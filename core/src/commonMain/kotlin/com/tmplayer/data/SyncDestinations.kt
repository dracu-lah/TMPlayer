package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatMemberStatus
import dev.g000sha256.tdl.dto.ChatMemberStatusAdministrator
import dev.g000sha256.tdl.dto.ChatMemberStatusCreator
import dev.g000sha256.tdl.dto.ChatMemberStatusMember
import dev.g000sha256.tdl.dto.ChatMemberStatusRestricted
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HX87: the viewer may keep the sync file ([ResumeSync]) and the settings backup
 * ([SavedMessagesBackup]) in a private channel of their own instead of in Saved Messages, which
 * stays the default. The channel is used only while it is certain that nobody else can read what
 * is written there: this account owns it, it has no public name, it is a channel (not a group),
 * Telegram lets the account post, and its member count is exactly 1. Anything else, and a count
 * Telegram has not given (0), means no write, a plain reason ([SyncChannelBlock]) and the file
 * stays in Saved Messages meanwhile.
 *
 * The decision is [SyncChannelCheck.judge], pure; the facts come from a [ChannelProbe] (TDLib in
 * the app, a fake in tests); [resolve] ties the two to the viewer's saved choice.
 */

/** The chosen channel: its chat id, its title as last seen, and the account that chose it. */
data class SyncChannelChoice(val chatId: Long, val title: String, val account: Long)

/** Why the chosen channel is not used right now; the sync file stays in Saved Messages. */
sealed class SyncChannelBlock(val code: String) {
    /** Telegram has not said how many people are in it (0), so "only you" is not verified. */
    data object CountUnknown : SyncChannelBlock("unknown")

    /** [count] members: a second person joined, or was there from the start. */
    data class MoreMembers(val count: Int) : SyncChannelBlock("members")

    /** Deleted, or this account was removed from it. */
    data object Gone : SyncChannelBlock("gone")

    /** A group or a chat, not a channel. */
    data object NotAChannel : SyncChannelBlock("group")

    /** It has a public name (a t.me link), so anybody can join. */
    data object Public : SyncChannelBlock("public")

    /**
     * A discussion group is linked to it: everyone in that group can read the channel's posts, so
     * a member count of 1 on the channel does not mean only the viewer can read the file.
     */
    data object LinkedGroup : SyncChannelBlock("linked")

    /** This account is not its owner (an admin or a member). */
    data object NotOwner : SyncChannelBlock("notowner")

    /** The owner left the channel. */
    data object OwnerLeft : SyncChannelBlock("left")

    /** Telegram does not let this account post: the channel is restricted, or a write was refused at [at]. */
    data class PostingBlocked(val at: Long = 0L) : SyncChannelBlock("posting")

    fun encode(): String = when (this) {
        is MoreMembers -> "$code:$count"
        is PostingBlocked -> "$code:$at"
        else -> code
    }

    /** The reason in plain words, ending with where the file is meanwhile. */
    fun words(): String = when (this) {
        CountUnknown -> L.syncchannelBlockCountUnknown
        is MoreMembers -> L.syncchannelBlockMembers(count)
        Gone -> L.syncchannelBlockGone
        NotAChannel -> L.syncchannelBlockGroup
        Public -> L.syncchannelBlockPublic
        LinkedGroup -> L.syncchannelBlockLinked
        NotOwner -> L.syncchannelBlockNotOwner
        OwnerLeft -> L.syncchannelBlockLeft
        is PostingBlocked -> L.syncchannelBlockPosting
    }

    companion object {
        /** A stored line back into a block; a line this build cannot read is no block. */
        fun decode(text: String?): SyncChannelBlock? {
            if (text.isNullOrBlank()) return null
            val name = text.substringBefore(':')
            val arg = text.substringAfter(':', "")
            return when (name) {
                "unknown" -> CountUnknown
                "members" -> MoreMembers(arg.toIntOrNull()?.coerceIn(2, MAX_COUNT) ?: return null)
                "gone" -> Gone
                "group" -> NotAChannel
                "public" -> Public
                "linked" -> LinkedGroup
                "notowner" -> NotOwner
                "left" -> OwnerLeft
                "posting" -> PostingBlocked(arg.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                else -> null
            }
        }

        private const val MAX_COUNT = 100_000_000
    }
}

/** What Telegram says about one channel, as far as writing there is concerned. */
data class ChannelFacts(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean,
    val hasPublicName: Boolean,
    /** A discussion group is linked to the channel (its posts are readable there). */
    val hasLinkedChat: Boolean = false,
    /** A forum or a broadcast group: never a place for the file. */
    val isForum: Boolean = false,
    val role: Role,
    /** Telegram restricted the channel itself (a restriction reason is set). */
    val restricted: Boolean,
    /** The larger of the two counts Telegram gives; 0 when it gives none. */
    val memberCount: Int,
) {
    enum class Role { Owner, OwnerLeft, Admin, Member, Outside }
}

/** The answer of a [ChannelProbe]. */
sealed interface ProbeAnswer {
    data class Facts(val facts: ChannelFacts) : ProbeAnswer

    /** Telegram says there is no such chat for this account. */
    data object Gone : ProbeAnswer

    /** Not answered (offline, a wait, an error): nothing is decided from it. */
    data class Unreachable(val reason: String) : ProbeAnswer
}

/** Where the facts come from: [TdChannelProbe] in the app, a fake in tests. */
fun interface ChannelProbe {
    suspend fun facts(chatId: Long): ProbeAnswer
}

/** The part of the supergroup's full info the check needs; null when it could not be fetched. */
data class FullInfoFacts(val memberCount: Int, val linkedChatId: Long)

/** Joins what TDLib gives into one [ProbeAnswer]; pure, so a failed fetch is a tested case. */
object ChannelFactsAssembler {

    /**
     * Fails closed: without the full info (the fetch failed) nothing is known about the linked
     * group or the real member count, so the answer is [ProbeAnswer.Unreachable] and nothing is
     * written until it is known. The count is the larger of the two Telegram gives.
     */
    fun assemble(
        chatId: Long,
        title: String,
        isChannel: Boolean,
        isForum: Boolean,
        hasPublicName: Boolean,
        role: ChannelFacts.Role,
        restricted: Boolean,
        groupMemberCount: Int,
        groupHasLinkedChat: Boolean,
        full: FullInfoFacts?,
        failure: String,
    ): ProbeAnswer {
        if (full == null) return ProbeAnswer.Unreachable(failure)
        return ProbeAnswer.Facts(
            ChannelFacts(
                chatId = chatId,
                title = title,
                isChannel = isChannel,
                hasPublicName = hasPublicName,
                hasLinkedChat = groupHasLinkedChat || full.linkedChatId != 0L,
                isForum = isForum,
                role = role,
                restricted = restricted,
                memberCount = maxOf(groupMemberCount, full.memberCount).coerceAtLeast(0),
            ),
        )
    }
}

/** What [SyncChannelCheck.judge] decides. */
sealed interface SyncJudgement {
    /** Safe to read and write: only the viewer is in it. */
    data class Use(val facts: ChannelFacts) : SyncJudgement

    data class Blocked(val block: SyncChannelBlock) : SyncJudgement

    /** Could not be checked now: no write, no fallback decision. */
    data class Retry(val reason: String) : SyncJudgement
}

object SyncChannelCheck {

    /** The one rule: a channel is used only while it is certain that it is the viewer's alone. */
    fun judge(answer: ProbeAnswer): SyncJudgement = when (answer) {
        ProbeAnswer.Gone -> SyncJudgement.Blocked(SyncChannelBlock.Gone)
        is ProbeAnswer.Unreachable -> SyncJudgement.Retry(answer.reason)
        is ProbeAnswer.Facts -> {
            val f = answer.facts
            val block: SyncChannelBlock? = when {
                !f.isChannel || f.isForum -> SyncChannelBlock.NotAChannel
                f.role == ChannelFacts.Role.Outside -> SyncChannelBlock.Gone
                f.role == ChannelFacts.Role.OwnerLeft -> SyncChannelBlock.OwnerLeft
                f.role != ChannelFacts.Role.Owner -> SyncChannelBlock.NotOwner
                f.hasPublicName -> SyncChannelBlock.Public
                f.hasLinkedChat -> SyncChannelBlock.LinkedGroup
                f.restricted -> SyncChannelBlock.PostingBlocked()
                f.memberCount <= 0 -> SyncChannelBlock.CountUnknown
                f.memberCount >= 2 -> SyncChannelBlock.MoreMembers(f.memberCount)
                else -> null
            }
            if (block == null) SyncJudgement.Use(f) else SyncJudgement.Blocked(block)
        }
    }

    /** Whether a failed send says "this account may not post here" (as against an outage or a wait). */
    fun refusedPosting(message: String?): Boolean {
        val raw = message?.trim().orEmpty()
        return POSTING_REFUSED.any { raw.contains(it, ignoreCase = true) }
    }

    private val POSTING_REFUSED = listOf(
        "CHAT_WRITE_FORBIDDEN",
        "CHAT_ADMIN_REQUIRED",
        "USER_BANNED_IN_CHANNEL",
        "CHAT_RESTRICTED",
        "CHANNEL_PRIVATE",
        "USER_RESTRICTED",
        "CHAT_SEND_PLAIN_FORBIDDEN",
        "CHAT_SEND_DOCS_FORBIDDEN",
        "Have no rights to send", // i18n-ok: TDLib text, matched
        "not enough rights", // i18n-ok: TDLib text, matched
    )
}

/** Where a sync or a backup goes this time. */
sealed interface SyncPlace {
    val chatId: Long

    /** Saved Messages; [why] is the reason the chosen channel is not used, when one is chosen. */
    data class SavedMessages(override val chatId: Long, val why: SyncChannelBlock? = null) : SyncPlace

    data class Channel(override val chatId: Long, val title: String) : SyncPlace

    /** The chosen channel could not be checked now: nothing is written anywhere this time. */
    data class Unreachable(val reason: String) : SyncPlace {
        override val chatId: Long get() = 0L
    }
}

/** A channel the viewer owns that may do; checked again by [SyncDestinations.choose] when picked. */
data class ChannelOption(val chatId: Long, val title: String)

/** What choosing or creating a channel came to. */
sealed interface ChooseResult {
    /** [created] is false when an existing one was taken instead of making another. */
    data class Picked(val title: String, val created: Boolean) : ChooseResult

    data class Refused(val block: SyncChannelBlock) : ChooseResult

    /** Made, but Telegram has not confirmed that only the viewer is in it yet; not chosen. */
    data class CreatedUnverified(val title: String) : ChooseResult

    data class Failed(val reason: String) : ChooseResult
}

object SyncDestinations {

    private val createLock = Mutex()

    /** A write refused for posting is not tried again on its own for this long. */
    const val POSTING_RETRY_MS = 60 * 60 * 1000L

    /** The name the guide gives the channel it makes, and what it is found by on a second device. */
    const val CHANNEL_NAME = "TMPlayer sync"

    /**
     * Where this sync or backup goes. No channel chosen: Saved Messages ([savedChat]). A channel
     * chosen by another account is forgotten (sign out and in as someone else). A channel that is
     * not provably the viewer's alone: Saved Messages, with the reason kept for the Settings page.
     * A channel that could not be checked: [SyncPlace.Unreachable], nothing written.
     */
    suspend fun resolve(
        settings: SettingsStore,
        myId: Long,
        savedChat: Long,
        probe: ChannelProbe,
        now: Long = System.currentTimeMillis(),
    ): SyncPlace {
        val choice = settings.syncChannelNow() ?: return SyncPlace.SavedMessages(savedChat)
        if (choice.account != myId) {
            settings.setSyncChannel(null)
            return SyncPlace.SavedMessages(savedChat)
        }
        val stored = settings.syncChannelBlock.first()
        return when (val judged = SyncChannelCheck.judge(probe.facts(choice.chatId))) {
            is SyncJudgement.Use -> {
                if (stored is SyncChannelBlock.PostingBlocked && stored.at > 0 && now - stored.at in 0 until POSTING_RETRY_MS) {
                    return SyncPlace.SavedMessages(savedChat, stored)
                }
                if (stored != null) settings.setSyncChannelBlock(null)
                if (judged.facts.title.isNotBlank() && judged.facts.title != choice.title) {
                    settings.setSyncChannel(choice.copy(title = judged.facts.title))
                }
                SyncPlace.Channel(choice.chatId, judged.facts.title.ifBlank { choice.title })
            }
            is SyncJudgement.Blocked -> {
                if (stored != judged.block) settings.setSyncChannelBlock(judged.block)
                SyncPlace.SavedMessages(savedChat, judged.block)
            }
            is SyncJudgement.Retry -> SyncPlace.Unreachable(judged.reason)
        }
    }

    /**
     * For the backup before sign out: the channel when [resolve] finds it safe, else Saved Messages,
     * never a stop. The check at the moment of the write is [backupChatBeforeWrite], run by the
     * sender right before the post (a second probe straight after [resolve] would add nothing).
     */
    suspend fun chatForBackup(settings: SettingsStore?, myId: Long, savedChat: Long, probe: ChannelProbe): Long {
        if (settings == null) return savedChat
        return when (val place = resolve(settings, myId, savedChat, probe)) {
            is SyncPlace.Channel -> place.chatId
            is SyncPlace.SavedMessages -> place.chatId
            is SyncPlace.Unreachable -> savedChat
        }
    }

    /**
     * The backup's last check, right before the post: reads the channel's facts anew from the
     * probe (nothing from [resolve] is reused), so somebody who joined while the file was being
     * made is caught. The channel when still the viewer's alone, else [savedChat].
     */
    suspend fun backupChatBeforeWrite(settings: SettingsStore, probe: ChannelProbe, chat: Long, savedChat: Long): Long =
        if (chat == savedChat || verifyBeforeWrite(settings, probe, chat) == null) chat else savedChat
    /**
     * The check again, right before a write: the sync's reads and merge take time, and somebody
     * can join in between. Null when the channel is still the viewer's alone; else the words for
     * the viewer, with nothing written (a block is kept for the Settings page).
     */
    suspend fun verifyBeforeWrite(settings: SettingsStore, probe: ChannelProbe, chatId: Long): String? =
        when (val judged = SyncChannelCheck.judge(probe.facts(chatId))) {
            is SyncJudgement.Use -> null
            is SyncJudgement.Blocked -> {
                settings.setSyncChannelBlock(judged.block)
                judged.block.words()
            }
            is SyncJudgement.Retry -> judged.reason
        }

    /** A write to the channel was refused: Saved Messages for the next hour, the reason on the page. */
    suspend fun notePostingRefused(settings: SettingsStore, now: Long = System.currentTimeMillis()) {
        settings.setSyncChannelBlock(SyncChannelBlock.PostingBlocked(now))
    }

    /**
     * Moves the destination in the settings: the remembered file message is dropped (it belongs to
     * the old place), and where an older copy was left is noted for the Settings page. The old
     * copy is never deleted here. Called with the sync lock held ([ResumeSync.changeDestination]).
     */
    internal suspend fun applyChoice(settings: SettingsStore, choice: SyncChannelChoice?): String? {
        val old = settings.syncChannelNow()
        if (old?.chatId == choice?.chatId) return null
        val hadFile = settings.resumeSyncFileNow() != 0L
        val oldName = old?.title?.ifBlank { null } ?: if (old != null) L.syncchannelThisChannel else L.syncchannelSavedMessages
        val newName = choice?.title?.ifBlank { null } ?: if (choice != null) L.syncchannelThisChannel else L.syncchannelSavedMessages
        settings.setSyncChannel(choice)
        settings.setResumeSyncFile(0L)
        settings.setSyncFileChat(0L)
        // The note names the place the file was just moved away from; writing in a place again makes
        // a note about it stale.
        if (hadFile) settings.setSyncOldCopy(oldName) else if (settings.syncOldCopy.first() == newName) settings.setSyncOldCopy("")
        return if (hadFile) oldName else null
    }

    // ---- choosing and creating (Settings) ---------------------------------------------------------

    /**
     * Private channels this account owns, from the first chats of the list and the archive: no
     * public name, the viewer's own. Who else is in them is checked when one is picked
     * ([choose]), not here, so the list costs no call to the server.
     */
    suspend fun candidates(td: TdlClient): List<ChannelOption> {
        val found = LinkedHashMap<Long, ChannelOption>()
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            val ids = td.getChats(list, SCAN_LIMIT).valueOrNull?.chatIds ?: continue
            for (id in ids) {
                if (found.size >= MAX_OPTIONS) break
                val chat = td.getChat(id).valueOrNull ?: continue
                val type = chat.type as? ChatTypeSupergroup ?: continue
                if (!type.isChannel) continue
                val group = td.getSupergroup(type.supergroupId).valueOrNull ?: continue
                val status = group.status
                if (status !is ChatMemberStatusCreator || !status.isMember) continue
                if (group.usernames?.activeUsernames?.isNotEmpty() == true || !group.usernames?.editableUsername.isNullOrBlank()) continue
                // A linked discussion group can read the posts, a forum is not a channel: never offered.
                if (group.hasLinkedChat || group.isForum || group.isBroadcastGroup) continue
                found[chat.id] = ChannelOption(chat.id, chat.title)
            }
        }
        return found.values.toList()
    }

    /**
     * Picks [chatId] for the sync file and backups, after checking that only the viewer is in it.
     * Not chosen when the check says otherwise, with the reason.
     */
    suspend fun choose(
        settings: SettingsStore,
        probe: ChannelProbe,
        myId: Long,
        chatId: Long,
        created: Boolean = false,
    ): ChooseResult = when (val judged = SyncChannelCheck.judge(probe.facts(chatId))) {
        is SyncJudgement.Use -> {
            val choice = SyncChannelChoice(chatId, judged.facts.title, myId)
            ResumeSync.changeDestination(settings, choice)
            ChooseResult.Picked(judged.facts.title, created)
        }
        is SyncJudgement.Blocked -> ChooseResult.Refused(judged.block)
        is SyncJudgement.Retry -> ChooseResult.Failed(judged.reason)
    }

    /**
     * "Create it for me": a private channel with only the viewer in it, chosen for the sync file.
     * Pressed twice, or on a second device, it takes the channel already made (found by its name,
     * and checked like any pick) instead of making another. The one call that makes something in
     * Telegram, and only on this press ([TelegramWritesPolicyTest] lists it on purpose).
     */
    suspend fun create(settings: SettingsStore, td: TdlClient, myId: Long, name: String, description: String): ChooseResult =
        createLock.withLock {
            try {
                val probe = TdChannelProbe(td)
                for (option in candidates(td).filter { it.title == name }) {
                    val taken = choose(settings, probe, myId, option.chatId)
                    if (taken is ChooseResult.Picked) return@withLock taken
                }
                val made = waitingOutFlood(
                    3, 60,
                    { r: TdlResult<*> -> (r as? TdlResult.Failure)?.let { Failures.floodWaitSeconds(it.message) } },
                ) {
                    // Only for the profile that asked (HX88): stops if it is no longer open.
                    OwnedSessions.ensure()
                    td.createNewSupergroupChat(name, false, true, description, null, 0, false)
                }
                if (made !is TdlResult.Success) return@withLock ChooseResult.Failed(createFailure(made.errorMessage))
                val chat = made.result
                // The count of a channel made a moment ago can lag for a second: asked again once.
                var picked = choose(settings, probe, myId, chat.id, created = true)
                if (picked is ChooseResult.Refused && picked.block == SyncChannelBlock.CountUnknown) {
                    delay(RECHECK_MS)
                    picked = choose(settings, probe, myId, chat.id, created = true)
                }
                if (picked is ChooseResult.Refused) ChooseResult.CreatedUnverified(chat.title) else picked
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Creating the sync channel failed", e)
                ChooseResult.Failed(Failures.humanise(e))
            }
        }

    /** The sentence for a failed create: Telegram's limit on owned channels in words, the rest as usual. */
    fun createFailure(message: String?): String {
        val raw = message.orEmpty()
        return if (raw.contains("CHANNELS_TOO_MUCH") || raw.contains("CHANNELS_ADMIN")) L.syncchannelTooMany else Failures.humanise(message)
    }

    private const val SCAN_LIMIT = 200
    private const val MAX_OPTIONS = 30
    private const val RECHECK_MS = 1_500L
    private const val TAG = "SyncDestinations"
}

/** The facts from TDLib: the chat, its supergroup and the full info for the member count. */
class TdChannelProbe(private val td: TdlClient) : ChannelProbe {

    override suspend fun facts(chatId: Long): ProbeAnswer {
        try {
            var chat = td.getChat(chatId)
            if (chat is TdlResult.Failure && chat.message.contains("not found", ignoreCase = true)) {
                // After a restart the chat may not be loaded yet: the lists load it, then once more.
                td.getChats(ChatListMain(), LOAD_LIMIT)
                td.getChats(ChatListArchive(), LOAD_LIMIT)
                chat = td.getChat(chatId)
                if (chat is TdlResult.Failure && chat.message.contains("not found", ignoreCase = true)) return ProbeAnswer.Gone
            }
            val loaded = (chat as? TdlResult.Success)?.result
                ?: return ProbeAnswer.Unreachable(Failures.humanise((chat as TdlResult.Failure).message))
            val type = loaded.type as? ChatTypeSupergroup
                ?: return ProbeAnswer.Facts(
                    ChannelFacts(chatId, loaded.title, false, false, role = ChannelFacts.Role.Outside, restricted = false, memberCount = 0),
                )
            val group = td.getSupergroup(type.supergroupId)
            val supergroup = (group as? TdlResult.Success)?.result
                ?: return ProbeAnswer.Unreachable(Failures.humanise((group as TdlResult.Failure).message))
            val fullResult = td.getSupergroupFullInfo(type.supergroupId)
            val full = (fullResult as? TdlResult.Success)?.result?.let { FullInfoFacts(it.memberCount, it.linkedChatId) }
            val names = supergroup.usernames
            return ChannelFactsAssembler.assemble(
                chatId = chatId,
                title = loaded.title,
                isChannel = type.isChannel,
                isForum = supergroup.isForum || supergroup.isBroadcastGroup,
                hasPublicName = names?.activeUsernames?.isNotEmpty() == true || !names?.editableUsername.isNullOrBlank(),
                role = roleOf(supergroup.status),
                restricted = supergroup.restrictionInfo != null,
                groupMemberCount = supergroup.memberCount,
                groupHasLinkedChat = supergroup.hasLinkedChat,
                full = full,
                failure = Failures.humanise((fullResult as? TdlResult.Failure)?.message),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProbeAnswer.Unreachable(Failures.humanise(e))
        }
    }

    private fun roleOf(status: ChatMemberStatus?): ChannelFacts.Role = when (status) {
        is ChatMemberStatusCreator -> if (status.isMember) ChannelFacts.Role.Owner else ChannelFacts.Role.OwnerLeft
        is ChatMemberStatusAdministrator -> ChannelFacts.Role.Admin
        is ChatMemberStatusMember -> ChannelFacts.Role.Member
        is ChatMemberStatusRestricted -> if (status.isMember) ChannelFacts.Role.Member else ChannelFacts.Role.Outside
        else -> ChannelFacts.Role.Outside
    }

    private companion object {
        const val LOAD_LIMIT = 100
    }
}
