package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import java.util.Locale
import kotlin.math.floor

/** The poster's dot: green playing, amber paused, grey when the server doesn't say. */
enum class StreamState { PLAYING, PAUSED, OTHER }

/** A card's title block: [ep] is "S04E03" for an episode, else "". */
data class CardTitle(
    val title: String,
    val sub: String,
    val ep: String,
)

/** Pills, in card order. Each kind has its own colours; [MODE] takes the badge's. */
enum class PillKind { RESOLUTION, VIDEO, AUDIO, BITRATE, MODE }

data class Pill(
    val kind: PillKind,
    val text: String,
)

/**
 * The OpsDec-style Now playing card (design revision 2026-10-18, plus the
 * findings from the Roku 1.6.22 build). A port of sashimi-roku's
 * `AdminDash.bs` card functions with the same strings and rules, so the three
 * clients show the same card for the same session:
 *
 * - Pills in a fixed order: resolution, video codec + range, audio codec +
 *   channels, bitrate, play mode. A converted value reads "source → output".
 * - Resolution, video and bitrate change only on a video conversion; an
 *   audio-only conversion copies the picture whatever TranscodingInfo says.
 * - `HW` only on a video conversion: the server reports its accelerator on
 *   remuxes and audio conversions too.
 * - `SDR` only when tone-mapping is a stated reason: the server never reports
 *   the output range.
 */
object StreamCards {
    private const val TICKS_PER_SECOND = 10_000_000.0

    // State and title

    fun state(session: SessionInfoDto): StreamState =
        when (session.playState?.isPaused) {
            true -> StreamState.PAUSED
            false -> StreamState.PLAYING
            null -> StreamState.OTHER
        }

    fun title(item: BaseItemDto?): CardTitle {
        item ?: return CardTitle("", "", "")
        val year =
            item.productionYear
                ?.takeIf { it > 0 }
                ?.toString()
                .orEmpty()
        return when (item.type) {
            ItemType.EPISODE -> CardTitle(item.name, item.seriesName.orEmpty(), episodeTag(item))
            ItemType.MOVIE -> CardTitle(item.name, if (year.isEmpty()) "Movie" else "Movie · $year", "")
            else -> CardTitle(item.name, year, "")
        }
    }

    /** "S04E03"; "E07" with no season; "" for anything but an episode. */
    fun episodeTag(item: BaseItemDto?): String {
        if (item?.type != ItemType.EPISODE) return ""
        val season = item.parentIndexNumber?.let { "S" + pad2(it) }.orEmpty()
        val episode = item.indexNumber?.let { "E" + pad2(it) }.orEmpty()
        return season + episode
    }

    /** "Sashimi · Living Room Roku": the app, then the device. */
    fun deviceLine(session: SessionInfoDto): String =
        listOfNotNull(session.client, session.deviceName).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The avatar letter. Initials only: the server ignores resize on user images and sends multi-MB originals. */
    fun initialOf(name: String?): String = if (name.isNullOrEmpty()) "?" else name.take(1).uppercase(Locale.US)

    fun percentText(session: SessionInfoDto): String {
        val runtime = session.nowPlayingItem?.runTimeTicks ?: 0
        if (runtime <= 0) return ""
        return "${floor(progress(session) * 100 + 0.5).toInt()}%"
    }

    private fun progress(session: SessionInfoDto): Double {
        val runtime = session.nowPlayingItem?.runTimeTicks ?: 0
        if (runtime <= 0) return 0.0
        val position = session.playState?.positionTicks ?: 0
        return (position.toDouble() / runtime).coerceIn(0.0, 1.0)
    }

    // Pills

    fun pills(session: SessionInfoDto): List<Pill> =
        listOf(
            Pill(PillKind.RESOLUTION, resolutionPill(session)),
            Pill(PillKind.VIDEO, videoPill(session)),
            Pill(PillKind.AUDIO, audioPill(session)),
            Pill(PillKind.BITRATE, ratePill(session)),
        ).filter { it.text.isNotEmpty() } + Pill(PillKind.MODE, modeLabel(session))

    private fun videoConverted(session: SessionInfoDto): Boolean = PlayModes.of(session.transcodingInfo) == PlayMode.CONVERTING_VIDEO

    fun resolutionPill(session: SessionInfoDto): String {
        val video = PlayModes.videoStream(session.nowPlayingItem) ?: return ""
        val src = resolutionLabel(video.width, video.height)
        if (src.isEmpty() || !videoConverted(session)) return src
        val info = session.transcodingInfo ?: return src
        val dst = resolutionLabel(info.width, info.height)
        return if (dst.isEmpty() || dst == src) src else "$src → $dst"
    }

    /** "HEVC DV", or "HEVC DV → H264" / "HEVC DV → HEVC SDR" when re-encoded. */
    fun videoPill(session: SessionInfoDto): String {
        val video = PlayModes.videoStream(session.nowPlayingItem) ?: return ""
        val srcCodec = pillCodec(video.codec)
        val src = joinNonEmpty(" ", srcCodec, rangeShort(video.videoRangeType))
        if (!videoConverted(session)) return src
        val info = session.transcodingInfo ?: return src
        val dstCodec = pillCodec(info.videoCodec)
        if (dstCodec.isEmpty()) return src
        val toneMapped = PlayModes.TONE_MAP in info.transcodeReasons.orEmpty()
        if (dstCodec == srcCodec && !toneMapped) return src
        return "$src → " + if (toneMapped) "$dstCodec SDR" else dstCodec
    }

    /** "DTS 5.1", "DTS → AC3 5.1" (same channels, said once), "DTS 5.1 → AAC 2.0". */
    fun audioPill(session: SessionInfoDto): String {
        val audio = PlayModes.audioStream(session) ?: return ""
        val srcCodec = pillCodec(audio.codec)
        val srcCh = channelsLabel(audio.channels)
        val src = joinNonEmpty(" ", srcCodec, srcCh)
        val info = session.transcodingInfo
        if (info == null || info.isAudioDirect == true) return src
        val dstCodec = pillCodec(info.audioCodec)
        if (dstCodec.isEmpty()) return src
        val dstCh = channelsLabel(info.audioChannels)
        if (dstCodec == srcCodec && (dstCh == srcCh || dstCh.isEmpty())) return src
        if (dstCh.isNotEmpty() && dstCh == srcCh) return "$srcCodec → $dstCodec $dstCh"
        return "$src → " + joinNonEmpty(" ", dstCodec, dstCh)
    }

    fun channelsLabel(channels: Int?): String =
        when {
            channels == null -> ""
            channels in LAYOUTS -> LAYOUTS.getValue(channels)
            channels > 0 -> "${channels}ch"
            else -> ""
        }

    private val LAYOUTS = mapOf(1 to "1.0", 2 to "2.0", 3 to "2.1", 5 to "5.0", 6 to "5.1", 7 to "6.1", 8 to "7.1")

    /** The delivered rate; "60 → 8 Mbps" when the picture is re-encoded. */
    fun ratePill(session: SessionInfoDto): String {
        val dst = formatBitrate(sessionBitrate(session))
        if (!videoConverted(session) || dst.isEmpty()) return dst
        var src = formatBitrate(sourceBitrate(session))
        if (src.isEmpty() || src == dst) return dst
        if (src.endsWith(MBPS) && dst.endsWith(MBPS)) src = src.removeSuffix(MBPS)
        return "$src → $dst"
    }

    private const val MBPS = " Mbps"

    /** The delivered rate when the server converts, else the file's video plus the audio track playing. */
    private fun sessionBitrate(session: SessionInfoDto): Double {
        val delivered = session.transcodingInfo?.bitrate ?: 0
        return if (delivered > 0) delivered.toDouble() else sourceBitrate(session)
    }

    private fun sourceBitrate(session: SessionInfoDto): Double {
        val video = PlayModes.videoStream(session.nowPlayingItem)?.bitRate ?: 0
        val audio = PlayModes.audioStream(session)?.bitRate ?: 0
        return video.toDouble() + audio
    }

    /** "720 kbps" under 1 Mbps, "8.4 Mbps", "60 Mbps"; "" when unknown. */
    fun formatBitrate(bitsPerSecond: Double): String {
        if (bitsPerSecond <= 0) return ""
        if (bitsPerSecond < 1_000_000) return "${floor(bitsPerSecond / 1000 + 0.5).toLong()} kbps"
        return scaled(bitsPerSecond / 1_000_000, MBPS, keepZero = false)
    }

    /** Encoding speed against real time, "1.8x": TranscodingInfo.Framerate over the source's fps. */
    fun speed(session: SessionInfoDto): String {
        val rate = session.transcodingInfo?.framerate?.toDouble() ?: return ""
        if (rate <= 0) return ""
        val video = PlayModes.videoStream(session.nowPlayingItem) ?: return ""
        val fps = video.realFrameRate?.toDouble()?.takeIf { it > 0 } ?: video.averageFrameRate?.toDouble() ?: 0.0
        if (fps <= 0) return ""
        return scaled(rate / fps, "x", keepZero = true)
    }

    fun isHardware(session: SessionInfoDto): Boolean =
        videoConverted(session) && HardwareTranscoding.isOn(session.transcodingInfo?.hardwareAccelerationType)

    /** "Direct Play", "Audio only · 1.0x", "TC 1.8x HW". */
    fun modeLabel(session: SessionInfoDto): String =
        when (PlayModes.of(session.transcodingInfo)) {
            PlayMode.DIRECT_PLAY -> "Direct Play"
            PlayMode.CONVERTING_AUDIO -> joinNonEmpty(" · ", "Audio only", speed(session))
            PlayMode.CONVERTING_VIDEO -> joinNonEmpty(" ", "TC", speed(session), if (isHardware(session)) "HW" else "")
        }

    // Footer

    /** Why, in words; the pills already show what changed. */
    fun footerReason(session: SessionInfoDto): String {
        val info = session.transcodingInfo ?: return "Plays as-is"
        if (PlayModes.of(info) == PlayMode.DIRECT_PLAY) return "Remux"
        val words =
            info.transcodeReasons
                .orEmpty()
                .map(TranscodeReasonWords::of)
                .filter { it.isNotEmpty() }
                .distinct()
        return if (words.isEmpty()) "Converting" else words.joinToString(" · ")
    }

    /** "Local" or "Remote" from the session's address; "" when it has none. The address is never shown. */
    fun whereLabel(session: SessionInfoDto): String {
        val address = session.remoteEndPoint
        if (address.isNullOrBlank()) return ""
        return if (isLocalAddress(address)) "Local" else "Remote"
    }

    /**
     * This network: RFC 1918, loopback and link-local IPv4; IPv6 loopback,
     * link-local (fe80::/10) and unique-local (fc00::/7); an IPv4-mapped IPv6
     * address by its IPv4 rule. CGNAT (100.64.0.0/10) is the carrier's
     * network, so it counts as remote.
     */
    fun isLocalAddress(address: String?): Boolean {
        address ?: return false
        var a = address.trim().lowercase(Locale.US)
        if (a.startsWith("[")) {
            val close = a.indexOf(']')
            if (close < 0) return false
            a = a.substring(1, close)
        } else if (a.count { it == ':' } == 1 && '.' in a) {
            a = a.substringBefore(':')
        }
        val mapped = "::ffff:"
        if (a.startsWith(mapped) && isIpv4(a.removePrefix(mapped))) a = a.removePrefix(mapped)

        if (isIpv4(a)) {
            val (o1, o2) = a.split('.').map(String::toInt)
            return o1 == 10 || o1 == 127 || (o1 == 172 && o2 in 16..31) || (o1 == 192 && o2 == 168) || (o1 == 169 && o2 == 254)
        }
        if (!isIpv6(a)) return false
        if (a == "::1") return true
        val first = a.substringBefore(':')
        if (first.isEmpty() || first.length > 4) return false
        val group = first.toIntOrNull(16) ?: return false
        // fc00-fdff unique-local, fe80-febf link-local.
        return group in 0xFC00..0xFDFF || group in 0xFE80..0xFEBF
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
    }

    /** Three or more colons, or a "::": a clock ("12:30:45") has two. */
    private fun isIpv6(s: String): Boolean {
        if (!s.all { it == ':' || it == '.' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        val colons = s.count { it == ':' }
        return colons >= 2 && (colons >= 3 || "::" in s)
    }

    // Shared labels

    /** "4K", "1080p", "720p", "480p", "SD"; "" when unknown. Width first: a 2.39:1 1080p film is 1920x800. */
    fun resolutionLabel(
        width: Int?,
        height: Int?,
    ): String {
        val w = width ?: 0
        val h = height ?: 0
        return when {
            w <= 0 && h <= 0 -> ""
            w >= 3200 || h >= 1800 -> "4K"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            h >= 400 || w >= 640 -> "480p"
            else -> "SD"
        }
    }

    /** Short codec names as OpsDec shows them: "HEVC", "H264", "EAC3", "TRUEHD". */
    private fun pillCodec(codec: String?): String {
        if (codec.isNullOrEmpty()) return ""
        val c = codec.lowercase(Locale.US)
        return if (c == "h265" || c == "hevc") "HEVC" else c.uppercase(Locale.US)
    }

    private fun rangeShort(rangeType: String?): String {
        val r = rangeType?.lowercase(Locale.US) ?: return ""
        return when {
            r.startsWith("dovi") -> "DV"
            r == "hdr10plus" -> "HDR10+"
            r == "hdr10" -> "HDR10"
            r == "hlg" -> "HLG"
            else -> ""
        }
    }

    /** One decimal under 10 ("1.8x", "8.4 Mbps"), whole numbers above ("12x", "60 Mbps"). */
    private fun scaled(
        value: Double,
        unit: String,
        keepZero: Boolean,
    ): String {
        if (value >= 10) return "${floor(value + 0.5).toLong()}$unit"
        val tenths = floor(value * 10 + 0.5).toLong()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L && !keepZero) "$whole$unit" else "$whole.$frac$unit"
    }

    private fun pad2(n: Int): String = if (n < 10) "0$n" else "$n"

    private fun joinNonEmpty(
        separator: String,
        vararg parts: String,
    ): String = parts.filter { it.isNotEmpty() }.joinToString(separator)
}
