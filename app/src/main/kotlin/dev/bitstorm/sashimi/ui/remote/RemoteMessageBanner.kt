package dev.bitstorm.sashimi.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.remote.RemoteMessageBoard
import dev.bitstorm.sashimi.core.remote.ShownMessage
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary

/**
 * A message sent from the server dashboard (#161), drawn over every screen
 * including the player. It leaves after the sender's TimeoutMs (the board owns
 * the timer); a tap dismisses it early. Read out by TalkBack as it appears.
 */
@Composable
fun RemoteMessageBanner(
    board: RemoteMessageBoard,
    modifier: Modifier = Modifier,
) {
    val current by board.current.collectAsStateWithLifecycle()
    // Keep the last message so the exit animation still has text to fade out.
    val last = remember { LastMessage() }
    current?.let { last.value = it }
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut(),
        modifier = modifier.safeDrawingPadding().padding(16.dp),
    ) {
        val message = last.value ?: return@AnimatedVisibility
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BannerBackground)
                .clickable(onClickLabel = "Dismiss") { board.dismiss() }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            message.header?.let {
                Text(it, color = SashimiAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(message.text, color = SashimiTextPrimary, fontSize = 15.sp)
        }
    }
}

private class LastMessage {
    var value: ShownMessage? = null
}

/** Readable over bright video as well as the app's dark screens. */
private val BannerBackground = Color(0xE61F1F24)
