package app.bolbol.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bolbol.tv.journeys.TARGET_PACKAGE
import app.bolbol.tv.journeys.scrollHome
import app.bolbol.tv.journeys.startToHome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey 2: D-pad along the first row, down three rows, back along the row, on a cold-started app
 * (so the first scroll shows the JIT-vs-profile difference). Budget: p90 `frameDurationCpuMs` <= 16 ms,
 * janky frames < 5 %. `frameOverrunMs` needs API 31+ and is absent on the TV (API 29).
 */
@RunWith(AndroidJUnit4::class)
class HomeScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollHomeWithoutProfile() = scroll(withoutProfile())

    @Test
    fun scrollHomeWithProfile() = scroll(withProfile())

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startToHome()
        },
    ) {
        scrollHome()
    }
}
