package com.middleearth.launcher

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

/**
 * What Lembas does with your steps (Health Connect shows this from its permissions screen):
 * they're read on the phone to move you along the road to Mount Doom, kept on the phone, and only
 * the distance walked goes to your watch and, if you turned sync on, your PC.
 */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.2f)
            text = "Lembas and your steps\n\n" +
                "Lembas reads your step count from Health Connect to carry you along the road from Bag End to Mount Doom.\n\n" +
                "Your steps are read and kept on this phone. Lembas never sends them anywhere. Only the distance you've walked " +
                "(and the place you've reached) goes to your watch, and to your PC if you turned on sync.\n\n" +
                "Lembas reads nothing else from Health Connect, and writes nothing to it. You can take the permission away at any " +
                "time in Health Connect, or stop the walk in Lembas's settings."
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
