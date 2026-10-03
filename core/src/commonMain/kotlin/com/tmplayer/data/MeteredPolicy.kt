package com.tmplayer.data

/** What to do about a video the viewer has asked for while the connection is a metered one. */
enum class MeteredDecision {
    /** Go ahead: nothing here costs the viewer anything they have not already agreed to. */
    Allow,

    /** Ask first, once. A large pull over cellular is worth a sentence before it starts. */
    Warn,

    /** Refuse: the viewer has asked for Wi-Fi only and this is not Wi-Fi. */
    Block,
}

/**
 * Whether a video may be fetched over the connection this device currently has.
 *
 * The rules are deliberately quiet: a file already on disk asks nothing of the network, and a
 * small one is not worth a dialog, so only the case that actually costs money interrupts anybody.
 *
 * Pure so the decision can be tested rather than reasoned about from a phone with a SIM in it.
 */
object MeteredPolicy {

    fun decide(
        metered: Boolean,
        wifiOnly: Boolean,
        alreadyDownloaded: Boolean,
        warnedThisSession: Boolean,
        sizeBytes: Long,
    ): MeteredDecision = when {
        // A complete local file is played off disk; the connection it arrived over is history.
        alreadyDownloaded -> MeteredDecision.Allow
        !metered -> MeteredDecision.Allow
        wifiOnly -> MeteredDecision.Block
        warnedThisSession -> MeteredDecision.Allow
        sizeBytes >= LARGE_DOWNLOAD_BYTES -> MeteredDecision.Warn
        else -> MeteredDecision.Allow
    }

    /**
     * Roughly a long episode at a decent bitrate. Below this the pull is comparable to what any
     * other app on the phone does without asking, and a prompt would be noise.
     */
    const val LARGE_DOWNLOAD_BYTES = 300L * 1024 * 1024
}
