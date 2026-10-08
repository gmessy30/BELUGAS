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
physical phone is unlocked can change between sessions. A third device, `R5GL14LNFCH` (Samsung
SM-X238U tablet), was also attached as of item 119; it had no Chrome DevTools socket open then
(Chrome not running), and it isn't assigned to BELUGAS testing.

**Phone assignment: `ZY22JSTXPW` (the Stylus) is BELUGAS's designated test device; the Edge is
kept free for the separate WARN project's parallel sessions.** Use the Stylus for BELUGAS
on-device work unless the user says otherwise. **This convention is being violated in practice,
not just at risk of it.** During item 119 (Sept 26, 2026), the Stylus's `accelerometer_rotation`
flipped 1 → 0 → 1 with no command from the BELUGAS session. The suspected cause is a WARN session
driving the wrong phone, and the user reports this has happened at least once already that same
night. Treat anything on the Stylus that this session didn't do (rotation, settings, tabs, a
Chrome restart) as possibly another project's live session, not as noise. Report it; don't
quietly work around it.

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

**HARD RULE: leave the phone user-ready. This is part of finishing the task, not optional
cleanup.** These are real phones someone picks up and uses in the field — a session that verified
on one is not done until that phone is back to a normal state. Before reporting the work finished,
every one of these:

- **Mobile data / wifi back ON** if either was turned off for testing (e.g. to exercise the
  offline queue or a weak-connection path). A phone left in airplane mode misses the very push
  alerts this app exists to deliver.
- **No `?debug=1` tabs left open** — that query param turns on the on-page debug overlays
  (tier-code.js's item-47 overlay, submit-view.js's bearing-dial overlay), which a real user
  should never be looking at.
- **Test/local-server tabs closed** — anything on `localhost:<port>` or a `reverse`-forwarded
  address is a dead page the moment the dev machine's server stops, and it is NOT the deployed
  app: it has its own origin, its own empty `localStorage` (so a different `belugas_subscriber_id`,
  meaning a different idea of whose sightings are editable) and possibly its own stale service
  worker. Leaving one open is how a user ends up reporting a whale into a dead tab. Close them and
  leave the phone on the real deployed URL, or on whatever it was showing before.
- **Every settings change reverted** — `accelerometer_rotation` / `user_rotation` (see the
  rotation section below) back to what they were, and anything else touched. Record the prior
  value BEFORE changing it so there is something to restore to.
- **Display/rotation settings get an explicit before/after check EVERY session, whether or not
  you meant to touch them** — read `accelerometer_rotation` and `user_rotation` on each phone
  you'll use BEFORE starting any work, write the values down in your own notes, and read them
  again as the last cleanup step. A mismatch is restored to the start value and reported, even
  if nothing you ran should have changed it. Item 119 is why: ZY22JSTXPW read
  `accelerometer_rotation=1` at session start and `0` at the end, with no `settings put` issued
  by that session at all. It was caught only because the end-of-session check happened to
  include it, and it had flipped back to `1` by itself minutes later. Something else on the
  device changes it (a parallel session on the same phone, e.g. WARN's, is the likely
  candidate), so "I didn't change it" is not evidence that it's unchanged. Compare against the
  start snapshot, never against memory of what you did.
- **adb port forwards and reverses removed** — `adb -s <serial> forward --remove-all` and
  `adb -s <serial> reverse --remove-all`.
- **Re-register the service worker if it was unregistered** for a no-store test, or at minimum
  load the real deployed URL once so it re-installs — otherwise the phone has no offline shell.

**If a session ends without doing this** — crash, token limit, an interrupted turn, a phone that
locked mid-session and could not be reached — **the NEXT session checks the phone's state FIRST,
before starting any new work, and restores it.** Do not assume the previous session left things
clean; assume it did not, and verify. A quick pass: `adb devices`, then list open tabs via
`tools/device-inspect/find_tab.py <port>` (looking for `localhost:` and `debug=1` in the URLs),
then `adb -s <serial> shell settings get system accelerometer_rotation` / `user_rotation`, then
`adb -s <serial> forward --list` and `reverse --list`.

**A locked phone does not excuse this** — it defers it. If the device is locked (biometric, and
CLAUDE.md's own rule above says never bypass a lock), say so explicitly in the final report, name
exactly what was left in a non-clean state and on which serial, and ask the user to unlock so it
can be finished. An unreported dirty phone is the failure this rule exists to prevent; a reported
one is just an open item.

**A locked or screen-off phone throttles Chrome to near-nothing, so a CDP TIMEOUT means UNKNOWN,
never FAILED — and never SUCCEEDED either.** Re-enumerate and read the actual state; do not infer
the outcome from whether the call came back. This bit twice in the single session that wrote this
rule, in both directions:
- A `GET /json` tab listing exceeded a 120s tool timeout and a follow-up `/json/version` probe
  timed out at 8s, which was reported as "the DevTools socket isn't serving while locked" and the
  cleanup declared blocked on a human unlock. Wrong: the listing had actually succeeded in the
  background and returned all 21 tabs. Nothing was blocked at all.
- Eight `GET /json/close/<id>` calls returned nothing inside a 60s timeout while two returned
  `Target is closing`. A re-enumeration showed **all ten** had closed. A slow success and a
  no-op are indistinguishable from the caller's side.

So: give these calls generous timeouts (run them with `run_in_background: true` rather than
fighting a 120s tool limit), and settle every question with a fresh enumeration of real state --
`/json` for tabs, `forward --list`/`reverse --list` for forwards, `settings get` for settings --
rather than with the return value of the call that was supposed to change it. Tens of seconds per
request is normal against a sleeping phone; it is not a malfunction and it is not a reason to
give up and hand the work back to the user.

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
queued, 0 sent).

**CORRECTION (item 119): position does NOT make a real insert silent.** This section used to say
a position far from every subscribed zone/point returns "no matching recipients". That is false.
`match_notification_recipients` has a second branch that returns EVERY `device_tokens` row whose
subscriber has no active subscription (or has a null subscriber_id), regardless of where the
sighting is. Checked live on Sept 26, 2026: a remote point and a position-less row each returned
7 recipients out of 16 tokens. Any committed insert that fires the trigger alerts those devices.

**When a real, committed row is genuinely needed** (e.g. to tap a real button on a phone), insert
it from the CLI with the trigger skipped for that session only:
`begin; set local session_replication_role = replica; insert ...; commit;`. This disables
triggers for that one transaction only; every other client's inserts keep notifying normally. It
also skips `on_sighting_insert_set_observer_tier`, so set `observer_tier` explicitly. Confirm
`count(*) from net.http_request_queue` and `net._http_response` are unchanged afterward. Place it
outside the Kenai banner area too: a row inside it can change `get_kenai_presence_state` for
everyone even with no push. Compare the phase before and after. Item 119 did exactly this: 0
queued, 0 sent, banner unchanged. Delete the rows afterward by exact id, and only rows you
created.

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

- **Migration applied and verified** (item 119 — delete my last sighting):
  `supabase/migrations/20261002000000_add_delete_my_last_sighting.sql` — applied by Claude on the
  user's explicit go-ahead (it touches `sightings`). Adds `delete_my_last_sighting(p_subscriber_id,
  p_sighting_id)`, a SECURITY DEFINER hard delete. It uses the same ownership, "single most recent"
  and `sighting_edit_window()` rules as `edit_my_last_sighting`, and those functions are
  unchanged. The one deliberate difference from edit is the extra `p_sighting_id`. Without it, a
  report the offline queue syncs mid-confirm would be the row deleted instead, irreversibly. It is
  also what makes "not the newest" a refusal at all. It is the ONLY client delete path: anon and
  authenticated hold a table DELETE grant, but there is no delete RLS policy, so a direct delete
  matches 0 rows. No FKs reference `sightings` and no DELETE trigger exists (both checked live).
  A delete can't recall alerts already sent. It also doesn't remove the photo from the public
  `sighting-photos` bucket, since there is no storage DELETE policy. Client: DELETE sits directly
  under EDIT on the same one row (map popup and list), behind a `confirm()`, and stays hidden
  until the RPC is detected (`probeSightingDeleteRpc`, db.js).
  Verified with a real button tap and dialog on the Stylus against test rows inserted with triggers
  skipped (see "A REAL INSERT FROM A TEST IS A REAL NOTIFICATION"). Deleted: own newest row, then
  the next row once it became newest. Refused: own older row, another device's row, own row
  requested by another device, own row outside the window (edit refuses it too).
  **Edit race: fixed by item 120 (below).** `edit_my_last_sighting` took no row id, so a newer
  report syncing while someone was editing got the older one's pre-filled values written onto it
  (its comment wrongly claimed it "matched zero rows"; that comment is gone).
- **Migration applied and verified** (item 120 — edit/delete pinned to the row's id, Oct 8, 2026):
  `supabase/migrations/20261008000000_add_sighting_id_guard_to_edit_and_delete.sql` — applied by
  the user via `tools\apply-latest-migration.ps1` (it touches `sightings`). Adds
  `edit_my_sighting(p_subscriber_id, p_sighting_id, p_updates)` and
  `delete_my_sighting(p_subscriber_id, p_sighting_id)`, both returning `{"status": ...}`: `ok`,
  `superseded` (a newer one of the caller's exists), `expired` (outside `sighting_edit_window()`),
  `not_found` (no such row, someone else's, or a null argument -- deliberately the same), and
  `no_changes` (edit, empty patch; `edited_at` not stamped). The write is still one statement
  with ownership, window and "still the newest" in its WHERE; only when it matches nothing does
  `classify_my_sighting_refusal` (EXECUTE for postgres/service_role only) work out why. "Newest"
  is `created_at desc nulls last, id desc` everywhere, same as `get_my_editable_sighting`
  (unchanged). New names, not overloads: `delete_my_last_sighting` already had the
  `(p_subscriber_id, p_sighting_id)` signature. The migration revokes Postgres's default PUBLIC
  EXECUTE on the new pair (the first dry run showed it), matching the old pair.
  **Old names are now wrappers, kept for older cached PWA copies**: `edit_my_last_sighting` looks
  up the caller's newest row itself and passes that id, so it behaves exactly as before, race
  included (an old client can't send an id; only the new client is protected).
  `delete_my_last_sighting` returns `delete_my_sighting(...).status = 'ok'`. Verified twice with
  synthetic rows in rolled-back transactions under `session_replication_role = replica` (a dry
  run with the migration body prepended, then against the live functions): 16 cases, identical
  results, 0 alerts queued, before/after snapshots identical. Client: `editMySighting`/
  `deleteMySighting` (db.js) return the status, falling back to the old RPCs on PGRST202; Save
  passes `editingSighting.id`, Delete passes `sighting.id`; refusals show
  `SIGHTING_CHANGE_REFUSAL_MESSAGES` and refresh so EDIT/DELETE move or disappear.
- **Migration applied and verified** (Oct 4, 2026 — idempotent tier-code redemption):
  `supabase/migrations/20261004000000_make_tier_code_redeem_idempotent.sql` — applied by the user
  via `tools\apply-latest-migration.ps1` (it touches `tier_roster`). Why: a tier-2 code committed
  on its FIRST call (attempt timestamp == `claimed_at`), the phone never showed success, and all 8
  retries hit `is_used = true` and were told "That code isn't valid." Adds
  `redeem_tier_code_detailed(p_code, p_subscriber_id) → jsonb` with a `status` of `success`
  (including a retry by the subscriber that already holds the code, `already_held: true`),
  `invalid`, `already_claimed` (held by another device) or `device_has_other_code`
  (`tier_roster_claimed_subscriber_id_uidx` allows one code per subscriber — previously swallowed
  as null by a `unique_violation` handler). Every outcome is a RETURN, never a RAISE, so the
  attempt row is never rolled back out of the 12/hour rate limit. A revoked code
  (`claimed_subscriber_id` null, `is_used` still true) stays `invalid`. `already_claimed` reveals
  that a used code exists; accepted by the user as a deliberate step back from 20260905010000's
  anti-enumeration design. `redeem_tier_code` keeps its signature and smallint return as a thin
  wrapper (success → tier, everything else → null). Verified with synthetic `ZZVF-` rows only, in
  a rolled-back transaction with `session_replication_role = replica`, calling as `anon`; the real
  claimed row was hash-identical before and after (ignoring `code`). Six cases passed:
  1. Same-device retry via the new RPC → `success`, tier 2, `already_held: true`.
  2. Same-device retry via the wrapper → `2`.
  3. Another device → `already_claimed` (wrapper `NULL`).
  4. A device already holding another code → `device_has_other_code` (wrapper `NULL`; the target
     code stayed unclaimed).
  5. A revoked code → `invalid`.
  6. A code that doesn't exist → `invalid`.
  Client (`db.js`'s `redeemTierCode`, `tier-code.js`'s `submitTierCode`) shows a distinct message
  per status; a request with no server answer (no PostgREST error code) is NETWORK, never
  "invalid"; the Enter key can no longer start a second call while one is in flight.
- **Migration LIVE** (item 101 bug fix) -- **correction, Oct 8, 2026**: this entry used to say
  "written, not applied". Checked live: `list_tier_codes`, `list_tier_admins` and
  `list_admin_actions` all carry the `email::text` cast, so the fix below is applied (when and by
  whom isn't recorded; any further change to these functions is still Hard-rule territory).
  Original description: `supabase/migrations/20260929010000_fix_email_type_mismatch_in_tier_admin_rpcs.sql`.
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
- **Migration LIVE** (item 97b) -- **correction, Oct 7, 2026**: this entry used to say "written,
  not applied". The linked project has `get_kenai_red_qualifying_sightings(p_now_epoch_ms bigint
  default null)` live, and `/webapp/status/` already calls it for the Kenai RED map. It returns
  `id, observed_at_epoch_ms, whale_lat, whale_lng, count_*, travel_bearing_degrees` ordered newest
  observed first, but NO `created_at`. Original description, still accurate:
  `supabase/migrations/20260927000000_add_kenai_red_qualifying_sightings_rpc.sql` adds
  `get_kenai_red_qualifying_sightings`, a new read-only RPC returning the actual RED-qualifying
  sighting row(s) (position, counts, `travel_bearing_degrees`) for `/webapp/status/`'s RED-state
  compact map — `get_kenai_presence_state` itself only ever returns the newest qualifying
  timestamp, never the rows. Its body is the RED-branch logic from `get_kenai_presence_state`
  copied verbatim (same cycle-low/departure-report ratchet/tier/confirmed/banner-area check) so
  the map can never show a sighting that isn't actually why the banner is RED. Any CHANGE to it
  is still Hard-rule territory (it copies `get_kenai_presence_state`'s logic and reads sightings):
  show the user the diff and get an explicit go-ahead first.
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
  gets a plain compass point and no landmark (no lookup exists for them). **Kenai place and
  direction wording (Oct 7, 2026):** `kenai-landmarks.js` coordinates come from named
  OpenStreetMap elements, checked Oct 7, 2026 (the original "general public knowledge" list had
  every place 2.5-7.8 km off): the river mouth (centerline start), the Kenai city boat launch,
  the Warren Ames Bridge, Cunningham Park, the Eagle Rock boat launch; element IDs are in that
  file's header. "near X" is dropped beyond 1.5 km. Past the mouth (`isKenaiInletPosition`: the
  nearest centerline point is the mouth itself and the sighting is more than 300 m beyond it) the
  line reads "in Cook Inlet off the river mouth" with "heading toward the river mouth" or
  "heading out into the inlet", never up/downriver. In the river, a bearing within 22.5 degrees of
  straight across reads "heading across the river". No bearing stays "direction not reported".
  **The RED map draws at
  most 3 sightings (Oct 7, 2026, display only)**: `RED_MAP_MAX_SHOWN` in status.js, chosen
  client-side from the full qualifying list by `sortByRecency` (newest observed, ties broken by
  `created_at`). The Kenai RPC returns no `created_at`, so `fillCreatedAtForTies` reads it from
  `sightings` for just the tied rows, only when a tie could change the top three; on failure the
  server's order stands. Newest drawn last with the highest `zIndexOffset`, so it sits on top even
  at a shared spot. The older two dots and arrows are drawn at 55% opacity, labels unfaded. The
  map fits only the shown three plus the river mouth (or zone outline). "Last seen…" describes
  the newest. When more qualify, a line under the map reads "Showing the 3 most recent of N
  reports"; nothing is shown at 3 or fewer. **"Open the full app" link (Oct 7, 2026):** fixed to the
  bottom in portrait and on tablets, but in the normal page flow at the end of the content under
  `@media (max-height: 500px)` (phones in landscape), where a fixed link covered the map and
  "Last seen" line. Body is `height: auto` (min-height 100vh) so its 76px bottom padding survives
  a long page; with `height: 100%` the open NOAA panel's end stayed under the link. Currently only `kenai` is
  `is_banner_watched` live, so `?zone=` for anything else renders an honest "UNKNOWN ZONE" state
  rather than silently substituting Kenai's data — this is expected until/unless another zone gets
  flagged, not a bug. The Share page's STATUS PAGE chip mode has its own zone picker (only shown
  when more than one zone is watched), driven by the same `get_watched_zone_statuses` list.
  **Item 121 (revised, Oct 7, 2026):** STATUS PAGE mode also shows an OPEN STATUS PAGE button and
  a PRINTABLE SIGN section linking `print/status-sign.html?zone=` and, for Kenai only,
  `print/status-sign.pdf` (the only PDF; it's the Kenai sign). These, and the mode's URL text,
  QR, Copy and Share, use the canonical `https://gmessy30.github.io/BELUGAS/webapp/` base
  (`CANONICAL_WEBAPP_BASE_URL`, share.js), never `window.location`, so a localhost session still
  hands out working links. APP mode still derives its URL from `window.location`. One QR per
  screen still holds: the sign page draws its own, on its own page.
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
- **News Feed dead-link handling (item 93c, BUILT Oct 8, 2026 — reader-reported, admins
  decide)**: `supabase/migrations/20261009000000_add_article_link_reports.sql`, applied by the user
  and verified live (synthetic rows in rolled-back transactions, replica role, dry run then live:
  identical results, 0 alerts queued, the 33 real articles hash-identical). Readers tap "Report
  broken link" (`report_broken_article_link`); reports land in `article_link_reports` (RLS on, no
  policies, no anon/authenticated grants -- RPCs only) and ONLY flag the article for the admin
  page's REPORTED LINKS section (`list_reported_article_links`, ordered by count). They never hide
  anything. Only an admin's `set_article_link_status` (`mark_broken` with an optional https
  `archive_url`, `mark_ok`, `fix_url`) changes what readers see: a broken card reads "This link
  may no longer work." plus "View archived copy →". Both admin RPCs use the same gate as
  `set_article_status` (`is_tier_admin()`; articles have no zone, so item 101's zone scoping
  doesn't apply), are authenticated-only (anon and PUBLIC revoked), and write `admin_actions`.
  - **Why not the original nightly server check**: 12 of the 23 published links are Google
    Scholar searches, and Scholar blocks automated fetches, so a server check would flag them
    every night. It would also mean fetching user-submitted URLs from our servers (SSRF risk) and,
    via pg_net, sharing `net.http_request_queue` with the sighting alerts.
  - **The device id in a report is NOT validated**: it's the client's own random
    `belugas_subscriber_id`, and no table lists real devices -- one person can mint many. Hence
    reports only flag, plus two limits: 10 reports per device per hour (`rate_limited`) and 50
    stored reports per article (past 50 the RPC still answers `recorded` but stores nothing).
  - **URL rule**: `articles_source_url_web` CHECK (`^https?://` plus a host) is `NOT VALID`, because
    one REJECTED test row from Sept 14 ("edge case not-a-url", source_url `not-a-url`, id
    `f0afb333…`) fails it and real articles weren't changed. Every insert and update is still
    checked, so that row can't be re-published without a real URL. The INSERT policy also now
    requires `link_status = 'ok'`, `link_status_at` and `archive_url` null, so a submitter can't
    pre-set a "View archived copy" link. Client-side, `isWebUrl` (db.js) gates every feed href and
    the suggest form ("Please enter a full web address starting with https://"); the admin page's
    links are gated the same way.
  - Not built from the old spec: DOI links, Wayback snapshots taken automatically at publish.
  - Left alone on purpose: one published article links to `copilot.microsoft.com` (looks like a
    private AI-chat share rather than an article) -- an admin call, not a code change.
- **Native-side parity item (item 93c, web-only so far)**: native HAS a News Feed
  (`NewsFeedScreen.kt`), but no "Report broken link" button, no broken-link notice or archived
  copy, and no URL check on its suggest form -- a native suggestion that isn't a full http(s)
  address is now refused by the database with a raw error. Port all three.
- **Native-side asset swap still pending (final artwork)**: Luna Montgomery's FINAL breaching-beluga
  artwork for the whale-count buttons is in place in `webapp/img/` only --
  `Whitebreaching.png`/`Greybreaching.png`/`Calfbreaching.png`/`Unknownbreaching.png`, all four
  confirmed 280x120 8-bit RGBA with real alpha (fully transparent corners, 24-57% fully
  transparent pixels plus antialiased semi-transparent edges), not merely an alpha channel that
  happens to be opaque. The four same-named files under
  `shared/src/commonMain/composeResources/drawable/` are STILL the old placeholders (confirmed by
  hash -- all four differ from the webapp copies) and need the identical swap during the native
  parity pass, alongside every other pending item below.
- **Camera preview hides part of the saved frame (Oct 4, 2026, open, not built)**: the camera
  now requests 1920x1080 with ideal values only (`getRearCameraStream`, submit-view.js) and saves
  the FULL frame, capped at 1920 px on the long edge; no reticle crop, deliberately. But
  `#camera-preview` is `object-fit: cover`, so the preview trims what the photo keeps. Measured
  on the Stylus (`ZY22JSTXPW`): portrait hides 10.9% (side edges), landscape hides 32.6% (top and
  bottom), because Chrome's address bar leaves a wide, short 918x348 viewport. A possible later
  change, not built: a full-frame preview in landscape (`object-fit: contain`, with black bars)
  or an on-screen note. **The iPhone stream size is still unmeasured**: the `?debug=1` overlay on
  the logging screen shows `camera stream: WxH · photo: WxH, N KB` after a capture, so an iPhone
  tester can read it and back out without submitting.
- **Native-side parity item (Oct 4, 2026 tier-code redemption, web-only so far)**: installed
  native builds already get same-device retry success, through the `redeem_tier_code` wrapper
  (see 20261004000000 above). They still show "invalid" for already-claimed, a device that already
  has a code, and network failures: `SupabaseApi.redeemTierCode` collapses everything but
  `rate_limited` to `TierRedeemResult.Invalid`. The wrapper can't carry those cases as a sentinel
  like `-1`, because `SupabaseClient.kt:909` treats any integer as `Success`. Port by calling
  `redeem_tier_code_detailed` and adding `AlreadyClaimed`/`DeviceHasOtherCode`/`Network` cases
  (with `TierClaimScreen.kt` messages matching `tier-code.js`), alongside the other pending items.
- **Native-side parity item (item 120, Oct 8, 2026)**: native calls none of the sighting edit or
  delete functions today -- editing and deleting your own last report exist only in the PWA. If
  native ever adds them, call `edit_my_sighting`/`delete_my_sighting` with the row's id and show
  the same plain messages for `superseded`/`expired`/`not_found`, never the old id-less wrapper.
- **Native-side parity item (Oct 8, 2026, web-only so far) -- grey instead of a calm blue with
  nothing behind it**: display only; the status VALUE (and so alerts, transitions, and how
  RED/YELLOW/BLUE are decided) is unchanged, and only BLUE is ever overridden. presence.js's
  `kenaiNoDataReason`/`zoneNoDataReason` pick the case, `NO_DATA_DISPLAY_COLOR` (#616161) and
  `NO_DATA_NOTES` the look. "tide": Kenai in season with no tide prediction (TIDE DATA
  UNAVAILABLE), which otherwise stays blue while fresh and up to 24 h after the last fetch
  (`KENAI_EXEMPT_BLUE_CEILING_MS`); off-season blue is not affected. "reports": a watched
  non-Kenai zone with no reports in `get_watched_zone_statuses`' window (every non-Kenai blue,
  since that RPC only looks back over the yellow window), labelled NO REPORTS YET. Used by the
  main app's banner (`presence-banner.js`, card `noDataReason`) and the status page, which also
  shows the plain-words note. Same pass on the status page: `?zone=` is trimmed and lowercased,
  and an unwatched or unknown zone reads "This area isn't being monitored by BELUGAS" / "Don't take
  this page as a sign the water is clear." (deliberately naming no monitored zones). The Sightings
  Map's zone SHADING still uses the plain status colour. **Native's `PresenceBanner.kt` needs the
  same grey for tide-unavailable and no-reports.**
- **Printable status sign: `LIVE_SIGN_ZONES` in `print/status-sign.html` must be updated when a zone
  goes live** (`zones.is_banner_watched`). Any other zone's sign is stamped "Not monitored yet.
  Don't post this sign." over the QR (Oct 8, 2026). Hardcoded on purpose: the sign page makes no
  network calls.
- **Native-side parity item (Oct 7, 2026, web-only so far) -- Alaska time everywhere**: every
  sighting time and day boundary in the PWA is America/Anchorage, whatever the phone's timezone
  (`39dc205` did the map pin caption and popup; this pass did the rest). Shared helpers live in
  map-view.js beside `anchorageDateParts`/`anchorageMidnightEpochMs`: `anchorageDateTimeParts`,
  `anchorageWallTimeToEpochMs` and `anchorageEndOfDayEpochMs` (end of day = next Alaska midnight
  minus 1 ms, never midnight + 24h: Nov 1, 2026 is 25 hours, Mar 8 is 23). Covered: Sightings List
  rows, the playback time label, Export Data's From/To (were UTC days, so an Alaska evening
  sighting landed on the next day), the map's custom range end and season ends, the banner's
  tide-gate times and the status page's "Last updated" (`formatTime12Hour`), and the Report
  Manually / Edit date-time picker, which now shows AND reads an Alaska wall-clock time (a
  typed time in the repeated 1-2 AM hour on Nov 1 resolves to the first, AKDT one). Verified
  identical with the browser in UTC, America/Los_Angeles and America/Anchorage. Not changed:
  CSV/GeoJSON `observed_at` stays ISO 8601 UTC; admin-page dates stay in the admin's own
  timezone. **Native needs the same rule** for its list (`OfflineSightingsList`), playback label
  and Export date range, plus its manual-logging date picker.
- **Native-side parity item (Oct 6, 2026, web-only so far) -- pin captions on a one-day range**:
  when the Sightings Map's active date range is exactly one Anchorage calendar day, every pin's
  caption shows the observed TIME instead of the date ("4 Belugas · 2:24 PM", America/Anchorage
  whatever the phone's timezone). The rule is "the REQUESTED window starts and ends on the same
  calendar day", not a list of buttons: TODAY, YESTERDAY, or a CUSTOM from/to on one date. It's
  judged before the data clamp, so ALL TIME (no bounds of its own) and an open-ended or multi-day
  CUSTOM range keep the date even when their data happens to fall on one day.
  `playbackRangeIsSingleDay`, set only in `recomputePlaybackRange`, read by `sightingCaptionText`
  (map-view.js). The popup keeps its full date and time; cluster badges are unchanged. Native's
  `SightingsMapScreen.kt` caption (`captionText`, ~line 413/422) has neither. **Known native slip
  to fix in the same pass**: the non-playback caption at `SightingsMapScreen.kt:413` hard-codes
  `"${s.total} Belugas"`, so one whale reads "1 Belugas"; the playback branch at :422 already
  pluralizes correctly, and the PWA always has.
- **Native-side parity item (Oct 6, 2026, web-only so far) -- the Sightings Map remembers its
  date range**: `loadPlaybackSettings` (map-view.js) restores the saved quick range on page load.
  Only `RESTORABLE_QUICK_RANGES` (TODAY, YESTERDAY, THIS_SEASON, ALL_TIME) are restored, stored as
  the KEY only and resolved fresh against the current Anchorage date, so a TODAY saved last week
  means today. A saved CUSTOM (its dates go stale), a first launch, and a missing, corrupt or
  unknown value all start at ALL_TIME. There is no 7-day or 30-day quick range in this app. Two
  additions:
  - `rerunRangeIfDayChanged` re-resolves the range on `visibilitychange` when the Anchorage date
    has changed, because nothing else does on resume (sightings refresh only on load,
    pull-to-refresh and submit). Without it a TODAY held in memory overnight keeps filtering to
    yesterday while the pill says TODAY.
  - `updateEmptyRangeHint` shows one line under the pill ("No sightings today. Tap to see other
    dates.") when the date range alone hides every sighting; it opens the playback panel and is
    hidden while the panel is open and on ALL TIME.
  Native's `SightingsMapScreen.kt` keeps the range in `remember` only (lost with the screen) and
  has neither the pill, the restore, nor the hint.
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
  item replaced the FAB's `⏱` glyph -- which renders on Android Chrome as a circle-with-a-stem,
  reading as a power button -- with an inline SVG clock face. `SightingsMapScreen.kt` has neither
  the label nor the icon change. **Reversed Oct 6, 2026:** item 113 also stopped RESTORING the
  quick range on a fresh load, because a persisted TODAY opened the app to a map that had silently
  dropped every earlier sighting. That reason no longer holds: the same item's always-visible
  amber pill makes a restored range visible, not silent. So `loadPlaybackSettings` restores the
  saved quick range again (see the Oct 6 parity item below for the rules).
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
- Self-service re-verification by email: not built, deferred (no date set).
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
- Real-device testing still needed (corrected Oct 8, 2026 -- some of this has since been exercised):
  time-lapse scrubbing/playing and the date-range chips were driven on the Stylus (Oct 6), and the
  camera stream was measured there in portrait and landscape (Oct 4). Still not exercised on a real
  device: cluster tap/expand on the Sightings Map, the back-GESTURE nav stack (tests called
  `navigateBack()` rather than performing a real gesture), fullscreen on touch devices, and the
  Android/iOS install prompt. Nothing has been tested on an iPhone.
- **Still open as of Oct 8, 2026 (summary; details in the entries above)**:
  - **The native parity pass**: every "Native-side parity item" and "Native-side BUG" entry in
    this list, done together, then a rebuild for Android and iOS. Includes the native News Feed
    suggest form needing the same full http(s) address check (item 93c's parity entry).
  - **iPhone camera stream size**: still unmeasured (see the camera-preview entry above for the
    `?debug=1` way to read it without submitting).
  - **The `copilot.microsoft.com` News Feed article**: one published article links to what looks
    like a private AI-chat share rather than an article. Left alone on purpose; it's an admin call
    (unpublish, or Fix URL from REPORTED LINKS), not a code change.
  - Web Push config (above), idempotent sighting inserts (above), self-service re-verification
    (above).
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
