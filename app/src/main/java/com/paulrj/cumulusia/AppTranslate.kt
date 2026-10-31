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
        Regex("^Sin datos de internet") to "No se usaron datos de internet"
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
            val lead = part.takeWhile { !it.isLetter() }
            val rest = part.substring(lead.length)
            val trail = rest.takeLastWhile { it.isWhitespace() }
            val core = rest.substring(0, rest.length - trail.length)
            out.append(lead).append(piece(core, tr, key)).append(trail)
        }
        for (m in urlRx.findAll(text)) {
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
}
