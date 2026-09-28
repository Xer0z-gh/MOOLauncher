package app.olauncher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.helper.dpToPx
import kotlin.math.abs

/** Decide an axis once; cancelled drags never become clicks or launcher actions. */
class HorizontalSwipeListener(context: Context, private val onSwipe: (right: Boolean) -> Unit) :
    RecyclerView.SimpleOnItemTouchListener() {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var axis = 0
    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; axis = 0 }
            MotionEvent.ACTION_MOVE -> if (axis == 0) {
                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                if (dx > slop && dx > dy * 1.5f) axis = 1
                else if (dy > slop) axis = 2
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> axis = 0
        }
        return axis == 1
    }
    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val distance = event.x - startX
            if (axis == 1 && abs(distance) >= 48.dpToPx()) onSwipe(distance > 0)
            axis = 0
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) axis = 0
    }
}
