package com.ibridge.otg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

data class AccentChoice(
    val name: String,
    val color: Color?,
    val swatch: Color,
)

val accentChoices: List<AccentChoice> = listOf(
    AccentChoice("默认", null, Color(0xFF3482FF)),
    AccentChoice("青蓝", Color(0xFF3482FF), Color(0xFF3482FF)),
    AccentChoice("翠绿", Color(0xFF34C759), Color(0xFF34C759)),
    AccentChoice("暖橙", Color(0xFFFF9500), Color(0xFFFF9500)),
    AccentChoice("紫罗兰", Color(0xFF7C4DFF), Color(0xFF7C4DFF)),
    AccentChoice("玫红", Color(0xFFFF2D55), Color(0xFFFF2D55)),
    AccentChoice("湖青", Color(0xFF00BCD4), Color(0xFF00BCD4)),
)

fun isDarkTheme(mode: Int): Boolean = when (mode) {
    1 -> false
    2 -> true
    else -> false
}

@Composable
fun resolveDarkTheme(mode: Int): Boolean = when (mode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun IBridgeTheme(
    themeMode: Int,
    accentIndex: Int,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(themeMode)
    val accent = accentChoices.getOrNull(accentIndex) ?: accentChoices.first()

    val controller = remember(themeMode, accentIndex, dark) {
        if (accent.color == null) {
            ThemeController(
                colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                isDark = dark,
            )
        } else {
            ThemeController(
                colorSchemeMode = ColorSchemeMode.MonetSystem,
                keyColor = accent.color,
                isDark = dark,
            )
        }
    }

    MiuixTheme(controller = controller, content = content)
}
