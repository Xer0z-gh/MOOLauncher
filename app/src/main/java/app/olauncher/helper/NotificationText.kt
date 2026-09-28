package app.olauncher.helper

/** Bounds third-party notification content before retaining or drawing it. */
object NotificationText {
    const val LINE_CHARS = 100
    const val TITLE_CHARS = 160
    const val BODY_CHARS = 800

    fun field(value: CharSequence?, maxChars: Int): String? = value
        ?.subSequence(0, minOf(value.length, maxChars))
        ?.toString()?.trim()?.ifEmpty { null }

    fun line(title: CharSequence?, text: CharSequence?): String? {
        val shortTitle = field(title, LINE_CHARS)
        val shortText = field(text, LINE_CHARS)
        return when {
            shortTitle != null && shortText != null -> "$shortTitle: $shortText".take(LINE_CHARS)
            shortTitle != null -> shortTitle
            else -> shortText
        }
    }
}
