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
  kenaiBelugaStatus: PRESENCE_UNKNOWN,
  // Item 44: parallels hasEverFetchedWatchedZoneStatuses above, but for the Kenai path -- lets
  // presence-banner.js tell "genuinely never fetched yet" (LOADING) apart from "fetched, and the
  // real answer is UNKNOWN" (kenaiPresenceSnapshot being null is NOT enough on its own for that,
  // since nothing here otherwise distinguishes those two cases).
  hasEverFetchedKenaiPresenceState: false
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

  // BUG FIX (item 44): these two used to be fire-and-forget (called without awaiting, both here
  // and by app.js's own caller), so app.js's splash-gate Promise.all never actually waited for
  // them -- kenaiBelugaStatus/watchedZoneStatuses sat at their default PRESENCE_UNKNOWN for
  // however long the FIRST real poll took to land, which was AFTER the splash had already
  // cleared and the presence banner was already visible: grey, then correcting to the real color
  // moments later (same root cause as the river shading's own analogous lag). Awaiting the first
  // round of each here -- and awaiting initPresenceState() itself from app.js's own gate now --
  // means neither the banner nor the shading is ever shown before the real data has actually
  // landed, except on a genuine fetch failure (see the LOADING-vs-UNKNOWN distinction those two
  // functions' own hasEverFetched* flags now carry, for exactly that residual case). Both
  // functions still self-schedule their own OWN recurring poll via setTimeout independently of
  // this await -- only the very first round is gated.
  await Promise.all([pollWatchedZoneStatuses(), pollKenaiPresenceState()]);
  tickWatchedZoneStatusesStaleness();
  tickKenaiBelugaStatus();
  // Not awaited/gated -- GPS can prompt for permission or take far longer than a Supabase RPC
  // round trip, and blocking the splash on that would risk a much worse hang than the two awaits
  // above. This only affects whether a zone counts as "nearby" (relevantIds' own visibility gate,
  // presence-banner.js), not the RED/YELLOW/BLUE color/status data itself.
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
    presenceState.hasEverFetchedKenaiPresenceState = true;
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
