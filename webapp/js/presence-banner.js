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
// ACKNOWLEDGEMENT_GATE. Otherwise it's a fixed bottom overlay above the menu/About/Resources/
// News Feed/Alerts/Share, same as native drawing it in the same Box as whatever screen is
// current -- native reserves bottom padding on every AppBackground screen equal to the banner's
// own measured height (LocalBottomContentInset) so it never covers their bottommost content;
// this app never reproduced that for those screens (flagged as a known simplification, not an
// oversight -- "other pages can stay as-is" per item 75).
//
// Map and List are the exception, and have been through two failed measured-offset attempts
// (67b's --presence-banner-height CSS var, 74's landscape max-height tweak) before item 75:
// both looked correct in source and both still failed on a real device (the playback FAB fully
// hidden in portrait, mostly hidden in landscape; the open panel's own bottom cut off under the
// banner in landscape). Item 75 replaces measuring entirely -- see
// presenceBannerInFlowContainerId/updatePresenceBannerParent below -- by making the banner a
// genuine flex sibling below #map-canvas/.list-canvas whenever Map or List is the topmost
// screen, so its own bottom edge (and everything anchored to it) sits above the banner BY
// CONSTRUCTION, no measurement of anything involved.
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
function isAnyPresenceBannerOverlayOpen() {
  return PRESENCE_BANNER_OVERLAY_IDS.some((id) => !document.getElementById(id).hidden);
}

function isReportScreenShowing() {
  if (isAnyPresenceBannerOverlayOpen()) return false;
  return !document.getElementById("submit-view").hidden;
}

// Item 75: which element the banner should be reparented INTO right now, or null if it should
// sit back at its original position (document.body) as a plain position:fixed overlay. Only Map
// and List ever return a container here -- every other topmost screen (an overlay page, the
// menu, or the Report tab) keeps the original fixed-overlay behavior untouched, matching "other
// pages can stay as-is."
//
// Explicitly re-checks isAnyPresenceBannerOverlayOpen() here, not just isReportScreenShowing():
// getActiveTabName() only looks at which .view is un-hidden underneath, with no idea whether an
// overlay (About, Menu, Resources, ...) is currently drawn on top of it. isReportScreenShowing()
// deliberately returns false in that case (the banner must still SHOW, unlike over the Report
// tab) -- but showing it doesn't mean reparenting it into the now-visually-covered map/list
// canvas is safe: those overlays' own z-index (900-960) sits BELOW the fixed banner's 970, so a
// banner still living at document.body renders correctly above them same as always, but one
// moved inside #map-canvas/.list-canvas would be trapped under that container's own stacking
// context and disappear behind the overlay instead. So: overlay open -> stay fixed, full stop.
function presenceBannerInFlowContainerId() {
  if (isReportScreenShowing() || isAnyPresenceBannerOverlayOpen()) return null;
  const activeTab = getActiveTabName();
  if (activeTab === "map") return "map-canvas";
  if (activeTab === "list") return "list-canvas";
  return null;
}

// Moves the banner element itself (not a clone -- appendChild on a node already in the DOM
// relocates it, keeping every event listener/bound reference intact) to sit as the last flex
// child of #map-canvas/.list-canvas, or back to <body> (its original position:fixed home) once
// neither applies. The .in-flow class (style.css) is what actually swaps its own CSS from fixed-
// overlay to a plain flex sibling; this function just decides which one should be true right now
// and moves the DOM node to match.
function updatePresenceBannerParent() {
  const banner = document.getElementById("presence-banner");
  const targetId = presenceBannerInFlowContainerId();
  const target = targetId ? document.getElementById(targetId) : null;

  if (target) {
    if (banner.parentElement !== target) target.appendChild(banner);
    banner.classList.add("in-flow");
  } else {
    if (banner.parentElement !== document.body) document.body.appendChild(banner);
    banner.classList.remove("in-flow");
  }

  // Map's own flex-item share of the screen (#map-canvas) changes size whenever the banner
  // joins/leaves it as a sibling, or flips hidden while already there (a routine presence-state
  // refresh) -- Leaflet has no way to notice that on its own, unlike a plain CSS reflow. A no-op
  // call whenever nothing actually changed is harmless (Leaflet's own invalidateSize() is cheap),
  // so this doesn't try to track whether the size genuinely changed since last time.
  if (targetId === "map-canvas" && typeof invalidateMapSize === "function") {
    requestAnimationFrame(invalidateMapSize);
  }
}

// Item 74: called from map-view.js while the Map's playback panel is open/closes, on a short
// landscape viewport where every bit of vertical room matters -- collapses the banner to one
// compact line (style.css's own .compact rule, landscape-only) so its real height shrinks.
// Since item 75 made the banner a genuine flex sibling of #map-canvas while Map is showing,
// that's an immediate, live CSS reflow giving .map-canvas (and the playback panel/FAB inside it)
// back that same space -- no separate propagation step needed. A no-op in portrait (or anywhere
// else the class has no matching CSS rule), so this is safe to call unconditionally regardless
// of orientation/screen.
function setPresenceBannerCompact(isCompact) {
  document.getElementById("presence-banner").classList.toggle("compact", isCompact);
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
    if (getActiveTabName() === "map" && !isAnyPresenceBannerOverlayOpen()) {
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
  // Item 75: keeps the banner correctly parented (in-flow under Map/List, or back at its
  // original fixed-overlay home everywhere else) regardless of whether it ends up hidden or
  // shown below -- so it's already in the right place the instant a card actually exists, with
  // no separate reparent-on-first-show step needed.
  updatePresenceBannerParent();

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
