package app.olauncher.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isEmpty
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.FOCUS_TICK_CHOICES
import app.olauncher.data.Prefs
import app.olauncher.ui.needsTimedFocusTick
import app.olauncher.ui.choiceRow
import app.olauncher.ui.settingsWeight
import app.olauncher.ui.stepSliderRow
import app.olauncher.ui.switchRow

/** Each surface exposes its own controls. Changes apply immediately and remain bounded. */
class PanelSettings(private val context: Context, private val prefs: Prefs,
    private val changed: () -> Unit, private val audioPermission: () -> Unit) {
    enum class Page { HOME, APPS, SEARCH, NOTIFICATIONS, WEATHER, VISUALIZER }
    private var dialog: AlertDialog? = null
    /** [scale]: the values are points on one scale (sizes, spacing, strength, interval) - a slider fits. */
    private data class Option(val title: Int, val labels: List<Int>, val value: Int, val scale: Boolean = false, val save: (Int) -> Unit)
    private fun toggle(title: Int, value: Boolean, save: (Boolean) -> Unit) = Option(title,
        listOf(R.string.panel_off, R.string.panel_on), if (value) 1 else 0) { save(it == 1) }
    fun close() { dialog?.dismiss(); dialog = null }
    fun show(page: Page) {
        close()
        val options = options(page)
        val title = when(page) {
            Page.HOME -> R.string.customize_home
            Page.APPS -> R.string.customize_apps
            Page.SEARCH -> R.string.customize_search
            Page.NOTIFICATIONS -> R.string.customize_notifications
            Page.WEATHER -> R.string.customize_weather
            Page.VISUALIZER -> R.string.customize_visualizer
        }
        buildPage(page, title, options)
    }

    /** The page's settings as they stand now; some appear only when another is on. */
    private fun options(page: Page): List<Option> {
        val sizes = listOf(R.string.panel_small, R.string.panel_medium, R.string.panel_large)
        return when (page) {
            Page.HOME -> listOfNotNull(
                toggle(R.string.panel_icons, prefs.showHomeIcons) { prefs.showHomeIcons = it },
                Option(R.string.home_order, listOf(R.string.home_order_manual, R.string.home_order_alphabetical),
                    if (prefs.autoSortHomeApps) 1 else 0) { prefs.autoSortHomeApps = it == 1 },
                Option(R.string.panel_home_layout, listOf(R.string.home_scroll_static, R.string.home_scroll_focus, R.string.panel_focus_soft), prefs.homeScrollStyle) { prefs.homeScrollStyle = it },
                // Below the layout that reveals them, so a choice never moves the rows above it.
                if (prefs.homeScrollStyle == 0) null else
                    toggle(R.string.home_scroll_haptics, prefs.homeScrollHaptics) { prefs.homeScrollHaptics = it },
                // Only where the pulse length is felt: vibration on, and a motor without a native
                // tick (on one that has it, Android plays its own tick and ignores the length).
                if (prefs.homeScrollStyle == 0 || !prefs.homeScrollHaptics || !needsTimedFocusTick(context)) null else
                    Option(R.string.home_focus_tick, listOf(R.string.home_focus_tick_light, R.string.home_focus_tick_medium,
                        R.string.home_focus_tick_strong), FOCUS_TICK_CHOICES.indexOf(prefs.focusTickMs).coerceAtLeast(1), scale = true) {
                        prefs.focusTickMs = FOCUS_TICK_CHOICES[it]
                    },
                Option(R.string.widget_text_size, sizes, prefs.informationSize, scale = true) { prefs.informationSize = it },
                Option(R.string.panel_weather_size, listOf(R.string.panel_small, R.string.panel_medium, R.string.panel_wide), prefs.weatherSize, scale = true) { prefs.weatherSize = it })
            Page.APPS -> listOf(
                Option(R.string.apps_browser_layout, listOf(R.string.apps_layout_legacy, R.string.apps_layout_card),
                    prefs.appsBrowserLayout) { prefs.appsBrowserLayout = it },
                toggle(R.string.panel_icons, prefs.showDrawerIcons) { prefs.showDrawerIcons = it },
                Option(R.string.app_sort_order, listOf(R.string.panel_categories, R.string.sort_az, R.string.sort_za, R.string.sort_installed), prefs.drawerSort) { prefs.drawerSort = it },
                Option(R.string.panel_text_size, sizes, listOf(20,24,28).indexOf(prefs.drawerTextSize).coerceAtLeast(0), scale = true) { prefs.drawerTextSize = listOf(20,24,28)[it] },
                Option(R.string.panel_spacing, listOf(R.string.spacing_compact, R.string.panel_comfortable, R.string.panel_roomy), prefs.drawerRowSize, scale = true) { prefs.drawerRowSize = it })
            Page.SEARCH -> listOf(
                toggle(R.string.panel_icons, prefs.searchShowIcons) { prefs.searchShowIcons = it },
                Option(R.string.panel_text_size, sizes, listOf(20,24,28).indexOf(prefs.searchTextSize).coerceAtLeast(0), scale = true) { prefs.searchTextSize = listOf(20,24,28)[it] },
                toggle(R.string.panel_keyboard, prefs.autoShowKeyboard) { prefs.autoShowKeyboard = it },
                toggle(R.string.panel_auto_launch, prefs.autoLaunchFromSearch) { prefs.autoLaunchFromSearch = it })
            Page.NOTIFICATIONS -> listOf(
                Option(R.string.panel_default_view, listOf(R.string.notifications_filtered, R.string.notifications_all), if (prefs.panelMode == 1) 0 else 1) { prefs.panelMode = it + 1 },
                Option(R.string.panel_filter_position, listOf(R.string.panel_top, R.string.panel_bottom), if (prefs.panelFiltersBottom) 1 else 0) { prefs.panelFiltersBottom = it == 1 },
                Option(R.string.panel_filter_order, listOf(R.string.panel_filtered_first, R.string.panel_all_first), if (prefs.panelAllFirst) 1 else 0) { prefs.panelAllFirst = it == 1 },
                toggle(R.string.panel_icons, prefs.panelShowIcons) { prefs.panelShowIcons = it },
                toggle(R.string.panel_preview, prefs.panelShowPreview) { prefs.panelShowPreview = it },
                toggle(R.string.panel_media_artwork, prefs.panelMediaArtwork) { prefs.panelMediaArtwork = it },
                toggle(R.string.panel_time, prefs.panelShowTime) { prefs.panelShowTime = it },
                toggle(R.string.panel_compact, prefs.panelCompact) { prefs.panelCompact = it },
                toggle(R.string.panel_swipe_dismiss, prefs.panelSwipeDismiss) { prefs.panelSwipeDismiss = it })
            Page.WEATHER -> listOf(
                Option(R.string.panel_weather_size, listOf(R.string.panel_small, R.string.panel_medium, R.string.panel_wide), prefs.weatherSize, scale = true) { prefs.weatherSize = it },
                Option(R.string.temperature_unit, listOf(R.string.unit_celsius, R.string.unit_fahrenheit),
                    if (prefs.weatherFahrenheit) 1 else 0) { prefs.weatherFahrenheit = it == 1 },
                Option(R.string.weather_refresh_interval,
                    listOf(R.string.weather_refresh_hourly, R.string.weather_refresh_three_hours, R.string.weather_refresh_six_hours),
                    listOf(60, 180, 360).indexOf(prefs.weatherRefreshMinutes).coerceAtLeast(0), scale = true) {
                    prefs.weatherRefreshMinutes = listOf(60, 180, 360)[it]
                })
            Page.VISUALIZER -> if (!prefs.visualizerEnabled) listOf(
                toggle(R.string.panel_visualizer_enabled, false) { prefs.visualizerEnabled = it })
            else listOf(
                toggle(R.string.panel_visualizer_enabled, prefs.visualizerEnabled) { prefs.visualizerEnabled = it },
                toggle(R.string.panel_on_home, prefs.visualizerHome) { prefs.visualizerHome = it },
                toggle(R.string.panel_on_notifications, prefs.visualizerPanel) { prefs.visualizerPanel = it },
                Option(R.string.panel_visualizer_style, listOf(R.string.panel_wave, R.string.panel_bars, R.string.panel_raw_wave), prefs.visualizerStyle) { prefs.visualizerStyle = it },
                Option(R.string.panel_visualizer_color, listOf(R.string.panel_follow_theme, R.string.panel_monochrome, R.string.panel_color), prefs.visualizerColor) { prefs.visualizerColor = it },
                Option(R.string.panel_visualizer_height, sizes, prefs.visualizerHeight, scale = true) { prefs.visualizerHeight = it })
        }
    }

    private fun buildPage(page: Page, title: Int, options: List<Option>) {
        // One page: a switch for on/off, a slider for a scale, a choice for separate options. Each
        // change applies at once; the page is rebuilt only when a change shows or hides a setting
        // (Focus layout reveals the tick controls), so a keyboard or TalkBack user keeps focus.
        val text = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
            else context.getColorFromAttr(R.attr.primaryColor)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Settings pages' column: labels 20dp from the card, values 24dp; rows 12dp apart.
            setPaddingRelative(20.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
            clipChildren = false
            clipToPadding = false
        }
        val gap = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dpToPx() } }
        fun render(options: List<Option>) {
            list.removeAllViews()
            options.forEach { option ->
                val after = { rebuildIfShapeChanged(page, options, list, ::render) }
                val name = context.getString(option.title)
                val row = when {
                    option.labels == TOGGLE -> context.switchRow(name, option.value == 1, text) {
                        option.save(if (it) 1 else 0); changed(); after()
                    }
                    option.scale -> context.stepSliderRow(name, option.labels.map(context::getString), option.value, text) {
                        option.save(it); changed(); after()
                    }
                    else -> context.choiceRow(name, option.labels.map(context::getString), option.value, text) {
                        option.save(it); changed(); after()
                    }
                }
                list.addView(row, gap().apply { if (list.isEmpty()) topMargin = 0 })
            }
            if (page == Page.VISUALIZER && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                list.addView(actionRow(R.string.visualizer_audio_access, text) { audioPermission() }, gap())
            if (page == Page.VISUALIZER && !context.notificationAccessGranted())
                list.addView(actionRow(R.string.notification_access, text) {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        .onFailure { context.showToast(context.getString(R.string.notification_access_needed)) }
                }, gap())
        }
        render(options)
        dialog = AlertDialog.Builder(context).setTitle(title)
            .setView(android.widget.ScrollView(context).apply {
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(48.dpToPx())
                addView(list)
            })
            .create().also { it.showSettingsPage() }
    }

    /** Re-reads the page's settings and rebuilds only if one appeared or went (not on a value change). */
    private fun rebuildIfShapeChanged(page: Page, shown: List<Option>, list: LinearLayout, render: (List<Option>) -> Unit) {
        val now = options(page)
        if (now.map { it.title } != shown.map { it.title }) render(now)
    }

    /** A plain action (open a permission screen): the whole row is the button. */
    private fun actionRow(title: Int, text: Int, action: () -> Unit): View =
        TextView(context, null, 0, R.style.TextSmall).apply {
            setText(title)
            minHeight = 48.dpToPx()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(text)
            applyTextWeight(context.settingsWeight())
            background = ripple()
            applyFocusOutline(text)
            setOnClickListener { action() }
        }

    private fun ripple() = android.util.TypedValue().let {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        ContextCompat.getDrawable(context, it.resourceId)
    }

    private companion object {
        val TOGGLE = listOf(R.string.panel_off, R.string.panel_on)
    }
}
