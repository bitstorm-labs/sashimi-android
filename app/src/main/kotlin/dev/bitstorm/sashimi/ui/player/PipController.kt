package dev.bitstorm.sashimi.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.OnUserLeaveHintProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dev.bitstorm.sashimi.R

/**
 * Picture-in-picture glue for the player route.
 *
 * Keeps the activity's PictureInPictureParams in step with playback so that:
 *  - below API 33 the PiP window has play/pause and seek RemoteActions (from
 *    33 the system sources them from the MediaSession instead);
 *  - on API 31+ pressing Home auto-enters PiP while playing, and not while
 *    paused;
 *  - below API 31, onUserLeaveHint enters PiP under the same condition.
 *
 * Entering PiP moves the activity to PAUSED, not STOPPED, so the player's
 * ON_STOP pause does not fire. Closing the PiP window does stop the activity,
 * which is what pauses playback then.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PipEffects(
    activity: Activity,
    player: Player,
    state: PlayerUiState,
) {
    val enterOnLeave = state.enterPipOnLeave

    // Push fresh params whenever anything they encode changes. Cheap, and the
    // only way the RemoteActions' play/pause icon follows the player.
    LaunchedEffect(state.showPlayButton, state.videoWidth, state.videoHeight, enterOnLeave) {
        runCatching { activity.setPictureInPictureParams(activity.pipParams(state)) }
    }

    // Leaving the player route: stop auto-entering PiP from other screens.
    DisposableEffect(activity) {
        onDispose {
            if (Build.VERSION.SDK_INT >= PipActions.AUTO_ENTER_API) {
                runCatching {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
                    )
                }
            }
        }
    }

    // Below API 31 there is no auto-enter; Home arrives as onUserLeaveHint.
    val currentState by rememberUpdatedState(state)
    DisposableEffect(activity) {
        val provider = activity as? OnUserLeaveHintProvider
        val onLeave =
            Runnable {
                if (currentState.enterPipOnLeave && Build.VERSION.SDK_INT < PipActions.AUTO_ENTER_API) {
                    activity.enterPip(currentState)
                }
            }
        provider?.addOnUserLeaveHintListener(onLeave)
        onDispose { provider?.removeOnUserLeaveHintListener(onLeave) }
    }

    // RemoteAction taps arrive as broadcasts. Only needed where we supply them.
    if (Build.VERSION.SDK_INT < PipActions.SESSION_CONTROLS_API) {
        DisposableEffect(activity, player) {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action != ACTION_PIP_CONTROL) return
                        when (intent.getStringExtra(EXTRA_ACTION)) {
                            PipAction.PLAY.name, PipAction.PAUSE.name -> Util.handlePlayPauseButtonAction(player)
                            PipAction.SEEK_BACK.name -> player.seekBack()
                            PipAction.SEEK_FORWARD.name -> player.seekForward()
                        }
                    }
                }
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(ACTION_PIP_CONTROL),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { runCatching { activity.unregisterReceiver(receiver) } }
        }
    }
}

/** Manual PiP entry, from the player's PiP button. */
internal fun Activity.enterPip(state: PlayerUiState) {
    runCatching { enterPictureInPictureMode(pipParams(state)) }
}

private val PlayerUiState.enterPipOnLeave: Boolean
    get() = PipActions.shouldEnterOnLeave(showPlayButton = showPlayButton, isLoading = isLoading, hasError = error != null)

private fun Activity.pipParams(state: PlayerUiState): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspect(state.videoWidth, state.videoHeight))
    val actions = PipActions.actionsFor(state.showPlayButton, Build.VERSION.SDK_INT)
    if (actions.isNotEmpty()) builder.setActions(actions.map { remoteAction(it) })
    if (Build.VERSION.SDK_INT >= PipActions.AUTO_ENTER_API) builder.setAutoEnterEnabled(state.enterPipOnLeave)
    return builder.build()
}

/** PiP rejects ratios outside 1:2.39 to 2.39:1, so clamp ultra-wide video. */
private fun pipAspect(
    videoWidth: Int,
    videoHeight: Int,
): Rational =
    if (videoWidth > 0 && videoHeight > 0) {
        Rational(videoWidth.coerceAtMost(videoHeight * 239 / 100), videoHeight)
    } else {
        Rational(16, 9)
    }

private fun Activity.remoteAction(action: PipAction): RemoteAction {
    val (icon, label) =
        when (action) {
            PipAction.SEEK_BACK -> R.drawable.ic_pip_rewind to "Rewind 10 seconds"
            PipAction.PLAY -> R.drawable.ic_pip_play to "Play"
            PipAction.PAUSE -> R.drawable.ic_pip_pause to "Pause"
            PipAction.SEEK_FORWARD -> R.drawable.ic_pip_forward to "Forward 10 seconds"
        }
    val intent =
        Intent(ACTION_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_ACTION, action.name)
    val pending =
        PendingIntent.getBroadcast(
            this,
            action.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
}

private const val ACTION_PIP_CONTROL = "dev.bitstorm.sashimi.action.PIP_CONTROL"
private const val EXTRA_ACTION = "action"
