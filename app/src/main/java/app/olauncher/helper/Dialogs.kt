package app.olauncher.helper

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.olauncher.data.Prefs
import app.olauncher.data.ColorTheme
import app.olauncher.R
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.get
import androidx.core.view.size
import app.olauncher.databinding.DialogBaseBinding

/** One opaque surface shared by the Settings hub, sections, and full-screen editors. */
fun settingsPageColor(context: Context, prefs: Prefs): Int =
    if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).background
    else context.getColorFromAttr(R.attr.primaryInverseColor)

fun settingsCardDrawable(context: Context, prefs: Prefs): GradientDrawable {
    val page = settingsPageColor(context, prefs)
    val text = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
        else context.getColorFromAttr(R.attr.primaryColor)
    return GradientDrawable().apply {
        setColor(ColorUtils.blendARGB(page, text, .075f))
        cornerRadius = 32.dpToPx().toFloat()
        setStroke((context.resources.displayMetrics.density * .5f).toInt().coerceAtLeast(1),
            ColorUtils.blendARGB(page, text, .35f))
    }
}

/** Full-height Settings detail page. Short value pickers continue to use showForLauncher. */
fun AlertDialog.showSettingsPage(@androidx.annotation.StringRes back: Int = R.string.settings_back) {
    showForLauncher()
    findViewById<View>(androidx.appcompat.R.id.parentPanel)?.let { panel ->
        panel.layoutParams = panel.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
    }
    findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_arrow_back, 0, 0, 0)
        compoundDrawablePadding = 12.dpToPx()
        // TextView centres a compound drawable on the whole title, which left Back between the
        // two lines of "Customize notifications". Shift it onto the first line, like Settings
        // section titles. setBounds only invalidates, so this is safe inside a layout pass.
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val title = v as android.widget.TextView
            val back = title.compoundDrawablesRelative[0] ?: return@addOnLayoutChangeListener
            val text = title.layout ?: return@addOnLayoutChangeListener
            val centre = title.compoundPaddingTop + (title.height - title.compoundPaddingTop - title.compoundPaddingBottom) / 2
            val dy = if (title.lineCount > 1)
                title.totalPaddingTop + (text.getLineTop(0) + text.getLineBottom(0)) / 2 - centre else 0
            back.setBounds(0, dy, back.intrinsicWidth, back.intrinsicHeight + dy)
        }
        minHeight = 48.dpToPx()
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(back) + ", " + text
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        setOnClickListener { this@showSettingsPage.dismiss() }
        applyFocusOutline(if (ColorTheme.isCustom(Prefs(context).colorThemeId))
            ColorTheme.byId(Prefs(context).colorThemeId).text else context.getColorFromAttr(R.attr.primaryColor))
    }
}

fun AlertDialog.showForLauncher() {
    val prefs = Prefs(context)
    if (LauncherMotion.preset(context, prefs) == LauncherMotion.OFF) window?.setWindowAnimations(0)
    if (!prefs.showStatusBar) window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    show()
    applyLauncherSurface(prefs)
    if (!prefs.showStatusBar) {
        window?.hideStatusBar()
        window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}

/** Opaque surfaces keep the page underneath from competing with settings text. */
private fun AlertDialog.applyLauncherSurface(prefs: Prefs) {
    val custom = ColorTheme.isCustom(prefs.colorThemeId)
    val theme = ColorTheme.byId(prefs.colorThemeId)
    val background = settingsPageColor(context, prefs)
    val foreground = if (custom) theme.text else context.getColorFromAttr(R.attr.primaryColor)
    window?.setBackgroundDrawable(background.toDrawable())
    window?.setGravity(Gravity.TOP)
    window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    findViewById<View>(androidx.appcompat.R.id.parentPanel)?.let { panel ->
        panel.background = settingsCardDrawable(context, prefs)
        (panel.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val inset = (12 * context.resources.displayMetrics.density).toInt()
            params.setMargins(inset, inset * 2, inset, inset)
            panel.layoutParams = params
        }
    }
    findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
        setTextAppearance(R.style.TextLarge)
        // AppCompat's DialogTitle is one line and shrinks its text when that line ellipsizes:
        // "Customize notifications" came out at row size, top-aligned off the Back glyph. Two
        // lines at full size, centred on the Back glyph, like Settings section titles.
        isSingleLine = false
        maxLines = 2
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        // At the user's Text weight, like the Settings page title that opened this one.
        applyTextWeight(app.olauncher.data.Constants.TextWeight.value(prefs.textWeight))
        minHeight = (48 * context.resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    window?.decorView?.let { root ->
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { root.tintTextTree(foreground, foreground.withAlpha(179)); root.tintCompoundDrawables(foreground); root.tintDialogControls(foreground) }
    }
}

fun View.tintDialogControls(color: Int) {
    if ((isFocusable || isClickable) && this !is android.widget.EditText) applyFocusOutline(color)
    if (this is android.widget.CheckedTextView) checkMarkTintList = android.content.res.ColorStateList.valueOf(color)
    if (this is android.widget.CompoundButton) buttonTintList = android.content.res.ColorStateList.valueOf(color)
    if (this is android.widget.EditText) backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    if (this is android.widget.AbsListView && getTag(R.id.dialog_list_tint) != color) {
        setTag(R.id.dialog_list_tint, color)
        // List recycling can attach fresh system-themed rows without a global layout.
        setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (view == null) return
                for (i in 0 until view.childCount) view.getChildAt(i).apply {
                    tintTextTree(color, color.withAlpha(179)); tintCompoundDrawables(color); tintDialogControls(color)
                }
            }
        })
        selector = GradientDrawable().apply {
            setColor(color.withAlpha(24)); setStroke(2.dpToPx(), color); cornerRadius = 4.dpToPx().toFloat()
        }
    }
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).tintDialogControls(color)
}

/**
 * Shows a popup menu hanging off the end edge of this view.
 * [configure] can add or tweak items before the menu is shown.
 */
fun View.showPopupMenu(
    @MenuRes menuRes: Int = 0,
    configure: (Menu) -> Unit = {},
    onItemClick: (MenuItem) -> Unit,
): AlertDialog {
    val popup = PopupMenu(context, this, Gravity.END)
    if (menuRes != 0) popup.menuInflater.inflate(menuRes, popup.menu)
    configure(popup.menu)
    val items = (0 until popup.menu.size).map { popup.menu[it] }.filter { it.isVisible }
    val dialog = AlertDialog.Builder(context)
        .setTitle(contentDescription?.takeIf { it.isNotBlank() } ?: (this as? android.widget.TextView)?.text)
        .setItems((listOf(context.getString(android.R.string.cancel)) + items.map { it.title.toString() }).toTypedArray()) { _, index ->
            if (index > 0 && items[index - 1].isEnabled) onItemClick(items[index - 1])
        }.create()
    dialog.showForLauncher()
    // A bounded native list makes long menus scroll by touch, wheel and keyboard.
    dialog.listView?.let { list ->
        val cap = minOf(280.dpToPx(), (resources.displayMetrics.heightPixels * .5f).toInt())
        if (items.size * 56.dpToPx() > cap) list.layoutParams = list.layoutParams.apply { height = cap }
        list.isVerticalScrollBarEnabled = true
    }
    return dialog
}

/**
 * App dialog: shows without bringing back a hidden status bar.
 */
class OlDialog(context: Context) : AlertDialog(context) {
    private var page: View? = null

    fun setPageView(view: View) { page = view }

    fun showRespectingStatusBar() {
        if (LauncherMotion.preset(context, Prefs(context)) == LauncherMotion.OFF) window?.setWindowAnimations(0)
        val window = window
        if (window == null || Prefs(context).showStatusBar) {
            show()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            show()
            window.hideStatusBar()
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        page?.let { setContentView(it, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        applyLauncherSurface(Prefs(context))
    }
}

/**
 * Builds a dialog using the app's own layout: a title row with a close icon,
 * an optional [message] or custom [content], and a text [action] at the end.
 * [content] receives the container so the inflated view keeps its XML margins.
 */
fun Context.createDialog(
    @StringRes title: Int,
    @StringRes action: Int,
    @StringRes message: Int = 0,
    messageText: CharSequence? = null,
    @StringRes neutral: Int = 0,
    onNeutral: () -> Unit = {},
    onAction: () -> Unit = {},
    content: ((ViewGroup) -> View)? = null,
): OlDialog {
    val dialog = OlDialog(this)
    dialog.setTitle(title)
    val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
    val prefs = Prefs(this)
    val foreground = if (ColorTheme.isCustom(prefs.colorThemeId)) ColorTheme.byId(prefs.colorThemeId).text
        else getColorFromAttr(R.attr.primaryColor)
    binding.ivClose.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
    binding.ivClose.alpha = 1f
    binding.tvTitle.setText(title)
    // Long compound words ("Bedienungshilfendienst") break at a syllable with a hyphen, not mid-letter.
    binding.tvTitle.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_FULL
    ViewCompat.setAccessibilityHeading(binding.tvTitle, true)
    listOf(binding.ivClose, binding.tvNeutral, binding.tvAction).forEach { control ->
        ViewCompat.setAccessibilityDelegate(control, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }
    binding.tvAction.setText(action)
    if (message != 0) binding.tvMessage.setText(message)
    messageText?.let(binding.tvMessage::setText)
    // A message with links (About's credits) needs taps routed to them.
    if (messageText is android.text.Spanned &&
        messageText.getSpans(0, messageText.length, android.text.style.ClickableSpan::class.java).isNotEmpty())
        binding.tvMessage.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    binding.tvMessage.isVisible = message != 0 || messageText != null
    if (neutral != 0) {
        binding.tvNeutral.setText(neutral)
        binding.tvNeutral.isVisible = true
    }
    content?.let {
        binding.contentContainer.addView(it(binding.contentContainer))
        binding.contentContainer.isVisible = true
    }
    dialog.setPageView(binding.root)
    binding.ivClose.setOnClickListener { dialog.dismiss() }
    binding.tvNeutral.setOnClickListener {
        onNeutral()
        dialog.dismiss()
    }
    binding.tvAction.setOnClickListener {
        onAction()
        dialog.dismiss()
    }
    return dialog
}

/** Title with a close icon, a message and a single action. */
fun Context.showMessageDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
): OlDialog {
    val dialog = createDialog(title, action, message = message, onAction = onAction)
    dialog.showRespectingStatusBar()
    return dialog
}
