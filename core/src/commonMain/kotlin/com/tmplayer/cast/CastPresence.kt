package com.tmplayer.cast

/**
 * When the Cast button is drawn (FQ8, the admission gate's "appears when relevant"): only once a TV
 * has been found on this network, Google Cast or DLNA alike, or while a cast is running or being
 * set up. A person with no TV to cast to never sees the button.
 *
 * - **Found this visit.** A TV seen while this screen was up keeps the button for the rest of the
 *   visit, so the picker's own search (which starts its list empty) never takes the button away
 *   from under a finger, and nor does a TV that goes quiet.
 * - **Seen a moment ago.** A TV seen in the last [REMEMBER_MS] anywhere in the app (the player, then
 *   the detail page) shows the button at once while the search confirms it; a search that ends
 *   with nothing found forgets it, and the button goes.
 * - **A cast under way** (connecting, connected, dropped, failed) always has the button: it is the
 *   way to Stop casting and to another TV.
 *
 * Pure, the clock passed in, for the tests; the phone ([com.tmplayer.cast.CastPresenceScan] in the
 * app) and the desktop's player bar each drive it with their own search.
 */
class CastPresence(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val memory: Memory = Memory.process,
) {
    /** When a TV was last seen, shared by every screen of the process. */
    class Memory {
        @Volatile
        var seenAtMs: Long? = null

        companion object {
            val process = Memory()
        }
    }

    var foundThisVisit = false
        private set
    var scanning = false
        private set
    private var foundThisScan = false

    fun scanStarted() {
        scanning = true
        foundThisScan = false
    }

    fun scanEnded() {
        if (scanning && !foundThisScan && !foundThisVisit) memory.seenAtMs = null
        scanning = false
    }

    /** What the list holds now: [anyFound] when at least one TV is in it, of either kind. */
    fun seen(anyFound: Boolean) {
        if (!anyFound) return
        foundThisVisit = true
        foundThisScan = true
        memory.seenAtMs = clock()
    }

    private fun rememberedRecently(): Boolean {
        val at = memory.seenAtMs ?: return false
        val age = clock() - at
        return age in 0..REMEMBER_MS
    }

    /** [castUnderWay]: connecting, connected, dropped or failed, anything but idle. */
    fun visible(available: Boolean, castUnderWay: Boolean): Boolean =
        available && (castUnderWay || foundThisVisit || (scanning && rememberedRecently()))

    companion object {
        const val REMEMBER_MS = 10 * 60_000L
    }
}
