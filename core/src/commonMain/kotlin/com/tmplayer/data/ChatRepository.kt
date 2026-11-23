package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.Chat
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
import dev.g000sha256.tdl.dto.SearchMessagesFilterPhoto
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
        archiveLimit: Int = ARCHIVE_FIRST,
    ): ChatSync = withContext(Dispatchers.IO) { ChatListLoad.cached(listCalls, limit, known, archiveLimit) }

    /** Which chats are in which folder ([ChatListLoad.membership]): read behind the list, never ahead of it. */
    suspend fun folderMembership(): FolderMembership =
        withContext(Dispatchers.IO) { ChatListLoad.membership(listCalls, FOLDER_LIMIT) }

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
        if (!saved) com.tmplayer.online.ChatLanguageHints.note(chat.id, chat.title)
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

            // A photo whose caption names the title leads to its album's videos ([AlbumPosts]); only
            // beside a query, and never failing the page: a photo search that fails is asked again.
            val photos = async {
                if (query.isBlank() || cursors.photoDone) null
                else attempt { search(chatId, cursors.photo, SearchMessagesFilterPhoto(), query, chatProtected, pageSize, topicId) }
            }
            val attempts = listOf(videos.await(), documents.await(), animations.await())
            // One of the three failing is not the page failing: whatever the others found is still
            // shown, and the failed one keeps its cursor to be asked again on the next page. Only
            // when every search asked for failed is it the chat that failed, and that is thrown.
            val asked = attempts.filterNotNull()
            if (asked.isNotEmpty() && asked.all { it.isFailure }) throw asked.first().exceptionOrNull()!!
            val (videoResult, documentResult, animationResult) = attempts.map { it?.getOrNull() }

            val photoResult = photos.await()?.getOrNull()
            val found = buildList {
                videoResult?.let { addAll(it.items) }
                documentResult?.let { addAll(it.items) }
                animationResult?.let { addAll(it.items) }
            }
            // Search hits only: one read of each album's neighbourhood, a few a page, never per item
            // of a listing.
            val items = (if (query.isBlank()) found else AlbumPosts.enrich(found, photoResult?.photos.orEmpty(), albumReader))
                .distinctBy { it.messageId }.sortedByDescending { it.messageId }
                // An album's videos without a caption of their own read as the album's.
                .let(MediaMapper::shareAlbumCaptions)

            val next = cursors.copy(
                video = videoResult?.next ?: cursors.video,
                document = documentResult?.next ?: cursors.document,
                animation = animationResult?.next ?: cursors.animation,
                videoDone = cursors.videoDone || videoResult?.done ?: (attempts[0] == null),
                documentDone = cursors.documentDone || documentResult?.done ?: (attempts[1] == null),
                animationDone = cursors.animationDone || animationResult?.done ?: (attempts[2] == null),
                photo = photoResult?.next ?: cursors.photo,
                photoDone = cursors.photoDone || photoResult?.done ?: false,
            )
            val hidden = listOfNotNull(videoResult, documentResult, animationResult).sumOf { it.selfDestructing }
            // "More like this" offers only titles the viewer has; this is how it knows them.
            com.tmplayer.online.KnownMedia.note(items)
            MediaPage(items, next, next.allDone, hidden, documentResult?.archives.orEmpty())
        }
        }

    /**
     * One page of [chatId]'s history, read back from [fromMessageId] (0 for the newest), for a
     * search Telegram's word search could not answer ([ChatSearchRun]): the videos among the
     * messages, how many messages were read, and where the next page starts (0 at the chat's
     * start). [onlyLocal] reads only what TDLib keeps on this device, for a search offline.
     */
    suspend fun historyPage(chatId: Long, fromMessageId: Long, limit: Int, onlyLocal: Boolean): ChatHistoryPage =
        withContext(Dispatchers.IO) {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            val found = td.getChatHistory(chatId, fromMessageId, 0, limit.coerceIn(1, 100), onlyLocal).value()
            // TDLib leaves a hole for a message it could not read: skipped, and counted as read.
            val read = found.messages.size
            val messages = found.messages.filterNotNull()
            val items = MediaMapper.screen(messages, chatProtected).items.let(MediaMapper::shareAlbumCaptions)
            ChatHistoryPage(items, read, messages.minOfOrNull { it.id } ?: 0L, messages.mapNotNull(MediaMapper::albumPhotoOf))
        }

    /**
     * A page of [chatId]'s videos around [anchorMessageId], for the player's next video in the
     * chat's order ([ChatOrder.lookUp]): the older ones (the page a grid scrolls on to), or with
     * [newer] the ones posted after it. The three searches of [mediaPage] (videos, silent clips and
     * documents that look like videos) with the same screening, so the chat's grid and the player
     * agree on what a video is. Throws when TDLib fails: the caller keeps what it had.
     * [ChatOrder.Window.next] is where the page after this one starts, 0 at the chat's end on that
     * side: nothing is passed over between two pages, and a search that ran out is left out of it.
     */
    suspend fun videoWindow(
        chatId: Long,
        anchorMessageId: Long,
        newer: Boolean,
        topicId: Int = 0,
        pageSize: Int = PAGE_SIZE,
    ): ChatOrder.Window = withContext(Dispatchers.IO) {
        coroutineScope {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            // A negative offset asks for the posts above the anchor as well: the page is the anchor
            // and the pageSize - 1 posts after it.
            val offset = if (newer) -(pageSize - 1) else 0
            val filters = listOf<dev.g000sha256.tdl.dto.SearchMessagesFilter>(
                SearchMessagesFilterVideo(), SearchMessagesFilterDocument(), SearchMessagesFilterAnimation(),
            )
            val answers = filters.map { filter ->
                async {
                    td.searchChatMessages(
                        chatId = chatId,
                        topicId = if (topicId != 0) MessageTopicForum(topicId) else null,
                        query = "",
                        senderId = null,
                        fromMessageId = anchorMessageId,
                        offset = offset,
                        limit = pageSize,
                        filter = filter,
                    ).value().messages.toList()
                }
            }.awaitAll()
            val items = MediaMapper.screen(answers.flatten(), chatProtected).items
            // Each search that still has posts on this side ends where it stopped; the page after
            // starts where the first of them did, so none is passed over.
            val edges = answers.mapNotNull { messages ->
                if (messages.isEmpty()) return@mapNotNull null
                if (newer) {
                    if (messages.count { it.id > anchorMessageId } < pageSize - 1) null else messages.maxOf { it.id }
                } else {
                    if (messages.size < pageSize) null else messages.minOf { it.id }
                }
            }
            ChatOrder.Window(items, (if (newer) edges.minOrNull() else edges.maxOrNull()) ?: 0L)
        }
    }

    /**
     * The album [anchorMessageId] is in, read from the messages around it: one `getChatHistory`
     * window of [AlbumPosts.WINDOW] (a whole album ten long, wherever the hit falls in it), the
     * videos among them with the album's photo folded in. Throws when TDLib fails, so the caller
     * waits out a FLOOD_WAIT or leaves the album as the hit showed it.
     */
    private val albumReader = AlbumReader { chatId, anchorMessageId ->
        withContext(Dispatchers.IO) {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            val found = td.getChatHistory(chatId, anchorMessageId, -AlbumPosts.MAX_MEMBERS, AlbumPosts.WINDOW, false).value()
            val messages = found.messages.filterNotNull()
            val photo = AlbumPosts.pick(messages.mapNotNull(MediaMapper::albumPhotoOf)).values.firstOrNull()
            AlbumWindow(MediaMapper.screen(messages, chatProtected).items, photo)
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

    /**
     * What [chatId] (or one of its topics) holds besides videos, for the line under "No playable
     * videos". Three counts at once, each TDLib's own figure first and the server only for what
     * it cannot say (-1), each given [HOLDS_TIMEOUT_MS]; a kind that does not answer is left out.
     * Asked once per chat and topic for [COUNT_TTL_MS], and only when an empty page is drawn.
     */
    suspend fun holds(chatId: Long, topicId: Int = 0): ChatHolds {
        val key = "$chatId:$topicId"
        heldCounts[key]?.takeIf { now() - it.first < COUNT_TTL_MS }?.let { return it.second }
        val found = withContext(Dispatchers.IO) {
            val topic = if (topicId != 0) MessageTopicForum(topicId) else null
            suspend fun count(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter): Int? =
                kotlinx.coroutines.withTimeoutOrNull(HOLDS_TIMEOUT_MS) {
                    ChatHolds.known(td.getChatMessageCount(chatId, topic, filter, true).valueOrNull?.count)
                        ?: ChatHolds.known(td.getChatMessageCount(chatId, topic, filter, false).valueOrNull?.count)
                }
            coroutineScope {
                val photos = async { count(dev.g000sha256.tdl.dto.SearchMessagesFilterPhoto()) }
                val audio = async { count(dev.g000sha256.tdl.dto.SearchMessagesFilterAudio()) }
                val files = async { count(SearchMessagesFilterDocument()) }
                ChatHolds(photos.await(), audio.await(), files.await())
            }
        }
        // Only a whole answer is kept, so a timeout is asked again next time; bounded, for a long session.
        if (found.photos != null && found.audio != null && found.files != null) {
            if (heldCounts.size >= HOLDS_KEPT) heldCounts.clear()
            heldCounts[key] = now() to found
        }
        return found
    }

    /**
     * Whether [chatId] is a group with Telegram's topics turned on, read from TDLib's memory alone
     * (no server call), or null when TDLib does not hold the chat. Kept for the process: a group
     * rarely turns topics on or off, and the search by topic name asks it of many chats (U14).
     */
    suspend fun isForum(chatId: Long): Boolean? = withContext(Dispatchers.IO) {
        forumChats[chatId]?.let { return@withContext it }
        val chat = td.getChat(chatId).valueOrNull ?: return@withContext null
        val type = chat.type as? ChatTypeSupergroup
        val answer = if (type == null || type.isChannel) {
            false
        } else {
            td.getSupergroup(type.supergroupId).valueOrNull?.isForum ?: return@withContext null
        }
        forumChats[chatId] = answer
        answer
    }

    /**
     * The topics of forum [chatId] whose names answer [query], Telegram's matches, at most
     * [limit]: one server call, the search by topic name (U14). Whether each holds videos comes
     * from what [forumTopics] counted earlier or TDLib's local count, never a server count, so a
     * search costs one call per group; a topic nothing is known of counts as holding videos.
     *
     * A FLOOD_WAIT is waited out here, by the call that got it, once and for at most
     * [TOPIC_FLOOD_WAIT_S]; past that, or on any other refusal, it throws [TdlError].
     */
    suspend fun searchForumTopics(chatId: Long, query: String, limit: Int = TopicSearch.TOPICS_PER_CHAT): List<ForumTopicSummary> = withContext(Dispatchers.IO) {
        var waited = false
        var found: List<ForumTopicSummary>? = null
        while (found == null) {
            when (val answer = td.getForumTopics(chatId, query, 0, 0L, 0, limit)) {
                is TdlResult.Success -> found = answer.result.topics.distinctBy { it.info.forumTopicId }.map { topic ->
                    val id = topic.info.forumTopicId
                    val known = countedTopics["$chatId:$id"]?.second
                    val hasVideos = known ?: run {
                        suspend fun local(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter): Int? =
                            td.getChatMessageCount(chatId, MessageTopicForum(id), filter, true).valueOrNull?.count?.takeIf { it >= 0 }
                        val videos = local(SearchMessagesFilterVideo())
                        val files = if (videos == 0) local(SearchMessagesFilterDocument()) else null
                        !(videos == 0 && files == 0)
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
                is TdlResult.Failure -> {
                    val wait = Failures.floodWaitSeconds(answer.message)
                    if (wait == null || waited || wait > TOPIC_FLOOD_WAIT_S) throw TdlError(answer.code, answer.message)
                    waited = true
                    kotlinx.coroutines.delay(wait * 1000L + 250L)
                }
            }
        }
        found.orEmpty()
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
            // The kids lock (CP57) and the added chats (HX27): Telegram searches the whole account,
            // so the chats the gate leaves out are dropped here, before any of them costs a
            // getChat. Beware that this is a post-filter over ONE global search: a viewer with few
            // added chats can get nothing back, which is why Home reads its Recently added row
            // per added chat ([HomeLayout.chosenChats]) once a choice has been made, not from here.
            val allowed = ChatGate.awaitBrowsing()
            found.filter { KidsLock.allows(allowed, it.chatId) }.groupBy { it.chatId }.flatMap { (chatId, messages) ->
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
     *
     * With [everyChat] the answer is not narrowed to the added chats (HX62's one-off "Search my
     * other chats"); the kids lock still applies. Never called with it unless the viewer asked.
     */
    suspend fun searchAllChats(
        query: String,
        cursor: AllChatsCursor = AllChatsCursor(),
        limit: Int = AllChatsSearch.PAGE_SIZE,
        filters: SearchFilters = SearchFilters(),
        everyChat: Boolean = false,
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
            // A photo whose caption names the title leads to its album's videos ([AlbumPosts]). Beside a
            // query only, and a failure of this one search is not the page failing.
            val photos = if (query.isBlank() || cursor.photoDone) null else async {
                try {
                    td.searchMessages(list, query, cursor.photoOffset, limit, SearchMessagesFilterPhoto(), chatType, 0, 0).value()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val videoFound = videos?.await()
            val documentFound = documents?.await()
            val photoFound = photos?.await()
            // The viewer's own chats only (HX62): the added chats met with the kids lock. Only the
            // one-off "Search my other chats" ([everyChat]) reads the kids lock alone.
            val allowed = if (everyChat) ChatGate.awaitReady() else ChatGate.awaitBrowsing()
            val messages = listOfNotNull(videoFound, documentFound).flatMap { it.messages.toList() }
                .filter { KidsLock.allows(allowed, it.chatId) }
            val photoHits = photoFound?.messages?.toList().orEmpty()
                .filter { KidsLock.allows(allowed, it.chatId) }.mapNotNull(MediaMapper::albumPhotoOf)
            val myId = if (messages.isEmpty() && photoHits.isEmpty()) 0L else Td.myId()
            val screened = messages.groupBy { it.chatId }.flatMap { (chatId, inChat) ->
                MediaMapper.screen(inChat, td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false).items
            }
            // One read of each hit's album, a few a page, never one per item.
            val items = (if (query.isBlank()) screened else AlbumPosts.enrich(screened, photoHits, albumReader)).distinctBy { it.id }
            val briefs = ArrayList<ChatBrief>()
            for (chatId in items.map { it.chatId }.distinct()) {
                val chat = td.getChat(chatId).valueOrNull ?: continue
                val summary = summarise(chat, myId)
                briefs += ChatBrief(summary.id, summary.title, summary.kind, summary.photoFileId, summary.miniThumbnail)
            }
            fun finished(found: dev.g000sha256.tdl.dto.FoundMessages?, was: Boolean) =
                was || found == null || found.messages.isEmpty() || found.nextOffset.isEmpty()
            AllChatsPage(
                items = items.sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }),
                cursor = AllChatsCursor(
                    videoOffset = videoFound?.nextOffset ?: cursor.videoOffset,
                    documentOffset = documentFound?.nextOffset ?: cursor.documentOffset,
                    videoDone = finished(videoFound, cursor.videoDone),
                    documentDone = finished(documentFound, cursor.documentDone),
                    photoOffset = photoFound?.nextOffset ?: cursor.photoOffset,
                    photoDone = finished(photoFound, cursor.photoDone),
                ),
                chats = briefs,
            )
        }
    }

    /**
     * The same search, but inside the chats the viewer added (HX27), a page of chats at a time.
     *
     * Why this exists at all: [searchAllChats] is ONE `searchMessages` over the whole account, and
     * Telegram gives no way to say "these chats only". Post-filtering its answer is what the gate
     * does, and for a viewer who added twelve chats of three thousand it filters almost everything
     * away: the search would look broken while working exactly as asked. So each added chat is
     * searched on its own instead.
     *
     * The cost is kept to a page: [AllChatsSearch.CHATS_PER_PAGE] chats per page,
     * [CHATS_AT_ONCE] of them in flight, three calls each (videos, documents for the ones sent
     * as files, and photos, whose captions lead to an album's videos; none for a blank query). Load more reaches the next chats rather than deeper into the ones already read,
     * so a chat with hundreds of matches offers its newest here and the rest in the chat itself.
     * [added] is in the order the viewer added the chats, which is the order they are searched.
     */
    suspend fun searchAddedChats(
        query: String,
        added: Collection<Long>,
        cursor: AllChatsCursor = AllChatsCursor(),
        limit: Int = AllChatsSearch.PAGE_SIZE,
        filters: SearchFilters = SearchFilters(),
    ): AllChatsPage = withContext(Dispatchers.IO) {
        val kids = ChatGate.allowed.value
        val order = added.filter { KidsLock.allows(kids, it) }
        val window = order.drop(cursor.chatsSearched).take(AllChatsSearch.CHATS_PER_PAGE)
        val reached = (cursor.chatsSearched + window.size).coerceAtMost(order.size)
        val ran = cursor.copy(chatsSearched = reached, videoDone = reached >= order.size, documentDone = reached >= order.size)
        if (window.isEmpty()) return@withContext AllChatsPage(emptyList(), ran)
        val myId = Td.myId()
        val briefs = LinkedHashMap<Long, ChatBrief>()
        val found = ArrayList<MediaItem>()
        val photoHits = ArrayList<AlbumPhoto>()
        for (chunk in window.chunked(CHATS_AT_ONCE)) {
            val pages = coroutineScope {
                chunk.map { chatId ->
                    async {
                        // A chat that has gone, or that the type and archive filters leave out,
                        // costs one getChat and nothing more.
                        val chat = td.getChat(chatId).valueOrNull ?: return@async null
                        val summary = summarise(chat, myId)
                        if (!filters.includeArchived && summary.isArchived) return@async null
                        if (!matchesType(filters.chatType, summary.kind)) return@async null
                        val protected = chat.hasProtectedContent
                        coroutineScope {
                            // A FLOOD_WAIT is waited out by the call that got it, so one busy chat does
                            // not fail the page the others have answered.
                            val videos = async { floodWaited { search(chatId, 0, SearchMessagesFilterVideo(), query, protected, limit) } }
                            val documents = async { floodWaited { search(chatId, 0, SearchMessagesFilterDocument(), query, protected, limit) } }
                            // A photo whose caption names the title leads to its album's videos; a failed
                            // photo search leaves the others' answer standing.
                            val photos = async { if (query.isBlank()) null else attempt { search(chatId, 0, SearchMessagesFilterPhoto(), query, protected, limit) }.getOrNull() }
                            Triple(summary, videos.await().items + documents.await().items, photos.await()?.photos.orEmpty())
                        }
                    }
                }.awaitAll()
            }
            for (page in pages.filterNotNull()) {
                val (summary, videos, photos) = page
                briefs[summary.id] = ChatBrief(summary.id, summary.title, summary.kind, summary.photoFileId, summary.miniThumbnail)
                found += videos
                photoHits += photos
            }
        }
        // One read of each hit's album, a few a page, never one per item.
        val items = (if (query.isBlank()) found else AlbumPosts.enrich(found, photoHits, albumReader)).distinctBy { it.id }
        val inItems = items.mapTo(HashSet()) { it.chatId }
        AllChatsPage(
            items = items.sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }),
            cursor = ran,
            chats = briefs.values.filter { it.id in inItems },
        )
    }

    /** Whether a chat's kind answers the search's chat type filter. */
    private fun matchesType(type: SearchChatType, kind: ChatKind): Boolean = when (type) {
        SearchChatType.All -> true
        SearchChatType.Channels -> kind == ChatKind.Channel
        SearchChatType.Groups -> kind == ChatKind.Group
        SearchChatType.People -> kind == ChatKind.Direct || kind == ChatKind.Saved
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
     * The chat a public @username names, as the chat list would show it, or null when no such
     * username exists, it is not a chat (a sticker set, say), or the kids lock keeps it out. It is
     * looked up on Telegram's servers, so it finds a channel or a person that is not in the list.
     *
     * For a t.me link the viewer opened or pasted ([LinkFlow], HX30), never for Search: HX62 keeps
     * search inside the viewer's own chats, so nothing typed in it is looked up by username.
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

    /**
     * [block], and once more after Telegram's FLOOD_WAIT when it asked for [TOPIC_FLOOD_WAIT_S]
     * seconds or fewer. A longer wait, a second one or any other refusal is thrown as it came.
     */
    private suspend fun <T> floodWaited(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: TdlError) {
            val wait = Failures.floodWaitSeconds(e.message)
            if (wait == null || wait > TOPIC_FLOOD_WAIT_S) throw e
            kotlinx.coroutines.delay(wait * 1000L + 250L)
            return block()
        }
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
        /** The photos of albums among the hits (a search with the photo filter), for [AlbumPosts.enrich]. */
        val photos: List<AlbumPhoto> = emptyList(),
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
        val photos = if (filter is SearchMessagesFilterPhoto) found.messages.mapNotNull(MediaMapper::albumPhotoOf) else emptyList()
        return SearchResult(screened.items, found.nextFromMessageId, done, screened.selfDestructing, archives, photos)
    }

    companion object {
        const val PAGE_SIZE = 40

        /** Added chats searched side by side in [searchAddedChats]: three calls each, so nine in flight. */
        const val CHATS_AT_ONCE = 3

        /** Per filter, for [recentMedia]: enough to fill a row of ten after screening and dedupe. */
        const val RECENT_LIMIT = 40
        const val CHAT_LIMIT = 300

        /**
         * How much of the archive the first read takes, from TDLib's local list: the cold start's
         * first frame pays a `getChat` for each. A sync reads the rest ([ARCHIVE_LIMIT]).
         */
        const val ARCHIVE_FIRST = 100

        /**
         * The hard ceiling on the archive a sync reads, in pages of 100 until TDLib has all of it
         * (HX32): an account with a thousand archived channels used to lose all but 100. The
         * rows are read in chunks and kept as the main list's are, so the ceiling bounds memory.
         */
        const val ARCHIVE_LIMIT = 4000

        /** Chats read per folder: ids only, so it can be far above [CHAT_LIMIT]. */
        const val FOLDER_LIMIT = 2000

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

        /** What [holds] learnt, by `chatId:topicId`, at most [HOLDS_KEPT] of them. */
        private val heldCounts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ChatHolds>>()
        private const val HOLDS_KEPT = 64
        private const val HOLDS_TIMEOUT_MS = 6_000L

        /** Whether each chat is a group with topics, by id, as [isForum] learnt it. For the process. */
        private val forumChats = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

        /** The longest FLOOD_WAIT a topic search sits out before it says it failed. */
        const val TOPIC_FLOOD_WAIT_S = 30
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
