package com.tmplayer.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign in's answer on its way, and when the proxy settings are offered in the pane (ON-17). */
class SubmissionTest {

    @Test
    fun `an answer that never comes releases the button and offers the proxy settings`() {
        val submission = Submission()
        var sent = 0
        submission.start { sent++ }
        assertTrue(submission.busy)
        assertFalse(submission.slow)
        submission.giveUp()
        assertFalse(submission.busy)
        assertTrue(submission.slow)
        assertEquals(1, sent)
    }

    @Test
    fun `an answer in time never offers it, and a new press clears it`() {
        val submission = Submission()
        submission.start {}
        submission.finish()
        submission.giveUp()
        assertFalse("a release before the ceiling is not slow", submission.slow)
        submission.start {}
        submission.giveUp()
        assertTrue(submission.slow)
        submission.start {}
        assertFalse("the next press starts the count again", submission.slow)
    }

    @Test
    fun `a second press while busy sends nothing`() {
        val submission = Submission()
        var sent = 0
        submission.start { sent++ }
        submission.start { sent++ }
        assertEquals(1, sent)
    }
}
