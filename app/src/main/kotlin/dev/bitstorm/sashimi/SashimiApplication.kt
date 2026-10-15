package dev.bitstorm.sashimi

import android.app.Application
import androidx.core.content.edit
import coil.imageLoader
import dev.bitstorm.sashimi.core.util.ImageCacheEpoch
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SashimiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Restore any saved session on launch (Swift did this in SessionManager.init).
        // Runs on IO, never Main: restoreSession() does EncryptedSharedPreferences
        // first-init (key generation) + disk reads synchronously, which must not
        // block the main thread during app startup.
        // The handler is the backstop, not the plan. This is a ROOT coroutine, so
        // an uncaught throw here goes to the default uncaught-exception handler
        // and kills the process during onCreate -- a launch crash loop rather
        // than a failed restore. SupervisorJob does not help: it isolates
        // sibling children, it does not swallow. Individual failure modes are
        // handled at their source (see EncryptedTokenStore.prefs); this ensures
        // that a mode nobody anticipated still degrades to "signed out".
        val restoreFailsafe =
            CoroutineExceptionHandler { _, _ ->
                // Deliberately silent: there is no logging anywhere in this app,
                // and leaving the session unrestored is the correct outcome --
                // the UI falls through to the sign-in screen on its own.
            }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + restoreFailsafe).launch {
            ServiceLocator.session.restoreSession()
        }
        clearStaleImageCacheOnce()
    }

    /**
     * Drops Coil's caches once per [ImageCacheEpoch] bump, so pictures cached
     * before image URLs carried the server's tag are refetched. The epoch is
     * recorded only after the clear succeeds, so a failure retries next launch.
     * Synchronous on purpose: it must finish before the first screen requests an
     * image, and it runs once per epoch, not per launch.
     */
    private fun clearStaleImageCacheOnce() {
        val prefs = getSharedPreferences(IMAGE_CACHE_PREFS, MODE_PRIVATE)
        if (!ImageCacheEpoch.needsClear(prefs.getInt(KEY_EPOCH, 0))) return
        val cleared =
            runCatching {
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            }.isSuccess
        if (cleared) prefs.edit { putInt(KEY_EPOCH, ImageCacheEpoch.CURRENT) }
    }

    private companion object {
        const val IMAGE_CACHE_PREFS = "image_cache"
        const val KEY_EPOCH = "epoch"
    }
}
