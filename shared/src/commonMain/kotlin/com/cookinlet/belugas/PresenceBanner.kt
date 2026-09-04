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

// How long since the last SUCCESSFUL presence-state poll (Kenai's get_kenai_presence_state, or
// any other watched zone's get_watched_zone_statuses) before the banner/map admit their data
// might be stale, rather than silently keep showing a last-known RED/YELLOW/BLUE that could be
// hours out of date. 3x the 5-minute poll cadence (LOCATION_POLL_INTERVAL_MS) -- long enough
// that one transient failed poll doesn't flap the indicator on and off, short enough that a
// real sustained outage still surfaces within a quarter hour. Applies uniformly to every watched
// zone, not just Kenai -- see the UNKNOWN case below for the OTHER half of this: staleness is
// about a value that WAS real going out of date, not about never having had one at all.
const val DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS = 3L * LOCATION_POLL_INTERVAL_MS

// UNKNOWN is deliberately its own state, not folded into BLUE -- BLUE is a real, confirmed claim
// ("we checked, nothing recent"); UNKNOWN means the client has never once heard a real answer
// (no successful fetch yet, for this zone, this app run). Collapsing the two used to mean a
// device that had never reached the server rendered a confident "NO RECENT SIGHTINGS" banner and
// blue river shading -- a false all-clear, the one thing get_kenai_presence_state's own
// RED-persistence rule exists to avoid (an overlong RED beats a premature all-clear). A failed or
// not-yet-completed fetch must never resolve to BLUE by default; see App.kt's polling loops
// (both Kenai's and the generic watched-zone one) for how UNKNOWN vs. a real last-known value vs.
// a STALE last-known value are now tracked separately, and SightingsMapScreen for how UNKNOWN
// specifically means "don't draw this zone's shading at all" rather than drawing it in some
// placeholder color.
enum class BelugaPresenceStatus { RED, YELLOW, BLUE, UNKNOWN }

// get_kenai_presence_state's `phase` column, rendered verbatim -- see KenaiPresenceState's own
// comment for why this isn't a local recompute. Any value other than RED/YELLOW fails safe to
// BLUE rather than crashing on an unrecognized string (e.g. if the RPC's phase enum ever grows).
// Never returns UNKNOWN -- that's purely a client-side "no data yet" state, not something the
// phase column itself ever expresses (a successful poll always yields a real phase).
fun belugaPresenceStatusFromKenaiPhase(phase: String): BelugaPresenceStatus = when (phase) {
    "RED" -> BelugaPresenceStatus.RED
    "YELLOW" -> BelugaPresenceStatus.YELLOW
    else -> BelugaPresenceStatus.BLUE
}

/**
 * Pure decay computation: RED if a verified sighting landed within [redWindowMs], else YELLOW
 * if any sighting (verified or not) landed within [yellowWindowMs], else BLUE. [status] null
 * means "a successful get_watched_zone_statuses fetch happened, but had no row for this
 * particular zone" (should not normally happen for a genuinely banner-watched relevant zone,
 * but handled defensively) -- both fold into BLUE, a real "confirmed no recent sightings"
 * answer. Callers must gate on "has a fetch ever actually succeeded" BEFORE calling this --
 * see App.kt's own hasEverFetchedWatchedZoneStatuses -- a status that's null because there is
 * NO data at all yet is UNKNOWN, not BLUE, and this function is never the place that decides
 * that distinction.
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
// always agree on what RED/YELLOW/BLUE/UNKNOWN actually look like. UNKNOWN's gray is
// deliberately unlike all three real colors -- SightingsMapScreen doesn't actually draw this
// color today (it skips the zone's shading entirely instead, see that file's own comment), but
// the banner does use it directly, and colorForBelugaPresenceStatus needs to stay total either way.
fun colorForBelugaPresenceStatus(status: BelugaPresenceStatus): Color = when (status) {
    BelugaPresenceStatus.RED -> Color(0xFFC62828)
    BelugaPresenceStatus.YELLOW -> Color(0xFFF9A825)
    BelugaPresenceStatus.BLUE -> Color(0xFF0277BD)
    BelugaPresenceStatus.UNKNOWN -> Color(0xFF616161)
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
        // Unreachable in practice -- BelugaPresenceBanner intercepts UNKNOWN before ever calling
        // this function, since kenaiDetail is null whenever status is UNKNOWN by construction
        // (both come from kenaiPresenceState being null). Kept for when-exhaustiveness.
        BelugaPresenceStatus.UNKNOWN -> "STATUS UNKNOWN$zoneSuffix"
    }

/**
 * Bottom bar reflecting [status] for [zoneName]. The caller decides whether to mount this at
 * all -- it's only shown when the user is subscribed to a watched zone or within
 * [DEFAULT_BANNER_PROXIMITY_METERS] of one (App.kt's showPresenceBanner), on top of the
 * screen-based Placement rule. Once mounted, BLUE is a real, shown state, not a hidden one --
 * it never disappears due to its own color, only because neither gate applies anymore.
 *
 * [status] == UNKNOWN renders as its own distinct gray "STATUS UNKNOWN" -- intercepted before
 * either the Kenai or generic label logic below, since neither has anything meaningful to say
 * about a zone with no data yet. [isDataStale] is unrelated to UNKNOWN (the two are mutually
 * exclusive by construction: staleness is measured from the last SUCCESSFUL fetch, which by
 * definition can't exist yet while status is UNKNOWN) -- it flags "the last successful poll is
 * older than [DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS]" as a suffix on a real last-known
 * [status]/[kenaiDetail], never a fallback to a different computation.
 *
 * [kenaiDetail] is non-null only for the Kenai zone (App.kt passes the fetched
 * KenaiPresenceState through) -- every other watched zone renders the original plain label.
 */
@Composable
fun BelugaPresenceBanner(
    status: BelugaPresenceStatus,
    zoneName: String?,
    modifier: Modifier = Modifier,
    kenaiDetail: KenaiPresenceState? = null,
    isDataStale: Boolean = false
) {
    val backgroundColor = colorForBelugaPresenceStatus(status)
    val zoneSuffix = zoneName?.let { " · ${it.uppercase()}" } ?: ""
    val baseLabel = when {
        status == BelugaPresenceStatus.UNKNOWN -> "STATUS UNKNOWN$zoneSuffix"
        kenaiDetail != null -> kenaiBannerLabel(status, kenaiDetail, zoneSuffix)
        else -> when (status) {
            BelugaPresenceStatus.RED -> "BELUGAS PRESENT$zoneSuffix"
            BelugaPresenceStatus.YELLOW -> "POSSIBLE ACTIVITY$zoneSuffix"
            BelugaPresenceStatus.BLUE -> "NO RECENT SIGHTINGS$zoneSuffix"
            // Unreachable (caught by the outer branch above); kept for when-exhaustiveness.
            BelugaPresenceStatus.UNKNOWN -> "STATUS UNKNOWN$zoneSuffix"
        }
    }
    val label = if (isDataStale) "$baseLabel (UPDATING…)" else baseLabel

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
