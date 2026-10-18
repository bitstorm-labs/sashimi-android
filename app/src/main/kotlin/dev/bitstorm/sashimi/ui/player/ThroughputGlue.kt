package dev.bitstorm.sashimi.ui.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.BandwidthMeter
import dev.bitstorm.sashimi.core.playback.ThroughputMeter

/**
 * The player's bandwidth meter with the [ThroughputMeter] listening in on
 * every network transfer. ExoPlayer hands the meter's transfer listener to
 * each DataSource it creates (progressive and HLS alike), which is the only
 * place that sees bytes and time per transfer. The delegate keeps doing its
 * own estimate; only network transfers reach the meter, so a download played
 * from disk never counts as a link reading.
 */
@UnstableApi
class MeteringBandwidthMeter(
    private val delegate: BandwidthMeter,
    private val meter: ThroughputMeter,
    private val onReading: (ThroughputMeter.Reading) -> Unit,
) : BandwidthMeter by delegate {
    private val listener =
        object : TransferListener {
            override fun onTransferInitializing(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                delegate.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
            }

            override fun onTransferStart(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                delegate.transferListener?.onTransferStart(source, dataSpec, isNetwork)
                if (isNetwork) meter.transferStarted(source, SystemClock.elapsedRealtimeNanos())
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                delegate.transferListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
                if (isNetwork) meter.bytesTransferred(source, bytesTransferred, SystemClock.elapsedRealtimeNanos())?.let(onReading)
            }

            override fun onTransferEnd(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                delegate.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
                if (isNetwork) meter.transferEnded(source)
            }
        }

    override fun getTransferListener(): TransferListener = listener

    // Kotlin's `by` does not forward a Java interface's default methods.
    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs
}

/**
 * The player's load control (the default one), telling the [ThroughputMeter] whenever it
 * declines to load more. A progressive direct play keeps one transfer open
 * for the whole file and simply stops reading while the buffer is full; what
 * it reads after that arrives at the playback rate, not the link's.
 */
@UnstableApi
class PauseObservingLoadControl(
    private val onPause: () -> Unit,
) : DefaultLoadControl() {
    // A subclass rather than `by` delegation: delegation would not forward
    // LoadControl's Java default methods (shouldStartPlayback, onPrepared...),
    // leaving the player on the interface's stubs.
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        super.shouldContinueLoading(parameters).also { if (!it) onPause() }
}
