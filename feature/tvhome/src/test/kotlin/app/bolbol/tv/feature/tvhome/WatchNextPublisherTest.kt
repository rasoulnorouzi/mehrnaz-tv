package app.bolbol.tv.feature.tvhome

import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for the TvProvider table, including the system flipping `browsable` when the user removes a card. */
private class FakeStore : WatchNextStore {
    data class Row(val id: Long, val spec: WatchNextSpec, var browsable: Boolean = true)

    val rows = mutableListOf<Row>()
    var nextId = 1L
    var reads = 0
    var writes = 0
    var failInsertFor: String? = null
    var failRead = false

    override fun readAll(): List<ExistingProgram> {
        reads++
        if (failRead) error("provider unavailable")
        return rows.map {
            ExistingProgram(it.id, it.spec.key.asString(), it.browsable, it.spec.lastEngagementMs, it.spec.fingerprint())
        }
    }

    override fun insert(spec: WatchNextSpec) {
        writes++
        if (spec.key.asString() == failInsertFor) error("insert refused")
        rows += Row(nextId++, spec)
    }

    override fun update(id: Long, spec: WatchNextSpec): Boolean {
        writes++
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return false
        rows[i] = rows[i].copy(spec = spec)
        return true
    }

    override fun delete(id: Long) {
        writes++
        rows.removeAll { it.id == id }
    }

    override fun deleteAll() {
        writes++
        rows.clear()
    }

    fun userRemoves(key: WatchKey) = rows.first { it.spec.key == key }.let { it.browsable = false }
    fun liveKeys() = rows.filter { it.browsable }.map { it.spec.key }.toSet()
}

class WatchNextPublisherTest {

    private val errors = mutableListOf<String>()

    private fun TestScope.publisher(store: WatchNextStore) =
        WatchNextPublisher(store, "app.bolbol.tv", StandardTestDispatcher(testScheduler)) { m, _ -> errors += m }

    @Test
    fun `publish inserts then is idempotent and skips unchanged input`() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        val items = listOf(item(WatchKey(1), updatedAtMs = 20), item(WatchKey(2, 1, 3), updatedAtMs = 10))

        assertTrue(p.publish(items))
        assertEquals(setOf(WatchKey(1), WatchKey(2, 1, 3)), store.liveKeys())
        val readsAfterFirst = store.reads
        val writesAfterFirst = store.writes

        assertTrue(p.publish(items)) // identical input: no provider round trip at all
        assertTrue(p.publish(items.toList()))
        assertEquals(readsAfterFirst, store.reads)
        assertEquals(writesAfterFirst, store.writes)
    }

    @Test
    fun `a fresh publisher over existing rows does no writes (idempotent across restarts)`() = runTest {
        val store = FakeStore()
        val items = listOf(item(WatchKey(1)), item(WatchKey(2)))
        publisher(store).publish(items)
        val writes = store.writes

        assertTrue(publisher(store).publish(items))
        assertEquals(writes, store.writes)
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `progress updates change only the touched row`() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        p.publish(listOf(item(WatchKey(1), updatedAtMs = 10), item(WatchKey(2), updatedAtMs = 5)))
        val writes = store.writes

        p.publish(listOf(item(WatchKey(1), positionMs = 900_000, updatedAtMs = 50), item(WatchKey(2), updatedAtMs = 5)))
        assertEquals(writes + 1, store.writes)
        assertEquals(900_000, store.rows.first { it.spec.key == WatchKey(1) }.spec.positionMs)
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `finished or removed titles are deleted`() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        p.publish(listOf(item(WatchKey(1)), item(WatchKey(2))))

        p.publish(listOf(item(WatchKey(1)), item(WatchKey(2), completed = true)))
        assertEquals(setOf(WatchKey(1)), store.liveKeys())

        p.publish(emptyList())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a card the user removed stays removed until newer engagement`() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        val items = listOf(item(WatchKey(1), updatedAtMs = 100), item(WatchKey(2), updatedAtMs = 90))
        p.publish(items)
        store.userRemoves(WatchKey(1))

        // Another publish with a different list (title 3 added) must not resurrect title 1.
        p.publish(items + item(WatchKey(3), updatedAtMs = 95))
        assertEquals(setOf(WatchKey(2), WatchKey(3)), store.liveKeys())

        // Same engagement again (e.g. a new process): still removed.
        publisher(store).publish(items)
        assertFalse(WatchKey(1) in store.liveKeys())

        // The viewer watches title 1 again: it returns.
        p.publish(listOf(item(WatchKey(1), positionMs = 2_000_000, updatedAtMs = 500), item(WatchKey(2), updatedAtMs = 90)))
        assertEquals(setOf(WatchKey(1), WatchKey(2)), store.liveKeys())
        assertEquals(1, store.rows.count { it.spec.key == WatchKey(1) })
    }

    @Test
    fun `a failure returns false, is logged, and the next call retries`() = runTest {
        val store = FakeStore().apply { failRead = true }
        val p = publisher(store)
        val items = listOf(item(WatchKey(1)))

        assertFalse(p.publish(items))
        assertEquals(1, errors.size)

        store.failRead = false
        assertTrue(p.publish(items))
        assertEquals(setOf(WatchKey(1)), store.liveKeys())
    }

    @Test
    fun `one failing insert does not block the others and is retried`() = runTest {
        val store = FakeStore().apply { failInsertFor = WatchKey(1).asString() }
        val p = publisher(store)
        val items = listOf(item(WatchKey(1), updatedAtMs = 20), item(WatchKey(2), updatedAtMs = 10))

        assertFalse(p.publish(items))
        assertEquals(setOf(WatchKey(2)), store.liveKeys())

        store.failInsertFor = null
        assertTrue(p.publish(items))
        assertEquals(setOf(WatchKey(1), WatchKey(2)), store.liveKeys())
    }

    @Test
    fun `clear removes everything and a later publish restores it`() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        val items = listOf(item(WatchKey(1)))
        p.publish(items)

        p.clear()
        assertTrue(store.rows.isEmpty())

        assertTrue(p.publish(items)) // the throttle must not think it is still published
        assertEquals(setOf(WatchKey(1)), store.liveKeys())
    }
}
