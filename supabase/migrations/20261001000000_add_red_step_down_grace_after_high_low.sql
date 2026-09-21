-- ITEM 117: a 90-minute RED step-down grace after a low tide that never flushed the channel.
--
-- PROBLEM (observed live, 2026-09-20). Eight tier-1 sightings ran 16:19-18:51 AKDT, all inside
-- the Kenai banner area, all geofence-verified. The cycle low fell at 19:14 with
-- low_height_m = 2.936 -- nowhere near a flush. At 19:14:00 exactly, v_current_low advanced, the
-- RED window's lower bound jumped with it, all eight sightings fell out of range at once, and the
-- phase dropped to YELLOW. It read YELLOW for twelve minutes until a fresh report at 19:26 pulled
-- it back to RED. Confirmed by probing the live function at those exact timestamps, not inferred:
--   19:13 -> RED (red_qualifying = 18:51)
--   19:14 -> YELLOW (red_qualifying = null)   <- the whales had not gone anywhere
--   19:26 -> RED (red_qualifying = 19:26)
-- Nothing about the water changed at 19:14. Only the calendar did.
--
-- CHANGE. When the low that just occurred is ABOVE v_red_grace_low_height_m (0.0 m), the channel
-- did not flush, so for v_red_grace_ms (90 minutes) after that low the RED window keeps the
-- PREVIOUS cycle's start instead of ratcheting immediately to the new one. Sightings from the
-- cycle that just ended keep qualifying for that window, giving observers time to reacquire a
-- visual before the phase steps down. A low at or below the threshold is a real flush and behaves
-- exactly as it does today, with no grace at all.
--
-- THIS IS A PER-BOUNDARY RULE, NOT A ONE-TIME EXCEPTION. The test is evaluated fresh on every
-- call from whatever v_current_row is at that v_now. At each successive low it runs again against
-- THAT low's own height: at or below the threshold steps down at the turn, above it grants
-- another full 90 minutes. No state is carried between calls or between cycles, so the behavior
-- repeats indefinitely with no accumulation. A new sighting logged during a grace window needs no
-- special handling -- its own timestamp already clears whatever bound is in effect -- and the
-- next boundary then applies the same test again from scratch.
--
-- COMPOSITION WITH THE ITEM-72 DEPARTURE RATCHET. The departure-report lookup is re-keyed from
-- v_current_low to v_red_cycle_start_ms. This matters: during a grace window the RED window
-- reaches back into the cycle that just ended, so a departure report filed in THAT cycle must
-- still be found, or re-admitting the old sightings would step straight over a report that had
-- already ratcheted past them. Outside a grace window the two values are identical and this is
-- byte-for-byte the existing behavior. A report filed DURING the grace window also still works:
-- greatest() pushes the bound past it and ends the re-admittance on the spot.
--
-- NOT CHANGED: the YELLOW window, the 3-hour departure-report YELLOW floor, the gate-time
-- selection branch and its own separate v_holdover_ms, the phase decision itself, and the
-- returned column list. v_holdover_ms is deliberately NOT reused for the grace duration even
-- though both are 90 minutes -- it governs which cycle's gate time is REPORTED and has never
-- influenced the phase; sharing one constant would silently couple the two.
--
-- SCOPE NOTE ON THE THRESHOLD. Across the 129 tide_cycles rows currently loaded, low_height_m
-- ranges -1.074 to 3.000 and 31 of them (24%) sit at or below 0.0. So the no-grace branch is
-- real, not dead code -- but grace is the common case, and the next 10 boundaries after
-- 2026-09-20 19:14 all grant it (the next flush low is 2026-09-26 11:10, -0.054 m).

CREATE OR REPLACE FUNCTION public.get_kenai_presence_state(p_now_epoch_ms bigint DEFAULT NULL::bigint)
 RETURNS TABLE(phase text, in_season boolean, current_cycle_low_epoch_ms bigint, next_cycle_low_epoch_ms bigint, red_qualifying_sighting_epoch_ms bigint, yellow_confirmed_sighting_epoch_ms bigint, gate_time_possible_epoch_ms bigint, gate_time_likely_epoch_ms bigint, current_phase text, warnings text[], prediction_available boolean)
 LANGUAGE plpgsql
 STABLE
AS $function$
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
  -- ITEM 117: the RED step-down grace. Same 90 minutes as v_holdover_ms above, but a SEPARATE
  -- constant on purpose -- v_holdover_ms governs which tide cycle's gate time is REPORTED and has
  -- never had any influence on the phase (confirmed: it appears exactly once in executable code,
  -- in the gate-selection branch below). These two knobs are free to diverge later; collapsing
  -- them into one would silently couple the phase to the gate-time display.
  v_red_grace_ms constant bigint := 90 * 60 * 1000;
  -- A low at or below this height is treated as a real flush of the channel -- no grace. Note
  -- this is NOT one of kenai_gate_time's 0.3/0.8 gate depths; it is its own threshold, asking
  -- "did the water actually drop out" rather than "is it deep enough to swim in".
  v_red_grace_low_height_m constant double precision := 0.0;
  v_previous_low bigint;
  v_red_cycle_start_ms bigint;
  v_departure_report_ttl_ms constant bigint := 3 * 60 * 60 * 1000;
  v_departure_report_in_cycle_ms bigint;
  v_departure_report_recent_ms bigint;
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

  -- ITEM 117: the low immediately before the current one -- the RED window's fallback start
  -- during the grace period below. Read every call from the CURRENT v_current_low, so this is a
  -- general per-boundary rule and not a fact about any one cycle.
  select low_at_epoch_ms into v_previous_low
  from public.tide_cycles
  where low_at_epoch_ms < v_current_low
  order by low_at_epoch_ms desc
  limit 1;

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

  -- ITEM 117: effective start of the RED window.
  --
  -- Normally this is just the current cycle's own low, i.e. RED resets at every tide boundary.
  -- But a low that never drops below v_red_grace_low_height_m didn't flush the channel, so whales
  -- present before the turn are very likely still there -- stepping RED down the instant the
  -- clock crosses the low punishes observers for the tide table rather than for anything actually
  -- observed. For v_red_grace_ms after such a low, the RED window keeps the PREVIOUS cycle's
  -- start, so sightings from the cycle that just ended still qualify.
  --
  -- This is evaluated FRESH on every call against whatever v_current_row is at that v_now -- it
  -- is a rule about the boundary being crossed, not a one-time exception for a particular cycle.
  -- At each successive low the same test runs again on THAT low's own height: <= the threshold
  -- steps down at the turn, above it grants another full grace window, indefinitely. Nothing here
  -- is sticky across calls and no state is carried between cycles.
  v_red_cycle_start_ms := v_current_low;
  if v_current_low is not null
     and v_previous_low is not null
     and v_current_row.low_height_m is not null
     and v_current_row.low_height_m > v_red_grace_low_height_m
     and v_now < v_current_low + v_red_grace_ms then
    v_red_cycle_start_ms := v_previous_low;
  end if;

  -- ITEM 72 FIX: most recent departure report filed during the CURRENT, still-open cycle -- no
  -- flat time expiry at all. This is what the RED ratchet below is keyed off now: as long as
  -- "now" hasn't rolled past into a NEW cycle (v_current_low itself hasn't advanced past the
  -- report), the report stays in force for the rest of THIS cycle, however long that is. Once a
  -- new cycle actually starts, v_current_low advances and this query naturally stops finding the
  -- old report on its own -- no separate cycle-boundary check needed beyond the >= v_current_low
  -- filter itself.
  --
  -- ITEM 117: keyed to v_red_cycle_start_ms, NOT v_current_low. During a grace period the RED
  -- window reaches back into the cycle that just ended, so a departure report filed in THAT cycle
  -- has to be found here -- otherwise re-admitting the old cycle's sightings would step straight
  -- over a report that had already ratcheted past them, which is exactly the regression this
  -- window could otherwise introduce. Outside a grace period the two are the same value and this
  -- is byte-for-byte the item-72 behavior.
  if v_red_cycle_start_ms is not null then
    select max(reported_at_epoch_ms) into v_departure_report_in_cycle_ms
    from public.kenai_departure_reports
    where reported_at_epoch_ms >= v_red_cycle_start_ms
      and reported_at_epoch_ms <= v_now;
  end if;

  -- Most recent departure report within the last 3h -- used ONLY for the YELLOW floor (point 2
  -- below), which is deliberately still on a flat clock: unlike the RED ratchet (a factual "has
  -- anything new happened since this report" question, correctly scoped to the whole cycle), the
  -- YELLOW floor is a confidence decay on the report ITSELF ("how long do we still trust this
  -- claim without anything reconfirming it"), which has no reason to track the tide.
  select max(reported_at_epoch_ms) into v_departure_report_recent_ms
  from public.kenai_departure_reports
  where reported_at_epoch_ms > v_now - v_departure_report_ttl_ms
    and reported_at_epoch_ms <= v_now;

  -- RED: qualifying sighting anywhere in the current, still-open cycle -- lower bound ratcheted
  -- past the in-cycle departure report's own timestamp (see above), so only a sighting logged
  -- AFTER the report can re-trigger RED, for as long as both it and "now" are in the same cycle.
  -- ITEM 117: base is the EFFECTIVE window start (the previous cycle's low during a grace
  -- period, the current cycle's low otherwise). The departure ratchet below is unchanged and
  -- still applies on top of whichever base is in effect -- a report filed during the grace period
  -- itself ends the re-admittance immediately, since greatest() pushes the bound past it.
  v_red_lower_bound_ms := v_red_cycle_start_ms;
  if v_departure_report_in_cycle_ms is not null then
    v_red_lower_bound_ms := greatest(v_red_lower_bound_ms, v_departure_report_in_cycle_ms + 1);
  end if;

  if v_current_low is not null then
    select max(s.observed_at_epoch_ms) into v_red_sighting_ms
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_red_lower_bound_ms
      and s.observed_at_epoch_ms <= v_now
      and s.is_geofence_verified
      -- ITEM 63: the one changed line in this whole function -- "tier 1/2 OR confirmed" now
      -- qualifies for RED, not just "tier 1/2" alone.
      and (((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1) or s.confirmed_at is not null)
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

  -- YELLOW floor: an active (within 3h) departure report never lets the phase read BLUE, even
  -- once the (now-ratcheted-past) RED sighting and any ordinary YELLOW-qualifying sighting both
  -- come up empty. This is the ONE place the flat 3h clock still applies -- see
  -- v_departure_report_recent_ms's own comment above.
  if v_phase = 'BLUE' and v_departure_report_recent_ms is not null then
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
$function$;
