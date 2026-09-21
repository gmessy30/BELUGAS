// Minimal app-shell cache -- just enough for the PWA install criteria and a usable reload while
// offline. Deliberately NOT an offline sighting queue (out of scope for this pass): a sighting
// submitted with no connection still just fails at the fetch/insert call, same as any ordinary
// web page.
// BUMP THIS on every deploy that changes any file in APP_SHELL (or this file itself) -- it's
// the only thing that makes the activate handler below actually replace the old cached shell.
// Without a version bump, a returning visitor's already-installed service worker sees byte-
// identical install/activate logic and never even attempts an update.
const CACHE_NAME = "belugas-shell-v104";

// Web Push (FCM) background-message handling -- coordinates with the app-shell caching below by
// living in this SAME service worker file rather than a separate firebase-messaging-sw.js (the
// two options item 11 offered): this file already registers at the whole /webapp/ scope, so
// there's nothing a second, separately-registered SW would cover that this one doesn't already.
// Wrapped in try/catch so a not-yet-configured deploy (js/firebase-config.js still has its
// placeholder values) or a transient CDN fetch failure during a SW update never breaks the
// install/activate/fetch handlers below, which this whole PWA's offline/reload behavior depends
// on regardless of whether push is set up yet.
try {
  importScripts(
    "https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js",
    "https://www.gstatic.com/firebasejs/10.14.1/firebase-messaging-compat.js",
    "./js/firebase-config.js"
  );
  if (!FIREBASE_CONFIG.apiKey.startsWith("REPLACE_")) {
    firebase.initializeApp(FIREBASE_CONFIG);
    // Constructing this registers firebase-messaging-compat.js's own internal "push" and
    // "notificationclick" listeners, which auto-display a system notification from a background
    // message's notification payload and open its webpush.fcm_options.link on click -- no
    // explicit onBackgroundMessage/notificationclick handler needed here on top of that.
    firebase.messaging();
  }
} catch (e) {
  console.error("SW_FIREBASE_MESSAGING_INIT_ERROR", e);
}

// Relative to this file's own location (webapp/), so this works whether the app is served from
// a domain root or a subpath like /BELUGAS/webapp/.
const APP_SHELL = [
  "./",
  "./index.html",
  "./manifest.json",
  "./css/style.css",
  "./js/config.js",
  "./js/firebase-config.js",
  "./js/nav-stack.js",
  "./js/install-prompt.js",
  "./js/db.js",
  "./js/push-notifications.js",
  "./js/geofence.js",
  "./js/heading-distance.js",
  "./js/presence.js",
  "./js/presence-state.js",
  "./js/offline-queue.js",
  "./js/tier-code.js",
  "./js/share.js",
  "./js/about.js",
  "./js/resources.js",
  "./js/news-feed.js",
  "./js/export-view.js",
  "./js/subscriptions.js",
  "./js/presence-banner.js",
  "./js/presence-alert.js",
  "./js/main-menu.js",
  "./js/launch-screen.js",
  "./js/orientation-lock.js",
  "./js/photo-lightbox.js",
  "./js/map-view.js",
  "./js/list-view.js",
  "./js/submit-view.js",
  "./js/app.js",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/apple-touch-icon.png",
  "./img/beluga_sketch.png",
  "./img/beluga_background.png",
  "./img/beluga_capture_button.png",
  "./img/Unknownbreaching.png",
  "./img/Greybreaching.png",
  "./img/Calfbreaching.png",
  "./img/Whitebreaching.png",
  "./img/beluga_about_sketches.jpg",
  "./img/breaching_belugas_sketches.png",
  "./audio/alert-red.mp3",
  "./audio/alert-red.ogg",
  "./audio/alert-yellow.mp3",
  "./audio/alert-yellow.ogg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Only ever cache-manage this app's own same-origin files. Everything else -- Supabase API
  // calls, map tiles, the Leaflet/Supabase-js CDN scripts -- goes straight to the network
  // untouched, so this never masks a stale sightings list or a broken upload behind a cached
  // response.
  if (event.request.method !== "GET" || url.origin !== self.location.origin) {
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).then((response) => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
        return response;
      });
    })
  );
});
