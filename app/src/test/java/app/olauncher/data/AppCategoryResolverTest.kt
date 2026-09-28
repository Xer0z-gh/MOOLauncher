package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoryResolverTest {
    @Test fun `known browser corrects misleading manifest category`() {
        assertEquals(20, AppCategoryResolver.resolve("com.duckduckgo.mobile.android", 7))
    }
    @Test fun `uncategorized communication app gets useful group`() {
        assertEquals(23, AppCategoryResolver.resolve("com.google.android.gm", -1))
    }
    @Test fun `unknown game and accessibility categories stay intact`() {
        assertEquals(0, AppCategoryResolver.resolve("example.game", 0))
        assertEquals(8, AppCategoryResolver.resolve("example.accessibility", 8))
    }
    @Test fun `video joins media and unknown stays other`() {
        assertEquals(1, AppCategoryResolver.resolve("example.video", 2))
        assertEquals(-1, AppCategoryResolver.resolve("example.unknown", -1))
        assertEquals(-1, AppCategoryResolver.resolve("example.future", 200))
    }
}
