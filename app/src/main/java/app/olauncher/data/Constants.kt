package app.olauncher.data

object Constants {

    object Key {
        const val FLAG = "flag"
        const val RENAME = "rename"
        const val KEYBOARD_MODE = "keyboard_mode"
        const val SECTION = "section"
    }

    /**
     * Which part of Settings to show. The settings list runs to about thirty rows in one scroll,
     * which is where Before Launcher splits into a short menu and one focused screen per section.
     * Same fragment either way - the hub simply shows the menu card and each section shows its own.
     */
    object Section {
        const val HUB = 0
        const val HOME = 1
        const val APPEARANCE = 2
        const val GESTURES = 3
        const val APPS = 4
    }

    /**
     * Whether the app drawer opens ready to type or ready to browse.
     *
     * Before Launcher treats "open app list" and "open app search" as two different gestures, and
     * they are only different if one of them suppresses the keyboard: a keyboard covering half the
     * screen is the difference between scanning a list and searching it. AUTO defers to the user's
     * own auto-show-keyboard setting, which is what every other entry point into the drawer wants.
     */
    object KeyboardMode {
        const val AUTO = 0
        const val HIDE = 1
        const val SHOW = 2
    }

    object Dialog {
        const val ABOUT = "ABOUT"
        const val WALLPAPER = "WALLPAPER"
        const val HIDDEN = "HIDDEN"
        const val KEYBOARD = "KEYBOARD"
        const val DIGITAL_WELLBEING = "DIGITAL_WELLBEING"
        const val PRO_MESSAGE = "PRO_MESSAGE"
    }

    object UserState {
        const val START = "START"
        const val WALLPAPER = "WALLPAPER"
        const val DONE = "DONE"
    }

    object DateTime {
        const val OFF = 0
        const val ON = 1
        const val DATE_ONLY = 2

        fun isTimeVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON
        }

        fun isDateVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON || dateTimeVisibility == DATE_ONLY
        }
    }

    object CharacterIndicator {
        const val SHOW = 102
        const val HIDE = 101
    }

    /**
     * What a home screen gesture does. Stored as an Int in Prefs, so the numbers are part of the
     * saved data: add new actions at the end, never renumber.
     */
    object GestureAction {
        const val NOTHING = 0
        const val APP_LIST = 1
        const val APP_SEARCH = 2
        const val NOTIFICATION_SHADE = 3
        const val LAUNCHER_SETTINGS = 4
        const val LOCK_SCREEN = 5
        const val LAUNCH_APP = 6
        const val MISSED_NOTIFICATIONS = 7
    }

    /** The gestures that can be reassigned. The string is the Prefs key prefix. */
    object Gesture {
        const val SWIPE_UP = "SWIPE_UP"
        const val SWIPE_DOWN = "SWIPE_DOWN"
        const val DOUBLE_TAP = "DOUBLE_TAP"
        const val LONG_PRESS = "LONG_PRESS"
    }

    /**
     * Date patterns offered on the home screen, in the order they appear in the menu. Stored as
     * an index, so entries are appended and never reordered.
     */
    object DateFormat {
        val PATTERNS = listOf("EEE, d MMM", "EEEE, d MMMM", "d MMM yyyy", "EEEE", "d/M/yyyy")
    }

    /** Font choices, stored as an index. Append only; these numbers are saved data. */
    object Font {
        const val LIGHT = 0
        const val REGULAR = 1
        const val MEDIUM = 2
        const val CONDENSED = 3
        const val SERIF = 4
        const val MONOSPACE = 5
        const val INTER = 6
    }

    /** How app icons are rendered, when they are shown at all. */
    object IconStyle {
        const val FULL_COLOR = 0
        const val GRAYSCALE = 1
    }

    /** How a notification badge is drawn on the home screen. */
    object BadgeStyle {
        const val COUNT = 0
        const val DOT = 1
    }

    const val FLAG_SET_GESTURE_APP_SWIPE_UP = 21
    const val FLAG_SET_GESTURE_APP_SWIPE_DOWN = 22
    const val FLAG_SET_GESTURE_APP_DOUBLE_TAP = 23
    const val FLAG_SET_GESTURE_APP_LONG_PRESS = 24

    fun gestureForFlag(flag: Int): String? = when (flag) {
        FLAG_SET_GESTURE_APP_SWIPE_UP -> Gesture.SWIPE_UP
        FLAG_SET_GESTURE_APP_SWIPE_DOWN -> Gesture.SWIPE_DOWN
        FLAG_SET_GESTURE_APP_DOUBLE_TAP -> Gesture.DOUBLE_TAP
        FLAG_SET_GESTURE_APP_LONG_PRESS -> Gesture.LONG_PRESS
        else -> null
    }

    val CLOCK_APP_PACKAGES = arrayOf(
        "com.google.android.deskclock", //Google Clock
        "com.sec.android.app.clockpackage", //Samsung Clock
        "com.oneplus.deskclock", //OnePlus Clock
        "com.miui.clock", //Xiaomi Clock
    )

    const val WALL_TYPE_LIGHT = "light"
    const val WALL_TYPE_DARK = "dark"

//    const val THEME_MODE_DARK = 0
//    const val THEME_MODE_LIGHT = 1
//    const val THEME_MODE_SYSTEM = 2

    const val FLAG_LAUNCH_APP = 100
    const val FLAG_HIDDEN_APPS = 101
    const val FLAG_BADGE_FILTER = 102

    const val FLAG_SET_HOME_APP_1 = 1
    const val FLAG_SET_HOME_APP_2 = 2
    const val FLAG_SET_HOME_APP_3 = 3
    const val FLAG_SET_HOME_APP_4 = 4
    const val FLAG_SET_HOME_APP_5 = 5
    const val FLAG_SET_HOME_APP_6 = 6
    const val FLAG_SET_HOME_APP_7 = 7
    const val FLAG_SET_HOME_APP_8 = 8

    const val FLAG_SET_SWIPE_LEFT_APP = 11
    const val FLAG_SET_SWIPE_RIGHT_APP = 12
    const val FLAG_SET_CLOCK_APP = 13
    const val FLAG_SET_CALENDAR_APP = 14
    const val FLAG_SET_SCREEN_TIME_APP = 15

    const val REQUEST_CODE_ENABLE_ADMIN = 666
    const val REQUEST_CODE_LAUNCHER_SELECTOR = 678


    const val LONG_PRESS_DELAY_MS = 500L
    const val ONE_DAY_IN_MILLIS = 86400000L
    const val ONE_HOUR_IN_MILLIS = 3600000L
    const val ONE_MINUTE_IN_MILLIS = 60000L

    const val MIN_ANIM_REFRESH_RATE = 30f

    const val URL_MOO_GITHUB = "https://github.com/Xer0z-gh/MOOLauncher"
    const val URL_WALLPAPERS = "https://gist.githubusercontent.com/tanujnotes/85e2d0343ace71e76615ac346fbff82b/raw"
    const val URL_DEFAULT_DARK_WALLPAPER = "https://images.unsplash.com/photo-1512551980832-13df02babc9e"
    const val URL_DEFAULT_LIGHT_WALLPAPER = "https://images.unsplash.com/photo-1515549832467-8783363e19b6"
    const val URL_DUCK_SEARCH = "https://duck.co/?q="

    const val DIGITAL_WELLBEING_PACKAGE_NAME = "com.google.android.apps.wellbeing"
    const val DIGITAL_WELLBEING_ACTIVITY = "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity"
    const val DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME = "com.samsung.android.forest"
    const val DIGITAL_WELLBEING_SAMSUNG_ACTIVITY = "com.samsung.android.forest.launcher.LauncherActivity"
    const val WALLPAPER_WORKER_NAME = "WALLPAPER_WORKER_NAME"
}