package app.olauncher.data

import app.olauncher.R

/** Stable stored IDs match Android's declared application categories. Unknown is intentional. */
object AppCategory {
    const val OTHER = -1
    const val SHORTCUTS = 9
    val names = linkedMapOf(
        23 to R.string.category_communication,
        20 to R.string.category_browsers,
        4 to R.string.category_social,
        7 to R.string.category_productivity,
        1 to R.string.category_audio,
        2 to R.string.category_video,
        3 to R.string.category_photography,
        0 to R.string.category_games,
        5 to R.string.category_news,
        6 to R.string.category_travel,
        8 to R.string.category_accessibility,
        10 to R.string.category_tools,
        21 to R.string.category_money,
        22 to R.string.category_shopping,
        SHORTCUTS to R.string.category_shortcuts,
        OTHER to R.string.category_other,
    )

    /** Match the browser's visible order when warming only its first screen of icons. */
    fun iconWarmOrder(source: List<AppModel>, sort: Int): List<AppModel.App> {
        val apps = source.filterIsInstance<AppModel.App>()
        val collator = java.text.Collator.getInstance()
        val byName = Comparator<AppModel.App> { a, b -> collator.compare(a.appLabel, b.appLabel) }
        return when (sort) {
            0 -> {
                val ranks = names.keys.withIndex().associate { it.value to it.index }
                apps.sortedWith { a, b ->
                    val category = (ranks[a.category] ?: Int.MAX_VALUE).compareTo(ranks[b.category] ?: Int.MAX_VALUE)
                    if (category != 0) category else byName.compare(a, b)
                }
            }
            2 -> apps.asReversed()
            3 -> apps.sortedWith { a, b ->
                val installed = b.installedAt.compareTo(a.installedAt)
                if (installed != 0) installed else byName.compare(a, b)
            }
            else -> apps
        }
    }
}
