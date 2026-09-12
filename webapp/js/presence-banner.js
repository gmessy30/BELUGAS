// Bottom presence-banner carousel -- ports PresenceBanner.kt's BelugaPresenceBanner/
// BelugaPresenceBannerCarousel + App.kt's card-building (relevantWatchedZoneIds, nonKenai
// escalation) directly.
//
// VISIBILITY, confirmed against source before porting (see App.kt's relevantWatchedZoneIds and
// SupabaseApi.getRelevantWatchedZoneIds' containment-aware RPC): a zone's card only exists when
// this device is subscribed to it (server-side containment match, e.g. an "Entire Inlet"
// subscription also covers Kenai) OR within DEFAULT_BANNER_PROXIMITY_METERS (15km) of it --
// unlike the map's shading, which every viewer sees unconditionally. Neither gate present means
// no card, means nothing renders -- an Anchorage user with no Kenai subscription and nowhere
// near Kenai never sees Kenai's banner, even though they'd still see its river shaded on the map.
//
// Hidden entirely while the Report tab (camera/logging) is showing (app.js's switchTab) --
// matches App.kt's showPresenceBanner excluding CAPTURE/PHOTO_LOGGING/MANUAL_LOGGING/
// ACKNOWLEDGEMENT_GATE. Otherwise it's a fixed bottom overlay above Map/List/the menu/About/
// Resources/News Feed/Alerts, same as native drawing it in the same Box as whatever screen is
// current. Native additionally reserves bottom padding on every AppBackground screen equal to
// the banner's own measured height (LocalBottomContentInset) so it never covers their bottommost
// content -- not reproduced here (a fixed ~50px bar has a small footprint, and dynamically
// measuring/reserving that inset for every screen was judged not worth the complexity for this
// pass); flagged as a known simplification, not an oversight.
let presenceBannerCards = [];
let presenceBannerCurrentIndex = 0;
let presenceBannerOrderKey = "";
let isReportTabActive = false;

function initPresenceBanner() {
  document.getElementById("presence-banner-main").addEventListener("click", () => {
    if (presenceBannerCards.length < 2) return;
    presenceBannerCurrentIndex = (presenceBannerCurrentIndex + 1) % presenceBannerCards.length;
    renderPresenceBannerCard();
  });

  onPresenceStateChanged(rebuildPresenceBannerCards);
  rebuildPresenceBannerCards();
}

function rebuildPresenceBannerCards() {
  const nearbyIds = presenceState.nearbyWatchedZones.map((z) => z.zone_id);
  const subscribedIds = presenceState.subscribedWatchedZoneIds;
  const relevantIds = Array.from(new Set([...nearbyIds, ...subscribedIds]));
  const nowMs = Date.now();

  const cards = relevantIds.map((zoneId) => {
    const nearby = presenceState.nearbyWatchedZones.find((z) => z.zone_id === zoneId);
    const statusRow = presenceState.watchedZoneStatuses.find((s) => s.zone_id === zoneId);
    const zoneSlug = nearby?.zone_slug ?? statusRow?.zone_slug ?? "";
    const zoneName = nearby?.zone_name ?? statusRow?.zone_name ?? null;
    const isKenai = zoneSlug === "kenai";

    let status;
    if (isKenai) {
      status = presenceState.kenaiBelugaStatus;
    } else if (!presenceState.hasEverFetchedWatchedZoneStatuses) {
      status = PRESENCE_UNKNOWN;
    } else {
      const flat = computeBelugaPresenceStatus(statusRow, nowMs);
      status = effectivePresenceStatus(flat, presenceState.lastSuccessfulWatchedZoneStatusesFetchAtMs, nowMs);
    }

    return {
      zoneId,
      zoneName,
      zoneSlug,
      status,
      kenaiDetail: isKenai ? (presenceState.kenaiPresenceSnapshot?.detail ?? null) : null,
      isDataStale: isKenai ? presenceState.isKenaiDataStale : presenceState.isWatchedZoneStatusesStale,
      isSubscribed: subscribedIds.includes(zoneId)
    };
  }).sort(presenceBannerCardComparator);

  // Reset to the top card whenever the ORDER changes (a new RED, a zone dropping off, a
  // subscribed zone overtaking a nearby-only one) -- not on every routine same-ordering refresh,
  // matching BelugaPresenceBannerCarousel's own orderKey behavior exactly.
  const orderKey = cards.map((c) => c.zoneId).join(",");
  if (orderKey !== presenceBannerOrderKey) {
    presenceBannerCurrentIndex = 0;
    presenceBannerOrderKey = orderKey;
  }

  presenceBannerCards = cards;
  renderPresenceBannerCard();
}

function renderPresenceBannerCard() {
  const banner = document.getElementById("presence-banner");

  if (isReportTabActive || presenceBannerCards.length === 0) {
    banner.hidden = true;
    return;
  }
  banner.hidden = false;

  const safeIndex = Math.min(presenceBannerCurrentIndex, presenceBannerCards.length - 1);
  const card = presenceBannerCards[safeIndex];
  const color = colorForBelugaPresenceStatus(card.status);

  const mainEl = document.getElementById("presence-banner-main");
  mainEl.style.background = color;
  document.getElementById("presence-banner-label").textContent = presenceBannerLabel(card);

  const warningsEl = document.getElementById("presence-banner-warnings");
  const warnings = card.kenaiDetail?.warnings ?? [];
  if (warnings.length > 0) {
    warningsEl.hidden = false;
    warningsEl.textContent = "⚠ " + warnings.join(" · ");
  } else {
    warningsEl.hidden = true;
  }

  const dotsEl = document.getElementById("presence-banner-dots");
  const showChrome = presenceBannerCards.length >= 2;
  mainEl.classList.toggle("clickable", showChrome);
  if (showChrome) {
    dotsEl.hidden = false;
    dotsEl.style.background = color;
    dotsEl.innerHTML = "";
    presenceBannerCards.forEach((_, i) => {
      const dot = document.createElement("span");
      dot.className = "presence-banner-dot" + (i === safeIndex ? " active" : "");
      dotsEl.appendChild(dot);
    });
  } else {
    dotsEl.hidden = true;
  }
}

// Called from app.js's switchTab.
function setPresenceBannerReportTabActive(active) {
  isReportTabActive = active;
  renderPresenceBannerCard();
}
