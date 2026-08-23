-- Backs the News Feed screen: general news and research papers, kept in separate sections by
-- content_type so research links never show up mixed into general news. No moderation UI yet
-- -- submissions default to pending_review and are approved by hand in the Supabase dashboard
-- (status -> 'published').
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

do $$ begin
  create type public.article_content_type as enum ('news', 'research_paper');
exception when duplicate_object then null;
end $$;

do $$ begin
  create type public.article_status as enum ('pending_review', 'published');
exception when duplicate_object then null;
end $$;

create table if not exists public.articles (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  summary text,
  source_url text not null,
  submitted_by text,
  content_type public.article_content_type not null,
  status public.article_status not null default 'pending_review',
  created_at timestamptz not null default now()
);

create index if not exists articles_content_type_status_idx on public.articles (content_type, status);

alter table public.articles enable row level security;

-- Published articles are public content -- anyone browsing the News Feed can read them, no
-- auth needed. Pending-review rows are NOT covered by this policy, so unapproved submissions
-- stay invisible (including to whoever submitted them) until manually approved.
drop policy if exists "Public read access to published articles" on public.articles;
create policy "Public read access to published articles"
on public.articles for select
to public
using (status = 'published');

-- Anon can submit a suggestion, but only ever landing as pending_review -- the check
-- constraint on status here is what stops a crafted request from self-publishing straight
-- past moderation, not just the DB default.
drop policy if exists "Anon can submit pending articles" on public.articles;
create policy "Anon can submit pending articles"
on public.articles for insert
to anon
with check (status = 'pending_review');
