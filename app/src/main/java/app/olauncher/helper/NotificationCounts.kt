package app.olauncher.helper

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Missed-notification counters for the home screen badges, keyed by package + user profile.
 *
 * The semantics here are deliberately NOT "how many notifications are currently in the shade".
 * A counter goes up once per genuinely new notification and only comes back down when the user
 * opens that app from the launcher or mutes its badges. Dismissing a notification from the shade does not decrement
 * it: it was still missed. This is what makes the badge a "what did I miss" signal rather than a
 * mirror of the status bar.
 *
 * Everything here runs on the main thread. NotificationListenerService delivers its callbacks via
 * its own handler on the main Looper, which is the same thread HomeFragment observes on, so no
 * locking is needed and adding any would be pure cost.
 */
object NotificationCounts {

    /** Upper bound on remembered notification keys, so a long uptime cannot grow the set forever. */
    private const val MAX_SEEN_KEYS = 256

    /** How many recent notification lines we keep per app for the tap-the-badge peek. */
    private const val MAX_LINES_PER_APP = 5

    /** Notifications arrive in bursts (a sync, a group chat). Publish at most once per burst. */
    private const val COALESCE_MS = 150L

    private val missed = HashMap<String, Int>()
    private val lines = HashMap<String, ArrayDeque<String>>()

    /**
     * StatusBarNotification keys already counted. Insertion-ordered so the oldest evicts first.
     * This is what stops a notification being counted again every time the posting app updates it
     * (progress bars, "now playing", a chat notification re-posted per message).
     */
    private val seen = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        // accessOrder = true: a key touched again moves to the back, so eviction drops the
        // least RECENTLY used rather than the oldest. With insertion order a chatty app
        // could push out a key that was still live, and that notification counted twice.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
            size > MAX_SEEN_KEYS
    }

    private val mutableCounts = MutableLiveData<Map<String, Int>>(emptyMap())
    val counts: LiveData<Map<String, Int>> = mutableCounts

    /** True while the system has us bound. Read by HomeFragment to decide whether to request a rebind. */
    @Volatile
    var connected: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val publish = Runnable { mutableCounts.value = HashMap(missed) }

    /**
     * Identity for a home app. Package name alone is ambiguous: a work profile and the personal
     * profile can both hold the same package, and they are different rows on the home screen.
     * The user string is whatever UserHandle.toString() produces, which is also what Prefs stores.
     */
    fun key(packageName: String, user: String): String = "$packageName|$user"

    /** Counts one notification. A key already seen is ignored, so a re-post does not count twice. */
    fun onPosted(notificationKey: String, appKey: String, line: String?) {
        if (seen.put(notificationKey, appKey) != null) return
        missed[appKey] = (missed[appKey] ?: 0) + 1
        if (!line.isNullOrBlank()) {
            val recent = lines.getOrPut(appKey) { ArrayDeque() }
            recent.addLast(line)
            while (recent.size > MAX_LINES_PER_APP) recent.removeFirst()
        }
        schedulePublish()
    }

    /** Clears an app after it is opened or its badges are switched off. */
    /**
     * A counter bumped by every clearApp, with the value each app was last cleared at. The badge
     * backfill reads the shade off the main thread and applies it later; an app opened or muted
     * in between must not be re-badged from that older snapshot. Main thread only; at most one
     * entry per app ever cleared.
     */
    var clearEpoch = 0L
        private set
    private val clearedAt = HashMap<String, Long>()

    fun clearedSince(appKey: String, epoch: Long): Boolean = (clearedAt[appKey] ?: 0L) > epoch

    fun clearApp(appKey: String) {
        clearedAt[appKey] = ++clearEpoch
        val had = missed.remove(appKey) != null
        lines.remove(appKey)
        // Forget this app's notification keys too. Without this, an app that reuses the same
        // notification id - most chat and mail apps do - posted a key we had already seen,
        // onPosted returned early, and it never badged again after the first clear.
        seen.entries.removeAll { it.value == appKey }
        if (had) schedulePublish()
    }

    /** Wipes everything: badges turned off, access revoked, or the listener reconnected. */
    fun clear() {
        if (missed.isEmpty() && seen.isEmpty() && lines.isEmpty()) return
        missed.clear()
        lines.clear()
        seen.clear()
        schedulePublish()
    }

    fun countFor(appKey: String): Int = missed[appKey] ?: 0

    fun linesFor(appKey: String): List<String> = lines[appKey]?.toList().orEmpty()

    private fun schedulePublish() {
        handler.removeCallbacks(publish)
        handler.postDelayed(publish, COALESCE_MS)
    }
}
