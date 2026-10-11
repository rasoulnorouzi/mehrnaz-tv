package app.bolbol.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bolbol.tv.journeys.TARGET_PACKAGE
import app.bolbol.tv.journeys.browseMovies
import app.bolbol.tv.journeys.openDetailsAndBack
import app.bolbol.tv.journeys.scrollHome
import app.bolbol.tv.journeys.startToHome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates `baseline-prof.txt` and `startup-prof.txt` for :app.
 *
 * Needs a device with API 33+ or root: run it on the `TV_API34` emulator (NOT on the API 29 TV).
 * The journeys are in `src/journeys` (shared with :benchmark).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Startup profile: only what a cold start to the home rows runs. It also lands in the baseline
     * profile, and drives R8's dex layout optimisation (`dexLayoutOptimization` in :app).
     */
    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startToHome()
    }

    /** Baseline profile: the four journeys (startup, home scroll, open details, browse grid). */
    @Test
    fun allJourneys() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startToHome()
        scrollHome()
        openDetailsAndBack()
        browseMovies()
    }
}
