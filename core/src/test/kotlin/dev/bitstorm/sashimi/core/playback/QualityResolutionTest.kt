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
                if (declared == 1080) {
                    1920
                } else if (declared == 720) {
                    1280
                } else {
                    854
                }
            assertEquals("${option.label} must request width $expectedWidth", expectedWidth, option.maxWidth)
        }
    }

    @Test
    fun `auto sends no codec profile at all`() {
        assertTrue(profile(null).codecProfiles.isEmpty())
    }

    @Test
    fun `a capped tier sends one video width condition`() {
        val profiles = profile(1280).codecProfiles
        assertEquals(1, profiles.size)
        assertEquals("Video", profiles[0].type)
        assertEquals(1, profiles[0].conditions.size)
        val condition = profiles[0].conditions[0]
        assertEquals("LessThanOrEqual", condition.condition)
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
        assertTrue("Condition missing: $json", json.contains("\"Condition\":\"LessThanOrEqual\""))
        assertTrue("Property missing: $json", json.contains("\"Property\":\"Width\""))
        assertTrue("Value must be a string: $json", json.contains("\"Value\":\"1280\""))
    }

    @Test
    fun `auto omits CodecProfiles from the payload entirely`() {
        val json = Json.encodeToString(DeviceProfile.serializer(), profile(null))
        assertTrue("Auto must not send a width cap: $json", !json.contains("\"Width\""))
    }
}
