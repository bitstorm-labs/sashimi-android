package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sub(
    index: Int,
    language: String? = null,
    isDefault: Boolean = false,
) = MediaStream(type = "Subtitle", language = language, index = index, isDefault = isDefault)

private fun audio(
    index: Int,
    language: String? = null,
) = MediaStream(type = "Audio", language = language, index = index)

class LanguageMatcherTest {
    @Test
    fun `normalize maps alpha3 to alpha2`() {
        assertEquals("en", LanguageMatcher.normalize("eng"))
        assertEquals("en", LanguageMatcher.normalize("EN"))
        assertEquals("fr", LanguageMatcher.normalize("fre"))
        assertEquals("fr", LanguageMatcher.normalize("fra"))
        assertEquals("xx", LanguageMatcher.normalize("xx")) // unknown passes through
        assertNull(LanguageMatcher.normalize(null))
        assertNull(LanguageMatcher.normalize(""))
    }

    @Test
    fun `matches is tolerant across 639-1 and 639-2`() {
        assertTrue(LanguageMatcher.matches("eng", "en"))
        assertTrue(LanguageMatcher.matches("EN", "eng"))
        assertFalse(LanguageMatcher.matches("en", "spa"))
        assertFalse(LanguageMatcher.matches(null, "en"))
    }

    @Test
    fun `preferred subtitle disabled returns null`() {
        val streams = listOf(sub(0, "eng"))
        assertNull(LanguageMatcher.preferredSubtitleIndex(streams, "en", subtitlesEnabled = false))
    }

    @Test
    fun `preferred subtitle matches language first`() {
        val streams = listOf(sub(0, "spa"), sub(1, "eng"), sub(2, "fre"))
        assertEquals(1, LanguageMatcher.preferredSubtitleIndex(streams, "en", subtitlesEnabled = true))
    }

    @Test
    fun `preferred subtitle falls back to default then first`() {
        val withDefault = listOf(sub(0, "spa"), sub(1, "fre", isDefault = true))
        assertEquals(1, LanguageMatcher.preferredSubtitleIndex(withDefault, "en", subtitlesEnabled = true))
        val noDefault = listOf(sub(3, "spa"), sub(4, "fre"))
        assertEquals(3, LanguageMatcher.preferredSubtitleIndex(noDefault, "en", subtitlesEnabled = true))
    }

    @Test
    fun `preferred audio index matches or null`() {
        val streams = listOf(audio(0, "eng"), audio(1, "jpn"))
        assertEquals(1, LanguageMatcher.preferredAudioIndex(streams, "ja"))
        assertNull(LanguageMatcher.preferredAudioIndex(streams, "de"))
        assertNull(LanguageMatcher.preferredAudioIndex(streams, "")) // no preference
    }
}
