-- Adds photo_url to sightings and sets up a public bucket to hold sighting photos.
-- Run this in the Supabase SQL editor (or `supabase db push` if the CLI project is linked) --
-- this repo has no DB credentials configured, so it cannot be applied automatically.

alter table public.sightings
  add column if not exists photo_url text;

insert into storage.buckets (id, name, public)
values ('sighting-photos', 'sighting-photos', true)
on conflict (id) do nothing;

-- Note: Postgres has no CREATE POLICY IF NOT EXISTS, so this migration (like any migration)
-- is meant to run once, not be re-applied.

-- Public read so photo_url links resolve directly (bucket is public, but explicit is clearer).
create policy "Public read access for sighting photos"
on storage.objects for select
to public
using (bucket_id = 'sighting-photos');

-- The app has no auth system yet (anon key only), so uploads must come from the anon role.
-- Tighten this to `authenticated` if/when the app adds user accounts.
create policy "Anon can upload sighting photos"
on storage.objects for insert
to anon
with check (bucket_id = 'sighting-photos');

-- Allows re-uploading the same object path (upsert = true) during sync retries.
create policy "Anon can overwrite own sighting photos"
on storage.objects for update
to anon
using (bucket_id = 'sighting-photos')
with check (bucket_id = 'sighting-photos');
