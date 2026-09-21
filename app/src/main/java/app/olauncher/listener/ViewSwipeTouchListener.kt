package app.olauncher.listener

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import app.olauncher.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal open class ViewSwipeTouchListener(c: Context?, v: View) : OnTouchListener {
    private var longPressOn = false
    private val gestureDetector: GestureDetector

    /** Screen density, so the swipe thresholds below can be expressed in dp. */
    private val density: Float = c?.resources?.displayMetrics?.density ?: 1f

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> view.isPressed = true
            MotionEvent.ACTION_UP -> view.isPressed = false
        }
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private inner class GestureListener(private val view: View) : SimpleOnGestureListener() {
        /**
         * How far a gesture has to travel, in dp, before it counts as a swipe.
         *
         * This was 100 raw pixels - 35dp on a 450dpi phone, 100dp on a 160dpi one - so the
         * same flick meant "swipe" on one device and "nothing happened" on another. See the
         * matching comment in OnSwipeTouchListener; both listeners had the same constant.
         */
        private val swipeThresholdPx = (36 * density).toInt()

        /** Velocity is px/second, so it needs the same conversion for the same reason. */
        private val swipeVelocityPx = (36 * density).toInt()

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClick(view)
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            longPressOn = true
            GlobalScope.launch {
                delay(Constants.LONG_PRESS_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (isActive && longPressOn)
                        onLongClick(view)
                }
            }
            super.onLongPress(e)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            try {
                val diffY = event2.y - (event1?.y ?: 0F)
                val diffX = event2.x - (event1?.x ?: 0F)
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > swipeThresholdPx && abs(velocityX) > swipeVelocityPx) {
                        if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    }
                } else {
                    if (abs(diffY) > swipeThresholdPx && abs(velocityY) > swipeVelocityPx) {
                        if (diffY < 0) onSwipeUp() else onSwipeDown()
                    }
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return false
        }
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
    open fun onSwipeUp() {}
    open fun onSwipeDown() {}
    open fun onLongClick(view: View) {}
    private fun onDoubleClick() {}
    open fun onClick(view: View) {}

    init {
        gestureDetector = GestureDetector(c, GestureListener(v))
    }
}