package dev.bitstorm.sashimi.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary

/**
 * Two-step connect flow (URL → credentials), the Compose port of MobileAuthView.
 * [onCancel] renders a Cancel button when shown as the Add Server sheet;
 * [onComplete] lets a presenting sheet dismiss after a successful sign-in.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onCancel: (() -> Unit)? = null,
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fieldModifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)

        Text(
            text = if (state.showLogin) "Sign In" else "Connect to Server",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )

        if (!state.showLogin) {
            ServerEntry(
                serverUrl = state.serverUrl,
                isConnecting = state.isConnecting,
                onServerUrlChange = viewModel::onServerUrlChange,
                onConnect = viewModel::connect,
                fieldModifier = fieldModifier,
            )
        } else {
            LoginEntry(
                username = state.username,
                password = state.password,
                isConnecting = state.isConnecting,
                onUsernameChange = viewModel::onUsernameChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSignIn = { viewModel.signIn(onComplete) },
                onUseDifferentServer = viewModel::useDifferentServer,
                fieldModifier = fieldModifier,
            )
        }

        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                Text("Cancel")
            }
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            },
            title = { Text("Error") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ServerEntry(
    serverUrl: String,
    isConnecting: Boolean,
    onServerUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    fieldModifier: Modifier,
) {
    Text(
        text = "Enter your Jellyfin server address",
        color = SashimiTextSecondary,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text("Server URL") },
        placeholder = { Text("jellyfin.example.com") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        modifier = fieldModifier,
    )
    Button(
        onClick = onConnect,
        enabled = serverUrl.isNotEmpty() && !isConnecting,
        modifier = fieldModifier.padding(top = 16.dp),
    ) {
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        } else {
            Text("Connect")
        }
    }
}

@Composable
private fun LoginEntry(
    username: String,
    password: String,
    isConnecting: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onUseDifferentServer: () -> Unit,
    fieldModifier: Modifier,
) {
    Text(
        text = "Enter your credentials",
        color = SashimiTextSecondary,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Username") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = fieldModifier,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        modifier = fieldModifier.padding(top = 12.dp),
    )
    Button(
        onClick = onSignIn,
        enabled = username.isNotEmpty() && !isConnecting,
        modifier = fieldModifier.padding(top = 16.dp),
    ) {
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        } else {
            Text("Sign In")
        }
    }
    TextButton(
        onClick = onUseDifferentServer,
        modifier = fieldModifier.padding(top = 4.dp),
    ) {
        Text("Use Different Server")
    }
}
