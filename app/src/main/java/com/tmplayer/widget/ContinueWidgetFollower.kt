package com.tmplayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.tmplayer.data.AppLocales
import com.tmplayer.data.AuthState
import com.tmplayer.data.ContinueWidget
import com.tmplayer.data.ContinueWidget.Tile
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GoneVideos
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the Continue watching widget current, and draws it.
 *
 * Two things redraw it. The system, at most every 30 minutes (`updatePeriodMillis`; Android never
 * goes lower and does not wake the device for it): that draws what is already on disk and asks
 * nothing of Telegram or the network. And this class, while the app's process is alive and a
 * widget is on a home screen: [start] follows the viewer's own resume points, the locks and the
 * account, and redraws when the tile changes, at most once a [ContinueWidget.MIN_GAP_MS] (a
 * minute). No wake lock, no alarm, no service of its own, and nothing at all with no widget placed.
 *
 * Its cost, stated: while a widget exists and the process is up, each write of a resume point
 * (the player writes one every few seconds) reads the resume list once more and compares the tile;
 * a redraw is a few small text and bitmap updates to the launcher.
 */
object ContinueWidgetFollower {

    private const val TAG = "ContinueWidget"

    /** How long the system's refresh waits for the sign in state when it has no video to show. */
    private const val SETTLE_MS = 3_000L

    /** Whether any widget of ours is on a home screen, kept by the provider and read once at start. */
    internal val present = MutableStateFlow(false)

    /**
     * Where the account state is read from. Telegram's own in the app; a test swaps it so the
     * widget is checked without a TDLib client.
     */
    @Volatile
    internal var auth: () -> StateFlow<AuthState> = { Td.auth }

    /** The viewer's settings. A seam, so a test reads a store of its own. */
    @Volatile
    internal var settings: (Context) -> SettingsStore = { SettingsStore(it) }

    private val drawing = Mutex()

    fun widgetIds(context: Context): IntArray =
        runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ContinueWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))

    /**
     * Follows the state for the life of [scope]. Not on a television (its receiver is switched off
     * there too), and nothing is read while no widget is placed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, scope: CoroutineScope) {
        if (FormFactor.isTv(context)) return
        val app = context.applicationContext
        scope.launch {
            present.value = widgetIds(app).isNotEmpty()
            present
                .flatMapLatest { on -> if (on) feed(app) else emptyFlow() }
                .catch { if (it is CancellationException) throw it else Logger.w(TAG, "widget feed stopped", it) }
                .conflate()
                .collect { tile ->
                    publish(app, tile)
                    // The most it redraws on progress: the newest tile after this wait is the next one drawn.
                    delay(ContinueWidget.MIN_GAP_MS)
                }
        }
    }

    /** The tile, again when the language changes (the text is built in it). */
    private fun feed(context: Context): Flow<Tile> {
        val store = settings(context)
        return combine(tiles(context), store.language) { tile, language -> tile to language }
            .distinctUntilChanged()
            .map { it.first }
    }

    /** Every tile as the state changes; equal ones are not repeated. */
    internal fun tiles(context: Context): Flow<Tile> {
        val store = settings(context)
        val account = auth().map { ContinueWidget.accountOf(it) }.distinctUntilChanged()
        // The open profile's records (HX88): its store's view moves with a switch, and the tile is
        // worked out again for the next profile even when its list reads the same.
        // And locked while the open profile's PIN was not given in this process (HX88): a PIN
        // locked profile's titles and posters never reach the home screen.
        return combine(
            store.continueWatching,
            store.appLock,
            account,
            com.tmplayer.data.Accounts.activeFolder,
            com.tmplayer.data.ProfileUnlock.hidden(),
        ) { records, lock, who, _, profileLocked ->
            // A video this session learnt is gone is skipped unless the viewer downloaded it.
            val gone = records.filter { GoneVideos.reasonFor(it.chatId, it.messageId) != null }
                .mapTo(HashSet()) { "${it.chatId}:${it.messageId}" }
            val downloaded = if (gone.isEmpty()) emptySet() else runCatching { LocalDownloads.presentIds(store) }.getOrDefault(emptySet())
            ContinueWidget.decide(
                account = who,
                locked = ContinueWidget.lockedBy(lock, profileLocked),
                records = records,
                unavailable = { r ->
                    val id = "${r.chatId}:${r.messageId}"
                    id in gone && id !in downloaded
                },
            )
        }.distinctUntilChanged()
    }

    /**
     * The tile now, for the system's refresh. With no video to show and the account still
     * connecting it waits a moment for the answer, so a signed out phone says so, but never long.
     */
    suspend fun tileNow(context: Context): Tile {
        val tile = tiles(context).first()
        if (tile != Tile.Nothing || ContinueWidget.accountOf(auth().value) != ContinueWidget.Account.Unknown) return tile
        withTimeoutOrNull(SETTLE_MS) { auth().first { ContinueWidget.accountOf(it) != ContinueWidget.Account.Unknown } }
        return tiles(context).first()
    }

    /** Draws what is on disk for the current state: the system's refresh, a resize, a language change. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        if (FormFactor.isTv(app) || widgetIds(app).isEmpty()) return
        useLanguage(app)
        render(app, tileNow(app))
    }

    /** Draws [tile], and when it is a video with no picture yet, fetches the picture and draws again. */
    private suspend fun publish(context: Context, tile: Tile) {
        runCatching {
            useLanguage(context)
            render(context, tile)
            if (tile is Tile.Video && WidgetArt.fileFor(context, tile.video) == null && WidgetArt.prepare(context, tile.video)) {
                render(context, tile)
            }
        }.onFailure { if (it is CancellationException) throw it else Logger.w(TAG, "widget not drawn", it) }
    }

    /** The in-app language, in force before any text is built: a cold start has not applied it yet. */
    private suspend fun useLanguage(context: Context) {
        runCatching { Translator.select(settings(context).language.first(), AppLocales.system(context)) }
    }

    /** Every widget gets the tile, in the layout its own size asks for. */
    internal suspend fun render(context: Context, tile: Tile) {
        drawing.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val poster = if (tile is Tile.Video) WidgetArt.bitmapFor(context, tile.video) else null
            for (id in ids) {
                val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                val size = ContinueWidget.sizeFor(
                    options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
                    options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
                )
                // profile-notifications: the tile is Locked while the profile is hidden (ContinueWidget.lockedBy)
                runCatching { manager.updateAppWidget(id, ContinueWidgetViews.build(context, tile, size, poster)) }
                    .onFailure { Logger.w(TAG, "widget $id not updated", it) }
            }
        }
    }
}
