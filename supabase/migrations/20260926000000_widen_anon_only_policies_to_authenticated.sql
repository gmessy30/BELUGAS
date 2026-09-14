-- Item 96 follow-up: a full audit for the SAME class of bug fixed in
-- 20260925000000_allow_authenticated_article_submission.sql -- any RLS policy granted `to anon`
-- only silently breaks for a device carrying a live Supabase Auth session (item 91's admin login,
-- which persists in localStorage across the WHOLE gmessy30.github.io origin, not just /admin/),
-- since that device's requests run as `authenticated` instead. An admin-logged-in device should
-- behave exactly like any other device for every ordinary, non-admin-gated app action.
--
-- AUDIT METHOD: queried the LINKED project directly (pg_policies for schemas public AND storage,
-- plus pg_proc/aclexplode for every public.* function's EXECUTE grants) rather than trusting
-- migration file text -- a policy or grant's CURRENT state can differ from what any single
-- migration file shows, exactly as this repo already learned from the articles table (see that
-- migration's own header comment).
--
-- RESULTS:
--   - Every public.* function already grants EXECUTE to both anon AND authenticated wherever it
--     grants to anon at all (redeem_tier_code, export_sightings, get_kenai_presence_state,
--     get_or_create_subscriber_identity, report_kenai_departure, is_kenai_departure_reporter,
--     etc.) -- Supabase's own default-privileges template applies to new functions regardless of
--     what any individual migration's own `grant ... to anon;` line says. Tier-code redemption
--     specifically named in the request is already fine. No RPC-level gap exists.
--   - public.sightings' insert/read policies are already `to public` (not `to anon`), which
--     already covers authenticated -- no gap.
--   - public.tier_roster / tier_code_redeem_attempts / subscriber_identities / tier_admins have
--     RLS enabled with ZERO table-level policies for anon OR authenticated (by design -- see
--     CLAUDE.md's "Tier codes" section: the only door in is the RPCs above), so there is no
--     anon-only table policy to widen there either.
--   - articles was already fixed by 20260925000000.
--   - The actual gap, found by listing every policy still scoped `to {anon}` only:
--       public.device_tokens: INSERT "Anon can register device tokens",
--         SELECT "Anon can view device tokens for upsert conflict resolution",
--         UPDATE "Anon can refresh its own device token"
--       public.kenai_departure_reports: SELECT "Anon can read kenai departure reports"
--       public.subscriptions: INSERT "Anon can create subscriptions",
--         SELECT "Anon can read subscriptions", UPDATE "Anon can update subscriptions",
--         DELETE "Anon can delete subscriptions"
--       storage.objects (bucket 'sighting-photos'): INSERT "Anon can upload sighting photos",
--         UPDATE "Anon can overwrite own sighting photos"
--
-- None of these need their USING/WITH CHECK expression changed (unlike articles, which needed a
-- genuinely new check for the reviewed_by/reviewed_at columns) -- this is a pure role-widening,
-- so `alter policy ... to anon, authenticated` is used instead of drop+recreate: it changes only
-- the roles list, keeps the same policy name/identity, and is a strictly smaller diff against
-- each policy's actual definition above.
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor
-- (this repo's own established convention, see CLAUDE.md).

alter policy "Anon can register device tokens" on public.device_tokens
  to anon, authenticated;

alter policy "Anon can view device tokens for upsert conflict resolution" on public.device_tokens
  to anon, authenticated;

alter policy "Anon can refresh its own device token" on public.device_tokens
  to anon, authenticated;

alter policy "Anon can read kenai departure reports" on public.kenai_departure_reports
  to anon, authenticated;

alter policy "Anon can create subscriptions" on public.subscriptions
  to anon, authenticated;

alter policy "Anon can read subscriptions" on public.subscriptions
  to anon, authenticated;

alter policy "Anon can update subscriptions" on public.subscriptions
  to anon, authenticated;

alter policy "Anon can delete subscriptions" on public.subscriptions
  to anon, authenticated;

alter policy "Anon can upload sighting photos" on storage.objects
  to anon, authenticated;

alter policy "Anon can overwrite own sighting photos" on storage.objects
  to anon, authenticated;
