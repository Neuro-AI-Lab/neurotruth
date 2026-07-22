package com.neurotruth.mobile.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentVersions
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.PrimaryButton
import com.neurotruth.mobile.ui.theme.ScreenScaffold
import com.neurotruth.mobile.ui.theme.SectionCard
import com.neurotruth.mobile.ui.theme.SectionLabel

/**
 * NT-03 · 동의 · 권한.
 *
 * Three required consents and seven optional ones. Turning every optional consent off still yields a
 * working app, and each row states what it does when off.
 *
 * Consent is not an OS permission: microphone and camera are requested when the matching feature is
 * first opened, so nothing here triggers a system dialog.
 */
@Composable
fun ConsentScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsentViewModel = viewModel(
        factory = ConsentViewModel.factory(NeuroTruthApp.from(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    ScreenScaffold(
        title = "동의 · 권한",
        modifier = modifier,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                PrimaryButton(
                    text = "저장하고 홈으로",
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    loading = state.isSubmitting,
                    contentDescription = "동의를 저장하고 홈으로 이동",
                    modifier = Modifier.padding(
                        horizontal = NeuroTruthSpacing.screenHorizontal,
                        vertical = NeuroTruthSpacing.screenVertical,
                    ),
                )
            }
        },
    ) {
        SectionLabel(text = "필수 동의")
        ConsentKey.REQUIRED.forEach { key ->
            ConsentRow(
                key = key,
                checked = state.values[key] == true,
                enabled = !state.isSubmitting,
                onCheckedChange = { viewModel.onToggle(key, it) },
            )
        }

        SectionLabel(text = "선택 동의")
        ConsentKey.OPTIONAL.forEach { key ->
            ConsentRow(
                key = key,
                checked = state.values[key] == true,
                enabled = !state.isSubmitting,
                onCheckedChange = { viewModel.onToggle(key, it) },
            )
        }

        Text(
            text = "동의를 철회하면 이후 처리가 중단되지만, 이미 보관된 기록이 삭제되지는 않습니다. " +
                "카메라·마이크 권한은 해당 기능을 처음 열 때 따로 요청해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "약관 ${ConsentVersions.TOS} · 개인정보 ${ConsentVersions.PRIVACY} · " +
                "동의서 ${ConsentVersions.CONSENT_FORM}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "적용된 동의 버전" },
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "저장 오류: $message" },
            )
        }
    }
}

@Composable
private fun ConsentRow(
    key: ConsentKey,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val explanation = if (checked || key.required) key.whenOn else key.whenOff
    SectionCard(tone = CardTone.Default) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NeuroTruthSpacing.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenRows),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (key.required) "${key.label} (필수)" else key.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (explanation.isNotBlank()) {
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription =
                        "${key.label} 동의 ${if (checked) "켜짐" else "꺼짐"}, 두 번 눌러 변경"
                },
            )
        }
    }
}
