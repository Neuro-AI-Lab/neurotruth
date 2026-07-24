package com.neurotruth.mobile.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.ui.theme.PrimaryButton

/**
 * NT-02 · 가입 · 로그인.
 *
 * `[가입하고 시작]` reaches no server: it validates and moves to NT-03, which makes the single signup
 * call carrying the consent snapshot. The login path calls the server directly.
 *
 * A modern welcome layout: a brand mark, a warm headline, soft borderless fields, one clear pill
 * action, and a text link to switch between signing up and logging in.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(56.dp))

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MonitorHeart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = if (signup) "함께 시작해요" else "다시 오셨네요",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (signup) {
                "갈망의 순간을 기록하고, 바로 대화로 이어가요."
            } else {
                "이메일과 비밀번호로 로그인하세요."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(36.dp))

        if (signup) {
            AuthField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChanged,
                placeholder = "표시 이름 (선택)",
                enabled = !state.isSubmitting,
                imeAction = ImeAction.Next,
                description = "표시 이름 입력란, 선택 항목",
            )
            Spacer(Modifier.height(12.dp))
        }

        AuthField(
            value = state.email,
            onValueChange = viewModel::onEmailChanged,
            placeholder = "이메일",
            enabled = !state.isSubmitting,
            isError = state.email.isNotBlank() && !state.emailLooksValid,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            description = "이메일 입력란",
        )
        Spacer(Modifier.height(12.dp))

        AuthField(
            value = state.password,
            onValueChange = viewModel::onPasswordChanged,
            placeholder = "비밀번호",
            enabled = !state.isSubmitting,
            isError = signup && state.password.isNotBlank() && !state.passwordLongEnough,
            keyboardType = KeyboardType.Password,
            password = true,
            imeAction = if (signup) ImeAction.Next else ImeAction.Done,
            description = "비밀번호 입력란",
        )

        if (signup) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "비밀번호는 ${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상이에요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            AuthField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChanged,
                placeholder = "비밀번호 확인",
                enabled = !state.isSubmitting,
                isError = state.confirmPassword.isNotBlank() && !state.passwordsMatch,
                keyboardType = KeyboardType.Password,
                password = true,
                imeAction = ImeAction.Done,
                description = "비밀번호 확인 입력란",
            )
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "입력 오류: $message" },
            )
        }

        Spacer(Modifier.height(32.dp))

        PrimaryButton(
            text = if (signup) "가입하고 시작" else "로그인",
            onClick = { if (signup) viewModel.submitSignup() else viewModel.submitLogin() },
            enabled = state.canSubmit,
            loading = state.isSubmitting,
            contentDescription = if (signup) {
                "가입하고 시작, 다음 화면에서 동의를 선택해요"
            } else {
                "로그인"
            },
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (signup) "이미 계정이 있으신가요?  " else "계정이 없으신가요?  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (signup) "로그인" else "가입하기",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !state.isSubmitting) {
                        viewModel.onModeChanged(if (signup) AuthMode.LOGIN else AuthMode.SIGNUP)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription =
                            if (signup) "기존 계정으로 로그인 화면으로 전환" else "새 계정 만들기 화면으로 전환"
                    },
            )
        }
    }
}

/** A soft, borderless filled input — the modern auth field, no card and no hard outline. */
@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            errorContainerColor = MaterialTheme.colorScheme.errorContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics { contentDescription = description },
    )
}
