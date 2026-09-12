// "OPEN APP TO" launch-screen preference -- ports MainMenuDrawer's own FilterChip row (App.kt)
// and AppPreferences.getLaunchScreen()/setLaunchScreen(), stored in localStorage instead of
// SharedPreferences/NSUserDefaults.
//
// DEFAULT ON FIRST LAUNCH IS A DELIBERATE DEVIATION FROM NATIVE: AppPreferences.android.kt/
// AppPreferences.ios.kt both fall back to LaunchScreen.CAMERA when nothing is stored ("Camera is
// the pre-existing default behavior" per App.kt's own comment) -- confirmed directly in source,
// not assumed. This app defaults to MENU instead, per explicit direction given that discrepancy,
// not because native actually does.
//
// DESKTOP-CLASS OVERRIDE: a saved CAMERA preference is ignored on a fine-pointer, no-touch device
// (an ordinary laptop/desktop browser) -- falls back to Map instead, since a live camera step
// makes little sense there. Native has no equivalent concept at all (every native device is a
// phone/tablet, always with a camera).
const LAUNCH_SCREEN_STORAGE_KEY = "belugas_launch_screen";
const LAUNCH_SCREEN_OPTIONS = ["MENU", "MAP", "CAMERA"];
const LAUNCH_SCREEN_DEFAULT = "MENU";

function getLaunchScreenPreference() {
  const stored = localStorage.getItem(LAUNCH_SCREEN_STORAGE_KEY);
  return LAUNCH_SCREEN_OPTIONS.includes(stored) ? stored : LAUNCH_SCREEN_DEFAULT;
}

function setLaunchScreenPreference(value) {
  localStorage.setItem(LAUNCH_SCREEN_STORAGE_KEY, value);
}

function isDesktopClassDevice() {
  const hasFinePointer = window.matchMedia("(pointer: fine)").matches;
  const hasNoTouch = navigator.maxTouchPoints === 0 && !("ontouchstart" in window);
  return hasFinePointer && hasNoTouch;
}

function initLaunchScreenPicker() {
  const current = getLaunchScreenPreference();
  const chips = document.querySelectorAll("[data-launch-screen]");
  chips.forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.launchScreen === current);
    btn.addEventListener("click", () => {
      setLaunchScreenPreference(btn.dataset.launchScreen);
      chips.forEach((b) => b.classList.toggle("active", b === btn));
    });
  });
}

// Called once at startup (app.js's DOMContentLoaded, after every other init has wired its own
// handlers) -- resolves straight to whatever nav-stack layer the chosen launch screen implies,
// exactly as if the user had just tapped the equivalent menu action themselves (see nav-stack.js/
// main-menu.js), so a back gesture from here behaves identically either way. MAP (and CAMERA
// falling back to it on a desktop-class device) needs no action at all -- Map is already the
// default resting .view (no `hidden` attribute on #map-view in index.html).
function resolveLaunchScreen() {
  const preference = getLaunchScreenPreference();

  if (preference === "MENU") {
    const menu = document.getElementById("main-menu");
    menu.hidden = false;
    pushNavLayer("menu", () => { menu.hidden = true; });
    return;
  }

  if (preference === "CAMERA" && !isDesktopClassDevice()) {
    switchTab("submit");
    pushNavLayer("tab:submit", () => switchTab("map"));
  }
}
