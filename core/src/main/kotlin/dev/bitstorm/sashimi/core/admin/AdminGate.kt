package dev.bitstorm.sashimi.core.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the signed-in user may see the admin dashboard. Closed until a read
 * of `/Users/Me` says `Policy.IsAdministrator` is true, and closed again on
 * sign-out, server switch or any 403 from an admin endpoint. The server is the
 * real guard; this only decides whether an entry point exists.
 */
class AdminGate {
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /** True when the last read failed (offline at launch), so a later screen can retry. */
    @Volatile var needsRefresh: Boolean = true
        private set

    @Volatile private var generation = 0

    suspend fun refresh(fetch: suspend () -> CurrentUserDto) {
        val mine = ++generation
        _isAdmin.value = false
        val result =
            try {
                isAdministrator(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        if (mine != generation) return
        needsRefresh = result == null
        _isAdmin.value = result == true
    }

    /** An admin endpoint answered 403: the account is no longer an admin. */
    fun forbidden() = close(needsRefresh = false)

    fun signedOut() = close(needsRefresh = true)

    private fun close(needsRefresh: Boolean) {
        generation++
        this.needsRefresh = needsRefresh
        _isAdmin.value = false
    }

    companion object {
        fun isAdministrator(user: CurrentUserDto?): Boolean = user?.policy?.isAdministrator == true
    }
}
