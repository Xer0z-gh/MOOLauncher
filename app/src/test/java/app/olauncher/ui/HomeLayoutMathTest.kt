package app.olauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutMathTest {
    @Test fun widgetCapEndsOnARowBoundary() {
        // narrow/01_home: a 418 px weather row, then a battery row, under a 675 px cap.
        assertEquals(418, wholeRowsFit(listOf(418, 300), 27, 675))
        assertEquals(418 + 27 + 200, wholeRowsFit(listOf(418, 200), 27, 675))
    }

    @Test fun firstRowShowsWholeEvenAboveTheCap() {
        assertEquals(500, wholeRowsFit(listOf(500, 100), 27, 300))
        assertEquals(0, wholeRowsFit(emptyList(), 27, 300))
    }

    @Test fun aTallerLaterRowStillScrollsIntoViewWhole() {
        // 200% text on the emulator: a two-line tile is 241 px, the charging battery tile 338 px,
        // so a two-line weather row ("Offline") over the battery row, under the 562 px cap.
        assertEquals(338, wholeRowsFit(listOf(241, 338), 22, 562))
    }

    @Test fun rowTopsStepByRowPlusGap() {
        assertEquals(listOf(0, 375, 735), rowTopsOf(listOf(353, 338, 241), 22))
        assertEquals(emptyList<Int>(), rowTopsOf(emptyList(), 22))
    }

    @Test fun fewAppsNeverShowOneTwice() {
        assertEquals(3, focusViewportRows(fit = 5, slots = 4))
        assertEquals(5, focusViewportRows(fit = 6, slots = 5))
        assertEquals(5, focusViewportRows(fit = 5, slots = 6))
    }

    @Test fun manyAppsKeepTheFullHeight() {
        assertEquals(-1, focusViewportRows(fit = 8, slots = 12))
        assertEquals(-1, focusViewportRows(fit = 0, slots = 8))
    }

    @Test fun shortViewportsShowAnOddCountOfWholeRows() {
        assertEquals(1, focusViewportRows(fit = 2, slots = 8))
        assertEquals(3, focusViewportRows(fit = 3, slots = 8))
        assertEquals(1, focusViewportRows(fit = 1, slots = 4))
    }
}
