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

// Item 45: full-screen overlays the banner (z-index 970) renders above -- if it's tapped while
// one of these is open, that overlay has to be closed too, not just left covering the map
// underneath after the tab switch. At most one is ever open at once in practice (a subpage is
// only ever reached via the menu, which hides itself first -- see main-menu.js).
const PRESENCE_BANNER_OVERLAY_IDS = [
  "main-menu", "resources-page", "news-feed-page", "about-page", "alerts-page", "share-page"
];

// Item 58 REGRESSION FIX: this used to be a manually-toggled `isReportTabActive` flag, flipped
// only by app.js's switchTab -- correct as long as EVERY way in or out of the Report tab went
// through switchTab, which stopped being true the moment main-menu.js's generic .menu-trigger-btn
// listener (CaptureScreen's own in-context "≡ MENU" button, not just the header's) started
// showing the #main-menu OVERLAY on top of the submit tab without ever calling switchTab: the flag
// stayed stuck true, so the banner never came back once the menu -- not Camera -- was what's
// actually on screen, and nothing was left to notice. Computed fresh from the DOM instead: the
// banner is hidden only while the Report tab is the ACTUAL foreground screen, not merely the
// .view underneath something else -- the menu and every other full-screen overlay
// (PRESENCE_BANNER_OVERLAY_IDS) sit on top of whichever tab and are themselves valid
// banner-visible screens regardless of what's hidden under them (matches App.kt's
// showPresenceBanner exclusion: CAPTURE/PHOTO_LOGGING/MANUAL_LOGGING/ACKNOWLEDGEMENT_GATE only).
function isReportScreenShowing() {
  const anyOverlayOpen = PRESENCE_BANNER_OVERLAY_IDS.some((id) => !document.getElementById(id).hidden);
  if (anyOverlayOpen) return false;
  return !document.getElementById("submit-view").hidden;
}

function initPresenceBanner() {
  document.getElementById("presence-banner-main").addEventListener("click", handlePresenceBannerTap);

  onPresenceStateChanged(rebuildPresenceBannerCards);
  rebuildPresenceBannerCards();

  // Rather than chasing down every place that shows/hides the menu or a full-screen page and
  // pairing it with a manual refresh call (the exact kind of pairing that just drifted out of
  // sync above), watch the real `hidden` attribute on every screen isReportScreenShowing reads
  // and re-evaluate straight from live DOM state on ANY change. Structurally can't drift again --
  // there's no separate flag left to forget to flip. Scoped to exactly these elements rather than
  // `document.body` with subtree:true -- this function's OWN writes below (banner/dots/warnings
  // .hidden) live in that same subtree, and a subtree-wide observer would re-trigger itself on
  // every render, forever.
  const bannerVisibilityObserver = new MutationObserver(() => renderPresenceBannerCard());
  [...PRESENCE_BANNER_OVERLAY_IDS, "map-view", "list-view", "submit-view"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) bannerVisibilityObserver.observe(el, { attributes: true, attributeFilter: ["hidden"] });
  });
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

  if (isReportScreenShowing() || presenceBannerCards.length === 0) {
    banner.hidden = true;
    return;
  }
  banner.hidden = false;

  const safeIndex = Math.min(presenceBannerCurrentIndex, presenceBannerCards.length - 1);
  const card = presenceBannerCards[safeIndex];
  const color = colorForBelugaPresenceStatus(card.status);

  // Item 53: YELLOW is now the same true --brand-yellow (#FFFF00) the rest of this app uses --
  // white text/dots (fine against RED/BLUE's own darker fills) read as barely-there against pure
  // yellow, so .on-yellow (style.css) flips them to black, matching the black-on-yellow
  // convention .chip-toggle.active/native's own yellow FilterChips already use. Toggled on
  // #presence-banner itself, not -main, since -dots is -main's own sibling, not a descendant --
  // both need to flip together.
  banner.classList.toggle("on-yellow", card.status === PRESENCE_YELLOW);

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
