package com.neurotruth.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography sized so the layout survives body text scaled to at least 130%.
 *
 * Every style declares a line height of roughly 1.5x its font size and lets the first and last line
 * keep their full leading, so a wrapped Korean sentence at 130% does not clip against the line above
 * it. Nothing in the app pins a text container to a fixed dp height.
 */
private val ScalableLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun scalable(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = ScalableLineHeightStyle,
)

val NeuroTruthTypography: Typography = Typography(
    displaySmall = scalable(34, 46, FontWeight.Bold),
    headlineLarge = scalable(30, 42, FontWeight.Bold),
    headlineMedium = scalable(26, 36, FontWeight.Bold),
    headlineSmall = scalable(22, 32, FontWeight.SemiBold),
    titleLarge = scalable(20, 30, FontWeight.SemiBold),
    titleMedium = scalable(17, 26, FontWeight.SemiBold, 0.1),
    titleSmall = scalable(15, 24, FontWeight.SemiBold, 0.1),
    bodyLarge = scalable(17, 27, FontWeight.Normal, 0.4),
    bodyMedium = scalable(15, 24, FontWeight.Normal, 0.2),
    bodySmall = scalable(13, 21, FontWeight.Normal, 0.3),
    labelLarge = scalable(15, 22, FontWeight.SemiBold, 0.1),
    labelMedium = scalable(13, 20, FontWeight.Medium, 0.4),
    labelSmall = scalable(12, 18, FontWeight.Medium, 0.4),
)
