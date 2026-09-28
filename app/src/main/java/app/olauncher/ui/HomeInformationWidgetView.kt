package app.olauncher.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isGone
import app.olauncher.R
import app.olauncher.helper.BatteryGlyph
import app.olauncher.helper.InformationPart
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.dpToPx
import app.olauncher.helper.withAlpha

/** The same value, condition and detail rhythm as the Home weather widget. */
class HomeInformationWidgetView(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
        contentDescription = null
    }
    private val headline = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon, LayoutParams(0, 0).apply { marginEnd = 8.dpToPx() })
    }
    val value = TextView(context, null, 0, R.style.TextDefault).apply {
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxWidth = 220.dpToPx()
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        // Tabular figures: '79%' and '100%' change width by digit count only, never per digit.
        fontFeatureSettings = "tnum"
    }
    val label = TextView(context, null, 0, R.style.TextDefault).apply {
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxWidth = 220.dpToPx()
        gravity = Gravity.START
    }
    val detail = TextView(context, null, 0, R.style.TextDefault).apply {
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxWidth = 220.dpToPx()
        gravity = Gravity.START
        visibility = View.GONE
    }
    private var iconState: Any? = null
    private var lastTextSp = Float.NaN

    /** What this view shows now; the tap handler reads it at tap time. */
    var part: InformationPart? = null
        private set

    /** The ACTION_CLICK label last applied, so a minute tick does not re-announce it. */
    var tapLabel: String? = null

    init {
        orientation = VERTICAL
        val pad = 4.dpToPx()
        setPadding(pad, pad, pad, pad)
        minimumHeight = 48.dpToPx()
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        headline.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(headline, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // MATCH_PARENT: a vertical LinearLayout of wrap_content width then sizes itself from the
        // headline and label only, and the detail wraps inside that. As WRAP_CONTENT, a long
        // state caption ("≈4.5 W", "Paused") widened the tile and re-wrapped the whole grid
        // every time charging paused or resumed.
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(part: InformationPart, title: String, color: Int, textSp: Float, valueWeight: Int) {
        this.part = part
        if (lastTextSp != textSp) {
            value.textSize = textSp * 1.25f
            label.textSize = textSp * 1.2f
            detail.textSize = textSp * 1.1f
            lastTextSp = textSp
        }
        if (value.text.toString() != part.value) value.text = part.value
        if (label.text.toString() != title) label.text = title
        val extra = part.caption
        if (detail.text.toString() != extra.orEmpty()) detail.text = extra.orEmpty()
        detail.visibility = if (extra.isNullOrBlank()) View.GONE else View.VISIBLE
        restyle(color, valueWeight)

        // The battery's name is "Battery", "Charging" or "Plugged in"; reserve the widest so the
        // tile, and every column after it, keeps one width through plugging in and out.
        val labelFloor = if (part.level >= 0) listOf(R.string.information_battery_short, R.string.home_battery_charging,
            R.string.home_battery_plugged).maxOf { label.paint.measureText(context.getString(it)) }.toInt() else 0
        if (label.minWidth != labelFloor) label.minWidth = labelFloor

        val iconSize = (label.textSize * 1.2f).toInt()
        val rowHeight = maxOf(iconSize, value.lineHeight)
        if (headline.minimumHeight != rowHeight) headline.minimumHeight = rowHeight
        // Battery always carries its glyph, sized and spaced like the weather icon, so its value
        // starts where the temperature does and plugging in never changes the widget's width.
        val battery = part.icon == R.drawable.ic_bolt || part.icon == R.drawable.ic_battery_outline
        val showIcon = battery || part.showIcon
        icon.visibility = if (showIcon) View.VISIBLE else View.GONE
        if (showIcon) {
            val state = listOf(part.icon, color, iconSize, part.level, part.charging)
            if (iconState != state) {
                icon.layoutParams = (icon.layoutParams as LayoutParams).apply {
                    width = iconSize
                    height = iconSize
                }
                icon.setImageDrawable(if (battery) BatteryGlyph(part.level, part.charging, color)
                    else AppCompatResources.getDrawable(context, part.icon)?.mutate()?.apply { setTint(color) })
                iconState = state
            }
        }
        // Visible order and words: the name, then what it reads ("Battery, 79%").
        val spoken = title + ", " + part.spoken
        if (contentDescription?.toString() != spoken) contentDescription = spoken
    }

    /**
     * LinearLayout measures the MATCH_PARENT detail at the full available width (one line), then
     * re-measures it at the tile's own width with its height pinned to that one line - so a caption
     * that wraps there (any caption at a large font scale) lost its second line. Measure it again
     * with the height free.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (detail.isGone) return
        detail.measure(MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        var needed = paddingTop + paddingBottom
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) needed += child.measuredHeight
        }
        if (needed > measuredHeight) setMeasuredDimension(measuredWidth, resolveSize(needed, heightMeasureSpec))
    }

    /**
     * Value full strength and one weight step heavier; name and detail at 70%. Home's theme pass
     * (tintTextTree, applyTextWeight) flattens every TextView under it, so it calls this again.
     */
    fun restyle(color: Int, valueWeight: Int) {
        val subdued = color.withAlpha(0xB3)
        if (value.currentTextColor != color) value.setTextColor(color)
        if (label.currentTextColor != subdued) label.setTextColor(subdued)
        if (detail.currentTextColor != subdued) detail.setTextColor(subdued)
        value.applyTextWeight(valueWeight)
    }
}

