package com.tmplayer.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.tmplayer.BuildConfig
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.CountryInfo
import dev.g000sha256.tdl.dto.PhoneNumberInfo
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.FileTypeAnimation
import dev.g000sha256.tdl.dto.FileTypeDocument
import dev.g000sha256.tdl.dto.FileTypeVideo
import dev.g000sha256.tdl.dto.OptionValueBoolean
import dev.g000sha256.tdl.dto.OptionValueInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 * TDLib owns the session on disk, so there is exactly one client and it lives as long as the
 * app does. Logging out closes the client for good, which is why [clientLoop] can build a
 * replacement; that is the only supported way to log back in.
 */
object Td {

    @Volatile
    private var current: TdlClient? = null

    private val generation = AtomicLong(0)
    private val _session = MutableStateFlow<TdSession?>(null)

    /** Throws if TDLib has not finished starting; every caller here runs after [AuthState.Ready]. */
    val client: TdlClient
        get() = current ?: error("TDLib client is not running")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var lastHandled: AuthorizationState? = null

    /**
     * The way the user chose to sign in, and the deferred that ends the current client's life.
     *
     * Both belong to the login flow rather than to a single update, because the choice has to
     * survive being asked the same TDLib question twice and [replay] has no `closed` of its own.
     */
    private var method = SignInMethod.Undecided

    @Volatile
    private var closedSignal: CompletableDeferred<Unit>? = null

    private val _auth = MutableStateFlow<AuthState>(AuthState.Connecting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    /**
     * Whether TDLib currently has a line to Telegram.
     *
     * Signing in is not the same thing as being connected. Coming back to the app after the TV has
     * been asleep, the session is read off disk and the account is ready long before the socket
     * is, and a search sent in that gap comes back empty: not an error, just nothing, which the
     * screens then report as an empty chat.
     */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private lateinit var appContext: Context

    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope.launch { clientLoop() }
    }

    private suspend fun clientLoop() = coroutineScope {
        while (isActive) {
            val closed = CompletableDeferred<Unit>()
            val td = TdlClient.create()
            current = td
            _session.value = TdSession(td, generation.incrementAndGet())
            lastHandled = null
            method = SignInMethod.Undecided
            closedSignal = closed

            val updates = launch {
                td.authorizationStateUpdates.collect { apply(td, it.authorizationState) }
            }
            val connection = launch {
                td.connectionStateUpdates.collect { update ->
                    // "Updating" means the socket is up and TDLib is pulling in what it missed.
                    // Requests are answered from that point on, so it counts as connected; waiting
                    // for it to finish would hold the chat list back on a large account.
                    _connected.value = update.state is ConnectionStateReady ||
                        update.state is ConnectionStateUpdating
                }
            }
            // The first update may already have been emitted before we subscribed, so ask
            // once directly. Both paths go through the same mutex-guarded handler.
            val primer = launch {
                runCatching { td.setLogVerbosityLevel(1) }
                val now = td.getAuthorizationState()
                if (now is TdlResult.Success) apply(td, now.result)
            }

            closed.await()
            updates.cancel()
            connection.cancel()
            // Cancelled with the rest of the generation. It usually finishes long before this,
            // but a client closed while it is still waiting on TDLib would otherwise leave a
            // coroutine holding a dead client, once per sign-in, for the life of the process.
            primer.cancel()
            _connected.value = false
            current = null
            _session.value = null
        }
    }

    private suspend fun apply(td: TdlClient, state: AuthorizationState) = gate.withLock {
        // The update flow and the one-off getAuthorizationState() often deliver the same state
        // back to back at startup. Acting twice would send TDLib its parameters twice and
        // surface the second attempt's error as a login failure. Comparing against only the
        // immediately previous state still lets a genuine repeat through: a QR link that
        // expires and sends us back to WaitPhoneNumber has a different state in between.
        if (state == lastHandled) return@withLock
        lastHandled = state

        val step = AuthReducer.reduce(state, method)
        // Never let a stale "connecting" overwrite a terminal failure the user still needs to read.
        if (_auth.value !is AuthState.Failed || step.state !is AuthState.Connecting) {
            _auth.value = step.state
        }
        Log.i(TAG, "auth: ${state::class.simpleName} -> ${step.state::class.simpleName}")

        when (step.action) {
            AuthAction.SendParameters -> sendParameters(td)
            AuthAction.RequestQrCode -> {
                val result = td.requestQrCodeAuthentication(longArrayOf())
                if (result is TdlResult.Failure) _auth.value = AuthState.Failed(Failures.humanise(result.message))
            }
            AuthAction.OnReady -> onReady(td)
            AuthAction.RecreateClient -> closedSignal?.complete(Unit)
            AuthAction.None -> Unit
        }
    }

    /**
     * Runs the last TDLib state through the reducer again after [method] has moved.
     *
     * TDLib is not told anything here and sends nothing new: the state it is sitting in has simply
     * come to mean something different, because the user has now said which way they are signing
     * in. Clearing [lastHandled] is what lets the same state through the duplicate guard once more.
     */
    private suspend fun replay() {
        val td = current ?: return
        val state = gate.withLock { lastHandled.also { lastHandled = null } } ?: return
        apply(td, state)
    }

    /** The user picked a route on the first login screen. */
    suspend fun chooseSignInMethod(chosen: SignInMethod) {
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
        val td = current ?: return "Not connected"
        return when (val result = td.setAuthenticationPhoneNumber(phoneNumber)) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.Phone(wrong = true)
                if (result.message.contains("PHONE_NUMBER_INVALID")) {
                    "That number isn't one Telegram recognises. Include the country code, as in +44."
                } else {
                    Failures.humanise(result.message)
                }
            }
        }
    }

    /** Submits the login code Telegram sent. Returns null on success, an error otherwise. */
    suspend fun submitCode(code: String): String? {
        val td = current ?: return "Not connected"
        // Copied rather than rebuilt: the screen's delivery route, digit count and resend timer all
        // live in this state, and a fresh Code() would blank them the moment a digit was mistyped.
        val current = _auth.value as? AuthState.Code ?: AuthState.Code("")
        return when (val result = td.checkAuthenticationCode(code)) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = current.copy(wrong = true)
                when {
                    result.message.contains("PHONE_CODE_INVALID") -> "Wrong code"
                    result.message.contains("PHONE_CODE_EXPIRED") ->
                        "That code has expired. Start over to have a new one sent."
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
     * Telegram's own reading of a half-typed number: which country it belongs to, and how that
     * country groups its digits.
     *
     * Worth the round trip rather than a local formatter, because this is the same table the
     * Telegram apps format against, so a number looks here exactly as it looks there.
     */
    suspend fun phoneNumberInfo(phoneNumber: String): PhoneNumberInfo? =
        current?.getPhoneNumberInfo(phoneNumber)?.valueOrNull

    /**
     * Asks Telegram to send the code again, by whatever route it has decided is next.
     *
     * The reason is left to TDLib's default: the app has not been told the code failed to arrive,
     * only that the user pressed the button, and guessing at a reason would be inventing one.
     */
    suspend fun resendCode(): String? {
        val td = current ?: return "Not connected"
        return when (val result = td.resendAuthenticationCode()) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> Failures.humanise(result.message)
        }
    }

    private suspend fun sendParameters(td: TdlClient) {
        if (BuildConfig.TG_API_ID == 0) {
            _auth.value = AuthState.Failed(
                "No Telegram API credentials in this build. Add TG_API_ID and TG_API_HASH to local.properties and rebuild. See the README.",
            )
            return
        }
        val result = td.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = File(appContext.filesDir, "tdlib").absolutePath,
            filesDirectory = File(appContext.filesDir, "tdlib-files").absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = BuildConfig.TG_API_ID,
            apiHash = BuildConfig.TG_API_HASH,
            systemLanguageCode = "en",
            deviceModel = Build.MODEL ?: "Android TV",
            systemVersion = Build.VERSION.RELEASE ?: "",
            applicationVersion = BuildConfig.VERSION_NAME,
        )
        if (result is TdlResult.Failure) {
            _auth.value = AuthState.Failed("TDLib rejected its parameters: ${result.message}")
        }
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
        val td = current ?: return "Not connected"
        val hint = (_auth.value as? AuthState.Password)?.hint.orEmpty()
        return when (val result = td.checkAuthenticationPassword(password)) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.Password(hint, wrong = true)
                if (result.message == "PASSWORD_HASH_INVALID") "Wrong password" else Failures.humanise(result.message)
            }
        }
    }

    suspend fun logOut() {
        _auth.value = AuthState.Connecting
        runCatching { current?.logOut() }
    }

    /**
     * Abandons a half-finished sign-in and goes back to the choice of method.
     *
     * The password and code steps are dead ends otherwise: somebody who scanned with the wrong
     * account, who cannot remember the password, or who typed the wrong number, has nothing to
     * press. Logging out here throws away an attempt rather than an account, since nobody is
     * signed in yet.
     */
    suspend fun restartSignIn() {
        method = SignInMethod.Undecided
        _auth.value = AuthState.Connecting
        runCatching { current?.logOut() }
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
     * The unbounded version is right for work that has nothing else to do, and wrong for anything
     * a viewer is watching a spinner for: on a device that never gets a socket it waits forever,
     * and the screen in front of them says "connecting" until they close the app. Null means the
     * time ran out, which callers turn into an offline state with a Retry rather than a hang.
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

    /** How much of a file is already on disk, whether or not the download ever finished. */
    suspend fun localDownloadedBytes(fileId: Int): Long {
        val td = current ?: return 0L
        return td.getFile(fileId).valueOrNull?.local?.downloadedSize ?: 0L
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
     * Keeps the cache under a ceiling, quietly, instead of waiting to be emptied by hand.
     *
     * [clearMediaCache] is the only thing that ever removed anything, it is a button, and it takes
     * everything at once. Photos and thumbnails were exempt from even that, so they grew for the
     * life of the install: on a stick with eight gigabytes that is the disk filling up with
     * pictures of chats nobody has opened in a year. This runs on launch, takes the oldest first
     * and stops at the ceiling, and it leaves anything touched in the last few minutes alone so it
     * cannot delete the video currently being streamed out from under the player.
     */
    suspend fun trimStorage(maxBytes: Long = CACHE_CEILING_BYTES) {
        val td = current ?: return
        runCatching {
            td.optimizeStorage(
                size = maxBytes,
                ttl = CACHE_TTL_SECONDS,
                count = Int.MAX_VALUE,
                immunityDelay = CACHE_IMMUNITY_SECONDS,
                // Every type, photos and thumbnails included, which is the whole difference
                // between this and the button.
                fileTypes = emptyArray(),
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

    /** Long enough for a slow stick on a cold morning, short enough not to read as a hang. */
    private const val CONNECT_TIMEOUT_MS = 30_000L

    /**
     * What TMPlayer is allowed to keep on disk before the oldest of it starts going.
     *
     * Four gigabytes: room for a couple of films and every thumbnail the browser has ever drawn,
     * on a device whose whole disk is eight.
     */
    const val CACHE_CEILING_BYTES = 4L * 1024 * 1024 * 1024

    /** A month. Anything untouched for longer is not a cache, it is a leak. */
    private const val CACHE_TTL_SECONDS = 30 * 24 * 60 * 60

    /** Recently touched files are left alone, so this cannot delete what is playing. */
    private const val CACHE_IMMUNITY_SECONDS = 10 * 60

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
