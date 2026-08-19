package com.cookinlet.belugas

private const val DAY_MS = 86_400_000L

/**
 * Quick shortcuts for narrowing the sightings-map playback slider, so users aren't stuck
 * dragging a thumb across years of mostly-empty timeline to find the handful of days that
 * actually have data.
 */
enum class QuickRange(val label: String) {
    LAST_7_DAYS("7 DAYS"),
    LAST_30_DAYS("30 DAYS"),
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
            LAST_7_DAYS -> (nowMs - 7 * DAY_MS) to nowMs
            LAST_30_DAYS -> (nowMs - 30 * DAY_MS) to nowMs
            THIS_SEASON -> thisSeasonRange(nowMs)
            ALL_TIME, CUSTOM -> dataMinMs to dataMaxMs
        }
        val clampedStart = start.coerceIn(dataMinMs, dataMaxMs)
        val clampedEnd = end.coerceIn(dataMinMs, dataMaxMs)
        return clampedStart to (if (clampedEnd <= clampedStart) (clampedStart + 1000L).coerceAtMost(dataMaxMs) else clampedEnd)
    }
}

/** Cook Inlet / St. Lawrence belugas are most reliably observed roughly May through September. */
private fun thisSeasonRange(nowMs: Long): Pair<Long, Long> {
    val (year, month, _) = civilFromEpochMs(nowMs)
    val seasonYear = if (month < 5) year - 1 else year
    val start = epochMsFromCivil(seasonYear, 5, 1)
    val end = epochMsFromCivil(seasonYear, 9, 30) + (DAY_MS - 1)
    return start to minOf(end, nowMs)
}

// Civil calendar <-> days-since-epoch conversion (Howard Hinnant's "days_from_civil" /
// "civil_from_days" algorithm: http://howardhinnant.github.io/date_algorithms.html), used
// instead of pulling in a full date/calendar library for these two boundary calculations.
// Assumes non-negative epoch ms (always true for sighting timestamps, all long after 1970),
// so plain truncating division is equivalent to floor division here.
private fun daysFromCivil(y: Long, m: Long, d: Long): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

private fun civilFromDays(z: Long): Triple<Long, Long, Long> {
    val zz = z + 719468L
    val era = (if (zz >= 0) zz else zz - 146096L) / 146097L
    val doe = zz - era * 146097L
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple(y + if (m <= 2) 1 else 0, m, d)
}

private fun epochMsFromCivil(year: Int, month: Int, day: Int): Long =
    daysFromCivil(year.toLong(), month.toLong(), day.toLong()) * DAY_MS

private fun civilFromEpochMs(epochMs: Long): Triple<Int, Int, Int> {
    val (y, m, d) = civilFromDays(epochMs / DAY_MS)
    return Triple(y.toInt(), m.toInt(), d.toInt())
}
