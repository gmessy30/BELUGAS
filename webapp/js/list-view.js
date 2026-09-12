// List tab: mirrors App.kt's OfflineSightingsList/SightingListItem structure directly -- a
// "QUEUED FOR SYNC" section (this device's own not-yet-synced queue) above a "REMOTE DATABASE"
// section, dark cards (native: Color(0xFF1E1E1E) fill, Color(0xFF333333) 1dp border), and the
// same text hierarchy: yellow bold date/time header, a large white bold total-count line (with
// a yellow "(QUEUED)" suffix for local items), a light-gray counts breakdown, and a monospace
// coordinates footer.
let lastQueuedSightings = [];
let lastRemoteSightings = [];
let listVerifiedOnly = false;

function initListView() {
  const toggleBtn = document.getElementById("list-verified-toggle-btn");
  toggleBtn.addEventListener("click", () => {
    listVerifiedOnly = !listVerifiedOnly;
    updateListVerifiedToggleUi();
    drawSightingsList();
  });
  updateListVerifiedToggleUi();
}

function updateListVerifiedToggleUi() {
  const toggleBtn = document.getElementById("list-verified-toggle-btn");
  toggleBtn.textContent = listVerifiedOnly ? "✓ VERIFIED ONLY" : "VERIFIED ONLY";
  toggleBtn.classList.toggle("active", listVerifiedOnly);
}

function renderSightingsList(queued, remote) {
  lastQueuedSightings = queued;
  lastRemoteSightings = remote;
  drawSightingsList();
}

function drawSightingsList() {
  const container = document.getElementById("list-items");
  container.innerHTML = "";

  // "Applies only to REMOTE DATABASE... QUEUED FOR SYNC is never filtered" -- same rule as
  // OfflineSightingsList's own filteredRemoteSightings.
  const filteredRemote = listVerifiedOnly
    ? lastRemoteSightings.filter(isHighConfidence)
    : lastRemoteSightings;

  if (lastQueuedSightings.length === 0 && filteredRemote.length === 0) {
    container.innerHTML = '<p class="empty-state">No sightings yet.</p>';
    return;
  }

  if (lastQueuedSightings.length > 0) {
    container.appendChild(sectionLabel("QUEUED FOR SYNC", "queued"));
    lastQueuedSightings.forEach((s) => container.appendChild(sightingListItem(s)));
  }

  if (filteredRemote.length > 0) {
    container.appendChild(sectionLabel("REMOTE DATABASE", "remote"));
    filteredRemote.forEach((s) => container.appendChild(sightingListItem(s)));
  }
}

function sectionLabel(text, kind) {
  const el = document.createElement("div");
  el.className = `list-section-label list-section-label-${kind}`;
  el.textContent = text;
  return el;
}

function sightingListItem(s) {
  const total = (s.count_whites || 0) + (s.count_greys || 0) + (s.count_calves || 0) + (s.count_unknown || 0);

  const item = document.createElement("div");
  item.className = "sighting-item";

  if (s.photo_url) {
    const thumb = document.createElement("img");
    thumb.className = "sighting-thumb";
    thumb.src = s.photo_url;
    thumb.alt = "Sighting photo";
    thumb.loading = "lazy";
    item.appendChild(thumb);
  }

  const details = document.createElement("div");
  details.className = "sighting-details";

  const time = document.createElement("div");
  time.className = "sighting-time";
  time.textContent = s.observed_at_epoch_ms
    ? new Date(s.observed_at_epoch_ms).toLocaleString("en-US", {
        weekday: "short", month: "short", day: "numeric", year: "numeric",
        hour: "numeric", minute: "2-digit"
      })
    : "Date Not Recorded";

  const totalRow = document.createElement("div");
  totalRow.className = "sighting-total-row";
  const totalText = document.createElement("span");
  totalText.className = "sighting-total";
  totalText.textContent = `${total} Beluga${total !== 1 ? "s" : ""}`;
  totalRow.appendChild(totalText);
  if (s.is_local) {
    const queuedTag = document.createElement("span");
    queuedTag.className = "sighting-queued-tag";
    queuedTag.textContent = " (QUEUED)";
    totalRow.appendChild(queuedTag);
  }

  const breakdown = document.createElement("div");
  breakdown.className = "sighting-breakdown";
  breakdown.textContent =
    `Whites: ${s.count_whites || 0}  |  Greys: ${s.count_greys || 0}  |  ` +
    `Calves: ${s.count_calves || 0}  |  Unknown: ${s.count_unknown || 0}`;

  const footer = document.createElement("div");
  footer.className = "sighting-footer";
  footer.textContent = (s.whale_lat != null && s.whale_lng != null)
    ? `Lat: ${s.whale_lat.toFixed(4)}, Lng: ${s.whale_lng.toFixed(4)}`
    : "Location not recorded";

  details.append(time, totalRow, breakdown, footer);
  item.appendChild(details);
  return item;
}
