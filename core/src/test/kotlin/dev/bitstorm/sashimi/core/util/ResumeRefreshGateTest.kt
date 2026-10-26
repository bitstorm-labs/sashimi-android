package dev.bitstorm.sashimi.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeRefreshGateTest {
    @Test
    fun `the first resume skips, every later one refreshes`() {
        val gate = ResumeRefreshGate()
        assertEquals(listOf(false, true, true), List(3) { gate.onResume() })
    }
}
