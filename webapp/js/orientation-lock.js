// NOT App.kt's LockLandscapeOrientation -- deliberately not ported, and removed after being tried.
// screen.orientation.lock("landscape") turned out to be a genuine LOCK on Android/Chrome (auto-
// rotation disabled entirely, frozen at whichever landscape variant was current the instant it
// resolved), not a live sensor-following choice between landscape-primary/secondary the way
// native's own SCREEN_ORIENTATION_SENSOR_LANDSCAPE is -- if the user physically held the phone in
// the OTHER landscape variant than whatever got locked, the screen rendered upside down relative
// to how they were actually holding it. On top of that, a layout built assuming one single locked
// orientation left Report Manually's own controls (SELF/OTHER, whale count, RECENTER, SUBMIT)
// unreachable once that assumption didn't hold. Removing the lock removes both failure modes at
// their real source, rather than patching around either one -- see the CSS for #camera-step/
// #review-step/#manual-log-step, which now lay out correctly in EITHER orientation instead of
// assuming landscape-only.
//
// All that's left here is a small, non-blocking, dismissible hint ("Rotate for a better view") on
// phone-sized touch devices while the Report tab is in portrait -- never a barrier, and never
// shown on a tablet/desktop-class screen where portrait is perfectly usable there too.

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

// Names kept for call-site continuity with switchTab (app.js) -- neither locks/unlocks anything
// any more, only shows/hides the hint banner for as long as the Report tab is the active one.
function lockLandscapeForReportTab() {
  rotateHintDismissedForThisVisit = false; // a fresh hint each time the tab is (re)entered
  updateRotateHint();
}

function unlockOrientationForOtherTabs() {
  document.getElementById("rotate-hint-banner").hidden = true;
}

function initOrientationLock() {
  if (orientationChangeListenerAttached) return;
  window.matchMedia("(orientation: portrait)").addEventListener("change", updateRotateHint);
  document.getElementById("rotate-hint-dismiss-btn").addEventListener("click", dismissRotateHint);
  orientationChangeListenerAttached = true;
}
