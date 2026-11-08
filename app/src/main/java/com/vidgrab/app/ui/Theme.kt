package com.vidgrab.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * VidGrab 主题
 * 关闭动态取色，使用固定品牌色（Color.kt / Type.kt）
 * 深色模式跟随系统，但配色固定
 */

@Composable
fun VidGrabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = VidGrabTypography,
        content = content
    )
}

/** 将 Compose Color 转为 Android ARGB int */
private fun Color.toAndroidArgb(): Int = value.toInt()

/** 状态栏/导航栏颜色同步（在 MainActivity / PlayerActivity 的 Composable 中调用） */
fun syncSystemBars(activity: Activity, darkTheme: Boolean) {
    val window = activity.window

    val (bg, nav) = if (darkTheme) {
        DarkBackground to DarkSurface
    } else {
        Paper to Panel
    }

    window.statusBarColor = bg.toAndroidArgb()
    window.navigationBarColor = nav.toAndroidArgb()
    window.setStatusBarContrastEnforced(false)
    window.setNavigationBarContrastEnforced(false)

    val decor = window.decorView
    var flags = decor.systemUiVisibility
    if (darkTheme) {
        flags = flags and (0xFFFFBFFF.toInt())
    } else {
        flags = flags or 0x00008000 or 0x00000010 // LIGHT_STATUS_BAR | LIGHT_NAVIGATION_BAR
    }
    decor.systemUiVisibility = flags
}

/** 仅状态栏同步（用于沉浸式页面如 PlayerActivity） */
fun syncStatusBarOnly(activity: Activity, darkTheme: Boolean) {
    val window = activity.window
    val bg = if (darkTheme) DarkBackground else Paper
    window.statusBarColor = bg.toAndroidArgb()
    window.setStatusBarContrastEnforced(false)
    val decor = window.decorView
    var flags = decor.systemUiVisibility
    if (darkTheme) {
        flags = flags and (0xFFFF7FFF.toInt())
    } else {
        flags = flags or 0x00008000 // LIGHT_STATUS_BAR
    }
    decor.systemUiVisibility = flags
}