package app.olauncher.helper

import app.olauncher.helper.WeatherSource.Hour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MetForecastTest {
    private val chicago = TimeZone.getTimeZone("America/Chicago")
    private fun at(local: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = chicago }.parse(local)!!.time
    private fun hour(local: String, celsius: Double, symbol: String = "cloudy") = Hour(at(local), celsius, symbol)

    private val forecast = listOf(
        hour("2026-09-30 13:00", 20.0, "partlycloudy_day"),
        hour("2026-09-30 14:00", 22.0, "fair_day"),
        hour("2026-09-30 17:00", 25.0),
        hour("2026-09-30 23:00", 12.0, "clearsky_night"),
        hour("2026-10-01 03:00", 5.0),   // tomorrow: colder, must not become today's low
        hour("2026-10-01 15:00", 30.0),  // tomorrow: warmer, must not become today's high
    )

    @Test fun readsTheHourUnderWayAndTheRestOfToday() {
        val reading = WeatherSource.summarize(forecast, at("2026-09-30 14:30"), chicago)!!
        assertEquals(22.0, reading.celsius, 0.0)
        assertEquals(1, reading.code)                    // fair
        assertEquals(25.0, reading.high, 0.0)
        assertEquals(12.0, reading.low, 0.0)             // 13:00 has passed; 20 is not "left today"
        assertTrue(reading.isDay)
        assertEquals("2026-09-30", reading.forecastDay)
        assertEquals("America/Chicago", reading.timezone)
    }

    @Test fun lateAtNightTheRangeIsWhatIsLeft() {
        val reading = WeatherSource.summarize(forecast, at("2026-09-30 23:40"), chicago)!!
        assertEquals(12.0, reading.celsius, 0.0)
        assertEquals(12.0, reading.high, 0.0)
        assertEquals(12.0, reading.low, 0.0)
        assertFalse(reading.isDay)                       // clearsky_night
        assertEquals(0, reading.code)
    }

    @Test fun beforeTheFirstHourStartsTheFirstHourIsCurrent() {
        val reading = WeatherSource.summarize(forecast, at("2026-09-30 12:50"), chicago)!!
        assertEquals(20.0, reading.celsius, 0.0)
        assertEquals(2, reading.code)                    // partly cloudy
    }

    @Test fun anEmptyForecastIsNoReading() {
        assertNull(WeatherSource.summarize(emptyList(), at("2026-09-30 12:00"), chicago))
    }

    @Test fun everySymbolFamilyMapsToALabel() {
        mapOf(
            "clearsky_day" to 0, "fair_night" to 1, "partlycloudy_polartwilight" to 2, "cloudy" to 3, "fog" to 45,
            "lightrain" to 61, "heavyrainshowers_day" to 61, "sleet" to 66, "lightssleetshowers_night" to 66,
            "snow" to 71, "heavysnowshowers_day" to 71, "rainandthunder" to 95,
            "lightssnowshowersandthunder_day" to 95, // MET's own spelling
        ).forEach { (symbol, code) -> assertEquals(symbol, code, WeatherSource.metCode(symbol)) }
    }

    @Test fun anUnknownSkyStillCountsAsACachedReading() {
        // Home treats a negative code as "nothing cached" and would fetch on every visit.
        assertTrue(WeatherSource.metCode("") >= 0)
        assertTrue(WeatherSource.metCode("volcanicash_day") >= 0)
    }

    @Test fun aCityAndItsSameNamedDistrictBecomeOneRow() {
        val city = Weather.Place("Paris", "Île-de-France, France", 48.8535, 2.3484)
        val district = Weather.Place("Paris", "Île-de-France, France", 48.8589, 2.3200)
        val texas = Weather.Place("Paris", "Texas, United States", 33.6618, -95.5555)
        assertEquals(listOf(city, texas), WeatherSource.merged(listOf(city, district, texas)))
    }

    @Test fun sameNamedVillagesFarApartBothKeepARow() {
        // Two Neukirchens in Schleswig-Holstein, about 60 km apart: each needs its own row.
        val first = Weather.Place("Neukirchen", "Schleswig-Holstein, Germany", 54.8715, 8.7390)
        val second = Weather.Place("Neukirchen", "Schleswig-Holstein, Germany", 54.2330, 10.9500)
        assertEquals(listOf(first, second), WeatherSource.merged(listOf(first, second)))
    }

    @Test fun countriesAreNamedInThePhonesLanguage() {
        assertEquals("Switzerland", WeatherSource.countryName("CH", Locale.ENGLISH))
        assertEquals("Schweiz", WeatherSource.countryName("CH", Locale.GERMAN))
        assertNull(WeatherSource.countryName("", Locale.ENGLISH))
        assertNull(WeatherSource.countryName("XX", Locale.ENGLISH))
    }
}
