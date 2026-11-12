package dev.bitstorm.sashimi.core.settingssync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import dev.bitstorm.sashimi.core.home.HomeRowStore
import dev.bitstorm.sashimi.core.lists.StubServer
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.VideoViewMode
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.core.shuffle.TvShuffleMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class MemoryRowStore(
    var rows: List<HomeRowConfig>? = null,
) : HomeRowStore {
    override fun load() = rows

    override fun save(rows: List<HomeRowConfig>) {
        this.rows = rows
    }
}

/** The real [AppSettings] and Home rows wired to the sync service, over a stub server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSyncedSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stub = StubServer()
    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
        }
    private val get = "GET /DisplayPreferences/sashimi-settings"
    private val post = "POST /DisplayPreferences/sashimi-settings"
    private val changes = mutableListOf<String>()
    private lateinit var settings: AppSettings
    private lateinit var rowStore: MemoryRowStore
    private lateinit var rows: HomeRowSettings
    private lateinit var synced: AppSyncedSettings

    @Before
    fun setUp() {
        context
            .getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        settings = AppSettings(context).also { it.onSyncedSettingChanged = { key -> changes += key } }
        rowStore = MemoryRowStore(listOf(HomeRowConfig.continueWatching(), HomeRowConfig.requests(), HomeRowConfig.library("m", "Movies")))
        rows = HomeRowSettings(SyncingHomeRowStore(rowStore) { changes += SettingsSyncSchema.HOME_ROWS })
        synced = AppSyncedSettings(settings, rows) { rowStore.rows }
    }

    private fun entry(
        v: String,
        t: Long,
    ) = "\"" + """{"v":$v,"t":$t}""".replace("\"", "\\\"") + "\""

    @Test
    fun `every synced setter reports its shared key and device-only settings do not`() {
        settings.setAutoPlayNextEpisode(false)
        settings.setAutoSkipIntro(true)
        settings.setAutoSkipCredits(true)
        settings.setResumeThresholdSeconds(600)
        settings.setForceDirectPlay(true)
        settings.setSubtitlesEnabled(true)
        settings.setPreferredAudioLanguage("fi")
        settings.setPreferredSubtitleLanguage("ru")
        settings.setTvShuffleMode(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE)
        settings.videoViewModes.setDefault(VideoViewMode.ZOOM)
        settings.setShowQualityBadges(false)
        settings.setShowReviewRatings(false)
        settings.setUseEpisodeRatings(true)
        settings.setUse24HourTime(true)
        settings.setMaxBitrate(4_000_000)
        settings.setThemeSongsEnabled(false)
        settings.setHeroTrailers(true)
        settings.setShowLists(false)

        assertEquals(SettingsSyncSchema.ANDROID_KEYS - SettingsSyncSchema.HOME_ROWS, changes)
    }

    @Test
    fun `applying a remote value updates the setting without reporting a change`() {
        synced.apply("autoSkipIntro", JsonPrimitive(true))
        synced.apply("resumeThresholdSeconds", JsonPrimitive(600))
        synced.apply("defaultViewMode", JsonPrimitive("stretch"))
        synced.apply("tvShuffleMode", JsonPrimitive("randomShowNextEpisode"))
        synced.apply("homeRows", Json.parseToJsonElement("""[{"id":"library:m","on":false},{"id":"continueWatching","on":true}]"""))

        assertTrue(settings.autoSkipIntro.value)
        assertEquals(600, settings.resumeThresholdSeconds.value)
        assertEquals(VideoViewMode.STRETCH, settings.videoViewModes.defaultMode.value)
        assertEquals(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, settings.tvShuffleMode.value)
        assertEquals(
            "m",
            rows.rows.value
                .first { it.kind == HomeRowConfig.Kind.LIBRARY }
                .id,
        )
        assertFalse(
            rows.rows.value
                .first { it.kind == HomeRowConfig.Kind.LIBRARY }
                .isEnabled,
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a user reorder of Home rows is reported, a new library is not`() {
        rows.updateLibraries(listOf(lib("m", "Movies"), lib("s", "Shows")))
        assertTrue(changes.isEmpty())

        rows.moveRow("s", "m")

        assertEquals(listOf(SettingsSyncSchema.HOME_ROWS), changes)
    }

    @Test
    fun `only settings that were written count as explicitly set`() {
        assertTrue(synced.explicitlySet().isEmpty())

        settings.setAutoSkipIntro(false)
        rowStore.rows = listOf(HomeRowConfig.library("m", "Movies"), HomeRowConfig.continueWatching())

        assertEquals(setOf("autoSkipIntro", "homeRows"), synced.explicitlySet())
    }

    @Test
    fun `a change in the app is pushed and a newer server value lands with its own stamp`() =
        runTest {
            val state = PrefsSettingsSyncState(context)
            val service = SettingsSyncService(JellyfinSettingsSyncTransport(client), synced, state, backgroundScope, now = { 7_000 })
            val pushes = mutableListOf<kotlinx.coroutines.Job?>()
            settings.onSyncedSettingChanged = { pushes += service.localChanged(it) }
            stub.routes[get] = 200 to """{"Id":"sashimi-settings","CustomPrefs":{"autoSkipCredits":${entry("true", 9_000)}}}"""
            stub.routes[post] = 204 to ""

            settings.setAutoSkipIntro(true)
            pushes.single()!!.join()

            val custom = Json.parseToJsonElement(stub.calls(post).single().body!!).jsonObject["CustomPrefs"]!!.jsonObject
            assertEquals(StampedValue(JsonPrimitive(true), 7_000), StampedValueCodec.decode(custom["autoSkipIntro"]))
            assertNull("an untouched default is not pushed", custom["showQualityBadges"])

            // The server now holds what was pushed.
            stub.routes[get] = 200 to """{"Id":"sashimi-settings","CustomPrefs":$custom}"""
            service.reconcile()

            assertTrue(settings.autoSkipCredits.value)
            assertEquals(9_000L, state.stamps()!!["autoSkipCredits"])
            assertEquals("the applied value is not echoed back", 1, stub.calls(post).size)
            assertEquals("and applying it reported no local change", 1, pushes.size)
        }

    @Test
    fun `the sync state is local and on by default`() {
        val state = PrefsSettingsSyncState(context)
        assertTrue(state.enabled.value)
        assertNull(state.stamps())

        state.setEnabled(false)
        state.updateStamps { mapOf("autoSkipIntro" to 5L) }

        val reread = PrefsSettingsSyncState(context)
        assertFalse(reread.enabled.value)
        assertEquals(mapOf("autoSkipIntro" to 5L), reread.stamps())
    }

    private fun lib(
        id: String,
        name: String,
    ) = dev.bitstorm.sashimi.core.model
        .JellyfinLibrary(id = id, name = name)
}
