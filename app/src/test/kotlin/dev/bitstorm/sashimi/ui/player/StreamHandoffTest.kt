package dev.bitstorm.sashimi.ui.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Apple TV bug (sashimi-apple #886): a stream the app had let go of kept
 * fetching segments after its transcode was stopped, so the server restarted
 * ffmpeg for a dead session and its audio played on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamHandoffTest {
    private lateinit var player: ExoPlayer
    private val stopped = mutableListOf<String>()

    /** What the player held at the moment the server was told to stop. */
    private var itemsWhenStopped = -1
    private var stateWhenStopped = -1

    private lateinit var handoff: StreamHandoff

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).setLooper(Looper.getMainLooper()).build()
        handoff =
            StreamHandoff(player) { session ->
                itemsWhenStopped = player.mediaItemCount
                stateWhenStopped = player.playbackState
                stopped += session
            }
        player.setMediaItem(MediaItem.fromUri("http://server.invalid/videos/item/main.m3u8?PlaySessionId=old"))
        player.prepare()
        player.playWhenReady = true
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun `the outgoing stream is unloaded before its transcode is stopped`() =
        runTest {
            handoff.retire(priorTranscodeSession = "old", positionMs = 60_000)

            assertEquals(listOf("old"), stopped)
            // Still loaded, the player would ask the server for the next
            // segment of a session it had just been told to end.
            assertEquals(0, itemsWhenStopped)
            assertEquals(Player.STATE_IDLE, stateWhenStopped)
        }

    @Test
    fun `a retired stream has nothing left to play`() =
        runTest {
            handoff.retire(priorTranscodeSession = null, positionMs = 60_000)

            // A media button, the PiP play action or audio focus coming back
            // all end up here; none of them may bring the old stream back.
            player.prepare()
            player.play()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, player.mediaItemCount)
            assertTrue(player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE)
            assertTrue(!player.isPlaying)
        }

    @Test
    fun `the position is held while no stream is loaded`() =
        runTest {
            handoff.retire(priorTranscodeSession = null, positionMs = 60_000)
            assertEquals(60_000L, handoff.heldPositionMs)

            handoff.handOver()
            assertNull(handoff.heldPositionMs)
        }
}
