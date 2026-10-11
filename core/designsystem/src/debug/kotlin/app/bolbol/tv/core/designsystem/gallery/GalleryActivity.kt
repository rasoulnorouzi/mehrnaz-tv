package app.bolbol.tv.core.designsystem.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.bolbol.tv.core.designsystem.BolbolTheme
import app.bolbol.tv.core.designsystem.bolbolImageLoader
import coil3.SingletonImageLoader

/**
 * Debug-only gallery of every design-system component on the dark RTL background.
 *
 *     adb shell am start -n app.mehrnaz.tv/app.bolbol.tv.core.designsystem.gallery.GalleryActivity
 *     adb shell am start -n app.mehrnaz.tv/app.bolbol.tv.core.designsystem.gallery.GalleryActivity --ei page 1
 *
 * Pages (each fits 960 x 540 dp, no scrolling needed):
 *  0 = home: TopBar, HeroBackdrop + title, ContentRow of PosterCards (first card focused;
 *      moving focus changes the hero through rememberDebouncedValue, so the crossfade is testable)
 *  1 = ContentRow of WideCards with progress, buttons, chips, badges, progress line, formatters
 *  2 = LoadingState, ErrorState (retry focused), EmptyState
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same loader the app should install: 15 % heap memory cache, hardware bitmaps.
        SingletonImageLoader.setSafe { context -> bolbolImageLoader(context) }
        val page = intent.getIntExtra("page", 0)
        setContent {
            BolbolTheme {
                GalleryRoot(page)
            }
        }
    }
}
