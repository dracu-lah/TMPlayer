package java.util.concurrent.atomic

import java.lang.Monitors

// The java.util.concurrent.atomic classes core uses, each operation under the object's own lock:
// atomic as the JDK's are, if not lock free.

class AtomicLong(initial: Long = 0L) {
    private var value = initial
    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)

    fun get(): Long = locked { value }
    fun set(newValue: Long) = locked { value = newValue }
    fun lazySet(newValue: Long) = set(newValue)
    fun getAndSet(newValue: Long): Long = locked { value.also { value = newValue } }
    fun incrementAndGet(): Long = locked { ++value }
    fun decrementAndGet(): Long = locked { --value }
    fun getAndIncrement(): Long = locked { value++ }
    fun getAndDecrement(): Long = locked { value-- }
    fun addAndGet(delta: Long): Long = locked { value += delta; value }
    fun getAndAdd(delta: Long): Long = locked { value.also { value += delta } }
    fun compareAndSet(expected: Long, newValue: Long): Boolean = locked { if (value == expected) { value = newValue; true } else false }
    fun updateAndGet(update: (Long) -> Long): Long = locked { update(value).also { value = it } }
    fun getAndUpdate(update: (Long) -> Long): Long = locked { value.also { value = update(it) } }
    fun accumulateAndGet(x: Long, accumulate: (Long, Long) -> Long): Long = locked { accumulate(value, x).also { value = it } }
    override fun toString(): String = get().toString()
}

class AtomicInteger(initial: Int = 0) {
    private var value = initial
    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)

    fun get(): Int = locked { value }
    fun set(newValue: Int) = locked { value = newValue }
    fun lazySet(newValue: Int) = set(newValue)
    fun getAndSet(newValue: Int): Int = locked { value.also { value = newValue } }
    fun incrementAndGet(): Int = locked { ++value }
    fun decrementAndGet(): Int = locked { --value }
    fun getAndIncrement(): Int = locked { value++ }
    fun getAndDecrement(): Int = locked { value-- }
    fun addAndGet(delta: Int): Int = locked { value += delta; value }
    fun getAndAdd(delta: Int): Int = locked { value.also { value += delta } }
    fun compareAndSet(expected: Int, newValue: Int): Boolean = locked { if (value == expected) { value = newValue; true } else false }
    fun updateAndGet(update: (Int) -> Int): Int = locked { update(value).also { value = it } }
    fun getAndUpdate(update: (Int) -> Int): Int = locked { value.also { value = update(it) } }
    override fun toString(): String = get().toString()
}

class AtomicBoolean(initial: Boolean = false) {
    private var value = initial
    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)

    fun get(): Boolean = locked { value }
    fun set(newValue: Boolean) = locked { value = newValue }
    fun lazySet(newValue: Boolean) = set(newValue)
    fun getAndSet(newValue: Boolean): Boolean = locked { value.also { value = newValue } }
    fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean = locked { if (value == expected) { value = newValue; true } else false }
    override fun toString(): String = get().toString()
}

class AtomicReference<V>(initial: V) {
    private var value = initial
    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)

    fun get(): V = locked { value }
    fun set(newValue: V) = locked { value = newValue }
    fun getAndSet(newValue: V): V = locked { value.also { value = newValue } }
    fun compareAndSet(expected: V, newValue: V): Boolean = locked { if (value === expected) { value = newValue; true } else false }
    fun updateAndGet(update: (V) -> V): V = locked { update(value).also { value = it } }
    fun getAndUpdate(update: (V) -> V): V = locked { value.also { value = update(it) } }
    override fun toString(): String = get().toString()
}
