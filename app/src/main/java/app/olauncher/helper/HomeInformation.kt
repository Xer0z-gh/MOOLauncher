package app.olauncher.helper

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.appcompat.content.res.AppCompatResources

/**
 * [level] and [charging] feed the battery glyph (level -1 = unknown). [setup] marks a usage
 * placeholder whose tap asks for usage access instead of opening the editor.
 */
data class InformationPart(val icon: Int, val value: String, val spoken: String = value, val showIcon: Boolean = false,
    val newLine: Boolean = false, val caption: String? = null, val level: Int = -1, val charging: Boolean = false,
    val setup: Boolean = false, val held: Boolean = false)

/** Inline vectors wrap with their values and are described once by the containing node. */
fun homeInformationText(context: Context, parts: List<InformationPart>, color: Int, iconSize: Int): CharSequence =
    SpannableStringBuilder().apply {
        for (part in parts) {
            if (isNotEmpty()) append(if (part.newLine) "\n" else "  ·  ")
            val start = length
            if (part.showIcon || part.icon == app.olauncher.R.drawable.ic_bolt) {
            append("\uFFFC\u00A0")
            AppCompatResources.getDrawable(context, part.icon)?.mutate()?.let { icon ->
                icon.setTint(color)
                icon.setBounds(0, 0, iconSize, iconSize)
                setSpan(ImageSpan(icon, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            }
            append(part.value)
        }
    }

/** Center the condition under the temperature; the icon has its own adjacent view. */
fun weatherInformationText(temperature: String, condition: String): CharSequence =
    SpannableStringBuilder().apply {
        append(temperature)
        setSpan(android.text.style.RelativeSizeSpan(1.25f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("\n").append(condition)
    }
