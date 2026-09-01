-- Observer-position -> whale-position redesign. `lat`/`lng` have meant "where the observer
-- stood" since this table's creation; `heading_degrees` has meant "observer->animal compass
-- bearing". Both are being replaced going forward by a position the app estimates for the
-- WHALE itself, never the observer -- the observer's raw position is no longer sent to
-- Supabase at all once the client side of this change lands.
--
-- Deliberately NEW columns rather than reinterpreting `lat`/`lng`/`heading_degrees` in place:
-- an old CSV export or a script keyed on those column names must not silently start reading
-- new-meaning data under an old-looking name. The old columns are left completely untouched
-- (not renamed, not backfilled, not dropped) -- see NOT NULL note below for why.
--
-- position_source is NULLABLE, not NOT NULL, despite every new row always populating it: this
-- table already has 14 existing rows under the old lat/lng meaning, and a NOT NULL column
-- added to a non-empty table requires a default, which would stamp all 14 with some
-- placeholder value and destroy the one thing this column exists to provide -- a permanent,
-- unambiguous "is this an old-meaning row or a new-meaning row" discriminator
-- (`position_source is null` <=> legacy observer-position row). The application layer is
-- responsible for always setting it on new inserts; nothing in this migration enforces that,
-- by design, so the discriminator itself can never be forced onto old rows by accident.
--
-- No backfill: the 14 existing rows predate this design and can't be faithfully converted (9
-- of the 14 have the old heading_degrees/distance_radius_meters populated and could be
-- mathematically projected into a guessed whale position, but that would manufacture a fact
-- nobody actually recorded for rows that are test data due to be cleared anyway -- not a
-- migration's job).
alter table public.sightings
  add column if not exists whale_lat double precision,
  add column if not exists whale_lng double precision,
  add column if not exists uncertainty_radius_meters double precision,
  add column if not exists uncertainty_bucket text,
  add column if not exists travel_bearing_degrees double precision,
  add column if not exists travel_bearing_source text,
  add column if not exists position_source text;

-- position_source values: 'PIN' (ManualLoggingScreen -- the dropped pin IS the whale position,
-- no projection), 'PROJECTED' (LoggingScreen -- observer GPS + a real heading+distance reading
-- projected outward via destinationPoint), 'FALLBACK' (LoggingScreen, no heading given --
-- CoastlineGeometry's new offshore-perpendicular walk guessed a position; see that function's
-- doc comment). Enforced at the application layer, not a DB constraint, so a legacy row with
-- position_source still null is never mistaken for an unrecognized/invalid new-format value.
