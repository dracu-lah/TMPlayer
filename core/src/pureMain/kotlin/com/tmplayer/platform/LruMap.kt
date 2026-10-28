package com.tmplayer.platform

/**
 * A map that keeps the [capacity] entries used last, as a `LinkedHashMap` in access order with
 * `removeEldestEntry` does on the JVM, guarded by one lock: [locked] runs a block holding it.
 * [onEvict] is handed each entry [set] pushes out, after the lock is let go.
 */
class LruMap<K, V>(private val capacity: Int, private val onEvict: (V) -> Unit = {}) {
    private val map = LinkedHashMap<K, V>()
    private val lock = PlainLock()

    operator fun get(key: K): V? = lock.locked {
        val value = map.remove(key) ?: return@locked null
        map[key] = value
        value
    }

    operator fun set(key: K, value: V) {
        val evicted = lock.locked {
            map.remove(key)
            map[key] = value
            buildList { while (map.size > capacity) map.remove(map.keys.first())?.let(::add) }
        }
        evicted.forEach(onEvict)
    }

    fun remove(key: K): V? = lock.locked { map.remove(key) }
    fun clear() = lock.locked { map.clear() }
    val values: Collection<V> get() = lock.locked { map.values.toList() }
    val size: Int get() = lock.locked { map.size }

    /** Runs [block] holding the map's lock, for a read and a write that must not be split. */
    fun <T> locked(block: LruMap<K, V>.() -> T): T = lock.locked { block() }
}
