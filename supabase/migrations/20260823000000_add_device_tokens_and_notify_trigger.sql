-- Minimal flat-broadcast notification pipeline: every registered device gets every new
-- sighting, no zone/subscriber targeting yet (that's the separate, already-scaffolded
-- notification zones/subscriptions system in
-- 20260819000000_add_notification_zones_and_subscriptions.sql -- this table is deliberately
-- simpler and not wired to subscriber_id, since we need something working fast for closed
-- testing).
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically. The trigger below calls an
-- Edge Function (supabase/functions/notify-new-sighting) that must be deployed separately --
-- see that function's header comment and the accompanying setup notes for the exact steps
-- (generating a Firebase service account key, deploying the function, setting its secrets).

create table if not exists public.device_tokens (
  fcm_token text primary key,
  registered_at timestamptz not null default now()
);

alter table public.device_tokens enable row level security;

-- Anon can register/refresh its own token (upserted from the app on every launch and on
-- token refresh) -- no per-device isolation possible without auth, same posture as
-- sightings/subscriptions elsewhere in this repo. No public read/delete policy: the token
-- list is only ever consumed server-side by the edge function below, which runs with the
-- service role key and bypasses RLS entirely -- anon never needs to read this table back.
drop policy if exists "Anon can register device tokens" on public.device_tokens;
create policy "Anon can register device tokens"
on public.device_tokens for insert
to anon
with check (true);

drop policy if exists "Anon can refresh its own device token" on public.device_tokens;
create policy "Anon can refresh its own device token"
on public.device_tokens for update
to anon
using (true)
with check (true);


-- =========================================================================================
-- TRIGGER: call the notify-new-sighting edge function on every new sighting.
-- =========================================================================================
-- supabase_functions.http_request is the same trigger function Supabase's own "Database
-- Webhooks" dashboard feature generates under the hood (built on the pg_net extension,
-- available by default on every Supabase project) -- this just writes that wiring directly
-- as SQL instead of clicking through the dashboard.
--
-- Auth: the edge function is deployed with --no-verify-jwt (a DB trigger has no user JWT to
-- present) and instead validates the x-webhook-secret header below against a WEBHOOK_SECRET
-- edge function secret you set separately. This value is a purpose-built shared secret
-- invented just for this one webhook -- NOT a real Supabase API key -- but still treat it as
-- sensitive: anyone with it can trigger a broadcast notification to every registered device.
-- Set the identical value via `supabase secrets set WEBHOOK_SECRET=...` (see the edge
-- function's header comment for the full setup steps).
drop trigger if exists on_sighting_insert_notify on public.sightings;
create trigger on_sighting_insert_notify
after insert on public.sightings
for each row
execute function supabase_functions.http_request(
  'https://vwbcrctzsqukutvlbqwy.supabase.co/functions/v1/notify-new-sighting',
  'POST',
  '{"Content-type":"application/json","x-webhook-secret":"REDACTED-SECRET"}',
  '{}',
  '5000'
);
