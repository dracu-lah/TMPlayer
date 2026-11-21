package com.tmplayer.ui

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The picture decode the app starts from many threads at once ([NativeJniWarmUp]): 32 threads
 * decode a file together as the first decode of a fresh JVM (`forkEvery = 1`). Without the
 * warm-up the native library aborts the whole JVM ("Class not found: java/nio/ShortBuffer",
 * exit value 134) in nearly every run; with it every thread gets its picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeJniWarmUpTest {

    @Test
    fun `many threads decoding a picture at once all get it`() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg==",
        )
        val file = File.createTempFile("pix", ".png").apply { writeBytes(png); deleteOnExit() }
        val start = CountDownLatch(1)
        val widths = ConcurrentLinkedQueue<Int>()
        val threads = List(32) {
            thread {
                start.await()
                widths += BitmapFactory.decodeFile(file.path)?.width ?: -1
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertEquals(List(32) { 1 }, widths.toList())
    }
}
