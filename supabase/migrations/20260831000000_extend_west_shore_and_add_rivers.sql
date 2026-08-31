-- Extends `cook_inlet_west_shore_kenai_latitude` south through the validated Trading
-- Bay/Redoubt Bay stretch, and adds real river centerline data for the West Cook Inlet
-- rivers CoastlineGeometry.kt didn't have -- see this migration's own commentary below for
-- sourcing and the cross-validation done before writing any of it.

-- `side` gains a third value: 'river'. Rivers aren't a shore -- they don't participate in
-- is_point_within_coastline_channel's east/west closest-point search (that function only
-- ever queries side='east'/'west', so 'river' rows are invisible to it, by construction, not
-- by convention someone could accidentally break) -- this is purely storage for real river
-- geometry that doesn't have anywhere else to live yet (map display, future geofence work).
alter table public.coastline_traces drop constraint if exists coastline_traces_side_check;
alter table public.coastline_traces add constraint coastline_traces_side_check
  check (side in ('east', 'west', 'river'));


-- =========================================================================================
-- WEST SHORE EXTENSION (Trading Bay / Redoubt Bay, ~60.16-60.41)
-- =========================================================================================
-- Extends cook_inlet_west_shore_kenai_latitude (previously 60.4069-60.7281) south to 60.1645,
-- replacing it with one continuous curve rather than a second row -- the new stretch connects
-- to the old one on a shared, identical endpoint (60.4068646,-152.2690165), verified by exact
-- coordinate match (0 distance), not assumed.
--
-- VALIDATION DONE BEFORE WRITING THIS DATA: this stretch was flagged as deliberately not
-- attempted in the prior migration (braided bay geometry, islands including Kalgin). Before
-- extending it, 220 real KPB parcels in this area (services.arcgis.com/.../
-- KPB_Redacted_Parcels_AOB) were cross-checked against these same OSM points: 55 sampled OSM
-- coastline points against 14,000 parcel vertices, median distance 44.7m, 89% within 200m,
-- 95% within 500m -- independent confirmation (legal parcel survey vs. community coastline
-- tracing) that this OSM data is accurate here, not just assumed-good like the northern
-- stretch had to be taken on OSM's own word alone.
--
-- SOURCING: OSM natural=coastline ways (same Overpass query/bbox as the original west-shore
-- trace, queried 2026-08-30), chained on shared nodes: 573754216, 573745723, 573754218,
-- 573745720, 20538592, 625709695, 21747675, 20539307, then the already-migrated 573542868
-- bridging back to the original trace. Downsampled every 28th node (46 new vertices for this
-- stretch, appended to the existing 24).
--
-- SCOPE: still doesn't reach the disconnected fragment further south (way 21750078,
-- ~59.82-59.87) -- no continuous chain of fetched ways bridges the gap between it and this
-- stretch's southern end (60.1645) without further Overpass queries this pass didn't attempt.
-- A point south of 60.1645 on the west shore is a known, disclosed gap, same spirit as
-- Kalgin Island's exclusion in the original migration.
update public.coastline_traces set
  line = ST_GeomFromText('LINESTRING(
    -152.6956727 60.1644926,
    -152.7363976 60.1769916,
    -152.7469132 60.1846627,
    -152.7552581 60.1887640,
    -152.8016282 60.1978408,
    -152.8687048 60.2170528,
    -152.8859139 60.2419812,
    -152.9022002 60.2666513,
    -152.9299021 60.2938415,
    -152.9927302 60.3004017,
    -152.9392040 60.3016933,
    -152.9012138 60.2904722,
    -152.8918147 60.2684710,
    -152.8693057 60.2446038,
    -152.8360180 60.2360097,
    -152.8079796 60.2317261,
    -152.7869618 60.2265267,
    -152.7641201 60.2296060,
    -152.7392507 60.2240653,
    -152.7420616 60.2336330,
    -152.7318263 60.2362642,
    -152.7128255 60.2396674,
    -152.7084589 60.2382827,
    -152.6970542 60.2397632,
    -152.6934064 60.2368874,
    -152.6867116 60.2350818,
    -152.6773238 60.2342189,
    -152.6663804 60.2438476,
    -152.6287866 60.2252268,
    -152.6295376 60.2221578,
    -152.5735545 60.2230210,
    -152.5541882 60.2279854,
    -152.5295878 60.2528454,
    -152.4953842 60.2710675,
    -152.4401307 60.2860144,
    -152.4201375 60.2863920,
    -152.4078208 60.2938814,
    -152.3952198 60.3019591,
    -152.3865938 60.3215616,
    -152.3806608 60.3376430,
    -152.3590636 60.3557489,
    -152.3083699 60.3654267,
    -152.3075867 60.3673524,
    -152.2556162 60.3871225,
    -152.2516036 60.3998436,
    -152.2690165 60.4068646,
    -152.2980273 60.4134127,
    -152.3322416 60.4643798,
    -152.3215048 60.4901158,
    -152.2837043 60.5261275,
    -152.2697139 60.5359658,
    -152.2549081 60.5423032,
    -152.2074008 60.5632378,
    -152.1748924 60.5753420,
    -152.1178539 60.5890197,
    -152.0855427 60.6146807,
    -152.0779413 60.6279364,
    -152.0749319 60.6335666,
    -152.0660162 60.6407922,
    -152.0512533 60.6500751,
    -152.0434322 60.6618263,
    -152.0219207 60.6729789,
    -152.0090032 60.6816483,
    -152.0066428 60.6832979,
    -151.9879103 60.6874792,
    -151.9735980 60.6944538,
    -151.9632983 60.7028760,
    -151.9381285 60.7078001,
    -151.8729005 60.7280577
  )', 4326),
  source_note = 'OSM natural=coastline ways, Overpass API, queried 2026-08-30. Northern stretch (60.4069-60.7281): ways 21750529, 624367035, 573537007, 624335253, 624335252, 573525196. Southern extension (60.1645-60.4069): ways 573754216, 573745723, 573754218, 573745720, 20538592, 625709695, 21747675, 20539307, 573542868. All chained on shared nodes (verified). Southern extension additionally cross-validated against 220 real KPB parcel boundaries (median 44.7m agreement, 89% within 200m) before being written.'
where slug = 'cook_inlet_west_shore_kenai_latitude';


-- =========================================================================================
-- WEST COOK INLET RIVER CENTERLINES (side='river' -- not shore data, see check constraint
-- comment above)
-- =========================================================================================
-- SOURCING: Kenai Peninsula Borough's KPB 21.18 Anadromous Streams layer (services.arcgis.com/
-- ba4DH9pIcqkXJVfl/.../KPB_2118_view/FeatureServer/0), a real esriGeometryPolyline dataset
-- (1,197 streams borough-wide, each tied to Alaska Dept. of Fish & Game's official Anadromous
-- Waters Catalog number), queried 2026-08-30. Each river below is its longest/main mapped
-- channel (several of these rivers have multiple disconnected mapped reaches; the longest was
-- taken as the primary one), downsampled to ~18-20 vertices. Same layer's Kenai/Kasilof River
-- entries were cross-validated against CoastlineGeometry.kt's existing OSM-derived points
-- before this migration (58-161m and 8-122m median agreement respectively) -- see
-- CoastlineGeometry.kt's own KENAI_RIVER_CENTERLINE/KASILOF_RIVER_CENTERLINE comment for that
-- upgrade; these 8 additional rivers are new data, not previously represented anywhere in
-- this app.
--
-- SCOPE: stored for now, not yet wired into any geofence or map-display logic -- none of
-- these fall inside a `zones` polygon the way Kenai/Kasilof do (there is no "west shore"
-- zone in the older well-sourced-zone system, only the newer coastline_traces/RPC one this
-- migration is part of), so there's no existing mechanism to plug river-proximity checks
-- into for them yet. Big River and Drift River's mouths do fall within this migration's west
-- shore trace's own reach; Chakachatna, McArthur, and Beluga sit north of it entirely.
insert into public.coastline_traces (slug, side, region_id, line, source_note) values
('big_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -152.0472785 60.6583147, -152.0462827 60.6920177, -152.0964211 60.7033320,
    -152.1133298 60.7179343, -152.1312173 60.7243221, -152.1314632 60.7328935,
    -152.1476347 60.7397716, -152.1543057 60.7456940, -152.1593395 60.7412714,
    -152.1700159 60.7519132, -152.1860194 60.7557451, -152.1947608 60.7595723,
    -152.2265315 60.7678500, -152.2485867 60.7735781, -152.2641897 60.7879556,
    -152.2824888 60.7869931, -152.2923707 60.7846883, -152.2991438 60.7837004,
    -152.3095439 60.7841129, -152.3105784 60.7841446
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 245-50-10050, 16.9mi), queried 2026-08-30, downsampled.'),
('drift_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -152.1273670 60.5875105, -152.1579333 60.6012256, -152.1643102 60.6066331,
    -152.2049480 60.6224519, -152.2339213 60.6327485, -152.2723761 60.6342769,
    -152.3162462 60.6348255, -152.3495515 60.6364382, -152.3878567 60.6308686,
    -152.4132284 60.6240838, -152.4383458 60.6159351, -152.4629341 60.6121460,
    -152.4828292 60.6095058, -152.4939367 60.6097797, -152.5252434 60.6074096,
    -152.5573310 60.6012736, -152.6058967 60.5982999
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 245-50-10085, 18.1mi), queried 2026-08-30, downsampled.'),
('tuxedni_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -153.2564299 60.2085377, -153.2360913 60.2069290, -153.2111856 60.2095662,
    -153.1940739 60.2194166, -153.1759056 60.2290661, -153.1577601 60.2362735,
    -153.1268972 60.2497719, -153.1124880 60.2603244, -153.1018109 60.2679015,
    -153.0901809 60.2772762, -153.0802685 60.2851410, -153.0631580 60.2917122,
    -153.0380668 60.2949891, -153.0100583 60.2981004, -152.9848756 60.2991129,
    -152.9646125 60.3001218, -152.9407303 60.2982107, -152.9253726 60.2965518
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 245-30-10080, 14.2mi), queried 2026-08-30, downsampled.'),
('crescent_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -152.5585305 60.2252145, -152.5674951 60.2332580, -152.5780261 60.2424608,
    -152.5831224 60.2498594, -152.5911205 60.2551306, -152.5964545 60.2594491,
    -152.5996469 60.2651833, -152.6071723 60.2719380, -152.6127960 60.2793918,
    -152.6272773 60.2869357, -152.6399534 60.2930221, -152.6429415 60.3014752,
    -152.6449545 60.3108429, -152.6673119 60.3203618, -152.6894771 60.3323914,
    -152.7142904 60.3385363, -152.7257307 60.3450645, -152.7374860 60.3502271,
    -152.7427561 60.3537178
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 245-30-10010, 12.4mi), queried 2026-08-30, downsampled.'),
('chakachatna_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -151.7439566 60.9451297, -151.7468637 60.9565698, -151.7599527 60.9644401,
    -151.7533689 60.9721739, -151.7643142 60.9887823, -151.7646503 61.0005123,
    -151.7688918 61.0076928, -151.7761501 61.0180439, -151.7857447 61.0424139,
    -151.7768596 61.0509124, -151.7670191 61.0743386, -151.7790810 61.0909282,
    -151.7952565 61.1199761, -151.8339344 61.1528093, -151.9556901 61.1836671,
    -152.0408152 61.1968825, -152.1624932 61.2031965, -152.2086286 61.2095317,
    -152.2521824 61.2036349
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 247-10-10080-2010, 38.2mi), queried 2026-08-30, downsampled.'),
('mcarthur_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -151.7203803 60.9038585, -151.7315156 60.9354654, -151.7947631 60.9465026,
    -151.8013255 60.9678447, -151.8353735 60.9797720, -151.8587117 60.9794048,
    -151.8715179 61.0190386, -151.8858638 61.0434742, -151.9603353 61.0418723,
    -152.0635297 61.0630352, -152.1053366 61.0733685, -152.1302726 61.0815482,
    -152.1628131 61.0826964, -152.1954585 61.0882103, -152.2370412 61.1098038,
    -152.2879983 61.1130091, -152.3046657 61.1120648, -152.3092093 61.1108134,
    -152.3217720 61.1110571, -152.3309821 61.1102102
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 247-10-10080, 33.3mi), queried 2026-08-30, downsampled.'),
('beluga_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -151.2460957 61.2532207, -151.2388193 61.2415369, -151.2361683 61.2365333,
    -151.2230295 61.2294643, -151.1975037 61.2179806, -151.1804927 61.2126846,
    -151.1668085 61.2047261, -151.1475206 61.2275094, -151.1272322 61.2281991,
    -151.0942147 61.2302005, -151.0722254 61.2360720, -151.0364832 61.2476995,
    -151.0141090 61.2464066, -150.9885109 61.2529927, -150.9759027 61.2493654,
    -150.9640303 61.2340028, -150.9740594 61.2224023, -150.9313959 61.2259705,
    -150.9415044 61.1975717
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 247-30-10090, 21.9mi), queried 2026-08-30, downsampled.'),
('chuitna_river', 'river', 'cook_inlet', ST_GeomFromText('LINESTRING(
    -151.7192236 61.2612995, -151.7136017 61.2554580, -151.7104210 61.2521072,
    -151.7097452 61.2439487, -151.7033266 61.2287371, -151.6898753 61.2136843,
    -151.6780500 61.2044811, -151.6576651 61.1994353, -151.6402835 61.1924955,
    -151.6197929 61.1872851, -151.5934261 61.1875857, -151.5733357 61.1822093,
    -151.5500557 61.1694155, -151.5357774 61.1598213, -151.5142043 61.1528545,
    -151.4922733 61.1525470, -151.4720181 61.1454929, -151.4549520 61.1469441,
    -151.4450657 61.1443892
  )', 4326), 'KPB 21.18 Anadromous Streams (AWC 247-20-10010, 40.8mi), queried 2026-08-30, downsampled.')
on conflict (slug) do update set line = excluded.line, source_note = excluded.source_note;
