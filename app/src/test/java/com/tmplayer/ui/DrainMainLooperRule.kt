package com.tmplayer.ui

import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Keeps Compose alive across the tests of one Robolectric class (HX92).
 *
 * The fault: Compose keeps `AndroidUiDispatcher.Main` for the whole process, and it posts to the main
 * looper at most once until that post has run (`scheduledTrampolineDispatch`, `scheduledFrameDispatch`).
 * Everything that resumes on it rides on that post: every `LaunchedEffect`, and
 * `GlobalSnapshotManager`, which tells every composition that state was written. Robolectric throws
 * the looper's queue away between two tests. When a post was still in it (something resumed on the
 * dispatcher while Robolectric tore the test down, which a loaded machine makes likely), the flags
 * stay on for good: the next frames still compose, but no effect runs and no state change ever
 * recomposes, so focus is never placed, a model never starts and every later test of the class
 * fails ("expected <Saved Messages> but was <>", "List is empty", "expected:<13> but was:<0>"). It hit
 * about one run in four and most runs on a busy machine, always from the second test on.
 * `forkEvery = 1` does not help: the poison is made inside the class's own JVM, and a class that
 * passes alone fails in a full run when the machine is busier.
 *
 * Use it in every test class that composes a screen: `@get:Rule val drain = DrainMainLooperRule()`.
 * - After each test it runs the looper on a little, in virtual time, so what the test left posted
 *   has run before Robolectric clears the queue.
 * - Before each test, with the queue fresh and empty, a post that is still marked as scheduled is
 *   stale: it is cleared and the work that waited for it is run (what the dropped post would have done).
 *
 * The repair reads Compose's private fields, so it is a test aid only and gives up quietly (the tests
 * then behave as they did before) if a Compose update renames them.
 */
class DrainMainLooperRule : TestWatcher() {

    override fun starting(description: Description) {
        runCatching {
            val looper = shadowOf(Looper.getMainLooper())
            if (!looper.isIdle) return
            val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as? CoroutineDispatcher ?: return
            val type = dispatcher.javaClass
            fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
            val trampoline = field("scheduledTrampolineDispatch")
            val frame = field("scheduledFrameDispatch")
            if (!trampoline.getBoolean(dispatcher) && !frame.getBoolean(dispatcher)) return
            System.err.println("DrainMainLooperRule: cleared a stale Compose post before ${description.methodName}")
            trampoline.setBoolean(dispatcher, false)
            frame.setBoolean(dispatcher, false)
            // A dispatch with nothing in it posts the dispatcher's callback again, which runs
            // everything that was queued behind the dropped one, the snapshot notifier included.
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { })
            looper.idleFor(Duration.ofMillis(STEP_MS))
        }
    }

    override fun finished(description: Description) {
        // A few rounds, since running a post can make the next one (apply, then recompose).
        runCatching {
            repeat(ROUNDS) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS)) }
        }
    }

    private companion object {
        const val ROUNDS = 5
        const val STEP_MS = 100L
    }
}
