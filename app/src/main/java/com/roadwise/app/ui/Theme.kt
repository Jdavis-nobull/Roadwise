package com.roadwise.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RwColors {
    val Blue = Color(0xFF1A73E8)
    val Green = Color(0xFF0F9D58)
    val NavGreen = Color(0xFF136F45)
    val Orange = Color(0xFFFF8F00)
    val Red = Color(0xFFE53935)
    val Purple = Color(0xFF7E57C2)
}

private val Light = lightColorScheme(
    primary = RwColors.Blue,
    onPrimary = Color.White,
    secondary = RwColors.Orange,
    tertiary = RwColors.Green,
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F3F4),
    background = Color(0xFFF8F9FA),
    error = RwColors.Red,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1F3A),
    secondary = Color(0xFFFFB74D),
    tertiary = Color(0xFF81C995),
    surface = Color(0xFF202124),
    surfaceVariant = Color(0xFF2D2F33),
    background = Color(0xFF17181A),
    error = Color(0xFFF28B82),
)

@Composable
fun RoadwiseTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
