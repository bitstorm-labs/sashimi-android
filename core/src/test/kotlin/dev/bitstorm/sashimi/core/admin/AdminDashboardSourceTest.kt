package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** Answers from a "METHOD path" -> (status, JSON) table and records requests. */
private class AdminStub : Interceptor {
    val routes = mutableMapOf<String, Pair<Int, String>>()
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val key = "${req.method} ${req.url.encodedPath}"
        requested += key + (req.url.encodedQuery?.let { "?$it" } ?: "")
        req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8().let(bodies::add) }
        val (status, body) = routes[key] ?: (404 to "")
        return Response
            .Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

class AdminDashboardSourceTest {
    private val stub = AdminStub()
    private var expired = 0

    private fun client() =
        JellyfinClient(
            deviceId = "test",
            httpClient = OkHttpClient.Builder().addInterceptor(stub).build(),
        ).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "u1")
            it.sessionExpiredHandler = { expired++ }
        }

    private val sessionsJson =
        """[{"Id":"s1","UserName":"mom","DeviceName":"Roku","SupportsRemoteControl":true,
           "NowPlayingItem":{"Id":"i1","Name":"300","Type":"Movie"},
           "TranscodingInfo":{"IsVideoDirect":true,"IsAudioDirect":false,"Bitrate":38000000,"TranscodeReasons":["AudioCodecNotSupported"]}},
           {"Id":"s2","UserName":"ozzy"}]"""

    @Test
    fun `current user reads the admin flag from Users Me`() =
        runTest {
            stub.routes["GET /Users/Me"] = 200 to """{"Id":"u1","Name":"mondo","Policy":{"IsAdministrator":true}}"""
            assertTrue(AdminGate.isAdministrator(client().getCurrentUser()))
        }

    @Test
    fun `sessions decode TranscodingInfo`() =
        runTest {
            stub.routes["GET /Sessions"] = 200 to sessionsJson
            val sessions = client().getAdminSessions()
            assertEquals(PlayMode.CONVERTING_AUDIO, PlayModes.of(sessions[0].transcodingInfo))
            assertTrue(stub.requested.single().contains("activeWithinSeconds="))
            assertEquals(1, ServerSummary.playing(sessions).size)
        }

    @Test
    fun `a 403 on an admin endpoint is Forbidden and never expires the session`() =
        runTest {
            stub.routes["GET /System/ActivityLog/Entries"] = 403 to ""
            try {
                client().getActivityLog(20)
                fail("expected Forbidden")
            } catch (e: JellyfinError.Forbidden) {
                // expected
            }
            assertEquals(0, expired)
        }

    @Test
    fun `a 401 on an admin endpoint still expires the session`() =
        runTest {
            stub.routes["GET /ScheduledTasks"] = 401 to ""
            try {
                client().getScheduledTasks()
                fail("expected SessionExpired")
            } catch (e: JellyfinError.SessionExpired) {
                // expected
            }
            assertEquals(1, expired)
        }

    @Test
    fun `missing storage endpoint gives null, not an error`() =
        runTest {
            assertNull(client().getStorage())
        }

    @Test
    fun `activity asks for a bounded page`() =
        runTest {
            stub.routes["GET /System/ActivityLog/Entries"] = 200 to """{"Items":[{"Id":5,"Name":"x"}],"TotalRecordCount":1}"""
            assertEquals(5L, client().getActivityLog(20).single().id)
            assertTrue(stub.requested.single().contains("limit=20"))
        }

    @Test
    fun `stop and message hit the session commands`() =
        runTest {
            stub.routes["POST /Sessions/s1/Playing/Stop"] = 204 to ""
            stub.routes["POST /Sessions/s1/Message"] = 204 to ""
            val c = client()
            c.stopSession("s1")
            c.sendSessionMessage("s1", "Dinner's ready")
            assertEquals(listOf("POST /Sessions/s1/Playing/Stop", "POST /Sessions/s1/Message"), stub.requested.toList())
            val body = stub.bodies.last()
            assertTrue(body, body.contains("\"Text\":\"Dinner's ready\""))
            assertTrue(body, body.contains("\"TimeoutMs\""))
        }

    @Test
    fun `sessions poll every 5 seconds`() =
        runTest {
            stub.routes["GET /Sessions"] = 200 to sessionsJson
            val source = AdminDashboardSource(client())
            val start = currentTime
            val loads = source.sessions().take(3).toList()
            assertTrue(loads.all { it is AdminLoad.Ready })
            assertEquals(10_000L, currentTime - start)
            assertEquals(3, stub.requested.size)
        }

    @Test
    fun `health polls every 30 seconds and carries every part`() =
        runTest {
            stub.routes["GET /System/Info"] = 200 to """{"ServerName":"JellyPal","Version":"12.2.0"}"""
            stub.routes["GET /ScheduledTasks"] =
                200 to
                """[{"Id":"t","Name":"Scan Media Library","Key":"RefreshLibrary",
                   "LastExecutionResult":{"Status":"Failed","EndTimeUtc":"2026-10-18T19:00:00Z"}}]"""
            stub.routes["GET /System/ActivityLog/Entries"] = 200 to """{"Items":[]}"""
            stub.routes["GET /System/Info/Storage"] =
                200 to """{"TranscodingTempFolder":{"FreeSpace":10,"UsedSpace":90,"DeviceId":"/cache"},"Libraries":[]}"""
            stub.routes["GET /System/Configuration/encoding"] = 200 to """{"HardwareAccelerationType":"qsv"}"""
            val source = AdminDashboardSource(client())
            val start = currentTime
            val loads = source.health().take(2).toList()
            assertEquals(30_000L, currentTime - start)
            val snap = (loads.first() as AdminLoad.Ready).data
            assertEquals("JellyPal", snap.info.serverName)
            assertEquals("qsv", snap.encoding?.hardwareAccelerationType)
            assertEquals(1, snap.storage.size)
            assertEquals("Scan Media Library", snap.tasks.single().name)
        }

    @Test
    fun `health survives a server without storage or encoding info`() =
        runTest {
            stub.routes["GET /System/Info"] = 200 to """{"ServerName":"JellyPal","Version":"12.2.0"}"""
            stub.routes["GET /ScheduledTasks"] = 200 to "[]"
            stub.routes["GET /System/ActivityLog/Entries"] = 200 to """{"Items":[]}"""
            val snap = (AdminDashboardSource(client()).health().take(1).toList().single() as AdminLoad.Ready).data
            assertEquals(emptyList<StorageRow>(), snap.storage)
            assertNull(snap.encoding)
        }

    @Test
    fun `Forbidden stops polling for good`() =
        runTest {
            stub.routes["GET /Sessions"] = 403 to ""
            val loads = AdminDashboardSource(client()).sessions().toList()
            assertEquals(listOf<AdminLoad<*>>(AdminLoad.Forbidden), loads)
            assertEquals(1, stub.requested.size)
        }

    @Test
    fun `a server error is reported and polling carries on`() =
        runTest {
            stub.routes["GET /Sessions"] = 500 to ""
            val loads = AdminDashboardSource(client()).sessions().take(2).toList()
            assertTrue(loads.all { it is AdminLoad.Failed })
            assertFalse(loads.any { it is AdminLoad.Forbidden })
        }
}
