-- Narrows the map's "belugas present" shading to the actual banner watch area for Kenai
-- (the river-proximity buffer plus the mouth semicircle, per get_kenai_presence_state /
-- is_whale_position_in_kenai_banner_area), while zones.boundary itself -- and everything that
-- reads it directly (export_sightings, match_notification_recipients, find_nearby_watched_zone,
-- and get_kenai_presence_state's own containment check) -- stays completely unchanged. This is
-- a new, additive, read-only shading endpoint layered on top; nothing about the administrative
-- zone changes.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- whale_position_verify_buffer_cap_meters: the single source for the 1000m cap already used
-- in two places -- is_whale_position_in_kenai_banner_area's river-proximity check below, and
-- (client-side) GeofenceUtils.WHALE_POSITION_VERIFY_BUFFER_CAP_METERS. Extracted to a no-arg
-- function, same reasoning and pattern as kenai_river_spike_line/kenai_mouth_semicircle below --
-- the shading function's river-buffer radius must literally be this same value, not a second
-- literal that happens to match it today. immutable (not stable) -- a true constant, no table
-- access, safe for the planner to inline/fold freely.
-- =========================================================================================
create or replace function public.whale_position_verify_buffer_cap_meters()
returns double precision
language sql
immutable
as $$ select 1000.0 $$;


-- =========================================================================================
-- kenai_river_spike_line / kenai_mouth_semicircle: the two geometry pieces
-- is_whale_position_in_kenai_banner_area already computed inline, lifted out to their own
-- no-arg functions so the new shading function below can reference the exact same source
-- geometry rather than a second copy that could drift from it. Pure extraction -- same CTE,
-- same WKT literal, moved, not altered. See is_whale_position_in_kenai_banner_area's own
-- (now-referencing-these) definition for the original derivation/seaward-side-confirmation
-- comments -- not repeated here to avoid the exact duplication this migration exists to avoid
-- elsewhere.
-- =========================================================================================
create or replace function public.kenai_river_spike_line()
returns geometry
language sql
stable
as $$
  select ST_MakeLine(v.geom order by v.idx)
  from public.zones z, lateral (
    select (dp).path[1] as idx, (dp).geom as geom
    from ST_DumpPoints(ST_ExteriorRing(z.boundary)) as dp
  ) v
  where z.slug = 'kenai'
    and v.idx between 12 and 190
$$;

create or replace function public.kenai_mouth_semicircle()
returns geometry
language sql
immutable
as $$
  select ST_GeomFromText('POLYGON((
    -151.26909626146644 60.52745591737828, -151.27168576519333 60.527110216457764,
    -151.27431785306797 60.52685433457824, -151.27697972025908 60.52668951650606,
    -151.2796584178234 60.52661656400195, -151.28234091537502 60.52663583194089,
    -151.28501416414028 60.52674722659485, -151.28766516009958 60.52695020608641,
    -151.290281006918 60.527243783010945, -151.29284897836484 60.52762652921524,
    -151.29535657992747 60.528096582709594, -151.2977916093266 60.52865165668095,
    -151.300142215645 60.52928905056403, -151.30239695678833 60.530005663118025,
    -151.30454485500363 60.530798007446826, -151.30657545018917 60.53166222789119,
    -151.3084788507401 60.53259411871265, -151.31024578168413 60.533589144479976,
    -151.31186762987394 60.5346424620611, -151.31333648601685 60.53574894411521,
    -151.31464518333476 60.53690320397306, -151.31578733266466 60.53809962178575,
    -151.316757353825 60.539332371816954, -151.31755050309022 60.540595450747276,
    -151.31816289663428 60.54188270685422, -151.3185915298232 60.54318786992738,
    -151.31883429225448 60.54450458177416, -151.31888997846303 60.545826427167995,
    -151.31875829423322 60.54714696508958, -151.3184398584775 60.54845976010866,
    -151.317936200664 60.54975841375405, -151.31724975379703 60.55103659571816,
    -151.31638384297605 60.552288074744524, -151.31534266958062 60.55350674904586,
    -151.31413129115157 60.55468667610425, -151.31275559705858 60.555822101706624,
    -151.31122228006723 60.556907488072504, -151.3095388039383 60.55793754093533,
    -151.30771336721392 60.55890723544344, -151.30575486336292 60.5598118407526,
    -151.30367283747847 60.560646943188225, -151.3014774397378 60.561408467861845,
    -151.29917937585162 60.56209269863517, -151.29678985474635 60.562696296331374,
    -151.29432053373677 60.56321631510384, -151.29178346146065 60.56365021687981,
    -151.26909626146644 60.52745591737828
  ))', 4326)
$$;


-- =========================================================================================
-- is_whale_position_in_kenai_banner_area: same signature and same behavior as before -- now
-- calling the three extracted helpers above instead of inlining them. Not a behavior change.
-- =========================================================================================
create or replace function public.is_whale_position_in_kenai_banner_area(
  p_lng double precision,
  p_lat double precision,
  p_uncertainty_radius_meters double precision default null
)
returns boolean
language sql
stable
as $$
  with pt as (
    select ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326) as geom
  )
  select
    (
      p_uncertainty_radius_meters is not null
      and public.kenai_river_spike_line() is not null
      and ST_Distance(
            public.kenai_river_spike_line()::geography,
            (select geom from pt)::geography
          ) <= least(p_uncertainty_radius_meters, public.whale_position_verify_buffer_cap_meters())
    )
    or ST_Contains(public.kenai_mouth_semicircle(), (select geom from pt))
$$;


-- =========================================================================================
-- get_watched_zone_shading_areas: what the map actually shades. Kenai gets the real banner
-- watch area (the river buffered by the same whale_position_verify_buffer_cap_meters() cap
-- the point-check itself uses, unioned with the mouth semicircle) -- the shaded strip is the
-- outer envelope of everything that could ever count, since a real sighting's own buffer is
-- always <= this cap: being OUTSIDE the strip guarantees a sighting there wouldn't count;
-- being inside it does NOT guarantee one there would (that still depends on that specific
-- sighting's own uncertainty_radius_meters). Every other is_banner_watched zone (none exist
-- yet) falls back to its own unmodified zones.boundary -- the branch lives entirely here, not
-- assumed anywhere client-side.
-- =========================================================================================
create or replace function public.get_watched_zone_shading_areas()
returns table (
  zone_id uuid,
  zone_slug text,
  zone_name text,
  shading_area geometry
)
language sql
stable
as $$
  select
    z.id,
    z.slug,
    z.name,
    case when z.slug = 'kenai'
      then ST_Union(
        ST_Buffer(public.kenai_river_spike_line()::geography, public.whale_position_verify_buffer_cap_meters())::geometry,
        public.kenai_mouth_semicircle()
      )
      else z.boundary
    end
  from public.zones z
  where z.is_banner_watched
$$;

grant execute on function public.get_watched_zone_shading_areas to anon;
