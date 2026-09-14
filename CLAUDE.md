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

**About's header shows this same version number** (`webapp/index.html`'s `.about-version` span,
"ABOUT · v{N}") — deliberately in the header row, not as a line at the bottom of the page's own
scrollable content, so it's always visible regardless of scroll position or the presence banner's
state. No build step ties the two together: bump `.about-version`'s number by hand every time
`CACHE_NAME` changes, so they never drift apart.

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

**Standard way the user applies a migration by hand:** `tools\apply-latest-migration.ps1` — finds
the newest file in `supabase\migrations\`, prints its name, asks for a Y/N confirm, then runs it
via the same CLI command below and prints the result. Point the user at this script rather than
having them type the raw command, unless they specifically want to apply something other than the
newest migration.

**Whenever a migration file is created, end that message with the exact PowerShell line the user
runs to apply it** (real filename filled in, in its own code block) — this is not a security risk
(it uses the linked project's own saved credentials, and the user is the one who runs it, never
Claude), so never omit it:

```powershell
& "$env:LOCALAPPDATA\supabase-cli\supabase.exe" db query --linked --file supabase\migrations\<FILENAME>.sql
```

### Legal pages

`PRIVACY.html` and `LICENSE.html` at the **repo root** (not under `webapp/`) are static, hand-
written renderings of `PRIVACY.md` and `LICENSE`, styled to match the app (teal gradient, dark
card, brand yellow) but otherwise self-contained — no dependency on `webapp/css/style.css`, so
they stay stable regardless of what changes inside the app's own SPA. They exist because
`.nojekyll` (required so GitHub Pages doesn't try to run Jekyll over `webapp/`) also means
`PRIVACY.md`/`LICENSE` are never auto-rendered to HTML the way Jekyll would otherwise do —
without these two files, `/PRIVACY.html` and `/LICENSE.html` simply don't resolve at all.
**If `PRIVACY.md` or `LICENSE` changes, update the matching `.html` file's content to match by
hand** — there's no build step tying them together. About's own PRIVACY POLICY/LICENSE buttons
(`webapp/index.html`) link to these with `../` (repo-root-relative from `webapp/`'s own deployed
path), opened in a new tab rather than as in-app subpages.

**Known low-priority issue (item 71):** each page's own "Back to BELUGAS" link tries
`window.opener`-close / `history.back()` before falling back to a plain `href="webapp/"` reload
(see each file's own inline `<script>`) — confirmed on a real device that this still falls through
to the reload every time, not just the opened-directly case it's meant for. Most likely cause:
this app runs installed as a standalone-mode PWA on the devices that hit this, and `target="_blank"`
from a standalone PWA opens the system browser as a genuinely separate app/process, not a sibling
browser tab — `window.opener` never gets a live reference to close back to in that context, unlike
two tabs within one ordinary browser instance. Not re-attempted for now: the system back
gesture/button already returns correctly (it's specifically the in-page Back **button** that
reloads instead of returning), so this is a rough edge, not a dead end.

### Audio assets

`webapp/audio/alert-red.mp3`/`.ogg` (~1.7s) and `alert-yellow.mp3`/`.ogg` (~1.0s, quieter/shorter)
are the in-app presence-alert sounds (item 87, `webapp/js/presence-alert.js`) — real Cook Inlet
beluga vocalizations, not a synthesized tone.

- **Source**: NOAA Fisheries' "Beluga Whale Vocalizations" video —
  https://videos.fisheries.noaa.gov/detail/video/6282639097001/beluga-whale-vocalizations
  ("General vocalizations by Cook Inlet beluga whales coupled with audio spectrogram... multiple
  beluga social vocalizations", per the video's own Brightcove metadata).
- **License**: a U.S. federal government work — public domain in the United States (17 U.S.C.
  § 105), no rights-holder permission needed. Credited anyway on the About page ("SOUND: Beluga
  vocalizations — NOAA Fisheries") as a courtesy and to name the actual source, not because it's
  legally required the way the app's own PolyForm/artwork terms are.
- **How it was obtained**: the page embeds a Brightcove player (account `659677166001`, player
  `4b3c8a9e-7bf7-43dd-b693-2614cc1ed6b7`, video id `6282639097001`). Fetched the player bundle
  (`https://players.brightcove.net/659677166001/4b3c8a9e-7bf7-43dd-b693-2614cc1ed6b7_default/index.html?videoId=6282639097001`)
  to extract its embedded `policyKey`, then called Brightcove's Playback API
  (`https://edge.api.brightcove.com/playback/v1/accounts/659677166001/videos/6282639097001` with
  header `Accept: application/json;pk=<policyKey>`), which returns a `sources` list including a
  plain progressive MP4 (no HLS demuxing needed). Downloaded that MP4 directly.
- **Processing**: extracted the audio track with ffmpeg (a static build via the `imageio-ffmpeg`
  pip package, since no system `ffmpeg` is installed in this dev environment).
- **BUG (found and fixed after shipping)**: the ORIGINAL cut of all four files (`loudnorm=I=-16:
  TP=-1.5:LRA=11` for red, `I=-23:TP=-3:LRA=11` for yellow) shipped as **pure digital silence** —
  confirmed via `ffmpeg -af volumedetect`: `mean_volume`/`max_volume` both exactly `-91.0 dB` (the
  16-bit PCM noise floor) on every one of the four files, decoded raw samples literally all-zero.
  This went undetected through on-device verification for a while because checking "does a
  `BufferSourceNode` get created and `start()`ed with the right `buffer.duration`" (which DOES
  correctly succeed even on a silent buffer) is not the same as checking "does the buffer contain
  actual signal" — the two are easy to conflate. **Root cause: single-pass `loudnorm` is
  unreliable on clips this short.** `loudnorm`'s integrated-loudness (EBU R128) measurement needs
  enough audio for its internal gating algorithm to find stable gated blocks; ffmpeg's own docs
  recommend it for broadcast-length content and single-pass mode is known to misbehave well under
  ~3s. Applied to a 1.0–1.7s clip, it drove the gain low enough to be indistinguishable from
  silence. Fixed by dropping `loudnorm` entirely in favor of a deterministic two-step process: (1)
  trim/fade/compress, (2) measure the ACTUAL peak with `ffmpeg -af volumedetect`, then apply
  exactly the linear gain needed (`-af volume=XdB`) to hit a target peak — verified after every
  step, including after the final `.mp3`/`.ogg` encode (lossy encoding shifts peak by a few tenths
  of a dB; not worth chasing further).
- **Current cut** (re-done from the same source clip, `full_source.wav` in that session's
  scratchpad if it's still present — re-extract from the Brightcove MP4 via the steps above
  otherwise): both windows were chosen by scoring every 100ms-stepped window of the source clip on
  loudness (RMS) minus spectral centroid (a "how high-pitched" proxy), favoring loud AND
  low-pitched over just loud alone.
  - `alert-red`: 11.8s–14.3s (2.5s — extended from an original 11.8s–13.8s cut that ended just
    before a louder, more audible squeal; the fade-out was shortened to 0.12s at the same time so
    that squeal isn't faded away right as it arrives) of the original clip, mono, fade in 0.08s /
    fade out 0.12s, light compression (`acompressor=threshold=-18dB:ratio=2.5:attack=20:
    release=250:makeup=1`), then gained to a measured **-1.0 dBFS peak** (RMS **-19.0 dBFS**; 92.0%
    of spectral energy below 500Hz, 99.0% below 2kHz — nothing near the 6kHz+ range that would
    read as thin/harsh on a phone speaker).
  - `alert-yellow`: 12.2s–13.4s (1.2s, within the same passage), same fade/compression shape,
    gained to a measured **-6.0 dBFS peak** (RMS **-20.5 dBFS**) — quieter on purpose, the "step
    down" cut for RED → YELLOW, not a new emergency.
  - Encoded to both `.mp3` (libmp3lame, 96kbps) and `.ogg` (libvorbis, q:a 4) — small files
    (11–25KB each), `presence-alert.js` tries mp3 first, falls back to ogg. Re-verify with
    `volumedetect` after any future re-cut — it would have caught this bug immediately.
- **iOS behavior, documented not fixed**: plain Web Audio API playback (this app's own
  `AudioContext`, no special audio-session category) respects the iPhone's physical Ring/Silent
  switch — flip it to silent and the alert sound is silenced along with everything else in-page,
  while `navigator.vibrate` and the local notification still fire. Noted on Resources' "HOW TO USE
  THIS APP" so this reads as expected platform behavior, not a bug report.

### Live device inspection (adb + Chrome DevTools Protocol)

Two physical Android phones are available over USB, USB debugging authorized: `adb` lives at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (there is no `adb` on PATH in this
environment — always invoke it by that full path, e.g. `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`
from bash). Run `adb devices` to see current serials — they were `ZY22JSTXPW` (locked with
fingerprint auth at the time of setup — unusable for this until unlocked by hand, adb cannot
authenticate a fingerprint) and `ZY22KH9WHP` (no lock, usable directly) — **check both are still
attached and note which is actually unlocked before relying on either serial**, since which
physical phone is unlocked can change between sessions.

**Item 85 established this so future sessions verify real behavior on-device instead of asking the
user to read a debug overlay and report back.** Two independent capabilities, both driven from
this same adb connection:

**Screenshots** — `adb -s <serial> exec-out screencap -p > shot.png`. Must be run from `cmd`/bash
(this repo's Bash tool), **never** PowerShell — PowerShell's `>` redirection is text-mode by
default and corrupts the binary PNG stream. Save into your scratchpad directory, then `Read` the
PNG file directly (the Read tool renders images).

**Live page access (read/drive real page state, not just look at pixels)**:
1. `adb -s <serial> forward tcp:<local-port> localabstract:chrome_devtools_remote` — pick a
   distinct local port per device if inspecting both at once (e.g. 9222/9223), since two devices
   can't share one forwarded port.
2. If Chrome isn't already showing the app: `adb -s <serial> shell am start -a
   android.intent.action.VIEW -d "https://gmessy30.github.io/BELUGAS/webapp/?debug=1"
   com.android.chrome` (append `?debug=1` to also get the on-page debug overlay's own state,
   useful as a cross-check). The device's screen must be on and unlocked first (`adb -s <serial>
   shell input keyevent KEYCODE_WAKEUP`, then check `adb -s <serial> shell dumpsys window | grep
   isKeyguardShowing` — if `true` and there's no PIN-less swipe, that device needs a human to
   unlock it; don't attempt to bypass a lock).
3. `GET http://localhost:<local-port>/json` lists every open tab (title, url,
   `webSocketDebuggerUrl`) — find the one whose `url` matches the deployed app.
   `tools/device-inspect/find_tab.py <local-port> [url-substring]` does this lookup (prints every
   tab with no substring, or just the matching tab's websocket URL with one).
4. Connect to that tab's `webSocketDebuggerUrl` and send the Chrome DevTools Protocol's
   `Runtime.evaluate` over it to run arbitrary JS in the live page — read any variable/DOM
   state, or drive the app directly (call its own global functions, e.g. `switchTab('map')`,
   `openPlaybackPanel()`, click a real chip/button via `.click()` to exercise its actual handler
   rather than hand-simulating one). `tools/device-inspect/cdp_eval.py <websocket-url> "<js
   expression>"` wraps this (needs `pip install websocket-client`).
   - **Gotcha**: recent Chrome rejects a DevTools WebSocket connection whose `Origin` header isn't
     on an allow-list (`403 Forbidden`, "Rejected an incoming WebSocket connection from the
     http://... origin") — there's no practical way to pass `--remote-allow-origins` to a stock
     installed Android Chrome, so the fix is to omit the `Origin` header entirely
     (`websocket-client`'s `create_connection(..., suppress_origin=True)`), not to try to guess an
     allowed value. `cdp_eval.py` already does this.
   - No Node.js is installed in this environment (the user's own message suggested a Node + `ws`
     script) — these tools use Python (`websocket-client`/`requests` via pip) instead, which was
     available. Node would work identically if it's ever present.
5. `fetch('./sw.js').then(r=>r.text())` from within a `Runtime.evaluate` call is a reliable way to
   confirm which `CACHE_NAME` is actually LIVE on the server (sw.js itself is never in
   `APP_SHELL`, so this always hits the network, not a cached copy) — cross-check this against
   `navigator.serviceWorker.controller`'s state (`hasController`/`controllerState:"activated"`) to
   confirm the tab is actually running that version, not just that a newer one exists on GitHub
   Pages while an older one is still installed/controlling.

Tear down forwards when done with `adb forward --remove tcp:<local-port>` (or `--remove-all`) —
they otherwise persist across adb server restarts until explicitly removed or the device
disconnects.

**Rotating the device from adb (no human needed to physically turn the phone)**:
`adb -s <serial> shell settings put system accelerometer_rotation 0` (disables auto-rotate, so the
next line sticks instead of the phone rotating back), then `adb -s <serial> shell settings put
system user_rotation 1` for landscape or `0` for portrait. Prefer this over asking the user to
rotate their phone by hand, or over emulating it purely in-page — this genuinely rotates the real
device/Chrome viewport, so real `@media (orientation: ...)` rules and real element geometry both
respond exactly as they would for an actual user. (`Emulation.setDeviceMetricsOverride` over CDP
can also fake a landscape viewport size for a quick check without touching the real device
orientation, but the adb rotation above is the more faithful option when both are available.)

**Reaching a specific screen** (e.g. the manual logging screen) via CDP instead of asking the user
to navigate there by hand: call the app's own real navigation functions through `Runtime.evaluate`
(e.g. `openManualReportFlow()` for Report Manually with no camera/GPS involved), the same way a
real tap would — never hand-write a substitute DOM state. See "drive the app directly" above.

**Actually confirming a control is tappable** (not just that its bounding rect doesn't
mathematically overlap another element's) — dispatch real pointer events at its own computed
center via CDP's `Input.dispatchMouseEvent` (or `element.getBoundingClientRect()` center +
`document.elementFromPoint(x, y)` to see what element would actually receive a tap there, which is
the more direct check: if `elementFromPoint` at a button's own center returns something other than
that button or its own descendant, a tap there will hit the wrong thing, e.g. an overlapping
higher-z-index element like the BearingDial). Prefer this over pure bounding-rect-overlap math,
which can miss a real conflict (or flag a false one) whenever z-index/`pointer-events` decide the
outcome, not just geometry.

**"Played" is not "audible"** — confirming that a `BufferSourceNode`/`<audio>` element was created
and `start()`ed (or `.play()`ed) with the right `buffer.duration` only proves playback was
*attempted*, not that the buffer contains actual signal: a silent (all-zero) buffer passes that
exact same check. This is a real bug that shipped and went undetected this way (item 87/88's alert
clips were pure digital silence — `ffmpeg -af volumedetect` showed `-91.0 dB` mean/max on all four
files — while every "did it play" check kept passing). When verifying audio, always report the
buffer's actual peak and RMS in dBFS (`ffmpeg -i <file> -af volumedetect -f null -`, or decode it
in-page via `AudioContext.decodeAudioData` and compute peak/RMS directly from the channel data) —
never just that playback started.

### Open items / not yet done

- **Migration not yet applied**:
  `supabase/migrations/20260923000000_add_sighting_activities_and_tier1_confirmation.sql` (items
  90/63 — the ACTIVITIES field + tier-1 confirmation of an existing sighting) has been written but
  NOT applied — the user reviews the `get_kenai_presence_state` diff first, then applies it
  themselves via `supabase db query --linked --file <path>`. Until it's applied, the client's own
  tolerant fallback (`db.js`'s `sightingSchemaHasNewColumns`) keeps everything else working
  without `activities`/`activity_note`/`confirmed_at`, and `is_tier_one_observer`/`confirm_sighting`
  simply don't exist yet — CONFIRM SIGHTING stays hidden (`cachedIsTierOneObserver` resolves to
  `false` on the RPC-not-found error, same as any other failure).
- **Migration not yet applied**:
  `supabase/migrations/20260924000000_add_article_moderation_rpcs.sql` (item 91 — article
  moderation on `webapp/admin/`) has been written but NOT applied — apply via
  `supabase db query --linked --file <path>`. Adds `'rejected'` to `article_status`, two new
  columns (`reviewed_by`, `reviewed_at`), and `list_pending_articles`/`set_article_status` (gated
  on `tier_admins`, same allow-list the tier-code RPCs use). No client-side schema-tolerance
  fallback here unlike item 90/63's `sightingSchemaHasNewColumns` — this is an admin-only page one
  person uses right after applying the migration themselves, not a public-facing path many
  concurrent users hit before a migration lands, so a plain `console.error` + "nothing renders"
  until it's applied is an acceptable, much lower-stakes failure mode.
- **Native-side parity item (item 90, web-only so far)**: the ACTIVITIES field (multi-select:
  Travelling, Milling, Feeding Observed, Benthic Feeding Evidenced, Courtship Behaviours, Other +
  a note) and its chip-picker modal (`webapp/index.html`'s `#activity-picker-modal`,
  `submit-view.js`'s `initActivityPicker`) exist only in the PWA so far. `ManualLoggingScreen.kt`
  (shared by both native entry points, same as `#manual-log-step` here since item 60) has no
  equivalent control yet — worth adding the same multi-select chip picker there for parity,
  alongside the other pending native-parity items above.
- **Native-side BUG (item 83a, not yet fixed there)**: `PlaybackRange.kt`'s `QuickRange.resolve()`
  has the identical bug the webapp just fixed in `map-view.js`'s `recomputePlaybackRange` —
  `start.coerceIn(dataMinMs, dataMaxMs)`/`end.coerceIn(...)` clamp TODAY/YESTERDAY/THIS_SEASON's
  own well-defined calendar boundaries against the loaded sighting data's own min/max, which
  silently pulls TODAY's start backward into an earlier window whenever nothing has been observed
  yet today (the most recent sighting overall landing on yesterday's data clamps TODAY's start
  down to yesterday's timestamp). This is a real, faithfully-ported bug, not a web-only
  divergence — worth fixing in `PlaybackRange.kt` too: only clamp ALL_TIME/CUSTOM to the data
  range; leave TODAY/YESTERDAY/THIS_SEASON's calendar boundaries unclamped (an empty result is
  correct when nothing's been observed in that window yet).
- Web Push via FCM: needs the real Firebase Web config + VAPID key pasted into
  `webapp/js/firebase-config.js` (currently placeholder values) before it can work at all.
- Self-service re-verification by email (deferred to after the Sunday deadline).
- **Make sighting inserts idempotent** (found while investigating item 73's phantom-marker bug):
  `insertSighting`/`attemptUploadAndInsert` (`webapp/js/db.js`, `offline-queue.js`) do a plain
  `.insert(record)` with no client-supplied id and no server-side uniqueness check. If the insert
  actually succeeds but the success response is lost to a dropped connection, the client sees a
  network error and retries (immediately, or later via the offline queue's own drain), creating a
  genuine duplicate row — this is a data-integrity gap, not the rendering bug item 73 fixed (the
  local queue purges correctly on a *recognized* success; this is about the case where success
  itself was never recognized). Fix: have the client generate the row's own id (uuid) before the
  first insert attempt and reuse that same id on every retry of the same submission, so a retried
  insert after a lost success response can be made to no-op (upsert on that id, or a unique
  constraint) instead of creating a second row.
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
- **Native-side parity item (item 65, web-only so far)**: Resources' "HOW TO USE THIS APP" section
  (`webapp/index.html`, mirrored in `ResourcesScreen.kt`) was rewritten for item 60's unified
  reporting flow (photo optional, then the same map+crosshair+BearingDial for both entry points)
  and gained a short paragraph on the credentialed-observer concept. About gained a plain "WHAT'S
  NEW — {date}" section (`webapp/index.html`'s About-page content, no equivalent in
  `AboutScreen.kt` yet) with a handful of short lines on today's user-visible changes. Apply the
  same text to `ResourcesScreen.kt`/`AboutScreen.kt` for parity, alongside the other pending items
  above.
- **Native-side parity item (item 92, web-only so far)**: `ManualLoggingScreen`'s own RECENTER
  (`webapp/index.html`'s `#manual-recenter-btn`, shared by both entry points since item 60) is now
  MY LOCATION — a tap takes a fresh GPS fix (`getCurrentPositionOnce`, tier-code.js) and centers
  the MAP VIEW on it at `GPS_CENTER_ZOOM` (14, ~1-2km visible), plus a small pulsing dot
  (`.observer-location-dot`) at the observer's own last-fetched position for the duration of this
  screen — client-only, cleared on exit, never stored or submitted. A long-press keeps the OLD
  plain "reset to the Cook Inlet overview" behavior (`DEFAULT_MAP_CENTER`/`DEFAULT_MAP_ZOOM`) as a
  fallback gesture, not a removal. Crucially, this does NOT reopen the "Get My Location" problem
  item 60 deliberately removed (see `openManualReportFlow`'s own comment in `submit-view.js`) —
  that one set the SUBMITTED position to the observer's fix; this one only ever calls
  `map.setView`, and the crosshair/`manualLat`/`manualLng` still read from wherever the map ends up
  panned to at SUBMIT time, exactly as before. `ManualLoggingScreen.kt`'s own RECENTER has no
  equivalent yet — worth porting the same tap/long-press split, the accuracy-fix zoom level, and
  the pulsing observer-position dot there too, alongside the other pending items above.
- **Native-side parity item (item 66, web-only so far)**: the PWA's BearingDial no longer draws
  the decorative teardrop pin at the ring's north point — the crosshair was always the sole
  position marker (both here and in `BearingDial.kt`), so the pin was pure decoration removed to
  stop competing with it. `PinGraphic`/its `BearingDial` call site (`ManualLoggingScreen.kt`) still
  draw it natively — worth removing there too for parity, alongside the other pending items above.
  (No native equivalent needed for the drag-pivot re-centering this same item fixed on the web
  side — that mismatch was specific to this app's own CSS/SVG implementation, between
  `bearingFromPointerEvent`'s element-center assumption and the ring's own off-center SVG
  coordinates; native's `detectDragGestures`/Canvas both already operate in the same coordinate
  space with no equivalent offset to begin with.)
- **Native-side parity item (item 70/79, web-only so far)**: About gained a
  `© 2026 Keen Eye Apps · Licensed under PolyForm Noncommercial 1.0.0` line under DEVELOPMENT and
  a `© 2026 Luna Montgomery · All rights reserved` line under ARTWORK (`webapp/index.html`), plus
  PRIVACY POLICY/LICENSE link buttons under the credits (item 69, opening the new root-level
  `PRIVACY.html`/`LICENSE.html` — see this file's own "Legal pages" section above). Item 79 gave
  each copyright its own terms directly rather than a single shared "Free for non-commercial use"
  line below both — that line read as covering the artwork too, which it never actually did (the
  artwork is separately copyrighted, all rights reserved, not under the software's PolyForm
  license at all). None of this exists in `AboutScreen.kt` yet — worth adding the same copyright
  lines (with the SAME per-section terms split, not a shared summary line) and legal links there
  for parity, alongside the other pending items above. (The actual license text/artwork carve-out
  itself — `LICENSE`, `README.md` — is already project-wide, nothing platform-specific to port
  there.)
