-- Wires the observer tier system (20260903010000_add_observer_tier_system.sql) into the RED
-- banner tier's actual eligibility rule. Before this migration, RED
-- (last_verified_sighting_epoch_ms) counted any sighting with observer_type='SELF' and
-- is_geofence_verified, with no tier check at all -- observer_tier existed on the table but
-- nothing read it yet.
--
-- New rule:
--   (observer_tier = 2 and observer_type = 'SELF' and is_geofence_verified)
--   or
--   (observer_tier = 1 and is_geofence_verified)
--
-- Tier 2 still requires personal witness (SELF) -- same bar as before, just now gated on tier
-- as well. Tier 1 counts either way (SELF or OTHER): a relayed/secondhand report from a tier-1
-- source is trusted enough on its own, which is the one real behavior change here -- a
-- verified OTHER-observer sighting could never drive RED before this migration, regardless of
-- who reported it.
--
-- Only the RED (last_verified_sighting_epoch_ms) filter changes. last_any_sighting_epoch_ms,
-- the kenai-vs-other-zone containment branch, and everything else about this function are
-- untouched.
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
      where s.is_geofence_verified
        and (
          (s.observer_tier = 2 and s.observer_type = 'SELF')
          or s.observer_tier = 1
        )
    ),
    max(s.observed_at_epoch_ms)
  from public.zones z
  left join public.sightings s
    on s.whale_lat is not null and s.whale_lng is not null
    and s.observed_at_epoch_ms >= (extract(epoch from now()) * 1000)::bigint - p_lookback_ms
    and (
      case when z.slug = 'kenai'
        then public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
        else public.is_whale_position_in_zone(z.id, s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
      end
    )
  where z.is_banner_watched
  group by z.id, z.slug, z.name
$$;
