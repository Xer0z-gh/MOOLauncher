package app.olauncher.data

/** A hidden package is hidden in its profile, including any pinned shortcuts it owns. */
object HiddenAppKeys {
    private val malformedLegacy = Regex("^(.+?)(UserHandle\\{[0-9]+\\})$")

    fun normalized(keys: Set<String>, personalUser: String): Set<String> = keys.map { key ->
        if ('|' in key) key else {
            val legacy = malformedLegacy.matchEntire(key)
            if (legacy != null) "${legacy.groupValues[1]}|${legacy.groupValues[2]}"
            else "$key|$personalUser"
        }
    }.toSet()

    fun contains(keys: Set<String>, pkg: String, user: String): Boolean = "$pkg|$user" in keys
}
