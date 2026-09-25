package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.PersonInfo
import java.text.Normalizer
import java.util.Locale

/**
 * Name folding shared by person matching and title de-duplication.
 *
 * Person and item ids are server-scoped, so the same actor or film on two
 * Jellyfin servers can only be recognised by name. Servers disagree on case,
 * diacritics and punctuation ("Zoë Kravitz" / "Zoe Kravitz", "Jr." / "Jr"), so
 * the key decomposes (NFD), drops combining marks, lower-cases and keeps only
 * letters and digits. Port of sashimi-apple `PersonInfo.matchingNameKey`.
 *
 * The case fold uses [Locale.ROOT], never the device locale: under a Turkish
 * locale "I" lower-cases to dotless "ı", which would split "IRIS" from "iris".
 */
object NameKey {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun of(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
        val stripped = combiningMarks.replace(decomposed, "")
        val folded = stripped.lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        folded.codePoints().forEach { cp -> if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp) }
        return out.toString()
    }
}

/** Cross-server matching key for this person's name. See [NameKey]. */
val PersonInfo.matchingNameKey: String
    get() = NameKey.of(name)

/**
 * The most useful subtitle for a roster entry: the character name when there is
 * one, else the credit type ("Director", "Writer") so crew still say why they
 * are listed.
 */
val PersonInfo.displayRole: String?
    get() = role?.takeIf { it.isNotBlank() } ?: type?.takeIf { it.isNotBlank() }

object CastOrdering {
    /**
     * On screen rather than behind the camera. Jellyfin types an episode's guest
     * actors as GuestStar, so Actor alone sorted them in with the crew.
     */
    fun isCast(person: PersonInfo): Boolean =
        person.type.equals("Actor", ignoreCase = true) || person.type.equals("GuestStar", ignoreCase = true)

    /**
     * Actors lead the roster, crew follow, each alphabetical, de-duplicated by id
     * (a person credited twice would otherwise render twice). Port of
     * sashimi-apple `PersonInfo.sortedForDisplay`.
     */
    fun sortedForDisplay(
        people: List<PersonInfo>,
        limit: Int = 20,
    ): List<PersonInfo> =
        people
            .sortedWith(
                compareBy<PersonInfo> { !isCast(it) }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            ).distinctBy { it.id }
            .take(limit.coerceAtLeast(0))
}
