// Forces landscape while the Report tab is active -- matches App.kt's wantsLandscape exactly:
// native computes ONE combined lock spanning CAPTURE + PHOTO_LOGGING + MANUAL_LOGGING (the same
// three screens WhaleCountRow lives on) rather than one lock per sub-screen, so moving directly
// between the camera step and the review step doesn't release-then-reacquire and flicker back
// toward portrait for a frame -- this app's Report tab covers exactly that same span (both
// sub-steps, both camera-path and manual-path), so it's locked/unlocked once per tab visit here,
// not per sub-step.
//
// Android/Chrome: a real screen.orientation.lock() attempt, best-effort and wrapped in try/catch
// -- it throws/rejects outside a fullscreen or installed-PWA context (a plain browser tab has no
// equivalent of native's unconditional Activity.requestedOrientation override; this is the real
// ceiling of what a web page can force). iOS Safari has no lock API at all, so a full-screen
// "Rotate your device" overlay stands in for the lock there. That overlay isn't actually gated to
// iOS in code -- it's driven by the device's REAL current orientation (matchMedia), shown
// whenever the Report tab is active and the device is still in portrait regardless of platform.
// A failed/unsupported lock() on Android leaves the device in exactly the same "still portrait"
// state iOS is always in, so a non-installed Android Chrome tab gets the same helpful nudge
// instead of silently rendering a cramped portrait-shaped layout with no explanation.
let orientationChangeListenerAttached = false;

function isPortraitOrientation() {
  return window.matchMedia("(orientation: portrait)").matches;
}

async function lockLandscapeForReportTab() {
  if (screen.orientation && screen.orientation.lock) {
    try {
      await screen.orientation.lock("landscape");
    } catch (e) {
      // Expected/common outside fullscreen or an installed PWA -- not an error worth surfacing
      // to the user, the rotate-overlay below is exactly the fallback for this case.
      console.warn("ORIENTATION_LOCK_UNAVAILABLE", e);
    }
  }
  updateRotateOverlay();
}

function unlockOrientationForOtherTabs() {
  if (screen.orientation && screen.orientation.unlock) {
    try {
      screen.orientation.unlock();
    } catch (e) {
      console.warn("ORIENTATION_UNLOCK_ERROR", e);
    }
  }
  document.getElementById("rotate-device-overlay").hidden = true;
}

function updateRotateOverlay() {
  const overlay = document.getElementById("rotate-device-overlay");
  const reportTabVisible = !document.getElementById("submit-view").hidden;
  overlay.hidden = !(reportTabVisible && isPortraitOrientation());
}

// Registered once, globally -- cheap to leave attached even while the Report tab isn't active,
// since updateRotateOverlay's own reportTabVisible check makes it a no-op (overlay stays hidden)
// the rest of the time.
function initOrientationLock() {
  if (orientationChangeListenerAttached) return;
  window.matchMedia("(orientation: portrait)").addEventListener("change", updateRotateOverlay);
  orientationChangeListenerAttached = true;
}
