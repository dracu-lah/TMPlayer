package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.platform.Logger
import com.tmplayer.platform.Paths
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.CountryInfo
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.FileType
import dev.g000sha256.tdl.dto.FileTypeAnimation
import dev.g000sha256.tdl.dto.FileTypeAudio
import dev.g000sha256.tdl.dto.FileTypeDocument
import dev.g000sha256.tdl.dto.FileTypePhoto
import dev.g000sha256.tdl.dto.FileTypePhotoStory
import dev.g000sha256.tdl.dto.FileTypeProfilePhoto
import dev.g000sha256.tdl.dto.FileTypeSticker
import dev.g000sha256.tdl.dto.FileTypeThumbnail
import dev.g000sha256.tdl.dto.FileTypeVideo
import dev.g000sha256.tdl.dto.FileTypeVideoNote
import dev.g000sha256.tdl.dto.FileTypeVideoStory
import dev.g000sha256.tdl.dto.NetworkType
import dev.g000sha256.tdl.dto.NetworkTypeMobile
import dev.g000sha256.tdl.dto.NetworkTypeMobileRoaming
import dev.g000sha256.tdl.dto.NetworkTypeOther
import dev.g000sha256.tdl.dto.NetworkTypeWiFi
import dev.g000sha256.tdl.dto.OptionValueBoolean
import dev.g000sha256.tdl.dto.OptionValueInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Td"

/**
 * The single TDLib connection for the whole process.
 *
 * TDLib owns the session on disk, so there is exactly one client and it lives as long as the app
 * does. Logging out closes the client for good, so [clientLoop] builds a replacement: that is the
 * only supported way to log back in.
 */
object Td {

    @Volatile
    private var current: TdlClient? = null

    private val generation = AtomicLong(0)
    private val _session = MutableStateFlow<TdSession?>(null)

    /** Throws if TDLib has not finished starting; every caller here runs after [AuthState.Ready]. */
    val client: TdlClient
        get() = current ?: error("TDLib client is not running")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("Td"))
    private val gate = Mutex()
    private var lastHandled: AuthorizationState? = null

    /**
     * The way the user chose to sign in, and the deferred that ends the current client's life.
     *
     * Both belong to the login flow rather than to a single update: the choice has to survive
     * being asked the same TDLib question twice, and [replay] has no `closed` of its own.
     */
    private var method = SignInMethod.Undecided

    /** The method a fresh client starts with: what [restartSignIn] was asked to go straight to. */
    private var methodAfterRestart = SignInMethod.Undecided

    @Volatile
    private var closedSignal: CompletableDeferred<Unit>? = null

    private val _auth = MutableStateFlow<AuthState>(AuthState.Connecting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    /**
     * True once the sign in has sat on [AuthState.Connecting] for [CONNECT_TIMEOUT_MS]: Telegram
     * is most likely out of reach, and the screen says so with a Retry ([retryConnecting]).
     * Kept apart from [auth] so the spinner stays what it is and every screen that matches on the
     * state keeps working; a screen that has no use for the hint simply does not read it.
     */
    private val _signInStalled = MutableStateFlow(false)
    val signInStalled: StateFlow<Boolean> = _signInStalled.asStateFlow()

    /**
     * TDLib is logging out (Telegram ended the session, or the viewer signed out) and has not yet
     * closed. Nothing is wrong with the network then, so the sign in's slow help stays away, and
     * [watchLogOut] moves a logout that hangs on by itself.
     */
    private val _endingSession = MutableStateFlow(false)
    val endingSession: StateFlow<Boolean> = _endingSession.asStateFlow()

    /**
     * Telegram ended this session without the viewer signing out (HX23): ended from Telegram's
     * Devices list or by Telegram itself, noticed while the app ran ([apply]) or, for a session
     * that ended while it was closed, by the sign in screen ([markSessionEnded]). The sign in
     * screens say so, and a player open on the old session closes. Cleared by the next sign in.
     */
    private val _sessionEnded = MutableStateFlow(false)
    val sessionEnded: StateFlow<Boolean> = _sessionEnded.asStateFlow()

    /**
     * Set by [logOut] and [restartSignIn], so the logout that follows is the viewer's own and not
     * Telegram's. Kept until the next sign in question, past any client a hung logout replaces.
     */
    @Volatile
    private var logOutAsked = false

    /** When a refused request last had the session checked ([noteRequestFailure]). */
    private val lastAuthCheckAt = AtomicLong(0)

    /** When a refused request last had a new client started over a session TDLib still called signed in. */
    @Volatile
    private var lastAuthFreshClientAt = 0L

    /**
     * What was found out about a line that will not come up ([onLineStuck]): the only grounds for
     * saying Telegram is blocked ([ConnectAdvice.hint]). Emptied when the line comes up or the
     * network changes.
     */
    private val _lineEvidence = MutableStateFlow(LineEvidence())
    val lineEvidence: StateFlow<LineEvidence> = _lineEvidence.asStateFlow()

    /**
     * True once a signed in session has had no line to Telegram for [UNREACHABLE_MS] while the
     * network itself is up: Telegram is blocked or out of reach, and a banner offers Retry and a
     * proxy. Cleared the moment the line is back, or by a Retry ([retryConnecting]).
     */
    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    /** Bumped by [retryConnecting], so the stall count starts again while the state stays put. */
    private val connectAttempt = MutableStateFlow(0)

    /**
     * Whether any of the app's screens is on view ([onForegroundChanged]). Time spent in the
     * background never counts towards [unreachable]: Android holds a backgrounded app's sockets
     * and TDLib backs off, so the line is down for reasons that say nothing about the network.
     */
    private val foreground = MutableStateFlow(true)

    /** Bumped each time the app comes back into view, so the [unreachable] count starts afresh. */
    private val foregroundEpoch = MutableStateFlow(0)

    /** The client [retryConnecting] is closing, whose aborted requests are not a sign in failure. */
    @Volatile
    private var retiring: TdlClient? = null

    /** Completed to try again after a client could not be started at all. See [keepClientRunning]. */
    @Volatile
    private var clientRetry: CompletableDeferred<Unit>? = null

    /** Why the first client could not start, kept for the retries that can only say less. */
    private var startFailure: String? = null

    /**
     * Whether TDLib currently has a line to Telegram.
     *
     * Signing in is not the same thing as being connected: the session is read off disk and the
     * account is ready long before the socket is, and a search sent in that gap comes back empty
     * rather than failing. Wait on this before treating an empty answer as the truth.
     */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * The same line in full: waiting for a network, going through a proxy, connecting, catching up
     * or ready. The connecting screen says which, so a wait is never a silent one.
     */
    private val _link = MutableStateFlow(TelegramLink.Starting)
    val link: StateFlow<TelegramLink> = _link.asStateFlow()

    /** What the platform last said about its network ([onNetworkChanged]); unknown on a desktop. */
    private val _network = MutableStateFlow(NetworkFacts())
    val network: StateFlow<NetworkFacts> = _network.asStateFlow()

    /** Whether the signed in account has Telegram Premium (the `is_premium` option). */
    private val _premium = MutableStateFlow(false)
    val premium: StateFlow<Boolean> = _premium.asStateFlow()

    /**
     * When Telegram last said it is capping this account's download speed
     * (`updateSpeedLimitNotification`), or 0. See [speedLimitedNow].
     */
    @Volatile
    private var speedLimitedAt = 0L

    /** Whether a slow download now is Telegram's cap for an account without Premium ([SpeedLimit]). */
    fun speedLimitedNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        SpeedLimit.limited(_premium.value, speedLimitedAt, nowMs)

    /** Whether TDLib is set to go through one of its stored proxies. See [proxies]. */
    private val _proxyOn = MutableStateFlow(false)
    val proxyOn: StateFlow<Boolean> = _proxyOn.asStateFlow()

    /**
     * The viewer's Telegram folders, in the order they were set up in.
     *
     * Collected here rather than where they are drawn because there is no request that asks for
     * them: TDLib announces the whole set once, shortly after the client starts, and again only
     * when it changes, so a screen that subscribes when it opens would miss the announcement.
     * Started with the client and emptied with it, so the folders go with whoever is signed in.
     */
    private val _folders = MutableStateFlow<List<ChatFolderSummary>>(emptyList())
    val folders: StateFlow<List<ChatFolderSummary>> = _folders.asStateFlow()

    /**
     * Where Telegram puts "All chats" among [folders]: 0 is first, which is where it is unless
     * the viewer moved it (a Premium account can). The Chats page's chips follow it.
     */
    private val _mainListPosition = MutableStateFlow(0)
    val mainListPosition: StateFlow<Int> = _mainListPosition.asStateFlow()

    /**
     * Videos posted, edited into a message or deleted while the app is open, as TDLib reports
     * them, for every chat. Nothing is fetched to produce these: a post arrives with its message,
     * an edit is reported only when the new content is a video, and a deletion is passed on as the
     * ids, for each listing to check against its own. A listing batches them ([batched]) so a burst
     * of posts is one redraw.
     *
     * TDLib only follows a channel or supergroup closely while it is open; see [watchChat].
     */
    private val _videoChanges = MutableSharedFlow<VideoChange>(
        extraBufferCapacity = VIDEO_CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val videoChanges: SharedFlow<VideoChange> = _videoChanges.asSharedFlow()

    /**
     * Tells TDLib the viewer is looking at [chatId] until the returned handle is closed, which is
     * what makes it deliver a channel's or a supergroup's new posts as they happen rather than on
     * the next fetch. Counted, so two screens on the same chat do not close it under each other.
     * Never marks anything read: that is a separate request this app does not make here.
     */
    suspend fun watchChat(chatId: Long): AutoCloseable {
        val first = synchronized(watched) {
            val count = watched.getOrDefault(chatId, 0)
            watched[chatId] = count + 1
            count == 0
        }
        if (first) current?.let { runCatching { it.openChat(chatId) } }
        return AutoCloseable {
            val last = synchronized(watched) {
                val count = watched.getOrDefault(chatId, 0) - 1
                if (count <= 0) watched.remove(chatId) else watched[chatId] = count
                count <= 0
            }
            if (last) current?.let { client -> scope.launch { runCatching { client.closeChat(chatId) } } }
        }
    }

    private val watched = HashMap<Long, Int>()

    /**
     * Where TDLib keeps its database and files, read by every new client in [sendParameters].
     *
     * Volatile and replaceable rather than set once, because [restart] points the next client at a
     * new files directory. Null until [start].
     */
    @Volatile
    private var currentPaths: Paths? = null
    private lateinit var device: DeviceInfo
    private lateinit var credentials: Credentials

    /** The API id this app signs in with, or 0 before [start]: how TMPlayer's own sessions are told apart. */
    val apiId: Int get() = if (::credentials.isInitialized) credentials.apiId else 0

    /** The directories the running client was given, or null before [start]. */
    val paths: Paths? get() = currentPaths

    /**
     * Starts the client once per process; later calls are ignored. Each app supplies where TDLib
     * keeps its files, what the device calls itself, and the API credentials it was built with.
     */
    fun start(paths: Paths, device: DeviceInfo, credentials: Credentials) {
        synchronized(this) {
            if (currentPaths != null) return
            this.device = device
            this.credentials = credentials
            this.currentPaths = paths
        }
        scope.launch { keepClientRunning() }
        scope.launch {
            // Held up on Telegram (ConnectAdvice.waiting), counted afresh by a retry but not by a
            // move between two states that are both still waiting, such as Connecting to the
            // phone pane with no line yet.
            val waiting = combine(_auth, _link, connectAttempt, _endingSession) { auth, link, attempt, ending ->
                (ConnectAdvice.waiting(auth, link) && !ending) to attempt
            }.distinctUntilChanged().map { it.first }
            AuthStall.watchWaiting(waiting, _signInStalled, SIGN_IN_STALL_MS)
        }
        scope.launch {
            // Signed in, the network up as far as the platform knows, and still no line: the
            // blocked network that otherwise reads as a slow one. A network that is really gone
            // has the offline notice already, so it is left to that.
            // Only while the app is on view, and counted from its return: a phone that was in a
            // pocket comes back with its line down, and that is a reconnect, not a blocked network.
            val held = combine(_auth, _link, _network, connectAttempt, combine(foreground, foregroundEpoch, ::Pair)) { auth, link, network, attempt, (shown, epoch) ->
                val up = network.status != NetworkStatus.Offline && link != TelegramLink.WaitingForNetwork
                (shown && auth is AuthState.Ready && !link.isUp && up) to (attempt to epoch)
            }.distinctUntilChanged().map { it.first }
            AuthStall.watchWaiting(held, _unreachable, UNREACHABLE_MS)
        }
        scope.launch {
            // A wait that went on too long is looked into rather than guessed at (HX23): TDLib is
            // asked whether the session is still signed in, and the line is checked for a sign of a
            // block. A sign in that stalls gets the same check, for its hint.
            combine(_unreachable, _signInStalled) { unreachable, stalled -> unreachable || stalled }
                .distinctUntilChanged()
                .collectLatest { stuck ->
                    if (!stuck) return@collectLatest
                    try {
                        onLineStuck()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "checking a line that will not come up failed", e)
                    }
                }
        }
    }

    /**
     * Runs [clientLoop], and turns anything that escapes it into a sign in failure the user can read.
     *
     * TDLib's native library is loaded by the first client, so a build missing the library for this
     * machine (or one the system refuses to load) throws here, outside any request. Left alone it
     * would end this coroutine, reach only the log, and leave the screen on Connecting for good.
     * The failure screen's Try again ([restartSignIn]) completes [clientRetry] to start over.
     */
    private suspend fun keepClientRunning() {
        while (true) {
            try {
                clientLoop()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "TDLib client could not run", e)
                current = null
                _session.value = null
                _connected.value = false
                // A class whose static set up failed once answers every later use with a bare
                // NoClassDefFoundError; the first failure is the one that says what went wrong.
                val reason = startFailure.takeIf { e is NoClassDefFoundError } ?: describe(e)
                if (startFailure == null) startFailure = reason
                val retry = CompletableDeferred<Unit>()
                clientRetry = retry
                _auth.value = AuthState.Failed(L.errorsTdlibUnavailable(reason))
                retry.await()
                clientRetry = null
                _auth.value = AuthState.Connecting
            }
        }
    }

    /** The first message in [error]'s chain of causes, or its class name when none has one. */
    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: error::class.simpleName.orEmpty()

    /**
     * Closes the client and starts a new one over [newPaths], keeping the viewer signed in.
     *
     * TDLib takes its directories once, at start, so moving its files directory means a new
     * client. The database directory should be the same as before, since that is where the
     * session is; only the files directory is expected to change. Returns once the replacement
     * client exists, or after [timeoutMs] if TDLib never reports the old one closed, in which case
     * the old one is abandoned and the new one started anyway.
     *
     * Callers stop everything that reads TDLib's files first (players, the download queue, the
     * watch cache sweep): their file ids and paths belong to the old client. The download queue
     * is not restored again; it is resumed by whoever paused it.
     */
    suspend fun restart(newPaths: Paths, timeoutMs: Long = RESTART_TIMEOUT_MS) {
        check(currentPaths != null) { "Td.start has not been called" }
        currentPaths = newPaths
        val before = _session.value?.generation
        val signal = closedSignal
        val td = current
        if (td != null) {
            gate.withLock { perform(td, AuthReducer.close()) }
        }
        val replaced = withTimeoutOrNull(timeoutMs) {
            _session.first { it != null && it.generation != before }
        }
        if (replaced == null) {
            Logger.w(TAG, "TDLib did not report the old client closed; starting a new one anyway")
            signal?.complete(Unit)
            _session.first { it != null && it.generation != before }
        }
    }

    private suspend fun clientLoop() = coroutineScope {
        while (isActive) {
            val closed = CompletableDeferred<Unit>()
            val td = TdlClient.create()
            current = td
            retiring = null
            _session.value = TdSession(td, generation.incrementAndGet())
            lastHandled = null
            method = methodAfterRestart
            methodAfterRestart = SignInMethod.Undecided
            closedSignal = closed

            val updates = launch {
                td.authorizationStateUpdates.collect { apply(td, it.authorizationState) }
            }
            val folders = launch {
                td.chatFoldersUpdates.collect { update ->
                    _mainListPosition.value = update.mainChatListPosition
                    _folders.value = update.chatFolders.map { folder ->
                        // The name is formatted text because Telegram lets a folder carry custom
                        // emoji. Only the plain characters are kept: the rail draws its own icon,
                        // and a custom emoji this client cannot render would print as its
                        // placeholder character.
                        ChatFolderSummary(
                            id = folder.id,
                            title = folder.name.text.text.trim().ifBlank { L.chatsUntitledFolder(folder.id.toString()) },
                            icon = folder.icon.name,
                        )
                    }
                }
            }
            val posted = launch {
                td.newMessageUpdates.collect { update ->
                    // Only what a chat's grid lists: a round video or a link preview's video is
                    // found through its own entry (CP81), never added to the grid live.
                    if (!MediaMapper.isVideoContent(update.message.content)) return@collect
                    MediaMapper.fromMessage(update.message)?.let { item ->
                        _videoChanges.tryEmit(VideoChange.Added(update.message.chatId, item))
                    }
                }
            }
            val edited = launch {
                td.messageContentUpdates.collect { update ->
                    if (MediaMapper.isVideoContent(update.newContent)) {
                        _videoChanges.tryEmit(VideoChange.Edited(update.chatId, update.messageId))
                    } else {
                        // Edited into a photo or a text: its tile has to go, or it plays a file
                        // the post no longer has. A message that never was a video is not listed,
                        // so removing it changes nothing.
                        _videoChanges.tryEmit(VideoChange.Removed(update.chatId, setOf(update.messageId), byEdit = true))
                    }
                }
            }
            val deleted = launch {
                td.deleteMessagesUpdates.collect { update ->
                    // fromCache is TDLib tidying its own storage, not anything the chat did.
                    if (update.isPermanent && !update.fromCache && update.messageIds.isNotEmpty()) {
                        _videoChanges.tryEmit(VideoChange.Removed(update.chatId, update.messageIds.toSet()))
                    }
                }
            }
            val options = launch {
                td.optionUpdates.collect { update ->
                    if (update.name == "is_premium") _premium.value = (update.value as? OptionValueBoolean)?.value == true
                }
            }
            val speedLimit = launch {
                td.speedLimitNotificationUpdates.collect { update ->
                    if (!update.isUpload) {
                        speedLimitedAt = System.currentTimeMillis()
                        Logger.i(TAG, "download speed limited by Telegram")
                    }
                }
            }
            val connection = launch {
                td.connectionStateUpdates.collect { update ->
                    // "Updating" means the socket is up and TDLib is pulling in what it missed.
                    // Requests are answered from that point on, so it counts as connected; waiting
                    // for it to finish would hold the chat list back on a large account.
                    val now = TelegramLink.of(update.state)
                    if (now != _link.value) Logger.i(TAG, "connection: $now")
                    _link.value = now
                    _connected.value = now.isUp
                    // A line is the end of any wait: what was found out about it is history.
                    if (now.isUp && _lineEvidence.value != LineEvidence()) _lineEvidence.value = LineEvidence()
                }
            }
            // The first update may already have been emitted before we subscribed, so ask
            // once directly. Both paths go through the same mutex-guarded handler.
            val primer = launch {
                runCatching { td.setLogVerbosityLevel(1) }
                when (val now = td.getAuthorizationState()) {
                    is TdlResult.Success -> apply(td, now.result)
                    // Not fatal: the update flow still delivers the state. Logged because it is
                    // the first thing to look at when a sign in never leaves Connecting.
                    is TdlResult.Failure -> Logger.w(TAG, "getAuthorizationState failed: ${now.message}")
                }
            }

            closed.await()
            updates.cancel()
            posted.cancel()
            edited.cancel()
            deleted.cancel()
            synchronized(watched) { watched.clear() }
            connection.cancel()
            options.cancel()
            speedLimit.cancel()
            _premium.value = false
            speedLimitedAt = 0L
            folders.cancel()
            _folders.value = emptyList()
            _mainListPosition.value = 0
            cachedMyId = 0L
            // Cancelled with the rest of the generation: a client closed while the primer is
            // still waiting on TDLib would otherwise leak a coroutine holding a dead client.
            primer.cancel()
            _connected.value = false
            _link.value = TelegramLink.Starting
            current = null
            _session.value = null
        }
    }

    private suspend fun apply(td: TdlClient, state: AuthorizationState) = gate.withLock {
        // The update flow and the one-off getAuthorizationState() often deliver the same state
        // back to back at startup. Acting twice would send TDLib its parameters twice and surface
        // the second attempt's error as a login failure. Comparing against only the immediately
        // previous state still lets a genuine repeat through: a QR link that expires and sends us
        // back to WaitPhoneNumber has a different state in between.
        if (state == lastHandled) return@withLock
        lastHandled = state

        val step = AuthReducer.reduce(state, method)
        Logger.i(TAG, "auth: ${state::class.simpleName} -> ${step.state::class.simpleName}")
        val ending = state is AuthorizationStateLoggingOut || state is AuthorizationStateClosing || state is AuthorizationStateClosed
        if (state is AuthorizationStateLoggingOut && !_endingSession.value) scope.launch { watchLogOut(td) }
        _endingSession.value = ending
        // A logout nobody here asked for is Telegram ending the session (HX23), whether it happened
        // while the app ran or before it opened (TDLib then resumes the logout at start).
        if (SessionCheck.endedByTelegram(AuthReducer.neutral(state), logOutAsked) && !_sessionEnded.value) {
            Logger.i(TAG, "Telegram ended this session")
            _sessionEnded.value = true
        }
        when (state) {
            is AuthorizationStateReady -> {
                _sessionEnded.value = false
                logOutAsked = false
            }
            // The logout is over, whoever asked for it: the next one is news again.
            is AuthorizationStateWaitPhoneNumber -> logOutAsked = false
            else -> Unit
        }
        perform(td, step)
    }

    /**
     * The sign in screen came after an account this device had signed in, with no Sign out in
     * between ([SessionCheck.endedElsewhere]): the session ended while the app was closed. The
     * sign in screens say so until the next sign in.
     */
    fun markSessionEnded() {
        if (_auth.value !is AuthState.Ready) _sessionEnded.value = true
    }

    /**
     * A request came back refused as signed out ([SessionCheck.isAuthError]) while the app shows a
     * signed in session (HX23): a file the player asked for on the TV failed this way for minutes
     * while Home sat on its cached rows. TDLib is asked its state at once; if it still says signed
     * in, a new client is started, which hears from Telegram straight away. At most once per
     * [AUTH_CHECK_GAP_MS], however many requests fail.
     */
    fun noteRequestFailure(code: Int, message: String?) {
        if (!SessionCheck.isAuthError(code, message)) return
        if (_auth.value !is AuthState.Ready) return
        val now = System.currentTimeMillis()
        val last = lastAuthCheckAt.get()
        if (now - last < AUTH_CHECK_GAP_MS || !lastAuthCheckAt.compareAndSet(last, now)) return
        Logger.i(TAG, "a request was refused as signed out ($code); checking the session")
        scope.launch {
            val td = current ?: return@launch
            if (!confirmSignedIn(td)) return@launch
            if (td !== current || now - lastAuthFreshClientAt < AUTH_FRESH_CLIENT_GAP_MS) return@launch
            lastAuthFreshClientAt = now
            Logger.i(TAG, "TDLib calls the session signed in and Telegram refused it; starting a new client")
            replaceClient(td)
        }
    }

    /**
     * Asks TDLib for its state directly, for an update the app may have missed. True when it is
     * still signed in (or did not answer); false when it is not, after handing the state to [apply].
     */
    private suspend fun confirmSignedIn(td: TdlClient): Boolean {
        val result = withTimeoutOrNull(AUTH_CHECK_MS) { td.getAuthorizationState() } ?: return true
        val state = result.valueOrNull ?: return true
        if (state is AuthorizationStateReady) return true
        Logger.i(TAG, "TDLib is ${state::class.simpleName} while the app shows Ready; catching up")
        apply(td, state)
        return false
    }

    /**
     * A sign in or a signed in session that has waited too long for a line ([unreachable],
     * [signInStalled]). TDLib is asked whether the session is still signed in, then the line is
     * checked ([LineProbe]) and what it shows kept as [lineEvidence]. When Telegram's data centres
     * answer and a signed in session still has no line, a new client is started once per wait: on
     * the Mi TV a session Telegram had ended sat on Connecting for many minutes, and only a restart
     * of the app brought the sign in up (HX23).
     */
    private suspend fun onLineStuck() {
        val td = current ?: return
        if (_auth.value is AuthState.Ready && !confirmSignedIn(td)) return
        // Through a proxy the direct line says nothing; its own hint covers it.
        if (_proxyOn.value) return
        val probe = LineProbe.run()
        if (td !== current || _link.value.isUp) return
        val evidence = _lineEvidence.value.copy(internet = probe.internet, telegram = probe.telegram)
        _lineEvidence.value = evidence
        Logger.i(TAG, "no line: other sites answer ${probe.internet}, Telegram answers ${probe.telegram}")
        when (SessionCheck.afterProbe(_auth.value is AuthState.Ready, foreground.value, _proxyOn.value, evidence)) {
            LineAction.FreshClient -> {
                _lineEvidence.value = evidence.copy(freshClientTried = true)
                Logger.i(TAG, "Telegram answers and this session has no line; starting a new client")
                // Apart from this wait, which the new client's Connecting ends and would cancel.
                scope.launch { replaceClient(td) }
            }
            LineAction.Wait -> Unit
        }
    }

    /**
     * A logout that hangs on: after a session Telegram ended, TDLib kept trying to tell Telegram
     * over a line that would not come up, and the window waited on "Reaching Telegram" until a
     * Retry (desktop, 2026-10-28). First the connections are reopened, which lets the logout
     * through; if it still has not closed, the client is replaced as Retry does.
     */
    private suspend fun watchLogOut(td: TdlClient) {
        delay(LOG_OUT_NUDGE_MS)
        if (td !== current || !_endingSession.value) return
        Logger.i(TAG, "logging out is slow; reopening connections")
        runCatching { tellNetworkType(td, force = true) }
        delay(LOG_OUT_GIVE_UP_MS)
        if (td !== current || !_endingSession.value) return
        Logger.i(TAG, "logging out did not finish; starting a new client")
        replaceClient(td)
    }

    /** Shows [step]'s state and carries out its action. Called with [gate] held. */
    private suspend fun perform(td: TdlClient, step: AuthStep) {
        // Never let a stale "connecting" overwrite a terminal failure the user still needs to read.
        if (_auth.value !is AuthState.Failed || step.state !is AuthState.Connecting) {
            _auth.value = step.state
        }

        when (step.action) {
            AuthAction.SendParameters -> sendParameters(td)
            // Not awaited under the gate: with no route to Telegram the request waits for one, and
            // holding the gate that long would leave the client's Closing and Closed unhandled, so
            // neither a retry nor a restart could replace it. The link itself arrives as an update.
            AuthAction.RequestQrCode -> {
                scope.launch {
                    val result = td.requestQrCodeAuthentication(longArrayOf())
                    // A client being replaced answers its open requests with an error; that is the
                    // retry working, not the sign in failing.
                    if (result is TdlResult.Failure && td === current && td !== retiring) {
                        // A flood wait carries its length, which the screen counts down before it
                        // offers the code again, rather than a Try again that fails the same way.
                        _auth.value = AuthState.Failed(
                            Failures.humanise(result.message),
                            Failures.floodWaitSeconds(result.message) ?: 0,
                        )
                    }
                }
            }
            AuthAction.OnReady -> onReady(td)
            AuthAction.RecreateClient -> closedSignal?.complete(Unit)
            // TDLib answers with Closing and then Closed, and Closed is reduced to RecreateClient
            // above: the replacement is started there, once the old client has let go of the
            // database, rather than here, where it would find the database still locked.
            AuthAction.Close -> runCatching { td.close() }
            AuthAction.None -> Unit
        }
    }

    /**
     * Runs the last TDLib state through the reducer again after [method] has moved.
     *
     * TDLib is not told anything here and sends nothing new: the state it is sitting in has simply
     * come to mean something different now the user has said which way they are signing in.
     * Clearing [lastHandled] is what lets the same state through the duplicate guard once more.
     */
    private suspend fun replay() {
        val td = current ?: return
        val state = gate.withLock { lastHandled.also { lastHandled = null } } ?: return
        apply(td, state)
    }

    /** The user picked a route on the first login screen. */
    suspend fun chooseSignInMethod(chosen: SignInMethod) {
        // Once a QR code is up TDLib is waiting for the other device, and it takes a phone number
        // only from its first question. Starting the sign in over gets back to that question, with
        // the method kept, so it is answered with the number entry this time.
        if (chosen == SignInMethod.Phone && _auth.value is AuthState.Qr) {
            restartSignIn(SignInMethod.Phone)
            return
        }
        method = chosen
        replay()
    }

    /**
     * Sends the phone number Telegram should text. Returns null on success, an error otherwise.
     *
     * The settings argument is left at TDLib's own default: flash calls and the SMS retriever both
     * want permissions this app does not hold, and neither exists on a TV at all.
     */
    suspend fun submitPhoneNumber(phoneNumber: String): String? {
        val td = current ?: return L.errorsNotConnected
        // With no line to Telegram the request waits for one, and the button waited with it. The
        // request stays with TDLib, so if the line comes back the code is still sent and the code
        // screen follows; the viewer is told now, rather than never.
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.setAuthenticationPhoneNumber(phoneNumber) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.Phone(wrong = true)
                if (result.message.contains("PHONE_NUMBER_INVALID")) {
                    L.errorsPhoneInvalid
                } else {
                    Failures.humanise(result.message)
                }
            }
        }
    }

    /** Submits the login code Telegram sent. Returns null on success, an error otherwise. */
    suspend fun submitCode(code: String): String? {
        val td = current ?: return L.errorsNotConnected
        // Copied rather than rebuilt: the screen's delivery route, digit count and resend timer all
        // live in this state, and a fresh Code() would blank them the moment a digit is mistyped.
        val current = _auth.value as? AuthState.Code ?: AuthState.Code("")
        return when (val result = td.checkAuthenticationCode(code)) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = current.copy(wrong = true)
                when {
                    result.message.contains("PHONE_CODE_INVALID") -> L.errorsWrongCode
                    result.message.contains("PHONE_CODE_EXPIRED") ->
                        L.errorsCodeExpired
                    else -> Failures.humanise(result.message)
                }
            }
        }
    }

    /**
     * Gives Telegram the email address it asked for ([AuthState.EmailAddress]); it mails a code
     * there next. Null on success, an error otherwise.
     */
    suspend fun submitEmailAddress(address: String): String? {
        val td = current ?: return L.errorsNotConnected
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.setAuthenticationEmailAddress(address.trim()) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.EmailAddress(wrong = true)
                if (result.message.contains("EMAIL_INVALID")) L.errorsEmailInvalid else Failures.humanise(result.message)
            }
        }
    }

    /** Submits the code Telegram mailed ([AuthState.EmailCode]). Null on success, an error otherwise. */
    suspend fun submitEmailCode(code: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.EmailCode ?: AuthState.EmailCode("")
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
            td.checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code.trim()))
        } ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = asked.copy(wrong = true)
                when {
                    result.message.contains("CODE_INVALID") -> L.errorsWrongCode
                    result.message.contains("CODE_EXPIRED") -> L.errorsCodeExpired
                    else -> Failures.humanise(result.message)
                }
            }
        }
    }

    /**
     * Every country Telegram knows, with its dial codes, in Telegram's own spelling.
     *
     * Hidden entries are dropped here rather than in the picker: they are countries Telegram will
     * not accept a number from, so nothing above this layer has any use for them.
     */
    suspend fun countries(): List<CountryInfo> {
        val td = current ?: return emptyList()
        return td.getCountries().valueOrNull?.countries.orEmpty().filterNot { it.isHidden }.toList()
    }

    /** The ISO code Telegram guesses from the IP address, blank if it will not guess. */
    suspend fun guessedCountryCode(): String {
        val td = current ?: return ""
        return td.getCountryCode().valueOrNull?.text.orEmpty()
    }

    /**
     * Asks Telegram to send the code again, by whatever route it has decided is next.
     *
     * The reason is left to TDLib's default: the app has not been told the code failed to arrive,
     * only that the user pressed the button, and guessing at a reason would be inventing one.
     */
    suspend fun resendCode(): String? {
        val td = current ?: return L.errorsNotConnected
        return when (val result = td.resendAuthenticationCode()) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> Failures.humanise(result.message)
        }
    }

    private suspend fun sendParameters(td: TdlClient) {
        val paths = currentPaths ?: return
        if (!credentials.present) {
            _auth.value = AuthState.Failed(
                L.errorsNoApiCredentials,
            )
            return
        }
        val result = td.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = paths.databaseDir.absolutePath,
            filesDirectory = paths.filesDir.absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = credentials.apiId,
            apiHash = credentials.apiHash,
            systemLanguageCode = "en",
            deviceModel = device.model,
            systemVersion = device.systemVersion,
            applicationVersion = device.appVersion,
        )
        if (result is TdlResult.Failure) {
            _auth.value = AuthState.Failed(L.errorsTdlibParameters(result.message))
            return
        }
        // Both are answered from TDLib's own database, so they work with no line to Telegram:
        // the network this device is on, and whether a stored proxy is switched on.
        scope.launch { tellNetworkType(td) }
        scope.launch { refreshProxyOn(td) }
    }

    private suspend fun onReady(td: TdlClient) {
        // Cheap wins on a 1 GB stick: no animated stickers or presence chatter we never render.
        runCatching {
            td.setOption("ignore_inline_thumbnails", OptionValueBoolean(false))
            td.setOption("disable_animated_emoji", OptionValueBoolean(true))
            td.setOption("ignore_background_updates", OptionValueBoolean(true))
            td.setOption("message_unload_delay", OptionValueInteger(300))
        }
    }

    /** Submits the two-step verification password. Returns null on success, an error otherwise. */
    suspend fun submitPassword(password: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.Password ?: AuthState.Password("")
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.checkAuthenticationPassword(password) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = asked.copy(wrong = true)
                if (result.message == "PASSWORD_HASH_INVALID") L.errorsWrongPassword else Failures.humanise(result.message)
            }
        }
    }

    /**
     * "Forgot password" on the two-step verification step: Telegram mails a recovery code to the
     * account's recovery address ([AuthState.Password.recoveryEmail]). Null on success, with the
     * screen moved to [AuthState.PasswordRecovery]; an error otherwise.
     */
    suspend fun forgotPassword(): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.Password ?: return null
        val pattern = asked.recoveryEmail ?: return L.loginRecoveryNoEmail
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.requestAuthenticationPasswordRecovery() }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> {
                _auth.value = AuthState.PasswordRecovery(pattern)
                null
            }
            is TdlResult.Failure -> Failures.humanise(result.message)
        }
    }

    /** Checks the mailed recovery code; on success the screen asks for a new password, or none. */
    suspend fun submitRecoveryCode(code: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.PasswordRecovery ?: return null
        val clean = code.trim()
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.checkAuthenticationPasswordRecoveryCode(clean) }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> {
                _auth.value = asked.copy(code = clean, wrong = false)
                null
            }
            is TdlResult.Failure -> {
                _auth.value = asked.copy(wrong = true)
                if (result.message.contains("CODE_INVALID")) L.errorsWrongCode else Failures.humanise(result.message)
            }
        }
    }

    /**
     * Finishes the recovery with [newPassword] and its [hint]. A blank password turns two-step
     * verification off, which the screen says in so many words before this is called.
     */
    suspend fun finishPasswordRecovery(newPassword: String, hint: String): String? {
        val td = current ?: return L.errorsNotConnected
        val asked = _auth.value as? AuthState.PasswordRecovery ?: return null
        val code = asked.code ?: return null
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
            td.recoverAuthenticationPassword(code, newPassword, if (newPassword.isEmpty()) "" else hint.trim())
        } ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> Failures.humanise(result.message)
        }
    }

    /** Back from the recovery to the password question, which TDLib never left. */
    suspend fun backToPassword() {
        if (_auth.value !is AuthState.PasswordRecovery) return
        replay()
    }

    /**
     * The last way in for somebody with neither the password nor a recovery email: Telegram's own
     * account reset, which deletes the account's messages and chats. Only ever called after the
     * screen has said exactly that and the viewer has confirmed it. A recently used account is
     * reset only after a waiting period; Telegram then refuses with `2FA_CONFIRM_WAIT_<seconds>`,
     * which comes back here as the sentence saying how long.
     */
    suspend fun resetAccount(): String? {
        val td = current ?: return L.errorsNotConnected
        if (_auth.value !is AuthState.Password) return null
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.deleteAccount("Forgot password", "") }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> SignInWaits.resetWaitSeconds(result.message)
                ?.let { L.loginResetAccountWait(Failures.waitPhrase(it)) }
                ?: Failures.humanise(result.message)
        }
    }

    /**
     * Asks Telegram to reset the login email of an account it signs in by email
     * ([EmailReset.Available]). TDLib answers with a new email code state that carries the wait.
     */
    suspend fun resetEmailAddress(): String? {
        val td = current ?: return L.errorsNotConnected
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) { td.resetAuthenticationEmailAddress() }
            ?: return L.errorsNoAnswer
        return when (result) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> Failures.humanise(result.message)
        }
    }

    /**
     * TDLib's layout for a national number being typed after [dial]: separators where its country
     * puts them and a "-" for each digit to come ([PhoneFormat]). Null when it has none. Answered
     * from TDLib's own tables, before sign in and with no line to Telegram.
     */
    suspend fun formatPhone(dial: String, national: String): String? {
        val td = current ?: return null
        if (dial.isEmpty()) return null
        return td.getPhoneNumberInfo("+$dial$national").valueOrNull
            ?.takeIf { it.countryCallingCode == dial }
            ?.formattedPhoneNumber
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The country TDLib places a number in, by its area code where a calling code is shared
     * ("+1 204..." is Canada, "+44 1534..." Jersey). Blank when it cannot tell. From TDLib's own
     * tables, before sign in and with no line to Telegram.
     */
    suspend fun phoneCountry(digits: String): String {
        val td = current ?: return ""
        if (digits.isEmpty()) return ""
        return td.getPhoneNumberInfo("+$digits").valueOrNull?.country?.countryCode.orEmpty()
    }

    suspend fun logOut() {
        logOutAsked = true
        _auth.value = AuthState.Connecting
        runCatching { current?.logOut() }
    }

    /**
     * Abandons a half-finished sign-in and goes back to the choice of method, or straight to [next].
     *
     * The password and code steps are dead ends otherwise: somebody who scanned with the wrong
     * account or cannot remember the password has nothing to press. Logging out here throws away
     * an attempt rather than an account, since nobody is signed in yet.
     */
    /**
     * A new QR code on request ("Get a new code"). TDLib takes requestQrCodeAuthentication only
     * from its first question, not while a code is up, so the sign in starts over with the QR
     * method kept, which asks for a fresh code at once.
     */
    suspend fun refreshQrCode() = restartSignIn(SignInMethod.Qr)

    suspend fun restartSignIn(next: SignInMethod = SignInMethod.Undecided) {
        method = next
        methodAfterRestart = next
        logOutAsked = true
        _auth.value = AuthState.Connecting
        // No client to log out of: the last one could not start, and this is the Try again.
        clientRetry?.let {
            it.complete(Unit)
            return
        }
        runCatching { current?.logOut() }
    }

    /**
     * Starts a new client when the sign in has been stuck on Connecting ([signInStalled]).
     *
     * A request TDLib cannot send stays queued, and asking again only queues a second one, so the
     * client is closed and a fresh one opened over the same database, keeping the chosen method.
     * Nobody is signed in at this point, so nothing is lost. If TDLib never reports the old client
     * closed, the new one is started anyway after [RESTART_TIMEOUT_MS], as [restart] does.
     *
     * Anywhere else (a sign in screen, or a signed in session that has lost its line) the client
     * is kept and only its connections are reopened, by telling it the network type again.
     */
    suspend fun retryConnecting() {
        _signInStalled.value = false
        _unreachable.value = false
        connectAttempt.value++
        clientRetry?.let {
            it.complete(Unit)
            return
        }
        val td = current ?: return
        if (_auth.value !is AuthState.Connecting) {
            // On a sign in screen with no line: the client is fine, its sockets are not. Telling
            // it the network again reopens every connection at once instead of after its backoff.
            Logger.i(TAG, "no line to Telegram; reopening connections")
            tellNetworkType(td, force = true)
            return
        }
        Logger.i(TAG, "sign in stalled on Connecting; starting a new client")
        replaceClient(td)
    }

    /**
     * A video's bytes stopped coming ([OpeningStall.nudgeDue]): the connections reopened once, as
     * the stall sheet's Try again does, before the sheet is shown at all. TDLib's download carries
     * on over the new connections by itself.
     */
    fun nudgeForStream() {
        Logger.i(TAG, "a video's bytes stopped; reopening connections")
        scope.launch { runCatching { retryConnecting() } }
    }

    /**
     * Closes [td] and opens a fresh client over the same database, keeping the chosen method; if
     * TDLib never reports [td] closed, the new one is started anyway after [RESTART_TIMEOUT_MS].
     */
    private suspend fun replaceClient(td: TdlClient) {
        methodAfterRestart = method
        retiring = td
        val before = _session.value?.generation
        val signal = closedSignal
        runCatching { td.close() }
        val replaced = withTimeoutOrNull(RESTART_TIMEOUT_MS) {
            _session.first { it != null && it.generation != before }
        }
        if (replaced == null) {
            Logger.w(TAG, "TDLib did not report the stalled client closed; starting a new one anyway")
            signal?.complete(Unit)
        }
    }

    /**
     * Steps back from the number entry to the choice of method.
     *
     * Nothing has been sent to Telegram yet at that point, so unlike [restartSignIn] this is a
     * local change of mind and TDLib stays exactly where it is.
     */
    suspend fun cancelPhoneEntry() {
        method = SignInMethod.Undecided
        replay()
    }

    /**
     * What the platform says about its network, each time it changes. TDLib cannot see the network
     * for itself on Android: told nothing, a switch from Wi-Fi to mobile data leaves it waiting on
     * dead sockets until its own backoff runs out. Told, it reopens them at once. Only a real
     * switch is passed on ([NetworkFacts.isSwitchFrom]), since every call drops every connection,
     * and losing the network is not, since TDLib told it has none would stop trying altogether.
     */
    fun onNetworkChanged(facts: NetworkFacts) {
        val before = _network.value
        _network.value = facts
        if (!facts.isSwitchFrom(before)) return
        // Another network: what the last one showed says nothing about this one.
        _lineEvidence.value = LineEvidence()
        val td = current ?: return
        scope.launch { tellNetworkType(td) }
    }

    /**
     * The app came into view ([visible]) or left it. Coming back with no line, TDLib is told the
     * network again, which reopens its connections at once rather than after the backoff it built
     * up while the app was in the background: what Telegram's own apps do on resume. A line that
     * is already up is left alone, since the call drops every connection.
     */
    fun onForegroundChanged(visible: Boolean) {
        if (foreground.value == visible) return
        foreground.value = visible
        if (!visible) return
        foregroundEpoch.value++
        val td = current ?: return
        if (_auth.value !is AuthState.Ready || _link.value.isUp) return
        Logger.i(TAG, "back in view with no line; reopening connections")
        scope.launch { tellNetworkType(td, force = true) }
    }

    private suspend fun tellNetworkType(td: TdlClient, force: Boolean = false) {
        val facts = _network.value
        // The desktop knows nothing about its network, and TDLib's default is already "other";
        // a Retry ([force]) still says it, for the reopened connections.
        if (!facts.known && !force) return
        val type: NetworkType = when (facts.kind) {
            NetworkKind.WiFi -> NetworkTypeWiFi()
            NetworkKind.Mobile -> NetworkTypeMobile()
            NetworkKind.Roaming -> NetworkTypeMobileRoaming()
            NetworkKind.Ethernet, NetworkKind.Other -> NetworkTypeOther()
        }
        val result = runCatching { td.setNetworkType(type) }.getOrNull()
        if (result is TdlResult.Failure) Logger.w(TAG, "setNetworkType failed: ${result.message}")
    }

    /**
     * The proxies TDLib has stored, which it keeps in its own database and so across restarts and
     * sign outs. Usable before sign in, which is the point: where Telegram is blocked the sign in
     * cannot happen without one. Empty with no client.
     */
    suspend fun proxies(): List<ProxyEntry> {
        val td = current ?: return emptyList()
        val list = td.getProxies().valueOrNull?.proxies.orEmpty().map(ProxyEntry::of)
        _proxyOn.value = list.any { it.enabled }
        return list
    }

    /** Stores [spec] and, with [enable], switches TDLib over to it. Null on success, an error otherwise. */
    suspend fun addProxy(spec: ProxySpec, enable: Boolean = true): String? {
        val td = current ?: return L.errorsNotConnected
        if (!spec.isComplete) return L.proxyFormInvalid
        val result = td.addProxy(spec.toTdlib(), enable, "")
        if (result is TdlResult.Failure) return Failures.humanise(result.message)
        afterProxyChange(td)
        return null
    }

    suspend fun enableProxy(id: Int): String? {
        val td = current ?: return L.errorsNotConnected
        val result = td.enableProxy(id)
        if (result is TdlResult.Failure) return Failures.humanise(result.message)
        afterProxyChange(td)
        return null
    }

    /** Back to a direct connection; the proxies stay stored. */
    suspend fun disableProxy(): String? {
        val td = current ?: return L.errorsNotConnected
        val result = td.disableProxy()
        if (result is TdlResult.Failure) return Failures.humanise(result.message)
        afterProxyChange(td)
        return null
    }

    suspend fun removeProxy(id: Int): String? {
        val td = current ?: return L.errorsNotConnected
        val result = td.removeProxy(id)
        if (result is TdlResult.Failure) return Failures.humanise(result.message)
        afterProxyChange(td)
        return null
    }

    /**
     * How long Telegram takes to answer through [spec], in milliseconds, or null with the reason
     * when it does not. TDLib gives up on its own; the ceiling is for a request it never answers.
     */
    suspend fun pingProxy(spec: ProxySpec): Pair<Long?, String?> {
        val td = current ?: return null to L.errorsNotConnected
        val result = withTimeoutOrNull(PING_TIMEOUT_MS) { td.pingProxy(spec.toTdlib()) }
            ?: return null to L.proxyPingFailed
        return when (result) {
            is TdlResult.Success -> (result.result.seconds * 1000).toLong() to null
            is TdlResult.Failure -> {
                Logger.i(TAG, "proxy ping failed: ${result.message}")
                null to L.proxyPingFailed
            }
        }
    }

    /** A proxy switched on or off is a new way to Telegram, so the sign in's wait counts again. */
    private suspend fun afterProxyChange(td: TdlClient) {
        refreshProxyOn(td)
        _signInStalled.value = false
        connectAttempt.value++
    }

    private suspend fun refreshProxyOn(td: TdlClient) {
        val list = td.getProxies().valueOrNull?.proxies ?: return
        _proxyOn.value = list.any { it.isEnabled }
    }

    /** Waits for an authorized client and returns the generation it belongs to. */
    suspend fun awaitAuthorizedSession(): TdSession =
        combine(_auth, _session) { auth, session ->
            session?.takeIf { auth is AuthState.Ready }
        }.first { it != null }!!

    /** Remote-only work waits without a timeout until this exact session has a connection. */
    suspend fun awaitConnectedSession(): TdSession {
        while (true) {
            val session = awaitAuthorizedSession()
            _connected.first { it }
            if (session.isCurrent()) return session
        }
    }

    /**
     * The same wait, given up on after [timeoutMs].
     *
     * The unbounded version is right for background work and wrong for anything a viewer is
     * watching a spinner for: on a device that never gets a socket it waits forever. Null means
     * the time ran out, which callers turn into an offline state with a Retry rather than a hang.
     */
    suspend fun awaitConnectedSessionOrNull(timeoutMs: Long = CONNECT_TIMEOUT_MS): TdSession? =
        withTimeoutOrNull(timeoutMs) { awaitConnectedSession() }

    /**
     * When Telegram's flood wait expires, as a wall-clock stamp, or zero when nothing is waiting.
     *
     * Recorded rather than only humanised, so a manual Refresh can decline to make things worse.
     * Pressing it during a flood wait cannot succeed, and every press extends the wait.
     */
    @Volatile
    private var floodWaitUntilMs = 0L

    /** Records a flood wait if [message] carries one. Returns the seconds Telegram named. */
    fun noteFailure(message: String?): Int? {
        val seconds = Failures.floodWaitSeconds(message) ?: return null
        floodWaitUntilMs = System.currentTimeMillis() + seconds * 1000L
        return seconds
    }

    /** How long a caller should hold off, in seconds; zero when there is nothing to wait for. */
    fun floodWaitRemainingSeconds(): Int {
        val left = floodWaitUntilMs - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    suspend fun storageUsedBytes(): Long =
        current?.getStorageStatisticsFast()?.valueOrNull?.filesSize ?: 0L

    /**
     * The video a stored row points at, asked for again from Telegram.
     *
     * Continue watching keeps a TDLib file id, and a file id is only good for the instance that
     * issued it: after a restart, a re-login or a database rebuild it resolves to nothing or to a
     * different file. TDLib also refuses to download a file it cannot trace back to a message it
     * has seen this session.
     *
     * Fetching the message fixes both: the id comes back current, and TDLib has the message as the
     * file's source, so the download goes through. Null when the message is gone or unreachable,
     * which leaves the caller with the stored row: that copy still plays if it is already on disk.
     */
    suspend fun refreshMedia(chatId: Long, messageId: Long): MediaItem? {
        val td = current ?: return null
        return (ChatRepository(td).lookupMedia(chatId, messageId) as? MessageLookup.Found)?.item
    }

    /**
     * [refreshMedia] with the reason when there is no video: gone for good (reported to
     * [GoneVideos]) or not answered within [timeoutMs], which a caller treats as "keep what is
     * stored and carry on" (HX21). For a kept video being opened, where a wait without end would be
     * a spinner without end.
     */
    suspend fun lookupMedia(chatId: Long, messageId: Long, timeoutMs: Long = LOOKUP_TIMEOUT_MS): MessageLookup {
        val td = current ?: return MessageLookup.Unreachable
        return try {
            withTimeoutOrNull(timeoutMs) { ChatRepository(td).lookupMedia(chatId, messageId) } ?: MessageLookup.Unreachable
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.w(TAG, "Looking up $chatId/$messageId failed", failure)
            MessageLookup.Unreachable
        }
    }

    /**
     * Whether the video's chat still allows keeping a copy, asked again at the moment a copy would
     * be made: a channel can turn "restrict saving content" on after a download was queued. True
     * when Telegram cannot be reached, because the download was allowed when it was asked for.
     */
    suspend fun maySave(chatId: Long, messageId: Long): Boolean {
        if (chatId == 0L || messageId == 0L) return true
        return runCatching { refreshMedia(chatId, messageId) }.getOrNull()?.canBeSaved ?: true
    }

    /** Verifies both TDLib's flag and the actual file before promising offline playback. */
    suspend fun localFileAvailability(fileId: Int): LocalFileAvailability {
        val td = current ?: return LocalFileAvailability.Missing
        val file = td.getFile(fileId).valueOrNull ?: return LocalFileAvailability.Missing
        val local = file.local
        val diskFile = local.path.takeIf { it.isNotBlank() }?.let(::File)
        return LocalFilePolicy.evaluate(
            downloadCompleted = local.isDownloadingCompleted,
            pathPresent = diskFile != null,
            regularFile = diskFile?.isFile == true,
            length = diskFile?.length() ?: 0,
            size = file.size,
            expectedSize = file.expectedSize,
        )
    }

    /**
     * The file id a stored row points at, as this session knows it.
     *
     * A TDLib file id belongs to the instance that issued it. After a restart, a re-login or a
     * database rebuild the id in a saved row resolves to nothing, and everything measured from it
     * reads as zero: rows vanish from Downloads, and the bytes still on the disk are attributed to
     * whatever bucket owns the leftovers. The message is the stable identity, so a stale id is
     * re-resolved from it.
     *
     * The stored id is tried first, because it is right for the whole of the session that wrote it
     * and costs one call to find out. Falls back to the stored id when the message cannot be
     * reached, which leaves the caller no worse off than before.
     */
    suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int, quality: Int = 0): Int {
        if (localDownloadedBytes(storedFileId) > 0) return storedFileId
        // A Telegram copy (CP80) asked for by its height: that copy's file in the message as it is now.
        return refreshMedia(chatId, messageId)?.let { TelegramQualities.fileFor(it, quality) } ?: storedFileId
    }

    /**
     * Whether [fileId] is still the file of a video [sizeBytes] long in this session. An id from a
     * stored row (Continue watching, Watch later) belongs to the TDLib instance that wrote it; after
     * a restart the same number can name a different file, a thumbnail or another video, and a
     * resume point past that file's end then reads as the end of the video. True when the size is
     * not known on either side, so the caller is no worse off than before.
     */
    suspend fun fileIdFits(fileId: Int, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        val td = current ?: return true
        val file = td.getFile(fileId).valueOrNull ?: return false
        val size = if (file.size > 0) file.size else file.expectedSize
        return size == sizeBytes
    }

    /** How much of a file is already on disk, whether or not the download ever finished. */
    suspend fun localDownloadedBytes(fileId: Int): Long {
        val td = current ?: return 0L
        return td.getFile(fileId).valueOrNull?.local?.downloadedSize ?: 0L
    }

    /**
     * Deletes one downloaded file and leaves the rest of the cache alone.
     *
     * This is what the Downloads screen removes a row with. `deleteFile` only touches the copy on
     * disk: the message it came from is untouched, so the video can be downloaded again by playing
     * it.
     */
    suspend fun deleteFile(fileId: Int) {
        val td = current ?: return
        runCatching { td.deleteFile(fileId) }
    }

    /**
     * Where a finished file actually is, for the apps this one hands a video to.
     *
     * Null unless the whole thing is on disk: a partial path is a path to a video that stops in
     * the middle, and the Share sheet has no way to say so.
     */
    suspend fun localFilePath(fileId: Int): String? {
        if (localFileAvailability(fileId) != LocalFileAvailability.Complete) return null
        val td = current ?: return null
        return td.getFile(fileId).valueOrNull?.local?.path?.takeIf { it.isNotBlank() }
    }

    /**
     * Where a file is on disk, finished or not.
     *
     * [localFilePath] deliberately refuses a partial file. This one answers anyway, for the
     * Downloads screen, which walks the disk looking for videos nothing in the app can name: a
     * half-loaded download whose path was unknown would be counted there as an orphan.
     */
    suspend fun localPathAnyway(fileId: Int): String? {
        val td = current ?: return null
        return td.getFile(fileId).valueOrNull?.local?.path?.takeIf { it.isNotBlank() }
    }

    suspend fun isFileCached(fileId: Int): Boolean =
        localFileAvailability(fileId) == LocalFileAvailability.Complete

    /**
     * Drops every cached video, document and animation.
     *
     * A `size` of zero means "keep nothing", and `immunityDelay` of zero waives TDLib's usual
     * grace period for recently touched files. Without that, the video the viewer just finished
     * would survive and the space would not actually come back.
     *
     * Photos and thumbnails are untouched: they are tiny and re-fetching them would make the
     * whole browse grid grey for no gain.
     */
    suspend fun clearMediaCache(td: TdlClient = client) {
        td.optimizeStorage(
            size = 0,
            ttl = Int.MAX_VALUE,
            count = Int.MAX_VALUE,
            immunityDelay = 0,
            fileTypes = arrayOf(FileTypeVideo(), FileTypeDocument(), FileTypeAnimation()),
            chatIds = longArrayOf(),
            excludeChatIds = longArrayOf(),
            returnDeletedFileStatistics = false,
            chatLimit = 0,
        )
    }

    /**
     * Pictures, thumbnails and the rest of what is not a video, gone.
     *
     * Nothing here may touch a video: the type filter is the same one the automatic trim uses, and
     * videos are governed by ownership in [WatchCache], not by any button on the Settings screen.
     */
    suspend fun clearPicturesAndPreviews() {
        val td = current ?: return
        runCatching {
            td.optimizeStorage(
                size = 0,
                ttl = Int.MAX_VALUE,
                count = Int.MAX_VALUE,
                immunityDelay = 0,
                fileTypes = NON_VIDEO_TYPES,
                chatIds = longArrayOf(),
                excludeChatIds = longArrayOf(),
                returnDeletedFileStatistics = false,
                chatLimit = 0,
            )
        }
    }

    /**
     * Everything TMPlayer is holding, of every kind, gone.
     *
     * [clearMediaCache] spares photos and thumbnails, which is right for the button that means
     * "delete the videos" and wrong for the one that means "I need this space back". So: no
     * immunity, no type filter, no ceiling. The browse grid goes grey for a moment and refetches.
     */
    suspend fun clearEverythingCached(): Boolean =
        optimizeStorage(sizeBytes = 0, ttlSeconds = Int.MAX_VALUE, immunityDelaySeconds = 0, fileTypes = emptyArray())

    /**
     * TDLib's own garbage collector, run once with the limits given.
     *
     * Files go oldest first until the cache is under [sizeBytes] (or at once, at zero), and any
     * not touched for [ttlSeconds] go regardless; nothing touched in the last
     * [immunityDelaySeconds] is taken, and only files of [fileTypes] are considered (all of them
     * when empty). TDLib never asks whether a file is a download, which is why a download must
     * not be in its files directory by the time this runs.
     *
     * @return whether TDLib carried it out; false with no client, or when it refused.
     */
    suspend fun optimizeStorage(
        sizeBytes: Long,
        ttlSeconds: Int,
        immunityDelaySeconds: Int,
        fileTypes: Array<FileType>,
    ): Boolean {
        val td = current ?: return false
        return runCatching {
            td.optimizeStorage(
                size = sizeBytes,
                ttl = ttlSeconds,
                count = Int.MAX_VALUE,
                immunityDelay = immunityDelaySeconds,
                fileTypes = fileTypes,
                chatIds = longArrayOf(),
                excludeChatIds = longArrayOf(),
                returnDeletedFileStatistics = false,
                chatLimit = 0,
            ) is TdlResult.Success
        }.getOrDefault(false)
    }

    /**
     * Keeps the cache under a ceiling, quietly, instead of waiting to be emptied by hand.
     *
     * Runs on launch, takes the oldest first and stops at the ceiling. Anything touched in the
     * last few minutes is left alone, so this cannot delete the video the player is streaming.
     *
     * It must not touch videos. TDLib evicts oldest first and has never heard of a download: asked
     * to get under a ceiling it would take a video the viewer ticked as readily as last night's
     * cache, leaving the Downloads row pointing at a file of zero bytes. So the ceiling here is
     * enforced only over files that have no owner: pictures, thumbnails, stickers, stories. Videos
     * are governed by ownership instead, in [WatchCache], which knows which the viewer asked for.
     */
    suspend fun trimStorage(maxBytes: Long = cacheCeilingBytes()) {
        val td = current ?: return
        runCatching {
            td.optimizeStorage(
                size = maxBytes,
                ttl = CACHE_TTL_SECONDS,
                count = Int.MAX_VALUE,
                immunityDelay = CACHE_IMMUNITY_SECONDS,
                fileTypes = NON_VIDEO_TYPES,
                chatIds = longArrayOf(),
                excludeChatIds = longArrayOf(),
                returnDeletedFileStatistics = false,
                chatLimit = 0,
            )
        }
    }

    /**
     * Stops an in-flight download and keeps whatever already landed.
     *
     * `onlyIfPending = false` because the download being cancelled is nearly always the active
     * one: a request that has not started yet costs nothing to leave alone.
     */
    suspend fun cancelDownload(fileId: Int) {
        val td = current ?: return
        runCatching { td.cancelDownloadFile(fileId, onlyIfPending = false) }
    }

    /**
     * The same cancellation, for callers that are themselves being cancelled.
     *
     * A thumbnail scrolled off the screen is exactly that case: the coroutine waiting on it is
     * gone, so there is nothing left to suspend in, and TDLib would otherwise keep fetching a
     * picture for a row nobody is looking at.
     */
    fun cancelDownloadInBackground(fileId: Int) {
        scope.launch { cancelDownload(fileId) }
    }

    /**
     * The signed-in account's own id, which is also the id of its Saved Messages chat.
     *
     * Cached, because it is asked for on the chat-list path and that path runs several times on a
     * cold start. It cannot change without the client being torn down and rebuilt, which is what
     * clears it, so a cached answer is not a stale one.
     */
    @Volatile
    private var cachedMyId = 0L

    suspend fun myId(): Long {
        cachedMyId.takeIf { it != 0L }?.let { return it }
        val id = client.getMe().valueOrNull?.id ?: return 0L
        cachedMyId = id
        return id
    }

    /** The signed-in account, for the avatar and name in the navigation rail. */
    suspend fun me(session: TdSession): Account? {
        val user = session.client.getMe().valueOrNull ?: return null
        if (!session.isCurrent()) return null
        val name = listOf(user.firstName, user.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Telegram" }
        return Account(
            name = name,
            username = user.usernames?.activeUsernames?.firstOrNull().orEmpty(),
            miniThumbnail = user.profilePhoto?.minithumbnail?.data,
            photoFileId = user.profilePhoto?.small?.id ?: 0,
        )
    }

    /** Room for a burst of posts between two windows of a listing; the oldest give way after that. */
    private const val VIDEO_CHANGE_BUFFER = 256

    /** Long enough for a slow stick on a cold morning, short enough not to read as a hang. */
    private const val CONNECT_TIMEOUT_MS = 30_000L

    /** How long [lookupMedia] waits for Telegram before the caller carries on with what it stored. */
    const val LOOKUP_TIMEOUT_MS = 6_000L

    /**
     * How long the sign in waits on Telegram before it says so and offers help. A first launch
     * on a working line is through in a few seconds; past this the viewer is wondering already.
     */
    const val SIGN_IN_STALL_MS = 12_000L

    /** How long a logout runs before its connections are reopened ([watchLogOut]). */
    private const val LOG_OUT_NUDGE_MS = 4_000L

    /** How much longer it runs after that before the client is replaced. */
    private const val LOG_OUT_GIVE_UP_MS = 6_000L

    /** How long TDLib is given to say its authorization state when asked directly ([confirmSignedIn]). */
    private const val AUTH_CHECK_MS = 5_000L

    /** How often a refused request may have the session checked ([noteRequestFailure]). */
    private const val AUTH_CHECK_GAP_MS = 30_000L

    /** How often a refused request may have a new client started over a session TDLib calls signed in. */
    private const val AUTH_FRESH_CLIENT_GAP_MS = 10 * 60_000L

    /** How long a signed in session goes without a line before [unreachable] says so. */
    private const val UNREACHABLE_MS = 20_000L

    /** How long a typed answer (a number, an email) waits for Telegram before the screen says so. */
    private const val SUBMIT_TIMEOUT_MS = 20_000L

    /** Past this a proxy is not answering, whatever TDLib is still doing about it. */
    private const val PING_TIMEOUT_MS = 15_000L

    /** How long [restart] waits for TDLib to say the old client is closed before moving on. */
    private const val RESTART_TIMEOUT_MS = 20_000L

    /**
     * What TMPlayer is allowed to keep on disk before the oldest of it starts going, measured
     * against the disk it is actually installed on. See [CacheShelf.ceiling].
     */
    fun cacheCeilingBytes(): Long = currentPaths?.let { CacheShelf.ceiling(it.disk().totalBytes) }
        ?: CacheShelf.MAX_CEILING_BYTES

    /** A month. Anything untouched for longer is not a cache, it is a leak. */
    private const val CACHE_TTL_SECONDS = 30 * 24 * 60 * 60

    /** Recently touched files are left alone, so this cannot delete what is playing. */
    private const val CACHE_IMMUNITY_SECONDS = 10 * 60

    /**
     * Everything the automatic trim is allowed to take: the files nobody ticked and nobody misses.
     *
     * The three that are missing are the three a video can be filed under, [FileTypeVideo],
     * [FileTypeDocument] and [FileTypeAnimation]. A video is either the cache's or the viewer's,
     * and [WatchCache] is the only thing in this app that knows which. See [trimStorage].
     */
    private val NON_VIDEO_TYPES: Array<FileType> = arrayOf(
        FileTypePhoto(),
        FileTypeThumbnail(),
        FileTypeProfilePhoto(),
        FileTypeSticker(),
        FileTypeAudio(),
        FileTypePhotoStory(),
        FileTypeVideoStory(),
        FileTypeVideoNote(),
    )

    internal fun isCurrent(session: TdSession): Boolean =
        current === session.client && generation.get() == session.generation && _auth.value is AuthState.Ready
}

/** A client plus its identity, so late work from a logged-out client cannot update the UI. */
class TdSession internal constructor(
    val client: TdlClient,
    val generation: Long,
) {
    fun isCurrent(): Boolean = Td.isCurrent(this)
}

/** Who is signed in. Shown in the rail so it is obvious whose library this is. */
data class Account(
    val name: String,
    val username: String,
    val miniThumbnail: ByteArray?,
    val photoFileId: Int,
) {
    override fun equals(other: Any?) = other is Account && other.name == name &&
        other.username == username && other.photoFileId == photoFileId

    override fun hashCode() = name.hashCode() * 31 + photoFileId
}
