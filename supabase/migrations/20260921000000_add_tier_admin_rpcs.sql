-- Admin page for issuing observer tier codes (webapp/admin/) -- Supabase Auth (email+password,
-- accounts created manually in the dashboard: Authentication -> Users -> Add user) gates WHO can
-- log in at all; tier_admins below gates WHO, once logged in, is actually allowed to touch
-- tier_roster -- being merely an authenticated user is deliberately not enough on its own, only a
-- user_id listed in tier_admins is.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically. After running it, add at least
-- one admin manually (there is no self-service way to become one, by design):
--   insert into public.tier_admins (user_id) values ('<the auth.users.id of the account you created>');


-- =========================================================================================
-- TIER_ADMINS: the actual admin allow-list. One row per admin, referencing the Supabase Auth
-- user created manually in the dashboard -- this table does no account creation/password
-- management itself, it only says "this already-authenticated user is additionally an admin."
--
-- NO RLS POLICIES AND NO GRANTS TO anon/authenticated -- same posture as tier_roster itself (see
-- that table's own comment in 20260903010000_add_observer_tier_system.sql): the only way any
-- role ever learns whether a given auth.uid() is in here is through is_tier_admin() below,
-- never a direct table read.
-- =========================================================================================
create table if not exists public.tier_admins (
  user_id uuid primary key references auth.users (id) on delete cascade
);

alter table public.tier_admins enable row level security;
-- Deliberately no policies -- see the table's own comment above.


-- =========================================================================================
-- DEVICE note -- the admin's own label for which of a person's devices this row/code belongs to
-- (e.g. "iPhone", "work tablet"), never surfaced to the observer. The tier_roster model is
-- already one row per device (see 20260910000000_widen_tier_roster_tier_check_to_include_
-- demoted_tier_3.sql's "REVOCATION POLICY, settled" note: "one code per device, a new code
-- issued on request") -- this just makes that fact visible in the admin UI itself, so a lead
-- issuing/revoking codes for one person's several devices can actually tell them apart instead
-- of only distinguishing by claim timestamp.
-- =========================================================================================
alter table public.tier_roster
  add column if not exists device text;


-- =========================================================================================
-- is_tier_admin: the one check every admin RPC below calls through, so "is this caller actually
-- an admin" is never re-implemented as a second copy that could drift (same reasoning as
-- get_observer_tier/normalize_tier_code being pulled out as their own shared functions
-- elsewhere in this system). SECURITY DEFINER + search_path pinned since, like
-- get_observer_tier, this function's entire purpose is an access-control decision -- it has to
-- run with elevated privilege to read tier_admins at all, since that table has no grant for
-- authenticated either.
-- =========================================================================================
create or replace function public.is_tier_admin()
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1 from public.tier_admins where user_id = auth.uid()
  )
$$;

revoke all on function public.is_tier_admin() from public;
-- No grant needed -- only ever called internally from the SECURITY DEFINER RPCs below, which
-- run under this function's owner's privileges regardless of the original caller's role (same
-- posture as normalize_tier_code/generate_crockford_short_code elsewhere in this system).


-- =========================================================================================
-- issue_tier_code: creates a new tier_roster row and returns its generated code (the same
-- generate_tier_code default -- 12 chars, grouped XXXX-XXXX-XXXX -- 20260905030000_group_tier_
-- code_to_expose_truncation.sql already made the default). Widened to FOUR arguments (p_device
-- added) rather than the three first sketched for this RPC, so the whole issue form (name,
-- contact, tier, device) is created in one atomic call instead of an insert-then-update.
-- =========================================================================================
create or replace function public.issue_tier_code(
  p_name text,
  p_contact text,
  p_tier smallint,
  p_device text default null
)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_code text;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  if p_tier not in (1, 2) then
    raise exception 'invalid_tier';
  end if;

  insert into public.tier_roster (name, contact, tier, device)
  values (p_name, p_contact, p_tier, p_device)
  returning code into v_code;

  return v_code;
end;
$$;

revoke all on function public.issue_tier_code(text, text, smallint, text) from public;
grant execute on function public.issue_tier_code(text, text, smallint, text) to authenticated;


-- =========================================================================================
-- list_tier_codes: the roster's own admin-facing read -- the only way any role ever reads
-- tier_roster back (anon has no grant on the table at all; authenticated has none directly
-- either, only through this function). Newest first, matching an admin's usual "what did I
-- just issue" workflow. Also doubles as this app's admin-status check: the client calls this
-- right after signing in, and any error back (not_authorized, raised above) means "logged in
-- but not an admin" -- see webapp/admin/admin.js.
-- =========================================================================================
create or replace function public.list_tier_codes()
returns table (
  id uuid,
  name text,
  contact text,
  device text,
  tier smallint,
  code text,
  is_used boolean,
  claimed_at timestamptz,
  created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  return query
    select r.id, r.name, r.contact, r.device, r.tier, r.code, r.is_used, r.claimed_at, r.created_at
    from public.tier_roster r
    order by r.created_at desc;
end;
$$;

revoke all on function public.list_tier_codes() from public;
grant execute on function public.list_tier_codes() to authenticated;


-- =========================================================================================
-- revoke_tier_code: the ORDINARY revoke tier_roster's own header comment describes (clears
-- claimed_subscriber_id, leaves is_used/tier/the rest of the row intact -- a burned code can
-- never be replayed onto a different person, per 20260903010000's own note) -- distinct from,
-- and not a replacement for, the separate tier=3 "demotion" path added in 20260910000000_widen_
-- tier_roster_tier_check_to_include_demoted_tier_3.sql for a lost/stolen/dead device. This
-- function does not touch tier at all, only claimed_subscriber_id. Matches on
-- normalize_tier_code(code) (not raw equality), same tolerance for hyphens/spaces/case
-- redeem_tier_code already has, since an admin is just as likely to hand-type/paste a code off
-- the same slip of paper.
-- =========================================================================================
create or replace function public.revoke_tier_code(p_code text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row_count int;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  update public.tier_roster
     set claimed_subscriber_id = null
   where public.normalize_tier_code(code) = public.normalize_tier_code(p_code);

  get diagnostics v_row_count = row_count;
  return v_row_count > 0;
end;
$$;

revoke all on function public.revoke_tier_code(text) from public;
grant execute on function public.revoke_tier_code(text) to authenticated;
