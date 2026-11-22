package com.tmplayer.ui

import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
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
            drain(looper, START_ROUNDS, STEP_MS)
        }
    }

    override fun finished(description: Description) {
        runCatching { drain(shadowOf(Looper.getMainLooper()), ROUNDS, STEP_MS) }
    }

    /**
     * Runs what is ready on the looper, then moves the clock on a little and runs again, [rounds]
     * times (running a post can make the next one: apply, then recompose). Counted task by task,
     * never `idleFor`: a screen that recomposes itself without end (a carousel's timer, a state
     * written in a composition) posts its next task at the same instant, so `idleFor` never
     * returned and a full run sat in one such drain for half an hour. Here a round gives up after
     * [MAX_TASKS] tasks, so a loop costs a bounded few thousand and the class goes on.
     */
    private fun drain(looper: org.robolectric.shadows.ShadowLooper, rounds: Int, stepMs: Long) {
        var capped = false
        repeat(rounds) {
            capped = runTasks(looper) || capped
            ShadowSystemClock.advanceBy(Duration.ofMillis(stepMs))
        }
        capped = runTasks(looper) || capped
        if (capped) System.err.println("DrainMainLooperRule: gave up after $MAX_TASKS tasks in a round, the looper still had work (a screen that recomposes without end?)")
    }

    /** Runs up to [MAX_TASKS] ready tasks; true when it stopped at the cap with work left. */
    private fun runTasks(looper: org.robolectric.shadows.ShadowLooper): Boolean {
        var ran = 0
        while (!looper.isIdle && ran < MAX_TASKS) {
            looper.runOneTask()
            ran++
        }
        return ran >= MAX_TASKS && !looper.isIdle
    }

    private companion object {
        const val ROUNDS = 25
        const val START_ROUNDS = 5
        const val STEP_MS = 20L
        const val MAX_TASKS = 400
    }
}
