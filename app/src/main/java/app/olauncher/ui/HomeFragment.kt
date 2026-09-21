package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import app.olauncher.helper.isDarkThemeOn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
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
import app.olauncher.helper.isPackageInstalled
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
import app.olauncher.helper.withAlpha
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private companion object {
        /** Space between the end of an app name and its badge. */
        const val BADGE_GAP_DP = 10

        /** Home app icon edge length, and the gap between it and the name. */
        const val ICON_SIZE_DP = 32
        const val ICON_GAP_DP = 12

        /** Space between the date/time block and the screen time line under it. */
        const val SCREEN_TIME_GAP_DP = 4

        /** Weather is refreshed at most this often; a launcher has no business polling. */
        const val WEATHER_REFRESH_MINUTES = 60

        /**
         * How much system bar the layout's own top margin already accounts for. Matches
         * fragment_home.xml's layout_marginTop on the date block; only the excess is padded.
         */
        const val ABSORBED_TOP_DP = 56
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    /**
     * The XML padding for a home row at this screen density, captured before any spacing setting
     * is applied. Read from the layout rather than hard-coded, because it differs per density
     * bucket, and re-reading it after we have changed it would let the setting compound.
     */
    private var basePaddingPx = 0

    /** Guards against two resumes firing the same weather request; see refreshWeather. */
    private var weatherFetchInFlight = false

    private var latestScreenTime: String = ""
    private var latestUnlockCount: Int = -1

    /**
     * Screen time and unlock count share one line, because they are the same thought and the home
     * screen has room for one number in that corner, not two. The unlock count is dropped when it
     * is off, unavailable on this Android version, or genuinely zero.
     */
    private fun renderScreenTimeLine() {
        val unlocks = latestUnlockCount
        val base = when {
            !prefs.showUnlockCount || unlocks <= 0 -> latestScreenTime
            latestScreenTime.isEmpty() ->
                resources.getQuantityString(R.plurals.unlocks_only, unlocks, unlocks)

            else -> resources.getQuantityString(
                R.plurals.screen_time_and_unlocks, unlocks, unlocks, latestScreenTime
            )
        }

        val weather = if (prefs.showWeather) prefs.weatherCached else ""
        binding.tvScreenTime.text = when {
            weather.isEmpty() -> base
            base.isEmpty() -> weather
            else -> getString(R.string.line_with_weather, base, weather)
        }
        // Spoken as "3h 31m" with no idea what the number is, otherwise.
        binding.tvScreenTime.contentDescription =
            getString(R.string.a11y_screen_time, binding.tvScreenTime.text)
        binding.tvScreenTime.isVisible = binding.tvScreenTime.text.isNotEmpty()
    }

    /**
     * Refreshes the temperature at most hourly, on a background thread, and only when the user
     * has switched it on and granted a location. The cached reading is what the home screen
     * draws, so the line is never waiting on the network.
     */
    private fun refreshWeather() {
        if (!prefs.showWeather) return
        val context = requireContext().applicationContext
        if (!Weather.hasLocationPermission(context)) return
        if (!prefs.weatherUpdatedAt.hasBeenMinutes(WEATHER_REFRESH_MINUTES)) return

        // In-flight flag, not the stored timestamp: stamping before the fetch meant one
        // failed request (no signal, airplane mode) blocked the next hour of retries.
        if (weatherFetchInFlight) return
        weatherFetchInFlight = true
        val fahrenheit = prefs.weatherFahrenheit
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reading = withContext(Dispatchers.IO) { Weather.fetch(context) }
                    ?: return@launch
                prefs.weatherUpdatedAt = System.currentTimeMillis()
                prefs.weatherCached = Weather.format(reading, fahrenheit)
                renderScreenTimeLine()
            } finally {
                weatherFetchInFlight = false
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
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        basePaddingPx = binding.homeApp1.paddingTop

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
            view.updatePadding(top = (bars.top - ABSORBED_TOP_DP.dpToPx()).coerceAtLeast(0))
            insets
        }
    }

    /**
     * Keeps each badge glued to the end of its app name. The name's position changes for reasons
     * the badge code does not own - alignment, text size, bold font, a rename, a longer label
     * wrapping to two lines - and every one of them is a layout pass on the name.
     */
    private fun initBadgeFollowers() {
        // The date/time block changes height with the date format, font and text size, so the
        // screen time line is repositioned whenever it settles rather than once at startup.
        binding.dateTimeLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionScreenTime()
        }

        val badges = homeAppBadgeViews()
        homeAppNameViews().forEachIndexed { index, name ->
            val badge = badges[index]
            name.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                positionBadge(name, badge)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncNotificationListener()
        refreshWeather()
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
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
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

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
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
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
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
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
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        // Badges get the same per-row swipe listener as their app name, anchored to the NAME view,
        // so a swipe that happens to start on the badge behaves identically to one on the label.
        val names = homeAppNameViews()
        homeAppBadgeViews().forEachIndexed { index, badge ->
            // The badge needs its OWN listener, not the name's. ViewSwipeTouchListener dispatches
            // onClick with the view it was constructed against, so reusing the name's listener
            // made tapping a badge launch the app instead of showing what was missed.
            badge.setOnTouchListener(getBadgeSwipeTouchListener(context, badge, names[index], index + 1))
        }
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        // Badges need the same treatment as the names above: the touch listener is consumed
        // by ViewSwipeTouchListener and never sets isClickable, so without these the badge
        // exposes no ACTION_CLICK and the peek is reachable by finger only.
        homeAppBadgeViews().forEachIndexed { index, badge ->
            badge.setOnClickListener { showBadgeDetails(index + 1) }
            badge.setOnLongClickListener { onLongClick(homeAppNameViews()[index]) }
        }
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        // The name carries the alignment itself, so it lands where it would with no badge at all.
        // positionBadge then follows it; the badge never influences where the name sits.
        val names = homeAppNameViews()
        val badges = homeAppBadgeViews()
        names.forEachIndexed { index, name ->
            (name.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = horizontalGravity or Gravity.CENTER_VERTICAL
                name.layoutParams = params
            }
            positionBadge(name, badges[index])
        }
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val pattern = Constants.DateFormat.PATTERNS.getOrElse(prefs.dateFormatIndex) {
            Constants.DateFormat.PATTERNS.first()
        }
        val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // The date block sits at 24dp + 3dp of its own padding; this view carries 10dp of padding,
        // so 17dp of margin puts the two texts on exactly the same edge instead of near it.
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 17.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            // Follows the home alignment rather than opposing it. It used to flip to the other
            // side, which reads as a mistake the moment the apps are centred: the line sat hard
            // right under a centred column. Screen time, unlocks and weather are about the phone,
            // same as the clock and date above them, so they line up with everything else.
            gravity = prefs.homeAlignment or Gravity.TOP
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
        positionScreenTime()
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
        val params = binding.tvScreenTime.layoutParams as? FrameLayout.LayoutParams ?: return
        val dateBlock = binding.dateTimeLayout
        if (!dateBlock.isVisible || dateBlock.height == 0) return

        val desired = dateBlock.bottom + SCREEN_TIME_GAP_DP.dpToPx()
        // Guarded so the relayout this triggers does not loop.
        if (params.topMargin == desired) return
        params.topMargin = desired
        binding.tvScreenTime.layoutParams = params
    }

    /**
     * Repaints the home screen for the chosen colour theme. Does nothing on the System theme, so
     * the stock light/dark behaviour and any wallpaper the user set are left completely alone.
     */
    private fun applyColorTheme() {
        applyFocusOutlines()
        binding.mainLayout.applyTextWeight(Constants.TextWeight.value(prefs.textWeight))
        if (!ColorTheme.isCustom(prefs.colorThemeId)) {
            binding.mainLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            applySystemBarIcons(requireContext().isDarkThemeOn().not())
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
     * Puts an app icon before each home app name, or clears them when icons are off.
     *
     * A cached icon is applied straight away so the common path never waits. A miss is loaded on
     * a background thread and applied when it arrives, with the slot's package re-checked first -
     * by then the user may have changed which app that row points at.
     */
    private fun refreshHomeIcons() {
        val names = homeAppNameViews()
        if (!prefs.showHomeIcons) {
            names.forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null) }
            return
        }

        val sizePx = ICON_SIZE_DP.dpToPx()
        val grayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
        val context = requireContext().applicationContext

        names.forEachIndexed { index, name ->
            val location = index + 1
            val packageName = prefs.getAppPackage(location)
            if (!name.isVisible || packageName.isEmpty() || prefs.getIsShortcut(location)) {
                name.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                return@forEachIndexed
            }

            val className = prefs.getAppActivityClassName(location)
            val user = getUserHandleFromString(context, prefs.getAppUser(location))
            name.compoundDrawablePadding = ICON_GAP_DP.dpToPx()

            val cached = IconCache.peek(packageName, className, user, sizePx, grayscale)
            if (cached != null) {
                name.setCompoundDrawablesRelative(cached, null, null, null)
                return@forEachIndexed
            }

            name.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            viewLifecycleOwner.lifecycleScope.launch {
                val icon = withContext(Dispatchers.IO) {
                    IconCache.load(context, packageName, className, user, sizePx, grayscale)
                } ?: return@launch
                // The row may point somewhere else by the time this lands.
                if (prefs.getAppPackage(location) != packageName) return@launch
                name.setCompoundDrawablesRelative(icon, null, null, null)
                positionBadge(name, homeAppBadgeViews()[index])
            }
        }
    }

    /**
     * Applies the home layout options: whether visibility changes animate, and how much air each
     * row gets. Spacing is added on top of the density default rather than replacing it, so a
     * setting of zero still looks right on every screen size.
     */
    private fun applyHomeLayoutOptions() {
        binding.mainLayout.layoutTransition =
            if (prefs.homeAnimations) android.animation.LayoutTransition() else null

        val extra = prefs.homeSpacingExtra.dpToPx()
        homeAppNameViews().forEach { name ->
            name.setPadding(name.paddingLeft, basePaddingPx + extra, name.paddingRight, basePaddingPx + extra)
        }
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        applyHomeLayoutOptions()
        populateHomeRows(appCountUpdated)
        refreshHomeIcons()
        applyColorTheme()
        // Must run after the rows, and outside populateHomeRows: that function returns early at
        // every one of the eight app-count checks, so anything appended to its body would be
        // skipped for all but a full eight-app home screen.
        refreshBadges()
    }

    private fun populateHomeRows(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                textView.contentDescription = getString(R.string.empty_home_slot)
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                textView.contentDescription = getString(R.string.empty_home_slot)
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            textView.contentDescription = null
            return true
        }
        textView.text = ""
        textView.contentDescription = getString(R.string.empty_home_slot)
        return false
    }

    private fun homeAppNameViews(): List<TextView> = listOf(
        binding.homeApp1, binding.homeApp2, binding.homeApp3, binding.homeApp4,
        binding.homeApp5, binding.homeApp6, binding.homeApp7, binding.homeApp8
    )

    /**
     * Puts the badge just past the end of the app name WITHOUT taking part in layout.
     *
     * The name is positioned by its own layout_gravity, exactly as it would be with no badge, so
     * a badge appearing or disappearing never shifts it. The badge is parked at the row's start
     * edge and moved by translationX, which is applied at draw time and cannot affect the name's
     * measured position or the row's centring.
     */
    private fun positionBadge(name: TextView, badge: TextView) {
        if (!badge.isVisible) return
        // A badge shown for the first time has width 0 until it is laid out; placing it from
        // that put it in the wrong spot until something else happened to trigger a pass.
        if (badge.width == 0) {
            badge.post { positionBadge(name, badge) }
            return
        }
        val gap = BADGE_GAP_DP.dpToPx()
        val row = badge.parent as? View
        val rtl = badge.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val trailing = if (rtl) (name.left - badge.width - gap) else (name.right + gap)
        val leading = if (rtl) (name.right + gap) else (name.left - badge.width - gap)
        // A right-aligned home screen ends the name flush with the row, so the trailing
        // position lands outside it and the row clips the badge away completely - the count
        // disappears for everyone, with no error. Fall back to the leading side when it
        // does not fit, which is the only place left that is still inside the row.
        val fits = row == null ||
            (trailing >= 0 && trailing + badge.width <= row.width)
        // translationX is a DELTA from where the view was laid out, not an absolute x. The
        // badge's layout_gravity is `start`, which is the RIGHT edge in RTL, so treating the
        // target as absolute pushed it straight off an RTL row.
        var left = if (fits) trailing else leading
        // Neither side fits when the name is long enough to fill the row - a wrapped label,
        // or a wide one at a large text size. Clamping keeps the count on screen; the old
        // code let the row clip it away with no error, which reads as "no notifications".
        if (row != null) left = left.coerceIn(0, (row.width - badge.width).coerceAtLeast(0))
        badge.translationX = (left - badge.left).toFloat()
    }

    /**
     * Android's default focus highlight is #292929, which is 1.44:1 on a black launcher -
     * well under the 3:1 a focus indicator owes, and the text itself does not change colour
     * when focused either, so d-pad and switch-access users had no cue at all. The ring is
     * drawn in the text colour, which the palette already guarantees at 9.9:1 or better.
     */
    private fun applyFocusOutlines() {
        val ring = if (ColorTheme.isCustom(prefs.colorThemeId))
            ColorTheme.byId(prefs.colorThemeId).text
        else requireContext().getColorFromAttr(R.attr.primaryColor)
        homeAppNameViews().forEach { it.applyFocusOutline(ring) }
        homeAppBadgeViews().forEach { it.applyFocusOutline(ring) }
        binding.clock.applyFocusOutline(ring)
        binding.date.applyFocusOutline(ring)
        binding.tvScreenTime.applyFocusOutline(ring)
    }

    private fun homeAppRows(): List<FrameLayout> = listOf(
        binding.homeAppRow1, binding.homeAppRow2, binding.homeAppRow3, binding.homeAppRow4,
        binding.homeAppRow5, binding.homeAppRow6, binding.homeAppRow7, binding.homeAppRow8
    )

    private fun homeAppBadgeViews(): List<TextView> = listOf(
        binding.homeAppBadge1, binding.homeAppBadge2, binding.homeAppBadge3, binding.homeAppBadge4,
        binding.homeAppBadge5, binding.homeAppBadge6, binding.homeAppBadge7, binding.homeAppBadge8
    )

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
        val names = homeAppNameViews()
        val badges = homeAppBadgeViews()
        val enabled = prefs.showNotificationBadges
        val homeAppsNum = prefs.homeAppsNum

        names.forEachIndexed { index, name ->
            val location = index + 1
            val badge = badges[index]

            // A shortcut is not an app and has no notifications of its own; a row past the app
            // count, hidden, or showing the empty hint has nothing to badge either.
            val badgeable = enabled &&
                location <= homeAppsNum &&
                name.isVisible &&
                !name.text.isNullOrEmpty() &&
                !prefs.getIsShortcut(location) &&
                prefs.getAppPackage(location).isNotEmpty()

            val count = if (badgeable) counts[badgeKeyFor(location)] ?: 0 else 0

            if (count <= 0) {
                if (badge.isVisible) badge.isVisible = false
                // Leave the empty-slot description alone; only clear a count we wrote.
                if (name.text.isNotEmpty()) name.contentDescription = null
                return@forEachIndexed
            }

            val label = when {
                prefs.badgeStyle == Constants.BadgeStyle.DOT -> getString(R.string.badge_dot)
                count > 99 -> getString(R.string.badge_count_overflow)
                else -> count.toString()
            }
            // Guard the write: setting identical text still costs a measure pass on a TextView.
            if (badge.text?.toString() != label) badge.text = label
            val spoken = resources.getQuantityString(
                R.plurals.missed_notifications, count, prefs.getAppName(location), count
            )
            badge.contentDescription = spoken
            // The name is the node that launches the app, so the count belongs on it too -
            // otherwise it is only heard by swiping onto a second node.
            name.contentDescription = spoken
            if (!badge.isVisible) badge.isVisible = true
            positionBadge(name, badge)
        }
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

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
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
        // Opening the app is the reset, and this is the one funnel every launch path goes
        // through - home tap, gesture, clock, calendar, screen time. Clearing at the
        // homeAppClicked caller instead meant launching the same app any other way left the
        // badge showing notifications you had just read.
        if (packageName.isNotEmpty())
            NotificationCounts.clearApp(NotificationCounts.key(packageName, userString))
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
        viewModel.getAppList(includeHiddenApps)
        val args = bundleOf(
            Constants.Key.FLAG to flag,
            Constants.Key.RENAME to rename,
            Constants.Key.KEYBOARD_MODE to keyboardMode
        )
        try {
            findNavController().navigate(R.id.action_mainFragment_to_appListFragment, args)
        } catch (e: Exception) {
            findNavController().navigate(R.id.appListFragment, args)
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
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
     * Everything missed across every home app, in one place. This is the opt-in stand-in for
     * Before Launcher's notification screen: it shows what arrived, grouped by app, and nothing
     * more - it deliberately cannot act on, dismiss or reply to a notification, because that is
     * the notification shade's job and duplicating it is how a minimal launcher stops being one.
     */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            MyAccessibilityService.lockScreen()
        ) return
        lockPhone()
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
                runGesture(Constants.Gesture.LONG_PRESS, Constants.GestureAction.LAUNCHER_SETTINGS)
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

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    /**
     * Same gestures as an app row, except a tap peeks at what was missed instead of launching.
     * Long press still opens the app picker for that slot, so the badge never becomes a dead zone
     * over the row's own behaviour.
     */
    private fun getBadgeSwipeTouchListener(
        context: Context,
        badge: View,
        nameView: View,
        location: Int,
    ): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, badge) {
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
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(context)
            }

            override fun onLongClick(view: View) {
                textOnLongClick(nameView)
            }

            override fun onClick(view: View) {
                showBadgeDetails(location)
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
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
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}