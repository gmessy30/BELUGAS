// Resources page -- ports ResourcesScreen.kt verbatim (static content, no backend). All text,
// section order, phone numbers, and links live directly in index.html rather than here, since
// there's no dynamic behavior beyond showing/hiding the page.
function initResourcesPage() {
  document.getElementById("resources-back-btn").addEventListener("click", () => {
    navigateBack();
  });
}

// Called from the main menu's "Resources" item.
function openResourcesPage() {
  document.getElementById("resources-page").hidden = false;
}
