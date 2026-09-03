-- Route get_watched_zone_statuses and export_sightings onto whale_lat/whale_lng instead of the
-- retired observer-position lat/lng columns. See 20260901000000_add_whale_position_columns.sql
-- for why those are separate columns with separate meanings ("where the observer stood" vs. "the
-- estimated whale position") -- since the client stopped writing lat/lng once that redesign
-- landed, both RPCs below have been silently returning nothing for every sighting logged since
-- (confirmed live: get_watched_zone_statuses reported null/null for the kenai zone on
-- 2026-09-02 despite 3 verified SELF sightings with whale_lat/whale_lng populated in the
-- preceding 24h).
--
-- Clean column swap, not COALESCE(whale_lat, lat) -- a legacy (position_source is null) row's
-- lat/lng meant "observer position," never "whale position," so folding it in under the new
-- column would silently manufacture a whale position nobody recorded. Legacy rows are already
-- invisible to match_notification_recipients for the equivalent reason (see that function's own
-- p_lat/p_lng nullability comment) -- this migration keeps that same posture rather than
-- special-casing legacy rows back in here.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

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
    and ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(s.whale_lng, s.whale_lat), 4326))
  where z.is_banner_watched
  group by z.id, z.slug, z.name
$$;

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
          and ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(s.whale_lng, s.whale_lat), 4326))
      )
    )
  order by s.observed_at_epoch_ms desc;
$$;

-- Signatures are unchanged, so create or replace preserves the existing anon EXECUTE grants on
-- both functions (from 20260829020000_add_beluga_presence_banner.sql and
-- 20260827000000_add_export_sightings_rpc.sql) -- no re-grant needed.
