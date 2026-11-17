package android.util

import platform.Foundation.NSRecursiveLock

/**
 * A cache that drops what was used longest ago once it holds more than [maxSize], Android's: each
 * entry counts [sizeOf], 1 unless overridden, and every call is safe from any thread.
 */
open class LruCache<K : Any, V : Any>(private var maxSize: Int) {
    private val map = LinkedHashMap<K, V>()
    private val lock = NSRecursiveLock()
    private var size = 0

    init {
        require(maxSize > 0) { "maxSize <= 0" }
    }

    private inline fun <R> locked(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun get(key: K): V? {
        locked {
            map.remove(key)?.let { value ->
                map[key] = value
                return value
            }
        }
        val created = create(key) ?: return null
        locked {
            map.put(key, created)?.let { previous ->
                map[key] = previous
                return previous
            }
            size += safeSizeOf(key, created)
        }
        trimToSize(maxSize)
        return created
    }

    fun put(key: K, value: V): V? {
        val previous = locked {
            size += safeSizeOf(key, value)
            map.remove(key)?.also { size -= safeSizeOf(key, it) }.also { map[key] = value }
        }
        if (previous != null) entryRemoved(false, key, previous, value)
        trimToSize(maxSize)
        return previous
    }

    fun remove(key: K): V? {
        val previous = locked { map.remove(key)?.also { size -= safeSizeOf(key, it) } }
        if (previous != null) entryRemoved(false, key, previous, null)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            val evicted = locked {
                if (size <= maxSize || map.isEmpty()) return
                val eldest = map.entries.first()
                map.remove(eldest.key)
                size -= safeSizeOf(eldest.key, eldest.value)
                eldest.key to eldest.value
            }
            entryRemoved(true, evicted.first, evicted.second, null)
        }
    }

    fun evictAll() = trimToSize(-1)

    fun resize(maxSize: Int) {
        require(maxSize > 0) { "maxSize <= 0" }
        locked { this.maxSize = maxSize }
        trimToSize(maxSize)
    }

    fun size(): Int = locked { size }

    fun maxSize(): Int = locked { maxSize }

    /** A copy, least recently used first. */
    fun snapshot(): Map<K, V> = locked { LinkedHashMap(map) }

    protected open fun sizeOf(key: K, value: V): Int = 1

    protected open fun create(key: K): V? = null

    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    private fun safeSizeOf(key: K, value: V): Int = sizeOf(key, value).also { check(it >= 0) { "Negative size: $key=$value" } }

    override fun toString(): String = locked { "LruCache[maxSize=$maxSize]" }
}
