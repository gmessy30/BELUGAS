// Observer-code redemption modal. The native app hides this behind a 7-tap gesture on the About
// screen (AboutScreen.kt) -- there's no equivalent "hidden" affordance that makes sense on the
// web, so this is a plain visible header button instead, per the brief.
function initTierCodeModal() {
  const modal = document.getElementById("tier-code-modal");
  const openBtn = document.getElementById("tier-code-open-btn");
  const closeBtn = document.getElementById("tier-code-close-btn");
  const submitBtn = document.getElementById("tier-code-submit-btn");
  const input = document.getElementById("tier-code-input");

  openBtn.addEventListener("click", () => {
    modal.hidden = false;
    setTierCodeStatus("");
    input.value = "";
    input.focus();
  });

  closeBtn.addEventListener("click", () => {
    modal.hidden = true;
  });

  // Tapping the dimmed backdrop (not the dialog card itself) closes it, same convention as
  // the location/camera steps' plain-button dismissal elsewhere in this app.
  modal.addEventListener("click", (event) => {
    if (event.target === modal) modal.hidden = true;
  });

  submitBtn.addEventListener("click", submitTierCode);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitTierCode();
  });
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
