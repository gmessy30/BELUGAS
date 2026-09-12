// News Feed page -- ports NewsFeedScreen.kt. Real data source: the public.articles table via
// the Supabase JS client (anon key) -- the exact same table/RPC path SupabaseApi.getArticles/
// submitArticle use natively (see db.js), confirmed against supabase/migrations/
// 20260823120000_add_articles.sql's RLS (published rows are public-readable; anon can insert but
// only ever landing as pending_review, enforced by a WITH CHECK, not just a column default) --
// not a curated or hardcoded feed. A submission here shows up in the exact same moderation queue
// a native submission would, and once approved, shows up in both apps identically.
let currentArticleType = "news";
let suggestArticleType = "news";

function initNewsFeedPage() {
  document.getElementById("news-back-btn").addEventListener("click", () => {
    navigateBack();
  });

  document.getElementById("news-filter-news").addEventListener("click", () => setArticleType("news"));
  document.getElementById("news-filter-research").addEventListener("click", () => setArticleType("research_paper"));

  document.getElementById("suggest-article-btn").addEventListener("click", openSuggestArticleModal);
  document.getElementById("suggest-cancel-btn").addEventListener("click", closeSuggestArticleModal);
  document.getElementById("suggest-submit-btn").addEventListener("click", submitSuggestedArticle);
  document.getElementById("suggest-type-news").addEventListener("click", () => setSuggestArticleType("news"));
  document.getElementById("suggest-type-research").addEventListener("click", () => setSuggestArticleType("research_paper"));
}

function setArticleType(type) {
  currentArticleType = type;
  document.getElementById("news-filter-news").classList.toggle("active", type === "news");
  document.getElementById("news-filter-research").classList.toggle("active", type === "research_paper");
  loadArticles();
}

async function loadArticles() {
  const container = document.getElementById("news-articles");
  container.innerHTML = '<p class="empty-state">Loading…</p>';

  const articles = await getArticles(currentArticleType);

  container.innerHTML = "";
  if (articles.length === 0) {
    const message = currentArticleType === "news" ? "No news articles yet." : "No research papers yet.";
    container.innerHTML = `<p class="empty-state">${message}</p>`;
    return;
  }
  articles.forEach((article) => container.appendChild(articleCard(article)));
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

  const ok = await submitArticle(title, url, note || null, name || null, suggestArticleType);

  submitBtn.disabled = false;
  if (ok) {
    statusEl.textContent = "Thanks! Submitted for review -- it'll appear here once approved.";
    statusEl.className = "status-info";
    setTimeout(closeSuggestArticleModal, 1500);
  } else {
    statusEl.textContent = "Couldn't submit -- check your connection and try again.";
    statusEl.className = "status-error";
  }
}
