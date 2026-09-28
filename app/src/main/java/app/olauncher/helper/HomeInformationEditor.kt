package app.olauncher.helper

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Prefs
import app.olauncher.ui.settingsWeight
import app.olauncher.ui.stepSliderRow
import app.olauncher.ui.switchRow

/**
 * The editor is a local draft; Cancel leaves size and selections unchanged.
 *
 * One scrolling page of the same rows as Settings: a switch per widget, the size slider and the
 * tap switch. The widget list used to be the dialog's stock multi-choice list, with checkboxes in
 * the system font above rows in the app's own; the buttons stay pinned below the scroll.
 */
fun Context.homeInformationEditor(prefs: Prefs, onSaved: (needsWeatherPermission: Boolean) -> Unit): AlertDialog {
    val labels = listOf(R.string.information_date, R.string.information_battery,
        R.string.information_weather, R.string.information_screen_time, R.string.information_unlocks,
        R.string.information_alarm).map(::getString)
    val selected = booleanArrayOf(prefs.infoShowDate, prefs.infoShowBattery, prefs.showWeather,
        prefs.infoShowScreenTime, prefs.showUnlockCount, prefs.infoShowAlarm)
    var size = prefs.informationSize
    var tapOpens = prefs.widgetTapOpens
    // Tracks, thumbs and switch values are drawn in the colour the dialog paints its text in.
    val textColor = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
        else getColorFromAttr(R.attr.primaryColor)
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Settings pages' column: names 20dp from the card, values 24dp.
        setPaddingRelative(20.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
        clipChildren = false
        clipToPadding = false
    }
    val gap = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dpToPx() } }
    labels.forEachIndexed { index, label ->
        // The date is its own line; the other five share Home's four widget slots.
        val allow = { on: Boolean ->
            val full = index > 0 && on && selected.drop(1).count { it } >= 4
            // Visible, not only spoken: a switch that refused to move said nothing.
            if (full) showToast(getString(R.string.home_information_limit))
            !full
        }
        controls.addView(switchRow(label, selected[index], textColor, allow) { selected[index] = it },
            gap().apply { if (index == 0) topMargin = 0 })
    }
    controls.addView(TextView(this, null, 0, R.style.SettingsDescription).apply {
        text = getString(R.string.home_information_limit)
        setTextColor(textColor)
        applyTextWeight(settingsWeight())
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dpToPx() })
    controls.addView(stepSliderRow(getString(R.string.widget_text_size), listOf(R.string.information_small,
        R.string.information_medium, R.string.information_large).map(::getString), size, textColor) { size = it }, gap())
    // Yes or no, so a switch; the size above is a scale, so a slider. Both change only the draft.
    controls.addView(switchRow(getString(R.string.home_widget_tap_opens), tapOpens, textColor) { tapOpens = it }, gap())
    return AlertDialog.Builder(this)
        .setTitle(R.string.home_information)
        .setView(ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(48.dpToPx())
            addView(controls)
        })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.save_changes) { _, _ ->
            prefs.infoShowDate = selected[0]
            prefs.infoShowBattery = selected[1]
            if (selected[2]) prefs.showWeather = true else prefs.disableWeatherAndClearCache()
            prefs.infoShowScreenTime = selected[3]
            prefs.showUnlockCount = selected[4]
            prefs.infoShowAlarm = selected[5]
            prefs.informationSize = size
            prefs.widgetTapOpens = tapOpens
            onSaved(selected[2] && !Weather.hasLocationPermission(this))
        }.create()
}
