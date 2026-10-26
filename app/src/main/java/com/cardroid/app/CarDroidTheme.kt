package com.cardroid.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val CarDroidColors = darkColorScheme(
    primary = CarDroidPalette.Mint,
    onPrimary = CarDroidPalette.OnMint,
    primaryContainer = CarDroidPalette.MintContainer,
    onPrimaryContainer = Color(0xFFA5F7DB),
    secondary = CarDroidPalette.Sky,
    onSecondary = Color(0xFF003349),
    secondaryContainer = CarDroidPalette.SkyContainer,
    onSecondaryContainer = Color(0xFFCEEAF9),
    tertiary = CarDroidPalette.Citrus,
    onTertiary = Color(0xFF28350E),
    tertiaryContainer = CarDroidPalette.CitrusContainer,
    onTertiaryContainer = Color(0xFFE4F3BC),
    background = CarDroidPalette.Background,
    onBackground = CarDroidPalette.Text,
    surface = CarDroidPalette.Surface,
    onSurface = CarDroidPalette.Text,
    surfaceVariant = CarDroidPalette.SurfaceHigh,
    onSurfaceVariant = CarDroidPalette.Muted,
    surfaceDim = CarDroidPalette.Background,
    surfaceBright = CarDroidPalette.SurfaceHighest,
    surfaceContainerLowest = CarDroidPalette.Background,
    surfaceContainerLow = CarDroidPalette.Dock,
    surfaceContainer = CarDroidPalette.Surface,
    surfaceContainerHigh = CarDroidPalette.SurfaceHigh,
    surfaceContainerHighest = CarDroidPalette.SurfaceHighest,
    surfaceTint = CarDroidPalette.Mint,
    outline = CarDroidPalette.Outline,
    outlineVariant = CarDroidPalette.OutlineVariant,
    inverseSurface = CarDroidPalette.Text,
    inverseOnSurface = CarDroidPalette.Background,
    inversePrimary = CarDroidPalette.MintContainer,
    scrim = Color(0xFF020C09),
    error = CarDroidPalette.Error,
    onError = Color(0xFF48110A),
    errorContainer = CarDroidPalette.ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD4),
)

private val CarDroidTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

private val CarDroidShapes = Shapes()

@Composable
fun CarDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarDroidColors,
        typography = CarDroidTypography,
        shapes = CarDroidShapes,
        content = content,
    )
}
