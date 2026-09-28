package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryGlyphTest {
    @Test fun unknownAndEmptyDrawNoFill() {
        assertEquals(0f, batteryFillFraction(-1), 0f)
        assertEquals(0f, batteryFillFraction(0), 0f)
    }

    @Test fun anyChargeShowsAtLeastASliver() {
        assertEquals(.06f, batteryFillFraction(1), 0f)
        assertEquals(.06f, batteryFillFraction(5), 0f)
    }

    @Test fun fillFollowsTheLevelAndStopsAtFull() {
        assertEquals(.79f, batteryFillFraction(79), .0001f)
        assertEquals(1f, batteryFillFraction(100), 0f)
        assertEquals(1f, batteryFillFraction(140), 0f)
    }
}
