package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaStream

/**
 * ISO 639-1 / 639-2 tolerant language matching, ported from the Swift
 * `PlaybackSelection.languagesMatch` + `normalizedLanguageCode`. Jellyfin
 * streams tag languages as either alpha-2 ("en") or alpha-3 ("eng"); a user's
 * preferred-language setting may be either. Both sides are normalised to alpha-2
 * so "eng" matches "en".
 */
object LanguageMatcher {
    // 639-2/B and /T three-letter codes → 639-1 two-letter, for the languages a
    // media server realistically tags. Unknown codes fall through to lowercased.
    private val alpha3ToAlpha2 =
        mapOf(
            "eng" to "en", "spa" to "es", "fra" to "fr", "fre" to "fr",
            "deu" to "de", "ger" to "de", "ita" to "it", "por" to "pt",
            "rus" to "ru", "jpn" to "ja", "kor" to "ko", "zho" to "zh",
            "chi" to "zh", "ara" to "ar", "hin" to "hi", "nld" to "nl",
            "dut" to "nl", "swe" to "sv", "nor" to "no", "dan" to "da",
            "fin" to "fi", "pol" to "pl", "tur" to "tr", "ces" to "cs",
            "cze" to "cs", "ell" to "el", "gre" to "el", "heb" to "he",
            "hun" to "hu", "tha" to "th", "vie" to "vi", "ukr" to "uk",
            "ron" to "ro", "rum" to "ro", "ind" to "id", "msa" to "ms",
            "may" to "ms",
        )

    fun normalize(code: String?): String? {
        val lower = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (lower.length == 2) return lower
        return alpha3ToAlpha2[lower] ?: lower
    }

    fun matches(
        a: String?,
        b: String?,
    ): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        return na == nb
    }

    /**
     * The subtitle stream index to auto-select, or null for none. Only applies
     * when [subtitlesEnabled]. Preference order (Swift `preferredSubtitleStream`):
     * (1) first matching [preferredLanguage]; (2) first with IsDefault; (3) first.
     */
    fun preferredSubtitleIndex(
        subtitleStreams: List<MediaStream>,
        preferredLanguage: String,
        subtitlesEnabled: Boolean,
    ): Int? {
        if (!subtitlesEnabled || subtitleStreams.isEmpty()) return null
        val byLanguage =
            preferredLanguage.takeIf { it.isNotEmpty() }?.let { pref ->
                subtitleStreams.firstOrNull { matches(it.language, pref) }
            }
        val chosen = byLanguage ?: subtitleStreams.firstOrNull { it.isDefault == true } ?: subtitleStreams.firstOrNull()
        return chosen?.index
    }

    /**
     * The audio stream index to auto-select for [preferredLanguage], or null when
     * no track matches (leave the server/player default). Ported from Swift
     * `preferredAudioOptionIndex` — first stream whose language matches.
     */
    fun preferredAudioIndex(
        audioStreams: List<MediaStream>,
        preferredLanguage: String,
    ): Int? {
        if (preferredLanguage.isEmpty()) return null
        return audioStreams.firstOrNull { matches(it.language, preferredLanguage) }?.index
    }
}
