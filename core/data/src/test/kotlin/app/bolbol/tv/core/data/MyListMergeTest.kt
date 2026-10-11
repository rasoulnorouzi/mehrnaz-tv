package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_ADD
import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_NONE
import app.bolbol.tv.core.database.MyListEntity.Companion.PENDING_REMOVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge rules of the My List sync as a pure function. */
class MyListMergeTest {

    private fun plan(local: List<Pair<Long, Int>>, server: List<Long>, complete: Boolean = true) =
        planMerge(local.map { (id, pending) -> myListRow(id, pending) }, server, complete)

    @Test
    fun `everything in sync produces an empty plan`() {
        val p = plan(listOf(1L to PENDING_NONE, 2L to PENDING_NONE), listOf(2, 1))
        assertEquals(MergePlan(), p)
    }

    @Test
    fun `server removals delete only rows with no pending change`() {
        val p = plan(
            listOf(1L to PENDING_NONE, 2L to PENDING_ADD, 3L to PENDING_REMOVE),
            server = emptyList(),
        )
        assertEquals(listOf(1L), p.deleteSynced)
        assertEquals(listOf(2L), p.pushAdd) // a pending add survives a server without it
        assertEquals(listOf(3L), p.dropRemoved)
        assertTrue(p.pushRemove.isEmpty())
    }

    @Test
    fun `pending changes that the server already reflects are just settled`() {
        val p = plan(listOf(1L to PENDING_ADD, 2L to PENDING_REMOVE), server = listOf(1, 2))
        assertEquals(listOf(1L), p.clearAdd)
        assertEquals(listOf(2L), p.pushRemove)
        assertTrue(p.pushAdd.isEmpty())
        assertTrue(p.deleteSynced.isEmpty())
    }

    @Test
    fun `server only ids are inserted in server order without duplicates`() {
        val p = plan(listOf(5L to PENDING_NONE, 6L to PENDING_REMOVE), server = listOf(9, 5, 7, 9, 6, 8))
        assertEquals(listOf(9L, 7L, 8L), p.insertFromServer) // 6 is a tombstone: never resurrected
    }

    @Test
    fun `an incomplete server list proves nothing`() {
        val p = plan(
            listOf(1L to PENDING_NONE, 2L to PENDING_ADD, 3L to PENDING_REMOVE, 4L to PENDING_REMOVE, 5L to PENDING_ADD),
            server = listOf(4, 5),
            complete = false,
        )
        assertTrue(p.deleteSynced.isEmpty())
        assertTrue(p.pushAdd.isEmpty())
        assertTrue(p.dropRemoved.isEmpty())
        assertEquals(listOf(4L), p.pushRemove) // presence is certain even on a partial read
        assertEquals(listOf(5L), p.clearAdd)
    }
}
