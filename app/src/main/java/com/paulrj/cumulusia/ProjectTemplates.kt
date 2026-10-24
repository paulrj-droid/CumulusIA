package com.paulrj.cumulusia

import java.text.Normalizer
import java.util.Locale

/**
 * 🏗️ BASE PROFESIONAL PARA LAS APPS Y PÁGINAS QUE CREA LA IA
 *
 * Un modelo pequeño no arma bien una app entera (sale «Botón 1, Botón 2» y sin los colores pedidos).
 * Por eso la app arma SIEMPRE una base moderna y probada, y la IA solo rellena lo propio de cada app:
 *  - Panel lateral IZQUIERDO con el menú
 *  - Panel lateral DERECHO con los ajustes
 *  - Barra superior con ☰ y ⚙️
 *  - Pantalla de inicio con tarjetas y atajos
 *  - Barra de escritura con 🎤 micrófono y ➤ enviar
 *  - Los colores que pidió el usuario («botones azules» → azul, «#FF5722» → ese color exacto)
 *  - MÓDULOS EXTRA cuando el usuario los nombra: 🫧 burbuja flotante, 🔔 notificaciones, 📷 cámara,
 *    💾 guardar datos, ⏰ recordatorios, 🌐 datos de internet, 💬 mensajería y 👥 red social
 *    (con su parte visual, su código y sus permisos)
 * La IA escribe: título, opciones del menú, ajustes, tarjetas, atajos y la LÓGICA (AppLogic.kt / logic.js).
 */
object ProjectTemplates {

    data class AppSpec(
        val title: String,
        val subtitle: String,
        val emoji: String,
        val menu: List<String>,
        val settings: List<Pair<Boolean, String>>,   // true = interruptor, false = botón
        val welcome: String,
        val welcomeSub: String,
        val cards: List<Pair<String, String>>,       // título (con emoji) → descripción
        val chips: List<String>,
        val hint: String
    )

    data class Palette(
        val primary: String, val primaryDark: String, val primaryLight: String, val onPrimary: String,
        val background: String, val surface: String, val surfaceVariant: String,
        val textPrimary: String, val textSecondary: String, val divider: String, val light: Boolean,
        // 🎨 Colores que pediste por separado (vacío = el de siempre): ventanas laterales ☰ ⚙️ y los íconos ☰ ⚙️
        val panel: String = "", val panelText: String = "", val icons: String = "",
        // 💬 Barra de escritura: su color, el de las letras y el de la pista/🎤 (vacío = el de siempre)
        val bar: String = "", val barText: String = "", val barHint: String = ""
    )

    // ------------------------------------------------------------------ módulos extra
    enum class Module(val label: String) {
        BUBBLE("🫧 Burbuja flotante"), NOTIFY("🔔 Notificaciones"), CAMERA("📷 Cámara"),
        STORE("💾 Guardar datos"), REMIND("⏰ Recordatorios"), NET("🌐 Datos de internet"), CHAT("💬 Mensajería"),
        SOCIAL("👥 Red social")
    }

    private val moduleWords = listOf(
        Module.BUBBLE to Regex("(?i)\\b(burbujas?|flotantes?|chat ?heads?|sobre (las )?otras apps|overlay|bubble)\\b"),
        Module.NOTIFY to Regex("(?i)\\b(notificaci[oó]n(es)?|notifica(r|me)?|avisos?|alertas?|push)\\b"),
        Module.CAMERA to Regex("(?i)\\b(c[aá]maras?|fotos?|fotograf[ií]as?|escane(a|ar|o|r)|esc[aá]ner|galer[ií]a|selfies?|qr|camera|photos?)\\b"),
        Module.STORE to Regex("(?i)\\b(guard(a|ar|e|en|ado|ados|ada|adas)|notas?|listas?|historial|base de datos|bd|tareas?|pendientes|favoritos|registros?|inventarios?|gastos?|diario|contactos|apuntes|persist\\w*)\\b"),
        Module.REMIND to Regex("(?i)\\b(recordatorios?|alarmas?|av[ií]same|recu[eé]rdame|agenda|calendario|horarios?|temporizador(es)?|timers?|reminders?)\\b"),
        Module.NET to Regex("(?i)\\b(internet|en l[ií]nea|online|apis?|clima|tiempo actual|noticias|precios?|d[oó]lar|tipo de cambio|cotizaci[oó]n|servidor|criptomonedas?|bitcoin)\\b"),
        Module.CHAT to Regex("(?i)\\b(mensajer[ií]a|whatsapp|telegram|messenger|chatear|chat entre|chats? (de|con|para) (amigos|usuarios|personas|grupos?)|mensajes (a|entre|con) (otr[oa]s|personas|usuarios|amigos)|mensajes (privados|directos)|chats? privados?|enviar mensajes|mandar mensajes)\\b"),
        Module.SOCIAL to Regex("(?i)\\b(red(es)? social(es)?|instagram|facebook|tiktok|twitter|threads|pinterest|publicaciones|publicar (fotos|posts|estados|historias|contenido)|seguidores|(me gusta|likes) y comentarios)\\b")
    )

    /** Módulos que el usuario nombró en su pedido (los recordatorios y la burbuja usan notificaciones). */
    fun modules(prompt: String): Set<Module> {
        val asked = prompt.substringBefore("\n\nHazla con")
        val set = moduleWords.filter { (_, rx) -> rx.containsMatchIn(asked) }.map { it.first }.toMutableSet()
        // «app de chat» sin IA ni bot = mensajería entre personas
        if (Regex("(?i)\\b(app|aplicaci[oó]n|p[aá]gina|sitio)( web)? de chats?\\b").containsMatchIn(asked) &&
            !Regex("(?i)\\b(ia|inteligencia|bot|asistente|gpt|modelo)\\b").containsMatchIn(asked)) set += Module.CHAT
        // 👥 Red social: publica fotos (cámara) y avisa de me gusta, comentarios y seguidores; «con chat» suma la mensajería
        if (Module.SOCIAL in set) {
            set += Module.CAMERA
            if (Regex("(?i)\\b(chats?|mensajer[ií]a|mensajes)\\b").containsMatchIn(asked)) set += Module.CHAT
        }
        if (Module.REMIND in set || Module.BUBBLE in set || Module.CHAT in set || Module.SOCIAL in set) set += Module.NOTIFY
        return set
    }

    // ------------------------------------------------------------------ colores pedidos
    private val colorWords = listOf(
        Regex("(?i)\\b(azul(es|ado|ados)?|blue)\\b") to "#2962FF",
        Regex("(?i)\\b(celestes?|cian|cyan|calipso)\\b") to "#00B8D4",
        Regex("(?i)\\b(turquesas?|aguamarina|teal)\\b") to "#00BFA5",
        Regex("(?i)\\b(verdes?|green)\\b") to "#00C853",
        Regex("(?i)\\b(roj[oa]s?|red(?!\\s+social)|carmes[ií])\\b") to "#E53935",   // «red social» no es un color
        Regex("(?i)\\b(rosad[oa]s?|rosas?|fucsias?|pink)\\b") to "#EC407A",
        Regex("(?i)\\b(morad[oa]s?|violetas?|lilas?|p[uú]rpuras?|purple)\\b") to "#7C4DFF",
        Regex("(?i)\\b(naranjas?|anaranjad[oa]s?|orange)\\b") to "#FF6D00",
        Regex("(?i)\\b(amarill[oa]s?|dorad[oa]s?|yellow|gold)\\b") to "#FFC400",
        Regex("(?i)\\b(gris(es)?|gray|grey|plateado)\\b") to "#78909C",
        Regex("(?i)\\b(caf[eé]s?|marr[oó]n(es)?|brown)\\b") to "#8D6E63"
    )
    private val hexRx = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b")

    /** Colores que se pueden nombrar para el fondo, las ventanas laterales o los íconos (además de los de [colorWords]). */
    private val extraColorWords = listOf(
        Regex("(?i)\\b(naranj[oa]s?)\\b") to "#FF6D00",
        Regex("(?i)\\b(magenta|fucsia)\\b") to "#FF16E6",
        Regex("(?i)\\b(blanc[oa]s?|white)\\b") to "#FFFFFF",
        Regex("(?i)\\b(negr[oa]s?|black)\\b") to "#000000"
    )

    /** Lo que pide un pedido (o un cambio) para cada parte; null = no lo nombró. */
    private data class ColorAsk(val bg: String?, val panel: String?, val icons: String?, val bar: String?,
                                val primary: String?, val lightMode: Boolean)

    private fun askColors(prompt: String): ColorAsk {
        val p = prompt.lowercase(Locale.ROOT)
        val anyColors = colorWords + extraColorWords
        val used = mutableListOf<IntRange>()
        /** El primer color que aparece justo después de [rx] (hasta 60 letras más allá, sin cruzar un punto). */
        fun colorAfter(rx: Regex): String? {
            val m = rx.find(p) ?: return null
            val from = m.range.last + 1
            val tail = p.substring(from).take(60).substringBefore('.').substringBefore(';')
            val hit = anyColors.mapNotNull { (crx, hex) -> crx.find(tail)?.let { it to hex } }.minByOrNull { it.first.range.first }
                ?: hexRx.find(tail)?.let { h -> h to normHex(h.groupValues[1]) }
                ?: return null
            used += (m.range.first..(from + hit.first.range.last))
            return hit.second
        }
        // La barra va primero: «la barra del chat negra con fondo naranjo» no confunde una con otra
        val bar = colorAfter(Regex("\\b(barra (de|del|para) (chat|escribir|escritura|mensajes?|texto)|cuadro (de|para) (texto|escribir|escritura)|caja de texto|campo de texto)\\b"))
        val bg = colorAfter(Regex("\\b(fondos?|background)\\b"))
        val panel = colorAfter(Regex("\\b((ventanas|paneles|men[uú]s|barras|pesta[ñn]as) laterales|laterales)\\b"))
        val icons = colorAfter(Regex("\\b(tres ra[yl]las|tuercas?|engranajes?|[ií]conos?)\\b"))
        fun free(r: IntRange) = used.none { u -> r.first in u }
        // El color principal: el primero que nombras que no sea del fondo, la barra, las ventanas o los íconos
        val named = (colorWords + extraColorWords.take(2)).mapNotNull { (rx, hex) -> rx.findAll(p).firstOrNull { free(it.range) }?.let { it.range.first to hex } }
        val hex = hexRx.findAll(prompt).firstOrNull { free(it.range) }?.let { m -> m.range.first to normHex(m.groupValues[1]) }
        val primary = (named + listOfNotNull(hex)).minByOrNull { it.first }?.second
        val lightMode = Regex("(tema|modo) claro|\\bclar[oa]\\b.*\\bfondo\\b").containsMatchIn(p)
        return ColorAsk(bg, panel, icons, bar, primary, lightMode)
    }

    /**
     * 🎨 Colores de la app según el pedido. De inicio, SIEMPRE el chat oscuro de siempre (como Cumulus).
     * Lo que nombres cambia solo su parte:
     *  • «fondo naranjo» / «fondo del chat gris» → el FONDO (las letras se ponen claras u oscuras para que se lean)
     *  • «barra del chat negra» / «cuadro de texto blanco» → la BARRA DE ESCRITURA. Si no la nombras y cambiaste
     *    el fondo, la barra sale de un color CONTRARIO al fondo (oscura en fondos claros, blanca en fondos oscuros)
     *  • «ventanas laterales azules» / «paneles laterales…» → los paneles ☰ y ⚙️
     *  • «tres rayas y tuerca blancas» / «íconos rojos» → los íconos ☰ y ⚙️
     *  • cualquier otro color que nombres («botones verdes», «una app morada») → el color PRINCIPAL
     *    (tus mensajes, el botón ➤ y las letras de las píldoras). También sirve un código exacto como #FF5722.
     * Los cambios que vas pidiendo se suman, una línea cada uno: lo último que pides para cada parte es lo que vale
     * («cambia la barra a negra» cambia SOLO la barra; el fondo y lo demás quedan como estaban).
     */
    fun palette(prompt: String): Palette {
        var bg: String? = null; var panel: String? = null; var icons: String? = null; var bar: String? = null
        var chosen: String? = null; var lightMode = false
        for (line in prompt.split('\n')) {
            val a = askColors(line)
            a.bg?.let { bg = it }; a.panel?.let { panel = it }; a.icons?.let { icons = it }; a.bar?.let { bar = it }
            a.primary?.let { chosen = it }
            if (a.lightMode) lightMode = true
            if (a.bg != null) lightMode = false   // un fondo nuevo manda sobre «modo claro»
        }
        val bgC = bg
        val base: Palette = if (bgC != null) {
            // 🎨 Fondo que pediste: las demás capas salen de ese color y el texto se elige para que se lea
            val light = luminance(bgC) > 0.6
            val toward = if (light) "#000000" else "#FFFFFF"
            val primary = chosen ?: if (light) "#3D5AFE" else mix(bgC, "#FFFFFF", 0.35)
            Palette(primary, mix(primary, "#000000", 0.3), mix(primary, "#FFFFFF", 0.35), if (luminance(primary) > 0.6) "#1A1A1A" else "#FFFFFF",
                bgC, mix(bgC, toward, 0.07), mix(bgC, toward, 0.14),
                if (light) "#14161C" else "#F2F4F8", if (light) "#4A5160" else mix(bgC, "#FFFFFF", 0.65), mix(bgC, toward, 0.22), light)
        } else {
            val primary = chosen ?: "#3D5AFE"
            val onPrimary = if (luminance(primary) > 0.6) "#1A1A1A" else "#FFFFFF"
            if (lightMode) Palette(primary, mix(primary, "#000000", 0.25), mix(primary, "#FFFFFF", 0.35), onPrimary,
                "#F6F7FB", "#FFFFFF", "#EEF0F6", "#14161C", "#5F6675", "#E1E4EC", true)
            else Palette(primary, mix(primary, "#000000", 0.3), mix(primary, "#FFFFFF", 0.35), onPrimary,
                "#0F1115", "#171A21", "#222632", "#F2F4F8", "#9AA3B2", "#2A2F3A", false)
        }
        val panelColor = panel ?: base.surface
        // 💬 La barra de escritura NUNCA del mismo color que el fondo: la que pediste, o una contraria al fondo
        val barColor = bar ?: if (bgC == null) base.surface else if (luminance(bgC) > 0.5) "#1C1C1E" else "#FFFFFF"
        val barLight = luminance(barColor) > 0.6
        return base.copy(
            panel = panelColor,
            panelText = if (panel == null) base.textPrimary else if (luminance(panelColor) > 0.6) "#14161C" else "#F2F4F8",
            icons = icons ?: base.textPrimary,
            bar = barColor,
            barText = if (bar == null && bgC == null) base.textPrimary else if (barLight) "#14161C" else "#F2F4F8",
            barHint = if (bar == null && bgC == null) base.textSecondary else if (barLight) "#6B7280" else "#A1A1AA"
        )
    }

    private fun normHex(h0: String): String {
        val h = h0.uppercase(Locale.ROOT)
        return "#" + (if (h.length == 3) h.map { "$it$it" }.joinToString("") else h)
    }

    private fun rgb(hex: String): IntArray {
        val h = hex.removePrefix("#")
        return intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }

    private fun mix(a: String, b: String, t: Double): String {
        val x = rgb(a); val y = rgb(b)
        return "#" + (0..2).joinToString("") { i -> "%02X".format(Math.round(x[i] + (y[i] - x[i]) * t).toInt().coerceIn(0, 255)) }
    }

    private fun luminance(hex: String): Double {
        val c = rgb(hex).map { it / 255.0 }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    // ------------------------------------------------------------------ lo que la IA rellena
    /** Pedido corto para que la IA describa SU app (cabe aunque el contexto sea de 2048). */
    fun specAsk(prompt: String, web: Boolean): String =
        "El usuario pidió: «${prompt.take(500)}»\n\n" +
            "Vas a crear esa ${if (web) "app web" else "app Android"}. Ya tiene una base con panel lateral izquierdo (menú), " +
            "panel derecho (ajustes), barra superior, pantalla de inicio con tarjetas y atajos, y barra de escritura con micrófono y enviar.\n" +
            "Rellena el contenido PROPIO de esta app (todo en español, con un emoji al inicio de cada opción, nada genérico como «Botón 1»).\n" +
            "Responde SOLO con estas líneas, en este formato exacto:\n" +
            "TITULO: nombre corto de la app\n" +
            "SUBTITULO: frase corta de qué hace\n" +
            "EMOJI: un emoji que la represente\n" +
            "MENU: opción del menú izquierdo (escribe de 4 a 6 líneas MENU)\n" +
            "AJUSTE: switch | ajuste que se activa o desactiva (o: boton | acción)  (de 3 a 5 líneas AJUSTE)\n" +
            "BIENVENIDA: saludo de la pantalla de inicio\n" +
            "SUBBIENVENIDA: frase corta debajo del saludo\n" +
            "TARJETA: función principal | descripción corta  (de 2 a 4 líneas TARJETA)\n" +
            "ATAJO: acción rápida  (de 3 a 5 líneas ATAJO)\n" +
            "PISTA: texto de ayuda de la barra de escritura"

    private fun clean(s: String) = s.replace(Regex("[*`#]|^[-•]\\s*"), "").trim().trim('"', '«', '»').trim()

    private fun hasEmoji(s: String) = s.isNotEmpty() && !Character.isLetterOrDigit(s.codePointAt(0)) && s.codePointAt(0) > 0x2000

    fun parseSpec(text: String, prompt: String): AppSpec {
        val lines = text.lines()
        fun all(key: String): List<String> {
            val rx = Regex("^\\s*[-*•]*\\s*\\**($key)\\**\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
            return lines.mapNotNull { rx.find(it)?.groupValues?.get(2)?.let(::clean) }.filter { it.isNotBlank() }
        }
        val title = all("T[IÍ]TULO|NOMBRE").firstOrNull()?.take(28) ?: titleFromPrompt(prompt)
        val subtitle = all("SUBT[IÍ]TULO").firstOrNull()?.take(60) ?: "Tu app, simple y moderna"
        val emoji = all("EMOJI").firstOrNull()?.takeIf { hasEmoji(it) }?.let { String(Character.toChars(it.codePointAt(0))) } ?: "✨"
        var menu = all("MEN[UÚ]").map { it.take(32) }.distinct().take(7)
        if (menu.size < 3) menu = (menu + listOf("🏠 Inicio", "🕘 Historial", "⭐ Favoritos", "❓ Ayuda")).distinct().take(5)
        var settings = all("AJUSTE").map { s ->
            val parts = s.split("|").map { it.trim() }
            if (parts.size >= 2) (!parts[0].lowercase(Locale.ROOT).startsWith("bot")) to parts[1].take(34)
            else true to s.take(34)
        }.filter { it.second.isNotBlank() }.distinctBy { it.second }.take(6)
        if (settings.size < 2) settings = (settings + listOf(true to "🌙 Modo oscuro", true to "🔊 Respuestas en voz alta",
            false to "🗑️ Borrar historial")).distinctBy { it.second }.take(3)
        val welcome = all("BIENVENIDA").firstOrNull()?.take(70) ?: "¡Hola! ¿Qué quieres hacer hoy?"
        val welcomeSub = all("SUBBIENVENIDA").firstOrNull()?.take(90) ?: "Elige una opción o escríbeme abajo."
        var cards = all("TARJETA").map { s ->
            val parts = s.split("|").map { it.trim() }
            parts[0].take(32) to (parts.getOrNull(1) ?: "").take(70)
        }.filter { it.first.isNotBlank() }.distinctBy { it.first }.take(4)
        if (cards.size < 2) cards = (cards + listOf("✨ Empezar" to "Toca para comenzar", "💡 Ideas" to "Sugerencias para ti",
            "📚 Guía rápida" to "Aprende a usarla en un minuto")).distinctBy { it.first }.take(3)
        var chips = all("ATAJO").map { it.take(26) }.distinct().take(6)
        if (chips.size < 2) chips = (chips + listOf("💡 Dame ideas", "❓ ¿Qué puedes hacer?", "⚙️ Ayuda")).distinct().take(4)
        val hint = all("PISTA").firstOrNull()?.take(40) ?: "Escribe un mensaje..."
        return AppSpec(title, subtitle, emoji, menu, settings, welcome, welcomeSub, cards, chips, hint)
    }

    fun titleFromPrompt(prompt: String): String {
        val first = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val m = Regex("(?i)\\b(app|aplicaci[oó]n|p[aá]gina(?: web)?|sitio(?: web)?|juego)\\s+(?:de|para|del|sobre)\\s+(?:una?\\s+|la\\s+|el\\s+)?([^,.;\\n]+?)(?:\\s+(?:con|que|en|y|usando)\\b|[,.;]|$)")
            .find(first)?.groupValues?.get(2)?.trim()
        // Sin los colores ni el estilo que pidió («de recetas morada» → «Recetas»)
        var t = m ?: "Mi App"
        colorWords.forEach { (rx, _) -> t = t.replace(rx, "") }
        t = t.replace(hexRx, "").replace(Regex("(?i)\\b(modern[oa]s?|bonit[oa]s?|oscur[oa]s?|clar[oa]s?)\\b"), "")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { "Mi App" }
        return t.take(28).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Nombre de paquete válido a partir del título: «IA Local» → com.ejemplo.ialocal */
    fun packageFor(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = ascii.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").trimStart { it.isDigit() }.take(20)
        return "com.ejemplo." + slug.ifEmpty { "miapp" }
    }

    // ------------------------------------------------------------------ partes visuales de los módulos
    /** (interruptor?, texto, etiqueta) de los ajustes que agregan los módulos. */
    private fun moduleSettings(mods: Set<Module>): List<Triple<Boolean, String, String>> = buildList {
        if (Module.BUBBLE in mods) add(Triple(true, "🫧 Burbuja flotante", "module:bubble"))
        if (Module.NOTIFY in mods) add(Triple(true, "🔔 Notificaciones", "module:notify"))
        if (Module.STORE in mods) add(Triple(false, "🗑️ Borrar datos guardados", "module:clear"))
        if (Module.SOCIAL in mods) add(Triple(true, "👥 Solo personas que sigo", "module:following"))
        if (Module.CHAT in mods || Module.SOCIAL in mods) add(Triple(false, "🚪 Cerrar sesión", "module:logout"))
    }

    /** (título, descripción, etiqueta) de las tarjetas que agregan los módulos. */
    private fun moduleCards(mods: Set<Module>): List<Triple<String, String, String>> = buildList {
        if (Module.CAMERA in mods) add(Triple("📷 Foto", "Toma una foto o elige una de tu galería", "module:camera"))
        if (Module.REMIND in mods) add(Triple("⏰ Recordatorio", "Escribe el texto abajo y elige la hora", "module:remind"))
    }

    /** Los ajustes de la IA sin los que ya trae un módulo (no se repite «Notificaciones» dos veces). */
    private fun aiSettings(spec: AppSpec, mods: Set<Module>) = spec.settings.filterNot { (_, s) ->
        val l = s.lowercase(Locale.ROOT)
        (Module.NOTIFY in mods && l.contains("notific")) || (Module.BUBBLE in mods && l.contains("burbuja")) ||
            (Module.STORE in mods && l.contains("borrar")) || ((Module.CHAT in mods || Module.SOCIAL in mods) && l.contains("sesi")) ||
            (Module.SOCIAL in mods && (l.contains("que sigo") || l.contains("siguiendo")))
    }

    // ------------------------------------------------------------------ utilidades de texto
    private fun x(s: String): String {   // texto dentro de XML de Android
        var t = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "\\'")
        if (t.startsWith("@") || t.startsWith("?")) t = "\\" + t
        return t
    }
    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun noEmoji(s: String): String {
        var i = 0
        while (i < s.length && !Character.isLetterOrDigit(s.codePointAt(i)) && s[i] != '¿' && s[i] != '¡') i += Character.charCount(s.codePointAt(i))
        return s.substring(i).trim().ifEmpty { s.trim() }
    }
    private fun firstEmoji(s: String, fallback: String) = s.takeIf { hasEmoji(it) }?.let { String(Character.toChars(it.codePointAt(0))) } ?: fallback

    // ========================================================================
    // 📱 BASE ANDROID (Kotlin + XML, Material Design 3)
    // ========================================================================
    // ------------------------------------------------------------------ 🎨 ESTILOS DE DISEÑO (galería de maquetas)
    //    Todos usan las MISMAS piezas (menú ☰, ajustes ⚙️, barra de escribir, «Tus botones») y los mismos ids:
    //    solo cambia cómo se ven el saludo, las tarjetas y los atajos de la pantalla de inicio.
    data class StyleInfo(val name: String, val desc: String)

    val STYLES = listOf(
        StyleInfo("📋 Lista", "Tarjetas una debajo de otra"),
        StyleInfo("🔲 Cuadrícula", "Tarjetas en dos columnas"),
        StyleInfo("⭐ Destacado", "Una tarjeta grande de color arriba"),
        StyleInfo("🎠 Carrusel", "Tarjetas que se deslizan de lado"),
        StyleInfo("🧱 Mosaico", "Íconos grandes en tres columnas"),
        StyleInfo("➖ Minimal", "Filas limpias, sin cajas"),
        StyleInfo("🎨 Encabezado", "Saludo sobre una franja de color"),
        StyleInfo("🫧 Burbujas", "Tarjetas redondas y suaves"),
        StyleInfo("📌 Acento", "Tarjetas con una barra de color"),
        StyleInfo("🎯 Centrado", "Todo al centro, ícono arriba"),
        StyleInfo("🧩 Compacta", "Botones chicos en dos columnas"),
        StyleInfo("🖼️ Galería", "Tarjetas altas con ícono gigante")
    )

    fun styleIndex(i: Int) = i.coerceIn(0, STYLES.size - 1)

    /**
     * 💬 CHAT LIMPIO (como Cumulus): solo ☰ · nombre · ⚙️ arriba y la barra de escritura con 🎤 y ➤ abajo.
     * Sin saludo, sin tarjetas, sin atajos, menú y ajustes vacíos: el usuario le va agregando todo paso a paso
     * (los botones salen como píldoras, igual que en Cumulus). Es la base de toda app nueva.
     */
    const val CLEAN_STYLE = 100

    /** Tarjeta en fila: ícono · título y descripción · › (la de siempre, con variantes). */
    private fun rowCardXml(t: String, d: String, tag: String, emo: String, radius: Int = 20, bg: String = "@color/surface",
                           stroke: Boolean = true, elevation: Int = 2, minimal: Boolean = false, accent: Boolean = false,
                           top: Int = 12, titleColor: String = "@color/text_primary", descColor: String = "@color/text_secondary"): String {
        val icon = if (minimal) """
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="${x(emo)}"
            android:textSize="22sp" />""" else """
        <TextView
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:background="@drawable/bg_icon_soft"
            android:gravity="center"
            android:text="${x(emo)}"
            android:textSize="24sp" />"""
        val bar = if (accent) """
        <View
            android:layout_width="6dp"
            android:layout_height="match_parent"
            android:layout_marginEnd="12dp"
            android:background="@color/primary" />""" else ""
        return """<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="${top}dp"
    android:clickable="true"
    android:focusable="true"
    android:tag="${x(tag)}"
    app:cardBackgroundColor="$bg"
    app:cardCornerRadius="${radius}dp"
    app:cardElevation="${elevation}dp"
    app:strokeColor="@color/divider"
    app:strokeWidth="${if (stroke) 1 else 0}dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:padding="${if (accent) "0dp" else if (minimal) "12dp" else "16dp"}">$bar$icon

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="14dp"
            android:layout_weight="1"
            android:orientation="vertical"${if (accent) "\n            android:paddingVertical=\"16dp\"" else ""}>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="${x(noEmoji(t))}"
                android:textColor="$titleColor"
                android:textSize="16sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="${x(d)}"
                android:textColor="$descColor"
                android:textSize="13sp" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="${if (accent) "16dp" else "0dp"}"
            android:text="›"
            android:textColor="@color/primary"
            android:textSize="24sp" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>"""
    }

    /** Tarjeta en bloque (para cuadrícula, mosaico, carrusel, galería): ícono arriba y el texto debajo. */
    private fun tileCardXml(t: String, d: String, tag: String, emo: String, width: String = "0dp", weight: Boolean = true,
                            emojiSize: Int = 30, showDesc: Boolean = true, minHeight: Int = 0, radius: Int = 20,
                            bg: String = "@color/surface", center: Boolean = false, titleSize: Int = 15,
                            titleColor: String = "@color/text_primary", descColor: String = "@color/text_secondary", stroke: Boolean = true): String {
        val g = if (center) "center" else "start"
        val desc = if (showDesc) """

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:text="${x(d)}"
            android:textColor="$descColor"
            android:textSize="12sp" />""" else ""
        return """<com.google.android.material.card.MaterialCardView
    android:layout_width="$width"
    android:layout_height="wrap_content"${if (weight) "\n    android:layout_weight=\"1\"" else ""}
    android:layout_margin="6dp"
    android:clickable="true"
    android:focusable="true"
    android:tag="${x(tag)}"
    app:cardBackgroundColor="$bg"
    app:cardCornerRadius="${radius}dp"
    app:cardElevation="2dp"
    app:strokeColor="@color/divider"
    app:strokeWidth="${if (stroke) 1 else 0}dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"${if (minHeight > 0) "\n        android:minHeight=\"${minHeight}dp\"" else ""}
        android:gravity="$g"
        android:orientation="vertical"
        android:padding="14dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="${x(emo)}"
            android:textSize="${emojiSize}sp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:gravity="$g"
            android:text="${x(noEmoji(t))}"
            android:textColor="$titleColor"
            android:textSize="${titleSize}sp"
            android:textStyle="bold" />$desc
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>"""
    }

    /** Filas de [cols] tarjetas (la última fila se completa con espacios para que no se estiren). */
    private fun gridXml(tiles: List<String>, cols: Int): String = tiles.chunked(cols).joinToString("\n") { row ->
        val fill = List(cols - row.size) { "<View\n        android:layout_width=\"0dp\"\n        android:layout_height=\"1dp\"\n        android:layout_margin=\"6dp\"\n        android:layout_weight=\"1\" />" }
        "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:layout_marginTop=\"4dp\"\n" +
            "    android:orientation=\"horizontal\">\n" + (row + fill).joinToString("\n\n") { it.prependIndent("    ") } + "\n</LinearLayout>"
    }

    /** Las tarjetas de inicio según el estilo elegido (ya con su sangría para ir dentro de feature_cards). */
    private fun styleCardsXml(style: Int, allCards: List<Triple<String, String, String>>, spec: AppSpec): String {
        fun emo(t: String) = firstEmoji(t, spec.emoji)
        val xml = when (styleIndex(style)) {
            1 -> gridXml(allCards.map { (t, d, tag) -> tileCardXml(t, d, tag, emo(t)) }, 2)
            2 -> {
                val first = allCards.firstOrNull()
                val hero = first?.let { (t, d, tag) ->
                    tileCardXml(t, d, tag, emo(t), width = "match_parent", weight = false, emojiSize = 40, minHeight = 150, radius = 26,
                        bg = "@color/primary", titleSize = 20, titleColor = "@color/on_primary", descColor = "@color/on_primary", stroke = false)
                }.orEmpty()
                hero + "\n" + allCards.drop(1).joinToString("\n") { (t, d, tag) -> rowCardXml(t, d, tag, emo(t)) }
            }
            3 -> "<HorizontalScrollView\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n" +
                "    android:layout_marginTop=\"8dp\"\n    android:scrollbars=\"none\">\n\n    <LinearLayout\n        android:layout_width=\"wrap_content\"\n" +
                "        android:layout_height=\"wrap_content\"\n        android:orientation=\"horizontal\">\n" +
                allCards.joinToString("\n\n") { (t, d, tag) ->
                    tileCardXml(t, d, tag, emo(t), width = "220dp", weight = false, emojiSize = 34, minHeight = 140).prependIndent("        ")
                } + "\n    </LinearLayout>\n</HorizontalScrollView>"
            4 -> gridXml(allCards.map { (t, d, tag) -> tileCardXml(t, d, tag, emo(t), emojiSize = 32, showDesc = false, center = true, titleSize = 13, radius = 18) }, 3)
            5 -> allCards.joinToString("\n") { (t, d, tag) ->
                rowCardXml(t, d, tag, emo(t), radius = 0, bg = "@android:color/transparent", stroke = false, elevation = 0, minimal = true, top = 2) +
                    "\n<View\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"1dp\"\n    android:background=\"@color/divider\" />"
            }
            7 -> allCards.joinToString("\n") { (t, d, tag) -> rowCardXml(t, d, tag, emo(t), radius = 32, bg = "@color/surface_variant", stroke = false, elevation = 0) }
            8 -> allCards.joinToString("\n") { (t, d, tag) -> rowCardXml(t, d, tag, emo(t), radius = 12, accent = true) }
            9 -> allCards.joinToString("\n") { (t, d, tag) ->
                tileCardXml(t, d, tag, emo(t), width = "match_parent", weight = false, emojiSize = 34, center = true, radius = 24)
            }
            10 -> gridXml(allCards.map { (t, d, tag) -> tileCardXml(t, d, tag, emo(t), emojiSize = 20, showDesc = false, titleSize = 13, radius = 12) }, 2)
            11 -> gridXml(allCards.map { (t, d, tag) -> tileCardXml(t, d, tag, emo(t), emojiSize = 56, minHeight = 170, center = true, radius = 24) }, 2)
            else -> allCards.joinToString("\n") { (t, d, tag) -> rowCardXml(t, d, tag, emo(t)) }   // 0 Lista y 6 Encabezado
        }
        return xml.prependIndent("                    ")
    }

    /** Fondo de los atajos según el estilo. */
    private fun styleChipBg(style: Int) = when (styleIndex(style)) {
        4, 10 -> "@drawable/bg_chip_square"
        5, 7, 11 -> "@drawable/bg_chip_soft"
        else -> "@drawable/bg_chip"
    }

    /** El saludo de inicio según el estilo (6 = sobre una franja de color · 9 = centrado). Mismos ids de siempre. */
    private fun styleWelcomeXml(style: Int, defaultXml: String): String = when (styleIndex(style)) {
        6 -> """                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="8dp"
                        android:background="@drawable/bg_drawer_header"
                        android:orientation="vertical"
                        android:padding="22dp">

                        <TextView
                            android:id="@+id/tv_welcome"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/welcome"
                            android:textColor="@color/on_primary"
                            android:textSize="26sp"
                            android:textStyle="bold" />

                        <TextView
                            android:id="@+id/tv_welcome_sub"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:alpha="0.9"
                            android:text="@string/welcome_sub"
                            android:textColor="@color/on_primary"
                            android:textSize="15sp" />
                    </LinearLayout>"""
        9 -> defaultXml.replace("android:id=\"@+id/tv_welcome\"\n", "android:id=\"@+id/tv_welcome\"\n                        android:layout_gravity=\"center_horizontal\"\n                        android:gravity=\"center\"\n")
            .replace("android:id=\"@+id/tv_welcome_sub\"\n", "android:id=\"@+id/tv_welcome_sub\"\n                        android:layout_gravity=\"center_horizontal\"\n                        android:gravity=\"center\"\n")
        else -> defaultXml
    }

    /** CSS de cada estilo para las apps web (va al final de style.css; el <div class="app"> lleva «estilo-N»). */
    private fun styleCss(): String = """

/* 🎨 Estilos de diseño (se elige uno en la galería de maquetas) */
.estilo-1 .cards, .estilo-10 .cards, .estilo-11 .cards { grid-template-columns: 1fr 1fr; }
.estilo-1 .card, .estilo-10 .card, .estilo-11 .card, .estilo-4 .card, .estilo-3 .card, .estilo-9 .card { flex-direction: column; align-items: flex-start; }
.estilo-1 .card-arrow, .estilo-3 .card-arrow, .estilo-4 .card-arrow, .estilo-9 .card-arrow, .estilo-10 .card-arrow, .estilo-11 .card-arrow { display: none; }
.estilo-2 .card:first-child { background: var(--primary); color: var(--on-primary); flex-direction: column; align-items: flex-start; min-height: 150px; border: none; border-radius: 26px; }
.estilo-2 .card:first-child .card-icon { background: transparent; font-size: 2.4rem; }
.estilo-2 .card:first-child small, .estilo-2 .card:first-child .card-arrow { color: var(--on-primary); }
.estilo-3 .cards { display: flex; overflow-x: auto; gap: 12px; padding-bottom: 6px; scrollbar-width: none; }
.estilo-3 .card { min-width: 220px; min-height: 140px; }
.estilo-4 .cards { grid-template-columns: repeat(3, 1fr); }
.estilo-4 .card, .estilo-9 .card, .estilo-11 .card { align-items: center; text-align: center; }
.estilo-4 .card-text small, .estilo-10 .card-text small { display: none; }
.estilo-4 .card { padding: 14px 8px; border-radius: 18px; }
.estilo-4 .chip, .estilo-10 .chip { border-radius: 8px; }
.estilo-5 .cards { gap: 0; }
.estilo-5 .card { background: transparent; border: none; border-bottom: 1px solid var(--line); border-radius: 0; box-shadow: none; padding: 14px 4px; }
.estilo-5 .card-icon { background: transparent; width: auto; }
.estilo-5 .chip, .estilo-7 .chip, .estilo-11 .chip { border: none; background: var(--surface-2); }
.estilo-6 #welcomeTitle, .estilo-6 #welcomeSub { color: var(--on-primary); background: linear-gradient(135deg, var(--primary), var(--primary-dark)); }
.estilo-6 #welcomeTitle { margin: 12px 0 0; padding: 22px 22px 6px; border-radius: 22px 22px 0 0; }
.estilo-6 #welcomeSub { margin: 0 0 14px; padding: 0 22px 22px; border-radius: 0 0 22px 22px; }
.estilo-7 .card { background: var(--surface-2); border: none; border-radius: 32px; box-shadow: none; }
.estilo-8 .card { border-left: 6px solid var(--primary); border-radius: 12px; }
.estilo-9 #welcomeTitle, .estilo-9 #welcomeSub { text-align: center; }
.estilo-9 .chips { justify-content: center; }
.estilo-10 .card { padding: 12px; border-radius: 12px; }
.estilo-10 .card-icon { width: 36px; height: 36px; font-size: 1.1rem; }
.estilo-11 .card { min-height: 170px; justify-content: center; border-radius: 24px; }
.estilo-11 .card-icon { width: 72px; height: 72px; font-size: 3rem; background: transparent; }
"""

    fun androidShell(spec: AppSpec, pal: Palette, pkg: String, cpp: Boolean, mods: Set<Module> = emptySet(), style: Int = 0): List<Pair<String, String>> {
        val clean = style == CLEAN_STYLE   // 💬 chat limpio: nada de saludo, tarjetas, atajos ni botones de fábrica
        val out = mutableListOf<Pair<String, String>>()
        val res = "app/src/main/res"
        val javaDir = "app/src/main/java/${pkg.replace('.', '/')}"
        val bubble = Module.BUBBLE in mods; val notify = Module.NOTIFY in mods; val camera = Module.CAMERA in mods
        val store = Module.STORE in mods; val remind = Module.REMIND in mods; val net = Module.NET in mods
        val chat = Module.CHAT in mods; val social = Module.SOCIAL in mods
        val account = chat || social   // cuentas con Firebase (mensajería y/o red social)
        out += "$res/values/colors.xml" to """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 🎨 Paleta de la app -->
    <color name="primary">${pal.primary}</color>
    <!-- Ventanas laterales ☰ ⚙️ y sus letras · color de los íconos ☰ ⚙️ -->
    <color name="panel">${pal.panel.ifEmpty { pal.surface }}</color>
    <color name="panel_text">${pal.panelText.ifEmpty { pal.textPrimary }}</color>
    <color name="icons">${pal.icons.ifEmpty { pal.textPrimary }}</color>
    <!-- Barra de escritura del chat: fondo, letras y pista/🎤 -->
    <color name="bar">${pal.bar.ifEmpty { pal.surface }}</color>
    <color name="bar_text">${pal.barText.ifEmpty { pal.textPrimary }}</color>
    <color name="bar_hint">${pal.barHint.ifEmpty { pal.textSecondary }}</color>
    <color name="primary_dark">${pal.primaryDark}</color>
    <color name="primary_light">${pal.primaryLight}</color>
    <color name="on_primary">${pal.onPrimary}</color>
    <color name="background">${pal.background}</color>
    <color name="surface">${pal.surface}</color>
    <color name="surface_variant">${pal.surfaceVariant}</color>
    <color name="text_primary">${pal.textPrimary}</color>
    <color name="text_secondary">${pal.textSecondary}</color>
    <color name="divider">${pal.divider}</color>
</resources>"""
        out += "$res/values/strings.xml" to """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${x(spec.title)}</string>
    <string name="app_subtitle">${x(spec.subtitle)}</string>
    <string name="welcome">${if (clean) "¡Hola! ¿En qué te puedo ayudar?" else x(spec.welcome)}</string>
    <string name="welcome_sub">${x(spec.welcomeSub)}</string>
    <string name="hint_message">${if (clean) "Pregúntame..." else x(spec.hint)}</string>
    <string name="settings_title">Ajustes</string>
    <string name="menu_title">Menú</string>
    <string name="shortcuts_title">Atajos</string>
    <string name="mic_unavailable">El reconocimiento de voz no está disponible en este teléfono.</string>
    <string name="cd_menu">Abrir menú</string>
    <string name="cd_settings">Abrir ajustes</string>
    <string name="cd_mic">Hablar</string>
    <string name="cd_send">Enviar</string>""" +
            (if (camera) """
    <string name="cd_camera">Foto</string>
    <string name="photo_take">📷 Tomar foto</string>
    <string name="photo_pick">🖼️ Elegir de la galería</string>""" else "") +
            (if (bubble) """
    <string name="bubble_emoji">${x(spec.emoji)}</string>
    <string name="bubble_permission">Para ver la burbuja activa «Mostrar sobre otras apps» y vuelve a la app.</string>""" else "") +
            (if (remind) """
    <string name="reminder_default">Recordatorio</string>""" else "") +
            (if (account) """
    <string name="auth_title">${if (social) "👥 Inicia sesión" else "💬 Inicia sesión"}</string>
    <string name="auth_sub">${if (social) "Usa tu correo para publicar, seguir a otras personas y ver lo que comparten." else "Usa tu correo para chatear con otras personas en tiempo real."}</string>
    <string name="auth_name">Tu nombre (para crear cuenta)</string>
    <string name="auth_email">Correo</string>
    <string name="auth_password">Contraseña (mínimo 6)</string>
    <string name="auth_login">Entrar</string>
    <string name="auth_register">Crear cuenta</string>
    <string name="auth_fill">Escribe tu correo y una contraseña de al menos 6 letras (y tu nombre para crear la cuenta).</string>""" else "") +
            (if (chat) """
    <string name="chats_title">💬 Chats</string>
    <string name="newchat">➕ Nuevo chat</string>
    <string name="newchat_title">Nuevo chat</string>
    <string name="newchat_hint">Correo de la otra persona</string>
    <string name="newchat_ok">Abrir chat</string>
    <string name="no_chats">Todavía no tienes chats: toca «Nuevo chat».</string>
    <string name="chat_subtitle">Chat · mensajes en tiempo real</string>""" else "") +
            (if (social) """
    <string name="post_hint">✍️ ¿Qué quieres compartir?</string>
    <string name="feed_title">📰 Publicaciones</string>
    <string name="feed_empty">Todavía no hay publicaciones: escribe abajo y toca ➤ para compartir la primera.</string>
    <string name="discover_title">✨ Descubre</string>
    <string name="pending_photo">📷 Foto lista para publicar · toca aquí para quitarla</string>
    <string name="social_title">👥 Comunidad</string>
    <string name="nav_home">🏠 Inicio</string>
    <string name="nav_profile">👤 Mi perfil</string>
    <string name="nav_edit">✏️ Editar perfil</string>
    <string name="nav_search">🔎 Buscar personas</string>
    <string name="nav_activity">🔔 Actividad</string>
    <string name="time_now">ahora</string>
    <string name="time_min">hace %d min</string>
    <string name="time_hours">hace %d h</string>
    <string name="time_days">hace %d d</string>
    <string name="cd_photo">Foto</string>
    <string name="post_delete">🗑️ Eliminar publicación</string>
    <string name="post_delete_q">¿Eliminar esta publicación?</string>
    <string name="view_profile">👤 Ver perfil</string>
    <string name="post_report">🚩 Reportar publicación</string>
    <string name="block_user">🚫 Bloquear a %s</string>
    <string name="block_q">¿Bloquear a %s? Ya no verás sus publicaciones ni sus comentarios.</string>
    <string name="reported">🚩 Gracias: la publicación se reportó y ya no la verás.</string>
    <string name="blocked_ok">🚫 Persona bloqueada</string>
    <string name="profile_subtitle">Perfil</string>
    <string name="followers_label">seguidores</string>
    <string name="following_label">siguiendo</string>
    <string name="follow">➕ Seguir</string>
    <string name="unfollow">✔️ Siguiendo</string>
    <string name="send_message">💬 Enviar mensaje</string>
    <string name="edit_profile">✏️ Editar perfil</string>
    <string name="name_hint">Tu nombre</string>
    <string name="bio_hint">Biografía (opcional)</string>
    <string name="save">Guardar</string>
    <string name="change_photo">📷 Foto</string>
    <string name="profile_saved">✅ Perfil guardado</string>
    <string name="comments_title">💬 Comentarios</string>
    <string name="comment_hint">Escribe un comentario...</string>
    <string name="no_comments">Todavía no hay comentarios: escribe el primero abajo.</string>
    <string name="activity_title">🔔 Actividad</string>
    <string name="activity_sub">Me gusta, comentarios y seguidores</string>
    <string name="no_activity">Todavía no tienes actividad.</string>
    <string name="act_like">le dio me gusta a tu publicación</string>
    <string name="act_comment">comentó tu publicación</string>
    <string name="act_follow">empezó a seguirte</string>
    <string name="search_title">🔎 Buscar personas</string>
    <string name="search_hint">Nombre o correo</string>
    <string name="search_ok">Buscar</string>
    <string name="search_none">No encontré a nadie con ese nombre o correo.</string>""" else "") + """
</resources>"""
        out += "$res/values/themes.xml" to """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.App" parent="Theme.Material3.${if (pal.light) "Light" else "Dark"}.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorOnPrimary">@color/on_primary</item>
        <item name="colorSecondary">@color/primary_light</item>
        <item name="android:colorBackground">@color/background</item>
        <item name="android:windowBackground">@color/background</item>
        <item name="android:statusBarColor">@color/background</item>
        <item name="android:navigationBarColor">@color/background</item>
        <item name="android:windowLightStatusBar">${pal.light}</item>
    </style>
</resources>"""
        // Fondos redondeados y degradados
        fun shape(name: String, body: String) { out += "$res/drawable/$name.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$body" }
        shape("bg_input", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="28dp" />
    <stroke android:width="1dp" android:color="@color/divider" />
</shape>""")
        shape("bg_chat_bar", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/bar" />
    <corners android:radius="28dp" />
    <stroke android:width="1dp" android:color="@color/divider" />
</shape>""")
        shape("bg_circle_primary", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/primary" />
</shape>""")
        shape("bg_chip", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="18dp" />
    <stroke android:width="1dp" android:color="@color/primary" />
</shape>""")
        shape("bg_chip_square", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="8dp" />
    <stroke android:width="1dp" android:color="@color/primary" />
</shape>""")
        shape("bg_chip_soft", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_variant" />
    <corners android:radius="18dp" />
</shape>""")
        shape("bg_icon_soft", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_variant" />
    <corners android:radius="14dp" />
</shape>""")
        shape("bg_bubble_user", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/primary" />
    <corners android:radius="18dp" />
</shape>""")
        shape("bg_bubble_ai", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_variant" />
    <corners android:radius="18dp" />
</shape>""")
        if (social) shape("bg_post", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="20dp" />
    <stroke android:width="1dp" android:color="@color/divider" />
</shape>""")
        shape("bg_drawer_header", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="315" android:startColor="@color/primary" android:endColor="@color/primary_dark" />
</shape>""")
        // Íconos (Material Icons)
        fun icon(name: String, path: String) {
            out += "$res/drawable/$name.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="$path" />
</vector>"""
        }
        icon("ic_menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z")
        icon("ic_tune", "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z")
        icon("ic_mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
        icon("ic_send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
        if (clean) {
            icon("ic_copy", "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z")
            icon("ic_share", "M14,9V5l7,7 -7,7v-4.1c-5,0 -8.5,1.6 -11,5.1 1,-5 4,-10 11,-11z")
            icon("ic_download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
        }
        if (camera) icon("ic_camera", "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z")
        if (notify) icon("ic_notification", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")

        // Tarjetas: las de la IA (envían su texto) + las de los módulos (hacen su acción)
        val allCards = spec.cards.map { (t, d) -> Triple(t, d, noEmoji(t)) } + moduleCards(mods)
        // 🎨 Las tarjetas según el estilo elegido en la galería (lista, cuadrícula, carrusel…)
        val cards = if (clean) "" else styleCardsXml(style, allCards, spec)
        val chips = if (clean) "" else spec.chips.joinToString("\n") { c ->
            """                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginEnd="8dp"
                            android:background="${styleChipBg(style)}"
                            android:paddingHorizontal="14dp"
                            android:paddingVertical="8dp"
                            android:text="${x(c)}"
                            android:textColor="@color/text_primary"
                            android:textSize="14sp" />"""
        }
        val menu = if (clean) "" else spec.menu.joinToString("\n") { m ->
            """            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="?attr/selectableItemBackground"
                android:paddingHorizontal="24dp"
                android:paddingVertical="14dp"
                android:text="${x(m)}"
                android:textColor="@color/text_primary"
                android:textSize="16sp" />"""
        }
        // Ajustes: los de la IA + los de los módulos (con etiqueta «module:…»)
        // (chat limpio: solo los de las funciones que pediste —notificaciones, recordatorios…—; el resto lo vas agregando)
        val allSettings = (if (clean) emptyList() else aiSettings(spec, mods).map { (sw, s) -> Triple(sw, s, "") }) + moduleSettings(mods)
        val settings = allSettings.joinToString("\n") { (isSwitch, s, tag) ->
            val tagAttr = if (tag.isNotEmpty()) "\n                android:tag=\"$tag\"" else ""
            if (isSwitch) """            <com.google.android.material.materialswitch.MaterialSwitch
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingVertical="8dp"$tagAttr
                android:text="${x(s)}"
                android:textColor="@color/text_primary"
                android:textSize="15sp" />"""
            else """            <com.google.android.material.button.MaterialButton
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"$tagAttr
                android:text="${x(s)}"
                android:textAllCaps="false"
                android:textColor="@color/on_primary"
                app:backgroundTint="@color/primary"
                app:cornerRadius="16dp" />"""
        }
        val authSection = if (account) """

            <!-- 💬 Inicio de sesión (solo si no hay una cuenta abierta) -->
            <ScrollView
                android:id="@+id/auth_section"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@color/background"
                android:visibility="gone">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="24dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:text="@string/auth_title"
                        android:textColor="@color/text_primary"
                        android:textSize="24sp"
                        android:textStyle="bold" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:layout_marginBottom="18dp"
                        android:text="@string/auth_sub"
                        android:textColor="@color/text_secondary"
                        android:textSize="14sp" />

                    <EditText
                        android:id="@+id/et_name"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="10dp"
                        android:background="@drawable/bg_input"
                        android:hint="@string/auth_name"
                        android:inputType="textPersonName|textCapWords"
                        android:padding="14dp"
                        android:textColor="@color/text_primary"
                        android:textColorHint="@color/text_secondary" />

                    <EditText
                        android:id="@+id/et_email"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="10dp"
                        android:background="@drawable/bg_input"
                        android:hint="@string/auth_email"
                        android:inputType="textEmailAddress"
                        android:padding="14dp"
                        android:textColor="@color/text_primary"
                        android:textColorHint="@color/text_secondary" />

                    <EditText
                        android:id="@+id/et_password"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:background="@drawable/bg_input"
                        android:hint="@string/auth_password"
                        android:inputType="textPassword"
                        android:padding="14dp"
                        android:textColor="@color/text_primary"
                        android:textColorHint="@color/text_secondary" />

                    <TextView
                        android:id="@+id/tv_auth_error"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:textColor="#FF5252"
                        android:textSize="13sp"
                        android:visibility="gone" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_login"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:text="@string/auth_login"
                        android:textAllCaps="false"
                        android:textColor="@color/on_primary"
                        app:backgroundTint="@color/primary"
                        app:cornerRadius="16dp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_register"
                        style="@style/Widget.Material3.Button.TextButton"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/auth_register"
                        android:textAllCaps="false"
                        android:textColor="@color/primary" />
                </LinearLayout>
            </ScrollView>""" else ""
        val chatSection = if (chat) """

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:paddingHorizontal="24dp"
                    android:paddingTop="16dp"
                    android:paddingBottom="8dp"
                    android:text="@string/chats_title"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btn_new_chat"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginHorizontal="20dp"
                    android:text="@string/newchat"
                    android:textAllCaps="false"
                    android:textColor="@color/on_primary"
                    app:backgroundTint="@color/primary"
                    app:cornerRadius="16dp" />

                <TextView
                    android:id="@+id/tv_no_chats"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:paddingHorizontal="24dp"
                    android:paddingVertical="10dp"
                    android:text="@string/no_chats"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <LinearLayout
                    android:id="@+id/chat_list"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical" />""" else ""
        val cameraButton = if (camera) """

            <ImageButton
                android:id="@+id/btn_camera"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/cd_camera"
                android:src="@drawable/ic_camera"
                app:tint="@color/text_secondary" />""" else ""
        // 👥 Red social: perfil, publicaciones (inicio), foto pendiente y sección del menú
        val socialFeed = if (social) """

                    <LinearLayout
                        android:id="@+id/profile_header"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:background="@drawable/bg_post"
                        android:orientation="vertical"
                        android:padding="16dp"
                        android:visibility="gone" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:text="@string/feed_title"
                        android:textColor="@color/text_secondary"
                        android:textSize="13sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tv_feed_empty"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="@string/feed_empty"
                        android:textColor="@color/text_secondary"
                        android:textSize="14sp" />

                    <LinearLayout
                        android:id="@+id/feed_list"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="24dp"
                        android:text="@string/discover_title"
                        android:textColor="@color/text_secondary"
                        android:textSize="13sp"
                        android:textStyle="bold" />
""" else ""
        val pendingPhoto = if (social) """

        <!-- 📷 Foto que se publicará con el próximo texto -->
        <TextView
            android:id="@+id/tv_pending_photo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="12dp"
            android:background="@drawable/bg_icon_soft"
            android:padding="10dp"
            android:text="@string/pending_photo"
            android:textColor="@color/text_primary"
            android:textSize="13sp"
            android:visibility="gone" />""" else ""
        fun navItem(id: String, text: String) = """

                <TextView
                    android:id="@+id/$id"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:background="?attr/selectableItemBackground"
                    android:paddingHorizontal="24dp"
                    android:paddingVertical="14dp"
                    android:text="@string/$text"
                    android:textColor="@color/text_primary"
                    android:textSize="16sp" />"""
        val socialNav = if (social) """

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:paddingHorizontal="24dp"
                    android:paddingTop="16dp"
                    android:paddingBottom="4dp"
                    android:text="@string/social_title"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp"
                    android:textStyle="bold" />""" + navItem("btn_feed_home", "nav_home") + navItem("btn_my_profile", "nav_profile") +
            navItem("btn_edit_profile", "nav_edit") + navItem("btn_search_people", "nav_search") + navItem("btn_activity", "nav_activity") else ""
        val myButtonsInWelcome = """
                    <!-- 🔘 Botones que le vas agregando a la app (cada uno con su lógica en su archivo Boton….kt) -->
                    <TextView
                        android:id="@+id/my_buttons_title"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="20dp"
                        android:layout_marginBottom="8dp"
                        android:text="🔘 Tus botones"
                        android:textColor="@color/text_secondary"
                        android:textSize="13sp"
                        android:textStyle="bold"
                        android:visibility="gone" />

                    <LinearLayout
                        android:id="@+id/my_buttons"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical">
                        <!-- BOTONES -->
                    </LinearLayout>"""
        // 💬 Chat limpio: tus botones (píldoras) quedan a la vista justo encima de la barra de escritura
        val myButtonsBelow = """

        <!-- 🔘 Tus botones (píldoras): cada uno con su lógica en su archivo Boton….kt -->
        <TextView
            android:id="@+id/my_buttons_title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="🔘 Tus botones"
            android:visibility="gone" />

        <LinearLayout
            android:id="@+id/my_buttons"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingHorizontal="12dp">
                        <!-- BOTONES -->
        </LinearLayout>"""
        val inputBarXml = if (clean) """        <!-- Barra de escritura (como Cumulus): el texto arriba · ${if (camera) "📷 · " else ""}🎤 y ➤ abajo a la derecha -->
        <LinearLayout
            android:id="@+id/input_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_margin="12dp"
            android:background="@drawable/bg_chat_bar"
            android:orientation="vertical"
            android:paddingHorizontal="8dp"
            android:paddingTop="8dp"
            android:paddingBottom="8dp">

            <EditText
                android:id="@+id/input_message"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@null"
                android:hint="@string/hint_message"
                android:imeOptions="actionSend"
                android:inputType="textCapSentences|textMultiLine"
                android:maxLines="5"
                android:minHeight="48dp"
                android:paddingHorizontal="12dp"
                android:paddingVertical="8dp"
                android:textColor="@color/bar_text"
                android:textColorHint="@color/bar_hint"
                android:textSize="17sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical|end"
                android:orientation="horizontal">$cameraButton

                <ImageButton
                    android:id="@+id/btn_mic"
                    android:layout_width="44dp"
                    android:layout_height="44dp"
                    android:background="?attr/selectableItemBackgroundBorderless"
                    android:contentDescription="@string/cd_mic"
                    android:src="@drawable/ic_mic"
                    app:tint="@color/bar_hint" />

                <ImageButton
                    android:id="@+id/btn_send"
                    android:layout_width="52dp"
                    android:layout_height="52dp"
                    android:layout_marginStart="8dp"
                    android:background="@drawable/bg_circle_primary"
                    android:contentDescription="@string/cd_send"
                    android:src="@drawable/ic_send"
                    app:tint="@color/on_primary" />
            </LinearLayout>
        </LinearLayout>""" else """        <!-- Barra de escritura: texto · ${if (camera) "📷 foto · " else ""}🎤 micrófono · ➤ enviar -->
        <LinearLayout
            android:id="@+id/input_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_margin="12dp"
            android:background="@drawable/bg_input"
            android:gravity="center_vertical"
            android:minHeight="56dp"
            android:orientation="horizontal"
            android:padding="6dp">

            <EditText
                android:id="@+id/input_message"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:background="@null"
                android:hint="@string/hint_message"
                android:imeOptions="actionSend"
                android:inputType="textCapSentences|textMultiLine"
                android:maxLines="5"
                android:paddingHorizontal="14dp"
                android:paddingVertical="10dp"
                android:textColor="@color/text_primary"
                android:textColorHint="@color/text_secondary"
                android:textSize="16sp" />$cameraButton

            <ImageButton
                android:id="@+id/btn_mic"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/cd_mic"
                android:src="@drawable/ic_mic"
                app:tint="@color/text_secondary" />

            <ImageButton
                android:id="@+id/btn_send"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:layout_marginStart="6dp"
                android:background="@drawable/bg_circle_primary"
                android:contentDescription="@string/cd_send"
                android:src="@drawable/ic_send"
                app:tint="@color/on_primary" />
        </LinearLayout>"""
        // 🎨 Saludo de inicio según el estilo (mismos ids de siempre)
        val welcomeXml = if (clean) """                    <!-- 💬 Chat limpio: el saludo queda oculto (se usa solo si agregas la red social) -->
                    <TextView
                        android:id="@+id/tv_welcome"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/welcome"
                        android:textColor="@color/text_primary"
                        android:textSize="26sp"
                        android:textStyle="bold"
                        android:visibility="gone" />

                    <TextView
                        android:id="@+id/tv_welcome_sub"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/welcome_sub"
                        android:textColor="@color/text_secondary"
                        android:textSize="15sp"
                        android:visibility="gone" />""" else styleWelcomeXml(style, """                    <TextView
                        android:id="@+id/tv_welcome"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/welcome"
                        android:textColor="@color/text_primary"
                        android:textSize="26sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tv_welcome_sub"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:layout_marginBottom="8dp"
                        android:text="@string/welcome_sub"
                        android:textColor="@color/text_secondary"
                        android:textSize="15sp" />""")
        out += "$res/layout/activity_main.xml" to """<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/drawer_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background"
    tools:context=".MainActivity">

    <!-- ===== Pantalla principal ===== -->
    <LinearLayout
        android:id="@+id/main_content"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <!-- Barra superior: ☰ menú · título · ⚙️ ajustes -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="64dp"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="8dp">

            <ImageButton
                android:id="@+id/btn_menu"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/cd_menu"
                android:src="@drawable/ic_menu"
                app:tint="@color/icons" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical"
                android:paddingStart="8dp"${if (clean) "\n                android:visibility=\"invisible\"" else ""}>

                <TextView
                    android:id="@+id/tv_title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name"
                    android:textColor="@color/text_primary"
                    android:textSize="20sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/tv_subtitle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_subtitle"
                    android:textColor="@color/text_secondary"
                    android:textSize="12sp" />
            </LinearLayout>

            <ImageButton
                android:id="@+id/btn_settings"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/cd_settings"
                android:src="@drawable/ic_tune"
                app:tint="@color/icons" />
        </LinearLayout>

        <!-- Contenido: inicio (tarjetas y atajos) y luego la conversación -->
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <ScrollView
                android:id="@+id/welcome_section"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <LinearLayout
                    android:id="@+id/feature_cards"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="20dp">

$welcomeXml$socialFeed

$cards

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="24dp"
                        android:layout_marginBottom="10dp"
                        android:text="@string/shortcuts_title"${if (clean) "\n                        android:visibility=\"gone\"" else ""}
                        android:textColor="@color/text_secondary"
                        android:textSize="13sp"
                        android:textStyle="bold" />

                    <HorizontalScrollView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:scrollbars="none">

                        <LinearLayout
                            android:id="@+id/quick_actions"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal">

$chips
                        </LinearLayout>
                    </HorizontalScrollView>
${if (clean) "" else myButtonsInWelcome}
                </LinearLayout>
            </ScrollView>

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/list_messages"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:clipToPadding="false"
                android:padding="12dp"
                android:visibility="gone"
                tools:listitem="@layout/item_message" />$authSection
        </FrameLayout>$pendingPhoto${if (clean) myButtonsBelow else ""}

$inputBarXml
    </LinearLayout>

    <!-- ===== Panel lateral IZQUIERDO: menú ===== -->
    <LinearLayout
        android:id="@+id/left_drawer"
        android:layout_width="300dp"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        android:background="@color/panel"
        android:clickable="true"
        android:focusable="true"
        android:orientation="vertical">
${if (clean) """
        <!-- 💬 Chat limpio: el menú parte vacío, igual que los ajustes (solo su título); tú le vas agregando botones -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="20dp"
            android:paddingTop="20dp"
            android:paddingBottom="12dp"
            android:text="@string/menu_title"
            android:textColor="@color/panel_text"
            android:textSize="20sp"
            android:textStyle="bold" />
""" else """
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_drawer_header"
            android:orientation="vertical"
            android:padding="24dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="${x(spec.emoji)}"
                android:textSize="40sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="@string/app_name"
                android:textColor="@color/on_primary"
                android:textSize="20sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:alpha="0.85"
                android:text="@string/app_subtitle"
                android:textColor="@color/on_primary"
                android:textSize="13sp" />
        </LinearLayout>
"""}
        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <LinearLayout
                    android:id="@+id/left_menu"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:paddingVertical="8dp">

$menu
                    <!-- BOTONES_MENU -->
                </LinearLayout>$socialNav$chatSection
            </LinearLayout>
        </ScrollView>
    </LinearLayout>

    <!-- ===== Panel lateral DERECHO: ajustes ===== -->
    <LinearLayout
        android:id="@+id/right_drawer"
        android:layout_width="300dp"
        android:layout_height="match_parent"
        android:layout_gravity="end"
        android:background="@color/panel"
        android:clickable="true"
        android:focusable="true"
        android:orientation="vertical"
        android:padding="20dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginBottom="12dp"
            android:text="@string/settings_title"
            android:textColor="@color/panel_text"
            android:textSize="20sp"
            android:textStyle="bold" />

        <!-- 📜 Se desliza hacia abajo: con muchos botones, interruptores o barras se ve todo -->
        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:fillViewport="true">

        <LinearLayout
            android:id="@+id/right_settings"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

$settings
            <!-- BOTONES_AJUSTES -->
        </LinearLayout>
        </ScrollView>
    </LinearLayout>
</androidx.drawerlayout.widget.DrawerLayout>"""
        out += "$res/layout/item_message.xml" to """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingVertical="4dp">

    <ImageView
        android:id="@+id/message_image"
        android:layout_width="220dp"
        android:layout_height="220dp"
        android:layout_marginBottom="4dp"
        android:contentDescription="Foto"
        android:scaleType="centerCrop"
        android:visibility="gone" />

    <TextView
        android:id="@+id/message_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:maxWidth="300dp"
        android:background="@drawable/bg_bubble_ai"
        android:paddingHorizontal="14dp"
        android:paddingVertical="10dp"
        android:text="Mensaje"
        android:textColor="@color/text_primary"
        android:textIsSelectable="true"
        android:textSize="15sp" />${if (clean) """

    <!-- 📋 copiar · ↗️ compartir · ⬇️ guardar (solo debajo de las respuestas) -->
    <LinearLayout
        android:id="@+id/message_actions"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:orientation="horizontal"
        android:visibility="gone">

        <ImageButton
            android:id="@+id/msg_copy"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Copiar"
            android:src="@drawable/ic_copy"
            app:tint="@color/text_secondary" />

        <ImageButton
            android:id="@+id/msg_share"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginStart="10dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Compartir"
            android:src="@drawable/ic_share"
            app:tint="@color/text_secondary" />

        <ImageButton
            android:id="@+id/msg_save"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginStart="10dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Guardar"
            android:src="@drawable/ic_download"
            app:tint="@color/text_secondary" />
    </LinearLayout>""" else ""}
</LinearLayout>"""
        out += "$javaDir/MainActivity.kt" to mainActivityKt(pkg, mods, clean)
        out += "$javaDir/AppButtons.kt" to appButtonsKt(pkg, emptyList())
        if (bubble) out += "$javaDir/BubbleService.kt" to bubbleServiceKt(pkg)
        if (notify) out += "$javaDir/Notifier.kt" to notifierKt(pkg, bubble)
        if (store) out += "$javaDir/Store.kt" to storeKt(pkg)
        if (remind) out += "$javaDir/Reminders.kt" to remindersKt(pkg)
        if (net) out += "$javaDir/Net.kt" to netKt(pkg)
        if (account) out += "$javaDir/Account.kt" to accountKt(pkg)
        if (chat) out += "$javaDir/Messenger.kt" to messengerKt(pkg)
        if (social) out += "$javaDir/Social.kt" to socialKt(pkg)
        if (account) {
            out += "firestore.rules" to firestoreRules(chat, social)
            out += "LEEME_FIREBASE.md" to firebaseGuide(pkg, web = false, chat = chat, social = social)
        }
        val perms = buildList {
            add("android.permission.RECORD_AUDIO"); add("android.permission.INTERNET")
            if (notify) add("android.permission.POST_NOTIFICATIONS")
            if (bubble) add("android.permission.SYSTEM_ALERT_WINDOW")
            if (remind) add("android.permission.SCHEDULE_EXACT_ALARM")
        }.joinToString("\n") { "    <uses-permission android:name=\"$it\" />" }
        val components = buildString {
            if (bubble) append("\n\n        <!-- 🫧 Burbuja flotante -->\n        <service\n            android:name=\".BubbleService\"\n            android:exported=\"false\" />")
            if (remind) append("\n\n        <!-- ⏰ Recordatorios -->\n        <receiver\n            android:name=\".ReminderReceiver\"\n            android:exported=\"false\" />")
        }
        out += "app/src/main/AndroidManifest.xml" to """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

$perms

    <!-- Para usar el reconocimiento de voz del teléfono (Android 11+) -->
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.App">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>$components
    </application>
</manifest>"""
        out += "app/build.gradle" to """plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'${if (account) "\n    id 'com.google.gms.google-services'   // 🔥 Firebase (necesita app/google-services.json)" else ""}
}

android {
    namespace '$pkg'
    compileSdk 34

    defaultConfig {
        applicationId "$pkg"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0.0"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }${if (cpp) """
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }""" else ""}
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.activity:activity-ktx:1.9.1'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.drawerlayout:drawerlayout:1.2.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.4'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'${if (account) """

    // 🔥 Firebase: cuentas + base de datos en tiempo real (${listOfNotNull(if (chat) "mensajería" else null, if (social) "red social" else null).joinToString(" y ")})
    implementation platform('com.google.firebase:firebase-bom:33.5.1')
    implementation 'com.google.firebase:firebase-auth'
    implementation 'com.google.firebase:firebase-firestore'""" else ""}
}"""
        out += "settings.gradle" to """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "${spec.title.replace("\"", "")}"
include ':app'"""
        out += "build.gradle" to """plugins {
    id 'com.android.application' version '8.5.2' apply false
    id 'org.jetbrains.kotlin.android' version '2.0.21' apply false${if (account) "\n    id 'com.google.gms.google-services' version '4.4.2' apply false" else ""}
}"""
        // ⚙️ Ajustes de compilación (sin esto Android no acepta las librerías AndroidX y el APK no se arma)
        out += "gradle.properties" to """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official"""
        // 🐙 GitHub Actions: al subir el proyecto a GitHub, el APK se arma solo (pestaña «Actions» → «Artifacts»)
        out += ".github/workflows/compilar.yml" to """name: Compilar APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - name: Descargar el proyecto
        uses: actions/checkout@v4

      - name: Instalar Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Instalar Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'
${if (cpp) """
      - name: Descargar llama.cpp (motor de IA en C++)
        run: git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
""" else ""}
      - name: Compilar el APK
        run: gradle assembleDebug --stacktrace

      - name: Guardar el APK
        uses: actions/upload-artifact@v4
        with:
          name: mi-app-apk
          path: app/build/outputs/apk/debug/*.apk
"""
        if (cpp) out += "app/src/main/cpp/CMakeLists.txt" to """cmake_minimum_required(VERSION 3.22.1)
project("app")

# 🦙 llama.cpp: copia la carpeta del repositorio llama.cpp dentro de app/src/main/cpp/llama.cpp
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp)

add_library(app SHARED native-lib.cpp)
target_link_libraries(app llama common android log)"""
        return out
    }

    /**
     * 🔘 Lista de botones que el usuario le va agregando a la app («agrégale un botón que…»).
     * Cada botón vive en su propio archivo (object BotonAlgo : AppButton) y aquí se anota por su nombre.
     */
    /** 🪟 Una ventana que abre un botón: «menu» (menú emergente), «lista» (elegir una opción con ✓) o «pagina» (ventana nueva con ←). */
    data class WinSpec(val id: String, val kind: String, val title: String, val options: List<String>, val hasLogic: Boolean)

    private fun ktStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    fun appButtonsKt(pkg: String, names: List<String>, parts: List<String> = emptyList(), switches: List<String> = emptyList(),
                     windows: List<WinSpec> = emptyList()): String = """package $pkg

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** 🔘 Un botón de la app: su texto y lo que hace al tocarlo. */
interface AppButton {
    val label: String
    /** Corre en segundo plano. Para tocar la pantalla usa activity.runOnUiThread { }. Devuelve lo que verá el usuario (o null). */
    fun run(activity: AppCompatActivity): String?
}

/** 🧩 Una parte de la pantalla (barra, panel, varios botones juntos…): conecta sus botones con su lógica. */
interface AppPart {
    /** Se llama una vez al abrir la app. [root] es la zona donde está la parte. */
    fun bind(activity: AppCompatActivity, root: View)
}

/** 🌙 Un interruptor de la app (como «Modo oscuro»): su texto y lo que hace al encenderlo o apagarlo. */
interface AppSwitch {
    val label: String
    /** Corre en segundo plano. Para tocar la pantalla usa activity.runOnUiThread { }. Devuelve lo que verá el usuario (o null). */
    fun onChange(activity: AppCompatActivity, on: Boolean): String?
}

/** 🪟 Lo que hacen las opciones de un menú emergente o de una lista para elegir. */
interface AppWindow {
    /** Al tocar una opción del menú o al elegirla en la lista. Corre en segundo plano. Devuelve lo que verá el usuario (o null). */
    fun onOption(activity: AppCompatActivity, option: String): String?
    /** Al encender o apagar un interruptor del menú. */
    fun onSwitch(activity: AppCompatActivity, option: String, on: Boolean): String? = null
}

/** 🪟 Una ventana: su tipo («menu», «lista» o «pagina»), su título, sus opciones y su lógica. */
class AppWin(val kind: String, val title: String, val options: List<String>, val logic: AppWindow?)

/** ✏️ Lo escrito en los campos de texto de la app (queda guardado). Ej.: AppFields.get(activity, "campo_url") */
object AppFields {
    fun get(context: Context, id: String): String = context.getSharedPreferences("campos", Context.MODE_PRIVATE).getString(id, "") ?: ""
    fun set(context: Context, id: String, text: String) { context.getSharedPreferences("campos", Context.MODE_PRIVATE).edit().putString(id, text).apply() }
}

/** Botones, interruptores, ventanas y partes de la app (se actualiza sola cada vez que le pides algo nuevo). */
object AppButtons {
    private val ALL: Map<String, AppButton> = mapOf(${names.joinToString(", ") { "\"$it\" to $it" }})
    fun find(name: String?): AppButton? = ALL[name]
    private val SWITCHES: Map<String, AppSwitch> = mapOf(${switches.joinToString(", ") { "\"$it\" to $it" }})
    fun findSwitch(name: String?): AppSwitch? = SWITCHES[name]
    private val WINDOWS: Map<String, AppWin> = mapOf(${windows.joinToString(",\n        ", if (windows.isEmpty()) "" else "\n        ") { w ->
        "\"${w.id}\" to AppWin(\"${w.kind}\", ${ktStr(w.title)}, listOf(${w.options.joinToString(", ") { ktStr(it) }}), ${if (w.hasLogic) w.id else "null"})" }})
    fun findWindow(name: String?): AppWin? = WINDOWS[name]
    val parts: List<AppPart> = listOf(${parts.joinToString(", ")})
}
"""

    /** 🪟 Ventana nueva (como «Modelos GGUF» en Cumulus): ← para volver, su título y abajo lo que le vas agregando. */
    fun windowPageXml(id: String, title: String): String = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/panel"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:padding="16dp">

        <TextView
            android:id="@+id/win_back"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:background="@drawable/bg_input"
            android:gravity="center"
            android:tag="win_back"
            android:text="←"
            android:textColor="@color/panel_text"
            android:textSize="24sp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:text="${x(title)}"
            android:textAllCaps="true"
            android:textColor="@color/panel_text"
            android:textSize="20sp"
            android:textStyle="bold" />
    </LinearLayout>

    <!-- 📜 Se desliza hacia abajo si le agregas muchas cosas -->
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingHorizontal="16dp"
            android:paddingBottom="24dp">

            <!-- VENTANA_$id -->
        </LinearLayout>
    </ScrollView>
</LinearLayout>
"""

    /** 🪟 Ventana nueva en la web: tapa la pantalla, con ← para volver, su título y lo que le vas agregando. */
    fun windowPageHtml(id: String, title: String): String = """<section class="ventana-pagina" id="ventana-$id" hidden>
    <header class="ventana-barra"><button class="ventana-volver" aria-label="Volver">←</button><h2>${h(title)}</h2></header>
    <div class="ventana-contenido">
      <!-- VENTANA_$id -->
    </div>
  </section>"""

    private fun mainActivityKt(pkg: String, mods: Set<Module>, clean: Boolean = false): String {
        val bubble = Module.BUBBLE in mods; val notify = Module.NOTIFY in mods; val camera = Module.CAMERA in mods
        val store = Module.STORE in mods; val remind = Module.REMIND in mods; val chat = Module.CHAT in mods
        val social = Module.SOCIAL in mods; val account = chat || social
        val imports = buildList {
            addAll(listOf("android.Manifest", "android.content.Intent", "android.content.pm.PackageManager", "android.graphics.Bitmap",
                "android.os.Bundle", "android.speech.RecognitionListener", "android.speech.RecognizerIntent", "android.speech.SpeechRecognizer",
                "android.view.Gravity", "android.view.LayoutInflater", "android.view.View", "android.view.ViewGroup",
                "android.view.inputmethod.EditorInfo", "android.widget.EditText", "android.widget.ImageButton", "android.widget.ImageView",
                "android.widget.LinearLayout", "android.widget.TextView", "androidx.activity.addCallback", "androidx.appcompat.app.AppCompatActivity",
                "androidx.core.app.ActivityCompat", "androidx.core.content.ContextCompat", "androidx.core.view.GravityCompat",
                "androidx.drawerlayout.widget.DrawerLayout", "androidx.lifecycle.lifecycleScope", "androidx.recyclerview.widget.LinearLayoutManager",
                "androidx.recyclerview.widget.RecyclerView", "com.google.android.material.card.MaterialCardView",
                "com.google.android.material.materialswitch.MaterialSwitch", "kotlinx.coroutines.Dispatchers", "kotlinx.coroutines.launch",
                "kotlinx.coroutines.withContext", "androidx.core.widget.doAfterTextChanged"))
            if (camera) addAll(listOf("android.app.AlertDialog", "android.graphics.BitmapFactory", "android.net.Uri", "androidx.activity.result.contract.ActivityResultContracts"))
            if (bubble) addAll(listOf("android.net.Uri", "android.provider.Settings"))
            if (notify) add("android.os.Build")
            if (remind) addAll(listOf("android.app.TimePickerDialog", "java.util.Calendar"))
            if (account) addAll(listOf("android.app.AlertDialog", "android.text.InputType", "com.google.firebase.firestore.ListenerRegistration"))
            if (social) addAll(listOf("android.graphics.Typeface", "com.google.android.material.button.MaterialButton",
                "com.google.android.material.snackbar.Snackbar"))
        }.distinct().sorted().joinToString("\n") { "import $it" }

        val sb = StringBuilder()
        sb.append("package $pkg\n\n$imports\n\n")
        sb.append("""/**
 * Pantalla principal: menú (izquierda), ajustes (derecha), inicio con tarjetas y atajos,
 * conversación, y barra de escritura con micrófono y enviar.
 * Lo propio de esta app está en AppLogic.kt.
 */
class MainActivity : AppCompatActivity() {

    /** Un mensaje de la conversación (puede traer una foto). */
    data class Msg(val fromUser: Boolean, val text: String, val image: Bitmap? = null)

    private lateinit var drawer: DrawerLayout
    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var welcome: View
    private lateinit var btnMic: ImageButton
    private val messages = mutableListOf<Msg>()
    private val adapter = MessageAdapter(messages)
    private var recognizer: SpeechRecognizer? = null
    private var visible = false
""")
        if (chat) sb.append("""
    // 💬 Mensajería
    private var openChat: Messenger.Chat? = null
    private var chatsListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private val seenAt = HashMap<String, Long>()
    private var chatsLoaded = false
""")
        if (social) sb.append("""
    // 👥 Red social
    private var feedListener: ListenerRegistration? = null
    private var profileListener: ListenerRegistration? = null
    private var followListener: ListenerRegistration? = null
    private var meListener: ListenerRegistration? = null
    private var activityListener: ListenerRegistration? = null
    private var commentsListener: ListenerRegistration? = null
    private var feedPosts: List<Social.Post> = emptyList()
    private var profilePosts: List<Social.Post> = emptyList()
    private var following: Set<String> = emptySet()
    private var blocked: Set<String> = emptySet()
    private var activityItems: List<Social.Activity> = emptyList()
    private val activitySeen = HashSet<String>()
    private var activityLoaded = false
    private var profileUid: String? = null
    private var commentsOf: Social.Post? = null
    private var showingActivity = false
    private var pendingPhoto: Bitmap? = null
    private var pendingAvatar = false
""")
        if (camera) sb.append("""
    // 📷 Cámara y galería (sin permisos extra: lo hace la app de cámara del teléfono)
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) onPhoto(bmp)
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadBitmap(it) }?.let { onPhoto(it) }
    }
""")
        sb.append("""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer_layout)
        input = findViewById(R.id.input_message)
        list = findViewById(R.id.list_messages)
        welcome = findViewById(R.id.welcome_section)
        btnMic = findViewById(R.id.btn_mic)

        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
""" + (if (clean) """
        // 💬 Chat limpio: se abre directo en la conversación, con el saludo de la IA
        welcome.visibility = View.GONE
        list.visibility = View.VISIBLE
        if (messages.isEmpty()) {
            messages.add(Msg(false, getString(R.string.welcome)))
            adapter.notifyDataSetChanged()
        }
""" else "") + """
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { drawer.openDrawer(GravityCompat.END) }
        findViewById<ImageButton>(R.id.btn_send).setOnClickListener { send() }
        btnMic.setOnClickListener { listen() }
""")
        if (camera) sb.append("        findViewById<ImageButton>(R.id.btn_camera).setOnClickListener { choosePhoto() }\n")
        sb.append("""        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        // ☰ Menú lateral izquierdo
        val menu = findViewById<LinearLayout>(R.id.left_menu)
        for (i in 0 until menu.childCount) {
            val item = menu.getChildAt(i) as? TextView ?: continue
            val btn = AppButtons.find(item.tag?.toString())   // 🔘 un botón que le agregaste al menú
            item.setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                if (btn != null) runButton(btn) else runLogic { AppLogic.onMenu(this, item.text.toString()) }
            }
        }

        // ⚙️ Ajustes (panel derecho): los interruptores se guardan solos
        val prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        val settings = findViewById<LinearLayout>(R.id.right_settings)
        for (i in 0 until settings.childCount) {
            when (val v = settings.getChildAt(i)) {
                is MaterialSwitch -> {
                    val key = (v.tag as? String) ?: v.text.toString()
                    v.isChecked = prefs.getBoolean(key, false)
                    v.setOnCheckedChangeListener { _, on ->
                        prefs.edit().putBoolean(key, on).apply()
                        if (key.startsWith("module:")) onModuleSwitch(key, on)
                        else runLogic { AppLogic.onSetting(this, key, on) }
                    }
                }
                is TextView -> v.setOnClickListener {
                    drawer.closeDrawer(GravityCompat.END)
                    val key = v.tag as? String
                    val btn = AppButtons.find(key)   // 🔘 un botón que le agregaste a los ajustes
                    if (btn != null) runButton(btn)
                    else if (key != null && key.startsWith("module:")) onModuleAction(key)
                    else runLogic { AppLogic.onSetting(this, v.text.toString(), true) }
                }
            }
        }

        // Tarjetas y atajos de la pantalla de inicio
        // (según el diseño elegido pueden estar en filas, en un carrusel o en una cuadrícula: se buscan todas)
        val cardList = mutableListOf<MaterialCardView>()
        fun findCards(g: android.view.ViewGroup) {
            for (i in 0 until g.childCount) {
                val v = g.getChildAt(i)
                if (v.id == R.id.my_buttons) continue
                if (v is MaterialCardView && v.tag != null) cardList += v
                else if (v is android.view.ViewGroup) findCards(v)
            }
        }
        findCards(findViewById(R.id.feature_cards))
        for (card in cardList) {
            val tag = card.tag?.toString().orEmpty()
            card.setOnClickListener { if (tag.startsWith("module:")) onModuleAction(tag) else send(tag) }
        }
        val chips = findViewById<LinearLayout>(R.id.quick_actions)
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? TextView ?: continue
            chip.setOnClickListener { send(chip.text.toString()) }
        }

        // 🔘 Botones que le fuiste agregando: cada uno hace lo que dice su archivo Boton….kt
        val myButtons = findViewById<LinearLayout>(R.id.my_buttons)
        for (i in 0 until myButtons.childCount) {
            val b = myButtons.getChildAt(i) as? TextView ?: continue
            val action = AppButtons.find(b.tag?.toString())
            // El resultado sale en un aviso: así la pantalla de inicio con tus botones sigue a la vista
            b.setOnClickListener { runButton(action) }
        }
        // 🔘 Botones en grupo (un título y sus píldoras de a dos, como en Cumulus): van dentro de filas, se buscan adentro
        bindGroup(myButtons) { }
        bindGroup(menu) { drawer.closeDrawer(GravityCompat.START) }
        bindGroup(settings) { drawer.closeDrawer(GravityCompat.END) }
        // 🧩 Partes que le fuiste agregando (barras, paneles…): cada una conecta sus botones
        for (part in AppButtons.parts) {
            try { part.bind(this, myButtons) } catch (e: Exception) {
                android.widget.Toast.makeText(this, "⚠️ " + (e.message ?: "Error en una parte"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
""")
        if (store) sb.append("""
        // 💾 La conversación guardada vuelve a aparecer al abrir la app
        Store.all(this, "chat").forEach { line -> messages.add(Msg(line.startsWith("u:"), line.drop(2))) }
        if (messages.isNotEmpty()) {
            welcome.visibility = View.GONE
            list.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            list.scrollToPosition(messages.size - 1)
        }
""")
        if (account) sb.append("""
        // 👤 Cuentas (Firebase): ${listOfNotNull(if (chat) "chats" else null, if (social) "red social" else null).joinToString(" y ")}
        setupAccount()
""")
        sb.append("""
        onBackPressedDispatcher.addCallback(this) {
            when {
                drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                drawer.isDrawerOpen(GravityCompat.END) -> drawer.closeDrawer(GravityCompat.END)""" +
            (if (chat) "\n                openChat != null -> closeChat()" else "") +
            (if (social) "\n                commentsOf != null -> closeComments()" +
                "\n                Account.uid != null && (profileUid != null || showingActivity || list.visibility == View.VISIBLE) -> showHome()" else "") + """
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
""")
        if (bubble) sb.append("""        BubbleService.clearBadge()
        if (getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("module:bubble", false) && Settings.canDrawOverlays(this)) {
            BubbleService.start(this)
        }
""")
        sb.append("""    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    /**
     * 🔘 Conecta lo que le fuiste agregando dentro de [g] (también dentro de filas y grupos): botones, interruptores
     * (recuerdan si quedaron encendidos y al abrir la app se vuelven a aplicar), botones que abren ventanas y campos de texto.
     */
    private fun bindGroup(g: ViewGroup, close: () -> Unit) {
        val switchPrefs = getSharedPreferences("interruptores", MODE_PRIVATE)
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            val key = v.tag?.toString()
            val sw = AppButtons.findSwitch(key)
            val action = AppButtons.find(key)
            if (sw != null && v is android.widget.CompoundButton) {
                v.isChecked = switchPrefs.getBoolean(key, false)
                if (v.isChecked) lifecycleScope.launch(Dispatchers.Default) { runCatching { sw.onChange(this@MainActivity, true) } }
                v.setOnCheckedChangeListener { _, on ->
                    switchPrefs.edit().putBoolean(key, on).apply()
                    showResult { sw.onChange(this@MainActivity, on) }
                }
            }
            else if (action != null) v.setOnClickListener { close(); runButton(action) }
            else if (key != null && AppButtons.findWindow(key) != null) v.setOnClickListener { close(); openWindow(key) }
            else if (key != null && key.startsWith("campo_") && v is EditText) {
                // ✏️ Lo que escribes queda guardado (los botones lo leen con AppFields.get)
                v.setText(AppFields.get(this, key))
                v.doAfterTextChanged { AppFields.set(this, key, it?.toString().orEmpty()) }
            }
            else if (v is ViewGroup) bindGroup(v, close)
        }
    }

    /** Corre [block] en segundo plano y muestra lo que devuelve en un aviso. */
    private fun showResult(block: () -> String?) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) {
                try { block() } catch (e: Exception) { "⚠️ " + (e.message ?: "Error") }
            }
            if (!r.isNullOrBlank()) android.widget.Toast.makeText(this@MainActivity, r, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 🪟 Abre la ventana de un botón: un menú emergente (como el ➕ de Cumulus), una lista para elegir con ✓ (como sus modos)
     * o una ventana nueva con ← para volver (como «Modelos GGUF»).
     */
    private fun openWindow(id: String) {
        val w = AppButtons.findWindow(id) ?: return
        val prefs = getSharedPreferences("ventanas", MODE_PRIVATE)
        if (w.kind == "pagina") {
            val dialog = android.app.Dialog(this, R.style.Theme_App)
            val layoutId = resources.getIdentifier("ventana_" + id.lowercase(), "layout", packageName)
            if (layoutId == 0) return
            val view = layoutInflater.inflate(layoutId, null)
            view.findViewWithTag<View>("win_back")?.setOnClickListener { dialog.dismiss() }
            (view as? ViewGroup)?.let { bindGroup(it) { } }
            dialog.setContentView(view)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.show()
            return
        }
        // 📋 Menú emergente o lista para elegir: una tarjeta redondeada sobre la pantalla
        val dialog = android.app.Dialog(this)
        val ctx = dialog.context
        val dp = resources.displayMetrics.density
        val textColor = ContextCompat.getColor(this, R.color.panel_text)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt()) }
        val chosen = prefs.getString(id, null)
        for (opt in w.options) {
            when {
                opt == "---" -> box.addView(View(ctx).apply {
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()).apply { setMargins((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt()) }
                })
                opt.startsWith("[s]") -> {
                    val text = opt.removePrefix("[s]").trim()
                    box.addView(MaterialSwitch(ctx).apply {
                        this.text = text; textSize = 18f; setTextColor(textColor); minHeight = (56 * dp).toInt()
                        setPadding((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                        isChecked = prefs.getBoolean(id + ":" + text, false)
                        setOnCheckedChangeListener { _, on ->
                            prefs.edit().putBoolean(id + ":" + text, on).apply()
                            showResult { w.logic?.onSwitch(this@MainActivity, text, on) }
                        }
                    })
                }
                else -> box.addView(TextView(ctx).apply {
                    // En la lista, la opción elegida lleva ✓ (como los modos de Cumulus)
                    this.text = if (w.kind == "lista") (if (opt == chosen) "✓   " else "      ") + opt else opt
                    textSize = 18f; setTextColor(textColor)
                    setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                    setOnClickListener {
                        if (w.kind == "lista") prefs.edit().putString(id, opt).apply()
                        dialog.dismiss()
                        showResult { w.logic?.onOption(this@MainActivity, opt) }
                    }
                })
            }
        }
        dialog.setContentView(android.widget.ScrollView(ctx).apply { addView(box) })
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 28 * dp; setColor(ContextCompat.getColor(this@MainActivity, R.color.panel))
        })
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** Envía lo escrito (o [textIn]) y muestra la respuesta de AppLogic. */
    /** 🔘 Corre la lógica de un botón agregado (en la pantalla, en el menú ☰ o en los ajustes ⚙️) y avisa el resultado. */
    private fun runButton(action: AppButton?) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) {
                try { action?.run(this@MainActivity) ?: "Este botón todavía no tiene su lógica." }
                catch (e: Exception) { "⚠️ " + (e.message ?: "Error") }
            }
            if (!r.isNullOrBlank()) android.widget.Toast.makeText(this@MainActivity, r, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun send(textIn: String? = null) {
        val text = (textIn ?: input.text.toString()).trim()""" + (if (social) """
        if (text.isEmpty()) {
            if (textIn == null && pendingPhoto != null && feedVisible()) publish("")   // 👥 solo la foto
            return
        }""" else """
        if (text.isEmpty()) return""") + """
        input.text.clear()""" + (if (chat) """
        openChat?.let { Messenger.send(it.id, text); return }   // 💬 con un chat abierto el mensaje va a la otra persona""" else "") +
            (if (social) """
        commentsOf?.let { Social.comment(it, text); return }    // 👥 comentario de la publicación abierta
        if (textIn == null && feedVisible()) { publish(text); return }   // 👥 en el inicio la barra publica
        if (showingActivity) leaveActivity()""" else "") + """
        addMessage(true, text)
        runLogic { AppLogic.reply(this, text) }
    }

    /** Ejecuta la lógica en segundo plano (no congela la pantalla) y muestra su mensaje. */
    private fun runLogic(block: () -> String?) {
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.Default) {
                try { block() } catch (e: Exception) { "⚠️ " + (e.message ?: "Error") }
            }
            if (!reply.isNullOrBlank()) {
                addMessage(false, reply)
""")
        if (notify || bubble) {
            sb.append("                if (!visible) {   // la app está cerrada o en segundo plano: se avisa\n")
            if (notify) sb.append("                    Notifier.show(this@MainActivity, getString(R.string.app_name), reply)\n")
            else sb.append("                    BubbleService.addBadge()\n")
            sb.append("                }\n")
        }
        sb.append("""            }
        }
    }

    private fun addMessage(fromUser: Boolean, text: String, image: Bitmap? = null) {
        welcome.visibility = View.GONE
        list.visibility = View.VISIBLE
        messages.add(Msg(fromUser, text, image))
        adapter.notifyItemInserted(messages.size - 1)
        list.scrollToPosition(messages.size - 1)
""")
        if (store) sb.append("        if (image == null) Store.add(this, \"chat\", (if (fromUser) \"u:\" else \"a:\") + text, max = 300)\n")
        sb.append("""    }

    /** Interruptores de los módulos (burbuja, notificaciones). */
    private fun onModuleSwitch(key: String, on: Boolean) {
        when (key) {
""")
        if (bubble) sb.append("""            "module:bubble" -> if (!on) BubbleService.stop(this) else if (Settings.canDrawOverlays(this)) BubbleService.start(this) else {
                addMessage(false, getString(R.string.bubble_permission))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
""")
        if (notify) sb.append("            \"module:notify\" -> if (on) askNotificationPermission()\n")
        if (social) sb.append("            \"module:following\" -> renderFeed()\n")
        sb.append("""            else -> {}
        }
    }

    /** Botones y tarjetas de los módulos (foto, recordatorio, borrar datos). */
    private fun onModuleAction(key: String) {
        when (key) {
""")
        if (camera) sb.append("            \"module:camera\" -> choosePhoto()\n")
        if (remind) sb.append("            \"module:remind\" -> pickReminder()\n")
        if (account) sb.append("            \"module:logout\" -> signOutAll()\n")
        if (store) sb.append("""            "module:clear" -> {
                Store.clearAll(this)
                messages.clear()
                adapter.notifyDataSetChanged()
                list.visibility = View.GONE
                welcome.visibility = View.VISIBLE
            }
""")
        sb.append("""            else -> {}
        }
    }
""")
        if (notify) sb.append("""
    // 🔔 Permiso para mostrar notificaciones (Android 13 o más nuevo)
    private fun askNotificationPermission() {
        getSharedPreferences("ajustes", MODE_PRIVATE).edit().putBoolean("module:notify", true).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
    }
""")
        if (camera) sb.append("""
    // 📷 Tomar una foto o elegirla de la galería
    private fun choosePhoto() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.photo_take), getString(R.string.photo_pick))) { _, which ->
                if (which == 0) takePhoto.launch(null) else pickPhoto.launch("image/*")
            }
            .show()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    } catch (e: Exception) { null }

    private fun onPhoto(photo: Bitmap) {""" + (if (chat) """
        openChat?.let { Messenger.send(it.id, "📷", photo); return }   // 💬 la foto va al chat abierto""" else "") +
            (if (social) """
        if (pendingAvatar) {   // 👥 nueva foto de perfil
            pendingAvatar = false
            Social.setPhoto(photo) { err -> info(err ?: getString(R.string.profile_saved)); profileUid?.let { openProfile(it) } }
            return
        }
        if (feedVisible() || commentsOf != null || showingActivity) {   // 👥 se publica con el próximo texto
            if (!feedVisible()) showHome()
            setPending(photo)
            return
        }""" else "") + """
        addMessage(true, "📷", photo)
        runLogic { AppLogic.onPhoto(this, photo) }
    }
""")
        if (remind) sb.append("""
    // ⏰ Recordatorio: el texto se escribe en la barra y se elige la hora
    private fun pickReminder() {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            val at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            val text = input.text.toString().trim().ifEmpty { getString(R.string.reminder_default) }
            input.text.clear()
            askNotificationPermission()
            Reminders.schedule(this, at.timeInMillis, text)
            addMessage(false, "⏰ " + String.format("%02d:%02d", hour, minute) + " · " + text)
        }, now.get(Calendar.HOUR_OF_DAY), (now.get(Calendar.MINUTE) + 5) % 60, true).show()
    }
""")
        if (account) sb.append("""
    // ===================== 👤 CUENTA (Firebase) =====================
    private fun setupAccount() {
        val name = findViewById<EditText>(R.id.et_name)
        val email = findViewById<EditText>(R.id.et_email)
        val pass = findViewById<EditText>(R.id.et_password)
        val error = findViewById<TextView>(R.id.tv_auth_error)
        val result: (String?) -> Unit = { err ->
            if (err == null) onSignedIn() else { error.text = "⚠️ " + err; error.visibility = View.VISIBLE }
        }
        findViewById<View>(R.id.btn_login).setOnClickListener {
            if (email.text.isBlank() || pass.text.length < 6) result(getString(R.string.auth_fill))
            else Account.signIn(email.text.toString(), pass.text.toString(), result)
        }
        findViewById<View>(R.id.btn_register).setOnClickListener {
            if (name.text.isBlank() || email.text.isBlank() || pass.text.length < 6) result(getString(R.string.auth_fill))
            else Account.signUp(name.text.toString().trim(), email.text.toString(), pass.text.toString(), result)
        }
""")
        if (chat) sb.append("""        findViewById<View>(R.id.btn_new_chat).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            askNewChat()
        }
""")
        if (social) sb.append("""        // 👥 Menú de la comunidad
        findViewById<View>(R.id.btn_feed_home).setOnClickListener { drawer.closeDrawer(GravityCompat.START); goHome() }
        findViewById<View>(R.id.btn_my_profile).setOnClickListener { Account.uid?.let { openProfile(it) } }
        findViewById<View>(R.id.btn_edit_profile).setOnClickListener { drawer.closeDrawer(GravityCompat.START); editProfile() }
        findViewById<View>(R.id.btn_search_people).setOnClickListener { drawer.closeDrawer(GravityCompat.START); searchPeople() }
        findViewById<View>(R.id.btn_activity).setOnClickListener { drawer.closeDrawer(GravityCompat.START); showActivity() }
        findViewById<View>(R.id.tv_pending_photo).setOnClickListener { setPending(null) }
""")
        if (account) sb.append("""        if (Account.uid == null) showAuth() else onSignedIn()
    }

    private fun showAuth() {
        findViewById<View>(R.id.auth_section).visibility = View.VISIBLE
        findViewById<View>(R.id.input_bar).visibility = View.GONE   // sin cuenta no se escribe
        welcome.visibility = View.GONE
        list.visibility = View.GONE
    }

    private fun onSignedIn() {
        findViewById<View>(R.id.auth_section).visibility = View.GONE
        findViewById<View>(R.id.input_bar).visibility = View.VISIBLE
        findViewById<View>(R.id.tv_auth_error).visibility = View.GONE
        if (openChat == null && messages.isEmpty()) welcome.visibility = View.VISIBLE
""".replace("openChat == null && ", if (chat) "openChat == null && " else ""))
        if (chat) sb.append("""        chatsListener?.remove()
        chatsListener = Messenger.listenChats { chats -> renderChats(chats) }
""")
        if (social) sb.append("        startSocial()\n")
        if (account) sb.append("""    }

    /** 🚪 Cerrar sesión: se dejan de escuchar los datos y vuelve el inicio de sesión. */
    private fun signOutAll() {
        drawer.closeDrawer(GravityCompat.END)
        Account.signOut()
""")
        if (chat) sb.append("""        chatsListener?.remove(); chatsListener = null
        closeChat()
        findViewById<LinearLayout>(R.id.chat_list).removeAllViews()
        seenAt.clear(); chatsLoaded = false
""")
        if (social) sb.append("        stopSocial()\n")
        if (account) sb.append("""        showAuth()
    }
""")
        if (chat) sb.append("""
    // ===================== 💬 MENSAJERÍA (Firebase) =====================
    /** Lista de chats en el menú izquierdo + aviso cuando otra persona te escribe. */
    private fun renderChats(chats: List<Messenger.Chat>) {
        val box = findViewById<LinearLayout>(R.id.chat_list)
        box.removeAllViews()
        findViewById<View>(R.id.tv_no_chats).visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (c in chats) {
            box.addView(TextView(this).apply {
                text = "👤 " + c.otherName + (if (c.lastText.isNotEmpty()) "\n" + c.lastText else "")
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setPadding(pad * 2, pad, pad * 2, pad)
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    openChatView(c)
                }
            })
            val last = seenAt[c.id]
            val fromOther = c.lastFrom.isNotEmpty() && c.lastFrom != Account.uid
            if (chatsLoaded && fromOther && (last == null || c.lastAt > last) && (openChat?.id != c.id || !visible)) {
                Notifier.show(this, c.otherName, c.lastText)
            }
            seenAt[c.id] = c.lastAt
        }
        chatsLoaded = true
    }

    private fun openChatView(c: Messenger.Chat) {""" + (if (social) """
        leaveSocial()
        input.hint = getString(R.string.hint_message)""" else "") + """
        openChat = c
        findViewById<TextView>(R.id.tv_title).text = c.otherName
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.chat_subtitle)
        messagesListener?.remove()
        messages.clear()
        adapter.notifyDataSetChanged()
        welcome.visibility = View.GONE
        list.visibility = View.VISIBLE
        messagesListener = Messenger.listenMessages(c.id) { items ->
            messages.clear()
            items.forEach { messages.add(Msg(it.fromMe, it.text, it.image)) }
            adapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
        }
    }

    private fun closeChat() {
        openChat = null
        messagesListener?.remove()
        messagesListener = null
        findViewById<TextView>(R.id.tv_title).setText(R.string.app_name)
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.app_subtitle)
        messages.clear()
        adapter.notifyDataSetChanged()
        list.visibility = View.GONE
        welcome.visibility = View.VISIBLE""" + (if (social) "\n        showHome()" else "") + """
    }

    private fun askNewChat() {
        val field = EditText(this).apply {
            hint = getString(R.string.newchat_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.newchat_title)
            .setView(field)
            .setPositiveButton(R.string.newchat_ok) { _, _ ->
                Messenger.startChatWith(field.text.toString()) { c, err ->
                    if (c != null) openChatView(c) else addMessage(false, "⚠️ " + (err ?: "No se pudo abrir el chat"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
""")
        if (social) sb.append(socialActivityPart(chat))
        sb.append("""
    // 🎤 Dictado por voz
    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage(false, getString(R.string.mic_unavailable))
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { btnMic.alpha = 0.4f }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { btnMic.alpha = 1f }
            override fun onError(error: Int) { btnMic.alpha = 1f }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) }
            }
            override fun onResults(results: Bundle?) {
                btnMic.alpha = 1f
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()""" +
            (if (social) """
                if (heard.isNotBlank() && feedVisible()) { input.setText(heard); input.setSelection(heard.length); return }   // 👥 revisa antes de publicar""" else "") + """
                if (heard.isNotBlank()) send(heard)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    override fun onDestroy() {
        recognizer?.destroy()""" + (if (chat) """
        chatsListener?.remove()
        messagesListener?.remove()""" else "") + (if (social) """
        listOf(feedListener, profileListener, followListener, meListener, activityListener, commentsListener).forEach { it?.remove() }""" else "") + """
        super.onDestroy()
    }

    /** Burbujas de la conversación: las del usuario a la derecha con el color principal. */
    private class MessageAdapter(private val items: List<Msg>) : RecyclerView.Adapter<MessageAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.message_text)
            val image: ImageView = view.findViewById(R.id.message_image)""" + (if (clean) """
            val actions: View = view.findViewById(R.id.message_actions)
            val copy: View = view.findViewById(R.id.msg_copy)
            val share: View = view.findViewById(R.id.msg_share)
            val save: View = view.findViewById(R.id.msg_save)""" else "") + """
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            holder.text.text = m.text
            holder.text.setBackgroundResource(if (m.fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai)
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.context, if (m.fromUser) R.color.on_primary else R.color.text_primary))
            holder.image.visibility = if (m.image != null) View.VISIBLE else View.GONE
            m.image?.let { holder.image.setImageBitmap(it) }
            (holder.itemView as LinearLayout).gravity = if (m.fromUser) Gravity.END else Gravity.START""" + (if (clean) """
            // 💬 Como Cumulus: la respuesta va sin globo y debajo lleva 📋 copiar · ↗️ compartir · ⬇️ guardar
            val ctx = holder.itemView.context
            if (!m.fromUser) holder.text.background = null
            holder.actions.visibility = if (m.fromUser || m.text.isBlank()) View.GONE else View.VISIBLE
            holder.copy.setOnClickListener {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("respuesta", m.text))
                android.widget.Toast.makeText(ctx, "📋 Copiado", android.widget.Toast.LENGTH_SHORT).show()
            }
            holder.share.setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.text)
                ctx.startActivity(Intent.createChooser(send, "Compartir"))
            }
            holder.save.setOnClickListener {
                try {
                    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
                    val f = java.io.File(dir, "respuesta_" + System.currentTimeMillis() + ".txt")
                    f.writeText(m.text)
                    android.widget.Toast.makeText(ctx, "⬇️ Guardado: " + f.name, android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, "⚠️ No se pudo guardar", android.widget.Toast.LENGTH_SHORT).show()
                }
            }""" else "") + """
        }
    }
}
""")
        return sb.toString()
    }

    /** 👥 Pantallas de la red social dentro de MainActivity (inicio, perfil, comentarios, actividad). */
    private fun socialActivityPart(chat: Boolean): String = """
    // ===================== 👥 RED SOCIAL (Firebase) =====================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun info(text: String) = Snackbar.make(drawer, text, Snackbar.LENGTH_LONG).show()

    /** El inicio (publicaciones) está a la vista y hay una cuenta abierta. */
    private fun feedVisible() = welcome.visibility == View.VISIBLE && Account.uid != null &&
        findViewById<View>(R.id.auth_section).visibility != View.VISIBLE

    private fun startSocial() {
        listOf(feedListener, followListener, meListener, activityListener).forEach { it?.remove() }
        feedListener = Social.listenFeed { posts -> feedPosts = posts; if (profileUid == null) renderFeed() }
        followListener = Social.listenFollowing { set -> following = set; renderFeed() }
        meListener = Social.listenMe { set -> blocked = set; renderFeed() }
        activityListener = Social.listenActivity { items -> onActivity(items) }
        showHome()
    }

    private fun stopSocial() {
        listOf(feedListener, profileListener, followListener, meListener, activityListener, commentsListener).forEach { it?.remove() }
        feedListener = null; profileListener = null; followListener = null; meListener = null; activityListener = null; commentsListener = null
        feedPosts = emptyList(); profilePosts = emptyList(); following = emptySet(); blocked = emptySet()
        activityItems = emptyList(); activitySeen.clear(); activityLoaded = false
        profileUid = null; commentsOf = null; showingActivity = false; pendingAvatar = false
        setPending(null)
        findViewById<LinearLayout>(R.id.feed_list).removeAllViews()
        findViewById<View>(R.id.profile_header).visibility = View.GONE
        input.hint = getString(R.string.hint_message)
    }

    /** Sale del perfil, los comentarios o la actividad (sin cambiar de pantalla). */
    private fun leaveSocial() {
        commentsListener?.remove(); commentsListener = null; commentsOf = null
        profileListener?.remove(); profileListener = null; profileUid = null
        showingActivity = false
        findViewById<View>(R.id.profile_header).visibility = View.GONE
        findViewById<View>(R.id.tv_welcome).visibility = View.VISIBLE
        findViewById<View>(R.id.tv_welcome_sub).visibility = View.VISIBLE
    }

    /** 🏠 Inicio desde el menú (cierra el chat si hay uno abierto). */
    private fun goHome() {""" + (if (chat) """
        if (openChat != null) { closeChat(); return }""" else "") + """
        showHome()
    }

    /** 🏠 Inicio: las publicaciones de todos (o solo de las personas que sigues). */
    private fun showHome() {
        leaveSocial()
        findViewById<TextView>(R.id.tv_title).setText(R.string.app_name)
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.app_subtitle)
        list.visibility = View.GONE
        welcome.visibility = View.VISIBLE
        input.hint = getString(R.string.post_hint)
        renderFeed()
    }

    private fun renderFeed() {
        val box = findViewById<LinearLayout>(R.id.feed_list)
        box.removeAllViews()
        val me = Account.uid
        val onlyFollowing = getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("module:following", false)
        val hidden = hiddenPosts()
        val source = if (profileUid != null) profilePosts else feedPosts
        val shown = source.filter { p ->
            p.author !in blocked && p.id !in hidden && (profileUid != null || !onlyFollowing || p.author == me || p.author in following)
        }
        findViewById<View>(R.id.tv_feed_empty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        shown.forEach { box.addView(postView(it)) }
    }

    /** Tarjeta de una publicación: autor, texto, foto, ❤️ me gusta y 💬 comentarios. */
    private fun postView(p: Social.Post): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_post)
            setPadding(dp(14), dp(12), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(avatarView(p.name, p.avatar, 40))
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        who.addView(TextView(this).apply {
            text = p.name; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.text_primary))
        })
        who.addView(TextView(this).apply { text = ago(p.at); textSize = 12f; setTextColor(color(R.color.text_secondary)) })
        head.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "⋮"; textSize = 22f; setPadding(dp(14), 0, dp(4), 0); setTextColor(color(R.color.text_secondary))
            setOnClickListener { postMenu(p) }
        })
        head.setOnClickListener { openProfile(p.author) }
        card.addView(head)
        if (p.text.isNotBlank()) card.addView(TextView(this).apply {
            text = p.text; textSize = 15f; setTextColor(color(R.color.text_primary)); setPadding(0, dp(10), 0, 0)
        })
        p.image?.let { bmp ->
            card.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = dp(420)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_icon_soft)
                clipToOutline = true
                contentDescription = getString(R.string.cd_photo)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        val liked = Account.uid in p.likes
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        actions.addView(TextView(this).apply {
            text = (if (liked) "❤️ " else "🤍 ") + p.likes.size
            textSize = 15f; setPadding(0, dp(8), dp(28), dp(8))
            setTextColor(color(if (liked) R.color.primary else R.color.text_secondary))
            setOnClickListener { Social.toggleLike(p) }
        })
        actions.addView(TextView(this).apply {
            text = "💬 " + p.comments
            textSize = 15f; setPadding(0, dp(8), dp(28), dp(8)); setTextColor(color(R.color.text_secondary))
            setOnClickListener { openComments(p) }
        })
        card.addView(actions)
        return card
    }

    /** Foto de perfil redonda (o la inicial del nombre si no tiene foto). */
    private fun avatarView(name: String, photo: Bitmap?, size: Int): View {
        val lp = LinearLayout.LayoutParams(dp(size), dp(size))
        return if (photo != null) ImageView(this).apply {
            setImageBitmap(photo); scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_circle_primary); clipToOutline = true; layoutParams = lp
        } else TextView(this).apply {
            val n = name.trim()
            text = if (n.isEmpty()) "?" else String(Character.toChars(n.codePointAt(0))).uppercase()
            gravity = Gravity.CENTER; textSize = size / 2.6f; setTextColor(color(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_circle_primary); layoutParams = lp
        }
    }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            at <= 0L || s < 60 -> getString(R.string.time_now)
            s < 3600 -> getString(R.string.time_min, s / 60)
            s < 86400 -> getString(R.string.time_hours, s / 3600)
            else -> getString(R.string.time_days, s / 86400)
        }
    }

    // Publicaciones que reportaste: ya no las ves en este teléfono (cada cuenta tiene su lista)
    private fun hiddenPosts(): Set<String> =
        getSharedPreferences("social", MODE_PRIVATE).getStringSet("hidden_" + Account.uid, emptySet()) ?: emptySet()
    private fun hidePost(id: String) {
        getSharedPreferences("social", MODE_PRIVATE).edit().putStringSet("hidden_" + Account.uid, hiddenPosts() + id).apply()
    }

    private fun setPending(photo: Bitmap?) {
        pendingPhoto = photo
        findViewById<View>(R.id.tv_pending_photo).visibility = if (photo != null) View.VISIBLE else View.GONE
    }

    private fun publish(text: String) {
        val photo = pendingPhoto
        setPending(null)
        Social.publish(text, photo) { err -> if (err != null) info("⚠️ " + err) }
    }

    private fun confirm(message: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> ok() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** ⋮ Opciones de una publicación: eliminar (la tuya) o ver perfil, reportar y bloquear (la de otra persona). */
    private fun postMenu(p: Social.Post) {
        val mine = p.author == Account.uid
        val options = if (mine) arrayOf(getString(R.string.post_delete))
        else arrayOf(getString(R.string.view_profile), getString(R.string.post_report), getString(R.string.block_user, p.name))
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when {
                mine -> confirm(getString(R.string.post_delete_q)) { Social.delete(p) }
                which == 0 -> openProfile(p.author)
                which == 1 -> { Social.report(p); hidePost(p.id); renderFeed(); info(getString(R.string.reported)) }
                else -> confirm(getString(R.string.block_q, p.name)) {
                    Social.block(p.author)
                    if (p.author in following) Social.follow(p.author, false)
                    blocked = blocked + p.author
                    info(getString(R.string.blocked_ok))
                    showHome()
                }
            }
        }.show()
    }

    /** 👤 Perfil: foto, nombre, biografía, seguidores, botón seguir y sus publicaciones. */
    private fun openProfile(uid: String) {
        if (Account.uid == null) return
        drawer.closeDrawers()""" + (if (chat) """
        if (openChat != null) closeChat()""" else "") + """
        leaveSocial()
        profileUid = uid
        profilePosts = emptyList()
        list.visibility = View.GONE
        welcome.visibility = View.VISIBLE
        input.hint = getString(R.string.post_hint)
        findViewById<View>(R.id.tv_welcome).visibility = View.GONE
        findViewById<View>(R.id.tv_welcome_sub).visibility = View.GONE
        val header = findViewById<LinearLayout>(R.id.profile_header)
        header.removeAllViews()
        header.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.profile_subtitle)
        renderFeed()
        profileListener = Social.listenUserPosts(uid) { posts -> if (profileUid == uid) { profilePosts = posts; renderFeed() } }
        Social.profile(uid) { prof -> if (profileUid == uid && prof != null) renderProfile(prof) }
    }

    private fun renderProfile(prof: Social.Profile) {
        val header = findViewById<LinearLayout>(R.id.profile_header)
        header.removeAllViews()
        findViewById<TextView>(R.id.tv_title).text = prof.name
        header.addView(avatarView(prof.name, prof.photo, 72))
        header.addView(TextView(this).apply {
            text = prof.name; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.text_primary)); setPadding(0, dp(8), 0, 0)
        })
        if (prof.bio.isNotBlank()) header.addView(TextView(this).apply {
            text = prof.bio; textSize = 14f; setTextColor(color(R.color.text_secondary)); setPadding(0, dp(4), 0, 0)
        })
        val counts = TextView(this).apply { textSize = 13f; setTextColor(color(R.color.text_secondary)); setPadding(0, dp(6), 0, 0) }
        header.addView(counts)
        val showCounts = {
            Social.counts(prof.uid) { followers, follows ->
                counts.text = followers.toString() + " " + getString(R.string.followers_label) + " · " +
                    follows.toString() + " " + getString(R.string.following_label)
            }
        }
        showCounts()
        val me = prof.uid == Account.uid
        val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        header.addView(MaterialButton(this).apply {
            setAllCaps(false)
            text = getString(if (me) R.string.edit_profile else if (prof.uid in following) R.string.unfollow else R.string.follow)
            setOnClickListener {
                if (me) editProfile() else {
                    val on = prof.uid !in following
                    following = if (on) following + prof.uid else following - prof.uid
                    text = getString(if (on) R.string.unfollow else R.string.follow)
                    Social.follow(prof.uid, on) { showCounts() }
                }
            }
        }, full)""" + (if (chat) """
        if (!me && prof.email.isNotEmpty()) header.addView(MaterialButton(this).apply {
            setAllCaps(false)
            text = getString(R.string.send_message)
            setOnClickListener {
                Messenger.startChatWith(prof.email) { c, err -> if (c != null) openChatView(c) else info("⚠️ " + (err ?: "")) }
            }
        }, LinearLayout.LayoutParams(full))""" else "") + """
    }

    /** ✏️ Editar nombre, biografía y foto de perfil. */
    private fun editProfile() {
        val myUid = Account.uid ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply {
            hint = getString(R.string.name_hint); setText(Account.myName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val bio = EditText(this).apply {
            hint = getString(R.string.bio_hint); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(name); box.addView(bio)
        Social.profile(myUid) { p -> if (p != null && bio.text.isEmpty()) bio.setText(p.bio) }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_profile)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                Social.saveProfile(name.text.toString().trim(), bio.text.toString().trim().take(160)) { err ->
                    info(if (err != null) "⚠️ " + err else getString(R.string.profile_saved))
                    profileUid?.let { openProfile(it) }
                }
            }
            .setNeutralButton(R.string.change_photo) { _, _ -> pendingAvatar = true; choosePhoto() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 🔎 Buscar personas por nombre o correo. */
    private fun searchPeople() {
        if (Account.uid == null) return
        val field = EditText(this).apply { hint = getString(R.string.search_hint); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_title)
            .setView(field)
            .setPositiveButton(R.string.search_ok) { _, _ ->
                Social.search(field.text.toString()) { people ->
                    val found = people.filter { it.uid !in blocked }
                    if (found.isEmpty()) info(getString(R.string.search_none))
                    else AlertDialog.Builder(this)
                        .setTitle(R.string.search_title)
                        .setItems(found.map { "👤 " + it.name }.toTypedArray()) { _, i -> openProfile(found[i].uid) }
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 💬 Comentarios de una publicación (se escriben en la barra de abajo). */
    private fun openComments(p: Social.Post) {
        commentsListener?.remove()
        commentsOf = p
        showingActivity = false
        findViewById<TextView>(R.id.tv_title).setText(R.string.comments_title)
        findViewById<TextView>(R.id.tv_subtitle).text = p.name + (if (p.text.isNotBlank()) ": " + p.text.take(40) else "")
        input.hint = getString(R.string.comment_hint)
        welcome.visibility = View.GONE
        list.visibility = View.VISIBLE
        messages.clear()
        adapter.notifyDataSetChanged()
        commentsListener = Social.listenComments(p.id) { items ->
            if (commentsOf?.id != p.id) return@listenComments
            val me = Account.uid
            messages.clear()
            messages.add(Msg(false, "📝 " + p.name + (if (p.text.isNotBlank()) "\n" + p.text else ""), p.image))
            items.filter { it.from !in blocked }.forEach { c ->
                messages.add(Msg(c.from == me, if (c.from == me) c.text else c.name + "\n" + c.text))
            }
            if (items.isEmpty()) messages.add(Msg(false, getString(R.string.no_comments)))
            adapter.notifyDataSetChanged()
            list.scrollToPosition(messages.size - 1)
        }
    }

    private fun closeComments() {
        commentsListener?.remove(); commentsListener = null; commentsOf = null
        val uid = profileUid
        if (uid != null) openProfile(uid) else showHome()
    }

    /** 🔔 Actividad: quién te dio me gusta, te comentó o te empezó a seguir (y aviso cuando llega algo nuevo). */
    private fun onActivity(items: List<Social.Activity>) {
        activityItems = items
        if (activityLoaded) items.filter { it.id !in activitySeen && it.from !in blocked }.forEach {
            Notifier.show(this, getString(R.string.app_name), it.name + " " + activityText(it))
        }
        items.forEach { activitySeen.add(it.id) }
        activityLoaded = true
        if (showingActivity) showActivity()
    }

    private fun activityText(a: Social.Activity) = when (a.type) {
        "like" -> getString(R.string.act_like)
        "comment" -> getString(R.string.act_comment) + ": " + a.text
        else -> getString(R.string.act_follow)
    }

    private fun showActivity() {
        if (Account.uid == null) return""" + (if (chat) """
        if (openChat != null) closeChat()""" else "") + """
        leaveSocial()
        showingActivity = true
        findViewById<TextView>(R.id.tv_title).setText(R.string.activity_title)
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.activity_sub)
        welcome.visibility = View.GONE
        list.visibility = View.VISIBLE
        messages.clear()
        val items = activityItems.filter { it.from !in blocked }
        items.forEach { messages.add(Msg(false, "👤 " + it.name + " " + activityText(it) + "\n" + ago(it.at))) }
        if (items.isEmpty()) messages.add(Msg(false, getString(R.string.no_activity)))
        adapter.notifyDataSetChanged()
    }

    private fun leaveActivity() {
        showingActivity = false
        messages.clear()
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tv_title).setText(R.string.app_name)
        findViewById<TextView>(R.id.tv_subtitle).setText(R.string.app_subtitle)
    }
"""

    // ------------------------------------------------------------------ archivos de los módulos (Android)
    private fun bubbleServiceKt(pkg: String) = """package $pkg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 🫧 Burbuja flotante: se ve encima de otras apps y muestra cuántos avisos nuevos hay.
 * Tócala para abrir la app · arrástrala para moverla · llévala abajo del todo para cerrarla.
 */
class BubbleService : Service() {

    companion object {
        @Volatile var badge = 0
            private set
        private var instance: BubbleService? = null

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startService(Intent(context, BubbleService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, BubbleService::class.java)) }
        fun addBadge() { badge++; instance?.refresh() }
        fun clearBadge() { badge = 0; instance?.refresh() }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var counter: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val size = (60 * d).toInt()
        val bubble = TextView(this).apply {
            text = getString(R.string.bubble_emoji)
            textSize = 26f
            gravity = Gravity.CENTER
            elevation = 8 * d
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@BubbleService, R.color.primary))
            }
        }
        val dot = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE53935.toInt()) }
        }
        val frame = FrameLayout(this)
        frame.addView(bubble, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        frame.addView(dot, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.TOP or Gravity.END))
        val lp = WindowManager.LayoutParams(
            size + (8 * d).toInt(), size + (8 * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (200 * d).toInt() }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 10 * d || abs(dy) > 10 * d) moved = true
                    lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(frame, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val screen = resources.displayMetrics
                    when {
                        moved && e.rawY > screen.heightPixels - 120 * d -> stopSelf()   // llevada abajo: se cierra
                        !moved -> {
                            clearBadge()
                            startActivity(Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }
                        else -> {   // se pega al borde más cercano
                            lp.x = if (e.rawX < screen.widthPixels / 2) 0 else screen.widthPixels - frame.width
                            runCatching { wm.updateViewLayout(frame, lp) }
                        }
                    }
                    true
                }
                else -> false
            }
        }
        root = frame
        counter = dot
        runCatching { wm.addView(frame, lp) }.onFailure { stopSelf() }
        refresh()
    }

    fun refresh() {
        val v = counter ?: return
        v.post {
            v.visibility = if (badge > 0) View.VISIBLE else View.GONE
            v.text = if (badge > 9) "9+" else badge.toString()
        }
    }

    override fun onDestroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        counter = null
        instance = null
        super.onDestroy()
    }
}
"""

    private fun notifierKt(pkg: String, bubble: Boolean) = """package $pkg

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 🔔 Notificaciones de la app: Notifier.show(context, "Título", "Texto"). */
object Notifier {
    private const val CHANNEL = "avisos"
    private var nextId = 1000

    /** Activadas en Ajustes y con permiso del teléfono. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("module:notify", false) &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(context: Context, title: String, text: String) {${if (bubble) "\n        BubbleService.addBadge()   // 🫧 la burbuja cuenta el aviso nuevo" else ""}
        if (!enabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(nextId++, notification)
        } catch (e: SecurityException) {
            // sin permiso: no se muestra
        }
    }
}
"""

    private fun storeKt(pkg: String) = """package $pkg

import android.content.Context
import org.json.JSONArray

/**
 * 💾 Datos que quedan guardados aunque cierres la app (notas, listas, historial…).
 * Store.add(context, "notas", "Comprar pan") · Store.all(context, "notas") · Store.remove(context, "notas", 0)
 */
object Store {
    private fun prefs(context: Context) = context.getSharedPreferences("datos", Context.MODE_PRIVATE)

    fun all(context: Context, list: String): MutableList<String> = try {
        val arr = JSONArray(prefs(context).getString(list, "[]"))
        MutableList(arr.length()) { arr.getString(it) }
    } catch (e: Exception) { mutableListOf() }

    fun set(context: Context, list: String, items: List<String>) {
        prefs(context).edit().putString(list, JSONArray(items).toString()).apply()
    }

    /** Agrega al final. Con [max] se guardan solo los últimos [max]. */
    fun add(context: Context, list: String, item: String, max: Int = 0) {
        val items = all(context, list)
        items.add(item)
        while (max > 0 && items.size > max) items.removeAt(0)
        set(context, list, items)
    }

    fun remove(context: Context, list: String, index: Int): Boolean {
        val items = all(context, list)
        if (index !in items.indices) return false
        items.removeAt(index)
        set(context, list, items)
        return true
    }

    fun clear(context: Context, list: String) { prefs(context).edit().remove(list).apply() }

    fun clearAll(context: Context) { prefs(context).edit().clear().apply() }
}
"""

    private fun remindersKt(pkg: String) = """package $pkg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** ⏰ Recordatorios: Reminders.schedule(context, horaEnMilisegundos, "texto"). Avisan con una notificación. */
object Reminders {
    fun schedule(context: Context, atMillis: Long, text: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(
            context, (atMillis / 1000).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Exacto si el teléfono lo permite; si no, aproximado (puede llegar unos minutos tarde)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}

/** Recibe la alarma y muestra el aviso. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Recordatorio"
        Notifier.show(context, "⏰ " + context.getString(R.string.app_name), text)
    }
}
"""

    private fun netKt(pkg: String) = """package $pkg

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 🌐 Datos de internet: Net.get(url) devuelve el texto; Net.json(url) un JSONObject (o null si falla). */
object Net {
    fun get(url: String, timeoutMs: Int = 15000): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) { null }

    fun json(url: String): JSONObject? = get(url)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
}
"""

    /** 👤 Cuentas con correo (Firebase Authentication): las usan la mensajería y la red social. */
    private fun accountKt(pkg: String) = """package $pkg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream

/**
 * 👤 Cuentas con correo y contraseña (Firebase).
 * Necesita el archivo google-services.json de tu proyecto de Firebase en la carpeta app/ (mira LEEME_FIREBASE.md).
 * Perfil: users/{uid} con name, nameLower, email y bio.
 */
object Account {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private var newName: String? = null   // el nombre recién elegido (Firebase lo guarda unos segundos después)

    val uid: String? get() = try { auth.currentUser?.uid } catch (e: Exception) { null }
    val myName: String
        get() = newName ?: auth.currentUser?.let { it.displayName ?: it.email?.substringBefore('@') } ?: "Yo"

    fun signIn(email: String, password: String, done: (String?) -> Unit) {
        newName = null
        try {
            auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener { done(null) }
                .addOnFailureListener { done(it.message ?: "No se pudo entrar") }
        } catch (e: Exception) { done(e.message ?: "Firebase no está configurado") }
    }

    fun signUp(name: String, email: String, password: String, done: (String?) -> Unit) {
        try {
            auth.createUserWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener { res ->
                    val user = res.user
                    if (user == null) { done("No se pudo crear la cuenta"); return@addOnSuccessListener }
                    newName = name
                    user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
                    db.collection("users").document(user.uid)
                        .set(mapOf("name" to name, "nameLower" to name.lowercase(), "email" to email.trim().lowercase(), "bio" to ""))
                        .addOnSuccessListener { done(null) }
                        .addOnFailureListener { done(it.message) }
                }
                .addOnFailureListener { done(it.message ?: "No se pudo crear la cuenta") }
        } catch (e: Exception) { done(e.message ?: "Firebase no está configurado") }
    }

    /** Cambia el nombre que ven las demás personas. */
    fun rename(name: String) {
        newName = name
        auth.currentUser?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
    }

    fun signOut() {
        newName = null
        try { auth.signOut() } catch (e: Exception) { }
    }

    // Las fotos van dentro de los datos (achicadas): no hace falta Firebase Storage (que es de pago)
    fun encode(b: Bitmap, max: Int = 640): String {
        val k = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decode(s: String): Bitmap? = try {
        val bytes = Base64.decode(s, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }
}
"""

    private fun messengerKt(pkg: String) = """package $pkg

import android.graphics.Bitmap
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * 💬 Mensajería real entre teléfonos con Firebase: chats y mensajes en tiempo real (las cuentas están en Account.kt).
 * Datos: chats/{idA_idB} · chats/{id}/messages/{auto}
 */
object Messenger {
    data class Chat(val id: String, val otherName: String, val lastText: String, val lastAt: Long, val lastFrom: String)
    data class Message(val fromMe: Boolean, val text: String, val image: Bitmap?, val at: Long)

    private val db get() = Account.db

    /** Abre (o crea) el chat con la persona que tiene ese correo. */
    fun startChatWith(email: String, done: (Chat?, String?) -> Unit) {
        val me = Account.uid ?: return done(null, "Primero inicia sesión")
        db.collection("users").whereEqualTo("email", email.trim().lowercase()).limit(1).get()
            .addOnSuccessListener { snap ->
                val other = snap.documents.firstOrNull()
                when {
                    other == null -> done(null, "No hay nadie con el correo " + email.trim())
                    other.id == me -> done(null, "Ese es tu propio correo")
                    else -> {
                        val otherName = other.getString("name") ?: email.trim()
                        val id = listOf(me, other.id).sorted().joinToString("_")
                        val ref = db.collection("chats").document(id)
                        ref.get().addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                done(Chat(id, otherName, doc.getString("lastText").orEmpty(), 0L, doc.getString("lastFrom").orEmpty()), null)
                            } else {
                                ref.set(mapOf(
                                    "members" to listOf(me, other.id),
                                    "names" to mapOf(me to Account.myName, other.id to otherName),
                                    "lastText" to "", "lastFrom" to "",
                                    "lastAt" to FieldValue.serverTimestamp()
                                )).addOnSuccessListener { done(Chat(id, otherName, "", 0L, ""), null) }
                                    .addOnFailureListener { done(null, it.message) }
                            }
                        }.addOnFailureListener { done(null, it.message) }
                    }
                }
            }
            .addOnFailureListener { done(null, it.message) }
    }

    /** Escucha tus chats (el más reciente primero). */
    fun listenChats(onChange: (List<Chat>) -> Unit): ListenerRegistration? {
        val me = Account.uid ?: return null
        return db.collection("chats").whereArrayContains("members", me).addSnapshotListener { snap, _ ->
            val chats = snap?.documents.orEmpty().map { d ->
                val names = d.get("names") as? Map<*, *> ?: emptyMap<Any, Any>()
                val other = names.entries.firstOrNull { it.key != me }?.value?.toString() ?: "Chat"
                Chat(d.id, other, d.getString("lastText").orEmpty(),
                    d.getTimestamp("lastAt")?.toDate()?.time ?: 0L, d.getString("lastFrom").orEmpty())
            }.sortedByDescending { it.lastAt }
            onChange(chats)
        }
    }

    /** Escucha los mensajes de un chat (los últimos 200, en orden). */
    fun listenMessages(chatId: String, onChange: (List<Message>) -> Unit): ListenerRegistration {
        val me = Account.uid
        return db.collection("chats").document(chatId).collection("messages")
            .orderBy("at", Query.Direction.ASCENDING).limitToLast(200)
            .addSnapshotListener { snap, _ ->
                onChange(snap?.documents.orEmpty().map { d ->
                    Message(d.getString("from") == me, d.getString("text").orEmpty(), d.getString("image")?.let { Account.decode(it) },
                        d.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis())
                })
            }
    }

    /** Envía un mensaje (y una foto achicada, si hay). */
    fun send(chatId: String, text: String, image: Bitmap? = null) {
        val me = Account.uid ?: return
        val data = hashMapOf<String, Any>("from" to me, "text" to text, "at" to FieldValue.serverTimestamp())
        if (image != null) data["image"] = Account.encode(image)
        val chat = db.collection("chats").document(chatId)
        chat.collection("messages").add(data)
        chat.update(mapOf(
            "lastText" to (if (image != null) "📷 Foto" else text).take(80),
            "lastFrom" to me,
            "lastAt" to FieldValue.serverTimestamp()
        ))
    }
}
"""

    /** 👥 Red social con Firebase (publicaciones, me gusta, comentarios, perfiles, seguir, actividad, reportar y bloquear). */
    private fun socialKt(pkg: String) = """package $pkg

import android.graphics.Bitmap
import android.util.LruCache
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * 👥 Red social real con Firebase: publicaciones con foto, ❤️ me gusta, 💬 comentarios, perfiles, seguir,
 * 🔔 actividad, 🚩 reportar y 🚫 bloquear (Google Play pide estas dos últimas en apps donde la gente publica).
 * Datos: users/{uid} · posts/{auto} · posts/{id}/comments · follows/{yo_otro} · users/{uid}/activity · reports
 */
object Social {
    data class Profile(val uid: String, val name: String, val bio: String, val photo: Bitmap?, val email: String)
    data class Post(val id: String, val author: String, val name: String, val avatar: Bitmap?, val text: String,
                    val image: Bitmap?, val at: Long, val likes: List<String>, val comments: Long)
    data class Comment(val from: String, val name: String, val text: String, val at: Long)
    data class Activity(val id: String, val from: String, val name: String, val type: String, val text: String, val at: Long)

    private val db get() = Account.db
    private var myPhoto: String? = null   // tu foto de perfil (achicada), va en cada publicación

    // Fotos ya convertidas: no se vuelven a decodificar cada vez que cambia algo (máx. 24 MB)
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun image(key: String, data: String?): Bitmap? {
        if (data.isNullOrEmpty()) return null
        images.get(key)?.let { return it }
        return Account.decode(data)?.also { images.put(key, it) }
    }

    private fun time(d: DocumentSnapshot) = d.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis()

    private fun post(d: DocumentSnapshot) = Post(
        d.id, d.getString("author").orEmpty(), d.getString("name") ?: "?",
        image(d.id + ":a", d.getString("avatar")), d.getString("text").orEmpty(), image(d.id + ":i", d.getString("image")),
        time(d), (d.get("likes") as? List<*>)?.map { it.toString() } ?: emptyList(), d.getLong("comments") ?: 0L
    )

    private fun profile(d: DocumentSnapshot): Profile {
        val photo = d.getString("photo")
        return Profile(d.id, d.getString("name") ?: "?", d.getString("bio").orEmpty(),
            image("u:" + d.id + ":" + (photo?.length ?: 0), photo), d.getString("email").orEmpty())
    }

    /** Avisa a otra persona (me gusta, comentario o nuevo seguidor). */
    private fun notify(to: String, type: String, postId: String, text: String) {
        val me = Account.uid ?: return
        if (to.isEmpty() || to == me) return
        db.collection("users").document(to).collection("activity").add(mapOf(
            "from" to me, "name" to Account.myName, "type" to type, "post" to postId,
            "text" to text.take(120), "at" to FieldValue.serverTimestamp()
        ))
    }

    /** Las publicaciones más nuevas de todos. */
    fun listenFeed(onChange: (List<Post>) -> Unit): ListenerRegistration? {
        if (Account.uid == null) return null
        return db.collection("posts").orderBy("at", Query.Direction.DESCENDING).limit(40)
            .addSnapshotListener { snap, _ -> onChange(snap?.documents.orEmpty().map { post(it) }) }
    }

    /** Las publicaciones de una persona (para su perfil). */
    fun listenUserPosts(uid: String, onChange: (List<Post>) -> Unit): ListenerRegistration =
        db.collection("posts").whereEqualTo("author", uid).limit(200)
            .addSnapshotListener { snap, _ -> onChange(snap?.documents.orEmpty().map { post(it) }.sortedByDescending { it.at }) }

    /** Publica un texto y/o una foto. */
    fun publish(text: String, photo: Bitmap?, done: (String?) -> Unit) {
        val me = Account.uid ?: return done("Primero inicia sesión")
        if (text.isBlank() && photo == null) return done(null)
        val data = hashMapOf<String, Any>(
            "author" to me, "name" to Account.myName, "text" to text.take(2000),
            "at" to FieldValue.serverTimestamp(), "likes" to emptyList<String>(), "comments" to 0L
        )
        myPhoto?.let { data["avatar"] = it }
        if (photo != null) data["image"] = Account.encode(photo, 640)
        db.collection("posts").add(data)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "No se pudo publicar") }
    }

    fun toggleLike(p: Post) {
        val me = Account.uid ?: return
        val liked = me in p.likes
        db.collection("posts").document(p.id)
            .update(mapOf("likes" to if (liked) FieldValue.arrayRemove(me) else FieldValue.arrayUnion(me)))
        if (!liked) notify(p.author, "like", p.id, "")
    }

    fun listenComments(postId: String, onChange: (List<Comment>) -> Unit): ListenerRegistration =
        db.collection("posts").document(postId).collection("comments")
            .orderBy("at", Query.Direction.ASCENDING).limitToLast(200)
            .addSnapshotListener { snap, _ ->
                onChange(snap?.documents.orEmpty().map { d ->
                    Comment(d.getString("from").orEmpty(), d.getString("name") ?: "?", d.getString("text").orEmpty(), time(d))
                })
            }

    fun comment(p: Post, text: String) {
        val me = Account.uid ?: return
        val ref = db.collection("posts").document(p.id)
        ref.collection("comments").add(mapOf(
            "from" to me, "name" to Account.myName, "text" to text.take(1000), "at" to FieldValue.serverTimestamp()
        ))
        ref.update(mapOf("comments" to FieldValue.increment(1)))
        notify(p.author, "comment", p.id, text)
    }

    fun delete(p: Post) {
        db.collection("posts").document(p.id).delete()
    }

    /** 🚩 Queda guardado en «reports» para que lo revises en la consola de Firebase. */
    fun report(p: Post) {
        val me = Account.uid ?: return
        db.collection("reports").add(mapOf(
            "by" to me, "post" to p.id, "author" to p.author, "text" to p.text.take(300), "at" to FieldValue.serverTimestamp()
        ))
    }

    /** 🚫 Ya no verás sus publicaciones ni sus comentarios. */
    fun block(uid: String) {
        val me = Account.uid ?: return
        db.collection("users").document(me).set(mapOf("blocked" to FieldValue.arrayUnion(uid)), SetOptions.merge())
    }

    fun follow(uid: String, on: Boolean, done: () -> Unit = {}) {
        val me = Account.uid ?: return
        val ref = db.collection("follows").document(me + "_" + uid)
        if (on) {
            ref.set(mapOf("from" to me, "to" to uid, "at" to FieldValue.serverTimestamp())).addOnSuccessListener { done() }
            notify(uid, "follow", "", "")
        } else {
            ref.delete().addOnSuccessListener { done() }
        }
    }

    /** Las personas que sigues. */
    fun listenFollowing(onChange: (Set<String>) -> Unit): ListenerRegistration? {
        val me = Account.uid ?: return null
        return db.collection("follows").whereEqualTo("from", me)
            .addSnapshotListener { snap, _ -> onChange(snap?.documents.orEmpty().mapNotNull { it.getString("to") }.toSet()) }
    }

    /** Cuántos seguidores tiene y a cuántos sigue. */
    fun counts(uid: String, done: (Int, Int) -> Unit) {
        val follows = db.collection("follows")
        follows.whereEqualTo("to", uid).get().addOnSuccessListener { a ->
            follows.whereEqualTo("from", uid).get().addOnSuccessListener { b -> done(a.size(), b.size()) }
        }
    }

    /** Tus datos: a quién bloqueaste (y tu foto para las publicaciones). */
    fun listenMe(onChange: (Set<String>) -> Unit): ListenerRegistration? {
        val me = Account.uid ?: return null
        return db.collection("users").document(me).addSnapshotListener { d, _ ->
            myPhoto = d?.getString("photo")
            onChange((d?.get("blocked") as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet())
        }
    }

    fun profile(uid: String, done: (Profile?) -> Unit) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { d -> done(if (d.exists()) profile(d) else null) }
            .addOnFailureListener { done(null) }
    }

    fun saveProfile(name: String, bio: String, done: (String?) -> Unit) {
        val me = Account.uid ?: return done("Primero inicia sesión")
        if (name.isBlank()) return done("Escribe tu nombre")
        Account.rename(name)
        db.collection("users").document(me)
            .set(mapOf("name" to name, "nameLower" to name.lowercase(), "bio" to bio), SetOptions.merge())
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message) }
    }

    /** Foto de perfil (pequeña, se ve redonda). */
    fun setPhoto(photo: Bitmap, done: (String?) -> Unit) {
        val me = Account.uid ?: return done("Primero inicia sesión")
        val small = Account.encode(photo, 160)
        myPhoto = small
        db.collection("users").document(me).set(mapOf("photo" to small), SetOptions.merge())
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message) }
    }

    /** 🔎 Busca por correo exacto o por el comienzo del nombre. */
    fun search(text: String, done: (List<Profile>) -> Unit) {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return done(emptyList())
        val users = db.collection("users")
        val query = if (q.contains("@")) users.whereEqualTo("email", q)
        else users.whereGreaterThanOrEqualTo("nameLower", q).whereLessThanOrEqualTo("nameLower", q + "")
        query.limit(20).get()
            .addOnSuccessListener { snap -> done(snap.documents.map { profile(it) }.filter { it.uid != Account.uid }) }
            .addOnFailureListener { done(emptyList()) }
    }

    /** 🔔 Lo último que pasó con tus publicaciones y seguidores. */
    fun listenActivity(onChange: (List<Activity>) -> Unit): ListenerRegistration? {
        val me = Account.uid ?: return null
        return db.collection("users").document(me).collection("activity")
            .orderBy("at", Query.Direction.DESCENDING).limit(40)
            .addSnapshotListener { snap, _ ->
                onChange(snap?.documents.orEmpty().map { d ->
                    Activity(d.id, d.getString("from").orEmpty(), d.getString("name") ?: "?", d.getString("type").orEmpty(),
                        d.getString("text").orEmpty(), time(d))
                })
            }
    }
}
"""

    /** Reglas de seguridad de Firestore: cada uno solo cambia lo suyo y solo ve sus chats. */
    private fun firestoreRules(chat: Boolean, social: Boolean): String = buildString {
        append("""rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Perfiles: cualquier usuario con sesión puede verlos (para buscar personas); solo tú cambias el tuyo
    match /users/{uid} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == uid;""")
        if (social) append("""
      // 🔔 Actividad: solo tú la ves; los demás solo pueden dejarte avisos firmados con su cuenta
      match /activity/{id} {
        allow read, delete: if request.auth != null && request.auth.uid == uid;
        allow create: if request.auth != null && request.resource.data.from == request.auth.uid;
      }""")
        append("""
    }""")
        if (chat) append("""
    // 💬 Chats: solo los dos miembros los ven y los cambian
    match /chats/{chatId} {
      allow read: if request.auth != null && (resource == null || request.auth.uid in resource.data.members);
      allow update: if request.auth != null && request.auth.uid in resource.data.members;
      allow create: if request.auth != null && request.auth.uid in request.resource.data.members;
      match /messages/{msgId} {
        allow read: if request.auth != null &&
          request.auth.uid in get(/databases/${'$'}(database)/documents/chats/${'$'}(chatId)).data.members;
        allow create: if request.auth != null && request.resource.data.from == request.auth.uid &&
          request.auth.uid in get(/databases/${'$'}(database)/documents/chats/${'$'}(chatId)).data.members;
      }
    }""")
        if (social) append("""
    // 👥 Publicaciones: todos las ven; solo el autor las crea o borra; los demás solo cambian me gusta y comentarios
    match /posts/{postId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null && request.resource.data.author == request.auth.uid &&
        request.resource.data.text is string && request.resource.data.text.size() <= 2000;
      allow delete: if request.auth != null && resource.data.author == request.auth.uid;
      allow update: if request.auth != null && (resource.data.author == request.auth.uid ||
        request.resource.data.diff(resource.data).affectedKeys().hasOnly(['likes', 'comments']));
      match /comments/{commentId} {
        allow read: if request.auth != null;
        allow create: if request.auth != null && request.resource.data.from == request.auth.uid;
        allow delete: if request.auth != null && resource.data.from == request.auth.uid;
      }
    }
    // 👥 Seguir: cada uno crea o borra solo sus propios «sigo a…»
    match /follows/{followId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null && request.resource.data.from == request.auth.uid &&
        followId == request.auth.uid + '_' + request.resource.data.to;
      allow delete: if request.auth != null && followId.matches(request.auth.uid + '_.*');
    }
    // 🚩 Reportes: se pueden enviar, pero solo tú los lees desde la consola de Firebase
    match /reports/{reportId} {
      allow create: if request.auth != null && request.resource.data.by == request.auth.uid;
    }""")
        append("""
  }
}""")
    }

    /** Pasos para conectar las cuentas y los datos con Firebase (gratis, unos 5 minutos). */
    private fun firebaseGuide(pkg: String, web: Boolean, chat: Boolean = true, social: Boolean = false): String {
        val what = listOfNotNull(if (chat) "💬 mensajería" else null, if (social) "👥 red social" else null).joinToString(" y ")
        val test = buildList {
            if (chat) add("desde ☰ → **➕ Nuevo chat** escribe el correo de la otra persona")
            if (social) add("escribe en la barra de abajo y toca ➤ para publicar; toca el nombre de alguien para ver su perfil y **➕ Seguir**")
        }.joinToString("; y ")
        return """# 🔥 Configurar Firebase ($what) — unos 5 minutos

Las cuentas y los datos compartidos necesitan un servidor que los lleve de un teléfono a otro. Se usa **Firebase** de Google (gratis para empezar).

1. Entra a https://console.firebase.google.com y toca **Crear un proyecto**.
""" + (if (web) """2. En la página del proyecto toca **</> (Web)**, ponle un nombre y registra la app.
3. Copia los datos que te muestra (`apiKey`, `authDomain`, `projectId`, `appId`…) y pégalos en **firebase-config.js**.
""" else """2. Toca **Android**, escribe el nombre de paquete **$pkg** y registra la app.
3. Descarga el archivo **google-services.json** y ponlo dentro de la carpeta **app/** del proyecto.
""") + """4. En el menú: **Authentication → Comenzar → Correo electrónico/contraseña → Activar → Guardar**.
5. **Firestore Database → Crear base de datos** (elige una ubicación cercana y el modo de producción).
6. En Firestore → **Reglas**, borra lo que hay, pega el contenido de **firestore.rules** y toca **Publicar**.
7. ${if (web) "Abre la página" else "Compila e instala la app"} en dos ${if (web) "navegadores o teléfonos" else "teléfonos"}, crea una cuenta en cada uno y $test.

**Notas**
- Las fotos van dentro de los datos (achicadas), así no necesitas el plan de pago de Firebase Storage.
- Los avisos llegan mientras la app está abierta o en segundo plano. Con la app cerrada del todo haría falta
  Firebase Cloud Messaging con un servidor propio (Cloud Functions usa el plan de pago).
""" + (if (social) """- 🚩 **Reportes:** cuando alguien reporta una publicación queda en Firestore → colección **reports** (con el id de la
  publicación). Revísala y, si no cumple tus normas, bórrala en **posts**. Google Play exige que las apps donde la gente
  publica tengan reportar y bloquear (ya vienen incluidos) y que respondas a los reportes.
- Antes de publicar en Google Play escribe tus **normas de la comunidad** y una **política de privacidad** (qué datos guardas).
""" else "")
    }

    /** Lo que la IA escribe para Android: la lógica propia de la app (AppLogic.kt). */
    fun logicAskAndroid(prompt: String, spec: AppSpec, pkg: String, cpp: Boolean, mods: Set<Module> = emptySet()): String =
        "App pedida por el usuario: «${prompt.take(400)}»\n" +
            "Ya existe la pantalla (MainActivity.kt, menú, ajustes, chat con micrófono). Escribe el archivo «AppLogic.kt» " +
            "con TODA la lógica real de esta app, con este contrato exacto:\n" +
            "package $pkg\nimport android.content.Context\n" + (if (Module.CAMERA in mods) "import android.graphics.Bitmap\n" else "") +
            "object AppLogic {\n" +
            "    fun reply(context: Context, text: String): String   // responde a lo que el usuario escribe o dice (corre en segundo plano)\n" +
            "    fun onMenu(context: Context, item: String): String? // opción del menú: ${spec.menu.joinToString(", ")}\n" +
            "    fun onSetting(context: Context, key: String, on: Boolean): String? // ajustes: ${aiSettings(spec, mods).joinToString(", ") { it.second }}\n" +
            (if (Module.CAMERA in mods) "    fun onPhoto(context: Context, photo: Bitmap): String? // el usuario sacó o eligió una foto\n" else "") +
            "}\n" +
            "Las tarjetas y atajos llegan a reply() con su texto: ${(spec.cards.map { noEmoji(it.first) } + spec.chips).joinToString(", ")}.\n" +
            moduleHelpersAndroid(mods) +
            (if (Module.CHAT in mods) "La mensajería real (cuentas, lista de chats y mensajes en tiempo real) YA está hecha en Messenger.kt: " +
                "no la reescribas. reply() solo responde cuando NO hay un chat abierto (como ayudante de la app).\n" else "") +
            (if (Module.SOCIAL in mods) "La red social real (cuentas, publicaciones con foto, me gusta, comentarios, perfiles, seguir, actividad, " +
                "reportar y bloquear) YA está hecha en Account.kt, Social.kt y MainActivity: no la reescribas. En el inicio la barra publica; " +
                "reply() responde a las tarjetas, los atajos y el menú (como ayudante de la app).\n" else "") +
            (if (cpp) "Usa C++ (llama.cpp) para la IA: dentro de AppLogic pon `init { System.loadLibrary(\"app\") }` y " +
                "`private external fun nativeReply(prompt: String): String`, y úsalo en reply().\n" else "") +
            "Que cada opción haga algo útil y real para esta app" + (if (Module.STORE in mods) " (guarda datos con Store)" else " (guarda datos con SharedPreferences si hace falta)") +
            ". Kotlin moderno y ordenado. Entrega SOLO el archivo completo dentro de un bloque ```kotlin."

    private fun moduleHelpersAndroid(mods: Set<Module>): String {
        if (mods.isEmpty()) return ""
        val l = mutableListOf<String>()
        if (Module.STORE in mods) l += "Store.add(context, lista, texto) · Store.all(context, lista): MutableList<String> · Store.remove(context, lista, índice) · Store.clear(context, lista)"
        if (Module.NOTIFY in mods) l += "Notifier.show(context, título, texto)"
        if (Module.REMIND in mods) l += "Reminders.schedule(context, horaEnMilisegundos, texto)"
        if (Module.NET in mods) l += "Net.get(url): String? · Net.json(url): org.json.JSONObject? (ya corres en segundo plano)"
        if (Module.BUBBLE in mods) l += "BubbleService.addBadge() (suma un aviso en la burbuja flotante)"
        return "Ya existen y puedes usarlos: " + l.joinToString(" · ") + "\n"
    }

    /** JNI: com.ejemplo.mi_app → com_ejemplo_mi_1app (así se llaman las funciones nativas). */
    private fun jniPkg(pkg: String) = pkg.replace("_", "_1").replace('.', '_')

    /**
     * 🦙 Lógica de partida de una app de IA LOCAL: busca el modelo .gguf en la carpeta de la app y responde con llama.cpp.
     * Los botones se agregan después, uno por uno.
     */
    fun starterLogicCpp(pkg: String, camera: Boolean): String = """package $pkg

import android.content.Context${if (camera) "\nimport android.graphics.Bitmap" else ""}
import java.io.File

/** Lógica de la app: la IA local (llama.cpp) responde a los mensajes; el menú y los ajustes dan su aviso. */
object AppLogic {

    init { System.loadLibrary("app") }

    /** Genera la respuesta con el modelo .gguf (está en native-lib.cpp). */
    private external fun nativeReply(modelPath: String, prompt: String): String

    /** Carpeta donde va el modelo: Android/data/(tu app)/files */
    fun modelFolder(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** El primer modelo .gguf que encuentre en la carpeta de la app. */
    private fun model(context: Context): File? =
        listOf(modelFolder(context), context.filesDir)
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.extension.equals("gguf", true) }

    fun reply(context: Context, text: String): String {
        val m = model(context) ?: return "📥 Todavía no hay un modelo. Copia un archivo .gguf en:\n" + modelFolder(context).absolutePath
        return try {
            nativeReply(m.absolutePath, text).trim().ifBlank { "…" }
        } catch (e: Throwable) {
            "⚠️ No pude usar el modelo: " + (e.message ?: "error")
        }
    }

    fun onMenu(context: Context, item: String): String? = "Abriste: " + item

    fun onSetting(context: Context, key: String, on: Boolean): String? =
        key + if (on) ": activado" else ": desactivado"${if (camera) """

    fun onPhoto(context: Context, photo: Bitmap): String? =
        "📷 Foto recibida (" + photo.width + "×" + photo.height + ")."""" else ""}
}
"""

    /** 🦙 Motor de IA local con llama.cpp (API actual): carga el .gguf una vez y responde usando la plantilla de chat del modelo. */
    fun starterNativeCpp(pkg: String): String = """// 🦙 IA local con llama.cpp: carga el modelo .gguf y genera la respuesta.
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "llama.h"

#define TAG "AppIA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::string g_path;
static std::mutex g_mu;

/** Carga el modelo (solo la primera vez o si cambió el archivo). */
static bool ensureLoaded(const std::string &path) {
    if (g_model && g_ctx && g_path == path) return true;
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    static bool backendReady = false;
    if (!backendReady) { llama_backend_init(); backendReady = true; }
    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return false;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 512;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return false; }
    g_path = path;
    LOGI("Modelo cargado: %s", path.c_str());
    return true;
}

/** Texto de Kotlin → UTF-8 de verdad (así los emojis y tildes llegan bien al modelo). */
static std::string jstr(JNIEnv *env, jstring s) {
    jclass cls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, getBytes, enc);
    std::string out;
    if (bytes) {
        jsize n = env->GetArrayLength(bytes);
        out.resize(n);
        env->GetByteArrayRegion(bytes, 0, n, (jbyte *) out.data());
        env->DeleteLocalRef(bytes);
    }
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return out;
}

/** UTF-8 → texto de Kotlin (NewStringUTF falla con emojis). */
static jstring toJString(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_${jniPkg(pkg)}_AppLogic_nativeReply(JNIEnv *env, jobject /* this */, jstring jpath, jstring jprompt) {
    std::lock_guard<std::mutex> lock(g_mu);
    const std::string path = jstr(env, jpath);
    const std::string prompt = jstr(env, jprompt);
    if (!ensureLoaded(path)) return toJString(env, "⚠️ No pude cargar el modelo .gguf");

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Plantilla de chat del propio modelo (si no trae, se usa el texto tal cual)
    std::string formatted = prompt;
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl) {
        llama_chat_message msg = {"user", prompt.c_str()};
        std::vector<char> buf(prompt.size() * 2 + 1024);
        int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int) buf.size());
        }
        if (n > 0) formatted.assign(buf.data(), n);
    }

    // Conversación nueva en cada mensaje
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Texto → tokens
    int nTok = -llama_tokenize(vocab, formatted.c_str(), (int) formatted.size(), nullptr, 0, true, true);
    if (nTok <= 0) return toJString(env, "⚠️ Mensaje vacío");
    std::vector<llama_token> toks(nTok);
    llama_tokenize(vocab, formatted.c_str(), (int) formatted.size(), toks.data(), (int) toks.size(), true, true);
    const int maxCtx = (int) llama_n_ctx(g_ctx);
    if ((int) toks.size() > maxCtx - 64) toks.erase(toks.begin(), toks.end() - (maxCtx - 64));

    if (llama_decode(g_ctx, llama_batch_get_one(toks.data(), (int) toks.size())) != 0)
        return toJString(env, "⚠️ El modelo no pudo leer el mensaje");

    // Muestreo: min-p + temperatura
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    int used = (int) toks.size();
    for (int i = 0; i < 1024 && used < maxCtx - 1; i++) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char piece[256];
        int len = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, true);
        if (len > 0) out.append(piece, len);
        if (llama_decode(g_ctx, llama_batch_get_one(&t, 1)) != 0) break;
        used++;
    }
    llama_sampler_free(smpl);
    return toJString(env, out);
}
"""

    fun nativeAsk(prompt: String, pkg: String): String =
        "App pedida por el usuario: «${prompt.take(300)}»\n" +
            "Escribe «native-lib.cpp» (C++17) para Android con llama.cpp: la función JNI " +
            "Java_${pkg.replace("_", "_1").replace('.', '_')}_AppLogic_nativeReply(JNIEnv*, jobject, jstring prompt) " +
            "que genera la respuesta con llama.cpp (API actual: llama_model_load_from_file, llama_init_from_model, llama_decode, " +
            "muestreo con llama_sampler) cargando el modelo .gguf desde la carpeta de archivos de la app. " +
            "Entrega SOLO el archivo completo dentro de un bloque ```cpp."

    // ========================================================================
    // 🌐 BASE WEB (HTML + CSS + JavaScript)
    // ========================================================================
    fun webShell(spec: AppSpec, pal: Palette, mods: Set<Module> = emptySet(), styleIn: Int = 0): List<Pair<String, String>> {
        // 💬 Chat limpio: la misma base que Android (si pediste funciones extra como cámara o cuentas, se usa la base completa)
        if (styleIn == CLEAN_STYLE && mods.isEmpty()) return cleanWebShell(spec.title, pal, withLogic = false)
        val style = if (styleIn == CLEAN_STYLE) 0 else styleIn
        val bubble = Module.BUBBLE in mods; val notify = Module.NOTIFY in mods; val camera = Module.CAMERA in mods
        val store = Module.STORE in mods; val remind = Module.REMIND in mods; val net = Module.NET in mods
        val chat = Module.CHAT in mods; val social = Module.SOCIAL in mods
        val account = chat || social   // cuentas con Firebase (mensajería y/o red social)
        val cards = (spec.cards.map { (t, d) ->
            "        <button class=\"card\" data-send=\"${h(noEmoji(t))}\"><span class=\"card-icon\">${firstEmoji(t, spec.emoji)}</span>" +
                "<span class=\"card-text\"><strong>${h(noEmoji(t))}</strong><small>${h(d)}</small></span><span class=\"card-arrow\">›</span></button>"
        } + moduleCards(mods).map { (t, d, tag) ->
            "        <button class=\"card\" data-module=\"$tag\"><span class=\"card-icon\">${firstEmoji(t, spec.emoji)}</span>" +
                "<span class=\"card-text\"><strong>${h(noEmoji(t))}</strong><small>${h(d)}</small></span><span class=\"card-arrow\">›</span></button>"
        }).joinToString("\n")
        val chips = spec.chips.joinToString("\n") { "        <button class=\"chip\" data-send=\"${h(it)}\">${h(it)}</button>" }
        val menu = spec.menu.joinToString("\n") { "      <button class=\"menu-item\" data-menu=\"${h(it)}\">${h(it)}</button>" }
        val settings = (aiSettings(spec, mods).map { (sw, s) -> Triple(sw, s, s) } + moduleSettings(mods)).joinToString("\n") { (isSwitch, s, key) ->
            if (isSwitch) "    <label class=\"setting\"><span>${h(s)}</span><input type=\"checkbox\" data-setting=\"${h(key)}\"><i class=\"toggle\"></i></label>"
            else "    <button class=\"setting-btn\" data-action=\"${h(key)}\">${h(s)}</button>"
        }
        val html = """<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${h(spec.title)}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <div class="app estilo-${styleIndex(style)}">
    <!-- Barra superior: ☰ menú · título · ⚙️ ajustes -->
    <header class="topbar">
      <button class="icon-btn" id="btnMenu" aria-label="Abrir menú">☰</button>${if (social) "\n      <button class=\"icon-btn\" id=\"btnBack\" aria-label=\"Volver\" hidden>←</button>" else ""}
      <div class="title">
        <h1>${h(spec.title)}</h1>
        <p>${h(spec.subtitle)}</p>
      </div>
      <button class="icon-btn" id="btnSettings" aria-label="Abrir ajustes">⚙️</button>
    </header>

    <!-- Inicio (tarjetas y atajos) y conversación -->
    <main class="content" id="content">""" + (if (account) """
      <!-- 👤 Inicio de sesión (solo si no hay una cuenta abierta) -->
      <section class="auth" id="authSection" hidden>
        <h2>${if (social) "👥" else "💬"} Inicia sesión</h2>
        <p>${if (social) "Usa tu correo para publicar, seguir a otras personas y ver lo que comparten." else "Usa tu correo para chatear con otras personas en tiempo real."}</p>
        <input type="text" id="authName" placeholder="Tu nombre (para crear cuenta)" autocomplete="name">
        <input type="email" id="authEmail" placeholder="Correo" autocomplete="email">
        <input type="password" id="authPass" placeholder="Contraseña (mínimo 6)" autocomplete="current-password">
        <p class="auth-error" id="authError"></p>
        <button class="setting-btn" id="btnLogin">Entrar</button>
        <button class="ghost wide" id="btnRegister">Crear cuenta</button>
      </section>""" else "") + """
      <section class="welcome" id="welcome">
        <h2 id="welcomeTitle">${h(spec.welcome)}</h2>
        <p id="welcomeSub">${h(spec.welcomeSub)}</p>""" + (if (social) """
        <!-- 👥 Perfil y publicaciones -->
        <div class="profile" id="profileHeader" hidden></div>
        <p class="label">📰 Publicaciones</p>
        <p class="muted" id="feedEmpty">Todavía no hay publicaciones: escribe abajo y toca ➤ para compartir la primera.</p>
        <div class="feed" id="feed"></div>
        <p class="label">✨ Descubre</p>""" else "") + """
        <div class="cards">
$cards
        </div>
        <p class="label">Atajos</p>
        <div class="chips">
$chips
        </div>
        <!-- 🔘 Botones que le vas agregando a la app (su lógica está en botones.js) -->
        <p class="label" id="myButtonsTitle" hidden>🔘 Tus botones</p>
        <div class="my-buttons" id="myButtons">
          <!-- BOTONES -->
        </div>
        <!-- 🧩 Partes que le vas agregando (barras, paneles, varios botones juntos…) -->
        <div class="my-parts" id="myParts">
          <!-- PARTES -->
        </div>
      </section>
      <div class="messages" id="messages" aria-live="polite"></div>
    </main>

    <!-- Barra de escritura: texto · ${if (camera) "📷 foto · " else ""}🎤 micrófono · ➤ enviar -->
    <footer class="composer">""" + (if (social) """
      <button class="pending" id="pendingPhoto" hidden>📷 Foto lista para publicar · toca aquí para quitarla</button>""" else "") + """
      <div class="input-bar">
        <textarea id="input" rows="1" placeholder="${h(spec.hint)}"></textarea>""" +
            (if (camera) """
        <button class="icon-btn" id="btnCamera" aria-label="Foto">📷</button>
        <input type="file" id="photoInput" accept="image/*" capture="environment" hidden>""" else "") + """
        <button class="icon-btn mic" id="btnMic" aria-label="Hablar">🎤</button>
        <button class="send" id="btnSend" aria-label="Enviar">➤</button>
      </div>
    </footer>
  </div>

  <!-- Panel lateral IZQUIERDO: menú -->
  <aside class="panel left" id="leftPanel" aria-label="Menú">
    <div class="panel-header">
      <div class="avatar">${spec.emoji}</div>
      <h3>${h(spec.title)}</h3>
      <p>${h(spec.subtitle)}</p>
    </div>
    <nav class="menu">
$menu
    </nav>""" + (if (social) """
    <div class="chats">
      <p class="label pad">👥 Comunidad</p>
      <button class="menu-item" id="btnFeedHome">🏠 Inicio</button>
      <button class="menu-item" id="btnMyProfile">👤 Mi perfil</button>
      <button class="menu-item" id="btnEditProfile">✏️ Editar perfil</button>
      <button class="menu-item" id="btnSearchPeople">🔎 Buscar personas</button>
      <button class="menu-item" id="btnActivity">🔔 Actividad</button>
    </div>""" else "") + (if (chat) """
    <div class="chats">
      <p class="label pad">💬 Chats</p>
      <button class="setting-btn slim" id="btnNewChat">➕ Nuevo chat</button>
      <p class="muted pad" id="noChats">Todavía no tienes chats: toca «Nuevo chat».</p>
      <div id="chatList"></div>
    </div>""" else "") + """
  </aside>

  <!-- Panel lateral DERECHO: ajustes -->
  <aside class="panel right" id="rightPanel" aria-label="Ajustes">
    <h3 class="panel-title">Ajustes</h3>
$settings
  </aside>

  <div class="scrim" id="scrim"></div>""" +
            (if (bubble) """

  <!-- 🫧 Burbuja flotante: muestra los avisos nuevos (arrástrala para moverla) -->
  <button class="bubble" id="bubble" aria-label="Avisos" hidden>${spec.emoji}<span class="badge" id="badge" hidden>0</span></button>""" else "") +
            (if (remind) """

  <!-- ⏰ Nuevo recordatorio -->
  <dialog class="sheet" id="remindDialog">
    <form method="dialog">
      <h3>⏰ Nuevo recordatorio</h3>
      <input type="text" id="remindText" placeholder="¿Qué te recuerdo?">
      <input type="time" id="remindTime">
      <div class="sheet-actions">
        <button type="button" class="ghost" id="remindCancel">Cancelar</button>
        <button type="button" class="setting-btn" id="remindSave">Guardar</button>
      </div>
    </form>
  </dialog>""" else "") + """
""" + (if (chat) """

  <!-- 💬 Nuevo chat -->
  <dialog class="sheet" id="newChatDialog">
    <form method="dialog">
      <h3>💬 Nuevo chat</h3>
      <input type="email" id="newChatEmail" placeholder="Correo de la otra persona">
      <div class="sheet-actions">
        <button type="button" class="ghost" id="newChatCancel">Cancelar</button>
        <button type="button" class="setting-btn" id="newChatOk">Abrir chat</button>
      </div>
    </form>
  </dialog>""" else "") + """
""" + (if (social) """

  <!-- 👥 Editar perfil -->
  <dialog class="sheet" id="profileDialog">
    <form method="dialog">
      <h3>✏️ Editar perfil</h3>
      <input type="text" id="profileName" placeholder="Tu nombre" maxlength="40">
      <textarea id="profileBio" rows="3" maxlength="160" placeholder="Biografía (opcional)"></textarea>
      <button type="button" class="ghost wide" id="profilePhoto">📷 Cambiar foto de perfil</button>
      <div class="sheet-actions">
        <button type="button" class="ghost" id="profileCancel">Cancelar</button>
        <button type="button" class="setting-btn" id="profileSave">Guardar</button>
      </div>
    </form>
  </dialog>

  <!-- 👥 Buscar personas -->
  <dialog class="sheet" id="searchDialog">
    <form method="dialog">
      <h3>🔎 Buscar personas</h3>
      <input type="search" id="searchText" placeholder="Nombre o correo">
      <div class="results" id="searchResults"></div>
      <div class="sheet-actions">
        <button type="button" class="ghost" id="searchCancel">Cerrar</button>
        <button type="button" class="setting-btn" id="searchOk">Buscar</button>
      </div>
    </form>
  </dialog>

  <!-- 👥 Opciones (publicación, confirmar) -->
  <dialog class="sheet" id="choiceDialog">
    <form method="dialog">
      <h3 id="choiceTitle"></h3>
      <div class="results" id="choiceList"></div>
      <div class="sheet-actions">
        <button type="button" class="ghost" id="choiceCancel">Cancelar</button>
      </div>
    </form>
  </dialog>""" else "") + """
  <div class="toast" id="toast" hidden></div>""" + (if (account) """
  <!-- 🔥 Firebase (cuentas y datos): pega tus datos en firebase-config.js -->
  <script src="https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js"></script>
  <script src="https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js"></script>
  <script src="https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js"></script>
  <script src="firebase-config.js"></script>
  <script src="account.js"></script>""" else "") + (if (chat) """
  <script src="messenger.js"></script>""" else "") + (if (social) """
  <script src="social.js"></script>""" else "") + """
  <script src="logic.js"></script>
  <script src="botones.js"></script>
  <script src="script.js"></script>
</body>
</html>"""
        val css = """/* 🎨 Colores de la app */
:root {
  --primary: ${pal.primary};
  --primary-dark: ${pal.primaryDark};
  --primary-light: ${pal.primaryLight};
  --on-primary: ${pal.onPrimary};
  --bg: ${pal.background};
  --surface: ${pal.surface};
  --surface-2: ${pal.surfaceVariant};
  --text: ${pal.textPrimary};
  --muted: ${pal.textSecondary};
  --line: ${pal.divider};
  --radius: 20px;
  --shadow: 0 8px 24px rgba(0, 0, 0, ${if (pal.light) "0.08" else "0.35"});
}

* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { height: 100%; }
body {
  font-family: "Inter", "Roboto", system-ui, -apple-system, "Segoe UI", sans-serif;
  background: var(--bg);
  color: var(--text);
  -webkit-font-smoothing: antialiased;
}
button { font: inherit; color: inherit; border: none; background: none; cursor: pointer; }

.app { display: flex; flex-direction: column; height: 100dvh; max-width: 820px; margin: 0 auto; }

/* Barra superior */
.topbar { display: flex; align-items: center; gap: 8px; padding: 10px 12px; }
.title { flex: 1; min-width: 0; }
.title h1 { font-size: 1.2rem; font-weight: 700; }
.title p { font-size: 0.8rem; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.icon-btn { width: 44px; height: 44px; border-radius: 50%; font-size: 1.3rem; display: grid; place-items: center; transition: background .2s; flex: none; }
.icon-btn:hover, .icon-btn:active { background: var(--surface-2); }

/* Contenido */
.content { flex: 1; overflow-y: auto; padding: 8px 20px 16px; scroll-behavior: smooth; }
.welcome h2 { font-size: 1.7rem; line-height: 1.2; margin-top: 12px; }
.welcome > p { color: var(--muted); margin: 6px 0 12px; }
.cards { display: grid; gap: 12px; }
.card {
  display: flex; align-items: center; gap: 14px; width: 100%; text-align: left;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 16px; box-shadow: var(--shadow); transition: transform .15s, border-color .2s;
}
.card:active { transform: scale(.98); }
.card:hover { border-color: var(--primary); }
.card-icon { width: 48px; height: 48px; border-radius: 14px; background: var(--surface-2); display: grid; place-items: center; font-size: 1.5rem; flex: none; }
.card-text { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.card-text small { color: var(--muted); }
.card-arrow { color: var(--primary); font-size: 1.6rem; }
.label { color: var(--muted); font-size: .8rem; font-weight: 700; margin: 24px 0 10px; text-transform: uppercase; letter-spacing: .06em; }
.chips { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; scrollbar-width: none; }
.chip { white-space: nowrap; padding: 8px 14px; border-radius: 18px; border: 1px solid var(--primary); background: var(--surface); transition: background .2s; }
.chip:hover, .chip:active { background: var(--primary); color: var(--on-primary); }
.my-buttons { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; margin: 4px 0 12px; }
.my-buttons:empty { display: none; }
.my-btn { padding: 14px 12px; border-radius: 16px; background: var(--primary); color: var(--on-primary); font-weight: 600; box-shadow: 0 4px 14px rgba(0,0,0,.18); transition: transform .15s, filter .15s; }
.my-btn:active { transform: scale(.97); filter: brightness(1.1); }
.my-parts { display: flex; flex-direction: column; gap: 12px; margin: 4px 0 12px; }
.my-parts:empty { display: none; }
.parte { background: var(--surface); border-radius: 16px; padding: 12px; }

/* Conversación */
.messages { display: flex; flex-direction: column; gap: 8px; }
.msg { max-width: 82%; padding: 10px 14px; border-radius: 18px; line-height: 1.45; white-space: pre-wrap; animation: pop .2s ease-out; }
.msg.user { align-self: flex-end; background: var(--primary); color: var(--on-primary); border-bottom-right-radius: 6px; }
.msg.bot { align-self: flex-start; background: var(--surface-2); border-bottom-left-radius: 6px; }
.msg.img { padding: 4px; background: transparent; }
.msg.img img { display: block; max-width: 240px; max-height: 240px; border-radius: 16px; }
.msg.typing::after { content: "●●●"; letter-spacing: 2px; animation: blink 1s infinite; }
@keyframes pop { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
@keyframes blink { 50% { opacity: .3; } }

/* Barra de escritura */
.composer { padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); }
.input-bar { display: flex; align-items: flex-end; gap: 4px; background: var(--surface); border: 1px solid var(--line); border-radius: 28px; padding: 6px; box-shadow: var(--shadow); }
.input-bar textarea { flex: 1; min-width: 0; resize: none; border: none; outline: none; background: transparent; color: var(--text); font: inherit; padding: 10px 12px; max-height: 140px; }
.input-bar textarea::placeholder { color: var(--muted); }
.mic.on { background: var(--primary); color: var(--on-primary); animation: blink 1s infinite; }
.send { width: 44px; height: 44px; border-radius: 50%; background: var(--primary); color: var(--on-primary); font-size: 1.1rem; flex: none; transition: transform .15s, background .2s; }
.send:active { transform: scale(.92); background: var(--primary-dark); }

/* Paneles laterales */
.panel {
  position: fixed; top: 0; bottom: 0; width: min(300px, 84vw); background: var(--surface);
  z-index: 20; overflow-y: auto; transition: transform .28s ease; box-shadow: var(--shadow);
}
.panel.left { left: 0; transform: translateX(-105%); }
.panel.right { right: 0; transform: translateX(105%); padding: 20px; }
.panel.open { transform: none; }
.panel-header { padding: 28px 24px; background: linear-gradient(135deg, var(--primary), var(--primary-dark)); color: var(--on-primary); }
.panel-header .avatar { font-size: 2.4rem; }
.panel-header h3 { margin-top: 8px; font-size: 1.25rem; }
.panel-header p { opacity: .85; font-size: .85rem; }
.menu { display: flex; flex-direction: column; padding: 8px 0; }
.menu-item { text-align: left; padding: 14px 24px; font-size: 1rem; transition: background .2s; }
.menu-item:hover, .menu-item:active { background: var(--surface-2); }
.panel-title { font-size: 1.25rem; margin-bottom: 12px; }
.setting { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); cursor: pointer; }
.setting input { display: none; }
.toggle { width: 44px; height: 26px; border-radius: 13px; background: var(--line); position: relative; transition: background .2s; flex: none; }
.toggle::after { content: ""; position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: #fff; transition: transform .2s; }
.setting input:checked + .toggle { background: var(--primary); }
.setting input:checked + .toggle::after { transform: translateX(18px); }
.setting-btn { width: 100%; margin-top: 14px; padding: 12px; border-radius: 16px; background: var(--primary); color: var(--on-primary); font-weight: 600; }
.scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, .5); opacity: 0; pointer-events: none; transition: opacity .25s; z-index: 10; }
.scrim.show { opacity: 1; pointer-events: auto; }

/* 🫧 Burbuja flotante */
.bubble {
  position: fixed; right: 16px; bottom: 96px; width: 60px; height: 60px; border-radius: 50%;
  background: var(--primary); color: var(--on-primary); font-size: 1.7rem; z-index: 15;
  box-shadow: 0 8px 22px rgba(0, 0, 0, .45); touch-action: none; display: grid; place-items: center;
}
.bubble[hidden] { display: none; }
.badge {
  position: absolute; top: -2px; right: -2px; min-width: 22px; height: 22px; padding: 0 6px; border-radius: 11px;
  background: #E53935; color: #fff; font-size: .75rem; font-weight: 700; display: grid; place-items: center;
}
.badge[hidden] { display: none; }

/* Ventana de recordatorio y avisos */
.sheet { margin: auto; border: none; border-radius: 22px; padding: 22px; width: min(360px, 92vw); background: var(--surface); color: var(--text); box-shadow: var(--shadow); }
.sheet::backdrop { background: rgba(0, 0, 0, .55); }
.sheet h3 { margin-bottom: 14px; }
.sheet input { width: 100%; margin-bottom: 12px; padding: 12px; border-radius: 14px; border: 1px solid var(--line); background: var(--surface-2); color: var(--text); font: inherit; }
.sheet-actions { display: flex; gap: 10px; align-items: center; }
.sheet-actions .setting-btn { margin-top: 0; }
.ghost { padding: 12px 16px; border-radius: 16px; color: var(--muted); }
.toast {
  position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%); z-index: 30; max-width: 90vw;
  background: var(--surface-2); color: var(--text); padding: 10px 16px; border-radius: 14px; box-shadow: var(--shadow);
}
.toast[hidden] { display: none; }

/* 💬 Mensajería */
.auth { display: flex; flex-direction: column; gap: 10px; max-width: 420px; margin: 16px auto; }
.auth[hidden], .composer[hidden] { display: none; }
.auth h2 { font-size: 1.6rem; }
.auth p { color: var(--muted); }
.auth input { padding: 14px; border-radius: 16px; border: 1px solid var(--line); background: var(--surface); color: var(--text); font: inherit; }
.auth .setting-btn { margin-top: 6px; }
.auth-error { color: #FF5252 !important; font-size: .9rem; min-height: 1em; }
.wide { width: 100%; }
.chats { border-top: 1px solid var(--line); padding-bottom: 16px; }
.pad { padding: 0 24px; }
.muted { color: var(--muted); font-size: .9rem; margin: 10px 0; }
.setting-btn.slim { width: calc(100% - 40px); margin: 0 20px; padding: 10px; }
.chat-item { display: flex; flex-direction: column; gap: 2px; width: 100%; text-align: left; padding: 12px 24px; }
.chat-item:hover, .chat-item:active { background: var(--surface-2); }
.chat-item small { color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }""" + (if (social) """

/* 👥 Red social */
.icon-btn[hidden], .profile[hidden], .pending[hidden] { display: none; }
.feed { display: flex; flex-direction: column; gap: 12px; }
.post { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 14px 6px; box-shadow: var(--shadow); animation: pop .2s ease-out; }
.post-head { display: flex; align-items: center; gap: 10px; }
.post-who { flex: 1; min-width: 0; display: flex; flex-direction: column; text-align: left; }
.post-who small { color: var(--muted); font-size: .78rem; }
.avatar-sm, .avatar-lg { border-radius: 50%; background: var(--primary); color: var(--on-primary); display: grid; place-items: center; font-weight: 700; flex: none; overflow: hidden; cursor: pointer; }
.avatar-sm { width: 40px; height: 40px; }
.avatar-lg { width: 72px; height: 72px; font-size: 1.8rem; }
.avatar-sm img, .avatar-lg img { width: 100%; height: 100%; object-fit: cover; }
.post-text { margin-top: 10px; white-space: pre-wrap; line-height: 1.45; overflow-wrap: anywhere; }
.post-img { display: block; width: 100%; max-height: 420px; object-fit: cover; border-radius: 16px; margin-top: 10px; background: var(--surface-2); }
.post-actions { display: flex; gap: 24px; }
.post-actions button { padding: 10px 0; color: var(--muted); }
.post-actions .liked { color: var(--primary); font-weight: 700; }
.profile { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px; margin-top: 8px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); }
.profile h3 { font-size: 1.3rem; }
.profile .bio { color: var(--muted); white-space: pre-wrap; }
.profile .counts { color: var(--muted); }
.profile .setting-btn { margin-top: 8px; }
.pending { display: block; width: 100%; margin-bottom: 8px; padding: 9px 14px; border-radius: 14px; background: var(--surface-2); text-align: left; font-size: .9rem; }
.sheet textarea { width: 100%; margin-bottom: 12px; padding: 12px; border-radius: 14px; border: 1px solid var(--line); background: var(--surface-2); color: var(--text); font: inherit; resize: vertical; }
.results { display: flex; flex-direction: column; margin-bottom: 12px; max-height: 50vh; overflow-y: auto; color: var(--muted); }
.result { width: 100%; text-align: left; padding: 12px 6px; border-bottom: 1px solid var(--line); color: var(--text); }
.result:hover, .result:active { background: var(--surface-2); }""" else "")
        val js = StringBuilder("""'use strict';
// Pantalla: paneles, envío de mensajes, micrófono y ajustes. Lo propio de la app está en logic.js (App).
const q = (sel) => document.querySelector(sel);
const leftPanel = q('#leftPanel');
const rightPanel = q('#rightPanel');
const scrim = q('#scrim');
const input = q('#input');
const messages = q('#messages');
const welcome = q('#welcome');
const btnMic = q('#btnMic');
// La lógica de la app (logic.js) puede venir como window.App = {...} o como const App = {...}
const getApp = () => (typeof App !== 'undefined' ? App : window.App) || {};

// Ajustes guardados en el navegador
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* sin almacenamiento */ } }
};

function toast(text) {
  const t = q('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 3500);
}
""")
        if (store) js.append("""
// 💾 Datos que quedan guardados (notas, listas, historial…): Store.add('notas', 'Comprar pan') · Store.all('notas')
const Store = {
  all(list) { try { return JSON.parse(localStorage.getItem('datos:' + list) || '[]'); } catch (e) { return []; } },
  set(list, items) { try { localStorage.setItem('datos:' + list, JSON.stringify(items)); } catch (e) { /* lleno */ } },
  add(list, item, max) { const l = Store.all(list); l.push(item); while (max && l.length > max) l.shift(); Store.set(list, l); },
  remove(list, index) { const l = Store.all(list); l.splice(index, 1); Store.set(list, l); },
  clear(list) { try { localStorage.removeItem('datos:' + list); } catch (e) { /* nada */ } },
  clearAll() { try { Object.keys(localStorage).filter((k) => k.startsWith('datos:')).forEach((k) => localStorage.removeItem(k)); } catch (e) { /* nada */ } }
};
""")
        if (notify) js.append("""
// 🔔 Notificaciones: Notify.show('Título', 'Texto') (si no hay permiso, aparece un aviso en la página)
const Notify = {
  enabled() { return store.get('module:notify') === '1'; },
  async ask() {
    if ('Notification' in window && Notification.permission === 'default') {
      try { await Notification.requestPermission(); } catch (e) { /* sin soporte */ }
    }
  },
  show(title, body) {
    if (typeof Bubble !== 'undefined') Bubble.add();
    if (!Notify.enabled()) return;
    if ('Notification' in window && Notification.permission === 'granted') {
      try { new Notification(title, { body }); return; } catch (e) { /* algunos celulares no lo permiten aquí */ }
    }
    toast(title + ': ' + body);
  }
};
""")
        if (net) js.append("""
// 🌐 Datos de internet: await Net.get(url) → texto · await Net.json(url) → objeto (o null si falla)
const Net = {
  async get(url) { try { const r = await fetch(url); return r.ok ? await r.text() : null; } catch (e) { return null; } },
  async json(url) { try { const r = await fetch(url); return r.ok ? await r.json() : null; } catch (e) { return null; } }
};
""")
        if (bubble) js.append("""
// 🫧 Burbuja flotante: cuenta los avisos nuevos; tócala para ir al último mensaje, arrástrala para moverla
const bubbleEl = q('#bubble');
const badgeEl = q('#badge');
let unread = 0;
const Bubble = {
  show(on) { bubbleEl.hidden = !on; },
  add() { unread++; badgeEl.textContent = unread > 9 ? '9+' : String(unread); badgeEl.hidden = false; },
  clear() { unread = 0; badgeEl.hidden = true; }
};
let drag = null;
bubbleEl.addEventListener('pointerdown', (e) => {
  const r = bubbleEl.getBoundingClientRect();
  drag = { x: e.clientX, y: e.clientY, left: r.left, top: r.top, moved: false };
  bubbleEl.setPointerCapture(e.pointerId);
});
bubbleEl.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const dx = e.clientX - drag.x;
  const dy = e.clientY - drag.y;
  if (Math.abs(dx) + Math.abs(dy) > 6) drag.moved = true;
  bubbleEl.style.left = (drag.left + dx) + 'px';
  bubbleEl.style.top = (drag.top + dy) + 'px';
  bubbleEl.style.right = 'auto';
  bubbleEl.style.bottom = 'auto';
});
bubbleEl.addEventListener('pointerup', () => {
  if (drag && !drag.moved) {
    Bubble.clear();
    const last = messages.lastElementChild;
    if (last) last.scrollIntoView({ behavior: 'smooth', block: 'end' });
    input.focus();
  }
  drag = null;
});
document.addEventListener('visibilitychange', () => { if (!document.hidden) Bubble.clear(); });
Bubble.show(store.get('module:bubble') === '1');
""")
        js.append("""
function openPanel(panel) { closePanels(); panel.classList.add('open'); scrim.classList.add('show'); }
function closePanels() { leftPanel.classList.remove('open'); rightPanel.classList.remove('open'); scrim.classList.remove('show'); }
q('#btnMenu').addEventListener('click', () => openPanel(leftPanel));
q('#btnSettings').addEventListener('click', () => openPanel(rightPanel));
scrim.addEventListener('click', closePanels);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePanels(); });

/** Muestra un mensaje en la conversación (y lo guarda si así se pide). */
function addMessage(text, who, save) {
  welcome.hidden = true;
  const el = document.createElement('div');
  el.className = 'msg ' + who;
  el.textContent = text;
  messages.appendChild(el);
  el.scrollIntoView({ behavior: 'smooth', block: 'end' });
  if (save) remember(text, who);
  return el;
}

function remember(text, who) {""" + (if (store) "\n  Store.add('chat', { text, who: who.split(' ')[0] }, 300);" else "") + """
}

async function runLogic(fn) {
  const el = addMessage('', 'bot typing', false);
  try {
    const r = await fn();
    if (r === null || r === undefined || r === '') { el.remove(); return; }
    el.classList.remove('typing');
    el.textContent = String(r);
    remember(String(r), 'bot');""" + (if (notify) "\n    if (document.hidden) Notify.show(document.title, String(r));" else if (bubble) "\n    if (document.hidden) Bubble.add();" else "") + """
  } catch (err) {
    el.classList.remove('typing');
    el.textContent = '⚠️ ' + (err && err.message ? err.message : err);
  }
}

function send(textIn) {
  const text = (textIn !== undefined ? textIn : input.value).trim();""" + (if (social) """
  if (typeof SocialUI !== 'undefined' && SocialUI.onSend(text, textIn === undefined)) { input.value = ''; autoSize(); return; }   // 👥 publica o comenta""" else "") + """
  if (!text) return;
  input.value = '';
  autoSize();""" + (if (chat) """
  if (typeof openChat !== 'undefined' && openChat) { Messenger.send(openChat.id, text); return; }   // 💬 va a la otra persona""" else "") + """
  addMessage(text, 'user', true);
  runLogic(() => (getApp().reply ? getApp().reply(text) : 'Listo.'));
}

function autoSize() { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 140) + 'px'; }
input.addEventListener('input', autoSize);
input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
q('#btnSend').addEventListener('click', () => send());

document.querySelectorAll('[data-send]').forEach((b) => b.addEventListener('click', () => send(b.dataset.send)));
// 🔘 Botones que le fuiste agregando a la app: cada uno hace lo que dice botones.js
// (el resultado sale en un aviso: así la pantalla de inicio con tus botones sigue a la vista)
document.querySelectorAll('[data-boton]').forEach((b) => b.addEventListener('click', async () => {
  const action = (window.AppButtons || {})[b.dataset.boton];
  try {
    const r = action && action.run ? await action.run() : 'Este botón todavía no tiene su lógica.';
    if (r !== null && r !== undefined && r !== '') toast(String(r));
  } catch (err) { toast('⚠️ ' + (err && err.message ? err.message : err)); }
}));
document.querySelectorAll('[data-module]').forEach((b) => b.addEventListener('click', () => onModuleAction(b.dataset.module)));
document.querySelectorAll('[data-menu]').forEach((b) => b.addEventListener('click', () => {
  closePanels();
  runLogic(() => (getApp().onMenu ? getApp().onMenu(b.dataset.menu) : null));
}));

// ⚙️ Ajustes: se recuerdan en el navegador
document.querySelectorAll('[data-setting]').forEach((box) => {
  const key = box.dataset.setting;
  box.checked = store.get(key) === '1';
  box.addEventListener('change', () => {
    store.set(key, box.checked ? '1' : '0');
    if (key.startsWith('module:')) onModuleSwitch(key, box.checked);
    else runLogic(() => (getApp().onSetting ? getApp().onSetting(key, box.checked) : null));
  });
});
document.querySelectorAll('[data-action]').forEach((b) => b.addEventListener('click', () => {
  closePanels();
  const key = b.dataset.action;
  if (key.startsWith('module:')) onModuleAction(key);
  else runLogic(() => (getApp().onSetting ? getApp().onSetting(key, true) : null));
}));

/** Interruptores de los módulos (burbuja, notificaciones). */
function onModuleSwitch(key, on) {""" +
            (if (bubble) "\n  if (key === 'module:bubble') Bubble.show(on);" else "") +
            (if (notify) "\n  if (key === 'module:notify' && on) Notify.ask();" else "") + """
}

/** Tarjetas y botones de los módulos (foto, recordatorio, borrar datos). */
function onModuleAction(key) {""" +
            (if (camera) "\n  if (key === 'module:camera') q('#photoInput').click();" else "") +
            (if (remind) "\n  if (key === 'module:remind') pickReminder();" else "") +
            (if (store) "\n  if (key === 'module:clear') { Store.clearAll(); messages.innerHTML = ''; welcome.hidden = false; toast('🗑️ Datos borrados'); }" else "") +
            (if (account) "\n  if (key === 'module:logout') Account.signOut();   // al salir se limpia todo (mira «CUENTA»)" else "") + """
}
""")
        if (camera) js.append("""
// 📷 Foto: con la cámara del celular o desde la galería
const photoInput = q('#photoInput');
q('#btnCamera').addEventListener('click', () => photoInput.click());
photoInput.addEventListener('change', () => {
  const file = photoInput.files && photoInput.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = async () => {""" + (if (chat) """
    if (typeof openChat !== 'undefined' && openChat) { Messenger.send(openChat.id, '📷', await shrinkImage(reader.result, 640)); return; }""" else "") +
            (if (social) """
    if (await SocialUI.onPhoto(reader.result)) return;   // 👥 foto para publicar o de perfil""" else "") + """
    welcome.hidden = true;
    const el = document.createElement('div');
    el.className = 'msg user img';
    const img = document.createElement('img');
    img.src = reader.result;
    img.alt = 'Foto';
    el.appendChild(img);
    messages.appendChild(el);
    el.scrollIntoView({ behavior: 'smooth', block: 'end' });
    runLogic(() => (getApp().onPhoto ? getApp().onPhoto(reader.result) : '📷 Foto recibida.'));
  };
  reader.readAsDataURL(file);
  photoInput.value = '';
});
""")
        if (remind) js.append("""
// ⏰ Recordatorios: Reminders.schedule(fechaEnMilisegundos, 'texto') (avisan mientras la página está abierta)
const remindDialog = q('#remindDialog');
const Reminders = {
  load() { try { return JSON.parse(localStorage.getItem('recordatorios') || '[]'); } catch (e) { return []; } },
  save(list) { try { localStorage.setItem('recordatorios', JSON.stringify(list)); } catch (e) { /* lleno */ } },
  schedule(at, text) {
    const list = Reminders.load().filter((r) => r.at > Date.now());
    list.push({ at, text });
    Reminders.save(list);
    Reminders.arm(at, text);
  },
  arm(at, text) {
    const ms = at - Date.now();
    if (ms < 0 || ms > 2147483647) return;
    setTimeout(() => {
      store.set('module:notify', '1');
      Notify.show('⏰ Recordatorio', text);
      addMessage('⏰ ' + text, 'bot', true);
    }, ms);
  }
};
Reminders.load().forEach((r) => Reminders.arm(r.at, r.text));

function pickReminder() {
  q('#remindText').value = input.value.trim();
  const d = new Date(Date.now() + 5 * 60000);
  q('#remindTime').value = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  if (remindDialog.showModal) remindDialog.showModal(); else remindDialog.setAttribute('open', '');
}
q('#remindCancel').addEventListener('click', () => remindDialog.close());
q('#remindSave').addEventListener('click', () => {
  const parts = (q('#remindTime').value || '').split(':').map(Number);
  if (parts.length < 2 || isNaN(parts[0])) return;
  const at = new Date();
  at.setHours(parts[0], parts[1], 0, 0);
  if (at <= new Date()) at.setDate(at.getDate() + 1);
  const text = q('#remindText').value.trim() || 'Recordatorio';
  Reminders.schedule(at.getTime(), text);
  remindDialog.close();
  input.value = '';
  store.set('module:notify', '1');
  Notify.ask();
  addMessage('⏰ ' + String(parts[0]).padStart(2, '0') + ':' + String(parts[1]).padStart(2, '0') + ' · ' + text, 'bot', true);
});
""")
        if (chat) js.append("""
// ===================== 💬 MENSAJERÍA (Firebase) =====================
const seenAt = {};
let openChat = null;
let stopMessages = null;
let stopChats = null;
let chatsLoaded = false;

function renderChats(chats) {
  const box = q('#chatList');
  box.innerHTML = '';
  q('#noChats').hidden = chats.length > 0;
  chats.forEach((c) => {
    const item = document.createElement('button');
    item.className = 'chat-item';
    const name = document.createElement('strong');
    name.textContent = '👤 ' + c.otherName;
    const last = document.createElement('small');
    last.textContent = c.lastText || ' ';
    item.append(name, last);
    item.addEventListener('click', () => { closePanels(); openChatView(c); });
    box.appendChild(item);
    const seen = seenAt[c.id];
    const fromOther = c.lastFrom && c.lastFrom !== Account.uid();
    if (chatsLoaded && fromOther && (seen === undefined || c.lastAt > seen) && (!openChat || openChat.id !== c.id || document.hidden)) {
      Notify.show(c.otherName, c.lastText);
    }
    seenAt[c.id] = c.lastAt;
  });
  chatsLoaded = true;
}

function openChatView(c) {""" + (if (social) """
  SocialUI.leave();""" else "") + """
  openChat = c;
  q('.title h1').textContent = c.otherName;
  q('.title p').textContent = 'Chat · mensajes en tiempo real';
  welcome.hidden = true;
  messages.innerHTML = '';
  if (stopMessages) stopMessages();
  stopMessages = Messenger.listenMessages(c.id, (list) => {
    messages.innerHTML = '';
    list.forEach((m) => {
      if (m.image) {
        const el = document.createElement('div');
        el.className = 'msg img ' + (m.fromMe ? 'user' : 'bot');
        const img = document.createElement('img');
        img.src = m.image;
        img.alt = 'Foto';
        el.appendChild(img);
        messages.appendChild(el);
      }
      if (m.text && !(m.image && m.text === '📷')) {
        const el = document.createElement('div');
        el.className = 'msg ' + (m.fromMe ? 'user' : 'bot');
        el.textContent = m.text;
        messages.appendChild(el);
      }
    });
    const lastEl = messages.lastElementChild;
    if (lastEl) lastEl.scrollIntoView({ block: 'end' });
  });
}

function closeChat() {
  openChat = null;
  if (stopMessages) stopMessages();
  stopMessages = null;
  q('.title h1').textContent = appTitle;
  q('.title p').textContent = appSubtitle;
  messages.innerHTML = '';
  welcome.hidden = false;""" + (if (social) """
  SocialUI.home();""" else "") + """
}

// Tocar el título (o Escape) vuelve al inicio
q('.title').addEventListener('click', () => { if (openChat) closeChat(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && openChat && !leftPanel.classList.contains('open') && !rightPanel.classList.contains('open')) closeChat();
});

const newChatDialog = q('#newChatDialog');
q('#btnNewChat').addEventListener('click', () => {
  closePanels();
  q('#newChatEmail').value = '';
  if (newChatDialog.showModal) newChatDialog.showModal(); else newChatDialog.setAttribute('open', '');
});
q('#newChatCancel').addEventListener('click', () => newChatDialog.close());
q('#newChatOk').addEventListener('click', async () => {
  const email = q('#newChatEmail').value.trim();
  if (!email) return;
  newChatDialog.close();
  try { openChatView(await Messenger.startChatWith(email)); } catch (e) { toast('⚠️ ' + e.message); }
});
""")
        if (social) js.append(socialUiJs(chat))
        if (account) js.append("""
// ===================== 👤 CUENTA (Firebase) =====================
const appTitle = q('.title h1').textContent;
const appSubtitle = q('.title p').textContent;
const authBox = q('#authSection');
const authError = q('#authError');

function showAuth(message) {
  authBox.hidden = false;
  q('.composer').hidden = true;   // sin cuenta no se escribe
  welcome.hidden = true;
  messages.innerHTML = '';
  authError.textContent = message || '';
}

function hideAuth() {
  authBox.hidden = true;
  q('.composer').hidden = false;
  if (""" + (if (chat) "!openChat && " else "") + """!messages.children.length) welcome.hidden = false;
}

if (!Account.ready) {
  showAuth('⚙️ Falta conectar Firebase: pega los datos de tu proyecto en firebase-config.js (mira LEEME_FIREBASE.md).');
} else {
  Account.onAuth((user) => {
    if (user) {
      hideAuth();""" + (if (chat) """
      if (stopChats) stopChats();
      stopChats = Messenger.listenChats(renderChats);""" else "") + (if (social) """
      SocialUI.start();""" else "") + """
    } else {""" + (if (chat) """
      if (stopChats) stopChats();
      stopChats = null;
      chatsLoaded = false;
      closeChat();
      q('#chatList').innerHTML = '';""" else "") + (if (social) """
      SocialUI.stop();""" else "") + """
      showAuth('');
    }
  });
  q('#btnLogin').addEventListener('click', async () => {
    try { await Account.signIn(q('#authEmail').value, q('#authPass').value); } catch (e) { authError.textContent = '⚠️ ' + e.message; }
  });
  q('#btnRegister').addEventListener('click', async () => {
    const name = q('#authName').value.trim();
    if (!name) { authError.textContent = 'Escribe tu nombre para crear la cuenta.'; return; }
    try { await Account.signUp(name, q('#authEmail').value, q('#authPass').value); } catch (e) { authError.textContent = '⚠️ ' + e.message; }
  });
}

/** Achica una foto (para guardarla dentro de los datos). */
function shrinkImage(dataUrl, max) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * k);
      c.height = Math.round(img.height * k);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      resolve(c.toDataURL('image/jpeg', 0.7));
    };
    img.onerror = () => resolve(dataUrl);
    img.src = dataUrl;
  });
}
""")
        if (store) js.append("""
// 💾 La conversación guardada vuelve a aparecer al abrir la página
Store.all('chat').forEach((m) => { if (m && m.text) addMessage(m.text, m.who === 'user' ? 'user' : 'bot', false); });
""")
        js.append("""
// 🎤 Dictado por voz (Chrome y Android)
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (Recognition) {
  const rec = new Recognition();
  rec.lang = 'es-ES';
  rec.interimResults = true;
  let listening = false;
  rec.onstart = () => { listening = true; btnMic.classList.add('on'); };
  rec.onend = () => { listening = false; btnMic.classList.remove('on'); };
  rec.onerror = () => { listening = false; btnMic.classList.remove('on'); };
  rec.onresult = (e) => {
    const text = Array.from(e.results).map((r) => r[0].transcript).join('');
    input.value = text;
    autoSize();
    if (e.results[e.results.length - 1].isFinal""" + (if (social) " && !SocialUI.feedVisible()" else "") + """) send();
  };
  btnMic.addEventListener('click', () => { if (listening) rec.stop(); else rec.start(); });
} else {
  btnMic.addEventListener('click', () => addMessage('🎤 Este navegador no permite dictado por voz.', 'bot', false));
}
""")
        val files = mutableListOf("index.html" to html, "style.css" to css + styleCss())
        if (account) {
            files += "firebase-config.js" to """// 🔥 Datos de tu proyecto de Firebase (mira LEEME_FIREBASE.md).
// Firebase → Configuración del proyecto → Tus apps → Web → copia aquí los valores.
window.FIREBASE_CONFIG = {
  apiKey: "PEGA_AQUI_TU_API_KEY",
  authDomain: "tu-proyecto.firebaseapp.com",
  projectId: "tu-proyecto",
  appId: "PEGA_AQUI_TU_APP_ID"
};"""
            files += "account.js" to accountJs()
            if (chat) files += "messenger.js" to messengerJs()
            if (social) files += "social.js" to socialJs()
            files += "firestore.rules" to firestoreRules(chat, social)
            files += "LEEME_FIREBASE.md" to firebaseGuide("", web = true, chat = chat, social = social)
        }
        files += "botones.js" to botonesJs(emptyList())
        files += "script.js" to js.toString()
        return files
    }

    // ======================================================================
    // 💬 CHAT LIMPIO PARA LA WEB (apps web y páginas web), igual que en Android y que Cumulus:
    //    arriba ☰ y ⚙️ · el saludo de la IA con 📋 ↗️ ⬇️ · abajo el cuadro para escribir con 🎤 y ➤.
    //    Menú y ajustes vacíos: los botones (píldoras) se agregan en la pantalla, en ☰ o en ⚙️.
    //    [withLogic] = incluye logic.js (las páginas no lo traen aparte).
    // ======================================================================
    fun cleanWebShell(title: String, pal: Palette, withLogic: Boolean): List<Pair<String, String>> {
        val svgMenu = "<svg viewBox=\"0 0 24 24\"><path d=\"M3,18h13v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z\"/></svg>"
        val svgGear = "<svg viewBox=\"0 0 24 24\"><path d=\"M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z\"/></svg>"
        val svgMic = "<svg viewBox=\"0 0 24 24\"><path d=\"M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z\"/></svg>"
        val svgSend = "<svg viewBox=\"0 0 24 24\"><path d=\"M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z\"/></svg>"
        val html = """<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <!-- interactive-widget=resizes-content: en Chrome de Android la página se achica al abrir el teclado,
       así la barra del chat sube y queda justo encima (como adjustResize en Android) -->
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content">
  <title>${h(title)}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <!-- Arriba: ☰ menú y ⚙️ ajustes (como Cumulus) -->
  <header class="topbar">
    <button class="icon-btn" id="btnMenu" aria-label="Menú">$svgMenu</button>
    <span class="app-title" hidden>${h(title)}</span>
    <button class="icon-btn" id="btnSettings" aria-label="Ajustes">$svgGear</button>
  </header>

  <!-- La conversación (empieza con el saludo de la IA) -->
  <main class="chat" id="chat">
    <div class="messages" id="messages"></div>
  </main>

  <!-- 🔘 Tus botones (píldoras) y las partes que le vas agregando -->
  <div class="my-buttons" id="myButtons">
    <p class="label" id="myButtonsTitle" hidden>🔘 Tus botones</p>
    <!-- BOTONES -->
    <!-- PARTES -->
  </div>

  <!-- Barra de escritura: el texto arriba · 🎤 y ➤ abajo a la derecha -->
  <form class="composer" id="composer" autocomplete="off">
    <textarea id="input" rows="1" placeholder="Pregúntame..."></textarea>
    <div class="composer-row">
      <button type="button" class="icon-btn mic" id="btnMic" aria-label="Hablar">$svgMic</button>
      <button type="submit" class="send" id="btnSend" aria-label="Enviar">$svgSend</button>
    </div>
  </form>

  <!-- Paneles: ☰ menú (izquierda) y ⚙️ ajustes (derecha), vacíos hasta que les agregues algo -->
  <div class="scrim" id="scrim" hidden></div>
  <aside class="panel left" id="panelLeft" aria-label="Menú">
    <nav class="panel-body">
      <h2>Menú</h2>
      <!-- BOTONES_MENU -->
    </nav>
  </aside>
  <aside class="panel right" id="panelRight" aria-label="Ajustes">
    <div class="panel-body">
      <h2>Ajustes</h2>
      <!-- BOTONES_AJUSTES -->
    </div>
  </aside>

  <div class="toast-msg" id="toastMsg"></div>
  <script src="logic.js"></script>
  <script src="botones.js"></script>
  <script src="script.js"></script>
</body>
</html>
"""
        val css = """:root {
  --primary: ${pal.primary};
  --on-primary: ${pal.onPrimary};
  --bg: ${pal.background};
  --surface: ${pal.surface};
  --surface-variant: ${pal.surfaceVariant};
  --text: ${pal.textPrimary};
  --text-2: ${pal.textSecondary};
  --divider: ${pal.divider};
  --panel: ${pal.panel.ifEmpty { pal.surface }};
  --panel-text: ${pal.panelText.ifEmpty { pal.textPrimary }};
  --icons: ${pal.icons.ifEmpty { pal.textPrimary }};
  --bar: ${pal.bar.ifEmpty { pal.surface }};
  --bar-text: ${pal.barText.ifEmpty { pal.textPrimary }};
  --bar-hint: ${pal.barHint.ifEmpty { pal.textSecondary }};
}
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin: 0; height: 100%; }
body { background: var(--bg); color: var(--text); font-family: system-ui, -apple-system, Roboto, "Segoe UI", sans-serif;
  display: flex; flex-direction: column; max-width: 760px; margin: 0 auto; }
button { font: inherit; cursor: pointer; }
svg { width: 26px; height: 26px; fill: currentColor; display: block; }

/* Arriba: ☰ y ⚙️ */
.topbar { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; }
.topbar .icon-btn { color: var(--icons); }
.icon-btn { background: none; border: none; color: var(--text); padding: 8px; border-radius: 50%; }
.icon-btn:active { background: var(--surface); }

/* Conversación: la IA sin globo (como Cumulus) y tú con globo del color de la app */
.chat { flex: 1; overflow-y: auto; padding: 8px 18px; }
.messages { display: flex; flex-direction: column; gap: 14px; }
.msg { max-width: 88%; font-size: 17px; line-height: 1.45; white-space: pre-wrap; word-wrap: break-word; }
.msg.user { align-self: flex-end; background: var(--primary); color: var(--on-primary); padding: 10px 14px; border-radius: 20px 20px 6px 20px; }
.msg.ai { align-self: flex-start; }
.msg img { max-width: 220px; border-radius: 14px; display: block; margin-bottom: 6px; }
.actions { display: flex; gap: 14px; margin-top: 8px; }
.actions button { background: none; border: none; color: var(--text-2); padding: 4px; }
.actions svg { width: 22px; height: 22px; }

/* 🔘 Píldoras (como los botones de Cumulus) */
.my-buttons { padding: 0 12px; }
.my-buttons:empty { display: none; }
.label { color: var(--text-2); font-size: 13px; font-weight: 700; margin: 6px 4px; }
.my-btn { display: block; width: 100%; height: 56px; margin: 0 0 10px; border: none; border-radius: 999px;
  background: var(--surface-variant); color: var(--primary); font-weight: 800; font-size: 15px;
  letter-spacing: .1em; text-transform: uppercase; }
.my-btn:active { transform: scale(.98); filter: brightness(1.1); }

/* Barra de escritura: cuadro grande redondeado */
.composer { margin: 10px 12px calc(12px + env(safe-area-inset-bottom)); background: var(--bar);
  border: 1px solid var(--divider); border-radius: 28px; padding: 10px 10px 8px 16px; }
.composer textarea { width: 100%; background: none; border: none; outline: none; resize: none; color: var(--bar-text);
  font: inherit; font-size: 17px; min-height: 44px; max-height: 140px; padding: 6px 0; }
.composer textarea::placeholder { color: var(--bar-hint); }
.composer-row { display: flex; justify-content: flex-end; align-items: center; gap: 8px; }
.mic { color: var(--bar-hint); }
.mic.on { color: var(--primary); }
.send { width: 52px; height: 52px; border-radius: 50%; border: none; background: var(--primary); color: var(--on-primary);
  display: flex; align-items: center; justify-content: center; }

/* Paneles ☰ y ⚙️ */
.scrim { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 9; }
.panel { position: fixed; top: 0; bottom: 0; width: min(300px, 82vw); background: var(--panel); color: var(--panel-text); z-index: 10;
  transition: transform .25s ease; overflow-y: auto; }
.panel.left { left: 0; transform: translateX(-100%); }
.panel.right { right: 0; transform: translateX(100%); }
.panel.open { transform: none; }
.panel-body { padding: 24px 16px; }
.panel h2 { margin: 0 0 14px; font-size: 20px; }

/* Avisos */
.toast-msg { position: fixed; left: 50%; bottom: 120px; transform: translateX(-50%) translateY(20px); opacity: 0;
  background: var(--surface-variant); color: var(--text); padding: 10px 16px; border-radius: 14px; max-width: 86%;
  transition: all .25s; pointer-events: none; z-index: 20; }
.toast-msg.show { opacity: 1; transform: translateX(-50%); }
"""
        val js = """'use strict';
// 💬 Chat limpio: ☰ y ⚙️ arriba, la conversación y la barra para escribir. Lo propio de la app va en logic.js.
const q = (s) => document.querySelector(s);
const input = q('#input');
const ICON_COPY = '<svg viewBox="0 0 24 24"><path d="M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"/></svg>';
const ICON_SHARE = '<svg viewBox="0 0 24 24"><path d="M14,9V5l7,7 -7,7v-4.1c-5,0 -8.5,1.6 -11,5.1 1,-5 4,-10 11,-11z"/></svg>';
const ICON_SAVE = '<svg viewBox="0 0 24 24"><path d="M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"/></svg>';
const messages = q('#messages');

window.toast = function (text) {
  const t = q('#toastMsg');
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(window.toast.timer);
  window.toast.timer = setTimeout(() => t.classList.remove('show'), 3500);
};
const toast = window.toast;
function getApp() { return window.App || {}; }

// Un mensaje en la conversación (las respuestas llevan 📋 copiar · ↗️ compartir · ⬇️ guardar)
function showMessage(text, who, image) {
  const box = document.createElement('div');
  box.className = 'msg ' + (who === 'user' ? 'user' : 'ai');
  if (image) { const img = document.createElement('img'); img.src = image; box.appendChild(img); }
  if (text) box.appendChild(document.createTextNode(String(text)));
  messages.appendChild(box);
  if (who !== 'user' && text) {
    const row = document.createElement('div');
    row.className = 'actions';
    const copy = document.createElement('button'); copy.innerHTML = ICON_COPY; copy.title = 'Copiar';
    copy.onclick = async () => { try { await navigator.clipboard.writeText(String(text)); toast('📋 Copiado'); } catch (e) { toast('No se pudo copiar'); } };
    const share = document.createElement('button'); share.innerHTML = ICON_SHARE; share.title = 'Compartir';
    share.onclick = async () => {
      if (navigator.share) { try { await navigator.share({ text: String(text) }); } catch (e) {} }
      else { try { await navigator.clipboard.writeText(String(text)); toast('Copiado para compartir'); } catch (e) {} }
    };
    const save = document.createElement('button'); save.innerHTML = ICON_SAVE; save.title = 'Guardar';
    save.onclick = () => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([String(text)], { type: 'text/plain' }));
      a.download = 'respuesta.txt';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    };
    row.append(copy, share, save);
    box.appendChild(row);
  }
  q('#chat').scrollTop = q('#chat').scrollHeight;
}
window.addMessage = (text) => showMessage(text, 'ai');   // (los botones pueden escribir en el chat)

async function runLogic(fn) {
  try {
    const r = await fn();
    if (r !== null && r !== undefined && r !== '') showMessage(r, 'ai');
  } catch (e) { showMessage('⚠️ ' + (e && e.message ? e.message : e), 'ai'); }
}

function send(textIn) {
  const text = (textIn !== undefined ? textIn : input.value).trim();
  if (!text) return;
  input.value = '';
  autoSize();
  showMessage(text, 'user');
  runLogic(() => (getApp().reply ? getApp().reply(text) : 'Listo.'));
}
function autoSize() { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 140) + 'px'; }
input.addEventListener('input', autoSize);
input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
q('#composer').addEventListener('submit', (e) => { e.preventDefault(); send(); });

// ☰ y ⚙️
function closePanels() { q('#panelLeft').classList.remove('open'); q('#panelRight').classList.remove('open'); q('#scrim').hidden = true; }
q('#btnMenu').addEventListener('click', () => { q('#panelLeft').classList.add('open'); q('#scrim').hidden = false; });
q('#btnSettings').addEventListener('click', () => { q('#panelRight').classList.add('open'); q('#scrim').hidden = false; });
q('#scrim').addEventListener('click', closePanels);

// 🔘 Botones que le vas agregando (en la pantalla, en ☰ o en ⚙️): cada uno hace lo que dice botones.js
document.querySelectorAll('[data-boton]').forEach((b) => b.addEventListener('click', async () => {
  closePanels();
  const action = (window.AppButtons || {})[b.dataset.boton];
  try {
    const r = action && action.run ? await action.run() : 'Este botón todavía no tiene su lógica.';
    if (r !== null && r !== undefined && r !== '') toast(String(r));
  } catch (err) { toast('⚠️ ' + (err && err.message ? err.message : err)); }
}));

// 🎤 Dictado por voz (si el navegador lo permite)
const Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
q('#btnMic').addEventListener('click', () => {
  if (!Rec) { toast('Tu navegador no permite dictar por voz'); return; }
  const r = new Rec();
  r.lang = 'es-ES';
  r.interimResults = false;
  q('#btnMic').classList.add('on');
  r.onresult = (e) => { input.value = e.results[0][0].transcript; autoSize(); };
  r.onend = () => q('#btnMic').classList.remove('on');
  r.start();
});

// 💬 Saludo de la IA al abrir
showMessage('¡Hola! ¿En qué te puedo ayudar?', 'ai');
"""
        val logic = """// Lógica de la app: responde a los mensajes.
window.App = {
  async reply(text) {
    const t = text.toLowerCase();
    if (t.includes('hola')) return '¡Hola! ¿En qué te ayudo?';
    return 'Entendido: «' + text + '».';
  }
};"""
        val files = mutableListOf("index.html" to html, "style.css" to css)
        if (withLogic) files += "logic.js" to logic
        files += "botones.js" to botonesJs(emptyList())
        files += "script.js" to js
        return files
    }

    /**
     * 📱 Maqueta del CHAT LIMPIO de Android (maqueta.js): la escribe la app, NO la IA, así siempre funciona igual.
     * Dibuja el saludo «¡Hola! ¿En qué te puedo ayudar?» con 📋 ↗ ⬇, y al escribir: tu mensaje a la derecha y la respuesta
     * de la IA a la izquierda (sin globo, con sus íconos). 🎤 y ➤ abajo a la derecha, como en la app real.
     * La marca «maqueta-base» hace que la maqueta use SOLO esta lógica (se ignoran los maqueta.js anteriores del chat).
     */
    fun cleanMockupJs(): String = """// 🧪 maqueta-base: chat limpio (lo escribe la app, no la IA)
(function () {
  var ICON_COPY = '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"/></svg>';
  var ICON_SHARE = '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M14,9V5l7,7 -7,7v-4.1c-5,0 -8.5,1.6 -11,5.1 1,-5 4,-10 11,-11z"/></svg>';
  var ICON_SAVE = '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"/></svg>';
  var ICON_MIC = '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"/></svg>';
  var ICON_SEND = '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"/></svg>';
  var ICON_MENU = '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M3,18h13v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z"/></svg>';
  var ICON_GEAR = '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"/></svg>';
  function q(id) { return document.querySelector('[data-id="' + id + '"]'); }
  var list = q('list_messages'), input = q('input_message'), send = q('btn_send'), mic = q('btn_mic');
  if (!list || !input || !send) return;
  function color(id, prop, def) { var e = q(id); return e ? getComputedStyle(e)[prop] : def; }
  var textColor = color('tv_welcome', 'color', '#F2F4F8');
  var softColor = color('tv_welcome_sub', 'color', '#9AA3B2');

  // 🧹 Se ve como la app: sin tarjetas de bienvenida, solo la conversación
  var welcome = q('welcome_section');
  if (welcome) welcome.style.display = 'none';
  list.innerHTML = '';
  list.style.display = 'flex';
  list.style.flexDirection = 'column';
  list.style.gap = '14px';
  list.style.overflow = 'auto';
  list.style.flex = '1';
  list.style.minHeight = '0';
  list.style.padding = '12px 16px';
  var host = list.parentNode;
  if (host) { host.style.flex = '1'; host.style.minHeight = '0'; host.style.display = 'flex'; host.style.flexDirection = 'column'; }

  // ☰ ⚙️ 🎤 ➤ con los mismos íconos de la app (🎤 y ➤ abajo a la derecha)
  var menu = q('btn_menu'), gear = q('btn_settings');
  if (menu) { menu.innerHTML = ICON_MENU; menu.dataset.h = '1'; }
  if (gear) { gear.innerHTML = ICON_GEAR; gear.dataset.h = '1'; }
  send.innerHTML = ICON_SEND;
  if (mic) { mic.innerHTML = ICON_MIC; if (mic.parentNode) mic.parentNode.style.justifyContent = 'flex-end'; }

  function addMessage(text, who) {
    var box = document.createElement('div');
    box.style.maxWidth = '85%';
    box.style.fontSize = '16px';
    box.style.lineHeight = '1.45';
    box.style.whiteSpace = 'pre-wrap';
    box.style.wordWrap = 'break-word';
    if (who === 'user') {
      // 💬 Tu mensaje: a la derecha, en un globo del color de la app
      box.style.alignSelf = 'flex-end';
      box.style.background = getComputedStyle(send).backgroundColor;
      box.style.color = getComputedStyle(send).color;
      box.style.padding = '8px 14px';
      box.style.borderRadius = '20px 20px 6px 20px';
      box.textContent = text;
    } else {
      // 🤖 La IA: a la izquierda, sin globo, con copiar · compartir · descargar
      box.style.alignSelf = 'flex-start';
      box.style.color = textColor;
      var t = document.createElement('div');
      t.textContent = text;
      box.appendChild(t);
      var row = document.createElement('div');
      row.style.display = 'flex';
      row.style.gap = '18px';
      row.style.marginTop = '10px';
      [[ICON_COPY, '📋 Copiado'], [ICON_SHARE, '↗️ En la app se abre el menú para compartir'],
       [ICON_SAVE, '⬇️ En la app se guarda en Descargas']].forEach(function (b) {
        var btn = document.createElement('button');
        btn.innerHTML = b[0];
        btn.style.cssText = 'background:none;border:none;padding:0;cursor:pointer;display:flex;color:' + softColor;
        btn.onclick = function (ev) {
          ev.stopPropagation();
          if (b[0] === ICON_COPY && navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
          aviso(b[1]);
        };
        row.appendChild(btn);
      });
      box.appendChild(row);
    }
    list.appendChild(box);
    list.scrollTop = list.scrollHeight;
  }

  function reply(text) {
    var t = text.toLowerCase();
    if (t.indexOf('hola') >= 0) return '¡Hola! ¿En qué te ayudo?';
    return 'Entendido: «' + text + '». (En la app real aquí responde tu IA.)';
  }
  function sendNow() {
    var text = (input.value || '').trim();
    if (!text) { aviso('✍️ Escribe algo primero'); return; }
    input.value = '';
    addMessage(text, 'user');
    setTimeout(function () { addMessage(reply(text), 'ai'); }, 350);
  }
  alClick('btn_send', sendNow);
  input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); sendNow(); } });
  if (mic) alClick('btn_mic', function () { aviso('🎤 En la app real te escucha y escribe lo que dices'); });

  // 🪟 Botones que abren ventanas: menú emergente, lista para elegir (con ✓) o ventana nueva (con ← para volver)
  var VENT = window.VENTANAS || [];
  var chosen = {};
  var drawer = q('right_drawer') || q('left_drawer');
  var panelBg = drawer ? getComputedStyle(drawer).backgroundColor : '#171A21';
  function closeOverlay() { var o = document.getElementById('mq_overlay'); if (o) o.remove(); }
  function openWin(w) {
    if (w.kind === 'pagina') {
      var k = NAMES.indexOf(w.layout);
      if (k >= 0) show(k); else aviso('🪟 ' + w.title);
      return;
    }
    closeOverlay();
    var phone = document.querySelector('.phone');
    var ov = document.createElement('div');
    ov.id = 'mq_overlay';
    ov.style.cssText = 'position:absolute;inset:0;background:#0009;z-index:30;display:flex;align-items:center;justify-content:center';
    ov.onclick = function (e) { if (e.target === ov) closeOverlay(); };
    var card = document.createElement('div');
    card.style.cssText = 'background:' + panelBg + ';color:' + textColor + ';border-radius:24px;padding:10px;width:86%;max-height:80%;overflow:auto';
    w.options.forEach(function (opt) {
      var row;
      if (opt === '---') {
        row = document.createElement('div');
        row.style.cssText = 'height:1px;background:#ffffff22;margin:6px 12px';
      } else if (opt.indexOf('[s]') === 0) {
        row = document.createElement('label');
        row.className = 'swr';
        row.style.cssText = 'padding:4px 12px;font-size:17px';
        var sp = document.createElement('span'); sp.textContent = opt.slice(3).trim();
        var ck = document.createElement('input'); ck.type = 'checkbox'; ck.className = 'swi';
        ck.onclick = function (e) { e.stopPropagation(); aviso(ck.checked ? '✅ Activado' : '⬜ Desactivado'); };
        row.appendChild(sp); row.appendChild(ck);
      } else {
        row = document.createElement('div');
        row.style.cssText = 'padding:14px 12px;font-size:17px;cursor:pointer';
        row.textContent = (w.kind === 'lista' ? (chosen[w.id] === opt ? '✓   ' : '      ') : '') + opt;
        row.onclick = function (e) {
          e.stopPropagation();
          if (w.kind === 'lista') chosen[w.id] = opt;
          closeOverlay();
          aviso('👆 ' + opt + ': en la app real hace su acción');
        };
      }
      card.appendChild(row);
    });
    ov.appendChild(card);
    phone.appendChild(ov);
  }
  document.querySelectorAll('.btn').forEach(function (b) {
    var t = b.textContent.trim().toLowerCase();
    var w = VENT.filter(function (x) { return x.label.toLowerCase() === t; })[0];
    if (!w) return;
    b.dataset.h = '1';
    b.addEventListener('click', function (ev) {
      ev.stopPropagation();
      var d = b.closest('.dl');
      if (d) d.classList.remove('ol', 'or');
      openWin(w);
    });
  });
  document.querySelectorAll('[data-id="win_back"]').forEach(function (b) {
    b.dataset.h = '1';
    b.style.cursor = 'pointer';
    b.addEventListener('click', function (ev) { ev.stopPropagation(); show(0); });
  });

  // 🔘 Tus botones (píldoras): en la maqueta avisan; en la app hacen su acción
  document.querySelectorAll('.btn').forEach(function (b) {
    if (b.dataset.h) return;
    b.dataset.h = '1';
    b.addEventListener('click', function (ev) {
      ev.stopPropagation();
      var d = b.closest('.dl');
      if (d) d.classList.remove('ol', 'or');
      aviso('🔘 ' + b.textContent.trim() + ': en la app real hace su acción');
    });
  });

  // 💬 Saludo de la IA al abrir (como en la app)
  addMessage('¡Hola! ¿En qué te puedo ayudar?', 'ai');
})();"""

    /** 🔘 Lógica de los botones que el usuario le va agregando a la página (un bloque por botón). */
    fun botonesJs(blocks: List<String>, page: Boolean = false): String = "'use strict';\n" +
        "// 🔘 Botones y partes que le vas agregando: AppButtons['id'] = { async run() { ... return 'texto'; } };\n" +
        "window.AppButtons = window.AppButtons || {};\n" +
        (if (page) PAGE_HELPERS else "") +
        blocks.joinToString("") { "\n" + it.trim() + "\n" }

    /** En la página (no tiene chat): avisos con toast() y los botones muestran su resultado en un aviso. */
    private const val PAGE_HELPERS = """window.toast = window.toast || function (text) {
  let t = document.getElementById('toastMsg');
  if (!t) { t = document.createElement('div'); t.id = 'toastMsg'; t.className = 'toast-msg'; document.body.appendChild(t); }
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(window.toast.timer);
  window.toast.timer = setTimeout(() => t.classList.remove('show'), 3500);
};
window.addMessage = window.addMessage || function (text) { window.toast(text); };
window.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-boton]').forEach((b) => b.addEventListener('click', async () => {
    const action = (window.AppButtons || {})[b.dataset.boton];
    try {
      const r = action && action.run ? await action.run() : null;
      if (r) window.toast(String(r));
    } catch (e) { window.toast('⚠️ ' + (e && e.message ? e.message : e)); }
  }));
});
"""

    /** 👤 Cuentas web con Firebase (versión «compat», se usa sin módulos). */
    private fun accountJs() = """'use strict';
// 👤 Cuentas con correo y contraseña (Firebase). Pega los datos de tu proyecto en firebase-config.js.
// Perfil: users/{uid} con name, nameLower, email y bio.
const Account = (() => {
  const cfg = window.FIREBASE_CONFIG || {};
  const ready = typeof firebase !== 'undefined' && cfg.apiKey && !String(cfg.apiKey).startsWith('PEGA');
  if (!ready) return { ready: false, uid: () => null, name: () => 'Yo' };
  firebase.initializeApp(cfg);
  const auth = firebase.auth();
  const db = firebase.firestore();
  let newName = null;   // el nombre recién elegido (Firebase lo guarda unos segundos después)
  return {
    ready: true,
    db,
    serverTime: () => firebase.firestore.FieldValue.serverTimestamp(),
    millis: (t) => (t && t.toMillis ? t.toMillis() : 0),
    onAuth(cb) { auth.onAuthStateChanged(cb); },
    uid() { return auth.currentUser ? auth.currentUser.uid : null; },
    name() {
      if (newName) return newName;
      const u = auth.currentUser;
      return u ? (u.displayName || (u.email || '').split('@')[0]) : 'Yo';
    },
    async signIn(email, password) { newName = null; await auth.signInWithEmailAndPassword(email.trim(), password); },
    async signUp(name, email, password) {
      newName = name;
      const cred = await auth.createUserWithEmailAndPassword(email.trim(), password);
      await cred.user.updateProfile({ displayName: name });
      await db.collection('users').doc(cred.user.uid).set({ name, nameLower: name.toLowerCase(), email: email.trim().toLowerCase(), bio: '' });
    },
    async rename(name) {
      newName = name;
      if (auth.currentUser) await auth.currentUser.updateProfile({ displayName: name });
    },
    signOut() { newName = null; return auth.signOut(); }
  };
})();
"""

    /** 💬 Mensajería web con Firebase (usa las cuentas de account.js). */
    private fun messengerJs() = """'use strict';
// 💬 Mensajería real con Firebase: chats y mensajes en tiempo real (las cuentas están en account.js).
// Datos: chats/{idA_idB} · chats/{id}/messages/{auto}
const Messenger = (() => {
  if (!Account.ready) return { ready: false };
  const db = Account.db;
  const serverTime = Account.serverTime;
  const millis = Account.millis;
  return {
    ready: true,
    /** Abre (o crea) el chat con la persona que tiene ese correo. */
    async startChatWith(email) {
      const me = Account.uid();
      if (!me) throw new Error('Primero inicia sesión');
      const snap = await db.collection('users').where('email', '==', email.trim().toLowerCase()).limit(1).get();
      if (snap.empty) throw new Error('No hay nadie con el correo ' + email.trim());
      const other = snap.docs[0];
      if (other.id === me) throw new Error('Ese es tu propio correo');
      const otherName = other.data().name || email.trim();
      const id = [me, other.id].sort().join('_');
      const ref = db.collection('chats').doc(id);
      const doc = await ref.get();
      if (!doc.exists) {
        const names = {};
        names[me] = Account.name();
        names[other.id] = otherName;
        await ref.set({ members: [me, other.id], names, lastText: '', lastFrom: '', lastAt: serverTime() });
      }
      return { id, otherName, lastText: doc.exists ? (doc.data().lastText || '') : '', lastAt: 0, lastFrom: '' };
    },
    /** Escucha tus chats (el más reciente primero). Devuelve una función para dejar de escuchar. */
    listenChats(cb) {
      const me = Account.uid();
      return db.collection('chats').where('members', 'array-contains', me).onSnapshot((snap) => {
        const chats = snap.docs.map((d) => {
          const data = d.data();
          const names = data.names || {};
          const otherId = (data.members || []).find((m) => m !== me);
          return { id: d.id, otherName: names[otherId] || 'Chat', lastText: data.lastText || '', lastAt: millis(data.lastAt), lastFrom: data.lastFrom || '' };
        }).sort((a, b) => b.lastAt - a.lastAt);
        cb(chats);
      }, () => cb([]));
    },
    /** Escucha los mensajes de un chat (los últimos 200, en orden). */
    listenMessages(chatId, cb) {
      const me = Account.uid();
      return db.collection('chats').doc(chatId).collection('messages').orderBy('at').limitToLast(200).onSnapshot((snap) => {
        cb(snap.docs.map((d) => {
          const m = d.data();
          return { fromMe: m.from === me, text: m.text || '', image: m.image || null, at: millis(m.at) || Date.now() };
        }));
      });
    },
    /** Envía un mensaje (y una foto achicada, si hay). */
    async send(chatId, text, image) {
      const me = Account.uid();
      if (!me) return;
      const data = { from: me, text, at: serverTime() };
      if (image) data.image = image;
      const chat = db.collection('chats').doc(chatId);
      await chat.collection('messages').add(data);
      await chat.update({ lastText: (image ? '📷 Foto' : text).slice(0, 80), lastFrom: me, lastAt: serverTime() });
    }
  };
})();
"""

    /** 👥 Red social web con Firebase (usa las cuentas de account.js). */
    private fun socialJs() = """'use strict';
// 👥 Red social real con Firebase: publicaciones con foto, me gusta, comentarios, perfiles, seguir, actividad,
// reportar y bloquear (Google Play pide estas dos últimas en apps donde la gente publica).
// Datos: users/{uid} · posts/{auto} · posts/{id}/comments · follows/{yo_otro} · users/{uid}/activity · reports
const Social = (() => {
  if (!Account.ready) return { ready: false };
  const db = Account.db;
  const FV = firebase.firestore.FieldValue;
  const now = Account.serverTime;
  const millis = Account.millis;
  let myPhoto = null;   // tu foto de perfil (achicada), va en cada publicación
  const post = (d) => {
    const p = d.data() || {};
    return { id: d.id, author: p.author || '', name: p.name || '?', avatar: p.avatar || null, text: p.text || '', image: p.image || null,
      at: millis(p.at) || Date.now(), likes: p.likes || [], comments: p.comments || 0 };
  };
  const profile = (d) => {
    const u = d.data() || {};
    return { uid: d.id, name: u.name || '?', bio: u.bio || '', photo: u.photo || null, email: u.email || '' };
  };
  // Avisa a otra persona (me gusta, comentario o nuevo seguidor)
  const notify = (to, type, postId, text) => {
    const me = Account.uid();
    if (!me || !to || to === me) return;
    db.collection('users').doc(to).collection('activity')
      .add({ from: me, name: Account.name(), type, post: postId || '', text: (text || '').slice(0, 120), at: now() });
  };
  return {
    ready: true,
    /** Las publicaciones más nuevas de todos. */
    listenFeed(cb) {
      return db.collection('posts').orderBy('at', 'desc').limit(40).onSnapshot((s) => cb(s.docs.map(post)), () => cb([]));
    },
    /** Las publicaciones de una persona (para su perfil). */
    listenUserPosts(uid, cb) {
      return db.collection('posts').where('author', '==', uid).limit(200)
        .onSnapshot((s) => cb(s.docs.map(post).sort((a, b) => b.at - a.at)), () => cb([]));
    },
    /** Publica un texto y/o una foto (dataURL achicada). */
    async publish(text, image) {
      const me = Account.uid();
      if (!me) throw new Error('Primero inicia sesión');
      if (!text && !image) return;
      const data = { author: me, name: Account.name(), text: (text || '').slice(0, 2000), at: now(), likes: [], comments: 0 };
      if (myPhoto) data.avatar = myPhoto;
      if (image) data.image = image;
      await db.collection('posts').add(data);
    },
    toggleLike(p) {
      const me = Account.uid();
      if (!me) return;
      const liked = p.likes.includes(me);
      db.collection('posts').doc(p.id).update({ likes: liked ? FV.arrayRemove(me) : FV.arrayUnion(me) });
      if (!liked) notify(p.author, 'like', p.id, '');
    },
    listenComments(postId, cb) {
      return db.collection('posts').doc(postId).collection('comments').orderBy('at').limitToLast(200).onSnapshot((s) => {
        cb(s.docs.map((d) => {
          const c = d.data();
          return { from: c.from || '', name: c.name || '?', text: c.text || '', at: millis(c.at) || Date.now() };
        }));
      }, () => cb([]));
    },
    async comment(p, text) {
      const me = Account.uid();
      if (!me) return;
      const ref = db.collection('posts').doc(p.id);
      await ref.collection('comments').add({ from: me, name: Account.name(), text: text.slice(0, 1000), at: now() });
      await ref.update({ comments: FV.increment(1) });
      notify(p.author, 'comment', p.id, text);
    },
    remove(postId) { return db.collection('posts').doc(postId).delete(); },
    /** 🚩 Queda guardado en «reports» para que lo revises en la consola de Firebase. */
    report(p) {
      const me = Account.uid();
      if (me) db.collection('reports').add({ by: me, post: p.id, author: p.author, text: (p.text || '').slice(0, 300), at: now() });
    },
    /** 🚫 Ya no verás sus publicaciones ni sus comentarios. */
    block(uid) {
      const me = Account.uid();
      if (me) db.collection('users').doc(me).set({ blocked: FV.arrayUnion(uid) }, { merge: true });
    },
    async follow(uid, on) {
      const me = Account.uid();
      if (!me) return;
      const ref = db.collection('follows').doc(me + '_' + uid);
      if (on) { await ref.set({ from: me, to: uid, at: now() }); notify(uid, 'follow', '', ''); } else await ref.delete();
    },
    /** Las personas que sigues. */
    listenFollowing(cb) {
      return db.collection('follows').where('from', '==', Account.uid())
        .onSnapshot((s) => cb(new Set(s.docs.map((d) => d.data().to))), () => cb(new Set()));
    },
    /** Cuántos seguidores tiene y a cuántos sigue. */
    async counts(uid) {
      const a = await db.collection('follows').where('to', '==', uid).get();
      const b = await db.collection('follows').where('from', '==', uid).get();
      return { followers: a.size, following: b.size };
    },
    /** Tus datos: a quién bloqueaste (y tu foto para las publicaciones). */
    listenMe(cb) {
      return db.collection('users').doc(Account.uid()).onSnapshot((d) => {
        const u = (d && d.data()) || {};
        myPhoto = u.photo || null;
        cb(new Set(u.blocked || []));
      }, () => {});
    },
    async profile(uid) {
      const d = await db.collection('users').doc(uid).get();
      return d.exists ? profile(d) : null;
    },
    async saveProfile(name, bio) {
      const me = Account.uid();
      if (!me) throw new Error('Primero inicia sesión');
      await Account.rename(name);
      await db.collection('users').doc(me).set({ name, nameLower: name.toLowerCase(), bio }, { merge: true });
    },
    /** Foto de perfil (pequeña, se ve redonda). */
    async setPhoto(dataUrl) {
      const me = Account.uid();
      if (!me) return;
      myPhoto = dataUrl;
      await db.collection('users').doc(me).set({ photo: dataUrl }, { merge: true });
    },
    /** 🔎 Busca por correo exacto o por el comienzo del nombre. */
    async search(text) {
      const t = (text || '').trim().toLowerCase();
      if (!t) return [];
      const users = db.collection('users');
      const query = t.includes('@') ? users.where('email', '==', t) : users.where('nameLower', '>=', t).where('nameLower', '<=', t + '');
      const s = await query.limit(20).get();
      return s.docs.map(profile).filter((p) => p.uid !== Account.uid());
    },
    /** 🔔 Lo último que pasó con tus publicaciones y seguidores. */
    listenActivity(cb) {
      return db.collection('users').doc(Account.uid()).collection('activity').orderBy('at', 'desc').limit(40).onSnapshot((s) => {
        cb(s.docs.map((d) => {
          const a = d.data();
          return { id: d.id, from: a.from || '', name: a.name || '?', type: a.type || '', text: a.text || '', at: millis(a.at) || Date.now() };
        }));
      }, () => cb([]));
    }
  };
})();
"""

    /** 👥 Pantallas de la red social en script.js (inicio, perfil, comentarios, actividad, buscar y editar perfil). */
    private fun socialUiJs(chat: Boolean) = """
// ===================== 👥 RED SOCIAL (Firebase) =====================
const SocialUI = (() => {
  const feedBox = q('#feed');
  const profileBox = q('#profileHeader');
  const pendingEl = q('#pendingPhoto');
  const backBtn = q('#btnBack');
  const baseHint = input.placeholder;
  const postHint = '✍️ ¿Qué quieres compartir?';
  const st = {
    posts: [], profilePosts: [], following: new Set(), blocked: new Set(), activity: [], profileUid: null, comments: null,
    showingActivity: false, pending: null, avatarNext: false, stops: [], stopProfile: null, stopComments: null,
    actLoaded: false, actSeen: new Set()
  };
  const chatOpen = () => """ + (if (chat) "!!openChat" else "false") + """;
  const show = (d) => { if (d.showModal) d.showModal(); else d.setAttribute('open', ''); };
  const setTitle = (title, sub) => { q('.title h1').textContent = title; q('.title p').textContent = sub; };

  // Publicaciones que reportaste: ya no las ves en este navegador (cada cuenta tiene su lista)
  const hiddenKey = () => 'social:hidden:' + Account.uid();
  const hiddenPosts = () => { try { return new Set(JSON.parse(localStorage.getItem(hiddenKey()) || '[]')); } catch (e) { return new Set(); } };
  const hidePost = (id) => {
    const h = hiddenPosts();
    h.add(id);
    try { localStorage.setItem(hiddenKey(), JSON.stringify(Array.from(h))); } catch (e) { /* sin almacenamiento */ }
  };

  function ago(at) {
    const s = Math.floor((Date.now() - at) / 1000);
    if (!at || s < 60) return 'ahora';
    if (s < 3600) return 'hace ' + Math.floor(s / 60) + ' min';
    if (s < 86400) return 'hace ' + Math.floor(s / 3600) + ' h';
    return 'hace ' + Math.floor(s / 86400) + ' d';
  }

  /** Foto de perfil redonda (o la inicial del nombre si no tiene foto). */
  function avatar(name, photo, big) {
    const el = document.createElement('div');
    el.className = big ? 'avatar-lg' : 'avatar-sm';
    if (photo) {
      const img = document.createElement('img');
      img.src = photo;
      img.alt = name;
      el.appendChild(img);
    } else {
      el.textContent = (Array.from((name || '?').trim())[0] || '?').toUpperCase();
    }
    return el;
  }

  /** El inicio (publicaciones) está a la vista y hay una cuenta abierta. */
  function feedVisible() { return !welcome.hidden && authBox.hidden && !!Account.uid(); }

  // ← Volver: se ve en el perfil, los comentarios, la actividad y los chats
  function syncBack() { backBtn.hidden = !Account.uid() || !authBox.hidden || (!welcome.hidden && !st.profileUid); }
  new MutationObserver(syncBack).observe(welcome, { attributes: true, attributeFilter: ['hidden'] });

  /** Sale del perfil, los comentarios o la actividad (sin cambiar de pantalla). */
  function leave() {
    if (st.stopComments) st.stopComments();
    if (st.stopProfile) st.stopProfile();
    st.stopComments = null; st.stopProfile = null; st.comments = null; st.profileUid = null; st.showingActivity = false;
    profileBox.hidden = true;
    q('#welcomeTitle').hidden = false;
    q('#welcomeSub').hidden = false;
    input.placeholder = baseHint;
    syncBack();
  }

  /** 🏠 Inicio: las publicaciones de todos (o solo de las personas que sigues). */
  function home() {
    leave();
    setTitle(appTitle, appSubtitle);
    messages.innerHTML = '';
    welcome.hidden = false;
    input.placeholder = postHint;
    render();
    syncBack();
  }

  function render() {
    feedBox.innerHTML = '';
    const me = Account.uid();
    const onlyFollowing = store.get('module:following') === '1';
    const hidden = hiddenPosts();
    const shown = (st.profileUid ? st.profilePosts : st.posts).filter((p) => !st.blocked.has(p.author) && !hidden.has(p.id) &&
      (st.profileUid || !onlyFollowing || p.author === me || st.following.has(p.author)));
    q('#feedEmpty').hidden = shown.length > 0;
    shown.forEach((p) => feedBox.appendChild(postEl(p)));
  }

  /** Tarjeta de una publicación: autor, texto, foto, ❤️ me gusta y 💬 comentarios. */
  function postEl(p) {
    const me = Account.uid();
    const el = document.createElement('article');
    el.className = 'post';
    const head = document.createElement('div');
    head.className = 'post-head';
    const av = avatar(p.name, p.avatar, false);
    av.addEventListener('click', () => openProfile(p.author));
    const who = document.createElement('button');
    who.className = 'post-who';
    const name = document.createElement('strong');
    name.textContent = p.name;
    const time = document.createElement('small');
    time.textContent = ago(p.at);
    who.append(name, time);
    who.addEventListener('click', () => openProfile(p.author));
    const more = document.createElement('button');
    more.className = 'icon-btn';
    more.textContent = '⋮';
    more.setAttribute('aria-label', 'Opciones');
    more.addEventListener('click', () => postMenu(p));
    head.append(av, who, more);
    el.appendChild(head);
    if (p.text) {
      const t = document.createElement('p');
      t.className = 'post-text';
      t.textContent = p.text;
      el.appendChild(t);
    }
    if (p.image) {
      const img = document.createElement('img');
      img.className = 'post-img';
      img.src = p.image;
      img.alt = 'Foto';
      img.loading = 'lazy';
      el.appendChild(img);
    }
    const actions = document.createElement('div');
    actions.className = 'post-actions';
    const liked = p.likes.includes(me);
    const like = document.createElement('button');
    like.textContent = (liked ? '❤️ ' : '🤍 ') + p.likes.length;
    if (liked) like.className = 'liked';
    like.addEventListener('click', () => Social.toggleLike(p));
    const com = document.createElement('button');
    com.textContent = '💬 ' + p.comments;
    com.addEventListener('click', () => openComments(p));
    actions.append(like, com);
    el.appendChild(actions);
    return el;
  }

  function choose(title, options) {
    const d = q('#choiceDialog');
    q('#choiceTitle').textContent = title;
    const box = q('#choiceList');
    box.innerHTML = '';
    options.forEach(([label, fn]) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'result';
      b.textContent = label;
      b.addEventListener('click', () => { d.close(); fn(); });
      box.appendChild(b);
    });
    show(d);
  }

  /** ⋮ Opciones: eliminar (la tuya) o ver perfil, reportar y bloquear (la de otra persona). */
  function postMenu(p) {
    if (p.author === Account.uid()) {
      choose('Tu publicación', [['🗑️ Eliminar publicación', () => choose('¿Eliminar esta publicación?', [['Sí, eliminar', () => Social.remove(p.id)]])]]);
      return;
    }
    choose(p.name, [
      ['👤 Ver perfil', () => openProfile(p.author)],
      ['🚩 Reportar publicación', () => { Social.report(p); hidePost(p.id); render(); toast('🚩 Gracias: la publicación se reportó y ya no la verás.'); }],
      ['🚫 Bloquear a ' + p.name, () => choose('¿Bloquear a ' + p.name + '? Ya no verás sus publicaciones ni sus comentarios.', [['Sí, bloquear', () => {
        Social.block(p.author);
        if (st.following.has(p.author)) Social.follow(p.author, false);
        st.blocked.add(p.author);
        toast('🚫 Persona bloqueada');
        home();
      }]])]
    ]);
  }

  /** 👤 Perfil: foto, nombre, biografía, seguidores, botón seguir y sus publicaciones. */
  async function openProfile(uid) {
    if (!uid || !Account.uid()) return;
    closePanels();
    if (chatOpen()) closeChat();
    leave();
    st.profileUid = uid;
    st.profilePosts = [];
    messages.innerHTML = '';
    welcome.hidden = false;
    input.placeholder = postHint;
    q('#welcomeTitle').hidden = true;
    q('#welcomeSub').hidden = true;
    profileBox.innerHTML = '';
    profileBox.hidden = false;
    setTitle('Perfil', '');
    render();
    syncBack();
    st.stopProfile = Social.listenUserPosts(uid, (posts) => { if (st.profileUid === uid) { st.profilePosts = posts; render(); } });
    const prof = await Social.profile(uid);
    if (prof && st.profileUid === uid) renderProfile(prof);
  }

  function renderProfile(prof) {
    const me = prof.uid === Account.uid();
    setTitle(prof.name, 'Perfil');
    profileBox.innerHTML = '';
    profileBox.appendChild(avatar(prof.name, prof.photo, true));
    const n = document.createElement('h3');
    n.textContent = prof.name;
    profileBox.appendChild(n);
    if (prof.bio) {
      const b = document.createElement('p');
      b.className = 'bio';
      b.textContent = prof.bio;
      profileBox.appendChild(b);
    }
    const counts = document.createElement('small');
    counts.className = 'counts';
    profileBox.appendChild(counts);
    const showCounts = async () => {
      try {
        const c = await Social.counts(prof.uid);
        counts.textContent = c.followers + ' seguidores · ' + c.following + ' siguiendo';
      } catch (e) { /* sin conexión */ }
    };
    showCounts();
    const btn = document.createElement('button');
    btn.className = 'setting-btn';
    const label = () => { btn.textContent = me ? '✏️ Editar perfil' : (st.following.has(prof.uid) ? '✔️ Siguiendo' : '➕ Seguir'); };
    label();
    btn.addEventListener('click', async () => {
      if (me) { editProfile(); return; }
      const on = !st.following.has(prof.uid);
      if (on) st.following.add(prof.uid); else st.following.delete(prof.uid);
      label();
      await Social.follow(prof.uid, on);
      showCounts();
    });
    profileBox.appendChild(btn);""" + (if (chat) """
    if (!me && prof.email) {
      const m = document.createElement('button');
      m.className = 'ghost wide';
      m.textContent = '💬 Enviar mensaje';
      m.addEventListener('click', async () => {
        try { openChatView(await Messenger.startChatWith(prof.email)); } catch (e) { toast('⚠️ ' + e.message); }
      });
      profileBox.appendChild(m);
    }""" else "") + """
  }

  /** 💬 Comentarios de una publicación (se escriben en la barra de abajo). */
  function openComments(p) {
    leave();
    st.comments = p;
    setTitle('💬 Comentarios', p.name + (p.text ? ': ' + p.text.slice(0, 40) : ''));
    input.placeholder = 'Escribe un comentario...';
    messages.innerHTML = '';
    welcome.hidden = true;
    st.stopComments = Social.listenComments(p.id, (list) => {
      if (!st.comments || st.comments.id !== p.id) return;
      const me = Account.uid();
      messages.innerHTML = '';
      addMessage('📝 ' + p.name + (p.text ? '\n' + p.text : ''), 'bot', false);
      if (p.image) {
        const el = document.createElement('div');
        el.className = 'msg img bot';
        const img = document.createElement('img');
        img.src = p.image;
        img.alt = 'Foto';
        el.appendChild(img);
        messages.appendChild(el);
      }
      list.filter((c) => !st.blocked.has(c.from)).forEach((c) => addMessage(c.from === me ? c.text : c.name + '\n' + c.text, c.from === me ? 'user' : 'bot', false));
      if (!list.length) addMessage('Todavía no hay comentarios: escribe el primero abajo.', 'bot', false);
    });
  }

  function activityText(a) {
    if (a.type === 'like') return 'le dio me gusta a tu publicación';
    if (a.type === 'comment') return 'comentó tu publicación: ' + a.text;
    return 'empezó a seguirte';
  }

  /** 🔔 Actividad: quién te dio me gusta, te comentó o te empezó a seguir. */
  function showActivity() {
    if (!Account.uid()) return;
    closePanels();
    if (chatOpen()) closeChat();
    leave();
    st.showingActivity = true;
    setTitle('🔔 Actividad', 'Me gusta, comentarios y seguidores');
    messages.innerHTML = '';
    const items = st.activity.filter((a) => !st.blocked.has(a.from));
    items.forEach((a) => addMessage('👤 ' + a.name + ' ' + activityText(a) + '\n' + ago(a.at), 'bot', false));
    if (!items.length) addMessage('Todavía no tienes actividad.', 'bot', false);
    syncBack();
  }

  /** ✏️ Editar nombre, biografía y foto de perfil. */
  async function editProfile() {
    if (!Account.uid()) return;
    closePanels();
    q('#profileName').value = Account.name();
    q('#profileBio').value = '';
    show(q('#profileDialog'));
    const prof = await Social.profile(Account.uid());
    if (prof && !q('#profileBio').value) q('#profileBio').value = prof.bio;
  }
  q('#profileDialog form').addEventListener('submit', (e) => e.preventDefault());
  q('#profileCancel').addEventListener('click', () => q('#profileDialog').close());
  q('#profilePhoto').addEventListener('click', () => { q('#profileDialog').close(); st.avatarNext = true; q('#photoInput').click(); });
  q('#profileSave').addEventListener('click', async () => {
    const name = q('#profileName').value.trim();
    if (!name) return;
    q('#profileDialog').close();
    try {
      await Social.saveProfile(name, q('#profileBio').value.trim().slice(0, 160));
      toast('✅ Perfil guardado');
      if (st.profileUid) openProfile(st.profileUid);
    } catch (e) { toast('⚠️ ' + e.message); }
  });

  /** 🔎 Buscar personas por nombre o correo. */
  function searchPeople() {
    if (!Account.uid()) return;
    closePanels();
    q('#searchText').value = '';
    q('#searchResults').innerHTML = '';
    show(q('#searchDialog'));
  }
  async function runSearch() {
    const box = q('#searchResults');
    box.textContent = '🔎 Buscando...';
    let found = [];
    try { found = (await Social.search(q('#searchText').value)).filter((p) => !st.blocked.has(p.uid)); } catch (e) { found = []; }
    box.innerHTML = '';
    if (!found.length) { box.textContent = 'No encontré a nadie con ese nombre o correo.'; return; }
    found.forEach((p) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'result';
      b.textContent = '👤 ' + p.name;
      b.addEventListener('click', () => { q('#searchDialog').close(); openProfile(p.uid); });
      box.appendChild(b);
    });
  }
  q('#searchDialog form').addEventListener('submit', (e) => { e.preventDefault(); runSearch(); });
  q('#searchOk').addEventListener('click', runSearch);
  q('#searchCancel').addEventListener('click', () => q('#searchDialog').close());
  q('#choiceCancel').addEventListener('click', () => q('#choiceDialog').close());

  function setPending(data) { st.pending = data; pendingEl.hidden = !data; }
  pendingEl.addEventListener('click', () => setPending(null));

  async function publish(text) {
    const photo = st.pending;
    setPending(null);
    try { await Social.publish(text, photo); } catch (e) { toast('⚠️ ' + e.message); }
  }

  /** La barra de abajo: en el inicio publica, con comentarios abiertos comenta. Devuelve true si lo usó. */
  function onSend(text, fromBar) {
    if (st.comments) { if (text) Social.comment(st.comments, text); return true; }
    if (fromBar && feedVisible()) { if (text || st.pending) publish(text); return true; }
    if (st.showingActivity && text) { st.showingActivity = false; messages.innerHTML = ''; setTitle(appTitle, appSubtitle); }
    return false;
  }

  /** Foto elegida: de perfil, o para la próxima publicación. Devuelve true si la usó. */
  async function onPhoto(dataUrl) {
    if (st.avatarNext) {
      st.avatarNext = false;
      try {
        await Social.setPhoto(await shrinkImage(dataUrl, 160));
        toast('✅ Foto de perfil lista');
        if (st.profileUid) openProfile(st.profileUid);
      } catch (e) { toast('⚠️ ' + e.message); }
      return true;
    }
    if (feedVisible() || st.comments || st.showingActivity) {
      if (!feedVisible()) home();
      setPending(await shrinkImage(dataUrl, 640));
      return true;
    }
    return false;
  }

  /** ← Volver (o Escape, o tocar el título). */
  function back() {
    if (chatOpen()) { closeChat(); return; }
    if (st.comments) { const uid = st.profileUid; if (uid) openProfile(uid); else home(); return; }
    if (st.profileUid || st.showingActivity || welcome.hidden) home();
  }
  backBtn.addEventListener('click', back);
  q('.title').addEventListener('click', () => { if (!chatOpen() && Account.uid() && authBox.hidden) back(); });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || chatOpen() || document.querySelector('dialog[open]')) return;
    if (leftPanel.classList.contains('open') || rightPanel.classList.contains('open')) return;
    if (Account.uid() && authBox.hidden) back();
  });

  // ☰ Menú de la comunidad
  q('#btnFeedHome').addEventListener('click', () => { closePanels(); if (chatOpen()) closeChat(); else home(); });
  q('#btnMyProfile').addEventListener('click', () => openProfile(Account.uid()));
  q('#btnEditProfile').addEventListener('click', editProfile);
  q('#btnSearchPeople').addEventListener('click', searchPeople);
  q('#btnActivity').addEventListener('click', showActivity);
  q('[data-setting="module:following"]').addEventListener('change', render);

  /** Al entrar: escucha publicaciones, a quién sigues, a quién bloqueaste y tu actividad. */
  function start() {
    stop();
    st.stops.push(Social.listenFeed((posts) => { st.posts = posts; if (!st.profileUid) render(); }));
    st.stops.push(Social.listenFollowing((set) => { st.following = set; render(); }));
    st.stops.push(Social.listenMe((set) => { st.blocked = set; render(); }));
    st.stops.push(Social.listenActivity((items) => {
      if (st.actLoaded) {
        items.filter((a) => !st.actSeen.has(a.id) && !st.blocked.has(a.from)).forEach((a) => Notify.show(appTitle, a.name + ' ' + activityText(a)));
      }
      items.forEach((a) => st.actSeen.add(a.id));
      st.actLoaded = true;
      st.activity = items;
      if (st.showingActivity) showActivity();
    }));
    home();
  }

  /** Al salir: deja de escuchar y borra lo que se veía. */
  function stop() {
    st.stops.forEach((f) => { if (typeof f === 'function') f(); });
    st.stops = [];
    leave();
    Object.assign(st, { posts: [], profilePosts: [], following: new Set(), blocked: new Set(), activity: [], actLoaded: false, actSeen: new Set(), avatarNext: false });
    setPending(null);
    feedBox.innerHTML = '';
    syncBack();
  }

  return { start, stop, home, leave, feedVisible, onSend, onPhoto };
})();
"""

    /** Lo que la IA escribe para la web: la lógica propia de la app (logic.js). */
    fun logicAskWeb(prompt: String, spec: AppSpec, mods: Set<Module> = emptySet()): String =
        "App web pedida por el usuario: «${prompt.take(400)}»\n" +
            "Ya existen index.html, style.css y script.js (menú, ajustes, chat con micrófono). Escribe el archivo «logic.js» " +
            "con TODA la lógica real de esta app, con este contrato exacto:\n" +
            "window.App = {\n" +
            "  async reply(text) { /* responde a lo que el usuario escribe o dice; devuelve un texto */ },\n" +
            "  async onMenu(item) { /* opciones: ${spec.menu.joinToString(", ")}; devuelve un texto o null */ },\n" +
            "  async onSetting(key, on) { /* ajustes: ${aiSettings(spec, mods).joinToString(", ") { it.second }}; devuelve un texto o null */ }" +
            (if (Module.CAMERA in mods) ",\n  async onPhoto(dataUrl) { /* el usuario sacó o eligió una foto; devuelve un texto */ }" else "") +
            "\n};\n" +
            "Las tarjetas y atajos llegan a reply() con su texto: ${(spec.cards.map { noEmoji(it.first) } + spec.chips).joinToString(", ")}.\n" +
            moduleHelpersWeb(mods) +
            (if (Module.CHAT in mods) "La mensajería real (cuentas, lista de chats y mensajes en tiempo real) YA está hecha en messenger.js: " +
                "no la reescribas. reply() solo responde cuando NO hay un chat abierto (como ayudante de la app).\n" else "") +
            (if (Module.SOCIAL in mods) "La red social real (cuentas, publicaciones con foto, me gusta, comentarios, perfiles, seguir, actividad, " +
                "reportar y bloquear) YA está hecha en account.js, social.js y script.js: no la reescribas ni declares Account, Social o SocialUI. " +
                "En el inicio la barra publica; reply() responde a las tarjetas, los atajos y el menú (como ayudante de la app).\n" else "") +
            "Que cada opción haga algo útil y real para esta app" + (if (Module.STORE in mods) " (guarda datos con Store)" else " (guarda datos con localStorage dentro de try/catch si hace falta)") +
            ". JavaScript moderno (const/let, funciones flecha). No uses document.write ni cambies el HTML base. " +
            "Entrega SOLO el archivo completo dentro de un bloque ```javascript."

    private fun moduleHelpersWeb(mods: Set<Module>): String {
        if (mods.isEmpty()) return ""
        val l = mutableListOf<String>()
        if (Module.STORE in mods) l += "Store.add(lista, valor) · Store.all(lista) · Store.remove(lista, índice) · Store.clear(lista)"
        if (Module.NOTIFY in mods) l += "Notify.show(título, texto)"
        if (Module.REMIND in mods) l += "Reminders.schedule(fechaEnMilisegundos, texto)"
        if (Module.NET in mods) l += "await Net.get(url) → texto · await Net.json(url) → objeto (o null)"
        if (Module.BUBBLE in mods) l += "Bubble.add() (suma un aviso en la burbuja flotante)"
        return "Ya existen y puedes usarlos: " + l.joinToString(" · ") + "\n"
    }

    // ==================================================================== 🌐 PÁGINA WEB (no app web)
    /**
     * 🌐 ¿Pide una PÁGINA web (negocio, restaurante, portafolio, currículum, blog, presentación de un producto…)
     * y no una APP web (chat, calculadora, lista de tareas, juego…)? Solo mira lo que pidió (no la tecnología elegida).
     */
    fun isWebPage(prompt: String): Boolean {
        val asked = prompt.substringBefore("\n\nHazla con").lowercase(Locale.ROOT)
        val page = Regex("\\b(p[aá]gina|sitio|landing|portafolio|portfolio|blog|curr[ií]culum|\\bcv\\b|web de (mi|un|una|la|el))\\b")
            .containsMatchIn(asked)
        val app = Regex("\\b(app|apps|aplicaci[oó]n|calculadora|juego|chat|chatbot|bot|tareas|to-?do|notas|dashboard|panel de|gestor|" +
            "administrador|herramienta|convertidor|reproductor|editor|asistente|traductor|cron[oó]metro|temporizador)\\b").containsMatchIn(asked)
        // «landing para mi app de recetas», «portafolio», «blog», «currículum»: siempre son páginas aunque nombren una app
        val surelyPage = Regex("\\b(landing|portafolio|portfolio|blog|curr[ií]culum|\\bcv\\b)\\b").containsMatchIn(asked)
        return surelyPage || (page && !app)
    }

    data class PageSpec(
        val title: String, val tagline: String, val emoji: String, val cta: String,
        val menu: List<String>,                          // secciones del menú de arriba
        val sections: List<Pair<String, String>>,        // título → texto
        val features: List<Pair<String, String>>,        // tarjetas destacadas: título → descripción
        val contact: List<String>,                       // dirección, teléfono, correo, horario…
        val footer: String
    )

    /** Pedido corto para que la IA describa SU página (cabe aunque el contexto sea de 2048). */
    fun pageSpecAsk(prompt: String): String =
        "El usuario pidió: «${prompt.substringBefore("\n\nHazla con").take(500)}»\n\n" +
            "Vas a crear esa PÁGINA WEB. Ya tiene una base con menú arriba, portada, secciones, tarjetas destacadas, contacto y pie de página.\n" +
            "Escribe el contenido PROPIO de esta página (en el idioma del pedido, real y concreto, nada genérico como «Sección 1»).\n" +
            "Responde SOLO con estas líneas, en este formato exacto:\n" +
            "TITULO: nombre del negocio, persona o producto\n" +
            "LEMA: frase corta y atractiva para la portada\n" +
            "EMOJI: un emoji que lo represente\n" +
            "BOTON: texto del botón principal de la portada (ej. «Reserva tu mesa»)\n" +
            "SECCION: título | párrafo de 2 o 3 frases  (de 2 a 4 líneas SECCION, ej. Nosotros, Historia, Experiencia)\n" +
            "DESTACADO: emoji y título | descripción corta  (de 3 a 6 líneas DESTACADO: servicios, platos, productos o proyectos)\n" +
            "CONTACTO: un dato de contacto (dirección, teléfono, correo u horario; de 1 a 4 líneas)\n" +
            "PIE: frase corta para el pie de página"

    fun parsePageSpec(text: String, prompt: String): PageSpec {
        val lines = text.lines()
        fun all(key: String): List<String> {
            val rx = Regex("^\\s*[-*•]*\\s*\\**($key)\\**\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
            return lines.mapNotNull { rx.find(it)?.groupValues?.get(2)?.let(::clean) }.filter { it.isNotBlank() }
        }
        fun pairs(key: String, max: Int) = all(key).map { s ->
            val p = s.split("|").map { it.trim() }
            p[0].take(40) to (p.getOrNull(1) ?: "").take(320)
        }.filter { it.first.isNotBlank() }.distinctBy { it.first }.take(max)
        val title = all("T[IÍ]TULO|NOMBRE").firstOrNull()?.take(36) ?: titleFromPrompt(prompt)
        val tagline = all("LEMA|ESLOGAN|SUBT[IÍ]TULO").firstOrNull()?.take(110) ?: "Bienvenido a $title"
        val emoji = all("EMOJI").firstOrNull()?.takeIf { hasEmoji(it) }?.let { String(Character.toChars(it.codePointAt(0))) } ?: "✨"
        val cta = all("BOT[OÓ]N").firstOrNull()?.take(30) ?: "Contáctanos"
        var sections = pairs("SECCI[OÓ]N", 4)
        if (sections.isEmpty()) sections = listOf("Nosotros" to "Somos $title. Trabajamos con dedicación para darte lo mejor.")
        var features = pairs("DESTACADO|SERVICIO|PRODUCTO", 6)
        if (features.size < 3) features = (features + listOf("⭐ Calidad" to "Lo mejor en cada detalle.",
            "🤝 Atención cercana" to "Te acompañamos en todo momento.", "⚡ Rapidez" to "Respuestas y entregas a tiempo.")).distinctBy { it.first }.take(3)
        val contact = all("CONTACTO").map { it.take(90) }.distinct().take(4)
        val footer = all("PIE").firstOrNull()?.take(90) ?: "Gracias por visitarnos"
        // El menú: las secciones + Destacados + Contacto
        val menu = (sections.map { it.first } + "Destacados" + "Contacto").distinct().take(6)
        return PageSpec(title, tagline, emoji, cta, menu, sections, features, contact, footer)
    }

    private fun slug(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "seccion" }

    /**
     * 🌐 Base de la PÁGINA web: menú arriba (fijo, con ☰ en el celular), portada grande con botón, secciones una
     * debajo de otra, tarjetas destacadas, contacto con formulario y pie de página. Con los colores pedidos.
     */
    fun pageShell(spec: PageSpec, pal: Palette): List<Pair<String, String>> {
        val nav = spec.menu.joinToString("\n        ") { m ->
            val id = if (m == "Destacados") "destacados" else if (m == "Contacto") "contacto" else slug(m)
            "<a href=\"#$id\">${h(m)}</a>"
        }
        val sections = spec.sections.mapIndexed { i, (t, body) ->
            "  <section id=\"${slug(t)}\" class=\"section${if (i % 2 == 1) " alt" else ""} reveal\">\n" +
                "    <div class=\"container\">\n      <h2>${h(t)}</h2>\n      <p>${h(body)}</p>\n    </div>\n  </section>"
        }.joinToString("\n\n")
        val cards = spec.features.joinToString("\n") { (t, d) ->
            "        <article class=\"card reveal\">\n          <h3>${h(t)}</h3>\n          <p>${h(d)}</p>\n        </article>"
        }
        val contactInfo = if (spec.contact.isEmpty()) "        <p>Escríbenos y te respondemos a la brevedad.</p>"
            else spec.contact.joinToString("\n") { "        <p>${h(it)}</p>" }
        val html = """<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${h(spec.title)}</title>
  <meta name="description" content="${h(spec.tagline)}">
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <header class="topbar">
    <div class="container nav">
      <a href="#inicio" class="logo">${spec.emoji} ${h(spec.title)}</a>
      <button class="menu-btn" id="menuBtn" aria-label="Abrir menú">☰</button>
      <nav class="links" id="links">
        $nav
      </nav>
    </div>
  </header>

  <section id="inicio" class="hero">
    <div class="container hero-inner">
      <div class="hero-emoji">${spec.emoji}</div>
      <h1>${h(spec.title)}</h1>
      <p class="tagline">${h(spec.tagline)}</p>
      <a href="#contacto" class="btn">${h(spec.cta)}</a>
    </div>
  </section>

$sections

  <section id="destacados" class="section reveal">
    <div class="container">
      <h2>Destacados</h2>
      <div class="grid">
$cards
      </div>
    </div>
  </section>

  <!-- 🧩 Lo que le vas agregando a la página (botones, galerías, formularios, contadores…) -->
  <section id="agregado" class="section" hidden>
    <div class="container">
      <div class="my-buttons" id="myButtons">
        <!-- BOTONES -->
      </div>
      <div class="my-parts" id="myParts">
        <!-- PARTES -->
      </div>
    </div>
  </section>

  <section id="contacto" class="section alt reveal">
    <div class="container contact">
      <div>
        <h2>Contacto</h2>
$contactInfo
      </div>
      <form id="contactForm" class="form">
        <input type="text" name="nombre" placeholder="Tu nombre" required>
        <input type="email" name="correo" placeholder="Tu correo" required>
        <textarea name="mensaje" rows="4" placeholder="Tu mensaje" required></textarea>
        <button type="submit" class="btn">Enviar</button>
        <p class="form-ok" id="formOk" hidden>✅ ¡Gracias! Te responderemos pronto.</p>
      </form>
    </div>
  </section>

  <footer class="footer">
    <div class="container">
      <p class="footer-title">${spec.emoji} ${h(spec.title)}</p>
      <p>${h(spec.footer)}</p>
      <p class="copy">© <span id="year"></span> ${h(spec.title)}. Todos los derechos reservados.</p>
    </div>
  </footer>

  <button class="to-top" id="toTop" aria-label="Volver arriba">↑</button>
  <script src="botones.js"></script>
  <script src="script.js"></script>
</body>
</html>"""
        val css = """:root {
  --primary: ${pal.primary};
  --primary-dark: ${pal.primaryDark};
  --primary-light: ${pal.primaryLight};
  --on-primary: ${pal.onPrimary};
  --bg: ${pal.background};
  --surface: ${pal.surface};
  --surface-2: ${pal.surfaceVariant};
  --text: ${pal.textPrimary};
  --text-2: ${pal.textSecondary};
  --divider: ${pal.divider};
  --radius: 16px;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html { scroll-behavior: smooth; }
section[id] { scroll-margin-top: 68px; }   /* el menú fijo de arriba no tapa el título al saltar a una sección */
body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); line-height: 1.6; }
a { color: inherit; text-decoration: none; }
.container { width: min(1100px, 92%); margin: 0 auto; }

/* ☰ Menú de arriba (fijo) */
.topbar { position: sticky; top: 0; z-index: 10; background: var(--surface); border-bottom: 1px solid var(--divider); backdrop-filter: blur(8px); }
.nav { display: flex; align-items: center; justify-content: space-between; height: 64px; }
.logo { font-weight: 800; font-size: 1.15rem; }
.links { display: flex; gap: 22px; }
.links a { color: var(--text-2); font-weight: 600; transition: color .2s; }
.links a:hover, .links a.active { color: var(--primary); }
.menu-btn { display: none; background: none; border: none; color: var(--text); font-size: 1.6rem; cursor: pointer; }

/* 🖼️ Portada */
.hero { padding: 96px 0 88px; text-align: center; color: var(--on-primary);
  background: linear-gradient(135deg, var(--primary-dark), var(--primary) 55%, var(--primary-light)); }
.hero-emoji { font-size: 4rem; margin-bottom: 8px; }
.hero h1 { font-size: clamp(2rem, 6vw, 3.4rem); line-height: 1.15; margin-bottom: 12px; }
.tagline { font-size: clamp(1.05rem, 2.6vw, 1.3rem); opacity: .92; max-width: 640px; margin: 0 auto 28px; }

/* Botones */
.btn { display: inline-block; background: var(--primary); color: var(--on-primary); border: none; border-radius: 999px;
  padding: 14px 28px; font-weight: 700; font-size: 1rem; cursor: pointer; box-shadow: 0 6px 18px rgba(0,0,0,.25); transition: transform .15s, filter .2s; }
.hero .btn { background: var(--surface); color: var(--text); }
.btn:hover { transform: translateY(-2px); filter: brightness(1.08); }

/* 📄 Secciones */
.section { padding: 72px 0; }
.section.alt { background: var(--surface); }
.section h2 { font-size: clamp(1.6rem, 4vw, 2.2rem); margin-bottom: 16px; }
.section p { color: var(--text-2); max-width: 760px; }

/* ⭐ Tarjetas destacadas */
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 18px; margin-top: 24px; }
.card { background: var(--surface-2); border: 1px solid var(--divider); border-radius: var(--radius); padding: 22px;
  transition: transform .2s, box-shadow .2s; }
.card:hover { transform: translateY(-4px); box-shadow: 0 10px 24px rgba(0,0,0,.25); }
.card h3 { margin-bottom: 8px; font-size: 1.1rem; }

/* ✉️ Contacto */
.contact { display: grid; grid-template-columns: 1fr 1fr; gap: 32px; align-items: start; }
.contact p { margin-bottom: 6px; }
.form { display: flex; flex-direction: column; gap: 12px; }
.form input, .form textarea { background: var(--bg); color: var(--text); border: 1px solid var(--divider); border-radius: 12px;
  padding: 12px 14px; font: inherit; }
.form input:focus, .form textarea:focus { outline: 2px solid var(--primary); border-color: transparent; }
.form-ok { color: var(--primary); font-weight: 700; }

/* Pie de página */
.footer { background: var(--surface-2); padding: 40px 0; text-align: center; color: var(--text-2); }
.footer-title { color: var(--text); font-weight: 800; font-size: 1.1rem; margin-bottom: 6px; }
.copy { font-size: .85rem; margin-top: 10px; opacity: .8; }

/* ↑ Volver arriba */
.to-top { position: fixed; right: 18px; bottom: 18px; width: 46px; height: 46px; border-radius: 50%; border: none;
  background: var(--primary); color: var(--on-primary); font-size: 1.2rem; cursor: pointer; opacity: 0; pointer-events: none; transition: opacity .3s; }
.to-top.show { opacity: 1; pointer-events: auto; }

/* 🧩 Lo que le vas agregando (botones y partes) */
.my-buttons { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-bottom: 16px; }
.my-buttons:empty { display: none; }
.my-btn { padding: 14px 16px; border-radius: 999px; border: none; background: var(--primary); color: var(--on-primary);
  font-weight: 700; cursor: pointer; transition: transform .15s, filter .15s; }
.my-btn:active { transform: scale(.97); filter: brightness(1.1); }
.my-parts { display: flex; flex-direction: column; gap: 16px; }
.parte { background: var(--surface); border-radius: 18px; padding: 18px; }
.toast-msg { position: fixed; left: 50%; bottom: 24px; transform: translate(-50%, 20px); opacity: 0; pointer-events: none;
  background: var(--text); color: var(--bg); padding: 12px 18px; border-radius: 14px; transition: opacity .3s, transform .3s; z-index: 50; }
.toast-msg.show { opacity: 1; transform: translate(-50%, 0); }

/* Aparecer al bajar */
.reveal { opacity: 0; transform: translateY(18px); transition: opacity .6s, transform .6s; }
.reveal.visible { opacity: 1; transform: none; }

/* 📱 Celular */
@media (max-width: 760px) {
  .menu-btn { display: block; }
  .links { position: absolute; top: 64px; left: 0; right: 0; flex-direction: column; gap: 0; background: var(--surface);
    border-bottom: 1px solid var(--divider); max-height: 0; overflow: hidden; transition: max-height .3s; }
  .links.open { max-height: 420px; }
  .links a { padding: 14px 4%; border-top: 1px solid var(--divider); }
  .contact { grid-template-columns: 1fr; }
  .hero { padding: 72px 0 64px; }
}"""
        val js = """// 🌐 Lógica de la página: menú del celular, sección activa, aparecer al bajar, formulario y volver arriba
(function () {
  var menuBtn = document.getElementById('menuBtn');
  var links = document.getElementById('links');
  // ☰ Abrir y cerrar el menú en el celular
  menuBtn.addEventListener('click', function () { links.classList.toggle('open'); });
  links.querySelectorAll('a').forEach(function (a) {
    a.addEventListener('click', function () { links.classList.remove('open'); });
  });

  // ✨ Las secciones aparecen suavemente al bajar
  var reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('visible'); io.unobserve(e.target); } });
    }, { threshold: 0.12 });
    reveals.forEach(function (r) { io.observe(r); });
  } else {
    reveals.forEach(function (r) { r.classList.add('visible'); });
  }

  // 📍 Marca en el menú la sección que estás viendo y muestra el botón ↑
  var toTop = document.getElementById('toTop');
  var navLinks = links.querySelectorAll('a');
  window.addEventListener('scroll', function () {
    toTop.classList.toggle('show', window.scrollY > 500);
    var current = '';
    document.querySelectorAll('section[id]').forEach(function (s) {
      if (window.scrollY + 90 >= s.offsetTop) current = s.id;
    });
    navLinks.forEach(function (a) { a.classList.toggle('active', a.getAttribute('href') === '#' + current); });
  });
  toTop.addEventListener('click', function () { window.scrollTo({ top: 0, behavior: 'smooth' }); });

  // ✉️ Formulario de contacto (aquí se puede conectar a un correo o a un servidor)
  var form = document.getElementById('contactForm');
  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    document.getElementById('formOk').hidden = false;
    form.reset();
  });

  // © Año actual en el pie de página
  document.getElementById('year').textContent = new Date().getFullYear();
})();"""
        return listOf("index.html" to html, "style.css" to css, "botones.js" to botonesJs(emptyList(), page = true), "script.js" to js)
    }

    // ------------------------------------------------------------------ contenido en líneas (para poder cambiarlo después)
    /** La app descrita en las mismas líneas que escribe la IA (así se le puede pedir que cambie algo). */
    fun specToText(s: AppSpec): String = buildString {
        appendLine("TITULO: ${s.title}"); appendLine("SUBTITULO: ${s.subtitle}"); appendLine("EMOJI: ${s.emoji}")
        s.menu.forEach { appendLine("MENU: $it") }
        s.settings.forEach { (sw, t) -> appendLine("AJUSTE: ${if (sw) "switch" else "boton"} | $t") }
        appendLine("BIENVENIDA: ${s.welcome}"); appendLine("SUBBIENVENIDA: ${s.welcomeSub}")
        s.cards.forEach { (t, d) -> appendLine("TARJETA: $t | $d") }
        s.chips.forEach { appendLine("ATAJO: $it") }
        append("PISTA: ${s.hint}")
    }

    /** La página descrita en las mismas líneas que escribe la IA. */
    fun pageSpecToText(s: PageSpec): String = buildString {
        appendLine("TITULO: ${s.title}"); appendLine("LEMA: ${s.tagline}"); appendLine("EMOJI: ${s.emoji}"); appendLine("BOTON: ${s.cta}")
        s.sections.forEach { (t, b) -> appendLine("SECCION: $t | $b") }
        s.features.forEach { (t, d) -> appendLine("DESTACADO: $t | $d") }
        s.contact.forEach { appendLine("CONTACTO: $it") }
        append("PIE: ${s.footer}")
    }

}


