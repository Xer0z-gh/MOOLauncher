package app.olauncher

import android.app.Application
import android.content.ComponentCallbacks2
import app.olauncher.helper.IconCache
import app.olauncher.helper.IconPack

/**
 * Process-wide caches also exist when Android starts only the notification service.
 *
 * Also WorkManager's configuration provider. Its startup initializer is removed in the manifest, so
 * WorkManager builds itself - Room database and all - the first time getInstance() is asked for,
 * which only the optional daily wallpaper ever does, instead of on every process start.
 */
class MooApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    // Android 7-13 still delivers these legacy pressure levels; keep their cache eviction behavior.
    // Nothing on UI_HIDDEN, deliberately. It used to trim the icon cache to a quarter, and because
    // the warm touches icons in first-screen order, the quarter it kept was the tail: the drawer's
    // first screen was evicted every time another app came to the front and re-rasterised on the
    // next Home press, to save about 1 MB of a ~130 MB process. The 2 MiB budget is the bound;
    // BACKGROUND and the pressure levels below still empty it.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                IconCache.clear()
                IconPack.reset()
            }
            // Legacy pressure callbacks are still delivered on Android 7-13.
            level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> IconCache.clear()
        }
    }

    @Deprecated("Legacy memory callback")
    override fun onLowMemory() {
        super.onLowMemory()
        IconCache.clear()
        IconPack.reset()
    }
}
