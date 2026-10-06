package com.flx_apps.digitaldetox.system_integration

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.flx_apps.digitaldetox.DetoxDroidApplication
import com.flx_apps.digitaldetox.TenSecondsInMs
import com.flx_apps.digitaldetox.util.NavigationUtil
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/** How long one app was in front of the user. */
data class AppScreenTime(val packageName: String, val screenTimeMs: Long)

/** [totalMs] counts overlapping apps once, so it can be less than the sum of [perApp]. */
data class PeriodScreenTime(val perApp: Map<String, AppScreenTime>, val totalMs: Long)

object UsageStatsProvider {

    /**
     * Two foreground phases of the same app separated by less than this gap are counted as one
     * user-perceived "session" in [groupSessionCounts].
     */
    private const val SESSION_GAP_THRESHOLD_MS = 5L * 60L * 1000L

    private val usageStatsManager: UsageStatsManager
        get() = DetoxDroidApplication.appContext.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

    private var usageStatsTodayLastRefresh = 0L

    /** Time in front of the screen today, apps used at the same time counted once. */
    var screenTimeTodayMs = 0L
        private set

    var usageStatsToday: Map<String, AppScreenTime> = mapOf()
        get() {
            val now = System.currentTimeMillis()
            if (now - usageStatsTodayLastRefresh > TenSecondsInMs) {
                val dayBeginningMs =
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val today = queryPeriod(dayBeginningMs, now)
                field = today.perApp
                screenTimeTodayMs = today.totalMs
                usageStatsTodayLastRefresh = now
            }
            return field
        }

    fun getUpdatedUsageStatsToday(): Map<String, AppScreenTime> {
        usageStatsTodayLastRefresh = 0L
        return usageStatsToday
    }

    fun getScreenTimeForApps(apps: List<String>): Long {
        return apps.sumOf { usageStatsToday[it]?.screenTimeMs ?: 0L }
    }

    /**
     * Screen time per app for [startMs]..[endMs], summed from the foreground sessions in the usage
     * event log. The OS's own per-app totals are not used: they add up the time each app's window
     * was visible or resumed, so apps that overlap (the launcher behind everything, a floating
     * window, a widget's host app) are counted at the same time and a day can add up to far more
     * than the time the screen was on.
     */
    fun queryForPeriod(startMs: Long, endMs: Long): Map<String, AppScreenTime> =
        queryPeriod(startMs, endMs).perApp

    /**
     * Screen time of one period: per app, where apps used at the same time (split screen, a
     * floating window) each get their own time, and in total, which only counts the time spent in
     * front of the screen. Two apps used side by side for 40 minutes are 40 minutes each and
     * 40 minutes in total, not 80.
     */
    fun queryPeriod(startMs: Long, endMs: Long): PeriodScreenTime {
        val events = queryEventCounts(startMs, endMs)
        return PeriodScreenTime(
            perApp = events.sessions.groupBy { it.packageName }.mapValues { (pkg, sessions) ->
                AppScreenTime(pkg, sessions.sumOf { it.totalTimeMs })
            },
            totalMs = events.totalScreenTimeMs
        )
    }

    /**
     * The phone itself rather than an app anyone spends time in: the system UI and every home
     * launcher. The system's own screen time leaves them out as well.
     */
    private fun nonAppPackages(): Set<String> =
        DetoxDroidApplication.appContext.packageManager
            .queryIntentActivities(NavigationUtil.homeScreenIntent(), 0)
            .mapTo(mutableSetOf("com.android.systemui")) { it.activityInfo.packageName }

    data class SessionInfo(
        val packageName: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val totalTimeMs: Long
    )

    data class EventCounts(
        val launchCounts: Map<String, Int>,
        val hourBuckets: IntArray,
        val unlockCount: Int,
        val unlockHourBuckets: IntArray,
        val sessions: List<SessionInfo>,
        /** Time in front of the screen: the sessions of all apps, overlaps counted once. */
        val totalScreenTimeMs: Long
    )

    /**
     * One entry of the OS usage-event log, reduced to what [foldEvents] reads. [className] tells
     * the windows of one app apart.
     */
    data class RawEvent(
        val type: Int, val packageName: String, val timeMs: Long, val className: String = packageName
    )

    /**
     * Walks the OS usage-event log for [startMs]..[endMs] and derives per-app launch counts,
     * launches-per-hour buckets, unlock counts (keyguard dismissals) and raw foreground sessions.
     *
     * Note: the OS keeps the event log only for a limited window (typically about a week), so for
     * longer ranges the results cover just the retained tail and undercount the full period.
     */
    fun queryEventCounts(startMs: Long, endMs: Long): EventCounts {
        val events = usageStatsManager.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()
        val raw = sequence {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: ""
                yield(RawEvent(event.eventType, pkg, event.timeStamp, event.className ?: pkg))
            }
        }
        return foldEvents(raw, endMs, nonAppPackages())
    }

    /**
     * Turns the usage-event log into [EventCounts]. Every window (activity) is followed from the
     * moment it resumes to the moment it pauses or stops, so a floating window or a pop-up that
     * opens over an app and closes again does not cost the app the time underneath. Windows of
     * one app that overlap or touch count once, and the screen going off or a shutdown closes
     * everything still open. A window whose close was never reported would run forever, so at
     * the end of the period only the most recently opened one is kept, up to [endMs].
     *
     * [nonApps] (the launcher, the system UI) get no time and no launch. An app is launched when
     * it comes to the front after a different package, or after the screen was off; hopping
     * between its own screens is not a launch.
     */
    fun foldEvents(events: Sequence<RawEvent>, endMs: Long, nonApps: Set<String>): EventCounts {
        val launchCounts = mutableMapOf<String, Int>()
        val hourBuckets = IntArray(24)
        val unlockHourBuckets = IntArray(24)
        var unlockCount = 0
        val intervals = mutableMapOf<String, MutableList<LongRange>>()
        val cal = Calendar.getInstance()

        class Window(val packageName: String, val startMs: Long)

        val open = mutableMapOf<String, Window>()
        var lastPkg: String? = null

        fun close(window: Window, endTimeMs: Long) {
            if (window.packageName !in nonApps && endTimeMs > window.startMs) {
                intervals.getOrPut(window.packageName) { mutableListOf() }
                    .add(window.startMs..endTimeMs)
            }
        }

        for (event in events) {
            when (event.type) {
                // same wire values as the deprecated MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND,
                // so pre-API-29 events are matched as well
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (event.packageName !in nonApps && event.packageName != lastPkg) {
                        launchCounts.merge(event.packageName, 1, Int::plus)
                        cal.timeInMillis = event.timeMs
                        hourBuckets[cal.get(Calendar.HOUR_OF_DAY)]++
                    }
                    lastPkg = event.packageName
                    open[event.className] = Window(event.packageName, event.timeMs)
                }

                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    open.remove(event.className)?.let { close(it, event.timeMs) }
                }

                // a reboot ends the sessions too: no pause is reported for the app in front
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    open.values.forEach { close(it, event.timeMs) }
                    open.clear()
                    lastPkg = null
                }

                // KEYGUARD_HIDDEN marks an actual unlock; SCREEN_INTERACTIVE would also count
                // every notification glance / ambient-display wake-up.
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    unlockCount++
                    cal.timeInMillis = event.timeMs
                    unlockHourBuckets[cal.get(Calendar.HOUR_OF_DAY)]++
                }
            }
        }
        open.values.maxByOrNull { it.startMs }?.let { close(it, endMs) }

        val sessions = intervals.flatMap { (pkg, ranges) ->
            mergeRanges(ranges).map { SessionInfo(pkg, it.first, it.last, it.last - it.first) }
        }
        val totalScreenTimeMs = mergeRanges(intervals.values.flatten()).sumOf { it.last - it.first }
        return EventCounts(
            launchCounts, hourBuckets, unlockCount, unlockHourBuckets, sessions, totalScreenTimeMs
        )
    }

    /** [ranges] with every overlapping or touching pair joined into one. */
    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        val merged = mutableListOf<LongRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /**
     * Collapses raw foreground [sessions] into user-perceived session counts per package: phases
     * separated by no more than [gapThresholdMs] count as one session.
     */
    fun groupSessionCounts(
        sessions: List<SessionInfo>, gapThresholdMs: Long = SESSION_GAP_THRESHOLD_MS
    ): Map<String, Int> {
        return sessions.groupBy { it.packageName }.mapValues { (_, pkgSessions) ->
            val sorted = pkgSessions.sortedBy { it.startTimeMs }
            var count = 1
            for (i in 1 until sorted.size) {
                if (sorted[i].startTimeMs - sorted[i - 1].endTimeMs > gapThresholdMs) {
                    count++
                }
            }
            count
        }
    }

    /**
     * Queries the merged per-app usage for each of the last [days] calendar days (today first).
     * Limited by the OS retention window for daily buckets.
     */
    fun queryDailyUsage(days: Int): List<Pair<LocalDate, Map<String, AppScreenTime>>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        return (0 until days).map { dayOffset ->
            val date = today.minusDays(dayOffset.toLong())
            val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            date to queryForPeriod(startMs, endMs)
        }
    }
}
