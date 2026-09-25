package dev.bitstorm.sashimi.core.person

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.PersonInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PersonMatchingTest {
    @Test
    fun `diacritics case and punctuation fold together`() {
        assertEquals(NameKey.of("Zoë Kravitz"), NameKey.of("Zoe Kravitz"))
        assertEquals(NameKey.of("Robert Downey Jr."), NameKey.of("robert downey jr"))
        assertEquals(NameKey.of("Lupita Nyong'o"), NameKey.of("LUPITA NYONGO"))
        assertEquals("zoekravitz", NameKey.of("Zoë Kravitz"))
    }

    @Test
    fun `different people stay different`() {
        assertNotEquals(NameKey.of("Chris Evans"), NameKey.of("Chris Pratt"))
    }

    @Test
    fun `non latin letters survive the fold`() {
        assertEquals("渡辺謙", NameKey.of("渡辺 謙"))
    }

    @Test
    fun `the fold ignores a Turkish device locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Under tr-TR a locale-sensitive lowercase turns "I" into dotless "ı".
            assertEquals(NameKey.of("iris"), NameKey.of("IRIS"))
            assertEquals("iris", NameKey.of("IRIS"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `display role prefers the character then the credit type`() {
        assertEquals("Hero", PersonInfo("1", "A", role = "Hero", type = "Actor").displayRole)
        assertEquals("Director", PersonInfo("1", "A", role = " ", type = "Director").displayRole)
        assertNull(PersonInfo("1", "A").displayRole)
    }

    @Test
    fun `actors lead the roster, crew follow, both alphabetical and de-duplicated`() {
        val people =
            listOf(
                PersonInfo("crew", "A Writer", type = "Writer"),
                PersonInfo("z", "Z Actor", role = "Hero", type = "Actor"),
                PersonInfo("a", "a Actor", type = "Actor"),
                PersonInfo("z", "Z Actor", role = "Hero", type = "Actor"),
                PersonInfo("dir", "B Director", type = "Director"),
            )
        assertEquals(listOf("a", "z", "crew", "dir"), CastOrdering.sortedForDisplay(people).map { it.id })
        assertEquals(listOf("a", "z"), CastOrdering.sortedForDisplay(people, limit = 2).map { it.id })
    }

    @Test
    fun `episode guest stars sort with the cast`() {
        val people =
            listOf(
                PersonInfo("director", "Antonio Negret", type = "Director"),
                PersonInfo("writer", "James Thorpe", type = "Writer"),
                PersonInfo("guest", "Rekha Sharma", role = "Dr. Tsing", type = "GuestStar"),
            )
        assertEquals(listOf("guest", "director", "writer"), CastOrdering.sortedForDisplay(people).map { it.id })
    }

    @Test
    fun `display year falls back from production year to premiere date`() {
        assertEquals(2024, BaseItemDto(id = "1", productionYear = 2024, premiereDate = "2023-01-01T00:00:00Z").displayYear)
        assertEquals(2023, BaseItemDto(id = "1", premiereDate = "2023-11-17T00:00:00.0000000Z").displayYear)
        assertNull(BaseItemDto(id = "1", premiereDate = "20").displayYear)
        assertNull(BaseItemDto(id = "1", type = ItemType.MOVIE).displayYear)
    }
}
