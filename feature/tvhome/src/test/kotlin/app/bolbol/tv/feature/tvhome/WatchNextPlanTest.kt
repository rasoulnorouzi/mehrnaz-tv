package app.bolbol.tv.feature.tvhome

import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPlanTest {

    private fun spec(key: WatchKey, updatedAtMs: Long = 1_000, positionMs: Long = 600_000) =
        item(key, positionMs = positionMs, updatedAtMs = updatedAtMs).toWatchNextSpec("app.mehrnaz.tv")!!

    private fun row(
        id: Long,
        spec: WatchNextSpec?,
        browsable: Boolean = true,
        providerId: String? = spec?.key?.asString(),
        engagement: Long = spec?.lastEngagementMs ?: 0,
        fingerprint: Long? = spec?.fingerprint(),
    ) = ExistingProgram(id, providerId, browsable, engagement, fingerprint)

    @Test
    fun `empty to empty is a no-op`() {
        assertTrue(planWatchNext(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `new titles are inserted in the wanted order`() {
        val a = spec(WatchKey(1), updatedAtMs = 30)
        val b = spec(WatchKey(2, 1, 1), updatedAtMs = 20)
        val plan = planWatchNext(listOf(a, b), emptyList())
        assertEquals(listOf(a, b), plan.inserts)
        assertTrue(plan.updates.isEmpty() && plan.deletes.isEmpty())
    }

    @Test
    fun `identical rows produce no work (idempotent)`() {
        val a = spec(WatchKey(1))
        val b = spec(WatchKey(2, 1, 1))
        assertTrue(planWatchNext(listOf(a, b), listOf(row(10, a), row(11, b))).isEmpty)
    }

    @Test
    fun `changed rows are updated in place and only those`() {
        val old = spec(WatchKey(2, 1, 1))
        val moved = spec(WatchKey(2, 1, 1), updatedAtMs = 5_000, positionMs = 900_000)
        val same = spec(WatchKey(1))
        val plan = planWatchNext(listOf(moved, same), listOf(row(10, same), row(11, old)))
        assertEquals(listOf(ProgramUpdate(11, moved)), plan.updates)
        assertTrue(plan.inserts.isEmpty() && plan.deletes.isEmpty())
    }

    @Test
    fun `a new episode of the same title replaces the old program rather than adding a second`() {
        val ep1 = spec(WatchKey(7, 1, 1))
        val ep2 = spec(WatchKey(7, 1, 2), updatedAtMs = 9_000)
        val plan = planWatchNext(listOf(ep2), listOf(row(10, ep1)))
        // Different WatchKey -> different internalProviderId: the old row goes, the new one is added.
        assertEquals(listOf(10L), plan.deletes)
        assertEquals(listOf(ep2), plan.inserts)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `rows no longer wanted are deleted (finished, hidden, dropped)`() {
        val keep = spec(WatchKey(1))
        val gone = spec(WatchKey(2))
        val removedByUser = spec(WatchKey(3))
        val plan = planWatchNext(
            listOf(keep),
            listOf(row(10, keep), row(11, gone), row(12, removedByUser, browsable = false)),
        )
        assertEquals(listOf(11L, 12L), plan.deletes)
        assertTrue(plan.updates.isEmpty() && plan.inserts.isEmpty())
    }

    @Test
    fun `rows without a provider id are deleted`() {
        val plan = planWatchNext(emptyList(), listOf(row(10, null, providerId = null)))
        assertEquals(listOf(10L), plan.deletes)
    }

    @Test
    fun `a card the user removed is not re-added while engagement is unchanged`() {
        val a = spec(WatchKey(1), updatedAtMs = 1_000)
        val plan = planWatchNext(listOf(a), listOf(row(10, a, browsable = false)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a card the user removed is not re-added when the data is older or equal`() {
        val older = spec(WatchKey(1), updatedAtMs = 500)
        val removed = row(10, spec(WatchKey(1), updatedAtMs = 1_000), browsable = false)
        assertTrue(planWatchNext(listOf(older), listOf(removed)).isEmpty)
    }

    @Test
    fun `a removed card comes back as a fresh program after newer engagement`() {
        val shown = spec(WatchKey(1), updatedAtMs = 1_000)
        val watchedAgain = spec(WatchKey(1), updatedAtMs = 2_000, positionMs = 1_200_000)
        val plan = planWatchNext(listOf(watchedAgain), listOf(row(10, shown, browsable = false)))
        // browsable cannot be flipped by the app, so the marker is deleted and a new row inserted.
        assertEquals(listOf(10L), plan.deletes)
        assertEquals(listOf(watchedAgain), plan.inserts)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `duplicates of one key are collapsed to a single live row`() {
        val a = spec(WatchKey(1))
        val plan = planWatchNext(listOf(a), listOf(row(10, a), row(12, a), row(11, a)))
        assertEquals(listOf(10L, 11L), plan.deletes) // keeps the highest id
        assertTrue(plan.updates.isEmpty() && plan.inserts.isEmpty())
    }

    @Test
    fun `a live row wins over a stale marker of the same key`() {
        val a = spec(WatchKey(1), updatedAtMs = 2_000)
        val oldMarker = row(10, spec(WatchKey(1), updatedAtMs = 1_000), browsable = false)
        val plan = planWatchNext(listOf(a), listOf(oldMarker, row(11, a)))
        assertEquals(listOf(10L), plan.deletes)
        assertTrue(plan.updates.isEmpty() && plan.inserts.isEmpty())
    }

    @Test
    fun `a row with a missing or stale fingerprint is updated`() {
        val a = spec(WatchKey(1))
        assertEquals(listOf(ProgramUpdate(10, a)), planWatchNext(listOf(a), listOf(row(10, a, fingerprint = null))).updates)
        assertEquals(listOf(ProgramUpdate(10, a)), planWatchNext(listOf(a), listOf(row(10, a, fingerprint = 123))).updates)
    }

    @Test
    fun `mixed scenario`() {
        val keep = spec(WatchKey(1))
        val changedOld = spec(WatchKey(2), updatedAtMs = 100)
        val changedNew = spec(WatchKey(2), updatedAtMs = 900)
        val fresh = spec(WatchKey(3), updatedAtMs = 800)
        val stale = spec(WatchKey(4))
        val plan = planWatchNext(
            listOf(changedNew, fresh, keep),
            listOf(row(1, keep), row(2, changedOld), row(3, stale)),
        )
        assertEquals(listOf(3L), plan.deletes)
        assertEquals(listOf(ProgramUpdate(2, changedNew)), plan.updates)
        assertEquals(listOf(fresh), plan.inserts)
    }
}
