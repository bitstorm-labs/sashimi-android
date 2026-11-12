package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.lists.StubServer
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory settings: values as JSON, with the explicitly set keys tracked. */
internal class FakeSyncedSettings(
    override val keys: List<String> = SettingsSyncSchema.ANDROID_KEYS,
) : SyncedSettings {
    val values = mutableMapOf<String, JsonElement>()
    val explicit = mutableSetOf<String>()
    val applied = mutableListOf<Pair<String, JsonElement>>()

    override fun explicitlySet(): Set<String> = explicit

    override fun value(
        key: String,
        remote: JsonElement?,
    ): JsonElement = values[key] ?: JsonNull

    override fun apply(
        key: String,
        v: JsonElement,
        t: Long,
        stamps: Map<String, Long>,
    ) {
        values[key] = v
        applied += key to v
    }
}

internal class FakeSyncState(
    enabled: Boolean = true,
    var stampMap: Map<String, Long>? = emptyMap(),
) : SettingsSyncState {
    private val flow = MutableStateFlow(enabled)
    override val enabled: StateFlow<Boolean> = flow

    override fun setEnabled(on: Boolean) {
        flow.value = on
    }

    override fun stamps(): Map<String, Long>? = stampMap

    override fun updateStamps(change: (Map<String, Long>?) -> Map<String, Long>) {
        stampMap = change(stampMap)
    }
}

class SettingsSyncServiceTest {
    private val me = "me"
    private val stub = StubServer()
    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = me)
        }
    private val settings = FakeSyncedSettings()
    private val state = FakeSyncState()
    private val get = "GET /DisplayPreferences/sashimi-settings"
    private val post = "POST /DisplayPreferences/sashimi-settings"

    private fun service(
        scope: TestScope,
        now: Long = 9_999,
    ) = SettingsSyncService(JellyfinSettingsSyncTransport(client), settings, state, scope.backgroundScope, now = { now })

    private fun entry(
        v: String,
        t: Long,
    ) = "\"" + """{"v":$v,"t":$t}""".replace("\"", "\\\"") + "\""

    private fun serve(vararg custom: Pair<String, String>) {
        val prefs = custom.joinToString(",") { (k, v) -> "\"$k\":$v" }
        stub.routes[get] = 200 to """{"Id":"sashimi-settings","SortBy":"SortName","Client":"sashimi","CustomPrefs":{$prefs}}"""
        stub.routes[post] = 204 to ""
    }

    private fun posted(): JsonObject = Json.parseToJsonElement(stub.calls(post).single().body!!).jsonObject

    private fun postedCustom(): JsonObject = posted()["CustomPrefs"]!!.jsonObject

    private fun postedEntry(key: String): StampedValue? = StampedValueCodec.decode(postedCustom()[key])

    @Test
    fun `V1 a newer remote value is applied with the remote stamp and nothing is written`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(false)
            state.stampMap = mapOf("autoSkipIntro" to 100L)
            serve("autoSkipIntro" to entry("true", 200))

            service(this).reconcile()

            assertEquals(JsonPrimitive(true), settings.values["autoSkipIntro"])
            assertEquals(200L, state.stampMap!!["autoSkipIntro"])
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `V2 a newer local value is kept and written with its own stamp`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(false)
            state.stampMap = mapOf("autoSkipIntro" to 300L)
            serve("autoSkipIntro" to entry("true", 200))

            service(this).reconcile()

            assertEquals(JsonPrimitive(false), settings.values["autoSkipIntro"])
            assertEquals(StampedValue(JsonPrimitive(false), 300), postedEntry("autoSkipIntro"))
            assertTrue(settings.applied.isEmpty())
        }

    @Test
    fun `V3 equal stamps change nothing and write nothing`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(false)
            state.stampMap = mapOf("autoSkipIntro" to 200L)
            serve("autoSkipIntro" to entry("true", 200))

            service(this).reconcile()

            assertEquals(JsonPrimitive(false), settings.values["autoSkipIntro"])
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `V4 a resume threshold that is not an option is ignored and the stamp stays`() =
        runTest {
            settings.values["resumeThresholdSeconds"] = JsonPrimitive(30)
            state.stampMap = mapOf("resumeThresholdSeconds" to 0L)
            serve("resumeThresholdSeconds" to entry("45", 500))

            service(this).reconcile()

            assertEquals(JsonPrimitive(30), settings.values["resumeThresholdSeconds"])
            assertEquals(0L, state.stampMap!!["resumeThresholdSeconds"])
            assertTrue("a value we do not know is never overwritten by reconcile", stub.calls(post).isEmpty())
        }

    @Test
    fun `V5 an unknown shuffle mode is ignored`() =
        runTest {
            settings.values["tvShuffleMode"] = JsonPrimitive("randomEpisode")
            serve("tvShuffleMode" to entry("\"shuffleAll\"", 500))

            service(this).reconcile()

            assertEquals(JsonPrimitive("randomEpisode"), settings.values["tvShuffleMode"])
            assertNull(state.stampMap!!["tvShuffleMode"])
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `V6 a write keeps every other CustomPrefs key and top-level field verbatim`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(true)
            state.stampMap = mapOf("autoSkipIntro" to 300L)
            serve(
                "subscriptions" to "\"[{\\\"id\\\":\\\"l1\\\"}]\"",
                "skipBackLength" to "\"10000\"",
                "futureKey" to """{"nested":[1,2]}""",
                "tvhome" to "null",
            )

            service(this).reconcile()

            val body = posted()
            assertEquals("SortName", body["SortBy"]!!.jsonPrimitive.content)
            assertEquals("sashimi-settings", body["Id"]!!.jsonPrimitive.content)
            val custom = postedCustom()
            assertEquals("[{\"id\":\"l1\"}]", custom["subscriptions"]!!.jsonPrimitive.content)
            assertEquals("10000", custom["skipBackLength"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""{"nested":[1,2]}"""), custom["futureKey"])
            assertEquals(JsonNull, custom["tvhome"])
            assertEquals("1", custom["schema"]!!.jsonPrimitive.content)
            val call = stub.calls(post).single().call
            assertTrue("userId=$me" in call && "client=sashimi" in call)
        }

    @Test
    fun `V7 with sync off a local change makes no request and is stamped now`() =
        runTest {
            state.setEnabled(false)
            serve()
            val service = service(this, now = 4_242)

            assertNull(service.localChanged("autoSkipIntro"))
            service.reconcile()

            assertTrue(stub.seen.isEmpty())
            assertEquals(4_242L, state.stampMap!!["autoSkipIntro"])
        }

    @Test
    fun `turning sync back on pushes what changed while it was off`() =
        runTest {
            state.setEnabled(false)
            settings.values["autoSkipIntro"] = JsonPrimitive(true)
            serve("autoSkipIntro" to entry("false", 100))
            val service = service(this, now = 4_242)
            service.localChanged("autoSkipIntro")

            service.setEnabled(true)!!.join()

            assertEquals(StampedValue(JsonPrimitive(true), 4_242), postedEntry("autoSkipIntro"))
        }

    @Test
    fun `a local change is pushed when the server holds an older value`() =
        runTest {
            settings.values["use24HourTime"] = JsonPrimitive(true)
            serve("use24HourTime" to entry("false", 100), "autoSkipIntro" to entry("true", 50))
            val service = service(this, now = 5_000)

            service.localChanged("use24HourTime")!!.join()

            assertEquals(StampedValue(JsonPrimitive(true), 5_000), postedEntry("use24HourTime"))
            assertEquals(StampedValue(JsonPrimitive(true), 50), postedEntry("autoSkipIntro"))
        }

    @Test
    fun `a local change is not pushed over a newer server value`() =
        runTest {
            settings.values["use24HourTime"] = JsonPrimitive(true)
            serve("use24HourTime" to entry("false", 9_000))
            val service = service(this, now = 5_000)

            service.localChanged("use24HourTime")!!.join()

            assertEquals(1, stub.calls(get).size)
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `V10 first run seeds explicitly set keys with stamp 1 and leaves defaults out`() =
        runTest {
            state.stampMap = null
            settings.values["autoSkipIntro"] = JsonPrimitive(true)
            settings.values["showQualityBadges"] = JsonPrimitive(true)
            settings.explicit += "autoSkipIntro"
            serve()

            service(this).reconcile()

            assertEquals(StampedValue(JsonPrimitive(true), 1), postedEntry("autoSkipIntro"))
            assertFalse("showQualityBadges" in postedCustom())
            assertEquals(mapOf("autoSkipIntro" to 1L), state.stampMap)
        }

    @Test
    fun `a fresh install with defaults takes the published values instead of overwriting them`() =
        runTest {
            state.stampMap = null
            settings.values["autoSkipIntro"] = JsonPrimitive(false)
            serve("autoSkipIntro" to entry("true", 1))

            service(this).reconcile()

            assertEquals(JsonPrimitive(true), settings.values["autoSkipIntro"])
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `V11 an entry that is not JSON is ignored without a crash`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(false)
            state.stampMap = mapOf("autoSkipIntro" to 100L)
            serve("autoSkipIntro" to "\"{not json\"", "autoSkipCredits" to entry("true", 300))

            service(this).reconcile()

            assertEquals(JsonPrimitive(false), settings.values["autoSkipIntro"])
            assertEquals(100L, state.stampMap!!["autoSkipIntro"])
            assertEquals(JsonPrimitive(true), settings.values["autoSkipCredits"])
            assertTrue(stub.calls(post).isEmpty())
        }

    @Test
    fun `a missing remote key with a local stamp is written in one request with the others`() =
        runTest {
            settings.values["autoSkipIntro"] = JsonPrimitive(true)
            settings.values["forceDirectPlay"] = JsonPrimitive(true)
            state.stampMap = mapOf("autoSkipIntro" to 10L, "forceDirectPlay" to 20L)
            serve()

            service(this).reconcile()

            assertEquals(1, stub.calls(post).size)
            assertEquals(StampedValue(JsonPrimitive(true), 10), postedEntry("autoSkipIntro"))
            assertEquals(StampedValue(JsonPrimitive(true), 20), postedEntry("forceDirectPlay"))
        }

    @Test
    fun `sync off makes no request on reconcile`() =
        runTest {
            state.setEnabled(false)
            state.stampMap = mapOf("autoSkipIntro" to 10L)
            serve("autoSkipCredits" to entry("true", 300))

            service(this).reconcile()

            assertTrue(stub.seen.isEmpty())
            assertTrue(settings.applied.isEmpty())
        }

    @Test
    fun `a higher schema number is kept and its known keys still apply`() =
        runTest {
            serve("schema" to "\"2\"", "autoSkipCredits" to entry("true", 300))
            settings.values["autoSkipIntro"] = JsonPrimitive(true)
            state.stampMap = mapOf("autoSkipIntro" to 10L)

            service(this).reconcile()

            assertEquals(JsonPrimitive(true), settings.values["autoSkipCredits"])
            assertEquals("2", postedCustom()["schema"]!!.jsonPrimitive.content)
        }

    @Test
    fun `homeRows is written with the remote layout to carry through`() =
        runTest {
            val remoteLayout = """[{"id":"continueWatching","on":true},{"id":"hero","on":true}]"""
            val layoutSettings =
                object : SyncedSettings by settings {
                    override fun value(
                        key: String,
                        remote: JsonElement?,
                    ): JsonElement {
                        assertEquals(Json.parseToJsonElement(remoteLayout), remote)
                        return Json.parseToJsonElement("""[{"id":"hero","on":true}]""")
                    }
                }
            state.stampMap = mapOf("homeRows" to 900L)
            serve("homeRows" to entry(remoteLayout, 100))

            SettingsSyncService(JellyfinSettingsSyncTransport(client), layoutSettings, state, backgroundScope).reconcile()

            assertEquals(Json.parseToJsonElement("""[{"id":"hero","on":true}]"""), postedEntry("homeRows")!!.v)
        }
}
