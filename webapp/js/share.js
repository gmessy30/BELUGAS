// Item 43: Share page -- web-only, no native equivalent (native is distributed via the app
// stores, which already have their own share/listing mechanics). Three things: the plain URL as
// selectable/copyable text, a Web Share API trigger where the platform supports it, and a QR code
// of that same URL with the BELUGAS icon overlaid in its center.
//
// Item 97c: the page shows exactly ONE link at a time (the main app, or the standalone status
// page from item 97) -- an APP / STATUS PAGE chip toggle selects which, and the URL text/QR/COPY/
// SHARE all follow that selection. Replaced item 97's original design of two full sections (one
// per link, each with its own QR) after the fact -- see this file's own git history for that
// version if it's ever needed for reference.
//
// STATUS PAGE mode additionally shows a zone picker -- one zone's QR at a time, same rule as the
// APP/STATUS PAGE chips themselves -- populated from get_watched_zone_statuses (already
// anon+authenticated-granted, no new RPC needed), which server-side filters to `is_banner_watched`
// zones -- so this can never offer a zone the status page itself wouldn't recognize (see that
// page's own "unknown zone" fallback, status.js).

let shareSelectedKind = "app"; // "app" | "status"
let shareSelectedZoneSlug = "kenai";
let shareZones = null; // [{slug, name}], lazily loaded once, cached forever

// location.origin + location.pathname only -- strips any query string (e.g. ?debug=1, item 39)
// and hash automatically, and stays correct wherever this is actually deployed rather than a
// hardcoded domain.
function getShareableAppUrl() {
  return window.location.origin + window.location.pathname;
}

// Item 97c: zoneSlug becomes the status page's own ?zone= param -- omitted for "kenai" (the status
// page's own default when the param is missing), included for anything else, so a Kenai link
// stays the same clean URL it always was. (Item 97 derived this URL from window.location as a
// sibling "status/" directory; item 121 replaced that, see below.)
//
// Item 121 (revised): the status page and sign links are the canonical DEPLOYED addresses, never
// derived from window.location -- a session opened from localhost (or any other origin) must still
// hand out a link and QR that work on someone else's phone. APP mode is unchanged.
const CANONICAL_WEBAPP_BASE_URL = "https://gmessy30.github.io/BELUGAS/webapp/";

function zoneQuery(zoneSlug) {
  return zoneSlug && zoneSlug !== "kenai" ? `?zone=${encodeURIComponent(zoneSlug)}` : "";
}

function getShareableStatusPageUrl(zoneSlug) {
  return `${CANONICAL_WEBAPP_BASE_URL}status/${zoneQuery(zoneSlug)}`;
}

// The printable sign takes the same ?zone= convention as the status page. The PDF is the Kenai
// sign only (webapp/print/status-sign.pdf), so it's offered for Kenai and null otherwise.
function getStatusSignPageUrl(zoneSlug) {
  return `${CANONICAL_WEBAPP_BASE_URL}print/status-sign.html${zoneQuery(zoneSlug)}`;
}

function getStatusSignPdfUrl(zoneSlug) {
  return (zoneSlug || "kenai") === "kenai" ? `${CANONICAL_WEBAPP_BASE_URL}print/status-sign.pdf` : null;
}

function updateStatusPageLinks() {
  const isStatus = shareSelectedKind === "status";
  document.getElementById("share-open-status-btn").hidden = !isStatus;
  document.getElementById("share-sign-section").hidden = !isStatus;
  if (!isStatus) return;
  document.getElementById("share-sign-link").href = getStatusSignPageUrl(shareSelectedZoneSlug);
  const pdfUrl = getStatusSignPdfUrl(shareSelectedZoneSlug);
  document.getElementById("share-sign-pdf-wrap").hidden = !pdfUrl;
  if (pdfUrl) document.getElementById("share-sign-pdf-link").href = pdfUrl;
}

function getShareableUrlForKind(kind) {
  return kind === "status" ? getShareableStatusPageUrl(shareSelectedZoneSlug) : getShareableAppUrl();
}

function initSharePage() {
  document.getElementById("share-back-btn").addEventListener("click", () => {
    navigateBack();
  });

  document.getElementById("share-chip-app").addEventListener("click", () => setShareSelectedKind("app"));
  document.getElementById("share-chip-status").addEventListener("click", () => setShareSelectedKind("status"));

  document.getElementById("share-copy-btn").addEventListener("click", handleCopyShareUrl);
  document.getElementById("share-open-status-btn").addEventListener("click", () => {
    window.open(getShareableStatusPageUrl(shareSelectedZoneSlug), "_blank", "noopener");
  });

  // Hidden entirely (not just disabled) where navigator.share doesn't exist -- most desktop
  // browsers -- rather than a disabled button with no visible explanation of why. The copy
  // button above already covers that case.
  if (navigator.share) {
    document.getElementById("share-native-section").hidden = false;
    document.getElementById("share-native-divider").hidden = false;
    document.getElementById("share-native-btn").addEventListener("click", handleNativeShare);
  }
}

async function setShareSelectedKind(kind) {
  shareSelectedKind = kind;
  document.getElementById("share-chip-app").classList.toggle("active", kind === "app");
  document.getElementById("share-chip-status").classList.toggle("active", kind === "status");

  const zoneRow = document.getElementById("share-zone-row");
  if (kind === "status") {
    await ensureShareZonesLoaded();
    // Oct 13, 2026: re-checked on every entry, not just the first load -- a zone can now arrive
    // from a link (openShareFromLaunchLink). One that isn't monitored falls back to Kenai rather
    // than a QR for a page that would only say "isn't being monitored". The chip highlight is
    // re-synced too: it used to keep whichever zone was picked on an earlier visit.
    if (!shareZones.some((z) => z.slug === shareSelectedZoneSlug)) shareSelectedZoneSlug = "kenai";
    zoneRow.querySelectorAll(".news-filter-chip").forEach((btn, i) => {
      btn.classList.toggle("active", shareZones[i].slug === shareSelectedZoneSlug);
    });
    zoneRow.hidden = shareZones.length <= 1; // nothing to PICK with only one (or zero) watched zone
  } else {
    zoneRow.hidden = true;
  }

  document.getElementById("share-url-text").textContent = getShareableUrlForKind(kind);
  updateStatusPageLinks();
  renderShareQrCode();
}

async function ensureShareZonesLoaded() {
  if (shareZones) return;
  // p_lookback_ms doesn't affect which zones come back (that's purely the server-side
  // is_banner_watched filter) -- only DEFAULT_YELLOW_WINDOW_MS's own last_verified/last_any
  // fields, unused here, so any value would do; reused anyway for consistency with every other
  // caller of this RPC (presence-state.js's pollWatchedZoneStatuses, status.js's own generic-zone
  // path).
  const { data, error } = await supabaseClient.rpc("get_watched_zone_statuses", { p_lookback_ms: DEFAULT_YELLOW_WINDOW_MS });
  if (error) {
    console.error("SHARE_ZONES_FETCH_ERROR", error);
    shareZones = [{ slug: "kenai", name: "Kenai" }]; // always at least the known default, never a broken/empty picker
    return;
  }
  shareZones = data.map((row) => ({ slug: row.zone_slug, name: row.zone_name }));
  if (!shareZones.some((z) => z.slug === "kenai")) {
    shareZones.unshift({ slug: "kenai", name: "Kenai" });
  }
  if (!shareZones.some((z) => z.slug === shareSelectedZoneSlug)) {
    shareSelectedZoneSlug = shareZones[0].slug;
  }

  const row = document.getElementById("share-zone-row");
  row.innerHTML = "";
  shareZones.forEach((zone) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "news-filter-chip" + (zone.slug === shareSelectedZoneSlug ? " active" : "");
    btn.textContent = zone.name.toUpperCase();
    btn.addEventListener("click", () => setShareSelectedZone(zone.slug));
    row.appendChild(btn);
  });
}

function setShareSelectedZone(zoneSlug) {
  shareSelectedZoneSlug = zoneSlug;
  document.getElementById("share-zone-row").querySelectorAll(".news-filter-chip").forEach((btn, i) => {
    btn.classList.toggle("active", shareZones[i].slug === zoneSlug);
  });
  document.getElementById("share-url-text").textContent = getShareableUrlForKind("status");
  updateStatusPageLinks();
  renderShareQrCode();
}

async function handleCopyShareUrl() {
  const btn = document.getElementById("share-copy-btn");
  const original = btn.textContent;
  try {
    await navigator.clipboard.writeText(getShareableUrlForKind(shareSelectedKind));
    btn.textContent = "✓ Copied";
  } catch (e) {
    console.error("SHARE_CLIPBOARD_COPY_ERROR", e);
    btn.textContent = "Couldn't copy";
  }
  setTimeout(() => { btn.textContent = original; }, 1500);
}

async function handleNativeShare() {
  try {
    await navigator.share({ title: "BELUGAS", url: getShareableUrlForKind(shareSelectedKind) });
  } catch (e) {
    // AbortError fires when the user just closes the share sheet without picking anything --
    // not a real failure, nothing to log or show for it.
    if (e.name !== "AbortError") {
      console.error("SHARE_NATIVE_ERROR", e);
    }
  }
}

// Item 97c: unlike item 97's original once-ever render, this now redraws on every chip/zone
// switch -- qrcodejs' own QRCode constructor doesn't clear a container it's already drawn into
// (calling it again would just stack another canvas on top), so the container is emptied first
// each time.
//
// Error-correction level H (~30% of the code's data can be damaged/obscured and still decode) is
// what makes the center icon overlay in the HTML (.share-qr-icon-wrap, 46px = ~19% of the
// code's own 240px width since Oct 13, 2026; was 54px, ~22%) safe at all -- the library's default level (M, ~15%) would risk the code
// failing to scan once anything is drawn over it. The icon is layered on top via plain CSS
// positioning, not composited into the QR's own pixel data (qrcodejs has no API for that) -- a
// scanner reading the rendered page sees the identical result either way, since it can't tell the
// difference between "this pixel was never drawn" and "this pixel is covered by another element."
//
// Oct 13, 2026: the white margin around the code (#share-qr-container's padding) is set here to
// the standard 4-module quiet zone, measured from the code actually drawn -- a module's size
// depends on the URL's length, so a fixed pixel padding can't be right for every link. The CSS
// padding is only the fallback if the module count can't be read.
const SHARE_QR_SIZE_PX = 240;
const SHARE_QR_QUIET_ZONE_MODULES = 4;

function renderShareQrCode() {
  const container = document.getElementById("share-qr-code");
  container.innerHTML = "";
  const qr = new QRCode(container, {
    text: getShareableUrlForKind(shareSelectedKind),
    width: SHARE_QR_SIZE_PX,
    height: SHARE_QR_SIZE_PX,
    colorDark: "#000000",
    colorLight: "#ffffff",
    correctLevel: QRCode.CorrectLevel.H
  });
  const moduleCount = qr._oQRCode && qr._oQRCode.getModuleCount ? qr._oQRCode.getModuleCount() : 0;
  if (moduleCount > 0) {
    const quietZonePx = Math.ceil((SHARE_QR_SIZE_PX / moduleCount) * SHARE_QR_QUIET_ZONE_MODULES);
    document.getElementById("share-qr-container").style.padding = `${quietZonePx}px`;
  }
}

// Called from the main menu's "Share" item and from the About page's own link (about.js), with
// no argument: always resets to APP -- same "fresh entry resets to the default" convention item
// 93's search page established for its own chips (openNewsFeedPage always starts on the NEWS
// chip). Oct 13, 2026: openShareFromLaunchLink passes { kind: "status", zoneSlug } instead.
function openSharePage(options) {
  shareSelectedZoneSlug = (options && options.zoneSlug) || "kenai";
  setShareSelectedKind((options && options.kind) || "app");
  document.getElementById("share-page").hidden = false;
}

// Oct 13, 2026: the status page's "Share this page" button links to the app as
// ?share=status&from=status[&zone=<slug>]. Called once at startup in place of resolveLaunchScreen
// (app.js); returns false, doing nothing, when there's no such link. The parameters are removed
// from the address bar first (anything else, e.g. ?debug=1, is kept), so a reload doesn't reopen
// the Share page and the base history entry initNavStack recorded stays the plain app URL. The
// zone is trimmed and lowercased the same way status.js reads it.
//
// Where Back goes depends on `from`, and ONLY the exact value "status" counts:
// - from=status: Share is the only layer, and Back (button or system gesture -- both arrive as
//   the same popstate, see nav-stack.js) returns to the status page. The origin lives only in
//   this one layer's onPop closure: not a global, not sessionStorage, so a reload forgets it, and
//   a later Share opened from the Menu (openSharePage via main-menu.js) never sees it.
// - anything else (no `from`, a bogus value, or an older cached status page that sends only
//   ?share=status): Share opens over the Menu, exactly as the menu's own Share item would leave
//   things, so Back lands on the Menu whatever the launch-screen preference -- in particular the
//   camera never starts underneath.
function openShareFromLaunchLink() {
  const params = new URLSearchParams(window.location.search);
  if (params.get("share") !== "status") return false;
  const zoneSlug = (params.get("zone") || "").trim().toLowerCase() || "kenai";
  const fromStatusPage = params.get("from") === "status";
  params.delete("share");
  params.delete("zone");
  params.delete("from");
  const query = params.toString();
  history.replaceState(history.state, "", window.location.pathname + (query ? `?${query}` : "") + window.location.hash);

  if (fromStatusPage) {
    // Read now, before anything else can change them: was this app load a same-tab navigation
    // from our own status page? Then the history entry just behind this one IS that page.
    const statusPageUrl = new URL(`status/${zoneQuery(zoneSlug)}`, window.location.href).href;
    const statusPageIsPreviousEntry = history.length > 1 && isOwnStatusPageUrl(document.referrer);
    openSharePage({ kind: "status", zoneSlug });
    // The Share page is deliberately left showing while the browser leaves, so the map
    // underneath never flashes on the way out.
    pushNavLayer("share-page", () => returnToStatusPage(statusPageUrl, statusPageIsPreviousEntry));
    return true;
  }

  const menu = document.getElementById("main-menu");
  pushNavLayer("menu", () => { menu.hidden = true; });
  openSharePage({ kind: "status", zoneSlug });
  pushNavLayer("share-page", () => {
    document.getElementById("share-page").hidden = true;
    menu.hidden = false;
  });
  return true;
}

function isOwnStatusPageUrl(url) {
  try {
    const u = new URL(url);
    return u.origin === window.location.origin && u.pathname === new URL("status/", window.location.href).pathname;
  } catch (e) {
    return false;
  }
}

// Prefers stepping back to the status page's own history entry: that is the page the person was
// on, and it leaves no duplicate entry behind (a replace would put a second copy of the status
// page in history, and the next system back would land on the first copy -- a back that seems to
// do nothing). If there's no such entry (the link was opened in a new tab or window), or the
// browser hasn't left within 3 s, it replaces this entry with the status page URL for the same
// normalised zone instead. 3 s, not less: a step back to a status page that isn't in the
// back/forward cache has to fetch it, and pagehide only fires once that lands -- a short timer
// would cut across a traversal that was working on a slow connection. pagehide is what tells the two apart; the timer is frozen with the page
// if it goes into the back/forward cache, and finds `left` already true if it's ever restored.
function returnToStatusPage(statusPageUrl, statusPageIsPreviousEntry) {
  let left = false;
  window.addEventListener("pagehide", () => { left = true; }, { once: true });
  if (statusPageIsPreviousEntry) history.back();
  setTimeout(() => {
    if (!left) window.location.replace(statusPageUrl);
  }, statusPageIsPreviousEntry ? 3000 : 0);
}
