package app.olauncher.pro

import android.content.Context
import app.olauncher.data.Prefs

/** Builds outside Google Play (GitHub, F-Droid) include every feature; there is nothing to buy. */
object ProStore {
    const val SELLS_PRO = false

    @Suppress("UNUSED_PARAMETER")
    fun unlocked(prefs: Prefs) = true

    @Suppress("UNUSED_PARAMETER")
    fun refresh(context: Context) = false
}
