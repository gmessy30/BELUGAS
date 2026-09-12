// Centralized presence-state polling -- ports App.kt's six independent state pieces (see that
// file's own header comment above its watchedZoneShadingAreas block) into one shared module, so
// map-view.js (shading, everyone sees it) and presence-banner.js (the gated carousel) read off
// the exact same fetched/ticked values instead of each polling independently.
const presenceState = {
  watchedZoneShadingAreas: [],
  watchedZoneStatuses: [],
  hasEverFetchedWatchedZoneStatuses: false,
  lastSuccessfulWatchedZoneStatusesFetchAtMs: null,
  isWatchedZoneStatusesStale: false,
  subscribedWatchedZoneIds: [],
  nearbyWatchedZones: [],
  kenaiPresenceSnapshot: null, // { detail, fetchedAtMs }
  isKenaiDataStale: false,
  kenaiBelugaStatus: PRESENCE_UNKNOWN
};

const presenceStateListeners = [];
function onPresenceStateChanged(callback) {
  presenceStateListeners.push(callback);
}
function notifyPresenceStateChanged() {
  presenceStateListeners.forEach((cb) => cb());
}

async function initPresenceState() {
  // Shading geometry -- fetched once, doesn't change at runtime, matches App.kt's own
  // single-shot LaunchedEffect(Unit).
  presenceState.watchedZoneShadingAreas = await getWatchedZoneShadingAreas();
  notifyPresenceStateChanged();

  pollWatchedZoneStatuses();
  tickWatchedZoneStatusesStaleness();
  pollKenaiPresenceState();
  tickKenaiBelugaStatus();
  pollNearbyWatchedZones();
}

async function pollWatchedZoneStatuses() {
  const statuses = await getWatchedZoneStatuses(DEFAULT_YELLOW_WINDOW_MS);
  // null (failure) intentionally leaves watchedZoneStatuses/subscribedWatchedZoneIds exactly as
  // they were -- matches App.kt's own comment on this exact branch.
  if (statuses != null) {
    presenceState.watchedZoneStatuses = statuses;
    presenceState.hasEverFetchedWatchedZoneStatuses = true;
    presenceState.lastSuccessfulWatchedZoneStatusesFetchAtMs = Date.now();
    const subscriberId = getOrCreateSubscriberId();
    presenceState.subscribedWatchedZoneIds = await getRelevantWatchedZoneIds(subscriberId);
    notifyPresenceStateChanged();
  }
  setTimeout(pollWatchedZoneStatuses, LOCATION_POLL_INTERVAL_MS);
}

function tickWatchedZoneStatusesStaleness() {
  const lastFetch = presenceState.lastSuccessfulWatchedZoneStatusesFetchAtMs;
  const wasStale = presenceState.isWatchedZoneStatusesStale;
  presenceState.isWatchedZoneStatusesStale = lastFetch != null &&
    (Date.now() - lastFetch > DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS);
  if (presenceState.isWatchedZoneStatusesStale !== wasStale) notifyPresenceStateChanged();
  setTimeout(tickWatchedZoneStatusesStaleness, PRESENCE_DECAY_TICK_INTERVAL_MS);
}

async function pollKenaiPresenceState() {
  const fetched = await getKenaiPresenceState();
  if (fetched != null) {
    presenceState.kenaiPresenceSnapshot = { detail: fetched, fetchedAtMs: Date.now() };
    notifyPresenceStateChanged();
  }
  setTimeout(pollKenaiPresenceState, LOCATION_POLL_INTERVAL_MS);
}

function tickKenaiBelugaStatus() {
  const snapshot = presenceState.kenaiPresenceSnapshot;
  const nowMs = Date.now();
  const wasStale = presenceState.isKenaiDataStale;
  const wasStatus = presenceState.kenaiBelugaStatus;
  presenceState.isKenaiDataStale = snapshot != null &&
    (nowMs - snapshot.fetchedAtMs > DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS);
  presenceState.kenaiBelugaStatus = snapshot ? effectiveKenaiPresenceStatus(snapshot, nowMs) : PRESENCE_UNKNOWN;
  if (presenceState.isKenaiDataStale !== wasStale || presenceState.kenaiBelugaStatus !== wasStatus) {
    notifyPresenceStateChanged();
  }
  setTimeout(tickKenaiBelugaStatus, PRESENCE_DECAY_TICK_INTERVAL_MS);
}

// Matches App.kt's location LaunchedEffect: retries sooner (LOCATION_RETRY_INTERVAL_MS) after a
// null/failed fix (e.g. a cold GPS read right after load) instead of waiting out the full poll
// interval, and never clobbers a previously-successful nearbyWatchedZones with empty on a later
// transient failure -- so an already-shown banner can't disappear from one bad location read.
function pollNearbyWatchedZones() {
  if (!navigator.geolocation) return; // no fallback poll possible -- subscription-only gating applies
  navigator.geolocation.getCurrentPosition(
    async (position) => {
      presenceState.nearbyWatchedZones = await findNearbyWatchedZones(
        position.coords.latitude,
        position.coords.longitude,
        DEFAULT_BANNER_PROXIMITY_METERS
      );
      notifyPresenceStateChanged();
      setTimeout(pollNearbyWatchedZones, LOCATION_POLL_INTERVAL_MS);
    },
    (err) => {
      // Permission denied or otherwise unavailable -- per the brief, fall back to subscription-
      // only gating rather than blocking the banner entirely (nearbyWatchedZones just stays
      // whatever it last was, empty if it's never succeeded).
      console.error("PRESENCE_LOCATION_ERROR", err);
      setTimeout(pollNearbyWatchedZones, LOCATION_RETRY_INTERVAL_MS);
    },
    { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
  );
}
