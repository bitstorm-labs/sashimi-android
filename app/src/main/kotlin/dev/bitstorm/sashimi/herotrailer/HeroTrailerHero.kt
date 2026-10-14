package dev.bitstorm.sashimi.herotrailer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import dev.bitstorm.sashimi.R
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.di.ServiceLocator
import java.util.UUID

/**
 * Reports this detail screen to [HeroTrailerController] and returns its owner
 * id, for [HeroTrailerBackdrop]. Reporting only: every decision belongs to the
 * controller.
 *
 * The owner is a ViewModel scoped to the destination's back stack entry, for
 * the reason spelled out on `ThemeSongVisitEffect`: Navigation Compose disposes
 * a destination when something is pushed over it and composes it again on the
 * way back, so composition alone cannot tell "covered" from "gone". Composition
 * reports shown and hidden; the entry's `onCleared` reports dismissed.
 */
@Composable
fun rememberHeroTrailerOwner(
    item: BaseItemDto?,
    /** The route's pinned server, or null for the active one. */
    serverId: String?,
    isCompact: Boolean,
    /** False when this screen's hero has nowhere to show a trailer. */
    hasTrailerLayer: Boolean = true,
): String {
    val visit: HeroTrailerVisitViewModel = viewModel()
    val context = LocalContext.current
    if (item != null) {
        DisposableEffect(visit.owner, item.id, isCompact, hasTrailerLayer) {
            ServiceLocator.heroTrailers.screenShown(visit.owner, item.takeIf { hasTrailerLayer }, serverId, isCompact)
            onDispose {
                // An activity being rebuilt for a configuration change is not the
                // user leaving: the new composition reports shown again and the
                // running trailer is handed straight back.
                if (context.findActivity()?.isChangingConfigurations != true) {
                    ServiceLocator.heroTrailers.screenHidden(visit.owner)
                }
            }
        }
    }
    return visit.owner
}

class HeroTrailerVisitViewModel : ViewModel() {
    val owner: String = UUID.randomUUID().toString()

    override fun onCleared() {
        ServiceLocator.heroTrailers.screenDismissed(owner)
    }
}

/**
 * A detail hero with its trailer layer. The video is drawn *under* [image],
 * which fades out while the trailer plays and back in when it ends.
 *
 * Both layers stay mounted for the screen's whole life; only the player
 * attached to the video layer and the image's alpha change. Removing the video
 * view is what produced the Apple client's "blank spot" after a trailer, so this
 * never does it: when the clip ends the image is simply opaque again over a
 * shutter that is the hero's own background colour.
 *
 * [image] receives the modifier that carries the fade; apply it to everything
 * that belongs to the picture (the image and any darkening over it), and keep
 * gradients that blend the hero into the page outside it.
 */
@Composable
fun HeroTrailerBackdrop(
    owner: String,
    modifier: Modifier = Modifier,
    image: @Composable BoxScope.(fade: Modifier) -> Unit,
) {
    val controller = ServiceLocator.heroTrailers
    val activeOwner by controller.activeOwner.collectAsStateWithLifecycle()
    val player by controller.player.collectAsStateWithLifecycle()
    val isVisible by controller.isVisible.collectAsStateWithLifecycle()
    val isOwner = activeOwner == owner
    val imageAlpha by animateFloatAsState(
        targetValue = if (isOwner && isVisible) 0f else 1f,
        animationSpec = tween(HeroTrailerController.CROSS_FADE_MS.toInt()),
        label = "heroTrailerImageAlpha",
    )
    val shownPlayer = if (isOwner) player else null
    Box(modifier) {
        AndroidView(
            factory = { context ->
                // A throwaway parent so the layout's own layout params resolve.
                LayoutInflater.from(context).inflate(R.layout.hero_trailer_view, FrameLayout(context), false) as PlayerView
            },
            modifier = Modifier.matchParentSize(),
            update = { view -> if (view.player !== shownPlayer) view.player = shownPlayer },
            onRelease = { view -> view.player = null },
        )
        image(Modifier.graphicsLayer { alpha = imageAlpha })
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
