package app.olauncher.helper

import app.olauncher.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Google Play build: MET Norway forecasts and Photon (OpenStreetMap) place search. This build sells
 * Moo Pro, and Open-Meteo's free API is for non-commercial apps only; both of these allow
 * commercial use with credit (see About and the Weather settings page).
 */
internal object WeatherSource {
    // MET Norway asks for an identifying User-Agent with a way to reach the developer; Photon gets
    // the same one as a courtesy.
    private val userAgent = "MooLauncher/${BuildConfig.VERSION_NAME} (+https://github.com/Xer0z-gh/MOOLauncher)"

    /** One forecast hour: when it starts (epoch ms), its temperature, and MET's sky symbol. */
    data class Hour(val start: Long, val celsius: Double, val symbol: String)

    private class Cached(val url: String, val hours: List<Hour>, val expires: Long, val lastModified: String?)

    // MET's terms: no new request for the same place before its Expires time (about 30 minutes), and
    // If-Modified-Since after it. Home re-asks on a unit change, a day change or weather off and on;
    // those are answered from here. ponytail: in memory, so a restarted process asks once more.
    @Volatile private var cached: Cached? = null

    suspend fun forecast(lat: String, lon: String): Weather.Result {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon"
        val previous = cached?.takeIf { it.url == url }
        if (previous != null && System.currentTimeMillis() < previous.expires) return reading(previous.hours)
        val headers = mapOf("User-Agent" to userAgent) + listOfNotNull(previous?.lastModified?.let { "If-Modified-Since" to it })
        val fetched = Weather.get(url, headers, ::hours)
        val hours = when {
            fetched.value != null -> fetched.value.also { cached = Cached(url, it, fetched.expires, fetched.lastModified) }
            fetched.status == HttpURLConnection.HTTP_NOT_MODIFIED && previous != null ->
                previous.hours.also { cached = Cached(url, it, fetched.expires, previous.lastModified) }
            else -> return fetched.failure()
        }
        return reading(hours)
    }

    private fun reading(hours: List<Hour>): Weather.Result =
        summarize(hours, System.currentTimeMillis(), TimeZone.getDefault())?.let { Weather.Result(it) }
            ?: Weather.Result(failure = Weather.Failure.NETWORK)

    private fun hours(body: String): List<Hour> {
        val series = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return (0 until series.length()).mapNotNull { i ->
            val entry = series.getJSONObject(i)
            val data = entry.getJSONObject("data")
            val celsius = data.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature")
            val start = utc.parse(entry.getString("time"))?.time ?: return@mapNotNull null
            val symbol = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull {
                data.optJSONObject(it)?.optJSONObject("summary")?.optString("symbol_code")?.takeIf(String::isNotBlank)
            }.orEmpty()
            if (celsius.isFinite()) Hour(start, celsius, symbol) else null
        }
    }

    /**
     * The reading at [now]: the temperature and sky of the hour under way, and the high and low of
     * what is left of today. MET has no daily summary and no past hours, so late in the day the
     * range narrows to the hours still ahead.
     * ponytail: the phone's zone decides "today", also for a typed place far away; carry the
     * place's own zone if that ever matters.
     */
    fun summarize(hours: List<Hour>, now: Long, zone: TimeZone): Weather.Reading? {
        if (hours.isEmpty()) return null
        val current = hours.lastOrNull { it.start <= now } ?: hours.first()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val today = day.format(Date(now))
        val rest = hours.filter { it.start >= current.start && day.format(Date(it.start)) == today } + current
        return Weather.Reading(current.celsius, metCode(current.symbol), rest.maxOf { it.celsius }, rest.minOf { it.celsius },
            !current.symbol.endsWith("_night"), today, zone.id)
    }

    /**
     * MET's sky symbol ("lightrainshowers_day") as the WMO code Moo's labels and icons use.
     * Unknown symbols get [UNKNOWN_SKY], not -1: Home reads a negative code as "nothing cached"
     * and would fetch again on every visit.
     */
    fun metCode(symbol: String): Int {
        val sky = symbol.substringBefore('_')
        return when {
            "thunder" in sky -> 95
            "sleet" in sky -> 66
            "snow" in sky -> 71
            "rain" in sky -> 61
            sky == "clearsky" -> 0
            sky == "fair" -> 1
            sky == "partlycloudy" -> 2
            sky == "cloudy" -> 3
            sky == "fog" -> 45
            else -> UNKNOWN_SKY
        }
    }

    const val UNKNOWN_SKY = 100

    suspend fun places(query: String): List<Weather.Place>? {
        // Photon names places in English, German or French and rejects other languages; "default"
        // gives each place's local name.
        val language = Locale.getDefault().language.takeIf { it in setOf("en", "de", "fr") } ?: "default"
        val locale = Locale.getDefault()
        return Weather.get("https://photon.komoot.io/api/?limit=6&layer=city&layer=district&lang=$language&q=" +
            URLEncoder.encode(query, "UTF-8"), mapOf("User-Agent" to userAgent)) { parsePlaces(it, locale) }.value
    }

    private fun parsePlaces(body: String, locale: Locale): List<Weather.Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return merged((0 until features.length()).mapNotNull { i ->
            val feature = features.getJSONObject(i)
            val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
            val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val longitude = point.optDouble(0)
            val latitude = point.optDouble(1)
            val name = properties.text("name")
            if (name.isBlank() || !latitude.isFinite() || !longitude.isFinite()) return@mapNotNull null
            val country = countryName(properties.text("countrycode"), locale) ?: properties.text("country")
            Weather.Place(name, listOf(properties.text("state"), country).filter { it.isNotBlank() }.distinct().joinToString(", "),
                latitude, longitude)
        })
    }

    /**
     * Photon lists a city and its same-named district as two hits a few km apart; keep the first.
     * Same-named villages in one state are further apart and both stay, so each keeps a row.
     */
    fun merged(places: List<Weather.Place>): List<Weather.Place> = places.fold(mutableListOf()) { kept, place ->
        kept.apply {
            if (none { it.name == place.name && it.detail == place.detail &&
                    abs(it.latitude - place.latitude) < 0.2 && abs(it.longitude - place.longitude) < 0.2 }) add(place)
        }
    }

    /** "CH" in the phone's language ("Switzerland"), since Photon's own country field can be multilingual. */
    fun countryName(code: String, locale: Locale): String? = runCatching {
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
    }.getOrNull()?.takeIf { code.length == 2 && it.isNotBlank() && !it.equals(code, ignoreCase = true) }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
