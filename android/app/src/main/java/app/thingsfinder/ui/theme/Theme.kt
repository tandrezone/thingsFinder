package app.thingsfinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentDark,
    secondary = AccentDark,
    onSecondary = Color.White,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = AccentDark,
    tertiary = InfoText,
    tertiaryContainer = InfoBg,
    onTertiaryContainer = InfoText,
    background = Bg,
    onBackground = TextInk,
    surface = Bg,
    onSurface = TextInk,
    surfaceVariant = CodeBg,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Color(0xFFF1EEE8),
    surfaceContainerHighest = Color(0xFFEBE6DD),
    outline = BorderStrong,
    outlineVariant = Border,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerSoft,
    onErrorContainer = Color(0xFF7D2A24),
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentSoft,
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = DarkAccent,
    onSecondary = DarkOnAccent,
    secondaryContainer = DarkAccentSoft,
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFB8C8D8),
    tertiaryContainer = Color(0xFF2E3A46),
    onTertiaryContainer = Color(0xFFD5E2EE),
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkBg,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = DarkMuted,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF3A342E),
    outline = Color(0xFF5A5148),
    outlineVariant = DarkBorder,
    error = DarkDanger,
    onError = Color(0xFF3F0D09),
    errorContainer = DarkDangerSoft,
    onErrorContainer = Color(0xFFFFDAD5),
)

// --radius-sm / --radius-md / --radius-lg from style.css
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// The web app uses the system font stack, so the platform default (Roboto) is the faithful choice.
private val base = Typography()
private val AppTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

@Composable
fun ThingsFinderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
