package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke test for the JVM screenshot setup (no emulator needed): renders design-system pieces at TV size in
 * RTL. Record with `tools/gw.sh :core:designsystem:recordRoborazziDebug`; PNGs land in
 * `core/designsystem/build/outputs/roborazzi/`. See docs/screenshot-tests.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignSystemScreenshotTest {

    @Test
    fun posterRowAndButtons() = captureRoboImage("build/outputs/roborazzi/designsystem_posters.png") {
        BolbolTheme {
            Box(Modifier.fillMaxSize().background(BolbolColors.Background).padding(48.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    BolbolText("ردیف نمونه · Sample row ۱۴۰۵")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PosterCard(title = "وان پیس لگویی", imageUrl = null, onClick = {}, year = "۲۰۲۶", imdb = 6.8f, subtitled = true, alwaysShowCaption = true)
                        PosterCard(title = "The Naked Gun 2025", imageUrl = null, onClick = {}, year = "۲۰۲۵", dubbed = true, progress = 0.4f, alwaysShowCaption = true)
                        PosterCard(title = "کری", imageUrl = null, onClick = {}, year = "۲۰۲۶", alwaysShowCaption = true)
                    }
                }
            }
        }
    }
}
