package com.cookinlet.belugas

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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

// Matches supabase/migrations/20260823000000_add_device_tokens_and_notify_trigger.sql --
// flat device-token registry for the minimal broadcast notification pipeline (no
// subscriber/zone targeting yet).
@Serializable
private data class DeviceTokenRecord(
    @SerialName("fcm_token")
    val fcmToken: String
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

// The `confidence_filter` values a zone subscription can be created with. Matches
// public.subscription_confidence_filter in
// supabase/migrations/20260819000000_add_notification_zones_and_subscriptions.sql.
enum class SubscriptionConfidenceFilter(val dbValue: String, val label: String) {
    ALL("all", "All Sightings"),
    VERIFIED_ONLY("verified_only", "Verified Observer Only")
}

// Matches the columns Stage 1 (zone-kind subscription management) touches on
// public.subscriptions -- custom_polygon/point/radius_meters/expires_at/updated_at aren't
// modeled here since nothing in this stage reads or writes them. `kind` is always the literal
// "zone" for now; other kinds (custom_polygon, point_radius) are a later stage.
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
    @SerialName("zone_id")
    val zoneId: String? = null,
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
     * Registers (or refreshes) this device's FCM token in the flat device_tokens broadcast
     * list. Upserts on fcm_token so re-registering the same token (e.g. on every app launch,
     * not just on a real refresh) is a no-op rather than an error.
     */
    suspend fun registerDeviceToken(token: String): Boolean {
        return try {
            supabase.postgrest["device_tokens"].upsert(DeviceTokenRecord(token)) {
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

    /**
     * Fetches one subscriber's zone-watch subscriptions, newest first, for the subscriptions
     * management screen.
     */
    suspend fun getSubscriptions(subscriberId: String): List<SubscriptionRecord> {
        return try {
            supabase.postgrest["subscriptions"].select {
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
     * Fetches all sightings from the remote Supabase database with a 10s timeout.
     */
    suspend fun getSightings(): List<SightingRecord> = withContext(Dispatchers.Default) {
        println("SUPABASE_FETCH: [1/3] Starting fetch request on background thread...")
        return@withContext try {
            withTimeout(10000L) {
                println("SUPABASE_FETCH: [2/3] Querying 'sightings' table...")
                val response = supabase.postgrest["sightings"].select().decodeList<SightingRecord>()
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
