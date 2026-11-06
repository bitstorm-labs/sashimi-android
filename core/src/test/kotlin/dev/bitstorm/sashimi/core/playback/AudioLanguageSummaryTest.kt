package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun audio(
    index: Int,
    language: String? = null,
    isDefault: Boolean = false,
) = MediaStream(type = "Audio", language = language, index = index, isDefault = isDefault)

/**
 * The detail page's audio-language chip (#219): the language that will play
 * plus how many other audio languages the item has. Real case: Love is Blind
 * S11E09 defaulted to a Portuguese dub with English as the second track.
 */
class AudioLanguageSummaryTest {
    private val dubbed = listOf(audio(1, "por", isDefault = true), audio(2, "eng"))

    @Test
    fun `preferred language present wins over the default track`() {
        assertEquals(AudioLanguageSummary("eng", 1), AudioLanguageSummary.of(dubbed, preferredLanguage = "en"))
    }

    @Test
    fun `preferred language absent falls back to the default track`() {
        assertEquals(AudioLanguageSummary("por", 1), AudioLanguageSummary.of(dubbed, preferredLanguage = "de"))
    }

    @Test
    fun `no preference uses the default track`() {
        assertEquals(AudioLanguageSummary("por", 1), AudioLanguageSummary.of(dubbed, preferredLanguage = ""))
    }

    @Test
    fun `no default flag uses the first track`() {
        val streams = listOf(audio(1, "fra"), audio(2, "eng"))
        assertEquals(AudioLanguageSummary("fra", 1), AudioLanguageSummary.of(streams, preferredLanguage = ""))
    }

    @Test
    fun `alpha-3 preference matches an alpha-2 tag`() {
        val streams = listOf(audio(1, "pt", isDefault = true), audio(2, "en"))
        assertEquals(AudioLanguageSummary("en", 1), AudioLanguageSummary.of(streams, preferredLanguage = "eng"))
    }

    @Test
    fun `others counts distinct languages, not streams`() {
        val streams =
            listOf(
                audio(1, "eng", isDefault = true),
                audio(2, "eng"), // commentary
                audio(3, "en"), // same language, other code form
                audio(4, "fra"),
                audio(5, "fre"),
                audio(6, "spa"),
                audio(7, null),
            )
        assertEquals(AudioLanguageSummary("eng", 2), AudioLanguageSummary.of(streams, preferredLanguage = ""))
    }

    @Test
    fun `single language has no others`() {
        val streams = listOf(audio(1, "eng", isDefault = true), audio(2, "eng"))
        assertEquals(AudioLanguageSummary("eng", 0), AudioLanguageSummary.of(streams, preferredLanguage = "en"))
    }

    @Test
    fun `no chip when no audio track has a language`() {
        assertNull(AudioLanguageSummary.of(listOf(audio(1), audio(2, "")), preferredLanguage = "en"))
        assertNull(AudioLanguageSummary.of(emptyList(), preferredLanguage = ""))
    }

    @Test
    fun `an untagged track that will play gets no chip`() {
        val streams = listOf(audio(1, null, isDefault = true), audio(2, "jpn"))
        assertNull(AudioLanguageSummary.of(streams, preferredLanguage = ""))
        // ...but a preferred language that IS present still names it.
        assertEquals(AudioLanguageSummary("jpn", 0), AudioLanguageSummary.of(streams, preferredLanguage = "ja"))
    }

    @Test
    fun `label uses the language name and the count`() {
        assertEquals("English  +1", AudioLanguageSummary("eng", 1).label(java.util.Locale.ENGLISH))
        assertEquals("Portuguese", AudioLanguageSummary("por", 0).label(java.util.Locale.ENGLISH))
    }
}
