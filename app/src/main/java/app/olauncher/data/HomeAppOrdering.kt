package app.olauncher.data

import java.util.Locale

/** Existing slot order is authoritative unless the user has chosen automatic ordering. */
object HomeAppOrdering {
    val comparator: Comparator<HomeAppEntry> = compareBy<HomeAppEntry>(
        { it.name.lowercase(Locale.ROOT) }, { it.name }, { it.pkg }, { it.user },
        { it.activity }, { it.shortcut }, { it.shortcutId })

    fun display(entries: List<HomeAppEntry>, automatic: Boolean): List<HomeAppEntry> =
        if (automatic) entries.sortedWith(comparator) else entries

    fun defaultAutomatic(existingEntries: List<HomeAppEntry>): Boolean = existingEntries.isEmpty()
}
