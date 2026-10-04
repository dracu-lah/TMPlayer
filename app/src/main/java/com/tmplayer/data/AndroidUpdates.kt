package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.tmplayer.BuildConfig
import java.io.File

// Installing an update is Android's business; checking for one and fetching it is shared, in
// `:core`'s [Updates], and when to check is [UpdateScheduler]'s.

/** Tells [Updates] what this build is: called once from `App.onCreate`. */
fun Updates.configureForAndroid() {
    val abis = Build.SUPPORTED_ABIS.orEmpty().toList()
    configure(
        installedVersion = BuildConfig.VERSION_NAME,
        abis = abis,
        connectivity = NetworkMonitor,
        // A release with no APK this device can run is no news here.
        offers = { UpdateFeed.androidAsset(it, abis) != null },
    )
}

/** The update schedule over this app's settings. Holds no state of its own, so one per caller is fine. */
fun updateScheduler(context: Context): UpdateScheduler = UpdateScheduler(SettingsStore(context).updatePrefs)

/** The sentence for a download refused because Wi-Fi only is on and this is not Wi-Fi. */
const val UPDATE_WAITS_FOR_WIFI = "Wi-Fi only is on in Settings, so the update waits for Wi-Fi."

/**
 * Fetches the APK for this device and hands it to the system installer.
 *
 * Honours "Wi-Fi only" the way a video does: on a metered connection with it on, nothing is
 * fetched. The warning about mobile data without it is the popup's, on the button itself.
 *
 * The file lands in the cache directory: once Android has installed it there is no reason to
 * keep a second copy of the app around on a stick with eight gigabytes on it.
 */
suspend fun Updates.downloadAndInstall(context: Context, release: Release) {
    if (onMeteredNetwork() && SettingsStore(context).wifiOnlyDownloadsNow()) {
        refuse(UPDATE_WAITS_FOR_WIFI)
        return
    }
    val file = download(release, File(context.cacheDir, "updates")) ?: return
    runCatching { context.startActivity(installIntent(context, file)) }
        .onFailure { installFailed(UpdateWords.NO_INSTALLER) }
}

/** Whether the device will let TMPlayer hand an APK to the installer at all. */
@Suppress("UnusedReceiverParameter")
fun Updates.canInstall(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

/**
 * The system screen where "allow apps from this source" is turned on.
 *
 * Android will not take that answer from inside this app, so the viewer is sent to the
 * system's own switch and comes back with Back.
 */
@Suppress("UnusedReceiverParameter")
fun Updates.unknownSourcesIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData(android.net.Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun installIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
