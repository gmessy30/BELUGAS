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
            for (item in queue) {
                println("SyncEngine: Attempting to sync item ${item.localId}")
                val success = SupabaseApi.postSighting(item.record)
                if (success) {
                    OfflineSightingRepository.markAsSynced(storage, item.localId)
                } else {
                    println("SyncEngine: Sync failed for item ${item.localId}, stopping queue processing.")
                    break
                }
            }

            val remaining = OfflineSightingRepository.getPendingQueue(storage)
            onStatusUpdate(remaining.size)
        }
    }
}
