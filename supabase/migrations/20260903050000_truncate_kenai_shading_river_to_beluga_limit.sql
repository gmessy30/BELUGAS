-- The Kenai river-shading buffer (get_watched_zone_shading_areas, 20260903040000) was built
-- on kenai_river_spike_line() -- the zone polygon's full KPB-mapped spike, which runs 50.0
-- miles one-way from the river mouth to its apex (confirmed live: ST_Length of the one-way
-- half, vertices 12-101, is 50.00 miles; the full out-and-back 12-190 line is 100.00 miles).
-- That's the SAME spike is_whale_position_in_kenai_banner_area's river-proximity check already
-- uses for RED-eligibility containment -- untouched here, deliberately, see below -- but for
-- SHADING specifically it visually implies watch coverage nearly 4.5x further upriver than the
-- app's own belief about where belugas are plausible (KENAI_RIVER_BELUGA_LIMIT_MILES = 11.5 in
-- CoastlineGeometry.kt, corroborated three ways per that constant's own comment: field
-- observation, the pre-KPB-swap geometry's original "11 mile apex", and the KPB water-body
-- layer's island/braiding pattern starting right around that cutoff). 156 km^2 of shaded area
-- (measured before this migration) vs. ~44 km^2 for the truncated version -- the 3-4x
-- discrepancy flagged before writing this.
--
-- SCOPE: this migration only narrows what get_watched_zone_shading_areas draws. It does NOT
-- touch is_whale_position_in_kenai_banner_area, kenai_river_spike_line, or anything about
-- RED-eligibility/get_kenai_presence_state -- those keep using the full 50-mile spike exactly
-- as before. Whether the ACTUAL containment check should also be limited to 11.5 miles is a
-- separate, real question this migration deliberately does not decide (see the report back to
-- the user this shipped alongside) -- narrowing that would change which sightings can drive
-- RED, a behavioral change with real consequences, not a visual-only one.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- kenai_river_spike_line_beluga_limit: the mouth-to-apex ONE-WAY half of the zone polygon's
-- spike (vertices 12-101 -- 102 is where the out-and-back turns and starts retracing the same
-- nodes back down to the mouth, so only the outbound half is a real single-direction
-- centerline), walked and cut at KENAI_RIVER_BELUGA_LIMIT_MILES exactly the way
-- CoastlineGeometry.kt's truncateAtRiverMiles does client-side: cumulative real-meters
-- (ST_Distance on ::geography per segment, not raw degree distance) from the mouth, cutting
-- the final segment at its own linear-interpolated point rather than snapping to the nearest
-- existing vertex.
--
-- 11.5 MILES IS DUPLICATED ACROSS A LANGUAGE BOUNDARY, NOT SINGLE-SOURCED -- flagging this
-- rather than pretending otherwise: CoastlineGeometry.kt's own copy has to stay a plain Kotlin
-- constant because the functions that use it (isWithinWellSourcedWater, projectOffshoreFallback)
-- must work fully offline, so it can't become a server fetch. If that Kotlin constant is ever
-- retuned, this one needs updating too -- there is no mechanism that enforces that today.
-- =========================================================================================
create or replace function public.kenai_river_spike_line_beluga_limit()
returns geometry
language plpgsql
stable
as $$
declare
  v_limit_meters double precision := 11.5 * 1609.344; -- KENAI_RIVER_BELUGA_LIMIT_MILES, mirrored from CoastlineGeometry.kt -- see header comment
  v_cum_meters double precision := 0;
  v_prev geometry;
  v_cur geometry;
  v_seg_meters double precision;
  v_points geometry[] := '{}';
  rec record;
begin
  for rec in
    select (dp).geom as geom
    from public.zones z, ST_DumpPoints(ST_ExteriorRing(z.boundary)) as dp
    where z.slug = 'kenai'
      and (dp).path[1] between 12 and 101
    order by (dp).path[1]
  loop
    v_cur := rec.geom;
    if v_prev is null then
      v_points := array_append(v_points, v_cur);
    else
      v_seg_meters := ST_Distance(v_prev::geography, v_cur::geography);
      if v_cum_meters + v_seg_meters >= v_limit_meters then
        v_points := array_append(
          v_points,
          ST_LineInterpolatePoint(ST_MakeLine(v_prev, v_cur), (v_limit_meters - v_cum_meters) / v_seg_meters)
        );
        exit;
      end if;
      v_points := array_append(v_points, v_cur);
      v_cum_meters := v_cum_meters + v_seg_meters;
    end if;
    v_prev := v_cur;
  end loop;

  return ST_MakeLine(v_points);
end;
$$;


-- =========================================================================================
-- get_watched_zone_shading_areas: same signature/behavior as before -- Kenai's river buffer
-- now built on the truncated centerline above instead of the full 50-mile spike. Everything
-- else (the buffer cap, the mouth semicircle, the non-Kenai fallback to a zone's own
-- boundary) is unchanged.
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
        ST_Buffer(public.kenai_river_spike_line_beluga_limit()::geography, public.whale_position_verify_buffer_cap_meters())::geometry,
        public.kenai_mouth_semicircle()
      )
      else z.boundary
    end
  from public.zones z
  where z.is_banner_watched
$$;
