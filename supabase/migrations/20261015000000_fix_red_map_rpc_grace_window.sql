-- Oct 15, 2026: bring get_kenai_red_qualifying_sightings (the status page's RED map) back in line
-- with get_kenai_presence_state's RED window.
--
-- WHY: 20260927000000 created this function as a copy of get_kenai_presence_state's RED branch so
-- the map could never disagree with the banner. Item 117 (20261001000000) then added the RED
-- step-down grace to get_kenai_presence_state only: for 90 minutes after a low higher than 0.0 m,
-- the RED window keeps the PREVIOUS cycle's start. This copy never got it, so during every such
-- grace window the banner said RED while this function returned 0 rows, and the status page hid
-- the map, the "Last seen" line and the count line.
--
-- Seen live on Oct 15, 2026: sighting 76b9c9bc... observed 12:42 AKDT, reported 13:52:41, after
-- the 13:52 low (+1.859 m). The banner was RED from 13:52:41 to 15:22 through the grace window
-- alone, and this function returned nothing for that whole time.
--
-- WHAT: the cycle start, the grace block and the departure ratchet are copied from
-- get_kenai_presence_state as it is live (constants included), and the sighting filter is the one
-- this function already had. Same signature, same return columns, same volatility, still SECURITY
-- INVOKER; CREATE OR REPLACE keeps the existing grants. get_kenai_presence_state is not touched.
--
-- Hard-rule territory (reads sightings, copies get_kenai_presence_state's RED logic): applied by
-- the user via tools\apply-latest-migration.ps1, after a rolled-back dry run.

create or replace function public.get_kenai_red_qualifying_sightings(p_now_epoch_ms bigint default null)
returns table(id uuid, observed_at_epoch_ms bigint, whale_lat double precision, whale_lng double precision,
              count_whites integer, count_greys integer, count_calves integer, count_unknown integer,
              travel_bearing_degrees double precision)
language plpgsql stable set search_path = public, pg_temp
as $$
declare
  v_now bigint := coalesce(p_now_epoch_ms, (extract(epoch from now()) * 1000)::bigint);
  v_current_row public.tide_cycles%rowtype;
  v_current_low bigint;
  v_previous_low bigint;
  v_red_grace_ms constant bigint := 90 * 60 * 1000;            -- same as get_kenai_presence_state
  v_red_grace_low_height_m constant double precision := 0.0;    -- same as get_kenai_presence_state
  v_red_cycle_start_ms bigint;
  v_departure_report_in_cycle_ms bigint;
  v_red_lower_bound_ms bigint;
begin
  select * into v_current_row from public.tide_cycles
   where low_at_epoch_ms <= v_now order by low_at_epoch_ms desc limit 1;
  v_current_low := v_current_row.low_at_epoch_ms;
  if v_current_low is null then return; end if;

  select low_at_epoch_ms into v_previous_low from public.tide_cycles
   where low_at_epoch_ms < v_current_low order by low_at_epoch_ms desc limit 1;

  -- Item 117 grace, copied from get_kenai_presence_state.
  v_red_cycle_start_ms := v_current_low;
  if v_previous_low is not null
     and v_current_row.low_height_m is not null
     and v_current_row.low_height_m > v_red_grace_low_height_m
     and v_now < v_current_low + v_red_grace_ms then
    v_red_cycle_start_ms := v_previous_low;
  end if;

  -- Departure ratchet keyed to the effective start, as in get_kenai_presence_state.
  select max(reported_at_epoch_ms) into v_departure_report_in_cycle_ms
    from public.kenai_departure_reports
   where reported_at_epoch_ms >= v_red_cycle_start_ms and reported_at_epoch_ms <= v_now;

  v_red_lower_bound_ms := v_red_cycle_start_ms;
  if v_departure_report_in_cycle_ms is not null then
    v_red_lower_bound_ms := greatest(v_red_lower_bound_ms, v_departure_report_in_cycle_ms + 1);
  end if;

  return query
    select s.id, s.observed_at_epoch_ms, s.whale_lat, s.whale_lng,
           s.count_whites, s.count_greys, s.count_calves, s.count_unknown, s.travel_bearing_degrees
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
