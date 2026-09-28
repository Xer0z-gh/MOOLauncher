package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test

class AudioEnvelopeTest {
    @Test fun silenceClearsPreviousPlayback() {
        val output = FloatArray(32) { 1f }
        audioEnvelope(ByteArray(128) { 128.toByte() }, output)
        assertTrue(output.all { it == 0f })
    }
    @Test fun unsignedPcmExtremesStayBounded() {
        val output = FloatArray(2)
        audioEnvelope(byteArrayOf(0, 128.toByte(), 255.toByte(), 128.toByte()), output)
        assertEquals(1f, output[0], .0001f)
        assertEquals(127f/128, output[1], .0001f)
    }
    @Test fun shortOrEmptyCaptureNeverReadsPastBuffer() {
        val output = FloatArray(32) { 1f }
        audioEnvelope(byteArrayOf(0), output)
        assertTrue(output.all { it == 1f })
        audioEnvelope(byteArrayOf(), output)
        assertTrue(output.all { it == 0f })
    }

    @Test fun waveformKeepsLocalSignAndOrder() {
        val output = FloatArray(4)
        audioWaveform(byteArrayOf(0, 128.toByte(), 255.toByte(), 128.toByte()), output)
        assertEquals(-1f, output[0], .0001f)
        assertEquals(0f, output[1], .0001f)
        assertEquals(127f / 128f, output[2], .0001f)
        assertEquals(0f, output[3], .0001f)
    }

    @Test fun waveformSelectsSignedPeakWithinEachTimeBucket() {
        val output = FloatArray(2)
        audioWaveform(byteArrayOf(120, 0, 255.toByte(), 150.toByte()), output)
        assertEquals(-1f, output[0], .0001f)
        assertEquals(127f / 128f, output[1], .0001f)
    }

    @Test fun waveformSilenceAndEmptyCaptureClearOldSamples() {
        val output = FloatArray(32) { 1f }
        audioWaveform(ByteArray(128) { 128.toByte() }, output)
        assertTrue(output.all { it == 0f })
        output.fill(1f)
        audioWaveform(byteArrayOf(), output)
        assertTrue(output.all { it == 0f })
    }

    @Test fun waveformShortCaptureAndEmptyOutputStaySafeAndBounded() {
        val output = FloatArray(32)
        audioWaveform(byteArrayOf(255.toByte()), output)
        assertTrue(output.all { it == 127f / 128f })
        audioWaveform(byteArrayOf(0), output)
        assertTrue(output.all { it == -1f })
        audioWaveform(byteArrayOf(0), FloatArray(0))
        audioWaveform(byteArrayOf(), FloatArray(0))
    }
}
