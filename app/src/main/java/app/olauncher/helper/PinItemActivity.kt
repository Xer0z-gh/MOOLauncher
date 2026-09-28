package app.olauncher.helper

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.R
import app.olauncher.data.Prefs

class PinItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(Prefs(this).appTheme)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.pin_action_not_supported)
            finish()
            return
        }

        val request = runCatching {
            getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        }.getOrNull()
        if (request == null || !request.isValid) {
            showToast(R.string.invalid_pin_request)
            finish()
            return
        }
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> confirmShortcut(request)
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                showToast(R.string.widgets_not_supported)
                finish()
            }
            else -> {
                showToast(R.string.pin_action_not_supported)
                finish()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun confirmShortcut(request: LauncherApps.PinItemRequest) {
        val shortcut = request.shortcutInfo
        if (shortcut == null) {
            showToast(R.string.invalid_shortcut)
            finish()
            return
        }
        val publisher = shortcut.`package`
        val appName = safeLabel(runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(publisher, 0)).toString()
        }.getOrDefault(publisher)).ifBlank { publisher }
        val shortcutName = safeLabel(shortcut.shortLabel.toString()).ifBlank { safeLabel(shortcut.id) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_shortcut_title)
            .setMessage(getString(R.string.pin_shortcut_message, shortcutName, appName, publisher))
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.pin_shortcut_add) { _, _ ->
                val success = request.isValid && runCatching { request.accept() }.getOrDefault(false)
                showToast(if (success) R.string.shortcut_pinned else R.string.shortcut_pin_failed)
                finish()
            }
            .setOnCancelListener { finish() }
            .create().also { dialog ->
                dialog.showForLauncher()
                // The window is full screen; expand its content panel too.
                dialog.findViewById<android.view.View>(androidx.appcompat.R.id.parentPanel)?.apply {
                    layoutParams = layoutParams.apply { height = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
                }
            }
    }

    private fun safeLabel(value: String): String = buildString {
        for (ch in value) {
            if (Character.isISOControl(ch) || ch in '\u202A'..'\u202E' ||
                ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' || ch == '\u061C') continue
            val normalized = if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) ' ' else ch
            if (normalized != ' ' || (isNotEmpty() && last() != ' ')) append(normalized)
            if (length == 80) break
        }
    }.trim()
}
