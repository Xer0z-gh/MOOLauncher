package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.os.trace
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.pro.ProStore
import app.olauncher.pro.showProDialog
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.notificationAccessGranted
import app.olauncher.helper.notificationListenerComponent
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.IconCache
import app.olauncher.helper.IconPack
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.NotificationService
import app.olauncher.helper.OlDialog
import app.olauncher.helper.showStatusBar
import app.olauncher.helper.showToast
import app.olauncher.helper.withAlpha
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import app.olauncher.helper.Weather
import app.olauncher.helper.homeInformationEditor
import app.olauncher.helper.homeAppsEditor
import app.olauncher.helper.motionEditor
import app.olauncher.helper.showForLauncher
import app.olauncher.helper.showSettingsPage
import app.olauncher.helper.settingsPageColor
import app.olauncher.helper.settingsCardDrawable
import app.olauncher.helper.tintTextTree
import app.olauncher.helper.tintCompoundDrawables
import app.olauncher.helper.tintDialogControls
import app.olauncher.helper.LauncherMotion

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var _binding: FragmentSettingsBinding? = null
    private var featureDialog: androidx.appcompat.app.AlertDialog? = null
    private val binding get() = _binding!!
    private var dialog: OlDialog? = null
    private var rowLabeller: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var rowLabellerObserver: ViewTreeObserver? = null

    private companion object {
        /** App names drawn inside each theme preview tile. */
        const val PREVIEW_LINES = 3
        const val SECTION_NOTIFICATIONS = 5
        const val SECTION_WEATHER = 6
        const val SECTION_POWER = 7
        const val SECTION_BADGES = 8
    }

    /**
     * Rows that are on/off switches, by value id, with how to read their state. The whole row is
     * announced as a Switch named by its label, so the state is spoken once, as the checked state.
     */
    private val switchStates: Map<Int, (Context) -> Boolean> = mapOf(
        R.id.statusBar to { _: Context -> prefs.showStatusBar },
        R.id.weatherRow to { _: Context -> prefs.showWeather },
        R.id.dailyWallpaper to { _: Context -> prefs.dailyWallpaper },
        R.id.dateTime to { _: Context -> Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility) },
        R.id.ultraBatterySaver to { c: Context -> LauncherMotion.savingPower(c, prefs) },
        R.id.saverFollowsSystem to { _: Context -> prefs.saverFollowsSystem },
        R.id.alignmentBottom to { _: Context -> prefs.homeBottomAlignment },
    )

    /** Looked up, not bound: a row missing from one layout variant makes its binding nullable. */
    private val saverFollowsSystemRow: TextView?
        get() = _binding?.root?.findViewById(R.id.saverFollowsSystem)

    /** Built in code by addBottomAlignmentRow(), so looked up the same way. */
    private val bottomAlignmentRow: TextView?
        get() = _binding?.root?.findViewById(R.id.alignmentBottom)

    /** Which section this instance is showing; see Constants.Section. */
    private var section = Constants.Section.HUB

    /** Each section shows one focused card; the launcher prompt and links stay in the hub. */
    private fun applySection() {
        binding.sectionHub.isVisible = section == Constants.Section.HUB
        binding.sectionHome.isVisible = section == Constants.Section.HOME
        binding.sectionAppearance.isVisible = section == Constants.Section.APPEARANCE
        // Gestures live on the Motion and power page, as a card of their own below it.
        binding.sectionGestures.isVisible = section == SECTION_POWER
        binding.sectionApps.isVisible = section == Constants.Section.APPS
        binding.sectionNotifications.isVisible = section == SECTION_NOTIFICATIONS
        binding.sectionBadges.isVisible = section == SECTION_BADGES
        binding.sectionWeather.isVisible = section == SECTION_WEATHER
        binding.sectionPower.isVisible = section == SECTION_POWER
        binding.settingsHeader.isVisible = section == Constants.Section.HUB
        binding.settingsFooter.isVisible = section == Constants.Section.HUB
        // Three links in a row overflow at large text; stack them instead.
        if (resources.configuration.fontScale > 1.3f) binding.settingsFooter.orientation = LinearLayout.VERTICAL
    }

    private fun applySettingsSurface() {
        val ctx = requireContext()
        binding.mainActivityLayout.setBackgroundColor(settingsPageColor(ctx, prefs))
        listOf(binding.settingsHeader, binding.sectionHub, binding.sectionHome,
            binding.sectionAppearance, binding.sectionApps, binding.sectionNotifications,
            binding.sectionBadges, binding.sectionWeather, binding.sectionPower, binding.sectionGestures).forEach { card ->
            card.background = settingsCardDrawable(ctx, prefs)
        }
        binding.appInfo.imageTintList = android.content.res.ColorStateList.valueOf(focusRingColor())
        if (ColorTheme.isCustom(prefs.colorThemeId)) {
            val text = ColorTheme.byId(prefs.colorThemeId).text
            binding.scrollLayout.tintTextTree(text, text.withAlpha(179))
            binding.scrollLayout.tintCompoundDrawables(text)
            binding.scrollLayout.tintDialogControls(text)
        }
    }
    /** All seven section headers use the same 48dp Back affordance and title hierarchy. */
    private fun addSectionBackButtons() {
        (binding.sectionGestures.getChildAt(0) as? TextView)?.let { androidx.core.view.ViewCompat.setAccessibilityHeading(it, true) }
        listOf(binding.sectionHome, binding.sectionAppearance, binding.sectionApps,
            binding.sectionNotifications, binding.sectionBadges, binding.sectionWeather, binding.sectionPower).forEach { card ->
            val title = card.getChildAt(0) as? TextView ?: return@forEach
            card.removeViewAt(0)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val back = android.widget.ImageButton(requireContext()).apply {
                background = null
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = android.content.res.ColorStateList.valueOf(focusRingColor())
                contentDescription = getString(if (card === binding.sectionBadges)
                    R.string.back_to_notifications else R.string.settings_back)
                isClickable = true
                isFocusable = true
                applyFocusOutline(focusRingColor())
                // The glyph sits on the rows' text column (it was 10dp inside it); the button
                // stays a 48dp target, the spare padding on its title side.
                setPaddingRelative(2.dpToPx(), 12.dpToPx(), 22.dpToPx(), 12.dpToPx())
                setOnClickListener { findNavController().navigateUp() }
            }
            row.addView(back, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))
            title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            androidx.core.view.ViewCompat.setAccessibilityHeading(title, true)
            row.addView(title)
            card.addView(row, 0)
            // At 200% text "Notifications" is wider than the slot beside Back, and StaticLayout
            // then breaks the word by character. Shrink once so the longest word fits whole;
            // multi-word titles still wrap at spaces. One-shot, never a layout listener.
            title.doOnLayout {
                val available = (title.width - title.totalPaddingStart - title.totalPaddingEnd).toFloat()
                val longest = title.text.split(' ').maxOf { title.paint.measureText(it) }
                if (available > 0f && longest > available) {
                    title.setTextSize(TypedValue.COMPLEX_UNIT_PX, title.textSize * available / longest * 0.98f)
                    // Asked for inside this layout pass, the re-measure was dropped: the text fit on
                    // one line but kept its two-line box, pushing Back and the rows below it down.
                    title.post { title.requestLayout() }
                }
            }
            // A title that wraps (large text) would leave Back centred between its lines: keep
            // Back on the first line. Translation only, so this listener never requests layout.
            title.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val layout = title.layout ?: return@addOnLayoutChangeListener
                back.translationY = if (layout.lineCount < 2) 0f
                    else title.totalPaddingTop + layout.getLineBottom(0) / 2f - title.height / 2f
            }
        }
    }

    /** Keep the XML row bindings intact while placing each control under its main category. */
    private fun organizeSettingsRows() {
        fun move(row: View, destination: LinearLayout) {
            (row.parent as ViewGroup).removeView(row)
            val tailActions = when (destination) {
                binding.sectionNotifications -> 2
                binding.sectionWeather -> 1
                else -> 0
            }
            destination.addView(row, destination.childCount - tailActions)
        }
        move(binding.weatherRow.parent as View, binding.sectionWeather)
        move(binding.temperatureUnit.parent as View, binding.sectionWeather)
        move(binding.notificationPanelLayout, binding.sectionNotifications)
        move(binding.notificationBadgesLayout, binding.sectionBadges)
        move(binding.badgeStyle.parent as View, binding.sectionBadges)
        move(binding.badgeTapDetails.parent as View, binding.sectionBadges)
        move(binding.badgeFilter.parent as View, binding.sectionBadges)
        move(binding.notificationAccessLayout, binding.sectionBadges)
        move(binding.homeAnimations.parent as View, binding.sectionPower)
        // The saver row, its description and the follow-Android row travel as one group.
        move(binding.root.findViewById<View>(R.id.ultraSaverGroup) ?: binding.ultraBatterySaver, binding.sectionPower)

        // These controls have an equivalent in the visible editors and would double the Home list.
        (binding.unlockCount.parent as View).isVisible = false // Home information owns widgets.
        binding.screenTimeLayout.isVisible = false // Usage access is requested by Home information.
        binding.panelCustomization.isVisible = false // Every panel now has a named category row.
        binding.homeScrollStyle.isVisible = false // Customize Home owns layout modes.
        (binding.autoShowKeyboard.parent as View).isVisible = false // Customize Search owns both.
        (binding.autoLaunchFromSearch.parent as View).isVisible = false
        listOf(binding.homeBulkEdit, binding.homeInformation, binding.homePanelSettings).forEachIndexed { index, row ->
            binding.sectionHome.removeView(row)
            binding.sectionHome.addView(row, index + 1)
        }
    }

    /**
     * Bottom alignment was the fourth item of the alignment menu. Alignment is a slider now, which
     * holds only Left / Center / Right, so Bottom is a switch row right under it. Built like the
     * XML rows, and before stackLargeTextRows/arrangeSettingsRows, so it stacks at large text and
     * becomes a Switch exactly as Notification bar does.
     */
    private fun addBottomAlignmentRow() {
        val ctx = requireContext()
        val row = FrameLayout(ctx).apply {
            addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = getString(R.string.bottom_alignment)
                setTextColor(ctx.getColorFromAttr(R.attr.primaryColor))
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            }, FrameLayout.LayoutParams(-2, -2).apply { marginStart = 8.dpToPx() })
            addView(TextView(ctx, null, 0, R.style.TextSmallBold).apply {
                id = R.id.alignmentBottom
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { marginEnd = 4.dpToPx() })
        }
        val alignmentRow = binding.alignment.parent as View
        val parent = alignmentRow.parent as ViewGroup
        parent.addView(row, parent.indexOfChild(alignmentRow) + 1,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dpToPx() })
    }

    private fun openSection(target: Int) {
        runCatching {
            findNavController().navigate(
                R.id.settingsFragment,
                bundleOf(Constants.Key.SECTION to target)
            )
        }.onFailure { it.printStackTrace() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Named slices (here and in onViewCreated) so a Perfetto trace splits a section's open cost.
        _binding = trace("Settings.inflate") { FragmentSettingsBinding.inflate(inflater, container, false) }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post {
            if (isAdded && viewLifecycleOwnerLiveData.value?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true &&
                ViewModelProvider(this)[app.olauncher.helper.HomeAppsDraftState::class.java].rows != null) {
                onClick(binding.homeBulkEdit)
            }
        }
        prefs = Prefs(requireContext())
        trace("Settings.arrange") {
            organizeSettingsRows()
            addBottomAlignmentRow()
            addSectionBackButtons()
            stackLargeTextRows(binding.scrollLayout)
            applySettingsSurface()
            configureBadgeRows()
        }
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")


        val requested = arguments?.getInt(Constants.Key.SECTION, Constants.Section.HUB) ?: Constants.Section.HUB
        section = if (requested == Constants.Section.GESTURES) SECTION_POWER else requested
        applySection()
        // Sent here by a gesture that needs the accessibility service: offer it at once, or at
        // least land on the gesture rows (they sit below the motion rows on this page).
        if (requested == Constants.Section.GESTURES && savedInstanceState == null) view.post {
            if (_binding == null) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isAccessServiceEnabled(requireContext())) showAccessibilityDialog()
            else binding.sectionGestures.requestRectangleOnScreen(android.graphics.Rect(0, 0, binding.sectionGestures.width, binding.sectionGestures.height), true)
        }
        // A resolveActivity binder call. Only the hub's launcher prompt, Home's bottom alignment
        // and Appearance's wallpaper toggle read it, and Home refreshes it on every resume.
        if (section == Constants.Section.HUB || section == Constants.Section.HOME ||
            section == Constants.Section.APPEARANCE || viewModel.isOlauncherDefault.value == null)
            viewModel.isOlauncherDefault()

        trace("Settings.populate") { populateAll() }
        // The app's own name, not a button: Apps > Hidden apps is the labelled way in.
        ViewCompat.setAccessibilityHeading(binding.olauncherHiddenApps, true)
        trace("Settings.listen") {
            initClickListeners()
            initObservers()
        }
        // Values change from clicks, popup menus and dialogs alike, and every one of them
        // re-measures the TextView it wrote to. Hanging off the layout pass therefore covers
        // all of them, where hand-written calls at 25 call sites would drift the first time
        // a row was added.
        //
        // Kept and removed by hand: a ViewTreeObserver outlives onDestroyView, and the first
        // version of this crashed the launcher on the way out of Settings by dereferencing a
        // binding that was already null.
        // Guarded on purpose. This listener has caused four defects - a null binding, a
        // relayout loop, a crash from a default argument, and it runs on every layout pass
        // of a screen inside the app that IS the home screen. What it does is decoration:
        // an accessible name and a focus ring. Decoration must never take the launcher down.
        // Functional paths are deliberately NOT wrapped like this, so real bugs still show.
        rowLabeller = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { trace("Settings.label") { labelSettingsRows() } }.onFailure { it.printStackTrace() }
        }
        trace("Settings.style") {
            val weight = Constants.TextWeight.value(prefs.textWeight)
            binding.scrollLayout.applyTextWeight(weight)
            binding.notificationAccessDetail.setTypeface(null, android.graphics.Typeface.NORMAL)
            // After applyTextWeight, which would otherwise flatten the values back to the label weight.
            arrangeSettingsRows(binding.scrollLayout, weight, TypedValue().also {
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId)
            emphasizeValue(binding.notificationAccessState, weight)
        }
        // Reading the wallpaper source used to be a hidden tap on the label, which opened a
        // browser with nothing saying so. The whole row toggles now; the source stays reachable
        // by name for screen readers.
        ViewCompat.addAccessibilityAction(rowOf(binding.dailyWallpaper),
            getString(R.string.wallpaper_source_action)) { host, _ ->
            host.context.openUrl(prefs.dailyWallpaperUrl)
            true
        }
        rowLabellerObserver = binding.scrollLayout.viewTreeObserver
        rowLabellerObserver?.addOnGlobalLayoutListener(rowLabeller)
    }

    private fun populateAll() {
        populateKeyboardText()
        populateAutoLaunchText()
        populateNotificationBadges()
        populateBadgeOptions()
        populateColorTheme()
        // Resolves the icon pack's label (a PackageManager lookup) for rows only Appearance shows.
        if (section == Constants.Section.APPEARANCE) populateIconSettings()
        populateGestures()
        populateWallpaperText()
        populateAppThemeText()
        populateTextSize()
        populateFont()
        populateTextWeight()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateHomeLayoutOptions()
    }

    /** Keep label/value pairs separate when enlarged text exhausts two columns. */
    private fun stackLargeTextRows(root: ViewGroup) {
        if (resources.configuration.fontScale <= 1.3f) return
        for (i in 0 until root.childCount) {
            val row = root.getChildAt(i) as? ViewGroup ?: continue
            if (row is android.widget.FrameLayout && row.childCount == 2 &&
                row.getChildAt(0) is TextView && row.getChildAt(1) is TextView) {
                val label = row.getChildAt(0); val value = row.getChildAt(1)
                (value as TextView).gravity = Gravity.START or Gravity.CENTER_VERTICAL
                // The value's start padding (8-24dp) was built for the right-hand column; stacked,
                // it indented the value off its label's edge. The 48dp minHeight keeps the target.
                value.setPaddingRelative(0, value.paddingTop, value.paddingEnd, value.paddingBottom)
                // A column cap (the saver's value) would break words once the value has the full width.
                value.maxWidth = Int.MAX_VALUE
                row.removeAllViews()
                val stack = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                listOf(label, value).forEach { child ->
                    stack.addView(child, LinearLayout.LayoutParams(-1, -2).apply { marginStart = 8.dpToPx() })
                }
                row.addView(stack, android.widget.FrameLayout.LayoutParams(-1, -2))
            } else stackLargeTextRows(row)
        }
    }

    /**
     * The label and value of a settings row: a FrameLayout holding two TextViews, or holding the
     * vertical stack stackLargeTextRows() puts them in at large text. Null for anything else.
     */
    private fun settingsPair(row: ViewGroup): Pair<TextView, TextView>? {
        if (row !is FrameLayout) return null
        val holder: ViewGroup =
            if (row.childCount == 1) (row.getChildAt(0) as? LinearLayout ?: return null) else row
        if (holder.childCount != 2) return null
        val label = holder.getChildAt(0) as? TextView ?: return null
        val value = holder.getChildAt(1) as? TextView ?: return null
        return label to value
    }

    /** The row that holds [value], stacked or not; [value] itself when it is not in a row. */
    private fun rowOf(value: View): View {
        val parent = value.parent as? View ?: return value
        if (parent is FrameLayout) return parent
        val grandparent = parent.parent
        return if (parent is LinearLayout && grandparent is FrameLayout) grandparent else value
    }

    /** Whether [view] is inside the section card applySection() left visible. */
    private fun inShownSection(view: View): Boolean =
        generateSequence(view) { it.parent as? View }
            .firstOrNull { it.parent === binding.scrollLayout }?.isVisible == true

    /**
     * Puts a slider under [value]'s row, or re-binds the one already there, so each populate
     * function calls this every time it writes its value and the slider never disagrees with the
     * stored one. Only for the section on screen: each section is its own fragment instance, and
     * the others' sliders would be built for rows nobody sees. [labels] is only asked for then -
     * the date row's is today's date in every format.
     */
    private fun bindSlider(value: TextView, index: Int, labels: () -> List<String>, onCommit: (Int) -> Unit): StepSlider? {
        if (!inShownSection(value)) return null
        val row = rowOf(value) as? ViewGroup ?: return null
        val (label, _) = settingsPair(row) ?: return null
        // The slider is the setting's one node and speaks its name and value; the texts above do not.
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return attachSliderBelow(row, value, label.text, labels(), index, focusRingColor(), label, onCommit).also {
            it.id = when (value.id) {
                R.id.appIconSize -> R.id.sliderAppIconSize
                R.id.homeSpacing -> R.id.sliderHomeSpacing
                R.id.textSizeValue -> R.id.sliderTextSize
                R.id.textWeight -> R.id.sliderTextWeight
                R.id.alignment -> R.id.sliderAlignment
                else -> View.NO_ID
            }
        }
    }

    /**
     * A setting whose values are separate options rather than points on a scale (date format,
     * font, theme mode): the row names the current one and a tap lists them all, the current one
     * checked. Sliders are for scales only - sizes, spacing, weight, position (Tanner, 2026-09-27:
     * "dont put the scrollbars everwhere just wherever fitting"). [target] takes the tap; badge rows
     * are whole-row controls, the rest forward row taps to the value (arrangeSettingsRows).
     */
    private fun bindChoice(value: TextView, index: Int, labels: () -> List<String>, target: View = value, onCommit: (Int) -> Unit) {
        target.setOnClickListener {
            val name = (rowOf(value) as? ViewGroup)?.let(::settingsPair)?.first?.text
            featureDialog?.dismiss()
            featureDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(name)
                .setSingleChoiceItems(labels().toTypedArray(), index) { choice, picked ->
                    choice.dismiss()
                    if (picked != index) onCommit(picked)
                }.create().also { it.showForLauncher() }
        }
    }

    /**
     * One pass over the label/value rows, once per view, after the rows are placed, stacked and
     * weighted:
     * - label and value share one vertical centre; the value column is right-aligned. The
     *   value used to sit at the row's bottom, ~4dp below its label, and short values were
     *   centred in their 48dp box, so the column's right edge wandered.
     * - the value is one weight step above the label (applyTextWeight had flattened the bold
     *   away) and uses tabular figures.
     * - the whole row is the control. Only the value used to respond, about 14% of a row
     *   people aim at by its label. The value keeps the listener, so popups still anchor on it.
     */
    private fun arrangeSettingsRows(root: ViewGroup, weight: Int, rowBackground: Int) {
        for (i in 0 until root.childCount) {
            val row = root.getChildAt(i) as? ViewGroup ?: continue
            val pair = settingsPair(row)
            if (pair == null) {
                arrangeSettingsRows(row, weight, rowBackground)
                continue
            }
            val (label, value) = pair
            if (label.parent === row) {
                (label.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    label.layoutParams = it
                }
                (value.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    value.layoutParams = it
                }
                value.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            emphasizeValue(value, weight)
            // Badge rows are already whole-row controls with their own roles (configureBadgeRows).
            // A slider row's value is a readout with no listener: the slider under it is the control.
            if (row.hasOnClickListeners() || label.isClickable || !value.hasOnClickListeners()) continue
            row.setOnClickListener { value.performClick() }
            // Not Daily wallpaper's: its long press replaces the wallpaper with plain black. Kept
            // on the value itself, as it always was; a long press on the label must not do it.
            if (value.isLongClickable && value.id != R.id.dailyWallpaper)
                row.setOnLongClickListener { value.performLongClick() }
            row.setBackgroundResource(rowBackground)
            row.isFocusable = true
            value.isFocusable = false
            // One node per row: the row speaks, its two texts do not.
            label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ViewCompat.setAccessibilityDelegate(row, rowRole(switchStates[value.id]))
        }
    }

    private fun emphasizeValue(value: TextView, weight: Int) {
        // Below API 28 the value keeps TextSmallBold's bold; applyTextWeight only adds bold there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) value.typeface = Typeface.create(
            value.typeface, (weight + 200).coerceAtMost(900), value.typeface?.isItalic == true)
        value.fontFeatureSettings = "tnum"
    }

    /** A Switch with its checked state when [checked] is given, otherwise a Button. */
    private fun rowRole(checked: ((Context) -> Boolean)?) = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = (if (checked != null) android.widget.Switch::class.java
                else android.widget.Button::class.java).name
            if (checked != null) {
                info.isCheckable = true
                info.isChecked = checked(host.context)
            }
        }
    }

    /**
     * Gives every settings control an accessible name, and a focus ring you can see.
     *
     * Only the value used to hold the click listener, so a screen reader announced "On, double
     * tap to activate" with no idea what it toggled, and Voice Access offered a dozen identical
     * "On" targets. The row now carries "Label, Value", or just the label on a switch, whose
     * state is spoken as its checked state.
     */
    private fun labelSettingsRows(root: ViewGroup? = null, ringColor: Int? = null) {
        // The guard has to come before ANY fragment access. A default argument of
        // `ring: Int = focusRingColor()` was evaluated BEFORE this body ran, so requireContext()
        // threw the instant the layout listener fired while the fragment was detaching - which is
        // what opening a settings section does, and it crashed the launcher every time.
        if (_binding == null || !isAdded) return
        val ring = ringColor ?: focusRingColor()
        @Suppress("NAME_SHADOWING") val root = root ?: binding.scrollLayout
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            // applySection() leaves the other sections GONE. Walking them anyway meant
            // most of the work on every pass was for rows nobody could see.
            if (!child.isVisible) continue
            val pair = settingsPair(child)
            if (pair != null) {
                val (label, value) = pair
                // Badge rows name themselves in populateBadgeOptions/populateNotificationBadges.
                if (!value.hasOnClickListeners()) continue
                // setContentDescription does not request layout, so this is safe to run from a
                // layout listener. Anything that DOES request layout is not - see the focus ring.
                val name = getString(R.string.a11y_pair, label.text, value.text)
                val wholeRow = child.hasOnClickListeners()
                val switch = value.id in switchStates
                if (wholeRow) {
                    val spoken = if (switch) label.text else name
                    if (child.contentDescription != spoken) child.contentDescription = spoken
                }
                // A popup menu titles itself from its anchor, the value, so a menu row's value
                // names the choice. Never the row's own name: uiautomator lists hidden nodes
                // too, and two nodes with one name break tap-by-name harnesses.
                val valueName = when {
                    !wholeRow -> name
                    switch -> null
                    else -> label.text
                }
                if (value.contentDescription != valueName) value.contentDescription = valueName
                label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                (if (wholeRow) child else value).applyFocusOutline(ring)
                continue
            }
            // Every clickable text in the row, not just the first: a row with two controls
            // left the second one with no visible focus at all.
            (0 until child.childCount).map { child.getChildAt(it) }.filterIsInstance<TextView>()
                .filter { it.isClickable }.forEach { it.applyFocusOutline(ring) }
            labelSettingsRows(child, ring)
        }
    }

    /** The ring follows the same text colour as the opaque Settings surface. */
    private fun focusRingColor(): Int =
        if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
        else context?.getColorFromAttr(R.attr.primaryColor) ?: Color.TRANSPARENT
    override fun onClick(view: View) {
        when (view.id) {
            R.id.hubHome -> openSection(Constants.Section.HOME)
            R.id.hubAppearance -> openSection(Constants.Section.APPEARANCE)
            R.id.hubApps -> openSection(Constants.Section.APPS)
            R.id.hubNotifications -> openSection(SECTION_NOTIFICATIONS)
            R.id.badgesSettings -> openSection(SECTION_BADGES)
            R.id.hubWeather -> openSection(SECTION_WEATHER)
            R.id.hubPower -> openSection(SECTION_POWER)
            R.id.homePanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.HOME) { viewModel.refreshHome(false) }
            R.id.appsPanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.APPS) { viewModel.refreshHome(false) }
            R.id.searchPanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.SEARCH) { viewModel.refreshHome(false) }
            R.id.notificationsPanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.NOTIFICATIONS) { viewModel.refreshHome(false) }
            R.id.weatherPanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.WEATHER) { viewModel.refreshHome(false) }
            R.id.visualizerPanelSettings -> customizePanel(app.olauncher.helper.PanelSettings.Page.VISUALIZER) { viewModel.refreshHome(false) }
            R.id.hiddenAppsRow -> showHiddenApps()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.autoLaunchFromSearch -> {
                prefs.autoLaunchFromSearch = !prefs.autoLaunchFromSearch
                populateAutoLaunchText()
            }
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            // A switch like its neighbours; it used to open a two-item On/Off menu.
            R.id.dateTime -> toggleDateTime(
                if (Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)) Constants.DateTime.OFF
                else Constants.DateTime.ON
            )
            R.id.homeAnimations -> {
                featureDialog?.dismiss()
                featureDialog = requireContext().motionEditor(prefs) {
                    populateHomeLayoutOptions()
                    viewModel.refreshHome(false)
                }.also { it.showSettingsPage() }
            }
            R.id.panelCustomization -> {
                featureDialog?.dismiss()
                val pages = app.olauncher.helper.PanelSettings.Page.values()
                val names = listOf(R.string.customize_home, R.string.customize_apps, R.string.customize_search,
                    R.string.customize_notifications, R.string.customize_weather, R.string.customize_visualizer)
                featureDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(R.string.customize_panels)
                    .setItems((listOf(getString(R.string.close)) + names.map(::getString)).toTypedArray()) { _, index ->
                        if (index > 0) customizePanel(pages[index - 1]) { viewModel.refreshHome(false) }
                    }
                    .create().also { it.showForLauncher() }
            }
            R.id.homeBulkEdit -> {
                featureDialog?.dismiss()
                featureDialog = requireContext().homeAppsEditor(prefs, viewLifecycleOwner.lifecycleScope, this) {
                    viewModel.refreshHome(true)
                }.also { it.showSettingsPage() }
            }
            R.id.homeInformation -> {
                featureDialog?.dismiss()
                featureDialog = requireContext().homeInformationEditor(prefs) { needsPermission ->
                    populateHomeLayoutOptions()
                    viewModel.refreshHome(false)
                    if (needsPermission) informationLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else requestInformationUsageAccess()
                }.also { it.showSettingsPage() }
            }
            R.id.homeScrollStyle -> {
                featureDialog?.dismiss()
                val choices = mutableListOf(R.string.home_scroll_static, R.string.home_scroll_focus)
                if (Build.VERSION.SDK_INT >= 31) choices.add(R.string.home_scroll_soft)
                featureDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.home_scroll_style)
                    .setSingleChoiceItems(choices.map(::getString).toTypedArray(), prefs.homeScrollStyle.coerceAtMost(choices.lastIndex)) { dialog, which ->
                        prefs.homeScrollStyle = which
                        populateHomeLayoutOptions()
                        viewModel.refreshHome(false)
                        dialog.dismiss()
                    }.create().also { it.showForLauncher() }
            }
            R.id.ultraBatterySaver -> {
                // Android Power Saver forces this mode; keep the user's manual choice intact.
                if (LauncherMotion.followingSystemSaver(requireContext(), prefs)) return
                prefs.ultraBatterySaver = !prefs.ultraBatterySaver
                if (prefs.ultraBatterySaver) suppressMotion()
                populateHomeLayoutOptions()
                viewModel.refreshHome(false)
                // Refresh rate now, not at the next start; the wallpaper part applies back on Home.
                (activity as? app.olauncher.MainActivity)?.applyPowerWindow()
                // Cancels the 4-hourly wallpaper job on the way in, re-schedules it on the way out.
                if (prefs.dailyWallpaper) viewModel.setWallpaperWorker()
            }
            R.id.saverFollowsSystem -> {
                // Same path as the manual switch above, minus the wallpaper job: that one only
                // follows the manual choice (MainViewModel.setWallpaperWorker).
                prefs.saverFollowsSystem = !prefs.saverFollowsSystem
                if (LauncherMotion.savingPower(requireContext(), prefs)) suppressMotion()
                populateHomeLayoutOptions()
                viewModel.refreshHome(false)
                (activity as? app.olauncher.MainActivity)?.applyPowerWindow()
            }

            R.id.weatherRow -> toggleWeather()
            R.id.unlockCount -> {
                if (!prefs.showUnlockCount && prefs.informationWidgetCount >= 4) {
                    requireContext().showToast(getString(R.string.information_widget_limit))
                    return
                }
                prefs.showUnlockCount = !prefs.showUnlockCount
                populateHomeLayoutOptions()
                viewModel.refreshHome(false)
            }
            R.id.colorTheme -> showColorThemeDialog()
            R.id.iconPack -> showIconPackDialog()
            R.id.notificationBadgesLayout -> toggleNotificationBadges()
            R.id.notificationAccessLayout -> openNotificationAccessSettings()
            R.id.badgeFilterLayout -> showBadgeFilter()
            R.id.notificationPanel -> openNotificationPanel()
            R.id.badgeTapDetailsLayout -> {
                prefs.badgeTapShowsDetails = !prefs.badgeTapShowsDetails
                populateBadgeOptions()
            }

            R.id.gestureSwipeUp, R.id.gestureSwipeDown, R.id.gestureDoubleTap,
            R.id.gestureLongPress, R.id.gestureSwipeLeft,
            R.id.gestureSwipeRight -> showGestureMenu(view)

            R.id.github -> requireContext().openUrl(Constants.URL_MOO_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_MOO_PRIVACY)
            R.id.about -> showDialog(
                requireContext().createDialog(
                    title = R.string.about,
                    action = R.string.source_code,
                    messageText = android.text.SpannableStringBuilder(getString(R.string.about_text, BuildConfig.VERSION_NAME, "\uFFFC"))
                        .let { about -> about.indexOf("\uFFFC").let { at -> about.replace(at, at + 1, Weather.credit(requireContext())) } },
                    neutral = R.string.privacy,
                    onNeutral = { requireContext().openUrl(Constants.URL_MOO_PRIVACY) },
                    onAction = { requireContext().openUrl(Constants.URL_MOO_GITHUB) },
                )
            )
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.dailyWallpaper -> removeWallpaper()
            // Long press jumps straight to the system screen, mirroring toggleLock above. This is
            // the way back in when access was revoked outside the app.
            R.id.notificationBadges -> openNotificationAccessSettings()

            // Long press re-picks the app without having to choose "Launch app" again. All
            // six rows, not four - swipe left and right were long-clickable but fell through
            // this branch and did nothing.
            R.id.gestureSwipeUp, R.id.gestureSwipeDown, R.id.gestureDoubleTap,
            R.id.gestureLongPress, R.id.gestureSwipeLeft,
            R.id.gestureSwipeRight -> gestureRowFor(view.id)?.let { row ->
                // The action is NOT set here. Setting it before the picker opened meant
                // backing out of the picker still changed the gesture; MainViewModel sets it
                // when an app is actually chosen.
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
        if (section == Constants.Section.APPEARANCE) populateIconSettings()
        populateGestures()
        if (_binding != null) refreshUltraBatterySaver()
    }

    private fun initClickListeners() {
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.autoLaunchFromSearch.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.badgesSettings.setOnClickListener(this)
        binding.notificationBadgesLayout.setOnClickListener(this)
        binding.notificationAccessLayout.setOnClickListener(this)
        binding.notificationBadges.setOnLongClickListener(this)
        binding.hubHome.setOnClickListener(this)
        binding.hubAppearance.setOnClickListener(this)
        binding.hubApps.setOnClickListener(this)
        binding.hubNotifications.setOnClickListener(this)
        binding.hubWeather.setOnClickListener(this)
        binding.hubPower.setOnClickListener(this)
        binding.homePanelSettings.setOnClickListener(this)
        binding.appsPanelSettings.setOnClickListener(this)
        binding.searchPanelSettings.setOnClickListener(this)
        binding.notificationsPanelSettings.setOnClickListener(this)
        binding.weatherPanelSettings.setOnClickListener(this)
        binding.visualizerPanelSettings.setOnClickListener(this)
        binding.hiddenAppsRow.setOnClickListener(this)
        binding.colorTheme.setOnClickListener(this)
        binding.iconPack.setOnClickListener(this)
        binding.badgeFilterLayout.setOnClickListener(this)
        binding.notificationPanel.setOnClickListener(this)
        binding.homeAnimations.setOnClickListener(this)
        binding.homeInformation.setOnClickListener(this)
        binding.homeBulkEdit.setOnClickListener(this)
        binding.panelCustomization.setOnClickListener(this)
        binding.ultraBatterySaver.setOnClickListener(this)
        saverFollowsSystemRow?.setOnClickListener(this)
        bottomAlignmentRow?.setOnClickListener(this)
        binding.homeScrollStyle.setOnClickListener(this)
        binding.unlockCount.setOnClickListener(this)
        binding.weatherRow.setOnClickListener(this)
        binding.badgeTapDetailsLayout.setOnClickListener(this)
        binding.gestureSwipeUp.setOnClickListener(this)
        binding.gestureSwipeDown.setOnClickListener(this)
        binding.gestureDoubleTap.setOnClickListener(this)
        binding.gestureLongPress.setOnClickListener(this)
        binding.gestureSwipeUp.setOnLongClickListener(this)
        binding.gestureSwipeDown.setOnLongClickListener(this)
        binding.gestureDoubleTap.setOnLongClickListener(this)
        binding.gestureLongPress.setOnLongClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.gestureSwipeLeft.setOnClickListener(this)
        binding.gestureSwipeRight.setOnClickListener(this)

        binding.github.setOnClickListener(this)
        binding.about.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.gestureSwipeLeft.setOnLongClickListener(this)
        binding.gestureSwipeRight.setOnLongClickListener(this)
    }

    private fun initObservers() {
        prefs.firstSettingsOpen = false
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }    }

    // Dialogs

    private fun showDialog(newDialog: OlDialog) {
        dialog?.dismiss()
        dialog = newDialog
        newDialog.showRespectingStatusBar()
    }

    // Prominent disclosure before sending the user to accessibility settings
    private fun showAccessibilityDialog() {
        showDialog(
            requireContext().createDialog(
                title = R.string.accessibility_service_title,
                action = R.string.open_settings,
                message = R.string.accessibility_disclosure,
                neutral = R.string.not_now,
                // The "Not working?" button opened Olauncher's own troubleshooting page. A
                // button in this app that explains a different app is worse than no button.
                onAction = { openAccessibilityService() },
            )
        )
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
        prefs.infoShowDate = prefs.infoShowDate
        prefs.dateTimeVisibility = selected
        populateDateTime()
        populateHomeLayoutOptions()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            if (Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)) R.string.on else R.string.off
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

        R.id.gestureSwipeLeft -> GestureRow(
            Constants.Gesture.SWIPE_LEFT, Constants.GestureAction.LAUNCH_APP,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_LEFT
        )

        R.id.gestureSwipeRight -> GestureRow(
            Constants.Gesture.SWIPE_RIGHT, Constants.GestureAction.LAUNCH_APP,
            Constants.FLAG_SET_GESTURE_APP_SWIPE_RIGHT
        )

        else -> null
    }

    private fun actionLabel(gesture: String, default: Int): String =
        when (prefs.getGestureAction(gesture, default)) {
            Constants.GestureAction.NOTHING -> getString(R.string.action_nothing)
            // The value names what opens, like "Camera" beside it; the verbs ("Open missed
            // notifications") stay in the picker, where they read as actions.
            Constants.GestureAction.APP_LIST -> getString(R.string.gesture_value_app_list)
            Constants.GestureAction.APP_SEARCH -> getString(R.string.gesture_value_app_search)
            Constants.GestureAction.NOTIFICATION_SHADE -> getString(R.string.gesture_value_notification_shade)
            Constants.GestureAction.LAUNCHER_SETTINGS -> getString(R.string.gesture_value_launcher_settings)
            Constants.GestureAction.LOCK_SCREEN -> getString(R.string.action_lock_screen)
            Constants.GestureAction.MISSED_NOTIFICATIONS -> getString(R.string.gesture_value_notification_panel)
            // With no app chosen, Home runs a fallback for swipe left and right (openSwipeLeftApp /
            // openSwipeRightApp); name what will open rather than a generic "Launch app".
            Constants.GestureAction.LAUNCH_APP -> when {
                prefs.getGestureAppPackage(gesture).isNotEmpty() ->
                    prefs.getGestureAppName(gesture).ifBlank { getString(R.string.action_launch_app) }
                gesture == Constants.Gesture.SWIPE_LEFT -> getString(R.string.gesture_default_camera)
                gesture == Constants.Gesture.SWIPE_RIGHT -> getString(R.string.gesture_default_phone)
                else -> getString(R.string.action_launch_app)
            }

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
        binding.gestureSwipeLeft.text =
            actionLabel(Constants.Gesture.SWIPE_LEFT, Constants.GestureAction.LAUNCH_APP)
        binding.gestureSwipeRight.text =
            actionLabel(Constants.Gesture.SWIPE_RIGHT, Constants.GestureAction.LAUNCH_APP)
    }

    /** Same single-choice list as every other choice row, the current action checked. */
    private fun showGestureMenu(anchor: View) {
        val row = gestureRowFor(anchor.id) ?: return
        val actions = listOf(
            Constants.GestureAction.APP_LIST to R.string.action_app_list,
            Constants.GestureAction.APP_SEARCH to R.string.action_app_search,
            Constants.GestureAction.LAUNCH_APP to R.string.action_launch_app,
            Constants.GestureAction.MISSED_NOTIFICATIONS to R.string.action_notification_panel,
            Constants.GestureAction.NOTIFICATION_SHADE to R.string.action_notification_shade,
            Constants.GestureAction.LOCK_SCREEN to R.string.action_lock_screen,
            Constants.GestureAction.LAUNCHER_SETTINGS to R.string.action_launcher_settings,
            Constants.GestureAction.NOTHING to R.string.action_nothing,
        ).filter { it.first != Constants.GestureAction.LOCK_SCREEN || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P }
        val current = actions.indexOfFirst { it.first == prefs.getGestureAction(row.gesture, row.default) }
        val name = (rowOf(anchor) as? ViewGroup)?.let(::settingsPair)?.first?.text
        featureDialog?.dismiss()
        featureDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(name)
            .setSingleChoiceItems(actions.map { getString(it.second) }.toTypedArray(), current) { choice, picked ->
                choice.dismiss()
                // Launch app again is how the app is changed, so it is never a no-op.
                val action = actions[picked].first
                // Re-picking Lock screen or the shade while the service is off must still offer it.
                val needsService = (action == Constants.GestureAction.LOCK_SCREEN || action == Constants.GestureAction.NOTIFICATION_SHADE) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isAccessServiceEnabled(requireContext())
                if (picked != current || action == Constants.GestureAction.LAUNCH_APP || needsService) applyGestureAction(row, action)
            }.create().also { it.showForLauncher() }
    }

    private fun applyGestureAction(row: GestureRow, action: Int) {
        // Locking the screen needs the accessibility grant. This is the only control for it
        // now, so it asks here rather than leaving a gesture that reads "Lock screen" and
        // quietly does nothing - which is exactly what the old separate toggle allowed.
        if (action == Constants.GestureAction.LOCK_SCREEN) prefs.lockModeOn = true

        // Save FIRST. Asking for the accessibility grant used to return early, which
        // threw the choice away: you picked Lock screen, granted access, came back, and
        // the row still read whatever it said before.
        prefs.setGestureAction(row.gesture, action)

        // The shade gesture needs the same service, so it gets the same disclosure.
        if ((action == Constants.GestureAction.LOCK_SCREEN || action == Constants.GestureAction.NOTIFICATION_SHADE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !isAccessServiceEnabled(requireContext())
        ) {
            populateGestures()
            showAccessibilityDialog()
            return
        }
        // Picking "Launch app" is only half a choice: send them straight to the app picker
        // rather than leaving a gesture bound to nothing in particular.
        if (action == Constants.GestureAction.LAUNCH_APP) showAppListIfEnabled(row.flag)
        else populateGestures()
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
                setTag(R.id.keep_text_color, true)
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
                    else setStroke(1.dpToPx(), foreground.withAlpha(0x80))
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
                    if (ColorTheme.isPro(theme.id) && !ProStore.unlocked(prefs))
                        getString(R.string.pro_theme_label, getString(theme.nameRes)) else getString(theme.nameRes)
                )
                setOnClickListener { applyColorTheme(theme) }
                addView(preview, LinearLayout.LayoutParams(tileWidth, tileHeight))
                addView(TextView(context).apply {
                    text = if (ColorTheme.isPro(theme.id) && !ProStore.unlocked(prefs))
                        getString(R.string.pro_theme_label, getString(theme.nameRes)) else getString(theme.nameRes)
                    setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
                    textSize = 11f
                    // "Follow theme mode" wraps under its tile instead of widening its column.
                    maxWidth = tileWidth
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
        if (ColorTheme.isPro(theme.id) && !ProStore.unlocked(prefs)) {
            requireContext().showProDialog()
            return
        }
        prefs.colorThemeId = theme.id
        if (ColorTheme.isCustom(theme.id)) {
            // A flat colour theme and a rotating wallpaper cannot both win.
            if (prefs.dailyWallpaper) {
                prefs.dailyWallpaper = false
                viewModel.cancelWallpaperWorker()
            }
            // Deliberately does NOT write the wallpaper. The launcher paints its own background
            // now, so it does not need to, and overwriting someone's wallpaper to change a
            // launcher theme is a far bigger side effect than the feature is worth - it is also
            // not undoable, since the previous wallpaper cannot be read back.
        }
        populateColorTheme()
        populateIconSettings()
        dialog?.dismiss()
        // Colours are read at inflate time in several places, so restart to repaint everything
        // consistently rather than leaving half the launcher on the old scheme.
        requireActivity().recreate()
    }

    /**
     * Icon size, style and pack also drive the Search and Notifications icons, which are
     * switched in Customize search and Customize notifications, not by App icons.
     */
    private fun iconsAnywhere(): Boolean =
        prefs.showHomeIcons || prefs.showDrawerIcons || prefs.searchShowIcons || prefs.panelShowIcons

    /**
     * Lists the icon packs installed on the device. Discovery queries the package manager, which
     * is binder work over every installed app, so it happens off the main thread and the dialog
     * opens when the list is ready.
     */
    private fun showIconPackDialog() {
        if (!iconsAnywhere()) {
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

    /**
     * Icons are chosen per surface rather than toggled globally: wanting them while browsing
     * every installed app but not on a deliberately spare home screen is a coherent preference,
     * and one on/off switch cannot express it.
     */
    private fun populateIconSettings() {
        val places = listOf(R.string.off, R.string.icons_home_only, R.string.icons_drawer_only,
            R.string.icons_everywhere).map(::getString)
        val place = when {
            prefs.showHomeIcons && prefs.showDrawerIcons -> 3
            prefs.showDrawerIcons -> 2
            prefs.showHomeIcons -> 1
            else -> 0
        }
        binding.appIcons.text = places[place]
        bindChoice(binding.appIcons, place, { places }) {
            prefs.showHomeIcons = it == 1 || it == 3
            prefs.showDrawerIcons = it >= 2
            populateIconSettings()
            viewModel.refreshHome(false)
        }
        // Size, style and pack change nothing while no surface shows icons, so they leave - the
        // size slider with its row.
        val iconsShown = iconsAnywhere()
        listOf(binding.appIconSize, binding.iconStyle, binding.iconPack).forEach {
            rowOf(it).isVisible = iconsShown
        }
        val sizes = listOf(20, 28, 32, 40, 48)
        val sizeLabels = sizes.map { getString(R.string.value_dp, it) }
        val size = nearestIndex(sizes, prefs.appIconSize)
        binding.appIconSize.text = sizeLabels[size]
        bindSlider(binding.appIconSize, size, { sizeLabels }) {
            prefs.appIconSize = sizes[it]
            viewModel.refreshHome(false)
        }?.isVisible = iconsShown
        val styles = listOf(getString(R.string.icon_style_full_color), getString(R.string.icon_style_grayscale))
        val style = if (prefs.iconStyle == Constants.IconStyle.GRAYSCALE) 1 else 0
        binding.iconStyle.text = styles[style]
        bindChoice(binding.iconStyle, style, { styles }) {
            prefs.iconStyle = if (it == 1) Constants.IconStyle.GRAYSCALE else Constants.IconStyle.FULL_COLOR
            // Cached icons are baked at one style, so the old ones are now wrong.
            IconCache.clear()
            populateIconSettings()
            viewModel.refreshHome(false)
        }
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

    /**
     * Today's date in date format [index], formatted the way Home formats it
     * (HomeFragment.populateDateTime). Automatic uses the locale's own order. Every choice in the
     * date list is named this way, not with a canned sample.
     */
    private fun dateFormatLabel(index: Int): String {
        val today = SimpleDateFormat(Constants.DateFormat.pattern(index), Locale.getDefault()).format(Date()).replace(".,", ",")
        return if (index == Constants.DateFormat.AUTOMATIC) getString(R.string.date_format_automatic, today) else today
    }

    override fun onPowerStateChanged() {
        super.onPowerStateChanged()
        // The whole block, not just the saver row: Motion shows its forced state while saving.
        if (_binding != null) populateHomeLayoutOptions()
    }

    private fun refreshUltraBatterySaver() {
        val forced = LauncherMotion.followingSystemSaver(requireContext(), prefs)
        val value = binding.ultraBatterySaver
        val text = getString(when {
            forced -> R.string.ultra_saver_system_on
            prefs.ultraBatterySaver -> R.string.on
            else -> R.string.off
        })
        if (value.text != text) value.text = text
        // While Android forces it the manual switch cannot change anything, so neither half of
        // the row takes a tap; the stored manual choice is kept for afterwards.
        value.isEnabled = !forced
        rowOf(value).isEnabled = !forced
        saverFollowsSystemRow?.text = getString(if (prefs.saverFollowsSystem) R.string.on else R.string.off)
    }

    private fun populateHomeLayoutOptions() {
        binding.dateFormat.text = dateFormatLabel(prefs.dateFormatIndex)
        bindChoice(binding.dateFormat, prefs.dateFormatIndex,
            { (0..Constants.DateFormat.AUTOMATIC).map(::dateFormatLabel) }) {
            prefs.dateFormatIndex = it
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
        }
        // While the saver holds motion off, say so. Showing the stored choice made the row read as if
        // the animation were still running; the stored choice is untouched and returns afterwards.
        binding.homeAnimations.text = if (LauncherMotion.savingPower(requireContext(), prefs))
            getString(R.string.motion_off_saver)
        else getString(listOf(R.string.off, R.string.motion_liquid,
            R.string.motion_glide, R.string.motion_fade)[prefs.motionPreset])
        binding.homeScrollStyle.text = getString(R.string.home_scroll_state,
            getString(listOf(R.string.home_scroll_static, R.string.home_scroll_focus, R.string.home_scroll_soft)[prefs.homeScrollStyle]))
        refreshUltraBatterySaver()
        binding.unlockCount.text =
            getString(if (prefs.showUnlockCount) R.string.on else R.string.off)
        binding.hiddenAppsRow.text = String.format(Locale.ROOT, "%d", prefs.hiddenApps.size)
        binding.weatherRow.text = getString(if (prefs.showWeather) R.string.on else R.string.off)
        val units = listOf(getString(R.string.unit_celsius), getString(R.string.unit_fahrenheit))
        val unit = if (prefs.weatherFahrenheit) 1 else 0
        binding.temperatureUnit.text = units[unit]
        bindChoice(binding.temperatureUnit, unit, { units }) {
            prefs.weatherFahrenheit = it == 1
            // The cached reading is already formatted in the old unit, so it has to be re-fetched.
            prefs.weatherCached = ""
            prefs.weatherUpdatedAt = 0L
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
        }
        val spacings = listOf(0, 4, 8, 14)
        val spacingLabels = spacings.map {
            if (it == 0) getString(R.string.value_dp, 0) else getString(R.string.home_spacing_value, it)
        }
        val spacing = nearestIndex(spacings, prefs.homeSpacingExtra)
        binding.homeSpacing.text = spacingLabels[spacing]
        bindSlider(binding.homeSpacing, spacing, { spacingLabels }) {
            prefs.homeSpacingExtra = spacings[it]
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
        }
    }

    /**
     * Opens the notification panel from Settings.
     *
     * The panel is otherwise only reachable from a gesture, and no gesture points at it by
     * default - swipe down opens the system shade, which is the right default and not
     * something to change on someone's behalf. Without this row the screen would exist and
     * nobody would find it.
     */
    private fun openNotificationPanel() {
        try {
            findNavController().navigate(R.id.action_settingsFragment_to_notificationPanelFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Weather needs a location, so turning it on asks for one rather than switching on a feature
     * that would silently show nothing. Coarse only: the temperature is for the nearest town.
     */
    private fun requestInformationUsageAccess() {
        if ((prefs.infoShowScreenTime || prefs.showUnlockCount) && !requireContext().appUsagePermissionGranted())
            viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
    }

    private val informationLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (_binding != null) {
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
            requestInformationUsageAccess()
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val room = prefs.showWeather || prefs.informationWidgetCount < 4
        prefs.showWeather = granted && room
        if (granted && !room) requireContext().showToast(getString(R.string.information_widget_limit))
        if (!granted) requireContext().showToast(getString(R.string.weather_needs_location))
        populateHomeLayoutOptions()
        viewModel.refreshHome(false)
    }

    private fun toggleWeather() {
        if (prefs.showWeather) {
            prefs.disableWeatherAndClearCache()
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
            return
        }
        if (prefs.informationWidgetCount >= 4) {
            requireContext().showToast(getString(R.string.information_widget_limit))
            return
        }
        if (Weather.canLocate(requireContext())) {
            prefs.showWeather = true
            // Force the next resume to fetch rather than wait out the hourly gate.
            prefs.weatherUpdatedAt = 0L
            populateHomeLayoutOptions()
            viewModel.refreshHome(false)
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun showBadgeFilter() {
        // Reuses a fresh hidden-inclusive list instead of forcing a rescan (HomeFragment does too).
        viewModel.ensureAppList(true)
        runCatching {
            findNavController().navigate(
                R.id.action_settingsFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to Constants.FLAG_BADGE_FILTER,
                    Constants.Key.KEYBOARD_MODE to Constants.KeyboardMode.HIDE
                )
            )
        }.onFailure { it.printStackTrace() }
    }

    private fun configureBadgeRows() {
        listOf(
            binding.notificationBadgesLayout,
            binding.badgeStyleLayout,
            binding.badgeTapDetailsLayout,
            binding.badgeFilterLayout,
            binding.notificationAccessLayout,
        ).forEach { row ->
            row.applyFocusOutline(focusRingColor())
            for (index in 0 until row.childCount) {
                row.getChildAt(index).importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            ViewCompat.setAccessibilityDelegate(row, rowRole(when (row) {
                binding.notificationBadgesLayout -> { c: Context ->
                    prefs.showNotificationBadges && c.notificationAccessGranted() }
                binding.badgeTapDetailsLayout -> { _: Context -> prefs.badgeTapShowsDetails }
                else -> null
            }))
        }
    }

    private fun populateBadgeOptions() {
        val styles = listOf(getString(R.string.badge_style_count), getString(R.string.badge_style_dot))
        val style = if (prefs.badgeStyle == Constants.BadgeStyle.DOT) 1 else 0
        binding.badgeStyle.text = styles[style]
        bindChoice(binding.badgeStyle, style, { styles }, target = binding.badgeStyleLayout) {
            prefs.badgeStyle = if (it == 1) Constants.BadgeStyle.DOT else Constants.BadgeStyle.COUNT
            populateBadgeOptions()
        }
        binding.badgeStyleLayout.contentDescription = getString(R.string.a11y_pair,
            getString(R.string.badge_style), binding.badgeStyle.text)
        binding.badgeTapDetails.text =
            getString(if (prefs.badgeTapShowsDetails) R.string.on else R.string.off)
        val muted = prefs.badgeMutedApps.size
        binding.badgeFilter.text =
            if (muted == 0) getString(R.string.all_apps)
            else getString(R.string.badge_muted_count, muted)
        binding.badgeTapDetailsLayout.contentDescription = getString(R.string.a11y_pair,
            getString(R.string.badge_tap_details), binding.badgeTapDetails.text)
        binding.badgeFilterLayout.contentDescription = getString(R.string.a11y_pair,
            getString(R.string.badge_filter), binding.badgeFilter.text)
    }

    private fun populateNotificationBadges() {
        val accessGranted = requireContext().notificationAccessGranted()
        binding.notificationBadges.text = getString(
            if (prefs.showNotificationBadges && accessGranted) R.string.on else R.string.off
        )
        // The Notifications page's door to this setting shows the same state.
        binding.badgesSettings.text = binding.notificationBadges.text
        binding.notificationBadgesLayout.contentDescription = getString(R.string.a11y_pair,
            getString(R.string.show_badges), binding.notificationBadges.text)
        val accessState = getString(if (accessGranted) R.string.on else R.string.off)
        binding.notificationAccessState.text = accessState
        binding.notificationAccessLayout.contentDescription =
            getString(R.string.notification_access_status, accessState)
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
        // A revoked system grant reads Off even if the stored choice was On. One tap on that
        // visible Off state must open the access explanation, not silently toggle the pref.
        if (!context.notificationAccessGranted()) {
            showNotificationDialog()
            return
        }
        prefs.showNotificationBadges = !prefs.showNotificationBadges
        if (prefs.showNotificationBadges) {
            // rescanActive first: requestRebind is a no-op when the listener is already
            // bound, which is the common case here, and nothing would have badged until the
            // next notification arrived.
            if (!NotificationService.rescanActive()) runCatching {
                NotificationListenerService.requestRebind(context.notificationListenerComponent())
            }
        } else {
            // Badge counting is preference-gated in the service. Keep its connection for
            // the independent notification panel and media visualizer.
            NotificationCounts.clear()
        }
        populateNotificationBadges()
    }

    private fun openAccessibilityService() {
        // prefs.lockModeOn = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    /** A scale factor, so it reads as one: 1.0x, not a bare 1.0 beside "28 dp". */
    private fun formatScale(scale: Float): String = String.format(Locale.getDefault(), "%.1f×", scale)

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

    /** System is listed like the other two; it used to hide until the row was long pressed. */
    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        val modes = listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val labels = listOf(getString(R.string.light), getString(R.string.dark), getString(R.string.system_default))
        val index = modes.indexOf(appTheme).let { if (it < 0) modes.lastIndex else it }
        binding.appThemeText.text = labels[index]
        bindChoice(binding.appThemeText, index, { labels }) { updateTheme(modes[it]) }
    }

    /** 0.5x up to 1.5x, or 2.0x on a tablet, in tenths. */
    private fun populateTextSize() {
        val tenths = (5..if (isTablet(requireContext())) 20 else 15).toList()
        val index = nearestIndex(tenths, (prefs.textSizeScale * 10).roundToInt())
        binding.textSizeValue.text = formatScale(tenths[index] / 10f)
        bindSlider(binding.textSizeValue, index, { tenths.map { formatScale(it / 10f) } }) {
            prefs.textSizeScale = tenths[it] / 10f
            // Text size is read when the Activity is created, like the font and weight beside it.
            requireActivity().recreate()
        }
    }

    /**
     * Weight is a theme attribute, so the Activity is recreated to apply it - the same as
     * the font beside it.
     */
    private fun populateTextWeight() {
        val weights = listOf(Constants.TextWeight.REGULAR, Constants.TextWeight.MEDIUM, Constants.TextWeight.BOLD)
        val labels = listOf(R.string.weight_regular, R.string.weight_medium, R.string.weight_bold).map(::getString)
        val index = weights.indexOf(prefs.textWeight).coerceAtLeast(0)
        binding.textWeight.text = labels[index]
        bindSlider(binding.textWeight, index, { labels }) {
            prefs.textWeight = weights[it]
            populateTextWeight()
            requireActivity().recreate()
        }
    }

    private fun populateFont() {
        val fonts = listOf(Constants.Font.LIGHT, Constants.Font.REGULAR, Constants.Font.MEDIUM,
            Constants.Font.CONDENSED, Constants.Font.SERIF, Constants.Font.MONOSPACE, Constants.Font.INTER)
        val labels = listOf(R.string.font_light, R.string.font_regular, R.string.font_medium, R.string.font_condensed,
            R.string.font_serif, R.string.font_monospace, R.string.font_inter).map(::getString)
        val index = fonts.indexOf(prefs.fontIndex).coerceAtLeast(0)
        binding.fontChoice.text = labels[index]
        bindChoice(binding.fontChoice, index, { labels }) {
            prefs.fontIndex = fonts[it]
            populateFont()
            // The font is a theme attribute, so it can only change when the theme is applied.
            requireActivity().recreate()
        }
    }

    private fun populateAutoLaunchText() {
        binding.autoLaunchFromSearch.text =
            getString(if (prefs.autoLaunchFromSearch) R.string.on else R.string.off)
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
        val gravities = listOf(Gravity.START, Gravity.CENTER, Gravity.END)
        val labels = listOf(getString(R.string.left), getString(R.string.center), getString(R.string.right))
        val index = gravities.indexOf(prefs.homeAlignment).coerceAtLeast(0)
        binding.alignment.text = labels[index]
        bottomAlignmentRow?.text = getString(if (prefs.homeBottomAlignment) R.string.on else R.string.off)
        val slider = bindSlider(binding.alignment, index, { labels }) {
            viewModel.updateHomeAlignment(gravities[it])
        } ?: return
        // Long pressing the row gave the app list this alignment. A SeekBar takes every touch as a
        // drag, so a long press on it never fires: touch keeps it on the header row, which stays
        // out of TalkBack's way, and screen readers get it as a named action on the slider.
        rowOf(binding.alignment).apply {
            setOnLongClickListener { copyAlignmentToAppList(); true }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ViewCompat.addAccessibilityAction(slider, getString(R.string.alignment_for_app_list)) { _, _ ->
            copyAlignmentToAppList()
            true
        }
    }

    private fun copyAlignmentToAppList() {
        prefs.appLabelAlignment = prefs.homeAlignment
        findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
        requireContext().showToast(getString(R.string.alignment_changed))
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

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        viewModel.ensureAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    override fun onDestroyView() {
        featureDialog?.dismiss()
        featureDialog = null
        dialog?.dismiss()
        dialog = null
        // Remove from the SAME observer it was added to. binding.scrollLayout.viewTreeObserver
        // returns a different, dead instance once the view is detached, which makes the
        // removal a silent no-op and leaks the fragment.
        rowLabeller?.let { l ->
            rowLabellerObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(l)
        }
        rowLabellerObserver = null
        rowLabeller = null
        super.onDestroyView()
        _binding = null
    }

}
