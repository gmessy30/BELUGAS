-- Fixes a subscriber who only ever subscribed to a *containing* zone (e.g. "Entire Inlet", a
-- bounding-rectangle convenience zone -- see CoastlineGeometry.kt's comment on why it's never
-- real coastline) silently missing the Kenai presence banner and its gate time.
--
-- App.kt's subscribedWatchedZoneId used to require an EXACT zone_id match against a
-- is_banner_watched zone (`it.zoneId in watchedZoneIds`). A zone-kind subscription whose
-- polygon merely *contains* a watched zone -- rather than being it -- never matched, even
-- though match_notification_recipients (20260902010000) already does real polygon containment
-- for push delivery. Net effect: an Entire Inlet subscriber kept getting push notifications for
-- Kenai sightings but never saw the bottom banner or the gate-time text, since both are gated on
-- the same relevantWatchedZoneId/isRelevantZoneKenai chain in App.kt.
--
-- This RPC resolves the same "is this subscriber relevant to this watched zone" question
-- server-side, the same way match_notification_recipients already does: exact zone match, OR
-- containment of the watched zone's centroid inside the subscribed zone's polygon.
create or replace function public.get_relevant_watched_zone_id(p_subscriber_id uuid)
returns table(zone_id uuid)
language sql
stable
as $$
  select distinct wz.id as zone_id
  from public.zones wz
  join public.subscriptions s
    on s.subscriber_id = p_subscriber_id
   and s.is_active
   and s.kind = 'zone'
   and (s.expires_at is null or s.expires_at > now())
  join public.zones sz on sz.id = s.zone_id
  where wz.is_banner_watched
    and (
      wz.id = sz.id
      or ST_Contains(sz.boundary, ST_Centroid(wz.boundary))
    )
$$;

grant execute on function public.get_relevant_watched_zone_id to anon;
