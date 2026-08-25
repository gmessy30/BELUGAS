package com.cookinlet.belugas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncEngine {

    // Per-item outcome of a sync attempt, for surfacing real success/failure feedback in the
    // UI (previously this only went to Logcat via println).
    sealed class SyncEvent {
        data class ItemSynced(val localId: String) : SyncEvent()
        data class ItemFailed(val localId: String, val reason: String) : SyncEvent()
    }

    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Serializes the whole read-queue/transmit/mark-synced cycle so an auto-triggered run
    // (fired after every save) and a manually-triggered run (the transmit button) never both
    // read the same pending item and post it to Supabase twice. Callers are invoked from the
    // Compose UI thread, so the isActive check below and the launch() that follows it are not
    // racing each other -- this only dedupes/reuses a job across sequential calls made while
    // one is still running.
    private val syncMutex = Mutex()
    private var activeJob: Job? = null

    /**
     * Processes the local pending queue opportunistically.
     * Bypasses standard system network guards to attempt transmission whenever triggered.
     *
     * Returns the Job doing the work so callers that need the sync attempt to actually finish
     * before proceeding (e.g. before refreshing the remote sightings list) can `.join()` it.
     * If a run is already in progress, that run's Job is returned instead of starting a
     * redundant overlapping one.
     */
    fun processQueueInBackground(
        scope: CoroutineScope,
        storage: LocalFileStorage,
        onStatusUpdate: (pendingCount: Int) -> Unit = {}
    ): Job {
        activeJob?.let { if (it.isActive) return it }

        val job = scope.launch(Dispatchers.Default) {
            syncMutex.withLock {
                _isSyncing.value = true
                try {
                    val queue = OfflineSightingRepository.getPendingQueue(storage)
                    if (queue.isEmpty()) {
                        onStatusUpdate(0)
                        return@withLock
                    }

                    // Attempt to transmit each item directly.
                    // If the network is truly down, the try/catch inside SupabaseApi will handle it.
                    // A failure on one item (photo upload or record insert) leaves that item queued for
                    // retry but does not stop the rest of the queue from syncing.
                    for (item in queue) {
                        println("SyncEngine: Attempting to sync item ${item.localId}")

                        val recordToSync = if (item.localPhotoPath != null && item.record.photoUrl == null) {
                            val uploadedUrl = SupabaseApi.uploadSightingPhoto(item.localId, item.localPhotoPath, storage)
                            if (uploadedUrl == null) {
                                println("SyncEngine: Photo upload failed for ${item.localId}, will retry later.")
                                _events.emit(SyncEvent.ItemFailed(item.localId, "Photo upload failed, will retry"))
                                continue
                            }
                            item.record.copy(photoUrl = uploadedUrl)
                        } else {
                            item.record
                        }

                        val success = SupabaseApi.postSighting(recordToSync)
                        if (success) {
                            OfflineSightingRepository.markAsSynced(storage, item.localId)
                            _events.emit(SyncEvent.ItemSynced(item.localId))
                        } else {
                            println("SyncEngine: Sync failed for item ${item.localId}, will retry later.")
                            _events.emit(SyncEvent.ItemFailed(item.localId, "Sync failed, will retry"))
                        }
                    }

                    val remaining = OfflineSightingRepository.getPendingQueue(storage)
                    onStatusUpdate(remaining.size)
                } finally {
                    _isSyncing.value = false
                }
            }
        }
        activeJob = job
        return job
    }
}
