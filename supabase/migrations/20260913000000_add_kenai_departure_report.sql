-- Tier-1 departure report: a claimed tier-1 observer, physically at the Kenai river mouth, can
-- report "they left" -- steps a RED phase down to YELLOW. Never clears to BLUE (one person's
-- judgement shouldn't produce a full all-clear; a false all-clear is worse than an overlong
-- RED). Self-heals rather than needing anyone to undo a mistaken call: expires after 3 hours,
-- and a genuinely NEW qualifying sighting logged after the report still restores RED on its own
-- (see the get_kenai_presence_state changes below for how).
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
--
-- BUILT ON THE CURRENTLY DEPLOYED get_kenai_presence_state -- 20260912000000's version (gate
-- times + the 90-minute gate holdover), confirmed identical to what's actually live before
-- writing this. This function's signature is unchanged from that version (no new output
-- columns), so this is a plain `create or replace`, no DROP needed.


-- =========================================================================================
-- KENAI_DEPARTURE_VIEWING_AREAS: the polygon(s) a tier-1 device must be physically inside for
-- the departure-report button to work at all -- "you can't watch a departure from town."
-- PLACEHOLDER BOUNDARY below (a rough ~500m box around the river-mouth center used elsewhere in
-- this codebase, Regions.COOK_INLET's own defaultCenterLat/Lng) -- swap it for the real KML
-- export with the same `insert ... on conflict (slug) do update set boundary = excluded.boundary`
-- pattern already used for zones/point_presets seeding (20260819130000), so the follow-up
-- migration is a one-line coordinate swap, not a schema change.
--
-- Separate table from `zones` (the notification-subscription polygons) rather than reusing it --
-- this gates a write action (who's allowed to file a departure report), not a read/subscription
-- concern, and conflating the two would mean a future zones edit for subscription purposes could
-- accidentally change who's allowed to report a departure.
-- =========================================================================================
create table if not exists public.kenai_departure_viewing_areas (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null,
  boundary geometry(Polygon, 4326) not null,
  created_at timestamptz not null default now()
);

alter table public.kenai_departure_viewing_areas enable row level security;

-- Read-only reference data, same posture as zones/point_presets -- the client needs this
-- geometry too, to decide locally whether to show the button at all (visibility is UX; the
-- actual enforcement is report_kenai_departure's own server-side ST_Contains check below, which
-- re-tests the submitted lat/lng regardless of what the client decided).
drop policy if exists "Public read access to kenai departure viewing areas" on public.kenai_departure_viewing_areas;
create policy "Public read access to kenai departure viewing areas"
on public.kenai_departure_viewing_areas for select
to public
using (true);

-- PLACEHOLDER -- replace via the same on-conflict upsert once the real KML lands.
insert into public.kenai_departure_viewing_areas (slug, name, boundary) values (
  'kenai_mouth', 'Kenai River Mouth (PLACEHOLDER)',
  ST_GeomFromText('POLYGON((
    -151.2633 60.5499,
    -151.2533 60.5499,
    -151.2533 60.5589,
    -151.2633 60.5589,
    -151.2633 60.5499
  ))', 4326)
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary;


-- =========================================================================================
-- KENAI_DEPARTURE_REPORTS: append-only log of departure reports. No update/delete from the app
-- at all -- a mistaken report is meant to self-expire (see get_kenai_presence_state below), not
-- be edited or removed by anyone.
-- =========================================================================================
create table if not exists public.kenai_departure_reports (
  id uuid primary key default gen_random_uuid(),
  subscriber_id uuid not null,
  reported_at_epoch_ms bigint not null,
  reported_lat double precision not null,
  reported_lng double precision not null,
  created_at timestamptz not null default now()
);

create index if not exists kenai_departure_reports_reported_at_idx
  on public.kenai_departure_reports (reported_at_epoch_ms desc);

alter table public.kenai_departure_reports enable row level security;

-- Read-only for anon (get_kenai_presence_state runs as invoker/anon, same as it always has --
-- not converting it to SECURITY DEFINER just for this, see that function's own history of
-- staying invoker-only). No anon write policy at all -- every insert goes through
-- report_kenai_departure below, which is SECURITY DEFINER specifically so writes don't need a
-- broad anon insert policy on the raw table (same "narrow door" posture as redeem_tier_code has
-- for tier_roster).
drop policy if exists "Anon can read kenai departure reports" on public.kenai_departure_reports;
create policy "Anon can read kenai departure reports"
on public.kenai_departure_reports for select
to anon
using (true);


-- =========================================================================================
-- report_kenai_departure: the single narrow door for filing a departure report. Three checks,
-- all server-side -- the client's own polygon check only controls whether the button is shown
-- (visibility is UX; location is client-asserted, so enforcement can't live there):
--   1. p_subscriber_id actually holds a claimed tier-1 seat (get_observer_tier, existing).
--   2. p_lat/p_lng actually falls inside a kenai_departure_viewing_areas polygon.
--   3. The phase is actually RED right now -- a departure report when nothing's RED is
--      meaningless, so it's a no-op (returns false) rather than doing something confusing.
-- Recomputes get_kenai_presence_state itself for check 3 rather than re-deriving RED separately,
-- so this stays correct as that function evolves -- it's already been replaced three times.
-- =========================================================================================
create or replace function public.report_kenai_departure(
  p_subscriber_id uuid,
  p_lat double precision,
  p_lng double precision
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_now bigint := (extract(epoch from now()) * 1000)::bigint;
  v_current_phase text;
begin
  if public.get_observer_tier(p_subscriber_id) is distinct from 1 then
    return false;
  end if;

  if not exists (
    select 1
    from public.kenai_departure_viewing_areas
    where ST_Contains(boundary, ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326))
  ) then
    return false;
  end if;

  select phase into v_current_phase from public.get_kenai_presence_state(v_now);
  if v_current_phase is distinct from 'RED' then
    return false;
  end if;

  insert into public.kenai_departure_reports (subscriber_id, reported_at_epoch_ms, reported_lat, reported_lng)
  values (p_subscriber_id, v_now, p_lat, p_lng);

  return true;
end;
$$;

revoke execute on function public.report_kenai_departure from public;
grant execute on function public.report_kenai_departure to anon;


-- =========================================================================================
-- get_kenai_presence_state: two additions on top of 20260912000000's version (gate holdover),
-- everything else -- current/upcoming row selection, the gate-time holdover itself, in_season,
-- warnings, etc. -- carried over unchanged.
--
-- 1. RED-ELIGIBILITY RATCHET: if an active (within 3h) departure report exists, the RED query's
--    lower bound moves forward from the cycle's own low to just after the report's own
--    timestamp. This is what makes the whole feature self-healing -- a sighting logged BEFORE
--    the report no longer counts (so a stale RED doesn't linger), but a NEW qualifying sighting
--    logged AFTER the report still counts and restores RED on its own, no manual undo needed.
--
-- 2. YELLOW FLOOR: if the phase would otherwise fall through to BLUE (no RED, no ordinary
--    YELLOW-qualifying sighting in the 3 prior cycles either) while a departure report is still
--    active, force YELLOW instead. This is the literal "never clears to BLUE" -- once the report
--    expires past 3h with nothing new, the floor lifts and the phase falls through to whatever
--    is actually true by then (most likely BLUE).
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
  -- clock, not the tide's (see this migration's own header for why 3h flat rather than a cycle
  -- boundary).
  select max(reported_at_epoch_ms) into v_departure_report_at_ms
  from public.kenai_departure_reports
  where reported_at_epoch_ms > v_now - v_departure_report_ttl_ms
    and reported_at_epoch_ms <= v_now;

  -- RED: qualifying sighting anywhere in the current, still-open cycle -- lower bound ratcheted
  -- past an active departure report's own timestamp, so only a sighting logged AFTER the report
  -- can re-trigger RED (see this function's own header, point 1).
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
  -- empty. See this function's own header, point 2.
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
