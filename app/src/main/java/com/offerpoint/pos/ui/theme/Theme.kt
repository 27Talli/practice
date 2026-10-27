package com.offerpoint.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.offerpoint.pos.domain.model.*

val LocalWorkspacePreferences = staticCompositionLocalOf { WorkspacePreferences() }

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4EA), onPrimaryContainer = Color(0xFF003A32),
    secondary = Color(0xFF49655E), secondaryContainer = Color(0xFFE1EDE7),
    tertiary = Color(0xFF866030), tertiaryContainer = Color(0xFFFFE5BF),
    background = Color(0xFFF5F7F6), onBackground = Color(0xFF172822),
    surface = Color(0xFFFAFDFC), onSurface = Color(0xFF172822),
    surfaceVariant = Color(0xFFE5ECE8), onSurfaceVariant = Color(0xFF4A5E55),
    outline = Color(0xFF718278), outlineVariant = Color(0xFFD4DFD8),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF72D8BF), onPrimary = Color(0xFF00392F),
    primaryContainer = Color(0xFF005044), onPrimaryContainer = Color(0xFF95F5D9),
    secondary = Color(0xFFB1CCC0), secondaryContainer = Color(0xFF354C42),
    tertiary = Color(0xFFF1BF83), tertiaryContainer = Color(0xFF654017),
    background = Color(0xFF101C17), onBackground = Color(0xFFDEE9E1),
    surface = Color(0xFF14211A), onSurface = Color(0xFFDEE9E1),
    surfaceVariant = Color(0xFF34473D), onSurfaceVariant = Color(0xFFBDCEC2),
    outline = Color(0xFF8A9C90), outlineVariant = Color(0xFF3D5045),
)

private val PosTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private fun colors(dark: Boolean, accent: AppColor): androidx.compose.material3.ColorScheme {
    val base = if (dark) DarkColors else LightColors
    if (accent == AppColor.TEAL) return base
    val shades = when (accent) {
        AppColor.BLUE -> if (dark) listOf(0xFFA8C7FA, 0xFF003060, 0xFF194675, 0xFFD6E4FF) else listOf(0xFF1D559C, 0xFFFFFFFF, 0xFFD6E4FF, 0xFF001B3D)
        AppColor.PURPLE -> if (dark) listOf(0xFFD7BAFF, 0xFF3B195C, 0xFF523274, 0xFFF0DBFF) else listOf(0xFF70438F, 0xFFFFFFFF, 0xFFF0DBFF, 0xFF2A0845)
        AppColor.AMBER -> if (dark) listOf(0xFFFFBA68, 0xFF482900, 0xFF663E08, 0xFFFFDDB6) else listOf(0xFF805319, 0xFFFFFFFF, 0xFFFFDDB6, 0xFF2B1700)
        AppColor.TEAL -> error("The default palette is handled above.")
    }
    return base.copy(primary = Color(shades[0]), onPrimary = Color(shades[1]), primaryContainer = Color(shades[2]), onPrimaryContainer = Color(shades[3]))
}

private fun TextStyle.scaled(factor: Float) = copy(
    fontSize = if (fontSize == TextUnit.Unspecified) fontSize else fontSize * factor,
    lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else lineHeight * factor,
)

private fun typography(size: AppTextSize): Typography {
    val scale = size.multiplier
    return PosTypography.copy(
        displayLarge = PosTypography.displayLarge.scaled(scale), displayMedium = PosTypography.displayMedium.scaled(scale), displaySmall = PosTypography.displaySmall.scaled(scale),
        headlineLarge = PosTypography.headlineLarge.scaled(scale), headlineMedium = PosTypography.headlineMedium.scaled(scale), headlineSmall = PosTypography.headlineSmall.scaled(scale),
        titleLarge = PosTypography.titleLarge.scaled(scale), titleMedium = PosTypography.titleMedium.scaled(scale), titleSmall = PosTypography.titleSmall.scaled(scale),
        bodyLarge = PosTypography.bodyLarge.scaled(scale), bodyMedium = PosTypography.bodyMedium.scaled(scale), bodySmall = PosTypography.bodySmall.scaled(scale),
        labelLarge = PosTypography.labelLarge.scaled(scale), labelMedium = PosTypography.labelMedium.scaled(scale), labelSmall = PosTypography.labelSmall.scaled(scale),
    )
}

@Composable
fun OfferPointTheme(darkTheme: Boolean? = null, preferences: WorkspacePreferences = LocalWorkspacePreferences.current, content: @Composable () -> Unit) {
    val dark = darkTheme ?: when (preferences.appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    MaterialTheme(colorScheme = colors(dark, preferences.color), typography = typography(preferences.textSize), content = content)
}
