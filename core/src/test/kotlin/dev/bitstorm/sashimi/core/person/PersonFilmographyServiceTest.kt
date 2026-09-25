package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.network.JellyfinError
import dev.bitstorm.sashimi.core.session.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersonFilmographyServiceTest {
    private val person = PersonInfo(id = "origin-person", name = "Zoë Kravitz", type = "Actor")

    private fun server(id: String) =
        ServerConfig(
            id = id,
            name = "Server $id",
            url = "https://$id.example",
            username = "u",
            userId = "user-$id",
        )

    private fun movie(
        id: String,
        name: String,
    ) = BaseItemDto(id = id, name = name, type = ItemType.MOVIE, productionYear = 2022)

    private class FakeClient(
        val people: List<PersonInfo> = emptyList(),
        val media: Map<String, List<BaseItemDto>> = emptyMap(),
        val failWith: Exception? = null,
        val beforeMedia: suspend () -> Unit = {},
    ) : PersonFilmographyClient {
        val searched = mutableListOf<String>()
        val mediaRequested = mutableListOf<String>()

        override suspend fun searchPeople(
            name: String,
            limit: Int,
        ): List<PersonInfo> {
            failWith?.let { throw it }
            searched += name
            return people
        }

        override suspend fun getPersonMedia(
            personId: String,
            pageSize: Int,
        ): List<BaseItemDto> {
            failWith?.let { throw it }
            mediaRequested += personId
            beforeMedia()
            return media[personId].orEmpty()
        }
    }

    private fun service(
        servers: List<ServerConfig>,
        clients: Map<String, FakeClient>,
        tokens: Map<String, String?> = servers.associate { it.id to "token-${it.id}" },
        built: MutableList<Pair<String, String>> = mutableListOf(),
    ) = PersonFilmographyService(
        servers = { servers },
        tokenFor = { tokens[it.id] },
        clientFor = { s, token ->
            built += s.id to token
            clients.getValue(s.id)
        },
    )

    @Test
    fun `origin uses the person id, other servers resolve by folded name`() =
        runTest {
            val origin = FakeClient(media = mapOf("origin-person" to listOf(movie("o1", "The Batman"))))
            val other =
                FakeClient(
                    people = listOf(PersonInfo("wrong", "Zoe Saldana"), PersonInfo("b-person", "Zoe Kravitz")),
                    media = mapOf("b-person" to listOf(movie("b1", "Big Little Lies"))),
                )
            val built = mutableListOf<Pair<String, String>>()
            val load = service(listOf(server("a"), server("b")), mapOf("a" to origin, "b" to other), built = built).load(person, "a")

            assertTrue(origin.searched.isEmpty())
            assertEquals(listOf("origin-person"), origin.mediaRequested)
            assertEquals(listOf("Zoë Kravitz"), other.searched)
            assertEquals(listOf("b-person"), other.mediaRequested)
            assertEquals(listOf("a:o1", "b:b1"), load.results.map { it.id })
            assertEquals("Server b", load.results.last().serverName)
            assertEquals(0, load.failedServerCount)
            // One client per server, each built with that server's own token.
            assertEquals(listOf("a" to "token-a", "b" to "token-b"), built)
        }

    @Test
    fun `servers are queried concurrently`() =
        runTest {
            // Each server's media call waits for the other to have started; a
            // sequential fan-out would never get there.
            val aStarted = CompletableDeferred<Unit>()
            val bStarted = CompletableDeferred<Unit>()
            val a =
                FakeClient(media = mapOf("origin-person" to listOf(movie("a1", "A"))), beforeMedia = {
                    aStarted.complete(Unit)
                    bStarted.await()
                })
            val b =
                FakeClient(
                    people = listOf(PersonInfo("pb", "Zoe Kravitz")),
                    media = mapOf("pb" to listOf(movie("b1", "B"))),
                    beforeMedia = {
                        bStarted.complete(Unit)
                        aStarted.await()
                    },
                )
            val load =
                withTimeout(5_000) {
                    service(listOf(server("a"), server("b")), mapOf("a" to a, "b" to b)).load(person, "a")
                }
            assertEquals(2, load.results.size)
        }

    @Test
    fun `a server without a token is skipped, not failed`() =
        runTest {
            val a = FakeClient(media = mapOf("origin-person" to listOf(movie("a1", "A"))))
            val load =
                service(
                    listOf(server("a"), server("b")),
                    mapOf("a" to a),
                    tokens = mapOf("a" to "t", "b" to null),
                ).load(person, "a")
            assertEquals(1, load.attemptedServerCount)
            assertEquals(0, load.failedServerCount)
        }

    @Test
    fun `no usable sessions is an error, not an empty filmography`() =
        runTest {
            try {
                service(listOf(server("a")), emptyMap(), tokens = mapOf("a" to null)).load(person, "a")
                fail("expected NoServerSessions")
            } catch (e: PersonFilmographyError.NoServerSessions) {
                // expected
            }
        }

    @Test
    fun `one failing server degrades to a partial result with a failure count`() =
        runTest {
            val a = FakeClient(media = mapOf("origin-person" to listOf(movie("a1", "A"))))
            val b = FakeClient(failWith = JellyfinError.SessionExpired)
            val load = service(listOf(server("a"), server("b")), mapOf("a" to a, "b" to b)).load(person, "a")
            assertEquals(listOf("a:a1"), load.results.map { it.id })
            assertEquals(1, load.failedServerCount)
        }

    @Test
    fun `every server failing is an error`() =
        runTest {
            val a = FakeClient(failWith = JellyfinError.NetworkError(RuntimeException("down")))
            try {
                service(listOf(server("a")), mapOf("a" to a)).load(person, "a")
                fail("expected AllServersFailed")
            } catch (e: PersonFilmographyError.AllServersFailed) {
                // expected
            }
        }

    @Test
    fun `a hung server times out as a failure instead of holding the screen`() =
        runTest {
            val a = FakeClient(media = mapOf("origin-person" to listOf(movie("a1", "A"))))
            val hung = FakeClient(beforeMedia = { awaitCancellation() }, people = listOf(PersonInfo("p", "Zoe Kravitz")))
            val load = service(listOf(server("a"), server("b")), mapOf("a" to a, "b" to hung)).load(person, "a")
            assertEquals(1, load.failedServerCount)
            assertEquals(listOf("a:a1"), load.results.map { it.id })
        }

    @Test
    fun `a person missing from a server is a successful empty answer`() =
        runTest {
            val a = FakeClient(media = mapOf("origin-person" to listOf(movie("a1", "A"))))
            val b = FakeClient(people = listOf(PersonInfo("x", "Someone Else")))
            val load = service(listOf(server("a"), server("b")), mapOf("a" to a, "b" to b)).load(person, "a")
            assertEquals(0, load.failedServerCount)
            assertTrue(b.mediaRequested.isEmpty())
        }

    @Test
    fun `resolvePersonId never matches on a blank key`() {
        assertNull(PersonFilmographyService.resolvePersonId(listOf(PersonInfo("x", "...")), PersonInfo("p", "!!!")))
        assertEquals("y", PersonFilmographyService.resolvePersonId(listOf(PersonInfo("y", "ZOE KRAVITZ")), person))
    }
}
