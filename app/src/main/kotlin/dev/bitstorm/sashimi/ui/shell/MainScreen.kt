package dev.bitstorm.sashimi.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import androidx.navigation.toRoute
import dev.bitstorm.sashimi.core.deeplink.DeepLinkResolver
import dev.bitstorm.sashimi.core.deeplink.DeepLinkTarget
import dev.bitstorm.sashimi.core.home.SideMenuItem
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.herotrailer.DetailAmbience
import dev.bitstorm.sashimi.ui.admin.AdminActivityScreen
import dev.bitstorm.sashimi.ui.admin.AdminHealthScreen
import dev.bitstorm.sashimi.ui.admin.ServerDashboardScreen
import dev.bitstorm.sashimi.ui.auth.AuthScreen
import dev.bitstorm.sashimi.ui.auth.AuthViewModel
import dev.bitstorm.sashimi.ui.auth.AuthViewModelFactory
import dev.bitstorm.sashimi.ui.components.LocalShowQualityBadges
import dev.bitstorm.sashimi.ui.components.LocalShowReviewRatings
import dev.bitstorm.sashimi.ui.components.LocalUseEpisodeRatings
import dev.bitstorm.sashimi.ui.detail.DetailScreen
import dev.bitstorm.sashimi.ui.downloads.DownloadsDestinationIcon
import dev.bitstorm.sashimi.ui.downloads.DownloadsScreen
import dev.bitstorm.sashimi.ui.downloads.OfflineHomeScreen
import dev.bitstorm.sashimi.ui.home.HomeScreen
import dev.bitstorm.sashimi.ui.home.RecentlyAddedGridScreen
import dev.bitstorm.sashimi.ui.library.LibrariesScreen
import dev.bitstorm.sashimi.ui.library.LibraryBrowseScreen
import dev.bitstorm.sashimi.ui.nav.AdminActivityRoute
import dev.bitstorm.sashimi.ui.nav.AdminHealthRoute
import dev.bitstorm.sashimi.ui.nav.DetailRoute
import dev.bitstorm.sashimi.ui.nav.DownloadsRoute
import dev.bitstorm.sashimi.ui.nav.HomeRoute
import dev.bitstorm.sashimi.ui.nav.HomeRowOrderRoute
import dev.bitstorm.sashimi.ui.nav.LibrariesRoute
import dev.bitstorm.sashimi.ui.nav.LibraryBrowseRoute
import dev.bitstorm.sashimi.ui.nav.PersonRoute
import dev.bitstorm.sashimi.ui.nav.PlayerRoute
import dev.bitstorm.sashimi.ui.nav.RecentlyAddedRoute
import dev.bitstorm.sashimi.ui.nav.SearchRoute
import dev.bitstorm.sashimi.ui.nav.ServerDashboardRoute
import dev.bitstorm.sashimi.ui.nav.SettingsRoute
import dev.bitstorm.sashimi.ui.person.PersonScreen
import dev.bitstorm.sashimi.ui.player.PlayerScreen
import dev.bitstorm.sashimi.ui.remote.RemoteMessageBanner
import dev.bitstorm.sashimi.ui.search.SearchScreen
import dev.bitstorm.sashimi.ui.settings.SettingsScreen
import dev.bitstorm.sashimi.ui.theme.SashimiAccent

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
    val isRestoring by session.isRestoring.collectAsStateWithLifecycle()
    val reauthServer by session.reauthServer.collectAsStateWithLifecycle()
    val showQualityBadges by ServiceLocator.appSettings.showQualityBadges.collectAsStateWithLifecycle()
    val showReviewRatings by ServiceLocator.appSettings.showReviewRatings.collectAsStateWithLifecycle()
    val useEpisodeRatings by ServiceLocator.appSettings.useEpisodeRatings.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalShowQualityBadges provides showQualityBadges,
        LocalShowReviewRatings provides showReviewRatings,
        LocalUseEpisodeRatings provides useEpisodeRatings,
    ) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (isRestoring) {
                // A signed-in user must not be shown Connect to Server while the
                // saved session is still being read off disk.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SashimiAccent)
                }
            } else if (!isAuthenticated && reauthServer == null) {
                val vm: AuthViewModel = viewModel(key = "root-auth", factory = AuthViewModelFactory())
                val logoutReason by session.logoutReason.collectAsStateWithLifecycle()
                // Session-expired banner on the connect screen (port of the iOS
                // "Your session has expired…" message).
                val banner =
                    if (logoutReason == dev.bitstorm.sashimi.core.session.LogoutReason.SESSION_EXPIRED) {
                        "Your session has expired. Please sign in again."
                    } else {
                        null
                    }
                AuthScreen(viewModel = vm, banner = banner, onComplete = { session.consumeLogoutReason() })
            } else {
                // Above the shell, so a server message also shows over the player.
                Box(Modifier.fillMaxSize()) {
                    AppShell(session = session, widthSizeClass = widthSizeClass)
                    RemoteMessageBanner(ServiceLocator.remoteMessages, Modifier.align(Alignment.TopCenter))
                }
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

    // Resolve any stashed sashimi:// deep link now that we're authenticated
    // (AppShell only renders when signed in) — the port of iOS pendingDeepLink.
    val pendingDeepLink by ServiceLocator.pendingDeepLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeepLink) {
        val target = DeepLinkResolver.resolve(pendingDeepLink) ?: return@LaunchedEffect
        when (target) {
            is DeepLinkTarget.Play -> {
                DetailAmbience.stopForPlayback()
                navController.navigate(PlayerRoute(itemId = target.itemId))
            }
            is DeepLinkTarget.Detail -> navController.navigate(DetailRoute(itemId = target.itemId))
        }
        ServiceLocator.consumePendingDeepLink()
    }

    // Offline mode: hide Libraries + Search (iPhone PhoneTabView parity) and swap
    // Home for the local-only offline variant.
    val isOnline by ServiceLocator.networkMonitor.isOnline.collectAsStateWithLifecycle()
    val destinations =
        if (isOnline) {
            Destination.entries.toList()
        } else {
            listOf(Destination.HOME, Destination.DOWNLOADS, Destination.SETTINGS)
        }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination

    // The player is a full-screen destination: hide the tab bar / nav rail while
    // it is on top.
    val isPlayerRoute = currentDestination?.hierarchy?.any { it.hasRoute(PlayerRoute::class) } == true

    val navigateToTab: (Destination) -> Unit = { dest ->
        // This is a flat NavHost: the tab roots and the pushed destinations
        // (Detail/LibraryBrowse/…) are all top-level siblings on one shared back
        // stack, so the "canonical" navigate(tabRoot){ popUpTo(start){ saveState }
        // …; restoreState } pattern is broken here — restoreState immediately
        // restores the detail that popUpTo(saveState) just saved, so tapping the
        // current tab (e.g. Home while viewing a detail) does nothing.
        //
        // Correct single-stack behavior: pop back to the tab's root if it's
        // already on the stack (clears any pushed detail, and pops to root on a
        // re-tap); otherwise navigate to it, clearing down to the start
        // destination. Selecting any destination therefore lands on its root.
        val poppedToTabRoot = navController.popBackStack(dest.route, inclusive = false)
        if (!poppedToTabRoot) {
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }

    val host: @Composable (Modifier) -> Unit = { hostModifier ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = hostModifier,
        ) {
            composable<HomeRoute> {
                if (isOnline) {
                    HomeScreen(
                        session = session,
                        onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln)) },
                        onSeeAll = { id, name, ct -> navController.navigate(RecentlyAddedRoute(id, name, ct)) },
                        onAddServer = { showAddServer = true },
                    )
                } else {
                    OfflineHomeScreen(
                        // No serverId: these are local files, which need no server
                        // to play, and their progress syncs to the server each
                        // download came from (DownloadedItemEntity.serverId). The
                        // download's server still picks WHICH copy to play.
                        onPlay = { key ->
                            DetailAmbience.stopForPlayback()
                            navController.navigate(PlayerRoute(itemId = key.itemId, downloadServerId = key.serverId))
                        },
                        onOpenSeries = { id -> navController.navigate(DetailRoute(id)) },
                    )
                }
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
                DownloadsScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    session = session,
                    onAddServer = { showAddServer = true },
                    onOpenRowOrder = { navController.navigate(HomeRowOrderRoute) },
                    onOpenServerDashboard = { navController.navigate(ServerDashboardRoute) },
                    isCompact = isCompact,
                )
            }
            composable<ServerDashboardRoute> {
                ServerDashboardScreen(
                    onBack = { navController.popBackStack() },
                    onOpenActivity = { navController.navigate(AdminActivityRoute) },
                    onOpenHealth = { navController.navigate(AdminHealthRoute) },
                )
            }
            composable<AdminActivityRoute> {
                AdminActivityScreen(onBack = { navController.popBackStack() })
            }
            composable<AdminHealthRoute> {
                AdminHealthScreen(onBack = { navController.popBackStack() })
            }
            composable<HomeRowOrderRoute> {
                dev.bitstorm.sashimi.ui.settings
                    .HomeRowOrderScreen(settings = ServiceLocator.homeRowSettings)
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
            composable<DetailRoute> { entry ->
                val route = entry.toRoute<DetailRoute>()
                DetailScreen(
                    itemId = route.itemId,
                    libraryName = route.libraryName,
                    serverId = route.serverId,
                    isCompact = isCompact,
                    onBack = { navController.popBackStack() },
                    // Episodes, seasons and "Go to Series" stay on the same server.
                    onOpenDetail = { id, ln -> navController.navigate(DetailRoute(id, ln, route.serverId)) },
                    onOpenPerson = { navController.navigate(it) },
                    // Playback stays on the title's server too, without switching servers.
                    onPlay = { playId, fromBeginning ->
                        DetailAmbience.stopForPlayback()
                        // Online, a detail belongs to a server, so only that
                        // server's download of the item plays. Offline, the
                        // detail was rebuilt from the download store with no
                        // server, and any copy of the item will do.
                        val downloadServerId = route.serverId ?: session.activeServerId.value.takeIf { isOnline }
                        navController.navigate(
                            PlayerRoute(
                                itemId = playId,
                                startFromBeginning = fromBeginning,
                                serverId = route.serverId,
                                downloadServerId = downloadServerId,
                            ),
                        )
                    },
                    onPlayTrailer = { trailerId ->
                        DetailAmbience.stopForPlayback()
                        navController.navigate(PlayerRoute(itemId = trailerId, trailerItemId = trailerId, serverId = route.serverId))
                    },
                )
            }
            composable<PersonRoute> { entry ->
                PersonScreen(
                    route = entry.toRoute<PersonRoute>(),
                    onBack = { navController.popBackStack() },
                    // Always pinned to the source's server, even the active one, so
                    // the detail keeps reading the right server if the user switches.
                    onOpenSource = { source ->
                        navController.navigate(DetailRoute(source.item.id, serverId = source.serverId))
                    },
                )
            }
            composable<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                // Backstop for the four navigate(PlayerRoute) call sites above:
                // every route into video, including any added later, is composed
                // here, and the theme and hero trailer must be silent before the player's audio
                // starts. Cheap and idempotent when both are already gone.
                LaunchedEffect(Unit) { DetailAmbience.stopForPlayback() }
                PlayerScreen(
                    itemId = route.itemId,
                    startFromBeginning = route.startFromBeginning,
                    trailerItemId = route.trailerItemId,
                    serverId = route.serverId,
                    downloadServerId = route.downloadServerId,
                    onExit = { navController.popBackStack() },
                )
            }
        }
    }

    if (isPlayerRoute) {
        host(Modifier.fillMaxSize())
    } else if (expanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            // The side menu (#187): Home, each library in Home row order, then
            // Search, Downloads, Settings. No separate Libraries entry here; the
            // phone bottom bar below keeps it.
            val sideMenu: SideMenuViewModel = viewModel()
            val items by sideMenu.items.collectAsStateWithLifecycle()
            val openLibraryId =
                currentEntry
                    ?.takeIf {
                        it.destination.hasRoute(
                            LibraryBrowseRoute::class,
                        )
                    }?.toRoute<LibraryBrowseRoute>()
                    ?.libraryId
            NavigationRail {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items.forEach { item ->
                        val dest = item.destination
                        NavigationRailItem(
                            selected =
                                when (item) {
                                    is SideMenuItem.Library -> item.id == openLibraryId
                                    else -> dest != null && currentDestination.isOnTab(dest)
                                },
                            onClick = {
                                when (item) {
                                    is SideMenuItem.Library ->
                                        navController.navigate(LibraryBrowseRoute(item.id, item.name, item.collectionType)) {
                                            // A side-menu library is a root, like a tab.
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                                        }
                                    else -> dest?.let(navigateToTab)
                                }
                            },
                            icon = {
                                if (dest != null) {
                                    DestinationIcon(dest)
                                } else if (item is SideMenuItem.Library) {
                                    Icon(libraryIcon(item), contentDescription = item.name)
                                }
                            },
                            label = { NavLabel(if (item is SideMenuItem.Library) item.name else dest?.label.orEmpty()) },
                        )
                    }
                }
            }
            host(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentDestination.isOnTab(dest),
                            onClick = { navigateToTab(dest) },
                            icon = { DestinationIcon(dest) },
                            label = { NavLabel(dest.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            // Consume what innerPadding already covers, or every pushed screen's
            // own top bar adds the status bar height a second time.
            host(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding))
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

/** A destination's icon; Downloads doubles as the global download indicator. */
@Composable
private fun DestinationIcon(dest: Destination) {
    if (dest == Destination.DOWNLOADS) {
        DownloadsDestinationIcon(dest.icon, dest.label)
    } else {
        Icon(dest.icon, contentDescription = dest.label)
    }
}

/** The fixed destination a side-menu item opens; null for a library. */
private val SideMenuItem.destination: Destination?
    get() =
        when (this) {
            SideMenuItem.Home -> Destination.HOME
            SideMenuItem.Search -> Destination.SEARCH
            SideMenuItem.Downloads -> Destination.DOWNLOADS
            SideMenuItem.Settings -> Destination.SETTINGS
            is SideMenuItem.Library -> null
        }

/** A library's side-menu icon. YouTube libraries report "tvshows", so they match by name (Apple parity). */
private fun libraryIcon(item: SideMenuItem.Library): ImageVector =
    when {
        item.name.contains("youtube", ignoreCase = true) -> Icons.Filled.SmartDisplay
        item.collectionType == "movies" -> Icons.Filled.Movie
        item.collectionType == "tvshows" -> Icons.Filled.Tv
        item.collectionType == "music" -> Icons.Filled.MusicNote
        item.collectionType == "homevideos" -> Icons.Filled.PhotoLibrary
        else -> Icons.Filled.VideoLibrary
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

/**
 * Tab label that stays on one line. A system font scale above 1.2 is capped here
 * (the tab icon carries the meaning), otherwise at 2.0 "Libraries"/"Downloads"
 * broke mid-word; ellipsis is the last resort on a very narrow item.
 */
@Composable
private fun NavLabel(text: String) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_NAV_LABEL_FONT_SCALE)),
    ) {
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_NAV_LABEL_FONT_SCALE = 1.2f
