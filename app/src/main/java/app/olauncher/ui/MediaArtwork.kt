package app.olauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import androidx.core.graphics.scale
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/** A bounded card-sized copy of session artwork. No full-size bitmap is cached by the panel. */
internal object MediaArtwork {
    private const val MAX_EDGE = 384
    private const val MAX_URI_BYTES = 2 * 1024 * 1024
    private val decodeGate = Mutex()

    class Request {
        val signal = CancellationSignal()
        val timeout = Runnable { cancel() }
        fun cancel() { signal.cancel() }
    }

    /** One artwork decode at a time, so a stalled provider cannot occupy unbounded IO workers. */
    suspend fun thumbnail(context: Context, metadata: MediaMetadata, request: Request): Bitmap? =
        withTimeoutOrNull(1_700L) {
            decodeGate.withLock {
                withContext(Dispatchers.IO) { decode(context, metadata, request) }
            }
        }

    private fun decode(context: Context, metadata: MediaMetadata, request: Request): Bitmap? {
        try {
            for (key in arrayOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
                if (request.signal.isCanceled) return null
                val bitmap = runCatching { metadata.getBitmap(key) }.getOrNull()
                if (bitmap != null && !bitmap.isRecycled &&
                    bitmap.width.toLong() * bitmap.height <= 32_000_000) return shrink(bitmap)
            }
            for (key in arrayOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
                if (request.signal.isCanceled) return null
                val raw = metadata.getString(key) ?: continue
                val uri = raw.toUri()
                if (uri.scheme != "content" && uri.scheme != "android.resource") continue
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", request.signal) ?: continue
                val bytes = descriptor.use { asset ->
                    asset.createInputStream().use { input ->
                        val output = ByteArrayOutputStream(128 * 1024)
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (request.signal.isCanceled) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_URI_BYTES) return null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) continue
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    var factor = 1
                    while (max(bounds.outWidth, bounds.outHeight) / factor > MAX_EDGE) factor *= 2
                    inSampleSize = factor
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                val small = shrink(decoded)
                if (small !== decoded) decoded.recycle()
                return small
            }
        } catch (_: OperationCanceledException) {
            // The row was recycled, the track changed, or its provider exceeded the deadline.
        } catch (_: Exception) {
            // A media provider may withdraw an artwork URI while its notification is visible.
        } catch (_: OutOfMemoryError) {
            // Malformed or oversized artwork must not take down the launcher.
        }
        return null
    }

    private fun shrink(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        if (largest <= MAX_EDGE) return source
        val scale = MAX_EDGE.toFloat() / largest
        return source.scale(
            max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
    }
}
