package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.bitstorm.sashimi.ui.theme.SashimiBackground

/*
 * The Watchlist Spotlight page's backdrop treatment (list-layouts option A),
 * shared by the Spotlight list pages and every title page (owner, 2026-11-11:
 * "match the Spotlight page's coverage"). The picture itself stays with each
 * caller, since a title page also plays its hero trailer in that frame; these
 * are the gradients that blend it into the page, drawn over picture and
 * trailer alike.
 */

/**
 * Wide: the picture fills the page, bright across the right two-thirds, and
 * these two washes darken it to the page colour on the left (behind the text)
 * and at the bottom (behind the row).
 */
@Composable
fun SpotlightWashes(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(
            Brush.horizontalGradient(0.3f to SashimiBackground, 0.75f to Color.Transparent),
        ),
    )
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(0.4f to Color.Transparent, 0.75f to SashimiBackground),
        ),
    )
}

/**
 * Portrait (a phone): the picture covers the top of the page and fades into
 * the content below it, clear down to about a third of its height.
 */
@Composable
fun SpotlightBottomFade(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to SashimiBackground)))
}

/**
 * Keeps the status bar and the Back arrow legible where the portrait picture
 * runs up under a transparent top bar.
 */
@Composable
fun SpotlightTopScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent)))
}
