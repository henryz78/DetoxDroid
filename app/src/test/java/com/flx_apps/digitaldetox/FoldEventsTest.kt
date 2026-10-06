package com.flx_apps.digitaldetox

import android.app.usage.UsageEvents
import com.flx_apps.digitaldetox.system_integration.UsageStatsProvider
import com.flx_apps.digitaldetox.system_integration.UsageStatsProvider.RawEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldEventsTest {
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED
    private val screenOff = UsageEvents.Event.SCREEN_NON_INTERACTIVE
    private val t0 = 1_000_000L
    private val launcher = "launcher"

    private fun fold(vararg events: RawEvent, endMs: Long = t0 + 1_000_000) =
        UsageStatsProvider.foldEvents(events.asSequence(), endMs, setOf(launcher))

    private fun UsageStatsProvider.EventCounts.timeOf(pkg: String) =
        sessions.filter { it.packageName == pkg }.sumOf { it.totalTimeMs }

    @Test
    fun `only one app is in front at a time`() {
        // a never reports a pause, so b's arrival has to end it
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(resumed, "b", t0 + 10_000),
            RawEvent(paused, "b", t0 + 20_000)
        )
        assertEquals(10_000L, counts.timeOf("a"))
        assertEquals(10_000L, counts.timeOf("b"))
    }

    @Test
    fun `the launcher gets no time and no launch but ends the app under it`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(resumed, launcher, t0 + 10_000),
            RawEvent(resumed, "a", t0 + 20_000), RawEvent(paused, "a", t0 + 25_000)
        )
        assertEquals(15_000L, counts.timeOf("a"))
        assertEquals(0L, counts.timeOf(launcher))
        assertEquals(2, counts.launchCounts["a"])
        assertEquals(null, counts.launchCounts[launcher])
    }

    @Test
    fun `hopping between an app's own screens is one launch`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(paused, "a", t0 + 1_000),
            RawEvent(resumed, "a", t0 + 1_000), RawEvent(paused, "a", t0 + 5_000)
        )
        assertEquals(1, counts.launchCounts["a"])
        assertEquals(5_000L, counts.timeOf("a"))
    }

    @Test
    fun `the screen going off ends the session even without a pause`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(screenOff, "", t0 + 10_000),
            RawEvent(resumed, "a", t0 + 500_000)
        )
        // the screen was off from 10 s to 500 s; the last session runs to the end of the period
        assertEquals(10_000L + (t0 + 1_000_000 - (t0 + 500_000)), counts.timeOf("a"))
        assertEquals(2, counts.launchCounts["a"])
    }
}
