package com.neurotruth.mobile.ui.settings

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.Manifest
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.net.PatientSignupRequest
import com.neurotruth.mobile.ui.consent.ConsentKey
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.PrimaryButton
import com.neurotruth.mobile.ui.theme.SecondaryButton
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SectionLabel

/**
 * NT-09 · 설정.
 *
 * Account information, required and optional consent, the product notice, device permission status,
 * password change and logout. Every decision belongs to [SettingsViewModel]; this file renders state
 * and branches on nothing but presence.
 *
 * Consent here is append-only, and the copy says plainly that withdrawing a consent blocks new use
 * without deleting what has already been collected.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showNotice by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    // In-app runtime permission request. Whatever the result, re-read the real OS state so the row
    // reflects it immediately.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    val requestPermission: (DevicePermission) -> Unit = { permission ->
        when (val manifest = permission.runtimePermission()) {
            // Notifications below API 33 have no runtime prompt — the channel is toggled in the
            // system settings, so that is where the tap goes.
            null -> context.openAppSettings()
            else -> permissionLauncher.launch(manifest)
        }
    }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    // A permission may have been changed in the system settings while the app was away.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showNotice) {
        ProductNoticeDialog(version = state.noticeVersion, onDismiss = { showNotice = false })
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("로그아웃할까요?") },
            text = {
                Text(
                    "측정과 알림 연결이 모두 끊기고, 이 기기에 남아 있는 대화·기록 화면 정보가 지워져요. " +
                        "서버에 저장된 기록은 그대로 남아 있어요.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        viewModel.logout()
                    },
                    modifier = Modifier.semantics { contentDescription = "로그아웃 확인" },
                ) { Text("로그아웃") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmLogout = false },
                    modifier = Modifier.semantics { contentDescription = "로그아웃 취소" },
                ) { Text("취소") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    horizontal = NeuroTruthSpacing.screenHorizontal,
                    vertical = NeuroTruthSpacing.screenVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenCards),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NeuroTruthSpacing.titleTop, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "설정",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedButton(
                    onClick = onBack,
                    shape = CircleShape,
                    modifier = Modifier
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "설정 닫고 홈으로" },
                ) {
                    Text("닫기", style = MaterialTheme.typography.labelLarge)
                }
            }

            AccountSection(state = state, viewModel = viewModel)
            ConsentSection(state = state, viewModel = viewModel)
            NoticeSection(version = state.noticeVersion, onReRead = { showNotice = true })
            PermissionSection(
                state = state,
                onRequest = requestPermission,
                onOpenSystemSettings = { context.openAppSettings() },
            )
            PasswordSection(state = state, viewModel = viewModel)

            SettingsCard(title = "로그아웃") {
                Text(
                    text = "로그아웃하면 측정·알림 연결과 이 기기의 화면 정보가 정리돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { confirmLogout = true },
                    enabled = !state.isSigningOut,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "로그아웃" },
                ) {
                    if (state.isSigningOut) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        Text("로그아웃", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = "오류: $message" },
                )
            }
        }
    }
}

@Composable
private fun AccountSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard(title = "계정 정보") {
        Text(
            text = state.email.ifBlank { "이메일을 불러오는 중이에요." },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "로그인 이메일 ${state.email}" },
        )
        OutlinedTextField(
            value = state.nameDraft,
            onValueChange = viewModel::onNameDraftChanged,
            label = { Text("표시 이름") },
            supportingText = { Text("홈 화면에 표시돼요. 비워 두면 이메일 앞부분을 사용해요.") },
            singleLine = true,
            enabled = !state.isSavingName && !state.isLoading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "표시 이름 입력란" },
        )
        PrimaryButton(
            text = "표시 이름 저장",
            onClick = viewModel::saveDisplayName,
            enabled = state.canSaveName,
            loading = state.isSavingName,
            contentDescription = "표시 이름 저장",
        )
        state.nameSavedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ConsentSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard(title = "동의") {
        Text(text = "필수 동의", style = MaterialTheme.typography.titleSmall)
        ConsentKey.REQUIRED.forEach { key ->
            RequiredConsentRow(key = key, granted = state.consentValues[key] != false)
        }
        Text(
            text = "필수 동의는 앱에서 해제할 수 없어요. 중단을 원하시면 연구 담당자에게 문의해 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(text = "선택 동의", style = MaterialTheme.typography.titleSmall)
        ConsentKey.OPTIONAL.forEach { key ->
            OptionalConsentRow(
                key = key,
                checked = state.consentValues[key] == true,
                enabled = !state.isSavingConsent && !state.isLoading,
                onCheckedChange = { viewModel.onConsentToggled(key, it) },
            )
        }

        Text(
            text = "동의를 철회하면 이후 새로운 처리는 중단되지만, 이미 수집·보관된 기록이 삭제되지는 않습니다. " +
                "변경 내용은 이전 기록을 덮어쓰지 않고 새 기록으로 추가돼요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.consentVersionSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "적용된 동의 버전" },
        )

        PrimaryButton(
            text = "동의 변경 저장",
            onClick = viewModel::saveConsent,
            enabled = !state.isSavingConsent && !state.isLoading,
            loading = state.isSavingConsent,
            contentDescription = "동의 변경 내용 저장",
        )
        state.consentSavedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RequiredConsentRow(key: ConsentKey, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
            .semantics {
                contentDescription = "${key.label} 필수 동의 ${if (granted) "동의함" else "확인 필요"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${key.label} (필수)", style = MaterialTheme.typography.titleSmall)
            Text(
                text = key.whenOn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (granted) "동의함" else "확인 필요",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptionalConsentRow(
    key: ConsentKey,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NeuroTruthSpacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = key.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (checked) key.whenOn else key.whenOff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "${key.label} 동의 ${if (checked) "켜짐" else "꺼짐"}, 두 번 눌러 변경"
            },
        )
    }
}

@Composable
private fun NoticeSection(version: String, onReRead: () -> Unit) {
    SettingsCard(title = "제품 안내") {
        Text(
            text = "가입할 때 확인한 안내를 다시 볼 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "안내 버전 $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "확인한 안내 버전 $version" },
        )
        SecondaryButton(
            text = "제품 안내 다시 보기",
            onClick = onReRead,
            contentDescription = "제품 안내 다시 보기",
        )
    }
}

@Composable
private fun ProductNoticeDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("제품 안내") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
            ) {
                Text(
                    text = "NeuroTruth는 갈망 상황을 기록하고 대화를 돕는 연구용 보조 시스템입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "이 앱은 의료행위·진단·처방·응급 대응을 대신하지 않습니다. " +
                        "기록은 실시간 감시가 아니며, 연락이나 대응을 보장하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "안내 버전 $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "제품 안내 닫기" },
            ) { Text("닫기") }
        },
    )
}

@Composable
private fun PermissionSection(
    state: SettingsUiState,
    onRequest: (DevicePermission) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    SettingsCard(title = "기기 권한") {
        Text(
            text = "권한은 동의와 별개예요. 허용을 누르면 바로 요청하고, 이미 거부한 권한은 시스템 설정에서 바꿀 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DevicePermission.entries.forEach { permission ->
            val granted = state.isGranted(permission)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                    .semantics {
                        contentDescription =
                            "${permission.label} 권한 ${if (granted) "허용됨" else "허용 안 됨"}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = permission.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = permission.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (granted) {
                    Text(
                        text = "허용됨",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = { onRequest(permission) },
                        modifier = Modifier.semantics {
                            contentDescription = "${permission.label} 권한 허용하기"
                        },
                    ) {
                        Text("허용", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        SecondaryButton(
            text = "시스템 설정 열기",
            onClick = onOpenSystemSettings,
            contentDescription = "시스템 권한 설정 열기",
        )
    }
}

/**
 * The runtime permission a row requests, or null when there is no runtime prompt (notifications
 * below API 33 are a system-settings channel toggle, not a runtime grant).
 */
private fun DevicePermission.runtimePermission(): String? = when (this) {
    DevicePermission.NOTIFICATION ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    DevicePermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    DevicePermission.CAMERA -> Manifest.permission.CAMERA
}

@Composable
private fun PasswordSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard(title = "비밀번호 변경") {
        OutlinedTextField(
            value = state.currentPassword,
            onValueChange = viewModel::onCurrentPasswordChanged,
            label = { Text("현재 비밀번호") },
            supportingText = state.currentPasswordError?.let { message -> { Text(message) } },
            singleLine = true,
            isError = state.currentPasswordError != null,
            enabled = !state.isChangingPassword,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = state.currentPasswordError
                        ?.let { message -> "현재 비밀번호 입력란, 오류: $message" }
                        ?: "현재 비밀번호 입력란"
                },
        )
        OutlinedTextField(
            value = state.newPassword,
            onValueChange = viewModel::onNewPasswordChanged,
            label = { Text("새 비밀번호") },
            supportingText = {
                Text("${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.")
            },
            singleLine = true,
            isError = state.newPassword.isNotBlank() && !state.newPasswordLongEnough,
            enabled = !state.isChangingPassword,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "새 비밀번호 입력란, ${PatientSignupRequest.MIN_PASSWORD_LENGTH}자 이상"
                },
        )
        OutlinedTextField(
            value = state.confirmPassword,
            onValueChange = viewModel::onConfirmPasswordChanged,
            label = { Text("새 비밀번호 확인") },
            singleLine = true,
            isError = state.confirmPassword.isNotBlank() && !state.newPasswordsMatch,
            enabled = !state.isChangingPassword,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "새 비밀번호 확인 입력란" },
        )
        PrimaryButton(
            text = "비밀번호 변경",
            onClick = viewModel::changePassword,
            enabled = !state.isChangingPassword,
            loading = state.isChangingPassword,
            contentDescription = "비밀번호 변경",
        )
        state.passwordChangedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows)) {
        SectionLabel(
            text = title,
            modifier = Modifier.semantics { contentDescription = "$title 영역" },
        )
        SectionCard(content = content)
    }
}

/** Permission changes belong to the system UI; the app never claims to grant them itself. */
private fun android.content.Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
