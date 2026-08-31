-- is_point_within_coastline_channel's p_max_search_meters default (60km) was chosen without
-- checking it against the actual width of every area it'd need to cover. After extending
-- cook_inlet_west_shore_kenai_latitude south into Trading Bay/Redoubt Bay
-- (20260831000000_extend_west_shore_and_add_rivers.sql), live-testing found real points in
-- that stretch returning null (no coverage) instead of a real answer: measured true distance
-- from several west-shore points there to the nearest 'east' data (lower_inlet_south_east_shore)
-- ranges up to 75.6km -- Cook Inlet is simply wider there than the Kenai-latitude stretch this
-- default was implicitly tuned against. Between-ness (ST_LineLocatePoint in [0,1]) and the
-- width sanity bound (summed distance <= 1.3x crossing width) still guard against nonsensical
-- pairings regardless of how wide the initial search is allowed to be, so raising this doesn't
-- trade away correctness -- it was just too tight to find genuinely-wide-but-real crossings in
-- the first place. 90km gives real margin over the observed 75.6km max without being
-- unboundedly loose.
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
