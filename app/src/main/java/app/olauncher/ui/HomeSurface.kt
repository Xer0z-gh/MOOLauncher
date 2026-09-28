package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.olauncher.helper.dpToPx
import kotlin.math.abs

/** Home swipes work identically over labels, the clock and empty space. */
class HomeSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onHorizontalSwipe: (Boolean) -> Unit = {}
    var onTouchFinished: () -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axis = 0
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; axis = 0 }
            MotionEvent.ACTION_MOVE -> if (axis == 0) {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > slop && dx > dy * 1.5f) {
                    axis = 1
                    MotionEvent.obtain(event).also { cancel ->
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                } else if (dy > slop) axis = 2
            }
            MotionEvent.ACTION_UP -> if (axis == 1) {
                axis = 0
                val dx = event.x - downX
                onTouchFinished()
                if (abs(dx) >= 48.dpToPx()) onHorizontalSwipe(dx > 0)
                return true
            }
            MotionEvent.ACTION_CANCEL -> axis = 0
        }
        val handled = if (axis == 1) true else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            onTouchFinished()
        return handled
    }
}
