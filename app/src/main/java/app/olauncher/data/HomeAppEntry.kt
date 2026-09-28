package app.olauncher.data

/** Home identities must retain profile, activity and pinned-shortcut information during edits. */
data class HomeAppEntry(val name: String, val pkg: String, val user: String,
    val activity: String = "", val shortcut: Boolean = false, val shortcutId: String = "") {
    fun sameTarget(other: HomeAppEntry): Boolean = pkg == other.pkg && user == other.user && shortcut == other.shortcut &&
        if (shortcut) shortcutId == other.shortcutId else activity.isBlank() || other.activity.isBlank() || activity == other.activity
    val identity: List<String> get() = listOf(pkg, user, activity, shortcut.toString(), shortcutId)
}
