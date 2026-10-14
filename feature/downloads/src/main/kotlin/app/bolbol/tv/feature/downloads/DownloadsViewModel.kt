package app.bolbol.tv.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.DownloadRepository
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder of the «دانلودها» screen. Works with no internet: everything comes from [DownloadRepository]'s local
 * data. [uiState] follows `downloads()` live (progress included) and reads the free space on each emission
 * (one cheap file-system query).
 *
 * Removing is always two steps: [onRemoveClicked] / [onRemoveAllClicked] only raise [confirm]; [onConfirm] deletes.
 */
class DownloadsViewModel(private val repo: DownloadRepository) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = repo.downloads()
        .conflate()
        .map { items ->
            val storage = try {
                repo.storage().toSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            DownloadsUiState.Content(items.map { it.toRow() }, storage) as DownloadsUiState
        }
        .catch { emit(DownloadsUiState.Content(emptyList(), null)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DownloadsUiState.Loading)

    private val _confirm = MutableStateFlow<DownloadsConfirm?>(null)

    /** The «حذف؟» question on screen, or null. */
    val confirm: StateFlow<DownloadsConfirm?> = _confirm

    /** «حذف» on one item: asks first. */
    fun onRemoveClicked(row: DownloadRowUi) {
        _confirm.value = DownloadsConfirm.RemoveOne(row.key, row.titleText, row.subtitleLine)
    }

    /** «حذف همه»: asks first. Nothing to ask about when the list is empty. */
    fun onRemoveAllClicked() {
        val count = (uiState.value as? DownloadsUiState.Content)?.rows?.size ?: 0
        if (count > 0) _confirm.value = DownloadsConfirm.RemoveAll(count)
    }

    fun onDismissConfirm() {
        _confirm.value = null
    }

    /** The user said yes. */
    fun onConfirm() {
        val pending = _confirm.value ?: return
        _confirm.value = null
        viewModelScope.launch {
            quietly {
                when (pending) {
                    is DownloadsConfirm.RemoveOne -> repo.remove(pending.key)
                    is DownloadsConfirm.RemoveAll -> repo.removeAll()
                }
            }
        }
    }

    /** «تلاش دوباره» on a failed item. */
    fun onRetry(key: WatchKey) {
        viewModelScope.launch { quietly { repo.retry(key) } }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The list follows the repository; a failed command simply leaves it unchanged.
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repo: DownloadRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { DownloadsViewModel(repo) } }
    }
}
