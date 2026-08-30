-- "Belugas present" safety banner, first pass: a persistent bottom bar (gated by proximity
-- or subscription) and unconditional river shading on the map, both using a simple
-- time-decayed RED/YELLOW/BLUE status per watched river/area.
--
-- Data-driven by design (the whole point of this migration): `is_banner_watched` is a plain
-- flag on the existing `zones` table, not a new hardcoded list -- watching a new area later
-- (Turnagain Arm, Anchorage, ...) is `update zones set is_banner_watched = true where slug =
-- '...'`, a data change, not a client release. Starting scope is Kenai only -- and since the
-- `kenai` zone's polygon already folds in the Kasilof River arm (see its seed comment in
-- 20260819130000_seed_notification_zones_and_point_presets.sql: "KENAI (folds in Kasilof)"),
-- watching that one zone covers both rivers for free.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

alter table public.zones
  add column if not exists is_banner_watched boolean not null default false;

update public.zones set is_banner_watched = true where slug = 'kenai';


-- =========================================================================================
-- get_watched_zone_statuses: raw sighting-recency facts for EVERY watched zone, no location
-- input at all. Two independent consumers:
--   1. Map river shading -- shown to everyone unconditionally, so it can't be gated by any
--      one user's location/subscriptions.
--   2. The bottom banner's status lookup, once the client has separately determined (via
--      find_nearby_watched_zone below, or this device's own subscriptions) which zone is
--      actually relevant to show.
--
-- Returns raw timestamps, not a computed color -- same reasoning as everywhere else in this
-- feature: PresenceBanner.kt decays RED/YELLOW/BLUE against its own tunable windows, so
-- tuning them doesn't need a migration/redeploy.
--
-- last_verified_sighting_epoch_ms = most recent sighting with observer_type='SELF' and
-- is_geofence_verified -- same "verified" definition the subscriptions confidence_filter
-- already established (20260819000000_add_notification_zones_and_subscriptions.sql).
-- last_any_sighting_epoch_ms = most recent sighting regardless of that.
--
-- p_lookback_ms bounds the sightings scan (sightings.lat/lng have no spatial index, so this
-- is a per-row ST_Contains scan, same performance profile export_sightings already has --
-- not a new regression). Callers should pass their own YELLOW-decay window so tuning that
-- constant client-side also narrows/widens what gets scanned.
--
-- Anon-executable, unlike Stage 3's match_notification_recipients: this only reads `zones`
-- and `sightings`, both already anon-readable.
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
    on s.lat is not null and s.lng is not null
    and s.observed_at_epoch_ms >= (extract(epoch from now()) * 1000)::bigint - p_lookback_ms
    and ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(s.lng, s.lat), 4326))
  where z.is_banner_watched
  group by z.id, z.slug, z.name
$$;

grant execute on function public.get_watched_zone_statuses to anon;


-- =========================================================================================
-- find_nearby_watched_zone: the closest watched zone within p_proximity_meters of a point,
-- if any -- one half of the bottom banner's visibility gate (the other half, "subscribed to
-- a watched zone," is a plain client-side check against this device's own subscriptions, no
-- RPC needed for that part).
--
-- Deliberately plain distance (ST_DWithin/ST_Distance on geography), not ST_Contains -- the
-- spec's whole point is that someone doesn't need to be inside the river to see the banner.
-- Geography distance to a polygon treats it as a filled area, so a point already inside
-- naturally comes back as distance 0 -- containment is the zero-distance special case of this,
-- not a separate check.
--
-- No sightings involved at all -- purely geometric, so this stays cheap regardless of
-- sightings volume.
-- =========================================================================================
create or replace function public.find_nearby_watched_zone(
  p_lat double precision,
  p_lng double precision,
  p_proximity_meters double precision default 15000
)
returns table (
  zone_id uuid,
  zone_slug text,
  zone_name text,
  distance_meters double precision
)
language sql
stable
as $$
  select
    z.id,
    z.slug,
    z.name,
    ST_Distance(z.boundary::geography, ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326)::geography) as distance_meters
  from public.zones z
  where z.is_banner_watched
    and p_lat is not null and p_lng is not null
    and ST_DWithin(
      z.boundary::geography,
      ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326)::geography,
      p_proximity_meters
    )
  order by distance_meters asc
  limit 1
$$;

grant execute on function public.find_nearby_watched_zone to anon;
