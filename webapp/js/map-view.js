// Map tab: plots real whale-position pins (whale_lat/whale_lng), not the old frozen
// observer-position lat/lng -- matches the native app's post-redesign map rendering.
//
// Marker/label styling below is pulled directly from SightingsMapScreen.kt's actual MapLibre
// layers, not invented for the web: a plain circular dot (native: CircleLayer radius 10dp,
// Color.Yellow fill, black 2dp stroke) with a permanent text caption below it (native:
// SymbolLayer, black text/white halo, "{N} Belugas · {date}"), gray instead of yellow for this
// device's own not-yet-synced queue (native: "Local" source -> Color(0xFF9E9E9E)), and the same
// cyan (0xFF00E5FF) uncertainty circle at the same 18% fill / stroke treatment. Clustering (below)
// uses Leaflet.markercluster (native: GeoJsonOptions(cluster = true)) with a matching solid-orange
// (0xFFFF6D00), black-stroke badge.
let mapInstance = null;
let mapShadingLayer = null;
let mapUncertaintyLayer = null;
let mapMarkersLayer = null;
let lastCombinedSightings = [];
let mapVerifiedOnly = false;

// Item 76: on-screen diagnostics for why #presence-banner/#playback-fab-btn aren't rendering as
// expected on Map -- same ?debug=1 pattern as items 39/47. Reports REAL measured/computed DOM
// state, not source-code reasoning, so a report of "the banner's invisible" can be checked
// against actual numbers read off the phone instead of guessed at again. Polled on an interval
// (below) rather than hooked to every individual call site that could change this -- no single
// event reliably covers every cause (orientation change, the browser address bar showing/hiding,
// the banner's own async re-render, a tab switch) -- negligible cost, and this whole block is
// meant to come out once item 76 is actually resolved.
//
// NOTE: .map-canvas does not exist in the current code -- item 75 (which introduced it) was
// fully reverted (see fdab633). The Leaflet map container is #map-view itself again, so "inside
// .map-canvas" below is checked against #map-view, the closest live equivalent.
// Item 83a: readable Anchorage-local timestamp (plus the raw epoch ms, for exact comparison)
// used by the date-range debug lines below -- so a reported range can be read directly off the
// phone instead of guessed at from raw milliseconds.
function fmtAnchorageTime(ms) {
  if (ms == null) return "—";
  const formatted = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Anchorage", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
  }).format(new Date(ms));
  return `${formatted} (${ms})`;
}

function renderMapDebugOverlay() {
  const el = document.getElementById("map-debug-overlay");
  if (!el) return;

  const banner = document.getElementById("presence-banner");
  const fab = document.getElementById("playback-fab-btn");
  const mapView = document.getElementById("map-view");
  if (!banner || !fab || !mapView) return;

  const bannerStyle = getComputedStyle(banner);
  const fmt = (r) => `${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.width)}x${Math.round(r.height)}`;

  el.textContent = [
    "--- #presence-banner ---",
    `parent id: ${banner.parentElement ? (banner.parentElement.id || `(no id, <${banner.parentElement.tagName.toLowerCase()}>)`) : "(detached)"}`,
    `computed display: ${bannerStyle.display}`,
    `computed position: ${bannerStyle.position}`,
    `offsetHeight: ${banner.offsetHeight}`,
    `hidden attribute present: ${banner.hasAttribute("hidden")}`,
    `bounding rect: ${fmt(banner.getBoundingClientRect())}`,
    `inside #map-view (Leaflet container -- .map-canvas doesn't exist post-revert): ${mapView.contains(banner)}`,
    "--- #playback-fab-btn ---",
    `bounding rect: ${fmt(fab.getBoundingClientRect())}`,
    `computed display: ${getComputedStyle(fab).display}`,
    "--- viewport / map ---",
    `window.innerHeight: ${window.innerHeight}`,
    `#map-view rect: ${fmt(mapView.getBoundingClientRect())}`,
    "--- item 83a: time-lapse date range ---",
    `panel open: ${playbackIsOpen}`,
    `selected quick range: ${playbackSelectedQuickRange}`,
    `now: ${fmtAnchorageTime(Date.now())}`,
    `computed range start: ${fmtAnchorageTime(playbackRangeStart)}`,
    `computed range end: ${fmtAnchorageTime(playbackRangeEnd)}`
  ].join("\n");
}

function initMapDebugOverlay() {
  if (!DEBUG_MODE_ENABLED) return;
  const el = document.getElementById("map-debug-overlay");
  if (!el) return;
  el.hidden = false;
  renderMapDebugOverlay();
  setInterval(renderMapDebugOverlay, 500);
}

function initMap() {
  if (mapInstance) return;

  initMapDebugOverlay();

  mapInstance = L.map("map-view", { zoomControl: true }).setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);

  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19
  }).addTo(mapInstance);

  // Shading added before the markers layer so it always paints underneath sighting pins,
  // matching SightingsMapScreen's own draw order (shading, then uncertainty circles, then pins).
  mapShadingLayer = L.layerGroup().addTo(mapInstance);

  // BUG FIX (item 73): uncertainty circles used to be added directly to mapMarkersLayer (the
  // markerClusterGroup below) -- L.markerClusterGroup accepts any layer via addLayer/addTo, not
  // just markers, so each circle silently became an extra COUNTED child of whatever cluster its
  // sighting fell into (5 sightings, some with a circle, reading as a cluster count of 8), and
  // spiderfying tried to lay out a "leg" for it too even though a circle has no icon to show at
  // the leg's end -- a leg with no dot. A separate, plain (non-clustered) layer group for these
  // keeps the cluster group's own count exactly equal to the number of markers in it, matching
  // native's own uncertainty circles (their own unclustered FillLayer, never part of the
  // GeoJsonOptions(cluster=true) source). Added before the markers layer, same draw-order reason
  // as mapShadingLayer above.
  mapUncertaintyLayer = L.layerGroup().addTo(mapInstance);

  // zoomToBoundsOnClick: false -- clusterClick below decides between zooming in (the normal case)
  // and showing the same-point sightings sheet (when every pin in the cluster shares one exact
  // coordinate, common for a fixed vantage point's manual reports -- zooming further wouldn't
  // separate them, matching SightingsMapScreen's own onClusterClick special case).
  mapMarkersLayer = L.markerClusterGroup({
    iconCreateFunction: clusterIconFn,
    zoomToBoundsOnClick: false,
    showCoverageOnHover: false
  }).addTo(mapInstance);
  mapMarkersLayer.on("clusterclick", (event) => {
    const cluster = event.layer;
    const children = cluster.getAllChildMarkers();
    const firstLatLng = children[0].getLatLng();
    const allSamePoint = children.every((m) => m.getLatLng().equals(firstLatLng));
    if (allSamePoint) {
      showSamePointSightingsSheet(children.map((m) => m.sightingData));
    } else {
      mapInstance.fitBounds(cluster.getBounds().pad(0.2));
    }
  });

  onPresenceStateChanged(drawWatchedZoneShading);
  drawWatchedZoneShading();

  const toggleBtn = document.getElementById("map-verified-toggle-btn");
  toggleBtn.addEventListener("click", () => {
    mapVerifiedOnly = !mapVerifiedOnly;
    updateMapVerifiedToggleUi();
    drawMapMarkers();
  });
  updateMapVerifiedToggleUi();

  document.getElementById("same-point-close-btn").addEventListener("click", () => {
    navigateBack();
  });

  initPlaybackPanel();
}

function clusterIconFn(cluster) {
  return L.divIcon({
    html: `<div class="sighting-cluster-badge">${cluster.getChildCount()}</div>`,
    className: "sighting-cluster-icon",
    iconSize: [32, 32]
  });
}

function showSamePointSightingsSheet(sightings) {
  const list = document.getElementById("same-point-list");
  list.innerHTML = "";
  sightings
    .slice()
    .sort((a, b) => (b.observed_at_epoch_ms || 0) - (a.observed_at_epoch_ms || 0))
    .forEach((s) => {
      const item = document.createElement("div");
      item.className = "same-point-item";
      item.textContent = sightingCaptionText(s);
      list.appendChild(item);
    });
  document.getElementById("same-point-sheet").hidden = false;
  pushNavLayer("same-point-sheet", () => {
    document.getElementById("same-point-sheet").hidden = true;
  });
}

function updateMapVerifiedToggleUi() {
  const toggleBtn = document.getElementById("map-verified-toggle-btn");
  toggleBtn.textContent = mapVerifiedOnly ? "✓ VERIFIED ONLY" : "VERIFIED ONLY";
  toggleBtn.classList.toggle("active", mapVerifiedOnly);
}

function renderSightingsOnMap(sightings) {
  lastCombinedSightings = sightings;
  drawMapMarkers();
}

/**
 * River/zone presence shading -- ports SightingsMapScreen's own FillLayer/LineLayer loop
 * exactly, no gating of any kind (every viewer sees every server-flagged watched zone,
 * regardless of subscriptions or location -- confirmed against App.kt/SightingsMapScreen.kt's
 * own comments before porting this). For "kenai" specifically, status is kenaiBelugaStatus (the
 * already-escalated value shared with the banner); every other zone uses
 * computeBelugaPresenceStatus's flat-decay result DIRECTLY -- deliberately NOT run through
 * effectivePresenceStatus's staleness escalation, unlike the banner's own non-Kenai handling.
 * That asymmetry is real in the native source (SightingsMapScreen.kt never calls
 * effectivePresenceStatus at all), not an oversight to "fix" here.
 */
function drawWatchedZoneShading() {
  if (!mapShadingLayer) return;
  mapShadingLayer.clearLayers();

  presenceState.watchedZoneShadingAreas.forEach((zoneShading) => {
    let zoneStatus;
    if (zoneShading.zone_slug === "kenai") {
      zoneStatus = presenceState.kenaiBelugaStatus;
    } else if (!presenceState.hasEverFetchedWatchedZoneStatuses) {
      zoneStatus = PRESENCE_UNKNOWN;
    } else {
      const statusRow = presenceState.watchedZoneStatuses.find((s) => s.zone_id === zoneShading.zone_id);
      zoneStatus = computeBelugaPresenceStatus(statusRow, Date.now());
    }

    // UNKNOWN isn't drawn in a placeholder color -- it isn't drawn at all, same as native.
    if (zoneStatus === PRESENCE_UNKNOWN) return;

    const zoneColor = colorForBelugaPresenceStatus(zoneStatus);
    L.geoJSON(
      { type: "Feature", properties: {}, geometry: zoneShading.shading_area },
      { style: { fillColor: zoneColor, fillOpacity: 0.35, color: zoneColor, weight: 3, opacity: 0.7 } }
    ).addTo(mapShadingLayer);
  });
}

// Native's own dot color (Color.Yellow), not a generic map-pin yellow -- kept as a named
// constant since both the marker fill and its border/label styling below need to agree with it.
const SIGHTING_DOT_COLOR = "#FFFF00";
const SIGHTING_DOT_LOCAL_COLOR = "#9E9E9E";

// skipFitBounds -- a playback scrub/tick/chip change redraws this very frequently (up to every
// 100ms while playing) and should never hijack the user's current pan/zoom the way a genuinely
// new data refresh should; only real data refreshes (renderSightingsOnMap) and the VERIFIED ONLY
// toggle fit bounds.
function drawMapMarkers(skipFitBounds = false) {
  if (!mapInstance) return;
  mapMarkersLayer.clearLayers();
  mapUncertaintyLayer.clearLayers();

  const visible = lastCombinedSightings.filter((s) => {
    if (s.whale_lat == null || s.whale_lng == null) return false;
    if (playbackIsOpen && !isWithinPlaybackWindow(s)) return false;
    if (s.is_local) return true; // never filtered by VERIFIED ONLY -- see isHighConfidence's own comment
    return !mapVerifiedOnly || isHighConfidence(s);
  });

  visible.forEach((s) => {
    const marker = L.marker([s.whale_lat, s.whale_lng], { icon: sightingDivIcon(s.is_local) });
    marker.sightingData = s; // read back by the cluster-click handler's same-point check above
    marker.bindPopup(sightingPopupHtml(s));
    marker.bindTooltip(sightingCaptionText(s), {
      permanent: true,
      direction: "bottom",
      offset: [0, 10],
      className: "sighting-label"
    });
    mapMarkersLayer.addLayer(marker);

    // Plain uncertainty circle -- same replacement for the old heading/distance sector wedge
    // the native map switched to post-redesign (SightingRecord.uncertaintyRadiusMeters' comment).
    // Color/opacity match SightingsMapScreen's FillLayer(0.18 opacity)/LineLayer(1.5dp) exactly.
    // Item 73: its own non-clustered layer, NOT mapMarkersLayer -- see mapUncertaintyLayer's own
    // declaration comment for why (a circle counted as a cluster child, with no icon to spiderfy
    // out to, is exactly what phantom-inflated the cluster badge count before).
    if (s.uncertainty_radius_meters != null) {
      L.circle([s.whale_lat, s.whale_lng], {
        radius: s.uncertainty_radius_meters,
        color: "#00E5FF",
        weight: 1.5,
        fillOpacity: 0.18,
        opacity: 1
      }).addTo(mapUncertaintyLayer);
    }
  });

  if (!skipFitBounds && visible.length > 0) {
    const bounds = L.latLngBounds(visible.map((s) => [s.whale_lat, s.whale_lng]));
    mapInstance.fitBounds(bounds.pad(0.2), { maxZoom: 12 });
  }
}

function sightingDivIcon(isLocal) {
  const color = isLocal ? SIGHTING_DOT_LOCAL_COLOR : SIGHTING_DOT_COLOR;
  return L.divIcon({
    className: "sighting-dot-icon",
    html: `<div class="sighting-dot" style="background:${color}"></div>`,
    iconSize: [20, 20],
    iconAnchor: [10, 10]
  });
}

// "{N} Belugas · {EEE, MMM d}" -- matches SightingsMapScreen's own captionText exactly (the
// "⏳ QUEUED ·" prefix included, native's literal treatment for a locally-queued item).
function sightingCaptionText(s) {
  const total = (s.count_whites || 0) + (s.count_greys || 0) + (s.count_calves || 0) + (s.count_unknown || 0);
  const prefix = s.is_local ? "⏳ QUEUED · " : "";
  const dateLabel = s.observed_at_epoch_ms
    ? new Date(s.observed_at_epoch_ms).toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" })
    : "Unknown date";
  return `${prefix}${total} Beluga${total !== 1 ? "s" : ""} · ${dateLabel}`;
}

function sightingPopupHtml(s) {
  const time = s.observed_at_epoch_ms ? new Date(s.observed_at_epoch_ms).toLocaleString() : "Unknown time";
  const counts = formatCounts(s);
  const direction = formatTravelDirection(s.travel_bearing_degrees);
  const photo = s.photo_url
    ? `<img src="${escapeHtml(s.photo_url)}" alt="Sighting photo" style="width:100%;border-radius:6px;margin-top:6px;">`
    : "";
  return `<div class="popup"><strong>${time}</strong><br>${counts}<br>${direction}${photo}</div>`;
}

// Same 8-point display-only snapping SightingsMapScreen's own travel-bearing stub rendering
// uses (SightingRecord.kt's snapToNearestCompass8Degrees) -- the stored value keeps its full
// precision, only the label shown here is snapped.
const COMPASS_POINT_LABELS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
function formatTravelDirection(bearingDegrees) {
  if (bearingDegrees == null) return "Direction: unknown";
  const normalized = ((bearingDegrees % 360) + 360) % 360;
  const index = Math.round(normalized / 45) % 8;
  return `Direction: ${COMPASS_POINT_LABELS[index]}`;
}

function formatCounts(s) {
  const parts = [];
  if (s.count_whites) parts.push(`${s.count_whites} white`);
  if (s.count_greys) parts.push(`${s.count_greys} grey`);
  if (s.count_calves) parts.push(`${s.count_calves} calf/calves`);
  if (s.count_unknown) parts.push(`${s.count_unknown} unknown`);
  return parts.length ? parts.join(", ") : "Count not recorded";
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str;
  return div.innerHTML;
}

// Leaflet sizes itself off its container's dimensions at creation time -- if the map tab wasn't
// visible yet (display:none) when initMap ran, tiles render into a collapsed 0-height box. Called
// whenever the map tab is switched into view.
function invalidateMapSize() {
  if (mapInstance) mapInstance.invalidateSize();
}

// --- Playback / time-lapse + date-range filter -- SightingsMapScreen.kt's own scrub slider,
// quick-range chips, fade window, and speed control. Ported as one panel, matching native's own
// single Surface/Column (no separate bottom-sheet wrapper for the date range). ---

// PlaybackRange.kt's QuickRange enum. Default is ALL_TIME (native: `remember { mutableStateOf
// (QuickRange.ALL_TIME) }`), not "since midnight" -- confirmed directly against source rather
// than assumed.
const QUICK_RANGES = [
  { key: "TODAY", label: "TODAY" },
  { key: "YESTERDAY", label: "YESTERDAY" },
  { key: "THIS_SEASON", label: "SEASON" },
  { key: "ALL_TIME", label: "ALL TIME" },
  { key: "CUSTOM", label: "CUSTOM" }
];
const PLAYBACK_SPEED_OPTIONS = [1, 5, 10, 30, 60];
const FADE_WINDOW_OPTIONS = [
  { key: "2h", label: "2H", ms: 2 * 60 * 60 * 1000 },
  { key: "6h", label: "6H", ms: 6 * 60 * 60 * 1000 },
  { key: "12h", label: "12H", ms: 12 * 60 * 60 * 1000 },
  { key: "24h", label: "24H", ms: 24 * 60 * 60 * 1000 },
  { key: "ALL", label: "ALL", ms: null }
];

const DAY_MS = 86400000;
const HALF_DAY_MS = DAY_MS / 2;

// PlaybackRange.kt's own FALL_START/END + SPRING_START/END constants (kept in sync with
// PresenceBanner.kt's isKenaiInSeasonLocally natively -- ported verbatim, not re-derived).
const SPRING_START_MONTH = 3, SPRING_START_DAY = 15;
const SPRING_END_MONTH = 5, SPRING_END_DAY = 14;
const FALL_START_MONTH = 8, FALL_START_DAY = 15;
const FALL_END_MONTH = 12, FALL_END_DAY = 31;

let playbackIsOpen = false;
let playbackIsMinimized = false;
let playbackIsPlaying = false;
let playbackSpeedMultiplier = 1;
let playbackFadeWindowKey = "ALL";
let playbackSelectedQuickRange = "ALL_TIME";
let playbackCustomFromMs = null;
let playbackCustomToMs = null;
let playbackRangeStart = 0;
let playbackRangeEnd = 0;
let playbackTimeMs = 0;
let playbackTickerId = null;

// Real America/Anchorage zone lookup (DST-safe), not a fixed UTC-9/-8 offset -- matches
// OfflineSightingRepository.kt's anchorageDateParts/anchorageMidnightEpochMs expect/actual pair,
// which native documents as deliberately going through a real timezone-database lookup rather
// than fixed arithmetic. JS has no direct equivalent, so this derives the same real offset via
// Intl's own timezone-aware formatting (formatting a UTC instant AS Anchorage-local time, then
// diffing against the instant, yields exactly that instant's real UTC offset).
function anchorageOffsetMinutesAt(epochMsUtc) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Anchorage", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
  }).formatToParts(new Date(epochMsUtc));
  const get = (type) => Number(parts.find((p) => p.type === type).value);
  const asIfUtc = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour") % 24, get("minute"), get("second"));
  return Math.round((asIfUtc - epochMsUtc) / 60000);
}

function anchorageDateParts(epochMs) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Anchorage", year: "numeric", month: "2-digit", day: "2-digit"
  }).formatToParts(new Date(epochMs));
  const get = (type) => Number(parts.find((p) => p.type === type).value);
  return [get("year"), get("month"), get("day")];
}

function anchorageMidnightEpochMs(year, month, day) {
  const utcGuess = Date.UTC(year, month - 1, day);
  return utcGuess - anchorageOffsetMinutesAt(utcGuess) * 60000;
}

// PlaybackRange.kt's private thisSeasonRange -- backward-looking only (never a future window
// with no data in it yet): inside a window, that window from its start through now; between
// windows, whichever one ended most recently.
function thisSeasonRange(nowMs) {
  const [year] = anchorageDateParts(nowMs);
  const springStart = anchorageMidnightEpochMs(year, SPRING_START_MONTH, SPRING_START_DAY);
  const springEnd = anchorageMidnightEpochMs(year, SPRING_END_MONTH, SPRING_END_DAY) + DAY_MS - 1;
  const fallStart = anchorageMidnightEpochMs(year, FALL_START_MONTH, FALL_START_DAY);
  const fallEnd = anchorageMidnightEpochMs(year, FALL_END_MONTH, FALL_END_DAY) + DAY_MS - 1;

  if (nowMs >= springStart && nowMs <= springEnd) return [springStart, nowMs];
  if (nowMs >= fallStart && nowMs <= fallEnd) return [fallStart, nowMs];
  if (nowMs < springStart) {
    const prevFallStart = anchorageMidnightEpochMs(year - 1, FALL_START_MONTH, FALL_START_DAY);
    const prevFallEnd = anchorageMidnightEpochMs(year - 1, FALL_END_MONTH, FALL_END_DAY) + DAY_MS - 1;
    return [prevFallStart, prevFallEnd];
  }
  return [springStart, springEnd];
}

// Item 82: TODAY's own start and YESTERDAY's own end are the SAME instant (today's local
// midnight, America/Anchorage) -- pulled into one explicitly shared, named calculation rather
// than two independent-looking anchorageMidnightEpochMs(...) calls that happen to agree today but
// could silently drift into disagreeing (e.g. one accidentally becoming a rolling last-24h
// window instead of a real midnight boundary) if either call site is ever edited alone.
function anchorageTodayMidnightEpochMs(nowMs) {
  const [y, m, d] = anchorageDateParts(nowMs);
  return anchorageMidnightEpochMs(y, m, d);
}

// QuickRange.resolve's per-shortcut window (the clamp-to-data-range + never-inverted guard lives
// in recomputePlaybackRange below, matching resolve()'s own trailing coerceIn/fallback).
function resolveQuickRange(key, dataMinMs, dataMaxMs, nowMs) {
  if (key === "TODAY") {
    // Since local midnight, open end at "now" -- NOT a rolling last-24h window. See
    // anchorageTodayMidnightEpochMs above: this is the exact same boundary YESTERDAY's own end
    // uses, so the two can't silently disagree about where "today" actually starts.
    return [anchorageTodayMidnightEpochMs(nowMs), nowMs];
  }
  if (key === "YESTERDAY") {
    const todayMidnight = anchorageTodayMidnightEpochMs(nowMs);
    const [yy, ym, yd] = anchorageDateParts(todayMidnight - HALF_DAY_MS);
    return [anchorageMidnightEpochMs(yy, ym, yd), todayMidnight - 1];
  }
  if (key === "THIS_SEASON") return thisSeasonRange(nowMs);
  return [dataMinMs, dataMaxMs]; // ALL_TIME, CUSTOM (CUSTOM's own from/to override this separately)
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

// Item 83b: persists the panel's own date range/fade window/speed choices across both a close
// (see closePlaybackPanel's own comment on why it no longer resets these) and a real page reload.
// DELIBERATE DEVIATION FROM NATIVE: SightingsMapScreen's own playback state is `remember`ed --
// it's genuinely gone the instant the screen (and its Composable state) is torn down, same as any
// other `remember`. This web app's Map view is never torn down at all (a single persistent .view
// toggled hidden/visible, not a real navigation destroy/recreate), so simply NOT resetting on
// close already made these choices outlive one open/close cycle -- localStorage extends that the
// one further step to outliving a real reload too, matching "the panel is a control surface, not
// a modal" rather than inventing a native equivalent that doesn't exist.
const PLAYBACK_SETTINGS_STORAGE_KEY = "belugas_playback_settings";

function loadPlaybackSettings() {
  try {
    const raw = localStorage.getItem(PLAYBACK_SETTINGS_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (QUICK_RANGES.some((r) => r.key === parsed.quickRange)) playbackSelectedQuickRange = parsed.quickRange;
    if (typeof parsed.customFromMs === "number") playbackCustomFromMs = parsed.customFromMs;
    if (typeof parsed.customToMs === "number") playbackCustomToMs = parsed.customToMs;
    if (FADE_WINDOW_OPTIONS.some((f) => f.key === parsed.fadeWindow)) playbackFadeWindowKey = parsed.fadeWindow;
    if (PLAYBACK_SPEED_OPTIONS.includes(parsed.speed)) playbackSpeedMultiplier = parsed.speed;
  } catch (e) {
    console.warn("PLAYBACK_SETTINGS_LOAD_ERROR", e);
  }
}

function savePlaybackSettings() {
  try {
    localStorage.setItem(PLAYBACK_SETTINGS_STORAGE_KEY, JSON.stringify({
      quickRange: playbackSelectedQuickRange,
      customFromMs: playbackCustomFromMs,
      customToMs: playbackCustomToMs,
      fadeWindow: playbackFadeWindowKey,
      speed: playbackSpeedMultiplier
    }));
  } catch (e) {
    console.warn("PLAYBACK_SETTINGS_SAVE_ERROR", e);
  }
}

// Item 83b: keeps the custom from/to <input type=date> fields (and whether that row is even
// shown at all) in sync with whatever playbackSelectedQuickRange/playbackCustomFromMs/ToMs
// actually are -- called from renderDateRangeChips itself so both the chip-tap path and the
// startup/restore-from-storage path stay correct with no separate wiring needed for either.
function syncCustomDateRangeUi() {
  const isCustom = playbackSelectedQuickRange === "CUSTOM";
  document.getElementById("date-range-custom").hidden = !isCustom;
  document.getElementById("date-range-from-input").value =
    playbackCustomFromMs != null ? formatEpochMsForDateInput(playbackCustomFromMs) : "";
  document.getElementById("date-range-to-input").value =
    playbackCustomToMs != null ? formatEpochMsForDateInput(playbackCustomToMs) : "";
}

function formatEpochMsForDateInput(epochMs) {
  const [y, m, d] = anchorageDateParts(epochMs);
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

function initPlaybackPanel() {
  document.getElementById("playback-fab-btn").addEventListener("click", openPlaybackPanel);
  document.getElementById("playback-close-btn").addEventListener("click", () => navigateBack());
  document.getElementById("playback-minimize-btn").addEventListener("click", () => {
    playbackIsMinimized = !playbackIsMinimized;
    updatePlaybackPanelUi();
  });
  document.getElementById("playback-play-btn").addEventListener("click", togglePlayback);

  document.getElementById("playback-slider").addEventListener("input", (event) => {
    playbackTimeMs = playbackRangeStart + Number(event.target.value);
    stopPlaybackTicker(); // matches native: dragging the slider stops playback
    playbackIsPlaying = false;
    updatePlaybackPlayButtonUi();
    updatePlaybackTimeLabel();
    drawMapMarkers(true);
  });

  document.getElementById("date-range-from-input").addEventListener("change", (event) => {
    playbackCustomFromMs = event.target.value ? parseDateInputToStartOfDayMs(event.target.value) : null;
    savePlaybackSettings();
    onPlaybackFilterChanged();
  });
  document.getElementById("date-range-to-input").addEventListener("change", (event) => {
    playbackCustomToMs = event.target.value ? (parseDateInputToStartOfDayMs(event.target.value) + DAY_MS - 1) : null;
    savePlaybackSettings();
    onPlaybackFilterChanged();
  });

  loadPlaybackSettings(); // item 83b: restore the persisted date range/fade window/speed BEFORE the first render
  renderDateRangeChips();
  renderFadeWindowChips();
  renderPlaybackSpeedChips();
}

function parseDateInputToStartOfDayMs(dateInputValue) {
  const [y, m, d] = dateInputValue.split("-").map(Number);
  return anchorageMidnightEpochMs(y, m, d);
}

function openPlaybackPanel() {
  playbackIsOpen = true;
  playbackIsMinimized = false;
  document.getElementById("playback-panel").hidden = false;
  updatePlaybackPanelUi();
  recomputePlaybackRange();
  playbackTimeMs = playbackRangeStart; // matches native: opening the panel resets the scrub to the range start
  updatePlaybackSliderUi();
  drawMapMarkers(true);
  pushNavLayer("playback-panel", closePlaybackPanel);
}

// Item 83b BUG FIX: this used to reset every setting (quick range, custom dates, fade window,
// speed) back to defaults on close -- reasoned at the time as matching native's own `remember`ed
// state getting torn down alongside the screen itself. But this web app's Map view is never
// actually torn down (a persistent .view, just hidden/shown), so that reset had no real native
// equivalent to justify it -- it just made the panel behave like a modal dialog you configure
// fresh every time, rather than a control surface whose settings stick until deliberately
// changed. Closing now only stops playback and hides the panel; every setting is left exactly as
// chosen (and persisted to localStorage -- see loadPlaybackSettings/savePlaybackSettings above --
// so it survives a reload too, not just a close/reopen within one session). The map itself still
// reverts to showing everything unfiltered the instant the panel closes regardless (drawMapMarkers'
// own playbackIsOpen check), same as before -- only the panel's REMEMBERED settings changed here.
function closePlaybackPanel() {
  stopPlaybackTicker();
  playbackIsPlaying = false;
  playbackIsOpen = false;
  document.getElementById("playback-panel").hidden = true;
  drawMapMarkers();
}

// Item 82: the ONLY function that ever computes playbackRangeStart/End -- called from
// openPlaybackPanel (the panel's own "default on load" moment, before any chip has been tapped)
// AND onPlaybackFilterChanged (every later chip tap), both routing through the exact same
// resolveQuickRange/anchorageTodayMidnightEpochMs above either way. There is no separate
// "default" date-range calculation anywhere else in this file for TODAY/YESTERDAY to drift
// against -- confirmed by grepping the whole webapp for both quick-range keys.
function recomputePlaybackRange() {
  const timestamps = lastCombinedSightings
    .map((s) => s.observed_at_epoch_ms)
    .filter((t) => t != null && t > 0);
  const nowMs = Date.now();
  const dataMinMs = timestamps.length ? Math.min(...timestamps) : nowMs;
  const dataMaxMs = timestamps.length ? Math.max(...timestamps) : nowMs;

  let start, end;
  if (playbackSelectedQuickRange === "CUSTOM") {
    start = playbackCustomFromMs ?? dataMinMs;
    end = playbackCustomToMs ?? dataMaxMs;
  } else {
    [start, end] = resolveQuickRange(playbackSelectedQuickRange, dataMinMs, dataMaxMs, nowMs);
  }

  // BUG FIX (item 83a): the actual reported cause of "TODAY behaves like last 24h" -- clamping
  // start/end to the loaded data's own min/max is only meaningful for ALL_TIME/CUSTOM (where the
  // window is otherwise unbounded or user-typed and could extend well past any real data).
  // TODAY/YESTERDAY/THIS_SEASON already resolve to well-defined ABSOLUTE calendar boundaries that
  // must not be adjusted by what data happens to exist. Clamping them anyway (this was a faithful
  // port of native's own PlaybackRange.kt QuickRange.resolve, which has the identical
  // start.coerceIn(dataMinMs, dataMaxMs) call -- a real bug there too, not a web-only issue, see
  // CLAUDE.md's open items) silently pulled TODAY's start backward into an EARLIER window
  // whenever there was no data yet inside the requested one: with no sightings logged yet today,
  // dataMaxMs (the most recent sighting overall) lands on yesterday's data, and
  // coerceIn/clamp(todayMidnight, dataMinMs, dataMaxMs=yesterday) clamps start DOWN to
  // yesterday's own timestamp -- exactly the reported symptom. Only ALL_TIME/CUSTOM get the data
  // clamp now; calendar-boundary ranges are used exactly as resolved. An empty (zero-sighting)
  // result is the CORRECT outcome when nothing has been observed in that window yet, not
  // something to paper over by silently substituting a different window.
  if (playbackSelectedQuickRange === "ALL_TIME" || playbackSelectedQuickRange === "CUSTOM") {
    start = clamp(start, dataMinMs, dataMaxMs);
    end = clamp(end, dataMinMs, dataMaxMs);
  }
  if (end <= start) end = start + 1000;

  playbackRangeStart = start;
  playbackRangeEnd = end;
}

function isWithinPlaybackWindow(s) {
  if (s.observed_at_epoch_ms == null) return false;
  if (s.observed_at_epoch_ms > playbackTimeMs) return false;
  if (s.observed_at_epoch_ms < playbackRangeStart || s.observed_at_epoch_ms > playbackRangeEnd) return false;
  const fadeOption = FADE_WINDOW_OPTIONS.find((f) => f.key === playbackFadeWindowKey);
  if (fadeOption.ms == null) return true;
  return playbackTimeMs - s.observed_at_epoch_ms <= fadeOption.ms;
}

function onPlaybackFilterChanged() {
  recomputePlaybackRange();
  playbackTimeMs = playbackRangeEnd;
  updatePlaybackSliderUi();
  drawMapMarkers(true);
}

function updatePlaybackPanelUi() {
  document.getElementById("playback-full-controls").hidden = playbackIsMinimized;
  document.getElementById("playback-minimize-btn").textContent = playbackIsMinimized ? "⌃" : "⌄";
}

function updatePlaybackPlayButtonUi() {
  document.getElementById("playback-play-btn").textContent = playbackIsPlaying ? "⏸" : "▶";
}

function updatePlaybackTimeLabel() {
  document.getElementById("playback-time-label").textContent = playbackTimeMs
    ? new Date(playbackTimeMs).toLocaleString()
    : "";
}

// Slider fix ported from SightingsMapScreen.kt's own current (already-fixed) code: an absolute
// epoch-ms value fed straight to a Float-backed Slider collapses to ~2-minute steps once the
// range spans multiple days (Float32's 24-bit mantissa can't hold a ~41-bit absolute-epoch
// magnitude precisely). The fix offsets to rangeStart BEFORE converting, so the control only ever
// sees the SPAN being scrubbed, not the giant absolute timestamp. JS numbers are doubles (no
// Float32 precision floor at this magnitude regardless), but the relative-offset design is kept
// anyway, per spec, rather than relying on wider precision to paper over the same shape of bug.
function updatePlaybackSliderUi() {
  const slider = document.getElementById("playback-slider");
  const span = Math.max(1, playbackRangeEnd - playbackRangeStart);
  slider.max = String(span);
  slider.value = String(clamp(playbackTimeMs, playbackRangeStart, playbackRangeEnd) - playbackRangeStart);
  updatePlaybackTimeLabel();
}

function togglePlayback() {
  playbackIsPlaying = !playbackIsPlaying;
  updatePlaybackPlayButtonUi();
  if (playbackIsPlaying) {
    playbackIsMinimized = true; // matches native: the panel auto-minimizes while playing
    updatePlaybackPanelUi();
    startPlaybackTicker();
  } else {
    stopPlaybackTicker();
  }
}

// 100ms real-time tick advancing 60 simulated seconds per tick (times the speed multiplier) --
// matches SightingsMapScreen's own LaunchedEffect ticker exactly.
function startPlaybackTicker() {
  stopPlaybackTicker();
  playbackTickerId = setInterval(() => {
    playbackTimeMs += 60000 * playbackSpeedMultiplier;
    if (playbackTimeMs >= playbackRangeEnd) {
      playbackTimeMs = playbackRangeEnd;
      playbackIsPlaying = false;
      stopPlaybackTicker();
      updatePlaybackPlayButtonUi();
    }
    updatePlaybackSliderUi();
    drawMapMarkers(true);
  }, 100);
}

function stopPlaybackTicker() {
  if (playbackTickerId != null) {
    clearInterval(playbackTickerId);
    playbackTickerId = null;
  }
}

function renderDateRangeChips() {
  const container = document.getElementById("date-range-chips");
  container.innerHTML = "";
  QUICK_RANGES.forEach((r) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (playbackSelectedQuickRange === r.key ? " active" : "");
    chip.textContent = r.label;
    chip.addEventListener("click", () => {
      playbackSelectedQuickRange = r.key;
      renderDateRangeChips();
      savePlaybackSettings();
      onPlaybackFilterChanged();
    });
    container.appendChild(chip);
  });
  syncCustomDateRangeUi();
}

function renderFadeWindowChips() {
  const container = document.getElementById("fade-window-chips");
  container.innerHTML = "";
  FADE_WINDOW_OPTIONS.forEach((f) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (playbackFadeWindowKey === f.key ? " active" : "");
    chip.textContent = f.label;
    chip.addEventListener("click", () => {
      playbackFadeWindowKey = f.key;
      renderFadeWindowChips();
      savePlaybackSettings();
      drawMapMarkers(true);
    });
    container.appendChild(chip);
  });
}

function renderPlaybackSpeedChips() {
  const container = document.getElementById("playback-speed-chips");
  container.innerHTML = "";
  PLAYBACK_SPEED_OPTIONS.forEach((speed) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (playbackSpeedMultiplier === speed ? " active" : "");
    chip.textContent = `${speed}x`;
    chip.addEventListener("click", () => {
      playbackSpeedMultiplier = speed;
      renderPlaybackSpeedChips();
      savePlaybackSettings();
    });
    container.appendChild(chip);
  });
}
