package dev.bitstorm.sashimi.core.settingssync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** What one read of the remote entry means for this device. */
data class SyncPlan(
    /** Remote values to apply locally; each key's stamp becomes the remote `t`. */
    val apply: Map<String, StampedValue>,
    /** Keys whose local value goes into the one write, stamped with the local stamp. */
    val write: Set<String>,
)

/** The merge rules, identical on every platform. Pure: no I/O, no clock. */
object SettingsSyncRules {
    /**
     * Reconcile, key by key:
     * 1. remote `t` newer than the local stamp, and the value valid here: apply;
     * 2. local stamp newer, or the remote key missing while there is a local stamp: write;
     * 3. equal: nothing.
     *
     * An entry that does not decode ("{not json") is left alone, as is a
     * newer value this platform does not know: a newer device may have written it.
     */
    fun reconcile(
        keys: List<String>,
        remote: Map<String, JsonElement>,
        stamps: Map<String, Long>,
    ): SyncPlan {
        val apply = linkedMapOf<String, StampedValue>()
        val write = linkedSetOf<String>()
        for (key in keys) {
            val local = stamps[key]
            val raw = remote[key]
            if (raw == null || raw is JsonNull) {
                if (local != null) write += key
                continue
            }
            val entry = StampedValueCodec.decode(raw) ?: continue
            when {
                entry.t > (local ?: Long.MIN_VALUE) -> if (SettingsSyncSchema.isValid(key, entry.v)) apply[key] = entry
                local != null && local > entry.t -> write += key
            }
        }
        return SyncPlan(apply, write)
    }

    /**
     * After a local change: write [key] only if our stamp is newer than what
     * the server holds. An entry that does not decode is replaced: the user
     * just made a real change.
     */
    fun shouldPush(
        key: String,
        remote: Map<String, JsonElement>,
        stamp: Long,
    ): Boolean {
        val raw = remote[key]
        if (raw == null || raw is JsonNull) return true
        val entry = StampedValueCodec.decode(raw) ?: return true
        return stamp > entry.t
    }

    /** First run after the upgrade: every explicitly set key gets stamp 1, defaults get none. */
    fun seed(explicitlySet: Set<String>): Map<String, Long> = explicitlySet.associateWith { SEED_STAMP }

    const val SEED_STAMP = 1L
}
