package com.tmplayer.data

/**
 * What the app gives back when Android says memory is short (`onTrimMemory`), pure so it is
 * tested alone. Levels are Android's numbers, spelt here because the constants are deprecated
 * from Android 14 though the levels still arrive.
 *
 * - The posters and thumbnails (`Thumbnails`, bounded by size): all of them when it is critical
 *   or the app is about to be killed, half otherwise, as before on every device. On a low-end
 *   device ([LowEndDevice.isLowEnd]) all of them from [RUNNING_LOW] up (2026-11-18): a 2 GB phone
 *   or a 1 GB stick playing a video has no room for Home's posters, and they come back from the disk.
 * - The answers kept for Home's shelves and the chat grids ([QueryCache], at most 160 answers of
 *   a page of videos each): on a low-end device from [RUNNING_LOW] up, the ones nobody asked for
 *   in the last [IDLE_QUERY_MS]. A normal device keeps them, as before.
 * - The video counts per chat (`ChatVideoCount`, one small entry per chat, no size bound but the
 *   account's chats): the expired ones, on the same line as the answers.
 */
object MemoryTrim {

    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80

    /** An answer not asked for in this long is dropped when memory is short. */
    const val IDLE_QUERY_MS = 60_000L

    enum class Pictures { Half, All }

    data class Plan(val pictures: Pictures, val dropIdleQueries: Boolean)

    fun of(level: Int, lowEnd: Boolean): Plan {
        val all = level == RUNNING_CRITICAL || level >= MODERATE || (lowEnd && level >= RUNNING_LOW)
        val queries = lowEnd && level >= RUNNING_LOW
        return Plan(if (all) Pictures.All else Pictures.Half, queries)
    }
}
