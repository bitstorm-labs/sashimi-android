package dev.bitstorm.sashimi.core.admin

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

enum class AttentionSeverity { ERROR, WARNING }

data class AttentionItem(
    val text: String,
    val `when`: String,
    val severity: AttentionSeverity,
)

/** The phone's "Needs attention" list: failures and warnings only. */
object Attention {
    private val FAILED_TASK = setOf("Failed", "Aborted")
    private val SIGN_IN_WINDOW: Duration = Duration.ofHours(24)
    private val fromUser = Regex("""\bfrom (.+)$""")

    fun items(
        tasks: List<ScheduledTaskDto>,
        activity: List<ActivityLogEntryDto>,
        storage: List<StorageRow>,
        now: Instant,
    ): List<AttentionItem> {
        val errors = mutableListOf<AttentionItem>()
        tasks.filter { it.lastExecutionResult?.status in FAILED_TASK }.forEach { task ->
            val ago = RelativeTime.short(task.lastExecutionResult?.endTimeUtc, now)
            errors += AttentionItem("${task.name} failed", ago, AttentionSeverity.ERROR)
        }
        activity
            .filter { it.type == "AuthenticationFailed" }
            .mapNotNull { e -> RelativeTime.parse(e.date)?.let { e to it } }
            .filter { (_, at) -> Duration.between(at, now) <= SIGN_IN_WINDOW }
            .groupBy { (e, _) -> signInName(e) }
            .forEach { (user, hits) ->
                val newest = hits.maxOf { it.second }
                val text = if (hits.size == 1) "Failed sign-in for “$user”" else "${hits.size} failed sign-ins for “$user”"
                errors += AttentionItem(text, RelativeTime.short(newest, now), AttentionSeverity.ERROR)
            }
        val warnings =
            storage.filter { it.isWarning }.map { row ->
                val pct = ((row.fraction ?: 0.0) * 100).roundToInt()
                AttentionItem("${row.label} $pct% full", "now", AttentionSeverity.WARNING)
            }
        return errors + warnings
    }

    /** The account a failed sign-in was for, redacted in case the text carries an address. */
    private fun signInName(entry: ActivityLogEntryDto): String {
        val name = IpRedaction.redact(entry.name).orEmpty()
        return fromUser.find(name)?.groupValues?.get(1) ?: name
    }
}
