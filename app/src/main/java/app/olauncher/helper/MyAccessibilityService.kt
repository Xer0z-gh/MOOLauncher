package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            // Matched on contentDescription until now, which meant a 1dp internal view had to
            // carry "lock layout description to be used a unique id to lock screen" as its
            // accessible name - developer text sitting in the layer a screen reader reads out.
            // The view id is the identity that was always meant here. endsWith, because the
            // debug build is app.olauncher.debug and the release build is app.olauncher.
            if (source.viewIdResourceName?.endsWith(":id/lock") == true &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }
}