package com.opus.music.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Outro: deep midnight violet + warm brass accent (dark) / clean paper white + brass (light).
val Brass = Color(0xFFE8B04B)
val BrassDim = Color(0xFFB9832F)
val Violet = Color(0xFF9D7BFF)
val Midnight = Color(0xFF0C0A12)
val MidnightSurface = Color(0xFF14101D)
val MidnightCard = Color(0xFF1C1628)
val MidnightElevated = Color(0xFF241C33)
val TextPrimary = Color(0xFFF5F0E6)
val TextSecondary = Color(0xFFA79FB5)

// Light theme: clean white surfaces, warm ink text, same brass brand accent.
val Paper = Color(0xFFFFFFFF)
val PaperSurface = Color(0xFFFAF7F1)
val PaperCard = Color(0xFFF3EEE4)
val PaperElevated = Color(0xFFEAE3D3)
val InkPrimary = Color(0xFF1D1A15)
val InkSecondary = Color(0xFF6E6558)
val BrassDeep = Color(0xFF9A6B1E) // darker brass for text/icons on white (contrast)
val VioletDeep = Color(0xFF5F4BC4) // darker violet for contrast on white

private val OpusDarkColors = darkColorScheme(
    primary = Brass,
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFF3A2A10),
    onPrimaryContainer = Brass,
    secondary = Violet,
    onSecondary = Color.White,
    background = Midnight,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = MidnightElevated,
    outline = Color(0xFF3A3247),
    error = Color(0xFFFF8A80)
)

private val OpusLightColors = lightColorScheme(
    primary = BrassDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E3B8),
    onPrimaryContainer = Color(0xFF4A3208),
    secondary = VioletDeep,
    onSecondary = Color.White,
    background = Paper,
    onBackground = InkPrimary,
    surface = PaperSurface,
    onSurface = InkPrimary,
    surfaceVariant = PaperCard,
    onSurfaceVariant = InkSecondary,
    surfaceContainerHigh = PaperElevated,
    outline = Color(0xFFD9D0BD),
    error = Color(0xFFBA1A1A)
)

private fun opusTypography(secondaryText: Color) = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, color = secondaryText),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun OpusTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // bodySmall carries the "secondary text" tint; keep it theme-aware so the
    // dark theme looks exactly as before while light stays readable.
    val typography = androidx.compose.runtime.remember(darkTheme) {
        opusTypography(if (darkTheme) TextSecondary else InkSecondary)
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> OpusDarkColors
        else -> OpusLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
