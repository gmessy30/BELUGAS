-- River-proximity fallback for zone containment. ST_Contains can never be true for a whale
-- position that sits directly on a zone's river-spike trace -- a zero-area out-and-back spike
-- (see 20260831020000_replace_kenai_zone_river_spikes.sql) encloses no area by construction, no
-- matter how closely it's traced to the real riverbank. Confirmed against two live sightings
-- this morning: 5ff7ec04 sits 14.9m from the kenai zone's Kenai River spike, 681526fe sits
-- 276.8m from it -- neither is a boundary-accuracy problem, both are real river-channel points
-- that ST_Contains structurally cannot enclose.
--
-- This migration adds public.is_whale_position_in_zone(), a drop-in "is this point in this
-- zone" check that tries ST_Contains first (unchanged behavior for every non-river case) and
-- falls back to river-spike proximity only when that fails: distance from the point to the
-- zone's river-spike vertices, accepted when within the sighting's own uncertainty_radius_meters,
-- capped at 1000m -- the same cap GeofenceUtils.isWhalePositionVerified already uses
-- client-side, so a position the app itself would call "verified enough" isn't second-guessed
-- server-side with a tighter bar.
--
-- Kenai-only for now, deliberately hardcoded rather than a generic per-zone mechanism: the only
-- watched zone with a documented river spike (ring vertices 12-190, isolated and verified
-- against the two live test points this morning) is `kenai`. A future watched zone with its own
-- river spike needs its own vertex range added to this function -- not attempting to guess that
-- shape generically from an arbitrary zone polygon.
--
-- Wired into all three places that decide "is this whale position in this zone": the two
-- alongside-ST_Contains callers, get_watched_zone_statuses and export_sightings, plus
-- match_notification_recipients's zone-kind subscription branch (custom_polygon and
-- point_radius subscriptions are untouched -- this is specifically a river-spike/zone-polygon
-- problem).
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

create or replace function public.is_whale_position_in_zone(
  p_zone_id uuid,
  p_lng double precision,
  p_lat double precision,
  p_uncertainty_radius_meters double precision default null
)
returns boolean
language sql
stable
as $$
  with z as (
    select id, slug, boundary from public.zones where id = p_zone_id
  ),
  pt as (
    select ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326) as geom
  ),
  spike_line as (
    -- Kenai River spike trace: ring vertices 12-190 of the kenai zone's polygon (11 head
    -- coastline vertices + the 179-vertex KPB centerline spike, per
    -- 20260831020000_replace_kenai_zone_river_spikes.sql's own vertex-count breakdown).
    -- Produces zero rows for every non-kenai zone, so the fallback below simply never fires
    -- for them.
    select ST_MakeLine(v.geom order by v.idx) as line
    from z, lateral (
      select (dp).path[1] as idx, (dp).geom as geom
      from ST_DumpPoints(ST_ExteriorRing(z.boundary)) as dp
    ) v
    where z.slug = 'kenai'
      and v.idx between 12 and 190
  )
  select
    exists (select 1 from z, pt where ST_Contains(z.boundary, pt.geom))
    or (
      p_uncertainty_radius_meters is not null
      and (select line from spike_line) is not null
      and ST_Distance(
            (select line from spike_line)::geography,
            (select geom from pt)::geography
          ) <= least(p_uncertainty_radius_meters, 1000)
    )
$$;

grant execute on function public.is_whale_position_in_zone to anon;
grant execute on function public.is_whale_position_in_zone to service_role;


-- =========================================================================================
-- get_watched_zone_statuses: same signature as before, now delegates zone containment to
-- is_whale_position_in_zone (ST_Contains + river-spike fallback) instead of a bare ST_Contains
-- call.
-- =========================================================================================
create or replace function public.get_watched_zone_statuses(
  p_lookback_ms bigint default 86400000
)
returns table (
  zone_id uuid,
  zone_slug text,
  zone_name text,
  last_verified_sighting_epoch_ms bigint,
  last_any_sighting_epoch_ms bigint
)
language sql
stable
as $$
  select
    z.id,
    z.slug,
    z.name,
    max(s.observed_at_epoch_ms) filter (
      where s.observer_type = 'SELF' and s.is_geofence_verified
    ),
    max(s.observed_at_epoch_ms)
  from public.zones z
  left join public.sightings s
    on s.whale_lat is not null and s.whale_lng is not null
    and s.observed_at_epoch_ms >= (extract(epoch from now()) * 1000)::bigint - p_lookback_ms
    and public.is_whale_position_in_zone(z.id, s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
  where z.is_banner_watched
  group by z.id, z.slug, z.name
$$;


-- =========================================================================================
-- export_sightings: same signature as before, zone filter now delegates to
-- is_whale_position_in_zone the same way.
-- =========================================================================================
create or replace function public.export_sightings(
  p_start_ms bigint,
  p_end_ms bigint,
  p_min_lat double precision,
  p_max_lat double precision,
  p_min_lng double precision,
  p_max_lng double precision,
  p_zone_slug text default null
)
returns setof public.sightings
language sql
stable
as $$
  select s.*
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


-- =========================================================================================
-- match_notification_recipients: gains a new trailing p_uncertainty_radius_meters parameter
-- (defaulted, so any caller that doesn't pass it gets the old ST_Contains-only behavior for the
-- zone branch -- ST_Distance against a null cap never passes). Adding a parameter changes the
-- function's argument-type signature, so CREATE OR REPLACE would create a second overload
-- instead of replacing this one in place -- the old 4-arg version is dropped first so there is
-- only ever one match_notification_recipients, avoiding PostgREST overload-resolution ambiguity
-- when called with just the original 4 named arguments.
-- =========================================================================================
drop function if exists public.match_notification_recipients(double precision, double precision, text, boolean);

create function public.match_notification_recipients(
  p_lat double precision,
  p_lng double precision,
  p_observer_type text,
  p_is_geofence_verified boolean,
  p_uncertainty_radius_meters double precision default null
)
returns table(fcm_token text)
language sql
stable
as $$
  with sighting_point as (
    select ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326) as geom
  ),
  active_subscriptions as (
    select s.*
    from public.subscriptions s
    where s.is_active
      and (s.expires_at is null or s.expires_at > now())
  ),
  matched_subscriber_ids as (
    select distinct s.subscriber_id
    from active_subscriptions s, sighting_point sp
    where (
      s.confidence_filter = 'all'
      or (s.confidence_filter = 'verified_only' and p_observer_type = 'SELF' and p_is_geofence_verified)
    )
    and (
      (s.kind = 'zone' and public.is_whale_position_in_zone(s.zone_id, p_lng, p_lat, p_uncertainty_radius_meters))
      or (s.kind = 'custom_polygon' and ST_Contains(s.custom_polygon, sp.geom))
      or (s.kind = 'point_radius' and ST_DWithin(s.point, sp.geom::geography, s.radius_meters))
    )
  ),
  opted_in_subscriber_ids as (
    select distinct subscriber_id from active_subscriptions
  )
  select dt.fcm_token
  from public.device_tokens dt
  where dt.subscriber_id in (select subscriber_id from matched_subscriber_ids)
  union
  select dt.fcm_token
  from public.device_tokens dt
  where dt.subscriber_id is null
     or dt.subscriber_id not in (select subscriber_id from opted_in_subscriber_ids)
$$;

revoke execute on function public.match_notification_recipients from public;
grant execute on function public.match_notification_recipients to service_role;
