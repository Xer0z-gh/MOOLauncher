package app.olauncher.data

import app.olauncher.helper.Weather
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WeatherCacheTest {
    private fun instant(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time
    @Test fun `previous daily forecast expires at midnight in forecast timezone`() {
        assertTrue(Weather.isCurrentDay("2026-09-21", "America/Chicago", instant("2026-09-22 04:55")))
        assertFalse(Weather.isCurrentDay("2026-09-21", "America/Chicago", instant("2026-09-22 05:05")))
        assertTrue(Weather.isCurrentDay("2026-09-22", "America/Chicago", instant("2026-09-22 05:05")))
    }
    @Test fun `missing forecast metadata forces refresh`() {
        assertFalse(Weather.isCurrentDay("", "", instant("2026-09-22 05:05")))
    }
}
