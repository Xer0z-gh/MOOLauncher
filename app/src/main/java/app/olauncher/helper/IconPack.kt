package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser

/**
 * Third-party icon packs, the ADW/Nova convention that essentially every pack on F-Droid and
 * Play follows.
 *
 * A pack is an ordinary installed app that ships a drawable per app plus an `appfilter.xml`
 * mapping `ComponentInfo{package/activity}` to a drawable name. Reading it means loading another
 * package's resources, which is allowed for any installed app and needs no permission.
 *
 * The parsed map is held for one pack at a time. A large pack such as Arcticons has thousands of
 * entries, so parsing it per icon would be absurd and keeping every pack parsed would be wasteful
 * on a 4GB phone; switching packs drops the previous map.
 */
object IconPack {

    /** Intents a pack declares to advertise itself. Any one of them is enough. */
    private val PACK_INTENTS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
    )

    data class Pack(val packageName: String, val label: String)

    @Volatile
    private var loadedPackage: String? = null
    private var componentToDrawable: Map<String, String> = emptyMap()

    /** Every icon pack installed on the device, sorted by name. Does binder work; keep it off the main thread. */
    fun installedPacks(context: Context): List<Pack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pack>()
        PACK_INTENTS.forEach { action ->
            val activities = runCatching {
                pm.queryIntentActivities(Intent(action), 0)
            }.getOrNull().orEmpty()
            activities.forEach { info ->
                val packageName = info.activityInfo?.packageName ?: return@forEach
                if (found.containsKey(packageName)) return@forEach
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                }.getOrNull() ?: packageName
                found[packageName] = Pack(packageName, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Looks up an icon in [packPackage] for [component], or null when the pack has no icon for
     * it. Callers fall back to the app's own icon, which is what makes a partial pack usable
     * rather than leaving holes in the drawer.
     */
    fun iconFor(context: Context, packPackage: String, component: ComponentName): Drawable? {
        if (packPackage.isEmpty()) return null
        val resources = runCatching {
            context.packageManager.getResourcesForApplication(packPackage)
        }.getOrNull() ?: return null

        ensureLoaded(resources, packPackage)

        val key = "ComponentInfo{${component.packageName}/${component.className}}"
        val drawableName = componentToDrawable[key]
            // Some packs key only by package, for apps whose launcher activity moves around.
            ?: componentToDrawable[component.packageName]
            ?: return null

        val id = resources.getIdentifier(drawableName, "drawable", packPackage)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(resources, id, null) }.getOrNull()
    }

    /** Drops the parsed map, so the next lookup re-reads. Call when the chosen pack changes. */
    fun reset() {
        loadedPackage = null
        componentToDrawable = emptyMap()
    }

    @Synchronized
    private fun ensureLoaded(resources: Resources, packPackage: String) {
        if (loadedPackage == packPackage) return
        componentToDrawable = parseAppFilter(resources, packPackage)
        loadedPackage = packPackage
    }

    /**
     * Reads appfilter.xml, which packs ship either as a compiled XML resource or as a raw asset.
     * Both are common, so both are tried before giving up.
     */
    private fun parseAppFilter(resources: Resources, packPackage: String): Map<String, String> {
        val fromResource = runCatching {
            val id = resources.getIdentifier("appfilter", "xml", packPackage)
            if (id == 0) null else resources.getXml(id)
        }.getOrNull()
        if (fromResource != null) return runCatching { readItems(fromResource) }.getOrDefault(emptyMap())

        return runCatching {
            resources.assets.open("appfilter.xml").use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, null)
                readItems(parser)
            }
        }.getOrDefault(emptyMap())
    }

    private fun readItems(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty())
                    map.putIfAbsent(component, drawable)
            }
            event = parser.next()
        }
        return map
    }
}
