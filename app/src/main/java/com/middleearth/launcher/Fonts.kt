package com.middleearth.launcher

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Loads the bundled fonts from assets/fonts. The GitHub build downloads them
 * (all SIL Open Font License). If a file is missing the system serif is used,
 * so the launcher still works without them.
 */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun get(assets: AssetManager, file: String, weight: Int = 400, italic: Boolean = false): Typeface {
        val key = "$file#$weight#$italic"
        cache[key]?.let { return it }
        val fallback = Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        val tf = try {
            val exists = assets.list("fonts")?.contains(file) == true
            if (!exists) fallback else {
                val b = Typeface.Builder(assets, "fonts/$file").setFallback("serif")
                // Cinzel and Cormorant ship as variable fonts; pick the weight we want.
                if (file.startsWith("Cinzel") || file.startsWith("Cormorant") || file.startsWith("Fraunces")) {
                    b.setFontVariationSettings("'wght' $weight")
                }
                b.build() ?: fallback
            }
        } catch (t: Throwable) {
            fallback
        }
        cache[key] = tf
        return tf
    }
}
