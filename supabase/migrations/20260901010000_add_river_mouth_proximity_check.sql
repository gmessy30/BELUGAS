-- Adds a river-mouth proximity check to is_point_within_coastline_channel, alongside (not
-- replacing) the existing east/west channel-crossing test. Before this migration, the 8
-- side='river' rows added in 20260831000000_extend_west_shore_and_add_rivers.sql were pure
-- storage -- the function only ever queries side='east'/'west', so a point near, say, the
-- Chakachatna or McArthur river mouth got no benefit from that real river geometry already
-- sitting in the table.
--
-- VERIFIED AGAINST THE LIVE FUNCTION, NOT ASSUMED: the failure mode there is NOT "no
-- coverage" (null) -- both rivers' east/west closest points are well within the 90km search
-- radius, so the channel test runs to completion and returns false, tripped by the
-- width-sanity bound. Concrete case, the Chakachatna river mouth (60.9451297, -151.7439566):
-- east_dist 30,958.7m, west_dist 25,183.6m (both far under 90km, so no null), fraction 0.731
-- (inside [0,1], passes betweenness), channel_width 25,276.3m, summed_dist 56,142.3m -- which
-- exceeds channel_width * 1.3 (32,859.2m), so the width-sanity check fails and the channel
-- test returns false. This is exactly the "up a real side bay/inlet the closest-curve search
-- doesn't know is a dead end" case the original function's own comment already describes --
-- a river mouth is that case. The river check below upgrades this false to true.
--
-- METHOD: after the existing channel test runs, if it didn't already return true, find the
-- single nearest side='river' trace and check the point's distance to it against a radius
-- that's local to THAT trace, not a global constant. Only ever upgrades false/null to true --
-- never overrides an existing channel-test true, and never turns a channel-test true into
-- false.
--
-- WHY A PER-TRACE RADIUS, NOT ONE GLOBAL NUMBER: these 8 traces (each ~18-20 vertices,
-- Douglas-Peucker-style downsampled from the source KPB Anadromous Streams paths, per
-- 20260831000000's own sourcing note) are not uniformly dense. Max gap between consecutive
-- vertices, computed via ST_DumpPoints + lead() over the stored geometry:
--
--   chakachatna_river   7,376 m
--   mcarthur_river      6,032 m
--   big_river           3,748 m
--   beluga_river        3,204 m
--   drift_river         2,830 m
--   tuxedni_river       2,270 m
--   crescent_river      1,811 m
--   chuitna_river       1,892 m
--
-- ST_Distance measures against the straight chord between vertices, not the true meandering
-- channel, so on a wide gap the chord can sit well inside or outside the real river on a bend
-- -- a single tight global radius (e.g. 300m) would systematically miss real near-mouth
-- sightings on chakachatna/mcarthur specifically, while a radius loose enough to cover their
-- 6-7.4km gaps would be far too loose for the other six (crescent/chuitna/tuxedni/drift/
-- big/beluga, all under 3.7km). None of these 8 traces have the kind of independent
-- cross-validation Kenai/Kasilof got (58-161m / 8-122m median agreement against OSM, per
-- 20260831020000) -- that's additional reason not to trust the coarser two at a tight radius.
--
-- FORMULA: for the nearest river trace, compute its own max inter-vertex segment length
-- (v_river_max_segment_m) and scale the check radius to it:
--
--   v_river_mouth_max_meters = greatest(1200, least(3000, v_river_max_segment_m * 0.6))
--
-- 1200m floor: even the six well-behaved traces shouldn't get a radius so tight it stops
-- meaning "near this river" in the presence of real river-mouth width (~100-300m for these
-- salmon streams) plus ordinary sighting-position uncertainty (the app's own DistanceBucket
-- FAR bucket for shore-based sightings is 1200m -- see HeadingDistance.kt -- so this floor
-- matches what the app already treats as a plausible position error at its loosest official
-- bucket). 3000m ceiling: keeps chakachatna/mcarthur meaningfully tighter than the 90km
-- channel-crossing search (20260831010000) -- this is meant to catch a real, localized
-- feature, not become a second wide fallback.
--
-- RESULTING RADIUS PER RIVER (computed from the actual stored geometry, not asserted):
--
--   chakachatna_river   7,376m -> 4,425.6m -> clamped to 3,000.0m (ceiling)
--   mcarthur_river      6,032m -> 3,619.2m -> clamped to 3,000.0m (ceiling)
--   big_river           3,748m -> 2,248.8m
--   beluga_river        3,204m -> 1,922.4m
--   drift_river         2,830m -> 1,698.0m
--   tuxedni_river       2,270m -> 1,362.0m
--   crescent_river      1,811m -> 1,086.6m -> clamped to 1,200.0m (floor)
--   chuitna_river       1,892m -> 1,135.2m -> clamped to 1,200.0m (floor)
--
-- Computed dynamically inside the function (not hardcoded per-slug) so it self-corrects if a
-- river trace's geometry is ever re-simplified or re-sourced, rather than needing this
-- function edited in lockstep with future coastline_traces data changes.
create or replace function public.is_point_within_coastline_channel(
  p_lat double precision,
  p_lng double precision,
  p_max_search_meters double precision default 90000
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
  v_channel_result boolean;
  v_river_line geometry;
  v_river_dist double precision;
  v_river_max_segment_m double precision;
  v_river_mouth_max_meters double precision;
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
    v_channel_result := null;
  elsif v_east_dist > p_max_search_meters or v_west_dist > p_max_search_meters then
    v_channel_result := null;
  else
    v_channel_line := ST_MakeLine(v_east_point, v_west_point);
    v_channel_width_m := ST_Distance(v_east_point::geography, v_west_point::geography);
    v_fraction := ST_LineLocatePoint(v_channel_line, v_point);

    if v_fraction < 0.0 or v_fraction > 1.0 then
      v_channel_result := false;
    elsif v_channel_width_m <= 0 or (v_east_dist + v_west_dist) > v_channel_width_m * 1.3 then
      v_channel_result := false;
    else
      v_channel_result := true;
    end if;
  end if;

  if v_channel_result is true then
    return true;
  end if;

  -- River-mouth proximity: independent second check, see this migration's header for the
  -- per-trace radius derivation. Only the single nearest river trace is considered.
  select t.line, ST_Distance(t.line::geography, v_point::geography)
    into v_river_line, v_river_dist
    from public.coastline_traces t
    where t.side = 'river'
    order by t.line::geography <-> v_point::geography
    limit 1;

  if v_river_line is not null then
    select max(ST_Distance(seg.a::geography, seg.b::geography))
      into v_river_max_segment_m
      from (
        select
          pt.geom as a,
          lead(pt.geom) over (order by (pt.path)[1]) as b
        from ST_DumpPoints(v_river_line) pt
      ) seg
      where seg.b is not null;

    v_river_mouth_max_meters := greatest(1200, least(3000, coalesce(v_river_max_segment_m, 0) * 0.6));

    if v_river_dist <= v_river_mouth_max_meters then
      return true;
    end if;
  end if;

  return v_channel_result;
end;
$$;

grant execute on function public.is_point_within_coastline_channel to anon;
