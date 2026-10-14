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
        "- Especificaciones, lanzamientos de dispositivos, actualizaciones de software y sistemas operativos, y precios de gadgets y hardware en tiendas especializadas.",
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
            "a qu[eé] hora (pasa|sale|parte|llega) (la |el )?(micro|bus|liebre|colectivo|tren|metro)") to BUSES,
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
            "aud[ií]fonos", "televisor(es)?", "smart ?tv", "tecnolog[ií]a", "snapdragon", "ficha t[eé]cnica") to TECNOLOGIA,
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
        return ORDER.firstOrNull { it.first.containsMatchIn(p) }?.second ?: GENERAL
    }

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
        10 to "1. Tienda/sucursal — dirección — horario de hoy — teléfono o sitio — stock si aparece",
        11 to "Compara por producto: Producto — precio en cada supermercado — ✅ el más barato.\nLuego 🏷️ ofertas de la semana por supermercado.",
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
        16 to "1. Línea/recorrido — desde → hasta — paraderos principales — horario y frecuencia — tarifa\n" +
            "Si pregunta cómo llegar: pasos numerados (qué micro o metro tomar y dónde bajarse).",
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
        22 to "1. Equipo — pantalla · procesador · RAM/almacenamiento · batería · cámara — precio — dónde comprar — fecha de lanzamiento\n" +
            "Si compara, al final el ganador en cada aspecto.",
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

    fun searchQuery(cat: Category, cleanedQuery: String, place: String, year: Int): String {
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
            else -> HINTS[cat.num].orEmpty()
        }
        val month = MONTHS[java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)]
        val hasYear = Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(q)
        val low = q.lowercase()
        hint.replace("{MES}", month).replace("{AÑO}", if (hasYear) "" else year.toString())
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

    /** «cerca», «aquí», «dónde queda», «sucursal», «mi comuna»…: lo que pide depende de dónde estás. */
    private val nearWords = Regex("(?iu)\\bcerca\\b|cercan[oa]s?|\\baqu[ií]\\b|\\bac[aá]\\b|donde estoy|por (aqu[ií]|ac[aá]|mi casa)|" +
        "mi (ciudad|comuna|zona|sector|barrio|regi[oó]n|pueblo)|d[oó]nde (queda|quedan|hay|est[aá]n?|encuentro|compro|puedo)|" +
        "\\bdirecci[oó]n(es)?\\b|sucursal(es)?|oficinas?|abiert[oa]s?|horarios? de atenci[oó]n|\\bm[aá]s cercan")

    /** Preguntas por precio: el precio cambia según el país (se agrega el tuyo). */
    private val priceWords = Regex("(?iu)precios?|cu[aá]nto (cuesta|vale|sale)|valor|comprar|d[oó]nde (lo |la )?(compro|venden)|barat|ofertas?|en chile|disponib")

    /** ✈️ «estado del vuelo LA800», «a qué hora llega el vuelo»: no es comprar pasajes. */
    private val flightStatus = Regex("(?iu)estado (de(l)? )?(mi )?vuelo|vuelo [A-Za-z]{2}\\s?\\d{2,4}\\b|\\b(LA|H2|JA|AR|AV|CM|IB|AA|UA|DL|AM)\\s?\\d{2,4}\\b|" +
        "(llega|lleg[oó]|aterriz|despeg|sale|sali[oó]) (el|mi) vuelo|vuelo (atrasado|retrasado|cancelado|demorado)|llegadas|salidas de vuelos|puerta de embarque")
    fun isFlightStatus(q: String): Boolean = flightStatus.containsMatchIn(q)

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
        sb.append("- Fecha y hora actual: ").append(dateTime).append('\n')
        sb.append("- Ubicación del usuario: ").append(lugar).append('\n')
        if (currency.isNotBlank() && cat.num in PRICE_CATS) sb.append("- Moneda local: ").append(currency).append('\n')
        sb.append("- Idioma de respuesta: ").append(language).append('\n')
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
            sb.append("- La portada/afiche y el enlace de YouTube de cada título los pone la app debajo: no escribas enlaces ni imágenes.\n")
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

    /**
     * 🔎 Enlaces reales para seguir buscando (los pone la app, no la IA): Google Vuelos, Google Hoteles, el mapa…
     * Siempre en Vuelos y Hoteles; en las demás, solo si internet no trajo resultados (failed = true).
     */
    fun serviceLinks(cat: Category, question: String, place: String, failed: Boolean): List<Pair<String, String>> {
        val city = place.substringBefore(',').trim()
        val q = question.trim().replace(Regex("[¿?¡!]"), "").replace(Regex("\\s+"), " ").take(120)
        if (q.isBlank()) return emptyList()
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val low = q.lowercase()
        val withCity = if (city.isNotBlank() && !low.contains(city.lowercase())) "$q $city" else q
        val recipe = Regex("(?iu)receta|cocinar|preparar").containsMatchIn(q)
        val links = when (cat) {
            VUELOS -> {
                val from = if (city.isNotBlank() && !Regex("(?u)\\b([Dd]esde|[Dd]e)\\s+\\p{Lu}").containsMatchIn(q) && !low.contains(city.lowercase())) " desde $city" else ""
                listOf("Ver vuelos y precios en Google Vuelos" to "https://www.google.com/travel/flights?q=" + e(q + from))
            }
            HOTELES -> listOf("Ver hoteles y precios en Google Hoteles" to "https://www.google.com/travel/hotels?q=" + e(q))
            COMIDA -> if (recipe) emptyList() else {
                // «quiero comer algo», «tengo hambre» → «restaurantes Santiago»; «sushi en Providencia» se deja tal cual
                val food = q.replace(Regex("(?iu)\\b(quiero|quisiera|comer|algo|tengo|hambre|dame|d[oó]nde|donde|puedo|para|me|un|una|lugar(es)?)\\b"), " ")
                    .replace(Regex("\\s+"), " ").trim().ifBlank { "restaurantes" }
                val mq = if (city.isNotBlank() && !food.lowercase().contains(city.lowercase())) "$food $city" else food
                listOf("Ver lugares para comer en el mapa" to "https://www.google.com/maps/search/" + e(mq))
            }
            DELIVERY, TIENDAS, SUPERMERCADOS -> listOf("Ver en el mapa" to "https://www.google.com/maps/search/" + e(withCity))
            EMPLEOS -> listOf("Ver ofertas en Google Empleos" to "https://www.google.com/search?ibp=htl;jobs&q=" + e(withCity))
            BUSES -> listOf("Ver recorrido en Google Maps" to "https://www.google.com/maps/search/" + e(withCity))
            else -> emptyList()
        }
        return when {
            links.isNotEmpty() && (failed || cat == VUELOS || cat == HOTELES) -> links
            failed -> listOf("Buscar en Google" to "https://www.google.com/search?q=" + e(if (cat.local && !recipe) withCity else q))
            else -> emptyList()
        }
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

