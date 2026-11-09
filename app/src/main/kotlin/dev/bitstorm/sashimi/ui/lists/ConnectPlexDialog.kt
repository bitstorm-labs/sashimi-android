package dev.bitstorm.sashimi.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.bitstorm.sashimi.core.lists.PlexLinkResult
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.requests.openPlexSignIn
import dev.bitstorm.sashimi.ui.requests.returnToApp
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connect (or Reconnect) Plex for the Watchlist: Plex's sign-in page in a
 * Custom Tab, then the PIN is polled and its token handed to the plugin. The
 * flow runs on the app scope, so it outlives this dialog while the Custom Tab
 * covers it. The token is never kept: it goes straight to the plugin.
 */
@Composable
fun ConnectPlexDialog(
    onDismiss: () -> Unit,
    onLinked: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val open = remember { AtomicBoolean(true) }
    val currentOnLinked by rememberUpdatedState(onLinked)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) { onDispose { open.set(false) } }

    LaunchedEffect(attempt) {
        error = null
        ServiceLocator.appScope.launch(Dispatchers.Main) {
            val signIn = ServiceLocator.plexSignIn
            val pin =
                runCatchingCancellable { signIn.begin(strong = true) }.getOrElse {
                    error = "Couldn't start Plex sign-in. Try again."
                    return@launch
                }
            openPlexSignIn(context, signIn.webSignInUrl(pin))
            val app = context.applicationContext
            val token = signIn.awaitToken(pin) { open.get() }
            if (!open.get()) return@launch
            if (token == null) {
                error = "The Plex code expired. Try again."
                return@launch
            }
            returnToApp(app)
            runCatchingCancellable { ServiceLocator.watchlistLinker.connect(token) }
                .onSuccess { result ->
                    when (result) {
                        is PlexLinkResult.Linked -> currentOnLinked()
                        PlexLinkResult.Rejected -> error = "Plex didn't accept that sign-in. Try again."
                        // The app-wide prompt asks whether to switch accounts.
                        is PlexLinkResult.ConflictsWith -> currentOnDismiss()
                    }
                }.onFailure { error = listErrorMessage(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Plex") },
        text = {
            val message = error
            if (message != null) {
                Text(message)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = SashimiAccent)
                    Text("Waiting for Plex…")
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text("Try Again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
