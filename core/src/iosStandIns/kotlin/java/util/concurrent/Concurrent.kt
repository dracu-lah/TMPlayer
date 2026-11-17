package java.util.concurrent

import java.lang.Monitors

/**
 * java.util.concurrent.ConcurrentHashMap: every operation under the map's own lock, with the
 * JDK's atomic compound operations (putIfAbsent, computeIfAbsent, ...) atomic here too. Its views
 * walk a copy, as the JDK's weakly consistent iterators may, and removals through them reach the map.
 */
class ConcurrentHashMap<K : Any, V : Any>(initialCapacity: Int = 16) : MutableMap<K, V> {
    private val map = LinkedHashMap<K, V>(initialCapacity)

    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)

    override val size: Int get() = locked { map.size }
    override fun isEmpty(): Boolean = locked { map.isEmpty() }
    override fun containsKey(key: K): Boolean = locked { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = locked { map.containsValue(value) }
    override fun get(key: K): V? = locked { map[key] }
    override fun put(key: K, value: V): V? = locked { map.put(key, value) }
    override fun remove(key: K): V? = locked { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = locked { map.putAll(from) }
    override fun clear() = locked { map.clear() }

    fun getOrDefault(key: K, defaultValue: V): V = locked { map[key] ?: defaultValue }
    fun putIfAbsent(key: K, value: V): V? = locked { map[key] ?: run { map[key] = value; null } }
    fun remove(key: K, value: V): Boolean = locked { if (map[key] == value) { map.remove(key); true } else false }
    fun replace(key: K, value: V): V? = locked { if (key in map) map.put(key, value) else null }
    fun replace(key: K, oldValue: V, newValue: V): Boolean = locked { if (map[key] == oldValue) { map[key] = newValue; true } else false }
    fun computeIfAbsent(key: K, mapping: (K) -> V?): V? = locked { map[key] ?: mapping(key)?.also { map[key] = it } }
    fun computeIfPresent(key: K, remapping: (K, V) -> V?): V? = locked {
        val old = map[key] ?: return@locked null
        remapping(key, old).also { if (it == null) map.remove(key) else map[key] = it }
    }
    fun compute(key: K, remapping: (K, V?) -> V?): V? = locked {
        remapping(key, map[key]).also { if (it == null) map.remove(key) else map[key] = it }
    }
    fun merge(key: K, value: V, remapping: (V, V) -> V?): V? = locked {
        val old = map[key]
        val new = if (old == null) value else remapping(old, value)
        new.also { if (it == null) map.remove(key) else map[key] = it }
    }

    override val keys: MutableSet<K> get() = locked { RemovingSet(map.keys.toMutableSet()) { k -> remove(k) } }
    override val values: MutableCollection<V> get() = locked { RemovingList(map.values.toMutableList()) { v -> locked { map.entries.firstOrNull { it.value == v }?.let { map.remove(it.key) } } } }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = locked {
            RemovingSet(map.entries.map { Entry(it.key, it.value) }.toMutableSet<MutableMap.MutableEntry<K, V>>()) { e -> remove(e.key, e.value) }
        }

    private inner class Entry(override val key: K, private var current: V) : MutableMap.MutableEntry<K, V> {
        override val value: V get() = current
        override fun setValue(newValue: V): V = current.also { current = newValue; put(key, newValue) }
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == current
        override fun hashCode(): Int = key.hashCode() xor current.hashCode()
        override fun toString(): String = "$key=$current"
    }

    override fun equals(other: Any?): Boolean = locked { map == other }
    override fun hashCode(): Int = locked { map.hashCode() }
    override fun toString(): String = locked { map.toString() }

    companion object {
        /** ConcurrentHashMap.newKeySet(): a set with the map's own locking. */
        fun <K : Any> newKeySet(): MutableSet<K> = ConcurrentKeySet()
    }
}

/** A copy that forwards removals to where it was copied from. */
private class RemovingSet<E>(private val copy: MutableSet<E>, private val onRemove: (E) -> Unit) : MutableSet<E> by copy {
    override fun remove(element: E): Boolean = copy.remove(element).also { if (it) onRemove(element) }
    override fun removeAll(elements: Collection<E>): Boolean = elements.map { remove(it) }.any { it }
    override fun retainAll(elements: Collection<E>): Boolean = copy.filter { it !in elements }.map { remove(it) }.any { it }
    override fun clear() = copy.toList().forEach { remove(it) }
    override fun iterator(): MutableIterator<E> = forwarding(copy.iterator(), onRemove)
}

private class RemovingList<E>(private val copy: MutableList<E>, private val onRemove: (E) -> Unit) : MutableCollection<E> by copy {
    override fun remove(element: E): Boolean = copy.remove(element).also { if (it) onRemove(element) }
    override fun removeAll(elements: Collection<E>): Boolean = elements.map { remove(it) }.any { it }
    override fun retainAll(elements: Collection<E>): Boolean = copy.filter { it !in elements }.map { remove(it) }.any { it }
    override fun clear() = copy.toList().forEach { remove(it) }
    override fun iterator(): MutableIterator<E> = forwarding(copy.iterator(), onRemove)
}

private fun <E> forwarding(inner: MutableIterator<E>, onRemove: (E) -> Unit): MutableIterator<E> = object : MutableIterator<E> {
    private var last: Any? = NONE
    override fun hasNext(): Boolean = inner.hasNext()
    override fun next(): E = inner.next().also { last = it }
    override fun remove() {
        check(last !== NONE) { "next() first" }
        inner.remove()
        @Suppress("UNCHECKED_CAST")
        onRemove(last as E)
        last = NONE
    }
}

private val NONE = Any()

/** java.util.concurrent.CopyOnWriteArrayList: writes copy the list, reads see a stable snapshot. */
/** The set newKeySet() gives: a ConcurrentHashMap's keys, every operation under its lock. */
private class ConcurrentKeySet<K : Any> : AbstractMutableSet<K>() {
    private val map = ConcurrentHashMap<K, Boolean>()
    override val size: Int get() = map.size
    override fun add(element: K): Boolean = map.putIfAbsent(element, true) == null
    override fun remove(element: K): Boolean = map.remove(element) != null
    override fun contains(element: K): Boolean = map.containsKey(element)
    override fun clear() = map.clear()
    override fun iterator(): MutableIterator<K> = map.keys.iterator()
}

class CopyOnWriteArrayList<E>(initial: Collection<E> = emptyList()) : MutableList<E> {
    private var items: List<E> = initial.toList()

    private inline fun <R> write(block: (MutableList<E>) -> R): R = Monitors.synchronized(this) {
        val copy = items.toMutableList()
        block(copy).also { items = copy }
    }

    override val size: Int get() = items.size
    override fun contains(element: E): Boolean = items.contains(element)
    override fun containsAll(elements: Collection<E>): Boolean = items.containsAll(elements)
    override fun get(index: Int): E = items[index]
    override fun indexOf(element: E): Int = items.indexOf(element)
    override fun lastIndexOf(element: E): Int = items.lastIndexOf(element)
    override fun isEmpty(): Boolean = items.isEmpty()
    override fun iterator(): MutableIterator<E> = items.toMutableList().iterator()
    override fun listIterator(): MutableListIterator<E> = items.toMutableList().listIterator()
    override fun listIterator(index: Int): MutableListIterator<E> = items.toMutableList().listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = items.subList(fromIndex, toIndex).toMutableList()
    override fun add(element: E): Boolean = write { it.add(element) }
    override fun add(index: Int, element: E) = write { it.add(index, element) }
    override fun addAll(elements: Collection<E>): Boolean = write { it.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<E>): Boolean = write { it.addAll(index, elements) }
    override fun clear() = write { it.clear() }
    override fun remove(element: E): Boolean = write { it.remove(element) }
    override fun removeAll(elements: Collection<E>): Boolean = write { it.removeAll(elements) }
    override fun removeAt(index: Int): E = write { it.removeAt(index) }
    override fun retainAll(elements: Collection<E>): Boolean = write { it.retainAll(elements) }
    override fun set(index: Int, element: E): E = write { it.set(index, element) }
    fun addIfAbsent(element: E): Boolean = write { if (element in it) false else it.add(element) }
    override fun equals(other: Any?): Boolean = items == other
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = items.toString()
}

enum class TimeUnit(private val nanos: Long) {
    NANOSECONDS(1), MICROSECONDS(1_000), MILLISECONDS(1_000_000), SECONDS(1_000_000_000),
    MINUTES(60_000_000_000), HOURS(3_600_000_000_000), DAYS(86_400_000_000_000);

    fun toMillis(duration: Long): Long = duration * nanos / 1_000_000
    fun toSeconds(duration: Long): Long = duration * nanos / 1_000_000_000
    fun toNanos(duration: Long): Long = duration * nanos
    fun toMinutes(duration: Long): Long = duration * nanos / 60_000_000_000
    fun toHours(duration: Long): Long = duration * nanos / 3_600_000_000_000
    fun toDays(duration: Long): Long = duration * nanos / 86_400_000_000_000
}
