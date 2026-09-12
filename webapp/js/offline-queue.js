// Offline queue for sighting submissions that fail because the device has no connectivity, not
// because anything about the submission itself was invalid. Deliberately simple (IndexedDB, no
// retry backoff/attempt limits) -- the goal is just "never silently lose a sighting," not to
// match OfflineSightingRepository.kt/SyncEngine.kt's actual queue architecture on the native side.
const QUEUE_DB_NAME = "belugas-offline-queue";
const QUEUE_DB_VERSION = 1;
const QUEUE_STORE = "pending_sightings";

function openQueueDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(QUEUE_DB_NAME, QUEUE_DB_VERSION);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(QUEUE_STORE, { keyPath: "id" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function putQueuedSighting(entry) {
  const db = await openQueueDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(QUEUE_STORE, "readwrite");
    tx.objectStore(QUEUE_STORE).put(entry);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function getQueuedSightings() {
  const db = await openQueueDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(QUEUE_STORE, "readonly");
    const request = tx.objectStore(QUEUE_STORE).getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function deleteQueuedSighting(id) {
  const db = await openQueueDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(QUEUE_STORE, "readwrite");
    tx.objectStore(QUEUE_STORE).delete(id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * One attempt at getting `record` (+ optional `photoBlob`) all the way to Supabase: upload the
 * photo if it hasn't already succeeded in a previous attempt (record.photo_url set means it
 * has -- never re-uploads a photo that already landed), then insert the row.
 */
async function attemptUploadAndInsert(id, record, photoBlob) {
  let photoUrl = record.photo_url ?? null;

  if (photoBlob && !photoUrl) {
    const upload = await uploadSightingPhoto(id, photoBlob);
    if (!upload.ok) return { ok: false, networkError: upload.networkError };
    photoUrl = upload.url;
  }

  const finalRecord = { ...record, photo_url: photoUrl };
  const insert = await insertSighting(finalRecord);
  if (!insert.ok) return { ok: false, networkError: insert.networkError, photoUrl };

  return { ok: true };
}

/**
 * Entry point from the submit form: tries once immediately, and only falls back to the local
 * queue when the failure looks like a connectivity problem (see isNetworkError in db.js) -- a
 * real server-side rejection is shown to the user right away instead, since queuing it would
 * just fail again on every retry.
 */
async function submitOrQueueSighting(record, photoBlob) {
  const id = crypto.randomUUID ? crypto.randomUUID() : fallbackQueueId();
  const result = await attemptUploadAndInsert(id, record, photoBlob);

  if (result.ok) return { ok: true };

  if (result.networkError) {
    await putQueuedSighting({
      id,
      record: { ...record, photo_url: result.photoUrl ?? null },
      // Never persist the blob once its upload already succeeded -- only the insert failed,
      // so a retry must not upload it a second time.
      photoBlob: result.photoUrl ? null : photoBlob,
      createdAt: Date.now()
    });
    await refreshQueueBadge();
    // Shows the newly-queued sighting on the map/list immediately (native's own "QUEUED FOR
    // SYNC" section doesn't wait for a sync attempt to appear either) rather than only once a
    // later drain/refresh happens to run.
    await renderAllSightings();
    return { ok: false, queued: true };
  }

  return { ok: false, queued: false };
}

/**
 * Retries every queued sighting. Called on the 'online' event, on a periodic timer, and once at
 * startup (in case anything was left over from a previous session that's already back online by
 * the time the page reloads).
 */
async function drainOfflineQueue() {
  const items = await getQueuedSightings();
  if (items.length === 0) return;

  let anySucceeded = false;
  for (const item of items) {
    const result = await attemptUploadAndInsert(item.id, item.record, item.photoBlob);
    if (result.ok) {
      await deleteQueuedSighting(item.id);
      anySucceeded = true;
    } else if (result.photoUrl && result.photoUrl !== item.record.photo_url) {
      // Photo upload succeeded this round even though the insert still failed -- persist that
      // so the next retry doesn't re-upload it.
      item.record.photo_url = result.photoUrl;
      item.photoBlob = null;
      await putQueuedSighting(item);
    }
    // A non-network failure on retry is left queued as-is rather than dropped -- this MVP queue
    // has no per-item error surfacing, so "keep trying" is the safer default over "lose it."
  }

  await refreshQueueBadge();
  // A success means a remote row now exists that didn't before -- worth a full remote refetch.
  // Otherwise (nothing succeeded, or a queued photo just got its real URL) just re-render with
  // the queue's current state -- no network call needed for that.
  if (anySucceeded) {
    await refreshSightings();
  } else {
    await renderAllSightings();
  }
}

/**
 * Queued entries reshaped to look like a fetched sighting row, so the map/list views can render
 * them alongside remote ones -- matching OfflineSightingsList's own "QUEUED FOR SYNC" section in
 * App.kt, which shows this device's unsynced local queue mixed into the same list/map rather
 * than hidden until it syncs. `is_local: true` is the marker both views key their queued-specific
 * treatment off (gray map dot, "(QUEUED)" list suffix), mirroring SightingRecord.isLocal there.
 *
 * A queued photo not yet uploaded has no real photo_url yet -- object-URL'd from the blob still
 * held locally so its thumbnail shows immediately instead of blank until it syncs.
 */
async function getQueuedSightingsAsRecords() {
  const items = await getQueuedSightings();
  return items.map((item) => ({
    id: item.id,
    whale_lat: item.record.whale_lat,
    whale_lng: item.record.whale_lng,
    count_whites: item.record.count_whites,
    count_greys: item.record.count_greys,
    count_calves: item.record.count_calves,
    count_unknown: item.record.count_unknown,
    observed_at_epoch_ms: item.record.observed_at_epoch_ms,
    photo_url: item.record.photo_url || (item.photoBlob ? URL.createObjectURL(item.photoBlob) : null),
    is_local: true
  }));
}

async function refreshQueueBadge() {
  const banner = document.getElementById("queue-banner");
  if (!banner) return;
  const items = await getQueuedSightings();
  if (items.length === 0) {
    banner.hidden = true;
  } else {
    banner.hidden = false;
    document.getElementById("queue-count").textContent = items.length;
  }
}

function fallbackQueueId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const QUEUE_DRAIN_INTERVAL_MS = 45000;

function initOfflineQueue() {
  document.getElementById("queue-retry-btn").addEventListener("click", drainOfflineQueue);
  window.addEventListener("online", drainOfflineQueue);
  setInterval(drainOfflineQueue, QUEUE_DRAIN_INTERVAL_MS);

  refreshQueueBadge();
  drainOfflineQueue();
}
