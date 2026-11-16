package com.tmplayer.ui.onboarding

import com.tmplayer.data.AuthState
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Strings

/**
 * The setup after signing in is one question ([com.tmplayer.data.SetupFlow]): which chats have your
 * videos. These are the answers to everything it no longer asks, kept as the rows of Settings'
 * "Redo setup choices" (one thing each, a picture of each answer, the current answer marked), on
 * the phone, the television and the desktop alike. The device decides Lighter or Full on its own
 * ([SetupFlowEffects]) and the quick tour is a Tips card ([TipsCard]).
 *
 * Who sees the question: a sign in screen arms it (`SettingsStore.armSetup`), so an install that
 * was signed in before this existed never does, and a fresh sign in always does. Settings can run
 * it again.
 */
enum class SetupStep {
    /** Posters and overviews looked up online by the cleaned title, or plain tiles. */
    Posters,

    /** While streaming: keep a big video whole, or save space by keeping only a window of it. */
    Storage,

    /** Full pictures and motion, or Lighter pictures, Reduce motion and no thumbnails while seeking. */
    Performance,

    /** On a phone only: Save mobile data and downloads that wait for Wi-Fi, or no limits. */
    MobileData,

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
    ;

    /**
     * True for the answer Settings suggests. That is every setting's default, except on Mobile
     * data, where saving it is suggested although the settings start off: an install updated from
     * a version without the step keeps behaving as it did.
     */
    val recommended: Boolean
        get() = step.choices.first() == this

    /**
     * Whether this answer is pictured with a screenshot. The storage and mobile data steps draw
     * theirs, since a frame of the app says nothing about what is kept or fetched.
     */
    val screenshot: Boolean
        get() = step == SetupStep.Posters || step == SetupStep.Performance
}

/** The settings Settings' Setup choices reads and writes, as they are now. */
data class SetupValues(
    val posters: Boolean,
    val lowStorage: Boolean,
    val lighter: Boolean = false,
    val saveData: Boolean = false,
) {
    /**
     * The answer on [step] that matches what is set, as Settings' Setup choices names it. Unlike
     * [current], Mobile data says what is set rather than what is suggested; null for the Device
     * step, whose answer is an action rather than a plain setting (so a press always writes it
     * rather than only on a change).
     */
    fun chosen(step: SetupStep): SetupChoice? = when (step) {
        SetupStep.MobileData -> if (saveData) SetupChoice.SaveData else SetupChoice.NoLimits
        SetupStep.Device -> null
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
    }
}

object Setup {

    /**
     * The steps Settings' Setup choices lists for this build and this device. Posters only where
     * the build looks them up at all; Storage wherever the streaming mode exists; Performance on
     * every device; Mobile data only on a phone, the one device that is ever on a metered
     * connection for long.
     *
     * A device short of memory or storage, with settings still to recommend, gets the Device step
     * in Storage's place, since Low storage mode is among them when storage is short. When those
     * settings include the lighter ones ([devicePerformance]), it takes Performance's place too,
     * so nothing is listed twice.
     */
    fun steps(
        posters: Boolean,
        storage: Boolean = STORAGE_STEP,
        device: Boolean = false,
        devicePerformance: Boolean = device,
        phone: Boolean = false,
    ): List<SetupStep> =
        buildList {
            if (posters) add(SetupStep.Posters)
            if (device) add(SetupStep.Device) else if (storage) add(SetupStep.Storage)
            if (!(device && devicePerformance)) add(SetupStep.Performance)
            if (phone) add(SetupStep.MobileData)
        }

    /**
     * The steps for a device of [form]. [recommendations] are what this device is recommended and
     * still has off (CP68): any at all bring the Device step. The desktop is only ever offered
     * Low storage mode there, so its Performance step stays.
     */
    fun stepsFor(
        form: DeviceForm,
        posters: Boolean,
        recommendations: List<Recommendation>,
    ): List<SetupStep> = steps(
        posters = posters,
        device = recommendations.isNotEmpty(),
        devicePerformance = form != DeviceForm.Desktop && recommendations.any { it != Recommendation.LowStorageMode },
        phone = form == DeviceForm.Phone,
    )

    /** Whether the storage step is offered: the streaming mode is core's (`TdByteWindow`), on every platform. */
    const val STORAGE_STEP = true

    /**
     * The setup is drawn in the language in use ([active]), so that language has already been
     * seen: remembered as announced (ON-10), so the first Home does not say "Now in Español" after
     * a whole setup in it. Only a later change of language announces. A blank [active] is left.
     */
    suspend fun markLanguageSeen(settings: SettingsStore, active: String) {
        if (active.isBlank()) return
        runCatching { settings.setLanguageAnnounced(active) }
    }

    /** How long the chat list may take before the chat question offers a way out (Continue without choosing). */
    const val WAIT_FOR_CHATS_MS = 8_000L

    /** Whether [auth] is a screen of the sign in itself, which is what arms the setup. */
    fun isSignInStep(auth: AuthState): Boolean = when (auth) {
        AuthState.ChooseMethod, is AuthState.Qr, is AuthState.Phone, is AuthState.Code, is AuthState.Password,
        is AuthState.PasswordRecovery, is AuthState.EmailAddress, is AuthState.EmailCode -> true
        AuthState.Connecting, AuthState.Ready, is AuthState.Failed -> false
    }
}

/**
 * Writes what [choice] stands for. The one place an answer is applied, by Settings' Setup choices
 * (and nothing else now), so changing a choice there does what the setup used to do. [posters]
 * switches online posters and details (`OnlineMetadata.setEnabled`), null in a build without them,
 * and [recommendations] is what Apply turns on.
 */
suspend fun SetupChoice.applyTo(
    settings: SettingsStore,
    posters: ((Boolean) -> Unit)?,
    recommendations: List<Recommendation> = emptyList(),
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
    }
}

/** A step's heading and the sentence under it. */
data class SetupCopy(val title: String, val body: String)

/** An answer's name and the line under it. */
data class ChoiceCopy(val title: String, val detail: String)

fun Strings.setupCopy(step: SetupStep): SetupCopy = when (step) {
    SetupStep.Posters -> SetupCopy(setupPostersTitle, setupPostersBody)
    SetupStep.Storage -> SetupCopy(setupStorageTitle, setupStreamBody)
    SetupStep.Performance -> SetupCopy(setupPerformanceTitle, setupPerformanceBody)
    SetupStep.MobileData -> SetupCopy(setupMobileTitle, setupMobileBody)
    // The screen says which: memory, storage or both (fitHeadline).
    SetupStep.Device -> SetupCopy(deviceShortMemory, deviceQuestion)
}

fun Strings.choiceCopy(choice: SetupChoice): ChoiceCopy = when (choice) {
    SetupChoice.PostersOn -> ChoiceCopy(setupPostersOn, setupPostersOnDetail)
    SetupChoice.PostersOff -> ChoiceCopy(setupPostersOff, setupPostersOffDetail)
    SetupChoice.KeepWhilePlaying -> ChoiceCopy(settingsStreamKeep, settingsStreamKeepDetail)
    SetupChoice.LowStorage -> ChoiceCopy(settingsStreamSave, settingsStreamSaveDetail)
    SetupChoice.FullQuality -> ChoiceCopy(setupPerformanceFull, setupPerformanceFullDetail)
    SetupChoice.Lighter -> ChoiceCopy(setupPerformanceLighter, setupPerformanceLighterDetail)
    SetupChoice.SaveData -> ChoiceCopy(setupMobileSave, setupMobileSaveDetail)
    SetupChoice.NoLimits -> ChoiceCopy(setupMobileNoLimits, setupMobileNoLimitsDetail)
    SetupChoice.ApplyRecommended -> ChoiceCopy(deviceApply, deviceQuestion)
    SetupChoice.NotNow -> ChoiceCopy(deviceNotNow, settingsSavesAsYouGo)
}
