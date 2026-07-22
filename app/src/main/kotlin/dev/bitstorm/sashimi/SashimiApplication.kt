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
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            ServiceLocator.session.restoreSession()
        }
    }
}
