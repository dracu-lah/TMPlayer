package com.tmplayer.player

/**
 * What "Reload" should actually do to a video that would not play.
 *
 * A plain retry re-prepares the same stream over the same bytes, which fixes a moved download
 * window or a connection that came back. It cannot fix a half written partial file on disk, where
 * every attempt over those bytes fails the same way, so the fix there is to throw the local copy
 * away and stream it again from nothing.
 *
 * That is destructive, which is why the choice is a pure named function rather than an `if` buried
 * in a click listener. A video the viewer downloaded on purpose is never thrown away: it may be
 * the only copy they have offline, and re-fetching it may be a gigabyte they are paying for. The
 * same goes for one a download is working on right now, whose bytes belong to the service rather
 * than to this screen.
 */
object Reload {

    enum class Plan {
        /** Drop TDLib's copy of the file, then open the stream again from nothing. */
        StartOver,

        /** Prepare the same player over the same bytes: nothing on disk is touched. */
        PlainRetry,
    }

    /**
     * @param keptForOffline the viewer asked for this video by name and it is theirs to keep.
     * @param queuedForOffline a download service run owns these bytes, finished or not.
     */
    fun plan(fileId: Int, keptForOffline: Boolean, queuedForOffline: Boolean): Plan = when {
        fileId <= 0 -> Plan.PlainRetry
        keptForOffline || queuedForOffline -> Plan.PlainRetry
        else -> Plan.StartOver
    }

    /**
     * What the button says it will do, so a press that is about to delete a gigabyte does not read
     * the same as one that will not.
     */
    fun label(plan: Plan): String = when (plan) {
        Plan.StartOver -> "Reload from scratch"
        Plan.PlainRetry -> "Reload"
    }

    /** The stage caption while it runs, for the same reason. */
    fun status(plan: Plan): String = when (plan) {
        Plan.StartOver -> "Clearing the cached copy…"
        Plan.PlainRetry -> "Trying again…"
    }
}
