package com.paulrj.cumulusia.ai

import java.net.URLEncoder

/**
 * 🌐 REGLAS DE BÚSQUEDA WEB Y CONTEXTO EN TIEMPO REAL
 * Igual que con las noticias: cuando la app busca en internet le entrega a la IA, junto con los resultados,
 *  - la fecha y hora del teléfono, la ciudad/país y el idioma de la app,
 *  - QUÉ entregar según el tipo de pedido (31 categorías: cine, música, empleos, vuelos, medicina…),
 *  - y el formato de la respuesta.
 * Solo se inyectan las reglas de la categoría detectada (no las 31): así cabe hasta en un contexto de 2048
 * y los modelos pequeños no se confunden.
 *
 * Las portadas/afiches y los enlaces de YouTube (Música, Series, Películas, Cine) NO los escribe la IA:
 * los busca la APP y los pone debajo de la respuesta, igual que las fotos de las noticias (así nunca son inventados).
 */
object WebSearchRules {

    /** Categorías que llevan imagen + enlace de YouTube obligatorios. */
    enum class Media { MUSIC, MOVIE, SERIES }

    class Category(
        val num: Int,
        val name: String,
        val emoji: String,
        /** Qué entregar (texto de las reglas; {LUGAR} = ciudad y país del teléfono). */
        val rules: String,
        /** Busca priorizando la ciudad/país del usuario. */
        val local: Boolean = false,
        /** Novedades: si el usuario pide lo nuevo/estrenos, se agrega el año actual a la búsqueda. */
        val fresh: Boolean = false,
        val media: Media? = null
    ) {
        val title: String get() = "$emoji $name"
    }

    /** Borde de palabra que también entiende tildes y ñ («menú», «canción»). */
    private const val B = "(?:(?<=[\\p{L}\\p{N}])(?![\\p{L}\\p{N}])|(?<![\\p{L}\\p{N}])(?=[\\p{L}\\p{N}]))"

    private fun rx(vararg parts: String) = Regex("(?iu)(" + parts.joinToString("|") { it.replace("\\b", B) } + ")")

    // ------------------------------------------------------------------------
    // 📋 LAS 31 CATEGORÍAS (en el orden de la lista del usuario)
    // ------------------------------------------------------------------------
    val CINE = Category(1, "CINE", "🎟️",
        "- Cartelera y estrenos vigentes en las salas de cine de {LUGAR} (cines, horarios y precios si aparecen).\n" +
        "- Distingue claramente: estrenos de este año en salas, estrenos de streaming y reestrenos/clásicos.",
        local = true, fresh = true, media = Media.MOVIE)
    val VIDEOS = Category(2, "VIDEOS", "📹",
        "- Tendencias de video, tráilers recientes y contenido destacado de YouTube o plataformas de streaming.\n" +
        "- Da títulos precisos, el creador o canal y el contexto de la publicación (cuándo y de qué trata).",
        fresh = true)
    val MUSICA = Category(3, "MÚSICA", "🎵",
        "- Lanzamientos de sencillos, álbumes recientes y listas de éxitos (charts) locales o internacionales.\n" +
        "- OBLIGATORIO: cada canción o álbum con su portada y su enlace de YouTube o YouTube Music (los agrega la app).",
        fresh = true, media = Media.MUSIC)
    val EVENTOS = Category(4, "EVENTOS Y CONCIERTOS", "🎤",
        "- Conciertos, recitales, festivales, teatro y eventos culturales presenciales en {LUGAR}.\n" +
        "- Fechas exactas, recinto/lugar, disponibilidad y dónde se compran las entradas.",
        local = true, fresh = true)
    val NEGOCIOS = Category(5, "NEGOCIOS", "📈",
        "- Bolsa de valores, divisas e indicadores económicos locales e internacionales con los valores más recientes.\n" +
        "- Indica la fecha y hora de la última actualización de cada valor (tal como aparece en los resultados).")
    val VENTAS = Category(6, "VENTAS", "🏷️",
        "- Promociones, liquidaciones, eventos de venta masiva (Cyber, Black Friday) y descuentos vigentes en tiendas y e-commerce de {LUGAR}.\n" +
        "- Precio normal, precio oferta, tienda y hasta cuándo dura, si aparecen.",
        local = true, fresh = true)
    val EMPLEOS = Category(7, "BÚSQUEDA DE EMPLEOS", "💼",
        "- Ofertas laborales vigentes y vacantes en {LUGAR} (LinkedIn, Laborum, Computrabajo, etc.): cargo, empresa, requisitos y rango de sueldo.\n" +
        "- Explica cómo postular y entrega el enlace de la oferta oficial si aparece en los resultados.",
        local = true, fresh = true)
    val VESTUARIO = Category(8, "VESTUARIO", "👕",
        "- Catálogos actuales, ropa, calzado, colecciones de temporada y tendencias de moda.\n" +
        "- Precios en la moneda local de {LUGAR} y la tienda de cada uno.",
        local = true, fresh = true)
    val MAQUILLAJE = Category(9, "MAQUILLAJE", "💄",
        "- Productos de belleza y cosmética, lanzamientos de marcas y comparación de precios en catálogos activos de {LUGAR}.",
        local = true, fresh = true)
    val TIENDAS = Category(10, "TIENDAS", "🏬",
        "- Horarios de atención, direcciones de sucursales y disponibilidad de stock en retail y tiendas por departamento de {LUGAR}.",
        local = true)
    val SUPERMERCADOS = Category(11, "SUPERMERCADOS", "🛒",
        "- Compara precios de la canasta básica, abarrotes y despacho, y las ofertas/folletos de la semana en las principales cadenas de {LUGAR}.\n" +
        "- Una línea por producto: producto — precio — supermercado.",
        local = true, fresh = true)
    val COMIDA = Category(12, "COMIDA", "🍽️",
        "- Recetas, menús y cartas de restaurantes con precios, opiniones gastronómicas y recomendaciones locales en {LUGAR}.",
        local = true)
    val DELIVERY = Category(13, "DELIVERY", "🛵",
        "- Tarifas, zonas de cobertura, promociones activas y disponibilidad de las apps y servicios de reparto vigentes en {LUGAR}.",
        local = true, fresh = true)
    val VIAJES = Category(14, "VIAJES", "🧳",
        "- Precios de pasajes y paquetes turísticos, requisitos de viaje (documentos, visas) y destinos recomendados con tarifas actualizadas.",
        fresh = true)
    val VUELOS = Category(15, "VUELOS Y LÍNEAS AÉREAS", "✈️",
        "- Estado de vuelos, itinerarios, promociones de pasajes aéreos, políticas de equipaje y servicios de las aerolíneas que operan en {LUGAR} o a nivel internacional.",
        fresh = true)
    val BUSES = Category(16, "RECORRIDO DE BUSES URBANOS", "🚌",
        "- Trayectos, paraderos, frecuencias, horarios, cambios de ruta y tarifas del transporte público urbano e interurbano de {LUGAR}.",
        local = true)
    val VACACIONES = Category(17, "LUGARES DE VACACIONES", "🏖️",
        "- Destinos turísticos y atractivos locales e internacionales, mejor época para viajar y actividades disponibles según la temporada actual.")
    val HOTELES = Category(18, "HOTELES", "🏨",
        "- Disponibilidad, tarifa por noche en moneda local, servicios incluidos, ubicación y valoraciones de hoteles y alojamientos.")
    val ARRIENDOS = Category(19, "ARRIENDOS DE CASAS Y DEPARTAMENTOS", "🏠",
        "- Precios de arriendos diarios o mensuales, propiedades, corredoras y portales inmobiliarios activos en {LUGAR}.",
        local = true)
    val VEHICULOS = Category(20, "COMPRA Y VENTA DE VEHÍCULOS", "🚗",
        "- Precios de autos y motos nuevos y usados, fichas técnicas, tasaciones, ofertas de concesionarias y portales automotrices de {LUGAR}.",
        local = true)
    val CLIMA = Category(21, "TIEMPO Y CLIMA", "🌤️",
        "- Pronóstico oficial de hoy y los próximos días para {LUGAR}: temperatura mínima/máxima, probabilidad de lluvia y radiación UV.",
        local = true)
    val TECNOLOGIA = Category(22, "TECNOLOGÍA", "📱",
        "- Especificaciones, lanzamientos de dispositivos, actualizaciones de software y sistemas operativos, y precios de gadgets y hardware en tiendas especializadas.",
        fresh = true)
    val ASTRONOMIA = Category(23, "ASTRONOMÍA", "🔭",
        "- Eventos astronómicos vigentes o próximos (fases lunares, eclipses, lluvias de estrellas, conjunciones, cometas) y descubrimientos recientes.\n" +
        "- Recomendaciones de observación (horas, instrumentos o astrofotografía) según la fecha y el hemisferio de {LUGAR}.",
        fresh = true)
    val MEDICINA = Category(24, "MEDICINA", "⚕️",
        "- Avances médicos, guías de salud actualizadas e información de medicamentos o tratamientos con base científica.\n" +
        "- No diagnostiques ni indiques dosis. La app agrega al final el aviso de que no reemplaza la consulta médica: no lo repitas.")
    val SERIES = Category(25, "SERIES TV", "📺",
        "- Catálogos y estrenos semanales en plataformas de streaming y TV disponibles en {LUGAR}.\n" +
        "- Nuevas temporadas, fechas de emisión de episodios, renovaciones o cancelaciones.\n" +
        "- OBLIGATORIO: póster oficial y enlace del tráiler en YouTube de cada serie (los agrega la app).",
        fresh = true, media = Media.SERIES)
    val PELICULAS = Category(26, "PELÍCULAS", "🎬",
        "- Sinopsis, reparto, director, críticas/puntuaciones actuales, premios y dónde verla (salas o plataformas de {LUGAR}).\n" +
        "- OBLIGATORIO: afiche oficial y enlace del tráiler en YouTube de cada película (los agrega la app).",
        fresh = true, media = Media.MOVIE)
    val JUEGOS = Category(27, "JUEGOS", "🎮",
        "- Eventos, temporadas, notas de parches, guías, sensibilidades/configuraciones óptimas y lanzamientos para móvil, PC o consolas.",
        fresh = true)
    val GOOGLE_PLAY = Category(28, "GOOGLE PLAY", "▶️",
        "- Apps y juegos de la Play Store: novedades, actualizaciones, versiones compatibles, parches, requisitos y valoraciones en la región actual.",
        fresh = true)
    val YOUTUBE = Category(29, "YOUTUBE", "📺",
        "- Videos más vistos, canales destacados, transmisiones en vivo, tendencias del momento y lanzamientos de creadores según la región del usuario.",
        fresh = true)
    val YOUTUBE_MUSIC = Category(30, "YOUTUBE MUSIC", "🎧",
        "- Listas más escuchadas (Top 50), álbumes en tendencia, sencillos recién añadidos y recomendaciones musicales actuales de la plataforma.\n" +
        "- OBLIGATORIO: cada canción o álbum con su portada y su enlace de YouTube Music (los agrega la app).",
        fresh = true, media = Media.MUSIC)
    val GENERAL = Category(31, "CONSULTA GENERAL", "🔎",
        "- Responde con precisión y claridad, con información comprobable de los resultados (trámites, historia, deportes, tutoriales, dudas generales).")

    val ALL: List<Category> = listOf(CINE, VIDEOS, MUSICA, EVENTOS, NEGOCIOS, VENTAS, EMPLEOS, VESTUARIO, MAQUILLAJE,
        TIENDAS, SUPERMERCADOS, COMIDA, DELIVERY, VIAJES, VUELOS, BUSES, VACACIONES, HOTELES, ARRIENDOS, VEHICULOS,
        CLIMA, TECNOLOGIA, ASTRONOMIA, MEDICINA, SERIES, PELICULAS, JUEGOS, GOOGLE_PLAY, YOUTUBE, YOUTUBE_MUSIC, GENERAL)

    // ------------------------------------------------------------------------
    // 🧭 DETECCIÓN: primero lo más específico (así «YouTube Music» no cae en «Música», ni «hotel» en «Vacaciones»)
    // ------------------------------------------------------------------------
    private val ORDER: List<Pair<Regex, Category>> = listOf(
        rx("youtube music", "yt music") to YOUTUBE_MUSIC,
        rx("youtube", "youtubers?", "\\byt\\b") to YOUTUBE,
        rx("google play", "play ?store", "tienda de google", "\\bapks?\\b", "\\bapps?\\b", "\\baplicaci[oó]n(es)?\\b") to GOOGLE_PLAY,
        rx("\\bvideojuegos?\\b", "\\bjuegos?\\b", "\\bgamers?\\b", "\\bgaming\\b", "free fire", "fortnite", "minecraft", "roblox", "pubg",
            "call of duty", "warzone", "valorant", "league of legends", "genshin", "clash (royale|of clans)", "brawl stars", "\\bea fc\\b",
            "\\bps[45]\\b", "playstation", "xbox", "nintendo", "\\bsteam\\b", "\\bparches?\\b", "sensibilidad(es)?", "pase de batalla",
            "battle pass", "\\bconsolas?\\b") to JUEGOS,
        rx("cartelera", "\\bcines?\\b", "en salas", "cinemark", "cin[eé]polis", "cineplanet", "hoyts", "cinemundo",
            "horarios? de (las )?pel[ií]culas?") to CINE,
        rx("\\bseries?\\b", "nueva temporada", "\\btemporada \\d", "cap[ií]tulos?", "episodios?", "\\banime\\b", "doramas?",
            "k-?dramas?", "telenovelas?") to SERIES,
        rx("pel[ií]culas?", "\\bfilms?\\b", "\\bfilmes?\\b", "tr[aá]iler", "taquilla", "sinopsis", "\\bactor\\b", "\\bactriz\\b",
            "los oscar", "premios? oscar", "\\bcintas?\\b") to PELICULAS,
        rx("netflix", "\\bhbo\\b", "\\bmax\\b", "disney ?\\+|disney plus", "prime video", "apple tv", "paramount", "crunchyroll",
            "star ?\\+", "qu[eé] (puedo )?ver (hoy|en|esta)") to SERIES,
        rx("m[uú]sicas?", "canci[oó]n(es)?", "\\btemas? (nuevos?|musicales?|de)\\b", "[aá]lbum(es)?", "\\bdiscos? nuevos?",
            "sencillos?", "\\bsingles?\\b", "cantantes?", "bandas? (de m[uú]sica|musical(es)?|de rock)", "reggaet[oó]n",
            "\\brap\\b", "\\btrap\\b", "\\brock\\b", "\\bpop\\b", "cumbia", "bachata", "\\bsalsa\\b", "playlists?", "spotify",
            "top 50", "billboard", "\\bcharts?\\b", "lo m[aá]s escuchado") to MUSICA,
        rx("\\bvideos?\\b", "tiktok", "twitch", "\\breels?\\b", "\\bvirales?\\b") to VIDEOS,
        rx("\\bvuelos?\\b", "aerol[ií]neas?", "pasajes? a[eé]reos?", "\\bavi[oó]n(es)?\\b", "aeropuertos?", "equipaje",
            "\\blatam\\b", "sky airline", "jetsmart", "aerom[eé]xico", "avianca", "iberia", "check-?in") to VUELOS,
        rx("\\bhotel(es)?\\b", "hostal(es)?", "alojamientos?", "hospedajes?", "caba[ñn]as?", "airbnb", "booking", "\\bresorts?\\b",
            "\\bmotel(es)?\\b") to HOTELES,
        rx("arriendos?", "arrendar", "alquiler(es)?", "alquilar", "inmobiliari", "corredoras? de propiedades", "\\bpropiedades\\b",
            "departamentos? (en|para) (arriendo|venta)", "casas? (en|para) (arriendo|venta|alquiler)", "portal ?inmobiliario", "toctoc") to ARRIENDOS,
        rx("\\bmicros?\\b", "\\bbus(es)?\\b", "recorridos?", "paraderos?", "red movilidad", "transantiago", "\\bmetro\\b",
            "transporte p[uú]blico", "colectivos?", "tarjeta bip", "\\bbip\\b") to BUSES,
        rx("\\bclima\\b", "\\btiempo (hace|har[aá]|va a hacer|para (hoy|ma[ñn]ana))", "pron[oó]stico", "llover", "\\blluvias?\\b",
            "temperatura", "radiaci[oó]n", "[ií]ndice uv") to CLIMA,
        rx("astronom", "eclipses?", "luna llena", "fases? (lunar|de la luna)", "lluvias? de estrellas", "meteoros?", "cometas?",
            "planetas?", "conjunci[oó]n", "telescopios?", "galaxias?", "\\bnasa\\b", "james webb", "astrofotograf", "constelaci",
            "superluna", "equinoccio", "solsticio", "auroras? (austral|boreal)") to ASTRONOMIA,
        rx("medicamentos?", "remedios?", "s[ií]ntomas?", "enfermedad(es)?", "tratamientos?", "\\bdosis\\b", "vacunas?",
            "efectos? secundarios?", "dolor de", "pastillas?", "f[aá]rmacos?", "\\bvirus\\b", "bacterias?", "c[aá]ncer", "diabetes",
            "presi[oó]n (alta|arterial)", "\\bsalud\\b", "avances? m[eé]dicos?") to MEDICINA,
        rx("conciertos?", "recitales?", "festival(es)?", "\\beventos?\\b", "obras? de teatro", "\\bteatro\\b", "\\bshows?\\b",
            "entradas (para|de)", "ticketmaster", "puntoticket", "passline", "\\bgira\\b", "stand ?up", "\\bferias?\\b", "exposici[oó]n") to EVENTOS,
        rx("\\btrabajos?\\b", "empleos?", "vacantes?", "ofertas? (de trabajo|laborales?|de empleo)", "postular", "curr[ií]culum",
            "\\bcv\\b", "sueldos?", "salarios?", "linkedin", "laborum", "computrabajo", "bumeran", "pr[aá]ctica profesional") to EMPLEOS,
        rx("\\bbolsa\\b", "\\bacciones\\b", "\\bipsa\\b", "dow jones", "nasdaq", "s&p", "d[oó]lar", "\\beuros?\\b", "divisas?",
            "tipo de cambio", "\\buf\\b", "\\butm\\b", "\\bipc\\b", "inflaci[oó]n", "tasas? de inter[eé]s", "banco central", "bitcoin",
            "criptomonedas?", "\\bcobre\\b", "petr[oó]leo", "indicadores? econ[oó]mic", "\\beconom[ií]a\\b", "\\bmercados?\\b(?! ?libre)") to NEGOCIOS,
        rx("delivery", "a domicilio", "pedidos ?ya", "rappi", "uber eats", "didi food", "cornershop", "\\breparto\\b") to DELIVERY,
        rx("supermercados?", "\\bs[uú]per\\b", "canasta b[aá]sica", "abarrotes", "\\bl[ií]der\\b", "\\bjumbo\\b", "unimarc",
            "santa isabel", "tottus", "acuenta", "walmart", "soriana", "carrefour", "mercadona", "folletos?") to SUPERMERCADOS,
        rx("recetas?", "\\bcomidas?\\b", "restaurant(es|e)?", "restoranes?", "\\bmen[uú]\\b", "\\bcarta de\\b", "cocinar",
            "\\bplatos?\\b", "almuerzo", "\\bcena\\b", "desayuno", "sushi", "pizzas?", "hamburguesas?", "empanadas?",
            "gastronom", "picadas?", "d[oó]nde comer") to COMIDA,
        rx("maquillaje", "cosm[eé]tic", "labial(es)?", "r[ií]mel", "pesta[ñn]as?", "skin ?care", "\\bcremas?\\b", "perfumes?",
            "belleza", "sephora", "maybelline", "l'?or[eé]al", "esmaltes?") to MAQUILLAJE,
        rx("\\bropa\\b", "vestuario", "vestidos?", "zapatillas", "zapatos", "calzado", "poleras?", "camisas?", "pantalones?",
            "\\bjeans\\b", "chaquetas?", "parkas?", "polerones?", "\\bmoda\\b", "outfits?", "\\bzara\\b", "h&m") to VESTUARIO,
        rx("\\bautos?\\b", "veh[ií]culos?", "\\bcarros?\\b", "\\bcoches?\\b", "\\bmotos?\\b", "camionetas?", "\\bsuv\\b",
            "concesionari", "automotoras?", "chileautos", "\\byapo\\b", "kilometraje", "tasaci[oó]n") to VEHICULOS,
        rx("celulares?", "tel[eé]fonos?", "smartphones?", "iphone", "samsung", "galaxy", "xiaomi", "\\bpixel\\b", "notebooks?",
            "laptops?", "computador(es|a)?", "\\bpc\\b", "tablets?", "\\bipad\\b", "procesador(es)?", "tarjetas? de video", "\\bgpu\\b",
            "\\bram\\b", "\\bandroid \\d", "\\bios \\d", "windows", "actualizaci[oó]n de (software|sistema)", "gadgets?", "smartwatch",
            "aud[ií]fonos", "televisor(es)?", "smart ?tv", "tecnolog[ií]a", "snapdragon", "ficha t[eé]cnica") to TECNOLOGIA,
        rx("vacaciones", "vacacionar", "lugares? (para|de|que) (visitar|conocer|pasear|ir)", "\\bdestinos?\\b", "turismo", "tur[ií]stic", "atractivos", "qu[eé] (hacer|visitar|conocer) en",
            "\\bplayas?\\b", "escapadas?", "fin de semana largo", "veranear") to VACACIONES,
        rx("\\bviajes?\\b", "viajar", "\\bpasajes?\\b", "paquetes? tur[ií]sticos?", "requisitos (de|para) (viaje|viajar|entrar)",
            "\\bvisas?\\b", "pasaporte", "\\btours?\\b", "cruceros?") to VIAJES,
        rx("ofertas?", "descuentos?", "promoci[oó]n(es)?", "liquidaci[oó]n(es)?", "cyber", "black friday", "rebajas", "cupones?",
            "\\bventas?\\b", "barat[oa]s?", "cu[aá]nto (cuesta|vale|sale)", "precios? de") to VENTAS,
        rx("\\btiendas?\\b", "sucursal(es)?", "horarios? de atenci[oó]n", "\\bmall\\b", "centro comercial", "\\bstock\\b",
            "falabella", "\\bripley\\b", "\\bparis\\b", "\\bhites\\b", "la polar", "sodimac", "\\beasy\\b") to TIENDAS
    )

    /** Categorías donde se comparan precios, especificaciones o catálogos. */
    private val PRICE_CATS = setOf(5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 18, 19, 20, 22, 28, 31)

    /** Tipo de pedido (si no encaja en ninguna, CONSULTA GENERAL: la búsqueda igual se hace). */
    fun detect(prompt: String): Category {
        val p = prompt.lowercase()
        return ORDER.firstOrNull { it.first.containsMatchIn(p) }?.second ?: GENERAL
    }

    // ------------------------------------------------------------------------
    // 🔎 BÚSQUEDA: prioriza la ciudad/país del usuario y el año actual cuando pide novedades
    // ------------------------------------------------------------------------
    private val freshWords = rx("estrenos?", "\\bnuev[oa]s?\\b", "[uú]ltim[oa]s?", "recientes?", "lanzamientos?", "tendencias?",
        "\\btop\\b", "mejores", "\\bahora\\b", "\\bhoy\\b", "esta semana", "este mes", "\\bactual(es)?\\b", "vigentes?", "pr[oó]xim[oa]s?")

    fun searchQuery(cat: Category, cleanedQuery: String, place: String, year: Int): String {
        var q = cleanedQuery.trim()
        if (cat.local && place.isNotBlank() && !mentionsPlace(q, place)) q += " " + place.replace(",", "")
        if (cat.fresh && !Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(q) && freshWords.containsMatchIn(q.lowercase())) q += " $year"
        return q.take(200)
    }

    /** ¿El usuario ya nombró un lugar? («en Valparaíso», «de Lima», o su propia ciudad/país). */
    private fun mentionsPlace(q: String, place: String): Boolean {
        val low = q.lowercase()
        if (place.split(',').map { it.trim().lowercase() }.any { it.length >= 3 && low.contains(it) }) return true
        return Regex("\\b(en|de|para|cerca de)\\s+(el |la |los |las )?\\p{Lu}\\p{L}{2,}").containsMatchIn(q)
    }

    // ------------------------------------------------------------------------
    // 🧾 LO QUE SE LE INYECTA A LA IA (corto: cabe en un contexto de 2048)
    // ------------------------------------------------------------------------
    /**
     * @param dateTime «martes 13 de octubre de 2026, 19:40 (GMT-03:00)»
     * @param place    «Santiago, Chile»
     * @param language «español»
     * @param currency «peso chileno (CLP)» o ""
     */
    fun contextBlock(cat: Category, dateTime: String, place: String, language: String, currency: String): String {
        val lugar = place.ifBlank { "el país del usuario" }
        val sb = StringBuilder()
        sb.append("[CONTEXTO EN TIEMPO REAL]\n")
        sb.append("- Fecha y hora actual: ").append(dateTime).append('\n')
        sb.append("- Ubicación del usuario: ").append(lugar).append('\n')
        if (currency.isNotBlank() && cat.num in PRICE_CATS) sb.append("- Moneda local: ").append(currency).append('\n')
        sb.append("- Idioma de respuesta: ").append(language).append('\n')
        sb.append("Si pide algo local o actualizado (eventos, precios, ofertas), prioriza lo de ").append(lugar).append(".\n\n")
        sb.append("TIPO DE PEDIDO: ").append(cat.title).append('\n')
        sb.append("Qué entregar:\n").append(cat.rules.replace("{LUGAR}", lugar)).append("\n\n")
        sb.append("FORMATO:\n")
        sb.append("- Responde solo en ").append(language).append(", directo y ordenado: primero la respuesta y luego los detalles.\n")
        if (cat.num in PRICE_CATS) sb.append("- Para comparar precios, especificaciones o catálogos usa una lista: una línea por opción (nombre — precio — tienda o fecha).\n")
        if (cat.media != null) {
            sb.append("- Escribe cada título en su propia línea numerada, así: «1. Título — ")
                .append(if (cat.media == Media.MUSIC) "Artista" else "Director o plataforma").append(" (año)».\n")
            sb.append("- La portada/afiche y el enlace de YouTube de cada uno los pone la app debajo de tu respuesta: no escribas enlaces ni imágenes.\n")
        }
        sb.append("- Usa SOLO los resultados de internet: fechas, cifras, precios y nombres tal cual aparecen. No inventes enlaces, precios ni fechas; si algo no aparece, dilo.")
        return sb.toString()
    }

    // ------------------------------------------------------------------------
    // 🎬🎵 PORTADAS, AFICHES Y ENLACES DE YOUTUBE (los busca la app después de la respuesta)
    // ------------------------------------------------------------------------
    class MediaItem(val title: String, val by: String, val year: String) {
        val label: String get() = title + (if (by.isNotBlank()) " — $by" else "") + (if (year.isNotBlank()) " ($year)" else "")
    }

    /** Saca de la respuesta los títulos de la lista numerada («1. Título — Artista (2026)»). */
    fun mediaTitles(answer: String, max: Int = 4): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val seen = HashSet<String>()
        for (raw in answer.lines()) {
            if (out.size >= max) break
            val m = Regex("^\\s*(?:\\d{1,2}\\s*[.)\\-]|[-•*▪●]|#{1,4})\\s+(.+)$").find(raw) ?: continue
            var line = m.groupValues[1].replace(Regex("\\*\\*|__|[«»\"“”`]"), "").trim()
            if (Regex("(?i)https?://|^fuentes?\\b").containsMatchIn(line)) continue
            val year = Regex("\\((?:[^)]*?)\\b((?:19|20)\\d{2})\\b[^)]*\\)").find(line)?.groupValues?.get(1)
                ?: Regex("\\b((?:19|20)\\d{2})\\b").find(line)?.groupValues?.get(1) ?: ""
            line = line.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
            val parts = line.split(Regex("\\s+[—–-]\\s+|\\s+por\\s+|\\s+de\\s+(?=\\p{Lu})")).map { it.trim() }
            var title = parts.firstOrNull().orEmpty()
            // «Título: descripción larga…» (sin guion) → solo el título
            val colon = title.indexOf(": ")
            if (parts.size == 1 && colon in 2..70 && title.length > 60) title = title.substring(0, colon)
            title = title.trimEnd('.', ',', ';', ':').take(70).trim()
            val by = parts.getOrNull(1).orEmpty().split(Regex("[.:;]|\\s+[—–-]\\s+")).first().take(40).trim()
            if (title.length < 2 || title.split(' ').size > 12) continue
            if (!seen.add(title.lowercase())) continue
            out += MediaItem(title, by, year)
        }
        return out
    }

    /** Si la respuesta no trae lista, el título sale de la pregunta («el tráiler de Dune 2» → «Dune 2»). */
    fun titleFromQuestion(question: String): MediaItem? {
        val q = question.replace(Regex("(?i)\\b(dame|mu[eé]strame|busca(me)?|quiero( ver)?|el|la|los|las|un|una|de|del|tr[aá]iler|afiche|p[oó]ster|" +
            "portada|car[aá]tula|pel[ií]cula|serie|canci[oó]n|[aá]lbum|disco|en|youtube|music|oficial|nuev[oa]s?|por favor|link|enlace)\\b"), " ")
            .replace(Regex("[¿?¡!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (q.length >= 2 && q.split(' ').size <= 8) MediaItem(q, "", "") else null
    }

    fun videoQuery(media: Media, item: MediaItem): String = when (media) {
        Media.MUSIC -> "${item.title} ${item.by}".trim()
        Media.MOVIE -> "${item.title} ${item.year} tráiler oficial".replace(Regex("\\s+"), " ").trim()
        Media.SERIES -> "${item.title} serie tráiler oficial"
    }

    fun imageQuery(media: Media, item: MediaItem): String = when (media) {
        Media.MUSIC -> "${item.title} ${item.by} portada".replace(Regex("\\s+"), " ").trim()
        Media.MOVIE -> "${item.title} ${item.year} afiche película póster".replace(Regex("\\s+"), " ").trim()
        Media.SERIES -> "${item.title} serie póster oficial"
    }

    /** Enlace directo al video (YouTube Music para la categoría 30). */
    fun videoUrl(cat: Category, videoId: String): String =
        if (cat == YOUTUBE_MUSIC) "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"

    /** Respaldo si no se encontró el video exacto: la búsqueda en YouTube / YouTube Music (siempre funciona). */
    fun searchUrl(cat: Category, query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return if (cat == YOUTUBE_MUSIC) "https://music.youtube.com/search?q=$q" else "https://www.youtube.com/results?search_query=$q"
    }

    fun mediaHeader(media: Media): String = when (media) {
        Media.MUSIC -> "🎧 Escúchalas en YouTube:"
        Media.MOVIE -> "🎬 Tráilers en YouTube:"
        Media.SERIES -> "📺 Tráilers en YouTube:"
    }

    const val MEDICAL_NOTE = "⚕️ Esta información es solo informativa y no reemplaza la consulta con un profesional de la salud."
}

