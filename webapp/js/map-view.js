// Map tab: plots real whale-position pins (whale_lat/whale_lng), not the old frozen
// observer-position lat/lng -- matches the native app's post-redesign map rendering.
let mapInstance = null;
let mapMarkersLayer = null;

function initMap() {
  if (mapInstance) return;

  mapInstance = L.map("map-view", { zoomControl: true }).setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);

  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19
  }).addTo(mapInstance);

  mapMarkersLayer = L.layerGroup().addTo(mapInstance);
}

function renderSightingsOnMap(sightings) {
  if (!mapInstance) return;
  mapMarkersLayer.clearLayers();

  const withPosition = sightings.filter((s) => s.whale_lat != null && s.whale_lng != null);

  withPosition.forEach((s) => {
    const marker = L.marker([s.whale_lat, s.whale_lng]);
    marker.bindPopup(sightingPopupHtml(s));
    mapMarkersLayer.addLayer(marker);

    // Plain uncertainty circle -- same replacement for the old heading/distance sector wedge
    // the native map switched to post-redesign (SightingRecord.uncertaintyRadiusMeters' comment).
    if (s.uncertainty_radius_meters != null) {
      L.circle([s.whale_lat, s.whale_lng], {
        radius: s.uncertainty_radius_meters,
        color: "#2b6cb0",
        weight: 1,
        fillOpacity: 0.08
      }).addTo(mapMarkersLayer);
    }
  });

  if (withPosition.length > 0) {
    const bounds = L.latLngBounds(withPosition.map((s) => [s.whale_lat, s.whale_lng]));
    mapInstance.fitBounds(bounds.pad(0.2), { maxZoom: 12 });
  }
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
