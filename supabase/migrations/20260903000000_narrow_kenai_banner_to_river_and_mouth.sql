-- Narrows what counts toward the Kenai presence banner's status. Before this migration,
-- get_watched_zone_statuses counted any sighting inside the whole `kenai` zone polygon --
-- which also covers Kasilof and a wide stretch of open Cook Inlet water -- toward Kenai's
-- banner. This migration is a Kenai-only carve-out inside get_watched_zone_statuses: the
-- banner now only counts a sighting as "in Kenai" if it's in the Kenai river itself or in a
-- semicircular mouth area. Nothing else changes -- zones.boundary, export_sightings,
-- match_notification_recipients, and find_nearby_watched_zone all keep using the full
-- Kenai+Kasilof zone polygon Teresa Becher's dispatch/subscription/export pipeline expects.
--
-- PART 1 -- KENAI RIVER: reuses the exact river-spike-proximity logic already in
-- is_whale_position_in_zone (20260902010000_add_river_proximity_fallback_to_zone_matching.sql)
-- -- ring vertices 12-190 of the kenai zone's own polygon are the Kenai River's KPB centerline
-- spike (see 20260831020000_replace_kenai_zone_river_spikes.sql). Kasilof's spike (ring
-- vertices 196-276, same polygon) is deliberately excluded here -- that exclusion is the point
-- of this migration, not an oversight.
--
-- PART 2 -- MOUTH SEMICIRCLE: diameter endpoints Dunes Rd (60.5274555,-151.269096, south
-- bank) and Evergreen St (60.5636498,-151.2917832, north bank/bluff). Radius = half the
-- geodesic distance between them = 2110.32m. Center = their midpoint,
-- POINT(-151.2804396 60.54555265).
--
-- SEAWARD SIDE CONFIRMED AGAINST REAL GEOMETRY, NOT ASSUMED: projected a point 2110.32m from
-- the center, perpendicular to the Dunes->Evergreen line, on each side (the Dunes->Evergreen
-- geodesic bearing +/-90 degrees). The -90 degree side (POINT(-151.3171782585786
-- 60.53996362054823)) fell inside zones.boundary for `kenai` (a polygon that's shore-hugging
-- on the land side and extends far into open Cook Inlet water on the water side) and sat
-- 2342.95m from the kenai_east_shore coastline_traces curve; the +90 degree side
-- (POINT(-151.24368827932366 60.55113156824919)) fell outside zones.boundary (i.e. on land)
-- and sat only 1102.48m from that same shore curve. So the semicircle sweeps from Dunes
-- through the confirmed-seaward -90 degree side to Evergreen, not through the landward +90
-- degree side. Cross-checked against all 44 interior arc vertices below: 40/44 fall inside
-- zones.boundary for kenai (i.e. in the water); the 4 exceptions are the vertices immediately
-- adjacent to Dunes/Evergreen themselves -- an expected edge effect, since those two landmarks
-- are themselves shore points the arc necessarily grazes land near.
--
-- Built as a 46-vertex polygon: 44 arc vertices at 4 degree geodesic-azimuth increments
-- (~150m arc length each, in line with this repo's existing ~150m simplification tolerance --
-- see 20260831020000) plus the two diameter endpoints, closed by the straight
-- Dunes<->Evergreen chord (the river-mouth "entrance line"). ST_IsValid: true. Area:
-- 6,991,252.8 sqm vs. a true semicircle's pi*r^2/2 = 6,995,463.7 sqm (0.06% off, from the
-- 46-gon approximation).
create or replace function public.is_whale_position_in_kenai_banner_area(
  p_lng double precision,
  p_lat double precision,
  p_uncertainty_radius_meters double precision default null
)
returns boolean
language sql
stable
as $$
  with z as (
    select boundary from public.zones where slug = 'kenai'
  ),
  pt as (
    select ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326) as geom
  ),
  -- Kenai River spike centerline only (ring vertices 12-190) -- same extraction
  -- is_whale_position_in_zone uses, deliberately excluding Kasilof's spike (196-276).
  spike_line as (
    select ST_MakeLine(v.geom order by v.idx) as line
    from z, lateral (
      select (dp).path[1] as idx, (dp).geom as geom
      from ST_DumpPoints(ST_ExteriorRing(z.boundary)) as dp
    ) v
    where v.idx between 12 and 190
  ),
  -- Kenai river-mouth semicircle -- see this function's header comment for derivation and
  -- seaward-side confirmation.
  mouth_semicircle as (
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
    ))', 4326) as geom
  )
  select
    (
      p_uncertainty_radius_meters is not null
      and (select line from spike_line) is not null
      and ST_Distance(
            (select line from spike_line)::geography,
            (select geom from pt)::geography
          ) <= least(p_uncertainty_radius_meters, 1000)
    )
    or ST_Contains((select geom from mouth_semicircle), (select geom from pt))
$$;

grant execute on function public.is_whale_position_in_kenai_banner_area to anon;


-- =========================================================================================
-- get_watched_zone_statuses: same signature as before (preserves the existing anon EXECUTE
-- grant). Only the kenai branch changes -- every other watched zone still uses
-- is_whale_position_in_zone exactly as before.
-- =========================================================================================
create or replace function public.get_watched_zone_statuses(
  p_lookback_ms bigint default 86400000
)
returns table (
  zone_id uuid,
  zone_slug text,
  zone_name text,
  last_verified_sighting_epoch_ms bigint,
  last_any_sighting_epoch_ms bigint
)
language sql
stable
as $$
  select
    z.id,
    z.slug,
    z.name,
    max(s.observed_at_epoch_ms) filter (
      where s.observer_type = 'SELF' and s.is_geofence_verified
    ),
    max(s.observed_at_epoch_ms)
  from public.zones z
  left join public.sightings s
    on s.whale_lat is not null and s.whale_lng is not null
    and s.observed_at_epoch_ms >= (extract(epoch from now()) * 1000)::bigint - p_lookback_ms
    and (
      case when z.slug = 'kenai'
        then public.is_whale_position_in_kenai_banner_area(s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
        else public.is_whale_position_in_zone(z.id, s.whale_lng, s.whale_lat, s.uncertainty_radius_meters)
      end
    )
  where z.is_banner_watched
  group by z.id, z.slug, z.name
$$;
