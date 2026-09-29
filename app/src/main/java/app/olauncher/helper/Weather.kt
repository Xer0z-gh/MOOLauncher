package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.olauncher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/** Optional foreground weather, with one bounded coarse fix when no recent cache exists. */
object Weather {
    data class Reading(val celsius: Double, val code: Int, val high: Double, val low: Double, val isDay: Boolean, val forecastDay: String, val timezone: String)
    enum class Failure { PERMISSION, LOCATION_OFF, NO_LOCATION, NETWORK }
    data class Result(val reading: Reading? = null, val failure: Failure? = null)
    data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

    /** A place named in Settings, used instead of the phone's location. */
    fun hasPlace(context: Context): Boolean = Prefs(context).weatherPlaceName.isNotBlank()

    /** Weather can run: a named place, or permission to read the phone's location. */
    fun canLocate(context: Context): Boolean = hasPlace(context) || hasLocationPermission(context)

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean =
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let {
            LocationManagerCompat.isLocationEnabled(it)
        } == true

    @SuppressLint("MissingPermission")
    suspend fun fetch(context: Context): Result {
        val prefs = Prefs(context)
        if (prefs.weatherPlaceName.isNotBlank()) return fetchAt(prefs.weatherPlaceLatitude, prefs.weatherPlaceLongitude)
        if (!hasLocationPermission(context)) return Result(failure = Failure.PERMISSION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result(failure = Failure.NO_LOCATION)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return Result(failure = Failure.LOCATION_OFF)
        val cached = withContext(Dispatchers.IO) {
            manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..3_600_000_000_000L }
                .maxByOrNull { it.elapsedRealtimeNanos }
        }
        val location = cached ?: currentNetworkLocation(context, manager)
            ?: return Result(failure = Failure.NO_LOCATION)
        return fetchAt(location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentNetworkLocation(context: Context, manager: LocationManager): Location? {
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, LocationManager.NETWORK_PROVIDER,
                        cancellation, ContextCompat.getMainExecutor(context)) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private suspend fun fetchAt(latitude: Double, longitude: Double): Result = suspendCancellableCoroutine { continuation ->
        // Forecasts do not need street-level coordinates; transmit roughly town-level precision.
        val lat = String.format(Locale.US, "%.2f", latitude)
        val lon = String.format(Locale.US, "%.2f", longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        // Cancellation disconnects the socket even while a blocking read is in progress.
        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            val result = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) Result(failure = Failure.NETWORK)
                else {
                    val body = connection.inputStream.use { BoundedWeatherResponse.read(it) }
                    val json = JSONObject(body)
                    val current = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")
                    val high = daily.getJSONArray("temperature_2m_max").getDouble(0)
                    val low = daily.getJSONArray("temperature_2m_min").getDouble(0)
                    val temperature = current.getDouble("temperature_2m")
                    if (!temperature.isFinite() || !high.isFinite() || !low.isFinite()) Result(failure = Failure.NETWORK)
                    else Result(Reading(temperature, current.optInt("weather_code", -1), high, low, current.optInt("is_day", 1) == 1,
                        daily.getJSONArray("time").getString(0), json.getString("timezone")))
                }
            } catch (_: Exception) {
                Result(failure = Failure.NETWORK)
            } finally {
                connection.disconnect()
            }
            if (continuation.isActive) continuation.resume(result)
        })
    }

    /** Places matching [query] from Open-Meteo's geocoder; null when the search itself failed. */
    suspend fun searchPlaces(query: String): List<Place>? = withContext(Dispatchers.IO) {
        val language = Locale.getDefault().language.ifBlank { "en" }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=$language&name=" +
            URLEncoder.encode(query, "UTF-8")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val results = JSONObject(connection.inputStream.use { BoundedWeatherResponse.read(it) })
                .optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val place = results.getJSONObject(i)
                val latitude = place.optDouble("latitude")
                val longitude = place.optDouble("longitude")
                val name = place.optString("name")
                if (name.isBlank() || !latitude.isFinite() || !longitude.isFinite()) null
                else Place(name, listOf(place.optString("admin1"), place.optString("country"))
                    .filter { it.isNotBlank() }.joinToString(", "), latitude, longitude)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun isCurrentDay(day: String, zone: String, now: Long = System.currentTimeMillis()): Boolean {
        if (day.isBlank() || zone.isBlank()) return false
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone(zone)
        return format.format(java.util.Date(now)) == day
    }

    fun temperature(celsius: Double, fahrenheit: Boolean): String =
        "${Math.round(if (fahrenheit) celsius * 9 / 5 + 32 else celsius)}°"

    fun format(reading: Reading, fahrenheit: Boolean): String =
        "${temperature(reading.celsius, fahrenheit)}  H:${temperature(reading.high, fahrenheit)}  L:${temperature(reading.low, fahrenheit)}"

    fun conditionLabel(code: Int): Int = when (code) {
        0 -> app.olauncher.R.string.weather_clear
        1 -> app.olauncher.R.string.weather_mostly_clear
        2 -> app.olauncher.R.string.weather_partly_cloudy
        3 -> app.olauncher.R.string.weather_cloudy
        45, 48 -> app.olauncher.R.string.weather_fog
        in 51..67, in 80..82 -> app.olauncher.R.string.weather_rain
        in 71..77, 85, 86 -> app.olauncher.R.string.weather_snow
        in 95..99 -> app.olauncher.R.string.weather_storm
        else -> app.olauncher.R.string.information_weather
    }

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) app.olauncher.R.drawable.ic_weather_sun else app.olauncher.R.drawable.ic_weather_moon
        45, 48 -> app.olauncher.R.drawable.ic_weather_fog
        in 51..67, in 80..82 -> app.olauncher.R.drawable.ic_weather_rain
        in 71..77, 85, 86 -> app.olauncher.R.drawable.ic_weather_snow
        in 95..99 -> app.olauncher.R.drawable.ic_weather_storm
        else -> app.olauncher.R.drawable.ic_weather_cloud
    }
}
