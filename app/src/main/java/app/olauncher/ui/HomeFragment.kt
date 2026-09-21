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
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.notificationAccessGranted
import app.olauncher.helper.notificationListenerComponent
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private companion object {
        /** Space between the end of an app name and its badge. */
        const val BADGE_GAP_DP = 10
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

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

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initBadgeFollowers()
    }

    /**
     * Keeps each badge glued to the end of its app name. The name's position changes for reasons
     * the badge code does not own - alignment, text size, bold font, a rename, a longer label
     * wrapping to two lines - and every one of them is a layout pass on the name.
     */
    private fun initBadgeFollowers() {
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
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
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
            it?.let { binding.tvScreenTime.text = it }
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
        binding.lock.setOnClickListener(this)
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
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
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
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
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
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        populateHomeRows(appCountUpdated)
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
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
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
        val gap = BADGE_GAP_DP.dpToPx()
        badge.translationX = if (badge.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            (name.left - badge.width - gap).toFloat()
        else
            (name.right + gap).toFloat()
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
                name.contentDescription = null
                return@forEachIndexed
            }

            val label = when {
                prefs.badgeStyle == Constants.BadgeStyle.DOT -> getString(R.string.badge_dot)
                count > 99 -> getString(R.string.badge_count_overflow)
                else -> count.toString()
            }
            // Guard the write: setting identical text still costs a measure pass on a TextView.
            if (badge.text?.toString() != label) badge.text = label
            val spoken = getString(R.string.missed_notifications, prefs.getAppName(location), count)
            badge.contentDescription = spoken
            name.contentDescription = prefs.getAppName(location)
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
        // Opening the app is the reset. This is the only place a badge count goes back to zero.
        NotificationCounts.clearApp(badgeKeyFor(location))
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
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
    private fun showMissedNotifications() {
        if (!prefs.showNotificationBadges || !requireContext().notificationAccessGranted()) {
            requireContext().showToast(getString(R.string.notification_badges_are_off))
            return
        }

        val sections = mutableListOf<String>()
        for (location in 1..prefs.homeAppsNum) {
            if (prefs.getIsShortcut(location) || prefs.getAppPackage(location).isEmpty()) continue
            val key = badgeKeyFor(location)
            val count = NotificationCounts.countFor(key)
            if (count <= 0) continue
            val lines = NotificationCounts.linesFor(key).asReversed()
            val header = getString(R.string.missed_notifications, prefs.getAppName(location), count)
            sections += if (lines.isEmpty()) header else header + "\n" + lines.joinToString("\n")
        }

        val body = if (sections.isEmpty()) getString(R.string.nothing_missed)
        else sections.joinToString("\n\n")

        requireContext().createDialog(
            title = R.string.missed,
            action = R.string.close,
            content = { container ->
                TextView(container.context, null, 0, R.style.TextSmall).apply { text = body }
            }
        ).showRespectingStatusBar()
    }

    /**
     * Runs whatever the user bound to a gesture. Every home gesture routes through here, so the
     * set of possible actions lives in exactly one place.
     */
    private fun runGesture(gesture: String, defaultAction: Int) {
        when (prefs.getGestureAction(gesture, defaultAction)) {
            Constants.GestureAction.NOTHING -> Unit
            Constants.GestureAction.APP_LIST -> showAppList(Constants.FLAG_LAUNCH_APP)
            Constants.GestureAction.APP_SEARCH -> showAppList(Constants.FLAG_LAUNCH_APP)
            Constants.GestureAction.NOTIFICATION_SHADE -> expandNotificationDrawer(requireContext())
            Constants.GestureAction.LAUNCHER_SETTINGS -> openLauncherSettings()
            Constants.GestureAction.LOCK_SCREEN -> lockPhoneByGesture()
            Constants.GestureAction.MISSED_NOTIFICATIONS -> showMissedNotifications()
            Constants.GestureAction.LAUNCH_APP -> {
                val packageName = prefs.getGestureAppPackage(gesture)
                if (packageName.isEmpty()) {
                    requireContext().showToast(getString(R.string.no_app_selected_for_gesture))
                    return
                }
                launchApp(
                    prefs.getGestureAppName(gesture),
                    packageName,
                    prefs.getGestureAppClassName(gesture),
                    prefs.getGestureAppUser(gesture)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) binding.lock.performClick()
        else lockPhone()
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