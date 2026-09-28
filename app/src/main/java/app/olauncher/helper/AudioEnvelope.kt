package app.olauncher.helper

import kotlin.math.abs
import kotlin.math.max

/** Reuses the destination so capture callbacks allocate no per-frame arrays. */
internal fun audioEnvelope(data: ByteArray, output: FloatArray) {
    for (i in output.indices) {
        val start = i * data.size / output.size
        val end = ((i + 1) * data.size / output.size).coerceAtLeast(start + 1).coerceAtMost(data.size)
        var peak = 0f
        for (j in start until end) peak = max(peak, abs((data[j].toInt() and 255) - 128) / 128f)
        output[i] = peak
    }
}


/** Keeps a signed sample from each time bucket without allocating during capture. */
internal fun audioWaveform(data: ByteArray, output: FloatArray) {
    if (data.isEmpty()) {
        output.fill(0f)
        return
    }
    for (i in output.indices) {
        val start = (i.toLong() * data.size / output.size).toInt()
        val end = (((i.toLong() + 1) * data.size / output.size).toInt())
            .coerceAtLeast(start + 1).coerceAtMost(data.size)
        var sample = 0
        for (j in start until end) {
            val signed = (data[j].toInt() and 255) - 128
            if (abs(signed) > abs(sample)) sample = signed
        }
        output[i] = sample / 128f
    }
}
