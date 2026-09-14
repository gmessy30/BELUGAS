-- Item 91: article moderation on the admin page (webapp/admin/) -- until now, approving a
-- submitted article/paper meant hand-editing its status in the Supabase dashboard (see
-- 20260823120000_add_articles.sql's own header comment: "No moderation UI yet"). This adds a
-- real in-app queue, gated by the SAME tier_admins allow-list the tier-code RPCs already use
-- (20260921000000_add_tier_admin_rpcs.sql's is_tier_admin()) -- being a signed-in admin for tier
-- codes and for article moderation is the same account/allow-list, not a separate one.
--
-- STATEMENT ORDER (see 20260923000000's own header comment on why this matters): every schema
-- change (the new enum value, the two new columns) runs BEFORE any function that references
-- them. `alter type ... add value` specifically can't be used in the SAME transaction that added
-- it -- since this project applies migrations via `supabase db query --linked --file <path>`,
-- which auto-commits each top-level statement individually (not wrapped in one big transaction;
-- confirmed by how the reordering fix for 20260923000000 actually worked), putting it first as
-- its own statement is enough: by the time set_article_status is first CALLED (a later,
-- definitely-separate transaction), the value is already durably committed. plpgsql function
-- BODIES (unlike a `language sql` function matching its own RETURNS TABLE shape) are opaque text
-- at CREATE TIME regardless -- only actually calling one that references a column/enum value
-- that doesn't exist yet would fail, not defining it.
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor
-- (this repo's own established convention, see CLAUDE.md).

-- =========================================================================================
-- SCHEMA CHANGES FIRST.
-- =========================================================================================

-- New target status for set_article_status below -- a submission an admin looks at and decides
-- NOT to publish (spam, broken link, duplicate, etc.), distinct from just leaving it sitting in
-- pending_review forever. IF NOT EXISTS makes this safe to re-run.
alter type public.article_status add value if not exists 'rejected';

-- reviewed_by/reviewed_at: who acted on this row and when, stamped by set_article_status itself
-- (never settable directly by the client) -- same "the RPC is the only write path, and it also
-- controls its own audit columns" posture as tier_roster's claimed_at. ON DELETE SET NULL (not
-- CASCADE): removing an admin's own auth.users row should never delete the articles they
-- reviewed, just lose that attribution.
alter table public.articles
  add column if not exists reviewed_by uuid references auth.users (id) on delete set null,
  add column if not exists reviewed_at timestamptz;

-- Defensive, explicit, matching this project's own established style for every admin-moderated
-- table (see tier_admins/tier_roster's own comments) -- RLS already has no UPDATE policy at all
-- for anon/authenticated on this table (only the INSERT/SELECT policies 20260823120000 added), so
-- this is belt-and-suspenders against Supabase's own project-level default grants, not the only
-- thing stopping a direct UPDATE.
revoke update on public.articles from anon, authenticated;


-- =========================================================================================
-- list_pending_articles: the moderation queue itself -- newest first, matching an admin's usual
-- "what came in since I last checked" workflow (same ordering convention as list_tier_codes).
-- =========================================================================================
create or replace function public.list_pending_articles()
returns table (
  id uuid,
  title text,
  summary text,
  source_url text,
  submitted_by text,
  content_type public.article_content_type,
  created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  return query
    select a.id, a.title, a.summary, a.source_url, a.submitted_by, a.content_type, a.created_at
    from public.articles a
    where a.status = 'pending_review'
    order by a.created_at desc;
end;
$$;

revoke all on function public.list_pending_articles() from public;
grant execute on function public.list_pending_articles() to authenticated;


-- =========================================================================================
-- set_article_status: the SOLE write path onto articles.status/title/summary/reviewed_*.
-- p_status is 'published' or 'rejected' from the pending-queue's own PUBLISH/REJECT buttons, or
-- 'pending_review' from the RECENTLY PUBLISHED list's UNPUBLISH action (puts it back in the
-- queue rather than needing a second dedicated RPC for what's really the same status-transition
-- operation). p_title/p_summary are OPTIONAL edits -- passing null leaves the existing value
-- alone (an admin fixing a typo before publishing shouldn't have to retype the whole thing, but
-- also shouldn't be forced to touch it just to change status).
-- =========================================================================================
create or replace function public.set_article_status(
  p_id uuid,
  p_status text,
  p_title text default null,
  p_summary text default null
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row_count int;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;

  if p_status not in ('published', 'rejected', 'pending_review') then
    raise exception 'invalid_status';
  end if;

  update public.articles
     set status = p_status::public.article_status,
         title = coalesce(p_title, title),
         summary = coalesce(p_summary, summary),
         reviewed_by = auth.uid(),
         reviewed_at = now()
   where id = p_id;

  get diagnostics v_row_count = row_count;
  return v_row_count > 0;
end;
$$;

revoke all on function public.set_article_status(uuid, text, text, text) from public;
grant execute on function public.set_article_status(uuid, text, text, text) to authenticated;
