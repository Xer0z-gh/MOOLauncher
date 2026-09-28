package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test

class NotificationTextTest {
    @Test fun combinedPeekLineIsBoundedEvenWithAnOversizedTitle() {
        val title = "T".repeat(100_000)
        assertEquals(NotificationText.LINE_CHARS, NotificationText.line(title, "body")?.length)
        assertEquals("body", NotificationText.line("", "body"))
    }

    @Test fun panelFieldsBoundThirdPartyContentBeforeRetainingIt() {
        assertEquals(NotificationText.TITLE_CHARS,
            NotificationText.field("X".repeat(100_000), NotificationText.TITLE_CHARS)?.length)
        assertEquals(NotificationText.BODY_CHARS,
            NotificationText.field("Y".repeat(100_000), NotificationText.BODY_CHARS)?.length)
        assertNull(NotificationText.field("   ", NotificationText.BODY_CHARS))
    }
}
