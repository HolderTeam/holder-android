package team.holder.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/** `scale` is applied as an outright replacement of the ambient (system) font scale, not a
 * multiplier on top of it -- SYSTEM is the only option that means "whatever the device says,"
 * so every other option needs to mean a specific, predictable size regardless of what that is. */
enum class HolderFontSizeOption(val label: String, val scale: Float?) {
    SYSTEM("System", null),
    SMALL("Small", 0.85f),
    DEFAULT("Default", 1.0f),
    LARGE("Large", 1.15f),
    EXTRA_LARGE("Extra Large", 1.3f),
    HUGE("Huge", 1.45f),
}

// Android's system fonts.xml registers "casual" as its own generic family (a rounded,
// friendly handwriting-adjacent face, e.g. Coming Soon) -- distinct from the "cursive" generic
// that FontFamily.Cursive requests, which resolves to an actual script/joined-letter face.
// DeviceFontFamilyName resolution falls back to the default typeface if "casual" isn't
// registered on a given device, so this never fails to render, just loses the styling.
private val RoundedFontFamily = FontFamily(Font(familyName = DeviceFontFamilyName("casual")))

enum class HolderFontFamilyOption(val label: String, val fontFamily: FontFamily) {
    DEFAULT("Default", FontFamily.Default),
    SERIF("Serif", FontFamily.Serif),
    MONOSPACE("Mono", FontFamily.Monospace),
    ROUNDED("Rounded", RoundedFontFamily),
    CURSIVE("Cursive", FontFamily.Cursive),
}

/** Applies a font family across every role in a [Typography], not just the one
 * ([Typography.bodyLarge]) this app happens to override -- every other role otherwise keeps
 * Material's own baseline fontFamily regardless of what's picked here. */
fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
