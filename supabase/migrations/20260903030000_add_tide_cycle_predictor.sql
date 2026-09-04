-- Kenai River beluga arrival predictor: BLUE's arrival-window prediction, and the real
-- YELLOW/RED rules from the scoped design (tier-gated RED already wired in
-- 20260903020000_wire_observer_tier_into_red_banner.sql -- this migration adds the tidal-cycle
-- machinery around it).
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.
--
-- SOURCING NOTE, since it matters for what this migration relies on: the reference SPEC
-- (Kenai Beluga Predictor SPEC.md ss3.3) cites tides.mobilegeographics.com/locations/3508.html
-- for the tide-low input. Verified live before writing the edge function this migration
-- schedules: location 3508 is actually "Kenilworth Aquatic Garden, Anacostia River, DC" --
-- unrelated. The correct station, confirmed live, is location 3503 ("Kenai River entrance,
-- Cook Inlet, Alaska Tide Chart", same <pre class="predictions-table"> format as the 3504/3506
-- current stations the model was actually fit against, heights in feet -- converted to meters
-- in the edge function since the model's low_height_m warning threshold and the sample table
-- are both in meters). The edge function (supabase/functions/predict-beluga-arrival) uses 3503,
-- not the SPEC's 3508.


-- =========================================================================================
-- TIDE_CYCLES: one row per low tide event at the Kenai River entrance -- doubles as (a) the
-- tidal-cycle boundary table (a cycle is [this row's low, the next row's low), per the scoped
-- design) and (b) the cached BLUE prediction for that low, computed by the
-- predict-beluga-arrival edge function. Public read (curated reference/prediction data, same
-- posture as zones/point_presets) -- no anon write, only the edge function (service role)
-- populates it.
-- =========================================================================================
create table if not exists public.tide_cycles (
  id uuid primary key default gen_random_uuid(),

  -- Cycle start: this low tide's time, UTC epoch ms. Unique -- one row per real low tide
  -- event, upserted idempotently across overlapping fetch windows.
  low_at_epoch_ms bigint not null unique,
  low_height_m double precision,

  -- Cached BLUE prediction for THIS low (see kenai_beluga_predictor.py's predict()). All
  -- nullable together -- a row can exist for cycle-boundary purposes (RED/YELLOW's tidal-cycle
  -- bucketing needs the low time alone) before/without a prediction having been computed for
  -- it yet (e.g. a current-events fetch that didn't cover this particular low this run --
  -- self-heals on a later scheduled run).
  predicted_delay_min integer,
  predicted_window_lo_min integer,
  predicted_window_hi_min integer,
  predicted_arrival_at_epoch_ms bigint,
  -- 'flood-ride' | 'ebb-arrival' | 'slack-arrival' -- the MODEL's own phase classification.
  -- Deliberately named current_phase, not phase -- get_kenai_presence_state's RED/YELLOW/BLUE
  -- banner phase is a different concept and the two must never be confused in the API shape.
  current_phase text,
  current_entrance_knots double precision,
  cfs_used double precision,
  station_ft smallint,
  warnings text[] not null default '{}',
  prediction_computed_at timestamptz,

  source text not null default 'mobilegeographics',
  created_at timestamptz not null default now()
);

create index if not exists tide_cycles_low_at_epoch_ms_idx on public.tide_cycles (low_at_epoch_ms);

alter table public.tide_cycles enable row level security;

drop policy if exists "Public read access to tide_cycles" on public.tide_cycles;
create policy "Public read access to tide_cycles"
on public.tide_cycles for select
to public
using (true);


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
-- SEASON GATE: validated on Spring 2026 data only (Kenai Beluga Predictor SPEC.md, R^2=0.964
-- on 28 Mar-May sightings) -- the model has no calendar awareness itself and nothing in it
-- refuses to run outside that window, so the gate lives here, not in the edge function. Kept
-- to exactly the fitted season (Mar-May, Alaska local month) per this round's explicit
-- direction: "nothing wider" than the fitted season, softened only by the model's own
-- `warnings` array (already returned alongside the prediction), not a second confidence
-- system. Plain, redeploy-free logic (extract(month ...)) rather than a config table -- same
-- philosophy as PresenceBanner.kt's tunable decay-window constants -- easy to revisit once
-- real autumn sightings start landing.
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

  v_in_season := extract(
    month from (to_timestamp(v_now / 1000.0) at time zone 'America/Anchorage')
  ) between 3 and 5;

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


-- =========================================================================================
-- Scheduled refresh: pg_cron calls a thin SECURITY DEFINER wrapper (same reasoning as
-- notify_new_sighting in 20260823000000_add_device_tokens_and_notify_trigger.sql -- the
-- calling context has no direct grant on net.http_post/vault.decrypted_secrets) which fires
-- the predict-beluga-arrival edge function via pg_net. The edge function does the actual
-- fetching/parsing/model computation and upserts into tide_cycles; this function only ever
-- triggers it, never computes anything itself.
--
-- Every 3 hours -- frequent enough to keep 2+ days of future low-tide rows populated (needed
-- so "next low" is always resolvable for RED's cycle-boundary check and BLUE's upcoming-low
-- display), infrequent enough that this is clearly a periodic refresh of static-per-day
-- harmonic pages, not a per-request fetch.
--
-- SETUP (same posture as notify_new_sighting -- none of this can be done from this CLI-less
-- environment):
--   1. Deploy with JWT verification disabled (a cron job has no user JWT either):
--        supabase functions deploy predict-beluga-arrival --no-verify-jwt --project-ref vwbcrctzsqukutvlbqwy
--   2. One-time, in the SQL editor only, never committed:
--        select vault.create_secret(
--          '<a fresh value, never previously committed anywhere>',
--          'predict_beluga_arrival_webhook_secret',
--          'Shared secret trigger_beluga_prediction_refresh sends and the edge function validates'
--        );
--   3. Set the same value as this function's secret:
--        supabase secrets set WEBHOOK_SECRET="<same value as step 2>" --project-ref vwbcrctzsqukutvlbqwy
--      (SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are auto-provisioned; only WEBHOOK_SECRET
--      needs setting -- the edge function doesn't need the Firebase credential
--      notify-new-sighting does.)
-- =========================================================================================
create extension if not exists pg_cron;

create or replace function public.trigger_beluga_prediction_refresh()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_webhook_secret text;
begin
  select decrypted_secret into v_webhook_secret
  from vault.decrypted_secrets
  where name = 'predict_beluga_arrival_webhook_secret'
  limit 1;

  if v_webhook_secret is null then
    raise warning 'trigger_beluga_prediction_refresh: no secret found in Vault under name %, skipping',
      'predict_beluga_arrival_webhook_secret';
    return;
  end if;

  perform net.http_post(
    url := 'https://vwbcrctzsqukutvlbqwy.supabase.co/functions/v1/predict-beluga-arrival',
    body := '{}'::jsonb,
    headers := jsonb_build_object(
      'Content-type', 'application/json',
      'x-webhook-secret', v_webhook_secret
    ),
    timeout_milliseconds := 25000
  );
end;
$$;

select cron.unschedule(jobid) from cron.job where jobname = 'refresh-beluga-prediction';
select cron.schedule(
  'refresh-beluga-prediction',
  '0 */3 * * *',
  $$select public.trigger_beluga_prediction_refresh();$$
);
