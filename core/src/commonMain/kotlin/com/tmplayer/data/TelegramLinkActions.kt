package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.InviteLinkChatTypeChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What opening a post link comes to (CP79). */
sealed interface PostOpen {
    /** The post is a video (or holds one): play it, from [startSec] when the link named a time. */
    data class Play(val item: MediaItem, val chat: ChatSummary?, val startSec: Int) : PostOpen

    /** The post is not a video, or could not be read: its chat opens instead, with [note] said. */
    data class OpenChat(val chat: ChatSummary, val note: String?) : PostOpen

    /** Nothing opens, for [reason]: not found, a private chat not joined, the kids lock. */
    data class Refused(val reason: String) : PostOpen
}

/** An invite link's chat as Telegram describes it before joining (checkChatInviteLink). */
data class InvitePreview(
    val link: TgLink.Invite,
    val title: String,
    val isChannel: Boolean,
    val photoFileId: Int,
    val bigPhotoFileId: Int,
    val miniThumbnail: ByteArray?,
    val memberCount: Int,
    val description: String,
    /** The admins approve each newcomer who joins through Telegram. */
    val createsJoinRequest: Boolean,
    /** A paid subscription: joining is left to Telegram, where the payment is. */
    val paid: Boolean,
    /** The chat, when the account is in it already (or may look in for a while); 0 otherwise. */
    val chatId: Long,
    val alreadyMember: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is InvitePreview && other.link == link && other.title == title &&
        other.chatId == chatId && other.alreadyMember == alreadyMember && other.memberCount == memberCount
    override fun hashCode(): Int = link.hashCode() * 31 + title.hashCode()
}

/**
 * The rules behind opening a link, kept apart from TDLib so they are tested once: which post
 * opens what, which errors mean what, and that the kids lock stops every one of them.
 *
 * Nothing here joins a chat on the viewer's account any more (HX30): a chat not already joined
 * is only ever previewed, and the viewer joins it themselves in Telegram if they want to.
 */
object LinkRules {

    /** Previewing a chat the account is not in is off while the kids lock is on. */
    fun mayJoin(kidsAllowed: Set<Long>?): Boolean = kidsAllowed == null

    /**
     * A resolved post: [chatId] 0 when Telegram would not say which chat (a private one the
     * account is not in), [video] the post's video if it has one, [chat] its chat as the list
     * draws it. [linkStart] is the link's own `t=`, [telegramStart] what TDLib read off it.
     */
    fun decide(
        kidsAllowed: Set<Long>?,
        chatId: Long,
        isPublic: Boolean,
        found: Boolean,
        video: MediaItem?,
        chat: ChatSummary?,
        linkStart: Int,
        telegramStart: Int,
    ): PostOpen {
        if (chatId == 0L) return PostOpen.Refused(if (isPublic) L.linkNotFound else L.linkPrivateNotMember)
        if (!KidsLock.allows(kidsAllowed, chatId)) return PostOpen.Refused(L.linkKidsLock)
        if (video != null) return PostOpen.Play(video, chat, (if (linkStart > 0) linkStart else telegramStart).coerceAtLeast(0))
        if (chat == null) return PostOpen.Refused(L.linkNotFound)
        return PostOpen.OpenChat(chat, if (found) L.linkNoVideo else L.linkPostGone)
    }

    /** The invite check's error, said: an expired or mistyped invite is the usual one. */
    fun inviteFailure(message: String?): String {
        val raw = message.orEmpty()
        return if (raw.contains("INVITE_HASH") || raw.contains("Wrong invite link") || raw.contains("400")) {
            L.linkInviteInvalid
        } else {
            Failures.humanise(raw)
        }
    }
}

/** Resolving links through TDLib, for the shared link flow on every device (HX30: never joins). */
class TelegramLinkActions(private val td: TdlClient) {

    /** Where [link] leads: a video to play, a chat to open, or why neither. */
    suspend fun openPost(link: TgLink.Post): PostOpen = withContext(Dispatchers.IO) {
        val allowed = ChatGate.awaitReady()
        val answer = td.getMessageLinkInfo(link.url)
        val info = when (answer) {
            is TdlResult.Success -> answer.result
            is TdlResult.Failure -> return@withContext PostOpen.Refused(
                if (answer.message.contains("CHANNEL_PRIVATE")) L.linkPrivateNotMember else Failures.humanise(answer.message).let {
                    if (answer.code == 400) L.linkNotFound else it
                },
            )
        }
        val chatId = info.chatId
        if (!KidsLock.allows(allowed, chatId)) return@withContext PostOpen.Refused(L.linkKidsLock)
        val chat = if (chatId != 0L) ChatRepository(td).summary(chatId) else null
        val message = info.message
        val chatProtected = if (chatId != 0L) td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false else false
        val video = message?.let { MediaMapper.fromMessage(it, chatProtected) }
        LinkRules.decide(
            kidsAllowed = allowed,
            chatId = chatId,
            isPublic = info.isPublic,
            found = message != null,
            video = video,
            chat = chat,
            linkStart = link.startSec,
            telegramStart = info.mediaTimestamp,
        )
    }

    /** The invite's chat, or why it cannot be shown (expired, wrong, the kids lock). */
    suspend fun previewInvite(link: TgLink.Invite): Result<InvitePreview> = withContext(Dispatchers.IO) {
        if (!LinkRules.mayJoin(ChatGate.awaitReady())) return@withContext Result.failure(LinkRefusal(L.linkKidsLock))
        when (val answer = td.checkChatInviteLink(link.url)) {
            is TdlResult.Failure -> Result.failure(LinkRefusal(LinkRules.inviteFailure(answer.message)))
            is TdlResult.Success -> {
                val info = answer.result
                Result.success(
                    InvitePreview(
                        link = link,
                        title = info.title,
                        isChannel = info.type is InviteLinkChatTypeChannel,
                        photoFileId = info.photo?.small?.id ?: 0,
                        bigPhotoFileId = info.photo?.big?.id ?: 0,
                        miniThumbnail = info.photo?.minithumbnail?.data,
                        memberCount = info.memberCount,
                        description = info.description.trim(),
                        createsJoinRequest = info.createsJoinRequest,
                        paid = info.subscriptionInfo != null,
                        chatId = info.chatId,
                        alreadyMember = info.chatId != 0L && info.accessibleFor == 0,
                    ),
                )
            }
        }
    }
}

/** A refusal already worded for the viewer. */
class LinkRefusal(override val message: String) : Exception(message)
