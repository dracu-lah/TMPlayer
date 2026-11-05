package com.tmplayer.data

import android.content.Context
import android.content.res.Configuration

/** The Interface setting (HX78): the layout the user picked, or [Auto] for the device's own answer. */
enum class InterfaceChoice(val key: String) {
    Auto("auto"),
    Tv("tv"),
    Phone("phone"),
    ;

    companion object {
        fun of(key: String?): InterfaceChoice = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * One answer, for the whole process, to "is this a television?".
 *
 * The UI branches on this in several places, so it has to be the same answer everywhere: a screen
 * that decides it is on a TV while the player decides it is on a phone is worse than either.
 */
object FormFactor {

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var signals: FormSignals? = null

    /**
     * True on a TV, false on a touch device.
     *
     * The Interface setting first, else [FormRules] over what the device says about itself:
     * leanback or the television UI mode on a certified TV, and the hardware on a generic box
     * that reports neither (HX78). Read once per process: `App.onCreate` and the layouts all
     * decide from it, so a change of the setting restarts the app ([FormRestart]).
     */
    fun isTv(context: Context): Boolean = cached ?: compute(context).also { cached = it }

    /** The same answer as [isTv], in the shared vocabulary: Android is never [DeviceForm.Desktop]. */
    fun form(context: Context): DeviceForm = if (isTv(context)) DeviceForm.Tv else DeviceForm.Phone

    /**
     * Answers [isTv] with [value] from here on, whatever the hardware says.
     *
     * This exists for the screenshot fixture, which captures the television layout on a phone
     * panel resized to 1920x1080. Nothing in a release build calls it: the promo activity is the
     * only caller and it is not part of a release APK.
     */
    fun override(value: Boolean) {
        cached = value
    }

    /** The layout picked in Settings. Read synchronously, before any activity, so not DataStore. */
    fun choice(context: Context): InterfaceChoice =
        runCatching { InterfaceChoice.of(prefs(context).getString(KEY_INTERFACE, null)) }.getOrDefault(InterfaceChoice.Auto)

    /**
     * Saves [choice] for the next start; [isTv] keeps its answer until then. `commit`, not
     * `apply`, because the process is about to be replaced.
     */
    fun setChoice(context: Context, choice: InterfaceChoice) {
        runCatching { prefs(context).edit().putString(KEY_INTERFACE, choice.key).commit() }
    }

    /** What Automatic would answer on this device, whatever is picked. */
    fun autoIsTv(context: Context): Boolean = FormRules.classify(signals(context)) == DeviceForm.Tv

    /**
     * True when the layout came from the hardware guess, not from the system or the user, so a
     * touch on the TV layout can offer the phone one back.
     */
    fun guessed(context: Context): Boolean = choice(context) == InterfaceChoice.Auto && FormRules.guessed(signals(context))

    /**
     * The UI mode an activity has to override (HX78) so `values-television` follows [isTv] rather
     * than the system: the TV type on a box the app treats as a TV, the normal type on a TV the
     * user switched to the touch layout. Null when the two agree. Only the type is set, so night
     * mode still comes from the system. For `applyOverrideConfiguration` in `attachBaseContext`.
     */
    fun uiModeOverride(base: Context): Configuration? = runCatching {
        val tv = isTv(base)
        val type = base.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val systemTv = type == Configuration.UI_MODE_TYPE_TELEVISION
        if (tv == systemTv) return@runCatching null
        Configuration().apply {
            uiMode = if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        }
    }.getOrNull()

    /** Android on a desktop or a Chromebook: arrow keys there are a keyboard's, not a remote's. */
    fun desktopLike(context: Context): Boolean = signals(context).let { it.pc == true || it.chromebook == true }

    /** The one log line a box owner's report needs: the answer, why, and every signal behind it. */
    fun describe(context: Context): String {
        val s = signals(context)
        return "tv=${isTv(context)} choice=${choice(context).key} auto=${FormRules.reason(s)} signals=$s"
    }

    /** "Never" on the remote nudge, kept with the setting. */
    fun nudgeNever(context: Context): Boolean = runCatching { prefs(context).getBoolean(KEY_NUDGE_NEVER, false) }.getOrDefault(false)

    fun setNudgeNever(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_NUDGE_NEVER, true).apply() }
    }

    private fun signals(context: Context): FormSignals =
        signals ?: FormSignalsAndroid.read(context).also { signals = it }

    private fun compute(context: Context): Boolean = when (choice(context)) {
        InterfaceChoice.Tv -> true
        InterfaceChoice.Phone -> false
        InterfaceChoice.Auto -> autoIsTv(context)
    }

    // Its own file, outside the settings backup (and the manifest has allowBackup off): a phone
    // restoring a box's settings must not come up in the TV layout.
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "form_prefs"
    private const val KEY_INTERFACE = "interface"
    private const val KEY_NUDGE_NEVER = "nudge_never"
}
