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

document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll(".tab-button").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  initMap();
  initSubmitView();
  refreshSightings();

  if ("serviceWorker" in navigator) {
    // Relative path, not "/sw.js" -- this app is served from a subpath (GitHub Pages project
    // page, e.g. /BELUGAS/webapp/), and an absolute path would register against the wrong scope.
    navigator.serviceWorker.register("sw.js").catch((e) => console.error("SW_REGISTER_ERROR", e));
  }
});
