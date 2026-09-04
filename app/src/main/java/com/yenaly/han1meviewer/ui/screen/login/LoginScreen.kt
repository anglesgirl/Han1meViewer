package com.yenaly.han1meviewer.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.component.appbar.HanimeScaffold
import com.yenaly.han1meviewer.ui.preview.ComponentPreview

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    isRefreshing: Boolean,
    isLoggingIn: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onOpenQrScanner: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    HanimeScaffold(
        title = stringResource(R.string.login),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.scan_for_cookies)) },
                icon = {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_baseline_scan_24),
                        contentDescription = null,
                    )
                },
                onClick = onOpenQrScanner,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.email)) },
                singleLine = true,
                enabled = !isLoggingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isLoggingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(email, password) },
                enabled = !isLoggingIn && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.login))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    ComponentPreview {
        LoginScreen(
            isRefreshing = false,
            isLoggingIn = false,
            onBack = {},
            onRefresh = {},
            onLogin = { _, _ -> },
            onOpenQrScanner = {},
        )
    }
}
