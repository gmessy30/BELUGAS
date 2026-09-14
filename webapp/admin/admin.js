// BELUGAS Admin -- issues observer tier codes (public.tier_roster), gated by Supabase Auth
// (email+password, accounts created manually in the dashboard) PLUS the separate tier_admins
// allow-list -- being a logged-in Supabase user is not enough on its own, only a user_id listed
// in tier_admins is (see supabase/migrations/20260921000000_add_tier_admin_rpcs.sql). Not linked
// from the main app's menu at all -- reachable only by knowing this URL.
//
// Its own Supabase client instance (separate from webapp/js/db.js's anon-only one) since this is
// the only page in the whole app that ever calls supabase.auth -- the main app has no login
// concept and never needs one.
const adminSupabase = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

let selectedIssueTier = 1;

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

  showAdminContent();
  renderTierCodesList(data);
  refreshPendingArticles();
  refreshRecentlyPublished();
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
    metaLine.textContent = `${contactText}Tier ${row.tier}`;

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
    revokeBtn.addEventListener("click", () => handleRevoke(row.code, row.name));

    item.append(info, statusEl, revokeBtn);
    container.appendChild(item);
  });
}

async function handleRevoke(code, name) {
  if (!confirm(`Revoke ${name}'s code? That device loses its tier immediately.`)) return;

  const { data, error } = await adminSupabase.rpc("revoke_tier_code", { p_code: code });
  if (error || !data) {
    alert("Couldn't revoke that code -- try again.");
    return;
  }
  await refreshTierCodesList();
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
    p_device: device || null
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
  showIssuedCode(code);
  await refreshTierCodesList();
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

  checkExistingSession();
});
