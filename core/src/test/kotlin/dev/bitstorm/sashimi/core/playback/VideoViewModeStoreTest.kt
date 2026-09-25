package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeViewModeDefaultPersistence(var stored: String? = null) : ViewModeDefaultPersistence {
    var saves = 0

    override fun load(): String? = stored

    override fun save(key: String) {
        stored = key
        saves++
    }
}

class VideoViewModeStoreTest {
    @Test
    fun `stored keys match the Apple and Roku clients`() {
        assertEquals(listOf("normal", "zoom", "stretch"), VideoViewMode.entries.map { it.key })
        assertEquals(listOf("Normal", "Zoom", "Stretch"), VideoViewMode.entries.map { it.label })
    }

    @Test
    fun `a fresh install uses Normal`() {
        val store = VideoViewModeStore(FakeViewModeDefaultPersistence())
        assertEquals(VideoViewMode.NORMAL, store.defaultMode.value)
        assertEquals(VideoViewMode.NORMAL, store.activeMode)
        assertNull(store.sessionMode.value)
    }

    @Test
    fun `an unknown stored value falls back to Normal`() {
        val store = VideoViewModeStore(FakeViewModeDefaultPersistence("fisheye"))
        assertEquals(VideoViewMode.NORMAL, store.activeMode)
    }

    @Test
    fun `the saved default applies when there is no session pick`() {
        val store = VideoViewModeStore(FakeViewModeDefaultPersistence("zoom"))
        assertEquals(VideoViewMode.ZOOM, store.activeMode)
    }

    @Test
    fun `a session pick overrides the default without persisting`() {
        val persistence = FakeViewModeDefaultPersistence("zoom")
        val store = VideoViewModeStore(persistence)
        store.selectForSession(VideoViewMode.STRETCH)
        assertEquals(VideoViewMode.STRETCH, store.activeMode)
        assertEquals(VideoViewMode.ZOOM, store.defaultMode.value)
        assertEquals("zoom", persistence.stored)
        assertEquals(0, persistence.saves)
    }

    @Test
    fun `a session pick survives to the next video`() {
        val store = VideoViewModeStore(FakeViewModeDefaultPersistence())
        store.selectForSession(VideoViewMode.ZOOM)
        // A later video reads the same process-wide store.
        assertEquals(VideoViewMode.ZOOM, store.activeMode)
    }

    @Test
    fun `the session pick does not outlive the app session`() {
        val persistence = FakeViewModeDefaultPersistence()
        VideoViewModeStore(persistence).selectForSession(VideoViewMode.STRETCH)
        assertEquals(VideoViewMode.NORMAL, VideoViewModeStore(persistence).activeMode)
    }

    @Test
    fun `Use for All Videos saves the active mode and clears the session pick`() {
        val persistence = FakeViewModeDefaultPersistence()
        val store = VideoViewModeStore(persistence)
        store.selectForSession(VideoViewMode.ZOOM)
        store.useForAllVideos()
        assertEquals("zoom", persistence.stored)
        assertEquals(VideoViewMode.ZOOM, store.defaultMode.value)
        assertNull(store.sessionMode.value)
        assertEquals(VideoViewMode.ZOOM, store.activeMode)
        assertEquals(VideoViewMode.ZOOM, VideoViewModeStore(persistence).activeMode)
    }

    @Test
    fun `changing the default in Settings replaces the session pick`() {
        val persistence = FakeViewModeDefaultPersistence()
        val store = VideoViewModeStore(persistence)
        store.selectForSession(VideoViewMode.ZOOM)
        store.setDefault(VideoViewMode.STRETCH)
        assertNull(store.sessionMode.value)
        assertEquals(VideoViewMode.STRETCH, store.activeMode)
        assertEquals("stretch", persistence.stored)
    }

    @Test
    fun `resolve prefers the session pick`() {
        assertEquals(VideoViewMode.NORMAL, VideoViewModeStore.resolve(null, VideoViewMode.NORMAL))
        assertEquals(VideoViewMode.STRETCH, VideoViewModeStore.resolve(null, VideoViewMode.STRETCH))
        assertEquals(VideoViewMode.ZOOM, VideoViewModeStore.resolve(VideoViewMode.ZOOM, VideoViewMode.STRETCH))
    }
}
