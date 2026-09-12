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

const cameraCounts = { whites: 0, greys: 0, calves: 0, unknown: 0 };
const manualCounts = { whites: 0, greys: 0, calves: 0, unknown: 0 };

// --- Camera-path position state (LoggingScreen.kt) ---
let selectedPodDirection = "NONE"; // AWAY/LEFT/RIGHT/NONE -- relative to the observer
let headingDegrees = null; // null = not set yet
let headingIsAerial = false; // cached once per review-step visit, see goToReviewStep
let selectedDistanceBucketKey = "MEDIUM";
let pendingHeadingDegreesDraft = 0; // the heading-distance modal's own working value pre-confirm

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

function initSubmitView() {
  document.getElementById("capture-btn").addEventListener("click", capturePhoto);
  document.getElementById("skip-camera-btn").addEventListener("click", () => goToReviewStep());
  document.getElementById("retake-btn").addEventListener("click", retakePhoto);
  document.getElementById("done-btn").addEventListener("click", submitCameraSighting);
  document.getElementById("manual-submit-btn").addEventListener("click", submitManualSighting);

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

  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false
    });
    video.srcObject = cameraStream;
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
  canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);

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
  startCamera();
}

function retakePhoto() {
  capturedPhotoBlob = null;
  navigateBack(); // pops the review-step layer; its onPop IS goToCameraStep
}

function resetCameraPositionControls() {
  selectedPodDirection = "NONE";
  document.querySelectorAll(".direction-arrow-btn").forEach((b) => b.classList.remove("selected"));
  headingDegrees = null;
  selectedDistanceBucketKey = "MEDIUM";
  updateHeadingDistanceButtonLabel();
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
  pendingHeadingDegreesDraft = headingDegrees ?? 0;
  document.getElementById("heading-slider").value = String(pendingHeadingDegreesDraft);
  document.getElementById("heading-slider-value").textContent = `${pendingHeadingDegreesDraft}°`;
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
    chip.className = "chip-toggle" + (selectedDistanceBucketKey === bucket.key ? " active" : "");
    chip.textContent = distanceBucketShortLabel(bucket.key, headingIsAerial);
    chip.addEventListener("click", () => {
      selectedDistanceBucketKey = bucket.key;
      renderHeadingDistanceChips();
    });
    container.appendChild(chip);
  });
}

function updateHeadingDistanceButtonLabel() {
  const btn = document.getElementById("heading-distance-btn");
  if (headingDegrees != null) {
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
  if (headingDegrees == null) {
    // No CoastlineGeometry fallback-guess port (see this file's header comment) -- always
    // directed here when no heading was set, not only once a fallback guess also fails as it is
    // natively.
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

  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Getting your location…");

  navigator.geolocation.getCurrentPosition(
    (position) => {
      const radiusMeters = distanceBucketRadiusMeters(selectedDistanceBucketKey, headingIsAerial);
      const [whaleLat, whaleLng] = destinationPoint(
        position.coords.latitude, position.coords.longitude, headingDegrees, radiusMeters
      );
      continueCameraSubmit(whaleLat, whaleLng, radiusMeters);
    },
    (err) => {
      console.error("GEOLOCATION_ERROR", err);
      setSubmitStatus("Couldn't get your location (" + err.message + "). Check location permissions.", true);
      button.disabled = false;
    },
    { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
  );
}

async function continueCameraSubmit(whaleLat, whaleLng, radiusMeters) {
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
  const finish = (verified) => finishCameraSubmit(whaleLat, whaleLng, radiusMeters, verified);
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

function buildCameraSightingRecord(whaleLat, whaleLng, radiusMeters, isGeofenceVerified) {
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
    observed_at_epoch_ms: Date.now(),
    observer_type: "SELF", // LoggingScreen has no SELF/OTHER toggle at all -- that's manual-only
    is_geofence_verified: isGeofenceVerified,
    photo_url: null,
    subscriber_id: getOrCreateSubscriberId()
  };
}

async function finishCameraSubmit(whaleLat, whaleLng, radiusMeters, isGeofenceVerified) {
  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Submitting…");

  try {
    const record = buildCameraSightingRecord(whaleLat, whaleLng, radiusMeters, isGeofenceVerified);
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
  // this screen opens, not just at submit time. The map starts at DEFAULT_MAP_CENTER instead: the
  // existing "Get My Location" button (a web-only affordance native has no equivalent for, since
  // panning a world map to find Cook Inlet by hand is a real usability problem on a phone screen)
  // is still there, user-initiated only, never automatic.
  initManualMapIfNeeded();
  setManualDatetimeInputToNow();
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
}

function setManualLocationStatus(message, isError = false) {
  const el = document.getElementById("manual-location-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

function initManualPositionControls() {
  // Web-only affordance (native has no manual GPS button at all here) -- user-initiated only,
  // never automatic, see openManualReportFlow's own comment.
  document.getElementById("manual-locate-btn").addEventListener("click", () => {
    if (!navigator.geolocation) {
      setManualLocationStatus("Geolocation isn't available in this browser.", true);
      return;
    }
    setManualLocationStatus("Getting your location…");
    navigator.geolocation.getCurrentPosition(
      (position) => {
        manualMapInstance.setView([position.coords.latitude, position.coords.longitude], 15);
        setManualLocationStatus("");
      },
      (err) => {
        console.error("GEOLOCATION_ERROR", err);
        setManualLocationStatus("Couldn't get your location (" + err.message + ").", true);
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
    );
  });

  // View reset only -- no GPS refetch, matching ManualLoggingScreen's own RECENTER exactly (this
  // screen marks where the whales were, not the observer's own position).
  document.getElementById("manual-recenter-btn").addEventListener("click", () => {
    manualMapInstance.setView(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM);
  });

  document.querySelectorAll("#manual-log-step .direction-chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("#manual-log-step .direction-chip").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      manualTravelBearingDegrees = btn.dataset.bearing === "" ? null : Number(btn.dataset.bearing);
    });
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
  manualTravelBearingDegrees = null;
  document.querySelectorAll("#manual-log-step .direction-chip").forEach((b) => {
    b.classList.toggle("active", b.classList.contains("direction-chip-unknown"));
  });
  setManualObserverType("SELF");
  setManualLocationStatus("");
  setManualDatetimeInputToNow();
  // No nested nav-stack layer to pop here -- manual mode's manual-log-step IS the "manual-report"
  // layer's own resting content (see openManualReportFlow's own push), so returning to
  // camera-step is just a visual change, not a back-stack pop.
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
