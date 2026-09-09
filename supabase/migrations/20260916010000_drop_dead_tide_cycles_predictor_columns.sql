-- Drops the 8 columns on tide_cycles that only ever served the retired mobilegeographics-based
-- regression predictor (supabase/functions/predict-beluga-arrival, removed from the repo in the
-- same commit as this migration). Confirmed before writing this:
--
-- 1. That edge function was never deployed -- no CI/CD exists anywhere in this repo (no
--    .github/workflows, no deploy script) and only one commit ever touched
--    supabase/functions/predict-beluga-arrival/ (its own creation). The 'refresh-beluga-
--    prediction' pg_cron job that calls it (20260903030000_add_tide_cycle_predictor.sql) was
--    scheduled and never unscheduled by anything since, per the migration trail -- see the
--    companion migration 20260916020000 that unschedules it.
-- 2. `current_phase` and `warnings` are DELIBERATELY KEPT, not dropped -- warnings is live
--    (PresenceBanner.kt renders kenaiDetail.warnings on the banner) and current_phase is kept
--    out of caution alongside it, even though nothing currently reads currentTidePhase
--    client-side.
-- 3. The 8 columns below are confirmed dead on every remaining read path: decoded into
--    KenaiPresenceState (predictedDelayMin, predictedWindowLoMin, predictedWindowHiMin,
--    predictedArrivalAtEpochMs, currentEntranceKnots, cfsUsed) with zero further Kotlin usage,
--    or (station_ft, prediction_computed_at) never selected by get_kenai_presence_state at all.
--    Since nothing ever wrote real values into them (the writer was never deployed), they have
--    always decoded as null in production regardless -- dropping them changes no observed
--    client behavior.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically. Apply this migration BEFORE or IN THE SAME
-- session as 20260916020000 (the cron unschedule) -- either order is safe on its own, but both
-- are needed together to fully retire the predictor.

alter table public.tide_cycles
  drop column if exists predicted_delay_min,
  drop column if exists predicted_window_lo_min,
  drop column if exists predicted_window_hi_min,
  drop column if exists predicted_arrival_at_epoch_ms,
  drop column if exists current_entrance_knots,
  drop column if exists cfs_used,
  drop column if exists station_ft,
  drop column if exists prediction_computed_at;

-- get_kenai_presence_state's own returns table lists 6 of the 8 dropped columns
-- (predicted_delay_min/predicted_window_lo_min/predicted_window_hi_min/
-- predicted_arrival_at_epoch_ms/current_entrance_knots/cfs_used) as real output columns --
-- station_ft and prediction_computed_at were never part of its output. A plain `create or
-- replace` cannot change the returns table's column list (see supabase/MIGRATIONS.md), so this
-- drops the function first. current_phase and warnings stay in the output, unchanged. Every
-- other line of the function body is IDENTICAL to the current live version
-- (20260913000000_add_kenai_departure_report.sql) -- only the returns table and the final
-- `return query select` column lists are narrowed.
drop function if exists public.get_kenai_presence_state(bigint);

create function public.get_kenai_presence_state(p_now_epoch_ms bigint default null)
returns table (
  phase text,
  in_season boolean,
  current_cycle_low_epoch_ms bigint,
  next_cycle_low_epoch_ms bigint,
  red_qualifying_sighting_epoch_ms bigint,
  yellow_confirmed_sighting_epoch_ms bigint,
  gate_time_possible_epoch_ms bigint,
  gate_time_likely_epoch_ms bigint,
  current_phase text,
  warnings text[],
  prediction_available boolean
)
language plpgsql
stable
as $$
declare
  v_now bigint := coalesce(p_now_epoch_ms, (extract(epoch from now()) * 1000)::bigint);
  v_current_low bigint;
  v_next_low bigint;
  v_three_cycles_ago_low bigint;
  v_red_sighting_ms bigint;
  v_yellow_sighting_ms bigint;
  v_current_row public.tide_cycles%rowtype;
  v_upcoming_row public.tide_cycles%rowtype;
  v_anchorage_month_day text;
  v_in_season boolean;
  v_phase text;
  v_gate_time_possible bigint;
  v_gate_time_likely bigint;
  v_gate_time_possible_current bigint;
  v_holdover_ms constant bigint := 90 * 60 * 1000;
  v_departure_report_ttl_ms constant bigint := 3 * 60 * 60 * 1000;
  v_departure_report_at_ms bigint;
  v_red_lower_bound_ms bigint;
begin
  -- Current cycle: fetched in full now, not just its low timestamp -- its own low/high pair
  -- feeds kenai_gate_time below the same way the upcoming row's already does.
  select * into v_current_row
  from public.tide_cycles
  where low_at_epoch_ms <= v_now
  order by low_at_epoch_ms desc
  limit 1;
  v_current_low := v_current_row.low_at_epoch_ms;

  -- Upcoming: the next low strictly after now -- the fallback gate once the current cycle's
  -- gate + holdover has passed (see the gate-floor block below).
  select * into v_upcoming_row
  from public.tide_cycles
  where low_at_epoch_ms > v_now
  order by low_at_epoch_ms asc
  limit 1;
  v_next_low := v_upcoming_row.low_at_epoch_ms;

  -- Three cycles back from the current one: the earliest of the 4 most recent lows at/before
  -- the current cycle's own start (current cycle counts as 0 back, so this is the 4th).
  select min(low_at_epoch_ms) into v_three_cycles_ago_low
  from (
    select low_at_epoch_ms
    from public.tide_cycles
    where low_at_epoch_ms <= coalesce(v_current_low, v_now)
    order by low_at_epoch_ms desc
    limit 4
  ) last4;

  -- Most recent still-active departure report, if any -- "active" meaning within the last 3h.
  -- Deliberately time-only, not cycle-scoped: a departure claim's confidence decays on its own
  -- clock, not the tide's (see 20260913000000's own header for why 3h flat rather than a cycle
  -- boundary).
  select max(reported_at_epoch_ms) into v_departure_report_at_ms
  from public.kenai_departure_reports
  where reported_at_epoch_ms > v_now - v_departure_report_ttl_ms
    and reported_at_epoch_ms <= v_now;

  -- RED: qualifying sighting anywhere in the current, still-open cycle -- lower bound ratcheted
  -- past an active departure report's own timestamp, so only a sighting logged AFTER the report
  -- can re-trigger RED.
  v_red_lower_bound_ms := v_current_low;
  if v_departure_report_at_ms is not null then
    v_red_lower_bound_ms := greatest(v_red_lower_bound_ms, v_departure_report_at_ms + 1);
  end if;

  if v_current_low is not null then
    select max(s.observed_at_epoch_ms) into v_red_sighting_ms
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_red_lower_bound_ms
      and s.observed_at_epoch_ms <= v_now
      and s.is_geofence_verified
      and ((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1)
      and public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters);
  end if;

  -- YELLOW: qualifying sighting in the 3 completed cycles before the current one. Unaffected by
  -- departure reports -- this window is strictly before v_current_low, and a departure report is
  -- always filed during the current (still-open) cycle, so it can never fall inside this range.
  if v_current_low is not null and v_three_cycles_ago_low is not null then
    select max(s.observed_at_epoch_ms) into v_yellow_sighting_ms
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_three_cycles_ago_low
      and s.observed_at_epoch_ms < v_current_low
      and s.is_geofence_verified
      and ((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1)
      and public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters);
  end if;

  -- Gate floor: prefer the CURRENT cycle's own gate while it's still pending or within its
  -- 90-minute holdover, else the upcoming cycle's gate -- unchanged from 20260912000000.
  v_gate_time_possible_current := public.kenai_gate_time(
    v_current_row.low_at_epoch_ms, v_current_row.low_height_m,
    v_current_row.high_at_epoch_ms, v_current_row.high_height_m,
    0.3
  );

  if v_gate_time_possible_current is not null
     and v_now < v_gate_time_possible_current + v_holdover_ms then
    v_gate_time_possible := v_gate_time_possible_current;
    v_gate_time_likely := public.kenai_gate_time(
      v_current_row.low_at_epoch_ms, v_current_row.low_height_m,
      v_current_row.high_at_epoch_ms, v_current_row.high_height_m,
      0.8
    );
  else
    v_gate_time_possible := public.kenai_gate_time(
      v_upcoming_row.low_at_epoch_ms, v_upcoming_row.low_height_m,
      v_upcoming_row.high_at_epoch_ms, v_upcoming_row.high_height_m,
      0.3
    );
    v_gate_time_likely := public.kenai_gate_time(
      v_upcoming_row.low_at_epoch_ms, v_upcoming_row.low_height_m,
      v_upcoming_row.high_at_epoch_ms, v_upcoming_row.high_height_m,
      0.8
    );
  end if;

  v_anchorage_month_day := to_char(
    (to_timestamp(v_now / 1000.0) at time zone 'America/Anchorage'),
    'MM-DD'
  );
  v_in_season := v_anchorage_month_day between '08-15' and '12-31'
    or v_anchorage_month_day between '03-15' and '05-14';

  if v_red_sighting_ms is not null then
    v_phase := 'RED';
  elsif v_yellow_sighting_ms is not null then
    v_phase := 'YELLOW';
  else
    v_phase := 'BLUE';
  end if;

  -- YELLOW floor: an active departure report never lets the phase read BLUE, even once the
  -- (now-ratcheted-past) RED sighting and any ordinary YELLOW-qualifying sighting both come up
  -- empty.
  if v_phase = 'BLUE' and v_departure_report_at_ms is not null then
    v_phase := 'YELLOW';
  end if;

  return query select
    v_phase,
    v_in_season,
    v_current_low,
    v_next_low,
    v_red_sighting_ms,
    v_yellow_sighting_ms,
    v_gate_time_possible,
    v_gate_time_likely,
    v_upcoming_row.current_phase,
    coalesce(v_upcoming_row.warnings, '{}'),
    (v_gate_time_possible is not null);
end;
$$;

grant execute on function public.get_kenai_presence_state to anon;
