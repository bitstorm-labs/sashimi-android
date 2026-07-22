package dev.bitstorm.sashimi.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.ui.auth.AuthScreen
import dev.bitstorm.sashimi.ui.auth.AuthViewModel
import dev.bitstorm.sashimi.ui.auth.AuthViewModelFactory
import dev.bitstorm.sashimi.ui.settings.SettingsScreen

/**
 * Root of the authenticated/unauthenticated app. Unauthenticated shows the
 * connect flow; authenticated shows the adaptive tabbed shell. A prefilled
 * re-auth sheet is presented whenever [SessionManager.reauthServer] is set
 * (driven by switching to a saved server whose token expired).
 */
@Composable
fun MainScreen(
    session: SessionManager,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    val isAuthenticated by session.isAuthenticated.collectAsStateWithLifecycle()
    val reauthServer by session.reauthServer.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!isAuthenticated && reauthServer == null) {
            // Root connect flow — no Cancel (nothing to cancel back to).
            val vm: AuthViewModel = viewModel(key = "root-auth", factory = AuthViewModelFactory())
            AuthScreen(viewModel = vm)
        } else {
            AppShell(session = session, widthSizeClass = widthSizeClass)
        }
    }

    // Prefilled re-auth sheet for a saved server whose session expired.
    reauthServer?.let { server ->
        val vm: AuthViewModel =
            viewModel(key = "reauth-${server.id}", factory = AuthViewModelFactory())
        androidx.compose.runtime.LaunchedEffect(server.id) {
            vm.prefill(serverUrl = server.url, username = server.username)
        }
        FullScreenSheet(
            onDismiss = {
                session.clearReauthServer()
                session.restoreActiveClient()
            },
        ) {
            AuthScreen(
                viewModel = vm,
                onCancel = {
                    session.clearReauthServer()
                    session.restoreActiveClient()
                },
                onComplete = { session.clearReauthServer() },
            )
        }
    }
}

@Composable
private fun AppShell(
    session: SessionManager,
    widthSizeClass: WindowWidthSizeClass,
) {
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showAddServer by remember { mutableStateOf(false) }

    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        DestinationContent(
            destination = current,
            session = session,
            onAddServer = { showAddServer = true },
            modifier = contentModifier,
        )
    }

    val expanded = widthSizeClass != WindowWidthSizeClass.Compact

    if (expanded) {
        // iPad-class: navigation rail beside the content.
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Destination.entries.forEach { dest ->
                    NavigationRailItem(
                        selected = current == dest,
                        onClick = { current = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
            content(Modifier.fillMaxSize())
        }
    } else {
        // Compact (iPhone parity): bottom navigation bar.
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest,
                            onClick = { current = dest },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            content(Modifier.fillMaxSize().padding(innerPadding))
        }
    }

    if (showAddServer) {
        val vm: AuthViewModel =
            viewModel(key = "add-server", factory = AuthViewModelFactory())
        FullScreenSheet(
            onDismiss = {
                // The probe repointed the shared client at the candidate server;
                // restore it to the active server so the live session survives.
                session.restoreActiveClient()
                showAddServer = false
            },
        ) {
            AuthScreen(
                viewModel = vm,
                onCancel = {
                    session.restoreActiveClient()
                    showAddServer = false
                },
                onComplete = { showAddServer = false },
            )
        }
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    session: SessionManager,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        Destination.HOME -> ComingSoonScreen("Home", "M2", modifier)
        Destination.LIBRARIES -> ComingSoonScreen("Libraries", "M2", modifier)
        Destination.SEARCH -> ComingSoonScreen("Search", "M2", modifier)
        Destination.DOWNLOADS -> ComingSoonScreen("Downloads", "M4", modifier)
        Destination.SETTINGS ->
            SettingsScreen(session = session, onAddServer = onAddServer, modifier = modifier)
    }
}

@Composable
private fun FullScreenSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
