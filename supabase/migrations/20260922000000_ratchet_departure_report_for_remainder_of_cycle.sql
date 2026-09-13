-- BUG FIX (item 72): a departure report was only ever excluding earlier sightings from the RED
-- check for 3 flat hours (v_departure_report_ttl_ms in 20260913000000/20260916010000). Once that
-- window lapsed, the RED-eligibility lower bound (v_red_lower_bound_ms) reverted all the way back
-- to the cycle's own low -- silently un-ratcheting a report that's still perfectly valid for the
-- rest of the still-open cycle, and letting a sighting from BEFORE the report resurrect RED on its
-- own with no new sighting at all. Observed live: a 2:31pm sighting, a departure report ~6:30pm
-- (correctly stepped RED to YELLOW), then RED again ~9:30pm -- exactly 3h after the report, and
-- with nothing new logged in between.
--
-- FIX: the RED ratchet now persists for the REMAINDER OF THE CYCLE (i.e. until v_current_low
-- itself advances to a new cycle), not 3 hours -- only a sighting observed AFTER the report can
-- re-trigger RED, for as long as the report and "now" both still fall in the same tide cycle. The
-- 3-hour flat clock still bounds the separate YELLOW floor (point 2 below) exactly as before --
-- that part of the design (self-heal to whatever's actually true once the claim goes stale) was
-- never the bug; only the RED-ratchet's own expiry was.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
--
-- BUILT ON THE CURRENTLY DEPLOYED get_kenai_presence_state -- 20260916010000's version (the one
-- that dropped the 6 dead predictor columns from the returns table), confirmed identical to what's
-- actually live before writing this. The returns table itself is UNCHANGED here (no new/removed
-- output columns), so this is a plain `create or replace`, no DROP needed.

create or replace function public.get_kenai_presence_state(p_now_epoch_ms bigint default null)
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

  -- ITEM 72 FIX: most recent departure report filed during the CURRENT, still-open cycle -- no
  -- flat time expiry at all. This is what the RED ratchet below is keyed off now: as long as
  -- "now" hasn't rolled past into a NEW cycle (v_current_low itself hasn't advanced past the
  -- report), the report stays in force for the rest of THIS cycle, however long that is. Once a
  -- new cycle actually starts, v_current_low advances and this query naturally stops finding the
  -- old report on its own -- no separate cycle-boundary check needed beyond the >= v_current_low
  -- filter itself.
  if v_current_low is not null then
    select max(reported_at_epoch_ms) into v_departure_report_in_cycle_ms
    from public.kenai_departure_reports
    where reported_at_epoch_ms >= v_current_low
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
  v_red_lower_bound_ms := v_current_low;
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
$$;

grant execute on function public.get_kenai_presence_state to anon;
