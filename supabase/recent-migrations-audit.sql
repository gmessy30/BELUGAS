-- Audit: is each migration from the last several days actually live? Per MIGRATIONS.md -- the
-- repo is not evidence of what's deployed, and 20260911's unique index just proved that directly
-- (committed days ago, never run). Each check below is independent -- none of them assume an
-- earlier migration in this list landed, even where the real functions build on each other, so a
-- gap in the middle can't hide behind a later one appearing to work.
--
-- Read each result plainly: expected TRUE/present means that migration is live; FALSE/absent/
-- empty means it isn't and needs running (find it under supabase/migrations/<name>.sql).
--
-- NOT a migration itself -- a diagnostic script to run in the SQL editor, kept alongside
-- MIGRATIONS.md rather than under migrations/ so tooling that runs everything in that folder
-- doesn't try to apply it.


-- 1. 20260907000000_add_tide_cycle_high_and_gate_time_function.sql
--    Expect: both true.
select
  exists (
    select 1 from information_schema.columns
    where table_schema='public' and table_name='tide_cycles' and column_name='high_at_epoch_ms'
  ) as m_20260907000000_high_at_epoch_ms_column_exists,
  exists (
    select 1 from pg_proc where proname = 'kenai_gate_time'
  ) as m_20260907000000_kenai_gate_time_function_exists;

-- 2. 20260907010000_drop_low_at_or_below_gate_datum.sql
--    Expect: false (column should be GONE if this ran).
select exists (
  select 1 from information_schema.columns
  where table_schema='public' and table_name='tide_cycles' and column_name='low_at_or_below_gate_datum'
) as m_20260907010000_dead_column_still_present;

-- 3. 20260908000000_add_noaa_tide_cycle_ingestion.sql
--    Expect: table true, both functions true, both cron jobs present (2 rows).
select
  exists (
    select 1 from information_schema.tables
    where table_schema='public' and table_name='tide_cycle_ingest_state'
  ) as m_20260908000000_ingest_state_table_exists,
  exists (select 1 from pg_proc where proname = 'request_tide_cycle_refresh') as m_20260908000000_request_fn_exists,
  exists (select 1 from pg_proc where proname = 'process_tide_cycle_response') as m_20260908000000_process_fn_exists;

select jobname, schedule, active
from cron.job
where jobname in ('request-tide-cycle-refresh', 'process-tide-cycle-response');
-- ^ expect 2 rows for m_20260908000000. Zero rows means the cron half never ran even if the
-- functions above happen to exist (e.g. from a partial apply).

-- 4. 20260909000000_return_gate_times_from_get_kenai_presence_state.sql
--    Expect: true. Reads the live function's actual output columns rather than trusting which
--    migration file last touched it -- so this stays valid evidence even though 20260912 and
--    20260913 (below) redefine this same function again on top of it.
select exists (
  select 1
  from jsonb_object_keys(to_jsonb((select t from public.get_kenai_presence_state() t limit 1))) k
  where k = 'gate_time_possible_epoch_ms'
) as m_20260909000000_gate_time_column_present;

-- 5. 20260910000000_widen_tier_roster_tier_check_to_include_demoted_tier_3.sql
--    Expect: constraint definition contains 3, e.g. "CHECK (tier = ANY (ARRAY[1, 2, 3]))".
select conname, pg_get_constraintdef(oid) as definition
from pg_constraint
where conname = 'tier_roster_tier_check';

-- 6. 20260911000000_add_zone_subscription_unique_constraint.sql
--    Expect: one row. Confirmed live 2026-09-08 after being committed for days unapplied --
--    the gap that prompted this whole audit script.
select indexname, indexdef
from pg_indexes
where tablename = 'subscriptions' and indexname = 'subscriptions_subscriber_zone_unique_idx';

-- 7. 20260912000000_add_gate_time_holdover.sql
--    Expect: true. get_kenai_presence_state takes a bigint parameter (defaulted, but regprocedure
--    resolution needs the exact signature regardless of defaults) -- the bare-name cast doesn't
--    resolve; this must be get_kenai_presence_state(bigint).
select
  pg_get_functiondef('public.get_kenai_presence_state(bigint)'::regprocedure) like '%v_holdover_ms%'
  as m_20260912000000_holdover_present;

-- 8. 20260913000000_add_kenai_departure_report.sql
--    Expect: both tables true, function true, RED-guard ratchet true.
select
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='kenai_departure_viewing_areas') as m_20260913000000_viewing_areas_table_exists,
  exists (select 1 from information_schema.tables where table_schema='public' and table_name='kenai_departure_reports') as m_20260913000000_reports_table_exists,
  exists (select 1 from pg_proc where proname = 'report_kenai_departure') as m_20260913000000_report_fn_exists,
  pg_get_functiondef('public.get_kenai_presence_state(bigint)'::regprocedure) like '%v_red_lower_bound_ms%' as m_20260913000000_ratchet_present;

-- 9. 20260914000000_swap_kenai_departure_polygon_and_expose_tier_check.sql
--    Expect: tier-check function true, and the real 18-vertex polygon (not the placeholder box
--    from 20260913) -- checked by vertex count on the exterior ring rather than eyeballing WKT.
select exists (
  select 1 from pg_proc where proname = 'is_kenai_departure_reporter'
) as m_20260914000000_tier_check_fn_exists;

select
  slug,
  ST_NPoints(ST_ExteriorRing(boundary)) as vertex_count -- expect 18 (placeholder box would be 5)
from public.kenai_departure_viewing_areas
where slug = 'kenai_mouth';

-- 10. 20260915000000_widen_kenai_closing_margin_for_coast_notch.sql
--     Expect: 500. Confirmed live directly (area 42.74 -> 43.53 km^2).
select public.kenai_closing_margin_meters() as m_20260915000000_closing_margin;
