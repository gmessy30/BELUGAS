// Camera tab (native's own menu label -- App.kt's MainMenuDrawer). Ports CaptureScreen.kt (live
// camera + reticle) then LoggingScreen.kt (captured photo full-bleed, DONE/RETAKE row, 3 relative
// direction arrows overlaid on the photo, a heading+distance trigger that PROJECTS the whale
// position, whale-count row) as two steps within this tab.
//
// ManualLoggingScreen.kt ("Report Manually", see openManualReportFlow below) is a STRUCTURALLY
// DIFFERENT native screen -- a real interactive map with a fixed center pin, SELF/OTHER toggle,
// no heading/distance concept at all -- so it's its own separate #manual-log-step section here,
// not a shared/overloaded copy of the camera path's review step (an earlier pass in this app's
// history conflated the two into one shared UI; restoring the real per-screen native behavior is
// exactly what un-does that).
//
// HEADING INPUT: no live compass-sensor attempt on the web (cross-browser magnetometer access is
// inconsistent, gated behind extra permission prompts on iOS Safari in particular) -- always the
// manual-entry path (8-point chips + slider) HeadingDistanceDialog itself already falls back to
// when its own sensor read comes back null. Not an invented simplification, native's own fallback
// UI reused directly.
//
// COASTLINE FALLBACK NOT PORTED: LoggingScreen still tries CoastlineGeometry's offshore-guess
// projection (FALLBACK) when no heading was given at all -- that needs a real port of
// CoastlineGeometry's raycasting, out of scope here. This app instead always shows the "Can't
// Place This Sighting" dialog (a real native dialog, just reached unconditionally on no-heading
// rather than only once a fallback guess also fails) -- flagged, not silently narrowed.

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

const cameraCounts = { whites: 0, greys: 0, calves: 0, unknown: 0 };
const manualCounts = { whites: 0, greys: 0, calves: 0, unknown: 0 };

// --- Camera-path position state (LoggingScreen.kt) ---
let selectedPodDirection = "NONE"; // AWAY/LEFT/RIGHT/NONE -- relative to the observer
let headingDegrees = null; // null = not set yet
let headingIsAerial = false; // cached once per review-step visit, see goToReviewStep
// BUG FIX (item 47): was "MEDIUM" -- an unreviewed default let a sighting submit with a real
// heading but a distance nobody ever actually chose. null now, same as headingDegrees, until
// explicitly set via a distance chip tap (see the heading-distance modal's own draft handling).
let selectedDistanceBucketKey = null;
let pendingHeadingDegreesDraft = null; // the heading-distance modal's own working value pre-confirm
// BUG FIX (item 47): mirrors pendingHeadingDegreesDraft above -- the distance chips used to write
// selectedDistanceBucketKey directly and immediately (even if the modal was then CANCELLED), with
// no pending/draft/confirm step at all, unlike heading. Now both fields go through the identical
// draft-then-commit-on-CONFIRM path.
let pendingDistanceBucketKeyDraft = null;

// --- Manual-path state (ManualLoggingScreen.kt) ---
let manualMapInstance = null;
let manualLat = DEFAULT_MAP_CENTER[0];
let manualLng = DEFAULT_MAP_CENTER[1];
let manualTravelBearingDegrees = null;
let manualObserverType = "SELF";

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

// Nearest-8-point label for a quick-glance display only (exact degrees are shown alongside it) --
// same rounding-for-display-only treatment map-view.js's formatTravelDirection already uses for
// stored travel bearings, applied here to the heading-to-whale value instead.
function compassLabelForDegrees(degrees) {
  const normalized = ((degrees % 360) + 360) % 360;
  const index = Math.round(normalized / 45) % 8;
  return COMPASS_POINTS_8[index][0];
}

// Item 34: compact readable summary (counts spelled out, direction/heading, time) with CONFIRM/
// BACK, shown before EITHER path's actual submit logic runs -- requires a deliberate tap, never
// auto-dismisses. onConfirm is deferred until the CONFIRM button's own click handler below, not
// called from here.
function showSubmitConfirmModal(countsText, directionText, timeText, onConfirm) {
  document.getElementById("confirm-summary-counts").textContent = countsText;
  document.getElementById("confirm-summary-direction").textContent = directionText;
  document.getElementById("confirm-summary-time").textContent = `Time: ${timeText}`;
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
  document.getElementById("skip-camera-btn").addEventListener("click", () => goToReviewStep());
  document.getElementById("retake-btn").addEventListener("click", retakePhoto);
  document.getElementById("done-btn").addEventListener("click", submitCameraSighting);
  document.getElementById("manual-submit-btn").addEventListener("click", submitManualSighting);

  document.getElementById("submit-confirm-back-btn").addEventListener("click", () => navigateBack());
  document.getElementById("submit-confirm-confirm-btn").addEventListener("click", () => {
    navigateBack();
    if (pendingConfirmAction) pendingConfirmAction();
  });

  document.getElementById("outer-geofence-reject-ok-btn").addEventListener("click", () => navigateBack());
  document.getElementById("geofence-warning-cancel-btn").addEventListener("click", () => navigateBack());
  document.getElementById("geofence-warning-save-btn").addEventListener("click", () => {
    navigateBack();
    if (pendingFinishAction) pendingFinishAction(); // SAVE ANYWAY -- not geofence-verified
  });

  document.getElementById("cannot-place-cancel-btn").addEventListener("click", () => navigateBack());
  document.getElementById("cannot-place-goto-manual-btn").addEventListener("click", () => {
    navigateBack();
    // Already inside the submit tab (this dialog only ever shows from the camera-path review
    // step), and the review-step nav-stack layer already on the stack is fine left as-is --
    // goToCameraStep (its onPop) hides BOTH #review-step and #manual-log-step defensively, so it
    // still tears down correctly whichever one is actually showing when this is eventually
    // backed out of. No new layer needed for this in-tab transition.
    openManualReportFlow();
  });

  initDirectionArrows();
  initHeadingDistanceModal();
  initWhaleCountWiring("#review-step", cameraCounts);
  initReviewBottomPanelHeightTracking();

  initManualObserverToggle();
  initManualPositionControls();
  initWhaleCountWiring("#manual-log-step", manualCounts);

  startCamera();
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

function handleCameraZoomInput(event) {
  const value = parseFloat(event.target.value);
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
      goToReviewStep();
    },
    "image/jpeg",
    0.85
  );
}

// Fetches the observer's altitude once per visit (for the heading dialog's isAerial bucket
// sizing, matching LoggingScreen's own LaunchedEffect(Unit) fetch) -- best-effort, defaults to
// shore (0m/non-aerial) if location isn't available, same as native's `?: 0.0` fallback.
async function cacheObserverAltitudeForHeadingDialog() {
  if (!navigator.geolocation) return;
  await new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        headingIsAerial = (position.coords.altitude || 0) > 100;
        resolve();
      },
      () => resolve(),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
    );
  });
}

function goToReviewStep() {
  stopCamera();

  const photoEl = document.getElementById("review-photo");
  if (reviewPhotoObjectUrl) {
    URL.revokeObjectURL(reviewPhotoObjectUrl);
    reviewPhotoObjectUrl = null;
  }
  if (capturedPhotoBlob) {
    reviewPhotoObjectUrl = URL.createObjectURL(capturedPhotoBlob);
    photoEl.src = reviewPhotoObjectUrl;
    photoEl.hidden = false;
  } else {
    photoEl.hidden = true; // no photo (camera unavailable, user chose to skip)
  }
  document.getElementById("review-step").classList.toggle("no-photo", !capturedPhotoBlob);
  document.getElementById("done-btn").textContent = "← DONE";
  document.getElementById("retake-btn").hidden = false;

  document.getElementById("camera-step").hidden = true;
  document.getElementById("review-step").hidden = false;
  measureReviewBottomPanelHeightNow();

  cacheObserverAltitudeForHeadingDialog();

  // Pushes its own nav-stack layer (see nav-stack.js) so the back gesture/hardware back button
  // returns to the camera step exactly like RETAKE does -- goToCameraStep is literally RETAKE's
  // own teardown, reused directly as this layer's onPop.
  pushNavLayer("review-step", goToCameraStep);
}

// Matches LoggingScreen's onDoneClick/onRetakeClick and ManualLoggingScreen's onDoneClick alike --
// all three return to Screen.CAPTURE, ready for the next report. Shared exit point for both this
// app's review-step (camera) and manual-log-step, since either one might be the caller.
function goToCameraStep() {
  if (reviewPhotoObjectUrl) {
    URL.revokeObjectURL(reviewPhotoObjectUrl);
    reviewPhotoObjectUrl = null;
  }
  document.getElementById("review-step").hidden = true;
  document.getElementById("manual-log-step").hidden = true;
  document.getElementById("camera-step").hidden = false;
  resetCameraPositionControls();
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
}

function retakePhoto() {
  capturedPhotoBlob = null;
  navigateBack(); // pops the review-step layer; its onPop IS goToCameraStep
}

function resetCameraPositionControls() {
  selectedPodDirection = "NONE";
  document.querySelectorAll(".direction-arrow-btn").forEach((b) => b.classList.remove("selected"));
  headingDegrees = null;
  selectedDistanceBucketKey = null; // BUG FIX (item 47): was "MEDIUM" -- see that variable's own comment
  updateHeadingDistanceButtonLabel();
}

// Mirrors ManualLoggingScreen.kt's own bottomPanelHeightPx/onGloballyPositioned pattern (used
// there to anchor RECENTER above its bottom panel) for the same underlying problem: the direction
// arrows need to know the REAL rendered height of #review-step's bottom panel, not a guessed
// constant, so they never overlap it regardless of viewport height or font metrics (see
// .direction-arrows-overlay's own comment in style.css). ResizeObserver reports the panel's
// actual box whenever it changes -- including the very first time it becomes visible, since going
// from a hidden ancestor to shown is itself a real resize from 0x0.
function initReviewBottomPanelHeightTracking() {
  const panel = document.querySelector("#review-step .review-bottom-panel");
  const reviewStep = document.getElementById("review-step");
  if (!panel || !reviewStep || typeof ResizeObserver === "undefined") return;
  const observer = new ResizeObserver((entries) => {
    for (const entry of entries) {
      reviewStep.style.setProperty("--review-bottom-panel-height", `${Math.ceil(entry.contentRect.height)}px`);
    }
  });
  observer.observe(panel);
}

// Belt-and-suspenders alongside the ResizeObserver above: a hidden-ancestor-to-visible transition
// (exactly what happens every time goToReviewStep runs) is the one case a ResizeObserver callback
// isn't guaranteed to fire promptly/at all for across every browser, so this explicitly re-measures
// the instant the panel is actually laid out and visible (one rAF after unhiding, not the same
// tick -- the browser hasn't computed its real box yet at the moment `hidden` is cleared). The
// z-index fix on .direction-arrows-overlay (style.css) means the arrows are never truly occluded/
// unclickable even if this were somehow stale, but there's no reason to leave the reservation
// itself wrong when it's this cheap to get right immediately.
function measureReviewBottomPanelHeightNow() {
  const panel = document.querySelector("#review-step .review-bottom-panel");
  const reviewStep = document.getElementById("review-step");
  if (!panel || !reviewStep) return;
  requestAnimationFrame(() => {
    reviewStep.style.setProperty("--review-bottom-panel-height", `${Math.ceil(panel.getBoundingClientRect().height)}px`);
  });
}

function initDirectionArrows() {
  document.querySelectorAll(".direction-arrow-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".direction-arrow-btn").forEach((b) => b.classList.remove("selected"));
      btn.classList.add("selected");
      selectedPodDirection = btn.dataset.podDirection;
    });
  });
}

function initHeadingDistanceModal() {
  document.getElementById("heading-distance-btn").addEventListener("click", openHeadingDistanceModal);
  document.getElementById("heading-distance-cancel-btn").addEventListener("click", () => navigateBack());
  document.getElementById("heading-distance-confirm-btn").addEventListener("click", () => {
    headingDegrees = pendingHeadingDegreesDraft;
    // BUG FIX (item 47): distance now commits on CONFIRM exactly like heading, instead of the
    // chip click writing selectedDistanceBucketKey directly and immediately (which meant a
    // distance change "stuck" even if the user then hit CANCEL, and meant there was no way for
    // it to ever be null -- see this variable's own declaration comment).
    selectedDistanceBucketKey = pendingDistanceBucketKeyDraft;
    updateHeadingDistanceButtonLabel();
    navigateBack();
  });
  document.getElementById("heading-slider").addEventListener("input", (event) => {
    pendingHeadingDegreesDraft = Number(event.target.value);
    document.getElementById("heading-slider-value").textContent = `${pendingHeadingDegreesDraft}°`;
    renderHeadingCompassChips();
  });
}

function openHeadingDistanceModal() {
  // BUG FIX (item 47): no "?? 0"/"?? MEDIUM" fallback here anymore -- a genuinely never-set
  // field (headingDegrees/selectedDistanceBucketKey both null) now stays null in the draft too,
  // rather than silently seeding a fabricated starting value that CONFIRM would then commit as
  // if it had been deliberately chosen. Re-opening to adjust an ALREADY-confirmed value still
  // carries that real value over correctly, since headingDegrees/selectedDistanceBucketKey
  // themselves are what's being read here, not a hardcoded default.
  pendingHeadingDegreesDraft = headingDegrees;
  pendingDistanceBucketKeyDraft = selectedDistanceBucketKey;
  const sliderDisplayValue = pendingHeadingDegreesDraft ?? 0;
  document.getElementById("heading-slider").value = String(sliderDisplayValue);
  document.getElementById("heading-slider-value").textContent =
    pendingHeadingDegreesDraft != null ? `${sliderDisplayValue}°` : "Not set";
  renderHeadingCompassChips();
  renderHeadingDistanceChips();
  document.getElementById("heading-distance-modal").hidden = false;
  pushNavLayer("heading-distance-modal", () => {
    document.getElementById("heading-distance-modal").hidden = true;
  });
}

// Same 8 points as COMPASS_POINTS in HeadingDistancePicker.kt.
const COMPASS_POINTS_8 = [
  ["N", 0], ["NE", 45], ["E", 90], ["SE", 135], ["S", 180], ["SW", 225], ["W", 270], ["NW", 315]
];

function renderHeadingCompassChips() {
  const container = document.getElementById("heading-compass-chips");
  container.innerHTML = "";
  COMPASS_POINTS_8.forEach(([label, degrees]) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (pendingHeadingDegreesDraft === degrees ? " active" : "");
    chip.textContent = label;
    chip.addEventListener("click", () => {
      pendingHeadingDegreesDraft = degrees;
      document.getElementById("heading-slider").value = String(degrees);
      document.getElementById("heading-slider-value").textContent = `${degrees}°`;
      renderHeadingCompassChips();
    });
    container.appendChild(chip);
  });
}

function renderHeadingDistanceChips() {
  const container = document.getElementById("heading-distance-chips");
  container.innerHTML = "";
  DISTANCE_BUCKETS.forEach((bucket) => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip-toggle" + (pendingDistanceBucketKeyDraft === bucket.key ? " active" : "");
    chip.textContent = distanceBucketShortLabel(bucket.key, headingIsAerial);
    chip.addEventListener("click", () => {
      pendingDistanceBucketKeyDraft = bucket.key;
      renderHeadingDistanceChips();
    });
    container.appendChild(chip);
  });
}

function updateHeadingDistanceButtonLabel() {
  const btn = document.getElementById("heading-distance-btn");
  // BUG FIX (item 47): both required now, not just heading -- selectedDistanceBucketKey can
  // genuinely be null (see its own declaration comment), and DISTANCE_BUCKETS.find(...) would
  // throw on a null key rather than just returning undefined.
  if (headingDegrees != null && selectedDistanceBucketKey != null) {
    const bucketLabel = DISTANCE_BUCKETS.find((b) => b.key === selectedDistanceBucketKey).label.toUpperCase();
    btn.textContent = `🧭 ${Math.round(headingDegrees)}° · ${bucketLabel}`;
  } else {
    btn.textContent = "🧭 SET HEADING & DISTANCE";
  }
}

function cameraTotalCount() {
  return cameraCounts.whites + cameraCounts.greys + cameraCounts.calves + cameraCounts.unknown;
}

function setSubmitStatus(message, isError = false) {
  const el = document.getElementById("submit-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

// Bound to the review step's "← DONE" button -- matches LoggingScreen's onClick: fetches a fresh
// GPS fix as the OBSERVER's own position (the projection origin, never stored as whale_lat/lng
// itself), projects the whale position outward from it via heading+distance, then runs the same
// geofence flow every path in this app uses (coarse outer-bound hard reject, then the real
// buffer-distance check with an online fallback and a SAVE ANYWAY override).
async function submitCameraSighting() {
  if (cameraTotalCount() === 0) {
    setSubmitStatus("Enter at least one whale count before submitting.", true);
    return;
  }
  // BUG FIX (item 47): distance used to default to "MEDIUM" and never actually require a tap --
  // a user could set heading, hit CONFIRM in the Heading & Distance modal without ever touching a
  // distance chip, and silently submit at MEDIUM's radius, unreviewed. Distance is now gated the
  // same way heading already is: null until explicitly chosen (see openHeadingDistanceModal/
  // renderHeadingDistanceChips), so either one missing routes here, matching this dialog's own
  // existing "no CoastlineGeometry fallback-guess port -- always directed here when no heading
  // was set" reasoning exactly (a radius with no real distance behind it is exactly as fabricated
  // as a position with no real heading behind it).
  if (headingDegrees == null || selectedDistanceBucketKey == null) {
    document.getElementById("cannot-place-modal").hidden = false;
    pushNavLayer("cannot-place-modal", () => {
      document.getElementById("cannot-place-modal").hidden = true;
    });
    return;
  }
  if (!navigator.geolocation) {
    setSubmitStatus("Geolocation isn't available in this browser.", true);
    return;
  }

  // Item 34: frozen here rather than re-read from Date.now() again after confirmation -- the
  // time shown in the summary is exactly the time that ends up stored, not an approximation of it.
  const observedAtEpochMs = Date.now();
  const directionParts = [
    `Heading to whale: ${Math.round(headingDegrees)}° (${compassLabelForDegrees(headingDegrees)}) · ` +
      distanceBucketShortLabel(selectedDistanceBucketKey, headingIsAerial)
  ];
  if (selectedPodDirection !== "NONE") {
    directionParts.push(`Pod moving: ${selectedPodDirection}`);
  }
  showSubmitConfirmModal(
    formatWhaleCountsSummary(cameraCounts),
    directionParts.join(" · "),
    new Date(observedAtEpochMs).toLocaleString(),
    () => proceedCameraSubmit(observedAtEpochMs)
  );
}

async function proceedCameraSubmit(observedAtEpochMs) {
  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Getting your location…");

  try {
    const position = await getBestGpsFix((sample) => {
      setSubmitStatus(`Getting your location… (best so far: ±${Math.round(sample.coords.accuracy)}m)`);
    });
    // Item 30a: surfaced so a poor fix is visible BEFORE it gets baked into a projected whale
    // position several hundred meters off -- native never shows this either (LocationService.kt's
    // LocationCoordinates doesn't even carry accuracy), but a raw browser GPS fix is more variable
    // than the OS-level fused/CoreLocation APIs native calls, so this app surfaces it where native
    // doesn't need to.
    console.log("GEOLOCATION_FIX_ACCURACY_METERS", position.coords.accuracy);
    setSubmitStatus(`Location acquired (±${Math.round(position.coords.accuracy)}m). Checking location…`);
    const radiusMeters = distanceBucketRadiusMeters(selectedDistanceBucketKey, headingIsAerial);
    const [whaleLat, whaleLng] = destinationPoint(
      position.coords.latitude, position.coords.longitude, headingDegrees, radiusMeters
    );
    continueCameraSubmit(whaleLat, whaleLng, radiusMeters, observedAtEpochMs);
  } catch (err) {
    console.error("GEOLOCATION_ERROR", err);
    setSubmitStatus("Couldn't get your location (" + (err.message || err) + "). Check location permissions.", true);
    button.disabled = false;
  }
}

// Item 30a: a single getCurrentPosition() call can hand back a poor fix even with
// enableHighAccuracy/maximumAge:0 -- a first-fix-after-idle chipset warm-up in particular, common
// in a browser tab/PWA that isn't holding a location session open the way a native app's fused/
// CoreLocation client does. Sampling a short burst via watchPosition and keeping whichever fix
// reports the best (lowest) coords.accuracy is far more reliable than trusting whatever the FIRST
// callback happens to deliver -- exactly the "prime suspect" for a projected position landing
// hundreds of meters off. Resolves early once a good-enough fix arrives rather than always
// waiting out the full sampling window.
const GPS_FIX_SAMPLE_WINDOW_MS = 5000;
const GPS_FIX_GOOD_ENOUGH_ACCURACY_METERS = 20;

function getBestGpsFix(onSample) {
  return new Promise((resolve, reject) => {
    let best = null;
    let settled = false;

    const finish = (fatalErr) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      navigator.geolocation.clearWatch(watchId);
      if (best) resolve(best);
      else reject(fatalErr || new Error("No location fix was received."));
    };

    const timer = setTimeout(finish, GPS_FIX_SAMPLE_WINDOW_MS);

    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        if (!best || position.coords.accuracy < best.coords.accuracy) {
          best = position;
          onSample?.(position);
        }
        if (position.coords.accuracy <= GPS_FIX_GOOD_ENOUGH_ACCURACY_METERS) {
          finish();
        }
      },
      (err) => {
        // Permission denial is terminal -- no reason to burn the whole sampling window waiting
        // on a sample that will never arrive (matches the old single-shot call's fail-fast
        // behavior for this case). Other errors (timeout/position-unavailable) might still be
        // followed by a later successful sample within the window, so only bail early here.
        if (!best && err.code === err.PERMISSION_DENIED) finish(err);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: GPS_FIX_SAMPLE_WINDOW_MS }
    );
  });
}

async function continueCameraSubmit(whaleLat, whaleLng, radiusMeters, observedAtEpochMs) {
  const button = document.getElementById("done-btn");

  if (!isWithinOuterGeofence(whaleLat, whaleLng)) {
    document.getElementById("outer-geofence-reject-modal").hidden = false;
    pushNavLayer("outer-geofence-reject-modal", () => {
      document.getElementById("outer-geofence-reject-modal").hidden = true;
    });
    button.disabled = false;
    return;
  }

  setSubmitStatus("Checking location…");
  const finish = (verified) => finishCameraSubmit(whaleLat, whaleLng, radiusMeters, verified, observedAtEpochMs);
  const verified = isWhalePositionVerified(whaleLat, whaleLng, radiusMeters);
  if (verified === true) {
    await finish(true);
  } else if (verified === false) {
    button.disabled = false;
    setSubmitStatus("");
    showGeofenceWarning(whaleLat, whaleLng, finish);
  } else {
    setSubmitStatus("Checking water data…");
    const validByChannel = await isWithinCoastlineChannelFallback(whaleLat, whaleLng);
    button.disabled = false;
    if (validByChannel) {
      await finish(true);
    } else {
      setSubmitStatus("");
      showGeofenceWarning(whaleLat, whaleLng, finish);
    }
  }
}

function buildCameraSightingRecord(whaleLat, whaleLng, radiusMeters, isGeofenceVerified, observedAtEpochMs) {
  const travelBearing = podDirectionToAbsoluteTravelBearingDegrees(selectedPodDirection, headingDegrees);
  return {
    whale_lat: whaleLat,
    whale_lng: whaleLng,
    uncertainty_radius_meters: radiusMeters,
    uncertainty_bucket: selectedDistanceBucketKey,
    travel_bearing_degrees: travelBearing,
    travel_bearing_source: travelBearing != null ? "MANUAL" : null,
    position_source: "PROJECTED",
    count_whites: cameraCounts.whites,
    count_greys: cameraCounts.greys,
    count_calves: cameraCounts.calves,
    count_unknown: cameraCounts.unknown,
    observed_at_epoch_ms: observedAtEpochMs,
    observer_type: "SELF", // LoggingScreen has no SELF/OTHER toggle at all -- that's manual-only
    is_geofence_verified: isGeofenceVerified,
    photo_url: null,
    subscriber_id: getOrCreateSubscriberId()
  };
}

async function finishCameraSubmit(whaleLat, whaleLng, radiusMeters, isGeofenceVerified, observedAtEpochMs) {
  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Submitting…");

  try {
    const record = buildCameraSightingRecord(whaleLat, whaleLng, radiusMeters, isGeofenceVerified, observedAtEpochMs);
    const result = await submitOrQueueSighting(record, capturedPhotoBlob);

    if (result.ok) {
      setSubmitStatus("Sighting submitted! Thank you.");
      resetCameraSubmitForm();
      await refreshSightings();
    } else if (result.queued) {
      setSubmitStatus("You're offline -- this sighting is saved on your device and will upload automatically once you're back online.");
      resetCameraSubmitForm();
    } else {
      setSubmitStatus("Couldn't save the sighting -- check your connection and try again.", true);
    }
  } finally {
    button.disabled = false;
  }
}

function resetCameraSubmitForm() {
  capturedPhotoBlob = null;
  resetWhaleCountUi("#review-step", cameraCounts);
  // Pops the review-step layer; its onPop IS goToCameraStep, which also resets heading/direction/
  // distance for the next capture (matching native: LoggingScreen's remembered state doesn't
  // survive leaving and re-entering the screen).
  navigateBack();
}

// --- ManualLoggingScreen.kt (Report Manually path) ---

// Called from the main menu's "Report Manually" item (main-menu.js owns the switchTab/nav-stack
// push for THAT entry point, since it also has to remember/restore whichever tab was active
// before) and from the camera path's "Can't Place This Sighting" dialog (already inside the
// submit tab, no tab switch or new push needed there -- see that button's own handler above).
// This function itself only ever changes which sub-step is visible within the submit tab.
function openManualReportFlow() {
  switchTab("submit"); // idempotent if already the active tab (the cannot-place-modal call site)
  stopCamera();
  document.getElementById("camera-step").hidden = true;
  document.getElementById("review-step").hidden = true;
  document.getElementById("manual-log-step").hidden = false;

  // GPS is deliberately NOT auto-fetched here. Confirmed against ManualLoggingScreen.kt's real
  // source, native DOES call LaunchedEffect(Unit) { recenterOnGps() } on entry -- but only to set
  // the map's STARTING center as a convenience; the submitted position is always whatever the
  // fixed center pin reads at SUBMIT time (wherever the user has panned to), never locked to
  // that initial fetch. This app overrides that on purpose anyway (explicitly confirmed, not an
  // oversight): manual reporting is meant to be explicitly NOT "where I am now" from the moment
  // this screen opens, not just at submit time. The map starts at DEFAULT_MAP_CENTER instead, and
  // there is no manual GPS button at all here any more, matching native exactly.
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

function initManualPositionControls() {
  // View reset only -- no GPS refetch, matching ManualLoggingScreen's own RECENTER exactly (this
  // screen marks where the whales were, not the observer's own position). "Get My Location" was
  // removed entirely (see the HTML's own comment) -- it implied the observer's own position is
  // the whale's, which this screen exists specifically to not assume.
  document.getElementById("manual-recenter-btn").addEventListener("click", () => {
    manualMapInstance.setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);
  });

  document.getElementById("manual-datetime-btn").addEventListener("click", openManualDatetimePicker);
  document.getElementById("manual-datetime-input").addEventListener("change", updateManualDatetimeButtonLabel);

  initBearingDial();
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
  const cx = 75, cy = 85, radius = 58; // must match the SVG geometry in index.html
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
  showSubmitConfirmModal(
    formatWhaleCountsSummary(manualCounts),
    directionText,
    new Date(manualSelectedTimestampMs()).toLocaleString(),
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
    subscriber_id: getOrCreateSubscriberId()
  };
}

async function finishManualSubmit(lat, lng, isGeofenceVerified) {
  const button = document.getElementById("manual-submit-btn");
  button.disabled = true;
  setManualSubmitStatus("Submitting…");

  try {
    const record = buildManualSightingRecord(lat, lng, isGeofenceVerified);
    const result = await submitOrQueueSighting(record, null);

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
  // No nested nav-stack layer to pop here -- manual mode's manual-log-step IS the "manual-report"
  // layer's own resting content (see openManualReportFlow's own push), so returning to
  // camera-step is just a visual change, not a back-stack pop. goToCameraStep itself resets the
  // bearing dial/observer-type state (resetManualPositionControls), so this doesn't duplicate it.
  goToCameraStep();
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
