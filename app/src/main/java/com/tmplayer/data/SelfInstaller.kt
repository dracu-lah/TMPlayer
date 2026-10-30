package com.tmplayer.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Installs a downloaded TMPlayer APK over this app (HX5), with as little asking as Android allows.
 *
 * - **Android 12 and later**: a `PackageInstaller` session. Where [SilentUpdateRule] allows it the
 *   session asks for no confirmation, and Android replaces the app and ends this process without a
 *   dialog. If the platform asks anyway (`STATUS_PENDING_USER_ACTION`) or aborts the silent
 *   session unasked, the refusal is remembered for this Android release and the viewer gets
 *   Android's question, now and from then on. A session a system component killed falls back to
 *   the system installer's screen.
 * - **Android 6 to 11**: the system installer's screen, as before (`ACTION_VIEW` on the file).
 *
 * Either way the APK is checked first ([ApkCheck]): the same package, the same signing key and a
 * higher versionCode, or it is deleted and never handed to Android. Only the GitHub build gets
 * here: the F-Droid build has no update check at all ([Updates.enabled]) and no install permission.
 *
 * The session handling follows Morphe's SessionInstaller (MorpheApp/morphe-manager, GPL-3.0).
 */
object SelfInstaller {

    /** What became of an install. */
    sealed interface Result {
        /** Android reported it installed (rare for our own update: the process ends first). */
        data object Installed : Result

        /** On Android 6 to 11, the system installer's screen has it now. */
        data object HandedOver : Result

        /** The viewer said no to Android's question. */
        data object Cancelled : Result

        /** Android wanted to ask, and nobody started this install to answer (the automatic one). */
        data object NeedsViewer : Result

        /** It did not install, in words for the viewer. */
        data class Failed(val message: String) : Result
    }

    private const val ACTION_STATUS = "com.tmplayer.action.SELF_INSTALL_STATUS"
    private const val EXTRA_SESSION = "com.tmplayer.extra.SELF_INSTALL_SESSION"

    /** How long Android's question may stay unanswered before the session is given up. */
    private const val ANSWER_WAIT_MS = 20 * 60_000L

    private const val TAG = "SelfInstaller"

    /**
     * Work to finish before an install ends the process: the player writes its place here. Each
     * runs once per install, in order, and a slow or failing one does not stop the install.
     */
    private val beforeInstall = CopyOnWriteArrayList<suspend () -> Unit>()

    fun addBeforeInstall(hook: suspend () -> Unit) {
        beforeInstall += hook
    }

    fun removeBeforeInstall(hook: suspend () -> Unit) {
        beforeInstall -= hook
    }

    private suspend fun runBeforeInstall() {
        for (hook in beforeInstall) {
            withTimeoutOrNull(HOOK_WAIT_MS) { runCatching { hook() } }
        }
    }

    private const val HOOK_WAIT_MS = 2_000L

    /**
     * Whether Android would install this app's next update without asking, judged on the
     * installed app (the update itself is judged again once it is downloaded). Before Android 12,
     * or in a build without the updater, never.
     */
    suspend fun canUpdateSilently(context: Context): Boolean {
        if (!Updates.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val installed = runCatching { installedFacts(context) }.getOrNull() ?: return false
        return silentAllowed(context, installed.packageName, installed.targetSdk)
    }

    @SuppressLint("NewApi")
    private suspend fun silentAllowed(context: Context, apkPackage: String, apkTargetSdk: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val app = context.applicationContext
        // Not declared in the manifest since 1.28.3 (Play Protect flagged the pair with
        // REQUEST_INSTALL_PACKAGES), so Android would only ask: do not try a silent session.
        if (app.checkSelfPermission(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION) != PackageManager.PERMISSION_GRANTED) return false
        val refused = runCatching { SettingsStore(app).updateSilentRefusedNow() }.getOrDefault(0L)
        return SilentUpdateRule.allows(
            sdkInt = Build.VERSION.SDK_INT,
            selfPackage = app.packageName,
            apkPackage = apkPackage,
            apkTargetSdk = apkTargetSdk,
            updateOwner = updateOwner(app),
            mayInstall = app.packageManager.canRequestPackageInstalls(),
            refusedOnApi = refused,
        )
    }

    /** Android 14's update owner of this app, or null for none (or an older Android). */
    private fun updateOwner(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching { context.packageManager.getInstallSourceInfo(context.packageName).updateOwnerPackageName }.getOrNull()
    }

    /**
     * Installs [file], the APK of [release]. [foreground] is an install the viewer started with
     * Update now; false is the automatic one, which never puts a question to anybody.
     *
     * On a result other than [Result.Installed] or [Result.HandedOver] the file has been deleted
     * and the app goes on as it was.
     */
    suspend fun install(context: Context, file: File, release: Release, foreground: Boolean): Result {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val apk = withContext(Dispatchers.IO) { if (file.isFile) archiveFacts(app, file) else null }
        val installed = runCatching { installedFacts(app) }.getOrNull()
        val verdict = if (installed == null) ApkCheck.Verdict.Unreadable else ApkCheck.verify(apk, installed)
        if (verdict != ApkCheck.Verdict.Ok) {
            Logger.w(TAG, "Update ${release.version} not installed: $verdict")
            file.delete()
            return Result.Failed(words(verdict))
        }
        apk!!

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Nobody to answer the system installer's screen: the automatic install is 12+ only.
            if (!foreground) {
                file.delete()
                return Result.NeedsViewer
            }
            runBeforeInstall()
            remember(settings, release, session = false, foreground = true)
            return runCatching {
                app.startActivity(viewIntent(app, file))
                Result.HandedOver
            }.getOrElse {
                forget(settings)
                Result.Failed(UpdateWords.NO_INSTALLER)
            }
        }

        val silent = silentAllowed(app, apk.packageName, apk.targetSdk)
        if (!silent && !foreground) {
            file.delete()
            return Result.NeedsViewer
        }
        runBeforeInstall()
        remember(settings, release, session = true, foreground = foreground)
        val result = sessionInstall(app, file, silent, foreground, settings)
        when (result) {
            Result.Installed -> file.delete()
            Result.HandedOver -> Unit
            else -> {
                forget(settings)
                file.delete()
            }
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun sessionInstall(context: Context, file: File, silent: Boolean, foreground: Boolean, settings: SettingsStore): Result {
        var outcome = commit(context, file, silent, foreground, onRefused = { refused(settings) })
        if (SessionSteps.remembersRefusal(outcome.step)) refused(settings)
        if (SessionSteps.next(outcome.step, foreground) == SessionSteps.Next.AskInstead) {
            Logger.w(TAG, "Silent update refused (${outcome.message}), asking instead")
            outcome = commit(context, file, silent = false, foreground = true, onRefused = {})
        }
        // The session was killed under it (some vendors' builds do): the system installer's screen
        // is what is left.
        if (SessionSteps.next(outcome.step, foreground) == SessionSteps.Next.SystemInstaller) {
            return runCatching {
                context.startActivity(viewIntent(context, file))
                Result.HandedOver
            }.getOrElse { Result.Failed(UpdateWords.NO_INSTALLER) }
        }
        return when (SessionSteps.result(outcome.step)) {
            SessionSteps.Ending.Installed -> Result.Installed
            SessionSteps.Ending.Cancelled -> Result.Cancelled
            // Android wanted to ask with nobody there, and the session is gone; the popup offers it.
            SessionSteps.Ending.NeedsViewer -> Result.NeedsViewer
            SessionSteps.Ending.Conflict -> Result.Failed(L.updateConflict(page = Updates.RELEASES_PAGE))
            SessionSteps.Ending.Storage -> Result.Failed(spaceWords(context, file.length()))
            SessionSteps.Ending.Incompatible -> Result.Failed(L.updateIncompatible)
            SessionSteps.Ending.Failed -> Result.Failed(L.updateInstallFailed(reason = outcome.message?.takeIf { it.isNotBlank() } ?: L.updateDidNotFinish))
        }
    }

    private class Outcome(val step: SessionSteps.Step, val message: String?)

    /**
     * One session: written, committed, and followed until Android's final answer. The session is
     * abandoned however this ends short of that, a cancelled coroutine included, so none is left
     * holding a copy of the APK.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun commit(
        context: Context,
        file: File,
        silent: Boolean,
        foreground: Boolean,
        onRefused: suspend () -> Unit,
    ): Outcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(
                if (silent) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                },
            )
        }
        val id = runCatching { installer.createSession(params) }.getOrElse {
            Logger.w(TAG, "No install session", it)
            return Outcome(SessionSteps.Step.Failed, it.message)
        }
        val answers = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_SESSION, -1) == id) answers.trySend(intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        var finished = false
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
            }
            val status = Intent(ACTION_STATUS).setPackage(context.packageName).putExtra(EXTRA_SESSION, id)
            // Mutable: the installer fills in the status. Explicit by package, as Android 14 wants.
            val pending = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            installer.openSession(id).use { it.commit(pending.intentSender) }

            var shown = !silent
            while (true) {
                val answer = withTimeoutOrNull(ANSWER_WAIT_MS) { answers.receive() }
                    ?: return Outcome(SessionSteps.Step.Failed, L.updateTooLong)
                val code = answer.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = answer.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Logger.i(TAG, "Session $id status=$code silent=$silent message=$message")
                val step = SessionSteps.step(code, silentAsked = silent, confirmationShown = shown, message = message)
                if (step != SessionSteps.Step.Confirm) {
                    finished = step == SessionSteps.Step.Installed
                    return Outcome(step, message)
                }
                // Android asks after all, so the next update asks straight away. Nobody to answer
                // in the background: give it up there.
                if (silent && !shown) onRefused()
                if (!foreground) return Outcome(step, message)
                val confirm = confirmIntent(answer) ?: return Outcome(SessionSteps.Step.Failed, message)
                shown = true
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { return Outcome(SessionSteps.Step.Failed, it.message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Install session failed", e)
            return Outcome(SessionSteps.Step.Failed, e.message)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            // A session that ended by itself is already gone and this does nothing; one cut short
            // here (no answer, cancelled, a background question) must not linger.
            if (!finished) withContext(NonCancellable) { runCatching { installer.abandonSession(id) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(status: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            status.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private suspend fun refused(settings: SettingsStore) {
        runCatching { settings.setUpdateSilentRefused(Build.VERSION.SDK_INT.toLong()) }
    }

    private suspend fun remember(settings: SettingsStore, release: Release, session: Boolean, foreground: Boolean) {
        val pending = UpdateRestart.Pending(release.version, System.currentTimeMillis(), session, foreground)
        runCatching { settings.setUpdateInstalling(pending.encode()) }
    }

    private suspend fun forget(settings: SettingsStore) {
        runCatching { settings.setUpdateInstalling("") }
    }

    /** The system installer's screen, for Android 6 to 11 and a session that died. */
    private fun viewIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** What a refused APK is called, in words. */
    private fun words(verdict: ApkCheck.Verdict): String = when (verdict) {
        ApkCheck.Verdict.WrongPackage -> L.updateNotThisApp
        ApkCheck.Verdict.WrongSigner -> L.updateConflict(page = Updates.RELEASES_PAGE)
        ApkCheck.Verdict.NotNewer -> L.updateNotNewer
        ApkCheck.Verdict.Unreadable, ApkCheck.Verdict.Ok -> UpdateWords.DAMAGED
    }

    /** "The update needs 120 MB free and this device has 40 MB." */
    fun spaceWords(context: Context, apkBytes: Long): String {
        val free = DiskSpace.read(context, context.cacheDir).freeBytes
        val format = Translator.messages.formatter
        return L.updateNeedsSpace(needed = format.bytes(AutoInstall.spaceNeeded(apkBytes)), free = format.bytes(free))
    }

    // ---- reading APKs ----------------------------------------------------------------------------

    /** The installed app's package, versionCode, signers and target SDK. */
    fun installedFacts(context: Context): ApkFacts {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        return facts(info) ?: error("no package info")
    }

    /** The downloaded APK's, or null when Android cannot read it as an APK. */
    fun archiveFacts(context: Context, file: File): ApkFacts? {
        val pm = context.packageManager
        val path = file.absolutePath
        val withCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
                ?.let(::facts)?.takeIf { it.signers.isNotEmpty() }
        } else {
            null
        }
        // Older Androids, and the odd one that reads no certificates from an archive that way.
        return withCertificates ?: runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }.getOrNull()?.let(::facts)
    }

    private fun facts(info: PackageInfo?): ApkFacts? {
        info ?: return null
        var current: List<ByteArray> = emptyList()
        var past: List<ByteArray> = emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signing ->
                current = signing.apkContentsSigners.orEmpty().map { it.toByteArray() }
                if (!signing.hasMultipleSigners()) past = signing.signingCertificateHistory.orEmpty().map { it.toByteArray() }
            }
        }
        if (current.isEmpty()) {
            @Suppress("DEPRECATION")
            current = info.signatures.orEmpty().map { it.toByteArray() }
        }
        // Null from an archive Android could only half read, whatever the annotation says.
        val name: String? = info.packageName
        return ApkFacts(
            packageName = name ?: return null,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signers = current.map(::sha256).toSet(),
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            pastSigners = past.map(::sha256).toSet(),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
