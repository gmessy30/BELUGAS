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
- Standalone status page (item 97/97b/97c) at `/webapp/status/` — its own `index.html`/`status.js`,
  deliberately NOT part of the main SPA/service-worker `APP_SHELL`, zone-aware via `?zone=<slug>`
  (default `kenai`). See that directory's own header comments for the full design.
- Printable signs (item 98) at `/webapp/print/` — `status-sign.html`/`.pdf` (zone-parameterized,
  same `?zone=` convention as `/status/`) and `app-sign.html`/`.pdf` (zone-agnostic). PDFs
  generated via headless Chrome (`chrome --headless --print-to-pdf`); regenerate by hand after any
  content change to the matching `.html` — no build step ties them together, same as
  `PRIVACY.html`/`LICENSE.html` below.

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

**Standing rule: every commit that ships a user-visible change updates About's WHAT'S NEW section
in the SAME commit** — not a later cleanup pass. This was not being followed (items 87/88/90/63/91/
92/93/97/97b/97c/98 all shipped without a WHAT'S NEW update, caught and backfilled all at once by
item 99) — the whole point of catching it is to stop doing that again, not to have caught it once.
`.about-version`'s own bump-on-every-push discipline (above) is the model to match: WHAT'S NEW
should never again need a dedicated backfill pass.

**WHAT'S NEW dates: use the REAL current date, never a guessed or rounded one.** The section is
grouped by date (`.whats-new-date` subheadings, newest first) — add each entry under the actual
date it ships (check the environment's "Today's date" or `git log`'s commit date, never infer it),
starting a new group if today has none yet; never re-date an existing group to "today" just
because a new line landed. This went wrong twice (item 106): the old single heading was set to
SEPTEMBER 27 and then SEPTEMBER 28, 2026 — both in commits actually made on Sept 14 — and then
stayed that way while Sept 15/16 entries were added under it. Resources' "HOW TO USE THIS APP" section
(`webapp/index.html`) is held to the same standard whenever a change affects something that section
actually describes (a reporting-flow control, a banner-color meaning, a menu item's behavior) —
it drifted out of date the same way WHAT'S NEW did, for the same reason.

### Known pattern: a layer opened from a CLOSING layer's own handler (item 107)

`history.back()` is asynchronous. `navigateBack()` (nav-stack.js) only REQUESTS a back-step; the
`popstate` that actually tears the layer down lands in a later task. So a handler that called
`navigateBack()` and then, in the same tick, opened another layer pushed that layer onto a stack
with a navigation still in flight -- and the popstate handler then reconciled `navLayers` down to
the depth the older history entry claimed, tearing the brand-new layer straight back down.

The symptom is a modal that opens and vanishes within one gesture, leaving the flow silently
abandoned with no error anywhere: the SUBMIT button appears to do nothing at all. It shipped this
way and went unnoticed because it only fires on the SYNCHRONOUS branch -- in
`proceedManualSubmit`, a position that fails `isWhalePositionVerified` outright reaches
`showGeofenceWarning` in the same tick, whereas the coastline-fallback branch `await`s first,
which lets the popstate land and the modal survives. Real user impact: an off-coastline position
could not be saved at all.

**Use `navigateBackThen(fn)` (nav-stack.js), not `navigateBack()`, whenever the follow-up work can
open a layer of its own.** It defers `fn` until after the popstate reconciliation, so anything it
pushes lands on a settled stack. Both submit-view.js call sites use it (the submit-confirm modal's
Confirm, and the geofence warning's SAVE ANYWAY). Capture any `pending*Action` in a local BEFORE
calling it -- the layer's own `onPop` is what clears those, and it now runs first.

Diagnosing this needs the stack, not the screen: read `navLayers.map(l => l.name)` immediately
after the click and again a few seconds later. A layer present in the first read and gone from the
second is this bug, not a rendering problem.

**Second bug, found in the same handler while verifying the first**: SAVE ANYWAY called
`pendingFinishAction()` with NO argument, so `finish = (verified) => finishManualSubmit(lat, lng,
verified)` ran with `verified === undefined`, `is_geofence_verified` was `undefined` on the
record, and `JSON.stringify` dropped the key from the request body. On INSERT this was invisible
-- the column is `NOT NULL DEFAULT false` and false is what SAVE ANYWAY means -- but item 106's
edit path refuses a position patch that doesn't carry the flag, so SAVE ANYWAY on an edit failed
outright. **A value that "works" only because a column default happens to match it is not being
sent**; check the actual captured request body, not just the outcome.

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

**Admin roles/zones/audit log (item 101, APPLIED — see "Migrations" below)**: `tier_admins` carries an `owner`
boolean (exactly one such row ever, enforced by a partial unique index — keeneyeapps@gmail.com is
the owner) and a `zone_slug`. A non-owner admin's every action (`issue_tier_code`/
`list_tier_codes`/`revoke_tier_code`) is scoped server-side to their own zone, not just hidden in
the admin UI — the owner alone sees/acts on every zone. `tier_roster` records `issued_by` and
`zone_slug` at issuance. Owner-device protection: `tier_roster.is_owner_device` (settable only at
issuance, only by the owner) marks a code `revoke_tier_code` refuses to touch for anyone but the
owner, regardless of zone. `tier_admins` membership itself (`add_tier_admin`/`remove_tier_admin`/
`update_tier_admin_zone`) is owner-only; `remove_tier_admin` refuses the owner row even when the
owner is the one calling it (no self-lockout). No RPC anywhere changes the `owner` flag itself —
transferring ownership is deliberately left a manual, out-of-band operation, not a casual
admin-page action. Every admin RPC (issue/revoke/publish/reject/unpublish/add-remove-update-admin)
writes to `admin_actions`, readable only via `list_admin_actions` (owner-only).

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

**When Claude may apply a migration itself** (as opposed to always handing the user the line
above): only for a small, additive, low-risk change — the kind where the blast radius is obvious
from reading the file, e.g. widening an existing RLS policy's role list or adding a genuinely new
policy/grant. Even then, show the migration's content (or diff) in the same message before running
it — applying it silently is never appropriate regardless of how low-risk it looks.

**Hard rule, no exception for "it looked safe": never apply a migration that touches
`get_kenai_presence_state`, sightings data (the `sightings` table itself, or any RPC/column that
reads or writes it), or `tier_roster` (including the RPCs that are tier_roster's only door in --
`issue_tier_code`, `redeem_tier_code`, `list_tier_codes`, `revoke_tier_code`) without first showing
the user the full diff and getting an explicit go-ahead.** These three are the app's core
observer-tier and presence-state logic — get_kenai_presence_state in particular has already had
its own header comment rewritten well over a dozen times chasing subtle gate-timing bugs (see its
migration history, 20260903030000 through 20260923000000) precisely because small-looking changes
here have repeatedly had non-obvious real-world effects. This applies no matter how small the
change looks, and regardless of the general allowance above for low-risk additive changes.

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

**A REAL INSERT FROM A TEST IS A REAL NOTIFICATION.** Reporting a sighting through the UI on a
test device fires `on_sighting_insert_notify` for real: during item 106's verification one such
test insert inside the Kenai banner area dispatched the live webhook, which returned
`{"sent":2,"failed":6}` -- two actual subscriber devices got a push for a whale that was never
there. The row was deleted afterward, but a delivered notification cannot be recalled. **Verify
write paths against synthetic rows inside a `begin; ... rollback;` transaction instead** (pg_net's
`net.http_post` only writes to `net.http_request_queue`, an ordinary table write invisible to its
background worker until commit, so a rollback discards the dispatch -- confirmed: 2 requests
queued, 0 sent). If a real insert is genuinely unavoidable, put it somewhere no subscription can
match -- recipients come from `match_notification_recipients` (20260829010000: zone polygons,
custom polygons and point+radius subscriptions, NOT the flat `device_tokens` broadcast the
original 20260823000000 comment describes), so a position far from every subscribed zone/point
returns `{"sent":0,"failed":0,"reason":"no matching recipients"}` -- and delete the row afterward
regardless.

**Stale JS is the default on localhost, not the exception.** Two separate verification rounds ran
against code that was already fixed on disk: `python -m http.server` sends no `Cache-Control`, so
Chrome heuristically caches, AND this app's own service worker serves `APP_SHELL` cache-first
under whatever `CACHE_NAME` was current when the tab first loaded. A page that "doesn't have" a
function you just wrote is almost always this, not a syntax error -- check
`typeof yourNewFunction` before debugging anything else. Serve with `Cache-Control: no-store` and
unregister the service worker (`getRegistrations()` + `caches.delete`) before trusting any
on-device result. Note a new port is a new ORIGIN: `localStorage` (and so
`belugas_subscriber_id`, which decides whose sightings are editable) starts empty there.

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

- **Migration applied and verified** (correcting stale docs — items 90/63):
  `supabase/migrations/20260923000000_add_sighting_activities_and_tier1_confirmation.sql` IS live
  — this entry previously (wrongly) said "not yet applied." Found and corrected during item 97b's
  migration work: querying the linked project's live `get_kenai_presence_state` definition
  directly showed item 63's `or s.confirmed_at is not null` clause already present, and
  `is_tier_one_observer`/`confirm_sighting` both already exist with the expected signatures.
  `activities`/`activity_note`/`confirmed_at` are live on `sightings` too. CONFIRM SIGHTING should
  work normally now, not stay hidden — worth a real on-device check next time someone's in that
  flow, since this drift means it was never re-verified after whenever this actually got applied.
- **Migration applied and verified** (item 91 — article moderation on `webapp/admin/`):
  `supabase/migrations/20260924000000_add_article_moderation_rpcs.sql` is live. Full on-device
  pass confirmed: a real submission via the Suggest form lands as `pending_review` and shows up in
  `list_pending_articles`; `list_pending_articles` correctly rejects an anon caller
  (`not_authorized`, confirming the `tier_admins` gate); PUBLISH makes it render in the News Feed
  on a fresh page load (no cache issue); UNPUBLISH (`set_article_status` back to
  `'pending_review'`) removes it from the feed and returns it to the queue. No known issues.
- **Migration applied and verified** (item 96 — article submission was broken by a signed-in
  admin session): `supabase/migrations/20260925000000_allow_authenticated_article_submission.sql`
  is live. Root cause: `20260823120000_add_articles.sql`'s original "Anon can submit pending
  articles" INSERT policy was granted `to anon` only. Since the admin login (item 91's Supabase
  Auth on `webapp/admin/`) shares one localStorage across the WHOLE `gmessy30.github.io` origin
  (not scoped to `/admin/`), any device that had ever logged into admin carried that JWT into
  ordinary main-app requests too — so a totally normal "+ SUGGEST AN ARTICLE OR PAPER" submission
  from that device ran as the `authenticated` role, which had NO insert policy on this table at
  all, and got rejected with `42501 new row violates row-level security policy for table
  "articles"`. Confirmed on a real device (Stylus, `ZY22JSTXPW`, which had a live
  `sb-vwbcrctzsqukutvlbqwy-auth-token` session from earlier item 91 testing) two ways: (1) a
  `fetch()` intercept around a live `submitArticle()` call captured the exact outgoing payload —
  `{"title":...,"summary":...,"source_url":...,"submitted_by":null,"content_type":"news"}`, fully
  compliant with the original `WITH CHECK` (`status` omitted, defaults to `pending_review`; no
  `reviewed_by`/`reviewed_at` sent) — proving the payload was never the problem; (2) `set local
  role authenticated; insert into public.articles (...)` inside a rolled-back transaction against
  the linked project reproduced the identical error with no client involved, isolating it to role,
  not payload. Not a policy that got dropped or narrowed by `20260924000000` — that migration never
  touched this policy; it's a gap that was harmless before admin auth and public submission shared
  an origin. Fix replaces the policy with one covering `to anon, authenticated`, and additionally
  requires `reviewed_by is null and reviewed_at is null` in the `WITH CHECK` (those two columns
  postdate the original policy and were never covered by it) — restoring the complete intended
  invariant: anon or a signed-in admin may INSERT only rows with `status = 'pending_review'` and no
  `reviewed_by`/`reviewed_at` set. Re-verified on-device post-fix: the same `submitArticle()` call
  from the still-authenticated Stylus session now succeeds (`{ok:true}`), and a crafted
  self-publish attempt (`status = 'published'`) as `anon` is still correctly rejected — the
  tightened check didn't loosen anything else.
- **Migration applied and verified** (item 96 follow-up — full audit for the same anon-only gap):
  `supabase/migrations/20260926000000_widen_anon_only_policies_to_authenticated.sql` is live.
  Queried the linked project directly (`pg_policies` for schemas `public` and `storage`, plus
  every `public.*` function's actual EXECUTE grants via `pg_proc`/`aclexplode`) rather than
  trusting migration file text, same lesson as item 96 itself. Found 10 policies still scoped
  `to anon` only: `device_tokens` (INSERT/SELECT/UPDATE — register, the upsert-conflict SELECT,
  and refresh), `kenai_departure_reports` (SELECT), `subscriptions` (INSERT/SELECT/UPDATE/DELETE),
  and `storage.objects` for the `sighting-photos` bucket (INSERT/UPDATE). Fixed with
  `alter policy ... to anon, authenticated` (a pure role-widening, no check-expression changes
  needed, unlike item 96's own fix) so each keeps its original name/identity. Confirmed clean
  elsewhere: every `public.*` function already grants EXECUTE to both roles wherever it grants to
  anon at all (Supabase's default-privileges template applies regardless of what any one
  migration's own `grant ... to anon;` line says) — tier-code redemption specifically was already
  fine; `sightings`' own insert/read policies are already `to public`, which already covers
  authenticated; and `tier_roster`/`tier_code_redeem_attempts`/`subscriber_identities`/
  `tier_admins` have RLS enabled with zero table-level policies for anon OR authenticated by
  design (RPC-only access, see "Tier codes" above), so there was no anon-only policy to widen
  there. Re-verified on-device post-fix on the same authenticated Stylus session: a real
  `registerDeviceToken()` upsert and a real `createPointSubscription()` insert both now succeed
  (previously would have hit the same 42501 as item 96). See CLAUDE.md's own new "When Claude may
  apply a migration itself" rule above — this migration qualified as low-risk/additive and doesn't
  touch `get_kenai_presence_state`/sightings/`tier_roster`, so it was applied directly rather than
  handed to the user, per that rule's own stated exception.
- **Migration applied and verified** (item 101 — admin roles, zone scoping, audit log):
  `supabase/migrations/20260929000000_add_tier_admin_roles_zones_and_audit_log.sql` is live —
  applied by the user directly (not by Claude — this migration touches `tier_roster`, squarely in
  CLAUDE.md's own "Hard rule" territory). Full design in this file's own "Tier codes" section
  above; full reasoning (including the flagged design choice — how "a device the owner holds" is
  represented, via an explicit `is_owner_device` flag rather than inferred, since nothing in the
  existing schema links a `tier_admins` row to a specific `tier_roster` row) in the migration's
  own header comment. **Known gap, corrected**: the migration's own backfill only covered `owner`/
  `issued_by` — it did not retroactively flag the owner's 4 pre-existing devices as
  `is_owner_device`, so those went unprotected by `revoke_tier_code`'s owner-device check until
  noticed. Fixed by hand (direct SQL against the linked project, not a re-run of this file); the
  migration file itself was updated afterward to add that same backfill (by row id, not by
  matching on `name`) so it now honestly describes what was actually needed to reach the live
  database's real state — see its own "CORRECTION FOR THE RECORD" comment. `webapp/admin/`
  (`admin.js`/`index.html`/`admin.css`)'s own rendering logic is confirmed correct against the
  live schema (a real device, stubbed RPC responses matching the actual confirmed row shapes) --
  the zone header and, for the owner, the ADMINS/AUDIT LOG sections all render correctly. The RPCs
  THEMSELVES have a live bug found right after, see the next entry below -- that mock pass
  couldn't have caught it (it never called the real function bodies).
- **Migration applied** (item 106 — edit your own most recent sighting):
  `supabase/migrations/20260930000000_add_edit_my_last_sighting.sql` — applied by the user via
  `tools\apply-latest-migration.ps1` (it touches `sightings`, so Claude never applies it). Adds
  `edited_at`, `sighting_edit_window()` (the 4 hours, written down exactly once), the visibility
  RPC `get_my_editable_sighting`, and `edit_my_last_sighting(p_subscriber_id, p_updates jsonb)`.
  **An UPDATE is silent by construction** — confirmed against the LIVE trigger set, not this
  repo's migration text: `on_sighting_insert_notify` (AFTER INSERT) and
  `on_sighting_insert_set_observer_tier` (BEFORE INSERT) are the only non-internal triggers on
  `sightings`, so an edit re-fires neither the FCM webhook nor the observer-tier stamp. Anything
  that later adds an `after update` trigger here has to revisit that claim.
  - `p_updates` is a jsonb PATCH (key present = set, including an explicit null; key absent =
    leave alone) rather than named params, precisely so "clear the travel bearing" and "don't
    touch the travel bearing" are expressible separately. Allowlisted keys only; anything else
    RAISES rather than being silently dropped.
  - **`is_geofence_verified` moves with the position, always** — patching the position without it
    raises, and patching it without a position raises. The value is client-computed and asserted,
    which is exactly what INSERT already does (the real check is CoastlineGeometry/geofence.js,
    which has no SQL equivalent — see 20260920000000's own scope note), so an edit is neither
    more nor less client-trusted than the original report. An earlier draft left the stale flag
    alone on a position edit; that was rejected in review for good reason.
  - Never reachable by an edit: `observer_tier`, `confirmed_at`/`confirmed_by_subscriber_id`,
    `observed_at_epoch_ms` (walking a row forward through tide cycles is what drives RED/YELLOW),
    `subscriber_id`/`created_at`/`id`.
  - Client (`db.js`/`map-view.js`/`list-view.js`/`submit-view.js`): EDIT appears on exactly one
    row — whichever `get_my_editable_sighting` names — and opens `#manual-log-step` pre-filled
    (counts, activities, position, direction, photo). **The photo is shown but NOT replaceable**:
    the RPC accepts a `photo_url` patch, but offering a swap means routing back through the
    camera step while holding edit state, deliberately left out of this item. `edited_at` renders
    as a quiet "· edited" mark on the map popup and list item.
  - **BUG FOUND AND FIXED DURING VERIFICATION, worth remembering**: the first cut of
    `get_my_editable_sighting` computed `editable_until` but never FILTERED on the window, so an
    aged-out row was still named and the client drew an EDIT button that `edit_my_last_sighting`
    could only ever refuse — exactly the dead end the feature exists to avoid. Caught only
    because the verification pass tested the aged-out case explicitly rather than assuming the
    deadline arithmetic implied the filter. The client now ALSO checks the returned deadline
    (`isEditableSighting`, map-view.js), which covers the one case the server can't: an app left
    open across the boundary, whose cache only refreshes on the next sightings refresh.
  - `edited_at` is its own optional-column group in `db.js` (`SIGHTING_LIST_COLUMNS_EDIT`, its own
    flag), NOT folded into item 90/63's trio — they come from different migrations and can be
    independently missing, and `fetchRecentSightings` now downgrades one group at a time
    (newest first) in a loop rather than a single retry.

- **Migration WRITTEN, NOT applied — needs the user's explicit go-ahead** (item 101 bug fix):
  `supabase/migrations/20260929010000_fix_email_type_mismatch_in_tier_admin_rpcs.sql`.
  `list_tier_codes`/`list_tier_admins`/`list_admin_actions` (all three, 20260929000000) select
  `auth.users.email` straight into a `RETURNS TABLE` column declared `text` -- but that column is
  actually `character varying(255)`, and PL/pgSQL's `RETURN QUERY` requires an exact type match
  (a plain `select` would coerce this fine; `RETURN QUERY` does not). Confirmed live for all three
  functions individually (`set local role authenticated; set local request.jwt.claims =
  '{"sub":"<owner uuid>", ...}'` against the linked project) -- each fails outright with
  "structure of query does not match function result type ... character varying(255) does not
  match expected type text." **This is a live regression, not just a new-feature bug**:
  `list_tier_codes` is the SAME function the admin page's pre-existing ISSUED CODES list has
  always used, so the admin page's core code list is currently broken in production, not just the
  three new item 101 sections. Fix is a one-word `::text` cast at each `u.email` reference, CREATE
  OR REPLACE, identical signatures, no schema change. Still touches tier_roster/tier_admins-reading
  function bodies, so it's flagged for the user's go-ahead per the Hard rule despite the urgency,
  same as everything else in this category.
- **Migration WRITTEN, NOT applied — needs the user's explicit go-ahead** (item 97b):
  `supabase/migrations/20260927000000_add_kenai_red_qualifying_sightings_rpc.sql` adds
  `get_kenai_red_qualifying_sightings`, a new read-only RPC returning the actual RED-qualifying
  sighting row(s) (position, counts, `travel_bearing_degrees`) for `/webapp/status/`'s RED-state
  compact map — `get_kenai_presence_state` itself only ever returns the newest qualifying
  timestamp, never the rows. Its body is the RED-branch logic from `get_kenai_presence_state`
  copied verbatim (same cycle-low/departure-report ratchet/tier/confirmed/banner-area check) so
  the map can never show a sighting that isn't actually why the banner is RED. This is exactly the
  kind of migration CLAUDE.md's own "Hard rule" above exists for (touches
  `get_kenai_presence_state`'s own logic and sightings data directly) — **do not apply it without
  showing the user this diff and getting an explicit go-ahead first**, regardless of how
  mechanical the copy looks. Apply via `supabase db query --linked --file <path>` once approved.
- **Standalone status page** (items 97/97b/97c) at `/webapp/status/`: full design/rationale lives
  in that directory's own file header comments (`index.html`, `status.js`,
  `kenai-landmarks.js`) — summarized here for discoverability. Reads
  `get_kenai_presence_state`/`get_watched_zone_statuses` directly via `fetch()` (never loads
  supabase-js) to stay fast on a weak connection; refreshes every 5 minutes while open; zone-aware
  via `?zone=<slug>` (default `kenai`) for every `is_banner_watched` zone, not just Kenai. RED
  state additionally shows a compact, lazily-loaded Leaflet map (geofence.js/kenai-landmarks.js
  also lazy-loaded, only then) of the actual RED-qualifying sighting(s), each with a
  `travel_bearing_degrees`-rotated direction arrow and a "Last seen … " plain-text line — Kenai
  gets river-relative "heading upriver/downriver" phrasing (geofence.js's real
  `KENAI_RIVER_CENTERLINE`) plus the small `kenai-landmarks.js` "near X" lookup; every other zone
  gets a plain compass point and no landmark (no lookup exists for them). Currently only `kenai` is
  `is_banner_watched` live, so `?zone=` for anything else renders an honest "UNKNOWN ZONE" state
  rather than silently substituting Kenai's data — this is expected until/unless another zone gets
  flagged, not a bug. The Share page's STATUS PAGE chip mode has its own zone picker (only shown
  when more than one zone is watched), driven by the same `get_watched_zone_statuses` list.
- **Printable signs** (item 98) at `/webapp/print/`: `status-sign.html` is zone-parameterized (same
  `?zone=` convention as `/status/`, generates its QR client-side via qrcodejs since it now needs a
  different code per zone) and keeps Kenai's own established "…before you launch" phrase, with a
  generic "Belugas in {Zone}? Scan for the latest." for every other zone (`ZONE_DISPLAY_NAMES` is a
  small static lookup there, deliberately NOT a live DB call — a PDF export shouldn't depend on
  network timing). `app-sign.html` is zone-agnostic and stays fully static (pre-generated inline
  SVG QR, `python`'s `qrcode` library, error-correction level H) since the app link never varies by
  zone. Both signs are deliberately plain black-on-white (no app brand colors) for home-printer
  grayscale friendliness, and letter/A4-agnostic (`@page` sets only a margin, never a `size`, so
  the browser's print dialog picks). The two `.pdf` files alongside them were generated via
  `chrome --headless --print-to-pdf` from each `.html` — regenerate by hand after any content
  change, same as `PRIVACY.html`/`LICENSE.html`'s own PDF-less but analogous hand-sync convention.
- **EXPORT DATA page** (item 100, `webapp/js/export-view.js`): the one deliberate PWA↔native
  parity exception — direction is normally PWA→native (PWA is authoritative when the two
  disagree), but this page had no PWA equivalent at all despite `PRIVACY.md` already promising a
  public data download, so native's `ExportScreen.kt`/`ExportUtils.kt` was the explicit reference
  to port FROM, then narrowed to this app's own current data-model/privacy conventions on top.
  Menu position matches native exactly (About → Export Data → Alerts). Calls `export_sightings`
  (already updated for `activities`/`confirmed_at`) across the full `OUTER_GEOFENCE_*` bounding
  box — no region/zone picker, unlike native's `Regions.ALL` + per-region named zones, since this
  app has never exposed a region concept anywhere else in its own UI. Exports CSV or GeoJSON (not
  native's CSV-only, optionally zipped with a `photos/` folder — this app links `photo_url`
  directly in both formats instead of bundling actual image bytes) with a plain HTML date-range
  picker defaulting to ALL TIME (native defaults to a trailing 30 days — an explicit, deliberate
  difference for this item, not an oversight), and shows the matching row count BEFORE download
  (native has no such preview). **Field allowlist is the actual privacy boundary, enforced by
  construction**: `export-view.js`'s `buildExportCsv`/`buildExportGeoJson` only ever read
  position/time/counts/`travel_bearing_degrees`/`activities`/`activity_note`/`photo_url`/a
  `confirmed` boolean (`confirmed_at != null`) off each row — never `subscriber_id`,
  `confirmed_by_subscriber_id`, `observer_id`, or `observer_tier`, even though
  `export_sightings`' own RPC response includes several of those (confirmed directly against its
  live return columns during this item's own work) — narrower than native's own CSV, which also
  includes `observer_type`/`is_geofence_verified`/`observer_tier`/`position_source`/
  `uncertainty_*`/`travel_bearing_source`. Verified end-to-end on a real device via CDP + adb: a
  real CSV and a real GeoJSON file both actually landed in `/sdcard/Download/` on Android Chrome
  (`Browser.setDownloadBehavior` over CDP, `adb pull` to inspect the bytes), confirming the field
  list matches this allowlist exactly with nothing extra leaking through.
- **Dead-link handling for the News Feed (item 93c, spec only — not built)**: nightly check
  (pg_cron or a scheduled edge function) does a HEAD/GET on each published article's `source_url`,
  storing `last_checked_at`/`http_status`; two consecutive failures mark it `link_broken`, and the
  feed shows a small "link unavailable" badge (not hidden entirely) with the admin page listing
  broken links for review plus a fix-URL field. At publish time, request a Wayback Machine
  snapshot (`web.archive.org/save/<url>`) and store the archive URL, so a broken card can offer
  "View archived copy" instead. Papers with a DOI: store the DOI and link via `doi.org`, which
  outlives publisher URLs. None of this is implemented yet — needs its own migration
  (`link_broken` status/column, `last_checked_at`/`http_status`/`archive_url`/`doi` columns) and a
  scheduled job, a bigger, separate piece of work from item 93's client-side search.
- **Native-side asset swap still pending (final artwork)**: Luna Montgomery's FINAL breaching-beluga
  artwork for the whale-count buttons is in place in `webapp/img/` only --
  `Whitebreaching.png`/`Greybreaching.png`/`Calfbreaching.png`/`Unknownbreaching.png`, all four
  confirmed 280x120 8-bit RGBA with real alpha (fully transparent corners, 24-57% fully
  transparent pixels plus antialiased semi-transparent edges), not merely an alpha channel that
  happens to be opaque. The four same-named files under
  `shared/src/commonMain/composeResources/drawable/` are STILL the old placeholders (confirmed by
  hash -- all four differ from the webapp copies) and need the identical swap during the native
  parity pass, alongside every other pending item below.
- **Native-side parity item (item 114, web-only so far)**: the BearingDial's white ring now
  carries curved "BELUGAS" (top) / "GO HERE" (bottom) text plus inward-pointing arrowheads at the
  left and right, the same "this is what you are aiming" language as CaptureScreen's
  SketchedReticle, adapted to a full circle. See index.html's own `bearing-dial-ring-label`
  comment for the geometry and why the two baselines sit at different radii (SVG turns glyphs
  outward on a top arc and inward on a bottom arc). **Known, accepted cosmetic issue, decided by
  the user rather than worked around**: the needle is ~12px wide (4px yellow + 8px black outline)
  and ends at r=58, the middle of the 14px band, so at bearings within roughly 25 degrees of N or
  S it crosses one letter -- "GO |ERE" at due south. The label is drawn AFTER the needle so the
  word survives rather than being punched out, but the crossing itself is unavoidable at this
  size. Shortening the needle to r=50 removes it completely and was explicitly declined: the
  ring/dial/needle stay exactly as `BearingDial.kt` has them, and the curved text is the only
  addition. `BearingDial.kt` has no ring text at all yet.
- **Native-side parity item (item 113, web-only so far)**: the Sightings Map's playback FAB now
  carries a permanent date-range pill beside it (`#playback-range-label`, map-view.js's
  `updatePlaybackRangeLabel`) showing the active range at all times, amber whenever it isn't
  ALL TIME, because the range filters the map's DEFAULT view too (`isWithinDateRange` ignores
  `playbackIsOpen`) and there was otherwise nothing on screen saying data was being hidden. Same
  item stopped RESTORING the quick range on a fresh load (item 83b's persistence now covers fade
  window/speed only) and replaced the FAB's `⏱` glyph -- which renders on Android Chrome as a
  circle-with-a-stem, reading as a power button -- with an inline SVG clock face.
  `SightingsMapScreen.kt` has neither the label nor the icon change.
- **Native-side parity item (item 105, web-only so far) -- "verified" definition**: the PWA's
  one shared rule is `isVerifiedSighting` (`webapp/js/db.js`) = `observer_tier` 1/2 OR
  `confirmed_at` set; a photo alone never counts. It drives the map/list VERIFIED ONLY toggles,
  the "✓ Verified"/"✓ Confirmed" badge (CONFIRM SIGHTING hidden on any already-verified row,
  including a tier-1/2 observer's own report -- nothing to elevate), and Export Data's own
  VERIFIED ONLY filter plus its `confirmed` column's value (column name kept, value now from this
  rule). `tier_roster.is_owner_device` is an admin revoke-immunity flag only, never read by this
  rule -- owner devices are ordinary tier 1 for reporting. Native's
  `SightingRecord.isHighConfidence` (`SightingRecord.kt`) is still `photoUrl != null ||
  observerTier == 1 || observerTier == 2` -- the same gap in both directions: it counts a bare
  photo, and ignores `confirmed_at` (a tier-1-confirmed tier-3 sighting stays hidden under
  "high confidence only"). Port the same rule there (the model also needs `confirmedAt`), used by
  `SightingsMapScreen.kt`/`App.kt`'s `OfflineSightingsList`, alongside the other pending items.
  Not a bug, deliberately left as-is: `observer_tier` is tier at SUBMISSION time, so a sighting
  submitted before its device redeemed a code stays unverified (two such live rows, `a29c0f59`/
  `58f3fa47`, Sept 13 2026 -- historically accurate, not backfilled).
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
