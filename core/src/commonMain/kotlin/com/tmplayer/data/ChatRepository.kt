package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatNotificationSettings
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.MessageTopicForum
import dev.g000sha256.tdl.dto.SearchMessagesChatTypeFilterChannel
import dev.g000sha256.tdl.dto.SearchMessagesChatTypeFilterGroup
import dev.g000sha256.tdl.dto.SearchMessagesChatTypeFilterPrivate
import dev.g000sha256.tdl.dto.SearchMessagesFilterAnimation
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class ChatRepository(private val td: TdlClient) {

    /**
     * TDLib serves chats out of its local database, so the list has to be pulled from the
     * server first. `loadChats` returns 404 once everything is already local, which is the
     * documented "no more" answer, not a failure (see [ChatListLoad.sync]).
     *
     * @param known rows the caller already has, so this does not fetch them a second time. A cold
     *   start reads the local list and then syncs, and re-fetching what it was just handed costs
     *   hundreds of round trips and hundreds of whole TDLib chat objects on a device with 96 MB of
     *   heap. Anything genuinely new is still fetched, and rows on screen are kept current by the
     *   update collectors rather than by re-reading them.
     */
    suspend fun syncChats(
        limit: Int = CHAT_LIMIT,
        known: List<ChatSummary> = emptyList(),
    ): ChatSync = withContext(Dispatchers.IO) { ChatListLoad.sync(listCalls, limit, known, ARCHIVE_LIMIT) }

    /**
     * Reads the chat list already in TDLib's database without requiring a network connection
     * (see [ChatListLoad.cached]).
     */
    suspend fun cachedChats(
        limit: Int = CHAT_LIMIT,
        known: List<ChatSummary> = emptyList(),
    ): List<ChatSummary> = withContext(Dispatchers.IO) { ChatListLoad.cached(listCalls, limit, known, ARCHIVE_LIMIT) }

    /** Which chats are in which folder ([ChatListLoad.membership]): read behind the list, never ahead of it. */
    suspend fun folderMembership(): Map<Long, List<Int>> =
        withContext(Dispatchers.IO) { ChatListLoad.membership(listCalls, CHAT_LIMIT) }

    /** The TDLib calls [ChatListLoad] reads the list with. */
    private val listCalls = object : ChatListCalls {
        private fun ChatListRef.td(): dev.g000sha256.tdl.dto.ChatList = when (this) {
            ChatListRef.Main -> ChatListMain()
            ChatListRef.Archive -> ChatListArchive()
            is ChatListRef.Folder -> ChatListFolder(id)
        }

        override suspend fun loadChats(list: ChatListRef, limit: Int): ChatLoad {
            val result = td.loadChats(list.td(), limit)
            if (result !is TdlResult.Failure) return ChatLoad.Loaded
            // The 404 is in the code: the message is "Not Found".
            return if (isAllLoaded(result.code, result.message)) ChatLoad.AllLoaded else ChatLoad.Failed(result.message)
        }

        override suspend fun chatIds(list: ChatListRef, limit: Int): List<Long> =
            td.getChats(list.td(), limit).value().chatIds.toList()

        override suspend fun summary(chatId: Long, myId: Long): ChatSummary? =
            td.getChat(chatId).valueOrNull?.let { summarise(it, myId) }

        override suspend fun myId(): Long = Td.myId()

        override fun folders(): List<Int> = Td.folders.value.map { it.id }
    }

    private fun summarise(chat: Chat, myId: Long): ChatSummary {
        val saved = myId != 0L && chat.id == myId
        // Whichever list this chat is actually in. A chat has at most one of the two, and asking
        // about the wrong one is how a pinned archived chat loses its pin on the way here.
        val position = chat.positions.firstOrNull {
            it.list is ChatListMain || it.list is ChatListArchive
        }
        return ChatSummary(
            id = chat.id,
            title = if (saved) L.chatsSavedMessages else chat.title.ifBlank { L.chatsUntitled(chat.id.toString()) },
            miniThumbnail = chat.photo?.minithumbnail?.data,
            photoFileId = chat.photo?.small?.id ?: 0,
            kind = if (saved) ChatKind.Saved else kindOf(chat.type),
            isPinned = position?.isPinned == true,
            isArchived = chat.positions.any { it.list is ChatListArchive },
            unreadCount = chat.unreadCount,
            // muteFor is a count of seconds still to run, so anything above zero is muted now.
            // useDefaultMuteFor means the account-wide setting decides, and this app has no way to
            // change that setting, so a chat that has never been muted individually reads as unmuted.
            isMuted = chat.notificationSettings?.let {
                !it.useDefaultMuteFor && it.muteFor > 0
            } == true,
            // Folders are filled in by the caller, from Telegram's own per folder answer.
        )
    }

    /**
     * The ids TDLib currently holds, in its own order, and nothing else.
     *
     * The order is the point: TDLib sorts the main list by position itself, so a caller that
     * already has the rows can put them in today's order for one round trip, without the
     * `getChat` fan-out that makes [cachedChats] the expensive half of a sync.
     */
    suspend fun chatOrder(limit: Int = CHAT_LIMIT): List<Long> = withContext(Dispatchers.IO) {
        val main = td.getChats(ChatListMain(), limit).valueOrNull?.chatIds?.toList() ?: return@withContext emptyList()
        // The archive belongs in the answer even though nothing reorders within it. The caller
        // matches this against every row it holds and gives up if any of them is missing, so a
        // main-list-only answer would silently stop the whole screen from ever reordering.
        val archived = td.getChats(ChatListArchive(), ARCHIVE_LIMIT).valueOrNull?.chatIds?.toList()
            .orEmpty()
        (main + archived).distinct()
    }

    /**
     * Next page of playable media in [chatId], newest first.
     *
     * The three searches run concurrently and are merged by message id, which is monotonic
     * per chat, so a straight descending sort restores true chronological order.
     */
    suspend fun mediaPage(
        chatId: Long,
        cursors: MediaCursors = MediaCursors(),
        query: String = "",
        /** Messages asked for per search. Home's rows want a handful, the chat grid a full page. */
        pageSize: Int = PAGE_SIZE,
        /** A forum topic to keep to, or 0 for the whole chat. */
        topicId: Int = 0,
    ): MediaPage =
        // Off Main for the whole page. The mapper asks the filesystem twice per message to decide
        // whether the file is already on disk, and this runs three searches of forty messages each
        // inside a loop that may do it eight times: nearly two thousand syscalls, none of which
        // belong on the thread drawing the grid.
        withContext(Dispatchers.IO) {
        coroutineScope {
            // TDLib answers getChat from memory for any chat it has listed, so this is no round trip.
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            val videos = async {
                if (cursors.videoDone) null
                else attempt { search(chatId, cursors.video, SearchMessagesFilterVideo(), query, chatProtected, pageSize, topicId) }
            }
            val documents = async {
                if (cursors.documentDone) null
                else attempt { search(chatId, cursors.document, SearchMessagesFilterDocument(), query, chatProtected, pageSize, topicId) }
            }
            val animations = async {
                if (cursors.animationDone) null
                else attempt { search(chatId, cursors.animation, SearchMessagesFilterAnimation(), query, chatProtected, pageSize, topicId) }
            }

            val attempts = listOf(videos.await(), documents.await(), animations.await())
            // One of the three failing is not the page failing: whatever the others found is still
            // shown, and the failed one keeps its cursor to be asked again on the next page. Only
            // when every search asked for failed is it the chat that failed, and that is thrown.
            val asked = attempts.filterNotNull()
            if (asked.isNotEmpty() && asked.all { it.isFailure }) throw asked.first().exceptionOrNull()!!
            val (videoResult, documentResult, animationResult) = attempts.map { it?.getOrNull() }

            val items = buildList {
                videoResult?.let { addAll(it.items) }
                documentResult?.let { addAll(it.items) }
                animationResult?.let { addAll(it.items) }
            }.distinctBy { it.messageId }.sortedByDescending { it.messageId }
                // An album's videos without a caption of their own read as the album's.
                .let(MediaMapper::shareAlbumCaptions)

            val next = cursors.copy(
                video = videoResult?.next ?: cursors.video,
                document = documentResult?.next ?: cursors.document,
                animation = animationResult?.next ?: cursors.animation,
                videoDone = cursors.videoDone || videoResult?.done ?: (attempts[0] == null),
                documentDone = cursors.documentDone || documentResult?.done ?: (attempts[1] == null),
                animationDone = cursors.animationDone || animationResult?.done ?: (attempts[2] == null),
            )
            val hidden = listOfNotNull(videoResult, documentResult, animationResult).sumOf { it.selfDestructing }
            // "More like this" offers only titles the viewer has; this is how it knows them.
            com.tmplayer.online.KnownMedia.note(items)
            MediaPage(items, next, next.allDone, hidden, documentResult?.archives.orEmpty())
        }
        }

    /**
     * The topics of [chatId] when it is a group with Telegram's topics turned on, in Telegram's own
     * order (pinned first, then by latest post), or an empty list for any other chat.
     *
     * A list of topics is all Telegram gives: whether one holds any videos is asked of each in
     * turn, two counts apiece, a few at a time. Those are server calls, so this is for the moment
     * a group is opened, not for every refresh. A count that fails leaves its topic in: offering a
     * topic that turns out empty is a smaller harm than hiding the one the viewer came for.
     */
    suspend fun forumTopics(chatId: Long, countVideos: Boolean = true): List<ForumTopicSummary> = withContext(Dispatchers.IO) {
        val chat = td.getChat(chatId).valueOrNull ?: return@withContext emptyList()
        val type = chat.type as? ChatTypeSupergroup ?: return@withContext emptyList()
        if (type.isChannel) return@withContext emptyList()
        val group = td.getSupergroup(type.supergroupId).valueOrNull ?: return@withContext emptyList()
        if (!group.isForum) return@withContext emptyList()

        val topics = mutableListOf<dev.g000sha256.tdl.dto.ForumTopic>()
        var offsetDate = 0
        var offsetMessage = 0L
        var offsetTopic = 0
        while (topics.size < TOPIC_LIMIT) {
            val page = td.getForumTopics(chatId, "", offsetDate, offsetMessage, offsetTopic, TOPIC_PAGE)
                .valueOrNull ?: break
            topics += page.topics
            if (page.topics.isEmpty() || (page.nextOffsetDate == 0 && page.nextOffsetMessageId == 0L &&
                    page.nextOffsetForumTopicId == 0)
            ) break
            offsetDate = page.nextOffsetDate
            offsetMessage = page.nextOffsetMessageId
            offsetTopic = page.nextOffsetForumTopicId
        }

        coroutineScope {
            topics.distinctBy { it.info.forumTopicId }.chunked(COUNT_CHUNK).flatMap { chunk ->
                chunk.map { topic ->
                    async {
                        val id = topic.info.forumTopicId
                        val known = if (countVideos) countedTopics["$chatId:$id"]?.takeIf { now() - it.first < COUNT_TTL_MS }?.second else true
                        val hasVideos = known ?: run {
                            // TDLib's own figure first, which costs no round trip when it has one;
                            // the server only for what it cannot say (-1).
                            suspend fun count(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter): Int? {
                                val local = td.getChatMessageCount(chatId, MessageTopicForum(id), filter, true).valueOrNull?.count
                                if (local != null && local >= 0) return local
                                return td.getChatMessageCount(chatId, MessageTopicForum(id), filter, false).valueOrNull?.count
                            }
                            val videos = count(SearchMessagesFilterVideo())
                            val files = if (videos != null && videos > 0) videos else count(SearchMessagesFilterDocument())
                            val answer = videos == null || files == null || videos > 0 || files > 0
                            if (videos != null && files != null) countedTopics["$chatId:$id"] = now() to answer
                            answer
                        }
                        ForumTopicSummary(
                            id = id,
                            name = topic.info.name.ifBlank { L.gridTopicUntitled },
                            isPinned = topic.isPinned,
                            isClosed = topic.info.isClosed,
                            unreadCount = topic.unreadCount,
                            hasVideos = hasVideos,
                        )
                    }
                }.awaitAll()
            }
        }
    }

    /** The forum topic [messageId] was posted in, or 0, for a player handed only the two ids. */
    suspend fun topicOf(chatId: Long, messageId: Long): Int = withContext(Dispatchers.IO) {
        val message = td.getMessage(chatId, messageId).valueOrNull ?: return@withContext 0
        (message.topicId as? MessageTopicForum)?.forumTopicId ?: 0
    }

    /**
     * The newest videos across every chat in the main list, newest first, for Home's "Recently
     * added in starred chats" row, which keeps the starred chats' share of them.
     *
     * One `searchMessages` per filter rather than a page per chat: Telegram answers across the
     * whole account in a single round trip, which is the only way this row can be cheap on a
     * 1 GB television. Videos sent as files come in through the document search and are screened
     * by name like everywhere else. Each chat's "restrict saving content" is read from TDLib's
     * memory, as [mediaPage] does for one chat.
     */
    suspend fun recentMedia(limit: Int = RECENT_LIMIT): List<MediaItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            val filters = listOf(SearchMessagesFilterVideo(), SearchMessagesFilterDocument())
            val found = filters.map { filter ->
                async {
                    td.searchMessages(ChatListMain(), "", "", limit, filter, null, 0, 0)
                        .valueOrNull?.messages?.toList().orEmpty()
                }
            }.flatMap { it.await() }
            // The kids lock (CP57): Telegram searches the whole account, so the chats it leaves out
            // are dropped here, before any of them costs a getChat.
            val allowed = ChatGate.awaitReady()
            found.filter { KidsLock.allows(allowed, it.chatId) && HiddenChats.shows(it.chatId) }.groupBy { it.chatId }.flatMap { (chatId, messages) ->
                val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
                MediaMapper.screen(messages, chatProtected).items
            }.distinctBy { it.id }
                .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
                .also { com.tmplayer.online.KnownMedia.note(it) }
        }
    }

    /**
     * One page of the search across every chat: Telegram's `searchMessages` with the video filter
     * and, beside it, the document filter, since most long videos are posted as files and Telegram
     * matches a document search against the file name. Each keeps its own offset in [cursor].
     *
     * [filters] decide the two things Telegram is asked rather than told afterwards: the kind of
     * chat (its own chat type filter) and whether archived chats count. With them counted no chat
     * list is named at all, which is how TDLib searches the archive along with the main list.
     *
     * The page carries a [ChatBrief] for each chat it found something in, read with `getChat`
     * (from TDLib's memory, for a chat it has seen), so a result names and pictures its chat even
     * when that chat is not in the list loaded on screen. A failure is thrown, so the caller can
     * say the search failed, or wait out a flood wait, rather than say nothing matched.
     */
    suspend fun searchAllChats(
        query: String,
        cursor: AllChatsCursor = AllChatsCursor(),
        limit: Int = AllChatsSearch.PAGE_SIZE,
        filters: SearchFilters = SearchFilters(),
    ): AllChatsPage = withContext(Dispatchers.IO) {
        coroutineScope {
            val list = if (filters.includeArchived) null else ChatListMain()
            val chatType = when (filters.chatType) {
                SearchChatType.All -> null
                SearchChatType.Channels -> SearchMessagesChatTypeFilterChannel()
                SearchChatType.Groups -> SearchMessagesChatTypeFilterGroup()
                SearchChatType.People -> SearchMessagesChatTypeFilterPrivate()
            }
            fun ask(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter, offset: String, done: Boolean) =
                if (done) null else async { td.searchMessages(list, query, offset, limit, filter, chatType, 0, 0).value() }
            val videos = ask(SearchMessagesFilterVideo(), cursor.videoOffset, cursor.videoDone)
            val documents = ask(SearchMessagesFilterDocument(), cursor.documentOffset, cursor.documentDone)
            val videoFound = videos?.await()
            val documentFound = documents?.await()
            val allowed = ChatGate.awaitReady()
            val messages = listOfNotNull(videoFound, documentFound).flatMap { it.messages.toList() }
                .filter { KidsLock.allows(allowed, it.chatId) && HiddenChats.shows(it.chatId) }
            val myId = if (messages.isEmpty()) 0L else Td.myId()
            val briefs = ArrayList<ChatBrief>()
            val items = messages.groupBy { it.chatId }.flatMap { (chatId, inChat) ->
                val chat = td.getChat(chatId).valueOrNull
                val screened = MediaMapper.screen(inChat, chat?.hasProtectedContent ?: false).items
                if (chat != null && screened.isNotEmpty()) {
                    val summary = summarise(chat, myId)
                    briefs += ChatBrief(summary.id, summary.title, summary.kind, summary.photoFileId, summary.miniThumbnail)
                }
                screened
            }
            fun finished(found: dev.g000sha256.tdl.dto.FoundMessages?, was: Boolean) =
                was || found == null || found.messages.isEmpty() || found.nextOffset.isEmpty()
            AllChatsPage(
                items = items.distinctBy { it.id }
                    .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }),
                cursor = AllChatsCursor(
                    videoOffset = videoFound?.nextOffset ?: cursor.videoOffset,
                    documentOffset = documentFound?.nextOffset ?: cursor.documentOffset,
                    videoDone = finished(videoFound, cursor.videoDone),
                    documentDone = finished(documentFound, cursor.documentDone),
                ),
                chats = briefs,
            )
        }
    }

    /**
     * One message as a video, for a Continue watching tile's picture: a resume record keeps no
     * artwork, and TDLib answers this from its own database for anything already seen.
     */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? =
        (lookupMedia(chatId, messageId) as? MessageLookup.Found)?.item

    /**
     * One message as a video, or a plain answer about why it cannot be had ([StaleEntries]): gone
     * for good (deleted, its chat closed, edited into something else) or simply not answered. A
     * gone one is reported to [GoneVideos], which takes it out of what the viewer kept (HX21);
     * anything else leaves the kept entry alone. A message that is there but not playable here
     * (withheld on this platform, a timer, a post still locked) is not gone either: its place is
     * kept, as the web does.
     */
    suspend fun lookupMedia(chatId: Long, messageId: Long): MessageLookup = withContext(Dispatchers.IO) {
        if (chatId == 0L || messageId <= 0L) return@withContext MessageLookup.Unreachable
        val lookup = when (val answer = td.getMessage(chatId, messageId)) {
            is TdlResult.Failure -> StaleEntries.classify(answer.code, answer.message)
            is TdlResult.Success -> {
                val message = answer.result
                val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
                val heldBack = MediaMapper.isVideoContent(message.content) ||
                    MediaMapper.selfDestructs(message) ||
                    !message.restrictionInfo?.restrictionReason.isNullOrEmpty() ||
                    message.schedulingState != null || message.sendingState != null
                MediaMapper.fromMessage(message, chatProtected)?.let { MessageLookup.Found(it) }
                    ?: if (heldBack) MessageLookup.Unreachable else MessageLookup.Gone(GoneReason.NotVideo)
            }
        }
        if (lookup is MessageLookup.Gone) GoneVideos.report(chatId, messageId, lookup.reason)
        lookup
    }

    /**
     * Pins or unpins a chat, in whichever of the two lists it currently lives in.
     *
     * These next three write to the viewer's actual Telegram account and show up on their phone a
     * second later. They are here because the tabs invite them: a screen with an Archived tab and
     * pins at the top of every list is one somebody will try to pin something into. All three are
     * reversible from the same menu, which is why these and not, say, leaving a chat.
     */
    suspend fun setPinned(chat: ChatSummary, pinned: Boolean): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            val list = if (chat.isArchived) ChatListArchive() else ChatListMain()
            td.toggleChatIsPinned(list, chat.id, pinned)
        }

    /**
     * Silences a chat, or gives it its voice back.
     *
     * Telegram has no mute flag: muting is a number of seconds to stay quiet for, and "forever" is
     * that number at its largest.
     *
     * The chat's existing settings are read first and only the two mute fields are changed. The
     * alternative, sending a freshly built settings object, would quietly reset the sound, the
     * previews and the story settings of every chat the viewer ever muted from here, none of which
     * they asked about and none of which this app has any business having an opinion on.
     */
    suspend fun setMuted(chatId: Long, muted: Boolean): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            val now = td.getChat(chatId).valueOrNull?.notificationSettings
            val settings = ChatNotificationSettings(
                // False either way: the chat is being given an answer of its own, and leaving it
                // on the account-wide default is what "unmute" would mean if the default were mute.
                useDefaultMuteFor = false,
                muteFor = if (muted) MUTE_FOREVER else 0,
                useDefaultSound = now?.useDefaultSound ?: true,
                soundId = now?.soundId ?: 0,
                useDefaultShowPreview = now?.useDefaultShowPreview ?: true,
                showPreview = now?.showPreview ?: true,
                useDefaultMuteStories = now?.useDefaultMuteStories ?: true,
                muteStories = now?.muteStories ?: false,
                useDefaultStorySound = now?.useDefaultStorySound ?: true,
                storySoundId = now?.storySoundId ?: 0,
                useDefaultShowStoryPoster = now?.useDefaultShowStoryPoster ?: true,
                showStoryPoster = now?.showStoryPoster ?: true,
                useDefaultDisablePinnedMessageNotifications =
                    now?.useDefaultDisablePinnedMessageNotifications ?: true,
                disablePinnedMessageNotifications = now?.disablePinnedMessageNotifications ?: false,
                useDefaultDisableMentionNotifications =
                    now?.useDefaultDisableMentionNotifications ?: true,
                disableMentionNotifications = now?.disableMentionNotifications ?: false,
            )
            td.setChatNotificationSettings(chatId, settings)
        }

    suspend fun setArchived(chatId: Long, archived: Boolean): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            // Telegram has no "unarchive": a chat is moved from one list to the other, and the
            // main list is where it goes back to.
            td.addChatToList(chatId, if (archived) ChatListArchive() else ChatListMain())
        }

    /**
     * Clears a chat's unread count without opening it.
     *
     * `forceRead` is what makes this work from a list. Without it TDLib treats the call as a claim
     * that the messages were actually on screen, and a chat whose messages this app has never
     * fetched keeps its count.
     */
    suspend fun markRead(chatId: Long): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            td.viewMessages(
                chatId = chatId,
                messageIds = longArrayOf(),
                source = null,
                forceRead = true,
            )
        }

    /**
     * The chat a public @username names, as the chat list would show it, or null when no such
     * username exists, it is not a chat (a sticker set, say), or the kids lock keeps it out. It is
     * looked up on Telegram's servers, so it finds a channel or a person that is not in the list.
     */
    suspend fun findByUsername(text: String): ChatSummary? = withContext(Dispatchers.IO) {
        val name = Usernames.clean(text) ?: return@withContext null
        val chat = td.searchPublicChat(name).valueOrNull ?: return@withContext null
        if (!KidsLock.allows(ChatGate.awaitReady(), chat.id)) return@withContext null
        summarise(chat, Td.myId())
    }

    /**
     * What a chat's info sheet shows: its name and picture, its public @username and t.me link
     * when it has one, a person's bio or a group's or channel's description, and how many members
     * or subscribers it has. Each part is asked for separately and left out when Telegram refuses
     * it, so a private person still shows a name and a picture.
     */
    suspend fun chatInfo(chatId: Long): ChatInfo? = withContext(Dispatchers.IO) {
        val chat = td.getChat(chatId).valueOrNull ?: return@withContext null
        val myId = Td.myId()
        val summary = summarise(chat, myId)
        var username: String? = null
        var about: String? = null
        var members: Int? = null
        var canJoin = false
        var joinByRequest = false
        when (val type = chat.type) {
            is ChatTypePrivate -> {
                username = td.getUser(type.userId).valueOrNull?.usernames?.activeUsernames?.firstOrNull()
                about = td.getUserFullInfo(type.userId).valueOrNull?.bio?.text
            }
            is ChatTypeSupergroup -> {
                val group = td.getSupergroup(type.supergroupId).valueOrNull
                username = group?.usernames?.activeUsernames?.firstOrNull()
                val full = td.getSupergroupFullInfo(type.supergroupId).valueOrNull
                about = full?.description
                members = full?.memberCount?.takeIf { it > 0 }
                    ?: group?.memberCount?.takeIf { it > 0 }
                // Seen through its @username or a link, not joined: the sheet offers to join it.
                canJoin = !username.isNullOrBlank() && group?.status is dev.g000sha256.tdl.dto.ChatMemberStatusLeft
                joinByRequest = group?.joinByRequest == true
            }
            is ChatTypeBasicGroup -> {
                about = td.getBasicGroupFullInfo(type.basicGroupId).valueOrNull?.description
                members = td.getBasicGroup(type.basicGroupId).valueOrNull?.memberCount?.takeIf { it > 0 }
            }
            else -> Unit
        }
        ChatInfo(
            summary = summary,
            bigPhotoFileId = chat.photo?.big?.id ?: 0,
            username = username?.takeIf { it.isNotBlank() && summary.kind != ChatKind.Saved },
            about = about?.trim()?.takeIf { it.isNotEmpty() },
            memberCount = members,
            canJoin = canJoin,
            joinByRequest = joinByRequest,
        )
    }

    /**
     * The account's own Saved Messages, for the places that open it straight away rather than
     * through a list holding that one chat. Null before sign in, or when TDLib cannot answer.
     */
    suspend fun savedMessages(): ChatSummary? = withContext(Dispatchers.IO) {
        val myId = Td.myId().takeIf { it != 0L } ?: return@withContext null
        val id = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
        val chat = td.getChat(id).valueOrNull ?: return@withContext null
        summarise(chat, myId)
    }

    /** One chat as the list draws it, read from TDLib (from memory for a chat it has seen). */
    suspend fun summary(chatId: Long): ChatSummary? = withContext(Dispatchers.IO) {
        val chat = td.getChat(chatId).valueOrNull ?: return@withContext null
        summarise(chat, Td.myId())
    }

    /**
     * A supergroup is Telegram's one type for both broadcast channels and large groups; only the
     * `isChannel` flag separates them, and getting it wrong puts a video channel under "Groups".
     */
    private fun kindOf(type: dev.g000sha256.tdl.dto.ChatType): ChatKind = when (type) {
        is ChatTypeSupergroup -> if (type.isChannel) ChatKind.Channel else ChatKind.Group
        is ChatTypeBasicGroup -> ChatKind.Group
        else -> ChatKind.Direct
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private class SearchResult(
        val items: List<MediaItem>,
        val next: Long,
        val done: Boolean,
        val selfDestructing: Int,
        val archives: List<ArchiveFile> = emptyList(),
    )

    private suspend fun search(
        chatId: Long,
        fromMessageId: Long,
        filter: dev.g000sha256.tdl.dto.SearchMessagesFilter,
        query: String = "",
        chatProtected: Boolean = false,
        pageSize: Int = PAGE_SIZE,
        topicId: Int = 0,
    ): SearchResult {
        val found = td.searchChatMessages(
            chatId = chatId,
            topicId = if (topicId != 0) MessageTopicForum(topicId) else null,
            // Telegram matches a document search against its file name, which is exactly how
            // videos are named in these chats.
            query = query,
            senderId = null,
            fromMessageId = fromMessageId,
            offset = 0,
            limit = pageSize,
            filter = filter,
        ).value()

        val screened = MediaMapper.screen(found.messages.toList(), chatProtected)
        // nextFromMessageId is 0 once the chat has no older matches left.
        val done = found.messages.isEmpty() || found.nextFromMessageId == 0L
        // A document search also meets the chat's archives, which are kept for the listing's foot.
        // Self-destructing ones stay out, as their videos do.
        val archives = if (filter is SearchMessagesFilterDocument) {
            found.messages.filter { !MediaMapper.selfDestructs(it) && it.schedulingState == null && it.sendingState == null }
                .mapNotNull(::archiveOf)
        } else {
            emptyList()
        }
        return SearchResult(screened.items, found.nextFromMessageId, done, screened.selfDestructing, archives)
    }

    companion object {
        /**
         * Telegram's "forever", which is a count of seconds like any other mute.
         *
         * Its own clients send this exact number for the option they label Mute forever, so a chat
         * silenced from here reads as muted rather than as muted for an oddly specific 68 years.
         */
        private const val MUTE_FOREVER = Int.MAX_VALUE

        const val PAGE_SIZE = 40

        /** Per filter, for [recentMedia]: enough to fill a row of ten after screening and dedupe. */
        const val RECENT_LIMIT = 40
        const val CHAT_LIMIT = 300

        /**
         * Rather fewer than the main list gets.
         *
         * The archive is where chats go to be out of the way, so it is read to fill a tab nobody
         * opens often rather than to be browsed three hundred deep, and every id in it costs a
         * `getChat` on the cold-start path that the main list is already paying for.
         */
        const val ARCHIVE_LIMIT = 100

        /** Topics read per `getForumTopics` call; Telegram's own cap is 100. */
        private const val TOPIC_PAGE = 100

        /** Past this a group is a forum of forums, and a chip row is the wrong way to browse it. */
        const val TOPIC_LIMIT = 300

        /** Topics whose videos are counted at once: two server calls each. */
        private const val COUNT_CHUNK = 8

        /**
         * Whether each topic held videos, by `chatId:topicId`, with when that was learnt: a group
         * opened again within [COUNT_TTL_MS] shows its tabs without counting every topic again.
         * For the process only; a topic that gains its first video shows up on the next launch.
         */
        private val countedTopics = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
        private const val COUNT_TTL_MS = 10 * 60 * 1000L
        private fun now() = System.currentTimeMillis()
    }
}

/**
 * [ChatRepository.savedMessages] on the signed-in session: what a Saved Messages button opens,
 * straight into the chat. Null when there is no session yet or TDLib has not answered within
 * [timeoutMs], and the caller then shows the list it showed before.
 */
suspend fun savedMessagesNow(timeoutMs: Long = 5_000): ChatSummary? = runCatching {
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { ChatRepository(Td.awaitAuthorizedSession().client).savedMessages() }
}.getOrNull()
