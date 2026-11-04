package com.paulrj.cumulusia

import java.io.File
import java.util.Locale

/**
 * 🔎 REVISAR TU PROYECTO ANTES DE COMPILARLO (sin IA: exacto, rápido y con cualquier modelo).
 *
 * El usuario pega el código que le hizo una IA grande (Claude, ChatGPT, Gemini…), adjunta los archivos o un .zip
 * con el proyecto, y Cumulus:
 *  • junta los archivos (pantallas XML, Kotlin/Java, strings, colores, manifiesto, HTML, CSS, JavaScript);
 *  • revisa lo que suele romper la compilación o la página: XML cortado o mal cerrado, @string/@color/@drawable
 *    que se usan y no existen, R.id / R.layout que no están en ningún diseño, actividades del manifiesto que no
 *    existen, archivos CSS/JS que la página pide y no vienen, id que el JavaScript busca y no están en la página;
 *  • los deja listos para la 📱 Maqueta de la app (diseño) y la 🖥️ Vista previa web (funciona de verdad).
 * Los mensajes van en español: la app los muestra en el idioma elegido (el código queda tal cual).
 */
object ProjectCheck {

    /**
     * 🎨 Los colores con nombre de Android (@android:color/holo_orange_dark, android.R.color.darker_gray) y los
     * nombres que acepta Color.parseColor("orange"…) → #RRGGBB (o «transparent»). null si no es uno de esos.
     */
    fun namedColor(name0: String): String? {
        val n = name0.trim().lowercase(Locale.ROOT)
        return when (n) {
            "white", "background_light", "primary_text_dark", "primary_text_dark_nodisable", "bright_foreground_dark" -> "#FFFFFF"
            "black", "background_dark", "primary_text_light", "primary_text_light_nodisable", "bright_foreground_light" -> "#000000"
            "transparent" -> "transparent"
            "darker_gray" -> "#AAAAAA"
            "secondary_text_dark", "secondary_text_dark_nodisable" -> "#BEBEBE"
            "secondary_text_light", "secondary_text_light_nodisable" -> "#323232"
            "tertiary_text_dark", "tertiary_text_light", "tab_indicator_text" -> "#808080"
            "holo_blue_light" -> "#33B5E5"; "holo_blue_dark" -> "#0099CC"; "holo_blue_bright" -> "#00DDFF"
            "holo_green_light" -> "#99CC00"; "holo_green_dark" -> "#669900"
            "holo_red_light" -> "#FF4444"; "holo_red_dark" -> "#CC0000"
            "holo_orange_light" -> "#FFBB33"; "holo_orange_dark" -> "#FF8800"
            "holo_purple" -> "#AA66CC"
            // (los nombres de Color.parseColor)
            "red" -> "#FF0000"; "green" -> "#00FF00"; "blue" -> "#0000FF"; "yellow" -> "#FFFF00"
            "cyan", "aqua" -> "#00FFFF"; "magenta", "fuchsia" -> "#FF00FF"
            "gray", "grey" -> "#888888"; "lightgray", "lightgrey" -> "#CCCCCC"; "darkgray", "darkgrey" -> "#444444"
            "lime" -> "#00FF00"; "maroon" -> "#800000"; "navy" -> "#000080"; "olive" -> "#808000"
            "purple" -> "#800080"; "silver" -> "#C0C0C0"; "teal" -> "#008080"
            else -> when {
                // 📱 Colores del sistema (Material You): aproximados
                n.startsWith("system_accent") || n.startsWith("system_primary") -> "#6750A4"
                n.startsWith("system_neutral") || n.startsWith("system_surface") || n.startsWith("system_on_") ->
                    Regex("(\\d+)$").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it <= 200) "#F4EFF4" else if (it >= 700) "#1C1B1F" else "#938F99" } ?: "#938F99"
                else -> null
            }
        }
    }

    /** Un archivo del proyecto: nombre («activity_main.xml»), ruta dentro del proyecto (si se sabe) y contenido. */
    data class SourceFile(val name: String, val path: String, val content: String)

    /** Lo que se encontró: archivos, avisos (⚠️ pueden romper la compilación) y notas (💡 conviene saber). */
    data class Report(val files: List<SourceFile>, val problems: List<String>, val notes: List<String>,
                      val hasAppLayouts: Boolean, val hasWeb: Boolean)

    private val codeExts = setOf("xml", "html", "htm", "css", "js", "mjs", "kt", "kts", "java", "gradle", "json", "pro", "properties", "toml")
    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp")

    // ------------------------------------------------------------------ 1) juntar los archivos

    /** Los bloques ```código``` de un mensaje, con el nombre del archivo si lo dice cerca (o adivinado por su contenido). */
    fun filesFromText(text: String): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        val rx = Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val segs = mutableListOf<Triple<String?, String, String>>()
        var lastEnd = 0
        for (m in rx.findAll(text)) {
            val code = m.groupValues[2].trimEnd()
            // (el nombre se busca solo entre el bloque anterior y este: antes podía tomar el nombre del archivo de arriba)
            val before = text.substring(maxOf(lastEnd, m.range.first - 300), m.range.first)
            lastEnd = m.range.last + 1
            if (code.isBlank()) continue
            val named = Regex("([\\w.-]+\\.(xml|html?|css|m?js|kts?|java|gradle|json|toml|properties))", RegexOption.IGNORE_CASE)
                .findAll(before).lastOrNull()?.value
            segs += Triple(named, code, m.groupValues[1].lowercase(Locale.ROOT))
        }
        out += finishNames(segs)
        // Código pegado SIN las comillas ``` (una pantalla XML, una página HTML o una clase Kotlin entera)
        if (out.isEmpty()) {
            val t = text.trim()
            val looksCode = t.startsWith("<?xml") || Regex("^<(LinearLayout|androidx\\.|android\\.|RelativeLayout|FrameLayout|ScrollView|" +
                "com\\.google|resources|manifest|menu|navigation|!DOCTYPE|html)", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                Regex("(?m)^\\s*package\\s+[\\w.]+\\s*$").containsMatchIn(t) && Regex("\\b(class|fun|object)\\b").containsMatchIn(t)
            if (looksCode && t.length > 60) { val n = guessName(t, "", 1); out += SourceFile(n, n, t) }
        }
        return out
    }

    /** Archivos adjuntos: los de código se leen; un .zip se abre y se toman los archivos del proyecto. */
    fun filesFromAttachments(files: List<File>, readText: (File) -> String?): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        for (f in files) {
            val shown = f.name.substringAfter('_')
            val ext = shown.substringAfterLast('.', "").lowercase(Locale.ROOT)
            when {
                ext == "zip" -> out += filesFromZip(f)
                ext in codeExts -> readText(f)?.takeIf { it.isNotBlank() }?.let { out += SourceFile(shown, shown, it) }
                // (una imagen, fuente o video suelto: se anota, para saber que vino)
                ext in imageExts || ext in webBinTypes -> out += SourceFile(shown, shown, "")
            }
        }
        return out
    }

    /** 🖼️🔤 Imágenes (res/drawable, res/mipmap) y fuentes (res/font) de tu proyecto para la maqueta: nombre → (tipo, contenido). */
    class Assets(val images: Map<String, Pair<String, ByteArray>>, val fonts: Map<String, Pair<String, ByteArray>>)

    private val fontExts = setOf("ttf", "otf")
    private const val MAX_ASSET = 1_500_000L        // (una imagen más grande no se muestra: se ve el recuadro de ejemplo)
    private const val MAX_ASSETS_TOTAL = 10_000_000L

    /**
     * 🖼️ Las imágenes y fuentes de tus archivos o tu .zip, para que la maqueta muestre tu logo, tus fondos, tus fotos
     * y tu letra. De cada imagen se toma la mejor versión que quepa (drawable, xxhdpi, xhdpi…). Con tope de tamaño
     * para no llenar la memoria del teléfono.
     */
    fun assetsFrom(files: List<File>): Assets {
        val images = HashMap<String, Pair<String, ByteArray>>(); val fonts = HashMap<String, Pair<String, ByteArray>>()
        val rankOf = HashMap<String, Int>()
        var total = 0L
        fun mime(ext: String) = when (ext) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> "application/octet-stream"
        }
        fun rank(path: String): Int {
            val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
            return when {
                folder.contains("-night") -> 8
                folder == "drawable" || folder == "font" || folder.endsWith("nodpi") || folder.isEmpty() -> 0
                folder.contains("xxxhdpi") -> 3
                folder.contains("xxhdpi") -> 1
                folder.contains("xhdpi") -> 2
                folder.contains("hdpi") -> 4
                folder.contains("mdpi") -> 5
                else -> 6
            }
        }
        /** ¿Sirve este archivo? Devuelve (nombre del recurso, extensión) o null. */
        fun pick(path: String): Pair<String, String>? {
            val file = path.substringAfterLast('/')
            val ext = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val inFolder = path.contains('/')
            val ok = when (ext) {
                in fontExts -> !inFolder || path.contains("/font/")
                in imageExts -> !inFolder || Regex("(^|/)(drawable|mipmap)[^/]*/[^/]+$").containsMatchIn(path)
                else -> false
            }
            if (!ok) return null
            return file.substringBeforeLast('.').removeSuffix(".9") to ext   // (las .9.png se llaman sin el .9)
        }
        fun add(name: String, ext: String, r: Int, size: Long, read: () -> ByteArray?) {
            if (size > MAX_ASSET || size <= 0 || total + size > MAX_ASSETS_TOTAL) return
            val old = rankOf[name]
            if (old != null && old <= r) return
            if (!memoryLeft(size * 2)) return
            val bytes = runCatching { read() }.getOrNull() ?: return
            // (se guarda tal cual: la maqueta la pide por su dirección, sin copiarla dentro de la página)
            if (ext in fontExts) fonts[name] = mime(ext) to bytes else images[name] = mime(ext) to bytes
            rankOf[name] = r
            total += bytes.size
        }
        for (f in files) {
            val shown = f.name.substringAfter('_')
            val ext = shown.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext == "zip") {
                for (cs in listOf(Charsets.UTF_8, Charsets.ISO_8859_1)) {
                    val ok = runCatching {
                        java.util.zip.ZipFile(f, cs).use { z ->
                            val junk = Regex("(^|/)(build|\\.git|\\.gradle|\\.idea|node_modules|__MACOSX)/")
                            for (e in z.entries().asSequence()) {
                                val path = e.name.replace('\\', '/')
                                if (e.isDirectory || junk.containsMatchIn(path)) continue
                                val (name, x) = pick(path) ?: continue
                                add(name, x, rank(path), e.size) { z.getInputStream(e).use { it.readBytes() } }
                            }
                        }
                    }.isSuccess
                    if (ok) break
                }
            } else pick(shown)?.let { (name, x) -> add(name, x, 0, f.length()) { f.readBytes() } }
        }
        return Assets(images, fonts)
    }

    private val webTextTypes = mapOf("html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
        "mjs" to "text/javascript", "json" to "application/json", "svg" to "image/svg+xml", "txt" to "text/plain", "xml" to "text/xml",
        "webmanifest" to "application/manifest+json", "csv" to "text/csv", "md" to "text/plain")
    private val webBinTypes = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "avif" to "image/avif", "bmp" to "image/bmp", "ico" to "image/x-icon", "woff" to "font/woff",
        "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf", "mp3" to "audio/mpeg", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "mp4" to "video/mp4", "webm" to "video/webm", "pdf" to "application/pdf", "wasm" to "application/wasm")
    private const val MAX_WEB_FILE = 8_000_000L
    private const val MAX_WEB_TOTAL = 40_000_000L

    /**
     * 🌐 Tu página o app web completa, como estaría en tu hosting: cada archivo en su ruta, desde la carpeta de index.html
     * (HTML, CSS, JS, datos, imágenes, íconos, fuentes, audio y video). Devuelve (archivos, página de inicio) o null si
     * no hay HTML. Con tope de tamaño para no llenar la memoria del teléfono.
     */
    fun webSite(files: List<SourceFile>, attachments: List<File>): Pair<Map<String, Pair<String, ByteArray>>, String>? {
        fun ext(p: String) = p.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        fun norm(p: String) = p.replace('\\', '/').trimStart('/')
        val htmls = files.filter { ext(it.path.ifBlank { it.name }) in setOf("html", "htm") && it.content.isNotBlank() }
        if (htmls.isEmpty()) return null
        fun depth(f: SourceFile) = norm(f.path.ifBlank { f.name }).count { it == '/' }
        val index = htmls.filter { it.name.equals("index.html", true) }.minByOrNull { depth(it) } ?: htmls.minBy { depth(it) }
        val root = norm(index.path.ifBlank { index.name }).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun rel(p: String): String? = norm(p).let { n -> if (root.isEmpty()) n else if (n.startsWith(root)) n.removePrefix(root) else null }
        val out = LinkedHashMap<String, Pair<String, ByteArray>>()
        var total = 0L
        for (f in files) {
            val p = f.path.ifBlank { f.name }
            val type = webTextTypes[ext(p)] ?: continue
            if (f.content.isEmpty()) continue
            val r = rel(p) ?: continue
            val bytes = f.content.toByteArray(Charsets.UTF_8)
            out[r] = type to bytes; total += bytes.size
        }
        fun addBin(path: String, size: Long, read: () -> ByteArray?) {
            val type = webBinTypes[ext(path)] ?: (if (ext(path) == "svg") "image/svg+xml" else null) ?: return
            val r = rel(path) ?: return
            if (out.containsKey(r) || size <= 0 || size > MAX_WEB_FILE || total + size > MAX_WEB_TOTAL || !memoryLeft(size * 2)) return
            val b = runCatching { read() }.getOrNull() ?: return
            out[r] = type to b; total += b.size
        }
        for (f in attachments) {
            val shown = f.name.substringAfter('_')
            if (shown.endsWith(".zip", true)) {
                for (cs in listOf(Charsets.UTF_8, Charsets.ISO_8859_1)) {
                    val ok = runCatching {
                        java.util.zip.ZipFile(f, cs).use { z ->
                            for (e in z.entries().asSequence()) {
                                val path = e.name.replace('\\', '/')
                                if (e.isDirectory || Regex("(^|/)(\\.git|node_modules|__MACOSX)/").containsMatchIn(path)) continue
                                addBin(path, e.size) { z.getInputStream(e).use { it.readBytes() } }
                            }
                        }
                    }.isSuccess
                    if (ok) break
                }
            } else addBin(shown, f.length()) { f.readBytes() }
        }
        return out to (rel(index.path.ifBlank { index.name }) ?: index.name)
    }

    /**
     * Si el teléfono se quedó sin memoria para leer todo el proyecto, lo que se alcanzó a leer (se avisa en el informe).
     * No hay otro límite: proyectos de 100.000 líneas o más se leen completos.
     */
    @Volatile private var memoryNote: String? = null
    /** 📦 Aviso de los archivos que se sacaron de adentro de una página web (se muestra en la revisión). */
    @Volatile private var embeddedNote: String? = null

    /**
     * 📦 A veces la IA entrega el proyecto como una página web bonita (documentación) con el código de la app escrito
     * adentro: en recuadros <pre>/<code> o en textos de su JavaScript. Si esos recuadros son archivos de una app Android
     * (diseños XML, Kotlin, Java, Gradle, Manifest…), se sacan con su nombre (el que aparece justo antes de cada uno) y se
     * suman al proyecto. Así puedes verla como app Kotlin; la página sigue estando para verla como página web.
     */
    fun embeddedAppFiles(files: List<SourceFile>): List<SourceFile> {
        val have = files.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val out = mutableListOf<SourceFile>()
        val android = Regex("<\\?xml|xmlns:android|<(?:LinearLayout|androidx\\.|com\\.google\\.android|manifest|resources|menu|RelativeLayout|FrameLayout|ScrollView)\\b|" +
            "^\\s*package\\s+[\\w.]+|import\\s+android|class\\s+\\w+\\s*:\\s*\\w*(?:Activity|Fragment)|plugins\\s*\\{|android\\s*\\{", RegexOption.MULTILINE)
        val nameRx = Regex("\\b([\\w-]+\\.(?:kt|kts|java|xml|gradle|json|pro|properties))\\b")
        fun unescape(t: String, tags: String = "") = t.replace(Regex("<[^>]+>"), tags)
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
        for (f in files) {
            if (!Regex("\\.html?$", RegexOption.IGNORE_CASE).containsMatchIn(f.name)) continue
            val html = f.content
            val pieces = mutableListOf<Pair<Int, String>>()   // (dónde empieza, el código)
            Regex("<pre\\b[^>]*>(.*?)</pre>|<textarea\\b[^>]*>(.*?)</textarea>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)
                .forEach { m -> pieces += m.range.first to unescape(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            // (código en el JavaScript de la página: `…` con un archivo de Android adentro)
            Regex("`((?:[^`\\\\]|\\\\.){40,})`", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
                val code = m.groupValues[1].replace("\\`", "`").replace("\\\\", "\\")
                if (!code.contains("\${")) pieces += m.range.first to code
            }
            val paste = StringBuilder(); val names = mutableListOf<String>()
            for ((at, code0) in pieces.sortedBy { it.first }) {
                val code = code0.trim('\n', '\r')
                if (code.lines().size < 3 || !android.containsMatchIn(code)) continue
                // Su nombre: el último «algo.kt / algo.xml» que aparece en el texto justo antes del recuadro
                val before = unescape(html.substring(maxOf(0, at - 1500), at), " ")
                val name = nameRx.findAll(before).lastOrNull()?.groupValues?.get(1)
                if (name != null && (name.lowercase(Locale.ROOT) in have || name in names)) continue
                name?.let { names += it }
                paste.append(name ?: "").append("\n```\n").append(code).append("\n```\n\n")
            }
            if (paste.isEmpty()) continue
            val got = runCatching { splitPasted(paste.toString()) }.getOrDefault(emptyList())
                .filter { it.name.lowercase(Locale.ROOT) !in have }
            if (got.none { it.name.endsWith(".xml") && Regex("xmlns:android|<(?:LinearLayout|androidx\\.|com\\.google|RelativeLayout|FrameLayout|ScrollView)\\b").containsMatchIn(it.content) } &&
                got.none { it.name.endsWith(".kt") || it.name.endsWith(".java") }) continue
            out += got
            embeddedNote = "📦 La página ${f.name} trae adentro el código de una app (${got.size} archivo(s): " +
                got.take(8).joinToString(", ") { it.name } + (if (got.size > 8) "…" else "") + "). Los saqué y los ordené: " +
                "en «Ver tu app Kotlin» ves la app y en «Ver tu app y página web» ves la página."
        }
        return out
    }

    /** ¿Queda memoria para seguir leyendo? (deja libre más de un tercio para que la app y la maqueta sigan funcionando) */
    private fun memoryLeft(next: Long): Boolean {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return used + next * 3 < rt.maxMemory() * 2 / 3   // (un texto en memoria ocupa ~2 bytes por letra, más la revisión)
    }

    /**
     * Los archivos de un proyecto en .zip, TODOS y completos (se saltan build/, .git/, .gradle/…, que son compilados o
     * del editor; las imágenes se anotan por nombre).
     */
    fun filesFromZip(zip: File, charsets: List<java.nio.charset.Charset> = emptyList()): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        memoryNote = null
        val junk = Regex("(^|/)(build|\\.git|\\.gradle|\\.idea|\\.vscode|node_modules|__MACOSX|\\.cxx)/")
        val tries = listOf(Charsets.UTF_8) + charsets + listOf(Charsets.ISO_8859_1)
        for (cs in tries) {
            val ok = runCatching {
                java.util.zip.ZipFile(zip, cs).use { z ->
                    var read = 0
                    for (e in z.entries().asSequence()) {
                        val path = e.name.replace('\\', '/')
                        if (e.isDirectory || junk.containsMatchIn(path)) continue
                        val name = path.substringAfterLast('/')
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        // (imágenes, fuentes, audio y video: se anotan por nombre y ruta, sin leerlos)
                        if (ext in imageExts || ext in webBinTypes) { out += SourceFile(name, path, ""); continue }
                        if (ext !in codeExts) continue
                        if (!memoryLeft(maxOf(e.size, 0L))) {
                            memoryNote = "Tu proyecto es más grande de lo que la memoria de este teléfono puede leer de una vez: " +
                                "revisé $read archivo(s). Para revisar el resto, mándalo en partes (por ejemplo, un .zip por carpeta)."
                            break
                        }
                        val text = z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
                        out += SourceFile(name, path, text)
                        read++
                    }
                }
            }.isSuccess
            if (ok) break
            out.clear()
        }
        return out
    }

    /** Nombre por el contenido: «activity_main.xml» no se sabe, pero sí que es una pantalla, el manifiesto, strings… */
    private fun guessName(code: String, lang: String, n: Int): String {
        val c = code.trimStart()
        return when {
            c.contains("<manifest") -> "AndroidManifest.xml"
            c.contains("<resources") && c.contains("<string ") -> "strings.xml"
            c.contains("<resources") && c.contains("<color ") -> "colors.xml"
            c.contains("<resources") -> "values_$n.xml"
            Regex("<menu[\\s>]").containsMatchIn(c) -> "menu_$n.xml"
            Regex("(?i)<!doctype html|<html[\\s>]").containsMatchIn(c) -> if (n == 1) "index.html" else "pagina_$n.html"
            c.startsWith("<") || lang == "xml" -> "pantalla_$n.xml"
            lang == "css" || Regex("^[.#\\w-]+\\s*\\{").containsMatchIn(c) && !c.contains("fun ") -> "style_$n.css"
            lang in setOf("js", "javascript") -> "script_$n.js"
            lang in setOf("kotlin", "kt") || Regex("\\bfun\\s+\\w+\\s*\\(").containsMatchIn(c) ->
                (Regex("\\b(?:class|object)\\s+(\\w+)").find(c)?.groupValues?.get(1) ?: "Archivo_$n") + ".kt"
            lang == "java" -> (Regex("\\bclass\\s+(\\w+)").find(c)?.groupValues?.get(1) ?: "Archivo_$n") + ".java"
            else -> "archivo_$n.${lang.ifBlank { "txt" }}"
        }
    }

    // ------------------------------------------------------------------ 1b) pegar varios archivos seguidos

    /** Línea que dice el nombre de un archivo («MainActivity.kt», «// activity_main.xml», «<!-- strings.xml -->», «📄 style.css:»). */
    private val headerRx = Regex("^\\s*(?:(?://+|#+|<!--|/\\*+|\\*|📄|📁|[-=*]+|File|Archivo|Fichier|Datei|Arquivo|Файл|文件|檔案|ファイル|파일)\\s*[:：]?\\s*)?" +
        "\\**`?([\\w./\\\\-]+\\.(?:kt|kts|java|xml|html?|css|m?js|gradle|json|toml|properties))`?\\**\\s*(?:-->|\\*+/)?\\s*[:：]?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * 📋 Lo que pegaste, separado en archivos aunque vengan todos seguidos (MainActivity.kt y activity_main.xml uno
     * detrás del otro, con o sin ``` y con o sin el nombre arriba). Cada archivo empieza donde empieza otro tipo de
     * código («package …», «<?xml», «<!DOCTYPE html>», una pantalla XML nueva) o donde dice el nombre de un archivo.
     */
    fun splitPasted(text: String): List<SourceFile> {
        if (text.contains("```")) return filesFromText(text)
        val segs = mutableListOf<Pair<String?, String>>()
        var name: String? = null
        val cur = StringBuilder()
        var kind = ""            // "xml" · "web" · "kt" · "" (todavía nada)
        var root: String? = null // la etiqueta principal del XML (para saber cuándo termina)
        var closed = false       // ¿ya se cerró esa etiqueta (o el </html>)?
        var depth = 0            // llaves { } abiertas (CSS o JavaScript): un archivo nuevo empieza fuera de ellas
        val jsStart = Regex("^(const|let|function|async\\s+function|document\\.|window\\.|fetch\\(|export\\s|import\\s.+\\sfrom\\s|\\(\\s*\\)\\s*=>|addEventListener)")
        val cssStart = Regex("^[.#@:\\w*\\[\\]>~+,\\s-]+\\{\\s*$|^[.#@:\\w*\\[\\]>~+,\\s-]+\\{\\s*[\\w-]+\\s*:")
        fun flush() {
            if (cur.isNotBlank()) segs += name to cur.toString().trim('\n')
            cur.setLength(0); name = null; kind = ""; root = null; closed = false; depth = 0
        }
        for (line in text.replace("\r", "").lines()) {
            val t = line.trim()
            val h = headerRx.find(line)
            if (h != null && t.length < 140 && (!t.startsWith("<") || t.startsWith("<!--"))) {
                flush(); name = h.groupValues[1].substringAfterLast('/').substringAfterLast('\\'); continue
            }
            val startsXml = t.startsWith("<?xml")
            val startsHtml = Regex("^<(!doctype\\s+html|html)", RegexOption.IGNORE_CASE).containsMatchIn(t)
            val startsKt = Regex("^package\\s+[\\w.]+").containsMatchIn(t)
            val tagAtStart = line.startsWith("<") && !line.startsWith("</") && !t.startsWith("<!--")
            val newFile = cur.isNotBlank() && kind.isNotEmpty() && when {
                startsXml -> true
                startsHtml -> kind != "web" || closed   // («<!DOCTYPE html>» y «<html>» son la misma página)
                startsKt -> true
                tagAtStart && kind == "kt" -> true
                tagAtStart && kind == "xml" && closed -> true
                // (otro XML que empieza con sangría después de cerrar el anterior)
                t.startsWith("<") && !t.startsWith("</") && !t.startsWith("<!--") && kind == "xml" && closed -> true
                Regex("^(import|class|object|fun)\\b").containsMatchIn(line) && kind != "kt" && closed -> true
                kind == "web" && closed && t.isNotEmpty() -> true
                // 🎨 CSS seguido de JavaScript (o al revés), sin nombre entre medio: se separan fuera de las llaves
                kind == "css" && depth == 0 && jsStart.containsMatchIn(t) -> true
                kind == "js" && depth == 0 && cssStart.containsMatchIn(t) && !t.contains('=') && !t.contains('(') -> true
                else -> false
            }
            if (newFile) flush()
            cur.append(line).append('\n')
            if (kind.isEmpty() && t.isNotEmpty()) kind = when {
                startsHtml -> "web"
                t.startsWith("<") -> "xml"
                jsStart.containsMatchIn(t) -> "js"
                cssStart.containsMatchIn(t) && !t.contains('=') && !t.contains('(') -> "css"
                Regex("^(package|import|class|object|fun|@|data class|abstract|open|interface|private|internal|public|val|var)\\b").containsMatchIn(t) -> "kt"
                else -> ""
            }
            if (kind == "xml" && root == null) Regex("^<([A-Za-z_][\\w.:-]*)").find(t)?.groupValues?.get(1)?.takeIf { !t.startsWith("<?") }?.let { root = it }
            // (se cerró la etiqueta principal: «</resources>» solo o al final de la línea, o todo el archivo en una línea)
            if (kind == "xml" && root != null && (t == "</$root>" || t.endsWith("</$root>") ||
                    (t.startsWith("<$root") && t.endsWith("/>") && cur.lines().count { it.isNotBlank() } <= 2))) closed = true
            if (kind == "web" && Regex("(?i)</html>\\s*$").containsMatchIn(t)) closed = true
            if (kind == "css" || kind == "js") depth += t.count { it == '{' } - t.count { it == '}' }
        }
        flush()
        return finishNames(segs.map { Triple(it.first, it.second, "") })
    }

    /**
     * 🏷️ Los archivos pegados SIN nombre reciben el nombre que les da tu propio código:
     *  • pantallas: R.layout.x, ActivityMainBinding, @layout/x (include, tools:listitem) → activity_main.xml…
     *  • íconos (<vector>) y fondos (<shape>, <selector>…): @drawable/x de src/icon y de background, R.drawable.x
     *  • menús y navegación: @menu/x, R.menu.x, @navigation/x
     *  • recursos: themes.xml (estilos), colors.xml, strings.xml, dimens.xml, AndroidManifest.xml
     *  • web: index.html y los CSS, JS y páginas con el nombre y la carpeta que enlaza tu HTML (css/estilos.css…)
     * Lo que no se puede saber queda con un nombre de ejemplo (pantalla_3.xml, dibujo_2.xml).
     */
    private fun finishNames(segs: List<Triple<String?, String, String>>): List<SourceFile> {
        val all = segs.joinToString("\n") { it.second }
        fun refs(vararg rx: String) = rx.flatMap { r -> Regex(r).findAll(all).map { it.groupValues[1] }.toList() }.distinct().toMutableList()
        fun kindOf(code: String, lang: String): String {
            val c = code.trimStart()
            return when {
                c.contains("<manifest") -> "manifest"
                Regex("<(vector|animated-vector)[\\s>]").containsMatchIn(c) -> "vector"
                Regex("<(shape|selector|layer-list|ripple|inset|bitmap|level-list|transition)[\\s>]").containsMatchIn(c) && !c.contains("<resources") -> "shape"
                Regex("<menu[\\s>]").containsMatchIn(c) && !isLayout(c) -> "menu"
                Regex("<navigation[\\s>]").containsMatchIn(c) -> "nav"
                c.contains("<resources") -> "res"
                Regex("(?i)<!doctype html|<html[\\s>]").containsMatchIn(c) -> "html"
                c.startsWith("<") || lang == "xml" -> "layout"
                // (CSS: «selector { propiedad: valor; }» y ninguna palabra de Kotlin o JavaScript)
                lang == "css" || (Regex("\\{\\s*[\\w-]+\\s*:\\s*[^;{}]+;").containsMatchIn(c) &&
                    !Regex("\\b(fun|class|object|package|function|const|let|var|val|return)\\b|(?<!@)\\bimport\\b|=>|document\\.|window\\.").containsMatchIn(c)) -> "css"
                lang in setOf("js", "javascript", "mjs") || Regex("\\b(document\\.|window\\.|function\\s|const\\s|let\\s|=>)").containsMatchIn(c) &&
                    !Regex("\\bfun\\s|\\bval\\s|^package\\s", RegexOption.MULTILINE).containsMatchIn(c) -> "js"
                else -> "other"
            }
        }
        val names = segs.map { it.first?.substringAfterLast('/')?.substringAfterLast('\\') }.toMutableList()
        val paths = segs.map { it.first?.replace('\\', '/') }.toMutableList()
        val kinds = segs.map { kindOf(it.second, it.third) }
        val taken = names.filterNotNull().map { it.lowercase(Locale.ROOT) }.toMutableSet()
        fun base(n: String) = n.substringBeforeLast('.')
        val have = taken.map { base(it) }.toSet()
        // Lo que tu código pide (en el orden en que aparece)
        val layoutsWanted = (refs("R\\.layout\\.(\\w+)", "@layout/(\\w+)") + Regex("\\b(\\w+)Binding\\.inflate").findAll(all).map { m ->
            m.groupValues[1].replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT) }.toList()).distinct().filter { it !in have }.toMutableList()
        // (íconos: primero los que se usan como ícono —app:icon, drawableStart, menús— o se llaman ic_…; una imagen de
        //  ImageView puede ser una foto .png que no se pega)
        val strongIcons = refs("(?:icon|navigationIcon|drawable(?:Start|End|Left|Right|Top|Bottom))=\"@drawable/(\\w+)\"")
        val iconRefs = refs("(?:src|srcCompat|icon|navigationIcon|logo|drawable(?:Start|End|Left|Right|Top|Bottom))=\"@drawable/(\\w+)\"",
            "(?<!android\\.)\\bR\\.drawable\\.(\\w+)", "android:icon=\"@drawable/(\\w+)\"").filter { it !in have }
            .sortedBy { if (it in strongIcons || it.startsWith("ic_")) 0 else 1 }.toMutableList()
        val bgRefs = refs("(?<![\\w:])@drawable/(\\w+)").filter { it !in have && it !in iconRefs }.toMutableList()
        val menusWanted = refs("@menu/(\\w+)", "(?<!android\\.)\\bR\\.menu\\.(\\w+)").filter { it !in have }.toMutableList()
        val navsWanted = refs("@navigation/(\\w+)", "\\bR\\.navigation\\.(\\w+)").filter { it !in have }.toMutableList()
        // 🌐 Lo que enlaza tu página principal (con su carpeta)
        val firstHtml = segs.indices.firstOrNull { kinds[it] == "html" }?.let { segs[it].second }.orEmpty()
        fun links(rx: String) = Regex(rx, RegexOption.IGNORE_CASE).findAll(firstHtml).map { it.groupValues[1].removePrefix("./").trimStart('/') }
            .filter { !it.startsWith("http") && !it.startsWith("//") && it.substringAfterLast('/').lowercase(Locale.ROOT) !in taken }.distinct().toMutableList()
        val cssLinks = links("<link[^>]+href=[\"']([^\"'?#]+\\.css)[\"']")
        val jsLinks = links("<script[^>]+src=[\"']([^\"'?#]+\\.m?js)[\"']")
        val pageLinks = links("<a[^>]+href=[\"']([^\"'?#:]+\\.html?)[\"']").filter { !it.equals("index.html", true) }.toMutableList()
        fun take(list: MutableList<String>): String? = list.removeFirstOrNull()
        val counter = HashMap<String, Int>()
        fun sample(prefix: String, ext: String): String {
            var n = counter.getOrDefault(prefix, 0)
            var nm: String
            do { n++; nm = "${prefix}_$n.$ext" } while (nm in taken)
            counter[prefix] = n
            return nm
        }
        for (i in segs.indices) {
            if (names[i] != null) continue
            val c = segs[i].second
            var path: String? = null
            val nm: String = when (kinds[i]) {
                "manifest" -> "AndroidManifest.xml"
                "res" -> when {
                    c.contains("<style") -> "themes.xml"
                    c.contains("<color ") -> "colors.xml"
                    c.contains("<string ") || c.contains("<string-array") || c.contains("<plurals") -> "strings.xml"
                    c.contains("<dimen ") -> "dimens.xml"
                    else -> "values.xml"
                }.let { if (it in taken) sample(it.substringBeforeLast('.'), "xml") else it }
                "vector" -> (take(iconRefs) ?: take(bgRefs))?.let { "$it.xml" } ?: sample("dibujo", "xml")
                "shape" -> (take(bgRefs) ?: take(iconRefs))?.let { "$it.xml" } ?: sample("fondo", "xml")
                "menu" -> take(menusWanted)?.let { "$it.xml" } ?: sample("menu", "xml")
                "nav" -> take(navsWanted)?.let { "$it.xml" } ?: sample("navegacion", "xml")
                "layout" -> take(layoutsWanted)?.let { "$it.xml" } ?: sample("pantalla", "xml")
                "html" -> if ("index.html" !in taken) "index.html" else take(pageLinks)?.also { path = it }?.substringAfterLast('/') ?: sample("pagina", "html")
                "css" -> take(cssLinks)?.also { path = it }?.substringAfterLast('/') ?: (if ("style.css" !in taken) "style.css" else sample("style", "css"))
                "js" -> take(jsLinks)?.also { path = it }?.substringAfterLast('/') ?: (if ("script.js" !in taken) "script.js" else sample("script", "js"))
                else -> guessName(c, segs[i].third, i + 1)
            }
            names[i] = nm; paths[i] = path ?: nm
            taken += nm.lowercase(Locale.ROOT)
        }
        return segs.indices.map { i -> SourceFile(names[i]!!, paths[i]!!, segs[i].second) }
    }

    // ------------------------------------------------------------------ 1c) ordenar en sus carpetas

    /**
     * 🗂️ Cada archivo en su carpeta de un proyecto Android (o de una página web), y las pantallas sin nombre con el
     * nombre que les da tu código («setContentView(R.layout.activity_main)» → activity_main.xml).
     */
    fun organize(files0: List<SourceFile>): List<SourceFile> {
        // 📦 Una página web (HTML) que trae adentro el código de una app Android en sus recuadros de código: se saca
        val files = (files0 + embeddedAppFiles(files0)).toMutableList()
        val code = files.filter { it.name.endsWith(".kt") || it.name.endsWith(".java") }.joinToString("\n") { it.content }
        // Pantallas que el código usa: R.layout.x y las de ViewBinding (ActivityMainBinding → activity_main)
        val wanted = (Regex("R\\.layout\\.(\\w+)").findAll(code).map { it.groupValues[1] } +
            Regex("\\b(\\w+)Binding\\.inflate").findAll(code).map { m ->
                m.groupValues[1].replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT) }).distinct().toMutableList()
        files.filter { it.name.endsWith(".xml") }.forEach { wanted.remove(it.name.removeSuffix(".xml")) }
        for (i in files.indices) {
            val f = files[i]
            if (Regex("^pantalla_\\d+\\.xml$").matches(f.name) && isLayout(f.content) && wanted.isNotEmpty()) {
                val n = wanted.removeAt(0) + ".xml"
                files[i] = f.copy(name = n, path = n)
            }
        }
        return files.map { f ->
            if (f.path.contains('/')) return@map f
            val ext = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val c = f.content
            val path = when {
                f.name.equals("AndroidManifest.xml", true) || c.contains("<manifest") -> "app/src/main/AndroidManifest.xml"
                ext == "xml" && c.contains("<resources") -> "app/src/main/res/values/${f.name}"
                ext == "xml" && Regex("<menu[\\s>]").containsMatchIn(c) -> "app/src/main/res/menu/${f.name}"
                ext == "xml" && Regex("<navigation[\\s>]").containsMatchIn(c) -> "app/src/main/res/navigation/${f.name}"
                ext == "xml" && Regex("<(shape|selector|vector|layer-list|ripple)[\\s>]").containsMatchIn(c) -> "app/src/main/res/drawable/${f.name}"
                ext == "xml" -> "app/src/main/res/layout/${f.name}"
                ext == "kt" || ext == "java" -> {
                    val pkg = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(c)?.groupValues?.get(1)
                    "app/src/main/java/" + (pkg?.replace('.', '/')?.plus("/") ?: "") + f.name
                }
                f.name.startsWith("settings.gradle") -> f.name
                ext == "gradle" || f.name.endsWith(".gradle.kts") -> "app/${f.name}"
                else -> f.name   // web: index.html, style.css, script.js… en la carpeta principal
            }
            f.copy(path = path)
        }
    }

    /** 🗂️ Las carpetas con sus archivos, para mostrarlas («📂 app/src/main/res/layout/ · 📄 activity_main.xml»). */
    fun treeText(files: List<SourceFile>): String {
        val real = files.filter { it.content.isNotBlank() }
        if (real.isEmpty()) return ""
        return real.groupBy { it.path.substringBeforeLast('/', "") }.toSortedMap().entries.joinToString("\n") { (dir, fs) ->
            (if (dir.isEmpty()) "📂 (carpeta principal)" else "📂 $dir/") + "\n" + fs.joinToString("\n") { f ->
                "   📄 ${f.name} · ${f.content.count { it == '\n' } + 1} líneas" }
        }
    }

    // ------------------------------------------------------------------ 1d) tu lógica Kotlin en la maqueta

    /**
     * 🧪 La lógica de tus botones, para la maqueta: lo que tu Kotlin hace al tocar cada botón (mostrar un aviso, cambiar
     * un texto, mostrar u ocultar algo, abrir otra pantalla) se simula en la maqueta con «maqueta.js». No es la app real:
     * lo demás (guardar datos, internet, la base de datos…) funciona recién al compilarla. null = no encontré botones.
     */
    fun kotlinToMockupJs(files: List<SourceFile>): String? {
        val kts = files.filter { it.name.endsWith(".kt") || it.name.endsWith(".java") }
        if (kts.isEmpty()) return null
        val xml = files.filter { it.name.endsWith(".xml") }.joinToString("\n") { it.content }
        val ids = Regex("@\\+id/(\\w+)").findAll(xml).map { it.groupValues[1] }.toSet()
        fun camel(id: String) = id.split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { c -> c.uppercase() } }.joinToString("")
        val byCamel = ids.associateBy { camel(it) }
        // Pantalla de cada clase (para «abrir otra pantalla»)
        val screenOf = HashMap<String, String>()
        for (f in kts) {
            val cls = Regex("\\bclass\\s+(\\w+)").find(f.content)?.groupValues?.get(1) ?: continue
            val lay = Regex("setContentView\\(\\s*R\\.layout\\.(\\w+)").find(f.content)?.groupValues?.get(1)
                ?: Regex("\\b(\\w+)Binding\\.inflate").find(f.content)?.groupValues?.get(1)
                    ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")?.lowercase(Locale.ROOT)
                ?: ("activity_" + cls.removeSuffix("Activity").removeSuffix("Fragment").replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT))
            screenOf[cls] = lay
        }
        val out = StringBuilder()
        val ix = KtIndex(kts, files)
        for (f in kts) {
            val c = f.content
            val myLayout = Regex("\\bclass\\s+(\\w+)").find(c)?.groupValues?.get(1)?.let { screenOf[it] }
            // Variables con su id («val btnGuardar = findViewById<Button>(R.id.btn_guardar)»)
            val vars = HashMap<String, String>()
            Regex("(?:val|var)?\\s*(\\w+)\\s*(?::\\s*[\\w.<>?]+)?\\s*=\\s*(?:\\w+\\.)?findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)\\s*\\)")
                .findAll(c).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
            fun idOf(ref: String): String? {
                val r = ref.removePrefix("binding.").removePrefix("this.").trim()
                return vars[r] ?: byCamel[r] ?: r.takeIf { it in ids }
            }
            val clickRx = Regex("(findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)\\s*\\)|binding\\.\\w+|\\b\\w+)\\s*\\??\\.setOnClickListener\\s*(?:\\(\\s*(?:View\\.OnClickListener\\s*)?)?\\{")
            for (m in clickRx.findAll(c)) {
                val id = m.groupValues[2].ifEmpty { null } ?: idOf(m.groupValues[1]) ?: continue
                val body = block(c, m.range.last) ?: continue
                // 🪟 Menús y ventanas: en el mismo botón o en la función que llama (showMenu(), abrirDialogo()…)
                // (primero una ventana armada por código —Dialog/AlertDialog con sus vistas—, en el botón o en lo que llama)
                val cw = ix.codeWindow(body) ?: ix.followedDeep(body).firstNotNullOfOrNull { ix.codeWindow(it) }
                val wm = ix.windowOrMenu(body) ?: ix.followed(body).firstNotNullOfOrNull { ix.windowOrMenu(it) }
                // (si la ventana se llena con una lista de opciones que se arma con datos, el menú con esas opciones se ve mejor)
                val win = if (cw != null && wm != null && wm.startsWith("menu(") && cw.contains("x:\"…\"")) wm else cw ?: wm
                // («if (campo vacío) { aviso } else { … }»: en la maqueta también se revisa lo escrito, como en tu app)
                val basic = if (Regex("^\\s*if\\s*\\(").containsMatchIn(body)) listOf(branchJs(body.trim(), ::idOf, screenOf)).filter { it.isNotBlank() }
                    else actions(body, ::idOf, screenOf, skipDialogTitles = win != null)
                val acts = basic + ix.styleStmts(body, { ref ->
                    if (ref == "window.decorView" || ref.endsWith("root") || ref.endsWith("rootView")) myLayout?.let { "@screen:$it" }
                    else (Regex("R\\.id\\.(\\w+)").find(ref)?.groupValues?.get(1) ?: idOf(ref))?.let { "el('$it')" } }, startupText = false) + listOfNotNull(win) +
                    ix.toggles(body, ::idOf) + ix.pages(body, ::idOf)
                // (si el botón solo cambia de página, se suma a lo que ya hace en la maqueta, como abrir un panel ⚙)
                val onlyPages = acts.isNotEmpty() && acts.all { it.startsWith("pagina") || it.startsWith("mostrarPadre(") }
                if (acts.isNotEmpty()) out.append(if (onlyPages) "alTocar('" else "alClick('").append(id).append("', function(){ ").append(acts.joinToString(" ")).append(" });\n")
            }
            // ⋮ El menú de la barra de arriba (onCreateOptionsMenu) y lo que hace cada opción (onOptionsItemSelected)
            optionsMenuJs(c, files, ::idOf, screenOf)?.let { out.append(it).append('\n') }
            // 🎨 Lo que tu Kotlin pinta al abrir la pantalla (onCreate… y las funciones que llama), sin lo que pasa al
            //    tocar botones: colores (también «si es modo oscuro, negro; si no, blanco»), fondos redondeados hechos en
            //    Kotlin, bordes, esquinas, mayúsculas, textos, y lo que se le hace a TODOS los botones de un tipo
            val startup = ix.startupBodies(c)
            // 🧱 Lo que tu código arma y agrega al abrir (una fila con un botón EDITAR debajo de otra…): antes de los estilos
            ix.insertedViews(startup, ::idOf).forEach { js -> if (!out.contains(js)) out.append(js).append('\n') }
            // 📏 Elementos que tu código deja del mismo alto (matchHeights(botonA, botonB))
            ix.equalHeights(startup, ::idOf).forEach { js -> if (!out.contains(js)) out.append(js).append('\n') }
            ix.walkers(startup + ix.fileFunctions(c)).forEach { out.append(it).append('\n') }
            ix.drawerPanels(startup).forEach { out.append(it).append('\n') }
            // 🖼️ Imágenes que tu código agrega al abrir (un logo junto al botón ☰…) y 🧾 las filas que pone en sus listas
            ix.addedImages(startup).forEach { js -> if (!out.contains(js)) out.append(js).append('\n') }
            // (y los íconos que el Adapter de una lista agrega en cada fila: ⤓ descargar, ⚑ reportar…)
            if (Regex(":\\s*(?:RecyclerView\\.)?Adapter<|\\bListAdapter<|\\bBaseAdapter\\(").containsMatchIn(c))
                ix.addedImages(ix.fileFunctions(c)).forEach { js -> if (!out.contains(js)) out.append(js).append('\n') }
            ix.startupRows(startup)?.let { js -> if (!out.contains("filas(")) out.append(js).append('\n') }
            val seenText = HashSet<String>(); val seenLine = HashSet<String>()
            for (b in startup) ix.styleStmts(b, { ref ->
                when {
                    ref == "window.decorView" || ref.endsWith("root") || ref.endsWith("rootView") || ref.contains("android.R.id.content") ->
                        if (myLayout != null) "@screen:$myLayout" else null
                    else -> (Regex("R\\.id\\.(\\w+)").find(ref)?.groupValues?.get(1) ?: idOf(ref))?.let { "el('$it')" }
                }
            }, startupText = true).forEach { js ->
                // (cada cosa una vez; el texto de cada elemento, el primero que se le pone al abrir)
                val tid = Regex("^texto\\('([^']+)'").find(js)?.groupValues?.get(1)
                if (seenLine.add(js) && (tid == null || seenText.add(tid))) out.append(js).append('\n')
            }
        }
        // 👁️ Lo que tus listas muestran en cada fila aunque en el diseño esté escondido (visibility = View.VISIBLE)
        ix.shownInRows().takeIf { it.isNotEmpty() }?.let { ids -> out.append("mostrarEnFilas(${ids.joinToString(",", "[", "]") { "'$it'" }});\n") }
        // ⚠️ Lo que tu Kotlin cambia de diseño y la maqueta no pudo imitar: se avisa arriba de la maqueta
        // ✨ Textos (e íconos) pintados con degradado de colores en toda la app («paint.shader = LinearGradient(…)»)
        ix.gradientText()?.let { out.append(it).append('\n') }
        // ✨ Textos con su propio degradado (un nombre pintado con un «span» de dos colores, como StarName.styled(…))
        ix.gradientSpanUses().forEach { out.append(it).append('\n') }
        val total = ix.runtimeStyleCount()
        val imitated = Regex("\\b(fondo|letra|forma|grad|borde|mayus|pantalla|pista|tinte)\\(").findAll(out).count() +
            Regex("\\bdegradado\\(").findAll(out).count() * Regex("\\.shader\\s*=").findAll(ix.allCode).count()
        if (total >= 6 && total > imitated) out.append("avisoCodigo($imitated,$total);\n")
        if (out.isEmpty()) return null
        return "// maqueta.js · lo armó Cumulus desde tu código Kotlin: simula lo que hacen tus botones.\n" +
            "// (la app real usa tu Kotlin al compilarla)\n" + out
    }

    /** Texto para el JavaScript de la maqueta, entre comillas dobles (los $variables quedan como «…»). */
    private fun jsq(s: String) = "\"" + s.replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…").replace("\\$", "$")
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    /**
     * 🪟 Lee tu Kotlin para que la maqueta abra lo que abre tu app al tocar un botón:
     *  • un menú (PopupMenu, AlertDialog con opciones, o funciones propias como addOption(ícono, "Imagen") { … });
     *  • una ventana con su diseño (inflate(R.layout.dialog_x), setContentView, setView, DialogXBinding.inflate).
     * Las opciones se buscan también en listas (listOf("a" to "Rápido", …), listOf(Modo("clave", "Solo chat", …))).
     */
    private class KtIndex(kts: List<SourceFile>, files: List<SourceFile>) {
        val all: String = kts.joinToString("\n") { it.content }
        /** El código sin textos ni comentarios (para contar lo que de verdad se ejecuta). */
        val allCode: String by lazy { withoutStrings(all) }
        /** Cuerpo de cada función (la primera con ese nombre). */
        val funs = HashMap<String, String>()
        /** 🎨 Los colores de colors.xml (para los que tu Kotlin usa con R.color.…). */
        val colors = HashMap<String, String>()

        /** Opciones de cada menú de res/menu (sus títulos, con los textos de strings.xml). */
        val menuTitles = HashMap<String, List<String>>()
        init {
            val strings = HashMap<String, String>()
            files.filter { it.content.contains("<resources") && !it.path.contains("-night") }.forEach { f ->
                Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.content)
                    .forEach { strings[it.groupValues[1]] = it.groupValues[2].trim() }
                Regex("<color\\s+name=\"([^\"]+)\"[^>]*>(.*?)</color>").findAll(f.content)
                    .forEach { colors[it.groupValues[1]] = it.groupValues[2].trim() }
            }
            files.filter { Regex("<menu[\\s>]").containsMatchIn(it.content) && it.name.endsWith(".xml") }.forEach { f ->
                menuTitles[f.name.substringBeforeLast('.')] = Regex("android:title=\"([^\"]+)\"").findAll(f.content).map { m ->
                    val t = m.groupValues[1]; if (t.startsWith("@string/")) strings[t.removePrefix("@string/")] ?: t.removePrefix("@string/") else t
                }.toList()
            }
            for (f in kts) {
                val c = f.content
                for (m in Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?(\\w+)\\s*\\(").findAll(c)) {
                    val name = m.groupValues[1]
                    if (name in funs) continue
                    var i = m.range.last; var depth = 0
                    while (i < c.length) { if (c[i] == '(') depth++ else if (c[i] == ')') { depth--; if (depth == 0) break }; i++ }
                    // (después de «)» viene «: Tipo» y luego { … } o = …; sin eso es una función sin cuerpo)
                    var j = i + 1
                    while (j < c.length && c[j] != '{' && c[j] != '=' && c[j] != '\n') j++
                    if (j < c.length && c[j] == '\n') { var k = j; while (k < c.length && c[k].isWhitespace()) k++; if (k < c.length && (c[k] == '{' || c[k] == '=')) j = k }
                    if (j >= c.length || c[j] == '\n') continue
                    val body = when (c[j]) {
                        '{' -> block(c, j)
                        '=' -> c.substring(j + 1, c.indexOf('\n', j).let { if (it < 0) c.length else it })
                        else -> null
                    } ?: continue
                    funs[name] = body
                }
            }
        }

        /** Un color de Kotlin como [rojo, verde, azul, opacidad] (0-255): Color.parseColor("#…"), Color.RED, R.color.x, 0xFF…, Color.rgb(…). */
        fun rgbaOf(expr: String): IntArray? {
            fun hex(h0: String): IntArray? {
                val h = h0.removePrefix("#")
                return when (h.length) {
                    6 -> intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16), 255)
                    8 -> intArrayOf(h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16), h.substring(6, 8).toInt(16), h.substring(0, 2).toInt(16))
                    3 -> intArrayOf(h[0].toString().repeat(2).toInt(16), h[1].toString().repeat(2).toInt(16), h[2].toString().repeat(2).toInt(16), 255)
                    4 -> intArrayOf(h[1].toString().repeat(2).toInt(16), h[2].toString().repeat(2).toInt(16), h[3].toString().repeat(2).toInt(16), h[0].toString().repeat(2).toInt(16))
                    else -> null
                }
            }
            Regex("Color\\.parseColor\\(\\s*\"(#[0-9A-Fa-f]{3,8})\"").find(expr)?.let { return hex(it.groupValues[1]) }
            // "#FFA500".toColorInt() · Color.parseColor("orange") · android.R.color.holo_orange_dark
            Regex("\"(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{8})\"\\s*\\.toColorInt\\(").find(expr)?.let { return hex(it.groupValues[1]) }
            Regex("Color\\.parseColor\\(\\s*\"([A-Za-z]+)\"").find(expr)?.let { m -> namedColor(m.groupValues[1])?.let { return if (it == "transparent") intArrayOf(0, 0, 0, 0) else hex(it) } }
            Regex("android\\.R\\.color\\.(\\w+)").find(expr)?.let { m -> namedColor(m.groupValues[1])?.let { return if (it == "transparent") intArrayOf(0, 0, 0, 0) else hex(it) } }
            // (Compose: Color.Red, Color.LightGray…)
            Regex("\\bColor\\.(Black|White|Red|Green|Blue|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta|Transparent)\\b").find(expr)?.let { m ->
                namedColor(m.groupValues[1])?.let { return if (it == "transparent") intArrayOf(0, 0, 0, 0) else hex(it) } }
            Regex("\\bColor\\.(BLACK|WHITE|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA|TRANSPARENT)\\b").find(expr)?.let {
                return when (it.groupValues[1]) {
                    "BLACK" -> intArrayOf(0, 0, 0, 255); "WHITE" -> intArrayOf(255, 255, 255, 255); "RED" -> intArrayOf(255, 0, 0, 255)
                    "GREEN" -> intArrayOf(0, 255, 0, 255); "BLUE" -> intArrayOf(0, 0, 255, 255); "GRAY" -> intArrayOf(136, 136, 136, 255)
                    "DKGRAY" -> intArrayOf(68, 68, 68, 255); "LTGRAY" -> intArrayOf(204, 204, 204, 255); "YELLOW" -> intArrayOf(255, 255, 0, 255)
                    "CYAN" -> intArrayOf(0, 255, 255, 255); "MAGENTA" -> intArrayOf(255, 0, 255, 255); else -> intArrayOf(0, 0, 0, 0)
                }
            }
            Regex("Color\\.argb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(expr)?.let { m ->
                return intArrayOf(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[1].toInt()) }
            Regex("Color\\.rgb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(expr)?.let { m ->
                return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), 255) }
            Regex("(?<!android\\.)\\bR\\.color\\.(\\w+)").find(expr)?.let { m ->
                var v: String? = colors[m.groupValues[1]]
                var guard = 0
                while (v != null && v.startsWith("@color/") && guard++ < 4) v = colors[v.removePrefix("@color/")]
                if (v != null && v.startsWith("@android:color/")) v = namedColor(v.substringAfter('/'))?.let { if (it == "transparent") "#00000000" else it }
                return v?.takeIf { it.startsWith("#") }?.let { hex(it) }
            }
            Regex("\\b0x([0-9A-Fa-f]{8})").find(expr)?.let { return hex(it.groupValues[1]) }
            return null
        }

        /**
         * 🎨 Los colores que tu Kotlin pone por código (setBackgroundColor, setTextColor, backgroundTintList,
         * setCardBackgroundColor), en maqueta.js: colorFondo / colorTexto de un elemento, o colorPantalla (window.decorView).
         */
        fun colorActions(body: String, idOf: (String) -> String?, layout: String?): List<String> {
            val ref = "(window\\.decorView|(?:binding\\.)?root(?:View)?|findViewById(?:<[^>]+>)?\\(\\s*(?:android\\.)?R\\.id\\.\\w+\\s*\\)|(?:binding\\.)?\\w+)"
            val rx = Regex("$ref\\s*\\??\\.(setBackgroundColor|setTextColor|setCardBackgroundColor|setBackgroundTintList|backgroundTintList\\s*=)\\s*\\(?([^;\\n]{1,160})")
            val out = mutableListOf<String>()
            for (m in rx.findAll(body)) {
                val c = rgbaOf(m.groupValues[3]) ?: continue
                val rgba = c.joinToString(",")
                val target = m.groupValues[1]
                val screen = target == "window.decorView" || target.endsWith("root") || target.endsWith("rootView") || target.contains("android.R.id.content")
                val kind = m.groupValues[2]
                if (screen) { if (layout != null && kind != "setTextColor") out += "colorPantalla('$layout',$rgba);"; continue }
                val id = Regex("R\\.id\\.(\\w+)").find(target)?.groupValues?.get(1) ?: idOf(target) ?: continue
                out += if (kind == "setTextColor") "colorTexto(el('$id'),$rgba);" else "colorFondo(el('$id'),$rgba);"
            }
            return out
        }

        /** Quita los bloques de lo que pasa al tocar o escribir (setOnClickListener, addTextChangedListener…): eso no corre al abrir. */
        fun withoutListeners(body0: String): String {
            var body = body0
            val rx = Regex("\\.(?:setOn\\w*Listener|addTextChangedListener|addOnScrollListener|doOnTextChanged|doAfterTextChanged|setOnCheckedChangeListener)\\s*(?:\\(\\s*(?:\\w+\\s*)?)?\\{")
            for (m in rx.findAll(body).toList().asReversed()) {
                val inner = block(body, m.range.last) ?: continue
                body = body.substring(0, m.range.last + 1) + " ".repeat(inner.length) + body.substring(m.range.last + 1 + inner.length)
            }
            return body
        }

        /** Lo que corre al abrir la pantalla: onCreate / onViewCreated / onStart / onResume y lo que llaman (hasta 6 niveles). */
        fun startupBodies(c: String): List<String> {
            val out = mutableListOf<String>(); val seen = hashSetOf("onCreate", "onViewCreated", "onStart", "onResume")
            var level = mutableListOf<String>()
            for (m in Regex("\\bfun\\s+(onCreate|onViewCreated|onStart|onResume)\\s*\\(").findAll(c)) {
                val open = c.indexOf('{', m.range.last).takeIf { it >= 0 } ?: continue
                block(c, open)?.let { level += withoutListeners(it) }
            }
            // (primero las funciones de ESTE archivo: con un proyecto grande, las de otros archivos con el mismo nombre
            //  llenaban la lista y no se llegaba a las que dan el estilo, como stylePill)
            val mine = Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w.]+\\.)?(\\w+)\\s*\\(").findAll(c).map { it.groupValues[1] }.toSet()
            var depth = 0
            while (level.isNotEmpty() && depth++ <= 8 && out.size < 400) {
                out += level
                val next = mutableListOf<String>()
                for (b in level) for (m in Regex("\\b([a-z]\\w*)\\s*\\(").findAll(b)) {
                    val name = m.groupValues[1]
                    if (name in seen || name in setOf("findViewById", "setContentView", "super", "runCatching", "apply", "let", "also", "with", "run")) continue
                    if (name !in mine && out.size + next.size >= 150) continue
                    val f = funs[name] ?: continue
                    seen += name; next += withoutListeners(f)
                    if (out.size + next.size >= 400) break
                }
                level = next
            }
            return out
        }

        /** Todas las funciones escritas en este archivo (para encontrar las que dan estilo a TODOS los botones). */
        fun fileFunctions(c: String): List<String> =
            Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w.]+\\.)?(\\w+)\\s*\\(").findAll(c).map { it.groupValues[1] }.distinct()
                .mapNotNull { funs[it] }.map { withoutListeners(it) }.toList()

        /**
         * 🗄️ Los paneles laterales (menú ☰ y ⚙️ de un DrawerLayout): «val panel = drawer.getChildAt(i)» y lo que se le
         * pone (fondo negro…). En la maqueta: todos('.drawer', …).
         */
        fun drawerPanels(bodies: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (b in bodies) for (m in Regex("\\bval\\s+(\\w+)\\s*=\\s*(\\w+)\\.getChildAt\\(").findAll(b)) {
                val v = m.groupValues[1]; val parent = m.groupValues[2]
                val isDrawer = parent.contains("drawer", true) ||
                    Regex("\\b${Regex.escape(parent)}\\s*=\\s*[^\\n]*(DrawerLayout|R\\.id\\.\\w*drawer)", RegexOption.IGNORE_CASE).containsMatchIn(b)
                if (!isDrawer) continue
                val stmts = styleStmts(b.substring(m.range.last), { ref -> if (ref == v) "e" else null }, startupText = false)
                if (stmts.isNotEmpty()) out += "todos('.drawer',function(e){ ${stmts.distinct().joinToString(" ")} });"
            }
            return out.toList()
        }

        /** Un número de dp de una expresión de Kotlin: «16 * d», «(16 * density).toInt()», «dp(16)», «16.dp», «16f». */
        fun dpOf(expr: String): Int? {
            val n = Regex("(\\d+(?:\\.\\d+)?)").find(expr)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
            val scaled = Regex("\\*\\s*[\\w.]*(?:density|\\bd\\b|dp)|\\bdp\\s*\\(|\\.dp\\b|\\.toPx|dpToPx").containsMatchIn(expr)
            return (if (scaled) n else n / 2.75f).toInt()
        }

        /**
         * 🎨 Un color de Kotlin para maqueta.js: «[r,g,b,a]», o «dn([día],[noche])» si depende del modo claro u oscuro
         * («if (light) Color.WHITE else Color.BLACK», «Color.parseColor(if (isDark) "#000" else "#FFF")»), o el de una
         * variable (val gris = Color.parseColor("#1F1F1F")). null si se calcula de otra forma.
         */
        /** 🔎 Dónde buscar primero una variable de color («val fill = …»): en la función que se está leyendo. */
        private var scopeNow: String = ""

        fun colorJs(expr0: String, depth: Int = 0, scope: String = scopeNow.ifEmpty { all }): String? {
            if (depth > 8) return null
            val expr = expr0.lines().joinToString("\n") { it.substringBefore("//") }.trim()   // (sin comentarios, en cada línea)
            fun arr(a: IntArray) = a.joinToString(",", "[", "]")
            fun dayNight(cond: String, a: String, b: String): String? {
                val ca = colorJs(a, depth + 1, scope) ?: return null
                val cb = colorJs(b, depth + 1, scope) ?: return null
                val c = cond.lowercase(Locale.ROOT)
                val lm = Regex("(!?)\\s*[\\w.]*(light|claro|day|dia|día)").find(c)
                val dm = Regex("(!?)\\s*[\\w.]*(dark|night|noche|oscuro|nocturno)").find(c)
                return when {
                    lm != null -> if (lm.groupValues[1] == "!") "dn($cb,$ca)" else "dn($ca,$cb)"
                    dm != null -> if (dm.groupValues[1] == "!") "dn($ca,$cb)" else "dn($cb,$ca)"
                    else -> cb   // (otra condición: lo de siempre, el «else»)
                }
            }
            // («when { … }» primero: sus ramas pueden tener un «if» adentro)
            Regex("^when\\s*\\{").find(expr)?.let { m ->
                val inner = block(expr, m.range.last) ?: return null
                val branches = inner.lines().mapNotNull { l -> Regex("^\\s*(.+?)\\s*->\\s*(.+?)\\s*(//.*)?$").find(l)?.let { it.groupValues[1] to it.groupValues[2] } }
                val other = branches.firstOrNull { it.first == "else" }?.second ?: return null
                val mode = branches.firstOrNull { b -> b.first != "else" && Regex("^!?[\\w.]*(light|claro|dark|night|noche|oscuro)\\w*$", RegexOption.IGNORE_CASE).matches(b.first.trim()) }
                return if (mode != null) dayNight(mode.first, mode.second, other) else colorJs(other, depth + 1, scope)
            }
            Regex("^if\\s*\\((.+?)\\)\\s*(.+?)\\s+else\\s+(.+)$", RegexOption.DOT_MATCHES_ALL).find(expr)?.let { m ->
                return dayNight(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            Regex("if\\s*\\(([^)]+)\\)\\s*(\"#[0-9A-Fa-f]{3,8}\")\\s*else\\s*(\"#[0-9A-Fa-f]{3,8}\")").find(expr)?.let { m ->
                return dayNight(m.groupValues[1], "Color.parseColor(${m.groupValues[2]})", "Color.parseColor(${m.groupValues[3]})") }
            rgbaOf(expr)?.let { return arr(it) }
            // (una variable con el color: «val pillGrayDark = Color.parseColor("#1F1F1F")»)
            Regex("^([a-zA-Z_]\\w*)$").find(expr)?.let { m -> valueOf(m.groupValues[1], scope)?.let { return colorJs(it, depth + 1, scope) } }
            Regex("ColorStateList\\.valueOf\\(").find(expr)?.let { m -> parens(expr, m.range.last)?.let { return colorJs(it, depth + 1, scope) } }
            return null
        }

        /**
         * 🎨 Lo que una parte de tu Kotlin le cambia al diseño, en maqueta.js. [target] dice a qué elemento corresponde
         * cada variable («btnGuardar» → el('btn_guardar'), «e» para TODOS los botones, «@screen:…» la pantalla).
         */
        fun styleStmts(body: String, target: (String) -> String?, startupText: Boolean): List<String> {
            // (las variables de color se buscan primero en ESTA función: con un proyecto grande, otro archivo puede tener
            //  una variable con el mismo nombre, como «fill», y se tomaba esa: los botones quedaban azules)
            val prevScope = scopeNow
            scopeNow = body
            try { return styleStmtsIn(body, target, startupText) } finally { scopeNow = prevScope }
        }

        private fun styleStmtsIn(body: String, target: (String) -> String?, startupText: Boolean): List<String> {
            val out = mutableListOf<String>()
            val refRx = "(window\\.decorView|(?:binding\\.)?root(?:View)?|findViewById(?:<[^>]+>)?\\(\\s*(?:android\\.)?R\\.id\\.\\w+\\s*\\)|(?:binding\\.)?[a-zA-Z_]\\w*)"
            val rx = Regex("$refRx\\s*\\??\\.(setBackgroundColor|setTextColor|setCardBackgroundColor|setBackgroundTintList|backgroundTintList|background|cornerRadius|strokeWidth|strokeColor|isAllCaps|text|shapeAppearanceModel|setBackgroundResource|setHintTextColor|imageTintList|setImageTintList|setColorFilter|iconTint|setIconTint)\\b\\s*(=(?!=)|\\()")
            for (m in rx.findAll(body)) {
                val ref = m.groupValues[1]
                if (ref in setOf("this", "it", "view", "v") && target(ref) == null) continue
                val t = target(ref) ?: continue
                val prop = m.groupValues[2]
                val arg = if (m.groupValues[3] == "(") parens(body, m.range.last) ?: continue
                    else body.substring(m.range.last + 1).let { rest ->
                        if (Regex("^\\s*(?:[\\w.]+\\.)?GradientDrawable|^\\s*object\\s*:").containsMatchIn(rest)) {
                            val ob = rest.indexOf('{').takeIf { it in 0..120 }
                            if (ob != null) rest.substring(0, ob) + "{" + (block(rest, ob) ?: "") + "}" else rest.substringBefore('\n')
                        } else rest.substringBefore('\n').substringBefore(';')
                    }
                fun put(js: String) { out += js }
                if (t.startsWith("@screen:")) {
                    if (prop == "setBackgroundColor") colorJs(arg)?.let { put("pantalla('${t.removePrefix("@screen:")}',$it);") }
                    continue
                }
                when (prop) {
                    "setBackgroundColor", "setCardBackgroundColor", "setBackgroundTintList", "backgroundTintList" -> colorJs(arg)?.let { put("fondo($t,$it);") }
                    // (un color de colors.xml como fondo: setBackgroundResource(R.color.naranjo))
                    "setBackgroundResource" -> if (arg.contains("R.color.")) colorJs(arg)?.let { put("fondo($t,$it);") }
                    "setHintTextColor" -> colorJs(arg)?.let { put("pista($t,$it);") }
                    "imageTintList", "setImageTintList", "setColorFilter", "iconTint", "setIconTint" -> colorJs(topSplit(arg).firstOrNull() ?: arg)?.let { put("tinte($t,$it);") }
                    "setTextColor" -> colorJs(arg)?.let { put("letra($t,$it);") }
                    // (un fondo dibujado por tu código —object : Drawable()— con su pincel de un color: ese color de fondo)
                    "background" -> if (Regex("^\\s*object\\s*:").containsMatchIn(arg)) {
                        Regex("\\bcolor\\s*=\\s*([^;}\\n]+)").find(arg)?.groupValues?.get(1)?.let { colorJs(it) }?.let { put("fondo($t,$it);") }
                        // (y su forma si la dibuja con líneas: moveTo/lineTo con el ancho «w» y el alto «h» → la misma figura)
                        val pts = Regex("\\b(?:moveTo|lineTo)\\(([^()]*(?:\\([^()]*\\))?[^()]*)\\)").findAll(arg).map { topSplit(it.groupValues[1]) }.toList()
                        fun coord(x0: String, size: String): String? {
                            val x = x0.trim().removeSuffix("f")
                            fun num(v: String): Int? = v.trim().toFloatOrNull()?.toInt()
                                ?: Regex("\\bval\\s+${Regex.escape(v.trim())}\\s*=\\s*([^\\n;]+)").find(arg)?.groupValues?.get(1)?.let { dpOf(it) }
                            return when {
                                x == "0" || x == "0." -> "0"
                                x == size -> "100%"
                                Regex("^$size\\s*-\\s*(.+)$").find(x) != null -> num(Regex("^$size\\s*-\\s*(.+)$").find(x)!!.groupValues[1])?.let { "calc(100% - ${it}px)" }
                                else -> num(x)?.let { "${it}px" }
                            }
                        }
                        val poly = pts.mapNotNull { p -> if (p.size == 2) coord(p[0], "w")?.let { cx -> coord(p[1], "h")?.let { cy -> "$cx $cy" } } else null }
                        if (poly.size >= 3 && poly.size == pts.size) put("recorte($t,'polygon(${poly.joinToString(",")})');")
                    } else if (arg.contains("GradientDrawable")) {
                        val r = Regex("cornerRadius\\s*=\\s*([^\\n;]+)").find(arg)?.groupValues?.get(1)?.let { dpOf(it) }
                        val fill = Regex("setColor\\(").find(arg)?.let { mm -> parens(arg, mm.range.last) }?.let { colorJs(it) }
                        val stroke = Regex("setStroke\\(").find(arg)?.let { mm -> parens(arg, mm.range.last) }?.let { topSplit(it) }
                        val sw = stroke?.getOrNull(0)?.let { dpOf(it) }; val sc = stroke?.getOrNull(1)?.let { colorJs(it) }
                        val grad = Regex("colors\\s*=\\s*intArrayOf\\(").find(arg)?.let { mm -> parens(arg, mm.range.last) }
                            ?.let { topSplit(it).mapNotNull { x -> colorJs(x) } }?.takeIf { it.size >= 2 }
                        if (grad != null) put("grad($t,${grad[0]},${grad[1]});")
                        if (r != null || fill != null || sw != null) put("forma($t,${r ?: "null"},${fill ?: "null"},${sw ?: "null"},${sc ?: "null"});")
                    }
                    "cornerRadius" -> dpOf(arg)?.let { put("forma($t,$it,null,null,null);") }
                    "strokeWidth" -> dpOf(arg)?.let { put("borde($t,$it,null);") }
                    "strokeColor" -> colorJs(arg)?.let { put("borde($t,null,$it);") }
                    "isAllCaps" -> put("mayus($t,${arg.trim().startsWith("true")});")
                    "shapeAppearanceModel" -> {
                        val near = body.substring(m.range.last, minOf(body.length, m.range.last + 400))
                        Regex("setAllCorner(?:Size)?s?\\(").find(near)?.let { mm ->
                            val a = parens(near, mm.range.last) ?: ""
                            (if (Regex("/\\s*2(?:\\.0)?f?\\b|50\\s*%|RelativeCornerSize\\(\\s*0?\\.5").containsMatchIn(a)) 999 else dpOf(a))?.let { put("forma($t,$it,null,null,null);") }
                        }
                    }
                    "text" -> if (startupText && t.startsWith("el('")) Regex("^\\s*\"((?:[^\"\\\\$]|\\\\.)*)\"\\s*$").find(arg)?.let { mm ->
                        val id = t.removePrefix("el('").substringBefore("'")
                        put("texto('$id', \"" + mm.groupValues[1].replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…") + "\");")
                    }
                }
            }
            return out
        }

        /**
         * 🎨 «A todos los botones…»: tu Kotlin recorre la pantalla y a cada MaterialButton (o TextView, ImageButton…)
         * le pone su estilo (stylePill(b), pintarBoton(it)…). Se hace lo mismo en la maqueta con todos los de ese tipo.
         */
        fun walkers(bodies: List<String>): List<String> {
            val sel = mapOf("MaterialButton" to ".btn", "AppCompatButton" to ".btn", "Button" to ".btn", "ImageButton" to ".ibtn",
                "TextView" to ".txt", "EditText" to ".inp", "TextInputEditText" to ".inp", "MaterialCardView" to ".card", "CardView" to ".card",
                "SwitchMaterial" to ".swr", "SwitchCompat" to ".swr")
            val out = LinkedHashSet<String>()
            val types = "MaterialButton|AppCompatButton|Button|ImageButton|TextView|EditText|TextInputEditText|MaterialCardView|CardView|SwitchMaterial|SwitchCompat"
            val rx = Regex("(?:\\b(\\w+)[ \\t]+is[ \\t]+(?:[\\w.]+\\.)?($types)\\b[ \\t]*\\)?|^\\s*is\\s+(?:[\\w.]+\\.)?($types)\\s*->)", RegexOption.MULTILINE)
            for (b in bodies) for (m in rx.findAll(b)) {
                // («when (view) { is MaterialButton -> stylePill(view) }»: la variable es la del when)
                val v = m.groupValues[1].ifEmpty {
                    Regex("when\\s*\\(\\s*(\\w+)\\s*\\)\\s*\\{").findAll(b.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1) ?: "" }
                if (v.isEmpty()) continue
                val css = sel[m.groupValues[2].ifEmpty { m.groupValues[3] }] ?: continue
                if (m.groupValues[1].isNotEmpty() && !m.value.trimEnd().endsWith(")")) continue   // (solo «if (v is Button) …»)
                // Lo que se hace en ESE caso: el resto de la línea, o su bloque { … }
                val lineEnd = b.indexOf('\n', m.range.last).let { if (it < 0) b.length else it }
                val ob = b.indexOf('{', m.range.last).takeIf { it in (m.range.last + 1)..lineEnd }
                val window = if (ob != null) block(b, ob) ?: continue else b.substring(m.range.last + 1, lineEnd)
                // (solo a algunos: «if (v.currentTextColor == azul) …» se imita comparando el color; otras condiciones no)
                var guard: String? = null
                val head = b.substring(m.range.last + 1, lineEnd)
                Regex("^\\s*if\\s*\\(").find(head)?.let { im ->
                    val cond = parens(head, im.range.last) ?: ""
                    val parts = cond.split("&&").map { it.trim() }
                    val col = parts.firstNotNullOfOrNull { Regex("^${Regex.escape(v)}\\.currentTextColor\\s*==\\s*(.+)$").find(it)?.groupValues?.get(1) }?.let { colorJs(it) }
                    val known = parts.all { Regex("^${Regex.escape(v)}\\s*!?is\\s+[\\w.]+$").matches(it) || it.contains(".currentTextColor") }
                    if (col != null && known) guard = col
                }
                if (guard == null && (Regex("^\\s*(if|when)\\b").containsMatchIn(window) || Regex("^\\s*(if|when)\\b").containsMatchIn(head))) continue   // (solo a algunos: no se adivina a cuáles)
                val stmts = mutableListOf<String>()
                stmts += styleStmts(window, { ref -> if (ref == v) "e" else null }, startupText = false)
                for (cm in Regex("\\b([a-z]\\w*)\\s*\\(\\s*${Regex.escape(v)}\\s*[,)]").findAll(window)) {
                    val f = funs[cm.groupValues[1]] ?: continue
                    // (si la función sale antes en algunos casos «if (…) return», no se sabe a cuáles: no se imita)
                    if (Regex("^\\s*if\\s*\\(.*\\)\\s*return\\b", RegexOption.MULTILINE).containsMatchIn(f)) continue
                    val p = Regex("\\bfun\\s+${Regex.escape(cm.groupValues[1])}\\s*\\(\\s*(\\w+)\\s*:").find(all)?.groupValues?.get(1) ?: continue
                    stmts += styleStmts(f, { ref -> if (ref == p) "e" else null }, startupText = false)
                }
                if (stmts.isNotEmpty()) out += if (guard != null) "todos('$css',function(e){ if(_igual(e,$guard)){ ${stmts.distinct().joinToString(" ")} } });"
                    else "todos('$css',function(e){ ${stmts.distinct().joinToString(" ")} });"
            }
            return out.toList()
        }

        /**
         * ✨ «Todo lo blanco con degradado»: tu Kotlin le pone a los textos un pincel de dos colores
         * (paint.shader = LinearGradient(…, celeste, magenta, …)) cuando son blancos, y a veces también a los íconos
         * (una clase que dibuja el ícono con ese degradado). En la maqueta: degradado(color1, color2, {noche, iconos}).
         */
        fun gradientText(): String? {
            val rx = Regex("\\b\\w+\\.paint\\.shader\\s*=\\s*(?:android\\.graphics\\.)?LinearGradient\\s*\\(")
            for (m in rx.findAll(all)) {
                val args = topSplit(parens(all, m.range.last) ?: continue)
                if (args.size < 7) continue   // (x0, y0, x1, y1, color1, color2, modo)
                val c1 = colorJs(args[4]) ?: continue
                val c2 = colorJs(args[5]) ?: continue
                // Lo que hay antes en esa función: ¿solo a lo blanco? ¿solo en modo oscuro?
                val ctx = all.substring(maxOf(0, all.lastIndexOf("fun ", m.range.first)), m.range.first)
                if (!Regex("isWhite|Color\\.WHITE|esBlanco|isBlanco").containsMatchIn(ctx)) continue
                val night = Regex("!\\s*(?:\\w+\\.)?(?:light|isLight\\w*|claro|modoClaro)\\b|\\b(?:isDark|dark|night|noche|oscuro)\\w*\\b", RegexOption.IGNORE_CASE).containsMatchIn(ctx)
                val icons = Regex("class\\s+\\w+\\s*\\([^)]*\\)\\s*:\\s*(?:android\\.graphics\\.drawable\\.)?Drawable\\s*\\(").containsMatchIn(all) &&
                    Regex("(?<!paint)\\.shader\\s*=\\s*(?:android\\.graphics\\.)?LinearGradient").containsMatchIn(all)
                return "degradado($c1,$c2,{noche:$night,iconos:$icons});"
            }
            return null
        }

        /** Un texto de Kotlin: "…" escrito tal cual, o la variable que lo tiene (val saludo get() = traducir("¡Hola!")). */
        private fun textOf(v0: String, depth: Int = 0): String? {
            if (depth > 3) return null
            val v = v0.trim()
            fun clean(t: String) = t.replace("\\\"", "\"").replace("\\'", "'").replace("\\n", " ")
            Regex("^\"((?:[^\"\\\\$]|\\\\.)*)\"$").find(v)?.let { return clean(it.groupValues[1]) }
            if (Regex("^[a-zA-Z_][\\w.]*$").matches(v)) {
                val vv = valueOf(v.substringAfterLast('.'), all) ?: return null
                lit.find(vv)?.groupValues?.get(1)?.takeIf { !it.contains('$') }?.let { return clean(it) }
            }
            return null
        }

        /**
         * 🧾 Las filas que tu código pone en una lista al abrir (la que usa su Adapter): lista.add(Mensaje(text = "¡Hola!",
         * sender = "Cumulus")). En la maqueta, la lista muestra esas filas en vez de las de ejemplo («Sender», «Message content»).
         */
        fun startupRows(bodies: List<String>): String? {
            val rows = mutableListOf<Map<String, String>>()
            for (b in bodies) {
                for (m in Regex("\\b(\\w+)\\.add\\(\\s*([A-Z]\\w*)\\s*\\(").findAll(b)) {
                    val list = m.groupValues[1]; val cls = m.groupValues[2]
                    if (!Regex("Adapter\\s*\\([^)]*?\\b${Regex.escape(list)}\\b").containsMatchIn(all)) continue
                    val args = topSplit(parens(b, m.range.last) ?: continue)
                    val params = Regex("class\\s+${Regex.escape(cls)}\\s*\\(").find(all)?.let { pm -> parens(all, pm.range.last) }
                        ?.let { p -> topSplit(p).map { Regex("(\\w+)\\s*:").find(it)?.groupValues?.get(1) ?: "" } } ?: emptyList()
                    val row = LinkedHashMap<String, String>()
                    args.forEachIndexed { i, a ->
                        val named = Regex("^(\\w+)\\s*=\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(a.trim())
                        val key = named?.groupValues?.get(1) ?: params.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                        textOf(named?.groupValues?.get(2) ?: a)?.let { row[key] = it }
                    }
                    if (row.isNotEmpty()) rows += row
                    if (rows.size >= 3) break
                }
                if (rows.isNotEmpty()) break   // (las de la primera parte que llena la lista)
            }
            if (rows.isEmpty()) return null
            return "filas([" + rows.joinToString(",") { r -> r.entries.joinToString(",", "{", "}") { (k, v) -> jsq(k) + ":" + jsq(v) } } + "]);"
        }

        /**
         * 🖼️ Una imagen que tu código crea y agrega al abrir: val img = ImageView(this) … fila.addView(img, …) con
         * setImageResource(R.drawable.logo). En la maqueta aparece en su lugar (al lado del elemento que indica tu código).
         */
        fun addedImages(bodies: List<String>): List<String> {
            val out = mutableListOf<String>()
            val declRx = Regex("(?:\\b(?:val|var)\\s+)?\\b(\\w+)\\s*=\\s*(?:[\\w.]+\\.)?(?:ImageView|ImageButton|AppCompatImageView|AppCompatImageButton|ShapeableImageView)\\s*\\(")
            for (b in bodies) for (m in declRx.findAll(b)) {
                val v = m.groupValues[1]; val ve = Regex.escape(v)
                val add = Regex("\\b(\\w+)\\??\\.addView\\(\\s*$ve\\b\\s*(?:,\\s*([^)\\n]+?))?\\s*\\)").find(b) ?: continue
                val parent = add.groupValues[1]; val idx = add.groupValues[2]
                // El id de una variable: «val boton = findViewById(R.id.x)» aquí, o en su clase (holder.btnShare → R.id.btnShareMessage)
                fun idOfVal(name0: String): String? {
                    val name = Regex.escape(name0.substringAfterLast('.'))
                    val rx = Regex("\\b(?:val|var)\\s+$name\\s*(?::\\s*[\\w.<>?]+)?\\s*=\\s*[\\w.]*findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)")
                    return rx.find(b)?.groupValues?.get(1) ?: rx.find(all)?.groupValues?.get(1)
                }
                val child = Regex("\\bval\\s+${Regex.escape(parent)}\\s*=\\s*([\\w.]+?)\\??\\.parent\\b").find(b)?.groupValues?.get(1)
                val anchor: String; val pos: String
                if (child != null) {
                    anchor = idOfVal(child) ?: continue
                    val ce = Regex.escape(child.substringAfterLast('.'))
                    pos = when {
                        idx.isBlank() -> "fin"
                        Regex("indexOfChild\\(\\s*[\\w.]*$ce\\s*\\)\\s*\\+\\s*1").containsMatchIn(b) -> "despues"
                        Regex("indexOfChild\\(\\s*[\\w.]*$ce\\s*\\)").containsMatchIn(b) -> "antes"
                        idx.trim() == "0" -> "inicio"
                        else -> "fin"
                    }
                } else { anchor = idOfVal(parent) ?: continue; pos = if (idx.trim() == "0") "dentro0" else "dentro" }
                val seg = b.substring(m.range.first)
                val applyBlock = seg.indexOf('{').takeIf { it in 0..80 }?.let { block(seg, it) } ?: ""
                // Su imagen: en la misma parte, o donde tu código la usa después (topAvatar = img … img.setImageResource(…))
                val imgRx = Regex("setImageResource\\(\\s*R\\.(?:drawable|mipmap)\\.(\\w+)")
                var res = Regex("$ve\\??\\.setImageResource\\(\\s*R\\.(?:drawable|mipmap)\\.(\\w+)").find(seg)?.groupValues?.get(1)
                    ?: imgRx.find(applyBlock)?.groupValues?.get(1)
                if (res == null) {
                    val prop = Regex("(?<![\\w.])(\\w+)\\s*=\\s*$ve\\b").findAll(b).map { it.groupValues[1] }.firstOrNull { it != v }
                    if (prop != null) res = funs.values.firstOrNull { Regex("=\\s*${Regex.escape(prop)}\\b").containsMatchIn(it) && imgRx.containsMatchIn(it) }
                        ?.let { imgRx.find(it)?.groupValues?.get(1) }
                }
                // Sin imagen de tu proyecto (un ícono dibujado por código): se usa un ícono parecido según su descripción
                val desc = Regex("contentDescription\\s*=\\s*${lit.pattern}").find(applyBlock)?.groupValues?.get(1).orEmpty() +
                    " " + Regex("\\btag\\s*=\\s*${lit.pattern}").find(applyBlock)?.groupValues?.get(1).orEmpty()
                val glyph = if (res != null) null else {
                    val d = desc.lowercase(Locale.ROOT)
                    when {
                        Regex("report|reportar|flag|bandera|denunciar").containsMatchIn(d) -> "⚑"
                        Regex("download|descarg").containsMatchIn(d) -> "⤓"
                        Regex("share|compart").containsMatchIn(d) -> "↗"
                        Regex("copy|copiar").containsMatchIn(d) -> "⧉"
                        Regex("delete|borrar|eliminar").containsMatchIn(d) -> "🗑"
                        Regex("edit|editar").containsMatchIn(d) -> "✎"
                        Regex("like|favorit|me gusta").containsMatchIn(d) -> "♡"
                        else -> null
                    }
                }
                if (res == null && glyph == null) continue
                // Tamaño (LayoutParams(48 dp, 48 dp)), margen, esquinas redondeadas y color del ícono (imageTintList)
                val lp = Regex("LayoutParams\\(").find(seg.take(1500))?.let { lm -> parens(seg, lm.range.last) }?.let { topSplit(it) }
                val w = lp?.getOrNull(0)?.let { dpOf(it) }; val h = lp?.getOrNull(1)?.let { dpOf(it) }
                val ml = Regex("marginStart\\s*=\\s*([^\\n;}]+)").find(seg.take(1500))?.groupValues?.get(1)?.let { dpOf(it) }
                val r = Regex("setRoundRect\\(").find(seg.take(1500))?.let { rm -> parens(seg, rm.range.last) }?.let { topSplit(it).lastOrNull() }?.let { dpOf(it) }
                    ?: Regex("cornerRadius\\s*=\\s*([^\\n;]+)").find(applyBlock)?.groupValues?.get(1)?.let { dpOf(it) }
                val tint = Regex("imageTintList\\s*=\\s*([^\\n]+)").find(applyBlock)?.groupValues?.get(1)?.let { colorJs(it) }
                    ?: Regex("$ve\\??\\.setImageDrawable\\(").find(seg)?.let { dm -> parens(seg, dm.range.last) }
                        ?.let { Regex("Color\\.parseColor\\(").find(it)?.let { pm -> parens(it, pm.range.last) }?.let { a -> colorJs("Color.parseColor($a)") } }
                // (si tu código quita el espacio que había al lado, la maqueta también)
                val dropSpace = Regex("removeView\\(").containsMatchIn(b) && Regex("as\\??\\s*(?:[\\w.]+\\.)?Space\\b").containsMatchIn(b)
                out += "vistaImg('$anchor','$pos',${res?.let { "'$it'" } ?: "null"},${w ?: "null"},${h ?: "null"},${r ?: "null"},$dropSpace,${tint ?: "null"}," +
                    "${glyph?.let { "'$it'" } ?: "null"},${ml ?: "null"});"
            }
            return out.distinct()
        }

        /**
         * 👁️ Lo que el Adapter de tu lista hace visible en cada fila (holder.avatar.visibility = View.VISIBLE) aunque en el
         * diseño esté escondido (android:visibility="gone"): los ids de esos elementos.
         */
        fun shownInRows(): List<String> {
            val out = LinkedHashSet<String>()
            val empty = Regex("isEmpty|isNotEmpty|isBlank|isNotBlank|isNullOrEmpty|isNullOrBlank|==\\s*null|!=\\s*null|\\.size\\b|\\.length\\b")
            for (m in Regex("\\bholder\\.(\\w+)\\.(?:visibility\\s*=\\s*View\\.VISIBLE|isVisible\\s*=\\s*true)").findAll(allCode)) {
                val f = Regex.escape(m.groupValues[1])
                // Solo lo que se ve en una fila normal de la app (como el saludo): no lo que depende de que haya datos
                // («si no hay adjuntos / código: se esconde y return»; «si tiene tokens: se muestra»)
                val before = allCode.substring(maxOf(0, m.range.first - 500), m.range.first)
                val guard = Regex("if\\s*\\(([^\\n]*)\\)\\s*\\{[^{}]*\\bholder\\.$f\\.visibility\\s*=\\s*View\\.GONE[^{}]*\\breturn\\b[^{}]*\\}").findAll(before).lastOrNull()
                if (guard != null && empty.containsMatchIn(guard.groupValues[1])) continue
                val lastIf = Regex("(?m)^\\s*(\\}\\s*else\\s*\\{|(?:\\}\\s*else\\s+)?if\\s*\\((.*)\\)\\s*\\{)\\s*$").findAll(before).lastOrNull()
                if (lastIf != null) {
                    val closed = before.substring(lastIf.range.last).count { it == '}' } > before.substring(lastIf.range.last).count { it == '{' }
                    if (!closed && lastIf.groupValues[2].isNotEmpty() && empty.containsMatchIn(lastIf.groupValues[2])) continue
                }
                Regex("\\b(?:val|var)\\s+$f\\s*(?::\\s*[\\w.<>?]+)?\\s*=\\s*[\\w.]*findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)").find(all)?.let { out += it.groupValues[1] }
            }
            return out.take(8)
        }

        /**
         * 🔁 Un botón que cambia de ícono cada vez que lo tocas: «if (sonando) { …play } else { …pause }» (▶ ⇄ ⏸). En la
         * maqueta: alternar('id', íconoA, avisoA, íconoB, avisoB); el primer toque hace lo del «else» (lo de antes de tocar).
         */
        fun toggles(body: String, idOf: (String) -> String?): List<String> {
            val m = Regex("^\\s*if\\s*\\(([^\\n]*)\\)\\s*\\{").find(body) ?: return emptyList()
            val a = block(body, body.indexOf('{', m.range.first)) ?: return emptyList()
            val rest = body.substring(body.indexOf('{', m.range.first) + a.length + 2)
            val em = Regex("^\\s*else\\s*\\{").find(rest) ?: return emptyList()
            val b = block(rest, rest.indexOf('{', em.range.first)) ?: return emptyList()
            val setRx = Regex("([\\w.]+)\\??\\.(?:setImageResource|setImageDrawable)\\(")
            fun branch(x: String): Triple<String, String, String?>? {
                val sm = setRx.find(x) ?: return null
                val id = idOf(sm.groupValues[1].substringAfterLast("binding.")) ?: return null
                val icon = Regex("drawable\\.(\\w+)").find(x)?.groupValues?.get(1) ?: return null
                val toast = Regex("(?:[Tt]oast\\w*\\(|makeText\\()[^\"\\n]*${lit.pattern}").find(x)?.groupValues?.get(1)
                return Triple(id, icon, toast)
            }
            val ta = branch(a) ?: return emptyList()
            val tb = branch(b) ?: return emptyList()
            if (ta.first != tb.first || ta.second == tb.second) return emptyList()
            return listOf("alternar('${ta.first}','${ta.second}',${ta.third?.let { jsq(it) } ?: "null"},'${tb.second}',${tb.third?.let { jsq(it) } ?: "null"});")
        }

        /** Un número entero de Kotlin: «2», o una constante (PAGE_TTS = 2). */
        private fun intOf(e0: String, depth: Int = 0): Int? {
            val e = e0.trim()
            e.toIntOrNull()?.let { return it }
            if (depth < 3 && Regex("^[A-Za-z_][\\w.]*$").matches(e)) valueOf(e.substringAfterLast('.'), all)?.let { return intOf(it.substringBefore("//"), depth + 1) }
            return null
        }

        /**
         * 📑 Un botón que cambia de página en un ViewFlipper / ViewAnimator («flipper.displayedChild = 2», showNext(),
         * o una función propia showPage(PAGE_TTS) que lo hace). En la maqueta: pagina('id', n). Y lo que esa función
         * esconde o muestra según la página («(boton.parent as View).visibility = if (page == 0) VISIBLE else GONE»).
         */
        fun pages(body: String, idOf: (String) -> String?): List<String> {
            val out = mutableListOf<String>()
            val setRx = Regex("([\\w.]+?)\\??\\.(?:displayedChild\\s*=\\s*([^\\n;]+)|setDisplayedChild\\(([^)\\n]+)\\)|(showNext|showPrevious)\\(\\))")
            fun emit(code: String, params: Map<String, String>) {
                for (m in setRx.findAll(code)) {
                    val id = idOf(m.groupValues[1].substringAfterLast("binding.")) ?: continue
                    val expr = (m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
                    when {
                        m.groupValues[4] == "showNext" -> out += "paginaMas('$id',1);"
                        m.groupValues[4] == "showPrevious" -> out += "paginaMas('$id',-1);"
                        else -> intOf(params[expr] ?: expr)?.let { n ->
                            out += "pagina('$id',$n);"
                            // (lo que se esconde o se muestra según la página)
                            for (vm in Regex("([\\w.]+?)\\??\\.parent\\b[^\\n]*?\\.visibility\\s*=\\s*if\\s*\\(\\s*(\\w+)\\s*(==|!=)\\s*([\\w.]+)\\s*\\)\\s*View\\.VISIBLE\\s*else\\s*View\\.(?:GONE|INVISIBLE)").findAll(code)) {
                                val who = idOf(vm.groupValues[1].substringAfterLast("binding.")) ?: continue
                                val left = intOf(params[vm.groupValues[2]] ?: vm.groupValues[2]) ?: continue
                                val right = intOf(vm.groupValues[4]) ?: continue
                                val on = if (vm.groupValues[3] == "==") left == right else left != right
                                out += "mostrarPadre('$who',$on);"
                            }
                        }
                    }
                }
            }
            emit(body, emptyMap())
            // (en una función propia que recibe la página: showSettingsPage(PAGE_GGUF_DOWNLOAD))
            for (cm in Regex("\\b([a-z]\\w*)\\s*\\(([^()\\n]*)\\)").findAll(body)) {
                val f = funs[cm.groupValues[1]] ?: continue
                if (!setRx.containsMatchIn(f)) continue
                val ps = Regex("\\bfun\\s+${Regex.escape(cm.groupValues[1])}\\s*\\(([^)]*)\\)").find(all)?.groupValues?.get(1)
                    ?.let { topSplit(it).map { p -> p.substringBefore(':').trim() } } ?: emptyList()
                val args = topSplit(cm.groupValues[2])
                emit(f, ps.zip(args).toMap())
            }
            return out.distinct()
        }

        // ------------------------------------------------------------------ 🧱 vistas armadas por código
        private val viewTypes = "LinearLayout|TextView|AppCompatTextView|MaterialTextView|Button|MaterialButton|AppCompatButton|EditText|AppCompatEditText|" +
            "TextInputEditText|ImageView|ImageButton|ScrollView|NestedScrollView|HorizontalScrollView|FrameLayout|RelativeLayout|SwitchCompat|SwitchMaterial|" +
            "Switch|CheckBox|RadioButton|SeekBar|ProgressBar|Space|View|CardView|MaterialCardView"

        /** Una vista que tu código crea: su tipo, su bloque «.apply { … }» (dónde está) y sus hijas. */
        private class VNode(val name: String, val type: String, val start: Int, val bStart: Int, val bEnd: Int, val callText: String?) {
            val kids = mutableListOf<VNode>(); var lp: String = ""; var click: String? = null
        }

        /** Las funciones del proyecto que devuelven una vista ya armada (fun boton(…) = MaterialButton(this).apply { … }): su tipo. */
        private val viewHelpers: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            for (fm in Regex("\\bfun\\s+(\\w+)\\s*\\(").findAll(all)) {
                val ps = parens(all, fm.range.last) ?: continue
                val after = all.substring(fm.range.last + ps.length + 2, minOf(all.length, fm.range.last + ps.length + 200))
                Regex("^\\s*(?::\\s*[\\w.<>?]+\\s*)?=\\s*(?:[\\w.]+\\.)?($viewTypes)\\s*\\(").find(after)?.let { m[fm.groupValues[1]] = it.groupValues[1] }
            }
            m
        }

        /** Un texto que se ve: "…" escrito tal cual (lo variable queda «…»), o el primer texto de una llamada (traducir("▶ Probar")). */
        private fun shownText(e0: String): String? {
            val e = e0.trim().removeSuffix(";")
            if (e.isEmpty()) return null
            Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"$").find(e)?.let { return it.groupValues[1].replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…").replace("\\n", " ") }
            lit.find(e)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it.replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…") }
            return "…"
        }

        /** «self.» delante de lo que se le hace a la vista dentro de su «.apply { }» (solo en su nivel, no en bloques de adentro). */
        private fun selfPrefixed(block: String): String {
            val sb = StringBuilder(); var depth = 0; var i = 0; var atStart = true
            val kw = Regex("^(val|var|if|else|for|when|return|addView|fun|try|run|let|also|apply|this|super|true|false|null)\\b")
            while (i < block.length) {
                val ch = block[i]
                if (atStart && !ch.isWhitespace()) {
                    atStart = false
                    if (depth == 0) {
                        val rest = block.substring(i, minOf(block.length, i + 60))
                        if (!kw.containsMatchIn(rest) && Regex("^(?:set[A-Z]\\w*\\(|[a-z]\\w*\\s*=[^=])").containsMatchIn(rest)) sb.append("self.")
                    }
                }
                when (ch) {
                    '"' -> { val j = i; i++; while (i < block.length && block[i] != '"') { if (block[i] == '\\') i++; i++ }; sb.append(block, j, minOf(block.length, i + 1)); i++; continue }
                    '{', '(' -> depth++
                    '}', ')' -> depth--
                    '\n', ';' -> if (depth == 0) atStart = true
                }
                sb.append(ch); i++
            }
            return sb.toString()
        }

        /** Todas las vistas que crea [f] y quién va dentro de quién (addView). */
        private fun viewTree(f: String): Map<String, VNode> {
            val nodes = LinkedHashMap<String, VNode>()
            var anon = 0
            fun applyAfter(pos: Int): Pair<Int, Int>? {
                val rest = f.substring(pos)
                // (después del constructor: «.apply {» — o «{ … }.apply {» de una función propia con lambda)
                val am = Regex("^\\s*(?:\\{[^{}]*\\}\\s*)?\\.apply\\s*\\{").find(rest) ?: return null
                val open = pos + am.range.last
                val inner = block(f, open) ?: return null
                return (open + 1) to (open + 1 + inner.length)
            }
            // Constructores de vistas: «val x = TextView(this).apply { … }» o sin nombre adentro de addView(…)
            for (m in Regex("(?:\\b(?:val|var)\\s+(\\w+)\\s*(?::\\s*[\\w.<>?]+)?\\s*=\\s*)?(?<![\\w])(?:android\\.widget\\.|android\\.view\\.|com\\.google\\.android\\.material\\.\\w+\\.|androidx\\.[\\w.]+\\.)?($viewTypes)\\s*\\(").findAll(f)) {
                val close = parens(f, m.range.last)?.let { m.range.last + it.length + 1 } ?: continue
                val args = f.substring(m.range.last + 1, close)
                if (args.isNotBlank() && !Regex("^\\s*(this(@\\w+)?|context|ctx|requireContext\\(\\)|activity|\\w*[Cc]ontext\\w*|it)\\s*(,.*)?$", RegexOption.DOT_MATCHES_ALL).matches(args)) continue
                val span = applyAfter(close + 1)
                val name = m.groupValues[1].ifEmpty { "#" + (anon++) }
                nodes[name] = VNode(name, m.groupValues[2], m.range.first, span?.first ?: -1, span?.second ?: -1, null)
            }
            // Funciones propias que devuelven una vista (buttonLike(src, "EDITAR") { … })
            for ((h, type) in viewHelpers) for (m in Regex("(?:\\b(?:val|var)\\s+(\\w+)\\s*=\\s*)?(?<![\\w.])${Regex.escape(h)}\\s*\\(").findAll(f)) {
                if (Regex("\\bfun\\s+$").containsMatchIn(f.substring(maxOf(0, m.range.first - 8), m.range.first))) continue
                val args = parens(f, m.range.last) ?: continue
                var end = m.range.last + args.length + 2
                val lam = Regex("^\\s*\\{").find(f.substring(end))?.let { lm -> block(f, end + lm.range.last) }
                if (lam != null) end = f.indexOf('{', end) + lam.length + 2
                val span = applyAfter(end)
                val name = m.groupValues[1].ifEmpty { "#" + (anon++) }
                nodes[name] = VNode(name, type, m.range.first, span?.first ?: -1, span?.second ?: -1, args).also { it.click = lam }
            }
            // Quién va dentro de quién
            for (m in Regex("(?:\\b(\\w+)\\??\\.)?\\baddView\\(").findAll(f)) {
                val args = parens(f, m.range.last) ?: continue
                val parts = topSplit(args)
                val childExpr = parts.firstOrNull() ?: continue
                val argStart = m.range.last + 1
                val child = nodes[childExpr.trim()] ?: nodes.values.filter { it.start in argStart until argStart + args.length }.minByOrNull { it.start } ?: continue
                val parent = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { nodes[it] }
                    ?: nodes.values.filter { it.bStart in 0..m.range.first && m.range.first < it.bEnd && it !== child }.maxByOrNull { it.bStart } ?: continue
                if (child === parent || parent.kids.contains(child)) continue
                child.lp = parts.getOrNull(1).orEmpty()
                parent.kids += child
            }
            return nodes
        }

        /** Una vista armada por código como objeto para maqueta.js: {t, x (texto), h (hint), s (tamaño), b, row, p, k (hijas)…}. */
        private fun nodeJs(n: VNode, f: String, depth: Int = 0): String {
            if (depth > 12) return "{t:'View'}"
            val inner = if (n.bStart >= 0) f.substring(n.bStart, n.bEnd) else ""
            // (lo que se le hace fuera de su «apply»: «titulo.text = …», «caja.setPadding(…)»)
            val outside = if (n.name.startsWith("#")) "" else Regex("(?m)^\\s*${Regex.escape(n.name)}\\??\\.(.+)$").findAll(f).joinToString("\n") { "self." + it.groupValues[1] }
            val code = selfPrefixed(inner) + "\n" + outside
            val o = LinkedHashMap<String, String>()
            o["t"] = "'${n.type}'"
            val textExpr = Regex("\\bself\\.(?:text\\s*=\\s*([^\\n]+)|setText\\(([^\\n]+)\\))").find(code)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].removeSuffix(")") } }
            val text = textExpr?.let { shownText(it) } ?: n.callText?.let { a -> lit.find(a)?.groupValues?.get(1) }
            text?.let { o["x"] = jsq(it) }
            Regex("\\bself\\.hint\\s*=\\s*([^\\n]+)").find(code)?.let { shownText(it.groupValues[1]) }?.takeIf { it != "…" }?.let { o["h"] = jsq(it) }
            Regex("\\bself\\.textSize\\s*=\\s*([\\d.]+)").find(code)?.groupValues?.get(1)?.toFloatOrNull()?.let { o["s"] = it.toInt().toString() }
            if (Regex("\\bself\\.(?:typeface\\s*=[^\\n]*BOLD|setTypeface\\([^\\n]*BOLD)").containsMatchIn(code)) o["b"] = "1"
            if (Regex("\\bself\\.orientation\\s*=\\s*[\\w.]*HORIZONTAL").containsMatchIn(code)) o["row"] = "1"
            Regex("\\bself\\.gravity\\s*=\\s*([^\\n]+)").find(code)?.groupValues?.get(1)?.let { g -> if (g.contains("CENTER") && !g.contains("CENTER_VERTICAL")) o["g"] = "1" else if (g.contains("CENTER_VERTICAL")) o["gv"] = "1" }
            Regex("\\bself\\.setPadding\\(").find(code)?.let { pm -> parens(code, pm.range.last) }?.let { topSplit(it).map { a -> dpOf(a) } }
                ?.takeIf { it.size == 4 }?.let { ps -> o["p"] = ps.joinToString(",", "[", "]") { (it ?: 0).toString() } }
            if (Regex("\\bself\\.visibility\\s*=\\s*View\\.GONE").containsMatchIn(code)) o["hide"] = "1"
            if (Regex("MULTI_LINE|isSingleLine\\s*=\\s*false|minLines\\s*=\\s*[2-9]").containsMatchIn(code)) o["ta"] = "1"
            // Medidas: LayoutParams(ancho, alto[, peso]) en addView(…) o en layoutParams = …
            val lpExpr = n.lp.ifBlank { Regex("\\bself\\.layoutParams\\s*=\\s*([^\\n]+)").find(code)?.groupValues?.get(1).orEmpty() }
            Regex("LayoutParams\\(").find(lpExpr)?.let { lm -> parens(lpExpr, lm.range.last) }?.let { topSplit(it) }?.let { a ->
                fun size(v0: String?): String? {
                  // (una variable de esta parte: «val tabH = (32 * d).toInt()»)
                  val v = v0?.trim()?.let { t -> if (Regex("^\\w+$").matches(t) && !t.matches(Regex("\\d+"))) Regex("\\bval\\s+$t\\s*=\\s*([^\\n]+)").find(f)?.groupValues?.get(1) ?: t else t }
                  return when {
                    v == null -> null
                    v.contains("MATCH_PARENT") || v.trim() == "-1" -> "'f'"
                    v.contains("WRAP_CONTENT") || v.trim() == "-2" -> null
                    v.trim() == "0" -> "0"
                    else -> dpOf(v)?.toString()
                  }
                }
                size(a.getOrNull(0))?.let { o["w"] = it }; size(a.getOrNull(1))?.let { o["hh"] = it }
                a.getOrNull(2)?.trim()?.removeSuffix("f")?.toFloatOrNull()?.let { o["wt"] = it.toString() }
            }
            fun localDp(x: String): Int? = dpOf(x.trim().let { t -> if (Regex("^\\w+$").matches(t)) Regex("\\bval\\s+$t\\s*=\\s*([^\\n]+)").find(f)?.groupValues?.get(1) ?: t else t })
            Regex("marginStart\\s*=\\s*([^;}\\n]+)").find(lpExpr)?.groupValues?.get(1)?.let { localDp(it) }?.let { o["ml"] = it.toString() }
            Regex("marginEnd\\s*=\\s*([^;}\\n]+)").find(lpExpr)?.groupValues?.get(1)?.let { localDp(it) }?.let { o["mr"] = it.toString() }
            Regex("topMargin\\s*=\\s*([^;}\\n]+)").find(lpExpr)?.groupValues?.get(1)?.let { localDp(it) }?.let { o["mt"] = it.toString() }
            // Colores, fondos redondeados, bordes… (lo mismo que ya se imita en las pantallas)
            val st0 = styleStmts(code, { ref -> if (ref == "self") "e" else null }, startupText = false)
            // (y lo que le hacen tus funciones a las que se la pasas: styleTopUsageView(tv) → su fondo y su color de letra)
            val viaFuns = if (n.name.startsWith("#")) emptyList() else Regex("\\b([a-z]\\w*)\\s*\\(\\s*${Regex.escape(n.name)}\\s*[,)]").findAll(f).mapNotNull { cm ->
                val fb = funs[cm.groupValues[1]] ?: return@mapNotNull null
                val p = Regex("\\bfun\\s+${Regex.escape(cm.groupValues[1])}\\s*\\(\\s*(\\w+)\\s*:").find(all)?.groupValues?.get(1) ?: return@mapNotNull null
                styleStmts(fb, { ref -> if (ref == p) "e" else null }, startupText = false)
            }.flatten().toList()
            val st = (st0 + viaFuns).distinct()
            if (st.isNotEmpty()) o["st"] = "function(e){ ${st.joinToString(" ")} }"
            // Al tocarla abre otra ventana armada por código (EDITAR → la ventana de editar)
            n.click?.let { lam -> (codeWindow(lam, depth + 1) ?: followedDeep(lam).firstNotNullOfOrNull { codeWindow(it, depth + 1) })?.let { o["clk"] = "function(){ $it }" } }
            if (n.kids.isNotEmpty()) o["k"] = n.kids.joinToString(",", "[", "]") { nodeJs(it, f, depth + 1) }
            return o.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }
        }

        /** Las funciones que llama [body] y las que llaman esas (2 niveles): para encontrar la ventana que abre un botón. */
        fun followedDeep(body: String): List<String> {
            val first = followed(body)
            return (first + first.flatMap { followed(it) }).distinct().take(8)
        }

        /**
         * 🪟 Una ventana que tu código arma vista por vista (Dialog con setContentView(raíz), o AlertDialog con setView(raíz),
         * su título y sus botones): en la maqueta se abre igual, con sus textos, tamaños, colores y formas.
         */
        fun codeWindow(body0: String, depth: Int = 0): String? {
            if (depth > 2) return null
            val f = withoutListeners(body0)
            val root = Regex("\\.setContentView\\(\\s*(\\w+)\\s*\\)").find(f)?.groupValues?.get(1)
                ?: Regex("\\.setView\\(\\s*(\\w+)\\s*\\)").find(f)?.groupValues?.get(1) ?: return null
            val nodes = viewTree(f)
            val r = nodes[root] ?: return null
            if (r.kids.isEmpty() && r.bStart < 0) return null
            val full = Regex("Dialog\\([^)]*(?:Fullscreen|FullScreen|NoActionBar|NoTitleBar)").containsMatchIn(f)
            val sheet = Regex("BottomSheetDialog").containsMatchIn(f)
            val chain = f.substring(maxOf(0, f.indexOf(".setView(") - 400))
            val title = if (f.contains(".setView(")) Regex("\\.setTitle\\(\\s*${lit.pattern}").find(chain)?.groupValues?.get(1) else null
            val btns = if (f.contains(".setView(")) Regex("\\.set(Negative|Neutral|Positive)Button\\(\\s*${lit.pattern}").findAll(chain).map { jsq(it.groupValues[2]) }.toList() else emptyList()
            return "ventanaCodigo(${nodeJs(r, f, depth)},{full:${full && !sheet},hoja:$sheet,titulo:${title?.let { jsq(it) } ?: "null"},botones:${btns.joinToString(",", "[", "]")}});"
        }

        /**
         * 🧱 Lo que tu código arma y agrega a la pantalla al abrir, al lado de algo que ya existe:
         * «val fila = src.parent; val caja = fila.parent; caja.addView(nueva, caja.indexOfChild(fila) + 1)».
         */
        fun insertedViews(bodies: List<String>, idOf: (String) -> String?): List<String> {
            val out = mutableListOf<String>()
            for (b0 in bodies) {
                if (!b0.contains("addView(") || !b0.contains(".parent")) continue
                val b = withoutListeners(b0)
                val nodes = viewTree(b)
                val done = HashSet<VNode>()
                for (m in Regex("\\b(\\w+)\\??\\.addView\\(").findAll(b)) {
                    if (nodes.containsKey(m.groupValues[1])) continue   // (adentro de una vista nueva: ya va con ella)
                    val raw = parens(b, m.range.last) ?: continue
                    val args = topSplit(raw)
                    val node = nodes[args.firstOrNull()?.trim() ?: continue]
                        ?: nodes.values.filter { it.start in (m.range.last + 1)..(m.range.last + raw.length) }.minByOrNull { it.start } ?: continue
                    if (!done.add(node)) continue   // (la misma vista agregada en otra rama: solo la primera)
                    node.lp = args.getOrNull(1)?.takeIf { it.contains("LayoutParams") } ?: args.getOrNull(2).orEmpty()
                    if (node.type == "ImageView" || node.type == "ImageButton") continue   // (de esas se encarga addedImages)
                    // Subir de «padre» en «padre» hasta algo con id: caja = fila.parent, fila = src.parent, src = btnBorrar
                    fun parentOf(v: String) = Regex("\\bval\\s+${Regex.escape(v)}\\s*=\\s*([\\w.]+?)\\??\\.parent\\b").find(b)?.groupValues?.get(1)
                    fun aliasOf(v: String) = Regex("\\bval\\s+${Regex.escape(v)}\\s*=\\s*([\\w.]+?)\\s*(?:\\?:|$|\\n)").find(b)?.groupValues?.get(1)
                    val idx = args.getOrNull(1).orEmpty()
                    val rel = Regex("indexOfChild\\(\\s*(\\w+)\\s*\\)\\s*(\\+\\s*1)?").find(idx)
                    var cur = rel?.groupValues?.get(1) ?: m.groupValues[1]
                    var up = if (rel == null) -1 else 0   // -1 = adentro de esa vista, al final
                    var anchor: String? = null
                    for (step in 0..6) {
                        val local = Regex("\\b(?:val|var)\\s+${Regex.escape(cur)}\\b").containsMatchIn(b)
                        // (una variable de esta parte: su findViewById, su «.parent» o a quién copia; si no, la de la pantalla)
                        val fv = Regex("\\b(?:val|var)\\s+${Regex.escape(cur)}\\s*(?::[^=\\n]+)?=\\s*[\\w.]*findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)").find(b)?.groupValues?.get(1)
                        if (fv != null) { anchor = fv; break }
                        if (!local) { anchor = idOf(cur.substringAfterLast('.')); break }
                        val p = parentOf(cur)
                        if (p != null) { cur = p; up++; continue }
                        val a = aliasOf(cur) ?: break
                        cur = a
                    }
                    val anc = anchor ?: continue
                    val pos = when { rel == null -> "dentro"; rel.groupValues[2].isNotEmpty() -> "despues"; else -> "antes" }
                    out += "insertarVista('$anc',$up,'$pos',${nodeJs(node, b)});"
                }
            }
            return out.distinct()
        }

        /**
         * 📏 Tu código deja varios elementos del mismo alto: una función propia que recibe esas vistas y les pone el alto del
         * más alto (matchHeights(a, b), igualarAlto(…), sameHeight(…)). En la maqueta: igualarAlto(['id1','id2']).
         */
        fun equalHeights(bodies: List<String>, idOf: (String) -> String?): List<String> {
            val out = mutableListOf<String>()
            for (b in bodies) for (m in Regex("\\b(\\w*(?:[Hh]eight|[Aa]lto|[Ss]ize)\\w*)\\s*\\(").findAll(b)) {
                val f = funs[m.groupValues[1]] ?: continue
                if (!Regex("max|maxOf|coerceAtLeast", RegexOption.IGNORE_CASE).containsMatchIn(f) || !Regex("height|minHeight|minimumHeight", RegexOption.IGNORE_CASE).containsMatchIn(f)) continue
                val args = topSplit(parens(b, m.range.last) ?: continue)
                val ids = args.mapNotNull { a -> Regex("^[\\w.]+$").find(a.trim().removeSuffix("!!"))?.value?.let { idOf(it.substringAfterLast("binding.")) } }
                if (ids.size >= 2) out += "igualarAlto(${ids.joinToString(",", "[", "]") { "'$it'" }});"
            }
            return out.distinct()
        }

        /**
         * ✨ Textos pintados con un degradado propio: una clase «Span» que dibuja con LinearGradient(…, color1, color2, …),
         * una función que la pone en el texto (styled(nombre)) y dónde se usa (titulo.text = …styled(…), o attach(titulo)).
         * En la maqueta: degradadoEn('id', color1, color2, soloDeNoche).
         */
        fun gradientSpanUses(): List<String> {
            val out = mutableListOf<String>()
            // 1) Las clases que dibujan el texto con dos colores
            val spans = HashMap<String, Triple<String, String, Boolean>>()
            for (cm in Regex("\\bclass\\s+(\\w+)[^{]*?:\\s*[\\w.]*(?:ReplacementSpan|CharacterStyle|MetricAffectingSpan|UpdateAppearance)\\b[^{]*\\{").findAll(all)) {
                val body = block(all, cm.range.last) ?: continue
                val lg = Regex("LinearGradient\\s*\\(").find(body)?.let { parens(body, it.range.last) }?.let { topSplit(it) } ?: continue
                if (lg.size < 6) continue
                val c1 = colorJs(lg[4]) ?: continue; val c2 = colorJs(lg[5]) ?: continue
                spans[cm.groupValues[1]] = Triple(c1, c2, Regex("\\blight\\b|isLight|claro|night|dark", RegexOption.IGNORE_CASE).containsMatchIn(body))
            }
            if (spans.isEmpty()) return out
            // 2) Las funciones que ponen esa clase en un texto (styled) y las que se la ponen a un TextView (attach)
            val stylers = HashMap<String, Triple<String, String, Boolean>>()
            for ((name, f) in funs) spans.entries.firstOrNull { Regex("setSpan\\(\\s*${Regex.escape(it.key)}\\s*\\(").containsMatchIn(f) }?.let { stylers[name] = it.value }
            val attachers = HashMap<String, Triple<String, String, Boolean>>()
            for ((name, f) in funs) if (name !in stylers) stylers.entries.firstOrNull { Regex("\\.text\\s*=\\s*${Regex.escape(it.key)}\\(").containsMatchIn(f) }?.let { attachers[name] = it.value }
            fun idOfAny(ref: String): String? {
                val n = Regex.escape(ref.substringAfterLast('.'))
                return Regex("\\b$n\\s*(?::\\s*[\\w.<>?]+)?\\s*=\\s*[\\w.]*findViewById(?:<[^>]+>)?\\(\\s*R\\.id\\.(\\w+)").find(all)?.groupValues?.get(1)
            }
            fun emit(ref: String, t: Triple<String, String, Boolean>) { idOfAny(ref)?.let { out += "degradadoEn('$it',${t.first},${t.second},${t.third});" } }
            // 3) Dónde se usan: «titulo.text = …styled(…)» o «attach(titulo)»
            for ((st, t) in stylers) for (m in Regex("([\\w.]+)\\??\\.text\\s*=\\s*[^\\n]*\\b${Regex.escape(st)}\\(").findAll(allCode)) emit(m.groupValues[1], t)
            for ((at, t) in attachers) for (m in Regex("\\b${Regex.escape(at)}\\(\\s*([\\w.]+)").findAll(allCode)) emit(m.groupValues[1], t)
            return out.distinct()
        }

        /** Cuántas veces tu Kotlin cambia el diseño por código (para avisar si la maqueta no alcanzó a imitarlo todo). */
        fun runtimeStyleCount(): Int = Regex("\\.(setBackgroundColor|setTextColor|setBackgroundTintList|setBackgroundResource|setCardBackgroundColor|" +
            "setImageResource|setColorFilter|setTextAppearance|setHintTextColor)\\s*\\(|\\.(backgroundTintList|background|cornerRadius|strokeColor|strokeWidth|shader|typeface|" +
            "imageTintList|textSize)\\s*=(?!=)").findAll(allCode).count()

        /** Las funciones que llama el botón y que muestran algo (showMenu(), abrirDialogo(), mostrarOpciones()…). */
        fun followed(body: String): List<String> = Regex("\\b([a-z]\\w*)\\s*\\(").findAll(body).map { it.groupValues[1] }.distinct()
            .filter { Regex("(?i)menu|dialog|popup|sheet|picker|chooser|option|opcion|select|elegir|escoger|choose|pick|^(show|open|mostrar|abrir|display)[A-Z]").containsMatchIn(it) }
            .mapNotNull { funs[it] }.take(3).toList()

        /** Dónde empieza el valor de cada «val nombre =» de todo el código (se busca una sola vez: rápido aunque sean 100 000 líneas). */
        private val valAt: Map<String, Int> by lazy {
            val map = HashMap<String, Int>()
            Regex("\\b(?:val|var)\\s+(\\w+)\\b[^=\\n]*=\\s*").findAll(all).forEach { m -> map.putIfAbsent(m.groupValues[1], m.range.last + 1) }
            map
        }

        /** Lo que viene después de «val nombre =» (la lista entera si es listOf(…)): primero en la función, después en todo el código. */
        private fun valueOf(name: String, scope: String): String? {
            for (inScope in listOf(true, false)) {
                val rest = if (inScope) Regex("\\b(?:val|var)\\s+$name\\b[^=\\n]*=\\s*").find(scope)?.let { scope.substring(it.range.last + 1) } ?: continue
                    else valAt[name]?.let { all.substring(it, minOf(all.length, it + 20_000)) } ?: continue
                // («val fill = when { … }» de varias líneas: se toma entero, no solo «when {»)
                Regex("^when\\b[^{\\n]*\\{").find(rest)?.let { w -> block(rest, w.range.last)?.let { return rest.substring(0, w.range.last + 1) + it + "}" } }
                val p = Regex("^(?:mutableListOf|listOfNotNull|listOf|arrayOf|setOf)\\s*\\(").find(rest)
                return if (p != null) parens(rest, p.range.last)?.let { "listOf($it)" } ?: rest.substringBefore('\n') else rest.substringBefore('\n')
            }
            return null
        }

        private fun parens(c: String, open: Int): String? {
            var depth = 0; var i = open
            while (i < c.length) {
                when (c[i]) {
                    '"' -> { i++; while (i < c.length && c[i] != '"') { if (c[i] == '\\') i++; i++ } }
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return c.substring(open + 1, i) }
                }
                i++
            }
            return null
        }

        /** Divide «a, b(c, d), e» en sus partes de arriba (sin cortar paréntesis ni textos). */
        private fun topSplit(s: String): List<String> {
            val out = mutableListOf<String>(); var depth = 0; var start = 0; var i = 0
            while (i < s.length) {
                when (s[i]) {
                    '"' -> { i++; while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ } }
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) { out += s.substring(start, i); start = i + 1 }
                }
                i++
            }
            out += s.substring(start)
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }

        private val lit = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        private fun isKey(s: String) = Regex("^[a-z0-9_.-]+$").matches(s)

        /**
         * Las opciones de una lista: "Rápido", "a" to "Rápido" → Rápido, Modo("clave", "Solo chat", …) → Solo chat.
         * Cada una con su clave («clave», si tiene) para poder aplicar los filtros de tu código.
         */
        private fun entriesOf(listBody: String): List<Pair<String?, String>> = topSplit(listBody).mapNotNull { item ->
            val lits = lit.findAll(item).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
            val label = when {
                lits.isEmpty() -> null
                Regex("\"\\s+to\\s+\"").containsMatchIn(item) -> lits.getOrNull(1)
                item.startsWith("\"") && lits.size == 1 -> lits[0]
                else -> lits.firstOrNull { !isKey(it) && it.length <= 40 }
            } ?: return@mapNotNull null
            lits.firstOrNull { isKey(it) && it != label } to label
        }.filter { it.second.length <= 40 }.take(14)

        /**
         * 🧹 Lo que tu código le hace a la lista antes de mostrarla: quita opciones
         * («.filter { it.key !in ocultos }», «.filterNot { it.key == "codigo" }») y la da vuelta («.reversed()»).
         */
        private fun applyListOps(part: String, entries: List<Pair<String?, String>>): List<Pair<String?, String>> {
            var out = entries
            val frx = Regex("\\.(filter|filterNot)\\s*\\{\\s*(?:\\w+\\s*->\\s*)?\\w+(?:\\.\\w+)?\\s*(!in|in|!=|==)\\s*(\\w+|\"[^\"]*\")\\s*\\}")
            for (m in frx.findAll(part)) {
                val ref = m.groupValues[3]
                val set: Set<String> = if (ref.startsWith("\"")) setOf(ref.trim('"'))
                    else (valueOf(ref, all) ?: continue).let { v -> lit.findAll(v).map { it.groupValues[1] }.toSet() }
                if (set.isEmpty()) continue
                val positive = m.groupValues[2] == "in" || m.groupValues[2] == "=="   // (la condición dice «está»)
                val keep = (m.groupValues[1] == "filter") == positive                  // ¿se quedan las que están?
                out = out.filter { e -> ((e.first != null && e.first in set) || e.second in set) == keep }
            }
            if (Regex("\\.(?:reversed|asReversed)\\(\\)").containsMatchIn(part)) out = out.reversed()
            return out
        }

        /** Las opciones de una expresión: una lista escrita ahí mismo o una variable/función que la tiene. */
        fun optionsOf(expr: String, scope: String, depth: Int = 0): List<String> = optionEntries(expr, scope, depth).map { it.second }

        private fun optionEntries(expr: String, scope: String, depth: Int = 0): List<Pair<String?, String>> {
            if (depth > 3) return emptyList()
            Regex("\\b(?:mutableListOf|listOfNotNull|listOf|arrayOf|setOf)\\s*\\(").find(expr)?.let { m ->
                parens(expr, m.range.last)?.let { entriesOf(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val skip = setOf("it", "this", "true", "false", "null", "map", "filter", "filterNot", "reversed", "asReversed", "sorted", "toList", "label", "second", "first",
                "anchor", "above", "checked", "icons", "current", "i", "key", "size", "indexOfFirst", "coerceAtLeast", "widthFrom")
            val parts = topSplit(expr.take(4000))
            for (m in Regex("\\b([a-zA-Z_]\\w*)\\b(\\s*\\()?").findAll(expr.take(4000)).take(40)) {
                val name = m.groupValues[1]
                if (name in skip || name.first().isUpperCase()) continue
                val sub = if (m.groupValues[2].isNotEmpty()) funs[name] else valueOf(name, scope)
                if (sub != null) optionEntries(sub, sub, depth + 1).takeIf { it.isNotEmpty() }?.let { found ->
                    // (los filtros y el orden de ESA parte de la expresión: «availableChatModes().reversed()»)
                    val part = parts.firstOrNull { Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(it) } ?: expr
                    return applyListOps(part.substring(maxOf(0, Regex("\\b${Regex.escape(name)}\\b").find(part)?.range?.first ?: 0)), found)
                }
            }
            return emptyList()
        }

        /** maqueta.js de lo que abre [body]: abrir('dialog_x', 1) o menu([...], "título"); null si no abre nada. */
        fun windowOrMenu(body: String): String? {
            // 🪟 Ventana con su propio diseño XML
            val lay = Regex("(?:inflate|setContentView|setView)\\(\\s*R\\.layout\\.(\\w+)").find(body)?.groupValues?.get(1)
                ?: Regex("\\b(\\w+)Binding\\.inflate").find(body)?.groupValues?.get(1)
                    ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")?.lowercase(Locale.ROOT)?.takeIf { !it.startsWith("activity") }
            if (lay != null && Regex("Dialog|BottomSheet|PopupWindow|AlertDialog|setView|inflate").containsMatchIn(body)) return "abrir('$lay', 1);"
            val title = Regex("\\.setTitle\\(\\s*${lit.pattern}").find(body)?.groupValues?.get(1)
            val opts = mutableListOf<String>()
            // ☰ PopupMenu: menu.add("…") y menús de res/menu
            if (body.contains("PopupMenu")) {
                Regex("\\.add\\(([^()\\n]*?)${lit.pattern}\\s*\\)").findAll(body).forEach { opts += it.groupValues[2] }
                Regex("inflate\\(\\s*R\\.menu\\.(\\w+)").findAll(body).forEach { m -> menuTitles[m.groupValues[1]]?.let { opts += it } }
            }
            // 💬 AlertDialog: opciones (setItems), o su mensaje y botones
            Regex("\\.set(?:Single|Multi)?(?:Choice)?Items\\(").find(body)?.let { m ->
                parens(body, m.range.last)?.let { opts += optionsOf(topSplit(it).firstOrNull() ?: it, body) }
            }
            if (opts.isEmpty() && Regex("AlertDialog|MaterialAlertDialogBuilder").containsMatchIn(body)) {
                Regex("\\.set(?:Positive|Negative|Neutral)Button\\(\\s*${lit.pattern}").findAll(body).forEach { opts += it.groupValues[1] }
                Regex("\\.setMessage\\(\\s*${lit.pattern}").find(body)?.let { m -> return "menu(${opts.joinToString(",", "[", "]") { jsq(it) }}, " +
                    jsq(listOfNotNull(title, m.groupValues[1]).joinToString(" · ")) + ");" }
            }
            // ➕ Funciones propias que ponen una opción con su nombre: addOption(R.drawable.x, "Imagen") { … }
            if (opts.isEmpty()) {
                Regex("\\b(\\w*(?:[Aa]dd|[Oo]ption|[Oo]pcion|[Ii]tem|[Ee]ntry|[Rr]ow|[Aa]ction)\\w*)\\(\\s*(?:[^(),\"\\n]++,\\s*+)*+${lit.pattern}\\s*(?:,[^()\\n]*+)?\\)\\s*\\{")
                    .findAll(body).forEach { opts += it.groupValues[2] }
                if (opts.isNotEmpty()) Regex("Switch\\w*\\([^)]*\\)\\.apply\\s*\\{[^}]{0,300}?\\btext\\s*=\\s*${lit.pattern}").findAll(body)
                    .forEach { opts += "⏻ " + it.groupValues[1] }
            }
            // ⋮ Un menú propio que recibe una lista: showRoundMenu(anchor, opciones.map { … }) → las opciones de esa lista
            if (opts.isEmpty()) for (m in Regex("\\b(\\w*(?:[Mm]enu|[Pp]opup|[Pp]icker|[Cc]hooser|[Ss]heet|[Dd]ialog|[Ss]elector)\\w*)\\s*\\(").findAll(body)) {
                val args = parens(body, m.range.last) ?: continue
                optionsOf(args, body).takeIf { it.isNotEmpty() }?.let { opts += it }
                if (opts.isNotEmpty()) break
            }
            val clean = opts.map { it.replace("\\n", " ").trim() }.filter { it.isNotEmpty() }.distinct().take(14)
            if (clean.isEmpty()) return null
            // (en una ventana —AlertDialog/Dialog— el menú sale al centro, como en tu app; en un menú desplegable, junto al botón)
            val centered = Regex("AlertDialog|MaterialAlertDialogBuilder|\\bDialog\\(|BottomSheetDialog").containsMatchIn(body) &&
                !Regex("showAsDropDown|PopupMenu\\(").containsMatchIn(body)
            return "menu(${clean.joinToString(",", "[", "]") { jsq(it) }}, ${title?.let { jsq(it) } ?: "null"}${if (centered) ", 1" else ""});"
        }
    }

    /** El bloque { … } que empieza en [open] (respeta textos y comentarios). */
    private fun block(c: String, open: Int): String? {
        var depth = 0; var i = open
        while (i < c.length) {
            when (c[i]) {
                '"' -> { i++; while (i < c.length && c[i] != '"') { if (c[i] == '\\') i++; i++ } }
                '/' -> if (i + 1 < c.length && c[i + 1] == '/') { while (i < c.length && c[i] != '\n') i++ }
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return c.substring(open + 1, i) }
            }
            i++
        }
        return null
    }

    /** Lo que hay entre el «(» que está en [open] y su «)» (respeta textos). */
    private fun parensAt(c: String, open: Int): String? {
        var depth = 0; var i = open
        while (i < c.length) {
            when (c[i]) {
                '"' -> { i++; while (i < c.length && c[i] != '"') { if (c[i] == '\\') i++; i++ } }
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return c.substring(open + 1, i) }
            }
            i++
        }
        return null
    }

    /**
     * Una condición sencilla de Kotlin sobre lo escrito en los campos, en JavaScript para la maqueta:
     * «titulo.text.isBlank() && contenido.text.isEmpty()» → «!valor('titulo').trim() && !valor('contenido')». null si es otra cosa.
     */
    private fun jsCond(cond: String, idOf: (String) -> String?): String? {
        var ok = true
        val js = Regex("((?:binding\\.)?\\w+)\\??\\.text\\??(?:\\.toString\\(\\))?(\\.trim\\(\\))?\\.(isBlank|isEmpty|isNotBlank|isNotEmpty|isNullOrBlank|isNullOrEmpty)\\(\\)").replace(cond) { m ->
            val id = idOf(m.groupValues[1]) ?: run { ok = false; "x" }
            val v = "valor('$id')" + if (m.groupValues[3].contains("Blank") || m.groupValues[2].isNotEmpty()) ".trim()" else ""
            if (m.groupValues[3].startsWith("isNot")) "!!$v" else "!$v"
        }
        if (!ok || !Regex("^[\\s!&|()]*(?:(?:!!?valor\\('[^']+'\\)(?:\\.trim\\(\\))?)[\\s!&|()]*)+$").matches(js)) return null
        return js
    }

    /** Lo que hace una parte del código (con «if (campo vacío) … else …» hecho en la maqueta, si se puede). */
    private fun branchJs(body: String, idOf: (String) -> String?, screenOf: Map<String, String>): String {
        Regex("^\\s*if\\s*\\(").find(body)?.let { im ->
            val cond = parensAt(body, im.range.last)
            val afterCond = im.range.last + (cond?.length ?: 0) + 2
            val cj = cond?.let { jsCond(it, idOf) }
            if (cj != null) {
                val rest = body.substring(afterCond)
                val ob = Regex("^\\s*\\{").find(rest)
                val a = if (ob != null) block(rest, ob.range.last) else rest.substringBefore('\n')
                if (a != null) {
                    val tail = if (ob != null) rest.substring(ob.range.last + a.length + 2) else rest.substringAfter('\n', "")
                    val em = Regex("^\\s*else\\s*\\{").find(tail)
                    val b = em?.let { block(tail, it.range.last) }.orEmpty()
                    return "if($cj){ ${actions(a, idOf, screenOf).joinToString(" ")} }else{ ${actions(b, idOf, screenOf).joinToString(" ")} }"
                }
            }
        }
        return actions(body, idOf, screenOf).joinToString(" ")
    }

    /**
     * ⋮ El menú de la barra de arriba: onCreateOptionsMenu infla res/menu/x.xml (o agrega menu.add("…")) y
     * onOptionsItemSelected dice qué hace cada opción. En la maqueta: el botón ⋮ en la barra (la de setSupportActionBar,
     * o la barra del tema) con esas opciones, y cada una hace lo mismo (avisos, limpiar campos, abrir otra pantalla…).
     */
    private fun optionsMenuJs(c: String, files: List<SourceFile>, idOf: (String) -> String?, screenOf: Map<String, String>): String? {
        val cm = Regex("\\bfun\\s+onCreateOptionsMenu\\s*\\(").find(c) ?: return null
        val create = c.indexOf('{', cm.range.last).takeIf { it >= 0 }?.let { block(c, it) } ?: return null
        val items = mutableListOf<Triple<String?, String, String?>>()   // (id, título, ícono)
        val strings = files.filter { it.content.contains("<string ") }.flatMap { f ->
            Regex("<string\\s+name=\"(\\w+)\"[^>]*>([^<]*)</string>").findAll(f.content).map { it.groupValues[1] to it.groupValues[2] }.toList() }.toMap()
        fun title(t: String) = if (t.startsWith("@string/")) strings[t.removePrefix("@string/")] ?: t.removePrefix("@string/") else t
        Regex("inflate\\(\\s*R\\.menu\\.(\\w+)").find(create)?.groupValues?.get(1)?.let { mn ->
            val xml = files.firstOrNull { it.name == "$mn.xml" && it.content.contains("<menu") }?.content ?: return@let
            Regex("<item\\b(.*?)/?>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { im ->
                val at = im.groupValues[1]
                fun attr(n: String) = Regex("\\b(?:android|app):$n=\"([^\"]*)\"").find(at)?.groupValues?.get(1)
                val t = attr("title") ?: return@forEach
                val show = attr("showAsAction").orEmpty()
                val icon = attr("icon")?.substringAfter('/')?.takeIf { show.contains("always") || show.contains("ifRoom") }
                items += Triple(attr("id")?.substringAfter('/'), title(t), icon)
            }
        }
        Regex("\\.add\\(\\s*(?:[^,()\\n]+,\\s*(R\\.id\\.\\w+|[^,()\\n]+),\\s*[^,()\\n]+,\\s*)?\"([^\"]+)\"").findAll(create).forEach { m ->
            items += Triple(m.groupValues[1].removePrefix("R.id.").takeIf { it.isNotBlank() && !it.contains(' ') }, m.groupValues[2], null) }
        if (items.isEmpty()) return null
        // Lo que hace cada opción
        val sel = Regex("\\bfun\\s+onOptionsItemSelected\\s*\\(").find(c)?.let { sm -> c.indexOf('{', sm.range.last).takeIf { it >= 0 }?.let { block(c, it) } }.orEmpty()
        fun actFor(id: String?): String {
            if (id == null) return ""
            val m = Regex("R\\.id\\.${Regex.escape(id)}\\s*(?:,[^-\\n]*)?->\\s*").find(sel) ?: return ""
            val rest = sel.substring(m.range.last + 1)
            val body = if (rest.trimStart().startsWith("{")) block(rest, rest.indexOf('{')) ?: "" else rest.substringBefore('\n')
            return branchJs(body.trim(), idOf, screenOf)
        }
        val bar = Regex("setSupportActionBar\\(\\s*([\\w.]+)").find(c)?.groupValues?.get(1)?.let { idOf(it.substringAfterLast("binding.")) }
        val list = items.joinToString(",", "[", "]") { (id, t, icon) ->
            "{t:${jsq(t)}${icon?.let { ",i:'$it'" } ?: ""},f:function(){ ${actFor(id)} }}"
        }
        return "menuOpciones(${bar?.let { "'$it'" } ?: "null"},$list);"
    }

    /** Lo que hace un botón, en las funciones de la maqueta (aviso, texto, mostrar, abrir). */
    private fun actions(body: String, idOf: (String) -> String?, screenOf: Map<String, String>, skipDialogTitles: Boolean = false): List<String> {
        val str = "\"((?:[^\"\\\\]|\\\\.)*)\""
        fun js(s: String) = "\"" + s.replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…").replace("\\$", "$") + "\""
        val acts = mutableListOf<Pair<Int, String>>()
        Regex("Toast\\.makeText\\([^,]+,\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
        Regex("Snackbar\\.make\\([^,]+,\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
        // (si el botón abre un menú o una ventana, su título va en la ventana y no como aviso)
        if (!skipDialogTitles) Regex("\\.set(?:Title|Message)\\(\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
        Regex("((?:binding\\.)?\\w+)\\??\\.text\\s*=\\s*$str").findAll(body).forEach { m ->
            idOf(m.groupValues[1])?.let { acts += m.range.first to "texto('$it', ${js(m.groupValues[2])});" } }
        Regex("((?:binding\\.)?\\w+)\\??\\.setText\\(\\s*$str").findAll(body).filter { it.groupValues[2].isNotEmpty() }.forEach { m ->
            idOf(m.groupValues[1])?.let { acts += m.range.first to "texto('$it', ${js(m.groupValues[2])});" } }
        Regex("((?:binding\\.)?\\w+)\\??\\.visibility\\s*=\\s*View\\.(VISIBLE|GONE|INVISIBLE)").findAll(body).forEach { m ->
            idOf(m.groupValues[1])?.let { acts += m.range.first to "mostrar('$it', ${m.groupValues[2] == "VISIBLE"});" } }
        Regex("((?:binding\\.)?\\w+)\\??\\.(?:text\\?*\\.)?clear\\(\\)|((?:binding\\.)?\\w+)\\??\\.setText\\(\\s*\"\"\\s*\\)").findAll(body).forEach { m ->
            idOf(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { acts += m.range.first to "limpiar('$it');" } }
        Regex("Intent\\([^,()]+,\\s*(\\w+)::class\\.java\\)").findAll(body).forEach { m ->
            screenOf[m.groupValues[1]]?.let { acts += m.range.first to "abrir('$it');" } }
        return acts.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** Los archivos como mensaje con bloques de código (así los leen la maqueta y la vista previa). */
    fun asPreviewText(files: List<SourceFile>): String = files.filter { it.content.isNotBlank() }.joinToString("\n\n") { f ->
        val ext = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { if (it == "htm") "html" else it }
        val fn = fence(f.content)
        // 📍 La carpeta de los recursos (values-night, drawable…): la maqueta usa la de modo oscuro solo si la app es oscura
        val where = if (f.path.contains("/res/")) "📍 Ruta: ${f.path}\n" else ""
        "📄 ${f.name}\n$where$fn$ext\n${f.content.trimEnd()}\n$fn"
    }

    /**
     * 🧱 Las comillas que encierran un archivo: siempre MÁS largas que cualquier ``` que el archivo tenga por dentro.
     * Antes, un código con ``` escritas (como el de Cumulus) cortaba el archivo a la mitad y la maqueta perdía los XML.
     */
    fun fence(content: String): String =
        "`".repeat(maxOf(3, (Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1))

    /**
     * ✂️ El código sin sus textos entre comillas ni sus comentarios: lo que de verdad se ejecuta.
     * Así no cuentan los ejemplos escritos dentro de textos (plantillas, guías) como R.id.btn_guardar.
     */
    private fun withoutStrings(code: String): String {
        val sb = StringBuilder(code.length)
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                code.startsWith("\"\"\"", i) -> {   // texto de varias líneas """…"""
                    val end = code.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) n else end + 3
                    sb.append("\"\"")
                }
                c == '"' -> {   // "texto"
                    i++
                    while (i < n && code[i] != '"' && code[i] != '\n') { if (code[i] == '\\') i++; i++ }
                    i++
                    sb.append("\"\"")
                }
                c == '\'' && i + 2 < n && (code[i + 2] == '\'' || code[i + 1] == '\\') -> {   // 'a', '\n'
                    val end = code.indexOf('\'', if (code[i + 1] == '\\') i + 3 else i + 2)
                    i = if (end < 0 || end - i > 8) i + 1 else end + 1
                    sb.append("' '")
                }
                code.startsWith("//", i) -> { val end = code.indexOf('\n', i); i = if (end < 0) n else end }
                code.startsWith("/*", i) -> { val end = code.indexOf("*/", i + 2); i = if (end < 0) n else end + 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** R.id.x, R.layout.x… del proyecto (no los de Android: android.R.id.content, android.R.drawable.ic_media_play). */
    private fun rRefs(kind: String, code: String) =
        Regex("(?<!android\\.)\\bR\\.$kind\\.(\\w+)").findAll(code).map { it.groupValues[1] }

    // ------------------------------------------------------------------ 2) revisar

    fun check(files0: List<SourceFile>): Report {
        // (si el mismo archivo vino dos veces, vale el último)
        val files = files0.reversed().distinctBy { it.path.ifBlank { it.name } }.reversed()
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        fun ext(f: SourceFile) = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val xmls = files.filter { ext(it) == "xml" && it.content.isNotBlank() }
        val code = files.filter { ext(it) in setOf("kt", "java") }
        val html = files.filter { ext(it) in setOf("html", "htm") }
        val css = files.filter { ext(it) == "css" }
        val js = files.filter { ext(it) in setOf("js", "mjs") }
        val images = files.filter { it.content.isBlank() && ext(it) in imageExts }

        // XML bien cerrado
        for (f in xmls) xmlError(f.content)?.let { problems += "${f.name}: el XML está cortado o mal cerrado ($it)." }

        val layouts = xmls.filter { isLayout(it.content) }
        val values = xmls.filter { it.content.contains("<resources") }
        val manifest = xmls.firstOrNull { it.content.contains("<manifest") }
        // (sin comentarios <!-- … -->: lo comentado no cuenta)
        val allXml = xmls.joinToString("\n") { it.content.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ") }
        // (sin textos entre comillas ni comentarios: los ejemplos escritos dentro de textos no son código real)
        val allCode = code.joinToString("\n") { withoutStrings(it.content) }

        // @string / @color que se usan y no existen (solo si mandaste algún strings.xml / colors.xml)
        val strings = values.flatMap { v -> Regex("<string\\s+name=\"([^\"]+)\"").findAll(v.content).map { it.groupValues[1] } }.toSet()
        val colors = values.flatMap { v -> Regex("<color\\s+name=\"([^\"]+)\"").findAll(v.content).map { it.groupValues[1] } }.toSet()
        val usedStrings = (Regex("@string/([\\w.]+)").findAll(allXml).map { it.groupValues[1] } +
            rRefs("string", allCode)).toSet()
        val usedColors = (Regex("@color/([\\w.]+)").findAll(allXml).map { it.groupValues[1] } +
            rRefs("color", allCode)).toSet()
        if (usedStrings.isNotEmpty()) {
            if (values.none { it.content.contains("<string ") }) notes += "Tus archivos usan textos @string/… pero no incluiste strings.xml (revisa que esté en res/values)."
            else (usedStrings - strings).takeIf { it.isNotEmpty() }?.let {
                problems += "Textos que se usan y no están en strings.xml: " + it.take(12).joinToString(", ") { s -> "@string/$s" } + "."
            }
        }
        if (usedColors.isNotEmpty()) {
            if (values.none { it.content.contains("<color ") }) notes += "Tus archivos usan colores @color/… pero no incluiste colors.xml (revisa que esté en res/values)."
            else (usedColors - colors).takeIf { it.isNotEmpty() }?.let {
                problems += "Colores que se usan y no están en colors.xml: " + it.take(12).joinToString(", ") { s -> "@color/$s" } + "."
            }
        }
        // @drawable: solo si mandaste la carpeta de imágenes (un .zip con res/drawable)
        val drawableNames = (images.map { it.name.substringBeforeLast('.') } +
            xmls.filter { it.path.contains("/drawable") }.map { it.name.substringBeforeLast('.') }).toSet()
        val usedDrawables = (Regex("@drawable/([\\w.]+)").findAll(allXml).map { it.groupValues[1] } +
            rRefs("drawable", allCode)).toSet()
        if (usedDrawables.isNotEmpty() && files.any { it.path.contains("/drawable") }) {
            (usedDrawables - drawableNames).takeIf { it.isNotEmpty() }?.let {
                problems += "Imágenes o fondos que se usan y no están en res/drawable: " + it.take(12).joinToString(", ") { s -> "@drawable/$s" } + "."
            }
        }

        // R.id / R.layout del código que no están en ningún diseño
        if (code.isNotEmpty() && layouts.isNotEmpty()) {
            val ids = Regex("@\\+id/([\\w]+)").findAll(allXml).map { it.groupValues[1] }.toSet()
            val usedIds = rRefs("id", allCode).toSet()
            (usedIds - ids).takeIf { it.isNotEmpty() }?.let {
                problems += "El código busca elementos que no están en ningún diseño XML: " + it.take(12).joinToString(", ") { s -> "R.id.$s" } + "."
            }
            // ViewBinding (binding.btnGuardar): el id en el XML es btn_guardar o btnGuardar
            if (Regex("\\bbinding\\.\\w+").containsMatchIn(allCode)) {
                val camel = ids.map { id -> id.split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { c -> c.uppercase() } }.joinToString("") }.toSet()
                val skip = setOf("root", "apply", "let", "run", "also", "getRoot")
                val usedB = Regex("\\bbinding\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }.filter { it !in skip }.toSet()
                (usedB - camel - ids).takeIf { it.isNotEmpty() }?.let {
                    problems += "El código usa con binding elementos que no están en el diseño: " + it.take(12).joinToString(", ") { s -> "binding.$s" } + "."
                }
            }
            val layoutNames = layouts.map { it.name.substringBeforeLast('.') }.toSet()
            val usedLayouts = rRefs("layout", allCode).toSet()
            if (layouts.none { it.name.startsWith("pantalla_") }) (usedLayouts - layoutNames).takeIf { it.isNotEmpty() }?.let {
                problems += "El código abre pantallas que no vienen en tus archivos: " + it.take(12).joinToString(", ") { s -> "R.layout.$s ($s.xml)" } + "."
            }
        }
        // Manifiesto: actividades declaradas que no existen en el código
        if (manifest != null && code.isNotEmpty()) {
            val declared = Regex("<activity\\b[^>]*?android:name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).findAll(manifest.content)
                .map { it.groupValues[1].substringAfterLast('.') }.toSet()
            val classes = Regex("\\b(?:class|object)\\s+(\\w+)").findAll(allCode).map { it.groupValues[1] }.toSet()
            (declared - classes).takeIf { it.isNotEmpty() }?.let {
                problems += "El AndroidManifest.xml declara pantallas (activity) que no están en tu código: " + it.joinToString(", ") + "."
            }
        } else if (manifest == null && Regex("\\b(AppCompatActivity|ComponentActivity|Activity)\\b").containsMatchIn(allCode)) {
            notes += "No incluiste el AndroidManifest.xml: sin él la app no se puede instalar (revisa que esté en app/src/main)."
        }

        // 🌐 Web: archivos que la página pide y no vienen, e id que el JavaScript busca y no están
        if (html.isNotEmpty()) {
            val names = files.map { it.name.lowercase(Locale.ROOT) }.toSet()
            for (h in html) {
                Regex("(?i)<(?:link[^>]+href|script[^>]+src)=[\"'](?!https?:|//|data:)([^\"'?#]+\\.(?:css|js))[\"']").findAll(h.content)
                    .map { it.groupValues[1].substringAfterLast('/') }.filter { it.lowercase(Locale.ROOT) !in names }.toSet()
                    .takeIf { it.isNotEmpty() }?.let { problems += "${h.name} usa archivos que no vienen: " + it.joinToString(", ") + "." }
            }
            // 🖼️ Imágenes, íconos, fuentes o páginas que tu página usa y no vinieron (solo si mandaste el proyecto con sus archivos)
            if (images.isNotEmpty() || files.any { it.path.contains('/') }) {
                val paths = files.map { it.path.replace('\\', '/').lowercase(Locale.ROOT) }
                fun has(ref: String): Boolean {
                    val r = ref.substringBefore('?').substringBefore('#').removePrefix("./").trimStart('/').lowercase(Locale.ROOT)
                    val tail = r.substringAfterLast("../")
                    return r.isBlank() || paths.any { it.endsWith("/$tail") || it == tail }
                }
                val refRx = Regex("(?i)(?:src|href|poster)\\s*=\\s*[\"']((?!https?:|//|data:|mailto:|tel:|javascript:|#)[^\"'{}$]+\\.(?:png|jpe?g|gif|webp|avif|svg|ico|bmp|mp3|mp4|webm|ogg|wav|woff2?|ttf|otf|json|html?|pdf))[\"']")
                val cssRx = Regex("(?i)url\\(\\s*[\"']?((?!https?:|//|data:)[^\"')]+\\.(?:png|jpe?g|gif|webp|avif|svg|bmp|woff2?|ttf|otf))[\"']?\\s*\\)")
                val missing = (html.flatMap { h -> refRx.findAll(h.content).map { it.groupValues[1] }.toList() } +
                    (css + html).flatMap { c -> cssRx.findAll(c.content).map { it.groupValues[1] }.toList() })
                    .filter { !has(it) }.distinct()
                if (missing.isNotEmpty()) problems += "Tu página usa imágenes o archivos que no vinieron (en la vista previa salen rotos): " +
                    missing.take(12).joinToString(", ") + (if (missing.size > 12) " y ${missing.size - 12} más" else "") + "."
            }
            val allHtml = html.joinToString("\n") { it.content }
            val htmlIds = Regex("(?i)\\bid=[\"']([^\"']+)[\"']").findAll(allHtml).map { it.groupValues[1] }.toSet()
            val allJs = js.joinToString("\n") { it.content } + "\n" +
                Regex("(?is)<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>").findAll(allHtml).joinToString("\n") { it.groupValues[1] }
            val jsIds = (Regex("getElementById\\(\\s*['\"]([^'\"]+)['\"]").findAll(allJs).map { it.groupValues[1] } +
                Regex("querySelector(?:All)?\\(\\s*['\"]#([\\w-]+)['\"]").findAll(allJs).map { it.groupValues[1] }).toSet()
            // (los id que el JavaScript crea él mismo no cuentan)
            val createdIds = Regex("\\.id\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(allJs).map { it.groupValues[1] }.toSet()
            (jsIds - htmlIds - createdIds).takeIf { it.isNotEmpty() }?.let {
                problems += "El JavaScript busca elementos que no están en la página: " + it.take(12).joinToString(", ") { s -> "#$s" } + "."
            }
            if (css.isEmpty() && !Regex("(?i)<style|<link[^>]+stylesheet|tailwind").containsMatchIn(allHtml))
                notes += "La página no trae estilos (CSS): se verá sin diseño."
        }
        // 🧩 Jetpack Compose: la pantalla se arma con código (@Composable), no con XML: la maqueta no la puede dibujar
        if (layouts.isEmpty() && allCode.contains("@Composable"))
            notes += "Tu app está hecha con Jetpack Compose (sin diseños XML): la maqueta no puede dibujarla. Para verla, compílala."
        if (files.none { it.content.isNotBlank() }) notes += "No encontré archivos de código que pueda revisar."
        memoryNote?.let { notes += it; memoryNote = null }
        embeddedNote?.let { notes += it; embeddedNote = null }
        return Report(files, problems.distinct(), notes.distinct(), layouts.isNotEmpty(), html.isNotEmpty() || (js.isNotEmpty() && layouts.isEmpty()))
    }

    private fun isLayout(xml: String) = Regex("<(?:[\\w.]*Layout|ScrollView|NestedScrollView|LinearLayout|FrameLayout|RelativeLayout|" +
        "androidx\\.[\\w.]+|com\\.google\\.android\\.material\\.[\\w.]+|merge|TextView|Button)\\b").containsMatchIn(xml) &&
        !xml.contains("<manifest") && !xml.contains("<resources") && !Regex("<(menu|navigation|shape|selector|vector)[\\s>]").containsMatchIn(xml)

    /** null = el XML está bien cerrado; si no, dónde se rompe («línea 12»). */
    private fun xmlError(xml: String): String? = runCatching {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val b = f.newDocumentBuilder()
        b.setErrorHandler(null)
        b.parse(org.xml.sax.InputSource(java.io.StringReader(xml.trim().removePrefix("\uFEFF"))))
        null
    }.getOrElse { e ->
        val line = (e as? org.xml.sax.SAXParseException)?.lineNumber?.takeIf { it > 0 }
        if (line != null) "línea $line" else "revisa el final del archivo"
    }

    /** El informe para el chat (en español: la app lo pasa a tu idioma). */
    /**
     * [opening] = true cuando la app o la página se abre ahora (desde el chat); false en la pantalla «Pega tu código»,
     * donde la app se muestra recién al tocar «▶ Mostrar app».
     */
    fun reportText(r: Report, simulatedButtons: Int = 0, opening: Boolean = true): String {
        val sb = StringBuilder("🔎 Revisé tu proyecto antes de compilarlo\n\n")
        val real = r.files.filter { it.content.isNotBlank() }
        val lines = real.sumOf { f -> f.content.count { it == '\n' } + 1 }
        sb.append("📂 ").append(real.size).append(" archivo(s) · ").append(String.format(Locale.ROOT, "%,d", lines).replace(',', '.'))
            .append(" líneas: ").append(real.take(14).joinToString(", ") { it.name })
        if (real.size > 14) sb.append(" y ${real.size - 14} más")
        sb.append("\n\n")
        if (r.problems.isEmpty()) sb.append("✅ No encontré errores que impidan compilar o ver tu proyecto.\n")
        else { sb.append("⚠️ Esto puede romper la compilación o la página:\n"); r.problems.forEach { sb.append("• ").append(it).append('\n') } }
        if (r.notes.isNotEmpty()) { sb.append("\n💡 Conviene saber:\n"); r.notes.forEach { sb.append("• ").append(it).append('\n') } }
        sb.append('\n')
        if (!opening) {
            // (pantalla «Pega tu código»: todavía no se muestra; se dice qué vas a ver al tocar el botón)
            if (r.hasAppLayouts) sb.append("📱 Lista para verla: toca «▶ Mostrar app». Verás tus pantallas (el diseño)" +
                (if (simulatedButtons > 0) " y $simulatedButtons botón(es) respondiendo como en tu código (avisos, textos, menús, ventanas y cambio de pantalla)" else "") +
                ". Lo demás de tu lógica Kotlin funciona recién al compilar la app.\n")
            else if (r.hasWeb) sb.append("🖥️ Lista para verla: toca «▶ Mostrar app». Tu página funcionará de verdad (botones, menús, formularios).\n")
            else sb.append("Todavía no hay una pantalla (XML) ni una página (HTML) para mostrar: pega también ese archivo.\n")
            return sb.toString().trim()
        }
        if (r.hasAppLayouts) sb.append("📱 Te abro la maqueta: ahí ves cómo se verán tus pantallas (el diseño). " +
            (if (simulatedButtons > 0) "Tus botones ya responden como en tu código ($simulatedButtons botón(es): avisos, textos y cambio de pantalla). " +
                "Lo demás (guardar datos, internet…) funciona recién al compilar la app.\n"
            else "La lógica en Kotlin funciona recién al compilar la app.\n"))
        if (r.hasWeb) sb.append("🖥️ " + (if (r.hasAppLayouts) "También puedes ver" else "Te abro") +
            " la vista previa web: ahí tu página funciona de verdad (botones, menús, formularios).\n")
        if (r.hasAppLayouts || r.hasWeb) sb.append("\nPuedes volver a abrirlas cuando quieras con ➕ → «Ver tu app Kotlin» o «Ver tu app y página web».")
        return sb.toString().trim()
    }
}
