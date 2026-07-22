package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Configuration for a single Home tab row — order + visibility, persisted to a
 * [HomeRowStore]. Port of Settings/HomeRowSettings.swift (HomeRowConfig).
 *
 * The Swift model used a nested enum (`.builtIn` / `.library`); this is
 * flattened to a single Codable-friendly shape ([kind] + optional library
 * fields) so it serializes to stable JSON without a polymorphic serializer.
 * There is one built-in row today — Continue Watching — plus one row per
 * library (Recently Added <library>).
 */
@Serializable
data class HomeRowConfig(
    val kind: Kind,
    val libraryId: String? = null,
    val libraryName: String? = null,
    val isEnabled: Boolean = true,
) {
    enum class Kind { CONTINUE_WATCHING, LIBRARY }

    /** Stable identity used for reordering + dedupe (matches the Swift `id`). */
    val id: String
        get() =
            when (kind) {
                Kind.CONTINUE_WATCHING -> CONTINUE_WATCHING_ID
                Kind.LIBRARY -> libraryId ?: ""
            }

    val displayName: String
        get() =
            when (kind) {
                Kind.CONTINUE_WATCHING -> "Continue Watching"
                Kind.LIBRARY -> "Recently Added ${libraryName ?: ""}"
            }

    companion object {
        const val CONTINUE_WATCHING_ID = "continue_watching"

        fun continueWatching(isEnabled: Boolean = true): HomeRowConfig {
            return HomeRowConfig(kind = Kind.CONTINUE_WATCHING, isEnabled = isEnabled)
        }

        fun library(
            id: String,
            name: String,
            isEnabled: Boolean = true,
        ) = HomeRowConfig(
            kind = Kind.LIBRARY,
            libraryId = id,
            libraryName = name,
            isEnabled = isEnabled,
        )
    }
}

/** Persistence seam so the ordering/migration logic is unit-testable. */
interface HomeRowStore {
    /** Returns null when nothing has ever been saved (first launch). */
    fun load(): List<HomeRowConfig>?

    fun save(rows: List<HomeRowConfig>)
}

/**
 * Ordered, per-library Home row configuration. Android port of the Swift
 * `HomeRowSettings` (was an ObservableObject; here it's a StateFlow holder with
 * an injected store so the reconcile logic runs in plain unit tests).
 */
class HomeRowSettings(
    private val store: HomeRowStore,
) {
    private val _rows = MutableStateFlow(loadRows())
    val rows: StateFlow<List<HomeRowConfig>> = _rows.asStateFlow()

    /**
     * Load persisted rows, or the default (just Continue Watching) on first
     * launch — libraries are appended dynamically by [updateLibraries]. This is
     * the migration behaviour: an install with no saved order still gets the
     * built-in row (port of Swift loadRows()).
     */
    private fun loadRows(): List<HomeRowConfig> = store.load() ?: listOf(HomeRowConfig.continueWatching())

    /**
     * Reconcile the row list against the current library set: append rows for
     * newly-seen libraries (enabled), drop rows for libraries that no longer
     * exist, and preserve the user's existing order + visibility. Port of Swift
     * updateLibraries().
     */
    fun updateLibraries(libraries: List<JellyfinLibrary>) {
        val current = _rows.value.toMutableList()

        // Append any library not already present.
        for (library in libraries) {
            val present =
                current.any { it.kind == HomeRowConfig.Kind.LIBRARY && it.libraryId == library.id }
            if (!present) {
                current.add(HomeRowConfig.library(id = library.id, name = library.name))
            }
        }

        // Remove library rows whose library is gone.
        val liveIds = libraries.map { it.id }.toSet()
        current.removeAll { it.kind == HomeRowConfig.Kind.LIBRARY && it.libraryId !in liveIds }

        commit(current)
    }

    /** Move a row (drag-to-reorder). No-op if indices are out of range. */
    fun moveRow(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val list = _rows.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        list.add(toIndex, list.removeAt(fromIndex))
        commit(list)
    }

    /** Toggle a row's visibility by its stable id. */
    fun toggleRow(id: String) {
        val list =
            _rows.value.map { if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it }
        commit(list)
    }

    fun setRows(rows: List<HomeRowConfig>) = commit(rows)

    private fun commit(rows: List<HomeRowConfig>) {
        _rows.value = rows
        store.save(rows)
    }
}
