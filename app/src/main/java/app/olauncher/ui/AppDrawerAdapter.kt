package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AppCategory
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterAppMenuBinding
import app.olauncher.databinding.AdapterAppRenameBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.databinding.AdapterCategoryHeaderBinding
import app.olauncher.helper.IconCache
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.styleTextTree
import app.olauncher.helper.tintCompoundDrawables
import app.olauncher.helper.withAlpha
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
    private val appCategoryListener: (AppModel, View) -> Unit = { _, _ -> },
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        const val VIEW_TYPE_CATEGORY = 2

        /** Gap between a drawer icon and its label, in pixels at this device's density. */
        private val ICON_GAP_PX = 12.dpToPx()

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user &&
                        oldItem.activityClassName == newItem.activityClassName

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true
                oldItem is AppModel.CategoryHeader && newItem is AppModel.CategoryHeader ->
                    oldItem.category == newItem.category && oldItem.user == newItem.user

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    /**
     * Text colour for the active home screen colour theme, or 0 to leave the theme's own colours
     * alone. Rows are recycled, so this has to be applied on every bind rather than once over the
     * tree the way the home screen does it.
     */
    var themeTextColor: Int = 0

    /**
     * Apps muted for notification badges, keyed "package|user". Only consulted in the badge
     * filter screen, where the label carries the state so the whole list is readable at a glance
     * rather than needing a tap to find out.
     */
    var mutedKeys: Set<String> = emptySet()

    /**
     * Icon settings for the drawer. [iconSizePx] of 0 means icons are off.
     *
     * Loads are tagged with the row's own package so a slow load landing after the row has been
     * recycled onto a different app is dropped rather than painting the wrong icon - the classic
     * RecyclerView image bug, and very visible during a fling through 200 apps.
     */
    var iconSizePx: Int = 0
    var iconGrayscale: Boolean = false
    var iconScope: CoroutineScope? = null

    /** CSS-style font weight for every row; see Constants.TextWeight. */
    var textWeight: Int = 400
    var labelSizeSp: Float = 24f
    var rowMinHeightDp: Int = 48

    private var autoLaunch = true
    private var isBangSearch = false
    var allowAutoLaunch = true
    @Volatile var browseCategories = false
    @Volatile var emptyQueryShowsAll = true
    var categoryLabels: Map<Int, String> = emptyMap()
    var browseSort = -1
    var onResultsChanged: (() -> Unit)? = null
    @Volatile private var requestedQuery = ""
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            is AppModel.CategoryHeader -> VIEW_TYPE_CATEGORY
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CATEGORY -> CategoryViewHolder(
                AdapterCategoryHeaderBinding.inflate(inflater, parent, false)).also { holder ->
                // Headings outrank their rows by weight first; the +4sp size only reinforces it.
                holder.binding.categoryTitle.applyTextWeight((textWeight + 100).coerceAtMost(700))
                holder.binding.categoryTitle.setTextColor(rowColor(parent.context))
                holder.binding.categoryRule.setBackgroundColor(holder.binding.categoryTitle.currentTextColor)
            }
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(inflater, parent, false)).also { style(it.itemView) }
            else -> ViewHolder(AdapterAppDrawerBinding.inflate(inflater, parent, false)).also { holder ->
                style(holder.itemView)
                // App icons keep their artwork; only the action glyphs follow the text theme.
                if (themeTextColor != 0)
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(holder.binding.appTitle, null)
            }
        }
    }

    /**
     * Colour, weight and focus ring, once per row when it is built. This ran on every bind, and
     * under a colour theme allocated a ColorStateList per TextView each time - but the theme
     * colour and weight are set before the first row exists and never change for this adapter,
     * and a recycled row keeps what was applied. The lazily built menu and rename field get the
     * same treatment when they are inflated (ViewHolder.inflated).
     */
    private fun style(view: View, focusRing: Boolean = true) {
        view.styleTextTree(
            if (themeTextColor != 0) themeTextColor else null,
            if (themeTextColor != 0) themeTextColor.withAlpha(0xB3) else null,
            textWeight,
        )
        // The action row's icons are compound drawables, which carry the APP theme's tint -
        // invisible on a drawer painted by a colour theme of the opposite polarity.
        if (themeTextColor != 0) view.tintCompoundDrawables(themeTextColor)
        if (focusRing) view.applyFocusOutline(rowColor(view.context))
    }

    private var primaryColor = 0

    private fun rowColor(context: Context): Int = if (themeTextColor != 0) themeTextColor else
        primaryColor.takeIf { it != 0 } ?: context.getColorFromAttr(R.attr.primaryColor).also { primaryColor = it }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            val appModel = getItem(position)
            if (holder is CategoryViewHolder && appModel is AppModel.CategoryHeader) {
                holder.binding.categoryTitle.text = appModel.appLabel
                holder.binding.categoryTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSizeSp + 4f)
                holder.binding.categoryTitle.gravity = appLabelGravity
                return
            }
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> {
                    holder.bind(appModel)
                    holder.binding.appTitle.apply {
                        textSize = labelSizeSp
                        // setMinHeight requests a layout even when the value is the same.
                        val floor = rowMinHeightDp.dpToPx()
                        if (minHeight != floor) minHeight = floor
                    }
                    bindIcon(holder, appModel)
                    bindMutedState(holder, appModel)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Badge choices keep every app readable, with an explicit state and accessible name. */
    private fun bindMutedState(holder: ViewHolder, appModel: AppModel) {
        val title = holder.binding.appTitle
        val state = holder.binding.badgeState
        if (flag != Constants.FLAG_BADGE_FILTER) {
            state.isVisible = false
            return
        }
        val muted = "${appModel.appPackage}|${appModel.user}" in mutedKeys
        state.isVisible = true
        state.setText(if (muted) R.string.off else R.string.on)
        title.setPaddingRelative(title.paddingStart, title.paddingTop, 96.dpToPx(), title.paddingBottom)
        title.contentDescription = title.contentDescription ?: appModel.appLabel
        ViewCompat.setStateDescription(title, state.text)
        ViewCompat.setAccessibilityDelegate(title, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View, info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = !muted
            }
        })
    }

    private fun bindIcon(holder: ViewHolder, appModel: AppModel) {
        holder.iconJob?.cancel()
        holder.iconJob = null
        val title = holder.binding.appTitle
        // Always clear first: this row was showing some other app a moment ago.
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)

        val size = iconSizePx
        val scope = iconScope
        if (size <= 0 || scope == null) return
        if (appModel !is AppModel.App || appModel.appPackage.isEmpty()) return

        title.compoundDrawablePadding = ICON_GAP_PX
        val packageName = appModel.appPackage
        val className = appModel.activityClassName.orEmpty()
        val user = appModel.user

        IconCache.peek(packageName, className, user, size, iconGrayscale)?.let {
            title.setCompoundDrawablesRelative(it, null, null, null)
            return
        }

        val context = title.context.applicationContext
        holder.iconJob = scope.launch {
            val icon = withContext(Dispatchers.IO) {
                IconCache.load(context, packageName, className, user, size, iconGrayscale)
            } ?: return@launch
            // The holder may have been recycled onto a different app while this was loading.
            val current = currentList.getOrNull(holder.bindingAdapterPosition)
            if (current !is AppModel.App || current.appPackage != packageName || current.user != user ||
                current.activityClassName.orEmpty() != className) return@launch
            title.setCompoundDrawablesRelative(icon, null, null, null)
        }
    }

    override fun getFilter(): Filter = this.appFilter

    fun filterQuery(query: CharSequence?) {
        requestedQuery = query?.toString().orEmpty()
        appFilter.filter(requestedQuery)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? ViewHolder)?.iconJob?.cancel()
        super.onViewRecycled(holder)
    }

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val source = appsList
                val appFilteredList = when {
                    browseCategories -> categorized(source)
                    browseSort >= 0 -> sortedBrowse(source)
                    charSearch.isNullOrBlank() -> if (emptyQueryShowsAll) source else emptyList()
                    else -> {
                        // The query's forms once per keystroke, not once per app.
                        val plain = charSearch.trim()
                        val normalized = charSearch.normalizeForSearch()
                        source.filter { app ->
                            app !is AppModel.PrivateSpaceHeader && app !is AppModel.CategoryHeader &&
                                appLabelMatches(app.appLabel, plain, normalized)
                        }
                    }
                }.toMutableList()

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (constraint?.toString().orEmpty() != requestedQuery) return
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        onResultsChanged?.invoke()
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (!browseCategories && itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
                && appFilteredList[0] !is AppModel.CategoryHeader
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, plain: CharSequence, normalized: String): Boolean {
        if (appLabel.contains(plain, true)) return true
        return normalized.isNotEmpty() &&
            normalizedLabels.getOrPut(appLabel) { appLabel.normalizeForSearch() }.contains(normalized, true)
    }

    /**
     * Labels in search form (NFD, marks and separators stripped), by label. Most labels miss the
     * plain match, so every keystroke used to run NFD plus two regexes over nearly every label.
     * Keyed by the label string, so a rename or a new list can never read a stale entry, and
     * concurrent because the filter runs on its own thread while setAppList runs on main.
     */
    private val normalizedLabels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun CharSequence.normalizeForSearch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(separatorsRegex, "")

    fun setAppList(appsList: MutableList<AppModel>) {
        this.appsList = appsList.toMutableList()
        // ponytail: whole-cache reset when it outgrows the list; renames are rare enough.
        if (normalizedLabels.size > 2 * appsList.size + 16) normalizedLabels.clear()
        filterQuery(requestedQuery)
    }

    private fun sortedBrowse(source: List<AppModel>): List<AppModel> {
        val privateStart = source.indexOfFirst { it is AppModel.PrivateSpaceHeader }
        val main = if (privateStart < 0) source else source.take(privateStart)
        val alpha = compareBy<AppModel, String>(java.text.Collator.getInstance()) { it.appLabel }
        val sorted = main.sortedWith(when (browseSort) {
            2 -> alpha.reversed()
            3 -> compareByDescending<AppModel> { (it as? AppModel.App)?.installedAt ?: 0 }.then(alpha)
            else -> alpha
        })
        return if (privateStart < 0) sorted else sorted + source.drop(privateStart)
    }

    private fun categorized(source: List<AppModel>): List<AppModel> {
        // Private-space controls and contents stay together behind their existing lock boundary.
        val privateStart = source.indexOfFirst { it is AppModel.PrivateSpaceHeader }
        val main = if (privateStart < 0) source else source.take(privateStart)
        val groups = main.filter { it.appPackage.isNotEmpty() }.groupBy {
            if (it is AppModel.App) it.category.takeIf(categoryLabels::containsKey) ?: AppCategory.OTHER
            else AppCategory.SHORTCUTS
        }
        val collator = java.text.Collator.getInstance()
        val order = AppCategory.names.keys.toList()
        val categories = groups.keys.sortedBy { order.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        return buildList {
            for (category in categories) {
                add(AppModel.CategoryHeader(category, categoryLabels[category].orEmpty()))
                addAll(groups.getValue(category).sortedWith(compareBy(collator) { it.appLabel }))
            }
            if (privateStart >= 0) addAll(source.drop(privateStart))
        }
    }

    fun launchFirstInList() {
        val first = currentList.firstOrNull { it is AppModel.App || it is AppModel.PinnedShortcut }
        if (first != null) appClickListener(first)
    }

    class CategoryViewHolder(val binding: AdapterCategoryHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            ViewCompat.setAccessibilityHeading(binding.categoryTitle, true)
        }
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    inner class ViewHolder(val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null

        /** Built on first use (see adapter_app_drawer.xml); null until then. */
        private var menu: AdapterAppMenuBinding? = null
        private var rename: AdapterAppRenameBinding? = null

        /**
         * What this row shows now. The menu and rename listeners are wired once, when those views
         * are built, so they read the row's current app here - never an app captured by an
         * earlier bind of this recycled row.
         */
        private var model: AppModel? = null

        fun bind(appModel: AppModel) = with(binding) {
            model = appModel
            menu?.root?.visibility = View.GONE
            rename?.root?.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle
            // The dot is the only thing distinguishing a work-profile app from a personal
            // one with the same name, and it was invisible to a screen reader.
            otherProfileIndicator.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            appTitle.contentDescription = if (appModel.user != myUserHandle)
                root.context.getString(R.string.a11y_work_profile, appModel.appLabel)
            else null

            appTitle.setOnClickListener { appClickListener(appModel) }
            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) openMenu(appModel)
                true
            }
        }

        /** The inflated subtree gets the styling onCreateViewHolder gave the rest of the row. */
        private fun inflated(view: View) = style(view, focusRing = false)

        private fun menu(): AdapterAppMenuBinding = menu
            ?: AdapterAppMenuBinding.bind(binding.appMenuStub.inflate()).also { m ->
                menu = m
                inflated(m.root)
                m.appRename.setOnClickListener { model?.let(::openRename) }
                m.appInfo.setOnClickListener { model?.let(appInfoListener) }
                m.appCategory.setOnClickListener { anchor -> model?.let { appCategoryListener(it, anchor) } }
                m.appDelete.setOnClickListener { model?.let(appDeleteListener) }
                m.appHide.setOnClickListener { model?.let { appHideListener(it, bindingAdapterPosition) } }
                m.appMenuClose.setOnClickListener { close(m.root) }
            }

        private fun rename(): AdapterAppRenameBinding = rename
            ?: AdapterAppRenameBinding.bind(binding.renameStub.inflate()).also { r ->
                rename = r
                inflated(r.root)
                r.etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    binding.appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
                }
                // A TextWatcher used to be added here on every bind and never removed, so a recycled
                // row accumulated one per rebind, each holding a stale AppModel and each running a
                // PackageManager lookup on every keystroke. It was also a no-op: onTextChanged cleared
                // the hint and afterTextChanged immediately put it back, and openRename already sets
                // that hint when the field opens.
                r.etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                    if (actionCode == EditorInfo.IME_ACTION_DONE) {
                        val appModel = model
                        val renameLabel = r.etAppRename.text.toString().trim()
                        if (appModel != null && renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                            appRenameListener(appModel, renameLabel)
                            r.root.visibility = View.GONE
                        }
                        true
                    }
                    false
                }
                r.tvSaveRename.setOnClickListener {
                    val appModel = model ?: return@setOnClickListener
                    r.etAppRename.hideKeyboard()
                    val renameLabel = r.etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                    } else {
                        appRenameListener(
                            appModel,
                            getAppName(r.etAppRename.context, appModel.appPackage, appModel.user)
                        )
                    }
                    r.root.visibility = View.GONE
                }
                r.appRenameClose.setOnClickListener { close(r.root) }
            }

        private fun openMenu(appModel: AppModel) = with(menu()) {
            val ctx = binding.root.context
            appDelete.alpha = when (
                appModel is AppModel.PinnedShortcut || !ctx.isSystemApp(appModel.appPackage, appModel.user)
            ) {
                true -> 1.0f
                false -> 0.5f
            }
            appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                ctx.getString(R.string.adapter_show)
            else
                ctx.getString(R.string.adapter_hide)
            val keyboardFocus = binding.appTitle.hasFocus()
            binding.appTitle.visibility = View.INVISIBLE
            appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                true -> 0.5f
                false -> 1.0f
            }
            appHideLayout.visibility = View.VISIBLE
            // The menu replaces the row in place, so the app's name is covered and
            // drops out of the accessibility tree - "Uninstall, button" with no
            // target. Name the app on every action, and say the menu opened at all.
            appDelete.contentDescription =
                ctx.getString(R.string.a11y_pair, appDelete.text, appModel.appLabel)
            appRename.contentDescription =
                ctx.getString(R.string.a11y_pair, appRename.text, appModel.appLabel)
            appHide.contentDescription =
                ctx.getString(R.string.a11y_pair, appHide.text, appModel.appLabel)
            appInfo.contentDescription =
                ctx.getString(R.string.a11y_pair, appInfo.text, appModel.appLabel)
            // Keep the existing spoken menu context when the app row is covered.
            @Suppress("DEPRECATION")
            binding.root.announceForAccessibility(appModel.appLabel)
            // Only allow renaming non hidden apps
            appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
            appMenuClose.contentDescription = ctx.getString(R.string.a11y_pair,
                appMenuClose.text, appModel.appLabel)
            appCategory.isVisible = appModel is AppModel.App && flag == Constants.FLAG_LAUNCH_APP
            appCategory.contentDescription = ctx.getString(R.string.choose_app_category) + ": " + appModel.appLabel
            appHideLayout.post {
                if (!appHideLayout.isVisible) return@post
                if (keyboardFocus) appDelete.requestFocus()
            }
        }

        private fun openRename(appModel: AppModel) {
            if (appModel.appPackage.isEmpty()) return
            val r = rename()
            r.etAppRename.hint = getAppName(r.etAppRename.context, appModel.appPackage, appModel.user)
            r.etAppRename.setText(appModel.appLabel)
            r.etAppRename.setSelectAllOnFocus(true)
            r.root.visibility = View.VISIBLE
            menu?.root?.visibility = View.GONE
            r.etAppRename.showKeyboard()
            r.etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
        }

        private fun close(panel: View) {
            val keyboardFocus = panel.hasFocus()
            panel.visibility = View.GONE
            binding.appTitle.visibility = View.VISIBLE
            if (keyboardFocus) binding.appTitle.requestFocus()
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
