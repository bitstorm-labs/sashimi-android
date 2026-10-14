package dev.bitstorm.sashimi.core.herotrailer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class HeroTrailerResolverTest {
    @Test
    fun `a trailerless title is only looked up once`() =
        runBlocking {
            var calls = 0
            val resolver =
                HeroTrailerResolver {
                    calls++
                    null
                }
            assertNull(resolver.resolve("s1"))
            assertNull(resolver.resolve("s1"))
            assertEquals(1, calls)
        }

    @Test
    fun `a found trailer is cached`() =
        runBlocking {
            var calls = 0
            val resolver =
                HeroTrailerResolver {
                    calls++
                    HeroTrailerSource("t-$it", 90.0)
                }
            assertEquals(HeroTrailerSource("t-m", 90.0), resolver.resolve("m"))
            assertEquals(HeroTrailerSource("t-m", 90.0), resolver.resolve("m"))
            assertEquals(1, calls)
        }

    @Test
    fun `a failed lookup is asked again next time`() =
        runBlocking {
            var calls = 0
            val resolver =
                HeroTrailerResolver {
                    calls++
                    if (calls == 1) throw IOException("offline") else HeroTrailerSource("t", null)
                }
            assertNull(resolver.resolve("m"))
            assertEquals(HeroTrailerSource("t", null), resolver.resolve("m"))
            assertEquals(2, calls)
        }
}
