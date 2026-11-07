package com.tmplayer.data

/**
 * Which cover pictures are an uploader's advert rather than the video's own.
 *
 * A channel's logo or a site's address is stamped on the Telegram thumbnail of every file it
 * posts, so a chat of unrelated videos shows one picture over and over and reads as a wall of
 * ads that people take for wrong posters. The tell is the picture's inline preview
 * ([MediaItem.miniThumbnail]): Telegram's tiny JPEG, the same bytes for the same cover. A cover
 * shared by [MIN_FILES] or more files of one chat whose titles are not all one title is flagged;
 * so is one shared by [MIN_CHATS] chats, with more than one title among them.
 *
 * The episodes of one show with one cover are not flagged, nor are the 720p and the 1080p of one
 * film: the files then share a title, and a title is all they share. Two files are below the
 * threshold, however alike they look.
 *
 * Pure and one pass over the list: a hash of each preview (read once per item, see
 * [MediaItem.miniKey]) and a small tally per (chat, picture), so a chat of 3,000 videos costs a
 * few milliseconds and memory in proportion to the pictures it really has.
 */
class PromoCovers private constructor(private val keys: Set<Long>) {

    /** True when [item]'s own thumbnail is flagged. */
    fun isPromo(item: MediaItem): Boolean = keys.isNotEmpty() && item.miniKey.let { it != 0L && it in keys }

    /** True when a preview with exactly these [bytes] is flagged. */
    fun isPromo(bytes: ByteArray?): Boolean =
        keys.isNotEmpty() && bytes != null && bytes.isNotEmpty() && keyOf(bytes) in keys

    /** The flagged pictures' keys, for a caller that keeps them beyond one list. */
    val flagged: Set<Long> get() = keys

    val isEmpty: Boolean get() = keys.isEmpty()

    companion object {
        /** Files of one chat sharing a cover before it counts as an advert. */
        const val MIN_FILES = 3

        /** Chats sharing a cover before it counts as an advert, whichever chat each file is in. */
        const val MIN_CHATS = 3

        val None = PromoCovers(emptySet())

        fun of(keys: Set<Long>): PromoCovers = if (keys.isEmpty()) None else PromoCovers(keys)

        /** 64-bit FNV-1a of a preview's bytes; never 0, which stands for no preview. */
        fun keyOf(bytes: ByteArray): Long {
            var hash = -3750763034362895579L
            for (byte in bytes) hash = (hash xor (byte.toLong() and 0xFF)) * 1099511628211L
            hash = hash xor bytes.size.toLong()
            return if (hash == 0L) 1L else hash
        }

        /** The flagged covers among [items], which may come from one chat or several. */
        fun of(items: Collection<MediaItem>): PromoCovers {
            if (items.size < MIN_FILES) return None
            val perChat = HashMap<ChatCover, Tally>()
            val perCover = HashMap<Long, Tally>()
            val flagged = HashSet<Long>()
            for (item in items) {
                val key = item.miniKey
                if (key == 0L || key in flagged) continue
                // A name with no title ("video_17.mp4") says nothing about whether two files are
                // the same film, so it counts for nothing either way.
                val title = item.parsedName.titleKey.ifEmpty { continue }
                val here = perChat.getOrPut(ChatCover(item.chatId, key)) { Tally() }
                here.add(title, item.chatId)
                if (here.files >= MIN_FILES && here.varied) {
                    flagged += key
                    continue
                }
                val all = perCover.getOrPut(key) { Tally() }
                all.add(title, item.chatId)
                if (all.chats >= MIN_CHATS && all.varied) flagged += key
            }
            return of(flagged)
        }
    }

    private data class ChatCover(val chatId: Long, val key: Long)

    /** What one picture has been seen on: files, whether under two titles, and chats (counted up to the threshold). */
    private class Tally {
        var files = 0
        var varied = false
        var chats = 0
        private var title: String? = null
        private var chat = 0L
        private var others: HashSet<Long>? = null

        fun add(titleKey: String, chatId: Long) {
            files++
            val first = title
            if (first == null) title = titleKey else if (first != titleKey) varied = true
            if (chats == 0) {
                chat = chatId
                chats = 1
            } else if (chats < MIN_CHATS && chatId != chat) {
                val set = others ?: HashSet<Long>().also { others = it }
                if (set.add(chatId)) chats = set.size + 1
            }
        }
    }
}
