package com.tmplayer.data

import io.sentry.SentryEvent
import io.sentry.protocol.SentryException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportsFilterTest {
    private fun event(module: String?, type: String, value: String = ""): SentryEvent =
        SentryEvent().apply {
            exceptions = listOf(SentryException().apply { this.module = module; this.type = type; this.value = value })
        }

    @Test
    fun systemServerDeathIsNotOurs() {
        assertTrue(CrashReports.isNotOurs(event("android.os", "DeadSystemException")))
        assertFalse(CrashReports.isNotOurs(event("android.os", "DeadObjectException")))
        assertFalse(CrashReports.isNotOurs(event("com.tmplayer", "DeadSystemException")))
    }

    @Test
    fun classFormatErrorOnlyInTheAwtTrayClasses() {
        assertTrue(CrashReports.isNotOurs(event("java.lang", "ClassFormatError", "sun/awt/X11/XSystemTrayPeer")))
        assertFalse(CrashReports.isNotOurs(event("java.lang", "ClassFormatError", "com/tmplayer/Thing")))
    }

    @Test
    fun ordinaryCrashAndEmptyEventsPass() {
        assertFalse(CrashReports.isNotOurs(event("java.lang", "IllegalStateException", "boom")))
        assertFalse(CrashReports.isNotOurs(SentryEvent()))
    }
}
