package app.olauncher.data

import org.junit.Assert.*
import org.junit.Test

class HiddenAppKeysTest {
    @Test fun repairsBothLegacyFormsWithoutChangingProfileKeys() {
        val repaired = HiddenAppKeys.normalized(setOf("com.old", "com.badUserHandle{0}",
            "com.work|UserHandle{10}"), "UserHandle{0}")
        assertEquals(setOf("com.old|UserHandle{0}", "com.bad|UserHandle{0}",
            "com.work|UserHandle{10}"), repaired)
    }

    @Test fun packageVisibilityIsProfileSpecific() {
        val keys = setOf("com.secret|UserHandle{0}")
        assertTrue(HiddenAppKeys.contains(keys, "com.secret", "UserHandle{0}"))
        assertFalse(HiddenAppKeys.contains(keys, "com.secret", "UserHandle{10}"))
    }
}
