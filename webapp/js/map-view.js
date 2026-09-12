// Map tab: plots real whale-position pins (whale_lat/whale_lng), not the old frozen
// observer-position lat/lng -- matches the native app's post-redesign map rendering.
//
// Marker/label styling below is pulled directly from SightingsMapScreen.kt's actual MapLibre
// layers, not invented for the web: a plain circular dot (native: CircleLayer radius 10dp,
// Color.Yellow fill, black 2dp stroke) with a permanent text caption below it (native:
// SymbolLayer, black text/white halo, "{N} Belugas · {date}"), gray instead of yellow for this
// device's own not-yet-synced queue (native: "Local" source -> Color(0xFF9E9E9E)), and the same
// cyan (0xFF00E5FF) uncertainty circle at the same 18% fill / stroke treatment. Native's cluster
// badges (a separate orange circle + count layer once markers overlap) are NOT reproduced here --
// that needs a clustering library (e.g. Leaflet.markercluster) this MVP doesn't pull in; plain
// overlapping dots are shown instead, noted as a known gap rather than forced.
let mapInstance = null;
let mapMarkersLayer = null;
let lastCombinedSightings = [];
let mapVerifiedOnly = false;

function initMap() {
  if (mapInstance) return;

  mapInstance = L.map("map-view", { zoomControl: true }).setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);

  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19
  }).addTo(mapInstance);

  mapMarkersLayer = L.layerGroup().addTo(mapInstance);

  const toggleBtn = document.getElementById("map-verified-toggle-btn");
  toggleBtn.addEventListener("click", () => {
    mapVerifiedOnly = !mapVerifiedOnly;
    updateMapVerifiedToggleUi();
    drawMapMarkers();
  });
  updateMapVerifiedToggleUi();
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

// Native's own dot color (Color.Yellow), not a generic map-pin yellow -- kept as a named
// constant since both the marker fill and its border/label styling below need to agree with it.
const SIGHTING_DOT_COLOR = "#FFFF00";
const SIGHTING_DOT_LOCAL_COLOR = "#9E9E9E";

function drawMapMarkers() {
  if (!mapInstance) return;
  mapMarkersLayer.clearLayers();

  const visible = lastCombinedSightings.filter((s) => {
    if (s.whale_lat == null || s.whale_lng == null) return false;
    if (s.is_local) return true; // never filtered by VERIFIED ONLY -- see isHighConfidence's own comment
    return !mapVerifiedOnly || isHighConfidence(s);
  });

  visible.forEach((s) => {
    const marker = L.marker([s.whale_lat, s.whale_lng], { icon: sightingDivIcon(s.is_local) });
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
    if (s.uncertainty_radius_meters != null) {
      L.circle([s.whale_lat, s.whale_lng], {
        radius: s.uncertainty_radius_meters,
        color: "#00E5FF",
        weight: 1.5,
        fillOpacity: 0.18,
        opacity: 1
      }).addTo(mapMarkersLayer);
    }
  });

  if (visible.length > 0) {
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
  const photo = s.photo_url
    ? `<img src="${escapeHtml(s.photo_url)}" alt="Sighting photo" style="width:100%;border-radius:6px;margin-top:6px;">`
    : "";
  return `<div class="popup"><strong>${time}</strong><br>${counts}${photo}</div>`;
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
