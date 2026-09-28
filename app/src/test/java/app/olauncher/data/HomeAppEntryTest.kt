package app.olauncher.data

import org.junit.Assert.*
import org.junit.Test

class HomeAppEntryTest {
    @Test fun legacyActivityAndRenameKeepTheSameTarget() {
        val old = HomeAppEntry("Old label", "pkg", "UserHandle{0}")
        assertTrue(old.sameTarget(old.copy(name = "New label", activity = "pkg.Main")))
    }
    @Test fun profilesActivitiesAndShortcutIdsRemainDistinct() {
        val app = HomeAppEntry("App", "pkg", "UserHandle{0}", "pkg.Main")
        assertFalse(app.sameTarget(app.copy(user = "UserHandle{10}")))
        assertFalse(app.sameTarget(app.copy(activity = "pkg.Other")))
        val shortcut = app.copy(shortcut = true, shortcutId = "one")
        assertFalse(shortcut.sameTarget(app))
        assertFalse(shortcut.sameTarget(shortcut.copy(shortcutId = "two")))
        assertTrue(shortcut.sameTarget(shortcut.copy(name = "Renamed")))
    }
}
