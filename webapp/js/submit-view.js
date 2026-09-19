// Camera tab (native's own menu label -- App.kt's MainMenuDrawer).
//
// ITEM 60 DESIGN CHANGE: automatic whale placement (a heading+distance projection outward from
// the observer's own GPS fix) kept landing whales on land -- a GPS fix's own error plus an
// estimated bearing/distance compounds fast at these distances. Both reporting paths now place
// the whale by human map placement instead: capturing a photo (or skipping the camera entirely)
// goes STRAIGHT into the same map+crosshair+BearingDial screen ManualLoggingScreen.kt/
// #manual-log-step already used for "Report Manually", with the photo (if any) attached to the
// eventual submission -- there is no more separate LoggingScreen-style review step, no heading/
// distance picker, and no CoastlineGeometry offshore-guess fallback (none of that math exists in
// this app any more, camera path included).
//
// The one thing the camera path does that plain "Report Manually" deliberately still doesn't: it
// takes a best-effort GPS fix at capture time and uses it ONLY to center the map initially (see
// enterManualLogStepFromCamera/centerManualMapFromGpsOnce below) -- so a user coming from the
// camera starts looking at roughly the right area instead of the region-wide default view. That
// fix is never stored and never the submitted position; the submitted position is always
// whatever the crosshair/map center reads at SUBMIT time, exactly like every other entry into
// this screen.
//
// NATIVE PARITY: NOT yet applied to the Kotlin app (see CLAUDE.md's own pending-parity list) --
// this is a web-only change for now, applied to native in a later pass alongside every other
// pending parity item, then rebuilt for both platforms.

let cameraStream = null;
let capturedPhotoBlob = null;
let reviewPhotoObjectUrl = null;

// --- Item 55: camera zoom (CameraPreviewHost.android.kt's zoom slider) ---
// Native always shows this slider (CameraX exposes zoom on every device it targets); the web
// Media Capture API doesn't make the same guarantee (MediaStreamTrack.getCapabilities().zoom is
// unsupported on plenty of devices, notably iOS Safari as of this writing). Slider is still
// always shown once a camera track exists, matching native's placement/styling -- the BACKING
// mechanism is what switches: real track.applyConstraints({advanced:[{zoom}]}) when the device
// reports capability (the captured frame already reflects it, same as native's real hardware
// zoom), otherwise a CSS scale() of the <video> preview (cosmetic only -- capturePhoto() below
// crops the source rectangle to match, so the saved photo agrees with what was framed on screen).
let cameraZoomTrack = null;
let cameraZoomUsesHardware = false;
let cameraCssZoomLevel = 1;
const CAMERA_ZOOM_MIN = 1;
const CAMERA_ZOOM_MAX = 5; // matches native's own 1f..5f range, used only by the CSS fallback
// Item 59: pinch-to-zoom (CameraPreviewHost.android.kt's detectTransformGestures) -- tracks the
// two-finger distance at pinch start and the slider's own value at that moment, so a pinch is a
// multiplier on wherever zoom already was, not an absolute jump. Shares applyCameraZoomValue with
// the slider itself (below), so both are clamped to the exact same live range -- the device's own
// getCapabilities().zoom {min,max} when hardware zoom is in use, or the 1x-5x CSS-fallback range
// otherwise -- with no separate range to keep in sync.
let cameraPinchStartDistance = null;
let cameraPinchStartZoomValue = 1;

// Item 60: one whale-count object for both entry points now -- there's only one whale-count row
// left (#manual-log-step's own), so the camera path's previously-separate cameraCounts is gone.
const manualCounts = { whites: 0, greys: 0, calves: 0, unknown: 0 };

// --- Manual-path state (ManualLoggingScreen.kt) -- shared by BOTH entry points as of item 60 ---
let manualMapInstance = null;
let manualLat = DEFAULT_MAP_CENTER[0];
let manualLng = DEFAULT_MAP_CENTER[1];
let manualTravelBearingDegrees = null;
let manualObserverType = "SELF";
// Item 92: the Leaflet marker for showObserverLocationDot/clearObserverLocationDot below -- null
// until the first successful "MY LOCATION" fix this visit, cleared on every exit from this
// screen (resetManualPositionControls), never persisted across visits or submitted with the
// sighting.
let observerLocationMarker = null;
// Item 90: multi-select, unlike every other .chip-toggle group on this screen -- a Set, not a
// single active value, since any number of these can apply to one sighting at once. Reset between
// sightings (resetManualPositionControls), never persisted.
const manualSelectedActivities = new Set();
let manualActivityOtherNote = "";
// Item 60: which of the two entry points brought us to #manual-log-step this visit -- the two
// differ by exactly one nav-stack layer (see enterManualLogStepFromCamera's own push vs.
// main-menu.js's "manual-report" push for openManualReportFlow), so a successful submit's own
// post-reset teardown (resetManualSubmitForm) needs to know which one to correctly unwind either
// the extra layer (camera path) or nothing at all (plain path, matching its own pre-existing
// behavior).
let manualLogStepReachedViaCameraPath = false;

// Item 106: the sighting row currently being EDITED, or null for an ordinary new report. Set only
// by openEditSightingFlow, cleared only by exitEditSightingFlow -- everything that behaves
// differently in edit mode branches on this one variable being non-null, rather than on a
// separate boolean that could disagree with it.
let editingSighting = null;

// Shared by both paths' geofence-warning "SAVE ANYWAY" button -- set right before showing the
// warning, so one modal/handler pair can serve either flow without needing to know which one is
// currently active.
let pendingFinishAction = null;

// Item 34: pre-submit confirmation, shared by both paths the same way pendingFinishAction is
// above -- set right before showing #submit-confirm-modal, cleared on either button. Native
// parity: the same confirm step goes into LoggingScreen.kt/ManualLoggingScreen.kt's own DONE/
// SUBMIT handlers (shared/src/commonMain), not web-only.
let pendingConfirmAction = null;

// "2 white, 0 grey, 2 calves, 0 unknown" -- shared by both paths' confirm summary, both count
// objects have the identical {whites, greys, calves, unknown} shape.
function formatWhaleCountsSummary(counts) {
  return `${counts.whites} white, ${counts.greys} grey, ${counts.calves} calves, ${counts.unknown} unknown`;
}

// Item 90: matches the CHECK constraint's own allowed values (supabase/migrations) exactly --
// this is the one place that label text is spelled out, reused by the confirm summary, the map
// popup, and the list item, so all three can never drift apart from each other or from the DB's
// own allowed set.
const ACTIVITY_LABELS = {
  TRAVELLING: "Travelling",
  MILLING: "Milling",
  FEEDING_OBSERVED: "Feeding Observed",
  BENTHIC_FEEDING_EVIDENCED: "Benthic Feeding Evidenced",
  COURTSHIP_BEHAVIOURS: "Courtship Behaviours",
  OTHER: "Other"
};

// null (not the empty string) whenever nothing's selected -- callers use that to hide the whole
// line rather than show "Activity: " with nothing after it.
function formatActivitiesSummary(activities, note) {
  if (!activities || activities.length === 0) return null;
  const parts = activities.map((key) => ACTIVITY_LABELS[key] || key);
  let text = `Activity: ${parts.join(", ")}`;
  if (activities.includes("OTHER") && note && note.trim()) {
    text += ` (${note.trim()})`;
  }
  return text;
}

// Item 34: compact readable summary (counts spelled out, direction/heading, time) with CONFIRM/
// BACK, shown before EITHER path's actual submit logic runs -- requires a deliberate tap, never
// auto-dismisses. onConfirm is deferred until the CONFIRM button's own click handler below, not
// called from here.
function showSubmitConfirmModal(countsText, directionText, timeText, activitiesText, onConfirm) {
  document.getElementById("confirm-summary-counts").textContent = countsText;
  document.getElementById("confirm-summary-direction").textContent = directionText;
  document.getElementById("confirm-summary-time").textContent = `Time: ${timeText}`;
  // Item 90: hidden entirely (not "Activity: none") whenever nothing was selected.
  const activitiesEl = document.getElementById("confirm-summary-activities");
  activitiesEl.hidden = activitiesText == null;
  activitiesEl.textContent = activitiesText || "";
  pendingConfirmAction = onConfirm;
  document.getElementById("submit-confirm-modal").hidden = false;
  pushNavLayer("submit-confirm-modal", () => {
    document.getElementById("submit-confirm-modal").hidden = true;
    pendingConfirmAction = null;
  });
}

function initSubmitView() {
  document.getElementById("capture-btn").addEventListener("click", capturePhoto);
  document.getElementById("camera-zoom-slider").addEventListener("input", handleCameraZoomInput);
  // Item 59: pinch-to-zoom, matching CameraPreviewHost.android.kt's own detectTransformGestures --
  // touchmove needs { passive: false } so preventDefault can actually suppress the browser's own
  // two-finger scroll/zoom while pinching the camera itself.
  const cameraStepEl = document.getElementById("camera-step");
  cameraStepEl.addEventListener("touchstart", handleCameraPinchStart, { passive: true });
  cameraStepEl.addEventListener("touchmove", handleCameraPinchMove, { passive: false });
  cameraStepEl.addEventListener("touchend", handleCameraPinchEnd, { passive: true });
  cameraStepEl.addEventListener("touchcancel", handleCameraPinchEnd, { passive: true });
  // Item 61: iOS Safari's proprietary GestureEvent (gesturestart/gesturechange/gestureend) fires
  // for a two-finger pinch independently of the standard touch events above -- older WebKit
  // versions in particular can still drive the page's own native pinch-zoom off these even when
  // touch-action:none/touchmove's preventDefault are both already in place. No browser other than
  // Safari ever fires these at all, so this is a harmless no-op everywhere else.
  cameraStepEl.addEventListener("gesturestart", (event) => event.preventDefault());
  cameraStepEl.addEventListener("gesturechange", (event) => event.preventDefault());
  cameraStepEl.addEventListener("gestureend", (event) => event.preventDefault());
  // Item 60: capturing a photo (or skipping the camera) now goes straight into the same
  // manual-log-step every "Report Manually" entry uses -- see enterManualLogStepFromCamera.
  document.getElementById("skip-camera-btn").addEventListener("click", () => enterManualLogStepFromCamera());
  document.getElementById("manual-photo-thumb-btn").addEventListener("click", retakePhoto);
  document.getElementById("manual-submit-btn").addEventListener("click", submitManualSighting);

  document.getElementById("submit-confirm-back-btn").addEventListener("click", () => navigateBack());
  // Item 107: the confirmed action runs AFTER this modal's own layer has actually been popped
  // (navigateBackThen, nav-stack.js), not in the same tick as the request to pop it. It can open
  // a further modal of its own -- proceedManualSubmit's geofence warning does exactly that, and
  // synchronously, whenever the position fails the coastline check outright -- and a layer pushed
  // while this pop was still in flight got torn straight back down by its popstate. pendingConfirm
  // Action is captured BEFORE the pop because this layer's own onPop is what clears it.
  document.getElementById("submit-confirm-confirm-btn").addEventListener("click", () => {
    const confirmedAction = pendingConfirmAction;
    navigateBackThen(() => {
      if (confirmedAction) confirmedAction();
    });
  });

  document.getElementById("outer-geofence-reject-ok-btn").addEventListener("click", () => navigateBack());
  document.getElementById("geofence-warning-cancel-btn").addEventListener("click", () => navigateBack());
  // Item 107: same deferral, same reason -- SAVE ANYWAY's own finish action can push layers of its
  // own on the way out (resetManualSubmitForm's navigateBack on the camera path, finishSightingEdit's
  // on the edit path), so it must not run while this modal's pop is still in flight.
  //
  // BUG FIX (item 107, found on-device): this used to call pendingFinishAction() with NO argument,
  // so proceedManualSubmit's `finish = (verified) => finishManualSubmit(lat, lng, verified)` ran
  // with verified === undefined -- meaning is_geofence_verified was undefined on the record, and
  // JSON.stringify DROPPED the key from the request body entirely. On INSERT that was invisible:
  // the column is NOT NULL DEFAULT false, so the omitted key landed as false, which is exactly
  // what SAVE ANYWAY means anyway. On item 106's EDIT path it is fatal -- edit_my_last_sighting
  // refuses a position patch that doesn't carry the flag, so SAVE ANYWAY on an edit failed
  // outright. Passing the false explicitly says what was always meant, on both paths, instead of
  // leaning on a column default that only one of them has.
  document.getElementById("geofence-warning-save-btn").addEventListener("click", () => {
    const finishAction = pendingFinishAction;
    navigateBackThen(() => {
      if (finishAction) finishAction(false); // SAVE ANYWAY -- explicitly NOT geofence-verified
    });
  });

  initManualObserverToggle();
  initManualPositionControls();
  initActivityPicker();
  initWhaleCountWiring("#manual-log-step", manualCounts);

  initCameraTapToStartOverlay();
}

// Item 80: true only for THIS session's actual launch into Camera with the fullscreen preference
// on -- checked once here, at app startup, before resolveLaunchScreen (launch-screen.js) has even
// run yet, using the exact same reachability condition it applies (CAMERA preference is only
// honored on a non-desktop-class device, see isDesktopClassDevice) so this can't disagree with
// what actually ends up on screen. getFullscreenPreferred/isStandaloneDisplayMode come from
// orientation-lock.js (item 77) -- both are plain reads (localStorage/matchMedia), safe to call
// from here regardless of file load order, since this only ever runs from initSubmitView, itself
// only called after every script has finished loading (app.js's DOMContentLoaded).
function cameraFullscreenTapGateActive() {
  return (
    getLaunchScreenPreference() === "CAMERA" &&
    !isDesktopClassDevice() &&
    getFullscreenPreferred() &&
    !isStandaloneDisplayMode()
  );
}

// Item 80: either starts the camera immediately (unchanged from before this item, the common
// case), or -- only when cameraFullscreenTapGateActive() -- shows the tap-to-start overlay
// instead and defers startCamera() to its own click handler, since that tap is what supplies the
// user gesture browsers require to actually grant fullscreen (app launch itself has none).
function initCameraTapToStartOverlay() {
  const overlay = document.getElementById("camera-tap-to-start-overlay");

  overlay.addEventListener("click", () => {
    enterFullscreenForTouchDevice();
    // Guards against a redundant/leaked second getUserMedia call in the (unlikely but possible)
    // case where a stream already went live some other way -- Skip Camera then back via the
    // manual-log-step's photo thumb -- while this overlay was still sitting there untapped;
    // startCamera's own success branch below is what actually hides it in that case.
    if (!cameraStream) startCamera();
    overlay.hidden = true;
  });

  if (cameraFullscreenTapGateActive()) {
    overlay.hidden = false;
  } else {
    startCamera();
  }
}

function initWhaleCountWiring(scopeSelector, counts) {
  document.querySelectorAll(`${scopeSelector} .whale-count-col`).forEach((col) => {
    const key = col.dataset.count;
    const valueEl = col.querySelector(".whale-count-value");
    col.querySelector(".whale-count-inc").addEventListener("click", () => {
      counts[key] = counts[key] + 1;
      valueEl.textContent = counts[key];
    });
    col.querySelector(".whale-count-dec").addEventListener("click", () => {
      counts[key] = Math.max(0, counts[key] - 1);
      valueEl.textContent = counts[key];
    });
  });
}

function resetWhaleCountUi(scopeSelector, counts) {
  Object.keys(counts).forEach((key) => (counts[key] = 0));
  document.querySelectorAll(`${scopeSelector} .whale-count-value`).forEach((el) => (el.textContent = "0"));
}

// Item 106: the same write, to the same two places (the counts object the rest of this file
// reads, and the on-screen value under each piece of artwork) -- but to real values rather than
// zeroes, for pre-filling the edit flow. resetWhaleCountUi above is exactly this with every value
// 0; kept separate rather than merged so the reset path's call sites stay as obvious as they are.
function setWhaleCountUi(scopeSelector, counts, values) {
  Object.keys(counts).forEach((key) => (counts[key] = Math.max(0, values[key] || 0)));
  document.querySelectorAll(`${scopeSelector} .whale-count-col`).forEach((col) => {
    const key = col.dataset.count;
    if (key in counts) col.querySelector(".whale-count-value").textContent = String(counts[key]);
  });
}

// --- CaptureScreen.kt / LoggingScreen.kt (camera path) ---

async function startCamera() {
  const video = document.getElementById("camera-preview");
  const cameraStatus = document.getElementById("camera-status");
  const skipBtn = document.getElementById("skip-camera-btn");
  cameraStatus.hidden = true;
  skipBtn.hidden = true;
  resetCameraZoomUi();

  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false
    });
    video.srcObject = cameraStream;
    initCameraZoomControl();
    // Item 80: whatever path actually got a live stream running (the tap-to-start overlay's own
    // tap, or a later goToCameraStep() call after the user ignored it via Skip Camera and came
    // back) clears the overlay -- nothing left for it to gate once the camera is genuinely live.
    document.getElementById("camera-tap-to-start-overlay").hidden = true;
  } catch (e) {
    // Native always has a working camera to trigger LoggingScreen from -- a browser can't
    // guarantee that, so this is the one deliberate departure from native's flow: a way to
    // reach the review step without a photo at all, rather than a dead end.
    console.error("CAMERA_ERROR", e);
    cameraStatus.hidden = false;
    cameraStatus.textContent = `Camera unavailable (${e.name}). You can continue without a photo, or check camera permissions.`;
    skipBtn.hidden = false;
  }
}

function stopCamera() {
  if (cameraStream) {
    cameraStream.getTracks().forEach((track) => track.stop());
    cameraStream = null;
  }
  resetCameraZoomUi();
}

// Fresh per camera stream -- a retake or a return trip through Report Manually tears down and
// re-requests getUserMedia, and a new MediaStreamTrack needs its own capability check (a
// previous track's zoom constraint doesn't carry over) rather than trusting stale state.
function resetCameraZoomUi() {
  cameraZoomTrack = null;
  cameraZoomUsesHardware = false;
  cameraCssZoomLevel = 1;
  cameraPinchStartDistance = null;
  document.getElementById("camera-preview").style.transform = "";
  document.getElementById("camera-zoom-control").hidden = true;
  document.getElementById("camera-zoom-slider").value = "1";
  document.getElementById("camera-zoom-label").textContent = "1.0x";
}

function initCameraZoomControl() {
  const track = cameraStream && cameraStream.getVideoTracks()[0];
  const control = document.getElementById("camera-zoom-control");
  const slider = document.getElementById("camera-zoom-slider");
  const label = document.getElementById("camera-zoom-label");
  if (!track) {
    control.hidden = true;
    return;
  }

  // Item 59: the slider's range comes from the device's OWN reported {min, max} here, never the
  // 1x-5x CAMERA_ZOOM_MIN/MAX constant below -- that constant is scoped to the CSS fallback only
  // (a few lines down), so a phone whose camera actually supports more than 5x (most iPhones do)
  // still gets its full real range, not native's own hardcoded cap.
  const caps = typeof track.getCapabilities === "function" ? track.getCapabilities() : {};
  if (caps && caps.zoom && caps.zoom.max > caps.zoom.min) {
    cameraZoomTrack = track;
    cameraZoomUsesHardware = true;
    slider.min = caps.zoom.min;
    slider.max = caps.zoom.max;
    slider.step = caps.zoom.step || 0.1;
    slider.value = caps.zoom.min;
    label.textContent = `${Number(caps.zoom.min).toFixed(1)}x`;
    control.hidden = false;
    return;
  }

  // No hardware zoom capability reported (common on iOS Safari, some desktop webcams) -- CSS
  // scale() fallback instead, same 1x-5x range native's own slider covers.
  cameraZoomUsesHardware = false;
  slider.min = CAMERA_ZOOM_MIN;
  slider.max = CAMERA_ZOOM_MAX;
  slider.step = "0.1";
  slider.value = "1";
  label.textContent = "1.0x";
  control.hidden = false;
}

// Item 59: shared by the slider's own input listener AND the pinch gesture below, clamping to
// the slider's own live min/max either way -- whatever initCameraZoomControl decided that range
// is (real device capability, or the CSS-fallback constant), both input paths land on the exact
// same number for the exact same finger position/slider position, never two ranges drifting
// apart.
function applyCameraZoomValue(rawValue) {
  const slider = document.getElementById("camera-zoom-slider");
  const min = parseFloat(slider.min);
  const max = parseFloat(slider.max);
  const value = Math.min(max, Math.max(min, rawValue));

  slider.value = value;
  document.getElementById("camera-zoom-label").textContent = `${value.toFixed(1)}x`;

  if (cameraZoomUsesHardware && cameraZoomTrack) {
    cameraZoomTrack.applyConstraints({ advanced: [{ zoom: value }] }).catch((e) => {
      console.error("CAMERA_ZOOM_CONSTRAINT_ERROR", e);
    });
    return;
  }

  cameraCssZoomLevel = value;
  document.getElementById("camera-preview").style.transform = `scale(${value})`;
}

function handleCameraZoomInput(event) {
  applyCameraZoomValue(parseFloat(event.target.value));
}

function cameraTouchPairDistance(touches) {
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.hypot(dx, dy);
}

function handleCameraPinchStart(event) {
  if (event.touches.length !== 2 || document.getElementById("camera-zoom-control").hidden) return;
  cameraPinchStartDistance = cameraTouchPairDistance(event.touches);
  cameraPinchStartZoomValue = parseFloat(document.getElementById("camera-zoom-slider").value);
}

function handleCameraPinchMove(event) {
  if (event.touches.length !== 2 || cameraPinchStartDistance == null) return;
  event.preventDefault(); // don't let two-finger movement scroll/gesture-zoom the page underneath
  const scaleFactor = cameraTouchPairDistance(event.touches) / cameraPinchStartDistance;
  applyCameraZoomValue(cameraPinchStartZoomValue * scaleFactor);
}

function handleCameraPinchEnd(event) {
  if (event.touches.length < 2) cameraPinchStartDistance = null;
}

function setCaptureLabel(text) {
  document.getElementById("capture-shutter-label").innerHTML = text.split("").join("<br>");
}

function capturePhoto() {
  const video = document.getElementById("camera-preview");
  const canvas = document.getElementById("capture-canvas");
  if (!video.videoWidth) return;

  setCaptureLabel("SAVING...");

  const MAX_EDGE = 1600;
  const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
  canvas.width = video.videoWidth * scale;
  canvas.height = video.videoHeight * scale;

  // Item 55: CSS scale() on the preview is a display-only illusion -- it never changes what the
  // camera actually delivers, so drawImage would otherwise still capture the un-zoomed full
  // frame. Crop the SOURCE rectangle down to the same centered fraction of the frame the on-
  // screen scale implies, then draw that into the full canvas size, so the saved photo matches
  // what was framed on screen. Real hardware zoom (cameraZoomUsesHardware) already changes the
  // frame at the source -- cameraCssZoomLevel only ever moves off 1 via the CSS-fallback path
  // (handleCameraZoomInput), so this stays a no-op crop (the untouched full frame) in that case.
  const cropWidth = video.videoWidth / cameraCssZoomLevel;
  const cropHeight = video.videoHeight / cameraCssZoomLevel;
  const cropX = (video.videoWidth - cropWidth) / 2;
  const cropY = (video.videoHeight - cropHeight) / 2;
  canvas.getContext("2d").drawImage(
    video,
    cropX, cropY, cropWidth, cropHeight,
    0, 0, canvas.width, canvas.height
  );

  canvas.toBlob(
    (blob) => {
      capturedPhotoBlob = blob;
      setCaptureLabel("CAPTURE");
      enterManualLogStepFromCamera();
    },
    "image/jpeg",
    0.85
  );
}

// Item 60: replaces the old goToReviewStep -- capturing a photo (or skipping the camera) now
// lands directly on the SAME map+crosshair+BearingDial screen "Report Manually" uses, with the
// photo (if any) attached via updateManualPhotoThumb, rather than LoggingScreen's own separate
// photo-plus-heading-plus-distance review step (gone entirely, see this file's header comment).
function enterManualLogStepFromCamera() {
  manualLogStepReachedViaCameraPath = true;
  stopCamera();
  updateManualPhotoThumb();

  document.getElementById("camera-step").hidden = true;
  document.getElementById("manual-log-step").hidden = false;

  initManualMapIfNeeded();
  setManualDatetimeInputToNow();
  updateManualDatetimeButtonLabel();

  // Item 60: a best-effort GPS fix taken ONLY to center the map near the observer -- never
  // stored, never the submitted position (see centerManualMapFromGpsOnce's own comment). Plain
  // "Report Manually" (openManualReportFlow) deliberately does NOT do this -- that's an
  // intentional, confirmed-against-source difference from native, unchanged by this item.
  centerManualMapFromGpsOnce();

  // Pushes its own nav-stack layer (see nav-stack.js) so the back gesture/hardware back button
  // returns to the camera step exactly like tapping the photo thumbnail (retakePhoto) does --
  // goToCameraStep is that same teardown, reused directly as this layer's onPop.
  pushNavLayer("manual-log-step-from-camera", goToCameraStep);
}

// Item 60: shows/hides the small corner thumbnail on #manual-log-step (see the HTML's own
// comment) -- visible only when this visit actually has a captured photo attached, i.e. only via
// the camera path, never via plain "Report Manually".
function updateManualPhotoThumb() {
  const thumbBtn = document.getElementById("manual-photo-thumb-btn");
  const thumbImg = document.getElementById("manual-photo-thumb");
  if (reviewPhotoObjectUrl) {
    URL.revokeObjectURL(reviewPhotoObjectUrl);
    reviewPhotoObjectUrl = null;
  }
  if (capturedPhotoBlob) {
    reviewPhotoObjectUrl = URL.createObjectURL(capturedPhotoBlob);
    thumbImg.src = reviewPhotoObjectUrl;
    thumbBtn.hidden = false;
  } else {
    thumbImg.src = "";
    thumbBtn.hidden = true;
  }
}

// Item 92: shared by this file's own two GPS-driven map-centering moments -- the camera path's
// automatic initial fix (centerManualMapFromGpsOnce, right below) and MY LOCATION's on-demand one
// (handleMyLocationTap) -- one named constant so the two can never quietly drift apart. ~1-2km
// visible across a typical phone screen at Cook Inlet's own latitude (60-61N): Leaflet's
// meters-per-pixel at zoom z is 156543.034*cos(lat)/2^z, so a ~400px-wide viewport at zoom 14
// spans roughly 1.9km -- comfortably in the requested range without being so tight a boat's own
// slight GPS jitter looks like it's leaping around.
const GPS_CENTER_ZOOM = 14;

// Item 60: a rough GPS fix taken once, at capture time, used ONLY to center the map near the
// observer's own position -- never stored, never the submitted position (that's still always
// whatever the crosshair/map center reads at SUBMIT time, exactly like a plain Report Manually
// entry). Skipped entirely if the user has already started panning/zooming the map by the time
// the fix arrives, so a slow fix can never yank the view out from under someone who's already
// begun placing the pin. Best-effort and silent -- a denied/failed fix just leaves the map at its
// DEFAULT_MAP_CENTER starting point, same as plain Report Manually always does.
function centerManualMapFromGpsOnce() {
  if (!navigator.geolocation || !manualMapInstance) return;
  let userHasInteracted = false;
  manualMapInstance.once("dragstart zoomstart", () => {
    userHasInteracted = true;
  });
  navigator.geolocation.getCurrentPosition(
    (position) => {
      // Also bails if the user has already left this screen entirely (back to camera-step, or
      // out of the tab) by the time a slow fix resolves -- otherwise a stale fix from an earlier
      // visit could still recenter the map out from under whatever the user is doing much later.
      if (userHasInteracted || document.getElementById("manual-log-step").hidden) return;
      manualMapInstance.setView(
        [position.coords.latitude, position.coords.longitude],
        GPS_CENTER_ZOOM
      );
    },
    (err) => console.warn("CAMERA_INITIAL_GPS_FIX_ERROR", err),
    { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
  );
}

// Matches LoggingScreen's onDoneClick/onRetakeClick and ManualLoggingScreen's onDoneClick alike --
// all three return to Screen.CAPTURE, ready for the next report. Shared exit point regardless of
// which of the two entry points (camera-with-photo or plain Report Manually) is currently showing.
function goToCameraStep() {
  capturedPhotoBlob = null;
  updateManualPhotoThumb();
  document.getElementById("manual-log-step").hidden = true;
  document.getElementById("camera-step").hidden = false;
  resetManualPositionControls();
  startCamera();
}

// Resets manual-path state on ANY exit from #manual-log-step (abandoned mid-way via back
// gesture/menu, not just a successful submit -- resetManualSubmitForm covers that path too, but
// this covers it unconditionally here) -- matches native's own remembered state resetting fresh
// every time ManualLoggingScreen is re-entered.
function resetManualPositionControls() {
  manualTravelBearingDegrees = null;
  updateBearingDialNeedle(null);
  setManualObserverType("SELF");
  resetActivityPicker();
  clearObserverLocationDot();
}

// Item 60: tapping the photo thumbnail on #manual-log-step discards the photo and returns to the
// camera step for another attempt -- the only "retake" affordance left, now that there's no
// separate review step to retake FROM.
function retakePhoto() {
  // Item 106: in edit mode the thumbnail is the ALREADY-SUBMITTED photo shown for context, not a
  // pending capture -- there's no camera step behind this screen to return to, and replacing the
  // photo isn't offered (see openEditSightingFlow's own comment), so the tap does nothing.
  if (editingSighting) return;
  navigateBack(); // pops the manual-log-step-from-camera layer; its onPop IS goToCameraStep
}

// --- ManualLoggingScreen.kt (Report Manually path) ---

// Called from the main menu's "Report Manually" item (main-menu.js owns the switchTab/nav-stack
// push for THAT entry point, since it also has to remember/restore whichever tab was active
// before). This function itself only ever changes which sub-step is visible within the submit
// tab -- no photo is ever attached via this entry point (updateManualPhotoThumb's hidden default
// covers that; capturedPhotoBlob is only ever set by the camera path).
function openManualReportFlow() {
  manualLogStepReachedViaCameraPath = false;
  switchTab("submit");
  stopCamera();
  document.getElementById("camera-step").hidden = true;
  document.getElementById("manual-log-step").hidden = false;

  // GPS is deliberately NOT auto-fetched on ENTRY here. Confirmed against ManualLoggingScreen.kt's
  // real source, native DOES call LaunchedEffect(Unit) { recenterOnGps() } on entry -- but only to
  // set the map's STARTING center as a convenience; the submitted position is always whatever the
  // fixed center pin reads at SUBMIT time (wherever the user has panned to), never locked to that
  // initial fetch. This app overrides that on purpose anyway (explicitly confirmed, not an
  // oversight): manual reporting is meant to be explicitly NOT "where I am now" from the moment
  // this screen opens, not just at submit time. The map starts at DEFAULT_MAP_CENTER instead.
  // Item 60's own camera-path entry point (enterManualLogStepFromCamera) is the one deliberate
  // exception to this on-ENTRY behavior, and takes its own separate GPS fix for that reason -- see
  // its own comment. Item 92 later added a MY LOCATION button (initManualPositionControls) to
  // BOTH entry points -- an explicit ON-DEMAND, view-only recentering aid, not a silent auto-fetch
  // and not a "the whale was where I'm standing" shortcut, so it doesn't reopen the problem this
  // comment originally described (a "Get My Location" that set the SUBMITTED position, removed
  // entirely, see initManualPositionControls' own comment for the full distinction).
  initManualMapIfNeeded();
  setManualDatetimeInputToNow();
  updateManualDatetimeButtonLabel();
}

// DatePickerDialog.kt, ported as a plain datetime-local input (the platform supplies its own
// picker UI) -- defaults to "now" each time this screen is entered, matching
// ManualLoggingScreen's own `var selectedTimestampMs by remember { mutableStateOf(currentTimeMillis()) }`.
function formatDatetimeLocalValue(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function setManualDatetimeInputToNow() {
  document.getElementById("manual-datetime-input").value = formatDatetimeLocalValue(new Date());
}

// Falls back to "now" if the input is somehow empty/invalid -- matches the field's own default,
// never a hard failure over a timestamp.
function manualSelectedTimestampMs() {
  const value = document.getElementById("manual-datetime-input").value;
  if (!value) return Date.now();
  const parsed = new Date(value).getTime();
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

function initManualMapIfNeeded() {
  if (manualMapInstance) {
    setTimeout(() => manualMapInstance.invalidateSize(), 0);
    updateManualPositionFromMapCenter();
    return;
  }
  manualMapInstance = L.map("manual-map").setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19
  }).addTo(manualMapInstance);
  manualMapInstance.on("move", updateManualPositionFromMapCenter);
  updateManualPositionFromMapCenter();
  setTimeout(() => manualMapInstance.invalidateSize(), 0);
}

function updateManualPositionFromMapCenter() {
  const center = manualMapInstance.getCenter();
  manualLat = center.lat;
  manualLng = center.lng;
  document.getElementById("manual-coords-readout").textContent =
    `${manualLat.toFixed(4)}, ${manualLng.toFixed(4)}`;
}

// Item 92: MY LOCATION replaces the old plain RECENTER -- a TAP now takes a fresh GPS fix and
// centers the MAP VIEW on it (handleMyLocationTap), distinct from the "Get My Location" this
// screen deliberately removed once before (see openManualReportFlow's own comment): that one set
// the SUBMITTED position to the observer's own fix, which conflated "where I am" with "where the
// whale was" -- exactly what this screen exists to keep separate. This is purely a navigational
// convenience, same category as panning/zooming by hand, and never writes to manualLat/manualLng
// itself -- only updateManualPositionFromMapCenter (bound to the map's own "move" event) does
// that, reading wherever the view ends UP after the fix, same as any other pan. A LONG-PRESS
// keeps the OLD behavior (plain reset to the Cook Inlet overview, no GPS at all) as a fallback,
// not a removal -- distinguished via a plain pointerdown/pointerup timer rather than a second
// button, since there's no room left in this row for one.
function initManualPositionControls() {
  const recenterBtn = document.getElementById("manual-recenter-btn");
  const LONG_PRESS_MS = 550;
  let longPressTimer = null;
  let longPressFired = false;

  recenterBtn.addEventListener("pointerdown", () => {
    longPressFired = false;
    longPressTimer = setTimeout(() => {
      longPressFired = true;
      manualMapInstance.setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);
    }, LONG_PRESS_MS);
  });
  const cancelLongPressTimer = () => {
    clearTimeout(longPressTimer);
    longPressTimer = null;
  };
  recenterBtn.addEventListener("pointerup", cancelLongPressTimer);
  recenterBtn.addEventListener("pointerleave", cancelLongPressTimer);
  recenterBtn.addEventListener("pointercancel", cancelLongPressTimer);

  // The browser's own click event always fires right after pointerup on the same element --
  // checking longPressFired here (rather than doing the tap's own work directly in pointerup)
  // means a held-then-released press that already triggered the long-press branch doesn't ALSO
  // fire a normal tap immediately afterward.
  recenterBtn.addEventListener("click", () => {
    if (longPressFired) {
      longPressFired = false;
      return;
    }
    handleMyLocationTap(recenterBtn);
  });

  document.getElementById("manual-datetime-btn").addEventListener("click", openManualDatetimePicker);
  document.getElementById("manual-datetime-input").addEventListener("change", updateManualDatetimeButtonLabel);

  initBearingDial();
}

// Item 92: reuses getCurrentPositionOnce (tier-code.js) -- the same never-rejects/carries-
// accuracy wrapper around getCurrentPosition the departure-eligibility check already relies on,
// rather than a second copy of the same plumbing. Briefly repurposes the button's own label to
// show progress/accuracy/failure instead of adding a whole new status element to an already
// crowded screen -- matches admin.js's handleCopyCode's own "flash a temporary label, then revert"
// pattern.
async function handleMyLocationTap(btn) {
  const originalLabel = btn.textContent;
  btn.disabled = true;
  btn.textContent = "📍 Locating…";

  const result = await getCurrentPositionOnce({ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 });

  btn.disabled = false;

  if (!result.coords) {
    console.warn("MY_LOCATION_GPS_ERROR", result.error);
    btn.textContent = "📍 No fix";
    setTimeout(() => { btn.textContent = originalLabel; }, 1800);
    return;
  }

  manualMapInstance.setView([result.coords.lat, result.coords.lng], GPS_CENTER_ZOOM);
  showObserverLocationDot(result.coords.lat, result.coords.lng);

  btn.textContent = Number.isFinite(result.accuracy) ? `📍 ±${Math.round(result.accuracy)}m` : originalLabel;
  setTimeout(() => { btn.textContent = originalLabel; }, 1800);
}

// Item 92: a small pulsing dot at the OBSERVER's own last-fetched position (see
// .observer-location-dot, style.css) -- purely a visual aid so a boater/pilot can see themselves
// relative to the crosshair, never interactive (a real tap must always land on the map/crosshair
// underneath, never this marker) and never part of the submitted record. Updates in place on a
// second fix rather than creating a duplicate marker.
function showObserverLocationDot(lat, lng) {
  if (observerLocationMarker) {
    observerLocationMarker.setLatLng([lat, lng]);
    return;
  }
  observerLocationMarker = L.marker([lat, lng], {
    icon: L.divIcon({ className: "observer-location-dot", iconSize: [16, 16] }),
    interactive: false,
    keyboard: false
  }).addTo(manualMapInstance);
}

// Client-only, this-screen-only, per the request -- called from resetManualPositionControls so
// every exit path from #manual-log-step (both entry points funnel through goToCameraStep, see
// that function's own comment) clears it, matching the bearing dial's own reset-between-visits
// scope.
function clearObserverLocationDot() {
  if (observerLocationMarker) {
    observerLocationMarker.remove();
    observerLocationMarker = null;
  }
}

// DatePickerDialog.kt as a compact trigger rather than a full-width bar -- the hidden input
// supplies the platform's own real picker UI via showPicker(); .click()/.focus() are the fallback
// wherever showPicker() itself isn't supported (older Safari versions in particular).
function openManualDatetimePicker() {
  const input = document.getElementById("manual-datetime-input");
  if (typeof input.showPicker === "function") {
    try {
      input.showPicker();
      return;
    } catch (e) {
      console.warn("DATETIME_SHOW_PICKER_ERROR", e);
    }
  }
  input.focus();
  input.click();
}

function updateManualDatetimeButtonLabel() {
  const btn = document.getElementById("manual-datetime-btn");
  const value = document.getElementById("manual-datetime-input").value;
  if (!value) {
    btn.textContent = "📅 SET DATE / TIME";
    return;
  }
  btn.textContent = `📅 ${new Date(value).toLocaleString()}`;
}

// --- BearingDial.kt, ported directly: continuous drag anywhere on the dial sets a LIVE angle
// (no snapping while dragging, so the needle tracks the finger/pointer smoothly), snapping to the
// nearest of 16 compass points (22.5deg steps) only at release -- matches
// detectDragGestures(onDragStart/onDrag/onDragEnd) exactly, one pointer-events-based drag instead
// of Compose's gesture detector. setPointerCapture keeps the whole drag routed to the dial even
// if the pointer moves outside its own bounds mid-gesture. ---
let bearingDialLiveDegrees = null;

function bearingFromPointerEvent(event, dialElement) {
  const rect = dialElement.getBoundingClientRect();
  const centerX = rect.left + rect.width / 2;
  const centerY = rect.top + rect.height / 2;
  const dx = event.clientX - centerX;
  const dy = event.clientY - centerY;
  const degrees = (Math.atan2(dx, -dy) * 180) / Math.PI;
  return (degrees + 360) % 360;
}

// Nearest of 16 compass points (22.5 degree steps) -- matches BearingDial's own
// snapToNearest16Point exactly.
function snapToNearest16Point(degrees) {
  return (Math.round(degrees / 22.5) * 22.5) % 360;
}

// Item 39: same diagnostics as item 37, now ALSO rendered on-screen (not just console.log) --
// a phone has no devtools console to read. Query-param gated (?debug=1, DEBUG_MODE_ENABLED in
// config.js, shared with tier-code.js's own item-47 overlay) so it never shows for a normal user;
// off by default. Remove this whole block, its call sites, and the overlay element/CSS once item
// 37/39 are confirmed fixed.
const bearingDialDebugState = {
  pointerdownCount: 0,
  lastPointerdown: null, // { pointerType, x, y }
  liveDegrees: null,
  committedDegrees: null,
  commitSkippedCount: 0,
  needle: null // filled in by logBearingDialNeedleDiagnostics
};

function renderBearingDialDebugOverlay() {
  if (!DEBUG_MODE_ENABLED) return;
  const el = document.getElementById("bearing-dial-debug-overlay");
  if (!el) return;
  const s = bearingDialDebugState;
  const n = s.needle;
  const fmt = (v) => (v == null ? "—" : typeof v === "number" ? v.toFixed(1) : String(v));
  el.textContent = [
    `pointerdown: ${s.pointerdownCount}x` + (s.lastPointerdown
      ? ` (last: ${s.lastPointerdown.pointerType} @ ${fmt(s.lastPointerdown.x)},${fmt(s.lastPointerdown.y)})`
      : " (none yet)"),
    `live drag degrees: ${fmt(s.liveDegrees)}`,
    `committed degrees: ${fmt(s.committedDegrees)}`,
    `commit-skipped (no live degrees): ${s.commitSkippedCount}x`,
    "--- needle ---",
    n ? `hidden attr present: ${n.hiddenAttrPresent}` : "(not updated yet)",
    n ? `computed display: ${n.computedDisplay}` : "",
    n ? `outline: (${n.outline.x1},${n.outline.y1}) -> (${n.outline.x2},${n.outline.y2}) stroke=${n.outline.stroke} width=${n.outline.strokeWidth}` : "",
    n ? `line: (${n.line.x1},${n.line.y1}) -> (${n.line.x2},${n.line.y2}) stroke=${n.line.stroke} width=${n.line.strokeWidth}` : "",
    n ? `svg viewBox=${n.svgViewBox} rect=${Math.round(n.svgClientRect.x)},${Math.round(n.svgClientRect.y)} ${Math.round(n.svgClientRect.width)}x${Math.round(n.svgClientRect.height)}` : ""
  ].filter((line) => line !== "").join("\n");
}

// Item 37: dumps the needle's actual live SVG state (hidden attribute, computed display, line
// coordinates/stroke) every time this runs, so a report of "the needle isn't visible" can be
// checked against real rendered values instead of source reasoning.
function logBearingDialNeedleDiagnostics(degrees) {
  const needle = document.getElementById("bearing-dial-needle");
  const outline = document.getElementById("bearing-dial-needle-outline");
  const line = document.getElementById("bearing-dial-needle-line");
  const svg = needle.closest("svg");
  const state = {
    degrees,
    hiddenAttrPresent: needle.hasAttribute("hidden"),
    computedDisplay: getComputedStyle(needle).display,
    outline: {
      x1: outline.getAttribute("x1"), y1: outline.getAttribute("y1"),
      x2: outline.getAttribute("x2"), y2: outline.getAttribute("y2"),
      stroke: getComputedStyle(outline).stroke,
      strokeWidth: getComputedStyle(outline).strokeWidth
    },
    line: {
      x1: line.getAttribute("x1"), y1: line.getAttribute("y1"),
      x2: line.getAttribute("x2"), y2: line.getAttribute("y2"),
      stroke: getComputedStyle(line).stroke,
      strokeWidth: getComputedStyle(line).strokeWidth
    },
    svgViewBox: svg.getAttribute("viewBox"),
    svgClientRect: svg.getBoundingClientRect()
  };
  console.log("BEARING_DIAL_NEEDLE_STATE", state);
  bearingDialDebugState.needle = state;
  renderBearingDialDebugOverlay();
}

// BUG FIX (item 42): SVGElement does not inherit from HTMLElement, and the `hidden` IDL property
// (the thing `el.hidden = true/false` actually sets) is only reflected on HTMLElement -- on an
// <svg>/<g>/etc. element, `.hidden = false` is a harmless no-op expando assignment that never
// touches the real `hidden` CONTENT ATTRIBUTE, the one the app-wide `[hidden] { display: none
// !important }` rule (style.css) actually matches against. Confirmed via the item-39/41 debug
// overlay: after a successful drag, the needle's coordinates were already correct, but its
// `hidden` ATTRIBUTE was still present (computed display: none) -- .hidden = false had silently
// done nothing. setAttribute/removeAttribute work on any element regardless of interface, since
// they operate on the actual attribute, not a per-interface reflected property.
function setSvgElementHidden(el, hidden) {
  if (hidden) {
    el.setAttribute("hidden", "");
  } else {
    el.removeAttribute("hidden");
  }
}

function updateBearingDialNeedle(degrees) {
  const needle = document.getElementById("bearing-dial-needle");
  // The "?" mark and the needle are mutually exclusive -- exactly one of them is ever visible, so
  // the dial always shows SOMETHING explicit rather than going blank when degrees is null (which
  // read as ambiguous: "not set yet" vs. "no needle drawn for some other reason" -- item 27b).
  setSvgElementHidden(document.getElementById("bearing-dial-unknown-mark"), degrees != null);
  if (degrees == null) {
    setSvgElementHidden(needle, true);
    logBearingDialNeedleDiagnostics(degrees);
    return;
  }
  setSvgElementHidden(needle, false);
  // Item 66: cy was 85 (off true-center, to leave headroom for the now-removed decorative pin) --
  // must match both the SVG's own re-centered geometry (index.html) AND bearingFromPointerEvent's
  // own assumption (the dial element's true geometric center), which is what actually fixed the
  // drag-pivot mismatch that offset caused.
  const cx = 75, cy = 75, radius = 58; // must match the SVG geometry in index.html
  const radians = (degrees * Math.PI) / 180;
  const endX = cx + Math.sin(radians) * radius;
  const endY = cy - Math.cos(radians) * radius;
  ["bearing-dial-needle-outline", "bearing-dial-needle-line"].forEach((id) => {
    const line = document.getElementById(id);
    line.setAttribute("x2", endX);
    line.setAttribute("y2", endY);
  });
  logBearingDialNeedleDiagnostics(degrees);
}

function initBearingDial() {
  const dial = document.getElementById("bearing-dial");

  if (DEBUG_MODE_ENABLED) {
    document.getElementById("bearing-dial-debug-overlay").hidden = false;
    renderBearingDialDebugOverlay();
  }

  dial.addEventListener("pointerdown", (event) => {
    // Item 37a: if this never fires, the gesture isn't reaching the dial at all (something
    // upstream -- another element, the browser's own touch handling -- is swallowing it first).
    console.log("BEARING_DIAL_POINTERDOWN", event.pointerType, event.clientX, event.clientY);
    bearingDialDebugState.pointerdownCount++;
    bearingDialDebugState.lastPointerdown = { pointerType: event.pointerType, x: event.clientX, y: event.clientY };
    renderBearingDialDebugOverlay();
    dial.setPointerCapture(event.pointerId);
    bearingDialLiveDegrees = bearingFromPointerEvent(event, dial);
    updateBearingDialNeedle(bearingDialLiveDegrees);
    event.preventDefault();
  });

  dial.addEventListener("pointermove", (event) => {
    if (bearingDialLiveDegrees == null) return;
    bearingDialLiveDegrees = bearingFromPointerEvent(event, dial);
    bearingDialDebugState.liveDegrees = bearingDialLiveDegrees;
    renderBearingDialDebugOverlay();
    updateBearingDialNeedle(bearingDialLiveDegrees);
  });

  const commitDrag = () => {
    // Item 37a: the direct answer to "is a bearing actually being committed on drag" -- if
    // bearingDialLiveDegrees was null here, this whole block is skipped and the skip counter
    // above (rather than a committed value) is what changes.
    if (bearingDialLiveDegrees == null) {
      console.log("BEARING_DIAL_COMMIT_SKIPPED_NO_LIVE_DEGREES");
      bearingDialDebugState.commitSkippedCount++;
      renderBearingDialDebugOverlay();
      return;
    }
    manualTravelBearingDegrees = snapToNearest16Point(bearingDialLiveDegrees);
    console.log("BEARING_DIAL_COMMITTED_DEGREES", manualTravelBearingDegrees);
    bearingDialDebugState.committedDegrees = manualTravelBearingDegrees;
    bearingDialLiveDegrees = null;
    renderBearingDialDebugOverlay();
    updateBearingDialNeedle(manualTravelBearingDegrees);
  };
  dial.addEventListener("pointerup", commitDrag);
  dial.addEventListener("pointercancel", () => {
    bearingDialLiveDegrees = null;
    updateBearingDialNeedle(manualTravelBearingDegrees); // revert to whatever was last committed
  });

  // Native's own drag gesture has no separate "clear" input at all (only ever reachable by never
  // touching the dial) -- this lets a user undo an accidental drag back to "not recorded" without
  // it being a native-tracked feature, see the HTML's own comment.
  document.getElementById("bearing-dial-unknown-btn").addEventListener("click", () => {
    manualTravelBearingDegrees = null;
    updateBearingDialNeedle(null);
  });
}

function initManualObserverToggle() {
  document.getElementById("manual-observer-self-btn").addEventListener("click", () => setManualObserverType("SELF"));
  document.getElementById("manual-observer-other-btn").addEventListener("click", () => setManualObserverType("OTHER"));
}

function setManualObserverType(type) {
  manualObserverType = type;
  document.getElementById("manual-observer-self-btn").classList.toggle("active", type === "SELF");
  document.getElementById("manual-observer-other-btn").classList.toggle("active", type === "OTHER");
}

// Item 90: multi-select chip picker -- each chip toggles its OWN membership in
// manualSelectedActivities independently (unlike every other .chip-toggle group here, which
// deactivates its siblings on tap), so any combination can be active at once.
function initActivityPicker() {
  document.getElementById("manual-activity-btn").addEventListener("click", () => {
    document.getElementById("activity-picker-modal").hidden = false;
    pushNavLayer("activity-picker-modal", () => {
      document.getElementById("activity-picker-modal").hidden = true;
    });
  });

  document.querySelectorAll("#activity-chips .chip-toggle").forEach((chip) => {
    chip.addEventListener("click", () => {
      const key = chip.dataset.activity;
      if (manualSelectedActivities.has(key)) {
        manualSelectedActivities.delete(key);
      } else {
        manualSelectedActivities.add(key);
      }
      chip.classList.toggle("active", manualSelectedActivities.has(key));

      if (key === "OTHER") {
        const noteInput = document.getElementById("activity-other-note-input");
        const otherSelected = manualSelectedActivities.has("OTHER");
        noteInput.hidden = !otherSelected;
        if (!otherSelected) {
          manualActivityOtherNote = "";
          noteInput.value = "";
        }
      }
    });
  });

  document.getElementById("activity-other-note-input").addEventListener("input", (event) => {
    manualActivityOtherNote = event.target.value;
  });

  document.getElementById("activity-picker-done-btn").addEventListener("click", () => {
    navigateBack(); // pops the activity-picker-modal layer, hiding it via its own onPop above
    updateActivityButtonUi();
  });
}

// Count badge -- hidden entirely (not "0") whenever nothing's selected.
function updateActivityButtonUi() {
  const badge = document.getElementById("manual-activity-badge");
  const count = manualSelectedActivities.size;
  badge.hidden = count === 0;
  badge.textContent = String(count);
}

// Item 90: resets between sightings -- called from resetManualPositionControls, which already
// runs on every exit from #manual-log-step (successful submit, abandoned mid-way, retake), same
// as travel direction/observer type right above it.
function resetActivityPicker() {
  manualSelectedActivities.clear();
  manualActivityOtherNote = "";
  document.querySelectorAll("#activity-chips .chip-toggle").forEach((chip) => chip.classList.remove("active"));
  const noteInput = document.getElementById("activity-other-note-input");
  noteInput.hidden = true;
  noteInput.value = "";
  updateActivityButtonUi();
}

// Item 106: resetActivityPicker's counterpart for pre-filling the edit flow. The chips carry
// their own state in the DOM (each toggles its own .active class, see initActivityPicker), so
// restoring a saved selection means writing BOTH the set and the chips -- setting only the set
// would show an unselected picker that silently re-submits selections the user never saw.
function setActivityPickerSelection(activities, note) {
  manualSelectedActivities.clear();
  (activities || []).forEach((key) => manualSelectedActivities.add(key));
  manualActivityOtherNote = note || "";
  document.querySelectorAll("#activity-chips .chip-toggle").forEach((chip) => {
    chip.classList.toggle("active", manualSelectedActivities.has(chip.dataset.activity));
  });
  const noteInput = document.getElementById("activity-other-note-input");
  const otherSelected = manualSelectedActivities.has("OTHER");
  noteInput.hidden = !otherSelected;
  noteInput.value = otherSelected ? manualActivityOtherNote : "";
  updateActivityButtonUi();
}

function manualTotalCount() {
  return manualCounts.whites + manualCounts.greys + manualCounts.calves + manualCounts.unknown;
}

function setManualSubmitStatus(message, isError = false) {
  const el = document.getElementById("manual-submit-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

// Bound to "← SUBMIT" -- matches ManualLoggingScreen's onClick: the pin (map center) IS the
// whale position, no projection, same geofence flow as the camera path.
async function submitManualSighting() {
  if (manualTotalCount() === 0) {
    setManualSubmitStatus("Enter at least one whale count before submitting.", true);
    return;
  }

  // Item 34: manualSelectedTimestampMs() reads the SET DATE/TIME input directly -- unlike the
  // camera path's Date.now(), there's no freeze-vs-reread mismatch to worry about here, since the
  // confirm modal is itself modal (the input can't change while it's showing).
  const directionText = manualTravelBearingDegrees != null
    ? `Travel direction: ${Math.round(manualTravelBearingDegrees)}°`
    : "Travel direction: Unknown";
  // Item 106: same summary, same geofence flow, same modal -- only the wording changes, so an
  // edit can never skip a confirmation step a new report gets (or vice versa).
  document.getElementById("submit-confirm-title").textContent =
    editingSighting ? "Save Changes" : "Confirm Sighting";
  showSubmitConfirmModal(
    formatWhaleCountsSummary(manualCounts),
    directionText,
    new Date(manualSelectedTimestampMs()).toLocaleString(),
    formatActivitiesSummary(Array.from(manualSelectedActivities), manualActivityOtherNote),
    () => proceedManualSubmit()
  );
}

async function proceedManualSubmit() {
  const button = document.getElementById("manual-submit-btn");
  button.disabled = true;
  setManualSubmitStatus("Checking location…");

  const lat = manualLat;
  const lng = manualLng;
  // Local-only search buffer for the geofence check -- never shown to the user, never saved to
  // the record (uncertainty_radius_meters/uncertainty_bucket stay null for a PIN row), matching
  // ManualLoggingScreen's own fixed MEDIUM/shore default exactly.
  const geofenceCheckRadiusMeters = distanceBucketRadiusMeters("MEDIUM", false);

  if (!isWithinOuterGeofence(lat, lng)) {
    document.getElementById("outer-geofence-reject-modal").hidden = false;
    pushNavLayer("outer-geofence-reject-modal", () => {
      document.getElementById("outer-geofence-reject-modal").hidden = true;
    });
    button.disabled = false;
    return;
  }

  const finish = (verified) => finishManualSubmit(lat, lng, verified);
  const verified = isWhalePositionVerified(lat, lng, geofenceCheckRadiusMeters);
  if (verified === true) {
    await finish(true);
  } else if (verified === false) {
    button.disabled = false;
    setManualSubmitStatus("");
    showGeofenceWarning(lat, lng, finish);
  } else {
    setManualSubmitStatus("Checking water data…");
    const validByChannel = await isWithinCoastlineChannelFallback(lat, lng);
    button.disabled = false;
    if (validByChannel) {
      await finish(true);
    } else {
      setManualSubmitStatus("");
      showGeofenceWarning(lat, lng, finish);
    }
  }
}

function buildManualSightingRecord(lat, lng, isGeofenceVerified) {
  return {
    whale_lat: lat,
    whale_lng: lng,
    travel_bearing_degrees: manualTravelBearingDegrees,
    travel_bearing_source: manualTravelBearingDegrees != null ? "MANUAL" : null,
    position_source: "PIN",
    count_whites: manualCounts.whites,
    count_greys: manualCounts.greys,
    count_calves: manualCounts.calves,
    count_unknown: manualCounts.unknown,
    observed_at_epoch_ms: manualSelectedTimestampMs(),
    observer_type: manualObserverType,
    is_geofence_verified: isGeofenceVerified,
    photo_url: null,
    subscriber_id: getOrCreateSubscriberId(),
    // Item 90: null (not []) when nothing's selected -- matches every other optional field on
    // this record. activity_note only ever accompanies OTHER, and only when it has real text;
    // capped at 140 chars to match the column's own CHECK constraint (also enforced by the input's
    // own maxlength, this is just a defensive belt-and-suspenders match).
    activities: manualSelectedActivities.size > 0 ? Array.from(manualSelectedActivities) : null,
    activity_note: (manualSelectedActivities.has("OTHER") && manualActivityOtherNote.trim())
      ? manualActivityOtherNote.trim().slice(0, 140)
      : null
  };
}

async function finishManualSubmit(lat, lng, isGeofenceVerified) {
  // Item 106: an edit takes the SAME route to get here (submitManualSighting's count check, the
  // confirm modal, proceedManualSubmit's outer-geofence rejection and its coastline/SAVE ANYWAY
  // warning) and only diverges at the actual write -- isGeofenceVerified included, which is why
  // it's passed straight through: the flag stored against an edited position is recomputed by the
  // SAME checks that produced it on insert, running right here, not carried over from wherever
  // the whale was originally placed. edit_my_last_sighting refuses a position patch that doesn't
  // carry one (see its own header comment).
  if (editingSighting) {
    await finishSightingEdit(lat, lng, isGeofenceVerified);
    return;
  }

  const button = document.getElementById("manual-submit-btn");
  button.disabled = true;
  setManualSubmitStatus("Submitting…");

  try {
    const record = buildManualSightingRecord(lat, lng, isGeofenceVerified);
    // Item 60: capturedPhotoBlob is non-null only when this visit came via the camera path
    // (enterManualLogStepFromCamera/updateManualPhotoThumb) -- plain Report Manually never sets
    // it, so this is null there exactly as it always was.
    const result = await submitOrQueueSighting(record, capturedPhotoBlob);

    if (result.ok) {
      setManualSubmitStatus("Sighting submitted! Thank you.");
      resetManualSubmitForm();
      await refreshSightings();
    } else if (result.queued) {
      setManualSubmitStatus("You're offline -- this sighting is saved on your device and will upload automatically once you're back online.");
      resetManualSubmitForm();
    } else {
      setManualSubmitStatus("Couldn't save the sighting -- check your connection and try again.", true);
    }
  } finally {
    button.disabled = false;
  }
}

function resetManualSubmitForm() {
  resetWhaleCountUi("#manual-log-step", manualCounts);
  setManualDatetimeInputToNow();
  updateManualDatetimeButtonLabel();
  // Item 60: the two entry points differ by exactly one nav-stack layer (see
  // manualLogStepReachedViaCameraPath's own declaration comment) -- the camera path's extra
  // "manual-log-step-from-camera" layer needs popping (matching the old camera-path reset's own
  // navigateBack()), the plain path's doesn't (matching ITS own pre-existing behavior: manual
  // mode's manual-log-step IS the "manual-report" layer's own resting content, see
  // openManualReportFlow's own push in main-menu.js, so returning to camera-step there is just a
  // visual change, not a back-stack pop). Either way goToCameraStep is what actually resets the
  // bearing dial/observer-type/photo state -- directly here, or via navigateBack's onPop there.
  if (manualLogStepReachedViaCameraPath) {
    navigateBack();
  } else {
    goToCameraStep();
  }
}

// --- Item 106: edit this device's own most recent sighting ---
//
// Reuses #manual-log-step wholesale (the same screen both reporting entry points already share
// since item 60) rather than building a second editor: same map/crosshair for the position, same
// BearingDial for the direction, same counts and ACTIVITY picker, pre-filled from the row as it
// currently stands. Reached from the EDIT button on the map popup or the list item, which is
// drawn on exactly one row (editSightingButtonHtml, map-view.js).
//
// WHAT AN EDIT CAN CHANGE HERE: counts, activities/note, position (and, with it, the recomputed
// is_geofence_verified flag -- see finishManualSubmit), travel direction. The
// date/time and SELF/OTHER controls are disabled rather than hidden -- they're part of the record
// being edited and worth seeing, but observed_at is the one claim edit_my_last_sighting refuses
// to revise (see its header comment) and observer_type isn't in its allowlist at all.
//
// THE PHOTO IS SHOWN BUT NOT REPLACEABLE, deliberately and for now: the RPC accepts a photo_url
// patch, but offering a replacement here means routing back through the camera step and holding
// this edit's state across it, which is a bigger change to the reporting flow than this item
// needs. A sighting's photo therefore can't be swapped or removed from the PWA yet.
const EDIT_MAP_ZOOM = 14;

function openEditSightingFlow(sighting) {
  // Same previous-tab capture the menu's own navigation items use (main-menu.js) -- an edit is
  // started from Map or List, and the back gesture has to return to whichever one it was.
  const previousTab = getActiveTabName();
  editingSighting = sighting;
  manualLogStepReachedViaCameraPath = false;
  capturedPhotoBlob = null;

  switchTab("submit");
  stopCamera();
  document.getElementById("camera-step").hidden = true;
  document.getElementById("manual-log-step").hidden = false;

  initManualMapIfNeeded();
  if (sighting.whale_lat != null && sighting.whale_lng != null) {
    // Straight to the recorded position -- the whole point of an edit is to adjust THAT, not to
    // start again from the Cook Inlet overview. GPS is never fetched on this path (neither entry
    // point's auto-fix applies: the position being edited is already known).
    manualMapInstance.setView([sighting.whale_lat, sighting.whale_lng], EDIT_MAP_ZOOM);
  }

  setWhaleCountUi("#manual-log-step", manualCounts, {
    whites: sighting.count_whites,
    greys: sighting.count_greys,
    calves: sighting.count_calves,
    unknown: sighting.count_unknown
  });
  manualTravelBearingDegrees = sighting.travel_bearing_degrees ?? null;
  updateBearingDialNeedle(manualTravelBearingDegrees);
  setActivityPickerSelection(sighting.activities, sighting.activity_note);
  setManualObserverType(sighting.observer_type === "OTHER" ? "OTHER" : "SELF");

  document.getElementById("manual-datetime-input").value = sighting.observed_at_epoch_ms
    ? formatDatetimeLocalValue(new Date(sighting.observed_at_epoch_ms))
    : formatDatetimeLocalValue(new Date());
  updateManualDatetimeButtonLabel();

  showEditModePhotoThumb(sighting.photo_url);
  applyEditModeUi();
  setManualSubmitStatus("Editing your last report. The date/time it was seen can't be changed.");

  pushNavLayer("edit-sighting", () => {
    exitEditSightingFlow();
    switchTab(previousTab);
  });
}

// Item 106: the already-uploaded photo, straight from its public URL -- NOT updateManualPhotoThumb,
// which renders capturedPhotoBlob (a pending capture that only ever exists on the camera path).
function showEditModePhotoThumb(photoUrl) {
  const thumbBtn = document.getElementById("manual-photo-thumb-btn");
  const thumbImg = document.getElementById("manual-photo-thumb");
  if (photoUrl) {
    thumbImg.src = photoUrl;
    thumbBtn.hidden = false;
  } else {
    thumbImg.src = "";
    thumbBtn.hidden = true;
  }
}

// Every control whose behavior differs between reporting and editing, in one place, driven off
// editingSighting alone -- so exitEditSightingFlow restoring it is a single call, not a list of
// individual undos that could fall out of step with this list.
function applyEditModeUi() {
  const isEdit = editingSighting != null;
  document.getElementById("manual-submit-btn").textContent = isEdit ? "← SAVE CHANGES" : "← SUBMIT";
  document.getElementById("manual-datetime-btn").disabled = isEdit;
  document.getElementById("manual-observer-self-btn").disabled = isEdit;
  document.getElementById("manual-observer-other-btn").disabled = isEdit;
}

// Runs on ANY exit from an edit -- the back gesture (this is the nav layer's own onPop), and a
// saved edit (which pops that same layer). Leaves the screen in the state a plain "Report
// Manually" entry would find it in, since #manual-log-step is shared with both reporting paths.
function exitEditSightingFlow() {
  editingSighting = null;
  applyEditModeUi();
  setManualSubmitStatus("");
  resetWhaleCountUi("#manual-log-step", manualCounts);
  resetManualPositionControls();
  showEditModePhotoThumb(null);
  setManualDatetimeInputToNow();
  updateManualDatetimeButtonLabel();
  document.getElementById("manual-log-step").hidden = true;
  document.getElementById("camera-step").hidden = false;
}

// The patch sent to edit_my_last_sighting -- EVERY editable field, every time, including the ones
// that didn't change. The RPC treats an absent key as "leave alone" and a present one as "set to
// this", so sending the whole editable set means what's stored afterward is exactly what this
// screen was showing, with no diffing logic here to get wrong. photo_url is deliberately absent
// (never patched -- see openEditSightingFlow's own comment); observed_at/observer_type/
// observer_tier/confirmed_at aren't in the RPC's allowlist at all.
//
// is_geofence_verified travels WITH the position, always -- the RPC refuses a position patch
// without it, and refuses it without a position. The value is this submission's own freshly-run
// check (finishManualSubmit's isGeofenceVerified), exactly the value an insert of the same
// position would have carried.
function buildSightingEditPatch(lat, lng, isGeofenceVerified) {
  return {
    whale_lat: lat,
    whale_lng: lng,
    is_geofence_verified: isGeofenceVerified,
    travel_bearing_degrees: manualTravelBearingDegrees,
    count_whites: manualCounts.whites,
    count_greys: manualCounts.greys,
    count_calves: manualCounts.calves,
    count_unknown: manualCounts.unknown,
    // Same null-not-[] and 140-char conventions buildManualSightingRecord uses on insert.
    activities: manualSelectedActivities.size > 0 ? Array.from(manualSelectedActivities) : null,
    activity_note: (manualSelectedActivities.has("OTHER") && manualActivityOtherNote.trim())
      ? manualActivityOtherNote.trim().slice(0, 140)
      : null
  };
}

async function finishSightingEdit(lat, lng, isGeofenceVerified) {
  const button = document.getElementById("manual-submit-btn");
  button.disabled = true;
  setManualSubmitStatus("Saving changes…");

  try {
    const patch = buildSightingEditPatch(lat, lng, isGeofenceVerified);
    const ok = await editMyLastSighting(getOrCreateSubscriberId(), patch);
    if (ok) {
      // navigateBack pops the edit layer, whose onPop IS exitEditSightingFlow -- so the cleanup
      // and the return to Map/List are the same single path a back gesture takes, never a second
      // copy of it. refreshSightings afterward picks up the real edited_at (and re-asks which row
      // is editable), same reasoning handleConfirmSightingClick's own post-action refetch uses.
      navigateBack();
      await refreshSightings();
    } else {
      // The ordinary reason to land here is the window having closed (or a newer sighting having
      // been reported) between this screen opening and SAVE -- rare, but the only honest thing to
      // do is say so rather than leave the edit looking pending. The row itself is untouched.
      setManualSubmitStatus("Couldn't save the changes -- this sighting may no longer be editable.", true);
    }
  } finally {
    button.disabled = false;
  }
}

// --- Shared geofence-warning modal (both paths) ---

function showGeofenceWarning(lat, lng, finishAction) {
  pendingFinishAction = finishAction;
  document.getElementById("geofence-warning-text").textContent =
    `The whale position (${lat.toFixed(4)}, ${lng.toFixed(4)}) falls outside ` +
    "the primary observation area for Cook Inlet. Do you still want to log this sighting?";
  document.getElementById("geofence-warning-modal").hidden = false;
  pushNavLayer("geofence-warning-modal", () => {
    document.getElementById("geofence-warning-modal").hidden = true;
  });
}
