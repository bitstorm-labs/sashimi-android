package dev.bitstorm.sashimi.core.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks every enum-valued string in the PlaybackInfo body against the SERVER's
 * vocabulary, not against what the client happens to emit.
 *
 * Jellyfin binds these fields to C# enums through a string enum converter. A
 * value that is not a member makes the converter throw, and the whole POST
 * returns 400. That is how `"Condition":"LessThanOrEqual"` broke every non-Auto
 * quality pick while a test asserting that same string passed.
 *
 * The sets below are copied from jellyfin/jellyfin (master, 2026-10):
 * MediaBrowser.Model/Dlna/{ProfileConditionType,ProfileConditionValue,
 * DlnaProfileType,CodecType,SubtitleDeliveryMethod,EncodingContext}.cs and
 * Jellyfin.Data/Enums/MediaStreamProtocol.cs.
 */
class DeviceProfileVocabularyTest {
    private val conditionTypes = setOf("Equals", "NotEquals", "LessThanEqual", "GreaterThanEqual", "EqualsAny")
    private val conditionValues =
        setOf(
            "AudioChannels", "AudioBitrate", "AudioProfile", "Width", "Height", "Has64BitOffsets", "PacketLength",
            "VideoBitDepth", "VideoBitrate", "VideoFramerate", "VideoLevel", "VideoProfile", "VideoTimestamp",
            "IsAnamorphic", "RefFrames", "NumAudioStreams", "NumVideoStreams", "IsSecondaryAudio", "VideoCodecTag",
            "IsAvc", "IsInterlaced", "AudioSampleRate", "AudioBitDepth", "VideoRangeType", "NumStreams", "VideoRotation",
        )
    private val dlnaProfileTypes = setOf("Audio", "Video", "Photo", "Subtitle", "Lyric")
    private val codecTypes = setOf("Video", "VideoAudio", "Audio")
    private val subtitleMethods = setOf("Encode", "Embed", "External", "Hls", "Drop")
    private val encodingContexts = setOf("Streaming", "Static")
    private val protocols = setOf("http", "hls")

    // encodeDefaults, as JellyfinClient's playbackJson has: the defaulted
    // "Type"/"Context" fields are on the wire and so must be checked too.
    private val wire = Json { encodeDefaults = true }

    private fun body(maxWidth: Int?): JsonObject {
        val profile =
            DeviceProfileBuilder(
                FixedCodecCapabilities(
                    setOf(CodecCapabilities.MimeTypes.HEVC, CodecCapabilities.MimeTypes.VP9, CodecCapabilities.MimeTypes.AV1),
                ),
            ).build(20_000_000, maxWidth)
        return wire.encodeToJsonElement(DeviceProfile.serializer(), profile).jsonObject
    }

    private fun JsonObject.strings(
        array: String,
        field: String,
    ): List<String> = getValue(array).jsonArray.map { it.jsonObject.getValue(field).jsonPrimitive.content }

    private fun assertAllIn(
        what: String,
        values: List<String>,
        allowed: Set<String>,
    ) {
        values.forEach { assertTrue("$what \"$it\" is not a Jellyfin enum member $allowed", it in allowed) }
    }

    @Test
    fun `a capped tier's condition type and property are server enum members`() {
        listOf(1920, 1280, 854).forEach { width ->
            val conditions =
                body(width).getValue("CodecProfiles").jsonArray.flatMap { it.jsonObject.getValue("Conditions").jsonArray }
            assertTrue("a capped tier must send a condition", conditions.isNotEmpty())
            assertAllIn("Condition", conditions.map { it.jsonObject.getValue("Condition").jsonPrimitive.content }, conditionTypes)
            assertAllIn("Property", conditions.map { it.jsonObject.getValue("Property").jsonPrimitive.content }, conditionValues)
        }
    }

    @Test
    fun `profile types, subtitle methods, context and protocol are server enum members`() {
        listOf(null, 1280).forEach { width ->
            val json = body(width)
            assertAllIn("CodecProfile Type", json.strings("CodecProfiles", "Type"), codecTypes)
            assertAllIn("DirectPlayProfile Type", json.strings("DirectPlayProfiles", "Type"), dlnaProfileTypes)
            assertAllIn("TranscodingProfile Type", json.strings("TranscodingProfiles", "Type"), dlnaProfileTypes)
            assertAllIn("TranscodingProfile Context", json.strings("TranscodingProfiles", "Context"), encodingContexts)
            assertAllIn("TranscodingProfile Protocol", json.strings("TranscodingProfiles", "Protocol"), protocols)
            assertAllIn("SubtitleProfile Method", json.strings("SubtitleProfiles", "Method"), subtitleMethods)
        }
    }
}
