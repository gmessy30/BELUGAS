package com.cookinlet.belugas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Guards isKenaiInSeasonLocally's two windows (Aug 15-Dec 31, Mar 15-May 14) against drift --
// see that function's own comment in PresenceBanner.kt for where these exact "MM-DD" literals
// also need to match get_kenai_presence_state's SQL side (20260906000000_expand_kenai_season_
// gate_to_two_windows.sql). This repo has no DB credentials to run that SQL function in CI, so
// this test is the only automated boundary check that exists -- the SQL side stays hand-verified
// against these same 8 dates.
class PresenceBannerSeasonGateTest {

    // Days-from-civil (Howard Hinnant's algorithm, same one PlaybackRange.kt uses privately for
    // its own unrelated May-Sep season range) -- duplicated here rather than shared, since the
    // two files have no reason to depend on each other's date math otherwise.
    private fun daysFromCivil(y: Long, m: Long, d: Long): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    // Noon UTC on the given date -- Anchorage is UTC-8 (AKDT) or UTC-9 (AKST) depending on DST,
    // so noon UTC always lands at 09:00-10:00 local, comfortably inside the same Anchorage
    // calendar day regardless of which offset is in effect on that date. That keeps these test
    // dates unambiguous without needing to track Alaska's DST transition dates here.
    private fun epochMsAtAnchorageNoonUtc(year: Int, month: Int, day: Int): Long =
        daysFromCivil(year.toLong(), month.toLong(), day.toLong()) * 86_400_000L + 12 * 60 * 60 * 1000L

    @Test
    fun dayBeforeFallStart_isOutOfSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 8, 14)
        assertFalse(isKenaiInSeasonLocally(ms), "Aug 14 is one day before the padded fall start and should be out of season")
    }

    @Test
    fun fallStartDay_isInSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 8, 15)
        assertTrue(isKenaiInSeasonLocally(ms), "Aug 15 is the padded fall start and should be in season")
    }

    @Test
    fun fallEndDay_isInSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 12, 31)
        assertTrue(isKenaiInSeasonLocally(ms), "Dec 31 is the fall end and should still be in season")
    }

    @Test
    fun dayAfterFallEnd_isOutOfSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2027, 1, 1)
        assertFalse(isKenaiInSeasonLocally(ms), "Jan 1 is one day after the fall end and should be out of season")
    }

    @Test
    fun dayBeforeSpringStart_isOutOfSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 3, 14)
        assertFalse(isKenaiInSeasonLocally(ms), "Mar 14 is one day before the spring start and should be out of season")
    }

    @Test
    fun springStartDay_isInSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 3, 15)
        assertTrue(isKenaiInSeasonLocally(ms), "Mar 15 is the spring start and should be in season")
    }

    @Test
    fun springEndDay_isInSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 5, 14)
        assertTrue(isKenaiInSeasonLocally(ms), "May 14 is the padded spring end and should still be in season")
    }

    @Test
    fun dayAfterSpringEnd_isOutOfSeason() {
        val ms = epochMsAtAnchorageNoonUtc(2026, 5, 15)
        assertFalse(isKenaiInSeasonLocally(ms), "May 15 is one day after the padded spring end and should be out of season")
    }
}
