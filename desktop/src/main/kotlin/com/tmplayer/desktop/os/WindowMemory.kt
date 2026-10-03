package com.tmplayer.desktop.os

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.util.Properties
import kotlin.math.max
import kotlin.math.min

/** A rectangle in AWT's logical pixels, which are what Compose Desktop calls dp. */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun intersection(other: Bounds): Bounds? {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val right = min(x + width, other.x + other.width)
        val bottom = min(y + height, other.y + other.height)
        return if (right > left && bottom > top) Bounds(left, top, right - left, bottom - top) else null
    }
}

enum class SavedPlacement { Floating, Maximized, Fullscreen }

/**
 * What is on disk: the last floating bounds (position may be missing if the window never left
 * the centre) and the placement it closed in. Maximised and fullscreen never overwrite the floating
 * bounds, so leaving either returns the window to where it was.
 */
data class SavedWindow(
    val x: Int?,
    val y: Int?,
    val width: Int,
    val height: Int,
    val placement: SavedPlacement,
)

/** What to open with: [x] and [y] null means centred. */
data class RestoredWindow(
    val x: Int?,
    val y: Int?,
    val width: Int,
    val height: Int,
    val placement: SavedPlacement,
)

/** The pure half of [WindowMemory]: validation and the properties format. */
object WindowGeometry {
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 800
    const val MIN_WIDTH = 960
    const val MIN_HEIGHT = 600

    /** The top strip of a window, where the title bar is; this is what has to be reachable. */
    private const val TITLE_STRIP = 32

    /** How much of that strip must be on some screen to grab it: wide enough to aim at. */
    private const val MIN_GRAB_WIDTH = 120
    private const val MIN_GRAB_HEIGHT = 16

    /**
     * Checks [saved] against the screens attached now ([screens], usable areas). A position whose
     * title bar no longer lands on any screen (a monitor unplugged since) is dropped, which means
     * centred; a size larger than the screen it opens on is shrunk to fit; a size below the minimum
     * is raised to it. Fullscreen comes back as maximised: opening straight into fullscreen
     * surprises more than it helps, and the player puts it back when a video starts.
     */
    fun restore(saved: SavedWindow?, screens: List<Bounds>): RestoredWindow {
        if (saved == null) return RestoredWindow(null, null, DEFAULT_WIDTH, DEFAULT_HEIGHT, SavedPlacement.Floating)
        var width = max(saved.width, MIN_WIDTH)
        var height = max(saved.height, MIN_HEIGHT)
        var x = saved.x
        var y = saved.y
        val placement = if (saved.placement == SavedPlacement.Fullscreen) SavedPlacement.Maximized else saved.placement

        val home = if (x != null && y != null) screenHolding(Bounds(x, y, width, height), screens) else null
        if (home == null) {
            x = null
            y = null
        }
        val fitTo = home ?: screens.firstOrNull()
        if (fitTo != null) {
            width = min(width, max(fitTo.width, MIN_WIDTH))
            height = min(height, max(fitTo.height, MIN_HEIGHT))
        }
        if (home != null && x != null && y != null) {
            // Pull it back so the whole title strip sits on that screen.
            x = x.coerceIn(home.x, max(home.x, home.x + home.width - width))
            y = y.coerceIn(home.y, max(home.y, home.y + home.height - TITLE_STRIP))
        }
        return RestoredWindow(x, y, width, height, placement)
    }

    /** The screen showing the most of [window]'s title strip, if any shows enough to grab it. */
    fun screenHolding(window: Bounds, screens: List<Bounds>): Bounds? {
        val strip = Bounds(window.x, window.y, window.width, TITLE_STRIP)
        return screens
            .mapNotNull { s -> strip.intersection(s)?.let { s to it } }
            .filter { (_, i) -> i.width >= min(MIN_GRAB_WIDTH, window.width) && i.height >= MIN_GRAB_HEIGHT }
            .maxByOrNull { (_, i) -> i.width.toLong() * i.height }
            ?.first
    }

    fun toProperties(saved: SavedWindow): Properties = Properties().apply {
        saved.x?.let { setProperty("x", it.toString()) }
        saved.y?.let { setProperty("y", it.toString()) }
        setProperty("width", saved.width.toString())
        setProperty("height", saved.height.toString())
        setProperty("placement", saved.placement.name)
    }

    fun fromProperties(p: Properties): SavedWindow? {
        val width = p.getProperty("width")?.toIntOrNull() ?: return null
        val height = p.getProperty("height")?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val x = p.getProperty("x")?.toIntOrNull()
        val y = p.getProperty("y")?.toIntOrNull()
        val placement = SavedPlacement.entries.firstOrNull { it.name == p.getProperty("placement") } ?: SavedPlacement.Floating
        return SavedWindow(if (y == null) null else x, if (x == null) null else y, width, height, placement)
    }
}

/**
 * Remembers the main window between launches, in `window.properties` under the config directory.
 *
 * ```
 * val state = remember { WindowMemory.load() }
 * LaunchedEffect(state) { WindowMemory.follow(state) }
 * Window(state = state, onCloseRequest = { WindowMemory.save(state); exitApplication() }) { ... }
 * ```
 */
object WindowMemory {
    private val store by lazy { WindowMemoryStore(File(DesktopPaths.configDir, "window.properties"), ::currentScreens) }

    fun load(): WindowState = store.load()

    fun save(state: WindowState) = store.save(state)

    /** While on, window changes are not remembered (the mini player is not the window's size). */
    fun freeze(on: Boolean) {
        store.frozen = on
    }

    /** Watches [state] until cancelled, keeping the floating bounds current. */
    suspend fun follow(state: WindowState) = store.follow(state)

    /** Usable area of every attached screen (minus panels and docks); empty when headless. */
    fun currentScreens(): List<Bounds> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val toolkit = Toolkit.getDefaultToolkit()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val config = device.defaultConfiguration
            val b = config.bounds
            val inset = runCatching { toolkit.getScreenInsets(config) }.getOrNull()
            if (inset == null) {
                Bounds(b.x, b.y, b.width, b.height)
            } else {
                Bounds(
                    b.x + inset.left,
                    b.y + inset.top,
                    b.width - inset.left - inset.right,
                    b.height - inset.top - inset.bottom,
                )
            }
        }
    }.getOrDefault(emptyList())
}

/** [WindowMemory] for any file and any screen list, so tests can drive it. */
class WindowMemoryStore(private val file: File, private val screens: () -> List<Bounds>) {

    /** The floating bounds last seen, which maximised and fullscreen leave alone. */
    @Volatile
    private var floating: SavedWindow? = null

    fun read(): SavedWindow? = runCatching {
        if (!file.isFile) return null
        WindowGeometry.fromProperties(Properties().apply { file.inputStream().use { load(it) } })
    }.getOrNull()

    fun write(saved: SavedWindow) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { WindowGeometry.toProperties(saved).store(it, "TMPlayer window") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Logger.w("WindowMemory", "could not save window state: ${it.message}") }
    }

    fun restore(): RestoredWindow {
        val saved = read()
        floating = saved
        return WindowGeometry.restore(saved, screens())
    }

    fun load(): WindowState {
        val r = restore()
        return WindowState(
            placement = when (r.placement) {
                SavedPlacement.Floating -> WindowPlacement.Floating
                SavedPlacement.Maximized -> WindowPlacement.Maximized
                SavedPlacement.Fullscreen -> WindowPlacement.Fullscreen
            },
            position = if (r.x != null && r.y != null) WindowPosition(r.x.dp, r.y.dp) else WindowPosition(Alignment.Center),
            size = DpSize(r.width.dp, r.height.dp),
        )
    }

    /** Set while the mini player has the window, whose bounds are not the ones to keep. */
    @Volatile
    var frozen = false

    /** Records [state]'s bounds if it is floating now; otherwise keeps the floating ones. */
    fun observe(state: WindowState) {
        if (frozen) return
        if (state.placement != WindowPlacement.Floating || state.isMinimized) return
        val size = state.size
        if (!size.isSpecified) return
        val pos = state.position
        val (x, y) = if (pos is WindowPosition.Absolute) pos.x.value.toInt() to pos.y.value.toInt() else null to null
        floating = SavedWindow(x, y, size.width.value.toInt(), size.height.value.toInt(), SavedPlacement.Floating)
    }

    fun save(state: WindowState) {
        observe(state)
        val placement = when (state.placement) {
            WindowPlacement.Floating -> SavedPlacement.Floating
            WindowPlacement.Maximized -> SavedPlacement.Maximized
            WindowPlacement.Fullscreen -> SavedPlacement.Fullscreen
        }
        val base = floating ?: SavedWindow(null, null, WindowGeometry.DEFAULT_WIDTH, WindowGeometry.DEFAULT_HEIGHT, SavedPlacement.Floating)
        write(base.copy(placement = placement))
    }

    suspend fun follow(state: WindowState) {
        snapshotFlow { listOf(state.placement, state.isMinimized, state.position, state.size) }
            .collectLatest {
                // Maximising moves size and placement in separate events; wait for both to land so
                // the maximised size is never taken for a floating one.
                delay(SETTLE_MS)
                observe(state)
            }
    }

    private companion object {
        const val SETTLE_MS = 400L
    }
}
