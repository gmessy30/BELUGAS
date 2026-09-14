// Item 97: standalone presence status page logic. See index.html's own header comment for why
// this talks to PostgREST directly instead of loading supabase-js.
//
// Item 97c: zone-aware via ?zone=<slug> (default "kenai"). Two parallel paths:
//   - KENAI (currentZoneSlug === "kenai"): the original item 97/97b design, unchanged --
//     get_kenai_presence_state's own rich tide-gate state, kenaiGateTimeLabel's text, and (RED
//     only) get_kenai_red_qualifying_sightings for the map, fit to the sightings + the real river
//     mouth (geofence.js's KENAI_RIVER_CENTERLINE[0]), with river-relative "heading upriver/
//     downriver" direction text and the kenai-landmarks.js lookup.
//   - EVERY OTHER ZONE: get_watched_zone_statuses (the SAME RPC the main app's map/banner use for
//     every non-Kenai zone -- see presence-banner.js's rebuildPresenceBannerCards) drives status
//     via computeBelugaPresenceStatus + effectivePresenceStatus (both presence.js, reused
//     verbatim -- see that composition's own comment there: "the generic-zone (non-Kenai)
//     staleness escalation used by the BANNER only"), with plain RED/YELLOW/BLUE/UNKNOWN text (no
//     tide-gate wording -- that's Kenai-specific machinery with nothing to generalize). RED map
//     data comes from export_sightings (already anon+authenticated-granted, zone_slug-filtered),
//     with the SAME tier/verification filter get_watched_zone_statuses' own
//     last_verified_sighting_epoch_ms uses applied client-side (export_sightings itself has no
//     tier filter). The map fits the zone's own real polygon (geofence.js's WELL_SOURCED_ZONES,
//     when that zone has one) plus the sightings, falling back to sightings-only bounds
//     otherwise. Direction text uses a plain compass point (no river to be relative to); the
//     landmark lookup is Kenai-only, so it's simply never shown for other zones.
// A zone slug that isn't "kenai" and isn't found among the fetched banner-watched rows renders an
// explicit "unknown zone" state -- never silently falls back to Kenai's data.

const urlParams = new URLSearchParams(window.location.search);
const currentZoneSlug = urlParams.get("zone") || "kenai";
const isKenaiZone = currentZoneSlug === "kenai";

// Spec: "refreshes every 5 minutes while open."
const STATUS_REFETCH_INTERVAL_MS = 5 * 60 * 1000;

let hasEverFetched = false;
let lastFetchFailed = false;
let zoneNotFound = false; // non-kenai only: fetched successfully, but currentZoneSlug wasn't among the banner-watched rows

// Kenai path
let lastSnapshot = null; // { detail, fetchedAtMs } -- get_kenai_presence_state's own row

// Generic-zone path
let currentZoneStatusRow = null; // { zone_id, zone_slug, zone_name, last_verified_sighting_epoch_ms, last_any_sighting_epoch_ms }
let lastZoneStatusesFetchAtMs = null;

// The zones table's own `name` for kenai, confirmed directly against the linked project --
// hardcoded rather than fetched to keep the (far more common) Kenai path to exactly one network
// round trip, same reasoning db.js's own getKenaiPresenceState never joins zones for this.
const KENAI_DISPLAY_NAME = "Kenai";

function zoneDisplayNameFallback(slug) {
  return slug.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

let currentZoneDisplayName = isKenaiZone ? KENAI_DISPLAY_NAME : zoneDisplayNameFallback(currentZoneSlug);
document.title = `BELUGAS Status — ${currentZoneDisplayName}`;
document.getElementById("status-zone-name").textContent = currentZoneDisplayName;

async function fetchKenaiPresenceStateRaw() {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/get_kenai_presence_state`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json"
    },
    body: "{}"
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return (data && data[0]) ?? null;
}

// Item 97c: same RPC/lookback (DEFAULT_YELLOW_WINDOW_MS, presence.js) presence-state.js's own
// pollWatchedZoneStatuses uses, so a zone's status here can never disagree with what the main
// app's own banner/map would show for it right now.
async function fetchWatchedZoneStatusesRaw() {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/get_watched_zone_statuses`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ p_lookback_ms: DEFAULT_YELLOW_WINDOW_MS })
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

async function refreshStatus() {
  try {
    if (isKenaiZone) {
      const detail = await fetchKenaiPresenceStateRaw();
      if (detail) {
        lastSnapshot = { detail, fetchedAtMs: Date.now() };
        hasEverFetched = true;
        lastFetchFailed = false;
      } else {
        lastFetchFailed = true;
      }
    } else {
      const rows = await fetchWatchedZoneStatusesRaw();
      const row = rows.find((r) => r.zone_slug === currentZoneSlug);
      lastZoneStatusesFetchAtMs = Date.now();
      hasEverFetched = true;
      lastFetchFailed = false;
      if (row) {
        currentZoneStatusRow = row;
        currentZoneDisplayName = row.zone_name || currentZoneDisplayName;
        document.title = `BELUGAS Status — ${currentZoneDisplayName}`;
        document.getElementById("status-zone-name").textContent = currentZoneDisplayName;
        zoneNotFound = false;
      } else {
        zoneNotFound = true;
      }
    }
  } catch (e) {
    console.error("STATUS_PAGE_FETCH_ERROR", e);
    lastFetchFailed = true;
  }
  render(/* fetchRedData */ true);
  // Retries sooner while the very first fetch hasn't landed yet -- same "cold start" reasoning as
  // presence-state.js's own pollNearbyWatchedZones, reusing its LOCATION_RETRY_INTERVAL_MS
  // constant (from presence.js) rather than inventing a new one.
  const delay = (!hasEverFetched && lastFetchFailed) ? LOCATION_RETRY_INTERVAL_MS : STATUS_REFETCH_INTERVAL_MS;
  setTimeout(refreshStatus, delay);
}

// Returns the CURRENT effective status (PRESENCE_RED/YELLOW/BLUE/UNKNOWN), computed identically
// to how the main app itself would for this same zone right now.
function computeCurrentStatus(nowMs) {
  if (isKenaiZone) {
    return effectiveKenaiPresenceStatus(lastSnapshot, nowMs);
  }
  if (zoneNotFound) return PRESENCE_UNKNOWN;
  const flat = computeBelugaPresenceStatus(currentZoneStatusRow, nowMs);
  return effectivePresenceStatus(flat, lastZoneStatusesFetchAtMs, nowMs);
}

function render(fetchRedData) {
  const textEl = document.getElementById("status-text");
  const updatedEl = document.getElementById("status-updated");

  if (!hasEverFetched) {
    document.body.style.background = colorForBelugaPresenceStatus(PRESENCE_UNKNOWN);
    document.body.classList.remove("on-yellow");
    textEl.textContent = lastFetchFailed ? "COULDN'T LOAD STATUS" : "LOADING…";
    updatedEl.textContent = lastFetchFailed ? "Check your connection — retrying…" : "";
    updateRedSection(PRESENCE_UNKNOWN, false);
    return;
  }

  if (!isKenaiZone && zoneNotFound) {
    document.body.style.background = colorForBelugaPresenceStatus(PRESENCE_UNKNOWN);
    document.body.classList.remove("on-yellow");
    textEl.textContent = "UNKNOWN ZONE";
    updatedEl.textContent = `"${currentZoneSlug}" isn't a watched zone.`;
    updateRedSection(PRESENCE_UNKNOWN, false);
    return;
  }

  // Same staleness-escalation math the main app's own banner runs for this exact zone, driven by
  // this file's own tick() below -- a status page left open with no working connection still
  // visibly decays toward UNKNOWN instead of showing an hours-old RED/YELLOW forever.
  const status = computeCurrentStatus(Date.now());
  document.body.style.background = colorForBelugaPresenceStatus(status);
  document.body.classList.toggle("on-yellow", status === PRESENCE_YELLOW);

  let label;
  let asOf;
  if (isKenaiZone) {
    if (status === PRESENCE_RED) {
      // Deliberate deviation from presence.js's own kenaiBannerLabel, whose RED text ends in
      // "· CHECK MAP · NEXT WINDOW ~{time}" -- there's no in-app map to send the reader to here
      // (the compact map below IS the map), and "next window" doesn't apply while it's already RED.
      label = "BELUGAS PRESENT";
    } else if (status === PRESENCE_YELLOW || status === PRESENCE_BLUE) {
      label = kenaiGateTimeLabel(lastSnapshot.detail, "");
    } else {
      label = "STATUS UNKNOWN";
    }
    asOf = formatTime12Hour(lastSnapshot.fetchedAtMs);
  } else {
    // Item 97c: plain generic text -- same branches presenceBannerLabel's own non-Kenai fallback
    // uses (BELUGAS PRESENT · CHECK MAP / POSSIBLE ACTIVITY / NO RECENT SIGHTINGS), minus the
    // "· CHECK MAP" half of RED for the same reason Kenai's own RED text drops it above. No
    // gate-time wording -- that's tide-cycle machinery specific to Kenai, nothing to generalize.
    if (status === PRESENCE_RED) {
      label = "BELUGAS PRESENT";
    } else if (status === PRESENCE_YELLOW) {
      label = "POSSIBLE ACTIVITY";
    } else if (status === PRESENCE_BLUE) {
      label = "NO RECENT SIGHTINGS";
    } else {
      label = "STATUS UNKNOWN";
    }
    asOf = formatTime12Hour(lastZoneStatusesFetchAtMs);
  }
  textEl.textContent = label;
  updatedEl.textContent = lastFetchFailed ? `Last updated ${asOf} — retrying…` : `Last updated ${asOf}`;

  updateRedSection(status, fetchRedData);
}

function tick() {
  if (hasEverFetched) render(/* fetchRedData */ false);
  setTimeout(tick, PRESENCE_DECAY_TICK_INTERVAL_MS);
}

// ---------------------------------------------------------------------------------------------
// Item 97b/97c: RED-only compact map + last-seen line, for whichever zone this page is showing.
// ---------------------------------------------------------------------------------------------

let leafletAndDepsLoadPromise = null;
let redQualifyingSightings = null; // last successful fetch, normalized to one shape regardless of source; or null
let redMapInstance = null;
let redMarkersLayerGroup = null;

// Loads Leaflet (same pinned CDN build/integrity hash the main app uses, see index.html), then
// geofence.js (KENAI_RIVER_CENTERLINE/nearestSegment/initialBearingDegrees/WELL_SOURCED_ZONES/
// OUTER_GEOFENCE_*, all reused as-is rather than reinvented) and kenai-landmarks.js, in that
// order, exactly once -- cached forever in leafletAndDepsLoadPromise regardless of how many times
// the status flips in and out of RED afterward. geofence.js/kenai-landmarks.js are loaded
// regardless of zone -- geofence.js's WELL_SOURCED_ZONES/OUTER_GEOFENCE_* are needed for every
// zone's map fit, not just Kenai's.
function ensureLeafletAndDepsLoaded() {
  if (leafletAndDepsLoadPromise) return leafletAndDepsLoadPromise;
  leafletAndDepsLoadPromise = new Promise((resolve, reject) => {
    const cssLink = document.createElement("link");
    cssLink.rel = "stylesheet";
    cssLink.href = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css";
    cssLink.integrity = "sha512-h9FcoyWjHcOcmEVkxOfTLnmZFWIH0iZhZT1H2TbOq55xssQGEJHEaIm+PgoUaZbRvQTNTluNOEfb1ZRy6D3BOw==";
    cssLink.crossOrigin = "anonymous";
    cssLink.referrerPolicy = "no-referrer";
    document.head.appendChild(cssLink);

    function loadScript(src, opts) {
      return new Promise((res, rej) => {
        const s = document.createElement("script");
        s.src = src;
        if (opts?.integrity) {
          s.integrity = opts.integrity;
          s.crossOrigin = "anonymous";
          s.referrerPolicy = "no-referrer";
        }
        s.onload = res;
        s.onerror = () => rej(new Error(`Failed to load ${src}`));
        document.body.appendChild(s);
      });
    }

    loadScript("https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js", {
      integrity: "sha512-BwHfrr4c9kmRkLw6iXFdzcdWV/PGkVgiIyIWLLlTSXzWQzxuSg4DiQUCpauz/EWjgk5TYQqX/kvn9pG1NpYfqg=="
    })
      .then(() => loadScript("../js/geofence.js"))
      .then(() => loadScript("kenai-landmarks.js"))
      .then(resolve)
      .catch(reject);
  });
  return leafletAndDepsLoadPromise;
}

async function fetchKenaiRedQualifyingSightingsRaw() {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/get_kenai_red_qualifying_sightings`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json"
    },
    body: "{}"
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

// Item 97c: the generic-zone equivalent, built on export_sightings (already anon+authenticated
// EXECUTE-granted, no new migration needed -- confirmed against the linked project's live grants
// during item 96's audit) rather than a per-zone bespoke RPC. export_sightings itself has no
// tier/verification filter, so the SAME rule get_watched_zone_statuses' own
// last_verified_sighting_epoch_ms uses (20260903020000_wire_observer_tier_into_red_banner.sql:
// is_geofence_verified AND ((observer_tier=2 AND observer_type='SELF') OR observer_tier=1)) is
// applied here client-side, then normalized to the same row shape
// get_kenai_red_qualifying_sightings returns so renderRedMap/buildLastSeenLine don't need to care
// which path produced their data.
async function fetchGenericZoneRedQualifyingSightings(zoneSlug, nowMs) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/export_sightings`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      p_start_ms: nowMs - DEFAULT_RED_WINDOW_MS,
      p_end_ms: nowMs,
      p_min_lat: OUTER_GEOFENCE_MIN_LAT,
      p_max_lat: OUTER_GEOFENCE_MAX_LAT,
      p_min_lng: OUTER_GEOFENCE_MIN_LNG,
      p_max_lng: OUTER_GEOFENCE_MAX_LNG,
      p_zone_slug: zoneSlug
    })
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const rows = await res.json();
  return rows
    .filter((s) => s.is_geofence_verified && ((s.observer_tier === 2 && s.observer_type === "SELF") || s.observer_tier === 1))
    .map((s) => ({
      id: s.id,
      observed_at_epoch_ms: s.observed_at_epoch_ms,
      whale_lat: s.whale_lat,
      whale_lng: s.whale_lng,
      count_whites: s.count_whites,
      count_greys: s.count_greys,
      count_calves: s.count_calves,
      count_unknown: s.count_unknown,
      travel_bearing_degrees: s.travel_bearing_degrees
    }))
    .sort((a, b) => b.observed_at_epoch_ms - a.observed_at_epoch_ms);
}

function formatRelativeTime(epochMs, nowMs) {
  const minutes = Math.round(Math.max(0, nowMs - epochMs) / 60000);
  if (minutes < 1) return "just now";
  if (minutes === 1) return "1 min ago";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
}

// Up/downriver, derived from the sighting's own travel_bearing_degrees against the REAL Kenai
// River centerline's local course at that point (geofence.js's KENAI_RIVER_CENTERLINE/
// nearestSegment) -- not just eyeballing a compass word. KENAI_RIVER_CENTERLINE is ordered from
// the mouth (index 0) upriver (see geofence.js's own truncateAtRiverMiles comment: "walks
// cumulative distance from the mouth"), so nearestSegment's own bearingDegrees (start->end of the
// matched segment) IS the local upriver direction at that point; downriver is its reverse.
function describeKenaiTravelDirection(travelBearingDegrees, lat, lng) {
  if (travelBearingDegrees == null) return "direction not reported";
  const seg = nearestSegment(lat, lng, KENAI_RIVER_CENTERLINE);
  if (!seg) return "direction not reported"; // shouldn't happen for a real RED-qualifying position, but never crash the page over it
  const downriverBearing = (seg.bearingDegrees + 180) % 360;
  let diff = Math.abs(travelBearingDegrees - downriverBearing);
  if (diff > 180) diff = 360 - diff;
  return diff <= 90 ? "heading downriver toward the mouth" : "heading upriver";
}

// Item 97c: no river to be relative to on a generic zone (Turnagain Arm, Knik Arm, ...) -- a
// plain 16-point compass label is the honest generalization instead of inventing a fake "up/down"
// axis for water that doesn't have one.
const COMPASS_POINTS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
function describeGenericTravelDirection(travelBearingDegrees) {
  if (travelBearingDegrees == null) return "direction not reported";
  const idx = Math.round(travelBearingDegrees / 22.5) % 16;
  return `heading ${COMPASS_POINTS[idx]}`;
}

function buildLastSeenLine(sighting, nowMs) {
  const relTime = formatRelativeTime(sighting.observed_at_epoch_ms, nowMs);
  if (isKenaiZone) {
    const landmarkName = nearestKenaiLandmarkName(sighting.whale_lat, sighting.whale_lng);
    const direction = describeKenaiTravelDirection(
      sighting.travel_bearing_degrees, sighting.whale_lat, sighting.whale_lng
    );
    const nearClause = landmarkName ? ` near ${landmarkName}` : "";
    return `Last seen ${relTime}${nearClause}, ${direction}`;
  }
  // No landmark lookup for generic zones (item 97b's own "if we have a small lookup, else omit").
  const direction = describeGenericTravelDirection(sighting.travel_bearing_degrees);
  return `Last seen ${relTime}, ${direction}`;
}

// Bounding box (Leaflet LatLngBounds corner pair) from a WELL_SOURCED_ZONES-style ring of
// [lat,lng] pairs.
function boundingBoxFromRing(ring) {
  let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity;
  ring.forEach(([lat, lng]) => {
    minLat = Math.min(minLat, lat);
    maxLat = Math.max(maxLat, lat);
    minLng = Math.min(minLng, lng);
    maxLng = Math.max(maxLng, lng);
  });
  return [[minLat, minLng], [maxLat, maxLng]];
}

// The extra reference point(s) the map's fitBounds should ALWAYS include, beyond the sightings
// themselves -- Kenai gets the real river mouth (per the original brief: "fit the sightings plus
// the river mouth"); a generic zone gets its own real traced polygon's bounding box when
// geofence.js's WELL_SOURCED_ZONES has one for it (not every banner-watched zone necessarily
// will), so the map still reads as "this zone," not just an unrecognizable close crop around
// whatever happened to be sighted. Falls back to sightings-only bounds when neither applies.
function extraFitBoundsPoints() {
  if (isKenaiZone) return [KENAI_RIVER_CENTERLINE[0]];
  const zoneEntry = WELL_SOURCED_ZONES.find((z) => z.slug === currentZoneSlug);
  if (!zoneEntry) return [];
  return boundingBoxFromRing(zoneEntry.fullRing);
}

function renderRedMap(sightings, nowMs) {
  if (!redMapInstance) {
    redMapInstance = L.map("status-map", {
      zoomControl: true,
      // "Lightly zoomable," per the brief -- drag/pinch enabled so a reader can see exact
      // placement better, scrollWheelZoom off so an embedded map inside a page you're scrolling
      // never hijacks that scroll on desktop.
      scrollWheelZoom: false
    });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19
    }).addTo(redMapInstance);
    redMarkersLayerGroup = L.layerGroup().addTo(redMapInstance);
  }
  redMarkersLayerGroup.clearLayers();

  const boundsPoints = extraFitBoundsPoints();

  sightings.forEach((s) => {
    boundsPoints.push([s.whale_lat, s.whale_lng]);
    const total = (s.count_whites || 0) + (s.count_greys || 0) + (s.count_calves || 0) + (s.count_unknown || 0);
    const relTime = formatRelativeTime(s.observed_at_epoch_ms, nowMs);
    const arrowHtml = s.travel_bearing_degrees != null
      ? `<div class="status-sighting-arrow" style="transform: rotate(${s.travel_bearing_degrees}deg);"></div>`
      : "";
    const icon = L.divIcon({
      html: `<div style="position:relative;">${arrowHtml}<div class="status-sighting-dot"></div></div>`,
      className: "",
      iconSize: [16, 16],
      iconAnchor: [8, 8]
    });
    const marker = L.marker([s.whale_lat, s.whale_lng], { icon }).addTo(redMarkersLayerGroup);
    marker.bindTooltip(`${total} beluga${total === 1 ? "" : "s"} · ${relTime}`, {
      permanent: true,
      direction: "top",
      className: "status-sighting-tooltip",
      offset: [0, -10]
    });
  });

  redMapInstance.fitBounds(L.latLngBounds(boundsPoints).pad(0.25));
  // The map container is hidden (display:none via [hidden]) right up until updateRedSection
  // below un-hides it, so Leaflet's own container-size measurement needs a kick once it's
  // actually visible -- without this, panes stay sized to whatever they measured at (possibly
  // 0x0, if this is the very first RED render).
  setTimeout(() => redMapInstance.invalidateSize(), 0);
}

// status: the CURRENT effective status (already computed by render()). fetchNewData: true only
// on an actual network refresh (refreshStatus), never on tick()'s 30s display-only recompute --
// the map/last-seen line only need to change when new sighting data could exist, not every tick.
async function updateRedSection(status, fetchNewData) {
  const wrapEl = document.getElementById("status-map-wrap");

  if (status !== PRESENCE_RED) {
    wrapEl.hidden = true;
    return;
  }

  if (fetchNewData) {
    try {
      await ensureLeafletAndDepsLoaded();
      redQualifyingSightings = isKenaiZone
        ? await fetchKenaiRedQualifyingSightingsRaw()
        : await fetchGenericZoneRedQualifyingSightings(currentZoneSlug, Date.now());
    } catch (e) {
      console.error("STATUS_PAGE_RED_MAP_ERROR", e);
      // Deliberately don't clear a previously-successful redQualifyingSightings on a transient
      // failure -- same graceful-degrade posture as the rest of this page (and the main app's own
      // presence state): keep showing the last known-good map/line rather than blanking it.
    }
  }

  // Nothing to show yet (still loading on this device's very first RED, or every attempt so far
  // has failed) -- fall back to just the color/text/updated-time above, no map, rather than an
  // empty or broken-looking map block.
  if (!redQualifyingSightings || redQualifyingSightings.length === 0) {
    wrapEl.hidden = true;
    return;
  }

  wrapEl.hidden = false;
  renderRedMap(redQualifyingSightings, Date.now());
  document.getElementById("status-last-seen-line").textContent =
    buildLastSeenLine(redQualifyingSightings[0], Date.now());
}

refreshStatus();
tick();
