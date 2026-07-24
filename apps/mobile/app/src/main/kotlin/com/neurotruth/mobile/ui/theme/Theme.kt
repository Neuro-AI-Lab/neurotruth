package com.neurotruth.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neurotruth.mobile.core.CravingStage

private val LightColors = lightColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = BrandOnPrimaryLight,
    primaryContainer = BrandPrimaryContainerLight,
    onPrimaryContainer = BrandOnPrimaryContainerLight,
    secondary = BrandSecondaryLight,
    onSecondary = BrandOnSecondaryLight,
    secondaryContainer = BrandSecondaryContainerLight,
    onSecondaryContainer = BrandOnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = BrandOnPrimaryDark,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandOnPrimaryContainerDark,
    secondary = BrandSecondaryDark,
    onSecondary = BrandOnSecondaryDark,
    secondaryContainer = BrandSecondaryContainerDark,
    onSecondaryContainer = BrandOnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

/** Shared spacing so no screen invents its own rhythm. */
object NeuroTruthSpacing {
    val screenHorizontal = 20.dp
    val screenVertical = 20.dp
    val betweenCards = 16.dp
    val cardPadding = 20.dp
    val betweenRows = 12.dp
    val minTouchTarget = 52.dp

    /** Breathing room above a screen's large title. */
    val titleTop = 20.dp

    /** Gap between logical sections within one screen. */
    val sectionGap = 24.dp
}

/**
 * Softer, larger corner radii than the Material default.
 *
 * Cards use `large` (24dp) and pills use a full round; the effect is a calm, modern surface rather
 * than the tighter default 12dp corners.
 */
val NeuroTruthShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun NeuroTruthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NeuroTruthTypography,
        shapes = NeuroTruthShapes,
        content = content,
    )
}

/**
 * Accent for a craving band.
 *
 * This ramp is deliberately kept OFF the brand blue: green → gold → amber → muted red reads as a
 * calm escalation and never collides with a tappable blue element. In particular 관찰 is gold, not
 * blue — a blue observe band would look like a brand control. The ramp also stops short of an alarm
 * red, because 안전/관찰/주의/심각 are research display bands, not clinical risk levels.
 */
@Composable
@ReadOnlyComposable
fun cravingAccent(stage: CravingStage?): Color {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    return when (stage) {
        CravingStage.SAFE -> if (dark) Color(0xFF5FC49E) else Color(0xFF2E8F6B)
        CravingStage.OBSERVE -> if (dark) Color(0xFFE0C275) else Color(0xFFB68E2E)
        CravingStage.CAUTION -> if (dark) Color(0xFFE0A46A) else Color(0xFFC67A38)
        CravingStage.SEVERE -> if (dark) Color(0xFFE08578) else Color(0xFFC0564A)
        null -> MaterialTheme.colorScheme.outline
    }
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
