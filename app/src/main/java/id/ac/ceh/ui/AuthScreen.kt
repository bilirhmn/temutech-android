package id.ac.ceh.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.ac.ceh.R

internal data class AuthUiState(
    val available: Boolean = false,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

@Composable
internal fun AuthScreen(
    state: AuthUiState,
    demoAvailable: Boolean,
    onAuthenticate: (email: String, password: String, register: Boolean) -> Unit,
    onEnterDemo: () -> Unit,
) {
    AuthLayout {
        if (state.available) {
            AuthForm(state, onAuthenticate, onEnterDemo.takeIf { demoAvailable })
        } else {
            Text(
                text = stringResource(state.error ?: R.string.cloud_auth_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (demoAvailable) {
                AuthDemo(showAction = !state.busy, onEnterDemo = onEnterDemo)
            }
        }
    }
}


@Composable
private fun AuthLayout(content: @Composable () -> Unit) {
    val spacing = dimensionResource(R.dimen.hub_space_medium)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AuthForm(
    state: AuthUiState,
    onAuthenticate: (email: String, password: String, register: Boolean) -> Unit,
    onEnterDemo: (() -> Unit)?,
) {
    var email by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(true) }
    var password by remember(register) { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val minTarget = dimensionResource(R.dimen.hub_min_target)
    val enabled = !state.busy
    val error = state.error.takeIf { showError && enabled }
    val emailError = error == R.string.cloud_auth_invalid_email || error == R.string.cloud_auth_email_in_use
    val passwordError = error == R.string.cloud_auth_short_password || error == R.string.cloud_auth_weak_password
    val action = stringResource(if (register) R.string.cloud_auth_register else R.string.sign_in)
    val submit = {
        if (enabled) {
            focusManager.clearFocus()
            showError = true
            onAuthenticate(email.trim(), password, register)
        }
    }

    LaunchedEffect(error) {
        if (emailError) emailFocus.requestFocus()
        else if (passwordError) passwordFocus.requestFocus()
    }

    Text(
        text = action,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(if (register) R.string.cloud_auth_register_intro else R.string.cloud_auth_login_intro),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.cloud_auth_email)) },
        modifier = Modifier.fillMaxWidth().focusRequester(emailFocus),
        enabled = enabled,
        isError = emailError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.cloud_auth_password)) },
        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
        enabled = enabled,
        isError = passwordError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
    if (state.busy) {
        Text(
            text = stringResource(R.string.cloud_auth_busy),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else if (error != null) {
        Text(
            text = stringResource(error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = submit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = minTarget),
    ) {
        Text(action)
    }
    TextButton(
        onClick = {
            showError = false
            register = !register
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = minTarget),
    ) {
        Text(stringResource(if (register) R.string.cloud_auth_switch_to_login else R.string.cloud_auth_switch_to_register))
    }
    if (onEnterDemo != null) {
        AuthDemo(showAction = enabled, onEnterDemo = {
            password = ""
            onEnterDemo()
        })
    }
}

@Composable
private fun AuthDemo(showAction: Boolean, onEnterDemo: () -> Unit) {
    Text(stringResource(R.string.demo_info), style = MaterialTheme.typography.bodyLarge)
    if (showAction) {
        HubButton(stringResource(R.string.use_demo), onEnterDemo, Modifier.fillMaxWidth())
    }
}
