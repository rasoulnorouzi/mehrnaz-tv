package app.bolbol.tv.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticValueTest {

    @Test
    fun updateShowsTheNewValueBeforeTheWriteFinishes() = runTest {
        val saved = mutableListOf<Int>()
        val source = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(0) }
        val value = OptimisticValue(backgroundScope, 0, source) { delay(100); saved += it }
        runCurrent()

        value.update { it + 1 }
        assertEquals(1, value.state.value)
        assertEquals(emptyList<Int>(), saved)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf(1), saved)
    }

    @Test
    fun writesAreSavedInOrder() = runTest {
        val saved = mutableListOf<Int>()
        val source = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(0) }
        val value = OptimisticValue(backgroundScope, 0, source) { delay(50); saved += it }
        runCurrent()

        value.update { it + 1 }
        value.update { it + 1 }
        value.update { it + 1 }
        assertEquals(3, value.state.value)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1, 2, 3), saved)
    }

    @Test
    fun staleRepositoryEmissionsAreIgnoredWhileWritesAreInFlight() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(0) }
        val value = OptimisticValue(backgroundScope, 0, source) { delay(100) }
        runCurrent()

        value.update { 5 }
        source.emit(0) // the old row arrives while the write is pending
        runCurrent()
        assertEquals(5, value.state.value)

        advanceTimeBy(101)
        runCurrent()
        source.emit(7) // an external change after the queue drained is taken
        runCurrent()
        assertEquals(7, value.state.value)
    }

    @Test
    fun repositoryValueIsTakenWhenNothingIsPending() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1)
        val value = OptimisticValue(backgroundScope, 0, source) { }
        runCurrent()
        source.emit(3)
        runCurrent()
        assertEquals(3, value.state.value)
    }

    @Test
    fun unchangedValueIsNotSaved() = runTest {
        val saved = mutableListOf<Int>()
        val source = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(2) }
        val value = OptimisticValue(backgroundScope, 0, source) { saved += it }
        runCurrent()
        value.update { it }
        runCurrent()
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun aFailedWriteDoesNotStopLaterWrites() = runTest {
        val saved = mutableListOf<Int>()
        val source = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(0) }
        val value = OptimisticValue(backgroundScope, 0, source) {
            if (it == 1) error("disk full")
            saved += it
        }
        runCurrent()
        value.update { 1 }
        value.update { 2 }
        runCurrent()
        assertEquals(listOf(2), saved)
        assertEquals(2, value.state.value)
    }
}
