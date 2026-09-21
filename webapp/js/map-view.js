// Map tab: plots real whale-position pins (whale_lat/whale_lng), not the old frozen
// observer-position lat/lng -- matches the native app's post-redesign map rendering.
//
// Marker/label styling below is pulled directly from SightingsMapScreen.kt's actual MapLibre
// layers, not invented for the web: a plain circular dot (native: CircleLayer radius 10dp,
// Color.Yellow fill, black 2dp stroke) with a permanent text caption below it (native:
// SymbolLayer, black text/white halo, "{N} Belugas · {date}"), gray instead of yellow for this
// device's own not-yet-synced queue (native: "Local" source -> Color(0xFF9E9E9E)). Clustering
// (below) uses Leaflet.markercluster (native: GeoJsonOptions(cluster = true)) with a matching
// solid-orange (0xFFFF6D00), black-stroke badge.
//
// Item 103: no uncertainty circle around the pin anymore -- removed entirely (rendering code,
// not just data) now that position is placed exactly by the observer rather than estimated.
// uncertainty_radius_meters/uncertainty_bucket are still fetched (db.js's column list) and still
// exist on old rows, just never read here going forward.
let mapInstance = null;
let mapShadingLayer = null;
let mapTravelStubsLayer = null;
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
  const el = document.getElementById("map-debug-overlay-text");
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
  // Item 84: the one tappable part of this overlay (pointer-events:auto, style.css) -- there'd
  // otherwise be no way to dismiss it short of stripping ?debug=1 from the URL and reloading.
  document.getElementById("map-debug-overlay-hide-btn").addEventListener("click", () => {
    el.hidden = true;
  });
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
  // matching SightingsMapScreen's own draw order (shading, then travel-direction stubs, then pins).
  mapShadingLayer = L.layerGroup().addTo(mapInstance);

  // Item 104: its own non-clustered layer, NOT mapMarkersLayer -- same reason item 73's
  // uncertainty circles got their own layer (mapUncertaintyLayer, since removed by item 103):
  // L.markerClusterGroup counts any addLayer'd child, marker or not, so a stub with no icon of
  // its own would silently inflate cluster badge counts and get a spiderfy "leg" with nothing at
  // the end of it.
  mapTravelStubsLayer = L.layerGroup().addTo(mapInstance);

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
  // Item 86: date range now governs the map's default (non-playback) view too, not just the
  // playback panel's own internal state while open -- recomputed here so the persisted quick
  // range (e.g. TODAY) is resolved fresh against whatever data/now actually are, even if the
  // panel has never been opened this session (playbackRangeStart/End otherwise stay at their
  // unset 0/0 initial values, which would filter out every real sighting).
  recomputePlaybackRange();
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
  mapTravelStubsLayer.clearLayers();

  // BUG FIX (item 86): date range and fade window used to be entangled behind one
  // `playbackIsOpen` gate -- closing the panel dropped ALL filtering (showing literally
  // everything, ignoring the selected date range), while an OPEN-but-idle panel still applied
  // the fade window/cursor left over from the last time playback ran, so the same date range
  // could show different sightings depending on unrelated leftover playback state. Now: the date
  // range applies UNCONDITIONALLY (it's the one setting that governs the static view, open or
  // closed, per item 83b); the fade window/cursor is a playback-only EFFECT layered on top, only
  // while actually playing or the slider is being actively dragged.
  const isPlaybackCursorActive = playbackIsPlaying || playbackScrubbing;

  const visible = lastCombinedSightings.filter((s) => {
    if (s.whale_lat == null || s.whale_lng == null) return false;
    if (!isWithinDateRange(s)) return false;
    if (isPlaybackCursorActive && !isWithinPlaybackFadeWindow(s)) return false;
    if (s.is_local) return true; // never filtered by VERIFIED ONLY -- see isVerifiedSighting's own comment
    return !mapVerifiedOnly || isVerifiedSighting(s);
  });

  visible.forEach((s) => {
    const marker = L.marker([s.whale_lat, s.whale_lng], { icon: sightingDivIcon(s.is_local) });
    marker.sightingData = s; // read back by the cluster-click handler's same-point check above
    marker.bindPopup(sightingPopupHtml(s));
    // Item 63: the popup's own CONFIRM SIGHTING button (if present -- see confirmSightingButtonHtml)
    // is injected as part of that same HTML string, so it needs wiring up fresh every time the
    // popup actually opens (Leaflet re-parses the string into DOM each time, there's no persistent
    // element to attach a listener to ahead of time).
    marker.on("popupopen", (event) => {
      wireConfirmSightingButton(event.popup.getElement(), s);
      wireEditSightingButton(event.popup.getElement(), s);
      wirePopupPhoto(event.popup.getElement(), s);
    });
    marker.bindTooltip(sightingCaptionText(s), {
      permanent: true,
      direction: "bottom",
      offset: [0, 10],
      className: "sighting-label"
    });
    mapMarkersLayer.addLayer(marker);

    // Item 104: plain travel-direction line stub -- this never actually rendered on the PWA
    // before now (checked: even before item 103, travel_bearing_degrees only ever fed
    // formatTravelDirection's popup TEXT, never a drawn line), so this is a straight port of
    // SightingsMapScreen's existing "sighting-travel-stubs" LineLayer, not a regression fix.
    // Item 104 follow-up: drawn at the TRUE recorded bearing, not snapped to the nearest of 8
    // compass points -- the BearingDial captures 16 points and the DB stores the exact value, so
    // snapping the drawn line to 8 points threw away real precision the record actually has (a
    // recorded 112.5° drew as 135°). Snapping stays for formatTravelDirection's own popup TEXT
    // label only, where a plain compass-point name reads better than a raw decimal degree.
    // Fixed 40m length/no arrowhead unchanged from buildTravelStubGeoJsonFeature's own choices;
    // destinationPoint below is the same spherical-earth forward-geodesic formula as
    // HeadingDistance.kt's own destinationPoint.
    if (s.travel_bearing_degrees != null) {
      const stubEnd = destinationPoint(s.whale_lat, s.whale_lng, s.travel_bearing_degrees, TRAVEL_STUB_LENGTH_METERS);
      L.polyline([[s.whale_lat, s.whale_lng], stubEnd], {
        color: "#FFFFFF",
        weight: 2
      }).addTo(mapTravelStubsLayer);
    }
  });

  if (!skipFitBounds && visible.length > 0) {
    const bounds = L.latLngBounds(visible.map((s) => [s.whale_lat, s.whale_lng]));
    mapInstance.fitBounds(bounds.pad(0.2), { maxZoom: 12 });
  }
}

// Item 115b: matches the manual/edit flows' own close-in zoom (GPS_CENTER_ZOOM/EDIT_MAP_ZOOM,
// submit-view.js -- both 14, ~1-2km visible) rather than inventing a third "close enough" value.
const SIGHTING_FOCUS_ZOOM = 14;

/**
 * Item 115b: jump to one specific sighting on the Sightings Map -- called from a list row's
 * "Show on Map" button (list-view.js). Switches to the Map tab if we aren't already there,
 * centers on the sighting, and opens its popup.
 *
 * The awkward part is that the map has TWO filters of its own that the list doesn't share
 * (VERIFIED ONLY, which is a separate flag from the list's own; and the item-86/113 date range),
 * so a row perfectly visible in the list can have no marker on the map at all. Landing the user
 * on a map with no pin and no explanation is exactly the dead end CLAUDE.md's item-106 note
 * warns about (an affordance that can only ever refuse), so whichever filter is actually hiding
 * THIS row is cleared first. That's a visible change, not a silent one: the VERIFIED ONLY toggle
 * and the item-113 date-range pill both sit on the map's own top controls and both re-render
 * here, so the user can see what was relaxed and put it back.
 */
function focusSightingOnMap(s) {
  if (s.whale_lat == null || s.whale_lng == null) return;

  let needsRedraw = false;

  // is_local rows are never subject to VERIFIED ONLY on the map (see drawMapMarkers), so only a
  // remote unverified row can be hidden by it.
  if (mapVerifiedOnly && !s.is_local && !isVerifiedSighting(s)) {
    mapVerifiedOnly = false;
    updateMapVerifiedToggleUi();
    needsRedraw = true;
  }

  if (!isWithinDateRange(s)) {
    // Same four steps the date-range chips' own click handler runs (renderDateRangeChips), so the
    // chip row, the persisted setting, the pill and the markers can't disagree about the range.
    playbackSelectedQuickRange = "ALL_TIME";
    renderDateRangeChips();
    savePlaybackSettings();
    onPlaybackFilterChanged(); // recomputes the range, updates the pill, and redraws
    needsRedraw = false; // onPlaybackFilterChanged already redrew
  }

  if (needsRedraw) drawMapMarkers(true);

  const previousTab = getActiveTabName();
  if (previousTab !== "map") {
    switchTab("map");
    // Same nav-stack shape as the presence banner's own tap-to-map jump (presence-banner.js), so
    // a back gesture returns to the list rather than leaving the user on the map.
    pushNavLayer("tab:map", () => switchTab(previousTab));
  }

  // Deferred a frame: the map was display:none until switchTab above, and Leaflet can only
  // resolve a setView against a container that has real dimensions. Nothing here pushes a nav
  // layer, so deferring is safe (contrast item 107, which is specifically about a layer opened
  // from a closing layer's handler).
  requestAnimationFrame(() => {
    invalidateMapSize();
    // animate:false on purpose -- zoomToShowLayer below runs immediately after and would race a
    // still-running pan/zoom animation.
    mapInstance.setView([s.whale_lat, s.whale_lng], SIGHTING_FOCUS_ZOOM, { animate: false });

    const marker = findMapMarkerForSighting(s);
    if (!marker) return; // centered anyway; nothing to open

    // markercluster's own helper: if this marker is still inside a cluster at this zoom (the
    // same-exact-coordinates case, which no amount of zooming separates), it spiderfies to
    // expose it and only then runs the callback.
    mapMarkersLayer.zoomToShowLayer(marker, () => marker.openPopup());
  });
}

// Both remote rows and queued local ones carry a real `id` (offline-queue.js's own
// getQueuedSightingsAsRecords sets it), so this one key identifies either kind.
function findMapMarkerForSighting(s) {
  let found = null;
  mapMarkersLayer.eachLayer((layer) => {
    if (layer.sightingData && layer.sightingData.id === s.id) found = layer;
  });
  return found;
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
  // Item 90: formatActivitiesSummary (submit-view.js) is shared with the confirm modal/list item
  // so all three can never describe the same sighting's activities differently.
  const activitiesText = formatActivitiesSummary(s.activities, s.activity_note);
  const activities = activitiesText ? `<br>${escapeHtml(activitiesText)}` : "";
  // Item 115a: the popup-photo class is what wirePopupPhoto finds on popupopen to make this
  // tappable -- the inline style stays as-is (it predates that and still sizes the image).
  const photo = s.photo_url
    ? `<img class="popup-photo" src="${escapeHtml(s.photo_url)}" alt="Sighting photo" style="width:100%;border-radius:6px;margin-top:6px;">`
    : "";
  return `<div class="popup"><strong>${time}</strong>${editedMarkHtml(s)}<br>${counts}<br>${direction}${activities}${photo}${confirmSightingButtonHtml(s)}${editSightingButtonHtml(s)}</div>`;
}

// Item 106: a small "· edited" mark on any row whose edited_at is set -- the record still says
// what it says, but a reader can see it was corrected after the fact rather than reported that
// way. Empty string (not a placeholder) on an unedited row, and on any database that predates
// edited_at entirely (the column simply isn't in the fetched row there -- see
// downgradeSightingColumnsOnce, db.js). Shared by the map popup and the list item.
function editedMarkHtml(s) {
  return s.edited_at ? ' <span class="sighting-edited-mark">· edited</span>' : "";
}

// Item 106: EDIT is offered on exactly one row -- whichever one get_my_editable_sighting named as
// this device's own most recent, still inside the edit window (cachedEditableSightingId, db.js).
// Never on a locally-queued row (no DB row to edit yet; it can still be edited the moment it
// syncs and the next refresh names it). Once the window closes or a newer sighting exists, the
// next refresh simply stops naming it and the button disappears -- no dead end, no error state to
// render. edit_my_last_sighting re-checks all of this server-side regardless.
function editSightingButtonHtml(s) {
  if (s.is_local || !isEditableSighting(s)) return "";
  return `<button type="button" class="edit-sighting-btn" data-sighting-id="${escapeHtml(s.id)}">Edit</button>`;
}

// Item 106: the server's answer (cachedEditableSightingId) AND the deadline it came with. The
// server filters the window itself, so the deadline check is belt-and-braces for one specific
// case it can't cover: an app left open past the boundary, whose cache is only refreshed on the
// next sightings refresh. Without it the button would linger until then and fail on save -- the
// dead end this feature is meant not to have. Shared by the map popup and the list item.
function isEditableSighting(s) {
  if (!cachedEditableSightingId || s.id !== cachedEditableSightingId) return false;
  if (cachedEditableSightingUntilMs && Date.now() >= cachedEditableSightingUntilMs) return false;
  return true;
}

// Item 63: null/empty for a locally-queued (not yet synced) sighting -- it has no real DB id yet,
// nothing to confirm. Otherwise: an already-confirmed row shows a plain badge (nothing to tap,
// visibility-only -- confirm_sighting itself would refuse a second confirmation regardless);
// everything else gets the button, gated on cachedIsTierOneObserver (db.js) -- this device not
// being tier-1 hides it outright rather than showing a button that would just fail server-side.
// "Is this MY OWN row" is NOT checked here at all: this app has no reliable client-side way to
// know that for an arbitrary fetched sighting (subscriber_id is never anon-readable), so that
// refusal reason is left entirely to confirm_sighting itself, same "visibility is UX, enforcement
// is server-side" split as the departure-report button.
// Item 105: a row that's already verified (isVerifiedSighting, db.js) gets a badge instead of the
// button -- a tier-1/2 observer's own report has nothing left to elevate, so CONFIRM SIGHTING is
// hidden there too, not just on already-confirmed rows. Visibility is still tier-1-viewer-only,
// unchanged from item 63.
function confirmSightingButtonHtml(s) {
  if (s.is_local || !cachedIsTierOneObserver) return "";
  if (isVerifiedSighting(s)) return `<div class="confirmed-sighting-badge">${verifiedBadgeLabel(s)}</div>`;
  return `<button type="button" class="confirm-sighting-btn" data-sighting-id="${escapeHtml(s.id)}">Confirm Sighting</button>`;
}

// Item 105: "✓ Confirmed" when a tier-1 observer actually vouched for it (confirmed_at set, the
// more specific fact), otherwise "✓ Verified" for a tier-1/2 observer's own report. Only
// meaningful for a row isVerifiedSighting already accepted. Shared by map popup and list item.
function verifiedBadgeLabel(s) {
  return s.confirmed_at ? "✓ Confirmed" : "✓ Verified";
}

// Item 63: shared by the map popup (called on Leaflet's own popupopen, since the popup's HTML is
// re-parsed into fresh DOM every time it opens) and the list item (called once, right after the
// button is actually appended to a real, persistent DOM element) -- one click-handling/two-step-
// guard implementation for both surfaces.
function wireConfirmSightingButton(container, s) {
  if (!container) return;
  const btn = container.querySelector(".confirm-sighting-btn");
  if (!btn) return;
  btn.addEventListener("click", () => handleConfirmSightingClick(s, btn));
}

// Item 106: same popupopen-time wiring as the confirm button right above, and for the identical
// reason (Leaflet re-parses the popup's HTML string into fresh DOM every time it opens).
function wireEditSightingButton(container, s) {
  if (!container) return;
  const btn = container.querySelector(".edit-sighting-btn");
  if (!btn) return;
  btn.addEventListener("click", () => {
    // Closing the popup first: the edit flow takes over the whole screen (the submit tab's own
    // manual-log-step), and a popup left open underneath would still be there on return.
    mapInstance.closePopup();
    openEditSightingFlow(s);
  });
}

// Item 115a: same popupopen-time wiring as the two buttons above, and for the identical reason
// (Leaflet re-parses the popup's HTML string into fresh DOM every time it opens, so there is no
// persistent element to bind to ahead of time).
function wirePopupPhoto(container, s) {
  if (!container || !s.photo_url) return;
  const img = container.querySelector(".popup-photo");
  if (!img) return;
  img.addEventListener("click", () => openPhotoLightbox(s.photo_url));
}

async function handleConfirmSightingClick(sighting, btn) {
  // Two-step guard (item 63) -- same plain confirm() gate as the departure-report button
  // (tier-code.js's submitDepartureReport), for the same reason: a stray tap shouldn't be able to
  // fire an action that can't be undone.
  if (!confirm("Confirm this sighting? This can't be undone.")) return;

  btn.disabled = true;
  btn.textContent = "Confirming…";
  const ok = await confirmSighting(sighting.id, getOrCreateSubscriberId());
  if (ok) {
    // Refetches rather than just flipping a local flag -- picks up the real confirmed_at (and
    // keeps the List tab's own copy of this same row in sync too), same reasoning
    // proceedManualSubmit's own post-submit refreshSightings() call already uses.
    await refreshSightings();
  } else {
    btn.disabled = false;
    btn.textContent = "Confirm Sighting";
    alert("Couldn't confirm this sighting -- it may already be confirmed, or it may be your own report.");
  }
}

// Same 8-point display-only snapping SightingsMapScreen's own travel-bearing stub rendering
// uses (HeadingDistance.kt's snapToNearestCompass8Degrees) -- the stored value keeps its full
// precision, only the label/stub direction shown here is snapped. Shared by formatTravelDirection
// (the popup text) and the map's own line-stub drawing (item 104) so the two can never disagree
// about which of the 8 points a given bearing rounds to.
function snapToNearestCompass8Degrees(bearingDegrees) {
  const snapped = Math.round(bearingDegrees / 45) * 45;
  return ((snapped % 360) + 360) % 360;
}

const COMPASS_POINT_LABELS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
function formatTravelDirection(bearingDegrees) {
  if (bearingDegrees == null) return "Direction: unknown";
  const index = snapToNearestCompass8Degrees(bearingDegrees) / 45;
  return `Direction: ${COMPASS_POINT_LABELS[index]}`;
}

// Item 104: ported from HeadingDistance.kt's own destinationPoint -- identical spherical-earth
// forward-geodesic formula, used here only to draw the travel-direction line stub (never for
// whale-position placement, which item 60 removed entirely in favor of human map placement).
const TRAVEL_STUB_LENGTH_METERS = 40;
function destinationPoint(lat, lng, bearingDegrees, distanceMeters) {
  const earthRadiusMeters = 6371000;
  const angularDistance = distanceMeters / earthRadiusMeters;
  const bearingRad = bearingDegrees * Math.PI / 180;
  const lat1 = lat * Math.PI / 180;
  const lng1 = lng * Math.PI / 180;

  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(angularDistance) + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearingRad)
  );
  const lng2 = lng1 + Math.atan2(
    Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(lat1),
    Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
  );

  return [lat2 * 180 / Math.PI, lng2 * 180 / Math.PI];
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
// Item 86: true only WHILE the slider is actively being dragged (set on the slider's own
// "input" event, cleared on "change" -- see initPlaybackPanel) -- distinct from playbackIsOpen
// (the panel being visible at all) and from playbackIsPlaying (the ticker actually running).
// isWithinPlaybackFadeWindow/drawMapMarkers only apply the fade-window+cursor effect while
// playbackIsPlaying || playbackScrubbing; the date-range filter applies unconditionally instead.
let playbackScrubbing = false;
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
    // Item 113 DELIBERATE EXCEPTION to item 83b's own persistence: the QUICK RANGE (and, with it,
    // the custom from/to dates) is NOT restored on a fresh load -- it always starts at ALL_TIME.
    // The range filters the map's DEFAULT view, not just the panel (isWithinDateRange ignores
    // playbackIsOpen), so a persisted TODAY meant a returning visitor opened the app to a map
    // that had silently dropped every earlier sighting. Fade window and speed ARE still restored:
    // both are playback-session presentation only and hide nothing when the panel is closed.
    // They're still WRITTEN by savePlaybackSettings (a mid-session close/reopen keeps the chosen
    // range -- see closePlaybackPanel's own comment); only the reload path ignores them.
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
  // Item 113: the range label reads as one control with the FAB beside it, so it opens the same
  // panel. role=button/tabindex=0 in the markup make it reachable without a pointer; a <span>
  // gets no implicit Enter/Space activation, hence the explicit keydown.
  const rangeLabel = document.getElementById("playback-range-label");
  rangeLabel.addEventListener("click", openPlaybackPanel);
  rangeLabel.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openPlaybackPanel();
    }
  });
  document.getElementById("playback-close-btn").addEventListener("click", () => navigateBack());
  document.getElementById("playback-minimize-btn").addEventListener("click", () => {
    playbackIsMinimized = !playbackIsMinimized;
    updatePlaybackPanelUi();
  });
  document.getElementById("playback-play-btn").addEventListener("click", togglePlayback);

  document.getElementById("playback-slider").addEventListener("input", (event) => {
    playbackScrubbing = true; // item 86: the fade/cursor effect is active for as long as this stays true
    playbackTimeMs = playbackRangeStart + Number(event.target.value);
    stopPlaybackTicker(); // matches native: dragging the slider stops playback
    playbackIsPlaying = false;
    updatePlaybackPlayButtonUi();
    updatePlaybackTimeLabel();
    drawMapMarkers(true);
  });
  // Item 86: fires once when the drag/touch actually ends (unlike "input", which fires
  // continuously mid-drag) -- this is what turns playbackScrubbing back off, so fade/cursor
  // filtering stops the instant the user lets go, same as it stops when playback itself pauses.
  document.getElementById("playback-slider").addEventListener("change", () => {
    playbackScrubbing = false;
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

  loadPlaybackSettings(); // item 83b/113: restore the persisted fade window/speed BEFORE the first render
  // Item 113: render the label immediately, not just once sightings arrive -- recomputePlaybackRange
  // (its other caller) doesn't run until renderSightingsOnMap, and the pill must never be blank.
  updatePlaybackRangeLabel();
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
  playbackScrubbing = false; // item 86: closing counts as a stop even mid-drag
  playbackIsOpen = false;
  document.getElementById("playback-panel").hidden = true;
  // Item 86: "the panel closes" is one of the two explicit cursor-reset triggers (the other is
  // playback stopping, see togglePlayback) -- the map itself no longer depends on this for what
  // it shows (isWithinDateRange applies regardless of playbackIsOpen now), but resetting it here
  // keeps playbackTimeMs from silently carrying a stale mid-scrub position into next time the
  // panel reopens.
  playbackTimeMs = playbackRangeEnd;
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
  // Item 113: the label is refreshed from HERE and nowhere else, so it can never describe a
  // different window than the one actually being filtered on -- this is the single function that
  // ever assigns playbackRangeStart/End (see this function's own header comment above).
  updatePlaybackRangeLabel();
}

// Item 113: short, always-visible summary of the active date range, shown next to the playback FAB
// whether the panel is open or closed. Non-CUSTOM ranges reuse the chip's OWN label text
// (QUICK_RANGES), so the pill and the chip can't word the same range differently; CUSTOM is
// formatted from the resolved start/end instead, since there's no fixed wording for it.
const MONTH_ABBREVIATIONS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                             "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

function playbackRangeLabelText() {
  if (playbackSelectedQuickRange !== "CUSTOM") {
    const match = QUICK_RANGES.find((r) => r.key === playbackSelectedQuickRange);
    return match ? match.label : "ALL TIME";
  }
  // Deliberately formats the RESOLVED range (playbackRangeStart/End), not the raw typed
  // from/to: CUSTOM is still clamped to the loaded data's own min/max (see recomputePlaybackRange),
  // so a "to" of OCT 3 against data ending SEP 19 genuinely filters to SEP 19. The pill reports
  // the window actually in effect rather than the one requested -- confirmed in a real browser.
  const [y1, m1, d1] = anchorageDateParts(playbackRangeStart);
  const [y2, m2, d2] = anchorageDateParts(playbackRangeEnd);
  const from = `${MONTH_ABBREVIATIONS[m1 - 1]} ${d1}`;
  if (y1 === y2 && m1 === m2 && d1 === d2) return from;
  // Same month: the month name is only worth printing once ("SEP 12-19"); across a month or year
  // boundary it isn't, so both sides get spelled out in full.
  if (y1 === y2 && m1 === m2) return `${from}–${d2}`;
  const to = `${MONTH_ABBREVIATIONS[m2 - 1]} ${d2}`;
  if (y1 === y2) return `${from} – ${to}`;
  return `${from} ${y1} – ${to} ${y2}`;
}

function updatePlaybackRangeLabel() {
  const el = document.getElementById("playback-range-label");
  if (!el) return;
  el.textContent = playbackRangeLabelText();
  // "filtered" = anything other than the unfiltered default, which is what the amber treatment is
  // actually warning about (style.css) -- not merely "a range is selected", since ALL TIME is one.
  el.classList.toggle("filtered", playbackSelectedQuickRange !== "ALL_TIME");
}

// Item 86: the persisted date-range filter -- applies whether or not the playback panel is even
// open, let alone playing. This is what makes "TODAY" (or any other quick range) an actual
// property of the map's default view, not just a playback-session detail.
function isWithinDateRange(s) {
  if (s.observed_at_epoch_ms == null) return false;
  return s.observed_at_epoch_ms >= playbackRangeStart && s.observed_at_epoch_ms <= playbackRangeEnd;
}

// Item 86: the playback-only fade effect -- only ever consulted while drawMapMarkers' own
// isPlaybackCursorActive is true (playing or actively scrubbing). Deliberately does NOT check
// the date range itself (isWithinDateRange already does, applied separately in drawMapMarkers) --
// this only judges a sighting's position relative to the live scrub cursor/fade window.
function isWithinPlaybackFadeWindow(s) {
  if (s.observed_at_epoch_ms > playbackTimeMs) return false;
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
    // Item 86: pausing is a STOP, not a pause-in-place -- the cursor resets to the end of the
    // range ("now") and the fade effect clears (drawMapMarkers' own isPlaybackCursorActive check
    // goes false the instant playbackIsPlaying does), same as reaching the end of the range
    // naturally already did in startPlaybackTicker below.
    playbackTimeMs = playbackRangeEnd;
    updatePlaybackSliderUi();
    drawMapMarkers(true);
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
