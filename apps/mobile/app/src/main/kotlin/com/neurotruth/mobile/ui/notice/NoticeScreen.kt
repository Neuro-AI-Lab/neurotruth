package com.neurotruth.mobile.ui.notice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ProductNoticePolicy
import com.neurotruth.mobile.ui.theme.CardTone
import com.neurotruth.mobile.ui.theme.NeuroTruthSpacing
import com.neurotruth.mobile.ui.theme.PrimaryButton
import com.neurotruth.mobile.ui.theme.ScreenScaffold
import com.neurotruth.mobile.ui.theme.SectionCard

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

    ScreenScaffold(
        title = "제품 안내",
        modifier = modifier,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                PrimaryButton(
                    text = "확인하고 계속",
                    onClick = {
                        noticePolicy.acknowledge()
                        onAcknowledged()
                    },
                    contentDescription = "제품 안내를 확인하고 가입 화면으로 이동",
                    modifier = Modifier.padding(
                        horizontal = NeuroTruthSpacing.screenHorizontal,
                        vertical = NeuroTruthSpacing.screenVertical,
                    ),
                )
            }
        },
    ) {
        NoticeCard(
            title = "NeuroTruth",
            body = "갈망 상황을 기록하고 대화를 돕는 연구용 보조 시스템입니다.",
            tone = CardTone.Highlight,
            description = "제품 정의",
        )
        NoticeCard(
            title = "이런 분께 도움이 됩니다",
            body = "치료 중이거나 치료 의지가 있고, 갈망 상황에서 지속적인 기록과 대화 지원이 필요한 분",
            tone = CardTone.Default,
            description = "대상 사용자",
        )
        NoticeCard(
            title = "꼭 확인해 주세요",
            body = "이 앱은 의료행위·진단·처방·응급 대응을 대신하지 않습니다. " +
                "기록은 실시간 감시가 아니며, 연락이나 대응을 보장하지 않습니다.",
            tone = CardTone.Warm,
            description = "제한 사항 안내",
        )
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    tone: CardTone,
    description: String,
) {
    SectionCard(
        tone = tone,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
    }
}
