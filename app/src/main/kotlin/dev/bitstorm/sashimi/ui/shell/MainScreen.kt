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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.auth.AuthScreen
import dev.bitstorm.sashimi.ui.auth.AuthViewModel
import dev.bitstorm.sashimi.ui.auth.AuthViewModelFactory
import dev.bitstorm.sashimi.ui.components.LocalShowQualityBadges
import dev.bitstorm.sashimi.ui.detail.DetailScreen
import dev.bitstorm.sashimi.ui.home.HomeScreen
import dev.bitstorm.sashimi.ui.home.RecentlyAddedGridScreen
import dev.bitstorm.sashimi.ui.library.LibrariesScreen
import dev.bitstorm.sashimi.ui.library.LibraryBrowseScreen
import dev.bitstorm.sashimi.ui.nav.DetailRoute
import dev.bitstorm.sashimi.ui.nav.DownloadsRoute
import dev.bitstorm.sashimi.ui.nav.HomeRoute
import dev.bitstorm.sashimi.ui.nav.LibrariesRoute
import dev.bitstorm.sashimi.ui.nav.LibraryBrowseRoute
import dev.bitstorm.sashimi.ui.nav.RecentlyAddedRoute
import dev.bitstorm.sashimi.ui.nav.SearchRoute
import dev.bitstorm.sashimi.ui.nav.SettingsRoute
import dev.bitstorm.sashimi.ui.search.SearchScreen
import dev.bitstorm.sashimi.ui.settings.SettingsScreen

/**
 * Root of the authenticated/unauthenticated app. Unauthenticated shows the
 * connect flow; authenticated shows the adaptive tabbed shell. A prefilled
 * re-auth sheet is presented whenever [SessionManager.reauthServer] is set.
 */
@Composable
fun MainScreen(
    session: SessionManager,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    val isAuthenticated by session.isAuthenticated.collectAsStateWithLifecycle()
    val reauthServer by session.reauthServer.collectAsStateWithLifecycle()
    val showQualityBadges by ServiceLocator.appSettings.showQualityBadges.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalShowQualityBadges provides showQualityBadges) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!isAuthenticated && reauthServer == null) {
                val vm: AuthViewModel = viewModel(key = "root-auth", factory = AuthViewModelFactory())
                AuthScreen(viewModel = vm)
            } else {
                AppShell(session = session, widthSizeClass = widthSizeClass)
            }
        }
    }

    reauthServer?.let { server ->
        val vm: AuthViewModel =
            viewModel(key = "reauth-${server.id}", factory = AuthViewModelFactory())
        LaunchedEffect(server.id) {
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
    val navController = rememberNavController()
    var showAddServer by remember { mutableStateOf(false) }
    val expanded = widthSizeClass != WindowWidthSizeClass.Compact
    val isCompact = widthSizeClass == WindowWidthSizeClass.Compact

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination

    val navigateToTab: (Destination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val host: @Composable (Modifier) -> Unit = { hostModifier ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = hostModifier,
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    session = session,
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                    onSeeAll = { id, name, ct -> navController.navigate(RecentlyAddedRoute(id, name, ct)) },
                    onAddServer = { showAddServer = true },
                )
            }
            composable<LibrariesRoute> {
                LibrariesScreen(
                    onOpenLibrary = { id, name, ct -> navController.navigate(LibraryBrowseRoute(id, name, ct)) },
                )
            }
            composable<SearchRoute> {
                SearchScreen(
                    isCompact = isCompact,
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                )
            }
            composable<DownloadsRoute> {
                ComingSoonScreen("Downloads", "M4")
            }
            composable<SettingsRoute> {
                SettingsScreen(session = session, onAddServer = { showAddServer = true })
            }
            composable<LibraryBrowseRoute> { entry ->
                val route = entry.toRoute<LibraryBrowseRoute>()
                LibraryBrowseScreen(
                    libraryId = route.libraryId,
                    libraryName = route.libraryName,
                    collectionType = route.collectionType,
                    isCompact = isCompact,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                )
            }
            composable<RecentlyAddedRoute> { entry ->
                val route = entry.toRoute<RecentlyAddedRoute>()
                RecentlyAddedGridScreen(
                    libraryId = route.libraryId,
                    libraryName = route.libraryName,
                    collectionType = route.collectionType,
                    isCompact = isCompact,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                )
            }
            composable<DetailRoute>(
                deepLinks =
                    listOf(
                        navDeepLink<DetailRoute>(basePath = "sashimi://item"),
                        // TODO(M3): sashimi://play/{id} should open the player; for
                        // now it routes to detail so the deep link is not dead.
                        navDeepLink<DetailRoute>(basePath = "sashimi://play"),
                    ),
            ) { entry ->
                val route = entry.toRoute<DetailRoute>()
                DetailScreen(
                    itemId = route.itemId,
                    libraryName = route.libraryName,
                    isCompact = isCompact,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                )
            }
        }
    }

    if (expanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Destination.entries.forEach { dest ->
                    NavigationRailItem(
                        selected = currentDestination.isOnTab(dest),
                        onClick = { navigateToTab(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
            host(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentDestination.isOnTab(dest),
                            onClick = { navigateToTab(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            host(Modifier.fillMaxSize().padding(innerPadding))
        }
    }

    if (showAddServer) {
        val vm: AuthViewModel = viewModel(key = "add-server", factory = AuthViewModelFactory())
        FullScreenSheet(
            onDismiss = {
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

/** True when the current destination belongs to [dest]'s tab (incl. pushed children). */
private fun androidx.navigation.NavDestination?.isOnTab(dest: Destination): Boolean =
    this?.hierarchy?.any { navDest ->
        when (dest) {
            Destination.HOME -> navDest.hasRoute(HomeRoute::class)
            Destination.LIBRARIES -> navDest.hasRoute(LibrariesRoute::class)
            Destination.SEARCH -> navDest.hasRoute(SearchRoute::class)
            Destination.DOWNLOADS -> navDest.hasRoute(DownloadsRoute::class)
            Destination.SETTINGS -> navDest.hasRoute(SettingsRoute::class)
        }
    } == true

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
