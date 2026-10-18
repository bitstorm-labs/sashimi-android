package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for the admin dashboard. Every field below was checked against a
// live Jellyfin 12.2 server (see the plan in plans/sashimi-android). Fields the
// server does not send (buffering, for one) are deliberately absent.

/** `GET /Users/Me`: only the admin flag is read. */
@Serializable
data class CurrentUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Policy") val policy: UserPolicyDto? = null,
)

@Serializable
data class UserPolicyDto(
    @SerialName("IsAdministrator") val isAdministrator: Boolean? = null,
)

/** One entry of `GET /Sessions`. */
@Serializable
data class SessionInfoDto(
    @SerialName("Id") val id: String,
    @SerialName("UserName") val userName: String? = null,
    @SerialName("Client") val client: String? = null,
    @SerialName("DeviceName") val deviceName: String? = null,
    @SerialName("SupportsRemoteControl") val supportsRemoteControl: Boolean = false,
    @SerialName("NowPlayingItem") val nowPlayingItem: BaseItemDto? = null,
    @SerialName("PlayState") val playState: PlayStateDto? = null,
    @SerialName("TranscodingInfo") val transcodingInfo: TranscodingInfoDto? = null,
)

@Serializable
data class PlayStateDto(
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
)

@Serializable
data class TranscodingInfoDto(
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("IsVideoDirect") val isVideoDirect: Boolean? = null,
    @SerialName("IsAudioDirect") val isAudioDirect: Boolean? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("AudioChannels") val audioChannels: Int? = null,
    @SerialName("HardwareAccelerationType") val hardwareAccelerationType: String? = null,
    @SerialName("TranscodeReasons") val transcodeReasons: List<String>? = null,
)

/** `GET /System/ActivityLog/Entries`. */
@Serializable
data class ActivityLogResult(
    @SerialName("Items") val items: List<ActivityLogEntryDto> = emptyList(),
)

@Serializable
data class ActivityLogEntryDto(
    @SerialName("Id") val id: Long = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String? = null,
    @SerialName("Severity") val severity: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("ShortOverview") val shortOverview: String? = null,
    @SerialName("Overview") val overview: String? = null,
)

/** `GET /ScheduledTasks`. */
@Serializable
data class ScheduledTaskDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Key") val key: String? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("IsHidden") val isHidden: Boolean = false,
    @SerialName("LastExecutionResult") val lastExecutionResult: TaskResultDto? = null,
)

@Serializable
data class TaskResultDto(
    @SerialName("Status") val status: String? = null,
    @SerialName("EndTimeUtc") val endTimeUtc: String? = null,
)

/** `GET /System/Info`: the header line. */
@Serializable
data class SystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

/** `GET /System/Configuration/encoding`. */
@Serializable
data class EncodingOptionsDto(
    @SerialName("HardwareAccelerationType") val hardwareAccelerationType: String? = null,
)

/** `GET /System/Info/Storage`. */
@Serializable
data class SystemStorageDto(
    @SerialName("CacheFolder") val cacheFolder: FolderStorageDto? = null,
    @SerialName("TranscodingTempFolder") val transcodingTempFolder: FolderStorageDto? = null,
    @SerialName("Libraries") val libraries: List<LibraryStorageDto> = emptyList(),
)

@Serializable
data class LibraryStorageDto(
    @SerialName("Name") val name: String = "",
    @SerialName("Folders") val folders: List<FolderStorageDto> = emptyList(),
)

@Serializable
data class FolderStorageDto(
    @SerialName("FreeSpace") val freeSpace: Long = 0,
    @SerialName("UsedSpace") val usedSpace: Long = 0,
    @SerialName("DeviceId") val deviceId: String? = null,
)

/** Body of `POST /Sessions/{id}/Message`. */
@Serializable
data class SessionMessage(
    @SerialName("Header") val header: String,
    @SerialName("Text") val text: String,
    @SerialName("TimeoutMs") val timeoutMs: Long,
)
