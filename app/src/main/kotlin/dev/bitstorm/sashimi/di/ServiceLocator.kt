package dev.bitstorm.sashimi.di

import android.content.Context
import androidx.room.Room
import dev.bitstorm.sashimi.core.downloads.DownloadDatabase
import dev.bitstorm.sashimi.core.downloads.DownloadFileManager
import dev.bitstorm.sashimi.core.downloads.DownloadManager
import dev.bitstorm.sashimi.core.downloads.DownloadRepository
import dev.bitstorm.sashimi.core.downloads.NetworkMonitor
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.AndroidCodecCapabilities
import dev.bitstorm.sashimi.core.playback.DeviceProfileBuilder
import dev.bitstorm.sashimi.core.playback.PlaybackEngine
import dev.bitstorm.sashimi.core.search.RecentSearchStore
import dev.bitstorm.sashimi.core.session.EncryptedTokenStore
import dev.bitstorm.sashimi.core.session.PrefsHomeRowStore
import dev.bitstorm.sashimi.core.session.PrefsRecentSearchStore
import dev.bitstorm.sashimi.core.session.PrefsServerStore
import dev.bitstorm.sashimi.core.session.SessionManager
import dev.bitstorm.sashimi.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    lateinit var homeRowSettings: HomeRowSettings
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

    private val appScope = CoroutineScope(SupervisorJob())

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

        client = JellyfinClient(deviceId = stableDeviceId(app))
        session =
            SessionManager(
                gateway = client,
                serverStore = PrefsServerStore(app),
                tokenStore = EncryptedTokenStore(app),
                scope = appScope,
            )
        homeRowSettings = HomeRowSettings(PrefsHomeRowStore(app))
        recentSearchStore = RecentSearchStore(PrefsRecentSearchStore(app))
        appSettings = AppSettings(app)
        playbackEngine = PlaybackEngine(client, DeviceProfileBuilder(AndroidCodecCapabilities()))

        networkMonitor = NetworkMonitor(app)
        downloadFileManager = DownloadFileManager(app)
        val db =
            Room.databaseBuilder(app, DownloadDatabase::class.java, "sashimi_downloads.db")
                .fallbackToDestructiveMigration()
                .build()
        downloadManager =
            DownloadManager(
                context = app,
                repository = DownloadRepository(db.downloadDao()),
                fileManager = downloadFileManager,
                client = client,
                networkMonitor = networkMonitor,
                scope = appScope,
            )
    }

    /** A UUID generated once per install and reused (mirrors the Swift deviceId). */
    private fun stableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = JellyfinClient.newDeviceId()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
