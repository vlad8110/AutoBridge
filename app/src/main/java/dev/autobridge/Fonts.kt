package dev.autobridge

import android.content.Context
import android.graphics.Typeface

/**
 * AutoBridge's type: Montserrat (SIL Open Font License; copyright and license are in the font file),
 * the closest free match to GM's wide, round climate-bar font. One variable file gives every weight.
 */
object Fonts {
    const val LIGHT = 300; const val REGULAR = 400; const val MEDIUM = 500; const val BOLD = 700
    private val cache = HashMap<Int, Typeface>()

    @Synchronized fun of(c: Context, weight: Int = REGULAR): Typeface = cache.getOrPut(weight) {
        runCatching { Typeface.Builder(c.assets, "fonts/Montserrat.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build() }
            .getOrNull() ?: Typeface.create(Typeface.DEFAULT, weight, false)  // the system font if the file can't be read
    }

    /** Font files installed on the car, for the log: GM's own font may be among them. */
    fun installed(): String = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts")
        .flatMap { java.io.File(it).list()?.toList().orEmpty() }
        .map { it.substringBefore('-').substringBefore('.').substringBefore('[') }
        .filterNot { it.startsWith("Noto") || it.startsWith("Roboto") }  // Android's own: hundreds of them
        .distinct().sorted().joinToString(", ").ifEmpty { "only Roboto and Noto" }
}
