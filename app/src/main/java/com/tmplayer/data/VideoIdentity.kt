package com.tmplayer.data

/** A Telegram message is unique only inside its account and chat. */
data class VideoIdentity(val accountId: Long, val chatId: Long, val messageId: Long) {
    init {
        require(accountId > 0L)
        require(chatId != 0L)
        require(messageId != 0L)
    }

    val storageSuffix: String get() = "${accountId}_${chatId}_$messageId"

    companion object {
        fun decode(value: String): VideoIdentity? {
            val parts = value.split('_')
            if (parts.size != 3) return null
            return runCatching {
                VideoIdentity(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
            }.getOrNull()
        }
    }
}
