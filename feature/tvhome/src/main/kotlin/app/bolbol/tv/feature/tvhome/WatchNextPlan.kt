package app.bolbol.tv.feature.tvhome

/** One row of our package in the TvProvider's Watch Next table, reduced to what the diff needs. */
internal data class ExistingProgram(
    /** TvProvider `_id`. */
    val id: Long,
    /** `WatchKey.asString()`; null for rows we did not write. */
    val providerId: String?,
    /** false once the user removed the card from the launcher (the system flips it; apps cannot set it). */
    val browsable: Boolean,
    val lastEngagementMs: Long,
    /** [WatchNextSpec.fingerprint] stored at write time; null when absent. */
    val fingerprint: Long?,
)

internal data class ProgramUpdate(val id: Long, val spec: WatchNextSpec)

/** What to do to make the launcher match the wanted specs. */
internal data class WatchNextPlan(
    val deletes: List<Long>,
    val updates: List<ProgramUpdate>,
    val inserts: List<WatchNextSpec>,
) {
    val isEmpty: Boolean get() = deletes.isEmpty() && updates.isEmpty() && inserts.isEmpty()
}

/**
 * Diff of [desired] (one spec per key) against the [existing] rows:
 *  - key missing from [desired] (finished, hidden, dropped from the list) -> delete its row(s);
 *  - key missing from [existing] -> insert;
 *  - live row, same fingerprint -> nothing; different fingerprint -> update in place;
 *  - row the user removed (browsable = false) -> keep it as a marker and do NOT re-add, unless the title has
 *    newer engagement than the removed card; then delete the marker and insert a fresh (browsable) program,
 *    because apps cannot flip `browsable` back themselves;
 *  - duplicates of one key (a crash between operations) and rows without a provider id are deleted.
 */
internal fun planWatchNext(desired: List<WatchNextSpec>, existing: List<ExistingProgram>): WatchNextPlan {
    val specs = desired.distinctBy { it.key.asString() }
    val wanted = specs.associateBy { it.key.asString() }
    val deletes = sortedSetOf<Long>()
    val updates = mutableListOf<ProgramUpdate>()
    val inserts = mutableListOf<WatchNextSpec>()

    val rowsByKey = existing.groupBy { it.providerId }
    rowsByKey[null].orEmpty().forEach { deletes += it.id }

    for ((providerId, rows) in rowsByKey) {
        if (providerId == null) continue
        val spec = wanted[providerId]
        if (spec == null) {
            rows.forEach { deletes += it.id }
            continue
        }
        val live = rows.filter { it.browsable }
        if (live.isNotEmpty()) {
            val keeper = live.maxBy { it.id }
            rows.filter { it !== keeper }.forEach { deletes += it.id }
            if (keeper.fingerprint != spec.fingerprint()) updates += ProgramUpdate(keeper.id, spec)
        } else {
            val removedByUser = rows.maxBy { it.lastEngagementMs }
            if (spec.lastEngagementMs > removedByUser.lastEngagementMs) {
                rows.forEach { deletes += it.id }
                inserts += spec
            } else {
                rows.filter { it !== removedByUser }.forEach { deletes += it.id }
            }
        }
    }

    // Keep the caller's order (newest first) for deterministic application.
    for (spec in specs) {
        if (rowsByKey[spec.key.asString()] == null) inserts += spec
    }
    val insertOrder = specs.withIndex().associate { (i, s) -> s.key.asString() to i }
    return WatchNextPlan(
        deletes = deletes.toList(),
        updates = updates.sortedBy { insertOrder[it.spec.key.asString()] },
        inserts = inserts.sortedBy { insertOrder[it.key.asString()] },
    )
}
