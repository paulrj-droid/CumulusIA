package com.paulrj.cumulusia.ai

import java.net.URLEncoder

/**
 * 🎟️ CINES CERCA DEL USUARIO (para «¿qué películas hay en cartelera?», «cine en Talcahuano»…)
 * La IA arma la cartelera con lo que encuentra en internet y la app agrega:
 *  - el afiche y el tráiler de cada película (ya existía para Cine/Películas),
 *  - los cines reales más cercanos (OpenStreetMap): nombre, 📍 dirección, 📞 teléfono, distancia, mapa,
 *  - y el enlace a la página del cine para ver la cartelera, los horarios y los precios.
 * Los enlaces son de la ficha del cine en el mapa o de la página oficial de la cadena (nunca inventados).
 */
object CinemaFinder {

    fun overpassQuery(lat: Double, lon: Double, radiusM: Int): String {
        val la = "%.6f".format(java.util.Locale.US, lat)
        val lo = "%.6f".format(java.util.Locale.US, lon)
        return "[out:json][timeout:20];(nwr[\"amenity\"=\"cinema\"](around:$radiusM,$la,$lo););out center tags 80;"
    }

    /** Página oficial de las cadenas más comunes, por país (solo las que existen de verdad). */
    private val CHAINS: List<Pair<Regex, Map<String, String>>> = listOf(
        Regex("(?i)cinemark") to mapOf("CL" to "https://www.cinemark.cl", "AR" to "https://www.cinemarkhoyts.com.ar",
            "PE" to "https://www.cinemark-peru.com", "CO" to "https://www.cinemark.com.co", "BR" to "https://www.cinemark.com.br",
            "US" to "https://www.cinemark.com"),
        Regex("(?i)cineplanet") to mapOf("CL" to "https://www.cineplanet.cl", "PE" to "https://www.cineplanet.com.pe"),
        Regex("(?i)cin[eé]polis") to mapOf("MX" to "https://cinepolis.com", "CL" to "https://cinepolischile.cl",
            "AR" to "https://www.cinepolis.com.ar", "CO" to "https://cinepolis.com.co"),
        Regex("(?i)hoyts") to mapOf("CL" to "https://cinehoyts.cl", "AR" to "https://www.cinemarkhoyts.com.ar"),
        Regex("(?i)cinemex") to mapOf("MX" to "https://cinemex.com"),
        Regex("(?i)muvix") to mapOf("CL" to "https://www.muvix.cl")
    )

    /** Enlace para ver la cartelera de ESE cine: su página en el mapa, la de su cadena o una búsqueda de su cartelera. */
    fun pageFor(place: FoodFinder.Place, country: String, city: String): String {
        if (place.website.isNotBlank()) return place.website
        CHAINS.firstOrNull { it.first.containsMatchIn(place.name) }?.second?.get(country.uppercase())?.let { return it }
        return "https://www.google.com/search?q=" + URLEncoder.encode("cartelera ${place.name} $city", "UTF-8")
    }

    /** Bloque «🎟️ Cines cerca de ti» que se agrega debajo de la cartelera. */
    fun block(cinemas: List<FoodFinder.Place>, areaLabel: String, country: String): String {
        if (cinemas.isEmpty()) return ""
        val city = areaLabel.substringBefore(',').trim()
        val sb = StringBuilder("🎟️ Cines cerca de ").append(areaLabel).append(":\n")
        cinemas.forEachIndexed { i, c ->
            sb.append("\n").append(i + 1).append(". ").append(c.name).append(" — ")
                .append(if (c.distanceM < 1000) "a ${c.distanceM} m" else "a " + "%.1f".format(java.util.Locale("es"), c.distanceM / 1000.0) + " km")
                .append('\n')
            sb.append("   📍 ").append(c.address.ifBlank { "Dirección: toca el mapa" }).append('\n')
            if (c.phone.isNotBlank()) sb.append("   📞 ").append(c.phone).append('\n')
            if (c.hours.isNotBlank()) sb.append("   🕒 ").append(c.hours).append('\n')
            sb.append("   🌐 Cartelera, horarios y precios: ").append(pageFor(c, country, city)).append('\n')
            sb.append("   🗺️ ").append(c.mapsUrl).append('\n')
        }
        sb.append("\n💲 El precio de la entrada cambia según el día, el formato (2D, 3D, 4DX) y la sala: lo ves al tocar 🌐 en cada cine.")
        return sb.toString().trimEnd()
    }

    /** Lugar del pedido: «cartelera en Talcahuano» o «Cinemark Talcahuano» → «Talcahuano» */
    fun placeIn(question: String): String? = FoodFinder.placeIn(question)
        ?: Regex("(?iu)\\b(?:cinemark|cineplanet|cin[eé]polis|cine ?hoyts|hoyts|muvix|cinemex)\\s+(?:de\\s+|del\\s+)?((?:mall\\s+)?[\\p{L}][\\p{L} .'-]{2,40})")
            .find(question)?.groupValues?.get(1)?.trim()?.trimEnd('?', '.', '!', ',')
            ?.split(Regex("(?iu)\\s+(hoy|ma[ñn]ana|esta|este|que|con|y|para|a las)\\b"))?.first()?.trim()?.takeIf { it.length >= 3 }

    /** Más cines en el mapa (si no apareció ninguno cerca). */
    fun moreUrl(areaLabel: String): String =
        "https://www.google.com/maps/search/" + URLEncoder.encode("cines cerca de $areaLabel", "UTF-8")
}

