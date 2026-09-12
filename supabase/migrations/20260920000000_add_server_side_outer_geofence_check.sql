-- Server-side enforcement of the coarse OUTER Cook Inlet geofence bound
-- (GeofenceUtils.OUTER_GEOFENCE_MIN/MAX_LAT/LNG on the native client, geofence.js's identical
-- constants on the web client) directly on the sightings table itself, via a CHECK constraint.
--
-- BEFORE THIS MIGRATION: this bound was entirely client-trusted. Both clients run the same
-- coarse-box check before ever attempting an insert, but nothing on the server stopped a request
-- that skipped it -- a modified client, a direct API call using the (public, anon-key) insert
-- policy, or a client-side bug. Confirmed directly: no trigger or CHECK constraint anywhere in
-- this repo's prior migrations touches sightings.whale_lat/whale_lng bounds at all (the only
-- existing sightings triggers are set_sighting_observer_tier and notify_new_sighting, neither of
-- which validates position). This closes that gap for every future writer, native and web alike,
-- not just whichever ones happen to run the client-side check first.
--
-- DELIBERATELY JUST THE COARSE OUTER BOUND, not the finer is_geofence_verified buffer-distance
-- determination -- that one is a judgment call based on proximity to real coastline/river data
-- (with a user-facing SAVE ANYWAY override for a rejection), not a hard boolean fact suitable for
-- a CHECK constraint. Enforcing it server-side would mean duplicating CoastlineGeometry.kt's real
-- polygon/centerline data and PostGIS logic in SQL, which is out of scope for closing this
-- specific gap. is_geofence_verified stays exactly as client-trusted as it already was (see
-- 20260819000000_add_notification_zones_and_subscriptions.sql's own "KNOWN GAP" comment on that
-- column) -- this migration only closes the coarser, far-cheaper-to-enforce "is this even
-- remotely Cook Inlet" check.
--
-- SCOPED TO whale_lat/whale_lng ONLY, not the old lat/lng columns -- those are frozen/observer-
-- position and no current code path (native or web) writes them at all (see
-- 20260901000000_add_whale_position_columns.sql's redesign). NULL is allowed for both columns
-- together (covers the pre-redesign legacy rows, and any future row that's genuinely unplaced),
-- but a row with exactly one of the two set null is rejected as malformed -- no current code path
-- ever produces that, on either client, so this is a bonus integrity check, not a new restriction
-- on anything that currently works.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.

alter table public.sightings
  drop constraint if exists sightings_whale_position_outer_geofence_check;

alter table public.sightings
  add constraint sightings_whale_position_outer_geofence_check
  check (
    (whale_lat is null and whale_lng is null)
    or (
      whale_lat between 59.0 and 61.8
      and whale_lng between -154.2 and -148.0
    )
  );
