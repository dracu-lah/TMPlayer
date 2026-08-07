package com.tmplayer.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.tmplayer.BuildConfig
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "Td"

/**
 * The single TDLib connection for the whole process.
 *
 * TDLib owns the session on disk, so there is exactly one client and it lives as long as the
 * app does. Logging out closes the client for good, which is why [clientLoop] can build a
 * replacement — that is the only supported way to log back in.
 */
object Td {

    @Volatile
    private var current: TdlClient? = null

    /** Throws if TDLib has not finished starting; every caller here runs after [AuthState.Ready]. */
    val client: TdlClient
        get() = current ?: error("TDLib client is not running")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    private val _auth = MutableStateFlow<AuthState>(AuthState.Connecting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var settings: SettingsStore

    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        settings = SettingsStore(appContext)
        scope.launch { clientLoop() }
    }

    private suspend fun clientLoop() = coroutineScope {
        while (isActive) {
            val closed = CompletableDeferred<Unit>()
            val td = TdlClient.create()
            current = td

            val updates = launch {
                td.authorizationStateUpdates.collect { apply(td, it.authorizationState, closed) }
            }
            // The first update may already have been emitted before we subscribed, so ask
            // once directly. Both paths go through the same mutex-guarded handler.
            launch {
                runCatching { td.setLogVerbosityLevel(1) }
                val now = td.getAuthorizationState()
                if (now is TdlResult.Success) apply(td, now.result, closed)
            }

            closed.await()
            updates.cancel()
            current = null
        }
    }

    private suspend fun apply(
        td: TdlClient,
        state: AuthorizationState,
        closed: CompletableDeferred<Unit>,
    ) = gate.withLock {
        val step = AuthReducer.reduce(state)
        // Never let a stale "connecting" overwrite a terminal failure the user still needs to read.
        if (_auth.value !is AuthState.Failed || step.state !is AuthState.Connecting) {
            _auth.value = step.state
        }
        Log.i(TAG, "auth: ${state::class.simpleName} -> ${step.state::class.simpleName}")

        when (step.action) {
            AuthAction.SendParameters -> sendParameters(td)
            AuthAction.RequestQrCode -> {
                val result = td.requestQrCodeAuthentication(longArrayOf())
                if (result is TdlResult.Failure) _auth.value = AuthState.Failed(result.message)
            }
            AuthAction.OnReady -> onReady(td)
            AuthAction.RecreateClient -> closed.complete(Unit)
            AuthAction.None -> Unit
        }
    }

    private suspend fun sendParameters(td: TdlClient) {
        if (BuildConfig.TG_API_ID == 0) {
            _auth.value = AuthState.Failed(
                "No Telegram API credentials in this build. Add TG_API_ID and TG_API_HASH to local.properties and rebuild — see the README.",
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
        runCatching { trimCache(td, settings.cacheLimitBytes()) }
    }

    /** Submits the two-step verification password. Returns null on success, an error otherwise. */
    suspend fun submitPassword(password: String): String? {
        val td = current ?: return "Not connected"
        val hint = (_auth.value as? AuthState.Password)?.hint.orEmpty()
        return when (val result = td.checkAuthenticationPassword(password)) {
            is TdlResult.Success -> null
            is TdlResult.Failure -> {
                _auth.value = AuthState.Password(hint, wrong = true)
                if (result.message == "PASSWORD_HASH_INVALID") "Wrong password" else result.message
            }
        }
    }

    suspend fun logOut() {
        _auth.value = AuthState.Connecting
        runCatching { current?.logOut() }
    }

    /** Fire-and-forget cache trim; safe to call from anywhere, does nothing if TDLib is down. */
    fun trimCacheInBackground(store: SettingsStore) {
        scope.launch { runCatching { trimCache(client, store.cacheLimitBytes()) } }
    }

    suspend fun storageUsedBytes(): Long =
        current?.getStorageStatisticsFast()?.valueOrNull?.filesSize ?: 0L

    /**
     * Deletes the oldest cached media until the on-disk footprint fits [limitBytes].
     * TDLib does the accounting; we only choose the ceiling.
     */
    suspend fun trimCache(td: TdlClient = client, limitBytes: Long) {
        td.optimizeStorage(
            size = limitBytes,
            ttl = Int.MAX_VALUE,
            count = Int.MAX_VALUE,
            immunityDelay = 60,
            fileTypes = arrayOf(FileTypeVideo(), FileTypeDocument(), FileTypeAnimation()),
            chatIds = longArrayOf(),
            excludeChatIds = longArrayOf(),
            returnDeletedFileStatistics = false,
            chatLimit = 0,
        )
    }
}
