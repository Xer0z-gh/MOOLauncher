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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** Optional foreground weather, with one bounded coarse fix when no recent cache exists. */
object Weather {
    data class Reading(val celsius: Double, val code: Int, val high: Double, val low: Double, val isDay: Boolean, val forecastDay: String, val timezone: String)
    /** REFUSED: the service answered 403 or 429 (blocked or throttling), so Home backs off instead of retrying soon. */
    enum class Failure { PERMISSION, LOCATION_OFF, NO_LOCATION, NETWORK, REFUSED }
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

    private suspend fun fetchAt(latitude: Double, longitude: Double): Result {
        // Forecasts do not need street-level coordinates; transmit roughly town-level precision.
        val lat = String.format(Locale.US, "%.2f", latitude)
        val lon = String.format(Locale.US, "%.2f", longitude)
        return WeatherSource.forecast(lat, lon)
    }

    /** Places matching [query] from this build's geocoder; null when the search itself failed. */
    suspend fun searchPlaces(query: String): List<Place>? = WeatherSource.places(query)

    /**
     * One GET's outcome. [value] is the parsed 200/203 body, [status] the HTTP code (-1 when no answer
     * came), [expires] the Expires header in epoch ms (0 when absent).
     */
    internal class Fetched<T>(val status: Int, val value: T?, val expires: Long = 0L, val lastModified: String? = null) {
        /** 403 and 429: turned away (blocked, or throttling). Not an outage, so no quick retry. */
        val refused get() = status == HttpURLConnection.HTTP_FORBIDDEN || status == 429

        fun failure() = Result(failure = if (refused) Failure.REFUSED else Failure.NETWORK)
    }

    /** GET [url] and [parse] the body on the IO thread. Cancelling disconnects the socket, even mid-read. */
    internal suspend fun <T> get(url: String, headers: Map<String, String> = emptyMap(), parse: (String) -> T?): Fetched<T> =
        suspendCancellableCoroutine { continuation ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable
                val fetched: Fetched<T> = try {
                    val status = connection.responseCode
                    // 203 is MET's "this API version is deprecated" notice; its terms ask for it to be logged.
                    if (status == HttpURLConnection.HTTP_NOT_AUTHORITATIVE) android.util.Log.w("Weather", "Deprecated API version: $url")
                    val value = if (status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_NOT_AUTHORITATIVE)
                        runCatching { connection.inputStream.use { parse(BoundedWeatherResponse.read(it)) } }.getOrNull() else null
                    Fetched(status, value, connection.expiration, connection.getHeaderField("Last-Modified"))
                } catch (_: Exception) {
                    Fetched(-1, null)
                } finally {
                    connection.disconnect()
                }
                if (continuation.isActive) continuation.resume(fetched)
            })
        }

    fun isCurrentDay(day: String, zone: String, now: Long = System.currentTimeMillis()): Boolean {
        if (day.isBlank() || zone.isBlank()) return false
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone(zone)
        return format.format(java.util.Date(now)) == day
    }

    /** This build's weather credit, links underlined in the text's own colour: a credit, not an accent. */
    fun credit(context: Context): CharSequence {
        val text = android.text.SpannableStringBuilder(androidx.core.text.HtmlCompat.fromHtml(
            context.getString(app.olauncher.R.string.weather_credit), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT))
        text.getSpans(0, text.length, android.text.style.URLSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            text.removeSpan(span)
            text.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) = widget.context.openUrl(span.url)
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = true }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
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
