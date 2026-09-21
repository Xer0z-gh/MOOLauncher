package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogTextSizeBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.notificationAccessGranted
import app.olauncher.helper.notificationListenerComponent
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.setPlainWallpaperColor
import app.olauncher.helper.shareApp
import app.olauncher.helper.IconCache
import app.olauncher.helper.IconPack
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.OlDialog
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showStatusBar
import app.olauncher.helper.showToast
import app.olauncher.helper.withAlpha
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val showPentastic = System.currentTimeMillis() % 2 == 0L
    private var dialog: OlDialog? = null

    private companion object {
        /** App names drawn inside each theme preview tile. */
        const val PREVIEW_LINES = 3
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateProMessage()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateNotificationBadges()
        populateBadgeOptions()
        populateColorTheme()
        populateIconSettings()
        populateGestures()
        populateLockSettings()
        // Home button for recents feature disabled
        // populateHomeButtonRecents()
        populateWallpaperText()
        populateAppThemeText()
        populateTextSize()
        populateBoldFont()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateActionHints()
        initClickListeners()
        initObservers()

        if (showPentastic)
            binding.footer.text = getText(R.string.new_app_minimal_todo_lists)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.moreFeatures -> viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.homeAppsNum -> showHomeAppsNumMenu(view)
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignment -> showAlignmentMenu(view)
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> showDateTimeMenu(view)
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = false)
            R.id.colorTheme -> showColorThemeDialog()
            R.id.appIcons -> toggleAppIcons()
            R.id.iconStyle -> showIconStyleMenu(view)
            R.id.iconPack -> showIconPackDialog()
            R.id.textSizeValue -> showTextSizeDialog()
            R.id.boldFont -> toggleBoldFont()
            R.id.notificationBadges -> toggleNotificationBadges()
            R.id.badgeStyle -> showBadgeStyleMenu(view)
            R.id.badgeTapDetails -> {
                prefs.badgeTapShowsDetails = !prefs.badgeTapShowsDetails
                populateBadgeOptions()
            }

            R.id.gestureSwipeUp, R.id.gestureSwipeDown,
            R.id.gestureDoubleTap, R.id.gestureLongPress -> showGestureMenu(view)

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT_OLAUNCHER)
            }

            R.id.share -> requireActivity().shareApp()
            R.id.rate -> {
                prefs.rateClicked = true
                requireActivity().rateApp()
            }

            R.id.twitter -> requireContext().openUrl(Constants.URL_TWITTER_TANUJ)
            R.id.github -> requireContext().openUrl(Constants.URL_OLAUNCHER_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_OLAUNCHER_PRIVACY)
            R.id.footer -> {
                requireContext().openUrl(
                    if (showPentastic) Constants.URL_PENTASTIC else Constants.URL_NTS
                )
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = true)
            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            // Long press jumps straight to the system screen, mirroring toggleLock above. This is
            // the way back in when access was revoked outside the app.
            R.id.notificationBadges -> openNotificationAccessSettings()

            // Long press re-picks the app without having to choose "Launch app" again.
            R.id.gestureSwipeUp, R.id.gestureSwipeDown,
            R.id.gestureDoubleTap, R.id.gestureLongPress -> gestureRowFor(view.id)?.let { row ->
                prefs.setGestureAction(row.gesture, Constants.GestureAction.LAUNCH_APP)
                showAppListIfEnabled(row.flag)
            }
        }
        return true
    }

    /**
     * Granting notification access happens on a system screen that returns no result, so without
     * re-reading on resume the row would still say Off after the user came back having granted it.
     * The other two permission-backed rows have the same latent bug and are refreshed here too.
     */
    override fun onResume() {
        super.onResume()
        populateNotificationBadges()
        populateBadgeOptions()
        populateColorTheme()
        populateIconSettings()
        populateGestures()
        populateLockSettings()
        populateScreenTimeOnOff()
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        binding.moreFeatures.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.homeAppsNum.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.notificationBadges.setOnClickListener(this)
        binding.notificationBadges.setOnLongClickListener(this)
        binding.colorTheme.setOnClickListener(this)
        binding.appIcons.setOnClickListener(this)
        binding.iconStyle.setOnClickListener(this)
        binding.iconPack.setOnClickListener(this)
        binding.badgeStyle.setOnClickListener(this)
        binding.badgeTapDetails.setOnClickListener(this)
        binding.gestureSwipeUp.setOnClickListener(this)
        binding.gestureSwipeDown.setOnClickListener(this)
        binding.gestureDoubleTap.setOnClickListener(this)
        binding.gestureLongPress.setOnClickListener(this)
        binding.gestureSwipeUp.setOnLongClickListener(this)
        binding.gestureSwipeDown.setOnLongClickListener(this)
        binding.gestureDoubleTap.setOnLongClickListener(this)
        binding.gestureLongPress.setOnLongClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.boldFont.setOnClickListener(this)

        binding.share.setOnClickListener(this)
        binding.rate.setOnClickListener(this)
        binding.twitter.setOnClickListener(this)
        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
        binding.footer.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    // Popup menus

    private fun showHomeAppsNumMenu(anchor: View) {
        anchor.showPopupMenu(
            configure = { menu ->
                for (num in 0..8) menu.add(Menu.NONE, num, num, num.toString())
            }
        ) { item -> updateHomeAppsNum(item.itemId) }
    }

    private fun showDateTimeMenu(anchor: View) {
        anchor.showPopupMenu(R.menu.date_time) { item ->
            when (item.itemId) {
                R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
                R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
                R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            }
        }
    }

    private fun showAlignmentMenu(anchor: View) {
        anchor.showPopupMenu(
            R.menu.alignment,
            configure = { menu ->
                menu.findItem(R.id.alignmentBottom).setTitle(
                    if (prefs.homeBottomAlignment) R.string.bottom_on else R.string.bottom_off
                )
            }
        ) { item ->
            when (item.itemId) {
                R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
                R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
                R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
                R.id.alignmentBottom -> updateHomeBottomAlignment()
            }
        }
    }

    // "System" stays hidden unless the row is long pressed
    private fun showAppThemeMenu(anchor: View, showSystem: Boolean) {
        anchor.showPopupMenu(
            R.menu.app_theme,
            configure = { menu -> menu.findItem(R.id.themeSystem).isVisible = showSystem }
        ) { item ->
            when (item.itemId) {
                R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
                R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
                R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    // Dialogs

    private fun showDialog(newDialog: OlDialog) {
        dialog?.dismiss()
        dialog = newDialog
        newDialog.showRespectingStatusBar()
    }

    private fun showTextSizeDialog() {
        var stepper: DialogTextSizeBinding? = null
        val dialog = requireContext().createDialog(R.string.text_size, R.string.okay) { container ->
            DialogTextSizeBinding.inflate(layoutInflater, container, false).also { stepper = it }.root
        }
        stepper?.apply {
            textSizeCurrent.text = formatScale(pendingOrCurrentTextSizeScale())
            textSizeMinus.setOnClickListener { adjustTextSizePreview(-0.1f, this) }
            textSizePlus.setOnClickListener { adjustTextSizePreview(0.1f, this) }
        }
        dialog.setOnDismissListener { applyTextSizeScale() }
        showDialog(dialog)
    }

    // Prominent disclosure before sending the user to accessibility settings
    private fun showAccessibilityDialog() {
        val serviceEnabled = isAccessServiceEnabled(requireContext())
        showDialog(
            requireContext().createDialog(
                title = R.string.gestures,
                action = if (serviceEnabled) R.string.disable else R.string.enable,
                message = R.string.accessibility_disclosure,
                neutral = R.string.not_working,
                onNeutral = { requireContext().openUrl(Constants.URL_DOUBLE_TAP) },
                onAction = { openAccessibilityService() },
            )
        )
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        if (prefs.swipeLeftEnabled) {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_left_app_enabled))
        } else {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_left_app_disabled))
        }
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        if (prefs.swipeRightEnabled) {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_right_app_enabled))
        } else {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_right_app_disabled))
        }
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            requireActivity().window.showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            requireActivity().window.hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    /**
     * The row reads On only when the user's own opt-in AND the system grant are both in place,
     * because either one going away silently stops the badges working.
     */
    // --- Gestures ---------------------------------------------------------------------------

    private data class GestureRow(val gesture: String, val default: Int, val flag: Int)

    private fun gestureRowFor(viewId: Int): GestureRow? = when (viewId) {
        R.id.gestureSwipeUp -> GestureRow(
            Constants.Gesture.SWIPE_UP, Constants.GestureAction.APP_LIST,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_UP
        )

        R.id.gestureSwipeDown -> GestureRow(
            Constants.Gesture.SWIPE_DOWN, Constants.GestureAction.NOTIFICATION_SHADE,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_DOWN
        )

        R.id.gestureDoubleTap -> GestureRow(
            Constants.Gesture.DOUBLE_TAP, Constants.GestureAction.LOCK_SCREEN,
            Constants.FLAG_SET_GESTURE_APP_DOUBLE_TAP
        )

        R.id.gestureLongPress -> GestureRow(
            Constants.Gesture.LONG_PRESS, Constants.GestureAction.LAUNCHER_SETTINGS,
            Constants.FLAG_SET_GESTURE_APP_LONG_PRESS
        )

        else -> null
    }

    private fun actionLabel(gesture: String, default: Int): String =
        when (prefs.getGestureAction(gesture, default)) {
            Constants.GestureAction.NOTHING -> getString(R.string.action_nothing)
            Constants.GestureAction.APP_LIST -> getString(R.string.action_app_list)
            Constants.GestureAction.APP_SEARCH -> getString(R.string.action_app_search)
            Constants.GestureAction.NOTIFICATION_SHADE -> getString(R.string.action_notification_shade)
            Constants.GestureAction.LAUNCHER_SETTINGS -> getString(R.string.action_launcher_settings)
            Constants.GestureAction.LOCK_SCREEN -> getString(R.string.action_lock_screen)
            Constants.GestureAction.MISSED_NOTIFICATIONS -> getString(R.string.action_missed_notifications)
            Constants.GestureAction.LAUNCH_APP ->
                prefs.getGestureAppName(gesture).ifBlank { getString(R.string.action_launch_app) }

            else -> getString(R.string.action_nothing)
        }

    private fun populateGestures() {
        binding.gestureSwipeUp.text =
            actionLabel(Constants.Gesture.SWIPE_UP, Constants.GestureAction.APP_LIST)
        binding.gestureSwipeDown.text =
            actionLabel(Constants.Gesture.SWIPE_DOWN, Constants.GestureAction.NOTIFICATION_SHADE)
        binding.gestureDoubleTap.text =
            actionLabel(Constants.Gesture.DOUBLE_TAP, Constants.GestureAction.LOCK_SCREEN)
        binding.gestureLongPress.text =
            actionLabel(Constants.Gesture.LONG_PRESS, Constants.GestureAction.LAUNCHER_SETTINGS)
    }

    private fun showGestureMenu(anchor: View) {
        val row = gestureRowFor(anchor.id) ?: return
        anchor.showPopupMenu(R.menu.gesture_action) { item ->
            val action = when (item.itemId) {
                R.id.actionAppList -> Constants.GestureAction.APP_LIST
                R.id.actionAppSearch -> Constants.GestureAction.APP_SEARCH
                R.id.actionNotificationShade -> Constants.GestureAction.NOTIFICATION_SHADE
                R.id.actionLauncherSettings -> Constants.GestureAction.LAUNCHER_SETTINGS
                R.id.actionLockScreen -> Constants.GestureAction.LOCK_SCREEN
                R.id.actionMissedNotifications -> Constants.GestureAction.MISSED_NOTIFICATIONS
                R.id.actionLaunchApp -> Constants.GestureAction.LAUNCH_APP
                else -> Constants.GestureAction.NOTHING
            }
            prefs.setGestureAction(row.gesture, action)
            // Picking "Launch app" is only half a choice: send them straight to the app picker
            // rather than leaving a gesture bound to nothing in particular.
            if (action == Constants.GestureAction.LAUNCH_APP) showAppListIfEnabled(row.flag)
            else populateGestures()
        }
    }

    private fun showBadgeStyleMenu(anchor: View) {
        anchor.showPopupMenu(R.menu.badge_style) { item ->
            prefs.badgeStyle = when (item.itemId) {
                R.id.badgeStyleDot -> Constants.BadgeStyle.DOT
                else -> Constants.BadgeStyle.COUNT
            }
            populateBadgeOptions()
        }
    }

    /**
     * A grid of live swatches rather than a list of colour names, because nobody can picture
     * "Plum" and every one of these is a decision about how the phone will look all day.
     * Each swatch paints its own background and shows the theme's text colour on it, so the
     * pairing being chosen is the pairing on screen.
     */
    private fun showColorThemeDialog() {
        dialog?.dismiss()
        dialog = requireContext().createDialog(
            title = R.string.color_theme,
            action = R.string.close,
            content = { container -> buildThemeGrid(container) }
        ).also { it.showRespectingStatusBar() }
    }

    /**
     * The names shown inside a theme preview. Uses the user's own home apps, so the preview is a
     * picture of their home screen rather than of a generic one; falls back to sample names when
     * no home apps are set yet.
     */
    private fun previewLabels(): List<String> {
        val names = (1..prefs.homeAppsNum)
            .map { prefs.getAppName(it) }
            .filter { it.isNotBlank() }
            .take(PREVIEW_LINES)
        return if (names.isNotEmpty()) names
        else listOf(
            getString(R.string.preview_app_one),
            getString(R.string.preview_app_two),
            getString(R.string.preview_app_three),
        )
    }

    private fun buildThemeGrid(container: ViewGroup): View {
        val context = container.context
        val gap = 6.dpToPx()
        val tileWidth = 72.dpToPx()
        val tileHeight = 92.dpToPx()
        val labels = previewLabels()

        val grid = GridLayout(context).apply {
            columnCount = 3
            setPadding(gap, gap, gap, gap)
        }

        ColorTheme.ALL.forEach { theme ->
            val isSystem = theme.id == ColorTheme.SYSTEM_ID
            val selected = prefs.colorThemeId == theme.id
            val tileColor =
                if (isSystem) requireContext().getColorFromAttr(R.attr.primaryShadeColor)
                else theme.background
            val foreground =
                if (isSystem) requireContext().getColorFromAttr(R.attr.primaryColor)
                else theme.text

            // The preview: a miniature of the home screen in this theme's colours, so what is on
            // screen is what choosing it produces, rather than a colour you have to imagine text on.
            val preview = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(gap, gap, gap, gap)
                background = GradientDrawable().apply {
                    cornerRadius = 10.dpToPx().toFloat()
                    setColor(tileColor)
                    // Every tile is outlined, not just the selected one: a black tile on a
                    // near-black dialog is otherwise invisible, which is what happened to Ink
                    // and System. The outline is the tile's own text colour, so it reads on a
                    // near-black and a near-white tile alike.
                    if (selected) setStroke(3.dpToPx(), foreground)
                    else setStroke(1.dpToPx(), foreground.withAlpha(0x55))
                }
                labels.forEach { label ->
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(foreground)
                        textSize = 8f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, 1.dpToPx(), 0, 1.dpToPx())
                    })
                }
            }

            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                contentDescription = getString(
                    if (selected) R.string.theme_selected else R.string.theme_not_selected,
                    getString(theme.nameRes)
                )
                setOnClickListener { applyColorTheme(theme) }
                addView(preview, LinearLayout.LayoutParams(tileWidth, tileHeight))
                addView(TextView(context).apply {
                    text = getString(theme.nameRes)
                    setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, 4.dpToPx(), 0, 0)
                })
            }

            grid.addView(cell, GridLayout.LayoutParams().apply {
                setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            })
        }

        return grid
    }

    private fun applyColorTheme(theme: ColorTheme) {
        prefs.colorThemeId = theme.id
        if (ColorTheme.isCustom(theme.id)) {
            // A flat colour theme and a rotating wallpaper cannot both win.
            if (prefs.dailyWallpaper) {
                prefs.dailyWallpaper = false
                viewModel.cancelWallpaperWorker()
            }
            setPlainWallpaperColor(requireContext(), theme.background)
        }
        populateColorTheme()
        populateIconSettings()
        dialog?.dismiss()
        // Colours are read at inflate time in several places, so restart to repaint everything
        // consistently rather than leaving half the launcher on the old scheme.
        requireActivity().recreate()
    }

    private fun toggleAppIcons() {
        prefs.showAppIcons = !prefs.showAppIcons
        populateIconSettings()
        viewModel.refreshHome(false)
    }

    private fun showIconStyleMenu(anchor: View) {
        if (!prefs.showAppIcons) {
            requireContext().showToast(getString(R.string.turn_on_app_icons_first))
            return
        }
        anchor.showPopupMenu(R.menu.icon_style) { item ->
            val style = when (item.itemId) {
                R.id.iconStyleGrayscale -> Constants.IconStyle.GRAYSCALE
                else -> Constants.IconStyle.FULL_COLOR
            }
            if (style != prefs.iconStyle) {
                prefs.iconStyle = style
                // Cached icons are baked at one style, so the old ones are now wrong.
                IconCache.clear()
            }
            populateIconSettings()
            viewModel.refreshHome(false)
        }
    }

    /**
     * Lists the icon packs installed on the device. Discovery queries the package manager, which
     * is binder work over every installed app, so it happens off the main thread and the dialog
     * opens when the list is ready.
     */
    private fun showIconPackDialog() {
        if (!prefs.showAppIcons) {
            requireContext().showToast(getString(R.string.turn_on_app_icons_first))
            return
        }
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { IconPack.installedPacks(context) }
            if (!isAdded) return@launch
            if (packs.isEmpty()) {
                requireContext().showToast(getString(R.string.no_icon_packs_installed))
                return@launch
            }
            dialog?.dismiss()
            dialog = requireContext().createDialog(
                title = R.string.icon_pack,
                action = R.string.close,
                content = { container -> buildIconPackList(container, packs) }
            ).also { it.showRespectingStatusBar() }
        }
    }

    private fun buildIconPackList(container: ViewGroup, packs: List<IconPack.Pack>): View {
        val context = container.context
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // "Default" first, so turning a pack back off is as easy as turning it on.
        val entries = listOf(IconPack.Pack("", getString(R.string.icon_pack_default))) + packs
        entries.forEach { pack ->
            val selected = prefs.iconPackPackage == pack.packageName
            list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = if (selected) getString(R.string.icon_pack_selected, pack.label) else pack.label
                setPadding(8.dpToPx(), 12.dpToPx(), 8.dpToPx(), 12.dpToPx())
                isFocusable = true
                setOnClickListener { applyIconPack(pack.packageName) }
            })
        }
        return list
    }

    private fun applyIconPack(packPackage: String) {
        prefs.iconPackPackage = packPackage
        IconCache.iconPackPackage = packPackage
        populateIconSettings()
        dialog?.dismiss()
        viewModel.refreshHome(false)
    }

    private fun populateIconSettings() {
        binding.appIcons.text = getString(if (prefs.showAppIcons) R.string.on else R.string.off)
        binding.iconStyle.text = getString(
            if (prefs.iconStyle == Constants.IconStyle.GRAYSCALE) R.string.icon_style_grayscale
            else R.string.icon_style_full_color
        )
        val pack = prefs.iconPackPackage
        binding.iconPack.text = if (pack.isEmpty()) getString(R.string.icon_pack_default)
        else runCatching {
            val pm = requireContext().packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pack, 0)).toString()
        }.getOrDefault(getString(R.string.icon_pack_default))
    }

    private fun populateColorTheme() {
        binding.colorTheme.text = getString(ColorTheme.byId(prefs.colorThemeId).nameRes)
    }

    private fun populateBadgeOptions() {
        binding.badgeStyle.text = getString(
            if (prefs.badgeStyle == Constants.BadgeStyle.DOT) R.string.badge_style_dot
            else R.string.badge_style_count
        )
        binding.badgeTapDetails.text =
            getString(if (prefs.badgeTapShowsDetails) R.string.on else R.string.off)
    }

    private fun populateNotificationBadges() {
        binding.notificationBadges.text = getString(
            if (prefs.showNotificationBadges && requireContext().notificationAccessGranted())
                R.string.on else R.string.off
        )
    }

    private fun showNotificationDialog() {
        requireContext().createDialog(
            title = R.string.notification_badges,
            action = R.string.notification_access,
            message = R.string.notification_badges_message,
            onAction = { openNotificationAccessSettings() },
        ).showRespectingStatusBar()
    }

    private fun openNotificationAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure {
                requireContext().showToast(getString(R.string.unable_to_open_notification_settings))
            }
    }

    private fun toggleNotificationBadges() {
        val context = requireContext()
        // Turning it on without the system grant shows the disclosure and does NOT flip the pref;
        // the same shape as toggleLockMode. A pref that says On while access is denied is a lie.
        if (!prefs.showNotificationBadges && !context.notificationAccessGranted()) {
            showNotificationDialog()
            return
        }
        prefs.showNotificationBadges = !prefs.showNotificationBadges
        if (prefs.showNotificationBadges) {
            runCatching {
                NotificationListenerService.requestRebind(context.notificationListenerComponent())
            }
        } else {
            // Off has to mean off: stop counting and drop what was counted, rather than leaving
            // the listener bound and quietly still reading every notification.
            NotificationCounts.clear()
            runCatching {
                NotificationListenerService.requestUnbind(context.notificationListenerComponent())
            }
        }
        populateNotificationBadges()
    }

    private fun openAccessibilityService() {
        // prefs.lockModeOn = true
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                showAccessibilityDialog()
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeWallpaper() {
        if (requireContext().isEinkDisplay()) {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
            setPlainWallpaper(requireContext(), android.R.color.white)
        } else {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_YES
            setPlainWallpaper(requireContext(), android.R.color.black)
        }
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isOlauncherDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    private var pendingTextSizeScale: Float = -1f

    private fun pendingOrCurrentTextSizeScale(): Float =
        if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale

    private fun formatScale(scale: Float): String = String.format("%.1f", scale)

    private fun adjustTextSizePreview(delta: Float, dialogBinding: DialogTextSizeBinding) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = pendingOrCurrentTextSizeScale()
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = formatScale(clamped)
        binding.textSizeValue.text = formatted
        dialogBinding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        val activity = activity ?: return
        if (activity.isChangingConfigurations.not())
            activity.recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            setPlainWallpaper(theme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateTextSize() {
        binding.textSizeValue.text = formatScale(prefs.textSizeScale)
    }

    private fun toggleBoldFont() {
        prefs.boldFont = !prefs.boldFont
        populateBoldFont()
        requireActivity().recreate()
    }

    private fun populateBoldFont() {
        binding.boldFont.text = getString(if (prefs.boldFont) R.string.on else R.string.off)
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
    }

    // Home button for recents feature disabled
    // private fun toggleHomeButtonRecents() {
    //     if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
    //         showAccessibilityDialog()
    //         return
    //     }
    //     prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
    //     populateHomeButtonRecents()
    // }

    // private fun populateHomeButtonRecents() {
    //     binding.homeButtonRecents.text = getString(
    //         if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
    //         else R.string.off
    //     )
    // }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn && isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        if (!prefs.swipeLeftEnabled)
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
        if (!prefs.swipeRightEnabled)
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
    }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        if (viewModel.isOlauncherDefault.value != true) return
        if (prefs.rateClicked.not() && prefs.toShowHintCounter > Constants.HINT_RATE_US && prefs.toShowHintCounter < Constants.HINT_RATE_US + 100)
            binding.rate.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.arrow_down_float, 0, 0)
    }

    private fun populateProMessage() {
        if (prefs.proMessageShown.not() && prefs.userState == Constants.UserState.SHARE) {
            prefs.proMessageShown = true
            viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
        }
    }

    override fun onDestroyView() {
        // Dismissing the text size dialog applies any pending scale via its dismiss listener
        dialog?.dismiss()
        dialog = null
        applyTextSizeScale()
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}
