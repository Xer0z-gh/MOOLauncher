package app.olauncher

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.helper.SingleLiveEvent
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.WallpaperWorker
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getAppsList
import app.olauncher.helper.getPrivateSpaceApps
import app.olauncher.helper.getPrivateSpaceUserHandle
import app.olauncher.helper.hasBeenMinutes
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.isPrivateSpaceLocked
import app.olauncher.helper.showToast
import app.olauncher.helper.usageStats.EventLogWrapper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val APP_LIST_CACHE_MS = 5 * 60_000L }

    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)
    private var appListJob: Job? = null
    private var appListLoadedAt = 0L
    private var appListIncludesHiddenApps: Boolean? = null
    private var appListRequestIncludesHiddenApps: Boolean? = null
    private var privateSpaceJob: Job? = null
    private var privateSpaceLoadedAt = 0L
    private var privateSpaceHandleAtLoad: android.os.UserHandle? = null
    private var privateSpaceLockedAtLoad: Boolean? = null

    val firstOpen = MutableLiveData<Boolean>()
    val refreshHome = MutableLiveData<Boolean>()
    val toggleDateTime = MutableLiveData<Unit>()
    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val isOlauncherDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()
    val homeAppAlignment = MutableLiveData<Int>()
    val screenTimeValue = MutableLiveData<String>()

    /** Today's unlock count, or -1 when it cannot be counted on this Android version. */
    val unlockCountValue = MutableLiveData<Int>()

    val privateSpaceApps = MutableLiveData<List<AppModel>?>()
    val privateSpaceLocked = MutableLiveData<Boolean>()
    val privateSpaceAvailable = MutableLiveData<Boolean>()

    // Suppress backToHomeScreen during Private Space lock/unlock auth
    var isPrivateSpaceToggling = false

    val showDialog = SingleLiveEvent<String>()
    val resetLauncherLiveData = SingleLiveEvent<Unit?>()
    // Home button for recents feature disabled
    // val showRecentApps = SingleLiveEvent<Unit?>()

    fun selectedApp(appModel: AppModel, flag: Int) {
        if (flag in Constants.FLAG_HOME_SLOT_BASE + 1..Constants.FLAG_HOME_SLOT_BASE + 512) {
            saveHomeApp(appModel, flag - Constants.FLAG_HOME_SLOT_BASE)
            return
        }
        if (appModel is AppModel.PrivateSpaceHeader || appModel is AppModel.CategoryHeader) return
        when (flag) {
            Constants.FLAG_HOME_ADD_AUTO -> {
                prefs.addHomeApp(appModel)
                refreshHome(false)
            }
            Constants.FLAG_LAUNCH_APP -> {
                when (appModel) {
                    is AppModel.PinnedShortcut -> launchShortcut(appModel)
                    is AppModel.App ->
                        launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)

                    else -> {}
                }
            }

            Constants.FLAG_HIDDEN_APPS -> {
                if (appModel is AppModel.App) {
                    launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
                }
            }

            Constants.FLAG_SET_HOME_APP_1 -> saveHomeApp(appModel, 1)
            Constants.FLAG_SET_HOME_APP_2 -> saveHomeApp(appModel, 2)
            Constants.FLAG_SET_HOME_APP_3 -> saveHomeApp(appModel, 3)
            Constants.FLAG_SET_HOME_APP_4 -> saveHomeApp(appModel, 4)
            Constants.FLAG_SET_HOME_APP_5 -> saveHomeApp(appModel, 5)
            Constants.FLAG_SET_HOME_APP_6 -> saveHomeApp(appModel, 6)
            Constants.FLAG_SET_HOME_APP_7 -> saveHomeApp(appModel, 7)
            Constants.FLAG_SET_HOME_APP_8 -> saveHomeApp(appModel, 8)

            Constants.FLAG_SET_GESTURE_APP_SWIPE_UP,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_DOWN,
            Constants.FLAG_SET_GESTURE_APP_DOUBLE_TAP,
            Constants.FLAG_SET_GESTURE_APP_LONG_PRESS,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_LEFT,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_RIGHT,
                -> Constants.gestureForFlag(flag)?.let { saveGestureApp(appModel, it) }
            Constants.FLAG_SET_CLOCK_APP -> saveClockApp(appModel)
            Constants.FLAG_SET_CALENDAR_APP -> saveCalendarApp(appModel)
            Constants.FLAG_SET_SCREEN_TIME_APP -> saveScreenTimeApp(appModel)
        }
    }

    private fun launchShortcut(appModel: AppModel.PinnedShortcut) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_shortcut))
            return
        }
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(appModel.appPackage)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }
        try {
            val shortcut = launcher.getShortcuts(query, appModel.user)
                ?.find { it.id == appModel.shortcutId }
            if (shortcut == null) {
                appContext.showToast(appContext.getString(R.string.shortcut_not_found))
                return
            }
            launcher.startShortcut(shortcut, null, null)
            clearOpenedAppBadge(appModel.appPackage, appModel.user)
        } catch (_: Exception) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_shortcut))
        }
    }

    private fun saveHomeApp(appModel: AppModel, position: Int) {
        prefs.saveHomeApp(appModel, position)
        refreshHome(false)
    }

    /**
     * Binds an app to a gesture. Shortcuts are not accepted: a pinned shortcut needs the
     * LauncherApps shortcut path to start, which the gesture dispatcher does not use, so
     * accepting one here would save a binding that silently does nothing.
     */
    private fun saveGestureApp(appModel: AppModel, gesture: String) {
        // Choosing an app IS choosing Launch app. Set it here, after the pick, so abandoning
        // the picker leaves the gesture as it was.
        if (appModel is AppModel.PrivateSpaceHeader || appModel is AppModel.CategoryHeader) return
        prefs.setGestureAction(gesture, Constants.GestureAction.LAUNCH_APP)
        when (appModel) {
            is AppModel.PrivateSpaceHeader, is AppModel.CategoryHeader -> return
            is AppModel.App -> prefs.setGestureApp(
                gesture = gesture,
                name = appModel.appLabel,
                appPackage = appModel.appPackage,
                className = appModel.activityClassName.orEmpty(),
                user = appModel.user.toString()
            )
            // Shortcuts were droppable before, because only the old swipe-left and
            // swipe-right settings could hold one. Now every gesture can.
            is AppModel.PinnedShortcut -> prefs.setGestureApp(
                gesture = gesture,
                name = appModel.appLabel,
                appPackage = appModel.appPackage,
                className = "",
                user = appModel.user.toString(),
                isShortcut = true,
                shortcutId = appModel.shortcutId
            )
        }
    }

    private fun saveClockApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.clockAppPackage = appModel.appPackage
            prefs.clockAppUser = appModel.user.toString()
            prefs.clockAppClassName = appModel.activityClassName
        }
    }

    private fun saveCalendarApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.calendarAppPackage = appModel.appPackage
            prefs.calendarAppUser = appModel.user.toString()
            prefs.calendarAppClassName = appModel.activityClassName
        }
    }

    private fun saveScreenTimeApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.screenTimeAppPackage = appModel.appPackage
            prefs.screenTimeAppUser = appModel.user.toString()
            prefs.screenTimeAppClassName = appModel.activityClassName
        }
    }

    fun firstOpen(value: Boolean) {
        firstOpen.postValue(value)
    }

    fun refreshHome(appCountUpdated: Boolean) {
        refreshHome.value = appCountUpdated
    }

    fun toggleDateTime() {
        toggleDateTime.postValue(Unit)
    }

    private fun clearOpenedAppBadge(packageName: String, userHandle: UserHandle) {
        NotificationCounts.clearApp(NotificationCounts.key(packageName, userHandle.toString()))
    }

    private fun launchApp(packageName: String, activityClassName: String?, userHandle: UserHandle) {
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityInfo = launcher.getActivityList(packageName, userHandle)

        val isActivityValid = activityClassName.isNullOrBlank().not()
                && activityInfo.any { it.componentName.className == activityClassName }

        val component = if (isActivityValid)
            ComponentName(packageName, activityClassName)
        else {
            when (activityInfo.size) {
                0 -> {
                    appContext.showToast(appContext.getString(R.string.app_not_found))
                    return
                }

                1 -> ComponentName(packageName, activityInfo[0].name)
                else -> ComponentName(packageName, activityInfo[activityInfo.size - 1].name)
            }.also { prefs.updateAppActivityClassName(packageName, it.className) }
        }

        try {
            launcher.startMainActivity(component, userHandle, null, null)
            clearOpenedAppBadge(packageName, userHandle)
        } catch (e: SecurityException) {
            try {
                val personalUser = android.os.Process.myUserHandle()
                launcher.startMainActivity(component, personalUser, null, null)
                clearOpenedAppBadge(packageName, personalUser)
            } catch (e: Exception) {
                appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            }
        } catch (e: Exception) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_app))
        }
    }

    /** Opening Apps can reuse the last scan; install/edit callbacks still call getAppList directly. */
    fun ensureAppList(includeHiddenApps: Boolean = false) {
        val age = SystemClock.elapsedRealtime() - appListLoadedAt
        if (appList.value != null && appListIncludesHiddenApps == includeHiddenApps &&
            age in 0L..APP_LIST_CACHE_MS) {
            getPrivateSpaceAppList()
            return
        }
        if (appListJob?.isActive == true && appListRequestIncludesHiddenApps == includeHiddenApps) return
        getAppList(includeHiddenApps)
    }

    fun getAppList(includeHiddenApps: Boolean = false) {
        appListJob?.cancel()
        appListLoadedAt = 0L
        appListRequestIncludesHiddenApps = includeHiddenApps
        // A picker that includes hidden apps must never flash its cached rows in ordinary Browse.
        if (appListIncludesHiddenApps != null && appListIncludesHiddenApps != includeHiddenApps)
            appList.value = null
        val generation = appListGeneration
        appListJob = viewModelScope.launch {
            val apps = getAppsList(appContext, prefs, includeRegularApps = true, includeHiddenApps)
            appListIncludesHiddenApps = includeHiddenApps
            // cancel() cannot stop a scan already inside its IO block, so one that started before
            // an invalidation can still land here. Its rows are shown, but not trusted as fresh.
            appListLoadedAt = if (apps.isEmpty() || generation != appListGeneration) 0L
                else SystemClock.elapsedRealtime()
            appList.value = apps
        }
        getPrivateSpaceAppList(force = true)
    }

    private var appListGeneration = 0
    private var appListRefresh: Job? = null

    /**
     * An app or shortcut changed while nothing is showing the list. Costs nothing now: the next
     * ensureAppList() - opening Apps - rescans instead of reusing the cached rows.
     */
    fun invalidateAppList() {
        appListGeneration++
        appListLoadedAt = 0L
        privateSpaceLoadedAt = 0L
    }

    /**
     * One scan for a burst of LauncherApps callbacks. An update of one app can report several
     * package and shortcut changes in a row, and each used to start its own full scan.
     */
    fun requestAppListRefresh(delayMs: Long) {
        invalidateAppList()
        appListRefresh?.cancel()
        appListRefresh = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            // The mode the screen on top last ASKED for, not the last one that finished: a picker
            // that just requested hidden apps must not be cancelled by a scan without them.
            getAppList(appListRequestIncludesHiddenApps ?: appListIncludesHiddenApps ?: false)
        }
    }

    fun getHiddenApps() {
        viewModelScope.launch {
            hiddenApps.value =
                getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = true)
        }
    }

    fun isOlauncherDefault() {
        isOlauncherDefault.value = isOlauncherDefault(appContext)
    }

    fun setWallpaperWorker() {
        // Every enqueue site comes through here, so this one guard keeps the job off while the
        // Ultra saver is on - it used to wake the phone (and cold-start a killed launcher) every
        // 4 hours only for the worker to return early. The Ultra switch calls this again on the
        // way out. Android Power Saver is left to the worker's own early return: it defers jobs.
        if (prefs.ultraBatterySaver) {
            WorkManager.getInstance(appContext).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val uploadWorkRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(4, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager
            .getInstance(appContext)
            .enqueueUniquePeriodicWork(
                Constants.WALLPAPER_WORKER_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                uploadWorkRequest
            )
    }

    fun cancelWallpaperWorker() {
        WorkManager.getInstance(appContext).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        prefs.dailyWallpaperUrl = ""
        prefs.dailyWallpaperKey = ""
        prefs.dailyWallpaper = false
    }

    fun updateHomeAlignment(gravity: Int) {
        prefs.homeAlignment = gravity
        homeAppAlignment.value = prefs.homeAlignment
    }

    private var screenTimeJob: Job? = null

    /** (screen time, unlocks) as they were for the last scan; null until the first. */
    private var scannedFor: Pair<Boolean, Boolean>? = null

    /**
     * Walks a full day of usage events and aggregates them, which on a phone in daily use is
     * thousands of events (the look-back for apps open across midnight is cached per day in
     * UnmatchedCloseEventGuardian). This used to run synchronously on the main thread from
     * HomeFragment.onResume, so the home screen froze for the length of the scan every time the
     * user pressed Home - the most visible possible moment to stall.
     */
    fun getTodaysScreenTime() {
        // The minute gate covers a scan of the SAME widgets. Each scan now only computes what
        // is switched on, so turning the other widget on must scan at once, not show "Loading…"
        // until a resume a minute later. And the gate is persisted but the values are not:
        // scannedFor starts null, so a fresh process always scans.
        val wanted = prefs.infoShowScreenTime to prefs.showUnlockCount
        if (wanted == scannedFor && prefs.screenTimeLastUpdated.hasBeenMinutes(1).not()) return
        // Claim the minute window BEFORE going async. The gate above reads a timestamp that used
        // to be written at the end of a synchronous scan; off the main thread, two resumes a
        // second apart would both pass it and start concurrent full-day scans.
        if (screenTimeJob?.isActive == true && wanted == scannedFor) return
        screenTimeJob?.cancel()
        scannedFor = wanted
        val endTime = System.currentTimeMillis()
        prefs.screenTimeLastUpdated = endTime

        screenTimeJob = viewModelScope.launch(Dispatchers.Default) {
            val eventLogWrapper = EventLogWrapper(appContext)
            // Start of today in millis
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis

            // Each widget pays only for itself. The unlock count used to be a second full-day
            // queryEvents - parcelling the whole day's log over binder again - even with screen
            // time on, where the first pass already walks every KEYGUARD_HIDDEN, and even with
            // the unlock count switched off.
            if (prefs.infoShowScreenTime) {
                val timeSpent = eventLogWrapper.aggregateSimpleUsageStats(
                    eventLogWrapper.aggregateForegroundStats(
                        eventLogWrapper.getForegroundStatsByTimestamps(startTime, endTime)
                    )
                )
                screenTimeValue.postValue(appContext.formattedTimeSpent(timeSpent))
                // Free here, so always posted: a widget switched on later has a value at once.
                unlockCountValue.postValue(eventLogWrapper.unlockCount)
            } else if (prefs.showUnlockCount) unlockCountValue.postValue(countUnlocksSince(startTime, endTime))
        }
    }

    /**
     * How many times the phone was unlocked today. KEYGUARD_HIDDEN is the event that actually
     * means "the user got in", as opposed to SCREEN_INTERACTIVE which also fires for a glance at
     * the lock screen. It needs API 28; below that there is no honest way to count this, so the
     * widget simply reports nothing rather than a number that means something else.
     */
    private fun countUnlocksSince(start: Long, end: Long): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return runCatching {
            val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = manager.queryEvents(start, end)
            val event = UsageEvents.Event()
            var count = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) count++
            }
            count
        }.getOrDefault(-1)
    }

    fun getPrivateSpaceAppList(force: Boolean = false) {
        if (!force && privateSpaceJob?.isActive == true) return
        privateSpaceJob?.cancel()
        privateSpaceJob = viewModelScope.launch {
            // Off main: viewModelScope starts immediately on the caller's frame, and this is two
            // or three binder calls (profiles, launcher user info, quiet mode) on every Apps open.
            val (handle, locked) = withContext(Dispatchers.IO) {
                val h = getPrivateSpaceUserHandle(appContext)
                h to (h == null || isPrivateSpaceLocked(appContext, h))
            }
            if (privateSpaceAvailable.value != (handle != null))
                privateSpaceAvailable.value = handle != null
            if (privateSpaceLocked.value != locked) privateSpaceLocked.value = locked
            if (handle == null) {
                if (privateSpaceApps.value?.isNotEmpty() != false)
                    privateSpaceApps.value = emptyList()
                privateSpaceHandleAtLoad = null
                privateSpaceLockedAtLoad = true
                privateSpaceLoadedAt = SystemClock.elapsedRealtime()
                return@launch
            }
            val age = SystemClock.elapsedRealtime() - privateSpaceLoadedAt
            if (!force && privateSpaceApps.value != null &&
                privateSpaceHandleAtLoad == handle && privateSpaceLockedAtLoad == locked &&
                age in 0L..APP_LIST_CACHE_MS) return@launch
            privateSpaceApps.value = getPrivateSpaceApps(appContext, prefs)
            privateSpaceHandleAtLoad = handle
            privateSpaceLockedAtLoad = locked
            privateSpaceLoadedAt = SystemClock.elapsedRealtime()
        }
    }

    fun openPrivateSpaceSettings() {
        try {
            val intent = Intent("android.settings.PRIVATE_SPACE_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (_: Exception) {
                appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            }
        }
    }

    fun togglePrivateSpaceLock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val handle = getPrivateSpaceUserHandle(appContext) ?: return
        try {
            isPrivateSpaceToggling = true
            val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
            val currentlyLocked = userManager.isQuietModeEnabled(handle)
            userManager.requestQuietModeEnabled(!currentlyLocked, handle)
        } catch (e: Exception) {
            isPrivateSpaceToggling = false
            e.printStackTrace()
        }
    }

    fun setDefaultClockApp() {
        viewModelScope.launch {
            try {
                Constants.CLOCK_APP_PACKAGES.firstOrNull { appContext.isPackageInstalled(it) }?.let { packageName ->
                    appContext.packageManager.getLaunchIntentForPackage(packageName)?.component?.className?.let {
                        prefs.clockAppPackage = packageName
                        prefs.clockAppClassName = it
                        prefs.clockAppUser = android.os.Process.myUserHandle().toString()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
