package dev.bitstorm.sashimi.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Port of Shared/Models/JellyfinModels.swift. Jellyfin returns PascalCase JSON;
// every field is mapped with @SerialName. The Json instance in :core is
// configured with ignoreUnknownKeys = true so partial Field lists decode.

/**
 * Cleans up YouTube channel titles by removing the common " - Videos" suffix.
 * Ported from the Swift `String.cleanedYouTubeTitle` extension.
 */
fun String.cleanedYouTubeTitle(): String = if (endsWith(" - Videos")) dropLast(9) else this

@Serializable
data class AuthenticationResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class MediaUrl(
    @SerialName("Name") val name: String? = null,
    @SerialName("Url") val url: String? = null,
)

/**
 * Media item type. Unknown raw values decode to [UNKNOWN] rather than throwing —
 * mirrors the Swift custom `init(from:)` that defaults to `.unknown`. A custom
 * serializer (rather than @Serializable enum) is required because the default
 * enum serializer throws on unrecognized wire values.
 */
@Serializable(with = ItemTypeSerializer::class)
enum class ItemType(val wireName: String) {
    MOVIE("Movie"),
    SERIES("Series"),
    SEASON("Season"),
    EPISODE("Episode"),
    VIDEO("Video"),
    BOX_SET("BoxSet"),
    FOLDER("Folder"),
    COLLECTION_FOLDER("CollectionFolder"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        fun fromWire(value: String): ItemType = entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

object ItemTypeSerializer : KSerializer<ItemType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ItemType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ItemType = ItemType.fromWire(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ItemType,
    ) = encoder.encodeString(value.wireName)
}

@Serializable
data class UserItemDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("IsDefault") val isDefault: Boolean? = null,
    @SerialName("IsExternal") val isExternal: Boolean? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
)

@Serializable
data class PersonInfo(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class ChapterInfo(
    @SerialName("StartPositionTicks") val startPositionTicks: Long,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
) {
    val startSeconds: Double
        get() = startPositionTicks / 10_000_000.0
}

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: ItemType? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: UserItemDataDto? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String>? = null,
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Taglines") val taglines: List<String>? = null,
    @SerialName("People") val people: List<PersonInfo>? = null,
    @SerialName("CriticRating") val criticRating: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("Chapters") val chapters: List<ChapterInfo>? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("RemoteTrailers") val remoteTrailers: List<MediaUrl>? = null,
    // Present when the query requests Fields=LocalTrailerCount. Drives the
    // local-first Trailer button (LocalTrailerCount > 0 → play inline).
    @SerialName("LocalTrailerCount") val localTrailerCount: Int? = null,
    // Present only when the item query requests Fields=MediaStreams.
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null,
) {
    /**
     * Resolution chip for cover art: "4K", "HD", or "SD"; null when the item has
     * no video stream (series/season) or dimensions are unknown.
     */
    val qualityBadge: String?
        get() {
            val stream = mediaStreams?.firstOrNull { it.type == "Video" } ?: return null
            val width = stream.width ?: 0
            val height = stream.height ?: 0
            if (width <= 0 && height <= 0) return null
            return when {
                width >= 3200 || height >= 2160 -> "4K"
                width >= 1280 || height >= 720 -> "HD"
                else -> "SD"
            }
        }

    /** Series + SxEy title for episodes; the plain name otherwise. */
    val displayTitle: String
        get() {
            if (type == ItemType.EPISODE && seriesName != null) {
                val parts =
                    buildList {
                        parentIndexNumber?.let { add("S$it") }
                        indexNumber?.let { add("E$it") }
                    }
                return "$seriesName ${parts.joinToString("")}".trim()
            }
            return name
        }

    /** Playback progress as a fraction 0.0–1.0. */
    val progressPercent: Double
        get() {
            val playbackTicks = userData?.playbackPositionTicks ?: return 0.0
            val totalTicks = runTimeTicks ?: return 0.0
            if (totalTicks <= 0) return 0.0
            return playbackTicks.toDouble() / totalTicks.toDouble()
        }
}

@Serializable
data class ItemsResponse(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class JellyfinLibrary(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
)

@Serializable
data class LibraryViewsResponse(
    @SerialName("Items") val items: List<JellyfinLibrary> = emptyList(),
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String,
    @SerialName("Path") val path: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean? = null,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
) {
    val videoCodec: String?
        get() = mediaStreams?.firstOrNull { it.type == "Video" }?.codec

    val videoResolution: String?
        get() {
            val stream = mediaStreams?.firstOrNull { it.type == "Video" } ?: return null
            val width = stream.width ?: 0
            val height = stream.height ?: 0
            if (width <= 0 && height <= 0) return null
            // Classify by width first: wide-aspect releases have sub-2160 heights
            // and would otherwise mislabel as 1080p.
            return when {
                width >= 3200 || height >= 2160 -> "4K"
                width >= 1800 || height >= 1080 -> "1080p"
                width >= 1200 || height >= 720 -> "720p"
                height > 0 -> "${height}p"
                else -> "SD"
            }
        }

    val audioStreams: List<MediaStream>
        get() = mediaStreams?.filter { it.type == "Audio" } ?: emptyList()

    val subtitleStreams: List<MediaStream>
        get() = mediaStreams?.filter { it.type == "Subtitle" } ?: emptyList()
}

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo>? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

/** Unauthenticated server info — used to label saved servers. */
@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
)

// MARK: - Media Segments (skip intro/credits via the intro-skipper plugin)

enum class MediaSegmentType(val displayName: String) {
    INTRO("Intro"),
    OUTRO("Credits"),
    PREVIEW("Preview"),
    RECAP("Recap"),
    UNKNOWN("Segment"),
    ;

    companion object {
        /** Maps the plugin's PascalCase keys ("Introduction", "Credits", …). */
        fun fromKey(key: String): MediaSegmentType =
            when (key) {
                "Introduction" -> INTRO
                "Credits" -> OUTRO
                "Preview" -> PREVIEW
                "Recap" -> RECAP
                else -> UNKNOWN
            }
    }
}

data class MediaSegmentDto(
    val id: String,
    val type: MediaSegmentType,
    val startSeconds: Double,
    val endSeconds: Double,
)

/** Intro-skipper plugin response entry: {"Start": 0, "End": 90}. */
@Serializable
data class IntroSkipperSegment(
    @SerialName("Start") val start: Double,
    @SerialName("End") val end: Double,
)
