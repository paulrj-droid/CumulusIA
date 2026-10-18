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
        "- Precios de pasajes (avión y bus), paquetes turísticos, requisitos de viaje (documentos, visas, vacunas, seguro) y destinos " +
        "recomendados con tarifas actualizadas.",
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
        "- Precios de arriendos diarios o mensuales, bienes raíces (casas, departamentos y terrenos en venta), corredoras y plataformas inmobiliarias activas en {LUGAR}.\n" +
        "- Separa arriendo mensual, arriendo por día y venta; cada aviso con su precio, tamaño y dónde está publicado.",
        local = true)
    val VEHICULOS = Category(20, "COMPRA Y VENTA DE VEHÍCULOS", "🚗",
        "- Precios de autos y motos nuevos y usados, fichas técnicas, tasaciones, ofertas de concesionarias y portales automotrices de {LUGAR}.\n" +
        "- Si pide arriendo de autos (rent a car): empresas, precio por día, qué incluye y requisitos.",
        local = true)
    val CLIMA = Category(21, "TIEMPO Y CLIMA", "🌤️",
        "- Pronóstico oficial de hoy y los próximos días para {LUGAR}: temperatura mínima/máxima, probabilidad de lluvia y radiación UV.",
        local = true)
    val TECNOLOGIA = Category(22, "TECNOLOGÍA", "📱",
        "- Especificaciones, lanzamientos de dispositivos, actualizaciones de software y sistemas operativos, y precios de gadgets y hardware en tiendas especializadas.\n" +
        "- También todo lo de tecnología: inteligencia artificial, robótica, chips, internet, ciberseguridad, realidad virtual, " +
        "tecnología antigua e historia de los inventos (con fechas, nombres y qué cambió).",
        fresh = true)
    val ASTRONOMIA = Category(23, "ASTRONOMÍA", "🔭",
        "- Eventos astronómicos vigentes o próximos (fases lunares, eclipses, lluvias de estrellas, conjunciones planetarias, visibilidad de cometas) y descubrimientos científicos recientes.\n" +
        "- Recomendaciones de observación (horas, instrumentación recomendada o astrofotografía) según la fecha y el hemisferio/ubicación de {LUGAR}.\n" +
        "- La app calcula y agrega debajo la Luna, los planetas visibles, las horas y los eclipses para la ubicación exacta: no inventes horas ni fechas.",
        fresh = true)
    val MEDICINA = Category(24, "MEDICINA", "⚕️",
        "- Avances médicos, guías de salud actualizadas, información sobre medicamentos o tratamientos con base científica.\n" +
        "- No diagnostiques ni indiques dosis. La app pone al inicio el aviso de que es solo informativa y no reemplaza la consulta con un profesional de la salud: no lo repitas.")
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
        "- Para cualquier tema no mencionado en la lista (trámites gubernamentales, historia local, deportes, tutoriales, dudas generales): " +
        "responde con precisión, claridad e información comprobable de los resultados de la web.")

    /** Datos que cambian (cartelera, precios, horarios, clima…): si internet no trae nada, la IA NO debe adivinarlos. */
    val CURRENT: Set<Category> by lazy { setOf(CINE, EVENTOS, VENTAS, EMPLEOS, TIENDAS, SUPERMERCADOS, DELIVERY, VIAJES, VUELOS,
        BUSES, HOTELES, ARRIENDOS, VEHICULOS, CLIMA, NEGOCIOS) }

    /** ¿La pregunta pide algo actual («hoy», «estrenos», «precio»…)? */
    fun asksCurrent(q: String): Boolean = Regex("(?iu)\\b(hoy|ahora|esta semana|este mes|este fin de semana|actual(es)?|[uú]ltim[oa]s?|estrenos?|cartelera|" +
        "precios?|cu[aá]nto (cuesta|vale|sale)|resultados?|en vivo|nuevos?|nuevas?)\\b").containsMatchIn(q)

    val ALL: List<Category> = listOf(CINE, VIDEOS, MUSICA, EVENTOS, NEGOCIOS, VENTAS, EMPLEOS, VESTUARIO, MAQUILLAJE,
        TIENDAS, SUPERMERCADOS, COMIDA, DELIVERY, VIAJES, VUELOS, BUSES, VACACIONES, HOTELES, ARRIENDOS, VEHICULOS,
        CLIMA, TECNOLOGIA, ASTRONOMIA, MEDICINA, SERIES, PELICULAS, JUEGOS, GOOGLE_PLAY, YOUTUBE, YOUTUBE_MUSIC, GENERAL)

    // ------------------------------------------------------------------------
    // 🧭 DETECCIÓN: primero lo más específico (así «YouTube Music» no cae en «Música», ni «hotel» en «Vacaciones»)
    // ------------------------------------------------------------------------
    private val ORDER: List<Pair<Regex, Category>> = listOf(
        rx("youtube music", "yt music") to YOUTUBE_MUSIC,
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
        // «YouTube» a secas va después de música/películas/series: «una canción de X en YouTube» es MÚSICA (con su enlace)
        rx("youtube", "youtubers?", "\\byt\\b") to YOUTUBE,
        rx("\\bvideos?\\b", "tiktok", "twitch", "\\breels?\\b", "\\bviral(es)?\\b") to VIDEOS,
        rx("\\bvuelos?\\b", "aerol[ií]neas?", "pasajes? a[eé]reos?", "\\bavi[oó]n(es)?\\b", "aeropuertos?", "equipaje",
            "\\blatam\\b", "sky airline", "jetsmart", "aerom[eé]xico", "avianca", "iberia", "check-?in") to VUELOS,
        rx("\\bhotel(es)?\\b", "hostal(es)?", "alojamientos?", "hospedajes?", "caba[ñn]as?", "airbnb", "booking", "\\bresorts?\\b",
            "\\bmotel(es)?\\b") to HOTELES,
        // 🔧 «cómo cambiar la rueda del auto» es un tutorial (consulta general con videos), no compra y venta de vehículos
        rx("c[oó]mo (se )?(cambi|arregl|repar|limpi|lav|pint|pul|revis|instal|desmont|encend|arranc|carg|ajust|calibr)\\p{L}* .{0,40}\\b(auto|autos|motor|ruedas?|neum[aá]ticos?|aceite|bater[ií]a|frenos?|moto|camioneta|buj[ií]as?|filtros?|luces|ampolletas?|parachoques|radiador|correa|embrague)\\b") to GENERAL,
        // 🔑 «arriendo de autos / rent a car» es VEHÍCULOS (no casas)
        rx("(arriendo|arrendar|alquiler|alquilar|renta|rentar)\\s+(de |un |una )?(autos?|veh[ií]culos?|camionetas?|carros?|coches?|motos?)\\b",
            "rent ?a ?car") to VEHICULOS,
        rx("arriendos?", "arrendar", "alquiler(es)?", "alquilar", "inmobiliari", "corredor(a|es|as)? de propiedades", "\\bpropiedades\\b",
            "departamentos? (en|para) (arriendo|venta)", "casas? (en|para) (arriendo|venta|alquiler)", "portal ?inmobiliario", "toctoc",
            "bienes ra[ií]ces", "\\bdeptos?\\b", "\\bdptos?\\b", "departamentos? (amoblados?|amueblados?|baratos?|por d[ií]a|en la playa)",
            "(piezas?|habitaci[oó]n(es)?|cuartos?) (en|para|de) (arriendo|alquiler|renta)", "terrenos? (en |para )?(venta|arriendo|alquiler)",
            "venta de terrenos?", "\\bparcelas?\\b",
            "(comprar|venta de|vender|vendo) (una |un |mi )?(casas?|departamentos?|deptos?|propiedad(es)?|terrenos?|parcelas?|sitios?)",
            "casas? (baratas?|en venta)", "\\binmuebles?\\b", "\\brentar (una |un )?(casa|departamento|depto|cuarto|habitaci)",
            "gastos comunes", "zonaprop", "inmuebles24", "fincara[ií]z", "urbania", "idealista", "fotocasa", "\\bdividendo\\b",
            "cr[eé]dito hipotecario") to ARRIENDOS,
        // 🧳 Pasajes de bus a otra ciudad, paquetes y requisitos son VIAJES (no micros ni vacaciones)
        rx("pasajes? (de |en )?bus(es)?", "bus(es)? interurbanos?", "(viajar|viaje) en bus", "terminal de buses",
            "paquetes? tur[ií]sticos?", "requisitos (de|para) (viaje|viajar|entrar)", "\\bvisas?\\b", "pasaporte") to VIAJES,
        rx("\\bmicros?\\b", "\\bbus(es)?\\b", "recorridos?", "paraderos?", "red movilidad", "transantiago", "\\bmetro\\b",
            "transporte p[uú]blico", "colectivos?", "tarjeta bip", "\\bbip\\b", "\\bl[ií]neas? [a-z]?\\d{1,3}[a-z]?\\b",
            "biotr[eé]n", "trolebus(es)?", "metro ?tren", "\\btren (urbano|de cercan[ií]as|suburbano)",
            "metrob[uú]s", "\\btransmilenio\\b", "\\bsitp\\b", "\\bcombis?\\b", "\\brutas? de (bus|micro|cami[oó]n)",
            // 🚌 «horario de salida de la locomoción», «las liebres», «transporte urbano», «a qué hora pasa la micro»
            "locomoci[oó]n", "\\bliebres?\\b", "transporte (urbano|colectivo|interurbano)", "\\btransportes?\\b(?! de carga)",
            "horarios? de salida", "salidas? de (la |las |los )?(micro|liebre|colectivo|tren|metro)",
            "a qu[eé] hora (pasa|sale|parte|llega) (la |el )?(micro|bus|liebre|colectivo|tren|metro)",
            // 🚆 Trenes y autobuses de cualquier país: «tren a Barcelona», «horario de los trenes», «autobús a Guadalajara»
            "\\btren(es)? (a|al|hacia|para|de|desde)\\b", "horarios? (del|de los) trenes", "\\bautob[uú]s(es)?\\b", "\\b[oó]mnibus\\b",
            "\\bcercan[ií]as\\b", "\\brenfe\\b") to BUSES,
        // 💼 «trabajo de médico», «empleo en farmacia» son EMPLEOS (no medicina)
        rx("\\btrabajos? (de|en|para|como)\\b", "\\bempleos?\\b", "vacantes?", "ofertas? (de trabajo|laborales?|de empleo)", "postular",
            "curr[ií]culum", "computrabajo", "laborum") to EMPLEOS,
        // ☄️ «lluvia de estrellas» es ASTRONOMÍA (no el clima)
        rx("lluvias? de (estrellas|meteoros)", "estrellas fugaces", "perseidas", "gem[ií]nidas", "le[oó]nidas", "ori[oó]nidas", "cuadr[aá]ntidas",
            "l[ií]ridas", "eta acu[aá]ridas", "delta acu[aá]ridas", "drac[oó]nidas", "t[aá]uridas", "[uú]rsidas") to ASTRONOMIA,
        rx("\\bclima\\b", "\\btiempo (hace|har[aá]|va a hacer|para (hoy|ma[ñn]ana))", "pron[oó]stico", "llover", "\\blluvias?\\b",
            "temperatura", "radiaci[oó]n", "[ií]ndice uv") to CLIMA,
        rx("astronom", "eclipses?", "luna llena", "fases? (lunar|de la luna)", "lluvias? de estrellas", "meteoros?", "cometas?",
            "planetas?", "conjunci[oó]n", "telescopios?", "galaxias?", "\\bnasa\\b", "james webb", "astrofotograf", "constelaci",
            "superluna", "equinoccio", "solsticio", "auroras? (austral|boreal)", "\\bla luna\\b", "\\bluna (llena|nueva|creciente|menguante|de sangre|azul)",
            "cuarto (creciente|menguante)", "j[uú]piter", "saturno", "\\bmarte\\b", "\\bvenus\\b", "\\burano\\b", "neptuno",
            "v[ií]a l[aá]ctea", "nebulosas?", "agujeros? negros?", "exoplanetas?", "\\bestrellas\\b", "el cielo (esta noche|de hoy|hoy|nocturno)",
            "qu[eé] (se )?(puede )?(ver|hay) en el cielo", "observar (el cielo|las estrellas)", "estaci[oó]n espacial", "spacex", "\\bartemis\\b",
            "\\bhubble\\b", "alineaci[oó]n (de )?(los )?planetas") to ASTRONOMIA,
        rx("medicamentos?", "remedios?", "s[ií]ntomas?", "enfermedad(es)?", "tratamientos?", "\\bdosis\\b", "vacunas?",
            "efectos? secundarios?", "dolor de", "pastillas?", "f[aá]rmacos?", "\\bvirus\\b", "bacterias?", "c[aá]ncer", "diabetes",
            "presi[oó]n (alta|arterial)", "\\bsalud\\b", "avances? m[eé]dicos?",
            "fiebre", "\\bgripe\\b", "resfr[ií]o", "\\bcovid", "influenza", "alergias?", "\\basma\\b", "hipertensi[oó]n", "colesterol",
            "migra[ñn]as?", "ansiedad", "depresi[oó]n", "embarazo", "infecci[oó]n", "gastritis", "tiroides", "artritis", "artrosis",
            "osteoporosis", "alzheimer", "parkinson", "epilepsia", "\\bvih\\b", "hepatitis", "dengue", "sarampi[oó]n", "varicela",
            "neumon[ií]a", "bronquitis", "otitis", "conjuntivitis", "insomnio", "anemia", "infarto", "\\bacv\\b", "c[aá]lculos? renal(es)?",
            "\\bhernias?\\b", "apendicitis", "para qu[eé] sirve (el|la|los|las) \\p{L}+(ina|ol|eno|ona|ato|ida|am|ex)\\b",
            "farmacias?", "\\bboticas?\\b", "hospital(es)?", "cl[ií]nicas?", "\\burgencias?\\b", "\\bcesfam\\b", "consultorios?",
            "\\bsapu\\b", "centros? (m[eé]dicos?|de salud)", "\\bm[eé]dicos?\\b", "contraindicaci", "efectos? adversos?",
            "(?<![\\p{L}])(" + HealthFinder.DRUGS.joinToString("|") + ")(?![\\p{L}])") to MEDICINA,
        rx("conciertos?", "recital(es)?", "festival(es)?", "\\beventos?\\b", "obras? de teatro", "\\bteatro\\b", "\\bshows?\\b",
            "entradas (para|de)", "ticketmaster", "puntoticket", "passline", "\\bgira\\b", "stand ?up", "\\bferias?\\b", "exposici[oó]n",
            // 🎉 «qué hago este fin de semana», «panoramas para hoy»
            "panoramas?", "qu[eé] (hago|hacer|puedo hacer|hay para hacer) (hoy|ma[ñn]ana|esta noche|este (fin de semana|s[aá]bado|domingo|finde))",
            "actividades (para )?(hoy|este fin de semana|el fin de semana)") to EVENTOS,
        rx("\\btrabajos?\\b", "empleos?", "vacantes?", "ofertas? (de trabajo|laborales?|de empleo)", "postular", "curr[ií]culum",
            "\\bcv\\b", "sueldos?", "salarios?", "linkedin", "laborum", "computrabajo", "bumeran", "pr[aá]ctica profesional") to EMPLEOS,
        rx("\\bbolsa\\b", "\\bacciones\\b", "\\bipsa\\b", "dow jones", "nasdaq", "s&p", "d[oó]lar", "\\beuros?\\b", "divisas?",
            "tipo de cambio", "\\buf\\b", "\\butm\\b", "\\bipc\\b", "inflaci[oó]n", "tasas? de inter[eé]s", "banco central", "bitcoin",
            "criptomonedas?", "\\bcobre\\b", "petr[oó]leo", "indicadores? econ[oó]mic", "\\beconom[ií]a\\b", "\\bmercados?\\b(?! ?libre)") to NEGOCIOS,
        rx("delivery", "a domicilio", "pedidos ?ya", "rappi", "uber eats", "didi food", "cornershop", "\\breparto\\b") to DELIVERY,
        rx("supermercados?", "\\bs[uú]per\\b", "canasta b[aá]sica", "abarrotes", "\\bl[ií]der\\b", "\\bjumbo\\b", "unimarc",
            "santa isabel", "tottus", "acuenta", "walmart", "soriana", "carrefour", "mercadona", "folletos?") to SUPERMERCADOS,
        rx("recetas?", "\\bcomidas?\\b", "\\bcomer\\b", "hambre", "antojo", "almorzar", "\\bcenar\\b", "desayunar", "restaurant(es|e)?", "restoranes?", "\\bmen[uú]\\b", "\\bcarta de\\b", "cocinar",
            "\\bplatos?\\b", "almuerzo", "\\bcena\\b", "desayuno", "sushi", "pizzas?", "hamburguesas?", "empanadas?",
            "gastronom", "picadas?", "d[oó]nde comer") to COMIDA,
        rx("maquillaje", "cosm[eé]tic", "labial(es)?", "r[ií]mel", "pesta[ñn]as?", "skin ?care", "\\bcremas?\\b", "perfumes?",
            "belleza", "sephora", "maybelline", "l'?or[eé]al", "esmaltes?") to MAQUILLAJE,
        rx("\\bropa\\b", "vestuario", "vestidos?", "zapatillas", "zapatos", "calzado", "poleras?", "camisas?", "pantal[oó]n(es)?",
            "\\bjeans\\b", "chaquetas?", "parkas?", "poler[oó]n(es)?", "\\bmoda\\b", "outfits?", "\\bzara\\b", "h&m") to VESTUARIO,
        rx("\\bautos?\\b", "veh[ií]culos?", "\\bcarros?\\b", "\\bcoches?\\b", "\\bmotos?\\b", "camionetas?", "\\bsuv\\b",
            "concesionari", "automotoras?", "chileautos", "\\byapo\\b", "kilometraje", "tasaci[oó]n",
            "autom[oó]vil(es)?", "motocicletas?", "\\bscooters?\\b", "cuatrimotos?", "\\bpick-?ups?\\b", "\\b4x4\\b", "\\bsed[aá]n\\b",
            "hatchback", "\\bkavak\\b", "autofact", "neoauto", "tucarro", "seminuevos?", "patiotuerca", "demotores",
            // Marcas (las que también son otra cosa, solo con un modelo: «Ford Ranger», «Honda Civic», «MG ZS»…)
            "toyota", "chevrolet", "hyundai", "nissan", "suzuki", "mazda", "peugeot", "mitsubishi", "subaru", "volkswagen", "renault",
            "citro[eë]n", "\\bjeep\\b", "\\bchery\\b", "\\bhaval\\b", "great wall", "changan", "\\bbyd\\b", "\\bgeely\\b", "jetour",
            "ssang ?yong", "\\bmaxus\\b", "mahindra", "\\bdfsk\\b", "\\bkia\\b", "\\bbmw\\b", "\\baudi\\b", "\\bvolvo\\b", "porsche",
            "\\blexus\\b", "land ?rover", "range ?rover", "\\bomoda\\b", "jaecoo", "\\bfoton\\b", "\\bisuzu\\b", "kawasaki", "\\bktm\\b",
            "\\bbajaj\\b", "royal enfield", "harley[- ]?davidson", "ducati", "benelli", "keeway", "zontes", "italika", "motomel",
            "tesla model", "cybertruck", "\\bford (ranger|territory|explorer|f-?150|escape|ecosport|bronco|mustang|fiesta|focus|ka|maverick|everest|expedition|edge|raptor)\\b",
            "\\bhonda (civic|accord|cr-?v|hr-?v|wr-?v|fit|city|pilot|cb ?\\d+|xr ?\\d+|cbr|navi|wave|pcx|xre|cg ?\\d+|africa twin)",
            "\\bfiat (500|argo|cronos|mobi|uno|palio|strada|toro|fiorino|pulse|fastback)\\b", "\\bseat (ibiza|le[oó]n|arona|ateca|tarraco)\\b",
            "\\bmg ?(zs|hs|3|5|gt|rx5|zx|one|marvel|cyberster)\\b", "\\bram (700|1000|1200|1500|2500|v700|rampage)\\b",
            "\\bmercedes[- ]benz\\b", "\\bmercedes (clase|amg|sprinter|gl[abce]|c ?\\d{3}|e ?\\d{3})", "\\byamaha (r ?\\d|mt|fz|xtz|ybr|crypton|nmax|aerox|tenere|tracer|bws)",
            "mini cooper") to VEHICULOS,
        rx("celular(es)?", "tel[eé]fonos?", "smartphones?", "iphone", "samsung", "galaxy", "xiaomi", "\\bpixel\\b", "notebooks?",
            "laptops?", "computador(es|a)?", "\\bpc\\b", "tablets?", "\\bipad\\b", "procesador(es)?", "tarjetas? de video", "\\bgpu\\b",
            "\\bram\\b", "\\bandroid \\d", "\\bios \\d", "windows", "actualizaci[oó]n de (software|sistema)", "gadgets?", "smartwatch",
            "aud[ií]fonos", "televisor(es)?", "smart ?tv", "tecnolog[ií]a", "snapdragon", "ficha t[eé]cnica",
            // 🤖 Inteligencia artificial, robótica, chips y todo lo de tecnología (actual y antigua)
            "inteligencia artificial", "\\bia generativa", "chat ?gpt", "openai", "\\bgemini\\b", "copilot", "\\bllms?\\b",
            "modelos? de (lenguaje|ia)", "machine learning", "aprendizaje autom[aá]tico", "redes neuronales", "deep learning",
            "rob[oó]tica", "\\brobots?\\b", "androides?\\b", "humanoides?", "\\bdrones?\\b", "impresoras? 3d", "impresi[oó]n 3d",
            "realidad (virtual|aumentada|mixta)", "\\bvr\\b", "metaverso", "computaci[oó]n cu[aá]ntica", "cu[aá]ntic[oa]s? (computador|chip)",
            "semiconductores?", "\\bchips?\\b", "microprocesador", "\\bnvidia\\b", "\\bintel\\b", "\\bamd\\b", "\\bqualcomm\\b",
            "ciberseguridad", "hackers?", "\\b5g\\b", "\\b6g\\b", "fibra [oó]ptica", "internet de las cosas", "\\biot\\b", "\\bsoftware\\b",
            "sistemas? operativos?", "\\blinux\\b", "\\bmacos\\b", "\\bmacbook\\b", "\\bapple\\b", "\\bmicrosoft\\b", "\\bmotorola\\b",
            "\\bhuawei\\b", "\\bnokia\\b", "\\boppo\\b", "\\bhonor\\b", "\\brealme\\b", "\\bconsolas? retro", "tecnolog[ií]a (antigua|retro|vieja)",
            "historia de (la computaci[oó]n|las computadoras|internet|los celulares|la tecnolog[ií]a|la inform[aá]tica)", "inventos?",
            "inform[aá]tica", "computaci[oó]n", "walkman", "disquetes?", "\\bvhs\\b", "casetes?", "transistor(es)?", "electr[oó]nica") to TECNOLOGIA,
        rx("vacaciones", "vacacionar", "lugares? (para|de|que) (visitar|conocer|pasear|ir)", "\\bdestinos?\\b", "turismo", "tur[ií]stic", "atractivos", "qu[eé] (hacer|visitar|conocer) en",
            "\\bplayas?\\b", "escapadas?", "fin de semana largo", "veranear") to VACACIONES,
        rx("\\bviajes?\\b", "viajar", "\\bpasajes?\\b", "paquetes? tur[ií]sticos?", "requisitos (de|para) (viaje|viajar|entrar)",
            "\\bvisas?\\b", "pasaporte", "\\btours?\\b", "cruceros?") to VIAJES,
        rx("ofertas?", "descuentos?", "promoci[oó]n(es)?", "liquidaci[oó]n(es)?", "cyber", "black friday", "rebajas", "cup[oó]n(es)?",
            "\\bventas?\\b", "barat[oa]s?", "cu[aá]nto (cuesta|vale|sale)", "precios? de") to VENTAS,
        rx("\\btiendas?\\b", "sucursal(es)?", "horarios? de atenci[oó]n", "\\bmall\\b", "centro comercial", "\\bstock\\b",
            "falabella", "\\bripley\\b", "\\bparis\\b", "\\bhites\\b", "la polar", "sodimac", "\\beasy\\b") to TIENDAS
    )

    /** Categorías donde se comparan precios, especificaciones o catálogos. */
    private val PRICE_CATS = setOf(5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 18, 19, 20, 22, 28, 31)

    /** Tipo de pedido (si no encaja en ninguna, CONSULTA GENERAL: la búsqueda igual se hace). */
    fun detect(prompt: String): Category {
        val p = prompt.lowercase()
        // 🌍 Primero en español; si no, en inglés, portugués, ruso, chino, coreano y japonés (los idiomas de la app)
        //    (si la pregunta no está en español, primero los otros idiomas: «météo Paris» es clima, no la tienda Paris)
        val spanish = runCatching { looksSpanish(p) }.getOrDefault(true)
        val first = if (spanish) ORDER else FOREIGN_ORDER
        val second = if (spanish) FOREIGN_ORDER else ORDER
        return first.firstOrNull { it.first.containsMatchIn(p) }?.second
            ?: second.firstOrNull { it.first.containsMatchIn(p) }?.second ?: GENERAL
    }

    /** Palabras sin bordes (chino, japonés y coreano no separan las palabras con espacios). */
    private fun cjk(vararg parts: String) = parts.joinToString("|")

    /**
     * 🌍 Los temas en los otros idiomas de la app: 🇺🇸 inglés · 🇧🇷🇵🇹 portugués · 🇷🇺 ruso · 🇨🇳 chino · 🇰🇷 coreano · 🇯🇵 japonés.
     * (Así «flights to New York», «voos para Lisboa», «погода в Москве» o «東京 天気» usan las páginas de ese tema y país.)
     */
    private val FOREIGN_ORDER: List<Pair<Regex, Category>> by lazy { listOf(
        rx("movie theaters?", "cinemas?", "showtimes", "movies? playing", "filmes? em cartaz", "sess[õo]es de cinema", "kinoteatr",
            "кинотеатр", "сеанс", "афиша кино") to CINE,
        Regex("(?iu)" + cjk("电影院", "电影票", "上映", "영화관", "상영시간", "영화 예매", "映画館", "上映スケジュール")) to CINE,
        rx("flight status", "status do voo", "статус рейса", "\\bflights?\\b", "airlines?", "plane tickets?", "airfare", "\\bvoos?\\b", "passage(m|ns) a[ée]rea",
            "companhias? a[ée]reas?", "авиабилет", "\\bрейс", "перел[её]т") to VUELOS,
        Regex("(?iu)" + cjk("航班", "机票", "항공권", "항공편", "비행기표", "フライト", "航空券", "飛行機")) to VUELOS,
        rx("\\bbus(es)?\\b", "subway", "\\bmetro\\b", "train schedule", "public transit", "greyhound", "[ôo]nibus", "metr[ôo]", "rodovi[áa]ria",
            "автобус", "метро", "электричк", "маршрут") to BUSES,
        Regex("(?iu)" + cjk("公交", "地铁", "火车", "长途汽车", "버스", "지하철", "기차", "バス", "電車", "地下鉄", "時刻表", "乗換", "乗り換え")) to BUSES,
        rx("hotels?", "accommodation", "lodging", "hospedagem", "pousadas?", "отел", "гостиниц") to HOTELES,
        Regex("(?iu)" + cjk("酒店", "宾馆", "호텔", "숙소", "ホテル", "宿泊", "旅館")) to HOTELES,
        rx("weather", "forecast", "previs[ãa]o do tempo", "como est[áa] o tempo", "погод", "прогноз") to CLIMA,
        Regex("(?iu)" + cjk("天气", "날씨", "天気")) to CLIMA,
        rx("grocer(y|ies)", "supermarkets?", "supermercados?", "супермаркет", "продукт") to SUPERMERCADOS,
        Regex("(?iu)" + cjk("超市", "마트", "슈퍼마켓", "スーパー")) to SUPERMERCADOS,
        rx("food delivery", "entrega de comida", "доставк[аи] еды") to DELIVERY,
        Regex("(?iu)" + cjk("外卖", "배달", "デリバリー", "出前")) to DELIVERY,
        rx("recipes?", "restaurants?", "where to eat", "receitas?", "restaurantes?", "onde comer", "рецепт", "ресторан", "где поесть") to COMIDA,
        Regex("(?iu)" + cjk("食谱", "菜谱", "餐厅", "美食", "레시피", "맛집", "식당", "レシピ", "レストラン", "グルメ")) to COMIDA,
        rx("\\bjobs?\\b", "job openings", "hiring", "careers", "\\bvagas?\\b", "empregos?", "ваканси", "\\bработ[аыу]\\b") to EMPLEOS,
        Regex("(?iu)" + cjk("招聘", "工作机会", "求职", "채용", "일자리", "구인", "求人", "転職")) to EMPLEOS,
        rx("concerts?", "event tickets", "\\bevents?\\b", "ingressos?", "\\bshows?\\b", "концерт", "билеты на", "мероприяти") to EVENTOS,
        Regex("(?iu)" + cjk("演唱会", "门票", "演出", "콘서트", "공연", "コンサート", "ライブ", "イベント")) to EVENTOS,
        rx("apartments? for rent", "\\brent\\b", "real estate", "houses? for sale", "aluguel", "im[óo]veis", "apartamentos?", "аренд", "квартир", "недвижимост") to ARRIENDOS,
        Regex("(?iu)" + cjk("租房", "房价", "二手房", "월세", "전세", "부동산", "賃貸", "不動産")) to ARRIENDOS,
        rx("used cars?", "new cars?", "\\bcars?\\b", "carros?", "автомобил", "машин[аыу]", "подержанн") to VEHICULOS,
        Regex("(?iu)" + cjk("汽车", "二手车", "자동차", "중고차", "中古車", "新車")) to VEHICULOS,
        rx("vacation", "things to do", "tourist attractions", "f[ée]rias", "pontos tur[íi]sticos", "o que fazer", "отпуск", "достопримечательност", "куда поехать") to VACACIONES,
        Regex("(?iu)" + cjk("旅游", "景点", "여행지", "관광지", "観光", "旅行先")) to VACACIONES,
        rx("\\bvisa\\b", "passport", "\\bvisto\\b", "passaporte", "\\bвиз[аы]\\b", "загранпаспорт", "travel requirements") to VIAJES,
        Regex("(?iu)" + cjk("签证", "护照", "비자", "여권", "ビザ", "パスポート")) to VIAJES,
        rx("makeup", "cosmetics", "lipstick", "maquiagem", "cosm[ée]ticos", "batom", "косметик", "макияж", "помад") to MAQUILLAJE,
        Regex("(?iu)" + cjk("化妆品", "口红", "화장품", "메이크업", "립스틱", "化粧品", "コスメ", "口紅")) to MAQUILLAJE,
        rx("clothes", "clothing", "sneakers", "shoes", "roupas?", "t[êe]nis", "sapatos", "одежд", "кроссовк", "обув") to VESTUARIO,
        Regex("(?iu)" + cjk("衣服", "服装", "运动鞋", "의류", "운동화", "洋服", "スニーカー", "옷", "服屋", "服を買")) to VESTUARIO,
        rx("deals", "discounts?", "\\bsales?\\b", "black friday", "promo[çc][ãa]o", "promo[çc][õo]es", "desconto", "скидк", "распродаж") to VENTAS,
        Regex("(?iu)" + cjk("优惠", "打折", "促销", "双十一", "할인", "세일", "특가", "セール", "割引")) to VENTAS,
        rx("store hours", "opening hours", "opening times", "\\bstores?\\b", "\\bshops?\\b", "\\blojas?\\b", "магазин", "часы работы") to TIENDAS,
        Regex("(?iu)" + cjk("商店", "营业时间", "매장", "영업시간", "店舗", "営業時間")) to TIENDAS,
        rx("stocks?", "stock market", "exchange rate", "economy", "bolsa de valores", "a[çc][õo]es", "cota[çc][ãa]o", "акци[ия]", "бирж", "курс (доллар|валют|евро)") to NEGOCIOS,
        Regex("(?iu)" + cjk("股票", "股市", "汇率", "주식", "증시", "환율", "株価", "為替", "日経平均")) to NEGOCIOS,
        rx("astronomy", "eclipse", "meteor shower", "planets?", "astronomia", "chuva de meteoros", "астрономи", "затмени", "звездопад", "космос") to ASTRONOMIA,
        Regex("(?iu)" + cjk("天文", "日食", "月食", "流星雨", "천문", "일식", "월식", "유성우", "流星群")) to ASTRONOMIA,
        rx("pharmacy", "drugstore", "symptoms?", "medicine", "medication", "hospital", "farm[áa]cia", "rem[ée]dio", "sintomas", "лекарств", "симптом", "аптек", "больниц") to MEDICINA,
        Regex("(?iu)" + cjk("药店", "症状", "医院", "药品", "약국", "증상", "병원", "의약품", "薬局", "病院", "薬")) to MEDICINA,
        rx("smartphones?", "\\bphones?\\b", "laptops?", "artificial intelligence", "\\bai\\b", "robots?", "technology", "\\btech\\b", "celular",
            "intelig[êe]ncia artificial", "tecnologia", "смартфон", "ноутбук", "искусственн", "технолог", "робот") to TECNOLOGIA,
        Regex("(?iu)" + cjk("手机", "笔记本电脑", "人工智能", "科技", "机器人", "스마트폰", "노트북", "인공지능", "로봇", "スマホ", "パソコン", "人工知能", "ロボット")) to TECNOLOGIA,
        rx("tv series", "tv shows?", "\\bseries\\b", "s[ée]ries?", "temporada", "сериал") to SERIES,
        Regex("(?iu)" + cjk("电视剧", "드라마", "ドラマ")) to SERIES,
        rx("\\bmovies?\\b", "\\bfilms?\\b", "trailer", "filmes?", "фильм") to PELICULAS,
        Regex("(?iu)" + cjk("电影", "영화", "映画")) to PELICULAS,
        rx("video ?games?", "\\bgames?\\b", "\\bps5\\b", "xbox", "nintendo", "\\bjogos?\\b", "\\bигр[аыу]\\b") to JUEGOS,
        Regex("(?iu)" + cjk("游戏", "게임", "ゲーム")) to JUEGOS,
        rx("music", "songs?", "albums?", "playlist", "m[úu]sicas?", "can[çc][ãa]o", "музык", "песн") to MUSICA,
        Regex("(?iu)" + cjk("音乐", "歌曲", "专辑", "음악", "노래", "앨범", "音楽", "アルバム")) to MUSICA,
        rx("\\bapps?\\b", "aplicativos?", "приложени") to GOOGLE_PLAY,
        Regex("(?iu)" + cjk("应用", "앱", "어플", "アプリ")) to GOOGLE_PLAY,
        // 🇫🇷🇩🇪🇮🇹 francés, alemán e italiano
        rx("s[ée]ances? de cin[ée]ma", "salles? de cin[ée]ma", "kinoprogramm", "\\bkino\\b", "programmazione (del )?cinema", "orari (dei )?film") to CINE,
        rx("\\bvols?\\b", "billets? d'avion", "\\bfl[üu]ge?\\b", "\\bvoli\\b", "\\bvolo\\b", "compagnie a[ée]rienne", "fluggesellschaft") to VUELOS,
        rx("\\btrains?\\b", "\\bm[ée]tro\\b", "\\bz[üu]ge?\\b", "fahrplan", "\\bbahn\\b", "\\btreni?\\b", "orari (dei )?(treni|bus|autobus)", "\\bstra[ßs]enbahn\\b") to BUSES,
        rx("h[ôo]tels?", "unterkunft", "alberghi", "albergo", "hot[ée]is") to HOTELES,
        rx("m[ée]t[ée]o", "\\bwetter\\b", "\\bmeteo\\b", "previsioni del tempo") to CLIMA,
        rx("supermarch[ée]", "faire les courses", "supermarkt", "lebensmittel", "supermercati") to SUPERMERCADOS,
        rx("livraison de repas", "lieferdienst", "consegna (a domicilio|cibo)", "essen (bestellen|liefern)", "lieferando") to DELIVERY,
        rx("recettes?", "rezepte?", "ricett[ae]", "ristorant[ei]", "o[ùu] manger", "wo essen") to COMIDA,
        rx("emplois?", "offres? d'emploi", "stellenangebote", "\\blavoro\\b", "offerte di lavoro") to EMPLEOS,
        rx("\\bkonzerte?\\b", "\\bconcerti\\b", "billetterie", "biglietti") to EVENTOS,
        rx("location appartement", "\\bimmobilier", "\\bwohnung", "\\bmiete\\b", "\\bimmobili\\b", "\\baffitto\\b") to ARRIENDOS,
        rx("voitures?", "gebrauchtwagen", "\\bautos? usat[ae]\\b", "auto d'occasione") to VEHICULOS,
        rx("\\bvacances\\b", "\\burlaub\\b", "\\bvacanze\\b", "que faire [àa]", "sehensw[üu]rdigkeiten", "cosa vedere") to VACACIONES,
        rx("passeport", "reisepass", "passaporto") to VIAJES,
        rx("maquillage", "\\bkosmetik\\b", "\\btrucco\\b", "\\bmake-?up\\b") to MAQUILLAJE,
        rx("v[êe]tements", "\\bkleidung\\b", "abbigliamento") to VESTUARIO,
        rx("\\bsoldes\\b", "\\bangebote\\b", "\\brabatt", "\\bofferte\\b", "\\bsconti\\b") to VENTAS,
        rx("\\bmagasins?\\b", "horaires d'ouverture", "[öo]ffnungszeiten", "\\bnegozi\\b", "orari di apertura") to TIENDAS,
        rx("\\bbourse\\b", "\\bb[öo]rse\\b", "\\baktien\\b", "\\bborsa\\b", "\\bazioni\\b", "taux de change", "wechselkurs") to NEGOCIOS,
        rx("astronomie", "[ée]clipse", "finsternis", "eclissi", "sternschnuppen", "stelle cadenti") to ASTRONOMIA,
        rx("pharmacie", "apotheke", "sympt[ôo]mes", "symptome", "sintomi", "m[ée]dicament", "medikament", "\\bfarmac[oi]\\b") to MEDICINA,
        rx("intelligence artificielle", "k[üu]nstliche intelligenz", "intelligenza artificiale", "\\bhandys?\\b (preis|kaufen|test)", "ordinateur", "t[ée]l[ée]phone portable") to TECNOLOGIA,
        rx("\\bstaffel\\b", "serie tv") to SERIES,
        rx("jeux? vid[ée]o", "\\bspiele\\b", "videogiochi") to JUEGOS,
        rx("musique", "chansons?", "\\bmusik\\b", "\\blieder\\b", "canzon[ei]") to MUSICA
    ) }

    // ------------------------------------------------------------------------
    // ▶️ PEDIDOS DIRECTOS A YOUTUBE: «dame una canción de Amar Azul en YouTube», «pon Bailando de Enrique»,
    //    «el tráiler de Dune 3»… La APP busca en YouTube y entrega los enlaces reales con su miniatura
    //    (como las noticias: la IA no puede negarse ni inventar enlaces).
    // ------------------------------------------------------------------------
    enum class YtKind { MUSIC, TRAILER, VIDEO }

    class YtRequest(val query: String, val kind: YtKind, val count: Int, val label: String, val icon: String)

    private val ytWord = rx("youtube", "\\byt\\b")

    /** Cualquier cosa que se ve o se escucha: así la búsqueda en YouTube sirve para TODO tema, no solo para algunos. */
    private val ytMediaNoun = rx("\\bvideos?\\b", "video ?clips?", "pel[ií]culas?", "\\bpelis?\\b", "\\bseries?\\b", "documental(es)?",
        "tr[aá]ilers?", "canci[oó]n(es)?", "m[uú]sica", "[aá]lbum(es)?", "\\bdiscos?\\b", "k-?dramas?", "doramas?", "\\banimes?\\b",
        "caricaturas?", "dibujos animados", "tutorial(es)?", "\\bcursos? (de|del|para)\\b", "\\bclases? (de|del|para)\\b", "rese[ñn]as?",
        "\\breviews?\\b", "unboxing", "gameplays?", "podcasts?", "entrevistas?", "cortometrajes?", "cap[ií]tulos?", "episodios?",
        "telenovelas?", "stand ?up", "karaoke", "playlists?", "\\bmix\\b", "enganchados?", "cumbias?", "reggaet[oó]n", "baladas?",
        "\\bshorts?\\b", "\\bclips?\\b", "transmisi[oó]n", "en vivo")
    /** Pedir algo: «dame», «quiero», «muéstrame», «busca», «recomiéndame», «pásame el link»… */
    private val ytRequestVerb = rx("\\bdame\\b", "\\bquiero\\b", "quer[ií]a", "querr[ií]a", "quisiera", "mu[eé]strame", "ens[eé][ñn]ame", "\\bbusca(me|r)?\\b",
        "b[uú]scame", "\\bpon(me|eme|er)?\\b", "reproduc", "p[aá]same", "m[aá]ndame", "\\btienes\\b", "recomi[eé]nda(me)?",
        "recomi[eé]ndame", "sugi[eé]reme", "me (das|pasas|puedes|podr[ií]as|muestras|mandas|recomiendas|pones)", "necesito",
        "\\blinks?\\b", "enlaces?", "para (ver|escuchar|o[ií]r|aprender)", "comp[aá]rteme", "d[eé]jame", "\\ben videos?\\b")
    private val ytSingular = rx("\\b(una|alguna|otra) (canci[oó]n|pel[ií]cula|peli|serie|cumbia|balada|clase|entrevista|rese[ñn]a|" +
        "telenovela|caricatura|playlist)", "\\b(un|alg[uú]n|otro) (video|videoclip|tr[aá]iler|[aá]lbum|disco|documental|tutorial|curso|anime|" +
        "k-?drama|dorama|cap[ií]tulo|episodio|podcast|clip|short|cortometraje|karaoke|mix|enganchado)\\b")
    private val ytHasVideoWord = rx("\\bvideos?\\b", "documental", "tutorial", "\\bclips?\\b")
    /** Esto se responde mejor buscando en la web (tendencias, canales, preguntas sobre YouTube…) o no es para reproducir. */
    /** Preguntas SOBRE YouTube (no piden ver nada): «¿cuánto paga YouTube?», «mi YouTube no carga»… → búsqueda web normal. */
    private val ytAboutYoutube = rx("qu[eé] es youtube", "c[oó]mo (subir|descargar|monetizar|crear|funciona)", "descargar", "premium",
        "suscriptores", "cu[aá]nto (paga|pagan|gana|ganan|cuesta|vale)", "qui[eé]n (cre[oó]|fund[oó]|es (el )?due[ñn]o)", "monetiz",
        "suscrib", "\\bcuenta\\b", "contrase[ñn]a", "bloque", "\\berror", "no (funciona|carga|abre|me deja)", "\\bpol[ií]tica",
        "derechos de autor", "copyright", "c[oó]digo", "\\bapi\\b", "modo (oscuro|claro)")
    /** Crear algo o trabajar con un archivo tuyo («escríbeme una canción», «resume este video»): nunca es buscar en YouTube. */
    private val ytCreateOrFile = rx("\\bescr[ií]be(me|lo|la|nos)?\\b", "\\bcomp[oó]n(me|lo|la)?\\b", "\\bcr[eé]a(me|lo|la)?\\b",
        "\\binventa(me|lo|la)?\\b", "\\bhaz(me)?\\b", "\\bredacta(me|lo|la)?\\b", "\\bgenera(me|lo|la)?\\b",
        "\\btrad[uú]ce(me|lo|la)?\\b", "\\banal[ií]za(me|lo|la)?\\b", "\\bexpl[ií]ca(me|lo|la)?\\b", "qu[eé] significa",
        "qui[eé]n canta", "de qu[eé] (trata|habla)",
        "(este|ese|esta|esa) (video|canci[oó]n|audio|archivo)", "adjunt", "\\bres[uú]me(me|lo|la)?\\b", "\\btranscr[ií]be(me|lo|la)?\\b",
        "\\bsubtit[uú]la(me|lo|la)?\\b", "\\bed[ií]ta(me|lo|la)?\\b", "\\brecorta(me|lo|la)?\\b", "\\bcomprime(me|lo|la)?\\b",
        "\\bconvierte(me|lo|la)?\\b", "\\bsube(me|lo|la)?\\b")
    /** Sin nombrar YouTube, esto tampoco es para ver o escuchar (tendencias, ajustes de la app, «serie de pasos»…). */
    private val ytNotDirect = rx("m[aá]s vist[oa]s", "tendencias?", "\\bcanales\\b", "youtubers?", "\\bletras?\\b", "\\btop\\b",
        // 🎟️ El cine (cartelera, salas, estrenos en cines) se responde con la cartelera y los cines cercanos
        "\\bcines?\\b", "cartelera", "en salas", "estrenos? (en|de) (cine|salas)", "cinemark", "cineplanet", "cin[eé]polis", "hoyts", "muvix", "cinemex",
        "alarma", "recordatorio", "temporizador", "cron[oó]metro", "bot[oó]n", "pantalla", "\\bapps?\\b", "aplicaci", "funci[oó]n",
        "\\bcolor", "\\bfondo\\b", "\\btexto\\b", "imagen", "\\bfotos?\\b", "\\blogo", "[ií]cono", "\\bmodo\\b",
        "oscuro", "\\bclaro\\b", "idioma", "brillo", "volumen", "\\bwi-?fi\\b", "\\bnotas?\\b",
        // «serie» y «disco» que no son para ver ni escuchar
        "serie de (pasos|n[uú]meros|fibonacci|taylor|fourier|ejercicios|preguntas|ideas|instrucciones|datos|tiempo)", "disco duro", "\\bssd\\b")

    /**
     * Pedidos de YouTube sin un nombre («películas de YouTube», «series en YouTube»): se busca lo que se ve allí,
     * así siempre llegan videos con su enlace y su miniatura.
     */
    private fun genericYtSearch(q: String): String? {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return when {
            Regex("^(pel[ií]culas?|pelis?)$").matches(q) -> "películas completas en español"
            Regex("^series?$").matches(q) -> "series completas en español"
            Regex("^(k-?dramas?|doramas?)$").matches(q) -> "$q completos sub español"
            Regex("^animes?$").matches(q) -> "anime completo sub español"
            Regex("^documental(es)?$").matches(q) -> "documentales completos en español"
            Regex("^(videos?|clips?|shorts?)$").matches(q) -> "videos en tendencia"
            Regex("^(canci[oó]n(es)?|temas?|m[uú]sica)$").matches(q) -> "música más escuchada $year"
            Regex("^noticias?$").matches(q) -> "noticias de hoy en vivo"
            else -> null
        }
    }

    /** «ponme X», «reproduce X», «quiero escuchar X»: siempre es para reproducir. */
    private val ytStrongPlay = rx("^\\s*(oye\\s+|por favor\\s+)?(pon(me|eme)?|reproduce(me)?)\\b", "quiero (escuchar|o[ií]r)", "para (escuchar|o[ií]r)")

    /** Sin nombrar YouTube ni pedir un video, esto NO se busca allí (lo responden las noticias, el clima, el editor de fotos…). */
    private val ytOtherTopics = rx("noticias?", "titulares", "\\bclima\\b", "\\btiempo\\b", "pron[oó]stico", "d[oó]lar", "\\bhora\\b",
        "\\bfecha\\b", "archivos?", "documentos?", "\\bpdf\\b", "correos?", "mensajes?", "historial", "configuraci", "ajustes",
        "\\bmodelos?\\b", "precios?", "ofertas?", "\\bmapa\\b", "c[oó]mo llego", "resultados?", "\\bmarcador\\b")

    /** «quiero una de Enrique Iglesias», «dame otra de Maná», «ponme uno de Shakira». */
    private val ytOneOf = Regex("(?iu)^(oye\\s+|por favor\\s+|y\\s+|ahora\\s+)?(quiero|quisiera|dame|ponme|p[aá]same|m[aá]ndame|" +
        "me (das|pasas|mandas|pones|puedes (dar|pasar|poner))|busca(me)?)\\s+(una|otra|alguna|uno|otro|algo|algunas?|unas)\\s+(de|del)\\s+" +
        "(?!(esas?|esos|estas?|estos|ellas?|ellos|tus|mis|sus|nuestr\\p{L}*|ese|este|eso|esto|aqu\\p{L}*|ah[ií]|all[aá]|lo|qu[eé]|" +
        "c[oó]mo|cu[aá]l\\p{L}*|cu[aá]nt\\p{L}*|d[oó]nde|cu[aá]ndo|qui[eé]n\\p{L}*|por qu[eé]|chocolate|vainilla|fresa)(\\s|$))(.{2,50})$")

    /** «quiero ver Titanic», «me gustaría ver El Juego del Calamar». */
    private val ytWantToSee = Regex("(?iu)^(oye\\s+|por favor\\s+)?(quiero|quer[ií]a|querr[ií]a|quisiera|me gustar[ií]a|deseo)\\s+ver\\s+(.{2,60})$")

    /** Justo después de una respuesta de YouTube: «y una de Enrique Iglesias», «otra de Maná», «ahora de Shakira», «y de Soda Stereo?». */
    private val ytFollow = Regex("(?iu)^(y\\s+|ahora\\s+|oye\\s+|tambi[eé]n\\s+|y ahora\\s+)*((quiero|dame|ponme|p[aá]same|m[aá]ndame|" +
        "me (das|pasas|mandas|pones)|busca(me)?)\\s+)?((otra|otro|una|uno|alguna|algo|m[aá]s|otras|otros|unas)\\s+)?" +
        "((canci[oó]n(es)?|temas?|videos?|pel[ií]culas?|series?|tr[aá]ilers?)\\s+)?(de|del)\\s+" +
        "(?!(esas?|esos|estas?|estos|ellas?|ellos|tus|mis|sus|lo|qu[eé]|c[oó]mo|cu[aá]l\\p{L}*|cu[aá]nt\\p{L}*|d[oó]nde|cu[aá]ndo|" +
        "qui[eé]n\\p{L}*|por qu[eé]|nuevo|nuevas?)(\\s|$))(.{2,50})$")

    /** Lo que NO es parte de lo que se busca: el pedido, la cortesía, «en YouTube», «el link de», «un video de»… */
    private val ytAskWords = rx("por favor", "\\bporfa\\b", "^\\s*(oye|hola)\\b",
        "me (puedes|podr[ií]as) (dar|pasar|poner|mostrar|buscar|recomendar|mandar|enviar|compartir)",
        "me (das|pasas|pones|muestras|mandas|env[ií]as|recomiendas|compartes)", "\\bpuedes\\b", "\\bpodr[ií]as\\b",
        "\\bdame\\b", "d[eé]jame", "\\b(quiero|quer[ií]a|querr[ií]a|deseo|busco)( ver| escuchar| o[ií]r)?\\b", "quisiera( ver| escuchar)?",
        "me gustar[ií]a( ver| escuchar)?",
        "mu[eé]strame", "ens[eé][ñn]ame", "\\bb[uú]sca(me|r)?\\b", "\\bpon(me|eme|er)?\\b", "reproduc\\p{L}*", "p[aá]same", "m[aá]ndame",
        "\\btienes\\b", "recomi[eé]nda(me)?", "recomi[eé]ndame", "sugi[eé]reme", "necesito", "comp[aá]rteme",
        "\\b(en|por|de|desde|del) (youtube( music)?|yt)\\b", "youtube( music)?", "\\byt\\b",
        "para (ver|escuchar|o[ií]r)( en)?\\b", "\\ben videos?\\b",
        "\\b((el|un|los|unos)\\s+)?(links?|enlaces?)(\\s+(de|del|para))?\\b",
        "\\b((el|la|un|una|los|las|unos|unas)\\s+)?(canci[oó]n(es)?|videos?|video ?clips?|clips?|tr[aá]ilers?( oficial(es)?)?)\\s+(de|del)\\b")

    /**
     * ¿Pide algo para ver o escuchar? Se busca en YouTube EXACTAMENTE lo que pidió (cualquier tema: «un video de inteligencia
     * artificial», «una película de comedia romántica», «un documental de la segunda guerra mundial»…).
     * Devuelve null si no es un pedido para YouTube (sigue el camino normal).
     * @param prevKind si la respuesta anterior fue de YouTube, su tipo: así «y una de Enrique Iglesias» sigue con canciones.
     */
    fun youtubeRequest(prompt: String, prevKind: YtKind? = null): YtRequest? {
        val raw = prompt.trim().trimEnd('?', '!', '.', ' ').trimStart('¿', '¡', ' ')
        val p = raw.lowercase()
        if (p.length < 4 || ytAboutYoutube.containsMatchIn(p) || ytCreateOrFile.containsMatchIn(p)) return null
        val yt = ytWord.containsMatchIn(p)
        // Si nombras YouTube, se busca allí aunque digas «más vistos», «tendencias», «canales», «música de fondo»…
        if (!yt && ytNotDirect.containsMatchIn(p)) return null
        if (!yt && !ytHasVideoWord.containsMatchIn(p) && ytOtherTopics.containsMatchIn(p)) return null
        val explicitKind = when {
            Regex("tr[aá]iler").containsMatchIn(p) -> YtKind.TRAILER
            Regex("canci[oó]n|m[uú]sica|video ?clip|[aá]lbum|\\bdisco|cumbia|reggaet|balada|karaoke|\\bmix\\b|enganchado|playlist").containsMatchIn(p) -> YtKind.MUSIC
            ytMediaNoun.containsMatchIn(p) -> YtKind.VIDEO
            else -> null
        }
        // 🧵 Sigue la conversación de YouTube: «y una de Enrique Iglesias» → otra canción, ahora de Enrique Iglesias
        if (prevKind != null && !yt) {
            ytFollow.find(raw)?.let { m ->
                val name = cleanName(m.groupValues.last())
                if (isName(name) && name.split(' ').size <= 7) {
                    val plural = Regex("\\b(otras|otros|unas|m[aá]s|canciones|temas|videos|pel[ií]culas|series)\\b").containsMatchIn(p)
                    return build(name, explicitKind ?: prevKind, if (plural) 5 else 3, iconHint(p))
                }
            }
        }
        // 🎯 «quiero una de Enrique Iglesias» (sin decir canción): se busca ese nombre en YouTube
        ytOneOf.find(raw)?.let { m ->
            val name = cleanName(m.groupValues.last())
            if (isName(name) && name.split(' ').size <= 7) {
                val plural = Regex("\\b(algunas|unas)\\b").containsMatchIn(p)
                return build(name, explicitKind ?: prevKind ?: YtKind.VIDEO, if (plural) 5 else 3, iconHint(p))
            }
        }
        // 🎬 «quiero ver Titanic», «quiero ver una película de comedia»
        if (!yt) ytWantToSee.find(raw)?.let { m ->
            val name = cleanName(m.groupValues.last()).replace(Regex("(?iu)^(la|el|los|las|una?|unos|unas|alg[uú]n|alguna)\\s+"), "")
            if (isName(name) && name.split(' ').size <= 9) return build(name, explicitKind ?: YtKind.VIDEO, 5, iconHint(p) ?: "🎬")
        }
        // 🔎 Búsqueda general: nombraste YouTube, o pides algo que se ve/escucha («dame un video de tecnología»)
        val media = ytMediaNoun.containsMatchIn(p)
        val verb = ytRequestVerb.containsMatchIn(p)
        val singular = ytSingular.containsMatchIn(p)
        val strong = ytStrongPlay.containsMatchIn(p)
        if (!yt && !(media && verb) && !(singular && media) && !strong) return null
        val kind = explicitKind ?: if (strong && !ytHasVideoWord.containsMatchIn(p)) YtKind.MUSIC else YtKind.VIDEO
        var q = p.replace(Regex("[¿?¡!.,;:«»\"“”]"), " ")
        q = q.replace(Regex("(?iu)\\b(en|por|de|desde|del|para)\\s+(youtube( music)?|yt)\\b"), " ")
        q = ytAskWords.replace(q, " ").replace(Regex("\\s+"), " ").trim()
        // Artículos y «de» que quedaron sueltos al comienzo o al final («una amar azul» → «amar azul»),
        // sin tocar los que son parte del nombre («Los Prisioneros», «La Bamba», «El Padrino»)
        val lead = Regex("^((una?|unos|unas|alguna?s?|alg[uú]n|otr[oa]s?|de|del|sobre|y|ahora)(\\s+|$)|(el|la|los|las)\\s+(?=(de|del|una?|el|la|los|las)\\s))")
        while (true) { val n = q.replace(lead, "").trim(); if (n == q) break; q = n }
        q = q.replace(Regex("\\s+(de|del|el|la|una?|en|por|para)$"), "").trim()
        if (q.length < 2) return null
        // «ponme una canción», «dame un video» sin decir cuál ni de qué y sin nombrar YouTube: que la IA pregunte
        if (!yt && Regex("^(canci[oó]n(es)?|temas?|videos?|pel[ií]culas?|pelis?|series?|clips?|en videos?)$").matches(q)) return null
        // «canciones nuevas», «estrenos», «lo último»: se agrega el año para que salga lo reciente
        if (!Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(q) && freshWordsYt.containsMatchIn(q))
            q += " " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return build(q, kind, if (singular) 3 else 5, iconHint(p))
    }

    private val freshWordsYt = rx("estrenos?", "\\bnuev[oa]s?\\b", "[uú]ltim[oa]s?", "recientes?", "lanzamientos?", "de este a[ñn]o")

    /** «las que me diste», «los mismos», «las opciones»: no es un nombre. */
    private val notAName = Regex("(?iu)^(las|los|el|la)\\s+(que|cuales|mism\\p{L}*|anterior\\p{L}*|otr\\p{L}*|primer\\p{L}*|[uú]ltim\\p{L}*|" +
        "opci\\p{L}*|de\\s|del\\s)|^(las|los)$")

    private fun isName(n: String) = n.length >= 2 && !notAName.containsMatchIn(n)

    private fun cleanName(s: String): String = s.replace(Regex("[¿?¡!.,;:«»\"“”]"), " ")
        .replace(Regex("(?iu)\\s+(en|por|de)\\s+(youtube|yt)\\b.*$"), "")
        .replace(Regex("(?iu)\\s+(por favor|porfa|please)$"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** Ícono por lo que dice el pedido («una película de…» → 🎬, «una serie de…» → 📺). */
    private fun iconHint(p: String): String? = when {
        Regex("pel[ií]cula|\\bpelis?\\b|\\bfilm").containsMatchIn(p) -> "🎬"
        Regex("\\bseries?\\b|k-?drama|dorama|novela|anime|caricatura|dibujos animados|cap[ií]tulo|episodio").containsMatchIn(p) -> "📺"
        Regex("documental").containsMatchIn(p) -> "🎥"
        Regex("tutorial|\\bcursos?\\b|\\bclases?\\b").containsMatchIn(p) -> "🎓"
        Regex("podcast|entrevista").containsMatchIn(p) -> "🎙️"
        else -> null
    }

    private fun build(name: String, kind: YtKind, count: Int, hint: String? = null): YtRequest {
        val q = name.lowercase()
        val search = genericYtSearch(q)
            ?: if (kind == YtKind.TRAILER && !q.contains("tráiler") && !q.contains("trailer")) "$q tráiler oficial" else q
        val small = setOf("de", "del", "la", "el", "los", "las", "y", "en", "sin", "con", "a", "o", "un", "una", "por", "para")
        val label = q.split(' ').mapIndexed { i, w -> if (i > 0 && w in small) w else w.replaceFirstChar { it.titlecase() } }.joinToString(" ")
        // Ícono según el tema: 📰 noticias, 📺 series/kdramas, 🎬 películas, 📱 tecnología, 🎮 juegos…
        val icon = when {
            hint != null && kind != YtKind.MUSIC -> hint
            kind == YtKind.MUSIC -> "🎵"
            kind == YtKind.TRAILER -> "🎬"
            Regex("noticia|titulares|en vivo|\\bnews\\b").containsMatchIn(q) -> "📰"
            else -> detect(q).let { if (it == GENERAL || it == YOUTUBE || it == VIDEOS) "▶️" else it.emoji }
        }
        return YtRequest(search, kind, count, label, icon)
    }

    // ------------------------------------------------------------------------
    // 📐 CÓMO ENTREGAR CADA CATEGORÍA: el formato exacto de la respuesta (una línea por opción, con sus datos)
    //    Así la IA siempre entrega una lista detallada y ordenada, sea lo que sea que pidas.
    // ------------------------------------------------------------------------
    private val FORMATS: Map<Int, String> = mapOf(
        1 to "Separa en 🆕 Estrenos de la semana · 🎞️ Siguen en cartelera · 🔁 Reestrenos. Cada una:\n" +
            "1. Película — género — clasificación — cines y horarios — precio de la entrada",
        2 to "1. Título — canal o creador — fecha — de qué trata (1 línea)",
        3 to "1. Canción o álbum — Artista (fecha de lanzamiento) — género — puesto en el ranking si aparece",
        4 to "Ordena por fecha (lo más próximo primero):\n1. 📅 Fecha y hora — Evento — Recinto (comuna) — precio desde — dónde comprar — disponibilidad",
        5 to "Una línea por indicador: Nombre — valor actual — variación (↑/↓ %) — fecha y hora de actualización.\nAl final, 1 línea de resumen del día.",
        6 to "Primero los descuentos más grandes:\n1. Producto o evento — tienda — precio normal → precio oferta (–%) — válido hasta",
        7 to "1. Cargo — Empresa — comuna/ciudad — jornada y modalidad — sueldo — requisitos clave — dónde postular\nAl final, 2 consejos para postular.",
        8 to "Agrupa por tipo (👕 Ropa · 👟 Calzado · 🧥 Abrigo · 👜 Accesorios):\n- Producto — marca — precio — tienda\nAl final, la tendencia de la temporada en 1 línea.",
        9 to "Agrupa por tipo (base · labiales · ojos · skincare · perfumes):\n- Producto — marca — para qué sirve o tipo de piel — precio — tienda",
        10 to "1. Tienda/sucursal — dirección — 🕒 hoy abre a las __ y cierra a las __ (y el horario del fin de semana) — teléfono o sitio — stock si aparece",
        11 to "Compara por producto: Producto — precio en cada supermercado — ✅ el más barato.\nLuego 🏷️ ofertas de la semana por supermercado.\n" +
            "Si aparece: 🕒 a qué hora abre y cierra cada supermercado hoy.",
        12 to "Muestra VARIAS CATEGORÍAS con 2-3 opciones cada una (solo las que aparezcan en los resultados):\n" +
            "🍔 Rápida · 🍣 Sushi/asiática · 🍕 Pizza/italiana · 🥗 Saludable · 🍲 Casera/típica · 🍰 Dulces · 🛵 Delivery\n" +
            "- Lugar o plato — comuna/dirección — precio aprox — horario\nSi pide una receta: ingredientes con cantidades y pasos numerados.",
        13 to "1. App o servicio — costo de envío — tiempo estimado — cobertura — promoción activa\nAl final, cuál conviene y por qué (1 línea).",
        14 to "Si pide un destino, entrega por secciones (solo las que aparezcan en los resultados):\n" +
            "✈️ Pasajes: aerolínea o empresa de bus — precio desde — duración del viaje\n" +
            "🧳 Paquetes: agencia — qué incluye (vuelo, hotel, traslados, tours) — noches — precio por persona\n" +
            "📋 Requisitos: documentos, visa, vacunas y seguro de viaje\n" +
            "Si pide ideas de destinos:\n1. Destino — por qué ir (1 línea) — precio desde — duración del viaje — mejor época",
        15 to "✈️ Origen → Destino (y la fecha, si la dio):\n1. Aerolínea — fecha — salida → llegada — directo o con escalas (dónde) — duración — " +
            "precio (ida o ida y vuelta) — equipaje incluido — dónde comprar\nAl final: 💰 el más barato y ⚡ el más rápido.",
        16 to "1. Línea/recorrido — desde → hasta — paraderos principales — 🕒 primera salida y última salida (días hábiles y fin de semana) — frecuencia — tarifa\n" +
            "Si pregunta cómo llegar: pasos numerados (qué micro o metro tomar y dónde bajarse).\n" +
            "Si pide micros, liebres, colectivos o buses URBANOS: solo líneas urbanas (dentro de la ciudad o entre comunas vecinas); " +
            "NO pongas buses interurbanos, empresas de terminal ni pasajes a otras ciudades.\n" +
            "Escribe SOLO los nombres de líneas, calles, paraderos, horas y tarifas que aparezcan en los resultados (con su moneda local). " +
            "Si un dato no aparece, escribe «Sin dato» y deja el enlace: nunca copies este formato como si fuera un dato ni inventes avenidas ni precios.",
        17 to "1. Destino — por qué ir (1 línea) — qué hacer — mejor época — presupuesto aprox por persona\nSepara nacionales e internacionales.\n" +
            "Si nombra un lugar: qué hacer allí en la temporada actual (actividades, atractivos y panoramas) y la mejor época para ir.",
        18 to "Del más económico al más caro:\n1. Hotel — zona/dirección — precio por noche — estrellas o valoración — incluye (desayuno, wifi, piscina…) — dónde reservar",
        19 to "Separa por secciones (solo las que pida o aparezcan en los resultados):\n" +
            "📅 Arriendo mensual · 🌙 Arriendo por día o temporada · 🏷️ En venta\n" +
            "1. Casa o depto — comuna/sector — dormitorios/baños/m² — precio (mensual, por noche o de venta) — gastos comunes — portal o corredora\n" +
            "Al final: 📊 el precio promedio de la zona y 🏢 las corredoras o portales que aparezcan.",
        20 to "Separa por secciones (solo las que pida o aparezcan en los resultados):\n" +
            "🆕 Nuevos: Marca Modelo versión — precio de lista — concesionaria u oferta vigente\n" +
            "🔁 Usados: Marca Modelo año — km — precio — vendedor o portal\n" +
            "📋 Ficha técnica: motor y potencia · transmisión · consumo · seguridad · maletero\n" +
            "💲 Tasación: rango de precio según año y km\n" +
            "🔑 Arriendo de autos: empresa — precio por día — qué incluye — requisitos\n" +
            "Al final, el rango de precios del modelo.",
        21 to "Hoy: mínima/máxima, lluvia %, índice UV.\nPróximos días: Día — mín/máx — condición — lluvia %.",
        22 to "Si pide un equipo (celular, notebook, tablet, TV…):\n1. Equipo — pantalla · procesador · RAM/almacenamiento · batería · cámara — precio — dónde comprar — fecha de lanzamiento\n" +
            "Si compara, al final el ganador en cada aspecto.\n" +
            "Si pregunta por un tema (inteligencia artificial, robótica, chips, tecnología antigua, historia de un invento): " +
            "explica qué es y cómo funciona en palabras simples, luego los hitos con fechas y nombres, y al final las novedades que aparezcan.",
        23 to "Separa por secciones (solo las que pida o aparezcan en los resultados):\n" +
            "📅 Eventos: 1. Fecha — Evento — ¿visible desde {LUGAR}? — hacia dónde mirar — cómo verlo (a simple vista, binoculares o telescopio)\n" +
            "☄️ Cometas: nombre — brillo — cuándo y dónde buscarlo\n" +
            "🔬 Descubrimientos recientes: qué se descubrió — quién (NASA, ESO, James Webb…) — fecha — por qué importa (1 línea)\n" +
            "Las horas exactas, la fase de la Luna y los planetas de esta noche los calcula la app debajo: no los inventes.",
        24 to "Qué es (1-2 líneas) · síntomas o usos · tratamientos o medicamentos habituales (sin dosis) · ⚠️ contraindicaciones o efectos " +
            "secundarios importantes · 🆕 avances recientes (con fuente y fecha, si aparecen) · 🚨 cuándo consultar de urgencia.",
        25 to "1. Serie — plataforma — fecha de estreno o de los episodios — temporada — género — de qué trata (1 línea)",
        26 to "1. Película (año) — director — reparto principal — género — puntuación o crítica — dónde verla (cine o plataforma) — sinopsis en 1 línea",
        27 to "1. Juego — plataforma — novedad (evento, parche o temporada) — fecha — detalles clave (cambios, recompensas, configuración recomendada)",
        28 to "1. App — desarrollador — valoración ★ — descargas — última versión y fecha — novedades — gratis o precio",
        29 to "1. Video o canal — creador — vistas — fecha — de qué trata (1 línea)",
        30 to "1. Canción — Artista — puesto en la lista — álbum",
        31 to "Respuesta directa en 1-2 líneas y luego los detalles en viñetas (fechas, cifras, pasos o requisitos, según el tema)."
    )

    /** Palabras que se agregan a la búsqueda para que internet traiga los datos de ese formato (precios, horarios…). */
    private val HINTS: Map<Int, String> = mapOf(
        1 to "cartelera horarios", 4 to "entradas {MES} {AÑO}", 5 to "hoy", 6 to "ofertas {MES} {AÑO}", 7 to "ofertas de trabajo",
        8 to "precio", 9 to "precio", 10 to "horario dirección", 11 to "precio supermercado", 12 to "restaurantes recomendados",
        13 to "costo de envío", 14 to "precio pasajes paquetes {MES} {AÑO}", 15 to "precio pasajes aerolíneas {MES} {AÑO}", 16 to "recorrido horario tarifa",
        17 to "mejores lugares {AÑO}", 18 to "precio por noche", 19 to "precio arriendo", 20 to "precio", 22 to "precio especificaciones",
        23 to "{MES} {AÑO}", 25 to "estreno {AÑO}", 26 to "reparto crítica dónde ver", 27 to "{AÑO}", 28 to "Play Store"
    )

    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    // ------------------------------------------------------------------------
    // 🔎 BÚSQUEDA: prioriza la ciudad/país del usuario y el año actual cuando pide novedades
    // ------------------------------------------------------------------------
    private val freshWords = rx("estrenos?", "\\bnuev[oa]s?\\b", "[uú]ltim[oa]s?", "recientes?", "lanzamientos?", "tendencias?",
        "\\btop\\b", "mejores", "\\bahora\\b", "\\bhoy\\b", "esta semana", "este mes", "\\bactual(es)?\\b", "vigentes?", "pr[oó]xim[oa]s?")

    fun searchQuery(cat: Category, cleanedQuery: String, place: String, year: Int, country: String = ""): String {
        var q = cleanedQuery.trim()
        val city = place.substringBefore(',').trim()
        val country = place.substringAfterLast(',', "").trim()
        // ✈️ Vuelos: si no dijo desde dónde, desde su ciudad (el estado de un vuelo no depende de dónde estás)
        if (cat == VUELOS && city.isNotBlank() && !isFlightStatus(q) && !Regex("(?iu)\\bdesde\\s+\\p{L}{3,}").containsMatchIn(q) &&
            !Regex("(?u)\\b([Dd]esde|[Dd]e)\\s+\\p{Lu}").containsMatchIn(q) && !q.lowercase().contains(city.lowercase()))
            q += " desde $city"
        // 📍 «cerca», «aquí», «dónde queda», «sucursal», «mi comuna»… en CUALQUIER tipo de pedido: tu ciudad o comuna
        //    (YouTube, videos y música no dependen del lugar)
        if (!cat.local && cat !in setOf(YOUTUBE, YOUTUBE_MUSIC, VIDEOS, MUSICA, VUELOS) && place.isNotBlank() &&
            nearWords.containsMatchIn(q) && !mentionsPlace(q, place, true)) q += " " + place.replace(",", "")
        // 🏨 Hoteles sin lugar nombrado («hoteles baratos»): donde estás
        else if (cat == HOTELES && city.isNotBlank() && !mentionsPlace(q, place, true)) q += " $city"
        // 🔭 Astronomía: lo que se ve depende del hemisferio y del país («lluvia de estrellas octubre 2026 Chile»)
        else if (cat == ASTRONOMIA && country.isNotBlank() && !q.lowercase().contains(country.lowercase()) && !mentionsPlace(q, place))
            q += " $country"
        // 📱🎮 Precios de tecnología, juegos y apps: en tu país y tu moneda
        else if (cat in setOf(TECNOLOGIA, JUEGOS, GOOGLE_PLAY, VIAJES, VACACIONES, GENERAL) && country.isNotBlank() &&
            priceWords.containsMatchIn(q) && !q.lowercase().contains(country.lowercase()) && !mentionsPlace(q, place))
            q += " $country"
        // 🍽️ Comida: una receta no depende de la ciudad; «dónde comer» sí
        val recipe = cat == COMIDA && Regex("(?iu)receta|cocinar|preparar|c[oó]mo (se )?hace").containsMatchIn(q)
        if (cat.local && !recipe && place.isNotBlank() && !mentionsPlace(q, place, cat in PLACE_CATS)) q += " " + place.replace(",", "")
        // 🏛️ Los trámites dependen del país: se agrega el tuyo si no lo nombró
        if (cat == GENERAL && GeneralFinder.kind(q) == GeneralFinder.Kind.TRAMITE) {
            val country = place.substringAfter(',', "").trim()
            if (country.isNotBlank() && !q.lowercase().contains(country.lowercase())) q += " $country"
        }
        // 💊 «farmacia de turno», «urgencias cerca»: en tu ciudad
        if (cat == MEDICINA && HealthFinder.placeKind(q) != null && place.isNotBlank() && !mentionsPlace(q, place, true)) q += " " + place.replace(",", "")
        // 🧳 Viajes: desde tu ciudad (si dijo a dónde y no desde dónde) y la pista según lo que pide
        if (cat == VIAJES && city.isNotBlank() && travelDestination(q) != null &&
            !Regex("(?iu)\\bdesde\\s+\\p{L}").containsMatchIn(q) && !q.lowercase().contains(city.lowercase()))
            q += " desde $city"
        val hint = when {
            recipe -> "receta"
            cat == VIAJES && Regex("(?iu)requisit|documento|\\bvisas?\\b|pasaporte|vacuna|seguro de viaje|aduana").containsMatchIn(q) ->
                "requisitos documentos {AÑO}" + (place.substringAfter(',', "").trim().let { if (it.isNotBlank()) " desde $it" else "" })
            cat == VIAJES && Regex("(?iu)paquete|todo incluido|all inclusive|\\btours?\\b|crucero").containsMatchIn(q) ->
                "paquetes turísticos precio {MES} {AÑO}"
            // ✈️ Estado de un vuelo (llegadas, salidas, atrasos): no precios de pasajes
            cat == VUELOS && isFlightStatus(q) -> "hoy"
            // 🔎 Consulta general: pista según el tipo (trámite, tutorial, deporte)
            cat == GENERAL ->GeneralFinder.hint(GeneralFinder.kind(q), q)
            // ⚕️ Medicamento, avances o lugar (farmacia de turno, urgencias)
            cat == MEDICINA && HealthFinder.placeKind(q) != null -> ""
            cat == MEDICINA && Regex("(?iu)avances?|nuevos? tratamientos?|estudios?|investigaci|descubr|ensayos?").containsMatchIn(q) -> "estudio {AÑO}"
            cat == MEDICINA && HealthFinder.drugName(q) != null -> "usos efectos secundarios contraindicaciones"
            // 🏠 Arriendo por día, venta o mensual · 🚗 rent a car, tasación, ficha técnica o precio
            cat == ARRIENDOS -> MarketFinder.parseRent(q).let { r -> when {
                r.daily -> "por día precio"
                r.sale -> "venta precio"
                Regex("(?iu)alquil|renta").containsMatchIn(q) -> "precio"
                else -> "arriendo precio mensual"
            } }
            cat == VEHICULOS -> MarketFinder.parseCar(q).let { c -> when {
                c.rental -> "precio por día"
                c.appraisal -> "tasación precio usado"
                c.specs && c.used == null -> "ficha técnica"
                c.used == true -> "usado precio"
                c.used == false -> "precio nuevo {AÑO}"
                else -> "precio nuevo usado"
            } }
            // 📱 Tecnología: precio y ficha solo para equipos; historia para lo antiguo; novedades del año para lo demás
            cat == TECNOLOGIA && techDevice.containsMatchIn(q) -> "precio especificaciones"
            cat == TECNOLOGIA && Regex("(?iu)historia|antigu|retro|viej|primer[oa]? |invent|origen|evoluci").containsMatchIn(q) -> "historia"
            cat == TECNOLOGIA && Regex("(?iu)nuev|[uú]ltim|novedad|avance|actual|reciente|hoy|lanzamiento").containsMatchIn(q) -> "novedades {MES} {AÑO}"
            cat == TECNOLOGIA -> ""
            // 🚌 Micros URBANOS («micros de Chiguayante a Talcahuano», «buses urbanos»): sin pasajes de terminal ni buses interurbanos
            cat == BUSES && isInterurban(q) -> "bus interurbano horarios precio pasaje terminal"
            cat == BUSES -> "micro urbana línea recorrido tarifa -pasajes -terminal -interurbano"
            else -> HINTS[cat.num].orEmpty()
        }
        val month = MONTHS[java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)]
        val hasYear = Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(q)
        val low = q.lowercase()
        // 🌍 Si la pregunta no está en español (en EE.UU., Brasil, Rusia, China, Corea, Japón…), no se agregan
        //    palabras en español («precio», «horario»…): solo el año
        val hintWords: String = if (!looksSpanish(q)) hint.split(' ').filter { it == "{AÑO}" }.joinToString(" ") else hint
        hintWords.replace("{MES}", month).replace("{AÑO}", if (hasYear) "" else year.toString())
            .split(' ').filter { it.isNotBlank() && !low.contains(it.lowercase()) }
            .let { if (it.isNotEmpty()) q += " " + it.joinToString(" ") }
        if (cat.fresh && !Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(q) && freshWords.containsMatchIn(low)) q += " $year"
        return q.replace(Regex("\\s+"), " ").trim().take(200)
    }

    /** ¿El usuario ya nombró un lugar? («en Valparaíso», «de Lima», o su propia ciudad/país). */
    /** Palabras que van después de «de/en» pero no son lugares («películas de terror», «horario de hoy»). */
    private val notAPlace = Regex("(?iu)^(transporte|p[uú]blico|transporte p[uú]blico|micros?|buses|bus|metro|cines?|cartelera|hoy|ma[ñn]ana|" +
        "ayer|semana|la semana|esta semana|noche|tarde|comida|internet|youtube|m[uú]sica|terror|acci[oó]n|comedia|amor|drama|" +
        "animaci[oó]n|suspenso|aventura|ciencia ficci[oó]n|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|" +
        "noviembre|diciembre|verano|invierno|oto[ñn]o|primavera|precio|precios|oferta|ofertas|trabajo|casa|mi casa|la casa|" +
        "familia|pareja|ni[ñn]os|vacaciones|turno|urgencia|delivery|regalo|cumplea[ñn]os|tecnolog[ií]a|f[uú]tbol|deportes?|" +
        // 🏠🍕🚌 Cosas (no lugares): «arriendo de departamento», «delivery de pizza», «horario de salida de la locomoción»
        "departamentos?|deptos?|casas?|piezas?|habitaci[oó]n(es)?|oficinas?|terrenos?|parcelas?|caba[ñn]as?|autos?|motos?|" +
        "camionetas?|veh[ií]culos?|pizzas?|sushi|hamburguesas?|completos?|empanadas?|pollo|helados?|comida china|salida|salidas|" +
        "llegada|locomoci[oó]n|liebres?|colectivos?|trenes?|tren|vuelos?|avi[oó]n|aviones|playa|playas|ropa|zapatillas|celulares?|" +
        "guardia|vendedor|cajero|cajera|bodega|turno noche|estrellas|planetas|la luna|luna|todo|todos|nada|algo|ahora|lujo|fin de semana)$")

    /**
     * 📍 La ciudad o comuna que el usuario dice que es la suya: «vivo en chiguayante», «estoy en Talcahuano»,
     * «soy de San Pedro de la Paz», «mi comuna es Hualpén». La app la recuerda para las próximas búsquedas.
     */
    fun homePlace(q: String): String? {
        val m = Regex("(?iu)\\b(?:vivo|estoy|resido|me encuentro|ando|me ubico) (?:en|por) |\\bsoy de |\\bmi (?:ciudad|comuna|pueblo|localidad) (?:es|se llama) ")
            .find(q) ?: return null
        val rest = q.substring(m.range.last + 1).trim()
            .split(Regex("(?iu)[,.;:?!¿¡]|\\s(?:y|que|qu[eé]|c[oó]mo|d[oó]nde|cu[aá]l(?:es)?|cu[aá]ndo|necesito|quiero|busco|b[uú]scame|dame|dime|me|hay|para|a qu[eé]|\\p{L}+(?:ando|iendo))(?:\\s|$)"))
            .first().trim().replace(Regex("(?iu)^(la|el|los|las)\\s+"), "")
        val words = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5 || words.first().length < 3) return null
        if (!words.all { Regex("^\\p{L}+$").matches(it) } || notAPlace.matches(rest) || words.any { notAPlace.matches(it) }) return null
        if (Regex("(?iu)^(casa|mi casa|el trabajo|la pega|clases?|el colegio|la universidad|reuni[oó]n|cama|mi pieza)").containsMatchIn(rest)) return null
        return words.joinToString(" ") { w ->
            if (w.lowercase() in setOf("de", "del", "la", "las", "los", "el", "y")) w.lowercase() else w.replaceFirstChar { it.titlecase() } }
    }

    // ------------------------------------------------------------------------
    // PALABRAS DE INICIO: «oye, dame…», «quiero que me des…», «me puedes dar…», «búscame…», «yo necesito…»,
    //    «tienes…», «muéstrame…», «ellos…», «nosotros…». Se quitan para buscar SOLO lo que se pide.
    // ------------------------------------------------------------------------
    private const val NB = "(?![\\p{L}\\p{N}])"   // fin de palabra (entiende tildes y ñ)

    /** Saludos, pedidos y pronombres del comienzo (se quitan una y otra vez: «oye, yo quiero que me des…»). */
    private val leadRequest = Regex("(?iu)^(?:" + listOf(
        "oye", "oiga", "hola", "holi", "buenas", "buenos d[ií]as", "buenas (?:tardes|noches)", "hey", "ey",
        "por favor", "porfa(?:vor)?", "porfis",
        "(?:yo )?(?:quiero|quisiera|quer[ií]a|necesito|necesitar[ií]a|deseo) que me (?:des|digas|busques|muestres|ense[ñn]es|encuentres|pases|mandes|recomiendes|expliques|ayudes (?:a|con))",
        "(?:yo )?(?:quiero|quisiera|quer[ií]a|necesito|necesitar[ií]a|deseo) (?:saber|conocer|ver|encontrar|buscar|informaci[oó]n (?:de|sobre)|info (?:de|sobre))",
        "(?:yo )?(?:quiero|quisiera|quer[ií]a|necesito|necesitar[ií]a|deseo)",
        "(?:t[uú] )?(?:me )?(?:puedes|podr[ií]as|pod[eé]s) (?:dar(?:me)?|decir(?:me)?|buscar(?:me)?|mostrar(?:me)?|encontrar(?:me)?|pasar(?:me)?|ayudar(?:me)? (?:a|con)|recomendar(?:me)?)",
        "(?:t[uú] )?(?:me )?(?:puedes|podr[ií]as|pod[eé]s)",
        "(?:t[uú] )?me das", "(?:t[uú] )?me dices", "(?:t[uú] )?me buscas", "(?:t[uú] )?me muestras", "(?:t[uú] )?me pasas",
        "d[aá]me", "d[aá]nos", "dime", "dinos", "b[uú]scame", "b[uú]scanos", "busca(?:r)?(?: en (?:internet|google|la web))?",
        "mu[eé]strame", "mu[eé]stranos", "ens[eé][ñn]ame", "encu[eé]ntrame", "p[aá]same", "m[aá]ndame", "recomi[eé]ndame",
        "cu[eé]ntame", "inf[oó]rmame(?: sobre)?", "averigua(?:me)?", "investiga(?:me)?", "ay[uú]dame (?:a|con)",
        "(?:t[uú] )?tienes", "(?:t[uú] )?ten[eé]s", "(?:t[uú] )?sabes",
        // «él quiere…», «nosotros queremos…», «ellos buscan…», «vosotros tenéis…» (después del pronombre)
        "(?:quiere|quieren|queremos|quer[eé]is|necesita|necesitan|necesitamos|necesit[aá]is|buscan|buscamos|busc[aá]is|" +
            "tiene|tienen|tenemos|ten[eé]is|desea|desean|deseamos|dese[aá]is)(?: (?:saber|ver|conocer|encontrar|buscar))?",
        "yo", "t[uú]", "él", "ella", "ellos", "ellas", "nosotros", "nosotras", "vosotros", "vosotras", "ustedes", "usted"
    ).joinToString("|") + ")$NB[\\s,.:;!¡¿?]*")

    /** Artículo que queda después de un pedido («dame LOS horarios» → «horarios»). */
    private val leadArticle = Regex("(?iu)^(?:el|la|los|las|un|una|unos|unas|algo de|algunos|algunas|info de|informaci[oó]n (?:de|sobre))$NB\\s*")

    /** «Los Ángeles», «Las Condes», «Los Andes»…: ahí «los/las» es parte del nombre del lugar y no se quita. */
    private val articlePlace = Regex("(?iu)^(?:los|las) (?:[aá]ngeles|andes|condes|vilos|lagos|r[ií]os|muermos|[aá]lamos|cabras|cruces|palmas|heras|toldos|lajas|sauces|vegas|rozas|mochis|cabos|teques|chorrillos|olivos|patios|reyes)$NB")

    /**
     * «oye, dame los horarios de la locomoción» → «horarios de la locomoción»;
     * «yo quiero que me des vuelos baratos» → «vuelos baratos»; «ellos…», «nosotros…» → sin el pronombre.
     */
    fun stripRequestWords(text: String): String {
        var t = text.trim().trimStart('¿', '¡', ',', '.', ' ')
        var strippedRequest = false
        for (i in 0 until 8) {
            val m = leadRequest.find(t) ?: break
            val rest = t.substring(m.range.last + 1).trim()
            if (rest.isEmpty()) break   // «dame» sola: no se deja vacía la búsqueda
            t = rest
            strippedRequest = true
        }
        // «los/las» al comienzo: se quita después de un pedido, o sola si no es parte de un lugar («Los Ángeles»)
        if (!articlePlace.containsMatchIn(t) && (strippedRequest || Regex("(?iu)^(?:los|las)$NB").containsMatchIn(t))) {
            leadArticle.find(t)?.let { m -> t.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let { t = it } }
        }
        return t.trim()
    }

    // ------------------------------------------------------------------------
    // 🎯 CADA TEMA BUSCA EN SUS PROPIAS PÁGINAS (y las de noticias son SOLO para noticias)
    //    Vuelos en aerolíneas y buscadores de vuelos, micros en Moovit y en el transporte oficial,
    //    precios del súper en las páginas de cada supermercado, hoteles en Booking/Trivago… Antes salía
    //    «Cyber Monday: descuentos en aerolíneas» (una noticia) cuando pedías vuelos.
    // ------------------------------------------------------------------------

    /** Temas donde se pueden leer diarios (la noticia es parte del tema: economía y bolsa, lanzamientos de tecnología). */
    private val NEWS_OK: Set<Category> by lazy { setOf(NEGOCIOS, TECNOLOGIA) }

    /**
     * ¿Este tema puede usar páginas de noticias? Vuelos, micros, precios, hoteles, recetas, cine, astronomía, empleos… NO.
     * En consultas generales, solo deportes (resultados del partido) y lo que no tiene otro tipo.
     */
    fun allowsNews(cat: Category?, question: String = ""): Boolean = when {
        cat == null || cat in NEWS_OK -> true
        cat == GENERAL -> runCatching { GeneralFinder.kind(question) }.getOrNull().let { it == null || it == GeneralFinder.Kind.SPORTS || it == GeneralFinder.Kind.OTHER }
        else -> false
    }

    /** Diarios, canales y sitios de noticias (Chile y Latinoamérica, y los grandes internacionales). */
    private val NEWS_HOSTS = listOf(
        "news.google.", "emol.com", "latercera.com", "biobiochile.cl", "cooperativa.cl", "t13.cl", "24horas.cl", "meganoticias.cl",
        "cnnchile.com", "chvnoticias.cl", "elmostrador.cl", "lun.com", "lacuarta.com", "eldinamo.cl", "theclinic.cl", "publimetro.cl",
        "adnradio.cl", "radioagricultura.cl", "elsur.cl", "diarioconcepcion.cl", "soychile.cl", "elmercurio.com", "lasegunda.com",
        "eldesconcierto.cl", "ciperchile.cl", "redgol.cl", "alairelibre.cl", "tvn.cl", "mega.cl", "chilevision.cl", "canal13.cl",
        "infobae.com", "clarin.com", "lanacion.com", "pagina12.com", "eltiempo.com", "elespectador.com", "eluniversal.com",
        "milenio.com", "excelsior.com", "elcomercio.pe", "larepublica.pe", "rpp.pe", "elpais.com", "elmundo.es", "abc.es",
        "20minutos.es", "bbc.com", "bbc.co.uk", "cnn.com", "cnnespanol.cnn.com", "reuters.com", "apnews.com", "efe.com",
        "europapress.es", "dw.com", "france24.com", "euronews.com", "nytimes.com", "washingtonpost.com", "foxnews.com", "msn.com",
        "yahoo.com/noticias", "es-us.noticias.yahoo.com",
        // 🇺🇸🇧🇷🇵🇹🇷🇺🇨🇳🇰🇷🇯🇵 diarios de los otros países de la app
        "nbcnews.com", "abcnews.go.com", "cbsnews.com", "usatoday.com", "wsj.com", "npr.org", "latimes.com", "theguardian.com",
        "g1.globo.com", "folha.uol.com.br", "noticias.uol.com.br", "estadao.com.br", "cnnbrasil.com.br", "r7.com", "metropoles.com",
        "publico.pt", "expresso.pt", "rtp.pt", "observador.pt", "cmjornal.pt", "jn.pt", "dn.pt",
        "ria.ru", "tass.ru", "lenta.ru", "kommersant.ru", "iz.ru", "rg.ru", "gazeta.ru", "1tv.ru",
        "xinhuanet.com", "people.com.cn", "chinadaily.com.cn", "thepaper.cn", "cctv.com", "globaltimes.cn", "news.sina.com.cn",
        "yna.co.kr", "chosun.com", "joongang.co.kr", "donga.com", "hani.co.kr", "kbs.co.kr", "news.naver.com",
        "nhk.or.jp", "asahi.com", "yomiuri.co.jp", "mainichi.jp", "japantimes.co.jp", "news.yahoo.co.jp", "sankei.com",
        // 🌎 diarios de Latinoamérica y España
        "lanacion.com.ar", "perfil.com", "tn.com.ar", "lavoz.com.ar", "larepublica.pe", "peru21.pe", "elcomercio.pe", "infobae.com",
        "semana.com", "elcolombiano.com", "caracol.com.co", "rcnradio.com", "noticiasrcn.com", "elheraldo.co", "eluniversal.com.mx",
        "jornada.com.mx", "proceso.com.mx", "elnacional.com", "eldeber.com.bo", "eluniverso.com", "primicias.ec", "elobservador.com.uy",
        "elpais.com.uy", "abc.com.py", "ultimahora.com", "nacion.com", "prensa.com", "listindiario.com", "diariolibre.com",
        "prensalibre.com", "lavanguardia.com", "elconfidencial.com", "eldiario.es", "rtve.es", "antena3.com", "telecinco.es",
        "larazon.es", "publico.es",
        // 🇪🇺 diarios de Europa
        "thetimes.co.uk", "telegraph.co.uk", "independent.co.uk", "dailymail.co.uk", "mirror.co.uk", "thesun.co.uk", "sky.com/news",
        "lemonde.fr", "lefigaro.fr", "liberation.fr", "francetvinfo.fr", "bfmtv.com", "leparisien.fr", "20minutes.fr", "ouest-france.fr",
        "spiegel.de", "zeit.de", "faz.net", "sueddeutsche.de", "bild.de", "welt.de", "tagesschau.de", "n-tv.de", "focus.de",
        "corriere.it", "repubblica.it", "lastampa.it", "ansa.it", "tgcom24.mediaset.it", "ilfattoquotidiano.it", "rainews.it",
        "nos.nl", "nu.nl", "telegraaf.nl", "hln.be", "lesoir.be", "nzz.ch", "20min.ch", "derstandard.at", "krone.at", "irishtimes.com",
        "rte.ie", "onet.pl", "wp.pl", "gazeta.pl", "aftonbladet.se", "expressen.se", "vg.no", "dagbladet.no", "dr.dk", "yle.fi",
        "kathimerini.gr", "novinky.cz", "digi24.ro", "index.hu", "pravda.com.ua", "unian.ua"
    )

    /** ¿Es una página de noticias? (diario, canal, Google Noticias o una sección /noticias/) */
    fun isNewsUrl(url: String): Boolean {
        val low = url.lowercase()
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
        if (host.startsWith("news.") || host.startsWith("noticias.")) return true
        if (NEWS_HOSTS.any { h -> if (h.contains('/')) low.contains(h) else host == h || host.endsWith(".$h") || host.startsWith(h) }) return true
        return Regex("/(noticias?|news|actualidad|nacional|cronica|cr[oó]nica)/").containsMatchIn(low)
    }

    // ------------------------------------------------------------------------
    // 🌍 PÁGINAS DE CADA PAÍS. El país sale de la SIM del teléfono.
    //    Latinoamérica y España: 🇦🇷 🇵🇪 🇨🇴 🇲🇽 🇻🇪 🇧🇴 🇪🇨 🇺🇾 🇵🇾 🇪🇸 🇨🇷 🇵🇦 🇩🇴 🇬🇹 ·
    //    Europa: 🇬🇧 🇫🇷 🇩🇪 🇮🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇮🇪 🇵🇱 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇬🇷 🇨🇿 🇷🇴 🇭🇺 🇺🇦 ·
    //    otros idiomas de la app: 🇺🇸 Estados Unidos · 🇧🇷 Brasil · 🇵🇹 Portugal · 🇷🇺 Rusia · 🇨🇳 China · 🇰🇷 Corea · 🇯🇵 Japón.
    //    Si un país no tiene página propia para un tema, se completa con la lista internacional (Despegar, Booking, Mercado Libre…).
    //    Clave: «TEMA» o «TEMA:subtipo» (micros urbanas/interurbanos, recetas/locales, estado de vuelo, trámites…).
    //    Chile tiene su propia lista (más abajo, en preferredSites). Los demás países usan la lista internacional.
    // ------------------------------------------------------------------------
    private fun sites(vararg lines: Pair<String, String>): Map<String, List<String>> =
        lines.associate { (k, v) -> k to v.trim().split(Regex("\\s+")) }

    private val COUNTRY_SITES: Map<String, Map<String, List<String>>> by lazy { mapOf(
        // 🇬🇧 Reino Unido
        "GB" to sites(
            "CINE" to "odeon.co.uk cineworld.co.uk myvue.com picturehouses.com",
            "EVENTOS" to "ticketmaster.co.uk seetickets.com eventbrite.co.uk axs.com",
            "NEGOCIOS" to "londonstockexchange.com bankofengland.co.uk ft.com investing.com",
            "VENTAS" to "hotukdeals.com amazon.co.uk argos.co.uk currys.co.uk",
            "EMPLEOS" to "indeed.co.uk reed.co.uk totaljobs.com findajob.dwp.gov.uk linkedin.com",
            "VESTUARIO" to "next.co.uk marksandspencer.com asos.com zara.com hm.com",
            "MAQUILLAJE" to "boots.com superdrug.com lookfantastic.com sephora.co.uk",
            "TIENDAS" to "argos.co.uk currys.co.uk johnlewis.com amazon.co.uk ikea.com",
            "SUPERMERCADOS" to "tesco.com sainsburys.co.uk asda.com morrisons.com aldi.co.uk lidl.co.uk trolley.co.uk",
            "COMIDA:receta" to "bbcgoodfood.com jamieoliver.com deliciousmagazine.co.uk",
            "COMIDA" to "tripadvisor.co.uk opentable.co.uk",
            "DELIVERY" to "deliveroo.co.uk just-eat.co.uk ubereats.com",
            "VIAJES:requisitos" to "gov.uk",
            "VIAJES:bus" to "nationalexpress.com megabus.com thetrainline.com nationalrail.co.uk",
            "VIAJES" to "expedia.co.uk lastminute.com tui.co.uk",
            "VUELOS:estado" to "heathrow.com flightradar24.com",
            "VUELOS" to "britishairways.com easyjet.com ryanair.com skyscanner.net kayak.co.uk",
            "BUSES:inter" to "nationalexpress.com megabus.com thetrainline.com nationalrail.co.uk",
            "BUSES" to "tfl.gov.uk citymapper.com nationalrail.co.uk moovitapp.com",
            "VACACIONES" to "visitbritain.com nationaltrust.org.uk tripadvisor.co.uk",
            "HOTELES" to "booking.com hotels.com premierinn.com travelodge.co.uk airbnb.co.uk",
            "ARRIENDOS" to "rightmove.co.uk zoopla.co.uk onthemarket.com spareroom.co.uk",
            "VEHICULOS" to "autotrader.co.uk motors.co.uk carwow.co.uk parkers.co.uk",
            "CLIMA" to "metoffice.gov.uk weather.com",
            "TECNOLOGIA" to "techradar.com trustedreviews.com which.co.uk currys.co.uk",
            "MEDICINA:farmacia" to "boots.com superdrug.com nhs.uk",
            "MEDICINA" to "nhs.uk patient.info",
            "SERIES" to "justwatch.com imdb.com radiotimes.com",
            "PELICULAS" to "justwatch.com imdb.com radiotimes.com",
            "JUEGOS" to "eurogamer.net game.co.uk store.steampowered.com",
            "GENERAL:TRAMITE" to "gov.uk",
            "GENERAL:SPORTS" to "skysports.com premierleague.com bbc.co.uk",
            "GENERAL:KNOWLEDGE" to "wikipedia.org britannica.com"
        ),
        // 🇫🇷 Francia
        "FR" to sites(
            "CINE" to "allocine.fr ugc.fr pathe.fr cgrcinemas.fr",
            "EVENTOS" to "fnacspectacles.com ticketmaster.fr billetreduc.com",
            "NEGOCIOS" to "boursorama.com zonebourse.com banque-france.fr lesechos.fr",
            "VENTAS" to "dealabs.com amazon.fr cdiscount.com fnac.com darty.com",
            "EMPLEOS" to "francetravail.fr indeed.fr welcometothejungle.com apec.fr hellowork.com",
            "VESTUARIO" to "zalando.fr laredoute.fr kiabi.com zara.com hm.com",
            "MAQUILLAJE" to "sephora.fr nocibe.fr marionnaud.fr",
            "TIENDAS" to "fnac.com darty.com boulanger.com leroymerlin.fr cdiscount.com",
            "SUPERMERCADOS" to "carrefour.fr leclercdrive.fr auchan.fr intermarche.com lidl.fr coursesu.com",
            "COMIDA:receta" to "marmiton.org cuisineaz.com 750g.com",
            "COMIDA" to "thefork.fr tripadvisor.fr",
            "DELIVERY" to "ubereats.com deliveroo.fr just-eat.fr",
            "VIAJES:requisitos" to "diplomatie.gouv.fr",
            "VIAJES:bus" to "sncf-connect.com flixbus.fr blablacar.fr trainline.fr",
            "VIAJES" to "opodo.fr expedia.fr sncf-connect.com",
            "VUELOS:estado" to "parisaeroport.fr flightradar24.com",
            "VUELOS" to "airfrance.fr transavia.com easyjet.com skyscanner.fr kayak.fr opodo.fr",
            "BUSES:inter" to "sncf-connect.com flixbus.fr blablacar.fr trainline.fr",
            "BUSES" to "ratp.fr iledefrance-mobilites.fr citymapper.com moovitapp.com",
            "VACACIONES" to "france.fr routard.com tripadvisor.fr",
            "HOTELES" to "booking.com trivago.fr hotels.com all.accor.com airbnb.fr",
            "ARRIENDOS" to "seloger.com leboncoin.fr pap.fr bienici.com",
            "VEHICULOS" to "lacentrale.fr leboncoin.fr autoscout24.fr caradisiac.com",
            "CLIMA" to "meteofrance.com lachainemeteo.com",
            "TECNOLOGIA" to "frandroid.com 01net.com numerama.com lesnumeriques.com",
            "MEDICINA" to "ameli.fr vidal.fr has-sante.fr doctissimo.fr",
            "SERIES" to "allocine.fr senscritique.com justwatch.com",
            "PELICULAS" to "allocine.fr senscritique.com justwatch.com",
            "JUEGOS" to "jeuxvideo.com gamekult.com store.steampowered.com",
            "GENERAL:TRAMITE" to "service-public.fr impots.gouv.fr ants.gouv.fr",
            "GENERAL:SPORTS" to "lequipe.fr ligue1.com flashscore.fr",
            "GENERAL:KNOWLEDGE" to "wikipedia.org larousse.fr"
        ),
        // 🇩🇪 Alemania
        "DE" to sites(
            "CINE" to "kino.de kinoheld.de cinemaxx.de uci-kinowelt.de",
            "EVENTOS" to "eventim.de ticketmaster.de reservix.de",
            "NEGOCIOS" to "finanzen.net onvista.de boerse-frankfurt.de bundesbank.de handelsblatt.com",
            "VENTAS" to "mydealz.de idealo.de amazon.de mediamarkt.de otto.de",
            "EMPLEOS" to "stepstone.de indeed.de arbeitsagentur.de xing.com",
            "VESTUARIO" to "zalando.de aboutyou.de otto.de hm.com",
            "MAQUILLAJE" to "douglas.de dm.de rossmann.de flaconi.de",
            "TIENDAS" to "mediamarkt.de saturn.de otto.de obi.de ikea.com amazon.de",
            "SUPERMERCADOS" to "rewe.de edeka.de lidl.de aldi-sued.de aldi-nord.de kaufland.de",
            "COMIDA:receta" to "chefkoch.de lecker.de essen-und-trinken.de",
            "COMIDA" to "tripadvisor.de lieferando.de",
            "DELIVERY" to "lieferando.de wolt.com ubereats.com",
            "VIAJES:requisitos" to "auswaertiges-amt.de",
            "VIAJES:bus" to "bahn.de flixbus.de omio.de",
            "VIAJES" to "check24.de opodo.de tui.com expedia.de",
            "VUELOS:estado" to "frankfurt-airport.com munich-airport.de flightradar24.com",
            "VUELOS" to "lufthansa.com eurowings.com condor.com skyscanner.de check24.de",
            "BUSES:inter" to "bahn.de flixbus.de omio.de",
            "BUSES" to "bahn.de bvg.de mvv-muenchen.de moovitapp.com",
            "VACACIONES" to "germany.travel holidaycheck.de tripadvisor.de",
            "HOTELES" to "booking.com hrs.de trivago.de check24.de holidaycheck.de",
            "ARRIENDOS" to "immobilienscout24.de immowelt.de wg-gesucht.de kleinanzeigen.de",
            "VEHICULOS" to "mobile.de autoscout24.de kleinanzeigen.de adac.de",
            "CLIMA" to "dwd.de wetter.com wetteronline.de",
            "TECNOLOGIA" to "heise.de golem.de chip.de computerbild.de idealo.de",
            "MEDICINA:farmacia" to "aponet.de shop-apotheke.com docmorris.de",
            "MEDICINA" to "gesundheitsinformation.de netdoktor.de apotheken-umschau.de",
            "SERIES" to "moviepilot.de filmstarts.de justwatch.com",
            "PELICULAS" to "moviepilot.de filmstarts.de justwatch.com kino.de",
            "JUEGOS" to "gamestar.de pcgames.de store.steampowered.com",
            "GENERAL:TRAMITE" to "bund.de elster.de",
            "GENERAL:SPORTS" to "kicker.de sportschau.de bundesliga.com",
            "GENERAL:KNOWLEDGE" to "wikipedia.org brockhaus.de"
        ),
        // 🇮🇹 Italia
        "IT" to sites(
            "CINE" to "comingsoon.it mymovies.it thespacecinema.it ucicinemas.it",
            "EVENTOS" to "ticketone.it vivaticket.com ticketmaster.it",
            "NEGOCIOS" to "borsaitaliana.it bancaditalia.it ilsole24ore.com milanofinanza.it",
            "VENTAS" to "trovaprezzi.it amazon.it mediaworld.it unieuro.it",
            "EMPLEOS" to "indeed.it infojobs.it subito.it linkedin.com",
            "VESTUARIO" to "zalando.it ovs.it yoox.com zara.com",
            "MAQUILLAJE" to "sephora.it douglas.it kikocosmetics.com",
            "TIENDAS" to "mediaworld.it unieuro.it amazon.it leroymerlin.it ikea.com",
            "SUPERMERCADOS" to "esselunga.it conad.it carrefour.it lidl.it doveconviene.it",
            "COMIDA:receta" to "giallozafferano.it cucchiaio.it misya.info",
            "COMIDA" to "thefork.it tripadvisor.it",
            "DELIVERY" to "justeat.it deliveroo.it glovoapp.com",
            "VIAJES:requisitos" to "viaggiaresicuri.it",
            "VIAJES:bus" to "trenitalia.com italotreno.com flixbus.it itabus.it",
            "VIAJES" to "edreams.it volagratis.com booking.com",
            "VUELOS:estado" to "adr.it milanoairports.it flightradar24.com",
            "VUELOS" to "ita-airways.com ryanair.com skyscanner.it volagratis.com",
            "BUSES:inter" to "trenitalia.com italotreno.com flixbus.it itabus.it",
            "BUSES" to "atm.it romamobilita.it trenitalia.com moovitapp.com",
            "VACACIONES" to "italia.it paesionline.it tripadvisor.it",
            "HOTELES" to "booking.com trivago.it airbnb.it",
            "ARRIENDOS" to "immobiliare.it idealista.it casa.it subito.it",
            "VEHICULOS" to "autoscout24.it subito.it automobile.it alvolante.it",
            "CLIMA" to "ilmeteo.it 3bmeteo.com meteo.it",
            "TECNOLOGIA" to "hdblog.it tomshw.it dday.it trovaprezzi.it",
            "MEDICINA:farmacia" to "farmaciediturno.org",
            "MEDICINA" to "issalute.it humanitas.it torrinomedica.it",
            "SERIES" to "comingsoon.it mymovies.it justwatch.com",
            "PELICULAS" to "comingsoon.it mymovies.it justwatch.com",
            "JUEGOS" to "multiplayer.it everyeye.it store.steampowered.com",
            "GENERAL:TRAMITE" to "agenziaentrate.gov.it inps.it governo.it",
            "GENERAL:SPORTS" to "gazzetta.it legaseriea.it flashscore.it",
            "GENERAL:KNOWLEDGE" to "wikipedia.org treccani.it"
        ),
        // 🇳🇱 Países Bajos
        "NL" to sites(
            "CINE" to "pathe.nl kinepolis.nl", "EVENTOS" to "ticketmaster.nl",
            "VENTAS" to "tweakers.net bol.com coolblue.nl", "TIENDAS" to "bol.com coolblue.nl",
            "EMPLEOS" to "indeed.nl nationalevacaturebank.nl", "SUPERMERCADOS" to "ah.nl jumbo.com lidl.nl plus.nl",
            "COMIDA:receta" to "leukerecepten.nl 24kitchen.nl", "DELIVERY" to "thuisbezorgd.nl",
            "VUELOS:estado" to "schiphol.nl flightradar24.com", "VUELOS" to "klm.com transavia.com skyscanner.nl",
            "BUSES" to "9292.nl ns.nl gvb.nl", "BUSES:inter" to "ns.nl flixbus.nl",
            "ARRIENDOS" to "funda.nl pararius.nl", "VEHICULOS" to "autoscout24.nl marktplaats.nl",
            "CLIMA" to "knmi.nl buienradar.nl weeronline.nl", "TECNOLOGIA" to "tweakers.net",
            "GENERAL:TRAMITE" to "rijksoverheid.nl belastingdienst.nl"
        ),
        // 🇧🇪 Bélgica
        "BE" to sites(
            "CINE" to "kinepolis.be", "SUPERMERCADOS" to "delhaize.be colruyt.be carrefour.eu",
            "EMPLEOS" to "vdab.be stepstone.be actiris.brussels",
            "VUELOS:estado" to "brusselsairport.be flightradar24.com", "VUELOS" to "brusselsairlines.com skyscanner.net",
            "BUSES" to "belgiantrain.be stib-mivb.be delijn.be", "ARRIENDOS" to "immoweb.be",
            "VEHICULOS" to "2dehands.be gocar.be", "CLIMA" to "meteo.be", "GENERAL:TRAMITE" to "belgium.be"
        ),
        // 🇨🇭 Suiza
        "CH" to sites(
            "CINE" to "cineman.ch pathe.ch", "EMPLEOS" to "jobs.ch jobup.ch",
            "SUPERMERCADOS" to "migros.ch coop.ch denner.ch", "VENTAS" to "toppreise.ch digitec.ch galaxus.ch",
            "TIENDAS" to "digitec.ch galaxus.ch", "ARRIENDOS" to "homegate.ch immoscout24.ch",
            "VEHICULOS" to "autoscout24.ch", "VUELOS:estado" to "flughafen-zuerich.ch flightradar24.com",
            "VUELOS" to "swiss.com skyscanner.ch", "BUSES" to "sbb.ch", "BUSES:inter" to "sbb.ch",
            "CLIMA" to "meteoswiss.admin.ch", "GENERAL:TRAMITE" to "ch.ch admin.ch"
        ),
        // 🇦🇹 Austria
        "AT" to sites(
            "CINE" to "cineplexx.at", "EMPLEOS" to "karriere.at ams.at", "VENTAS" to "geizhals.at willhaben.at",
            "SUPERMERCADOS" to "billa.at spar.at hofer.at", "ARRIENDOS" to "willhaben.at immobilienscout24.at",
            "VEHICULOS" to "willhaben.at", "VUELOS:estado" to "viennaairport.com flightradar24.com", "VUELOS" to "austrian.com skyscanner.at",
            "BUSES" to "wienerlinien.at oebb.at", "BUSES:inter" to "oebb.at flixbus.at", "CLIMA" to "geosphere.at",
            "GENERAL:TRAMITE" to "oesterreich.gv.at"
        ),
        // 🇮🇪 Irlanda
        "IE" to sites(
            "SUPERMERCADOS" to "tesco.ie dunnesstores.com supervalu.ie lidl.ie", "EMPLEOS" to "irishjobs.ie jobs.ie indeed.ie",
            "ARRIENDOS" to "daft.ie myhome.ie", "VEHICULOS" to "donedeal.ie carzone.ie",
            "VUELOS:estado" to "dublinairport.com flightradar24.com", "VUELOS" to "aerlingus.com ryanair.com skyscanner.ie",
            "BUSES" to "transportforireland.ie irishrail.ie buseireann.ie", "CLIMA" to "met.ie",
            "GENERAL:TRAMITE" to "gov.ie citizensinformation.ie revenue.ie"
        ),
        // 🇵🇱 Polonia
        "PL" to sites(
            "CINE" to "cinema-city.pl multikino.pl helios.pl", "EVENTOS" to "ebilet.pl eventim.pl",
            "VENTAS" to "ceneo.pl allegro.pl pepper.pl", "TIENDAS" to "allegro.pl ceneo.pl",
            "EMPLEOS" to "pracuj.pl olx.pl", "SUPERMERCADOS" to "biedronka.pl lidl.pl auchan.pl carrefour.pl",
            "COMIDA:receta" to "kwestiasmaku.com aniagotuje.pl", "DELIVERY" to "pyszne.pl wolt.com glovoapp.com",
            "VUELOS" to "lot.com wizzair.com skyscanner.pl", "BUSES" to "jakdojade.pl", "BUSES:inter" to "intercity.pl flixbus.pl",
            "ARRIENDOS" to "otodom.pl olx.pl", "VEHICULOS" to "otomoto.pl", "CLIMA" to "imgw.pl twojapogoda.pl",
            "TECNOLOGIA" to "benchmark.pl komputerswiat.pl", "GENERAL:TRAMITE" to "gov.pl"
        ),
        // 🇸🇪 Suecia
        "SE" to sites(
            "CINE" to "filmstaden.se", "EMPLEOS" to "arbetsformedlingen.se", "VENTAS" to "prisjakt.nu elgiganten.se",
            "SUPERMERCADOS" to "ica.se coop.se willys.se", "COMIDA:receta" to "ica.se koket.se", "DELIVERY" to "foodora.se wolt.com",
            "VUELOS:estado" to "swedavia.se flightradar24.com", "VUELOS" to "sas.se norwegian.com",
            "BUSES" to "sl.se vasttrafik.se", "BUSES:inter" to "sj.se flixbus.se", "ARRIENDOS" to "hemnet.se blocket.se",
            "VEHICULOS" to "blocket.se", "CLIMA" to "smhi.se", "GENERAL:TRAMITE" to "skatteverket.se"
        ),
        // 🇳🇴 Noruega
        "NO" to sites(
            "VENTAS" to "prisjakt.no finn.no", "EMPLEOS" to "finn.no nav.no", "SUPERMERCADOS" to "oda.com meny.no",
            "VUELOS:estado" to "avinor.no flightradar24.com", "VUELOS" to "norwegian.com sas.no",
            "BUSES" to "ruter.no entur.no", "BUSES:inter" to "vy.no entur.no", "ARRIENDOS" to "finn.no", "VEHICULOS" to "finn.no",
            "CLIMA" to "yr.no", "GENERAL:TRAMITE" to "skatteetaten.no"
        ),
        // 🇩🇰 Dinamarca
        "DK" to sites(
            "VENTAS" to "pricerunner.dk elgiganten.dk", "EMPLEOS" to "jobindex.dk", "SUPERMERCADOS" to "nemlig.com rema1000.dk coop.dk",
            "VUELOS:estado" to "cph.dk flightradar24.com", "VUELOS" to "sas.dk norwegian.com",
            "BUSES" to "rejseplanen.dk dsb.dk", "ARRIENDOS" to "boligsiden.dk boligportal.dk", "VEHICULOS" to "bilbasen.dk dba.dk",
            "CLIMA" to "dmi.dk", "GENERAL:TRAMITE" to "borger.dk skat.dk"
        ),
        // 🇫🇮 Finlandia
        "FI" to sites(
            "VENTAS" to "hintaopas.fi verkkokauppa.com tori.fi", "EMPLEOS" to "duunitori.fi tyomarkkinatori.fi",
            "SUPERMERCADOS" to "k-ruoka.fi s-kaupat.fi", "VUELOS:estado" to "finavia.fi flightradar24.com", "VUELOS" to "finnair.com",
            "BUSES" to "hsl.fi vr.fi", "BUSES:inter" to "vr.fi matkahuolto.fi", "ARRIENDOS" to "oikotie.fi etuovi.com",
            "VEHICULOS" to "nettiauto.com", "CLIMA" to "ilmatieteenlaitos.fi", "GENERAL:TRAMITE" to "suomi.fi vero.fi"
        ),
        // 🇬🇷 Grecia
        "GR" to sites(
            "VENTAS" to "skroutz.gr", "TIENDAS" to "skroutz.gr", "EMPLEOS" to "kariera.gr", "SUPERMERCADOS" to "sklavenitis.gr ab.gr mymarket.gr",
            "VUELOS:estado" to "aia.gr flightradar24.com", "VUELOS" to "aegeanair.com skyscanner.gr", "BUSES" to "oasa.gr hellenictrain.gr",
            "ARRIENDOS" to "spitogatos.gr xe.gr", "VEHICULOS" to "car.gr", "CLIMA" to "emy.gr meteo.gr", "GENERAL:TRAMITE" to "gov.gr"
        ),
        // 🇨🇿 República Checa
        "CZ" to sites(
            "VENTAS" to "heureka.cz alza.cz", "TIENDAS" to "alza.cz heureka.cz", "EMPLEOS" to "jobs.cz", "SUPERMERCADOS" to "rohlik.cz kosik.cz",
            "BUSES" to "idos.cz", "BUSES:inter" to "regiojet.cz cd.cz", "ARRIENDOS" to "sreality.cz", "VEHICULOS" to "sauto.cz",
            "CLIMA" to "chmi.cz", "GENERAL:TRAMITE" to "gov.cz"
        ),
        // 🇷🇴 Rumania
        "RO" to sites(
            "VENTAS" to "emag.ro compari.ro", "TIENDAS" to "emag.ro", "EMPLEOS" to "ejobs.ro bestjobs.eu",
            "SUPERMERCADOS" to "kaufland.ro carrefour.ro", "VUELOS" to "tarom.ro bucharestairports.ro skyscanner.ro",
            "BUSES" to "stbsa.ro cfrcalatori.ro", "ARRIENDOS" to "imobiliare.ro storia.ro olx.ro", "VEHICULOS" to "autovit.ro",
            "CLIMA" to "meteoromania.ro"
        ),
        // 🇭🇺 Hungría
        "HU" to sites(
            "VENTAS" to "arukereso.hu emag.hu", "EMPLEOS" to "profession.hu", "SUPERMERCADOS" to "tesco.hu spar.hu auchan.hu",
            "BUSES" to "bkk.hu menetrendek.hu", "BUSES:inter" to "mavcsoport.hu menetrendek.hu", "ARRIENDOS" to "ingatlan.com",
            "VEHICULOS" to "hasznaltauto.hu", "CLIMA" to "met.hu", "GENERAL:TRAMITE" to "magyarorszag.hu"
        ),
        // 🇺🇦 Ucrania
        "UA" to sites(
            "VENTAS" to "rozetka.com.ua hotline.ua prom.ua", "TIENDAS" to "rozetka.com.ua prom.ua", "EMPLEOS" to "work.ua robota.ua",
            "SUPERMERCADOS" to "silpo.ua atbmarket.com novus.ua", "BUSES:inter" to "uz.gov.ua busfor.ua", "ARRIENDOS" to "dom.ria.com olx.ua",
            "VEHICULOS" to "auto.ria.com", "CLIMA" to "sinoptik.ua", "GENERAL:TRAMITE" to "diia.gov.ua"
        ),
        // 🇦🇷 Argentina
        "AR" to sites(
            "CINE" to "cinemarkhoyts.com.ar cinepolis.com.ar",
            "EVENTOS" to "allaccess.com.ar ticketek.com.ar tuentrada.com passline.com eventbrite.com.ar",
            "NEGOCIOS" to "bcra.gob.ar byma.com.ar dolarhoy.com ambito.com cronista.com",
            "VENTAS" to "mercadolibre.com.ar fravega.com musimundo.com cybermonday.com.ar",
            "EMPLEOS" to "bumeran.com.ar zonajobs.com.ar ar.computrabajo.com linkedin.com",
            "VESTUARIO" to "dafiti.com.ar mercadolibre.com.ar grimoldi.com",
            "MAQUILLAJE" to "farmacity.com mercadolibre.com.ar",
            "TIENDAS" to "mercadolibre.com.ar fravega.com easy.com.ar sodimac.com.ar musimundo.com",
            "SUPERMERCADOS" to "preciosclaros.gob.ar cotodigital3.com.ar carrefour.com.ar disco.com.ar jumbo.com.ar dia.com.ar",
            "COMIDA:receta" to "cocinerosargentinos.com paulinacocina.net recetasgratis.net",
            "COMIDA" to "guiaoleo.com.ar tripadvisor.com.ar pedidosya.com.ar",
            "DELIVERY" to "pedidosya.com.ar rappi.com.ar",
            "VIAJES:requisitos" to "argentina.gob.ar migraciones.gob.ar",
            "VIAJES:bus" to "plataforma10.com.ar centraldepasajes.com.ar busbud.com",
            "VIAJES" to "despegar.com.ar turismocity.com.ar",
            "VUELOS:estado" to "aa2000.com.ar flightaware.com flightradar24.com",
            "VUELOS" to "aerolineas.com.ar flybondi.com jetsmart.com despegar.com.ar turismocity.com.ar",
            "BUSES:inter" to "plataforma10.com.ar centraldepasajes.com.ar busbud.com",
            "BUSES" to "moovitapp.com buenosaires.gob.ar argentina.gob.ar",
            "VACACIONES" to "tripadvisor.com.ar parquesnacionales.gob.ar despegar.com.ar",
            "HOTELES" to "booking.com despegar.com.ar trivago.com.ar airbnb.com.ar",
            "ARRIENDOS" to "zonaprop.com.ar argenprop.com mercadolibre.com.ar",
            "VEHICULOS" to "autos.mercadolibre.com.ar kavak.com deruedas.com.ar acara.org.ar",
            "CLIMA" to "smn.gob.ar meteored.com.ar tiempo.com",
            "TECNOLOGIA" to "mercadolibre.com.ar fravega.com xataka.com gsmarena.com",
            "MEDICINA:farmacia" to "farmacity.com farmaciasdeturno.com.ar",
            "MEDICINA" to "argentina.gob.ar medlineplus.gov mayoclinic.org",
            "GENERAL:TRAMITE" to "argentina.gob.ar arca.gob.ar afip.gob.ar anses.gob.ar",
            "GENERAL:SPORTS" to "promiedos.com.ar tycsports.com ole.com.ar"
        ),
        // 🇵🇪 Perú
        "PE" to sites(
            "CINE" to "cineplanet.com.pe cinemark-peru.com cinestar.com.pe",
            "EVENTOS" to "teleticket.com.pe joinnus.com ticketmaster.pe",
            "NEGOCIOS" to "bcrp.gob.pe bvl.com.pe gestion.pe",
            "VENTAS" to "falabella.com.pe ripley.com.pe mercadolibre.com.pe plazavea.com.pe cyberdays.pe",
            "EMPLEOS" to "bumeran.com.pe pe.computrabajo.com laborum.pe empleosperu.gob.pe",
            "VESTUARIO" to "falabella.com.pe ripley.com.pe oechsle.pe",
            "TIENDAS" to "falabella.com.pe ripley.com.pe oechsle.pe sodimac.com.pe promart.pe",
            "SUPERMERCADOS" to "plazavea.com.pe wong.pe metro.pe tottus.com.pe",
            "COMIDA:receta" to "recetasgratis.net cookpad.com",
            "COMIDA" to "tripadvisor.com.pe rappi.com.pe",
            "DELIVERY" to "rappi.com.pe didi-food.com",
            "VIAJES:bus" to "cruzdelsur.com.pe oltursa.pe redbus.pe busbud.com",
            "VIAJES" to "despegar.com.pe peru.travel",
            "VUELOS:estado" to "lima-airport.com flightaware.com flightradar24.com",
            "VUELOS" to "latamairlines.com skyairline.com jetsmart.com despegar.com.pe",
            "BUSES:inter" to "cruzdelsur.com.pe oltursa.pe redbus.pe busbud.com",
            "BUSES" to "moovitapp.com atu.gob.pe lineauno.pe",
            "VACACIONES" to "peru.travel ytuqueplanes.com tripadvisor.com.pe",
            "HOTELES" to "booking.com despegar.com.pe trivago.pe",
            "ARRIENDOS" to "urbania.pe adondevivir.com properati.com.pe",
            "VEHICULOS" to "neoauto.com mercadolibre.com.pe",
            "CLIMA" to "senamhi.gob.pe tiempo.com",
            "MEDICINA:farmacia" to "inkafarma.pe mifarma.com.pe",
            "MEDICINA" to "gob.pe medlineplus.gov",
            "GENERAL:TRAMITE" to "gob.pe sunat.gob.pe reniec.gob.pe",
            "GENERAL:SPORTS" to "depor.com dechalaca.com flashscore.pe"
        ),
        // 🇨🇴 Colombia
        "CO" to sites(
            "CINE" to "cinecolombia.com cinemark.com.co procinal.com.co",
            "EVENTOS" to "tuboleta.com eticket.co",
            "NEGOCIOS" to "banrep.gov.co bvc.com.co larepublica.co portafolio.co",
            "VENTAS" to "mercadolibre.com.co exito.com falabella.com.co alkosto.com ktronix.com",
            "EMPLEOS" to "elempleo.com co.computrabajo.com serviciodeempleo.gov.co linkedin.com",
            "VESTUARIO" to "falabella.com.co exito.com dafiti.com.co",
            "TIENDAS" to "exito.com falabella.com.co alkosto.com homecenter.com.co mercadolibre.com.co",
            "SUPERMERCADOS" to "exito.com carulla.com olimpica.com tiendasjumbo.co",
            "COMIDA:receta" to "recetasgratis.net cookpad.com mycolombianrecipes.com",
            "COMIDA" to "tripadvisor.co rappi.com.co",
            "DELIVERY" to "rappi.com.co didi-food.com",
            "VIAJES:bus" to "pinbus.com redbus.co expresobolivariano.com.co busbud.com",
            "VIAJES" to "despegar.com.co colombia.travel",
            "VUELOS:estado" to "eldorado.aero flightaware.com flightradar24.com",
            "VUELOS" to "avianca.com latamairlines.com wingo.com jetsmart.com despegar.com.co",
            "BUSES:inter" to "pinbus.com redbus.co expresobolivariano.com.co busbud.com",
            "BUSES" to "moovitapp.com transmilenio.gov.co metrodemedellin.gov.co",
            "VACACIONES" to "colombia.travel parquesnacionales.gov.co tripadvisor.co",
            "HOTELES" to "booking.com despegar.com.co trivago.com.co",
            "ARRIENDOS" to "fincaraiz.com.co metrocuadrado.com ciencuadras.com",
            "VEHICULOS" to "tucarro.com.co carroya.com mercadolibre.com.co",
            "CLIMA" to "ideam.gov.co tiempo.com",
            "MEDICINA:farmacia" to "cruzverde.com.co larebajavirtual.com locatelcolombia.com",
            "MEDICINA" to "minsalud.gov.co medlineplus.gov",
            "GENERAL:TRAMITE" to "gov.co dian.gov.co registraduria.gov.co",
            "GENERAL:SPORTS" to "futbolred.com dimayor.com.co flashscore.co"
        ),
        // 🇲🇽 México
        "MX" to sites(
            "CINE" to "cinepolis.com cinemex.com",
            "EVENTOS" to "ticketmaster.com.mx eticket.mx boletia.com",
            "NEGOCIOS" to "banxico.org.mx bmv.com.mx elfinanciero.com.mx eleconomista.com.mx",
            "VENTAS" to "mercadolibre.com.mx amazon.com.mx liverpool.com.mx coppel.com elbuenfin.org",
            "EMPLEOS" to "occ.com.mx mx.computrabajo.com indeed.com.mx empleo.gob.mx",
            "VESTUARIO" to "liverpool.com.mx coppel.com mercadolibre.com.mx",
            "MAQUILLAJE" to "sephora.com.mx liverpool.com.mx sanborns.com.mx",
            "TIENDAS" to "liverpool.com.mx coppel.com homedepot.com.mx elektra.com.mx mercadolibre.com.mx",
            "SUPERMERCADOS" to "walmart.com.mx soriana.com chedraui.com.mx lacomer.com.mx heb.com.mx",
            "COMIDA:receta" to "kiwilimon.com cocinafacil.com.mx recetasgratis.net",
            "COMIDA" to "tripadvisor.com.mx rappi.com.mx",
            "DELIVERY" to "rappi.com.mx ubereats.com didi-food.com",
            "VIAJES:bus" to "ado.com.mx etn.com.mx primeraplus.com.mx clickbus.com.mx",
            "VIAJES" to "despegar.com.mx bestday.com.mx visitmexico.com",
            "VUELOS:estado" to "aicm.com.mx flightaware.com flightradar24.com",
            "VUELOS" to "aeromexico.com volaris.com vivaaerobus.com despegar.com.mx",
            "BUSES:inter" to "ado.com.mx etn.com.mx primeraplus.com.mx clickbus.com.mx",
            "BUSES" to "moovitapp.com metro.cdmx.gob.mx semovi.cdmx.gob.mx",
            "VACACIONES" to "visitmexico.com tripadvisor.com.mx bestday.com.mx",
            "HOTELES" to "booking.com despegar.com.mx trivago.com.mx bestday.com.mx",
            "ARRIENDOS" to "inmuebles24.com vivanuncios.com.mx lamudi.com.mx",
            "VEHICULOS" to "seminuevos.com kavak.com autos.mercadolibre.com.mx",
            "CLIMA" to "smn.conagua.gob.mx meteored.mx",
            "MEDICINA:farmacia" to "farmaciasguadalajara.com fahorro.com benavides.com.mx",
            "MEDICINA" to "gob.mx medlineplus.gov",
            "GENERAL:TRAMITE" to "gob.mx sat.gob.mx",
            "GENERAL:SPORTS" to "mediotiempo.com ligamx.net record.com.mx"
        ),
        // 🇻🇪 Venezuela
        "VE" to sites(
            "CINE" to "cinex.com.ve cinesunidos.com",
            "EVENTOS" to "ticketmundo.com",
            "NEGOCIOS" to "bcv.org.ve",
            "EMPLEOS" to "ve.computrabajo.com bumeran.com.ve",
            "SUPERMERCADOS" to "gamaenlinea.com farmatodo.com.ve",
            "VUELOS" to "conviasa.aero avior.com.ve laserairlines.com despegar.com",
            "BUSES" to "moovitapp.com metrodecaracas.gob.ve",
            "ARRIENDOS" to "tuinmueble.com.ve",
            "VEHICULOS" to "tucarro.com.ve",
            "CLIMA" to "inameh.gob.ve",
            "MEDICINA:farmacia" to "farmatodo.com.ve locatel.com.ve",
            "GENERAL:TRAMITE" to "saime.gob.ve seniat.gob.ve"
        ),
        // 🇧🇴 Bolivia
        "BO" to sites(
            "CINE" to "cinecenter.com.bo",
            "NEGOCIOS" to "bcb.gob.bo",
            "EMPLEOS" to "bo.computrabajo.com",
            "SUPERMERCADOS" to "hipermaxi.com",
            "VUELOS" to "boa.bo despegar.com",
            "BUSES" to "moovitapp.com miteleferico.bo",
            "ARRIENDOS" to "ultracasas.com infocasas.com.bo",
            "CLIMA" to "senamhi.gob.bo",
            "GENERAL:TRAMITE" to "gob.bo impuestos.gob.bo"
        ),
        // 🇪🇨 Ecuador
        "EC" to sites(
            "CINE" to "supercines.com cinemark.com.ec",
            "EVENTOS" to "ticketshow.com.ec",
            "NEGOCIOS" to "bce.fin.ec",
            "EMPLEOS" to "multitrabajos.com ec.computrabajo.com",
            "SUPERMERCADOS" to "supermaxi.com",
            "TIENDAS" to "mercadolibre.com.ec",
            "VUELOS" to "latamairlines.com avianca.com despegar.com.ec",
            "BUSES" to "moovitapp.com",
            "ARRIENDOS" to "plusvalia.com",
            "VEHICULOS" to "patiotuerca.com",
            "CLIMA" to "inamhi.gob.ec",
            "MEDICINA:farmacia" to "fybeca.com",
            "GENERAL:TRAMITE" to "gob.ec sri.gob.ec"
        ),
        // 🇺🇾 Uruguay
        "UY" to sites(
            "CINE" to "movie.com.uy grupocine.com.uy",
            "EVENTOS" to "tickantel.com.uy redtickets.uy",
            "NEGOCIOS" to "bcu.gub.uy",
            "VENTAS" to "mercadolibre.com.uy tiendainglesa.com.uy",
            "TIENDAS" to "mercadolibre.com.uy tiendainglesa.com.uy",
            "EMPLEOS" to "buscojobs.com.uy uy.computrabajo.com",
            "SUPERMERCADOS" to "tiendainglesa.com.uy disco.com.uy devoto.com.uy tata.com.uy",
            "VUELOS:estado" to "aeropuertodecarrasco.com.uy flightaware.com",
            "VUELOS" to "despegar.com.uy latamairlines.com",
            "BUSES:inter" to "trescruces.com.uy busbud.com",
            "BUSES" to "montevideo.gub.uy moovitapp.com",
            "ARRIENDOS" to "gallito.com.uy infocasas.com.uy mercadolibre.com.uy",
            "VEHICULOS" to "autos.mercadolibre.com.uy",
            "CLIMA" to "inumet.gub.uy",
            "MEDICINA:farmacia" to "farmashop.com.uy",
            "GENERAL:TRAMITE" to "gub.uy dgi.gub.uy"
        ),
        // 🇵🇾 Paraguay
        "PY" to sites(
            "NEGOCIOS" to "bcp.gov.py",
            "EMPLEOS" to "py.computrabajo.com",
            "SUPERMERCADOS" to "superseis.com.py biggie.com.py",
            "VUELOS" to "despegar.com.py latamairlines.com paranair.com",
            "ARRIENDOS" to "infocasas.com.py",
            "CLIMA" to "meteorologia.gov.py",
            "GENERAL:TRAMITE" to "paraguay.gov.py dnit.gov.py"
        ),
        // 🇪🇸 España
        "ES" to sites(
            "CINE" to "ecartelera.com cinesa.es yelmocines.es kinepolis.es",
            "EVENTOS" to "ticketmaster.es entradas.com eventbrite.es wegow.com",
            "NEGOCIOS" to "bolsamadrid.es bde.es expansion.com",
            "VENTAS" to "chollometro.com amazon.es elcorteingles.es mediamarkt.es pccomponentes.com",
            "EMPLEOS" to "infojobs.net indeed.es sepe.es linkedin.com",
            "VESTUARIO" to "zara.com elcorteingles.es zalando.es hm.com mango.com",
            "MAQUILLAJE" to "sephora.es druni.es primor.eu douglas.es",
            "TIENDAS" to "elcorteingles.es amazon.es mediamarkt.es leroymerlin.es ikea.com",
            "SUPERMERCADOS" to "mercadona.es carrefour.es lidl.es dia.es alcampo.es eroski.es",
            "COMIDA:receta" to "recetasgratis.net directoalpaladar.com hogarmania.com cookpad.com",
            "COMIDA" to "thefork.es tripadvisor.es",
            "DELIVERY" to "glovoapp.com ubereats.com just-eat.es",
            "VIAJES:requisitos" to "exteriores.gob.es",
            "VIAJES:bus" to "alsa.es flixbus.es renfe.com omio.es",
            "VIAJES" to "edreams.es rumbo.es atrapalo.com booking.com",
            "VUELOS:estado" to "aena.es flightradar24.com",
            "VUELOS" to "iberia.com vueling.com aireuropa.com ryanair.com skyscanner.es edreams.es",
            "BUSES:inter" to "alsa.es flixbus.es renfe.com omio.es",
            "BUSES" to "moovitapp.com emtmadrid.es tmb.cat crtm.es renfe.com",
            "VACACIONES" to "spain.info minube.com tripadvisor.es",
            "HOTELES" to "booking.com trivago.es rumbo.es airbnb.es",
            "ARRIENDOS" to "idealista.com fotocasa.es habitaclia.com pisos.com",
            "VEHICULOS" to "coches.net autoscout24.es wallapop.com coches.com",
            "CLIMA" to "aemet.es eltiempo.es meteored.com",
            "TECNOLOGIA" to "xataka.com pccomponentes.com mediamarkt.es hipertextual.com",
            "MEDICINA" to "cun.es aemps.gob.es medlineplus.gov",
            "SERIES" to "filmaffinity.com justwatch.com sensacine.com",
            "PELICULAS" to "filmaffinity.com ecartelera.com sensacine.com justwatch.com",
            "GENERAL:TRAMITE" to "administracion.gob.es agenciatributaria.es sede.dgt.gob.es",
            "GENERAL:SPORTS" to "laliga.com flashscore.es marca.com as.com"
        ),
        // 🇨🇷 Costa Rica
        "CR" to sites(
            "CINE" to "cinepolis.co.cr novacinemas.cr",
            "EMPLEOS" to "cr.computrabajo.com empleos.net",
            "SUPERMERCADOS" to "walmart.co.cr automercado.cr masxmenos.cr",
            "VUELOS" to "copaair.com avianca.com despegar.com sjoairport.com",
            "ARRIENDOS" to "encuentra24.com",
            "VEHICULOS" to "crautos.com encuentra24.com",
            "CLIMA" to "imn.ac.cr",
            "GENERAL:TRAMITE" to "hacienda.go.cr"
        ),
        // 🇵🇦 Panamá
        "PA" to sites(
            "CINE" to "cinepolis.com.pa",
            "EMPLEOS" to "konzerta.com pa.computrabajo.com",
            "SUPERMERCADOS" to "superxtra.com elmachetazo.com ribasmith.com",
            "VUELOS:estado" to "tocumenpanama.aero flightaware.com",
            "VUELOS" to "copaair.com despegar.com",
            "ARRIENDOS" to "encuentra24.com",
            "VEHICULOS" to "encuentra24.com",
            "CLIMA" to "imhpa.gob.pa",
            "GENERAL:TRAMITE" to "gob.pa"
        ),
        // 🇩🇴 República Dominicana
        "DO" to sites(
            "CINE" to "caribbeancinemas.com palaciodelcine.com.do",
            "EMPLEOS" to "do.computrabajo.com",
            "SUPERMERCADOS" to "sirena.do",
            "VUELOS:estado" to "aerodom.com flightaware.com",
            "VUELOS" to "arajet.com despegar.com.do",
            "ARRIENDOS" to "corotos.com.do supercasas.com",
            "VEHICULOS" to "supercarros.com corotos.com.do",
            "CLIMA" to "onamet.gob.do",
            "GENERAL:TRAMITE" to "gob.do dgii.gov.do"
        ),
        // 🇬🇹 Guatemala
        "GT" to sites(
            "CINE" to "cinepolis.com.gt",
            "EMPLEOS" to "tecoloco.com.gt gt.computrabajo.com",
            "SUPERMERCADOS" to "walmart.com.gt",
            "VUELOS" to "avianca.com despegar.com",
            "ARRIENDOS" to "encuentra24.com",
            "VEHICULOS" to "encuentra24.com",
            "CLIMA" to "insivumeh.gob.gt",
            "GENERAL:TRAMITE" to "sat.gob.gt"
        ),
        // 🇺🇸 Estados Unidos (inglés)
        "US" to sites(
            "CINE" to "fandango.com amctheatres.com regmovies.com cinemark.com",
            "VIDEOS" to "youtube.com vimeo.com tiktok.com",
            "MUSICA" to "spotify.com music.apple.com billboard.com pandora.com",
            "EVENTOS" to "ticketmaster.com livenation.com eventbrite.com stubhub.com seatgeek.com",
            "NEGOCIOS" to "finance.yahoo.com marketwatch.com investing.com sec.gov cnbc.com",
            "VENTAS" to "slickdeals.net amazon.com bestbuy.com walmart.com target.com",
            "EMPLEOS" to "indeed.com linkedin.com glassdoor.com ziprecruiter.com usajobs.gov",
            "VESTUARIO" to "nordstrom.com macys.com gap.com hm.com zara.com amazon.com",
            "MAQUILLAJE" to "sephora.com ulta.com target.com amazon.com",
            "TIENDAS" to "amazon.com walmart.com target.com bestbuy.com homedepot.com costco.com",
            "SUPERMERCADOS" to "walmart.com kroger.com target.com costco.com safeway.com instacart.com",
            "COMIDA:receta" to "allrecipes.com foodnetwork.com seriouseats.com simplyrecipes.com",
            "COMIDA" to "yelp.com tripadvisor.com opentable.com doordash.com",
            "DELIVERY" to "doordash.com ubereats.com grubhub.com instacart.com",
            "VIAJES:requisitos" to "travel.state.gov cbp.gov iatatravelcentre.com",
            "VIAJES:bus" to "greyhound.com flixbus.com megabus.com wanderu.com amtrak.com",
            "VIAJES" to "expedia.com kayak.com tripadvisor.com priceline.com",
            "VUELOS:estado" to "flightaware.com flightradar24.com",
            "VUELOS" to "kayak.com expedia.com skyscanner.com aa.com delta.com united.com southwest.com",
            "BUSES:inter" to "greyhound.com flixbus.com megabus.com wanderu.com amtrak.com",
            "BUSES" to "transitapp.com moovitapp.com mta.info wmata.com",
            "VACACIONES" to "tripadvisor.com nps.gov lonelyplanet.com visittheusa.com",
            "HOTELES" to "booking.com hotels.com expedia.com marriott.com hilton.com airbnb.com",
            "ARRIENDOS" to "zillow.com apartments.com realtor.com redfin.com trulia.com",
            "VEHICULOS" to "cars.com autotrader.com carmax.com kbb.com edmunds.com carvana.com",
            "CLIMA" to "weather.gov weather.com accuweather.com",
            "TECNOLOGIA" to "theverge.com cnet.com arstechnica.com wired.com technologyreview.com bestbuy.com",
            "ASTRONOMIA" to "nasa.gov timeanddate.com skyandtelescope.org space.com",
            "MEDICINA:farmacia" to "cvs.com walgreens.com riteaid.com",
            "MEDICINA" to "medlineplus.gov mayoclinic.org cdc.gov webmd.com",
            "SERIES" to "justwatch.com imdb.com rottentomatoes.com",
            "PELICULAS" to "justwatch.com imdb.com rottentomatoes.com fandango.com",
            "JUEGOS" to "store.steampowered.com ign.com gamespot.com metacritic.com",
            "GENERAL:TRAMITE" to "usa.gov irs.gov ssa.gov uscis.gov",
            "GENERAL:TUTORIAL" to "wikihow.com support.google.com support.apple.com support.microsoft.com",
            "GENERAL:SPORTS" to "espn.com cbssports.com nba.com nfl.com mlb.com",
            "GENERAL:KNOWLEDGE" to "wikipedia.org britannica.com nationalgeographic.com nasa.gov"
        ),
        // 🇧🇷 Brasil (portugués)
        "BR" to sites(
            "CINE" to "ingresso.com cinemark.com.br kinoplex.com.br adorocinema.com",
            "VIDEOS" to "youtube.com tiktok.com globoplay.globo.com",
            "MUSICA" to "spotify.com deezer.com music.apple.com",
            "EVENTOS" to "sympla.com.br ingresso.com eventim.com.br ticketmaster.com.br",
            "NEGOCIOS" to "infomoney.com.br valor.globo.com b3.com.br bcb.gov.br",
            "VENTAS" to "zoom.com.br buscape.com.br promobit.com.br mercadolivre.com.br amazon.com.br",
            "EMPLEOS" to "vagas.com.br catho.com.br indeed.com.br infojobs.com.br linkedin.com",
            "VESTUARIO" to "renner.com.br cea.com.br riachuelo.com.br dafiti.com.br netshoes.com.br",
            "MAQUILLAJE" to "sephora.com.br boticario.com.br natura.com.br belezanaweb.com.br",
            "TIENDAS" to "mercadolivre.com.br magazineluiza.com.br americanas.com.br casasbahia.com.br amazon.com.br",
            "SUPERMERCADOS" to "carrefour.com.br paodeacucar.com assai.com.br atacadao.com.br zoom.com.br",
            "COMIDA:receta" to "tudogostoso.com.br panelinha.com.br receitas.globo.com",
            "COMIDA" to "tripadvisor.com.br ifood.com.br",
            "DELIVERY" to "ifood.com.br rappi.com.br ubereats.com",
            "VIAJES:requisitos" to "gov.br itamaraty.gov.br iatatravelcentre.com",
            "VIAJES:bus" to "clickbus.com.br buscaonibus.com.br queropassagem.com.br",
            "VIAJES" to "decolar.com cvc.com.br maxmilhas.com.br tripadvisor.com.br",
            "VUELOS:estado" to "flightaware.com flightradar24.com",
            "VUELOS" to "latamairlines.com voegol.com.br voeazul.com.br decolar.com skyscanner.com.br kayak.com.br",
            "BUSES:inter" to "clickbus.com.br buscaonibus.com.br queropassagem.com.br",
            "BUSES" to "moovitapp.com sptrans.com.br",
            "VACACIONES" to "tripadvisor.com.br decolar.com viajenaviagem.com",
            "HOTELES" to "booking.com trivago.com.br decolar.com hoteis.com airbnb.com.br",
            "ARRIENDOS" to "zapimoveis.com.br vivareal.com.br quintoandar.com.br olx.com.br",
            "VEHICULOS" to "webmotors.com.br olx.com.br icarros.com.br mobiauto.com.br veiculos.fipe.org.br",
            "CLIMA" to "climatempo.com.br inmet.gov.br tempo.com",
            "TECNOLOGIA" to "tecmundo.com.br tecnoblog.net olhardigital.com.br canaltech.com.br zoom.com.br",
            "ASTRONOMIA" to "timeanddate.com nasa.gov inpe.br",
            "MEDICINA:farmacia" to "drogasil.com.br drogaraia.com.br paguemenos.com.br",
            "MEDICINA" to "msdmanuals.com gov.br mayoclinic.org",
            "SERIES" to "justwatch.com adorocinema.com imdb.com",
            "PELICULAS" to "justwatch.com adorocinema.com imdb.com ingresso.com",
            "JUEGOS" to "store.steampowered.com nuuvem.com playstation.com br.ign.com",
            "GENERAL:TRAMITE" to "gov.br receita.fazenda.gov.br",
            "GENERAL:TUTORIAL" to "wikihow.com support.google.com support.apple.com",
            "GENERAL:SPORTS" to "ge.globo.com espn.com.br flashscore.com.br",
            "GENERAL:KNOWLEDGE" to "wikipedia.org todamateria.com.br brasilescola.uol.com.br"
        ),
        // 🇵🇹 Portugal (portugués)
        "PT" to sites(
            "CINE" to "cinemas.nos.pt cinemacity.pt ucicinemas.pt",
            "EVENTOS" to "ticketline.sapo.pt blueticket.pt bol.pt",
            "VENTAS" to "kuantokusta.pt olx.pt worten.pt fnac.pt",
            "EMPLEOS" to "net-empregos.com indeed.pt sapo.pt linkedin.com",
            "TIENDAS" to "worten.pt fnac.pt radiopopular.pt olx.pt",
            "SUPERMERCADOS" to "continente.pt pingodoce.pt auchan.pt lidl.pt kuantokusta.pt",
            "COMIDA:receta" to "teleculinaria.pt pingodoce.pt cookpad.com",
            "COMIDA" to "thefork.pt tripadvisor.pt zomato.com",
            "DELIVERY" to "glovoapp.com ubereats.com boltfood.pt",
            "VUELOS" to "flytap.com skyscanner.pt edreams.pt ryanair.com easyjet.com",
            "VUELOS:estado" to "flightaware.com flightradar24.com aeroportolisboa.pt",
            "BUSES:inter" to "rede-expressos.pt flixbus.pt cp.pt",
            "BUSES" to "moovitapp.com carris.pt metrolisboa.pt cp.pt",
            "HOTELES" to "booking.com trivago.pt airbnb.pt",
            "ARRIENDOS" to "idealista.pt imovirtual.com casa.sapo.pt",
            "VEHICULOS" to "standvirtual.com olx.pt",
            "CLIMA" to "ipma.pt tempo.pt",
            "GENERAL:TRAMITE" to "gov.pt eportugal.gov.pt portaldasfinancas.gov.pt",
            "GENERAL:SPORTS" to "zerozero.pt record.pt flashscore.pt"
        ),
        // 🇷🇺 Rusia
        "RU" to sites(
            "CINE" to "afisha.ru kinopoisk.ru kassir.ru",
            "VIDEOS" to "rutube.ru vk.com dzen.ru",
            "MUSICA" to "music.yandex.ru zvuk.com vk.com",
            "EVENTOS" to "afisha.ru kassir.ru ticketland.ru",
            "NEGOCIOS" to "rbc.ru cbr.ru moex.com investing.com",
            "VENTAS" to "ozon.ru wildberries.ru market.yandex.ru avito.ru",
            "EMPLEOS" to "hh.ru superjob.ru trudvsem.ru avito.ru",
            "VESTUARIO" to "wildberries.ru lamoda.ru ozon.ru",
            "MAQUILLAJE" to "goldapple.ru letu.ru wildberries.ru",
            "TIENDAS" to "ozon.ru wildberries.ru market.yandex.ru dns-shop.ru mvideo.ru",
            "SUPERMERCADOS" to "perekrestok.ru 5ka.ru magnit.ru auchan.ru lenta.com",
            "COMIDA:receta" to "eda.ru povarenok.ru",
            "COMIDA" to "afisha.ru 2gis.ru tripadvisor.ru",
            "DELIVERY" to "eda.yandex.ru samokat.ru vkusvill.ru",
            "VIAJES" to "aviasales.ru tutu.ru ostrovok.ru",
            "VIAJES:bus" to "busfor.ru tutu.ru rzd.ru",
            "VUELOS:estado" to "flightradar24.com svo.aero",
            "VUELOS" to "aviasales.ru tutu.ru aeroflot.ru s7.ru pobeda.aero",
            "BUSES:inter" to "busfor.ru tutu.ru rzd.ru",
            "BUSES" to "2gis.ru transport.mos.ru mosgortrans.ru",
            "VACACIONES" to "tripadvisor.ru tutu.ru ostrovok.ru",
            "HOTELES" to "ostrovok.ru sutochno.ru 101hotels.com tvil.ru",
            "ARRIENDOS" to "cian.ru avito.ru domclick.ru",
            "VEHICULOS" to "auto.ru drom.ru avito.ru",
            "CLIMA" to "gismeteo.ru meteoinfo.ru rp5.ru",
            "TECNOLOGIA" to "habr.com ixbt.com 3dnews.ru dns-shop.ru",
            "ASTRONOMIA" to "timeanddate.com roscosmos.ru nasa.gov",
            "MEDICINA:farmacia" to "apteka.ru eapteka.ru",
            "MEDICINA" to "rlsnet.ru minzdrav.gov.ru",
            "SERIES" to "kinopoisk.ru ivi.ru okko.tv",
            "PELICULAS" to "kinopoisk.ru ivi.ru okko.tv afisha.ru",
            "JUEGOS" to "store.steampowered.com stopgame.ru igromania.ru",
            "GENERAL:TRAMITE" to "gosuslugi.ru nalog.gov.ru",
            "GENERAL:TUTORIAL" to "wikihow.com support.google.com",
            "GENERAL:SPORTS" to "sports.ru championat.com flashscore.ru",
            "GENERAL:KNOWLEDGE" to "wikipedia.org bigenc.ru"
        ),
        // 🇨🇳 China (Google, YouTube y Wikipedia están bloqueados allá)
        "CN" to sites(
            "CINE" to "maoyan.com taopiaopiao.com douban.com",
            "VIDEOS" to "bilibili.com douyin.com youku.com iqiyi.com",
            "MUSICA" to "music.163.com y.qq.com kugou.com",
            "EVENTOS" to "damai.cn maoyan.com",
            "NEGOCIOS" to "eastmoney.com xueqiu.com sse.com.cn pbc.gov.cn",
            "VENTAS" to "smzdm.com jd.com tmall.com taobao.com pinduoduo.com",
            "EMPLEOS" to "zhipin.com zhaopin.com 51job.com liepin.com",
            "VESTUARIO" to "tmall.com taobao.com vip.com jd.com",
            "MAQUILLAJE" to "tmall.com jd.com xiaohongshu.com",
            "TIENDAS" to "jd.com tmall.com taobao.com pinduoduo.com",
            "SUPERMERCADOS" to "jd.com tmall.com meituan.com smzdm.com",
            "COMIDA:receta" to "xiachufang.com meishij.net",
            "COMIDA" to "dianping.com meituan.com",
            "DELIVERY" to "meituan.com ele.me",
            "VIAJES" to "ctrip.com trip.com qunar.com fliggy.com",
            "VIAJES:bus" to "12306.cn ctrip.com",
            "VUELOS:estado" to "variflight.com flightradar24.com",
            "VUELOS" to "ctrip.com trip.com qunar.com airchina.com.cn csair.com ceair.com",
            "BUSES:inter" to "12306.cn ctrip.com",
            "BUSES" to "amap.com map.baidu.com",
            "VACACIONES" to "ctrip.com mafengwo.cn qunar.com",
            "HOTELES" to "ctrip.com trip.com qunar.com meituan.com booking.com",
            "ARRIENDOS" to "lianjia.com ke.com anjuke.com 58.com",
            "VEHICULOS" to "autohome.com.cn dongchedi.com che168.com guazi.com",
            "CLIMA" to "weather.com.cn nmc.cn",
            "TECNOLOGIA" to "ithome.com zol.com.cn 36kr.com jd.com",
            "ASTRONOMIA" to "cnsa.gov.cn timeanddate.com",
            "MEDICINA" to "dxy.cn nhc.gov.cn baike.baidu.com",
            "SERIES" to "douban.com iqiyi.com v.qq.com youku.com",
            "PELICULAS" to "douban.com maoyan.com iqiyi.com",
            "JUEGOS" to "taptap.cn 3dmgame.com store.steampowered.com",
            "GOOGLE_PLAY" to "taptap.cn app.mi.com",
            "GENERAL:TRAMITE" to "gov.cn",
            "GENERAL:SPORTS" to "hupu.com dongqiudi.com",
            "GENERAL:KNOWLEDGE" to "baike.baidu.com zhihu.com",
            "GENERAL:TUTORIAL" to "zhihu.com baike.baidu.com"
        ),
        // 🇰🇷 Corea del Sur
        "KR" to sites(
            "CINE" to "cgv.co.kr lottecinema.co.kr megabox.co.kr",
            "MUSICA" to "melon.com genie.co.kr bugs.co.kr",
            "EVENTOS" to "interpark.com ticketlink.co.kr yes24.com",
            "NEGOCIOS" to "finance.naver.com krx.co.kr bok.or.kr hankyung.com",
            "VENTAS" to "danawa.com coupang.com gmarket.co.kr 11st.co.kr",
            "EMPLEOS" to "saramin.co.kr jobkorea.co.kr wanted.co.kr work.go.kr",
            "VESTUARIO" to "musinsa.com 29cm.co.kr zigzag.kr coupang.com",
            "MAQUILLAJE" to "oliveyoung.co.kr hwahae.co.kr coupang.com",
            "TIENDAS" to "coupang.com gmarket.co.kr 11st.co.kr ssg.com danawa.com",
            "SUPERMERCADOS" to "ssg.com lotteon.com homeplus.co.kr kurly.com coupang.com",
            "COMIDA:receta" to "10000recipe.com",
            "COMIDA" to "diningcode.com map.naver.com map.kakao.com",
            "DELIVERY" to "baemin.com yogiyo.co.kr coupangeats.com",
            "VIAJES" to "interpark.com hanatour.com skyscanner.co.kr",
            "VIAJES:bus" to "kobus.co.kr letskorail.com",
            "VUELOS:estado" to "airport.kr flightradar24.com",
            "VUELOS" to "koreanair.com flyasiana.com jejuair.net skyscanner.co.kr interpark.com",
            "BUSES:inter" to "kobus.co.kr txbus.t-money.co.kr letskorail.com",
            "BUSES" to "map.naver.com map.kakao.com",
            "VACACIONES" to "visitkorea.or.kr hanatour.com tripadvisor.co.kr",
            "HOTELES" to "yanolja.com goodchoice.kr agoda.com booking.com",
            "ARRIENDOS" to "zigbang.com dabangapp.com land.naver.com",
            "VEHICULOS" to "encar.com kcar.com danawa.com",
            "CLIMA" to "weather.go.kr weather.naver.com",
            "TECNOLOGIA" to "danawa.com zdnet.co.kr bloter.net itworld.co.kr",
            "ASTRONOMIA" to "kasa.go.kr kari.re.kr timeanddate.com nasa.gov",
            "MEDICINA" to "health.kr amc.seoul.kr kdca.go.kr",
            "SERIES" to "watcha.com justwatch.com namu.wiki",
            "PELICULAS" to "watcha.com cgv.co.kr namu.wiki",
            "JUEGOS" to "inven.co.kr ruliweb.com store.steampowered.com",
            "GENERAL:TRAMITE" to "gov.kr hometax.go.kr",
            "GENERAL:SPORTS" to "sports.naver.com kleague.com koreabaseball.com",
            "GENERAL:KNOWLEDGE" to "namu.wiki terms.naver.com wikipedia.org"
        ),
        // 🇯🇵 Japón
        "JP" to sites(
            "CINE" to "eiga.com tohotheater.jp aeoncinema.com 109cinemas.net",
            "MUSICA" to "oricon.co.jp billboard-japan.com music.apple.com spotify.com",
            "EVENTOS" to "eplus.jp pia.jp l-tike.com walkerplus.com",
            "NEGOCIOS" to "nikkei.com kabutan.jp jpx.co.jp boj.or.jp finance.yahoo.co.jp",
            "VENTAS" to "kakaku.com amazon.co.jp rakuten.co.jp shopping.yahoo.co.jp",
            "EMPLEOS" to "indeed.com doda.jp rikunabi.com mynavi.jp hellowork.mhlw.go.jp",
            "VESTUARIO" to "uniqlo.com zozo.jp gu-global.com rakuten.co.jp",
            "MAQUILLAJE" to "cosme.net rakuten.co.jp amazon.co.jp",
            "TIENDAS" to "amazon.co.jp rakuten.co.jp kakaku.com yodobashi.com biccamera.com",
            "SUPERMERCADOS" to "tokubai.co.jp aeon.com itoyokado.co.jp",
            "COMIDA:receta" to "cookpad.com kurashiru.com delishkitchen.tv",
            "COMIDA" to "tabelog.com hotpepper.jp retty.me",
            "DELIVERY" to "ubereats.com demae-can.com wolt.com",
            "VIAJES" to "jalan.net travel.rakuten.co.jp jtb.co.jp",
            "VIAJES:bus" to "kosokubus.com willer.co.jp navitime.co.jp",
            "VUELOS:estado" to "tokyo-haneda.com narita-airport.jp flightradar24.com",
            "VUELOS" to "jal.co.jp ana.co.jp skyscanner.jp peach-air.jp",
            "BUSES:inter" to "kosokubus.com willer.co.jp jorudan.co.jp",
            "BUSES" to "navitime.co.jp jorudan.co.jp ekitan.com transit.yahoo.co.jp",
            "VACACIONES" to "jalan.net japan.travel tripadvisor.jp",
            "HOTELES" to "jalan.net travel.rakuten.co.jp ikyu.com booking.com agoda.com",
            "ARRIENDOS" to "suumo.jp homes.co.jp athome.co.jp",
            "VEHICULOS" to "carsensor.net goo-net.com kakaku.com",
            "CLIMA" to "tenki.jp jma.go.jp weathernews.jp",
            "TECNOLOGIA" to "kakaku.com gizmodo.jp itmedia.co.jp watch.impress.co.jp",
            "ASTRONOMIA" to "nao.ac.jp jaxa.jp timeanddate.com",
            "MEDICINA" to "msdmanuals.com mhlw.go.jp",
            "SERIES" to "filmarks.com justwatch.com",
            "PELICULAS" to "eiga.com filmarks.com justwatch.com",
            "JUEGOS" to "famitsu.com 4gamer.net nintendo.com store.steampowered.com",
            "GENERAL:TRAMITE" to "e-gov.go.jp nta.go.jp",
            "GENERAL:SPORTS" to "sports.yahoo.co.jp jleague.jp npb.jp",
            "GENERAL:KNOWLEDGE" to "wikipedia.org kotobank.jp"
        )
    ) }

    // ------------------------------------------------------------------------
    // 🧩 TEMAS QUE FALTABAN EN CADA PAÍS: así TODOS los países tienen página propia para los 31 temas.
    //    Formato: «TEMA=página página; TEMA=página…». Solo se usan si el país no tenía ese tema arriba.
    // ------------------------------------------------------------------------
    private fun parseSites(text: String): Map<String, List<String>> = text.split(';').map { it.trim() }.filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().split(Regex("\\s+")) }

    private val EXTRA_SITES: Map<String, String> by lazy { mapOf(
        "US" to "GOOGLE_PLAY=play.google.com",
        "BR" to "GOOGLE_PLAY=play.google.com",
        "RU" to "GOOGLE_PLAY=rustore.ru play.google.com; YOUTUBE=rutube.ru vk.com; YOUTUBE_MUSIC=music.yandex.ru zvuk.com",
        "CN" to "YOUTUBE=bilibili.com douyin.com; YOUTUBE_MUSIC=music.163.com y.qq.com",
        "KR" to "VIDEOS=youtube.com tv.naver.com chzzk.naver.com; GOOGLE_PLAY=play.google.com onestore.co.kr",
        "JP" to "VIDEOS=youtube.com nicovideo.jp tver.jp; GOOGLE_PLAY=play.google.com",
        "PT" to "NEGOCIOS=euronext.com bportugal.pt jornaldenegocios.pt; VESTUARIO=zalando.pt zara.com hm.com elcorteingles.pt; " +
            "MAQUILLAJE=sephora.pt douglas.pt wells.pt; VIAJES=edreams.pt booking.com; VACACIONES=visitportugal.com tripadvisor.pt; " +
            "TECNOLOGIA=pplware.sapo.pt kuantokusta.pt worten.pt; MEDICINA=sns.gov.pt infarmed.pt; MEDICINA:farmacia=farmaciasdeservico.net; " +
            "PELICULAS=cinemas.nos.pt justwatch.com imdb.com",
        "AR" to "ASTRONOMIA=timeanddate.com in-the-sky.org nasa.gov",
        "PE" to "MAQUILLAJE=falabella.com.pe ripley.com.pe oechsle.pe; TECNOLOGIA=falabella.com.pe ripley.com.pe xataka.com gsmarena.com",
        "CO" to "MAQUILLAJE=falabella.com.co exito.com; TECNOLOGIA=alkosto.com ktronix.com xataka.com gsmarena.com",
        "MX" to "TECNOLOGIA=xataka.com.mx mercadolibre.com.mx liverpool.com.mx gsmarena.com",
        "VE" to "VENTAS=mercadolibre.com.ve; TIENDAS=mercadolibre.com.ve; VESTUARIO=mercadolibre.com.ve; MAQUILLAJE=farmatodo.com.ve; " +
            "COMIDA:receta=recetasgratis.net cookpad.com; COMIDA=tripadvisor.com.ve; DELIVERY=pedidosya.com.ve; VIAJES=despegar.com; " +
            "VACACIONES=tripadvisor.com.ve; HOTELES=booking.com despegar.com; TECNOLOGIA=mercadolibre.com.ve xataka.com gsmarena.com; " +
            "MEDICINA=farmatodo.com.ve medlineplus.gov",
        "BO" to "EVENTOS=eventbrite.com passline.com; NEGOCIOS=bcb.gob.bo investing.com; VEHICULOS=ultracasas.com",
        "EC" to "VENTAS=mercadolibre.com.ec; VESTUARIO=mercadolibre.com.ec; TECNOLOGIA=mercadolibre.com.ec xataka.com gsmarena.com",
        "UY" to "VESTUARIO=mercadolibre.com.uy; TECNOLOGIA=mercadolibre.com.uy xataka.com gsmarena.com; VACACIONES=turismo.gub.uy tripadvisor.com.uy",
        "PY" to "CINE=cinepolis.com cinemark.com; BUSES=moovitapp.com; VENTAS=mercadolibre.com.py; TIENDAS=mercadolibre.com.py",
        "CR" to "NEGOCIOS=bccr.fi.cr investing.com; VENTAS=mercadolibre.co.cr; TIENDAS=mercadolibre.co.cr; BUSES=moovitapp.com; VACACIONES=visitcostarica.com tripadvisor.co.cr",
        "PA" to "NEGOCIOS=investing.com; VENTAS=mercadolibre.com.pa; TIENDAS=mercadolibre.com.pa; BUSES=moovitapp.com elmetrodepanama.com",
        "DO" to "NEGOCIOS=bancentral.gov.do investing.com; VENTAS=mercadolibre.com.do; TIENDAS=mercadolibre.com.do; BUSES=moovitapp.com",
        "GT" to "NEGOCIOS=banguat.gob.gt investing.com; VENTAS=mercadolibre.com.gt; TIENDAS=mercadolibre.com.gt; BUSES=moovitapp.com",
        "NL" to "NEGOCIOS=dnb.nl investing.com; VESTUARIO=zalando.nl hm.com; MAQUILLAJE=douglas.nl kruidvat.nl; COMIDA=thefork.nl tripadvisor.nl; " +
            "VIAJES=booking.com skyscanner.nl; VACACIONES=zoover.nl tripadvisor.nl; HOTELES=booking.com trivago.nl; MEDICINA=thuisarts.nl apotheek.nl",
        "BE" to "EVENTOS=ticketmaster.be; NEGOCIOS=nbb.be investing.com; VENTAS=bol.com coolblue.be; TIENDAS=coolblue.be bol.com; VESTUARIO=zalando.be; " +
            "MAQUILLAJE=douglas.be ici-paris-xl.be; COMIDA=thefork.be tripadvisor.be; DELIVERY=takeaway.com ubereats.com deliveroo.be; HOTELES=booking.com trivago.be; " +
            "VACACIONES=visitflanders.com tripadvisor.be; TECNOLOGIA=coolblue.be tweakers.net",
        "CH" to "EVENTOS=ticketcorner.ch; NEGOCIOS=snb.ch six-group.com; VESTUARIO=zalando.ch; MAQUILLAJE=douglas.ch; COMIDA=thefork.ch tripadvisor.ch; " +
            "DELIVERY=just-eat.ch ubereats.com; HOTELES=booking.com trivago.ch; VACACIONES=myswitzerland.com; TECNOLOGIA=digitec.ch toppreise.ch",
        "AT" to "EVENTOS=oeticket.com; NEGOCIOS=oenb.at wienerborse.at; VESTUARIO=zalando.at; MAQUILLAJE=douglas.at bipa.at; TIENDAS=mediamarkt.at willhaben.at; " +
            "COMIDA=tripadvisor.at; DELIVERY=lieferando.at foodora.at; HOTELES=booking.com trivago.at; VACACIONES=austria.info; TECNOLOGIA=geizhals.at; MEDICINA=gesundheit.gv.at",
        "IE" to "CINE=omniplex.ie; EVENTOS=ticketmaster.ie; NEGOCIOS=centralbank.ie investing.com; VENTAS=boards.ie harveynorman.ie; TIENDAS=harveynorman.ie argos.ie; " +
            "VESTUARIO=zalando.ie; MAQUILLAJE=boots.ie; COMIDA=tripadvisor.ie; DELIVERY=just-eat.ie deliveroo.ie; HOTELES=booking.com trivago.ie; " +
            "VACACIONES=discoverireland.ie; MEDICINA=hse.ie",
        "PL" to "NEGOCIOS=nbp.pl gpw.pl; VESTUARIO=zalando.pl reserved.com; MAQUILLAJE=rossmann.pl notino.pl douglas.pl; COMIDA=tripadvisor.pl; " +
            "VIAJES=booking.com skyscanner.pl; VACACIONES=polska.travel; HOTELES=booking.com trivago.pl; MEDICINA=pacjent.gov.pl",
        "SE" to "EVENTOS=ticketmaster.se; NEGOCIOS=riksbank.se; VESTUARIO=zalando.se hm.com; MAQUILLAJE=lyko.com kicks.se; TIENDAS=elgiganten.se prisjakt.nu; " +
            "COMIDA=tripadvisor.se; HOTELES=booking.com trivago.se; VACACIONES=visitsweden.com; TECNOLOGIA=prisjakt.nu; MEDICINA=1177.se",
        "NO" to "CINE=nfkino.no; EVENTOS=ticketmaster.no; NEGOCIOS=norges-bank.no; VESTUARIO=zalando.no; TIENDAS=elkjop.no finn.no; COMIDA:receta=matprat.no; " +
            "COMIDA=tripadvisor.no; DELIVERY=foodora.no wolt.com; HOTELES=booking.com trivago.no; VACACIONES=visitnorway.com; TECNOLOGIA=prisjakt.no; MEDICINA=helsenorge.no",
        "DK" to "CINE=kino.dk; EVENTOS=ticketmaster.dk; NEGOCIOS=nationalbanken.dk; VESTUARIO=zalando.dk; TIENDAS=elgiganten.dk; COMIDA:receta=valdemarsro.dk; " +
            "COMIDA=tripadvisor.dk; DELIVERY=just-eat.dk wolt.com; HOTELES=booking.com trivago.dk; VACACIONES=visitdenmark.com; TECNOLOGIA=pricerunner.dk; MEDICINA=sundhed.dk",
        "FI" to "CINE=finnkino.fi; EVENTOS=lippu.fi; NEGOCIOS=suomenpankki.fi; VESTUARIO=zalando.fi; TIENDAS=verkkokauppa.com gigantti.fi; COMIDA:receta=valio.fi; " +
            "COMIDA=tripadvisor.fi; DELIVERY=wolt.com foodora.fi; HOTELES=booking.com trivago.fi; VACACIONES=visitfinland.com; TECNOLOGIA=hintaopas.fi; MEDICINA=terveyskirjasto.fi",
        "GR" to "CINE=athinorama.gr; EVENTOS=viva.gr; NEGOCIOS=bankofgreece.gr athexgroup.gr; VESTUARIO=skroutz.gr; COMIDA=tripadvisor.com.gr efood.gr; " +
            "DELIVERY=efood.gr wolt.com; HOTELES=booking.com trivago.gr; VACACIONES=visitgreece.gr; TECNOLOGIA=skroutz.gr plaisio.gr",
        "CZ" to "CINE=cinemacity.cz; EVENTOS=ticketportal.cz; NEGOCIOS=cnb.cz; VESTUARIO=zalando.cz; MAQUILLAJE=notino.cz; COMIDA=tripadvisor.cz; DELIVERY=wolt.com foodora.cz; " +
            "VUELOS=smartwings.com prg.aero; HOTELES=booking.com trivago.cz; VACACIONES=visitczechia.cz; TECNOLOGIA=alza.cz heureka.cz",
        "RO" to "CINE=cinemacity.ro; EVENTOS=iabilet.ro; NEGOCIOS=bnr.ro bvb.ro; MAQUILLAJE=notino.ro; COMIDA=tripadvisor.ro; DELIVERY=glovoapp.com wolt.com tazz.ro; " +
            "HOTELES=booking.com trivago.ro; VACACIONES=romania.travel; TECNOLOGIA=emag.ro; GENERAL:TRAMITE=gov.ro anaf.ro",
        "HU" to "CINE=cinemacity.hu; EVENTOS=jegy.hu; NEGOCIOS=mnb.hu bet.hu; MAQUILLAJE=notino.hu; TIENDAS=mediamarkt.hu emag.hu; COMIDA=tripadvisor.co.hu; " +
            "DELIVERY=wolt.com foodora.hu; VUELOS=wizzair.com bud.hu; HOTELES=booking.com szallas.hu; VACACIONES=visithungary.com; TECNOLOGIA=arukereso.hu",
        "UA" to "CINE=multiplex.ua; EVENTOS=concert.ua karabas.com; NEGOCIOS=bank.gov.ua; MAQUILLAJE=makeup.com.ua notino.ua; COMIDA=tripadvisor.com; " +
            "DELIVERY=glovoapp.com boltfood.ua; VUELOS=skyscanner.net; BUSES=moovitapp.com; HOTELES=booking.com; TECNOLOGIA=rozetka.com.ua hotline.ua"
    ) }

    /**
     * 🌐 Lo que sirve en todos los países (si el país no tiene uno propio): videos, música, YouTube, Google Play,
     * series, películas, juegos, astronomía, medicina y viajes. China y Rusia tienen los suyos arriba.
     */
    private val GLOBAL_FILL: Map<String, List<String>> by lazy { parseSites(
        "VIDEOS=youtube.com vimeo.com tiktok.com dailymotion.com; MUSICA=spotify.com music.apple.com deezer.com youtube.com; " +
        "YOUTUBE=youtube.com; YOUTUBE_MUSIC=music.youtube.com youtube.com; GOOGLE_PLAY=play.google.com; " +
        "SERIES=justwatch.com imdb.com filmaffinity.com rottentomatoes.com; PELICULAS=justwatch.com imdb.com filmaffinity.com rottentomatoes.com; " +
        "JUEGOS=store.steampowered.com playstation.com xbox.com nintendo.com metacritic.com; ASTRONOMIA=timeanddate.com in-the-sky.org nasa.gov esa.int; " +
        "MEDICINA=who.int msdmanuals.com medlineplus.gov; VIAJES=booking.com skyscanner.net tripadvisor.com; VACACIONES=tripadvisor.com lonelyplanet.com; " +
        "HOTELES=booking.com trivago.com hotels.com airbnb.com; COMIDA:receta=cookpad.com recetasgratis.net; COMIDA=tripadvisor.com; " +
        "DELIVERY=ubereats.com wolt.com glovoapp.com pedidosya.com rappi.com; EVENTOS=eventbrite.com ticketmaster.com; " +
        "NEGOCIOS=investing.com; VENTAS=amazon.com mercadolibre.com; TIENDAS=amazon.com mercadolibre.com; VESTUARIO=zara.com hm.com; " +
        "MAQUILLAJE=sephora.com notino.com; TECNOLOGIA=gsmarena.com xataka.com; CINE=cinepolis.com cinemark.com; BUSES=moovitapp.com rome2rio.com; " +
        "VUELOS=skyscanner.net kayak.com; CLIMA=accuweather.com tiempo.com; ARRIENDOS=airbnb.com; VEHICULOS=autocosmos.com; EMPLEOS=linkedin.com indeed.com; " +
        "SUPERMERCADOS=tiendeo.com; GENERAL:TUTORIAL=wikihow.com support.google.com support.apple.com; " +
        "GENERAL:KNOWLEDGE=wikipedia.org britannica.com nationalgeographic.com; GENERAL:SPORTS=flashscore.com espn.com sofascore.com"
    ) }

    /** Clave del tema en COUNTRY_SITES («VUELOS», «BUSES», …) según la categoría. */
    private fun countryKey(cat: Category): String? = when (cat) {
        CINE -> "CINE"; VIDEOS -> "VIDEOS"; MUSICA -> "MUSICA"; EVENTOS -> "EVENTOS"; NEGOCIOS -> "NEGOCIOS"
        VENTAS -> "VENTAS"; EMPLEOS -> "EMPLEOS"; VESTUARIO -> "VESTUARIO"; MAQUILLAJE -> "MAQUILLAJE"; TIENDAS -> "TIENDAS"
        SUPERMERCADOS -> "SUPERMERCADOS"; COMIDA -> "COMIDA"; DELIVERY -> "DELIVERY"; VIAJES -> "VIAJES"; VUELOS -> "VUELOS"
        BUSES -> "BUSES"; VACACIONES -> "VACACIONES"; HOTELES -> "HOTELES"; ARRIENDOS -> "ARRIENDOS"; VEHICULOS -> "VEHICULOS"
        CLIMA -> "CLIMA"; TECNOLOGIA -> "TECNOLOGIA"; ASTRONOMIA -> "ASTRONOMIA"; MEDICINA -> "MEDICINA"; SERIES -> "SERIES"
        PELICULAS -> "PELICULAS"; JUEGOS -> "JUEGOS"; GOOGLE_PLAY -> "GOOGLE_PLAY"; GENERAL -> "GENERAL"
        YOUTUBE -> "YOUTUBE"; YOUTUBE_MUSIC -> "YOUTUBE_MUSIC"
        else -> null
    }

    /** Subtipo del pedido (para elegir «TEMA:subtipo» si el país lo tiene). */
    private fun countrySub(cat: Category, question: String): String? {
        val q = question.lowercase()
        return when (cat) {
            BUSES -> if (isInterurban(question)) "inter" else null
            VUELOS -> if (isFlightStatus(question)) "estado" else null
            COMIDA -> if (Regex("receta|cocinar|preparar|c[oó]mo (se )?hace|recipe|receita|recette|rezept|ricett|рецепт|食谱|菜谱|레시피|レシピ").containsMatchIn(q)) "receta" else null
            MEDICINA -> if (Regex("farmacia|turno|pharmacy|drugstore|pharmacie|apotheke|аптек|药店|약국|薬局").containsMatchIn(q)) "farmacia" else null
            VIAJES -> when {
                Regex("requisit|documento|\\bvisas?\\b|pasaporte|passport|vacuna|aduana").containsMatchIn(q) -> "requisitos"
                Regex("\\bbus(es)?\\b|autob[uú]s|[oó]mnibus|terrestre|\\btren(es)?\\b|ônibus|onibus|автобус|大巴|버스|バス").containsMatchIn(q) -> "bus"
                else -> null
            }
            GENERAL -> runCatching { GeneralFinder.kind(question) }.getOrNull()?.name
            else -> null
        }
    }

    /** Las páginas de ese país para ese tema (null si el país no tiene lista propia: se usa la internacional). */
    fun countrySites(cat: Category?, question: String, country: String): List<String>? {
        cat ?: return null
        val cc = country.uppercase()
        val byCountry = COUNTRY_SITES[cc] ?: return null
        val key = countryKey(cat) ?: return null
        val sub = countrySub(cat, question)
        val extra = EXTRA_SITES[cc]?.let { parseSites(it) } ?: emptyMap()
        // 1) el tema exacto del país · 2) lo que faltaba del país · 3) lo que sirve en todos los países
        fun find(k: String) = byCountry[k] ?: extra[k] ?: GLOBAL_FILL[k]
        return (if (sub != null) find("$key:$sub") else null) ?: find(key)
    }

    /** ¿La pregunta está en español? (palabras o letras propias del español). Si no, la búsqueda va en el idioma del usuario. */
    fun looksSpanish(q: String): Boolean {
        val low = q.lowercase()
        if (Regex("[\\p{IsCyrillic}\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]").containsMatchIn(low)) return false
        if (Regex("[ñ¿¡]").containsMatchIn(low)) return true
        return Regex("(?<![\\p{L}])(el|los|las|del|y|con|una|unos|qu[eé]|c[oó]mo|d[oó]nde|cu[aá]ndo|cu[aá]nto|dame|dime|quiero|necesito|busca|b[uú]scame|hoy|ma[ñn]ana|precio|precios|horario|horarios|vuelos?|micros?|cerca|barato|baratos|hoteles|pel[ií]culas?|recetas?|de la|en el|al|pasajes?|boletos?|entradas|ofertas?|tiendas?|colectivos?|liebres?|arriendo|clima)(?![\\p{L}])")
            .containsMatchIn(low)
    }

    /**
     * Páginas buenas para cada tema. [country] es el país de la SIM («CL», «AR», «MX»…): en Chile se usan las chilenas,
     * en otros países las internacionales (que funcionan en toda Latinoamérica).
     */
    fun preferredSites(cat: Category?, question: String, country: String): List<String> {
        cat ?: return emptyList()
        // 🌍 Las páginas de ese país (Latinoamérica, España, EE.UU., Brasil, Portugal, Rusia, China, Corea, Japón)
        countrySites(cat, question, country)?.let { return it }
        val cl = country.equals("CL", ignoreCase = true)
        val q = question.lowercase()
        fun pick(chile: List<String>, world: List<String>) = if (cl) chile + world.filter { it !in chile }.take(2) else world
        return when (cat) {
            BUSES -> if (isInterurban(question))
                pick(listOf("recorrido.cl", "kupos.cl", "turbus.cl", "pullman.cl", "busbud.com"), listOf("busbud.com", "rome2rio.com", "moovitapp.com"))
            else pick(listOf("moovitapp.com", "dtpr.gob.cl", "red.cl", "efe.cl", "metro.cl"), listOf("moovitapp.com", "rome2rio.com"))
            VUELOS -> if (isFlightStatus(question))
                listOf("flightaware.com", "flightradar24.com", "nuevopudahuel.cl", "latamairlines.com")
            else pick(listOf("latamairlines.com", "skyairline.com", "jetsmart.com", "despegar.cl", "skyscanner.cl", "kayak.cl"),
                listOf("skyscanner.com", "kayak.com", "despegar.com", "latamairlines.com", "jetsmart.com", "avianca.com", "copaair.com"))
            VIAJES -> when {
                Regex("requisit|documento|\\bvisas?\\b|pasaporte|vacuna|aduana").containsMatchIn(q) ->
                    pick(listOf("chileatiende.gob.cl", "minrel.gob.cl", "sag.gob.cl", "pdi.cl"), listOf("iatatravelcentre.com", "gob.mx", "argentina.gob.ar"))
                Regex("\\bbus(es)?\\b|terrestre|pasajes? de bus").containsMatchIn(q) ->
                    pick(listOf("recorrido.cl", "kupos.cl", "turbus.cl", "pullman.cl"), listOf("busbud.com", "rome2rio.com"))
                else -> pick(listOf("despegar.cl", "cocha.com", "latamairlines.com", "tripadvisor.cl", "chile.travel"),
                    listOf("despegar.com", "tripadvisor.com", "skyscanner.com", "booking.com"))
            }
            SUPERMERCADOS -> pick(listOf("lider.cl", "jumbo.cl", "santaisabel.cl", "unimarc.cl", "tottus.cl", "acuenta.cl", "knasta.cl"),
                listOf("mercadolibre.com", "walmart.com", "carrefour.com"))
            TIENDAS -> pick(listOf("falabella.com", "paris.cl", "ripley.cl", "mercadolibre.cl", "sodimac.cl", "easy.cl", "knasta.cl"),
                listOf("mercadolibre.com", "amazon.com", "falabella.com"))
            VENTAS -> pick(listOf("knasta.cl", "solotodo.cl", "mercadolibre.cl", "falabella.com", "paris.cl", "ripley.cl", "cybermonday.cl"),
                listOf("mercadolibre.com", "amazon.com"))
            VESTUARIO -> pick(listOf("falabella.com", "paris.cl", "ripley.cl", "dafiti.cl", "hm.com", "zara.com"), listOf("hm.com", "zara.com", "mercadolibre.com"))
            MAQUILLAJE -> pick(listOf("preunic.cl", "maicao.cl", "dbs.cl", "falabella.com", "paris.cl", "sephora.cl"), listOf("sephora.com", "mercadolibre.com"))
            HOTELES -> pick(listOf("booking.com", "trivago.cl", "despegar.cl", "tripadvisor.cl", "airbnb.cl", "hotels.com"),
                listOf("booking.com", "trivago.com", "hotels.com", "expedia.com", "tripadvisor.com", "airbnb.com"))
            ARRIENDOS -> pick(listOf("portalinmobiliario.com", "toctoc.com", "yapo.cl", "chilepropiedades.cl", "goplaceit.com", "airbnb.cl"),
                listOf("zonaprop.com.ar", "inmuebles24.com", "fincaraiz.com.co", "urbania.pe", "idealista.com", "airbnb.com"))
            VEHICULOS -> pick(listOf("chileautos.cl", "yapo.cl", "mercadolibre.cl", "kavak.com", "autofact.cl"), listOf("mercadolibre.com", "kavak.com", "autocosmos.com"))
            EMPLEOS -> pick(listOf("laborum.cl", "cl.computrabajo.com", "trabajando.cl", "bne.cl", "chiletrabajos.cl", "empleospublicos.cl", "linkedin.com"),
                listOf("computrabajo.com", "linkedin.com", "indeed.com", "bumeran.com"))
            COMIDA -> if (Regex("receta|cocinar|preparar|c[oó]mo (se )?hace").containsMatchIn(q))
                listOf("recetasgratis.net", "cookpad.com", "directoalpaladar.com", "recetasnestle.cl")
            else pick(listOf("tripadvisor.cl", "pedidosya.cl", "ubereats.com", "rappi.cl"), listOf("tripadvisor.com", "pedidosya.com", "ubereats.com", "rappi.com"))
            DELIVERY -> pick(listOf("pedidosya.cl", "ubereats.com", "rappi.cl"), listOf("pedidosya.com", "ubereats.com", "rappi.com", "didi-food.com"))
            CINE -> pick(listOf("cinemark.cl", "cineplanet.cl", "cinepolischile.cl", "cinehoyts.cl"), listOf("cinepolis.com", "cinemark.com", "cineplanet.com.pe"))
            EVENTOS -> pick(listOf("puntoticket.com", "ticketmaster.cl", "passline.com", "eventbrite.cl", "ticketplus.cl"),
                listOf("ticketmaster.com", "eventbrite.com", "passline.com"))
            CLIMA -> pick(listOf("meteochile.gob.cl", "tiempo.com", "accuweather.com", "windy.com"), listOf("accuweather.com", "tiempo.com", "weather.com"))
            MEDICINA -> if (Regex("farmacia|turno|urgencia|cesfam|consultorio|cl[ií]nica|hospital").containsMatchIn(q))
                pick(listOf("minsal.cl", "farmanet.minsal.cl", "cruzverde.cl", "salcobrand.cl", "farmaciasahumada.cl"), listOf("who.int", "medlineplus.gov"))
            else listOf("medlineplus.gov", "mayoclinic.org", "who.int", "minsal.cl", "cun.es")
            VACACIONES -> pick(listOf("chile.travel", "tripadvisor.cl", "conaf.cl", "sernatur.cl", "despegar.cl"), listOf("tripadvisor.com", "lonelyplanet.com", "despegar.com"))
            GOOGLE_PLAY -> listOf("play.google.com")
            JUEGOS -> listOf("store.steampowered.com", "playstation.com", "xbox.com", "nintendo.com", "metacritic.com", "eneba.com")
            SERIES, PELICULAS -> listOf("justwatch.com", "imdb.com", "filmaffinity.com", "rottentomatoes.com")
            TECNOLOGIA -> when {
                // 📱 Equipos: precios y fichas técnicas
                techDevice.containsMatchIn(q) -> pick(listOf("solotodo.cl", "knasta.cl", "gsmarena.com", "xataka.com", "mercadolibre.cl"),
                    listOf("gsmarena.com", "xataka.com", "mercadolibre.com", "versus.com"))
                // 🤖 Inteligencia artificial
                Regex("inteligencia artificial|\\bia\\b|chat ?gpt|openai|gemini|copilot|\\bllm|modelos? de (lenguaje|ia)|machine learning|aprendizaje autom|redes neuronales|deep learning").containsMatchIn(q) ->
                    listOf("xataka.com", "technologyreview.com", "wwwhatsnew.com", "hipertextual.com", "openai.com", "deepmind.google", "huggingface.co", "wired.com")
                // 🦾 Robótica, drones e impresión 3D
                Regex("rob[oó]t|androide|humanoide|drone|impres(ora|i[oó]n) 3d").containsMatchIn(q) ->
                    listOf("xataka.com", "spectrum.ieee.org", "technologyreview.com", "bostondynamics.com", "wired.com", "hipertextual.com")
                // 📼 Tecnología antigua e historia de los inventos
                Regex("historia|antigu|retro|viej|invent|origen|evoluci|walkman|disquete|vhs|casete|transistor").containsMatchIn(q) ->
                    listOf("wikipedia.org", "computerhistory.org", "britannica.com", "xataka.com", "muyinteresante.com", "nationalgeographic.com")
                // 🔐 Ciberseguridad, chips, internet, software y lo demás
                else -> listOf("xataka.com", "hipertextual.com", "genbeta.com", "wired.com", "theverge.com", "technologyreview.com", "gsmarena.com")
            }
            ASTRONOMIA -> pick(listOf("timeanddate.com", "in-the-sky.org", "nasa.gov", "esa.int", "eso.org", "almaobservatory.org"),
                listOf("timeanddate.com", "in-the-sky.org", "nasa.gov", "esa.int"))
            VIDEOS -> listOf("youtube.com", "vimeo.com", "dailymotion.com", "tiktok.com")
            YOUTUBE -> listOf("youtube.com")
            YOUTUBE_MUSIC -> listOf("music.youtube.com", "youtube.com")
            MUSICA -> listOf("spotify.com", "music.apple.com", "deezer.com", "billboard.com", "last.fm", "youtube.com")
            NEGOCIOS -> pick(listOf("bcentral.cl", "bolsadesantiago.com", "cmfchile.cl", "sii.cl", "df.cl", "investing.com"),
                listOf("investing.com", "bloomberglinea.com", "finance.yahoo.com", "expansion.com"))
            GENERAL -> when (runCatching { GeneralFinder.kind(question) }.getOrNull()) {
                // 🏛️ Trámites: las páginas oficiales del gobierno
                GeneralFinder.Kind.TRAMITE -> pick(listOf("chileatiende.gob.cl", "gob.cl", "registrocivil.cl", "sii.cl", "ips.gob.cl", "fonasa.cl",
                    "superintendenciadepensiones.cl"), listOf("gob.mx", "argentina.gob.ar", "gob.pe", "gov.co"))
                // 🛠️ Cómo hacer algo: guías paso a paso
                GeneralFinder.Kind.TUTORIAL -> listOf("wikihow.com", "support.google.com", "support.apple.com", "support.microsoft.com", "youtube.com")
                // ⚽ Deportes: resultados, tablas y fixture
                GeneralFinder.Kind.SPORTS -> pick(listOf("espn.cl", "flashscore.cl", "anfp.cl", "promiedos.com.ar", "sofascore.com"),
                    listOf("espn.com", "flashscore.com", "sofascore.com", "fifa.com"))
                // 📖 Saber de un tema (qué es, quién fue, cómo funciona): enciclopedias y sitios de divulgación
                GeneralFinder.Kind.KNOWLEDGE -> listOf("wikipedia.org", "britannica.com", "nationalgeographic.com", "muyinteresante.com", "nasa.gov", "who.int")
                else -> emptyList()   // lo demás busca en toda la web
            }
            else -> emptyList()
        }
    }

    /**
     * 😄 Páginas de chistes, payas, adivinanzas y trabalenguas (nunca noticias ni páginas de otros temas).
     * Según lo que pidas: payas → folclore chileno; adivinanzas y trabalenguas → páginas infantiles; chistes → páginas de chistes.
     */
    fun humorSites(question: String, country: String = ""): List<String> {
        val q = question.lowercase()
        return when (humorLanguage(question, country)) {
            // 🇺🇸🇬🇧🇮🇪 inglés
            "en" -> when {
                Regex("riddles?|brain ?teasers?").containsMatchIn(q) -> listOf("riddles.com", "rd.com", "funology.com", "brainzilla.com")
                Regex("tongue ?twisters?").containsMatchIn(q) -> listOf("rd.com", "funology.com")
                else -> listOf("rd.com", "laughfactory.com", "funology.com", "jokes4us.com", "boredpanda.com")
            }
            // 🇧🇷🇵🇹 portugués: piadas, adivinhas, trava-línguas
            "pt" -> if (Regex("adivinha|charada|trava").containsMatchIn(q)) listOf("osvigaristas.com.br", "smartkids.com.br")
                else listOf("osvigaristas.com.br", "piadas.com.br", "smartkids.com.br")
            // 🇫🇷 francés: blagues, devinettes
            "fr" -> listOf("humour.com", "blague.info", "teteamodeler.com")
            // 🇩🇪🇦🇹🇨🇭 alemán: Witze, Rätsel
            "de" -> if (Regex("r[äa]tsel").containsMatchIn(q)) listOf("raetselstunde.de", "aberwitzig.com")
                else listOf("aberwitzig.com", "schlechtewitze.com", "witze.net")
            // 🇮🇹 italiano: barzellette, indovinelli
            "it" -> listOf("barzellette.net", "focusjunior.it")
            // 🇳🇱🇧🇪 neerlandés: moppen
            "nl" -> listOf("moppenbank.nl")
            // 🇵🇱 polaco: dowcipy, kawały
            "pl" -> listOf("jeja.pl")
            // 🇷🇺 ruso: анекдоты, загадки
            "ru" -> listOf("anekdot.ru", "anekdotov.net")
            // 🇨🇳 chino: 笑话, 谜语
            "zh" -> listOf("xiaohua.zol.com.cn", "jokeji.cn", "zhihu.com")
            // 🇰🇷 coreano: 유머, 농담, 수수께끼
            "ko" -> listOf("humoruniv.com", "dogdrip.net")
            // 🇯🇵 japonés: ダジャレ, なぞなぞ, ジョーク
            "ja" -> listOf("dajare.jp")
            // 🇨🇱🇦🇷🇲🇽🇵🇪🇨🇴🇪🇸… español: chistes, payas, adivinanzas, trabalenguas
            else -> when {
                Regex("payas?|payador|contrapunto|décima|decima").containsMatchIn(q) ->
                    if (country.equals("CL", true) || country.isBlank()) listOf("memoriachilena.gob.cl", "guiainfantil.com", "chistes.com", "folkloreinfantil.cl")
                    else listOf("guiainfantil.com", "chistes.com", "memoriachilena.gob.cl")
                Regex("adivinanzas?|acertijos?").containsMatchIn(q) ->
                    listOf("guiainfantil.com", "mundoprimaria.com", "pequeocio.com", "educapeques.com", "chistes.com")
                Regex("trabalenguas").containsMatchIn(q) ->
                    listOf("guiainfantil.com", "mundoprimaria.com", "pequeocio.com", "educapeques.com")
                else -> listOf("chistes.com", "chistescortos.eu", "chistalia.es", "guiainfantil.com", "mundoprimaria.com", "pequeocio.com")
            }
        }
    }

    // ------------------------------------------------------------------------
    // 💻 INGENIERO DE CÓDIGO: páginas de programación según el lenguaje (Kotlin, Python, JavaScript…)
    //    y según el idioma/país del usuario (Stack Overflow en español, Habr, Qiita, CSDN, Developpez…)
    // ------------------------------------------------------------------------
    /** Documentación oficial de cada tecnología que nombre la pregunta. */
    private val CODE_DOCS: List<Pair<Regex, List<String>>> by lazy { listOf(
        Regex("(?iu)\\bkotlin\\b|코틀린|コトリン|jetpack|compose|\\bandroid\\b|gradle|\\bapk\\b|\\baab\\b") to listOf("developer.android.com", "kotlinlang.org"),
        Regex("(?iu)\\bpython\\b|파이썬|パイソン|django|flask|pandas|numpy|fastapi") to listOf("docs.python.org", "realpython.com", "pypi.org"),
        Regex("(?iu)javascript|자바스크립트|ジャバスクリプト|\\bjs\\b|typescript|\\bts\\b|\\bnode(\\.?js)?\\b|\\breact\\b|\\bvue\\b|angular|next\\.?js|\\bnpm\\b") to listOf("developer.mozilla.org", "nodejs.org", "react.dev", "typescriptlang.org", "npmjs.com"),
        Regex("(?iu)\\bhtml\\b|\\bcss\\b|tailwind|bootstrap") to listOf("developer.mozilla.org", "web.dev", "tailwindcss.com", "getbootstrap.com"),
        Regex("(?iu)\\bjava\\b|spring|maven") to listOf("docs.oracle.com", "baeldung.com", "spring.io"),
        Regex("(?iu)c#|\\.net\\b|dotnet|asp\\.net|\\bunity\\b") to listOf("learn.microsoft.com", "docs.unity3d.com"),
        Regex("(?iu)\\bswift\\b|swiftui|\\bios\\b|xcode") to listOf("developer.apple.com", "swift.org"),
        Regex("(?iu)\\bphp\\b|laravel|wordpress") to listOf("php.net", "laravel.com", "developer.wordpress.org"),
        Regex("(?iu)\\brust\\b|cargo") to listOf("doc.rust-lang.org", "docs.rs"),
        Regex("(?iu)\\bgolang\\b|\\bgo (lang|programming)") to listOf("go.dev", "pkg.go.dev"),
        Regex("(?iu)\\bc\\+\\+|\\bcpp\\b|\\bc lenguaje|\\bcmake\\b|\\bjni\\b|\\bndk\\b") to listOf("cppreference.com", "cplusplus.com", "developer.android.com"),
        Regex("(?iu)\\bsql\\b|mysql|postgres|sqlite|mongodb") to listOf("dev.mysql.com", "postgresql.org", "sqlite.org", "mongodb.com"),
        Regex("(?iu)flutter|\\bdart\\b") to listOf("docs.flutter.dev", "dart.dev", "pub.dev"),
        Regex("(?iu)\\bgit\\b|github") to listOf("git-scm.com", "docs.github.com"),
        Regex("(?iu)docker|kubernetes|\\blinux\\b|\\bbash\\b") to listOf("docs.docker.com", "kubernetes.io")
    ) }

    /** Comunidades y tutoriales de programación en el idioma del usuario. */
    private val CODE_COMMUNITY: Map<String, List<String>> = mapOf(
        "es" to listOf("es.stackoverflow.com", "stackoverflow.com", "github.com", "freecodecamp.org", "desarrolloweb.com", "geeksforgeeks.org"),
        "en" to listOf("stackoverflow.com", "github.com", "geeksforgeeks.org", "dev.to", "freecodecamp.org", "baeldung.com"),
        "pt" to listOf("pt.stackoverflow.com", "stackoverflow.com", "github.com", "devmedia.com.br", "alura.com.br", "tabnews.com.br"),
        "fr" to listOf("developpez.com", "openclassrooms.com", "zestedesavoir.com", "stackoverflow.com", "github.com"),
        "de" to listOf("stackoverflow.com", "github.com", "wiki.selfhtml.org", "heise.de"),
        "it" to listOf("html.it", "stackoverflow.com", "github.com"),
        "nl" to listOf("stackoverflow.com", "github.com", "tweakers.net"),
        "pl" to listOf("4programmers.net", "stackoverflow.com", "github.com"),
        "ru" to listOf("ru.stackoverflow.com", "habr.com", "metanit.com", "stackoverflow.com", "github.com"),
        "zh" to listOf("csdn.net", "juejin.cn", "segmentfault.com", "cnblogs.com", "runoob.com", "gitee.com"),
        "ko" to listOf("velog.io", "okky.kr", "stackoverflow.com", "github.com"),
        "ja" to listOf("ja.stackoverflow.com", "qiita.com", "zenn.dev", "stackoverflow.com", "github.com")
    )

    /**
     * Páginas para buscar código: primero la documentación oficial de lo que nombraste (Kotlin → developer.android.com…),
     * después la comunidad de programación de tu idioma y país (Stack Overflow en español, Habr, Qiita, CSDN…).
     */
    fun codeSites(question: String, country: String): List<String> {
        val docs = CODE_DOCS.filter { it.first.containsMatchIn(question) }.flatMap { it.second }
        // Idioma: el de la pregunta; «como usar…» en Brasil es portugués, pero «¿cómo…?», «qué», «ñ» son español
        val spanishStrong = Regex("(?iu)[ñ¿¡]|(?<![\\p{L}])(qu[eé]|c[oó]mo se|d[oó]nde|cu[aá]l|cu[aá]ndo|del|los|las|el|hacer|usar en)(?![\\p{L}])").containsMatchIn(question)
        val byLang = humorLanguage(question, country)
        val lang = when {
            byLang in setOf("ru", "zh", "ko", "ja") -> byLang
            spanishStrong -> "es"
            else -> byLang
        }
        val community = CODE_COMMUNITY[lang] ?: CODE_COMMUNITY.getValue("en")
        return (docs.take(4) + community).distinct().take(10)
    }

    /** Idioma de los chistes: el de la pregunta (por sus palabras o su escritura) o, si no se nota, el del país. */
    fun humorLanguage(question: String, country: String = ""): String {
        val q = question.lowercase()
        return when {
            Regex("[\\p{IsHiragana}\\p{IsKatakana}]").containsMatchIn(q) -> "ja"
            Regex("\\p{IsHangul}").containsMatchIn(q) -> "ko"
            Regex("\\p{IsHan}").containsMatchIn(q) -> "zh"
            Regex("\\p{IsCyrillic}").containsMatchIn(q) -> "ru"
            Regex("chistes?|payas?|adivinanzas?|trabalenguas|el colmo|qu[eé] le dijo").containsMatchIn(q) -> "es"
            Regex("\\bjokes?\\b|riddles?|tongue ?twisters?|knock ?knock|\\bpuns?\\b").containsMatchIn(q) -> "en"
            Regex("piadas?|adivinhas?|trava-?l[íi]nguas?|charadas?").containsMatchIn(q) -> "pt"
            Regex("blagues?|devinettes?|vannes?").containsMatchIn(q) -> "fr"
            Regex("\\bwitze?\\b|r[äa]tsel|zungenbrecher").containsMatchIn(q) -> "de"
            Regex("barzellett|indovinell|scioglilingua").containsMatchIn(q) -> "it"
            Regex("\\bmoppen?\\b|raadsels?").containsMatchIn(q) -> "nl"
            Regex("dowcip|kawa[łl]|zagadk").containsMatchIn(q) -> "pl"
            else -> when (country.uppercase()) {
                "US", "GB", "IE", "CA", "AU", "NZ" -> "en"; "BR", "PT" -> "pt"; "FR" -> "fr"; "DE", "AT", "CH" -> "de"
                "IT" -> "it"; "NL" -> "nl"; "PL" -> "pl"; "RU" -> "ru"; "CN" -> "zh"; "KR" -> "ko"; "JP" -> "ja"
                else -> "es"
            }
        }
    }

    /**
     * 😄 ¿Pide un chiste, una adivinanza o un trabalenguas en otro idioma? (inglés, portugués, francés, alemán, italiano,
     * neerlandés, polaco, ruso, chino, coreano o japonés). El español lo revisa la app aparte.
     */
    private val humorAnyLanguage = Regex("(?iu)\\bjokes?\\b|riddles?|tongue ?twisters?|knock ?knock|\\bpuns?\\b|piadas?|adivinhas?|trava-?l[íi]nguas?|" +
        "blagues?|devinettes?|\\bwitze?\\b|r[äa]tsel|zungenbrecher|barzellett|indovinell|scioglilingua|\\bmoppen?\\b|dowcip|kawa[łl]|" +
        "анекдот|шутк|загадк|скороговорк|笑话|段子|谜语|绕口令|농담|유머|수수께끼|ジョーク|ダジャレ|なぞなぞ|笑い話|冗談")

    fun asksHumorAnyLanguage(q: String): Boolean = humorAnyLanguage.containsMatchIn(q)

    /** «mexicanos», «argentinos»…: en un país de habla hispana (fuera de Chile) se buscan los chistes de ese país si no dijo cuál. */
    fun humorDemonym(country: String): String? = mapOf(
        "AR" to "argentinos", "MX" to "mexicanos", "PE" to "peruanos", "CO" to "colombianos", "VE" to "venezolanos", "BO" to "bolivianos",
        "EC" to "ecuatorianos", "UY" to "uruguayos", "PY" to "paraguayos", "ES" to "españoles", "CR" to "costarricenses", "PA" to "panameños",
        "DO" to "dominicanos", "GT" to "guatemaltecos", "SV" to "salvadoreños", "HN" to "hondureños", "NI" to "nicaragüenses", "CU" to "cubanos",
        "PR" to "puertorriqueños"
    )[country.uppercase()]

    /** «a joke from the internet», «piada da internet», «анекдот из интернета», «网上的笑话», «인터넷 유머», «ネットのジョーク». */
    fun wantsHumorFromInternet(q: String): Boolean = asksHumorAnyLanguage(q) &&
        (netMarker.containsMatchIn(q) || Regex("(?iu)\\binternet\\w*|из интернета|в интернете|网上|网络|인터넷|ネット|インターネット|\\bonline\\b").containsMatchIn(q))

    /** 📱 Equipos (para buscar precio y ficha técnica): celular, notebook, tablet, TV, audífonos, consola… */
    private val techDevice = Regex("(?iu)celular|tel[eé]fono|smartphone|iphone|samsung|galaxy|xiaomi|\\bpixel\\b|notebook|laptop|computador|\\bpc\\b|" +
        "tablet|\\bipad\\b|tarjeta de video|\\bgpu\\b|smartwatch|reloj inteligente|aud[ií]fono|televisor|smart ?tv|macbook|motorola|huawei|oppo|" +
        "honor|realme|precio|ficha t[eé]cnica|especificaciones")

    // ------------------------------------------------------------------------
    // 🚌 MICROS URBANOS vs BUSES INTERURBANOS
    // ------------------------------------------------------------------------
    /** «bus interurbano», «pasaje», «terminal», «larga distancia», «bus rural»: buses a otra ciudad (no micros). */
    fun isInterurban(q: String): Boolean =
        Regex("(?iu)intercity|greyhound|flixbus|megabus|rodovi[áa]ria|\\btren(es)? (a|al|hacia|para)\\b|autob[uú]s (a|al|hacia|para)\\b|" +
            "\\btrains? (to|for|pour|nach)\\b|\\bz[üu]ge? nach\\b|\\btreni? per\\b|\\bsncf\\b|trenitalia|national express|" +
            "\\b(train|trains|zug|z[üu]ge|treno|treni)\\b.*\\b(to|nach|per|pour)\\b|" +
            "междугородн|长途汽车|고속버스|시외버스|高速バス|夜行バス").containsMatchIn(q) ||
        Regex("(?iu)interurban|\\bpasajes?\\b|\\bterminal\\b|larga distancia|\\brural(es)?\\b|\\bbuses? cama\\b|semi ?cama|\\bpullman\\b|turbus|tur bus|\\bjac\\b|\\bcondor\\b|eme bus|via ?lactea")
            .containsMatchIn(q) && !Regex("(?iu)\\burban[oa]s?\\b|\\bmicros?\\b|\\bliebres?\\b|colectivos?").containsMatchIn(q)

    // ------------------------------------------------------------------------
    // 🔍 REVISIÓN DE LA RESPUESTA CONTRA LO QUE TRAJO INTERNET: tarifas y precios que NO están en los resultados
    //    («Tarifa: $20» cuando el pasaje vale $580) y paraderos o avenidas inventadas se cambian por «Sin dato».
    // ------------------------------------------------------------------------
    private val moneyToken = Regex("(?iu)(?:US\\$|USD\\s?|CLP\\s?|\\$|€|S/\\.?\\s?|R\\$)\\s?\\d[\\d.,]*|\\d[\\d.,]*\\s?(?:pesos|d[oó]lares|dollars|euros|soles|reales|usd|clp|ars|mxn|cop|pen|eur)\\b")
    private val priceLabel = Regex("(?iu)tarifa|precio|pasaje|valor|costo|coste|cuesta|vale|cobran|price|fare|cost")
    private val stopLabel = Regex("(?iu)^(\\W*(?:[-•*]|\\d+[.)])?\\s*\\**\\s*(?:paraderos?(?: principales)?|paradas?(?: principales)?|recorrido por|pasa por|calles?|avenidas?)\\s*\\**\\s*:\\s*)(.+)$")
    private val templateEcho = Regex("(?iu)primera salida y [uú]ltima salida \\(d[ií]as h[aá]biles y fin de semana\\)")

    private val timeToken = Regex("(?<![\\d:])([01]?\\d|2[0-3])[.h:]([0-5]\\d)(?![\\d:])")
    private fun timeKey(t: String): String = timeToken.find(t)?.let { "${it.groupValues[1].toInt()}:${it.groupValues[2]}" } ?: t

    private fun digitsOf(s: String) = s.filter { it.isDigit() }.trimStart('0')

    private fun plain(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Quita de la respuesta los precios y paraderos que no aparecen en los resultados de internet. */
    fun verifyAgainstSource(answer: String, source: String, cat: Category?): String {
        if (answer.isBlank() || source.isBlank()) return answer
        val srcNums = Regex("\\d[\\d.,]*").findAll(source).map { digitsOf(it.value) }.filter { it.isNotEmpty() }.toSet()
        val srcPlain = plain(source)
        val srcTimes = timeToken.findAll(source).map { timeKey(it.value) }.toSet()
        val stopWords = setOf("avenida", "calle", "paradero", "pasaje", "esquina", "entre", "con", "frente", "plaza", "centro", "sector", "puente")
        var changed = false
        val out = answer.lines().map { line ->
            var l = line
            // 💲 Tarifas y precios: cada monto tiene que estar en los resultados
            if (priceLabel.containsMatchIn(l)) {
                l = moneyToken.replace(l) { m ->
                    val d = digitsOf(m.value)
                    if (d.isEmpty() || d in srcNums) m.value else { changed = true; "Sin dato (revisa el enlace)" }
                }
            }
            // 🕒 La IA copió el formato en vez de poner las horas
            if (templateEcho.containsMatchIn(l)) { changed = true; l = templateEcho.replace(l, "Sin dato (revisa el enlace)") }
            // 🅰️ Nombres de relleno inventados («Aerolínea A», «Hotel X», «Línea B»): esa línea se quita
            if (placeholderName.find(l)?.let { !srcPlain.contains(plain(it.value)) } == true) { changed = true; return@map null }
            // 🕒 Micros, buses y vuelos: las horas tienen que salir en los resultados («Horas: 5:00 - 17:00» inventadas → Sin dato)
            if (cat == BUSES || cat == VUELOS) {
                l = timeToken.replace(l) { m ->
                    if (timeKey(m.value) in srcTimes) m.value else { changed = true; "Sin dato" }
                }
                if (l.contains("Sin dato") && Regex("(?iu)Sin dato\\s*(?:-|–|a|hasta)\\s*Sin dato").containsMatchIn(l))
                    l = Regex("(?iu)Sin dato\\s*(?:-|–|a|hasta)\\s*Sin dato(?:\\s*\\([^)]*\\))?").replace(l, "Sin dato (revisa el enlace)")
            }
            // 🚏 Paraderos y avenidas: solo los que salen en los resultados
            if (cat == BUSES) stopLabel.find(l)?.let { m ->
                val items = m.groupValues[2].split(Regex("\\s*[,;]\\s*|\\s+y\\s+")).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
                val kept = items.filter { item ->
                    val words = plain(item).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stopWords }
                    words.isEmpty() || words.any { srcPlain.contains(it) }
                }
                if (kept.size < items.size) {
                    changed = true
                    l = m.groupValues[1] + (if (kept.isEmpty()) "Sin dato (revisa el enlace)" else kept.joinToString(", "))
                }
            }
            l
        }.filterNotNull()
        return if (changed) out.joinToString("\n") else answer
    }

    /** «Aerolínea A», «Hotel X», «Línea B», «Empresa 1»: relleno que inventa un modelo pequeño cuando no tiene datos. */
    private val placeholderName = Regex("(?u)\\b(?:[Aa]erol[ií]nea|[Hh]otel|[Ll][ií]nea|[Ee]mpresa|[Tt]ienda|[Ss]upermercado|[Rr]estaurante|[Pp]roducto|[Vv]uelo|[Bb]us|[Mm]icro)\\s+(?:[A-Z]|[XYZ]{1,3}|\\d)(?![\\p{L}\\p{N}])")

    // ------------------------------------------------------------------------
    // 🔁 LA IA SE QUEDÓ PEGADA REPITIENDO («sin dato — sin dato — sin dato…» hasta 2048 tokens y 2 minutos)
    // ------------------------------------------------------------------------
    /** ¿El final del texto es lo mismo repetido muchas veces? (para cortar al modelo mientras escribe) */
    fun isLooping(text: String): Boolean {
        val tail = text.takeLast(700).replace(Regex("\\s+"), " ")
        if (tail.length < 240) return false
        for (len in 3..90) {
            if (tail.length < len * 6) break
            val unit = tail.takeLast(len)
            // Solo texto con letras («sin dato — sin dato…»): «0, 0, 0, 0…» o «────» de un código o una tabla no es un error
            if (unit.isBlank() || unit.none { it.isLetter() }) continue
            var count = 0
            var end = tail.length
            while (end - len >= 0 && tail.regionMatches(end - len, unit, 0, len)) { count++; end -= len }
            if (count >= 6 && count * len >= 200) return true
        }
        return false
    }

    /** Deja una sola vez lo que se repitió seguido 4 o más veces («sin dato — sin dato — sin dato…» → «sin dato»). */
    fun collapseRepeats(text: String): String {
        if (text.length < 60) return text
        val rx = Regex("([^\\n]{4,90}?)\\1{3,}")
        val out = rx.replace(text) { m ->
            val unit = m.groupValues[1]
            // Solo frases (con espacio): «jajajaja» o «────» se dejan como están
            if (unit.contains(' ') && unit.any { it.isLetter() }) unit.trimEnd(' ', '—', '-', '–', ',') else m.value
        }
        return if (out == text) text else out.replace(Regex("\\n{3,}"), "\n\n")
    }

    /** ¿La respuesta es casi solo «sin dato»? (entonces conviene mostrar lo que trajo internet) */
    fun mostlyNoData(text: String): Boolean {
        val n = Regex("(?iu)sin dato").findAll(text).count()
        if (n < 4) return false
        return !hasRealContent(text.replace(Regex("(?iu)sin dato(?:\\s*\\(revisa el enlace\\))?"), " ").replace(Regex("[—–\\-•|]+"), " "))
    }

    // ------------------------------------------------------------------------
    // 🧹 LA IA COPIÓ LAS INSTRUCCIONES: «1. Aerolínea — fecha — salida → llegada…», «Primero la respuesta directa…»,
    //    «Tu prioridad es entregar estos datos…». Esas líneas se quitan (son el formato, no datos).
    // ------------------------------------------------------------------------
    private fun echoKey(s: String) = plain(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Quita de la respuesta las líneas que son copia de las instrucciones (y la pregunta repetida al comienzo). */
    fun stripPromptEcho(answer: String, instructions: String, question: String = ""): String {
        if (answer.isBlank() || instructions.isBlank()) return answer
        val frags = instructions.split(Regex("\\n|(?<=[.;:!?])\\s+")).map { echoKey(it) }.filter { it.length >= 18 }.distinct()
        if (frags.isEmpty()) return answer
        val q = echoKey(question)
        var first = true
        val kept = answer.lines().mapNotNull { raw ->
            var line = raw
            // «Primero la respuesta directa: …» → «…»
            line = line.replace(Regex("(?iu)^(\\s*)(?:primero\\s+)?(?:la\\s+)?respuesta directa\\s*:\\s*"), "$1")
            val k = echoKey(line)
            val isFirst = first && k.isNotEmpty()
            if (k.isNotEmpty()) first = false
            when {
                k.isEmpty() -> line
                // La pregunta repetida como primera línea («Vuelos horarios y pasajes.»)
                isFirst && q.length >= 6 && (k == q || (k.length <= q.length + 3 && q.startsWith(k))) -> null
                k.length >= 18 && frags.any { f -> f.contains(k) || (f.length >= 24 && k.contains(f)) } -> null
                else -> line
            }
        }
        // Sin líneas vacías repetidas
        val out = kept.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (out == answer.trim()) answer else out
    }

    /** ¿Quedó algo de verdad después de quitar la copia de las instrucciones? (no solo «Detalles importantes:») */
    fun hasRealContent(text: String): Boolean =
        text.lines().map { echoKey(it) }.filter { it.isNotEmpty() && !Regex("^(detalles importantes|detalles|resumen|datos|informacion)$").matches(it) }
            .joinToString(" ").length >= 20

    // ------------------------------------------------------------------------
    // 🌍 «BUSCA EN INTERNET» EN CUALQUIER IDIOMA Y DE CUALQUIER FORMA:
    //    «búscame esto por internet», «quiero algo por internet», «¿sabéis qué música hay por internet?»,
    //    «search the web for…», «look up…», «pesquisa na internet…», «cherche sur internet…»,
    //    «cerca su internet…», «such im Internet…». Devuelve SOLO lo que hay que buscar.
    // ------------------------------------------------------------------------

    /** «por internet», «en la web», «online», «na internet», «sur internet», «su internet», «im Internet»… */
    private val netMarker = Regex("(?iu)(?:^|[\\s,¿¡])(?:" + listOf(
        // español
        "(?:por|en|desde|a trav[eé]s de|mediante|usando)\\s+(?:(?:el\\s+|un\\s+)?(?:internet|google|web|navegador|buscador)|la\\s+(?:web|red))",
        "en l[ií]nea",
        // inglés
        "(?:on|in|from|over|across|via|using)\\s+(?:the\\s+)?(?:internet|web|net|google)", "the (?:internet|web)", "online", "on line",
        // portugués
        "(?:na|pela|pelo|via)\\s+(?:internet|web|rede)", "no google",
        // francés
        "(?:sur|dans|depuis|via)\\s+(?:l['’]\\s*)?(?:internet|web|google|le web|la toile)", "en ligne",
        // italiano
        "(?:su|in|tramite)\\s+(?:l['’]\\s*)?(?:internet|web|google|rete)",
        // alemán
        "(?:im|aus dem|über das|ueber das|bei|mit)\\s+(?:internet|netz|web|google)"
    ).joinToString("|") + ")$NB")

    /** «online», «en línea»: se dejan en lo que se busca («juegos online» sigue siendo «juegos online»). */
    private val onlineWord = Regex("(?iu)^(?:online|on line|en l[ií]nea|en ligne)$")

    /** «¿qué música HAY por internet?», «what is there on the web», «o que tem na internet», «qu'y a-t-il sur internet». */
    private val thereIs = Regex("(?iu)(?:^|\\s)(?:hay|existen|existe|sale|salen|there (?:is|are)|(?:is|are) there|tem|h[aá]|existe|y a|il y a|c['’]è|ci sono|gibt es|es gibt|gibt)(?:$NB|$)")

    /** Pedidos para crear o traducir: «escribe un poema sobre la vida en internet» no es una búsqueda. */
    private val notASearch = Regex("(?iu)^(?:escr[ií]be(?:me)?|redacta(?:me)?|inventa(?:me)?|crea(?:me)?|haz(?:me)?|genera(?:me)?|traduce(?:me)?|tradu[cz]|" +
        "write|translate|create|make|draw|dibuja(?:me)?|escreve|traduz|cr[ié]e|[ée]cris|tradui[st]|scrivi|traduci|crea|schreib|übersetze)$NB")

    /** Verbos de buscar que bastan solos (aunque no digan «internet»): «búscame…», «search…», «pesquisa…», «recherche…». */
    private val strongSearchVerb = Regex("(?iu)^(?:" + listOf(
        "b[uú]sca(?:me|nos|r|rme|le|la|lo)?", "b[uú]sques", "b[uú]squen(?:me)?", "b[uú]squeme", "investiga(?:me|r)?", "averigua(?:me|r)?",
        "googlea(?:me|r)?", "gu[gq]lea(?:me|r)?", "consulta(?:me)? en (?:internet|google|la web)", "rastrea(?:me)?",
        "search(?: for| up)?", "look(?:ing)? (?:up|for)", "google(?: it)?", "browse(?: for)?", "research",
        "pesquis[ae](?:r)?", "pesquise(?:-me)?", "busque(?:-me)?", "procure na internet",
        "recherche(?:z|r)?", "cherche(?:z|r)?(?: moi)?",
        "ricerca", "cercami", "cerca(?:re)? (?:su|in) (?:internet|google|rete)",
        "suche(?: mal)?(?: nach)?", "such (?:mal|nach)", "recherchiere", "googel(?:e|n)?"
    ).joinToString("|") + ")$NB[\\s,:]*")

    /** Verbos o pedidos que solo cuentan si además dice «internet / la web / online»: «encuéntrame… en internet», «find… online». */
    private val weakSearchLead = Regex("(?iu)^(?:" + listOf(
        "encu[eé]ntra(?:me|nos)?", "mira(?:me)?", "revisa(?:me)?", "fíjate", "fijate", "chequea(?:me)?", "ve",
        "find(?: me)?", "check", "get me", "show me", "tell me", "give me", "i want", "i need", "can you find",
        "procura(?:r)?", "encontre(?:-me)?", "mostra(?:-me)?", "me (?:mostra|diga|d[aá])", "quero", "preciso",
        "trouve(?:z)?(?: moi)?", "montre(?:z)?(?: moi)?", "je veux", "dis moi",
        "cerca", "trova(?:mi)?", "mostrami", "dimmi", "voglio",
        "finde", "zeig(?:e)?(?: mir)?", "ich will", "ich möchte", "sag mir",
        "sab[eé]is", "saben", "sabes", "sab[ée]s", "do you know", "você sabe", "vous savez", "sai", "weißt du", "weisst du"
    ).joinToString("|") + ")$NB[\\s,:]*")

    /** «eso», «esto», «it», «that», «isso», «ça»…: la búsqueda se refiere a lo que se habló antes. */
    private val pronounOnly = Regex("(?iu)^(?:eso|esto|esa|ese|aquello|lo|la|lo anterior|lo mismo|that|this|it|isso|isto|ça|cela|ceci|questo|quello|das|es|dies)$")

    /** ¿Es «eso / esto / it…»? Entonces hay que buscar el tema anterior. */
    fun isPronounSearch(query: String): Boolean = pronounOnly.matches(query.trim().trimEnd('?', '.', '!', ' '))

    /**
     * ¿El usuario pide buscar en internet? Devuelve lo que hay que buscar (o null si no lo pide).
     * Entiende español, inglés, portugués, francés, italiano y alemán, y cualquier orden de las palabras.
     */
    fun internetSearch(text: String): String? {
        val raw = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (raw.length < 4) return null
        val hasNet = netMarker.containsMatchIn(raw)
        // Sin la marca «por internet» («online» / «en línea» se quedan): quita «oye, por favor, quiero que me…»
        //    y mira si empieza con «busca / search / pesquisa…»
        var t = raw.replace(netMarker) { m ->
            val w = m.value.trim().trimStart('¿', '¡', ',')
            if (onlineWord.matches(w)) m.value else " "
        }.replace(Regex("\\s{2,}"), " ").trim().trimStart('¿', '¡', ',', '.', ' ')
        if (notASearch.containsMatchIn(t)) return null
        t = t.replace(Regex("(?iu)^(?:oye|oiga|hey|ey|hola|hi|hello|ol[aá]|salut|ciao|hallo|please|por favor|porfa(?:vor)?|s'il te pla[iî]t|per favore|bitte|ya|bueno|ok|okay)$NB[\\s,.:;!]*"), "")
        t = t.replace(Regex("(?iu)^(?:(?:yo )?(?:quiero|necesito|quisiera) que (?:me )?|(?:me )?(?:puedes|podr[ií]as|pod[eé]s)\\s+|(?:can|could|would) you (?:please )?|" +
            "(?:você )?(?:pode|podes|poderia)\\s+|(?:tu )?(?:peux|pouvez|pourrais)[- ](?:tu|vous)?\\s*|(?:puoi|potresti)\\s+|(?:kannst|könntest) du\\s+)"), "")
        val strong = strongSearchVerb.find(t)
        val weak = if (strong == null && hasNet) weakSearchLead.find(t) else null
        // «seguridad en internet», «historia de internet»: hablan de internet, no piden buscar.
        //    Sí es búsqueda si pide algo («dame / quiero / tell me… por internet») o pregunta qué HAY ahí.
        val asksSomething = strong != null || weak != null ||
            (hasNet && (thereIs.containsMatchIn(t) || runCatching { stripRequestWords(t) != t }.getOrDefault(false)))
        if (!asksSomething) return null
        if (strong != null) {
            val verb = strong.value.trim()
            t = t.substring(strong.range.last + 1)
            // «búscalo», «búscala por internet», «google it»: se busca el tema anterior
            if (t.isBlank() && Regex("(?iu)(?:lo|la|le|los|las|it)$").containsMatchIn(verb)) return "eso"
        } else if (weak != null) t = t.substring(weak.range.last + 1)
        // «búscame en internet SOBRE…», «search for INFORMATION ABOUT…»
        t = t.trim().replace(Regex("(?iu)^(?:sobre|acerca de|de|del|informaci[oó]n (?:de|sobre)|info (?:de|sobre)|algo (?:de|sobre)|" +
            "about|for|on|information (?:about|on)|info (?:about|on)|sobre|acerca|sur|à propos de|des informations sur|su|riguardo|über|ueber|nach)$NB\\s*"), "")
        t = runCatching { stripRequestWords(t) }.getOrDefault(t)
        // «búscame ESTO: precio del cobre», «este contenido: recetas», «the following: …» → solo lo que viene después
        t = t.trim().replace(Regex("(?iu)^(?:esto|eso|este (?:contenido|tema|dato|link)|esta (?:informaci[oó]n|cosa|p[aá]gina)|lo siguiente|" +
            "this|the following|isto|isso|ceci|cela|questo|das hier|folgendes)$NB\\s*[,\\-–:]+\\s*(?=\\S)"), "")
        t = t.trim().trimEnd('?', '.', '!', ',', ' ')
        // «quiero algo por internet» sin decir qué → igual se busca lo que dijo (sin las palabras vacías)
        val core = t.replace(Regex("(?iu)^(?:algo|cosas|something|anything|alguma coisa|algo|quelque chose|qualcosa|etwas)$NB\\s*"), "").trim()
        if (core.length < 2) return null
        // «tengo internet?», «hay internet?», «no tengo internet»: es sobre la conexión, no una búsqueda
        if (strong == null && Regex("(?iu)^(?:no )?(?:tengo|hay|tienes|funciona|anda|have|is there|tem|há|j'ai|ho|habe)$NB").containsMatchIn(core) &&
            core.split(Regex("\\s+")).size <= 2) return null
        return core
    }

    // ------------------------------------------------------------------------
    // 🙅 RECLAMOS Y RECHAZOS: «no me lo entregaste», «no está», «no me diste», «no me lo pasaste», «no lo veo»
    //    (= no llegó lo que pidió: se vuelve a buscar) · «no po», «no», «no quiero eso», «no lo necesito»,
    //    «no lo quiero», «eso no» (= no quiere eso). Si después dice lo que sí quiere, se busca eso.
    // ------------------------------------------------------------------------
    enum class FeedbackKind { MISSING, REJECT }

    class Feedback(val kind: FeedbackKind, val rest: String)

    /** Fin de la frase o una pausa («no.», «no,», «no está!»): así «no hay micros?» o «no sé qué hacer» no se toman como reclamo. */
    private const val PAUSE = "(?:\\s*[,.:;!¡?]+\\s*|\\s*$)"
    private const val ANY = "[\\s,.:;!¡¿?]*"

    /** Siempre son reclamo, siga lo que siga («no me diste el horario»). */
    private val missingStrong = Regex("(?iu)^(?:" + listOf(
        "no me (?:lo |la |los |las )?(?:entregaste|diste|pasaste|mostraste|mandaste|enviaste|trajiste|buscaste)(?: nada)?",
        "no (?:me )?(?:lo |la |los |las )?veo", "no veo nada", "no hay nada", "no me sali[oó] nada", "no era eso", "no es eso",
        "te equivocaste", "eso no es lo que ped[ií]"
    ).joinToString("|") + ")$NB$ANY")

    /** Reclamo solo si termina ahí o sigue con «en la pantalla / ahí / nada» («no está», «no aparece en la pantalla»). */
    private val missingWeak = Regex("(?iu)^(?:no (?:est[aá]|est[aá]n|aparece|aparecen|sale|salen|sali[oó]|salieron|lleg[oó]|llegaron|lo encuentro|encuentro nada))$NB" +
        "(?:$PAUSE|\\s+(?=(?:en (?:la )?pantalla|ah[ií]|ac[aá]|aqu[ií]|nada)$NB))")

    /** Siempre son rechazo («no po, dame…», «eso no»). */
    private val rejectStrong = Regex("(?iu)^(?:no po|no pues|no gracias|no quiero eso|no necesito eso|eso no|nada de eso)$NB$ANY")

    /** Rechazo solo si termina ahí o hay una pausa («no.», «no lo quiero,»): «no quiero ir a trabajar» no es un rechazo. */
    private val rejectWeak = Regex("(?iu)^(?:no lo quiero|no la quiero|no los quiero|no lo necesito|no la necesito|no me sirve|no sirve|no quiero|no)$NB$PAUSE")

    /** ¿El mensaje es un reclamo o un rechazo? (null si es un pedido normal). */
    fun userFeedback(text: String): Feedback? {
        var t = text.trim().trimStart('¿', '¡', ',', '.', ' ')
        // «oye», «hola», «pero», «ya»… antes del reclamo
        t = t.replace(Regex("(?iu)^(?:oye|oiga|hey|ey|pero|ya|mira|oh|ah|uy)$NB[\\s,.:;!¡]*"), "")
        var kind: FeedbackKind? = null
        for (i in 0 until 4) {
            val m = missingStrong.find(t) ?: missingWeak.find(t)
            if (m != null && m.value.isNotBlank()) { kind = FeedbackKind.MISSING; t = t.substring(m.range.last + 1).trim(); continue }
            val r = rejectStrong.find(t) ?: rejectWeak.find(t)
            if (r != null && r.value.isNotBlank()) { if (kind == null) kind = FeedbackKind.REJECT; t = t.substring(r.range.last + 1).trim(); continue }
            break
        }
        kind ?: return null
        // Lo que viene después: «no, eso no, quiero vuelos a Cusco» → «vuelos a Cusco»
        val rest = t.replace(Regex("(?iu)^(?:y|pero|sino|mejor|m[aá]s bien|ahora|entonces)$NB[\\s,]*"), "")
            .let { stripRequestWords(it) }.trim().trimEnd('.', '!', '?')
        val meaningful = rest.split(Regex("\\s+")).count { it.length >= 3 } >= 2 || (rest.length >= 4 && detect(rest) != GENERAL)
        // «no lo veo en la pantalla», «no está el horario»: sigue siendo un reclamo por lo anterior
        val aboutPrevious = Regex("(?iu)^(?:en (?:la )?pantalla|ac[aá]|aqu[ií]|nada|la (?:foto|informaci[oó]n|respuesta|lista)|el (?:horario|precio|link|enlace|dato)|los (?:datos|precios|horarios)|lo que te ped[ií])")
            .containsMatchIn(rest)
        return Feedback(kind, if (meaningful && !aboutPrevious) rest else "")
    }

    // ------------------------------------------------------------------------
    // 📖 PREGUNTAS DE CONOCIMIENTO: «explícame qué son los UAPs», «háblame de la inteligencia artificial»,
    //    «qué es la fotosíntesis», «quién fue Gabriela Mistral», «cómo funciona un motor»… Siempre se buscan en internet
    //    (antes «explícame…» se tomaba como «sigue con lo anterior» y no se buscaba nada).
    // ------------------------------------------------------------------------
    private val knowledgeAsk = Regex("(?iu)^(?:(?:oye|y|ahora|bueno|ya|hola|a ver|entonces)[,\\s]+)*(?:" +
        "(?:expl[ií]ca(?:me|nos)?|h[aá]bla(?:me|nos)|cu[eé]nta(?:me|nos)|dime|ens[eé][ñn]a(?:me|nos)|qu[eé] sabes|sabes algo|" +
        "informaci[oó]n|info|investiga|averigua|resume(?:me)?|quiero saber|necesito saber|me puedes (?:explicar|contar|decir))" +
        "\\s+(?:de|del|sobre|acerca de|qu[eé]|qui[eé]n(?:es)?|c[oó]mo|cu[aá]ndo|d[oó]nde|por qu[eé]|para qu[eé]|el|la|los|las|un|una)\\s+" +
        "|(?:qu[eé] (?:es|son|era|eran|fue|fueron|significa|significan|quiere decir)|qui[eé]n(?:es)? (?:es|son|fue|fueron|era|invent[oó]|cre[oó]|descubri[oó])|" +
        "c[oó]mo (?:funciona|funcionan|se (?:hace|forma|form[oó]|cre[oó])|naci[oó]|surgi[oó])|para qu[eé] sirve|por qu[eé]|cu[aá]ndo (?:fue|naci[oó]|muri[oó]|se (?:cre[oó]|invent[oó]|descubri[oó]))|" +
        "historia de|origen de|significado de)\\s+)(.+)$")

    /** «eso», «lo anterior», «tu respuesta», «el código»…: entonces sí es seguir con lo anterior. */
    private val aboutPreviousTopic = Regex("(?iu)^(?:eso|esto|aquello|lo anterior|lo mismo|m[aá]s|mejor|otra vez|de nuevo|tu respuesta|" +
        "el c[oó]digo|la app|la aplicaci[oó]n|la funci[oó]n|el archivo|la l[oó]gica|ese|esa|esos|esas|este|esta|lo que dijiste|" +
        "c[oó]digo|app|aplicaci[oó]n|funci[oó]n|archivo|l[oó]gica|respuesta|error|paso|pasos)(?:$NB|$)")

    /** ¿Pregunta por un tema para saber de él? (se busca en internet aunque empiece con «explícame» o «cuéntame»). */
    fun asksKnowledge(q: String): Boolean = knowledgeTopic(q) != null

    /** El tema de la pregunta: «explícame qué son los UAPs» → «UAPs»; «háblame de la inteligencia artificial» → «inteligencia artificial». */
    fun knowledgeTopic(q: String): String? {
        val m = knowledgeAsk.find(q.trim().trimStart('¿', '¡')) ?: return null
        var topic = m.groupValues[1].trim().trimEnd('?', '.', '!', ' ')
        // «qué sabes de LOS UAPs», «explícame qué SON los…»: se quitan las palabras que sobran al comienzo
        repeat(3) {
            topic = topic.replace(Regex("(?iu)^(?:qu[eé] (?:es|son|significa|significan)|qui[eé]n(?:es)? (?:es|son|fue)|de|del|sobre|acerca de|" +
                "es|son|era|eran|fue|fueron|significa|significan|" +
                "el|la|los|las|un|una|unos|unas)$NB\\s*"), "").trim()
        }
        if (topic.length < 2 || aboutPreviousTopic.containsMatchIn(topic)) return null
        return topic
    }

    // ------------------------------------------------------------------------
    // 📋 VOLVER A LO ANTERIOR: «dame la respuesta anterior», «dame la conversación anterior», «dame lo que conversamos
    //    anteriormente», «dime qué hablamos más arriba», «dime qué dije más arriba», «dime qué conversamos más arriba».
    //    Lo entrega la APP tal cual está en el chat (el modelo no lo reescribe ni lo inventa).
    // ------------------------------------------------------------------------
    enum class RecallKind { LAST_ANSWER, CONVERSATION, MY_MESSAGES }

    private const val RC_LEAD = "(?:(?:oye|y|ahora|a ver|bueno|ya|hola|por favor|porfa)\\s+)*"
    private const val RC_WHEN = "(?:\\s+(?:anterior|anteriormente|de antes|antes|pasada|m[aá]s arriba|arriba|hace rato|hace un rato|reci[eé]n|la [uú]ltima vez|que me diste|en este chat))*"
    private const val RC_TAIL = "(?:\\s+(?:por favor|porfa|po|otra vez|de nuevo|completa|completo))*"
    private const val RC_ASK = "(?:d[aá]me|dime|mu[eé]strame|p[aá]same|rep[ií]te(?:me)?|recu[eé]rdame|vuelve a (?:darme|mostrarme|decirme)|" +
        "cu[aá]l (?:fue|era)|me (?:das|dices|muestras|pasas|repites|recuerdas)|puedes (?:darme|decirme|mostrarme|repetirme|recordarme)|resume(?:me)?)"

    private val recallLastAnswer = Regex("(?iu)^$RC_LEAD(?:$RC_ASK\\s+)?(?:la |tu |esa )?(?:[uú]ltima )?respuesta$RC_WHEN$RC_TAIL$|" +
        "^$RC_LEAD(?:$RC_ASK\\s+)(?:lo|eso) (?:[uú]ltimo |anterior |que me (?:dijiste|diste|respondiste|escribiste))$RC_WHEN$RC_TAIL$|" +
        "^$RC_LEAD(?:rep[ií]te(?:me)?|rep[ií]telo|vuelve a dec[ií]rmelo) (?:lo [uú]ltimo|lo anterior|la respuesta)?$RC_WHEN$RC_TAIL$")

    private val recallConversation = Regex("(?iu)^$RC_LEAD(?:$RC_ASK\\s+)?(?:la |nuestra )?conversaci[oó]n$RC_WHEN$RC_TAIL$|" +
        "^$RC_LEAD(?:$RC_ASK\\s+)?(?:lo que|qu[eé]|de qu[eé]|sobre qu[eé]) (?:conversamos|hablamos|estuvimos hablando|est[aá]bamos hablando|platicamos)$RC_WHEN$RC_TAIL$")

    private val recallMine = Regex("(?iu)^$RC_LEAD(?:$RC_ASK\\s+)?(?:lo que|qu[eé]) (?:te )?(?:dije|escrib[ií]|pregunt[eé]|ped[ií])$RC_WHEN$RC_TAIL$|" +
        "^$RC_LEAD(?:$RC_ASK\\s+)?(?:mi |mis )(?:[uú]ltim[oa]s? )?(?:pregunta|preguntas|mensaje|mensajes)$RC_WHEN$RC_TAIL$")

    /** ¿Pide ver algo anterior del chat? (null si es un pedido normal). */
    fun recallRequest(text: String): RecallKind? {
        val q = text.trim().trimStart('¿', '¡').replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.length > 80) return null
        return when {
            recallMine.containsMatchIn(q) -> RecallKind.MY_MESSAGES
            recallConversation.containsMatchIn(q) -> RecallKind.CONVERSATION
            recallLastAnswer.containsMatchIn(q) -> RecallKind.LAST_ANSWER
            else -> null
        }
    }

    /** «cerca», «aquí», «dónde queda», «sucursal», «mi comuna»…: lo que pide depende de dónde estás. */
    private val nearWords = Regex("(?iu)\\bcerca\\b|cercan[oa]s?|\\baqu[ií]\\b|\\bac[aá]\\b|donde estoy|por (aqu[ií]|ac[aá]|mi casa)|" +
        "mi (ciudad|comuna|zona|sector|barrio|regi[oó]n|pueblo)|d[oó]nde (queda|quedan|hay|est[aá]n?|encuentro|compro|puedo)|" +
        "\\bdirecci[oó]n(es)?\\b|sucursal(es)?|oficinas?|abiert[oa]s?|horarios? de atenci[oó]n|\\bm[aá]s cercan")

    /** Preguntas por precio: el precio cambia según el país (se agrega el tuyo). */
    private val priceWords = Regex("(?iu)precios?|cu[aá]nto (cuesta|vale|sale)|valor|comprar|d[oó]nde (lo |la )?(compro|venden)|barat|ofertas?|en chile|disponib")

    /** ✈️ «estado del vuelo LA800», «a qué hora llega el vuelo»: no es comprar pasajes. */
    private val flightStatus = Regex("(?iu)estado (de(l)? )?(mi )?vuelo|vuelo [A-Za-z]{2}\\s?\\d{2,4}\\b|\\b(LA|H2|JA|AR|AV|CM|IB|AA|UA|DL|AM)\\s?\\d{2,4}\\b|" +
        "(llega|lleg[oó]|aterriz|despeg|sale|sali[oó]) (el|mi) vuelo|vuelo (atrasado|retrasado|cancelado|demorado)|llegadas|salidas de vuelos|puerta de embarque")
    fun isFlightStatus(q: String): Boolean = flightStatus.containsMatchIn(q) ||
        Regex("(?iu)flight status|flight [A-Za-z]{2}\\s?\\d{2,4}|arrivals|departures|status do voo|chegadas|статус рейса|航班动态|航班状态|항공편 현황|도착 정보|運航状況|フライト状況|発着").containsMatchIn(q)

    /** El lugar que nombró al final de la pregunta aunque lo escriba en minúsculas: «horario del transporte de chiguayante» → «Chiguayante». */
    fun namedPlace(q: String): String? {
        val text = q.trim().replace(Regex("[?!.¿¡]+\\s*$"), "").trim()
        // Se prueba cada «de/en/para…» de izquierda a derecha: el primero cuyo resto sea un lugar (y llegue hasta el final)
        for (m in Regex("(?iu)\\b(?:en|de|del|para|cerca de|desde)\\s+").findAll(text)) {
            val rest = text.substring(m.range.last + 1).replace(Regex("(?iu)^(la|el|los|las)\\s+"), "").trim()
            val words = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > 5) continue
            if (words.first().length < 3 || notAPlace.matches(words.first()) || notAPlace.matches(rest)) continue
            if (!words.all { Regex("^\\p{L}+$").matches(it) }) continue
            if (words.any { notAPlace.matches(it) }) continue
            return words.joinToString(" ") { w ->
                if (w.lowercase() in setOf("de", "del", "la", "las", "los", "el", "y")) w.lowercase() else w.replaceFirstChar { it.titlecase() } }
        }
        return null
    }

    /** Pedidos donde lo que va después de «de/en» suele ser un lugar («micros de chiguayante», «cine en talcahuano»). */
    private val PLACE_CATS: Set<Category> by lazy { setOf(BUSES, CINE, HOTELES, VACACIONES, COMIDA, DELIVERY, TIENDAS, EVENTOS, ARRIENDOS, CLIMA) }

    private fun mentionsPlace(q: String, place: String, lowercasePlaces: Boolean = false): Boolean {
        val low = q.lowercase()
        if (place.split(',').map { it.trim().lowercase() }.any { it.length >= 3 && low.contains(it) }) return true
        if (lowercasePlaces && namedPlace(q) != null) return true
        return Regex("\\b(en|de|para|cerca de)\\s+([Ee]l |[Ll]a |[Ll]os |[Ll]as )?\\p{Lu}\\p{L}{2,}").containsMatchIn(q)
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
    fun contextBlock(cat: Category, dateTime: String, place: String, language: String, currency: String, season: String = "",
                     question: String = ""): String {
        val lugar = place.ifBlank { "el país del usuario" }
        val sb = StringBuilder()
        sb.append("[CONTEXTO EN TIEMPO REAL]\n")
        // 🕒 Día, fecha, hora y año de ahora: la información se entrega para este momento
        sb.append("- Ahora (cuando el usuario lee tu respuesta): ").append(dateTime).append('\n')
        sb.append("- Ubicación del usuario: ").append(lugar).append('\n')
        if (currency.isNotBlank() && cat.num in PRICE_CATS) sb.append("- Moneda local: ").append(currency).append('\n')
        // 🌍 Responde en el idioma en que escribió el usuario (inglés → inglés, portugués → portugués…)
        sb.append("- Idioma de respuesta: el mismo idioma del mensaje del usuario (si no se nota, ").append(language).append(")\n")
        // 🌦️ Vacaciones y viajes: se recomienda según la temporada en que está el usuario
        if (season.isNotBlank()) sb.append("- Temporada actual: ").append(season).append(". Recomienda destinos y actividades para esta temporada.\n")
        sb.append("Si pide algo local o actualizado (eventos, precios, ofertas), prioriza lo de ").append(lugar)
            .append(". Si la pregunta nombra otra ciudad o comuna, usa ESA y no la ubicación del usuario.\n\n")
        sb.append("TIPO DE PEDIDO: ").append(cat.title).append('\n')
        sb.append("Qué entregar:\n").append(cat.rules.replace("{LUGAR}", lugar)).append("\n\n")
        sb.append("CÓMO ENTREGARLO (usa este formato, una línea por opción, con TODAS las opciones que traigan los resultados):\n")
        // 🔎 Consulta general: el formato según lo que pide (deporte, trámite, tutorial, «qué es / quién fue»)
        val format = if (cat == GENERAL && question.isNotBlank()) GeneralFinder.format(GeneralFinder.kind(question)) else FORMATS[cat.num].orEmpty()
        sb.append(format.replace("{LUGAR}", lugar)).append("\n\n")
        sb.append("REGLAS:\n")
        sb.append("- Responde solo en ").append(language).append(": primero la respuesta directa y luego la lista detallada.\n")
        if (cat.media != null)
            sb.append("- La portada/afiche de cada título la pone la app (se ve arriba de tu respuesta) y el enlace de YouTube, debajo: no escribas enlaces ni imágenes.\n")
        sb.append("- «Hoy», «mañana» y «esta semana» se cuentan desde la fecha y hora de arriba; no presentes como vigente algo que ya terminó.\n")
        sb.append("- Lo primero es entregar los datos de los resultados: con resultados, no pidas disculpas ni digas que no puedes buscar.\n")
        sb.append("- Usa SOLO los resultados de internet: fechas, cifras, precios y nombres tal cual aparecen. " +
            "Si un dato no aparece, escribe «sin dato» (no lo inventes). Nunca inventes enlaces.")
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

    // ------------------------------------------------------------------------
    // ▶️ TEMAS CON VIDEOS EN YOUTUBE: tecnología, astronomía, medicina y reino animal.
    // La respuesta sale igual que siempre y al final va el enlace para ver videos de eso en YouTube.
    // ------------------------------------------------------------------------
    private val ANIMALS = Regex("(?iu)(?<![\\p{L}])(reino animal|animal(es|ito)?|mam[ií]fer\\p{L}*|reptil\\p{L}*|anfibi\\p{L}*|insect\\p{L}*|aves|p[aá]jaro|peces|ballena|" +
        "delf[ií]n|tibur[oó]n|le[oó]n|leones|tigre|elefante|jirafa|osos?|lobos?|zorros?|perros?|gatos?|caballos?|serpiente|cocodrilo|" +
        "ara[ñn]a|abejas?|hormiga|ping[uü]ino|[aá]guila|c[oó]ndor|puma|huemul|fauna|dinosaurio|primate|gorila|mono|koala|canguro|" +
        "pulpo|tortuga|rana|murci[eé]lago|b[uú]ho|loro|especies? (animal|en peligro))(s|es)?(?![\\p{L}])")

    /** ¿Pregunta por animales? («cuánto vive un elefante», «el reino animal», «datos de los pulpos») */
    fun asksAnimals(question: String): Boolean = ANIMALS.containsMatchIn(question)

    /** (texto, enlace) de YouTube para tecnología, astronomía, medicina o reino animal; null en lo demás. */
    fun youtubeTopicLink(cat: Category?, question: String, country: String = "CL"): Pair<String, String>? {
        val what = when {
            cat == TECNOLOGIA -> "de tecnología"
            cat == ASTRONOMIA -> "de astronomía"
            cat == MEDICINA -> "de salud"
            asksAnimals(question) -> "del reino animal"
            else -> return null
        }
        val q = stripRequestWords(question.trim().replace(Regex("[¿?¡!]"), " ").replace(Regex("\\s+"), " ")).take(100).trim()
        if (q.length < 2) return null
        val lang = runCatching { replyLanguage(question, country) }.getOrDefault("es")
        return (if (lang == "es") "▶️ Ver videos $what sobre esto en YouTube" else "▶️ YouTube") to
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
    }

    // ------------------------------------------------------------------------
    // 🔗 SOLO ENLACES: en los temas de páginas (cine, buses, vuelos, delivery, maquillaje, vacaciones…)
    // la app entrega solo los enlaces a las páginas, sin horarios, precios ni datos (así nunca son inventados).
    // No aplica a: noticias, clima, YouTube (videos, música, películas, series), chistes, código,
    // ni a temas de conocimiento (tecnología, astronomía, medicina, consulta general).
    // ------------------------------------------------------------------------
    private val LINKS_ONLY: Map<Category, String> by lazy { linkedMapOf(
        CINE to "Aquí tienes las páginas del cine",
        EVENTOS to "Aquí tienes las páginas de eventos y conciertos",
        NEGOCIOS to "Aquí tienes las páginas de negocios y finanzas",
        VENTAS to "Aquí tienes las páginas de ventas",
        EMPLEOS to "Aquí tienes las páginas de búsqueda de empleos",
        VESTUARIO to "Aquí tienes las páginas de vestuario",
        MAQUILLAJE to "Aquí tienes las páginas de maquillaje",
        TIENDAS to "Aquí tienes las páginas de tiendas",
        SUPERMERCADOS to "Aquí tienes las páginas de supermercados",
        COMIDA to "Aquí tienes las páginas de comida",
        DELIVERY to "Aquí tienes las páginas de delivery",
        VIAJES to "Aquí tienes las páginas de viajes",
        VUELOS to "Aquí tienes las páginas de vuelos y líneas aéreas",
        BUSES to "Aquí tienes las páginas de la locomoción colectiva y buses",
        VACACIONES to "Aquí tienes las páginas de lugares de vacaciones",
        HOTELES to "Aquí tienes las páginas de hoteles",
        ARRIENDOS to "Aquí tienes las páginas de arriendos de casas y departamentos",
        VEHICULOS to "Aquí tienes las páginas de compra y venta de vehículos",
        JUEGOS to "Aquí tienes las páginas de juegos",
        GOOGLE_PLAY to "Aquí tienes Google Play"
    ) }

    /** «Aquí tienes las páginas» en el idioma del usuario (el emoji ya dice el tema: 🎟️ cine, 🚌 buses, ✈️ vuelos…) */
    private val LINKS_TITLE = mapOf(
        "en" to "Here are the websites", "pt" to "Aqui estão os sites", "fr" to "Voici les sites", "de" to "Hier sind die Websites",
        "it" to "Ecco i siti", "nl" to "Hier zijn de websites", "pl" to "Oto strony", "ru" to "Вот сайты",
        "zh" to "以下是相关网站", "ko" to "관련 사이트입니다", "ja" to "関連サイトはこちらです"
    )

    /** Países de Latinoamérica (ahí sirven Mercado Libre, PedidosYa, Rappi, Cinépolis, Cinemark…) */
    private val LATAM = setOf("AR", "BO", "BR", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA", "PE", "PR", "PY", "SV", "UY", "VE")
    private val LATAM_ONLY = listOf("mercadolibre", "pedidosya", "rappi", "cinepolis", "cinemark", "despegar", "latamairlines", "jetsmart")
    private val SPANISH_COUNTRIES = LATAM - "BR" + setOf("ES", "GQ")
    private val LATAM_TLDS = setOf("cl", "ar", "pe", "co", "mx", "br", "uy", "py", "bo", "ec", "ve", "gt", "cr", "pa", "do", "hn", "sv", "ni", "cu")

    /**
     * 🌏 Páginas de cada país que completan los temas donde faltaban (se agregan DESPUÉS de las que ya tenía el país).
     * Canadá, Australia, Nueva Zelanda, India, Irlanda, Turquía, Sudáfrica, países nórdicos, Europa central y del este,
     * y Centroamérica / Caribe / Ecuador.
     */
    private val MORE_COUNTRIES: Map<String, Map<String, List<String>>> by lazy { mapOf(
        "CA" to "CINE=cineplex.com landmarkcinemas.com; BUSES=transitapp.com moovitapp.com; DELIVERY=skipthedishes.com doordash.com ubereats.com; " +
            "EVENTOS=ticketmaster.ca; NEGOCIOS=tmx.com; VENTAS=amazon.ca bestbuy.ca walmart.ca; TIENDAS=walmart.ca canadiantire.ca; " +
            "EMPLEOS=indeed.ca jobbank.gc.ca; MAQUILLAJE=shoppersdrugmart.ca sephora.com; SUPERMERCADOS=loblaws.ca sobeys.com metro.ca walmart.ca; " +
            "VUELOS=aircanada.com westjet.com flighthub.com; VIAJES=expedia.ca; HOTELES=expedia.ca booking.com; ARRIENDOS=rentals.ca kijiji.ca; " +
            "VEHICULOS=autotrader.ca kijiji.ca; VACACIONES=pc.gc.ca",
        "AU" to "CINE=hoyts.com.au eventcinemas.com.au villagecinemas.com.au; BUSES=transitapp.com moovitapp.com; " +
            "DELIVERY=ubereats.com doordash.com menulog.com.au; EVENTOS=ticketek.com.au ticketmaster.com.au; NEGOCIOS=asx.com.au; " +
            "VENTAS=amazon.com.au jbhifi.com.au ebay.com.au; TIENDAS=kmart.com.au bigw.com.au; EMPLEOS=seek.com.au indeed.com.au; " +
            "VESTUARIO=myer.com.au davidjones.com theiconic.com.au; MAQUILLAJE=mecca.com priceline.com.au sephora.com.au; " +
            "SUPERMERCADOS=woolworths.com.au coles.com.au aldi.com.au; VUELOS=qantas.com virginaustralia.com jetstar.com webjet.com.au; " +
            "HOTELES=wotif.com booking.com; ARRIENDOS=realestate.com.au domain.com.au; VEHICULOS=carsales.com.au gumtree.com.au; VACACIONES=australia.com",
        "NZ" to "CINE=eventcinemas.co.nz hoyts.co.nz; BUSES=transitapp.com moovitapp.com; DELIVERY=ubereats.com doordash.com; " +
            "EVENTOS=ticketmaster.co.nz; VENTAS=trademe.co.nz; EMPLEOS=seek.co.nz trademe.co.nz; MAQUILLAJE=mecca.com farmers.co.nz; " +
            "SUPERMERCADOS=woolworths.co.nz paknsave.co.nz newworld.co.nz; VUELOS=airnewzealand.co.nz jetstar.com; " +
            "ARRIENDOS=trademe.co.nz; VEHICULOS=trademe.co.nz; VACACIONES=newzealand.com",
        "IN" to "CINE=in.bookmyshow.com pvrcinemas.com; BUSES=moovitapp.com redbus.in; DELIVERY=zomato.com swiggy.com; " +
            "EVENTOS=in.bookmyshow.com; NEGOCIOS=nseindia.com bseindia.com moneycontrol.com; VENTAS=amazon.in flipkart.com; TIENDAS=amazon.in flipkart.com; " +
            "EMPLEOS=naukri.com indeed.co.in; VESTUARIO=myntra.com ajio.com; MAQUILLAJE=nykaa.com purplle.com; " +
            "SUPERMERCADOS=bigbasket.com blinkit.com; COMIDA=zomato.com; VUELOS=makemytrip.com goindigo.in airindia.com cleartrip.com; " +
            "VIAJES=makemytrip.com; HOTELES=makemytrip.com goibibo.com; ARRIENDOS=99acres.com magicbricks.com nobroker.in; " +
            "VEHICULOS=cardekho.com cars24.com carwale.com; VACACIONES=makemytrip.com",
        "IE" to "BUSES=transportforireland.ie buseireann.ie dublinbus.ie; DELIVERY=deliveroo.ie just-eat.ie; EVENTOS=ticketmaster.ie; " +
            "EMPLEOS=irishjobs.ie jobs.ie; VENTAS=donedeal.ie; SUPERMERCADOS=tesco.ie dunnesstores.com supervalu.ie; MAQUILLAJE=boots.ie; " +
            "VUELOS=aerlingus.com ryanair.com; ARRIENDOS=daft.ie myhome.ie; VEHICULOS=donedeal.ie carzone.ie",
        "TR" to "CINE=cinemaximum.com.tr; EVENTOS=biletix.com; VENTAS=trendyol.com hepsiburada.com; TIENDAS=trendyol.com hepsiburada.com; " +
            "VESTUARIO=trendyol.com; MAQUILLAJE=trendyol.com; EMPLEOS=kariyer.net; SUPERMERCADOS=migros.com.tr; DELIVERY=yemeksepeti.com getir.com; " +
            "VUELOS=turkishairlines.com flypgs.com; BUSES=moovitapp.com obilet.com; ARRIENDOS=sahibinden.com; VEHICULOS=sahibinden.com arabam.com",
        "ZA" to "CINE=sterkinekor.com numetro.co.za; EVENTOS=computicket.com; VENTAS=takealot.com; TIENDAS=takealot.com; " +
            "EMPLEOS=pnet.co.za careers24.com; SUPERMERCADOS=checkers.co.za pnp.co.za woolworths.co.za; DELIVERY=ubereats.com mrd.com; " +
            "VUELOS=flysafair.co.za flysaa.com; BUSES=moovitapp.com intercape.co.za; ARRIENDOS=property24.com privateproperty.co.za; " +
            "VEHICULOS=autotrader.co.za cars.co.za; MAQUILLAJE=clicks.co.za dischem.co.za; VACACIONES=southafrica.net",
        "SE" to "SUPERMERCADOS=ica.se coop.se; ARRIENDOS=blocket.se hemnet.se; VEHICULOS=blocket.se; VENTAS=blocket.se; VUELOS=sas.se norwegian.com; " +
            "DELIVERY=foodora.se wolt.com; MAQUILLAJE=lyko.com kicks.se; VESTUARIO=zalando.se hm.com",
        "NO" to "ARRIENDOS=finn.no; VEHICULOS=finn.no; VENTAS=finn.no; EMPLEOS=finn.no nav.no; DELIVERY=foodora.no wolt.com; " +
            "SUPERMERCADOS=rema.no kiwi.no meny.no; VUELOS=norwegian.com sas.no wideroe.no",
        "DK" to "VENTAS=dba.dk; VEHICULOS=dba.dk bilbasen.dk; ARRIENDOS=boligportal.dk; EMPLEOS=jobindex.dk; DELIVERY=just-eat.dk wolt.com; " +
            "SUPERMERCADOS=netto.dk rema1000.dk; VUELOS=sas.dk norwegian.com",
        "FI" to "CINE=finnkino.fi; VENTAS=tori.fi; ARRIENDOS=oikotie.fi; EMPLEOS=oikotie.fi duunitori.fi; VEHICULOS=nettiauto.com; " +
            "SUPERMERCADOS=s-kaupat.fi k-ruoka.fi; VUELOS=finnair.com; DELIVERY=wolt.com foodora.fi",
        "CZ" to "ARRIENDOS=sreality.cz bezrealitky.cz; EMPLEOS=jobs.cz; VEHICULOS=sauto.cz; VENTAS=bazos.cz alza.cz; TIENDAS=alza.cz; " +
            "SUPERMERCADOS=rohlik.cz; CINE=cinemacity.cz; DELIVERY=wolt.com foodora.cz",
        "HU" to "ARRIENDOS=ingatlan.com; EMPLEOS=profession.hu; VEHICULOS=hasznaltauto.hu; VENTAS=jofogas.hu emag.hu; CINE=cinemacity.hu; " +
            "DELIVERY=wolt.com foodora.hu",
        "RO" to "ARRIENDOS=imobiliare.ro olx.ro; EMPLEOS=ejobs.ro; VEHICULOS=autovit.ro olx.ro; VENTAS=emag.ro olx.ro; TIENDAS=emag.ro; " +
            "CINE=cinemacity.ro; DELIVERY=glovoapp.com",
        "GR" to "ARRIENDOS=spitogatos.gr xe.gr; VEHICULOS=car.gr; VENTAS=skroutz.gr; TIENDAS=skroutz.gr; DELIVERY=efood.gr wolt.com; " +
            "EMPLEOS=kariera.gr; VUELOS=aegeanair.com",
        "UA" to "CINE=multiplex.ua; VENTAS=olx.ua rozetka.com.ua; TIENDAS=rozetka.com.ua; EMPLEOS=work.ua robota.ua; VEHICULOS=auto.ria.com; " +
            "ARRIENDOS=dom.ria.com olx.ua; DELIVERY=glovoapp.com",
        "NL" to "ARRIENDOS=funda.nl pararius.nl; VENTAS=marktplaats.nl bol.com; TIENDAS=bol.com; SUPERMERCADOS=ah.nl jumbo.com; " +
            "DELIVERY=thuisbezorgd.nl; EMPLEOS=nationalevacaturebank.nl indeed.nl; VEHICULOS=marktplaats.nl autoscout24.nl",
        "BE" to "ARRIENDOS=immoweb.be; VENTAS=2dehands.be; DELIVERY=takeaway.com deliveroo.be; EMPLEOS=stepstone.be; VEHICULOS=autoscout24.be 2dehands.be",
        "CH" to "ARRIENDOS=homegate.ch comparis.ch; VENTAS=tutti.ch galaxus.ch; TIENDAS=galaxus.ch; SUPERMERCADOS=migros.ch coop.ch; " +
            "BUSES=sbb.ch; VEHICULOS=autoscout24.ch; DELIVERY=just-eat.ch",
        "AT" to "ARRIENDOS=willhaben.at; VEHICULOS=willhaben.at; SUPERMERCADOS=billa.at spar.at; BUSES=oebb.at; DELIVERY=lieferando.at foodora.at; " +
            "VUELOS=austrian.com",
        "EC" to "ARRIENDOS=plusvalia.com; VEHICULOS=patiotuerca.com; EMPLEOS=multitrabajos.com; SUPERMERCADOS=supermaxi.com",
        "CR" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com; SUPERMERCADOS=automercado.cr",
        "PA" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com; VUELOS=copaair.com",
        "GT" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com",
        "HN" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com",
        "SV" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com",
        "NI" to "VENTAS=encuentra24.com; VEHICULOS=encuentra24.com; ARRIENDOS=encuentra24.com",
        "DO" to "VENTAS=corotos.com.do; VEHICULOS=corotos.com.do; ARRIENDOS=supercasas.com corotos.com.do; SUPERMERCADOS=sirena.do"
    ).mapValues { (_, text) -> parseSites(text) } }

    /** Idioma de la respuesta: el de la pregunta (o el del país si la pregunta no lo deja claro). */
    fun replyLanguage(question: String, country: String): String {
        val q = question.lowercase()
        if (Regex("[\\p{IsHiragana}\\p{IsKatakana}]").containsMatchIn(q)) return "ja"
        if (Regex("\\p{IsHangul}").containsMatchIn(q)) return "ko"
        // 漢字 solos: en Japón es japonés («映画館»); en lo demás, chino
        if (Regex("\\p{IsHan}").containsMatchIn(q)) return if (country.equals("JP", ignoreCase = true)) "ja" else "zh"
        if (Regex("\\p{IsCyrillic}").containsMatchIn(q)) return "ru"
        if (looksSpanish(question)) return "es"
        // 🇪🇸 Escrito en español aunque esté en Estados Unidos, China o Alemania («buses en Chicago», «cine en Miami»):
        //    la respuesta va en español (mucha gente vive allá y no entiende bien el idioma del país)
        val english = Regex("(?<![\\p{L}])(the|to|of|for|near|my|show|find|where|what|how|cheap|flights?|movies?|hotels?|food|jobs?|stores?|shops?|cars?|rent|tickets?|routes?)(?![\\p{L}])").containsMatchIn(q)
        val otherRomance = Regex("[ãõçàèâêîôûœ]|(?<![\\p{L}])(em|não|você|para o|le|les|pour|avec|und|der|die|das|il|gli|per)(?![\\p{L}])").containsMatchIn(q)
        val spanishOnly = Regex("(?<![\\p{L}])(cines?|comida|maquillaje|empleos?|trabajos?|colectivos?|recorridos?|p[aá]ginas?|buscar|cerca de m[ií]|" +
            "ropa|zapatillas|autos?|arriendos?|departamentos?|casas?|tiendas?|supermercados?|farmacias?|boletos?)(?![\\p{L}])").containsMatchIn(q)
        val spanishWords = Regex("(?<![\\p{L}])(en|de|del|la|el|los|las|para|por|un|una|y|que|con|mi|me|a la|al)(?![\\p{L}])").containsMatchIn(q)
        // En Brasil, Portugal, Francia, Italia, Alemania…: «de», «la», «comida» también son de su idioma,
        //    así que ahí se pide una palabra que sea solo del español
        val countryLang = humorLanguage("", country.uppercase())
        // (solo en países de letras latinas: en Corea, China, Japón o Rusia su idioma se escribe con otras letras)
        val sharedRisk = countryLang in setOf("pt", "fr", "it", "de", "nl", "pl")
        val strongSpanish = spanishOnly && !(sharedRisk && Regex("(?<![\\p{L}])(comida|casas?|autos?|cines?)(?![\\p{L}])").containsMatchIn(q) &&
            !Regex("(?<![\\p{L}])(maquillaje|empleos?|trabajos?|colectivos?|recorridos?|p[aá]ginas?|buscar|cerca de m[ií]|ropa|zapatillas|arriendos?|departamentos?|tiendas?|supermercados?|farmacias?|boletos?)(?![\\p{L}])").containsMatchIn(q))
        if (!english && !otherRomance && (strongSpanish || (spanishWords && !sharedRisk))) return "es"
        // 🇬🇧 Escrito en inglés aunque esté en Japón, Alemania o Brasil («flights to Paris», «bus routes near me»)
        if (english) return "en"
        // 🌍 Portugués, francés, alemán, italiano, neerlandés o polaco: se reconoce por sus letras y palabras propias,
        //    esté donde esté («ônibus em Miami» → portugués aunque esté en EE.UU.)
        val marks = mapOf(
            "pt" to "[ãõ]|(?<![\\p{L}])(em|não|você|ônibus|onibus|voos?|passagens?|perto|onde|comprar|filmes?|loja|lojas|entrega)(?![\\p{L}])",
            "fr" to "[œ]|(?<![\\p{L}])(le|les|des|pour|avec|où|vols?|près|billets?|livraison|magasins?|horaires?)(?![\\p{L}])",
            "de" to "[äöüß]|(?<![\\p{L}])(und|der|die|das|nach|flüge|kino|wo|in der|im|günstig|lieferung|geschäfte?)(?![\\p{L}])",
            "it" to "(?<![\\p{L}])(il|gli|per|voli|vicino|dove|della|negozi|consegna|biglietti|orari)(?![\\p{L}])",
            "nl" to "(?<![\\p{L}])(het|een|naar|waar|vluchten|bioscoop|winkels?|bezorging)(?![\\p{L}])",
            "pl" to "[ąęłńśźż]|(?<![\\p{L}])(gdzie|loty|kino w|sklepy?|dostawa|bilety)(?![\\p{L}])"
        ).mapValues { (_, rx) -> Regex("(?iu)$rx").findAll(q).count() }
        marks.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { return it.key }
        val cc = country.uppercase()
        val byCountry = humorLanguage("", cc)
        return when {
            cc in SPANISH_COUNTRIES -> "es"
            // Letras latinas en un país de otro alfabeto (China, Japón, Corea, Rusia): quien escribe así no usa el idioma
            //    del país (ese se escribe con sus propias letras) → inglés
            byCountry in setOf("zh", "ja", "ko", "ru") -> "en"
            byCountry != "es" -> byCountry
            else -> "en"
        }
    }

    /** Nombre del servicio de Google según el enlace (Google Maps, Google Flights…), igual en todos los idiomas. */
    private fun googleServiceName(url: String): String = when {
        url.contains("/maps") -> "Google Maps"
        url.contains("/travel/flights") -> "Google Flights"
        url.contains("/travel/hotels") -> "Google Hotels"
        url.contains("tbm=shop") -> "Google Shopping"
        url.contains("htl;jobs") -> "Google Jobs"
        url.contains("htl;events") -> "Google Events"
        url.contains("/finance") -> "Google Finance"
        url.contains("play.google") -> "Google Play"
        else -> "Google"
    }

    /** ¿Este pedido se responde solo con enlaces? (también las recetas: van a las páginas de recetas del país) */
    fun linksOnly(cat: Category, question: String): Boolean = cat in LINK_KEYS

    private val RECIPE_RX = Regex("(?iu)receta|cocinar|preparar|ingredientes|recipe|how to (cook|make)|receita|recette|rezept|ricett|рецепт|食谱|菜谱|做法|레시피|만드는 법|レシピ|作り方")

    /** ¿Pide una receta? («receta de empanadas», «recipe for lasagna», «receita de bolo», «菜谱»…) */
    fun asksRecipe(question: String): Boolean = RECIPE_RX.containsMatchIn(question)

    /** 🍳 Páginas de recetas de cada país (las más usadas allí). */
    private val RECIPE_SITES: Map<String, List<String>> by lazy { mapOf(
        "CL" to "cookpad.com/cl recetasgratis.net", "AR" to "paulinacocina.net cookpad.com/ar recetasgratis.net",
        "MX" to "kiwilimon.com cookpad.com/mx recetasgratis.net", "PE" to "cookpad.com/pe recetasgratis.net",
        "CO" to "cookpad.com/co recetasgratis.net", "UY" to "cookpad.com/uy recetasgratis.net", "VE" to "cookpad.com/ve recetasgratis.net",
        "ES" to "recetasgratis.net directoalpaladar.com cookpad.com/es",
        "US" to "allrecipes.com foodnetwork.com seriouseats.com food.com", "CA" to "allrecipes.com foodnetwork.ca ricardocuisine.com",
        "GB" to "bbcgoodfood.com jamieoliver.com", "IE" to "bbcgoodfood.com jamieoliver.com",
        "AU" to "taste.com.au bestrecipes.com.au", "NZ" to "taste.com.au bbcgoodfood.com",
        "IN" to "tarladalal.com hebbarskitchen.com vegrecipesofindia.com",
        "BR" to "tudogostoso.com.br panelinha.com.br cybercook.com.br", "PT" to "teleculinaria.pt cookpad.com/pt",
        "FR" to "marmiton.org cuisineaz.com 750g.com", "BE" to "marmiton.org dagelijksekost.vrt.be",
        "DE" to "chefkoch.de lecker.de kitchenstories.com", "AT" to "chefkoch.de ichkoche.at", "CH" to "chefkoch.de fooby.ch",
        "IT" to "giallozafferano.it cucchiaio.it misya.info", "NL" to "ah.nl/allerhande 24kitchen.nl",
        "PL" to "kwestiasmaku.com aniagotuje.pl", "SE" to "ica.se/recept koket.se", "NO" to "matprat.no",
        "DK" to "valdemarsro.dk arla.dk/opskrifter", "FI" to "kotikokki.net valio.fi/reseptit",
        "GR" to "akispetretzikis.com sintagespareas.gr", "CZ" to "toprecepty.cz", "HU" to "nosalty.hu mindmegette.hu",
        "RO" to "jamilacuisine.ro", "TR" to "nefisyemektarifleri.com yemek.com", "RU" to "povarenok.ru eda.ru russianfood.com",
        "UA" to "cookpad.com/ua", "CN" to "xiachufang.com meishij.net", "KR" to "10000recipe.com",
        "JP" to "cookpad.com kurashiru.com delishkitchen.tv"
    ).mapValues { it.value.split(' ') } }

    /** Recetas: las del país; en Latinoamérica sin lista, las en español; en lo demás, las internacionales. */
    private fun recipeSites(cc: String, lang: String): List<String> = RECIPE_SITES[cc]
        ?: when {
            cc in LATAM || lang == "es" -> listOf("cookpad.com", "recetasgratis.net")
            lang == "pt" -> RECIPE_SITES.getValue("BR")
            lang == "fr" -> RECIPE_SITES.getValue("FR")
            lang == "de" -> RECIPE_SITES.getValue("DE")
            lang == "it" -> RECIPE_SITES.getValue("IT")
            lang == "zh" -> RECIPE_SITES.getValue("CN")
            lang == "ko" -> RECIPE_SITES.getValue("KR")
            lang == "ja" -> RECIPE_SITES.getValue("JP")
            lang == "ru" -> RECIPE_SITES.getValue("RU")
            else -> listOf("allrecipes.com", "bbcgoodfood.com", "cookpad.com")
        }

    private val LINK_KEYS: Set<Category> by lazy { LINKS_ONLY.keys }

    /** «cinemark.cl» → «https://www.cinemark.cl» · «cinemas.nos.pt» y «red.cl/planifica» se respetan */
    private fun siteUrl(site: String): String {
        val host = site.substringBefore('/')
        val plainDomain = host.count { it == '.' } == 1 || Regex("^[^.]+\\.(com|co|net|org)\\.[a-z]{2}$").matches(host)
        return "https://" + (if (plainDomain && !host.startsWith("www.")) "www." else "") + site
    }

    /** Nombres conocidos tal como se escriben (el resto: la palabra principal del dominio con mayúscula). */
    private val SITE_NAMES = mapOf(
        "moovitapp" to "Moovit", "latamairlines" to "LATAM", "skyairline" to "SKY Airline", "jetsmart" to "JetSMART",
        "pedidosya" to "PedidosYa", "ubereats" to "Uber Eats", "cinehoyts" to "Cine Hoyts", "cinepolischile" to "Cinépolis",
        "cinepolis" to "Cinépolis", "cinemarkhoyts" to "Cinemark Hoyts", "dbs" to "DBS", "bne" to "BNE", "efe" to "EFE",
        "dtpr" to "DTPR (Transporte Público Regional)", "red" to "Red Movilidad", "computrabajo" to "Computrabajo",
        "tripadvisor" to "Tripadvisor", "chiletrabajos" to "Chiletrabajos", "mercadolibre" to "Mercado Libre",
        "yapo" to "Yapo", "chileautos" to "Chileautos", "portalinmobiliario" to "Portal Inmobiliario", "turbus" to "Turbus",
        "recorrido" to "Recorrido.cl", "kupos" to "Kupos", "busbud" to "Busbud", "rome2rio" to "Rome2rio",
        // 🌍 Otros países
        "aa" to "American Airlines", "amctheatres" to "AMC Theatres", "regmovies" to "Regal", "transitapp" to "Transit",
        "mta" to "MTA", "wmata" to "WMATA", "doordash" to "DoorDash", "grubhub" to "Grubhub", "ele" to "Ele.me", "jd" to "JD.com",
        "airchina" to "Air China", "csair" to "China Southern", "ctrip" to "Ctrip", "amap" to "Amap (高德地图)", "baidu" to "Baidu Maps (百度地图)",
        "voegol" to "GOL", "voeazul" to "Azul", "ifood" to "iFood", "sptrans" to "SPTrans", "decolar" to "Decolar",
        "aerolineas" to "Aerolíneas Argentinas", "jal" to "JAL", "ana" to "ANA", "peach-air" to "Peach", "demae-can" to "Demae-can",
        "cosme" to "@cosme", "yahoo" to "Yahoo! 乗換案内", "rakuten" to "Rakuten", "hrs" to "HRS", "bvg" to "BVG", "dm" to "dm",
        "bahn" to "Deutsche Bahn", "uci-kinowelt" to "UCI Kinowelt", "cinemaxx" to "CinemaxX", "booking" to "Booking.com",
        "hotels" to "Hotels.com", "steampowered" to "Steam", "investing" to "Investing.com", "linkedin" to "LinkedIn",
        "indeed" to "Indeed", "eventbrite" to "Eventbrite", "ticketmaster" to "Ticketmaster", "skyscanner" to "Skyscanner",
        "kayak" to "Kayak", "expedia" to "Expedia", "airbnb" to "Airbnb", "amazon" to "Amazon", "sephora" to "Sephora",
        "notino" to "Notino", "zara" to "Zara", "hm" to "H&M", "wolt" to "Wolt", "glovoapp" to "Glovo", "lonelyplanet" to "Lonely Planet",
        "autocosmos" to "Autocosmos", "tiendeo" to "Tiendeo", "trivago" to "Trivago", "agoda" to "Agoda",
        "cgv" to "CGV", "lottecinema" to "Lotte Cinema", "megabox" to "Megabox", "naver" to "Naver Map", "kakao" to "Kakao Map",
        "baemin" to "Baemin (배달의민족)", "yogiyo" to "Yogiyo", "coupangeats" to "Coupang Eats", "coupang" to "Coupang",
        "maoyan" to "Maoyan (猫眼)", "taopiaopiao" to "Taopiaopiao (淘票票)", "douban" to "Douban (豆瓣)", "meituan" to "Meituan (美团)",
        "tmall" to "Tmall", "xiaohongshu" to "Xiaohongshu (小红书)", "qunar" to "Qunar", "eiga" to "Eiga.com", "tohotheater" to "TOHO Cinemas",
        "aeoncinema" to "Aeon Cinema", "navitime" to "NAVITIME", "jorudan" to "Jorudan", "ekitan" to "Ekitan", "jalan" to "Jalan",
        "ikyu" to "Ikyu", "afisha" to "Afisha", "kinopoisk" to "Kinopoisk", "yandex" to "Yandex", "odeon" to "ODEON",
        "cineworld" to "Cineworld", "myvue" to "Vue", "allocine" to "AlloCiné", "ugc" to "UGC", "pathe" to "Pathé",
        "cinesa" to "Cinesa", "yelmocines" to "Yelmo Cines", "ecartelera" to "eCartelera", "kinepolis" to "Kinepolis",
        "fandango" to "Fandango", "kinoplex" to "Kinoplex", "ingresso" to "Ingresso.com", "lieferando" to "Lieferando",
        "deliveroo" to "Deliveroo", "justeat" to "Just Eat", "thuisbezorgd" to "Thuisbezorgd",
        "cineplex" to "Cineplex", "landmarkcinemas" to "Landmark Cinemas", "skipthedishes" to "SkipTheDishes", "hoyts" to "HOYTS",
        "eventcinemas" to "Event Cinemas", "villagecinemas" to "Village Cinemas", "menulog" to "Menulog", "bookmyshow" to "BookMyShow",
        "pvrcinemas" to "PVR INOX", "redbus" to "redBus", "zomato" to "Zomato", "swiggy" to "Swiggy",
        "encuentra24" to "Encuentra24", "trademe" to "Trade Me", "realestate" to "realestate.com.au", "carsales" to "Carsales",
        "seek" to "SEEK", "naukri" to "Naukri", "flipkart" to "Flipkart", "myntra" to "Myntra", "nykaa" to "Nykaa", "makemytrip" to "MakeMyTrip",
        "99acres" to "99acres", "cardekho" to "CarDekho", "daft" to "Daft.ie", "donedeal" to "DoneDeal", "ryanair" to "Ryanair",
        "aerlingus" to "Aer Lingus", "sahibinden" to "Sahibinden", "trendyol" to "Trendyol", "yemeksepeti" to "Yemeksepeti",
        "turkishairlines" to "Turkish Airlines", "takealot" to "Takealot", "property24" to "Property24", "finn" to "FINN.no",
        "blocket" to "Blocket", "olx" to "OLX", "emag" to "eMAG", "funda" to "Funda", "marktplaats" to "Marktplaats",
        "bol" to "bol.com", "ah" to "Albert Heijn", "immoweb" to "Immoweb", "homegate" to "Homegate", "willhaben" to "willhaben",
        "sbb" to "SBB", "oebb" to "ÖBB", "copaair" to "Copa Airlines", "aircanada" to "Air Canada", "westjet" to "WestJet",
        "qantas" to "Qantas", "jetstar" to "Jetstar", "airnewzealand" to "Air New Zealand", "kijiji" to "Kijiji", "autotrader" to "AutoTrader",
        "skroutz" to "Skroutz", "rozetka" to "Rozetka", "multiplex" to "Multiplex", "finnkino" to "Finnkino", "cinemacity" to "Cinema City",
        "sterkinekor" to "Ster-Kinekor", "numetro" to "Nu Metro", "ticketek" to "Ticketek", "woolworths" to "Woolworths", "coles" to "Coles",
        "cookpad" to "Cookpad", "recetasgratis" to "RecetasGratis", "paulinacocina" to "Paulina Cocina", "kiwilimon" to "Kiwilimón",
        "directoalpaladar" to "Directo al Paladar", "allrecipes" to "Allrecipes", "foodnetwork" to "Food Network",
        "seriouseats" to "Serious Eats", "food" to "Food.com", "ricardocuisine" to "Ricardo", "bbcgoodfood" to "BBC Good Food",
        "jamieoliver" to "Jamie Oliver", "taste" to "taste.com.au", "bestrecipes" to "Best Recipes", "tarladalal" to "Tarla Dalal",
        "hebbarskitchen" to "Hebbar's Kitchen", "vegrecipesofindia" to "Veg Recipes of India", "tudogostoso" to "TudoGostoso",
        "panelinha" to "Panelinha", "cybercook" to "CyberCook", "teleculinaria" to "Teleculinária", "marmiton" to "Marmiton",
        "cuisineaz" to "CuisineAZ", "750g" to "750g", "chefkoch" to "Chefkoch", "lecker" to "LECKER", "kitchenstories" to "Kitchen Stories",
        "ichkoche" to "ichkoche.at", "fooby" to "FOOBY", "giallozafferano" to "GialloZafferano", "cucchiaio" to "Il Cucchiaio d'Argento",
        "misya" to "Misya", "24kitchen" to "24Kitchen", "kwestiasmaku" to "Kwestia Smaku", "aniagotuje" to "Ania Gotuje",
        "koket" to "Köket.se", "matprat" to "MatPrat", "valdemarsro" to "Valdemarsro", "arla" to "Arla", "kotikokki" to "Kotikokki",
        "valio" to "Valio", "akispetretzikis" to "Akis Petretzikis", "sintagespareas" to "Syntages Pareas", "toprecepty" to "TopRecepty",
        "nosalty" to "Nosalty", "mindmegette" to "Mindmegette", "jamilacuisine" to "JamilaCuisine",
        "nefisyemektarifleri" to "Nefis Yemek Tarifleri", "yemek" to "Yemek.com", "povarenok" to "Povarenok", "eda" to "Eda.ru",
        "russianfood" to "RussianFood", "xiachufang" to "下厨房 (Xiachufang)", "meishij" to "美食杰 (Meishij)",
        "10000recipe" to "만개의레시피 (10000recipe)", "kurashiru" to "クラシル (Kurashiru)", "delishkitchen" to "DELISH KITCHEN"
    )

    // ------------------------------------------------------------------------
    // 🇺🇸 BUSES Y METRO EN ESTADOS UNIDOS: la página de transporte de cada ciudad grande.
    // MTA (Nueva York) y WMATA (Washington) aparecen solo ahí; en las demás ciudades: Transit, Moovit y Google Maps.
    // ------------------------------------------------------------------------
    private val US_TRANSIT: List<Pair<Regex, List<String>>> by lazy { listOf(
        "new york|nueva york|brooklyn|queens|manhattan|bronx|staten island" to listOf("mta.info"),
        "washington|arlington|alexandria|bethesda" to listOf("wmata.com"),
        "los [aá]ngeles|santa monica|pasadena|long beach|hollywood" to listOf("metro.net"),
        "chicago|evanston" to listOf("transitchicago.com"),
        "miami|hialeah|coral gables|doral" to listOf("miamidade.gov/transit/routes.asp"),
        "san francisco|oakland|berkeley" to listOf("sfmta.com", "bart.gov"),
        "boston|cambridge|somerville" to listOf("mbta.com"),
        "philadelphia|filadelfia" to listOf("septa.org"),
        "seattle|tacoma|bellevue" to listOf("soundtransit.org"),
        "atlanta" to listOf("itsmarta.com"),
        "houston" to listOf("ridemetro.org"),
        "dallas" to listOf("dart.org"),
        "denver|aurora|boulder" to listOf("rtd-denver.com"),
        "phoenix|tempe|mesa|scottsdale" to listOf("valleymetro.org"),
        "san diego" to listOf("sdmts.com"),
        "portland" to listOf("trimet.org"),
        "minneapolis|saint paul|st\\.? paul" to listOf("metrotransit.org"),
        "las vegas|henderson" to listOf("rtcsnv.com"),
        "new orleans|nueva orleans" to listOf("norta.com"),
        "honolulu" to listOf("thebus.org"),
        "austin" to listOf("capmetro.org"),
        "baltimore" to listOf("mta.maryland.gov"),
        "orlando" to listOf("golynx.com"),
        "salt lake" to listOf("rideuta.com"),
        "san jos[eé]" to listOf("vta.org"),
        "sacramento" to listOf("sacrt.com"),
        "pittsburgh" to listOf("rideprt.org")
    ).map { (rx, sites) -> Regex("(?iu)(?<![\\p{L}])($rx)(?![\\p{L}])") to sites } }

    /** Páginas de una sola ciudad: no se muestran a quien vive en otra */
    private val US_CITY_ONLY = setOf("mta.info", "wmata.com")

    /** La página de buses/metro de la ciudad del usuario (o la que nombró): «Chicago» → transitchicago.com */
    fun usCityTransit(where: String): List<String> =
        US_TRANSIT.firstOrNull { it.first.containsMatchIn(where) }?.second.orEmpty()

    /** Nombres por dominio completo (cuando la palabra sola no alcanza: «metro.net» → «LA Metro») */
    private val HOST_NAMES = mapOf(
        "metro.net" to "LA Metro", "transitchicago.com" to "CTA (Chicago)", "miamidade.gov" to "Miami-Dade Transit",
        "sfmta.com" to "SFMTA Muni", "bart.gov" to "BART", "mbta.com" to "MBTA", "septa.org" to "SEPTA",
        "soundtransit.org" to "Sound Transit", "itsmarta.com" to "MARTA", "ridemetro.org" to "Houston METRO", "dart.org" to "DART",
        "rtd-denver.com" to "RTD Denver", "valleymetro.org" to "Valley Metro", "sdmts.com" to "San Diego MTS", "trimet.org" to "TriMet",
        "metrotransit.org" to "Metro Transit", "rtcsnv.com" to "RTC Las Vegas", "norta.com" to "RTA New Orleans",
        "thebus.org" to "TheBus", "capmetro.org" to "CapMetro", "mta.maryland.gov" to "Maryland MTA", "golynx.com" to "LYNX",
        "rideuta.com" to "UTA", "vta.org" to "VTA", "sacrt.com" to "SacRT", "rideprt.org" to "Pittsburgh Regional Transit",
        "mta.info" to "MTA (New York)", "wmata.com" to "WMATA (Washington D.C.)"
    )

    /** Subdominios que no son el nombre del sitio («cl.computrabajo.com», «map.baidu.com», «store.steampowered.com») */
    private val SUBDOMAINS = setOf("www", "m", "cl", "ar", "mx", "pe", "co", "es", "br", "en", "map", "maps", "transit", "travel",
        "store", "play", "music", "support", "cinemas", "in")

    /** «moovitapp.com» → «Moovit» · «cl.computrabajo.com» → «Computrabajo» · «cinemark.cl» → «Cinemark» */
    private fun siteLabel(site: String): String {
        HOST_NAMES[site.substringBefore('/').removePrefix("www.").lowercase()]?.let { return it }
        val parts = site.substringBefore('/').removePrefix("www.").split('.')
        // «cl.computrabajo.com», «m.algo.com»: se salta el subdominio corto
        val base = (if (parts.size >= 3 && parts[0].lowercase() in SUBDOMAINS) parts[1] else parts[0]).lowercase()
        return SITE_NAMES[base] ?: base.replaceFirstChar { it.uppercase() }
    }

    /**
     * 🔗 La respuesta con solo los enlaces: «🚌 Aquí tienes las páginas de la locomoción colectiva y buses:»
     * + las páginas del tema en tu país + los enlaces de Google (Maps, Vuelos, Hoteles…) con lo que pediste.
     */
    fun linksOnlyReply(cat: Category, question: String, place: String, country: String): String {
        val recipe = cat == COMIDA && asksRecipe(question)
        val title = (if (recipe) "Aquí tienes las páginas de recetas" else LINKS_ONLY[cat]) ?: return ""
        val lang = replyLanguage(question, country)
        val tld = "." + country.lowercase()
        val cc = country.uppercase()
        // 🌍 Las páginas del país del usuario (las 40 de la lista). Chile usa su lista propia. En un país sin lista:
        //    solo las internacionales (sin las latinoamericanas si no está en Latinoamérica)
        val more = countryKey(cat)?.let { MORE_COUNTRIES[cc]?.get(it) }.orEmpty()
        val countryList = runCatching { countrySites(cat, question, country) }.getOrNull()
        val fallback = when {
            countryList != null -> emptyList()
            cc == "CL" -> runCatching { preferredSites(cat, question, country) }.getOrDefault(emptyList())
            more.size >= 2 -> emptyList()   // ya hay páginas propias del país: no se mezclan las de otros países
            // 🌍 Las internacionales (las de Latinoamérica solo en Latinoamérica: Mercado Libre, PedidosYa, Rappi…)
            else -> countryKey(cat)?.let { GLOBAL_FILL[it] }.orEmpty().filter { s -> cc in LATAM || LATAM_ONLY.none { s.contains(it) } }
                // 🌎 sin las páginas de OTRO país latino (Cineplanet Perú, Despegar Chile…)
                .filter { s -> val tld = Regex("\\.([a-z]{2})(?:/|$)").find(s.substringBefore('/') + "/")?.groupValues?.get(1)
                    tld == null || tld !in LATAM_TLDS || tld == cc.lowercase() }
        }
        // 1) las páginas del país · 2) las que se agregaron para completar · 3) las internacionales (si el país no tenía lista)
        // 🍳 Recetas: las páginas de recetas del país (Allrecipes en EE.UU., Tudo Gostoso en Brasil, 下厨房 en China…)
        val base = if (recipe) recipeSites(cc, lang) else countryList.orEmpty() + more + fallback
        // 🇺🇸 Buses en Estados Unidos: primero la página de la ciudad; MTA y WMATA solo en Nueva York y Washington
        val withCity = if (cat == BUSES && cc == "US") {
            // 1) el lugar que nombró · 2) la pregunta · 3) su ciudad (solo la ciudad: «Seattle, Washington» no es Washington D.C.)
            val local = usCityTransit(namedPlace(question).orEmpty()).ifEmpty { usCityTransit(question) }
                .ifEmpty { usCityTransit(place.substringBefore(',')) }
            local + base.filter { it !in US_CITY_ONLY && it !in local }
        } else base
        val sites = withCity
            .filter { it.isNotBlank() }
            // 🎟️ Cine: solo los cines del país (en Chile, Cinépolis México no sirve)
            .let { all -> if (cat == CINE && all.count { it.substringBefore('/').endsWith(tld) } >= 2) all.filter { it.substringBefore('/').endsWith(tld) } else all }
            .distinctBy { siteLabel(it) }.take(5)
        val google = (if (recipe) listOf("Ver la receta en Google" to "https://www.google.com/search?q=" +
                URLEncoder.encode(stripRequestWords(question.trim().replace(Regex("[¿?¡!]"), " ")).trim().ifBlank { question }, "UTF-8"))
            else runCatching { serviceLinks(cat, question, place, failed = true) }.getOrDefault(emptyList()))
            // 🌍 En otro idioma: el nombre del servicio de Google (igual en todos los idiomas) en vez del texto en español
            .map { (label, url) ->
                if (lang == "es") label to url
                // las palabras en español que agrega la búsqueda («cartelera», «desde») se quitan en otro idioma
                else googleServiceName(url) to url.replace("cartelera+cine+", "").replace("cartelera+", "").replace("+desde+", "+")
            }
        if (sites.isEmpty() && google.isEmpty()) return ""
        val sb = StringBuilder(if (recipe) "🍳" else cat.emoji).append(' ').append(if (lang == "es") title else LINKS_TITLE[lang] ?: LINKS_TITLE.getValue("en"))
        val city = place.substringBefore(',').trim()
        // 📍 Tu ciudad solo si no nombraste otro lugar («micros en Concepción» → no se agrega Chiguayante)
        if (!recipe && city.isNotBlank() && cat.local && namedPlace(question) == null) sb.append(" (").append(city).append(")")
        sb.append(":\n")
        sites.forEach { sb.append("\n🔗 ").append(siteLabel(it)).append(": ").append(siteUrl(it)) }
        if (google.isNotEmpty()) {
            if (sites.isNotEmpty()) sb.append('\n')
            google.forEach { (label, url) -> sb.append("\n🔎 ").append(label).append(": ").append(url) }
        }
        return sb.toString()
    }

    /**
     * 🔎 Enlaces a Google para ver más (los pone la app, no la IA), en TODOS los tipos de pedido:
     * ✈️ Google Vuelos con precios, 🏨 Google Hoteles, 🗺️ Google Maps (horarios de tiendas y supermercados, cómo llegar en micro),
     * 💼 Google Empleos, 🛍️ Google Shopping (precios y ofertas), 🎟️ cartelera, 🎤 eventos, 📈 Google Finance…
     * La información va primero en la pantalla; el enlace es para ver más detalle.
     */
    fun serviceLinks(cat: Category, question: String, place: String, failed: Boolean): List<Pair<String, String>> {
        val city = place.substringBefore(',').trim()
        val country = place.substringAfterLast(',', "").trim()
        val q = stripRequestWords(question.trim().replace(Regex("[¿?¡!]"), "").replace(Regex("\\s+"), " ")).take(120)
        if (q.isBlank()) return emptyList()
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val low = q.lowercase()
        val withCity = if (city.isNotBlank() && !low.contains(city.lowercase()) && namedPlace(q) == null) "$q $city" else q
        val withCountry = if (country.isNotBlank() && !low.contains(country.lowercase())) "$q $country" else q
        val recipe = Regex("(?iu)receta|cocinar|preparar").containsMatchIn(q)
        fun google(text: String) = "https://www.google.com/search?q=" + e(text)
        fun maps(text: String) = "https://www.google.com/maps/search/" + e(text)
        fun shopping(text: String) = "https://www.google.com/search?tbm=shop&q=" + e(text)
        val links = when (cat) {
            CINE -> listOf("Ver cartelera y horarios en Google" to google(when {
                Regex("(?iu)cartelera|horario").containsMatchIn(q) -> withCity
                Regex("(?iu)\\bcines?\\b|pel[ií]cula").containsMatchIn(q) -> "cartelera $withCity"   // sin «cine cine»
                else -> "cartelera cine $withCity"
            }))
            VIDEOS -> listOf("Ver en YouTube" to "https://www.youtube.com/results?search_query=" + e(q))
            MUSICA -> listOf("Escuchar en YouTube Music" to "https://music.youtube.com/search?q=" + e(q))
            EVENTOS -> listOf("Ver eventos y entradas en Google" to "https://www.google.com/search?ibp=htl;events&q=" + e(withCity))
            NEGOCIOS -> listOf("Ver valores en Google Finance" to "https://www.google.com/finance/?q=" + e(q),
                "Ver más en Google" to google(withCountry))
            VENTAS -> listOf("Ver precios y ofertas en Google Shopping" to shopping(withCountry))
            EMPLEOS -> listOf("Ver ofertas en Google Empleos" to "https://www.google.com/search?ibp=htl;jobs&q=" + e(withCity))
            VESTUARIO, MAQUILLAJE, TECNOLOGIA -> listOfNotNull("Ver precios en Google Shopping" to shopping(withCountry),
                if (StoreFinder.asksForStore(q)) "Ver tiendas y horarios en Google Maps" to maps(withCity) else null)
            TIENDAS -> listOf("Ver tiendas, horarios y cómo llegar en Google Maps" to maps(withCity))
            SUPERMERCADOS -> listOf("Ver supermercados y horarios en Google Maps" to maps(if (Regex("(?iu)supermercado|l[ií]der|jumbo|unimarc|tottus|santa isabel|acuenta").containsMatchIn(q)) withCity else "supermercados $withCity"),
                "Ver precios y ofertas en Google Shopping" to shopping(withCountry))
            COMIDA -> if (recipe) listOf("Ver la receta en Google" to google(q)) else {
                // «quiero comer algo», «tengo hambre» → «restaurantes Santiago»; «sushi en Providencia» se deja tal cual
                val food = q.replace(Regex("(?iu)\\b(quiero|quisiera|comer|algo|tengo|hambre|dame|d[oó]nde|donde|puedo|para|me|un|una|lugar(es)?)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim().ifBlank { "restaurantes" }
                val mq = if (city.isNotBlank() && !food.lowercase().contains(city.lowercase())) "$food $city" else food
                listOf("Ver lugares para comer en el mapa" to maps(mq))
            }
            DELIVERY -> listOf("Ver locales con delivery en Google Maps" to maps(withCity))
            VIAJES, VACACIONES -> listOf("Ver más en Google" to google(withCountry))
            VUELOS -> {
                val from = if (city.isNotBlank() && !isFlightStatus(q) && !Regex("(?iu)\\bdesde\\s+\\p{L}{3,}").containsMatchIn(q) &&
                    !Regex("(?u)\\b([Dd]esde|[Dd]e)\\s+\\p{Lu}").containsMatchIn(q) && !low.contains(city.lowercase())) " desde $city" else ""
                if (isFlightStatus(q)) listOf("Ver el estado del vuelo en Google" to google(q))
                else listOf("Ver vuelos y precios en Google Vuelos" to "https://www.google.com/travel/flights?q=" + e(q + from))
            }
            BUSES -> {
                val dest = NearbyFinder.destination(q)
                listOfNotNull(
                    dest?.let { "Cómo llegar en micro a $it (Google Maps)" to "https://www.google.com/maps/dir/?api=1&travelmode=transit&destination=" + e(if (city.isNotBlank()) "$it, $city" else it) },
                    "Ver recorridos y horarios en Google Maps" to maps(withCity),
                    "Ver horarios en Google" to google(withCity)
                )
            }
            HOTELES -> listOf("Ver hoteles y precios en Google Hoteles" to "https://www.google.com/travel/hotels?q=" + e(withCity))
            ARRIENDOS, VEHICULOS -> listOf("Ver más avisos en Google" to google(withCity))
            CLIMA -> listOf("Ver el pronóstico en Google" to google(if (low.contains("clima") || low.contains("tiempo")) withCity else "clima $withCity"))
            ASTRONOMIA -> listOf("Ver más en Google" to google(withCountry))
            SERIES, PELICULAS -> listOf("Dónde verla (Google)" to google("$q dónde ver"))
            JUEGOS -> listOf("Ver más en Google" to google(q))
            GOOGLE_PLAY -> listOf("Buscar en Google Play" to "https://play.google.com/store/search?c=apps&q=" + e(q))
            MEDICINA, YOUTUBE, YOUTUBE_MUSIC -> emptyList()
            else -> listOf("Ver más en Google" to google(if (cat.local) withCity else q))
        }
        return if (links.isEmpty() && failed) listOf("Buscar en Google" to google(if (cat.local && !recipe) withCity else q)) else links
    }

    // ------------------------------------------------------------------------
    // 🧳 VIAJES: el destino que pidió y los enlaces reales para planificarlo (los pone la app)
    // ------------------------------------------------------------------------
    /** Lugares que no son un destino («la playa», «el extranjero», «vacaciones»…) */
    private val notADestination = Regex("(?iu)^(la |el |los |las |un |una )?(playa|montaña|nieve|campo|sur|norte|extranjero|exterior|" +
        "vacaciones|viaje|viajar|paseo|tour|crucero|paquete|pasaje|hotel|aeropuerto|terminal|ciudad|pa[ií]s|lugar|destino|" +
        "verano|invierno|oto[ñn]o|primavera|semana|fin de semana|mes|año|d[ií]a|familia|pareja|niños|amigos|bus|avi[oó]n|auto)s?$")

    /** «viaje a Buenos Aires», «pasajes a Puerto Montt en enero», «requisitos para viajar a Perú» → «Buenos Aires», «Puerto Montt», «Perú» */
    fun travelDestination(question: String): String? {
        val clean = question.replace(Regex("(?iu)\\b(para|de|quiero|quisiera|me gustar[ií]a)\\s+(viajar|ir|entrar|salir|volar|conocer|visitar)\\b"), " ")
        val m = Regex("(?iu)\\b(?:a|al|hacia|para|rumbo a|con destino a)\\s+((?:la |el |los |las )?[\\p{L}][\\p{L} .'-]{2,40})").findAll(clean)
            .map { it.groupValues[1].trim().trimEnd('?', '.', '!', ',') }
            .map { it.split(Regex("(?iu)\\s+(en|para|por|con|desde|que|y|este|esta|el pr[oó]ximo|la pr[oó]xima|ida|barato|baratos|econ[oó]mico|" +
                "con|todo|precio|precios)\\b")).first().trim() }
            .firstOrNull { it.length >= 3 && !notADestination.matches(it) &&
                !Regex("(?iu)^(viajar|ir|entrar|salir|conocer|visitar|comprar|ver|pasar|irme|ir de|la hora)\\b").containsMatchIn(it) }
            ?: return null
        return m.split(' ').joinToString(" ") { w -> if (w.length > 3 || w.first().isUpperCase()) w.replaceFirstChar { it.titlecase() } else w }
    }

    /** Enlaces para planificar el viaje a ese destino: vuelos, hoteles, requisitos (y buses si viaja por tierra). */
    fun travelLinks(dest: String, city: String, country: String, byBus: Boolean): List<Pair<String, String>> {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val from = if (city.isNotBlank()) " desde $city" else ""
        val out = mutableListOf(
            "✈️ Vuelos y precios" to "https://www.google.com/travel/flights?q=" + e("vuelos a $dest$from"),
            "🏨 Hoteles y precios" to "https://www.google.com/travel/hotels?q=" + e("hoteles en $dest")
        )
        if (byBus) out += "🚌 Pasajes de bus" to "https://www.google.com/search?q=" + e("pasajes de bus$from a $dest")
        out += "🧳 Paquetes turísticos" to "https://www.google.com/search?q=" + e("paquetes turísticos a $dest$from")
        out += "📋 Requisitos para viajar" to "https://www.google.com/search?q=" +
            e("requisitos para viajar a $dest" + (if (country.isNotBlank()) " desde $country" else ""))
        return out
    }

    const val MEDICAL_NOTE = "⚕️ Esta información es solo informativa y no reemplaza la consulta con un profesional de la salud."
}
