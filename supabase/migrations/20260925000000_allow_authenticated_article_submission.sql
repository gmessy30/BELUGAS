-- Item 96 BUG FIX: article submission ("+ SUGGEST AN ARTICLE OR PAPER") was failing with
-- "new row violates row-level security policy for table \"articles\"" on any device that had
-- ever logged into the admin page (webapp/admin/, item 91's Supabase Auth login). Root cause
-- confirmed on a real device (Stylus, ZY22JSTXPW) by capturing the exact outgoing payload via a
-- fetch() intercept around a live submitArticle() call:
--
--   {"title":"...","summary":"...","source_url":"...","submitted_by":null,"content_type":"news"}
--
-- -- fully compliant with the original WITH CHECK (status omitted, so it takes its column
-- default of 'pending_review'; no reviewed_by/reviewed_at sent at all). The payload was never
-- the problem. The device's localStorage held a live Supabase Auth session
-- (sb-vwbcrctzsqukutvlbqwy-auth-token, role "authenticated", from a prior admin login) -- and
-- since 20260823120000_add_articles.sql's "Anon can submit pending articles" policy was only
-- ever granted `to anon`, a request that carries ANY Supabase Auth JWT runs as the `authenticated`
-- role instead, which has NO insert policy on this table at all (confirmed directly: `set local
-- role authenticated; insert into public.articles (...)` inside a rolled-back transaction against
-- the linked project reproduced the identical 42501 error with no client involved). This isn't a
-- policy that got dropped or narrowed by 20260924000000 -- it's a gap that was harmless when
-- article submission and admin auth were unrelated concerns, but became live the moment both
-- share one origin (gmessy30.github.io) and localStorage persists a login across the whole site,
-- not just /admin/. A signed-in admin suggesting an article from the main app is a completely
-- normal thing to do and must work the same as anon.
--
-- Fix: replace the anon-only policy with one covering BOTH anon and authenticated, and -- since
-- 20260924000000 added reviewed_by/reviewed_at after the original policy was written, and those
-- columns were never covered by any WITH CHECK -- explicitly require both to be unset on insert,
-- restoring "anon (or a logged-in admin) may INSERT only rows with status = 'pending_review' and
-- no reviewed_by/reviewed_at set" as the real, complete invariant. set_article_status
-- (20260924000000) remains the ONLY path that ever sets those two columns or moves status off
-- pending_review -- this does not touch UPDATE, which stays revoked for anon/authenticated.
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor
-- (this repo's own established convention, see CLAUDE.md).

drop policy if exists "Anon can submit pending articles" on public.articles;

create policy "Anon or authenticated can submit pending articles"
on public.articles for insert
to anon, authenticated
with check (
  status = 'pending_review'
  and reviewed_by is null
  and reviewed_at is null
);
