package com.example.aichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// اجعلها false لإعادة اتباع وضع الهاتف (فاتح/داكن)
private const val ALWAYS_LIGHT = true

// Soft Violet (فاتح)
private val VioletBackground = Color(0xFFF6F2FF)
private val VioletSurface    = Color(0xFFFFFFFF)
private val VioletPrimary    = Color(0xFF5B21D6)
private val VioletContainer  = Color(0xFFE6DBFF)
private val VioletSoft       = Color(0xFFEDE5FF)
private val VioletDeep       = Color(0xFF2A0B73)
private val VioletText       = Color(0xFF1B1230)
private val VioletTextSoft   = Color(0xFF5F5878)
private val VioletOutline    = Color(0xFFC9BCEB)

private val LightColors = lightColorScheme(
    primary              = VioletPrimary,
    onPrimary            = Color.White,
    primaryContainer     = VioletContainer,
    onPrimaryContainer   = VioletDeep,
    secondary            = Color(0xFF6B4BC8),
    onSecondary          = Color.White,
    secondaryContainer   = VioletSoft,
    onSecondaryContainer = VioletDeep,
    background           = VioletBackground,
    onBackground         = VioletText,
    surface              = VioletSurface,
    onSurface            = VioletText,
    surfaceVariant       = VioletSoft,
    onSurfaceVariant     = VioletTextSoft,
    outline              = VioletOutline
)

// الداكن القديم كما كان (غير مستعمل ما دام ALWAYS_LIGHT = true)
private val DarkColors = darkColorScheme(
    primary          = Color(0xFF8AB4F8),
    onPrimary        = Color(0xFF1A1A2E),
    primaryContainer = Color(0xFF1A4A8A),
    secondary        = Color(0xFF81C995),
    background       = Color(0xFF1A1A1A),
    surface          = Color(0xFF2D2D2D),
    surfaceVariant   = Color(0xFF3D3D3D)
)

@Composable
fun AiChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useDark = darkTheme && !ALWAYS_LIGHT
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        content     = content
    )
}
