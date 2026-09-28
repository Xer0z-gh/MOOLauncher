package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The parsed pack, kept only for apps that are installed. Arcticons maps tens of thousands of
     * components and this phone launches about two hundred, so the whole map was megabytes of
     * heap held for the life of the process for keys that can never be looked up.
     */
    private class PackMap(
        val packageName: String,
        val entries: Map<String, String>,
        /** Packages the map was filtered to; empty means unfiltered. */
        val installed: Set<String>,
        /** Packages outside [installed] already re-read for, so a miss re-reads once, not per icon. */
        val retried: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    )
    private val loaded = AtomicReference(PackMap("", emptyMap(), emptySet()))

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
    // External packs name drawables in appfilter.xml; their IDs are unknown at build time.
    @SuppressLint("DiscouragedApi")
    fun iconFor(context: Context, packPackage: String, component: ComponentName): Drawable? {
        if (packPackage.isEmpty()) return null
        val resources = runCatching {
            context.packageManager.getResourcesForApplication(packPackage)
        }.getOrNull() ?: return null

        var map = ensureLoaded(context, resources, packPackage)
        // Installed after the parse - possibly while no activity was alive to reset this map.
        // Read the pack again, once per such package, so its icon is not missing until a restart.
        // The claim is made under the lock: a second lookup racing the re-read waits for it and
        // gets the new map, instead of missing and caching the app's own icon.
        if (map.installed.isNotEmpty() && component.packageName !in map.installed)
            map = reload(context, resources, packPackage, map, component.packageName)
        val entries = map.entries
        val key = "ComponentInfo{${component.packageName}/${component.className}}"
        val drawableName = entries[key] ?: entries[component.packageName] ?: return null

        val id = resources.getIdentifier(drawableName, "drawable", packPackage)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(resources, id, null) }.getOrNull()
    }

    /** Drops the parsed map, so the next lookup re-reads. Call when the chosen pack changes. */
    fun reset() {
        // Never wait for background XML parsing from a main-thread memory callback.
        // A fresh identity also prevents an in-flight parse from undoing the reset.
        loaded.set(PackMap("", emptyMap(), emptySet()))
    }

    @Synchronized
    private fun ensureLoaded(context: Context, resources: Resources, packPackage: String): PackMap {
        val before = loaded.get()
        if (before.packageName == packPackage) return before
        return parse(context, resources, packPackage).also { loaded.compareAndSet(before, it) }
    }

    @Synchronized
    private fun reload(context: Context, resources: Resources, packPackage: String, stale: PackMap, pkg: String): PackMap {
        val now = loaded.get()
        // Another thread re-read it already, or the pack was reset meanwhile (same monitor, so
        // ensureLoaded re-enters): never answer from a map that is no longer current.
        if (now !== stale) return if (now.packageName == packPackage) now
            else ensureLoaded(context, resources, packPackage)
        // One re-read per package: a package that never has a launcher entry stops here.
        if (!stale.retried.add(pkg)) return stale
        return parse(context, resources, packPackage, stale.retried, fromCache = false)
            .also { loaded.compareAndSet(stale, it) }
    }

    /**
     * The installed-filtered map, from the copy saved by the last parse when the pack is unchanged.
     * Parsing Arcticons took 330 ms of a background thread on the A17 at every process start (a
     * launcher is restarted whenever Android reclaims its memory); the saved copy is a few hundred
     * lines. An app installed since is outside the saved set, so [reload] parses afresh and saves.
     */
    private fun parse(
        context: Context,
        resources: Resources,
        packPackage: String,
        retried: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        fromCache: Boolean = true,
    ): PackMap {
        val stamp = packStamp(context, packPackage)
        val cache = android.util.AtomicFile(java.io.File(context.noBackupFilesDir, "iconpack.map"))
        if (fromCache && stamp != null) {
            val saved = androidx.core.os.trace("IconPack.cached") {
                runCatching { decodePackCache(cache.readFully().toString(Charsets.UTF_8), packPackage, stamp) }.getOrNull()
            }
            if (saved != null) return PackMap(packPackage, saved.second, saved.first, retried)
        }
        return androidx.core.os.trace("IconPack.parse") {
            val byProfile = launchableByProfile(context)
            val installed = byProfile.values.flatMapTo(HashSet()) { it }
            val entries = parseAppFilter(resources, packPackage, installed)
            // Saved: this profile's apps only, so Private Space and work apps are never written to
            // disk. Looking one of them up misses the saved set and re-reads once, like a new app.
            // Unfiltered (nothing could be read) would be the whole pack: not saved.
            val own = byProfile[android.os.Process.myUserHandle()].orEmpty()
            if (stamp != null && own.isNotEmpty()) runCatching {
                val saved = entries.filterKeys { keepAppfilterItem(it, own) }
                val out = cache.startWrite()
                runCatching { out.write(encodePackCache(packPackage, stamp, own, saved).toByteArray()) }
                    .onSuccess { cache.finishWrite(out) }.onFailure { cache.failWrite(out) }
            }
            PackMap(packPackage, entries, installed, retried)
        }
    }

    /** Changes whenever the pack is updated or reinstalled; null if it cannot be read. */
    private fun packStamp(context: Context, packPackage: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(packPackage, 0)
        "${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}:${info.lastUpdateTime}"
    }.getOrNull()

    /** Every package with a launcher entry, per profile. Empty if it cannot be read. */
    private fun launchableByProfile(context: Context): Map<android.os.UserHandle, Set<String>> = runCatching {
        val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
        context.getSystemService(android.os.UserManager::class.java).userProfiles.associateWith { user ->
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                .mapTo(HashSet()) { it.componentName.packageName }
        }
    }.getOrDefault(emptyMap())

    /**
     * Reads appfilter.xml, which packs ship either as a compiled XML resource or as a raw asset.
     * Both are common, so both are tried before giving up.
     */
    // The compiled XML belongs to another APK, so a generated R identifier is unavailable.
    @SuppressLint("DiscouragedApi")
    private fun parseAppFilter(resources: Resources, packPackage: String, installed: Set<String>): Map<String, String> {
        val fromResource = runCatching {
            val id = resources.getIdentifier("appfilter", "xml", packPackage)
            if (id == 0) null else resources.getXml(id)
        }.getOrNull()
        if (fromResource != null) return runCatching {
            fromResource.use { readItems(it, installed) }
        }.getOrDefault(emptyMap())

        return runCatching {
            resources.assets.open("appfilter.xml").use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, null)
                readItems(parser, installed)
            }
        }.getOrDefault(emptyMap())
    }

    private fun readItems(parser: XmlPullParser, installed: Set<String>): Map<String, String> {
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty() &&
                    keepAppfilterItem(component, installed))
                    map.putIfAbsent(component, drawable)
            }
            event = parser.next()
        }
        return map
    }
}

/** The package an appfilter `component` names: `ComponentInfo{pkg/activity}`, or a bare package. */
internal fun appfilterPackage(component: String): String =
    if (component.startsWith("ComponentInfo{")) component.substring(14).substringBefore('/').substringBefore('}')
    else component

/** Whether to keep an appfilter entry. An empty installed set - it could not be read - keeps all. */
internal fun keepAppfilterItem(component: String, installed: Set<String>): Boolean =
    installed.isEmpty() || appfilterPackage(component) in installed

/**
 * The saved form of a filtered pack map: a header naming the pack and its [stamp], the installed
 * set, then one `component<TAB>drawable` per line. Tabs and line breaks never occur in component
 * strings or resource names; an entry that has one is left out rather than corrupting the file.
 */
internal fun encodePackCache(pack: String, stamp: String, installed: Set<String>, entries: Map<String, String>): String =
    buildString {
        append("v1\t").append(pack).append('\t').append(stamp).append('\n')
        installed.filter { '\t' !in it && '\n' !in it }.joinTo(this, "\t")
        append('\n')
        for ((component, drawable) in entries) {
            if ('\t' in component || '\n' in component || '\t' in drawable || '\n' in drawable) continue
            append(component).append('\t').append(drawable).append('\n')
        }
    }

/** The installed set and entries saved for [pack] at [stamp], or null for anything else. */
internal fun decodePackCache(text: String, pack: String, stamp: String): Pair<Set<String>, Map<String, String>>? {
    val lines = text.split('\n')
    if (lines.size < 2 || lines[0] != "v1\t$pack\t$stamp") return null
    val installed = lines[1].split('\t').filterTo(HashSet()) { it.isNotEmpty() }
    if (installed.isEmpty()) return null
    val entries = HashMap<String, String>(lines.size)
    for (i in 2 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.length - 1 || line.indexOf('\t', tab + 1) >= 0) return null
        entries[line.substring(0, tab)] = line.substring(tab + 1)
    }
    return installed to entries
}
