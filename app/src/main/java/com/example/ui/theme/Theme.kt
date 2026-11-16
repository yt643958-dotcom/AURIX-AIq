package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val AurixDarkColorScheme = darkColorScheme(
    primary = AurixNeonCyan,
    onPrimary = Color(0xFF00141A),
    primaryContainer = Color(0xFF0E294B),
    onPrimaryContainer = AurixNeonCyan,
    secondary = AurixNeonPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B184B),
    onSecondaryContainer = Color(0xFFE7CEFF),
    tertiary = AurixElectricBlue,
    onTertiary = Color.White,
    background = AurixObsidian,
    onBackground = AurixTextPrimary,
    surface = AurixMidnight,
    onSurface = AurixTextPrimary,
    surfaceVariant = AurixSurface,
    onSurfaceVariant = AurixTextSecondary,
    surfaceContainerLowest = AurixObsidian,
    surfaceContainerLow = AurixMidnight,
    surfaceContainer = AurixSurface,
    surfaceContainerHigh = AurixSurfaceElevated,
    outline = AurixGlassBorder,
    outlineVariant = AurixDivider,
    error = AurixRoseError,
    onError = Color.White
)

val AurixShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AurixTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(view) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = AurixDarkColorScheme,
        typography = AurixTypography,
        shapes = AurixShapes,
        content = content
    )
}
