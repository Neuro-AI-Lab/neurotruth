package com.neurotruth.mobile.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The shared visual vocabulary for the whole app — one screen frame, one card, one button set — so
 * no screen invents its own header, corner radius, or button shape.
 *
 * The look is Direction A ("clinical calm"): soft tonal surfaces instead of hard outlines, large
 * corner radii, pill buttons, and a large title with breathing room instead of a divider rule.
 */

/**
 * Standard screen frame: a background-coloured [Scaffold] with a large title, standard insets, and
 * an optional bottom bar. Content is laid out in a scrolling column with a consistent gap.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    contentGap: androidx.compose.ui.unit.Dp = NeuroTruthSpacing.betweenCards,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
    ) { insets ->
        val scrollModifier = if (scroll) Modifier.verticalScroll(scrollState) else Modifier
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .then(scrollModifier)
                .padding(
                    horizontal = NeuroTruthSpacing.screenHorizontal,
                    vertical = NeuroTruthSpacing.screenVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(contentGap),
        ) {
            ScreenTitle(title)
            content()
        }
    }
}

/** The large screen title. No divider — spacing and weight carry the hierarchy. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = modifier
            .padding(top = NeuroTruthSpacing.titleTop, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** A small tracked label that groups a section, e.g. 필수 동의 or 최근 1시간. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(top = 4.dp)
            .semantics { heading() },
    )
}

/** Tonal fill for a [SectionCard]. */
enum class CardTone { Default, Low, Hero, Highlight, Warm, Alert }

/**
 * A soft, filled, large-radius card — the single card used everywhere, replacing the earlier mix of
 * OutlinedCard and Card. It carries no hard border; the tonal fill separates it from the ground.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Default,
    elevation: androidx.compose.ui.unit.Dp? = null,
    contentPadding: PaddingValues = PaddingValues(NeuroTruthSpacing.cardPadding),
    contentGap: androidx.compose.ui.unit.Dp = NeuroTruthSpacing.betweenRows,
    content: @Composable ColumnScope.() -> Unit,
) {
    // White cards separate from the cool ground by shadow, not by a tinted fill. Each tone has a
    // resting shadow; the hero lifts more, and the tinted tones (Highlight/Warm/Alert) sit flat.
    val shadow: androidx.compose.ui.unit.Dp = elevation ?: when (tone) {
        CardTone.Hero -> 4.dp
        CardTone.Default -> 2.dp
        CardTone.Low -> 0.dp
        CardTone.Highlight, CardTone.Warm, CardTone.Alert -> 0.dp
    }
    val container: Color = when (tone) {
        CardTone.Default -> MaterialTheme.colorScheme.surfaceContainer
        CardTone.Low -> MaterialTheme.colorScheme.surfaceContainerLow
        // Hero lifts off the green wash: the lightest surface plus a soft shadow so the most
        // important card on a screen reads as the focal point instead of one green block among many.
        CardTone.Hero -> MaterialTheme.colorScheme.surfaceContainerLowest
        CardTone.Highlight -> MaterialTheme.colorScheme.secondaryContainer
        CardTone.Warm -> MaterialTheme.colorScheme.tertiaryContainer
        CardTone.Alert -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor: Color = when (tone) {
        CardTone.Highlight -> MaterialTheme.colorScheme.onSecondaryContainer
        CardTone.Warm -> MaterialTheme.colorScheme.onTertiaryContainer
        CardTone.Alert -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = contentColor,
        shadowElevation = shadow,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentGap),
            content = content,
        )
    }
}

/** Full-width filled pill — the primary action on a screen. Shows a spinner while [loading]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = CircleShape,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.heightIn(min = 20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Full-width tonal pill — the secondary action, softer than the primary and without a hard border. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NeuroTruthSpacing.minTouchTarget)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
