// Tab switching + top-level init/refresh. Kept deliberately dumb -- three plain views toggled
// by hiding/showing, no router/framework, matching the "framework-free, fast to build and debug
// this weekend" brief.
let latestSightings = [];

async function refreshSightings() {
  latestSightings = await fetchRecentSightings();
  renderSightingsOnMap(latestSightings);
  renderSightingsList(latestSightings);
}

function switchTab(tabName) {
  document.querySelectorAll(".view").forEach((el) => (el.hidden = el.id !== `${tabName}-view`));
  document.querySelectorAll(".tab-button").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.tab === tabName);
  });

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
  document.querySelectorAll(".tab-button").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  initMap();
  initSubmitView();
  initTierCodeModal();
  initOfflineQueue();

  const minDelay = new Promise((resolve) => setTimeout(resolve, SPLASH_MIN_DISPLAY_MS));
  await Promise.all([minDelay, refreshSightings()]);
  hideSplash();

  if ("serviceWorker" in navigator) {
    // Relative path, not "/sw.js" -- this app is served from a subpath (GitHub Pages project
    // page, e.g. /BELUGAS/webapp/), and an absolute path would register against the wrong scope.
    navigator.serviceWorker.register("sw.js").catch((e) => console.error("SW_REGISTER_ERROR", e));
  }
});
