package dev.bitstorm.sashimi.core.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkResolverTest {
    @Test
    fun `play link resolves to Play target`() {
        assertEquals(DeepLinkTarget.Play("abc123"), DeepLinkResolver.resolve("sashimi://play/abc123"))
    }

    @Test
    fun `item link resolves to Detail target`() {
        assertEquals(DeepLinkTarget.Detail("xyz"), DeepLinkResolver.resolve("sashimi://item/xyz"))
    }

    @Test
    fun `query and fragment are stripped`() {
        assertEquals(DeepLinkTarget.Detail("id7"), DeepLinkResolver.resolve("sashimi://item/id7?foo=bar#frag"))
    }

    @Test
    fun `scheme without double slash still parses`() {
        assertEquals(DeepLinkTarget.Play("id9"), DeepLinkResolver.resolve("sashimi:play/id9"))
    }

    @Test
    fun `unknown host returns null`() {
        assertNull(DeepLinkResolver.resolve("sashimi://library/id"))
    }

    @Test
    fun `missing id returns null`() {
        assertNull(DeepLinkResolver.resolve("sashimi://play"))
        assertNull(DeepLinkResolver.resolve("sashimi://play/"))
    }

    @Test
    fun `foreign scheme and blank input return null`() {
        assertNull(DeepLinkResolver.resolve("https://example.com/item/1"))
        assertNull(DeepLinkResolver.resolve(""))
        assertNull(DeepLinkResolver.resolve(null))
    }
}
