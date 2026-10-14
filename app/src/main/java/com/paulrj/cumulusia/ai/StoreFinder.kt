package com.paulrj.cumulusia.ai

import java.net.URLEncoder
import java.util.Calendar

/**
 * 🏬🛒 TIENDAS Y SUPERMERCADOS REALES CERCA DE TI (mapa de OpenStreetMap, gratis y sin clave):
 * nombre, distancia, 📍 dirección, 📞 teléfono, 🕒 horario de cada día y si está 🟢 ABIERTO o 🔴 CERRADO ahora
 * (a qué hora cierra o abre), más el enlace a Google Maps. Lo arma la app con datos reales: la IA no lo inventa.
 */
object StoreFinder {

    enum class Kind(val label: String, val emoji: String, val osm: String) {
        SUPERMARKET("Supermercados", "🛒", "nwr[\"shop\"~\"^(supermarket|wholesale)$\"]"),
        CONVENIENCE("Minimarkets y almacenes", "🏪", "nwr[\"shop\"~\"^(convenience|greengrocer|butcher|bakery)$\"]"),
        MALL("Malls y tiendas por departamento", "🏬", "nwr[\"shop\"~\"^(mall|department_store)$\"]"),
        HARDWARE("Ferreterías y tiendas para el hogar", "🔨", "nwr[\"shop\"~\"^(doityourself|hardware|houseware|furniture|paint)$\"]"),
        ELECTRONICS("Tiendas de tecnología", "📱", "nwr[\"shop\"~\"^(electronics|mobile_phone|computer|appliance)$\"]"),
        CLOTHES("Tiendas de ropa y calzado", "👕", "nwr[\"shop\"~\"^(clothes|shoes|sports|boutique|fashion_accessories)$\"]"),
        BEAUTY("Tiendas de belleza y perfumerías", "💄", "nwr[\"shop\"~\"^(cosmetics|beauty|perfumery|chemist)$\"]"),
        FUEL("Bencineras", "⛽", "nwr[\"amenity\"=\"fuel\"]"),
        BANK("Bancos y cajeros", "🏦", "nwr[\"amenity\"~\"^(bank|atm)$\"]"),
        ANY("Tiendas", "🏬", "nwr[\"shop\"~\"^(supermarket|mall|department_store|doityourself|hardware|electronics|clothes|convenience)$\"]")
    }

    /** Cadenas conocidas: si nombra una, se buscan solo sus sucursales. */
    private val BRANDS = listOf(
        "l[ií]der", "jumbo", "unimarc", "santa isabel", "tottus", "acuenta", "alvi", "mayorista 10", "ok market", "oxxo",
        "falabella", "ripley", "paris", "hites", "la polar", "abcdin", "sodimac", "easy", "construmart", "chilemat",
        "mall plaza", "mall del centro", "mall marina", "parque arauco", "costanera center", "cencosud", "walmart", "soriana",
        "carrefour", "mercadona", "coto", "wong", "plaza vea", "[eé]xito", "ol[ií]mpica",
        "copec", "shell", "aramco", "petrobras", "ypf", "banco ?estado", "santander", "bci", "scotiabank", "banco de chile",
        "ita[uú]", "pc factory", "entel", "movistar", "zara", "h&m", "skechers",
        "adidas", "nike", "preunic", "salcobrand", "cruz verde", "dbs", "maicao"
    )
    private val brandRx = Regex("(?iu)(?<![\\p{L}])(" + BRANDS.distinct().joinToString("|") + ")(?![\\p{L}])")

    /** Qué tipo de negocio busca («supermercado», «mall», «ferretería»…). */
    fun kindOf(question: String, category: WebSearchRules.Category?): Kind {
        val q = question.lowercase()
        fun has(vararg w: String) = w.any { Regex("(?iu)(?<![\\p{L}])$it").containsMatchIn(q) }
        return when {
            has("bencin", "gasolin", "combustible", "estaci[oó]n de servicio", "copec", "shell", "aramco") -> Kind.FUEL
            has("bancos?(?![\\p{L}])", "cajeros?", "banco ?estado", "santander", "scotiabank", "bci(?![\\p{L}])") -> Kind.BANK
            has("ferreter", "sodimac", "easy(?![\\p{L}])", "construmart", "chilemat", "materiales de construcci", "muebles") -> Kind.HARDWARE
            has("minimarket", "almac[eé]n", "botiller", "panader", "verduler", "carnicer", "negocio de la esquina") -> Kind.CONVENIENCE
            has("supermercado", "s[uú]per(?![\\p{L}])", "l[ií]der", "jumbo", "unimarc", "santa isabel", "tottus", "acuenta", "alvi",
                "mayorista", "walmart", "soriana", "carrefour", "mercadona", "canasta", "abarrotes") -> Kind.SUPERMARKET
            has("mall", "centro comercial", "tiendas? por departamento", "falabella", "ripley", "paris(?![\\p{L}])", "hites",
                "la polar", "abcdin", "parque arauco", "costanera") -> Kind.MALL
            has("celular", "computador", "notebook", "electr[oó]nica", "tecnolog", "pc factory", "electrodom") -> Kind.ELECTRONICS
            has("ropa", "zapat", "calzado", "vestuario", "polera", "pantal", "zara", "h&m", "bata(?![\\p{L}])", "deportiv") -> Kind.CLOTHES
            has("maquillaje", "cosm[eé]tic", "perfum", "belleza", "preunic", "maicao", "dbs(?![\\p{L}])") -> Kind.BEAUTY
            category == WebSearchRules.SUPERMERCADOS -> Kind.SUPERMARKET
            category == WebSearchRules.VESTUARIO -> Kind.CLOTHES
            category == WebSearchRules.MAQUILLAJE -> Kind.BEAUTY
            category == WebSearchRules.TECNOLOGIA -> Kind.ELECTRONICS
            else -> Kind.ANY
        }
    }

    /** ¿Pregunta por un local (horario, dirección, dónde queda, cerca)? Para ropa, belleza y tecnología solo entonces se buscan tiendas. */
    fun asksForStore(question: String): Boolean = Regex("(?iu)horario|abre|abren|cierra|cierran|abiert|cerrad|d[oó]nde (queda|hay|est[aá]|compro|venden)|" +
        "direcci[oó]n|sucursal|cerca|tienda|local(es)?(?![\\p{L}])|mall|supermercado").containsMatchIn(question)

    /** La cadena que nombró («Líder», «Sodimac»…) o null. */
    fun brand(question: String): String? = brandRx.find(question)?.value

    private fun f6(d: Double) = "%.6f".format(java.util.Locale.US, d)

    fun query(lat: Double, lon: Double, radiusM: Int, kind: Kind, brand: String?): String {
        val a = "(around:$radiusM,${f6(lat)},${f6(lon)})"
        val b = brand?.let { it.replace(Regex("[^\\p{L}0-9 ]"), ".") }
        val body = if (b != null)
            "${kind.osm.substringBefore('[')}[\"name\"~\"$b\",i]$a;${kind.osm.substringBefore('[')}[\"brand\"~\"$b\",i]$a;" +
                "nwr[\"shop\"][\"name\"~\"$b\",i]$a;"
        else "${kind.osm}[\"name\"]$a;"
        return "[out:json][timeout:20];($body);out center tags 120;"
    }

    class Store(val spot: NearbyFinder.Spot, val rawHours: String, val brandName: String)

    /** Respuesta de OpenStreetMap → tiendas (con el horario original para saber si está abierta ahora). */
    fun parse(json: String, lat: Double, lon: Double): List<Store> {
        val out = mutableListOf<Store>()
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name").ifBlank { t.optString("brand") }.trim()
            if (name.isBlank()) continue
            val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val phone = listOf("phone", "contact:phone", "contact:mobile").map { t.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty().split(';', ',').first().trim()
            val street = listOf(t.optString("addr:street"), t.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val area = t.optString("addr:suburb").ifBlank { t.optString("addr:city") }
            val web = listOf("website", "contact:website", "url").map { t.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                .let { w -> if (w.isBlank()) "" else if (w.startsWith("http")) w else if (w.contains('.')) "https://$w" else "" }
            val raw = t.optString("opening_hours").trim()
            out += Store(NearbyFinder.Spot(name, "", 0, phone, listOf(street, area).filter { it.isNotBlank() }.joinToString(", "), web,
                FoodFinder.hoursEs(raw), plat, plon, FoodFinder.distance(lat, lon, plat, plon)), raw, t.optString("brand"))
        }
        return out
    }

    /** Primero las que tienen horario y dirección, después las más cercanas. Sin repetir el mismo local. */
    fun rank(list: List<Store>): List<Store> {
        val seen = HashSet<String>()
        return list.sortedWith(compareByDescending<Store> { s ->
            (if (s.rawHours.isNotBlank()) 3 else 0) + (if (s.spot.address.isNotBlank()) 1 else 0) +
                (if (s.spot.phone.isNotBlank()) 1 else 0) - s.spot.distanceM / 1500
        }.thenBy { it.spot.distanceM })
            .filter { seen.add(it.spot.name.lowercase().replace(Regex("[^\\p{L}0-9]"), "") + "_" + (it.spot.distanceM / 150)) }
    }

    // ------------------------------------------------------------------------
    // 🕒 ¿ABIERTO AHORA? (horarios del mapa: «Mo-Fr 08:00-22:00; Sa 09:00-21:00; Su off»)
    // ------------------------------------------------------------------------
    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val DAY_ES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    /** Día de la semana 0 = lunes … 6 = domingo. */
    private fun dayIndex(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** Para cada día (0 = lunes), sus tramos en minutos [abre, cierra]. Null si el horario no se entiende. */
    fun weekTable(raw: String): List<List<IntArray>>? {
        val h = raw.trim()
        if (h.isBlank()) return null
        if (h == "24/7") return List(7) { listOf(intArrayOf(0, 24 * 60)) }
        val week = MutableList(7) { mutableListOf<IntArray>() }
        var understood = false
        for (ruleRaw in h.split(';')) {
            val rule = ruleRaw.trim()
            if (rule.isEmpty()) continue
            if (rule.startsWith("PH") || rule.startsWith("SH")) continue   // festivos: no se pueden saber aquí
            val m = Regex("^((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:\\s*[-,]\\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))*)?\\s*(?:,\\s*PH)?\\s*(.*)$").find(rule) ?: return null
            val daysPart = m.groupValues[1]
            val timesPart = m.groupValues[2].trim()
            val days = if (daysPart.isBlank()) (0..6).toList() else daysOf(daysPart) ?: return null
            if (timesPart.equals("off", true) || timesPart.equals("closed", true)) {
                days.forEach { week[it].clear() }; understood = true; continue
            }
            val spans = timesPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { span ->
                val t = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\+?$").find(span) ?: return null
                val (h1, m1, h2, m2) = t.destructured
                intArrayOf(h1.toInt() * 60 + m1.toInt(), h2.toInt() * 60 + m2.toInt())
            }
            if (spans.isEmpty()) return null
            days.forEach { d -> week[d].clear(); week[d].addAll(spans) }
            understood = true
        }
        return if (understood) week else null
    }

    private fun daysOf(part: String): List<Int>? {
        val out = mutableListOf<Int>()
        for (piece in part.split(',').map { it.trim() }) {
            if ('-' in piece) {
                val (a, b) = piece.split('-').map { DAYS.indexOf(it.trim()) }
                if (a < 0 || b < 0) return null
                var d = a
                while (true) { out += d; if (d == b) break; d = (d + 1) % 7 }
            } else {
                val d = DAYS.indexOf(piece); if (d < 0) return null; out += d
            }
        }
        return out
    }

    private fun hhmm(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

    /** «🟢 Abierto ahora · cierra a las 22:00» / «🔴 Cerrado ahora · abre hoy a las 08:30» / null si no hay horario claro. */
    fun openStatus(raw: String, now: Calendar = Calendar.getInstance()): String? {
        val week = weekTable(raw) ?: return null
        val today = dayIndex(now)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (raw.trim() == "24/7") return "🟢 Abierto ahora · las 24 horas"
        // Tramo de ayer que pasa la medianoche (ej. 18:00-02:00)
        val yesterday = (today + 6) % 7
        week[yesterday].firstOrNull { it[1] <= it[0] && minute < it[1] }?.let { return "🟢 Abierto ahora · cierra a las ${hhmm(it[1])}" }
        for (s in week[today]) {
            val close = if (s[1] <= s[0]) s[1] + 24 * 60 else s[1]
            if (minute >= s[0] && minute < close) return "🟢 Abierto ahora · cierra a las ${hhmm(close)}"
        }
        week[today].filter { it[0] > minute }.minByOrNull { it[0] }?.let { return "🔴 Cerrado ahora · abre hoy a las ${hhmm(it[0])}" }
        for (k in 1..7) {
            val d = (today + k) % 7
            week[d].minByOrNull { it[0] }?.let { s ->
                val whenTxt = if (k == 1) "mañana" else "el " + DAY_ES[d]
                return "🔴 Cerrado ahora · abre $whenTxt a las ${hhmm(s[0])}"
            }
        }
        return "🔴 Cerrado"
    }

    /** «hoy: 08:30 a 22:00» (o «hoy: cerrado»). */
    fun todayHours(raw: String, now: Calendar = Calendar.getInstance()): String? {
        if (raw.trim() == "24/7") return "hoy: abierto las 24 horas"
        val week = weekTable(raw) ?: return null
        val spans = week[dayIndex(now)]
        return if (spans.isEmpty()) "hoy: cerrado" else "hoy: " + spans.joinToString(" y ") { "${hhmm(it[0])} a ${hhmm(it[1])}" }
    }

    private fun mapsUrl(s: NearbyFinder.Spot): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode("${s.name} ${s.lat},${s.lon}", "UTF-8")

    /** El bloque que se ve en la pantalla: cada local con su horario de hoy, si está abierto y su mapa. */
    fun block(kind: Kind, stores: List<Store>, area: String, now: Calendar = Calendar.getInstance()): String {
        if (stores.isEmpty()) return ""
        val sb = StringBuilder("${kind.emoji} ${kind.label} cerca de ${area.ifBlank { "ti" }} (mapa de OpenStreetMap):\n")
        stores.forEachIndexed { i, st ->
            val s = st.spot
            sb.append("\n").append(i + 1).append(". ").append(s.name).append(" — ").append(NearbyFinder.distanceLabel(s.distanceM)).append('\n')
            sb.append("   📍 ").append(s.address.ifBlank { "Dirección: toca el mapa" }).append('\n')
            val status = openStatus(st.rawHours, now)
            val today = todayHours(st.rawHours, now)
            when {
                status != null -> sb.append("   ").append(status).append(today?.let { " ($it)" } ?: "").append('\n')
                s.hours.isNotBlank() -> sb.append("   🕒 ").append(s.hours).append('\n')
                else -> sb.append("   🕒 Horario: no está en el mapa (míralo en Google Maps)\n")
            }
            if (status != null && s.hours.isNotBlank()) sb.append("   🗓️ ").append(s.hours).append('\n')
            if (s.phone.isNotBlank()) sb.append("   📞 ").append(s.phone).append('\n')
            if (s.website.isNotBlank()) sb.append("   🌐 ").append(s.website).append('\n')
            sb.append("   🗺️ ").append(mapsUrl(s)).append('\n')
        }
        sb.append("\n🕒 Horarios del mapa: en festivos pueden cambiar; confírmalos en Google Maps.")
        return sb.toString().trimEnd()
    }
}

