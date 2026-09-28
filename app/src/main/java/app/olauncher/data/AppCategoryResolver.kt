package app.olauncher.data

/** Curated identities fill missing or misleading manifest metadata; manual choices win in Prefs. */
object AppCategoryResolver {
    private val known = buildMap {
        fun group(category: Int, vararg packages: String) { packages.forEach { put(it, category) } }
        group(23, "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.google.android.gm", "com.samsung.android.email.provider", "com.microsoft.office.outlook",
            "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.contacts",
            "com.samsung.android.app.contacts", "com.google.android.apps.tachyon", "com.discord")
        group(20, "com.android.chrome", "com.chrome.beta", "com.duckduckgo.mobile.android",
            "org.mozilla.firefox", "org.mozilla.fenix", "com.brave.browser", "com.microsoft.emmx",
            "com.sec.android.app.sbrowser", "com.opera.browser")
        group(1, "com.soundcloud.android", "com.spotify.music", "app.revanced.android.youtube",
            "com.google.android.youtube", "com.google.android.apps.youtube.music", "com.netflix.mediaclient",
            "org.videolan.vlc", "com.amazon.avod.thirdpartyclient", "com.sec.android.app.music")
        group(4, "com.snapchat.android", "com.instagram.android", "com.facebook.katana",
            "com.zhiliaoapp.musically", "com.reddit.frontpage", "com.twitter.android", "com.pinterest")
        group(21, "com.sofi.mobile", "com.chase.sig.android", "com.bankofamerica.cashpromobile",
            "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash", "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay", "com.samsung.android.samsungpay.gear")
        group(22, "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.etsy.android",
            "com.walmart.android", "com.target.ui", "com.shopify.arrive")
        group(7, "com.streetwriters.notesnook", "com.samsung.android.app.reminder", "com.mobilefork.hermesagent",
            "com.samsung.android.app.notes", "com.google.android.calendar", "com.samsung.android.calendar",
            "com.google.android.keep", "com.google.android.apps.docs", "com.microsoft.office.onenote")
        group(10, "com.x8bit.bitwarden", "com.bitwarden.app", "com.tailscale.ipn", "juloo.keyboard2",
            "io.github.muntashirakon.AppManager", "com.google.android.apps.authenticator2", "com.microsoft.rdc.androidx",
            "com.donnnno.arcticons", "com.donnnno.arcticons.light", "app.revanced.android.gms",
            "app.revanced.manager.flutter", "org.fdroid.fdroid", "zed.rainxch.githubstore", "dev.bikram.obtainx",
            "all.in.one.calculator", "com.sec.android.app.clockpackage", "com.google.android.deskclock",
            "com.android.settings", "com.sec.android.app.myfiles", "com.sec.android.app.popupcalculator",
            "com.google.android.apps.nbu.files", "com.android.vending", "com.sec.android.app.samsungapps",
            "com.beforesoft.launcher")
        group(6, "com.life360.android.safetymapd", "com.google.android.apps.maps", "com.waze", "com.ubercab")
        group(3, "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.sec.android.gallery3d",
            "com.google.android.apps.photos")
    }

    fun resolve(packageName: String, declared: Int): Int = known[packageName] ?: when (declared) {
        2 -> 1 // Music and video share the compact Media group; explicit Video overrides remain available.
        in 0..8 -> declared
        else -> -1
    }
}
