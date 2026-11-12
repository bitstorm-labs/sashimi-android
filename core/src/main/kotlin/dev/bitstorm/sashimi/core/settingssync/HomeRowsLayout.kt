package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.home.HomeRowConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The shared `homeRows` layout value and its mapping to Android's
 * [HomeRowConfig] list (settings-sync schema, "Layout value").
 *
 * Android has no hero and no SashimiTV row, and its menu-only entries
 * (`menu:*`) are not part of the shared layout: they stay where they are
 * locally, and remote entries Android lacks are carried through on write.
 */
object HomeRowsLayout {
    const val CONTINUE_WATCHING = "continueWatching"
    const val REQUESTS = "requests"
    const val LIBRARY_PREFIX = "library:"
    const val LIST_PREFIX = "list:"

    /** One row in a layout: its shared id and whether it shows on Home. */
    data class Entry(
        val id: String,
        val on: Boolean,
    )

    /** The shared id of a local row, or null for a row the shared layout does not hold. */
    fun sharedId(row: HomeRowConfig): String? =
        when (row.kind) {
            HomeRowConfig.Kind.CONTINUE_WATCHING -> CONTINUE_WATCHING
            HomeRowConfig.Kind.REQUESTS -> REQUESTS
            HomeRowConfig.Kind.LIBRARY -> row.libraryId?.takeIf { it.isNotEmpty() }?.let { LIBRARY_PREFIX + it }
            HomeRowConfig.Kind.LIST -> row.listId?.takeIf { it.isNotEmpty() }?.let { LIST_PREFIX + it }
            else -> null
        }

    /** This device's synced rows, in its order. */
    fun project(rows: List<HomeRowConfig>): List<Entry> = rows.mapNotNull { row -> sharedId(row)?.let { Entry(it, row.isEnabled) } }

    /** The entries of a layout value, or null unless it is an array of `{id: string, on: bool}`. */
    fun decode(v: JsonElement): List<JsonObject>? {
        val array = v as? JsonArray ?: return null
        return array.map { element ->
            val obj = element as? JsonObject ?: return null
            if (obj.id() == null || obj.on() == null) return null
            obj
        }
    }

    fun entries(v: JsonElement): List<Entry>? = decode(v)?.map { Entry(it.id()!!, it.on()!!) }

    /**
     * Applies a remote layout to the local rows:
     * 1. rows this device has and the layout names take the layout's order and `on` flag;
     * 2. a row the layout does not name stays right after the row it follows now;
     * 3. ids this device does not have are skipped.
     */
    fun apply(
        local: List<HomeRowConfig>,
        remote: List<Entry>,
    ): List<HomeRowConfig> {
        val remoteOn = LinkedHashMap<String, Boolean>()
        remote.forEach { if (it.id !in remoteOn) remoteOn[it.id] = it.on }
        val byShared = local.associateBy { sharedId(it) }
        val result =
            remoteOn
                .mapNotNull { (id, on) -> byShared[id]?.copy(isEnabled = on) }
                .toMutableList()
        val placed = result.mapTo(HashSet()) { sharedId(it) }
        local.forEachIndexed { index, row ->
            if (sharedId(row)?.let { it in placed } == true) return@forEachIndexed
            if (index == 0) {
                result.add(0, row)
            } else {
                val predecessor = local[index - 1]
                val at = result.indexOfFirst { it.id == predecessor.id && it.kind == predecessor.kind }
                result.add(at + 1, row)
            }
        }
        return result
    }

    /**
     * The layout this device writes: its own rows in its order, then every
     * remote entry it does not have put back right after the predecessor it
     * had remotely, carried through untouched (tvOS's hero, an unknown id).
     */
    fun write(
        local: List<HomeRowConfig>,
        remote: JsonElement?,
    ): JsonArray {
        val result: MutableList<JsonObject> = project(local).mapTo(mutableListOf()) { it.toJson() }
        val remoteEntries = remote?.let { decode(it) } ?: return JsonArray(result)
        val have = result.mapTo(HashSet()) { it.id() }
        remoteEntries.forEachIndexed { index, entry ->
            val id = entry.id()
            if (id in have) return@forEachIndexed
            val at =
                if (index == 0) {
                    0
                } else {
                    val predecessor = remoteEntries[index - 1].id()
                    result.indexOfFirst { it.id() == predecessor } + 1
                }
            result.add(at, entry)
            have += id
        }
        return JsonArray(result)
    }

    /**
     * Whether a save from [before] to [after] is the user reordering or
     * hiding rows. Rows coming and going on their own (a new library, a list
     * subscribed elsewhere) and side-menu-only changes are not: only the
     * order and `on` flags of the rows present in both count.
     */
    fun isUserChange(
        before: List<HomeRowConfig>?,
        after: List<HomeRowConfig>,
    ): Boolean {
        if (before == null) return false
        val old = project(before)
        val new = project(after)
        val common = old.map { it.id }.toSet().intersect(new.map { it.id }.toSet())
        return old.filter { it.id in common } != new.filter { it.id in common }
    }

    /**
     * Seeding: whether this order looks set by the user rather than grown by
     * default. A hidden row, or rows out of the order Android adds them in
     * (Continue Watching, lists, Requests, libraries), counts. A reorder among
     * libraries alone cannot be told from the server's order and does not.
     */
    fun isCustomised(rows: List<HomeRowConfig>): Boolean {
        if (rows.any { sharedId(it) != null && !it.isEnabled }) return true
        val sequence =
            rows
                .mapNotNull {
                    when (it.kind) {
                        HomeRowConfig.Kind.CONTINUE_WATCHING -> 'C'
                        HomeRowConfig.Kind.LIST -> 'L'
                        HomeRowConfig.Kind.REQUESTS -> 'R'
                        HomeRowConfig.Kind.LIBRARY -> 'B'
                        else -> null
                    }
                }.joinToString("")
        return !DEFAULT_SEQUENCE.matches(sequence)
    }

    private val DEFAULT_SEQUENCE = Regex("C?L*R?B*")

    private fun Entry.toJson() = JsonObject(mapOf("id" to JsonPrimitive(id), "on" to JsonPrimitive(on)))

    private fun JsonObject.id(): String? = (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.on(): Boolean? = (this["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
