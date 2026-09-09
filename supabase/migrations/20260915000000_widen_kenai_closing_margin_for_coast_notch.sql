-- kenai_closing_margin_meters was 75.0, sized in 20260904000000_close_kenai_containment_seam.sql
-- for a ~10m seam between the river buffer and kenai_mouth_semicircle() near their join. That fix
-- was confirmed live and correct: ST_IsValid, single ring, no interior hole, seam closed.
--
-- Separately, live screenshots (compared across two devices and two days, same pan/zoom, same
-- build) showed a reproducible V-shaped notch cut into the OUTER, coast-facing edge of the mouth
-- semicircle -- nowhere near the river-buffer seam the 75m margin was sized for. It showed up in
-- both the FillLayer and the LineLayer stroke, meaning it was a real vertex-level feature of the
-- returned ring (a valid, simple, but reentrant/concave notch), not a MapLibre fill-tessellation
-- artifact -- ruled out because a pure rendering artifact can't touch the outline stroke, which
-- only draws the ring's actual vertices.
--
-- This notch was several hundred metres across at its opening -- an order of magnitude bigger
-- than the ~10m seam 75.0 was sized for, so it was never going to close at that margin. 300.0 was
-- tried first and closed most but not all of it; 500.0 confirmed live (via
-- get_watched_zone_shading_areas) to close it fully. Area grew from 42.74 to 43.53 km^2 (+1.8%)
-- moving 75 -> 500, consistent with filling concave notches rather than inflating the boundary
-- outward (morphological closing is monotonic that way: increasing the margin can only fill
-- concavities, never remove real coverage, so this was a safe direction to push without
-- re-litigating where the notch came from).
--
-- kenai_closing_margin_meters feeds kenai_river_and_mouth_area(), used by BOTH
-- get_watched_zone_shading_areas() (what renders) and is_whale_position_in_kenai_banner_area()
-- (real containment/verification) -- this also means a sighting that used to land in that coastal
-- notch and fail verification now correctly verifies, not just renders.
create or replace function public.kenai_closing_margin_meters()
returns double precision
language sql
immutable
as $$ select 500.0 $$;
