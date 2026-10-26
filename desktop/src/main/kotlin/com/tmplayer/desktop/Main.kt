package com.tmplayer.desktop

import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineSubtitlesStore
import com.tmplayer.online.SubtitleCache
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.tmplayer.data.RemoveAfterWatching
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.desktop.os.AppExit
import com.tmplayer.desktop.os.DesktopLog
import com.tmplayer.desktop.os.DesktopTransferNotifier
import com.tmplayer.desktop.os.NativeFullscreen
import com.tmplayer.desktop.os.NonReparentingWm
import com.tmplayer.desktop.os.NativeInventory
import com.tmplayer.desktop.os.SingleInstance
import com.tmplayer.desktop.os.StartupFailure
import com.tmplayer.desktop.os.WindowMemory
import com.tmplayer.desktop.os.WindowsTitleBar
import com.tmplayer.desktop.os.UndecoratedFrame
import com.tmplayer.desktop.os.WindowChromeMode
import com.tmplayer.desktop.ui.LocalWindowChrome
import com.tmplayer.desktop.ui.ResizeEdges
import com.tmplayer.desktop.ui.WindowChrome
import com.tmplayer.desktop.ui.WindowControlsFallback
import com.tmplayer.ui.theme.Tone
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.tmplayer.desktop.ui.DesktopShell
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.desktop.ui.ProvideFocusVisibility
import com.tmplayer.desktop.ui.idleCursor
import com.tmplayer.desktop.ui.trackFocusVisibility
import com.tmplayer.ui.theme.rememberUiScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.system.exitProcess

private const val QUIT_GRACE_MS = 3_000L

/** How long a fullscreen change is given to land before the window asks for the keyboard again. */
private const val FULLSCREEN_SETTLE_MS = 250L

fun main(args: Array<String>) {
    // Loads every native library, reports, exits: no window, no second instance check.
    args.firstOrNull(SelfTest::matches)?.let { exitProcess(SelfTest.run(it)) }
    com.tmplayer.data.PerfLog.processStart
    // Before the first window: sway and its kind never reparent, which AWT has to be told.
    NonReparentingWm.applyIfNeeded()
    // TDLib's Windows DLL needs the C++ runtime; take the bundled JVM's copy (see WindowsRuntime).
    WindowsRuntime.preload()
    // TDLib's macOS build needs OpenSSL 3, which the app carries (see MacOpenSsl). A failure here
    // shows as TDLib's own load error at sign in, which names the library.
    runCatching { MacOpenSsl.preload() }
    // A second launch hands its arguments (a pressed Windows toast's tmplayer: link, later tg:
    // links) to the first and leaves.
    var raise: () -> Unit = {}
    // `--tv`, `--desktop`, `--fullscreen` (plan 8.7): the layout and full screen for this run.
    val launch = com.tmplayer.desktop.ui.LaunchOptions.parse(args.toList())
    val handedOver: (List<String>) -> Unit = { forwarded ->
        java.awt.EventQueue.invokeLater {
            raise()
            // A media PC's autostart that finds TMPlayer already open: its layout, in this window.
            com.tmplayer.desktop.ui.LaunchOptions.parse(forwarded).layout?.let { com.tmplayer.desktop.ui.BigScreen.override = it }
            com.tmplayer.desktop.os.ToastPresses.handle(forwarded)
        }
    }
    if (!SingleInstance.acquire(args.toList(), handedOver)) return
    // Only the first instance, so a second launch never rotates the log of the one running.
    DesktopLog.install(java.io.File(DesktopPaths.dataDir, "logs"))
    // An uncaught exception is written down while crash reports are off, to be offered on the next
    // start; before StartupFailure, whose handler runs this one first (DesktopCrashReports).
    DesktopCrashReports.watch(java.io.File(DesktopPaths.dataDir, "crash"))
    // Until the window is up, an error ends the launch out loud instead of leaving a process with
    // no window that holds the single instance lock (see StartupFailure).
    StartupFailure.install()
    try {
        // TDLib first: it opens its database on its own threads, and the settings read below is
        // the first of the process, about a tenth of a second of class loading and disk that the
        // database no longer waits behind. A failure in it is caught and shown at sign in, and
        // [DesktopCrashReports.watch] above already writes down anything uncaught.
        Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
        // The crash reports switch next, so whatever fails from here on can be reported, and a
        // crash left from the last run is sent or asked about before anything else is built.
        DesktopCrashReports.launch(DesktopServices.settings)
        // A background job that throws is logged and reported (switch on), never left to the thread (HX10).
        com.tmplayer.platform.JobFailures.report = DesktopCrashReports::handled
        // `-Dtmplayer.crashAtStart=true`: a launch that fails before the window, for testing that.
        DesktopCrashReports.crashAtStartIfAsked()
        // For the log only, and about 60 ms of resource lookups and JNA's loading on a cold start:
        // off the thread that has the window to build.
        Background.scope.launch { runCatching { NativeInventory.log() } }
        OnlineSubtitles.current = desktopOnlineSubtitles()
        val settings = DesktopServices.settings
        // The interface size for a viewer who never chose one, from the screen (see UiScale.forScreen).
        settings.unchosenUiScale = unchosenUiScale()
        // The kids lock (CP57), read once here for every list of chats in the process.
        com.tmplayer.data.ChatGate.follow(settings)
        com.tmplayer.data.HiddenChats.follow(settings)
        // The viewer's corrections to names and groups, for every screen that arranges a chat.
        com.tmplayer.data.VideoCorrections.follow(settings)
        OnlineMetadata.current = desktopOnlineMetadata()
        // Home as it was last built, read now so its first frame finds it in memory (HomeCache).
        com.tmplayer.data.HomeCache.file = java.io.File(DesktopPaths.dataDir, "home-cache.json")
        Background.scope.launch { runCatching { com.tmplayer.data.HomeCache.preload() } }
        // And the chats opened most recently, so a cold start opens one of them at once (PageQueries).
        com.tmplayer.data.PageQueries.store = com.tmplayer.data.ChatGridDisk(java.io.File(DesktopPaths.dataDir, "chat-grids.json"))
        Background.scope.launch { runCatching { com.tmplayer.data.PageQueries.preload() } }
        // What an earlier run was in the middle of comes back paused, once there is an account to
        // ask TDLib about.
        // A storage move a crash or a kill cut short is finished first: it needs the settings store
        // and the disk, not TDLib, which is already starting over the new folders.
        Background.scope.launch { runCatching { DesktopStorage.relocation.resumePending() } }
        // The UI language: the Settings choice, else the system's, else English. The en-XA
        // pseudo-locale is for development runs only (-Dtmplayer.pseudo=true).
        Translator.pseudoEnabled = System.getProperty("tmplayer.pseudo") == "true"
        Background.scope.launch {
            settings.language.collect { saved -> Translator.select(saved, listOf(Locale.getDefault().toLanguageTag())) }
        }
        // Lighter pictures and Reduce motion (CP68) have no rows on a computer, but a backup made
        // on a phone can carry them, and they mean the same here.
        // Pictures are decoded at full size here and the carousel's can be a 4K screen wide (33 MB
        // decoded), past the phone's cap: a sixth of the heap, between 64 and 256 MB.
        com.tmplayer.data.Thumbnails.budget(
            (Runtime.getRuntime().maxMemory() / 6).coerceIn(64L shl 20, 256L shl 20).toInt(),
        )
        Background.scope.launch { settings.lighterPictures.collect { com.tmplayer.data.Thumbnails.light = it } }
        // In Low storage mode the stream warm-up brings the head and the index only
        // ([com.tmplayer.player.StreamWarmup]). Allowed once the setting is known.
        Background.scope.launch {
            settings.lowStorageMode.collect { low ->
                com.tmplayer.player.StreamWarmup.lowStorage = { low }
                com.tmplayer.player.StreamWarmup.allowed = { true }
            }
        }
        Background.scope.launch { settings.reduceMotion.collect { com.tmplayer.ui.theme.Motion.reduced = it } }
        // `-Dtmplayer.lowend=true`: the low-end verdict, short of storage, for checking the card and the step.
        if (System.getProperty("tmplayer.lowend") == "true") {
            com.tmplayer.data.DeviceFitOverride.forced = com.tmplayer.data.DeviceFit(shortOfMemory = false, shortOfStorage = true)
        }
        Background.scope.launch {
            Td.awaitAuthorizedSession()
            DesktopServices.downloads.restore()
        }
        // "Remove after watching", for the life of the process: the grid, the player's button and the
        // player reaching the end all write the one watched list this reads.
        Background.scope.launch {
            RemoveAfterWatching(
                enabled = settings.removeAfterWatching,
                watched = DesktopServices.watched.watched,
                downloads = { settings.downloadsNow() },
                delete = { DownloadIndex.delete(settings, it) },
            ).run()
        }
        DesktopUpdates.configure()
        // Before anything can rewrite desktop.properties, which would drop the old update keys.
        Background.scope.launch { runCatching { DesktopUpdates.migrate(DesktopServices.prefs, settings) } }
        // Compose would end the process itself once the last window closes, with System.exit, which on
        // Linux can hang for good (see AppExit). It returns here instead, and AppExit ends it.
        // Who draws minimize, maximize and close: Windows gets an undecorated window with the app's
        // own buttons, a tiling Linux window manager gets the app's close and fullscreen buttons
        // over its plain window, everything else keeps the system's title bar (WindowChromeMode).
        // The Layout setting before the first frame, so a television never sees the desktop's
        // layout drawn first, then followed as Settings changes it.
        com.tmplayer.desktop.ui.BigScreen.layout = DesktopServices.prefs.now.layout
        com.tmplayer.desktop.ui.BigScreen.override = launch.layout
        Background.scope.launch {
            DesktopServices.prefs.state.collect { com.tmplayer.desktop.ui.BigScreen.layout = it.layout }
        }
        val startFullscreen = launch.fullscreen ?: DesktopServices.prefs.now.startFullscreen
        com.tmplayer.platform.Logger.i("Main", "layout: ${com.tmplayer.desktop.ui.BigScreen.effective}, full screen at start: $startFullscreen")
        val chromeMode = WindowChromeMode.current(DesktopServices.prefs.now.systemTitleBar)
        com.tmplayer.platform.Logger.i("Main", "window buttons: $chromeMode")
        val undecorated = chromeMode == WindowChromeMode.Custom
        // A touchpad's sideways swipe on Linux is a sideways scroll, not the mouse's Back button.
        com.tmplayer.desktop.os.X11SideScroll.install()
        application(exitProcessOnExit = false) {
            val windowState = remember { WindowMemory.load() }
            LaunchedEffect(windowState) { WindowMemory.follow(windowState) }
            val shell = remember { ShellState(settings, DesktopServices.downloads, DesktopServices.watched, bookmarks = DesktopServices.bookmarks) }
            // A card under the pointer or a key on it warms its stream (PlaybackWarmup, StreamWarmup).
            androidx.compose.runtime.DisposableEffect(shell) {
                com.tmplayer.data.PlaybackWarmup.warmer = com.tmplayer.data.PlaybackWarmup.Warmer { shell.warmStream(it) }
                onDispose { com.tmplayer.data.PlaybackWarmup.warmer = null }
            }
            val quit = {
                // Saving is a courtesy; a failure there must never keep the app from closing.
                runCatching { WindowMemory.save(windowState) }
                // Should taking the window down hang (a native player that will not let go), the
                // process still ends.
                Thread {
                    Thread.sleep(QUIT_GRACE_MS)
                    AppExit.now()
                }.apply { isDaemon = true }.start()
                exitApplication()
            }
            // Focus drawn for the keyboard only: a click hides it, Tab and the arrows show it.
            com.tmplayer.desktop.ui.FocusVisible.install()
            // A screen that throws is put away behind an error card, not the end of the app (HX10).
            val recovery = remember { com.tmplayer.desktop.ui.WindowRecovery() }
            com.tmplayer.desktop.ui.RecoveringWindow(
                recovery = recovery,
                onCloseRequest = quit,
                state = windowState,
                title = "TMPlayer",
                undecorated = undecorated,
                icon = rememberVectorPainter(AppLogo.Mark),
                onPreviewKeyEvent = shell::onPreviewKey,
                onKeyEvent = shell::onKey,
            ) {
                // The layouts are made for 960 by 600 at the Default size, so the least the window
                // can be grows with the interface size, as far as the screen allows.
                val uiScale by rememberUiScale(settings)
                // The big-screen mode scales to the window it is given, so it asks for no more room.
                val bigScreen = com.tmplayer.desktop.ui.BigScreen.on
                LaunchedEffect(uiScale, bigScreen) { window.minimumSize = minimumWindow(window, if (bigScreen) 1f else uiScale.factor) }
                // Before the window is first shown, so one that opens maximized stops at the taskbar.
                if (undecorated) UndecoratedFrame.holdToWorkArea(window)
                DisposableEffect(window) {
                    val follow = object : java.awt.event.ComponentAdapter() {
                        override fun componentMoved(e: java.awt.event.ComponentEvent) = UndecoratedFrame.holdToWorkArea(window)
                    }
                    if (undecorated) window.addComponentListener(follow)
                    onDispose { window.removeComponentListener(follow) }
                }
                LaunchedEffect(Unit) {
                    if (!undecorated) return@LaunchedEffect
                    repeat(100) { if (!window.isDisplayable) delay(50) }
                    UndecoratedFrame.restoreCaptionStyles(window)
                    UndecoratedFrame.holdToWorkArea(window)
                }
                val chrome = remember(window) {
                    if (!chromeMode.inApp) {
                        null
                    } else if (undecorated) {
                        WindowChrome(
                            mode = chromeMode,
                            window = window,
                            onMinimize = { windowState.isMinimized = true },
                            onToggleMaximize = {
                                UndecoratedFrame.holdToWorkArea(window)
                                windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                            },
                            onClose = quit,
                            maximized = { windowState.placement == WindowPlacement.Maximized },
                        )
                    } else {
                        // A tiling window manager sizes the window itself: the middle button is
                        // fullscreen, and there is nowhere to minimize to.
                        WindowChrome(
                            mode = chromeMode,
                            window = window,
                            onMinimize = null,
                            onToggleMaximize = { shell.fullscreen = !shell.fullscreen },
                            onClose = quit,
                            maximized = { shell.fullscreen },
                        )
                    }
                }
                DesktopServices.selfUpdate.quit = quit
                shell.quit = quit
                shell.minimize = if (undecorated) ({ windowState.isMinimized = true }) else null
                // Transfers show outside the window too, once there is a window for the taskbar bar.
                LaunchedEffect(Unit) {
                    DesktopStorage.closePlayer = { shell.closePlayer() }
                    DesktopTransferNotifier.raise = { java.awt.EventQueue.invokeLater { raise() } }
                    DesktopStorage.notifier = DesktopTransferNotifier.create({ window }) {
                        java.awt.EventQueue.invokeLater {
                            raise()
                            shell.go(com.tmplayer.desktop.ui.Destination.Downloads)
                        }
                    }
                }
                // The update check, on every launch and before sign in too: the first one ten seconds
                // after the first frame, then every six hours while the window is open.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    DesktopServices.updates.run()
                }
                // Fullscreen is the shell's to ask for and the window's to do. The window system is
                // asked directly where it can be (see NativeFullscreen); elsewhere Compose's placement
                // does it, and leaving goes back to whatever the window was before.
                val native = remember(window) { NativeFullscreen(window) }
                var beforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
                LaunchedEffect(shell.fullscreen) {
                    WindowMemory.freeze(WindowMemory.Hold.Fullscreen, shell.fullscreen)
                    if (native.set(shell.fullscreen)) return@LaunchedEffect
                    if (shell.fullscreen && windowState.placement != WindowPlacement.Fullscreen) {
                        beforeFullscreen = windowState.placement
                        windowState.placement = WindowPlacement.Fullscreen
                    } else if (!shell.fullscreen && windowState.placement == WindowPlacement.Fullscreen) {
                        windowState.placement = beforeFullscreen
                    }
                }
                // The change remaps the window, and the window system may hand it back without the
                // keyboard (Esc and Space then reach nothing until a click). Once it has settled,
                // the window asks for focus again; the player takes it from there (PlayerScreen).
                var fullscreenSeen by remember { mutableStateOf(shell.fullscreen) }
                LaunchedEffect(shell.fullscreen) {
                    if (fullscreenSeen == shell.fullscreen) return@LaunchedEffect
                    fullscreenSeen = shell.fullscreen
                    delay(FULLSCREEN_SETTLE_MS)
                    if (!window.isShowing || window.isActive) return@LaunchedEffect
                    window.toFront()
                    window.requestFocus()
                }
                // "Start in full screen", `--fullscreen` and `--tv`: once the window is shown, so the
                // window system is asked directly (NativeFullscreen) and leaving it later undoes
                // what was done rather than a placement nobody set.
                LaunchedEffect(Unit) {
                    if (!startFullscreen) return@LaunchedEffect
                    repeat(100) { if (!window.isDisplayable) delay(50) }
                    shell.fullscreen = true
                }
                raise = {
                    window.isVisible = true
                    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    }
                    window.toFront()
                    window.requestFocus()
                }
                // The first frame is on screen: later launches may hand over to this one now.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    SingleInstance.markReady()
                    StartupFailure.started()
                    // `-Dtmplayer.crashAfterWindow=true`: an uncaught exception with the window up.
                    DesktopCrashReports.crashAfterWindowIfAsked()
                }
                DesktopTheme(settings) {
                    val colors = MaterialTheme.colorScheme
                    LaunchedEffect(colors.background, colors.onBackground) {
                        if (undecorated) return@LaunchedEffect
                        // The handle exists once the window is shown, which is just after the first frame.
                        repeat(100) { if (!window.isDisplayable) delay(50) }
                        WindowsTitleBar.apply(
                            window,
                            dark = colors.background.luminance() < 0.5f,
                            caption = colors.background.toArgb() and 0xFFFFFF,
                            text = colors.onBackground.toArgb() and 0xFFFFFF,
                        )
                    }
                    // A television in full screen has no window buttons: nothing to drag, nothing to
                    // minimise, and the remote leaves full screen with Back.
                    CompositionLocalProvider(LocalWindowChrome provides if (bigScreen && shell.fullscreen) null else chrome) {
                        // The pointer hides while it rests in the big-screen mode (IdleCursor).
                        Box(Modifier.fillMaxSize().trackFocusVisibility().idleCursor(bigScreen)) {
                            com.tmplayer.desktop.ui.ShellOrFailure(
                                recovery,
                                onTryAgain = { shell.closePlayer() },
                                onSettings = {
                                    shell.closePlayer()
                                    shell.go(com.tmplayer.desktop.ui.Destination.Settings)
                                },
                            ) {
                                DesktopShell(shell) { request, close ->
                                    PlayerHost(
                                        request = request,
                                        onClose = {
                                            // A television stays full screen between videos.
                                            if (!com.tmplayer.desktop.ui.BigScreen.on) shell.fullscreen = false
                                            close()
                                        },
                                        settings = settings,
                                        fullscreen = shell.fullscreen,
                                        onToggleFullscreen = { shell.fullscreen = !shell.fullscreen },
                                        onToggleAlwaysOnTop = { window.isAlwaysOnTop = !window.isAlwaysOnTop },
                                        onQuit = quit,
                                        onRaise = raise,
                                        onFreeUpSpace = {
                                            if (!com.tmplayer.desktop.ui.BigScreen.on) shell.fullscreen = false
                                            shell.freeUpSpace()
                                        },
                                    )
                                }
                            }
                            // Nothing on screen with a page header (sign in, the PIN pad): the
                            // buttons on their own, top right. The player has its own in its bar.
                            if (shell.nowPlaying == null || recovery.failure != null) WindowControlsFallback(Modifier.align(Alignment.TopEnd))
                            // A crash from the last run, signed in or not (DesktopCrashReports).
                            com.tmplayer.desktop.ui.CrashQuestionHost(settings)
                            // Undecorated and floating: a hairline for the edge the system no
                            // longer draws, and the edges to resize by.
                            if (undecorated && windowState.placement == WindowPlacement.Floating && !shell.fullscreen) {
                                val hairline = with(LocalDensity.current) { 1.toDp() }
                                Box(Modifier.fillMaxSize().border(hairline, Tone.outline))
                                ResizeEdges(window)
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Throwable) {
        StartupFailure.report(e)
    }
    // TDLib's, libmpv's and D-Bus's threads are not daemons; with the window gone, nothing is left
    // that should keep the process alive.
    AppExit.now()
}

/**
 * [com.tmplayer.data.UiScale.forScreen] for the screen the window opens on: the default one, in
 * the units the window's size is in (pixels over the system's scaling). Default without a screen.
 */
private fun unchosenUiScale(): com.tmplayer.data.UiScale = runCatching {
    val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    com.tmplayer.data.UiScale.forScreen(screen.width, screen.height)
}.getOrDefault(com.tmplayer.data.UiScale.Default).also {
    com.tmplayer.platform.Logger.i("Main", "interface size when none is chosen: $it")
}

/** 960 by 600 at [factor], no larger than the usable part of the screen [window] is on. */
private fun minimumWindow(window: java.awt.Window, factor: Float): java.awt.Dimension {
    val config = window.graphicsConfiguration ?: return java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
    val bounds = config.bounds
    val width = (MIN_WIDTH * factor).toInt().coerceAtMost(bounds.width - insets.left - insets.right).coerceAtLeast(MIN_WIDTH)
    val height = (MIN_HEIGHT * factor).toInt().coerceAtMost(bounds.height - insets.top - insets.bottom).coerceAtLeast(MIN_HEIGHT)
    return java.awt.Dimension(width, height)
}

private const val MIN_WIDTH = 960
private const val MIN_HEIGHT = 600

/**
 * The theme the Settings choice asks for. "System" follows the OS (SystemAppearance: Windows,
 * macOS, and on Linux the desktop portal or gsettings) and changes with it while the app runs;
 * where the OS says nothing, dark, which is the right default for a window that plays video.
 * Drawn at the viewer's interface size.
 */
@Composable
fun DesktopTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val choice by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val systemDark by com.tmplayer.desktop.os.SystemAppearance.dark.collectAsState()
    val dark = choice.isDark(systemDark)
    // The Pure black switch, wherever the window is dark, System on a dark desktop included.
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val black = com.tmplayer.data.pureBlackGround(choice, pureBlack, systemDark)
    // The interface size (CP59) for the whole window, the player's controls included; the video
    // fills whatever space it is given, so it is the same at every size.
    val scale by rememberUiScale(settings)
    val accent by settings.accent.collectAsState(initial = com.tmplayer.data.Accent.Default)
    val customAccent by settings.customAccent.collectAsState(initial = null)
    val highContrast by settings.highContrast.collectAsState(initial = false)
    val typeface by settings.typeface.collectAsState(initial = com.tmplayer.data.AppTypeface.Default)
    TmMaterialTheme(dark = dark, black = black, accent = accent, customAccent = customAccent, highContrast = highContrast, typeface = typeface) {
        // The big-screen mode (plan 8.7) sizes the whole window for a sofa's distance (TvScale).
        ProvideFocusVisibility { com.tmplayer.desktop.ui.ProvideInterfaceScale(scale, com.tmplayer.desktop.ui.BigScreen.on, content) }
    }
}

/**
 * Online subtitles with this build's OpenSubtitles key: the account beside the settings in the
 * data directory, the cache in the cache directory. A build without a key gets one that says so.
 */
internal fun desktopOnlineSubtitles(): OnlineSubtitles = OnlineSubtitles(
    apiKey = BuildInfo.OPENSUBTITLES_API_KEY,
    store = OnlineSubtitlesStore(java.io.File(DesktopPaths.dataDir, "online-subtitles.properties")),
    cache = SubtitleCache(java.io.File(DesktopPaths.cacheDir, "online-subtitles")),
    appVersion = BuildInfo.VERSION,
).also { online -> Background.scope.launch { runCatching { online.cache.prune() } } }

/**
 * Posters and overviews with this build's TMDB key: the switches beside the settings in the data
 * directory, the answers and pictures in the cache directory. Off until the viewer turns it on.
 */
internal fun desktopOnlineMetadata(): OnlineMetadata = OnlineMetadata(
    buildKey = BuildInfo.TMDB_API_KEY,
    store = MetadataStore(java.io.File(DesktopPaths.dataDir, "online-metadata.properties")),
    cache = MetadataCache(java.io.File(DesktopPaths.cacheDir, "online-metadata")),
    appVersion = BuildInfo.VERSION,
    omdbKey = BuildInfo.OMDB_API_KEY,
).also { metadata -> Background.scope.launch { runCatching { metadata.warmUp() }; runCatching { metadata.cache.prune() } } }
