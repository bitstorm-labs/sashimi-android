package dev.bitstorm.sashimi.core.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AdminGateTest {
    private fun user(admin: Boolean?) = CurrentUserDto(policy = admin?.let { UserPolicyDto(isAdministrator = it) })

    @Test
    fun `only an explicit IsAdministrator true opens the gate`() {
        assertTrue(AdminGate.isAdministrator(user(true)))
        assertFalse(AdminGate.isAdministrator(user(false)))
        assertFalse(AdminGate.isAdministrator(user(null)))
        assertFalse(AdminGate.isAdministrator(CurrentUserDto(policy = UserPolicyDto(null))))
        assertFalse(AdminGate.isAdministrator(null))
    }

    @Test
    fun `refresh opens for an admin and closes for a non-admin`() =
        runTest {
            val gate = AdminGate()
            assertFalse(gate.isAdmin.value)
            gate.refresh { user(true) }
            assertTrue(gate.isAdmin.value)
            gate.refresh { user(false) }
            assertFalse(gate.isAdmin.value)
        }

    @Test
    fun `a failed read leaves the gate closed and marks it for retry`() =
        runTest {
            val gate = AdminGate()
            gate.refresh { user(true) }
            gate.refresh { throw IOException("offline") }
            assertFalse(gate.isAdmin.value)
            assertTrue(gate.needsRefresh)
            gate.refresh { user(true) }
            assertFalse(gate.needsRefresh)
        }

    @Test
    fun `forbidden and sign-out close the gate`() =
        runTest {
            val gate = AdminGate()
            gate.refresh { user(true) }
            gate.forbidden()
            assertFalse(gate.isAdmin.value)
            gate.refresh { user(true) }
            gate.signedOut()
            assertFalse(gate.isAdmin.value)
        }

    @Test
    fun `a stale read finishing after a sign-out cannot reopen the gate`() =
        runTest {
            val gate = AdminGate()
            val answer = CompletableDeferred<CurrentUserDto>()
            val job = launch { gate.refresh { answer.await() } }
            testScheduler.runCurrent()
            gate.signedOut()
            answer.complete(user(true))
            job.join()
            assertFalse(gate.isAdmin.value)
        }
}
