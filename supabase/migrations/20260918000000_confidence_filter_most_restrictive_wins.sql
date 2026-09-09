-- Confidence-filter override fix: match_notification_recipients used to OR confidence_filter
-- across every one of a subscriber's covering subscriptions independently, so a broad "all"
-- subscription (e.g. Entire Inlet) silently let unverified sightings through to a subscriber who
-- also had a narrower "verified_only" subscription (e.g. Kenai) covering the same location --
-- and, symmetrically, a broad "verified_only" subscription silently tightens a narrower "all"
-- one it overlaps. Most-restrictive-wins: an unverified sighting reaches a subscriber only if
-- NONE of their covering subscriptions demand verification, regardless of how broad or narrow
-- each one is. Same 5-arg signature and single fcm_token output column as before, so this is a
-- safe create or replace (see supabase/MIGRATIONS.md) -- no caller needs to change.
create or replace function public.match_notification_recipients(
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
  -- Every active subscription whose geometry covers this sighting, regardless of its own
  -- confidence_filter -- the filter is applied afterward, aggregated per subscriber below.
  covering_subscriptions as (
    select s.subscriber_id, s.confidence_filter
    from active_subscriptions s, sighting_point sp
    where
      (s.kind = 'zone' and public.is_whale_position_in_zone(s.zone_id, p_lng, p_lat, p_uncertainty_radius_meters))
      or (s.kind = 'custom_polygon' and ST_Contains(s.custom_polygon, sp.geom))
      or (s.kind = 'point_radius' and ST_DWithin(s.point, sp.geom::geography, s.radius_meters))
  ),
  matched_subscriber_ids as (
    select subscriber_id
    from covering_subscriptions
    group by subscriber_id
    having
      (p_observer_type = 'SELF' and p_is_geofence_verified)
      or bool_and(confidence_filter = 'all')
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

-- Surfaces the above tightening on the Subscriptions screen: for each of a subscriber's own
-- active 'verified_only' subscriptions, returns it if its geometry overlaps (ST_Intersects, not
-- containment -- these can be arbitrary custom polygons or point/radius circles, not just zones)
-- one of their own active 'all' subscriptions. The UI attaches a passive one-line note to each
-- returned subscription's row ("Your X (verified only) setting applies to overlapping zones"),
-- whichever of the two overlapping subscriptions happens to be the verified_only one -- the
-- effect is symmetric (a broad verified_only subscription can just as easily tighten a narrower
-- all one as the reverse), so this doesn't assume "zone" kind or any particular size relationship
-- between the two.
create or replace function public.get_confidence_filter_overlaps(p_subscriber_id uuid)
returns table(subscription_id uuid)
language sql
stable
as $$
  with subs as (
    select
      s.id,
      s.confidence_filter,
      case s.kind
        when 'zone' then z.boundary
        when 'custom_polygon' then s.custom_polygon
        when 'point_radius' then ST_Buffer(s.point::geography, s.radius_meters)::geometry
      end as geom
    from public.subscriptions s
    left join public.zones z on z.id = s.zone_id
    where s.subscriber_id = p_subscriber_id
      and s.is_active
      and (s.expires_at is null or s.expires_at > now())
  )
  select distinct v.id as subscription_id
  from subs v
  join subs a on a.id <> v.id and a.confidence_filter = 'all'
  where v.confidence_filter = 'verified_only'
    and v.geom is not null
    and a.geom is not null
    and ST_Intersects(v.geom, a.geom)
$$;

grant execute on function public.get_confidence_filter_overlaps to anon;
