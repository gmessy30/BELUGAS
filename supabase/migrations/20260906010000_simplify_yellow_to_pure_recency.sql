-- get_kenai_presence_state's YELLOW rule required a sighting in the 3 completed cycles before
-- the current one AND EITHER "now" landing near the current cycle's own predicted arrival
-- window OR the immediately preceding cycle being the one that qualified (the "just expired"
-- case, meant only to make RED step down to YELLOW right at the cycle turn instead of waiting
-- for a window to open). In practice this made YELLOW blink: true only in a narrow window
-- around a predicted arrival (or for exactly one cycle right after a RED), back to BLUE for the
-- rest of each subsequent cycle even though the underlying sighting was still recent.
--
-- That assumption doesn't hold up: whales sometimes skip a cycle or two, gather at the river
-- mouth and abort an entrance, or just hang around the mouth between tides rather than arriving
-- on schedule. This migration drops the timing gate entirely -- YELLOW is now a pure recency
-- claim: any qualifying sighting in the 3 completed cycles before the current one, full stop,
-- persisting for the whole of each of those cycles rather than blinking within them. Three
-- cycles is roughly 36 hours, deliberately generous -- YELLOW is a caution, not a call to
-- action, so the fatigue cost of it staying on too long is low, while the cost of it expiring
-- early is someone standing down while whales are still plausibly around. RED still takes
-- priority unconditionally and RED's own persistence/step-down behavior is unchanged -- only
-- YELLOW's gate goes away, and the step-down from RED to YELLOW at a cycle turn is now automatic
-- (the just-expired cycle falls inside the ordinary 3-cycle lookback) rather than a special case.
--
-- Also collapses the CURRENT cycle's row fetch to a scalar low_at_epoch_ms select. Confirmed no
-- other column of that row was ever read once v_near_current_arrival goes -- the
-- predicted_window_lo_min/hi_min columns themselves stay in the table and stay essential, since
-- they're still read off the UPCOMING row for BLUE's own display, which this migration does not
-- touch.
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
--            (not including it -- RED already owns "did it happen in the current cycle"). Pure
--            recency, not timing: whales sometimes skip a cycle or two, gather at the mouth and
--            abort an entrance, or just hang around the mouth between tides, so a caution
--            persists across the following three cycles instead of blinking on and off within
--            each one the way an arrival-window gate would produce. ~36 hours (3 cycles) is
--            deliberately generous -- YELLOW is a caution, not a call to action, so the fatigue
--            cost of it staying on too long is low, while expiring early risks someone standing
--            down while whales are still plausibly around. This also makes RED's step-down to
--            YELLOW at a cycle turn automatic: the cycle that just closed (and carried the RED
--            sighting) is always inside this 3-cycle lookback, so there's no separate
--            "just-expired" case to handle.
--   BLUE   -- default. Shows the UPCOMING low tide's prediction (the next low strictly after
--            now) when in-season and that prediction has been computed; otherwise a "not
--            expected"/"unavailable" state -- see in_season below. RED/YELLOW are NEVER
--            season-gated: they reflect real confirmed reports, not the model's guess, so an
--            out-of-season confirmed sighting still drives RED/YELLOW exactly as it would in
--            season.
--
-- WHY THE UPCOMING ROW IS FETCHED IN FULL BUT THE CURRENT CYCLE ISN'T: BLUE displays the
-- UPCOMING low's full prediction (a forward-looking "here's when to expect them next"), so that
-- row is fetched whole. The CURRENT cycle, by contrast, only ever needs to answer "when did it
-- start" -- for RED's own-cycle window and YELLOW's 3-cycles-back lookback -- so it's fetched as
-- a scalar low_at_epoch_ms rather than a full row.
--
-- SEASON GATE: the underlying tide/arrival MODEL is still validated on spring 2026 data only
-- (Kenai Beluga Predictor SPEC.md, R^2=0.964 on 28 Mar-May sightings) and has no calendar
-- awareness of its own -- nothing in it refuses to run outside that window, so the gate lives
-- here, not in the edge function. What changed in a prior migration is the WINDOW the gate opens
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
  v_three_cycles_ago_low bigint;
  v_red_sighting_ms bigint;
  v_yellow_sighting_ms bigint;
  v_upcoming_row public.tide_cycles%rowtype;
  v_anchorage_month_day text;
  v_in_season boolean;
  v_phase text;
begin
  -- Current cycle: only the boundary timestamp of the most recent low at/before now is needed --
  -- see "WHY THE UPCOMING ROW IS FETCHED IN FULL BUT THE CURRENT CYCLE ISN'T" above.
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

  -- YELLOW: qualifying sighting in the 3 completed cycles before the current one -- see PHASE
  -- PRIORITY above for why this alone is sufficient, with no additional timing gate.
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
    v_upcoming_row.current_phase,
    v_upcoming_row.current_entrance_knots,
    v_upcoming_row.cfs_used,
    coalesce(v_upcoming_row.warnings, '{}'),
    (v_upcoming_row.predicted_delay_min is not null);
end;
$$;

grant execute on function public.get_kenai_presence_state to anon;
