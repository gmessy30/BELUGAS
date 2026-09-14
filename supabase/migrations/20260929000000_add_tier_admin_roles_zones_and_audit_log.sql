-- Item 101: admin roles (owner vs. zone-scoped admin), per-zone tier-code scoping, and an
-- admin_actions audit log covering every admin RPC (tier-code issue/revoke, article
-- publish/reject/unpublish).
--
-- DESIGN CHOICE FLAGGED FOR REVIEW: "revoke_tier_code refuses any code claimed by a device the
-- owner holds" requires SOME way to identify which tier_roster row(s) are "the owner's own
-- device(s)" -- there is no existing linkage between a tier_admins row (a Supabase Auth account)
-- and a tier_roster row (an anonymous per-device code, identified only by name/contact/device
-- free text) to derive this automatically. This migration adds an explicit
-- tier_roster.is_owner_device boolean, settable only at issuance (issue_tier_code's own
-- p_is_owner_device param, silently ignored/forced false unless the CALLER is actually the
-- owner) -- the owner marks their own code(s) this way when issuing themselves one. If a
-- different mechanism was intended (e.g. matching by a specific known name/contact string
-- instead), say so before this is applied -- string-matching felt too fragile/bug-prone to use
-- for a security boundary, so an explicit flag was chosen instead.
--
-- SCOPE NOTE: "only the owner can change another admin's zone or flags" is implemented as
-- zone-only (update_tier_admin_zone below) -- there is no owner-transfer RPC at all in this
-- migration. The only "flag" that exists is `owner` itself, and transferring ownership is
-- deliberately left as a manual, careful, out-of-band operation (direct SQL via
-- `supabase db query --linked`) rather than something exposed as a casual admin-page button --
-- see the partial unique index below for why a casual UI action here would be actively dangerous
-- (exactly one owner row is enforced at the DB level, so a careless transfer RPC could brick
-- ownership entirely if it raced or half-completed).
--
-- STATEMENT ORDER (see 20260923000000's own header comment on why this matters): every schema
-- change runs before any function that references it.
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor.
-- Per CLAUDE.md's own "Hard rule": this touches tier_roster directly -- DO NOT apply without the
-- user's explicit go-ahead after reviewing this diff, regardless of how mechanical it looks.

-- =========================================================================================
-- SCHEMA CHANGES
-- =========================================================================================

alter table public.tier_admins
  add column if not exists owner boolean not null default false,
  add column if not exists zone_slug text not null default 'kenai' references public.zones (slug);

-- Exactly one owner row, enforced at the database level (not just by RPC checks) -- a partial
-- unique index on a constant expression, scoped to rows where owner is true, so at most one such
-- row can ever exist.
create unique index if not exists tier_admins_one_owner_idx
  on public.tier_admins ((true))
  where owner;

alter table public.tier_roster
  add column if not exists issued_by uuid references auth.users (id) on delete set null,
  -- NOT NULL DEFAULT 'kenai' backfills every existing row to 'kenai' as part of this ALTER
  -- itself (Postgres fills existing tuples with the default when adding a NOT NULL column with
  -- one) -- satisfies "Backfill existing codes as zone 'kenai'" without a separate UPDATE.
  add column if not exists zone_slug text not null default 'kenai' references public.zones (slug),
  -- See this file's own header comment for why this is a flag rather than something derived.
  add column if not exists is_owner_device boolean not null default false;

create table if not exists public.admin_actions (
  id uuid primary key default gen_random_uuid(),
  admin_user_id uuid references auth.users (id) on delete set null,
  action text not null,
  target_table text not null,
  target_id uuid,
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table public.admin_actions enable row level security;
-- Zero direct policies for anon/authenticated -- same "the RPC is the only door in" posture as
-- tier_roster/tier_admins themselves. Read access is list_admin_actions (owner-only) below; there
-- is no direct-write path at all, only log_admin_action, called internally by the RPCs below.

-- Mark the existing (and, before this migration, only) admin as owner, and backfill their own
-- past issuances -- both explicitly, not inferred, since "who is the owner" and "who issued the
-- existing codes" are facts, not guesses.
update public.tier_admins set owner = true
where user_id = '993183af-1bce-45f0-b6bf-da7c4960cb1e'; -- keeneyeapps@gmail.com, confirmed live

update public.tier_roster set issued_by = '993183af-1bce-45f0-b6bf-da7c4960cb1e'
where issued_by is null;


-- =========================================================================================
-- HELPER FUNCTIONS
-- =========================================================================================

create or replace function public.is_tier_owner()
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (select 1 from public.tier_admins where user_id = auth.uid() and owner)
$$;

revoke all on function public.is_tier_owner() from public;
grant execute on function public.is_tier_owner() to authenticated;

-- The caller's own zone -- null for a non-admin caller (harmless: every call site below already
-- gates on is_tier_admin()/is_tier_owner() first, so this is never consulted for one).
create or replace function public.caller_admin_zone_slug()
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select zone_slug from public.tier_admins where user_id = auth.uid()
$$;

revoke all on function public.caller_admin_zone_slug() from public;
grant execute on function public.caller_admin_zone_slug() to authenticated;

-- Any authenticated caller may look up THEIR OWN admin status/zone (self-scoped via auth.uid(),
-- same reasoning is_tier_admin() itself already relies on) -- used by the admin page to show
-- "whose codes am I looking at" in its own header right after login, for every admin, not just
-- the owner. Zero rows back for a non-admin caller, not an error.
create or replace function public.get_my_admin_info()
returns table (zone_slug text, owner boolean)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select zone_slug, owner from public.tier_admins where user_id = auth.uid()
$$;

revoke all on function public.get_my_admin_info() from public;
grant execute on function public.get_my_admin_info() to authenticated;

-- Internal only -- never granted to anon/authenticated directly. Called from inside the other
-- SECURITY DEFINER functions below, which is enough for them to call it (a security definer
-- function's own internal calls run under the DEFINER's role, same reason revoke_tier_code has
-- always been able to call is_tier_admin() with no separate grant between them).
create or replace function public.log_admin_action(
  p_action text,
  p_target_table text,
  p_target_id uuid,
  p_detail jsonb default '{}'::jsonb
)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  insert into public.admin_actions (admin_user_id, action, target_table, target_id, detail)
  values (auth.uid(), p_action, p_target_table, p_target_id, p_detail);
end;
$$;

revoke all on function public.log_admin_action(text, text, uuid, jsonb) from public;


-- =========================================================================================
-- issue_tier_code: DROP + CREATE (parameter list is changing) -- adds p_zone_slug (owner-only
-- override; ignored for a non-owner caller, who always issues into their own zone regardless of
-- what's passed) and p_is_owner_device (silently forced false unless the caller is actually the
-- owner). Now logs to admin_actions.
-- =========================================================================================

drop function if exists public.issue_tier_code(text, text, smallint, text);

create function public.issue_tier_code(
  p_name text,
  p_contact text,
  p_tier smallint,
  p_device text default null,
  p_zone_slug text default null,
  p_is_owner_device boolean default false
)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_code text;
  v_roster_id uuid;
  v_is_owner boolean;
  v_caller_zone text;
  v_effective_zone text;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  if p_tier not in (1, 2) then
    raise exception 'invalid_tier';
  end if;

  v_is_owner := public.is_tier_owner();
  v_caller_zone := public.caller_admin_zone_slug();

  -- Owner may target any zone (defaults to their own if omitted); a non-owner admin's own zone
  -- is used regardless of what they pass -- not merely validated-and-rejected, so a crafted
  -- request can't slip a different zone through.
  v_effective_zone := case when v_is_owner then coalesce(p_zone_slug, v_caller_zone) else v_caller_zone end;

  insert into public.tier_roster (name, contact, tier, device, zone_slug, issued_by, is_owner_device)
  values (p_name, p_contact, p_tier, p_device, v_effective_zone, auth.uid(), v_is_owner and p_is_owner_device)
  returning id, code into v_roster_id, v_code;

  perform public.log_admin_action(
    'issue_tier_code', 'tier_roster', v_roster_id,
    jsonb_build_object('name', p_name, 'tier', p_tier, 'zone_slug', v_effective_zone)
  );

  return v_code;
end;
$$;

revoke all on function public.issue_tier_code(text, text, smallint, text, text, boolean) from public;
grant execute on function public.issue_tier_code(text, text, smallint, text, text, boolean) to authenticated;


-- =========================================================================================
-- list_tier_codes: DROP + CREATE (return shape is changing) -- adds zone_slug/issued_by/
-- issued_by_email/is_owner_device, and scopes non-owner admins to their own zone only.
-- =========================================================================================

drop function if exists public.list_tier_codes();

create function public.list_tier_codes()
returns table (
  id uuid,
  name text,
  contact text,
  device text,
  tier smallint,
  code text,
  is_used boolean,
  claimed_at timestamptz,
  created_at timestamptz,
  zone_slug text,
  issued_by uuid,
  issued_by_email text,
  is_owner_device boolean
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

  if public.is_tier_owner() then
    return query
      select r.id, r.name, r.contact, r.device, r.tier, r.code, r.is_used, r.claimed_at, r.created_at,
             r.zone_slug, r.issued_by, u.email, r.is_owner_device
      from public.tier_roster r
      left join auth.users u on u.id = r.issued_by
      order by r.created_at desc;
  else
    return query
      select r.id, r.name, r.contact, r.device, r.tier, r.code, r.is_used, r.claimed_at, r.created_at,
             r.zone_slug, r.issued_by, u.email, r.is_owner_device
      from public.tier_roster r
      left join auth.users u on u.id = r.issued_by
      where r.zone_slug = public.caller_admin_zone_slug()
      order by r.created_at desc;
  end if;
end;
$$;

revoke all on function public.list_tier_codes() from public;
grant execute on function public.list_tier_codes() to authenticated;


-- =========================================================================================
-- revoke_tier_code: same signature (CREATE OR REPLACE is fine) -- adds the zone-scoping check,
-- the owner-device protection, and admin_actions logging.
-- =========================================================================================

create or replace function public.revoke_tier_code(p_code text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row_count int;
  v_target_id uuid;
  v_target_zone text;
  v_target_is_owner_device boolean;
  v_is_owner boolean;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  select id, zone_slug, is_owner_device
    into v_target_id, v_target_zone, v_target_is_owner_device
  from public.tier_roster
  where public.normalize_tier_code(code) = public.normalize_tier_code(p_code);

  if v_target_id is null then
    return false; -- no such code -- same "nothing to do" result as before, not an authorization question
  end if;

  v_is_owner := public.is_tier_owner();

  -- Owner-device protection first, regardless of zone -- a non-owner admin can never revoke a
  -- code the owner has flagged as their own device, even one that happens to sit in that
  -- admin's own zone.
  if v_target_is_owner_device and not v_is_owner then
    raise exception 'owner_device_protected';
  end if;

  if not v_is_owner and v_target_zone is distinct from public.caller_admin_zone_slug() then
    raise exception 'not_authorized';
  end if;

  update public.tier_roster
     set claimed_subscriber_id = null
   where id = v_target_id;

  get diagnostics v_row_count = row_count;

  perform public.log_admin_action(
    'revoke_tier_code', 'tier_roster', v_target_id,
    jsonb_build_object('zone_slug', v_target_zone)
  );

  return v_row_count > 0;
end;
$$;


-- =========================================================================================
-- Owner-only tier_admins management. add_tier_admin takes an email (the account is created
-- manually in the Supabase dashboard first, per admin.js's own header comment -- the owner then
-- adds that already-existing auth.users account here by email, not by UUID, since that's what
-- the owner actually has on hand). No RPC anywhere sets/changes the `owner` flag itself -- see
-- this file's own header comment on why that's deliberately out of scope here.
-- =========================================================================================

create or replace function public.list_tier_admins()
returns table (user_id uuid, email text, owner boolean, zone_slug text)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  return query
    select ta.user_id, u.email, ta.owner, ta.zone_slug
    from public.tier_admins ta
    join auth.users u on u.id = ta.user_id
    order by ta.owner desc, u.email;
end;
$$;

revoke all on function public.list_tier_admins() from public;
grant execute on function public.list_tier_admins() to authenticated;


create or replace function public.add_tier_admin(p_email text, p_zone_slug text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid;
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  select id into v_user_id from auth.users where email = p_email;
  if v_user_id is null then
    raise exception 'user_not_found';
  end if;

  insert into public.tier_admins (user_id, zone_slug, owner)
  values (v_user_id, p_zone_slug, false)
  on conflict (user_id) do update set zone_slug = excluded.zone_slug;

  perform public.log_admin_action(
    'add_tier_admin', 'tier_admins', v_user_id,
    jsonb_build_object('zone_slug', p_zone_slug, 'email', p_email)
  );
end;
$$;

revoke all on function public.add_tier_admin(text, text) from public;
grant execute on function public.add_tier_admin(text, text) to authenticated;


create or replace function public.remove_tier_admin(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_target_is_owner boolean;
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  select owner into v_target_is_owner from public.tier_admins where user_id = p_user_id;
  -- Blocks EVERYONE, including the owner acting on their own row -- "so I can't lock myself out
  -- by accident" is the whole point, so this check does not special-case p_user_id = auth.uid().
  if v_target_is_owner then
    raise exception 'cannot_remove_owner';
  end if;

  delete from public.tier_admins where user_id = p_user_id;

  perform public.log_admin_action('remove_tier_admin', 'tier_admins', p_user_id, '{}'::jsonb);
end;
$$;

revoke all on function public.remove_tier_admin(uuid) from public;
grant execute on function public.remove_tier_admin(uuid) to authenticated;


create or replace function public.update_tier_admin_zone(p_user_id uuid, p_zone_slug text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  update public.tier_admins set zone_slug = p_zone_slug where user_id = p_user_id;

  perform public.log_admin_action(
    'update_tier_admin_zone', 'tier_admins', p_user_id,
    jsonb_build_object('zone_slug', p_zone_slug)
  );
end;
$$;

revoke all on function public.update_tier_admin_zone(uuid, text) from public;
grant execute on function public.update_tier_admin_zone(uuid, text) to authenticated;


-- =========================================================================================
-- list_admin_actions: the audit log's own read path, owner-only.
-- =========================================================================================

create or replace function public.list_admin_actions(p_limit int default 200)
returns table (
  id uuid,
  admin_user_id uuid,
  admin_email text,
  action text,
  target_table text,
  target_id uuid,
  detail jsonb,
  created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  return query
    select aa.id, aa.admin_user_id, u.email, aa.action, aa.target_table, aa.target_id, aa.detail, aa.created_at
    from public.admin_actions aa
    left join auth.users u on u.id = aa.admin_user_id
    order by aa.created_at desc
    limit least(p_limit, 1000);
end;
$$;

revoke all on function public.list_admin_actions(int) from public;
grant execute on function public.list_admin_actions(int) to authenticated;


-- =========================================================================================
-- set_article_status: same signature (CREATE OR REPLACE is fine) -- adds admin_actions logging
-- only, everything else (item 91's own logic) unchanged.
-- =========================================================================================

create or replace function public.set_article_status(
  p_id uuid,
  p_status text,
  p_title text default null,
  p_summary text default null
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row_count int;
  v_action text;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  if p_status not in ('published', 'rejected', 'pending_review') then
    raise exception 'invalid_status';
  end if;

  update public.articles
     set status = p_status::public.article_status,
         title = coalesce(p_title, title),
         summary = coalesce(p_summary, summary),
         reviewed_by = auth.uid(),
         reviewed_at = now()
   where id = p_id;

  get diagnostics v_row_count = row_count;

  v_action := case p_status
    when 'published' then 'publish_article'
    when 'rejected' then 'reject_article'
    else 'unpublish_article'
  end;
  perform public.log_admin_action(v_action, 'articles', p_id, jsonb_build_object('status', p_status));

  return v_row_count > 0;
end;
$$;

revoke all on function public.set_article_status(uuid, text, text, text) from public;
grant execute on function public.set_article_status(uuid, text, text, text) to authenticated;
