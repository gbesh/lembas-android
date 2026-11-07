package com.middleearth.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Deeds of the Red Book: the rare things you've seen, written down the first time (and
 * counted after). Kept on the phone only. The page shows today's saying, the next feast, then the
 * deeds: the ones you've seen with the day you first saw them, the rest as a hint.
 */
object Deeds {
    class Deed(val id: String, val name: String, val line: String, val hint: String)

    val ALL = listOf(
        Deed("fox", "A fox in the Shire", "Trotting over the meadow as if it owned the place.", "Watch the Shire's meadow"),
        Deed("owl", "An owl in Rivendell", "Blinking at you from the dark of the trees.", "Rivendell, late at night"),
        Deed("boat", "A boat on the lake", "A lantern crossing the water under the stars.", "The starlit lake"),
        Deed("meteor", "A falling star", "Gone before you could make a wish. Or were you quick?", "Keep an eye on the night sky"),
        Deed("rainbow", "A rainbow after rain", "The sun came out and the hills wore all their colours.", "Sunshine just after rain"),
        Deed("frost", "A frosty morning", "The Shire glittering before the sun was properly up.", "A cold, clear dawn"),
        Deed("beacons", "The beacons lit", "Fire on the hills: the weather came in hard.", "When the weather turns wild"),
        Deed("storm", "A storm over the hills", "Lightning far off, and the smial snug.", "Thunder about"),
        Deed("eclipse", "The moon in shadow", "A real eclipse: the moon turned the colour of embers.", "A night the Earth's shadow falls on the moon"),
        Deed("supermoon", "A great moon", "The full moon at its nearest, larger than it has any right to be.", "A full moon when it's closest"),
        Deed("fireworks", "Gandalf's fireworks", "Hobbit Day, and the sky full of green and gold.", "An evening in September"),
        Deed("durin", "Moon-letters", "Durin's Day: letters on the page that only the moon can show.", "The last new moon of autumn"),
        Deed("lanterns", "Lanterns for a holiday", "The Shire strung with lanterns for Yule or Lithe.", "A Shire holiday"),
        Deed("toast", "A toast to the Professor", "Nine o'clock on the third of January. Glasses up.", "A January evening"),
        Deed("turning", "The season turning", "One season blowing out and the next blowing in.", "The first day of a season"),
        Deed("eagle", "The Eagles are coming", "A ride or a delivery, and an eagle sweeping in ahead of it.", "Something on its way to you"),
        Deed("crebain", "Crebain on the wing", "A flurry of messages, and a flurry of wings.", "A busy moment"),
        Deed("mellon", "Speak, friend", "You said the word, and the doors knew you.", "An Elvish word at a door"),
        Deed("ring", "The inscription", "Fire-letters on the Ring. Best put it away again.", "Words best left unsaid"),
        Deed("smokering", "A smoke ring", "Focused, finished, and a smoke ring to show for it.", "A while of focus"),
        Deed("garden", "Sam's garden in bloom", "A quiet day on the phone, and the garden loved it.", "A gentle day"),
        Deed("refill", "Oil for the lamps", "Down to the last drop, and the lamps filled again.", "When the lamps burn low"),
    ) + listOf(
        Triple("bree", "Over the hedge and into Bree, with a pint at the end of it.", "217 km down the road"),
        Triple("weathertop", "Up on the old hill, with the wind for company.", "The hill east of Bree"),
        Triple("rivendell", "The Last Homely House, and a long rest earned.", "Over the Last Bridge and on"),
        Triple("moria", "At the doors in the mountain wall. You know the word.", "South along the mountains"),
        Triple("lorien", "Under the golden trees, where time runs slow.", "Beyond the mountains"),
        Triple("amonhen", "The Seat of Seeing, high above the river.", "Down the Great River"),
        Triple("blackgate", "The Black Gate, looming. Best go round.", "Across the marshes"),
        Triple("doom", "The whole road, walked on your own two feet.", "The end of the road"),
    ).map { (id, line, hint) -> Journey.PLACES.first { it.id == id }.let { Deed("walk_$id", it.name.replaceFirstChar { c -> c.uppercase() }, line, hint) } }

    private const val PREF = "deeds"
    @Volatile private var cache: JSONObject? = null
    private val today = HashSet<String>()
    private var todayKey = 0L

    private fun load(ctx: Context): JSONObject = cache ?: try {
        JSONObject(ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getString(PREF, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }.also { cache = it }

    /** Saw [id] (counted once a day; cheap to call every frame). */
    fun saw(ctx: Context, id: String) {
        val d = LocalDate.now().toEpochDay()
        if (d != todayKey) { todayKey = d; today.clear() }
        if (!today.add(id)) return
        val j = load(ctx)
        val e = j.optJSONObject(id) ?: JSONObject().put("first", d)
        if (e.optLong("last", -1) == d) return
        e.put("last", d).put("count", e.optInt("count", 0) + 1)
        j.put(id, e)
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, j.toString()).apply()
    }

    fun seen(ctx: Context): Int = load(ctx).length()

    /** The page: today's saying, the next feast, and the deeds. */
    fun show(activity: Activity, look: Look) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).roundToInt()
        val dark = !look.lightBars
        val ink = if (dark) 0xFFEFEDE7.toInt() else 0xFF1E1A14.toInt()
        val soft = if (dark) 0xB3F2EEE4.toInt() else 0x991E1A14.toInt()
        val faint = if (dark) 0x66F2EEE4 else 0x551E1A14
        val surface = if (dark) 0x1FFFFFFF else 0x14000000
        val sans: Typeface = Fonts.get(activity.assets, "AlegreyaSans-Medium.ttf", 500)
        val display: Typeface = Fonts.get(activity.assets, look.displayItalicFont, 400, true)
        val title: Typeface = Fonts.variable(activity.assets, "Fraunces.ttf", "'wght' 600, 'opsz' 72, 'SOFT' 50")
        val j = load(activity)
        val fmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

        val dialog = Dialog(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22f), px(10f), px(22f), px(28f)) }
        content.addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = 99f; setColor(soft) }
            layoutParams = LinearLayout.LayoutParams(px(36f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(16f) }
        })
        fun text(t: CharSequence, tf: Typeface, sp: Float, color: Int, top: Float = 0f) = TextView(activity).apply {
            text = t; typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); setPadding(0, px(top), 0, 0)
        }
        content.addView(text("The Red Book", title, 26f, ink))
        content.addView(text(Sayings.today(), display, 18f, ink, 10f).apply { setLineSpacing(0f, 1.15f) })
        content.addView(text(Feasts.countdown(), sans, 14f, look.accent, 10f))
        if (Journey.started(activity)) {
            content.addView(text("THE ROAD · ${Journey.line(activity).uppercase(Locale.getDefault())}", sans, 12f, look.accent).apply { letterSpacing = .1f; setPadding(px(4f), px(24f), 0, px(8f)) })
            content.addView(JourneyMap(activity, look, Journey.km(activity)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(220f)))
        }

        val seenCount = ALL.count { j.has(it.id) }
        content.addView(text("DEEDS · $seenCount OF ${ALL.size}", sans, 12f, look.accent, 26f).apply { letterSpacing = .12f; setPadding(px(4f), px(26f), 0, px(8f)) })
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(surface) }
        }
        // seen first (newest first), then the ones still to find
        val (seen, unseen) = ALL.partition { j.has(it.id) }
        val ordered = seen.sortedByDescending { j.optJSONObject(it.id)?.optLong("first") ?: 0 } + unseen
        ordered.forEachIndexed { i, d ->
            if (i > 0) group.addView(View(activity).apply {
                setBackgroundColor(if (dark) 0x14FFFFFF else 0x10000000)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = px(16f) }
            })
            val e = j.optJSONObject(d.id)
            group.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16f), px(12f), px(16f), px(12f))
                if (e != null) {
                    addView(text(d.name, display, 17f, ink))
                    addView(text(d.line, sans, 14f, soft, 2f))
                    val first = LocalDate.ofEpochDay(e.optLong("first")).format(fmt)
                    val n = e.optInt("count", 1)
                    addView(text("First seen $first" + if (n > 1) " · $n times" else "", sans, 12f, faint, 4f))
                    contentDescription = "${d.name}. ${d.line} First seen $first."
                } else {
                    addView(text("· · ·", display, 17f, faint))
                    addView(text(d.hint, sans, 14f, faint, 2f))
                    contentDescription = "Not yet seen. Hint: ${d.hint}"
                }
            })
        }
        content.addView(group)
        content.addView(text("Kept on this phone only.", sans, 12f, faint, 14f).apply { gravity = Gravity.CENTER_HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })

        val scroll = ScrollView(activity).apply { addView(content); isVerticalScrollBarEnabled = false }
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(look.drawerTop, look.drawerBottom)).apply {
                cornerRadii = floatArrayOf(28 * dp, 28 * dp, 28 * dp, 28 * dp, 0f, 0f, 0f, 0f)
            }
            addView(scroll)
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82f).roundToInt())
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
            val lp = attributes
            if (Blur.behind(activity, lp, px(30f))) { lp.dimAmount = 0.2f; attributes = lp }
        }
        dialog.show()
    }
}
