package com.paulrj.cumulusia.ai

import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🍽️ LOCALES PARA COMER Y DELIVERY CERCA DEL USUARIO
 * Cuando pides algo para comer («quiero sushi», «tengo hambre», «delivery de pizza en Ñuñoa»…), la APP busca
 * los locales reales en el mapa (OpenStreetMap, gratis y sin claves) y arma la respuesta con:
 *  nombre, tipo de comida, 📍 dirección, 📞 teléfono (se toca para llamar), 🕒 horario, 🛵 delivery, sitio web,
 *  la ubicación en Google Maps y una 🖼️ foto de su carta o de lo que vende (la busca la app).
 * La IA no reescribe estos datos: así nunca inventa un teléfono ni una dirección.
 */
object FoodFinder {

    class Request(
        /** Lo que pidió, para mostrar: «sushi», «pizza», «comida» */
        val what: String,
        /** Tipos de cocina en OpenStreetMap (cuisine=…): «sushi|japanese» */
        val cuisineRx: String?,
        /** Palabra que suele estar en el nombre del local: «sushi», «pizz» */
        val nameRx: String?,
        val emoji: String,
        /** Solo comida rápida */
        val fastFood: Boolean,
        /** Lugar que nombró («Providencia», «Plaza de Armas»); null = cerca del teléfono */
        val place: String?,
        /** Pidió delivery: primero los locales que reparten */
        val delivery: Boolean
    )

    class Place(
        val name: String,
        val kind: String,
        val cuisine: String,
        val phone: String,
        var address: String,
        val hours: String,
        val website: String,
        val delivery: Boolean?,
        val lat: Double,
        val lon: Double,
        val distanceM: Int
    ) {
        val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=" +
            URLEncoder.encode("$name $lat,$lon", "UTF-8")
    }

    private fun rx(s: String) = Regex("(?iu)$s")

    /** Palabra que pides → (para mostrar, cocina en OpenStreetMap, palabra del nombre, emoji, solo comida rápida) */
    private class Cuisine(val match: Regex, val label: String, val osm: String?, val name: String?, val emoji: String, val fast: Boolean = false)

    private val CUISINES = listOf(
        Cuisine(rx("sushi|japones[ae]?|ramen"), "sushi", "sushi|japanese|ramen", "sushi|japo|ramen", "🍣"),
        Cuisine(rx("pizz"), "pizza", "pizza", "pizz", "🍕"),
        Cuisine(rx("hamburgues|burger"), "hamburguesas", "burger", "burger|hamburg", "🍔"),
        Cuisine(rx("completos?\\b|hot ?dogs?|sangu|s[aá]ndwich|churrasco|barros"), "completos y sándwiches",
            "hot_dog|sandwich|chilean", "complet|sangu|sandw|fuente|churras", "🌭"),
        Cuisine(rx("empanad"), "empanadas", "empanada|chilean", "empanad", "🥟"),
        Cuisine(rx("\\bpollo"), "pollo", "chicken", "pollo|chicken", "🍗"),
        Cuisine(rx("chin[ao]\\b|chifa|wok"), "comida china", "chinese", "chin|chifa|wok", "🥡"),
        Cuisine(rx("peruan|ceviche|cebiche"), "comida peruana", "peruvian|ceviche", "peru|cevich", "🐟"),
        Cuisine(rx("mexican|tacos?\\b|burrit"), "comida mexicana", "mexican|tex-mex", "mexic|taco", "🌮"),
        Cuisine(rx("italian|pastas?\\b"), "comida italiana", "italian|pasta", "trattor|pasta|italia", "🍝"),
        Cuisine(rx("[aá]rabe|shawarma|kebab|falafel"), "comida árabe", "arab|lebanese|kebab|shawarma|middle_eastern|turkish", "shawarm|kebab|arab", "🥙"),
        Cuisine(rx("\\bindia\\b|hind[uú]|curry"), "comida india", "indian", "india|curry", "🍛"),
        Cuisine(rx("corean"), "comida coreana", "korean", "korea|corea", "🍜"),
        Cuisine(rx("tailand|thai"), "comida tailandesa", "thai", "thai", "🍜"),
        Cuisine(rx("vegan|vegetarian"), "comida vegetariana", "vegan|vegetarian", "vegan|veggie|vegetar", "🥗"),
        Cuisine(rx("saludable|ensalada"), "comida saludable", "salad|healthy|vegetarian|vegan", "salad|ensalad|healthy|green", "🥗"),
        Cuisine(rx("mariscos?|pescados?\\b"), "mariscos y pescados", "seafood|fish", "marisc|pesc|caleta", "🦐"),
        Cuisine(rx("parrill|asados?\\b|carnes?\\b|steak"), "parrilladas y carnes", "steak_house|grill|barbecue|argentinian|brazilian", "parrill|asado|grill|carne", "🥩"),
        Cuisine(rx("caf[eé]\\b|cafeter"), "cafés", "coffee_shop|cafe", "caf[eé]|coffee", "☕"),
        Cuisine(rx("helad"), "heladerías", "ice_cream", "helad|gelat", "🍦"),
        Cuisine(rx("postre|pastel|torta|dulce|kuchen|donas?\\b|waffle"), "postres y dulces", "dessert|cake|pastry|donut|crepe|ice_cream", "pastel|dulce|torta|postre|kuchen", "🍰"),
        Cuisine(rx("desayun|brunch"), "desayunos", "breakfast|coffee_shop|brunch", "brunch|desayun|caf[eé]", "🥐"),
        Cuisine(rx("comida r[aá]pida|fast ?food"), "comida rápida", null, null, "🍟", fast = true),
        Cuisine(rx("chilen|casera|picada|cazuela|porotos|pastel de choclo"), "comida chilena y casera", "chilean|regional|local", "picada|chilen|criollo|fuente", "🍲")
    )

    private val foodWords = rx("\\bcomer\\b|comida|hambre|antojo|restaurant|restor[aá]n|restaurante|\\blocal(es)?\\b|delivery|domicilio|\\bpedir\\b|" +
        "almorzar|almuerzo|\\bcenar\\b|\\bcena\\b|desayunar|\\bmen[uú]\\b|\\bcarta\\b|picada|para llevar")
    /** Cocinar en casa, recetas, nutrición o datos sobre una comida: no es buscar un local. */
    private val notAPlace = rx("receta|cocin|prepar|c[oó]mo (se )?hace|ingrediente|calor[ií]a|nutri|dieta|prote[ií]na|historia|origen|" +
        "qui[eé]n invent|significa|en ingl[eé]s|c[oó]mo se dice|youtube|\\bvideos?\\b|ideas? (de|para)|escr[ií]be|poema|chiste|" +
        // 🍳 recetas en otros idiomas (van a las páginas de recetas del país, no a locales)
        "recipe|how to (cook|make)|receita|recette|rezept|ricett|рецепт|食谱|菜谱|做法|레시피|만드는 법|レシピ|作り方")
    /** «¿Qué puedo comer?» (ideas por categoría, ya existía) salvo que diga pedir, delivery, local, dónde… */
    private val homeIdeas = rx("qu[eé] (puedo|podr[ií]a|deber[ií]a) (comer|almorzar|cenar|desayunar)|qu[eé] (como|almuerzo|ceno|desayuno)( hoy)?\\b|" +
        "recomi[eé]nda(me)? (algo de |qu[eé] )?(comer|comida)")
    private val wantsPlace = rx("pedir|delivery|domicilio|\\blocal|restaurant|restor|d[oó]nde|cerca|picada|\\bcarta\\b|\\bmen[uú]\\b|para llevar")

    /** ¿Pide algo para comer en un local o por delivery? null = no (sigue el camino normal). */
    fun parse(prompt: String): Request? {
        val p = prompt.lowercase().trim()
        if (p.length < 3 || notAPlace.containsMatchIn(p)) return null
        val cuisine = CUISINES.firstOrNull { it.match.containsMatchIn(p) }
        val cat = WebSearchRules.detect(prompt)
        val isFood = cat == WebSearchRules.COMIDA || cat == WebSearchRules.DELIVERY || foodWords.containsMatchIn(p)
        if (!isFood && cuisine == null) return null
        // «sushi» suelto sí; «una pizza de juguete»… raro. «café» suelto solo si pide local
        if (cuisine == null && !foodWords.containsMatchIn(p)) return null
        if (homeIdeas.containsMatchIn(p) && !wantsPlace.containsMatchIn(p)) return null
        val delivery = rx("delivery|domicilio|pedir|despacho|reparto|me lo traigan|que me traigan").containsMatchIn(p)
        return Request(
            what = cuisine?.label ?: "comida",
            cuisineRx = cuisine?.osm,
            nameRx = cuisine?.name,
            emoji = cuisine?.emoji ?: if (delivery) "🛵" else "🍽️",
            fastFood = cuisine?.fast == true,
            place = placeIn(prompt),
            delivery = delivery
        )
    }

    /** «sushi en Providencia», «pizza cerca de Plaza de Armas», «delivery por Ñuñoa» → el lugar */
    fun placeIn(prompt: String): String? {
        // (?=…) revisa cada «en / cerca de…» por separado: «hoteles en verano en La Serena» → «La Serena»
        val m = Regex("(?iu)(?=\\b(?:en|cerca de|cerca del|por|en la comuna de|en el sector de|alrededor de)\\s+((?:el |la |los |las )?[\\p{L}0-9][\\p{L}0-9.'° -]{2,50}))")
            .findAll(prompt).map { it.groupValues[1].trim().trimEnd('?', '.', '!', ',') }
            .firstOrNull { cand ->
                val c = cand.lowercase()
                !Regex("^(mi |la |el )?(casa|delivery|domicilio|l[ií]nea|internet|la app|este momento|la noche|la tarde|la mañana|" +
                    "hoy|mañana|un rato|la semana|el día|oferta|promoci[oó]n|efectivo|tarjeta|moto|bici|auto|mi ubicaci[oó]n|mi zona|mi sector|" +
                    "m[ií]|aqu[ií]|ac[aá]|el barrio|mi barrio|cartelera|el cine|los cines|cines?|salas?|3d|2d|imax|4dx|estreno|youtube|verano|invierno|oto[ñn]o|primavera|vacaciones|familia|pareja|temporada|arriendos?|alquiler|venta|renta|d[ií]as?|noches?|semanas?|meses|mensual|cuotas|usad[oa]s?|semana santa|fiestas patrias|navidad|a[ñn]o nuevo|fin de semana|luna de miel)\\b").containsMatchIn(c) &&
                    CUISINES.none { it.match.matches(c) } && !foodWords.matches(c) && !isTransport(cand)
            } ?: return null
        // Se quita lo que viene después del lugar («Providencia que tenga delivery» → «Providencia»)
        return m.split(Regex("(?iu)\\s+(que|con|para|y|donde|abierto|abiertos|barato|baratos|por favor)\\b")).first().trim().takeIf { it.length >= 3 }
    }

    /** «en micro», «en metro hasta el centro» = medio de transporte (no es un lugar); «cerca del metro Baquedano» sí es un lugar. */
    private val transportRx = Regex("(?iu)^(?:(?:el|la|los|las)\\s+)?(?:micros?|bus(?:es)?|metro|colectivos?|biotr[eé]n|tren|trolebuses?|taxi|uber|liebres?)(?:\\s+(.*))?$")
    private fun isTransport(cand: String): Boolean {
        val m = transportRx.find(cand.trim()) ?: return false
        val rest = m.groupValues[1].trim()
        return rest.isEmpty() || !rest.first().isUpperCase()
    }

    /** Consulta a OpenStreetMap (Overpass): locales de comida alrededor del punto. */
    fun overpassQuery(lat: Double, lon: Double, radiusM: Int, req: Request): String {
        val amen = "restaurant|fast_food|cafe|ice_cream|food_court"
        val around = "(around:$radiusM,${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lon)})"
        val body = when {
            req.fastFood -> "nwr[\"amenity\"=\"fast_food\"]$around;"
            req.cuisineRx != null -> "nwr[\"amenity\"~\"$amen\"][\"cuisine\"~\"${req.cuisineRx}\",i]$around;" +
                (req.nameRx?.let { "nwr[\"amenity\"~\"$amen\"][\"name\"~\"$it\",i]$around;" } ?: "")
            else -> "nwr[\"amenity\"~\"restaurant|fast_food|food_court\"]$around;"
        }
        return "[out:json][timeout:20];($body);out center tags 150;"
    }

    fun overpassUrl(query: String): String = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")

    private val kindEs = mapOf("restaurant" to "restaurante", "fast_food" to "comida rápida", "cafe" to "café",
        "ice_cream" to "heladería", "food_court" to "patio de comidas")

    private val cuisineEs = mapOf("sushi" to "sushi", "japanese" to "japonesa", "pizza" to "pizza", "burger" to "hamburguesas",
        "chicken" to "pollo", "chinese" to "china", "peruvian" to "peruana", "mexican" to "mexicana", "italian" to "italiana",
        "chilean" to "chilena", "regional" to "casera", "sandwich" to "sándwiches", "hot_dog" to "completos", "coffee_shop" to "café",
        "ice_cream" to "helados", "seafood" to "mariscos", "fish" to "pescados", "steak_house" to "carnes", "grill" to "parrilla",
        "barbecue" to "parrilla", "argentinian" to "argentina", "brazilian" to "brasileña", "arab" to "árabe", "lebanese" to "libanesa",
        "kebab" to "kebab", "indian" to "india", "korean" to "coreana", "thai" to "tailandesa", "vegan" to "vegana",
        "vegetarian" to "vegetariana", "salad" to "ensaladas", "dessert" to "postres", "cake" to "tortas", "pastry" to "pastelería",
        "breakfast" to "desayunos", "empanada" to "empanadas", "ramen" to "ramen", "donut" to "donas", "crepe" to "crepes",
        "pasta" to "pastas", "tex-mex" to "tex-mex", "middle_eastern" to "árabe", "turkish" to "turca", "ceviche" to "ceviche")

    /** Convierte la respuesta de OpenStreetMap en locales (sin nombre no sirven). Requiere org.json. */
    fun parseOverpass(json: String, lat: Double, lon: Double): List<Place> {
        val out = mutableListOf<Place>()
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name").ifBlank { t.optString("brand") }.trim()
            if (name.isBlank()) continue
            val plat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val plon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val phone = listOf("phone", "contact:phone", "contact:mobile", "contact:whatsapp", "mobile")
                .map { t.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                .split(';', ',').first().trim()
            val street = t.optString("addr:street")
            val number = t.optString("addr:housenumber")
            val area = t.optString("addr:suburb").ifBlank { t.optString("addr:district") }.ifBlank { t.optString("addr:city") }
            val address = listOf(listOf(street, number).filter { it.isNotBlank() }.joinToString(" "), area)
                .filter { it.isNotBlank() }.joinToString(", ")
            val web = listOf("website", "contact:website", "url", "contact:instagram", "contact:facebook")
                .map { t.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                .let { w -> when {
                    w.isBlank() -> ""
                    w.startsWith("http") -> w
                    w.startsWith("@") -> "https://instagram.com/" + w.removePrefix("@")
                    w.contains('.') -> "https://$w"
                    else -> ""
                } }
            val delivery = when (t.optString("delivery").lowercase()) { "yes", "only" -> true; "no" -> false; else -> null }
            val cuisine = t.optString("cuisine").split(';').mapNotNull { c -> cuisineEs[c.trim().lowercase()] ?: c.trim().takeIf { it.isNotBlank() } }
                .distinct().take(3).joinToString(", ")
            out += Place(name, kindEs[t.optString("amenity")] ?: "local", cuisine, phone, address, hoursEs(t.optString("opening_hours")),
                web, delivery, plat, plon, distance(lat, lon, plat, plon))
        }
        return out
    }

    /** Primero los que tienen teléfono y dirección (lo que pediste), después los más cercanos. Sin repetir cadenas. */
    fun rank(list: List<Place>, req: Request): List<Place> {
        val seen = HashSet<String>()
        return list.sortedWith(compareByDescending<Place> { p ->
            (if (p.phone.isNotBlank()) 4 else 0) + (if (p.address.isNotBlank()) 2 else 0) + (if (p.hours.isNotBlank()) 1 else 0) +
                (if (p.website.isNotBlank()) 1 else 0) + (if (req.delivery && p.delivery == true) 3 else 0) - p.distanceM / 1500
        }.thenBy { it.distanceM }).filter { seen.add(it.name.lowercase().replace(Regex("[^\\p{L}0-9]"), "")) }
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return (r * 2 * atan2(sqrt(a), sqrt(1 - a))).roundToInt()
    }

    private fun distanceLabel(m: Int) = if (m < 1000) "a $m m" else "a " + "%.1f".format(java.util.Locale("es"), m / 1000.0) + " km"

    /** «Mo-Fr 12:00-23:00; Sa,Su 13:00-22:00» → «Lu-Vi 12:00-23:00; Sá,Do 13:00-22:00» */
    fun hoursEs(h: String): String {
        if (h.isBlank()) return ""
        if (h.trim() == "24/7") return "abierto las 24 horas, todos los días"
        var s = h
        listOf("Mo" to "Lu", "Tu" to "Ma", "We" to "Mi", "Th" to "Ju", "Fr" to "Vi", "Sa" to "Sá", "Su" to "Do", "PH" to "festivos")
            .forEach { (en, es) -> s = s.replace(Regex("\\b$en\\b"), es) }
        return s.replace("off", "cerrado").replace(";", " ·").take(120)
    }

    /**
     * Respuesta lista para mostrar (la arma la app, con los datos exactos del mapa).
     * @param photoNumbers número de foto de cada local (null = sin foto), en el orden de las fotos debajo de la respuesta
     */
    fun answer(req: Request, places: List<Place>, areaLabel: String, photoNumbers: List<Int?>, moreUrl: String): String {
        val sb = StringBuilder()
        val title = if (req.what == "comida") "Locales para comer" else req.what.replaceFirstChar { it.titlecase() }
        sb.append(req.emoji).append(' ').append(title).append(if (req.delivery) " con delivery" else "").append(" cerca de ")
            .append(areaLabel).append(":\n")
        places.forEachIndexed { i, p ->
            sb.append("\n").append(i + 1).append(". ").append(p.name)
            val kind = listOf(p.cuisine.ifBlank { p.kind }, distanceLabel(p.distanceM)).filter { it.isNotBlank() }
            sb.append(" — ").append(kind.joinToString(" · ")).append('\n')
            sb.append("   📍 ").append(p.address.ifBlank { "Dirección: toca el mapa" }).append('\n')
            if (p.phone.isNotBlank()) sb.append("   📞 ").append(p.phone).append('\n')
            if (p.hours.isNotBlank()) sb.append("   🕒 ").append(p.hours).append('\n')
            when (p.delivery) {
                true -> sb.append("   🛵 Tiene delivery\n")
                false -> sb.append("   🛵 Sin delivery (retiro o consumo en el local)\n")
                null -> Unit
            }
            if (p.website.isNotBlank()) sb.append("   🌐 ").append(p.website).append('\n')
            sb.append("   🗺️ ").append(p.mapsUrl).append('\n')
            photoNumbers.getOrNull(i)?.let { sb.append("   🖼️ Foto ").append(it).append(" (arriba): su carta o lo que vende\n") }
        }
        sb.append("\n🔎 Ver más locales en el mapa: ").append(moreUrl)
        if (places.none { it.phone.isNotBlank() })
            sb.append("\n\nEstos locales no tienen su teléfono publicado en el mapa: toca 🗺️ para verlo en Google Maps.")
        return sb.toString().trimEnd()
    }

    /** Búsqueda en Google Maps para «ver más» (abre la app de mapas). */
    fun moreUrl(req: Request, areaLabel: String): String =
        "https://www.google.com/maps/search/" + URLEncoder.encode(
            (if (req.what == "comida") "restaurantes" else req.what) + (if (req.delivery) " delivery" else "") + " cerca de " + areaLabel, "UTF-8")
}
