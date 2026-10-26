package com.tmplayer.data

import android.content.pm.PackageInstaller

// The rules behind SelfInstaller, kept free of Android's services so they can be tested at every
// API level. The silent update rule and the status handling follow Morphe's SessionInstaller
// (MorpheApp/morphe-manager, domain/installer/SessionInstaller.kt, GPL-3.0 like TMPlayer): its
// `allowsSilentUpdate` and the fallback to the dialog it remembers as `silentUpdatesRefused`.

/**
 * When Android 12 and later update an app without the installer's question
 * (`PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`), as the
 * platform documents it for an installer holding REQUEST_INSTALL_PACKAGES:
 *
 * - Android 12 (API 31) or later;
 * - the installer is updating itself, which is the only install TMPlayer ever makes;
 * - the APK being installed targets a recent enough API level for that Android release
 *   ([minTargetSdk]);
 * - on Android 14 and later, no other installer has claimed the app's updates (the update owner
 *   is unset or TMPlayer itself);
 * - the viewer has allowed installs from TMPlayer ("Install unknown apps");
 * - and this device has not already refused it on this Android release ([refusedOnApi]).
 */
object SilentUpdateRule {

    const val ANDROID_12 = 31
    const val ANDROID_14 = 34

    /**
     * The lowest target SDK an update may have for Android [sdkInt] to install it without asking,
     * or null where it never does (before Android 12). From the platform's documentation of
     * `setRequireUserAction`: Q on 12, R on 13, S on 14, TIRAMISU on 15, UPSIDE_DOWN_CAKE on 16,
     * which is two levels behind the platform from 15 on; a later release is assumed to keep that.
     */
    fun minTargetSdk(sdkInt: Int): Int? = when {
        sdkInt < ANDROID_12 -> null
        sdkInt <= 32 -> 29
        sdkInt == 33 -> 30
        sdkInt == 34 -> 31
        else -> sdkInt - 2
    }

    fun allows(
        sdkInt: Int,
        selfPackage: String,
        apkPackage: String?,
        apkTargetSdk: Int?,
        updateOwner: String?,
        mayInstall: Boolean,
        refusedOnApi: Long,
    ): Boolean {
        val floor = minTargetSdk(sdkInt) ?: return false
        if (apkPackage != selfPackage) return false
        if (apkTargetSdk == null || apkTargetSdk < floor) return false
        if (sdkInt >= ANDROID_14 && !updateOwner.isNullOrEmpty() && updateOwner != selfPackage) return false
        if (!mayInstall) return false
        // Refused on this release: ask straight away. A newer Android release tries once more.
        if (refusedOnApi != 0L && refusedOnApi >= sdkInt) return false
        return true
    }
}

/**
 * What is known of an APK: the downloaded one, or the installed app's own. [signers] are the
 * SHA-256 digests of its current signing certificates, [pastSigners] those of earlier keys it
 * names through key rotation (Android 9's signing certificate history).
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val targetSdk: Int = 0,
    val pastSigners: Set<String> = emptySet(),
)

/**
 * Whether a downloaded APK may be installed over this app: the same package, signed with the same
 * key (or a rotation from it), and a higher versionCode (RL1's numbering). Anything else is never
 * handed to Android, whatever the feed said it was.
 */
object ApkCheck {

    enum class Verdict { Ok, Unreadable, WrongPackage, WrongSigner, NotNewer }

    fun verify(apk: ApkFacts?, installed: ApkFacts): Verdict = when {
        apk == null -> Verdict.Unreadable
        apk.packageName != installed.packageName -> Verdict.WrongPackage
        !sameSigner(apk, installed) -> Verdict.WrongSigner
        apk.versionCode <= installed.versionCode -> Verdict.NotNewer
        else -> Verdict.Ok
    }

    /**
     * The same key, or a key rotated from the installed one. An APK or an install whose signers
     * could not be read never matches: unknown is not the same.
     */
    private fun sameSigner(apk: ApkFacts, installed: ApkFacts): Boolean {
        if (apk.signers.isEmpty() || installed.signers.isEmpty()) return false
        return apk.signers == installed.signers || apk.pastSigners.containsAll(installed.signers)
    }
}

/**
 * One answer from an installer session, turned into what to do next. [silentAsked] is a session
 * committed without the question; [confirmationShown] is whether Android's question has been put
 * to the viewer in this session, which is what tells "the viewer said no" from "the platform
 * refused" when the session is aborted.
 */
object SessionSteps {

    enum class Step {
        /** Installed. For TMPlayer's own update the process usually ends before this arrives. */
        Installed,
        /** Android wants the viewer to confirm: start the intent it sent. */
        Confirm,
        /** The viewer said no. */
        Cancelled,
        /** Asked to install without the question, the platform aborted it unasked (HyperOS does). */
        SilentRefused,
        /** Signed with another key, or another conflict with the installed app. */
        Conflict,
        /** Not enough space. */
        Storage,
        /** An APK this device cannot run: its Android release or its processor. */
        Incompatible,
        /** A system component killed the session: worth handing the file to the system installer. */
        Dead,
        /** Any other failure; the system's message says what. */
        Failed,
    }

    /** What to do once the first session has ended on [step]. */
    enum class Next {
        /** Its answer is the answer. */
        Finish,
        /** The platform refused to install without asking: commit again, with Android's question. */
        AskInstead,
        /** The session died: hand the file to the system installer's screen. */
        SystemInstaller,
    }

    /** How an install ended, for the words the popup shows. */
    enum class Ending { Installed, Cancelled, NeedsViewer, Conflict, Storage, Incompatible, Failed }

    /**
     * After the first session. Only an install the viewer started ([foreground]) falls back to a
     * question or to the system installer: the automatic one asks nobody and stops.
     */
    fun next(step: Step, foreground: Boolean): Next = when {
        !foreground -> Next.Finish
        step == Step.SilentRefused -> Next.AskInstead
        step == Step.Dead -> Next.SystemInstaller
        else -> Next.Finish
    }

    /**
     * Whether [step] is the platform turning down an install without the question, which is
     * remembered so the next update asks straight away. A question asked after a silent commit is
     * remembered as it happens (`SelfInstaller.commit`), before the viewer answers it.
     */
    fun remembersRefusal(step: Step): Boolean = step == Step.SilentRefused

    /** The final [step] of an install, the second session's after a fallback. */
    fun result(step: Step): Ending = when (step) {
        Step.Installed -> Ending.Installed
        Step.Cancelled -> Ending.Cancelled
        // A question with nobody to answer it (only the automatic install stops on one), a refusal
        // in the background, a dead session in the background.
        Step.Confirm, Step.SilentRefused, Step.Dead -> Ending.NeedsViewer
        Step.Conflict -> Ending.Conflict
        Step.Storage -> Ending.Storage
        Step.Incompatible -> Ending.Incompatible
        Step.Failed -> Ending.Failed
    }

    fun step(status: Int, silentAsked: Boolean, confirmationShown: Boolean, message: String?): Step = when (status) {
        PackageInstaller.STATUS_SUCCESS -> Step.Installed
        PackageInstaller.STATUS_PENDING_USER_ACTION -> Step.Confirm
        PackageInstaller.STATUS_FAILURE_ABORTED -> when {
            confirmationShown -> Step.Cancelled
            silentAsked -> Step.SilentRefused
            else -> Step.Cancelled
        }
        PackageInstaller.STATUS_FAILURE_CONFLICT -> Step.Conflict
        PackageInstaller.STATUS_FAILURE_STORAGE -> Step.Storage
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Step.Incompatible
        else -> if (message != null && (message.contains("dead", ignoreCase = true) || message.contains("abandoned", ignoreCase = true))) {
            Step.Dead
        } else {
            Step.Failed
        }
    }
}
