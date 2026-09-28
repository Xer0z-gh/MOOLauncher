package app.olauncher.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showForLauncher

/*
 * Rows for settings built in code (Customize pages, editors), in the Settings rows' shape: the
 * name on the start edge, the value bold on the end, the whole row one 48dp control. Which row a
 * setting gets depends on its values: on/off is a switch, separate options are a choice, and a
 * scale (sizes, spacing, strength) is a stepSliderRow.
 */

/**
 * An on/off setting: the name and On/Off, the whole row a switch. [allow] can refuse a change
 * before it happens (the Home information editor's four-widget limit).
 */
fun Context.switchRow(title: CharSequence, on: Boolean, @ColorInt textColor: Int,
    allow: (Boolean) -> Boolean = { true }, onToggle: (Boolean) -> Unit): View {
    var state = on
    val value = valueText(textColor)
    val row = pairRow(title, value, textColor)
    fun show() {
        value.setText(if (state) R.string.panel_on else R.string.panel_off)
        row.contentDescription = title
    }
    ViewCompat.setAccessibilityDelegate(row, role(android.widget.Switch::class.java.name) { state })
    row.setOnClickListener {
        if (!allow(!state)) return@setOnClickListener
        state = !state
        show()
        onToggle(state)
    }
    show()
    return row
}

/**
 * A setting whose values are separate options (a layout, an order, a style): the name and the
 * current option; a tap lists the options with the current one checked.
 */
fun Context.choiceRow(title: CharSequence, labels: List<String>, index: Int, @ColorInt textColor: Int, onPick: (Int) -> Unit): View {
    var current = index.coerceIn(0, labels.lastIndex)
    val value = valueText(textColor)
    val row = pairRow(title, value, textColor)
    fun show() {
        value.text = labels[current]
        row.contentDescription = getString(R.string.a11y_pair, title, labels[current])
    }
    ViewCompat.setAccessibilityDelegate(row, role(android.widget.Button::class.java.name, null))
    row.setOnClickListener {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { choice, picked ->
                choice.dismiss()
                if (picked == current) return@setSingleChoiceItems
                current = picked
                show()
                onPick(picked)
            }.create().also { it.showForLauncher() }
    }
    show()
    return row
}

/**
 * The user's Text weight as Settings pages apply it: names at the weight, values one step
 * heavier. Without it these pages rendered lighter than the Settings page that opened them.
 */
internal fun Context.settingsWeight() = Constants.TextWeight.value(Prefs(this).textWeight)
internal fun valueWeight(weight: Int) = (weight + 200).coerceAtMost(900)

private fun Context.valueText(@ColorInt textColor: Int) = TextView(this, null, 0, R.style.TextSmallBold).apply {
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    setTextColor(textColor)
    applyTextWeight(valueWeight(settingsWeight()))
}

/**
 * The name on the start edge and [value] on the end, taking the width it needs ("Category
 * sections"); with enlarged text the value goes under the name, as Settings rows do.
 */
private fun Context.pairRow(title: CharSequence, value: TextView, @ColorInt textColor: Int): LinearLayout {
    val stacked = resources.configuration.fontScale > 1.3f
    if (stacked) value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    return LinearLayout(this).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 48.dpToPx()
        isClickable = true
        isFocusable = true
        background = TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            ContextCompat.getDrawable(context, it.resourceId)
        }
        applyFocusOutline(textColor)
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = title
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(textColor)
            applyTextWeight(settingsWeight())
        }, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        addView(value, LinearLayout.LayoutParams(-2, -2).apply { if (!stacked) marginStart = 16.dpToPx() })
    }
}

private fun role(className: String, checked: (() -> Boolean)?) = object : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = className
        if (checked != null) {
            info.isCheckable = true
            info.isChecked = checked()
        }
    }
}
