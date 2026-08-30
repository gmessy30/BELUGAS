-- Real coastline curves for the "is this point in the water channel" geofence fallback --
-- distinct from `zones.boundary` (closed polygons, used for containment/watched-zone
-- shading). A curve makes ST_ClosestPoint/ST_LineLocatePoint meaningful in a way a closed
-- ring doesn't: given a sighting point, find its closest point on the nearest real shore on
-- each side of the inlet, then check the point actually falls between those two closest
-- points (not beyond either) and isn't implausibly far from them relative to the inlet's
-- real local width -- see is_point_within_coastline_channel below.
--
-- Scope of this first pass: the two already-clean single-shore traces from
-- CoastlineGeometry.kt (kenai, lower_inlet_south -- both trace the Kenai Peninsula's
-- west-facing coast, i.e. the inlet's east/south side), plus one newly-sourced west
-- (mainland) shore trace covering the same latitude band as `kenai`, closing the gap that
-- caused real open-water sightings near the Kenai River mouth to fail the existing
-- (shore-hugging-only) geofence checks.
--
-- NOT included in this pass: `ship_creek_knik_arm_anchorage` and the `turnagain_arm_*`
-- zones. Their existing coastlineOnly point lists are interleaved zigzags between both
-- banks of a narrow arm (needed for their polygon's containment test, not safe to reuse
-- directly as a single directional curve -- ST_ClosestPoint against a zigzag would jump
-- back and forth across the water instead of following one real shore). Splitting those
-- into clean per-bank curves needs real geometric care this migration doesn't attempt --
-- flagged as a known gap, not silently dropped. Those arms are narrow enough that their
-- existing polygon containment check already covers them reasonably well in the meantime.
create table if not exists public.coastline_traces (
  id uuid primary key default gen_random_uuid(),

  -- Stable machine key, no display purpose (these aren't user-facing like `zones`).
  slug text not null unique,

  -- Which side of the inlet's water channel this curve represents. The RPC below groups
  -- candidate curves by this and picks the closest on each side independently.
  side text not null check (side in ('east', 'west')),

  region_id text not null,

  line geometry(LineString, 4326) not null,

  -- Where this curve's vertices came from -- same sourcing-discipline convention as
  -- `zones` (see 20260819130000_seed_notification_zones_and_point_presets.sql's header).
  source_note text not null,

  created_at timestamptz not null default now()
);

create index if not exists coastline_traces_line_gix on public.coastline_traces using gist (line);
create index if not exists coastline_traces_side_idx on public.coastline_traces (side);

alter table public.coastline_traces enable row level security;

drop policy if exists "Public read access to coastline_traces" on public.coastline_traces;
create policy "Public read access to coastline_traces"
on public.coastline_traces for select
to public
using (true);


-- =========================================================================================
-- SEED DATA
-- =========================================================================================

-- KENAI EAST SHORE: direct port of CoastlineGeometry.kt's KENAI.coastlineOnly (already a
-- clean single-shore trace, no closure/spike points, no zigzag) -- itself sourced from OSM
-- natural=coastline ways 21750028, 21750570, 573884428, 573884429, 1052003119, 1052003121,
-- see that migration's/file's own citation. Vertex order preserved (north to south).
insert into public.coastline_traces (slug, side, region_id, line, source_note) values (
  'kenai_east_shore', 'east', 'cook_inlet',
  ST_GeomFromText('LINESTRING(
    -151.3469160 60.7368572,
    -151.3937317 60.7261008,
    -151.4101289 60.7191148,
    -151.4014373 60.6935775,
    -151.3878076 60.6759362,
    -151.3632285 60.6544696,
    -151.3492429 60.6322871,
    -151.3298644 60.5809819,
    -151.3050015 60.5630859,
    -151.2833585 60.5553444,
    -151.2621273 60.5486469,
    -151.2730405 60.5276312,
    -151.2803865 60.4860598,
    -151.2826138 60.4673916,
    -151.2884557 60.4284368,
    -151.2950780 60.3997196,
    -151.3021596 60.3860366,
    -151.3218588 60.3828618,
    -151.3527568 60.3753466,
    -151.3819584 60.3429598,
    -151.3816645 60.3176174
  )', 4326),
  'Direct port of CoastlineGeometry.kt KENAI.coastlineOnly (OSM natural=coastline ways 21750028, 21750570, 573884428, 573884429, 1052003119, 1052003121, per 20260819130000''s citation).'
)
on conflict (slug) do update set line = excluded.line, source_note = excluded.source_note;

-- LOWER INLET SOUTH EAST SHORE: direct port of LOWER_INLET_SOUTH.coastlineOnly, continuing
-- the same Kenai Peninsula shore south past Ninilchik toward Homer/Kachemak Bay (filtered to
-- the open-inlet-facing shore, per that zone's own known-limitation note about Kachemak
-- Bay's inner shoreline not being independently traced).
insert into public.coastline_traces (slug, side, region_id, line, source_note) values (
  'lower_inlet_south_east_shore', 'east', 'cook_inlet',
  ST_GeomFromText('LINESTRING(
    -151.3527568 60.3753466,
    -151.4675938 60.1824602,
    -151.6290098 60.0831595,
    -151.6998027 60.0326754,
    -151.7935463 59.8856007,
    -151.8433034 59.7353228,
    -151.4041742 59.6750709,
    -151.6373241 59.6491266,
    -151.4669234 59.6424097,
    -151.5465474 59.6385074,
    -151.5001532 59.6379875,
    -151.5291233 59.6357842,
    -151.4923374 59.6300155,
    -151.4549495 59.6208577,
    -151.4489685 59.6164695,
    -151.4396240 59.6105027,
    -151.4361317 59.6091295,
    -151.4248518 59.6035425,
    -151.3713133 59.5518838,
    -151.3732553 59.5461827
  )', 4326),
  'Direct port of CoastlineGeometry.kt LOWER_INLET_SOUTH.coastlineOnly, per 20260819130000''s citation.'
)
on conflict (slug) do update set line = excluded.line, source_note = excluded.source_note;

-- COOK INLET WEST SHORE (KENAI LATITUDE BAND): the mainland shore opposite `kenai_east_shore`
-- (West Foreland down through the northern Trading Bay approach) -- newly sourced to close
-- the gap that caused real open-water sightings near the Kenai River mouth (e.g. lat
-- ~60.55, lng ~-152.0) to fail the existing shore-hugging-only geofence.
--
-- SOURCING: OSM natural=coastline ways, queried live via Overpass API
-- (https://overpass-api.de/api/interpreter, bbox 59.4,-153.6,60.8,-151.9) on 2026-08-30.
-- Six ways chain end-to-end on shared real OSM nodes (verified numerically, not assumed):
-- 21750529 (60.40686-60.46990), 624367035 (60.46990-60.54230), 573537007
-- (60.54230-60.58902), 624335253 (60.58902-60.63561), 624335252 (60.63561-60.66147),
-- 573525196 (60.66147-60.72806) -- 589 raw nodes concatenated in that south-to-north order,
-- downsampled to every 26th node (24 vertices) for a density comparable to the existing
-- zones' own simplified traces. A small closed-loop spur way (624335249, a single cove/spit
-- detail at 60.645,-152.063) was excluded as noise, not part of the through-shore.
--
-- SCOPE: covers 60.4069-60.7281, bracketing `kenai_east_shore`'s 60.3176-60.7369 span at
-- the north end (with margin) but not the full length -- south of ~60.41 (the braided
-- Trading Bay / Redoubt Bay interior, with numerous coves and islands including Kalgin
-- Island) was deliberately not attempted in this pass; that stretch needs more careful
-- per-feature review than a first pass should rush. A point on or near Kalgin Island itself
-- (roughly 60.34-60.52 lat) is a known gap this data does not protect against.
insert into public.coastline_traces (slug, side, region_id, line, source_note) values (
  'cook_inlet_west_shore_kenai_latitude', 'west', 'cook_inlet',
  ST_GeomFromText('LINESTRING(
    -151.8729005 60.7280577,
    -151.9381285 60.7078001,
    -151.9632983 60.7028760,
    -151.9735980 60.6944538,
    -151.9879103 60.6874792,
    -152.0066428 60.6832979,
    -152.0090032 60.6816483,
    -152.0219207 60.6729789,
    -152.0434322 60.6618263,
    -152.0512533 60.6500751,
    -152.0660162 60.6407922,
    -152.0749319 60.6335666,
    -152.0779413 60.6279364,
    -152.0855427 60.6146807,
    -152.1178539 60.5890197,
    -152.1748924 60.5753420,
    -152.2074008 60.5632378,
    -152.2549081 60.5423032,
    -152.2697139 60.5359658,
    -152.2837043 60.5261275,
    -152.3215048 60.4901158,
    -152.3322416 60.4643798,
    -152.2980273 60.4134127,
    -152.2690165 60.4068646
  )', 4326),
  'OSM natural=coastline ways 21750529, 624367035, 573537007, 624335253, 624335252, 573525196 (Overpass API, bbox 59.4,-153.6,60.8,-151.9, queried 2026-08-30), chained on shared nodes, downsampled every 26th vertex.'
)
on conflict (slug) do update set line = excluded.line, source_note = excluded.source_note;


-- =========================================================================================
-- is_point_within_coastline_channel: real-coastline-curve replacement for (an additional
-- fallback tier ahead of) GeofenceUtils' sparse 7-point check, for locations the existing
-- well-sourced zone polygons don't cover -- specifically genuinely far-offshore/mid-channel
-- points, which is exactly what the polygon-containment approach can't validate (a shore-
-- hugging polygon has no interior far from any shore, by construction).
--
-- Method: closest point on the nearest 'east'-side curve, closest point on the nearest
-- 'west'-side curve, then two checks against the straight line connecting those two closest
-- points (the local "channel crossing" at this point of the inlet):
--   1. Between-ness: ST_LineLocatePoint's fraction must fall in [0,1] -- outside that range
--      means the point is beyond one of the two shores along the crossing, not between them
--      (e.g. past the mouth of the inlet, or up a narrow arm past where real data ends).
--   2. Width sanity bound: the point's own distance to each closest shore point, summed,
--      must not exceed the direct east-west crossing distance by more than 30% -- catches a
--      point that's technically "between" in the fraction sense but far off the direct line
--      (e.g. up a real side bay the closest-curve search doesn't know is a dead end).
--
-- Returns null (not false) when either side has no covering curve within
-- p_max_search_meters -- that's an absence-of-data case, not a rejection; callers must
-- treat null the same way GeofenceUtils already treats isWithinWellSourcedWater's null
-- (defer to the next, coarser fallback tier) rather than as a confident answer.
-- =========================================================================================
create or replace function public.is_point_within_coastline_channel(
  p_lat double precision,
  p_lng double precision,
  p_max_search_meters double precision default 60000
)
returns boolean
language plpgsql
stable
as $$
declare
  v_point geometry := ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326);
  v_east_point geometry;
  v_east_dist double precision;
  v_west_point geometry;
  v_west_dist double precision;
  v_channel_line geometry;
  v_channel_width_m double precision;
  v_fraction double precision;
begin
  select ST_ClosestPoint(t.line, v_point), ST_Distance(t.line::geography, v_point::geography)
    into v_east_point, v_east_dist
    from public.coastline_traces t
    where t.side = 'east'
    order by t.line::geography <-> v_point::geography
    limit 1;

  select ST_ClosestPoint(t.line, v_point), ST_Distance(t.line::geography, v_point::geography)
    into v_west_point, v_west_dist
    from public.coastline_traces t
    where t.side = 'west'
    order by t.line::geography <-> v_point::geography
    limit 1;

  if v_east_point is null or v_west_point is null then
    return null;
  end if;

  if v_east_dist > p_max_search_meters or v_west_dist > p_max_search_meters then
    return null;
  end if;

  v_channel_line := ST_MakeLine(v_east_point, v_west_point);
  v_channel_width_m := ST_Distance(v_east_point::geography, v_west_point::geography);
  v_fraction := ST_LineLocatePoint(v_channel_line, v_point);

  if v_fraction < 0.0 or v_fraction > 1.0 then
    return false;
  end if;

  if v_channel_width_m <= 0 or (v_east_dist + v_west_dist) > v_channel_width_m * 1.3 then
    return false;
  end if;

  return true;
end;
$$;

grant execute on function public.is_point_within_coastline_channel to anon;
