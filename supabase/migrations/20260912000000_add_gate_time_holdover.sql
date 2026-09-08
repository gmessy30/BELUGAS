-- Fixes a real bug hit in production: at 10:02 AM the banner read "NOT EXPECTED IN THE RIVER
-- BEFORE 9:43 PM" (tonight's cycle) while that morning's own gate, 10:43 AM, was still 40
-- minutes away. get_kenai_presence_state (20260909000000) always computed the gate from
-- v_upcoming_row -- the next LOW strictly after now -- but the gate opens sometime after its
-- own cycle's low, on the rising limb, so the CURRENT cycle's gate can still be in the future
-- (or, as here, recently past) even once its low has. The function was skipping straight past
-- a window that hadn't happened yet.
--
-- FIX: HOLDOVER, not just "still pending". Once a cycle's gate opens, keep showing it for
-- v_holdover_ms (90 minutes) afterward rather than advancing to the upcoming cycle's gate the
-- instant it passes -- whales run late, and the next window is ~12h away regardless, so there's
-- no urgency in advancing. Someone checking at 11:30 AM (within 90 minutes of a 10:43 AM gate)
-- should still see "10:43 AM", not be bumped to that night's window. Only once v_holdover_ms has
-- elapsed past the current cycle's gate does the function fall through to the upcoming row's
-- gate -- the same computation this function has always done, just now gated on holdover
-- expiring rather than unconditional.
--
-- ROW SELECTION: v_current_row is now fetched in full (previously only its low_at_epoch_ms was
-- pulled, since nothing but the RED/YELLOW sighting-window boundaries needed more than that) so
-- its own low/high pair can feed kenai_gate_time the same way v_upcoming_row's already does.
-- v_gate_time_likely (0.8m) is derived from whichever row v_gate_time_possible (0.3m) selected,
-- not chosen independently per threshold -- the two are meant to describe one cycle as a pair,
-- and only v_gate_time_possible is actually rendered by the client today (PresenceBanner.kt's
-- kenaiBannerLabel), so it's the one holdover is keyed on.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically. Same signature as 20260909000000's version
-- (no new output columns), so a plain `create or replace function` applies cleanly -- no DROP
-- needed first, unlike a migration that changes the return signature.
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
  v_current_row public.tide_cycles%rowtype;
  v_upcoming_row public.tide_cycles%rowtype;
  v_anchorage_month_day text;
  v_in_season boolean;
  v_phase text;
  v_gate_time_possible bigint;
  v_gate_time_likely bigint;
  v_gate_time_possible_current bigint;
  v_holdover_ms constant bigint := 90 * 60 * 1000;
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

  -- Gate floor: prefer the CURRENT cycle's own gate while it's still pending or within its
  -- 90-minute holdover (see this migration's header) -- only fall through to the upcoming
  -- cycle's gate once that holdover has actually expired, or if the current cycle has no gate
  -- to compute at all (no row yet, or its high data hasn't been ingested -- kenai_gate_time
  -- returns null either way, same null-safety this already relied on before this migration).
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
