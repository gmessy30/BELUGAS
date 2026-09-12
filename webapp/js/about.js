// About page -- ports AboutScreen.kt + AboutHiddenGesture.kt directly. The tier-code modal has
// no other visible entry point: it's reached ONLY by tapping 7 times within the whale-B nose
// zone on this page's own artwork, mirroring Android's long-precedented "tap the build number 7
// times" developer-options convention exactly, the same way native does.

const LUNA_ARTWORK_INQUIRY_EMAIL = "keeneyeapps+luna@gmail.com";
const DEVELOPER_CONTACT_EMAIL = "keeneyeapps@gmail.com";

// Matches AboutHiddenGesture.kt's own constants exactly -- the tap count resets (rather than
// accumulating indefinitely) after any gap longer than this between taps, and there's no visible
// hint anywhere that this exists.
const HIDDEN_GESTURE_TAP_COUNT = 7;
const HIDDEN_GESTURE_TAP_TIMEOUT_MS = 1500;

// beluga_about_sketches.jpg's real pixel dimensions (confirmed via the source file, not assumed
// -- same as native's own comment). The artwork is drawn with object-fit:contain (CSS's
// ContentScale.Fit equivalent), which letterboxes rather than crops, so the drawn image's on-
// screen rect depends on both this intrinsic aspect ratio and whatever size the viewport is.
const ABOUT_ARTWORK_INTRINSIC_WIDTH = 1179;
const ABOUT_ARTWORK_INTRINSIC_HEIGHT = 1500;

// Whale B's nose (mouth/teeth), as a proportion of the artwork's own bounds -- a 150x150px box
// at (635,840) in the 1179x1500 original, exactly matching WHALE_B_NOSE_X_MIN/MAX/Y_MIN/MAX in
// AboutHiddenGesture.kt. Proportional, not fixed pixels, so it lands on the same whale
// regardless of viewport size/aspect ratio.
const WHALE_B_NOSE_X_MIN = 635 / ABOUT_ARTWORK_INTRINSIC_WIDTH;
const WHALE_B_NOSE_X_MAX = 785 / ABOUT_ARTWORK_INTRINSIC_WIDTH;
const WHALE_B_NOSE_Y_MIN = 840 / ABOUT_ARTWORK_INTRINSIC_HEIGHT;
const WHALE_B_NOSE_Y_MAX = 990 / ABOUT_ARTWORK_INTRINSIC_HEIGHT;

// The two backgrounds AboutScreen swipes between -- page 0 is Luna's original reference sheet
// (also what the hidden gesture above is calibrated against), page 1 the newer breaching
// sketches. Tap-the-dots here stands in for native's swipeable HorizontalPager -- a real swipe
// gesture library felt like overkill for a two-image toggle with no other purpose.
const ABOUT_BACKGROUND_IMAGES = ["img/beluga_about_sketches.jpg", "img/breaching_belugas_sketches.png"];

let hiddenGestureTapCount = 0;
let hiddenGestureLastTapAt = 0;

/**
 * Undoes object-fit:contain's letterboxing to find where a raw tap (in the image element's own
 * local pixels) lands within the artwork's 0..1 coordinate space. Ported directly from
 * AboutHiddenGesture.kt's normalizedArtworkPoint -- same scale/letterbox/bounds-check math, just
 * JS instead of Kotlin. Returns null if the tap landed in the letterbox margin itself (genuinely
 * outside the drawn image).
 */
function normalizedArtworkPoint(tapX, tapY, containerWidth, containerHeight) {
  if (containerWidth <= 0 || containerHeight <= 0) return null;

  const scale = Math.min(containerWidth / ABOUT_ARTWORK_INTRINSIC_WIDTH, containerHeight / ABOUT_ARTWORK_INTRINSIC_HEIGHT);
  const drawnWidth = ABOUT_ARTWORK_INTRINSIC_WIDTH * scale;
  const drawnHeight = ABOUT_ARTWORK_INTRINSIC_HEIGHT * scale;
  const letterboxX = (containerWidth - drawnWidth) / 2;
  const letterboxY = (containerHeight - drawnHeight) / 2;

  const imageX = tapX - letterboxX;
  const imageY = tapY - letterboxY;
  if (imageX < 0 || imageX > drawnWidth || imageY < 0 || imageY > drawnHeight) return null;

  return { x: imageX / drawnWidth, y: imageY / drawnHeight };
}

function isWithinWhaleBNose(point) {
  return point.x >= WHALE_B_NOSE_X_MIN && point.x <= WHALE_B_NOSE_X_MAX &&
    point.y >= WHALE_B_NOSE_Y_MIN && point.y <= WHALE_B_NOSE_Y_MAX;
}

// Matches AboutScreen's onWhaleNoseTap exactly: a rolling window, not an ever-accumulating
// counter, and no feedback at all until the count is actually reached.
function onWhaleNoseTap() {
  const now = Date.now();
  hiddenGestureTapCount = (now - hiddenGestureLastTapAt <= HIDDEN_GESTURE_TAP_TIMEOUT_MS)
    ? hiddenGestureTapCount + 1
    : 1;
  hiddenGestureLastTapAt = now;

  if (hiddenGestureTapCount >= HIDDEN_GESTURE_TAP_COUNT) {
    hiddenGestureTapCount = 0;
    document.getElementById("about-page").hidden = true;
    openTierCodeModal();
  }
}

function setAboutBackground(index) {
  document.getElementById("about-bg-image").src = ABOUT_BACKGROUND_IMAGES[index];
  document.querySelectorAll(".about-bg-dot").forEach((dot, i) => {
    dot.classList.toggle("active", i === index);
  });
}

function setAboutTextVisible(visible) {
  document.getElementById("about-text-content").hidden = !visible;
  document.getElementById("about-toggle-text-btn").textContent = visible ? "HIDE TEXT" : "SHOW TEXT";
}

function initAboutPage() {
  document.getElementById("about-back-btn").addEventListener("click", () => {
    document.getElementById("about-page").hidden = true;
  });

  document.getElementById("about-toggle-text-btn").addEventListener("click", () => {
    setAboutTextVisible(document.getElementById("about-text-content").hidden);
  });

  document.querySelectorAll(".about-bg-dot").forEach((dot) => {
    dot.addEventListener("click", () => setAboutBackground(Number(dot.dataset.bgIndex)));
  });

  // One listener on the whole page, not just a background layer: native's own tap detector sits
  // BEHIND the header/text (so those get first claim on their own taps) but still receives any
  // tap that isn't consumed by a real control -- including a tap that lands on empty space
  // within the text panel itself, not just the bare artwork. Bubbling naturally reproduces that
  // here; .about-interactive marks the controls that should never also run the nose-zone check.
  document.getElementById("about-page").addEventListener("click", (event) => {
    if (event.target.closest(".about-interactive")) return;

    const img = document.getElementById("about-bg-image");
    const rect = img.getBoundingClientRect();
    const point = normalizedArtworkPoint(event.clientX - rect.left, event.clientY - rect.top, rect.width, rect.height);
    if (!point || !isWithinWhaleBNose(point)) return;

    // A nose tap while the text overlay is still showing both dismisses it AND counts as the
    // gesture's first tap, matching AboutScreen's own onWhaleNoseTap wiring exactly.
    if (!document.getElementById("about-text-content").hidden) {
      setAboutTextVisible(false);
    }
    onWhaleNoseTap();
  });
}

// Called from the main menu's "About" item.
function openAboutPage() {
  hiddenGestureTapCount = 0;
  setAboutTextVisible(true);
  setAboutBackground(0);
  document.getElementById("about-page").hidden = false;
}
