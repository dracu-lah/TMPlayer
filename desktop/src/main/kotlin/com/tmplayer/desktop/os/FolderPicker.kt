package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
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

    /** The folder picked, or null when the viewer cancelled. */
    suspend fun pick(title: String, startIn: File?): File? {
        if (!initialised) {
            runCatching { FileKit.init(APP_ID) }.onFailure { Logger.w(TAG, "FileKit would not start", it) }
            initialised = true
        }
        val viaFileKit = runCatching {
            val start = startIn?.takeIf { it.isDirectory }?.let { PlatformFile(it) }
            FileKit.openDirectoryPicker(directory = start, dialogSettings = FileKitDialogSettings(title = title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system folder picker failed; using Swing's", it) }
        if (viaFileKit.isSuccess) return viaFileKit.getOrNull()?.file
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser(startIn).apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    private const val APP_ID = "TMPlayer"
    private const val TAG = "FolderPicker"
}
