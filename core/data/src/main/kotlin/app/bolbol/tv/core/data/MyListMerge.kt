package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.MyListEntity

/**
 * What one sync has to do, decided only from the local rows and the server's list of ids (pure, so the rules are
 * unit-tested without a database or a server). The ids in each list are disjoint.
 *
 * Rules (docs/auth.md section 8.5; the local list is the source of truth, the server a mirror):
 * - in sync locally ([MyListEntity.PENDING_NONE]) and missing on the server: the server removed it, so [deleteSynced];
 * - added locally ([MyListEntity.PENDING_ADD]) and already on the server: [clearAdd]; missing there: [pushAdd];
 * - removed locally ([MyListEntity.PENDING_REMOVE]) and gone from the server: [dropRemoved]; still there: [pushRemove];
 * - on the server and unknown locally: [insertFromServer] (union, in server order).
 * Server removals are therefore applied only to items with no pending local change.
 *
 * Safety: when the server list may be incomplete ([complete] = false, e.g. a page limit was hit) absence proves
 * nothing, so nothing is deleted and nothing is pushed as an add (the server toggles; an add of an item that sits on an
 * unread page would remove it).
 */
internal data class MergePlan(
    val deleteSynced: List<Long> = emptyList(),
    val clearAdd: List<Long> = emptyList(),
    val dropRemoved: List<Long> = emptyList(),
    val pushAdd: List<Long> = emptyList(),
    val pushRemove: List<Long> = emptyList(),
    val insertFromServer: List<Long> = emptyList(),
)

internal fun planMerge(local: List<MyListEntity>, serverIds: List<Long>, complete: Boolean = true): MergePlan {
    val server = serverIds.toSet()
    val deleteSynced = ArrayList<Long>()
    val clearAdd = ArrayList<Long>()
    val dropRemoved = ArrayList<Long>()
    val pushAdd = ArrayList<Long>()
    val pushRemove = ArrayList<Long>()
    for (row in local) {
        val id = row.postId
        val onServer = id in server
        when (row.pendingSync) {
            MyListEntity.PENDING_NONE -> if (!onServer && complete) deleteSynced += id
            MyListEntity.PENDING_ADD -> if (onServer) clearAdd += id else if (complete) pushAdd += id
            MyListEntity.PENDING_REMOVE -> if (onServer) pushRemove += id else if (complete) dropRemoved += id
        }
    }
    val known = local.mapTo(HashSet()) { it.postId }
    return MergePlan(
        deleteSynced = deleteSynced,
        clearAdd = clearAdd,
        dropRemoved = dropRemoved,
        pushAdd = pushAdd,
        pushRemove = pushRemove,
        insertFromServer = serverIds.distinct().filter { it !in known },
    )
}
