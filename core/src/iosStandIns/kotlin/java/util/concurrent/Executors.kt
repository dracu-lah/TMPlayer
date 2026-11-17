package java.util.concurrent

import java.util.concurrent.atomic.AtomicInteger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

fun interface ThreadFactory {
    fun newThread(task: java.lang.Runnable): java.lang.Thread
}

/** java.util.concurrent.Executor: something that runs a task, now or later, on a thread of its own. */
fun interface Executor {
    fun execute(command: java.lang.Runnable)
}

/**
 * A single thread executor: its tasks run one after the other on a serial queue of its own, named
 * by its thread factory. Core and ui also hand it to coroutines as a dispatcher
 * (asCoroutineDispatcher), which makes a single thread context of the same name.
 */
open class ExecutorService internal constructor(val threadName: String) : Executor {
    private val queue by lazy { dispatch_queue_create(threadName, null) }

    override fun execute(command: java.lang.Runnable) {
        dispatch_async(queue) { command.run() }
    }

    fun shutdown() = Unit
}

/** A pool of one thread, which is what Android's code asks of it here: tasks in order, a full queue refusing the rest. */
class ThreadPoolExecutor(
    @Suppress("UNUSED_PARAMETER") corePoolSize: Int,
    @Suppress("UNUSED_PARAMETER") maximumPoolSize: Int,
    @Suppress("UNUSED_PARAMETER") keepAliveTime: Long,
    @Suppress("UNUSED_PARAMETER") unit: TimeUnit,
    private val workQueue: BlockingQueue<java.lang.Runnable>,
    factory: ThreadFactory,
    private val handler: RejectedExecutionHandler,
) : ExecutorService(factory.newThread(java.lang.Runnable {}).name) {
    private val waiting = AtomicInteger(0)

    /** Threads that end when idle: the serial queue holds no thread while it is empty, so there is nothing to time out. */
    @Suppress("UNUSED_PARAMETER")
    fun allowCoreThreadTimeOut(value: Boolean) = Unit

    override fun execute(command: java.lang.Runnable) {
        if (waiting.incrementAndGet() > workQueue.capacity) {
            waiting.decrementAndGet()
            handler.rejectedExecution(command, this)
            return
        }
        super.execute { try { command.run() } finally { waiting.decrementAndGet() } }
    }

    /** What happens to a task the queue has no room for, Java's. */
    fun interface RejectedExecutionHandler {
        fun rejectedExecution(command: java.lang.Runnable, executor: ThreadPoolExecutor)
    }

    /** Drops the task without a word. */
    class DiscardPolicy : RejectedExecutionHandler {
        override fun rejectedExecution(command: java.lang.Runnable, executor: ThreadPoolExecutor) = Unit
    }
}

/** A queue that holds at most [capacity] tasks, Java's: here only its size is asked of it. */
interface BlockingQueue<E> {
    val capacity: Int
}

class ArrayBlockingQueue<E>(override val capacity: Int) : BlockingQueue<E>

object Executors {
    fun newSingleThreadExecutor(): ExecutorService = ExecutorService("pool-thread")

    fun newSingleThreadExecutor(factory: ThreadFactory): ExecutorService =
        ExecutorService(factory.newThread(java.lang.Runnable {}).name)
}
