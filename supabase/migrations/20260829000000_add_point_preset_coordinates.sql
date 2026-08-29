-- Adds plain lat/lng columns to point_presets, generated from the existing `point` geography
-- column, so the client can fetch a preset's coordinates via a normal select() instead of
-- needing to parse PostgREST's default hex-WKB text serialization of geography columns (which
-- nothing in this app currently does). Purely additive/read-side -- does not touch the `point`
-- column itself, any other table, or the write path (subscriptions.point/custom_polygon are
-- still written as EWKT text from the client, which Postgres's normal input casting handles).
--
-- Read-only design note: geography(Point,4326)::geometry is a lossless reinterpret cast (no
-- reprojection -- geography's SRID is already 4326), so ST_X/ST_Y on the cast result are exactly
-- the stored longitude/latitude.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically. IF NOT EXISTS makes it safe to
-- re-run. Until this is applied, SupabaseApi.getPointPresets() selects columns that don't exist
-- yet and falls back to an empty list (same degrade path getZones() already has for a region
-- with no seeded zones) -- the point-preset picker chips just won't show anything until then.

alter table public.point_presets
  add column if not exists lat double precision generated always as (ST_Y(point::geometry)) stored;

alter table public.point_presets
  add column if not exists lng double precision generated always as (ST_X(point::geometry)) stored;
