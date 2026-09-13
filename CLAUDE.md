# BELUGAS

## webapp/

A plain HTML/CSS/JS PWA (no framework, no build step) under `/webapp` — a stopgap for iOS/Android
browser users who can't install the native Kotlin Multiplatform app. Ports native behavior from
`shared/src/commonMain/kotlin/com/cookinlet/belugas/` (and platform actuals) as faithfully as
possible: read the actual native source/SQL before changing webapp behavior, and flag any
deliberate deviation from native in a code comment rather than silently diverging.

- Leaflet.js (+ Leaflet.markercluster) and the Supabase JS client, both loaded via CDN `<script>`
  tags — no bundler, no npm. Deployed by GitHub Pages at `/webapp/`; `.nojekyll` lives at the repo
  root so Pages doesn't try to run Jekyll over it.
- Admin page (tier-code issuance) at `/webapp/admin/` — login-gated (Supabase Auth + `tier_admins`
  allow-list), not linked from the main app's menu.

### Service worker

`webapp/sw.js` registers with `updateViaCache: "none"` plus a `controllerchange` reload, so a new
deploy actually takes over an already-open tab instead of requiring a full close/reopen.
**Always bump `CACHE_NAME` on any change to a file listed in `APP_SHELL`** (check the current value
in `webapp/sw.js` rather than trusting a number here, it moves every deploy) — without a bump, a
returning visitor's installed service worker sees byte-identical install/activate logic and never
attempts an update.

### Known pattern: Leaflet's internal z-index escapes an unisolated container

Leaflet's bundled CSS gives its own internal panes real z-index values (tile pane 200, overlay
400, marker 600, controls 1000). Any element that hosts an `L.map(...)` instance MUST have
`isolation: isolate` (or an equivalent stacking-context trigger) in this app's CSS, or those
values escape the container and compete directly against sibling UI in the SAME parent stacking
context — since 200+ beats a typical UI z-index like 2-5, the map paints OVER that sibling
entirely (not just a corner control), not merely under it. This has already recurred twice:
- The Sightings Map's own OSM attribution poking through every other screen (`.view` didn't
  isolate `#map-view`'s Leaflet instance) -- fixed with `isolation: isolate` on `.view`.
- Report Manually's entire bottom panel (SELF/OTHER, whale count, direction picker, date/time)
  rendering invisible under the map (`.manual-map` didn't isolate its own Leaflet instance) --
  fixed with `isolation: isolate` on `.manual-map`, and proactively on `.picker-map` (the Alerts
  point/polygon pickers, same latent bug, not yet symptomatic) for the same reason.

Every current Leaflet container in the app is covered by one of these three selectors --
`.view` (`#map-view`), `.manual-map` (`#manual-map`), `.picker-map` (`#point-picker-map`/
`#polygon-picker-map`). **Any new Leaflet map added to this app needs the same treatment on its
own container the moment it's created**, not just when a symptom is actually reported.

### Deliberate deviations from native (documented in code, not oversights)

- **Launch screen defaults to Menu**, not Camera — native's own real default (confirmed in
  `AppPreferences.android.kt`/`.ios.kt`) is Camera; the web app was explicitly directed to default
  to Menu anyway. The Menu/Map/Camera picker itself still matches native's `MainMenuDrawer`.
- **No compass-heading/magnetometer picker** for travel direction — browsers can't reliably read a
  compass heading cross-platform, so travel bearing is set via an absolute compass-rose control
  instead of native's relative AWAY/LEFT/RIGHT-against-a-live-heading scheme.
- **No persistent bottom tab bar** — removed in favor of native's actual primary navigation, the
  full-screen `MENU` overlay (`MainMenuDrawer`), reached via a "≡ MENU" button, matching native
  exactly rather than the web-only tab bar an earlier pass had invented.
- **Presence banner** is gated by (subscription OR within 15 km), while **map zone shading** is
  global or (every viewer sees every server-flagged watched zone, no gating at all) — this asymmetry
  is real in native source, not a bug to reconcile.

### Tier codes

One code per device, each independently revocable (not a multi-device-per-person model). Only
door into `tier_roster` is a small set of RPCs — anon/authenticated have **zero** direct grants on
that table. Admin-only RPCs (`issue_tier_code`, `list_tier_codes`, `revoke_tier_code`) additionally
require the caller's `auth.uid()` to be listed in `tier_admins` — being merely logged in via
Supabase Auth is not enough on its own.

### Migrations

`supabase/migrations/20260920000000_add_server_side_outer_geofence_check.sql` and
`supabase/migrations/20260921000000_add_tier_admin_rpcs.sql` are **already applied** to the linked
project (via `supabase db query --linked --file <path>`) — don't tell the user to run them again.
**Convention: apply migrations via that CLI command, never the Supabase web SQL editor.**

### Open items / not yet done

- Admin page styling pass (Mae background / dark cards, matching the rest of the app more closely).
- Web Push via FCM: needs the real Firebase Web config + VAPID key pasted into
  `webapp/js/firebase-config.js` (currently placeholder values) before it can work at all.
- Self-service re-verification by email (deferred to after the Sunday deadline).
- Real-device testing still needed for: time-lapse playback + clustering (Sightings Map), the
  Android/PWA back-gesture nav stack, Camera/Report Manually's responsive (portrait+landscape,
  no forced orientation) layout and fullscreen-on-touch-devices behavior, and the Android/iOS
  install prompt — none of this has been exercised in an actual browser this session.
- **Native-side parity item**: the PWA's presence banner (RED phase, "CHECK MAP") is now tappable
  and navigates straight to the Sightings Map (webapp/js/presence-banner.js's
  `handlePresenceBannerTap`) — a field suggestion implemented web-only so far. `PresenceBanner.kt`/
  `BelugaPresenceBannerCarousel` don't have this yet; worth adding there too for parity.
- **Native-side parity item**: the PWA's Kenai RED banner label now appends " · NEXT WINDOW
  ~{time}" using `gate_time_possible_epoch_ms` (`webapp/js/presence.js`'s `kenaiBannerLabel`) —
  native's own `kenaiBannerLabel` (PresenceBanner.kt) only ever surfaces that field in the BLUE
  branch today ("NOT EXPECTED IN THE RIVER BEFORE {time}"), never during RED. Worth adding the
  same RED-branch addition natively for parity.
- **Native-side parity item (item 60, DESIGN CHANGE, web-only so far)**: automatic whale placement
  (a heading+distance projection outward from the observer's own GPS fix) kept landing whales on
  land, so both reporting paths were changed to place the whale by human map placement instead.
  On the PWA, capturing a photo (or skipping the camera) now goes straight into the same
  map+crosshair+BearingDial screen "Report Manually" already used, photo attached
  (`submit-view.js`'s `enterManualLogStepFromCamera`) — the old LoggingScreen-style review step,
  its heading/distance picker, and the "Can't Place This Sighting" dialog are gone entirely from
  the web app. The one thing the camera path still does that plain "Report Manually" deliberately
  doesn't: it takes a best-effort GPS fix at capture time to center the map initially (never
  stored, never the submitted position). Native (`LoggingScreen.kt`, `HeadingDistancePicker.kt`,
  `CaptureScreen.kt`'s hand-off, `CoastlineGeometry`'s offshore-guess fallback) is UNCHANGED so
  far — deliberately deferred, not forgotten: unify Camera → `ManualLoggingScreen` with the photo
  attached, remove `HeadingDistancePicker`/the projection math/the coastline fallback entirely,
  matching the web app's new design. Apply alongside every other pending native-parity item above
  in one later pass, then rebuild for both Android and iOS.
- **Native-side parity item (item 62c, web-only so far)**: the PWA's REPORT DEPARTURE button
  (`webapp/js/tier-code.js`'s `submitDepartureReport`, the "WHALES LEFT?" section reached via the
  hidden 7-tap nose gesture) is now a two-step action — a `confirm()` dialog ("Report that the
  whales have left? This steps RED to YELLOW and can't be undone for 3 hours.") gates the actual
  RPC call, since the button sits in the same modal that gesture opens and a stray trailing tap
  landing on it would otherwise fire an irreversible (3h) report with no chance to back out.
  `TierClaimScreen.kt`'s own "WHALES LEFT?" button has no equivalent confirmation step yet — worth
  adding the same two-step gate there for parity.
