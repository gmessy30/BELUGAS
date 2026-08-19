package com.cookinlet.belugas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SyncEngine {

    /**
     * Processes the local pending queue opportunistically.
     * Bypasses standard system network guards to attempt transmission whenever triggered.
     */
    fun processQueueInBackground(
        scope: CoroutineScope,
        storage: LocalFileStorage,
        onStatusUpdate: (pendingCount: Int) -> Unit = {}
    ) {
        scope.launch(Dispatchers.Default) {
            val queue = OfflineSightingRepository.getPendingQueue(storage)
            if (queue.isEmpty()) {
                onStatusUpdate(0)
                return@launch
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
                        continue
                    }
                    item.record.copy(photoUrl = uploadedUrl)
                } else {
                    item.record
                }

                val success = SupabaseApi.postSighting(recordToSync)
                if (success) {
                    OfflineSightingRepository.markAsSynced(storage, item.localId)
                } else {
                    println("SyncEngine: Sync failed for item ${item.localId}, will retry later.")
                }
            }

            val remaining = OfflineSightingRepository.getPendingQueue(storage)
            onStatusUpdate(remaining.size)
        }
    }
}
