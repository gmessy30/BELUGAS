// "Install App" -- no native equivalent at all (native is installed via the app stores; there's
// no in-app install-prompt concept there to port). Purely a web/PWA affordance.
//
// The beforeinstallprompt listener is registered here at TOP LEVEL, not deferred to
// DOMContentLoaded -- Chrome can fire it very early after page load (sometimes before
// DOMContentLoaded finishes) if this origin already meets install criteria from a previous
// visit, and missing that first firing would mean waiting for a page reload before the menu
// item could ever appear. Safe to reference the menu item element immediately since this
// script tag sits near the end of <body>, after that markup, regardless of when the event
// itself fires relative to DOMContentLoaded.
let deferredInstallPrompt = null;

function isStandaloneDisplayMode() {
  return window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone === true;
}

// Standard iOS detection: iPadOS 13+ reports as "MacIntel" with touch support, but that's only
// relevant for desktop-vs-tablet Safari distinctions this app doesn't need -- the classic
// iPhone/iPad/iPod user-agent check (with the !window.MSStream guard against old IE's iOS UA
// spoofing) is enough here, since the only thing this gates is which install affordance to show.
function isIosDevice() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
}

window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  deferredInstallPrompt = event;
  const menuItem = document.getElementById("menu-install-item");
  if (menuItem && !isStandaloneDisplayMode()) menuItem.hidden = false;
});

// Fires on a successful install regardless of how it was triggered -- hide the item either way,
// there's nothing left to install.
window.addEventListener("appinstalled", () => {
  deferredInstallPrompt = null;
  const menuItem = document.getElementById("menu-install-item");
  if (menuItem) menuItem.hidden = true;
});

function initInstallPrompt() {
  const menuItem = document.getElementById("menu-install-item");

  if (isStandaloneDisplayMode()) {
    // Already installed and running from the Home Screen -- nothing to offer.
    menuItem.hidden = true;
  } else if (isIosDevice()) {
    // iOS never fires beforeinstallprompt -- always show the instructions path instead.
    menuItem.hidden = false;
  } else if (deferredInstallPrompt) {
    // beforeinstallprompt already fired before this ran (a slow DOMContentLoaded lagging a fast
    // event) -- show immediately rather than waiting on a second event that won't come.
    menuItem.hidden = false;
  }
  // Otherwise stays hidden (its default HTML state) until/unless beforeinstallprompt fires.

  menuItem.addEventListener("click", onInstallItemClick);
  document.getElementById("ios-install-close-btn").addEventListener("click", () => {
    navigateBack();
  });
}

async function onInstallItemClick() {
  const menu = document.getElementById("main-menu");

  if (deferredInstallPrompt) {
    // A captured event can only ever be prompted once -- clear it (and hide the item) up front
    // regardless of the user's choice below, rather than leaving a spent prompt reference around.
    const promptEvent = deferredInstallPrompt;
    deferredInstallPrompt = null;
    document.getElementById("menu-install-item").hidden = true;
    // Nothing of ours opens in the menu's place here -- the browser's own native install dialog
    // takes over, so this just pops the "menu" nav-stack layer (see nav-stack.js) rather than
    // pushing a new one, same as the plain "Close" item.
    navigateBack();
    promptEvent.prompt();
    await promptEvent.userChoice;
  } else if (isIosDevice()) {
    menu.hidden = true;
    document.getElementById("ios-install-modal").hidden = false;
    pushNavLayer("ios-install-modal", () => {
      document.getElementById("ios-install-modal").hidden = true;
      menu.hidden = false;
    });
  }
}
