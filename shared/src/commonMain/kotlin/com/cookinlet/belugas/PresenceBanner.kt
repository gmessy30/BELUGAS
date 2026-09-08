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

// -------------------------------------------------------------------------------------------
// Staleness ESCALATION: a device that's been offline long enough can't just keep showing a
// last-known RED/YELLOW/exempted-BLUE forever with an "(UPDATING...)" suffix (see
// DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS above) -- at some point the claim itself is no
// longer trustworthy and the banner needs to admit it doesn't know, same motivation as UNKNOWN
// existing at all. This only ever moves a status TOWARD UNKNOWN, never invents a fresher one --
// the real value still only ever changes on an actual successful poll.
//
// Cycle boundaries are measured off get_kenai_presence_state's own nextCycleLowEpochMs -- an
// astronomical tide prediction, so it stays valid for the whole outage even though the
// SIGHTING data (what actually makes RED/YELLOW true) can't be refreshed. Only the sighting
// side goes stale; the boundary times don't.
//
// Flat second-boundary offset past the first cycle boundary, rather than predicting the next
// low from the observed current-to-next interval: low-to-low intervals alternate long/short
// (diurnal inequality), so using the preceding interval to predict the next is
// anti-correlated -- it overshoots after a long cycle and undershoots after a short one. A
// flat offset sidesteps that. 12h is the observed MINIMUM low-to-low interval at station 3503
// (real sample: 13:24, 12:08, 13:29, 12:14, 12:45, 12:13, 12:43) -- deliberately the minimum,
// not the average, because the two error directions aren't symmetric: escalating a cycle early
// just shows "STATUS UNKNOWN" slightly sooner, while escalating late means showing YELLOW for
// a stretch nobody actually has sighting data for. Do not "improve" this into an average of
// observed intervals -- that trades the safe direction away.
const val KENAI_SECOND_BOUNDARY_OFFSET_MS = 12L * 60 * 60 * 1000

// When nextCycleLowEpochMs itself is null (the tide predictor hasn't computed that far ahead),
// there's no real boundary timestamp to escalate against -- fall back to a flat ceiling
// measured from the last successful fetch instead. 15h comfortably exceeds every observed
// low-to-low interval at station 3503 (12:08-13:29), so by 15h a real cycle boundary is
// guaranteed to have passed even without knowing exactly when.
const val KENAI_NULL_BOUNDARY_CEILING_MS = 15L * 60 * 60 * 1000

// Ceiling for the two BLUE sub-states get_kenai_presence_state's predicate exempts from
// ordinary cycle-boundary escalation ("NOT EXPECTED THIS TIME OF YEAR" and "PREDICTION
// UNAVAILABLE") -- both non-cycle-scoped claims, so they share one constant rather than each
// getting its own. Strictly, an exempted BLUE is blind to a qualifying sighting landing during
// the outage (RED only ever persists one cycle, so correctness alone would argue for the same
// cycle-scoped rule as everything else) -- but out of season a sighting is rare, and
// escalating every ~12h would leave the banner gray most of the winter for anyone not
// constantly online, training people to ignore it. Alert fatigue is the bigger risk for this
// app, so 24h is the deliberate middle: short enough that no device carries a confident
// all-clear across a meaningful stretch of season, long enough the banner stays meaningful
// rather than perpetually "STATUS UNKNOWN" all winter. Same constant PREDICTION_UNAVAILABLE
// already implied -- not a second number to keep in sync.
const val KENAI_EXEMPT_BLUE_CEILING_MS = 24L * 60 * 60 * 1000

// Applied to gate_time_possible_epoch_ms once, at render, in kenaiBannerLabel below --
// kenai_gate_time itself returns the raw geometric formula result with zero margin by design
// (see that function's own header comment: the offset is deliberately the CALLER's job, not
// folded into the formula). The bias runs EARLY, not late: someone who checks the banner and
// waits three extra minutes for nothing has lost three minutes; someone told "not before X" who
// treats that as a green light and misses the entrance because the tide was already at gate
// depth by X has missed the whole point of the banner. Not rounded to 5 or 10 minutes --
// kenai_gate_time is exact to the minute (worst disagreement verified against 18 hand-tabulated
// logbook cycles was 1.4 minutes), and rounding to a coarser grain would throw away precision
// the formula actually has for no benefit.
const val KENAI_GATE_TIME_EARLY_BIAS_MS = 3L * 60 * 1000

// Pure local recompute of get_kenai_presence_state's own SEASON GATE (two disjoint windows,
// America/Anchorage -- see that function's header comment), used only to catch a device that's
// been offline across a season boundary itself and is holding a now-wrong "NOT EXPECTED THIS
// TIME OF YEAR" claim. This is a DIFFERENT failure than plain elapsed-time staleness --
// crossing a calendar boundary isn't something KENAI_EXEMPT_BLUE_CEILING_MS's ceiling alone
// catches (a device could still be well inside the 24h window and yet already be in a new
// season) -- so both run, not either.
//
// FALL_SEASON_START/END and SPRING_SEASON_START/END keep the SAME LITERAL "MM-DD" STRINGS as
// get_kenai_presence_state's own v_in_season -- a reviewer diffing this file against that
// migration should be able to confirm the two match by eye, not just trust the range logic is
// equivalent. Neither window wraps the Dec 31/Jan 1 year turn, so plain string comparison
// within a single calendar year is safe -- see PresenceBannerSeasonGateTest for the exact
// boundary dates this is expected to hold for.
//
// BOUNDARIES: five years of personal sighting records put the earliest fall arrival at Aug 21
// and the latest spring departure at May 7 (each observed 2021-2025). The windows below pad
// roughly a week past both observed extremes in the direction that matters -- so a slightly
// early or slightly late animal still gets a real BLUE/prediction claim instead of "NOT EXPECTED
// THIS TIME OF YEAR" -- not a claim that these are the true biological limits. Aug 15/Dec 31 and
// Mar 15/May 14 are a judgment call, not derived data; revisit them if more seasons of records
// push either observed extreme wider than the current padding.
private const val FALL_SEASON_START = "08-15" // padding; earliest observed fall arrival is Aug 21
private const val FALL_SEASON_END = "12-31"
private const val SPRING_SEASON_START = "03-15"
private const val SPRING_SEASON_END = "05-14" // padding; latest observed spring departure is May 7

fun isKenaiInSeasonLocally(nowMs: Long): Boolean {
    val monthDay = anchorageMonthDay(nowMs)
    return monthDay in FALL_SEASON_START..FALL_SEASON_END ||
        monthDay in SPRING_SEASON_START..SPRING_SEASON_END
}

// Pairs a fetched KenaiPresenceState with the wall-clock time it was fetched at -- the two are
// always set together (see App.kt's polling loop) and effectiveKenaiPresenceStatus needs both,
// so this makes them impossible to split. They used to be two separate nullable vars in
// App.kt; a caller could (and did, briefly) hand the state to the tick loop with no fetch
// time, and the `?:` fallback that covered that gap defaulted to "elapsed time is zero" --
// i.e. never escalate, the unsafe direction for a feature whose whole point is escalating.
data class KenaiPresenceSnapshot(val detail: KenaiPresenceState, val fetchedAtMs: Long)

/**
 * The banner/map's actually-displayed Kenai status: [snapshot]'s server phase, escalated
 * toward UNKNOWN if [nowMs] has drifted too far past [snapshot]'s fetch time (or past a known
 * cycle boundary) for that phase to still be trustworthy. See the constants above for each
 * ceiling's reasoning.
 *
 * Escalation only ever engages once the snapshot itself is stale
 * (DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS since [snapshot]'s fetch time -- the same threshold
 * that drives the banner's "(UPDATING…)" suffix). Below that, the server phase is returned
 * verbatim regardless of where "now" sits relative to a cycle boundary -- boundaries land twice
 * a day for EVERY device, healthy or not, so escalating on elapsed time alone would gray out
 * the banner for the few minutes between a boundary passing and the next poll landing, on every
 * healthy device, right at the tide turn -- precisely when someone's likely on the bank
 * watching. A genuinely offline device clears DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS long
 * before any of these boundaries or ceilings matter, so gating here doesn't blunt what this
 * function exists to catch.
 *
 * - RED persists until the first cycle boundary (matches the server's own persistence rule),
 *   then reads as YELLOW until the second boundary, then UNKNOWN.
 * - A server-returned YELLOW gets the SAME first+second boundary treatment as RED, not a single
 *   boundary -- get_kenai_presence_state's YELLOW is a pure recency claim spanning the 3
 *   completed cycles before whichever cycle is current (see that function's own comment), so a
 *   YELLOW fetched at any point in that span is just as likely to still be a real YELLOW two
 *   boundaries later as a RED is. There's no "genuine vs. just-expired" distinction left to draw
 *   server-side -- every YELLOW now has the same multi-cycle persistence shape.
 * - A real, non-exempt BLUE ("NEXT WINDOW ..." / the "NO RECENT SIGHTINGS" fallback) is the one
 *   status with no persistence mechanism behind it at all -- a plain "nothing recent" claim, so
 *   it stays cycle-scoped to ONE boundary, straight to UNKNOWN. This is the case the whole
 *   feature exists to close: a stale "no recent sightings" during viewing season is the exact
 *   false all-clear get_kenai_presence_state's own RED-persistence rule was designed to avoid.
 * - The two EXEMPTED BLUE sub-states (out-of-season, prediction-unavailable) are the only ones
 *   that don't use the cycle boundary at all -- they escalate to UNKNOWN past
 *   KENAI_EXEMPT_BLUE_CEILING_MS instead, or immediately if the local season recompute now
 *   disagrees with a stored "not expected" claim (still gated on staleness first, same rule).
 */
fun effectiveKenaiPresenceStatus(
    snapshot: KenaiPresenceSnapshot,
    nowMs: Long
): BelugaPresenceStatus {
    val detail = snapshot.detail
    val lastFetchAtMs = snapshot.fetchedAtMs
    val serverStatus = belugaPresenceStatusFromKenaiPhase(detail.phase)

    if (nowMs - lastFetchAtMs <= DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS) {
        return serverStatus
    }

    val firstBoundaryMs = detail.nextCycleLowEpochMs ?: (lastFetchAtMs + KENAI_NULL_BOUNDARY_CEILING_MS)

    if (serverStatus == BelugaPresenceStatus.BLUE) {
        val isExemptOutOfSeason = !detail.inSeason
        val isExemptNoPrediction = detail.inSeason && !detail.predictionAvailable
        if (!isExemptOutOfSeason && !isExemptNoPrediction) {
            return if (nowMs < firstBoundaryMs) BelugaPresenceStatus.BLUE else BelugaPresenceStatus.UNKNOWN
        }
        if (isExemptOutOfSeason && isKenaiInSeasonLocally(nowMs)) {
            return BelugaPresenceStatus.UNKNOWN
        }
        return if (nowMs - lastFetchAtMs > KENAI_EXEMPT_BLUE_CEILING_MS) {
            BelugaPresenceStatus.UNKNOWN
        } else {
            BelugaPresenceStatus.BLUE
        }
    }

    return when (serverStatus) {
        BelugaPresenceStatus.RED -> {
            val secondBoundaryMs = firstBoundaryMs + KENAI_SECOND_BOUNDARY_OFFSET_MS
            when {
                nowMs < firstBoundaryMs -> BelugaPresenceStatus.RED
                nowMs < secondBoundaryMs -> BelugaPresenceStatus.YELLOW
                else -> BelugaPresenceStatus.UNKNOWN
            }
        }
        BelugaPresenceStatus.YELLOW -> {
            val secondBoundaryMs = firstBoundaryMs + KENAI_SECOND_BOUNDARY_OFFSET_MS
            if (nowMs < secondBoundaryMs) BelugaPresenceStatus.YELLOW else BelugaPresenceStatus.UNKNOWN
        }
        // Unreachable -- belugaPresenceStatusFromKenaiPhase never returns BLUE/UNKNOWN here
        // (BLUE handled above, UNKNOWN never produced by it at all). Kept for exhaustiveness.
        else -> serverStatus
    }
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
// WHEN these fire, not what they say once they have (except RED's own "· CHECK MAP" suffix --
// see that case's own comment). BLUE is the one that actually differs: it's
// not a single state here but three (see get_kenai_presence_state's own header comment) --
// out-of-season, in-season-but-no-usable-gate-time, and the real "here's when to expect them"
// case, each needing different text rather than one generic "no recent sightings."
//
// The third case renders gate_time_possible_epoch_ms (0.3m) only -- gate_time_likely_epoch_ms
// (0.8m) stays in the model, unused here, pending a future surface for it. Branches on the
// gate-time field itself (not detail.predictionAvailable) so Kotlin can smart-cast it non-null
// in the branch body below -- the two are equivalent by the server's own definition
// (20260909000000_return_gate_times_from_get_kenai_presence_state.sql), so this is purely to
// avoid a !! rather than a different claim.
private fun kenaiBannerLabel(status: BelugaPresenceStatus, detail: KenaiPresenceState, zoneSuffix: String): String =
    when (status) {
        // RED means a qualifying sighting actually landed this cycle -- the map has something
        // worth looking at right now, so it points there directly. YELLOW deliberately does NOT
        // get the same suffix: it's a recency caution (nobody's seen them for a cycle or more),
        // not a live sighting, and sending someone to the map for something hours old is weaker
        // advice that would dilute "CHECK MAP" as a signal for when RED actually needs it.
        BelugaPresenceStatus.RED -> "BELUGAS PRESENT · CHECK MAP$zoneSuffix"
        BelugaPresenceStatus.YELLOW -> "POSSIBLE ACTIVITY$zoneSuffix"
        BelugaPresenceStatus.BLUE -> when {
            !detail.inSeason -> "NOT EXPECTED THIS TIME OF YEAR$zoneSuffix"
            detail.gateTimePossibleEpochMs == null -> "TIDE DATA UNAVAILABLE$zoneSuffix"
            else -> {
                val biasedGateTimeMs = detail.gateTimePossibleEpochMs - KENAI_GATE_TIME_EARLY_BIAS_MS
                "NOT EXPECTED IN THE RIVER BEFORE ${formatTime12Hour(biasedGateTimeMs)}$zoneSuffix"
            }
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
            // Same "· CHECK MAP" reasoning as kenaiBannerLabel's RED case above -- RED is a real
            // qualifying sighting this cycle regardless of which watched zone it's for.
            BelugaPresenceStatus.RED -> "BELUGAS PRESENT · CHECK MAP$zoneSuffix"
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
