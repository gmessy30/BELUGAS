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

// Item 45: full-screen overlays the banner (z-index 970) renders above -- if it's tapped while
// one of these is open, that overlay has to be closed too, not just left covering the map
// underneath after the tab switch. At most one is ever open at once in practice (a subpage is
// only ever reached via the menu, which hides itself first -- see main-menu.js).
const PRESENCE_BANNER_OVERLAY_IDS = [
  "main-menu", "resources-page", "news-feed-page", "about-page", "alerts-page", "share-page"
];

function initPresenceBanner() {
  document.getElementById("presence-banner-main").addEventListener("click", handlePresenceBannerTap);

  onPresenceStateChanged(rebuildPresenceBannerCards);
  rebuildPresenceBannerCards();
}

// Item 45 (field suggestion): RED's own label always ends in "CHECK MAP" -- tapping the banner
// while it reads that way jumps straight to the Sightings Map, the same real destination the
// text is already telling the reader to go check. Every OTHER phase (YELLOW/BLUE/UNKNOWN/LOADING)
// keeps the existing tap-to-cycle-cards behavior unchanged; RED takes priority over cycling on
// the (rare) chance both would otherwise apply to the same tap.
function handlePresenceBannerTap() {
  const safeIndex = Math.min(presenceBannerCurrentIndex, presenceBannerCards.length - 1);
  const card = presenceBannerCards[safeIndex];

  if (card && card.status === PRESENCE_RED) {
    const anyOverlayOpen = PRESENCE_BANNER_OVERLAY_IDS.some((id) => !document.getElementById(id).hidden);
    if (getActiveTabName() === "map" && !anyOverlayOpen) {
      return; // Already looking at the map -- nothing to do.
    }
    const previousTab = getActiveTabName();
    PRESENCE_BANNER_OVERLAY_IDS.forEach((id) => {
      document.getElementById(id).hidden = true;
    });
    switchTab("map");
    pushNavLayer("tab:map", () => {
      switchTab(previousTab);
    });
    return;
  }

  if (presenceBannerCards.length < 2) return;
  presenceBannerCurrentIndex = (presenceBannerCurrentIndex + 1) % presenceBannerCards.length;
  renderPresenceBannerCard();
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

    // Item 44: isLoading is distinct from a genuinely-UNKNOWN status -- "never fetched yet" reads
    // as LOADING (presenceBannerLabel, presence.js), not the same "STATUS UNKNOWN" grey a real
    // fetch failure or staleness escalation produces. With initPresenceState() now awaited by
    // app.js's own splash gate, a normal launch never actually reaches this branch at all (the
    // first fetch has already landed by the time the banner can even be shown) -- it's a fallback
    // for the residual case (a fetch failure, or a slower-than-usual network), not the common path.
    let status;
    let isLoading = false;
    if (isKenai) {
      if (!presenceState.hasEverFetchedKenaiPresenceState) {
        isLoading = true;
        status = PRESENCE_UNKNOWN;
      } else {
        status = presenceState.kenaiBelugaStatus;
      }
    } else if (!presenceState.hasEverFetchedWatchedZoneStatuses) {
      isLoading = true;
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
      isLoading,
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

  // Item 45: RED always navigates on tap (handlePresenceBannerTap), independent of card count --
  // .clickable/the chevron reflect that this card specifically goes somewhere, same affordance
  // idea as the existing multi-card cycle indicator (dots) below.
  const isRed = card.status === PRESENCE_RED;
  document.getElementById("presence-banner-chevron").hidden = !isRed;

  const dotsEl = document.getElementById("presence-banner-dots");
  const showChrome = presenceBannerCards.length >= 2;
  mainEl.classList.toggle("clickable", showChrome || isRed);
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
