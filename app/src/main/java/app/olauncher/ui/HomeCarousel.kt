package app.olauncher.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.olauncher.helper.HomeForeground
import app.olauncher.helper.IconCache
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import android.content.Context
import android.os.Process
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.helper.LauncherMotion
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.dpToPx
import kotlin.math.abs

/**
 * Whether the focus tick needs a timed pulse: the motor has no native EFFECT_TICK (the A17). Where
 * it has one, Android's own tick plays and a pulse length has nothing to change.
 */
fun needsTimedFocusTick(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    @Suppress("DEPRECATION")
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    return vibrator != null && vibrator.hasVibrator() &&
        vibrator.areEffectsSupported(android.os.VibrationEffect.EFFECT_TICK).firstOrNull() !=
        android.os.Vibrator.VIBRATION_EFFECT_SUPPORT_YES
}

/**
 * Rows a repeating Focus viewport shows for [fit] whole rows of space and [slots] apps, or -1 to
 * fill the height. On a short screen a half-visible outer row is a thin clipped line under the
 * widgets, so up to three rows it is an odd count of whole rows. Taller screens keep the full
 * carousel height (the logged design) unless the apps would not fill it: then the viewport is
 * capped at the odd count of distinct apps, so none is on screen twice.
 */
internal fun focusViewportRows(fit: Int, slots: Int): Int {
    val distinct = if (slots % 2 == 0) slots - 1 else slots
    return when {
        fit in 1..3 -> minOf(if (fit % 2 == 0) fit - 1 else fit, distinct)
        fit > 3 && distinct <= fit -> distinct
        else -> -1
    }
}

/** Recycled, finite storage with a repeating visual viewport. No idle animation. */
class HomeCarousel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {
    private val prefs = Prefs(context)
    private val manager = LinearLayoutManager(context)
    private val snap = LinearSnapHelper()
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener { refresh() }
    var scope: CoroutineScope? = null
    private var validation: Job? = null
    private var generation = 0
    private var unavailable = emptySet<Int>()
    private var keyboardNavigation = false
    private var snapAttached = true
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
    // isEnabled is a field the manager keeps locally; the service list is a binder call. With no
    // accessibility service on - the usual case - every refresh used to pay that call for nothing.
    private fun accessibleNavigation(): Boolean = keyboardNavigation || accessibility.isTouchExplorationEnabled ||
        accessibility.isEnabled && accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        }
    private var slots = emptyList<Int>()
    private var repeat = false
    private val labelLineHeight = FocusLabel(context).lineHeight
    private var cellHeight = 80.dpToPx()
    private var counts = emptyMap<String, Int>()
    private var motion = false
    private var hapticPosition = NO_POSITION
    private var hapticsEnabled = prefs.homeScrollHaptics
    private var touchFeedbackAllowed = true
    @Suppress("DEPRECATION")
    private val vibrator by lazy { context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator }
    private val needsTimedTick by lazy { needsTimedFocusTick(context) }
    /** Rebuilt in refresh() when Customize Home's tick length changes. */
    private var tickMs = 0
    private var timedTick: android.os.VibrationEffect? = null
    private val tickAttributes by lazy { android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build() }
    private val vibrationAttributes by lazy {
        if (Build.VERSION.SDK_INT >= 33) android.os.VibrationAttributes.Builder(tickAttributes).build() else null
    }
    @Suppress("DEPRECATION")
    private fun readTouchFeedbackSetting() {
        touchFeedbackAllowed = android.provider.Settings.System.getInt(context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }

    private fun emitTick(position: Int) {
        if (position != hapticPosition || !hapticsEnabled || !motion ||
            LauncherMotion.savingPower(context, prefs) || !isHapticFeedbackEnabled ||
            !hasWindowFocus() || !touchFeedbackAllowed) return
        // A17's basic motor does not support EFFECT_TICK. A 30 ms pulse
        // was still faint at the phone's Low touch-intensity setting; the
        // length is the user's choice (30/45/60 ms, default 45) and still
        // honours that setting.
        if (Build.VERSION.SDK_INT >= 30 && needsTimedTick) {
            runCatching { timedTick?.let { tick ->
                if (Build.VERSION.SDK_INT >= 33) {
                    vibrationAttributes?.let { vibrator?.vibrate(tick, it) }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(tick, tickAttributes)
                }
            } }
        } else performHapticFeedback(if (Build.VERSION.SDK_INT >= 34)
            android.view.HapticFeedbackConstants.SEGMENT_TICK else
            android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun scrollTick(dy: Int) {
        if (!hapticsEnabled || !motion) { hapticPosition = NO_POSITION; return }
        val position = snap.findSnapView(manager)?.let(::getChildAdapterPosition) ?: NO_POSITION
        if (position == NO_POSITION) return
        val previous = hapticPosition
        hapticPosition = position
        // Emit at the crossing. Delaying and cancelling the pulse dropped ticks
        // during a continuous swipe on the A17.
        if (dy != 0 && scrollState != SCROLL_STATE_IDLE && previous != NO_POSITION &&
            position != previous) emitTick(position)
    }
    private var softEdges = false
    private val blurEffects by lazy {
        if (Build.VERSION.SDK_INT >= 31) Array(4) { level ->
            if (level == 0) null else android.graphics.RenderEffect.createBlurEffect(
                level * resources.displayMetrics.density * .5f,
                level * resources.displayMetrics.density * .5f, android.graphics.Shader.TileMode.DECAL)
        } else emptyArray()
    }
    var onLaunch: (Int) -> Unit = {}
    var onAppMenu: (Int) -> Unit = {}
    var onBadge: (Int) -> Unit = {}
    var onAdd: () -> Unit = {}
    var onHomeMenu: () -> Unit = {}
    var onBrowse: () -> Unit = {}
    var onSearch: () -> Unit = {}
    var onSwipe: (Boolean) -> Unit = {}
    private val rows = Rows()

    init {
        layoutManager = manager
        adapter = rows
        itemAnimator = null
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        snap.attachToRecyclerView(this)
        addOnItemTouchListener(HorizontalSwipeListener(context) { right -> onSwipe(right) })
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateScale(); scrollTick(dy) }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    hapticsEnabled = prefs.homeScrollHaptics
                    if (hapticsEnabled) readTouchFeedbackSetting()
                }
                if (newState != SCROLL_STATE_IDLE && hapticPosition == NO_POSITION)
                    hapticPosition = snap.findSnapView(manager)?.let(::getChildAdapterPosition) ?: NO_POSITION
                if (newState == SCROLL_STATE_IDLE && hapticPosition != NO_POSITION) {
                    val position = snap.findSnapView(manager)?.let(::getChildAdapterPosition) ?: NO_POSITION
                    if (position != NO_POSITION && position != hapticPosition) {
                        hapticPosition = position
                        emitTick(position)
                    }
                }
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibility.addTouchExplorationStateChangeListener(explorationListener)
        accessibility.addAccessibilityStateChangeListener(accessibilityListener)
    }

    override fun onDetachedFromWindow() {
        stopScroll()
        accessibility.removeTouchExplorationStateChangeListener(explorationListener)
        accessibility.removeAccessibilityStateChangeListener(accessibilityListener)
        validation?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Profiles by their stored string, read once per refresh. bind() resolved each row's user
     * with getUserHandleFromString, a UserManager binder call per bound row, and rows are bound
     * several times around the first frame and again on every Home press.
     */
    private var profiles: Map<String, android.os.UserHandle>? = null

    private fun profileFor(stored: String): android.os.UserHandle =
        (profiles ?: context.getSystemService(android.os.UserManager::class.java).userProfiles
            .associateBy { it.toString() }.also { profiles = it })[stored] ?: Process.myUserHandle()

    @android.annotation.SuppressLint("NotifyDataSetChanged") // Slot identities and the finite/infinite adapter size can change together.
    fun refresh() {
        profiles = null
        hapticPosition = NO_POSITION
        hapticsEnabled = prefs.homeScrollHaptics
        if (hapticsEnabled) readTouchFeedbackSetting()
        val ms = prefs.focusTickMs
        if (Build.VERSION.SDK_INT >= 26 && tickMs != ms) {
            tickMs = ms
            timedTick = android.os.VibrationEffect.createOneShot(ms.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val selected = snap.findSnapView(manager)?.let { child ->
            val p = getChildAdapterPosition(child)
            if (p >= 0 && slots.isNotEmpty()) slots[p % slots.size] else null
        }
        val next = (1..prefs.homeAppsNum).filter { prefs.getAppPackage(it).isNotBlank() }
        val accessible = accessibleNavigation()
        val focusLayout = prefs.homeScrollStyle != 0 && LauncherMotion.preset(context, prefs) != LauncherMotion.OFF
        val nextRepeat = next.size > 1 && !accessible && focusLayout
        val changed = next != slots || repeat != nextRepeat
        slots = next
        repeat = nextRepeat
        motion = focusLayout && LauncherMotion.preset(context, prefs) != LauncherMotion.OFF && !accessible
        softEdges = motion && prefs.homeScrollStyle == 2 && Build.VERSION.SDK_INT >= 31
        val useSnap = motion && repeat
        if (snapAttached != useSnap) { snap.attachToRecyclerView(if (useSnap) this else null); snapAttached = useSnap }
        if (changed) rows.notifyDataSetChanged() else notifyVisibleRowsChanged()
        if (changed) { requestLayout(); post {
            centerSlot(selected)
            doOnNextLayout { hapticPosition = snap.findSnapView(manager)?.let(::getChildAdapterPosition) ?: NO_POSITION }
        } }
        post { updateScale() }
        validateApps()
    }

    /** Called after a completed touch anywhere on Home, including its clock and widgets. */
    fun resumeTouchNavigation() {
        if (!keyboardNavigation) return
        // Rebinding during ACTION_DOWN/MOVE would interrupt the gesture.
        post {
            if (isAttachedToWindow && keyboardNavigation) {
                keyboardNavigation = false
                refresh()
            }
        }
    }

    /** Enter a finite list before a hardware key reaches a Home child. */
    fun prepareKeyboardNavigation(event: KeyEvent): Boolean {
        if (!repeat || keyboardNavigation) return false
        val focused = findFocus()
        val holder = focused?.let { findContainingViewHolder(it) as? Row }
        val position = holder?.bindingAdapterPosition ?: NO_POSITION
        val slot = if (position >= 0 && slots.isNotEmpty()) slots[position % slots.size] else null
        val badgeFocused = focused != null && focused === holder?.badge
        keyboardNavigation = true
        refresh()
        if (slot == null) return false // A clock/widget key can keep its normal focus move.

        val backwards = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            (event.keyCode == KeyEvent.KEYCODE_TAB && event.isShiftPressed)
        val target = slots.indexOf(slot) + if (backwards) -1 else 1
        if (target !in slots.indices) {
            // Leave the finite list on the first key, including reverse traversal.
            val outside = if (backwards) intArrayOf(R.id.tvScreenTime, R.id.homeWeather, R.id.date, R.id.clock)
                else intArrayOf(R.id.clock, R.id.date, R.id.homeWeather, R.id.tvScreenTime)
            post {
                outside.asSequence().mapNotNull { rootView.findViewById<View>(it) }
                    .firstOrNull { it.isShown && it.isFocusable }?.requestFocus()
            }
            return true
        }
        // The old row is recycled when the repeating adapter becomes finite.
        // Complete this key's focus move after that layout instead of losing it to Clock.
        post {
            if (isAttachedToWindow && keyboardNavigation) {
                manager.scrollToPositionWithOffset(target, (height - cellHeight) / 2)
                doOnNextLayout {
                    val next = findViewHolderForAdapterPosition(target) as? Row
                    val focus = if (badgeFocused && next?.badge?.isVisible == true) next.badge else next?.itemView
                    focus?.requestFocus()
                }
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU) {
            onHomeMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private data class Identity(val slot: Int, val pkg: String, val user: String, val shortcut: Boolean, val shortcutId: String)
    private fun validateApps() {
        validation?.cancel()
        val run = ++generation
        val identities = slots.map { Identity(it, prefs.getAppPackage(it), prefs.getAppUser(it), prefs.getIsShortcut(it), prefs.getShortcutId(it)) }
        validation = scope?.launch {
            val missing = withContext(Dispatchers.IO) {
                val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                identities.filter { app ->
                    if (app.shortcut) {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && runCatching {
                            val query = LauncherApps.ShortcutQuery().apply {
                                setPackage(app.pkg); setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                            }
                            launcher.getShortcuts(query, getUserHandleFromString(context, app.user))?.none { it.id == app.shortcutId } == true
                        }.getOrDefault(false)
                    } else runCatching { !isPackageInstalled(context, app.pkg, app.user) }.getOrDefault(false)
                }.map { it.slot }.toSet()
            }
            // Keep saved identities intact: a locked work/private profile may be temporary.
            if (generation == run && missing != unavailable) { unavailable = missing; notifyVisibleRowsChanged() }
        }
    }

    private fun notifyVisibleRowsChanged() {
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first != NO_POSITION && last >= first) rows.notifyItemRangeChanged(first, last - first + 1)
    }

    fun updateBadges(value: Map<String, Int>) {
        if (value == counts) return
        counts = value
        for (i in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(i)) as Row
            val position = holder.bindingAdapterPosition
            if (position != NO_POSITION) bind(holder, position)
        }
    }

    fun suppressMotion() {
        motion = false
        softEdges = false
        if (snapAttached) { snap.attachToRecyclerView(null); snapAttached = false }
        stopScroll()
        refresh()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Preserve compact phone/Static spacing; wide screens should not show repeated cycles.
        val oldCellHeight = cellHeight
        cellHeight = maxOf(48.dpToPx(), labelLineHeight +
            2 * resources.getDimensionPixelSize(R.dimen.home_app_padding_vertical)) + prefs.homeSpacingExtra.dpToPx()
        if (prefs.showHomeIcons) cellHeight = maxOf(cellHeight, (prefs.appIconSize + 12).dpToPx())
        val count = maxOf(1, if (repeat) slots.size.coerceAtMost(7) else slots.size)
        val visible = if (repeat && count % 2 == 0) count - 1 else count
        val available = MeasureSpec.getSize(heightSpec)
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        if (repeat && slots.size >= 4 && tablet) {
            val visibleRows = minOf(9, if (slots.size % 2 == 0) slots.size + 1 else slots.size)
            cellHeight = maxOf(cellHeight, (available + visibleRows - 1) / visibleRows)
        }
        if (oldCellHeight != cellHeight) post { notifyVisibleRowsChanged() }
        val desired = if (repeat && slots.size >= 4) {
            // Tablets size their cells to the height above and keep it; phones cap the viewport.
            val rows = if (tablet) -1 else focusViewportRows(available / cellHeight, slots.size)
            if (rows > 0) cellHeight * rows else available
        } else minOf(available, cellHeight * visible)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(desired, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        val selected = snap.findSnapView(manager)?.let { getChildAdapterPosition(it) }
            ?.takeIf { it >= 0 && slots.isNotEmpty() }?.let { slots[it % slots.size] }
        post { notifyVisibleRowsChanged(); centerSlot(selected) }
    }

    private fun centerSlot(slot: Int?) {
        if (!isAttachedToWindow || height <= 0) return
        val index = slots.indexOf(slot).coerceAtLeast(0)
        val position = if (repeat) Int.MAX_VALUE / 2 / slots.size * slots.size + index else index
        if (!repeat) {
            val padding = ((height - cellHeight * maxOf(1, slots.size)) / 2).coerceAtLeast(0)
            setPadding(0, padding, 0, padding)
            manager.scrollToPositionWithOffset(position, 0)
        } else {
            setPadding(0, 0, 0, 0)
            manager.scrollToPositionWithOffset(position, (height - cellHeight) / 2)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateScale()
    }

    private fun updateScale() {
        val center = height / 2f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val distance = abs((child.top + child.bottom) / 2f - center) / maxOf(1, cellHeight)
            val focus = 1f / (1f + 4f * distance * distance)
            val holder = getChildViewHolder(child) as Row
            holder.name.apply {
                visualScale = if (motion) .76f + .24f * focus else 1f
                alpha = if (motion) .62f + .38f * focus else 1f
            }
            val edge = abs((child.top + child.bottom) / 2f - center) / maxOf(1f, center)
            // The badge takes its row's depth, so a faded row's count is no brighter than its name.
            // Opacity and blur only: its scale stays 1 so the 48 dp target does not shrink.
            holder.badge.alpha = holder.name.alpha
            val blur = if (softEdges) ((edge - .65f) * 9f).toInt().coerceIn(0, 3) else 0
            if (Build.VERSION.SDK_INT >= 31 && holder.blurLevel != blur) {
                holder.name.setRenderEffect(blurEffects[blur])
                holder.badge.setRenderEffect(blurEffects[blur])
                holder.blurLevel = blur
            }
        }
    }

    private fun bind(holder: Row, position: Int) {
        val slot = if (slots.isEmpty()) 0 else slots[position % slots.size]
        val label = if (slot == 0) context.getString(R.string.home_add_app) else prefs.getAppName(slot)
        val color = HomeForeground.color(context, prefs)
        holder.itemView.layoutParams.height = cellHeight
        holder.iconJob?.cancel()
        // Home re-binds every visible row on each resume. setCompoundDrawablesRelative and setText
        // relayout the row even when handed what it already shows, so both are skipped when
        // nothing changed: an unchanged Home return now rebinds without a relayout.
        var icon: android.graphics.drawable.Drawable? = null
        if (slot > 0 && prefs.showHomeIcons && !prefs.getIsShortcut(slot)) {
            val pkg = prefs.getAppPackage(slot)
            val cls = prefs.getAppActivityClassName(slot)
            val user = profileFor(prefs.getAppUser(slot))
            val gray = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
            // setCompoundDrawablePadding relayouts even for the same value.
            val pad = 10.dpToPx()
            if (holder.name.compoundDrawablePadding != pad) holder.name.compoundDrawablePadding = pad
            icon = IconCache.peek(pkg, cls, user, prefs.appIconSize.dpToPx(), gray)
            if (icon == null) holder.iconJob = scope?.launch {
                val loaded = withContext(Dispatchers.IO) { IconCache.load(context, pkg, cls, user, prefs.appIconSize.dpToPx(), gray) }
                if (holder.bindingAdapterPosition == position) holder.name.setCompoundDrawablesRelative(loaded, null, null, null)
            }
        }
        if (holder.name.compoundDrawablesRelative[0] !== icon) holder.name.setCompoundDrawablesRelative(icon, null, null, null)
        val text = if (slot in unavailable) context.getString(R.string.home_app_unavailable, label) else label
        if (!android.text.TextUtils.equals(holder.name.text, text)) holder.name.text = text
        holder.name.gravity = prefs.homeAlignment or Gravity.CENTER_VERTICAL
        holder.name.setTextColor(color)
        holder.name.applyTextWeight(Constants.TextWeight.value(prefs.textWeight))
        val key = if (slot == 0) "" else NotificationCounts.key(prefs.getAppPackage(slot),
            prefs.getAppUser(slot).ifBlank { Process.myUserHandle().toString() })
        val count = if (prefs.showNotificationBadges && slot > 0 && !prefs.getIsShortcut(slot)) counts[key] ?: 0 else 0
        holder.badge.isVisible = count > 0
        holder.balance.isVisible = count > 0 && (prefs.homeAlignment and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        val badgeText = when {
            prefs.badgeStyle == Constants.BadgeStyle.DOT -> context.getString(R.string.badge_dot)
            count > 99 -> context.getString(R.string.badge_count_overflow)
            else -> count.toString()
        }
        if (!android.text.TextUtils.equals(holder.badge.text, badgeText)) holder.badge.text = badgeText
        holder.badge.setTextColor(color)
        val spoken = if (count > 0) resources.getQuantityString(R.plurals.missed_notifications, count, holder.name.text, count) else holder.name.text.toString()
        holder.itemView.contentDescription = spoken
        holder.badge.contentDescription = if (count > 0) resources.getQuantityString(
            R.plurals.view_app_notifications, count, label, count) else null
        holder.itemView.applyFocusOutline(color)
        holder.badge.applyFocusOutline(color)
        holder.itemView.setOnClickListener {
            val offset = (holder.itemView.top + holder.itemView.bottom - height) / 2
            if (slot > 0 && repeat && abs(offset) > 4.dpToPx()) {
                stopScroll()
                if (motion) smoothScrollBy(0, offset) else scrollBy(0, offset)
            } else if (slot == 0) onAdd()
            else if (slot in unavailable) onAppMenu(slot)
            else onLaunch(slot)
        }
        holder.itemView.setOnLongClickListener { if (slot == 0) onHomeMenu() else onAppMenu(slot); true }
        holder.badge.setOnClickListener { onBadge(slot) }
    }

    private inner class Rows : Adapter<Row>() {
        override fun getItemCount() = if (repeat) Int.MAX_VALUE else maxOf(1, slots.size)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(context).also { holder ->
            androidx.core.view.ViewCompat.addAccessibilityAction(holder.itemView, context.getString(R.string.browse_apps)) { _, _ -> onBrowse(); true }
            androidx.core.view.ViewCompat.addAccessibilityAction(holder.itemView, context.getString(R.string.search_mode)) { _, _ -> onSearch(); true }
        }
        override fun onBindViewHolder(holder: Row, position: Int) = bind(holder, position)
        override fun onViewRecycled(holder: Row) { holder.iconJob?.cancel(); holder.iconJob = null }
    }

    private class Row(context: Context) : ViewHolder(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24.dpToPx(), 0, 24.dpToPx(), 0)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 80.dpToPx())
        isFocusable = true
    }) {
        var blurLevel = 0
        var iconJob: Job? = null
        val name = FocusLabel(context).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val balance = View(context).apply { isVisible = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        val badge = Badge(context).apply {
            // End-aligned tabular digits: 1, 7 and 10 share a right edge down the column.
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fontFeatureSettings = "tnum"
            isFocusable = true
            minimumHeight = 48.dpToPx()
        }
        init {
            (itemView as LinearLayout).apply {
                addView(balance, LinearLayout.LayoutParams(48.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(badge, LinearLayout.LayoutParams(48.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    /**
     * Scale ink only: hit targets and accessibility bounds never shrink or move. That holds because
     * this label is neither clickable nor in the accessibility tree - the row container is both -
     * so a transform on the label cannot change what a finger or TalkBack reaches.
     *
     * Two things here used to cost more than half of the GPU thread's time while scrolling Home.
     * Measured on the A17 with Perfetto, eight flings, before this change: RenderThread missed the
     * 11.1 ms frame budget on 83 of 269 frames, and `alpha caused saveLayer 946x155` ran 2,092
     * times. 946 px is this label's width - the row less its 48 dp badge column.
     *
     * 1. The scale was applied with canvas.scale in onDraw, so every change called invalidate() and
     *    re-recorded every visible row on every scroll frame. scaleX/scaleY are render properties:
     *    the recorded text is reused and only the transform changes.
     * 2. Every off-centre row is faded with alpha, and the Home text style carries a shadow. A
     *    TextView with a shadow reports overlapping rendering, so Android drew each faded row into
     *    its own offscreen layer and composited that, every frame. The shadow is a halo in the
     *    background's own tone - black on dark, white on light - so letting alpha apply to the
     *    glyph and the shadow separately draws the same thing without the layer.
     */
    /**
     * The badge fades with its row. Like FocusLabel, its text style carries a shadow, so without
     * this every faded badge would get an offscreen layer on every scroll frame.
     */
    @android.annotation.SuppressLint("AppCompatCustomView") // Same four-argument TextView style as before.
    private class Badge(context: Context) : TextView(context, null, 0, R.style.TextSmall) {
        override fun hasOverlappingRendering() = false
    }

    @android.annotation.SuppressLint("AppCompatCustomView") // Preserve the existing four-argument TextView style and glyph metrics.
    private class FocusLabel(context: Context) : TextView(context, null, 0, R.style.TextLarge) {
        var visualScale = 1f
            set(value) {
                if (abs(value - field) <= .001f) return
                field = value
                scaleX = value
                scaleY = value
            }

        override fun hasOverlappingRendering() = false

        /** Scale toward the text's own edge, as the canvas scale did: gravity decides which edge. */
        @android.annotation.SuppressLint("RtlHardcoded") // The value is already resolved to absolute gravity for this layout direction.
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val horizontal = Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
            pivotX = when (horizontal) { Gravity.LEFT -> paddingLeft.toFloat(); Gravity.RIGHT -> (width - paddingRight).toFloat(); else -> width / 2f }
            pivotY = height / 2f
        }
    }
}
