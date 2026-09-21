package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Current temperature for the home screen.
 *
 * Uses Open-Meteo, which needs no API key and no account, so the feature works out of the box
 * instead of waiting on someone to register for one. Before Launcher uses OpenWeather, which
 * does need a key - that difference is the whole reason this could be built at all.
 *
 * Location comes from the last known fix rather than requesting a new one: a launcher has no
 * business waking the GPS, and a temperature that is right to the nearest town is right enough.
 * If there is no cached fix, the widget stays quiet rather than showing a guess.
 */
object Weather {

    data class Reading(val celsius: Double, val code: Int)

    /** Whether the user has granted a location permission at all. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Fetches the current temperature, or null if it cannot be determined. Does network and
     * location work, so it must not be called on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun fetch(context: Context): Reading? {
        if (!hasLocationPermission(context)) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // Newest cached fix across providers; no new fix is requested.
        val location = runCatching {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.3f".format(location.latitude)}" +
            "&longitude=${"%.3f".format(location.longitude)}" +
            "&current=temperature_2m,weather_code"

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val current = JSONObject(body).getJSONObject("current")
            Reading(
                celsius = current.getDouble("temperature_2m"),
                code = current.optInt("weather_code", -1)
            )
        }.getOrNull()
    }

    /** Rounded temperature in the unit the user asked for. */
    fun format(reading: Reading, fahrenheit: Boolean): String {
        val value = if (fahrenheit) reading.celsius * 9 / 5 + 32 else reading.celsius
        return "${Math.round(value)}°"
    }
}
