package com.tmplayer.data

/**
 * TDLib's file calls, answered from maps, for the code that moves downloads out of its cache.
 *
 * [bytes] is what each file id has on disk and [paths] where; [deleted] records every id the code
 * under test told TDLib to let go of. [renumbered] stands in for a saved id that a fresh session
 * knows by another number.
 */
class FakeTdFiles(
    val bytes: MutableMap<Int, Long> = mutableMapOf(),
    val paths: MutableMap<Int, String> = mutableMapOf(),
    private val renumbered: Map<Int, Int> = emptyMap(),
) : TdFiles {
    val deleted = mutableListOf<Int>()

    override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int): Int =
        renumbered[storedFileId] ?: storedFileId

    override suspend fun deleteFile(fileId: Int) {
        deleted += fileId
        bytes.remove(fileId)
        paths.remove(fileId)
    }

    override suspend fun localDownloadedBytes(fileId: Int): Long = bytes[fileId] ?: 0L

    override suspend fun localPathAnyway(fileId: Int): String? = paths[fileId]
}
