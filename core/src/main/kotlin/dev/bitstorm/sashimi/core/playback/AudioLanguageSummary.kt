package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaStream
import java.util.Locale

/**
 * What the detail page's audio-language chip says (#219): the language that
 * will play and how many OTHER audio languages the item has ("English  +1").
 *
 * The language that will play follows the player's own pick: the preferred
 * audio language when a track matches it, otherwise the track flagged
 * default, otherwise the first. When that track carries no language tag
 * there is no chip ([of] returns null): naming another track's language
 * would claim something the player will not do. Untagged tracks never count
 * as "others".
 */
data class AudioLanguageSummary(
    /** The playing track's language code, as the server tagged it. */
    val languageCode: String,
    /** Distinct other languages (eng/en count once; untagged never counts). */
    val otherLanguages: Int,
) {
    fun label(locale: Locale = Locale.getDefault()): String {
        val normalized = LanguageMatcher.normalize(languageCode) ?: languageCode
        val name =
            Locale
                .forLanguageTag(normalized)
                .getDisplayLanguage(locale)
                .takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
                ?.replaceFirstChar { it.titlecase(locale) }
                ?: languageCode.uppercase(locale)
        return if (otherLanguages > 0) "$name  +$otherLanguages" else name
    }

    companion object {
        fun of(
            audioStreams: List<MediaStream>,
            preferredLanguage: String,
        ): AudioLanguageSummary? {
            val tagged = audioStreams.filter { !it.language.isNullOrBlank() }
            val playing =
                preferredLanguage.takeIf { it.isNotEmpty() }?.let { pref ->
                    tagged.firstOrNull { LanguageMatcher.matches(it.language, pref) }
                }
                    ?: audioStreams.firstOrNull { it.isDefault == true }
                    ?: audioStreams.firstOrNull()
                    ?: return null
            if (playing.language.isNullOrBlank()) return null
            val playingKey = LanguageMatcher.normalize(playing.language)
            val others =
                tagged
                    .mapNotNull { LanguageMatcher.normalize(it.language) }
                    .toSet()
                    .count { it != playingKey }
            return AudioLanguageSummary(playing.language!!, others)
        }
    }
}
