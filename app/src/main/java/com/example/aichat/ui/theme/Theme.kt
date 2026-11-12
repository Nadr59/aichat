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

// Dark Emerald
private val EmeraldBackground = Color(0xFF061A16)
private val EmeraldCard       = Color(0xFF0C2922)
private val EmeraldMint       = Color(0xFF49E6B1)
private val EmeraldSecondary  = Color(0xFF164438)
private val EmeraldText       = Color(0xFFE6FFF5)
private val EmeraldTextSoft   = Color(0xFFA5C2B6)
private val EmeraldOnMint     = Color(0xFF04231A)
private val EmeraldBorder     = Color(0xFF3E6B5C)

private val DarkColors = darkColorScheme(
    primary               = EmeraldMint,
    onPrimary             = EmeraldOnMint,
    primaryContainer      = EmeraldSecondary,
    onPrimaryContainer    = EmeraldText,
    secondary             = Color(0xFF7FD9B8),
    onSecondary           = EmeraldOnMint,
    secondaryContainer    = EmeraldSecondary,
    onSecondaryContainer  = EmeraldText,
    background            = EmeraldBackground,
    onBackground          = EmeraldText,
    surface               = EmeraldCard,
    onSurface             = EmeraldText,
    surfaceVariant        = EmeraldSecondary,
    onSurfaceVariant      = EmeraldTextSoft,
    outline               = EmeraldBorder
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
