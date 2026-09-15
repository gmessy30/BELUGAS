-- Item 101 BUG FIX: 20260929000000's own list_tier_codes/list_tier_admins/list_admin_actions all
-- join auth.users and select its `email` column directly into a RETURNS TABLE column declared
-- `text` -- but auth.users.email is actually `character varying(255)`, and PL/pgSQL's
-- RETURN QUERY requires an EXACT type match (unlike a plain `select`, which would implicitly
-- coerce this fine). All three fail outright with "structure of query does not match function
-- result type ... character varying(255) does not match expected type text" the moment they're
-- actually called as an authenticated user -- confirmed live against the linked project (`set
-- local role authenticated; set local request.jwt.claims = '{"sub":"<owner uuid>", ...}'`) for
-- all three functions individually. This is a live regression: list_tier_codes() is the SAME
-- function the admin page's pre-existing ISSUED CODES list has always depended on, so this broke
-- that too, not just the three new item 101 sections.
--
-- Fix: cast the auth.users.email reference to ::text at the point each function selects it,
-- rather than widening every RETURNS TABLE declaration -- text is what every other text column
-- here already is, so a one-word cast at the source is the smaller, more obviously-correct fix.
-- No schema changes, no new columns -- CREATE OR REPLACE with identical signatures throughout.
--
-- Run this via `supabase db query --linked --file <path>` -- never the Supabase web SQL editor.
-- Per CLAUDE.md's own "Hard rule": all three functions read from tier_roster/tier_admins --
-- flagging for the user's go-ahead per that rule, even though this is a pure type-cast fix with
-- no schema/behavior change beyond "the query no longer errors."

create or replace function public.list_tier_codes()
returns table (
  id uuid,
  name text,
  contact text,
  device text,
  tier smallint,
  code text,
  is_used boolean,
  claimed_at timestamptz,
  created_at timestamptz,
  zone_slug text,
  issued_by uuid,
  issued_by_email text,
  is_owner_device boolean
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

  if public.is_tier_owner() then
    return query
      select r.id, r.name, r.contact, r.device, r.tier, r.code, r.is_used, r.claimed_at, r.created_at,
             r.zone_slug, r.issued_by, u.email::text, r.is_owner_device
      from public.tier_roster r
      left join auth.users u on u.id = r.issued_by
      order by r.created_at desc;
  else
    return query
      select r.id, r.name, r.contact, r.device, r.tier, r.code, r.is_used, r.claimed_at, r.created_at,
             r.zone_slug, r.issued_by, u.email::text, r.is_owner_device
      from public.tier_roster r
      left join auth.users u on u.id = r.issued_by
      where r.zone_slug = public.caller_admin_zone_slug()
      order by r.created_at desc;
  end if;
end;
$$;

create or replace function public.list_tier_admins()
returns table (user_id uuid, email text, owner boolean, zone_slug text)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  return query
    select ta.user_id, u.email::text, ta.owner, ta.zone_slug
    from public.tier_admins ta
    join auth.users u on u.id = ta.user_id
    order by ta.owner desc, u.email;
end;
$$;

create or replace function public.list_admin_actions(p_limit int default 200)
returns table (
  id uuid,
  admin_user_id uuid,
  admin_email text,
  action text,
  target_table text,
  target_id uuid,
  detail jsonb,
  created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.is_tier_owner() then
    raise exception 'not_authorized';
  end if;

  return query
    select aa.id, aa.admin_user_id, u.email::text, aa.action, aa.target_table, aa.target_id, aa.detail, aa.created_at
    from public.admin_actions aa
    left join auth.users u on u.id = aa.admin_user_id
    order by aa.created_at desc
    limit least(p_limit, 1000);
end;
$$;
