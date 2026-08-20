package team.holder.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The set of selectable themes. SYSTEM follows the device's light/dark setting (and Material
 * You dynamic color, where available) exactly as before this existed. HIGH_CONTRAST is the one
 * other entry that still follows system light/dark -- it's an accessibility theme, not an
 * aesthetic one, so it has to compose with whichever the device is already in rather than
 * overriding it. Every other entry is a single fixed palette representing a specific look, and
 * intentionally does not change with system light/dark.
 */
enum class HolderThemeOption(val label: String, val description: String) {
    SYSTEM("System", "Follows your device's light/dark setting."),
    HIGH_CONTRAST("High Contrast", "Accessibility theme: maximum contrast."),
    MONOCHROME("Monochrome", "Deliberately austere."),
    PAPER("Paper", "A Classic Index Card."),
    TERMINAL("Terminal", "There is no spoon."),
    SUOMI("Suomi", "The brave eat the soup."),
    BURGUNDY("Burgundy", "Old Money."),
    WALNUT("Walnut", "Resolute."),
    PINK("Pink", "Make it happen"),
    ROSE("Rose", "Subtly Strong and Refined."),
    RACING_GREEN("Racing Green", "Pedal to the metal."),
    MAGNUS("Magnus", "You Ancient, You Free."),
    ALBION("Albion", "Rule the waves."),
}

/** A representative color for each theme's swatch dot in the picker. Not used for rendering. */
fun HolderThemeOption.swatchColor(): Color = when (this) {
    HolderThemeOption.SYSTEM -> Color(0xFF9E9E9E)
    HolderThemeOption.PINK -> PinkPrimary
    HolderThemeOption.ROSE -> RosePrimary
    HolderThemeOption.RACING_GREEN -> RacingGreenPrimary
    HolderThemeOption.MAGNUS -> MagnusBlue
    HolderThemeOption.ALBION -> AlbionRed
    HolderThemeOption.TERMINAL -> TerminalPrimary
    HolderThemeOption.SUOMI -> SuomiPrimary
    HolderThemeOption.PAPER -> PaperPrimary
    HolderThemeOption.BURGUNDY -> BurgundyPrimary
    HolderThemeOption.WALNUT -> WalnutBase
    HolderThemeOption.MONOCHROME -> Color(0xFF000000)
    HolderThemeOption.HIGH_CONTRAST -> Color(0xFFFFD400)
}

private val PinkPrimary = Color(0xFFE4007C)
private val RosePrimary = Color(0xFFB76E79)
private val RacingGreenPrimary = Color(0xFF01411C)
private val MagnusBlue = Color(0xFF0058A3)
private val MagnusYellow = Color(0xFFFFDB00)
private val AlbionRed = Color(0xFFC8102E)
private val AlbionBlue = Color(0xFF012169)
private val TerminalPrimary = Color(0xFF39FF14)
private val SuomiPrimary = Color(0xFF003580)
private val PaperPrimary = Color(0xFF2B2926)
private val BurgundyPrimary = Color(0xFF6D1220)

/**
 * Building a [ColorScheme] via [lightColorScheme]/[darkColorScheme] with only primary/
 * secondary/tertiary/background/surface set leaves every "container" role (the ones that
 * FloatingActionButton, DropdownMenu, the markdown toolbar, AlertDialog, etc. actually paint
 * with by default -- primaryContainer, surfaceContainer and friends) on Material's baseline
 * purple palette, which bleeds through as an off-theme purple FAB/menu on every one of these.
 * This derives the full role set instead, so nothing falls back to that baseline. Container
 * roles are a light tint of their accent blended into `surface`; the surfaceContainer "elevation"
 * family is a graduated tint of `onSurface` into `surface`, same as M3's own tonal design intent.
 */
private fun holderColorScheme(
    primary: Color,
    onPrimary: Color,
    secondary: Color,
    onSecondary: Color,
    tertiary: Color,
    onTertiary: Color,
    background: Color,
    onBackground: Color,
    surface: Color,
    onSurface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    outline: Color,
    error: Color = Color(0xFFB00020),
    onError: Color = Color(0xFFFFFFFF),
    // A blend toward `surface` reads as a muddy grey when the accent and surface sit at
    // near-complementary hues (e.g. Magnus's blue surface with a yellow accent) -- RGB lerp
    // trends toward grey at the midpoint of complementary colors. Themes that hit this can pass
    // the container/on-container colors directly instead of relying on the computed blend.
    primaryContainer: Color? = null,
    onPrimaryContainer: Color? = null,
): ColorScheme {
    fun container(accent: Color) = lerp(surface, accent, 0.28f)
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer ?: container(primary),
        onPrimaryContainer = onPrimaryContainer ?: onSurface,
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = container(secondary),
        onSecondaryContainer = onSurface,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = container(tertiary),
        onTertiaryContainer = onSurface,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        error = error,
        onError = onError,
        errorContainer = container(error),
        onErrorContainer = onSurface,
        outline = outline,
        outlineVariant = lerp(surface, outline, 0.5f),
        scrim = Color(0xFF000000),
        surfaceBright = lerp(surface, onSurface, 0.05f),
        surfaceDim = lerp(surface, onSurface, 0.12f),
        surfaceContainerLowest = lerp(surface, onSurface, 0.02f),
        surfaceContainerLow = lerp(surface, onSurface, 0.04f),
        surfaceContainer = lerp(surface, onSurface, 0.08f),
        surfaceContainerHigh = lerp(surface, onSurface, 0.11f),
        surfaceContainerHighest = lerp(surface, onSurface, 0.14f),
    )
}

private val PinkScheme = holderColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFB0006B),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF4FA8),
    onTertiary = Color(0xFF3D0027),
    background = Color(0xFFFFE3F0),
    onBackground = Color(0xFF3D0027),
    surface = Color(0xFFFFE3F0),
    onSurface = Color(0xFF3D0027),
    surfaceVariant = Color(0xFFF6C6DE),
    onSurfaceVariant = Color(0xFF5C0A3A),
    outline = Color(0xFFA34472),
)

private val RoseScheme = holderColorScheme(
    primary = RosePrimary,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF9C6772),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFC98F92),
    onTertiary = Color(0xFF3A2426),
    background = Color(0xFFFBF1F0),
    onBackground = Color(0xFF4A3B3B),
    surface = Color(0xFFFBF1F0),
    onSurface = Color(0xFF4A3B3B),
    surfaceVariant = Color(0xFFEBD9D8),
    onSurfaceVariant = Color(0xFF5C4646),
    outline = Color(0xFFA98787),
)

private val RacingGreenScheme = holderColorScheme(
    primary = RacingGreenPrimary,
    onPrimary = Color(0xFFFFFDF5),
    secondary = Color(0xFF3C5E45),
    onSecondary = Color(0xFFFFFDF5),
    tertiary = Color(0xFFB08D3F),
    onTertiary = Color(0xFF2A2000),
    background = Color(0xFFF5F0DC),
    onBackground = Color(0xFF14231A),
    surface = Color(0xFFF5F0DC),
    onSurface = Color(0xFF14231A),
    surfaceVariant = Color(0xFFDED6BA),
    onSurfaceVariant = Color(0xFF20301F),
    outline = Color(0xFF5C6E52),
)

private val MagnusScheme = holderColorScheme(
    primary = MagnusYellow,
    onPrimary = Color(0xFF001E3C),
    secondary = MagnusBlue,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = MagnusYellow,
    onTertiary = Color(0xFF001E3C),
    background = MagnusBlue,
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF004C8C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF003D73),
    onSurfaceVariant = Color(0xFFCFE3F5),
    outline = MagnusYellow,
    primaryContainer = MagnusYellow,
    onPrimaryContainer = Color(0xFF001E3C),
)

private val AlbionScheme = holderColorScheme(
    primary = AlbionRed,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFFFFF),
    onSecondary = AlbionBlue,
    tertiary = AlbionBlue,
    onTertiary = Color(0xFFFFFFFF),
    background = AlbionBlue,
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF001B4D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF001333),
    onSurfaceVariant = Color(0xFFCBD6EA),
    outline = AlbionRed,
    primaryContainer = AlbionRed,
    onPrimaryContainer = Color(0xFFFFFFFF),
)

private val TerminalScheme = holderColorScheme(
    primary = TerminalPrimary,
    onPrimary = Color(0xFF001A00),
    secondary = Color(0xFF1FAF1F),
    onSecondary = Color(0xFF001A00),
    tertiary = Color(0xFF7CFF7C),
    onTertiary = Color(0xFF001A00),
    background = Color(0xFF0A0F0A),
    onBackground = TerminalPrimary,
    surface = Color(0xFF0F150F),
    onSurface = TerminalPrimary,
    surfaceVariant = Color(0xFF17201A),
    onSurfaceVariant = Color(0xFF7CFF7C),
    outline = Color(0xFF1FAF1F),
)

private val SuomiScheme = holderColorScheme(
    primary = SuomiPrimary,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4F82C4),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF002F6C),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF001C3D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF001C3D),
    surfaceVariant = Color(0xFFDCE8F5),
    onSurfaceVariant = Color(0xFF163752),
    outline = Color(0xFF6A9BC4),
)

private val PaperScheme = holderColorScheme(
    primary = PaperPrimary,
    onPrimary = Color(0xFFFAF6EC),
    secondary = Color(0xFF8A6D3B),
    onSecondary = Color(0xFFFAF6EC),
    tertiary = Color(0xFFA88A52),
    onTertiary = Color(0xFFFAF6EC),
    background = Color(0xFFFAF6EC),
    onBackground = Color(0xFF2B2926),
    surface = Color(0xFFF3EEDF),
    onSurface = Color(0xFF2B2926),
    surfaceVariant = Color(0xFFE6DFC9),
    onSurfaceVariant = Color(0xFF433F38),
    outline = Color(0xFF948C78),
)

private val BurgundyScheme = holderColorScheme(
    primary = BurgundyPrimary,
    onPrimary = Color(0xFFFFF8EF),
    secondary = Color(0xFF8A5A2A),
    onSecondary = Color(0xFFFFF8EF),
    tertiary = Color(0xFFB08D57),
    onTertiary = Color(0xFF2A1B00),
    background = Color(0xFFFBF3E7),
    onBackground = Color(0xFF2A0A10),
    surface = Color(0xFFFBF3E7),
    onSurface = Color(0xFF2A0A10),
    surfaceVariant = Color(0xFFE6D2BC),
    onSurfaceVariant = Color(0xFF3E1A20),
    outline = Color(0xFF9C6E52),
)

/** `background` is transparent on purpose: every screen's Scaffold defaults its container color
 * to `colorScheme.background`, so leaving it transparent lets the procedural wood-grain texture
 * drawn behind `content` in [HolderTheme] show through everywhere without touching every screen.
 * `surface` stays a solid, opaque color (matching the grain's own base tone) since things like
 * TopAppBar and DropdownMenu paint their own background and would otherwise be see-through too. */
private val WalnutScheme = holderColorScheme(
    primary = Color(0xFFC9A227),
    onPrimary = Color(0xFF2B1B0E),
    secondary = Color(0xFF6F4A2A),
    onSecondary = Color(0xFFF5E9D9),
    tertiary = Color(0xFF8C6239),
    onTertiary = Color(0xFF2B1B0E),
    background = Color.Transparent,
    onBackground = Color(0xFFF5E9D9),
    surface = WalnutBase,
    onSurface = Color(0xFFF5E9D9),
    surfaceVariant = Color(0xFF3B2414),
    onSurfaceVariant = Color(0xFFD8C3A5),
    outline = Color(0xFFC9A227),
)

private val MonochromeScheme = holderColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6E6E6E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF444444),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF2E2E2E),
    outline = Color(0xFF9E9E9E),
)

private val HighContrastLightScheme = holderColorScheme(
    primary = Color(0xFF0000EE),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF6A00CC),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF000000),
    outline = Color(0xFF000000),
)

private val HighContrastDarkScheme = holderColorScheme(
    primary = Color(0xFFFFD400),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF00E5FF),
    onTertiary = Color(0xFF000000),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF000000),
    onSurfaceVariant = Color(0xFFFFFFFF),
    outline = Color(0xFFFFFFFF),
)

/** Resolves a theme option to a concrete scheme. `darkTheme` only affects SYSTEM (handled by
 * the caller, since that path also needs dynamic color) and HIGH_CONTRAST -- every other option
 * is a fixed palette by design. */
fun HolderThemeOption.fixedColorScheme(darkTheme: Boolean): ColorScheme? = when (this) {
    HolderThemeOption.SYSTEM -> null
    HolderThemeOption.PINK -> PinkScheme
    HolderThemeOption.ROSE -> RoseScheme
    HolderThemeOption.RACING_GREEN -> RacingGreenScheme
    HolderThemeOption.MAGNUS -> MagnusScheme
    HolderThemeOption.ALBION -> AlbionScheme
    HolderThemeOption.TERMINAL -> TerminalScheme
    HolderThemeOption.SUOMI -> SuomiScheme
    HolderThemeOption.PAPER -> PaperScheme
    HolderThemeOption.BURGUNDY -> BurgundyScheme
    HolderThemeOption.WALNUT -> WalnutScheme
    HolderThemeOption.MONOCHROME -> MonochromeScheme
    HolderThemeOption.HIGH_CONTRAST -> if (darkTheme) HighContrastDarkScheme else HighContrastLightScheme
}
