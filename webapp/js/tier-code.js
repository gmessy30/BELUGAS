// Observer-code redemption modal. Reached the same way it is natively: only via the hidden
// 7-tap-the-whale's-nose gesture on the About page (see about.js's onWhaleNoseTap), not any
// visible menu item.
function initTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const closeBtn = document.getElementById("tier-code-close-btn");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const input = document.getElementById("tier-code-input");

  closeBtn.addEventListener("click", () => {
    navigateBack();
  });

  // Tapping the dimmed backdrop (not the dialog card itself) closes it, same convention as
  // the location/camera steps' plain-button dismissal elsewhere in this app.
  modal.addEventListener("click", (event) => {
    if (event.target === modal) navigateBack();
  });

  submitBtn.addEventListener("click", submitTierCode);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitTierCode();
  });
}

// Called from about.js's onWhaleNoseTap once the hidden gesture completes.
function openTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const input = document.getElementById("tier-code-input");
  modal.hidden = false;
  setTierCodeStatus("");
  input.value = "";
  input.focus();
}

async function submitTierCode() {
  const input = document.getElementById("tier-code-input");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const code = input.value.trim();

  if (!code) {
    setTierCodeStatus("Enter a code first.", true);
    return;
  }

  submitBtn.disabled = true;
  setTierCodeStatus("Checking…");

  const subscriberId = getOrCreateSubscriberId();
  const result = await redeemTierCode(code, subscriberId);

  if (result.status === "SUCCESS") {
    setTierCodeStatus(`Code accepted -- this device is now a Tier ${result.tier} observer.`);
    input.value = "";
  } else if (result.status === "RATE_LIMITED") {
    setTierCodeStatus("Too many attempts on this device recently -- try again later.", true);
  } else {
    setTierCodeStatus("That code isn't valid.", true);
  }

  submitBtn.disabled = false;
}

function setTierCodeStatus(message, isError = false) {
  const el = document.getElementById("tier-code-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}
