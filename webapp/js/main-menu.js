// Full-screen navigation menu -- ports App.kt's MainMenuDrawer directly, replacing this app's
// earlier bottom tab bar (which had no native equivalent at all: MainMenuDrawer, reached via a
// "≡ MENU" button on CaptureScreen.kt, IS the native app's actual primary navigation -- a
// full-screen column of bold uppercase white text items over the teal/art backdrop, not a
// persistent bottom bar).
//
// Item set/order/wording here mirrors native's own menu order exactly: Report Sighting (CAMERA),
// Report Manually (REPORT MANUALLY -- opens ManualLoggingScreen.kt's port directly, see
// openManualReportFlow in submit-view.js), Sightings List, Sightings Map, News Feed, Resources,
// About, Alerts, in that same relative order. EXPORT DATA has no web equivalent yet, so it's
// just omitted rather than reordering what's left. "About" opens the
// real AboutScreen.kt port (about.js) -- the tier-code modal has no menu entry at all, matching
// native: it's reached only via the hidden nose-tap gesture on that page. "Alerts" opens the real
// SubscriptionsScreen.kt port (subscriptions.js) -- native's own menu item is also literally
// labeled "ALERTS" even though the screen underneath is titled "ALERTS" too (SubscriptionsScreen
// itself), so the wording matches on both counts. "Close" has no native equivalent either
// (Android back/gesture nav dismisses MainMenuDrawer natively); styled after AboutScreen.kt's own
// "← BACK" convention (yellow, bold) since that's the app's real dismiss-this-screen idiom, just
// reused here.
function initMainMenu() {
  const menu = document.getElementById("main-menu");

  // Two triggers share this class: the outer header's button (Map/List) and CaptureScreen's own
  // in-context one (native's CaptureScreen has its own "≡ MENU" button drawn over the camera
  // feed, not a separate app-wide header -- see switchTab in app.js, which hides the outer
  // header entirely while the camera/review steps are showing).
  document.querySelectorAll(".menu-trigger-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      menu.hidden = false;
    });
  });

  document.getElementById("menu-close-item").addEventListener("click", () => {
    menu.hidden = true;
  });

  // "About" shares the .menu-nav-item class for its text styling but isn't a switchTab()
  // destination (it opens the About page instead, wired below) -- scoped to [data-nav]
  // specifically so it doesn't also pick up this generic handler.
  document.querySelectorAll(".menu-nav-item[data-nav]").forEach((item) => {
    item.addEventListener("click", () => {
      switchTab(item.dataset.nav);
      menu.hidden = true;
    });
  });

  document.getElementById("menu-report-manually-item").addEventListener("click", () => {
    menu.hidden = true;
    openManualReportFlow();
  });

  document.getElementById("menu-news-feed-item").addEventListener("click", () => {
    menu.hidden = true;
    openNewsFeedPage();
  });

  document.getElementById("menu-resources-item").addEventListener("click", () => {
    menu.hidden = true;
    openResourcesPage();
  });

  document.getElementById("menu-about-item").addEventListener("click", () => {
    menu.hidden = true;
    openAboutPage();
  });

  document.getElementById("menu-alerts-item").addEventListener("click", () => {
    menu.hidden = true;
    openAlertsPage();
  });
}
