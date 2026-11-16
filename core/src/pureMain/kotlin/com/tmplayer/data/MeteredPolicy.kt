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

    /**
     * Whether Settings offers "Wi-Fi only" here. Not on a television: a TV is wired
     * or on Wi-Fi, never on a data allowance paid for by the gigabyte, so there it would ask the
     * viewer to rule out something that cannot happen.
     */
    fun wifiOnlyOffered(television: Boolean): Boolean = !television

    /**
     * The switch as it applies on this device. Where it is not offered its stored value is kept, so
     * a backup carries it on to the next phone, but it holds nothing back: a phone's "Wi-Fi only"
     * restored onto a TV that happens to sit on a phone's hotspot would otherwise stop every video
     * behind a switch the TV has no row for.
     */
    fun wifiOnlyInForce(stored: Boolean, television: Boolean): Boolean = stored && wifiOnlyOffered(television)

    /**
     * Whether Settings offers "Save mobile data" here: where "Wi-Fi only" is, for the same reason.
     * A television is never on a data allowance, and the desktop has no metered state to read.
     */
    fun saveMobileDataOffered(television: Boolean): Boolean = wifiOnlyOffered(television)

    /**
     * "Save mobile data" as it applies on this device: kept where it is not offered, so a backup
     * carries it on, but bounding nothing there, as with [wifiOnlyInForce].
     */
    fun saveMobileDataInForce(stored: Boolean, television: Boolean): Boolean =
        stored && saveMobileDataOffered(television)

    fun decide(
        metered: Boolean,
        wifiOnly: Boolean,
        alreadyDownloaded: Boolean,
        warnedThisSession: Boolean,
        sizeBytes: Long,
        /** False under "On mobile data: Allow": the viewer said it is fine, so a large video is not asked about. */
        asks: Boolean = true,
    ): MeteredDecision = when {
        // A complete local file is played off disk; the connection it arrived over is history.
        alreadyDownloaded -> MeteredDecision.Allow
        !metered -> MeteredDecision.Allow
        wifiOnly -> MeteredDecision.Block
        warnedThisSession || !asks -> MeteredDecision.Allow
        sizeBytes >= LARGE_DOWNLOAD_BYTES -> MeteredDecision.Warn
        else -> MeteredDecision.Allow
    }

    /**
     * Roughly a long episode at a decent bitrate. Below this the pull is comparable to what any
     * other app on the phone does without asking, and a prompt would be noise.
     */
    const val LARGE_DOWNLOAD_BYTES = 300L * 1024 * 1024
}
