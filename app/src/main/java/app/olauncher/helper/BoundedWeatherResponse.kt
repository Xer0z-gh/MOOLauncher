package app.olauncher.helper

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Forecast payloads are small; bound a misbehaving server before allocating or parsing JSON. */
internal object BoundedWeatherResponse {
    private const val MAX_BYTES = 64 * 1024

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = MAX_BYTES
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count < 0) break
            if (count == 0) continue
            if (count > remaining) throw IOException("Weather response is too large")
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
