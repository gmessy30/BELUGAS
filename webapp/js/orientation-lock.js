// Report-tab environment: a non-blocking rotate hint (this file), plus -- separately, see the
// FULLSCREEN section below -- an app-wide fullscreen TOGGLE the user controls explicitly. NOT
// App.kt's LockLandscapeOrientation -- a forced/locked orientation was tried here and removed.
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
// FULLSCREEN, item 77 (current design, after two automatic-entry attempts both caused real bugs):
// entering fullscreen on a TOUCH DEVICE reclaims real screen space from the browser's own address
// bar/chrome -- no native equivalent to port at all (a native app has no browser chrome to
// reclaim space from in the first place). Previously (items 22/67a) this was requested
// AUTOMATICALLY on entering the Report tab, and briefly on entering Map too. Both were removed:
// - Item 76: Map's automatic request, whenever it actually SUCCEEDED (only possible when Map was
//   entered via an actual tap, since fullscreen requires a live user gesture in the call stack --
//   a direct/relaunch entry has none, silently failing there the same way it already silently
//   fails on iOS Safari, which has no real Element.requestFullscreen support at all), hid the
//   body-level #presence-banner (a sibling of #map-view, not a descendant of it) entirely.
// - Item 77: Report's own automatic request had the identical class of problem waiting to happen
//   (gesture-dependent success, silently inconsistent from screen to screen), just not yet
//   symptomatic there since the presence banner is ALREADY deliberately hidden on Report
//   regardless (isReportScreenShowing, presence-banner.js) -- nothing was left for it to visibly
//   break, but the same fragility was still real. Removed for consistency: automatic fullscreen
//   entry is gone from EVERY screen now.
//
// In its place: a single, explicit, user-controlled TOGGLE (menu-fullscreen-toggle-item, wired up
// in initOrientationLock below) that calls document.documentElement.requestFullscreen() --
// fullscreening the WHOLE document, never a page element -- so #presence-banner, the playback
// FAB, and every full-screen overlay (Menu/About/etc., all body-level like the banner) stay
// visible and tappable no matter which tab is showing when it's toggled on, and fullscreen no
// longer needs to be entered/exited on every tab switch at all (lockLandscapeForReportTab/
// lockFullscreenForMapTab/unlockOrientationForOtherTabs below are now ONLY about the rotate hint --
// kept, not fullscreen). The toggle itself is what supplies the required user gesture, so it
// always succeeds when tapped; hidden entirely on non-touch devices, when
// Element.requestFullscreen isn't supported at all (iOS Safari), and in installed/standalone mode
// (matches native: no browser chrome exists there to reclaim in the first place, so there's
// nothing for the toggle to do). The user's on/off choice is persisted (localStorage) and
// re-applied on the next real page load's first user gesture, since browsers require that gesture
// for EVERY fresh requestFullscreen() call -- a stored "was on" preference can't just silently
// re-invoke it the instant the page loads with nothing behind it in the call stack.
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

async function enterFullscreenForTouchDevice() {
  if (!isTouchDevice() || !document.documentElement.requestFullscreen) return;
  try {
    await document.documentElement.requestFullscreen();
  } catch (e) {
    // Expected whenever the browser withholds fullscreen for its own reasons (no direct user
    // gesture in the call stack, etc.) -- never worth surfacing to the user, the app is fully
    // usable without it. The toggle button itself IS a direct gesture, so this only fires for the
    // armFullscreenReentryOnNextGesture() retry path below on a browser that's unusually strict
    // about what counts.
    console.warn("FULLSCREEN_REQUEST_UNAVAILABLE", e);
  }
}

function exitFullscreenIfActive() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch((e) => console.warn("FULLSCREEN_EXIT_ERROR", e));
  }
}

// Names kept for call-site continuity with switchTab (app.js) -- neither of these touches
// fullscreen at all anymore (see this file's own header comment); both are purely the rotate
// hint's own reset/cleanup now.
function lockLandscapeForReportTab() {
  rotateHintDismissedForThisVisit = false; // a fresh hint each time the tab is (re)entered
  updateRotateHint();
}

function lockFullscreenForMapTab() {
  document.getElementById("rotate-hint-banner").hidden = true;
}

function unlockOrientationForOtherTabs() {
  document.getElementById("rotate-hint-banner").hidden = true;
}

// --- Item 77: app-wide fullscreen toggle (see this file's own header comment for the full
// reasoning behind replacing the old per-tab automatic requests with this) ---

const FULLSCREEN_PREFERRED_STORAGE_KEY = "belugas_fullscreen_preferred";

function isStandaloneDisplayMode() {
  // matchMedia covers Android/Chrome's installed-PWA case (manifest.json's own "display":
  // "standalone"); navigator.standalone is the older, Safari-only equivalent property, still the
  // only signal iOS gives for this at all.
  return window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone === true;
}

function isFullscreenActive() {
  return document.fullscreenElement != null;
}

function getFullscreenPreferred() {
  return localStorage.getItem(FULLSCREEN_PREFERRED_STORAGE_KEY) === "1";
}

function setFullscreenPreferred(preferred) {
  if (preferred) {
    localStorage.setItem(FULLSCREEN_PREFERRED_STORAGE_KEY, "1");
  } else {
    localStorage.removeItem(FULLSCREEN_PREFERRED_STORAGE_KEY);
  }
}

// Whether the toggle should exist AT ALL right now: touch devices only (a mouse/keyboard user
// already keeps their own browser chrome exactly how they want it), only where
// Element.requestFullscreen actually exists (iOS Safari doesn't, same gap
// enterFullscreenForTouchDevice above already silently no-ops on), and never in installed/
// standalone mode -- there's no browser chrome there to reclaim in the first place, matching
// native (which has none to begin with either).
function fullscreenToggleShouldExist() {
  return isTouchDevice() && !!document.documentElement.requestFullscreen && !isStandaloneDisplayMode();
}

function updateFullscreenToggleUi() {
  const btn = document.getElementById("menu-fullscreen-toggle-item");
  if (!btn) return;
  const shouldExist = fullscreenToggleShouldExist();
  btn.hidden = !shouldExist;
  if (!shouldExist) return;
  btn.textContent = isFullscreenActive() ? "Exit Fullscreen" : "Enter Fullscreen";
}

// The button tap itself IS the user gesture browsers require, so this always succeeds when
// available (unlike the old automatic per-tab requests, which only sometimes had one). Persists
// the choice either way so armFullscreenReentryOnNextGesture (below) can restore it after the
// next real page load, where fullscreen never survives on its own.
async function toggleFullscreen() {
  if (isFullscreenActive()) {
    setFullscreenPreferred(false);
    exitFullscreenIfActive();
  } else {
    setFullscreenPreferred(true);
    await enterFullscreenForTouchDevice();
  }
  updateFullscreenToggleUi();
}

// Fullscreen never survives a real page (re)load in any browser, and a stored "was on" preference
// can't just silently call requestFullscreen() the instant the page loads -- there's no user
// gesture behind that call at all, so it would just silently fail (the exact FULLSCREEN_REQUEST_
// UNAVAILABLE case above). Instead, arm a ONE-TIME listener for the next actual tap/click
// anywhere in the app and retry then -- that gesture is what makes the retry succeed. Capture
// phase + `once` so it fires on the very first interaction with anything (the splash screen, the
// menu button, whatever's tapped first) and never fires twice.
function armFullscreenReentryOnNextGesture() {
  if (!getFullscreenPreferred() || isFullscreenActive()) return;
  document.addEventListener(
    "pointerdown",
    () => {
      enterFullscreenForTouchDevice().then(updateFullscreenToggleUi);
    },
    { capture: true, once: true }
  );
}

function initOrientationLock() {
  if (orientationChangeListenerAttached) return;
  window.matchMedia("(orientation: portrait)").addEventListener("change", updateRotateHint);
  document.getElementById("rotate-hint-dismiss-btn").addEventListener("click", dismissRotateHint);
  orientationChangeListenerAttached = true;

  const fullscreenToggleBtn = document.getElementById("menu-fullscreen-toggle-item");
  if (fullscreenToggleBtn) fullscreenToggleBtn.addEventListener("click", toggleFullscreen);
  // Keeps the button's own label in sync however fullscreen state actually changes -- the toggle
  // itself, the browser's own built-in "exit fullscreen" affordance, a hardware back press, etc.
  // all fire this the same way.
  document.addEventListener("fullscreenchange", updateFullscreenToggleUi);
  window.matchMedia("(display-mode: standalone)").addEventListener("change", updateFullscreenToggleUi);
  updateFullscreenToggleUi();
  armFullscreenReentryOnNextGesture();
}
