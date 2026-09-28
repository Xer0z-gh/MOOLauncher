package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.olauncher.helper.isSystemAnimationsDisabled
import kotlin.math.min

/** Content-sized information row. Wrap only when it cannot fit, never into equal tiles. */
class HomeWidgetGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    var size = 1
        set(value) { if (field != value) { field = value.coerceIn(0, 2); requestLayout() } }
    var weatherSize = 1
        set(value) { if (field != value) { field = value.coerceIn(0, 2); requestLayout() } }
    var alignment = Gravity.START
        set(value) { if (field != value) { field = value; requestLayout() } }
    private val gap get() = (16 * resources.displayMetrics.density).toInt()
    val rowGap get() = (8 * resources.displayMetrics.density).toInt()
    private var rows = emptyList<List<View>>()
    private var rowHeights = emptyList<Int>()
    private var pairedColumns: Pair<Int, Int>? = null
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val width = min(MeasureSpec.getSize(widthMeasureSpec),
            ((if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                600 else 400) * density).toInt())
        val views = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.take(4)
        val measuredRows = mutableListOf<MutableList<View>>()
        var row = mutableListOf<View>()
        var used = 0
        views.forEach { child ->
            // Guarded: setMinimumHeight/Width request a layout even for the same value, and from
            // inside onMeasure that force-flags every child, defeating the measure cache for the
            // siblings whose text did not change.
            val minH = ((56 + size * 8) * density).toInt()
            if (child.minimumHeight != minH) child.minimumHeight = minH
            val minW = min(width, ((if (child.id == app.olauncher.R.id.homeWeather)
                88 + weatherSize * 24 else 48) * density).toInt())
            if (child.minimumWidth != minW) child.minimumWidth = minW
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (row.isNotEmpty() && used + gap + child.measuredWidth > width) {
                measuredRows.add(row); row = mutableListOf(); used = 0
            }
            used += (if (row.isEmpty()) 0 else gap) + child.measuredWidth
            row.add(child)
        }
        if (row.isNotEmpty()) measuredRows.add(row)
        rows = measuredRows
        // When four widgets wrap into two pairs, use the same column starts
        // without stretching the content-sized widgets into equal tiles.
        val pairs = rows.filter { it.size == 2 }
        pairedColumns = if (pairs.size >= 2) {
            val first = pairs.maxOf { it[0].measuredWidth }
            val second = pairs.maxOf { it[1].measuredWidth }
            if (first + gap + second <= width) first to second else null
        } else null
        rowHeights = rows.map { cells -> cells.maxOf { it.measuredHeight } }
        // Equal row heights preserve hit areas, but text remains top-aligned so the
        // weather range cannot push its headline above the other widget values.
        rows.forEachIndexed { i, cells -> cells.forEach { child ->
            child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeights[i], MeasureSpec.EXACTLY))
        } }
        setMeasuredDimension(width, rowHeights.sum() + rowGap * (rows.size - 1).coerceAtLeast(0))
    }
    @android.annotation.SuppressLint("RtlHardcoded") // getAbsoluteGravity has already resolved START/END for the current layout direction.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = 0
        val horizontal = Gravity.getAbsoluteGravity(alignment, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        rows.forEachIndexed { index, row ->
            val columns = pairedColumns?.takeIf { row.size == 2 }
            val rowWidth = if (columns != null) columns.first + gap + columns.second
                else row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            val offset = when (horizontal) {
                Gravity.RIGHT -> measuredWidth - rowWidth
                Gravity.CENTER_HORIZONTAL -> (measuredWidth - rowWidth) / 2
                else -> 0
            }
            var used = 0
            row.forEachIndexed { column, child ->
                val x = offset + if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    rowWidth - used - child.measuredWidth else used
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                used += (if (columns == null) child.measuredWidth
                    else if (column == 0) columns.first else columns.second) + gap
            }
            y += rowHeights[index] + rowGap
        }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    /** Height of the whole rows that fit in [limit] (always at least the first), from the last measure. */
    fun wholeRowsHeight(limit: Int): Int = wholeRowsFit(rowHeights, rowGap, limit)

    /** The scroll offset of each row's top, from the last measure. */
    fun rowTops(): List<Int> = rowTopsOf(rowHeights, rowGap)
}

/** Where each row starts: the rows above it plus a gap after each. */
internal fun rowTopsOf(rowHeights: List<Int>, rowGap: Int): List<Int> =
    rowHeights.runningFold(0) { top, h -> top + h + rowGap }.dropLast(1)

/**
 * Sum of the leading rows (with the gaps between them) that fit in [limit]. The first row always
 * counts, so a cap smaller than one row still shows that row whole instead of cutting its text,
 * and the result is never shorter than the tallest row, so every row can be scrolled into view
 * whole (at 200% text a two-line "Offline" weather row set a 241 px viewport over a 338 px
 * charging battery row, which could then never show its last line).
 */
internal fun wholeRowsFit(rowHeights: List<Int>, rowGap: Int, limit: Int): Int {
    var used = 0
    for ((index, row) in rowHeights.withIndex()) {
        val next = used + (if (index == 0) 0 else rowGap) + row
        if (index > 0 && next > limit) break
        used = next
    }
    return maxOf(used, rowHeights.maxOrNull() ?: 0)
}

/**
 * Large accessibility text can scroll inside the same bounded information area. The cap snaps to
 * whole widget rows, never ending mid-glyph; with more below, the top of the next row shows under
 * a fading edge, and a scrollbar that stays up says the same.
 */
class HomeWidgetContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private var thumbColor = 0

    /** The default thumb was light grey on white (2.2:1); draw it in Home's text colour at 60%. */
    fun tintScrollbar(textColor: Int) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q || thumbColor == textColor) return
        thumbColor = textColor
        verticalScrollbarThumbDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 0x99))
            cornerRadius = 2 * resources.displayMetrics.density
        }
    }

    private var dragging = false
    private val settle = Runnable { snapToRow() }

    /**
     * Where a drag or fling stops is arbitrary, so the rows above and below were both left half
     * under the fades. Once it settles, ease to the nearest row's top: that row reads clear and
     * the next one peeks below.
     */
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        removeCallbacks(settle)
        if (!dragging) postDelayed(settle, 80)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Observes the gesture; ScrollView still handles it.
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { dragging = true; removeCallbacks(settle) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                postDelayed(settle, 80)
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun snapToRow() {
        val grid = getChildAt(0) as? HomeWidgetGrid ?: return
        val maxScroll = (grid.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
        val target = grid.rowTops().map { it.coerceAtMost(maxScroll) }.minByOrNull { kotlin.math.abs(it - scrollY) } ?: return
        if (target == scrollY) return
        if (context.isSystemAnimationsDisabled()) scrollTo(0, target) else smoothScrollTo(0, target)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        super.onDetachedFromWindow()
    }

    init {
        isVerticalScrollBarEnabled = true
        // The thumb is only drawn when the content overflows, so this costs nothing otherwise.
        isScrollbarFadingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((16 * resources.displayMetrics.density).toInt())
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = min(MeasureSpec.getSize(widthMeasureSpec),
            ((if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                600 else 400) * resources.displayMetrics.density).toInt())
        val capDp = if (resources.configuration.screenHeightDp < 480) 96 else 200
        val cap = (capDp * resources.displayMetrics.density).toInt()
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else min(cap, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val grid = getChildAt(0) as? HomeWidgetGrid ?: return
        if (grid.measuredHeight <= measuredHeight) return
        // The content overflows the cap: end on a row boundary instead of mid-glyph.
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        val rows = grid.wholeRowsHeight(height - paddingTop - paddingBottom)
        // With more below, also show the gap and the first 16 dp of the next row: the fade then
        // dims the row that is coming (a cue that there is more) instead of the last whole row's
        // label, which at 200% text is the only one on screen.
        val peek = if (grid.measuredHeight > rows) min(grid.measuredHeight - rows, grid.rowGap + verticalFadingEdgeLength) else 0
        val snapped = (rows + peek + paddingTop + paddingBottom).coerceAtMost(limit)
        if (snapped != measuredHeight) setMeasuredDimension(measuredWidth, snapped)
    }
}
