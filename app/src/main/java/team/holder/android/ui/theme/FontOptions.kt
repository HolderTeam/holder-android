package team.holder.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import team.holder.android.R

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

// Real bundled font files (res/font/) for the "fantasy" options below -- none of these exist
// as Android system fonts, unlike Rounded/Cursive above. All are Google Fonts, OFL- or
// Apache-2.0-licensed; see THIRD_PARTY_FONTS.md at the repo root for source/attribution.
private val StencilFontFamily = FontFamily(Font(R.font.stencil))
private val WesternFontFamily = FontFamily(Font(R.font.western))
private val ArtDecoFontFamily = FontFamily(Font(R.font.art_deco))
private val GothicFontFamily = FontFamily(Font(R.font.gothic))
private val TypewriterFontFamily = FontFamily(Font(R.font.typewriter))
private val ComicFontFamily = FontFamily(Font(R.font.comic))
private val PixelFontFamily = FontFamily(Font(R.font.pixel))
private val SciFiFontFamily = FontFamily(Font(R.font.sci_fi))

/**
 * `fontFamily` is used for display/headline/title/label roles (app bar titles, buttons, section
 * headers); `bodyFontFamily` is used for body roles (paragraph text, list content) and defaults
 * to the same family. The louder display faces below (Stencil, Western, Art Deco, Gothic, Pixel,
 * Sci-Fi) override it back to Default instead -- several of these are genuinely hard to read as
 * paragraph text, so they're confined to titles/labels/buttons where they add character without
 * costing readability. Typewriter and Comic stay legible at body sizes, so they apply everywhere.
 */
enum class HolderFontFamilyOption(
    val label: String,
    val fontFamily: FontFamily,
    val bodyFontFamily: FontFamily = fontFamily,
) {
    DEFAULT("Default", FontFamily.Default),
    SERIF("Serif", FontFamily.Serif),
    MONOSPACE("Mono", FontFamily.Monospace),
    ROUNDED("Rounded", RoundedFontFamily),
    CURSIVE("Cursive", FontFamily.Cursive),
    STENCIL("Stencil", StencilFontFamily, bodyFontFamily = FontFamily.Default),
    WESTERN("Western", WesternFontFamily, bodyFontFamily = FontFamily.Default),
    ART_DECO("Art Deco", ArtDecoFontFamily, bodyFontFamily = FontFamily.Default),
    GOTHIC("Gothic", GothicFontFamily, bodyFontFamily = FontFamily.Default),
    TYPEWRITER("Typewriter", TypewriterFontFamily),
    COMIC("Comic", ComicFontFamily),
    PIXEL("Pixel", PixelFontFamily, bodyFontFamily = FontFamily.Default),
    SCI_FI("Sci-Fi", SciFiFontFamily, bodyFontFamily = FontFamily.Default),
}

/** Applies a [HolderFontFamilyOption] across every role in a [Typography], not just the one
 * ([Typography.bodyLarge]) this app happens to override -- every other role otherwise keeps
 * Material's own baseline fontFamily regardless of what's picked here. Display/headline/title/
 * label roles get [HolderFontFamilyOption.fontFamily]; body roles get
 * [HolderFontFamilyOption.bodyFontFamily] (see its doc comment for why they can differ). */
fun Typography.withFontFamilyOption(option: HolderFontFamilyOption): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = option.fontFamily),
    displayMedium = displayMedium.copy(fontFamily = option.fontFamily),
    displaySmall = displaySmall.copy(fontFamily = option.fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = option.fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = option.fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = option.fontFamily),
    titleLarge = titleLarge.copy(fontFamily = option.fontFamily),
    titleMedium = titleMedium.copy(fontFamily = option.fontFamily),
    titleSmall = titleSmall.copy(fontFamily = option.fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = option.bodyFontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = option.bodyFontFamily),
    bodySmall = bodySmall.copy(fontFamily = option.bodyFontFamily),
    labelLarge = labelLarge.copy(fontFamily = option.fontFamily),
    labelMedium = labelMedium.copy(fontFamily = option.fontFamily),
    labelSmall = labelSmall.copy(fontFamily = option.fontFamily),
)
