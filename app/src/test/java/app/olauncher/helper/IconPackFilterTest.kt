package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The icon-pack map keeps only entries for installed apps, without losing any it needs. */
class IconPackFilterTest {
    private val installed = setOf("com.whatsapp", "org.mozilla.firefox")

    @Test fun componentEntriesKeepTheirPackage() {
        assertEquals("com.whatsapp", appfilterPackage("ComponentInfo{com.whatsapp/com.whatsapp.Main}"))
        assertTrue(keepAppfilterItem("ComponentInfo{com.whatsapp/com.whatsapp.Main}", installed))
        assertFalse(keepAppfilterItem("ComponentInfo{com.not.installed/.Main}", installed))
    }

    @Test fun barePackageKeysSurvive() {
        // iconFor falls back to entries[packageName]; dropping these would lose real icons.
        assertTrue(keepAppfilterItem("org.mozilla.firefox", installed))
    }

    @Test fun malformedComponentsAreDroppedNotCrashed() {
        assertFalse(keepAppfilterItem("ComponentInfo{:LAUNCHER_ACTION_APP_DRAWER}", installed))
        assertFalse(keepAppfilterItem("ComponentInfo{", installed))
    }

    @Test fun anUnreadableInstalledSetKeepsEverything() {
        assertTrue(keepAppfilterItem("ComponentInfo{com.anything/.Main}", emptySet()))
    }

    @Test fun aLargePackShrinksToWhatIsInstalled() {
        val pack = (0 until 20_000).map { "ComponentInfo{pkg.n$it/pkg.n$it.Main}" } +
            "ComponentInfo{com.whatsapp/com.whatsapp.Main}"
        assertEquals(1, pack.count { keepAppfilterItem(it, installed) })
    }
}
