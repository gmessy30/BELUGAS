// Item 87: in-app alert (sound + vibration + a local notification, if already permitted) when any
// zone's presence-banner status transitions UP into RED, or steps back DOWN from RED to YELLOW
// (quieter -- a step-down, not a new emergency) -- while this tab is open, foregrounded OR
// backgrounded (a background tab still runs JS/timers, see presence-state.js's own
// setTimeout-driven polling, so this fires there too, not just while actively looking at the
// screen). Deliberately separate from push-notifications.js's own FCM pipeline: that's
// server-triggered on a NEW SIGHTING row; this is purely client-side, driven by the exact same
// polled/decayed status presence-banner.js already computes for its own cards -- a zone's status
// can escalate/decay from pure time passing (staleness, a departure report's own ratchet, etc.),
// with no new row involved at all. No native equivalent exists to port or diverge from -- this is
// a web-only addition (no push-permission prompt of its own; see "if Notification permission is
// already granted" below).
//
// SOUND (item 88): real Cook Inlet beluga vocalizations, not a synthesized tone -- a ~1.5s clip
// (webapp/audio/alert-red.mp3/.ogg) for a RED transition, a shorter/softer ~1s clip
// (alert-yellow.mp3/.ogg) for RED -> YELLOW. Source: NOAA Fisheries' public-domain "Beluga Whale
// Vocalizations" video (see CLAUDE.md's own note on the source URL/license and the exact
// trim/processing applied). Credited on the About page.
//
// AUDIO GESTURE REQUIREMENT: browsers refuse to produce sound from an AudioContext that hasn't
// been resumed from within a real user gesture -- the exact same constraint orientation-lock.js's
// fullscreen toggle ran into. armPresenceAlertAudioContext primes one (and starts loading/
// decoding both clips into AudioBuffers) on the very next tap anywhere, the same one-time-listener
// pattern as that file's own armFullscreenReentryOnNextGesture, so the FIRST real alert -- which
// could fire at any time, with no guarantee of a specific gesture right before it -- doesn't
// silently fail to make a sound.

const PRESENCE_ALERT_ENABLED_STORAGE_KEY = "belugas_presence_alert_enabled";
let presenceAlertAudioContext = null;
let presenceAlertBuffers = { red: null, yellow: null };
let previousZoneStatusById = {};

function isPresenceAlertEnabled() {
  const stored = localStorage.getItem(PRESENCE_ALERT_ENABLED_STORAGE_KEY);
  return stored === null ? true : stored === "1"; // default ON per the request
}

function setPresenceAlertEnabled(enabled) {
  localStorage.setItem(PRESENCE_ALERT_ENABLED_STORAGE_KEY, enabled ? "1" : "0");
}

// Tries the mp3 first (universally decodable via Web Audio in every current target browser);
// falls back to the ogg only if that somehow fails to decode. Both files are shipped either way
// (see APP_SHELL, sw.js) since the request asked for both regardless of which actually gets used.
async function fetchAndDecodeWithFallback(ctx, basePath) {
  for (const ext of ["mp3", "ogg"]) {
    try {
      const response = await fetch(`${basePath}.${ext}`);
      if (!response.ok) continue;
      const arrayBuffer = await response.arrayBuffer();
      // Callback form (not the newer Promise-returning overload) -- broader Safari support.
      return await new Promise((resolve, reject) => ctx.decodeAudioData(arrayBuffer, resolve, reject));
    } catch (e) {
      continue;
    }
  }
  return null;
}

async function loadPresenceAlertBuffers() {
  const ctx = presenceAlertAudioContext;
  if (!ctx) return;
  const [red, yellow] = await Promise.all([
    fetchAndDecodeWithFallback(ctx, "audio/alert-red"),
    fetchAndDecodeWithFallback(ctx, "audio/alert-yellow")
  ]);
  presenceAlertBuffers.red = red;
  presenceAlertBuffers.yellow = yellow;
}

function armPresenceAlertAudioContext() {
  if (presenceAlertAudioContext) return;
  document.addEventListener(
    "pointerdown",
    () => {
      try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        presenceAlertAudioContext = new Ctx();
        if (presenceAlertAudioContext.state === "suspended") presenceAlertAudioContext.resume();
        loadPresenceAlertBuffers();
      } catch (e) {
        console.warn("PRESENCE_ALERT_AUDIO_CONTEXT_ERROR", e);
      }
    },
    { capture: true, once: true }
  );
}

function playAlertSound(quiet) {
  const ctx = presenceAlertAudioContext;
  const buffer = quiet ? presenceAlertBuffers.yellow : presenceAlertBuffers.red;
  if (!ctx || !buffer) return; // never armed (no gesture yet this session), or still decoding -- skip, not an error
  if (ctx.state === "suspended") ctx.resume();
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  source.start(0);
}

function vibrateForTransition(quiet) {
  if (!("vibrate" in navigator)) return;
  navigator.vibrate(quiet ? [80] : [150, 80, 150]);
}

// "if Notification permission is already granted" -- deliberately never requests it here;
// push-notifications.js's own explicit "Enable Alerts" tap is the one place this app ever asks.
async function showPresenceAlertNotification(card) {
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
  try {
    const registration = await navigator.serviceWorker.ready;
    await registration.showNotification("BELUGAS", {
      body: presenceBannerLabel(card),
      icon: "icons/icon-192.png",
      tag: "belugas-presence-alert" // replaces any still-showing prior alert instead of stacking
    });
  } catch (e) {
    console.warn("PRESENCE_ALERT_NOTIFICATION_ERROR", e);
  }
}

function firePresenceAlert(card, quiet) {
  if (!isPresenceAlertEnabled()) return;
  playAlertSound(quiet);
  vibrateForTransition(quiet);
  showPresenceAlertNotification(card);
}

// Diffs each currently-relevant zone's status against what it was the last time this ran, keyed
// by zoneId -- so a transition is detected exactly once, per zone, regardless of how many other
// unrelated fields changed on the same presence-state tick (staleness flags, nearby zones, etc.
// all also call notifyPresenceStateChanged). Reuses presenceBannerCards directly
// (presence-banner.js) rather than recomputing status independently, so this can never disagree
// with what the banner itself is showing right now -- registered AFTER initPresenceBanner's own
// listener (see app.js's call order) so rebuildPresenceBannerCards has already refreshed
// presenceBannerCards by the time this runs on the same notify.
//
// card.isLoading zones are skipped entirely (not yet a real reading) -- and the FIRST real
// (non-loading) status ever recorded for a zone is just the baseline, never an alert on its own:
// without this, a zone that's ALREADY red the moment its first real fetch lands (nothing to do
// with a genuine change the user is present for) would incorrectly fire an alert on load.
function checkPresenceAlertTransitions() {
  presenceBannerCards.forEach((card) => {
    if (card.isLoading) return;
    const previous = previousZoneStatusById[card.zoneId];
    const current = card.status;
    if (previous !== undefined && previous !== current) {
      if (current === PRESENCE_RED && previous !== PRESENCE_RED) {
        firePresenceAlert(card, false);
      } else if (current === PRESENCE_YELLOW && previous === PRESENCE_RED) {
        firePresenceAlert(card, true);
      }
    }
    previousZoneStatusById[card.zoneId] = current;
  });
}

function updatePresenceAlertToggleUi() {
  const toggleBtn = document.getElementById("menu-presence-alert-toggle-item");
  if (!toggleBtn) return;
  toggleBtn.textContent = isPresenceAlertEnabled() ? "🔔 Sound Alerts: On" : "🔕 Sound Alerts: Off";
}

function initPresenceAlert() {
  armPresenceAlertAudioContext();
  onPresenceStateChanged(checkPresenceAlertTransitions);

  const toggleBtn = document.getElementById("menu-presence-alert-toggle-item");
  if (toggleBtn) {
    updatePresenceAlertToggleUi();
    toggleBtn.addEventListener("click", () => {
      setPresenceAlertEnabled(!isPresenceAlertEnabled());
      updatePresenceAlertToggleUi();
    });
  }
}
