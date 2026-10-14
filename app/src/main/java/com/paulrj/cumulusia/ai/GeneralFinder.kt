package com.paulrj.cumulusia.ai

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/**
 * 🔎 CUALQUIER OTRA CONSULTA (categoría 31): la IA responde con lo que encuentra en internet y la app agrega datos comprobables:
 *  📖 ¿Qué es? ¿Quién fue? Historia local → resumen de Wikipedia en español con su foto y su enlace.
 *  🏛️ Trámites → los sitios OFICIALES de tu país para ese trámite (ChileAtiende, Registro Civil, SII, ANSES, SAT, RENIEC…).
 *  ▶️ Tutoriales («cómo hacer…», «paso a paso») → videos tutoriales reales de YouTube con su miniatura.
 *  ⚽ Deportes → resultados, próximos partidos y tabla de posiciones (datos de ESPN) y el resumen en YouTube.
 * Las fuentes de la búsqueda ya las pone la app al final («Fuentes:»).
 */
object GeneralFinder {

    enum class Kind { SPORTS, TRAMITE, TUTORIAL, KNOWLEDGE, OTHER }

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun google(q: String) = "https://www.google.com/search?q=" + e(q)
    fun plain(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private val sportsRx = Regex("(?iu)f[uú]tbol|partidos? (de|del) (hoy|f[uú]tbol|la selecci[oó]n|la roja|ma[ñn]ana)|el partido de|resultados? (del?|de la) (partido|fecha|campeonato|liga)|" +
        "marcador|\\bgol(es)?\\b|tabla de posiciones|tabla del (campeonato|torneo)|" +
        "campeonato|\\bliga\\b|torneo|copa (libertadores|sudamericana|am[eé]rica|del mundo|chile|de la liga)|mundial (de f[uú]tbol|de clubes|sub|20[23]\\d)|eliminatorias|champions|" +
        "premier|la ?liga|serie a|bundesliga|ligue 1|\\bmls\\b|liga mx|\\bnba\\b|\\bnfl\\b|f[oó]rmula 1|\\bf1\\b|tenis|\\batp\\b|\\bwta\\b|" +
        "boxeo|\\bufc\\b|cu[aá]ndo juega|a qu[eé] hora juega|resultados? de (anoche|ayer|hoy)|lakers|celtics|warriors|knicks|" +
        "selecci[oó]n (chilena|argentina|mexicana|" +
        "colombiana|peruana|espa[ñn]ola)|la roja|colo[- ]?colo|universidad de chile|universidad cat[oó]lica|real madrid|barcelona|boca juniors|river plate|" +
        "manchester (city|united)|liverpool|arsenal|chelsea|juventus|bayern|psg|inter miami|flamengo|palmeiras")

    private val tramiteRx = Regex("(?iu)tr[aá]mites?|certificados?|\\bcarnet\\b|c[eé]dula|licencia de conduc|clave [uú]nica|registro civil|\\bsii\\b|" +
        "impuestos?|declaraci[oó]n de (renta|impuestos)|operaci[oó]n renta|\\bafp\\b|fonasa|isapre|\\bbonos?\\b|subsidios?|beneficios? (del estado|sociales?)|" +
        "pensi[oó]n|\\bpgu\\b|permiso de circulaci[oó]n|revisi[oó]n t[eé]cnica|multas?|partida de nacimiento|\\bcurp\\b|\\brut\\b|\\bdni\\b|\\bnie\\b|" +
        "\\brfc\\b|\\bsat\\b|anses|imss|afip|\\barca\\b|seguro de cesant[ií]a|registro social de hogares|\\brsh\\b|chileatiende|finiquito|" +
        "posesi[oó]n efectiva|antecedentes|c[oó]mo (sacar|obtener|renovar|pedir|tramitar|solicitar)|requisitos para (sacar|obtener|renovar|pedir|postular)|" +
        "postular (a|al)|inscribir(me)? en|reni[ec]c|sunat|dian\\b|registradur[ií]a|monotributo|cuit|cuil")

    private val tutorialRx = Regex("(?iu)tutorial|paso a paso|c[oó]mo (se )?(hace|hacer|hago|instal|configur|arregl|repar|cambi[oa]r?|limpi|aprend|arm[oa]r?|" +
        "dibuj|tej|pint|plant|cultiv|cocin|prepar|conect|desbloque|recuper|actualiz|format|quit|pon[eo]r?|cort|pegar|cos|lavar|hacer funcionar)|" +
        "gu[ií]a (para|de)|aprende a|trucos? para|manual de")

    private val knowledgeRx = Regex("(?iu)^\\s*¿?\\s*(qu[eé] (es|son|era|fue|significa)|qui[eé]n(es)? (es|son|fue|fueron|era|invent|descubri|escribi|pint|cre[oó]|fund)|" +
        "cu[aá]ndo (fue|se fund|naci[oó]|muri[oó]|ocurri[oó]|empez[oó]|termin[oó]|se cre[oó]|se descubri[oó])|d[oó]nde (queda|est[aá]|naci[oó])|por qu[eé] se llama)|" +
        "historia de|significado de|origen de|biograf[ií]a de|datos (curiosos )?(de|sobre)|h[aá]blame (de|sobre)|cu[eé]ntame (de|sobre)|" +
        "cu[aá]ntos habitantes tiene|cu[aá]l es la capital de|poblaci[oó]n de")

    /** Qué tipo de consulta general es (para el formato de la respuesta y lo que agrega la app). */
    fun kind(q: String): Kind = when {
        sportsRx.containsMatchIn(q) -> Kind.SPORTS
        tramiteRx.containsMatchIn(q) -> Kind.TRAMITE
        tutorialRx.containsMatchIn(q) -> Kind.TUTORIAL
        knowledgeRx.containsMatchIn(q) -> Kind.KNOWLEDGE
        else -> Kind.OTHER
    }

    /** Formato de la respuesta según el tipo de consulta general. */
    fun format(k: Kind): String = when (k) {
        Kind.SPORTS -> "⚽ Resultado o próximo partido: fecha — hora — rivales — marcador — estadio\n" +
            "Luego: goles y figuras, posición en la tabla y lo que viene. Los resultados exactos y la tabla los agrega la app debajo."
        Kind.TRAMITE -> "Respuesta directa en 1-2 líneas y luego:\n📋 Requisitos\n🪜 Pasos numerados (en línea o presencial)\n💲 Costo\n⏱️ Plazo\n" +
            "🏛️ Dónde se hace (institución oficial). Los enlaces oficiales los agrega la app: no los inventes."
        Kind.TUTORIAL -> "Respuesta directa en 1 línea y luego:\n🧰 Qué necesitas\n🪜 Pasos numerados, cortos y claros\n⚠️ Precauciones o errores comunes\n" +
            "Los videos tutoriales de YouTube los agrega la app debajo."
        Kind.KNOWLEDGE -> "Respuesta directa en 1-2 líneas y luego:\n📅 Fechas y hechos clave en orden\n• Datos importantes o curiosos en viñetas\n" +
            "El resumen de Wikipedia con su foto lo agrega la app debajo."
        Kind.OTHER -> "Respuesta directa en 1-2 líneas y luego los detalles en viñetas (fechas, cifras, pasos o requisitos, según el tema)."
    }

    /** Palabras que se agregan a la búsqueda según el tipo. */
    fun hint(k: Kind, q: String): String = when (k) {
        Kind.TRAMITE -> "requisitos pasos {AÑO}"
        Kind.TUTORIAL -> "paso a paso"
        Kind.SPORTS -> if (Regex("(?iu)tabla|posiciones").containsMatchIn(q)) "{AÑO}" else "{MES} {AÑO}"
        else -> ""
    }

    // =====================================================================
    // 📖 WIKIPEDIA EN ESPAÑOL
    // =====================================================================
    /** «¿Quién fue Arturo Prat?» → «Arturo Prat» · «historia de Concepción» → «historia de Concepción» */
    fun wikiTerm(q: String): String = q.replace(Regex("[¿?¡!]"), " ")
        .replace(Regex("(?iu)^\\s*(oye|hola|por favor|dime|cu[eé]ntame|h[aá]blame|expl[ií]came|quiero saber)\\b[,\\s]*"), "")
        .replace(Regex("(?iu)^\\s*(qu[eé] (es|son|era|fue|significa)|qui[eé]n(es)? (es|son|fue|fueron|era)|d[oó]nde (queda|est[aá])|biograf[ií]a de|" +
            "cu[aá]ntos habitantes tiene|cu[aá]l es la capital de|poblaci[oó]n de|" +
            "significado de|datos (curiosos )?(de|sobre)|(de|sobre))\\s+(el |la |los |las |un |una )?"), "")
        .replace(Regex("\\s+"), " ").trim().take(80)

    fun wikiSearchUrl(term: String): String =
        "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&utf8=1&srsearch=" + e(term)

    fun wikiSummaryUrl(title: String): String =
        "https://es.wikipedia.org/api/rest_v1/page/summary/" + e(title.replace(' ', '_')).replace("+", "%20")

    /** Primer título de la búsqueda de Wikipedia. Requiere org.json. */
    fun parseWikiSearch(json: String): String? = org.json.JSONObject(json).optJSONObject("query")?.optJSONArray("search")
        ?.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }

    class Wiki(val title: String, val extract: String, val url: String, val image: String)

    fun parseWikiSummary(json: String): Wiki? {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "disambiguation") return null
        val extract = o.optString("extract").trim()
        if (extract.isBlank()) return null
        val url = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            ?: o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page") ?: ""
        val thumb = o.optJSONObject("thumbnail")?.optString("source").orEmpty()
        // Miniatura más grande (800 px) si la original lo permite
        val image = if (thumb.contains(Regex("/\\d+px-"))) thumb.replace(Regex("/\\d+px-"), "/800px-") else thumb
        return Wiki(o.optString("title"), shorten(extract, 650), url, image)
    }

    private fun shorten(t: String, max: Int): String {
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        val end = cut.lastIndexOf(". ")
        return if (end > max / 2) cut.substring(0, end + 1) else cut.trimEnd() + "…"
    }

    fun wikiBlock(w: Wiki, photoNumber: Int?): String =
        "📖 Wikipedia — ${w.title}" + (photoNumber?.let { " · 🖼️ Foto $it (abajo)" } ?: "") + ":\n" + w.extract +
            (if (w.url.isNotBlank()) "\n🔗 ${w.url}" else "")

    // =====================================================================
    // 🏛️ TRÁMITES: SITIOS OFICIALES POR PAÍS
    // =====================================================================
    private class Office(val name: String, val domain: String, val rx: Regex?)

    private fun r(p: String) = Regex("(?iu)$p")

    private val OFFICES: Map<String, List<Office>> = mapOf(
        "CL" to listOf(
            Office("ChileAtiende", "chileatiende.gob.cl", null),
            Office("Registro Civil", "registrocivil.cl", r("carnet|c[eé]dula|pasaporte|certificado|nacimiento|matrimonio|defunci|antecedentes|posesi[oó]n efectiva|registro civil|anotaciones|inscripci[oó]n")),
            Office("SII (impuestos)", "sii.cl", r("impuesto|renta|\\bsii\\b|boleta|factura|\\brut\\b|\\biva\\b|contribuciones|inicio de actividades")),
            Office("Registro Social de Hogares", "registrosocial.gob.cl", r("registro social|\\brsh\\b|\\bbonos?\\b|subsidio|beneficio|\\bpgu\\b")),
            Office("Fonasa", "fonasa.cl", r("fonasa|tramo|licencia m[eé]dica")),
            Office("Superintendencia de Pensiones (AFP)", "spensiones.cl", r("\\bafp\\b|pensi[oó]n|jubilaci|retiro de fondos")),
            Office("AFC (seguro de cesantía)", "afc.cl", r("cesant[ií]a")),
            Office("Dirección del Trabajo", "dt.gob.cl", r("finiquito|contrato de trabajo|despido|vacaciones|horas extra|sueldo m[ií]nimo")),
            Office("Clave Única", "claveunica.gob.cl", r("clave [uú]nica")),
            Office("MINVU (vivienda)", "minvu.gob.cl", r("subsidio (de )?(vivienda|habitacional|arriendo)|vivienda"))
        ),
        "AR" to listOf(
            Office("Argentina.gob.ar", "argentina.gob.ar", null),
            Office("ANSES", "anses.gob.ar", r("anses|jubilaci|asignaci[oó]n|\\bauh\\b|pensi[oó]n|\\bbonos?\\b")),
            Office("ARCA (ex AFIP)", "arca.gob.ar", r("afip|\\barca\\b|monotributo|impuesto|ganancias|cuit|factura")),
            Office("RENAPER (DNI y pasaporte)", "argentina.gob.ar/interior/renaper", r("\\bdni\\b|pasaporte|documento"))
        ),
        "MX" to listOf(
            Office("gob.mx", "gob.mx", null),
            Office("SAT", "sat.gob.mx", r("\\bsat\\b|\\brfc\\b|impuesto|factura|declaraci[oó]n|e\\.?firma")),
            Office("IMSS", "imss.gob.mx", r("imss|semanas cotizadas|incapacidad|\\bnss\\b|pensi[oó]n")),
            Office("CURP", "gob.mx/curp", r("curp")),
            Office("INE", "ine.mx", r("\\bine\\b|credencial (para votar|de elector)"))
        ),
        "CO" to listOf(
            Office("GOV.CO", "gov.co", null),
            Office("Registraduría", "registraduria.gov.co", r("c[eé]dula|registro civil|tarjeta de identidad")),
            Office("DIAN", "dian.gov.co", r("dian|\\brut\\b|impuesto|renta|factura")),
            Office("RUNT (vehículos y licencias)", "runt.gov.co", r("runt|licencia de conducci|veh[ií]culo|soat"))
        ),
        "PE" to listOf(
            Office("gob.pe", "gob.pe", null),
            Office("RENIEC", "gob.pe/reniec", r("\\bdni\\b|reniec|partida")),
            Office("SUNAT", "sunat.gob.pe", r("sunat|\\bruc\\b|impuesto|renta|factura|boleta")),
            Office("Migraciones (pasaporte)", "gob.pe/migraciones", r("pasaporte|migraciones|carn[eé] de extranjer"))
        ),
        "ES" to listOf(
            Office("administracion.gob.es", "administracion.gob.es", null),
            Office("Agencia Tributaria", "agenciatributaria.gob.es", r("hacienda|renta|impuesto|\\biva\\b|\\bnif\\b|declaraci[oó]n")),
            Office("Seguridad Social", "seg-social.es", r("seguridad social|pensi[oó]n|jubilaci|vida laboral|\\bbaja\\b")),
            Office("SEPE (paro)", "sepe.es", r("\\bparo\\b|desempleo|sepe")),
            Office("DNI y pasaporte", "dnielectronico.es", r("\\bdni\\b|pasaporte")),
            Office("DGT", "dgt.es", r("carn[eé] de conducir|\\bdgt\\b|multa|\\bitv\\b|veh[ií]culo"))
        ),
        "US" to listOf(
            Office("USA.gov en español", "usa.gov/es", null),
            Office("IRS en español", "irs.gov/es", r("\\birs\\b|impuesto|taxes|declaraci[oó]n")),
            Office("USCIS en español", "uscis.gov/es", r("uscis|green card|residencia|ciudadan|visa|permiso de trabajo")),
            Office("Seguro Social", "ssa.gov/espanol", r("seguro social|\\bssn\\b|jubilaci|medicare"))
        )
    )

    /** Sitios oficiales para el trámite (búsqueda dentro de cada sitio, así el enlace siempre funciona). */
    fun tramiteLinks(country: String, q: String): List<Pair<String, String>> {
        val term = q.replace(Regex("[¿?¡!]"), " ").replace(Regex("(?iu)\\b(c[oó]mo|puedo|quiero|necesito|dime|por favor|hago|hacer|el|la|los|las|un|una|mi|para)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().take(80)
        val offices = OFFICES[country.uppercase()] ?: return listOf("Buscar en sitios de gobierno" to google("$term site:gob OR site:gov"))
        val specific = offices.filter { it.rx != null && it.rx.containsMatchIn(q) }
        val general = offices.filter { it.rx == null }
        return (specific + general).take(4).map { it.name to google("$term site:${it.domain}") }
    }

    fun tramiteBlock(country: String, countryName: String, q: String): String {
        val links = tramiteLinks(country, q)
        val sb = StringBuilder("🏛️ Sitios oficiales").append(if (countryName.isNotBlank()) " de $countryName" else "").append(" para este trámite:")
        links.forEach { (n, u) -> sb.append("\n   • ").append(n).append(": ").append(u) }
        if (country.equals("CL", true)) sb.append("\n📞 ChileAtiende: 101 (orientación de trámites del Estado)")
        sb.append("\n⚠️ Haz los trámites solo en sitios oficiales (.gob / .gov): nunca pagues a intermediarios ni des tu clave por teléfono.")
        return sb.toString()
    }

    // =====================================================================
    // ▶️ TUTORIALES EN YOUTUBE
    // =====================================================================
    /** «¿cómo cambio la rueda del auto?» → «cómo cambiar la rueda del auto tutorial» */
    fun tutorialQuery(q: String): String {
        val t = q.replace(Regex("[¿?¡!]"), " ").replace(Regex("(?iu)\\b(por favor|dime|me puedes|puedes|ens[eé][ñn]ame|quiero saber|necesito saber)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return if (Regex("(?iu)tutorial").containsMatchIn(t)) t else "$t tutorial"
    }

    // =====================================================================
    // ⚽ DEPORTES (ESPN: resultados, próximos partidos y tabla)
    // =====================================================================
    class League(val slug: String, val name: String, val sport: String = "soccer")

    private val LEAGUES: List<Pair<Regex, League>> = listOf(
        r("libertadores") to League("conmebol.libertadores", "Copa Libertadores"),
        r("sudamericana") to League("conmebol.sudamericana", "Copa Sudamericana"),
        r("champions") to League("uefa.champions", "Champions League"),
        r("europa league") to League("uefa.europa", "Europa League"),
        r("\\bnba\\b|lakers|celtics|warriors|bulls|knicks") to League("nba", "NBA", "basketball"),
        r("premier|manchester (city|united)|liverpool|arsenal|chelsea|tottenham|newcastle|aston villa") to League("eng.1", "Premier League"),
        r("la ?liga|liga espa[ñn]ola|real madrid|barcelona|bar[cç]a|atl[eé]tico de madrid|sevilla|betis|villarreal|real sociedad|athletic club") to League("esp.1", "LaLiga"),
        r("serie a|juventus|inter de mil[aá]n|\\bmilan\\b|napoli|\\broma\\b|lazio") to League("ita.1", "Serie A"),
        r("bundesliga|bayern|dortmund|leverkusen") to League("ger.1", "Bundesliga"),
        r("ligue 1|\\bpsg\\b|paris saint|marsella|olympique") to League("fra.1", "Ligue 1"),
        r("brasileir|flamengo|palmeiras|corinthians|s[aã]o paulo|fluminense|botafogo") to League("bra.1", "Brasileirão"),
        r("\\bmls\\b|inter miami|la galaxy") to League("usa.1", "MLS"),
        r("liga mx|chivas|cruz azul|pumas|tigres|monterrey|toluca|club am[eé]rica") to League("mex.1", "Liga MX"),
        r("liga profesional|f[uú]tbol argentino|boca|river|racing|independiente|san lorenzo|estudiantes|v[eé]lez|talleres|rosario central|newell") to League("arg.1", "Liga Profesional Argentina"),
        r("liga betplay|millonarios|atl[eé]tico nacional|am[eé]rica de cali|junior de barranquilla|santa fe") to League("col.1", "Liga BetPlay"),
        r("liga 1 per|alianza lima|universitario|sporting cristal") to League("per.1", "Liga 1 de Perú"),
        r("campeonato (nacional|chileno)|primera divisi[oó]n de chile|liga de primera|f[uú]tbol chileno|colo[- ]?colo|universidad de chile|\\bla u\\b|" +
            "universidad cat[oó]lica|\\bla uc\\b|cobreloa|everton|huachipato|palestino|o'?higgins|audax|cobresal|coquimbo|uni[oó]n espa[ñn]ola|[ñn]ublense|" +
            "deportes iquique|deportes la serena|uni[oó]n la calera|deportes limache") to League("chi.1", "Campeonato chileno")
    )

    private val HOME_LEAGUE = mapOf("CL" to "chi.1", "AR" to "arg.1", "MX" to "mex.1", "CO" to "col.1", "PE" to "per.1", "ES" to "esp.1",
        "US" to "usa.1", "BR" to "bra.1")

    /** Liga del pedido (o la de tu país si solo dice «fútbol», «el partido de hoy»…). Null = no es fútbol/NBA. */
    fun league(q: String, country: String): League? {
        LEAGUES.firstOrNull { it.first.containsMatchIn(q) }?.let { return it.second }
        if (!Regex("(?iu)f[uú]tbol|partido|campeonato|\\bliga\\b|tabla|torneo|\\bgol(es)?\\b|marcador").containsMatchIn(q)) return null
        val slug = HOME_LEAGUE[country.uppercase()] ?: return null
        return LEAGUES.map { it.second }.firstOrNull { it.slug == slug }
    }

    /** Equipo nombrado (para filtrar sus partidos): alias → nombre como lo escribe ESPN (sin tildes, en minúsculas). */
    private val ALIASES = listOf(
        r("colo[- ]?colo") to "colo-colo", r("universidad de chile|\\bla u\\b") to "universidad de chile",
        r("universidad cat[oó]lica|\\bla uc\\b|\\bcat[oó]lica\\b") to "universidad catolica", r("bar[cç]a|barcelona") to "barcelona",
        r("real madrid") to "real madrid", r("atl[eé]tico de madrid") to "atletico", r("manchester city|\\bman city\\b") to "manchester city",
        r("manchester united|\\bman united\\b") to "manchester united", r("\\bpsg\\b|paris saint") to "paris saint-germain",
        r("inter de mil[aá]n") to "internazionale", r("boca") to "boca juniors", r("river") to "river plate", r("chivas") to "guadalajara",
        r("club am[eé]rica") to "america", r("inter miami") to "inter miami", r("lakers") to "lakers", r("celtics") to "celtics",
        r("warriors") to "warriors", r("bulls") to "bulls", r("knicks") to "knicks"
    )

    fun team(q: String): String? {
        ALIASES.firstOrNull { it.first.containsMatchIn(q) }?.let { return it.second }
        // Sin (?i): con mayúsculas/minúsculas ignoradas, \\p{Lu} también aceptaría minúsculas
        val named = Regex("(?u)(?<![\\p{L}])(?:[Dd]el?|[Ee]l|[Jj]uega|[Jj]ug[oó]|[Gg]an[oó]|[Pp]artido (?:del?|de la))\\s+(\\p{Lu}[\\p{L}'-]+(?:\\s+(?:de\\s+)?\\p{Lu}[\\p{L}'-]+){0,3})").find(q)
            ?.groupValues?.get(1) ?: return null
        val p = plain(named)
        return p.takeUnless { Regex("^(chile|argentina|mexico|espana|peru|colombia|la|el|hoy|la roja|roja|chilena|argentina)$").matches(it) }
    }

    fun wantsTable(q: String): Boolean = Regex("(?iu)tabla|posiciones|clasificaci[oó]n|qui[eé]n va primero|l[ií]der").containsMatchIn(q)

    private fun ymd(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun scoreboardUrl(l: League, now: Long, backDays: Int, aheadDays: Int): String =
        "https://site.api.espn.com/apis/site/v2/sports/${l.sport}/${l.slug}/scoreboard?lang=es&region=cl&limit=200&dates=" +
            ymd(now - backDays * 86_400_000L) + "-" + ymd(now + aheadDays * 86_400_000L)

    fun standingsUrl(l: League): String = "https://site.api.espn.com/apis/v2/sports/${l.sport}/${l.slug}/standings?lang=es&region=cl"

    class Match(val time: Long, val home: String, val away: String, val homeScore: String, val awayScore: String,
                val done: Boolean, val live: Boolean, val status: String, val venue: String)

    /** Partidos del marcador de ESPN. Requiere org.json. */
    fun parseScoreboard(json: String): List<Match> {
        val out = mutableListOf<Match>()
        val events = org.json.JSONObject(json).optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val cs = comp.optJSONArray("competitors") ?: continue
            var home: org.json.JSONObject? = null; var away: org.json.JSONObject? = null
            for (k in 0 until cs.length()) {
                val c = cs.optJSONObject(k) ?: continue
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) continue
            val type = (comp.optJSONObject("status") ?: ev.optJSONObject("status"))?.optJSONObject("type")
            val state = type?.optString("state").orEmpty()
            val time = runCatching { java.time.Instant.parse(ev.optString("date").let { if (Regex(":\\d{2}Z$").containsMatchIn(it) && it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }
                .getOrDefault(0L)
            fun name(c: org.json.JSONObject) = c.optJSONObject("team")?.let { t -> t.optString("displayName").ifBlank { t.optString("name") } }.orEmpty()
            out += Match(time, name(home), name(away), home.optString("score"), away.optString("score"),
                type?.optBoolean("completed") == true || state == "post", state == "in",
                type?.optString("shortDetail").orEmpty().ifBlank { type?.optString("description").orEmpty() },
                comp.optJSONObject("venue")?.optString("fullName").orEmpty())
        }
        return out.sortedBy { it.time }
    }

    class Row(val pos: Int, val team: String, val played: String, val points: String, val diff: String)

    fun parseStandings(json: String): List<Row> {
        val o = org.json.JSONObject(json)
        val groups = mutableListOf<org.json.JSONObject>()
        o.optJSONObject("standings")?.let { groups += o }
        o.optJSONArray("children")?.let { ch -> for (i in 0 until ch.length()) ch.optJSONObject(i)?.let { groups += it } }
        val g = groups.firstOrNull() ?: return emptyList()
        val entries = g.optJSONObject("standings")?.optJSONArray("entries") ?: return emptyList()
        val rows = mutableListOf<Row>()
        for (i in 0 until entries.length()) {
            val en = entries.optJSONObject(i) ?: continue
            val team = en.optJSONObject("team")?.optString("displayName").orEmpty()
            val stats = en.optJSONArray("stats")
            fun stat(vararg names: String): String {
                if (stats == null) return ""
                for (k in 0 until stats.length()) {
                    val s = stats.optJSONObject(k) ?: continue
                    if (s.optString("name") in names || s.optString("type") in names)
                        return s.optString("displayValue").ifBlank { s.optString("value").substringBefore(".0") }
                }
                return ""
            }
            val rank = stat("rank").toIntOrNull() ?: (i + 1)
            rows += Row(rank, team, stat("gamesPlayed"), stat("points"), stat("pointDifferential", "pointdifferential"))
        }
        return rows.sortedBy { it.pos }
    }

    private val MES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val DIA = listOf("dom", "lun", "mar", "mié", "jue", "vie", "sáb")

    private fun whenText(ms: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return DIA[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MES[c.get(Calendar.MONTH)] +
            " %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun matchLine(m: Match, tz: TimeZone): String = when {
        m.done -> "✅ ${whenText(m.time, tz).substringBeforeLast(' ')}: ${m.home} ${m.homeScore}–${m.awayScore} ${m.away}"
        m.live -> "🔴 EN VIVO: ${m.home} ${m.homeScore}–${m.awayScore} ${m.away} (${m.status})"
        else -> "🕒 ${whenText(m.time, tz)}: ${m.home} vs ${m.away}" + if (m.venue.isNotBlank()) " · 🏟️ ${m.venue}" else ""
    }

    /** Bloque de resultados: los del equipo (si nombró uno) o los de la liga (recientes, en vivo y próximos). */
    fun matchesBlock(l: League, matches: List<Match>, team: String?, now: Long, tz: TimeZone): String {
        val list = if (team != null) matches.filter { plain(it.home).contains(team) || plain(it.away).contains(team) } else matches
        if (list.isEmpty()) return ""
        val done = list.filter { it.done }.takeLast(if (team != null) 3 else 6)
        val live = list.filter { it.live }
        val next = list.filter { !it.done && !it.live && it.time > now - 3 * 3_600_000L }.take(if (team != null) 2 else 6)
        val sb = StringBuilder("⚽ ").append(l.name).append(" (datos de ESPN):")
        (live + done.reversed() + next).forEach { sb.append("\n   ").append(matchLine(it, tz)) }
        return sb.toString()
    }

    fun tableBlock(l: League, rows: List<Row>, team: String?): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("📊 Tabla de posiciones — ").append(l.name).append(":")
        val top = rows.take(10).toMutableList()
        if (team != null) rows.firstOrNull { plain(it.team).contains(team) }?.let { if (it !in top) top += it }
        top.forEach { r ->
            sb.append("\n   ").append(r.pos).append(". ").append(r.team).append(" — ").append(r.points).append(" pts")
            val extra = listOfNotNull(r.played.takeIf { it.isNotBlank() }?.let { "$it PJ" }, r.diff.takeIf { it.isNotBlank() }?.let { "DG $it" })
            if (extra.isNotEmpty()) sb.append(" (").append(extra.joinToString(", ")).append(")")
        }
        return sb.toString()
    }
}
