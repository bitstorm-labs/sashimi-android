package dev.bitstorm.sashimi.ui.lists

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.lists.LinkPrompt
import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * The Watchlist's Plex questions and notices, hosted once over the whole app:
 * Requests sign-in can link the Watchlist from Settings, so they can't live on
 * the Watchlist screen. "Sync your Watchlist with Plex account X instead?"
 * when another account is linked; "Your Watchlist now syncs with Plex" after a
 * first automatic link.
 */
@Composable
fun WatchlistLinkPrompts(modifier: Modifier = Modifier) {
    val linker = ServiceLocator.watchlistLinker
    val prompt by linker.prompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(linker) { linker.notices.collect { snackbar.showSnackbar(it) } }

    SnackbarHost(snackbar, modifier)

    // A question asked for another person (signed out or switched since) is dropped, never shown.
    when (val p = prompt?.takeIf { linker.promptStillValid() }) {
        is LinkPrompt.Replace ->
            AlertDialog(
                onDismissRequest = { linker.answer(false) },
                text = { Text("Sync your Watchlist with Plex account ${p.username} instead?") },
                confirmButton = { TextButton(onClick = { linker.answer(true) }) { Text("Switch") } },
                dismissButton = { TextButton(onClick = { linker.answer(false) }) { Text("Keep") } },
            )
        null -> Unit
    }
}
