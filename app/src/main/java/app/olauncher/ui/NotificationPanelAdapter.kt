package app.olauncher.ui

import android.text.format.DateUtils
import android.graphics.Color
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.AdapterNotificationBinding
import app.olauncher.databinding.AdapterNotificationPlainBinding
import app.olauncher.helper.IconCache
import app.olauncher.helper.NotificationItem
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.styleTextTree
import app.olauncher.helper.withAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val isMuted: Boolean,
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
    private val onDismiss: (NotificationItem) -> Unit,
) : ListAdapter<PanelRow, NotificationPanelAdapter.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_PLAIN = 0
        private const val TYPE_MEDIA = 1

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
    var showPreview = true
    var showMediaArtwork = true
    var showTime = true
    var compact = true
    var onConfigureVisualizer: (() -> Unit)? = null

    /** Non-zero when a custom colour theme is painting this screen; see AppDrawerFragment. */
    var themeTextColor: Int = 0

    /**
     * A row is MEDIA only when its notification carries a media session; everything else is
     * PLAIN, which inflates adapter_notification_plain.xml: the same row without the media card,
     * artwork, scrim and AudioVisualizerView. The playing session is pinned to its own adapter,
     * so nearly every main-list row is PLAIN, and each one used to build a visualizer (a
     * TextView, a WaveView with six Paints and ~7 KB of arrays, a Handler) and run its
     * stop/refresh chain on every bind only to stay hidden. An unbound visualizer is not a
     * cheaper stand-in: with no notification bound it shows itself and follows every session.
     */
    class ViewHolder private constructor(
        root: View,
        val row: View,
        val groupRule: View,
        val appLabel: TextView,
        val icon: ImageView,
        val title: TextView,
        val body: TextView,
        val time: TextView,
        /** The media card's views; null on a PLAIN row. */
        val media: AdapterNotificationBinding?,
    ) : RecyclerView.ViewHolder(root) {
        var iconJob: Job? = null

        constructor(b: AdapterNotificationBinding) :
            this(b.root, b.row, b.groupRule, b.appLabel, b.icon, b.title, b.body, b.time, b)

        constructor(b: AdapterNotificationPlainBinding) :
            this(b.root, b.row, b.groupRule, b.appLabel, b.icon, b.title, b.body, b.time, null)
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).item.mediaToken != null) TYPE_MEDIA else TYPE_PLAIN

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
        holder.media?.let { media ->
            media.mediaVisualizer.bindNotification(null, false)
            media.mediaArtwork.setImageDrawable(null)
            media.mediaArtwork.isVisible = false
            media.mediaScrim.isVisible = false
        }
        super.onViewRecycled(holder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = if (viewType == TYPE_MEDIA)
            ViewHolder(AdapterNotificationBinding.inflate(inflater, parent, false))
        else ViewHolder(AdapterNotificationPlainBinding.inflate(inflater, parent, false))
        holder.media?.mediaCard?.clipToOutline = true

        // Styling that depends only on adapter-wide settings, which the fragment sets once
        // before either list is attached, so it is done once per holder rather than per bind.
        // One walk for colour and weight, the same shape as the drawer's.
        val foreground = if (themeTextColor != 0) themeTextColor
            else parent.context.getColorFromAttr(R.attr.primaryColor)
        holder.itemView.styleTextTree(
            if (themeTextColor != 0) themeTextColor else null,
            if (themeTextColor != 0) themeTextColor.withAlpha(0xB3) else null,
            textWeight,
        )
        holder.itemView.applyFocusOutline(foreground)
        holder.groupRule.setBackgroundColor(foreground.withAlpha(0x40))
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context

        holder.groupRule.isVisible = row.showAppLabel
        holder.appLabel.isVisible = row.showAppLabel
        holder.appLabel.text = row.appLabel

        // A notification with no title still has a body; showing an empty line above it would
        // leave a gap that reads as a rendering bug.
        val title = if (showPreview) row.item.title ?: row.item.text else context.getString(R.string.panel_private_notification)
        val body = if (!showPreview || row.item.title == null) null else row.item.text
        val largeMediaText = row.item.mediaToken != null && context.resources.configuration.fontScale >= 1.5f
        holder.title.textSize = if (compact) 18f else 22f
        holder.title.maxLines = if (largeMediaText) 2 else 1
        holder.body.maxLines = if (largeMediaText) 2 else if (compact) 1 else 3
        holder.title.text = title
        holder.body.text = body
        holder.body.isVisible = !body.isNullOrEmpty()

        holder.time.isVisible = showTime && !largeMediaText
        holder.time.text = if (holder.time.isVisible) timeLabel(context, row.item.postTime) else ""

        // Secondary text is dimmed by opacity, not by a second colour, so it survives every
        // theme and stays legible in greyscale.
        val foreground = if (themeTextColor != 0) themeTextColor else context.getColorFromAttr(R.attr.primaryColor)
        val dim = foreground.withAlpha(0xB3)
        holder.appLabel.setTextColor(foreground)
        holder.appLabel.textSize = 20f
        holder.body.textSize = 14f
        holder.time.textSize = 12f
        // A MEDIA row's artwork callback below repaints these three for the card.
        holder.title.setTextColor(foreground)
        holder.body.setTextColor(dim)
        holder.time.setTextColor(dim)

        // Read out as one sentence rather than four separate nodes, which is what a screen
        // reader does with a row of TextViews.
        holder.itemView.contentDescription = listOfNotNull(
            row.appLabel,
            title,
            body,
            holder.time.text?.toString(),
        ).joinToString(". ")

        holder.row.setOnClickListener { onClick(row.item) }
        holder.row.setOnLongClickListener { it.showContextMenu() }
        holder.row.isFocusable = true
        holder.row.isClickable = true

        val muteLabel = context.getString(
            if (row.isMuted) R.string.panel_unfilter else R.string.panel_move_filtered,
        )
        ViewCompat.replaceAccessibilityAction(holder.row, AccessibilityActionCompat.ACTION_LONG_CLICK,
            context.getString(R.string.notification_actions)) { view, _ ->
            view.showContextMenu()
        }
        ViewCompat.removeAccessibilityAction(holder.row, AccessibilityActionCompat.ACTION_DISMISS.id)
        if (row.item.clearable) {
            ViewCompat.replaceAccessibilityAction(holder.row, AccessibilityActionCompat.ACTION_DISMISS,
                context.getString(R.string.dismiss)) { _, _ -> onDismiss(row.item); true }
        }
        holder.row.setOnCreateContextMenuListener { menu, _, _ ->
            menu.setHeaderTitle(context.getString(R.string.notification_actions))
            menu.add(muteLabel).setOnMenuItemClickListener { onLongClick(row); true }
            if (row.item.clearable) menu.add(R.string.dismiss).setOnMenuItemClickListener { onDismiss(row.item); true }
        }
        holder.row.setOnKeyListener { view, keyCode, event ->
            when {
                event.action != KeyEvent.ACTION_UP -> false
                keyCode == KeyEvent.KEYCODE_MENU -> view.showContextMenu()
                keyCode == KeyEvent.KEYCODE_FORWARD_DEL && row.item.clearable -> { onDismiss(row.item); true }
                else -> false
            }
        }

        holder.media?.let { media ->
            media.mediaVisualizer.bindNotification(row.item.mediaToken, showPreview && showMediaArtwork) { art ->
                val visible = art != null && showPreview && showMediaArtwork && row.item.mediaToken != null
                media.mediaArtwork.setImageBitmap(if (visible) art else null)
                media.mediaArtwork.isVisible = visible
                media.mediaScrim.isVisible = visible
                val cardInk = if (visible) Color.WHITE else foreground
                val cardDim = if (visible) 0xCCFFFFFF.toInt() else dim
                holder.title.setTextColor(cardInk)
                holder.body.setTextColor(cardDim)
                holder.time.setTextColor(cardDim)
                val inset = if (visible) (8 * context.resources.displayMetrics.density).toInt() else 0
                media.mediaContent.setPadding(inset, 0, inset, 0)
            }
            media.mediaVisualizer.onConfigure = onConfigureVisualizer
        }
        bindIcon(holder, row)
    }

    private fun bindIcon(holder: ViewHolder, row: PanelRow) {
        val icon = holder.icon
        holder.iconJob?.cancel()
        holder.iconJob = null
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
        // package, not an activity. Prefer its launcher icon, then its notification mask.
        IconCache.peek(packageName, "", user, size, iconGrayscale)?.let {
            icon.setImageDrawable(it)
            return
        }

        val context = icon.context.applicationContext
        val tint = if (themeTextColor != 0) themeTextColor else icon.context.getColorFromAttr(R.attr.primaryColor)
        holder.iconJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                IconCache.load(context, packageName, "", user, size, iconGrayscale)
                    ?: row.item.smallIcon?.let { IconCache.loadNotificationIcon(context, it, user, size, tint) }
            }
            // Recycled onto a different notification while this was loading.
            val current = currentList.getOrNull(holder.bindingAdapterPosition)
            if (current?.item?.key != row.item.key) return@launch
            icon.setImageDrawable(loaded)
            // Preserve the column even if a package disappeared or supplied an unsupported icon.
            icon.visibility = if (loaded != null) View.VISIBLE else View.INVISIBLE
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
