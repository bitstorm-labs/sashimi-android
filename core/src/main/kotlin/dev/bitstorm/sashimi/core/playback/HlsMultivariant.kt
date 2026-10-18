package dev.bitstorm.sashimi.core.playback

/**
 * Reading Jellyfin's HLS multivariant playlist (`master.m3u8`) to decide
 * whether the player should be handed one media playlist instead. Ported from
 * the Apple client's `HLSMultivariantPlaylist` (#443 there).
 *
 * For an HDR source the server is going to stream-copy, Jellyfin's
 * `DynamicHlsHelper` appends HDR to SDR fallback variants
 * (`AllowVideoStreamCopy=false`) at the SAME bandwidth as the copy variant.
 * Seen live for "300" once the profile allowed HEVC copy: an `hvc1` PQ copy,
 * an `hvc1` SDR re-encode and an `avc1` SDR re-encode, all 46.4 Mbps. They
 * share one ffmpeg slot and segment namespace on the server but not one
 * segment grid, so every variant switch restarts ffmpeg and the stream
 * stalls; on Apple TV 4K HDR could not get through an episode. ExoPlayer
 * treats the two `hvc1` variants as one adaptive group and may switch between
 * them, so Android pins too.
 */
object HlsMultivariant {
    data class Variant(
        /** The `#EXT-X-STREAM-INF` attribute list. */
        val attributes: String,
        /** The URI line that follows it, as written. */
        val uri: String,
    ) {
        /** Jellyfin marks its fallback variants by forcing the stream copy off. */
        val isStreamCopyFallback: Boolean get() = uri.contains("AllowVideoStreamCopy=false", ignoreCase = true)
    }

    /** Every `#EXT-X-STREAM-INF` entry with its URI; image streams and media renditions are not variants. */
    fun variants(playlist: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        var pending: String? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith(STREAM_INF) -> pending = line.removePrefix(STREAM_INF)
                // A tag or comment between STREAM-INF and its URI is legal.
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    variants += Variant(pending, line)
                    pending = null
                }
            }
        }
        return variants
    }

    /**
     * The media playlist to play instead of [masterUrl], or null to play the
     * master as it is. Pins only when the fallback variants are present AND
     * there is a primary to pin to: an adaptive ladder without fallbacks is a
     * legitimate master, and a master of nothing but forced re-encodes (a
     * request that itself disallowed the copy) has no primary.
     */
    fun primaryVariantUrl(
        playlist: String,
        masterUrl: String,
    ): String? {
        val variants = variants(playlist)
        if (variants.none { it.isStreamCopyFallback }) return null
        val primary = variants.firstOrNull { !it.isStreamCopyFallback } ?: return null
        return resolve(masterUrl, primary.uri)
    }

    /**
     * [uri] relative to [base]. By hand rather than java.net.URI, which throws
     * on characters Jellyfin leaves unescaped in its query strings.
     */
    internal fun resolve(
        base: String,
        uri: String,
    ): String {
        if (uri.contains("://")) return uri
        val path = base.substringBefore('?')
        if (uri.startsWith("/")) {
            val schemeEnd = path.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
            val authorityEnd = path.indexOf('/', schemeEnd).takeIf { it >= 0 } ?: path.length
            return path.substring(0, authorityEnd) + uri
        }
        return path.substringBeforeLast('/') + "/" + uri
    }

    /** Whether [url] names a multivariant playlist worth reading first. */
    fun isMultivariant(url: String): Boolean = url.substringBefore('?').endsWith("/master.m3u8", ignoreCase = true)

    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
}
