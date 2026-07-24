package com.neurotruth.mobile.ui.notice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ProductNoticePolicy
import com.neurotruth.mobile.ui.theme.PrimaryButton

/**
 * NT-01 · 제품 안내.
 *
 * Acknowledgement is owned by [ProductNoticePolicy] in `:core`, which compares the stored device
 * version against the shipped constant. The screen only reports the tap.
 *
 * The back gesture is swallowed: a freshly installed user must not reach NT-02 without seeing this.
 * The layout matches the NT-02 welcome — a brand mark, a warm headline, and icon-led rows instead of
 * stacked cards — so onboarding reads as one flow.
 */
@Composable
fun NoticeScreen(
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
    noticePolicy: ProductNoticePolicy = NeuroTruthApp.from(LocalContext.current).noticePolicy,
) {
    BackHandler(enabled = true) { /* NT-01 cannot be skipped with the back gesture. */ }

    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                PrimaryButton(
                    text = "확인하고 계속",
                    onClick = {
                        noticePolicy.acknowledge()
                        onAcknowledged()
                    },
                    contentDescription = "제품 안내를 확인하고 가입 화면으로 이동",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))

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
                text = "NeuroTruth",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "갈망 상황을 기록하고 대화를 돕는 연구용 보조 시스템이에요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))

            FeatureRow(
                icon = Icons.Outlined.SelfImprovement,
                tint = MaterialTheme.colorScheme.primaryContainer,
                onTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = "이런 분께 도움이 돼요",
                body = "치료 중이거나 치료 의지가 있고, 갈망 상황에서 지속적인 기록과 대화 지원이 필요한 분.",
            )
            Spacer(Modifier.height(22.dp))
            FeatureRow(
                icon = Icons.Outlined.Info,
                tint = MaterialTheme.colorScheme.tertiaryContainer,
                onTint = MaterialTheme.colorScheme.onTertiaryContainer,
                title = "꼭 확인해 주세요",
                body = "의료행위·진단·처방·응급 대응을 대신하지 않아요. 기록은 실시간 감시가 아니며, 연락이나 대응을 보장하지 않아요.",
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    onTint: Color,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.semantics { contentDescription = "$title. $body" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = onTint, modifier = Modifier.size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
