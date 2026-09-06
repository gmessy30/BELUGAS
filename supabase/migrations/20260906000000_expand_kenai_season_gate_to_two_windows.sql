-- get_kenai_presence_state's SEASON GATE was fitted to Mar-May only (spring 2026 predictor
-- data, see 20260903030000_add_tide_cycle_predictor.sql). Real observed arrivals aren't
-- confined to that window -- personal sighting records also show a second, separate run in
-- late summer/fall. This migration widens the gate to two disjoint windows instead of the one
-- contiguous Mar-May range. Only v_in_season and its surrounding comment change below; every
-- other rule in the function (RED/YELLOW tier logic, tidal-cycle bucketing, BLUE's upcoming-low
-- display) is untouched -- CREATE OR REPLACE requires restating the whole function body anyway
-- since Postgres has no way to patch a single statement inside one.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

-- =========================================================================================
-- get_kenai_presence_state: the real BLUE/YELLOW/RED decision. RED and YELLOW both reuse
-- exactly the tier rule wired in 20260903020000 -- (observer_tier=2 and observer_type='SELF')
-- or observer_tier=1 -- windowed by tidal cycle instead of get_watched_zone_statuses' flat
-- decay windows, which this function replaces for Kenai specifically (get_watched_zone_statuses
-- itself is untouched -- it still feeds the map's unconditional river shading and stays the
-- fallback path for any future banner-watched zone that has no predictor).
--
-- PHASE PRIORITY, RED > YELLOW > BLUE:
--   RED    -- a qualifying sighting anywhere in the CURRENT (still-open) cycle. Persists for
--            the rest of that cycle by construction: as long as "now" is still inside the same
--            cycle the sighting landed in, this stays true -- no separate expiry timestamp to
--            maintain. The moment the tide reaches the next low, a new cycle starts and this
--            check naturally stops matching.
--   YELLOW -- a qualifying sighting in any of the 3 COMPLETED cycles before the current one
--            (not including it -- RED already owns "did it happen in the current cycle") AND
--            EITHER "now" falls inside the CURRENT cycle's own predicted arrival window (not
--            the upcoming/future low's window -- see below) OR the cycle immediately before
--            this one was itself the one that qualified (the RED-just-expired case). The
--            second branch is what makes "RED persists... then reverts to YELLOW" an immediate
--            transition right when the tide turns, not something that waits until the NEW
--            cycle's own predicted window opens (which can be hours after that low, depending
--            on its own delay_min) -- an older confirmation (2-3 cycles back, nothing
--            immediately adjacent) still goes through the ordinary "near predicted arrival"
--            gate.
--   BLUE   -- default. Shows the UPCOMING low tide's prediction (the next low strictly after
--            now) when in-season and that prediction has been computed; otherwise a "not
--            expected"/"unavailable" state -- see in_season below. RED/YELLOW are NEVER
--            season-gated: they reflect real confirmed reports, not the model's guess, so an
--            out-of-season confirmed sighting still drives RED/YELLOW exactly as it would in
--            season.
--
-- WHY TWO DIFFERENT tide_cycles ROWS ARE READ: the CURRENT cycle's own row (whose low already
-- happened) is what YELLOW's "near predicted arrival" check is measured against -- that's the
-- only window "now" could plausibly be inside. The UPCOMING row (next low, still in the
-- future) is what BLUE actually displays -- a forward-looking "here's when to expect them
-- next." These are deliberately different rows; conflating them would either make YELLOW
-- unreachable (checking a window that hasn't started yet) or show BLUE a stale past prediction.
--
-- SEASON GATE: the underlying tide/arrival MODEL is still validated on spring 2026 data only
-- (Kenai Beluga Predictor SPEC.md, R^2=0.964 on 28 Mar-May sightings) and has no calendar
-- awareness of its own -- nothing in it refuses to run outside that window, so the gate lives
-- here, not in the edge function. What changed in this migration is the WINDOW the gate opens
-- for, not the model: five years of personal sighting records (2021-2025) put the earliest fall
-- arrival at Aug 21 and the latest spring departure at May 7. The two ranges below pad roughly a
-- week past each observed extreme, in the direction that matters, so a slightly early or late
-- animal gets a real BLUE/prediction claim instead of "NOT EXPECTED THIS TIME OF YEAR" -- Aug 15/
-- Dec 31 and Mar 15/May 14 are a judgment call on top of that data, not a second data point.
-- Revisit the padding, not just the observed dates, if more seasons push either extreme wider.
--
-- MM-DD text comparison, not extract(month)/day-of-year: keeps this expressible as a plain
-- lexicographic range on zero-padded strings (no leap-year-sensitive day-of-year arithmetic),
-- and keeps the SAME LITERAL '08-15'/'12-31'/'03-15'/'05-14' strings as PresenceBanner.kt's
-- isKenaiInSeasonLocally -- a reviewer diffing this migration against that file should be able
-- to confirm the two match by eye. Neither window wraps the Dec 31/Jan 1 year turn, so this
-- needs no cross-year handling. See PresenceBannerSeasonGateTest for the exact boundary dates
-- this is expected to hold for (client side only -- this repo has no DB credentials to run this
-- function in CI, so the SQL side stays hand-verified against that same test's dates).
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
  v_minus1_low bigint;
  v_three_cycles_ago_low bigint;
  v_red_sighting_ms bigint;
  v_yellow_sighting_ms bigint;
  v_just_expired_sighting_ms bigint;
  v_current_row public.tide_cycles%rowtype;
  v_upcoming_row public.tide_cycles%rowtype;
  v_anchorage_month_day text;
  v_in_season boolean;
  v_phase text;
  v_near_current_arrival boolean := false;
begin
  -- Current cycle: the most recent low at/before now.
  select * into v_current_row
  from public.tide_cycles
  where low_at_epoch_ms <= v_now
  order by low_at_epoch_ms desc
  limit 1;
  v_current_low := v_current_row.low_at_epoch_ms;

  -- Upcoming: the next low strictly after now -- what BLUE displays.
  select * into v_upcoming_row
  from public.tide_cycles
  where low_at_epoch_ms > v_now
  order by low_at_epoch_ms asc
  limit 1;
  v_next_low := v_upcoming_row.low_at_epoch_ms;

  -- The cycle immediately before the current one -- needed separately from the 3-cycle window
  -- below for the RED-just-expired case (see v_just_expired_sighting_ms).
  if v_current_low is not null then
    select low_at_epoch_ms into v_minus1_low
    from public.tide_cycles
    where low_at_epoch_ms < v_current_low
    order by low_at_epoch_ms desc
    limit 1;
  end if;

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

  -- YELLOW support: qualifying sighting in the 3 completed cycles before the current one.
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

  -- RED-just-expired case: was the CYCLE IMMEDIATELY BEFORE this one itself RED (a qualifying
  -- sighting anywhere in [minus1_low, current_low))? If so, YELLOW fires unconditionally for
  -- the rest of the current cycle, regardless of whether "now" happens to be near the current
  -- cycle's own predicted arrival window yet -- this is what makes the persistence rule's "then
  -- reverts to YELLOW, not BLUE" an immediate transition at the moment RED expires, not
  -- something that only becomes true once the new cycle's own window opens (which could be
  -- hours after the tide turns, depending on that cycle's own delay_min). Older confirmations
  -- (2-3 cycles back, i.e. no RED immediately adjacent to the current cycle) still go through
  -- the ordinary "near predicted arrival" gate below -- only a just-expired RED skips it.
  if v_current_low is not null and v_minus1_low is not null then
    select max(s.observed_at_epoch_ms) into v_just_expired_sighting_ms
    from public.sightings s
    where s.whale_lat is not null and s.whale_lng is not null
      and s.observed_at_epoch_ms >= v_minus1_low
      and s.observed_at_epoch_ms < v_current_low
      and s.is_geofence_verified
      and ((s.observer_tier = 2 and s.observer_type = 'SELF') or s.observer_tier = 1)
      and public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters);
  end if;

  -- Is "now" inside the CURRENT cycle's own predicted arrival window (absolute time =
  -- current low + [lo_min, hi_min])? Only meaningful once that cycle's prediction has been
  -- computed -- if not, YELLOW simply can't apply yet (stays BLUE per the scoped design: "if
  -- not, stay BLUE with the prediction still shown, not escalated").
  if v_current_row.predicted_window_lo_min is not null then
    v_near_current_arrival := v_now between
      v_current_low + (v_current_row.predicted_window_lo_min * 60000)
      and v_current_low + (v_current_row.predicted_window_hi_min * 60000);
  end if;

  -- Two disjoint windows, padded ~1 week past 5 years of observed extremes (Aug 21 earliest
  -- fall arrival, May 7 latest spring departure) -- see this function's header comment for the
  -- reasoning and PresenceBanner.kt's isKenaiInSeasonLocally for the mirrored client-side copy.
  v_anchorage_month_day := to_char(
    (to_timestamp(v_now / 1000.0) at time zone 'America/Anchorage'),
    'MM-DD'
  );
  v_in_season := v_anchorage_month_day between '08-15' and '12-31'
    or v_anchorage_month_day between '03-15' and '05-14';

  if v_red_sighting_ms is not null then
    v_phase := 'RED';
  elsif v_yellow_sighting_ms is not null and (v_near_current_arrival or v_just_expired_sighting_ms is not null) then
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
    v_upcoming_row.current_phase,
    v_upcoming_row.current_entrance_knots,
    v_upcoming_row.cfs_used,
    coalesce(v_upcoming_row.warnings, '{}'),
    (v_upcoming_row.predicted_delay_min is not null);
end;
$$;

grant execute on function public.get_kenai_presence_state to anon;
