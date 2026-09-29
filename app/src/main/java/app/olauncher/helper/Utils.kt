package app.olauncher.helper

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import androidx.core.net.toUri
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.createBitmap
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.data.AppCategoryResolver
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.HiddenAppKeys
import app.olauncher.data.Prefs
import app.olauncher.data.shortcutIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

fun Context.showToast(message: String?, duration: Int = Toast.LENGTH_SHORT) {
    if (message.isNullOrBlank()) return
    Toast.makeText(this, message, duration).show()
}

fun Context.showToast(stringResource: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, getString(stringResource), duration).show()
}

// Lint cannot see that the finally around the whole body closes the section on every path.
@android.annotation.SuppressLint("UnclosedTrace")
suspend fun getAppsList(
    context: Context,
    prefs: Prefs,
    includeRegularApps: Boolean = true,
    includeHiddenApps: Boolean = false,
): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()
        // A named slice, so a Perfetto trace can count full scans (the body never suspends, so
        // begin and end stay on one thread). Costs nothing when nobody is tracing.
        android.os.Trace.beginSection("Moo getAppsList")
        try {
            val hiddenApps = HiddenAppKeys.normalized(
                prefs.hiddenApps, android.os.Process.myUserHandle().toString())
            if (hiddenApps != prefs.hiddenApps) prefs.hiddenApps = hiddenApps.toMutableSet()
            if (!prefs.hiddenAppsUpdated) prefs.hiddenAppsUpdated = true

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (profile in userManager.userProfiles) {
                if (isPrivateSpaceProfile(context, profile)) continue
                for (app in launcherApps.getActivityList(null, profile)) {
                    val appLabelShown = prefs.getAppRenameLabel(app.applicationInfo.packageName)
                        .ifBlank { app.label.toString() }
                    val appModel = AppModel.App(
                        appLabel = appLabelShown,
                        // No CollationKey: every sort uses a Collator on appLabel, and nothing
                        // reads the key, so each scan built and retained ~200 ICU keys for nothing.
                        key = null,
                        appPackage = app.applicationInfo.packageName,
                        activityClassName = app.componentName.className,
                        installedAt = app.firstInstallTime,
                        isNew = (System.currentTimeMillis() - app.firstInstallTime) < Constants.ONE_HOUR_IN_MILLIS,
                        user = profile,
                        category = prefs.appCategoryOverride(app.applicationInfo.packageName, profile.toString())
                            ?: AppCategoryResolver.resolve(app.applicationInfo.packageName,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.applicationInfo.category else -1),
                    )

                    // if the current app is not OLauncher
                    if (app.applicationInfo.packageName != BuildConfig.APPLICATION_ID) {
                        // is this a hidden app?
                        if (HiddenAppKeys.contains(hiddenApps,
                                app.applicationInfo.packageName, profile.toString())) {
                            if (includeHiddenApps) {
                                appList.add(appModel)
                            }
                        } else {
                            // this is a regular app
                            if (includeRegularApps) {
                                appList.add(appModel)
                            }
                        }
                    }
                }
            }

            // A hidden app's pinned shortcuts obey the same profile-specific visibility rule.
            if ((includeRegularApps || includeHiddenApps) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pinned = try {
                    getPinnedShortcuts(context, prefs, hiddenApps, includeRegularApps, includeHiddenApps)
                } catch (e: Exception) {
                    emptyList()
                }
                appList.addAll(pinned)
            }

            appList.sortWith(compareBy(collator) { it.appLabel })
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            android.os.Trace.endSection()
        }
        appList
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun getPinnedShortcuts(
    context: Context,
    prefs: Prefs,
    hiddenApps: Set<String>,
    includeRegularApps: Boolean,
    includeHiddenApps: Boolean,
): List<AppModel.PinnedShortcut> =
    withContext(Dispatchers.IO) {
        val pinnedShortcuts = mutableListOf<AppModel.PinnedShortcut>()
        val shortcuts = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (shortcuts?.hasShortcutHostPermission() == true) {
            val query = LauncherApps.ShortcutQuery().apply {
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            shortcuts.profiles.forEach { profile ->
                if (isPrivateSpaceProfile(context, profile)) return@forEach
                try {
                    shortcuts.getShortcuts(query, profile)?.forEach { shortcut ->
                        val identity = shortcutIdentity(
                            shortcut.`package`,
                            shortcut.id,
                            profile.toString()
                        )
                        val hidden = HiddenAppKeys.contains(hiddenApps,
                            shortcut.`package`, profile.toString())
                        if (shortcut.isPinned && ((hidden && includeHiddenApps) || (!hidden && includeRegularApps)) &&
                            pinnedShortcuts.none { it.identity == identity }) {
                            val label = prefs.getAppRenameLabel(identity)
                                .ifBlank { prefs.getAppRenameLabel(shortcut.id) }
                                .takeIf { it.isNotBlank() }
                                ?: shortcut.shortLabel?.toString()
                                ?: shortcut.longLabel?.toString().orEmpty()
                            pinnedShortcuts.add(
                                AppModel.PinnedShortcut(
                                    appLabel = label,
                                    key = null, // Unread; see getAppsList.
                                    appPackage = shortcut.`package`,
                                    shortcutId = shortcut.id,
                                    isNew = false,
                                    user = profile
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        pinnedShortcuts
    }

fun isPackageInstalled(context: Context, packageName: String, userString: String): Boolean {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val activityInfo = launcher.getActivityList(packageName, getUserHandleFromString(context, userString))
    if (activityInfo.isNotEmpty()) return true
    return false
}

fun isPrivateSpaceProfile(context: Context, userHandle: UserHandle): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
    return try {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.getLauncherUserInfo(userHandle)?.userType == "android.os.usertype.profile.PRIVATE"
    } catch (e: Exception) {
        false
    }
}

fun isPrivateSpaceLocked(context: Context, userHandle: UserHandle): Boolean {
    return try {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        userManager.isQuietModeEnabled(userHandle)
    } catch (e: Exception) {
        true
    }
}

fun getPrivateSpaceUserHandle(context: Context): UserHandle? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (profile in userManager.userProfiles) {
        if (isPrivateSpaceProfile(context, profile)) return profile
    }
    return null
}

suspend fun getPrivateSpaceApps(
    context: Context,
    prefs: Prefs,
): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()
        try {
            val privateSpaceHandle = getPrivateSpaceUserHandle(context) ?: return@withContext appList
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (app in launcherApps.getActivityList(null, privateSpaceHandle)) {
                if (app.applicationInfo.packageName == BuildConfig.APPLICATION_ID) continue
                val appLabelShown = prefs.getAppRenameLabel(app.applicationInfo.packageName)
                    .ifBlank { app.label.toString() }
                appList.add(
                    AppModel.App(
                        appLabel = appLabelShown,
                        key = null, // Unread; see getAppsList.
                        appPackage = app.applicationInfo.packageName,
                        activityClassName = app.componentName.className,
                        isNew = false,
                        user = privateSpaceHandle,
                        category = prefs.appCategoryOverride(app.applicationInfo.packageName, privateSpaceHandle.toString())
                            ?: AppCategoryResolver.resolve(app.applicationInfo.packageName,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.applicationInfo.category else -1),
                    )
                )
            }
            appList.sortWith(compareBy(collator) { it.appLabel })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        appList
    }
}

fun getUserHandleFromString(context: Context, userHandleString: String): UserHandle {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (userHandle in userManager.userProfiles) {
        if (userHandle.toString() == userHandleString) {
            return userHandle
        }
    }
    return android.os.Process.myUserHandle()
}

fun isOlauncherDefault(context: Context): Boolean {
    val launcherPackageName = getDefaultLauncherPackage(context)
    return BuildConfig.APPLICATION_ID == launcherPackageName
}

fun getDefaultLauncherPackage(context: Context): String {
    val intent = Intent()
    intent.action = Intent.ACTION_MAIN
    intent.addCategory(Intent.CATEGORY_HOME)
    val packageManager = context.packageManager
    val result = packageManager.resolveActivity(intent, 0)
    return if (result?.activityInfo != null) {
        result.activityInfo.packageName
    } else "android"
}

fun setPlainWallpaperByTheme(context: Context, appTheme: Int) {
    when (appTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(context, android.R.color.black)
        AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(context, android.R.color.white)
        else -> {
            if (context.isDarkThemeOn())
                setPlainWallpaper(context, android.R.color.black)
            else setPlainWallpaper(context, android.R.color.white)
        }
    }
}

fun setPlainWallpaper(context: Context, color: Int) {
    try {
        val bitmap = createBitmap(1000, 2000)
        bitmap.eraseColor(context.getColor(color))
        val manager = WallpaperManager.getInstance(context)
        manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
        manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
        bitmap.recycle()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun getChangedAppTheme(context: Context, currentAppTheme: Int): Int {
    return when (currentAppTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
        else -> {
            if (context.isDarkThemeOn())
                AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
        }
    }
}

fun openAppInfo(context: Context, userHandle: UserHandle, packageName: String) {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val component = launcher.getActivityList(packageName, userHandle).firstOrNull()?.componentName
    if (component != null)
        launcher.startAppDetailsActivity(component, userHandle, null, null)
    else
        context.showToast(context.getString(R.string.unable_to_open_app_info))
}

private fun InputStream.readBoundedBytes(limit: Int, deadlineMs: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        if (SystemClock.elapsedRealtime() > deadlineMs) throw java.net.SocketTimeoutException("Remote image deadline exceeded")
        val count = read(buffer)
        if (SystemClock.elapsedRealtime() > deadlineMs) throw java.net.SocketTimeoutException("Remote image deadline exceeded")
        if (count < 0) break
        if (output.size() + count > limit) throw java.io.IOException("Remote image is too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun openBoundedConnection(src: String): HttpURLConnection {
    val url = URL(src)
    require(url.protocol == "https")
    return (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 8000
        doInput = true
        // A redirect could lead off the wallpaper host allowlist; anything but a direct 200 fails.
        instanceFollowRedirects = false
        if (responseCode != HttpURLConnection.HTTP_OK) {
            disconnect()
            throw java.io.IOException("HTTP $responseCode")
        }
    }
}

suspend fun getBitmapFromURL(src: String?): Bitmap? = withContext(Dispatchers.IO) {
    if (src.isNullOrBlank()) return@withContext null
    var connection: HttpURLConnection? = null
    try {
        val deadline = SystemClock.elapsedRealtime() + 15000
        connection = openBoundedConnection(src)
        val bytes = connection.inputStream.use { it.readBoundedBytes(8 * 1024 * 1024, deadline) }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || width > 10000 || height > 10000) return@withContext null
        var sample = 1
        while (width.toLong() * height / sample / sample > 6_000_000 ||
            width / sample > 4096 || height / sample > 4096) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: Exception) {
        Log.w("MooWallpaper", "Wallpaper download or decode failed (${e.javaClass.simpleName})")
        null
    } finally {
        connection?.disconnect()
    }
}

suspend fun getWallpaperBitmap(originalImage: Bitmap, width: Int, height: Int): Bitmap {
    return withContext(Dispatchers.IO) {

        val background = createBitmap(width, height)

        val originalWidth: Float = originalImage.width.toFloat()
        val originalHeight: Float = originalImage.height.toFloat()

        val canvas = Canvas(background)
        val heightScale: Float = height / originalHeight
        val widthScale: Float = width / originalWidth
        val scale = maxOf(heightScale, widthScale)

        val (xTranslation, yTranslation) = if (heightScale > widthScale)
            Pair((width - originalWidth * heightScale) / 2.0f, 0f)
        else
            Pair(0f, (height - originalHeight * widthScale) / 2.0f)

        val transformation = Matrix()
        transformation.postTranslate(xTranslation, yTranslation)
        transformation.preScale(scale, scale)

        val paint = Paint()
        paint.isFilterBitmap = true
        canvas.drawBitmap(originalImage, transformation, paint)

        background
    }
}

suspend fun setWallpaper(appContext: Context, url: String): Boolean {
    return withContext(Dispatchers.IO) {
        if (appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && isTablet(appContext).not())
            return@withContext false
        val originalImageBitmap = getBitmapFromURL(url) ?: return@withContext false

        var scaledBitmap: Bitmap? = null
        try {
            val wallpaperManager = WallpaperManager.getInstance(appContext)
            val (width, height) = getScreenDimensions(appContext)
            scaledBitmap = getWallpaperBitmap(originalImageBitmap, width, height)
            wallpaperManager.setBitmap(scaledBitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            wallpaperManager.setBitmap(scaledBitmap, null, false, WallpaperManager.FLAG_LOCK)
            true
        } catch (e: Exception) {
            Log.w("MooWallpaper", "Unable to set wallpaper (${e.javaClass.simpleName})")
            false
        } finally {
            originalImageBitmap.recycle()
            scaledBitmap?.recycle()
        }
    }
}

// Wallpaper sizing needs the full physical display, not the current window bounds.
@Suppress("DEPRECATION")
fun getScreenDimensions(context: Context): Pair<Int, Int> {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val point = Point()
    windowManager.defaultDisplay.getRealSize(point)
    return Pair(point.x, point.y)
}

/** The index entry today's wallpaper comes from. Local only: no network. */
fun todaysWallpaperKey(firstOpenTime: Long): String =
    if (firstOpenTime.isDaySince() < 10)
        String.format("0_%s", firstOpenTime.isDaySince().toString())
    else {
        val month = SimpleDateFormat("M", Locale.ENGLISH).format(Date()) ?: "0"
        val day = SimpleDateFormat("d", Locale.ENGLISH).format(Date()) ?: "0"
        String.format("%s_%s", month, day)
    }

suspend fun getTodaysWallpaper(wallType: String, firstOpenTime: Long): String {
    return withContext(Dispatchers.IO) {
        var wallpaperUrl: String
        try {
            val key = todaysWallpaperKey(firstOpenTime)

            val url = URL(Constants.URL_WALLPAPERS)
            val deadline = SystemClock.elapsedRealtime() + 15000
            val connection = openBoundedConnection(url.toString())
            val payload = try { connection.inputStream.use { it.readBoundedBytes(256 * 1024, deadline) } }
                finally { connection.disconnect() }
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val wallpapers = json.getString(key)
            val wallpapersJson = JSONObject(wallpapers)
            wallpaperUrl = wallpapersJson.getString(wallType)
            if (wallpaperUrl.toUri().let { it.scheme != "https" || it.host !in Constants.WALLPAPER_HOSTS })
                wallpaperUrl = getBackupWallpaper(wallType)
            wallpaperUrl

        } catch (e: Exception) {
            wallpaperUrl = getBackupWallpaper(wallType)
            wallpaperUrl
        }
    }
}

fun getBackupWallpaper(wallType: String): String {
    return if (wallType == Constants.WALL_TYPE_LIGHT)
        Constants.URL_DEFAULT_LIGHT_WALLPAPER
    else Constants.URL_DEFAULT_DARK_WALLPAPER
}

/**
 * Opens the notification shade.
 *
 * The accessibility service first, because it is the only supported route.
 * StatusBarManager.expandNotificationsPanel is a blocklisted non-SDK interface: on Android
 * 9+ the reflection throws, the catch below swallows it, and the gesture does nothing at
 * all - no error, no toast, just a swipe that appears to be ignored. It is kept only for
 * devices where the accessibility grant has not been given, where it may still work.
 */
@SuppressLint("WrongConstant", "PrivateApi")
fun expandNotificationDrawer(context: Context) {
    if (MyAccessibilityService.expandNotifications()) return
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val method = statusBarManager.getMethod("expandNotificationsPanel")
        method.invoke(statusBarService)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openDialerApp(context: Context) {
    try {
        val sendIntent = Intent(Intent.ACTION_DIAL)
        context.startActivity(sendIntent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openCameraApp(context: Context) {
    try {
        val sendIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        context.startActivity(sendIntent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openAlarmApp(context: Context) {
    try {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        context.startActivity(intent)
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.d("MooAlarm", "Alarm app unavailable", e)
    }
}

// The calendar fallback must resolve a calendar app rather than an internal component.
@SuppressLint("UnsafeImplicitIntentLaunch")
fun openCalendar(context: Context) {
    try {
        val calendarUri = CalendarContract.CONTENT_URI
            .buildUpon()
            .appendPath("time")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, calendarUri))
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun isAccessServiceEnabled(context: Context): Boolean {
    val enabled = try {
        Settings.Secure.getInt(context.applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    } catch (e: Exception) {
        0
    }
    if (enabled == 1) {
        val enabledServicesString: String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServicesString?.contains(context.packageName + "/" + MyAccessibilityService::class.java.name) ?: false
    }
    return false
}

// Keep the window manager app metrics used by the existing physical-size threshold.
@Suppress("DEPRECATION")
fun isTablet(context: Context): Boolean {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    windowManager.defaultDisplay.getMetrics(metrics)
    val widthInches = metrics.widthPixels / metrics.xdpi
    val heightInches = metrics.heightPixels / metrics.ydpi
    val diagonalInches = sqrt(widthInches.toDouble().pow(2.0) + heightInches.toDouble().pow(2.0))
    if (diagonalInches >= 7.0) return true
    return false
}

fun Context.isDarkThemeOn(): Boolean {
    return resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
}

fun Context.copyToClipboard(text: String) {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText(getString(R.string.app_name), text)
    clipboardManager.setPrimaryClip(clipData)
    showToast("")
}

fun Context.openUrl(url: String) {
    if (url.isEmpty()) return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { showToast(url) }
}

fun Context.isSystemApp(packageName: String, user: UserHandle? = null): Boolean {
    if (packageName.isBlank()) return true
    return try {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val targetUser = user ?: android.os.Process.myUserHandle()
        val activityList = launcherApps.getActivityList(packageName, targetUser)
        if (activityList.isNotEmpty()) {
            val applicationInfo = activityList.first().applicationInfo
            ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    || (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
        } else {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    || (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.uninstall(packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE)
    intent.data = "package:$packageName".toUri()
    startActivity(intent)
}

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true,
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}


@RequiresApi(Build.VERSION_CODES.N_MR1)
fun Context.deletePinnedShortcut(packageName: String, shortcutIdToDelete: String, user: UserHandle) {
    val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    // 1. Query for existing pinned shortcuts for the package
    val query = LauncherApps.ShortcutQuery().apply {
        setPackage(packageName)
        // Query only for pinned shortcuts
        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    }

    try {
        val pinnedShortcuts = launcherApps.getShortcuts(query, user)

        if (pinnedShortcuts != null) {
            // 2. Filter out the shortcut to be deleted
            val updatedPinnedIds = pinnedShortcuts
                .filter { it.id != shortcutIdToDelete }
                .map { it.id }

            // 3. Re-pin the remaining shortcuts
            // This replaces the existing set of pinned shortcuts for this package
            launcherApps.pinShortcuts(packageName, updatedPinnedIds, user)
        }
    } catch (e: SecurityException) {
        // Handle cases where the app doesn't have permission
        // (e.g., not the default launcher or active voice interaction service)
        if (BuildConfig.DEBUG) Log.e("ShortcutHelper", "Permission denied to modify pinned shortcuts", e)
    } catch (e: IllegalStateException) {
        // Handle cases where the user profile is locked or not running
        if (BuildConfig.DEBUG) Log.e("ShortcutHelper", "User profile unavailable", e)
    } catch (e: Exception) {
        // Handle other potential exceptions (like RemoteException wrapped)
        if (BuildConfig.DEBUG) Log.e("ShortcutHelper", "Failed to modify pinned shortcuts", e)
    }
}
