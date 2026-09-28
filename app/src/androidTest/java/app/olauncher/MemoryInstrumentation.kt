package app.olauncher

import app.olauncher.ui.AudioVisualizerView
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import app.olauncher.helper.IconCache
import app.olauncher.helper.IconPack
import app.olauncher.helper.NotificationCounts
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import android.content.pm.LauncherApps
import app.olauncher.data.HomeAppEntry
import app.olauncher.data.Prefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Framework-only regression tests; no test library ships in the launcher. */
class MemoryInstrumentation : Instrumentation() {
    private var options: Bundle? = null
    override fun onCreate(arguments: Bundle?) { options = arguments; super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        if (options?.getString("badgeLaunchReset") == "true") {
            try {
                check(android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true))
                val app = targetContext.applicationContext as android.app.Application
                val user = android.os.Process.myUserHandle()
                val launcher = app.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val activity = launcher.getActivityList("com.android.settings", user).firstOrNull()
                    ?: error("Settings launcher activity missing on emulator")
                val model = AppModel.App(
                    appLabel = "Settings", key = null, appPackage = "com.android.settings",
                    activityClassName = activity.name, isNew = false, user = user
                )
                val missingPackage = "app.olauncher.badge_fixture_missing"
                val missing = AppModel.App(
                    appLabel = "Missing", key = null, appPackage = missingPackage,
                    activityClassName = null, isNew = false, user = user
                )
                val viewModel = MainViewModel(app)
                val successKey = NotificationCounts.key(model.appPackage, user.toString())
                val failureKey = NotificationCounts.key(missingPackage, user.toString())
                runOnMainSync {
                    NotificationCounts.clear()
                    NotificationCounts.onPosted("badge-fixture-success", successKey, "Private preview")
                    check(NotificationCounts.countFor(successKey) == 1)
                    viewModel.selectedApp(model, Constants.FLAG_LAUNCH_APP)
                    check(NotificationCounts.countFor(successKey) == 0)
                    check(NotificationCounts.linesFor(successKey).isEmpty())

                    NotificationCounts.onPosted("badge-fixture-failure", failureKey, "Keep preview")
                    check(NotificationCounts.countFor(failureKey) == 1)
                    viewModel.selectedApp(missing, Constants.FLAG_LAUNCH_APP)
                    check(NotificationCounts.countFor(failureKey) == 1)
                    check(NotificationCounts.linesFor(failureKey) == listOf("Keep preview"))
                    NotificationCounts.clearApp(failureKey)
                }
                results.putString("stream", "PASS successful Apps launch clears badge; missing app preserves it\n")
                finish(Activity.RESULT_OK, results)
            } catch (error: Throwable) {
                android.util.Log.e("MooInstrumentation", "Badge launch reset failed", error)
                results.putString("stream", "FAIL badge launch reset: $error\n")
                finish(Activity.RESULT_CANCELED, results)
            }
            return
        }
        if (options?.getString("weatherIconTransition") == "true") {
            try {
                check(android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true))
                val prefs = Prefs(targetContext)
                prefs.showWeather = true
                prefs.weatherCached = "64°  H:68°  L:60°"
                prefs.weatherCode = 0
                prefs.weatherIsDay = true
                val zone = java.util.TimeZone.getDefault()
                prefs.weatherTimezone = zone.id
                prefs.weatherForecastDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = zone
                }.format(java.util.Date())
                prefs.weatherDescription = "64°, high 68°, low 60°"
                prefs.weatherUpdatedAt = System.currentTimeMillis()
                check(targetContext.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
                    .edit().putLong("WEATHER_UPDATED_AT", prefs.weatherUpdatedAt).commit())
                val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                waitForIdleSync()
                var failure: Throwable? = null
                runOnMainSync {
                    try {
                        val weather = activity.findViewById<android.view.View>(R.id.homeWeather)
                        val icon = activity.findViewById<android.widget.ImageView>(R.id.weatherIcon)
                        val current = activity.findViewById<android.widget.TextView>(R.id.weatherCurrent)
                        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                            as androidx.navigation.fragment.NavHostFragment
                        val home = host.childFragmentManager.primaryNavigationFragment
                            as app.olauncher.ui.HomeFragment
                        val render = home.javaClass.getDeclaredMethod("renderWeather", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                        fun pixels(): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
                            icon.drawable.setBounds(0, 0, 64, 64)
                            icon.drawable.draw(android.graphics.Canvas(bitmap))
                        }
                        check(icon.visibility == android.view.View.VISIBLE)
                        val description = weather.contentDescription
                        val spoken = description.toString()
                        val spans = (description as android.text.Spanned).getSpans(0, description.length,
                            android.text.style.LocaleSpan::class.java)
                        check(spans.any { it.locale?.language == java.util.Locale.ENGLISH.language })
                        val day = pixels()
                        val dayState = icon.tag
                        Prefs(targetContext).weatherIsDay = false
                        render.invoke(home, current.currentTextColor)
                        val night = pixels()
                        check(weather.contentDescription.toString() == spoken)
                        check(!day.sameAs(night)) { "Weather icon did not repaint: code=${Prefs(targetContext).weatherCode}, isDay=${Prefs(targetContext).weatherIsDay}, day=$dayState, night=${icon.tag}, visible=${icon.visibility}, current=${current.text}, spoken=${weather.contentDescription}" }
                        day.recycle()
                        night.recycle()
                    } catch (error: Throwable) { failure = error }
                }
                activity.finish()
                failure?.let { throw it }
                results.putString("stream", "PASS weather icon repaints on day/night change with unchanged spoken weather\n")
                finish(Activity.RESULT_OK, results)
            } catch (error: Throwable) {
                android.util.Log.e("MooInstrumentation", "Weather icon transition failed", error)
                results.putString("stream", "FAIL weather icon transition: $error\n")
                finish(Activity.RESULT_CANCELED, results)
            }
            return
        }
        options?.getString("weatherFixture")?.let { fixture ->
            try {
                check(android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true))
                val values = fixture.split(",")
                check(values.size in 4..5)
                val prefs = Prefs(targetContext)
                if (values.size == 5) {
                    prefs.appTheme = values[4].toInt()
                    val wallpaper = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    try {
                        wallpaper.eraseColor(if (prefs.appTheme == 1) android.graphics.Color.WHITE
                            else android.graphics.Color.BLACK)
                        android.app.WallpaperManager.getInstance(targetContext).setBitmap(wallpaper)
                    } finally { wallpaper.recycle() }
                }
                prefs.showWeather = true
                prefs.weatherCached = "${values[0]}  H:${values[1]}  L:${values[2]}"
                prefs.weatherCode = values[3].toInt()
                prefs.weatherIsDay = true
                val zone = java.util.TimeZone.getDefault()
                prefs.weatherTimezone = zone.id
                prefs.weatherForecastDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = zone
                }.format(java.util.Date())
                prefs.weatherDescription = "${values[0]}, high ${values[1]}, low ${values[2]}"
                prefs.weatherUpdatedAt = System.currentTimeMillis()
                // Instrumentation exits its process immediately; commit the last fixture value
                // so all earlier apply() writes are on disk before Home starts.
                check(targetContext.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
                    .edit().putLong("WEATHER_UPDATED_AT", prefs.weatherUpdatedAt).commit())
                results.putString("stream", "PASS emulator weather fixture $fixture for ${targetContext.packageName}\n")
                finish(Activity.RESULT_OK, results)
            } catch (error: Throwable) {
                results.putString("stream", "FAIL weather fixture: $error\n")
                finish(Activity.RESULT_CANCELED, results)
            }
            return
        }
        if (options?.getString("widgetLayout") == "true") {
            try {
                check(android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                    android.os.Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true))
                val prefs = Prefs(targetContext)
                val size = options?.getString("size")?.toIntOrNull() ?: 1
                prefs.informationSize = size
                prefs.showWeather = true
                prefs.infoShowBattery = true
                prefs.infoShowScreenTime = true
                prefs.showUnlockCount = true
                prefs.infoShowAlarm = false
                prefs.weatherCached = "64\u00B0  H:68\u00B0  L:60\u00B0"
                prefs.weatherCode = 2
                prefs.weatherIsDay = true
                val zone = java.util.TimeZone.getDefault()
                prefs.weatherTimezone = zone.id
                prefs.weatherForecastDay = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = zone
                }.format(java.util.Date())
                prefs.weatherDescription = "64\u00B0, high 68\u00B0, low 60\u00B0"
                prefs.weatherUpdatedAt = System.currentTimeMillis()
                check(targetContext.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
                    .edit().putLong("WEATHER_UPDATED_AT", prefs.weatherUpdatedAt).commit())
                val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                waitForIdleSync()
                var failure: Throwable? = null
                var geometry = ""
                runOnMainSync {
                    try {
                        val grid = activity.findViewById<app.olauncher.ui.HomeWidgetGrid>(R.id.homeWidgets)
                        check(grid.childCount == 4) { "Expected weather plus three metric widgets" }
                        val weather = activity.findViewById<android.view.View>(R.id.homeWeather)
                        val condition = activity.findViewById<android.widget.TextView>(R.id.weatherCondition)
                        val battery = grid.getChildAt(1) as app.olauncher.ui.HomeInformationWidgetView
                        check(battery.label.text.toString() == "Battery")
                        val editAction = targetContext.getString(R.string.information_edit)
                        listOf(weather, battery).forEach { widget ->
                            val action = widget.createAccessibilityNodeInfo().actionList.firstOrNull {
                                it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                            }
                            check(action?.label?.toString() == editAction) {
                                "Widget needs a named Edit information accessibility action"
                            }
                        }
                        val lowerRight = grid.getChildAt(3)
                        if (grid.getChildAt(2).top == lowerRight.top && lowerRight.top > battery.top) {
                            check(kotlin.math.abs(lowerRight.left - battery.left) <= 1) {
                                "Wrapped second-column widgets do not align"
                            }
                        }
                        val conditionBaseline = weather.top + condition.top + condition.baseline
                        val batteryBaseline = battery.top + battery.label.top + battery.label.baseline
                        val difference = kotlin.math.abs(conditionBaseline - batteryBaseline)
                        check(difference <= 8 * targetContext.resources.displayMetrics.density) {
                            "Weather/Battery second-line baseline differs by " + difference + " px"
                        }
                        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                            as androidx.navigation.fragment.NavHostFragment
                        val home = host.childFragmentManager.primaryNavigationFragment
                            as app.olauncher.ui.HomeFragment
                        home.javaClass.getDeclaredMethod("renderInformationWidgets", List::class.java,
                            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(home,
                            listOf(
                                app.olauncher.helper.InformationPart(R.drawable.ic_bolt, "79%",
                                    "79% charging", caption = "7.4 W"),
                                app.olauncher.helper.InformationPart(R.drawable.ic_usage_outline, "2h 10m"),
                                app.olauncher.helper.InformationPart(R.drawable.ic_unlock_outline, "14")
                            ), condition.currentTextColor)
                        check(battery.value.text.toString() == "79%")
                        check(battery.label.text.toString() == "Battery")
                        check(battery.detail.text.toString() == "7.4 W")
                        check(battery.detail.visibility == android.view.View.VISIBLE)
                        check(kotlin.math.abs(battery.label.textSize - condition.textSize) < 1f)
                        val high = activity.findViewById<android.widget.TextView>(R.id.weatherHigh)
                        check(kotlin.math.abs(battery.detail.textSize - high.textSize) < 1f)
                        check(battery.contentDescription.toString().contains("79% charging"))
                        geometry = "size=" + size + " second-line baseline difference=" + difference +
                            "px; battery label and wattage aligned"
                    } catch (error: Throwable) { failure = error }
                }
                activity.finish()
                failure?.let { throw it }
                results.putString("stream", "PASS widget layout " + geometry + "\n")
                finish(Activity.RESULT_OK, results)
            } catch (error: Throwable) {
                android.util.Log.e("MooInstrumentation", "Widget layout failed", error)
                results.putString("stream", "FAIL widget layout: " + error + "\n")
                finish(Activity.RESULT_CANCELED, results)
            }
            return
        }
        try {
            val field = IconCache.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val cache = field.get(IconCache) as LruCache<String, Drawable>
            IconCache.clear()
            val retained = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            cache.put("visible", BitmapDrawable(targetContext.resources, retained))
            repeat(20) {
                cache.put("test-$it", BitmapDrawable(targetContext.resources,
                    Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)))
            }
            check(cache.size() <= 2 * 1024 * 1024 && cache.snapshot().size <= 8)
            check(!retained.isRecycled)
            val app = targetContext.applicationContext as MooApplication
            // UI_HIDDEN keeps the cache (Home is back within seconds); only real pressure empties it.
            val beforeHidden = cache.size()
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            check(cache.size() == beforeHidden && cache.size() <= 2 * 1024 * 1024)
            app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            check(cache.size() == 0)

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = Thread {
                synchronized(IconPack) { held.countDown(); release.await(3, TimeUnit.SECONDS) }
            }
            worker.start()
            try {
                check(held.await(2, TimeUnit.SECONDS))
                val resetDone = CountDownLatch(1)
                val reset = Thread { IconPack.reset(); resetDone.countDown() }
                reset.start()
                check(resetDone.await(500, TimeUnit.MILLISECONDS)) { "Reset blocked behind parser monitor" }
                reset.join(1000)
            } finally { release.countDown(); worker.join(4000) }
            var visualizerError: Throwable? = null
            runOnMainSync {
                try {
                    val view = AudioVisualizerView(android.view.ContextThemeWrapper(targetContext, R.style.AppTheme))
                    val session = android.media.session.MediaSession(targetContext, "error-state-regression")
                    try {
                        view.bindNotification(session.sessionToken)
                        val title = view.getChildAt(0) as android.widget.TextView
                        check(title.visibility == android.view.View.GONE)
                        view.javaClass.getDeclaredMethod("showCaptureFailure").apply { isAccessible = true }.invoke(view)
                        check(title.visibility == android.view.View.VISIBLE)
                        check(title.text.toString() == targetContext.getString(R.string.visualizer_unavailable))
                        check(view.getChildAt(1).visibility == android.view.View.GONE)
                    } finally { session.release() }
                } catch (error: Throwable) { visualizerError = error }
            }
            visualizerError?.let { throw it }
            // Isolate preference round trips from the user's live launcher data.
            val testContext = object : ContextWrapper(targetContext) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                    baseContext.getSharedPreferences("${name}_instrumentation", mode)
            }
            val store = testContext.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
            store.edit().clear().commit()
            try {
                val fresh = Prefs(testContext)
                check(fresh.autoSortHomeApps && fresh.weatherRefreshMinutes == 60)
                fresh.replaceHomeApps(listOf(
                    HomeAppEntry("Zulu", "pkg.z", "UserHandle{0}"),
                    HomeAppEntry("Alpha", "pkg.a", "UserHandle{0}")))
                // A pre-existing arrangement without a sort preference remains manual.
                check(!Prefs(testContext).autoSortHomeApps)
                fresh.autoSortHomeApps = true
                fresh.weatherRefreshMinutes = 180
                val reopened = Prefs(testContext)
                check(reopened.autoSortHomeApps && reopened.weatherRefreshMinutes == 180)
                check(reopened.homeAppEntries().map { it.name } == listOf("Alpha", "Zulu"))
                reopened.autoSortHomeApps = false
                check(Prefs(testContext).homeAppEntries().map { it.name } == listOf("Alpha", "Zulu"))
                reopened.showWeather = true
                reopened.weatherCached = "12�  H:17�  L:8�"
                reopened.weatherForecastDay = "2026-09-24"
                reopened.weatherTimezone = "America/Chicago"
                reopened.weatherCode = 2
                reopened.weatherDescription = "Partly cloudy"
                reopened.weatherUpdatedAt = 1234L
                reopened.disableWeatherAndClearCache()
                val weatherOff = Prefs(testContext)
                check(!weatherOff.showWeather && weatherOff.weatherCached.isEmpty())
                check(weatherOff.weatherForecastDay.isEmpty() && weatherOff.weatherTimezone.isEmpty())
                check(weatherOff.weatherCode == -1 && weatherOff.weatherDescription.isEmpty())
                check(weatherOff.weatherUpdatedAt == 0L)
                // A response that completes after the user disables Weather cannot refill the cache.
                check(!reopened.storeWeatherIfEnabled("21°  H:24°  L:16°", "2026-09-24",
                    "America/Chicago", 2, true, "Partly cloudy", 5678L))
                val afterLateResponse = Prefs(testContext)
                check(!afterLateResponse.showWeather && afterLateResponse.weatherCached.isEmpty())
                check(afterLateResponse.weatherCode == -1 && afterLateResponse.weatherUpdatedAt == 0L)
                afterLateResponse.showWeather = true
                check(afterLateResponse.storeWeatherIfEnabled("21°  H:24°  L:16°", "2026-09-24",
                    "America/Chicago", 2, true, "Partly cloudy", 5678L))
                check(Prefs(testContext).weatherCached.startsWith("21°"))
            } finally { store.edit().clear().commit() }
            results.putString("stream", "PASS inline capture failure, bounded icon cache, trim, non-blocking reset; isolated Home order and weather preference persistence\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            android.util.Log.e("MooInstrumentation", "Regression failure", error)
            results.putString("stream", "FAIL: $error\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
