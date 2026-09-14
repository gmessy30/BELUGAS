-- Item 97b: the standalone status page's RED state shows a compact map of the actual sighting(s)
-- that are making the phase RED -- get_kenai_presence_state itself only ever returns the single
-- newest qualifying timestamp (red_qualifying_sighting_epoch_ms), never the row(s) themselves
-- (position, counts, travel_bearing), so a new read-only RPC is needed.
--
-- CRITICAL: this MUST return exactly the same set of rows get_kenai_presence_state's own RED
-- branch would count, or the map could show a sighting that isn't actually why the banner is red
-- (or, worse, miss the one that is). So this is not export_sightings reused with a zone_slug --
-- export_sightings filters by is_whale_position_in_zone() against a NAMED zone polygon, which is
-- a DIFFERENT geofence than is_whale_position_in_kenai_banner_area() (see
-- 20260903000000_narrow_kenai_banner_to_river_and_mouth.sql and
-- 20260903060000_widen_red_containment_river_limit.sql -- the banner area has its own, separately
-- tuned upriver cutoff). Instead, this function's body is the RED-branch logic from
-- get_kenai_presence_state (20260923000000's version, currently live) copied verbatim -- same
-- cycle-low lookup, same departure-report ratchet, same tier/confirmed condition, same
-- is_whale_position_in_kenai_banner_area() check -- with the SELECT changed from
-- `max(observed_at_epoch_ms)` to the actual rows, ordered newest first. Any future change to
-- get_kenai_presence_state's RED branch must be mirrored here too, or the two will drift out of
-- agreement about what counts as RED-qualifying -- flagged in both functions' own comments.
--
-- Read-only, no schema changes. Granted to anon AND authenticated directly (item 96's own
-- lesson: an anon-only grant silently breaks for a device carrying an admin login).
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor
-- (this repo's own established convention, see CLAUDE.md). Per CLAUDE.md's own "Hard rule" on
-- migrations touching get_kenai_presence_state/sightings data: DO NOT apply this until the user
-- has reviewed this diff and given an explicit go-ahead.

create or replace function public.get_kenai_red_qualifying_sightings(p_now_epoch_ms bigint default null)
returns table (
  id uuid,
  observed_at_epoch_ms bigint,
  whale_lat double precision,
  whale_lng double precision,
  count_whites integer,
  count_greys integer,
  count_calves integer,
  count_unknown integer,
  travel_bearing_degrees double precision
)
language plpgsql
stable
set search_path = public, pg_temp
as $$
declare
  v_now bigint := coalesce(p_now_epoch_ms, (extract(epoch from now()) * 1000)::bigint);
  v_current_low bigint;
  v_departure_report_in_cycle_ms bigint;
  v_red_lower_bound_ms bigint;
begin
  -- Current cycle's own low -- same lookup as get_kenai_presence_state.
  select low_at_epoch_ms into v_current_low
  from public.tide_cycles
  where low_at_epoch_ms <= v_now
  order by low_at_epoch_ms desc
  limit 1;

  if v_current_low is null then
    return;
  end if;

  -- Same in-cycle departure-report ratchet as get_kenai_presence_state's own RED branch.
  select max(reported_at_epoch_ms) into v_departure_report_in_cycle_ms
  from public.kenai_departure_reports
  where reported_at_epoch_ms >= v_current_low
    and reported_at_epoch_ms <= v_now;

  v_red_lower_bound_ms := v_current_low;
  if v_departure_report_in_cycle_ms is not null then
    v_red_lower_bound_ms := greatest(v_red_lower_bound_ms, v_departure_report_in_cycle_ms + 1);
  end if;

  return query
    select
      s.id, s.observed_at_epoch_ms, s.whale_lat, s.whale_lng,
      s.count_whites, s.count_greys, s.count_calves, s.count_unknown,
      s.travel_bearing_degrees
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_red_lower_bound_ms
      and s.observed_at_epoch_ms <= v_now
      and s.is_geofence_verified
      and (((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1) or s.confirmed_at is not null)
      and public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
    order by s.observed_at_epoch_ms desc;
end;
$$;

revoke all on function public.get_kenai_red_qualifying_sightings(bigint) from public;
grant execute on function public.get_kenai_red_qualifying_sightings(bigint) to anon, authenticated;
