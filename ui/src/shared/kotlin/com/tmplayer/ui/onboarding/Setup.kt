package com.tmplayer.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.data.AuthState
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Strings

/**
 * The short setup after signing in (CP67, CP69), on the phone, the television and the desktop
 * alike. It replaced the two page tour before sign in and the one card after it (CP42): the sign
 * in screen comes first, and once the account is in, the setup runs as one flow in three groups
 * ([SetupAndTour]): Appearance, these preference steps (one thing each, a picture of each answer,
 * the current answer marked and Next to keep or apply it), and the quick tour ([Tour]) last.
 *
 * Who sees it: a sign in screen arms it (`SettingsStore.armSetup`), so an install that was signed
 * in before this existed never does, and a fresh sign in always does. Settings can run it again.
 */
enum class SetupStep {
    /** Posters and overviews looked up online by the cleaned title, or plain tiles. */
    Posters,

    /** Low storage mode: stream big videos without keeping them whole. */
    Storage,

    /** Full pictures and motion, or Lighter pictures, Reduce motion and no thumbnails while seeking. */
    Performance,

    /** On a phone only: Save mobile data and downloads that wait for Wi-Fi, or no limits. */
    MobileData,

    /**
     * Which chats TMPlayer reads for videos (HX28): browse the whole account, or open the "Add
     * chats" picker ([com.tmplayer.ui.browse.AddChatsScreen], HX27) and choose a smaller set.
     * Only where the chat list has come in with enough chats to make narrowing worth asking
     * ([Setup.worthChoosingChats]); the picker's own Done is what writes the answer, not Next, so
     * the step is drawn with its own rows rather than the two pictured answers every other step
     * has (there is nothing to picture: the real choice is a screen of chats).
     */
    Chats,

    /**
     * Send crash reports (CP102f), the one consent question: off until it is turned on, with no
     * answer suggested. Only in a build that can report ([CrashReporting.available]), always the
     * last of the preferences, and drawn as one switch rather than two pictured answers.
     */
    CrashReports,

    /**
     * A device short of memory or storage (CP68): apply the recommended settings, or not now. It
     * takes the storage step's place, since Low storage mode is among them when storage is short.
     */
    Device,
    ;

    /** The two answers, in the order they are shown. The first is the recommended one. */
    val choices: List<SetupChoice> get() = SetupChoice.entries.filter { it.step == this }
}

/** One answer on a [SetupStep]. */
enum class SetupChoice(val step: SetupStep) {
    PostersOn(SetupStep.Posters),
    PostersOff(SetupStep.Posters),
    KeepWhilePlaying(SetupStep.Storage),
    LowStorage(SetupStep.Storage),
    FullQuality(SetupStep.Performance),
    Lighter(SetupStep.Performance),
    SaveData(SetupStep.MobileData),
    NoLimits(SetupStep.MobileData),
    ApplyRecommended(SetupStep.Device),
    NotNow(SetupStep.Device),
    // Off first: it is what Next keeps when the switch was never touched.
    CrashReportsOff(SetupStep.CrashReports),
    CrashReportsOn(SetupStep.CrashReports),
    // Browse everything first: it is the recommended, default answer, and what every account
    // already does until this is answered (AddedChats.AddedMode.Unanswered reads the same as
    // every chat). Choosing it writes that on purpose (HX28's "answer rather than silence"), so a
    // later nag for existing accounts never asks somebody who already went through this.
    BrowseAllChats(SetupStep.Chats),
    // Writing the real answer takes a screen of chats, not a tap: the picker's own Done does it
    // (AddChatsViewModel.done), not this answer's applyTo. Marking it opens the picker at once.
    ChooseChats(SetupStep.Chats),
    ;

    /**
     * True for the answer the setup suggests. That is every setting's default, except on Mobile
     * data, where saving it is suggested although the settings start off: an install updated from
     * a version without the step keeps behaving as it did. Crash reports suggest neither: sending
     * them is consent, so nothing there is marked as the better answer.
     */
    val recommended: Boolean
        get() = step != SetupStep.CrashReports && step.choices.first() == this

    /**
     * Whether this answer is pictured with a screenshot. The storage and mobile data steps draw
     * theirs, since a frame of the app says nothing about what is kept or fetched.
     */
    val screenshot: Boolean
        get() = step == SetupStep.Posters || step == SetupStep.Performance
}

/** The settings the setup reads and writes, as they are now. */
data class SetupValues(
    val posters: Boolean,
    val lowStorage: Boolean,
    val lighter: Boolean = false,
    val saveData: Boolean = false,
    val crashReports: Boolean = false,
    /** Whether [com.tmplayer.data.AddedChats] already holds a chosen set (HX27's Picked mode). */
    val addedChatsPicked: Boolean = false,
) {
    /**
     * The answer on [step] that matches what is set, as Settings' Setup choices names it. Unlike
     * [current], Mobile data says what is set rather than what is suggested; null for the Device
     * and Chats steps, whose answers are an action rather than a plain setting (so Next always
     * writes or opens something rather than only on a change).
     */
    fun chosen(step: SetupStep): SetupChoice? = when (step) {
        SetupStep.MobileData -> if (saveData) SetupChoice.SaveData else SetupChoice.NoLimits
        SetupStep.Device, SetupStep.Chats -> null
        else -> current(step)
    }

    /** The answer on [step] that matches what is set, the one a remote starts on. */
    fun current(step: SetupStep): SetupChoice = when (step) {
        SetupStep.Posters -> if (posters) SetupChoice.PostersOn else SetupChoice.PostersOff
        SetupStep.Storage -> if (lowStorage) SetupChoice.LowStorage else SetupChoice.KeepWhilePlaying
        SetupStep.Performance -> if (lighter) SetupChoice.Lighter else SetupChoice.FullQuality
        // A remote starts on the suggestion here, the settings being off until somebody chooses.
        SetupStep.MobileData -> SetupChoice.SaveData
        // Apply is what the step is for; Not now is the press that changes nothing.
        SetupStep.Device -> SetupChoice.ApplyRecommended
        // What is set, which on a fresh install is off: the switch is never turned on for anyone.
        SetupStep.CrashReports -> if (crashReports) SetupChoice.CrashReportsOn else SetupChoice.CrashReportsOff
        // A returning viewer (Settings' "Run the setup again", a reinstall that restored its
        // backup) starts marked on what they already chose, never silently back to everything.
        SetupStep.Chats -> if (addedChatsPicked) SetupChoice.ChooseChats else SetupChoice.BrowseAllChats
    }
}

object Setup {

    /**
     * The steps this build and this device get. Posters only where the build looks them up at
     * all; Storage wherever Low storage mode exists; Performance on every
     * device; Mobile data only on a phone, the one device that is ever on a metered connection
     * for long.
     *
     * A device short of memory or storage, with settings still to recommend, gets the Device step
     * in Storage's place, since Low storage mode is among them when storage is short. When those
     * settings include the lighter ones ([devicePerformance]), it takes Performance's place too,
     * so nothing is asked twice.
     */
    fun steps(
        posters: Boolean,
        storage: Boolean = STORAGE_STEP,
        device: Boolean = false,
        devicePerformance: Boolean = device,
        phone: Boolean = false,
        /** Whether the Chats step (HX28) is offered: the host's own call to [worthChoosingChats]. */
        chats: Boolean = false,
    ): List<SetupStep> =
        buildList {
            if (posters) add(SetupStep.Posters)
            if (device) add(SetupStep.Device) else if (storage) add(SetupStep.Storage)
            if (!(device && devicePerformance)) add(SetupStep.Performance)
            if (phone) add(SetupStep.MobileData)
            // Last of the preferences proper: it is the one step that needs a loaded chat list and
            // a screen of its own, so it comes after every quick, two-tap step.
            if (chats) add(SetupStep.Chats)
        }

    /**
     * The steps for a device of [form], worked out the same way by the setup after sign in and by
     * Settings' Setup choices, so the two never list different steps. [recommendations] are what
     * this device is recommended and still has off (CP68): any at all bring the Device step. The
     * desktop is only ever offered Low storage mode there, so its Performance step stays.
     */
    fun stepsFor(
        form: DeviceForm,
        posters: Boolean,
        recommendations: List<Recommendation>,
        /**
         * Whether the Chats step is worth offering right now: the chat list has come in
         * ([worthChoosingChats] over its size). Defaults to false, so Settings' Setup choices
         * (which calls this without a chat list) never lists it: that page already has its own
         * permanent "Add more chats" row, and showing a second, smaller picker beside it would be
         * a second thing to keep in step rather than a help.
         */
        chatsAvailable: Boolean = false,
    ): List<SetupStep> = steps(
        posters = posters,
        device = recommendations.isNotEmpty(),
        devicePerformance = form != DeviceForm.Desktop && recommendations.any { it != Recommendation.LowStorageMode },
        phone = form == DeviceForm.Phone,
        chats = chatsAvailable,
    )

    /**
     * Whether the chat list has enough chats for narrowing them to mean anything. Below this, a
     * fresh account (Saved Messages and a couple of chats) would meet a picker with nothing to
     * choose from, which is worse than the step it replaces: never asked, never a dead end.
     */
    const val CHATS_STEP_MIN = 4

    fun worthChoosingChats(totalChats: Int): Boolean = totalChats >= CHATS_STEP_MIN

    /**
     * Whether the storage step is offered. Low storage mode is core's (`TdByteWindow`), so the
     * phone, the television and the desktop all have it.
     */
    const val STORAGE_STEP = true

    /**
     * [steps] with the crash report question at the end, where the build can send a report
     * ([CrashReporting.available]). Settings' Setup choices leave it out: Settings has the same
     * switch in its own place, and the per-crash prompt stays as it is.
     */
    fun withCrashReports(steps: List<SetupStep>, available: Boolean): List<SetupStep> =
        if (available && SetupStep.CrashReports !in steps) steps + SetupStep.CrashReports else steps

    /** What the shell does about the setup right now. */
    enum class Decision {
        /** Nothing to do: not armed, or already done. */
        None,

        /** Armed, but the chat list (and with it the folder list) is not in yet. */
        Wait,

        /** Show it. */
        Show,
    }

    /**
     * The setup's next move. [pending] is the armed flag; [chatsLoaded] whether the chat list has
     * come from TDLib, which sends the folders before it, so an empty folder list then really
     * means none. [waitedOut] gives up waiting on a list that is slow to come, and the setup goes
     * ahead with the folders known so far.
     */
    fun decide(pending: Boolean, chatsLoaded: Boolean, waitedOut: Boolean = false): Decision = when {
        !pending -> Decision.None
        !chatsLoaded && !waitedOut -> Decision.Wait
        else -> Decision.Show
    }

    /** How long the setup waits for the chat list before going ahead without it. */
    const val WAIT_FOR_CHATS_MS = 8_000L

    /** Whether [auth] is a screen of the sign in itself, which is what arms the setup. */
    fun isSignInStep(auth: AuthState): Boolean = when (auth) {
        AuthState.ChooseMethod, is AuthState.Qr, is AuthState.Phone, is AuthState.Code, is AuthState.Password,
        is AuthState.PasswordRecovery, is AuthState.EmailAddress, is AuthState.EmailCode -> true
        AuthState.Connecting, AuthState.Ready, is AuthState.Failed -> false
    }
}

/**
 * Writes what [choice] stands for. The one place an answer is applied, by the setup's steps and by
 * Settings' Setup choices alike, so changing a choice there does exactly what the setup did.
 * [posters] switches online posters and details (`OnlineMetadata.setEnabled`), null in a build
 * without them, [recommendations] what Apply turns on, and [crashReports] the platform's own
 * start or stop of crash reporting once the setting is written ([CrashReporting.onChange]).
 */
suspend fun SetupChoice.applyTo(
    settings: SettingsStore,
    posters: ((Boolean) -> Unit)?,
    recommendations: List<Recommendation> = emptyList(),
    crashReports: ((Boolean) -> Unit)? = null,
) {
    when (this) {
        SetupChoice.PostersOn -> posters?.invoke(true)
        SetupChoice.PostersOff -> posters?.invoke(false)
        SetupChoice.KeepWhilePlaying -> settings.setLowStorageMode(false)
        SetupChoice.LowStorage -> settings.setLowStorageMode(true)
        // The three rows the Device step offers a slow device (CP68), together.
        SetupChoice.FullQuality -> {
            settings.setLighterPictures(false)
            settings.setReduceMotion(false)
            settings.setTrickplay(true)
        }
        SetupChoice.Lighter -> {
            settings.setLighterPictures(true)
            settings.setReduceMotion(true)
            settings.setTrickplay(false)
        }
        // Save mobile data alone, the row its Also in link opens. Wi-Fi only stays as it was: it
        // also stops a video that is not downloaded from playing on mobile data at all, which is
        // more than an answer about saving data should switch on.
        SetupChoice.SaveData -> settings.setSaveMobileData(true)
        SetupChoice.NoLimits -> {
            settings.setSaveMobileData(false)
            settings.setWifiOnlyDownloads(false)
        }
        SetupChoice.ApplyRecommended -> settings.applyRecommendations(recommendations)
        SetupChoice.NotNow -> Unit
        // The setting Settings' "Send crash reports" switch writes, then the SDK brought up or down.
        SetupChoice.CrashReportsOn -> {
            settings.setCrashReports(true)
            crashReports?.invoke(true)
        }
        SetupChoice.CrashReportsOff -> {
            settings.setCrashReports(false)
            crashReports?.invoke(false)
        }
        // "Keep all my chats" (HX28): an explicit answer, not silence, so a later one-time prompt
        // for accounts that never saw this never asks somebody who already did.
        SetupChoice.BrowseAllChats -> settings.keepAllChats()
        // Never reached in the setup itself: the step opens the real picker before this would run
        // (SetupScreen's own Next, over SetupStep.Chats), and the picker's own Done
        // (AddChatsViewModel.done) writes the answer directly. Kept only so this `when` stays
        // exhaustive for callers outside the setup screen.
        SetupChoice.ChooseChats -> Unit
    }
}

/**
 * What a platform tells the first run about crash reports (CP102f). [available] is whether this
 * build can send a report at all (on Android `CrashReports.available`: a build with no DSN has
 * nowhere to send one, so the question is not asked, as Settings leaves out its switch).
 * [onChange] runs after the setting is written, to start the reporting SDK or stop it.
 *
 * The desktop passes [Unavailable] until it reports crashes (CP102g). CP100's Preferences screen
 * takes the same value for its "Send crash reports" row ([CrashReportsQuestion]).
 */
class CrashReporting(val available: Boolean, val onChange: (Boolean) -> Unit = {}) {
    companion object {
        /** A build that cannot send a report: the question is never shown. */
        val Unavailable = CrashReporting(available = false)
    }
}

/**
 * Where the setup is. Picking an answer or Skip moves on and finishes after the last step; Back
 * goes to the step before, and from the first leaves the setup as Skip all does. Snapshot state,
 * so a screen that reads [index] recomposes when it moves.
 */
class SetupState(val steps: List<SetupStep>, start: Int = 0) {
    init {
        require(steps.isNotEmpty()) { "a setup with no steps is not shown" }
    }

    var index by mutableIntStateOf(start.coerceIn(0, steps.lastIndex))
        private set

    val step: SetupStep get() = steps[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == steps.lastIndex

    /** Skip all is offered while more than this step is left; on the last, Skip says the same. */
    val canSkipAll: Boolean get() = !isLast

    /** Moves on a step; false when this was the last, which means the setup is done. */
    fun next(): Boolean {
        if (isLast) return false
        index++
        return true
    }

    /** Moves back a step; false on the first, which means leaving the setup. */
    fun back(): Boolean {
        if (isFirst) return false
        index--
        return true
    }
}

/** A step's heading and the sentence under it. */
data class SetupCopy(val title: String, val body: String)

/** An answer's name and the line under it. */
data class ChoiceCopy(val title: String, val detail: String)

fun Strings.setupCopy(step: SetupStep): SetupCopy = when (step) {
    SetupStep.Posters -> SetupCopy(setupPostersTitle, setupPostersBody)
    SetupStep.Storage -> SetupCopy(setupStorageTitle, setupStorageBody)
    SetupStep.Performance -> SetupCopy(setupPerformanceTitle, setupPerformanceBody)
    SetupStep.MobileData -> SetupCopy(setupMobileTitle, setupMobileBody)
    // The screen says which: memory, storage or both (fitHeadline).
    SetupStep.Device -> SetupCopy(deviceShortMemory, deviceQuestion)
    SetupStep.Chats -> SetupCopy(setupChatsTitle, setupChatsBody)
    SetupStep.CrashReports -> SetupCopy(setupCrashTitle, setupCrashBody)
}

fun Strings.choiceCopy(choice: SetupChoice): ChoiceCopy = when (choice) {
    SetupChoice.PostersOn -> ChoiceCopy(setupPostersOn, setupPostersOnDetail)
    SetupChoice.PostersOff -> ChoiceCopy(setupPostersOff, setupPostersOffDetail)
    SetupChoice.KeepWhilePlaying -> ChoiceCopy(setupStorageNormal, setupStorageNormalDetail)
    SetupChoice.LowStorage -> ChoiceCopy(setupStorageLow, setupStorageLowDetail)
    SetupChoice.FullQuality -> ChoiceCopy(setupPerformanceFull, setupPerformanceFullDetail)
    SetupChoice.Lighter -> ChoiceCopy(setupPerformanceLighter, setupPerformanceLighterDetail)
    SetupChoice.SaveData -> ChoiceCopy(setupMobileSave, setupMobileSaveDetail)
    SetupChoice.NoLimits -> ChoiceCopy(setupMobileNoLimits, setupMobileNoLimitsDetail)
    SetupChoice.ApplyRecommended -> ChoiceCopy(deviceApply, deviceQuestion)
    SetupChoice.NotNow -> ChoiceCopy(deviceNotNow, settingsSavesAsYouGo)
    SetupChoice.CrashReportsOn -> ChoiceCopy(settingsCrashReports, setupCrashHolds)
    SetupChoice.CrashReportsOff -> ChoiceCopy(settingsCrashReports, settingsCrashReportsOff)
    SetupChoice.BrowseAllChats -> ChoiceCopy(setupChatsAll, setupChatsAllDetail)
    SetupChoice.ChooseChats -> ChoiceCopy(setupChatsChoose, setupChatsChooseDetail)
}
