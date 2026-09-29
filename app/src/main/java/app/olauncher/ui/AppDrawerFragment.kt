package app.olauncher.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.isNotEmpty
import androidx.core.view.updatePadding
import android.content.res.ColorStateList
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AppCategory
import app.olauncher.data.ColorTheme
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.HomeForeground
import app.olauncher.helper.IconCache
import app.olauncher.helper.NotificationCounts
import app.olauncher.helper.applyTextWeight
import app.olauncher.helper.LauncherMotion
import app.olauncher.helper.applyFocusOutline
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showPopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.clearLayoutTransitions
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.tintTextTree
import app.olauncher.helper.withAlpha
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private companion object {
        /** Drawer icon edge length. Matched to the home screen so the two read as one launcher. */
        const val ICON_SIZE_DP = 32
    }

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false

    /** Set by whoever opened the drawer; see Constants.KeyboardMode. */
    private var keyboardMode = Constants.KeyboardMode.AUTO
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false
    private var searchMode = false
    private var searchFromBrowse = false
    private var browsePosition = 0
    private var restoreBrowse = false
    private lateinit var searchBack: OnBackPressedCallback
    private var modeAnimator: android.animation.Animator? = null
    private var iconWarmJob: Job? = null
    private var iconPrefetchJob: Job? = null
    private var iconPrefetchBucket = -1
    /** Search with the keyboard up; see applyImeCompaction. */
    private var imeCompact = false

    override fun suppressMotion() {
        modeAnimator?.end()
        super.suppressMotion()
    }

    override fun onPowerStateChanged() {
        super.onPowerStateChanged()
        iconPrefetchBucket = -1
        if (LauncherMotion.savingPower(requireContext(), prefs)) {
            iconWarmJob?.cancel()
            iconWarmJob = null
            iconPrefetchJob?.cancel()
            iconPrefetchJob = null
        } else preloadIcons(currentAppList)
    }

    private val viewModel: MainViewModel by activityViewModels()
    private var drawerCard: android.widget.LinearLayout? = null
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
            keyboardMode = it.getInt(Constants.Key.KEYBOARD_MODE, Constants.KeyboardMode.AUTO)
        }
        searchMode = savedInstanceState?.getBoolean("searchMode")
            ?: (flag != Constants.FLAG_LAUNCH_APP || keyboardMode == Constants.KeyboardMode.SHOW)
        searchFromBrowse = savedInstanceState?.getBoolean("searchFromBrowse") ?: false
        browsePosition = savedInstanceState?.getInt("browsePosition") ?: 0

        initViews()
        initSearch()
        initAdapter()
        initNavigation()
        initObservers()
        initClickListeners()
        showSearch(searchMode, animate = false)
        savedInstanceState?.getString("query")?.let { binding.search.setQuery(it, false) }
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag == Constants.FLAG_BADGE_FILTER)
            binding.search.queryHint = getString(R.string.badge_filter_hint)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP ||
            flag == Constants.FLAG_HOME_ADD_AUTO ||
            flag in Constants.FLAG_HOME_SLOT_BASE + 1..Constants.FLAG_HOME_SLOT_BASE + 512)
            binding.search.queryHint = getString(R.string.please_select_an_app)
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            // A field reads from its start edge whatever the list's label alignment; centred, the
            // prompt and the first keystroke sat in different places.
            searchTextView?.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            searchTextView?.addOnLayoutChangeListener { field, _, _, _, _, _, _, _, _ -> alignPrompt(field as TextView) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + android.net.Uri.encode(query))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    binding.searchPrompt.isVisible = searchMode &&
                        flag == Constants.FLAG_LAUNCH_APP && newText.isEmpty()
                    adapter.allowAutoLaunch = searchMode && prefs.autoLaunchFromSearch && !isSearchComposing()
                    adapter.filterQuery(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    // Some legacy IMEs expose only locale when languageTag is empty.
    @Suppress("DEPRECATION")
    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    /**
     * Warms the icon cache for the top of the list, off the main thread, as soon as the app
     * list arrives - so the rows that are about to be drawn already have their icons.
     */
    private fun preloadIcons(apps: List<AppModel>?) {
        iconWarmJob?.cancel()
        iconWarmJob = null
        iconPrefetchJob?.cancel()
        iconPrefetchJob = null
        iconPrefetchBucket = -1
        if (apps.isNullOrEmpty() || !(if (searchMode) prefs.searchShowIcons else prefs.showDrawerIcons)) return
        if (app.olauncher.helper.LauncherMotion.savingPower(requireContext(), prefs)) return
        val size = prefs.appIconSize.dpToPx()
        val grayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
        val context = requireContext().applicationContext
        val ordered = if (!searchMode && flag == Constants.FLAG_LAUNCH_APP)
            app.olauncher.data.AppCategory.iconWarmOrder(apps, prefs.drawerSort)
        else apps.filterIsInstance<AppModel.App>()
        val capacity = IconCache.warmCapacity(size)
        val entries = ordered.take(capacity)
            .map { Triple(it.appPackage, it.activityClassName.orEmpty(), it.user) }
        iconWarmJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            IconCache.warm(context, entries, size, grayscale, limit = capacity)
        }
    }

    /** Resolve upcoming icons while they are still below the visible viewport. */
    private fun prefetchIconsAhead() {
        if (searchMode || !prefs.showDrawerIcons ||
            LauncherMotion.savingPower(requireContext(), prefs)) return
        val first = linearLayoutManager.findFirstVisibleItemPosition()
        val last = linearLayoutManager.findLastVisibleItemPosition()
        if (first < 0 || last < first) return
        val bucket = first / 4
        if (bucket == iconPrefetchBucket) return
        iconPrefetchBucket = bucket
        val size = prefs.appIconSize.dpToPx()
        val grayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
        val entries = ArrayList<Triple<String, String, android.os.UserHandle>>(12)
        val rows = adapter.currentList
        for (index in last + 1 until rows.size) {
            val app = rows[index] as? AppModel.App ?: continue
            entries.add(Triple(app.appPackage, app.activityClassName.orEmpty(), app.user))
            if (entries.size == 12) break
        }
        if (entries.isEmpty()) return
        val context = requireContext().applicationContext
        iconPrefetchJob?.cancel()
        val firstViewport = iconWarmJob
        iconPrefetchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Scrolling ahead must not cancel the first viewport's warmup.
            firstViewport?.join()
            IconCache.warm(context, entries, size, grayscale, limit = 12)
        }
    }

    /** The prompt starts exactly where typed text starts: the field's text edge, in the prompt's frame. */
    private fun alignPrompt(field: TextView) {
        val prompt = _binding?.searchPrompt ?: return
        val frame = prompt.parent as? View ?: return
        val a = IntArray(2).also(field::getLocationInWindow)
        val b = IntArray(2).also(frame::getLocationInWindow)
        val start = if (field.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            (b[0] + frame.width) - (a[0] + field.width) + field.totalPaddingRight
        else a[0] - b[0] + field.totalPaddingLeft
        // Guarded: an unchanged padding requests no layout, so this cannot loop.
        if (prompt.paddingStart != start) prompt.setPaddingRelative(start.coerceAtLeast(0), 0, 0, 0)
    }

    /**
     * The field you type into is AppCompat's, inside the SearchView, and padding on the
     * SearchView does not reach it - raising that padding made the inner field shorter, not
     * taller. It measured 36dp, under Android's 48dp minimum, on the screen this launcher
     * opens most.
     */
    private fun applySearchFieldHeight() {
        // 48dp at least, and tall enough for one line of the field's own text: at 200% text the
        // typed letters (30sp, so 60sp) were clipped by a fixed 48dp. The full line including the
        // font's padding, as the prompt beside it measures: with lineHeight the field came out
        // 17px shorter than the prompt, and the typed line sat above the prompt and Back.
        val field = binding.search.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
        val target = maxOf(48.dpToPx(), (field?.let {
            it.paint.fontMetricsInt.let { fm -> fm.bottom - fm.top } + it.paddingTop + it.paddingBottom } ?: 0))
        listOf(
            androidx.appcompat.R.id.search_plate,
            androidx.appcompat.R.id.search_edit_frame,
        ).forEach { id -> binding.search.findViewById<View>(id)?.minimumHeight = target }

        // The field the user actually touches is search_src_text, and it stayed 36dp inside a
        // 48dp plate through three attempts: minimumHeight, then minHeight, then dropping the
        // plate's padding. Measuring after each one said the same thing, so this stops
        // negotiating with AppCompat's measurement and states the height outright.
        binding.search.findViewById<View>(androidx.appcompat.R.id.search_src_text)?.let { field ->
            field.minimumHeight = target
            (field as? TextView)?.minHeight = target
            field.layoutParams = field.layoutParams?.also { it.height = target }
        }
    }

    /**
     * animateLayoutChanges is set in the drawer's XML, and nothing was clearing the
     * LayoutTransition it creates - so the long-press action menu and the rename row kept
     * animating with "Remove animations" on. The home screen's copy was already gated.
     */
    private fun applyMotionPreference() {
        binding.root.clearLayoutTransitions()
    }

    /**
     * Tints the drawer chrome for the chosen colour theme. Rows are handled by the adapter,
     * because RecyclerView recycles them and a one-off tree walk would miss every row scrolled
     * into view afterwards.
     */
    private fun applyColorTheme() {
        if (!ColorTheme.isCustom(prefs.colorThemeId)) return
        val theme = ColorTheme.byId(prefs.colorThemeId)
        // Opaque, same as the home screen: the drawer's own shade colour over an out-of-sync
        // wallpaper is the same unreadable-text problem in a different place.
        binding.root.setBackgroundColor(theme.background)
        binding.root.tintTextTree(theme.text, theme.text.withAlpha(0xB3))
        adapter.themeTextColor = theme.text
    }

    /**
     * Hands the adapter what it needs to draw icons. The scope is the fragment's, so every
     * in-flight icon load is cancelled when the drawer closes rather than outliving it.
     */
    private fun applyIconSettings() {
        adapter.iconSizePx = if (if (searchMode) prefs.searchShowIcons else prefs.showDrawerIcons) prefs.appIconSize.dpToPx() else 0
        adapter.iconGrayscale = prefs.iconStyle == Constants.IconStyle.GRAYSCALE
        adapter.iconScope = viewLifecycleOwner.lifecycleScope
        if (flag == Constants.FLAG_BADGE_FILTER) adapter.mutedKeys = prefs.badgeMutedApps
    }

    /** Mutes or unmutes an app for notification badges and repaints just that row. */
    private fun toggleBadgeMuted(appModel: AppModel) {
        if (appModel.appPackage.isEmpty()) return
        val key = "${appModel.appPackage}|${appModel.user}"
        val updated = prefs.badgeMutedApps.toMutableSet()
        val nowMuted = if (key in updated) {
            updated.remove(key)
            false
        } else {
            updated.add(key)
            true
        }
        prefs.badgeMutedApps = updated
        if (nowMuted) NotificationCounts.clearApp(key)
        adapter.mutedKeys = updated

        val position = adapter.currentList.indexOf(appModel)
        if (position >= 0) adapter.notifyItemChanged(position)
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (flag == Constants.FLAG_BADGE_FILTER) {
                    // Toggling stays on the screen: muting apps is something you do to several in
                    // a row, and bouncing back to settings after each one would be miserable.
                    toggleBadgeMuted(appModel)
                } else {
                    viewModel.selectedApp(appModel, flag)
                    if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                        findNavController().popBackStack(R.id.mainFragment, false)
                    else
                        findNavController().popBackStack()
                }
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader, is AppModel.CategoryHeader -> {}
                    is AppModel.PinnedShortcut -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }
                        // Our own unpin has no guaranteed LauncherApps callback, so rescan now to
                        // drop the row. An uninstall is covered by onPackageRemoved's rescan.
                        viewModel.getAppList()
                    }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
            },
            appHideListener = { appModel, _ ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.setAppList(adapter.appsList.filterNot { it == appModel }.toMutableList())

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                // Only the hidden-apps screen observes this list. Elsewhere drop it rather than
                // scan twice; Hidden apps rescans on entry and ignores the null.
                if (flag == Constants.FLAG_HIDDEN_APPS) viewModel.getHiddenApps()
                else viewModel.hiddenApps.value = null
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appCategoryListener = { app, anchor ->
                anchor.showPopupMenu(configure = { menu ->
                    menu.add(0, 100, 0, R.string.category_automatic)
                    AppCategory.names.filterKeys { it != AppCategory.SHORTCUTS }.forEach { (id, label) ->
                        menu.add(0, id + 10, id + 11, label)
                    }
                }) { item ->
                    prefs.setAppCategory(app.appPackage, app.user.toString(),
                        if (item.itemId == 100) null else item.itemId - 10)
                    viewModel.getAppList()
                }
            },
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -56.dpToPx() && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        // Its size is 0dp + weight, never its contents, so a search keystroke's list update need
        // not re-measure the whole drawer above it.
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.adapter = adapter
        adapter.textWeight = Constants.TextWeight.value(prefs.textWeight)
        adapter.categoryLabels = AppCategory.names.mapValues { getString(it.value) }
        applyColorTheme()
        applyMotionPreference()
        applySearchFieldHeight()
        applyIconSettings()
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        // Animate the content plane once, never a cascade of recycled rows.
        binding.recyclerView.layoutAnimation = null
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
                preloadIcons(it)
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filterQuery(binding.search.query)
    }

    private fun initClickListeners() {
        binding.searchPrompt.setOnClickListener {
            binding.search.requestFocus()
            binding.search.showKeyboard(true)
        }
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) prefetchIconsAhead()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(searchMode && prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        if (flag == Constants.FLAG_LAUNCH_APP) {
            goHome()
        } else findNavController().popBackStack()
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        binding.search.showKeyboard(searchMode &&
            when (keyboardMode) {
                Constants.KeyboardMode.SHOW -> prefs.autoShowKeyboard
                Constants.KeyboardMode.HIDE -> false
                else -> prefs.autoShowKeyboard
            }
        )
    }

    override fun onStop() {
        iconWarmJob?.cancel()
        iconWarmJob = null
        iconPrefetchJob?.cancel()
        iconPrefetchJob = null
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        modeAnimator?.cancel()
        modeAnimator = null
        adapter.onResultsChanged = null
        super.onDestroyView()
        searchTextView = null
        drawerCard = null
        imeCompact = false
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("searchMode", searchMode)
        outState.putBoolean("searchFromBrowse", searchFromBrowse)
        outState.putInt("browsePosition", browsePosition)
        // Under Settings in the back stack this fragment has no view when a recreate saves it.
        _binding?.let { outState.putString("query", it.search.query.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun initNavigation() {
        val color = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
            else requireContext().getColorFromAttr(R.attr.primaryColor)
        listOf(binding.drawerHome, binding.drawerMode, binding.drawerCustomize).forEach {
            it.setTextColor(color)
            it.applyFocusOutline(color)
        }
        listOf(binding.drawerHome, binding.drawerMode).forEach { control ->
            control.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }
        ViewCompat.setAccessibilityHeading(binding.drawerTitle, true)
        binding.drawerTitle.applyFocusOutline(color)
        binding.drawerCustomize.isVisible = false
        binding.drawerHome.isVisible = flag != Constants.FLAG_LAUNCH_APP
        binding.drawerHome.text = ""
        binding.drawerHome.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_arrow_back, 0, 0, 0)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.drawerHome,
            ColorStateList.valueOf(color))
        binding.drawerTitle.setTextAppearance(R.style.TextLarge)
        binding.drawerTitle.setTextColor(color)
        // After setTextAppearance, which resets the typeface: the title takes the rows' weight.
        binding.drawerNavigation.applyTextWeight(adapter.textWeight)
        styleSearchField(color)
        val root = binding.root
        val card = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        drawerCard = card
        while (root.isNotEmpty()) {
            val child = root.getChildAt(0)
            root.removeViewAt(0)
            card.addView(child)
        }
        root.addView(card, android.widget.LinearLayout.LayoutParams(-1, -1))
        applyDrawerSurface()
        if (flag == Constants.FLAG_LAUNCH_APP)
            binding.drawerTitle.setOnLongClickListener { binding.drawerCustomize.performClick(); true }
        binding.drawerCustomize.setOnClickListener {
            binding.search.hideKeyboard()
            customizePanel(if (searchMode) app.olauncher.helper.PanelSettings.Page.SEARCH else app.olauncher.helper.PanelSettings.Page.APPS) {
                showSearch(searchMode, false)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        binding.drawerHome.setOnClickListener {
            when {
                flag == Constants.FLAG_BADGE_FILTER -> findNavController().navigateUp()
                flag != Constants.FLAG_LAUNCH_APP -> findNavController().navigateUp()
                searchMode && searchFromBrowse -> showSearch(false)
                else -> goHome()
            }
        }
        binding.drawerMode.setOnClickListener {
            if (!searchMode) {
                browsePosition = linearLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
                searchFromBrowse = true
            }
            showSearch(!searchMode)
        }
        searchBack = object : OnBackPressedCallback(searchMode && searchFromBrowse) {
            override fun handleOnBackPressed() { showSearch(false) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(flag == Constants.FLAG_LAUNCH_APP) {
                override fun handleOnBackPressed() { goHome() }
            })
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBack)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val width = resources.configuration.screenWidthDp
            val side = if (width >= 600) ((width - 560) / 2).dpToPx() else 0
            val compact = searchMode && ime.bottom > 0
            if (compact != imeCompact) {
                imeCompact = compact
                applyImeCompaction()
            }
            view.updatePadding(left = side + bars.left, right = side + bars.right,
                top = bars.top + (if (legacyBrowseSurface() || compact) 8 else 64).dpToPx(),
                bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        adapter.onResultsChanged = {
            if (_binding != null) {
                val empty = adapter.currentList.isEmpty()
                val loading = currentAppList == null && flag == Constants.FLAG_LAUNCH_APP
                // The Search field already prompts for input; keep the empty page quiet.
                binding.drawerEmpty.isVisible = empty && (loading || !searchMode || binding.search.query.isNotEmpty())
                binding.drawerEmpty.setText(when {
                    loading -> R.string.loading_apps
                    !searchMode -> R.string.browse_no_apps
                    else -> R.string.search_no_matches
                })
                if (restoreBrowse) {
                    linearLayoutManager.scrollToPositionWithOffset(browsePosition, 0)
                    restoreBrowse = false
                }
            }
        }
        installSwipeHome()
    }

    private fun legacyBrowseSurface() =
        flag == Constants.FLAG_LAUNCH_APP && !searchMode && prefs.appsBrowserLayout == 0

    private fun applyDrawerSurface() {
        val legacy = legacyBrowseSurface()
        val card = drawerCard ?: return
        val narrow = resources.configuration.screenWidthDp <= 360
        val edge = if (legacy || narrow) 0 else 12.dpToPx()
        (card.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { params ->
            if (params.marginStart != edge || params.marginEnd != edge) {
                params.marginStart = edge
                params.marginEnd = edge
                card.layoutParams = params
            }
        }
        // Preserve full category words at large text on the narrowest phones.
        val inset = if (legacy || narrow) 0 else 8.dpToPx()
        val vertical = cardVerticalPadding()
        card.setPadding(inset, vertical, inset, vertical)
        // Full-bleed on narrow phones, so a radius and stroke would only run into the screen edge.
        card.background = if (legacy || narrow) null
            else app.olauncher.helper.settingsCardDrawable(requireContext(), prefs)
        binding.root.setBackgroundColor(when {
            ColorTheme.isCustom(prefs.colorThemeId) -> ColorTheme.byId(prefs.colorThemeId).background
            legacy -> legacyScrim()
            else -> app.olauncher.helper.settingsPageColor(requireContext(), prefs)
        })
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * The legacy surface is a scrim over the wallpaper, and its old 25% shade left white text at
     * 1.8:1 over a white wallpaper. The inverse colour at 0x8F keeps the theme's text at >= 4.9:1
     * over any wallpaper pixel (white text over pure white, black text over pure black). When the
     * wallpaper's own colour already disagrees with the theme it deepens to 80% (about 12.6:1).
     */
    private fun legacyScrim(): Int {
        val context = requireContext()
        val inverse = context.getColorFromAttr(R.attr.primaryColorInverseTrans80)
        val text = context.getColorFromAttr(R.attr.primaryColor)
        val over = if (ColorTheme.isCustom(prefs.colorThemeId)) HomeForeground.color(context, prefs)
            else HomeForeground.wallpaperText(context) ?: text
        val agrees = over == text
        return if (agrees) inverse.withAlpha(0x8F) else inverse
    }

    private fun cardVerticalPadding() = if (legacyBrowseSurface() || imeCompact) 0 else 16.dpToPx()

    /**
     * Keyboard up in a search: the card's padding and the list's tail go to results, and on
     * narrow phones the back control moves inline with the field so the title row's height does
     * too. At 320dp and 200% text the list had been 56px tall, shorter than one row.
     */
    private fun applyImeCompaction() {
        drawerCard?.let { card ->
            val vertical = cardVerticalPadding()
            card.setPadding(card.paddingLeft, vertical, card.paddingRight, vertical)
        }
        binding.recyclerView.updatePadding(bottom = if (imeCompact) 0 else 24.dpToPx())
        val inline = imeCompact && resources.configuration.screenWidthDp <= 360
        val back = binding.drawerHome
        val home = if (inline) binding.searchContainer else binding.drawerNavigation
        if (back.parent !== home) {
            (back.parent as? ViewGroup)?.removeView(back)
            home.addView(back, if (inline) 0 else binding.drawerNavigation.indexOfChild(binding.drawerTitle))
        }
        binding.drawerNavigation.isVisible = !inline
    }

    /**
     * A visible caret in the text colour, and placeholders at 70% so they read as placeholders.
     * Runs after applyColorTheme, whose tree tint would otherwise repaint the hidden native hint.
     */
    private fun styleSearchField(color: Int) {
        val placeholder = color.withAlpha(0xB3)
        binding.searchPrompt.setTextColor(placeholder)
        val field = searchTextView ?: return
        // Launcher Search draws its own prompt, at the typed text's start and size; the native hint
        // stays for screen readers.
        field.setHintTextColor(if (flag == Constants.FLAG_LAUNCH_APP) android.graphics.Color.TRANSPARENT else placeholder)
        field.isCursorVisible = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            field.textCursorDrawable = field.textCursorDrawable?.mutate()?.apply { setTint(color) }
    }

    private fun showSearch(show: Boolean, animate: Boolean = true) {
        searchMode = show
        applyDrawerSurface()
        binding.searchContainer.isVisible = show
        val launcherBrowser = flag == Constants.FLAG_LAUNCH_APP
        binding.searchPrompt.isVisible = show && launcherBrowser && binding.search.query.isEmpty()
        binding.drawerTitle.text = when {
            flag == Constants.FLAG_BADGE_FILTER -> getString(R.string.badge_filter)
            flag == Constants.FLAG_HIDDEN_APPS -> getString(R.string.hidden_apps)
            !launcherBrowser -> getString(R.string.please_select_an_app)
            show -> getString(R.string.search_mode)
            else -> getString(R.string.browse_apps)
        }
        binding.drawerTitle.gravity = (if (launcherBrowser && !show) android.view.Gravity.END
            else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL
        binding.drawerTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        binding.drawerTitle.contentDescription = null
        ViewCompat.setAccessibilityHeading(binding.drawerTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, binding.drawerTitle.text)
        if (launcherBrowser) ViewCompat.replaceAccessibilityAction(binding.drawerTitle,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(if (show) R.string.customize_search else R.string.customize_apps)) { _, _ ->
            binding.drawerCustomize.performClick(); true
        }
        binding.drawerHome.isVisible = !launcherBrowser || show
        binding.drawerHome.contentDescription = getString(when {
            flag == Constants.FLAG_BADGE_FILTER -> R.string.back_to_badges
            !launcherBrowser -> R.string.menu_back
            searchFromBrowse -> R.string.browse_apps
            else -> R.string.return_home
        })
        binding.drawerMode.isVisible = launcherBrowser && !show
        if (launcherBrowser && !show && binding.drawerNavigation.indexOfChild(binding.drawerMode) != 0) {
            binding.drawerNavigation.removeView(binding.drawerMode)
            binding.drawerNavigation.addView(binding.drawerMode, 0)
        }
        binding.drawerMode.text = ""
        binding.drawerMode.setCompoundDrawablesRelativeWithIntrinsicBounds(if (show) R.drawable.ic_categories else R.drawable.ic_search_minimal, 0, 0, 0)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.drawerMode,
            ColorStateList.valueOf(binding.drawerMode.currentTextColor))
        binding.drawerMode.contentDescription = getString(if (show) R.string.browse_apps else R.string.search_mode)
        adapter.browseCategories = !show && flag == Constants.FLAG_LAUNCH_APP && prefs.drawerSort == 0
        adapter.browseSort = if (!show && flag == Constants.FLAG_LAUNCH_APP) prefs.drawerSort else -1
        adapter.labelSizeSp = if (show) prefs.searchTextSize.toFloat() else prefs.drawerTextSize.toFloat()
        adapter.rowMinHeightDp = 48 + prefs.drawerRowSize * 12
        applyIconSettings()
        adapter.allowAutoLaunch = searchMode && prefs.autoLaunchFromSearch && !isSearchComposing()
        binding.drawerCustomize.contentDescription = getString(if (show) R.string.customize_search else R.string.customize_apps)
        adapter.emptyQueryShowsAll = flag != Constants.FLAG_LAUNCH_APP
        searchBack.isEnabled = show && searchFromBrowse
        if (show) {
            binding.search.requestFocus()
            if (animate) binding.search.showKeyboard(prefs.autoShowKeyboard)
        } else {
            binding.search.hideKeyboard()
            binding.search.clearFocus()
            restoreBrowse = true
        }
        adapter.filterQuery(binding.search.query)
        if (animate) {
            modeAnimator?.cancel()
            modeAnimator = LauncherMotion.transition(binding.recyclerView, true,
                LauncherMotion.preset(requireContext(), prefs)).also { it.start() }
        }
    }

    private fun goHome() {
        binding.search.hideKeyboard()
        // The drawer is transparent over the wallpaper. Remove its category rules before
        // NavHost draws Home underneath it, otherwise a divider flashes across Home.
        binding.root.alpha = 0f
        findNavController().popBackStack(R.id.mainFragment, false)
    }

    private fun installSwipeHome() {
        binding.recyclerView.addOnItemTouchListener(HorizontalSwipeListener(requireContext()) { right ->
            if (right) { if (searchMode && searchFromBrowse) showSearch(false) else goHome() }
            else if (!searchMode && flag == Constants.FLAG_LAUNCH_APP) {
                browsePosition = linearLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
                searchFromBrowse = true
                showSearch(true)
            }
        })
    }

}
