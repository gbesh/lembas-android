package com.middleearth.launcher

import java.time.LocalDate
import java.time.LocalTime

/**
 * Words for the day: a hobbit saying (written fresh for Lembas, in the spirit of Shire proverbs;
 * a new one each day, the same on every device), and a short Sindarin greeting for the time of
 * day, with the English beneath.
 */
object Sayings {
    private val SAYINGS = listOf(
        "A full larder makes a short winter.",
        "The kettle doesn't boil any faster for being watched, but it does boil.",
        "Mind the weather, but don't let it mind you.",
        "Better a muddy boot than a missed sunrise.",
        "A small garden well kept feeds more than a big one forgotten.",
        "Second breakfast is never the mistake.",
        "Never trust a path that's in a hurry.",
        "The longest road starts at your own front door, and so does the shortest.",
        "A good pipe and a good friend: you can do without the pipe.",
        "Rain on the roof is the best song for sleeping.",
        "Mend the fence before the goats find the gap.",
        "An apple a day, and a pie on Sundays.",
        "The quiet ones see the most of the stars.",
        "If the sun is shining, the washing can wait. If it's raining, so can the walk.",
        "Kind words cost nothing and keep for years.",
        "Plant on a cloudy day and water on a sunny one.",
        "A hobbit with a map still asks the way.",
        "Leave the gate as you found it.",
        "What's cooked slowly is eaten gladly.",
        "Every hill looks bigger before lunch.",
        "Busy hands, easy heart.",
        "There's no such thing as too many handkerchiefs.",
        "A borrowed book comes back with a story of its own.",
        "Fair words at the door, fair welcome at the table.",
        "The mushroom you leave is the one you dream about.",
        "Don't count the eggs; count the hens.",
        "A long letter is a short visit.",
        "The best seat is the one by the fire, but share it.",
        "When the river is high, admire it from the bridge.",
        "Little and often fills the pantry.",
        "A song on the road lightens the pack.",
        "The night is long, but the morning is longer.",
        "Old friends and old boots: hardest to replace, easiest to wear.",
        "A tidy hole is a happy hole.",
        "You can't hurry blackberries.",
        "Light the lamp before you need it.",
        "A good neighbour is better than a far-off cousin.",
        "If it's worth a walk, it's worth a picnic.",
        "Worry is a poor cook: it burns everything.",
        "The garden forgives, but it doesn't forget.",
        "Count your blessings at supper; there are usually more than spoons.",
        "A smile at the market buys more than a silver penny.",
        "Never argue with a cook or a gardener.",
        "Put the kettle on first; decide afterwards.",
        "Even a short rest is a rest.",
        "The wise traveller packs one more pocket-handkerchief than they need.",
        "Many a feast began as a crust shared.",
        "A candle in the window brings travellers home.",
        "Talk less at the gate, and you'll be in by teatime.",
        "Some days are for doing, and some are for sitting in the sun.",
        "The tallest tree was once a nut that held its ground.",
        "Better to ask twice than to get lost once.",
        "A merry heart goes all the way.",
        "Leave a little in the pot for whoever comes late.",
        "Good bread needs time, and so do good friends.",
        "It's a poor day that can't be mended by a walk.",
        "A clean pipe draws sweet.",
        "Don't look for trouble; it has its own map.",
        "Sweep your own step before you mind your neighbour's.",
        "Strawberries and gossip are best fresh.",
        "The road goes on, but you needn't go with it every day.",
        "A full belly and a fair wind: what more is there?",
        "Bake for a week and the week bakes for you.",
        "Sit by the river long enough and everything floats past.",
        "Hope is a good breakfast and a poor supper.",
        "If the cat's by the fire, the frost is coming.",
        "One good turn makes the next easier.",
        "Plenty of blankets, plenty of dreams.",
        "Weeds come uninvited; flowers need asking.",
        "A hobbit's word is as good as their pantry.",
        "Mushrooms and secrets grow best in the dark.",
        "A room without books is a hole without windows.",
        "The hill that's hard to climb is the one with the view.",
        "Never miss a chance to sit down.",
        "Shared bread is twice as filling.",
        "Thank the cook and you'll never go hungry.",
        "A wet spring makes a green summer.",
        "Don't rake the leaves while the tree's still dropping them.",
        "Be home before the lamps are lit, or bring a lantern.",
        "Every pantry has room for one more jar.",
        "A cheerful fire needs feeding, and so do cheerful folk.",
        "The fastest way across the field is around the bull.",
        "Early to bed, early to breakfast.",
        "Take the long way home if the sun is out.",
        "Good company shortens any road.",
        "Write it down, or it's only a rumour.",
        "A pinch of salt and a pinch of patience.",
        "Store your apples apart and your quarrels too.",
        "Kindness is the best seasoning.",
        "What the frost takes, the spring gives back.",
        "Keep a spare key and a spare cake.",
        "The oven knows when you're impatient.",
        "Sing to your bees and they'll sing back.",
        "Some adventures are best enjoyed from an armchair.",
        "A dry pipe and a dry pair of socks are worth a fortune on the road.",
        "Never say no to a picnic until you've seen the basket.",
        "Even the Brandywine was a trickle once.",
        "A gift given gladly comes home doubled.",
        "Look up now and then; the sky does change.",
        "The day is long enough if you start it with porridge.",
        "Close the round door gently; it's been good to you.",
    )

    /** Today's saying (the same all day, a different one tomorrow). */
    fun today(d: LocalDate = LocalDate.now()): String {
        val n = SAYINGS.size
        val i = ((d.toEpochDay() * 37 + 11) % n).toInt().let { if (it < 0) it + n else it }
        return SAYINGS[i]
    }

    /** A Sindarin greeting for the hour, and its English. */
    fun greeting(t: LocalTime = LocalTime.now()): Pair<String, String> = when (t.hour) {
        in 4..6 -> "Le suilon" to "I greet you"
        in 7..10 -> "Aur vaer" to "good morning"
        in 11..13 -> "Suilad" to "greetings"
        in 14..17 -> "Galu" to "good fortune"
        in 18..20 -> "Mae govannen" to "well met"
        in 21..23 -> "Losto mae" to "sleep well"
        else -> "Novaer" to "be well"
    }
}
