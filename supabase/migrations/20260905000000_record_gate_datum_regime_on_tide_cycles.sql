-- Records which confidence regime a low tide falls into, even though nothing displays it yet.
--
-- Two regimes, real and different, not a matter of degree: below 0.0m (the tide gauge's own
-- datum), the current has room to build and the model is well-fit against it. Above 0.0m, the
-- gate tide itself is effectively absent -- the water never drops far enough for the station's
-- own low-tide reading to mean what it normally means -- so predictions there are instead
-- estimated by correlating last spring's higher-than-gate arrivals against current/riverflow,
-- a materially weaker basis than the below-gate fit. Deliberately NOT surfaced to the banner or
-- as a new warnings entry (see the report handed back alongside this migration) -- the app's
-- framing is that every prediction is a working hypothesis, stated plainly, in both regimes
-- alike, so a per-regime confidence display would contradict that framing rather than support
-- it. This column exists so the distinction is captured server-side from day one regardless --
-- available to query/analyze later without needing to backfill a judgment call about historical
-- rows.
--
-- GENERATED, not a plain column the edge function sets: low_height_m already carries the raw
-- fact this is entirely derived from, so a generated column can never drift out of sync with it
-- and needs no application code to keep populated -- correct automatically for every row any
-- future insert path writes, not just predict-beluga-arrival's own upserts.
--
-- Boundary: <= 0.0m counts as below-gate (the well-modeled side); > 0.0m is above-gate. NULL
-- when low_height_m itself is NULL (no height data at all -- can't classify what wasn't fetched).
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.

alter table public.tide_cycles
  add column if not exists low_at_or_below_gate_datum boolean
    generated always as (low_height_m <= 0.0) stored;
