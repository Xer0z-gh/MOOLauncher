package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import app.olauncher.R
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.dpToPx
import app.olauncher.helper.withAlpha

/**
 * A setting with a fixed set of values, as a stepped slider: one stop per value, the value named
 * in the row's value column while it moves. A drag commits on release (a font, theme or text size
 * change recreates the page); keyboard and TalkBack steps commit as they land. Monochrome in the
 * page's text colour: the filled part is the choice made, the dots are the choices there are.
 */
class StepSlider(context: Context) : AppCompatSeekBar(context) {
    private var labels: List<String> = emptyList()
    private var name: CharSequence = ""
    private var readout: TextView? = null
    private var onCommit: (Int) -> Unit = {}
    private var committed = 0
    private var tracking = false
    private var color = 0

    init {
        keyProgressIncrement = 1
        splitTrack = false
        background = null
        // The pressed thumb's radius: the resting thumb then starts on the label's edge above it.
        setPaddingRelative(9.dpToPx(), 0, 9.dpToPx(), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                show(value)
                if (fromUser && !tracking) commit(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                tracking = false
                commit(bar.progress)
            }
        })
    }

    /**
     * ProgressBar sizes itself from its drawable and ignores minimumHeight, which left a 14dp strip
     * to hit. 48dp tall, with the track and thumb centred in it (AbsSeekBar centres them).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val target = 48.dpToPx()
        if (measuredHeight < target && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
            setMeasuredDimension(measuredWidth, target)
    }

    /**
     * [labels] name every stop in order; [index] is the current one. [readout], when given, shows
     * the value (the row's own value column); [onCommit] receives the new index once it is chosen.
     */
    fun bind(name: CharSequence, labels: List<String>, index: Int, readout: TextView?, onCommit: (Int) -> Unit) {
        require(labels.size >= 2) { "a slider needs at least two values" }
        this.name = name
        this.labels = labels
        this.readout = readout
        this.onCommit = onCommit
        committed = index.coerceIn(0, labels.lastIndex)
        max = labels.lastIndex
        progress = committed
        (progressDrawable as? StepTrack)?.steps = labels.lastIndex
        show(committed)
    }

    /** Draws in [textColor]: filled track and thumb full strength, the rest and the stops muted. */
    fun tint(@ColorInt textColor: Int) {
        if (color == textColor && progressDrawable is StepTrack) return
        color = textColor
        progressDrawable = StepTrack(textColor, labels.lastIndex.coerceAtLeast(1))
        // Both states are 18dp boxes (the resting dot is 14dp inset by 2dp): AbsSeekBar centres
        // the thumb on a stop only when its offset is half the thumb's width, and a thumb that
        // changed size on press sat 6px off the first dot.
        thumb = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), dot(textColor, 18))
            addState(intArrayOf(), android.graphics.drawable.InsetDrawable(dot(textColor, 14), 2.dpToPx()))
        }
        thumbOffset = thumb.intrinsicWidth / 2
        applyFocusOutline(textColor)
        // Re-apply the level so the new drawable draws the current value.
        val value = progress
        progress = 0
        progress = value
    }

    /**
     * The track runs from where [label]'s text starts to where [readout]'s text ends, so its two
     * ends sit on the row's own edges (a Settings value ends 12dp in, its label starts 8dp in; a
     * fixed padding put the track's end past the value). With the value stacked under the name
     * (large text) the end mirrors the start. Once, after layout; rows do not move sideways.
     */
    fun alignTrack(label: TextView, readout: TextView) = doOnLayout {
        val me = IntArray(2).also(::getLocationInWindow)
        val l = IntArray(2).also(label::getLocationInWindow)
        val r = IntArray(2).also(readout::getLocationInWindow)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = if (rtl) me[0] + width - (l[0] + label.width - label.totalPaddingRight)
            else l[0] + label.totalPaddingLeft - me[0]
        val valueAtEnd = (readout.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.END
        val end = when {
            !valueAtEnd -> start
            rtl -> r[0] + readout.totalPaddingLeft - me[0]
            else -> me[0] + width - (r[0] + readout.width - readout.totalPaddingRight)
        }
        if (start > 0 && end > 0 && (paddingStart != start || paddingEnd != end)) {
            setPaddingRelative(start, paddingTop, end, paddingBottom)
            // AbsSeekBar places the track and thumb in onSizeChanged; a padding change alone left
            // the last stop where the old padding put it, 9px past the value.
            onSizeChanged(width, height, width, height)
        }
    }

    private fun dot(@ColorInt color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(sizeDp.dpToPx(), sizeDp.dpToPx())
    }

    private fun show(index: Int) {
        val label = labels.getOrNull(index) ?: return
        readout?.text = label
        contentDescription = name
        ViewCompat.setStateDescription(this, label)
    }

    private fun commit(index: Int) {
        if (index == committed) return
        committed = index
        onCommit(index)
    }
}

/** The track: muted line, filled line up to the value, a dot at every stop. */
private class StepTrack(@ColorInt private val color: Int, steps: Int) : Drawable() {
    var steps = steps
        set(value) { field = value.coerceAtLeast(1); invalidateSelf() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2f.dp }
    private val stop = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val y = b.exactCenterY()
        val end = b.left + b.width() * level / 10_000f
        line.color = color.withAlpha(0x40)
        canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, line)
        line.color = color
        canvas.drawLine(b.left.toFloat(), y, end, y, line)
        for (i in 0..steps) {
            val x = b.left + b.width() * i / steps.toFloat()
            stop.color = if (x <= end + 0.5f) color else color.withAlpha(0x73)
            canvas.drawCircle(x, y, 2.5f.dp, stop)
        }
    }

    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicHeight() = 12.dpToPx()

    private val Float.dp get() = this * android.content.res.Resources.getSystem().displayMetrics.density
}

/**
 * A labelled slider row for dialogs and panels: the name and the current value on one line, the
 * slider under them. Settings rows already have that line, so they use [attachSliderBelow].
 */
fun Context.stepSliderRow(title: CharSequence, labels: List<String>, index: Int, @ColorInt textColor: Int,
    onCommit: (Int) -> Unit): LinearLayout {
    val label = TextView(this, null, 0, R.style.TextSmall).apply {
        text = title
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setTextColor(textColor)
        applyTextWeight(settingsWeight())
    }
    val value = TextView(this, null, 0, R.style.TextSmallBold).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        fontFeatureSettings = "tnum"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setTextColor(textColor)
        applyTextWeight(valueWeight(settingsWeight()))
    }
    val slider = StepSlider(this).apply {
        tint(textColor)
        bind(title, labels, index, value, onCommit)
        alignTrack(label, value)
    }
    // Name and value share a line and the value takes the width it needs; with enlarged text the
    // value goes under the name instead, as Settings rows do (a fixed gap let them overlap).
    val stacked = resources.configuration.fontScale > 1.3f
    if (stacked) value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    val header = LinearLayout(this).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        addView(value, LinearLayout.LayoutParams(-2, -2).apply { if (!stacked) marginStart = 16.dpToPx() })
    }
    // The slider reaches 9dp past the text column on both sides, so alignTrack can put its stops
    // on the name's first letter and the value's end (label, value and slider sharing one edge left
    // it nothing to align, and the track sat 9dp inside); the thumb overhangs into that space.
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(slider, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = -9.dpToPx()
            marginEnd = -9.dpToPx()
        })
    }
}

/**
 * Puts a slider under an existing Settings row ([row] holds the label and [readout]), in the same
 * parent, and returns it. The row stops being a button: the slider is the control.
 */
fun attachSliderBelow(row: View, readout: TextView, name: CharSequence, labels: List<String>, index: Int,
    @ColorInt textColor: Int, label: TextView? = null, onCommit: (Int) -> Unit): StepSlider {
    val parent = row.parent as android.view.ViewGroup
    (parent.getChildAt(parent.indexOfChild(row) + 1) as? StepSlider)?.let { existing ->
        existing.tint(textColor)
        existing.bind(name, labels, index, readout, onCommit)
        return existing
    }
    readout.setOnClickListener(null)
    readout.isClickable = false
    readout.isFocusable = false
    readout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    readout.fontFeatureSettings = "tnum"
    val slider = StepSlider(row.context).apply {
        tint(textColor)
        bind(name, labels, index, readout, onCommit)
        if (label != null) alignTrack(label, readout)
    }
    val params = LinearLayout.LayoutParams(-1, -2).apply {
        (row.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { marginStart = it.marginStart; marginEnd = it.marginEnd }
    }
    parent.addView(slider, parent.indexOfChild(row) + 1, params)
    return slider
}

/** The stop for a stored value: its own index, or the nearest one when it is not in [values]. */
internal fun nearestIndex(values: List<Int>, current: Int): Int =
    values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
