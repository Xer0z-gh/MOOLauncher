package app.olauncher.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.olauncher.helper.applyFocusOutline
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.os.Bundle
import android.os.UserHandle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentNotificationsBinding
import app.olauncher.helper.NotificationItem
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.NotificationService
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.notificationAccessGranted
import app.olauncher.helper.showToast
import app.olauncher.helper.tintTextTree
import app.olauncher.helper.withAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The notification panel: what is in the shade right now, grouped by app.
 *
 * This is a different question from the home screen badges, and the two share no state. A badge
 * counts what you missed and only resets when you open the app, so it survives the notification
 * being dismissed. This screen mirrors the shade, so dismissing something removes it here.
 *
 * It reads through [NotificationService.snapshot], which is a binder call, so every read happens
 * off the main thread and the result is posted back. While this screen is visible it subscribes
 * to shade changes; when it leaves, it unsubscribes, so nothing is observed when nothing is
 * watching.
 */
internal object PanelEmptyState {
    fun message(mode: Int, rowCount: Int, hasPinnedMedia: Boolean): Int? {
        if (rowCount > 0 || (hasPinnedMedia && mode != 1)) return null
        return when (mode) {
            0 -> R.string.panel_focus_empty
            1 -> R.string.panel_filtered_empty
            else -> R.string.nothing_in_the_shade
        }
    }
}

class NotificationPanelFragment : BaseFragment() {

    private companion object {
        /** Matched to the drawer so the two lists read as one launcher. */
        const val ICON_SIZE_DP = 32

        /** A row has to travel this fraction of its width before a swipe counts as a dismiss. */
        const val SWIPE_THRESHOLD = 0.45f
        const val EDGE_RETURN_WIDTH_DP = 28
        const val EDGE_RETURN_DISTANCE_DP = 56
    }

    private lateinit var prefs: Prefs
    private lateinit var adapter: NotificationPanelAdapter
    private lateinit var pinnedAdapter: NotificationPanelAdapter

    /** App label per "package|user". Resolving a label is a binder call; the shade repeats apps. */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var clearableKeys: List<String> = emptyList()

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        applyContentWidth()
        applyInsets()
        applyColorTheme()
        initAdapter()
        initHeader()
    }

    /**
     * Caps the reading column. The window is full-bleed by design, but a line of body text
     * stretched across a tablet or an unfolded foldable is slower to read, not faster - and a
     * phone in landscape hits the same width without ever being sw600dp.
     */
    private fun applyContentWidth() {
        val max = resources.getDimensionPixelSize(R.dimen.panel_max_width)
        val screen = resources.displayMetrics.widthPixels
        if (screen <= max) return
        binding.content.updateLayoutParams<ViewGroup.LayoutParams> { width = max }
    }

    /**
     * The launcher lays its windows out under the system bars (FLAG_LAYOUT_NO_LIMITS) so the
     * wallpaper shows through. This screen has no hardcoded margin to absorb that with, so it
     * takes the insets directly - without this the title sits under the status bar and the last
     * row under the gesture handle.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.panelRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        // Defensive, and honestly labelled as such: insets are dispatched to a window once, and
        // a view added afterwards - which this fragment is, since it arrives on navigation - can
        // miss that dispatch and never have the listener above run at all. Measured on an API 36
        // emulator switched to three-button navigation, the list ends at 2274 of 2400 either way,
        // so on that device the dispatch was already reaching it and this changes nothing. Kept
        // because the failure it guards against is real and documented, not because it was seen.
        ViewCompat.requestApplyInsets(binding.panelRoot)
    }

    private fun applyColorTheme() {
        if (!ColorTheme.isCustom(prefs.colorThemeId)) return
        val theme = ColorTheme.byId(prefs.colorThemeId)
        binding.root.setBackgroundColor(theme.background)
        binding.root.tintTextTree(theme.text, theme.text.withAlpha(0xB3))
    }

    private fun initAdapter() {
        adapter = NotificationPanelAdapter(
            onClick = ::openNotification,
            onLongClick = ::toggleAppFilter,
            onDismiss = ::dismissNotification,
        )
        pinnedAdapter = NotificationPanelAdapter(
            onClick = ::openNotification,
            onLongClick = ::toggleAppFilter,
            onDismiss = ::dismissNotification,
        )
        listOf(adapter, pinnedAdapter).forEach { panelAdapter ->
            panelAdapter.textWeight = Constants.TextWeight.value(prefs.textWeight)
            panelAdapter.iconSizePx = if (prefs.panelShowIcons) prefs.appIconSize.dpToPx() else 0
            panelAdapter.iconGrayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
            panelAdapter.iconScope = viewLifecycleOwner.lifecycleScope
            if (ColorTheme.isCustom(prefs.colorThemeId)) {
                panelAdapter.themeTextColor = ColorTheme.byId(prefs.colorThemeId).text
            }
        }

        binding.pinnedMedia.layoutManager = LinearLayoutManager(requireContext())
        binding.pinnedMedia.adapter = pinnedAdapter
        binding.pinnedMedia.itemAnimator = null
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        // Rows are a fixed height regardless of content, so RecyclerView can skip a full
        // requestLayout on every insert and removal.
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.itemAnimator = null
        installSwipeHome()
        attachSwipeToDismiss()
    }

    private fun initHeader() {
        binding.focusToggle.isVisible = false
        binding.panelHome.isVisible = false
        binding.panelCustomize.isVisible = false
        binding.clearAll.isVisible = false
        binding.panelTitle.setOnLongClickListener { customizeNotifications(); true }
        binding.filterToggle.setOnClickListener { setPanelMode(1) }
        binding.panelHome.setOnClickListener { returnHome() }
        binding.panelCustomize.setOnClickListener { customizeNotifications() }
        adapter.onConfigureVisualizer = { customizeNotifications(app.olauncher.helper.PanelSettings.Page.VISUALIZER) }
        pinnedAdapter.onConfigureVisualizer = adapter.onConfigureVisualizer
        val color = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text else requireContext().getColorFromAttr(R.attr.primaryColor)
        listOf(binding.panelHome, binding.panelCustomize, binding.focusToggle, binding.filterToggle, binding.allToggle, binding.clearAll).forEach {
            it.applyFocusOutline(color)
        }
        ViewCompat.setAccessibilityHeading(binding.panelTitle, true)
        binding.panelTitle.minimumHeight = 48.dpToPx()
        binding.panelTitle.applyFocusOutline(color)
        binding.allToggle.setOnClickListener { setPanelMode(2) }
        binding.clearAll.setOnClickListener { clearAll() }
        ViewCompat.setAccessibilityDelegate(binding.clearAll, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        binding.emptyState.setOnClickListener { onEmptyStateClick() }
        paintToggles()
    }

    private fun customizeNotifications(page: app.olauncher.helper.PanelSettings.Page = app.olauncher.helper.PanelSettings.Page.NOTIFICATIONS) {
        customizePanel(page) { applyPanelOptions(); refresh() }
    }
    private fun applyPanelOptions() {
        clearableKeys = emptyList()
        binding.clearAll.isVisible = false
        listOf(adapter, pinnedAdapter).forEach { panelAdapter ->
            panelAdapter.iconSizePx = if (prefs.panelShowIcons) prefs.appIconSize.dpToPx() else 0
            panelAdapter.showPreview = prefs.panelShowPreview
            panelAdapter.showMediaArtwork = prefs.panelMediaArtwork
            panelAdapter.showTime = prefs.panelShowTime
            panelAdapter.compact = prefs.panelCompact
            panelAdapter.notifyItemRangeChanged(0, panelAdapter.itemCount)
        }
        placeFilters()
        paintToggles()
    }

    private fun placeFilters() {
        val content = binding.content
        val controls = binding.filterControls
        val target = if (prefs.panelFiltersBottom) content.childCount - 2 else 2
        if (content.indexOfChild(controls) != target) {
            content.removeView(controls)
            content.addView(controls, target)
        }
        val bar = binding.filterBar
        val first = if (prefs.panelAllFirst) binding.allToggle else binding.filterToggle
        if (bar.getChildAt(1) !== first) {
            bar.removeView(binding.filterToggle)
            bar.removeView(binding.allToggle)
            bar.addView(first, 1)
            bar.addView(if (prefs.panelAllFirst) binding.filterToggle else binding.allToggle, 2)
        }
    }
    private fun setPanelMode(mode: Int) {
        if (prefs.panelMode == mode) return
        prefs.panelMode = mode
        prefs.panelShowAll = mode == 2
        clearableKeys = emptyList()
        binding.clearAll.isVisible = false
        paintToggles(); refresh()
    }

    /**
     * The accent marks which view is live. That is the one place this screen uses it: everything
     * else is ink at one of two opacities, so the active filter is the only coloured thing on
     * the screen and cannot be missed.
     */
    private fun paintToggles() {
        val accent = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
            else requireContext().getColorFromAttr(R.attr.primaryColor)
        paintToggle(binding.focusToggle, active = prefs.panelMode == 0, accent = accent)
        paintToggle(binding.filterToggle, active = prefs.panelMode == 1, accent = accent)
        paintToggle(binding.allToggle, active = prefs.panelMode == 2, accent = accent)
    }

    private fun paintToggle(view: TextView, active: Boolean, accent: Int) {
        view.alpha = 1f
        view.setTextColor(if (active) accent else accent.withAlpha(0xB3))
        view.isSelected = active
        // Announced as "Filtered, selected" rather than leaving a screen reader to infer the
        // state from a colour it cannot see.
        view.contentDescription = getString(
            if (active) R.string.filter_selected else R.string.filter_not_selected,
            view.text
        )
    }

    override fun onResume() {
        super.onResume()
        applyPanelOptions()
        // Refresh on every change while this screen is in front, and only while it is.
        NotificationService.onShadeChanged = { if (isAdded) refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NotificationService.onShadeChanged = null
        refreshJob?.cancel()
    }

    override fun onDestroyView() {
        NotificationService.onShadeChanged = null
        binding.recyclerView.adapter = null
        binding.pinnedMedia.adapter = null
        adapter.iconScope = null
        pinnedAdapter.iconScope = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Reads the shade and rebuilds the list.
     *
     * Everything expensive - the binder call for the notifications, and one more per app for its
     * label - happens on IO. Only the finished list touches the main thread.
     */
    private fun refresh() {
        val context = requireContext().applicationContext
        val accessGranted = requireContext().notificationAccessGranted()
        val muted = prefs.badgeMutedApps
        val mode = prefs.panelMode
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val items = NotificationService.snapshot() ?: return@withContext null
                val playing = playingMediaTokens(context)
                val media = items.firstOrNull { it.mediaToken != null && it.mediaToken in playing.orEmpty() }
                    ?: items.firstOrNull { it.mediaToken != null }
                val pinned = media?.let {
                    PanelRow(it, labelFor(context, it.packageName, it.user, it.appKey), true, it.appKey in muted)
                }
                val visible = items.filter { it.key != media?.key && (mode != 1 || it.appKey in muted) }
                val clearable = clearableKeysFor(visible, media, playing)
                Triple(buildRows(context, visible, muted), pinned, clearable)
            }
            if (_binding == null) return@launch
            clearableKeys = result?.third.orEmpty()
            render(result?.first, result?.second, accessGranted)
        }
    }

    private fun playingMediaTokens(context: Context): Set<MediaSession.Token>? = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(ComponentName(context, NotificationService::class.java))
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.sessionToken }.toSet()
    }.getOrNull()

    private fun clearableKeysFor(
        visible: List<NotificationItem>,
        media: NotificationItem?,
        playing: Set<MediaSession.Token>?,
    ): List<String> {
        val keys = ArrayList<String>(visible.size + 1)
        fun canClear(item: NotificationItem) =
            item.clearable && (item.mediaToken == null || (playing != null && item.mediaToken !in playing))
        for (item in visible) if (canClear(item)) keys.add(item.key)
        if (media != null && canClear(media)) keys.add(media.key)
        return keys
    }

    /**
     * Groups by app without a second view type: rows stay a flat list, and the app name is drawn
     * only on the first of each run. Apps are ordered by their most recent notification, so the
     * newest conversation is at the top with the rest of its run underneath rather than scattered.
     *
     * Grouped by the app's NAME, not its package. His shade had two different system packages
     * both presenting as "Android System", which produced that heading twice with an unrelated
     * app between them - and two identical headings read as a rendering fault, whatever is true
     * underneath. Muting still acts on the package, which each row carries for itself, so the
     * heading being shared does not make the filter coarser.
     */
    private fun buildRows(context: Context, items: List<NotificationItem>, muted: Set<String>): List<PanelRow> {
        val byLabel = LinkedHashMap<String, MutableList<NotificationItem>>()
        // items arrive newest first, so first-seen order is already most-recent-app order.
        for (item in items) {
            val label = labelFor(context, item.packageName, item.user, item.appKey)
            byLabel.getOrPut(label) { mutableListOf() }.add(item)
        }

        val rows = ArrayList<PanelRow>(items.size)
        for ((label, group) in byLabel) {
            group.forEachIndexed { index, item ->
                rows += PanelRow(item = item, appLabel = label, showAppLabel = index == 0, isMuted = item.appKey in muted)
            }
        }
        return rows
    }

    /** Must not run on the main thread: both lookups are binder calls. */
    private fun labelFor(
        context: Context,
        packageName: String,
        user: UserHandle,
        appKey: String,
    ): String {
        labelCache[appKey]?.let { return it }
        val label = runCatching {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.getActivityList(packageName, user).firstOrNull()?.label?.toString()
        }.getOrNull()
            // A notification can come from something with no launcher activity at all - the
            // system, a carrier service - and those still belong in the panel.
            ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
            ?: packageName

        labelCache[appKey] = label
        return label
    }

    private fun render(rows: List<PanelRow>?, pinned: PanelRow?, accessGranted: Boolean) {
        // null means the listener is not connected - a different state from an empty shade,
        // and saying "nothing here" when the permission is off is how a launcher teaches
        // someone that a working feature is broken.
        val message: String? = when {
            !accessGranted -> getString(R.string.notification_access_needed)
            rows == null -> getString(R.string.notification_service_connecting)
            else -> PanelEmptyState.message(prefs.panelMode, rows.size, pinned != null)?.let(::getString)
        }

        binding.filterHelp.isVisible = prefs.panelMode == 1 && rows.isNullOrEmpty() && accessGranted
        if (binding.emptyState.text?.toString() != message) binding.emptyState.text = message
        binding.emptyState.isVisible = message != null
        binding.emptyState.isClickable = !accessGranted
        binding.recyclerView.isVisible = message == null
        binding.clearAll.isVisible = accessGranted && clearableKeys.isNotEmpty()
        binding.pinnedMedia.isVisible = pinned != null
        pinnedAdapter.submitList(pinned?.let(::listOf).orEmpty())
        adapter.submitList(rows.orEmpty())
    }

    private fun onEmptyStateClick() {
        if (requireContext().notificationAccessGranted()) return
        runCatching {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure { requireContext().showToast(getString(R.string.notification_access_needed)) }
    }

    /**
     * Opens whatever sent the notification, then clears it if the notification asked to be
     * cleared on tap - which is what the real shade does, and the reason a message you have
     * just read does not stay in the list.
     */
    private fun openNotification(item: NotificationItem) {
        val intent: PendingIntent? = item.contentIntent
        if (intent == null) {
            requireContext().showToast(getString(R.string.notification_cannot_open))
            return
        }
        val sent = runCatching { intent.send() }.isSuccess
        if (!sent) {
            requireContext().showToast(getString(R.string.notification_cannot_open))
            return
        }
        if (item.autoCancel && item.clearable) NotificationService.dismiss(item.key)
    }

    /**
     * Long press mutes an app, or unmutes it. This is the same set the home screen badges use:
     * one list of apps you do not care about, not two that can disagree.
     */
    private fun toggleAppFilter(row: PanelRow) {
        val muted = prefs.badgeMutedApps.toMutableSet()
        val nowMuted = if (row.item.appKey in muted) {
            muted.remove(row.item.appKey); false
        } else {
            muted.add(row.item.appKey); true
        }
        prefs.badgeMutedApps = muted
        if (nowMuted) NotificationCounts.clearApp(row.item.appKey)
        requireContext().showToast(
            getString(
                if (nowMuted) R.string.app_muted_for_notifications
                else R.string.app_unmuted_for_notifications,
                row.appLabel
            )
        )
        refresh()
    }

    private fun clearAll() {
        if (clearableKeys.isEmpty()) return
        val context = requireContext().applicationContext
        val mode = prefs.panelMode
        val muted = prefs.badgeMutedApps
        clearableKeys = emptyList()
        binding.clearAll.isEnabled = false
        binding.clearAll.isVisible = false
        binding.filterToggle.isEnabled = false
        binding.allToggle.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val items = NotificationService.snapshot() ?: return@withContext
                val playing = playingMediaTokens(context)
                val media = items.firstOrNull { it.mediaToken != null && it.mediaToken in playing.orEmpty() }
                    ?: items.firstOrNull { it.mediaToken != null }
                val visible = items.filter { it.key != media?.key && (mode != 1 || it.appKey in muted) }
                NotificationService.dismissAll(clearableKeysFor(visible, media, playing))
            }
            if (_binding != null) {
                binding.clearAll.isEnabled = true
                binding.filterToggle.isEnabled = true
                binding.allToggle.isEnabled = true
                refresh()
            }
        }
    }

    private fun dismissNotification(item: NotificationItem) {
        if (!item.clearable) return
        NotificationService.dismiss(item.key)
        refresh()
    }


    private fun returnHome() {
        findNavController().popBackStack(R.id.mainFragment, false)
    }

    /**
     * The row helper owns ordinary left/right swipes. Only a left swipe beginning in the
     * rightmost 28dp returns Home, so dismissing a notification from its body still works.
     * Register first: RecyclerView asks item touch listeners in insertion order.
     */
    private fun installSwipeHome() {
        val edgeWidth = EDGE_RETURN_WIDTH_DP.dpToPx()
        val minDistance = EDGE_RETURN_DISTANCE_DP.dpToPx()
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        listOf(binding.recyclerView, binding.pinnedMedia).forEach { list ->
            val listGesture = EdgeReturnGesture(edgeWidth, minDistance, slop)
            list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                    return when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            listGesture.start(rv.width, event)
                            false
                        }
                        MotionEvent.ACTION_MOVE -> listGesture.shouldIntercept(event)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            listGesture.reset()
                            false
                        }
                        else -> false
                    }
                }

                override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val completed = listGesture.isComplete(event)
                        listGesture.reset()
                        if (completed) returnHome()
                    } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        listGesture.reset()
                    }
                }
            })
        }

        // These surfaces are visible when the list has no rows, or above the first row.
        // Taps in the edge strip still reach the view's existing click action.
        listOf(
            binding.panelRoot, binding.content, binding.panelTitle,
            binding.filterControls, binding.emptyState,
        ).forEach { surface ->
            val gesture = EdgeReturnGesture(edgeWidth, minDistance, slop)
            surface.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gesture.start(v.width, event)
                        gesture.active
                    }
                    MotionEvent.ACTION_MOVE -> {
                        gesture.shouldIntercept(event)
                        gesture.active
                    }
                    MotionEvent.ACTION_UP -> {
                        val wasActive = gesture.active
                        val completed = gesture.isComplete(event)
                        val tap = gesture.isTap(event)
                        gesture.reset()
                        if (completed) returnHome()
                        else if (tap) v.performClick()
                        wasActive
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        val wasActive = gesture.active
                        gesture.reset()
                        wasActive
                    }
                    else -> gesture.active
                }
            }
        }
    }

    private class EdgeReturnGesture(
        private val edgeWidth: Int,
        private val minDistance: Int,
        private val slop: Int,
    ) {
        private var downX = Float.NaN
        private var downY = 0f
        val active get() = !downX.isNaN()

        fun start(width: Int, event: MotionEvent) {
            downX = if (width > 0 && event.x >= width - edgeWidth) event.x else Float.NaN
            downY = event.y
        }

        fun shouldIntercept(event: MotionEvent): Boolean {
            if (!active) return false
            val left = downX - event.x
            val vertical = abs(event.y - downY)
            if (vertical > slop && vertical > abs(left)) {
                reset()
                return false
            }
            return left > slop && left > vertical * 1.5f
        }

        fun isComplete(event: MotionEvent): Boolean {
            if (!active) return false
            val left = downX - event.x
            return left >= minDistance && left > abs(event.y - downY) * 1.5f
        }

        fun isTap(event: MotionEvent): Boolean =
            active && abs(event.x - downX) <= slop && abs(event.y - downY) <= slop

        fun reset() {
            downX = Float.NaN
        }
    }

    /**
     * Swipe a row away to dismiss it, in either direction, the way the system shade works.
     *
     * A notification that is not clearable - an ongoing call, a foreground service - refuses the
     * swipe rather than animating away and springing back, because a row that moves and returns
     * reads as a dropped gesture rather than as "this one cannot be dismissed".
     */
    private fun attachSwipeToDismiss() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun getSwipeDirs(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
            ): Int {
                val row = adapter.currentList.getOrNull(vh.bindingAdapterPosition)
                return if (prefs.panelSwipeDismiss && row?.item?.clearable == true) super.getSwipeDirs(rv, vh) else 0
            }

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = SWIPE_THRESHOLD

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val row = adapter.currentList.getOrNull(vh.bindingAdapterPosition)
                if (row == null) {
                    refresh()
                    return
                }
                // The list is rebuilt from the shade rather than locally - the dismiss may fail
                // if the listener dropped, and a row removed optimistically would leave the
                // screen disagreeing with the actual shade.
                dismissNotification(row.item)
            }

            /** Fades the row as it goes, so the gesture has a result before it completes. */
            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                val width = vh.itemView.width.toFloat()
                if (width > 0f) vh.itemView.alpha = 1f - (abs(dX) / width).coerceAtMost(1f)
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                // Holders are reused; a row left at 0 alpha comes back invisible.
                vh.itemView.alpha = 1f
                super.clearView(rv, vh)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }
}
