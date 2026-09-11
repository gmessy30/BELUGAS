// List tab: scrollable recent-sightings list -- thumbnail, time, rough location/direction.
function renderSightingsList(sightings) {
  const container = document.getElementById("list-view");
  container.innerHTML = "";

  if (sightings.length === 0) {
    container.innerHTML = '<p class="empty-state">No sightings yet.</p>';
    return;
  }

  const list = document.createElement("div");
  list.className = "sighting-list";

  sightings.forEach((s) => {
    list.appendChild(sightingListItem(s));
  });

  container.appendChild(list);
}

function sightingListItem(s) {
  const item = document.createElement("div");
  item.className = "sighting-item";

  const thumb = document.createElement("div");
  thumb.className = "sighting-thumb";
  if (s.photo_url) {
    const img = document.createElement("img");
    img.src = s.photo_url;
    img.alt = "Sighting photo";
    img.loading = "lazy";
    thumb.appendChild(img);
  } else {
    thumb.textContent = "🐋";
  }

  const details = document.createElement("div");
  details.className = "sighting-details";

  const time = document.createElement("div");
  time.className = "sighting-time";
  time.textContent = s.observed_at_epoch_ms ? new Date(s.observed_at_epoch_ms).toLocaleString() : "Unknown time";

  const meta = document.createElement("div");
  meta.className = "sighting-meta";
  meta.textContent = formatCounts(s);

  const location = document.createElement("div");
  location.className = "sighting-location";
  location.textContent = formatRoughLocation(s);

  details.append(time, meta, location);
  item.append(thumb, details);
  return item;
}

function formatRoughLocation(s) {
  if (s.whale_lat != null && s.whale_lng != null) {
    const coords = `${s.whale_lat.toFixed(3)}, ${s.whale_lng.toFixed(3)}`;
    const bearing = s.travel_bearing_degrees != null
      ? ` · heading ${Math.round(s.travel_bearing_degrees)}°`
      : "";
    return coords + bearing;
  }
  return "Location not recorded";
}
