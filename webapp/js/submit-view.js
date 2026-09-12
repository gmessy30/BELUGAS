// Report Sighting tab: ports the native app's actual two-screen flow -- CaptureScreen.kt (live
// camera + reticle, no location/count UI at all) then LoggingScreen.kt (the captured photo full-
// bleed, "← DONE"/"RETAKE ↻" top row, controls stacked at the bottom) -- as two steps within this
// one tab, rather than a single scrolling form. Also ports ManualLoggingScreen.kt's "Report
// Manually" path (see openManualReportFlow below): the same review-step UI, entered directly
// from the menu with no camera/photo at all -- native's own separate, always-available manual-
// entry screen, not something reachable only when the camera happens to work.
//
// NOT ported from LoggingScreen: the compass-heading + distance-bucket picker and the AWAY/LEFT/
// RIGHT direction arrows, and the position math tied to them (PROJECTED/FALLBACK whale-position
// estimation). That whole pipeline exists to estimate the whale's position FROM the observer's
// compass bearing + a distance guess, which needs a working magnetometer -- inconsistent/gated-
// behind-extra-permission-prompts across mobile browsers (iOS Safari in particular) and a lot of
// native-only machinery to reproduce for a fallback capability. Both this app's paths use the
// GPS+draggable-pin approach instead (PIN semantics, matching ManualLoggingScreen's own position
// handling exactly, including its geofence check -- see submitSighting below) -- same eventual
// data shape, just a different, browser-feasible way of arriving at a whale_lat/whale_lng.

let cameraStream = null;
let capturedPhotoBlob = null;
let reviewPhotoObjectUrl = null;
let confirmedLat = null;
let confirmedLng = null;
let locationMarker = null;
let locationMapInstance = null;
let isManualReportMode = false;
// Null = unknown direction (HeadingDistance.kt's PodDirection.NONE equivalent) -- see the
// direction-rose markup's own comment in index.html for why this is an absolute compass-rose
// pick rather than native's relative AWAY/LEFT/RIGHT-against-a-compass-bearing scheme.
let travelBearingDegrees = null;

const counts = { whites: 0, greys: 0, calves: 0, unknown: 0 };

function initSubmitView() {
  document.getElementById("capture-btn").addEventListener("click", capturePhoto);
  document.getElementById("skip-camera-btn").addEventListener("click", () => goToReviewStep());
  document.getElementById("retake-btn").addEventListener("click", retakePhoto);
  document.getElementById("locate-btn").addEventListener("click", locateMe);
  document.getElementById("done-btn").addEventListener("click", submitSighting);

  document.getElementById("outer-geofence-reject-ok-btn").addEventListener("click", () => {
    document.getElementById("outer-geofence-reject-modal").hidden = true;
  });
  document.getElementById("geofence-warning-cancel-btn").addEventListener("click", () => {
    document.getElementById("geofence-warning-modal").hidden = true;
  });
  document.getElementById("geofence-warning-save-btn").addEventListener("click", () => {
    document.getElementById("geofence-warning-modal").hidden = true;
    finishSubmit(false); // SAVE ANYWAY -- not geofence-verified
  });

  document.querySelectorAll(".direction-chip").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".direction-chip").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      travelBearingDegrees = btn.dataset.bearing === "" ? null : Number(btn.dataset.bearing);
    });
  });

  document.querySelectorAll(".whale-count-col").forEach((col) => {
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

  startCamera();
}

// Called from the main menu's "Report Manually" item -- matches ManualLoggingScreen being its
// own separate, always-reachable screen natively (never gated behind the camera actually
// working). Skips the camera step entirely and goes straight to the review step's GPS+pin+count
// UI, auto-fetching a GPS fix immediately the same way ManualLoggingScreen's own
// LaunchedEffect(Unit) { recenterOnGps() } does on entry (the camera-path review step is
// unchanged and still requires an explicit "Get My Location" tap, per keeping that path as-is).
function openManualReportFlow() {
  switchTab("submit");
  isManualReportMode = true;
  capturedPhotoBlob = null;
  stopCamera();
  document.getElementById("camera-step").hidden = true;
  document.getElementById("review-step").hidden = false;
  updateReviewStepModeUi();
  locateMe();
}

// "← DONE"/"RETAKE ↻" (camera path) vs. ManualLoggingScreen's own "← SUBMIT" with no retake
// affordance at all (nothing to retake -- there was never a photo).
function updateReviewStepModeUi() {
  document.getElementById("review-photo").hidden = true;
  document.getElementById("review-step").classList.toggle("no-photo", true);
  document.getElementById("done-btn").textContent = isManualReportMode ? "← SUBMIT" : "← DONE";
  document.getElementById("retake-btn").hidden = isManualReportMode;
}

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
    // reach the location/count step without a photo at all, rather than a dead end.
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

  // Matches CaptureScreen's own transient "SAVING..." label swap while the platform camera API
  // writes the photo out -- this app's capture is just a canvas draw, effectively instant, but
  // the same brief state change is kept for the same interaction feel.
  setCaptureLabel("SAVING...");

  // Cap the longest edge so a 12MP phone photo doesn't become a multi-MB upload over cellular.
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

function goToReviewStep() {
  stopCamera();
  isManualReportMode = false;

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
}

// Matches both LoggingScreen's and ManualLoggingScreen's onDoneClick -- both return to
// Screen.CAPTURE, not back to the menu, ready for the next report. Also the shared reset path
// out of manual mode: whichever path got here, the next visit to this tab starts fresh on the
// camera step.
function goToCameraStep() {
  if (reviewPhotoObjectUrl) {
    URL.revokeObjectURL(reviewPhotoObjectUrl);
    reviewPhotoObjectUrl = null;
  }
  isManualReportMode = false;
  document.getElementById("review-step").hidden = true;
  document.getElementById("camera-step").hidden = false;
  startCamera();
}

// Matches LoggingScreen's onRetakeClick -- discards the captured photo entirely and returns to
// the camera step (native also deletes the saved photo file at this point; here that's just
// dropping the in-memory blob, nothing was ever written to disk).
function retakePhoto() {
  capturedPhotoBlob = null;
  goToCameraStep();
}

function locateMe() {
  if (!navigator.geolocation) {
    setSubmitStatus("Geolocation isn't available in this browser.", true);
    return;
  }
  setLocationStatus("Getting your location…");
  navigator.geolocation.getCurrentPosition(
    (position) => {
      confirmedLat = position.coords.latitude;
      confirmedLng = position.coords.longitude;
      showLocationOnMap(confirmedLat, confirmedLng);
      setLocationStatus("Location found -- drag the pin if the whale wasn't right where you're standing.");
    },
    (err) => {
      console.error("GEOLOCATION_ERROR", err);
      setLocationStatus("Couldn't get your location (" + err.message + "). Check location permissions.", true);
    },
    { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
  );
}

function showLocationOnMap(lat, lng) {
  const mapEl = document.getElementById("location-map");
  mapEl.hidden = false;

  if (!locationMapInstance) {
    locationMapInstance = L.map("location-map").setView([lat, lng], 15);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19
    }).addTo(locationMapInstance);
  } else {
    locationMapInstance.setView([lat, lng], 15);
  }
  // Leaflet can't size itself correctly if the container was hidden (display:none) up to now.
  setTimeout(() => locationMapInstance.invalidateSize(), 0);

  if (locationMarker) {
    locationMarker.setLatLng([lat, lng]);
  } else {
    locationMarker = L.marker([lat, lng], { draggable: true }).addTo(locationMapInstance);
    locationMarker.on("dragend", () => {
      const pos = locationMarker.getLatLng();
      confirmedLat = pos.lat;
      confirmedLng = pos.lng;
    });
  }
}

function setSubmitStatus(message, isError = false) {
  const el = document.getElementById("submit-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

function setLocationStatus(message, isError = false) {
  const el = document.getElementById("location-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

function totalCount() {
  return counts.whites + counts.greys + counts.calves + counts.unknown;
}

// photo_url is filled in later (by attemptUploadAndInsert in offline-queue.js) once the photo
// upload itself succeeds -- not known yet at record-build time. isGeofenceVerified matches
// SightingRecord.isGeofenceVerified: whether the geofence check below actually passed, as
// opposed to the user overriding a rejected location via SAVE ANYWAY.
function buildSightingRecord(isGeofenceVerified) {
  return {
    whale_lat: confirmedLat,
    whale_lng: confirmedLng,
    position_source: "PIN",
    count_whites: counts.whites,
    count_greys: counts.greys,
    count_calves: counts.calves,
    count_unknown: counts.unknown,
    travel_bearing_degrees: travelBearingDegrees,
    travel_bearing_source: travelBearingDegrees != null ? "MANUAL" : null,
    observed_at_epoch_ms: Date.now(),
    observer_type: "SELF",
    is_geofence_verified: isGeofenceVerified,
    photo_url: null,
    subscriber_id: getOrCreateSubscriberId()
  };
}

// Bound to the review step's "← DONE"/"← SUBMIT" button -- matches LoggingScreen's/
// ManualLoggingScreen's own onClick exactly: that button IS the save/submit trigger natively,
// not a separate button further down the screen. Runs the same geofence check
// ManualLoggingScreen uses (both this app's paths are PIN semantics): a coarse outer-bound hard
// reject with no override, then a real coastline/river-data buffer check that can be overridden
// via SAVE ANYWAY, falling back to the online coastline-channel RPC when the offline check has
// no data either way for this point.
async function submitSighting() {
  if (totalCount() === 0) {
    setSubmitStatus("Enter at least one whale count before submitting.", true);
    return;
  }
  if (confirmedLat == null || confirmedLng == null) {
    setSubmitStatus("Get your location before submitting.", true);
    return;
  }

  if (!isWithinOuterGeofence(confirmedLat, confirmedLng)) {
    // No SAVE ANYWAY here, deliberately -- matches native exactly: this location isn't remotely
    // Cook Inlet, not a borderline call.
    document.getElementById("outer-geofence-reject-modal").hidden = false;
    return;
  }

  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Checking location…");

  const verified = isWhalePositionVerified(confirmedLat, confirmedLng, MANUAL_PIN_GEOFENCE_CHECK_RADIUS_METERS);
  if (verified === true) {
    await finishSubmit(true);
  } else if (verified === false) {
    button.disabled = false;
    setSubmitStatus("");
    showGeofenceWarning();
  } else {
    // Only reachable once the buffer check found no well-sourced data near this point at all --
    // the online fallback never runs, and this status never shows, on a normal (resolved) submit.
    setSubmitStatus("Checking water data…");
    const validByChannel = await isWithinCoastlineChannelFallback(confirmedLat, confirmedLng);
    if (validByChannel) {
      await finishSubmit(true);
    } else {
      button.disabled = false;
      setSubmitStatus("");
      showGeofenceWarning();
    }
  }
}

function showGeofenceWarning() {
  document.getElementById("geofence-warning-text").textContent =
    `The whale position (${confirmedLat.toFixed(4)}, ${confirmedLng.toFixed(4)}) falls outside ` +
    "the primary observation area for Cook Inlet. Do you still want to log this sighting?";
  document.getElementById("geofence-warning-modal").hidden = false;
}

async function finishSubmit(isGeofenceVerified) {
  const button = document.getElementById("done-btn");
  button.disabled = true;
  setSubmitStatus("Submitting…");

  try {
    const record = buildSightingRecord(isGeofenceVerified);
    const result = await submitOrQueueSighting(record, capturedPhotoBlob);

    if (result.ok) {
      setSubmitStatus("Sighting submitted! Thank you.");
      resetSubmitForm();
      await refreshSightings();
    } else if (result.queued) {
      setSubmitStatus("You're offline -- this sighting is saved on your device and will upload automatically once you're back online.");
      resetSubmitForm();
    } else {
      setSubmitStatus("Couldn't save the sighting -- check your connection and try again.", true);
    }
  } finally {
    button.disabled = false;
  }
}

// Matches LoggingScreen's onDoneClick -- returns to the camera step (native: Screen.CAPTURE),
// ready for the next report.
function resetSubmitForm() {
  capturedPhotoBlob = null;
  confirmedLat = null;
  confirmedLng = null;
  Object.keys(counts).forEach((key) => (counts[key] = 0));
  document.querySelectorAll(".whale-count-value").forEach((el) => (el.textContent = "0"));
  travelBearingDegrees = null;
  document.querySelectorAll(".direction-chip").forEach((b) => {
    b.classList.toggle("active", b.classList.contains("direction-chip-unknown"));
  });
  document.getElementById("location-status").textContent = "";
  document.getElementById("location-map").hidden = true;
  goToCameraStep();
}
