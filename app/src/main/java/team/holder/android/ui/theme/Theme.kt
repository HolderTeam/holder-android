package team.holder.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import team.holder.android.HolderSettings

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun HolderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeOption by HolderSettings.themeOption(context).collectAsState(initial = HolderThemeOption.SYSTEM)
    val fontSizeOption by HolderSettings.fontSizeOption(context).collectAsState(initial = HolderFontSizeOption.SYSTEM)
    val fontFamilyOption by HolderSettings.fontFamilyOption(context)
        .collectAsState(initial = HolderFontFamilyOption.DEFAULT)

    val colorScheme = themeOption.fixedColorScheme(darkTheme) ?: when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // fontSizeOption.scale is an outright replacement of the ambient fontScale (see its own
    // doc comment) -- SYSTEM leaves the ambient density, from LocalDensity.current, untouched.
    val baseDensity = LocalDensity.current
    val density = fontSizeOption.scale?.let { scale -> Density(baseDensity.density, scale) } ?: baseDensity

    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography.withFontFamilyOption(fontFamilyOption),
        ) {
            if (themeOption == HolderThemeOption.WALNUT) {
                // WalnutScheme's background is transparent so every screen's Scaffold shows this
                // through instead of painting over it -- see WalnutScheme's comment.
                Box(modifier = Modifier.fillMaxSize()) {
                    WalnutBackground(modifier = Modifier.fillMaxSize())
                    content()
                }
            } else {
                content()
            }
        }
    }
}