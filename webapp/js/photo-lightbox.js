// Item 115a: full-screen photo inspector ("lightbox") for a sighting's photo.
// Item 118: hand-implemented pinch-zoom / pan / double-tap-reset on top of it.
//
// Before item 115a, a sighting photo was static on BOTH surfaces that show one -- a 64px
// `.sighting-thumb` in the list (list-view.js) and a popup-width <img> on the map
// (map-view.js's sightingPopupHtml) -- with no click handling anywhere. Neither is big enough to
// actually judge whether the shapes in the water are belugas, which is the one question a photo
// on this app is FOR. Tapping either opens this full-screen view over everything else.
//
// Dismiss: a plain tap anywhere, a swipe DOWN past a threshold, the ✕ button, or the system back
// gesture (it's a real nav-stack layer, like every other overlay in this app). The swipe follows
// the finger while it's in progress and snaps back if released short of the threshold, so a
// half-gesture reads as "not yet", never as a dead control.
//
// ZOOM (item 118). A browser's own pinch acts on the whole page, not one element, so there is no
// way to get element-level zoom here for free -- this tracks the two touch points itself and
// drives a transform on the <img>. Because the transform never leaves that one element (and the
// overlay sets touch-action:none, so the browser never claims the gesture for page zoom), nothing
// outside the lightbox layer is affected: no page scroll, no visual-viewport scale change.
//
// GESTURE ARBITRATION -- the part worth reading before changing anything here:
//   * TWO pointers down  -> pinch. Any swipe-dismiss in progress is abandoned and snapped back,
//     because a pinch that starts with one finger landing slightly before the other would
//     otherwise be read as the beginning of a dismiss.
//   * ONE pointer down, scale > 1 -> pan. Swipe-to-dismiss is deliberately NOT available while
//     zoomed: a zoomed photo's whole point is dragging it around, and the two gestures are the
//     same motion. This is the pattern every photo viewer uses.
//   * ONE pointer down, scale == 1 -> swipe-to-dismiss, exactly as item 115a shipped it.
//
// TAP vs DOUBLE-TAP. Tap-to-dismiss works at any zoom level, and double-tap resets to fit -- so
// at scale > 1 the first tap of a double-tap is ambiguous and the dismiss has to be deferred by
// PHOTO_LIGHTBOX_DOUBLE_TAP_MS to see whether a second tap lands. At scale == 1 there is no
// ambiguity (a reset would be a no-op), so the far more common case keeps an INSTANT dismiss with
// no artificial delay. That asymmetry is the whole reason double-tap is reset-only and doesn't
// also zoom IN at 1x -- adding that would put a 280ms lag on every ordinary tap-to-close.
//
// The ✕ button and the system back gesture dismiss immediately at any zoom level, untouched by
// any of the above.
//
// No native equivalent to port from: neither SightingsMapScreen.kt nor App.kt's
// OfflineSightingsList opens a photo full-screen either (SightingPhotoLoader.kt only ever feeds
// the same small inline thumbnail), so this is a web-first addition -- a native-parity item, the
// same direction as items 90/92/105/113/114.

// Below .modal-backdrop's 991 would let a modal paint over the photo; at or above #splash-screen's
// 1000 would let it cover the splash on a cold start. 995 sits deliberately between the two.
// See style.css's own #photo-lightbox rule for the matching value.

// How far down the finger has to travel before release dismisses rather than snaps back.
const PHOTO_LIGHTBOX_DISMISS_DISTANCE_PX = 90;
// Below this, a pointerup counts as a TAP (which also dismisses) rather than a drag -- without
// it, the tiny movement in any real finger tap would register as an aborted swipe and snap back
// instead of closing. Also the movement budget a double-tap's two taps must both stay inside.
const PHOTO_LIGHTBOX_TAP_SLOP_PX = 10;
// Item 118: zoom limits. 1 is "fit", the only scale at which swipe-to-dismiss is live; 4x is
// enough to inspect a distant blow or a dorsal ridge without the source JPEG turning to mush.
const PHOTO_LIGHTBOX_MIN_SCALE = 1;
const PHOTO_LIGHTBOX_MAX_SCALE = 4;
// How long a tap waits for a possible second tap -- ONLY while zoomed (see the header comment).
const PHOTO_LIGHTBOX_DOUBLE_TAP_MS = 280;

// --- Gesture state. All of it is reset by resetPhotoLightboxTransform() on open and on close,
// so a previous photo's zoom can never leak into the next one. ---
let photoLightboxScale = 1;
let photoLightboxTx = 0;
let photoLightboxTy = 0;
// Swipe-dismiss offset, kept separate from photoLightboxTy so the two can never fight: the swipe
// is transient and always ends in either a dismiss or a snap back to 0, while Ty is committed pan.
let photoLightboxSwipeDy = 0;
// pointerId -> {x, y} for every pointer currently down on the overlay.
const photoLightboxPointers = new Map();
// null | "swipe" | "pan" | "pinch"
let photoLightboxGesture = null;
// Baselines captured at the start of the current gesture.
let photoLightboxPinchStartDist = 0;
let photoLightboxPinchStartScale = 1;
let photoLightboxGestureStartTx = 0;
let photoLightboxGestureStartTy = 0;
let photoLightboxGestureStartX = 0;
let photoLightboxGestureStartY = 0;
// Tap bookkeeping: cleared the moment a gesture moves beyond the slop, so a drag can never be
// mistaken for a tap on release.
let photoLightboxTapCandidate = false;
let photoLightboxPendingTapTimer = null;
let photoLightboxLastTapPoint = null;
// Set on a pointerdown that landed on the ✕ button, so the overlay's own tap/swipe handling sits
// this gesture out entirely. See initPhotoLightbox's pointerdown for why this is needed.
let photoLightboxGestureOnCloseBtn = false;

function photoLightboxImg() {
  return document.getElementById("photo-lightbox-img");
}

// Item 118: the two states need genuinely different instructions -- swipe-down dismisses only at
// 1x, and double-tap-to-reset only means anything above it. Driven off the live scale rather than
// off which gesture last ran, so it can't drift out of sync with what the handlers will actually
// do.
function updatePhotoLightboxHint() {
  const el = document.getElementById("photo-lightbox-hint");
  if (!el) return;
  el.textContent = photoLightboxScale > PHOTO_LIGHTBOX_MIN_SCALE
    ? "Drag to move · double-tap to fit · tap to close"
    : "Pinch to zoom · tap or swipe down to close";
}

function clampPhotoLightboxValue(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

// Keeps a zoomed photo from being flung entirely off screen: translation is allowed only as far
// as the scaled image actually overhangs the viewport, and is pinned to 0 on whichever axis it
// doesn't overhang at all. offsetWidth/offsetHeight (NOT getBoundingClientRect) because those are
// layout sizes, unaffected by the transform we're in the middle of computing.
//
// Known simplification: the <img> is object-fit:contain, so a photo whose aspect ratio differs
// from its box is letterboxed inside it, and these bounds are the BOX's, not the picture's. The
// practical effect is a little slack at the edges on an unusually shaped photo -- deliberately
// not chased, since computing the true contained rect would mean tracking naturalWidth/Height
// through every resize and orientation change for a barely visible difference.
function clampPhotoLightboxPan() {
  const img = photoLightboxImg();
  const maxX = Math.max(0, (img.offsetWidth * photoLightboxScale - window.innerWidth) / 2);
  const maxY = Math.max(0, (img.offsetHeight * photoLightboxScale - window.innerHeight) / 2);
  photoLightboxTx = clampPhotoLightboxValue(photoLightboxTx, -maxX, maxX);
  photoLightboxTy = clampPhotoLightboxValue(photoLightboxTy, -maxY, maxY);
}

// translate() is written BEFORE scale() on purpose: CSS applies the list right-to-left, so the
// element is scaled first and the translation stays in unscaled screen pixels -- which is the
// space every number above is computed in.
function applyPhotoLightboxTransform() {
  photoLightboxImg().style.transform =
    `translate(${photoLightboxTx}px, ${photoLightboxTy + photoLightboxSwipeDy}px) ` +
    `scale(${photoLightboxScale})`;
}

function resetPhotoLightboxTransform(animated) {
  const img = photoLightboxImg();
  img.classList.toggle("animating", !!animated);
  photoLightboxScale = 1;
  photoLightboxTx = 0;
  photoLightboxTy = 0;
  photoLightboxSwipeDy = 0;
  applyPhotoLightboxTransform();
  document.getElementById("photo-lightbox").style.setProperty("--lightbox-drag-progress", "0");
  updatePhotoLightboxHint();
}

function photoLightboxPointerList() {
  return Array.from(photoLightboxPointers.values());
}

function photoLightboxPinchDistance() {
  const [a, b] = photoLightboxPointerList();
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function photoLightboxPinchMidpoint() {
  const [a, b] = photoLightboxPointerList();
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

// Rescales about the pinch midpoint so the bit of photo under the fingers stays under the
// fingers. Derivation: a point m maps to c + t + s*u where c is the element's (unmoving) layout
// centre and u is that point in unscaled element-local coords, u = (m - c - t0) / s0. Holding m
// fixed and solving for the new translation gives t1 = m - c - (s1/s0) * (m - c - t0).
function scalePhotoLightboxAbout(midpoint, nextScale) {
  const img = photoLightboxImg();
  const rect = img.getBoundingClientRect();
  // The CURRENT rect is transformed, so recover the untransformed centre by backing the
  // translation out of it -- the centre itself never moves under a centre-origin transform.
  const centreX = rect.left + rect.width / 2 - photoLightboxTx;
  const centreY = rect.top + rect.height / 2 - (photoLightboxTy + photoLightboxSwipeDy);
  const ratio = nextScale / photoLightboxScale;
  photoLightboxTx = midpoint.x - centreX - ratio * (midpoint.x - centreX - photoLightboxTx);
  photoLightboxTy = midpoint.y - centreY - ratio * (midpoint.y - centreY - photoLightboxTy);
  photoLightboxScale = nextScale;
  clampPhotoLightboxPan();
  updatePhotoLightboxHint();
}

function cancelPhotoLightboxPendingTap() {
  if (photoLightboxPendingTapTimer !== null) {
    clearTimeout(photoLightboxPendingTapTimer);
    photoLightboxPendingTapTimer = null;
  }
}

// A completed tap. At 1x this dismisses immediately; while zoomed it waits to see whether a
// second tap turns it into a reset -- see the header comment on why only the zoomed case pays
// that cost.
function handlePhotoLightboxTap(point) {
  if (photoLightboxScale <= PHOTO_LIGHTBOX_MIN_SCALE) {
    closePhotoLightbox();
    return;
  }

  const near = photoLightboxLastTapPoint
    && Math.hypot(point.x - photoLightboxLastTapPoint.x, point.y - photoLightboxLastTapPoint.y)
       <= PHOTO_LIGHTBOX_TAP_SLOP_PX * 3;

  if (photoLightboxPendingTapTimer !== null && near) {
    cancelPhotoLightboxPendingTap();
    photoLightboxLastTapPoint = null;
    resetPhotoLightboxTransform(/* animated */ true);
    return;
  }

  cancelPhotoLightboxPendingTap();
  photoLightboxLastTapPoint = point;
  photoLightboxPendingTapTimer = setTimeout(() => {
    photoLightboxPendingTapTimer = null;
    photoLightboxLastTapPoint = null;
    closePhotoLightbox();
  }, PHOTO_LIGHTBOX_DOUBLE_TAP_MS);
}

// Picks the one-finger gesture for the CURRENT scale and captures its baseline. Called both on a
// fresh pointerdown and when a pinch drops back to a single finger, so lifting one finger mid-
// pinch continues as a pan instead of dead-ending.
function beginPhotoLightboxSinglePointerGesture() {
  const [p] = photoLightboxPointerList();
  if (!p) return;
  photoLightboxGesture = photoLightboxScale > PHOTO_LIGHTBOX_MIN_SCALE ? "pan" : "swipe";
  photoLightboxGestureStartX = p.x;
  photoLightboxGestureStartY = p.y;
  photoLightboxGestureStartTx = photoLightboxTx;
  photoLightboxGestureStartTy = photoLightboxTy;
}

function initPhotoLightbox() {
  const overlay = document.getElementById("photo-lightbox");
  const img = photoLightboxImg();

  // The ✕ is a real button (not just the tap-anywhere affordance) so the dismiss is discoverable
  // without the user having to guess that the backdrop is tappable.
  document.getElementById("photo-lightbox-close-btn").addEventListener("click", () => {
    closePhotoLightbox();
  });

  overlay.addEventListener("pointerdown", (event) => {
    // BUG FIX (item 115a), caught on-device: stopPropagation on the button's own CLICK handler
    // doesn't help here -- pointerup fires and propagates to this overlay BEFORE click ever runs,
    // so a tap on ✕ was closing the lightbox twice: once via the overlay's tap-to-dismiss below,
    // once via the button's click. Two navigateBack() calls pop two layers, so a ✕ tap on a
    // lightbox opened from a map popup ALSO popped the "tab:map" layer underneath and dumped the
    // user back on the list (confirmed on-device: navLayers went straight from
    // ["tab:map","photo-lightbox"] to []). Claiming the gesture here, before it can become either
    // one, is what actually fixes it.
    if (event.target.closest("#photo-lightbox-close-btn")) {
      photoLightboxGestureOnCloseBtn = true;
      return;
    }

    photoLightboxPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    overlay.setPointerCapture(event.pointerId); // same reason as the BearingDial's own capture
    img.classList.remove("animating");

    if (photoLightboxPointers.size === 1) {
      photoLightboxTapCandidate = true;
      beginPhotoLightboxSinglePointerGesture();
      return;
    }

    if (photoLightboxPointers.size === 2) {
      // A second finger means this was never a tap and never a dismiss -- drop any swipe already
      // in progress rather than letting it dismiss out from under a pinch.
      photoLightboxTapCandidate = false;
      cancelPhotoLightboxPendingTap();
      photoLightboxSwipeDy = 0;
      overlay.style.setProperty("--lightbox-drag-progress", "0");
      photoLightboxGesture = "pinch";
      photoLightboxPinchStartDist = photoLightboxPinchDistance();
      photoLightboxPinchStartScale = photoLightboxScale;
      applyPhotoLightboxTransform();
    }
  });

  overlay.addEventListener("pointermove", (event) => {
    if (!photoLightboxPointers.has(event.pointerId)) return;
    photoLightboxPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

    if (photoLightboxGesture === "pinch" && photoLightboxPointers.size === 2) {
      if (photoLightboxPinchStartDist <= 0) return;
      const next = clampPhotoLightboxValue(
        photoLightboxPinchStartScale * (photoLightboxPinchDistance() / photoLightboxPinchStartDist),
        PHOTO_LIGHTBOX_MIN_SCALE,
        PHOTO_LIGHTBOX_MAX_SCALE
      );
      scalePhotoLightboxAbout(photoLightboxPinchMidpoint(), next);
      applyPhotoLightboxTransform();
      return;
    }

    const dx = event.clientX - photoLightboxGestureStartX;
    const dy = event.clientY - photoLightboxGestureStartY;
    if (Math.hypot(dx, dy) > PHOTO_LIGHTBOX_TAP_SLOP_PX) {
      photoLightboxTapCandidate = false;
      cancelPhotoLightboxPendingTap();
    }

    if (photoLightboxGesture === "pan") {
      photoLightboxTx = photoLightboxGestureStartTx + dx;
      photoLightboxTy = photoLightboxGestureStartTy + dy;
      clampPhotoLightboxPan();
      applyPhotoLightboxTransform();
      return;
    }

    if (photoLightboxGesture === "swipe") {
      // Downward only -- an upward drag isn't a dismiss gesture, so it shouldn't drag the photo
      // off the top of the screen as if it were.
      photoLightboxSwipeDy = Math.max(0, dy);
      applyPhotoLightboxTransform();
      // Fading the backdrop with the drag is what makes the gesture read as "throwing it away"
      // rather than just sliding a picture around.
      overlay.style.setProperty(
        "--lightbox-drag-progress",
        String(Math.min(1, photoLightboxSwipeDy / PHOTO_LIGHTBOX_DISMISS_DISTANCE_PX))
      );
    }
  });

  const endGesture = (event) => {
    if (photoLightboxGestureOnCloseBtn) {
      photoLightboxGestureOnCloseBtn = false; // the button's own click handler closes it
      return;
    }
    if (!photoLightboxPointers.has(event.pointerId)) return;

    const released = photoLightboxPointers.get(event.pointerId);
    photoLightboxPointers.delete(event.pointerId);

    // Lifting one finger out of a pinch: continue with whatever the remaining finger should now
    // be doing, re-baselined from where it currently is (otherwise the photo would jump by the
    // whole distance that finger travelled during the pinch).
    if (photoLightboxPointers.size > 0) {
      beginPhotoLightboxSinglePointerGesture();
      photoLightboxTapCandidate = false;
      return;
    }

    const wasSwipe = photoLightboxGesture === "swipe";
    const swipeDy = photoLightboxSwipeDy;
    const wasTap = photoLightboxTapCandidate;
    photoLightboxGesture = null;
    photoLightboxTapCandidate = false;

    if (wasTap) {
      // A tap never leaves a swipe offset behind, but clear it defensively so the transform can't
      // keep a few stray pixels if the dismiss is deferred by the double-tap wait.
      photoLightboxSwipeDy = 0;
      applyPhotoLightboxTransform();
      handlePhotoLightboxTap(released);
      return;
    }

    if (wasSwipe && swipeDy >= PHOTO_LIGHTBOX_DISMISS_DISTANCE_PX) {
      closePhotoLightbox();
      return;
    }

    if (wasSwipe) {
      // Released short of the threshold (or dragged upward) -- snap back to where it started.
      img.classList.add("animating");
      photoLightboxSwipeDy = 0;
      applyPhotoLightboxTransform();
      overlay.style.setProperty("--lightbox-drag-progress", "0");
    }
  };
  overlay.addEventListener("pointerup", endGesture);
  overlay.addEventListener("pointercancel", endGesture);
}

function openPhotoLightbox(url) {
  if (!url) return;
  const overlay = document.getElementById("photo-lightbox");
  const img = photoLightboxImg();

  photoLightboxPointers.clear();
  photoLightboxGesture = null;
  photoLightboxTapCandidate = false;
  photoLightboxLastTapPoint = null;
  cancelPhotoLightboxPendingTap();
  resetPhotoLightboxTransform(/* animated */ false);

  img.src = url;
  overlay.hidden = false;

  pushNavLayer("photo-lightbox", () => {
    overlay.hidden = true;
    // Dropping the src releases the decoded bitmap (and, for a QUEUED sighting, stops holding the
    // blob object-URL alive any longer than the view that's showing it).
    img.removeAttribute("src");
    photoLightboxPointers.clear();
    photoLightboxGesture = null;
    photoLightboxTapCandidate = false;
    photoLightboxLastTapPoint = null;
    photoLightboxGestureOnCloseBtn = false;
    cancelPhotoLightboxPendingTap();
    resetPhotoLightboxTransform(/* animated */ false);
  });
}

// Goes through the nav stack rather than just hiding the overlay, so the history entry
// pushNavLayer added is actually consumed -- otherwise the next system back gesture would burn
// itself closing an already-closed layer. Nothing here opens a layer of its own, so plain
// navigateBack is correct (see CLAUDE.md's item-107 note on when navigateBackThen is required).
function closePhotoLightbox() {
  cancelPhotoLightboxPendingTap();
  navigateBack();
}
