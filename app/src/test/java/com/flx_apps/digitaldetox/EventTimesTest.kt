package com.flx_apps.digitaldetox

import com.flx_apps.digitaldetox.data.DailyAppUsage
import com.flx_apps.digitaldetox.system_integration.AppScreenTime
import com.flx_apps.digitaldetox.ui.screens.usage_stats.UsageStatsViewModel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTimesTest {
    private val day = LocalDate.of(2026, 10, 3)
    private val hour = 3_600_000L

    private fun row(date: LocalDate, pkg: String, timeMs: Long, launches: Int = 7) = DailyAppUsage(
        rowId = DailyAppUsage.createRowId(date, pkg), date = date, packageName = pkg,
        totalTimeMs = timeMs, sessionCount = 1, launchCount = launches, scrollCount = 3,
        breakCount = 2, blockCount = 1
    )

    private fun merged(rows: List<DailyAppUsage>, eventDay: Map<String, Long>?) =
        UsageStatsViewModel.withEventTimes(
            rows,
            if (eventDay == null) emptyMap()
            else mapOf(day to eventDay.mapValues { (pkg, ms) -> AppScreenTime(pkg, ms) })
        ).associateBy { it.packageName }

    @Test
    fun `an inflated stored time is replaced by the counted one and the counters stay`() {
        val result = merged(listOf(row(day, "game", 11 * hour)), mapOf("game" to hour / 2))
        assertEquals(hour / 2, result["game"]!!.totalTimeMs)
        assertEquals(7, result["game"]!!.launchCount)
        assertEquals(2, result["game"]!!.breakCount)
    }

    @Test
    fun `a stored app the log has no time for on that day gets none`() {
        val result = merged(listOf(row(day, "old", hour)), mapOf("other" to 1_000L))
        assertEquals(0L, result["old"]!!.totalTimeMs)
        assertEquals(1_000L, result["other"]!!.totalTimeMs)
    }

    @Test
    fun `days the log does not cover are left alone`() {
        val result = merged(listOf(row(day, "game", 11 * hour)), null)
        assertEquals(11 * hour, result["game"]!!.totalTimeMs)
    }

    @Test
    fun `an app only the log knows gets a row without counters`() {
        val result = merged(emptyList(), mapOf("new" to 5_000L))
        assertEquals(5_000L, result["new"]!!.totalTimeMs)
        assertEquals(0, result["new"]!!.launchCount)
    }
}
