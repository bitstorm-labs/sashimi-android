package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Records which transcoded downloads were fetched with real per-tier encode
 * parameters.
 *
 * Until that fix, High / Medium / Low asked the progressive endpoint for
 * `MaxStreamingBitrate`, which it ignores, and the server produced either a
 * roughly 1 kbps encode or a full-size stream copy. Those files are still on
 * users' devices marked COMPLETED, and nothing in the row says which URL made
 * them. So the fixed code records each download it completes here, and a
 * completed transcoded row that is NOT recorded is one made by the old URL
 * (see [DownloadPolicy.needsRedownload]).
 *
 * Kept outside the Room table on purpose: the database falls back to
 * destructive migration, so a schema bump that went wrong would drop every row
 * and the orphan sweep would then delete the user's files. Absence here costs
 * only a spurious "re-download" badge.
 */
interface TierEncodeLedger {
    val keys: StateFlow<Set<DownloadKey>>

    fun mark(key: DownloadKey)

    fun unmark(key: DownloadKey)

    fun clear()
}

/** The ledger held in memory only; the base of the persisted one, and the test double. */
open class InMemoryTierEncodeLedger(
    initial: Set<DownloadKey> = emptySet(),
) : TierEncodeLedger {
    private val state = MutableStateFlow(initial)
    override val keys: StateFlow<Set<DownloadKey>> = state.asStateFlow()

    override fun mark(key: DownloadKey) = change { it + key }

    override fun unmark(key: DownloadKey) = change { it - key }

    override fun clear() = change { emptySet() }

    private fun change(transform: (Set<DownloadKey>) -> Set<DownloadKey>) {
        state.update(transform)
        persist(state.value)
    }

    protected open fun persist(keys: Set<DownloadKey>) = Unit
}

/** The ledger persisted in SharedPreferences, one `serverId/itemId` string per download. */
class PrefsTierEncodeLedger private constructor(
    private val prefs: android.content.SharedPreferences,
) : InMemoryTierEncodeLedger(TierEncodeLedgerCodec.decode(prefs.getStringSet(KEY, null).orEmpty())) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun persist(keys: Set<DownloadKey>) {
        prefs.edit().putStringSet(KEY, TierEncodeLedgerCodec.encode(keys)).apply()
    }

    private companion object {
        const val PREFS = "download_tier_encodes"
        const val KEY = "keys"
    }
}

/** The ledger's stored form. Pure, so the round trip is unit-testable. */
object TierEncodeLedgerCodec {
    fun encode(keys: Set<DownloadKey>): Set<String> = keys.map { "${it.serverId}/${it.itemId}" }.toSet()

    /** Splits on the LAST slash: an item id never contains one, a server id might. */
    fun decode(stored: Set<String>): Set<DownloadKey> =
        stored
            .mapNotNull { entry ->
                val cut = entry.lastIndexOf('/')
                if (cut < 0) null else DownloadKey(entry.substring(0, cut), entry.substring(cut + 1))
            }.toSet()
}
