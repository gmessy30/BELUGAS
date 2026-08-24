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
-- Originally written against supabase_functions.http_request (the trigger function Supabase's
-- dashboard "Database Webhooks" feature generates) -- but that schema turns out NOT to exist
-- on every project by default; it's lazily provisioned the first time Database Webhooks is
-- opened in the dashboard, which this project had never done, so that version failed with
-- "schema supabase_functions does not exist". Rewritten on pg_net directly (the lower-level
-- extension supabase_functions.http_request itself is built on) -- pg_net installs on every
-- Supabase project without needing any dashboard step first.
create extension if not exists pg_net;

-- SECURITY FIX (see git history on this file): the webhook secret used to be a literal
-- string right here in this migration, which is committed to the repo -- anyone with read
-- access to the git history could read it and trigger a broadcast notification to every
-- registered device. Not scrubbing the old value from history yet (that's a separate,
-- deliberate follow-up), but it must be treated as burned: the value now looked up below is
-- a freshly generated replacement that was never committed anywhere.
--
-- The secret now lives in Supabase Vault (a Postgres extension for encrypted secret storage,
-- https://supabase.com/docs/guides/database/vault) instead of in this file. This migration
-- only creates the plumbing that reads it -- the actual secret value must be set separately,
-- OUTSIDE any committed file, by running this ONCE in the SQL editor (not saved anywhere):
--
--   select vault.create_secret(
--     '<the new secret value>',
--     'notify_new_sighting_webhook_secret',
--     'Shared secret the notify-new-sighting trigger sends and the edge function validates'
--   );
--
-- Note Supabase's own Vault docs warn that INSERT-style statements (which create_secret
-- wraps) get captured in Supabase's query logs by default -- so the plaintext value is safe
-- from git, but not necessarily from project logs. Acceptable for a value that only gates
-- one internal webhook, but worth knowing.
-- CREATE EXTENSION ... WITH SCHEMA requires the target schema to already exist (it does not
-- create it), unlike pg_net above which manages its own schema implicitly.
create schema if not exists vault;
create extension if not exists supabase_vault with schema vault;

-- security definer: sightings inserts come from the app via the anon role, which doesn't
-- necessarily have EXECUTE on net.http_post or SELECT on vault.decrypted_secrets granted
-- directly -- running this function as its owner (the migration role) sidesteps needing to
-- grant anon access to either.
--
-- Auth: the edge function is deployed with --no-verify-jwt (a DB trigger has no user JWT to
-- present) and instead validates the x-webhook-secret header below against a WEBHOOK_SECRET
-- edge function secret (a *separate* store from Vault -- see the edge function's header
-- comment for the `supabase secrets set` step, which must be given the same new value).
create or replace function public.notify_new_sighting()
returns trigger
language plpgsql
security definer
as $$
declare
  v_webhook_secret text;
begin
  select decrypted_secret into v_webhook_secret
  from vault.decrypted_secrets
  where name = 'notify_new_sighting_webhook_secret'
  limit 1;

  if v_webhook_secret is null then
    -- Don't fail the sighting insert just because notification setup isn't finished yet --
    -- log and skip the dispatch instead.
    raise warning 'notify_new_sighting: no secret found in Vault under name %, skipping dispatch',
      'notify_new_sighting_webhook_secret';
    return new;
  end if;

  perform net.http_post(
    url := 'https://vwbcrctzsqukutvlbqwy.supabase.co/functions/v1/notify-new-sighting',
    body := jsonb_build_object('type', 'INSERT', 'table', 'sightings', 'record', to_jsonb(new)),
    headers := jsonb_build_object(
      'Content-type', 'application/json',
      'x-webhook-secret', v_webhook_secret
    ),
    timeout_milliseconds := 5000
  );
  return new;
end;
$$;

drop trigger if exists on_sighting_insert_notify on public.sightings;
create trigger on_sighting_insert_notify
after insert on public.sightings
for each row
execute function public.notify_new_sighting();
