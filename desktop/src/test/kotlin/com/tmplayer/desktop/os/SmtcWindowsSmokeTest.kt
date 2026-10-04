package com.tmplayer.desktop.os

import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * Runs SMTC against a real native window, never shown, on Windows only (the CI Windows job runs
 * it with the other desktop tests). It asserts what the player relies on: creating, updating,
 * clearing and releasing a session never throws, whether SMTC came up or the session fell back.
 * Which of the two happened is printed as `smtc: ...`, which the CI step lifts into a notice.
 */
class SmtcWindowsSmokeTest {

    @Test
    fun aSessionOnAHiddenWindowNeverThrows() {
        assumeTrue(OsInfo.isWindows)
        assumeFalse(GraphicsEnvironment.isHeadless())
        var frame: Frame? = null
        SwingUtilities.invokeAndWait {
            frame = Frame("TMPlayer SMTC smoke test").apply {
                setSize(320, 240)
                // A native window without showing it: the HWND SMTC is tied to.
                addNotify()
            }
        }
        try {
            val session = MediaSession.create(object : MediaSessionCallbacks {}, frame)
            val live = session is SmtcMediaSession
            session.update("Smoke test", 60_000, 0, playing = true)
            session.update("Smoke test", 60_000, 1_000, playing = false, canGoNext = true)
            session.update("Second title", 30_000, 0, playing = true)
            session.clear()
            session.update("Third title", 30_000, 0, playing = true)
            // The calls run on SMTC's own thread; give them a moment to fail, if they are going to.
            Thread.sleep(1_000)
            val healthy = (session as? SmtcMediaSession)?.healthy == true
            session.release()
            println(
                when {
                    !live -> "smtc: fell back to NoMediaSession (see the Smtc log line for why)"
                    healthy -> "smtc: live, every call on the hidden window returned success"
                    else -> "smtc: created, then a call failed and the session went quiet (see the Smtc log line)"
                },
            )
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }
}
