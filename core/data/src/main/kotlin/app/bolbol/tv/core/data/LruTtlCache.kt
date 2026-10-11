package app.bolbol.tv.core.data

/**
 * Tiny thread-safe LRU map with a time-to-live per entry (no Android classes, so it runs in JVM tests).
 * Reads refresh recency; an expired entry is removed on read.
 */
internal class LruTtlCache<K, V : Any>(
    private val maxSize: Int,
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val value: V, val storedAtMs: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxSize
    }

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (clock() - entry.storedAtMs >= ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, clock())
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size
}
