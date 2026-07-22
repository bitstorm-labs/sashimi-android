package dev.bitstorm.sashimi

import android.content.Intent
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
        // Stash any launch deep link; AppShell resolves it once authenticated.
        stashDeepLink(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stashDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-check connectivity on every foreground so a stale offline state (e.g.
        // from background network blocking) can't survive a minimize→return cycle.
        ServiceLocator.networkMonitor.refresh()
    }

    private fun stashDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "sashimi") ServiceLocator.setPendingDeepLink(data.toString())
    }
}
