package app.olauncher.helper

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * GitHub/F-Droid build: Open-Meteo for forecasts and place search. Its server is open source (no
 * F-Droid NonFreeNet label), and its free API covers this build, which sells nothing.
 */
internal object WeatherSource {

    suspend fun forecast(lat: String, lon: String): Weather.Result {
        val fetched = Weather.get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            parse = ::parseForecast)
        return fetched.value?.let { Weather.Result(it) } ?: fetched.failure()
    }

    private fun parseForecast(body: String): Weather.Reading? {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val high = daily.getJSONArray("temperature_2m_max").getDouble(0)
        val low = daily.getJSONArray("temperature_2m_min").getDouble(0)
        val temperature = current.getDouble("temperature_2m")
        if (!temperature.isFinite() || !high.isFinite() || !low.isFinite()) return null
        return Weather.Reading(temperature, current.optInt("weather_code", -1), high, low, current.optInt("is_day", 1) == 1,
            daily.getJSONArray("time").getString(0), json.getString("timezone"))
    }

    suspend fun places(query: String): List<Weather.Place>? {
        val language = Locale.getDefault().language.ifBlank { "en" }
        return Weather.get("https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=$language&name=" +
            URLEncoder.encode(query, "UTF-8"), parse = ::parsePlaces).value
    }

    private fun parsePlaces(body: String): List<Weather.Place> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val place = results.getJSONObject(i)
            val latitude = place.optDouble("latitude")
            val longitude = place.optDouble("longitude")
            val name = place.optString("name")
            if (name.isBlank() || !latitude.isFinite() || !longitude.isFinite()) null
            else Weather.Place(name, listOf(place.optString("admin1"), place.optString("country"))
                .filter { it.isNotBlank() }.joinToString(", "), latitude, longitude)
        }
    }
}
