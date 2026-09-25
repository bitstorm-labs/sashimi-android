package dev.bitstorm.sashimi.core.trickplay

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.TrickplayInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrickplayMathTest {
    // Jellyfin's default: 320x180 thumbnails, 10x10 per sheet, one every 10s.
    private val info320 =
        TrickplayInfo(
            width = 320,
            height = 180,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = 250,
            interval = 10_000,
            bandwidth = 1000,
        )

    @Test
    fun `thumbnail index is position over interval`() {
        assertEquals(0, TrickplayMath.thumbnailIndex(info320, 0))
        assertEquals(0, TrickplayMath.thumbnailIndex(info320, 9_999))
        assertEquals(1, TrickplayMath.thumbnailIndex(info320, 10_000))
        assertEquals(123, TrickplayMath.thumbnailIndex(info320, 1_234_567))
    }

    @Test
    fun `thumbnail index clamps to the thumbnails that exist`() {
        assertEquals(249, TrickplayMath.thumbnailIndex(info320, 99_999_999))
        assertEquals(0, TrickplayMath.thumbnailIndex(info320, -5_000))
    }

    @Test
    fun `sheet index rolls over every tileWidth x tileHeight thumbnails`() {
        assertEquals(0, TrickplayMath.sheetIndex(info320, 0))
        assertEquals(0, TrickplayMath.sheetIndex(info320, 99))
        assertEquals(1, TrickplayMath.sheetIndex(info320, 100))
        assertEquals(2, TrickplayMath.sheetIndex(info320, 249))
    }

    @Test
    fun `cell rect is row-major within the sheet`() {
        assertEquals(TileRect(0, 0, 320, 180), TrickplayMath.cellRect(info320, 0))
        assertEquals(TileRect(320, 0, 320, 180), TrickplayMath.cellRect(info320, 1))
        assertEquals(TileRect(2880, 0, 320, 180), TrickplayMath.cellRect(info320, 9))
        assertEquals(TileRect(0, 180, 320, 180), TrickplayMath.cellRect(info320, 10))
        assertEquals(TileRect(960, 900, 320, 180), TrickplayMath.cellRect(info320, 53))
        // Thumbnail 153 is on sheet 1, at the same cell as 53.
        assertEquals(TileRect(960, 900, 320, 180), TrickplayMath.cellRect(info320, 153))
    }

    @Test
    fun `non-square grids use tileWidth for columns`() {
        val grid = info320.copy(tileWidth = 4, tileHeight = 3, thumbnailCount = 30)
        // 12 per sheet; index 17 -> sheet 1, within 5 -> row 1, col 1.
        val frame = TrickplayMath.frameAt(grid, 175_000)
        assertEquals(1, frame.sheetIndex)
        assertEquals(TileRect(320, 180, 320, 180), frame.cell)
    }

    @Test
    fun `selects the width closest to 320`() {
        val map =
            mapOf(
                "src" to
                    mapOf(
                        "160" to info320.copy(width = 160, height = 90),
                        "480" to info320.copy(width = 480, height = 270),
                        "320" to info320,
                    ),
            )
        assertEquals(320, TrickplayMath.select("item", map, "src")?.info?.width)
        val noExact = mapOf("src" to mapOf("240" to info320.copy(width = 240), "600" to info320.copy(width = 600)))
        assertEquals(240, TrickplayMath.select("item", noExact, "src")?.info?.width)
        val tie = mapOf("src" to mapOf("220" to info320.copy(width = 220), "420" to info320.copy(width = 420)))
        assertEquals(420, TrickplayMath.select("item", tie, "src")?.info?.width)
    }

    @Test
    fun `prefers the playing media source and falls back to any other`() {
        val map =
            mapOf(
                "other" to mapOf("320" to info320.copy(interval = 5_000)),
                "mine" to mapOf("320" to info320),
            )
        assertEquals("mine", TrickplayMath.select("item", map, "mine")?.mediaSourceId)
        assertEquals("other", TrickplayMath.select("item", mapOf("other" to map.getValue("other")), "mine")?.mediaSourceId)
    }

    @Test
    fun `no or malformed trickplay data selects nothing`() {
        assertNull(TrickplayMath.select("item", null, "src"))
        assertNull(TrickplayMath.select("item", emptyMap(), "src"))
        assertNull(TrickplayMath.select("item", mapOf("src" to mapOf("320" to info320.copy(interval = 0))), "src"))
        assertNull(TrickplayMath.select("item", mapOf("src" to mapOf("320" to info320.copy(tileWidth = 0))), "src"))
    }

    @Test
    fun `decodes the Trickplay field from a Jellyfin item`() {
        val json = Json { ignoreUnknownKeys = true }
        val item =
            json.decodeFromString<BaseItemDto>(
                """
                {"Id":"abc","Trickplay":{"abc":{"320":{"Width":320,"Height":134,"TileWidth":10,
                "TileHeight":10,"ThumbnailCount":712,"Interval":10000,"Bandwidth":12345}}}}
                """.trimIndent(),
            )
        val track = TrickplayMath.select(item.id, item.trickplay, item.id)
        assertEquals(TrickplayTrack("abc", "abc", info320.copy(height = 134, thumbnailCount = 712, bandwidth = 12345)), track)
    }
}
