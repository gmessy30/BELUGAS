package com.cookinlet.belugas

private const val DAY_MS = 86_400_000L
private const val HALF_DAY_MS = DAY_MS / 2

/**
 * Quick shortcuts for narrowing the sightings-map playback slider, so users aren't stuck
 * dragging a thumb across years of mostly-empty timeline to find the handful of days that
 * actually have data.
 */
enum class QuickRange(val label: String) {
    TODAY("TODAY"),
    YESTERDAY("YESTERDAY"),
    THIS_SEASON("SEASON"),
    ALL_TIME("ALL TIME"),
    CUSTOM("CUSTOM");

    /**
     * Resolves this shortcut to a [start, end] window in epoch ms, clamped to the actual
     * data range ([dataMinMs], [dataMaxMs]) so a shortcut never expands the slider beyond
     * real data (and never leaves an empty/inverted range).
     */
    fun resolve(dataMinMs: Long, dataMaxMs: Long, nowMs: Long): Pair<Long, Long> {
        val (start, end) = when (this) {
            TODAY -> {
                val (y, m, d) = anchorageDateParts(nowMs)
                anchorageMidnightEpochMs(y, m, d) to nowMs
            }
            YESTERDAY -> {
                // The calendar day before, midnight to midnight -- not "now minus 24h". Found
                // via today's own Anchorage midnight, stepped back half a day (guaranteed to
                // still land within yesterday even on a 23-hour DST-changeover day, unlike a
                // flat 24h subtraction) and re-derived through anchorageDateParts/
                // anchorageMidnightEpochMs so the result is yesterday's own real local
                // midnight, not an arithmetic guess.
                val (ty, tm, td) = anchorageDateParts(nowMs)
                val todayMidnight = anchorageMidnightEpochMs(ty, tm, td)
                val (yy, ym, yd) = anchorageDateParts(todayMidnight - HALF_DAY_MS)
                anchorageMidnightEpochMs(yy, ym, yd) to (todayMidnight - 1)
            }
            THIS_SEASON -> thisSeasonRange(nowMs)
            ALL_TIME, CUSTOM -> dataMinMs to dataMaxMs
        }
        val clampedStart = start.coerceIn(dataMinMs, dataMaxMs)
        val clampedEnd = end.coerceIn(dataMinMs, dataMaxMs)
        return clampedStart to (if (clampedEnd <= clampedStart) (clampedStart + 1000L).coerceAtMost(dataMaxMs) else clampedEnd)
    }
}

// Same two disjoint windows as PresenceBanner.kt's isKenaiInSeasonLocally (FALL_SEASON_START/
// END, SPRING_SEASON_START/END) -- deliberately kept in sync rather than re-derived here, since
// two definitions of "season" in one app (this file used to have its own separate May-Sep
// window) is exactly how they drift apart.
private const val FALL_START_MONTH = 8
private const val FALL_START_DAY = 15
private const val FALL_END_MONTH = 12
private const val FALL_END_DAY = 31
private const val SPRING_START_MONTH = 3
private const val SPRING_START_DAY = 15
private const val SPRING_END_MONTH = 5
private const val SPRING_END_DAY = 14

/**
 * "Season" resolves to whichever window is actually relevant right now, backward-looking only
 * -- never a future window with no data in it yet:
 * - Inside a window: that window, from its start through now (it may still be ongoing).
 * - Between windows: whichever one ended most recently. The two windows strictly alternate
 *   (spring, gap, fall, gap, spring, ...), so whichever gap `now` falls in has exactly one
 *   completed window behind it and one not-yet-started window ahead of it -- no "which is
 *   closer" comparison needed, just the one behind.
 */
private fun thisSeasonRange(nowMs: Long): Pair<Long, Long> {
    val (year, _, _) = anchorageDateParts(nowMs)

    val springStart = anchorageMidnightEpochMs(year, SPRING_START_MONTH, SPRING_START_DAY)
    val springEnd = anchorageMidnightEpochMs(year, SPRING_END_MONTH, SPRING_END_DAY) + DAY_MS - 1
    val fallStart = anchorageMidnightEpochMs(year, FALL_START_MONTH, FALL_START_DAY)
    val fallEnd = anchorageMidnightEpochMs(year, FALL_END_MONTH, FALL_END_DAY) + DAY_MS - 1

    return when {
        nowMs in springStart..springEnd -> springStart to nowMs
        nowMs in fallStart..fallEnd -> fallStart to nowMs
        // Jan 1 through Mar 14: this year's spring hasn't started (future, excluded) -- the one
        // completed window behind `now` is LAST year's fall.
        nowMs < springStart -> {
            val previousFallStart = anchorageMidnightEpochMs(year - 1, FALL_START_MONTH, FALL_START_DAY)
            val previousFallEnd = anchorageMidnightEpochMs(year - 1, FALL_END_MONTH, FALL_END_DAY) + DAY_MS - 1
            previousFallStart to previousFallEnd
        }
        // May 15 through Aug 14: this year's fall hasn't started (future, excluded) -- the
        // completed window behind `now` is this year's spring.
        else -> springStart to springEnd
    }
}
