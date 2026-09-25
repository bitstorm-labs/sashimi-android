package dev.bitstorm.sashimi.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import dev.bitstorm.sashimi.core.trickplay.TrickplayMath
import dev.bitstorm.sashimi.core.trickplay.TrickplayTrack
import dev.bitstorm.sashimi.di.ServiceLocator

private val ThumbWidth = 160.dp

/** Slider thumb half-width: Material3's track starts/ends this far in from the edges. */
private val TrackInset = 10.dp

/**
 * Scrub thumbnail (Jellyfin trickplay) shown above the scrubber while the user
 * drags it, centred over the scrub position and clamped to the bar. Draws
 * nothing when [track] is null, the duration is unknown, or the sheet has not
 * loaded yet -- an empty frame reads as a broken thumbnail.
 *
 * Sheets load through Coil with the token in an `X-Emby-Token` header (the
 * trickplay endpoint requires auth; the artwork endpoints do not).
 */
@Composable
fun TrickplayPreview(
    track: TrickplayTrack?,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (track == null || durationMs <= 0) return
    val info = track.info
    val frame = TrickplayMath.frameAt(info, positionMs)
    val sheet = rememberTrickplaySheet(track, frame.sheetIndex) ?: return

    BoxWithConstraints(modifier) {
        val thumbHeight = ThumbWidth * info.height.toFloat() / info.width.toFloat()
        val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val knobX = TrackInset + (maxWidth - TrackInset * 2) * fraction
        val x = (knobX - ThumbWidth / 2).coerceIn(0.dp, (maxWidth - ThumbWidth).coerceAtLeast(0.dp))
        Box(
            Modifier
                .offset(x = x)
                .size(ThumbWidth, thumbHeight)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
        ) {
            val cell = frame.cell
            // A sheet smaller than the cell (e.g. a short final sheet) has no
            // frame here; draw nothing rather than a garbage crop.
            if (cell.x + cell.width <= sheet.width && cell.y + cell.height <= sheet.height) {
                Canvas(Modifier.size(ThumbWidth, thumbHeight)) {
                    drawImage(
                        image = sheet,
                        srcOffset = IntOffset(cell.x, cell.y),
                        srcSize = IntSize(cell.width, cell.height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            }
        }
    }
}

/** Loads one tile sheet at full size; null until it arrives or if it fails. */
@Composable
private fun rememberTrickplaySheet(
    track: TrickplayTrack,
    sheetIndex: Int,
): ImageBitmap? {
    val context = LocalContext.current
    val key = Triple(track.itemId, track.info.width, sheetIndex)
    var loaded by remember(track) { mutableStateOf<Pair<Triple<String, Int, Int>, ImageBitmap>?>(null) }
    LaunchedEffect(key) {
        val client = ServiceLocator.client
        val url = client.trickplayTileURL(track.itemId, track.info.width, sheetIndex, track.mediaSourceId) ?: return@LaunchedEffect
        val token = client.currentAccessToken ?: return@LaunchedEffect
        val request =
            ImageRequest.Builder(context)
                .data(url)
                .addHeader("X-Emby-Token", token)
                // Crop maths is in the sheet's own pixels, so it must not be downsampled.
                .size(Size.ORIGINAL)
                // A 10x10 sheet of 320px thumbnails is 3200x1800: RGB_565 halves
                // that (JPEG has no alpha), and it stays out of the memory cache
                // so it cannot evict the poster art. The disk cache still makes
                // re-visiting a sheet cheap.
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null) loaded = key to bitmap.asImageBitmap()
    }
    // Only the sheet for THIS position: a neighbouring sheet's cell is the wrong frame.
    return loaded?.takeIf { it.first == key }?.second
}
