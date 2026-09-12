// Bridges the Android/PWA back gesture (and hardware back button) to in-app navigation instead of
// exiting the app entirely -- every screen/overlay pushed here becomes a real history entry, so
// the browser/OS back gesture and this app's own "<- BACK"/"<- DONE"/"Close" buttons both funnel
// through the exact same popstate-driven teardown and can never drift out of sync with each other.
//
// Map (the app's default/home view) is the single base history entry (history.replaceState at
// load, never pushed again) -- with nothing else on navLayers, pressing back has no popstate to
// catch at all, so the browser's/PWA's own real "exit" behavior takes over untouched, exactly like
// a native app's root screen. Every other tab, page, modal, picker, and the submit tab's
// camera->review step is a pushed layer on top of that.
let navLayers = [];

// name is a plain label (only useful for debugging -- popping is purely depth-driven, see
// initNavStack's popstate handler); onPop is what actually tears this layer back down, called
// exactly once, whether triggered by a browser/OS back gesture or by navigateBack() below.
function pushNavLayer(name, onPop) {
  navLayers.push({ name, onPop });
  history.pushState({ navDepth: navLayers.length }, "");
}

// Swaps the CURRENT top layer's name/onPop without changing history depth -- for a transition
// that should stay exactly as back-able as whatever it's replacing (same depth, new teardown),
// not an additional thing to back out of on top of it.
function replaceNavLayer(name, onPop) {
  if (navLayers.length === 0) return;
  navLayers[navLayers.length - 1] = { name, onPop };
}

// Bound to a screen's own back/close/done/cancel button -- a button handler should NEVER call a
// layer's onPop (or otherwise hide its own overlay) directly. Calling history.back() here instead
// fires popstate, which is the ONLY place a layer actually gets torn down -- so the in-app button
// path and the swipe/hardware-back path can never diverge into two different ideas of what's open.
function navigateBack() {
  if (navLayers.length > 0) history.back();
}

function initNavStack() {
  history.replaceState({ navDepth: 0 }, "");

  // Reconciles navLayers down to whatever depth the new (already-changed) history entry claims,
  // popping (and tearing down) everything above it -- one iteration per back-step, so a jump of
  // more than one entry at once (not reachable from a plain single back-gesture, but harmless if
  // it ever happens) still unwinds correctly rather than assuming exactly one layer changed.
  window.addEventListener("popstate", (event) => {
    const targetDepth = event.state ? event.state.navDepth : 0;
    while (navLayers.length > targetDepth) {
      navLayers.pop().onPop();
    }
  });
}
