package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

/**
 * Asks the viewer for a folder, in the OS's own dialog: FileKit's `IFileOpenDialog` on Windows,
 * the `org.freedesktop.portal.FileChooser` portal on Linux (which is also what makes it work
 * inside the Flatpak, the choice itself granting access), `NSOpenPanel` on macOS.
 *
 * `java.awt.FileDialog` is no use here: it cannot pick folders on Windows and does not go through
 * the portal. Where FileKit fails outright (no portal running, as on a bare sway session) Swing's
 * `JFileChooser` in folders only mode stands in: plainer, but it always works.
 */
object FolderPicker {

    @Volatile
    private var initialised = false

    /**
     * The app's window, which every picker names as its parent. Without one, the portal's dialog
     * is a window of its own: on a tiling window manager (sway) it was tiled beside TMPlayer,
     * which shrank the app to half the screen and reloaded every page and picture, then again
     * when it closed. With a parent it is a dialog of the app's, floated over it.
     */
    internal fun parentWindow(): java.awt.Window? = runCatching {
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Window.getWindows().firstOrNull { it.isShowing && it is java.awt.Frame }
    }.getOrNull()

    /** The dialog's [title], over [parentWindow] when there is one. */
    internal fun dialogSettings(title: String): FileKitDialogSettings =
        parentWindow()?.let { window ->
            runCatching { FileKitDialogSettings(title = title, parent = FileKitDialogParent.awt(window)) }.getOrNull()
        } ?: FileKitDialogSettings(title = title)

    /** Starts FileKit once, for this picker and [FilePicker] alike. */
    internal fun ensureFileKit() {
        if (!initialised) {
            runCatching { FileKit.init(APP_ID) }.onFailure { Logger.w(TAG, "FileKit would not start", it) }
            initialised = true
        }
    }

    /** The folder picked, or null when the viewer cancelled. */
    suspend fun pick(title: String, startIn: File?): File? {
        ensureFileKit()
        val viaFileKit = runCatching {
            val start = startIn?.takeIf { it.isDirectory }?.let { PlatformFile(it) }
            FileKit.openDirectoryPicker(directory = start, dialogSettings = dialogSettings(title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system folder picker failed; using Swing's", it) }
        if (viaFileKit.isSuccess) return viaFileKit.getOrNull()?.file
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser(startIn).apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(parentWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    private const val APP_ID = "TMPlayer"
    private const val TAG = "FolderPicker"
}
