// News Feed page -- ports NewsFeedScreen.kt. Real data source: the public.articles table via
// the Supabase JS client (anon key) -- the exact same table/RPC path SupabaseApi.getArticles/
// submitArticle use natively (see db.js), confirmed against supabase/migrations/
// 20260823120000_add_articles.sql's RLS (published rows are public-readable; anon can insert but
// only ever landing as pending_review, enforced by a WITH CHECK, not just a column default) --
// not a curated or hardcoded feed. A submission here shows up in the exact same moderation queue
// a native submission would, and once approved, shows up in both apps identically.
let currentArticleType = "news";
let suggestArticleType = "news";
// Item 93: the raw fetch result for the CURRENT chip, before any search filtering -- kept
// separate from what's actually rendered so switching the search query never needs a network
// refetch, only a re-filter of what's already loaded.
let lastLoadedArticles = [];
let currentSearchQuery = "";

function initNewsFeedPage() {
  document.getElementById("news-back-btn").addEventListener("click", () => {
    navigateBack();
  });

  document.getElementById("news-filter-news").addEventListener("click", () => setArticleType("news"));
  document.getElementById("news-filter-research").addEventListener("click", () => setArticleType("research_paper"));

  // Item 93: deliberately does NOT call loadArticles/refetch -- typing only ever re-filters
  // lastLoadedArticles (renderFilteredArticles), same list switching chips already fetched.
  document.getElementById("news-search-input").addEventListener("input", (event) => {
    currentSearchQuery = event.target.value;
    updateNewsSearchClearBtnVisibility();
    renderFilteredArticles();
  });
  document.getElementById("news-search-clear-btn").addEventListener("click", () => {
    const input = document.getElementById("news-search-input");
    input.value = "";
    currentSearchQuery = "";
    updateNewsSearchClearBtnVisibility();
    renderFilteredArticles();
    input.focus();
  });

  document.getElementById("suggest-article-btn").addEventListener("click", openSuggestArticleModal);
  document.getElementById("suggest-cancel-btn").addEventListener("click", closeSuggestArticleModal);
  document.getElementById("suggest-submit-btn").addEventListener("click", submitSuggestedArticle);
  document.getElementById("suggest-type-news").addEventListener("click", () => setSuggestArticleType("news"));
  document.getElementById("suggest-type-research").addEventListener("click", () => setSuggestArticleType("research_paper"));
}

function updateNewsSearchClearBtnVisibility() {
  document.getElementById("news-search-clear-btn").hidden = currentSearchQuery.length === 0;
}

// Item 93: switching NEWS/RESEARCH PAPERS keeps the current search query (per the request) --
// only re-fetches for the new content_type and re-applies whatever's already typed, never clears
// the input itself.
function setArticleType(type) {
  currentArticleType = type;
  document.getElementById("news-filter-news").classList.toggle("active", type === "news");
  document.getElementById("news-filter-research").classList.toggle("active", type === "research_paper");
  loadArticles();
}

async function loadArticles() {
  const container = document.getElementById("news-articles");
  container.innerHTML = '<p class="empty-state">Loading…</p>';

  lastLoadedArticles = await getArticles(currentArticleType);
  renderFilteredArticles();
}

// Item 93: plain case-insensitive substring match across title/summary/source_url -- "source/
// domain" from the request is covered by source_url directly (a domain is just a substring of
// its own URL, e.g. searching "noaa" matches ".../noaa.gov/..."). Client-side only for now,
// filtering whatever loadArticles already fetched -- if the feed ever grows large enough for this
// to matter, swap to a Postgres full-text query in getArticles (db.js) instead.
function articleMatchesSearch(article, query) {
  if (!query) return true;
  return (
    (article.title || "").toLowerCase().includes(query) ||
    (article.summary || "").toLowerCase().includes(query) ||
    (article.source_url || "").toLowerCase().includes(query)
  );
}

function renderFilteredArticles() {
  const container = document.getElementById("news-articles");
  const query = currentSearchQuery.trim().toLowerCase();
  const filtered = lastLoadedArticles.filter((article) => articleMatchesSearch(article, query));

  container.innerHTML = "";
  if (filtered.length === 0) {
    if (query) {
      container.innerHTML = '<p class="empty-state">No matches.</p>';
    } else {
      const message = currentArticleType === "news" ? "No news articles yet." : "No research papers yet.";
      container.innerHTML = `<p class="empty-state">${message}</p>`;
    }
    return;
  }
  filtered.forEach((article) => container.appendChild(articleCard(article)));
}

function articleCard(article) {
  const card = document.createElement("a");
  card.className = "article-card";
  card.href = article.source_url;
  card.target = "_blank";
  card.rel = "noopener";

  const title = document.createElement("div");
  title.className = "article-title";
  title.textContent = article.title;
  card.appendChild(title);

  if (article.summary) {
    const summary = document.createElement("div");
    summary.className = "article-summary";
    summary.textContent = article.summary;
    card.appendChild(summary);
  }

  const openLink = document.createElement("div");
  openLink.className = "article-open-link";
  openLink.textContent = "Open link →";
  card.appendChild(openLink);

  return card;
}

// Called from the main menu's "News Feed" item.
function openNewsFeedPage() {
  document.getElementById("news-feed-page").hidden = false;
  // Item 93: a fresh ENTRY into this page (as opposed to switching chips while already on it)
  // starts with a clear search box -- "keep the query while switching chips" only covers the
  // latter.
  document.getElementById("news-search-input").value = "";
  currentSearchQuery = "";
  updateNewsSearchClearBtnVisibility();
  setArticleType("news");
}

function setSuggestArticleType(type) {
  suggestArticleType = type;
  document.getElementById("suggest-type-news").classList.toggle("active", type === "news");
  document.getElementById("suggest-type-research").classList.toggle("active", type === "research_paper");
}

function openSuggestArticleModal() {
  document.getElementById("suggest-title-input").value = "";
  document.getElementById("suggest-url-input").value = "";
  document.getElementById("suggest-note-input").value = "";
  document.getElementById("suggest-name-input").value = "";
  setSuggestArticleType(currentArticleType);
  const statusEl = document.getElementById("suggest-status");
  statusEl.textContent = "";
  document.getElementById("suggest-article-modal").hidden = false;
  pushNavLayer("suggest-article-modal", () => {
    document.getElementById("suggest-article-modal").hidden = true;
  });
}

// Bound to the cancel button AND called after a successful submit (via setTimeout below) -- both
// just pop the layer pushed in openSuggestArticleModal, whose onPop does the actual hiding, same
// as every other in-app "close" action (see nav-stack.js).
function closeSuggestArticleModal() {
  navigateBack();
}

// Submitted items land as pending_review and won't show up here until approved -- same as
// native, nothing to refresh after a successful submit, just close the form.
async function submitSuggestedArticle() {
  const title = document.getElementById("suggest-title-input").value.trim();
  const url = document.getElementById("suggest-url-input").value.trim();
  const note = document.getElementById("suggest-note-input").value.trim();
  const name = document.getElementById("suggest-name-input").value.trim();
  const statusEl = document.getElementById("suggest-status");

  if (!title || !url) {
    statusEl.textContent = "Title and URL are required.";
    statusEl.className = "status-error";
    return;
  }

  const submitBtn = document.getElementById("suggest-submit-btn");
  submitBtn.disabled = true;
  statusEl.textContent = "Submitting…";
  statusEl.className = "status-info";

  const result = await submitArticle(title, url, note || null, name || null, suggestArticleType);

  submitBtn.disabled = false;
  if (result.ok) {
    statusEl.textContent = "Thanks! Submitted for review -- it'll appear here once approved.";
    statusEl.className = "status-info";
    setTimeout(closeSuggestArticleModal, 1500);
  } else if (result.isNetworkError) {
    // Item 95: only the genuinely network-shaped failure (empty error.code, see submitArticle's
    // own comment) gets this text -- a real server-side rejection below gets its own message
    // instead, so a rejection never again reads as a connectivity problem.
    statusEl.textContent = "Couldn't submit -- check your connection and try again.";
    statusEl.className = "status-error";
  } else {
    statusEl.textContent = result.message
      ? `Couldn't submit: ${result.message}`
      : "Couldn't submit -- the server rejected this submission.";
    statusEl.className = "status-error";
  }
}
