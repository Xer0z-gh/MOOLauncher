package app.olauncher.helper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.olauncher.data.Prefs

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

    private val prefs by lazy { Prefs(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationCounts.connected = true

        // Rebuild from scratch: we may have missed events entirely while unbound, so whatever is
        // in the store is untrustworthy. Persisting counts across this would only preserve lies.
        NotificationCounts.clear()
        if (!prefs.showNotificationBadges) return

        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) count(sbn)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationCounts.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !prefs.showNotificationBadges) return
        count(sbn)
    }

    // Intentionally no onNotificationRemoved override. Clearing a notification from the shade does
    // not clear the badge: the point of the badge is what was missed, and the reset is opening the
    // app. Removing this comment and adding a decrement would change the feature's meaning.

    private fun count(sbn: StatusBarNotification) {
        if (!badgeWorthy(sbn)) return
        val user = sbn.user.toString()
        // Per-app filter. Read fresh rather than cached: the set changes from the settings screen
        // while this service stays bound, and a stale copy would quietly ignore the user's choice.
        if ("${sbn.packageName}|$user" in prefs.badgeMutedApps) return
        val appKey = NotificationCounts.key(sbn.packageName, user)
        NotificationCounts.onPosted(sbn.key, appKey, lineFor(sbn))
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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        when {
            !title.isNullOrEmpty() && !text.isNullOrEmpty() -> "$title: ${text.take(MAX_LINE_CHARS)}"
            !title.isNullOrEmpty() -> title.take(MAX_LINE_CHARS)
            !text.isNullOrEmpty() -> text.take(MAX_LINE_CHARS)
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val MAX_LINE_CHARS = 100
    }
}
