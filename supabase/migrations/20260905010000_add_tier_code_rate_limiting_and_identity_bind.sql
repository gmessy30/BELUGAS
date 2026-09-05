-- Two additions to the observer-tier system (see 20260903010000_add_observer_tier_system.sql):
--   1. Generous per-device rate limiting on redeem_tier_code -- roughly a dozen attempts/hour,
--      throttling script-style hammering without a real person ever noticing it.
--   2. A manual fallback path for someone who can't manage the code-entry UI: a short,
--      read-aloud-safe id for their device, and an admin-only function to bind a tier code to
--      it by hand once they've phoned it in.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- TIER_CODE_REDEEM_ATTEMPTS: append-only log of every redeem_tier_code call, keyed by
-- subscriber_id (device), not by the code entered -- see redeem_tier_code's own comment below
-- for why per-device is the right axis. No RLS policies (same posture as tier_roster) -- the
-- only writer is redeem_tier_code itself, running SECURITY DEFINER; anon has no direct grant
-- on this table at all. Left unpruned deliberately -- at this app's expected redemption volume
-- (a handful of attempts per real observer, ever) this table stays tiny; a cleanup job would be
-- solving a problem that doesn't exist yet.
-- =========================================================================================
create table if not exists public.tier_code_redeem_attempts (
  id uuid primary key default gen_random_uuid(),
  subscriber_id uuid not null,
  attempted_at timestamptz not null default now()
);

create index if not exists tier_code_redeem_attempts_subscriber_window_idx
  on public.tier_code_redeem_attempts (subscriber_id, attempted_at);

alter table public.tier_code_redeem_attempts enable row level security;
-- Deliberately no policies -- see the table's own comment above.


-- =========================================================================================
-- redeem_tier_code: adds a rate-limit gate ahead of the original lookup/claim logic (unchanged
-- below the gate). Per-subscriber_id (device), not per-code: a per-code counter would be shared
-- by everyone who tries that code, so a stranger spamming (or mistyping) someone else's
-- assigned code would burn down the legitimate holder's own budget before they ever get to it.
-- Per-device is the only axis where whoever causes the cost also pays it.
--
-- ORDER MATTERS: the count check runs BEFORE the attempt is logged, and the log insert only
-- happens on the allowed path. This means a call made while already over the limit doesn't
-- extend the window further by adding another row -- the block expires based on the age of the
-- attempts that actually triggered it, not on how many more times someone hammers it afterward.
-- It also sidesteps any "insert then roll back on RAISE" concern entirely, since nothing is
-- written on the blocked path.
--
-- 12/hour is deliberately generous (the brief was "roughly a dozen," explicitly not a
-- three-strikes lockout) -- this isn't a brute-force defense (a 12-character hex code is 48
-- bits of entropy; no rate limit that stays usable for a retiree fat-fingering their own code
-- meaningfully slows an actual attacker). It's throttling against a script hammering the RPC,
-- while staying invisible to a real person.
--
-- On rate limit, RAISE EXCEPTION with the literal message 'rate_limited' (default SQLSTATE
-- P0001, raise_exception) -- PostgREST surfaces a raised exception's message verbatim in the
-- error response body, and the Kotlin client (SupabaseClient.kt's redeemTierCode) matches on
-- this exact string to distinguish "rate limited" from "bad/used code" without the RPC's return
-- shape needing to change for the success/invalid path. This deliberately reveals NOTHING about
-- code validity -- the message says only "you're going too fast," never whether the code(s)
-- tried were right, wrong, or already used, so it can't be used to enumerate valid codes any
-- more than the existing null-for-every-failure return already prevents.
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
   where code = p_code
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
-- SUBSCRIBER_IDENTITIES: a short, read-aloud-safe id for a subscriber_id, so someone who can't
-- manage the code-entry UI can phone one in. subscriber_id itself has no other server-side home
-- until it's used somewhere else (a subscription, a device token) -- this table exists so the
-- manual path works even for a device that's done nothing else yet.
--
-- short_code is generated server-side (generate_crockford_short_code below), never client-
-- supplied, and stored UNIQUE -- so there is never a collision to resolve later. That's the
-- actual answer to "how does the admin lookup handle a collision": it can't have one, by
-- construction, because a colliding candidate is rejected and retried at generation time
-- (see get_or_create_subscriber_identity), before it's ever shown to a user.
--
-- No RLS policies (same posture as tier_roster/tier_code_redeem_attempts) -- anon has no direct
-- grant on this table. The only doors in are get_or_create_subscriber_identity (anon, narrow,
-- see its own comment on why it can't be used to enumerate anyone else's row) and
-- admin_bind_tier_code (service_role only, see its own comment).
-- =========================================================================================
create table if not exists public.subscriber_identities (
  subscriber_id uuid primary key,
  short_code text not null unique,
  created_at timestamptz not null default now()
);

alter table public.subscriber_identities enable row level security;
-- Deliberately no policies -- see the table's own comment above.


-- =========================================================================================
-- generate_crockford_short_code: 8 characters from the Crockford Base32 alphabet (excludes
-- I, L, O, U -- easily confused with 1, 1, 0, V when read aloud or handwritten). 32^8 ≈ 1.1e12
-- possible values -- retried on collision by the caller, not because collisions are expected,
-- but so a collision is a non-event instead of a support problem.
-- =========================================================================================
create or replace function public.generate_crockford_short_code(p_length int default 8)
returns text
language plpgsql
as $$
declare
  v_alphabet text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  v_result text := '';
begin
  for i in 1..p_length loop
    v_result := v_result || substr(v_alphabet, (floor(random() * length(v_alphabet)) + 1)::int, 1);
  end loop;
  return v_result;
end;
$$;


-- =========================================================================================
-- get_or_create_subscriber_identity: idempotent -- returns the existing short_code if this
-- subscriber_id already has one, otherwise mints one. Called lazily from the app the first time
-- the hidden tier-claim gesture reveals the fallback panel, not on every launch, so a device
-- that never finds the gesture never creates a row here.
--
-- ANON-CALLABLE BY DESIGN, same posture as every other subscriber_id-keyed write in this app
-- (subscriptions, device_tokens) -- see 20260819000000_add_notification_zones_and_subscriptions
-- .sql's note on the no-real-auth trust model this is meant to fit into. It can only ever
-- create-or-return the row for the EXACT p_subscriber_id passed in -- there is no parameter or
-- code path that looks anything up BY short_code or returns any OTHER subscriber's row, so
-- knowing one subscriber_id gets you exactly that subscriber's own short code and nothing about
-- anyone else's. Reversing short_code back to a subscriber_id (what the admin needs for the
-- manual bind) is deliberately NOT exposed here at all -- that direction only exists in
-- admin_bind_tier_code, which is not anon-callable. This function is one-way by construction.
--
-- Race-safe: a unique_violation here means either (a) a concurrent call for this exact
-- subscriber_id already won -- re-select and return its short_code, or (b) the generated
-- short_code collided with someone else's -- generate a fresh one and retry. Distinguishing
-- these (rather than just "retry on any conflict") is what keeps case (a) from looping forever,
-- since retrying with a new short_code can never fix a conflict on the subscriber_id primary key.
-- =========================================================================================
create or replace function public.get_or_create_subscriber_identity(p_subscriber_id uuid)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_short_code text;
  v_attempt int := 0;
begin
  select short_code into v_short_code
  from public.subscriber_identities
  where subscriber_id = p_subscriber_id;

  if v_short_code is not null then
    return v_short_code;
  end if;

  loop
    v_short_code := public.generate_crockford_short_code();
    begin
      insert into public.subscriber_identities (subscriber_id, short_code)
      values (p_subscriber_id, v_short_code);
      return v_short_code;
    exception
      when unique_violation then
        select short_code into v_short_code
        from public.subscriber_identities
        where subscriber_id = p_subscriber_id;
        if v_short_code is not null then
          return v_short_code; -- a concurrent call already won -- use its code, not ours
        end if;
        v_attempt := v_attempt + 1;
        if v_attempt >= 10 then
          raise exception 'could not generate a unique short code after % attempts', v_attempt;
        end if;
        -- else: the short_code itself collided with someone else's -- loop and try a new one
    end;
  end loop;
end;
$$;

grant execute on function public.get_or_create_subscriber_identity to anon;


-- =========================================================================================
-- admin_bind_tier_code: the manual fallback's only write path -- looks up a short_code (phoned
-- in) to a subscriber_id, then performs the exact same claim redeem_tier_code would have. This
-- collapses the admin's original two-step manual process (look up the UUID, then hand-paste it
-- into an UPDATE) into one call that never surfaces the raw subscriber_id at all, removing the
-- highest-error-rate step (mistyping a 36-character UUID) from the workflow.
--
-- SERVICE ROLE ONLY -- NOT anon, NOT authenticated, deliberately and explicitly. This function
-- grants tier privileges by short_code alone, with no further proof of identity -- the short
-- code is shown on-screen specifically so it's easy to read/copy, which also makes it easy to
-- read over someone's shoulder or off a screenshot. If this were anon-callable with the same
-- grant as redeem_tier_code, anyone who saw a short code (not just the device it belongs to)
-- could bind a tier to it themselves, bypassing the one-time-code model entirely -- the code
-- itself stops being the credential. The admin's own judgment about who's actually calling
-- (the same judgment already exercised over who a tier_roster code gets emailed to in the first
-- place) is the only thing standing in for that missing proof, so this must only be reachable
-- from a context that already carries that judgment -- the Supabase dashboard / SQL editor via
-- the service role, never the public API.
--
-- DEPENDENCY TO NOT MISS: this writes tier_roster.claimed_subscriber_id directly, the same
-- single-claim-per-subscriber shape redeem_tier_code uses today. If/when the tier model moves
-- to a tier_claims table (one row per device, for the multi-device reshape), this function has
-- to move with it -- otherwise the manual path keeps writing the old single-claim shape while
-- the app-driven path writes the new one, and the two silently diverge. Whoever does that
-- migration needs to touch this function in the same change, not discover it later.
-- =========================================================================================
create or replace function public.admin_bind_tier_code(p_short_code text, p_tier_code text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_subscriber_id uuid;
  v_normalized_short_code text := upper(regexp_replace(p_short_code, '[^0-9A-Za-z]', '', 'g'));
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
   where code = p_tier_code
     and is_used = false;

  get diagnostics v_row_count = row_count;
  return v_row_count > 0;
exception
  when unique_violation then
    return false;
end;
$$;

-- Explicit revoke before the explicit grant, matching this repo's convention of never relying
-- on Postgres's default-PUBLIC-EXECUTE-on-create being left alone (see export_sightings in
-- 20260903010000_add_observer_tier_system.sql for the same pattern) -- especially load-bearing
-- here, where the whole point is that two roles must NEVER reach this function.
revoke all on function public.admin_bind_tier_code(text, text) from public;
revoke execute on function public.admin_bind_tier_code(text, text) from anon, authenticated;
grant execute on function public.admin_bind_tier_code(text, text) to service_role;
