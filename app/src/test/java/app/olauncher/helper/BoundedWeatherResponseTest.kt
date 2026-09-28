package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BoundedWeatherResponseTest {
    @Test fun acceptsSmallUtf8Forecast() {
        val payload = "{\"condition\":\"café\"}"
        assertEquals(payload, BoundedWeatherResponse.read(ByteArrayInputStream(payload.toByteArray())))
    }

    @Test fun acceptsExactly64KiBAndRejectsAnExtraByte() {
        assertEquals(64 * 1024, BoundedWeatherResponse.read(ByteArrayInputStream(ByteArray(64 * 1024) { 65 })).length)
        assertThrows(IOException::class.java) {
            BoundedWeatherResponse.read(ByteArrayInputStream(ByteArray(64 * 1024 + 1) { 65 }))
        }
    }
}
