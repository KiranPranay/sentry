package com.sentry.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sentry's palette.
 *
 * The assistant surface is drawn over whatever app the user was in, so it is dark and
 * translucent regardless of the system theme — a light panel over a dark app reads as
 * a bug, and the orb's colours only work against a dark ground.
 */
private val SentryDark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00204D),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB9C3DC),
    onSecondary = Color(0xFF232D42),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFE4E6EC),
    surfaceVariant = Color(0xFF1A1D26),
    onSurfaceVariant = Color(0xFFB6BAC6),
    background = Color(0xFF07080C),
    onBackground = Color(0xFFE4E6EC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val SentryLight = lightColorScheme(
    primary = Color(0xFF2B5CB8),
    onPrimary = Color.White,
    surface = Color(0xFFFBFBFF),
    onSurface = Color(0xFF11131A),
)

/** Slightly tighter and heavier than the Material default; it reads better at a glance. */
private val SentryTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

/**
 * @param forceDark true for the assistant overlay, which is always dark. The settings
 *   app passes false and follows the system.
 */
@Composable
fun SentryTheme(
    forceDark: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || isSystemInDarkTheme()
    val context = LocalContext.current

    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> SentryDark
        else -> SentryLight
    }

    MaterialTheme(colorScheme = colors, typography = SentryTypography, content = content)
}
