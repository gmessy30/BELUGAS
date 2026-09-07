-- Wires the entrance-gate floor (kenai_gate_time, 20260907000000) into get_kenai_presence_state's
-- actual output -- until now the tide pipeline computed gate times but nothing served them.
-- Pipeline is verified live: 86 NOAA rows ingested via the two-phase pg_cron job
-- (20260908000000), gate times reproduce the 18 hand-tabulated logbook cycles exactly across
-- every overlapping row, and the h_low >= g branch (tide never drops below the gate this cycle)
-- returns the low time correctly.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
--
-- WHAT CHANGES: BLUE's actual claim was always meant to be "here's when to expect them next" --
-- until now that was predicted_window_lo_min/hi_min from the retired fitted-delay model (still
-- present in tide_cycles, but never populated by NOAA-sourced rows, so every real row has left
-- it null since ingestion went live). gate_time_possible_epoch_ms (0.3m) and
-- gate_time_likely_epoch_ms (0.8m) replace it as what BLUE actually displays, computed fresh on
-- every call against the UPCOMING row's low/high -- not cached at ingestion time the way the old
-- model's prediction was, since kenai_gate_time is cheap pure arithmetic, not a model run.
--
-- RAW, NO MARGIN: both gate times are kenai_gate_time's raw formula output. The 3-minute early
-- bias belongs client-side (a named constant, applied once at render) per kenai_gate_time's own
-- design -- see that function's header comment on why the offset is the CALLER's job, not folded
-- into the formula.
--
-- prediction_available REDEFINED: now (gate_time_possible_epoch_ms is not null) instead of the
-- old (predicted_delay_min is not null). kenai_gate_time already returns null on any null/
-- malformed input (missing high, transposed low/high, etc. -- see its own guards), so a bad or
-- incomplete upcoming row falls straight through to "unavailable" via the existing single
-- boolean -- no separate validity flag needed on top of it.
--
-- LEFT ALONE: predicted_delay_min/predicted_window_lo_min/predicted_window_hi_min/
-- predicted_arrival_at_epoch_ms/current_phase/current_entrance_knots/cfs_used/warnings stay in
-- the return signature, still sourced from the upcoming row exactly as before (null on every
-- NOAA-sourced row today). Dropping them from the signature and the underlying tide_cycles
-- columns is the separate fitted-model cleanup migration already deferred (see
-- 20260907010000's own note) -- not part of this change.
-- =========================================================================================
create or replace function public.get_kenai_presence_state(p_now_epoch_ms bigint default null)
returns table (
  phase text,
  in_season boolean,
  current_cycle_low_epoch_ms bigint,
  next_cycle_low_epoch_ms bigint,
  red_qualifying_sighting_epoch_ms bigint,
  yellow_confirmed_sighting_epoch_ms bigint,
  predicted_delay_min integer,
  predicted_window_lo_min integer,
  predicted_window_hi_min integer,
  predicted_arrival_at_epoch_ms bigint,
  gate_time_possible_epoch_ms bigint,
  gate_time_likely_epoch_ms bigint,
  current_phase text,
  current_entrance_knots double precision,
  cfs_used double precision,
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
  v_upcoming_row public.tide_cycles%rowtype;
  v_anchorage_month_day text;
  v_in_season boolean;
  v_phase text;
  v_gate_time_possible bigint;
  v_gate_time_likely bigint;
begin
  -- Current cycle: only the boundary timestamp of the most recent low at/before now is needed.
  select low_at_epoch_ms into v_current_low
  from public.tide_cycles
  where low_at_epoch_ms <= v_now
  order by low_at_epoch_ms desc
  limit 1;

  -- Upcoming: the next low strictly after now -- what BLUE displays.
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

  -- RED: qualifying sighting anywhere in the current, still-open cycle.
  if v_current_low is not null then
    select max(s.observed_at_epoch_ms) into v_red_sighting_ms
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_current_low
      and s.observed_at_epoch_ms <= v_now
      and s.is_geofence_verified
      and ((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1)
      and public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters);
  end if;

  -- YELLOW: qualifying sighting in the 3 completed cycles before the current one.
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

  -- Gate floor: computed fresh against the upcoming row's own low/high. Null-safe by
  -- construction -- kenai_gate_time returns null if low/high/either height is missing (e.g. a
  -- trailing low still waiting on its high from tomorrow's ingestion run), which is exactly what
  -- makes prediction_available below correct without any extra validity check.
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

  return query select
    v_phase,
    v_in_season,
    v_current_low,
    v_next_low,
    v_red_sighting_ms,
    v_yellow_sighting_ms,
    v_upcoming_row.predicted_delay_min,
    v_upcoming_row.predicted_window_lo_min,
    v_upcoming_row.predicted_window_hi_min,
    v_upcoming_row.predicted_arrival_at_epoch_ms,
    v_gate_time_possible,
    v_gate_time_likely,
    v_upcoming_row.current_phase,
    v_upcoming_row.current_entrance_knots,
    v_upcoming_row.cfs_used,
    coalesce(v_upcoming_row.warnings, '{}'),
    (v_gate_time_possible is not null);
end;
$$;

grant execute on function public.get_kenai_presence_state to anon;
