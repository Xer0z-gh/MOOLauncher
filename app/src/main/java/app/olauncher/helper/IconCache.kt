package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * App icons for the home screen and the app drawer.
 *
 * Two decisions here are about running on a 4GB phone rather than about icons:
 *
 * 1. Every icon is rasterised to a fixed-size bitmap when it is loaded. A launcher icon is
 *    usually an AdaptiveIconDrawable - two layers, a mask and a shader - which is re-rendered on
 *    every draw. Flattening it once means the drawer scrolls against plain bitmaps, and it makes
 *    the memory cost exactly known instead of open-ended.
 * 2. The cache is bounded by bitmap allocation bytes. Icons can have different sizes after
 *    customization, and dense screens must not multiply the retained memory budget.
 *    Visible rows keep their own references; eviction never recycles a bitmap still on screen.
 */
object IconCache {

    /** Roughly one screenful of drawer rows plus the home screen, with room to scroll back. */
    private const val MAX_ENTRIES = 48

    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Do not prewarm more bitmaps than the cache can retain at the selected icon size. */
    fun warmCapacity(sizePx: Int): Int = if (sizePx <= 0) 0 else
        (MAX_BYTES / (sizePx.toLong() * sizePx * 4)).toInt().coerceIn(1, MAX_ENTRIES)
    private val cache = object : LruCache<String, Drawable>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Drawable): Int =
            (value as BitmapDrawable).bitmap.allocationByteCount.coerceAtLeast(1)
    }

    /** The chosen icon pack's package, or empty for the apps' own icons. */
    @Volatile
    var iconPackPackage: String = ""
        set(value) {
            if (field == value) return
            field = value
            IconPack.reset()
            clear()
        }

    private fun key(
        packageName: String,
        className: String,
        user: UserHandle,
        sizePx: Int,
        grayscale: Boolean,
    ) = "$packageName|$className|${user.hashCode()}|$sizePx|$grayscale|$iconPackPackage"

    /** Cached icon, or null if it has not been loaded yet. Safe on any thread. */
    fun peek(
        packageName: String,
        className: String,
        user: UserHandle,
        sizePx: Int,
        grayscale: Boolean,
    ): Drawable? = cache.get(key(packageName, className, user, sizePx, grayscale))

    /**
     * Loads and caches an icon. Does binder work and drawable rendering, so it must not be called
     * on the main thread.
     */
    fun load(
        context: Context,
        packageName: String,
        className: String,
        user: UserHandle,
        sizePx: Int,
        grayscale: Boolean,
    ): Drawable? {
        if (packageName.isEmpty() || sizePx <= 0) return null
        val cacheKey = key(packageName, className, user, sizePx, grayscale)
        cache.get(cacheKey)?.let { return it }

        val source = runCatching { resolveIcon(context, packageName, className, user) }
            .getOrNull() ?: return null
        val flattened = runCatching { rasterize(context, source, sizePx, grayscale) }
            .getOrNull() ?: return null

        cache.put(cacheKey, flattened)
        return flattened
    }

    /**
     * Warms the cache for up to [limit] apps. Must be called off the main thread.
     *
     * Without this the drawer resolves each icon as its row scrolls into view - a binder
     * call into LauncherApps plus an AdaptiveIconDrawable rasterise, per row. Doing the
     * first screenful in advance is the difference between a list that draws and one that
     * pops in as you scroll. Bounded by [limit] and by the cache itself, so this cannot
     * grow past the ceiling the cache already guarantees.
     */
    suspend fun warm(
        context: Context,
        apps: List<Triple<String, String, UserHandle>>,
        sizePx: Int,
        grayscale: Boolean,
        limit: Int = MAX_ENTRIES,
    ) {
        if (sizePx <= 0) return
        for ((packageName, className, user) in apps.take(limit.coerceIn(0, MAX_ENTRIES))) {
            currentCoroutineContext().ensureActive()
            if (packageName.isEmpty()) continue
            if (peek(packageName, className, user, sizePx, grayscale) != null) continue
            load(context, packageName, className, user, sizePx, grayscale)
        }
    }

    /** Drops everything, for when the icon style changes or apps are added or removed. */
    fun clear() = cache.evictAll()

    /** The notification's own mask for packages such as System UI with no launcher activity. */
    fun loadNotificationIcon(
        context: Context,
        source: NotificationSmallIcon,
        user: UserHandle,
        sizePx: Int,
        tint: Int,
    ): Drawable? {
        if (sizePx <= 0 || source.resourceId == 0) return null
        val key = "notification|${source.packageName}|${source.resourceId}|$user|$sizePx|$tint"
        cache.get(key)?.let { return it }
        val flattened = runCatching {
            val resources = context.packageManager.getResourcesForApplication(source.packageName)
            val drawable = ResourcesCompat.getDrawable(resources, source.resourceId, null)!!.mutate()
            drawable.setTint(tint)
            rasterize(context, drawable, sizePx, grayscale = false)
        }.getOrNull() ?: return null
        cache.put(key, flattened)
        return flattened
    }

    private fun resolveIcon(
        context: Context,
        packageName: String,
        className: String,
        user: UserHandle,
    ): Drawable? {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(packageName, user)
        if (activities.isEmpty()) return null
        // Prefer the exact activity the home slot points at; a package can expose several.
        val match = activities.firstOrNull { it.componentName.className == className }
            ?: activities.first()

        // A chosen pack wins where it has an icon. Packs are always partial, so anything it does
        // not cover falls back to the app's own icon rather than leaving a hole in the list.
        val pack = iconPackPackage
        if (pack.isNotEmpty()) {
            IconPack.iconFor(context, pack, match.componentName)?.let { return it }
        }
        return match.getBadgedIcon(0)
    }

    private fun rasterize(
        context: Context,
        drawable: Drawable,
        sizePx: Int,
        grayscale: Boolean,
    ): Drawable {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        if (grayscale) {
            // Drawn through a zero-saturation filter rather than filtered at draw time, so the
            // grey version costs nothing extra once it is in the cache.
            drawable.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        drawable.draw(canvas)
        drawable.colorFilter = null
        return bitmap.toDrawable(context.resources).apply {
            setBounds(0, 0, sizePx, sizePx)
        }
    }
}
