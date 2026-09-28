package app.olauncher.helper

import android.app.Activity
import android.os.Build
import android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER

/**
 * The window-level half of the Ultra battery saver: the display refresh rate and the wallpaper
 * layer. Everything else the saver does lives with the view it affects; these two belong to the
 * window, so the activity applies them - on start, on every navigation, and whenever Android
 * Power Saver or the manual switch changes.
 *
 * Refresh rate. The A17's panel scans at 90 Hz, and a panel refreshing costs power even when the
 * launcher draws nothing - a settled Home already renders zero frames, so the scan rate is what is
 * left. While saving, the launcher asks for the lowest touch-capable rate at the current
 * resolution, and turns off the per-touch boost back to the top rate. The requests only hold while
 * this window is in front, so they never follow the user into another app.
 *
 * Wallpaper. The theme keeps FLAG_SHOW_WALLPAPER on, so SurfaceFlinger composites the wallpaper
 * layer under Home even when Home is painted opaque and the wallpaper cannot be seen, and a live
 * wallpaper keeps rendering. While saving, Home is painted pure black - AMOLED pixels off - so the
 * layer is dropped. Only while Home itself is showing: the drawer and Settings sit on translucent
 * shades that would turn to near-black under a light theme without the wallpaper behind them. The
 * saver also turns transitions off, so the swap at a navigation is instant rather than a flash.
 */
object SaverWindow {
    /** Below this a mode is for video or always-on display; Home at 30 Hz would feel broken to touch. */
    private const val MIN_TOUCH_RATE = 59f

    /** What the saver asks for when no lower touch-capable rate is visible. See lowestTouchRate. */
    private const val TOUCH_FLOOR_RATE = 60f

    fun apply(activity: Activity, saving: Boolean, homeShowing: Boolean) {
        val window = activity.window ?: return
        val attributes = window.attributes
        var changed = false
        val lowRate = if (saving) lowestTouchRate(activity) else 0f
        // Both requests, because devices expose a lower rate in one of two ways. Older ones list it
        // as a separate display mode, which only preferredDisplayModeId can select. Android 14+
        // devices - the A17 among them - have ONE mode at 90 Hz with 60 Hz as an alternative rate
        // inside it: there is no second mode to pick, and preferredRefreshRate is what reaches it.
        if (attributes.preferredRefreshRate != lowRate) {
            attributes.preferredRefreshRate = lowRate
            changed = true
        }
        val wanted = if (saving) modeIdAtRate(activity, lowRate) else 0
        if (attributes.preferredDisplayModeId != wanted) {
            attributes.preferredDisplayModeId = wanted
            changed = true
        }
        // Android 15+ boosts the panel to its top rate for every touch, which on a launcher is most
        // of the time it is being looked at - and would quietly undo the lower rate asked for above.
        // Off while saving; the system default otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            attributes.getFrameRateBoostOnTouchEnabled() == saving) {
            attributes.setFrameRateBoostOnTouchEnabled(!saving)
            changed = true
        }
        if (changed) window.attributes = attributes
        val hideWallpaper = saving && homeShowing
        val wallpaperShown = (window.attributes.flags and FLAG_SHOW_WALLPAPER) != 0
        if (hideWallpaper && wallpaperShown) window.clearFlags(FLAG_SHOW_WALLPAPER)
        else if (!hideWallpaper && !wallpaperShown) window.addFlags(FLAG_SHOW_WALLPAPER)
    }

    /**
     * The slowest touch-capable rate at the resolution already in use, counting each mode's own
     * rate and the alternative rates it can run at. Never compared against the current rate: once
     * this request is granted the current rate IS the slow one, and a comparison would withdraw
     * the request and set the display oscillating.
     */
    private fun lowestTouchRate(activity: Activity): Float {
        val display = display(activity) ?: return TOUCH_FLOOR_RATE
        val discovered = (sameResolutionModes(display) + display.mode).flatMap { mode ->
            val alternatives = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                mode.alternativeRefreshRates.toList() else emptyList()
            alternatives + mode.refreshRate
        } + @Suppress("DEPRECATION") display.supportedRefreshRates.toList()
        // 60 Hz is always a candidate, because discovery cannot be trusted. Logged on the A17
        // (Android 16): the app is given one mode, 90 Hz, with its alternative rates emptied, while
        // the panel really has a second mode at 60 Hz and the current mode's own description lists
        // it. Built only from what the app was shown, the saver asked for 90 - exactly what it had.
        // A rate request resolves to the nearest rate the panel really supports, so asking for 60
        // on a panel without it changes nothing, and on this one it reaches the hidden mode.
        return (discovered + TOUCH_FLOOR_RATE).filter { it >= MIN_TOUCH_RATE }.minOrNull()
            ?: TOUCH_FLOOR_RATE
    }

    /** A mode whose own rate is [rate], or 0 when the rate only exists as an alternative. */
    private fun modeIdAtRate(activity: Activity, rate: Float): Int {
        if (rate <= 0f) return 0
        val display = display(activity) ?: return 0
        return sameResolutionModes(display)
            .firstOrNull { kotlin.math.abs(it.refreshRate - rate) < 0.5f }
            ?.modeId ?: 0
    }

    private fun display(activity: Activity): android.view.Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay

    private fun sameResolutionModes(display: android.view.Display): List<android.view.Display.Mode> {
        val current = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
    }
}
