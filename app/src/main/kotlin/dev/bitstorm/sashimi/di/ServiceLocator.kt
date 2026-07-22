package dev.bitstorm.sashimi.di

import android.content.Context
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.session.EncryptedTokenStore
import dev.bitstorm.sashimi.core.session.PrefsServerStore
import dev.bitstorm.sashimi.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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

    private val appScope = CoroutineScope(SupervisorJob())

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
