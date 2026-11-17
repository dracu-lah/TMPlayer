package java.util.concurrent

fun interface ThreadFactory {
    fun newThread(task: java.lang.Runnable): java.lang.Thread
}

/**
 * An executor core and ui hand to coroutines as a dispatcher (asCoroutineDispatcher); on iOS it only
 * carries the name its thread factory gives, for the single thread context made from it.
 */
class ExecutorService internal constructor(val threadName: String) {
    fun shutdown() = Unit
}

object Executors {
    fun newSingleThreadExecutor(): ExecutorService = ExecutorService("pool-thread")

    fun newSingleThreadExecutor(factory: ThreadFactory): ExecutorService =
        ExecutorService(factory.newThread(java.lang.Runnable {}).name)
}
