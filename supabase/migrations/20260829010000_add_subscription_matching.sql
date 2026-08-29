-- Stage 3 of the notification-zone subscriptions feature: real dispatch/matching, replacing
-- the flat-broadcast-to-everyone behavior notify-new-sighting has used since
-- 20260823000000_add_device_tokens_and_notify_trigger.sql.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically. The notify-new-sighting edge
-- function also needs redeploying after this lands (see that function's own comments) --
-- applying this migration alone doesn't change dispatch behavior by itself.

-- =========================================================================================
-- device_tokens.subscriber_id: the missing link between a registered device and its
-- subscriptions. Nullable -- a device that hasn't (re-)registered since this shipped won't
-- have one yet; match_notification_recipients() below treats a null subscriber_id the same as
-- "zero active subscriptions" (falls back to broadcast), so nothing silently stops getting
-- notified during the rollout window. Not a foreign key -- same posture as
-- subscriptions.subscriber_id itself (see that column's note in
-- 20260819000000_add_notification_zones_and_subscriptions.sql): there's no real auth/accounts
-- table for either to reference yet.
-- =========================================================================================
alter table public.device_tokens
  add column if not exists subscriber_id uuid;

create index if not exists device_tokens_subscriber_id_idx on public.device_tokens (subscriber_id);


-- =========================================================================================
-- match_notification_recipients: given a new sighting's location + confidence signal, returns
-- the distinct fcm_tokens that should be notified -- either because one of their subscriber's
-- active subscriptions actually matches this sighting, or because their subscriber has no
-- active subscriptions at all (the broadcast-fallback default for anyone who hasn't set up
-- alerts yet, or whose only subscriptions have expired).
--
-- "Active" = is_active AND (expires_at is null or not yet passed) -- an expired subscription
-- is deliberately treated the same as having none: falling back to broadcast is the safer
-- default for someone who let a temporary watch lapse, rather than silently going quiet.
--
-- Containment uses ST_Contains (same convention as export_sightings' zone filter in
-- 20260827000000_add_export_sightings_rpc.sql). point_radius uses ST_DWithin against the
-- geography-typed point/sighting location, which is real great-circle distance, not a flat
-- lat/lng-as-plane approximation.
--
-- p_lat/p_lng may be null (a sighting with no recorded location, e.g. bad manual/test data) --
-- ST_MakePoint(null,null) and every downstream ST_Contains/ST_DWithin against it are
-- strict-null, so the "matched" branch below naturally contributes nothing for such a
-- sighting; only broadcast-fallback devices are returned. Deliberate: a subscriber picked a
-- specific area, and a sighting with no known location genuinely isn't "in" it.
--
-- No anon grant (unlike export_sightings): this reads device_tokens, which has no anon-select
-- policy at all -- only the notify-new-sighting edge function calls this, using the
-- service-role key, which bypasses RLS regardless of this function's own security context. Not
-- declared SECURITY DEFINER for exactly that reason: an anon caller (there shouldn't be one --
-- see the explicit revoke below) would still see zero device_tokens rows, since it'd run under
-- its own (RLS-restricted) privileges rather than the function owner's.
-- =========================================================================================
create or replace function public.match_notification_recipients(
  p_lat double precision,
  p_lng double precision,
  p_observer_type text,
  p_is_geofence_verified boolean
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
      (s.kind = 'zone' and exists (
        select 1 from public.zones z
        where z.id = s.zone_id and ST_Contains(z.boundary, sp.geom)
      ))
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
