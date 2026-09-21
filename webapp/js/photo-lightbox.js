// Item 115a: full-screen photo inspector ("lightbox") for a sighting's photo.
//
// Before this, a sighting photo was static on BOTH surfaces that show one -- a 64px
// `.sighting-thumb` in the list (list-view.js) and a popup-width <img> on the map
// (map-view.js's sightingPopupHtml) -- with no click handling anywhere. Neither is big enough to
// actually judge whether the shapes in the water are belugas, which is the one question a photo
// on this app is FOR. Tapping either now opens the same full-screen view over everything else.
//
// Dismiss: a plain tap anywhere, a swipe DOWN past a threshold, the ✕ button, or the system back
// gesture (it's a real nav-stack layer, like every other overlay in this app). The swipe follows
// the finger while it's in progress and snaps back if released short of the threshold, so a
// half-gesture reads as "not yet", never as a dead control.
//
// No pinch-zoom: a browser's own pinch acts on the whole page, not one element, so real zoom here
// would mean hand-implementing a two-pointer transform -- deliberately out of scope for this
// item, which is about getting the photo to full-screen size at all. Noted rather than silently
// skipped.
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
// instead of closing.
const PHOTO_LIGHTBOX_TAP_SLOP_PX = 10;

let photoLightboxDragStartY = null;
let photoLightboxDragDy = 0;
// Set on a pointerdown that landed on the ✕ button, so the overlay's own tap/swipe handling sits
// this gesture out entirely. See initPhotoLightbox's pointerdown for why this is needed.
let photoLightboxGestureOnCloseBtn = false;

function initPhotoLightbox() {
  const overlay = document.getElementById("photo-lightbox");
  const img = document.getElementById("photo-lightbox-img");

  // The ✕ is a real button (not just the tap-anywhere affordance) so the dismiss is discoverable
  // without the user having to guess that the backdrop is tappable.
  document.getElementById("photo-lightbox-close-btn").addEventListener("click", () => {
    closePhotoLightbox();
  });

  overlay.addEventListener("pointerdown", (event) => {
    // BUG FIX, caught on-device: stopPropagation on the button's own CLICK handler doesn't help
    // here -- pointerup fires and propagates to this overlay BEFORE click ever runs, so a tap on
    // ✕ was closing the lightbox twice: once via the overlay's tap-to-dismiss below, once via the
    // button's click. Two navigateBack() calls pop two layers, so a ✕ tap on a lightbox opened
    // from a map popup ALSO popped the "tab:map" layer underneath and dumped the user back on the
    // list (confirmed on-device: navLayers went straight from ["tab:map","photo-lightbox"] to
    // []). Claiming the gesture here, before it can become either one, is what actually fixes it.
    if (event.target.closest("#photo-lightbox-close-btn")) {
      photoLightboxGestureOnCloseBtn = true;
      return;
    }
    photoLightboxDragStartY = event.clientY;
    photoLightboxDragDy = 0;
    overlay.setPointerCapture(event.pointerId); // same reason as the BearingDial's own capture
    img.classList.remove("snapping-back");
  });

  overlay.addEventListener("pointermove", (event) => {
    if (photoLightboxDragStartY == null) return;
    photoLightboxDragDy = event.clientY - photoLightboxDragStartY;
    // Downward only -- an upward drag isn't a dismiss gesture, so it shouldn't drag the photo
    // off the top of the screen as if it were.
    const dy = Math.max(0, photoLightboxDragDy);
    img.style.transform = `translateY(${dy}px)`;
    // Fading the backdrop with the drag is what makes the gesture read as "throwing it away"
    // rather than just sliding a picture around.
    overlay.style.setProperty(
      "--lightbox-drag-progress",
      String(Math.min(1, dy / PHOTO_LIGHTBOX_DISMISS_DISTANCE_PX))
    );
  });

  const endDrag = () => {
    if (photoLightboxGestureOnCloseBtn) {
      photoLightboxGestureOnCloseBtn = false; // the button's own click handler closes it
      return;
    }
    if (photoLightboxDragStartY == null) return;
    const dy = photoLightboxDragDy;
    photoLightboxDragStartY = null;
    photoLightboxDragDy = 0;

    if (Math.abs(dy) <= PHOTO_LIGHTBOX_TAP_SLOP_PX || dy >= PHOTO_LIGHTBOX_DISMISS_DISTANCE_PX) {
      closePhotoLightbox(); // a tap, or a completed swipe-down
      return;
    }
    // Released short of the threshold (or dragged upward) -- snap back to where it started.
    img.classList.add("snapping-back");
    img.style.transform = "";
    overlay.style.setProperty("--lightbox-drag-progress", "0");
  };
  overlay.addEventListener("pointerup", endDrag);
  overlay.addEventListener("pointercancel", endDrag);
}

function openPhotoLightbox(url) {
  if (!url) return;
  const overlay = document.getElementById("photo-lightbox");
  const img = document.getElementById("photo-lightbox-img");

  img.classList.remove("snapping-back");
  img.style.transform = "";
  overlay.style.setProperty("--lightbox-drag-progress", "0");
  img.src = url;
  overlay.hidden = false;

  pushNavLayer("photo-lightbox", () => {
    overlay.hidden = true;
    // Dropping the src releases the decoded bitmap (and, for a QUEUED sighting, stops holding the
    // blob object-URL alive any longer than the view that's showing it).
    img.removeAttribute("src");
    img.style.transform = "";
    photoLightboxDragStartY = null;
    photoLightboxGestureOnCloseBtn = false;
  });
}

// Goes through the nav stack rather than just hiding the overlay, so the history entry
// pushNavLayer added is actually consumed -- otherwise the next system back gesture would burn
// itself closing an already-closed layer. Nothing here opens a layer of its own, so plain
// navigateBack is correct (see CLAUDE.md's item-107 note on when navigateBackThen is required).
function closePhotoLightbox() {
  navigateBack();
}
