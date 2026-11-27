package dev.autobridge

import android.content.Context
import android.graphics.Typeface

/**
 * AutoBridge's type: on a GMC, the truck's own UI font (GMSUIGMCGlobal, read from its system fonts), so AutoBridge
 * matches the climate bar. Elsewhere Montserrat (SIL Open Font License; copyright and license are in the font file),
 * the closest free match. One variable file gives every weight.
 */
object Fonts {
    private val DIRS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts")
    const val LIGHT = 300; const val REGULAR = 400; const val MEDIUM = 500; const val BOLD = 700
    private val cache = HashMap<Int, Typeface>()

    @Synchronized fun of(c: Context, weight: Int = REGULAR): Typeface = cache.getOrPut(weight) {
        gm(weight)
            ?: runCatching { Typeface.Builder(c.assets, "fonts/Montserrat.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build() }.getOrNull()
            ?: Typeface.create(Typeface.DEFAULT, weight, false)  // the system font if neither can be read
    }

    /** GMC's own UI font, read from the truck's system fonts (never copied into the app). Null on other cars. */
    private fun gm(weight: Int): Typeface? {
        val files = gmFiles.ifEmpty { return null }
        // Weight from the file name, e.g. GMSUIGMCGlobal-Medium.ttf; a plain name counts as Regular.
        fun w(f: java.io.File) = when {
            "Thin" in f.name || "ExtraLight" in f.name -> 200; "Light" in f.name -> 300; "SemiBold" in f.name || "Semibold" in f.name -> 600
            "ExtraBold" in f.name || "Black" in f.name || "Heavy" in f.name -> 800; "Bold" in f.name -> 700; "Medium" in f.name -> 500; else -> 400
        }
        val best = files.filterNot { "Italic" in it.name }.minByOrNull { kotlin.math.abs(w(it) - weight) } ?: return null
        return runCatching { Typeface.Builder(best).setFontVariationSettings("'wght' $weight").setWeight(weight).build() }.getOrNull()  // a variable file takes the weight; a static one ignores it
    }

    private val gmFiles by lazy {
        DIRS.flatMap { d -> java.io.File(d).listFiles()?.toList().orEmpty() }.filter { it.name.startsWith("GMSUIGMCGlobal") && it.canRead() }
    }

    /** The GMC font files found, for the log. */
    fun gmFont() = gmFiles.joinToString(", ") { it.name }.ifEmpty { "not on this car (using Montserrat)" }

    /** Font files installed on the car, for the log: GM's own font may be among them. */
    fun installed(): String = DIRS
        .flatMap { java.io.File(it).list()?.toList().orEmpty() }
        .map { it.substringBefore('-').substringBefore('.').substringBefore('[') }
        .filterNot { it.startsWith("Noto") || it.startsWith("Roboto") }  // Android's own: hundreds of them
        .distinct().sorted().joinToString(", ").ifEmpty { "only Roboto and Noto" }
}
