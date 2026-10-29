package com.vidgrab.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 浅色
val Paper = Color(0xFFEEF2F4)
val Panel = Color(0xFFF8FAFB)
val Ink = Color(0xFF10161B)
val Body = Color(0xFF2A3742)
val Muted = Color(0xFF586874)
val Line = Color(0xFFC7D2DA)
val Hair = Color(0xFFE1E8ED)
val Blue = Color(0xFF0B5FFF)
val BlueSoft = Color(0xFFDCE7FF)
val Yellow = Color(0xFFF5B700)
val Dash = Color(0xFF6749C9)
val DashSoft = Color(0xFFE9E3FB)
val Green = Color(0xFF1C7A57)
val GreenSoft = Color(0xFFDDF1E8)
val Danger = Color(0xFFC62828)

private val LightScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueSoft,
    onPrimaryContainer = Color(0xFF00205C),
    secondary = Yellow,
    onSecondary = Ink,
    secondaryContainer = BlueSoft,
    onSecondaryContainer = Blue,
    background = Paper,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Hair,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerLow = Panel,
    surfaceContainerHigh = Color(0xFFF1F5F7),
    outline = Line,
    outlineVariant = Hair,
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenSoft,
    onTertiaryContainer = Color(0xFF003824),
    error = Danger,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FA6FF),
    onPrimary = Color(0xFF0A1A3D),
    primaryContainer = Color(0xFF123A8A),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFF5B700),
    onSecondary = Color(0xFF1A1400),
    secondaryContainer = Color(0xFF123A8A),
    onSecondaryContainer = Color(0xFFDCE7FF),
    background = Color(0xFF101820),
    onBackground = Color(0xFFE7EDF1),
    surface = Color(0xFF182129),
    onSurface = Color(0xFFE7EDF1),
    surfaceVariant = Color(0xFF243039),
    onSurfaceVariant = Color(0xFFA9B7C2),
    surfaceContainer = Color(0xFF182129),
    surfaceContainerLow = Color(0xFF141C23),
    surfaceContainerHigh = Color(0xFF1F2933),
    outline = Color(0xFF3A4853),
    outlineVariant = Color(0xFF2B3842),
    tertiary = Color(0xFF7FD1AE),
    onTertiary = Color(0xFF003825),
    tertiaryContainer = Color(0xFF005138),
    onTertiaryContainer = Color(0xFF9DEDC9),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF410002)
)

val VidGrabTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun VidGrabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = VidGrabTypography,
        content = content
    )
}
