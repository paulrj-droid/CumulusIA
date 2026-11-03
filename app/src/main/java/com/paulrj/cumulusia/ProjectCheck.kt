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
        for (m in rx.findAll(text)) {
            val code = m.groupValues[2].trimEnd()
            if (code.isBlank()) continue
            val before = text.substring(maxOf(0, m.range.first - 300), m.range.first)
            val named = Regex("([\\w.-]+\\.(xml|html?|css|m?js|kts?|java|gradle|json|toml|properties))", RegexOption.IGNORE_CASE)
                .findAll(before).lastOrNull()?.value
            val name = named ?: guessName(code, m.groupValues[1].lowercase(Locale.ROOT), out.size + 1)
            out += SourceFile(name, name, code)
        }
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
            }
        }
        return out
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
                        if (ext in imageExts) { out += SourceFile(name, path, ""); continue }
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
        fun flush() {
            if (cur.isNotBlank()) segs += name to cur.toString().trim('\n')
            cur.setLength(0); name = null; kind = ""; root = null; closed = false
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
                Regex("^(import|class|object|fun)\\b").containsMatchIn(line) && kind != "kt" && closed -> true
                kind == "web" && closed && t.isNotEmpty() -> true
                else -> false
            }
            if (newFile) flush()
            cur.append(line).append('\n')
            if (kind.isEmpty() && t.isNotEmpty()) kind = when {
                startsHtml -> "web"
                t.startsWith("<") -> "xml"
                Regex("^(package|import|class|object|fun|@|data class|abstract|open|interface|private|internal|public|val|var)\\b").containsMatchIn(t) -> "kt"
                else -> ""
            }
            if (kind == "xml" && root == null) Regex("^<([A-Za-z_][\\w.:-]*)").find(t)?.groupValues?.get(1)?.takeIf { !t.startsWith("<?") }?.let { root = it }
            if (kind == "xml" && root != null && (t == "</$root>" || (t.startsWith("<$root") && t.endsWith("/>") && cur.lines().count { it.isNotBlank() } <= 2))) closed = true
            if (kind == "web" && Regex("(?i)</html>\\s*$").containsMatchIn(t)) closed = true
        }
        flush()
        val used = segs.mapNotNull { it.first?.lowercase(Locale.ROOT) }.toMutableSet()
        return segs.mapIndexed { i, (n, c) ->
            var nm = n ?: guessName(c, "", i + 1)
            // (sin nombre: la primera hoja de estilos es style.css y el primer JavaScript script.js, como los pide la página)
            if (n == null) {
                val plain = when { nm.endsWith(".css") -> "style.css"; nm.endsWith(".js") -> "script.js"; else -> null }
                if (plain != null && used.add(plain)) nm = plain
            }
            SourceFile(nm, nm, c)
        }
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
        for (f in kts) {
            val c = f.content
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
                val acts = actions(body, ::idOf, screenOf)
                if (acts.isNotEmpty()) out.append("alClick('").append(id).append("', function(){ ").append(acts.joinToString(" ")).append(" });\n")
            }
        }
        if (out.isEmpty()) return null
        return "// maqueta.js · lo armó Cumulus desde tu código Kotlin: simula lo que hacen tus botones.\n" +
            "// (la app real usa tu Kotlin al compilarla)\n" + out
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
    private fun actions(body: String, idOf: (String) -> String?, screenOf: Map<String, String>): List<String> {
        val str = "\"((?:[^\"\\\\]|\\\\.)*)\""
        fun js(s: String) = "\"" + s.replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "…").replace("\\$", "$") + "\""
        val acts = mutableListOf<Pair<Int, String>>()
        Regex("Toast\\.makeText\\([^,]+,\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
        Regex("Snackbar\\.make\\([^,]+,\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
        Regex("\\.set(?:Title|Message)\\(\\s*$str").findAll(body).forEach { acts += it.range.first to "aviso(${js(it.groupValues[1])});" }
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
        "📄 ${f.name}\n```$ext\n${f.content.trimEnd()}\n```"
    }

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
        val allXml = xmls.joinToString("\n") { it.content }
        val allCode = code.joinToString("\n") { it.content }

        // @string / @color que se usan y no existen (solo si mandaste algún strings.xml / colors.xml)
        val strings = values.flatMap { v -> Regex("<string\\s+name=\"([^\"]+)\"").findAll(v.content).map { it.groupValues[1] } }.toSet()
        val colors = values.flatMap { v -> Regex("<color\\s+name=\"([^\"]+)\"").findAll(v.content).map { it.groupValues[1] } }.toSet()
        val usedStrings = (Regex("@string/([\\w.]+)").findAll(allXml).map { it.groupValues[1] } +
            Regex("R\\.string\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }).toSet()
        val usedColors = (Regex("@color/([\\w.]+)").findAll(allXml).map { it.groupValues[1] } +
            Regex("R\\.color\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }).toSet()
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
            Regex("R\\.drawable\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }).toSet()
        if (usedDrawables.isNotEmpty() && files.any { it.path.contains("/drawable") }) {
            (usedDrawables - drawableNames).takeIf { it.isNotEmpty() }?.let {
                problems += "Imágenes o fondos que se usan y no están en res/drawable: " + it.take(12).joinToString(", ") { s -> "@drawable/$s" } + "."
            }
        }

        // R.id / R.layout del código que no están en ningún diseño
        if (code.isNotEmpty() && layouts.isNotEmpty()) {
            val ids = Regex("@\\+id/([\\w]+)").findAll(allXml).map { it.groupValues[1] }.toSet()
            val usedIds = Regex("R\\.id\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }.toSet()
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
            val usedLayouts = Regex("R\\.layout\\.(\\w+)").findAll(allCode).map { it.groupValues[1] }.toSet()
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
                (if (simulatedButtons > 0) " y $simulatedButtons botón(es) respondiendo como en tu código (avisos, textos y cambio de pantalla)" else "") +
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
