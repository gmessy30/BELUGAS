-- Closes the real gap between the Kenai river buffer and the river-mouth semicircle, confirmed
-- live against the actual DB before writing this (see the report handed back alongside this
-- migration): get_watched_zone_shading_areas' ST_Union of the two pieces was coming back as a
-- 2-element MultiPolygon, not one merged shape -- they don't actually overlap in area near the
-- mouth. Measured directly off the live shading_area response: the river buffer's closest ring
-- vertex to the mouth sits ~10m from the semicircle's boundary. A point sitting in that ~10m
-- seam (e.g. the river buffer's own mouth-closest vertex) fails is_whale_position_in_kenai_
-- banner_area even at the full 1000m buffer cap, because that function ORs two independent
-- boolean tests (distance-to-line, ST_Contains(semicircle)) that are only as good as the union
-- of the shapes they test against -- same blind spot as the shading union, not a rendering-only
-- issue.
--
-- Fix: a small morphological closing (dilate by a margin, then erode back by the same margin) on
-- the combined river-buffer-plus-semicircle shape, at the one place that shape gets built. A 10m
-- gap needs far less than this to close -- 75m was chosen as comfortable headroom (real-world
-- river-mouth surveys/GPS error easily exceeds 10m), not tuned tight to the measured gap.
-- Closing only ever grows a shape (never shrinks it below its original extent) and specifically
-- fills gaps/notches narrower than 2x the margin, so this is safe to apply unconditionally --
-- it can't remove real coverage, only add coverage in seams/notches under ~150m across.
--
-- SHARED SOURCE, PER THE ASK: kenai_river_and_mouth_area(p_river_line) below is now the ONE place
-- this combined-and-closed shape is built. get_watched_zone_shading_areas and
-- is_whale_position_in_kenai_banner_area both call it -- each with their own already-distinct
-- river line (11.5mi beluga-limit for shading, 13.5mi RED-containment-limit for containment,
-- unchanged from 20260903060000) -- rather than each hand-rolling its own
-- ST_Union(ST_Buffer(...), semicircle) that could drift out of sync.
--
-- SEMANTIC NOTE, worth flagging explicitly rather than burying: is_whale_position_in_kenai_
-- banner_area's OR is unchanged in shape (river-distance-check OR area-containment-check), but
-- the area-containment leg now checks the full closed river-buffer+semicircle area instead of
-- just the bare semicircle. That leg has never been gated by a sighting's own
-- uncertainty_radius_meters (ST_Contains(mouth_semicircle, pt) never consulted it either) -- this
-- change extends that same already-radius-independent leg to also cover the (already-existing,
-- cap-distance) river buffer near the seam, not just the semicircle proper. In practice this
-- only matters for points already within ~1000m of the river/mouth, and only right at the seam
-- where the two shapes used to fail to connect -- consistent with 20260903060000's own reasoning
-- for RED (tier-gating already does most of the trust work a tight distance would otherwise need
-- to provide). Flagged, not hidden -- not treating this as a no-op change.
--
-- EPSG:3338 (Alaska Albers, meters) confirmed present in this DB's spatial_ref_sys before writing
-- this (queried live). Needed because geography's ST_Buffer only accepts positive distances --
-- there's no such thing as a negative-radius geodesic buffer -- so the erosion (inward) half of
-- the closing has to happen in a real planar meters CRS, not geography. The dilation (outward)
-- half stays on geography, same as every other real-world buffer in this file.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.


-- =========================================================================================
-- kenai_closing_margin_meters: single source for the closing operation's margin, same pattern
-- as whale_position_verify_buffer_cap_meters. immutable -- a true constant.
-- =========================================================================================
create or replace function public.kenai_closing_margin_meters()
returns double precision
language sql
immutable
as $$ select 75.0 $$;


-- =========================================================================================
-- kenai_river_and_mouth_area: the shared, closed combined shape -- p_river_line's cap-distance
-- buffer unioned with the mouth semicircle, then morphologically closed (dilate by
-- kenai_closing_margin_meters(), erode back by the same amount) to bridge the real ~10m seam
-- between them confirmed above. Callers pass whichever truncated river line is theirs to use
-- (kenai_river_spike_line_beluga_limit for shading, kenai_river_spike_line_red_containment_limit
-- for containment) -- this function owns only the union-and-close step, not the truncation
-- length, so the 11.5mi/13.5mi distinction from 20260903060000 stays exactly as deliberate as it
-- was there.
-- =========================================================================================
create or replace function public.kenai_river_and_mouth_area(p_river_line geometry)
returns geometry
language sql
stable
as $$
  select ST_Transform(
    ST_Buffer(
      ST_Transform(
        ST_Buffer(
          ST_Union(
            ST_Buffer(p_river_line::geography, public.whale_position_verify_buffer_cap_meters())::geometry,
            public.kenai_mouth_semicircle()
          )::geography,
          public.kenai_closing_margin_meters()
        )::geometry,
        3338
      ),
      -public.kenai_closing_margin_meters()
    ),
    4326
  )
$$;


-- =========================================================================================
-- get_watched_zone_shading_areas: same signature/behavior as before -- Kenai's shading area now
-- comes from the shared, closed kenai_river_and_mouth_area instead of its own inline
-- ST_Union(ST_Buffer(...), semicircle). Visually this only changes the seam near the mouth
-- (now filled) and, as a side effect, should also close the small ~44m x 100m self-intersection
-- hole found upriver in kenai_river_spike_line_beluga_limit's own buffer (well under the 150m
-- span a 75m closing fills) -- worth confirming against the live geometry once this is applied,
-- not assumed here.
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
      then public.kenai_river_and_mouth_area(public.kenai_river_spike_line_beluga_limit())
      else z.boundary
    end
  from public.zones z
  where z.is_banner_watched
$$;


-- =========================================================================================
-- is_whale_position_in_kenai_banner_area: same signature as before. The river-proximity leg is
-- untouched (still the sighting's own least(radius, cap) distance to the 13.5mi RED-containment
-- line -- see this migration's header note on why that stays radius-sensitive). The area leg now
-- checks the shared, closed kenai_river_and_mouth_area (built from that same 13.5mi line) instead
-- of the bare mouth_semicircle, which is what actually closes the seam for points like the ones
-- verified in the report alongside this migration.
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
      and public.kenai_river_spike_line_red_containment_limit() is not null
      and ST_Distance(
            public.kenai_river_spike_line_red_containment_limit()::geography,
            (select geom from pt)::geography
          ) <= least(p_uncertainty_radius_meters, public.whale_position_verify_buffer_cap_meters())
    )
    or ST_Contains(
         public.kenai_river_and_mouth_area(public.kenai_river_spike_line_red_containment_limit()),
         (select geom from pt)
       )
$$;
