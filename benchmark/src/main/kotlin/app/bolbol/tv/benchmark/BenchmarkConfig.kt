package app.bolbol.tv.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode

/** Iterations per benchmark (budget: docs/perf-budget.md). */
const val ITERATIONS = 5

/** JIT only: the app as it would run without the profile (the "before" of the profile's gain). */
fun withoutProfile(): CompilationMode = CompilationMode.None()

/**
 * What a sideloaded release gets: the profile packaged in the APK is installed and AOT-compiled.
 * `Require` fails the run if the APK carries no profile, so a missing profile cannot pass silently.
 */
fun withProfile(): CompilationMode = CompilationMode.Partial(BaselineProfileMode.Require)
