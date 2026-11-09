package dev.bitstorm.sashimi.ui.requests

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.bitstorm.sashimi.MainActivity

/** Plex's sign-in page in a Custom Tab; a plain browser if no Custom Tabs provider is installed. */
fun openPlexSignIn(
    context: Context,
    url: String,
) {
    val uri = url.toUri()
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
        .recoverCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Brings the app back over Plex's sign-in page once the PIN is linked. Plex's page
 * cannot send the browser back to a custom scheme (#232), so the app does it:
 * CLEAR_TOP closes the Custom Tab sitting above MainActivity in this task. If the
 * platform blocks the start, the person returns with Back and finds the app signed in.
 */
fun returnToApp(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }
}
