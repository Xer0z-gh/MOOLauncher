package io.github.xer0z_gh.moo.pro

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen in Moo's own look: what Moo Pro unlocks, and a way into Moo Launcher (or its page). */
class KeyActivity : Activity() {

    private val launcher = "io.github.xer0z_gh.moo"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val installed = packageManager.getLaunchIntentForPackage(launcher) != null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            addView(TextView(this@KeyActivity).apply {
                setText(R.string.title)
                textSize = 30f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
            })
            addView(TextView(this@KeyActivity).apply {
                setText(if (installed) R.string.active else R.string.needs_launcher)
                textSize = 18f
                setTextColor(themeColor(android.R.attr.textColorSecondary))
                setPadding(0, (12 * dp).toInt(), 0, (24 * dp).toInt())
            })
            // Moo's dialog action: plain bold text on the end edge, a full 48dp target.
            addView(TextView(this@KeyActivity).apply {
                setText(if (installed) R.string.open_launcher else R.string.get_launcher)
                textSize = 18f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = (48 * dp).toInt()
                minWidth = (48 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                background = TypedValue().let {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    getDrawable(it.resourceId)
                }
                isFocusable = true
                accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                }
                setOnClickListener { if (installed) openLauncher() else openStore() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        // Scrolls at large text and keeps clear of the system bars (edge-to-edge on Android 15+).
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(column)
        })
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    private fun openLauncher() {
        packageManager.getLaunchIntentForPackage(launcher)?.let { startActivity(it) }
        finish()
    }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$launcher"))
        runCatching { startActivity(market) }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$launcher"))) }
        }
    }
}
