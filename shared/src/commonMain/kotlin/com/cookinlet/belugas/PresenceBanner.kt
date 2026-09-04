package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Belugas present" safety banner, first pass: a simple time-decay model, NOT the tidal-based
// heuristic from the original design notes (a future refinement). RED decays to YELLOW after
// [DEFAULT_RED_WINDOW_MS] since the last SELF+geofence-verified sighting in a watched zone;
// YELLOW decays to BLUE after [DEFAULT_YELLOW_WINDOW_MS] since the last sighting of any kind.
// Both are plain constants (not baked into the backend RPC) specifically so they're tunable
// without a migration -- see supabase/migrations/20260829020000_add_beluga_presence_banner.sql.
const val DEFAULT_RED_WINDOW_MS = 2L * 60 * 60 * 1000
const val DEFAULT_YELLOW_WINDOW_MS = 24L * 60 * 60 * 1000

// How close (real distance, not polygon containment) counts as "near" a watched zone for the
// bottom banner's proximity gate -- someone doesn't need to be standing in the river to see
// it. Also client-owned, passed to find_nearby_watched_zone as a parameter, same reasoning as
// the decay windows.
const val DEFAULT_BANNER_PROXIMITY_METERS = 15000.0

// How often to re-fetch the user's location/watched-zone data -- a network round trip, kept
// infrequent. Decay itself (see computeBelugaPresenceStatus) is recomputed locally on a much
// shorter tick so the banner and map shading keep visibly aging between polls.
const val LOCATION_POLL_INTERVAL_MS = 5L * 60 * 1000
const val PRESENCE_DECAY_TICK_INTERVAL_MS = 30L * 1000

// How soon to retry the proximity location fetch specifically after it comes back null (a cold
// GPS fix, most commonly right after launch) rather than waiting out the full poll interval --
// see the retry LaunchedEffect in App.kt for the failure-vs-success branch this feeds.
const val LOCATION_RETRY_INTERVAL_MS = 15L * 1000

// How long since the last SUCCESSFUL get_kenai_presence_state() poll before the banner admits
// its data might be stale, rather than silently keep showing a last-known RED/YELLOW/BLUE that
// could be hours out of date. 3x the 5-minute poll cadence (LOCATION_POLL_INTERVAL_MS) --
// long enough that one transient failed poll doesn't flap the indicator on and off, short
// enough that a real sustained outage still surfaces within a quarter hour. Deliberately NOT a
// fallback to flat-decay computeBelugaPresenceStatus -- that would reintroduce exactly the
// two-sources-of-truth disagreement between the banner and the map's shading this design avoids
// (see get_kenai_presence_state's own migration comment on why RED/YELLOW/BLUE for Kenai isn't a
// pure function of elapsed time the way the old decay windows were).
const val DEFAULT_KENAI_STALENESS_THRESHOLD_MS = 3L * LOCATION_POLL_INTERVAL_MS

enum class BelugaPresenceStatus { RED, YELLOW, BLUE }

// get_kenai_presence_state's `phase` column, rendered verbatim -- see KenaiPresenceState's own
// comment for why this isn't a local recompute. Any value other than RED/YELLOW fails safe to
// BLUE rather than crashing on an unrecognized string (e.g. if the RPC's phase enum ever grows).
fun belugaPresenceStatusFromKenaiPhase(phase: String): BelugaPresenceStatus = when (phase) {
    "RED" -> BelugaPresenceStatus.RED
    "YELLOW" -> BelugaPresenceStatus.YELLOW
    else -> BelugaPresenceStatus.BLUE
}

/**
 * Pure decay computation: RED if a verified sighting landed within [redWindowMs], else YELLOW
 * if any sighting (verified or not) landed within [yellowWindowMs], else BLUE. [status] null
 * (no data yet for the zone in question) behaves identically to a watched zone with no recent
 * sightings -- both fold into BLUE.
 */
fun computeBelugaPresenceStatus(
    status: WatchedZoneSightingStatus?,
    nowMs: Long,
    redWindowMs: Long = DEFAULT_RED_WINDOW_MS,
    yellowWindowMs: Long = DEFAULT_YELLOW_WINDOW_MS
): BelugaPresenceStatus {
    val verifiedAt = status?.lastVerifiedSightingEpochMs
    val anyAt = status?.lastAnySightingEpochMs
    return when {
        verifiedAt != null && nowMs - verifiedAt <= redWindowMs -> BelugaPresenceStatus.RED
        anyAt != null && nowMs - anyAt <= yellowWindowMs -> BelugaPresenceStatus.YELLOW
        else -> BelugaPresenceStatus.BLUE
    }
}

// Shared between the bottom banner and the map's river-shading FillLayer, so both surfaces
// always agree on what RED/YELLOW/BLUE actually look like.
fun colorForBelugaPresenceStatus(status: BelugaPresenceStatus): Color = when (status) {
    BelugaPresenceStatus.RED -> Color(0xFFC62828)
    BelugaPresenceStatus.YELLOW -> Color(0xFFF9A825)
    BelugaPresenceStatus.BLUE -> Color(0xFF0277BD)
}

// Kenai-specific label text, used only when kenaiDetail is non-null (see BelugaPresenceBanner
// below). RED/YELLOW stay identical to the generic labels -- the tide-cycle machinery changes
// WHEN these fire, not what they say once they have. BLUE is the one that actually differs: it's
// not a single state here but three (see get_kenai_presence_state's own header comment) --
// out-of-season, in-season-but-not-yet-computed, and the real "here's the predicted window"
// case, each needing different text rather than one generic "no recent sightings."
private fun kenaiBannerLabel(status: BelugaPresenceStatus, detail: KenaiPresenceState, zoneSuffix: String): String =
    when (status) {
        BelugaPresenceStatus.RED -> "BELUGAS PRESENT$zoneSuffix"
        BelugaPresenceStatus.YELLOW -> "POSSIBLE ACTIVITY$zoneSuffix"
        BelugaPresenceStatus.BLUE -> when {
            !detail.inSeason -> "NOT EXPECTED THIS TIME OF YEAR$zoneSuffix"
            !detail.predictionAvailable -> "PREDICTION UNAVAILABLE$zoneSuffix"
            detail.nextCycleLowEpochMs != null &&
                detail.predictedWindowLoMin != null &&
                detail.predictedWindowHiMin != null -> {
                val windowStart = formatTime(detail.nextCycleLowEpochMs + detail.predictedWindowLoMin * 60_000L)
                val windowEnd = formatTime(detail.nextCycleLowEpochMs + detail.predictedWindowHiMin * 60_000L)
                "NEXT WINDOW $windowStart–$windowEnd$zoneSuffix"
            }
            else -> "NO RECENT SIGHTINGS$zoneSuffix"
        }
    }

/**
 * Bottom bar reflecting [status] for [zoneName]. The caller decides whether to mount this at
 * all -- it's only shown when the user is subscribed to a watched zone or within
 * [DEFAULT_BANNER_PROXIMITY_METERS] of one (App.kt's showPresenceBanner), on top of the
 * screen-based Placement rule. Once mounted, BLUE is a real, shown state, not a hidden one --
 * it never disappears due to its own color, only because neither gate applies anymore.
 *
 * [kenaiDetail] is non-null only for the Kenai zone (App.kt passes the fetched
 * KenaiPresenceState through) -- every other watched zone renders the original plain label.
 * [isKenaiDataStale] separately flags "the last successful poll is older than
 * [DEFAULT_KENAI_STALENESS_THRESHOLD_MS]" -- shown as a suffix on whatever [status]/[kenaiDetail]
 * last resolved to, not a fallback to a different computation.
 */
@Composable
fun BelugaPresenceBanner(
    status: BelugaPresenceStatus,
    zoneName: String?,
    modifier: Modifier = Modifier,
    kenaiDetail: KenaiPresenceState? = null,
    isKenaiDataStale: Boolean = false
) {
    val backgroundColor = colorForBelugaPresenceStatus(status)
    val zoneSuffix = zoneName?.let { " · ${it.uppercase()}" } ?: ""
    val baseLabel = if (kenaiDetail != null) {
        kenaiBannerLabel(status, kenaiDetail, zoneSuffix)
    } else {
        when (status) {
            BelugaPresenceStatus.RED -> "BELUGAS PRESENT$zoneSuffix"
            BelugaPresenceStatus.YELLOW -> "POSSIBLE ACTIVITY$zoneSuffix"
            BelugaPresenceStatus.BLUE -> "NO RECENT SIGHTINGS$zoneSuffix"
        }
    }
    val label = if (isKenaiDataStale) "$baseLabel (UPDATING…)" else baseLabel

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
            if (kenaiDetail != null && kenaiDetail.warnings.isNotEmpty()) {
                Text(
                    text = "⚠ " + kenaiDetail.warnings.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
