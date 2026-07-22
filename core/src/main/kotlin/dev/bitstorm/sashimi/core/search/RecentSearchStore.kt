package dev.bitstorm.sashimi.core.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistence seam for recent searches (SharedPreferences in prod, fake in tests). */
interface RecentSearchPersistence {
    fun load(): List<String>

    fun save(searches: List<String>)
}

/**
 * Last-[MAX_COUNT] search queries. Android port of the Swift `RecentSearches`
 * enum: newest-first, case-insensitive dedupe, capped at 10, with Clear.
 */
class RecentSearchStore(
    private val persistence: RecentSearchPersistence,
) {
    private val _searches = MutableStateFlow(persistence.load())
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    /**
     * Record a settled query. Trims whitespace; ignores blanks; removes any
     * existing case-insensitive match before inserting at the front; caps the
     * list at [MAX_COUNT]. Port of RecentSearches.add().
     */
    fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val deduped = _searches.value.filterNot { it.equals(trimmed, ignoreCase = true) }
        val updated = (listOf(trimmed) + deduped).take(MAX_COUNT)
        _searches.value = updated
        persistence.save(updated)
    }

    fun clear() {
        _searches.value = emptyList()
        persistence.save(emptyList())
    }

    companion object {
        const val MAX_COUNT = 10
    }
}
