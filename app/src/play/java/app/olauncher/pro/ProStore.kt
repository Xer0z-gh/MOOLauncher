package app.olauncher.pro

import android.content.Context
import android.content.pm.PackageManager
import app.olauncher.data.Constants
import app.olauncher.data.Prefs

/**
 * Moo Pro on Google Play is a separate paid key app ([Constants.MOO_PRO_PACKAGE]). Installed and signed
 * with the same key as this app means unlocked. Checked on every resume, so buying it (or a refund,
 * which uninstalls it) takes effect as soon as the user comes back to the launcher.
 */
object ProStore {
    const val SELLS_PRO = true

    fun unlocked(prefs: Prefs) = prefs.proUnlocked

    /** True when the unlock changed, so the caller redraws. */
    fun refresh(context: Context): Boolean {
        val pm = context.packageManager
        val owned = runCatching {
            pm.getPackageInfo(Constants.MOO_PRO_PACKAGE, 0)
            pm.checkSignatures(context.packageName, Constants.MOO_PRO_PACKAGE) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)
        val prefs = Prefs(context)
        if (prefs.proUnlocked == owned) return false
        prefs.proUnlocked = owned
        return true
    }
}
