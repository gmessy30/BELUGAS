package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LocalPendingSighting(
    val localId: String,
    val record: SightingRecord,
    val timestamp: Long = 0L // Placeholder for platform timestamp if needed
)

expect class LocalFileStorage() {
    suspend fun writeText(fileName: String, content: String)
    suspend fun readText(fileName: String): String?
    fun getFilePath(fileName: String): String
    fun fileExists(fileName: String): Boolean
    fun getFileSize(fileName: String): Long
    fun getCacheDir(): String
    suspend fun writeBytes(fileName: String, bytes: ByteArray)
    suspend fun deleteFile(fileName: String)
}

@Composable
fun rememberLocalFileStorage(): LocalFileStorage = remember { LocalFileStorage() }

object OfflineSightingRepository {
    private const val QUEUE_FILE_NAME = "pending_sightings_queue.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // Live observable pending count for Compose UI
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    // Call on app startup to initialize count from file
    suspend fun refreshPendingCount(storage: LocalFileStorage) = mutex.withLock {
        val list = getQueueInternal(storage)
        _pendingCount.value = list.size
    }

    // 1. Save new sighting locally immediately
    suspend fun queueSighting(
        storage: LocalFileStorage,
        record: SightingRecord
    ): LocalPendingSighting = mutex.withLock {
        val pendingList = getQueueInternal(storage).toMutableList()
        val newItem = LocalPendingSighting(
            localId = "local_${currentTimeMillis()}",
            record = record,
            timestamp = currentTimeMillis()
        )
        pendingList.add(newItem)
        saveQueueInternal(storage, pendingList)
        _pendingCount.value = pendingList.size // Update live UI
        return newItem
    }

    // 2. Fetch all unsynced items
    suspend fun getPendingQueue(storage: LocalFileStorage): List<LocalPendingSighting> = mutex.withLock {
        return getQueueInternal(storage)
    }

    // 3. Remove item once Supabase confirms receipt
    suspend fun removeSighting(storage: LocalFileStorage, localId: String) = markAsSynced(storage, localId)

    suspend fun markAsSynced(storage: LocalFileStorage, localId: String) = mutex.withLock {
        val pendingList = getQueueInternal(storage).toMutableList()
        pendingList.removeAll { it.localId == localId }
        saveQueueInternal(storage, pendingList)
        _pendingCount.value = pendingList.size // Update live UI
    }

    private suspend fun getQueueInternal(storage: LocalFileStorage): List<LocalPendingSighting> {
        val content = storage.readText(QUEUE_FILE_NAME) ?: return emptyList()
        return try {
            json.decodeFromString<List<LocalPendingSighting>>(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveQueueInternal(storage: LocalFileStorage, list: List<LocalPendingSighting>) {
        val jsonString = json.encodeToString(list)
        storage.writeText(QUEUE_FILE_NAME, jsonString)
    }
}

// Helper to handle timestamp in commonMain
expect fun currentTimeMillis(): Long
expect fun formatTime(epochMs: Long): String
expect fun formatDateTime(epochMs: Long): String
expect fun formatDateLabel(epochMs: Long): String
expect fun formatCoord(value: Double): String
