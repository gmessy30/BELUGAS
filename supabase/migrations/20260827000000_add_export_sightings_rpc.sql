-- Data-export feature: a `export_sightings` RPC the app calls to fetch sightings filtered by
-- date range and, optionally, a named zone from `public.zones` (see
-- 20260819000000_add_notification_zones_and_subscriptions.sql for that table).
--
-- Zone containment (ST_Contains against a real polygon) has to happen in PostGIS -- the
-- polygon geometry only exists in this database, so this is the one correct place for that
-- check to live rather than re-implementing point-in-polygon client-side against a
-- hand-parsed WKT/GeoJSON copy that could drift from the real column.
--
-- Region-level filtering (Cook Inlet vs. St. Lawrence) is done via a plain lat/lng bounding
-- box passed in from the client's own RegionConfig bounds, rather than duplicating those
-- bounds here -- RegionConfig is app-side config, same reasoning as the zones.region_id
-- comment in the schema migration ("not itself backed by a table").
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

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
    and s.lat is not null and s.lng is not null
    and s.lat between p_min_lat and p_max_lat
    and s.lng between p_min_lng and p_max_lng
    and (
      p_zone_slug is null
      or exists (
        select 1 from public.zones z
        where z.slug = p_zone_slug
          and ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(s.lng, s.lat), 4326))
      )
    )
  order by s.observed_at_epoch_ms desc;
$$;

-- Same anon-key-only posture as every other table/RPC in this app (see the subscriptions
-- table's RLS note in the schema migration) -- no server-side auth exists yet to restrict
-- this further.
grant execute on function public.export_sightings to anon;
