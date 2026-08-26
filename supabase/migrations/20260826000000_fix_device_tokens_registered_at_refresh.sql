-- BUG FIX: registerDeviceToken() (SupabaseClient.kt) upserts device_tokens on every app
-- launch and on token refresh, onConflict = "fcm_token" -- but the client payload only ever
-- contains fcm_token, never registered_at. PostgREST's default upsert resolution
-- (merge-duplicates) turns a conflict into an UPDATE that sets only the columns present in
-- the payload, so registered_at (whose `default now()` only applies on the original INSERT)
-- was never touched again. Confirmed on-device: DEVICE_TOKEN_REGISTER_SUCCESS logs on every
-- launch, but the row's registered_at stayed pinned to its very first registration -- making
-- it useless as a "device last seen" signal.
--
-- Fixed at the DB layer with a trigger rather than by adding a timestamp field to the Kotlin
-- payload -- that keeps "registered_at reflects the most recent upsert" true for this call
-- site and any future one, without every caller needing to remember to send it.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- see the header note on
-- 20260823000000_add_device_tokens_and_notify_trigger.sql for why this repo can't apply it
-- automatically.

create or replace function public.touch_device_token_registered_at()
returns trigger
language plpgsql
as $$
begin
  new.registered_at := now();
  return new;
end;
$$;

drop trigger if exists touch_device_token_registered_at on public.device_tokens;
create trigger touch_device_token_registered_at
before update on public.device_tokens
for each row
execute function public.touch_device_token_registered_at();
