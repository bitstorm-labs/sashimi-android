package dev.bitstorm.sashimi.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.bitstorm.sashimi.core.playback.AudioTrack
import dev.bitstorm.sashimi.core.playback.QualityOption
import dev.bitstorm.sashimi.core.playback.StreamInfo
import dev.bitstorm.sashimi.core.playback.StreamMethod
import kotlinx.coroutines.delay

private val SpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Full-screen Media3 player route. Custom chrome (Media3's built-in controller
 * is disabled) ported from MobilePlayerView: tap toggles a 5s auto-hiding
 * overlay with a close button, title/subtitle, the stream-info chip, a settings
 * sheet (Quality / Audio / Subtitles / Speed), a Skip Intro/Credits button, PiP,
 * and transport controls. Auto-play-next and resume are driven by the VM.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    itemId: String,
    startFromBeginning: Boolean,
    trailerItemId: String?,
    onExit: () -> Unit,
) {
    val app = LocalActivity().application
    val vm: PlayerViewModel =
        viewModel(
            key = "player-$itemId-${trailerItemId ?: ""}",
            factory = PlayerViewModel.Factory(app, itemId, startFromBeginning, trailerItemId),
        )
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.playbackEnded) {
        if (state.playbackEnded) onExit()
    }

    var overlayVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    // Auto-hide the overlay 5s after it is shown (while playing).
    LaunchedEffect(overlayVisible, showSettings) {
        if (overlayVisible && !showSettings) {
            delay(5_000)
            overlayVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { overlayVisible = !overlayVisible },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = vm.player
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }

        state.error?.let { err ->
            Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(err, color = Color.White, fontSize = 15.sp)
            }
        }

        // Skip Intro/Credits — always tappable, independent of the overlay.
        state.skipSegment?.let { segment ->
            SkipButton(
                label = skipLabel(segment.type),
                onClick = vm::skipCurrentSegment,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible && !state.isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerOverlay(
                state = state,
                vm = vm,
                onClose = onExit,
                onOpenSettings = { showSettings = true },
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onQuality = vm::selectQuality,
            onAudio = vm::selectAudioTrack,
            onSubtitle = vm::selectSubtitle,
            onSpeed = vm::setSpeed,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerOverlay(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        // Top bar: close, title/subtitle, stream chip, PiP, settings.
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    state.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.streamInfo?.let { StreamChip(it) }
            }
            val activity = LocalActivity()
            IconButton(onClick = { activity.enterPip(state.videoWidth, state.videoHeight) }) {
                Icon(Icons.Filled.PictureInPicture, contentDescription = "Picture in picture", tint = Color.White)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        // Center transport: -10s, play/pause, +10s.
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.player.seekBack() }) {
                Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10 seconds", tint = Color.White, modifier = Modifier.size(40.dp))
            }
            PlayPauseButton(vm)
            IconButton(onClick = { vm.player.seekForward() }) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // Bottom scrubber.
        Scrubber(vm, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayPauseButton(vm: PlayerViewModel) {
    var isPlaying by remember { mutableStateOf(vm.player.isPlaying) }
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = vm.player.isPlaying
            delay(200)
        }
    }
    IconButton(onClick = { if (vm.player.isPlaying) vm.player.pause() else vm.player.play() }) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Scrubber(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!scrubbing) {
                positionMs = vm.player.currentPosition.coerceAtLeast(0)
                durationMs = vm.player.duration.takeIf { it > 0 } ?: 0
            }
            delay(500)
        }
    }

    Column(modifier) {
        Slider(
            value = if (scrubbing) scrubValue else positionMs.toFloat(),
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                vm.player.seekTo(scrubValue.toLong())
                positionMs = scrubValue.toLong()
                scrubbing = false
            },
            valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF8C5CC7),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (scrubbing) scrubValue.toLong() else positionMs), color = Color.White, fontSize = 12.sp)
            Text(formatTime(durationMs), color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StreamChip(info: StreamInfo) {
    val dot =
        when (info.method) {
            StreamMethod.DIRECT_PLAY -> Color(0xFF4CAF50)
            StreamMethod.DIRECT_STREAM -> Color(0xFFFFC107)
            StreamMethod.TRANSCODE -> Color(0xFFFF9800)
        }
    Row(
        modifier =
            Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        val text = info.detail?.let { "${info.label} · $it" } ?: info.label
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun SkipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onQuality: (QualityOption) -> Unit,
    onAudio: (AudioTrack) -> Unit,
    onSubtitle: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1F1F24)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsGroup(Icons.Filled.Settings, "Quality") {
                    QualityOption.entries.forEach { q ->
                        ChoiceChip(q.label, selected = state.selectedQuality == q) { onQuality(q) }
                    }
                }
                if (state.audioTracks.size > 1) {
                    SettingsGroup(Icons.AutoMirrored.Filled.VolumeUp, "Audio") {
                        state.audioTracks.forEach { track ->
                            ChoiceChip(track.displayName, selected = state.selectedAudioIndex == track.index) { onAudio(track) }
                        }
                    }
                }
                if (state.subtitleTracks.size > 1) {
                    SettingsGroup(Icons.Filled.Subtitles, "Subtitles") {
                        state.subtitleTracks.forEach { track ->
                            val idx = if (track.isOff) PlayerUiState.OFF_SUBTITLE else track.index
                            ChoiceChip(track.displayName, selected = state.selectedSubtitleIndex == idx) { onSubtitle(idx) }
                        }
                    }
                }
                SettingsGroup(Icons.Filled.Speed, "Speed") {
                    SpeedOptions.forEach { s ->
                        ChoiceChip(if (s == 1f) "1×" else "$s×", selected = state.speed == s) { onSpeed(s) }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsGroup(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private fun skipLabel(type: dev.bitstorm.sashimi.core.model.MediaSegmentType): String =
    when (type) {
        dev.bitstorm.sashimi.core.model.MediaSegmentType.INTRO -> "Skip Intro"
        dev.bitstorm.sashimi.core.model.MediaSegmentType.OUTRO -> "Skip Credits"
        dev.bitstorm.sashimi.core.model.MediaSegmentType.RECAP -> "Skip Recap"
        dev.bitstorm.sashimi.core.model.MediaSegmentType.PREVIEW -> "Skip Preview"
        else -> "Skip"
    }

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// MARK: - Activity helpers (PiP)

@Composable
private fun LocalActivity(): Activity {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { context.findActivity() }
}

private fun Context.findActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("PlayerScreen must be hosted in an Activity")
}

private fun Activity.enterPip(
    videoWidth: Int,
    videoHeight: Int,
) {
    val aspect =
        if (videoWidth > 0 && videoHeight > 0) {
            Rational(videoWidth.coerceAtMost(videoHeight * 239 / 100), videoHeight)
        } else {
            Rational(16, 9)
        }
    runCatching {
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(aspect).build())
    }
}
