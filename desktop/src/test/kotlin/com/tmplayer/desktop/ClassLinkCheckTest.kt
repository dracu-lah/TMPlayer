package com.tmplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Every class of the app's own code (:core, :ui and :desktop), as the compiler wrote it, loads and
 * links with the bytecode verifier on, on every platform's CI. 1.28.1 shipped a SeriesKt the JVM
 * refused (HX1), but that fault came from ProGuard's frames, so it is the release check that
 * catches it: `verifyReleaseClasses` and the packaged app's `--self-test` run [ClassLinkCheck]
 * over ProGuard's jars. This one keeps a compiler fault of the same kind from reaching them.
 */
class ClassLinkCheckTest {

    @Test
    fun `every class of the app's own code passes the verifier`() {
        // The JVM verifies classes off the application classpath unless told not to.
        val args = ManagementFactory.getRuntimeMXBean().inputArguments
        assertTrue("the verifier is off: $args", args.none { it == "-Xverify:none" || it == "-noverify" || it == "-XX:-BytecodeVerificationRemote" })

        val result = ClassLinkCheck.run()
        // :core, :ui and :desktop each come from their own folder or jar.
        assertEquals("roots: ${result.roots}", 3, result.roots.size)
        assertTrue(
            "${result.failures.size} classes failed to link:\n" + result.failures.joinToString("\n"),
            result.failures.isEmpty(),
        )
        assertTrue("only ${result.linked} classes linked", result.linked > 1000)
        println("linked ${result.linked} com.tmplayer classes from ${result.roots}")
    }
}
