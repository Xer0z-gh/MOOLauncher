package app.olauncher.helper

import android.animation.Animator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs

fun Context.motionEditor(prefs: Prefs, onSaved: () -> Unit): AlertDialog {
    val labels = listOf(R.string.off, R.string.motion_liquid, R.string.motion_glide, R.string.motion_fade)
    var selection = prefs.motionPreset
    var animation: Animator? = null
    // The Preview button's focus ring takes the colour the dialog paints its text in.
    val textColor = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
        else getColorFromAttr(R.attr.primaryColor)
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // Four named styles to compare, not a scale, so they are listed with the saved one checked.
    // A pick changes only the draft: Save writes it, Cancel keeps the saved motion, Preview plays it.
    val weight = Constants.TextWeight.value(prefs.textWeight)
    val style = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        // Settings pages' column: 20dp from the card at the start, 24dp at the end.
        setPaddingRelative(20.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
        labels.forEachIndexed { index, label ->
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(label)
                setTextAppearance(R.style.TextSmall)
                minHeight = 48.dpToPx()
                setOnClickListener { selection = index }
            }
            addView(button, RadioGroup.LayoutParams(-1, -2))
            if (index == selection) check(button.id)
        }
    }
    val preview = TextView(this, null, 0, R.style.TextLarge).apply {
        text = (1..prefs.homeAppsNum).map { prefs.getAppName(it) }.firstOrNull { it.isNotBlank() }
            ?: getString(R.string.browse_apps)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 12.dpToPx())
        minimumHeight = 64.dpToPx()
    }
    val play = TextView(this, null, 0, R.style.TextSmall).apply {
        text = getString(R.string.motion_preview)
        gravity = Gravity.CENTER
        // Inside the page's column like everything above it; unpadded, the paused note ran from
        // one card edge almost to the other.
        setPaddingRelative(24.dpToPx(), 0, 24.dpToPx(), 0)
        minimumHeight = 48.dpToPx()
        isFocusable = true
        applyFocusOutline(textColor)
        setOnClickListener {
            animation?.cancel()
            val effective = if (LauncherMotion.savingPower(context, prefs) || context.isSystemAnimationsDisabled() ||
                context.isEinkDisplay()) LauncherMotion.OFF else selection
            animation = LauncherMotion.transition(preview, true, effective).also { it.start() }
        }
    }
    content.addView(style)
    content.addView(preview)
    content.addView(play)
    // Every text at the Text weight, the way Settings pages set theirs.
    content.applyTextWeight(weight)
    if (LauncherMotion.savingPower(this, prefs) || isSystemAnimationsDisabled() || isEinkDisplay()) {
        play.text = getString(R.string.motion_paused)
        play.isEnabled = false
    } else {
        // Preview is a control, so it reads as one: a value's weight and a touch ripple.
        play.applyTextWeight(app.olauncher.ui.valueWeight(weight))
        play.background = android.util.TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            androidx.core.content.ContextCompat.getDrawable(this, it.resourceId)
        }
    }
    return AlertDialog.Builder(this).setTitle(R.string.motion_presets)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.save_changes) { _, _ -> prefs.motionPreset = selection; onSaved() }
        .create().also { dialog -> dialog.setOnDismissListener { animation?.cancel() } }
}
