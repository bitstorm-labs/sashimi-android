package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.PersonInfo
import dev.bitstorm.sashimi.core.session.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** The two read-only calls a filmography needs. JellyfinClient implements it. */
interface PersonFilmographyClient {
    /** People on this server whose name matches [name] (Jellyfin `/Persons?SearchTerm=`). */
    suspend fun searchPeople(
        name: String,
        limit: Int = 20,
    ): List<PersonInfo>

    /** Every Movie and Series crediting [personId], across all pages. */
    suspend fun getPersonMedia(
        personId: String,
        pageSize: Int = 100,
    ): List<BaseItemDto>
}

sealed class PersonFilmographyError(message: String) : Exception(message) {
    /** No saved server has a token, so nothing was even attempted. */
    object NoServerSessions : PersonFilmographyError("No signed-in server is available.")

    /** Every attempted server failed. Distinct from "succeeded with no titles". */
    object AllServersFailed : PersonFilmographyError("Couldn't reach any of your servers.")
}

/**
 * Merged, per-server-tagged credits. [failedServerCount] lets the screen say
 * that part of the picture is missing instead of presenting a partial list as
 * the whole catalogue.
 */
data class PersonFilmographyLoad(
    val results: List<ServerMediaResult>,
    val attemptedServerCount: Int,
    val failedServerCount: Int,
)

/**
 * Fans a person's filmography out across every saved server with a usable token,
 * concurrently, one client instance per server. Port of sashimi-apple
 * `MultiServerPeopleService`.
 *
 * Never repoints a shared client: [clientFor] must hand back a client bound to
 * that one server. Person ids are server-scoped, so only the originating server
 * can use [PersonInfo.id]; every other server resolves the person by
 * [matchingNameKey].
 */
class PersonFilmographyService(
    private val servers: () -> List<ServerConfig>,
    private val tokenFor: (ServerConfig) -> String?,
    private val clientFor: (ServerConfig, String) -> PersonFilmographyClient,
    /**
     * Per-server budget. The client retries idempotent GETs with backoff on top
     * of 30s socket timeouts, so one unreachable server could otherwise hold the
     * whole screen on a spinner for minutes while the others have answered.
     */
    private val perServerTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(20),
) {
    suspend fun load(
        person: PersonInfo,
        originServerId: String?,
        pageSize: Int = 100,
    ): PersonFilmographyLoad {
        val requests = servers().mapNotNull { server -> tokenFor(server)?.let { server to it } }
        if (requests.isEmpty()) throw PersonFilmographyError.NoServerSessions

        val outcomes =
            coroutineScope {
                requests.map { (server, token) ->
                    async { loadOne(server, token, person, originServerId, pageSize) }
                }.awaitAll()
            }
        return aggregate(outcomes)
    }

    private suspend fun loadOne(
        server: ServerConfig,
        token: String,
        person: PersonInfo,
        originServerId: String?,
        pageSize: Int,
    ): List<ServerMediaResult>? =
        try {
            withTimeout(perServerTimeoutMillis) {
                val client = clientFor(server, token)
                val personId =
                    if (server.id == originServerId) {
                        person.id
                    } else {
                        resolvePersonId(client.searchPeople(person.name), person)
                    }
                personId?.let { id ->
                    client.getPersonMedia(id, pageSize).map { ServerMediaResult(it, server.id, server.name) }
                } ?: emptyList()
            }
        } catch (e: CancellationException) {
            // withTimeout throws a CancellationException subtype; only our own
            // timeout is a server failure. A cancelled caller must propagate.
            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        /**
         * The candidate whose folded name equals the person's. A blank key (a name
         * that is all punctuation) matches nothing rather than everything.
         */
        fun resolvePersonId(
            candidates: List<PersonInfo>,
            person: PersonInfo,
        ): String? {
            val key = person.matchingNameKey
            if (key.isEmpty()) return null
            return candidates.firstOrNull { it.matchingNameKey == key }?.id
        }

        /** null outcome = that server failed. Results keep saved-server order. */
        fun aggregate(outcomes: List<List<ServerMediaResult>?>): PersonFilmographyLoad {
            if (outcomes.isEmpty()) throw PersonFilmographyError.NoServerSessions
            if (outcomes.all { it == null }) throw PersonFilmographyError.AllServersFailed
            return PersonFilmographyLoad(
                results = outcomes.filterNotNull().flatten(),
                attemptedServerCount = outcomes.size,
                failedServerCount = outcomes.count { it == null },
            )
        }
    }
}
