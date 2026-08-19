package com.cookinlet.belugas

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            println("SYNC ERROR DETAILS: ${e.message}")
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
            println("PHOTO_UPLOAD_ERROR: ${e.message}")
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
