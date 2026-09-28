package app.olauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StepSliderTest {
    @Test fun aStoredValueFindsItsOwnStop() {
        assertEquals(2, nearestIndex(listOf(20, 28, 32, 40, 48), 32))
        assertEquals(0, nearestIndex(listOf(0, 4, 8, 14), 0))
    }

    @Test fun aValueBetweenStopsFindsTheNearest() {
        // An icon size saved by an older build (30 dp) lands on 28, not on the first stop.
        assertEquals(1, nearestIndex(listOf(20, 28, 32, 40, 48), 30))
        assertEquals(3, nearestIndex(listOf(0, 4, 8, 14), 99))
    }

    @Test fun noValuesIsTheFirstStop() {
        assertEquals(0, nearestIndex(emptyList(), 5))
    }
}
