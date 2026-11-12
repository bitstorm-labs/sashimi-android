package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.playback.VideoViewMode
import dev.bitstorm.sashimi.core.shuffle.TvShuffleMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncSchemaTest {
    private fun valid(
        key: String,
        json: String,
    ) = SettingsSyncSchema.isValid(key, Json.parseToJsonElement(json))

    @Test
    fun `an entry round-trips through its CustomPrefs string`() {
        val value = StampedValue(Json.parseToJsonElement("""[{"id":"hero","on":true}]"""), 1_762_963_200_000)
        val raw = StampedValueCodec.encode(value)

        assertEquals("""{"v":[{"id":"hero","on":true}],"t":1762963200000}""", raw)
        assertEquals(value, StampedValueCodec.decode(JsonPrimitive(raw)))
    }

    @Test
    fun `entries that are not a string holding v and an integer t do not decode`() {
        assertNull(StampedValueCodec.decode(JsonPrimitive("{not json")))
        assertNull(StampedValueCodec.decode(JsonPrimitive("""{"v":true}""")))
        assertNull(StampedValueCodec.decode(JsonPrimitive("""{"v":true,"t":"200"}""")))
        assertNull(StampedValueCodec.decode(JsonPrimitive("""[true,200]""")))
        assertNull(StampedValueCodec.decode(Json.parseToJsonElement("""{"v":true,"t":200}""")))
        assertNull(StampedValueCodec.decode(null))
    }

    @Test
    fun `values are checked against each key's type and options`() {
        assertTrue(valid("autoSkipIntro", "true"))
        assertFalse(valid("autoSkipIntro", "\"true\""))
        assertFalse(valid("autoSkipIntro", "1"))
        assertTrue(valid("resumeThresholdSeconds", "600"))
        assertTrue(valid("resumeThresholdSeconds", "0"))
        assertFalse(valid("resumeThresholdSeconds", "45"))
        assertFalse(valid("resumeThresholdSeconds", "\"30\""))
        assertFalse(valid("resumeThresholdSeconds", "30.5"))
        assertTrue(valid("preferredAudioLanguage", "\"\""))
        assertTrue(valid("preferredSubtitleLanguage", "\"fi\""))
        assertFalse(valid("preferredSubtitleLanguage", "\"xx\""))
        assertTrue(valid("tvShuffleMode", "\"randomShowNextEpisode\""))
        assertFalse(valid("tvShuffleMode", "\"shuffleAll\""))
        assertTrue(valid("defaultViewMode", "\"zoom\""))
        assertFalse(valid("defaultViewMode", "\"fill\""))
        assertTrue(valid("sideMenu", "[{\"id\":\"search\",\"menu\":false},{\"id\":\"hero\"}]"))
        assertFalse(valid("sideMenu", "[{\"id\":\"search\",\"menu\":\"false\"}]"))
        assertFalse(valid("maxBitrate", "0"))
    }

    @Test
    fun `option lists are the shared ones`() {
        assertEquals(listOf(0, 10, 30, 60, 120, 300, 600), SettingsSyncSchema.RESUME_THRESHOLDS)
        assertEquals(18, SettingsSyncSchema.LANGUAGES.size)
        assertEquals(TvShuffleMode.entries.map { it.key }, SettingsSyncSchema.TV_SHUFFLE_MODES)
        assertEquals(VideoViewMode.entries.map { it.key }, SettingsSyncSchema.VIEW_MODES)
    }
}
