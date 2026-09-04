package com.cookinlet.belugas

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

// Public bucket created by supabase/migrations/20260818000000_add_photo_url_to_sightings.sql
const val SIGHTING_PHOTOS_BUCKET = "sighting-photos"

// SUPABASE_URL / SUPABASE_ANON_KEY come from the generated SupabaseSecrets.kt
// (see shared/build.gradle.kts), sourced from local.properties or CI env vars.

val jsonConfig = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

// Shared KMP Supabase Client instance
val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_URL,
    supabaseKey = SUPABASE_ANON_KEY
) {
    install(Postgrest)
    install(Storage)
    install(Realtime)
    defaultSerializer = KotlinXSerializer(jsonConfig)
    // Default (10s) is sized for lightweight JSON calls and applies uniformly to every
    // request this client makes, including Storage photo uploads -- a multi-MB camera photo
    // on a slow/congested cellular connection can easily exceed that, surfacing as a spurious
    // "will retry" even though nothing is actually broken server-side.
    requestTimeout = 60.seconds
}

// Data model matching our Supabase 'sightings' table schema
enum class ObserverType { SELF, OTHER }

// Matches supabase/migrations/20260823000000_add_device_tokens_and_notify_trigger.sql, plus
// the subscriber_id link added in
// supabase/migrations/20260829010000_add_subscription_matching.sql -- Stage 3's
// match_notification_recipients RPC joins on it to find a subscriber's device(s). Nullable
// on the table (and here) since a device that hasn't re-registered since that migration
// shipped won't have one yet -- the RPC treats that the same as "no active subscriptions"
// (broadcast fallback), so nothing silently stops getting notified during rollout.
@Serializable
private data class DeviceTokenRecord(
    @SerialName("fcm_token")
    val fcmToken: String,
    @SerialName("subscriber_id")
    val subscriberId: String
)

// Matches supabase/migrations/20260823120000_add_articles.sql. "SELF" is not involved here --
// content_type distinguishes general news from research papers so the News Feed screen can
// keep the two in separate sections/tabs rather than mixed together.
enum class ArticleContentType(val dbValue: String) {
    NEWS("news"),
    RESEARCH_PAPER("research_paper")
}

// Matches supabase/migrations/20260819000000_add_notification_zones_and_subscriptions.sql +
// the seeded rows in 20260819130000_seed_notification_zones_and_point_presets.sql. `boundary`
// is deliberately not modeled here -- the app never needs the raw polygon client-side, only
// the slug to hand back to the export_sightings RPC, which does containment in PostGIS.
@Serializable
data class ZoneRecord(
    val id: String = "",
    val slug: String,
    val name: String,
    @SerialName("region_id")
    val regionId: String,
    @SerialName("display_order")
    val displayOrder: Int = 0
)

// Matches public.point_presets, plus the lat/lng generated columns added in
// supabase/migrations/20260829000000_add_point_preset_coordinates.sql -- NOT yet applied to the
// live project as of this writing (see that migration's header). Until it's applied, selecting
// these columns fails and getPointPresets() falls back to an empty list, same degrade path
// getZones() already has for a region with no seeded zones.
@Serializable
data class PointPresetRecord(
    val id: String = "",
    val slug: String,
    val name: String,
    @SerialName("region_id")
    val regionId: String,
    val lat: Double,
    val lng: Double,
    @SerialName("default_radius_meters")
    val defaultRadiusMeters: Double,
    @SerialName("display_order")
    val displayOrder: Int = 0
)

// The `confidence_filter` values a zone subscription can be created with. Matches
// public.subscription_confidence_filter in
// supabase/migrations/20260819000000_add_notification_zones_and_subscriptions.sql.
enum class SubscriptionConfidenceFilter(val dbValue: String, val label: String) {
    ALL("all", "All Sightings"),
    VERIFIED_ONLY("verified_only", "Verified Observer Only")
}

// Matches the columns Stage 1 + Stage 2 (all three subscription kinds) touch on
// public.subscriptions. `updated_at` isn't modeled -- nothing yet edits an existing
// subscription, only creates/deletes.
//
// `point`/`customPolygon` are write-only in practice: on create, they carry an EWKT string
// (e.g. "SRID=4326;POINT(lng lat)") that Postgres/PostGIS casts into the geography/geometry
// column. On a GET they'd decode PostgREST's default hex-WKB text for that column into this
// same String field -- harmless (it still decodes, just as an opaque string) but never used,
// since the WATCHING list only ever displays `label` + `radiusMeters`/`expiresAt` for a
// point-kind subscription, matching the same "never need to parse the raw geometry back"
// precedent as zones.boundary (see ZoneRecord above).
@Serializable
data class SubscriptionRecord(
    val id: String = "",
    @SerialName("subscriber_id")
    val subscriberId: String,
    val kind: String,
    @SerialName("confidence_filter")
    val confidenceFilter: String = SubscriptionConfidenceFilter.ALL.dbValue,
    @SerialName("is_active")
    val isActive: Boolean = true,
    // User-facing display name. Null for 'zone' (falls back to the zone's own name) and
    // 'custom_polygon' (falls back to a generic "Custom area" label) -- set explicitly at
    // creation time only for 'point_radius' (the preset's name, or "Custom Point").
    val label: String? = null,
    @SerialName("zone_id")
    val zoneId: String? = null,
    val point: String? = null,
    @SerialName("custom_polygon")
    val customPolygon: String? = null,
    @SerialName("radius_meters")
    val radiusMeters: Double? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class ArticleRecord(
    val id: String = "",
    val title: String,
    val summary: String? = null,
    @SerialName("source_url")
    val sourceUrl: String,
    @SerialName("submitted_by")
    val submittedBy: String? = null,
    @SerialName("content_type")
    val contentType: String,
    val status: String = "pending_review"
)

// Parameter names must match export_sightings' SQL argument names exactly -- Postgrest RPC
// serializes this object's fields directly as the call's named arguments.
@Serializable
private data class ExportSightingsParams(
    @SerialName("p_start_ms") val startMs: Long,
    @SerialName("p_end_ms") val endMs: Long,
    @SerialName("p_min_lat") val minLat: Double,
    @SerialName("p_max_lat") val maxLat: Double,
    @SerialName("p_min_lng") val minLng: Double,
    @SerialName("p_max_lng") val maxLng: Double,
    @SerialName("p_zone_slug") val zoneSlug: String? = null
)

// Matches supabase/migrations/20260829020000_add_beluga_presence_banner.sql's
// get_watched_zone_statuses RPC -- one row per watched zone (is_banner_watched), regardless
// of anyone's location. Raw facts only -- no computed color -- so PresenceBanner.kt can decay
// RED/YELLOW/BLUE against its own tunable windows instead of a value baked into a migration.
// Feeds both the map's unconditional river shading and (via a zone id lookup) the bottom
// banner's status once the client knows which zone is relevant to it.
@Serializable
data class WatchedZoneSightingStatus(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_slug") val zoneSlug: String,
    @SerialName("zone_name") val zoneName: String,
    @SerialName("last_verified_sighting_epoch_ms") val lastVerifiedSightingEpochMs: Long? = null,
    @SerialName("last_any_sighting_epoch_ms") val lastAnySightingEpochMs: Long? = null
)

@Serializable
private data class WatchedZoneStatusesParams(
    @SerialName("p_lookback_ms") val lookbackMs: Long
)

// Matches find_nearby_watched_zone's return shape -- the closer of the banner's two
// visibility gates (the other, "subscribed to a watched zone," is a plain client-side check
// against SubscriptionRecord, no RPC needed).
@Serializable
data class NearbyWatchedZone(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_slug") val zoneSlug: String,
    @SerialName("zone_name") val zoneName: String,
    @SerialName("distance_meters") val distanceMeters: Double
)

@Serializable
private data class NearbyWatchedZoneParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_proximity_meters") val proximityMeters: Double
)

// get_watched_zone_shading_areas' shading_area column as PostgREST returns it for an RPC with
// a geometry-typed output column: a nested GeoJSON object, confirmed live (curled the RPC
// directly) -- the same serialization zones.boundary already gets for a plain table select
// (confirmed separately against subscriptions.custom_polygon, see the Stage 2 commit fixing
// SubscriptionRecord's decode of it), so PostgREST applies it uniformly by Postgres type, not
// by table-select-vs-function-call. Modeled as JsonElement rather than a typed geometry class
// since nothing here needs to inspect it -- SightingsMapScreen just wraps it straight into a
// GeoJSON Feature for a FillLayer.
//
// For Kenai this is the real banner watch area (river buffer + mouth semicircle,
// 20260903040000_add_watched_zone_shading_areas.sql), NOT the full zones.boundary -- any other
// is_banner_watched zone without its own narrower area falls back to its unmodified boundary,
// decided entirely server-side in that RPC. This client code has no zone-slug branching and
// doesn't need any: it always just draws whatever shadingArea comes back.
@Serializable
data class WatchedZoneShadingRecord(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_slug") val zoneSlug: String,
    @SerialName("zone_name") val zoneName: String,
    @SerialName("shading_area") val shadingArea: JsonElement
)

// Matches get_kenai_presence_state's return row
// (supabase/migrations/20260903030000_add_tide_cycle_predictor.sql) -- the real tide-cycle-aware
// RED/YELLOW/BLUE for Kenai specifically, replacing the flat-decay computeBelugaPresenceStatus
// path for this one zone (PresenceBanner.kt/App.kt branch on zone_slug == "kenai" to pick this
// over getWatchedZoneStatuses' generic fallback). A single row always comes back (the RPC's own
// `return query select` always executes), decoded via decodeList().firstOrNull() same as
// findNearbyWatchedZone -- PostgREST wraps a `returns table` function's result in a JSON array
// regardless of row count.
@Serializable
data class KenaiPresenceState(
    // "RED" | "YELLOW" | "BLUE" -- the server's own phase-priority decision. Rendered verbatim,
    // not re-derived client-side (see PresenceBanner.kt's belugaPresenceStatusFromKenaiPhase) --
    // tide-cycle phase isn't a pure function of elapsed time the way the old flat decay was, so
    // there's no safe local recompute between polls.
    val phase: String,
    @SerialName("in_season") val inSeason: Boolean,
    @SerialName("current_cycle_low_epoch_ms") val currentCycleLowEpochMs: Long? = null,
    @SerialName("next_cycle_low_epoch_ms") val nextCycleLowEpochMs: Long? = null,
    @SerialName("red_qualifying_sighting_epoch_ms") val redQualifyingSightingEpochMs: Long? = null,
    @SerialName("yellow_confirmed_sighting_epoch_ms") val yellowConfirmedSightingEpochMs: Long? = null,
    @SerialName("predicted_delay_min") val predictedDelayMin: Int? = null,
    @SerialName("predicted_window_lo_min") val predictedWindowLoMin: Int? = null,
    @SerialName("predicted_window_hi_min") val predictedWindowHiMin: Int? = null,
    @SerialName("predicted_arrival_at_epoch_ms") val predictedArrivalAtEpochMs: Long? = null,
    // The TIDE's own phase classification (flood-ride/ebb-arrival/slack-arrival) for the
    // upcoming low -- distinct from `phase` above (the banner's RED/YELLOW/BLUE), never to be
    // confused with it, same warning the migration's own tide_cycles.current_phase column gives.
    @SerialName("current_phase") val currentTidePhase: String? = null,
    @SerialName("current_entrance_knots") val currentEntranceKnots: Double? = null,
    @SerialName("cfs_used") val cfsUsed: Double? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("prediction_available") val predictionAvailable: Boolean = false
)

@Serializable
private data class WatchedZoneStatusParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_lookback_ms") val lookbackMs: Long
)

@Serializable
private data class CoastlineChannelParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double
)

@Serializable
private data class RedeemTierCodeParams(
    @SerialName("p_code") val code: String,
    @SerialName("p_subscriber_id") val subscriberId: String
)

object SupabaseApi {
    /**
     * Attempts to post a single sighting record to Supabase.
     * Returns true on success, false on failure (network or RLS).
     */
    suspend fun postSighting(record: SightingRecord): Boolean {
        return try {
            // Attempt to transmit the record
            supabase.postgrest["sightings"].insert(record)
            println("SYNC SUCCESS: Record transmitted to Supabase.")
            true
        } catch (e: Exception) {
            // Include the exception type alongside the message: a schema mismatch (missing
            // column) and a network/timeout failure both just print a terse message otherwise,
            // and telling those apart is the whole ballgame when a queued item won't drain.
            println("SYNC ERROR DETAILS: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Uploads a locally-captured photo to Supabase Storage and returns its public URL,
     * or null on failure (caller should leave the sighting queued for retry).
     */
    suspend fun uploadSightingPhoto(
        localId: String,
        localPhotoPath: String,
        storage: LocalFileStorage
    ): String? {
        return try {
            val bytes = storage.readBytesAtPath(localPhotoPath)
            if (bytes == null) {
                println("PHOTO_UPLOAD_ERROR: local file not found at $localPhotoPath")
                return null
            }
            val objectPath = "$localId.jpg"
            val bucket = supabase.storage.from(SIGHTING_PHOTOS_BUCKET)
            // upsert = true so a re-attempted upload of the same queued item doesn't fail on retry
            bucket.upload(objectPath, bytes) {
                upsert = true
                contentType = ContentType.Image.JPEG
            }
            val url = bucket.publicUrl(objectPath)
            println("PHOTO_UPLOAD_SUCCESS: $url")
            url
        } catch (e: Exception) {
            println("PHOTO_UPLOAD_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Registers (or refreshes) this device's FCM token, linked to [subscriberId] so Stage 3's
     * match_notification_recipients RPC can find this device's subscriptions. Upserts on
     * fcm_token so re-registering the same token (e.g. on every app launch, not just on a real
     * refresh) is a no-op rather than an error -- also keeps subscriber_id in sync if it were
     * ever somehow stale.
     */
    suspend fun registerDeviceToken(token: String, subscriberId: String): Boolean {
        return try {
            supabase.postgrest["device_tokens"].upsert(DeviceTokenRecord(token, subscriberId)) {
                onConflict = "fcm_token"
            }
            println("DEVICE_TOKEN_REGISTER_SUCCESS")
            true
        } catch (e: Exception) {
            println("DEVICE_TOKEN_REGISTER_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Fetches published articles of one content type (news or research paper), newest first.
     * Unapproved (pending_review) submissions are excluded by RLS on the server side, not
     * filtered here -- this only ever sees what's actually public.
     */
    suspend fun getArticles(contentType: ArticleContentType): List<ArticleRecord> {
        return try {
            supabase.postgrest["articles"].select {
                filter {
                    eq("content_type", contentType.dbValue)
                    eq("status", "published")
                }
                order("created_at", Order.DESCENDING)
            }.decodeList<ArticleRecord>()
        } catch (e: Exception) {
            println("ARTICLES_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Submits a user-suggested article/paper. Always lands as pending_review (the RLS insert
     * policy enforces this server-side too) -- there's no moderation UI yet, so approval is a
     * manual status edit in the Supabase dashboard.
     */
    suspend fun submitArticle(
        title: String,
        sourceUrl: String,
        summary: String?,
        submittedBy: String?,
        contentType: ArticleContentType
    ): Boolean {
        return try {
            supabase.postgrest["articles"].insert(
                ArticleRecord(
                    title = title,
                    summary = summary,
                    sourceUrl = sourceUrl,
                    submittedBy = submittedBy,
                    contentType = contentType.dbValue
                )
            )
            println("ARTICLE_SUBMIT_SUCCESS")
            true
        } catch (e: Exception) {
            println("ARTICLE_SUBMIT_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Fetches the curated zone presets for one region (e.g. "cook_inlet"), ordered for display,
     * for the export screen's zone picker. Empty for a region with no seeded zones (e.g.
     * St. Lawrence) -- callers should fall back to region-only filtering in that case.
     */
    suspend fun getZones(regionId: String): List<ZoneRecord> {
        return try {
            supabase.postgrest["zones"].select {
                filter { eq("region_id", regionId) }
                order("display_order", Order.ASCENDING)
            }.decodeList<ZoneRecord>()
        } catch (e: Exception) {
            println("ZONES_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // Deliberately excludes `point`/`custom_polygon` -- confirmed live that PostgREST returns
    // geometry(Polygon,4326) as a nested GeoJSON object (not text), which would throw decoding
    // into SubscriptionRecord's String field for every row once any custom_polygon subscription
    // existed. The WATCHING list never needs either raw geometry back (same precedent as
    // zones.boundary), so this just never asks for them.
    private val SUBSCRIPTION_LIST_COLUMNS = Columns.list(
        "id", "subscriber_id", "kind", "confidence_filter", "is_active",
        "label", "zone_id", "radius_meters", "expires_at", "created_at"
    )

    // Every anon-readable sightings column EXCEPT subscriber_id -- anon has no SELECT grant on
    // that column at all (20260903010000_add_observer_tier_system.sql's column-level
    // lockdown), so an unqualified select() would fail outright (Postgres requires privilege
    // on every column a bare `select *`-equivalent expands to). observer_tier IS included --
    // unlike subscriber_id, nothing about it is sensitive, and it's the whole point of the
    // tier system to be usable client-side (e.g. the "verified sightings only" filter).
    private val SIGHTING_LIST_COLUMNS = Columns.list(
        "id", "lat", "lng", "heading", "heading_degrees", "heading_source",
        "heading_accuracy_degrees", "distance_bucket", "distance_radius_meters",
        "count_whites", "count_greys", "count_calves", "count_unknown",
        "observed_at_epoch_ms", "observer_type", "is_geofence_verified", "photo_url",
        "whale_lat", "whale_lng", "uncertainty_radius_meters", "uncertainty_bucket",
        "travel_bearing_degrees", "travel_bearing_source", "position_source", "observer_tier"
    )

    /**
     * Fetches one subscriber's subscriptions (all three kinds), newest first, for the
     * subscriptions management screen.
     */
    suspend fun getSubscriptions(subscriberId: String): List<SubscriptionRecord> {
        return try {
            supabase.postgrest["subscriptions"].select(columns = SUBSCRIPTION_LIST_COLUMNS) {
                filter { eq("subscriber_id", subscriberId) }
                order("created_at", Order.DESCENDING)
            }.decodeList<SubscriptionRecord>()
        } catch (e: Exception) {
            println("SUBSCRIPTIONS_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Fetches the curated point presets for one region (AKBMP monitoring sites + Anchorage),
     * ordered for display, for the subscriptions screen's point picker. Empty if the region has
     * none seeded, OR if the lat/lng generated columns from
     * supabase/migrations/20260829000000_add_point_preset_coordinates.sql haven't been applied
     * yet -- see that migration's header.
     */
    suspend fun getPointPresets(regionId: String): List<PointPresetRecord> {
        return try {
            supabase.postgrest["point_presets"].select {
                filter { eq("region_id", regionId) }
                order("display_order", Order.ASCENDING)
            }.decodeList<PointPresetRecord>()
        } catch (e: Exception) {
            println("POINT_PRESETS_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Creates a kind='zone' subscription watching [zoneId] for [subscriberId]. Returns true on
     * success, false on failure (network or RLS).
     */
    suspend fun createZoneSubscription(
        subscriberId: String,
        zoneId: String,
        confidenceFilter: SubscriptionConfidenceFilter
    ): Boolean {
        return try {
            supabase.postgrest["subscriptions"].insert(
                SubscriptionRecord(
                    subscriberId = subscriberId,
                    kind = "zone",
                    confidenceFilter = confidenceFilter.dbValue,
                    zoneId = zoneId
                )
            )
            println("SUBSCRIPTION_CREATE_SUCCESS")
            true
        } catch (e: Exception) {
            println("SUBSCRIPTION_CREATE_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Creates a kind='point_radius' subscription for [subscriberId]: [lat]/[lng]/[radiusMeters]
     * are copied in as a point value at creation time -- no ongoing link back to a preset, per
     * the schema's documented design. [label] is the preset's name, or "Custom Point" for a
     * hand-dropped pin. [expiresAtEpochMs] null means a permanent subscription.
     */
    suspend fun createPointSubscription(
        subscriberId: String,
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        confidenceFilter: SubscriptionConfidenceFilter,
        label: String,
        expiresAtEpochMs: Long?
    ): Boolean {
        return try {
            supabase.postgrest["subscriptions"].insert(
                SubscriptionRecord(
                    subscriberId = subscriberId,
                    kind = "point_radius",
                    confidenceFilter = confidenceFilter.dbValue,
                    label = label,
                    point = ewktPoint(lat, lng),
                    radiusMeters = radiusMeters,
                    expiresAt = expiresAtEpochMs?.let { formatIso8601Utc(it) }
                )
            )
            println("SUBSCRIPTION_CREATE_SUCCESS")
            true
        } catch (e: Exception) {
            println("SUBSCRIPTION_CREATE_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Creates a kind='custom_polygon' subscription for [subscriberId] from a finger-painted
     * [vertices] ring (lat, lng pairs, at least 3, NOT pre-closed -- this closes the ring
     * itself). `label` is left null: the WATCHING list shows the schema's documented generic
     * "Custom area" fallback for this kind.
     */
    suspend fun createPolygonSubscription(
        subscriberId: String,
        vertices: List<Pair<Double, Double>>,
        confidenceFilter: SubscriptionConfidenceFilter
    ): Boolean {
        return try {
            supabase.postgrest["subscriptions"].insert(
                SubscriptionRecord(
                    subscriberId = subscriberId,
                    kind = "custom_polygon",
                    confidenceFilter = confidenceFilter.dbValue,
                    customPolygon = ewktPolygon(vertices)
                )
            )
            println("SUBSCRIPTION_CREATE_SUCCESS")
            true
        } catch (e: Exception) {
            println("SUBSCRIPTION_CREATE_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Deletes a subscription by id. Returns true on success, false on failure.
     */
    suspend fun deleteSubscription(id: String): Boolean {
        return try {
            supabase.postgrest["subscriptions"].delete {
                filter { eq("id", id) }
            }
            println("SUBSCRIPTION_DELETE_SUCCESS")
            true
        } catch (e: Exception) {
            println("SUBSCRIPTION_DELETE_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Fetches sightings for the data-export feature: within [startMs, endMs] and inside
     * [region]'s bounding box, additionally narrowed to a single zone's real polygon
     * (server-side, via export_sightings' PostGIS containment check) when [zoneSlug] is given.
     *
     * Deliberately does NOT swallow failures to an empty list the way getSightings() below
     * does -- the export screen needs to tell "genuinely no matches" apart from "the RPC call
     * itself failed" (e.g. the migration adding export_sightings hasn't been run yet), so this
     * lets the caller's try/catch surface the real exception message instead.
     */
    suspend fun exportSightings(
        startMs: Long,
        endMs: Long,
        region: RegionConfig,
        zoneSlug: String?
    ): List<SightingRecord> {
        val params = jsonConfig.encodeToJsonElement(
            ExportSightingsParams(
                startMs = startMs,
                endMs = endMs,
                minLat = region.minLat,
                maxLat = region.maxLat,
                minLng = region.minLng,
                maxLng = region.maxLng,
                zoneSlug = zoneSlug
            )
        ).jsonObject
        return supabase.postgrest.rpc("export_sightings", params).decodeList<SightingRecord>()
    }

    /**
     * Fetches raw sighting-recency facts for every watched (is_banner_watched) zone --
     * unconditional, no location involved. Feeds the map's river shading (shown to everyone)
     * and, via a zone id lookup, the bottom banner's status. Empty on failure or if nothing
     * is watched yet.
     */
    suspend fun getWatchedZoneStatuses(lookbackMs: Long): List<WatchedZoneSightingStatus> {
        return try {
            val params = jsonConfig.encodeToJsonElement(
                WatchedZoneStatusesParams(lookbackMs = lookbackMs)
            ).jsonObject
            supabase.postgrest.rpc("get_watched_zone_statuses", params)
                .decodeList<WatchedZoneSightingStatus>()
        } catch (e: Exception) {
            println("WATCHED_ZONE_STATUSES_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Resolves the closer half of the bottom banner's visibility gate: the nearest watched
     * zone within [proximityMeters] of [lat]/[lng], or null if none is that close (or on
     * failure). The other half -- "subscribed to a watched zone" -- is a plain client-side
     * check against getSubscriptions(), no RPC needed.
     */
    suspend fun findNearbyWatchedZone(lat: Double, lng: Double, proximityMeters: Double): NearbyWatchedZone? {
        return try {
            val params = jsonConfig.encodeToJsonElement(
                NearbyWatchedZoneParams(lat = lat, lng = lng, proximityMeters = proximityMeters)
            ).jsonObject
            supabase.postgrest.rpc("find_nearby_watched_zone", params)
                .decodeList<NearbyWatchedZone>()
                .firstOrNull()
        } catch (e: Exception) {
            println("NEARBY_WATCHED_ZONE_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Fetches every watched zone's shading geometry for the map's "belugas present" FillLayer
     * -- see WatchedZoneShadingRecord's own comment for what that actually is per zone.
     * Deliberately NOT part of getZones() -- that one is used far more often (every zone
     * picker) and never needed any polygon at all; this is the one place that actually does.
     */
    suspend fun getWatchedZoneShadingAreas(): List<WatchedZoneShadingRecord> {
        return try {
            supabase.postgrest.rpc("get_watched_zone_shading_areas").decodeList<WatchedZoneShadingRecord>()
        } catch (e: Exception) {
            println("WATCHED_ZONE_SHADING_AREAS_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Fetches Kenai's real tide-cycle-aware presence state -- see KenaiPresenceState's own
     * comment. No params: p_now_epoch_ms defaults to the server's own now() server-side, same
     * as getWatchedZoneShadingAreas' zero-arg call below. Null on any failure -- callers must
     * keep showing their own last-known state rather than treat null as "no data" (see
     * PresenceBanner.kt's staleness handling, App.kt's polling loop).
     */
    suspend fun getKenaiPresenceState(): KenaiPresenceState? {
        return try {
            supabase.postgrest.rpc("get_kenai_presence_state").decodeList<KenaiPresenceState>().firstOrNull()
        } catch (e: Exception) {
            println("KENAI_PRESENCE_STATE_FETCH_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Real-coastline-curve check for a point the client's own well-sourced-zone/sparse-point
     * fallbacks (GeofenceUtils.isWithin3DFunnel / CoastlineGeometry.isWithinWellSourcedWater)
     * couldn't resolve -- typically a genuinely far-offshore/mid-channel point neither of
     * those shore-hugging checks has coverage for. See
     * is_point_within_coastline_channel's own doc comment
     * (supabase/migrations/20260830000000_add_coastline_traces.sql) for the actual
     * closest-point/between-ness/width-bound method.
     *
     * Returns null both when the RPC itself has no coastline_traces coverage near this point
     * (its own null case) and on any network/decode failure -- not currently called from
     * anywhere (this app's geofence check is a synchronous, offline-capable function; wiring
     * a network RPC into it is an intentionally separate decision, not made by adding this),
     * so a caller that does use it must treat null as "no additional evidence either way" and
     * fall back further, the same way isWithinWellSourcedWater's null is already handled.
     */
    suspend fun isPointWithinCoastlineChannel(lat: Double, lng: Double): Boolean? {
        return try {
            val params = jsonConfig.encodeToJsonElement(
                CoastlineChannelParams(lat = lat, lng = lng)
            ).jsonObject
            // decodeAs<T>() requires T : Any, which a genuinely-nullable SQL boolean result
            // doesn't satisfy -- the RPC's own null case (no coverage near this point) is a
            // real, expected outcome here, not a decode failure, so this parses the raw JSON
            // body directly instead (a bare `true`, `false`, or `null` literal).
            val raw = supabase.postgrest.rpc("is_point_within_coastline_channel", params).data
            when (val element = jsonConfig.parseToJsonElement(raw)) {
                is JsonNull -> null
                else -> element.jsonPrimitive.booleanOrNull
            }
        } catch (e: Exception) {
            println("COASTLINE_CHANNEL_CHECK_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Attempts to claim [code] onto this device's [subscriberId] via TierClaimScreen (see
     * that screen's own comment -- only reachable through AboutScreen's hidden gesture).
     * Returns the assigned tier (1 or 2) on success, null on any failure -- a bad code, an
     * already-used code, and a network/decode error all collapse to the same null so this
     * can't be used to probe which codes exist, matching redeem_tier_code's own design
     * (20260903010000_add_observer_tier_system.sql).
     */
    suspend fun redeemTierCode(code: String, subscriberId: String): Int? {
        return try {
            val params = jsonConfig.encodeToJsonElement(
                RedeemTierCodeParams(code = code, subscriberId = subscriberId)
            ).jsonObject
            // Same raw-parse approach as isPointWithinCoastlineChannel above -- decodeAs<T>()
            // requires non-null T, and a genuinely-null result (bad/used code) is an expected
            // outcome here, not a decode failure.
            val raw = supabase.postgrest.rpc("redeem_tier_code", params).data
            when (val element = jsonConfig.parseToJsonElement(raw)) {
                is JsonNull -> null
                else -> element.jsonPrimitive.intOrNull
            }
        } catch (e: Exception) {
            println("TIER_CODE_REDEEM_ERROR: [${e::class.simpleName}] ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Fetches all sightings from the remote Supabase database with a 10s timeout.
     */
    suspend fun getSightings(): List<SightingRecord> = withContext(Dispatchers.Default) {
        println("SUPABASE_FETCH: [1/3] Starting fetch request on background thread...")
        return@withContext try {
            withTimeout(10000L) {
                println("SUPABASE_FETCH: [2/3] Querying 'sightings' table...")
                val response = supabase.postgrest["sightings"].select(columns = SIGHTING_LIST_COLUMNS)
                    .decodeList<SightingRecord>()
                println("SUPABASE_FETCH: [3/3] SUCCESS! Decoded ${response.size} items from database.")
                response.forEach {
                    println("  -> Sighting ID: ${it.id} | Lat: ${it.lat} | Lng: ${it.lng}")
                }
                response
            }
        } catch (t: Throwable) {
            println("SUPABASE_FETCH_FAILED: ${t::class.simpleName} - ${t.message}")
            t.printStackTrace()
            emptyList()
        }
    }
}

// EWKT (WKT with an explicit SRID prefix) for the two subscription geometry columns. The
// explicit "SRID=4326;" matters: without it, Postgres/PostGIS's typmod check on a
// geometry(Polygon,4326)-declared column rejects an incoming WKT value (defaults to SRID 0,
// which doesn't match the column's declared SRID) -- this sidesteps that entirely.
private fun ewktPoint(lat: Double, lng: Double): String = "SRID=4326;POINT($lng $lat)"

private fun ewktPolygon(vertices: List<Pair<Double, Double>>): String {
    // Closes the ring back to its first vertex -- callers pass an open list of (lat, lng)
    // pairs, same as a finger-painted polygon's raw tap sequence.
    val ring = vertices + vertices.first()
    val coords = ring.joinToString(", ") { (lat, lng) -> "$lng $lat" }
    return "SRID=4326;POLYGON(($coords))"
}
