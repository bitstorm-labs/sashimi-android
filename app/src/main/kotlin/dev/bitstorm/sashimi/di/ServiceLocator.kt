package dev.bitstorm.sashimi.di

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import dev.bitstorm.sashimi.BuildConfig
import dev.bitstorm.sashimi.core.admin.AdminGate
import dev.bitstorm.sashimi.core.downloads.DownloadDatabase
import dev.bitstorm.sashimi.core.downloads.DownloadFileManager
import dev.bitstorm.sashimi.core.downloads.DownloadManager
import dev.bitstorm.sashimi.core.downloads.DownloadRepository
import dev.bitstorm.sashimi.core.downloads.DownloadSchema
import dev.bitstorm.sashimi.core.downloads.KeepNextEpisodesStore
import dev.bitstorm.sashimi.core.downloads.KeepNextEpisodesSync
import dev.bitstorm.sashimi.core.downloads.NetworkMonitor
import dev.bitstorm.sashimi.core.downloads.PrefsTierEncodeLedger
import dev.bitstorm.sashimi.core.home.ContinueWatchingHides
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.AndroidCodecCapabilities
import dev.bitstorm.sashimi.core.playback.DeviceProfileBuilder
import dev.bitstorm.sashimi.core.playback.PlaybackEngine
import dev.bitstorm.sashimi.core.playback.PrefsThroughputPersistence
import dev.bitstorm.sashimi.core.playback.SustainedThroughputStore
import dev.bitstorm.sashimi.core.remote.RemoteCommand
import dev.bitstorm.sashimi.core.remote.RemoteControlConnection
import dev.bitstorm.sashimi.core.remote.RemoteControlPresence
import dev.bitstorm.sashimi.core.remote.RemoteMessageBoard
import dev.bitstorm.sashimi.core.remote.RemoteSessionKey
import dev.bitstorm.sashimi.core.search.RecentSearchStore
import dev.bitstorm.sashimi.core.session.EncryptedTokenStore
import dev.bitstorm.sashimi.core.session.PrefsHiddenSeriesStore
import dev.bitstorm.sashimi.core.session.PrefsHomeRowStore
import dev.bitstorm.sashimi.core.session.PrefsKeepNextEpisodesStore
import dev.bitstorm.sashimi.core.session.PrefsRecentSearchStore
import dev.bitstorm.sashimi.core.session.PrefsServerStore
import dev.bitstorm.sashimi.core.session.ServerScopedClients
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.herotrailer.HeroTrailerController
import dev.bitstorm.sashimi.themesong.ThemeSongService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Minimal hand-rolled DI. M1 has no need for a framework; a single process-wide
 * graph built from the Application context is enough. The JellyfinClient and
 * SessionManager are the Android analogues of the Swift `.shared` singletons.
 */
object ServiceLocator {
    private const val PREFS = "sashimi_device"
    private const val KEY_DEVICE_ID = "deviceId"

    lateinit var client: JellyfinClient
        private set

    lateinit var session: SessionManager
        private set

    /**
     * Clients for work tied to one saved server: the person filmography, a
     * detail route opened from it, and that title's playback, downloads and
     * theme song. Other servers get their own client; the shared [client]
     * always follows the active server and is never repointed.
     */
    lateinit var serverClients: ServerScopedClients<JellyfinClient>
        private set

    /**
     * Whether the signed-in user is a Jellyfin administrator, which decides
     * whether Settings offers the server dashboard. Re-read on every launch,
     * sign-in (Quick Connect included) and server switch.
     */
    val adminGate = AdminGate()

    /** Whether the session socket should be open: the app is foreground or something plays. */
    val remotePresence = RemoteControlPresence()

    /**
     * The active server's session socket: it makes this device controllable
     * from the server dashboard (Stop, Send a message). Commands arrive on
     * [RemoteControlConnection.commands]; the player handles Stop.
     */
    lateinit var remoteControl: RemoteControlConnection
        private set

    /** The server message on screen, drawn over everything by MainScreen. */
    lateinit var remoteMessages: RemoteMessageBoard
        private set

    lateinit var homeRowSettings: HomeRowSettings
        private set

    /** Series removed from Continue Watching, per server + user. */
    lateinit var continueWatchingHides: ContinueWatchingHides
        private set

    lateinit var recentSearchStore: RecentSearchStore
        private set

    lateinit var appSettings: AppSettings
        private set

    lateinit var playbackEngine: PlaybackEngine
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    lateinit var downloadManager: DownloadManager
        private set

    lateinit var downloadFileManager: DownloadFileManager
        private set

    /** Per-show "Keep next episodes downloaded" settings. */
    lateinit var keepNextEpisodes: KeepNextEpisodesStore
        private set

    /** Applies [keepNextEpisodes]: deletes watched episodes, queues the next ones. */
    lateinit var keepNextSync: KeepNextEpisodesSync
        private set

    /**
     * Owns theme-song playback and the show-visit state. App-level on purpose:
     * screens report intent to it and never touch the player themselves.
     */
    lateinit var themeSongs: ThemeSongService
        private set

    /**
     * Owns the detail-screen hero trailer: the player, which screen shows it,
     * and the server encode behind it. App-level like [themeSongs].
     */
    lateinit var heroTrailers: HeroTrailerController
        private set

    /**
     * Scope for work that must outlive the UI that started it -- a mutation the
     * user has already committed to must not be cancelled just because they
     * navigated away or the row scrolled out of view.
     */
    val appScope = CoroutineScope(SupervisorJob())

    /**
     * A `sashimi://` deep link captured by [dev.bitstorm.sashimi.MainActivity]
     * that hasn't been consumed yet. Stashed here (rather than routed straight
     * into the NavHost) so a cold start while signed-out defers the link until
     * after authentication — the port of the iOS ContentView `pendingDeepLink`.
     */
    private val _pendingDeepLink = MutableStateFlow<String?>(null)
    val pendingDeepLink: StateFlow<String?> = _pendingDeepLink.asStateFlow()

    fun setPendingDeepLink(uri: String?) {
        _pendingDeepLink.value = uri
    }

    fun consumePendingDeepLink() {
        _pendingDeepLink.value = null
    }

    fun init(context: Context) {
        if (::client.isInitialized) return
        val app = context.applicationContext

        // The version the server dashboard shows for this device. Without it every
        // session reported the client's placeholder default, 0.1.0 (#163).
        client = JellyfinClient(deviceId = stableDeviceId(app), clientVersion = BuildConfig.VERSION_NAME)
        val serverStore = PrefsServerStore(app)
        session =
            SessionManager(
                gateway = client,
                serverStore = serverStore,
                tokenStore = EncryptedTokenStore(app),
                scope = appScope,
            )
        serverClients =
            ServerScopedClients(
                shared = client,
                activeServerId = { session.activeServerId.value },
                servers = { session.servers.value },
                tokenFor = session::tokenFor,
            ) { server, token ->
                client.forServer(serverUrl = server.url, accessToken = token, userId = server.userId)
            }
        appScope.launch {
            combine(session.isAuthenticated, session.activeServerId, session.currentUser) { auth, server, user ->
                Triple(auth, server, user?.id)
            }.distinctUntilChanged().collectLatest { (auth, _, _) ->
                if (auth) refreshAdminGate() else adminGate.signedOut()
            }
        }
        homeRowSettings = HomeRowSettings(PrefsHomeRowStore(app))
        continueWatchingHides = ContinueWatchingHides(PrefsHiddenSeriesStore(app))
        recentSearchStore = RecentSearchStore(PrefsRecentSearchStore(app))
        appSettings = AppSettings(app)
        networkMonitor = NetworkMonitor(app)
        startRemoteControl()
        playbackEngine =
            PlaybackEngine(
                client,
                DeviceProfileBuilder(AndroidCodecCapabilities(app)),
                throughput = SustainedThroughputStore(PrefsThroughputPersistence(app)),
                linkKind = networkMonitor::linkKind,
            )
        themeSongs = ThemeSongService(app, serverClients::clientFor, appSettings, appScope)

        heroTrailers =
            HeroTrailerController(
                context = app,
                client = { client },
                engine = { playbackEngine },
                activeServerId = { session.activeServerId.value },
                settings = appSettings,
                network = networkMonitor,
                themeIsPlaying = themeSongs.isPlaying,
                themeEnding = themeSongs.endingNaturally,
                appScope = appScope,
            )
        downloadFileManager = DownloadFileManager(app)
        val db =
            Room
                .databaseBuilder(app, DownloadDatabase::class.java, "sashimi_downloads.db")
                .addMigrations(
                    DownloadDatabase.MIGRATION_2_3,
                    // Read from the store, not the session: the migration runs on
                    // the first query, which can beat the session restore.
                    DownloadDatabase.migration3To4 {
                        DownloadSchema.legacyServerId(serverStore.getActiveServerId(), serverStore.loadServers().map { it.id })
                    },
                ).fallbackToDestructiveMigration()
                .build()
        downloadManager =
            DownloadManager(
                context = app,
                repository = DownloadRepository(db.downloadDao()),
                fileManager = downloadFileManager,
                clientFor = serverClients::forRecord,
                networkMonitor = networkMonitor,
                authenticated = session.isAuthenticated,
                scope = appScope,
                tierEncodes = PrefsTierEncodeLedger(app),
            )
        startKeepNextEpisodes(app)
    }

    /**
     * Keep next episodes downloaded. Triggers, as on Apple: app foreground
     * (MainActivity), the network coming back, a download completing, and a
     * player closing (PlayerViewModel). The sync itself is debounced.
     */
    private fun startKeepNextEpisodes(app: Context) {
        keepNextEpisodes = KeepNextEpisodesStore(PrefsKeepNextEpisodesStore(app))
        keepNextSync =
            KeepNextEpisodesSync(
                store = keepNextEpisodes,
                clientFor = { serverId -> serverClients.clientFor(serverId) },
                downloads = { downloadManager.downloads.value },
                enqueue = { items, quality, serverId -> downloadManager.enqueueDownloads(items, quality, serverId) },
                delete = downloadManager::delete,
                isOnline = { networkMonitor.isOnline.value && session.isAuthenticated.value },
                scope = appScope,
            )
        appScope.launch {
            networkMonitor.isOnline
                .drop(1)
                .filter { it }
                .collect { keepNextSync.scheduleSync() }
        }
        appScope.launch {
            downloadManager.downloads
                .map { rows -> rows.count { it.isComplete } }
                .distinctUntilChanged()
                .drop(1)
                .collect { keepNextSync.scheduleSync() }
        }
    }

    /**
     * Opens the socket for the active server's signed-in user while
     * [remotePresence] wants it, and routes DisplayMessage to the board. The
     * socket follows the shared [client], which always tracks the active server.
     */
    private fun startRemoteControl() {
        remoteMessages = RemoteMessageBoard(appScope)
        remoteControl =
            RemoteControlConnection(
                connector = client.remoteSocketConnector(),
                resolveTarget = client::remoteTarget,
                registerCapabilities = { client.reportCapabilities() },
            )
        val keys =
            combine(
                session.isAuthenticated,
                session.activeServerId,
                session.currentUser,
                remotePresence.wanted,
                networkMonitor.isOnline,
            ) { auth, server, user, wanted, online ->
                RemoteSessionKey.of(auth, server, user?.id, wanted, online)
            }
        appScope.launch { remoteControl.run(keys) }
        appScope.launch {
            remoteControl.commands.filterIsInstance<RemoteCommand.DisplayMessage>().collect(remoteMessages::show)
        }
    }

    /** Re-reads the admin flag for the active server's user. */
    suspend fun refreshAdminGate() = adminGate.refresh { client.getCurrentUser() }

    /**
     * The per-server client for a saved server, or null when that server is no
     * longer saved or has no token (signed out, or dropped by a past expiry).
     */
    fun clientForServer(serverId: String): JellyfinClient? = serverClients.dedicated(serverId)

    /**
     * The client to play a title from [serverId] with: the shared client for
     * the active server (or null), that server's own client otherwise. Null when
     * that server is no longer saved or has no token.
     */
    fun playbackClientFor(serverId: String?): JellyfinClient? = serverClients.clientFor(serverId)

    /** A UUID generated once per install and reused (mirrors the Swift deviceId). */
    private fun stableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = JellyfinClient.newDeviceId()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }
}
