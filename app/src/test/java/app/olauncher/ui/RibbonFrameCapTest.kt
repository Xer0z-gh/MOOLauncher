package app.olauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home ribbon draws on every second vsync instead of every one. Vsync timestamps jitter,
 * so the gate needs slack: without it, a frame that arrives a hair early is skipped and the
 * next draw waits three frames - a visible judder. The last two tests show those failures are
 * real, by running the same timelines through gates that have them.
 */
class RibbonFrameCapTest {

    /** Draw gaps, in whole vsync periods, over three seconds of vsyncs jittered +-1.5 ms. */
    private fun gaps(periodNs: Long, due: (Long, Long) -> Boolean): List<Long> {
        var seed = 42L
        var lastDraw = 0L
        var lastDrawIndex = 0L
        val out = mutableListOf<Long>()
        var i = 1L
        while (i * periodNs < 3_000_000_000L) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val jitter = ((seed ushr 33) % 3_000_000L) - 1_500_000L
            val t = i * periodNs + jitter
            if (due(t, lastDraw)) {
                if (lastDraw != 0L) out += i - lastDrawIndex
                lastDraw = t
                lastDrawIndex = i
            }
            i++
        }
        return out
    }

    private fun shipped(periodNs: Long): (Long, Long) -> Boolean =
        { t, last -> ribbonFrameDue(t, last, periodNs, settled = false) }

    @Test fun everySecondFrameAtSixtyNinetyAndOneTwentyHz() {
        for (period in longArrayOf(16_666_667L, 11_111_111L, 8_333_333L)) {
            val g = gaps(period, shipped(period))
            assertTrue(g.size > 50)
            assertEquals("period $period ns", setOf(2L), g.toSet())
        }
    }

    @Test fun theFrameThatSettlesAlwaysDraws() {
        assertTrue(ribbonFrameDue(frameNanos = 1_000L, lastDrawNanos = 999L, vsyncNanos = 11_111_111L, settled = true))
    }

    @Test fun aHardTwoFrameCutJudders() {
        val hard: (Long, Long) -> Boolean = { t, last -> t - last >= 22_222_222L }
        assertTrue("a hard cut must show third-frame waits", gaps(11_111_111L, hard).any { it == 3L })
    }

    @Test fun aFixedGapCannotServeSixtyAndNinety() {
        val fixed: (Long, Long) -> Boolean = { t, last -> t - last >= 19_500_000L }
        val at60 = gaps(16_666_667L, fixed).toSet()
        val at90 = gaps(11_111_111L, fixed).toSet()
        assertTrue("fixed 19.5 ms is irregular at 60 or 90 Hz: $at60 / $at90", at60 != setOf(2L) || at90 != setOf(2L))
    }
}
