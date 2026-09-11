// Submit tab: camera capture (getUserMedia) + GPS (Geolocation API) + photo upload + sightings
// insert, matching the native app's PIN-style manual logging flow (ManualLoggingScreen.kt):
// the confirmed point IS the whale position, no heading/distance projection, so
// uncertainty_radius_meters/uncertainty_bucket are left null, same as every PIN-sourced record
// there. position_source is 'PIN' for the same reason -- there's no projection math involved,
// which is the actual distinction PIN/PROJECTED/FALLBACK encodes (see PositionSource in
// shared/src/commonMain/kotlin/com/cookinlet/belugas/SightingRecord.kt).

let cameraStream = null;
let capturedPhotoBlob = null;
let confirmedLat = null;
let confirmedLng = null;
let locationMarker = null;
let locationMapInstance = null;

const counts = { whites: 0, greys: 0, calves: 0, unknown: 0 };

function initSubmitView() {
  document.getElementById("capture-btn").addEventListener("click", capturePhoto);
  document.getElementById("retake-btn").addEventListener("click", retakePhoto);
  document.getElementById("locate-btn").addEventListener("click", locateMe);
  document.getElementById("submit-sighting-btn").addEventListener("click", submitSighting);

  document.querySelectorAll(".count-stepper").forEach((stepper) => {
    const key = stepper.dataset.count;
    const valueEl = stepper.querySelector(".count-value");
    stepper.querySelector(".count-minus").addEventListener("click", () => {
      counts[key] = Math.max(0, counts[key] - 1);
      valueEl.textContent = counts[key];
    });
    stepper.querySelector(".count-plus").addEventListener("click", () => {
      counts[key] = counts[key] + 1;
      valueEl.textContent = counts[key];
    });
  });

  startCamera();
}

async function startCamera() {
  const video = document.getElementById("camera-preview");
  setSubmitStatus("");
  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false
    });
    video.srcObject = cameraStream;
    video.hidden = false;
    document.getElementById("captured-photo").hidden = true;
    document.getElementById("capture-btn").hidden = false;
    document.getElementById("retake-btn").hidden = true;
  } catch (e) {
    console.error("CAMERA_ERROR", e);
    setSubmitStatus(
      "Camera unavailable (" + e.name + "). You can still submit without a photo if your browser allows it, " +
      "or check camera permissions in Settings.",
      true
    );
  }
}

function stopCamera() {
  if (cameraStream) {
    cameraStream.getTracks().forEach((track) => track.stop());
    cameraStream = null;
  }
}

function capturePhoto() {
  const video = document.getElementById("camera-preview");
  const canvas = document.getElementById("capture-canvas");
  if (!video.videoWidth) return;

  // Cap the longest edge so a 12MP phone photo doesn't become a multi-MB upload over cellular.
  const MAX_EDGE = 1600;
  const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
  canvas.width = video.videoWidth * scale;
  canvas.height = video.videoHeight * scale;
  canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);

  canvas.toBlob(
    (blob) => {
      capturedPhotoBlob = blob;
      const img = document.getElementById("captured-photo");
      img.src = URL.createObjectURL(blob);
      img.hidden = false;
      video.hidden = true;
      document.getElementById("capture-btn").hidden = true;
      document.getElementById("retake-btn").hidden = false;
      stopCamera();
    },
    "image/jpeg",
    0.85
  );
}

function retakePhoto() {
  capturedPhotoBlob = null;
  startCamera();
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
// upload itself succeeds -- not known yet at record-build time.
function buildSightingRecord() {
  return {
    whale_lat: confirmedLat,
    whale_lng: confirmedLng,
    position_source: "PIN",
    count_whites: counts.whites,
    count_greys: counts.greys,
    count_calves: counts.calves,
    count_unknown: counts.unknown,
    observed_at_epoch_ms: Date.now(),
    observer_type: "SELF",
    photo_url: null,
    subscriber_id: getOrCreateSubscriberId()
  };
}

async function submitSighting() {
  if (totalCount() === 0) {
    setSubmitStatus("Enter at least one whale count before submitting.", true);
    return;
  }
  if (confirmedLat == null || confirmedLng == null) {
    setSubmitStatus("Get your location before submitting.", true);
    return;
  }

  const button = document.getElementById("submit-sighting-btn");
  button.disabled = true;
  setSubmitStatus("Submitting…");

  try {
    const record = buildSightingRecord();
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

function resetSubmitForm() {
  capturedPhotoBlob = null;
  confirmedLat = null;
  confirmedLng = null;
  Object.keys(counts).forEach((key) => (counts[key] = 0));
  document.querySelectorAll(".count-value").forEach((el) => (el.textContent = "0"));
  document.getElementById("location-status").textContent = "";
  document.getElementById("location-map").hidden = true;
  startCamera();
}
