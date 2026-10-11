package app.bolbol.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolTheme
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.feature.tvhome.DeepLinks
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A Watch Next card (bolbol://play/…) to open; consumed by MainNavigation. */
    private val pendingDeepLink = MutableStateFlow<WatchKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BolbolApp
        if (savedInstanceState == null) pendingDeepLink.value = DeepLinks.parse(intent?.data)
        setContent {
            BolbolTheme {
                Box(Modifier.fillMaxSize().background(BolbolColors.Background)) {
                    MainNavigation(app, pendingDeepLink)
                }
            }
        }
        // The launch logo (windowBackground) only covers the gap before Compose's first frame.
        // Drop it afterwards: Compose paints an opaque background, and a window background under a
        // full-screen UI is pure overdraw on the TV's small GPU. Then start background work.
        window.decorView.post {
            window.setBackgroundDrawable(null)
            app.startBackgroundWork()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinks.parse(intent.data)?.let { pendingDeepLink.value = it }
    }
}
