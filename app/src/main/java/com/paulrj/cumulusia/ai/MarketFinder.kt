package com.paulrj.cumulusia.ai

import java.net.URLEncoder
import java.text.Normalizer

/**
 * 🏠 ARRIENDOS Y 🚗 VEHÍCULOS: lo que la app agrega debajo de la respuesta de la IA.
 *  🏠 ARRIENDOS DE CASAS Y DEPARTAMENTOS (mensual, por día o en venta)
 *     → avisos reales con foto y el enlace a cada aviso, los portales inmobiliarios de tu país con la búsqueda lista
 *       (Portal Inmobiliario, Toctoc, Yapo, Zonaprop, Inmuebles24, Fincaraíz, Idealista…), Airbnb y Booking si es por día,
 *       y las corredoras de propiedades cerca (📍 dirección, 📞 teléfono, sitio web y mapa).
 *  🚗 COMPRA Y VENTA DE VEHÍCULOS (autos y motos, nuevos y usados)
 *     → foto del modelo, avisos reales con foto y enlace, los portales automotrices de tu país (Chileautos, Kavak, Mercado Libre,
 *       TuCarro, Neoauto, coches.net…), dónde tasarlo, la ficha técnica y las concesionarias o automotoras cerca.
 *     → «arriendo de autos»: las empresas de rent a car cerca con su teléfono y dirección.
 * Todos los enlaces son de páginas que existen: la del aviso, la del portal o una búsqueda dentro del portal.
 */
object MarketFinder {

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun google(q: String) = "https://www.google.com/search?q=" + e(q)
    private fun site(q: String, domain: String) = google("$q site:$domain")
    private fun noAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun f6(d: Double) = "%.6f".format(java.util.Locale.US, d)
    private fun around(r: Int, lat: Double, lon: Double) = "(around:$r,${f6(lat)},${f6(lon)})"

    /** Mercado Libre por país (búsqueda: listado.mercadolibre.cl/toyota-yaris-2020). */
    private val ML = mapOf("CL" to "mercadolibre.cl", "AR" to "mercadolibre.com.ar", "MX" to "mercadolibre.com.mx",
        "CO" to "mercadolibre.com.co", "PE" to "mercadolibre.com.pe", "UY" to "mercadolibre.com.uy", "EC" to "mercadolibre.com.ec",
        "VE" to "mercadolibre.com.ve")

    private fun mercadoLibre(country: String, text: String): Pair<String, String>? = ML[country.uppercase()]?.let { d ->
        val slug = noAccents(text.lowercase()).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        "Mercado Libre" to "https://listado.$d/$slug"
    }

    /** «en Chile» no sirve para buscar en el mapa (es todo el país): se usa tu ubicación. */
    private val countryNames = Regex("(?iu)^(chile|argentina|per[uú]|bolivia|colombia|m[eé]xico|espa[ñn]a|venezuela|ecuador|uruguay|paraguay|" +
        "cuba|guatemala|honduras|el salvador|nicaragua|costa rica|panam[aá]|rep[uú]blica dominicana|puerto rico|estados unidos|eeuu|usa)$")
    fun placeOf(q: String): String? = FoodFinder.placeIn(q)?.takeUnless { countryNames.matches(it.trim()) }

    private fun marketplace(text: String) = "Facebook Marketplace" to "https://www.facebook.com/marketplace/search/?query=" + e(text)

    // =====================================================================
    // 🏠 ARRIENDOS, VENTA DE PROPIEDADES Y CORREDORAS
    // =====================================================================
    class Rent(
        val what: String,        // departamento, casa, pieza, cabaña, oficina, local, terreno, parcela, propiedad
        val daily: Boolean,      // por día / por noche / temporada
        val sale: Boolean,       // compra o venta (bienes raíces)
        val rooms: Int?,         // dormitorios
        val furnished: Boolean,
        val place: String?
    ) {
        val operation: String get() = when { sale -> "en venta"; daily -> "por día"; else -> "en arriendo" }
    }

    private val propertyKinds = listOf(
        Regex("(?iu)\\b(departamentos?|deptos?|dptos?|apartamentos?|aptos?|monoambientes?|estudios?)\\b") to "departamento",
        Regex("(?iu)\\b(casas?|chalets?)\\b") to "casa",
        Regex("(?iu)\\b(piezas?|habitaci[oó]n(es)?|cuartos?|rooms?)\\b") to "pieza",
        Regex("(?iu)\\b(caba[ñn]as?)\\b") to "cabaña",
        Regex("(?iu)\\b(oficinas?)\\b") to "oficina",
        Regex("(?iu)\\b(locales? comerciales?|locales?|bodegas?)\\b") to "local comercial",
        Regex("(?iu)\\b(terrenos?|sitios?|lotes?)\\b") to "terreno",
        Regex("(?iu)\\b(parcelas?)\\b") to "parcela"
    )

    fun parseRent(q: String): Rent {
        val what = propertyKinds.firstOrNull { it.first.containsMatchIn(q) }?.second ?: "propiedad"
        val daily = Regex("(?iu)por (d[ií]a|noche|semana|fin de semana|temporada)|diari[oa]s?|vacaciones|veraneo|temporada|airbnb|booking").containsMatchIn(q)
        val sale = Regex("(?iu)\\b(venta|vendo|vender|comprar|compra|bienes ra[ií]ces|cr[eé]dito hipotecario|hipoteca|subsidio)\\b").containsMatchIn(q) &&
            !Regex("(?iu)arriendo|arrendar|alquil|renta").containsMatchIn(q)
        val rooms = Regex("(?iu)\\b(\\d)\\s*(dormitorios?|habitaciones|piezas|cuartos|rec[aá]maras|ambientes|d\\b)").find(q)?.groupValues?.get(1)?.toIntOrNull()
        val furnished = Regex("(?iu)amoblad|amueblad").containsMatchIn(q)
        return Rent(what, daily, sale, rooms, furnished, placeOf(q))
    }

    /** «departamento en arriendo Ñuñoa», «casa por día Viña del Mar», «terreno en venta Concepción» */
    fun rentText(r: Rent, city: String): String {
        val w = if (r.what == "propiedad") (if (r.sale) "propiedades" else "departamento") else r.what
        val extra = listOfNotNull(r.rooms?.let { "$it dormitorios" }, if (r.furnished) "amoblado" else null).joinToString(" ")
        return listOf(w, extra, r.operation, r.place ?: city).filter { it.isNotBlank() }.joinToString(" ")
    }

    private class Portal(val name: String, val domain: String)

    private val RENT_PORTALS: Map<String, List<Portal>> = mapOf(
        "CL" to listOf(Portal("Portal Inmobiliario", "portalinmobiliario.com"), Portal("Toctoc", "toctoc.com"), Portal("Yapo", "yapo.cl")),
        "AR" to listOf(Portal("Zonaprop", "zonaprop.com.ar"), Portal("Argenprop", "argenprop.com")),
        "MX" to listOf(Portal("Inmuebles24", "inmuebles24.com"), Portal("Vivanuncios", "vivanuncios.com.mx"), Portal("Lamudi", "lamudi.com.mx")),
        "CO" to listOf(Portal("Fincaraíz", "fincaraiz.com.co"), Portal("Metrocuadrado", "metrocuadrado.com"), Portal("Ciencuadras", "ciencuadras.com")),
        "PE" to listOf(Portal("Urbania", "urbania.pe"), Portal("Adondevivir", "adondevivir.com")),
        "UY" to listOf(Portal("InfoCasas", "infocasas.com.uy"), Portal("Gallito", "gallito.com.uy")),
        "EC" to listOf(Portal("Plusvalía", "plusvalia.com")),
        "BO" to listOf(Portal("InfoCasas", "infocasas.com.bo"), Portal("UltraCasas", "ultracasas.com")),
        "PY" to listOf(Portal("InfoCasas", "infocasas.com.py"), Portal("Clasipar", "clasipar.paraguay.com")),
        "ES" to listOf(Portal("Idealista", "idealista.com"), Portal("Fotocasa", "fotocasa.es"), Portal("Habitaclia", "habitaclia.com")),
        "US" to listOf(Portal("Zillow", "zillow.com"), Portal("Apartments.com", "apartments.com"), Portal("Realtor.com", "realtor.com")),
        "DO" to listOf(Portal("SuperCasas", "supercasas.com"), Portal("Corotos", "corotos.com.do")),
        "CR" to listOf(Portal("Encuentra24", "encuentra24.com")), "PA" to listOf(Portal("Encuentra24", "encuentra24.com")),
        "GT" to listOf(Portal("Encuentra24", "encuentra24.com")), "HN" to listOf(Portal("Encuentra24", "encuentra24.com")),
        "SV" to listOf(Portal("Encuentra24", "encuentra24.com")), "NI" to listOf(Portal("Encuentra24", "encuentra24.com"))
    )

    /** Dominios de avisos inmobiliarios del país (para quedarse solo con fotos de avisos de verdad). */
    fun rentDomains(country: String, daily: Boolean): List<String> =
        RENT_PORTALS[country.uppercase()].orEmpty().map { it.domain } + listOfNotNull(ML[country.uppercase()]) +
            (if (daily) listOf("airbnb.", "booking.com") else emptyList())

    /** Portales del país con la búsqueda lista. */
    fun rentPortals(country: String, r: Rent, city: String): List<Pair<String, String>> {
        val text = rentText(r, city)
        val where = r.place ?: city
        val out = mutableListOf<Pair<String, String>>()
        if (r.daily && where.isNotBlank()) {
            out += "Airbnb" to "https://www.airbnb.com/s/" + e(where).replace("+", "%20") + "/homes"
            out += "Booking" to "https://www.booking.com/searchresults.es.html?ss=" + e(where)
        }
        RENT_PORTALS[country.uppercase()].orEmpty().forEach { p -> out += p.name to site(text, p.domain) }
        mercadoLibre(country, text)?.let { out += it }
        out += marketplace(text)
        if (out.size <= 2) out += "Más avisos en Google" to google(text)
        return out
    }

    fun agentQuery(lat: Double, lon: Double, radiusM: Int): String {
        val a = around(radiusM, lat, lon)
        return "[out:json][timeout:20];(nwr[\"office\"=\"estate_agent\"][\"name\"]$a;nwr[\"shop\"=\"estate_agent\"][\"name\"]$a;);out center tags 80;"
    }

    // =====================================================================
    // 🚗 VEHÍCULOS (autos y motos) Y ARRIENDO DE AUTOS
    // =====================================================================
    class Car(
        val kind: String,        // auto, camioneta, SUV, moto, furgón, camión
        val brand: String?,
        val model: String?,
        val year: String?,
        val moto: Boolean,
        val used: Boolean?,      // true usado · false nuevo · null no dijo
        val rental: Boolean,     // arriendo de autos (rent a car)
        val appraisal: Boolean,  // tasación
        val specs: Boolean,      // ficha técnica
        val place: String?
    ) {
        val name: String get() = listOfNotNull(brand, model, year).joinToString(" ")
    }

    /** Marcas (como se escriben bien) y cómo puede escribirlas el usuario. */
    private val BRANDS: List<Pair<String, Regex>> = listOf(
        "Toyota" to "toyota", "Chevrolet" to "chevrolet|chevy", "Hyundai" to "hyundai|hiunday", "Nissan" to "nissan|nisan",
        "Suzuki" to "suzuki", "Mazda" to "mazda", "Peugeot" to "peugeot|peugot", "Mitsubishi" to "mitsubishi", "Subaru" to "subaru",
        "Volkswagen" to "volkswagen|\\bvw\\b", "Renault" to "renault", "Citroën" to "citro[eë]n", "Jeep" to "jeep", "Chery" to "chery",
        "Haval" to "haval", "Great Wall" to "great wall", "Changan" to "changan", "BYD" to "\\bbyd\\b", "Geely" to "geely",
        "Jetour" to "jetour", "SsangYong" to "ssang ?yong|kgm", "Maxus" to "maxus", "Mahindra" to "mahindra", "DFSK" to "dfsk",
        "Kia" to "\\bkia\\b", "Tesla" to "tesla", "BMW" to "\\bbmw\\b", "Audi" to "\\baudi\\b", "Volvo" to "volvo", "Dodge" to "dodge",
        "Ford" to "\\bford\\b", "Honda" to "honda", "Fiat" to "\\bfiat\\b", "SEAT" to "\\bseat\\b", "Cupra" to "cupra", "MG" to "\\bmg\\b",
        "RAM" to "\\bram\\b", "Mercedes-Benz" to "mercedes", "Land Rover" to "land ?rover|range ?rover", "Porsche" to "porsche",
        "Lexus" to "lexus", "JAC" to "\\bjac\\b", "BAIC" to "\\bbaic\\b", "Dongfeng" to "dongfeng", "Foton" to "foton", "Omoda" to "omoda",
        "Jaecoo" to "jaecoo", "Exeed" to "exeed", "GAC" to "\\bgac\\b", "Opel" to "\\bopel\\b", "Škoda" to "[sš]koda", "Alfa Romeo" to "alfa romeo",
        "MINI" to "mini cooper", "Isuzu" to "isuzu", "Chrysler" to "chrysler", "Cadillac" to "cadillac", "GMC" to "\\bgmc\\b",
        "Yamaha" to "yamaha", "Kawasaki" to "kawasaki", "KTM" to "\\bktm\\b", "Bajaj" to "bajaj", "Royal Enfield" to "royal enfield",
        "Harley-Davidson" to "harley", "Ducati" to "ducati", "Benelli" to "benelli", "Keeway" to "keeway", "Zontes" to "zontes",
        "CFMoto" to "cf ?moto", "TVS" to "\\btvs\\b", "Hero" to "\\bhero\\b", "Triumph" to "triumph", "Vespa" to "vespa",
        "Italika" to "italika", "Motomel" to "motomel"
    ).map { (n, p) -> n to Regex("(?iu)(" + p + ")") }

    private val MOTO_BRANDS = setOf("Yamaha", "Kawasaki", "KTM", "Bajaj", "Royal Enfield", "Harley-Davidson", "Ducati", "Benelli",
        "Keeway", "Zontes", "CFMoto", "TVS", "Hero", "Triumph", "Vespa", "Italika", "Motomel")

    private val notModel = Regex("(?iu)^(usad[oa]s?|nuev[oa]s?|precios?|en|de|del|el|la|los|las|a[ñn]os?|cu[aá]nto|cuesta|vale|km|kms|a|para|con|y|o|que|" +
        "ficha|t[eé]cnica|tasaci[oó]n|venta|vendo|comprar|barat[oa]s?|segunda|mano|autom[aá]tic[oa]|mec[aá]nic[oa]|di[eé]sel|bencinera?|" +
        "gasolina|el[eé]ctric[oa]|h[ií]brid[oa]|cerca|aqu[ií]|hoy|por|favor|sale|tiene|es|me|mi|un|una|modelo|versi[oó]n|auto|autos|moto|motos|" +
        "camioneta|camionetas|suv|sed[aá]n|hatchback|concesionarias?|automotoras?|chile|argentina|m[eé]xico|per[uú]|colombia)$")

    fun parseCar(q: String): Car {
        val hit = BRANDS.mapNotNull { (n, rx) -> rx.find(q)?.let { Triple(n, it.range.first, it.range.last + 1) } }.minByOrNull { it.second }
        val brand = hit?.first
        val model = hit?.let { h ->
            Regex("(?iu)^[\\s-]*([\\p{L}0-9][\\p{L}0-9-]*)(?:\\s+([\\p{L}0-9][\\p{L}0-9-]*))?").find(q.substring(h.third))?.let { m ->
                val toks = listOf(m.groupValues[1], m.groupValues[2]).filter { it.isNotBlank() }
                    .takeWhile { !notModel.matches(it) && !Regex("^(19|20)\\d{2}$").matches(it) }
                toks.joinToString(" ") { t -> if (t.length <= 3 || t.any { it.isDigit() }) t.uppercase() else t.replaceFirstChar { it.titlecase() } }
                    .takeIf { it.isNotBlank() }
            }
        }
        val year = Regex("\\b(19[89]\\d|20[0-3]\\d)\\b").find(q)?.value
        val moto = brand in MOTO_BRANDS || Regex("(?iu)\\b(motos?|motocicletas?|scooters?|cuatrimotos?|motonetas?|enduro|\\d{2,4}\\s?cc)\\b").containsMatchIn(q)
        val used = when {
            Regex("(?iu)usad|segunda mano|seminuev|de ocasi[oó]n|kilometraje").containsMatchIn(q) -> true
            Regex("(?iu)\\bnuev[oa]s?\\b|0 ?km|cero kil[oó]metro").containsMatchIn(q) -> false
            else -> null
        }
        val kind = when {
            moto -> "moto"
            Regex("(?iu)camionetas?|pick-?ups?|\\b4x4\\b").containsMatchIn(q) -> "camioneta"
            Regex("(?iu)\\bsuvs?\\b|todoterreno").containsMatchIn(q) -> "SUV"
            Regex("(?iu)furg[oó]n|furgones|\\bvans?\\b|minivan").containsMatchIn(q) -> "furgón"
            Regex("(?iu)camion(es)?\\b|cami[oó]n\\b").containsMatchIn(q) -> "camión"
            Regex("(?iu)el[eé]ctric").containsMatchIn(q) -> "auto eléctrico"
            Regex("(?iu)h[ií]brid").containsMatchIn(q) -> "auto híbrido"
            else -> "auto"
        }
        val rental = Regex("(?iu)(arriendo|arrendar|alquiler|alquilar|renta|rentar)\\s+(de |un |una )?(autos?|veh[ií]culos?|camionetas?|carros?|coches?|motos?)|rent ?a ?car")
            .containsMatchIn(q)
        val selling = Regex("(?iu)\\b(vender|vendo) (mi|el|la|un|una)\\b").containsMatchIn(q)
        val appraisal = selling || Regex("(?iu)tasaci|tasar|aval[uú]o|cu[aá]nto (vale|me dan por|cuesta) mi|precio de mercado").containsMatchIn(q)
        val specs = Regex("(?iu)ficha|especificaci|\\bmotor\\b|consumo|rendimiento|potencia|caballos|\\bhp\\b|torque|dimensiones|maletero|airbags?|transmisi")
            .containsMatchIn(q)
        return Car(kind, brand, model, year, moto, if (selling && used == null) true else used, rental, appraisal, specs, placeOf(q))
    }

    /** «Toyota Yaris 2020 usado», «moto Yamaha FZ nueva», «auto usado» */
    fun carText(c: Car): String {
        val base = c.name.ifBlank { c.kind }
        val prefix = if (c.brand != null && !c.kind.startsWith("auto") && !base.lowercase().contains(c.kind.lowercase())) c.kind + " " else ""
        val fem = (if (c.name.isBlank() || prefix.isNotBlank()) c.kind else "auto") in setOf("moto", "camioneta")
        val cond = when (c.used) { true -> if (fem) " usada" else " usado"; false -> if (fem) " nueva" else " nuevo"; null -> "" }
        return (prefix + base + cond).trim()
    }

    private val CAR_PORTALS: Map<String, List<Portal>> = mapOf(
        "CL" to listOf(Portal("Chileautos", "chileautos.cl"), Portal("Kavak", "kavak.com"), Portal("Yapo", "yapo.cl")),
        "AR" to listOf(Portal("DeMotores", "demotores.com.ar"), Portal("Autocosmos", "autocosmos.com.ar"), Portal("Kavak", "kavak.com")),
        "MX" to listOf(Portal("Kavak", "kavak.com"), Portal("Seminuevos", "seminuevos.com"), Portal("Autocosmos", "autocosmos.com.mx")),
        "CO" to listOf(Portal("TuCarro", "tucarro.com.co"), Portal("Carroya", "carroya.com")),
        "PE" to listOf(Portal("Neoauto", "neoauto.com")),
        "UY" to listOf(Portal("Gallito", "gallito.com.uy")),
        "EC" to listOf(Portal("Patiotuerca", "patiotuerca.com")),
        "ES" to listOf(Portal("coches.net", "coches.net"), Portal("Wallapop", "wallapop.com"), Portal("AutoScout24", "autoscout24.es")),
        "US" to listOf(Portal("Autotrader", "autotrader.com"), Portal("Cars.com", "cars.com"), Portal("CarGurus", "cargurus.com")),
        "CR" to listOf(Portal("CRautos", "crautos.com"), Portal("Encuentra24", "encuentra24.com")),
        "PA" to listOf(Portal("Encuentra24", "encuentra24.com")), "GT" to listOf(Portal("Encuentra24", "encuentra24.com")),
        "HN" to listOf(Portal("Encuentra24", "encuentra24.com")), "SV" to listOf(Portal("Encuentra24", "encuentra24.com")),
        "NI" to listOf(Portal("Encuentra24", "encuentra24.com")), "DO" to listOf(Portal("Corotos", "corotos.com.do"))
    )

    /** Dónde tasar un usado, por país (sitios que existen). */
    private val APPRAISAL: Map<String, Portal> = mapOf(
        "CL" to Portal("Autofact", "autofact.cl"), "AR" to Portal("InfoAuto", "infoauto.com.ar"), "MX" to Portal("Libro Azul", "libroazul.com"),
        "CO" to Portal("Revista Motor", "revistamotor.com.co"), "US" to Portal("Kelley Blue Book", "kbb.com"), "ES" to Portal("coches.net", "coches.net")
    )

    fun carDomains(country: String): List<String> =
        CAR_PORTALS[country.uppercase()].orEmpty().map { it.domain } + listOfNotNull(ML[country.uppercase()])

    fun carPortals(country: String, c: Car): List<Pair<String, String>> {
        val text = carText(c)
        val out = mutableListOf<Pair<String, String>>()
        CAR_PORTALS[country.uppercase()].orEmpty().forEach { p -> out += p.name to site(text, p.domain) }
        mercadoLibre(country, text)?.let { out += it }
        out += marketplace(text)
        if (out.size <= 2) out += "Más avisos en Google" to google(text)
        return out
    }

    /** Tasación y ficha técnica (solo si hay modelo o lo pidió). */
    fun carExtras(country: String, c: Car): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val name = c.name.ifBlank { c.kind }
        if (c.appraisal || c.used == true || c.brand != null) {
            val ap = APPRAISAL[country.uppercase()]
            out += "💲 Tasación (cuánto vale según año y km)" to (ap?.let { site("tasación $name", it.domain) } ?: google("tasación $name usado precio"))
        }
        if (c.brand != null || c.specs) out += "📋 Ficha técnica (motor, potencia, consumo, seguridad)" to google("ficha técnica $name")
        return out
    }

    fun dealerQuery(lat: Double, lon: Double, radiusM: Int, c: Car, brandOnly: Boolean): String {
        val a = around(radiusM, lat, lon)
        val body = when {
            c.rental -> "nwr[\"amenity\"=\"car_rental\"][\"name\"]$a;"
            else -> {
                val shop = if (c.moto) "motorcycle" else "car"
                if (brandOnly && c.brand != null) {
                    val b = c.brand.substringBefore('-').replace(Regex("[^\\p{L}0-9 ]"), ".")
                    "nwr[\"shop\"=\"$shop\"][\"brand\"~\"$b\",i]$a;nwr[\"shop\"=\"$shop\"][\"name\"~\"$b\",i]$a;"
                } else "nwr[\"shop\"=\"$shop\"][\"name\"]$a;"
            }
        }
        return "[out:json][timeout:20];($body);out center tags 80;"
    }

    // =====================================================================
    // 🖼️ AVISOS CON FOTO (desde la búsqueda de imágenes: la foto + la página del aviso)
    // =====================================================================
    class Listing(val title: String, val pageUrl: String, val imageUrl: String) {
        val site: String get() = Regex("^https?://(?:www\\.)?([^/]+)").find(pageUrl)?.groupValues?.get(1).orEmpty()
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Lee las fotos de la búsqueda de imágenes de Bing con la página de donde salen (purl) y su título. */
    fun parseImageResults(html: String): List<Listing> {
        val out = mutableListOf<Listing>()
        Regex("\\bm=\"(\\{[^\"]*?\\})\"").findAll(html).forEach { mm ->
            val j = unescape(mm.groupValues[1])
            fun field(k: String) = Regex("\"$k\":\"(.*?)(?<!\\\\)\"").find(j)?.groupValues?.get(1)?.replace("\\/", "/")
                ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }.orEmpty()
            val purl = field("purl"); val murl = field("murl")
            if (purl.startsWith("http") && murl.startsWith("http")) out += Listing(field("t").replace(Regex("<[^>]+>"), "").trim(), purl, murl)
        }
        return out.distinctBy { it.pageUrl }
    }

    /** Solo avisos de portales del país (no la portada del portal ni páginas de búsqueda vacías). */
    fun onlyListings(list: List<Listing>, domains: List<String>): List<Listing> = list.filter { l ->
        val host = l.site.lowercase()
        val path = l.pageUrl.substringAfter(l.site, "").substringBefore('?')
        domains.any { d -> host == d || host.endsWith(".$d") || (d.endsWith(".") && host.contains(d)) } && path.length > 8
    }

    /** Título corto del aviso: «Departamento en arriendo 2D 1B Ñuñoa | Portal Inmobiliario» → «Departamento en arriendo 2D 1B Ñuñoa» */
    fun shortTitle(t: String): String = t.split(Regex("\\s+[|·–-]\\s+")).first().trim().take(90)

    // =====================================================================
    // 🏢 NEGOCIOS DEL MAPA (corredoras, concesionarias, automotoras, rent a car)
    // =====================================================================
    fun rankPlaces(list: List<NearbyFinder.Spot>): List<NearbyFinder.Spot> {
        val seen = HashSet<String>()
        return list.sortedWith(compareByDescending<NearbyFinder.Spot> { s ->
            (if (s.phone.isNotBlank()) 3 else 0) + (if (s.website.isNotBlank()) 2 else 0) + (if (s.address.isNotBlank()) 1 else 0) - s.distanceM / 2500
        }.thenBy { it.distanceM }).filter { seen.add(it.name.lowercase().replace(Regex("[^\\p{L}0-9]"), "")) }
    }

    fun placesBlock(header: String, spots: List<NearbyFinder.Spot>): String {
        if (spots.isEmpty()) return ""
        val sb = StringBuilder(header).append('\n')
        spots.forEachIndexed { i, s ->
            sb.append("\n").append(i + 1).append(". ").append(s.name).append(" — ").append(NearbyFinder.distanceLabel(s.distanceM)).append('\n')
            sb.append("   📍 ").append(s.address.ifBlank { "Dirección: toca el mapa" }).append('\n')
            if (s.phone.isNotBlank()) sb.append("   📞 ").append(s.phone).append('\n')
            if (s.hours.isNotBlank()) sb.append("   🕒 ").append(s.hours).append('\n')
            if (s.website.isNotBlank()) sb.append("   🌐 ").append(s.website).append('\n')
            sb.append("   🗺️ ").append(s.mapsUrl).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun mapsSearch(text: String): String = "https://www.google.com/maps/search/" + e(text)

    // =====================================================================
    // 🧩 BLOQUES FINALES
    // =====================================================================
    /** Avisos con foto: «🖼️ Foto 2 (abajo) — Título · 🔗 enlace» (photoNumbers = número de foto de cada aviso). */
    fun listingsBlock(header: String, listings: List<Listing>, photoNumbers: List<Int?>): String {
        if (listings.isEmpty()) return ""
        val sb = StringBuilder(header).append('\n')
        listings.forEachIndexed { i, l ->
            sb.append("\n").append(i + 1).append(". ").append(shortTitle(l.title).ifBlank { l.site })
            photoNumbers.getOrNull(i)?.let { sb.append(" · 🖼️ Foto ").append(it).append(" (abajo)") }
            sb.append("\n   🔗 ").append(l.pageUrl).append('\n')
        }
        sb.append("\n⚠️ Confirma en el aviso que siga disponible y el precio final antes de pagar o reservar.")
        return sb.toString().trimEnd()
    }

    fun linksBlock(header: String, links: List<Pair<String, String>>): String =
        if (links.isEmpty()) "" else header + "\n" + links.joinToString("\n") { (n, u) -> "   • $n: $u" }
}
