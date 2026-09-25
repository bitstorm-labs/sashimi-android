package dev.bitstorm.sashimi.core.shuffle

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun item(
    id: String,
    type: ItemType,
    season: Int? = null,
    played: Boolean = false,
) = BaseItemDto(
    id = id,
    name = id,
    type = type,
    parentIndexNumber = season,
    userData = UserItemDataDto(played = played),
)

private fun ep(
    id: String,
    season: Int,
    played: Boolean = false,
) = item(id, ItemType.EPISODE, season, played)

/** A fake server: one random answer per type, plus per-series Next Up and episodes. */
private class FakeSource : TvShuffleSource {
    val random = mutableMapOf<ItemType, BaseItemDto>()
    val nextUp = mutableMapOf<String, BaseItemDto>()
    val episodes = mutableMapOf<String, List<BaseItemDto>>()
    val randomRequests = mutableListOf<Pair<String, List<ItemType>>>()

    override suspend fun randomItem(
        parentId: String,
        includeTypes: List<ItemType>,
    ): BaseItemDto? {
        randomRequests += parentId to includeTypes
        return includeTypes.firstNotNullOfOrNull { random[it] }
    }

    override suspend fun nextUpEpisode(seriesId: String) = nextUp[seriesId]

    override suspend fun episodes(seriesId: String) = episodes[seriesId].orEmpty()
}

class TvShuffleTest {
    private suspend fun pick(
        mode: TvShuffleMode,
        source: FakeSource,
        types: List<ItemType> = listOf(ItemType.EPISODE),
    ) = TvShuffle.pick("lib", types, mode, source)

    @Test
    fun `default mode is Random Episode`() {
        assertEquals(TvShuffleMode.RANDOM_EPISODE, TvShuffleMode.DEFAULT)
        assertEquals(TvShuffleMode.RANDOM_EPISODE, TvShuffleMode.fromKey(null))
        assertEquals(TvShuffleMode.RANDOM_EPISODE, TvShuffleMode.fromKey("somethingElse"))
    }

    @Test
    fun `stored keys round-trip`() {
        TvShuffleMode.entries.forEach { assertEquals(it, TvShuffleMode.fromKey(it.key)) }
    }

    @Test
    fun `Random Episode picks any episode in the library`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.EPISODE] = ep("ep", season = 3)
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            assertEquals("ep", pick(TvShuffleMode.RANDOM_EPISODE, source)?.id)
            assertEquals(listOf("lib" to listOf(ItemType.EPISODE)), source.randomRequests)
        }

    @Test
    fun `Random Show plays that show's Next Up episode`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            source.random[ItemType.EPISODE] = ep("random-ep", season = 2)
            source.nextUp["show"] = ep("s2e4", season = 2)
            source.episodes["show"] = listOf(ep("s1e1", 1))
            assertEquals("s2e4", pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, source)?.id)
            assertEquals("lib" to listOf(ItemType.SERIES), source.randomRequests.first())
        }

    @Test
    fun `Random Show without Next Up plays the first unwatched regular episode`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            source.episodes["show"] =
                listOf(ep("s0e1", 0), ep("s1e1", 1, played = true), ep("s1e2", 1), ep("s2e1", 2))
            assertEquals("s1e2", pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, source)?.id)
        }

    @Test
    fun `Random Show skips a special while regular episodes are unwatched`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            source.nextUp["show"] = ep("s0e9", 0)
            source.episodes["show"] = listOf(ep("s0e9", 0), ep("s1e1", 1), ep("s1e2", 1))
            assertEquals("s1e1", pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, source)?.id)
        }

    @Test
    fun `a fully watched show restarts at its first regular episode`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            source.episodes["show"] =
                listOf(ep("s0e1", 0, played = true), ep("s1e1", 1, played = true), ep("s1e2", 1, played = true))
            assertEquals("s1e1", pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, source)?.id)
        }

    @Test
    fun `Random Show on an empty library is null`() =
        runTest {
            assertNull(pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, FakeSource()))
        }

    @Test
    fun `a movie library ignores the TV mode`() =
        runTest {
            val source = FakeSource()
            source.random[ItemType.MOVIE] = item("movie", ItemType.MOVIE)
            source.random[ItemType.SERIES] = item("show", ItemType.SERIES)
            assertEquals("movie", pick(TvShuffleMode.RANDOM_SHOW_NEXT_EPISODE, source, listOf(ItemType.MOVIE))?.id)
            assertEquals(listOf("lib" to listOf(ItemType.MOVIE)), source.randomRequests)
        }
}
