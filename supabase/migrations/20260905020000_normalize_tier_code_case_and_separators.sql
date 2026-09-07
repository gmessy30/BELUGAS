-- Closes a case-sensitivity gap found testing the manual-fallback migration
-- (20260905010000_add_tier_code_rate_limiting_and_identity_bind.sql): admin_bind_tier_code
-- already normalizes its short_code input (upper() + strip non-alphanumeric) before comparing,
-- but redeem_tier_code's `where code = p_code` was a plain case-sensitive equality -- so a
-- lowercase-typed code (or one with a stray space/hyphen from a copy-paste) fails against the
-- uppercase, separator-free codes tier_roster.code actually stores by default. Backwards from
-- where the forgiveness is needed: the self-service path is the one a person types by hand off
-- a slip of paper or a screen, so it's the one that most needs to tolerate exactly this.
--
-- NORMALIZE_TIER_CODE: pulled out as its own function, called by BOTH redeem_tier_code and
-- admin_bind_tier_code, rather than each keeping its own copy of the same regex -- that
-- duplication is exactly how they drifted apart the first time (admin_bind_tier_code got this
-- treatment when it was written; redeem_tier_code didn't, until now). One implementation means
-- the two paths can't silently diverge again later.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

create or replace function public.normalize_tier_code(p_code text)
returns text
language sql
immutable
as $$
  select upper(regexp_replace(p_code, '[^0-9A-Za-z]', '', 'g'))
$$;

revoke all on function public.normalize_tier_code(text) from public;
-- No grant needed -- only ever called from inside redeem_tier_code/admin_bind_tier_code, both
-- SECURITY DEFINER, so it runs under their owner's privileges regardless of who the original
-- caller is (same reasoning as generate_crockford_short_code's own revoke-only posture).


-- =========================================================================================
-- redeem_tier_code: now normalizes p_code the same way admin_bind_tier_code normalizes
-- p_short_code, before the tier_roster lookup. Everything else (the rate-limit gate, the
-- attempt log insert, the unique_violation handler for an existing different claim) is
-- unchanged from 20260905010000 -- only the comparison itself gets normalized, not the codes
-- stored in tier_roster.code, so this stays compatible with every code already issued.
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
   where code = v_normalized_code
     and is_used = false
  returning tier into v_tier;

  return v_tier; -- null when the update matched no row
exception
  when unique_violation then
    return null;
end;
$$;

grant execute on function public.redeem_tier_code to anon;


-- =========================================================================================
-- admin_bind_tier_code: switched to normalize_tier_code instead of its own inline
-- regexp_replace, for both p_short_code and (new) p_tier_code -- the admin typing/pasting a
-- tier code by hand off the same slip of paper deserves the identical tolerance the self-
-- service path now gets, not a second, separately-maintained copy of the same fix.
-- =========================================================================================
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
   where code = v_normalized_tier_code
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
