// Thin wrapper around the Supabase JS client, mirroring shared/src/commonMain/kotlin/com/
// cookinlet/belugas/SupabaseClient.kt's SupabaseApi so this web version's data shape and
// permissions stay in step with the native app rather than diverging.
const supabaseClient = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Every anon-readable sightings column EXCEPT subscriber_id -- anon has no SELECT grant on that
// column at all (supabase/migrations/20260903010000_add_observer_tier_system.sql's column-level
// lockdown), so a bare `select('*')` would fail outright. Kept as an explicit list, same as
// SupabaseApi.SIGHTING_LIST_COLUMNS on the native side.
const SIGHTING_LIST_COLUMNS_BASE = [
  "id", "lat", "lng", "heading", "heading_degrees", "heading_source",
  "heading_accuracy_degrees", "distance_bucket", "distance_radius_meters",
  "count_whites", "count_greys", "count_calves", "count_unknown",
  "observed_at_epoch_ms", "observer_type", "is_geofence_verified", "photo_url",
  "whale_lat", "whale_lng", "uncertainty_radius_meters", "uncertainty_bucket",
  "travel_bearing_degrees", "travel_bearing_source", "position_source", "observer_tier"
];

// Items 90/63: activities/activity_note (the ACTIVITY picker) and confirmed_at (tier-1
// confirmation -- NOT confirmed_by_subscriber_id, which gets the same anon-SELECT lockdown as
// the ORIGINAL subscriber_id column and for the identical reason: it identifies a specific
// device/observer's own action, not just public sighting data) all land in the SAME migration,
// so one shared capability flag below covers all three -- they can only ever be missing or
// present together.
const SIGHTING_LIST_COLUMNS_NEW = ["activities", "activity_note", "confirmed_at"];

// null = not yet determined, true/false once a real query has actually told us. Tried optimistically
// (assume the new columns exist) and only ever flipped to false by a real missing-column error --
// never re-tried upward afterward within one page load, since the schema doesn't change mid-session.
let sightingSchemaHasNewColumns = null;

function sightingListColumns() {
  const cols = sightingSchemaHasNewColumns === false
    ? SIGHTING_LIST_COLUMNS_BASE
    : [...SIGHTING_LIST_COLUMNS_BASE, ...SIGHTING_LIST_COLUMNS_NEW];
  return cols.join(",");
}

// Postgres/PostgREST's own "column does not exist" signature -- code 42703, or (PostgREST
// sometimes surfaces this as a plain message instead of a structured code) a message mentioning
// it directly. Used to tell "the migration hasn't been applied yet" apart from every other kind
// of query failure, which should NOT flip the capability flag (a transient network error here
// must not permanently downgrade every later request for the rest of the session).
function isMissingColumnError(error) {
  if (!error) return false;
  if (error.code === "42703") return true;
  return /column .* does not exist/i.test(error.message || "");
}

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
 *
 * Items 90/63: tries the new columns (activities/activity_note/confirmed_at) first -- if the
 * migration hasn't actually been applied yet, that specific request fails with a missing-column
 * error, which flips sightingSchemaHasNewColumns to false and retries ONCE with the base column
 * list, so the app keeps working (just without those fields) rather than breaking entirely until
 * the migration lands.
 */
async function fetchRecentSightings(limit = 200) {
  let { data, error } = await supabaseClient
    .from("sightings")
    .select(sightingListColumns())
    .order("observed_at_epoch_ms", { ascending: false })
    .limit(limit);

  if (error && isMissingColumnError(error) && sightingSchemaHasNewColumns !== false) {
    sightingSchemaHasNewColumns = false;
    ({ data, error } = await supabaseClient
      .from("sightings")
      .select(sightingListColumns())
      .order("observed_at_epoch_ms", { ascending: false })
      .limit(limit));
  } else if (!error && sightingSchemaHasNewColumns === null) {
    sightingSchemaHasNewColumns = true;
  }

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
// Item 90: strips activities/activity_note from a record about to be inserted -- used only once
// the schema's already been confirmed NOT to have those columns yet (sightingSchemaHasNewColumns
// === false), so a submission from before the migration lands still succeeds, just without those
// two fields, rather than failing outright on a missing column.
function stripNewSightingFields(record) {
  const { activities, activity_note, ...rest } = record;
  return rest;
}

async function insertSighting(record) {
  try {
    const payload = sightingSchemaHasNewColumns === false ? stripNewSightingFields(record) : record;
    let { error } = await supabaseClient.from("sightings").insert(payload);

    if (error && isMissingColumnError(error) && sightingSchemaHasNewColumns !== false) {
      sightingSchemaHasNewColumns = false;
      ({ error } = await supabaseClient.from("sightings").insert(stripNewSightingFields(record)));
    } else if (!error && sightingSchemaHasNewColumns === null) {
      sightingSchemaHasNewColumns = true;
    }

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
 * Fetches every watched zone's shading geometry for the map's river-shading fill -- matches
 * SupabaseApi.getWatchedZoneShadingAreas exactly: a zero-argument RPC, no subscriber/location
 * filtering of any kind. Every viewer sees every server-flagged is_banner_watched zone, which is
 * the whole point (confirmed against SightingsMapScreen.kt/App.kt's own comments: "the map
 * shading needs it regardless of whether the banner itself is currently shown").
 */
async function getWatchedZoneShadingAreas() {
  const { data, error } = await supabaseClient.rpc("get_watched_zone_shading_areas");
  if (error) {
    console.error("WATCHED_ZONE_SHADING_AREAS_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * Raw sighting-recency facts for every watched zone, no location/subscription involved --
 * matches SupabaseApi.getWatchedZoneStatuses. Returns null on failure (not the same as a real
 * empty list) so callers can leave their last-known data alone rather than wipe it to nothing on
 * a transient failure -- same null-vs-empty distinction native's own comment insists on.
 */
async function getWatchedZoneStatuses(lookbackMs) {
  const { data, error } = await supabaseClient.rpc("get_watched_zone_statuses", { p_lookback_ms: lookbackMs });
  if (error) {
    console.error("WATCHED_ZONE_STATUSES_FETCH_ERROR", error);
    return null;
  }
  return data ?? [];
}

/**
 * Kenai's real tide-cycle-aware RED/YELLOW/BLUE state -- matches SupabaseApi.
 * getKenaiPresenceState. Null on any failure; callers must keep showing their own last-known
 * state rather than treat null as "no data."
 */
async function getKenaiPresenceState() {
  const { data, error } = await supabaseClient.rpc("get_kenai_presence_state");
  if (error) {
    console.error("KENAI_PRESENCE_STATE_FETCH_ERROR", error);
    return null;
  }
  return (data && data[0]) ?? null;
}

/**
 * Every watched zone within proximityMeters of lat/lng, nearest first -- matches SupabaseApi.
 * findNearbyWatchedZones. Empty on failure or if none is that close.
 */
async function findNearbyWatchedZones(lat, lng, proximityMeters) {
  const { data, error } = await supabaseClient.rpc("find_nearby_watched_zone", {
    p_lat: lat,
    p_lng: lng,
    p_proximity_meters: proximityMeters
  });
  if (error) {
    console.error("NEARBY_WATCHED_ZONE_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * Every watched zone this subscriber is relevant to via an active zone-kind subscription --
 * matches SupabaseApi.getRelevantWatchedZoneIds exactly, including the containment-aware match
 * (a subscription to "Entire Inlet" also covers Kenai) -- that matching is done entirely
 * server-side by this RPC (get_relevant_watched_zone_id), so this client never needs its own
 * polygon-containment logic. Empty on failure or no match.
 */
async function getRelevantWatchedZoneIds(subscriberId) {
  const { data, error } = await supabaseClient.rpc("get_relevant_watched_zone_id", { p_subscriber_id: subscriberId });
  if (error) {
    console.error("RELEVANT_WATCHED_ZONE_ID_FETCH_ERROR", error);
    return [];
  }
  return (data ?? []).map((row) => row.zone_id);
}

/**
 * The curated ~9 MVP zone presets -- matches SupabaseApi.getZones. Fetched live, not hardcoded,
 * same as native: a new/adjusted zone shows up here without a client release.
 */
async function getZones(regionId) {
  const { data, error } = await supabaseClient
    .from("zones")
    .select("id, slug, name, region_id, display_order")
    .eq("region_id", regionId)
    .order("display_order", { ascending: true });
  if (error) {
    console.error("ZONES_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * The curated point+radius presets -- matches SupabaseApi.getPointPresets. lat/lng come from the
 * generated columns migration; empty (not an error) if that hasn't been applied yet, same
 * degrade path native's own comment documents.
 */
async function getPointPresets(regionId) {
  const { data, error } = await supabaseClient
    .from("point_presets")
    .select("id, slug, name, region_id, lat, lng, default_radius_meters, display_order")
    .eq("region_id", regionId)
    .order("display_order", { ascending: true });
  if (error) {
    console.error("POINT_PRESETS_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * This subscriber's own subscriptions (all three kinds), newest first -- matches SupabaseApi.
 * getSubscriptions. point/custom_polygon deliberately excluded from the select (same reasoning
 * as native: PostgREST returns geometry as nested GeoJSON, never needed back once created).
 */
async function getSubscriptions(subscriberId) {
  const { data, error } = await supabaseClient
    .from("subscriptions")
    .select("id, subscriber_id, kind, confidence_filter, is_active, label, zone_id, radius_meters, expires_at, created_at")
    .eq("subscriber_id", subscriberId)
    .order("created_at", { ascending: false });
  if (error) {
    console.error("SUBSCRIPTIONS_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * Which of this subscriber's own 'verified_only' subscriptions overlap one of their own 'all'
 * subscriptions -- matches SupabaseApi.getConfidenceFilterOverlaps. Empty on failure or no
 * overlap.
 */
async function getConfidenceFilterOverlaps(subscriberId) {
  const { data, error } = await supabaseClient.rpc("get_confidence_filter_overlaps", { p_subscriber_id: subscriberId });
  if (error) {
    console.error("CONFIDENCE_FILTER_OVERLAPS_FETCH_ERROR", error);
    return new Set();
  }
  return new Set((data ?? []).map((row) => row.subscription_id));
}

// EWKT (WKT with an explicit SRID prefix) for the two subscription geometry columns -- matches
// SupabaseClient.kt's own ewktPoint/ewktPolygon exactly, including the explicit "SRID=4326;"
// prefix (without it, Postgres/PostGIS's typmod check on a geometry(Polygon,4326)-declared
// column rejects incoming WKT that defaults to SRID 0).
function ewktPoint(lat, lng) {
  return `SRID=4326;POINT(${lng} ${lat})`;
}

function ewktPolygon(vertices) {
  const ring = [...vertices, vertices[0]];
  const coords = ring.map(([lat, lng]) => `${lng} ${lat}`).join(", ");
  return `SRID=4326;POLYGON((${coords}))`;
}

/**
 * Creates a kind='zone' subscription. Matches SupabaseApi.createZoneSubscription, including the
 * ALREADY_EXISTS outcome for the partial unique index on (subscriber_id, zone_id) where
 * kind='zone' -- a 409 from Postgrest.
 */
async function createZoneSubscription(subscriberId, zoneId, confidenceFilter) {
  const { error } = await supabaseClient.from("subscriptions").insert({
    subscriber_id: subscriberId,
    kind: "zone",
    confidence_filter: confidenceFilter,
    zone_id: zoneId
  });
  if (error) {
    if (error.code === "23505" || error.status === 409) return "ALREADY_EXISTS";
    console.error("SUBSCRIPTION_CREATE_ERROR", error);
    return "ERROR";
  }
  return "SUCCESS";
}

/** Matches SupabaseApi.createPointSubscription. expiresAtEpochMs null = permanent. */
async function createPointSubscription(subscriberId, lat, lng, radiusMeters, confidenceFilter, label, expiresAtEpochMs) {
  const { error } = await supabaseClient.from("subscriptions").insert({
    subscriber_id: subscriberId,
    kind: "point_radius",
    confidence_filter: confidenceFilter,
    label,
    point: ewktPoint(lat, lng),
    radius_meters: radiusMeters,
    expires_at: expiresAtEpochMs != null ? new Date(expiresAtEpochMs).toISOString() : null
  });
  if (error) {
    console.error("SUBSCRIPTION_CREATE_ERROR", error);
    return false;
  }
  return true;
}

/** Matches SupabaseApi.createPolygonSubscription. vertices: array of [lat, lng], not pre-closed. */
async function createPolygonSubscription(subscriberId, vertices, confidenceFilter) {
  const { error } = await supabaseClient.from("subscriptions").insert({
    subscriber_id: subscriberId,
    kind: "custom_polygon",
    confidence_filter: confidenceFilter,
    custom_polygon: ewktPolygon(vertices)
  });
  if (error) {
    console.error("SUBSCRIPTION_CREATE_ERROR", error);
    return false;
  }
  return true;
}

/** Matches SupabaseApi.deleteSubscription. */
async function deleteSubscription(id) {
  const { error } = await supabaseClient.from("subscriptions").delete().eq("id", id);
  if (error) {
    console.error("SUBSCRIPTION_DELETE_ERROR", error);
    return false;
  }
  return true;
}

/**
 * Registers (or refreshes) this device's FCM token -- matches SupabaseApi.registerDeviceToken
 * exactly (supabase/migrations/20260823000000_add_device_tokens_and_notify_trigger.sql's
 * device_tokens table): upserts on fcm_token so re-registering the same token (a silent refresh
 * on every app load, or this app's own "Enable Alerts" re-tap) is a no-op rather than an error,
 * and keeps subscriber_id in sync so match_notification_recipients can target this device.
 */
async function registerDeviceToken(token, subscriberId) {
  const { error } = await supabaseClient.from("device_tokens").upsert(
    { fcm_token: token, subscriber_id: subscriberId },
    { onConflict: "fcm_token" }
  );
  if (error) {
    console.error("DEVICE_TOKEN_REGISTER_ERROR", error);
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

/**
 * Fetches published articles of one content type (news or research paper), newest first --
 * matches SupabaseApi.getArticles exactly. Unapproved (pending_review) submissions are excluded
 * by RLS server-side, not filtered here -- this only ever sees what's actually public.
 */
async function getArticles(contentType) {
  const { data, error } = await supabaseClient
    .from("articles")
    .select("id, title, summary, source_url, content_type, status")
    .eq("content_type", contentType)
    .eq("status", "published")
    .order("created_at", { ascending: false });

  if (error) {
    console.error("ARTICLES_FETCH_ERROR", error);
    return [];
  }
  return data ?? [];
}

/**
 * Submits a user-suggested article/paper. Always lands as pending_review (the RLS insert policy
 * enforces this server-side too, via a WITH CHECK, not just relying on the column default) --
 * matches SupabaseApi.submitArticle exactly. Moderation itself (publish/reject) is now a real
 * in-app admin queue (item 91, webapp/admin/), not a manual dashboard edit.
 *
 * Item 95: returns a result object, not a plain boolean -- {ok:false} alone can't tell the caller
 * WHY it failed, which is exactly what made every failure here render as the same generic "check
 * your connection" text regardless of cause. `error.code` is the distinguishing signal: a real
 * PostgREST/Postgres rejection (RLS violation, constraint, etc.) always carries a non-empty
 * SQLSTATE-shaped code (e.g. "42501"); supabase-js's own fetch wrapper catches a genuine
 * network-level failure (offline, DNS, CORS, a dropped connection) and reports it as an error
 * object with an EMPTY code and just a raw message like "Failed to fetch" -- confirmed against
 * this exact client version (dist/umd/supabase.min.js@2 loaded in index.html) by forcing both
 * shapes live: an explicit RLS violation (status:'published' in the payload) came back
 * {code:"42501", message:"new row violates row-level security policy..."}, matching this.
 */
async function submitArticle(title, sourceUrl, summary, submittedBy, contentType) {
  const { error } = await supabaseClient.from("articles").insert({
    title,
    summary,
    source_url: sourceUrl,
    submitted_by: submittedBy,
    content_type: contentType
  });

  if (error) {
    console.error("ARTICLE_SUBMIT_ERROR", error);
    return {
      ok: false,
      isNetworkError: !error.code,
      message: error.message || null
    };
  }
  return { ok: true };
}

/**
 * Real-coastline-curve check for a point the client's own well-sourced-zone/sparse-point
 * fallbacks (geofence.js's isWithinGeofenceBuffer) couldn't resolve -- matches SupabaseApi.
 * isPointWithinCoastlineChannel exactly. Returns null both when the RPC has no coastline_traces
 * coverage near this point and on any network/decode failure -- callers must treat null as "no
 * additional evidence either way," not a rejection. Unlike the Kotlin client (which has to
 * parse the raw response body to avoid a non-null generic constraint), supabase-js just hands
 * back the true/false/null value directly.
 */
async function isPointWithinCoastlineChannel(lat, lng) {
  const { data, error } = await supabaseClient.rpc("is_point_within_coastline_channel", { p_lat: lat, p_lng: lng });
  if (error) {
    console.error("COASTLINE_CHANNEL_CHECK_ERROR", error);
    return null;
  }
  return data;
}

// Item 47: the last RPC error isKenaiDepartureReporter hit, if any -- the function's own return
// value collapses "really not tier-1" and "the RPC failed" into the same `false` (correct for the
// real UI, which should fail closed either way), so this is the one place that distinction
// survives, read by tier-code.js's debug overlay to confirm the check isn't failing silently.
// Cleared on a call that succeeds (whether the answer was true or false), not just left stale.
let lastDepartureTierCheckError = null;

/**
 * Item 40: matches SupabaseApi.isKenaiDepartureReporter exactly -- purely a visibility signal
 * for the departure-report section (tier-code.js), narrower than exposing get_observer_tier's
 * actual tier number to anon. report_kenai_departure re-checks tier itself server-side
 * regardless of what this returns, so nothing here is ever trusted for enforcement. Fails closed
 * (false) on any error -- a network failure should hide the button, not show it and then fail
 * confusingly on submit.
 */
async function isKenaiDepartureReporter(subscriberId) {
  const { data, error } = await supabaseClient.rpc("is_kenai_departure_reporter", { p_subscriber_id: subscriberId });
  if (error) {
    console.error("DEPARTURE_TIER_CHECK_ERROR", error);
    lastDepartureTierCheckError = error.message || String(error);
    return false;
  }
  lastDepartureTierCheckError = null;
  return data === true;
}

/**
 * Item 40: matches SupabaseApi.reportKenaiDeparture exactly -- files a departure report at the
 * caller's current lat/lng. Returns whether the server actually accepted it; false covers every
 * rejection reason uniformly (not tier-1, outside the viewing polygon, phase isn't RED right
 * now), matching report_kenai_departure's own single-boolean design
 * (supabase/migrations/20260913000000_add_kenai_departure_report.sql) -- the client's own tier/
 * polygon checks already cover the ordinary "why is this button even showing" cases.
 */
async function reportKenaiDeparture(subscriberId, lat, lng) {
  const { data, error } = await supabaseClient.rpc("report_kenai_departure", {
    p_subscriber_id: subscriberId,
    p_lat: lat,
    p_lng: lng
  });
  if (error) {
    console.error("DEPARTURE_REPORT_ERROR", error);
    return false;
  }
  return data === true;
}

// Item 63: purely a visibility signal for whether to show CONFIRM SIGHTING at all -- same
// "visibility is UX, enforcement is server-side" split as isKenaiDepartureReporter right above
// (confirm_sighting re-checks tier itself server-side regardless of what this returns). Checked
// ONCE per app load (refreshTierOneObserverStatus, called from app.js's own splash-gate
// Promise.all) rather than per sighting row -- it's the same device/subscriber_id for every row
// shown, so there's nothing to gain from re-checking it repeatedly. Fails closed (false) on error.
let cachedIsTierOneObserver = false;

async function isTierOneObserver(subscriberId) {
  const { data, error } = await supabaseClient.rpc("is_tier_one_observer", { p_subscriber_id: subscriberId });
  if (error) {
    console.error("TIER_ONE_CHECK_ERROR", error);
    return false;
  }
  return data === true;
}

async function refreshTierOneObserverStatus() {
  cachedIsTierOneObserver = await isTierOneObserver(getOrCreateSubscriberId());
}

/**
 * Item 63: the sole write path for confirmed_by_subscriber_id/confirmed_at -- confirm_sighting
 * itself re-checks tier, refuses an already-confirmed row, and refuses the caller's own row,
 * entirely server-side (this app has no reliable client-side way to know "is this row mine" for
 * an arbitrary fetched sighting anyway, since subscriber_id itself is never anon-readable). false
 * covers every refusal reason uniformly, matching reportKenaiDeparture's own single-boolean design.
 */
async function confirmSighting(sightingId, subscriberId) {
  const { data, error } = await supabaseClient.rpc("confirm_sighting", {
    p_sighting_id: sightingId,
    p_subscriber_id: subscriberId
  });
  if (error) {
    console.error("CONFIRM_SIGHTING_ERROR", error);
    return false;
  }
  return data === true;
}
