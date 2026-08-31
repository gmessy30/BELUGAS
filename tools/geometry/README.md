# River centerline simplification tools

PowerShell scripts used to (re)generate `KENAI_RIVER_CENTERLINE` / `KASILOF_RIVER_CENTERLINE`
in `shared/src/commonMain/kotlin/com/cookinlet/belugas/CoastlineGeometry.kt` from the Kenai
Peninsula Borough's real river survey data. Not part of the app build — run by hand when a
centerline needs re-deriving (new tolerance, a different river, updated source data).

## Source: KPB 21.18 Anadromous Streams

```
https://services.arcgis.com/ba4DH9pIcqkXJVfl/arcgis/rest/services/KPB_2118_view/FeatureServer/0
```

A real `esriGeometryPolyline` layer maintained by the Kenai Peninsula Borough, tied to the
Alaska Dept. of Fish & Game's Anadromous Waters Catalog (AWC) numbers — see the layer's own
`description` field (`?f=json` on the URL above) for KPB's sourcing notes. Fields that matter
here: `Name` (river name), `AWC_No` (ADF&G catalog number), `Miles`.

**Querying a river by AWC ID** (or by name):

```
GET {layer}/query
    ?where=AWC_No='244-30-10010'          (or: where=Name='Kenai River')
    &outFields=OBJECTID,Name,AWC_No,Miles
    &returnGeometry=true
    &outSR=4326                            (WGS84 lat/lng, not the layer's native SR)
    &f=json
```

Both scripts here take `-RiverName` or `-AwcNo` and do this for you.

**Multi-part features.** Some rivers (e.g. Kenai River, AWC `244-30-10010`) come back as a
single feature whose `geometry.paths` holds *several disconnected reaches*, not one continuous
line — side channels, oxbows, and separately-digitized segments alongside the main channel.
Kenai River's feature has 11 paths; the one actually used for `KENAI_RIVER_CENTERLINE` is path
index 2 (312 raw vertices, the longest, running the river's real main-channel length). Always
check `paths.Count` and pick deliberately — `-PathIndex` defaults to the longest path by raw
vertex count, but confirm that's actually the reach you want (print each path's vertex count
and lat/lng span first if unsure).

## Simplification: Douglas-Peucker, 50m tolerance

The straightforward approach — take every Nth raw vertex — was tried first and was wrong: raw
vertex density along these paths is uneven (dense through real meanders, sparse on straight
stretches), so a fixed stride skips whole meander loops and chords straight across them,
visibly cutting corners on the Sightings Map's river line.

Douglas-Peucker fixes this because it's deviation-bounded, not vertex-count-bounded: it keeps
however many points are needed to stay within a max perpendicular distance of the raw line, so
straight stretches collapse to few points and tight meanders keep enough to actually trace the
bend. **50m** was chosen because it's comfortably under the ~0.8-3km buffers
`CoastlineGeometry.isWithinWellSourcedWater` / `GeofenceUtils.isWithin3DFunnel` already use for
geofence proximity checks — the simplification error is a rounding error against those buffers,
not a meaningful source of false accepts/rejects — while still cutting Kenai's 312 raw vertices
down to a maintainable 159, and Kasilof's 990 down to 81.

If a re-run ever produces more than ~300 points at 50m tolerance for a single river, stop and
flag it rather than loosening the tolerance to compensate — that's a sign the raw source (or the
chosen path) is unexpectedly complex and deserves a look before being baked into a hand-edited
Kotlin literal.

## Projection

Both scripts project lat/lng to local flat meters the same way
`CoastlineGeometry.kt`'s own `nearestSegment` does (`METERS_PER_DEGREE_LAT = 111320.0`,
`metersPerDegreeLng(lat) = 111320.0 * cos(lat)`), using a single reference latitude (the mean
latitude of the raw path) for the longitude scale factor. That's deliberate: it keeps deviation/
distance numbers produced here directly comparable to what the app's own geofence checks compute
at runtime, rather than introducing a second, slightly different notion of "meters."

## Usage

```powershell
# Simplify a river, print the Kotlin literal:
.\dp_simplify.ps1 -RiverName "Kenai River" -PathIndex 2 -ToleranceMeters 50

# ...or write it straight to a file:
.\dp_simplify.ps1 -AwcNo "244-30-10050" -ToleranceMeters 50 -OutFile kasilof.txt

# Verify the result is a strictly-ordered walk along the raw path (not just the right set of
# points in the wrong sequence) and see the largest consecutive-point gap:
.\verify_order.ps1 -RiverName "Kenai River" -PathIndex 2 -LiteralFile kenai.txt
```

`verify_order.ps1` re-fetches the same raw path, maps every point in `-LiteralFile` back to its
exact raw-vertex index, and asserts those indices are strictly ascending. This exists because a
Douglas-Peucker implementation that emits points in recursion-visit order instead of re-scanning
its kept-flags by ascending raw index can silently produce an out-of-order polyline — the right
points, wrong sequence, which reads on a map as chords cutting across the river rather than
following it. **Always run this before pasting a new literal into `CoastlineGeometry.kt`.**
It exits non-zero on an order violation.

## Applying a result

Both `dp_simplify.ps1`'s output and `CoastlineGeometry.kt`'s existing literals use the same
`lat to lng` pair format (Kotlin's infix `to` building a `Pair<Double, Double>`), so the output
can be pasted directly into `KENAI_RIVER_CENTERLINE` / `KASILOF_RIVER_CENTERLINE` (or a new
`private val ..._CENTERLINE` for another river). Update that file's inline sourcing comment
(method, tolerance, point count, actual max deviation) alongside the data — see the comment
above `KENAI_RIVER_CENTERLINE` in `CoastlineGeometry.kt` for the expected level of detail.
