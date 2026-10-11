package app.bolbol.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bolbol.tv.journeys.TARGET_PACKAGE
import app.bolbol.tv.journeys.UiHooks
import app.bolbol.tv.journeys.openDetailsAndBack
import app.bolbol.tv.journeys.startToHome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey 3: OK on a poster, wait for the details screen, Back. Budget: details ready <= 0.4 s cached /
 * 1.0 s network (docs/perf-budget.md).
 *
 * `details_ready` is a custom trace section the UI has to emit (OK pressed until the details content is
 * drawn): `androidx.tracing.trace("details_ready") { ... }`, see docs/perf-budget.md §UI hooks. Until
 * the app emits it only the frame metrics are reported.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class OpenDetailsBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun openDetailsWithoutProfile() = openDetails(withoutProfile())

    @Test
    fun openDetailsWithProfile() = openDetails(withProfile())

    private fun openDetails(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(TraceSectionMetric(UiHooks.TRACE_DETAILS_READY), FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startToHome()
        },
    ) {
        openDetailsAndBack()
    }
}
