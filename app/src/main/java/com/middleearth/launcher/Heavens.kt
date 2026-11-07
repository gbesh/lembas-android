package com.middleearth.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real sky over you: the bright stars and the planets where they really are, and the Moon's
 * true height, phase and distance (so a supermoon is drawn larger and a lunar eclipse turns it red).
 * Low-precision formulas (Paul Schlyter's for the Moon, JPL's approximate elements for the
 * planets): good to a fraction of a degree, plenty for a picture. Lembas for Windows uses the same.
 */
object Heavens {
    private const val D2R = PI / 180
    private fun rev(x: Double) = x - floor(x / 360.0) * 360.0
    private fun sind(x: Double) = sin(x * D2R)
    private fun cosd(x: Double) = cos(x * D2R)

    /** Days since 2000 Jan 0.0 UT (Schlyter's day number). */
    fun day(ms: Long = System.currentTimeMillis()) = ms / 86_400_000.0 + 2440587.5 - 2451543.5

    /** Where something is in your sky: altitude and azimuth in degrees (azimuth from north, eastward). */
    class AltAz(val alt: Double, val az: Double)

    private fun obliquity(d: Double) = 23.4393 - 3.563e-7 * d

    /** Local sidereal time in degrees. */
    private fun lst(d: Double, lon: Double): Double {
        val ms = rev(356.0470 + 0.9856002585 * d); val ws = 282.9404 + 4.70935e-5 * d
        val utH = (d - floor(d)) * 24.0
        return rev(ms + ws + 180 + utH * 15.04107 + lon)
    }

    private fun altAz(raDeg: Double, decDeg: Double, d: Double, lat: Double, lon: Double): AltAz {
        val ha = rev(lst(d, lon) - raDeg)
        val x = cosd(ha) * cosd(decDeg); val y = sind(ha) * cosd(decDeg); val z = sind(decDeg)
        val xh = x * sind(lat) - z * cosd(lat); val zh = x * cosd(lat) + z * sind(lat)
        return AltAz(asin(zh.coerceIn(-1.0, 1.0)) / D2R, rev(atan2(y, xh) / D2R + 180))
    }

    private fun eclToEq(lon: Double, lat: Double, d: Double): Pair<Double, Double> {
        val e = obliquity(d)
        val x = cosd(lon) * cosd(lat); val y = sind(lon) * cosd(lat); val z = sind(lat)
        val ye = y * cosd(e) - z * sind(e); val ze = y * sind(e) + z * cosd(e)
        return rev(atan2(ye, x) / D2R) to (atan2(ze, hypot(x, ye)) / D2R)
    }

    // ------------------------------------------------------------------ the Sun and the Moon
    private fun sunLon(d: Double): Double {
        val w = 282.9404 + 4.70935e-5 * d; val e = 0.016709 - 1.151e-9 * d; val m = rev(356.0470 + 0.9856002585 * d)
        val ea = m + e / D2R * sind(m) * (1 + e * cosd(m))
        val xv = cosd(ea) - e; val yv = sqrt(1 - e * e) * sind(ea)
        return rev(atan2(yv, xv) / D2R + w)
    }

    /** The Moon tonight. [phase]: 0 new, .5 full; [km]: its distance; [eclipse]: 0 none, 1 deep in Earth's shadow. */
    class Moon(val pos: AltAz, val phase: Double, val km: Double, val eclipse: Double) {
        val full get() = abs(phase - .5) < .05
        val supermoon get() = full && km < 360_000
    }

    fun moon(lat: Double, lon: Double, ms: Long = System.currentTimeMillis()): Moon {
        val d = day(ms)
        val n = rev(125.1228 - 0.0529538083 * d); val i = 5.1454; val w = rev(318.0634 + 0.1643573223 * d)
        val a = 60.2666; val e = 0.054900; val mm = rev(115.3654 + 13.0649929509 * d)
        var ea = mm + e / D2R * sind(mm) * (1 + e * cosd(mm))
        repeat(3) { ea -= (ea - e / D2R * sind(ea) - mm) / (1 - e * cosd(ea)) }
        val xv = a * (cosd(ea) - e); val yv = a * sqrt(1 - e * e) * sind(ea)
        val v = atan2(yv, xv) / D2R; var r = hypot(xv, yv)
        val xh = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(i))
        val yh = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(i))
        val zh = r * sind(v + w) * sind(i)
        var lonEcl = rev(atan2(yh, xh) / D2R); var latEcl = atan2(zh, hypot(xh, yh)) / D2R
        // the main perturbations
        val ms0 = rev(356.0470 + 0.9856002585 * d); val ls = rev(ms0 + 282.9404 + 4.70935e-5 * d)
        val lm = rev(mm + w + n); val dd = rev(lm - ls); val f = rev(lm - n)
        lonEcl += -1.274 * sind(mm - 2 * dd) + 0.658 * sind(2 * dd) - 0.186 * sind(ms0) - 0.059 * sind(2 * mm - 2 * dd) -
            0.057 * sind(mm - 2 * dd + ms0) + 0.053 * sind(mm + 2 * dd) + 0.046 * sind(2 * dd - ms0) + 0.041 * sind(mm - ms0) -
            0.035 * sind(dd) - 0.031 * sind(mm + ms0) - 0.015 * sind(2 * f - 2 * dd) + 0.011 * sind(mm - 4 * dd)
        latEcl += -0.173 * sind(f - 2 * dd) - 0.055 * sind(mm - f - 2 * dd) - 0.046 * sind(mm + f - 2 * dd) + 0.033 * sind(f + 2 * dd) + 0.017 * sind(2 * mm + f)
        r += -0.58 * cosd(mm - 2 * dd) - 0.46 * cosd(2 * dd)
        val (ra, dec) = eclToEq(lonEcl, latEcl, d)
        val p = altAz(ra, dec, d, lat, lon)
        val alt = p.alt - 0.95 * cosd(p.alt)   // seen from the ground, not the Earth's centre
        val sl = sunLon(d)
        val phase = rev(lonEcl - sl) / 360.0
        // Earth's shadow points away from the Sun: how far the Moon is from its middle
        val dl = rev(lonEcl - (sl + 180) + 180) - 180
        val sep = hypot(dl * cosd(latEcl), latEcl)
        val eclipse = ((0.96 - sep) / 0.52).coerceIn(0.0, 1.0)
        return Moon(AltAz(alt, p.az), phase, r * 6371.0, eclipse)
    }

    /** Durin's Day: the last new moon of autumn (for your hemisphere), this year, or null if it's unknown. */
    fun durinsDay(year: Int, south: Boolean): java.time.LocalDate? {
        // new moons step 29.53 days from a known one (6 Jan 2000, 18:14 UT)
        val ref = 947182440000L; val syn = 29.530588853 * 86_400_000.0
        val winter = if (south) java.time.LocalDate.of(year, 6, 1) else java.time.LocalDate.of(year, 12, 1)
        val autumn = if (south) java.time.LocalDate.of(year, 3, 1) else java.time.LocalDate.of(year, 9, 1)
        val winterMs = winter.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val k = floor((winterMs - ref) / syn).toLong()
        val newMoon = java.time.Instant.ofEpochMilli((ref + k * syn).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (!newMoon.isBefore(autumn)) newMoon else null
    }

    // ------------------------------------------------------------------ the planets
    private class Orbit(val a: Double, val e: Double, val i: Double, val l: Double, val p: Double, val n: Double,
                        val da: Double, val de: Double, val di: Double, val dl: Double, val dp: Double, val dn: Double)
    private val EARTH = Orbit(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0, 0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)
    /** A planet you can see: its name, colour and how bright it looks. */
    class Planet(val name: String, val color: Int, val size: Float, val pos: AltAz)
    private val PLANETS = listOf(
        Triple("Mercury", 0xFFE8DCC8.toInt(), 1.6f) to Orbit(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593, 0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081),
        Triple("Venus", 0xFFFFF6E0.toInt(), 3.2f) to Orbit(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255, 0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
        Triple("Mars", 0xFFFFB08A.toInt(), 2.0f) to Orbit(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891, 0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
        Triple("Jupiter", 0xFFFFF0D8.toInt(), 2.8f) to Orbit(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909, -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
        Triple("Saturn", 0xFFF2E2B0.toInt(), 2.2f) to Orbit(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448, -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
    )

    private fun helio(o: Orbit, t: Double): DoubleArray {
        val a = o.a + o.da * t; val e = o.e + o.de * t; val i = o.i + o.di * t
        val l = o.l + o.dl * t; val p = o.p + o.dp * t; val n = o.n + o.dn * t
        val m = rev(l - p); val w = p - n
        var ea = m + e / D2R * sind(m)
        repeat(5) { ea -= (ea - e / D2R * sind(ea) - m) / (1 - e * cosd(ea)) }
        val xp = a * (cosd(ea) - e); val yp = a * sqrt(1 - e * e) * sind(ea)
        val x = (cosd(w) * cosd(n) - sind(w) * sind(n) * cosd(i)) * xp + (-sind(w) * cosd(n) - cosd(w) * sind(n) * cosd(i)) * yp
        val y = (cosd(w) * sind(n) + sind(w) * cosd(n) * cosd(i)) * xp + (-sind(w) * sind(n) + cosd(w) * cosd(n) * cosd(i)) * yp
        val z = (sind(w) * sind(i)) * xp + (cosd(w) * sind(i)) * yp
        return doubleArrayOf(x, y, z)
    }

    fun planets(lat: Double, lon: Double, ms: Long = System.currentTimeMillis()): List<Planet> {
        val d = day(ms)
        val t = (ms / 86_400_000.0 + 2440587.5 - 2451545.0) / 36525.0
        val earth = helio(EARTH, t)
        return PLANETS.map { (look, o) ->
            val h = helio(o, t)
            val x = h[0] - earth[0]; val y = h[1] - earth[1]; val z = h[2] - earth[2]
            val lonE = rev(atan2(y, x) / D2R); val latE = atan2(z, hypot(x, y)) / D2R
            val (ra, dec) = eclToEq(lonE, latE, d)
            Planet(look.first, look.second, look.third, altAz(ra, dec, d, lat, lon))
        }
    }

    // ------------------------------------------------------------------ the bright stars
    /** A named star: right ascension (hours), declination (degrees), magnitude, colour. */
    class Star(val name: String, val ra: Double, val dec: Double, val mag: Double, val color: Int = 0xFFF4F2EE.toInt())
    private const val WARM = 0xFFFFD2A6.toInt()
    private const val RED = 0xFFFFB090.toInt()
    private const val BLUE = 0xFFD6E2FF.toInt()
    val STARS = listOf(
        // the Southern Cross and its Pointers
        Star("Acrux", 12.443, -63.10, 0.8, BLUE), Star("Mimosa", 12.795, -59.69, 1.3, BLUE), Star("Gacrux", 12.519, -57.11, 1.6, RED),
        Star("Imai", 12.252, -58.75, 2.8), Star("Rigil Kentaurus", 14.660, -60.83, -0.3, WARM), Star("Hadar", 14.064, -60.37, 0.6, BLUE),
        // Orion
        Star("Betelgeuse", 5.919, 7.41, 0.5, RED), Star("Rigel", 5.242, -8.20, 0.1, BLUE), Star("Bellatrix", 5.419, 6.35, 1.6, BLUE),
        Star("Alnitak", 5.679, -1.94, 1.8, BLUE), Star("Alnilam", 5.604, -1.20, 1.7, BLUE), Star("Mintaka", 5.533, -0.30, 2.2, BLUE),
        Star("Saiph", 5.796, -9.67, 2.1, BLUE),
        // the Plough (the Sickle of the Valar)
        Star("Dubhe", 11.062, 61.75, 1.8, WARM), Star("Merak", 11.031, 56.38, 2.4), Star("Phecda", 11.897, 53.69, 2.4), Star("Megrez", 12.257, 57.03, 3.3),
        Star("Alioth", 12.900, 55.96, 1.8), Star("Mizar", 13.399, 54.93, 2.2), Star("Alkaid", 13.792, 49.31, 1.9, BLUE),
        // Cassiopeia and the pole
        Star("Schedar", 0.675, 56.54, 2.2, WARM), Star("Caph", 0.153, 59.15, 2.3), Star("Navi", 0.945, 60.72, 2.2, BLUE),
        Star("Ruchbah", 1.430, 60.24, 2.7), Star("Segin", 1.907, 63.67, 3.4, BLUE), Star("Polaris", 2.530, 89.26, 2.0),
        // Scorpius
        Star("Antares", 16.490, -26.43, 1.0, RED), Star("Shaula", 17.560, -37.10, 1.6, BLUE), Star("Sargas", 17.622, -43.00, 1.9),
        Star("Dschubba", 16.006, -22.62, 2.3, BLUE), Star("Acrab", 16.090, -19.81, 2.6, BLUE),
        // the brightest of the rest
        Star("Sirius", 6.752, -16.72, -1.5, BLUE), Star("Canopus", 6.399, -52.70, -0.7), Star("Arcturus", 14.261, 19.18, -0.1, WARM),
        Star("Vega", 18.616, 38.78, 0.0, BLUE), Star("Capella", 5.278, 46.00, 0.1, WARM), Star("Procyon", 7.655, 5.22, 0.4),
        Star("Achernar", 1.629, -57.24, 0.5, BLUE), Star("Altair", 19.846, 8.87, 0.8), Star("Aldebaran", 4.599, 16.51, 0.9, RED),
        Star("Spica", 13.420, -11.16, 1.0, BLUE), Star("Pollux", 7.755, 28.03, 1.1, WARM), Star("Fomalhaut", 22.961, -29.62, 1.2),
        Star("Deneb", 20.690, 45.28, 1.3), Star("Regulus", 10.139, 11.97, 1.4, BLUE), Star("Castor", 7.577, 31.89, 1.6),
        Star("Alcyone", 3.791, 24.11, 2.9, BLUE), Star("Atria", 16.811, -69.03, 1.9, WARM), Star("Peacock", 20.427, -56.74, 1.9, BLUE),
        Star("Miaplacidus", 9.220, -69.72, 1.7), Star("Avior", 8.375, -59.51, 1.9, WARM), Star("Alnair", 22.137, -46.96, 1.7, BLUE),
    )

    fun stars(lat: Double, lon: Double, ms: Long = System.currentTimeMillis()): List<Pair<Star, AltAz>> {
        val d = day(ms)
        return STARS.map { it to altAz(it.ra * 15, it.dec, d, lat, lon) }
    }
}
