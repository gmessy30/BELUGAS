// Minimal app-shell cache -- just enough for the PWA install criteria and a usable reload while
// offline. Deliberately NOT an offline sighting queue (out of scope for this pass): a sighting
// submitted with no connection still just fails at the fetch/insert call, same as any ordinary
// web page.
const CACHE_NAME = "belugas-shell-v1";

// Relative to this file's own location (webapp/), so this works whether the app is served from
// a domain root or a subpath like /BELUGAS/webapp/.
const APP_SHELL = [
  "./",
  "./index.html",
  "./manifest.json",
  "./css/style.css",
  "./js/config.js",
  "./js/db.js",
  "./js/map-view.js",
  "./js/list-view.js",
  "./js/submit-view.js",
  "./js/app.js",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/apple-touch-icon.png"
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
