package dev.bitstorm.sashimi.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runtime POST_NOTIFICATIONS gate for the download UI. Android 13+ requires the
 * permission at runtime for the foreground download-progress notification to
 * show; it is declared in the manifest but must still be requested.
 *
 * Returns a `run(proceed)` lambda: on API < 33 (or once granted) it invokes
 * [proceed] immediately; on 33+ without the permission it requests it, then
 * runs [proceed] REGARDLESS of the user's choice — a denial must never block
 * the download itself, it only means no progress notification.
 */
@Composable
fun rememberNotificationPermissionGate(): (proceed: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Denial does not block the download; proceed either way.
            pending.value?.invoke()
            pending.value = null
        }
    return remember(launcher) {
        { proceed ->
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                proceed()
            } else {
                pending.value = proceed
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
