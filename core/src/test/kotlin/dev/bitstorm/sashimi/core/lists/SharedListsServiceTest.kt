package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** A request the stub saw: "METHOD /path?query" plus its body. */
private data class Seen(
    val call: String,
    val body: String?,
)

/** Answers "METHOD /path" from a table (404 when absent) and records every call. */
private class StubServer : Interceptor {
    val routes = mutableMapOf<String, Pair<Int, String>>()

    /** Answers used once each, in order, before [routes]. */
    val queued = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    /** Paths that fail as if the network were down. */
    val unreachable = mutableSetOf<String>()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = "${request.method} ${request.url.encodedPath}"
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        seen += Seen(key + (request.url.encodedQuery?.let { "?$it" } ?: ""), body)
        if (key in unreachable) throw java.io.IOException("unreachable")
        val (status, json) = queued[key]?.removeFirstOrNull() ?: routes[key] ?: (404 to "")
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    fun calls(prefix: String) = seen.filter { it.call.startsWith(prefix) }
}

private fun movie(
    id: String,
    entry: String = "pe-$id",
) = """{"Id":"$id","Name":"$id","Type":"Movie","PlaylistItemId":"$entry"}"""

private fun episode(
    id: String,
    series: String,
    season: Int,
    number: Int,
    entry: String = "pe-$id",
) = """{"Id":"$id","Name":"$id","Type":"Episode","SeriesId":"$series","SeriesName":"Show $series",""" +
    """"SeriesPrimaryImageTag":"tag-$series","ParentIndexNumber":$season,"IndexNumber":$number,"PlaylistItemId":"$entry"}"""

private fun items(vararg json: String) = json.joinToString(prefix = """{"Items":[""", postfix = """],"TotalRecordCount":${json.size}}""")

class SharedListsServiceTest {
    private val me = "me"
    private val stub = StubServer()
    private var expired = false

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = me)
            it.sessionExpiredHandler = { expired = true }
        }

    private val service = SharedListsService(client, publicRecheckDelayMs = 2_000)

    private fun serveList(
        id: String,
        name: String,
        sharing: String,
        entries: String,
    ) {
        stub.routes["GET /Users/$me/Items/$id"] = 200 to """{"Id":"$id","Name":"$name","Type":"Playlist"}"""
        stub.routes["GET /Playlists/$id"] = 200 to sharing
        stub.routes["GET /Playlists/$id/Items"] = 200 to entries
    }

    @Test
    fun `count comes from the viewer's filtered items, never ItemIds`() =
        runTest {
            // Two entries on the list, but a parental limit hides one from this viewer.
            serveList(
                "l1",
                "Christmas",
                """{"OpenAccess":true,"Shares":[{"UserId":"marge","CanEdit":true}],"ItemIds":["m1","m2"]}""",
                items(movie("m1")),
            )
            stub.routes["GET /Users"] = 200 to """[{"Id":"marge","Name":"Marge"},{"Id":"me","Name":"Me"}]"""

            val list = service.list("l1")!!

            assertEquals(1, list.count)
            assertEquals(listOf("m1"), list.displayIds)
            assertEquals("Marge", list.ownerName)
            assertFalse(list.isMine)
            assertEquals("From Marge: Christmas", list.rowTitle)
            val itemsCall = stub.calls("GET /Playlists/l1/Items").single().call
            assertTrue("items are asked for as this viewer", "userId=$me" in itemsCall)
        }

    @Test
    fun `a list made outside Sashimi has no creator and does not break`() =
        runTest {
            serveList("l1", "Old", """{"OpenAccess":true,"Shares":[]}""", items(movie("m1")))

            val list = service.list("l1")!!

            assertNull(list.ownerId)
            assertNull(list.ownerName)
            assertEquals("Old", list.rowTitle)
        }

    @Test
    fun `a pilot shows as its series and remove takes every entry behind a card`() =
        runTest {
            serveList(
                "l1",
                "Mine",
                """{"OpenAccess":false,"Shares":[{"UserId":"me","CanEdit":true}]}""",
                items(episode("e1", "show1", 1, 1, "pe1"), movie("m1"), episode("e5", "show1", 1, 1, "pe5")),
            )
            stub.routes["DELETE /Playlists/l1/Items"] = 204 to ""

            val list = service.list("l1")!!
            assertTrue(list.isMine)
            assertEquals("Your list: Mine", list.rowTitle)
            val show = list.items.first()
            assertEquals("show1", show.item.id)
            assertEquals("Show show1", show.item.name)
            assertEquals(mapOf("Primary" to "tag-show1"), show.item.imageTags)
            assertEquals(listOf("pe1", "pe5"), show.entryIds)

            service.remove("l1", show)
            assertEquals(
                "DELETE /Playlists/l1/Items?entryIds=pe1,pe5",
                java.net.URLDecoder.decode(stub.calls("DELETE").single().call, "UTF-8"),
            )
        }

    @Test
    fun `a 404 on a subscribed list prunes it on the next save`() =
        runTest {
            stub.routes["GET /DisplayPreferences/sashimi-lists"] =
                200 to """{"Id":"x","CustomPrefs":{"subscriptions":"[{\"id\":\"gone\",\"seen\":[]},{\"id\":\"kept\",\"seen\":[\"a\"]}]"}}"""
            stub.routes["POST /DisplayPreferences/sashimi-lists"] = 204 to ""

            assertNull(service.list("gone"))
            assertEquals(setOf("gone"), service.missing)

            val saved = service.updateSubscriptions { it }

            assertEquals(listOf(ListSubscription("kept", listOf("a"))), saved)
            val body = Json.parseToJsonElement(stub.calls("POST /DisplayPreferences").single().body!!).jsonObject
            val stored = body["CustomPrefs"]!!.jsonObject["subscriptions"]!!.jsonPrimitive.content
            assertEquals(saved, SharedLists.decodeSubscriptions(stored))
            assertTrue("pruned once saved", service.missing.isEmpty())
        }

    @Test
    fun `saving keeps every other preference as it was read`() =
        runTest {
            stub.routes["GET /DisplayPreferences/sashimi-lists"] =
                200 to """{"Id":"x","SortBy":"SortName","Client":"sashimi","CustomPrefs":{"skipBackLength":"10000","tvhome":null}}"""
            stub.routes["POST /DisplayPreferences/sashimi-lists"] = 204 to ""

            service.updateSubscriptions { SharedLists.subscribe(it, "l1", listOf("m1")) }

            val post = stub.calls("POST /DisplayPreferences").single()
            assertTrue("userId=$me" in post.call && "client=sashimi" in post.call)
            val body = Json.parseToJsonElement(post.body!!).jsonObject
            assertEquals("SortName", body["SortBy"]!!.jsonPrimitive.content)
            val custom = body["CustomPrefs"]!!.jsonObject
            assertEquals("10000", custom["skipBackLength"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, custom["tvhome"])
            assertEquals(
                listOf(ListSubscription("l1", listOf("m1"))),
                SharedLists.decodeSubscriptions(custom["subscriptions"]!!.jsonPrimitive.content),
            )
        }

    @Test
    fun `a new list is shared with its creator`() =
        runTest {
            stub.routes["POST /Playlists"] = 200 to """{"Id":"new"}"""
            stub.routes["POST /Playlists/new/Users/$me"] = 204 to ""

            assertEquals("new", service.create("Date Night", isPublic = true, firstItem = null))

            val create = Json.parseToJsonElement(stub.calls("POST /Playlists").first().body!!).jsonObject
            assertEquals("Video", create["MediaType"]!!.jsonPrimitive.content)
            assertEquals("true", create["IsPublic"]!!.jsonPrimitive.content)
            assertEquals(me, create["UserId"]!!.jsonPrimitive.content)
            assertEquals("""{"CanEdit":true}""", stub.calls("POST /Playlists/new/Users/$me").single().body)
        }

    @Test
    fun `the first title goes in after the self-share, never in the create call`() =
        runTest {
            // Jellyfin re-saves a playlist created with Ids from its pre-share
            // state, dropping the creator's share a few seconds later.
            stub.routes["POST /Playlists"] = 200 to """{"Id":"new"}"""
            stub.routes["POST /Playlists/new/Users/$me"] = 204 to ""
            stub.routes["POST /Playlists/new/Items"] = 204 to ""
            val film =
                dev.bitstorm.sashimi.core.model
                    .BaseItemDto(id = "m1", type = dev.bitstorm.sashimi.core.model.ItemType.MOVIE)

            service.create("Date Night", isPublic = false, firstItem = film)

            val create = Json.parseToJsonElement(stub.calls("POST /Playlists").first().body!!).jsonObject
            assertEquals("[]", create["Ids"].toString())
            val order = stub.seen.map { it.call.substringBefore("?") }
            assertEquals(listOf("POST /Playlists", "POST /Playlists/new/Users/$me", "POST /Playlists/new/Items"), order)
            assertTrue("ids=m1" in stub.seen.last().call)
        }

    @Test
    fun `a list whose self-share fails is deleted again`() =
        runTest {
            stub.routes["POST /Playlists"] = 200 to """{"Id":"new"}"""
            stub.routes["POST /Playlists/new/Users/$me"] = 500 to ""
            stub.routes["DELETE /Items/new"] = 204 to ""

            try {
                service.create("Date Night", isPublic = false)
                fail("the failed share must be reported")
            } catch (e: JellyfinError.HttpError) {
                assertEquals(500, e.statusCode)
            }
            assertEquals(1, stub.calls("DELETE /Items/new").size)
        }

    @Test
    fun `adding a show stores its first episode, never the series`() =
        runTest {
            stub.routes["GET /Shows/show1/Episodes"] =
                200 to items(episode("s0e1", "show1", 0, 1), episode("s1e2", "show1", 1, 2), episode("s1e1", "show1", 1, 1))
            stub.routes["GET /Playlists/l1/Items"] = 200 to items(movie("m1"))
            stub.routes["POST /Playlists/l1/Items"] = 204 to ""
            val show =
                dev.bitstorm.sashimi.core.model
                    .BaseItemDto(id = "show1", type = dev.bitstorm.sashimi.core.model.ItemType.SERIES)

            assertEquals(AddResult.ADDED, service.add("l1", show))

            val add = stub.calls("POST /Playlists/l1/Items").single().call
            assertTrue(add, "ids=s1e1" in add)
            assertFalse(add, "show1" in add)
        }

    @Test
    fun `adding something already on the list adds nothing`() =
        runTest {
            stub.routes["GET /Shows/show1/Episodes"] = 200 to items(episode("s1e1", "show1", 1, 1))
            stub.routes["GET /Playlists/l1/Items"] = 200 to items(episode("s1e1", "show1", 1, 1), movie("m1"))
            val show =
                dev.bitstorm.sashimi.core.model
                    .BaseItemDto(id = "show1", type = dev.bitstorm.sashimi.core.model.ItemType.SERIES)
            val film =
                dev.bitstorm.sashimi.core.model
                    .BaseItemDto(id = "m1", type = dev.bitstorm.sashimi.core.model.ItemType.MOVIE)

            assertEquals(AddResult.ALREADY_ON_LIST, service.add("l1", show))
            assertEquals(AddResult.ALREADY_ON_LIST, service.add("l1", film))
            assertTrue(stub.calls("POST").isEmpty())
        }

    @Test
    fun `the server refusing a change to someone else's list is not an expired session`() =
        runTest {
            stub.routes["GET /Playlists/l1/Items"] = 200 to items()
            stub.routes["POST /Playlists/l1/Items"] = 403 to ""
            val film =
                dev.bitstorm.sashimi.core.model
                    .BaseItemDto(id = "m1", type = dev.bitstorm.sashimi.core.model.ItemType.MOVIE)

            try {
                service.add("l1", film)
                fail("a refusal must be reported")
            } catch (e: JellyfinError) {
                assertEquals(JellyfinError.Forbidden, e)
            }
            assertFalse("the user stays signed in", expired)
        }

    @Test
    fun `rename and public switch send only the field that changes`() =
        runTest {
            stub.routes["POST /Playlists/l1"] = 204 to ""
            stub.routes["GET /Playlists/l1"] = 200 to """{"OpenAccess":false,"Shares":[]}"""

            service.rename("l1", "New name")
            service.setPublic("l1", false)

            assertEquals(listOf("""{"Name":"New name"}""", """{"IsPublic":false}"""), stub.calls("POST /Playlists/l1").map { it.body })
        }

    // MARK: - Owner (#208)

    private fun publicListWithWipedShare(id: String = "l1") =
        serveList(id, "Christmas", """{"OpenAccess":true,"Shares":[]}""", items(movie("m1")))

    @Test
    fun `the plugin names the creator after the self-share was wiped`() =
        runTest {
            // Jellyfin 12.4 drops every self-share when any user is deleted.
            publicListWithWipedShare()
            stub.routes["GET /SashimiLists/Playlists/l1/Owner"] = 200 to """{"OwnerUserId":"marge","OwnerName":"Marge"}"""

            val list = service.list("l1")!!

            assertEquals("marge", list.ownerId)
            assertEquals("Marge", list.ownerName)
            assertFalse(list.isMine)
            assertEquals("From Marge: Christmas", list.rowTitle)
            assertTrue("someone else's share is never touched", stub.calls("POST").isEmpty())
        }

    @Test
    fun `the plugin's owner wins over a stale self-share`() =
        runTest {
            serveList("l1", "Christmas", """{"OpenAccess":true,"Shares":[{"UserId":"me","CanEdit":true}]}""", items())
            stub.routes["GET /SashimiLists/Playlists/l1/Owner"] = 200 to """{"OwnerUserId":"marge","OwnerName":"Marge"}"""

            assertFalse(service.list("l1")!!.isMine)
        }

    @Test
    fun `my list whose self-share was wiped is still mine and gets the share back`() =
        runTest {
            publicListWithWipedShare()
            // The plugin may write ids with dashes and capitals.
            stub.routes["GET /SashimiLists/Playlists/l1/Owner"] = 200 to """{"OwnerUserId":"M-E","OwnerName":"Me"}"""
            stub.routes["POST /Playlists/l1/Users/$me"] = 204 to ""

            val list = service.list("l1")!!

            assertTrue(list.isMine)
            assertEquals("Your list: Christmas", list.rowTitle)
            assertEquals("""{"CanEdit":true}""", stub.calls("POST /Playlists/l1/Users/$me").single().body)
        }

    @Test
    fun `without the plugin the owner self-check finds my list`() =
        runTest {
            publicListWithWipedShare()
            stub.routes["GET /Playlists/l1/Users/$me"] = 200 to """{"UserId":"$me","CanEdit":true}"""
            stub.routes["POST /Playlists/l1/Users/$me"] = 204 to ""

            val list = service.list("l1")!!

            assertTrue(list.isMine)
            assertEquals(me, list.ownerId)
            assertEquals(1, stub.calls("POST /Playlists/l1/Users/$me").size)
        }

    @Test
    fun `the self-check ignores CanDelete, which every admin has`() =
        runTest {
            publicListWithWipedShare()
            stub.unreachable += "GET /SashimiLists/Playlists/l1/Owner"
            stub.routes["GET /Playlists/l1/Users/$me"] = 200 to """{"UserId":"$me","CanEdit":false,"CanDelete":true}"""

            val list = service.list("l1")!!

            assertFalse(list.isMine)
            assertNull(list.ownerId)
            assertTrue(stub.calls("POST").isEmpty())
        }

    @Test
    fun `without the plugin the self-share still names the creator`() =
        runTest {
            serveList("l1", "Christmas", """{"OpenAccess":true,"Shares":[{"UserId":"marge","CanEdit":true}]}""", items())
            stub.routes["GET /Users"] = 200 to """[{"Id":"marge","Name":"Marge"}]"""

            val list = service.list("l1")!!

            assertEquals("Marge", list.ownerName)
            assertFalse(list.isMine)
            assertTrue("no self-check needed", stub.calls("GET /Playlists/l1/Users").isEmpty())
        }

    @Test
    fun `the Lists page asks the plugin once for every owner`() =
        runTest {
            stub.routes["GET /Items"] =
                200 to items("""{"Id":"l1","Name":"A","Type":"Playlist"}""", """{"Id":"l2","Name":"B","Type":"Playlist"}""")
            serveList("l1", "A", """{"OpenAccess":true,"Shares":[]}""", items())
            serveList("l2", "B", """{"OpenAccess":true,"Shares":[]}""", items())
            stub.routes["GET /SashimiLists/Owners"] =
                200 to """{"l1":{"OwnerUserId":"marge","OwnerName":"Marge"},"l2":{"OwnerUserId":"me","OwnerName":"Me"}}"""
            stub.routes["POST /Playlists/l2/Users/$me"] = 204 to ""

            val lists = service.allLists().associateBy { it.id }

            assertEquals("Marge", lists.getValue("l1").ownerName)
            assertTrue(lists.getValue("l2").isMine)
            val batch = java.net.URLDecoder.decode(stub.calls("GET /SashimiLists/Owners").single().call, "UTF-8")
            assertTrue(batch, "ids=l1,l2" in batch)
            assertTrue(stub.calls("GET /SashimiLists/Playlists").isEmpty())
            assertTrue(stub.calls("GET /Playlists/l1/Users").isEmpty())
        }

    @Test
    fun `a batch from Home is used instead of asking per list`() =
        runTest {
            publicListWithWipedShare()
            stub.routes["GET /SashimiLists/Owners"] = 200 to """{"l1":{"OwnerUserId":"marge","OwnerName":"Marge"}}"""

            val owners = service.owners(listOf("l1"))
            val list = service.list("l1", owners)!!

            assertEquals("Marge", list.ownerName)
            assertTrue(stub.calls("GET /SashimiLists/Playlists").isEmpty())
        }

    @Test
    fun `a plugin that is not installed falls back without failing the page`() =
        runTest {
            stub.routes["GET /Items"] = 200 to items("""{"Id":"l1","Name":"A","Type":"Playlist"}""")
            serveList("l1", "A", """{"OpenAccess":true,"Shares":[{"UserId":"marge","CanEdit":true}]}""", items())
            stub.routes["GET /Users"] = 200 to """[{"Id":"marge","Name":"Marge"}]"""
            stub.unreachable += "GET /SashimiLists/Owners"

            assertEquals("Marge", service.allLists().single().ownerName)
            assertFalse(expired)
        }

    // MARK: - Public switch (#208)

    @Test
    fun `a Public switch the server reverted is sent again and the server's value returned`() =
        runTest {
            stub.routes["POST /Playlists/l1"] = 204 to ""
            // A queued refresh after an add saves the old OpenAccess about a second later.
            stub.queued["GET /Playlists/l1"] =
                ArrayDeque(listOf(200 to """{"OpenAccess":false}""", 200 to """{"OpenAccess":true}"""))

            val start = testScheduler.currentTime
            assertTrue(service.setPublic("l1", true))

            assertEquals(2, stub.calls("POST /Playlists/l1").size)
            assertEquals(2, stub.calls("GET /Playlists/l1").size)
            assertTrue("re-read only after the refresh lands", testScheduler.currentTime - start >= 4_000)
        }

    @Test
    fun `a Public switch that held is sent once`() =
        runTest {
            stub.routes["POST /Playlists/l1"] = 204 to ""
            stub.routes["GET /Playlists/l1"] = 200 to """{"OpenAccess":true}"""

            assertTrue(service.setPublic("l1", true))
            assertEquals(1, stub.calls("POST /Playlists/l1").size)
        }

    @Test
    fun `a switch the server keeps undoing reports the server's value, not the wish`() =
        runTest {
            stub.routes["POST /Playlists/l1"] = 204 to ""
            stub.routes["GET /Playlists/l1"] = 200 to """{"OpenAccess":false}"""

            assertFalse(service.setPublic("l1", true))
            assertEquals("re-applied once only", 2, stub.calls("POST /Playlists/l1").size)
        }
}
