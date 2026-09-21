package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * The connected service, or null. Same process as the launcher, so this is a direct
         * call rather than a click on an invisible view that the service had to recognise.
         */
        @Volatile
        private var instance: MyAccessibilityService? = null

        /** Locks the screen. Returns false when the service is not connected. */
        fun lockScreen(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        instance = this
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Nothing to listen for. The launcher calls lockScreen() directly.
    }

    override fun onInterrupt() {

    }
}