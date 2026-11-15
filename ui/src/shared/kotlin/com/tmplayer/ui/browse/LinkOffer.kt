package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What Search offers for a link typed or pasted into it (CP79): "Open this post" for a post link
 * and "Preview and join" for an invite. Pressed, it goes to [LinkRequests].
 *
 * Search no longer looks a public @username up on Telegram, nor offers to join the chat found
 * (HX62): it reads the viewer's own chats and their videos, and a chat that is not among them is
 * not reached from here. A t.me link to a public chat typed or opened elsewhere still goes through
 * [LinkFlow], which is the viewer asking for that one chat by name.
 */
data class LinkOffer(val label: String, val detail: String, val link: com.tmplayer.data.TgLink)

@Composable
fun rememberLinkOffer(query: String): LinkOffer? {
    val link = remember(query) { com.tmplayer.data.TelegramLinks.parse(query) }
    val strings = com.tmplayer.ui.i18n.LocalStrings.current
    return when (link) {
        is com.tmplayer.data.TgLink.Post -> LinkOffer(strings.linkOpenPost, strings.linkOpenPostDetail, link)
        is com.tmplayer.data.TgLink.Invite -> LinkOffer(strings.linkPreviewJoin, strings.linkPreviewJoinDetail, link)
        else -> null
    }
}
