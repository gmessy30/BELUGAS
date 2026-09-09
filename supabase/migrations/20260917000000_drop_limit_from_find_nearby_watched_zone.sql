-- The presence banner is becoming a carousel that can show every relevant watched zone at once
-- (supabase/migrations/20260916000000_match_watched_zones_by_containment.sql did the same for
-- the subscribed-zone half of the relevance check). find_nearby_watched_zone's own
-- `limit 1` silently dropped every zone but the single nearest one -- correct for the old
-- single-zone banner, wrong once a device can be simultaneously within proximity of two watched
-- zones. Same signature and output columns as before, so a plain create or replace is safe (see
-- supabase/MIGRATIONS.md).
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
$$;

grant execute on function public.find_nearby_watched_zone to anon;
