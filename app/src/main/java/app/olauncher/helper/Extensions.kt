package app.olauncher.helper

import android.app.Activity
import android.app.AppOpsManager
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.data.Constants
import java.util.Calendar
import java.util.Locale

fun Window.showStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        insetsController?.show(WindowInsets.Type.statusBars())
    else
        @Suppress("DEPRECATION", "InlinedApi")
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
}

fun Window.hideStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        insetsController?.hide(WindowInsets.Type.statusBars())
    else
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
}

fun View.hideKeyboard() {
    this.clearFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

fun View.showKeyboard(show: Boolean = true) {
    if (show.not()) return
    if (this.requestFocus())
        postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        }, 100)
}


@RequiresApi(Build.VERSION_CODES.Q)
fun Activity.showLauncherSelector(requestCode: Int) {
    val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
    if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        startActivityForResult(intent, requestCode)
    } else
        resetDefaultLauncher()
}

fun Context.resetDefaultLauncher() {
    try {
        val componentName = ComponentName(this, FakeHomeActivity::class.java)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        val selector = Intent(Intent.ACTION_MAIN)
        selector.addCategory(Intent.CATEGORY_HOME)
        startActivity(selector)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.isDefaultLauncher(): Boolean {
    val launcherPackageName = getDefaultLauncherPackage(this)
    return BuildConfig.APPLICATION_ID == launcherPackageName
}

fun Context.resetLauncherViaFakeActivity() {
    resetDefaultLauncher()
    if (getDefaultLauncherPackage(this).contains("."))
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
}

fun Context.openSearch(query: String? = null) {
    val intent = Intent(Intent.ACTION_WEB_SEARCH)
    intent.putExtra(SearchManager.QUERY, query ?: "")
    startActivity(intent)
}

private var isEinkDevice: Boolean? = null

fun Context.isEinkDisplay(): Boolean {
    isEinkDevice?.let { return it }
    return (hasEinkRefreshRate() || isOnyxDevice() || isKnownEinkModel())
        .also { isEinkDevice = it }
}

private fun Context.hasEinkRefreshRate(): Boolean {
    return try {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Check max supported refresh rate, not the current one, because adaptive
        // refresh rate displays can drop to 30Hz or lower without being e-ink
        windowManager.defaultDisplay.supportedModes.maxOf { it.refreshRate } <= Constants.MIN_ANIM_REFRESH_RATE
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Boox devices report 60Hz+ refresh rates, so the refresh rate check misses them
private fun isOnyxDevice(): Boolean {
    if (Build.MANUFACTURER.equals("ONYX", ignoreCase = true)) return true
    return try {
        // Onyx firmware ships its e-ink SDK classes in the boot classpath
        Class.forName("android.onyx.ViewUpdateHelper")
        true
    } catch (ignored: Throwable) {
        false
    }
}

private fun isKnownEinkModel(): Boolean {
    val brand = Build.BRAND.lowercase()
    val manufacturer = Build.MANUFACTURER.lowercase()
    val einkOnlyBrands = listOf("onyx", "boox", "dasung", "bigme", "boyue", "meebook", "mudita")
    if (einkOnlyBrands.any { brand.contains(it) || manufacturer.contains(it) }) return true
    // Hisense also sells LCD phones, so match only their e-ink line
    if (brand.contains("hisense") || manufacturer.contains("hisense"))
        return Regex("\\bA[579]\\b|TOUCH|HI READER").containsMatchIn(Build.MODEL.uppercase())
    return false
}

fun Context.isSystemAnimationsDisabled(): Boolean {
    return try {
        Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) == 0f
                || Settings.Global.getFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
                || Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.isPackageInstalled(packageName: String, userHandle: UserHandle = android.os.Process.myUserHandle()): Boolean {
    val launcher = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val activityInfo = launcher.getActivityList(packageName, userHandle)
    return activityInfo.isNotEmpty()
}

fun Context.isCountryIn(): Boolean {
    val country = runCatching {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephonyManager?.networkCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: Locale.getDefault().country
    return country.equals("IN", ignoreCase = true)
}

@RequiresApi(Build.VERSION_CODES.Q)
fun Context.appUsagePermissionGranted(): Boolean {
    val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return appOpsManager.unsafeCheckOpNoThrow(
        "android:get_usage_stats",
        android.os.Process.myUid(),
        packageName
    ) == AppOpsManager.MODE_ALLOWED
}

/**
 * Paints every TextView under this view in [color], including hint text.
 *
 * The launcher's colours normally come from theme attributes, which cannot be swapped at runtime
 * without recreating the Activity. A colour theme is a user-visible choice that should apply the
 * instant it is picked, so the home screen and app drawer tint their own text instead. It is a
 * walk over a few dozen views on a screen that is not scrolling, which costs nothing measurable.
 */
fun View.tintTextTree(@ColorInt color: Int, @ColorInt hintColor: Int) {
    when (this) {
        is TextView -> {
            // A flat int REPLACES the ColorStateList from text_colors_default.xml, which is
            // where the pressed-state dim lives - so every custom theme silently lost press
            // feedback on home apps and drawer rows. Rebuild the states instead.
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(color.withAlpha(0x80), color)
                )
            )
            setHintTextColor(hintColor)
        }

        is ViewGroup -> for (i in 0 until childCount) getChildAt(i).tintTextTree(color, hintColor)
    }
}

/**
 * A visible focus ring for d-pad, keyboard and switch access.
 *
 * Android's default highlight is #292929, which is 1.44:1 against a black launcher - well
 * under the 3:1 that WCAG 2.4.11 asks of a focus indicator, and worse on the light themes.
 * The ring is drawn in the same colour as the text, so it inherits the >= 9.9:1 contrast the
 * palette already guarantees, and it follows a custom theme rather than a theme attribute
 * that can disagree with the painted background.
 */
/**
 * Clears every LayoutTransition in a view tree.
 *
 * animateLayoutChanges is declarative, so a layout that sets it animates whatever the
 * system animation scale says - there is no per-view opt-out in XML. The only way to honour
 * "Remove animations" for those is to null the transition the flag created.
 */
fun View.clearLayoutTransitions() {
    if (this !is ViewGroup) return
    layoutTransition = null
    for (i in 0 until childCount) getChildAt(i).clearLayoutTransitions()
}

fun View.applyFocusOutline(@ColorInt color: Int) {
    val ring = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setStroke((2f * resources.displayMetrics.density).toInt(), color)
        setColor(Color.TRANSPARENT)
    }
    foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
}

/** Half-transparent version of a colour, for hints and secondary text. */
@ColorInt
fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

fun Context.notificationListenerComponent(): ComponentName =
    ComponentName(this, NotificationService::class.java)

/**
 * Whether the user has granted notification access in system Settings.
 *
 * ponytail: package-level check. A stale entry for a renamed or deleted listener class would read
 * as granted, which cannot happen while this app has exactly one listener class that never gets
 * renamed. If a second listener is ever added, switch to the component-level
 * NotificationManager.isNotificationListenerAccessGranted(component) behind an API 27 gate.
 */
fun Context.notificationAccessGranted(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

fun Context.formattedTimeSpent(timeSpent: Long): String {
    val seconds = timeSpent / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        timeSpent == 0L -> "0m"

        hours > 0 -> getString(
            R.string.time_spent_hour,
            hours.toString(),
            remainingMinutes.toString()
        )

        minutes > 0 -> {
            getString(R.string.time_spent_min, minutes.toString())
        }

        else -> "<1m"
    }
}

fun Long.convertEpochToMidnight(): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = this
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

fun Long.isDaySince(): Int = ((System.currentTimeMillis().convertEpochToMidnight() - this.convertEpochToMidnight())
        / Constants.ONE_DAY_IN_MILLIS).toInt()

fun Long.hasBeenDays(days: Int): Boolean =
    ((System.currentTimeMillis() - this) / Constants.ONE_DAY_IN_MILLIS) >= days

fun Long.hasBeenHours(hours: Int): Boolean =
    ((System.currentTimeMillis() - this) / Constants.ONE_HOUR_IN_MILLIS) >= hours

fun Long.hasBeenMinutes(minutes: Int): Boolean =
    ((System.currentTimeMillis() - this) / Constants.ONE_MINUTE_IN_MILLIS) >= minutes

fun Int.dpToPx(): Int {
    return (this * Resources.getSystem().displayMetrics.density).toInt()
}
