package com.vidgrab.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * VidGrab 固定品牌色定义
 * 参考 docs/design/vidgrab-visual-spec.md
 * 关闭动态取色，保证跨设备一致性
 */

// 浅色 token
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
val Error = Color(0xFFC62828)

val LightScheme = lightColorScheme(
    background = Paper,
    surface = Panel,
    surfaceVariant = Hair,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    primary = Blue,
    onPrimary = Color.White,
    secondary = Yellow,
    onSecondary = Ink,
    outline = Line,
    outlineVariant = Hair,
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenSoft,
    onTertiaryContainer = Color(0xFF003824),
    error = Error,
    onError = Color.White
)

// 深色 token
val DarkBackground = Color(0xFF101820)
val DarkSurface = Color(0xFF182129)
val DarkSurfaceVariant = Color(0xFF243039)
val DarkOnBackground = Color(0xFFE7EDF1)
val DarkOnSurfaceVariant = Color(0xFFA9B7C2)
val DarkPrimary = Color(0xFF7FA6FF)
val DarkOnPrimary = Color(0xFF0A1A3D)
val DarkSecondary = Color(0xFFF5B700)
val DarkOnSecondary = Color(0xFF1A1400)
val DarkOutline = Color(0xFF3A4853)
val DarkError = Color(0xFFFF8A80)
val DarkBlueSoft = Color(0xFFDCE7FF)
val DarkDashSoft = Color(0xFFE9E3FB)
val DarkGreenSoft = Color(0xFFDDF1E8)

val DarkScheme = darkColorScheme(
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnBackground,
    onSurfaceVariant = DarkOnSurfaceVariant,
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    tertiary = Color(0xFF7FD1AE),
    onTertiary = Color(0xFF003825),
    tertiaryContainer = Color(0xFF005138),
    onTertiaryContainer = Color(0xFF9DEDC9),
    error = DarkError,
    onError = Color(0xFF410002)
)

/** 嗅探结果标签色（不随主题变化，保持语义一致） */
object SniffChips {
    // HLS
    val HlsBg = BlueSoft
    val HlsFg = Blue
    // DASH
    val DashBg = DashSoft
    val DashFg = Dash
    // MP4/VIDEO
    val VideoBg = GreenSoft
    val VideoFg = Green
    // AUDIO
    val AudioBg = Hair
    val AudioFg = Muted
}