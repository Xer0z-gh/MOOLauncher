package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherFetchedTest {
    private fun failureFor(status: Int) = Weather.Fetched<String>(status, null).failure().failure

    @Test fun blockedAndThrottledAreRefusedSoHomeBacksOff() {
        // MET's terms: on 429 "limit traffic immediately"; 403 is a block, not an outage.
        assertEquals(Weather.Failure.REFUSED, failureFor(429))
        assertEquals(Weather.Failure.REFUSED, failureFor(403))
    }

    @Test fun everythingElseIsANetworkFailureAndRetriesAfterAMinute() {
        listOf(-1, 404, 500, 502, 503).forEach { assertEquals("HTTP $it", Weather.Failure.NETWORK, failureFor(it)) }
    }
}
