package com.leatherbound.listening.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.leatherbound.listening.data.AppSettings
import kotlinx.coroutines.delay
import java.time.LocalTime
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leatherbound.listening.data.ThemeMode

// Palette drawn from the app icon: oxblood leather, gilt tooling, parchment pages, dark ink.
object Leather {
    val Oxblood = Color(0xFF6B3A22)
    val Saddle = Color(0xFF8A4B2A)
    val DarkLeather = Color(0xFF1C1411)
    val Hide = Color(0xFF251A15)
    val Gilt = Color(0xFFC9A45C)
    val PaleGilt = Color(0xFFE2C98F)
    val Parchment = Color(0xFFF3E9D2)
    val Vellum = Color(0xFFF8F0DC)
    val Ink = Color(0xFF2B1D14)
}

private val LightColors = lightColorScheme(
    primary = Leather.Oxblood,
    onPrimary = Leather.Vellum,
    primaryContainer = Color(0xFFE9CDB4),
    onPrimaryContainer = Color(0xFF3B1D0D),
    secondary = Color(0xFF9A7432),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DDB0),
    onSecondaryContainer = Color(0xFF2E2108),
    tertiary = Color(0xFF3E5641),
    background = Leather.Parchment,
    onBackground = Leather.Ink,
    surface = Leather.Parchment,
    onSurface = Leather.Ink,
    surfaceVariant = Color(0xFFE8DABA),
    onSurfaceVariant = Color(0xFF5A4636),
    surfaceContainer = Color(0xFFEDE1C5),
    surfaceContainerHigh = Color(0xFFE7D9BA),
    surfaceContainerLow = Leather.Vellum,
    outline = Color(0xFFB89B6A),
    outlineVariant = Color(0xFFD6C29A),
)

private val DarkColors = darkColorScheme(
    primary = Leather.Gilt,
    onPrimary = Color(0xFF2A1A10),
    primaryContainer = Leather.Oxblood,
    onPrimaryContainer = Leather.PaleGilt,
    secondary = Color(0xFFD08A5E),
    onSecondary = Color(0xFF2A140A),
    secondaryContainer = Color(0xFF4A2E1E),
    onSecondaryContainer = Leather.PaleGilt,
    tertiary = Color(0xFF9DB89F),
    background = Leather.DarkLeather,
    onBackground = Color(0xFFEADBC0),
    surface = Leather.DarkLeather,
    onSurface = Color(0xFFEADBC0),
    surfaceVariant = Color(0xFF3A2A21),
    onSurfaceVariant = Color(0xFFCDB898),
    surfaceContainer = Leather.Hide,
    surfaceContainerHigh = Color(0xFF2E211A),
    surfaceContainerLow = Color(0xFF201713),
    outline = Color(0xFF8C7152),
    outlineVariant = Color(0xFF4E3B2D),
)

// Lamplight: for reading in bed. Near-black, warm, dim, no blue light; amber text at lower contrast.
private val LamplightColors = darkColorScheme(
    primary = Color(0xFFB8894A),
    onPrimary = Color(0xFF1A0F06),
    primaryContainer = Color(0xFF3A2414),
    onPrimaryContainer = Color(0xFFD8B98A),
    secondary = Color(0xFFA8703F),
    onSecondary = Color(0xFF1A0E05),
    secondaryContainer = Color(0xFF2E1C10),
    onSecondaryContainer = Color(0xFFCFAE80),
    tertiary = Color(0xFF8A9A70),
    background = Color(0xFF0E0906),
    onBackground = Color(0xFFC8A878),
    surface = Color(0xFF0E0906),
    onSurface = Color(0xFFC8A878),
    surfaceVariant = Color(0xFF24180F),
    onSurfaceVariant = Color(0xFF9C8060),
    surfaceContainer = Color(0xFF140D08),
    surfaceContainerHigh = Color(0xFF1C130C),
    surfaceContainerHighest = Color(0xFF231810),
    surfaceContainerLow = Color(0xFF110B07),
    surfaceContainerLowest = Color(0xFF0A0604),
    inverseSurface = Color(0xFFC8A878),
    inverseOnSurface = Color(0xFF140D08),
    outline = Color(0xFF6E5638),
    outlineVariant = Color(0xFF33251A),
    error = Color(0xFFC07060),
)

/** True on tablets / unfolded foldables / landscape, where layouts become two-page spreads. */
val LocalWideLayout = compositionLocalOf { false }

@OptIn(ExperimentalTextApi::class)
private fun bookFont(context: Context): FontFamily {
    // Libre Baskerville is bundled by the fetchFonts Gradle task. Fall back to the system serif if absent.
    val available = runCatching { context.assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
    fun font(file: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
        if (file in available) Font("fonts/$file", context.assets, weight, style, FontVariation.Settings(FontVariation.weight(weight.weight)))
        else null
    val fonts = listOfNotNull(
        font("LibreBaskerville-Regular.ttf", FontWeight.Normal),
        font("LibreBaskerville-Bold.ttf", FontWeight.Bold),
        font("LibreBaskerville-Bold.ttf", FontWeight.SemiBold),
        font("LibreBaskerville-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
    )
    return if (fonts.isEmpty()) FontFamily.Serif else FontFamily(fonts)
}

private fun bookTypography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.book(size: Int? = null, line: Int? = null, weight: FontWeight? = null) = copy(
        fontFamily = family,
        fontSize = size?.sp ?: fontSize,
        lineHeight = line?.sp ?: lineHeight,
        fontWeight = weight ?: fontWeight,
        letterSpacing = 0.sp,
    )
    return Typography(
        displayLarge = base.displayLarge.book(48, 56),
        displayMedium = base.displayMedium.book(40, 48),
        displaySmall = base.displaySmall.book(32, 40),
        headlineLarge = base.headlineLarge.book(28, 36),
        headlineMedium = base.headlineMedium.book(24, 32),
        headlineSmall = base.headlineSmall.book(20, 28),
        titleLarge = base.titleLarge.book(20, 28, FontWeight.Bold),
        titleMedium = base.titleMedium.book(16, 22, FontWeight.Bold),
        titleSmall = base.titleSmall.book(14, 20, FontWeight.Bold),
        bodyLarge = base.bodyLarge.book(16, 26),
        bodyMedium = base.bodyMedium.book(14, 22),
        bodySmall = base.bodySmall.book(12, 18),
        labelLarge = base.labelLarge.book(14, 20),
        labelMedium = base.labelMedium.book(12, 16),
        labelSmall = base.labelSmall.book(11, 16),
    )
}

/** The theme to use now: [AppSettings.lamplightAtNight] overrides the chosen one at night. */
@Composable
fun currentThemeMode(settings: AppSettings): ThemeMode {
    val hour by produceState(LocalTime.now().hour) {
        while (true) {
            value = LocalTime.now().hour
            delay(30_000)
        }
    }
    return if (settings.lamplightAtNight && settings.isNight(hour)) ThemeMode.LAMPLIGHT else settings.theme
}

@Composable
fun LeatherboundTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (mode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.LAMPLIGHT -> LamplightColors
    }
    val light = colors === LightColors
    // Status and navigation bar icons follow the theme, not the system setting.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    val context = LocalContext.current
    val typography = remember { bookTypography(bookFont(context)) }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
