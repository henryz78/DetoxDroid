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

    var usageStatsToday: Map<String, AppScreenTime> = mapOf()
        get() {
            val now = System.currentTimeMillis()
            if (now - usageStatsTodayLastRefresh > TenSecondsInMs) {
                val dayBeginningMs =
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                field = queryForPeriod(dayBeginningMs, now)
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
        queryEventCounts(startMs, endMs).sessions.groupBy { it.packageName }
            .mapValues { (pkg, sessions) -> AppScreenTime(pkg, sessions.sumOf { it.totalTimeMs }) }

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
        val sessions: List<SessionInfo>
    )

    /** One entry of the OS usage-event log, reduced to what [foldEvents] reads. */
    data class RawEvent(val type: Int, val packageName: String, val timeMs: Long)

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
                yield(RawEvent(event.eventType, event.packageName ?: "", event.timeStamp))
            }
        }
        return foldEvents(raw, endMs, nonAppPackages())
    }

    /**
     * Turns the usage-event log into [EventCounts]. Only one app is in front at a time: an app
     * coming to the foreground ends the previous one's session, and the screen going off ends it
     * too, so the sessions never add up to more than the time the screen was on. [nonApps] (the
     * launcher, the system UI) still end the app under them but get no session and no launch.
     * An app is launched when it comes to the front after something else was, or after the screen
     * was off; hopping between its own screens is not a launch.
     */
    fun foldEvents(events: Sequence<RawEvent>, endMs: Long, nonApps: Set<String>): EventCounts {
        val launchCounts = mutableMapOf<String, Int>()
        val hourBuckets = IntArray(24)
        val unlockHourBuckets = IntArray(24)
        var unlockCount = 0
        val sessions = mutableListOf<SessionInfo>()
        val cal = Calendar.getInstance()

        var currentPkg: String? = null
        var currentStartMs: Long = 0
        var lastPkg: String? = null

        fun endCurrentSession(endTimeMs: Long) {
            val pkg = currentPkg ?: return
            if (currentStartMs > 0 && pkg !in nonApps) {
                val duration = endTimeMs - currentStartMs
                if (duration > 0) {
                    sessions.add(SessionInfo(pkg, currentStartMs, endTimeMs, duration))
                }
            }
            currentPkg = null
            currentStartMs = 0
        }

        for (event in events) {
            when (event.type) {
                // same wire values as the deprecated MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND,
                // so pre-API-29 events are matched as well
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    endCurrentSession(event.timeMs)
                    if (event.packageName !in nonApps && event.packageName != lastPkg) {
                        launchCounts.merge(event.packageName, 1, Int::plus)
                        cal.timeInMillis = event.timeMs
                        hourBuckets[cal.get(Calendar.HOUR_OF_DAY)]++
                    }
                    lastPkg = event.packageName
                    currentPkg = event.packageName
                    currentStartMs = event.timeMs
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (currentPkg == event.packageName) {
                        endCurrentSession(event.timeMs)
                    }
                }

                // a reboot ends the session too: no pause is reported for the app in front
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    endCurrentSession(event.timeMs)
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
        endCurrentSession(endMs)

        return EventCounts(launchCounts, hourBuckets, unlockCount, unlockHourBuckets, sessions)
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
