// Report-tab environment: fullscreen + a non-blocking rotate hint. NOT App.kt's
// LockLandscapeOrientation -- a forced/locked orientation was tried here and removed.
// screen.orientation.lock("landscape") turned out to be a genuine LOCK on Android/Chrome (auto-
// rotation disabled entirely, frozen at whichever landscape variant was current the instant it
// resolved), not a live sensor-following choice between landscape-primary/secondary the way
// native's own SCREEN_ORIENTATION_SENSOR_LANDSCAPE is -- if the user physically held the phone in
// the OTHER landscape variant than whatever got locked, the screen rendered upside down relative
// to how they were actually holding it. On top of that, a layout built assuming one single locked
// orientation left Report Manually's own controls (SELF/OTHER, whale count, RECENTER, SUBMIT)
// unreachable once that assumption didn't hold. Removing the lock removes both failure modes at
// their real source, rather than patching around either one -- see the CSS for #camera-step/
// #manual-log-step, which now lay out correctly in EITHER orientation instead of assuming
// landscape-only.
//
// FULLSCREEN: entering the Report tab requests fullscreen on touch devices, reclaiming the space
// the browser's own address bar/chrome would otherwise take up -- no native equivalent to port at
// all (a native app has no browser chrome to reclaim space from in the first place). Best-effort
// and wrapped in try/catch: iOS Safari does not support Element.requestFullscreen on most
// elements (historically only <video> via a WebKit-specific API), so this silently no-ops there --
// see style.css's own 100dvh sizing for #camera-step/#manual-log-step, which is what actually
// reclaims that space on iPhone instead.
//
// Item 67a gave Sightings Map the same fullscreen request (lockFullscreenForMapTab/app.js's
// switchTab). Item 76 REMOVED it again: whenever that request actually succeeded, the body-level
// #presence-banner (a sibling of #map-view, not a descendant of it) rendered nowhere at all.
// Confirmed by the exact repro pattern: identical code, banner visible when Map is the launch
// screen (no user gesture in that call stack, so the fullscreen request silently fails there, the
// same way it already silently fails on iOS Safari above), invisible whenever Map is entered by
// an actual tap (Menu -> Map IS a user gesture, so the request succeeds there instead). Map's own
// 100dvh sizing (style.css) already reclaims the same browser-chrome space fullscreen was trying
// to, without this failure mode, so the request itself is gone rather than chased further -- see
// lockFullscreenForMapTab below. Report's own fullscreen request above is UNCHANGED: the presence
// banner is already deliberately hidden on Report regardless of fullscreen (isReportScreenShowing,
// presence-banner.js), so there's nothing for this same failure mode to break there.
//
// ROTATE HINT: a small, non-blocking, dismissible hint ("Rotate for a better view") on phone-sized
// touch devices while the Report tab is in portrait -- never a barrier, and never shown on a
// tablet/desktop-class screen where portrait is perfectly usable there too.

let orientationChangeListenerAttached = false;
let rotateHintDismissedForThisVisit = false;

function isPortraitOrientation() {
  return window.matchMedia("(orientation: portrait)").matches;
}

// "Phone-sized" -- a tablet has plenty of room to lay either orientation out comfortably, so the
// hint would just be noise there. Touch-based (not pointer:fine) since a phone is the only device
// this hint is meant for; a small emulated/resized desktop browser window has a mouse, not a
// thumb to rotate.
function isPhoneSizedTouchDevice() {
  const hasCoarsePointer = window.matchMedia("(pointer: coarse)").matches;
  const isPhoneSized = Math.min(window.innerWidth, window.innerHeight) < 600;
  return hasCoarsePointer && isPhoneSized;
}

function isTouchDevice() {
  return "ontouchstart" in window || navigator.maxTouchPoints > 0;
}

function updateRotateHint() {
  const banner = document.getElementById("rotate-hint-banner");
  const reportTabVisible = !document.getElementById("submit-view").hidden;
  banner.hidden = !(
    reportTabVisible && isPortraitOrientation() && isPhoneSizedTouchDevice() && !rotateHintDismissedForThisVisit
  );
}

function dismissRotateHint() {
  rotateHintDismissedForThisVisit = true;
  updateRotateHint();
}

// Item 67a: generalized from enterFullscreenForReportTab/exitFullscreenForReportTab so Report and
// (briefly, until item 76 reverted it -- see this file's own header comment) Map could share one
// implementation; kept generalized since exitFullscreenIfActive below still needs to be callable
// regardless of which tab entered fullscreen in the first place.
async function enterFullscreenForTouchDevice() {
  if (!isTouchDevice() || !document.documentElement.requestFullscreen) return;
  try {
    await document.documentElement.requestFullscreen();
  } catch (e) {
    // Expected on iOS Safari (no real support here) and whenever the browser withholds
    // fullscreen for its own reasons (no direct user gesture in the call stack, etc.) -- never
    // worth surfacing to the user, the app is fully usable without it.
    console.warn("FULLSCREEN_REQUEST_UNAVAILABLE", e);
  }
}

function exitFullscreenIfActive() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch((e) => console.warn("FULLSCREEN_EXIT_ERROR", e));
  }
}

// Names kept for call-site continuity with switchTab (app.js).
function lockLandscapeForReportTab() {
  rotateHintDismissedForThisVisit = false; // a fresh hint each time the tab is (re)entered
  updateRotateHint();
  enterFullscreenForTouchDevice();
}

// Item 67a originally requested fullscreen here too (same reasoning as the Report tab: reclaim
// the browser's own address-bar space, no native equivalent needed since a native app has no
// browser chrome to begin with). Item 76 removed that request -- see this file's own header
// comment for the full repro/reasoning -- leaving only the rotate-hint cleanup (Map never showed
// the hint, but switching tabs FROM Report doesn't otherwise clear a hint left dismissed-then-
// re-triggered mid-transition) and #map-view's own 100dvh sizing (style.css) to reclaim that
// space instead.
function lockFullscreenForMapTab() {
  document.getElementById("rotate-hint-banner").hidden = true;
  // Fullscreen is tied to the whole document, not any one tab -- switchTab (app.js) routes
  // Report -> Map straight into this function, never through unlockOrientationForOtherTabs, so if
  // Report had just entered fullscreen (lockLandscapeForReportTab) it would otherwise stay active
  // across that switch with nothing here to end it, silently bringing back the exact bug this
  // item removed the Map-side REQUEST for. Map must never be shown with fullscreen active,
  // regardless of which tab was active immediately before it.
  exitFullscreenIfActive();
}

function unlockOrientationForOtherTabs() {
  document.getElementById("rotate-hint-banner").hidden = true;
  exitFullscreenIfActive();
}

function initOrientationLock() {
  if (orientationChangeListenerAttached) return;
  window.matchMedia("(orientation: portrait)").addEventListener("change", updateRotateHint);
  document.getElementById("rotate-hint-dismiss-btn").addEventListener("click", dismissRotateHint);
  orientationChangeListenerAttached = true;
}
