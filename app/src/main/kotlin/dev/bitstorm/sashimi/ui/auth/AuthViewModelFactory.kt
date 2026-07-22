package dev.bitstorm.sashimi.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.bitstorm.sashimi.di.ServiceLocator

/** Builds [AuthViewModel] from the process-wide graph (ServiceLocator). */
class AuthViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(ServiceLocator.client, ServiceLocator.session) as T
}
