package app.bolbol.tv.feature.tvhome

import android.content.Context
import android.util.Log
import app.bolbol.tv.core.model.ContinueWatchingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the Android TV home's "Play Next" (Watch Next) row in step with Continue Watching. See README.md.
 *
 * One program per title; only programs written by this app are touched. Safe to call as often as you like: a call
 * whose input equals the last successful publish does no work, and concurrent calls are serialised.
 */
class WatchNextPublisher internal constructor(
    private val store: WatchNextStore,
    private val appPackage: String,
    private val io: CoroutineDispatcher,
    private val log: (message: String, error: Throwable?) -> Unit,
) {
    constructor(context: Context) : this(
        store = TvProviderWatchNextStore(context),
        appPackage = context.packageName,
        io = Dispatchers.IO,
        log = { message, error -> Log.w(TAG, message, error) },
    )

    private val lock = Mutex()

    /** Digest of the last fully applied publish; null = unknown (start, failure). */
    private var lastApplied: Long? = null

    /**
     * Makes the launcher's Watch Next match [items] (ours only): inserts new, updates changed, deletes stale.
     * Idempotent and IO-dispatched. Returns false (after logging) if anything failed; only cancellation propagates.
     */
    suspend fun publish(items: List<ContinueWatchingItem>): Boolean = withContext(io) {
        lock.withLock {
            try {
                val specs = selectWatchNextSpecs(items, appPackage)
                val digest = fingerprintOf(specs)
                if (digest == lastApplied) return@withLock true

                val plan = planWatchNext(specs, store.readAll())
                var ok = true
                for (id in plan.deletes) ok = attempt("delete $id") { store.delete(id) } && ok
                for (u in plan.updates) {
                    ok = attempt("update ${u.spec.key.asString()}") {
                        // The row vanished between the read and the write: fall back to inserting it.
                        if (!store.update(u.id, u.spec)) store.insert(u.spec)
                    } && ok
                }
                for (spec in plan.inserts) ok = attempt("insert ${spec.key.asString()}") { store.insert(spec) } && ok

                lastApplied = if (ok) digest else null
                ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastApplied = null
                log("Watch Next publish failed", e)
                false
            }
        }
    }

    /** Removes every program this app has on the launcher (e.g. on sign-out or when the feature is switched off). */
    suspend fun clear() {
        withContext(io) {
            lock.withLock {
                try {
                    store.deleteAll()
                    lastApplied = fingerprintOf(emptyList())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastApplied = null
                    log("Watch Next clear failed", e)
                }
            }
        }
    }

    private inline fun attempt(what: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Watch Next: $what failed", e)
            false
        }

    private companion object {
        const val TAG = "WatchNext"
    }
}
