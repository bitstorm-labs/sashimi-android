package dev.bitstorm.sashimi

import android.app.Application
import dev.bitstorm.sashimi.di.ServiceLocator
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ServiceLocator.session.restoreSession()
        }
    }
}
