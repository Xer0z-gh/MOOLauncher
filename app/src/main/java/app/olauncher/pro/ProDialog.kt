package app.olauncher.pro

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.helper.createDialog
import app.olauncher.helper.openUrl

/** Offers Moo Pro: its Google Play page. Coming back to the launcher re-checks the unlock. */
fun Context.showProDialog() {
    createDialog(
        title = R.string.moo_pro,
        action = R.string.pro_get,
        message = R.string.pro_message,
        neutral = R.string.not_now,
        onAction = {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${Constants.MOO_PRO_PACKAGE}".toUri())) }
                .onFailure { openUrl("https://play.google.com/store/apps/details?id=${Constants.MOO_PRO_PACKAGE}") }
        },
    ).showRespectingStatusBar()
}
