package com.paulrj.cumulusia.ai

import java.net.URLEncoder

/**
 * ⚕️ MEDICINA CON FUENTES CONFIABLES (lo agrega la app debajo de la respuesta de la IA)
 *  📚 Resumen de MedlinePlus en español (Biblioteca Nacional de Medicina de EE. UU., gratis y sin claves) con su enlace.
 *  💊 Si preguntas por un medicamento: su ficha en MedlinePlus y, en Chile, en el ISP.
 *  🏛️ Enlaces a la OMS y al Ministerio de Salud de tu país.
 *  💊 Farmacias de turno (Chile, datos oficiales del MINSAL) o farmacias, hospitales y urgencias cerca (mapa).
 *  🚨 El número de emergencias de tu país.
 * La app pone al inicio el aviso de que es solo informativa y no reemplaza la consulta con un profesional de la salud.
 */
object HealthFinder {

    const val TOP_NOTE = "⚕️ Información solo informativa: no reemplaza la consulta con un profesional de la salud."

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun google(q: String) = "https://www.google.com/search?q=" + e(q)

    // =====================================================================
    // 💊 MEDICAMENTOS
    // =====================================================================
    /** Medicamentos comunes en Latinoamérica y España (genéricos y algunos nombres comerciales muy usados). */
    val DRUGS = listOf(
        "paracetamol", "acetaminof[eé]n", "ibuprofeno", "amoxicilina", "omeprazol", "esomeprazol", "pantoprazol", "losart[aá]n", "metformina",
        "aspirina", "[aá]cido acetilsalic[ií]lico", "diclofenaco", "naproxeno", "ketoprofeno", "ketorolaco", "metamizol", "dipirona",
        "nimesulida", "meloxicam", "celecoxib", "etoricoxib", "loratadina", "desloratadina", "cetirizina", "levocetirizina", "clorfenamina",
        "salbutamol", "budesonida", "fluticasona", "montelukast", "atorvastatina", "rosuvastatina", "simvastatina", "enalapril", "captopril",
        "amlodipino", "valsart[aá]n", "hidroclorotiazida", "furosemida", "espironolactona", "propranolol", "bisoprolol", "carvedilol",
        "levotiroxina", "sertralina", "fluoxetina", "escitalopram", "paroxetina", "duloxetina", "venlafaxina", "bupropi[oó]n", "trazodona",
        "quetiapina", "risperidona", "aripiprazol", "clonazepam", "alprazolam", "lorazepam", "diazepam", "zopiclona", "zolpidem",
        "melatonina", "tramadol", "code[ií]na", "morfina", "prednisona", "betametasona", "dexametasona", "hidrocortisona",
        "azitromicina", "claritromicina", "ciprofloxacino", "levofloxacino", "cefalexina", "cefadroxilo", "ceftriaxona", "doxiciclina",
        "clindamicina", "metronidazol", "nitrofuranto[ií]na", "cotrimoxazol", "clotrimazol", "fluconazol", "nistatina", "aciclovir",
        "valaciclovir", "oseltamivir", "ivermectina", "albendazol", "mebendazol", "insulina", "glibenclamida", "sitagliptina",
        "semaglutida", "ozempic", "liraglutida", "warfarina", "acenocumarol", "clopidogrel", "apixab[aá]n", "rivaroxab[aá]n",
        "ranitidina", "famotidina", "domperidona", "metoclopramida", "ondansetr[oó]n", "loperamida", "sildenafil", "tadalafil",
        "finasterida", "tamsulosina", "levonorgestrel", "anticonceptiv[oa]s?", "[aá]cido f[oó]lico", "sulfato ferroso", "vitamina [a-e]\\d{0,2}",
        "colchicina", "alopurinol", "gabapentina", "pregabalina", "carbamazepina", "[aá]cido valproico", "valproato", "lamotrigina",
        "levetiracetam", "ciclobenzaprina", "baclofeno", "hidroxicloroquina", "metotrexato", "isotretino[ií]na", "minoxidil", "mupirocina",
        "viagra", "tapsin", "zinc", "magnesio", "omega 3", "probi[oó]ticos?", "antibi[oó]ticos?", "antihistam[ií]nicos?", "antidepresivos?",
        "antiinflamatorios?", "analg[eé]sicos?", "ansiol[ií]ticos?"
    )
    private val drugRx = Regex("(?iu)(?<![\\p{L}])(" + DRUGS.joinToString("|") + ")(?![\\p{L}])")

    fun drugName(q: String): String? = drugRx.find(q)?.value?.lowercase()

    fun isMedication(q: String): Boolean = drugName(q) != null ||
        Regex("(?iu)medicamentos?|remedios?|pastillas?|f[aá]rmacos?|para qu[eé] sirve|efectos? (secundarios?|adversos?)|contraindicaci|interacci[oó]n").containsMatchIn(q)

    // =====================================================================
    // 📚 MEDLINEPLUS EN ESPAÑOL (wsearch.nlm.nih.gov, máximo 85 consultas por minuto)
    // =====================================================================
    private val stop = Regex("(?iu)^(qu[eé]|que|cu[aá]l(es)?|son|es|est[aá]n?|los|las|el|la|lo|de|del|al|para|por|sirve|sirven|s[ií]ntomas?|tratamientos?|" +
        "causas?|c[oó]mo|como|se|cura|curar|tratar|trata|prevenir|previene|me|mi|mis|tengo|tiene|duele|efectos?|secundarios?|adversos?|avances?|" +
        "nuevos?|nuevas?|[uú]ltimos?|informaci[oó]n|sobre|acerca|medicamentos?|remedios?|pastillas?|contra|un|una|unos|unas|y|o|en|con|sin|hay|" +
        "puedo|puede|tomar|dosis|cu[aá]nto|cuanto|recomendada|bueno|buena|malo|mala|peligroso|hoy|gu[ií]as?|dime|explica|expl[ií]came|quiero|saber|" +
        "necesito|ayuda|consejos?|existe|existen|nueva|cl[ií]nica|cl[ií]nicas|dr|doctor|m[eé]dico|salud|mucho|muy|siempre|cuando|cu[aá]ndo|" +
        "debo|deber[ií]a|hacer|hago|\\d{4})$")

    /** «¿cuáles son los síntomas de la diabetes tipo 2?» → «diabetes tipo 2» · «para qué sirve el ibuprofeno» → «ibuprofeno» */
    fun term(q: String): String {
        drugName(q)?.let { return it }
        val words = q.replace(Regex("[¿?¡!.,;:()\"']"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && !stop.matches(it) }
        return words.take(4).joinToString(" ").lowercase()
    }

    fun medlineUrl(term: String): String =
        "https://wsearch.nlm.nih.gov/ws/query?db=healthTopicsSpanish&retmax=3&term=" + e(term) + "&tool=cumulusia"

    class Topic(val title: String, val url: String, val summary: String)

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&nbsp;", " ").replace("&amp;", "&")

    private fun clean(s: String): String = unescape(unescape(s)).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    /** Lee la respuesta XML de MedlinePlus: título, enlace y un resumen corto (2-3 oraciones). */
    fun parseMedline(xml: String): List<Topic> = Regex("(?s)<document[^>]*\\burl=\"([^\"]+)\"[^>]*>(.*?)</document>").findAll(xml).mapNotNull { m ->
        val body = m.groupValues[2]
        fun field(name: String) = Regex("(?s)<content name=\"$name\">(.*?)</content>").find(body)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        val title = field("title")
        if (title.isBlank()) return@mapNotNull null
        val full = field("FullSummary").ifBlank { field("snippet") }
        val sentences = Regex("(?<=[.!?])\\s+").split(full)
        val sb = StringBuilder()
        for (s in sentences) { if (sb.length + s.length > 420 && sb.isNotEmpty()) break; if (sb.isNotEmpty()) sb.append(' '); sb.append(s) }
        Topic(title, unescape(m.groupValues[1]), sb.toString().take(480))
    }.toList()

    fun medlineBlock(topics: List<Topic>): String {
        if (topics.isEmpty()) return ""
        val sb = StringBuilder("📚 MedlinePlus en español (Biblioteca Nacional de Medicina de EE. UU.):")
        topics.take(2).forEach { t ->
            sb.append("\n\n• ").append(t.title)
            if (t.summary.isNotBlank()) sb.append(": ").append(t.summary)
            sb.append("\n   🔗 ").append(t.url)
        }
        return sb.toString()
    }

    // =====================================================================
    // 🏛️ FUENTES OFICIALES Y 🚨 EMERGENCIAS
    // =====================================================================
    private val MINISTRY = mapOf(
        "CL" to ("Ministerio de Salud de Chile" to "minsal.cl"), "AR" to ("Ministerio de Salud de Argentina" to "argentina.gob.ar/salud"),
        "MX" to ("Secretaría de Salud de México" to "gob.mx/salud"), "CO" to ("Ministerio de Salud de Colombia" to "minsalud.gov.co"),
        "PE" to ("Ministerio de Salud del Perú" to "gob.pe/minsa"), "ES" to ("Ministerio de Sanidad de España" to "sanidad.gob.es"),
        "US" to ("CDC en español" to "cdc.gov/spanish"), "UY" to ("Ministerio de Salud Pública de Uruguay" to "gub.uy/ministerio-salud-publica"),
        "EC" to ("Ministerio de Salud Pública de Ecuador" to "salud.gob.ec")
    )

    fun trustedLinks(country: String, term: String, drug: String?): List<Pair<String, String>> {
        val q = term.ifBlank { "salud" }
        val out = mutableListOf<Pair<String, String>>()
        if (drug != null) {
            out += "💊 Ficha del medicamento (MedlinePlus)" to google("$drug site:medlineplus.gov/spanish/druginfo")
            if (country.equals("CL", true)) out += "💊 Registro en el ISP (Instituto de Salud Pública de Chile)" to google("$drug site:ispch.cl")
        } else out += "📚 Más en MedlinePlus en español" to google("$q site:medlineplus.gov/spanish")
        out += "🌍 Organización Mundial de la Salud (OMS)" to google("$q site:who.int/es")
        MINISTRY[country.uppercase()]?.let { (name, domain) -> out += "🏛️ $name" to google("$q site:$domain") }
        return out
    }

    private val EMERGENCY = mapOf(
        "CL" to "131 (SAMU, ambulancia)", "AR" to "107 (emergencias médicas) o 911", "MX" to "911", "CO" to "123", "PE" to "106 (SAMU)",
        "ES" to "112", "US" to "911", "UY" to "911", "EC" to "911", "CR" to "911", "PA" to "911", "DO" to "911"
    )

    fun emergencyLine(country: String): String {
        val n = EMERGENCY[country.uppercase()]
        val sb = StringBuilder("🚨 Si es una urgencia (dolor en el pecho, falta de aire, desmayo, sangrado abundante, signos de ACV): ")
            .append(if (n != null) "llama al $n" else "llama al número de emergencias de tu país").append(" o ve al servicio de urgencias más cercano.")
        if (country.equals("CL", true)) sb.append("\n📞 Salud Responde (MINSAL, 24 horas): 600 360 7777")
        return sb.toString()
    }

    // =====================================================================
    // 🏥 FARMACIAS, FARMACIAS DE TURNO, HOSPITALES Y URGENCIAS CERCA
    // =====================================================================
    enum class PlaceKind { DUTY_PHARMACY, PHARMACY, HOSPITAL }

    fun placeKind(q: String): PlaceKind? = when {
        Regex("(?iu)farmacias? de turno|turnos? de farmacias?|farmacias? (abiertas?|24 ?h|24 horas)").containsMatchIn(q) -> PlaceKind.DUTY_PHARMACY
        Regex("(?iu)\\bfarmacias?\\b|\\bboticas?\\b|droguer[ií]as?").containsMatchIn(q) -> PlaceKind.PHARMACY
        Regex("(?iu)\\bhospital(es)?\\b|\\bcl[ií]nicas?\\b|\\burgencias?\\b|\\bcesfam\\b|\\bconsultorios?\\b|\\bsapu\\b|\\bsar\\b|\\bpostas?\\b|centros? (m[eé]dicos?|de salud)")
            .containsMatchIn(q) -> PlaceKind.HOSPITAL
        else -> null
    }

    fun placesQuery(kind: PlaceKind, lat: Double, lon: Double, radiusM: Int): String {
        val a = "(around:$radiusM,${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lon)})"
        val body = when (kind) {
            PlaceKind.HOSPITAL -> "nwr[\"amenity\"~\"^(hospital|clinic)$\"][\"name\"]$a;nwr[\"healthcare\"~\"^(hospital|clinic|centre)$\"][\"name\"]$a;"
            else -> "nwr[\"amenity\"=\"pharmacy\"][\"name\"]$a;"
        }
        return "[out:json][timeout:20];($body);out center tags 80;"
    }

    /** Farmacias de turno de Chile (MINSAL, datos oficiales y públicos). */
    const val DUTY_URL = "https://midas.minsal.cl/farmacia_v2/WS/getLocalesTurnos.php"

    class Pharmacy(val name: String, val address: String, val comuna: String, val phone: String, val opens: String, val closes: String,
                   val lat: Double, val lon: Double, val distanceM: Int) {
        val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=" + e("$name $lat,$lon")
    }

    /** [days]: las fechas de hoy y ayer en los formatos posibles («2026-10-14», «14-10-2026»…). Requiere org.json. */
    fun parseDuty(json: String, lat: Double, lon: Double, days: Set<String>): List<Pharmacy> {
        val arr = org.json.JSONArray(json.trim())
        val all = mutableListOf<Pair<Pharmacy, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val la = o.optString("local_lat").replace(',', '.').trim().toDoubleOrNull() ?: continue
            val lo = o.optString("local_lng").replace(',', '.').trim().toDoubleOrNull() ?: continue
            if (la == 0.0 || lo == 0.0) continue
            fun t(k: String) = o.optString(k).trim().let { if (it.equals("null", true)) "" else it }
            val ph = Pharmacy(t("local_nombre").split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } },
                t("local_direccion").split(' ').joinToString(" ") { w -> if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.titlecase() } },
                t("comuna_nombre").ifBlank { t("localidad_nombre") }, t("local_telefono"),
                t("funcionamiento_hora_apertura").take(5), t("funcionamiento_hora_cierre").take(5), la, lo, FoodFinder.distance(lat, lon, la, lo))
            all += ph to t("fecha")
        }
        val today = all.filter { it.second.isBlank() || it.second in days }.ifEmpty { all }
        val seen = HashSet<String>()
        return today.map { it.first }.sortedBy { it.distanceM }.filter { seen.add(it.name + it.address) }.take(5)
    }

    fun dutyBlock(list: List<Pharmacy>, area: String): String {
        if (list.isEmpty()) return ""
        val sb = StringBuilder("💊 Farmacias de turno más cercanas a ").append(area).append(" (MINSAL):\n")
        list.forEachIndexed { i, p ->
            sb.append("\n").append(i + 1).append(". ").append(p.name).append(" — ").append(NearbyFinder.distanceLabel(p.distanceM)).append('\n')
            sb.append("   📍 ").append(p.address).append(if (p.comuna.isNotBlank()) ", ${p.comuna.lowercase().replaceFirstChar { it.titlecase() }}" else "").append('\n')
            if (p.phone.isNotBlank()) sb.append("   📞 ").append(p.phone).append('\n')
            if (p.opens.isNotBlank() && p.closes.isNotBlank()) sb.append("   🕒 Turno: ").append(
                if (p.opens == p.closes) "24 horas (desde las ${p.opens})" else "${p.opens} a ${p.closes}").append('\n')
            sb.append("   🗺️ ").append(p.mapsUrl).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun mapsSearch(text: String): String = "https://www.google.com/maps/search/" + e(text)
}

