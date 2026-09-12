// Thin wrapper around the Supabase JS client, mirroring shared/src/commonMain/kotlin/com/
// cookinlet/belugas/SupabaseClient.kt's SupabaseApi so this web version's data shape and
// permissions stay in step with the native app rather than diverging.
const supabaseClient = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Every anon-readable sightings column EXCEPT subscriber_id -- anon has no SELECT grant on that
// column at all (supabase/migrations/20260903010000_add_observer_tier_system.sql's column-level
// lockdown), so a bare `select('*')` would fail outright. Kept as an explicit list, same as
// SupabaseApi.SIGHTING_LIST_COLUMNS on the native side.
const SIGHTING_LIST_COLUMNS = [
  "id", "lat", "lng", "heading", "heading_degrees", "heading_source",
  "heading_accuracy_degrees", "distance_bucket", "distance_radius_meters",
  "count_whites", "count_greys", "count_calves", "count_unknown",
  "observed_at_epoch_ms", "observer_type", "is_geofence_verified", "photo_url",
  "whale_lat", "whale_lng", "uncertainty_radius_meters", "uncertainty_bucket",
  "travel_bearing_degrees", "travel_bearing_source", "position_source", "observer_tier"
].join(",");

// Matches SightingRecord.isHighConfidence in shared/src/commonMain/kotlin/com/cookinlet/belugas/
// SightingRecord.kt exactly: a photo is direct evidence regardless of who logged it; absent
// that, tier 1/2 (credentialed observer) is next-best. Used by both the map and list "VERIFIED
// ONLY" toggles (SightingsMapScreen.kt / OfflineSightingsList in App.kt each have their own
// independent copy of this same toggle natively) -- never applied to this device's own queued-
// but-not-yet-synced sightings, same as native (a local sighting has no observer_tier yet at all,
// server-computed only on insert).
function isHighConfidence(sighting) {
  return sighting.photo_url != null || sighting.observer_tier === 1 || sighting.observer_tier === 2;
}

/**
 * Fetches recent sightings, newest first. Returns [] on failure -- callers show an inline error
 * separately rather than crash the map/list view.
 */
async function fetchRecentSightings(limit = 200) {
  const { data, error } = await supabaseClient
    .from("sightings")
    .select(SIGHTING_LIST_COLUMNS)
    .order("observed_at_epoch_ms", { ascending: false })
    .limit(limit);

  if (error) {
    console.error("SIGHTINGS_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * True when `error` looks like a connectivity failure (DNS/offline/timeout) rather than a real
 * response from the server (a permissions error, a bad value, etc.) -- the offline queue
 * (offline-queue.js) only ever queues the former; the latter is shown to the user as a genuine
 * failure, since retrying it later can't possibly help.
 *
 * There's no fully reliable way to tell these apart from the error object alone (supabase-js
 * normalizes both into a similarly-shaped {message} rather than exposing the raw fetch
 * TypeError), so this leans on navigator.onLine plus the common browser fetch-failure strings
 * (Chrome/Firefox: "Failed to fetch", Safari: "Load failed").
 */
function isNetworkError(error) {
  if (!error) return false;
  if (navigator.onLine === false) return true;
  const message = String(error.message || error).toLowerCase();
  return message.includes("failed to fetch") || message.includes("load failed") || message.includes("networkerror");
}

/**
 * Uploads a photo blob to the sighting-photos bucket. Object path is "<id>.jpg", matching
 * SupabaseApi.uploadSightingPhoto's convention (upsert -- a retried queued upload under the same
 * id overwrites cleanly rather than erroring).
 */
async function uploadSightingPhoto(id, blob) {
  const objectPath = `${id}.jpg`;
  try {
    const { error } = await supabaseClient.storage
      .from(SIGHTING_PHOTOS_BUCKET)
      .upload(objectPath, blob, { upsert: true, contentType: "image/jpeg" });

    if (error) {
      console.error("PHOTO_UPLOAD_ERROR", error);
      return { ok: false, networkError: isNetworkError(error) };
    }
    const { data } = supabaseClient.storage.from(SIGHTING_PHOTOS_BUCKET).getPublicUrl(objectPath);
    return { ok: true, url: data?.publicUrl ?? null };
  } catch (e) {
    // A thrown (not returned) error here is almost always the underlying fetch() itself
    // rejecting -- i.e. genuinely offline, not a server response of any kind.
    console.error("PHOTO_UPLOAD_EXCEPTION", e);
    return { ok: false, networkError: true };
  }
}

/**
 * Inserts one new sighting row. `record` must already match the whale-position schema (see
 * buildSightingRecord in submit-view.js) -- this function does not transform it.
 *
 * Deliberately does NOT chain .select() after insert: PostgREST's `return=representation` would
 * try to SELECT every column of the inserted row, including subscriber_id, which anon has no
 * SELECT grant on at all -- that would fail the whole insert even though the write itself is
 * permitted. Same reasoning as SupabaseApi.postSighting on the native side, which also never
 * requests the row back.
 */
async function insertSighting(record) {
  try {
    const { error } = await supabaseClient.from("sightings").insert(record);
    if (error) {
      console.error("SIGHTING_INSERT_ERROR", error);
      return { ok: false, networkError: isNetworkError(error) };
    }
    return { ok: true };
  } catch (e) {
    console.error("SIGHTING_INSERT_EXCEPTION", e);
    return { ok: false, networkError: true };
  }
}

/**
 * Attempts to claim `code` onto this device's subscriber_id via the RPC's existing
 * rate-limited, anti-enumeration design (supabase/migrations/20260905010000_add_tier_code_rate_
 * limiting_and_identity_bind.sql) -- mirrors SupabaseApi.redeemTierCode/TierRedeemResult on the
 * native side exactly. The RPC normalizes both the typed code and the stored value server-side
 * (dashes/case-insensitive), so this never touches the input string itself.
 *
 * Every ordinary failure (bad code, already-used code, network/decode error) collapses to the
 * same "invalid" outcome -- deliberately, so this can't be used to probe which codes exist.
 * RATE_LIMITED is kept separate only because the RPC itself raises a distinct exception for it.
 */
async function redeemTierCode(code, subscriberId) {
  const { data, error } = await supabaseClient.rpc("redeem_tier_code", {
    p_code: code,
    p_subscriber_id: subscriberId
  });

  if (error) {
    if (error.message === "rate_limited") return { status: "RATE_LIMITED" };
    console.error("TIER_CODE_REDEEM_ERROR", error);
    return { status: "INVALID" };
  }
  if (data == null) return { status: "INVALID" };
  return { status: "SUCCESS", tier: data };
}

/**
 * This browser's persistent per-device id, generated once and kept in localStorage -- mirrors
 * AppPreferences.getOrCreateSubscriberId() on the native side. Write-only from this app's own
 * perspective too: sent at insert so the server's BEFORE INSERT trigger can compute
 * observer_tier, never read back (anon has no SELECT grant on subscriber_id).
 */
function getOrCreateSubscriberId() {
  const STORAGE_KEY = "belugas_subscriber_id";
  let id = localStorage.getItem(STORAGE_KEY);
  if (id) return id;

  id = (crypto.randomUUID ? crypto.randomUUID() : randomUuidV4Fallback());
  localStorage.setItem(STORAGE_KEY, id);
  return id;
}

// crypto.randomUUID() needs a secure context (fine on GitHub Pages/https, and on localhost for
// dev) and Safari >= 15.4 -- this covers the rare gap (very old iOS, or http:// local testing).
function randomUuidV4Fallback() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
