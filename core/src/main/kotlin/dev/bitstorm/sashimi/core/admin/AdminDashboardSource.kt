package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One poll's outcome. [Forbidden] is terminal: the account is not an admin. */
sealed interface AdminLoad<out T> {
    data class Ready<T>(val data: T) : AdminLoad<T>

    data class Failed(val message: String) : AdminLoad<Nothing>

    data object Forbidden : AdminLoad<Nothing>
}

/** Everything the slow poll reads: header, activity and health. */
data class HealthSnapshot(
    val info: SystemInfoDto,
    val tasks: List<ScheduledTaskDto>,
    val activity: List<ActivityLogEntryDto>,
    val storage: List<StorageRow>,
    val encoding: EncodingOptionsDto?,
)

/**
 * Polls the admin endpoints. The flows are cold and poll only while collected,
 * so a screen that stops collecting (gone, or app in the background) stops the
 * requests: no polling the user can't see.
 */
class AdminDashboardSource(
    private val client: JellyfinClient,
    private val sessionsEveryMs: Long = SESSIONS_EVERY_MS,
    private val healthEveryMs: Long = HEALTH_EVERY_MS,
    private val activityLimit: Int = ACTIVITY_LIMIT,
) {
    fun sessions(): Flow<AdminLoad<List<SessionInfoDto>>> = poll(sessionsEveryMs) { client.getAdminSessions() }

    fun health(): Flow<AdminLoad<HealthSnapshot>> =
        poll(healthEveryMs) {
            HealthSnapshot(
                info = client.getSystemInfo(),
                tasks = client.getScheduledTasks().filterNot { it.isHidden },
                activity = client.getActivityLog(activityLimit),
                storage = StorageHealth.rows(client.getStorage()),
                encoding = optional { client.getEncodingOptions() },
            )
        }

    private fun <T> poll(
        everyMs: Long,
        read: suspend () -> T,
    ): Flow<AdminLoad<T>> =
        flow {
            while (true) {
                val load =
                    try {
                        AdminLoad.Ready(read())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: JellyfinError.Forbidden) {
                        AdminLoad.Forbidden
                    } catch (e: JellyfinError) {
                        AdminLoad.Failed(e.userMessage)
                    } catch (e: Exception) {
                        AdminLoad.Failed("Could not reach the server.")
                    }
                emit(load)
                if (load is AdminLoad.Forbidden) return@flow
                delay(everyMs)
            }
        }

    /** A part the dashboard can do without; a 403 still closes the screen. */
    private suspend fun <T> optional(read: suspend () -> T): T? =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JellyfinError.Forbidden) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        const val SESSIONS_EVERY_MS = 5_000L
        const val HEALTH_EVERY_MS = 30_000L
        const val ACTIVITY_LIMIT = 20
    }
}
