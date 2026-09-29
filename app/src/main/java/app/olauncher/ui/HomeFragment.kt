package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AppCategory
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.HomeForeground
import app.olauncher.helper.IconCache
import app.olauncher.helper.MyAccessibilityService
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.hasBeenMinutes
import app.olauncher.helper.notificationAccessGranted
import app.olauncher.helper.notificationListenerComponent
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.showToast
import app.olauncher.helper.tintTextTree
import app.olauncher.helper.Weather
import app.olauncher.helper.InformationPart
import app.olauncher.helper.homeInformationText
import app.olauncher.helper.homeInformationEditor
import app.olauncher.helper.homeAppsEditor
import app.olauncher.helper.LauncherMotion
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showForLauncher
import app.olauncher.helper.withAlpha
import app.olauncher.listener.OnSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private companion object {
        /** Space between the end of an app name and its badge. */
        const val BADGE_GAP_DP = 10

        /** The ACTION_BATTERY_CHANGED fields the information block actually draws from. */
        val BATTERY_SHOWN_EXTRAS = arrayOf(BatteryManager.EXTRA_LEVEL, BatteryManager.EXTRA_SCALE,
            BatteryManager.EXTRA_STATUS, BatteryManager.EXTRA_PLUGGED)

        /** Home app icon edge length, and the gap between it and the name. */
        const val ICON_SIZE_DP = 32
        const val ICON_GAP_DP = 12

        /** Space between the date/time block and the screen time line under it. */
        const val SCREEN_TIME_GAP_DP = 4

        /** Weather is refreshed at most this often; a launcher has no business polling. */

        /**
         * How much system bar the layout's own top margin already accounts for. Matches
         * fragment_home.xml's layout_marginTop on the date block; only the excess is padded.
         */
        const val ABSORBED_TOP_DP = 56
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    /** Guards against two resumes firing the same weather request; see refreshWeather. */
    private var weatherJob: kotlinx.coroutines.Job? = null
    private var homeMenu: androidx.appcompat.app.AlertDialog? = null
    private var weatherFetchInFlight = false
    private var weatherFailure: Weather.Failure? = null
    private var lastWeatherAttempt = 0L
    private var drawerWarmJob: kotlinx.coroutines.Job? = null
    private var drawerWarmKey = ""

    private var latestScreenTime: String = ""
    private var latestUnlockCount: Int = -1
    private var batteryReading: Intent? = null
    private var informationStarted = false
    private var batteryPowerJob: Job? = null
    private var lastBatteryPowerSampleAt = 0L
    private var sampledBatteryWatts: Double? = null
    private var lastInformationColor: Int? = null
    private var informationDialog: androidx.appcompat.app.AlertDialog? = null
    private val informationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (_binding != null) {
            populateDateTime()
            refreshWeather()
            requestInformationUsageAccess()
        }
    }
    private val informationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                // The sticky re-delivery right after registering, and voltage- or temperature-only
                // updates, change nothing Home shows; the watts caption has its own 15 s poll.
                val unchanged = batteryReading?.let { old ->
                    BATTERY_SHOWN_EXTRAS.all { old.getIntExtra(it, -1) == intent.getIntExtra(it, -1) }
                } == true
                batteryReading = intent
                if (unchanged) return
                updateBatteryPowerPolling()
            }
            if (_binding != null) {
                if (intent.action == Intent.ACTION_DATE_CHANGED) prefs.weatherUpdatedAt = 0L
                if (intent.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION || intent.action == Intent.ACTION_DATE_CHANGED) {
                    lastWeatherAttempt = 0L
                    refreshWeather()
                }
                populateDateTime()
            }
        }
    }

    /**
     * Screen time and unlock count share one line, because they are the same thought and the home
     * screen has room for one number in that corner, not two. The unlock count is dropped when it
     * is off, unavailable on this Android version, or genuinely zero.
     */
    private fun renderScreenTimeLine() {
        // The screen-time and unlock observers land a frame or two apart after one query; one
        // posted pass covers both instead of rebuilding the whole block for each.
        _binding?.root?.run { removeCallbacks(informationPass); post(informationPass) }
    }

    private val informationPass = Runnable { if (_binding != null) populateDateTime() }

    /**
     * Refreshes the temperature at most hourly, on a background thread, and only when the user
     * has switched it on and granted a location. The cached reading is what the home screen
     * draws, so the line is never waiting on the network.
     */
    private fun refreshWeather() {
        if (!prefs.showWeather) return
        val context = requireContext().applicationContext
        if (app.olauncher.helper.LauncherMotion.savingPower(context, prefs)) return
        if (!Weather.canLocate(context)) return
        if (prefs.weatherCode >= 0 && Weather.isCurrentDay(prefs.weatherForecastDay, prefs.weatherTimezone) &&
            !prefs.weatherUpdatedAt.hasBeenMinutes(prefs.weatherRefreshMinutes)) return

        // In-flight flag, not the stored timestamp: stamping before the fetch meant one
        // failed request (no signal, airplane mode) blocked the next hour of retries.
        if (weatherFetchInFlight) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (weatherFailure != null && lastWeatherAttempt > 0L && now - lastWeatherAttempt < 60_000) return
        lastWeatherAttempt = now
        weatherFetchInFlight = true
        populateDateTime()
        // The fragment's scope, not the view's: a trip to Apps or Settings destroys Home's view,
        // and cancelling there restarted a stale fetch (location fix, TLS) on every short visit.
        weatherJob = lifecycleScope.launch {
            try {
                val place = prefs.weatherPlaceName
                val result = Weather.fetch(context)
                // A place picked while this was in flight: the reading is for the old location.
                if (prefs.weatherPlaceName != place) return@launch
                weatherFailure = result.failure
                val reading = result.reading ?: return@launch
                if (!prefs.showWeather) return@launch
                val fahrenheit = prefs.weatherFahrenheit
                val description = getString(R.string.weather_spoken_range,
                    Weather.temperature(reading.celsius, fahrenheit), Weather.temperature(reading.high, fahrenheit),
                    Weather.temperature(reading.low, fahrenheit))
                if (!prefs.storeWeatherIfEnabled(Weather.format(reading, fahrenheit), reading.forecastDay,
                        reading.timezone, reading.code, reading.isDay, description, System.currentTimeMillis())) return@launch
                renderScreenTimeLine()
            } finally {
                weatherFetchInFlight = false
                if (_binding != null) populateDateTime()
            }
        }
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post {
            if (isAdded && viewLifecycleOwnerLiveData.value?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true &&
                ViewModelProvider(this)[app.olauncher.helper.HomeAppsDraftState::class.java].rows != null) {
                editHomeApps()
            }
        }
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        if (resources.configuration.screenHeightDp < 480) {
            binding.clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 32f)
            (binding.dateTimeLayout.layoutParams as FrameLayout.LayoutParams).also {
                it.topMargin = 12.dpToPx()
                binding.dateTimeLayout.layoutParams = it
            }
        } else {
            // Past 150% text the clock, already the largest type on Home, stops growing: at 200%
            // it alone took about an app row's height from the list.
            val fontScale = resources.configuration.fontScale
            val growth = if (fontScale > 1.5f) 1.5f / fontScale else 1f
            binding.clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, binding.clock.textSize * .82f * growth)
        }
        binding.homeAppsScroll.scope = viewLifecycleOwner.lifecycleScope
        binding.homeAppsScroll.onLaunch = ::homeAppClicked
        binding.homeAppsScroll.onAppMenu = ::showHomeAppMenu
        binding.homeAppsScroll.onBadge = ::showBadgeDetails
        binding.homeAppsScroll.onAdd = ::addHomeApp
        binding.homeAppsScroll.onHomeMenu = ::showHomeMenu
        binding.homeAppsScroll.onBrowse = { showAppList(Constants.FLAG_LAUNCH_APP, keyboardMode = Constants.KeyboardMode.HIDE) }
        binding.homeAppsScroll.onSearch = { showAppList(Constants.FLAG_LAUNCH_APP, keyboardMode = Constants.KeyboardMode.SHOW) }
        binding.homeAppsScroll.onSwipe = { right -> if (right) openSwipeRightApp() else openSwipeLeftApp() }
        binding.mainLayout.onHorizontalSwipe = { right -> if (right) openSwipeRightApp() else openSwipeLeftApp() }
        val homeCarousel = binding.homeAppsScroll
        binding.mainLayout.onTouchFinished = { homeCarousel.resumeTouchNavigation() }

        applyTopInset()
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initBadgeFollowers()
    }

    /**
     * Pads the home screen down by however much of the system bar the layout cannot
     * already absorb.
     *
     * The window is laid out under the bars on purpose so the wallpaper shows through, and
     * the layout compensates with fixed margins. Measured with the emulator's tall cutout
     * (a 126px / 48dp inset) the clock still clears it by 21px, so this is additive rather
     * than a rewrite: a device where it already looks right gets nothing added, and one
     * with a taller bar than the layout allows for stops drawing underneath it.
     */
    private fun applyTopInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainLayout) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = (bars.top - ABSORBED_TOP_DP.dpToPx()).coerceAtLeast(0), bottom = bars.bottom)
            insets
        }
    }

    /**
     * Keeps each badge glued to the end of its app name. The name's position changes for reasons
     * the badge code does not own - alignment, text size, bold font, a rename, a longer label
     * wrapping to two lines - and every one of them is a layout pass on the name.
     */
    private fun initBadgeFollowers() {
        widgetRenderKey = ""
        binding.dateTimeLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionScreenTime() }
    }

    override fun onResume() {
        super.onResume()
        // No HomeForeground.invalidate() here: a Home press on Home resumes without stopping, and
        // each one paid a getWallpaperColors binder call. HomeForeground now drops its cache when
        // the wallpaper's colours change.
        binding.mainLayout.visibility = View.VISIBLE
        // Load the catalog while Home is visible so Browse can draw its first rows with icons -
        // but after Home's first frame, not before it. The scan is off the main thread, yet on a cold
        // start it still competed with the first frame: a Perfetto trace on the A17 showed six
        // getLauncherActivities and eight getPackageInfo calls in that window, with the main thread
        // spending 78 ms waiting for a CPU. Home's rows come from preferences and need none of it.
        if (prefs.showDrawerIcons && !LauncherMotion.savingPower(requireContext(), prefs) &&
            viewModel.appList.value == null) binding.root.doOnPreDraw { root ->
            root.post { if (isResumed && viewModel.appList.value == null) viewModel.ensureAppList() }
        }
        warmDrawerIcons(viewModel.appList.value)
        syncNotificationListener()
        refreshWeather()
        populateHomeScreen(false)
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onPause() {
        // Drop the old Home media layer before NavHost composites the return transition.
        _binding?.homeVisualizer?.visibility = View.GONE
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        informationStarted = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        batteryReading = ContextCompat.registerReceiver(requireContext(), informationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // No populate here: onResume follows in the same transaction and builds Home. A pass here
        // was a second full build before the Home-return frame.
        // Except when Home is drawn without resuming (a dialog-style activity over it): then it
        // would show whatever it said when Home was last left, so build it first. The replayed
        // LiveData observers skip that case (they only run while resumed), so this covers it.
        binding.root.doOnPreDraw { if (_binding != null && informationStarted && !isResumed) populateHomeScreen(false) }
        updateBatteryPowerPolling()
        // Here rather than onResume: every path that changes the default Home stops Home first,
        // and a Home press on Home (resume without stop) no longer pays a resolveActivity call.
        viewModel.isOlauncherDefault()
    }

    override fun onStop() {
        informationStarted = false
        batteryPowerJob?.cancel()
        batteryPowerJob = null
        // Only when the activity itself stops (an app launch, screen off). A trip to Apps or
        // Settings stops this fragment but not the activity; the fetch finishes in the background.
        if (!requireActivity().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            weatherJob?.cancel()
            if (weatherFetchInFlight) lastWeatherAttempt = 0L
        }
        requireContext().unregisterReceiver(informationReceiver)
        super.onStop()
    }

    private fun requestInformationUsageAccess() {
        if ((prefs.infoShowScreenTime || prefs.showUnlockCount) && !requireContext().appUsagePermissionGranted())
            viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
    }

    private fun editHomeInformation() {
        informationDialog?.dismiss()
        informationDialog = requireContext().homeInformationEditor(prefs) { needsPermission ->
            populateDateTime()
            // A usage widget just switched on has no value yet: scan now (the scan gate sees the
            // new widget set), rather than at the next Home return a minute later.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) populateScreenTime()
            if (needsPermission) informationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            else {
                refreshWeather()
                requestInformationUsageAccess()
            }
        }.also { it.showForLauncher() }
    }

    override fun onClick(view: View) {
        when (view.id) {
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> if (prefs.widgetTapOpens) openCalendarApp() else editHomeInformation()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> view.showPopupMenu(configure = { menu ->
                menu.add(0, 1, 0, R.string.information_edit)
                menu.add(0, 2, 1, R.string.open_calendar)
                menu.add(0, 3, 2, R.string.calendar_app)
                menu.add(0, 4, 3, R.string.screen_time_app)
            }) { item -> when (item.itemId) {
                1 -> editHomeInformation()
                2 -> openCalendarApp()
                3 -> showAppList(Constants.FLAG_SET_CALENDAR_APP)
                4 -> showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
            } }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        // The onboarding hint is optional; on a short display with large system text it
        // overlaps the centered Add app row, making both unreadable.
        val config = resources.configuration
        val roomForTips = config.screenHeightDp >= 700 || config.fontScale < 1.3f
        if (prefs.firstSettingsOpen && roomForTips) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        // These three are plain LiveData, so a new view's observers get the last value replayed
        // at onStart - on every return from Apps, Search, Settings or the panel, once anything
        // set them. onResume runs the same work right after, so only act while resumed.
        viewModel.refreshHome.observe(viewLifecycleOwner) {
            if (isResumed) populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            if (isResumed) setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            if (isResumed) populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let {
                latestScreenTime = it
                renderScreenTimeLine()
            }
        }
        viewModel.unlockCountValue.observe(viewLifecycleOwner) {
            latestUnlockCount = it ?: -1
            renderScreenTimeLine()
        }
        // Push channel: notifications land while the home screen is already in front, so onResume
        // alone would leave the badges stale until the user left and came back.
        NotificationCounts.counts.observe(viewLifecycleOwner) {
            refreshBadges(it)
        }
        viewModel.appList.observe(viewLifecycleOwner) { warmDrawerIcons(it) }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Gesture listener handles taps; HomeSurface's click remains child-driven.
    private fun initSwipeTouchListener() {
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(requireContext()))
    }

    private fun initClickListeners() {
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.homeWeather.setOnClickListener { onWeatherTap() }
        binding.homeWeather.setOnLongClickListener { customizeHomePanel(app.olauncher.helper.PanelSettings.Page.WEATHER); true }
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)
        // The clock announced only the time; say what tap and long press do, as date and weather do.
        labelTap(binding.clock, getString(R.string.home_open_clock))
        ViewCompat.replaceAccessibilityAction(binding.clock,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.home_choose_clock_app)) { target, _ -> target.performLongClick() }
        // A new view: its date and weather actions are labelled on their first render.
        dateTapLabel = null
        weatherTapLabel = null
    }

    /** The ACTION_CLICK labels last applied, so a minute tick does not re-announce them. */
    private var dateTapLabel: String? = null
    private var weatherTapLabel: String? = null

    /** Names what a tap does; the action is the view's own click, so name and behaviour cannot drift. */
    private fun labelTap(view: View, label: String) {
        ViewCompat.replaceAccessibilityAction(view,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            label) { target, _ -> target.performClick() }
    }

    /** Weather without a location grant asks for it; otherwise the editor, as before. */
    private fun onWeatherTap() {
        if (prefs.showWeather && !Weather.canLocate(requireContext()))
            informationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        else editHomeInformation()
    }

    private fun openBatteryUsage() {
        runCatching { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }.onFailure { editHomeInformation() }
    }

    /**
     * What tapping an information widget does, and the name TalkBack gives it. Long press is
     * always the editor. "Open its app" (widgetTapOpens) is off by default, which is the editor.
     */
    private fun informationTap(part: InformationPart?): Pair<Int, () -> Unit> {
        val battery = part?.icon == R.drawable.ic_bolt || part?.icon == R.drawable.ic_battery_outline
        return when {
            part == null -> R.string.information_edit to ::editHomeInformation
            // "Set up" sets up. Only for Unlocks: Screen time's tap belongs to a separate change
            // (opening a chosen screen-time app), so it keeps the editor here.
            part.setup && part.icon == R.drawable.ic_unlock_outline ->
                R.string.home_allow_usage_access to ::requestInformationUsageAccess
            !prefs.widgetTapOpens -> R.string.information_edit to ::editHomeInformation
            battery -> R.string.home_open_battery_usage to ::openBatteryUsage
            // Among the metric widgets the calendar glyph is the next alarm; the date is its own view.
            part.icon == R.drawable.ic_calendar_outline -> R.string.home_open_alarms to ::openClockApp
            else -> R.string.information_edit to ::editHomeInformation
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeAppsScroll.refresh()
    }

    private fun updateBatteryPowerPolling() {
        val charging = batteryReading?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING &&
            (batteryReading?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        // informationStarted, not the view lifecycle: a weather fetch cancelled by onStop runs its
        // finally -> populateDateTime -> here after onStop, and the view scope outlives onStop, so
        // the 15 s loop restarted and ran with Home hidden, reading a battery state nothing updated.
        if (!informationStarted || !prefs.infoShowBattery || !charging || _binding == null ||
            LauncherMotion.savingPower(requireContext(), prefs)) {
            batteryPowerJob?.cancel()
            batteryPowerJob = null
            return
        }
        if (batteryPowerJob?.isActive == true) return
        batteryPowerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                if (_binding != null) populateDateTime()
            }
        }
    }

    private fun sampleBatteryWatts(status: Int, plugged: Int, voltage: Int): Double? {
        if (status != BatteryManager.BATTERY_STATUS_CHARGING || plugged == 0 ||
            LauncherMotion.savingPower(requireContext(), prefs)) {
            lastBatteryPowerSampleAt = 0L
            sampledBatteryWatts = null
            return null
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastBatteryPowerSampleAt == 0L || now - lastBatteryPowerSampleAt >= 15_000L) {
            lastBatteryPowerSampleAt = now
            val current = runCatching {
                (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                    .getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            }.getOrDefault(Long.MIN_VALUE)
            sampledBatteryWatts = app.olauncher.helper.BatteryIndicator.batteryWatts(status, plugged, current, voltage)
        }
        return sampledBatteryWatts
    }

    private fun populateDateTime() {
        updateBatteryPowerPolling()
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        val pattern = Constants.DateFormat.pattern(prefs.dateFormatIndex)
        val parts = buildList {
            if (prefs.infoShowDate) add(InformationPart(R.drawable.ic_calendar_outline,
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date()).replace(".,", ",")))
            if (prefs.infoShowBattery) {
                val battery = batteryReading
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
                val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val charging = app.olauncher.helper.BatteryIndicator.isCharging(status, plugged)
                // Plugged in but held (Samsung battery protection at 85%): it would otherwise look
                // exactly like unplugged, so the name reads "Plugged in" and the detail "Paused".
                val held = app.olauncher.helper.BatteryIndicator.isPaused(status, plugged)
                val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                val watts = sampleBatteryWatts(status, plugged, voltage)
                val wattsValue = watts?.let { String.format(Locale.getDefault(), "%.1f", it) }
                val caption = when {
                    wattsValue != null -> getString(R.string.information_charge_power, wattsValue)
                    held -> getString(R.string.information_paused)
                    else -> null
                }
                val value = if (percent >= 0) "$percent%" else getString(R.string.information_battery_unavailable)
                // The widget's name ("Battery" or "Charging") prefixes this in bind.
                val spoken = when {
                    wattsValue != null -> "$value. ${getString(R.string.information_charge_power_spoken, wattsValue)}"
                    caption != null -> "$value. $caption"
                    else -> value
                }
                add(InformationPart(if (charging) R.drawable.ic_bolt else R.drawable.ic_battery_outline,
                    value, spoken, caption = caption, level = percent, charging = charging, held = held))
            }
            val usageAvailable = !(prefs.infoShowScreenTime || prefs.showUnlockCount) ||
                requireContext().appUsagePermissionGranted()
            // The saver skips the usage query (populateScreenTime), so nothing will ever replace a
            // placeholder: "Loading…" would stay up for as long as the saver does, and a number read
            // before the saver started would pass for a live one. Say it is paused instead.
            val usagePaused = LauncherMotion.savingPower(requireContext(), prefs)
            val paused = getString(R.string.information_paused)
            // Spoken values omit the widget's name: bind prefixes it ("Screen time, 2h 11m").
            val setupSpoken = getString(R.string.home_usage_setup_spoken)
            if (prefs.infoShowScreenTime) {
                if (!usageAvailable) add(InformationPart(R.drawable.ic_usage_outline,
                    getString(R.string.information_setup), setupSpoken, setup = true))
                else if (latestScreenTime.isNotBlank()) add(InformationPart(R.drawable.ic_usage_outline,
                    latestScreenTime,
                    latestScreenTime + if (usagePaused) ". $paused" else "",
                    caption = if (usagePaused) paused else null))
                else add(InformationPart(R.drawable.ic_usage_outline,
                    if (usagePaused) paused else getString(R.string.information_loading)))
            }
            if (prefs.showUnlockCount) {
                if (!usageAvailable) add(InformationPart(R.drawable.ic_unlock_outline,
                    getString(R.string.information_setup), setupSpoken, setup = true))
                else if (latestUnlockCount >= 0) {
                    val unlocks = resources.getQuantityString(R.plurals.unlocks_only, latestUnlockCount, latestUnlockCount)
                    add(InformationPart(R.drawable.ic_unlock_outline, latestUnlockCount.toString(),
                        unlocks + if (usagePaused) ". $paused" else "",
                        caption = if (usagePaused) paused else null))
                }
                else add(InformationPart(R.drawable.ic_unlock_outline,
                    if (usagePaused) paused else getString(R.string.information_loading)))
            }
        }
        val color = HomeForeground.color(requireContext(), prefs)
        val dateParts = if (prefs.infoShowDate) parts.take(1) else emptyList()
        val description = dateParts.joinToString(". ") { it.spoken }
        if (binding.date.contentDescription?.toString() != description || lastInformationColor != color) {
            binding.date.text = homeInformationText(requireContext(), dateParts, color, (binding.date.textSize * .9f).toInt())
            binding.date.contentDescription = description
            lastInformationColor = color
        }
        binding.date.gravity = prefs.homeAlignment or Gravity.CENTER_VERTICAL
        binding.date.isVisible = dateParts.isNotEmpty()
        val metrics = parts.drop(dateParts.size).toMutableList()
        if (prefs.infoShowAlarm) {
            val alarm = (requireContext().getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).nextAlarmClock
            val value = alarm?.let { android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),
                if (android.text.format.DateFormat.is24HourFormat(requireContext())) "EEEHm" else "EEEhm"), it.triggerTime).toString() }
                ?: getString(R.string.information_no_alarm)
            metrics.add(InformationPart(R.drawable.ic_calendar_outline, value))
        }
        val dateTap = getString(if (prefs.widgetTapOpens) R.string.open_calendar else R.string.information_edit)
        if (dateTapLabel != dateTap) { dateTapLabel = dateTap; labelTap(binding.date, dateTap) }
        renderInformationWidgets(metrics, color)
        binding.dateTimeLayout.isVisible = binding.clock.isVisible || dateParts.isNotEmpty() || binding.homeWidgets.isVisible || binding.homeVisualizer.isVisible
        binding.tvScreenTime.isVisible = false
        positionScreenTime()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (!prefs.infoShowScreenTime && !prefs.showUnlockCount) return
        if (requireContext().appUsagePermissionGranted().not()) return

        if (!LauncherMotion.savingPower(requireContext(), prefs)) viewModel.getTodaysScreenTime()
        // Screen time is drawn by the information widgets now. This used to rebuild LayoutParams
        // for the old, always-hidden tvScreenTime line on every resume, and setLayoutParams
        // requests a layout of the whole Home tree even for a GONE view.
    }

    /**
     * Drops the screen time line below the clock instead of guessing a top margin.
     *
     * The hardcoded margins above clear a short "2h 11m" and nothing longer: adding the unlock
     * count made the line wide enough to run straight through a centre-aligned clock. Measuring
     * where the date/time block actually ends is robust to any string, font or text size, none of
     * which a fixed margin can know about.
     */
    private fun positionScreenTime() {
        val region = binding.homeAppsRegion.layoutParams as FrameLayout.LayoutParams
        // At large system text sizes the first carousel row can enter as a clipped edge
        // immediately under the last widget label. Scale this gap with text, not the whole
        // Home layout: regular-density A17 spacing stays exactly as the user arranged it.
        val widgetGap = if (resources.configuration.fontScale >= 1.5f) 24.dpToPx() else 12.dpToPx()
        val desired = if (binding.dateTimeLayout.isVisible) binding.dateTimeLayout.bottom + widgetGap else 24.dpToPx()
        if (region.topMargin != desired) { region.topMargin = desired; binding.homeAppsRegion.layoutParams = region }
        val list = binding.homeAppsScroll.layoutParams as FrameLayout.LayoutParams
        val gravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        if (list.gravity != gravity) { list.gravity = gravity; binding.homeAppsScroll.layoutParams = list }
    }

    private var widgetRenderKey = ""

    private fun renderInformationWidgets(parts: List<InformationPart>, color: Int) {
        val grid = binding.homeWidgets
        grid.size = prefs.informationSize
        grid.weatherSize = prefs.weatherSize
        grid.alignment = prefs.homeAlignment
        val textSp = 12f + prefs.informationSize * 2f
        val room = if (prefs.showWeather) 3 else 4
        val shown = parts.take(room)
        // Value updates preserve the actual nodes and accessibility/keyboard focus.
        val identities = shown.map { if (it.icon == R.drawable.ic_bolt) R.drawable.ic_battery_outline else it.icon }
        val key = "${prefs.informationSize}|${prefs.homeAlignment}|$color|${prefs.showWeather}|${prefs.weatherSize}|$identities"
        if (widgetRenderKey != key) {
            widgetRenderKey = key
            while (grid.childCount > 1) grid.removeViewAt(1)
            binding.weatherCurrent.textSize = textSp * 1.25f
            binding.weatherCondition.textSize = textSp * 1.2f
            binding.weatherHigh.textSize = textSp * 1.1f
            binding.weatherLow.textSize = textSp * 1.1f
            binding.homeWeather.contentDescription = null
            shown.forEach { _ ->
                grid.addView(HomeInformationWidgetView(requireContext()).apply {
                    // Read at tap time: the view is kept while its value and state change.
                    setOnClickListener { informationTap(part).second() }
                    setOnLongClickListener { editHomeInformation(); true }
                    applyFocusOutline(color)
                })
            }
        }
        val weight = valueWeight()
        shown.forEachIndexed { index, part ->
            val view = grid.getChildAt(index + 1) as HomeInformationWidgetView
            val label = when (part.icon) {
                // The glyph names the widget, so the second line carries the state.
                R.drawable.ic_bolt, R.drawable.ic_battery_outline -> getString(when {
                    part.charging -> R.string.home_battery_charging
                    part.held -> R.string.home_battery_plugged
                    else -> R.string.information_battery_short
                })
                R.drawable.ic_usage_outline -> getString(R.string.information_screen_time)
                R.drawable.ic_unlock_outline -> getString(R.string.information_unlocks)
                else -> getString(R.string.information_alarm)
            }
            view.bind(part, label, color, textSp, weight)
            val tap = getString(informationTap(part).first)
            if (view.tapLabel != tap) { view.tapLabel = tap; labelTap(view, tap) }
        }
        renderWeather(color)
        grid.isVisible = prefs.showWeather || parts.isNotEmpty()
        binding.homeWidgetContainer.isVisible = grid.isVisible
        binding.homeWidgetContainer.tintScrollbar(color)
    }

    private fun renderWeather(color: Int) {
        val view = binding.homeWeather
        view.isVisible = prefs.showWeather
        if (!prefs.showWeather) return
        val needsPermission = !Weather.canLocate(requireContext())
        val name = getString(R.string.weather)
        // (headline, spoken). Line 1 holds a value or a short status and line 2 the name, the same
        // order as every other widget; the spoken name carries both words the screen shows.
        val status: Pair<String, String>? = when {
            needsPermission -> getString(R.string.information_setup) to getString(R.string.weather_setup)
            !Weather.hasPlace(requireContext()) && !Weather.locationEnabled(requireContext()) -> getString(R.string.weather_location_off).let { it to "$name, $it" }
            prefs.weatherCached.isNotBlank() -> null
            weatherFetchInFlight -> getString(R.string.information_loading) to getString(R.string.weather_loading)
            LauncherMotion.savingPower(requireContext(), prefs) -> getString(R.string.information_paused) to getString(R.string.weather_paused)
            weatherFailure == Weather.Failure.NO_LOCATION -> getString(R.string.home_weather_no_location).let { it to "$name, $it" }
            weatherFailure == Weather.Failure.NETWORK -> getString(R.string.home_weather_offline) to getString(R.string.weather_network_error)
            else -> getString(R.string.home_weather_unavailable) to getString(R.string.weather_unavailable)
        }
        val tap = getString(if (needsPermission) R.string.home_allow_location else R.string.information_edit)
        if (weatherTapLabel != tap) { weatherTapLabel = tap; labelTap(view, tap) }
        val condition = getString(Weather.conditionLabel(prefs.weatherCode))
        val currentDay = Weather.isCurrentDay(prefs.weatherForecastDay, prefs.weatherTimezone)
        val summary = if (currentDay) prefs.weatherDescription.ifBlank { prefs.weatherCached } else prefs.weatherCached.substringBefore("  H:")
        val spoken = status?.second ?: "$condition. $summary"
        val current = binding.weatherCurrent
        val conditionView = binding.weatherCondition
        val high = binding.weatherHigh
        val low = binding.weatherLow
        val iconView = binding.weatherIcon
        val iconResource = if (status == null) Weather.icon(prefs.weatherCode, prefs.weatherIsDay) else null
        val iconSize = (conditionView.textSize * 1.2f).toInt()
        val iconState = Triple(iconResource, color, iconSize)
        if (view.contentDescription?.toString() != spoken || current.currentTextColor != color ||
            iconView.tag != iconState) {
            iconView.isVisible = status == null
            if (iconResource != null) {
                iconView.layoutParams = iconView.layoutParams.apply {
                    width = iconSize
                    height = iconSize
                }
                iconView.setImageDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    requireContext(), iconResource)?.mutate()?.apply {
                    setTint(color)
                })
            }
            iconView.tag = iconState
            current.text = status?.first ?: prefs.weatherCached.substringBefore("  H:")
            conditionView.text = if (status == null) condition else name
            val range = if (status == null && currentDay)
                prefs.weatherCached.substringAfter("  H:", "") else ""
            high.text = range.substringBefore("  L:").trim().takeIf { range.isNotBlank() }?.let { "H:$it" }.orEmpty()
            low.text = range.substringAfter("  L:", "").trim().takeIf { it.isNotBlank() }?.let { "L:$it" }.orEmpty()
            view.contentDescription = android.text.SpannableString(spoken).apply {
                setSpan(android.text.style.LocaleSpan(Locale.ENGLISH), 0, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Value full strength and a weight step up; everything under it at 70%.
        current.setTextColor(color)
        current.applyTextWeight(valueWeight())
        conditionView.setTextColor(color.withAlpha(0xB3))
        high.setTextColor(color.withAlpha(0xB3))
        low.setTextColor(color.withAlpha(0xB3))
        high.isVisible = high.text.isNotBlank()
        low.isVisible = low.text.isNotBlank()
        view.applyFocusOutline(color)
    }

    /**
     * Repaints Home's own surface and text: pure black under the Ultra saver, the colour theme's
     * background and text for a custom theme, and on the System theme a 70% surface that is dark
     * in night mode and, by day, follows the wallpaper (HomeForeground). Then puts back the
     * information widgets' hierarchy, which the whole-tree tint and weight pass flattens.
     */
    private fun applyColorTheme() {
        paintColorTheme()
        restyleInformation()
    }

    /** Widget values sit one weight step above their names: hierarchy by weight first. */
    private fun valueWeight() = (Constants.TextWeight.value(prefs.textWeight) + 100).coerceAtMost(700)

    /**
     * tintTextTree gives every Home TextView the full-strength colour and applyTextWeight one
     * weight, which left the weather H:/L: line, the battery caption and "Paused" at full strength
     * until the next minute tick. Re-apply the muted lines and the value weight after it.
     */
    private fun restyleInformation() {
        val color = HomeForeground.color(requireContext(), prefs)
        val muted = color.withAlpha(0xB3)
        val weight = valueWeight()
        binding.weatherCurrent.applyTextWeight(weight)
        binding.weatherCondition.setTextColor(muted)
        binding.weatherHigh.setTextColor(muted)
        binding.weatherLow.setTextColor(muted)
        for (i in 0 until binding.homeWidgets.childCount)
            (binding.homeWidgets.getChildAt(i) as? HomeInformationWidgetView)?.restyle(color, weight)
    }

    private fun paintColorTheme() {
        applyFocusOutlines()
        binding.mainLayout.applyTextWeight(Constants.TextWeight.value(prefs.textWeight))
        if (LauncherMotion.savingPower(requireContext(), prefs)) {
            // Pure black under the Ultra battery saver: on this AMOLED panel a black pixel is a pixel
            // switched off. Derived here, never written to the theme setting, so the user's own theme
            // comes straight back when the saver ends. MainActivity drops the wallpaper layer too.
            binding.mainLayout.setBackgroundColor(android.graphics.Color.BLACK)
            binding.mainLayout.tintTextTree(android.graphics.Color.WHITE,
                android.graphics.Color.WHITE.withAlpha(0xB3))
            applySystemBarIcons(false)
            return
        }
        if (!ColorTheme.isCustom(prefs.colorThemeId)) {
            // White in night mode; by day it follows the wallpaper.
            val foreground = HomeForeground.color(requireContext(), prefs)
            // A wallpaper can contain both bright and dark regions, and Android's color hint can
            // lag a dimming change. A quiet surface guarantees readable Home text in either theme.
            val surface = if (foreground == android.graphics.Color.BLACK) 0xB3FFFFFF.toInt()
                else 0xB3000000.toInt()
            binding.mainLayout.setBackgroundColor(surface)
            binding.mainLayout.tintTextTree(foreground, foreground.withAlpha(0xB3))
            applySystemBarIcons(foreground == android.graphics.Color.BLACK)
            return
        }
        val theme = ColorTheme.byId(prefs.colorThemeId)
        // The launcher paints its own background rather than trusting the wallpaper to match.
        // Relying on the wallpaper meant the two could drift apart - a wallpaper changed from
        // anywhere else, or the daily wallpaper worker running - and dark theme text on whatever
        // was behind it is unreadable, which is exactly what Cream looked like when it happened.
        binding.mainLayout.setBackgroundColor(theme.background)
        binding.mainLayout.tintTextTree(theme.text, theme.text.withAlpha(0xB3))
        applySystemBarIcons(ColorUtils.calculateLuminance(theme.background) > 0.5)
    }

    /**
     * The status and navigation bar icons are drawn light or dark by the APP theme, but the
     * background behind them now comes from the COLOUR theme. Pick Cream while the app theme
     * is dark and you get white icons on a cream bar - invisible. Derive them from what is
     * actually painted instead.
     */
    private fun applySystemBarIcons(lightBackground: Boolean) {
        val window = activity?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }

    /**
     * Applies the home layout options: whether visibility changes animate, and how much air each
     * row gets. Spacing is added on top of the density default rather than replacing it, so a
     * setting of zero still looks right on every screen size.
     */
    private fun applyHomeLayoutOptions() {
        binding.mainLayout.layoutTransition = null
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        binding.homeVisualizer.homeSurface = true
        binding.homeVisualizer.onConfigure = { customizeHomePanel(app.olauncher.helper.PanelSettings.Page.VISUALIZER) }
        binding.homeVisualizer.refresh()
        applyHomeLayoutOptions()
        populateHomeRows(appCountUpdated)
        applyColorTheme()
        // Must run after the rows, and outside populateHomeRows: that function returns early at
        // every one of the eight app-count checks, so anything appended to its body would be
        // skipped for all but a full eight-app home screen.
        refreshBadges()
    }

    private fun populateHomeRows(appCountUpdated: Boolean) {
        populateDateTime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) populateScreenTime()
        binding.homeAppsScroll.refresh()
    }

/**
     * Android's default focus highlight is #292929, which is 1.44:1 on a black launcher -
     * well under the 3:1 a focus indicator owes, and the text itself does not change colour
     * when focused either, so d-pad and switch-access users had no cue at all. The ring is
     * drawn in the text colour, which the palette already guarantees at 9.9:1 or better.
     */
    private fun applyFocusOutlines() {
        val color = HomeForeground.color(requireContext(), prefs)
        binding.clock.applyFocusOutline(color)
        binding.date.applyFocusOutline(color)
    }


    /**
     * The identity a notification is matched against. A blank stored user means the slot was saved
     * before per-profile home apps existed, and those are always the personal profile, which is
     * what StatusBarNotification.user stringifies to for a normal app.
     */
    private fun badgeKeyFor(location: Int): String = NotificationCounts.key(
        prefs.getAppPackage(location),
        prefs.getAppUser(location).ifBlank { Process.myUserHandle().toString() }
    )

    private fun refreshBadges(counts: Map<String, Int> = NotificationCounts.counts.value.orEmpty()) {
        binding.homeAppsScroll.updateBadges(counts)
    }

    /**
     * Tapping a badge says what was missed. Deliberately a small peek and not a notification
     * panel: it shows the few most recent lines, newest first, and cannot act on them.
     */
    private fun showBadgeDetails(location: Int) {
        if (!prefs.badgeTapShowsDetails) {
            homeAppClicked(location)
            return
        }
        val lines = NotificationCounts.linesFor(badgeKeyFor(location))
        val appName = prefs.getAppName(location)
        val body = if (lines.isEmpty()) getString(R.string.no_notification_details)
        else lines.asReversed().joinToString("\n\n")

        requireContext().createDialog(
            title = R.string.notification_badges,
            action = R.string.close,
            content = { container ->
                TextView(container.context, null, 0, R.style.TextSmall).apply {
                    text = if (lines.isEmpty()) body else "$appName\n\n$body"
                    setTextIsSelectable(false)
                }
            }
        ).showRespectingStatusBar()
    }


    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        // MainViewModel clears the badge only after Android accepts the launch. Home, Apps,
        // Search and shortcuts all use that same success path; a missing app keeps its badge.
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    /**
     * Keeps the badge state honest across the things Android does behind the app's back: access
     * revoked in Settings without onListenerDisconnected ever firing, and the binding dropped
     * after an app update. Pressing Home is the user's most frequent action, so it is also the
     * cheapest place to recover. Rebinding only when disconnected matters because Android 12+
     * rate-limits repeated requestRebind calls.
     */
    private fun syncNotificationListener() {
        val context = requireContext()
        if (!prefs.showNotificationBadges || !context.notificationAccessGranted()) {
            NotificationCounts.clear()
            return
        }
        if (!NotificationCounts.connected) runCatching {
            NotificationListenerService.requestRebind(context.notificationListenerComponent())
        }
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    // Swipe left and right are ordinary gestures now, same eight actions as the rest. The
    // fallbacks keep Olauncher's out-of-the-box behaviour for a fresh install, where the
    // gesture defaults to Launch app with nothing chosen yet.
    private fun openSwipeRightApp() =
        runGesture(Constants.Gesture.SWIPE_RIGHT, Constants.GestureAction.LAUNCH_APP) {
            openDialerApp(requireContext())
        }

    private fun openSwipeLeftApp() =
        runGesture(Constants.Gesture.SWIPE_LEFT, Constants.GestureAction.LAUNCH_APP) {
            openCameraApp(requireContext())
        }

    private fun showAppList(
        flag: Int,
        rename: Boolean = false,
        includeHiddenApps: Boolean = false,
        keyboardMode: Int = Constants.KeyboardMode.AUTO,
    ) {
        viewModel.ensureAppList(includeHiddenApps)
        val args = bundleOf(
            Constants.Key.FLAG to flag,
            Constants.Key.RENAME to rename,
            Constants.Key.KEYBOARD_MODE to keyboardMode
        )
        // Home sits below a translucent drawer; hide its text before the drawer fades in.
        binding.mainLayout.visibility = View.INVISIBLE
        try {
            findNavController().navigate(R.id.action_mainFragment_to_appListFragment, args)
        } catch (e: Exception) {
            findNavController().navigate(R.id.appListFragment, args)
            e.printStackTrace()
        }
    }

    private fun warmDrawerIcons(apps: List<AppModel>?) {
        if (apps.isNullOrEmpty() || !prefs.showDrawerIcons ||
            LauncherMotion.savingPower(requireContext(), prefs)) return
        val context = requireContext().applicationContext
        val size = prefs.appIconSize.dpToPx()
        val capacity = IconCache.warmCapacity(size)
        val entries = AppCategory.iconWarmOrder(apps, prefs.drawerSort).take(capacity).map {
            Triple(it.appPackage, it.activityClassName.orEmpty(), it.user)
        }
        val grayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
        val key = "${prefs.drawerSort}|$size|$grayscale|${IconCache.iconPackPackage}|$entries"
        if (drawerWarmKey == key && drawerWarmJob?.isActive == true) return
        drawerWarmJob?.cancel()
        drawerWarmKey = key
        // The fragment survives navigation; keep the bounded warmup alive as Apps opens.
        drawerWarmJob = lifecycleScope.launch(Dispatchers.IO) {
            IconCache.warm(context, entries, size, grayscale, limit = capacity)
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Opens the notification panel.
     *
     * This gesture used to raise a dialog of missed counts for home apps only. The panel shows
     * the whole shade, grouped by app, and can act on what is in it - so the dialog had nothing
     * left that the panel does not do better.
     */
    private fun openNotificationPanel() {
        if (!requireContext().notificationAccessGranted()) {
            requireContext().showToast(getString(R.string.notification_access_needed))
        }
        try {
            findNavController().navigate(R.id.action_mainFragment_to_notificationPanelFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Runs whatever the user bound to a gesture. Every home gesture routes through here, so the
     * set of possible actions lives in exactly one place.
     */
    private fun runGesture(gesture: String, defaultAction: Int, fallback: (() -> Unit)? = null) {
        when (prefs.getGestureAction(gesture, defaultAction)) {
            Constants.GestureAction.NOTHING -> Unit
            // Browse the list with the keyboard out of the way...
            Constants.GestureAction.APP_LIST -> showAppList(
                Constants.FLAG_LAUNCH_APP,
                keyboardMode = Constants.KeyboardMode.HIDE
            )
            // ...versus land in it ready to type. These were the same call until now, which made
            // two of the gesture options indistinguishable.
            Constants.GestureAction.APP_SEARCH -> showAppList(
                Constants.FLAG_LAUNCH_APP,
                keyboardMode = Constants.KeyboardMode.SHOW
            )
            Constants.GestureAction.NOTIFICATION_SHADE -> expandNotificationDrawer(requireContext())
            Constants.GestureAction.LAUNCHER_SETTINGS -> openLauncherSettings()
            Constants.GestureAction.LOCK_SCREEN -> lockPhoneByGesture()
            Constants.GestureAction.MISSED_NOTIFICATIONS -> openNotificationPanel()
            Constants.GestureAction.LAUNCH_APP -> {
                val packageName = prefs.getGestureAppPackage(gesture)
                if (packageName.isEmpty()) {
                    if (fallback != null) fallback()
                    else requireContext().showToast(getString(R.string.no_app_selected_for_gesture))
                    return
                }
                launchAppOrShortcut(
                    appName = prefs.getGestureAppName(gesture),
                    packageName = packageName,
                    activityClassName = prefs.getGestureAppClassName(gesture),
                    shortcutId = prefs.getGestureShortcutId(gesture),
                    isShortcut = prefs.getGestureIsShortcut(gesture),
                    userString = prefs.getGestureAppUser(gesture),
                    fallback = fallback
                )
            }
        }
    }

    private fun openLauncherSettings() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            viewModel.firstOpen(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun lockPhoneByGesture() {
        if (MyAccessibilityService.lockScreen()) return
        // Android 7-8 cannot lock through the service; the option is hidden there, so stay silent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        requireContext().showToast(getString(R.string.gesture_needs_service), Toast.LENGTH_LONG)
        // Straight to the gesture rows (they live on Motion and power), where the disclosure is.
        findNavController().navigate(R.id.action_mainFragment_to_settingsFragment,
            androidx.core.os.bundleOf(Constants.Key.SECTION to Constants.Section.GESTURES))
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                runGesture(Constants.Gesture.SWIPE_UP, Constants.GestureAction.APP_LIST)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                runGesture(Constants.Gesture.SWIPE_DOWN, Constants.GestureAction.NOTIFICATION_SHADE)
            }

            override fun onLongClick() {
                super.onLongClick()
                if (prefs.getGestureAction(Constants.Gesture.LONG_PRESS, Constants.GestureAction.LAUNCHER_SETTINGS) ==
                    Constants.GestureAction.LAUNCHER_SETTINGS) showHomeMenu()
                else runGesture(Constants.Gesture.LONG_PRESS, Constants.GestureAction.LAUNCHER_SETTINGS)
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                // Lock is the historical default and still needs the accessibility grant; any
                // other bound action is the user's explicit choice and does not.
                val action = prefs.getGestureAction(
                    Constants.Gesture.DOUBLE_TAP,
                    Constants.GestureAction.LOCK_SCREEN
                )
                if (action == Constants.GestureAction.LOCK_SCREEN && !prefs.lockModeOn) return
                runGesture(Constants.Gesture.DOUBLE_TAP, Constants.GestureAction.LOCK_SCREEN)
            }

        }
    }

    /**
     * Same gestures as an app row, except a tap peeks at what was missed instead of launching.
     * Long press still opens the app picker for that slot, so the badge never becomes a dead zone
     * over the row's own behaviour.
     */


    private fun addHomeApp() {
        if (prefs.homeAppEntries().size < 512)
            showAppList(Constants.FLAG_HOME_ADD_AUTO, includeHiddenApps = true)
    }

    private fun customizeHomePanel(page: app.olauncher.helper.PanelSettings.Page = app.olauncher.helper.PanelSettings.Page.HOME) {
        customizePanel(page) { widgetRenderKey = ""; populateHomeScreen(false); refreshWeather() }
    }
    private fun editHomeApps() {
        homeMenu?.dismiss()
        homeMenu = requireContext().homeAppsEditor(prefs, viewLifecycleOwner.lifecycleScope, this) {
            populateHomeScreen(true)
        }.also { it.showForLauncher() }
    }

    private fun openHomeSettings() {
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_settingsFragment,
                bundleOf(Constants.Key.SECTION to Constants.Section.HOME)
            )
            viewModel.firstOpen(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The Home edit surface uses the same card, typography, and row rhythm as Settings. One card
     * (the dialog's own Settings card), sized to its rows; a tap anywhere outside it closes it.
     */
    private fun showHomeEditMenu(title: String, actions: List<Pair<Int, () -> Unit>>) {
        homeMenu?.dismiss()
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 16.dpToPx(), 12.dpToPx(), 16.dpToPx())
        }
        card.addView(TextView(context, null, 0, R.style.TextLarge).apply {
            text = title
            minHeight = 48.dpToPx()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val selectable = android.util.TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        actions.forEachIndexed { index, (label, action) ->
            if (index > 0) card.addView(View(context).apply {
                setBackgroundColor(context.getColorFromAttr(R.attr.primaryColor).withAlpha(36))
            }, LinearLayout.LayoutParams(-1, 1.dpToPx()).apply { marginStart = 8.dpToPx(); marginEnd = 8.dpToPx() })
            card.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                setText(label)
                minHeight = 48.dpToPx()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                if (selectable != 0) setBackgroundResource(selectable)
                isFocusable = true
                applyFocusOutline(context.getColorFromAttr(R.attr.primaryColor))
                setOnClickListener { homeMenu?.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // At the Text weight, like the Settings pages and editors these rows open.
        card.applyTextWeight(Constants.TextWeight.value(prefs.textWeight))
        val scroll = ScrollView(context).apply {
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        val homeSurface = binding.mainLayout
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context).setView(scroll).create()
        dialog.setOnDismissListener {
            homeSurface.alpha = 1f
            if (homeMenu === dialog) homeMenu = null
        }
        homeMenu = dialog
        // showForLauncher paints the opaque Settings page colour behind the card. The translucent
        // shade this used to set let a light wallpaper through as a grey frame in dark mode.
        dialog.showForLauncher()
        // The window fills the screen, so Android's own outside-touch cancel never fires. The card
        // wraps its rows and consumes its own taps; a tap on the page around it closes the menu.
        // The page is not an accessibility node of its own: Back closes the menu for TalkBack.
        dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel)?.apply {
            isClickable = true
            // Swallowing taps is all it does; TalkBack must not offer it as a control. Its rows still are.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dialog.findViewById<View>(android.R.id.content)?.apply {
            setOnClickListener { dialog.dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        homeSurface.alpha = 0f
    }

    private fun showHomeAppMenu(slot: Int) {
        showHomeEditMenu(prefs.getAppName(slot), listOf(
            R.string.home_replace_app to { showAppList(Constants.FLAG_HOME_SLOT_BASE + slot, includeHiddenApps = true) },
            R.string.home_remove_app to { prefs.removeHomeApp(slot); populateHomeScreen(true) },
            R.string.home_bulk_title to { editHomeApps() }
        ))
    }

    private fun showHomeMenu() {
        showHomeEditMenu(getString(R.string.home_screen), listOf(
            R.string.home_bulk_title to { editHomeApps() },
            R.string.home_information to { editHomeInformation() },
            R.string.home_settings to { openHomeSettings() },
            R.string.all_settings to { openLauncherSettings() }
        ))
    }

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? {
        if (prefs.homeScrollStyle == 0 || LauncherMotion.savingPower(requireContext(), prefs))
            return android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(0)
        return super.onCreateAnimator(transit, enter, nextAnim)
    }

    /**
     * MainActivity declares configChanges="uiMode", so a night-mode switch arrives here instead
     * of as a recreate. Home's colours follow it (HomeForeground is white at night).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        HomeForeground.invalidate()
        if (_binding != null && isResumed) populateHomeScreen(false)
    }

    override fun onPowerStateChanged() {
        super.onPowerStateChanged()
        val saving = LauncherMotion.savingPower(requireContext(), prefs)
        if (saving) {
            drawerWarmJob?.cancel()
            drawerWarmJob = null
            drawerWarmKey = ""
        } else warmDrawerIcons(viewModel.appList.value)
        if (_binding == null) return
        // Before the rows: bind reads HomeForeground, which is white under the saver.
        applyColorTheme()
        binding.homeAppsScroll.refresh()
        populateDateTime()
        refreshWeather()
        // Screen time is skipped while saving. When the saver ends, fetch it now rather than
        // leaving "Paused" up until the next time Home happens to resume.
        if (!saving && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) populateScreenTime()
    }

    override fun suppressMotion() {
        super.suppressMotion()
        weatherJob?.cancel()
        if (weatherFetchInFlight) lastWeatherAttempt = 0L
        _binding?.homeAppsScroll?.suppressMotion()
    }

    override fun onDestroyView() {
        homeMenu?.dismiss()
        homeMenu = null
        informationDialog?.dismiss()
        informationDialog = null
        super.onDestroyView()
        _binding = null
    }
}
