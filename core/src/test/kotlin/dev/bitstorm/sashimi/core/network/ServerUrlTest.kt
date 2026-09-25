package dev.bitstorm.sashimi.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `prepends https when scheme missing`() {
        assertEquals("https://jellyfin.example.com", normalizeServerUrl("jellyfin.example.com"))
    }

    @Test
    fun `preserves explicit http scheme`() {
        assertEquals("http://192.168.1.5:8096", normalizeServerUrl("http://192.168.1.5:8096"))
    }

    @Test
    fun `preserves explicit https scheme`() {
        assertEquals("https://media.example.com", normalizeServerUrl("https://media.example.com"))
    }

    @Test
    fun `strips a single trailing slash`() {
        assertEquals("https://media.example.com", normalizeServerUrl("https://media.example.com/"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://media.example.com", normalizeServerUrl("  media.example.com  "))
    }

    @Test
    fun `trims and adds scheme and strips slash together`() {
        assertEquals("https://media.example.com", normalizeServerUrl("  media.example.com/  "))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
    }

    @Test
    fun `only plain http servers are unencrypted`() {
        org.junit.Assert.assertTrue(isUnencryptedServerUrl("http://192.168.1.5:8096"))
        org.junit.Assert.assertTrue(isUnencryptedServerUrl(" HTTP://media.local "))
        org.junit.Assert.assertFalse(isUnencryptedServerUrl("https://media.example.com"))
        org.junit.Assert.assertFalse(isUnencryptedServerUrl(null))
    }
}
