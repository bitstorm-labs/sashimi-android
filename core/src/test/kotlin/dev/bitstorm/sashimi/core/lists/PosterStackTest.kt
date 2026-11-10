package dev.bitstorm.sashimi.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The poster-stack cover's geometry (#244, list-covers mockup option A). */
class PosterStackTest {
    @Test
    fun `one layer per poster, at most three, back to front`() {
        assertEquals(listOf(0), PosterStack.layers(1, fanned = false).map { it.depth })
        assertEquals(listOf(1, 0), PosterStack.layers(2, fanned = false).map { it.depth })
        assertEquals(listOf(2, 1, 0), PosterStack.layers(3, fanned = false).map { it.depth })
        assertEquals(listOf(2, 1, 0), PosterStack.layers(7, fanned = false).map { it.depth })
        // An empty list still draws one (placeholder) poster.
        assertEquals(listOf(0), PosterStack.layers(0, fanned = false).map { it.depth })
    }

    @Test
    fun `the front poster is full size, sharp and straight`() {
        val front = PosterStack.layers(3, fanned = false).last()
        assertEquals(1f, front.scale)
        assertEquals(0f, front.rotation)
        assertEquals(1f, front.alpha)
        assertEquals(0f, front.offsetX)
    }

    @Test
    fun `the next two sit smaller, further right and turned about 3 and 7 degrees, the back one faded`() {
        val (back, middle) = PosterStack.layers(3, fanned = false)
        assertEquals(3f, middle.rotation)
        assertEquals(7f, back.rotation)
        assertTrue(middle.scale < 1f && back.scale < middle.scale)
        assertTrue(middle.offsetX > 0f && back.offsetX > middle.offsetX)
        assertEquals(1f, middle.alpha)
        assertTrue(back.alpha < 1f)
    }

    @Test
    fun `focus fans the stack out a little`() {
        val rest = PosterStack.layers(3, fanned = false)
        val fanned = PosterStack.layers(3, fanned = true)
        for (i in 0..1) {
            assertTrue(fanned[i].offsetX > rest[i].offsetX)
            assertTrue(fanned[i].rotation > rest[i].rotation)
        }
        assertEquals(rest[2], fanned[2])
    }

    @Test
    fun `every layer fits in the cover's width`() {
        listOf(false, true).forEach { fanned ->
            PosterStack.layers(3, fanned).forEach { layer ->
                assertTrue(layer.offsetX + layer.scale <= PosterStack.WIDTH_IN_POSTERS)
            }
        }
    }
}
