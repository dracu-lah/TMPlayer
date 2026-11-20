package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The chat listings opened most recently, on the disk, so a cold start opens one of them at once
 * and brings it up to date behind ([PageQueries]), as Home does with [HomeCache].
 *
 * Kept in [HomeCache]'s spelling: one JSON file written whole through a temporary file, the videos
 * as Telegram gave them (before the size limits), TDLib's file ids as they are. Forgotten on sign
 * out ([clear]). The JVM only: the browser keeps its listings in memory. Each profile keeps its own
 * copy beside [base] ([Accounts.scopedFile], HX88).
 */
class ChatGridDisk(private val base: File) : GridStore {

    /** What was last written and where, so a save of the same listings is skipped. */
    @Volatile
    private var written: Pair<File, String>? = null

    /** The open profile's file. */
    private val file: File get() = Accounts.scopedFile(base)

    init {
        // A removed profile's listings go with its folders (HX88): "" is the adopted profile's own file.
        Accounts.onPurge { folder ->
            val doomed = Accounts.scopedFile(base, folder.ifEmpty { null })
            synchronized(this) {
                runCatching { doomed.delete() }
                runCatching { File(doomed.path + ".tmp").delete() }
                if (written?.first == doomed) written = null
            }
        }
    }

    override fun load(): List<SavedGrid> =
        runCatching { file.takeIf { it.isFile }?.readText()?.let(::decode) }.getOrNull().orEmpty()

    /**
     * Writes [grids] to the file of [stamp]'s profile, the one they were gathered for. Dropped when
     * that profile is no longer open: checked under the lock the write holds, so a switch between
     * gathering and writing never puts one profile's listings in another's file.
     */
    override fun save(grids: List<SavedGrid>, stamp: AccountStamp) {
        val text = encode(grids)
        val file = Accounts.scopedFile(base, stamp)
        if (written == (file to text)) return
        synchronized(this) {
            if (!stamp.isCurrent) return
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(text)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
                written = file to text
            }
        }
    }

    override fun clear() {
        synchronized(this) {
            written = null
            runCatching { file.delete() }
        }
    }

    companion object {
        private const val VERSION = 1

        fun encode(grids: List<SavedGrid>): String = JSONObject()
            .put("v", VERSION)
            .put("grids", JSONArray(grids.map { saved ->
                val grid = saved.grid
                val c = grid.cursors
                JSONObject()
                    .put("chat", saved.chatId)
                    .put("topic", saved.topicId)
                    .put("saved", saved.savedAt)
                    .put("end", grid.endReached)
                    .put("sd", grid.selfDestructing)
                    .put("si", grid.scrollIndex)
                    .put("so", grid.scrollOffset)
                    .put("cur", JSONArray(listOf(c.video, c.document, c.animation)))
                    .put("done", JSONArray(listOf(c.videoDone, c.documentDone, c.animationDone)))
                    .put("items", JSONArray(grid.items.map { HomeCache.itemJson(it) }))
                    .put("arch", JSONArray(grid.archives.map { a ->
                        JSONObject()
                            .put("c", a.chatId).put("m", a.messageId).put("f", a.fileId).put("n", a.fileName)
                            .put("s", a.sizeBytes).put("d", a.date).put("k", a.kind.name).put("vol", a.volume)
                    }))
            }))
            .toString()

        /** Anything unreadable is dropped: it is a cache, and the chat is asked again on opening. */
        fun decode(text: String): List<SavedGrid> {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            if (json.optInt("v") != VERSION) return emptyList()
            return objects(json.optJSONArray("grids")).mapNotNull { g ->
                runCatching {
                    val cur = g.getJSONArray("cur")
                    val done = g.getJSONArray("done")
                    val grid = ChatGrid(
                        items = objects(g.optJSONArray("items")).mapNotNull { HomeCache.itemOf(it) },
                        cursors = MediaCursors(
                            video = cur.getLong(0),
                            document = cur.getLong(1),
                            animation = cur.getLong(2),
                            videoDone = done.getBoolean(0),
                            documentDone = done.getBoolean(1),
                            animationDone = done.getBoolean(2),
                        ),
                        endReached = g.optBoolean("end"),
                        archives = objects(g.optJSONArray("arch")).mapNotNull { a ->
                            val kind = ArchiveKind.entries.firstOrNull { it.name == a.optString("k") } ?: return@mapNotNull null
                            ArchiveFile(
                                chatId = a.getLong("c"),
                                messageId = a.getLong("m"),
                                fileId = a.getInt("f"),
                                fileName = a.optString("n"),
                                sizeBytes = a.optLong("s"),
                                date = a.optInt("d"),
                                kind = kind,
                                volume = a.optBoolean("vol"),
                            )
                        },
                        selfDestructing = g.optInt("sd"),
                        scrollIndex = g.optInt("si"),
                        scrollOffset = g.optInt("so"),
                    )
                    SavedGrid(g.getLong("chat"), g.optInt("topic"), grid, g.optLong("saved"))
                }.getOrNull()
            }
        }

        private fun objects(list: JSONArray?): List<JSONObject> =
            if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }
}
