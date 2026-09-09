-- Unschedules 'refresh-beluga-prediction' (20260903030000_add_tide_cycle_predictor.sql), the
-- pg_cron job that calls trigger_beluga_prediction_refresh() every 3 hours to invoke the
-- predict-beluga-arrival edge function via pg_net. That edge function was never deployed (no
-- CI/CD exists in this repo, and it's been removed from the repo entirely in the same commit as
-- this migration) -- per the migration trail, this job has been scheduled since 20260903030000
-- and nothing has unscheduled it since, so it has very likely been firing every 3 hours against
-- a target that doesn't exist, failing silently each time.
--
-- Deliberately NOT touching 'request-tide-cycle-refresh' or 'process-tide-cycle-response'
-- (20260908000000_add_noaa_tide_cycle_ingestion.sql) -- those are the current, live NOAA-direct
-- ingestion pipeline ("this is what stocks the table going forward", per that migration's own
-- header), a separate system from the retired regression predictor. tide_cycle_ingest_state and
-- both of its functions stay exactly as they are.
--
-- Safe to run whether or not the job is currently scheduled -- `where jobname = ...` matches
-- zero rows and this is a no-op if it's already gone, same idiom the existing migrations already
-- use for this table.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically. Check `select * from cron.job;` first if you
-- want to confirm the job's current state before running this.

select cron.unschedule(jobid) from cron.job where jobname = 'refresh-beluga-prediction';

-- The wrapper function itself has no other caller now that its only cron job is gone.
drop function if exists public.trigger_beluga_prediction_refresh();
