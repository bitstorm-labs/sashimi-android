package dev.bitstorm.sashimi.ui.requests

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.seerr.SeerrAuthState
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings › Requests: the Seerr server and the person's own sign-in (Plex
 * in a Custom Tab, or a Seerr email and password). Each person signs in as
 * themselves; no admin key is ever stored.
 */
@Composable
fun RequestsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = ServiceLocator.seerr
    val state by session.state.collectAsStateWithLifecycle()
    val server by session.serverUrl.collectAsStateWithLifecycle()
    val appTitle by session.applicationTitle.collectAsStateWithLifecycle()
    val error by session.errorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var address by rememberSaveable(server) { mutableStateOf(server.orEmpty()) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    // No default server: sign-in stays off until the field holds a real address.
    val addressValid = SeerrSession.normalizedServerUrl(address) != null

    fun applyAddress(): Boolean {
        val ok = session.setServerAddress(address)
        addressError = if (ok) null else "That doesn't look like a server address."
        return ok
    }

    Column(modifier.fillMaxSize()) {
        BackTopBar(title = "Requests", onBack = onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Request movies and shows from $appTitle. Sign in with your own account, so approvals and limits work as on the website.",
                    color = SashimiTextSecondary,
                )
                when (val s = state) {
                    is SeerrAuthState.SignedIn -> {
                        Text("Signed in as ${s.account.name}", style = MaterialTheme.typography.titleMedium)
                        server?.let { Text(it, color = SashimiTextTertiary) }
                        OutlinedButton(onClick = { scope.launch { session.signOut() } }) { Text("Sign Out") }
                    }
                    is SeerrAuthState.AwaitingPlex -> {
                        Text("Finish signing in on the Plex page.", style = MaterialTheme.typography.titleMedium)
                        CircularProgressIndicator(color = SashimiAccent)
                        TextButton(onClick = session::cancelPlexSignIn) { Text("Cancel") }
                    }
                    SeerrAuthState.SigningIn -> CircularProgressIndicator(color = SashimiAccent)
                    SeerrAuthState.NotConfigured, SeerrAuthState.Expired -> {
                        if (s == SeerrAuthState.Expired) {
                            Text("Your $appTitle sign-in has expired. Sign in again.", color = SeerrBadge.PENDING.color())
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                addressError = null
                            },
                            label = { Text("Server address") },
                            placeholder = { Text("https://requests.example.com") },
                            singleLine = true,
                            isError = addressError != null,
                            supportingText = addressError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (!applyAddress()) return@Button
                                // App scope: the poll outlives this screen while the Custom Tab covers it.
                                ServiceLocator.appScope.launch(Dispatchers.Main) {
                                    val pin = session.beginPlexSignIn(strong = true) ?: return@launch
                                    openInBrowser(context, session.plexWebSignInUrl(pin))
                                    session.completePlexSignIn(pin)
                                }
                            },
                            enabled = addressValid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with Plex") }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Or with a $appTitle account", color = SashimiTextTertiary)
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email or username") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                if (!applyAddress()) return@OutlinedButton
                                scope.launch {
                                    session.signInLocal(email, password)
                                    if (session.isActive) password = ""
                                }
                            },
                            enabled = addressValid && email.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign In") }
                    }
                }
                error?.let { Text(it, color = SeerrBadge.DECLINED.color()) }
            }
        }
    }
}

/** Plex's sign-in page in a Custom Tab; a plain browser if no Custom Tabs provider is installed. */
private fun openInBrowser(
    context: Context,
    url: String,
) {
    val uri = url.toUri()
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
        .recoverCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
