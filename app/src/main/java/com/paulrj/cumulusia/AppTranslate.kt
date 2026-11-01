package com.paulrj.cumulusia

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 🌍 Traductor de Google dentro del teléfono (ML Kit).
 *
 * Cuando eliges otro idioma en la app (inglés, ruso, coreano…):
 *  - lo que la app escribe en español en el chat (noticias, clima, dólar, YouTube, títulos, avisos) sale en tu idioma;
 *  - lo que tú escribes se pasa al español SOLO para que la app entienda qué pides (noticias, clima, «conversando»…);
 *  - los titulares de otro país («noticias de Corea») salen en tu idioma.
 * Los enlaces, números, emojis y el código (bloques ```) quedan tal cual. Lo que ya está en el idioma pedido no se toca.
 * La primera vez se baja cada idioma (~30 MB); después traduce sin internet. Si algo falla, queda el texto como estaba.
 *
 * ⚠️ Se llama SIEMPRE desde un hilo de fondo (nunca desde la pantalla): espera a que termine cada traducción.
 */
object AppTranslate {
    private const val TAG = "AppTranslate"

    private val translators = ConcurrentHashMap<String, Translator>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Lo ya traducido (los títulos y avisos se repiten mucho): sale al instante. */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }

    private val langId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
        )
    }

    private val urlRx = Regex("https?://\\S+|www\\.\\S+")
    /**
     * ✍️ Lo que escribes entre comillas («Pablo Rojas», "서울시 강남구", “…”, 「…」) no se traduce al pasar tu mensaje al español:
     * así un nombre, una dirección o un dato del currículum queda exactamente como lo escribiste.
     */
    private val quotedRx = Regex("«[^»\\n]{1,120}»|\"[^\"\\n]{1,120}\"|“[^”\\n]{1,120}”|「[^」\\n]{1,120}」|『[^』\\n]{1,120}』")

    /**
     * ✍️ Frases fijas de la app dichas de otra forma ANTES de traducirlas (cortas o raras se traducían mal:
     * «Ver más en Google» salía «información sobre Google» y «Datos de:» salía «datos datos»).
     * Solo cambia lo que se traduce: en español la app sigue igual.
     */
    private val clearer = listOf(
        Regex("^Ver más en Google") to "Ver más resultados en Google",
        Regex("^Datos de: ") to "Fuente: ",
        Regex("^lee ≈") to "Leídos ≈",
        Regex("^escribe ≈") to "Escritura ≈",
        Regex("^rapido$") to "rápido",
        Regex("^Sin datos de internet") to "No se usaron datos de internet",
        Regex("^Quieres que te busque «(.+?)» en internet, o me lo estás diciendo para conversar\\?") to
            "¿Quieres que yo busque \"$1\" en internet, o solo quieres conversar?",
        Regex("^Me lo estás contando para conversar, o quieres que te busque algo sobre eso en internet\\?") to
            "¿Solo quieres conversar, o quieres que yo busque información sobre eso en internet?",
        Regex("^Dime «búscalo» o «solo conversando»\\.") to "Responde \"busca\" o \"solo conversar\".",
        Regex("^Conversamos o lo busco\\?") to "¿Conversar o buscar?"
    )

    /** Código de la app («en», «zh-CN», «pt-BR»…) → idioma del traductor (null = no lo traduce). */
    fun mlLang(code: String?): String? {
        val c = code?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
        if (c.isEmpty() || c == "und") return null
        val m = when (c) {
            "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; "fil" -> "tl"; "nb", "nn" -> "no"
            else -> c
        }
        return TranslateLanguage.fromLanguageTag(m)
    }

    /** El traductor de un idioma a otro (la primera vez baja el idioma; si no se puede, null). */
    private fun translator(src: String, tgt: String, onDownload: (() -> Unit)?): Translator? {
        val key = "$src>$tgt"
        // (si recién falló la descarga, no se vuelve a esperar en cada mensaje: se reintenta al minuto)
        failedAt[key]?.let { if (System.currentTimeMillis() - it < 60_000L) return null }
        val tr = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
        }
        if (key !in ready) {
            onDownload?.invoke()
            val ok = runCatching {
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()), 150, TimeUnit.SECONDS)
            }.onFailure { Log.w(TAG, "No se pudo bajar el idioma $key", it) }.isSuccess
            if (!ok) { failedAt[key] = System.currentTimeMillis(); return null }
            failedAt.remove(key)
            ready += key
        }
        return tr
    }

    /** ¿En qué idioma está? («und» = no se sabe: muy corto o solo símbolos) */
    private fun identify(text: String): String =
        runCatching { Tasks.await(langId.identifyLanguage(text), 3, TimeUnit.SECONDS) }.getOrDefault("und")

    /** Traduce un trozo de texto sin enlaces. */
    private fun piece(text: String, tr: Translator, key: String): String {
        if (text.none { it.isLetter() }) return text
        var src = text
        if (key.startsWith("es>")) clearer.forEach { (rx, better) -> src = rx.replace(src, better) }
        val ck = "$key|$src"
        synchronized(cache) { cache[ck]?.let { return it } }
        val out = runCatching { Tasks.await(tr.translate(src), 20, TimeUnit.SECONDS) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return text
        synchronized(cache) { cache[ck] = out }
        return out
    }

    /**
     * Traduce una línea dejando tal cual los enlaces y los símbolos del comienzo (emojis, «1.», «•», «-»).
     * («🔎 Dónde verla (Google): https://…» → «🔎 Where to watch it (Google): https://…»)
     */
    private fun line(text: String, tr: Translator, key: String): String {
        val out = StringBuilder()
        var last = 0
        fun plain(part: String) {
            if (part.none { it.isLetter() }) { out.append(part); return }
            var lead = part.takeWhile { !it.isLetter() }
            val rest = part.substring(lead.length)
            if (!key.endsWith(">es")) lead = lead.replace("¿", "").replace("¡", "")
            val trail = rest.takeLastWhile { it.isWhitespace() }
            val core = rest.substring(0, rest.length - trail.length)
            out.append(lead).append(piece(core, tr, key)).append(trail)
        }
        // (enlaces siempre tal cual; y al pasar TU mensaje al español, también lo que va entre comillas)
        val keep = if (key.endsWith(">es")) Regex(urlRx.pattern + "|" + quotedRx.pattern) else urlRx
        for (m in keep.findAll(text)) {
            plain(text.substring(last, m.range.first))
            out.append(m.value)
            last = m.range.last + 1
        }
        plain(text.substring(last))
        return out.toString()
    }

    /**
     * Traduce línea por línea al idioma [tgt]. [pickSrc] decide, según el idioma reconocido de cada línea,
     * desde qué idioma se traduce (null = esa línea queda igual). El código (entre ```) no se toca.
     */
    private fun convert(text: String, tgt: String, onDownload: (() -> Unit)?, pickSrc: (String) -> String?): String {
        if (text.isBlank() || text.none { it.isLetter() }) return text
        val out = StringBuilder()
        var inCode = false
        text.split('\n').forEachIndexed { i, ln ->
            if (i > 0) out.append('\n')
            if (ln.trimStart().startsWith("```")) { inCode = !inCode; out.append(ln); return@forEachIndexed }
            if (inCode || ln.none { it.isLetter() }) { out.append(ln); return@forEachIndexed }
            val id = identify(urlRx.replace(ln, " "))
            // Ya está en el idioma pedido: queda igual
            if (id == tgt || mlLang(id) == tgt) { out.append(ln); return@forEachIndexed }
            val src = pickSrc(id)?.takeIf { it != tgt }
            val tr = src?.let { translator(it, tgt, onDownload) }
            out.append(if (src != null && tr != null) line(ln, tr, "$src>$tgt") else ln)
        }
        return out.toString()
    }

    /**
     * 🌍 Del español al idioma elegido en la app ([targetCode]). Solo traduce las líneas en español
     * (o parecidas, o que no se sabe en qué idioma están); lo que ya está en tu idioma queda igual.
     * [assumeSpanish] = todo lo que no esté en tu idioma se toma como español (textos cortos de la app, como la línea gris).
     * [onDownload]: aviso cuando hay que bajar el idioma (la primera vez).
     */
    fun spanishTo(text: String, targetCode: String?, assumeSpanish: Boolean = false, onDownload: (() -> Unit)? = null): String {
        val tgt = mlLang(targetCode) ?: return text
        if (tgt == TranslateLanguage.SPANISH) return text
        val spanishLike = setOf("es", "und", "gl", "ca", "pt", "it")
        return convert(text, tgt, onDownload) { id ->
            if (assumeSpanish || id in spanishLike) TranslateLanguage.SPANISH else null
        }
    }

    /**
     * 🌍 Al idioma [targetCode] desde el idioma en que esté cada línea (ej. titulares de Corea en coreano → español).
     * Lo que ya está en ese idioma, o no se sabe en qué idioma está, queda igual.
     */
    fun toLanguage(text: String, targetCode: String?, onDownload: (() -> Unit)? = null): String {
        val tgt = mlLang(targetCode) ?: return text
        return convert(text, tgt, onDownload) { id -> mlLang(id) }
    }

    /**
     * 🌍 De tu idioma ([fromCode]) al español: para que la app entienda lo que pides
     * («뉴스 좀 찾아줘» → «búscame noticias», «just chatting» → «solo conversando»).
     * Lo que ya está en español queda igual.
     */
    fun toSpanish(text: String, fromCode: String?): String {
        val user = mlLang(fromCode) ?: return text
        if (user == TranslateLanguage.SPANISH) return text
        return convert(text, TranslateLanguage.SPANISH, null) { id -> mlLang(id) ?: user }
    }

    // ========================================================================
    // 💻 CÓDIGO EN TU IDIOMA (modo Ingeniero de código): en las apps que arma Cumulus se traducen SOLO
    //    · los textos que ve el usuario: android:text / hint / title…, <string>, textos de las páginas web,
    //      placeholder / title / alt, y los mensajes entre comillas que son frases («Escribe un mensaje…»);
    //    · los comentarios (// …, /* … */, <!-- … -->).
    //    NUNCA se tocan nombres de funciones, variables, id, tag, claves, colores, rutas, enlaces ni marcas internas,
    //    así el código sigue funcionando igual. Con español no hace nada.
    // ========================================================================
    private val xmlAttrRx = Regex("((?:android|app|tools):(?:text|hint|title|contentDescription|summary|label|subtitle))=\"([^\"]*)\"")
    private val xmlStringRx = Regex("(<string\\b[^>]*>)([^<]+)(</string>)")
    private val markupCommentRx = Regex("<!--([\\s\\S]*?)-->")
    private val htmlAttrRx = Regex("\\b(placeholder|title|aria-label|alt)=\"([^\"]*)\"")
    private val htmlTextRx = Regex(">([^<>]+)<")
    private val scriptStyleRx = Regex("(?is)<(script|style)\\b[\\s\\S]*?</\\1>")
    private val blockCommentRx = Regex("/\\*([\\s\\S]*?)\\*/")
    private val lineCommentRx = Regex("(?m)(^|[\\s;{}(),>])//([^\\n]*)$")
    private val dqStringRx = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    private val sqStringRx = Regex("'((?:[^'\\\\\\n]|\\\\.)*)'")

    /** ¿Es una FRASE para el usuario (y no una clave, un id, una ruta o código)? */
    private fun isProse(t: String): Boolean {
        val s = t.trim()
        if (s.length < 2 || s.none { it.isLetter() }) return false
        if (Regex("[{}<>=\\\\/_$#@|`;]|https?:|www\\.|\\.(kt|xml|js|html|css|png|jpg|json)\\b").containsMatchIn(s)) return false
        // Una sola palabra sin mayúscula ni signos es una clave («campos», «interruptores»): no se toca
        return s.contains(' ') || Regex("[.!?…:¡¿]$|^[¡¿]").containsMatchIn(s) || (s.first().isUpperCase() && s.length > 2) ||
            !Character.isLetterOrDigit(s.codePointAt(0))
    }

    /** ¿Comentario que se puede traducir? (los marcadores internos en MAYÚSCULAS, como «VENTANA_b1», no) */
    private fun isCommentProse(t: String) = t.any { it.isLowerCase() } && t.trim().contains(' ') && !t.contains("VENTANA_") &&
        !Regex("^\\s*[A-Z0-9_]+\\s*$").matches(t)

    /**
     * 💻 Traduce los textos y comentarios de un archivo de código ([fileName] decide el tipo) al idioma [targetCode].
     * Si algo falla, el archivo queda como estaba. Se llama desde un hilo de fondo.
     */
    fun localizeCode(fileName: String, content: String, targetCode: String?): String {
        val tgt = mlLang(targetCode) ?: return content
        if (tgt == TranslateLanguage.SPANISH || content.isBlank()) return content
        // 🧪 maqueta.js: lo que va como nombre de pantalla o id (abrir('Perfil'), texto('tvTitulo', …)) queda igual,
        //    si no, el botón ya no encontraría su pantalla. Los textos que se muestran (el 2.º dato, los avisos) sí se traducen.
        val keep = ArrayList<String>()
        val src = if (!fileName.equals("maqueta.js", true)) content else mockupArgRx.replace(content) { m ->
            keep.add(m.groupValues[2]); m.groupValues[1] + "\u2060" + (keep.size - 1) + "\u2060"
        }
        val out = runCatching { localizeCodeOrFail(fileName.lowercase(), src, targetCode) }
            .onFailure { Log.w(TAG, "No se pudo traducir $fileName", it) }.getOrNull() ?: return content
        if (keep.isEmpty()) return out
        val back = Regex("\u2060(\\d+)\u2060").replace(out) { m -> keep.getOrNull(m.groupValues[1].toInt()) ?: m.value }
        return if (back.contains('\u2060')) content else back
    }

    /** El 1.er dato (pantalla o id) de las funciones de la maqueta: abrir('…'), el('…'), alClick('…'), texto('…'… */
    private val mockupArgRx = Regex("\\b((?:abrir|el|alClick|texto|valor|limpiar|mostrar|agregar)\\(\\s*)('[^'\\n]*'|\"[^\"\\n]*\")")

    private fun localizeCodeOrFail(name: String, content: String, code: String?): String {
        val memo = HashMap<String, String>()
        fun tr(t: String): String {
            val core = t.trim()
            if (core.isEmpty() || core.none { it.isLetter() }) return t
            val out = memo.getOrPut(core) { spanishTo(core, code, assumeSpanish = true) }
            return t.takeWhile { it.isWhitespace() } + out + t.takeLastWhile { it.isWhitespace() }
        }
        fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun xmlUnesc(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        fun comments(text: String, line: Boolean, block: Boolean): String {
            var t = text
            if (block) t = blockCommentRx.replace(t) { m ->
                val inner = m.groupValues[1]
                if (!isCommentProse(inner)) m.value
                else "/*" + inner.split('\n').joinToString("\n") { ln ->
                    val lead = ln.takeWhile { it.isWhitespace() || it == '*' }
                    val rest = ln.substring(lead.length)
                    if (isCommentProse(rest)) lead + tr(rest) else ln
                } + "*/"
            }
            if (line) t = lineCommentRx.replace(t) { m ->
                val body = m.groupValues[2]
                if (!isCommentProse(body)) m.value else m.groupValues[1] + "//" + tr(body)
            }
            return t
        }
        fun strings(text: String, rx: Regex, quote: Char, kotlin: Boolean): String = rx.replace(text) { m ->
            val raw = m.groupValues[1]
            if (raw.contains('\\') || !isProse(raw)) m.value
            else {
                var out = tr(raw).replace("\\", "\\\\").replace(quote.toString(), "\\" + quote)
                if (kotlin) out = out.replace("$", "\\$")
                quote + out + quote
            }
        }
        return when {
            name.endsWith(".xml") -> {
                var t = xmlAttrRx.replace(content) { m ->
                    val v = m.groupValues[2]
                    if (v.startsWith("@") || v.startsWith("?") || !isProse(xmlUnesc(v)) && v.none { it.isLetter() }) m.value
                    else m.groupValues[1] + "=\"" + xmlEsc(tr(xmlUnesc(v))) + "\""
                }
                t = xmlStringRx.replace(t) { m ->
                    val v = m.groupValues[2]
                    if (v.startsWith("@") || v.none { it.isLetter() }) m.value else m.groupValues[1] + xmlEsc(tr(xmlUnesc(v))) + m.groupValues[3]
                }
                markupCommentRx.replace(t) { m -> if (isCommentProse(m.groupValues[1])) "<!--" + tr(m.groupValues[1]) + "-->" else m.value }
            }
            name.endsWith(".html") || name.endsWith(".htm") -> {
                // (los bloques <script> y <style> se traducen como código; el resto, como página)
                val out = StringBuilder()
                var last = 0
                fun page(part: String): String {
                    var t = markupCommentRx.replace(part) { m -> if (isCommentProse(m.groupValues[1])) "<!--" + tr(m.groupValues[1]) + "-->" else m.value }
                    t = htmlAttrRx.replace(t) { m ->
                        val v = m.groupValues[2]
                        if (v.none { it.isLetter() } || v.contains("{") || v.contains("$")) m.value
                        else m.groupValues[1] + "=\"" + xmlEsc(tr(xmlUnesc(v))) + "\""
                    }
                    return htmlTextRx.replace(t) { m ->
                        val v = m.groupValues[1]
                        if (v.isBlank() || v.none { it.isLetter() } || v.contains("{") || v.contains("$")) m.value
                        else ">" + tr(xmlUnesc(v)).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "<"
                    }
                }
                for (m in scriptStyleRx.findAll(content)) {
                    out.append(page(content.substring(last, m.range.first)))
                    val block = m.value
                    out.append(if (m.groupValues[1].equals("style", true)) comments(block, line = false, block = true)
                        else strings(strings(comments(block, line = true, block = true), dqStringRx, '"', false), sqStringRx, '\'', false))
                    last = m.range.last + 1
                }
                out.append(page(content.substring(last)))
                out.toString()
            }
            name.endsWith(".css") -> comments(content, line = false, block = true)
            name.endsWith(".js") || name.endsWith(".ts") ->
                strings(strings(comments(content, line = true, block = true), dqStringRx, '"', false), sqStringRx, '\'', false)
            name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".java") ->
                strings(comments(content, line = true, block = true), dqStringRx, '"', name.endsWith(".kt") || name.endsWith(".kts"))
            name.endsWith(".gradle") || name.endsWith(".cpp") || name.endsWith(".c") || name.endsWith(".h") ->
                comments(content, line = true, block = true)
            name.endsWith(".md") || name.endsWith(".txt") -> spanishTo(content, code)
            else -> content
        }
    }
}
