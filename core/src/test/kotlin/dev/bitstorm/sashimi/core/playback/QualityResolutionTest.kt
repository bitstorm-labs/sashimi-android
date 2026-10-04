package dev.bitstorm.sashimi.core.playback

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shape of the resolution cap, because this is a server-contract
 * fix rather than a local behaviour change: the tiers were bitrate-only, so
 * picking "720p" delivered 1080p at a lower bitrate. Jellyfin sizes a transcode
 * from a Video CodecProfile Width condition and from nothing else.
 */
class QualityResolutionTest {
    private fun profile(maxWidth: Int?) = DeviceProfileBuilder(FixedCodecCapabilities(emptySet())).build(20_000_000, maxWidth)

    @Test
    fun `every non-auto tier carries a width, auto carries none`() {
        assertEquals(null, QualityOption.AUTO.maxWidth)
        assertEquals(1920, QualityOption.P1080.maxWidth)
        assertEquals(1280, QualityOption.P720.maxWidth)
        assertEquals(854, QualityOption.P480.maxWidth)
    }

    @Test
    fun `a tier's label matches the width actually requested`() {
        // The whole defect was a label that did not match what was sent.
        QualityOption.entries.filter { it != QualityOption.AUTO }.forEach { option ->
            val declared = option.label.removeSuffix("p").toInt()
            val expectedWidth =
                when (declared) {
                    1080 -> 1920
                    720 -> 1280
                    480 -> 854
                    else -> 640
                }
            assertEquals("${option.label} must request width $expectedWidth", expectedWidth, option.maxWidth)
        }
    }

    // Every codec now also carries its own capability profile (Codec set);
    // the tier cap is the one profile that applies to all codecs.
    private fun tierProfiles(maxWidth: Int?) = profile(maxWidth).codecProfiles.filter { it.codec == null }

    @Test
    fun `auto sends no tier width profile`() {
        assertTrue(tierProfiles(null).isEmpty())
    }

    @Test
    fun `a capped tier sends one video width condition`() {
        val profiles = tierProfiles(1280)
        assertEquals(1, profiles.size)
        assertEquals("Video", profiles[0].type)
        assertEquals(1, profiles[0].conditions.size)
        val condition = profiles[0].conditions[0]
        assertEquals("LessThanEqual", condition.condition)
        assertEquals("Width", condition.property)
        assertEquals("1280", condition.value)
    }

    @Test
    fun `height is left unconstrained so non-16-9 sources are not letterboxed`() {
        val conditions = profile(1280).codecProfiles.flatMap { it.conditions }
        assertTrue(conditions.none { it.property == "Height" })
    }

    @Test
    fun `the condition is advisory, so the server downscales rather than refusing the item`() {
        assertEquals(false, profile(1280).codecProfiles[0].conditions[0].isRequired)
    }

    @Test
    fun `serialises to the property names Jellyfin expects`() {
        val json = Json.encodeToString(DeviceProfile.serializer(), profile(1280))
        assertTrue("CodecProfiles missing: $json", json.contains("\"CodecProfiles\""))
        assertTrue("Condition missing: $json", json.contains("\"Condition\":\"LessThanEqual\""))
        assertTrue("Property missing: $json", json.contains("\"Property\":\"Width\""))
        assertTrue("Value must be a string: $json", json.contains("\"Value\":\"1280\""))
    }

    @Test
    fun `auto omits CodecProfiles from the payload entirely`() {
        val json = Json.encodeToString(DeviceProfile.serializer(), profile(null))
        assertTrue("Auto must not send a width cap: $json", !json.contains("\"Width\""))
    }
}
