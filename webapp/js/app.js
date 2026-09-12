// Tab switching + top-level init/refresh. Kept deliberately dumb -- three plain views toggled
// by hiding/showing, no router/framework, matching the "framework-free, fast to build and debug
// this weekend" brief.
let latestRemoteSightings = [];

async function refreshSightings() {
  latestRemoteSightings = await fetchRecentSightings();
  await renderAllSightings();
}

// Combines this device's own offline queue with the last remote fetch and pushes the result to
// both views -- matches OfflineSightingsList's own "QUEUED FOR SYNC" + "REMOTE DATABASE" split
// (App.kt), shown together rather than the queue being invisible until it syncs. Called after
// every remote refresh AND, from offline-queue.js, whenever the queue itself changes (a new
// sighting gets queued, or a queued item's photo finishes uploading) -- those don't need a
// network round trip, just a re-render of already-known data.
async function renderAllSightings() {
  const queued = await getQueuedSightingsAsRecords();
  renderSightingsOnMap([...queued, ...latestRemoteSightings]);
  renderSightingsList(queued, latestRemoteSightings);
}

function switchTab(tabName) {
  document.querySelectorAll(".view").forEach((el) => (el.hidden = el.id !== `${tabName}-view`));

  // CaptureScreen.kt/LoggingScreen.kt (the native screens behind the Report tab) never show any
  // shared app chrome at all -- each draws its own full-bleed content with its own top row. This
  // app's outer teal header/queue-banner are a web-only adaptation for Map/List, so they're
  // hidden here to let the camera/review steps go truly full-bleed the same way natively.
  const isReportTab = tabName === "submit";
  document.querySelector("header").hidden = isReportTab;
  if (isReportTab) {
    document.getElementById("queue-banner").hidden = true;
  } else {
    refreshQueueBadge();
  }
  // Matches App.kt's showPresenceBanner exclusion (CAPTURE/PHOTO_LOGGING/MANUAL_LOGGING/
  // ACKNOWLEDGEMENT_GATE) -- this tab covers the camera+logging equivalent.
  setPresenceBannerReportTabActive(isReportTab);

  if (tabName === "map") {
    invalidateMapSize();
  }
}

// Matches SPLASH_ICON_ONLY_DURATION_MS in App.kt -- the native splash's fixed minimum hold
// before the background artwork fades in/the app becomes interactive. Kept as a MINIMUM here
// (raced against the initial sightings fetch below), not an added-on-top delay, so a slow
// network doesn't extend the wait further than it already has to.
const SPLASH_MIN_DISPLAY_MS = 1200;

function hideSplash() {
  const splash = document.getElementById("splash-screen");
  if (!splash) return;
  splash.classList.add("splash-hidden");
  setTimeout(() => { splash.hidden = true; }, 400);
}

document.addEventListener("DOMContentLoaded", async () => {
  initMap();
  initListView();
  initSubmitView();
  initTierCodeModal();
  initAboutPage();
  initResourcesPage();
  initNewsFeedPage();
  initAlertsPage();
  initPresenceBanner();
  initMainMenu();
  initOfflineQueue();
  initPresenceState();

  const minDelay = new Promise((resolve) => setTimeout(resolve, SPLASH_MIN_DISPLAY_MS));
  await Promise.all([minDelay, refreshSightings()]);
  hideSplash();

  if ("serviceWorker" in navigator) {
    // Relative path, not "/sw.js" -- this app is served from a subpath (GitHub Pages project
    // page, e.g. /BELUGAS/webapp/), and an absolute path would register against the wrong scope.
    //
    // updateViaCache: "none" -- GitHub Pages serves static files with ordinary HTTP caching
    // headers we have no control over, and without this option the browser's update check can
    // be satisfied straight out of its own HTTP cache, never noticing sw.js changed on the
    // server at all. This forces every update check to actually hit the network for sw.js.
    navigator.serviceWorker
      .register("sw.js", { updateViaCache: "none" })
      .catch((e) => console.error("SW_REGISTER_ERROR", e));

    // sw.js's skipWaiting()/clients.claim() get a new worker into control immediately, but a
    // tab that was already open is still running the OLD index.html/js/css it loaded with --
    // taking control of future fetches doesn't retroactively change what's already executing.
    // Reloading once the controller actually changes is what makes a new deploy visibly take
    // over instead of requiring the user to fully close and reopen the app.
    let hasReloadedForNewServiceWorker = false;
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      if (hasReloadedForNewServiceWorker) return;
      hasReloadedForNewServiceWorker = true;
      window.location.reload();
    });
  }
});
