// Web Push via Firebase Cloud Messaging -- wires this PWA into the existing FCM pipeline (see
// db.js's registerDeviceToken, supabase/functions/notify-new-sighting, and SupabaseClient.kt's
// own registerDeviceToken/BelugasMessagingService for the native side this mirrors).
//
// Uses the Firebase COMPAT build (global `firebase` namespace, plain <script> tags) rather than
// the modular ES-module build -- this app's whole architecture is deliberately framework-free,
// classic <script> tags sharing one global scope (see app.js's own header comment); the compat
// build fits that directly, and the service-worker side (sw.js) needs importScripts() either way
// (SW contexts can't use <script type=module>), so both sides end up using the same API shape.
//
// GATING (deliberate deviation from native): MainActivity.kt requests Notification permission
// AUTOMATICALLY on every launch and registers unconditionally. This app never does that for a
// FIRST-time request -- only an explicit "Enable Alerts" tap in the Alerts screen calls
// Notification.requestPermission(), matching browsers' own push-permission best practices (an
// unsolicited permission prompt on page load is exactly what Chrome/Safari penalize and can
// auto-deny after enough sites do it). Once permission is already granted from a prior session,
// though, silently re-fetching the (possibly-rotated) token on every load IS done automatically
// below -- that's not a new prompt, just the same "re-register on every launch" idempotency
// MainActivity.kt's own unconditional FirebaseMessaging.getInstance().token call has, and doubles
// as this app's token-refresh handling (the modern modular/compat SDK has no separate
// onTokenRefresh event -- calling getToken() again is itself always "give me the current valid
// token", refreshed or not).
let messagingInstance = null;
let currentFcmToken = null;
let pushForegroundBannerTimeoutId = null;

function isFirebaseConfigured() {
  return typeof FIREBASE_CONFIG !== "undefined" && !FIREBASE_CONFIG.apiKey.startsWith("REPLACE_");
}

function isPushSupported() {
  return "Notification" in window && "serviceWorker" in navigator && typeof firebase !== "undefined";
}

// iOS Safari only delivers Web Push to a PWA actually installed to the Home Screen -- a plain
// Safari tab's Notification.requestPermission() there either silently no-ops or the resulting
// token never receives anything, so this app doesn't even attempt it outside standalone mode.
function isPushAvailableOnThisPlatform() {
  return !isIosDevice() || isStandaloneDisplayMode();
}

function initPushNotifications() {
  if (!isPushSupported() || !isFirebaseConfigured()) return;

  firebase.initializeApp(FIREBASE_CONFIG);
  messagingInstance = firebase.messaging();

  // Foreground messages: BelugasMessagingService.onMessageReceived (native) only logs -- Android
  // itself auto-displays a system-tray notification for a BACKGROUNDED app, but the one moment
  // that's guaranteed NOT to auto-display anything (the app already open and focused) is exactly
  // when native shows the user nothing at all. Deliberately not copied -- a foreground message
  // here shows an in-app banner instead, so "the app happens to be open" never means "silently
  // missed it".
  messagingInstance.onMessage((payload) => {
    showPushForegroundBanner(
      payload.notification?.title ?? "BELUGAS",
      payload.notification?.body ?? "New sighting reported."
    );
  });

  if (Notification.permission === "granted" && isPushAvailableOnThisPlatform()) {
    silentlyRefreshToken();
  }
}

function initPushForegroundBanner() {
  document.getElementById("push-foreground-banner-dismiss-btn").addEventListener("click", () => {
    document.getElementById("push-foreground-banner").hidden = true;
    clearTimeout(pushForegroundBannerTimeoutId);
  });
}

function showPushForegroundBanner(title, body) {
  document.getElementById("push-foreground-banner-title").textContent = title;
  document.getElementById("push-foreground-banner-body").textContent = body;
  const banner = document.getElementById("push-foreground-banner");
  banner.hidden = false;
  clearTimeout(pushForegroundBannerTimeoutId);
  pushForegroundBannerTimeoutId = setTimeout(() => { banner.hidden = true; }, 6000);
}

// Shared by both the silent on-load refresh and the explicit "Enable Alerts" tap -- the only
// difference between them is which one calls Notification.requestPermission() first (this
// function assumes permission is already granted by the time it's called).
async function fetchAndRegisterToken() {
  const registration = await navigator.serviceWorker.ready;
  const token = await messagingInstance.getToken({
    vapidKey: FCM_VAPID_PUBLIC_KEY,
    serviceWorkerRegistration: registration
  });
  if (!token) return false;
  currentFcmToken = token;
  return registerDeviceToken(token, getOrCreateSubscriberId());
}

async function silentlyRefreshToken() {
  try {
    await fetchAndRegisterToken();
  } catch (e) {
    console.warn("PUSH_TOKEN_REFRESH_ERROR", e);
  }
  updatePushUi();
}

// Bound to the Alerts screen's "🔔 ENABLE ALERTS" button -- the one explicit, user-initiated
// entry point that's allowed to call Notification.requestPermission() (see this file's header
// comment on why that's never automatic).
async function enablePushNotifications() {
  const statusEl = document.getElementById("push-status");
  const enableBtn = document.getElementById("push-enable-btn");
  enableBtn.disabled = true;
  statusEl.textContent = "Requesting permission…";
  statusEl.className = "status-info";

  try {
    const permission = await Notification.requestPermission();
    if (permission !== "granted") {
      statusEl.textContent = "Permission not granted -- alerts stay off.";
      statusEl.className = "status-error";
      return;
    }
    const ok = await fetchAndRegisterToken();
    statusEl.textContent = ok
      ? "Alerts enabled!"
      : "Enabled, but couldn't reach the server -- try again shortly.";
    statusEl.className = ok ? "status-info" : "status-error";
  } catch (e) {
    console.error("PUSH_ENABLE_ERROR", e);
    statusEl.textContent = "Couldn't enable alerts -- check your connection and try again.";
    statusEl.className = "status-error";
  } finally {
    enableBtn.disabled = false;
    updatePushUi();
  }
}

// Refreshes the Alerts screen's push-notification section to match current permission/token
// state -- called on init and every time the Alerts page is opened (see subscriptions.js's
// openAlertsPage), since permission can change from outside this app (browser/site settings)
// between visits.
function updatePushUi() {
  const descEl = document.getElementById("push-enable-description");
  const enableBtn = document.getElementById("push-enable-btn");
  const installFirstBtn = document.getElementById("push-ios-install-first-btn");
  const statusEl = document.getElementById("push-status");

  enableBtn.hidden = true;
  installFirstBtn.hidden = true;

  if (!isPushSupported() || !isFirebaseConfigured()) {
    descEl.textContent = "Push notifications aren't available in this browser.";
    return;
  }

  if (!isPushAvailableOnThisPlatform()) {
    descEl.textContent = "On iPhone/iPad, sighting alerts require installing BELUGAS to your Home Screen first.";
    installFirstBtn.hidden = false;
    return;
  }

  if (Notification.permission === "denied") {
    descEl.textContent = "Notifications are blocked for this site in your browser settings -- allow them there to get sighting alerts.";
    return;
  }

  if (Notification.permission === "granted" && currentFcmToken) {
    descEl.textContent = "Alerts are enabled on this device.";
    statusEl.textContent = "";
    return;
  }

  descEl.textContent = "Get notified when a beluga sighting matches your alerts below.";
  enableBtn.hidden = false;
}

function initPushEnableButtons() {
  document.getElementById("push-enable-btn").addEventListener("click", enablePushNotifications);
  document.getElementById("push-ios-install-first-btn").addEventListener("click", () => {
    document.getElementById("ios-install-modal").hidden = false;
    pushNavLayer("ios-install-modal", () => {
      document.getElementById("ios-install-modal").hidden = true;
    });
  });
}
