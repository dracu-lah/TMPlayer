@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package kotlinx.coroutines

/**
 * kotlinx.coroutines' Executor.asCoroutineDispatcher(), which exists on the JVM only: on iOS the
 * executor's one thread becomes a single thread context of the same name.
 */
fun java.util.concurrent.ExecutorService.asCoroutineDispatcher(): CloseableCoroutineDispatcher = newSingleThreadContext(threadName)
