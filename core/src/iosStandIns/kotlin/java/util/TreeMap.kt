package java.util

interface SortedMap<K, V> : MutableMap<K, V> {
    fun firstKey(): K
    fun lastKey(): K
}

/**
 * java.util.TreeMap: keys kept in their natural order (or a comparator's), so iteration, as in a
 * signed query string or a stable JSON dump, comes out sorted. A sorted list of keys beside a hash
 * map; fine for the small maps core sorts.
 */
class TreeMap<K, V>(private val comparator: Comparator<in K>? = null) : SortedMap<K, V> {
    private val store = HashMap<K, V>()
    private val order = ArrayList<K>()

    @Suppress("UNCHECKED_CAST")
    private fun compare(a: K, b: K): Int = comparator?.compare(a, b) ?: (a as Comparable<K>).compareTo(b)

    private fun slot(key: K): Int {
        var low = 0
        var high = order.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = compare(order[mid], key)
            when {
                c < 0 -> low = mid + 1
                c > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    override val size: Int get() = order.size
    override fun isEmpty(): Boolean = order.isEmpty()
    override fun containsKey(key: K): Boolean = slot(key) >= 0
    override fun containsValue(value: V): Boolean = store.containsValue(value)
    override fun get(key: K): V? = if (slot(key) >= 0) store[order[slot(key)]] else null

    override fun put(key: K, value: V): V? {
        val at = slot(key)
        return if (at >= 0) store.put(order[at], value) else {
            order.add(-(at + 1), key)
            store.put(key, value)
        }
    }

    override fun remove(key: K): V? {
        val at = slot(key)
        if (at < 0) return null
        return store.remove(order.removeAt(at))
    }

    override fun putAll(from: Map<out K, V>) = from.forEach { (k, v) -> put(k, v) }
    override fun clear() {
        order.clear()
        store.clear()
    }

    override fun firstKey(): K = order.firstOrNull() ?: throw NoSuchElementException()
    override fun lastKey(): K = order.lastOrNull() ?: throw NoSuchElementException()

    override val keys: MutableSet<K> get() = LinkedHashSet(order)
    override val values: MutableCollection<V> get() = order.mapTo(ArrayList()) { store.getValue(it) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = order.mapTo(LinkedHashSet()) { key ->
            object : MutableMap.MutableEntry<K, V> {
                override val key: K = key
                override val value: V get() = store.getValue(key)
                override fun setValue(newValue: V): V = @Suppress("UNCHECKED_CAST") (store.put(key, newValue) as V)
            }
        }

    override fun equals(other: Any?): Boolean = other is Map<*, *> && other.entries.map { it.key to it.value }.toSet() == entries.map { it.key to it.value }.toSet()
    override fun hashCode(): Int = store.hashCode()
    override fun toString(): String = order.joinToString(", ", "{", "}") { "$it=${store[it]}" }
}
