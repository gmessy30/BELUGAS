// Resources page -- ports ResourcesScreen.kt verbatim (static content, no backend). All text,
// section order, phone numbers, and links live directly in index.html rather than here, since
// there's no dynamic behavior beyond showing/hiding the page.

// Item 28b: a reader-controlled dim/hide toggle for Mae, since the text-shadow fix (style.css)
// alone can still fall short over a particularly light patch of the artwork. No equivalent in
// native (ResourcesScreen.kt has no such control) -- a web-only addition for this app's own
// readability pass, not a ported feature. Persisted so the choice survives reopening this page or
// the app entirely, not just the current visit -- a real per-viewer preference, not transient UI
// state, unlike e.g. the About page's HIDE TEXT toggle which always resets to shown.
const RESOURCES_BG_DIM_STORAGE_KEY = "belugas.resourcesBgDimmed";

function setResourcesBgDimmed(dimmed) {
  document.getElementById("resources-dim-overlay").hidden = !dimmed;
  document.getElementById("resources-toggle-bg-btn").textContent = dimmed ? "SHOW BACKGROUND" : "DIM BACKGROUND";
  try {
    localStorage.setItem(RESOURCES_BG_DIM_STORAGE_KEY, dimmed ? "1" : "0");
  } catch (e) {
    // Private-browsing/storage-disabled: the toggle still works for this visit, it just won't
    // be remembered next time -- never worth failing the toggle itself over.
  }
}

function initResourcesPage() {
  document.getElementById("resources-back-btn").addEventListener("click", () => {
    navigateBack();
  });

  document.getElementById("resources-toggle-bg-btn").addEventListener("click", () => {
    setResourcesBgDimmed(document.getElementById("resources-dim-overlay").hidden);
  });
}

// Called from the main menu's "Resources" item.
function openResourcesPage() {
  let dimmed = false;
  try {
    dimmed = localStorage.getItem(RESOURCES_BG_DIM_STORAGE_KEY) === "1";
  } catch (e) {
    // Same fallback as above -- default to not-dimmed (Mae's normal state) if storage is
    // unavailable rather than failing the page open.
  }
  setResourcesBgDimmed(dimmed);
  document.getElementById("resources-page").hidden = false;
}
