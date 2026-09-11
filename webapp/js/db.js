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
 * Uploads a photo blob to the sighting-photos bucket and returns its public URL, or null on
 * failure. Object path is "<id>.jpg", matching SupabaseApi.uploadSightingPhoto's convention.
 */
async function uploadSightingPhoto(id, blob) {
  const objectPath = `${id}.jpg`;
  const { error: uploadError } = await supabaseClient.storage
    .from(SIGHTING_PHOTOS_BUCKET)
    .upload(objectPath, blob, { upsert: true, contentType: "image/jpeg" });

  if (uploadError) {
    console.error("PHOTO_UPLOAD_ERROR", uploadError);
    return null;
  }

  const { data } = supabaseClient.storage.from(SIGHTING_PHOTOS_BUCKET).getPublicUrl(objectPath);
  return data?.publicUrl ?? null;
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
  const { error } = await supabaseClient.from("sightings").insert(record);
  if (error) {
    console.error("SIGHTING_INSERT_ERROR", error);
    return false;
  }
  return true;
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
