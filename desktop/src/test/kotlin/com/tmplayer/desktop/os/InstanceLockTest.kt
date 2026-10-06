package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class InstanceLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a second launch forwards its arguments to the first and is told to exit`() {
        val received = LinkedBlockingQueue<List<String>>()
        val first = InstanceLock(tmp.root)
        val second = InstanceLock(tmp.root)
        val third = InstanceLock(tmp.root)
        try {
            assertTrue(first.acquire(emptyList()) { received.put(it) })
            first.ready = true

            assertFalse(second.acquire(listOf("tg://resolve?domain=example", "--fullscreen")) { error("never") })
            assertEquals(listOf("tg://resolve?domain=example", "--fullscreen"), received.poll(5, TimeUnit.SECONDS))

            // No arguments is still a message: it means "show the window".
            assertFalse(third.acquire(emptyList()) { error("never") })
            assertEquals(emptyList<String>(), received.poll(5, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            third.close()
        }
    }

    @Test
    fun `the lock is free again once the first closes, even with a stale socket file left`() {
        val first = InstanceLock(tmp.root)
        assertTrue(first.acquire(emptyList()) { })
        first.close()
        first.socketFile.writeText("left behind by a crash")

        val next = InstanceLock(tmp.root)
        try {
            assertTrue(next.acquire(emptyList()) { })
        } finally {
            next.close()
        }
    }

    @Test
    fun `a directory too deep for a socket path falls back to the temp directory`() {
        val deep = File(tmp.root, "a".repeat(60) + "/" + "b".repeat(60))
        val path = InstanceLock.socketPath(deep, "instance")
        assertTrue(path.absolutePath.startsWith(File(System.getProperty("java.io.tmpdir")).absolutePath))
        assertEquals(path, InstanceLock.socketPath(deep, "instance"))

        val received = LinkedBlockingQueue<List<String>>()
        val first = InstanceLock(deep)
        val second = InstanceLock(deep)
        try {
            assertTrue(first.acquire(emptyList()) { received.put(it) })
            first.ready = true
            assertFalse(second.acquire(listOf("x")) { })
            assertEquals(listOf("x"), received.poll(5, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `a first launch with no window yet is told starting, and a later launch leaves it be`() {
        val received = LinkedBlockingQueue<List<String>>()
        val first = InstanceLock(tmp.root)
        val second = InstanceLock(tmp.root)
        try {
            assertTrue(first.acquire(emptyList()) { received.put(it) })
            // Same process, so never a candidate for ending, however long it has been starting.
            assertFalse(second.acquire(listOf("x")) { })
            assertEquals(null, received.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `a holder stuck without a window is ended and the later launch takes over`() {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), StuckHolder::class.java.name, tmp.root.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            // Wait for the child to take the lock and write its pid.
            val pid = File(tmp.root, "instance.pid")
            val deadline = System.currentTimeMillis() + 20_000
            while (!pid.isFile && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("child never took the lock: " + child.inputStream.bufferedReader().readText().take(500), pid.isFile)

            val later = InstanceLock(tmp.root, stuckAfterMs = 0)
            try {
                assertTrue(later.acquire(emptyList()) { })
                assertFalse(child.isAlive)
            } finally {
                later.close()
            }
        } finally {
            child.destroyForcibly()
        }
    }
}

/** A first launch that takes the lock and never shows a window. */
object StuckHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        InstanceLock(File(args[0])).acquire(emptyList()) { }
        Thread.sleep(60_000)
    }
}
