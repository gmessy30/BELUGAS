-- Two unrelated features bundled into one migration file per explicit request (item 90's
-- ACTIVITIES field, item 63's tier-1 confirmation of an EXISTING sighting) -- not because they're
-- related, only because they were asked for together.
--
-- Run this in the Supabase SQL editor (or `supabase db query --linked --file <path>`) -- this
-- repo has no DB credentials configured, so it cannot be applied automatically.
--
-- BUILT ON THE CURRENTLY DEPLOYED get_kenai_presence_state --
-- 20260922000000_ratchet_departure_report_for_remainder_of_cycle.sql's version, confirmed
-- identical to what's actually live before writing this. See that function's own section below
-- for exactly what changed (one line) and what didn't (everything else, copied verbatim).


-- =========================================================================================
-- ITEM 90: ACTIVITIES -- what the observer saw the whale(s) doing. Optional, multi-select
-- (six allowed values), plus a free-text note that only ever accompanies OTHER.
-- =========================================================================================

alter table public.sightings
  add column if not exists activities text[],
  add column if not exists activity_note text;

-- <@ (is-contained-by), not = ANY(...) per element or a bespoke loop -- activities can be null
-- (nothing selected) or any subset of these six values, any order, duplicates harmless (the
-- client never sends any). A null activities value trivially satisfies this (null <@ anything is
-- null, not false, and CHECK only rejects an explicit FALSE), so "nothing selected" needs no
-- special-casing here.
alter table public.sightings
  add constraint sightings_activities_allowed_check
  check (activities <@ array[
    'TRAVELLING', 'MILLING', 'FEEDING_OBSERVED',
    'BENTHIC_FEEDING_EVIDENCED', 'COURTSHIP_BEHAVIOURS', 'OTHER'
  ]::text[]);

-- Matches the client's own maxlength=140 input cap (webapp/index.html's
-- #activity-other-note-input, enforced again in submit-view.js's buildManualSightingRecord) --
-- belt-and-suspenders, not the only enforcement.
alter table public.sightings
  add constraint sightings_activity_note_length_check
  check (activity_note is null or char_length(activity_note) <= 140);

-- COLUMN-LEVEL GRANT: same posture as observer_tier before it (20260903010000) -- ordinary
-- sighting data, anon-readable like everything else on this table except subscriber_id. Brand new
-- columns need this explicit grant regardless -- the table-level `revoke select ... from anon` in
-- that same migration means a new column has NO grant at all until one is stated, unlike a
-- pre-existing column that might still carry an old blanket grant to revoke first.
grant select (activities, activity_note) on public.sightings to anon;

-- export_sightings: DROP + CREATE, not CREATE OR REPLACE -- the RETURNS shape is changing (three
-- new columns across this migration's two features), and Postgres won't let CREATE OR REPLACE
-- change an existing function's return type, same reason the observer_tier migration's own
-- rewrite of this function needed the DROP first.
drop function if exists public.export_sightings(bigint, bigint, double precision, double precision, double precision, double precision, text);

create function public.export_sightings(
  p_start_ms bigint,
  p_end_ms bigint,
  p_min_lat double precision,
  p_max_lat double precision,
  p_min_lng double precision,
  p_max_lng double precision,
  p_zone_slug text default null
)
returns table (
  id uuid,
  created_at timestamptz,
  observer_id text,
  lat double precision,
  lng double precision,
  heading text,
  count_whites integer,
  count_greys integer,
  count_calves integer,
  count_unknown integer,
  photo_url text,
  observed_at_epoch_ms bigint,
  observer_type text,
  heading_degrees double precision,
  heading_source text,
  heading_accuracy_degrees double precision,
  distance_bucket text,
  distance_radius_meters double precision,
  is_geofence_verified boolean,
  whale_lat double precision,
  whale_lng double precision,
  uncertainty_radius_meters double precision,
  uncertainty_bucket text,
  travel_bearing_degrees double precision,
  travel_bearing_source text,
  position_source text,
  observer_tier smallint,
  activities text[],
  activity_note text,
  confirmed_at timestamptz
)
language sql
stable
as $$
  select
    s.id, s.created_at, s.observer_id, s.lat, s.lng, s.heading,
    s.count_whites, s.count_greys, s.count_calves, s.count_unknown,
    s.photo_url, s.observed_at_epoch_ms, s.observer_type,
    s.heading_degrees, s.heading_source, s.heading_accuracy_degrees,
    s.distance_bucket, s.distance_radius_meters, s.is_geofence_verified,
    s.whale_lat, s.whale_lng, s.uncertainty_radius_meters, s.uncertainty_bucket,
    s.travel_bearing_degrees, s.travel_bearing_source, s.position_source,
    s.observer_tier, s.activities, s.activity_note, s.confirmed_at
  from public.sightings s
  where s.observed_at_epoch_ms between p_start_ms and p_end_ms
    and s.whale_lat is not null and s.whale_lng is not null
    and s.whale_lat between p_min_lat and p_max_lat
    and s.whale_lng between p_min_lng and p_max_lng
    and (
      p_zone_slug is null
      or exists (
        select 1 from public.zones z
        where z.slug = p_zone_slug
          and public.is_whale_position_in_zone(z.id, s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
      )
    )
  order by s.observed_at_epoch_ms desc;
$$;

-- DROP removed the previous grant along with the old function -- restated explicitly, not
-- assumed (a freshly created function otherwise defaults to PUBLIC EXECUTE, wider than this
-- repo's anon-only convention).
grant execute on function public.export_sightings to anon;


-- =========================================================================================
-- ITEM 63: TIER-1 CONFIRMATION OF AN EXISTING SIGHTING -- a tier-1 observer can vouch for a
-- sighting they didn't personally file, strengthening its RED-eligibility without altering who
-- reported it or their own tier at submission time.
-- =========================================================================================

alter table public.sightings
  add column if not exists confirmed_by_subscriber_id uuid,
  add column if not exists confirmed_at timestamptz;

-- confirmed_at gets the same ordinary anon-SELECT grant as any other sighting column -- the
-- client needs it to decide whether to show the CONFIRM SIGHTING button or the "Confirmed" badge.
-- confirmed_by_subscriber_id deliberately does NOT: same lockdown as the original subscriber_id
-- column, for the identical reason -- it identifies a specific device/observer's own action, not
-- public sighting data. Nothing in this app's client ever needs to read WHO confirmed a row, only
-- WHETHER it's confirmed.
grant select (confirmed_at) on public.sightings to anon;

-- is_tier_one_observer: a general-purpose "is this subscriber tier-1" visibility check --
-- deliberately its own function, not a reuse of is_kenai_departure_reporter
-- (20260914000000_swap_kenai_departure_polygon_and_expose_tier_check.sql), which is the exact
-- same get_observer_tier(...) = 1 test but named for a Kenai-specific feature that has nothing to
-- do with confirming a sighting -- reusing it here would be a confusing name for what it actually
-- gates. Purely a visibility signal, same "visibility is UX, enforcement is server-side" split as
-- that function and report_kenai_departure both already use -- confirm_sighting below re-checks
-- tier itself regardless of what this returns.
create or replace function public.is_tier_one_observer(p_subscriber_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select public.get_observer_tier(p_subscriber_id) = 1
$$;

revoke execute on function public.is_tier_one_observer from public;
grant execute on function public.is_tier_one_observer to anon;

-- confirm_sighting: the SOLE write path for confirmed_by_subscriber_id/confirmed_at. Deliberately
-- does NOT touch observer_tier -- confirming a sighting doesn't change who reported it or their
-- tier at submission time, only that a tier-1 observer has since vouched for it. The single UPDATE
-- below is what actually enforces every refusal rule atomically (no separate SELECT-then-UPDATE
-- race, same pattern as redeem_tier_code's own claim):
--   - confirmed_at is null        -- refuses an already-confirmed row (first confirmation wins,
--                                    never reassigned; also makes two simultaneous confirm
--                                    attempts on the same row race-safe: whichever commits first
--                                    wins, the second's WHERE then matches zero rows)
--   - subscriber_id IS DISTINCT   -- refuses the caller confirming their OWN sighting
--     FROM p_subscriber_id           (self-confirmation defeats the entire point of an
--                                    independent tier-1 vouch); IS DISTINCT FROM (not <>) so a
--                                    row with a null subscriber_id (no reporter on record at all)
--                                    is still confirmable, matching ordinary null-safe intent
-- The tier check itself happens BEFORE the update, re-verified here regardless of what
-- is_tier_one_observer told the client earlier. Every refusal reason collapses to the same
-- `false`, matching report_kenai_departure's own single-boolean design -- the client's own
-- visibility check already covers the ordinary "why is this button even showing" case; this is
-- the actual enforcement.
create or replace function public.confirm_sighting(p_sighting_id uuid, p_subscriber_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if public.get_observer_tier(p_subscriber_id) is distinct from 1 then
    return false;
  end if;

  update public.sightings
     set confirmed_by_subscriber_id = p_subscriber_id,
         confirmed_at = now()
   where id = p_sighting_id
     and confirmed_at is null
     and subscriber_id is distinct from p_subscriber_id;

  return found;
end;
$$;

revoke execute on function public.confirm_sighting from public;
grant execute on function public.confirm_sighting to anon;


-- =========================================================================================
-- get_kenai_presence_state: RED-qualifying now ALSO includes a CONFIRMED sighting, regardless of
-- its own observer_tier -- a tier-1 observer vouching for someone else's report is exactly as
-- strong a signal as that tier-1 observer having filed it themselves. The ONLY change from the
-- currently-deployed version (20260922000000) is the one added `or s.confirmed_at is not null`
-- condition in the RED-sighting query below -- everything else (the departure-report ratchet,
-- the season gate, the gate-time floor) is copied verbatim, unchanged.
-- =========================================================================================

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
$$;

grant execute on function public.get_kenai_presence_state to anon;
