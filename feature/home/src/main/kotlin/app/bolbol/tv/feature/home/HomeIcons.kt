package app.bolbol.tv.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Arrow pointing physically left, which is "onwards" in this right-to-left UI (Material Symbols
 * "arrow_back" path data, Apache 2.0). Not auto-mirrored on purpose.
 */
internal val ArrowOnwards: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowOnwards",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
        fill = SolidColor(Color.Black),
    ).build()
}
