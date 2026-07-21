package com.neurotruth.mobile.ui.notice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ProductNoticePolicy
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing

/**
 * NT-01 · 제품 안내.
 *
 * Acknowledgement is owned by [ProductNoticePolicy] in `:core`, which compares the stored device
 * version against the shipped constant. The screen only reports the tap.
 *
 * The back gesture is swallowed: a freshly installed user must not reach NT-02 without seeing this.
 */
@Composable
fun NoticeScreen(
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
    noticePolicy: ProductNoticePolicy = NeuroTruthApp.from(LocalContext.current).noticePolicy,
) {
    BackHandler(enabled = true) { /* NT-01 cannot be skipped with the back gesture. */ }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = {
                        noticePolicy.acknowledge()
                        onAcknowledged()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = NeuroTruthSpacing.screenHorizontal,
                            vertical = NeuroTruthSpacing.screenVertical,
                        )
                        .heightIn(min = NeuroTruthSpacing.minTouchTarget)
                        .semantics { contentDescription = "제품 안내를 확인하고 가입 화면으로 이동" },
                ) {
                    Text(text = "확인하고 계속", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        horizontal = NeuroTruthSpacing.screenHorizontal,
                        vertical = NeuroTruthSpacing.screenVertical,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(NeuroTruthSpacing.betweenCards),
        ) {
            Text(text = "제품 안내", style = MaterialTheme.typography.headlineMedium)
            HorizontalDivider()

            NoticeCard(
                title = "NeuroTruth",
                body = "갈망 상황을 기록하고 대화를 돕는 연구용 보조 시스템입니다.",
                container = MaterialTheme.colorScheme.secondaryContainer,
                onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                description = "제품 정의",
            )

            NoticeCard(
                title = "이런 분께 도움이 됩니다",
                body = "치료 중이거나 치료 의지가 있고, 갈망 상황에서 지속적인 기록과 대화 지원이 필요한 분",
                container = MaterialTheme.colorScheme.surface,
                onContainer = MaterialTheme.colorScheme.onSurface,
                description = "대상 사용자",
            )

            NoticeCard(
                title = "꼭 확인해 주세요",
                body = "이 앱은 의료행위·진단·처방·응급 대응을 대신하지 않습니다. " +
                    "기록은 실시간 감시가 아니며, 연락이나 대응을 보장하지 않습니다.",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                description = "제한 사항 안내",
                emphasised = true,
            )

            Spacer(modifier = Modifier.heightIn(min = 8.dp))
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    description: String,
    emphasised: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        border = if (emphasised) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier.padding(NeuroTruthSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
