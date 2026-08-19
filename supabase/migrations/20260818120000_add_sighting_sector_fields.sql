-- Adds compass-heading + bucketed-distance fields used to render an annular sector
-- (origin = observer's lat/lng, direction = heading, radius = distance bucket) on the map.
-- Run this in the Supabase SQL editor (or `supabase db push`) --
-- this repo has no DB credentials configured, so it cannot be applied automatically.

alter table public.sightings
  add column if not exists heading_degrees double precision,
  add column if not exists heading_source text,
  add column if not exists heading_accuracy_degrees double precision,
  add column if not exists distance_bucket text,
  add column if not exists distance_radius_meters double precision;

-- Note: ALTER TABLE ... ADD CONSTRAINT has no IF NOT EXISTS clause in Postgres, so this
-- migration (like any migration) is meant to run once, not be re-applied.
alter table public.sightings
  add constraint sightings_heading_source_check
    check (heading_source is null or heading_source in ('SENSOR', 'MANUAL'));

alter table public.sightings
  add constraint sightings_distance_bucket_check
    check (distance_bucket is null or distance_bucket in ('CLOSE', 'MEDIUM', 'FAR'));
