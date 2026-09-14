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

// Item 97: the standalone status page (webapp/status/) is always a sibling "status/" directory
// of wherever this page's own index.html actually is -- handles both the pathname ending in
// "/" (navigated to the directory) and ending in "index.html" (an explicit link/bookmark), same
// two shapes getShareableAppUrl() itself has to tolerate. Item 97c: zoneSlug becomes the page's
// own ?zone= param -- omitted for "kenai" (the status page's own default when the param is
// missing), included for anything else, so a Kenai link stays the same clean URL it always was.
function getShareableStatusPageUrl(zoneSlug) {
  const appUrl = getShareableAppUrl();
  const dir = appUrl.endsWith("index.html") ? appUrl.slice(0, -"index.html".length) : appUrl;
  const base = (dir.endsWith("/") ? dir : `${dir}/`) + "status/";
  return zoneSlug && zoneSlug !== "kenai" ? `${base}?zone=${encodeURIComponent(zoneSlug)}` : base;
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
    zoneRow.hidden = shareZones.length <= 1; // nothing to PICK with only one (or zero) watched zone
  } else {
    zoneRow.hidden = true;
  }

  document.getElementById("share-url-text").textContent = getShareableUrlForKind(kind);
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
// what makes the center icon overlay in the HTML (.share-qr-icon-wrap, sized to ~22% of the
// code's own width) safe at all -- the library's default level (M, ~15%) would risk the code
// failing to scan once anything is drawn over it. The icon is layered on top via plain CSS
// positioning, not composited into the QR's own pixel data (qrcodejs has no API for that) -- a
// scanner reading the rendered page sees the identical result either way, since it can't tell the
// difference between "this pixel was never drawn" and "this pixel is covered by another element."
function renderShareQrCode() {
  const container = document.getElementById("share-qr-code");
  container.innerHTML = "";
  new QRCode(container, {
    text: getShareableUrlForKind(shareSelectedKind),
    width: 240,
    height: 240,
    colorDark: "#000000",
    colorLight: "#ffffff",
    correctLevel: QRCode.CorrectLevel.H
  });
}

// Called from the main menu's "Share" item and from the About page's own link (about.js). Always
// resets to APP -- same "fresh entry resets to the default" convention item 93's search page
// established for its own chips (openNewsFeedPage always starts on the NEWS chip).
function openSharePage() {
  shareSelectedZoneSlug = "kenai";
  setShareSelectedKind("app");
  document.getElementById("share-page").hidden = false;
}
