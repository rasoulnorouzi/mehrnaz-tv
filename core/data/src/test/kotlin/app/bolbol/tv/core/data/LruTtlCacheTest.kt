package app.bolbol.tv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruTtlCacheTest {

    private var now = 0L

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val cache = LruTtlCache<Int, String>(maxSize = 2, ttlMs = 1_000, clock = { now })
        cache.put(1, "a")
        cache.put(2, "b")
        cache.get(1) // 2 is now the eldest
        cache.put(3, "c")
        assertEquals("a", cache.get(1))
        assertNull(cache.get(2))
        assertEquals("c", cache.get(3))
        assertEquals(2, cache.size)
    }

    @Test
    fun entriesExpireAfterTheTtlAndAreRemoved() {
        val cache = LruTtlCache<Int, String>(maxSize = 4, ttlMs = 1_000, clock = { now })
        cache.put(1, "a")
        now = 999
        assertEquals("a", cache.get(1))
        now = 1_000
        assertNull(cache.get(1))
        assertEquals(0, cache.size)
    }

    @Test
    fun overwritingRefreshesTheTimestamp() {
        val cache = LruTtlCache<Int, String>(maxSize = 4, ttlMs = 1_000, clock = { now })
        cache.put(1, "a")
        now = 900
        cache.put(1, "b")
        now = 1_500
        assertEquals("b", cache.get(1))
    }
}
