-- Two changes needed to wire up the departure-report button client-side:
--
-- 1. Real KML-exported viewing polygon, replacing the placeholder box from
--    20260913000000_add_kenai_departure_report.sql -- same upsert pattern that migration's own
--    comment promised, no schema change. 18 vertices, closed, no self-intersections, 1.89 km2,
--    ~13.3 km perimeter -- a narrow beach ribbon (north bluff/Old Town down through the
--    southwest beach, crossing the channel to the south flats), not a compact area. That shape
--    is intentional, not a mistake.
--
-- 2. is_kenai_departure_reporter: get_observer_tier itself has never been anon-grantable (only
--    internal callers -- report_kenai_departure, the sightings trigger -- use it, both already
--    running with the elevation they need). Rather than grant broad anon access to "look up
--    ANY subscriber_id's exact tier" just so the client can decide whether to show one button,
--    this narrow function answers only the one question the client actually needs. Purely a
--    visibility signal -- report_kenai_departure re-checks tier (and the polygon, and the
--    current phase) itself server-side regardless of what this returns, same "visibility is UX,
--    enforcement is server-side" split as the polygon check.
--
-- Run this in the Supabase SQL editor (or `supabase db push`) -- this repo has no DB credentials
-- configured, so it cannot be applied automatically.
--
-- DUPLICATED ON THE CLIENT: TierClaimScreen.kt's KENAI_DEPARTURE_VIEWING_AREA holds this same
-- ring (reordered lat/lng) for its own visibility-only point-in-polygon check -- there's no
-- client-side geometry-column parsing in this codebase to fetch it instead. If this boundary is
-- ever updated again, that constant needs updating too, in lockstep -- KenaiDepartureViewingAreaTest
-- (shared/src/commonTest) pins the client copy against specific coordinates so an unnoticed edit
-- to one and not the other breaks a test instead of silently drifting.
insert into public.kenai_departure_viewing_areas (slug, name, boundary) values (
  'kenai_mouth', 'Kenai River Mouth',
  ST_GeomFromText('POLYGON((
    -151.2929030905107 60.55840946945002,
    -151.2711621015774 60.548809724266,
    -151.2663236677443 60.54447894339786,
    -151.2807913408272 60.523702563429,
    -151.2728886260558 60.52157475630008,
    -151.2684593705852 60.53299593807134,
    -151.2595707653854 60.54586176396081,
    -151.2559459380841 60.5458859329573,
    -151.2612319296148 60.5497924659469,
    -151.2476677121801 60.55272009801407,
    -151.237950970811 60.55204282721643,
    -151.2403839329403 60.55349234777209,
    -151.2486397201363 60.55399897149227,
    -151.2647257396582 60.55147439417682,
    -151.2694197660138 60.55301499382099,
    -151.274443365124 60.55336655022332,
    -151.289777984689 60.55892294657778,
    -151.2929030905107 60.55840946945002
  ))', 4326)
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary;

create or replace function public.is_kenai_departure_reporter(p_subscriber_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select public.get_observer_tier(p_subscriber_id) = 1
$$;

revoke execute on function public.is_kenai_departure_reporter from public;
grant execute on function public.is_kenai_departure_reporter to anon;
