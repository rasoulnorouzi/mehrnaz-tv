package app.bolbol.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bolbol.tv.journeys.TARGET_PACKAGE
import app.bolbol.tv.journeys.startToHome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey 1: cold start to the home rows. Budget: <= 1.5 s (docs/perf-budget.md).
 * Read `timeToInitialDisplayMs` (TTID). `timeToFullDisplayMs` needs `reportFullyDrawn()` in the app and
 * may be missing on API 29.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutProfile() = coldStart(withoutProfile())

    @Test
    fun coldStartWithProfile() = coldStart(withProfile())

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startToHome()
    }
}
