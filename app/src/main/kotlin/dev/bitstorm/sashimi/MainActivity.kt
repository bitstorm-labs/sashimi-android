package dev.bitstorm.sashimi

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.shell.MainScreen
import dev.bitstorm.sashimi.ui.theme.SashimiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Whether the activity is currently in a PiP window.
     *
     * The player draws its own chrome (close button, title, 40dp seek icons,
     * 56dp play button, scrubber) sized for a full screen. Nothing tracked PiP,
     * so all of that stayed composited over a thumbnail-sized window until the
     * 5-second auto-hide happened to fire.
     */
    val isInPip: StateFlow<Boolean> get() = _isInPip

    private val _isInPip = MutableStateFlow(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPip.value = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        // Re-check connectivity on every foreground so a stale offline state (e.g.
        // from background network blocking) can't survive a minimize→return cycle.
        ServiceLocator.networkMonitor.refresh()
        // Second chance for progress stashed while offline: isOnline only fires
        // on a transition, so a device already online at process start never
        // triggers a sync on its own.
        ServiceLocator.downloadManager.syncNow()
    }

    private fun stashDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "sashimi") ServiceLocator.setPendingDeepLink(data.toString())
    }
}
