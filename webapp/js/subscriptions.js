// Alerts page -- ports SubscriptionsScreen.kt: three subscription kinds (zone / point+radius /
// custom polygon) writing to the same public.subscriptions table native uses, keyed by this
// browser's own subscriber_id, plus the confidence-tier filter and expiry handling exactly as
// native stores them.
//
// CORRECTION vs. the brief: SubscriptionConfidenceFilter (SupabaseClient.kt) has exactly TWO
// values -- 'all' ("All Sightings") and 'verified_only' ("Verified Observer Only") -- not the
// three-tier "all / verified-anytime / verified-in-official-windows" split described in the
// request. There is no "official windows" concept anywhere in the confidence-filter code; that
// may be conflating it with the Kenai gate-time banner, a separate feature. Ported the real
// two-value enum rather than inventing a third option that doesn't exist in the source.
//
// Region picker deliberately omitted, matching native's own SubscriptionsScreen comment: St.
// Lawrence has no seeded zone/point data yet, so a picker would just show empty lists there.
const ALERTS_REGION_ID = "cook_inlet";
const ALERTS_REGION_NAME = "Cook Inlet, Alaska";
const ALERTS_DEFAULT_CENTER = [60.5544, -151.2583];
const ALERTS_DEFAULT_ZOOM = 10;

const RADIUS_OPTIONS_METERS = [500, 1000, 2000, 5000];

const DURATION_OPTIONS = [
  { key: "permanent", label: "Permanent", durationMs: null },
  { key: "today", label: "Today", durationMs: 24 * 60 * 60 * 1000 },
  { key: "this_week", label: "This Week", durationMs: 7 * 24 * 60 * 60 * 1000 }
];

// Matches SubscriptionConfidenceFilter exactly -- see this file's header comment.
const CONFIDENCE_FILTER_OPTIONS = [
  { value: "all", label: "All Sightings" },
  { value: "verified_only", label: "Verified Observer Only" }
];

function formatRadiusMeters(meters) {
  if (meters < 1000) return `${Math.round(meters)} m`;
  const km = meters / 1000;
  const rounded = Math.round(km * 10) / 10;
  const kmText = Number.isInteger(rounded) ? String(rounded) : String(rounded);
  return `${kmText} km`;
}

let alertsSubscriberId = null;
let alertsZones = [];
let alertsPointPresets = [];
let alertsSubscriptions = [];
let alertsConfidenceOverlapIds = new Set();

let alertsSelectedKind = "zone";
let alertsSelectedConfidenceFilter = "all";
let alertsSelectedZoneSlug = null;
let alertsSelectedPresetSlug = null;
let alertsCustomPointLat = null;
let alertsCustomPointLng = null;
let alertsSelectedRadiusMeters = RADIUS_OPTIONS_METERS[0];
let alertsSelectedDurationKey = "permanent";
let alertsPolygonVertices = [];
let alertsIsSubscribing = false;

function subscribedZoneIds() {
  return new Set(
    alertsSubscriptions.filter((s) => s.is_active && s.kind === "zone").map((s) => s.zone_id)
  );
}

function initAlertsPage() {
  document.getElementById("alerts-back-btn").addEventListener("click", () => {
    navigateBack();
  });

  document.getElementById("alerts-kind-zone").addEventListener("click", () => setAlertsKind("zone"));
  document.getElementById("alerts-kind-point").addEventListener("click", () => setAlertsKind("point_radius"));
  document.getElementById("alerts-kind-polygon").addEventListener("click", () => setAlertsKind("custom_polygon"));

  document.getElementById("alerts-drop-pin-btn").addEventListener("click", openPointPicker);
  document.getElementById("alerts-draw-area-btn").addEventListener("click", openPolygonPicker);
  document.getElementById("alerts-subscribe-btn").addEventListener("click", subscribeAlerts);

  renderConfidenceChips();
  renderDurationChips();
  renderRadiusChips();
  initPointPicker();
  initPolygonPicker();
}

// Called from the main menu's "Alerts" item.
async function openAlertsPage() {
  document.getElementById("alerts-page").hidden = false;
  document.getElementById("alerts-loading").hidden = false;
  document.getElementById("alerts-content").hidden = true;
  updatePushUi();

  alertsSubscriberId = getOrCreateSubscriberId();
  const [zones, presets] = await Promise.all([
    getZones(ALERTS_REGION_ID),
    getPointPresets(ALERTS_REGION_ID)
  ]);
  alertsZones = zones;
  alertsPointPresets = presets;
  await refreshAlertsSubscriptions();

  document.getElementById("alerts-loading").hidden = true;
  document.getElementById("alerts-content").hidden = false;

  renderPointPresetChips();
  updateDropPinButton();
  updatePolygonStatus();
  updateAlertsSubscribeEnabled();
}

async function refreshAlertsSubscriptions() {
  const [subs, overlaps] = await Promise.all([
    getSubscriptions(alertsSubscriberId),
    getConfidenceFilterOverlaps(alertsSubscriberId)
  ]);
  alertsSubscriptions = subs;
  alertsConfidenceOverlapIds = overlaps;
  renderWatchingList();
  renderZoneChips();
}

function setAlertsKind(kind) {
  alertsSelectedKind = kind;
  document.getElementById("alerts-kind-zone").classList.toggle("active", kind === "zone");
  document.getElementById("alerts-kind-point").classList.toggle("active", kind === "point_radius");
  document.getElementById("alerts-kind-polygon").classList.toggle("active", kind === "custom_polygon");

  document.getElementById("alerts-zone-section").hidden = kind !== "zone";
  document.getElementById("alerts-point-section").hidden = kind !== "point_radius";
  document.getElementById("alerts-polygon-section").hidden = kind !== "custom_polygon";
  // DURATION only applies to point_radius, matching SubscriptionsScreen's own conditional.
  document.getElementById("alerts-duration-section").hidden = kind !== "point_radius";

  updateAlertsSubscribeEnabled();
}

function renderZoneChips() {
  const container = document.getElementById("alerts-zone-chips");
  container.innerHTML = "";
  if (alertsZones.length === 0) {
    container.innerHTML = `<p class="empty-state">No named zones for ${ALERTS_REGION_NAME} yet.</p>`;
    return;
  }
  const watching = subscribedZoneIds();
  alertsZones.forEach((zone) => {
    const isWatching = watching.has(zone.id);
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + ((isWatching || alertsSelectedZoneSlug === zone.slug) ? " active" : "");
    chip.textContent = isWatching ? `✓ ${zone.name}` : zone.name;
    // Already-watching zones render permanently selected and disabled -- nothing to tap means
    // nothing to insert twice, same fix SubscriptionsScreen's own comment describes.
    chip.disabled = isWatching;
    if (!isWatching) {
      chip.addEventListener("click", () => {
        alertsSelectedZoneSlug = zone.slug;
        renderZoneChips();
        updateAlertsSubscribeEnabled();
      });
    }
    container.appendChild(chip);
  });
}

function renderPointPresetChips() {
  const container = document.getElementById("alerts-point-presets");
  container.innerHTML = "";
  if (alertsPointPresets.length === 0) {
    container.innerHTML = `<p class="empty-state">No named points for ${ALERTS_REGION_NAME} yet — drop a custom pin below.</p>`;
    return;
  }
  alertsPointPresets.forEach((preset) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (alertsSelectedPresetSlug === preset.slug ? " active" : "");
    chip.textContent = preset.name;
    chip.addEventListener("click", () => {
      alertsSelectedPresetSlug = preset.slug;
      alertsSelectedRadiusMeters = preset.default_radius_meters;
      alertsCustomPointLat = null;
      alertsCustomPointLng = null;
      renderPointPresetChips();
      renderRadiusChips();
      updateDropPinButton();
      updateAlertsSubscribeEnabled();
    });
    container.appendChild(chip);
  });
}

function updateDropPinButton() {
  const btn = document.getElementById("alerts-drop-pin-btn");
  const hasCustomPin = alertsCustomPointLat != null && alertsCustomPointLng != null;
  btn.textContent = hasCustomPin ? "📍 CUSTOM PIN SET — TAP TO CHANGE" : "📍 DROP A CUSTOM PIN";
  btn.classList.toggle("set", hasCustomPin);
}

function renderRadiusChips() {
  const container = document.getElementById("alerts-radius-chips");
  container.innerHTML = "";
  RADIUS_OPTIONS_METERS.forEach((meters) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (alertsSelectedRadiusMeters === meters ? " active" : "");
    chip.textContent = formatRadiusMeters(meters);
    chip.addEventListener("click", () => {
      alertsSelectedRadiusMeters = meters;
      renderRadiusChips();
    });
    container.appendChild(chip);
  });
}

function renderConfidenceChips() {
  const container = document.getElementById("alerts-confidence-chips");
  container.innerHTML = "";
  CONFIDENCE_FILTER_OPTIONS.forEach((option) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (alertsSelectedConfidenceFilter === option.value ? " active" : "");
    chip.textContent = option.label;
    chip.addEventListener("click", () => {
      alertsSelectedConfidenceFilter = option.value;
      renderConfidenceChips();
    });
    container.appendChild(chip);
  });
}

function renderDurationChips() {
  const container = document.getElementById("alerts-duration-chips");
  container.innerHTML = "";
  DURATION_OPTIONS.forEach((option) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (alertsSelectedDurationKey === option.key ? " active" : "");
    chip.textContent = option.label;
    chip.addEventListener("click", () => {
      alertsSelectedDurationKey = option.key;
      renderDurationChips();
    });
    container.appendChild(chip);
  });
}

function renderWatchingList() {
  const container = document.getElementById("alerts-watching-list");
  container.innerHTML = "";
  if (alertsSubscriptions.length === 0) {
    container.innerHTML = '<p class="empty-state">Not watching anything yet — subscribe below to get notified about sightings.</p>';
    return;
  }

  alertsSubscriptions.forEach((sub) => {
    const confidenceLabel = CONFIDENCE_FILTER_OPTIONS.find((o) => o.value === sub.confidence_filter)?.label ?? sub.confidence_filter;
    let title;
    let subtitle;

    if (sub.kind === "zone") {
      title = alertsZones.find((z) => z.id === sub.zone_id)?.name ?? "Unknown zone";
      subtitle = confidenceLabel;
    } else if (sub.kind === "point_radius") {
      const radiusText = sub.radius_meters != null ? formatRadiusMeters(sub.radius_meters) : null;
      const expiryText = sub.expires_at ? `until ${sub.expires_at.substring(0, 10)}` : null;
      title = sub.label ?? "Custom Point";
      subtitle = [radiusText, confidenceLabel, expiryText].filter(Boolean).join(" · ");
    } else if (sub.kind === "custom_polygon") {
      title = "Custom area";
      subtitle = confidenceLabel;
    } else {
      title = sub.label ?? sub.kind;
      subtitle = confidenceLabel;
    }

    const item = document.createElement("div");
    item.className = "watching-item";

    const info = document.createElement("div");
    const titleEl = document.createElement("div");
    titleEl.className = "watching-item-title";
    titleEl.textContent = title;
    const subtitleEl = document.createElement("div");
    subtitleEl.className = "watching-item-subtitle";
    subtitleEl.textContent = subtitle;
    info.append(titleEl, subtitleEl);

    // Passive, not a warning -- most-restrictive-wins is a quiet behavior change, not something
    // wrong with this subscription. Matches SubscriptionListItem's own note exactly.
    if (alertsConfidenceOverlapIds.has(sub.id)) {
      const note = document.createElement("div");
      note.className = "watching-item-note";
      note.textContent = `Your ${title} (verified only) setting applies to overlapping zones.`;
      info.appendChild(note);
    }

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "watching-item-remove";
    removeBtn.textContent = "REMOVE";
    removeBtn.addEventListener("click", () => unsubscribeAlerts(sub.id));

    item.append(info, removeBtn);
    container.appendChild(item);
  });
}

function updateAlertsSubscribeEnabled() {
  const btn = document.getElementById("alerts-subscribe-btn");
  let enabled = !alertsIsSubscribing;
  if (alertsSelectedKind === "zone") {
    const zone = alertsZones.find((z) => z.slug === alertsSelectedZoneSlug);
    enabled = enabled && zone != null && !subscribedZoneIds().has(zone.id);
  } else if (alertsSelectedKind === "point_radius") {
    enabled = enabled && (alertsSelectedPresetSlug != null || (alertsCustomPointLat != null && alertsCustomPointLng != null));
  } else if (alertsSelectedKind === "custom_polygon") {
    enabled = enabled && alertsPolygonVertices.length >= 3;
  }
  btn.disabled = !enabled;
}

async function subscribeAlerts() {
  if (alertsIsSubscribing) return;
  const errorEl = document.getElementById("alerts-error");
  errorEl.hidden = true;

  let action;
  if (alertsSelectedKind === "zone") {
    const zone = alertsZones.find((z) => z.slug === alertsSelectedZoneSlug);
    if (!zone) return;
    action = () => createZoneSubscription(alertsSubscriberId, zone.id, alertsSelectedConfidenceFilter);
  } else if (alertsSelectedKind === "point_radius") {
    const preset = alertsPointPresets.find((p) => p.slug === alertsSelectedPresetSlug);
    const lat = preset?.lat ?? alertsCustomPointLat;
    const lng = preset?.lng ?? alertsCustomPointLng;
    if (lat == null || lng == null) return;
    const duration = DURATION_OPTIONS.find((d) => d.key === alertsSelectedDurationKey);
    const expiresAtEpochMs = duration?.durationMs != null ? Date.now() + duration.durationMs : null;
    action = async () => {
      const ok = await createPointSubscription(
        alertsSubscriberId, lat, lng, alertsSelectedRadiusMeters, alertsSelectedConfidenceFilter,
        preset?.name ?? "Custom Point", expiresAtEpochMs
      );
      return ok ? "SUCCESS" : "ERROR";
    };
  } else if (alertsSelectedKind === "custom_polygon") {
    if (alertsPolygonVertices.length < 3) return;
    action = async () => {
      const ok = await createPolygonSubscription(alertsSubscriberId, alertsPolygonVertices, alertsSelectedConfidenceFilter);
      return ok ? "SUCCESS" : "ERROR";
    };
  } else {
    return;
  }

  alertsIsSubscribing = true;
  updateAlertsSubscribeEnabled();
  const result = await action();
  alertsIsSubscribing = false;

  if (result === "SUCCESS") {
    alertsSelectedZoneSlug = null;
    alertsSelectedPresetSlug = null;
    alertsCustomPointLat = null;
    alertsCustomPointLng = null;
    alertsSelectedRadiusMeters = RADIUS_OPTIONS_METERS[0];
    alertsSelectedDurationKey = "permanent";
    alertsPolygonVertices = [];
    updateDropPinButton();
    updatePolygonStatus();
    renderRadiusChips();
    renderDurationChips();
    await refreshAlertsSubscriptions();
  } else if (result === "ALREADY_EXISTS") {
    // Shouldn't normally be reachable (the zone chips already disable an already-watched zone)
    // but a second device subscribing to the same zone at the same moment can still race past
    // that client-side check -- the partial unique index is what actually rejects it.
    errorEl.textContent = "Already watching that zone.";
    errorEl.hidden = false;
    await refreshAlertsSubscriptions();
  } else {
    errorEl.textContent = "Couldn't create that subscription. Try again.";
    errorEl.hidden = false;
  }
  updateAlertsSubscribeEnabled();
}

async function unsubscribeAlerts(id) {
  const errorEl = document.getElementById("alerts-error");
  errorEl.hidden = true;
  const ok = await deleteSubscription(id);
  if (ok) {
    await refreshAlertsSubscriptions();
  } else {
    errorEl.textContent = "Couldn't remove that subscription. Try again.";
    errorEl.hidden = false;
  }
}

// --- Point+radius picker: tap the map to drop/move a pin, pick a radius, see a live preview
// circle. Leaflet's L.circle draws true ground-meter circles directly, so unlike native (whose
// map layer's radius unit is screen dp, not meters) this needs no polygon approximation. ---
let pointPickerMap = null;
let pointPickerMarker = null;
let pointPickerCircle = null;
let pointPickerLat = null;
let pointPickerLng = null;
let pointPickerRadius = RADIUS_OPTIONS_METERS[0];

function initPointPicker() {
  document.getElementById("point-picker-cancel-btn").addEventListener("click", () => {
    navigateBack();
  });
  document.getElementById("point-picker-confirm-btn").addEventListener("click", () => {
    alertsCustomPointLat = pointPickerLat;
    alertsCustomPointLng = pointPickerLng;
    alertsSelectedRadiusMeters = pointPickerRadius;
    alertsSelectedPresetSlug = null;
    renderPointPresetChips();
    renderRadiusChips();
    updateDropPinButton();
    updateAlertsSubscribeEnabled();
    navigateBack();
  });
  renderPointPickerRadiusChips();
}

function openPointPicker() {
  pointPickerLat = alertsCustomPointLat ?? ALERTS_DEFAULT_CENTER[0];
  pointPickerLng = alertsCustomPointLng ?? ALERTS_DEFAULT_CENTER[1];
  pointPickerRadius = alertsSelectedRadiusMeters;

  document.getElementById("point-picker-overlay").hidden = false;
  pushNavLayer("point-picker", () => {
    document.getElementById("point-picker-overlay").hidden = true;
  });

  if (!pointPickerMap) {
    pointPickerMap = L.map("point-picker-map").setView([pointPickerLat, pointPickerLng], ALERTS_DEFAULT_ZOOM);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(pointPickerMap);
    pointPickerMap.on("click", (e) => {
      pointPickerLat = e.latlng.lat;
      pointPickerLng = e.latlng.lng;
      updatePointPickerOverlay();
    });
  } else {
    pointPickerMap.setView([pointPickerLat, pointPickerLng], ALERTS_DEFAULT_ZOOM);
  }
  setTimeout(() => pointPickerMap.invalidateSize(), 0);
  renderPointPickerRadiusChips();
  updatePointPickerOverlay();
}

function updatePointPickerOverlay() {
  const latlng = [pointPickerLat, pointPickerLng];
  if (pointPickerMarker) {
    pointPickerMarker.setLatLng(latlng);
  } else {
    pointPickerMarker = L.marker(latlng, { draggable: true }).addTo(pointPickerMap);
    pointPickerMarker.on("dragend", () => {
      const pos = pointPickerMarker.getLatLng();
      pointPickerLat = pos.lat;
      pointPickerLng = pos.lng;
      updatePointPickerOverlay();
    });
  }
  if (pointPickerCircle) {
    pointPickerCircle.setLatLng(latlng);
    pointPickerCircle.setRadius(pointPickerRadius);
  } else {
    pointPickerCircle = L.circle(latlng, {
      radius: pointPickerRadius,
      color: "#00E5FF",
      weight: 1.5,
      fillOpacity: 0.25
    }).addTo(pointPickerMap);
  }
}

function renderPointPickerRadiusChips() {
  const container = document.getElementById("point-picker-radius-chips");
  container.innerHTML = "";
  RADIUS_OPTIONS_METERS.forEach((meters) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (pointPickerRadius === meters ? " active" : "");
    chip.textContent = formatRadiusMeters(meters);
    chip.addEventListener("click", () => {
      pointPickerRadius = meters;
      renderPointPickerRadiusChips();
      updatePointPickerOverlay();
    });
    container.appendChild(chip);
  });
}

// --- Custom-polygon picker: each tap appends a vertex with a live outline/fill preview; Undo
// drops the last vertex, Finish requires at least 3. ---
let polygonPickerMap = null;
let polygonPickerVertices = [];
let polygonPickerLine = null;
let polygonPickerFill = null;
let polygonPickerMarkersLayer = null;

function initPolygonPicker() {
  document.getElementById("polygon-picker-cancel-btn").addEventListener("click", () => {
    navigateBack();
  });
  document.getElementById("polygon-picker-undo-btn").addEventListener("click", () => {
    polygonPickerVertices = polygonPickerVertices.slice(0, -1);
    redrawPolygonPicker();
  });
  document.getElementById("polygon-picker-finish-btn").addEventListener("click", () => {
    if (polygonPickerVertices.length < 3) return;
    alertsPolygonVertices = polygonPickerVertices;
    updatePolygonStatus();
    updateAlertsSubscribeEnabled();
    navigateBack();
  });
}

function openPolygonPicker() {
  polygonPickerVertices = [...alertsPolygonVertices];
  document.getElementById("polygon-picker-overlay").hidden = false;
  pushNavLayer("polygon-picker", () => {
    document.getElementById("polygon-picker-overlay").hidden = true;
  });

  if (!polygonPickerMap) {
    polygonPickerMap = L.map("polygon-picker-map").setView(ALERTS_DEFAULT_CENTER, ALERTS_DEFAULT_ZOOM);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(polygonPickerMap);
    polygonPickerMarkersLayer = L.layerGroup().addTo(polygonPickerMap);
    polygonPickerMap.on("click", (e) => {
      polygonPickerVertices = [...polygonPickerVertices, [e.latlng.lat, e.latlng.lng]];
      redrawPolygonPicker();
    });
  } else {
    polygonPickerMap.setView(ALERTS_DEFAULT_CENTER, ALERTS_DEFAULT_ZOOM);
  }
  setTimeout(() => polygonPickerMap.invalidateSize(), 0);
  redrawPolygonPicker();
}

function redrawPolygonPicker() {
  if (polygonPickerLine) {
    polygonPickerMap.removeLayer(polygonPickerLine);
    polygonPickerLine = null;
  }
  if (polygonPickerFill) {
    polygonPickerMap.removeLayer(polygonPickerFill);
    polygonPickerFill = null;
  }
  polygonPickerMarkersLayer.clearLayers();

  if (polygonPickerVertices.length >= 2) {
    polygonPickerLine = L.polyline(polygonPickerVertices, { color: "#00E5FF", weight: 2 }).addTo(polygonPickerMap);
  }
  if (polygonPickerVertices.length >= 3) {
    polygonPickerFill = L.polygon(polygonPickerVertices, { color: "#00E5FF", fillOpacity: 0.25, weight: 0 }).addTo(polygonPickerMap);
  }
  polygonPickerVertices.forEach((latlng) => {
    L.circleMarker(latlng, { radius: 6, color: "#000000", weight: 2, fillColor: "#FFFF00", fillOpacity: 1 }).addTo(polygonPickerMarkersLayer);
  });

  document.getElementById("polygon-picker-hint").textContent = polygonPickerVertices.length < 3
    ? "Tap the map to add points (need at least 3)"
    : `${polygonPickerVertices.length} points — tap Finish when done`;
  document.getElementById("polygon-picker-undo-btn").disabled = polygonPickerVertices.length === 0;
  document.getElementById("polygon-picker-finish-btn").disabled = polygonPickerVertices.length < 3;
}

function updatePolygonStatus() {
  const el = document.getElementById("alerts-polygon-status");
  if (alertsPolygonVertices.length >= 3) {
    el.hidden = false;
    el.textContent = `${alertsPolygonVertices.length}-point custom area selected.`;
  } else {
    el.hidden = true;
  }
  const btn = document.getElementById("alerts-draw-area-btn");
  btn.textContent = alertsPolygonVertices.length >= 3 ? "✏ REDRAW AREA" : "✏ DRAW A CUSTOM AREA";
  btn.classList.toggle("set", alertsPolygonVertices.length >= 3);
}
