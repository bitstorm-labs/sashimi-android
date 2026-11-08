package dev.bitstorm.sashimi.core.seerr

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import java.text.Normalizer

/** The TMDB id from ProviderIds (present when the query asks for it). */
val BaseItemDto.tmdbId: Int?
    get() =
        providerIds
            ?.entries
            ?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.toIntOrNull()

/** Movie or show, in Seerr's terms. Null for anything else. */
val BaseItemDto.seerrKind: SeerrMediaKind?
    get() =
        when (type) {
            ItemType.MOVIE -> SeerrMediaKind.MOVIE
            ItemType.SERIES -> SeerrMediaKind.TV
            else -> null
        }

/**
 * Keeps search's "Not in your library" row to titles that aren't there:
 * anything Seerr says is on the server, or the library row already shows,
 * appears once, in the library row. Port of sashimi-apple SeerrSearchMerge.
 */
object SeerrSearchMerge {
    private data class Key(
        val kind: SeerrMediaKind,
        val tmdbId: Int,
    )

    private data class TitleKey(
        val kind: SeerrMediaKind,
        val title: String,
        val year: Int?,
    )

    fun requestable(
        results: List<SeerrSearchResult>,
        library: List<BaseItemDto>,
    ): List<SeerrSearchResult> {
        val libraryIds = mutableSetOf<Key>()
        val untagged = mutableSetOf<TitleKey>()
        for (item in library) {
            val kind = item.seerrKind ?: continue
            val tmdb = item.tmdbId
            if (tmdb != null) {
                libraryIds += Key(kind, tmdb)
            } else {
                // No ProviderIds (an unmatched item): title and year, only as good as the metadata.
                untagged += TitleKey(kind, normalized(item.name), item.productionYear)
            }
        }
        val seen = mutableSetOf<Key>()
        return results.filter { result ->
            val key = Key(result.kind, result.tmdbId)
            if (!seen.add(key)) return@filter false
            if (result.mediaInfo?.mediaStatus == SeerrMediaStatus.AVAILABLE) return@filter false
            if (key in libraryIds) return@filter false
            TitleKey(result.kind, normalized(result.title), result.year) !in untagged
        }
    }

    /** Every movie and show once, in Seerr's order. */
    fun unique(results: List<SeerrSearchResult>): List<SeerrSearchResult> {
        val seen = mutableSetOf<Key>()
        return results.filter { seen.add(Key(it.kind, it.tmdbId)) }
    }

    /** The library's copy of a Seerr title, by TMDB id only: a near-miss on title would open the wrong thing. */
    fun libraryMatch(
        items: List<BaseItemDto>,
        tmdbId: Int,
        kind: SeerrMediaKind,
    ): BaseItemDto? = items.firstOrNull { it.seerrKind == kind && it.tmdbId == tmdbId }

    private fun normalized(title: String): String =
        Normalizer
            .normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
}
