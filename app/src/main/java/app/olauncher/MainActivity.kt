package app.olauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.pro.ProStore
import app.olauncher.databinding.ActivityMainBinding
import app.olauncher.helper.IconCache
import app.olauncher.helper.LauncherMotion
import app.olauncher.helper.SaverWindow
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isDefaultLauncher
import app.olauncher.helper.OlDialog
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.resetLauncherViaFakeActivity
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.showLauncherSelector
import app.olauncher.helper.showMessageDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.BaseFragment
import app.olauncher.ui.HomeCarousel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var timerJob: Job? = null
    private var isResumed = false
    private var profileReceiver: BroadcastReceiver? = null
    private var launcherAppsCallback: LauncherApps.Callback? = null
    private var messageDialog: OlDialog? = null

//    override fun onBackPressed() {
//        if (navController.currentDestination?.id != R.id.mainFragment)
//            super.onBackPressed()
//    }


    /**
     * Font is a theme attribute, so it can only be chosen at theme-application time - which is
     * why changing it restarts the Activity rather than repainting.
     */
    private fun fontOverlay(index: Int): Int = when (index) {
        Constants.Font.REGULAR -> R.style.FontRegular
        Constants.Font.MEDIUM -> R.style.FontMedium
        Constants.Font.CONDENSED -> R.style.FontCondensed
        Constants.Font.SERIF -> R.style.FontSerif
        Constants.Font.MONOSPACE -> R.style.FontMonospace
        Constants.Font.INTER -> R.style.FontInter
        else -> R.style.FontLight
    }

    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        // Multiply, never assign. Assigning threw away the phone's own font size, so a
        // launcher - the first screen after unlock - rendered at 1.0 however large the
        // user had set their display. The slider here is a multiplier on top of it.
        newConfig.fontScale = context.resources.configuration.fontScale * Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        prefs.migrateSwipeGestures()
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        super.onCreate(savedInstanceState)
        theme.applyStyle(fontOverlay(prefs.fontIndex), true)
        if (isEinkDisplay() || isSystemAnimationsDisabled()) theme.applyStyle(R.style.NoAnimationOverlay, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navController = this.findNavController(R.id.nav_host_fragment)
        // The saver drops the wallpaper layer only while Home itself is showing; see SaverWindow.
        navController.addOnDestinationChangedListener { _, _, _ -> applyPowerWindow() }
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back never leaves the home screen; elsewhere it pops the nav stack
                if (navController.currentDestination?.id != R.id.mainFragment)
                    navController.popBackStack()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.setDefaultClockApp()
            viewModel.resetLauncherLiveData.call()
        }

        initObservers(viewModel)
        // After the first frame; see HomeFragment.onResume for the measured reason.
        binding.root.doOnPreDraw { root -> root.post { afterFirstFrame() } }
        registerShortcutCallback()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)
        applyRotationPolicy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            profileReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    viewModel.isPrivateSpaceToggling = false
                    viewModel.getPrivateSpaceAppList(force = true)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
            registerReceiver(profileReceiver, filter)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_TAB || event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            ::navController.isInitialized && navController.currentDestination?.id == R.id.mainFragment) {
            if (findViewById<HomeCarousel>(R.id.homeAppsScroll)?.prepareKeyboardNavigation(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        restartLauncherOrCheckTheme()
        // The cache is a process-wide singleton, so it has to learn the chosen pack once per
        // process start rather than only when the setting is changed.
        IconCache.iconPackPackage = prefs.iconPackPackage
        // Registered for the whole visible span. Pulling the notification shade over Home does not
        // stop the activity, and that is exactly where Power Saver usually gets switched on.
        ContextCompat.registerReceiver(this, powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        applyPowerWindow()
    }

    /**
     * Work that used to run before Home's first frame and never needed to.
     *
     * The app list: nothing on the first frame reads it. And WorkManager, which no longer starts
     * at every process start (see the manifest): when the daily wallpaper is on it is started here
     * instead, off the main thread, because its start-up is also where it notices a force-stop and
     * re-registers jobs the system dropped - skip it and the wallpaper would quietly stop.
     */
    private fun afterFirstFrame() {
        if (isFinishing || isDestroyed) return
        viewModel.getAppList()
        if (prefs.dailyWallpaper) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { androidx.work.WorkManager.getInstance(applicationContext) }
        }
    }

    /**
     * The only Power Saver receiver. Each BaseFragment used to register its own in onStart and
     * drop it in onStop: two synchronous ActivityManager binder calls on the main thread per
     * fragment swap, app launch and return, all during transitions, for a broadcast this one
     * already receives. Fragments are started only inside this activity's onStart..onStop, where
     * this receiver is registered, so forwarding to the STARTED ones keeps the same coverage.
     * STARTED rather than "has a view" so a page still animating out is not driven.
     */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyPowerWindow()
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                ?.childFragmentManager?.fragments?.forEach { fragment ->
                    if (fragment is BaseFragment && fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        fragment.dispatchPowerStateChanged()
                }
        }
    }

    /**
     * Applies the window-level half of the Ultra battery saver - refresh rate and wallpaper layer.
     * Public so the Settings switch can apply a manual change at once instead of at the next start.
     */
    fun applyPowerWindow() {
        if (!::prefs.isInitialized) return
        val homeShowing = ::navController.isInitialized &&
            navController.currentDestination?.id == R.id.mainFragment
        val saving = LauncherMotion.savingPower(this, prefs)
        SaverWindow.apply(this, saving, homeShowing)
        // The saver paints Home black and sets light bar icons for it, and only Home sets bar
        // icons at all, so they carried over: white icons on the white Settings page, drawer
        // and panel of a light theme. Away from Home, match the page those screens paint.
        if (saving && !homeShowing) {
            val light = androidx.core.graphics.ColorUtils.calculateLuminance(
                app.olauncher.helper.settingsPageColor(this, prefs)) > .5
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        viewModel.isPrivateSpaceToggling = false
        // Moo Pro (Play builds): one PackageManager lookup, so buying or refunding it applies on return.
        if (ProStore.refresh(this)) recreate()
        // Something was installed, removed or pinned while away: one scan, after the return frame.
        if (appsChangedWhileAway) {
            appsChangedWhileAway = false
            viewModel.requestAppListRefresh(400L)
        }
        // No getAppList() here on purpose: it enumerated every launchable activity, resolved a
        // label per app and re-sorted with a Collator on every single Home press, even when only
        // the home screen was showing and nothing consumed the list. The LauncherApps.Callback
        // above now refreshes it when the set of installed apps actually changes.
    }

    private fun registerShortcutCallback() {
        val launcherApps = getSystemService(LauncherApps::class.java)
        launcherAppsCallback = object : LauncherApps.Callback() {
            // These three used to be no-ops, which is why onResume re-scanned every launchable
            // activity on every Home press. Answering the platform's own events instead means the
            // scan runs when the app list actually changes, which is rarely.
            // Added and removed refresh almost at once, so an uninstall from the drawer does not
            // leave a dead row; the others coalesce, because one app update reports several.
            override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) =
                onAppsChanged(150L)

            override fun onPackageAdded(packageName: String, user: android.os.UserHandle) =
                onAppsChanged(150L)

            override fun onPackageChanged(packageName: String, user: android.os.UserHandle) =
                onAppsChanged(750L)
            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean,
            ) = Unit

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean,
            ) = Unit

            override fun onShortcutsChanged(
                packageName: String,
                shortcuts: MutableList<ShortcutInfo>,
                user: android.os.UserHandle,
            ) {
                // Messaging apps publish a conversation shortcut with nearly every message, and
                // this fires for each one. The list shows only pinned shortcuts, so only a change
                // that adds one, or touches a package that has one listed (an unpin or removal no
                // longer appears in `shortcuts`), can change a row.
                val listed = viewModel.appList.value?.any {
                    it is AppModel.PinnedShortcut && it.appPackage == packageName && it.user == user
                } == true
                if (listed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                    shortcuts.any { it.isPinned }) onAppsChanged(750L)
            }
        }
        launcherApps.registerCallback(launcherAppsCallback!!)
    }

    private var appsChangedWhileAway = false

    /**
     * The callback is registered for the activity's whole life, so it also fires with another app
     * in front and with the screen off - and every call used to run a full scan: every launchable
     * activity's label, a Collator sort, the pinned shortcuts and the private space. Away from
     * Home it now only marks the list stale, and one scan runs on the way back.
     */
    private fun onAppsChanged(delayMs: Long) {
        if (isResumed) viewModel.requestAppListRefresh(delayMs)
        else {
            appsChangedWhileAway = true
            viewModel.invalidateAppList()
        }
    }

    override fun onStop() {
        isResumed = false
        runCatching { unregisterReceiver(powerSaveReceiver) }
        // Leaving the launcher returns to Home, but a recreate is not leaving: text size, weight,
        // font and theme recreate the page, and this used to drop you from Settings onto Home.
        if (!isChangingConfigurations) backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        // Home button for recents feature disabled
        // val alreadyHome = navController.currentDestination?.id == R.id.mainFragment
        backToHomeScreen()
        // if (alreadyHome && isResumed && prefs.homeButtonShowRecents)
        //     viewModel.showRecentApps.call()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        if (prefs.dailyWallpaper && AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            setPlainWallpaper()
            viewModel.setWallpaperWorker()
            recreate()
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
        viewModel.showDialog.observe(this) {
            when (it) {
                Constants.Dialog.HIDDEN -> {
                    showMessage(R.string.hidden_apps, R.string.hidden_apps_message, R.string.okay) {
                    }
                }

                Constants.Dialog.KEYBOARD -> {
                    showMessage(R.string.app_name, R.string.keyboard_message, R.string.okay) {
                    }
                }

                Constants.Dialog.DIGITAL_WELLBEING -> {
                    showMessage(R.string.screen_time, R.string.app_usage_message, R.string.permission) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }

            }
        }
    }

    private fun showMessage(title: Int, message: Int, action: Int, clickListener: () -> Unit) {
        messageDialog?.dismiss()
        messageDialog = showMessageDialog(title, message, action, clickListener)
    }

    private fun backToHomeScreen() {
        if (viewModel.isPrivateSpaceToggling) return
        messageDialog?.dismiss()
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    private fun setPlainWallpaper() {
        if (this.isDarkThemeOn())
            setPlainWallpaper(this, android.R.color.black)
        else setPlainWallpaper(this, android.R.color.white)
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    /**
     * Recreates only when the theme is actually wrong. Olauncher also recreated the activity, and
     * wiped cacheDir, on the first Home press after every 4 hours: a full re-inflate of Home at the
     * most visible moment, fixing nothing - nothing writes to cacheDir, and checkTheme() below is
     * the recovery for a wrong theme. Date, battery and weather refresh on their own in onResume.
     */
    private fun restartLauncherOrCheckTheme(forceRestart: Boolean = false) {
        if (forceRestart) recreate() else checkTheme()
    }

    private fun checkTheme() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            delay(200)
            if ((prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.white))
                || (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_NO && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.black))
            )
                restartLauncherOrCheckTheme(true)
        }
    }

    override fun onDestroy() {
        messageDialog?.dismiss()
        messageDialog = null
        launcherAppsCallback?.let {
            getSystemService(LauncherApps::class.java).unregisterCallback(it)
        }
        profileReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == Activity.RESULT_OK)
                    resetLauncherViaFakeActivity()
            }
        }
    }

    /**
     * Lets the launcher turn with the device, but only on a screen big enough for it to be an
     * improvement.
     *
     * Android pins a home activity to the device's natural orientation unless it asks for
     * something else. Measured: with Settings in front the display reported ROTATION_90, and
     * the instant this launcher came forward it went back to ROTATION_0 - so the whole of
     * res/layout-land was unreachable rather than untested.
     *
     * On a phone that default is right, and nothing here changes it: a text launcher in
     * landscape on a 6-inch screen is a worse version of itself. At sw600dp - a tablet, an
     * unfolded foldable, a desktop window - a launcher that will not turn with the device is
     * just broken, so there it follows rotation.
     *
     * FULL_USER, not FULL_SENSOR: it obeys the system auto-rotate switch, so someone who has
     * deliberately locked their screen is not overridden by their launcher.
     */
    private fun applyRotationPolicy() {
        // 600dp, not the isTablet() helper in Utils: that measures physical diagonal inches
        // through a deprecated API, and the question here is whether a landscape LAYOUT is
        // worth showing. smallestScreenWidthDp is the same number Android uses to pick
        // sw600dp resources, so the gate and the layouts agree by construction.
        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        requestedOrientation = if (smallestWidthDp >= 600)
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else
            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
    }
}
