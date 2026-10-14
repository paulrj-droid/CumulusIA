package com.paulrj.cumulusia.ai

import java.net.URLEncoder

/**
 * 🗺️ DATOS REALES DEL MAPA (OpenStreetMap, gratis y sin claves) para tres categorías de tu lista:
 *  🚌 BUSES URBANOS E INTERURBANOS → la línea que pediste (de dónde a dónde, operador, frecuencia, horario y sus paraderos
 *     en orden), los paraderos más cercanos a ti y las líneas que pasan cerca, más el enlace «cómo llegar» en transporte público.
 *  🏖️ LUGARES DE VACACIONES → atractivos del lugar (museos, miradores, playas, parques…) con foto y mapa.
 *  🏨 HOTELES → hoteles, hostales y cabañas con ⭐ estrellas, 📍 dirección, 📞 teléfono, sitio web, foto y el enlace
 *     para ver el precio por noche, la disponibilidad y las valoraciones en Google Hoteles.
 * La IA agrega lo que encuentra en internet (tarifas, opiniones, cambios de ruta); estos datos los pone la app tal cual.
 */
object NearbyFinder {

    class Spot(
        val name: String,
        val type: String,
        val stars: Int,
        val phone: String,
        var address: String,
        val website: String,
        val hours: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Int
    ) {
        val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode("$name $lat,$lon", "UTF-8")
    }

    class Line(
        val ref: String,
        val name: String,
        val from: String,
        val to: String,
        val operator: String,
        val interval: String,
        val hours: String,
        val fare: String,
        val stops: List<String>
    )

    private fun f6(d: Double) = "%.6f".format(java.util.Locale.US, d)
    private fun around(r: Int, lat: Double, lon: Double) = "(around:$r,${f6(lat)},${f6(lon)})"
    fun url(query: String): String = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
    fun distanceLabel(m: Int) = if (m < 1000) "a $m m" else "a " + "%.1f".format(java.util.Locale("es"), m / 1000.0) + " km"

    // =====================================================================
    // 🏨 HOTELES Y 🏖️ ATRACTIVOS
    // =====================================================================
    private val typeEs = mapOf(
        "hotel" to "hotel", "hostel" to "hostal", "guest_house" to "hospedaje", "motel" to "motel", "apartment" to "departamento turístico",
        "chalet" to "cabaña", "camp_site" to "camping", "attraction" to "atracción", "museum" to "museo", "viewpoint" to "mirador",
        "theme_park" to "parque de diversiones", "zoo" to "zoológico", "gallery" to "galería de arte", "aquarium" to "acuario",
        "beach" to "playa", "park" to "parque", "nature_reserve" to "reserva natural", "national_park" to "parque nacional",
        "castle" to "castillo", "monument" to "monumento", "ruins" to "ruinas", "waterfall" to "cascada"
    )

    fun hotelQuery(lat: Double, lon: Double, radiusM: Int): String =
        "[out:json][timeout:20];(nwr[\"tourism\"~\"^(hotel|hostel|guest_house|motel|apartment|chalet|camp_site)$\"][\"name\"]" +
            "${around(radiusM, lat, lon)};);out center tags 150;"

    fun attractionQuery(lat: Double, lon: Double, radiusM: Int): String {
        val a = around(radiusM, lat, lon)
        return "[out:json][timeout:25];(" +
            "nwr[\"tourism\"~\"^(attraction|museum|viewpoint|theme_park|zoo|gallery|aquarium)$\"][\"name\"]$a;" +
            "nwr[\"natural\"~\"^(beach|waterfall)$\"][\"name\"]$a;" +
            "nwr[\"boundary\"=\"national_park\"][\"name\"]$a;" +
            "nwr[\"historic\"~\"^(castle|monument|ruins)$\"][\"name\"]$a;" +
            ");out center tags 150;"
    }

    /** Lee hoteles o atractivos de la respuesta de OpenStreetMap. Requiere org.json. */
    fun parseSpots(json: String, lat: Double, lon: Double): List<Spot> {
        val out = mutableListOf<Spot>()
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name").trim()
            if (name.isBlank()) continue
            val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val rawType = listOf("tourism", "natural", "historic", "boundary", "leisure").map { t.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val phone = listOf("phone", "contact:phone", "contact:mobile", "contact:whatsapp").map { t.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty().split(';', ',').first().trim()
            val street = listOf(t.optString("addr:street"), t.optString("addr:housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val area = t.optString("addr:suburb").ifBlank { t.optString("addr:city") }
            val web = listOf("website", "contact:website", "url", "contact:instagram", "contact:facebook").map { t.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty().let { w -> when {
                    w.isBlank() -> ""
                    w.startsWith("http") -> w
                    w.startsWith("@") -> "https://instagram.com/" + w.removePrefix("@")
                    w.contains('.') -> "https://$w"
                    else -> ""
                } }
            out += Spot(name, typeEs[rawType] ?: rawType.replace('_', ' '), t.optString("stars").take(1).toIntOrNull() ?: 0, phone,
                listOf(street, area).filter { it.isNotBlank() }.joinToString(", "), web, FoodFinder.hoursEs(t.optString("opening_hours")),
                plat, plon, FoodFinder.distance(lat, lon, plat, plon))
        }
        return out
    }

    private fun dedupe(list: List<Spot>): List<Spot> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.name.lowercase().replace(Regex("[^\\p{L}0-9]"), "")) }
    }

    /** Hoteles: primero los que tienen teléfono, sitio y estrellas; luego los más cercanos. */
    fun rankHotels(list: List<Spot>): List<Spot> = dedupe(list.sortedWith(compareByDescending<Spot> { s ->
        (if (s.phone.isNotBlank()) 3 else 0) + (if (s.website.isNotBlank()) 2 else 0) + (if (s.address.isNotBlank()) 1 else 0) +
            s.stars - s.distanceM / 2000
    }.thenBy { it.distanceM }))

    /** Atractivos: los más conocidos (con sitio web) y los más cercanos. */
    fun rankAttractions(list: List<Spot>): List<Spot> = dedupe(list.sortedWith(compareByDescending<Spot> { s ->
        (if (s.website.isNotBlank()) 2 else 0) + (if (s.type == "museo" || s.type == "mirador" || s.type == "playa") 1 else 0) - s.distanceM / 3000
    }.thenBy { it.distanceM }))

    fun hotelBlock(spots: List<Spot>, area: String, photoNumbers: List<Int?>): String {
        if (spots.isEmpty()) return ""
        val city = area.substringBefore(',').trim()
        val sb = StringBuilder("🏨 Alojamientos en ").append(area).append(" (del mapa):\n")
        spots.forEachIndexed { i, s ->
            sb.append("\n").append(i + 1).append(". ").append(s.name).append(" — ").append(s.type)
            if (s.stars > 0) sb.append(" ").append("⭐".repeat(s.stars.coerceAtMost(5)))
            sb.append(" · ").append(distanceLabel(s.distanceM)).append('\n')
            sb.append("   📍 ").append(s.address.ifBlank { "Dirección: toca el mapa" }).append('\n')
            if (s.phone.isNotBlank()) sb.append("   📞 ").append(s.phone).append('\n')
            if (s.website.isNotBlank()) sb.append("   🌐 ").append(s.website).append('\n')
            sb.append("   💲 Precio por noche, disponibilidad y valoraciones: https://www.google.com/travel/hotels?q=")
                .append(URLEncoder.encode("${s.name} $city", "UTF-8")).append('\n')
            sb.append("   🗺️ ").append(s.mapsUrl).append('\n')
            photoNumbers.getOrNull(i)?.let { sb.append("   🖼️ Foto ").append(it).append(" (abajo)\n") }
        }
        return sb.toString().trimEnd()
    }

    fun attractionsBlock(spots: List<Spot>, area: String, photoNumbers: List<Int?>): String {
        if (spots.isEmpty()) return ""
        val sb = StringBuilder("🏞️ Atractivos en ").append(area).append(":\n")
        spots.forEachIndexed { i, s ->
            sb.append("\n").append(i + 1).append(". ").append(s.name).append(" — ").append(s.type).append(" · ").append(distanceLabel(s.distanceM)).append('\n')
            if (s.hours.isNotBlank()) sb.append("   🕒 ").append(s.hours).append('\n')
            if (s.website.isNotBlank()) sb.append("   🌐 ").append(s.website).append('\n')
            sb.append("   🗺️ ").append(s.mapsUrl).append('\n')
            photoNumbers.getOrNull(i)?.let { sb.append("   🖼️ Foto ").append(it).append(" (abajo)\n") }
        }
        return sb.toString().trimEnd()
    }

    /** ¿Pide atractivos de un lugar concreto o cerca de él? («qué hacer en Valdivia», «lugares turísticos cerca») */
    fun wantsLocalAttractions(question: String): Boolean =
        Regex("(?iu)\\b(en|de|cerca|por)\\b").containsMatchIn(question) &&
            Regex("(?iu)tur[ií]stic|atractiv|qu[eé] (hacer|visitar|conocer)|lugares|paseos?|panoramas?|cerca|museos?|miradores?|playas?|parques?").containsMatchIn(question)

    // =====================================================================
    // 🚌 BUSES URBANOS E INTERURBANOS
    // =====================================================================
    /** «recorrido de la micro 210», «línea D18», «bus 405» → «210», «D18», «405» */
    fun lineRef(question: String): String? =
        Regex("(?iu)\\b(?:micros?|buses?|bus|l[ií]neas?|recorridos?|troles?|trolebuses?|liebres?|colectivos?|n[uú]mero|n[°º])\\s*(?:de la |del |la |el |n[°º]\\s*)?(?:micro\\s+|bus\\s+|l[ií]nea\\s+)?([a-z]?\\d{1,3}[a-z]?)\\b")
            .find(question)?.groupValues?.get(1)?.uppercase()

    /** «cómo llego a la Plaza de Armas en micro» → «la Plaza de Armas» */
    fun destination(question: String): String? =
        Regex("(?iu)\\b(?:c[oó]mo (?:llego|llegar|me voy|ir|voy)|ir|llegar|viajar)\\s+(?:a|al|hasta|hacia)\\s+([\\p{L}0-9][\\p{L}0-9 .'°-]{2,50})")
            .find(question)?.groupValues?.get(1)?.trim()?.trimEnd('?', '.', '!', ',')
            ?.split(Regex("(?iu)\\s+(en|desde|por|con|y|que|hoy|ma[ñn]ana)\\b"))?.first()?.trim()?.takeIf { it.length >= 3 }

    fun lineQuery(ref: String, lat: Double, lon: Double): String =
        "[out:json][timeout:25];rel[\"route\"~\"^(bus|trolleybus|share_taxi|minibus)$\"][\"ref\"~\"^${ref.filter { it.isLetterOrDigit() }}$\",i]" +
            "${around(40000, lat, lon)}->.r;.r out body;node(r.r);out tags;"

    fun nearbyTransitQuery(lat: Double, lon: Double): String =
        "[out:json][timeout:25];(node[\"highway\"=\"bus_stop\"]${around(450, lat, lon)};node[\"public_transport\"=\"platform\"][\"bus\"=\"yes\"]" +
            "${around(450, lat, lon)};);out tags;rel[\"route\"~\"^(bus|trolleybus|share_taxi|minibus)$\"]${around(500, lat, lon)};out tags;"

    private fun lineFrom(t: org.json.JSONObject, stops: List<String>) = Line(
        ref = t.optString("ref"), name = t.optString("name"), from = t.optString("from"), to = t.optString("to"),
        operator = t.optString("operator").ifBlank { t.optString("network") },
        interval = t.optString("interval").let { iv -> when {
            iv.isBlank() -> ""
            Regex("^\\d{1,3}$").matches(iv) -> "cada $iv min"
            Regex("^\\d{1,2}:\\d{2}$").matches(iv) -> "cada " + iv.substringBefore(':').toInt().let { h -> if (h > 0) "$h h " else "" } + iv.substringAfter(':').toInt() + " min"
            else -> iv
        } },
        hours = FoodFinder.hoursEs(t.optString("opening_hours")), fare = t.optString("charge"), stops = stops
    )

    /** La línea pedida, con sus paraderos en el orden del recorrido (primer sentido que trae el mapa). */
    fun parseLine(json: String, ref: String): Line? {
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return null
        val names = HashMap<Long, String>()
        val rels = mutableListOf<org.json.JSONObject>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "node" -> e.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotBlank() }?.let { names[e.optLong("id")] = it }
                "relation" -> rels += e
            }
        }
        val rel = rels.firstOrNull { it.optJSONObject("tags")?.optString("ref").equals(ref, ignoreCase = true) } ?: rels.firstOrNull() ?: return null
        val stops = mutableListOf<String>()
        rel.optJSONArray("members")?.let { m ->
            for (k in 0 until m.length()) {
                val mem = m.optJSONObject(k) ?: continue
                if (mem.optString("type") != "node") continue
                val role = mem.optString("role")
                if (!role.startsWith("platform") && !role.startsWith("stop")) continue
                val n = names[mem.optLong("ref")] ?: continue
                if (stops.lastOrNull() != n) stops += n
            }
        }
        // Si el mapa marca parada y andén por separado, el mismo nombre sale dos veces: se deja una
        val ordered = stops.distinct()
        return lineFrom(rel.optJSONObject("tags") ?: org.json.JSONObject("{}"), ordered.take(30))
    }

    /** Paraderos cercanos (con distancia) y líneas que pasan cerca. */
    fun parseNearby(json: String, lat: Double, lon: Double): Pair<List<Spot>, List<Line>> {
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return emptyList<Spot>() to emptyList()
        val stops = mutableListOf<Spot>()
        val lines = mutableListOf<Line>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val t = e.optJSONObject("tags") ?: continue
            when (e.optString("type")) {
                "node" -> {
                    val name = listOf(t.optString("name"), t.optString("ref")).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    if (name.isBlank()) continue
                    val d = FoodFinder.distance(lat, lon, e.optDouble("lat"), e.optDouble("lon"))
                    stops += Spot(name, "paradero", 0, "", "", "", "", e.optDouble("lat"), e.optDouble("lon"), d)
                }
                "relation" -> if (t.optString("ref").isNotBlank() || t.optString("name").isNotBlank()) lines += lineFrom(t, emptyList())
            }
        }
        val seenS = HashSet<String>()
        val seenL = HashSet<String>()
        return stops.sortedBy { it.distanceM }.filter { seenS.add(it.name) }.take(4) to
            lines.filter { seenL.add(it.ref.ifBlank { it.name }) }
                .sortedWith(compareBy({ Regex("\\d+").find(it.ref)?.value?.toIntOrNull() ?: 9999 }, { it.ref })).take(15)
    }

    /** Bloque con la línea pedida, los paraderos y líneas cercanas, y cómo llegar. */
    fun transitBlock(line: Line?, ref: String?, stops: List<Spot>, lines: List<Line>, area: String, dest: String?): String {
        val city = area.substringBefore(',').trim()
        val sb = StringBuilder()
        if (line != null) {
            sb.append("🚌 Recorrido ").append(line.ref.ifBlank { ref ?: "" })
            if (line.operator.isNotBlank()) sb.append(" (").append(line.operator).append(")")
            sb.append(":\n")
            val fromTo = if (line.from.isNotBlank() && line.to.isNotBlank()) "${line.from} → ${line.to}" else line.name
            if (fromTo.isNotBlank()) sb.append("   🛣️ ").append(fromTo).append('\n')
            if (line.interval.isNotBlank()) sb.append("   ⏱️ Frecuencia: ").append(line.interval).append('\n')
            if (line.hours.isNotBlank()) sb.append("   🕒 Horario: ").append(line.hours).append('\n')
            if (line.fare.isNotBlank()) sb.append("   💲 Tarifa: ").append(line.fare).append('\n')
            if (line.stops.isNotEmpty()) sb.append("   🚏 Paraderos en orden (").append(line.stops.size).append("): ")
                .append(line.stops.joinToString(" → ")).append('\n')
            sb.append("   🗺️ Ver el recorrido en el mapa: https://www.google.com/maps/search/")
                .append(URLEncoder.encode("recorrido ${line.ref.ifBlank { ref ?: "" }} $city", "UTF-8")).append('\n')
        } else if (ref != null) {
            sb.append("🚌 La línea ").append(ref).append(" no está en el mapa abierto cerca de ").append(area).append(".\n")
                .append("   🗺️ Búscala en Google Maps: https://www.google.com/maps/search/")
                .append(URLEncoder.encode("recorrido $ref $city", "UTF-8")).append('\n')
        }
        if (stops.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("🚏 Paraderos más cercanos a ti:\n")
            stops.forEach { s -> sb.append("   • ").append(s.name).append(" — ").append(distanceLabel(s.distanceM)).append(" · 🗺️ ").append(s.mapsUrl).append('\n') }
        }
        if (lines.isNotEmpty() && line == null) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("🚌 Líneas que pasan cerca:\n")
            lines.forEach { l ->
                val fromTo = if (l.from.isNotBlank() && l.to.isNotBlank()) "${l.from} → ${l.to}" else l.name
                sb.append("   • ").append(l.ref.ifBlank { "—" }).append(if (fromTo.isNotBlank()) ": $fromTo" else "")
                    .append(if (l.interval.isNotBlank()) " (${l.interval})" else "").append('\n')
            }
        }
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("🧭 Cómo llegar en transporte público (rutas, horarios y tiempo real): https://www.google.com/maps/dir/?api=1")
        if (dest != null) sb.append("&destination=").append(URLEncoder.encode("$dest, $city", "UTF-8"))
        sb.append("&travelmode=transit")
        return sb.toString().trimEnd()
    }

    // =====================================================================
    // 🌦️ TEMPORADA ACTUAL (para recomendar vacaciones según la época)
    // =====================================================================
    private val southern = setOf("CL", "AR", "UY", "PY", "BO", "PE", "BR", "ZA", "AU", "NZ")

    /** «primavera (hemisferio sur)» según el mes y el país del teléfono */
    fun season(countryCode: String, month: Int /* 0 = enero */): String {
        val south = countryCode.uppercase() in southern
        val north = when (month) { 11, 0, 1 -> "invierno"; 2, 3, 4 -> "primavera"; 5, 6, 7 -> "verano"; else -> "otoño" }
        val s = if (!south) north else when (north) { "invierno" -> "verano"; "verano" -> "invierno"; "primavera" -> "otoño"; else -> "primavera" }
        return "$s (hemisferio ${if (south) "sur" else "norte"})"
    }
}

