package com.tmplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** A video cut into byte pieces, read through Media3's interface as the one file it was. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JoinedDataSourceTest {

    private val whole = Random(3).nextBytes(250)
    private val cuts = listOf(0 until 100, 100 until 200, 200 until 250)
    private val uris = cuts.indices.map { Uri.parse("tdfile://${it + 1}") }
    private val left = mutableListOf<Uri>()

    /** One piece's bytes by its URI, the way TdDataSource answers for a Telegram file. */
    private inner class Piece : BaseDataSource(false) {
        private var inner: ByteArrayDataSource? = null
        override fun open(dataSpec: DataSpec): Long {
            val index = uris.indexOf(dataSpec.uri)
            val range = cuts[index]
            return ByteArrayDataSource(whole.copyOfRange(range.first, range.last + 1)).also { inner = it }.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner!!.read(buffer, offset, length)
        override fun getUri(): Uri? = inner?.uri
        override fun close() {
            inner?.close()
            inner = null
        }
    }

    private fun source() = JoinedDataSource(
        direct = ByteArrayDataSource(byteArrayOf(9, 9, 9)),
        pieces = DataSource.Factory { Piece() },
        onLeftPiece = { left += it },
    )

    private fun joined() = joinedUri(uris.mapIndexed { i, uri -> uri to cuts[i].count().toLong() })

    private fun readAll(source: DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(37)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `the pieces read as one file across the seams`() {
        val source = source()
        assertEquals(250L, source.open(DataSpec(joined())))
        assertArrayEquals(whole, readAll(source))
        source.close()
    }

    @Test
    fun `an open at a position lands in the right piece and reads on to the end`() {
        val source = source()
        assertEquals(60L, source.open(DataSpec.Builder().setUri(joined()).setPosition(190).build()))
        assertArrayEquals(whole.copyOfRange(190, 250), readAll(source))
        source.close()
        // A bounded read stops where it was told to, in the middle of a piece.
        assertEquals(30L, source.open(DataSpec.Builder().setUri(joined()).setPosition(90).setLength(30).build()))
        assertArrayEquals(whole.copyOfRange(90, 120), readAll(source))
        source.close()
    }

    @Test
    fun `a seek that leaves a piece behind says which`() {
        val source = source()
        source.open(DataSpec.Builder().setUri(joined()).setPosition(10).build())
        source.read(ByteArray(5), 0, 5)
        source.close()
        source.open(DataSpec.Builder().setUri(joined()).setPosition(220).build())
        assertEquals(listOf(uris[0]), left)
        source.close()
    }

    @Test
    fun `any other uri goes straight through`() {
        val source = source()
        assertEquals(3L, source.open(DataSpec(Uri.parse("tdfile://42"))))
        assertArrayEquals(byteArrayOf(9, 9, 9), readAll(source))
        source.close()
    }

    private fun windowed(start: Long, length: Long) =
        joinedUri(uris.mapIndexed { i, uri -> uri to cuts[i].count().toLong() }, start to length)

    @Test
    fun `a window shows the bytes inside it as the whole file, across a seam`() {
        // A stored video 150 bytes long, starting 70 bytes into the archive: through the seam at 100 and 200.
        val source = source()
        assertEquals(150L, source.open(DataSpec(windowed(70, 150))))
        assertArrayEquals(whole.copyOfRange(70, 220), readAll(source))
        source.close()
        // A seek inside the window is relative to its start.
        assertEquals(40L, source.open(DataSpec.Builder().setUri(windowed(70, 150)).setPosition(110).build()))
        assertArrayEquals(whole.copyOfRange(180, 220), readAll(source))
        source.close()
        // A bounded read stops where told, and never runs past the window's end.
        assertEquals(10L, source.open(DataSpec.Builder().setUri(windowed(70, 150)).setPosition(140).setLength(500).build()))
        assertArrayEquals(whole.copyOfRange(210, 220), readAll(source))
        source.close()
    }

    @Test(expected = java.io.IOException::class)
    fun `a window that leaves the pieces is refused`() {
        source().open(DataSpec(windowed(200, 100)))
    }
}
