package app.olauncher.helper

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.olauncher.data.Prefs

/**
 * One notification, flattened to what a list row needs and nothing more.
 *
 * Deliberately holds no Notification, no Bundle, no Icon and no RemoteViews - those are the
 * expensive parts, and a panel that retained them would pin every notification's bitmaps in
 * the launcher's heap on a 4 GB phone. [contentIntent] is a token, not a graph, so keeping it
 * costs nothing and is the only way tapping a row can open what sent it.
 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val user: UserHandle,
    val userString: String,
    val title: String?,
    val text: String?,
    val postTime: Long,
    val contentIntent: PendingIntent?,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val smallIcon: NotificationSmallIcon?,
    val mediaToken: android.media.session.MediaSession.Token? = null,
) {
    /** "package|user", the same identity Prefs and NotificationCounts use. */
    val appKey: String get() = NotificationCounts.key(packageName, userString)
}

/** Resource identity only: retaining an Icon could pin a notification's full bitmap in memory. */
data class NotificationSmallIcon(val packageName: String, val resourceId: Int)

/**
 * Feeds [NotificationCounts] so the home screen can badge apps with what the user missed.
 *
 * Deliberately cheap, because this runs in the launcher's own process on a low-RAM phone:
 *  - getActiveNotifications() is called in exactly one place, onListenerConnected, and never per
 *    callback. It is a binder call that parcels every active notification across, so calling it
 *    per posted notification would be the single most expensive thing this app does.
 *  - Nothing derived from a StatusBarNotification is retained. We extract a short String and let
 *    the notification, its extras Bundle, its icons and its RemoteViews go.
 */
class NotificationService : NotificationListenerService() {

    companion object {
        /** A peek line is a preview, not the notification; long ones are truncated. */

        @Volatile
        private var instance: NotificationService? = null

        /**
         * Counts the notifications already in the shade, for when badges are switched on
         * while the listener is ALREADY bound. requestRebind does nothing in that case, so
         * onListenerConnected never re-runs and nothing would badge until the next
         * notification arrived. Returns false when the service is not connected, which is
         * when requestRebind is the right call instead.
         */
        fun rescanActive(): Boolean {
            val service = instance ?: return false
            service.backfill()
            return true
        }

        /**
         * Everything currently in the shade, newest first.
         *
         * Does binder work (activeNotifications) so it must not be called on the main thread.
         * Returns null when the listener is not connected, which the caller shows as "access
         * is off" rather than as an empty shade - those are different states and conflating
         * them is how a permission problem reads as "no notifications".
         */
        fun snapshot(): List<NotificationItem>? {
            val service = instance ?: return null
            val active = runCatching { service.activeNotifications }.getOrNull() ?: return null
            return active
                .filter { service.panelWorthy(it) }
                .map { service.itemFor(it) }
                .sortedByDescending { it.postTime }
        }

        /** Dismisses one notification. Returns false when the listener is not connected. */
        fun dismiss(key: String): Boolean {
            val service = instance ?: return false
            return runCatching { service.cancelNotification(key) }.isSuccess
        }

        /** Dismisses several at once - one binder call rather than one per row. */
        fun dismissAll(keys: List<String>): Boolean {
            val service = instance ?: return false
            if (keys.isEmpty()) return true
            return runCatching { service.cancelNotifications(keys.toTypedArray()) }.isSuccess
        }

        /**
         * Called on the main thread when the shade has changed. Set by the panel while it is
         * visible and cleared when it leaves, so nothing is observed when nothing is watching.
         */
        @Volatile
        var onShadeChanged: (() -> Unit)? = null

        /** Notifications arrive in bursts; one refresh per burst, not per notification. */
        private const val COALESCE_MS = 150L

        private val handler = Handler(Looper.getMainLooper())
        private val notifyPanel = Runnable { onShadeChanged?.invoke() }

        /**
         * Asks the panel to re-read, at most once per [COALESCE_MS].
         *
         * Without this a burst - a sync finishing, a group chat, a boot - started one full
         * snapshot per notification, and a snapshot is a binder call that parcels every active
         * notification across plus a label lookup per new app. This is the same coalescing
         * NotificationCounts already does for the badges.
         */
        private fun scheduleShadeNotice() {
            if (onShadeChanged == null) return
            handler.removeCallbacks(notifyPanel)
            handler.postDelayed(notifyPanel, COALESCE_MS)
        }
    }

    private val prefs by lazy { Prefs(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationCounts.connected = true
        backfill()
    }

    /**
     * Rebuilds from scratch: we may have missed events entirely while unbound, so whatever is
     * in the store is untrustworthy. Persisting counts across this would only preserve lies.
     */
    private fun backfill() {
        NotificationCounts.clear()
        if (!prefs.showNotificationBadges) return

        // Off the main thread: onListenerConnected runs on it, in the same window as Home's
        // first frame after a process restart, and this parcels every notification in the shade.
        // Only (key, app, line) Strings come back; the counts store stays main-thread only.
        val generation = ++backfillGeneration
        val epoch = NotificationCounts.clearEpoch
        kotlin.concurrent.thread(name = "moo-badge-backfill") {
            val active = runCatching { activeNotifications }.getOrNull() ?: return@thread
            val rows = active.mapNotNull { sbn -> countable(sbn)?.let { Triple(sbn.key, it, lineFor(sbn)) } }
            handler.post {
                // A disconnect, a newer backfill, or badges switched off while this ran: drop it,
                // or it would resurrect counts that were just cleared.
                if (generation != backfillGeneration || !NotificationCounts.connected ||
                    !prefs.showNotificationBadges) return@post
                // Likewise an app opened (which clears its badge) or muted in the meantime.
                val muted = prefs.badgeMutedApps
                rows.forEach { (key, appKey, line) ->
                    if (appKey !in muted && !NotificationCounts.clearedSince(appKey, epoch))
                        NotificationCounts.onPosted(key, appKey, line)
                }
            }
        }
    }

    /** Main thread only. */
    private var backfillGeneration = 0

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        NotificationCounts.connected = false
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Only if the panel would actually show it. A group summary is filtered out of the
        // panel, so refreshing for one is a binder call that changes nothing on screen.
        // And only while a panel is watching: panelWorthy reads title and text, which unparcels
        // the extras of every notification on the phone - each download-progress tick included -
        // on the main thread, for a notice scheduleShadeNotice would then drop.
        if (sbn != null && onShadeChanged != null && panelWorthy(sbn)) scheduleShadeNotice()
        if (sbn == null || !prefs.showNotificationBadges) return
        count(sbn)
    }

    /**
     * Only ever tells the panel to re-read. It deliberately does NOT touch the badge counters:
     * clearing a notification from the shade does not clear the badge, because the point of the
     * badge is what was missed and the reset is opening the app. Adding a decrement here would
     * change what the badge means.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // No panelWorthy check here: something the panel was showing may have just gone, and
        // a removal carries no reliable way to know whether it was on screen.
        scheduleShadeNotice()
    }

    private fun count(sbn: StatusBarNotification) {
        val appKey = countable(sbn) ?: return
        NotificationCounts.onPosted(sbn.key, appKey, lineFor(sbn))
    }

    /** The badge key this notification counts toward, or null when it does not count. */
    private fun countable(sbn: StatusBarNotification): String? {
        if (!badgeWorthy(sbn)) return null
        val user = sbn.user.toString()
        // Per-app filter. Read fresh rather than cached: the set changes from the settings screen
        // while this service stays bound, and a stale copy would quietly ignore the user's choice.
        if ("${sbn.packageName}|$user" in prefs.badgeMutedApps) return null
        return NotificationCounts.key(sbn.packageName, user)
    }

    /**
     * Whether a notification represents something the user would consider "missed".
     *
     * The exclusions matter more than they look. Group summaries would double-count every grouped
     * conversation, and ongoing/foreground-service notifications (music players, navigation, USB,
     * downloads) are persistent status, not events, so they would pin a badge on permanently.
     */
    private fun badgeWorthy(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val notification = sbn.notification ?: return false

        val flags = notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (!sbn.isClearable) return false

        return when (notification.category) {
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SYSTEM -> false

            else -> true
        }
    }

    /**
     * One short line for the tap-the-badge peek. Reading extras can throw on some OEM builds when
     * a notification carries a custom Parcelable the launcher cannot unmarshal, so it is guarded.
     */
    private fun lineFor(sbn: StatusBarNotification): String? = runCatching {
        val extras = sbn.notification?.extras ?: return@runCatching null
        NotificationText.line(extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT))
    }.getOrNull()

    /**
     * Whether a notification belongs in the panel.
     *
     * Looser than [badgeWorthy] on purpose. A badge is "you missed something", so ongoing
     * media and navigation are noise. A panel is "what is in my shade", and a shade with the
     * music player missing from it is wrong - the user can see it in the system shade and
     * would read its absence here as a bug. Group summaries stay out either way: they are a
     * duplicate of the children, not an extra notification.
     */
    private fun panelWorthy(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val notification = sbn.notification ?: return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Something with neither a title nor a body is a row with nothing to read.
        return !titleOf(sbn).isNullOrEmpty() || !textOf(sbn).isNullOrEmpty()
    }

    private fun itemFor(sbn: StatusBarNotification): NotificationItem {
        val notification = sbn.notification
        return NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            user = sbn.user,
            userString = sbn.user.toString(),
            title = titleOf(sbn),
            text = textOf(sbn),
            postTime = sbn.postTime,
            contentIntent = notification?.contentIntent,
            clearable = sbn.isClearable,
            autoCancel = (notification?.flags ?: 0) and Notification.FLAG_AUTO_CANCEL != 0,
            smallIcon = smallIconFor(sbn),
            mediaToken = mediaTokenFor(notification),
        )
    }

    @Suppress("DEPRECATION")
    private fun mediaTokenFor(notification: Notification?): android.media.session.MediaSession.Token? = runCatching {
        notification?.extras?.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION) as? android.media.session.MediaSession.Token
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun smallIconFor(sbn: StatusBarNotification): NotificationSmallIcon? = runCatching {
        val icon = sbn.notification.smallIcon
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // URI and bitmap icons are intentionally not retained or fetched by the launcher.
            if (icon?.type != Icon.TYPE_RESOURCE) return@runCatching null
            NotificationSmallIcon(icon.resPackage.ifEmpty { sbn.packageName }, icon.resId)
        } else {
            // Icon's type/resource accessors became public in API 28. The legacy field is
            // still populated by notifications constructed with a resource ID on API 24-27.
            sbn.notification.icon.takeIf { it != 0 }?.let { NotificationSmallIcon(sbn.packageName, it) }
        }
    }.getOrNull()

    /**
     * Reading extras can throw on OEM builds that put a custom Parcelable in there, so both
     * of these are guarded - the same reason [lineFor] is.
     */
    private fun titleOf(sbn: StatusBarNotification): String? = runCatching {
        NotificationText.field(sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE),
            NotificationText.TITLE_CHARS)
    }.getOrNull()

    private fun textOf(sbn: StatusBarNotification): String? = runCatching {
        val extras = sbn.notification?.extras ?: return@runCatching null
        // Messaging-style notifications can put the body only in BIG_TEXT.
        NotificationText.field(extras.getCharSequence(Notification.EXTRA_TEXT), NotificationText.BODY_CHARS)
            ?: NotificationText.field(extras.getCharSequence(Notification.EXTRA_BIG_TEXT), NotificationText.BODY_CHARS)
    }.getOrNull()
}
