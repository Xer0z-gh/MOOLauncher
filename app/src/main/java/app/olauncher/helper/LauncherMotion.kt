package app.olauncher.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.view.animation.PathInterpolator
import app.olauncher.data.Prefs

/** One policy for page transitions, list reveals and optional background preparation. */
object LauncherMotion {
    const val OFF = 0
    const val LIQUID = 1
    const val GLIDE = 2
    const val FADE = 3

    fun systemPowerSaver(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    /** Android Power Saver turns the saver on only while the user lets it (default: it does). */
    fun followingSystemSaver(context: Context, prefs: Prefs): Boolean =
        prefs.saverFollowsSystem && systemPowerSaver(context)

    fun savingPower(context: Context, prefs: Prefs): Boolean =
        prefs.ultraBatterySaver || followingSystemSaver(context, prefs)

    fun preset(context: Context, prefs: Prefs): Int =
        if (savingPower(context, prefs) || context.isSystemAnimationsDisabled() || context.isEinkDisplay()) OFF
        else prefs.motionPreset

    fun transition(view: View, enter: Boolean, preset: Int): Animator {
        if (preset == OFF) return ValueAnimator.ofFloat(0f, 1f).setDuration(0)
        val distance = when (preset) { LIQUID -> 12f; GLIDE -> 24f; else -> 0f } * view.resources.displayMetrics.density
        fun values(start: Float, end: Float) = if (enter) floatArrayOf(start, end) else floatArrayOf(end, start)
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, *values(0f, 1f)),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, *values(distance, 0f)),
                ObjectAnimator.ofFloat(view, View.SCALE_X, *values(if (preset == LIQUID) .99f else 1f, 1f)),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, *values(if (preset == LIQUID) .98f else 1f, 1f)),
            )
            duration = when (preset) { LIQUID -> 220L; GLIDE -> 180L; else -> 140L }
            interpolator = PathInterpolator(.18f, .85f, .2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
        }
    }
}
