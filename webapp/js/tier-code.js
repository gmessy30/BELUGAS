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

// Wraps getCurrentPosition in a promise that never rejects -- matches locationService.
// getCurrentLocation()'s own null-on-failure contract, so callers can use the same
// `result.coords != null` check native does. Item 47: also carries accuracy/the raw error
// message through (previously discarded entirely), read by checkDepartureEligibility's debug
// overlay so a failed/unavailable fix is visible instead of just silently resolving to "no."
function getCurrentPositionOnce(options) {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve({ coords: null, accuracy: null, error: "geolocation not supported" });
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        coords: { lat: position.coords.latitude, lng: position.coords.longitude },
        accuracy: position.coords.accuracy,
        error: null
      }),
      (err) => resolve({ coords: null, accuracy: null, error: err.message || String(err) }),
      options
    );
  });
}

// Item 47: on-screen diagnostics for why the "WHALES LEFT?" section isn't showing -- same
// ?debug=1 pattern as item 39's BearingDial overlay, for testing on a phone with no devtools
// console. Shows the raw result of each of the three gates (including any RPC/geolocation error)
// instead of the collapsed true/false the actual visibility logic uses.
function renderDepartureDebugOverlay(state) {
  if (!DEBUG_MODE_ENABLED) return;
  const el = document.getElementById("tier-code-departure-debug-overlay");
  if (!el) return;
  el.hidden = false;
  const fmt = (v) => (v == null ? "—" : String(v));
  el.textContent = [
    `subscriberId: ${fmt(state.subscriberId)}`,
    `isKenaiDepartureReporter(): ${fmt(state.isReporter)}` +
      (state.tierCheckError ? ` (RPC ERROR: ${state.tierCheckError})` : ""),
    state.isReporter
      ? `GPS fix: ${state.coords ? `${state.coords.lat.toFixed(6)}, ${state.coords.lng.toFixed(6)} (±${fmt(state.accuracy)}m)` : "none"}` +
        (state.gpsError ? ` (ERROR: ${state.gpsError})` : "")
      : "GPS fix: (skipped -- not a tier-1 reporter)",
    `pointInPolygon(KENAI_DEPARTURE_VIEWING_AREA): ${fmt(state.isInsideViewingArea)}`,
    `section shown: ${!document.getElementById("tier-code-departure-section").hidden}`
  ].join("\n");
}

async function checkDepartureEligibility(subscriberId) {
  const section = document.getElementById("tier-code-departure-section");
  const checking = document.getElementById("tier-code-departure-checking");
  section.hidden = true;
  checking.hidden = true;
  document.getElementById("tier-code-departure-status").textContent = "";
  document.getElementById("tier-code-departure-debug-overlay").hidden = true;

  const isReporter = await isKenaiDepartureReporter(subscriberId);
  const tierCheckError = lastDepartureTierCheckError;
  if (!isReporter) {
    renderDepartureDebugOverlay({ subscriberId, isReporter, tierCheckError, coords: null, accuracy: null, gpsError: null, isInsideViewingArea: null });
    return;
  }

  checking.hidden = false;
  const gpsResult = await getCurrentPositionOnce({ enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 });
  checking.hidden = true;

  const isInsideViewingArea = gpsResult.coords != null &&
    pointInPolygon(gpsResult.coords.lat, gpsResult.coords.lng, KENAI_DEPARTURE_VIEWING_AREA);
  section.hidden = !isInsideViewingArea;

  renderDepartureDebugOverlay({
    subscriberId,
    isReporter,
    tierCheckError,
    coords: gpsResult.coords,
    accuracy: gpsResult.accuracy,
    gpsError: gpsResult.error,
    isInsideViewingArea
  });
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
  const gpsResult = await getCurrentPositionOnce({ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 });
  const succeeded = gpsResult.coords != null &&
    await reportKenaiDeparture(subscriberId, gpsResult.coords.lat, gpsResult.coords.lng);

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
