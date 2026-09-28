package app.olauncher.ui

import android.animation.Animator
import android.content.Intent
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import app.olauncher.data.Prefs
import app.olauncher.helper.showForLauncher
import app.olauncher.helper.LauncherMotion
import app.olauncher.helper.clearLayoutTransitions

open class BaseFragment : Fragment() {
    protected var panelSettings: app.olauncher.helper.PanelSettings? = null
    private val audioPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        refreshAudioViews(view)
    }
    protected fun requestAudioVisualizationPermission() {
        val prefs = Prefs(requireContext())
        val permission = android.Manifest.permission.RECORD_AUDIO
        val settings = prefs.visualizerPermissionRequested && !shouldShowRequestPermissionRationale(permission)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(app.olauncher.R.string.visualizer_audio_access)
            .setMessage(if (settings) app.olauncher.R.string.visualizer_permission_settings else app.olauncher.R.string.visualizer_permission_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (settings) app.olauncher.R.string.visualizer_open_settings else android.R.string.ok) { _, _ ->
                if (settings) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        ("package:" + requireContext().packageName).toUri())) }
                } else {
                    prefs.visualizerPermissionRequested = true
                    audioPermission.launch(permission)
                }
            }.create().showForLauncher()
    }

    private fun refreshAudioViews(target: android.view.View?) {
        if (target is AudioVisualizerView) target.refresh()
        if (target is android.view.ViewGroup) for (i in 0 until target.childCount) refreshAudioViews(target.getChildAt(i))
    }
    protected fun customizePanel(page: app.olauncher.helper.PanelSettings.Page, changed: () -> Unit) {
        panelSettings?.close()
        panelSettings = app.olauncher.helper.PanelSettings(requireContext(), Prefs(requireContext()), changed, ::requestAudioVisualizationPermission)
        panelSettings?.show(page)
    }
    private var pageTransition: Animator? = null

    /** Called by MainActivity's Power Saver receiver for each STARTED page; see MainActivity. */
    internal fun dispatchPowerStateChanged() {
        if (view != null) onPowerStateChanged()
    }

    protected open fun onPowerStateChanged() {
        if (LauncherMotion.preset(requireContext(), Prefs(requireContext())) == LauncherMotion.OFF) suppressMotion()
        refreshAudioViews(view)
    }

    protected open fun suppressMotion() {
        pageTransition?.end()
        view?.clearLayoutTransitions()
        view?.animate()?.cancel()
    }

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        if (nextAnim == 0) return null
        val target = view ?: return null
        pageTransition?.cancel()
        return LauncherMotion.transition(target, enter,
            LauncherMotion.preset(requireContext(), Prefs(requireContext()))).also { pageTransition = it }
    }

    override fun onDestroyView() {
        panelSettings?.close(); panelSettings = null
        pageTransition?.cancel()
        pageTransition = null
        super.onDestroyView()
    }
}
