-- Seeds real coordinate data into `zones` and `point_presets` (schema defined in
-- 20260819000000_add_notification_zones_and_subscriptions.sql -- NOT touched by this file).
-- Additive and reviewable: not run against the live project. Uses `on conflict (slug) do
-- update` throughout so it's safe to re-run once it *is* applied (matches this repo's
-- established idempotent-migration convention).
--
-- SOURCING: every vertex below is either (a) a real point pulled from OpenStreetMap via the
-- Overpass API (https://overpass-api.de/api/interpreter) or Nominatim geocoding, cited by
-- OSM way/node id and query date, or (b) a point computed via disclosed bearing+distance
-- spherical projection from a real anchor (same destination-point formula as
-- shared/src/commonMain/kotlin/com/cookinlet/belugas/HeadingDistance.kt's
-- destinationPoint()), used only to close a polygon's water-side edge -- never to fabricate
-- shore/river detail. Every such computed point is called out explicitly where used.
--
-- NOAA GSHHG and NOAA ENC/nautical chart data (the preferred sources) are distributed as
-- shapefiles/binary formats this environment has no GDAL/geopandas to read (checked: no
-- ogr2ogr, no Python geopandas available). OpenStreetMap coastline + waterway data was used
-- for most zones/points instead, per the explicit fallback in the sourcing instructions.
-- `susitna_delta` (revised 2026-08-20, see below) instead pulled real data directly from
-- USGS NHD's ArcGIS REST service, which turned out to be reachable as live GeoJSON here even
-- without shapefile tooling. All data pulled 2026-08-20 (OSM base timestamp per the Overpass
-- responses / NHD service response: see individual citations).
--
-- KNOWN LIMITATIONS (flagged rather than silently smoothed over):
--   * Zone polygons are simplified traces (real sampled vertices at reduced density, not
--     every OSM/NHD node) and in places just a shore-hugging strip closed with a computed
--     offshore/inland edge rather than a true independently-sourced boundary on both sides.
--     Good enough for a first-pass notification catchment; not surveying-grade.
--   * `kenai` and `lower_inlet_south`'s Kachemak Bay stretch only use coastline points
--     filtered to the open-inlet-facing shore, not the bay's inner shoreline -- noted in the
--     `lower_inlet_south` comment below.
--   * `turnagain_arm_southern` and `susitna_delta` were both revised 2026-08-20 after an
--     initial pass that under-sourced them (a placeholder wedge, and a naive lat-sort,
--     respectively); see their per-zone comments below for what changed and what each is
--     built from now.
--   * Polygon winding/self-intersection was not independently validated (no PostGIS
--     available client-side in this environment to run ST_IsValid). Run
--     `select slug, ST_IsValid(boundary) from zones;` after applying this migration and
--     clean up (ST_MakeValid or manual fix) anything that comes back false before relying on
--     these for actual containment queries.

-- =========================================================================================
-- ZONES
-- =========================================================================================

-- KENAI (folds in Kasilof). Coastline: OSM natural=coastline ways 21750028, 21750570,
-- 573884428, 573884429, 1052003119, 1052003121 (Overpass bbox 60.32,-151.55,60.73,-151.10,
-- queried 2026-08-20), sampled north (near East Foreland, OSM node 5510363981 -- a real cape
-- feature marking where the coast returns to a roughly east-west trend, matching the user's
-- "returns to a roughly east-west orientation" boundary description) to south (near
-- Kasilof). Kenai River arm: OSM way 221509441 (waterway=river "Kenai River"), walked
-- upstream from its westmost/coastal node computing cumulative great-circle distance; the
-- 11-mile point (11.098mi) falls at node index 93 of that walk, lat/lon 60.5459337
-- -151.1252208. Kasilof River arm: OSM way 221540026 (waterway=river "Kasilof River") --
-- note this way's westmost mapped node (60.3860366,-151.3021596) sits ~1-2km short of the
-- literal Cook Inlet shoreline (no further "waterway=river"-tagged segment continues to the
-- coast in OSM for this river), used as the effective river-mouth anchor; 11-mile point
-- (11.022mi) at node index 192, lat/lon 60.3056840 -151.2222750. Both river arms are traced
-- up and then retraced back down the same real centerline nodes (a zero-width inclusion, not
-- a true channel-width polygon -- no riverbank/waterway=riverbank polygon exists in OSM for
-- either river to source real bank geometry from; checked and confirmed absent). Seaward
-- edge closed with two points computed 12km due west (bearing 270) of the northernmost and
-- southernmost real coastal vertices.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'kenai', 'Kenai', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -151.5676882 60.7366758,
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
    -151.2327182 60.5406886,
    -151.2266779 60.5250928,
    -151.1731440 60.5212876,
    -151.1252208 60.5459337,
    -151.1731440 60.5212876,
    -151.2266779 60.5250928,
    -151.2327182 60.5406886,
    -151.2621273 60.5486469,
    -151.2730405 60.5276312,
    -151.2803865 60.4860598,
    -151.2826138 60.4673916,
    -151.2884557 60.4284368,
    -151.2950780 60.3997196,
    -151.3021596 60.3860366,
    -151.2890138 60.3424351,
    -151.2621582 60.3172346,
    -151.2475703 60.3102738,
    -151.2222750 60.3056840,
    -151.2475703 60.3102738,
    -151.2621582 60.3172346,
    -151.2890138 60.3424351,
    -151.3021596 60.3860366,
    -151.3218588 60.3828618,
    -151.3527568 60.3753466,
    -151.3819584 60.3429598,
    -151.3816645 60.3176174,
    -151.5995967 60.3174391,
    -151.5676882 60.7366758
  ))', 4326),
  1
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- LOWER INLET SOUTH: south of Kasilof, covering Ninilchik, Clam Gulch, Anchor Point, Homer.
-- Coastline points filtered from the same Overpass pull (bbox 59.55,-151.95,60.28,-151.15,
-- queried 2026-08-20, 82 natural=coastline ways) to only those west of -151.35 lon -- i.e.
-- the open Cook Inlet-facing shore -- to keep the trace clean; Kachemak Bay's inner,
-- convoluted shoreline (east of that cutoff) is NOT traced here and would need its own pass
-- if this zone is meant to reach further into the bay than Homer's outer coast. Named-place
-- anchors along this stretch, all real OSM nodes (Nominatim/Overpass, 2026-08-20): Ninilchik
-- 60.0400364,-151.6761179; Clam Gulch 60.2301018,-151.3955489; Anchor Point
-- 59.7805612,-151.8392978; Homer (town) 59.6454064,-151.5445643. Seaward edge closed with a
-- computed point 12km due west of the north end and 15km at bearing 225 (southwest, toward
-- the open inlet) of the south end.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'lower_inlet_south', 'Lower Inlet South', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -151.5710751 60.3751679,
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
    -151.3732553 59.5461827,
    -151.5609226 59.4506606,
    -151.5710751 60.3751679
  ))', 4326),
  2
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- SHIP CREEK / KNIK ARM / ANCHORAGE: Knik Arm folded in, south to a line due south of
-- Anchorage. East (Anchorage) and west (Point MacKenzie) shore points extracted from OSM
-- natural=coastline ways (Overpass bbox 60.75,-150.20,61.30,-149.00, queried 2026-08-20; 52
-- ways total for this and the Turnagain Arm zones below) by binning into 0.02-degree
-- latitude bands and taking the max/min-longitude point per band as the east/west shore
-- respectively -- a real-data-based approximation, not a true bank-by-bank trace, so
-- individual bends within a band are smoothed out. One clear outlier bin on the east shore
-- (~61.19,-150.03, inconsistent with its neighbors) was dropped rather than kept. Anchorage
-- anchor (real OSM node 150921144): 61.2163129,-149.8948520 -- "due south of Anchorage" is
-- taken as this zone's southern extent, i.e. roughly the latitude band where this data pull
-- starts (~61.05).
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'ship_creek_knik_arm_anchorage', 'Ship Creek / Knik Arm / Anchorage', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -149.7958923 61.0501897,
    -149.8270671 61.0702701,
    -149.8686609 61.0901281,
    -149.9268614 61.1102239,
    -149.9716155 61.1305650,
    -149.9234689 61.2093600,
    -149.8916513 61.2253725,
    -149.8825525 61.2498793,
    -149.8637985 61.2670324,
    -149.8382770 61.2895950,
    -149.8189027 61.3073970,
    -149.8009818 61.3187581,
    -149.9185563 61.3198466,
    -149.9181032 61.2905041,
    -149.9222257 61.2735680,
    -149.9614696 61.2502319,
    -150.0486695 61.2491436,
    -149.9233488 61.2100046,
    -150.0281811 61.1911934,
    -150.0490272 61.1773396,
    -150.0488381 61.1477858,
    -149.9705297 61.1299173,
    -149.9245010 61.1097138,
    -149.8654364 61.0895569,
    -149.8260286 61.0694978,
    -149.7958923 61.0501897
  ))', 4326),
  3
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- NORTHERN TURNAGAIN ARM: from the due-south-of-Anchorage line to Mile 95.3 Pullout. Both
-- shores, from the same Overpass pull as the Knik Arm zone above, this time binned by
-- 0.03-degree longitude bands (Turnagain Arm runs roughly east-west) taking max-latitude
-- (north shore) / min-latitude (south shore) per band. "Mile 95.3 Pullout" is taken to be
-- Bird Point (real OSM node/way: cape node 7770571889 at 60.9286111,-149.3625000, parking
-- way 182092608 centered 60.9293967,-149.3465, both via Nominatim 2026-08-20) -- this is an
-- INFERENCE, not a directly-sourced fact: no OSM highway=milestone node reading exactly
-- "95.3" was found (the nearest real milestones found are Seward Highway mile 99.5, node
-- 12040922780 at 60.9563453,-149.4216590, and mile 100, node 4365747785 at
-- 60.9616385,-149.4313066). Bird Point's position is consistent with sitting ~3-4 road miles
-- southwest of those, i.e. roughly mile 95-96, but this should be checked against the
-- AKBMP site map before relying on it.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'turnagain_arm_northern', 'Northern Turnagain Arm', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -149.8956907 60.9501879,
    -149.8290968 60.9687547,
    -149.7397115 61.0175752,
    -149.6435683 60.9994750,
    -149.5311968 60.9838916,
    -149.4671907 60.9727474,
    -149.3796517 60.9460508,
    -149.3539654 60.8946491,
    -149.4400256 60.9042512,
    -149.5306776 60.9256969,
    -149.6458563 60.9209726,
    -149.7182679 60.9554544,
    -149.8250628 60.9661930,
    -149.9186612 60.9248332,
    -149.8956907 60.9501879
  ))', 4326),
  4
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- MID TURNAGAIN ARM: Mile 95.3 Pullout (Bird Point, see above -- same caveat applies) to the
-- Twentymile River/Road area. Both shores, same binned Overpass coastline data as the
-- northern zone above, continuing east along the same longitude bands.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'turnagain_arm_mid', 'Mid Turnagain Arm', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -149.3796517 60.9460508,
    -149.2623142 60.9370152,
    -149.1938707 60.9448624,
    -149.0991900 60.9135708,
    -149.0162893 60.8583638,
    -149.0002884 60.8197676,
    -149.0811245 60.8642041,
    -149.1737297 60.8868840,
    -149.2741072 60.8947429,
    -149.3539654 60.8946491,
    -149.3796517 60.9460508
  ))', 4326),
  5
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- UPPER TURNAGAIN ARM: the Twentymile Road area itself (road runs on both sides). Both
-- shores from the same binned coastline data, plus the Twentymile River mouth as a real
-- anchor: OSM way 263775817 (waterway=river "Twentymile River" / alt_name "20 Mile River",
-- source:name "USGS Topo Map"), its south/coastal-most node, 60.8417766,-149.0075211.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'turnagain_arm_upper', 'Upper Turnagain Arm', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -149.1389043 60.9221044,
    -149.0748797 60.9012904,
    -149.0162893 60.8583638,
    -149.0075211 60.8417766,
    -149.0002884 60.8197676,
    -149.0811245 60.8642041,
    -149.1389043 60.9221044
  ))', 4326),
  6
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- SOUTHERN TURNAGAIN ARM: west of Twentymile Road down to the east-west coastline strip
-- below Cook Inlet. REVISED 2026-08-20 (superseding the first pass, which only real-traced
-- two corners and placeholder-projected the rest) -- this is a corrected re-trace, not just a
-- refinement: the first pass's placeholder anchored off turnagain_arm_northern's Anchorage-
-- line corner (~-149.90 lon), which put it near the ARM'S EAST END, contradicting its own
-- "west of Twentymile Road" description. It should instead pick up where
-- turnagain_arm_upper's south shore leaves off (~-149.00 lon) and run west -- fixed here.
--
-- Real OSM natural=coastline data, two fresh Overpass pulls (2026-08-20): bbox
-- 60.70,-150.95,60.95,-148.95 (ways incl. 643711461/462/463, 460425411/412, 709057235/236,
-- 572933441, 894742929/934) for the -149.00 to -151.00 stretch, and bbox
-- 60.55,-151.60,60.85,-150.90 (way 643711461 continues; also 849367427/428, 21748874,
-- 573884428) for the -151.00 to -151.40 stretch. Both were extracted by binning into
-- 0.05-degree longitude bands and taking the coastline vertex closest to the water (south
-- shore facing Turnagain Arm/Cook Inlet) per band -- same technique as the original
-- Ship Creek/Knik Arm and Turnagain Arm zones, just correctly scoped and extended this time.
-- East end (-149.0002884,60.8197676) is the exact real point shared with turnagain_arm_upper;
-- -149.3539654,60.8946491 is the exact real point shared with turnagain_arm_mid -- both
-- confirmed identical across the independent pulls, so this zone's east edge lines up with
-- its neighbors rather than just approximately matching.
--
-- West end (-151.1016559,60.7842395) sits at the start of a real, genuinely flat east-west
-- coastline run (bands at -151.22/-151.17/-151.10 lon all sit within 60.78-60.784 lat) --
-- taken as "the east-west coastline strip below Cook Inlet" the zone description refers to.
-- This stops short of the `kenai` zone's north edge (East Foreland, ~60.737 lat) rather than
-- running into it, to avoid overlapping that zone.
--
-- The two northernmost vertices (lat 60.97) are a computed closure, not traced coastline --
-- there is no second real shoreline bounding this zone's open-water side (it fronts the
-- Turnagain Arm mouth / upper Cook Inlet). 60.97 was chosen because it sits just north of this
-- trace's own highest real point (60.9616 at -149.7500253, near Bird/Indian Point), which
-- guarantees the closing edge can't cut back across the real coastline trace.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'turnagain_arm_southern', 'Southern Turnagain Arm', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -149.0002884 60.8197676,
    -149.0507943 60.8464221,
    -149.1525402 60.8788877,
    -149.2505924 60.8928346,
    -149.3539654 60.8946491,
    -149.4531003 60.9065769,
    -149.5994917 60.9292085,
    -149.6500379 60.9243665,
    -149.7500253 60.9616105,
    -149.8985768 60.9435137,
    -149.9985696 60.8592498,
    -150.0810529 60.8849857,
    -150.1604463 60.8923767,
    -150.2506542 60.9398556,
    -150.6963396 60.9454367,
    -150.7988679 60.8972366,
    -150.8983541 60.8530529,
    -151.0002995 60.8130895,
    -151.1016559 60.7842395,
    -151.1016559 60.9700000,
    -149.0002884 60.9700000,
    -149.0002884 60.8197676
  ))', 4326),
  7
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- SUSITNA DELTA: the delta area where belugas summer, roughly under the Susitna/Beluga area.
-- REVISED 2026-08-20 (superseding the first pass's naive lat-sort of OSM coastline points).
--
-- This time sourced from USGS National Hydrography Dataset (NHD) directly, not OSM -- the
-- "Area - Large Scale" (NHDArea) layer of the National Map's NHD ArcGIS REST service, which
-- turned out to be reachable as a live GeoJSON query API from this sandbox (no GDAL/shapefile
-- handling needed): https://hydro.nationalmap.gov/arcgis/rest/services/nhd/MapServer/9/query
-- queried 2026-08-20 with geometry envelope -151.5,60.95,-150.0,61.40 (esriGeometryEnvelope,
-- inSR=4326), maxAllowableOffset=0.003 degrees (~330m, server-side simplification -- the
-- unsimplified response for this bbox was 25MB of individual channel/waterbody polygons,
-- impractical to process fully here) and geometryPrecision=5, f=geojson. This layer covers
-- exactly the braided distributary channels and waterbody polygons this zone needed, which
-- OSM's plain coastline tagging doesn't represent well for a delta this complex.
--
-- Construction: all polygon vertices from the response were filtered to strictly within the
-- query bbox (dropping vertices belonging to any feature that only clips the bbox corner, so
-- a large feature extending mostly outside the area of interest couldn't skew the shape),
-- leaving 1091 real points. Rather than sorting on one axis (the source of the original
-- pass's zig-zagging), these were binned into 24 fifteen-degree angular sectors around their
-- own centroid (-150.607009,61.214585) and the farthest real point in each sector was kept --
-- a star-shaped/radial hull, which by construction can't self-intersect (every vertex is
-- visible from the centroid, connected to its angular neighbors in order) and traces the
-- delta's actual outer extent, including its braided-channel irregularity, instead of
-- smoothing over it. 22 of 24 sectors had data (two empty sectors near due-southwest are
-- simply skipped in the ring, not filled with an invented point). Real OSM node
-- cross-references that should fall within this trace: Tyonek (150920988, 61.0684293,
-- -151.1409141) and Beluga hamlet (150920134, 61.1791667,-151.0236111).
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'susitna_delta', 'Susitna Delta', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -150.01060 61.23728,
    -150.21966 61.39668,
    -150.52432 61.26504,
    -150.48045 61.39975,
    -150.50635 61.39370,
    -150.60582 61.39919,
    -150.62402 61.37363,
    -150.65768 61.33724,
    -150.67466 61.31459,
    -150.71687 61.30845,
    -150.75815 61.26564,
    -151.49914 61.38022,
    -151.49807 61.00398,
    -151.48493 60.97552,
    -150.64728 61.18136,
    -150.74611 60.95599,
    -150.65717 60.97193,
    -150.59429 61.01403,
    -150.50759 61.00551,
    -150.45348 61.01640,
    -150.18266 60.96603,
    -150.02459 60.96688,
    -150.02066 61.13971,
    -150.01060 61.23728
  ))', 4326),
  8
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;

-- ENTIRE INLET: deliberately coarse by design (not under-sourced -- this one is meant to be
-- "everything," for broad watchers/researchers). Uses the app's own existing
-- RegionConfig.COOK_INLET bounding box (shared/src/commonMain/kotlin/com/cookinlet/belugas/
-- RegionConfig.kt: minLat=59.0, maxLat=61.5, minLng=-154.0, maxLng=-149.0) rather than a
-- freshly invented rectangle, so it stays consistent with the region the rest of the app
-- already uses for geofencing.
insert into public.zones (slug, name, region_id, boundary, display_order) values (
  'entire_inlet', 'Entire Inlet', 'cook_inlet',
  ST_GeomFromText('POLYGON((
    -154.0 59.0,
    -149.0 59.0,
    -149.0 61.5,
    -154.0 61.5,
    -154.0 59.0
  ))', 4326),
  9
)
on conflict (slug) do update set name = excluded.name, boundary = excluded.boundary, display_order = excluded.display_order;


-- =========================================================================================
-- POINT_PRESETS
-- =========================================================================================
-- default_radius_meters is a disclosed, reasoned choice per point (not sourced data) --
-- roughly: a river mouth or creek gets enough radius to cover its immediate nearshore area;
-- a highway pullout gets enough to reasonably span the arm's width at that point since
-- sightings are typically spotted across the water from shore; Anchorage gets a broad
-- city-scale radius matching the "I'm in Anchorage today" temporary/mobile use case.

-- Kenai River: OSM way 221509441 (waterway=river "Kenai River"), westmost/coastal node.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'kenai_river', 'Kenai River', 'cook_inlet', ST_GeogFromText('POINT(-151.2621273 60.5486469)'), 2000, 1
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- Kasilof River: OSM way 221540026 (waterway=river "Kasilof River"), westmost mapped node --
-- see the note on the `kenai` zone above about this being ~1-2km short of the literal coast.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'kasilof_river', 'Kasilof River', 'cook_inlet', ST_GeogFromText('POINT(-151.3021596 60.3860366)'), 1500, 2
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- Ship Creek: OSM way 29407764 (waterway=river "Ship Creek"), westmost/coastal node, near
-- the Port of Alaska / Ship Creek small-boat-launch area in downtown Anchorage. Cross-
-- referenced against nearby real named POIs "Ship Creek Trail" (node 3256209092,
-- 61.2235741,-149.8877507) and "Ship Creek Overlook" (node 3679599330, 61.2206804,
-- -149.8910228), both close by.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'ship_creek', 'Ship Creek', 'cook_inlet', ST_GeogFromText('POINT(-149.9033944 61.2270402)'), 1000, 3
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- Mile 95.3 Pullout: taken as Bird Point (OSM cape node 7770571889 via Nominatim,
-- 2026-08-20) -- see the detailed caveat on the turnagain_arm_northern zone above: this is
-- an inference from nearby real mile-marker nodes (99.5, 100), not a directly-sourced "95.3"
-- marker. Verify against the AKBMP site map before relying on this one.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'mile_95_3_pullout', 'Mile 95.3 Pullout', 'cook_inlet', ST_GeogFromText('POINT(-149.3625000 60.9286111)'), 3000, 4
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- Twentymile River: OSM way 263775817 (waterway=river "Twentymile River" / alt_name "20 Mile
-- River", source:name "USGS Topo Map"), south/coastal-most node.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'twentymile_river', 'Twentymile River', 'cook_inlet', ST_GeogFromText('POINT(-149.0075211 60.8417766)'), 3000, 5
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- The Point (AWCC): Alaska Wildlife Conservation Center, OSM way 297210608 (tourism=zoo) via
-- Nominatim, 2026-08-20.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'the_point_awcc', 'The Point (AWCC)', 'cook_inlet', ST_GeogFromText('POINT(-148.9837033 60.8247740)'), 3000, 6
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;

-- Anchorage: OSM node 150921144 (place=city "Anchorage"), 2026-08-20.
insert into public.point_presets (slug, name, region_id, point, default_radius_meters, display_order) values (
  'anchorage', 'Anchorage', 'cook_inlet', ST_GeogFromText('POINT(-149.8948520 61.2163129)'), 8000, 7
)
on conflict (slug) do update set name = excluded.name, point = excluded.point, default_radius_meters = excluded.default_radius_meters, display_order = excluded.display_order;
