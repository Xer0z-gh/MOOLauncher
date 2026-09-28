package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackCacheTest {
    private val installed = setOf("com.android.chrome", "org.telegram.messenger")
    private val entries = mapOf(
        "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" to "chrome",
        "org.telegram.messenger" to "telegram",
    )

    @Test fun roundTripsThePackMap() {
        val text = encodePackCache("arcticons", "412:1727000000000", installed, entries)
        assertEquals(installed to entries, decodePackCache(text, "arcticons", "412:1727000000000"))
    }

    @Test fun anUpdatedOrDifferentPackIsNotReused() {
        val text = encodePackCache("arcticons", "412:1727000000000", installed, entries)
        assertNull(decodePackCache(text, "arcticons", "413:1727100000000"))
        assertNull(decodePackCache(text, "other.pack", "412:1727000000000"))
    }

    @Test fun anythingMalformedIsRejectedNotHalfRead() {
        assertNull(decodePackCache("", "arcticons", "1:1"))
        assertNull(decodePackCache("v1\tarcticons\t1:1\n", "arcticons", "1:1"))               // no installed set
        assertNull(decodePackCache("v1\tarcticons\t1:1\na\nno-tab-here\n", "arcticons", "1:1"))
        assertNull(decodePackCache("v1\tarcticons\t1:1\na\nx\ty\tz\n", "arcticons", "1:1"))
    }

    @Test fun anEntryWithATabIsLeftOutRatherThanCorruptingTheFile() {
        val text = encodePackCache("p", "1:1", setOf("a"), mapOf("a" to "ok", "b\tc" to "bad", "d" to "e\tf"))
        assertEquals(setOf("a") to mapOf("a" to "ok"), decodePackCache(text, "p", "1:1"))
    }
}
