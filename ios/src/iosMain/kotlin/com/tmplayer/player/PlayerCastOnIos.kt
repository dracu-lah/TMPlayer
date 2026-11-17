package com.tmplayer.player

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tmplayer.cast.CastReader
import com.tmplayer.cast.CastSource
import com.tmplayer.cast.CastSupport
import com.tmplayer.data.EpisodeSteps
import com.tmplayer.data.MediaItem
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// The player's casting on iOS, in place of upstream's PlayerCast.kt: casting is left out on iOS (the
// maintainer, 2026-11-12; docs/IOS.md, "Not in scope"). PlayerActivity makes a PlayerCast
// only where Cast.support says casting is available, which on iOS it never is, so none is made and
// the Cast button stays hidden, Android's own path for a device without a route. The names are
// upstream's, so the activity compiles unchanged.

/** A video's bytes read through the player's data source, upstream's DataSourceCastSource. */
class DataSourceCastSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    override val length: Long,
) : CastSource {
    override fun open(position: Long): CastReader {
        val source = factory.createDataSource()
        source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = source.read(buffer, offset, length)
                return if (n == C.RESULT_END_OF_INPUT) -1 else n
            }

            override fun close() = source.close()
        }
    }
}

/** The cast screen and its session; never made on iOS while no casting is available. */
class PlayerCast(
    @Suppress("UNUSED_PARAMETER") activity: ComponentActivity,
    @Suppress("UNUSED_PARAMETER") root: ViewGroup,
    val support: CastSupport,
    @Suppress("UNUSED_PARAMETER") hooks: Hooks,
) {
    /** Upstream's hooks, the same members, so the activity's object implements them unchanged. */
    interface Hooks {
        fun handOff(): Long?

        fun takeBack(positionMs: Long)

        fun source(): Pair<CastSource, String>?

        fun title(): String

        fun subtitleLine(): String

        fun audioLanguage(): String?

        fun subtitleFiles(): List<Triple<File, String, String>>

        val episodes: StateFlow<EpisodeSteps>

        fun playEpisode(item: MediaItem)

        fun onEnded()

        fun onCasting(casting: Boolean)

        fun close()
    }

    var ready: Boolean = false

    fun begin(startMs: Long?) = Unit

    val casting: Boolean get() = false

    val connected: Boolean get() = false

    val positionMs: Long? get() = null

    val durationMs: Long get() = 0L

    val playing: Boolean get() = false

    fun openPicker() = Unit

    fun togglePlay() = Unit

    fun stepVolume(up: Boolean) = Unit

    fun seekTo(positionMs: Long) = Unit

    fun seekBy(deltaMs: Long) = Unit

    fun addSubtitle(file: File, mime: String, label: String): Boolean = false

    fun release() = Unit
}
