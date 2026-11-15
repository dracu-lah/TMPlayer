package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.tmplayer.cast.Cast
import com.tmplayer.cast.CastSessionState
import com.tmplayer.data.ChatGate
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.LocalVideo
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.JobFailures
import com.tmplayer.ui.components.showMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a press on a video can go to another app instead of TMPlayer's player (PR4): the one
 * place that reads the Default player setting, checks it against what is installed, and either
 * opens the app, asks with [ExternalChooserSheet], or lets TMPlayer's own player go on.
 *
 * Called from the one place a video starts ([take], from the main screen's `playNow`), and from the
 * video menu's "Open in another app" ([take] with `forceAsk`). The screens it needs (the chooser,
 * the "closed right away" offer) are state here that [ExternalPlayHost] draws, so no caller owns a
 * dialog. The pure decisions are [ExternalPlay]'s and tested there; this is the Android glue.
 *
 * What it never does: open an app while the kids lock is on or a cast is running; start a stream
 * the disk has no room for; keep a stream open once the other app has come back and stopped
 * reading it.
 */
object ExternalPlayRouter {

    private const val TAG = "ExternalPlayRouter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + JobFailures.handler(TAG))

    /** What the chooser is for. [forced] is the viewer's own "Open in another app", never a press on Play. */
    data class Chooser(
        val item: MediaItem,
        val chatTitle: String,
        val apps: List<InstalledPlayers.Found>,
        val gone: PlayerChoice.App?,
        val forced: Boolean,
    )

    /** The app that closed right away, for the offer to play the video in TMPlayer. */
    data class Closed(val item: MediaItem, val chatTitle: String, val appLabel: String)

    private class Sent(
        val item: MediaItem,
        val chatTitle: String,
        val app: ExternalApp,
        val at: Long,
        val durationMs: Long,
        var stopped: Boolean = false,
        var reportedMs: Long? = null,
    )

    private val _chooser = MutableStateFlow<Chooser?>(null)
    val chooser: StateFlow<Chooser?> = _chooser

    private val _closed = MutableStateFlow<Closed?>(null)
    val closed: StateFlow<Closed?> = _closed

    /** Set by [ExternalPlayHost]: starts an intent for a result, and plays a video in TMPlayer's own player. */
    @Volatile
    var launcher: ((Intent) -> Unit)? = null

    @Volatile
    var playBuiltIn: ((MediaItem, String) -> Unit)? = null

    private val bypass = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sent: Sent? = null

    private fun key(item: MediaItem) = "${item.chatId}:${item.messageId}"

    /** The next [take] for this video lets TMPlayer's player have it ("Play in TMPlayer"). */
    fun bypassNext(item: MediaItem) {
        bypass.add(key(item))
    }

    /** Plays [item] in TMPlayer's own player whatever the default says. */
    fun playHere(item: MediaItem, chatTitle: String) {
        bypassNext(item)
        playBuiltIn?.invoke(item, chatTitle)
    }

    /**
     * True when this press was taken over (the chooser is up or another app is opening) and
     * TMPlayer's player must not start; false to carry on as always. Never throws: whatever fails
     * here is a reason to play in TMPlayer.
     */
    suspend fun take(
        context: Context,
        settings: SettingsStore,
        item: MediaItem,
        chatTitle: String,
        forceAsk: Boolean = false,
    ): Boolean {
        if (!forceAsk && bypass.remove(key(item))) return false
        return try {
            takeNow(context, settings, item, chatTitle, forceAsk)
        } catch (e: CancellationException) {
            // The screen left while this ran: the press is abandoned, so TMPlayer's player must not start for it.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not route the press", e)
            false
        }
    }

    private suspend fun takeNow(context: Context, settings: SettingsStore, item: MediaItem, chatTitle: String, forceAsk: Boolean): Boolean {
        val choice = settings.defaultPlayerNow()
        // The common case, a viewer who never changed it: no package query, no cost.
        if (choice == PlayerChoice.TmPlayer && !forceAsk) return false

        val readiness = readiness(settings, item)
        val installed = InstalledPlayers.find(context)
        val plan = ExternalPlay.plan(
            choice = choice,
            installed = installed.map { it.app },
            kidsLocked = ChatGate.allowed.value != null,
            casting = Cast.existing()?.session?.value.let { it != null && it !is CastSessionState.Idle },
            forceAsk = forceAsk,
            needsStream = !readiness.complete,
            cannotHand = item.isJoined && !readiness.complete,
        )
        return when (plan) {
            is ExternalPlay.Plan.BuiltIn -> {
                // A press on Play just plays; the viewer's own "Open in another app" is told why not.
                if (forceAsk) tell(context, plan.why)
                forceAsk
            }
            is ExternalPlay.Plan.Launch -> {
                val found = installed.first { it.app.packageName == plan.app.packageName }
                launch(context, settings, item, chatTitle, found.app, viaDefault = true, fallback = true)
                true
            }
            is ExternalPlay.Plan.Ask -> {
                val apps = plan.apps.mapNotNull { app -> installed.firstOrNull { it.app.packageName == app.packageName } }
                _chooser.value = Chooser(item, chatTitle, apps, plan.gone, forced = forceAsk)
                true
            }
        }
    }

    private fun tell(context: Context, why: ExternalPlay.BuiltInWhy) {
        val text = when (why) {
            ExternalPlay.BuiltInWhy.KidsLock -> L.externalKidsLock
            ExternalPlay.BuiltInWhy.Casting -> L.externalCasting
            ExternalPlay.BuiltInWhy.NoApps -> L.externalNoApps
            ExternalPlay.BuiltInWhy.CannotHand -> L.externalCannotHand
            ExternalPlay.BuiltInWhy.Chosen -> return
        }
        showMessage(context, text)
    }

    /** The viewer's answer in the chooser: [always] also stores it as the default player. */
    fun chosen(context: Context, settings: SettingsStore, request: Chooser, choice: PlayerChoice, always: Boolean) {
        _chooser.value = null
        scope.launch {
            if (always) runCatching { settings.setDefaultPlayer(choice) }
            when (choice) {
                is PlayerChoice.App -> {
                    val app = request.apps.firstOrNull { it.app.packageName == choice.packageName }?.app
                    if (app == null) playHere(request.item, request.chatTitle)
                    else launch(context, settings, request.item, request.chatTitle, app, viaDefault = always, fallback = !request.forced)
                }
                else -> playHere(request.item, request.chatTitle)
            }
        }
    }

    fun dismissChooser() {
        _chooser.value = null
    }

    fun dismissClosed() {
        _closed.value = null
    }

    fun playHereFromClosed() {
        val c = _closed.value ?: return
        _closed.value = null
        playHere(c.item, c.chatTitle)
    }

    // ---- handover -------------------------------------------------------------------------------

    private class Readiness(val complete: Boolean, val local: LocalVideo?, val file: File?)

    private suspend fun readiness(settings: SettingsStore, item: MediaItem): Readiness {
        LocalDownloads.videoFor(settings, item.chatId, item.messageId)?.let { return Readiness(true, it, it.file) }
        if (item.fileId <= 0) return Readiness(false, null, null)
        val availability = runCatching { Td.localFileAvailability(item.fileId) }.getOrDefault(LocalFileAvailability.Missing)
        if (availability != LocalFileAvailability.Complete) return Readiness(false, null, null)
        val path = runCatching {
            Td.awaitAuthorizedSession().client.getFile(item.fileId).valueOrNull?.local?.path
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return Readiness(false, null, null)
        return Readiness(true, LocalVideo.of(null, path), File(path))
    }

    private suspend fun launch(
        context: Context,
        settings: SettingsStore,
        item: MediaItem,
        chatTitle: String,
        app: ExternalApp,
        viaDefault: Boolean,
        fallback: Boolean,
    ) {
        val prepared = runCatching { prepare(context, settings, item, app) }
            .onFailure { Log.w(TAG, "Could not prepare ${app.packageName}", it) }
            .getOrNull()
        val intent = prepared?.intent
        if (intent == null) {
            showMessage(context, prepared?.problem ?: L.externalNotHanded(app = app.label))
            if (fallback) playHere(item, chatTitle)
            return
        }
        val record = Sent(item, chatTitle, app, SystemClock.elapsedRealtime(), item.durationSec * 1000L)
        sent = record
        val started = runCatching {
            val start = launcher
            if (start != null) start(intent) else context.startActivity(intent)
        }
        if (started.isFailure) {
            // The app was removed or updated since the list was made, or would not take the address.
            Log.w(TAG, "${app.packageName} would not open", started.exceptionOrNull())
            sent = null
            ExternalStreams.close(context)
            showMessage(context, L.externalNotHanded(app = app.label))
            if (fallback) playHere(item, chatTitle)
            return
        }
        if (viaDefault && !runCatching { settings.externalHintSeenNow() }.getOrDefault(true)) {
            runCatching { settings.markExternalHintSeen() }
            showMessage(context, L.externalHint(app = app.label))
        }
    }

    private class Prepared(val intent: Intent?, val problem: String? = null)

    private suspend fun prepare(context: Context, settings: SettingsStore, item: MediaItem, app: ExternalApp): Prepared {
        val positionMs = runCatching { settings.resumeStartFor(item) }.getOrDefault(0L)
        val readiness = readiness(settings, item)
        val name = item.fileName.ifBlank { item.title }
        val mime = item.mimeType.ifBlank { "video/*" }
        val uri: Uri
        var streamOpened = false
        if (readiness.complete && readiness.local != null && app.acceptsFile) {
            // A finished video: the file handoff that already exists, aimed at the one app.
            val path = readiness.file?.absolutePath ?: readiness.local.uri().toString()
            uri = LocalVideo.handoutUri(context, path) ?: return Prepared(null, L.playerHandoffRefused)
        } else {
            // Not all here, or an app that takes an address only: served on 127.0.0.1.
            if (!app.acceptsStream) return Prepared(null, L.externalNotHanded(app = app.label))
            if (!readiness.complete) {
                val remaining = (item.sizeBytes - runCatching { Td.localDownloadedBytes(item.fileId) }.getOrDefault(0L)).coerceAtLeast(0L)
                val free = DiskSpace.read(context).freeBytes
                if (!ExternalPlay.hasRoomToStream(remaining, free)) {
                    val formatter = Translator.messages.formatter
                    return Prepared(null, L.externalNoRoom(need = formatter.size(ExternalPlay.streamNeeds(remaining)), free = formatter.size(free.coerceAtLeast(0L))))
                }
                if (item.fileId <= 0 || item.sizeBytes <= 0) return Prepared(null, L.playerHandoffNothing)
            }
            val address = try {
                ExternalStreams.open(
                    context = context,
                    fileId = item.fileId,
                    sizeBytes = item.sizeBytes,
                    mime = mime,
                    name = name,
                    title = item.title,
                    downloaded = if (readiness.complete) readiness.file else null,
                )
            } catch (e: IOException) {
                Log.w(TAG, "No stream", e)
                return Prepared(null, L.externalNotHanded(app = app.label))
            }
            streamOpened = true
            uri = Uri.parse(address)
        }
        // The loopback stream is open from here on: whatever fails below closes it again, so no
        // server, download window or notification is left up for an app that never got the address.
        try {
            // The subtitle the viewer loaded or had fetched for this video, or one beside the downloaded file
            // (a file that is gone is skipped, and so is one the app cannot hand out).
            val language = runCatching { settings.trackPreferencesNow().subtitleKey }.getOrNull()
            val subtitle = runCatching {
                ExternalSubtitleFile.forVideo(context, SubtitleMemory.keyOf(item.chatId, item.messageId), readiness.file, language)
            }.getOrNull()
            return Prepared(ExternalPlayer.viewIntent(context, app, uri, mime, item.title, positionMs, subtitle))
        } catch (e: Throwable) {
            if (streamOpened) runCatching { ExternalStreams.close(context) }
            throw e
        }
    }

    // ---- coming back ----------------------------------------------------------------------------

    /** TMPlayer went out of sight: another app is in front. */
    fun onStopped() {
        sent?.stopped = true
    }

    /** The other app finished and answered (or not). Only a position that makes sense is kept. */
    fun onResult(context: Context, settings: SettingsStore, data: Intent?) {
        val record = sent ?: return
        val extras = runCatching { data?.extras }.getOrNull()
        val vlc = runCatching { extras?.getLong("extra_position", -1L) }.getOrNull()
        val mx = runCatching { extras?.getInt("position", -1)?.toLong() }.getOrNull()
        val position = ExternalPlay.positionFrom(listOf(vlc, mx), record.durationMs)
        record.reportedMs = position
        if (position != null) {
            scope.launch {
                runCatching { settings.saveResumePosition(record.item.chatId, record.item.messageId, position, record.durationMs) }
            }
        }
    }

    /**
     * TMPlayer is in front again. An app that came back within [ExternalPlay.EARLY_CLOSE_MS] with
     * no position did not play: the offer to use TMPlayer's own player. A stream is let go once
     * nothing reads it ([ExternalStreams.closeIfDone], also run by its service).
     */
    fun onResumed(context: Context) {
        val record = sent ?: return
        if (!record.stopped) return
        sent = null
        ExternalStreams.markReturned()
        val away = SystemClock.elapsedRealtime() - record.at
        if (ExternalPlay.closedTooFast(away, record.reportedMs)) {
            ExternalStreams.close(context)
            _closed.value = Closed(record.item, record.chatTitle, record.app.label)
        }
    }
}
