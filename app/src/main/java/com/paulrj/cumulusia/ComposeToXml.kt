package com.paulrj.cumulusia

/**
 * 🧩 JETPACK COMPOSE → MAQUETA
 *
 * Una app hecha con Compose arma sus pantallas con código (@Composable: Column, Row, Box, Text, Button, Scaffold…) en
 * vez de diseños XML. Este traductor lee ese código y arma los diseños XML equivalentes (LinearLayout, FrameLayout,
 * TextView, MaterialButton…), con sus colores, formas, tamaños, posiciones y su tema (lightColorScheme / darkColorScheme).
 * Así la maqueta de siempre los dibuja con todo lo que ya sabe hacer (paneles laterales, ventanas, menús, colores…),
 * y lo que hacen los botones (onClick) se pasa a la lógica de la maqueta: avisos (Toast), abrir el panel lateral,
 * cambiar de pantalla (navController.navigate), abrir una ventana (AlertDialog) o un menú (DropdownMenu).
 *
 * No necesita Android: solo lee texto. Si algo no lo entiende, lo salta (la maqueta sigue con lo demás).
 */
object ComposeToXml {

    /** Lo que sale: los diseños (nombre → XML), los dibujos de fondo y estilos (como recursos), y la lógica (maqueta.js). */
    class Result(
        val layouts: LinkedHashMap<String, String>,
        val resources: List<Pair<String, String>>,   // (nombre del archivo, contenido): themes / shapes / selectores
        val themeName: String,
        val js: String,
        val night: Boolean
    )

    // ------------------------------------------------------------------ nodos (lo que será cada vista del XML)
    private class Node(val tag: String) {
        val attrs = LinkedHashMap<String, String>()
        val kids = mutableListOf<Node>()
        var clickJs: String? = null       // lo que hace al tocarlo (JavaScript de la maqueta)
        var transparent = false           // (MaterialTheme, CompositionLocalProvider…: solo pasan a sus hijos)
        operator fun set(k: String, v: String?) { if (v != null) attrs[k] = v }
        operator fun get(k: String) = attrs[k]
    }

    private const val A = "android:"
    private const val APP = "app:"

    // ------------------------------------------------------------------ estado de una traducción
    private class Ctx(val night: Boolean) {
        val funs = HashMap<String, Pair<String, String>>()       // @Composable fun nombre → (parámetros, cuerpo)
        val colorVals = HashMap<String, String>()                 // val Purple40 = Color(0xFF6650A4) → #6650A4
        val scheme = HashMap<String, String>()                    // primary → #…
        val res = mutableListOf<Pair<String, String>>()
        val js = StringBuilder()
        val layouts = LinkedHashMap<String, String>()
        val dialogs = HashMap<String, String>()                   // variable que la muestra (showDialog) → nombre del diseño
        val menus = HashMap<String, List<String>>()               // variable que lo abre (expanded) → opciones
        val routes = LinkedHashMap<String, String>()              // ruta de navegación → cuerpo de la pantalla
        var n = 0
        var stateBools = HashMap<String, Boolean>()
        var code = ""                                             // (todo el código: para leer las listas de ejemplo)
        val dataClasses = HashMap<String, List<String>>()         // data class Mensaje(val texto, val mio) → [texto, mio]
        var stateStrings = HashMap<String, String>()
        var depth = 0
        var route: String? = null                                 // (la ruta que se está dibujando dentro del NavHost)
        val clicks = mutableListOf<Triple<String, String, Map<String, String>>>()   // (id, código del onClick, estados de esa pantalla)
        val templates = HashMap<String, MutableList<Pair<String, String>>>()       // estado numérico → (id del texto, "Tocaste $cuenta veces")
        var startRoute: String? = null
    }

    /** [files]: (nombre, contenido) de los .kt del proyecto. [night]: el teléfono está en modo oscuro. null si no es Compose. */
    fun convert(files: List<Pair<String, String>>, night: Boolean): Result? {
        val code = files.filter { it.first.endsWith(".kt") }.joinToString("\n\n") { stripComments(it.second) }
        if (!code.contains("@Composable")) return null
        val dark = night && Regex("isSystemInDarkTheme\\(\\)").containsMatchIn(code) && code.contains("darkColorScheme")
        val c = Ctx(dark)
        c.code = code
        Regex("\\bdata\\s+class\\s+(\\w+)\\s*\\(").findAll(code).forEach { m ->
            val cl = matchClose(code, m.range.last); if (cl > 0) c.dataClasses[m.groupValues[1]] =
                splitTop(code.substring(m.range.last + 1, cl)).mapNotNull { Regex("(?:val|var)\\s+(\\w+)").find(it)?.groupValues?.get(1) } }
        readColors(code, c)
        readFunctions(code, c)
        readScheme(code, c)
        c.res += "cmp_theme.xml" to themeXml(c)
        // 🧭 Pantallas: las rutas de navegación (NavHost → composable("ruta") { … }), o lo que pone setContent { … }
        val screens = LinkedHashMap<String, String>()
        Regex("\\bcomposable\\s*\\(").findAll(code).forEach { m ->
            val cp = matchClose(code, m.range.last); if (cp < 0) return@forEach
            val route = Regex("^\\s*(?:route\\s*=\\s*)?\"([^\"]+)\"").find(code.substring(m.range.last + 1, cp))?.groupValues?.get(1) ?: return@forEach
            val ob = code.indexOf('{', cp); if (ob < 0 || code.substring(cp + 1, ob).isNotBlank()) return@forEach
            block(code, ob)?.let { screens[safeName(route)] = it.replace(Regex("^\\s*\\w+\\s*->"), ""); c.routes[route] = safeName(route) }
        }
        // (ventanas y menús que se abren con una variable: se conocen antes, para que el botón que los abre los encuentre)
        Regex("if\\s*\\(\\s*(\\w+)\\s*\\)\\s*\\{?\\s*(?:AlertDialog|Dialog|ModalBottomSheet|BasicAlertDialog)\\s*\\(").findAll(code).forEach { c.dialogs[it.groupValues[1]] = "dialog_" + safeName(it.groupValues[1]) }
        Regex("\\bDropdownMenu\\s*\\(").findAll(code).forEach { m ->
            val cp = matchClose(code, m.range.last); if (cp < 0) return@forEach
            val v = Regex("expanded\\s*=\\s*(\\w+)").find(code.substring(m.range.last, cp))?.groupValues?.get(1) ?: return@forEach
            val ob = code.indexOf('{', cp); val lam = if (ob >= 0 && code.substring(cp + 1, ob).isBlank()) block(code, ob).orEmpty() else code.substring(m.range.last, cp)
            val ops = Regex("text\\s*=\\s*\\{\\s*Text\\s*\\(\\s*(?:text\\s*=\\s*)?\"([^\"]*)\"").findAll(lam).map { unq(it.groupValues[1]) }.toList()
            if (ops.isNotEmpty()) c.menus[v] = ops
        }
        val start = Regex("startDestination\\s*=\\s*\"([^\"]+)\"").find(code)?.groupValues?.get(1)?.let { safeName(it) }
        Regex("\\bsetContent\\s*\\{").findAll(code).forEach { m -> block(code, m.range.last)?.let { b -> screens.putIfAbsent("activity_main", b) } }
        if (screens.isEmpty()) {
            // (sin setContent: la función que parezca la pantalla principal)
            val main = c.funs.keys.firstOrNull { Regex("(Screen|App|Main|Home|Pantalla)$").containsMatchIn(it) } ?: c.funs.keys.firstOrNull()
            main?.let { screens["activity_main"] = "$it()" }
        }
        c.startRoute = Regex("startDestination\\s*=\\s*\"([^\"]+)\"").find(code)?.groupValues?.get(1)
        val mainBody = screens["activity_main"]
        if (c.routes.isNotEmpty() && mainBody != null) {
            // 🧭 Con navegación (NavHost): una pantalla por cada ruta, cada una con lo que la rodea (barras, menú de abajo…)
            val rs = c.routes.keys.sortedBy { if (it == c.startRoute) 0 else 1 }
            for (r in rs) {
                c.route = r; c.stateBools = HashMap(); c.stateStrings = HashMap(); readState(mainBody, c)
                c.layouts[safeName(r)] = rootXml(parseBlock(mainBody, c, false))
            }
            c.route = null
        } else {
            val order = screens.entries.sortedBy { if (it.key == start) 0 else if (it.key == "activity_main") 1 else 2 }
            for ((name, body) in order) {
                c.stateBools = HashMap(); c.stateStrings = HashMap()
                readState(body, c)
                c.layouts[name] = rootXml(parseBlock(body, c, false))
            }
        }
        // 👆 Lo que hace cada botón (ya con todas las ventanas, menús, rutas y textos conocidos)
        for ((id, body, states) in c.clicks) {
            val js = actionsJs(body, c) + counterJs(body, states, c)
            if (js.isNotBlank()) c.js.append("alClick('$id', function(){ $js });\n")
        }
        if (c.js.contains("ST.")) c.js.insert(0, "var ST=window.ST||(window.ST={});\n")
        return Result(c.layouts, c.res, "ComposeTheme", c.js.toString(), dark)
    }

    // ------------------------------------------------------------------ lectura de colores, funciones y tema
    private fun readColors(code: String, c: Ctx) {
        Regex("\\bval\\s+(\\w+)\\s*(?::\\s*Color\\s*)?=\\s*(Color\\s*\\([^)]*\\)|Color\\.\\w+)").findAll(code).forEach { m ->
            colorOf(m.groupValues[2], c)?.let { c.colorVals[m.groupValues[1]] = it }
        }
    }

    private fun readFunctions(code: String, c: Ctx) {
        Regex("@Composable\\s+(?:(?:private|internal|public|inline)\\s+)*fun\\s+(?:[\\w.]+\\.)?(\\w+)\\s*\\(").findAll(code).forEach { m ->
            val open = m.range.last
            val close = matchClose(code, open); if (close < 0) return@forEach
            val params = code.substring(open + 1, close)
            val ob = code.indexOf('{', close); if (ob < 0) return@forEach
            // (entre los paréntesis y la llave solo puede ir el tipo que devuelve: «: Unit»)
            val between = code.substring(close + 1, ob).trim()
            if (between.isNotEmpty() && !Regex("^:\\s*[\\w.<>?, ]+$").matches(between)) return@forEach
            block(code, ob)?.let { c.funs.putIfAbsent(m.groupValues[1], params to it) }
        }
    }

    private val M3_LIGHT = mapOf("primary" to "#6750A4", "onPrimary" to "#FFFFFF", "primaryContainer" to "#EADDFF", "onPrimaryContainer" to "#21005D",
        "secondary" to "#625B71", "onSecondary" to "#FFFFFF", "secondaryContainer" to "#E8DEF8", "onSecondaryContainer" to "#1D192B",
        "tertiary" to "#7D5260", "onTertiary" to "#FFFFFF", "tertiaryContainer" to "#FFD8E4", "onTertiaryContainer" to "#31111D",
        "background" to "#FEF7FF", "onBackground" to "#1D1B20", "surface" to "#FEF7FF", "onSurface" to "#1D1B20",
        "surfaceVariant" to "#E7E0EC", "onSurfaceVariant" to "#49454F", "outline" to "#79747E", "outlineVariant" to "#CAC4D0",
        "error" to "#B3261E", "onError" to "#FFFFFF", "errorContainer" to "#F9DEDC", "inverseSurface" to "#322F35", "inverseOnSurface" to "#F5EFF7",
        "surfaceContainer" to "#F3EDF7", "surfaceContainerLow" to "#F7F2FA", "surfaceContainerLowest" to "#FFFFFF",
        "surfaceContainerHigh" to "#ECE6F0", "surfaceContainerHighest" to "#E6E0E9", "surfaceTint" to "#6750A4", "scrim" to "#000000")
    private val M3_DARK = mapOf("primary" to "#D0BCFF", "onPrimary" to "#381E72", "primaryContainer" to "#4F378B", "onPrimaryContainer" to "#EADDFF",
        "secondary" to "#CCC2DC", "onSecondary" to "#332D41", "secondaryContainer" to "#4A4458", "onSecondaryContainer" to "#E8DEF8",
        "tertiary" to "#EFB8C8", "onTertiary" to "#492532", "tertiaryContainer" to "#633B48", "onTertiaryContainer" to "#FFD8E4",
        "background" to "#141218", "onBackground" to "#E6E0E9", "surface" to "#141218", "onSurface" to "#E6E0E9",
        "surfaceVariant" to "#49454F", "onSurfaceVariant" to "#CAC4D0", "outline" to "#938F99", "outlineVariant" to "#49454F",
        "error" to "#F2B8B5", "onError" to "#601410", "errorContainer" to "#8C1D18", "inverseSurface" to "#E6E0E9", "inverseOnSurface" to "#322F35",
        "surfaceContainer" to "#211F26", "surfaceContainerLow" to "#1D1B20", "surfaceContainerLowest" to "#0F0D13",
        "surfaceContainerHigh" to "#2B2930", "surfaceContainerHighest" to "#36343B", "surfaceTint" to "#D0BCFF", "scrim" to "#000000")

    /** 🎨 Los colores de la app: lightColorScheme(primary = Purple40, …) o darkColorScheme(…) si el teléfono está oscuro. */
    private fun readScheme(code: String, c: Ctx) {
        c.scheme.putAll(if (c.night) M3_DARK else M3_LIGHT)
        val fn = if (c.night) "darkColorScheme" else "lightColorScheme"
        Regex("\\b$fn\\s*\\(").find(code)?.let { m ->
            val close = matchClose(code, m.range.last); if (close < 0) return@let
            splitTop(code.substring(m.range.last + 1, close)).forEach { a ->
                val (k, v) = named(a) ?: return@forEach
                colorOf(v, c)?.let { c.scheme[k] = it }
            }
        }
    }

    private fun themeXml(c: Ctx): String {
        val s = c.scheme
        fun i(k: String, v: String?) = v?.let { "<item name=\"$k\">$it</item>" } ?: ""
        return "<resources><style name=\"ComposeTheme\" parent=\"Theme.Material3.${if (c.night) "Dark" else "Light"}.NoActionBar\">" +
            i("colorPrimary", s["primary"]) + i("colorOnPrimary", s["onPrimary"]) + i("colorPrimaryContainer", s["primaryContainer"]) +
            i("colorOnPrimaryContainer", s["onPrimaryContainer"]) + i("colorSecondary", s["secondary"]) + i("colorSecondaryContainer", s["secondaryContainer"]) +
            i("colorOnSecondaryContainer", s["onSecondaryContainer"]) + i("colorTertiary", s["tertiary"]) +
            i("colorSurface", s["surface"]) + i("android:colorBackground", s["background"]) + i("android:windowBackground", s["background"]) +
            i("colorOnSurface", s["onSurface"]) + i("colorOnBackground", s["onBackground"]) + i("android:textColorPrimary", s["onSurface"]) +
            i("android:textColorSecondary", s["onSurfaceVariant"]) + i("colorOutline", s["outline"]) + i("colorError", s["error"]) +
            "</style></resources>"
    }

    /** Valores iniciales de los estados: var mostrar by remember { mutableStateOf(false) }, var nombre by … ("Ana"). */
    private fun readState(body: String, c: Ctx) {
        Regex("(?:var|val)\\s+(\\w+)\\s*(?:by|=)\\s*(?:remember|rememberSaveable)\\s*(?:\\([^)]*\\))?\\s*\\{\\s*mutable(?:Int|Float|Long|Double)?StateOf\\s*\\(\\s*([^)]*?)\\s*\\)").findAll(body).forEach { m ->
            val v = m.groupValues[2].trim()
            when {
                v == "true" -> c.stateBools[m.groupValues[1]] = true
                v == "false" -> c.stateBools[m.groupValues[1]] = false
                v.startsWith("\"") -> c.stateStrings[m.groupValues[1]] = unq(v)
                Regex("^-?[0-9.]+[fFL]?$").matches(v) -> c.stateStrings[m.groupValues[1]] = v.trimEnd('f', 'F', 'L')
            }
        }
    }

    // ------------------------------------------------------------------ utilidades de texto
    private fun stripComments(s: String): String {
        val out = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (s.startsWith("\"\"\"", i)) { val e = s.indexOf("\"\"\"", i + 3).let { if (it < 0) s.length else it + 3 }; out.append(s, i, e); i = e; continue }
            if (ch == '"') { var j = i + 1; while (j < s.length && s[j] != '"' && s[j] != '\n') { if (s[j] == '\\') j++; j++ }; out.append(s, i, minOf(j + 1, s.length)); i = j + 1; continue }
            if (ch == '\'' ) { var j = i + 1; while (j < s.length && s[j] != '\'' && j - i < 4) { if (s[j] == '\\') j++; j++ }; out.append(s, i, minOf(j + 1, s.length)); i = j + 1; continue }
            if (s.startsWith("//", i)) { while (i < s.length && s[i] != '\n') i++; continue }
            if (s.startsWith("/*", i)) { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2; continue }
            out.append(ch); i++
        }
        return out.toString()
    }

    /** Índice del cierre que corresponde a la apertura ( [ { en [open] (saltando textos); -1 si no hay. */
    private fun matchClose(s: String, open: Int): Int {
        if (open !in s.indices) return -1
        var d = 0; var i = open
        while (i < s.length) {
            val ch = s[i]
            if (s.startsWith("\"\"\"", i)) { val e = s.indexOf("\"\"\"", i + 3); i = if (e < 0) return -1 else e + 3; continue }
            if (ch == '"') { i++; while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; else if (s[i] == '$' && i + 1 < s.length && s[i + 1] == '{') { val e = matchClose(s, i + 1); if (e > 0) i = e }; i++ }; i++; continue }
            if (ch == '\'') { val e = s.indexOf('\'', i + 1); if (e in (i + 1)..(i + 4)) { i = e + 1; continue } }
            if (ch == '(' || ch == '[' || ch == '{') d++
            if (ch == ')' || ch == ']' || ch == '}') { d--; if (d == 0) return i }
            i++
        }
        return -1
    }

    private fun block(s: String, open: Int): String? { if (open !in s.indices || s[open] != '{') return null; val e = matchClose(s, open); return if (e < 0) null else s.substring(open + 1, e) }

    private fun splitTop(s: String): List<String> {
        val out = mutableListOf<String>(); var d = 0; var last = 0; var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') { i++; while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ }; i++; continue }
            if (ch == '(' || ch == '[' || ch == '{') d++
            if (ch == ')' || ch == ']' || ch == '}') d--
            if (ch == ',' && d == 0) { out += s.substring(last, i).trim(); last = i + 1 }
            if (ch == '-' && i + 1 < s.length && s[i + 1] == '>' && d == 0) { /* (lambda arrow) */ }
            i++
        }
        s.substring(last).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** «if (true) A else B» (después de poner los datos de la fila) → A. Si la condición no se sabe, queda igual. */
    private fun pickIf(v: String): String {
        val m = Regex("^(\\w+\\s*=\\s*)?if\\s*\\(\\s*(!?)\\s*(true|false)\\s*\\)\\s*(.+?)\\s+else\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).find(v.trim()) ?: return v
        val t = (m.groupValues[3] == "true") != (m.groupValues[2] == "!")
        return m.groupValues[1] + (if (t) m.groupValues[4] else m.groupValues[5]).trim()
    }

    /** «nombre = valor» → (nombre, valor); null si es un argumento sin nombre. */
    private fun named(a: String): Pair<String, String>? = Regex("^(\\w+)\\s*=(?!=)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(a.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() }

    private fun unq(v: String): String = v.trim().removeSurrounding("\"").replace(Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_]\\w*"), "…").replace("\\n", " ").replace("\\\"", "\"")

    private fun safeName(r: String) = r.replace(Regex("[^A-Za-z0-9_]"), "_").lowercase().ifEmpty { "pantalla" }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Argumentos de una llamada: los con nombre y los en orden. */
    private class Args(text: String) {
        val named = HashMap<String, String>(); val pos = mutableListOf<String>()
        init { splitTop(text).forEach { a0 -> val a = pickIf(a0); val nv = ComposeToXml.named(a); if (nv != null && !a.trim().startsWith("\"")) named[nv.first] = pickIf(nv.second) else pos += a } }
        operator fun get(k: String) = named[k]
    }

    // ------------------------------------------------------------------ valores: colores, medidas, formas
    private val NAMED = mapOf("Red" to "#FF0000", "Green" to "#00FF00", "Blue" to "#0000FF", "Black" to "#000000", "White" to "#FFFFFF",
        "Gray" to "#888888", "LightGray" to "#CCCCCC", "DarkGray" to "#444444", "Yellow" to "#FFFF00", "Cyan" to "#00FFFF",
        "Magenta" to "#FF00FF", "Transparent" to "#00000000")

    private fun colorOf(e0: String?, c: Ctx): String? {
        val e = e0?.trim() ?: return null
        Regex("^Color\\s*\\(\\s*0x([0-9A-Fa-f]{6,8})[uUL]*\\s*\\)").find(e)?.let { m ->
            val h = m.groupValues[1].uppercase(); return if (h.length == 8 && h.startsWith("FF")) "#" + h.substring(2) else "#$h" }
        Regex("^Color\\s*\\(\\s*(?:red\\s*=\\s*)?(\\d+)\\s*,\\s*(?:green\\s*=\\s*)?(\\d+)\\s*,\\s*(?:blue\\s*=\\s*)?(\\d+)").find(e)?.let { m ->
            val v = m.groupValues.drop(1).map { it.toInt().coerceIn(0, 255) }; return String.format("#%02X%02X%02X", v[0], v[1], v[2]) }
        Regex("^Color\\s*\\(\\s*android\\.graphics\\.Color\\.parseColor\\(\\s*\"(#[0-9A-Fa-f]+)\"").find(e)?.let { return it.groupValues[1] }
        Regex("^Color\\.(\\w+)").find(e)?.let { m -> NAMED[m.groupValues[1]]?.let { base ->
            // (Color.White.copy(alpha = 0.5f))
            Regex("alpha\\s*=\\s*([0-9.]+)").find(e)?.groupValues?.get(1)?.toFloatOrNull()?.let { a -> return "#" + String.format("%02X", (a * 255).toInt()) + base.removePrefix("#").takeLast(6) }
            return base } }
        Regex("MaterialTheme\\.colorScheme\\.(\\w+)").find(e)?.let { return c.scheme[it.groupValues[1]] }
        Regex("colorResource\\(\\s*(?:id\\s*=\\s*)?R\\.color\\.(\\w+)").find(e)?.let { return "@color/" + it.groupValues[1] }
        Regex("^(\\w+)(?:\\.copy\\(.*\\))?$").find(e)?.let { m -> c.colorVals[m.groupValues[1]]?.let { return it } }
        return null
    }

    /** 16.dp → 16dp · 20.sp → 20sp · (número suelto) → dp. */
    private fun dim(e0: String?, unit: String = "dp"): String? {
        val e = e0?.trim() ?: return null
        Regex("^\\(?\\s*(-?[0-9]+(?:\\.[0-9]+)?)[fF]?\\s*\\)?\\s*\\.\\s*(dp|sp)").find(e)?.let { return it.groupValues[1] + it.groupValues[2] }
        Regex("^(-?[0-9]+(?:\\.[0-9]+)?)[fF]?$").find(e)?.let { return it.groupValues[1] + unit }
        return null
    }

    /** Una forma: esquinas [arribaIzq, arribaDer, abajoDer, abajoIzq] (dp, «50%» = píldora), cortadas o un círculo. */
    private class Shape(val corners: List<String>, val cut: Boolean = false, val oval: Boolean = false)

    private fun shapeOf(e0: String?): Shape? {
        val e = e0?.trim() ?: return null
        if (Regex("^CircleShape").containsMatchIn(e)) return Shape(List(4) { "50%" }, oval = false)
        if (Regex("^RectangleShape").containsMatchIn(e)) return Shape(List(4) { "0dp" })
        Regex("MaterialTheme\\.shapes\\.(\\w+)").find(e)?.let { m ->
            val r = when (m.groupValues[1]) { "extraSmall" -> "4dp"; "small" -> "8dp"; "medium" -> "12dp"; "large" -> "16dp"; "extraLarge" -> "28dp"; else -> "12dp" }
            return Shape(List(4) { r }) }
        Regex("^(Rounded|Cut)CornerShape\\s*\\(").find(e)?.let { m ->
            val close = matchClose(e, m.range.last); val inner = if (close > 0) e.substring(m.range.last + 1, close) else ""
            val a = Args(inner); val cut = m.groupValues[1] == "Cut"
            fun one(v: String?): String? = v?.let { dim(it) ?: Regex("^(\\d+)$").find(it.trim())?.let { p -> "${p.groupValues[1]}%" } ?: Regex("percent\\s*=\\s*(\\d+)").find(it)?.let { p -> "${p.groupValues[1]}%" } }
            a["percent"]?.let { p -> return Shape(List(4) { "${p.trim()}%" }, cut) }
            a["size"]?.let { s -> one(s)?.let { return Shape(List(4) { _ -> it }, cut) } }
            if (a.pos.isNotEmpty() && a.named.isEmpty()) {
                val v = a.pos.map { one(it) ?: "0dp" }
                return if (v.size == 1) Shape(List(4) { v[0] }, cut) else Shape(listOf(v.getOrElse(0) { "0dp" }, v.getOrElse(1) { "0dp" }, v.getOrElse(2) { "0dp" }, v.getOrElse(3) { "0dp" }), cut)
            }
            return Shape(listOf(one(a["topStart"]) ?: "0dp", one(a["topEnd"]) ?: "0dp", one(a["bottomEnd"]) ?: "0dp", one(a["bottomStart"]) ?: "0dp"), cut)
        }
        return null
    }

    /** Un fondo dibujado (color + forma + borde) como recurso: @drawable/cmp_bg_N. */
    private fun bgDrawable(color: String?, shape: Shape?, strokeW: String?, strokeC: String?, c: Ctx): String {
        val name = "cmp_bg_${++c.n}"
        val sb = StringBuilder("<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">")
        color?.let { sb.append("<solid android:color=\"$it\"/>") }
        if (shape != null) {
            val k = shape.corners.map { if (it.endsWith("%")) "999dp" else it }
            if (k.toSet().size == 1) sb.append("<corners android:radius=\"${k[0]}\"/>")
            else sb.append("<corners android:topLeftRadius=\"${k[0]}\" android:topRightRadius=\"${k[1]}\" android:bottomRightRadius=\"${k[2]}\" android:bottomLeftRadius=\"${k[3]}\"/>")
        }
        if (strokeW != null) sb.append("<stroke android:width=\"$strokeW\" android:color=\"${strokeC ?: "#79747E"}\"/>")
        sb.append("</shape>")
        c.res += "$name.xml" to sb.toString()
        return "@drawable/$name"
    }

    /** Esquinas cortadas en diagonal: un estilo (shapeAppearanceOverlay) que la maqueta ya sabe leer. */
    private fun cutStyle(shape: Shape, c: Ctx, cut: Boolean = true): String {
        val name = "cmp_cut_${++c.n}"
        val n = listOf("TopLeft", "TopRight", "BottomRight", "BottomLeft")
        c.res += "$name.xml" to "<resources><style name=\"$name\"><item name=\"cornerFamily\">${if (cut) "cut" else "rounded"}</item>" +
            shape.corners.mapIndexed { i, v -> "<item name=\"cornerSize${n[i]}\">$v</item>" }.joinToString("") + "</style></resources>"
        return "@style/$name"
    }

    // ------------------------------------------------------------------ Modifier.…
    private class Mods {
        var w: String? = null; var h: String? = null; var weight: String? = null
        val margin = arrayOf<String?>(null, null, null, null)   // izq, arriba, der, abajo
        val pad = arrayOf<String?>(null, null, null, null)
        var bg: String? = null; var shape: Shape? = null; var strokeW: String? = null; var strokeC: String? = null
        var gravity: String? = null; var tx: String? = null; var ty: String? = null; var rot: String? = null
        var click: String? = null; var elevation: String? = null; var minH: String? = null; var minW: String? = null
    }

    private fun modsOf(e0: String?, c: Ctx): Mods {
        val m = Mods(); val e = e0?.trim() ?: return m
        var sawBg = false
        var i = Regex("^(?:Modifier|modifier)").find(e)?.range?.last?.plus(1) ?: return m
        while (i < e.length) {
            while (i < e.length && e[i].isWhitespace()) i++
            if (i >= e.length || e[i] != '.') break
            val nm = Regex("^\\.(\\w+)").find(e.substring(i)) ?: break
            i += nm.value.length
            var args = ""
            while (i < e.length && e[i] == ' ') i++
            if (i < e.length && e[i] == '(') { val cl = matchClose(e, i); if (cl < 0) break; args = e.substring(i + 1, cl); i = cl + 1 }
            var lam: String? = null
            val j = e.substring(i).indexOfFirst { !it.isWhitespace() }.let { if (it < 0) -1 else i + it }
            if (j >= 0 && e[j] == '{') { lam = block(e, j); i = matchClose(e, j) + 1 }
            val a = Args(args)
            fun four(arr: Array<String?>) {
                val all = a.pos.firstOrNull()?.let { dim(it) } ?: a["all"]?.let { dim(it) }
                all?.let { v -> for (k in 0..3) arr[k] = v }
                a["horizontal"]?.let { dim(it) }?.let { arr[0] = it; arr[2] = it }
                a["vertical"]?.let { dim(it) }?.let { arr[1] = it; arr[3] = it }
                (a["start"] ?: a["left"])?.let { dim(it) }?.let { arr[0] = it }
                a["top"]?.let { dim(it) }?.let { arr[1] = it }
                (a["end"] ?: a["right"])?.let { dim(it) }?.let { arr[2] = it }
                a["bottom"]?.let { dim(it) }?.let { arr[3] = it }
            }
            when (nm.groupValues[1]) {
                "fillMaxWidth" -> m.w = "match_parent"
                "fillMaxHeight" -> m.h = "match_parent"
                "fillMaxSize" -> { m.w = "match_parent"; m.h = "match_parent" }
                "width", "requiredWidth" -> m.w = dim(a.pos.firstOrNull()) ?: m.w
                "height", "requiredHeight" -> m.h = dim(a.pos.firstOrNull()) ?: m.h
                "size", "requiredSize" -> { val v = a.pos.map { dim(it) }; m.w = v.getOrNull(0) ?: dim(a["width"]) ?: m.w; m.h = v.getOrNull(1) ?: v.getOrNull(0) ?: dim(a["height"]) ?: m.h }
                "heightIn", "defaultMinSize" -> { m.minH = dim(a["min"] ?: a["minHeight"]) ?: m.minH; m.minW = dim(a["minWidth"]) ?: m.minW }
                "widthIn" -> m.minW = dim(a["min"]) ?: m.minW
                "weight" -> m.weight = a.pos.firstOrNull()?.trim()?.removeSuffix("f")?.removeSuffix("F") ?: "1"
                "padding" -> four(if (sawBg) m.pad else m.margin)
                "background" -> { sawBg = true; m.bg = colorOf(a.pos.firstOrNull() ?: a["color"], c) ?: m.bg; shapeOf(a.pos.getOrNull(1) ?: a["shape"])?.let { m.shape = it } }
                "clip" -> { sawBg = true; shapeOf(a.pos.firstOrNull() ?: a["shape"])?.let { m.shape = it } }
                "border" -> { sawBg = true; m.strokeW = dim(a.pos.getOrNull(0) ?: a["width"]); m.strokeC = colorOf(a.pos.getOrNull(1) ?: a["color"], c); shapeOf(a.pos.getOrNull(2) ?: a["shape"])?.let { m.shape = it } }
                "align" -> m.gravity = gravityOf(a.pos.firstOrNull())
                "offset" -> { m.tx = dim(a.pos.getOrNull(0) ?: a["x"]); m.ty = dim(a.pos.getOrNull(1) ?: a["y"]) }
                "rotate" -> m.rot = a.pos.firstOrNull()?.trim()?.removeSuffix("f")
                "shadow" -> m.elevation = dim(a.pos.firstOrNull() ?: a["elevation"])
                "clickable", "combinedClickable", "toggleable", "selectable" -> m.click = lam ?: a["onClick"]?.let { lambdaBody(it) } ?: ""
            }
        }
        return m
    }

    private fun lambdaBody(e: String): String? { val t = e.trim(); return if (t.startsWith("{")) block(t, 0) else t }

    /** Alignment.TopEnd → top|end · Alignment.CenterHorizontally → center_horizontal … */
    private fun gravityOf(e0: String?): String? {
        val e = e0?.substringAfterLast('.')?.trim() ?: return null
        return when (e) {
            "TopStart" -> "top|start"; "TopCenter" -> "top|center_horizontal"; "TopEnd" -> "top|end"
            "CenterStart" -> "center_vertical|start"; "Center" -> "center"; "CenterEnd" -> "center_vertical|end"
            "BottomStart" -> "bottom|start"; "BottomCenter" -> "bottom|center_horizontal"; "BottomEnd" -> "bottom|end"
            "CenterHorizontally" -> "center_horizontal"; "CenterVertically" -> "center_vertical"
            "Start" -> "start"; "End" -> "end"; "Top" -> "top"; "Bottom" -> "bottom"
            else -> null
        }
    }

    /** Pone en el nodo lo que dice su Modifier (tamaño, márgenes, relleno, fondo con forma y borde, lugar, giro…). */
    private fun apply(n: Node, m: Mods, c: Ctx, defW: String = "wrap_content", defH: String = "wrap_content") {
        n["${A}layout_width"] = m.w ?: n["${A}layout_width"] ?: defW
        n["${A}layout_height"] = m.h ?: n["${A}layout_height"] ?: defH
        m.weight?.let { n["${A}layout_weight"] = it }
        val ml = listOf("layout_marginStart", "layout_marginTop", "layout_marginEnd", "layout_marginBottom")
        val pl = listOf("paddingStart", "paddingTop", "paddingEnd", "paddingBottom")
        m.margin.forEachIndexed { i, v -> v?.let { n["$A${ml[i]}"] = it } }
        m.pad.forEachIndexed { i, v -> v?.let { n["$A${pl[i]}"] = it } }
        if (m.bg != null || m.strokeW != null || (m.shape != null && n["${A}background"] != null)) {
            val shape = m.shape
            if (shape != null && shape.cut) { m.bg?.let { n["${A}background"] = it }; n["${APP}shapeAppearanceOverlay"] = cutStyle(shape, c) }
            else if (shape != null || m.strokeW != null) n["${A}background"] = bgDrawable(m.bg ?: n["${A}background"]?.takeIf { it.startsWith("#") }, shape, m.strokeW, m.strokeC, c)
            else n["${A}background"] = m.bg
        } else if (m.shape != null) n["__shape"] = "1"
        m.gravity?.let { n["${A}layout_gravity"] = it }
        m.tx?.let { n["${A}translationX"] = it }; m.ty?.let { n["${A}translationY"] = it }
        m.rot?.let { n["${A}rotation"] = it }
        m.elevation?.let { n["${A}elevation"] = it }
        m.minH?.let { n["${A}minHeight"] = it }; m.minW?.let { n["${A}minWidth"] = it }
        m.click?.let { setClick(n, it, c) }
    }

    // ------------------------------------------------------------------ lo que hacen los botones (onClick)
    private fun setClick(n: Node, body: String, c: Ctx) {
        if (body.isBlank()) return
        val id = n["${A}id"]?.substringAfter('/') ?: "cmp_${++c.n}".also { n["${A}id"] = "@+id/$it" }
        // (la acción se arma al final, cuando ya se conocen todas las ventanas, menús y textos de la pantalla)
        c.clicks += Triple(id, body, HashMap(c.stateStrings))
    }

    /** 🔢 Contadores: «cuenta++», «cuenta += 2», «cuenta = 0» cambian los textos que muestran «$cuenta». */
    private fun counterJs(body: String, states: Map<String, String>, c: Ctx): String {
        val out = StringBuilder()
        for ((v, list) in c.templates) {
            val init = states[v]?.toDoubleOrNull() ?: continue
            val ops = mutableListOf<String>()
            Regex("\\b$v\\s*\\+\\+|\\+\\+\\s*$v\\b").findAll(body).forEach { ops += "+1" }
            Regex("\\b$v\\s*--|--\\s*$v\\b").findAll(body).forEach { ops += "-1" }
            Regex("\\b$v\\s*\\+=\\s*(-?[0-9.]+)").findAll(body).forEach { ops += "+" + it.groupValues[1] }
            Regex("\\b$v\\s*-=\\s*(-?[0-9.]+)").findAll(body).forEach { ops += "-" + it.groupValues[1] }
            Regex("\\b$v\\s*=\\s*(-?[0-9.]+)[fFL]?\\b").findAll(body).forEach { ops += "=" + it.groupValues[1] }
            if (ops.isEmpty()) continue
            val iv = if (init % 1.0 == 0.0) init.toLong().toString() else init.toString()
            ops.forEach { op -> out.append(if (op.startsWith("=")) "ST.$v=${op.drop(1)};" else "ST.$v=(ST.$v!=null?ST.$v:$iv)$op;") }
            list.forEach { (id, tpl) ->
                val parts = Regex("\\$\\{\\s*$v\\s*\\}|\\$$v(?!\\w)").split(tpl)
                out.append("texto('$id', ").append(parts.joinToString("+ST.$v+") { jsq(unq("\"" + it + "\"")) }).append(");")
            }
        }
        return out.toString()
    }

    private fun actionsJs(body: String, c: Ctx): String {
        val out = mutableListOf<Pair<Int, String>>()
        Regex("makeText\\([^,]+,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body).forEach { out += it.range.first to "aviso(${jsq(unq(it.groupValues[1]))});" }
        Regex("showSnackbar\\(\\s*(?:message\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body).forEach { out += it.range.first to "aviso(${jsq(unq(it.groupValues[1]))});" }
        Regex("\\.open\\(\\)").findAll(body).forEach { m -> if (body.substring(0, m.range.first).contains("rawer")) out += m.range.first to "cajon('L');" }
        Regex("\\.close\\(\\)").findAll(body).forEach { m -> if (body.substring(0, m.range.first).contains("rawer")) out += m.range.first to "cajon(0);" }
        Regex("navigate\\(\\s*\"([^\"]+)\"").findAll(body).forEach { m -> c.routes[m.groupValues[1]]?.let { out += m.range.first to "abrir('$it');" } }
        Regex("\\b(popBackStack|navigateUp)\\(").findAll(body).forEach { out += it.range.first to "show(0);" }
        // (mostrar = true → la ventana o el menú que dependen de esa variable)
        Regex("\\b(\\w+)\\s*=\\s*(true|!\\s*\\1)\\b").findAll(body).forEach { m ->
            val v = m.groupValues[1]
            c.dialogs[v]?.let { out += m.range.first to "abrir('$it', 1);" }
            c.menus[v]?.let { ops -> out += m.range.first to "menu(${ops.joinToString(",", "[", "]") { jsq(it) }}, null, 0, 1);" }
        }
        Regex("\\b(\\w+)\\s*=\\s*false\\b").findAll(body).forEach { m -> if (c.dialogs.containsKey(m.groupValues[1])) out += m.range.first to "cerrarVentana();" }
        return out.sortedBy { it.first }.map { it.second }.distinct().joinToString(" ")
    }

    private fun jsq(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    // ------------------------------------------------------------------ el árbol de composables
    private val CONTROL_SKIP = setOf("LaunchedEffect", "DisposableEffect", "SideEffect", "BackHandler", "rememberCoroutineScope", "remember")

    /** Lee un bloque de código Compose y devuelve sus vistas (en orden). [row]: el padre es una fila. */
    private fun parseBlock(body: String, c: Ctx, row: Boolean): List<Node> {
        val out = mutableListOf<Node>()
        if (c.depth > 14) return out
        c.depth++
        try {
            var i = 0
            val s = body
            while (i < s.length) {
                val ch = s[i]
                if (ch.isWhitespace() || ch == ';' || ch == ',') { i++; continue }
                if (ch == '"') { val e = s.indexOf('"', i + 1); i = if (e < 0) s.length else e + 1; continue }
                val word = Regex("^[A-Za-z_][\\w.]*").find(s.substring(i, minOf(s.length, i + 120)))?.value
                if (word == null) { i++; continue }
                when {
                    word == "val" || word == "var" -> { i = endOfStatement(s, i); continue }
                    word == "if" -> {
                        val po = s.indexOf('(', i); val pc = matchClose(s, po)
                        if (po < 0 || pc < 0) { i += 2; continue }
                        val cond = s.substring(po + 1, pc).trim()
                        val (thenTxt, after) = branch(s, pc + 1)
                        var j = after
                        val elseM = Regex("^\\s*else\\b").find(s.substring(j))
                        var elseTxt: String? = null
                        if (elseM != null) { val r = branch(s, j + elseM.value.length); elseTxt = r.first; j = r.second }
                        // (una ventana o un menú que se muestra con una variable: va aparte, y el botón la abre)
                        val show = condValue(cond, c)
                        val dlg = Regex("^!?\\s*(\\w+)$").find(cond)?.groupValues?.get(1)
                        if (dlg != null && Regex("\\b(AlertDialog|Dialog|ModalBottomSheet|BasicAlertDialog)\\s*\\(").containsMatchIn(thenTxt) && !cond.startsWith("!")) {
                            buildDialog(dlg, thenTxt, c)
                        } else if (show != false) out += parseBlock(thenTxt, c, row)
                        else if (elseTxt != null) out += parseBlock(elseTxt, c, row)
                        i = j; continue
                    }
                    word == "when" -> { val ob = s.indexOf('{', i); val e = if (ob < 0) -1 else matchClose(s, ob); i = if (e < 0) i + 4 else e + 1; continue }
                    word == "for" || word.endsWith(".forEach") || word.endsWith(".forEachIndexed") || word == "repeat" || word == "items" || word == "itemsIndexed" -> {
                        // 🔁 Lo que se repite (una lista): se dibuja 3 veces, como en la maqueta de las listas
                        val ob = s.indexOf('{', i); val e = if (ob < 0) -1 else matchClose(s, ob)
                        if (e < 0) { i += word.length; continue }
                        val times = if (word == "repeat") Regex("repeat\\s*\\(\\s*(\\d+)").find(s.substring(i))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 3 else 3
                        val head = s.substring(i, ob)
                        val raw = s.substring(ob + 1, e)
                        val loopVar = Regex("^\\s*\\(?\\s*(?:\\w+\\s*,\\s*)?(\\w+)\\s*\\)?\\s*->").find(raw)?.groupValues?.get(1)
                            ?: Regex("for\\s*\\(\\s*(\\w+)\\s+in").find(head)?.groupValues?.get(1) ?: "it"
                        val inner = raw.replace(Regex("^\\s*[\\w, ()]+->"), "")
                        // 📋 Los datos de ejemplo de la lista (listOf / mutableStateListOf con sus textos): cada fila con los suyos
                        val listName = Regex("(?:items|itemsIndexed)\\s*\\(\\s*(?:items\\s*=\\s*)?(\\w+)").find(head)?.groupValues?.get(1)
                            ?: Regex("(\\w+)\\.forEach").find(head)?.groupValues?.get(1) ?: Regex("\\bin\\s+(\\w+)").find(head)?.groupValues?.get(1)
                        val rows = listName?.let { sampleRows(it, c) }.orEmpty()
                        if (rows.isNotEmpty()) rows.take(8).forEach { r -> out += parseBlock(fillRow(inner, loopVar, r), c, row) }
                        else repeat(times) { out += parseBlock(inner, c, row) }
                        i = e + 1; continue
                    }
                    word == "item" || word == "stickyHeader" -> { val ob = s.indexOf('{', i); val e = if (ob < 0) -1 else matchClose(s, ob); if (e > 0) out += parseBlock(s.substring(ob + 1, e), c, row); i = if (e < 0) i + 4 else e + 1; continue }
                    word == "return" || word == "else" -> { i += word.length; continue }
                    word.first().isUpperCase() && !word.contains('.') && word !in CONTROL_SKIP -> {
                        val r = parseCall(s, i + word.length, word, c, row)
                        if (r == null) { i = endOfStatement(s, i); continue }
                        out += r.first; i = r.second; continue
                    }
                    else -> { i = endOfStatement(s, i); continue }
                }
            }
        } finally { c.depth-- }
        return out
    }

    /** Las filas de ejemplo de una lista: cada una como (propiedad → valor), «" "» para una lista de textos. */
    private fun sampleRows(list: String, c: Ctx): List<Map<String, String>> {
        val m = Regex("(?:val|var)\\s+$list\\s*(?::[^=]+)?=\\s*(?:remember\\s*\\{\\s*)?(?:mutableStateListOf|listOf|mutableListOf|arrayListOf|arrayOf)(?:<[^>]*>)?\\s*\\(").find(c.code) ?: return emptyList()
        val cl = matchClose(c.code, m.range.last); if (cl < 0) return emptyList()
        return splitTop(c.code.substring(m.range.last + 1, cl)).mapNotNull { el ->
            val t = el.trim()
            if (t.startsWith("\"")) return@mapNotNull mapOf("" to t)
            val k = Regex("^(\\w+)\\s*\\(").find(t) ?: return@mapNotNull null
            val ce = matchClose(t, k.range.last); if (ce < 0) return@mapNotNull null
            val props = c.dataClasses[k.groupValues[1]] ?: return@mapNotNull null
            val args = Args(t.substring(k.range.last + 1, ce))
            props.mapIndexedNotNull { idx, p -> (args[p] ?: args.pos.getOrNull(idx))?.trim()?.let { p to it } }.toMap()
        }
    }

    /** Pone los datos de una fila en el código de la fila: «m.texto» → "¡Hola!", «m» → "texto". */
    private fun fillRow(code: String, v: String, r: Map<String, String>): String {
        var out = code
        r[""]?.let { return out.replace(Regex("(?<![.\\w])$v(?![\\w.(])"), it.replace("$", "\\$")) }
        r.forEach { (p, value) -> out = out.replace(Regex("(?<![.\\w])$v\\.$p(?!\\w)"), value.replace("$", "\\$")) }
        return out
    }

    /** Bloque de un if/else: «{ … }» o una sola llamada. */
    private fun branch(s: String, from: Int): Pair<String, Int> {
        var j = from; while (j < s.length && s[j].isWhitespace()) j++
        if (j < s.length && s[j] == '{') { val e = matchClose(s, j); return if (e < 0) "" to s.length else s.substring(j + 1, e) to e + 1 }
        val e = endOfStatement(s, j); return s.substring(j, e) to e
    }

    private fun condValue(cond: String, c: Ctx): Boolean? {
        when (cond.trim()) { "true", "!false" -> return true; "false", "!true" -> return false }
        Regex("^(!)?\\s*(\\w+)$").find(cond.trim())?.let { m -> c.stateBools[m.groupValues[2]]?.let { return if (m.groupValues[1].isEmpty()) it else !it } }
        return null
    }

    /** Fin de una instrucción: el salto de línea fuera de paréntesis (si la línea siguiente no sigue con «.»). */
    private fun endOfStatement(s: String, from: Int): Int {
        var i = from; var d = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') { i++; while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ }; i++; continue }
            if (ch == '(' || ch == '[' || ch == '{') d++
            if (ch == ')' || ch == ']' || ch == '}') { if (d == 0) return i; d-- }
            if (ch == '\n' && d == 0) {
                val rest = s.substring(i + 1).trimStart()
                if (!rest.startsWith(".") && !rest.startsWith("?.") && !rest.startsWith("?:") && !rest.startsWith("+") && !rest.startsWith("&&") && !rest.startsWith("||")) return i + 1
            }
            if (ch == ';' && d == 0) return i + 1
            i++
        }
        return s.length
    }

    /**
     * Una llamada «Nombre(args) { contenido }» desde [after] (justo después del nombre). Devuelve sus vistas y dónde
     * termina; null si no es una llamada.
     */
    private fun parseCall(s: String, after: Int, name: String, c: Ctx, row: Boolean): Pair<List<Node>, Int>? {
        var i = after
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        var args = ""
        if (i < s.length && s[i] == '(') { val e = matchClose(s, i); if (e < 0) return null; args = s.substring(i + 1, e); i = e + 1 }
        var j = i; while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
        var lam: String? = null
        if (j < s.length && s[j] == '{') { val e = matchClose(s, j); if (e < 0) return null; lam = s.substring(j + 1, e); i = e + 1 }
        if (args.isEmpty() && lam == null && (i >= s.length || s[after] != '(')) return null
        return build(name, Args(args), lam?.replace(Regex("^\\s*\\w+\\s*->"), ""), c, row) to i
    }

    private fun textOf(e0: String?, c: Ctx): String? {
        val e = e0?.trim() ?: return null
        if (e.startsWith("\"")) {
            // (los valores de los estados dentro del texto: "Tocaste $cuenta veces" → "Tocaste 0 veces")
            val lit = (Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"").find(e)?.value ?: e)
                .replace(Regex("\\$\\{\\s*(\\w+)\\s*\\}|\\$([A-Za-z_]\\w*)")) { m -> c.stateStrings[m.groupValues[1].ifEmpty { m.groupValues[2] }] ?: m.value }
            return unq(lit)
        }
        Regex("stringResource\\(\\s*(?:id\\s*=\\s*)?R\\.string\\.(\\w+)").find(e)?.let { return "@string/" + it.groupValues[1] }
        Regex("^(\\w+)$").find(e)?.let { m -> c.stateStrings[m.groupValues[1]]?.let { return it }; return "…" }
        return "…"
    }

    /** La primera Text(...) dentro de un contenido (el texto de un botón o de un título). */
    private fun firstText(lam: String?, c: Ctx): Pair<String?, Args?> {
        if (lam == null) return null to null
        val m = Regex("\\bText\\s*\\(").find(lam) ?: return null to null
        val e = matchClose(lam, m.range.last); if (e < 0) return null to null
        val a = Args(lam.substring(m.range.last + 1, e))
        return textOf(a["text"] ?: a.pos.firstOrNull(), c) to a
    }

    /** El ícono de un contenido: Icon(Icons.Default.Menu…) → @drawable/ic_menu (la maqueta usa el dibujo o uno parecido). */
    private fun iconRef(e0: String?): String? {
        val e = e0 ?: return null
        Regex("Icons\\.(?:AutoMirrored\\.)?(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone)\\.(\\w+)").find(e)?.let { m ->
            return "@drawable/ic_" + m.groupValues[1].replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase() }
        Regex("(?:painterResource|ImageVector\\.vectorResource)\\(\\s*(?:id\\s*=\\s*)?R\\.drawable\\.(\\w+)").find(e)?.let { return "@drawable/" + it.groupValues[1] }
        return null
    }

    private fun firstIcon(lam: String?, c: Ctx): Pair<String?, String?> {
        if (lam == null) return null to null
        val m = Regex("\\bIcon\\s*\\(").find(lam) ?: return null to null
        val e = matchClose(lam, m.range.last); if (e < 0) return null to null
        val a = Args(lam.substring(m.range.last + 1, e))
        return iconRef(a["imageVector"] ?: a["painter"] ?: a.pos.firstOrNull()) to colorOf(a["tint"], c)
    }

    private val TYPO = mapOf("displayLarge" to ("57sp" to false), "displayMedium" to ("45sp" to false), "displaySmall" to ("36sp" to false),
        "headlineLarge" to ("32sp" to false), "headlineMedium" to ("28sp" to false), "headlineSmall" to ("24sp" to false),
        "titleLarge" to ("22sp" to false), "titleMedium" to ("16sp" to true), "titleSmall" to ("14sp" to true),
        "bodyLarge" to ("16sp" to false), "bodyMedium" to ("14sp" to false), "bodySmall" to ("12sp" to false),
        "labelLarge" to ("14sp" to true), "labelMedium" to ("12sp" to true), "labelSmall" to ("11sp" to true),
        "h4" to ("34sp" to false), "h5" to ("24sp" to false), "h6" to ("20sp" to true), "subtitle1" to ("16sp" to false), "body1" to ("16sp" to false), "body2" to ("14sp" to false))

    private fun textNode(a: Args, c: Ctx): Node {
        val n = Node("TextView")
        val raw = (a["text"] ?: a.pos.firstOrNull())?.trim()
        n["${A}text"] = textOf(raw, c) ?: ""
        // (un texto que muestra un contador —"Tocaste $cuenta veces"—: los botones que lo cambian lo actualizan)
        if (raw != null && raw.startsWith("\"")) Regex("\\$\\{\\s*(\\w+)\\s*\\}|\\$([A-Za-z_]\\w*)").findAll(raw).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .filter { c.stateStrings[it]?.toDoubleOrNull() != null }.distinct().forEach { v ->
                val id = n["${A}id"]?.substringAfter('/') ?: "cmp_${++c.n}".also { n["${A}id"] = "@+id/$it" }
                val tpl = (Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)?.groupValues?.get(1) ?: return@forEach)
                    .replace(Regex("\\$\\{\\s*(\\w+)\\s*\\}|\\$([A-Za-z_]\\w*)")) { m -> val k = m.groupValues[1].ifEmpty { m.groupValues[2] }; if (k == v) "\$$v" else c.stateStrings[k] ?: m.value }
                c.templates.getOrPut(v) { mutableListOf() } += id to tpl
            }
        Regex("typography\\.(\\w+)").find(a["style"].orEmpty())?.groupValues?.get(1)?.let { TYPO[it] }?.let { (sz, b) ->
            n["${A}textSize"] = sz; if (b) n["${A}textStyle"] = "bold" }
        dim(a["fontSize"], "sp")?.let { n["${A}textSize"] = it }
        colorOf(a["color"], c)?.let { n["${A}textColor"] = it }
        a["fontWeight"]?.let { if (Regex("Bold|SemiBold|ExtraBold|Black|W[6-9]00").containsMatchIn(it)) n["${A}textStyle"] = "bold" }
        if (a["fontStyle"]?.contains("Italic") == true) n["${A}textStyle"] = (n["${A}textStyle"]?.let { "$it|" } ?: "") + "italic"
        when (a["textAlign"]?.substringAfterLast('.')) { "Center" -> n["${A}gravity"] = "center"; "End", "Right" -> n["${A}gravity"] = "end" }
        a["maxLines"]?.let { n["${A}maxLines"] = it.trim() }
        apply(n, modsOf(a["modifier"], c), c)
        return n
    }

    /** Colores de botones, tarjetas, interruptores…: ButtonDefaults.buttonColors(containerColor = …, contentColor = …). */
    private fun colorsArg(e: String?, c: Ctx): Map<String, String> {
        val t = e ?: return emptyMap()
        val m = Regex("\\w+Defaults\\.\\w+[Cc]olors\\s*\\(").find(t) ?: return emptyMap()
        val close = matchClose(t, m.range.last); if (close < 0) return emptyMap()
        val out = HashMap<String, String>()
        splitTop(t.substring(m.range.last + 1, close)).forEach { a -> named(a)?.let { (k, v) -> colorOf(v, c)?.let { out[k] = it } } }
        return out
    }

    private fun container(tag: String, orient: String?, kids: List<Node>): Node {
        val n = Node(tag); orient?.let { n["${A}orientation"] = it }; n.kids += kids; return n
    }

    /** Una vista por cada composable conocido; los de la app se leen por dentro. */
    private fun build(name: String, a: Args, lam: String?, c: Ctx, row: Boolean): List<Node> {
        val mods = modsOf(a["modifier"], c)
        fun kids(r: Boolean = false) = lam?.let { parseBlock(it, c, r) } ?: emptyList()
        fun arrange(n: Node, vertical: Boolean, arr: String?, al: String?) {
            val g = mutableListOf<String>()
            val av = arr?.substringAfterLast("Arrangement.")?.trim().orEmpty()
            gravityOf(al)?.let { g += it }
            when {
                av.startsWith("Center") -> g += if (vertical) "center_vertical" else "center_horizontal"
                av.startsWith("Bottom") || av.startsWith("End") -> g += if (vertical) "bottom" else "end"
                av.startsWith("spacedBy") -> dim(Regex("spacedBy\\(([^,)]*)").find(av)?.groupValues?.get(1))?.let { sp ->
                    n.kids.drop(1).forEach { k -> val key = if (vertical) "${A}layout_marginTop" else "${A}layout_marginStart"; if (k[key] == null) k[key] = sp } }
                av.startsWith("SpaceBetween") || av.startsWith("SpaceEvenly") || av.startsWith("SpaceAround") -> {
                    // (el espacio repartido entre los hijos: espaciadores que crecen, como en Android con pesos)
                    val spacer = { Node("View").also { it["${A}layout_width"] = if (vertical) "1dp" else "0dp"; it["${A}layout_height"] = if (vertical) "0dp" else "1dp"; it["${A}layout_weight"] = "1" } }
                    val ks = n.kids.toList(); n.kids.clear()
                    if (!av.startsWith("SpaceBetween")) n.kids += spacer()
                    ks.forEachIndexed { idx, k -> n.kids += k; if (idx < ks.size - 1) n.kids += spacer() }
                    if (!av.startsWith("SpaceBetween")) n.kids += spacer()
                }
            }
            if (g.isNotEmpty()) n["${A}gravity"] = g.joinToString("|")
        }
        // 🧩 Funciones @Composable de la app: se leen por dentro (con su contenido en el lugar de content())
        c.funs[name]?.let { (params, body) ->
            if (c.depth > 10) return emptyList()
            var b = body
            val ps = splitTop(params).mapNotNull { p -> Regex("^(\\w+)\\s*:").find(p.trim())?.groupValues?.get(1)?.let { it to p } }
            ps.forEachIndexed { idx, (pn, decl) ->
                val v = a[pn] ?: a.pos.getOrNull(idx)
                // (un parámetro que es una acción —onClick = { … }—: su código va donde la función lo usa)
                if (decl.contains("->") && v != null && v.trim().startsWith("{")) {
                    val body0 = lambdaBody(v).orEmpty()
                    b = b.replace(Regex("(?<![.\\w])$pn\\s*\\(\\s*\\)"), body0.replace("$", "\\$")).replace(Regex("(?<![.\\w])$pn(?![\\w(])"), v.trim().replace("$", "\\$"))
                }
                else if (decl.contains("->") && lam != null && (idx == ps.size - 1 || pn == "content")) {
                    // (el contenido que recibe la función: donde lo llama —content()— o donde lo pasa —content = content—)
                    val slot = "Slot__ {${lam.replace("$", "\\$")}}"
                    b = b.replace(Regex("\\b$pn\\s*\\(\\s*\\)"), slot)
                        .replace(Regex("(?<=[=(,]\\s{0,3})(?<![.\\w])$pn(?![\\w(])(?!\\s*=)"), "{ ${slot.replace("\\", "\\\\").replace("$", "\\$")} }")
                }
                else if (v != null && v.trim().startsWith("\"") && v.trim().endsWith("\"")) {
                    // (un texto: también dentro de otros textos —"Hola $nombre"—)
                    val inner = v.trim().removeSurrounding("\"").replace("$", "\\$")
                    b = b.replace(Regex("\\$\\{\\s*$pn\\s*\\}|\\$$pn(?!\\w)"), inner)
                    b = b.replace(Regex("(?<![.\\w\"])$pn(?![\\w(=])"), v.trim().replace("$", "\\$"))
                }
                else if (v != null && (v.trim().startsWith("\"") || v.trim() == "true" || v.trim() == "false" || colorOf(v, c) != null || dim(v) != null)) b = b.replace(Regex("(?<![.\\w])$pn(?![\\w(=])"), v.trim().replace("$", "\\$"))
            }
            val saveB = c.stateBools; val saveS = c.stateStrings
            c.stateBools = HashMap(saveB); c.stateStrings = HashMap(saveS)
            // (los parámetros de la función tapan a las variables de afuera que se llaman igual)
            ps.forEach { (pn, _) -> c.stateBools.remove(pn); c.stateStrings.remove(pn) }
            readState(b, c)
            val r = parseBlock(b, c, row)
            c.stateBools = saveB; c.stateStrings = saveS
            return r
        }
        val n: Node = when (name) {
            "MaterialTheme", "Slot__", "CompositionLocalProvider", "AnimatedVisibility", "Crossfade", "AnimatedContent", "SelectionContainer", "key" ->
                return (lam ?: a["content"]?.let { lambdaBody(it) })?.let { parseBlock(it, c, row) } ?: emptyList()
            "Column" -> container("LinearLayout", "vertical", kids(false)).also { arrange(it, true, a["verticalArrangement"], a["horizontalAlignment"]) }
            "Row", "FlowRow" -> container("LinearLayout", "horizontal", kids(true)).also { arrange(it, false, a["horizontalArrangement"], a["verticalAlignment"]) }
            "LazyColumn", "LazyVerticalGrid", "LazyVerticalStaggeredGrid" -> container("LinearLayout", "vertical", kids(false)).also { arrange(it, true, a["verticalArrangement"], a["horizontalAlignment"]) }
            "LazyRow", "LazyHorizontalGrid" -> container("LinearLayout", "horizontal", kids(true)).also { arrange(it, false, a["horizontalArrangement"], a["verticalAlignment"]) }
            "Box", "BoxWithConstraints" -> container("FrameLayout", null, kids(false)).also { b ->
                gravityOf(a["contentAlignment"])?.let { g -> b.kids.forEach { k -> if (k["${A}layout_gravity"] == null) k["${A}layout_gravity"] = g } } }
            "Surface" -> container("FrameLayout", null, kids(false)).also { s ->
                (colorOf(a["color"], c))?.let { col -> mods.bg = mods.bg ?: col }
                shapeOf(a["shape"])?.let { mods.shape = it; if (mods.bg == null) mods.bg = c.scheme["surface"] }
                dim(a["shadowElevation"] ?: a["tonalElevation"])?.let { s["${A}elevation"] = it } }
            "Card", "ElevatedCard", "OutlinedCard" -> {
                val col = colorsArg(a["colors"], c)
                val inner = container("LinearLayout", "vertical", kids(false))
                inner["${A}layout_width"] = "match_parent"; inner["${A}layout_height"] = "wrap_content"
                val card = Node("com.google.android.material.card.MaterialCardView"); card.kids += inner
                card["${APP}cardBackgroundColor"] = col["containerColor"] ?: c.scheme[if (name == "ElevatedCard") "surfaceContainerLow" else if (name == "OutlinedCard") "surface" else "surfaceContainerHighest"]
                card["${APP}cardCornerRadius"] = shapeOf(a["shape"])?.corners?.firstOrNull()?.let { if (it.endsWith("%")) "999dp" else it } ?: "12dp"
                card["${APP}cardElevation"] = Regex("defaultElevation\\s*=\\s*([^,)]+)").find(a["elevation"].orEmpty())?.groupValues?.get(1)?.let { dim(it) } ?: if (name == "ElevatedCard") "1dp" else "0dp"
                if (name == "OutlinedCard") { card["${APP}strokeWidth"] = "1dp"; card["${APP}strokeColor"] = c.scheme["outlineVariant"] }
                col["contentColor"]?.let { cc -> inner.kids.forEach { k -> if (k.tag == "TextView" && k["${A}textColor"] == null) k["${A}textColor"] = cc } }
                card
            }
            "Spacer" -> Node("View")
            "Divider", "HorizontalDivider" -> Node("View").also { d -> d["${A}layout_width"] = "match_parent"; d["${A}layout_height"] = dim(a["thickness"]) ?: "1dp"; d["${A}background"] = colorOf(a["color"], c) ?: c.scheme["outlineVariant"] }
            "VerticalDivider" -> Node("View").also { d -> d["${A}layout_width"] = dim(a["thickness"]) ?: "1dp"; d["${A}layout_height"] = "match_parent"; d["${A}background"] = colorOf(a["color"], c) ?: c.scheme["outlineVariant"] }
            "Text" -> return listOf(textNode(a, c))
            "Button", "OutlinedButton", "TextButton", "FilledTonalButton", "ElevatedButton" -> {
                val b = Node("com.google.android.material.button.MaterialButton")
                b["style"] = when (name) { "OutlinedButton" -> "@style/Widget.Material3.Button.OutlinedButton"; "TextButton" -> "@style/Widget.Material3.Button.TextButton"
                    "FilledTonalButton" -> "@style/Widget.Material3.Button.TonalButton"; "ElevatedButton" -> "@style/Widget.Material3.Button.ElevatedButton"; else -> null }
                val (t, ta) = firstText(lam, c)
                b["${A}text"] = t ?: ""
                ta?.let { tx -> dim(tx["fontSize"], "sp")?.let { b["${A}textSize"] = it }; colorOf(tx["color"], c)?.let { b["${A}textColor"] = it } }
                val (ic, _) = firstIcon(lam, c)
                ic?.let { b["${APP}icon"] = it; if (lam!!.indexOf("Icon(") > lam.indexOf("Text(") && lam.contains("Text(")) b["${APP}iconGravity"] = "textEnd" }
                val col = colorsArg(a["colors"], c)
                col["containerColor"]?.let { b["${A}backgroundTint"] = it }
                col["contentColor"]?.let { b["${A}textColor"] = it; b["${APP}iconTint"] = it }
                shapeOf(a["shape"])?.let { sh ->
                    if (sh.cut || sh.corners.toSet().size > 1) b["${APP}shapeAppearanceOverlay"] = cutStyle(sh, c, sh.cut)
                    else b["${APP}cornerRadius"] = sh.corners[0].let { if (it.endsWith("%")) "999dp" else it } }
                Regex("BorderStroke\\(\\s*([^,]+),\\s*([^)]+\\)?)").find(a["border"].orEmpty())?.let { bs -> b["${APP}strokeWidth"] = dim(bs.groupValues[1]); b["${APP}strokeColor"] = colorOf(bs.groupValues[2], c) }
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it }
                if (a["enabled"]?.trim() == "false") b["${A}alpha"] = "0.38"
                b
            }
            "IconButton", "FilledIconButton", "FilledTonalIconButton", "OutlinedIconButton", "IconToggleButton" -> {
                val b = Node("ImageButton")
                val (ic, tint) = firstIcon(lam, c)
                b["${A}src"] = ic ?: "@drawable/ic_circle"
                val col = colorsArg(a["colors"], c)
                b["${A}tint"] = tint ?: col["contentColor"] ?: if (name == "FilledIconButton") c.scheme["onPrimary"] else null
                val fill = col["containerColor"] ?: when (name) { "FilledIconButton" -> c.scheme["primary"]; "FilledTonalIconButton" -> c.scheme["secondaryContainer"]; else -> null }
                if (fill != null || name == "OutlinedIconButton") b["${A}background"] = bgDrawable(fill, Shape(List(4) { "50%" }), if (name == "OutlinedIconButton") "1dp" else null, c.scheme["outline"], c)
                else b["${A}background"] = "?attr/selectableItemBackgroundBorderless"
                b["${A}layout_width"] = if (fill != null || name == "OutlinedIconButton") "40dp" else "48dp"; b["${A}layout_height"] = b["${A}layout_width"]
                if (fill != null) { b["${A}layout_margin"] = "4dp" }
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it }
                b
            }
            "Icon" -> Node("ImageButton").also { b ->
                b["${A}src"] = iconRef(a["imageVector"] ?: a["painter"] ?: a.pos.firstOrNull()) ?: "@drawable/ic_circle"
                b["${A}tint"] = colorOf(a["tint"], c); b["${A}background"] = "@null"
                b["${A}layout_width"] = "24dp"; b["${A}layout_height"] = "24dp"; b["${A}clickable"] = "false" }
            "Image", "AsyncImage" -> Node("ImageView").also { b ->
                b["${A}src"] = iconRef(a["painter"] ?: a.pos.firstOrNull()) ?: "@drawable/imagen"
                b["${A}scaleType"] = when (a["contentScale"]?.substringAfterLast('.')) { "Crop" -> "centerCrop"; "FillBounds" -> "fitXY"; else -> "fitCenter" } }
            "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton" -> Node("com.google.android.material.floatingactionbutton.FloatingActionButton").also { b ->
                val (ic, tint) = firstIcon(lam, c)
                b["${A}src"] = ic ?: "@drawable/ic_add"
                b["${APP}backgroundTint"] = colorOf(a["containerColor"] ?: a["backgroundColor"], c) ?: c.scheme["primaryContainer"]
                b["${APP}tint"] = tint ?: colorOf(a["contentColor"], c) ?: c.scheme["onPrimaryContainer"]
                if (name == "SmallFloatingActionButton") b["${APP}fabSize"] = "mini"; if (name == "LargeFloatingActionButton") b["${APP}fabCustomSize"] = "96dp"
                shapeOf(a["shape"])?.let { sh -> if (sh.corners[0].endsWith("%")) b["__round"] = "1" }
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it } }
            "ExtendedFloatingActionButton" -> Node("com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton").also { b ->
                val tl = a["text"]?.let { lambdaBody(it) } ?: lam
                b["${A}text"] = firstText(tl, c).first ?: ""
                b["${APP}icon"] = firstIcon(a["icon"]?.let { lambdaBody(it) } ?: lam, c).first
                b["${APP}backgroundTint"] = colorOf(a["containerColor"], c) ?: c.scheme["primaryContainer"]
                b["${A}textColor"] = colorOf(a["contentColor"], c) ?: c.scheme["onPrimaryContainer"]
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it } }
            "TextField", "OutlinedTextField" -> {
                val til = Node("com.google.android.material.textfield.TextInputLayout")
                til["style"] = if (name == "OutlinedTextField") "@style/Widget.Material3.TextInputLayout.OutlinedBox" else "@style/Widget.Material3.TextInputLayout.FilledBox"
                til["${A}hint"] = firstText(a["label"]?.let { lambdaBody(it) }, c).first ?: firstText(a["placeholder"]?.let { lambdaBody(it) }, c).first ?: ""
                val col = colorsArg(a["colors"], c)
                (col["unfocusedContainerColor"] ?: col["containerColor"])?.let { til["${APP}boxBackgroundColor"] = it }
                (col["unfocusedBorderColor"] ?: col["unfocusedIndicatorColor"])?.let { til["${APP}boxStrokeColor"] = it }
                shapeOf(a["shape"])?.corners?.let { k -> listOf("TopStart", "TopEnd", "BottomEnd", "BottomStart").forEachIndexed { idx, s -> til["${APP}boxCornerRadius$s"] = k[idx].let { if (it.endsWith("%")) "28dp" else it } } }
                val et = Node("com.google.android.material.textfield.TextInputEditText")
                et["${A}layout_width"] = "match_parent"; et["${A}layout_height"] = "wrap_content"
                a["value"]?.let { textOf(it, c) }?.takeIf { it != "…" && it.isNotEmpty() }?.let { et["${A}text"] = it }
                (col["unfocusedTextColor"] ?: col["textColor"])?.let { et["${A}textColor"] = it }
                til.kids += et; til
            }
            "BasicTextField" -> Node("EditText").also { e -> e["${A}background"] = "@null"; e["${A}hint"] = "" }
            "Switch" -> Node("com.google.android.material.materialswitch.MaterialSwitch").also { sw ->
                sw["${A}checked"] = (a["checked"]?.trim() == "true" || condValue(a["checked"].orEmpty(), c) == true).toString()
                val col = colorsArg(a["colors"], c)
                fun sel(on: String?, off: String?): String? { if (on == null && off == null) return null
                    val nm = "cmp_sel_${++c.n}"
                    c.res += "$nm.xml" to "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                        (on?.let { "<item android:state_checked=\"true\" android:color=\"$it\"/>" } ?: "") + "<item android:color=\"${off ?: on}\"/></selector>"
                    return "@color/$nm" }
                sel(col["checkedThumbColor"], col["uncheckedThumbColor"])?.let { sw["${APP}thumbTint"] = it }
                sel(col["checkedTrackColor"], col["uncheckedTrackColor"])?.let { sw["${APP}trackTint"] = it } }
            "Checkbox", "TriStateCheckbox" -> Node("CheckBox").also { b -> b["${A}checked"] = (a["checked"]?.trim() == "true" || condValue(a["checked"].orEmpty(), c) == true).toString()
                colorsArg(a["colors"], c)["checkedColor"]?.let { b["${APP}buttonTint"] = it } }
            "RadioButton" -> Node("RadioButton").also { b -> b["${A}checked"] = (a["selected"]?.trim() == "true").toString()
                colorsArg(a["colors"], c)["selectedColor"]?.let { b["${APP}buttonTint"] = it } }
            "Slider", "RangeSlider" -> Node("SeekBar")
            "CircularProgressIndicator" -> Node("ProgressBar")
            "LinearProgressIndicator" -> Node("ProgressBar").also { it["style"] = "?android:attr/progressBarStyleHorizontal" }
            "TopAppBar", "CenterAlignedTopAppBar", "SmallTopAppBar", "MediumTopAppBar", "LargeTopAppBar" -> {
                val bar = Node("LinearLayout"); bar["${A}orientation"] = "horizontal"; bar["${A}gravity"] = "center_vertical"
                val col = colorsArg(a["colors"], c)
                bar["${A}background"] = col["containerColor"] ?: c.scheme["surface"]
                bar["${A}layout_width"] = "match_parent"; bar["${A}layout_height"] = "64dp"; bar["${A}paddingHorizontal"] = "4dp"
                val nav = a["navigationIcon"]?.let { lambdaBody(it) }?.let { parseBlock(it, c, true) }.orEmpty()
                col["navigationIconContentColor"]?.let { cc -> nav.forEach { tintDeep(it, cc) } }
                bar.kids += nav
                val (t, ta) = firstText(a["title"]?.let { lambdaBody(it) }, c)
                val tv = Node("TextView"); tv["${A}text"] = t ?: ""
                tv["${A}layout_width"] = "0dp"; tv["${A}layout_weight"] = "1"; tv["${A}layout_height"] = "wrap_content"
                tv["${A}textSize"] = ta?.get("fontSize")?.let { dim(it, "sp") } ?: if (name == "LargeTopAppBar") "28sp" else if (name == "MediumTopAppBar") "24sp" else "22sp"
                tv["${A}textColor"] = ta?.get("color")?.let { colorOf(it, c) } ?: col["titleContentColor"] ?: c.scheme["onSurface"]
                if (ta?.get("fontWeight")?.contains("Bold") == true) tv["${A}textStyle"] = "bold"
                if (name == "CenterAlignedTopAppBar") tv["${A}gravity"] = "center"
                tv["${A}layout_marginStart"] = if (nav.isEmpty()) "12dp" else "4dp"
                bar.kids += tv
                if (name == "CenterAlignedTopAppBar" && nav.isEmpty()) { val sp = Node("View"); sp["${A}layout_width"] = "48dp"; sp["${A}layout_height"] = "1dp"; bar.kids.add(0, sp) }
                val acts = a["actions"]?.let { lambdaBody(it) }?.let { parseBlock(it, c, true) }.orEmpty()
                col["actionIconContentColor"]?.let { cc -> acts.forEach { tintDeep(it, cc) } }
                bar.kids += acts
                if (name == "CenterAlignedTopAppBar" && acts.isEmpty() && nav.isNotEmpty()) { val sp = Node("View"); sp["${A}layout_width"] = "48dp"; sp["${A}layout_height"] = "1dp"; bar.kids += sp }
                bar
            }
            "BottomAppBar" -> container("LinearLayout", "horizontal", kids(true)).also { b -> b["${A}gravity"] = "center_vertical"; b["${A}layout_width"] = "match_parent"; b["${A}layout_height"] = "80dp"
                b["${A}background"] = colorOf(a["containerColor"], c) ?: c.scheme["surfaceContainer"]; b["${A}paddingHorizontal"] = "4dp" }
            "NavigationBar", "BottomNavigation" -> {
                val bar = container("LinearLayout", "horizontal", kids(true))
                bar["${A}layout_width"] = "match_parent"; bar["${A}layout_height"] = "80dp"; bar["${A}gravity"] = "center_vertical"
                bar["${A}background"] = colorOf(a["containerColor"] ?: a["backgroundColor"], c) ?: c.scheme["surfaceContainer"]
                bar
            }
            "NavigationBarItem", "BottomNavigationItem" -> {
                val item = Node("LinearLayout"); item["${A}orientation"] = "vertical"; item["${A}gravity"] = "center"
                item["${A}layout_width"] = "0dp"; item["${A}layout_weight"] = "1"; item["${A}layout_height"] = "match_parent"
                val sr = c.route ?: c.startRoute
                val sel = a["selected"]?.trim() == "true" || (sr != null && a["selected"].orEmpty().contains("\"$sr\"")) ||
                    (sr == null && Regex("==\\s*0\\b|0\\s*==").containsMatchIn(a["selected"].orEmpty()))
                val (ic, tint) = firstIcon(a["icon"]?.let { lambdaBody(it) }, c)
                val ib = Node("ImageButton"); ib["${A}src"] = ic ?: "@drawable/ic_circle"; ib["${A}layout_width"] = "64dp"; ib["${A}layout_height"] = "32dp"
                ib["${A}tint"] = tint ?: c.scheme[if (sel) "onSecondaryContainer" else "onSurfaceVariant"]
                ib["${A}background"] = if (sel) bgDrawable(c.scheme["secondaryContainer"], Shape(List(4) { "16dp" }), null, null, c) else "@null"
                item.kids += ib
                firstText(a["label"]?.let { lambdaBody(it) }, c).first?.let { t -> val tv = Node("TextView"); tv["${A}text"] = t; tv["${A}textSize"] = "12sp"
                    tv["${A}layout_marginTop"] = "4dp"; tv["${A}textColor"] = c.scheme[if (sel) "onSurface" else "onSurfaceVariant"]; if (sel) tv["${A}textStyle"] = "bold"; item.kids += tv }
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it }
                item
            }
            "NavigationDrawerItem" -> {
                val r = Node("LinearLayout"); r["${A}orientation"] = "horizontal"; r["${A}gravity"] = "center_vertical"
                r["${A}layout_width"] = "match_parent"; r["${A}layout_height"] = "56dp"; r["${A}paddingHorizontal"] = "16dp"
                val sel = a["selected"]?.trim() == "true"
                if (sel) r["${A}background"] = bgDrawable(c.scheme["secondaryContainer"], Shape(List(4) { "28dp" }), null, null, c)
                firstIcon(a["icon"]?.let { lambdaBody(it) }, c).first?.let { ic -> val ib = Node("ImageButton"); ib["${A}src"] = ic; ib["${A}background"] = "@null"
                    ib["${A}layout_width"] = "24dp"; ib["${A}layout_height"] = "24dp"; ib["${A}layout_marginEnd"] = "12dp"; ib["${A}tint"] = c.scheme["onSurfaceVariant"]; r.kids += ib }
                firstText(a["label"]?.let { lambdaBody(it) }, c).first?.let { t -> val tv = Node("TextView"); tv["${A}text"] = t; tv["${A}textSize"] = "14sp"
                    tv["${A}textColor"] = c.scheme[if (sel) "onSecondaryContainer" else "onSurfaceVariant"]; r.kids += tv }
                a["onClick"]?.let { lambdaBody(it) }?.let { mods.click = it }
                r
            }
            "ModalDrawerSheet", "DismissibleDrawerSheet", "PermanentDrawerSheet" -> container("LinearLayout", "vertical", kids(false)).also { s ->
                s["${A}background"] = colorOf(a["drawerContainerColor"], c) ?: c.scheme["surfaceContainerLow"]; s["${A}paddingHorizontal"] = "12dp" }
            "ModalNavigationDrawer", "DismissibleNavigationDrawer" -> {
                val dl = Node("androidx.drawerlayout.widget.DrawerLayout")
                dl["${A}layout_width"] = "match_parent"; dl["${A}layout_height"] = "match_parent"
                val content = container("FrameLayout", null, kids(false)); content["${A}layout_width"] = "match_parent"; content["${A}layout_height"] = "match_parent"
                content.kids.forEach { k -> if (k["${A}layout_height"] == null || k["${A}layout_height"] == "wrap_content") k["${A}layout_height"] = "match_parent"; if (k["${A}layout_width"] == "wrap_content") k["${A}layout_width"] = "match_parent" }
                val sheetKids = a["drawerContent"]?.let { lambdaBody(it) }?.let { parseBlock(it, c, false) }.orEmpty()
                val sheet = if (sheetKids.size == 1 && sheetKids[0].tag == "LinearLayout") sheetKids[0] else container("LinearLayout", "vertical", sheetKids)
                sheet["${A}layout_width"] = "300dp"; sheet["${A}layout_height"] = "match_parent"; sheet["${A}layout_gravity"] = "start"
                if (sheet["${A}background"] == null) sheet["${A}background"] = c.scheme["surfaceContainerLow"]
                dl.kids += content; dl.kids += sheet
                return listOf(dl)
            }
            "Scaffold" -> {
                // 🏗️ Scaffold: barra de arriba, contenido, barra de abajo y botón flotante en su esquina
                val root = Node("FrameLayout"); root["${A}layout_width"] = "match_parent"; root["${A}layout_height"] = "match_parent"
                root["${A}background"] = colorOf(a["containerColor"] ?: a["backgroundColor"], c) ?: c.scheme["background"]
                val col = container("LinearLayout", "vertical", emptyList()); col["${A}layout_width"] = "match_parent"; col["${A}layout_height"] = "match_parent"
                a["topBar"]?.let { lambdaBody(it) }?.let { col.kids += parseBlock(it, c, false) }
                val content = container("LinearLayout", "vertical", kids(false)); content["${A}layout_width"] = "match_parent"; content["${A}layout_height"] = "0dp"; content["${A}layout_weight"] = "1"
                content.kids.forEach { k -> if (k["${A}layout_width"] == "wrap_content" && k.tag != "TextView") k["${A}layout_width"] = "match_parent" }
                col.kids += content
                val bottom = a["bottomBar"]?.let { lambdaBody(it) }?.let { parseBlock(it, c, false) }.orEmpty()
                col.kids += bottom
                root.kids += col
                a["floatingActionButton"]?.let { lambdaBody(it) }?.let { parseBlock(it, c, false) }?.forEach { f ->
                    f["${A}layout_gravity"] = when (a["floatingActionButtonPosition"]?.substringAfterLast('.')) { "Center" -> "bottom|center_horizontal"; "Start" -> "bottom|start"; else -> "bottom|end" }
                    f["${A}layout_margin"] = "16dp"
                    if (bottom.isNotEmpty()) f["${A}layout_marginBottom"] = "96dp"
                    root.kids += f
                }
                apply(root, mods, c, "match_parent", "match_parent")
                return listOf(root)
            }
            "NavHost", "AnimatedNavHost" -> {
                // 🧭 Dentro del NavHost: solo la pantalla de la ruta que se está dibujando (o la de inicio)
                val want = c.route ?: c.startRoute
                val body = lam.orEmpty()
                var found: String? = null
                Regex("\\bcomposable\\s*\\(").findAll(body).forEach { m ->
                    if (found != null) return@forEach
                    val cp = matchClose(body, m.range.last); if (cp < 0) return@forEach
                    val r = Regex("^\\s*(?:route\\s*=\\s*)?\"([^\"]+)\"").find(body.substring(m.range.last + 1, cp))?.groupValues?.get(1) ?: return@forEach
                    val ob = body.indexOf('{', cp)
                    if (want == null || r == want) found = block(body, ob)?.replace(Regex("^\\s*\\w+\\s*->"), "")
                }
                val inner = found?.let { parseBlock(it, c, false) }.orEmpty()
                val box = container("FrameLayout", null, inner)
                inner.forEach { k -> if (k["${A}layout_width"] == "wrap_content" || k["${A}layout_width"] == null) k["${A}layout_width"] = "match_parent" }
                box["${A}layout_width"] = "match_parent"; box["${A}layout_height"] = "match_parent"
                apply(box, mods, c, "match_parent", "match_parent")
                return listOf(box)
            }
            "DropdownMenu" -> {
                // (un menú que se abre con una variable: sus opciones las abre el botón que la cambia)
                val v = Regex("^\\s*(\\w+)").find(a["expanded"].orEmpty())?.groupValues?.get(1)
                val ops = Regex("text\\s*=\\s*\\{\\s*Text\\s*\\(\\s*(?:text\\s*=\\s*)?\"([^\"]*)\"").findAll(lam.orEmpty()).map { unq(it.groupValues[1]) }.toList()
                if (v != null && ops.isNotEmpty()) c.menus[v] = ops
                return emptyList()
            }
            "AlertDialog", "Dialog", "ModalBottomSheet", "BasicAlertDialog" -> return emptyList()
            else -> {
                // (algo que no conocemos: si trae contenido, se dibuja su contenido en columna)
                if (lam == null) return emptyList()
                container("LinearLayout", "vertical", kids(false))
            }
        }
        if (name == "Row" || name == "Column" || name == "LazyColumn" || name == "LazyRow" || name == "Box" || name == "Surface") {
            // (contenedores: ancho y alto según su Modifier; si no dice, lo que mida su contenido)
        }
        apply(n, mods, c)
        return listOf(n)
    }

    /** El color de los íconos de una barra (navigationIconContentColor / actionIconContentColor): a todos los de adentro. */
    private fun tintDeep(n: Node, color: String) {
        if (n.tag == "ImageButton" && n["${A}tint"] == null) n["${A}tint"] = color
        n.kids.forEach { tintDeep(it, color) }
    }

    /** 🪟 Una ventana (AlertDialog / Dialog / ModalBottomSheet) que se muestra con [variable]: va como diseño aparte. */
    private fun buildDialog(variable: String, code: String, c: Ctx) {
        val m = Regex("\\b(AlertDialog|Dialog|ModalBottomSheet|BasicAlertDialog)\\s*\\(").find(code) ?: return
        val close = matchClose(code, m.range.last); if (close < 0) return
        val a = Args(code.substring(m.range.last + 1, close))
        var j = close + 1; while (j < code.length && code[j].isWhitespace()) j++
        val lam = if (j < code.length && code[j] == '{') block(code, j) else null
        val name = c.dialogs[variable] ?: "dialog_${safeName(variable)}"
        val box = Node("LinearLayout"); box["${A}orientation"] = "vertical"; box["${A}padding"] = "24dp"
        box["${A}layout_width"] = "match_parent"; box["${A}layout_height"] = "wrap_content"
        box["${A}background"] = colorOf(a["containerColor"], c) ?: c.scheme["surfaceContainerHigh"]
        firstText(a["title"]?.let { lambdaBody(it) }, c).first?.let { t -> val tv = Node("TextView"); tv["${A}text"] = t; tv["${A}textSize"] = "24sp"
            tv["${A}textColor"] = colorOf(a["titleContentColor"], c) ?: c.scheme["onSurface"]; tv["${A}layout_marginBottom"] = "16dp"; box.kids += tv }
        a["text"]?.let { lambdaBody(it) }?.let { tb -> parseBlock(tb, c, false).forEach { k -> if (k.tag == "TextView" && k["${A}textColor"] == null) k["${A}textColor"] = c.scheme["onSurfaceVariant"]; box.kids += k } }
        if (lam != null) box.kids += parseBlock(lam, c, false)
        val btns = Node("LinearLayout"); btns["${A}orientation"] = "horizontal"; btns["${A}gravity"] = "end"
        btns["${A}layout_width"] = "match_parent"; btns["${A}layout_height"] = "wrap_content"; btns["${A}layout_marginTop"] = "24dp"
        listOf("dismissButton", "confirmButton").forEach { k -> a[k]?.let { lambdaBody(it) }?.let { btns.kids += parseBlock(it, c, true) } }
        // (los botones de la ventana la cierran, como en Android al cambiar la variable)
        fun closeAll(n: Node) { if (n.tag.endsWith("Button") && n["${A}id"] == null) { val id = "cmp_${++c.n}"; n["${A}id"] = "@+id/$id"; c.js.append("alClick('$id', function(){ cerrarVentana(); });\n") }; n.kids.forEach { closeAll(it) } }
        btns.kids.forEach { closeAll(it) }
        if (btns.kids.isNotEmpty()) box.kids += btns
        c.layouts[name] = rootXml(listOf(box), dialog = true)
        c.dialogs[variable] = name
        // (si la variable se cambia en otro botón de una pantalla ya leída, se agrega su acción al leerla: ver actionsJs)
    }

    // ------------------------------------------------------------------ XML
    private fun rootXml(kids0: List<Node>, dialog: Boolean = false): String {
        val kids = kids0.filter { !it.transparent }
        val root = if (kids.size == 1) kids[0] else Node("FrameLayout").also { f -> f.kids += kids }
        if (!dialog) { root["${A}layout_width"] = "match_parent"; root["${A}layout_height"] = "match_parent" }
        root["xmlns:android"] = "http://schemas.android.com/apk/res/android"
        root["xmlns:app"] = "http://schemas.android.com/apk/res-auto"
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        write(root, sb, 0, null)
        return sb.toString()
    }

    private fun write(n: Node, sb: StringBuilder, depth: Int, parent: Node?) {
        // (peso dentro de una fila o columna: el lado que crece va en 0dp, como en Android)
        n["${A}layout_weight"]?.let {
            val rowParent = parent?.get("${A}orientation") == "horizontal"
            if (rowParent) { if (n["${A}layout_width"] == "wrap_content" || n["${A}layout_width"] == null || n["${A}layout_width"] == "match_parent") n["${A}layout_width"] = "0dp" }
            else if (parent?.tag == "LinearLayout") { if (n["${A}layout_height"] == "wrap_content" || n["${A}layout_height"] == null || n["${A}layout_height"] == "match_parent") n["${A}layout_height"] = "0dp" }
        }
        if (n["${A}layout_width"] == null) n["${A}layout_width"] = "wrap_content"
        // (en Compose, una columna que no dice su ancho pero tiene un hijo con fillMaxWidth ocupa todo el ancho que le dan)
        if (n["${A}layout_width"] == "wrap_content" && n.tag == "LinearLayout" && parent?.get("${A}orientation") != "horizontal" &&
            n.kids.any { it["${A}layout_width"] == "match_parent" }) n["${A}layout_width"] = "match_parent"
        if (n["${A}layout_height"] == null) n["${A}layout_height"] = "wrap_content"
        val pad = "  ".repeat(depth)
        sb.append(pad).append('<').append(n.tag)
        for ((k, v) in n.attrs) if (!k.startsWith("__")) sb.append("\n").append(pad).append("    ").append(k).append("=\"").append(xmlEsc(v)).append('"')
        if (n.kids.isEmpty()) { sb.append(" />\n"); return }
        sb.append(">\n")
        n.kids.forEach { write(it, sb, depth + 1, n) }
        sb.append(pad).append("</").append(n.tag).append(">\n")
    }
}
