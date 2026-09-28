package app.olauncher.helper

import android.content.Context
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.HomeAppEntry
import app.olauncher.data.HomeAppOrdering
import app.olauncher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One draft, one list: checked Home apps first, then unchecked available apps. */
@android.annotation.SuppressLint("NotifyDataSetChanged") // One edit moves rows between both sections, changing their indices and counts together.
fun Context.homeAppsEditor(prefs: Prefs, scope: CoroutineScope, owner: Fragment, onSaved: () -> Unit): AlertDialog {
    val activity = owner.requireActivity()
    val state = ViewModelProvider(owner)[HomeAppsDraftState::class.java]
    val draft = state.rows ?: HomeAppOrdering.display(prefs.homeAppEntries(),
        prefs.autoSortHomeApps).mapIndexed { index, entry ->
        DraftHomeRow(index.toLong(), entry)
    }.toMutableList().also { state.rows = it }
    var nextId = (draft.maxOfOrNull { it.id } ?: -1L) + 1L
    var availableApps = emptyList<HomeAppEntry>()
    var filteredApps = emptyList<HomeAppEntry>()
    var loaded = false
    var child: AlertDialog? = null
    var active = true
    var load: Job? = null

    fun label(entry: HomeAppEntry): String = buildList {
        add(entry.name)
        if (entry.user.isNotBlank() && entry.user != Process.myUserHandle().toString()) add(getString(R.string.home_bulk_work))
        if (entry.shortcut) add(getString(R.string.home_bulk_shortcut))
        if (draft.any { it.entry.name == entry.name && it.entry.user == entry.user && !it.entry.sameTarget(entry) }) {
            add(entry.pkg)
            add(if (entry.shortcut) entry.shortcutId else entry.activity.substringAfterLast('.'))
        }
    }.joinToString(" · ")

    val body = BulkEditorBody(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
    }
    val list = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context)
        itemAnimator = null
        // Every edit calls notifyDataSetChanged, which scraps all ~11-13 visible rows into the
        // pool; at the default 5 per type the rest were rebuilt from scratch on each tap. With
        // room for a screenful they are only rebound.
        recycledViewPool.setMaxRecycledViews(0, 32)
    }
    body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

    lateinit var rows: RecyclerView.Adapter<RecyclerView.ViewHolder>
    lateinit var drag: ItemTouchHelper
    fun refresh() {
        if (prefs.autoSortHomeApps && !state.manualReordered)
            draft.sortWith { a, b -> HomeAppOrdering.comparator.compare(a.entry, b.entry) }
        filteredApps = availableApps.filter { candidate -> draft.none { it.entry.sameTarget(candidate) } }
        rows.notifyDataSetChanged()
    }
    fun rename(row: DraftHomeRow) {
        state.renameId = row.id
        val input = EditText(this).apply {
            setText(state.renameText ?: row.entry.name)
            selectAll()
            maxLines = 2
            hint = getString(R.string.home_bulk_name)
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            doAfterTextChanged { state.renameText = it.toString() }
        }
        child?.dismiss()
        child = AlertDialog.Builder(this).setTitle(R.string.home_bulk_rename).setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> state.renameId = null; state.renameText = null }
            .setOnCancelListener { state.renameId = null; state.renameText = null }
            .setPositiveButton(R.string.save_changes, null).create().also { dialog ->
                // Keep the existing IME resize behavior on older Android releases.
                @Suppress("DEPRECATION")
                dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialog.showForLauncher()
                dialog.fitEditorIme(activity)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) input.error = getString(R.string.home_bulk_name_required)
                    else {
                        if (row in draft) { row.entry = row.entry.copy(name = name); refresh() }
                        state.renameId = null
                        state.renameText = null
                        dialog.dismiss()
                    }
                }
            }
    }
    fun edit(row: DraftHomeRow) {
        val index = draft.indexOf(row)
        if (index < 0) return
        child?.dismiss()
        child = AlertDialog.Builder(this).setTitle(row.entry.name)
            .setItems(arrayOf(getString(R.string.close), getString(R.string.home_bulk_rename), getString(R.string.home_bulk_up),
                getString(R.string.home_bulk_down))) { _, which ->
                when (which) {
                    1 -> rename(row)
                    2, 3 -> {
                        val target = (index + if (which == 2) -1 else 1).coerceIn(0, draft.lastIndex)
                        if (target != index) {
                            state.manualReordered = true
                            draft.removeAt(index)
                            draft.add(target, row)
                            refresh()
                            list.scrollToPosition(target + 1)
                            list.doOnNextLayout {
                                (list.findViewHolderForAdapterPosition(target + 1) as? BulkHomeRow)?.name?.apply {
                                    requestFocus()
                                    // Preserve the spoken reorder result while changing no editor behavior.
                                    @Suppress("DEPRECATION")
                                    announceForAccessibility(getString(R.string.home_bulk_position, row.entry.name,
                                        target + 1, draft.size))
                                }
                            }
                        }
                    }
                }
            }.create().also { it.showForLauncher() }
    }
    fun add(entry: HomeAppEntry) {
        if (draft.size >= 512) { showToast(getString(R.string.home_bulk_limit)); return }
        draft.add(DraftHomeRow(nextId++, entry))
        refresh()
        list.post {
            // Preserve the existing spoken add confirmation.
            @Suppress("DEPRECATION")
            list.announceForAccessibility(getString(R.string.home_bulk_added_announcement, entry.name))
        }
    }

    rows = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = draft.size + filteredApps.size + 2 + if (filteredApps.isEmpty()) 1 else 0
        override fun getItemViewType(position: Int) = when {
            position == 0 || position == draft.size + 1 -> 1
            filteredApps.isEmpty() && position == draft.size + 2 -> 2
            else -> 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
            if (type == 0) BulkHomeRow(parent.context) else BulkSection(parent.context, type == 1)
        @android.annotation.SuppressLint("ClickableViewAccessibility") // Returning false lets TextView handle ACTION_UP and performClick itself.
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is BulkSection) {
                holder.title.text = when (position) {
                    0 -> getString(R.string.home_bulk_on_home)
                    draft.size + 1 -> getString(R.string.home_bulk_other_apps)
                    else -> if (loaded) getString(R.string.home_bulk_no_matches) else getString(R.string.loading_apps)
                }
                return
            }
            holder as BulkHomeRow
            val added = position <= draft.size
            val row = if (added) draft[position - 1] else null
            val entry = row?.entry ?: filteredApps[position - draft.size - 2]
            val text = label(entry) // Once per bind: each call scans the whole draft.
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = added
            holder.check.contentDescription = getString(
                if (added) R.string.home_bulk_uncheck else R.string.home_bulk_check, text)
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked && !added) {
                    add(entry)
                } else if (!checked && row != null) {
                    draft.remove(row)
                    refresh()
                    list.post {
                        // Preserve the existing spoken remove confirmation.
                        @Suppress("DEPRECATION")
                        list.announceForAccessibility(getString(R.string.home_bulk_removed_announcement, entry.name))
                    }
                }
            }
            holder.name.text = text
            holder.name.contentDescription = if (row == null) getString(R.string.home_bulk_check, text)
                else getString(R.string.home_bulk_edit, text)
            holder.name.setOnClickListener { if (row == null) add(entry) else edit(row) }
            holder.handle.visibility = if (added) View.VISIBLE else View.GONE
            holder.handle.contentDescription = getString(R.string.home_reorder_app, text)
            holder.handle.setOnClickListener { if (row != null) edit(row) }
            holder.handle.setOnTouchListener { _, event ->
                if (row != null && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) drag.startDrag(holder)
                false
            }
        }
    }
    list.adapter = rows
    drag = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false
        override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (holder.bindingAdapterPosition in 1..draft.size) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            else makeMovementFlags(0, 0)
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition - 1
            val b = to.bindingAdapterPosition - 1
            if (a !in draft.indices || b !in draft.indices) return false
            state.manualReordered = true
            draft.add(b, draft.removeAt(a))
            rows.notifyItemMoved(a + 1, b + 1)
            return true
        }
    }).also { it.attachToRecyclerView(list) }

    load = scope.launch {
        availableApps = getAppsList(applicationContext, prefs).mapNotNull { app ->
            when (app) {
                is AppModel.App -> HomeAppEntry(app.appLabel, app.appPackage, app.user.toString(), app.activityClassName.orEmpty())
                is AppModel.PinnedShortcut -> HomeAppEntry(app.appLabel, app.appPackage, app.user.toString(),
                    shortcut = true, shortcutId = app.shortcutId)
                else -> null
            }
        }.distinctBy { it.identity }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        loaded = true
        if (active) refresh()
    }
    refresh()
    return AlertDialog.Builder(this).setTitle(R.string.home_bulk_dialog_title).setView(body)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.save_changes) { _, _ ->
            prefs.replaceHomeApps(draft.map { it.entry }, automatic = if (state.manualReordered) false else null)
            onSaved()
        }
        .create().apply {
            setOnShowListener { state.renameId?.let { id -> draft.find { it.id == id }?.let(::rename) } }
            setOnDismissListener {
                active = false
                load?.cancel()
                child?.dismiss()
                if (!activity.isChangingConfigurations) {
                    state.rows = null
                    state.renameId = null
                    state.renameText = null
                    state.manualReordered = false
                }
            }
        }
}

private class BulkSection(context: Context, heading: Boolean) : RecyclerView.ViewHolder(TextView(context, null, 0,
    if (heading) R.style.TextMedium else R.style.TextSmall).apply {
    minHeight = 48.dpToPx()
    gravity = Gravity.CENTER_VERTICAL
    setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 4.dpToPx())
    ViewCompat.setAccessibilityHeading(this, heading)
}) {
    val title = itemView as TextView
}

private class BulkHomeRow(context: Context) : RecyclerView.ViewHolder(LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = RecyclerView.LayoutParams(-1, -2)
}) {
    val handle = TextView(context, null, 0, R.style.TextSmall).apply {
        minWidth = 48.dpToPx(); minHeight = 48.dpToPx(); gravity = Gravity.CENTER
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_drag_handle, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(context.getColorFromAttr(R.attr.primaryColor)))
        setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
        isFocusable = true
        applyFocusOutline(context.getColorFromAttr(R.attr.primaryColor))
    }
    val check = CheckBox(context).apply { minWidth = 48.dpToPx(); minHeight = 48.dpToPx() }
    val name = TextView(context, null, 0, R.style.TextSmall).apply {
        minHeight = 48.dpToPx(); gravity = Gravity.CENTER_VERTICAL; isFocusable = true
        setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        applyFocusOutline(context.getColorFromAttr(R.attr.primaryColor))
    }
    init {
        (itemView as LinearLayout).apply {
            addView(check, LinearLayout.LayoutParams(48.dpToPx(), -2))
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(handle, LinearLayout.LayoutParams(48.dpToPx(), -2))
        }
    }
}

class DraftHomeRow(val id: Long, var entry: HomeAppEntry)

class HomeAppsDraftState : ViewModel() {
    var rows: MutableList<DraftHomeRow>? = null
    var manualReordered: Boolean = false
    var renameId: Long? = null
    var renameText: String? = null
}

private class BulkEditorBody(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve room for the Settings header and Save/Cancel footer on short screens.
        val cap = (resources.displayMetrics.heightPixels - 180.dpToPx()).coerceAtLeast(180.dpToPx())
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else MeasureSpec.getSize(heightMeasureSpec).coerceAtMost(cap)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

private fun AlertDialog.fitEditorIme(activity: android.app.Activity) {
    val dialogWindow = window ?: return
    val decor = dialogWindow.decorView
    var lastHeight = -1
    val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val visible = android.graphics.Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(visible)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val ime = if (android.os.Build.VERSION.SDK_INT >= 30 &&
            decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
            decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0 else 0
        val available = if (android.os.Build.VERSION.SDK_INT >= 30) screenHeight - ime else visible.height()
        val constrained = available < screenHeight * .8f
        val height = if (constrained) available.coerceAtLeast(1) else ViewGroup.LayoutParams.MATCH_PARENT
        if (height != lastHeight) {
            lastHeight = height
            val compact = constrained && available < 320.dpToPx()
            findViewById<View>(androidx.appcompat.R.id.title_template)?.visibility = if (compact) View.GONE else View.VISIBLE
            dialogWindow.setGravity(Gravity.TOP)
            dialogWindow.attributes = dialogWindow.attributes.apply { y = 0 }
            dialogWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
    }
    decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            v.removeOnAttachStateChangeListener(this)
        }
    })
}
