package app.bolbol.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.bolbol.tv.core.designsystem.BolbolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BolbolTheme {
                HelloTvScreen()
            }
        }
    }
}
