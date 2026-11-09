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
import dev.bitstorm.sashimi.core.lists.ConnectPlexOutcome
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
            val app = context.applicationContext
            val outcome =
                ServiceLocator.watchlistConnectPlex.run(
                    resume = null,
                    onPin = { pin -> openPlexSignIn(context, ServiceLocator.plexSignIn.webSignInUrl(pin)) },
                    onToken = { returnToApp(app) },
                    stillWaiting = { open.get() },
                )
            when (outcome) {
                ConnectPlexOutcome.Linked -> currentOnLinked()
                // The app-wide prompt asks whether to switch accounts.
                ConnectPlexOutcome.Conflict -> currentOnDismiss()
                ConnectPlexOutcome.Abandoned -> Unit
                else -> error = connectPlexMessage(outcome)
            }
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

/** What a failed Connect Plex tells the person. */
private fun connectPlexMessage(outcome: ConnectPlexOutcome): String =
    when (outcome) {
        ConnectPlexOutcome.CouldNotStart -> "Couldn't start Plex sign-in. Try again."
        ConnectPlexOutcome.Expired -> "The Plex code expired. Try again."
        ConnectPlexOutcome.Rejected -> "Plex didn't accept that sign-in. Try again."
        ConnectPlexOutcome.PlexUnreachable -> "Couldn't reach Plex. Try again."
        is ConnectPlexOutcome.Failed -> listErrorMessage(outcome.error)
        ConnectPlexOutcome.Linked, ConnectPlexOutcome.Conflict, ConnectPlexOutcome.Abandoned -> ""
    }
