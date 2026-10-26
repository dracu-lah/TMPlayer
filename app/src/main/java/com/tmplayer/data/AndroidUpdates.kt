package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.tmplayer.BuildConfig
import com.tmplayer.i18n.L
import java.io.File

// Installing an update is Android's business; checking for one and fetching it is shared, in
// `:core`'s [Updates], and when to check is [UpdateScheduler]'s.

/** Tells [Updates] what this build is: called once from `App.onCreate`. */
fun Updates.configureForAndroid(context: Context) {
    val abis = Build.SUPPORTED_ABIS.orEmpty().toList()
    val app = context.applicationContext
    configure(
        installedVersion = BuildConfig.VERSION_NAME,
        abis = abis,
        connectivity = NetworkMonitor,
        testVersions = { SettingsStore(app).updatePrefs.testVersions() },
        // A release with no APK this device can run is no news here.
        offers = { UpdateFeed.androidAsset(it, abis) != null },
    )
}

/** The update schedule over this app's settings. Holds no state of its own, so one per caller is fine. */
fun updateScheduler(context: Context): UpdateScheduler = UpdateScheduler(SettingsStore(context).updatePrefs)

/** The sentence for a download refused because Wi-Fi only is on and this is not Wi-Fi. */
val UPDATE_WAITS_FOR_WIFI: String get() = L.updateWaitsForWifi

/**
 * Fetches the APK for this device and installs it ([SelfInstaller]): on Android 12 and later
 * without Android's question where it allows that, else with it. Started by Update now.
 *
 * Honours "Wi-Fi only" the way a video does: on a metered connection with it on, nothing is
 * fetched. The warning about mobile data without it is the popup's, on the button itself. Nothing
 * is fetched either without room for the APK and Android's copy of it ([AutoInstall.spaceNeeded]),
 * and the popup says how much is needed and how much is free.
 *
 * The file lands in the cache directory and is deleted once installed or refused: there is no
 * reason to keep a second copy of the app around on a stick with eight gigabytes on it.
 */
suspend fun Updates.downloadAndInstall(context: Context, release: Release) {
    if (onMeteredNetwork() && MeteredPolicy.wifiOnlyInForce(SettingsStore(context).wifiOnlyDownloadsNow(), FormFactor.isTv(context))) {
        refuse(UPDATE_WAITS_FOR_WIFI)
        return
    }
    val size = apkFor(release)?.size ?: 0L
    if (DiskSpace.read(context, context.cacheDir).freeBytes < AutoInstall.spaceNeeded(size)) {
        refuse(SelfInstaller.spaceWords(context, size))
        return
    }
    // Android 12's session keeps the popup up, saying it is installing; before that the system
    // installer's screen takes over and the popup closes on Ready.
    val session = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val file = download(release, File(context.cacheDir, "updates"), installing = session) ?: return
    when (val result = SelfInstaller.install(context, file, release, foreground = true)) {
        // The process usually ends before this; if not, the restart notice still follows.
        SelfInstaller.Result.Installed -> Unit
        SelfInstaller.Result.HandedOver -> handedOver(release, file)
        SelfInstaller.Result.Cancelled, SelfInstaller.Result.NeedsViewer -> backToOffer()
        is SelfInstaller.Result.Failed -> installFailed(result.message)
    }
}

/** The work that pauses while TMPlayer updates: downloads running or queued, a save to a folder. */
@Suppress("UnusedReceiverParameter")
fun Updates.workPausedByInstall(): Boolean =
    OfflineDownloads.active.value.values.any { it.busy } || FolderExport.busy

/** Whether the device will let TMPlayer hand an APK to the installer at all. */
@Suppress("UnusedReceiverParameter")
fun Updates.canInstall(context: Context): Boolean =
    // Before Android 8 there is no per-app permission, only the one "unknown sources" switch, and
    // the system's installer asks for that itself when the APK is handed over.
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/**
 * The system screen where "allow apps from this source" is turned on.
 *
 * Android will not take that answer from inside this app, so the viewer is sent to the
 * system's own switch and comes back with Back.
 */
@Suppress("UnusedReceiverParameter")
fun Updates.unknownSourcesIntent(context: Context): Intent =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        // Android 7 and older keep the one global switch on the security page.
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
