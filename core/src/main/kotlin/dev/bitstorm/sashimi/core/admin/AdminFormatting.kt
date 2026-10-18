package dev.bitstorm.sashimi.core.admin

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** "now", "5 m", "2 h", "3 d" for a server timestamp; "" when there is none. */
object RelativeTime {
    fun parse(raw: String?): Instant? =
        try {
            raw?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (e: DateTimeParseException) {
            null
        }

    fun short(
        raw: String?,
        now: Instant,
    ): String = parse(raw)?.let { short(it, now) } ?: ""

    fun short(
        at: Instant,
        now: Instant,
    ): String {
        val seconds = Duration.between(at, now).seconds
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60} m"
            seconds < 86_400 -> "${seconds / 3600} h"
            else -> "${seconds / 86_400} d"
        }
    }
}

/** An activity-log entry ready to show: text redacted, failures flagged. */
data class ActivityRow(
    val id: Long,
    val text: String,
    val detail: String?,
    val `when`: String,
    val isFailure: Boolean,
)

object ActivityRows {
    fun isFailure(entry: ActivityLogEntryDto): Boolean =
        entry.type == "AuthenticationFailed" || entry.severity == "Error" || entry.severity == "Critical"

    fun of(
        entries: List<ActivityLogEntryDto>,
        now: Instant,
    ): List<ActivityRow> =
        entries.map { e ->
            ActivityRow(
                id = e.id,
                text = IpRedaction.redact(e.name).orEmpty(),
                detail = IpRedaction.redact(e.shortOverview ?: e.overview),
                `when` = RelativeTime.short(e.date, now),
                isFailure = isFailure(e),
            )
        }
}

/** Whether the server has hardware transcoding set up, and by what name. */
object HardwareTranscoding {
    fun isOn(type: String?): Boolean = !type.isNullOrBlank() && type != "none"

    fun label(type: String?): String =
        when {
            !isOn(type) -> "Off"
            type == "qsv" -> "Intel Quick Sync"
            type == "nvenc" -> "NVIDIA NVENC"
            type == "vaapi" -> "VA-API"
            type == "amf" -> "AMD AMF"
            type == "videotoolbox" -> "VideoToolbox"
            type == "v4l2m2m" -> "V4L2"
            else -> type!!.replaceFirstChar { it.uppercase() }
        }
}

/** The library scan's last result, from the RefreshLibrary scheduled task. */
object LibraryScan {
    private const val KEY = "RefreshLibrary"

    fun summary(
        tasks: List<ScheduledTaskDto>,
        now: Instant,
    ): String? {
        val task = tasks.firstOrNull { it.key == KEY } ?: return null
        val result = task.lastExecutionResult ?: return "Never run"
        val status = result.status ?: return "Never run"
        val ago = RelativeTime.parse(result.endTimeUtc)?.let { RelativeTime.short(it, now) }
        return when (ago) {
            null -> status
            "now" -> "$status · just now"
            else -> "$status · $ago ago"
        }
    }
}
