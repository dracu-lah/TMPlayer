package com.tmplayer.data

/** Whether a Telegram file is safe to use without asking the network for another byte. */
enum class LocalFileAvailability {
    Complete,
    Partial,
    Missing,
}

/** Pure policy kept separate from TDLib and the filesystem so its edge cases stay testable. */
object LocalFilePolicy {

    fun evaluate(
        downloadCompleted: Boolean,
        pathPresent: Boolean,
        regularFile: Boolean,
        length: Long,
        size: Long,
        expectedSize: Long,
    ): LocalFileAvailability {
        if (!pathPresent || !regularFile || length <= 0) return LocalFileAvailability.Missing
        if (!downloadCompleted) return LocalFileAvailability.Partial

        val knownFullSize = maxOf(size, expectedSize)
        return if (knownFullSize <= 0 || length >= knownFullSize) {
            LocalFileAvailability.Complete
        } else {
            LocalFileAvailability.Partial
        }
    }
}
