package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.home.HomeRowConfig
import dev.bitstorm.sashimi.core.home.HomeRowSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The shared `sideMenu` value (settings-sync schema, phase 2): the **full
 * combined order** of Home rows and menu-only destinations, each entry
 * `{"id", "menu"}` with `menu` only on destinations. Row ids are the
 * `homeRows` ids; destinations are `search`, `watchlist`, `fintv`,
 * `requests`, `lists`, `dashboard`, `downloads` and `library:<id>`. Home
 * and Settings are fixed and never in it.
 *
 * On Android a destination's flag is its [HomeRowConfig.showInSideMenu],
 * except Lists, whose menu entry follows the separate `showListsInSideMenu`
 * switch (it also drives the phone tab bar). `sideMenu` never touches Home
 * visibility: that is `homeRows`' `on`.
 */
object SideMenuLayout {
    const val SEARCH = "search"
    const val WATCHLIST = "watchlist"
    const val LISTS = "lists"
    const val DASHBOARD = "dashboard"
    const val DOWNLOADS = "downloads"

    /** One entry: its shared id, and its menu flag for a destination (null for a Home-only row). */
    data class Entry(
        val id: String,
        val menu: Boolean?,
    )

    /** The rows after applying a value, and the Lists switch when the value set it. */
    data class Applied(
        val rows: List<HomeRowConfig>,
        val showLists: Boolean?,
    )

    /** The shared id of any local entry: a `homeRows` id for rows, a destination id for menu-only ones. */
    fun sharedId(row: HomeRowConfig): String? =
        HomeRowsLayout.sharedId(row) ?: when (row.kind) {
            HomeRowConfig.Kind.SEARCH -> SEARCH
            HomeRowConfig.Kind.WATCHLIST -> WATCHLIST
            HomeRowConfig.Kind.LISTS -> LISTS
            HomeRowConfig.Kind.DASHBOARD -> DASHBOARD
            HomeRowConfig.Kind.DOWNLOADS -> DOWNLOADS
            else -> null
        }

    /** A Home row (Continue Watching, a library, a list, Requests), as opposed to a menu-only entry. */
    private fun isRow(row: HomeRowConfig) = HomeRowsLayout.sharedId(row) != null

    private fun menuFlag(
        row: HomeRowConfig,
        showLists: Boolean,
    ): Boolean? =
        when {
            row.kind == HomeRowConfig.Kind.LISTS -> showLists
            row.isSideMenuDestination -> row.showInSideMenu
            else -> null
        }

    /** This device's combined order. */
    fun project(
        rows: List<HomeRowConfig>,
        showLists: Boolean,
    ): List<Entry> = rows.mapNotNull { row -> sharedId(row)?.let { Entry(it, menuFlag(row, showLists)) } }

    /** The entries of a value, or null unless it is an array of `{id: string, menu?: bool}`. */
    fun decode(v: JsonElement): List<JsonObject>? {
        val array = v as? JsonArray ?: return null
        return array.map { element ->
            val obj = element as? JsonObject ?: return null
            if (obj.id() == null) return null
            val menu = obj["menu"]
            if (menu != null && menu !is JsonNull && menu.bool() == null) return null
            obj
        }
    }

    fun entries(v: JsonElement): List<Entry>? = decode(v)?.map { Entry(it.id()!!, it["menu"]?.bool()) }

    /**
     * Applies a remote value:
     * 1. entries this device has take the value's order, and destinations its `menu` flag;
     * 2. an entry the value does not name stays right after the entry it follows now;
     * 3. ids this device does not have are skipped.
     *
     * [keepRowOrder] (this device's `homeRows` order is newer than the value)
     * keeps the rows' relative order as it is: the value then only places the
     * menu-only entries, which keep its positions while the rows fill the rest.
     */
    fun apply(
        local: List<HomeRowConfig>,
        remote: List<Entry>,
        keepRowOrder: Boolean = false,
    ): Applied {
        val remoteMenu = LinkedHashMap<String, Boolean?>()
        remote.forEach { if (it.id !in remoteMenu) remoteMenu[it.id] = it.menu }
        val byShared = local.filter { sharedId(it) != null }.associateBy { sharedId(it) }
        val ordered =
            remoteMenu
                .mapNotNull { (id, menu) -> byShared[id]?.withMenu(menu) }
                .toMutableList()
        val result = placeUnnamed(local, ordered) { row -> sharedId(row)?.let { it in remoteMenu } == true }
        val rows = if (keepRowOrder) fillRowSlots(result, local.filter(::isRow).map { it.id }) else result
        val showLists = remoteMenu[LISTS].takeIf { byShared.containsKey(LISTS) }
        return Applied(rows, showLists)
    }

    private fun HomeRowConfig.withMenu(menu: Boolean?): HomeRowConfig =
        if (menu == null || !isSideMenuDestination || kind == HomeRowConfig.Kind.LISTS) this else copy(showInSideMenu = menu)

    /**
     * Rows in [order] (local ids), put into the slots rows take in [rows];
     * everything else keeps its index.
     */
    internal fun fillRowSlots(
        rows: List<HomeRowConfig>,
        order: List<String>,
    ): List<HomeRowConfig> {
        val rowsById = rows.filter(::isRow).associateBy { it.id }
        val queue = ArrayDeque(order.mapNotNull { rowsById[it] } + rows.filter { isRow(it) && it.id !in order })
        return rows.map { if (isRow(it)) queue.removeFirst() else it }
    }

    /** Puts each local entry not [placed] right after the entry it follows locally. */
    internal fun placeUnnamed(
        local: List<HomeRowConfig>,
        ordered: MutableList<HomeRowConfig>,
        placed: (HomeRowConfig) -> Boolean,
    ): List<HomeRowConfig> {
        local.forEachIndexed { index, row ->
            if (placed(row)) return@forEachIndexed
            if (index == 0) {
                ordered.add(0, row)
            } else {
                val predecessor = local[index - 1]
                val at = ordered.indexOfFirst { it.id == predecessor.id && it.kind == predecessor.kind }
                ordered.add(at + 1, row)
            }
        }
        return ordered
    }

    /**
     * The value this device writes: its own combined order, then every remote
     * entry it does not have (tvOS's hero and SashimiTV, an unknown id) put
     * back after the predecessor it had remotely, untouched.
     */
    fun write(
        local: List<HomeRowConfig>,
        showLists: Boolean,
        remote: JsonElement?,
    ): JsonArray {
        val own = project(local, showLists).map { it.toJson() }
        return HomeRowsLayout.carryThrough(own, remote?.let { decode(it) })
    }

    /**
     * Whether a save from [before] to [after] is the user editing the menu:
     * the relative order of entries present in both, or a destination's menu
     * switch. Entries appearing by themselves (a new library, a list, Search
     * added by the one-time migration) are not edits, nor is a Home switch.
     */
    fun isUserChange(
        before: List<HomeRowConfig>?,
        after: List<HomeRowConfig>,
    ): Boolean {
        if (before == null) return false
        val old = project(HomeRowSettings.normalized(before), showLists = true)
        val new = project(after, showLists = true)
        val common = old.map { it.id }.toSet().intersect(new.map { it.id }.toSet())
        return old.filter { it.id in common } != new.filter { it.id in common }
    }

    /**
     * Seeding: whether the user clearly customised the menu. A destination
     * switched off counts, and so does an order of destinations no Sashimi
     * version grows by default: `search, watchlist, libraries…, lists,
     * requests, dashboard, downloads` today, or an order from before Search
     * joined it, where Requests sat wherever Continue Watching was. A reorder
     * among libraries alone does not count, as for `homeRows`.
     */
    fun isCustomised(
        rows: List<HomeRowConfig>,
        showLists: Boolean,
    ): Boolean {
        if (!showLists) return true
        if (rows.any { it.isSideMenuDestination && it.kind != HomeRowConfig.Kind.LISTS && !it.showInSideMenu }) return true
        val sequence =
            rows
                .mapNotNull {
                    when (it.kind) {
                        HomeRowConfig.Kind.SEARCH -> 'S'
                        HomeRowConfig.Kind.WATCHLIST -> 'W'
                        HomeRowConfig.Kind.LIBRARY -> 'B'
                        HomeRowConfig.Kind.LISTS -> 'T'
                        HomeRowConfig.Kind.REQUESTS -> 'R'
                        HomeRowConfig.Kind.DASHBOARD -> 'D'
                        HomeRowConfig.Kind.DOWNLOADS -> 'O'
                        else -> null
                    }
                }.joinToString("")
        return DEFAULT_SEQUENCES.none { it.matches(sequence) }
    }

    private val DEFAULT_SEQUENCES = listOf(Regex("S?W?B*T?R?D?O?"), Regex("S?W?B*R?B*T?D?O?"))

    private fun Entry.toJson(): JsonObject =
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                menu?.let { put("menu", JsonPrimitive(it)) }
            },
        )

    private fun JsonObject.id(): String? = (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull
}
