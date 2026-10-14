package com.paulrj.cumulusia.ai

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🔭 EL CIELO CALCULADO EN EL TELÉFONO (sin internet y para tu ubicación exacta)
 *  🌙 Fase de la Luna, % iluminada, a qué hora sale y se pone, y las próximas fases (nueva, cuartos y llena).
 *  🌅 Puesta y salida del Sol y la «noche cerrada» (cuando ya no queda luz del Sol: lo mejor para observar).
 *  🪐 Planetas visibles esta noche: desde qué hora hasta qué hora, hacia dónde mirar, qué tan alto y su brillo.
 *  🤝 Conjunciones (la Luna o dos planetas muy juntos) de los próximos 30 días.
 *  ☄️ Lluvias de estrellas: fecha del máximo, cuántas por hora, mejores horas y si la Luna estorba.
 *  🌑 Eclipses de Sol y de Luna (lista oficial de la NASA) y si se ven desde donde estás.
 *  🔭 Recomendaciones de observación y astrofotografía según lo que hay en el cielo.
 * Cálculos con los elementos orbitales de Paul Schlyter (precisión de 1-2 minutos de arco: de sobra para observar).
 */
object SkyCalc {

    // =====================================================================
    // 📐 MATEMÁTICA BÁSICA
    // =====================================================================
    private const val RAD = PI / 180.0
    private fun sind(x: Double) = sin(x * RAD)
    private fun cosd(x: Double) = cos(x * RAD)
    private fun atan2d(y: Double, x: Double) = atan2(y, x) / RAD
    private fun asind(x: Double) = asin(x.coerceIn(-1.0, 1.0)) / RAD
    private fun acosd(x: Double) = acos(x.coerceIn(-1.0, 1.0)) / RAD
    private fun rev(x: Double) = ((x % 360.0) + 360.0) % 360.0

    /** Días desde el 31-dic-1999 0h UT (el «d» de Schlyter). */
    fun dayNumber(ms: Long): Double = ms / 86_400_000.0 + 2440587.5 - 2451543.5

    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L

    /** Posición: ascensión recta y declinación (°), longitud y latitud eclípticas (°), distancia a la Tierra, distancia al Sol. */
    class Pos(val ra: Double, val dec: Double, val lon: Double, val lat: Double, val dist: Double, val helioR: Double)

    private fun kepler(mDeg: Double, e: Double): Double {
        var ee = mDeg + e / RAD * sind(mDeg) * (1.0 + e * cosd(mDeg))
        repeat(8) {
            val next = ee - (ee - e / RAD * sind(ee) - mDeg) / (1.0 - e * cosd(ee))
            if (abs(next - ee) < 1e-5) return next
            ee = next
        }
        return ee
    }

    private fun obliquity(d: Double) = 23.4393 - 3.563E-7 * d

    private fun eclToEqu(lon: Double, lat: Double, d: Double): Pair<Double, Double> {
        val ecl = obliquity(d)
        val x = cosd(lon) * cosd(lat); val y = sind(lon) * cosd(lat); val z = sind(lat)
        val ye = y * cosd(ecl) - z * sind(ecl); val ze = y * sind(ecl) + z * cosd(ecl)
        return rev(atan2d(ye, x)) to atan2d(ze, sqrt(x * x + ye * ye))
    }

    // =====================================================================
    // ☀️ SOL, 🌙 LUNA Y 🪐 PLANETAS
    // =====================================================================
    private class SunRaw(val lon: Double, val r: Double, val m: Double, val w: Double)

    private fun sunRaw(d: Double): SunRaw {
        val w = 282.9404 + 4.70935E-5 * d
        val e = 0.016709 - 1.151E-9 * d
        val m = rev(356.0470 + 0.9856002585 * d)
        val ee = kepler(m, e)
        val xv = cosd(ee) - e
        val yv = sqrt(1.0 - e * e) * sind(ee)
        return SunRaw(rev(atan2d(yv, xv) + w), sqrt(xv * xv + yv * yv), m, w)
    }

    fun sun(d: Double): Pos {
        val s = sunRaw(d)
        val (ra, dec) = eclToEqu(s.lon, 0.0, d)
        return Pos(ra, dec, s.lon, 0.0, s.r, 0.0)
    }

    fun moon(d: Double): Pos {
        val n = 125.1228 - 0.0529538083 * d
        val i = 5.1454
        val w = 318.0634 + 0.1643573223 * d
        val a = 60.2666
        val e = 0.054900
        val m = rev(115.3654 + 13.0649929509 * d)
        val ee = kepler(m, e)
        val xv = a * (cosd(ee) - e); val yv = a * sqrt(1.0 - e * e) * sind(ee)
        val v = atan2d(yv, xv); var r = sqrt(xv * xv + yv * yv)
        val xh = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(i))
        val yh = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(i))
        val zh = r * (sind(v + w) * sind(i))
        var lon = atan2d(yh, xh); var lat = atan2d(zh, sqrt(xh * xh + yh * yh))
        val s = sunRaw(d)
        val ms = s.m; val mm = m
        val ls = s.m + s.w; val lm = m + w + n
        val dd = lm - ls; val f = lm - n
        lon += -1.274 * sind(mm - 2 * dd) + 0.658 * sind(2 * dd) - 0.186 * sind(ms) - 0.059 * sind(2 * mm - 2 * dd) -
            0.057 * sind(mm - 2 * dd + ms) + 0.053 * sind(mm + 2 * dd) + 0.046 * sind(2 * dd - ms) + 0.041 * sind(mm - ms) -
            0.035 * sind(dd) - 0.031 * sind(mm + ms) - 0.015 * sind(2 * f - 2 * dd) + 0.011 * sind(mm - 4 * dd)
        lat += -0.173 * sind(f - 2 * dd) - 0.055 * sind(mm - f - 2 * dd) - 0.046 * sind(mm + f - 2 * dd) +
            0.033 * sind(f + 2 * dd) + 0.017 * sind(2 * mm + f)
        r += -0.58 * cosd(mm - 2 * dd) - 0.46 * cosd(2 * dd)
        lon = rev(lon)
        val (ra, dec) = eclToEqu(lon, lat, d)
        return Pos(ra, dec, lon, lat, r, 0.0)
    }

    enum class Planet(val es: String, val emoji: String, val tip: String) {
        MERCURY("Mercurio", "☿️", "a simple vista, muy bajo en el horizonte (binoculares ayudan; nunca apuntes hacia el Sol)"),
        VENUS("Venus", "✨", "a simple vista (el más brillante); con telescopio se ven sus fases"),
        MARS("Marte", "🔴", "a simple vista (rojizo); con telescopio, su disco y los casquetes polares"),
        JUPITER("Júpiter", "🪐", "a simple vista; con binoculares, sus 4 lunas grandes; con telescopio, sus bandas"),
        SATURN("Saturno", "🪐", "a simple vista; con un telescopio pequeño (30 aumentos o más), sus anillos"),
        URANUS("Urano", "🔵", "con binoculares desde un lugar oscuro (se ve como una estrella verdosa)"),
        NEPTUNE("Neptuno", "🔵", "solo con telescopio")
    }

    /** N, i, w, a, e, M (Schlyter) */
    private fun elements(p: Planet, d: Double): DoubleArray = when (p) {
        Planet.MERCURY -> doubleArrayOf(48.3313 + 3.24587E-5 * d, 7.0047 + 5.00E-8 * d, 29.1241 + 1.01444E-5 * d, 0.387098, 0.205635 + 5.59E-10 * d, 168.6562 + 4.0923344368 * d)
        Planet.VENUS -> doubleArrayOf(76.6799 + 2.46590E-5 * d, 3.3946 + 2.75E-8 * d, 54.8910 + 1.38374E-5 * d, 0.723330, 0.006773 - 1.302E-9 * d, 48.0052 + 1.6021302244 * d)
        Planet.MARS -> doubleArrayOf(49.5574 + 2.11081E-5 * d, 1.8497 - 1.78E-8 * d, 286.5016 + 2.92961E-5 * d, 1.523688, 0.093405 + 2.516E-9 * d, 18.6021 + 0.5240207766 * d)
        Planet.JUPITER -> doubleArrayOf(100.4542 + 2.76854E-5 * d, 1.3030 - 1.557E-7 * d, 273.8777 + 1.64505E-5 * d, 5.20256, 0.048498 + 4.469E-9 * d, 19.8950 + 0.0830853001 * d)
        Planet.SATURN -> doubleArrayOf(113.6634 + 2.38980E-5 * d, 2.4886 - 1.081E-7 * d, 339.3939 + 2.97661E-5 * d, 9.55475, 0.055546 - 9.499E-9 * d, 316.9670 + 0.0334442282 * d)
        Planet.URANUS -> doubleArrayOf(74.0005 + 1.3978E-5 * d, 0.7733 + 1.9E-8 * d, 96.6612 + 3.0565E-5 * d, 19.18171 - 1.55E-8 * d, 0.047318 + 7.45E-9 * d, 142.5905 + 0.011725806 * d)
        Planet.NEPTUNE -> doubleArrayOf(131.7806 + 3.0173E-5 * d, 1.7700 - 2.55E-7 * d, 272.8461 - 6.027E-6 * d, 30.05826 + 3.313E-8 * d, 0.008606 + 2.15E-9 * d, 260.2471 + 0.005995147 * d)
    }

    /** Posición heliocéntrica eclíptica (lon, lat, r) con las perturbaciones grandes de Júpiter, Saturno y Urano. */
    private fun helio(p: Planet, d: Double): DoubleArray {
        val el = elements(p, d)
        val n = el[0]; val i = el[1]; val w = el[2]; val a = el[3]; val e = el[4]; val m = rev(el[5])
        val ee = kepler(m, e)
        val xv = a * (cosd(ee) - e); val yv = a * sqrt(1.0 - e * e) * sind(ee)
        val v = atan2d(yv, xv); val r = sqrt(xv * xv + yv * yv)
        val xh = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(i))
        val yh = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(i))
        val zh = r * (sind(v + w) * sind(i))
        var lon = atan2d(yh, xh); var lat = atan2d(zh, sqrt(xh * xh + yh * yh))
        val mj = rev(19.8950 + 0.0830853001 * d); val msa = rev(316.9670 + 0.0334442282 * d); val mu = rev(142.5905 + 0.011725806 * d)
        when (p) {
            Planet.JUPITER -> lon += -0.332 * sind(2 * mj - 5 * msa - 67.6) - 0.056 * sind(2 * mj - 2 * msa + 21) + 0.042 * sind(3 * mj - 5 * msa + 21) -
                0.036 * sind(mj - 2 * msa) + 0.022 * cosd(mj - msa) + 0.023 * sind(2 * mj - 3 * msa + 52) - 0.016 * sind(mj - 5 * msa - 69)
            Planet.SATURN -> {
                lon += 0.812 * sind(2 * mj - 5 * msa - 67.6) - 0.229 * cosd(2 * mj - 4 * msa - 2) + 0.119 * sind(mj - 2 * msa - 3) +
                    0.046 * sind(2 * mj - 6 * msa - 69) + 0.014 * sind(mj - 3 * msa + 32)
                lat += -0.020 * cosd(2 * mj - 4 * msa - 2) + 0.018 * sind(2 * mj - 6 * msa - 49)
            }
            Planet.URANUS -> lon += 0.040 * sind(msa - 2 * mu + 6) + 0.035 * sind(msa - 3 * mu + 33) - 0.015 * sind(mj - mu + 20)
            else -> {}
        }
        return doubleArrayOf(lon, lat, r)
    }

    fun planet(p: Planet, d: Double): Pos {
        val h = helio(p, d)
        val xh = h[2] * cosd(h[0]) * cosd(h[1]); val yh = h[2] * sind(h[0]) * cosd(h[1]); val zh = h[2] * sind(h[1])
        val s = sunRaw(d)
        val xg = xh + s.r * cosd(s.lon); val yg = yh + s.r * sind(s.lon); val zg = zh
        val dist = sqrt(xg * xg + yg * yg + zg * zg)
        val lon = rev(atan2d(yg, xg)); val lat = atan2d(zg, sqrt(xg * xg + yg * yg))
        val (ra, dec) = eclToEqu(lon, lat, d)
        return Pos(ra, dec, lon, lat, dist, h[2])
    }

    /** Brillo (magnitud): mientras más negativo, más brillante. A simple vista se ve hasta ~5,5. */
    fun magnitude(p: Planet, d: Double): Double {
        val pos = planet(p, d)
        val r = pos.helioR; val big = pos.dist; val s = sunRaw(d).r
        val fv = acosd((r * r + big * big - s * s) / (2 * r * big))
        val base = 5 * log10(r * big)
        return when (p) {
            Planet.MERCURY -> -0.36 + base + 0.027 * fv + 2.2E-13 * Math.pow(fv, 6.0)
            Planet.VENUS -> -4.34 + base + 0.013 * fv + 4.2E-7 * fv * fv * fv
            Planet.MARS -> -1.51 + base + 0.016 * fv
            Planet.JUPITER -> -9.25 + base + 0.014 * fv
            Planet.SATURN -> -9.0 + base + 0.044 * fv
            Planet.URANUS -> -7.15 + base + 0.001 * fv
            Planet.NEPTUNE -> -6.90 + base + 0.001 * fv
        }
    }

    /** Distancia angular entre dos posiciones (°). */
    fun separation(a: Pos, b: Pos): Double =
        acosd(sind(a.dec) * sind(b.dec) + cosd(a.dec) * cosd(b.dec) * cosd(a.ra - b.ra))

    // =====================================================================
    // 🧭 ALTURA Y DIRECCIÓN DESDE TU UBICACIÓN
    // =====================================================================
    class AltAz(val alt: Double, val az: Double)

    fun altAz(p: Pos, lat: Double, lon: Double, ms: Long, topoMoon: Boolean = false): AltAz {
        val jd = ms / 86_400_000.0 + 2440587.5
        val gmst = rev(280.46061837 + 360.98564736629 * (jd - 2451545.0))
        val h = rev(gmst + lon - p.ra)
        val east = -cosd(p.dec) * sind(h)
        val north = sind(p.dec) * cosd(lat) - cosd(p.dec) * cosd(h) * sind(lat)
        val up = sind(p.dec) * sind(lat) + cosd(p.dec) * cosd(h) * cosd(lat)
        var alt = asind(up)
        if (topoMoon) alt -= asind(1.0 / p.dist) * cosd(alt)   // paralaje de la Luna (~1°)
        return AltAz(alt, rev(atan2d(east, north)))
    }

    private val DIRS = listOf("el norte", "el noreste", "el este", "el sureste", "el sur", "el suroeste", "el oeste", "el noroeste")
    private val DIRS_SHORT = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    fun direction(az: Double): String = DIRS[((rev(az) + 22.5) / 45).toInt() % 8]
    fun directionShort(az: Double): String = DIRS_SHORT[((rev(az) + 22.5) / 45).toInt() % 8]

    private fun sunAlt(lat: Double, lon: Double, ms: Long) = altAz(sun(dayNumber(ms)), lat, lon, ms).alt
    private fun moonAlt(lat: Double, lon: Double, ms: Long) = altAz(moon(dayNumber(ms)), lat, lon, ms, topoMoon = true).alt

    /** Primer cruce de la altura [h0] entre [from] y [to] (subiendo o bajando), con precisión de 1 minuto. */
    private fun crossing(from: Long, to: Long, h0: Double, rising: Boolean, alt: (Long) -> Double): Long? {
        val step = 10 * MIN
        var t = from
        var prev = alt(t) - h0
        while (t < to) {
            val t2 = t + step
            val cur = alt(t2) - h0
            if ((rising && prev < 0 && cur >= 0) || (!rising && prev >= 0 && cur < 0)) {
                var lo = t; var hi = t2
                while (hi - lo > MIN) {
                    val mid = (lo + hi) / 2
                    val v = alt(mid) - h0
                    if ((rising && v < 0) || (!rising && v >= 0)) lo = mid else hi = mid
                }
                return hi
            }
            prev = cur; t = t2
        }
        return null
    }

    // =====================================================================
    // 🌙 LUNA: FASE, ILUMINACIÓN, SALIDA/PUESTA Y PRÓXIMAS FASES
    // =====================================================================
    /** Ángulo Luna-Sol en longitud (0 = nueva, 90 = cuarto creciente, 180 = llena, 270 = cuarto menguante). */
    fun moonAngle(ms: Long): Double { val d = dayNumber(ms); return rev(moon(d).lon - sun(d).lon) }

    fun illumination(ms: Long): Double {
        val d = dayNumber(ms); val m = moon(d); val s = sun(d)
        val elong = acosd(cosd(m.lon - s.lon) * cosd(m.lat))
        return (1 - cosd(elong)) / 2
    }

    fun phaseName(angle: Double): Pair<String, String> = when {
        angle < 6 || angle >= 354 -> "Luna nueva" to "🌑"
        angle < 84 -> "Luna creciente" to "🌒"
        angle < 96 -> "Cuarto creciente" to "🌓"
        angle < 174 -> "Gibosa creciente" to "🌔"
        angle < 186 -> "Luna llena" to "🌕"
        angle < 264 -> "Gibosa menguante" to "🌖"
        angle < 276 -> "Cuarto menguante" to "🌗"
        else -> "Luna menguante" to "🌘"
    }

    /** Momento en que el ángulo Luna-Sol llega a [target] (0, 90, 180, 270) después de [from]. */
    fun nextPhase(from: Long, target: Double): Long {
        fun f(t: Long) = rev(moonAngle(t) - target + 180) - 180
        var t = from
        var prev = f(t)
        val step = 6 * HOUR
        repeat(4 * 32) {
            val t2 = t + step
            val cur = f(t2)
            if (prev < 0 && cur >= 0 && cur - prev < 90) {
                var lo = t; var hi = t2
                while (hi - lo > MIN) { val mid = (lo + hi) / 2; if (f(mid) < 0) lo = mid else hi = mid }
                return hi
            }
            prev = cur; t = t2
        }
        return t
    }

    class MoonInfo(val name: String, val emoji: String, val illum: Int, val waxing: Boolean, val angle: Double,
                   val rise: Long?, val set: Long?, val next: List<Triple<String, String, Long>>)

    fun moonInfo(lat: Double, lon: Double, now: Long): MoonInfo {
        val ang = moonAngle(now)
        val (name, emoji) = phaseName(ang)
        val rise = crossing(now, now + 30 * HOUR, -0.833, true) { moonAlt(lat, lon, it) }
        val set = crossing(now, now + 30 * HOUR, -0.833, false) { moonAlt(lat, lon, it) }
        val next = listOf(Triple("Luna nueva", "🌑", 0.0), Triple("Cuarto creciente", "🌓", 90.0), Triple("Luna llena", "🌕", 180.0),
            Triple("Cuarto menguante", "🌗", 270.0)).map { (n, e, a) -> Triple(n, e, nextPhase(now, a)) }.sortedBy { it.third }
        return MoonInfo(name, emoji, (illumination(now) * 100).roundToInt(), ang < 180, ang, rise, set, next)
    }

    // =====================================================================
    // 🌅 LA NOCHE: PUESTA DEL SOL, NOCHE CERRADA Y AMANECER
    // =====================================================================
    class Night(val start: Long, val end: Long, val sunset: Long?, val sunrise: Long?, val darkStart: Long?, val darkEnd: Long?)

    /** La noche de hoy (o la que está en curso si es de madrugada). */
    fun night(lat: Double, lon: Double, now: Long): Night {
        val alt = { t: Long -> sunAlt(lat, lon, t) }
        val nightNow = alt(now) < -0.833
        val sunset = if (nightNow) {
            // ¿cuándo empezó? se busca hacia atrás
            var t = now; var found: Long? = null
            while (t > now - 20 * HOUR) { if (alt(t - 10 * MIN) >= -0.833) { found = crossing(t - 10 * MIN, t + MIN, -0.833, false, alt); break }; t -= 10 * MIN }
            found
        } else crossing(now, now + 26 * HOUR, -0.833, false, alt)
        val from = sunset ?: now
        val sunrise = crossing(from + MIN, from + 26 * HOUR, -0.833, true, alt)
        val end = sunrise ?: (from + 12 * HOUR)
        val darkStart = crossing(from, end, -18.0, false, alt) ?: if (alt(from) < -18) from else null
        val darkEnd = darkStart?.let { crossing(it, end, -18.0, true, alt) }
        return Night(from, end, sunset, sunrise, darkStart, darkEnd)
    }

    // =====================================================================
    // 🪐 PLANETAS VISIBLES ESTA NOCHE
    // =====================================================================
    class PlanetNight(val p: Planet, val from: Long, val to: Long, val best: Long, val bestAlt: Int, val bestAz: Double, val mag: Double, val sunDist: Int)

    fun planetsTonight(lat: Double, lon: Double, n: Night): Pair<List<PlanetNight>, List<String>> {
        val vis = mutableListOf<PlanetNight>()
        val hidden = mutableListOf<String>()
        for (p in Planet.values()) {
            val minAlt = if (p == Planet.MERCURY || p == Planet.VENUS) 3.0 else 8.0
            var first: Long? = null; var last: Long? = null; var best = 0L; var bestAlt = -90.0; var bestAz = 0.0
            var t = n.start
            while (t <= n.end) {
                if (sunAlt(lat, lon, t) < -6) {
                    val aa = altAz(planet(p, dayNumber(t)), lat, lon, t)
                    if (aa.alt > minAlt) {
                        if (first == null) first = t
                        last = t
                        if (aa.alt > bestAlt) { bestAlt = aa.alt; best = t; bestAz = aa.az }
                    }
                }
                t += 10 * MIN
            }
            val mid = (n.start + n.end) / 2
            val d = dayNumber(mid)
            val sunDist = separation(planet(p, d), sun(d)).roundToInt()
            if (first != null && last != null) vis += PlanetNight(p, first, last, best, bestAlt.roundToInt(), bestAz, magnitude(p, d), sunDist)
            else hidden += p.es + if (sunDist < 15) " (muy cerca del Sol)" else " (bajo el horizonte de noche)"
        }
        return vis to hidden
    }

    // =====================================================================
    // 🤝 CONJUNCIONES (próximos 30 días)
    // =====================================================================
    class Conj(val a: String, val b: String, val time: Long, val sep: Double, val when_: String, val dir: String)

    private val naked = listOf(Planet.MERCURY, Planet.VENUS, Planet.MARS, Planet.JUPITER, Planet.SATURN)

    fun conjunctions(lat: Double, lon: Double, now: Long, days: Int = 30): List<Conj> {
        val out = mutableListOf<Conj>()
        // Luna con cada planeta (cada 2 h) y planetas entre sí (cada 12 h)
        val pairs = naked.map { "Luna" to it } + naked.flatMapIndexed { i, a -> naked.drop(i + 1).map { b -> a.es to b } }
        for ((aName, b) in pairs) {
            val isMoon = aName == "Luna"
            val step = if (isMoon) 2 * HOUR else 12 * HOUR
            val limit = if (isMoon) 4.0 else 2.5
            fun sep(t: Long): Double {
                val d = dayNumber(t)
                val pa = if (isMoon) moon(d) else planet(Planet.values().first { it.es == aName }, d)
                return separation(pa, planet(b, d))
            }
            var t = now
            var s0 = sep(t - step); var s1 = sep(t)
            while (t < now + days * DAY) {
                val s2 = sep(t + step)
                if (s1 <= s0 && s1 < s2 && s1 < limit) {
                    // refina el mínimo
                    var lo = t - step; var hi = t + step
                    repeat(20) {
                        val m1 = lo + (hi - lo) / 3; val m2 = hi - (hi - lo) / 3
                        if (sep(m1) < sep(m2)) hi = m2 else lo = m1
                    }
                    val tm = (lo + hi) / 2
                    viewing(lat, lon, tm, isMoon, aName, b)?.let { (w, dir) -> out += Conj(aName, b.es, tm, sep(tm), w, dir) }
                }
                s0 = s1; s1 = s2; t += step
            }
        }
        return out.sortedBy { it.time }
    }

    /** ¿Se ve la pareja esa noche? → («al anochecer», «el oeste») o null. */
    private fun viewing(lat: Double, lon: Double, tm: Long, isMoon: Boolean, aName: String, b: Planet): Pair<String, String>? {
        var bestT = 0L; var bestAlt = 0.0; var bestAz = 0.0
        var t = tm - 12 * HOUR
        while (t <= tm + 12 * HOUR) {
            if (sunAlt(lat, lon, t) < -6) {
                val d = dayNumber(t)
                val pa = if (isMoon) moon(d) else planet(Planet.values().first { it.es == aName }, d)
                val a1 = altAz(pa, lat, lon, t, topoMoon = isMoon); val a2 = altAz(planet(b, d), lat, lon, t)
                val low = minOf(a1.alt, a2.alt)
                if (low > 8 && low > bestAlt) { bestAlt = low; bestT = t; bestAz = a2.az }
            }
            t += 20 * MIN
        }
        if (bestT == 0L) return null
        val sunUpSoon = sunAlt(lat, lon, bestT + 2 * HOUR) > -0.833
        val sunJustSet = sunAlt(lat, lon, bestT - 2 * HOUR) > -0.833
        val w = when { sunJustSet -> "al anochecer"; sunUpSoon -> "de madrugada"; else -> "de noche" }
        return w to direction(bestAz)
    }

    // =====================================================================
    // ☄️ LLUVIAS DE ESTRELLAS
    // =====================================================================
    /** Noche del máximo (mes, día de la tarde), estrellas por hora en cielo ideal (THZ), radiante (AR, Dec) */
    class Shower(val name: String, val month: Int, val day: Int, val zhr: Int, val ra: Double, val dec: Double)

    val SHOWERS = listOf(
        Shower("Cuadrántidas", 1, 3, 110, 230.0, 49.0), Shower("Alfa Centáuridas", 2, 7, 6, 210.0, -59.0),
        Shower("Líridas", 4, 21, 18, 271.0, 34.0), Shower("Eta Acuáridas", 5, 5, 50, 338.0, -1.0),
        Shower("Delta Acuáridas del sur", 7, 29, 25, 340.0, -16.0), Shower("Perseidas", 8, 12, 100, 48.0, 58.0),
        Shower("Dracónidas", 10, 8, 10, 262.0, 54.0), Shower("Oriónidas", 10, 21, 20, 95.0, 16.0),
        Shower("Táuridas del norte", 11, 11, 5, 58.0, 22.0), Shower("Leónidas", 11, 16, 15, 152.0, 22.0),
        Shower("Gemínidas", 12, 13, 150, 112.0, 33.0), Shower("Úrsidas", 12, 21, 10, 217.0, 76.0)
    )

    /** «perseidas», «eta acuáridas», «gemínidas» → la lluvia que nombró */
    fun namedShower(q: String): Shower? {
        val low = q.lowercase()
        return SHOWERS.firstOrNull { sh ->
            val key = sh.name.lowercase().split(' ').let { w -> if (w.size > 1) w.take(2).joinToString(" ") else w[0] }
            Regex("(?<![\\p{L}])" + java.util.regex.Pattern.quote(key) + "(?![\\p{L}])").containsMatchIn(low) ||
                Regex("(?<![\\p{L}])" + java.util.regex.Pattern.quote(key.replace(Regex("[áéíóú]")) { m -> "aeiou"["áéíóú".indexOf(m.value)].toString() }) + "(?![\\p{L}])").containsMatchIn(low)
        }
    }

    class ShowerNight(val s: Shower, val evening: Long, val maxRadiantAlt: Int, val best: Long?, val bestAz: Double, val perHour: Int,
                      val moonIllum: Int, val moonUp: Boolean)

    fun showers(lat: Double, lon: Double, now: Long, tz: TimeZone, count: Int = 3, onlyName: String? = null): List<ShowerNight> {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val year = cal.get(Calendar.YEAR)
        val list = SHOWERS.filter { onlyName == null || it.name.equals(onlyName, true) }.map { s ->
            fun evening(y: Int) = Calendar.getInstance(tz).apply { clear(); set(y, s.month - 1, s.day, 20, 0) }.timeInMillis
            var ev = evening(year)
            if (ev < now - 2 * DAY) ev = evening(year + 1)
            s to ev
        }.sortedBy { it.second }.take(count)
        return list.map { (s, ev) ->
            val n = night(lat, lon, ev - 4 * HOUR)
            var best: Long? = null; var bestAlt = -90.0; var bestAz = 0.0
            var t = n.start
            while (t <= n.end) {
                if (sunAlt(lat, lon, t) < -12) {
                    val a = altAz(Pos(s.ra, s.dec, 0.0, 0.0, 1.0, 0.0), lat, lon, t)
                    if (a.alt > bestAlt) { bestAlt = a.alt; best = t; bestAz = a.az }
                }
                t += 15 * MIN
            }
            val tt = best ?: ev
            val moonUp = moonAlt(lat, lon, tt) > 0
            val illum = (illumination(tt) * 100).roundToInt()
            val perHour = if (bestAlt <= 0) 0 else (s.zhr * sind(bestAlt) * (if (moonUp && illum > 50) 0.5 else 1.0)).roundToInt()
            ShowerNight(s, ev, (90 - abs(lat - s.dec)).roundToInt(), best, bestAz, perHour, illum, moonUp)
        }
    }

    // =====================================================================
    // 🌑 ECLIPSES (lista oficial de la NASA, 2027-2030)
    // =====================================================================
    class Eclipse(val solar: Boolean, val year: Int, val month: Int, val day: Int, val type: String, val where: String, val countries: Set<String>)

    private val SA = setOf("AR", "BO", "BR", "CL", "CO", "EC", "PY", "PE", "UY", "VE")
    private val CA = setOf("MX", "GT", "HN", "SV", "NI", "CR", "PA", "CU", "DO", "PR")

    val ECLIPSES = listOf(
        Eclipse(true, 2027, 2, 6, "anular", "anular en una franja de Chile, Argentina y el Atlántico; parcial en Sudamérica, la Antártida y el oeste y sur de África", SA),
        Eclipse(false, 2027, 2, 20, "penumbral", "", emptySet()),
        Eclipse(false, 2027, 7, 18, "penumbral", "", emptySet()),
        Eclipse(true, 2027, 8, 2, "total", "total en Marruecos, el sur de España, Argelia, Libia, Egipto, Arabia Saudita, Yemen y Somalia; parcial en África, Europa, Medio Oriente y el oeste y sur de Asia", setOf("ES")),
        Eclipse(false, 2027, 8, 17, "penumbral", "", emptySet()),
        Eclipse(false, 2028, 1, 12, "parcial", "", emptySet()),
        Eclipse(true, 2028, 1, 26, "anular", "anular en Ecuador, Perú, Brasil, Surinam, España y Portugal; parcial en el este de Norteamérica, Centro y Sudamérica, el oeste de Europa y el noroeste de África", SA + CA + setOf("US", "ES")),
        Eclipse(false, 2028, 7, 6, "parcial", "", emptySet()),
        Eclipse(true, 2028, 7, 22, "total", "total en Australia y Nueva Zelanda; parcial en el sudeste asiático, Indonesia, Australia y Nueva Zelanda", emptySet()),
        Eclipse(false, 2028, 12, 31, "total", "", emptySet()),
        Eclipse(true, 2029, 1, 14, "parcial", "parcial en Norteamérica y Centroamérica", CA + setOf("US")),
        Eclipse(true, 2029, 6, 12, "parcial", "parcial en el Ártico, Escandinavia, Alaska, el norte de Asia y el norte de Canadá", setOf("US")),
        Eclipse(false, 2029, 6, 26, "total", "", emptySet()),
        Eclipse(true, 2029, 7, 11, "parcial", "parcial en el sur de Chile y el sur de Argentina", setOf("CL", "AR")),
        Eclipse(true, 2029, 12, 5, "parcial", "parcial en el sur de Argentina, el sur de Chile y la Antártida", setOf("CL", "AR")),
        Eclipse(false, 2029, 12, 20, "total", "", emptySet()),
        Eclipse(true, 2030, 6, 1, "anular", "anular en Argelia, Túnez, Grecia, Turquía, Rusia, el norte de China y Japón; parcial en Europa, el norte de África, Medio Oriente, Asia, el Ártico y Alaska", setOf("ES", "US")),
        Eclipse(false, 2030, 6, 15, "parcial", "", emptySet()),
        Eclipse(true, 2030, 11, 25, "total", "total en Botswana, Sudáfrica y Australia; parcial en el sur de África, el sur del océano Índico, Indonesia, Australia y la Antártida", emptySet())
    )

    class EclipseView(val e: Eclipse, val time: Long, val visibleHere: Boolean, val note: String)

    fun eclipses(lat: Double, lon: Double, now: Long, country: String, count: Int = 4, solarOnly: Boolean? = null): List<EclipseView> {
        val utc = TimeZone.getTimeZone("UTC")
        return ECLIPSES.asSequence().filter { solarOnly == null || it.solar == solarOnly }.mapNotNull { e ->
            val day0 = Calendar.getInstance(utc).apply { clear(); set(e.year, e.month - 1, e.day, 0, 0) }.timeInMillis
            if (day0 < now - DAY) return@mapNotNull null
            // El máximo está a minutos de la Luna nueva (Sol) o llena (Luna) de ese día
            val t = nextPhase(day0 - 2 * DAY, if (e.solar) 0.0 else 180.0)
            if (e.solar) {
                val inCountry = country.uppercase() in e.countries
                val sunUp = sunAlt(lat, lon, t) > 0
                val sunNear = (-90..90 step 15).any { m -> sunAlt(lat, lon, t + m * MIN) > 0 }
                EclipseView(e, t, inCountry && sunNear, when {
                    inCountry && sunUp -> "✅ Tu país está en la zona donde se ve"
                    inCountry && sunNear -> "✅ Tu país está en la zona; desde donde estás se ve con el Sol bajo (al salir o al ponerse)"
                    inCountry -> "Tu país está en la zona, pero a esa hora el Sol no está sobre tu horizonte"
                    else -> "No se ve desde tu país"
                })
            } else {
                val up = moonAlt(lat, lon, t) > 0
                val partly = !up && (-150..150 step 15).any { m -> moonAlt(lat, lon, t + m * MIN) > 0 }
                EclipseView(e, t, up || partly, when {
                    up -> "✅ Se ve desde tu ubicación (la Luna estará sobre el horizonte)"
                    partly -> "✅ Se ve en parte: la Luna sale o se pone durante el eclipse"
                    else -> "No se ve desde tu ubicación (la Luna estará bajo el horizonte)"
                })
            }
        }.take(count).toList()
    }

    // =====================================================================
    // 🧩 TEXTO PARA EL CHAT
    // =====================================================================
    enum class Topic { MOON, PLANETS, SHOWERS, ECLIPSES, ALL }

    /** Qué partes mostrar según lo que preguntó; null = no es del cielo (p. ej. un descubrimiento o una misión espacial). */
    fun topics(q: String): Set<Topic>? {
        val t = mutableSetOf<Topic>()
        if (Regex("(?iu)\\bluna\\b|lunar|superluna|cuarto (creciente|menguante)").containsMatchIn(q)) t += Topic.MOON
        if (Regex("(?iu)planeta|j[uú]piter|saturno|marte|venus|mercurio|urano|neptuno|conjunci|alineaci").containsMatchIn(q)) t += Topic.PLANETS
        if (Regex("(?iu)lluvias? de (estrellas|meteoros)|meteoros?|estrellas fugaces").containsMatchIn(q) || namedShower(q) != null) t += Topic.SHOWERS
        if (Regex("(?iu)eclipse").containsMatchIn(q)) t += Topic.ECLIPSES
        if (t.isNotEmpty()) return t
        val skyWords = Regex("(?iu)cielo|observ|telescop|binocular|astrofotograf|estrellas|astronom|constelaci|v[ií]a l[aá]ctea|noche|qu[eé] (se )?(puedo )?ver|cometa|evento")
        val newsWords = Regex("(?iu)descubr|nasa|webb|hubble|galaxia|agujero negro|exoplaneta|misi[oó]n|cohete|spacex|artemis|estaci[oó]n espacial|noticia|big bang|universo")
        return if (skyWords.containsMatchIn(q) || !newsWords.containsMatchIn(q)) setOf(Topic.ALL) else null
    }

    private val MES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val DIA = listOf("dom", "lun", "mar", "mié", "jue", "vie", "sáb")

    fun hhmm(ms: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun date(ms: Long, tz: TimeZone, withYear: Boolean = false): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return DIA[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MES[c.get(Calendar.MONTH)] +
            if (withYear) " " + c.get(Calendar.YEAR) else ""
    }

    private fun dayLabel(ms: Long, now: Long, tz: TimeZone): String {
        val a = Calendar.getInstance(tz).apply { timeInMillis = now }; val b = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val diff = ((b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 400 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR))
        return when (diff) { 0 -> "hoy"; 1 -> "mañana"; -1 -> "ayer"; else -> date(ms, tz) }
    }

    private fun mag(m: Double) = "%.1f".format(java.util.Locale("es"), m)

    class Report(val area: String, val lat: Double, val lon: Double, val now: Long, val tz: TimeZone, val moon: MoonInfo, val night: Night,
                 val planets: List<PlanetNight>, val hidden: List<String>, val conj: List<Conj>, val showers: List<ShowerNight>,
                 val eclipses: List<EclipseView>) {
        val southern: Boolean get() = lat < 0
    }

    fun report(area: String, lat: Double, lon: Double, now: Long, tz: TimeZone, country: String, question: String = ""): Report {
        val n = night(lat, lon, now)
        val (vis, hid) = planetsTonight(lat, lon, n)
        val named = namedShower(question)?.name
        val solarOnly = when {
            Regex("(?iu)eclipse (de|del) sol|eclipse solar").containsMatchIn(question) -> true
            Regex("(?iu)eclipse (de|del) (la )?luna|eclipse lunar").containsMatchIn(question) -> false
            else -> null
        }
        return Report(area, lat, lon, now, tz, moonInfo(lat, lon, now), n, vis, hid, conjunctions(lat, lon, now),
            showers(lat, lon, now, tz, if (named != null) 1 else 3, named), eclipses(lat, lon, now, country, 4, solarOnly))
    }

    fun text(r: Report, topics: Set<Topic>): String {
        val tz = r.tz
        val all = Topic.ALL in topics
        val sb = StringBuilder("🔭 El cielo desde ").append(r.area).append(" (hemisferio ").append(if (r.southern) "sur" else "norte")
            .append(") — calculado por la app para tu ubicación:")
        // 🌙 Luna
        if (all || Topic.MOON in topics || Topic.SHOWERS in topics) {
            val m = r.moon
            sb.append("\n\n").append(m.emoji).append(" Luna: ").append(m.name).append(", ").append(m.illum).append("% iluminada")
            m.rise?.let { sb.append(" · sale ").append(dayLabel(it, r.now, tz)).append(" a las ").append(hhmm(it, tz)) }
            m.set?.let { sb.append(" · se pone ").append(dayLabel(it, r.now, tz)).append(" a las ").append(hhmm(it, tz)) }
            if (all || Topic.MOON in topics) {
                sb.append("\n   Próximas fases:")
                m.next.forEach { (n, e, t) -> sb.append("\n   ").append(e).append(" ").append(n).append(": ").append(date(t, tz)).append(" ").append(hhmm(t, tz)) }
                if (r.southern) sb.append("\n   (En el hemisferio sur la Luna creciente se ilumina por la izquierda: se ve como una «C».)")
            }
        }
        // 🌅 Noche
        val n = r.night
        if (all || Topic.PLANETS in topics || Topic.MOON in topics) {
            sb.append("\n\n🌅 Sol: ")
            val parts = mutableListOf<String>()
            n.sunset?.let { parts += "se pone ${dayLabel(it, r.now, tz)} a las ${hhmm(it, tz)}" }
            n.sunrise?.let { parts += "sale ${dayLabel(it, r.now, tz)} a las ${hhmm(it, tz)}" }
            sb.append(parts.joinToString(" · ").ifBlank { "sin puesta ni salida hoy" })
            if (n.darkStart != null && n.darkEnd != null) sb.append("\n🌌 Noche cerrada (mejor para observar): de ").append(hhmm(n.darkStart, tz)).append(" a ").append(hhmm(n.darkEnd, tz))
            else if (n.darkStart == null) sb.append("\n🌌 Esta noche no oscurece por completo en tu latitud.")
        }
        // 🪐 Planetas
        if (all || Topic.PLANETS in topics) {
            sb.append("\n\n🪐 Planetas visibles esta noche:")
            if (r.planets.isEmpty()) sb.append("\n   Ninguno a buena altura esta noche.")
            r.planets.sortedBy { it.mag }.forEach { p ->
                sb.append("\n   ").append(p.p.emoji).append(" ").append(p.p.es).append(" — de ").append(hhmm(p.from, tz)).append(" a ").append(hhmm(p.to, tz))
                    .append(" · más alto a las ").append(hhmm(p.best, tz)).append(" hacia ").append(direction(p.bestAz)).append(" (").append(p.bestAlt).append("° de altura)")
                    .append(" · brillo ").append(mag(p.mag))
                sb.append("\n      👁️ ").append(p.p.tip)
            }
            if (r.hidden.isNotEmpty()) sb.append("\n   No visibles: ").append(r.hidden.joinToString(", ")).append(".")
            val conj = r.conj.take(if (Topic.PLANETS in topics) 6 else 3)
            if (conj.isNotEmpty()) {
                sb.append("\n\n🤝 Conjunciones (próximos 30 días):")
                conj.forEach { c ->
                    sb.append("\n   • ").append(date(c.time, tz)).append(": ").append(if (c.a == "Luna") "la Luna" else c.a).append(" y ").append(c.b)
                        .append(" a ").append("%.1f".format(java.util.Locale("es"), c.sep)).append("° — se ven ").append(c.when_).append(" hacia ").append(c.dir)
                }
            }
        }
        // ☄️ Lluvias de estrellas
        if (all || Topic.SHOWERS in topics) {
            val list = if (all && Topic.SHOWERS !in topics) r.showers.take(1) else r.showers
            if (list.isNotEmpty()) sb.append("\n\n☄️ Lluvias de estrellas:")
            list.forEach { s ->
                sb.append("\n   • ").append(s.s.name).append(" — máximo la noche del ").append(date(s.evening, tz))
                if (s.maxRadiantAlt < 10) {
                    sb.append("\n      Desde tu latitud su punto de origen (radiante) casi no sube: verás muy pocas o ninguna.")
                } else {
                    sb.append(" · hasta ~").append(s.perHour).append(" por hora en cielo oscuro")
                    s.best?.let { sb.append("\n      Mejor hora: cerca de las ").append(hhmm(it, tz)).append(", mirando hacia ").append(direction(s.bestAz)).append(" (y a todo el cielo)") }
                    sb.append("\n      🌙 Luna ").append(s.moonIllum).append("%").append(
                        when { !s.moonUp -> " bajo el horizonte: cielo ideal"; s.moonIllum > 50 -> " sobre el horizonte: su luz tapa las más débiles"; else -> ": molesta poco" })
                }
            }
            if (list.isNotEmpty()) sb.append("\n   👁️ Se ven a simple vista: recuéstate, sin binoculares, lejos de las luces y espera 20 minutos a que tus ojos se adapten.")
        }
        // 🌑 Eclipses
        if (all || Topic.ECLIPSES in topics) {
            val list = if (all && Topic.ECLIPSES !in topics) r.eclipses.take(2) else r.eclipses
            if (list.isNotEmpty()) sb.append("\n\n🌑 Próximos eclipses (NASA):")
            list.forEach { v ->
                val e = v.e
                sb.append("\n   • ").append(date(v.time, tz, withYear = true)).append(" — eclipse ").append(e.type).append(if (e.solar) " de Sol" else " de Luna")
                    .append(" (máximo cerca de las ").append(hhmm(v.time, tz)).append(")")
                if (e.where.isNotBlank()) sb.append("\n      Se ve: ").append(e.where)
                sb.append("\n      ").append(v.note)
                if (!e.solar && e.type == "penumbral") sb.append(" · es penumbral: la Luna solo se oscurece levemente")
            }
            if (list.any { it.e.solar }) sb.append("\n   😎 Eclipse de Sol: míralo SOLO con lentes certificados ISO 12312-2; nunca con binoculares, telescopio o cámara sin filtro solar.")
        }
        // 🔭 Recomendaciones
        sb.append("\n\n🔭 Para observar: aléjate de las luces de la ciudad, usa una linterna roja y deja que tus ojos se adapten 20 minutos.")
        val bright = r.planets.filter { it.p == Planet.JUPITER || it.p == Planet.SATURN || it.p == Planet.MARS || it.p == Planet.VENUS }
        if (bright.isNotEmpty()) sb.append(" Con binoculares o un telescopio pequeño apunta a ").append(
            bright.map { it.p.es }.let { l -> if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " y " + l.last() }).append(".")
        if (r.moon.illum in 15..85) sb.append(" La Luna con telescopio se ve mejor cerca de la línea entre la luz y la sombra (cráteres en relieve).")
        sb.append("\n📷 Astrofotografía: trípode y disparador con retardo; cielo y estrellas: ISO 1600-3200, f/2.8 y 15-20 s (regla 500 ÷ distancia focal); ")
            .append("Luna: ISO 100, 1/125 s y f/8; celular: modo noche o «pro» apoyado en algo firme.")
        return sb.toString()
    }
}
