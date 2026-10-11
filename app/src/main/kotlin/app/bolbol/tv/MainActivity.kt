package app.bolbol.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BolbolApp
        setContent {
            BolbolTheme {
                Box(Modifier.fillMaxSize().background(BolbolColors.Background)) {
                    MainNavigation(app)
                }
            }
        }
        // The launch logo (windowBackground) only covers the gap before Compose's first frame.
        // Drop it afterwards: Compose paints an opaque background, and a window background under a
        // full-screen UI is pure overdraw on the TV's small GPU.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }
}
