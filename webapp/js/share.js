// Item 43: Share page -- web-only, no native equivalent (native is distributed via the app
// stores, which already have their own share/listing mechanics). Three things: the plain app URL
// as selectable/copyable text, a Web Share API trigger where the platform supports it, and a QR
// code of that same URL with the BELUGAS icon overlaid in its center.

let shareQrGenerated = false;

// location.origin + location.pathname only -- strips any query string (e.g. ?debug=1, item 39)
// and hash automatically, and stays correct wherever this is actually deployed rather than a
// hardcoded domain.
function getShareableAppUrl() {
  return window.location.origin + window.location.pathname;
}

function initSharePage() {
  document.getElementById("share-back-btn").addEventListener("click", () => {
    navigateBack();
  });

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

async function handleCopyShareUrl() {
  const btn = document.getElementById("share-copy-btn");
  const original = btn.textContent;
  try {
    await navigator.clipboard.writeText(getShareableAppUrl());
    btn.textContent = "✓ Copied";
  } catch (e) {
    console.error("SHARE_CLIPBOARD_COPY_ERROR", e);
    btn.textContent = "Couldn't copy";
  }
  setTimeout(() => { btn.textContent = original; }, 1500);
}

async function handleNativeShare() {
  try {
    await navigator.share({ title: "BELUGAS", url: getShareableAppUrl() });
  } catch (e) {
    // AbortError fires when the user just closes the share sheet without picking anything --
    // not a real failure, nothing to log or show for it.
    if (e.name !== "AbortError") {
      console.error("SHARE_NATIVE_ERROR", e);
    }
  }
}

// Rendered once (the URL never changes at runtime) rather than regenerated on every visit to
// this page -- qrcodejs' own QRCode constructor doesn't clear a container it's already drawn
// into, so calling it a second time would just draw another canvas on top of the first.
//
// Error-correction level H (~30% of the code's data can be damaged/obscured and still decode) is
// what makes the center icon overlay in the HTML (.share-qr-icon-wrap, sized to ~22% of the
// code's own width) safe at all -- the library's default level (M, ~15%) would risk the code
// failing to scan once anything is drawn over it. The icon is layered on top via plain CSS
// positioning, not composited into the QR's own pixel data (qrcodejs has no API for that) -- a
// scanner reading the rendered page sees the identical result either way, since it can't tell the
// difference between "this pixel was never drawn" and "this pixel is covered by another element."
function renderShareQrCodeIfNeeded() {
  if (shareQrGenerated) return;
  shareQrGenerated = true;

  new QRCode(document.getElementById("share-qr-code"), {
    text: getShareableAppUrl(),
    width: 240,
    height: 240,
    colorDark: "#000000",
    colorLight: "#ffffff",
    correctLevel: QRCode.CorrectLevel.H
  });
}

// Called from the main menu's "Share" item and from the About page's own link (about.js).
function openSharePage() {
  document.getElementById("share-url-text").textContent = getShareableAppUrl();
  renderShareQrCodeIfNeeded();
  document.getElementById("share-page").hidden = false;
}
