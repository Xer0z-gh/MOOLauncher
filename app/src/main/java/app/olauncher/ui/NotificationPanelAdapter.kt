package app.olauncher.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.AdapterNotificationBinding
import app.olauncher.helper.IconCache
import app.olauncher.helper.NotificationItem
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.styleTextTree
import app.olauncher.helper.withAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row's worth of data: the notification, the app's display name, and whether this row is
 * the first of a run from that app.
 *
 * [showAppLabel] is computed once when the list is built rather than by the adapter comparing
 * against its neighbour at bind time. A RecyclerView binds out of order during a fling, so
 * "am I the first of my group" is not a question a bind can answer correctly.
 */
data class PanelRow(
    val item: NotificationItem,
    val appLabel: String,
    val showAppLabel: Boolean,
)

/**
 * The notification panel's list.
 *
 * A ListAdapter rather than notifyDataSetChanged: the shade changes while this screen is open -
 * a message arrives, one is dismissed elsewhere - and rebuilding every row for a single new
 * notification is what makes a list flicker and lose its scroll position mid-read.
 */
class NotificationPanelAdapter(
    private val onClick: (NotificationItem) -> Unit,
    private val onLongClick: (PanelRow) -> Unit,
) : ListAdapter<PanelRow, NotificationPanelAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PanelRow>() {
            // The notification key is the system's own identity for it, stable across the
            // posting app updating its content.
            override fun areItemsTheSame(old: PanelRow, new: PanelRow) =
                old.item.key == new.item.key

            override fun areContentsTheSame(old: PanelRow, new: PanelRow) = old == new
        }
    }

    /** Icon edge in px, or 0 when icons are off. Set by the fragment from the same pref the drawer reads. */
    var iconSizePx: Int = 0
    var iconGrayscale: Boolean = false
    var iconScope: CoroutineScope? = null

    /** CSS-style font weight, from the launcher-wide text weight setting. */
    var textWeight: Int = 400

    /** Non-zero when a custom colour theme is painting this screen; see AppDrawerFragment. */
    var themeTextColor: Int = 0

    class ViewHolder(val binding: AdapterNotificationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        AdapterNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val binding = holder.binding
        val context = binding.root.context

        // One walk for colour and weight, the same shape as the drawer's - three separate
        // walks per bind is what the performance pass removed there.
        holder.itemView.styleTextTree(
            if (themeTextColor != 0) themeTextColor else null,
            if (themeTextColor != 0) themeTextColor.withAlpha(0xB3) else null,
            textWeight,
        )
        holder.itemView.applyFocusOutline(
            if (themeTextColor != 0) themeTextColor
            else context.getColorFromAttr(R.attr.primaryColor)
        )

        binding.appLabel.isVisible = row.showAppLabel
        binding.appLabel.text = row.appLabel

        // A notification with no title still has a body; showing an empty line above it would
        // leave a gap that reads as a rendering bug.
        val title = row.item.title ?: row.item.text
        val body = if (row.item.title == null) null else row.item.text
        binding.title.text = title
        binding.body.text = body
        binding.body.isVisible = !body.isNullOrEmpty()

        binding.time.text = timeLabel(context, row.item.postTime)

        // Secondary text is dimmed by opacity, not by a second colour, so it survives every
        // theme and stays legible in greyscale.
        val dim = if (themeTextColor != 0) themeTextColor.withAlpha(0xB3) else null
        if (dim != null) {
            binding.appLabel.setTextColor(dim)
            binding.body.setTextColor(dim)
            binding.time.setTextColor(dim)
        }
        binding.appLabel.alpha = 0.7f
        binding.body.alpha = 0.7f
        binding.time.alpha = 0.7f

        // Read out as one sentence rather than four separate nodes, which is what a screen
        // reader does with a row of TextViews.
        holder.itemView.contentDescription = listOfNotNull(
            row.appLabel,
            title,
            body,
            binding.time.text?.toString(),
        ).joinToString(". ")

        binding.row.setOnClickListener { onClick(row.item) }
        binding.row.setOnLongClickListener { onLongClick(row); true }
        binding.row.isFocusable = true
        binding.row.isClickable = true

        bindIcon(holder, row)
    }

    private fun bindIcon(holder: ViewHolder, row: PanelRow) {
        val icon = holder.binding.icon
        // This holder was showing some other app a moment ago.
        icon.setImageDrawable(null)

        val size = iconSizePx
        val scope = iconScope
        if (size <= 0 || scope == null) {
            icon.isVisible = false
            return
        }
        icon.isVisible = true

        val packageName = row.item.packageName
        val user = row.item.user

        // The launcher activity's class name is not known here - a notification names a
        // package, not an activity - so the icon resolves from the package, which is what
        // IconCache already falls back to.
        IconCache.peek(packageName, "", user, size, iconGrayscale)?.let {
            icon.setImageDrawable(it)
            return
        }

        val context = icon.context.applicationContext
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                IconCache.load(context, packageName, "", user, size, iconGrayscale)
            } ?: return@launch
            // Recycled onto a different notification while this was loading.
            val current = currentList.getOrNull(holder.bindingAdapterPosition)
            if (current?.item?.packageName != packageName) return@launch
            icon.setImageDrawable(loaded)
        }
    }

    /**
     * When it arrived: a clock time today, a weekday before that.
     *
     * A relative string ("3 min. ago") was the first version and was wrong twice over. It is
     * rendered once and then goes stale while the panel sits open, so a row can claim a message
     * arrived "0 min. ago" several minutes later; and on en-US it abbreviates to "0 min. ago"
     * anyway, which is the longest and least important text on a row that is mostly the message
     * itself. The clock time is shorter, cannot go stale, and follows the 12/24-hour setting.
     */
    private fun timeLabel(context: android.content.Context, postTime: Long): CharSequence {
        val sameDay = DateUtils.isToday(postTime)
        val flags = if (sameDay) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        return DateUtils.formatDateTime(context, postTime, flags)
    }
}
