// BELUGAS Admin -- issues observer tier codes (public.tier_roster), gated by Supabase Auth
// (email+password, accounts created manually in the dashboard) PLUS the separate tier_admins
// allow-list -- being a logged-in Supabase user is not enough on its own, only a user_id listed
// in tier_admins is (see supabase/migrations/20260921000000_add_tier_admin_rpcs.sql). Not linked
// from the main app's menu at all -- reachable only by knowing this URL.
//
// Item 101: tier_admins now also carries an owner flag and a zone_slug (supabase/migrations/
// 20260929000000_add_tier_admin_roles_zones_and_audit_log.sql). A zone-scoped (non-owner) admin
// only ever sees/acts on codes in their own zone -- enforced server-side in list_tier_codes/
// issue_tier_code/revoke_tier_code, not just hidden here -- and never sees the ADMINS/AUDIT LOG
// sections at all. The owner sees every zone and both of those sections. Every admin RPC
// (issue/revoke/publish/reject/unpublish/add-remove-update-admin) writes to admin_actions;
// list_admin_actions (owner-only) is that log's only read path.
//
// Its own Supabase client instance (separate from webapp/js/db.js's anon-only one) since this is
// the only page in the whole app that ever calls supabase.auth -- the main app has no login
// concept and never needs one.
const adminSupabase = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

let selectedIssueTier = 1;
// Item 101: this caller's own admin status, fetched once per session via get_my_admin_info()
// (self-scoped, any admin can call it -- see that RPC's own comment) -- drives the zone-label
// header, the owner-only sections (ADMINS/AUDIT LOG), and the issue form's zone/owner-device
// fields, which only ever matter for the owner.
let myAdminInfo = { zoneSlug: null, owner: false };

function setLoginStatus(message, isError = false) {
  const el = document.getElementById("admin-login-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

function setIssueStatus(message, isError = false) {
  const el = document.getElementById("issue-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

function showAdminContent() {
  document.getElementById("admin-login-section").hidden = true;
  document.getElementById("admin-content-section").hidden = false;
  document.getElementById("admin-logout-btn").hidden = false;
}

function showLoginForm() {
  document.getElementById("admin-content-section").hidden = true;
  document.getElementById("admin-login-section").hidden = false;
  document.getElementById("admin-logout-btn").hidden = true;
}

// Called once at page load, in case a previous session's auth token is still valid (supabase-js
// persists it in localStorage by default) -- so a returning admin doesn't have to sign in again
// every single visit.
async function checkExistingSession() {
  const { data: { session } } = await adminSupabase.auth.getSession();
  if (!session) return;

  const { data, error } = await adminSupabase.rpc("list_tier_codes");
  if (error) {
    // Session is valid but this account isn't (or is no longer) an admin -- don't leave it
    // silently signed in against a login form that would otherwise look like it needs signing
    // into again.
    await adminSupabase.auth.signOut();
    return;
  }

  await loadMyAdminInfo();
  showAdminContent();
  renderTierCodesList(data);
  refreshPendingArticles();
  refreshRecentlyPublished();
}

// Item 101: fetches this caller's own zone/owner status and applies every UI consequence of it
// (header label, owner-only sections, issue-form fields) -- called once right after a successful
// login/session-restore, before anything else renders.
async function loadMyAdminInfo() {
  const { data, error } = await adminSupabase.rpc("get_my_admin_info");
  if (error || !data || data.length === 0) {
    console.error("GET_MY_ADMIN_INFO_ERROR", error);
    myAdminInfo = { zoneSlug: null, owner: false };
  } else {
    myAdminInfo = { zoneSlug: data[0].zone_slug, owner: data[0].owner };
  }

  const zoneLabel = document.getElementById("admin-zone-label");
  zoneLabel.textContent = myAdminInfo.owner
    ? "OWNER · ALL ZONES"
    : myAdminInfo.zoneSlug
      ? `ZONE: ${myAdminInfo.zoneSlug.toUpperCase()}`
      : "";
  zoneLabel.hidden = !zoneLabel.textContent;

  document.getElementById("issue-zone-input").hidden = !myAdminInfo.owner;
  document.getElementById("issue-owner-device-label").hidden = !myAdminInfo.owner;

  document.getElementById("admins-section").hidden = !myAdminInfo.owner;
  document.getElementById("admins-divider").hidden = !myAdminInfo.owner;
  document.getElementById("audit-log-section").hidden = !myAdminInfo.owner;
  document.getElementById("audit-log-divider").hidden = !myAdminInfo.owner;

  if (myAdminInfo.owner) {
    refreshAdmins();
    refreshAuditLog();
  }
}

async function handleLogin() {
  const email = document.getElementById("admin-email-input").value.trim();
  const password = document.getElementById("admin-password-input").value;
  if (!email || !password) {
    setLoginStatus("Enter both email and password.", true);
    return;
  }

  const loginBtn = document.getElementById("admin-login-btn");
  loginBtn.disabled = true;
  setLoginStatus("Signing in…");

  const { error: signInError } = await adminSupabase.auth.signInWithPassword({ email, password });
  if (signInError) {
    setLoginStatus("Sign-in failed -- check your email and password.", true);
    loginBtn.disabled = false;
    return;
  }

  // Being logged in isn't enough on its own -- list_tier_codes raises unless auth.uid() is in
  // tier_admins (is_tier_admin, in the migration), so ANY error back here means "not an admin,"
  // not necessarily a network/server problem -- see that RPC's own comment.
  const { data, error: listError } = await adminSupabase.rpc("list_tier_codes");
  if (listError) {
    setLoginStatus("This account isn't authorized as a tier admin.", true);
    await adminSupabase.auth.signOut();
    loginBtn.disabled = false;
    return;
  }

  loginBtn.disabled = false;
  setLoginStatus("");
  document.getElementById("admin-password-input").value = "";
  await loadMyAdminInfo();
  showAdminContent();
  renderTierCodesList(data);
  refreshPendingArticles();
  refreshRecentlyPublished();
}

async function handleLogout() {
  await adminSupabase.auth.signOut();
  showLoginForm();
  document.getElementById("admin-email-input").value = "";
  document.getElementById("admin-password-input").value = "";
  document.getElementById("issued-code-result").hidden = true;
  setLoginStatus("");
  myAdminInfo = { zoneSlug: null, owner: false };
  document.getElementById("admin-zone-label").hidden = true;
}

async function refreshTierCodesList() {
  const { data, error } = await adminSupabase.rpc("list_tier_codes");
  if (error) {
    console.error("LIST_TIER_CODES_ERROR", error);
    return;
  }
  renderTierCodesList(data);
}

function renderTierCodesList(rows) {
  const container = document.getElementById("tier-codes-list");
  container.innerHTML = "";
  if (!rows || rows.length === 0) {
    container.innerHTML = '<p class="empty-state">No codes issued yet.</p>';
    return;
  }

  rows.forEach((row) => {
    const item = document.createElement("div");
    item.className = "tier-code-row";

    const info = document.createElement("div");
    info.className = "tier-code-info";

    // Name + device together, so a lead can tell which of one person's several devices a given
    // row is, per the "one code per device" convention (tier_roster's own header comment).
    const nameLine = document.createElement("div");
    nameLine.className = "tier-code-name";
    nameLine.textContent = row.device ? `${row.name} — ${row.device}` : row.name;

    const metaLine = document.createElement("div");
    metaLine.className = "tier-code-meta";
    const contactText = row.contact ? `${row.contact} · ` : "";
    // Item 101: zone/issuer only really add information once more than one exists to tell
    // apart -- the owner (who can see every zone at once) is the one who actually needs this on
    // screen; a zone-scoped admin only ever sees their own zone/their own issuances anyway.
    const zoneText = myAdminInfo.owner && row.zone_slug ? ` · ${row.zone_slug}` : "";
    const issuerText = myAdminInfo.owner && row.issued_by_email ? ` · issued by ${row.issued_by_email}` : "";
    const ownerDeviceText = row.is_owner_device ? " · OWNER'S DEVICE" : "";
    metaLine.textContent = `${contactText}Tier ${row.tier}${zoneText}${issuerText}${ownerDeviceText}`;

    const codeLine = document.createElement("div");
    codeLine.className = "tier-code-code";
    codeLine.textContent = row.code;

    info.append(nameLine, metaLine, codeLine);

    const statusEl = document.createElement("div");
    if (row.is_used) {
      statusEl.className = "tier-code-status-used";
      statusEl.textContent = row.claimed_at
        ? `Claimed ${new Date(row.claimed_at).toLocaleDateString()}`
        : "Used";
    } else {
      statusEl.className = "tier-code-status-active";
      statusEl.textContent = "Unclaimed";
    }

    const revokeBtn = document.createElement("button");
    revokeBtn.type = "button";
    revokeBtn.className = "secondary";
    revokeBtn.textContent = "Revoke";
    // Nothing to revoke on an unclaimed code -- claimed_subscriber_id is already null, and
    // revoke_tier_code only ever clears that column.
    revokeBtn.disabled = !row.is_used;
    revokeBtn.addEventListener("click", () => handleRevoke(row));

    item.append(info, statusEl, revokeBtn);
    container.appendChild(item);
  });
}

// Item 101: names the person, device, zone, and who issued the code -- the confirmation is the
// last thing standing between an admin and an irreversible-feeling action, so it names everything
// that could matter for that decision, not just whose name is on the code.
async function handleRevoke(row) {
  const deviceText = row.device || "device";
  const zoneText = row.zone_slug || "unknown zone";
  const issuerText = row.issued_by_email || "unknown";
  const message = `Revoke ${row.name}'s ${deviceText} code?\n\nZone: ${zoneText}\nIssued by: ${issuerText}\n\nThat device loses its tier immediately.`;
  if (!confirm(message)) return;

  const { data, error } = await adminSupabase.rpc("revoke_tier_code", { p_code: row.code });
  if (error || !data) {
    if (error?.message?.includes("owner_device_protected")) {
      alert("This code is flagged as the owner's own device -- only the owner can revoke it.");
    } else if (error?.message?.includes("not_authorized")) {
      alert("That code belongs to a different zone -- you can only revoke codes in your own zone.");
    } else {
      alert("Couldn't revoke that code -- try again.");
    }
    return;
  }
  await refreshTierCodesList();
  if (myAdminInfo.owner) refreshAuditLog();
}

function setSelectedIssueTier(tier) {
  selectedIssueTier = tier;
  document.getElementById("issue-tier-1-btn").classList.toggle("active", tier === 1);
  document.getElementById("issue-tier-2-btn").classList.toggle("active", tier === 2);
}

async function handleIssue() {
  const name = document.getElementById("issue-name-input").value.trim();
  const contact = document.getElementById("issue-contact-input").value.trim();
  const device = document.getElementById("issue-device-input").value.trim();
  // Both only ever meaningful for the owner -- the server silently ignores/forces these for a
  // non-owner caller regardless of what's sent (see issue_tier_code's own comment), so it's safe
  // to always include them rather than branching on myAdminInfo.owner here too.
  const zone = document.getElementById("issue-zone-input").value.trim();
  const isOwnerDevice = document.getElementById("issue-owner-device-checkbox").checked;

  if (!name) {
    setIssueStatus("Enter a name.", true);
    return;
  }

  const submitBtn = document.getElementById("issue-submit-btn");
  submitBtn.disabled = true;
  setIssueStatus("Issuing…");

  const { data: code, error } = await adminSupabase.rpc("issue_tier_code", {
    p_name: name,
    p_contact: contact || null,
    p_tier: selectedIssueTier,
    p_device: device || null,
    p_zone_slug: zone || null,
    p_is_owner_device: isOwnerDevice
  });

  submitBtn.disabled = false;

  if (error || !code) {
    console.error("ISSUE_TIER_CODE_ERROR", error);
    setIssueStatus("Couldn't issue a code -- try again.", true);
    return;
  }

  setIssueStatus("");
  document.getElementById("issue-name-input").value = "";
  document.getElementById("issue-contact-input").value = "";
  document.getElementById("issue-device-input").value = "";
  document.getElementById("issue-zone-input").value = "";
  document.getElementById("issue-owner-device-checkbox").checked = false;
  showIssuedCode(code);
  await refreshTierCodesList();
  if (myAdminInfo.owner) refreshAuditLog();
}

function showIssuedCode(code) {
  document.getElementById("issued-code-value").textContent = code;
  document.getElementById("issued-code-result").hidden = false;

  // A bare "sms:" with no phone number (the admin picks the recipient in their own messaging
  // app) + "&body=" -- the widely-compatible form on both iOS and Android since there's no
  // number making "?" the natural first separator either way.
  const smsBtn = document.getElementById("issued-code-sms-btn");
  smsBtn.href = `sms:?&body=${encodeURIComponent(`Your BELUGAS observer code: ${code}`)}`;
}

async function handleCopyCode() {
  const code = document.getElementById("issued-code-value").textContent;
  try {
    await navigator.clipboard.writeText(code);
    const copyBtn = document.getElementById("issued-code-copy-btn");
    const original = copyBtn.textContent;
    copyBtn.textContent = "Copied!";
    setTimeout(() => { copyBtn.textContent = original; }, 1500);
  } catch (e) {
    console.error("CLIPBOARD_COPY_ERROR", e);
  }
}

// Item 91: article moderation queue -- supabase/migrations/20260924000000_add_article_
// moderation_rpcs.sql's list_pending_articles/set_article_status are the only door onto
// public.articles beyond the public "published rows only" read (see that migration's own header
// comment for why set_article_status also accepts 'pending_review' as a target, reused for
// UNPUBLISH rather than adding a second RPC for what's really the same status transition).
function articleTypeTag(contentType) {
  return contentType === "research_paper" ? "RESEARCH" : "NEWS";
}

async function refreshPendingArticles() {
  const { data, error } = await adminSupabase.rpc("list_pending_articles");
  const section = document.getElementById("pending-articles-section");
  const divider = document.getElementById("pending-articles-divider");

  if (error) {
    console.error("LIST_PENDING_ARTICLES_ERROR", error);
    return;
  }

  if (!data || data.length === 0) {
    section.hidden = true;
    divider.hidden = true;
    return;
  }

  section.hidden = false;
  divider.hidden = false;
  const container = document.getElementById("pending-articles-list");
  container.innerHTML = "";
  data.forEach((article) => container.appendChild(pendingArticleCard(article)));
}

function pendingArticleCard(article) {
  const card = document.createElement("div");
  card.className = "article-review-card";

  const header = document.createElement("div");
  header.className = "article-review-header";
  const tag = document.createElement("span");
  tag.className = "article-review-tag";
  tag.textContent = articleTypeTag(article.content_type);
  const date = document.createElement("span");
  date.className = "article-review-date";
  date.textContent = new Date(article.created_at).toLocaleDateString();
  header.append(tag, date);
  card.appendChild(header);

  if (article.submitted_by) {
    const submitter = document.createElement("div");
    submitter.className = "article-review-submitter";
    submitter.textContent = `Submitted by ${article.submitted_by}`;
    card.appendChild(submitter);
  }

  const urlLink = document.createElement("a");
  urlLink.className = "article-review-url";
  urlLink.href = article.source_url;
  urlLink.target = "_blank";
  urlLink.rel = "noopener";
  urlLink.textContent = article.source_url;
  card.appendChild(urlLink);

  const titleInput = document.createElement("input");
  titleInput.className = "suggest-input article-review-title-input";
  titleInput.type = "text";
  titleInput.value = article.title;
  card.appendChild(titleInput);

  const summaryInput = document.createElement("textarea");
  summaryInput.className = "suggest-input article-review-summary-input";
  summaryInput.rows = 3;
  summaryInput.value = article.summary || "";
  summaryInput.placeholder = "Summary (optional)";
  card.appendChild(summaryInput);

  const statusEl = document.createElement("p");
  statusEl.className = "status-info article-review-status";
  card.appendChild(statusEl);

  const actions = document.createElement("div");
  actions.className = "modal-actions";
  const publishBtn = document.createElement("button");
  publishBtn.type = "button";
  publishBtn.className = "primary";
  publishBtn.textContent = "Publish";
  publishBtn.addEventListener("click", () =>
    handleSetArticleStatus(article.id, "published", titleInput, summaryInput, statusEl, publishBtn,
      `Publish "${titleInput.value.trim()}"? It will appear in the News Feed immediately.`));

  const rejectBtn = document.createElement("button");
  rejectBtn.type = "button";
  rejectBtn.className = "secondary";
  rejectBtn.textContent = "Reject";
  rejectBtn.addEventListener("click", () =>
    handleSetArticleStatus(article.id, "rejected", titleInput, summaryInput, statusEl, rejectBtn,
      `Reject "${titleInput.value.trim()}"? It will not appear in the News Feed.`));

  actions.append(publishBtn, rejectBtn);
  card.appendChild(actions);

  return card;
}

// Shared by PUBLISH/REJECT (pendingArticleCard) and UNPUBLISH (publishedArticleCard) -- same
// two-step confirm() guard revoke_tier_code's own handleRevoke uses, since PUBLISH is just as
// irreversible-feeling (goes live immediately) as a tier revocation is.
async function handleSetArticleStatus(id, status, titleInput, summaryInput, statusEl, triggerBtn, confirmMessage) {
  if (!confirm(confirmMessage)) return;

  triggerBtn.disabled = true;
  statusEl.textContent = "Saving…";

  const { data, error } = await adminSupabase.rpc("set_article_status", {
    p_id: id,
    p_status: status,
    p_title: titleInput ? titleInput.value.trim() : null,
    p_summary: summaryInput ? summaryInput.value.trim() : null
  });

  if (error || !data) {
    console.error("SET_ARTICLE_STATUS_ERROR", error);
    statusEl.textContent = "Couldn't save -- try again.";
    statusEl.className = "status-error article-review-status";
    triggerBtn.disabled = false;
    return;
  }

  await Promise.all([refreshPendingArticles(), refreshRecentlyPublished()]);
  if (myAdminInfo.owner) refreshAuditLog();
}

// Item 101: owner-only admin management -- ADMINS section is hidden entirely for a non-owner
// (loadMyAdminInfo), so these are only ever called when myAdminInfo.owner is true.
async function refreshAdmins() {
  const { data, error } = await adminSupabase.rpc("list_tier_admins");
  if (error) {
    console.error("LIST_TIER_ADMINS_ERROR", error);
    return;
  }
  renderAdminsList(data);
}

function renderAdminsList(rows) {
  const container = document.getElementById("admins-list");
  container.innerHTML = "";
  if (!rows || rows.length === 0) {
    container.innerHTML = '<p class="empty-state">No admins yet.</p>';
    return;
  }

  rows.forEach((row) => {
    const item = document.createElement("div");
    item.className = "admin-row";

    const info = document.createElement("div");
    info.className = "admin-row-info";
    const emailLine = document.createElement("div");
    emailLine.className = "admin-row-email";
    emailLine.textContent = row.email;
    const metaLine = document.createElement("div");
    metaLine.className = "admin-row-meta";
    metaLine.textContent = row.owner ? "OWNER · all zones" : `Zone: ${row.zone_slug}`;
    info.append(emailLine, metaLine);
    item.appendChild(info);

    // Owner row gets no zone/remove controls at all -- ownership isn't changeable here (see the
    // migration's own comment on why that's deliberately out of scope), and remove_tier_admin
    // refuses the owner row server-side regardless, so there's nothing these controls could
    // actually do for that row.
    if (!row.owner) {
      const zoneInput = document.createElement("input");
      zoneInput.className = "suggest-input";
      zoneInput.type = "text";
      zoneInput.value = row.zone_slug;
      zoneInput.style.width = "120px";
      zoneInput.style.marginTop = "0";

      const saveBtn = document.createElement("button");
      saveBtn.type = "button";
      saveBtn.className = "secondary";
      saveBtn.textContent = "Save Zone";
      saveBtn.addEventListener("click", () => handleUpdateAdminZone(row.user_id, zoneInput.value.trim(), saveBtn));

      const removeBtn = document.createElement("button");
      removeBtn.type = "button";
      removeBtn.className = "secondary";
      removeBtn.textContent = "Remove";
      removeBtn.addEventListener("click", () => handleRemoveAdmin(row.user_id, row.email));

      item.append(zoneInput, saveBtn, removeBtn);
    }

    container.appendChild(item);
  });
}

function setAdminsStatus(message, isError = false) {
  const el = document.getElementById("admins-status");
  el.textContent = message;
  el.className = isError ? "status-error" : "status-info";
}

async function handleAddAdmin() {
  const email = document.getElementById("add-admin-email-input").value.trim();
  const zone = document.getElementById("add-admin-zone-input").value.trim();
  if (!email || !zone) {
    setAdminsStatus("Enter both an email and a zone slug.", true);
    return;
  }

  const btn = document.getElementById("add-admin-btn");
  btn.disabled = true;
  setAdminsStatus("Adding…");

  const { error } = await adminSupabase.rpc("add_tier_admin", { p_email: email, p_zone_slug: zone });
  btn.disabled = false;

  if (error) {
    console.error("ADD_TIER_ADMIN_ERROR", error);
    setAdminsStatus(
      error.message?.includes("user_not_found")
        ? "No account with that email -- create it in the Supabase dashboard first."
        : "Couldn't add that admin -- try again.",
      true
    );
    return;
  }

  setAdminsStatus("");
  document.getElementById("add-admin-email-input").value = "";
  document.getElementById("add-admin-zone-input").value = "";
  await refreshAdmins();
  refreshAuditLog();
}

async function handleUpdateAdminZone(userId, newZone, triggerBtn) {
  if (!newZone) return;
  triggerBtn.disabled = true;
  const { error } = await adminSupabase.rpc("update_tier_admin_zone", { p_user_id: userId, p_zone_slug: newZone });
  triggerBtn.disabled = false;
  if (error) {
    console.error("UPDATE_TIER_ADMIN_ZONE_ERROR", error);
    setAdminsStatus("Couldn't update that admin's zone -- try again.", true);
    return;
  }
  setAdminsStatus("");
  await refreshAdmins();
  refreshAuditLog();
}

async function handleRemoveAdmin(userId, email) {
  if (!confirm(`Remove ${email} as an admin? They immediately lose all admin access.`)) return;

  const { error } = await adminSupabase.rpc("remove_tier_admin", { p_user_id: userId });
  if (error) {
    console.error("REMOVE_TIER_ADMIN_ERROR", error);
    setAdminsStatus("Couldn't remove that admin -- try again.", true);
    return;
  }
  await refreshAdmins();
  refreshAuditLog();
}

// Item 101: audit log, owner-only -- read-only, no actions here, just a history of who did what.
async function refreshAuditLog() {
  const { data, error } = await adminSupabase.rpc("list_admin_actions");
  if (error) {
    console.error("LIST_ADMIN_ACTIONS_ERROR", error);
    return;
  }
  renderAuditLog(data);
}

function renderAuditLog(rows) {
  const container = document.getElementById("audit-log-list");
  container.innerHTML = "";
  if (!rows || rows.length === 0) {
    container.innerHTML = '<p class="empty-state">No admin actions logged yet.</p>';
    return;
  }

  rows.forEach((row) => {
    const item = document.createElement("div");
    item.className = "audit-log-row";

    const info = document.createElement("div");
    info.className = "audit-log-info";
    const actionLine = document.createElement("div");
    actionLine.className = "audit-log-action";
    actionLine.textContent = row.action;
    const metaLine = document.createElement("div");
    metaLine.className = "audit-log-meta";
    const detailText = row.detail && Object.keys(row.detail).length > 0
      ? Object.entries(row.detail).map(([k, v]) => `${k}: ${v}`).join(", ")
      : "";
    metaLine.textContent = `${row.admin_email || "unknown"} → ${row.target_table}${detailText ? " · " + detailText : ""}`;
    info.append(actionLine, metaLine);

    const timeEl = document.createElement("div");
    timeEl.className = "audit-log-time";
    timeEl.textContent = new Date(row.created_at).toLocaleString();

    item.append(info, timeEl);
    container.appendChild(item);
  });
}

async function refreshRecentlyPublished() {
  // No dedicated RPC needed here -- 20260823120000_add_articles.sql's own "Public read access to
  // published articles" RLS policy already lets ANY caller (this admin session included) read
  // status='published' rows directly, the same policy the News Feed's own getArticles (db.js)
  // relies on. reviewed_at is null for nothing here since it's only ever populated when
  // set_article_status runs, which is the only way a row's status becomes 'published' at all.
  const { data, error } = await adminSupabase
    .from("articles")
    .select("id, title, summary, source_url, content_type, submitted_by, reviewed_at")
    .eq("status", "published")
    .order("reviewed_at", { ascending: false })
    .limit(10);

  const container = document.getElementById("published-articles-list");
  if (error) {
    console.error("LIST_RECENTLY_PUBLISHED_ERROR", error);
    return;
  }

  container.innerHTML = "";
  if (!data || data.length === 0) {
    container.innerHTML = '<p class="empty-state">Nothing published yet.</p>';
    return;
  }
  data.forEach((article) => container.appendChild(publishedArticleCard(article)));
}

function publishedArticleCard(article) {
  const card = document.createElement("div");
  card.className = "article-review-card article-published-card";

  const header = document.createElement("div");
  header.className = "article-review-header";
  const tag = document.createElement("span");
  tag.className = "article-review-tag";
  tag.textContent = articleTypeTag(article.content_type);
  const date = document.createElement("span");
  date.className = "article-review-date";
  date.textContent = article.reviewed_at ? new Date(article.reviewed_at).toLocaleDateString() : "";
  header.append(tag, date);
  card.appendChild(header);

  const title = document.createElement("div");
  title.className = "article-review-published-title";
  title.textContent = article.title;
  card.appendChild(title);

  const urlLink = document.createElement("a");
  urlLink.className = "article-review-url";
  urlLink.href = article.source_url;
  urlLink.target = "_blank";
  urlLink.rel = "noopener";
  urlLink.textContent = article.source_url;
  card.appendChild(urlLink);

  const statusEl = document.createElement("p");
  statusEl.className = "status-info article-review-status";
  card.appendChild(statusEl);

  const unpublishBtn = document.createElement("button");
  unpublishBtn.type = "button";
  unpublishBtn.className = "secondary";
  unpublishBtn.textContent = "Unpublish";
  unpublishBtn.addEventListener("click", () =>
    handleSetArticleStatus(article.id, "pending_review", null, null, statusEl, unpublishBtn,
      `Unpublish "${article.title}"? It will be removed from the News Feed and returned to the pending queue.`));
  card.appendChild(unpublishBtn);

  return card;
}

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("admin-login-btn").addEventListener("click", handleLogin);
  document.getElementById("admin-password-input").addEventListener("keydown", (event) => {
    if (event.key === "Enter") handleLogin();
  });
  document.getElementById("admin-logout-btn").addEventListener("click", handleLogout);

  document.getElementById("issue-tier-1-btn").addEventListener("click", () => setSelectedIssueTier(1));
  document.getElementById("issue-tier-2-btn").addEventListener("click", () => setSelectedIssueTier(2));
  document.getElementById("issue-submit-btn").addEventListener("click", handleIssue);
  document.getElementById("issued-code-copy-btn").addEventListener("click", handleCopyCode);

  document.getElementById("add-admin-btn").addEventListener("click", handleAddAdmin);

  checkExistingSession();
});
