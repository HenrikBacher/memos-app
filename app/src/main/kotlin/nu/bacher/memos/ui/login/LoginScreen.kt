package nu.bacher.memos.ui.login

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import nu.bacher.memos.data.auth.SsoAuthenticator
import nu.bacher.memos.ui.common.readableContentWidth
import androidx.compose.foundation.text.KeyboardOptions
import nu.bacher.memos.R
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    ssoRedirect: String? = null,
    onSsoRedirectConsumed: () -> Unit = {},
    vm: LoginViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }
    val busy = state.loading || state.ssoLoading
    val canSubmit = state.serverUrl.isNotBlank() && state.token.isNotBlank() && !busy
    val canStartSso = state.serverUrl.isNotBlank() && !busy

    // onLoggedIn is a lambda from the NavHost and gets a new identity on every
    // recomposition; capture the latest so the redirect effect below doesn't
    // restart whenever it changes.
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)

    // Auth Tab delivers the redirect straight back as an activity result, so
    // the happy path never touches the manifest intent-filter. That filter is
    // still the fallback: AuthTabIntent.build() deliberately leaves the intent
    // interpretable as a plain Custom Tab, and a browser that takes that route
    // bounces the redirect to MainActivity as an Intent instead — arriving
    // here via [ssoRedirect] below.
    val authLauncher = rememberLauncherForActivityResult(
        AuthTabIntent.AuthenticateUserResultContract(),
    ) { result ->
        when (result.resultCode) {
            AuthTabIntent.RESULT_OK ->
                result.resultUri?.let { vm.onSsoRedirect(it.toString(), currentOnLoggedIn) }
                    ?: vm.onSsoCancelled()

            AuthTabIntent.RESULT_CANCELED -> vm.onSsoCancelled()
            // Verification results only apply to https redirects, which this
            // flow doesn't use — so anything else here is the browser telling
            // us the tab ended in a state it couldn't describe.
            else -> vm.onSsoUnusableResult()
        }
    }

    LaunchedEffect(Unit) {
        vm.authorizationUrls.collect { url ->
            if (!authLauncher.launchAuthorization(url)) vm.onBrowserUnavailable()
        }
    }

    // An authorization code is single-use, so this fires once per value and
    // then reports the value consumed — otherwise it would be replayed the
    // next time this screen is composed, e.g. after a later logout.
    val currentOnConsumed by rememberUpdatedState(onSsoRedirectConsumed)
    LaunchedEffect(ssoRedirect) {
        ssoRedirect?.let {
            vm.onSsoRedirect(it, currentOnLoggedIn)
            currentOnConsumed()
        }
    }

    if (state.ssoProviders.isNotEmpty()) {
        ProviderPickerDialog(
            providers = state.ssoProviders,
            onSelect = vm::onProviderSelected,
            onDismiss = vm::onProviderPickerDismissed,
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.login_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(padding)
                .readableContentWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = vm::onUrlChange,
                label = { Text(stringResource(R.string.login_server_url)) },
                placeholder = { Text(stringResource(R.string.login_server_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::onTokenChange,
                label = { Text(stringResource(R.string.login_token)) },
                placeholder = { Text(stringResource(R.string.login_token_hint)) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (tokenVisible) R.string.login_hide_token
                                else R.string.login_show_token,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error)
                // The redirect URI has to be registered on the OAuth app the
                // memos instance points at, and there's nowhere else in the UI
                // to learn what it is. Only shown once SSO has actually failed,
                // so it doesn't clutter the common path.
                if (it.isSsoError()) {
                    Text(
                        text = stringResource(
                            R.string.login_sso_redirect_hint,
                            SsoAuthenticator.REDIRECT_URI,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.submit(onLoggedIn) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.login_connect))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.login_or),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedButton(
                onClick = vm::startSso,
                enabled = canStartSso,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.ssoLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.login_sso))
                }
            }
        }
    }
}

/** Shown only when the server offers more than one provider. */
@Composable
private fun ProviderPickerDialog(
    providers: List<SsoAuthenticator.Provider>,
    onSelect: (SsoAuthenticator.Provider) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_sso_pick_provider)) },
        text = {
            Column {
                providers.forEach { provider ->
                    TextButton(
                        onClick = { onSelect(provider) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(provider.title, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.login_sso_cancel)) }
        },
    )
}

/**
 * Open [url] in an Auth Tab, returning false if the device has no browser
 * that can. RFC 8252 wants an external user agent here, never an embedded
 * WebView — the user needs a real URL bar to see who is asking for their
 * password.
 */
private fun ActivityResultLauncher<Intent>.launchAuthorization(url: String): Boolean = try {
    AuthTabIntent.Builder()
        .build()
        .launch(this, url.toUri(), SsoAuthenticator.REDIRECT_SCHEME)
    true
} catch (_: ActivityNotFoundException) {
    false
}

private fun LoginViewModel.LoginError.isSsoError(): Boolean = when (this) {
    LoginViewModel.LoginError.SSO_NOT_CONFIGURED,
    LoginViewModel.LoginError.SSO_UNSUPPORTED,
    LoginViewModel.LoginError.SSO_DENIED,
    LoginViewModel.LoginError.SSO_FAILED,
    LoginViewModel.LoginError.SSO_CANCELLED,
    LoginViewModel.LoginError.SSO_NO_BROWSER,
    -> true

    else -> false
}

/**
 * Maps the ViewModel's typed error to a localized string. Same contract as
 * MemoEditScreen's `messageRes` — the screen owns the wording.
 */
private fun LoginViewModel.LoginError.messageRes(): Int = when (this) {
    LoginViewModel.LoginError.URL_NOT_HTTPS -> R.string.login_error_url_https
    LoginViewModel.LoginError.TOKEN_REQUIRED -> R.string.login_error_token_required
    LoginViewModel.LoginError.NETWORK -> R.string.login_error_network
    LoginViewModel.LoginError.AUTH -> R.string.login_error_auth
    LoginViewModel.LoginError.BUSY -> R.string.error_server_busy
    LoginViewModel.LoginError.SERVER -> R.string.login_error_server
    LoginViewModel.LoginError.GENERIC -> R.string.login_error_generic
    LoginViewModel.LoginError.SSO_NOT_CONFIGURED -> R.string.login_error_sso_not_configured
    LoginViewModel.LoginError.SSO_UNSUPPORTED -> R.string.login_error_sso_unsupported
    LoginViewModel.LoginError.SSO_DENIED -> R.string.login_error_sso_denied
    LoginViewModel.LoginError.SSO_FAILED -> R.string.login_error_sso_failed
    LoginViewModel.LoginError.SSO_CANCELLED -> R.string.login_error_sso_cancelled
    LoginViewModel.LoginError.SSO_NO_BROWSER -> R.string.login_error_sso_no_browser
}
