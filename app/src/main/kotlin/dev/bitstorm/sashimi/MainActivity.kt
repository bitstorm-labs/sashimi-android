package dev.bitstorm.sashimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.shell.MainScreen
import dev.bitstorm.sashimi.ui.theme.SashimiTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SashimiTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                MainScreen(
                    session = ServiceLocator.session,
                    widthSizeClass = windowSizeClass.widthSizeClass,
                )
            }
        }
    }
}
