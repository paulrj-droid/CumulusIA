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

    /** 🖼️🔤 Imágenes (res/drawable, res/mipmap) y fuentes (res/font) de tu proyecto para la maqueta: nombre → data: URI. */
    class Assets(val images: Map<String, String>, val fonts: Map<String, String>)

    private val fontExts = setOf("ttf", "otf")
    private const val MAX_ASSET = 1_500_000L        // (una imagen más grande no se muestra: se ve el recuadro de ejemplo)
    private const val MAX_ASSETS_TOTAL = 10_000_000L

    /**
     * 🖼️ Las imágenes y fuentes de tus archivos o tu .zip, para que la maqueta muestre tu logo, tus fondos, tus fotos
     * y tu letra. De cada imagen se toma la mejor versión que quepa (drawable, xxhdpi, xhdpi…). Con tope de tamaño
     * para no llenar la memoria del teléfono.
     */
    fun assetsFrom(files: List<File>): Assets {
        val images = HashMap<String, String>(); val fonts = HashMap<String, String>()
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
            val uri = "data:${mime(ext)};base64," + java.util.Base64.getEncoder().encodeToString(bytes)
            if (ext in fontExts) fonts[name] = uri else images[name] = uri
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
        val files = files0.toMutableList()
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
                val win = ix.windowOrMenu(body) ?: ix.followed(body).firstNotNullOfOrNull { ix.windowOrMenu(it) }
                val acts = actions(body, ::idOf, screenOf, skipDialogTitles = win != null) + ix.colorActions(body, ::idOf, myLayout) + listOfNotNull(win)
                if (acts.isNotEmpty()) out.append("alClick('").append(id).append("', function(){ ").append(acts.joinToString(" ")).append(" });\n")
            }
            // 🎨 Colores que tu Kotlin pone al abrir la pantalla (onCreate…), sin contar lo que pasa al tocar botones
            for (m in Regex("\\bfun\\s+(onCreate|onViewCreated|onStart|onResume)\\s*\\(").findAll(c)) {
                val open = c.indexOf('{', m.range.last).takeIf { it >= 0 } ?: continue
                var body = block(c, open) ?: continue
                for (cm in clickRx.findAll(body).toList().asReversed()) {
                    val inner = block(body, cm.range.last) ?: continue
                    body = body.substring(0, cm.range.last + 1) + " ".repeat(inner.length) + body.substring(cm.range.last + 1 + inner.length)
                }
                ix.colorActions(body, ::idOf, myLayout).forEach { out.append(it).append('\n') }
            }
        }
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
                    else -> null
                }
            }
            Regex("Color\\.parseColor\\(\\s*\"(#[0-9A-Fa-f]{3,8})\"").find(expr)?.let { return hex(it.groupValues[1]) }
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

        /** Los nombres de una lista: "Rápido", "a" to "Rápido" → Rápido, Modo("clave", "Solo chat", …) → Solo chat. */
        private fun labelsOf(listBody: String): List<String> = topSplit(listBody).mapNotNull { item ->
            val lits = lit.findAll(item).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
            when {
                lits.isEmpty() -> null
                Regex("\"\\s+to\\s+\"").containsMatchIn(item) -> lits.getOrNull(1)
                item.startsWith("\"") && lits.size == 1 -> lits[0]
                else -> lits.firstOrNull { !isKey(it) && it.length <= 40 }
            }
        }.filter { it.length <= 40 }.take(14)

        /** Las opciones de una expresión: una lista escrita ahí mismo o una variable/función que la tiene. */
        fun optionsOf(expr: String, scope: String, depth: Int = 0): List<String> {
            if (depth > 3) return emptyList()
            Regex("\\b(?:mutableListOf|listOfNotNull|listOf|arrayOf|setOf)\\s*\\(").find(expr)?.let { m ->
                parens(expr, m.range.last)?.let { labelsOf(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val skip = setOf("it", "this", "true", "false", "null", "map", "filter", "reversed", "sorted", "toList", "label", "second", "first",
                "anchor", "above", "checked", "icons", "current", "i", "key", "size", "indexOfFirst", "coerceAtLeast", "widthFrom")
            for (m in Regex("\\b([a-zA-Z_]\\w*)\\b(\\s*\\()?").findAll(expr.take(4000)).take(40)) {
                val name = m.groupValues[1]
                if (name in skip || name.first().isUpperCase()) continue
                val sub = if (m.groupValues[2].isNotEmpty()) funs[name] else valueOf(name, scope)
                if (sub != null) optionsOf(sub, sub, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
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
            return "menu(${clean.joinToString(",", "[", "]") { jsq(it) }}${title?.let { ", " + jsq(it) } ?: ""});"
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
