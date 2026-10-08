-- Item 93c (PROPOSAL, not applied): News Feed dead-link handling, reader-reported design.
--
-- Readers can say "this link is broken". Reports are stored per device and only ever FLAG an
-- article for the admin; they never hide it. An admin confirms (marks the link broken, optionally
-- with an archived copy), fixes the URL, or clears the reports. Only an admin-confirmed "broken"
-- changes what readers see.
--
-- Why reports never auto-hide: the only device identity this app has is belugas_subscriber_id, a
-- random uuid the client makes for itself. Anyone can mint as many as they like, so "N distinct
-- devices" is not a trust boundary -- an automatic hide at N reports would let one person hide any
-- article. N (REPORT_ATTENTION_THRESHOLD in the admin page) only orders and highlights the queue.
--
-- Also closes a gap found while reading the current code: nothing validates source_url (the
-- suggest form only checks it's non-empty; the table has no check), so a javascript: or other
-- non-web URL could reach the review queue and, if published by mistake, become a card's href.
-- All 33 existing rows are https, so the new CHECK validates cleanly.
--
-- Not touched: sightings, get_kenai_presence_state, tier_roster, net.http_request_queue (nothing
-- here makes an HTTP request).

-- 1. Columns on articles.
alter table public.articles
  add column link_status text not null default 'ok'
    constraint articles_link_status_check check (link_status in ('ok', 'broken')),
  add column link_status_at timestamptz,
  add column archive_url text
    constraint articles_archive_url_https check (archive_url is null or archive_url ~ '^https://');

-- web URLs only, for new submissions and edits alike. NOT VALID: one existing row, a REJECTED test
-- article from Sept 14 ("edge case not-a-url", source_url 'not-a-url'), fails it, and this
-- migration doesn't change real articles. NOT VALID skips existing rows when the constraint is
-- added but still checks every insert and every update -- so that row can't be re-published
-- without a fixed URL. The other 32 rows (all 23 published) pass.
alter table public.articles
  add constraint articles_source_url_web check (source_url ~ '^https?://[^\s/]+') not valid;

-- 2. Reports: one row per (article, device). RLS on with NO policies, so anon/authenticated can't
-- read, write or count it directly; the RPCs below are the only door.
create table public.article_link_reports (
  id uuid primary key default gen_random_uuid(),
  article_id uuid not null references public.articles(id) on delete cascade,
  subscriber_id uuid not null,
  created_at timestamptz not null default now(),
  constraint article_link_reports_once_per_device unique (article_id, subscriber_id)
);
create index article_link_reports_subscriber_recent on public.article_link_reports (subscriber_id, created_at);
alter table public.article_link_reports enable row level security;
revoke all on public.article_link_reports from anon, authenticated;

-- 3. Reader RPC. Status: recorded | already_reported | not_found | rate_limited.
-- Only published articles can be reported. At most 10 reports per device per hour, so a single
-- device can't flood the admin queue (a determined person can still mint devices -- which is why
-- reports only flag, never hide). At most 50 stored reports per article: past that, "recorded" is
-- returned without storing another row, so minted devices can't grow the table without bound and
-- a reader is never told anything different. The device id is NOT validated against anything:
-- it's the client's own random belugas_subscriber_id, and no table lists real devices.
create or replace function public.report_broken_article_link(p_article_id uuid, p_subscriber_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_recent int;
begin
  if p_article_id is null or p_subscriber_id is null then
    return jsonb_build_object('status', 'not_found');
  end if;

  -- Locks the article row (no change to it) so concurrent reports take turns and the 50 cap
  -- below is exact rather than "about 50".
  perform 1 from public.articles where id = p_article_id and status = 'published' for update;
  if not found then
    return jsonb_build_object('status', 'not_found');
  end if;

  select count(*) into v_recent from public.article_link_reports
   where subscriber_id = p_subscriber_id and created_at > now() - interval '1 hour';
  if v_recent >= 10 then
    return jsonb_build_object('status', 'rate_limited');
  end if;

  if exists (select 1 from public.article_link_reports
             where article_id = p_article_id and subscriber_id = p_subscriber_id) then
    return jsonb_build_object('status', 'already_reported');
  end if;

  if (select count(*) from public.article_link_reports where article_id = p_article_id) >= 50 then
    return jsonb_build_object('status', 'recorded');
  end if;

  insert into public.article_link_reports (article_id, subscriber_id)
  values (p_article_id, p_subscriber_id);
  return jsonb_build_object('status', 'recorded');
end;
$$;

-- 4. Admin queue: every article with open reports or a confirmed-broken link.
create or replace function public.list_reported_article_links()
returns table (id uuid, title text, source_url text, status public.article_status, link_status text,
               link_status_at timestamptz, archive_url text, report_count bigint,
               first_reported_at timestamptz, last_reported_at timestamptz)
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
    select a.id, a.title, a.source_url, a.status, a.link_status, a.link_status_at, a.archive_url,
           count(r.id), min(r.created_at), max(r.created_at)
    from public.articles a
    left join public.article_link_reports r on r.article_id = a.id
    group by a.id
    having count(r.id) > 0 or a.link_status = 'broken'
    order by count(r.id) desc, max(r.created_at) desc nulls last;
end;
$$;

-- 5. Admin action. p_action:
--   'mark_broken' -- link_status = 'broken' (readers see the notice); p_archive_url optional
--   'mark_ok'     -- link_status = 'ok', archive_url cleared, reports cleared ("looks fine")
--   'fix_url'     -- source_url := p_source_url (must be http/https), link_status = 'ok',
--                    archive_url cleared, reports cleared
-- Every action is written to admin_actions, like set_article_status.
create or replace function public.set_article_link_status(p_article_id uuid, p_action text,
                                                         p_source_url text default null,
                                                         p_archive_url text default null)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_rows int;
begin
  if not public.is_tier_admin() then
    raise exception 'not_authorized';
  end if;
  if p_action not in ('mark_broken', 'mark_ok', 'fix_url') then
    raise exception 'invalid_action';
  end if;
  if p_action = 'fix_url' and (p_source_url is null or p_source_url !~ '^https?://[^\s/]+') then
    raise exception 'invalid_url';
  end if;
  -- archive_url's own CHECK refuses anything that isn't https.

  update public.articles
     set link_status = case when p_action = 'mark_broken' then 'broken' else 'ok' end,
         link_status_at = now(),
         archive_url = case when p_action = 'mark_broken' then p_archive_url else null end,
         source_url = case when p_action = 'fix_url' then p_source_url else source_url end
   where id = p_article_id;
  get diagnostics v_rows = row_count;

  if v_rows > 0 and p_action in ('mark_ok', 'fix_url') then
    delete from public.article_link_reports where article_id = p_article_id;
  end if;

  perform public.log_admin_action('article_link_' || p_action, 'articles', p_article_id,
    jsonb_build_object('source_url', p_source_url, 'archive_url', p_archive_url));
  return v_rows > 0;
end;
$$;

-- 6. Grants. Postgres gives PUBLIC EXECUTE on new functions by default (see 20261008000000), and
-- this project's default privileges also grant anon, so revoke both and grant explicitly. The
-- admin RPCs are callable by authenticated only (they check is_tier_admin() themselves, like
-- set_article_status -- which, unlike these, still grants anon); the report RPC by anon and
-- authenticated.
revoke execute on function public.report_broken_article_link(uuid, uuid) from public;
revoke execute on function public.list_reported_article_links() from public, anon;
revoke execute on function public.set_article_link_status(uuid, text, text, text) from public, anon;
grant execute on function public.report_broken_article_link(uuid, uuid) to anon, authenticated;
grant execute on function public.list_reported_article_links() to authenticated;
grant execute on function public.set_article_link_status(uuid, text, text, text) to authenticated;

-- 7. RLS on articles. The SELECT policy ("Public read access to published articles") is unchanged
-- and simply exposes link_status / archive_url on published rows (nothing sensitive). The INSERT
-- policy MUST be tightened: as it stands it constrains only status/reviewed_by/reviewed_at, so a
-- submitter could insert a pending row with archive_url pointing anywhere (or link_status
-- 'broken'), and if an admin published it, readers would get that link as "View archived copy".
-- Only set_article_link_status may set these three columns.
alter policy "Anon or authenticated can submit pending articles" on public.articles
  with check (
    status = 'pending_review'::public.article_status
    and reviewed_by is null and reviewed_at is null
    and link_status = 'ok' and link_status_at is null and archive_url is null
  );
