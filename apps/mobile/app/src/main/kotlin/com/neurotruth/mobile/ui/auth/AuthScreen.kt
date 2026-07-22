package com.neurotruth.mobile.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.ui.theme.PrimaryButton
import com.neurotruth.mobile.ui.theme.ScreenScaffold
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SecondaryButton

/**
 * NT-02 · 가입 · 로그인.
 *
 * `[가입하고 시작]` reaches no server: it validates and moves to NT-03, which makes the single signup
 * call carrying the consent snapshot. `[기존 계정으로 로그인]` calls the server directly.
 */
@Composable
fun AuthScreen(
    onNavigateToConsent: () -> Unit,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        when (destination) {
            AuthDestination.CONSENT -> {
                viewModel.consumeDestination()
                onNavigateToConsent()
            }
            AuthDestination.HOME -> {
                viewModel.consumeDestination()
                onNavigateToHome()
            }
            null -> Unit
        }
    }

    val signup = state.mode == AuthMode.SIGNUP

    ScreenScaffold(
        title = "가입 · 로그인",
        modifier = modifier.imePadding(),
    ) {
        SectionCard {
            if (signup) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayNameChanged,
                    label = { Text("표시 이름 (선택)") },
                    supportingText = { Text("홈 화면에 표시돼요. 비워 두면 이메일 앞부분을 사용해요.") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "표시 이름 입력란, 선택 항목" },
                )
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                label = { Text("이메일") },
                placeholder = { Text("demo@example.com") },
                singleLine = true,
                isError = state.email.isNotBlank() && !state.emailLooksValid,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "이메일 입력란" },
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("비밀번호") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = signup && state.password.isNotBlank() && !state.passwordLongEnough,
                supportingText = {
                    if (signup) Text("${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상")
                },
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (signup) ImeAction.Next else ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "비밀번호 입력란" },
            )

            if (signup) {
                OutlinedTextField(
                    value = state.confirmPassword,
                    onValueChange = viewModel::onConfirmPasswordChanged,
                    label = { Text("비밀번호 확인") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = state.confirmPassword.isNotBlank() && !state.passwordsMatch,
                    enabled = !state.isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "비밀번호 확인 입력란" },
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "입력 오류: $message" },
            )
        }

        if (signup) {
            PrimaryButton(
                text = "가입하고 시작",
                onClick = viewModel::submitSignup,
                enabled = state.canSubmit,
                contentDescription = "가입하고 시작, 다음 화면에서 동의를 선택해요",
            )
            SecondaryButton(
                text = "기존 계정으로 로그인",
                onClick = { viewModel.onModeChanged(AuthMode.LOGIN) },
                enabled = !state.isSubmitting,
                contentDescription = "기존 계정으로 로그인 화면으로 전환",
            )
        } else {
            PrimaryButton(
                text = "로그인",
                onClick = viewModel::submitLogin,
                enabled = state.canSubmit,
                loading = state.isSubmitting,
                contentDescription = "로그인",
            )
            SecondaryButton(
                text = "새 계정 만들기",
                onClick = { viewModel.onModeChanged(AuthMode.SIGNUP) },
                enabled = !state.isSubmitting,
                contentDescription = "새 계정 만들기 화면으로 전환",
            )
        }
    }
}
