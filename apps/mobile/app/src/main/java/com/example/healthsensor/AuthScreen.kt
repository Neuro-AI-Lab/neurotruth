package com.example.healthsensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PatientAuthScreen(
    loading: Boolean,
    message: String,
    onLogin: (String, String) -> Unit,
    onSignup: (String, String, String?, ConsentSelection) -> Unit
) {
    var signupMode by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var tos by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var sensitive by rememberSaveable { mutableStateOf(false) }
    var biosignal by rememberSaveable { mutableStateOf(false) }
    var aiAnalysis by rememberSaveable { mutableStateOf(false) }
    var cameraRppg by rememberSaveable { mutableStateOf(false) }
    var faceVideoRetention by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var notification by rememberSaveable { mutableStateOf(false) }
    var reportGeneration by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NeuroTruth", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            if (signupMode) "환자 계정을 만들고 동의 항목을 설정합니다." else "환자 계정으로 로그인해 주세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (signupMode) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("이름 (선택)") },
                singleLine = true
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("이메일") },
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("비밀번호") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        if (signupMode) {
            HorizontalDivider()
            Text("필수 동의", fontWeight = FontWeight.Bold)
            ConsentCheckbox("이용약관 동의", tos, { tos = it })
            ConsentCheckbox("개인정보 처리 동의", privacy, { privacy = it })
            ConsentCheckbox("민감정보 처리 동의", sensitive, { sensitive = it })

            Text("선택 동의", fontWeight = FontWeight.Bold)
            ConsentSwitch("생체신호 수집", biosignal, { biosignal = it })
            ConsentSwitch("AI 분석", aiAnalysis, { aiAnalysis = it })
            ConsentSwitch("카메라 rPPG 분석", cameraRppg, { cameraRppg = it })
            ConsentSwitch("얼굴 영상 영구 보존", faceVideoRetention, { faceVideoRetention = it })
            Text(
                "촬영 영상은 서버에서 암호화되어 관리자가 삭제할 때까지 보존됩니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ConsentSwitch("알림 수신", notification, { notification = it })
            ConsentSwitch("보고서 생성", reportGeneration, { reportGeneration = it })
            ConsentSwitch("음성 입력(STT)", voice, { voice = it })
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = if (message.endsWith("중")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        Button(
            onClick = {
                if (signupMode) {
                    onSignup(
                        email,
                        password,
                        name.trim().takeIf(String::isNotEmpty),
                        ConsentSelection(
                            tos = tos,
                            privacy = privacy,
                            sensitive = sensitive,
                            biosignal = biosignal,
                            aiAnalysis = aiAnalysis,
                            cameraRppg = cameraRppg,
                            faceVideoRetention = faceVideoRetention,
                            voice = voice,
                            notification = notification,
                            reportGeneration = reportGeneration,
                            tosVersion = CONSENT_VERSION,
                            privacyVersion = CONSENT_VERSION,
                            consentFormVersion = CONSENT_VERSION
                        )
                    )
                } else {
                    onLogin(email, password)
                }
            },
            enabled = !loading && if (signupMode) {
                AuthInputPolicy.canSignup(email, password, tos, privacy, sensitive)
            } else {
                AuthInputPolicy.canLogin(email, password)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(if (signupMode) "가입하고 시작" else "로그인")
            }
        }
        TextButton(
            onClick = { signupMode = !signupMode },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (signupMode) "이미 계정이 있습니다" else "환자 계정 직접 가입")
        }
    }
}

@Composable
fun RequiredPasswordChangeScreen(
    loading: Boolean,
    message: String,
    onSubmit: (String, String, String) -> Unit,
    onLogout: () -> Unit
) {
    var current by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("비밀번호 변경 필요", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("계속하려면 임시 비밀번호를 새 비밀번호로 변경해 주세요.")
        Spacer(Modifier.height(16.dp))
        PasswordField("현재 비밀번호", current) { current = it }
        PasswordField("새 비밀번호", newPassword) { newPassword = it }
        PasswordField("새 비밀번호 확인", confirmation) { confirmation = it }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSubmit(current, newPassword, confirmation) },
            enabled = !loading && current.isNotBlank() && newPassword.length >= 12 && newPassword == confirmation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "변경 중" else "비밀번호 변경")
        }
        OutlinedButton(onClick = onLogout, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text("로그아웃")
        }
    }
}

@Composable
fun AuthenticatedAccountBar(
    user: AuthUser,
    onConsentSettings: () -> Unit,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(user.email, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onConsentSettings) { Text("동의 설정") }
            TextButton(onClick = onLogout) { Text("로그아웃") }
        }
    }
}

@Composable
fun PatientConsentSettingsDialog(
    current: ConsentSelection?,
    loading: Boolean,
    message: String,
    onDismiss: () -> Unit,
    onSave: (ConsentSelection) -> Unit
) {
    val base = current ?: ConsentSelection(
        tos = true,
        privacy = true,
        sensitive = true,
        biosignal = false,
        aiAnalysis = false,
        cameraRppg = false,
        faceVideoRetention = false,
        voice = false,
        notification = false,
        reportGeneration = false,
        tosVersion = CONSENT_VERSION,
        privacyVersion = CONSENT_VERSION,
        consentFormVersion = CONSENT_VERSION
    )
    var biosignal by remember(current) { mutableStateOf(base.biosignal) }
    var aiAnalysis by remember(current) { mutableStateOf(base.aiAnalysis) }
    var cameraRppg by remember(current) { mutableStateOf(base.cameraRppg) }
    var faceVideoRetention by remember(current) { mutableStateOf(base.faceVideoRetention) }
    var voice by remember(current) { mutableStateOf(base.voice) }
    var notification by remember(current) { mutableStateOf(base.notification) }
    var reportGeneration by remember(current) { mutableStateOf(base.reportGeneration) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("수집·분석 동의 설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("필수 동의", fontWeight = FontWeight.Bold)
                ConsentSwitch("이용약관 (필수)", true, {}, enabled = false)
                ConsentSwitch("개인정보 처리 (필수)", true, {}, enabled = false)
                ConsentSwitch("민감정보 처리 (필수)", true, {}, enabled = false)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("선택 동의", fontWeight = FontWeight.Bold)
                ConsentSwitch("생체신호 수집", biosignal, { biosignal = it }, enabled = !loading)
                ConsentSwitch("AI 분석", aiAnalysis, { aiAnalysis = it }, enabled = !loading)
                ConsentSwitch("카메라 rPPG 분석", cameraRppg, { cameraRppg = it }, enabled = !loading)
                ConsentSwitch(
                    "얼굴 영상 영구 보존",
                    faceVideoRetention,
                    { faceVideoRetention = it },
                    enabled = !loading
                )
                ConsentSwitch("알림", notification, { notification = it }, enabled = !loading)
                ConsentSwitch("보고서 생성", reportGeneration, { reportGeneration = it }, enabled = !loading)
                ConsentSwitch("음성 입력(STT)", voice, { voice = it }, enabled = !loading)
                Text(
                    "선택 동의를 철회하면 신규 수집·분석만 즉시 중지되며 기존 자료는 자동 삭제되지 않습니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isNotBlank()) {
                    Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading,
                onClick = {
                    onSave(
                        base.copy(
                            biosignal = biosignal,
                            aiAnalysis = aiAnalysis,
                            cameraRppg = cameraRppg,
                            faceVideoRetention = faceVideoRetention,
                            voice = voice,
                            notification = notification,
                            reportGeneration = reportGeneration
                        )
                    )
                }
            ) {
                Text(if (loading) "저장 중" else "새 동의 스냅샷 저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("닫기") }
        }
    )
}

@Composable
private fun ConsentCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun ConsentSwitch(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
}

private const val CONSENT_VERSION = "2026-07-15-v1"
