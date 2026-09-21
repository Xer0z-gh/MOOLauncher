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

/*
Swipe, double tap and long press touch listener for a view
Source: https://www.tutorialspoint.com/how-to-handle-swipe-gestures-in-kotlin
*/

internal open class OnSwipeTouchListener(c: Context?) : OnTouchListener {
    private var longPressOn = false

    //    private var doubleTapOn = false
    private val gestureDetector: GestureDetector

    /** Screen density, so the swipe thresholds below can be expressed in dp. */
    private val density: Float = c?.resources?.displayMetrics?.density ?: 1f

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        if (motionEvent.action == MotionEvent.ACTION_UP)
            longPressOn = false
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private inner class GestureListener : SimpleOnGestureListener() {
        /**
         * How far a gesture has to travel, in dp, before it counts as a swipe.
         *
         * This was 100 raw pixels, which is 35dp on a 450dpi phone and 100dp on a 160dpi one -
         * so the same flick meant "swipe" on one device and "nothing happened" on another, and
         * the cheap, low-density phones this launcher is for were the ones that had to work
         * hardest. 36dp is what 100px already was on the screen it was developed against, so
         * that device behaves exactly as before.
         */
        private val swipeThresholdPx = (36 * density).toInt()

        /** Velocity is px/second, so it needs the same conversion for the same reason. */
        private val swipeVelocityPx = (36 * density).toInt()

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
//            if (doubleTapOn) {
//                doubleTapOn = false
//                onTripleClick()
//            }
            onClick()
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
//            doubleTapOn = true
//            Timer().schedule(Constants.TRIPLE_TAP_DELAY_MS) {
//                if (doubleTapOn) {
//                    doubleTapOn = false
//                    onDoubleClick()
//                }
//            }
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            longPressOn = true
            GlobalScope.launch {
                delay(Constants.LONG_PRESS_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (isActive && longPressOn)
                        onLongClick()
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
    open fun onLongClick() {}
    open fun onDoubleClick() {}
    open fun onTripleClick() {}
    open fun onClick() {}

    init {
        gestureDetector = GestureDetector(c, GestureListener())
    }
}