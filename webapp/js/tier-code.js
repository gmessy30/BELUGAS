// Observer-code redemption modal. Reached the same way it is natively: only via the hidden
// 7-tap-the-whale's-nose gesture on the About page (see about.js's onWhaleNoseTap), not any
// visible menu item.
//
// Item 62: this gesture's own repeated rapid tapping is exactly what makes the modal risky to
// open under the finger unguarded -- screen sensitivity means the 7-tap count sometimes runs to
// 8 or 9 "boops" before the user notices the modal appeared, and every one of those trailing taps
// lands at roughly the same screen position the LAST nose-tap did. Two independent guards below
// (62a positions the input, not a button, under that position; 62b ignores button taps for a
// short window after open) rather than relying on either alone.
function initTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const closeBtn = document.getElementById("tier-code-close-btn");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const input = document.getElementById("tier-code-input");

  closeBtn.addEventListener("click", () => {
    if (isTierCodeModalButtonIgnored()) return;
    navigateBack();
  });

  // Tapping the dimmed backdrop (not the dialog card itself) closes it, same convention as
  // the location/camera steps' plain-button dismissal elsewhere in this app. Not guarded by the
  // ignore-window: the card itself covers the nose-tap position (see positionTierCodeModalUnderTap),
  // so a trailing boop lands ON the card, never on the backdrop behind it.
  modal.addEventListener("click", (event) => {
    if (event.target === modal) navigateBack();
  });

  submitBtn.addEventListener("click", () => {
    if (isTierCodeModalButtonIgnored()) return;
    submitTierCode();
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitTierCode();
  });

  document.getElementById("tier-code-departure-btn").addEventListener("click", () => {
    if (isTierCodeModalButtonIgnored()) return;
    submitDepartureReport();
  });
}

// Item 62b: a button tap within this window of the modal opening is silently ignored -- a
// trailing "boop" from the 7-tap gesture landing on SUBMIT/CANCEL/REPORT DEPARTURE the very
// instant the modal appears (before the user has even seen it, let alone decided to tap
// something) is exactly the accidental-action risk this closes off. 600ms is comfortably longer
// than the gap between rapid taps but short enough that it's never noticeable as a real delay to
// someone deliberately tapping a button afterwards.
const TIER_CODE_MODAL_BUTTON_IGNORE_MS = 600;
let tierCodeModalOpenedAt = 0;

function isTierCodeModalButtonIgnored() {
  return Date.now() - tierCodeModalOpenedAt < TIER_CODE_MODAL_BUTTON_IGNORE_MS;
}

// Item 62a: shifts the modal card so the CODE INPUT (harmless to tap into or accidentally type
// a stray character in -- nothing submits on its own) lands at the tapping finger's own last
// screen position, rather than the modal's generic flex-centered spot -- every button (Cancel/
// Submit, and Report Departure further down) ends up clearly below that position instead.
// tapScreenY is the 7th tap's own event.clientY (about.js's onWhaleNoseTap) -- literally where
// the finger just was, not a theoretical zone-center guess.
function positionTierCodeModalUnderTap(tapScreenY) {
  const card = document.querySelector("#tier-code-modal .modal-card");
  const input = document.getElementById("tier-code-input");
  card.style.transform = ""; // clear any previous shift before measuring fresh
  if (tapScreenY == null) return;

  requestAnimationFrame(() => {
    const inputRect = input.getBoundingClientRect();
    const cardRect = card.getBoundingClientRect();
    const inputCenterY = inputRect.top + inputRect.height / 2;
    let deltaY = tapScreenY - inputCenterY;

    // Clamped so the card's own top/bottom both stay safely on-screen -- a tap near the very
    // top or bottom edge shouldn't push the buttons/departure section off the viewport entirely
    // just to keep the input exactly centered under it.
    const margin = 16;
    const minDeltaY = margin - cardRect.top;
    const maxDeltaY = window.innerHeight - margin - cardRect.bottom;
    deltaY = Math.min(maxDeltaY, Math.max(minDeltaY, deltaY));

    card.style.transform = `translateY(${deltaY}px)`;
  });
}

// Called from about.js's onWhaleNoseTap once the hidden gesture completes. tapScreenY is that
// 7th tap's own event.clientY -- see positionTierCodeModalUnderTap's own comment.
function openTierCodeModal(tapScreenY) {
  const modal = document.getElementById("tier-code-modal");
  const input = document.getElementById("tier-code-input");
  modal.hidden = false;
  tierCodeModalOpenedAt = Date.now();
  setTierCodeStatus("");
  input.value = "";
  input.focus();
  positionTierCodeModalUnderTap(tapScreenY);

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

// Item 62c: a two-step action now, not a single tap -- REPORT DEPARTURE is a real, hard-to-undo
// consequence (steps RED to YELLOW, can't be reversed for 3 hours), and sits in the same modal
// the 7-tap nose gesture opens, where a stray trailing tap landing on it was a real risk even
// with 62a/62b's own guards. The native browser confirm() dialog matches this app's own existing
// precedent for a consequential, hard-to-undo action (admin.js's handleRevoke) -- and as a real
// separate OS-level dialog, it can't itself be dismissed/confirmed by an accidental touch the way
// a same-page button could.
async function submitDepartureReport() {
  if (!confirm(
    "Report that the whales have left? This steps RED to YELLOW and can't be undone for 3 hours."
  )) {
    return;
  }

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
