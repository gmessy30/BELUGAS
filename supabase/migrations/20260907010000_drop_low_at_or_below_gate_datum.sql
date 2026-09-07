-- low_at_or_below_gate_datum (20260905000000_record_gate_datum_regime_on_tide_cycles.sql)
-- recorded which confidence regime a low tide fell into FOR THE FITTED DELAY MODEL -- below vs.
-- above the tide gauge's own 0.0m datum was a statement about that model's fit quality (well-fit
-- below the datum, materially weaker above it). The entrance-gate floor replacing that model
-- (see kenai_gate_time, 20260907000000_add_tide_cycle_high_and_gate_time_function.sql) has no
-- fit quality to have a regime about -- it's deterministic geometry, computable or not, nothing
-- in between. This column has no meaning left to record, so it's dropped outright rather than
-- left inert.
--
-- Dropped as its own migration, separate from the wider fitted-model column cleanup
-- (predicted_delay_min, predicted_window_lo_min/hi_min, predicted_arrival_at_epoch_ms,
-- current_phase, current_entrance_knots, cfs_used, station_ft, warnings,
-- prediction_computed_at), which stays untouched until the gate-floor path is live and verified
-- -- this one column is unambiguously dead regardless of how that verification goes, so there's
-- no reason to wait on it.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

alter table public.tide_cycles
  drop column if exists low_at_or_below_gate_datum;
