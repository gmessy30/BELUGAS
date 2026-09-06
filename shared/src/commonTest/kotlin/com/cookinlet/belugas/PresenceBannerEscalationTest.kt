package com.cookinlet.belugas

import kotlin.test.Test
import kotlin.test.assertEquals

// Guards effectiveKenaiPresenceStatus's stale RED/YELLOW escalation now that
// get_kenai_presence_state's YELLOW is a pure recency claim (see 20260906010000_simplify_
// yellow_to_pure_recency.sql): a stale YELLOW gets the SAME first+second boundary treatment as a
// stale RED -- there's no more "genuine vs. just-expired" distinction to give YELLOW only one
// boundary of grace. The two sets of cases below are deliberately parallel (same boundary math,
// same offsets) to make that symmetry visible rather than just asserted.
class PresenceBannerEscalationTest {

    // Comfortably past DEFAULT_PRESENCE_STALENESS_THRESHOLD_MS (15 min) so every case below
    // actually exercises escalation instead of hitting the early "return serverStatus verbatim"
    // path.
    private val fetchedAtMs = 0L
    private val firstBoundaryMs = 1_000_000L
    private val secondBoundaryMs = firstBoundaryMs + KENAI_SECOND_BOUNDARY_OFFSET_MS

    private fun snapshotOf(phase: String) = KenaiPresenceSnapshot(
        detail = KenaiPresenceState(
            phase = phase,
            inSeason = true,
            nextCycleLowEpochMs = firstBoundaryMs
        ),
        fetchedAtMs = fetchedAtMs
    )

    @Test
    fun staleRed_beforeFirstBoundary_staysRed() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("RED"), firstBoundaryMs - 1)
        assertEquals(BelugaPresenceStatus.RED, status)
    }

    @Test
    fun staleRed_justAfterFirstBoundary_stepsDownToYellow() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("RED"), firstBoundaryMs + 1)
        assertEquals(BelugaPresenceStatus.YELLOW, status)
    }

    @Test
    fun staleRed_justBeforeSecondBoundary_isStillYellow() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("RED"), secondBoundaryMs - 1)
        assertEquals(BelugaPresenceStatus.YELLOW, status)
    }

    @Test
    fun staleRed_atSecondBoundary_escalatesToUnknown() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("RED"), secondBoundaryMs)
        assertEquals(BelugaPresenceStatus.UNKNOWN, status)
    }

    // The parallel YELLOW cases: a stale YELLOW must behave exactly like RED's second half above
    // (RED already decayed to YELLOW by firstBoundaryMs+1) -- same secondBoundaryMs cutoff, not
    // the single-boundary cutoff YELLOW used to get.

    @Test
    fun staleYellow_beforeFirstBoundary_staysYellow() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("YELLOW"), firstBoundaryMs - 1)
        assertEquals(BelugaPresenceStatus.YELLOW, status)
    }

    @Test
    fun staleYellow_justAfterFirstBoundary_staysYellow() {
        // This is the behavior that changed: the old rule escalated a stale YELLOW to UNKNOWN
        // here (a single boundary of grace). Now it matches RED's second half exactly.
        val status = effectiveKenaiPresenceStatus(snapshotOf("YELLOW"), firstBoundaryMs + 1)
        assertEquals(BelugaPresenceStatus.YELLOW, status)
    }

    @Test
    fun staleYellow_justBeforeSecondBoundary_isStillYellow() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("YELLOW"), secondBoundaryMs - 1)
        assertEquals(BelugaPresenceStatus.YELLOW, status)
    }

    @Test
    fun staleYellow_atSecondBoundary_escalatesToUnknown() {
        val status = effectiveKenaiPresenceStatus(snapshotOf("YELLOW"), secondBoundaryMs)
        assertEquals(BelugaPresenceStatus.UNKNOWN, status)
    }
}
