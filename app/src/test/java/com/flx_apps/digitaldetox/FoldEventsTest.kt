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
    fun `a window that opens over an app and closes again costs the app nothing`() {
        // the game stays resumed underneath the floating window and reports nothing when it closes
        val counts = fold(
            RawEvent(resumed, "game", t0), RawEvent(resumed, "chat", t0 + 10_000),
            RawEvent(paused, "chat", t0 + 20_000), RawEvent(paused, "game", t0 + 100_000)
        )
        assertEquals(100_000L, counts.timeOf("game"))
        assertEquals(10_000L, counts.timeOf("chat"))
    }

    @Test
    fun `apps used at the same time count once in the total`() {
        // two apps side by side for 40 minutes: 40 minutes each, 40 minutes in total, not 80
        val forty = 40 * 60_000L
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(resumed, "b", t0),
            RawEvent(paused, "a", t0 + forty), RawEvent(paused, "b", t0 + forty)
        )
        assertEquals(forty, counts.timeOf("a"))
        assertEquals(forty, counts.timeOf("b"))
        assertEquals(forty, counts.totalScreenTimeMs)
    }

    @Test
    fun `the total adds up separate stretches`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(paused, "a", t0 + 10_000),
            RawEvent(resumed, "b", t0 + 30_000), RawEvent(paused, "b", t0 + 45_000)
        )
        assertEquals(25_000L, counts.totalScreenTimeMs)
    }

    @Test
    fun `the launcher gets no time and no launch`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(paused, "a", t0 + 10_000),
            RawEvent(resumed, launcher, t0 + 10_000), RawEvent(paused, launcher, t0 + 20_000),
            RawEvent(resumed, "a", t0 + 20_000), RawEvent(paused, "a", t0 + 25_000)
        )
        assertEquals(15_000L, counts.timeOf("a"))
        assertEquals(0L, counts.timeOf(launcher))
        assertEquals(2, counts.launchCounts["a"])
        assertEquals(null, counts.launchCounts[launcher])
    }

    @Test
    fun `hopping between an app's own screens is one launch and one stretch of time`() {
        val counts = fold(
            RawEvent(resumed, "a", t0, "A1"), RawEvent(resumed, "a", t0 + 900, "A2"),
            RawEvent(paused, "a", t0 + 1_000, "A1"), RawEvent(paused, "a", t0 + 5_000, "A2")
        )
        assertEquals(1, counts.launchCounts["a"])
        assertEquals(5_000L, counts.timeOf("a"))
        assertEquals(1, counts.sessions.size)
    }

    @Test
    fun `the screen going off closes every open window`() {
        val counts = fold(
            RawEvent(resumed, "a", t0), RawEvent(screenOff, "", t0 + 10_000),
            RawEvent(resumed, "a", t0 + 500_000)
        )
        // off from 10 s to 500 s; the last window runs to the end of the period
        assertEquals(10_000L + (t0 + 1_000_000 - (t0 + 500_000)), counts.timeOf("a"))
        assertEquals(2, counts.launchCounts["a"])
    }

    @Test
    fun `of the windows never reported closed only the latest runs to the end`() {
        val counts = fold(RawEvent(resumed, "a", t0), RawEvent(resumed, "c", t0 + 5_000))
        assertEquals(0L, counts.timeOf("a"))
        assertEquals(1_000_000L - 5_000L, counts.timeOf("c"))
    }
}
