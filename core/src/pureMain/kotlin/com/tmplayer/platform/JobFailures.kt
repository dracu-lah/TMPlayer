package com.tmplayer.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The last stop for an exception nobody caught in a background job (HX10).
 *
 * Without it, a job on an app-wide scope or a view model's scope that threw (a TDLib answer of an
 * unexpected kind, a file that went away, a JSON field missing) went to the thread's uncaught
 * handler: on Android that ends the process, on the desktop it printed `E/Crash` and left
 * whatever the job was doing half done. Here the failure is logged with the job's name, handed to
 * [report] (the opt-in crash reporting, which sends it only with the switch on) and dropped, so
 * the rest of the app carries on. The job itself is over: whoever started it must not assume it
 * finished, which is why the state it fills should start as Loading or empty, never as "done".
 *
 * Cancellation is not a failure and never reaches here.
 */
object JobFailures {

    /**
     * Told about every failure, after it is logged: the platform's crash reporting. Set once at
     * start by the app; null (the tests, a build without reporting) only logs.
     */
    @Volatile
    var report: ((Throwable) -> Unit)? = null

    /** How many failures were contained in this run, for the tests and the logs. */
    @Volatile
    var count: Int = 0
        private set

    /** A handler that names [where] in the log line, for a scope's context. */
    fun handler(where: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error -> handle(where, error) }

    /** Logs and reports [error] from [where], unless it is a cancellation. Never throws. */
    fun handle(where: String, error: Throwable) {
        if (error is CancellationException) return
        count += 1
        runCatching { Logger.w(TAG, "a background job in $where failed, the app carries on", error) }
        runCatching { report?.invoke(error) }
    }

    /**
     * A scope for work that outlives a screen, with a supervisor so one failed job leaves its
     * siblings running, and [handler] so a failure is contained rather than fatal.
     */
    fun scope(where: String, context: CoroutineContext): CoroutineScope =
        CoroutineScope(SupervisorJob() + context + handler(where))

    /**
     * What a view model passes to `ViewModel(scope)`: the same main dispatcher and supervisor as
     * the stock `viewModelScope`, plus [handler]. Without a main dispatcher (a plain JVM test) the
     * jobs run where they are resumed, as the stock scope does.
     */
    fun viewModelScope(where: String): CoroutineScope {
        val main: CoroutineContext = try {
            val dispatcher: MainCoroutineDispatcher = Dispatchers.Main
            dispatcher.immediate
        } catch (_: NotImplementedError) {
            EmptyCoroutineContext
        } catch (_: IllegalStateException) {
            EmptyCoroutineContext
        }
        return CoroutineScope(SupervisorJob() + main + handler(where))
    }

    private const val TAG = "JobFailures"
}
