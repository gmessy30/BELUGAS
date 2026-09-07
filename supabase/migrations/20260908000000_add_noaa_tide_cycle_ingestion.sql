-- Populates tide_cycles from NOAA CO-OPS directly (no edge function -- CO-OPS's own JSON API
-- needs no HTML scraping/parsing, unlike the retired mobilegeographics-based predictor). Only
-- the 18 logbook_verification rows exist today; this is what stocks the table going forward.
--
-- STATION: TWC1983 ("Kenai River entrance"), NOT 9455742 ("Kenai City Pier"). Confirmed via
-- NOAA's own metadata API that these are two different physical points (60.55,-151.283 vs
-- 60.545,-151.218, ~3.6km apart), both subordinate stations referenced to Seldovia (9455500)
-- but with distinct offsets -- NOT the same gauge under two IDs. A live datagetter comparison
-- for the same 3-day window showed 9455742's lows running ~37 minutes later and ~0.2m lower
-- than TWC1983's -- using it would have fed kenai_gate_time gate depths referenced to the wrong
-- point and silently invalidated the logbook verification (kenai_gate_time itself would still be
-- right; the live output would just be wrong, with nothing to signal it). The predictor SPEC is
-- explicit that the mouth gauge, not the in-town pier, is the correct reference. TWC1983's
-- alphanumeric id is an ordinary NOAA subordinate-station id (type "S", pre-dating/alongside the
-- 7-digit numeric scheme, no harmonic constants of its own) -- confirmed live and fetchable
-- through this same datagetter endpoint, no legacy offset math needed on our side; NOAA applies
-- its own time/height offsets against Seldovia before returning the response.
--
-- datum=MLLW confirmed against the source of the 18 verification rows (logbook footer: "NOAA
-- Station: TWC1983 | Datum: MLLW"). time_zone=gmt per direction, so the AKDT/AKST transition
-- twice a year never touches how these epochs are computed.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
--
-- =========================================================================================
-- WHY TWO PHASES, NOT ONE FUNCTION: an earlier version of this migration fired net.http_get and
-- blocked on net._http_collect_response(..., false) in the same transaction, to fetch/parse/
-- upsert in one call. That deadlocks, structurally, regardless of timeout -- confirmed directly
-- against pg_net 0.20.4's C source (src/worker.c): the worker runs its own transaction
-- (StartTransactionCommand + PushActiveSnapshot(GetTransactionSnapshot())) and is only woken via
-- a hook literally named wake_at_commit, fired on XACT_EVENT_COMMIT, commented "only wake at
-- commit time to prevent excessive and unnecessary wakes". A request queued and then blocked on
-- from inside the SAME uncommitted transaction can never be dispatched -- the wake it needs
-- doesn't fire until commit, which can't happen while still blocked waiting. Verified live: a
-- bare net.http_get() followed by net._http_response lookup in a SEPARATE statement returned a
-- populated 200 row on the very first check (request 78, created within the same second) -- the
-- actual dispatch is seconds, not minutes; the deadlock is purely an artifact of asking for the
-- result before the request that would produce it has even been sent.
-- =========================================================================================


-- =========================================================================================
-- tide_cycle_ingest_state: singleton row handing a request_id from the phase-1 job to the
-- phase-2 job across the commit boundary above. requested_at is what phase 2 checks for
-- freshness -- see process_tide_cycle_response's own comment -- rather than trusting that
-- whatever's sitting in net._http_response for this id was necessarily produced by TODAY's
-- request. No RLS policies (same posture as tier_code_redeem_attempts/subscriber_identities):
-- purely internal cron hand-off state, never read or written outside the two functions below.
-- =========================================================================================
create table if not exists public.tide_cycle_ingest_state (
  id smallint primary key default 1 check (id = 1),
  pending_request_id bigint,
  requested_at timestamptz
);

insert into public.tide_cycle_ingest_state (id) values (1) on conflict (id) do nothing;

alter table public.tide_cycle_ingest_state enable row level security;
-- Deliberately no policies -- see the table's own comment above.


-- =========================================================================================
-- request_tide_cycle_refresh (PHASE 1): fires the NOAA fetch and returns immediately -- does
-- NOT wait for or read the response (see the file header for why that's structurally
-- impossible in one transaction). Its only job is to queue the request and record which
-- request_id to look for, so its own commit is what lets pg_net's worker actually dispatch it.
-- =========================================================================================
create or replace function public.request_tide_cycle_refresh()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_request_id bigint;
begin
  v_request_id := net.http_get(
    url := 'https://api.tidesandcurrents.noaa.gov/api/prod/datagetter',
    params := jsonb_build_object(
      'station', 'TWC1983',
      'product', 'predictions',
      'interval', 'hilo',
      'datum', 'MLLW',
      'units', 'metric',
      'time_zone', 'gmt',
      'format', 'json',
      -- UTC-anchored regardless of session timezone -- see the file header on why gmt matters
      -- here specifically (the AKDT/AKST boundary the verification was run across).
      'begin_date', to_char((now() at time zone 'UTC') - interval '1 day', 'YYYYMMDD'),
      'end_date', to_char((now() at time zone 'UTC') + interval '45 days', 'YYYYMMDD')
    ),
    timeout_milliseconds := 25000
  );

  update public.tide_cycle_ingest_state
     set pending_request_id = v_request_id,
         requested_at = now()
   where id = 1;
exception
  when others then
    raise warning 'request_tide_cycle_refresh: unexpected error % - %, skipping', sqlstate, sqlerrm;
end;
$$;

revoke all on function public.request_tide_cycle_refresh() from public;
-- No grant needed -- only ever invoked by its own cron job below.


-- =========================================================================================
-- process_tide_cycle_response (PHASE 2): scheduled 2 minutes after phase 1 -- ample against the
-- confirmed seconds-long real dispatch time, and comfortably inside net._http_response's
-- retention (pg_net.ttl, default 6 hours -- rows are opportunistically swept on the worker's own
-- schedule, not deleted the instant the TTL elapses, per pg_net's own docs; reported drift in
-- the wild runs the OTHER direction, rows outliving their TTL rather than disappearing early, so
-- 2 minutes against a 6-hour(+) default has essentially no exposure either way. This repo has no
-- DB credentials to confirm Supabase hasn't lowered pg_net.ttl from that default -- if you want
-- to double check, `show pg_net.ttl;` in the SQL editor).
--
-- STALENESS GUARD: requested_at must be within the last 10 minutes (5x the phase1/phase2 gap,
-- so ordinary scheduler jitter can't trip it, but a whole missed day very much does). Without
-- this, a day where phase 1 fails (network error, NOAA down) leaves tide_cycle_ingest_state
-- holding the LAST SUCCESSFUL day's request_id -- and if that old response row happened to still
-- be sitting in net._http_response (short-lived retention isn't guaranteed to have swept it),
-- phase 2 would silently re-upsert yesterday's fetch and report success, masking the fact that
-- today's fetch never actually ran. The guard turns that into an explicit skip instead.
-- =========================================================================================
create or replace function public.process_tide_cycle_response()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_request_id bigint;
  v_requested_at timestamptz;
  v_status_code int;
  v_error_msg text;
  v_content text;
  v_body jsonb;
  v_predictions jsonb;
  v_row jsonb;
  v_type text;
  v_epoch_ms bigint;
  v_height double precision;
  v_pending_low_epoch bigint;
  v_pending_low_height double precision;
begin
  select pending_request_id, requested_at
    into v_request_id, v_requested_at
    from public.tide_cycle_ingest_state
   where id = 1;

  if v_request_id is null or v_requested_at is null or v_requested_at < now() - interval '10 minutes' then
    raise warning 'process_tide_cycle_response: no fresh pending request (requested_at=%), skipping', v_requested_at;
    return;
  end if;

  select status_code, error_msg, content
    into v_status_code, v_error_msg, v_content
    from net._http_response
   where id = v_request_id;

  if not found then
    raise warning 'process_tide_cycle_response: no response yet for request_id %, skipping', v_request_id;
    return;
  end if;

  if v_error_msg is not null or v_status_code is distinct from 200 then
    raise warning 'process_tide_cycle_response: fetch failed (status_code=%, error_msg=%), skipping',
      v_status_code, v_error_msg;
    return;
  end if;

  v_body := v_content::jsonb;
  v_predictions := v_body -> 'predictions';

  if v_predictions is null then
    raise warning 'process_tide_cycle_response: no predictions array in response, skipping. Body: %',
      left(v_content, 500);
    return;
  end if;

  v_pending_low_epoch := null;

  for v_row in select * from jsonb_array_elements(v_predictions)
  loop
    v_type := v_row ->> 'type';
    v_epoch_ms := (extract(epoch from ((v_row ->> 't')::timestamp at time zone 'UTC')) * 1000)::bigint;
    v_height := (v_row ->> 'v')::double precision;

    if v_type = 'L' then
      -- Defensive only -- Cook Inlet's semidiurnal cycle means two L's in a row can't happen
      -- against real data. If it somehow did, the stale pending low is flushed low-only rather
      -- than silently dropped.
      if v_pending_low_epoch is not null then
        insert into public.tide_cycles (low_at_epoch_ms, low_height_m, source)
        values (v_pending_low_epoch, v_pending_low_height, 'noaa_coops')
        on conflict (low_at_epoch_ms) do update
          set low_height_m = excluded.low_height_m;
      end if;
      v_pending_low_epoch := v_epoch_ms;
      v_pending_low_height := v_height;
    elsif v_type = 'H' and v_pending_low_epoch is not null then
      insert into public.tide_cycles (low_at_epoch_ms, low_height_m, high_at_epoch_ms, high_height_m, source)
      values (v_pending_low_epoch, v_pending_low_height, v_epoch_ms, v_height, 'noaa_coops')
      on conflict (low_at_epoch_ms) do update
        set low_height_m = excluded.low_height_m,
            high_at_epoch_ms = excluded.high_at_epoch_ms,
            high_height_m = excluded.high_height_m;
      v_pending_low_epoch := null;
    end if;
    -- H with no pending low (leading orphan) falls through as a no-op -- the 1-day lookback
    -- guarantees this can't happen against real data (see below).
  end loop;

  -- Trailing L at the window's edge -- no H yet this run, by construction (the window has to end
  -- somewhere). Left low-only; a later run whose window extends past its H fills it in via the
  -- on-conflict branch above. The mirror case -- a LEADING orphan H, whose L fell outside this
  -- window -- can't happen given the 1-day lookback in phase 1: Cook Inlet's cycle is ~12.4h,
  -- well under the 24h lookback, so the L before any H in the window was already fetched.
  if v_pending_low_epoch is not null then
    insert into public.tide_cycles (low_at_epoch_ms, low_height_m, source)
    values (v_pending_low_epoch, v_pending_low_height, 'noaa_coops')
    on conflict (low_at_epoch_ms) do update
      set low_height_m = excluded.low_height_m;
  end if;

  update public.tide_cycle_ingest_state set pending_request_id = null, requested_at = null where id = 1;
exception
  when others then
    raise warning 'process_tide_cycle_response: unexpected error % - %, skipping', sqlstate, sqlerrm;
end;
$$;

revoke all on function public.process_tide_cycle_response() from public;
-- No grant needed -- only ever invoked by its own cron job below.


-- =========================================================================================
-- Scheduled daily, phase 2 exactly 2 minutes after phase 1 (confirmed real dispatch is
-- seconds-long; see the file header). Both extensions already provisioned (per the earlier
-- fitted-model job); create extension if not exists here purely so this migration is
-- self-contained/replayable on its own.
-- =========================================================================================
create extension if not exists pg_net;
create extension if not exists pg_cron;

select cron.unschedule(jobid) from cron.job where jobname in ('request-tide-cycle-refresh', 'process-tide-cycle-response');

select cron.schedule(
  'request-tide-cycle-refresh',
  '0 9 * * *',
  $$select public.request_tide_cycle_refresh();$$
);

select cron.schedule(
  'process-tide-cycle-response',
  '2 9 * * *',
  $$select public.process_tide_cycle_response();$$
);
