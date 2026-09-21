package app.olauncher.data

import androidx.annotation.ColorInt
import app.olauncher.R

/**
 * A home screen colour scheme: a flat background and the text colour that sits on it.
 *
 * The launcher draws over the wallpaper, so "background" means a plain wallpaper of that colour.
 * Only the system wallpaper is touched, never the lock screen - changing someone's lock screen
 * because they picked a launcher theme is not a thing a launcher should do.
 *
 * [id] is written to preferences, so the numbers are saved data: add new themes at the end and
 * never renumber an existing one.
 */
data class ColorTheme(
    val id: Int,
    val nameRes: Int,
    @ColorInt val background: Int,
    @ColorInt val text: Int,
) {
    companion object {
        /** Follows the light/dark setting and leaves the wallpaper alone. */
        const val SYSTEM_ID = 0

        val SYSTEM = ColorTheme(SYSTEM_ID, R.string.theme_system, 0, 0)

        /**
         * Every pairing here clears 7:1 contrast, comfortably past WCAG AA for the large text the
         * home screen uses, without going all the way to pure black on pure white - maximum
         * contrast is what makes a screen tiring to look at all day.
         */
        val ALL: List<ColorTheme> = listOf(
            SYSTEM,
            ColorTheme(1, R.string.theme_ink, 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            ColorTheme(2, R.string.theme_paper, 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            ColorTheme(3, R.string.theme_charcoal, 0xFF141619.toInt(), 0xFFE8ECF0.toInt()),
            ColorTheme(4, R.string.theme_slate, 0xFF2B2F36.toInt(), 0xFFE4E8EE.toInt()),
            ColorTheme(5, R.string.theme_midnight, 0xFF0B1A2B.toInt(), 0xFFDCE6F0.toInt()),
            ColorTheme(6, R.string.theme_ocean, 0xFF06232B.toInt(), 0xFFCFE7EE.toInt()),
            ColorTheme(7, R.string.theme_forest, 0xFF10231A.toInt(), 0xFFD6E8DC.toInt()),
            ColorTheme(8, R.string.theme_plum, 0xFF1C1222.toInt(), 0xFFE6DAF0.toInt()),
            ColorTheme(9, R.string.theme_clay, 0xFF2A1113.toInt(), 0xFFF0D9DB.toInt()),
            ColorTheme(10, R.string.theme_cream, 0xFFF5F1E6.toInt(), 0xFF2A2620.toInt()),
            ColorTheme(11, R.string.theme_sand, 0xFFE8DFCF.toInt(), 0xFF33302A.toInt()),
            ColorTheme(12, R.string.theme_mist, 0xFFDDE3E8.toInt(), 0xFF23292E.toInt()),
        )

        fun byId(id: Int): ColorTheme = ALL.firstOrNull { it.id == id } ?: SYSTEM

        /** True when the theme overrides colours rather than deferring to light/dark. */
        fun isCustom(id: Int): Boolean = id != SYSTEM_ID
    }
}
