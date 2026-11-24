package com.tmplayer.data

/**
 * The two optional steps after the setup's question and before Home: "Notifications" on an
 * Android 13 or later phone or tablet, and "Support TMPlayer" on every device that has support
 * links. Each has a Skip, shows once, and never blocks: nothing here asks again after an answer
 * or a skip.
 *
 * Where it sits: the setup's question ends the setup ([SettingsStore.markSetupDone]), which arms
 * these steps for a fresh sign in only. An install that was updated (nothing armed) never sees
 * them. They run before Home's Tips card, so a sign in reads: chats question, notifications,
 * support, Home, and the quick tour offered at the foot of Home as before. Purely state driven:
 * the stored text is the only memory, so process death, rotation and a second launch all land on
 * the right step ([next]).
 */
object OnboardingSteps {

    enum class Step(val key: String) {
        Notifications("notifications"),
        Support("support"),
    }

    /** How a step ended, or where it stands. */
    enum class Outcome(val key: String) {
        /** The permission was granted: the step has nothing left to say. */
        Done("done"),

        /** Skip, or Back, was pressed: the steps never ask about it again. */
        Skipped("skipped"),

        /** The system's question was answered with a no: the step shows the settings route and never asks again. */
        Denied("denied"),

        /** The support page was opened in the browser. */
        Opened("opened"),
    }

    /** What is stored: whether the steps are armed, and each step's outcome so far. */
    data class State(val armed: Boolean = false, val outcomes: Map<Step, Outcome> = emptyMap()) {
        fun with(step: Step, outcome: Outcome) = copy(outcomes = outcomes + (step to outcome))
    }

    /** What the platform knows when it asks [next]. */
    data class Inputs(
        /**
         * Notifications cannot reach the person now, and this device is one that asks: Android 13 or
         * later, a phone or tablet. False on Android 12 and older, on a television, on the desktop
         * and once granted, so the step is skipped there.
         */
        val notificationsOff: Boolean = false,
        /** The build has support links (not the Play build). */
        val supportOffered: Boolean = false,
    )

    /**
     * The step to show now, or null when none is left or none is armed. Order: Notifications
     * first, Support second (never the first thing asked). A step that was Skipped, Done or
     * Opened is never shown again; a Denied notifications step is shown only in its settings
     * form, until it is skipped or the person turns notifications on in the system settings.
     */
    fun next(state: State, inputs: Inputs): Step? {
        if (!state.armed) return null
        val notes = state.outcomes[Step.Notifications]
        if (inputs.notificationsOff && notes != Outcome.Skipped && notes != Outcome.Done) return Step.Notifications
        if (inputs.supportOffered && state.outcomes[Step.Support] == null) return Step.Support
        return null
    }

    /** The steps this device has at all, in order, for "Step n of m". */
    fun applicable(inputs: Inputs): List<Step> = buildList {
        if (inputs.notificationsOff) add(Step.Notifications)
        if (inputs.supportOffered) add(Step.Support)
    }

    /** `armed;notifications=denied;support=opened`, or null before anything is armed. */
    fun encode(state: State): String? {
        if (!state.armed) return null
        return (listOf("armed") + state.outcomes.map { (s, o) -> "${s.key}=${o.key}" }).joinToString(";")
    }

    /** The stored text; anything unreadable is read as nothing armed, so a bad value shows nothing. */
    fun decode(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        val parts = raw.split(';')
        if ("armed" !in parts) return State()
        val outcomes = parts.mapNotNull { part ->
            val step = Step.entries.firstOrNull { it.key == part.substringBefore('=') } ?: return@mapNotNull null
            val outcome = Outcome.entries.firstOrNull { it.key == part.substringAfter('=', "") } ?: return@mapNotNull null
            step to outcome
        }.toMap()
        return State(armed = true, outcomes = outcomes)
    }
}
