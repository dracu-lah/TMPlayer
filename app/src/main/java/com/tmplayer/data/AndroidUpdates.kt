package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.tmplayer.BuildConfig
import java.io.File

// Installing an update is Android's business; checking for one and fetching it is shared, in
// `:core`'s [Updates].

/** Tells [Updates] what this build is: called once from `App.onCreate`. */
fun Updates.configureForAndroid() = configure(
    installedVersion = BuildConfig.VERSION_NAME,
    abis = Build.SUPPORTED_ABIS.orEmpty().toList(),
    connectivity = NetworkMonitor,
)

/**
 * Fetches the APK for this TV and hands it to the system installer.
 *
 * The file lands in the cache directory: once Android has installed it there is no reason to
 * keep a second copy of the app around on a stick with eight gigabytes on it.
 */
suspend fun Updates.downloadAndInstall(context: Context, release: Release) {
    val file = download(release, File(context.cacheDir, "updates")) ?: return
    runCatching { context.startActivity(installIntent(context, file)) }
        .onFailure {
            installFailed(
                "TMPlayer could not open Android's installer. Install it by hand from " +
                    "${Updates.RELEASES_PAGE}.",
            )
        }
}

/** Whether the TV will let TMPlayer hand an APK to the installer at all. */
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
