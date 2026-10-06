package com.tmplayer.player

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tmplayer.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/**
 * Subtitle files loaded from the phone over the video playing now: the phone's version of the
 * desktop's drop a subtitle on the picture.
 *
 * The file comes through the system document picker (`ACTION_OPEN_DOCUMENT`), so no storage
 * permission is asked for, and is copied into the app's cache before Media3 sees it: a content
 * URI's grant ends with this activity, and a copy is also the moment to turn a MicroDVD `.sub`
 * into SubRip ([ExternalSubtitle]). Media3 takes it as a [Media3Item.SubtitleConfiguration] on the
 * video's media item, so loading one rebuilds the item at the current position, and the new track
 * is selected as soon as the player reports it.
 *
 * Built as a field of the activity, because the document picker's result has to be registered
 * before the activity starts.
 */
@OptIn(UnstableApi::class)
class SubtitleFiles(
    private val activity: FragmentActivity,
    private val player: () -> ExoPlayer?,
    private val say: (String) -> Unit,
) {
    private val loaded = mutableListOf<Media3Item.SubtitleConfiguration>()

    /** The id of the file just loaded, until its track shows up and is selected. */
    private var pending: Pair<String, String>? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    /** Opens the document picker. Every file is offered; the extension is checked on the way back. */
    fun pick() {
        runCatching { picker.launch(arrayOf("*/*")) }
            .onFailure { say(L.tracksNoFilePicker) }
    }

    /** The video's media item with every subtitle file loaded so far, for building or rebuilding. */
    fun item(video: Uri): Media3Item = Media3Item.Builder()
        .setUri(video)
        .setSubtitleConfigurations(loaded.toList())
        .build()

    /** Selects a just loaded file's track once the player lists it. Call from onTracksChanged. */
    fun onTracksChanged(tracks: Tracks) {
        val (id, label) = pending ?: return
        val exo = player() ?: return
        val group = tracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { index ->
                val format = group.getTrackFormat(index)
                format.id?.contains(id) == true || format.label == label
            }
        } ?: return
        pending = null
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** A subtitle already on the disk as UTF-8, such as one downloaded from online, loaded as is. */
    fun attachFile(file: File, label: String) {
        val mime = ExternalSubtitle.mimeTypeOf(ExternalSubtitle.extensionOf(file.name))
        if (mime == null) {
            say(L.onlineNoticeUnreadable)
            return
        }
        attach(Copy.Done(file, mime), label)
    }

    private fun load(uri: Uri) {
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: L.tracksFileFallbackName
            val extension = ExternalSubtitle.extensionOf(name)
            if (!ExternalSubtitle.accepts(name)) {
                say(L.tracksChooseFile)
                return@launch
            }
            // Read on the main thread before the copy: the player belongs to it.
            val fps = player()?.videoFormat?.frameRate?.toDouble()?.takeIf { it > 0 } ?: ExternalSubtitle.DEFAULT_FPS
            val copied = withContext(Dispatchers.IO) { runCatching { copy(uri, extension, fps) }.getOrNull() }
            when (copied) {
                null -> say(L.tracksFileUnreadable(name))
                is Copy.Refused -> say(copied.reason)
                is Copy.Done -> attach(copied, name)
            }
        }
    }

    private fun attach(copy: Copy.Done, name: String) {
        val exo = player() ?: return
        val video = exo.currentMediaItem?.localConfiguration?.uri ?: return
        val id = "external-${loaded.size + 1}"
        loaded += Media3Item.SubtitleConfiguration.Builder(Uri.fromFile(copy.file))
            .setMimeType(copy.mimeType)
            .setLabel(name)
            .setId(id)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        pending = id to name
        exo.setMediaItem(item(video), exo.currentPosition)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        say(L.tracksFileLoaded(name))
    }

    private sealed interface Copy {
        data class Done(val file: File, val mimeType: String) : Copy
        data class Refused(val reason: String) : Copy
    }

    private fun copy(uri: Uri, extension: String, fps: Double): Copy {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                if (out.size() > ExternalSubtitle.MAX_BYTES) {
                    return Copy.Refused(L.tracksFileTooBig)
                }
            }
            out.toByteArray()
        } ?: return Copy.Refused(L.tracksFileUnopenable)
        val folder = File(activity.cacheDir, "subtitles").apply { mkdirs() }
        // Written back as UTF-8 whatever it arrived in: Media3 reads these formats as UTF-8, and
        // an older .srt saved in a Windows code page would otherwise lose every accented letter.
        val text = decode(bytes)
        val mime = ExternalSubtitle.mimeTypeOf(extension)
        if (mime != null) {
            val file = File(folder, "${loaded.size + 1}.$extension")
            file.writeText(text)
            return Copy.Done(file, mime)
        }
        if (!ExternalSubtitle.isMicroDvd(text)) {
            return Copy.Refused(L.tracksFileVobsub)
        }
        val file = File(folder, "${loaded.size + 1}.srt")
        file.writeText(ExternalSubtitle.microDvdToSrt(text, fps))
        return Copy.Done(file, ExternalSubtitle.MIME_SUBRIP)
    }

    /** UTF-8 when the bytes are valid UTF-8, and the Western Windows code page when they are not. */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
