// Observer-code redemption modal. Reached the same way it is natively: only via the hidden
// 7-tap-the-whale's-nose gesture on the About page (see about.js's onWhaleNoseTap), not any
// visible menu item.
function initTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const closeBtn = document.getElementById("tier-code-close-btn");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const input = document.getElementById("tier-code-input");

  closeBtn.addEventListener("click", () => {
    navigateBack();
  });

  // Tapping the dimmed backdrop (not the dialog card itself) closes it, same convention as
  // the location/camera steps' plain-button dismissal elsewhere in this app.
  modal.addEventListener("click", (event) => {
    if (event.target === modal) navigateBack();
  });

  submitBtn.addEventListener("click", submitTierCode);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitTierCode();
  });

  document.getElementById("tier-code-departure-btn").addEventListener("click", submitDepartureReport);
}

// Called from about.js's onWhaleNoseTap once the hidden gesture completes.
function openTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const input = document.getElementById("tier-code-input");
  modal.hidden = false;
  setTierCodeStatus("");
  input.value = "";
  input.focus();

  // Item 40: fire-and-forget, matching native's own LaunchedEffect(Unit) -- doesn't block the
  // modal from opening while the tier check (and, if eligible, a GPS fix) resolve.
  checkDepartureEligibility(getOrCreateSubscriberId());
}

// --- Item 40: Kenai departure de-escalation (TierClaimScreen.kt's "WHALES LEFT?" section) ---
// Visibility-only gating (both checks re-verified server-side by report_kenai_departure
// regardless): a claimed tier-1 device, physically standing inside the viewing polygon right now.

// Same polygon as kenai_departure_viewing_areas' 'kenai_mouth' row (supabase/migrations/
// 20260914000000_swap_kenai_departure_polygon_and_expose_tier_check.sql) -- (lat, lng) pairs,
// matching pointInPolygon's own parameter order (geofence.js). Ported verbatim from
// TierClaimScreen.kt's own KENAI_DEPARTURE_VIEWING_AREA -- keep both in sync by hand if this
// boundary is ever updated again (native's own KenaiDepartureViewingAreaTest is the drift guard
// on that side; there's no equivalent test harness in this web app).
const KENAI_DEPARTURE_VIEWING_AREA = [
  [60.55840946945002, -151.2929030905107],
  [60.548809724266, -151.2711621015774],
  [60.54447894339786, -151.2663236677443],
  [60.523702563429, -151.2807913408272],
  [60.52157475630008, -151.2728886260558],
  [60.53299593807134, -151.2684593705852],
  [60.54586176396081, -151.2595707653854],
  [60.5458859329573, -151.2559459380841],
  [60.5497924659469, -151.2612319296148],
  [60.55272009801407, -151.2476677121801],
  [60.55204282721643, -151.237950970811],
  [60.55349234777209, -151.2403839329403],
  [60.55399897149227, -151.2486397201363],
  [60.55147439417682, -151.2647257396582],
  [60.55301499382099, -151.2694197660138],
  [60.55336655022332, -151.274443365124],
  [60.55892294657778, -151.289777984689]
];

// Wraps getCurrentPosition in a promise that resolves null (never rejects) on any failure/
// unavailability -- matches locationService.getCurrentLocation()'s own null-on-failure contract
// exactly, so callers can use the same `coords != null` check native does.
function getCurrentPositionOnce(options) {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({ lat: position.coords.latitude, lng: position.coords.longitude }),
      () => resolve(null),
      options
    );
  });
}

async function checkDepartureEligibility(subscriberId) {
  const section = document.getElementById("tier-code-departure-section");
  const checking = document.getElementById("tier-code-departure-checking");
  section.hidden = true;
  checking.hidden = true;
  document.getElementById("tier-code-departure-status").textContent = "";

  const isReporter = await isKenaiDepartureReporter(subscriberId);
  if (!isReporter) return;

  checking.hidden = false;
  const coords = await getCurrentPositionOnce({ enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 });
  checking.hidden = true;

  const isInsideViewingArea = coords != null &&
    pointInPolygon(coords.lat, coords.lng, KENAI_DEPARTURE_VIEWING_AREA);
  section.hidden = !isInsideViewingArea;
}

function setDepartureStatus(message, isError = false) {
  const el = document.getElementById("tier-code-departure-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

async function submitDepartureReport() {
  const btn = document.getElementById("tier-code-departure-btn");
  btn.disabled = true;
  setDepartureStatus("Reporting…");

  const subscriberId = getOrCreateSubscriberId();
  // Fresh, uncached fix -- this is asserting "I am here right now," not a cached location from
  // whenever the modal happened to open.
  const coords = await getCurrentPositionOnce({ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 });
  const succeeded = coords != null && await reportKenaiDeparture(subscriberId, coords.lat, coords.lng);

  if (succeeded) {
    setDepartureStatus("Reported. The alert will step down.");
  } else {
    setDepartureStatus("Couldn't report right now -- check that you're still in position and try again.", true);
  }
  btn.disabled = false;
}

async function submitTierCode() {
  const input = document.getElementById("tier-code-input");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const code = input.value.trim();

  if (!code) {
    setTierCodeStatus("Enter a code first.", true);
    return;
  }

  submitBtn.disabled = true;
  setTierCodeStatus("Checking…");

  const subscriberId = getOrCreateSubscriberId();
  const result = await redeemTierCode(code, subscriberId);

  if (result.status === "SUCCESS") {
    setTierCodeStatus(`Code accepted -- this device is now a Tier ${result.tier} observer.`);
    input.value = "";
  } else if (result.status === "RATE_LIMITED") {
    setTierCodeStatus("Too many attempts on this device recently -- try again later.", true);
  } else {
    setTierCodeStatus("That code isn't valid.", true);
  }

  submitBtn.disabled = false;
}

function setTierCodeStatus(message, isError = false) {
  const el = document.getElementById("tier-code-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}
