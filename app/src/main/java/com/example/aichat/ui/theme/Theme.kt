package com.example.aichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary          = Color(0xFF1A73E8),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    secondary        = Color(0xFF34A853),
    background       = Color(0xFFF8F9FA),
    surface          = Color.White,
    surfaceVariant   = Color(0xFFE8EAED)
)

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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content     = content
    )
}
