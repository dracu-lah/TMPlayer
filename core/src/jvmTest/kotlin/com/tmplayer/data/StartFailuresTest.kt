package com.tmplayer.data

import com.tmplayer.data.StartFailures.Kind
import com.tmplayer.i18n.L
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign in failure screen's sentence when TDLib does not start (#27, #30, #35). */
class StartFailuresTest {

    /** #27, Windows 11 with Smart App Control on, word for word but the user's name. */
    private val blocked = "C:\\Users\\name\\AppData\\Local\\Temp\\windows-x6414911547184123555596\\z.dll: " +
        "An Application Control policy has blocked this file"

    /** #30, the AppImage on Pop!_OS 22.04. */
    private val tooOld = "/tmp/linux-x641037444194577397710/libtdjsonjava.so: /lib/x86_64-linux-gnu/libstdc++.so.6: " +
        "version `GLIBCXX_3.4.32' not found (required by /tmp/linux-x641037444194577397710/libtdjsonjava.so)"

    /** #35, a storage location on a drive that is gone. */
    private val folder = "Can't init files directory \"F:\\TM player\\TMPlayer\\cache\": [WindowsError : The system " +
        "cannot find the path specified. : 3 : Can't create directory \"F:\\TM player\\TMPlayer\\cache\": " +
        "[WindowsError : The system cannot find the path specified. : 3 : Can't create directory \"F:\"]]"

    @Test
    fun `Windows blocking the DLL reads as blocked, without the temp path`() {
        assertEquals(Kind.Blocked, StartFailures.kind(blocked))
        val shown = StartFailures.describe(UnsatisfiedLinkError(blocked))
        assertEquals(L.errorsStartBlocked, shown)
        assertFalse(shown.contains("z.dll"))
        assertTrue(shown.contains("tmplayer.org/install"))
        assertEquals(Kind.Blocked, StartFailures.kind("0x800711C7"))
        assertEquals(Kind.Blocked, StartFailures.kind("This program is blocked by group policy."))
    }

    @Test
    fun `a Linux older than the library reads as too old, whichever symbol is missing`() {
        assertEquals(Kind.SystemTooOld, StartFailures.kind(tooOld))
        assertEquals(Kind.SystemTooOld, StartFailures.kind("libtdjsonjava.so: /lib64/libc.so.6: version `GLIBC_2.38' not found"))
        assertEquals(Kind.SystemTooOld, StartFailures.kind("version 'CXXABI_1.3.15' not found"))
        assertEquals(L.errorsStartSystemTooOld, StartFailures.describe(UnsatisfiedLinkError(tooOld)))
    }

    @Test
    fun `a missing drive names the folder TDLib could not make`() {
        assertEquals(Kind.FolderUnusable, StartFailures.kind(folder))
        assertEquals("F:\\TM player\\TMPlayer\\cache", StartFailures.folder(folder))
        assertEquals(L.errorsStartFolder("F:\\TM player\\TMPlayer\\cache"), StartFailures.describeRejected(folder))
        assertEquals(L.errorsStartFolderUnnamed, StartFailures.describeRejected("Can't init database directory"))
        assertEquals(Kind.FolderUnusable, StartFailures.kind("Can't open database directory \"/x\""))
    }

    @Test
    fun `the reason is found deeper in the chain of causes`() {
        val wrapped = IllegalStateException("could not create the client", UnsatisfiedLinkError(blocked))
        assertEquals(L.errorsStartBlocked, StartFailures.describe(wrapped))
    }

    @Test
    fun `anything else keeps the raw reason for the report`() {
        assertEquals(L.errorsStartFailed("boom"), StartFailures.describe(RuntimeException("boom")))
        assertEquals(L.errorsStartRejected("Valid api_id must be provided"), StartFailures.describeRejected("Valid api_id must be provided"))
        assertEquals(Kind.Other, StartFailures.kind("the file was blocked"))
        assertEquals(L.errorsStartFailed("UnsatisfiedLinkError"), StartFailures.describe(UnsatisfiedLinkError()))
    }

    @Test
    fun `garbage never throws and never comes back empty`() {
        val loop = RuntimeException("loop")
        val cyclic = RuntimeException("outer", loop).also { loop.initCause(it) }
        val inputs = listOf(
            null, "", " ", "\u0000", "\uD800", "\"", "\"\"", "\"" + "x".repeat(5_000) + "\"", "a".repeat(100_000),
            "Can't create directory \"", "Can't create directory \"\"", "version `GLIBC_' not found", "version",
            "Can't init files directory \"" + "F:\\".repeat(10_000) + "\"",
        )
        for (input in inputs) {
            StartFailures.kind(input)
            StartFailures.folder(input)
            assertTrue(StartFailures.describeRejected(input).isNotBlank())
            assertTrue(StartFailures.describe(RuntimeException(input)).isNotBlank())
        }
        assertTrue(StartFailures.describe(null).isNotBlank())
        assertTrue(StartFailures.describe(cyclic).isNotBlank())
        assertNull(StartFailures.folder("Can't create directory \"\""))
        assertTrue(StartFailures.describeRejected("x".repeat(100_000)).length < 2_000)
    }
}
