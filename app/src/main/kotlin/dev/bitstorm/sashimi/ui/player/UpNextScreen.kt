package dev.bitstorm.sashimi.ui.player

import android.animation.ValueAnimator
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.R
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.playback.UpNextState
import dev.bitstorm.sashimi.ui.components.LocalShowReviewRatings
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiBackground
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.util.Formatting

/** The system's "remove animations" / animator-duration-scale 0. */
private fun reduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

/**
 * Full-screen Up Next, shown over the ended player: the next episode's still,
 * blurred, behind a crisp card of it, with Play (and its countdown), Skip and
 * Cancel. Once cancelled: Play, Replay and Done.
 *
 * D-pad ready: Play takes focus first, every button shows a focus ring, and
 * Back cancels (then leaves, once cancelled).
 */
@Composable
internal fun UpNextScreen(
    upNext: UpNextUi,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
) {
    val state = upNext.state
    val still = reduceMotion()

    BackHandler { if (state.cancelled) onDone() else onCancel() }

    // Gentle entrance: fade plus a slight scale up. Nothing under reduced motion.
    val visible = remember { MutableTransitionState(still) }
    visible.targetState = true

    Box(
        Modifier
            .fillMaxSize()
            // Swallow taps on empty space: underneath is the player, whose
            // tap-to-show-chrome would otherwise fire through this screen.
            .pointerInput(Unit) { detectTapGestures { } }
            .background(SashimiBackground),
    ) {
        Backdrop(upNext.artwork, still)
        AnimatedVisibility(
            visibleState = visible,
            enter = if (still) EnterTransition.None else fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.96f),
        ) {
            Content(upNext, still, onPlay, onSkip, onCancel, onReplay, onDone)
        }
    }
}

/** The still, full-bleed: blurred where the platform can (API 31+), and darkened for legibility. */
@Composable
private fun Backdrop(
    artwork: Any?,
    still: Boolean,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Crossfade(targetState = artwork, animationSpec = if (still) snap() else tween(450), label = "upNextBackdrop") { model ->
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (canBlur) Modifier.blur(36.dp) else Modifier),
        )
    }
    // Without a blur the picture is busy, so the scrim does more of the work.
    val top = if (canBlur) 0.45f else 0.7f
    val bottom = if (canBlur) 0.92f else 0.96f
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = top), Color.Black.copy(alpha = bottom)))),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))),
    )
}

@Composable
private fun Content(
    upNext: UpNextUi,
    still: Boolean,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
) {
    val state = upNext.state
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Side by side wherever there is the width for it (landscape, tablets,
        // TV); stacked on a portrait phone.
        val wide = maxWidth >= 560.dp && maxWidth > maxHeight
        val scroll = rememberScrollState()
        // Never taller than the screen allows, so the text keeps its room.
        val cardMax = (maxHeight - 32.dp) * 16f / 9f
        if (wide) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 1100.dp).verticalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(upNext, still, Modifier.weight(0.46f).widthIn(max = cardMax))
                Column(Modifier.weight(0.54f)) {
                    Details(state.episode, still)
                    Spacer(Modifier.height(24.dp))
                    Buttons(state, onPlay, onSkip, onCancel, onReplay, onDone)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.Center,
            ) {
                Thumbnail(upNext, still, Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Details(state.episode, still)
                Spacer(Modifier.height(24.dp))
                Buttons(state, onPlay, onSkip, onCancel, onReplay, onDone)
            }
        }
    }
}

@Composable
private fun Thumbnail(
    upNext: UpNextUi,
    still: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .shadow(elevation = 24.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(SashimiCard)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape),
    ) {
        Crossfade(targetState = upNext.artwork, animationSpec = if (still) snap() else tween(350), label = "upNextCard") { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Details(
    episode: BaseItemDto,
    still: Boolean,
) {
    // Cross-fades when Skip changes the episode.
    AnimatedContent(
        targetState = episode,
        contentKey = { it.id },
        transitionSpec = {
            if (still) EnterTransition.None togetherWith fadeOut(snap()) else fadeIn(tween(300)) togetherWith fadeOut(tween(200))
        },
        label = "upNextDetails",
    ) { ep ->
        Column {
            Text(
                "UP NEXT",
                color = SashimiAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    episodeNumber(ep)?.let {
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.7f))) { append(it) }
                        append("  ")
                    }
                    append(ep.name)
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(ep)
            ep.overview?.let(Formatting::stripUrls)?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "S2 · E5", or null when the numbering is unknown. */
private fun episodeNumber(ep: BaseItemDto): String? {
    val s = ep.parentIndexNumber
    val e = ep.indexNumber
    return when {
        s != null && e != null -> "S$s · E$e"
        e != null -> "E$e"
        else -> null
    }
}

/** Runtime · TMDb rating, as the detail page shows it. */
@Composable
private fun MetaLine(ep: BaseItemDto) {
    val runtime = ep.runTimeTicks?.takeIf { it > 0 }?.let(Formatting::runtime)
    val rating = ep.communityRating?.takeIf { it > 0 && LocalShowReviewRatings.current }
    if (runtime == null && rating == null) return
    Row(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val secondary = Color.White.copy(alpha = 0.75f)
        runtime?.let { Text(it, color = secondary, fontSize = 14.sp) }
        if (runtime != null && rating != null) Text("·", color = secondary, fontSize = 14.sp)
        rating?.let {
            Image(
                painter = painterResource(R.drawable.tmdb_logo),
                contentDescription = "TMDb",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(14.dp),
            )
            Text("%.1f".format(it), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(
    state: UpNextState,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // Default focus on Play, for a remote or keyboard.
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    // Cancel and a Skip that vanishes both remove the focused button: hand focus back to Play.
    LaunchedEffect(state.cancelled, state.showSkip) { runCatching { playFocus.requestFocus() } }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayButton(state, onPlay, Modifier.focusRequester(playFocus))
        if (state.cancelled) {
            FocusRinged { source, mod ->
                FilledTonalButton(onClick = onReplay, interactionSource = source, modifier = mod, contentPadding = PillPadding) {
                    Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Replay", fontSize = 16.sp)
                }
            }
            FocusRinged { source, mod ->
                OutlinedButton(
                    onClick = onDone,
                    interactionSource = source,
                    modifier = mod,
                    contentPadding = PillPadding,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Done", fontSize = 16.sp)
                }
            }
        } else {
            if (state.showSkip) {
                FocusRinged { source, mod ->
                    FilledTonalButton(onClick = onSkip, interactionSource = source, modifier = mod, contentPadding = PillPadding) {
                        Text("Skip", fontSize = 16.sp)
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            FocusRinged { source, mod ->
                OutlinedButton(
                    onClick = onCancel,
                    interactionSource = source,
                    modifier = mod,
                    contentPadding = PillPadding,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Cancel", fontSize = 16.sp)
                }
            }
        }
    }
}

private val PillPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/**
 * Wraps a button with a visible focus ring and a small lift, since Material's
 * own focus state layer is too faint to follow with a remote across a room.
 */
@Composable
private fun FocusRinged(content: @Composable (MutableInteractionSource, Modifier) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hasFocus by source.collectIsFocusedAsState()
    // Play holds focus from the start so a remote works at once; on a touch
    // screen that focus is invisible, so the ring only shows for keys.
    val focused = hasFocus && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (focused && !reduceMotion()) 1.05f else 1f, label = "focusScale")
    val ring = if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier
    content(source, Modifier.scale(scale).then(ring))
}

/**
 * The Play pill. With a countdown, an accent fill sweeps across it left to right
 * as the seconds run out, with the seconds left beside the label; at the far
 * edge the episode starts. Without one it is a solid accent pill.
 */
@Composable
private fun PlayButton(
    state: UpNextState,
    onPlay: () -> Unit,
    modifier: Modifier,
) {
    val shape: Shape = RoundedCornerShape(50)
    // Ticks come every 100 ms; animating between them makes the sweep continuous.
    // Under reduced motion it simply steps with the ticks.
    val target = state.progress
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion() || target == 0f) snap() else tween(durationMillis = 110, easing = LinearEasing),
        label = "upNextFill",
    )
    val seconds = state.secondsRemaining
    FocusRinged { source, ringed ->
        Button(
            onClick = onPlay,
            interactionSource = source,
            shape = shape,
            contentPadding = PillPadding,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (state.hasCountdown) Color.Transparent else SashimiAccent,
                    contentColor = Color.White,
                ),
            modifier =
                modifier
                    .then(ringed)
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .then(
                        if (state.hasCountdown) {
                            Modifier.drawBehind {
                                // The track: the accent, dimmed; the fill: the accent.
                                drawRect(SashimiAccent.copy(alpha = 0.35f))
                                drawRect(SashimiAccent, size = Size(size.width * progress, size.height))
                            }
                        } else {
                            Modifier
                        },
                    ).semantics {
                        contentDescription = seconds?.let { "Play, starts in $it seconds" } ?: "Play"
                    },
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("Play", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (seconds != null) {
                Spacer(Modifier.size(10.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$seconds", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
