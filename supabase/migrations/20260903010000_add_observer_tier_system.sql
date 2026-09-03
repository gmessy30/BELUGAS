-- Observer tier system: a small admin-managed roster that lets a trusted observer claim an
-- elevated tier (1 or 2) on their device via a one-time code, entered through a hidden screen
-- (AboutScreen's 7-tap gesture, see AboutScreen.kt). Tier lookup is always a server-side join
-- against this roster by subscriber_id -- never a client-asserted claim -- and every sighting
-- records the tier that applied AT SUBMISSION TIME, so past sightings keep whatever tier
-- applied when they were made regardless of later roster changes.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- TIER_ROSTER: admin-managed directly in Supabase (dashboard/service role) -- rows are
-- created manually ahead of time, revoked by clearing claimed_subscriber_id (leaving is_used
-- true and the rest of the row intact, per the reviewed design -- a cleared code stays
-- permanently burned, it is never recycled onto a different person).
--
-- NO RLS POLICIES AND NO GRANTS TO anon/authenticated AT ALL -- unlike every other table in
-- this app (zones/point_presets are public-read, subscriptions/device_tokens are anon
-- read/write), this table holds real names and contact info and must never be readable or
-- writable by the anon key. The only door in is redeem_tier_code below, a narrow SECURITY
-- DEFINER function. The admin's own access is via the Supabase dashboard (service role,
-- which bypasses RLS entirely) -- RLS being enabled with zero policies denies every other
-- role by construction, so there is nothing further to lock down here.
-- =========================================================================================
create table if not exists public.tier_roster (
  id uuid primary key default gen_random_uuid(),

  -- Admin's label for who this is -- never surfaced in-app.
  name text not null,

  -- Admin's own reference only (email or similar) -- never surfaced in-app, no verification
  -- tied to it. Purely a "who do I contact about this row" note for the admin.
  contact text,

  -- One-time claim code. Default-generated (12 uppercase hex chars off a fresh UUID) so the
  -- admin doesn't have to hand-roll random strings when creating a row -- they can still type
  -- their own value instead if they want to.
  code text not null unique
    default upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)),

  -- Intended tier. Exactly {1, 2} per the reviewed design -- flagging that this range wasn't
  -- independently re-derived in this session (it's carried over from an earlier conversation
  -- about RED-eligibility/the confidence filter), so confirm it's still right if those tiers'
  -- meaning ever changes.
  tier smallint not null check (tier in (1, 2)),

  -- Set by redeem_tier_code on successful redemption. Nullable -- unclaimed until then, and
  -- cleared (not the row deleted) on revoke.
  claimed_subscriber_id uuid,

  -- Independent of claimed_subscriber_id being non-null: flips true on redemption and stays
  -- true forever after, including through a revoke, so a burned code can never be replayed
  -- onto a second device.
  is_used boolean not null default false,

  created_at timestamptz not null default now(),
  claimed_at timestamptz
);

alter table public.tier_roster enable row level security;
-- Deliberately no policies -- see the table's own comment above.

-- Keeps get_observer_tier's lookup unambiguous: a subscriber_id can be the live claim on at
-- most one roster row at a time. A revoke (clearing claimed_subscriber_id to null) frees this
-- immediately, so re-claiming a fresh code later is unaffected.
create unique index if not exists tier_roster_claimed_subscriber_id_uidx
  on public.tier_roster (claimed_subscriber_id)
  where claimed_subscriber_id is not null;


-- =========================================================================================
-- get_observer_tier: the one join point every tier consumer (this migration's own sightings
-- trigger below, and future RED-eligibility/confidence-filter logic) calls through, so "what
-- tier is this subscriber" is never re-implemented as a second copy that could drift.
--
-- SECURITY DEFINER (owned by the migration role, same pattern as notify_new_sighting in
-- 20260823000000_add_device_tokens_and_notify_trigger.sql) -- callers reach this from the
-- anon role, which has no grant on tier_roster at all; running as the function's owner
-- sidesteps needing to grant anon direct access to that table. search_path pinned since this
-- function's whole purpose is an access-control decision -- unlike notify_new_sighting, worth
-- the extra hardening here.
--
-- Returns null (no tier) for a subscriber_id with no live claimed row -- the default/ordinary
-- case for every observer.
-- =========================================================================================
create or replace function public.get_observer_tier(p_subscriber_id uuid)
returns smallint
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select tier
  from public.tier_roster
  where claimed_subscriber_id = p_subscriber_id
    and is_used = true
  limit 1
$$;


-- =========================================================================================
-- redeem_tier_code: the single narrow door into tier_roster for the app. Looks up the entered
-- code and, if it's valid and unused, atomically claims it for p_subscriber_id -- the update's
-- own `where code = p_code and is_used = false` is what makes this race-safe (a second
-- simultaneous attempt at the same code simply matches zero rows once the first commits).
--
-- Returns the assigned tier on success, null on any failure (bad code, already-used code, or
-- this subscriber_id already holding a different live claim -- unique_violation on the
-- claimed_subscriber_id index above) -- deliberately the same null for every failure case, so
-- the claim screen can't be used to enumerate which codes exist or are already taken.
-- =========================================================================================
create or replace function public.redeem_tier_code(p_code text, p_subscriber_id uuid)
returns smallint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_tier smallint;
begin
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
-- SIGHTINGS: subscriber_id (stored, but never anon-readable) + observer_tier (server-computed,
-- anon-readable) -- see this migration's header for why tier-at-submission is its own stored
-- column rather than always re-deriving it live from tier_roster.
-- =========================================================================================
alter table public.sightings
  add column if not exists subscriber_id uuid,
  add column if not exists observer_tier smallint;

-- BEFORE INSERT, not AFTER: overwrites whatever (if anything) arrived in observer_tier before
-- the row is ever persisted, so it can never be client-asserted even though subscriber_id
-- itself is client-sent (same trust model already used for every other client-computed field
-- on this table -- see is_geofence_verified's own "KNOWN GAP" comment in
-- 20260819000000_add_notification_zones_and_subscriptions.sql). Plain SECURITY INVOKER is
-- fine here (unlike get_observer_tier) -- it only ever touches NEW, which needs no privilege,
-- and get_observer_tier itself carries the elevation needed to read tier_roster.
create or replace function public.set_sighting_observer_tier()
returns trigger
language plpgsql
as $$
begin
  new.observer_tier := public.get_observer_tier(new.subscriber_id);
  return new;
end;
$$;

drop trigger if exists on_sighting_insert_set_observer_tier on public.sightings;
create trigger on_sighting_insert_set_observer_tier
before insert on public.sightings
for each row
execute function public.set_sighting_observer_tier();


-- =========================================================================================
-- COLUMN-LEVEL LOCKDOWN: subscriber_id must never be readable through any anon-key query --
-- not just export_sightings, the ordinary map/list reads too. Postgres column privileges only
-- restrict what a blanket table-level grant doesn't already cover, so the existing table-level
-- SELECT anon already holds must come off first, then be re-granted column-by-column for
-- every column except subscriber_id ("grant anon SELECT on the rest of the table as normal").
-- The admin can still read it directly via the service role/dashboard, which bypasses grants
-- entirely.
--
-- A bare `select *` (or PostgREST's own default no-`select=`-param request) needs privilege on
-- every column it expands to, so any anon-facing query that used to rely on that now needs an
-- explicit column list -- see export_sightings below (rewritten off `select s.*`) and
-- SupabaseApi.getSightings's Kotlin-side column list (SupabaseClient.kt).
-- =========================================================================================
revoke select on public.sightings from anon;

grant select (
  id, created_at, observer_id, lat, lng, heading,
  count_whites, count_greys, count_calves, count_unknown,
  photo_url, observed_at_epoch_ms, observer_type,
  heading_degrees, heading_source, heading_accuracy_degrees,
  distance_bucket, distance_radius_meters, is_geofence_verified,
  whale_lat, whale_lng, uncertainty_radius_meters, uncertainty_bucket,
  travel_bearing_degrees, travel_bearing_source, position_source,
  observer_tier
) on public.sightings to anon;


-- =========================================================================================
-- export_sightings: same parameters/behavior as before, just no longer `returns setof
-- public.sightings` / `select s.*` -- that would need anon SELECT on every column including
-- subscriber_id, which no longer exists. Explicit return table matches SightingRecord's own
-- shape (subscriber_id was never part of it). Changing the RETURNS shape is why this needs an
-- explicit DROP first -- CREATE OR REPLACE cannot change an existing function's return type,
-- only match_notification_recipients-style argument-signature changes (same reasoning as that
-- function's own DROP in 20260902010000_add_river_proximity_fallback_to_zone_matching.sql).
-- =========================================================================================
drop function if exists public.export_sightings(bigint, bigint, double precision, double precision, double precision, double precision, text);

create function public.export_sightings(
  p_start_ms bigint,
  p_end_ms bigint,
  p_min_lat double precision,
  p_max_lat double precision,
  p_min_lng double precision,
  p_max_lng double precision,
  p_zone_slug text default null
)
returns table (
  id uuid,
  created_at timestamptz,
  observer_id text,
  lat double precision,
  lng double precision,
  heading text,
  count_whites integer,
  count_greys integer,
  count_calves integer,
  count_unknown integer,
  photo_url text,
  observed_at_epoch_ms bigint,
  observer_type text,
  heading_degrees double precision,
  heading_source text,
  heading_accuracy_degrees double precision,
  distance_bucket text,
  distance_radius_meters double precision,
  is_geofence_verified boolean,
  whale_lat double precision,
  whale_lng double precision,
  uncertainty_radius_meters double precision,
  uncertainty_bucket text,
  travel_bearing_degrees double precision,
  travel_bearing_source text,
  position_source text,
  observer_tier smallint
)
language sql
stable
as $$
  select
    s.id, s.created_at, s.observer_id, s.lat, s.lng, s.heading,
    s.count_whites, s.count_greys, s.count_calves, s.count_unknown,
    s.photo_url, s.observed_at_epoch_ms, s.observer_type,
    s.heading_degrees, s.heading_source, s.heading_accuracy_degrees,
    s.distance_bucket, s.distance_radius_meters, s.is_geofence_verified,
    s.whale_lat, s.whale_lng, s.uncertainty_radius_meters, s.uncertainty_bucket,
    s.travel_bearing_degrees, s.travel_bearing_source, s.position_source,
    s.observer_tier
  from public.sightings s
  where s.observed_at_epoch_ms between p_start_ms and p_end_ms
    and s.whale_lat is not null and s.whale_lng is not null
    and s.whale_lat between p_min_lat and p_max_lat
    and s.whale_lng between p_min_lng and p_max_lng
    and (
      p_zone_slug is null
      or exists (
        select 1 from public.zones z
        where z.slug = p_zone_slug
          and public.is_whale_position_in_zone(z.id, s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
      )
    )
  order by s.observed_at_epoch_ms desc;
$$;

-- DROP removed the previous grant along with the old function -- Postgres would otherwise
-- default a freshly created function to PUBLIC EXECUTE, wider than this repo's convention
-- (anon only, matching every other RPC's explicit grant). Re-stated explicitly, not assumed.
grant execute on function public.export_sightings to anon;
