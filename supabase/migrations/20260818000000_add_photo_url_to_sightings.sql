-- Adds photo_url to sightings and sets up a public bucket to hold sighting photos.
-- Run this in the Supabase SQL editor (or `supabase db push` if the CLI project is linked) --
-- this repo has no DB credentials configured, so it cannot be applied automatically.

alter table public.sightings
  add column if not exists photo_url text;

insert into storage.buckets (id, name, public)
values ('sighting-photos', 'sighting-photos', true)
on conflict (id) do nothing;

-- Postgres has no CREATE POLICY IF NOT EXISTS, so each policy below is preceded by a
-- DROP POLICY IF EXISTS, making this migration safe to re-run in full (e.g. if it was only
-- partially applied last time, as happened here: photo_url landed but the bucket/policies
-- below did not).

-- Public read so photo_url links resolve directly (bucket is public, but explicit is clearer).
drop policy if exists "Public read access for sighting photos" on storage.objects;
create policy "Public read access for sighting photos"
on storage.objects for select
to public
using (bucket_id = 'sighting-photos');

-- The app has no auth system yet (anon key only), so uploads must come from the anon role.
-- Tighten this to `authenticated` if/when the app adds user accounts.
drop policy if exists "Anon can upload sighting photos" on storage.objects;
create policy "Anon can upload sighting photos"
on storage.objects for insert
to anon
with check (bucket_id = 'sighting-photos');

-- Allows re-uploading the same object path (upsert = true) during sync retries.
drop policy if exists "Anon can overwrite own sighting photos" on storage.objects;
create policy "Anon can overwrite own sighting photos"
on storage.objects for update
to anon
using (bucket_id = 'sighting-photos')
with check (bucket_id = 'sighting-photos');
