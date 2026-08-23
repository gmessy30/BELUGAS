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
