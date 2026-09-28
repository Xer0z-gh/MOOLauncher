package app.olauncher.helper.usageStats

import app.olauncher.BuildConfig
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log

/**
 * “…a diminutive Guardian who traveled backward through time…”
 *
 * Guards [EventLogWrapper] against Faulty unmatched close events (per
 * [the documentation](https://codeberg.org/fynngodau/usageDirect/wiki/Event-log-wrapper-scenarios))
 * by seeking backwards through time and scanning for the open event.
 */
class UnmatchedCloseEventGuardian(private val usageStatsManager: UsageStatsManager) {

    companion object {
        private const val SCAN_INTERVAL = 1000L * 60 * 60 * 24 // 24 hours

        /**
         * Packages still open at a query start, keyed by that start. It used to be a fresh
         * 24-hour query per unmatched close event, on every screen-time refresh - but the answer
         * for a given start (midnight, or today's boot) is fixed history, so it is read once a
         * day and kept for the process. More than one key only on a day the phone rebooted
         * (midnight, then the boot); anything older than today is dropped.
         */
        private val openAtStart = HashMap<Long, Set<String>>()
    }

    /**
     * @param event      Event to validate
     * @param queryStart Timestamp at which original query o
     * @return True if the event is valid, false otherwise
     */
    fun test(event: UsageEvents.Event, queryStart: Long): Boolean {
        val open = synchronized(openAtStart) {
            openAtStart.getOrPut(queryStart) {
                openAtStart.keys.removeAll { it < queryStart - SCAN_INTERVAL }
                packagesOpenAt(queryStart)
            }
        }
        val result = event.packageName in open

        // Debug-gated: this is the innermost loop of the screen time scan, and building the
        // message allocates a String per close event even when nothing reads the log.
        if (BuildConfig.DEBUG) Log.d("Guardian", "Close event classified as " + if (result) "True" else "Faulty")

        // Event is valid if it was previously opened (within SCAN_INTERVAL)
        return result
    }

    /**
     * One pass over the 24 hours before [queryStart], with the same rules the per-package scan
     * used: RESUMED (or CONTINUE_PREVIOUS_DAY, 4) opens a package, PAUSED (or END_OF_DAY, 3)
     * closes it, and DEVICE_STARTUP closes everything. The window ends before queryStart, so it
     * never holds the close event being tested - the old same-timestamp exception cannot apply.
     */
    private fun packagesOpenAt(queryStart: Long): Set<String> {
        val events = usageStatsManager.queryEvents(queryStart - SCAN_INTERVAL, queryStart)
        val e = UsageEvents.Event()
        val open = HashSet<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.DEVICE_STARTUP -> open.clear()
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> open.add(e.packageName)
                UsageEvents.Event.ACTIVITY_PAUSED, 3 -> open.remove(e.packageName)
            }
        }
        return open
    }
}
