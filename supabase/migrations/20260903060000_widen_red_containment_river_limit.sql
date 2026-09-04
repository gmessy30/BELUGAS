-- Gives RED's containment check its own upriver limit, deliberately different from the
-- shading limit added in 20260903050000: 13.5 miles for RED, 11.5 miles for shading. These are
-- now two different numbers for two different reasons, not one number reused loosely --
-- shading represents corroborated beluga plausibility shown to the general public, while RED's
-- containment check can afford a wider margin because only credentialed tier 1/2 observers can
-- trigger RED at all (get_kenai_presence_state's tier-gated rule, wired in
-- 20260903020000_wire_observer_tier_into_red_banner.sql) -- the tier gate already does most of
-- the trust work a tighter distance would otherwise need to provide.
--
-- Before this migration, is_whale_position_in_kenai_banner_area's river check had NO upriver
-- limit at all -- it used the zone polygon's full, untruncated 50-mile spike
-- (kenai_river_spike_line(), confirmed live before the shading fix). This migration is the
-- first time RED containment gets a real upriver cutoff; 13.5 miles is intentionally that
-- cutoff, not a widening of some prior narrower one.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB
-- credentials configured, so it cannot be applied automatically.


-- =========================================================================================
-- kenai_river_spike_line_truncated_at_miles: the mouth-to-apex truncation walk from
-- 20260903050000, parameterized instead of hardcoded to 11.5 -- kenai_river_spike_line_
-- beluga_limit (shading, 11.5mi) and kenai_river_spike_line_red_containment_limit (RED
-- containment, 13.5mi, new below) are now both thin wrappers around this one walk, so the two
-- different mile values are the only thing that differs between them -- not two copies of the
-- walking algorithm that could drift apart.
-- =========================================================================================
create or replace function public.kenai_river_spike_line_truncated_at_miles(p_miles double precision)
returns geometry
language plpgsql
stable
as $$
declare
  v_limit_meters double precision := p_miles * 1609.344;
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

-- Refactor only -- same 11.5, same output as 20260903050000's version, now delegating to the
-- parameterized walk above instead of holding its own copy of it.
create or replace function public.kenai_river_spike_line_beluga_limit()
returns geometry
language sql
stable
as $$ select public.kenai_river_spike_line_truncated_at_miles(11.5) $$;

-- RED containment's own limit -- 13.5, not 11.5. See this migration's header for why they're
-- deliberately different.
create or replace function public.kenai_river_spike_line_red_containment_limit()
returns geometry
language sql
stable
as $$ select public.kenai_river_spike_line_truncated_at_miles(13.5) $$;


-- =========================================================================================
-- is_whale_position_in_kenai_banner_area: same signature as before -- the river check now
-- measures against the 13.5-mile RED containment limit instead of the full, uncapped 50-mile
-- spike. This function backs BOTH get_kenai_presence_state's RED/YELLOW containment and
-- get_watched_zone_statuses' kenai branch (verified live which functions reference it before
-- writing this) -- both get this same, intentionally-not-shading-matched limit, since both are
-- the tier-gated "did a credentialed observer confirm this" question, not the general-public
-- shading question.
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
    or ST_Contains(public.kenai_mouth_semicircle(), (select geom from pt))
$$;

-- Orphaned by the change above -- confirmed live (queried pg_proc) that
-- is_whale_position_in_kenai_banner_area was its only caller before this migration, and it no
-- longer is. Dropped rather than left as unused dead code.
drop function if exists public.kenai_river_spike_line();
