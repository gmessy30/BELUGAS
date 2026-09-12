// Full-screen navigation menu -- ports App.kt's MainMenuDrawer directly, replacing this app's
// earlier bottom tab bar (which had no native equivalent at all: MainMenuDrawer, reached via a
// "≡ MENU" button on CaptureScreen.kt, IS the native app's actual primary navigation -- a
// full-screen column of bold uppercase white text items over the teal/art backdrop, not a
// persistent bottom bar).
//
// Item set/order/wording here is adapted, not a verbatim copy of native's own 9-item menu (this
// app only has three real destinations): "Sightings Map"/"Sightings List" reuse native's exact
// wording; "Report Sighting" stands in for native's separate CAMERA + REPORT MANUALLY items
// (this app's one submit flow covers both); "Observer Code" has no native menu entry at all --
// natively it's a hidden 7-tap gesture on the About screen -- surfaced here as a visible item
// instead, per the brief. "Close" has no native equivalent either (Android back/gesture nav
// dismisses MainMenuDrawer natively); styled after AboutScreen.kt's own "← BACK" convention
// (yellow, bold) since that's the app's real dismiss-this-screen idiom, just reused here.
function initMainMenu() {
  const menu = document.getElementById("main-menu");
  const openBtn = document.getElementById("menu-open-btn");

  openBtn.addEventListener("click", () => {
    menu.hidden = false;
  });

  document.getElementById("menu-close-item").addEventListener("click", () => {
    menu.hidden = true;
  });

  document.querySelectorAll(".menu-nav-item").forEach((item) => {
    item.addEventListener("click", () => {
      switchTab(item.dataset.nav);
      menu.hidden = true;
    });
  });

  document.getElementById("menu-observer-code-item").addEventListener("click", () => {
    menu.hidden = true;
    openTierCodeModal();
  });
}
