-- Root cause of the "case sensitivity" rejections turned out to be truncation: the Supabase
-- dashboard's table editor renders the code column narrower than 12 characters, so reading a
-- code off it by eye silently drops the last couple characters -- exactly the failure mode an
-- admin reading a code aloud over the phone would also hit, not something specific to the
-- dashboard UI. A 10-character read of a 12-character contiguous hex string looks exactly like
-- a complete code; nothing about it signals "this is cut off." Widening the column (or
-- remembering to) fixes the symptom for one admin, one session -- this fixes it at the source
-- so a truncated read is visibly malformed instead of silently plausible, regardless of where
-- or how it's read from.
--
-- CHOSE GROUPING OVER SHORTENING: a shorter contiguous code has the identical problem at a
-- smaller scale -- still no internal structure signaling "this is the whole thing," just fewer
-- characters to lose. Grouping as XXXX-XXXX-XXXX (matching subscriber_identities.short_code's
-- own display convention) means a truncated read is missing a whole group or has a short final
-- group, both immediately recognizable as incomplete rather than a plausible-looking value.
-- Kept at 12 characters / 48 bits rather than also shortening -- grouping alone closes the
-- actual gap, so there's no reason to spend down entropy that isn't buying anything further.
--
-- Existing codes (ungrouped, already possibly emailed to someone) keep working unchanged --
-- normalize_tier_code strips every non-alphanumeric character before comparing, so "A1B2C3D4E5F6"
-- and "A1B2-C3D4-E5F6" normalize identically. This migration only changes the DEFAULT for
-- newly-created rows and how the comparison reaches the stored value, not any code already
-- issued.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- generate_tier_code: same source entropy as the original inline default (12 hex characters
-- off a fresh UUID), grouped 4-4-4 with dashes purely for readability -- the dashes carry no
-- information and are stripped by normalize_tier_code on every comparison.
-- =========================================================================================
create or replace function public.generate_tier_code()
returns text
language sql
as $$
  select left(s, 4) || '-' || substr(s, 5, 4) || '-' || substr(s, 9, 4)
  from (select upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)) as s) x
$$;

revoke all on function public.generate_tier_code() from public;
-- No grant needed -- only ever evaluated as tier_roster.code's own DEFAULT, i.e. only when the
-- admin inserts a row directly (service role / SQL editor -- tier_roster has no anon grant at
-- all, per its own table comment in 20260903010000_add_observer_tier_system.sql). Revoked
-- anyway for the same reason generate_crockford_short_code and normalize_tier_code are: this
-- migration doesn't leave anything on Postgres's default PUBLIC EXECUTE.

alter table public.tier_roster
  alter column code set default public.generate_tier_code();


-- =========================================================================================
-- redeem_tier_code / admin_bind_tier_code: both now normalize the STORED code column too, not
-- just the input -- `where normalize_tier_code(code) = v_normalized_code` instead of a raw
-- equality against the column. Needed now that tier_roster.code itself can contain dashes: the
-- input side was already normalized (strips dashes), but comparing that against a
-- dash-containing stored value would never match without normalizing both sides. This is what
-- makes new (grouped) and old (ungrouped) codes compare identically regardless of typed
-- formatting -- see this migration's header for why that backward compatibility matters.
-- =========================================================================================
create or replace function public.redeem_tier_code(p_code text, p_subscriber_id uuid)
returns smallint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_tier smallint;
  v_recent_attempts int;
  v_normalized_code text := public.normalize_tier_code(p_code);
begin
  select count(*) into v_recent_attempts
  from public.tier_code_redeem_attempts
  where subscriber_id = p_subscriber_id
    and attempted_at > now() - interval '1 hour';

  if v_recent_attempts >= 12 then
    raise exception 'rate_limited';
  end if;

  insert into public.tier_code_redeem_attempts (subscriber_id) values (p_subscriber_id);

  update public.tier_roster
     set claimed_subscriber_id = p_subscriber_id,
         is_used = true,
         claimed_at = now()
   where public.normalize_tier_code(code) = v_normalized_code
     and is_used = false
  returning tier into v_tier;

  return v_tier; -- null when the update matched no row
exception
  when unique_violation then
    return null;
end;
$$;

grant execute on function public.redeem_tier_code to anon;


create or replace function public.admin_bind_tier_code(p_short_code text, p_tier_code text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_subscriber_id uuid;
  v_normalized_short_code text := public.normalize_tier_code(p_short_code);
  v_normalized_tier_code text := public.normalize_tier_code(p_tier_code);
  v_row_count int;
begin
  select subscriber_id into v_subscriber_id
  from public.subscriber_identities
  where short_code = v_normalized_short_code;

  if v_subscriber_id is null then
    return false;
  end if;

  update public.tier_roster
     set claimed_subscriber_id = v_subscriber_id,
         is_used = true,
         claimed_at = now()
   where public.normalize_tier_code(code) = v_normalized_tier_code
     and is_used = false;

  get diagnostics v_row_count = row_count;
  return v_row_count > 0;
exception
  when unique_violation then
    return false;
end;
$$;

revoke all on function public.admin_bind_tier_code(text, text) from public;
revoke execute on function public.admin_bind_tier_code(text, text) from anon, authenticated;
grant execute on function public.admin_bind_tier_code(text, text) to service_role;
