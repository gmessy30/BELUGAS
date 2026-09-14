// Item 100: EXPORT DATA -- ported from native's ExportScreen.kt/ExportUtils.kt, the explicit
// reference for this feature (the PWA had no equivalent at all, even though PRIVACY.md already
// promises a public data download). Field set/format/behavior then narrowed to this app's own
// current data-model and privacy conventions on top, per explicit direction:
//
//   - Exports ONLY position, time, counts, direction, activities, photo URL, and a confirmed
//     flag -- see EXPORT_CSV_HEADER/buildExportGeoJson below, the actual enforcement point. Never
//     id-adjacent identity fields: NOT subscriber_id, NOT observer_id, NOT observer_tier, NOT
//     confirmed_by_subscriber_id -- even though export_sightings' own RPC response includes some
//     of these (confirmed directly against its live return columns; see db.js's exportSightings
//     comment), this file's own builders only ever READ the allowlisted fields off each row, by
//     construction, rather than passing the rest of the row through and trying to strip it after
//     the fact. activities/activity_note (item 90) and confirmed (item 63) are new fields native's
//     own CSV predates entirely.
//   - No region/zone selector -- native offers Regions.ALL plus that region's own named zones;
//     this app has always been Cook Inlet only with no region concept anywhere else in its own
//     UI, so the whole picker is dropped rather than bolted on for just this one screen. The full
//     OUTER_GEOFENCE_* bounding box (geofence.js, already loaded by index.html) is used
//     unconditionally instead of a region's own bounds.
//   - GeoJSON offered alongside CSV -- native only ever produces a CSV (optionally zipped with a
//     photos/ folder for offline viewing). No zip/photo-bundling here: photo_url is exported as a
//     plain link in both formats instead, so a researcher fetches only the photos they actually
//     want rather than downloading every one regardless of need.
//   - Default date range is ALL TIME, not native's trailing 30 days -- an explicit, deliberate
//     product decision for this item, not an oversight.
//   - Row count is shown BEFORE download -- native has no equivalent preview; DOWNLOAD itself is
//     native's only signal, and a truly huge or empty result isn't obvious until the file's
//     already been produced.

const EXPORT_ALL_TIME_START_MS = Date.UTC(2020, 0, 1); // safely before this app's own real data

let exportCachedRecords = null; // last successful fetch's raw rows
let exportCacheKey = null; // `${startMs}|${endMs}` the cache above was fetched for
let exportSelectedFormat = "csv"; // "csv" | "geojson"
let exportFetchToken = 0; // guards a slower, now-superseded fetch from overwriting a newer result

function initExportPage() {
  document.getElementById("export-back-btn").addEventListener("click", () => navigateBack());

  document.getElementById("export-format-csv").addEventListener("click", () => setExportFormat("csv"));
  document.getElementById("export-format-geojson").addEventListener("click", () => setExportFormat("geojson"));

  document.getElementById("export-start-date").addEventListener("change", refreshExportRowCount);
  document.getElementById("export-end-date").addEventListener("change", refreshExportRowCount);

  document.getElementById("export-download-btn").addEventListener("click", handleExportDownload);
}

function setExportFormat(format) {
  exportSelectedFormat = format;
  document.getElementById("export-format-csv").classList.toggle("active", format === "csv");
  document.getElementById("export-format-geojson").classList.toggle("active", format === "geojson");
}

// Empty date input -- ALL TIME on that side, per this item's own default.
function exportDateRangeMs() {
  const startInput = document.getElementById("export-start-date").value;
  const endInput = document.getElementById("export-end-date").value;
  const startMs = startInput ? new Date(`${startInput}T00:00:00Z`).getTime() : EXPORT_ALL_TIME_START_MS;
  const endMs = endInput ? new Date(`${endInput}T23:59:59.999Z`).getTime() : Date.now();
  return { startMs, endMs };
}

async function refreshExportRowCount() {
  const { startMs, endMs } = exportDateRangeMs();
  const cacheKey = `${startMs}|${endMs}`;
  const statusEl = document.getElementById("export-row-count");
  const downloadBtn = document.getElementById("export-download-btn");

  if (cacheKey === exportCacheKey && exportCachedRecords != null) return; // already have this exact range

  const myToken = ++exportFetchToken;
  statusEl.textContent = "Checking how many sightings match…";
  downloadBtn.disabled = true;

  const records = await exportSightings(
    startMs, endMs,
    OUTER_GEOFENCE_MIN_LAT, OUTER_GEOFENCE_MAX_LAT, OUTER_GEOFENCE_MIN_LNG, OUTER_GEOFENCE_MAX_LNG
  );
  if (myToken !== exportFetchToken) return; // a newer request already landed or is in flight

  if (records === null) {
    statusEl.textContent = "Couldn't check — try DOWNLOAD to retry.";
    downloadBtn.disabled = false;
    exportCachedRecords = null;
    exportCacheKey = null;
    return;
  }

  exportCachedRecords = records;
  exportCacheKey = cacheKey;
  statusEl.textContent = records.length === 1 ? "1 sighting matches." : `${records.length} sightings match.`;
  downloadBtn.disabled = false;
}

async function handleExportDownload() {
  const { startMs, endMs } = exportDateRangeMs();
  const cacheKey = `${startMs}|${endMs}`;
  const statusEl = document.getElementById("export-status");
  const downloadBtn = document.getElementById("export-download-btn");

  let records = exportCachedRecords;
  if (cacheKey !== exportCacheKey || records == null) {
    downloadBtn.disabled = true;
    statusEl.hidden = false;
    statusEl.textContent = "Preparing export…";
    records = await exportSightings(
      startMs, endMs,
      OUTER_GEOFENCE_MIN_LAT, OUTER_GEOFENCE_MAX_LAT, OUTER_GEOFENCE_MIN_LNG, OUTER_GEOFENCE_MAX_LNG
    );
    downloadBtn.disabled = false;
    if (records === null) {
      statusEl.textContent = "Export failed — check your connection and try again.";
      return;
    }
    exportCachedRecords = records;
    exportCacheKey = cacheKey;
  }

  if (records.length === 0) {
    statusEl.hidden = false;
    statusEl.textContent = "No sightings match this range.";
    return;
  }

  statusEl.hidden = true;
  const timestamp = Date.now();
  if (exportSelectedFormat === "geojson") {
    downloadTextFile(`belugas_export_${timestamp}.geojson`, "application/geo+json", buildExportGeoJson(records));
  } else {
    downloadTextFile(`belugas_export_${timestamp}.csv`, "text/csv", buildExportCsv(records));
  }
}

// Plain client-side Blob + <a download> trigger -- no server round trip, no File System Access
// API dependency, works the same on Android Chrome and desktop.
function downloadTextFile(filename, mimeType, content) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function csvEscape(value) {
  const str = String(value ?? "");
  return /[",\n\r]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
}

// The ONLY fields this export ever writes, regardless of what export_sightings' own RPC response
// happens to include -- see this file's own header comment.
const EXPORT_CSV_HEADER = [
  "id", "observed_at", "whale_lat", "whale_lng",
  "count_whites", "count_greys", "count_calves", "count_unknown",
  "travel_bearing_degrees", "activities", "activity_note", "photo_url", "confirmed"
];

function buildExportCsv(records) {
  const rows = records.map((r) => [
    r.id ?? "",
    r.observed_at_epoch_ms ? new Date(r.observed_at_epoch_ms).toISOString() : "",
    r.whale_lat ?? "",
    r.whale_lng ?? "",
    r.count_whites ?? 0,
    r.count_greys ?? 0,
    r.count_calves ?? 0,
    r.count_unknown ?? 0,
    r.travel_bearing_degrees ?? "",
    (r.activities || []).join(";"),
    r.activity_note ?? "",
    r.photo_url ?? "",
    r.confirmed_at != null
  ]);
  return [EXPORT_CSV_HEADER, ...rows].map((row) => row.map(csvEscape).join(",")).join("\r\n") + "\r\n";
}

function buildExportGeoJson(records) {
  // Legacy/unplaced rows (no whale_lat/whale_lng) can't become a Point -- excluded here, but
  // still counted in the row-count preview/CSV above, which don't require a real position.
  const features = records
    .filter((r) => r.whale_lat != null && r.whale_lng != null)
    .map((r) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [r.whale_lng, r.whale_lat] },
      properties: {
        id: r.id ?? null,
        observed_at: r.observed_at_epoch_ms ? new Date(r.observed_at_epoch_ms).toISOString() : null,
        count_whites: r.count_whites ?? 0,
        count_greys: r.count_greys ?? 0,
        count_calves: r.count_calves ?? 0,
        count_unknown: r.count_unknown ?? 0,
        travel_bearing_degrees: r.travel_bearing_degrees ?? null,
        activities: r.activities || [],
        activity_note: r.activity_note ?? null,
        photo_url: r.photo_url ?? null,
        confirmed: r.confirmed_at != null
      }
    }));
  return JSON.stringify({ type: "FeatureCollection", features }, null, 2);
}

// Called from the main menu's "Export Data" item. Always resets to CSV/all-time -- same "fresh
// entry resets to the default" convention item 93's search page established for its own chips.
function openExportPage() {
  setExportFormat("csv");
  document.getElementById("export-start-date").value = "";
  document.getElementById("export-end-date").value = "";
  document.getElementById("export-status").hidden = true;
  document.getElementById("export-row-count").textContent = "";
  exportCachedRecords = null;
  exportCacheKey = null;
  document.getElementById("export-page").hidden = false;
  refreshExportRowCount();
}
