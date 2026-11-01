package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Asks the viewer for one file to open or a place to save one, in the OS's own dialog, the way
 * [FolderPicker] asks for a folder: FileKit first (the portal on Linux, so the Flatpak can reach the
 * file), Swing's `JFileChooser` where FileKit fails outright.
 */
object FilePicker {

    /** The file picked, or null when the viewer cancelled. Only files ending in one of [extensions] are offered. */
    suspend fun open(title: String, vararg extensions: String): File? {
        FolderPicker.ensureFileKit()
        val viaFileKit = runCatching {
            FileKit.openFilePicker(type = FileKitType.File(extensions.toSet()), dialogSettings = FolderPicker.dialogSettings(title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system file picker failed; using Swing's", it) }
        if (viaFileKit.isSuccess) return viaFileKit.getOrNull()?.file
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileFilter = FileNameExtensionFilter(extensions.joinToString(), *extensions)
            }
            if (chooser.showOpenDialog(FolderPicker.parentWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    /**
     * Where to save [suggestedName], or null when the viewer cancelled. The name keeps [extension]
     * even when the viewer typed it without.
     */
    suspend fun save(title: String, suggestedName: String, extension: String): File? {
        FolderPicker.ensureFileKit()
        val base = suggestedName.removeSuffix(".$extension")
        val viaFileKit = runCatching {
            FileKit.openFileSaver(suggestedName = base, extension = extension, dialogSettings = FolderPicker.dialogSettings(title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system save dialog failed; using Swing's", it) }
        val picked = if (viaFileKit.isSuccess) {
            viaFileKit.getOrNull()?.file
        } else {
            withContext(Dispatchers.Main) {
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    selectedFile = File(currentDirectory, "$base.$extension")
                }
                if (chooser.showSaveDialog(FolderPicker.parentWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
        }
        return picked?.let { if (it.name.endsWith(".$extension")) it else File(it.parentFile, "${it.name}.$extension") }
    }

    private const val TAG = "FilePicker"
}
