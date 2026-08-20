-- Notification zone/subscription system: `zones` (curated regional polygon presets),
-- `point_presets` (curated named point+radius presets), `subscriptions` (a subscriber's
-- zone / custom polygon / point+radius watches), and a `sightings.is_geofence_verified`
-- flag the `verified_only` confidence filter reads.
--
-- SCHEMA ONLY in this migration: no zone/preset boundary data is seeded here. Coordinates
-- for the ~9 regional zones and the AKBMP point presets need real GIS boundaries (from
-- AKBMP or another source) rather than guessed ones for a wildlife-notification feature —
-- that's a separate, reviewed data migration once we have them.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.

-- Supabase projects have PostGIS available; this just turns it on if it isn't already.
-- geometry(Polygon, 4326) is used for zone/custom-polygon containment checks (matches the
-- precision level GeofenceUtils.kt already uses -- plain lat/lng, no distinct projection).
-- geography(Point, 4326) is used for the point+radius case specifically, so ST_DWithin can
-- do real great-circle meters instead of treating degrees as a flat plane.
create extension if not exists postgis;


-- =========================================================================================
-- ZONES: curated regional polygon presets (subscription type 3). Deliberately a plain
-- table, not hardcoded in the app, so new/adjusted zones don't require a client release.
-- =========================================================================================
create table if not exists public.zones (
  id uuid primary key default gen_random_uuid(),

  -- Stable machine key (e.g. 'kenai_kasilof', 'turnagain_arm_upper', 'entire_inlet') for the
  -- app to reference without depending on display-name text.
  slug text not null unique,
  name text not null,

  -- Matches RegionConfig.id in Kotlin ('cook_inlet' | 'st_lawrence'). Not a foreign key --
  -- RegionConfig is app-side config, not itself backed by a table.
  region_id text not null,

  boundary geometry(Polygon, 4326) not null,

  -- Display ordering hint for a future zone picker list.
  display_order integer not null default 0,

  created_at timestamptz not null default now()
);

create index if not exists zones_boundary_gix on public.zones using gist (boundary);
create index if not exists zones_region_id_idx on public.zones (region_id);

alter table public.zones enable row level security;

-- Zones are curated reference data (seeded via migration/dashboard, not user-created), so
-- only a public read policy is needed -- no insert/update/delete from the app.
drop policy if exists "Public read access to zones" on public.zones;
create policy "Public read access to zones"
on public.zones for select
to public
using (true);


-- =========================================================================================
-- POINT_PRESETS: curated named point+radius presets (the AKBMP monitoring sites + Anchorage,
-- for subscription type 1). Symmetric with `zones` for the same reason -- these should be
-- addable/adjustable without an app release -- just a point instead of a polygon, and no
-- boundary geometry to intersect against (a `point_radius` subscription created from a
-- preset copies its lat/lng/radius in at creation time; there's no ongoing FK from
-- subscriptions to this table, same as a user-dropped custom point).
-- =========================================================================================
create table if not exists public.point_presets (
  id uuid primary key default gen_random_uuid(),

  slug text not null unique,
  name text not null,

  -- Matches RegionConfig.id in Kotlin, same convention as zones.region_id.
  region_id text not null,

  point geography(Point, 4326) not null,

  -- Suggested default radius for a subscription created from this preset; the UI can still
  -- let the user override it before saving.
  default_radius_meters double precision not null check (default_radius_meters > 0),

  display_order integer not null default 0,

  created_at timestamptz not null default now()
);

create index if not exists point_presets_point_gix on public.point_presets using gist (point);
create index if not exists point_presets_region_id_idx on public.point_presets (region_id);

alter table public.point_presets enable row level security;

-- Same posture as zones: curated reference data, public read only.
drop policy if exists "Public read access to point presets" on public.point_presets;
create policy "Public read access to point presets"
on public.point_presets for select
to public
using (true);


-- =========================================================================================
-- SUBSCRIPTIONS: one table for all three subscription types (zone / custom polygon /
-- point+radius), discriminated by `kind`, so dispatch can match a sighting against a single
-- table instead of unioning three. The CHECK constraint enforces that only the columns
-- belonging to a row's `kind` are populated.
-- =========================================================================================
-- Postgres has no CREATE TYPE IF NOT EXISTS; these DO blocks make the enum creation
-- idempotent the same way the DROP POLICY IF EXISTS pattern does for policies elsewhere in
-- this repo's migrations.
do $$ begin
  create type public.subscription_kind as enum ('zone', 'custom_polygon', 'point_radius');
exception when duplicate_object then null;
end $$;

-- 'all' = any sighting (including secondhand / outside-geofence reports).
-- 'verified_only' = observer_type = 'SELF' AND sightings.is_geofence_verified -- see below.
do $$ begin
  create type public.subscription_confidence_filter as enum ('all', 'verified_only');
exception when duplicate_object then null;
end $$;

create table if not exists public.subscriptions (
  id uuid primary key default gen_random_uuid(),

  -- No real auth yet (anon key only) -- see the note below the table for how this is meant
  -- to migrate once accounts exist.
  subscriber_id uuid not null,

  kind subscription_kind not null,
  confidence_filter subscription_confidence_filter not null default 'all',
  is_active boolean not null default true,

  -- Optional user-facing display name (e.g. "Anchorage today"). Falls back to the zone's
  -- name / a generic "Custom area" label in the UI when null.
  label text,

  -- kind = 'zone'
  zone_id uuid references public.zones(id) on delete cascade,

  -- kind = 'custom_polygon' (finger-painted on the map)
  custom_polygon geometry(Polygon, 4326),

  -- kind = 'point_radius' (AKBMP-preset point, or a user-dropped temporary/mobile point).
  -- expires_at is set for the temporary/mobile variant ("I'm in Anchorage today") and left
  -- null for a permanent point subscription; dispatch treats a subscription as inactive once
  -- expires_at has passed rather than requiring a background job to flip is_active.
  point geography(Point, 4326),
  radius_meters double precision,
  expires_at timestamptz,

  created_at timestamptz not null default now(),
  -- Not trigger-maintained; the app sets this explicitly on updates (e.g. toggling
  -- confidence_filter or is_active).
  updated_at timestamptz not null default now(),

  constraint subscriptions_radius_positive_check
    check (radius_meters is null or radius_meters > 0),

  -- Exactly the columns for a row's own kind may be populated -- keeps a 'zone' row from
  -- ever also carrying a stray custom_polygon or point, and vice versa.
  constraint subscriptions_kind_payload_check check (
    (kind = 'zone'
      and zone_id is not null and custom_polygon is null and point is null and radius_meters is null)
    or (kind = 'custom_polygon'
      and custom_polygon is not null and zone_id is null and point is null and radius_meters is null)
    or (kind = 'point_radius'
      and point is not null and radius_meters is not null and zone_id is null and custom_polygon is null)
  )
);

create index if not exists subscriptions_subscriber_id_idx on public.subscriptions (subscriber_id);
create index if not exists subscriptions_zone_id_idx on public.subscriptions (zone_id) where zone_id is not null;
create index if not exists subscriptions_custom_polygon_gix on public.subscriptions using gist (custom_polygon);
create index if not exists subscriptions_point_gix on public.subscriptions using gist (point);

alter table public.subscriptions enable row level security;

-- No auth means there's no server-side way yet to restrict a row to "its own" device --
-- this mirrors the existing security posture for `sightings` and `storage.objects` (anon can
-- read/write broadly; isolation is left to the client only querying its own subscriber_id).
-- Tighten to `using (auth.uid() = user_id)` once real accounts land (see note below).
drop policy if exists "Anon can read subscriptions" on public.subscriptions;
create policy "Anon can read subscriptions"
on public.subscriptions for select
to anon
using (true);

drop policy if exists "Anon can create subscriptions" on public.subscriptions;
create policy "Anon can create subscriptions"
on public.subscriptions for insert
to anon
with check (true);

drop policy if exists "Anon can update subscriptions" on public.subscriptions;
create policy "Anon can update subscriptions"
on public.subscriptions for update
to anon
using (true)
with check (true);

drop policy if exists "Anon can delete subscriptions" on public.subscriptions;
create policy "Anon can delete subscriptions"
on public.subscriptions for delete
to anon
using (true);

-- NOTE on subscriber_id / auth migration path:
-- For now, subscriber_id is a random UUID the app generates once on first launch and
-- persists in local device storage (no relation to any Supabase auth user -- there isn't
-- one). It is NOT a foreign key to anything.
--
-- When real accounts are added later:
--   1. Add a nullable `user_id uuid references auth.users(id)` column to this table.
--   2. On first sign-in, run `update subscriptions set user_id = auth.uid() where
--      subscriber_id = <the device's locally-stored UUID>` to claim that device's existing
--      subscriptions into the new account ("claim your subscriptions" flow).
--   3. Tighten the RLS policies above to `using (auth.uid() = user_id)` and eventually drop
--      reliance on subscriber_id once every row has a user_id (subscriber_id can stay as a
--      historical/debugging column, or be dropped in a later migration).


-- =========================================================================================
-- SIGHTINGS: add the signal `verified_only` subscribers filter on.
-- =========================================================================================
-- Computed client-side at submission time via the same GeofenceUtils.isWithin3DFunnel check
-- already used for the "outside geofence, save anyway?" warning dialog, so there's one
-- source of truth for this logic instead of a second copy re-implemented in SQL/PostGIS that
-- could drift from it. This follows the same trust model already used for every other
-- client-computed field on this table (heading_source, distance_bucket, etc.) -- there's no
-- server-side validation anywhere in this app yet, anon key only.
--
-- KNOWN GAP: because it's entirely client-trusted, a buggy or malicious client could
-- misreport this flag, which would matter more here than for the other client-computed
-- fields since it directly drives who gets notified as "verified." Accepted for now:
-- revisit with real server-side validation once real auth exists (see the subscriber_id
-- migration note above) rather than building anything for it today.
--
-- 'verified_only' subscribers should match on: observer_type = 'SELF' AND
-- is_geofence_verified = true. That combined check lives in the dispatch query, not as a
-- separate stored "confidence level" column here, so the two inputs it depends on don't get
-- duplicated into a third derived column that could disagree with them.
alter table public.sightings
  add column if not exists is_geofence_verified boolean not null default false;
