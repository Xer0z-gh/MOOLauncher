package app.olauncher.helper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import kotlinx.coroutines.coroutineScope

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = coroutineScope {
        // Keep the daily marker unchanged so the next periodic run can catch up after saver ends.
        if (LauncherMotion.savingPower(applicationContext, prefs)) return@coroutineScope Result.success()
        val success =
            if (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && isOlauncherDefault(applicationContext).not())
                true
            else if (prefs.dailyWallpaper) {
                val wallType = checkWallpaperType()
                // The wallpaper changes once a day and this runs every 4 hours. Each run used to
                // download the index (a radio wake-up) only to find today's URL already applied.
                val dayKey = todaysWallpaperKey(prefs.firstOpenTime) + "|" + wallType
                if (prefs.dailyWallpaperKey == dayKey) return@coroutineScope Result.success()
                val wallpaperUrl = getTodaysWallpaper(wallType, prefs.firstOpenTime)
                // Only a URL that came from the index settles the day. A fetch that failed falls
                // back to the default wallpaper, and the next run should still try the real one.
                val fromIndex = wallpaperUrl != getBackupWallpaper(wallType)
                val applied = prefs.dailyWallpaperUrl == wallpaperUrl ||
                    setWallpaper(applicationContext, wallpaperUrl).also { if (it) prefs.dailyWallpaperUrl = wallpaperUrl }
                if (applied && fromIndex) prefs.dailyWallpaperKey = dayKey
                applied
            } else
                true

        if (success)
            Result.success()
        else
            Result.retry()
    }

    private fun checkWallpaperType(): String {
        return when (prefs.appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> Constants.WALL_TYPE_DARK
            AppCompatDelegate.MODE_NIGHT_NO -> Constants.WALL_TYPE_LIGHT
            else -> if (applicationContext.isDarkThemeOn())
                Constants.WALL_TYPE_DARK
            else
                Constants.WALL_TYPE_LIGHT
        }
    }
}
