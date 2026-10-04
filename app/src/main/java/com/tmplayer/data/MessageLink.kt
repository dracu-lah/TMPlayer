package com.tmplayer.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * The link to a video's message in Telegram, put on the clipboard.
 *
 * The desktop's Copy link, on the phone: the tile menu and the player's overflow both offer it.
 * TDLib builds the link, the same `getMessageLink` call the desktop makes, so a public channel
 * gives a `t.me/name/123` anyone can open and a private chat gives the `t.me/c/...` form only its
 * members can. A chat with neither (a secret chat, a private conversation) gives no link, and the
 * toast says so rather than putting nothing on the clipboard in silence.
 */
object MessageLink {

    /** The link, or null when this chat has none to give. */
    suspend fun of(chatId: Long, messageId: Long): String? = runCatching {
        Td.client.getMessageLink(chatId, messageId, 0, 0, "", false, false).valueOrNull?.link
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Fetches the link and copies it, with a toast either way. Call from the main thread. */
    suspend fun copy(context: Context, chatId: Long, messageId: Long) {
        val link = of(chatId, messageId)
        if (link == null) {
            Toast.makeText(context, "This chat has no links to its messages", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("Telegram link", link)) }
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }
}
