// Pure presence-status math -- ports PresenceBanner.kt's constants/functions directly (no
// Compose/UI in this file, same separation native keeps). Shared by map-view.js (river shading)
// and presence-banner.js (the bottom carousel), so both surfaces can never disagree about what
// RED/YELLOW/BLUE/UNKNOWN mean or look like.

// "BelugaPresenceStatus" -- plain string constants standing in for the Kotlin enum.
const PRESENCE_RED = "RED";
const PRESENCE_YELLOW = "YELLOW";
const PRESENCE_BLUE = "BLUE";
const PRESENCE_UNKNOWN = "UNKNOWN";

const DEFAULT_RED_WINDOW_MS = 2 * 60 * 60 * 1000;
const DEFAULT_YELLOW_WINDOW_MS = 36 * 60 * 60 * 1000;
const DEFAULT_BANNER_PROXIMITY_METERS = 15000.0;
const LOCATION_POLL_INTERVAL_MS = 5 * 60 * 1000;
const PRESENCE_DECAY_TICK_INTERVAL_MS = 30 * 1000;
const LOCATION_RETRY_INTERVAL_MS = 15 * 1000;
const DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS = 3 * LOCATION_POLL_INTERVAL_MS;

const KENAI_SECOND_BOUNDARY_OFFSET_MS = 12 * 60 * 60 * 1000;
const KENAI_NULL_BOUNDARY_CEILING_MS = 15 * 60 * 60 * 1000;
const KENAI_EXEMPT_BLUE_CEILING_MS = 24 * 60 * 60 * 1000;
const KENAI_GATE_TIME_EARLY_BIAS_MS = 3 * 60 * 1000;
const DEFAULT_GENERIC_ESCALATION_WINDOW_MS = 12 * 60 * 60 * 1000;

// Same literal "MM-DD" strings as get_kenai_presence_state's own v_in_season / PresenceBanner.kt
// -- kept identical on purpose so the two can be diffed by eye.
const FALL_SEASON_START = "08-15";
const FALL_SEASON_END = "12-31";
const SPRING_SEASON_START = "03-15";
const SPRING_SEASON_END = "05-14";

// Matches the native platform's anchorageMonthDay(epochMs): "MM-DD" in America/Anchorage.
function anchorageMonthDay(epochMs) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Anchorage",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(new Date(epochMs));
  const month = parts.find((p) => p.type === "month").value;
  const day = parts.find((p) => p.type === "day").value;
  return `${month}-${day}`;
}

function isKenaiInSeasonLocally(nowMs) {
  const monthDay = anchorageMonthDay(nowMs);
  return (monthDay >= FALL_SEASON_START && monthDay <= FALL_SEASON_END) ||
    (monthDay >= SPRING_SEASON_START && monthDay <= SPRING_SEASON_END);
}

function belugaPresenceStatusFromKenaiPhase(phase) {
  if (phase === "RED") return PRESENCE_RED;
  if (phase === "YELLOW") return PRESENCE_YELLOW;
  return PRESENCE_BLUE;
}

/**
 * Ports effectiveKenaiPresenceStatus exactly: escalates snapshot.detail's server phase toward
 * UNKNOWN once nowMs has drifted too far past snapshot.fetchedAtMs (or a known tide-cycle
 * boundary) for that phase to still be trustworthy. Only ever moves TOWARD UNKNOWN.
 */
function effectiveKenaiPresenceStatus(snapshot, nowMs) {
  const detail = snapshot.detail;
  const lastFetchAtMs = snapshot.fetchedAtMs;
  const serverStatus = belugaPresenceStatusFromKenaiPhase(detail.phase);

  if (nowMs - lastFetchAtMs <= DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS) {
    return serverStatus;
  }

  const firstBoundaryMs = detail.next_cycle_low_epoch_ms ?? (lastFetchAtMs + KENAI_NULL_BOUNDARY_CEILING_MS);

  if (serverStatus === PRESENCE_BLUE) {
    const isExemptOutOfSeason = !detail.in_season;
    const isExemptNoPrediction = detail.in_season && !detail.prediction_available;
    if (!isExemptOutOfSeason && !isExemptNoPrediction) {
      return nowMs < firstBoundaryMs ? PRESENCE_BLUE : PRESENCE_UNKNOWN;
    }
    if (isExemptOutOfSeason && isKenaiInSeasonLocally(nowMs)) {
      return PRESENCE_UNKNOWN;
    }
    return (nowMs - lastFetchAtMs > KENAI_EXEMPT_BLUE_CEILING_MS) ? PRESENCE_UNKNOWN : PRESENCE_BLUE;
  }

  const secondBoundaryMs = firstBoundaryMs + KENAI_SECOND_BOUNDARY_OFFSET_MS;
  if (serverStatus === PRESENCE_RED) {
    if (nowMs < firstBoundaryMs) return PRESENCE_RED;
    if (nowMs < secondBoundaryMs) return PRESENCE_YELLOW;
    return PRESENCE_UNKNOWN;
  }
  if (serverStatus === PRESENCE_YELLOW) {
    return nowMs < secondBoundaryMs ? PRESENCE_YELLOW : PRESENCE_UNKNOWN;
  }
  return serverStatus;
}

/**
 * Ports computeBelugaPresenceStatus exactly: RED if a verified sighting landed within
 * redWindowMs, else YELLOW if any sighting landed within yellowWindowMs, else BLUE. Callers
 * must gate on "has a fetch ever actually succeeded" before calling this -- a null/missing
 * status here means "no row for this zone," not "no data at all," and folds into BLUE.
 */
function computeBelugaPresenceStatus(status, nowMs, redWindowMs = DEFAULT_RED_WINDOW_MS, yellowWindowMs = DEFAULT_YELLOW_WINDOW_MS) {
  const verifiedAt = status?.last_verified_sighting_epoch_ms;
  const anyAt = status?.last_any_sighting_epoch_ms;
  if (verifiedAt != null && nowMs - verifiedAt <= redWindowMs) return PRESENCE_RED;
  if (anyAt != null && nowMs - anyAt <= yellowWindowMs) return PRESENCE_YELLOW;
  return PRESENCE_BLUE;
}

/**
 * Ports effectivePresenceStatus exactly: the generic-zone (non-Kenai) staleness escalation used
 * by the BANNER only -- the map's own shading loop does NOT apply this (see SightingsMapScreen.kt:
 * non-Kenai zones there use computeBelugaPresenceStatus's raw result directly), so this must
 * never be called from the map-shading path.
 */
function effectivePresenceStatus(status, lastSuccessfulFetchAtMs, nowMs) {
  if (status === PRESENCE_UNKNOWN) return PRESENCE_UNKNOWN;
  if (lastSuccessfulFetchAtMs == null) return status;
  const staleness = nowMs - lastSuccessfulFetchAtMs;
  if (staleness <= DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS) return status;
  const secondStageMs = DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS + DEFAULT_GENERIC_ESCALATION_WINDOW_MS;
  if (status === PRESENCE_RED) return staleness <= secondStageMs ? PRESENCE_YELLOW : PRESENCE_UNKNOWN;
  if (status === PRESENCE_YELLOW) return staleness <= secondStageMs ? PRESENCE_YELLOW : PRESENCE_UNKNOWN;
  if (status === PRESENCE_BLUE) return PRESENCE_UNKNOWN;
  return PRESENCE_UNKNOWN;
}

// Shared by the map's FillLayer/LineLayer and the banner -- same hex values as
// colorForBelugaPresenceStatus, so the two surfaces always agree.
function colorForBelugaPresenceStatus(status) {
  switch (status) {
    case PRESENCE_RED: return "#C62828";
    case PRESENCE_YELLOW: return "#F9A825";
    case PRESENCE_BLUE: return "#0277BD";
    default: return "#616161";
  }
}

// Matches kenaiBannerLabel exactly, including the gate-time early-bias and the three distinct
// BLUE sub-states -- PLUS item 48's own RED addition, which native doesn't have yet (see that
// branch's own comment).
function kenaiBannerLabel(status, detail, zoneSuffix) {
  if (status === PRESENCE_RED) {
    // Item 48: gate_time_possible_epoch_ms/gate_time_likely_epoch_ms are predicted ARRIVAL
    // times, not departure times -- there is no departure prediction anywhere in this model
    // (confirmed against get_kenai_presence_state's own SQL before writing this, not assumed).
    // The RPC's own "gate floor" logic already rolls gate_time_possible_epoch_ms forward to the
    // UPCOMING cycle once the current cycle's own gate + 90-min holdover has passed -- typically
    // already true by the time a RED condition is even showing -- so by the time this renders,
    // it's naturally "the next window after this one," not the gate that already happened. Same
    // field/bias/formatting as the BLUE branch below (native's own "NOT EXPECTED IN THE RIVER
    // BEFORE {time}"), just worded for a forward-looking "next window" instead, since native
    // doesn't surface this during RED at all yet -- gracefully omitted if gate_time_possible_
    // epoch_ms is null (tide data unavailable), matching that branch's own null-handling exactly.
    const base = `BELUGAS PRESENT · CHECK MAP`;
    if (detail.gate_time_possible_epoch_ms == null) return `${base}${zoneSuffix}`;
    const biasedGateTimeMs = detail.gate_time_possible_epoch_ms - KENAI_GATE_TIME_EARLY_BIAS_MS;
    return `${base} · NEXT WINDOW ~${formatTime12Hour(biasedGateTimeMs)}${zoneSuffix}`;
  }
  if (status === PRESENCE_YELLOW) return `POSSIBLE ACTIVITY${zoneSuffix}`;
  if (status === PRESENCE_BLUE) {
    if (!detail.in_season) return `NOT EXPECTED THIS TIME OF YEAR${zoneSuffix}`;
    if (detail.gate_time_possible_epoch_ms == null) return `TIDE DATA UNAVAILABLE${zoneSuffix}`;
    const biasedGateTimeMs = detail.gate_time_possible_epoch_ms - KENAI_GATE_TIME_EARLY_BIAS_MS;
    return `NOT EXPECTED IN THE RIVER BEFORE ${formatTime12Hour(biasedGateTimeMs)}${zoneSuffix}`;
  }
  return `STATUS UNKNOWN${zoneSuffix}`;
}

// Matches OfflineSightingRepository.kt's formatTime12Hour: 12-hour clock with AM/PM (e.g. "3:42 PM").
function formatTime12Hour(epochMs) {
  return new Date(epochMs).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
}

// Matches BelugaPresenceBanner's own label assembly (the non-Kenai branch) plus the "(UPDATING…)"
// staleness suffix shared by both branches.
function presenceBannerLabel(card) {
  const zoneSuffix = card.zoneName ? ` · ${card.zoneName.toUpperCase()}` : "";
  let baseLabel;
  // Item 44: checked before the plain UNKNOWN branch below -- "never fetched yet" (isLoading)
  // reads as a genuinely different claim than "fetched, and the real answer is unknown/stale."
  // A grey "STATUS UNKNOWN" banner during an actual RED condition (the data just hasn't landed
  // yet) is actively misleading; "LOADING…" makes clear this isn't the real answer at all.
  if (card.isLoading) {
    baseLabel = `LOADING…${zoneSuffix}`;
  } else if (card.status === PRESENCE_UNKNOWN) {
    baseLabel = `STATUS UNKNOWN${zoneSuffix}`;
  } else if (card.kenaiDetail != null) {
    baseLabel = kenaiBannerLabel(card.status, card.kenaiDetail, zoneSuffix);
  } else if (card.status === PRESENCE_RED) {
    baseLabel = `BELUGAS PRESENT · CHECK MAP${zoneSuffix}`;
  } else if (card.status === PRESENCE_YELLOW) {
    baseLabel = `POSSIBLE ACTIVITY${zoneSuffix}`;
  } else if (card.status === PRESENCE_BLUE) {
    baseLabel = `NO RECENT SIGHTINGS${zoneSuffix}`;
  } else {
    baseLabel = `STATUS UNKNOWN${zoneSuffix}`;
  }
  return card.isDataStale ? `${baseLabel} (UPDATING…)` : baseLabel;
}

function presenceSeverityRank(status) {
  if (status === PRESENCE_RED) return 0;
  if (status === PRESENCE_YELLOW) return 1;
  if (status === PRESENCE_BLUE) return 2;
  return 3;
}

// Matches presenceBannerCardComparator: RED before YELLOW before BLUE/UNKNOWN; within a phase, a
// subscribed zone before a nearby-only one; alphabetical tiebreak.
function presenceBannerCardComparator(a, b) {
  const rankDiff = presenceSeverityRank(a.status) - presenceSeverityRank(b.status);
  if (rankDiff !== 0) return rankDiff;
  const subscribedDiff = (a.isSubscribed ? 0 : 1) - (b.isSubscribed ? 0 : 1);
  if (subscribedDiff !== 0) return subscribedDiff;
  return (a.zoneName ?? "").localeCompare(b.zoneName ?? "");
}
