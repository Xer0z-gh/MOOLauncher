package app.olauncher.helper

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Prefs

/**
 * Match Home text to the system wallpaper by day; white (on a dark surface) in night mode. One
 * cached wallpaper-colour query, dropped when the wallpaper's colours change.
 */
object HomeForeground {
    private var cached: Int? = null
    private var checkedAt = 0L
    private var listening = false

    fun invalidate() { cached = null; checkedAt = 0L }

    fun color(context: Context, prefs: Prefs): Int {
        // The Ultra battery saver paints Home pure black so AMOLED pixels switch off, so its text is
        // white whatever the theme or wallpaper says - and no wallpaper colour is worth a binder call.
        if (LauncherMotion.savingPower(context, prefs)) return Color.WHITE
        if (ColorTheme.isCustom(prefs.colorThemeId)) return ColorTheme.byId(prefs.colorThemeId).text
        // Dark mode means a dark Home: a light wallpaper used to turn it white, flashing to the
        // black menus and Settings on every long press.
        if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) return Color.WHITE
        return wallpaperText(context) ?: context.getColorFromAttr(R.attr.primaryColor)
    }

    /**
     * Black or white, whichever reads over the wallpaper itself, whatever the theme; null when
     * the wallpaper's colours cannot be read. The Apps scrim asks this: [color] is white in dark
     * mode, so it could never tell a white wallpaper under a dark theme from a black one.
     */
    fun wallpaperText(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        listenForWallpaperColors(context)
        val now = SystemClock.uptimeMillis()
        // The listener drops the cache when the colours change, so while it is registered the
        // cached answer stands; the 30 s limit is only for when registering failed. (Opening the
        // dark Apps list asks this, and it must not cost a binder call each time.)
        if (checkedAt != 0L && (listening || now - checkedAt < 30_000L)) return cached
        val wallpaper = runCatching { WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
        cached = wallpaper?.let { if (ColorUtils.calculateLuminance(it) > 0.4) Color.BLACK else Color.WHITE }
        checkedAt = now
        return cached
    }

    /**
     * Home used to drop the cache on every resume, so each Home press made a main-thread
     * getWallpaperColors binder call. The wallpaper tells us when it changes instead; the 30 s
     * age limit stays as a backstop.
     */
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun listenForWallpaperColors(context: Context) {
        if (listening) return
        listening = runCatching {
            WallpaperManager.getInstance(context.applicationContext).addOnColorsChangedListener(
                { _, _ -> invalidate() }, Handler(Looper.getMainLooper()))
            true
        }.getOrDefault(false)
    }
}
