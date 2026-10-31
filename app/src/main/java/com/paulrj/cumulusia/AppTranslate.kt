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
 * Cuando eliges otro idioma en la app (inglés, ruso, chino…), lo que la app escribe en español en el chat
 * (noticias, clima, dólar, YouTube, títulos, preguntas, avisos) se traduce a ese idioma.
 *  - Los enlaces, números, emojis y el código (bloques ```) quedan tal cual.
 *  - Lo que ya está en tu idioma (lo que escribió la IA) no se toca.
 *  - La primera vez se baja el idioma (~30 MB); después traduce sin internet.
 *  - Si algo falla, queda el texto como estaba (nunca te quedas sin respuesta).
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

    /** Código de la app («en», «zh-CN», «pt-BR»…) → idioma del traductor (null = no lo traduce). */
    fun mlLang(code: String?): String? {
        val c = code?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
        if (c.isEmpty()) return null
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
        val ck = "$key|$text"
        synchronized(cache) { cache[ck]?.let { return it } }
        val out = runCatching { Tasks.await(tr.translate(text), 20, TimeUnit.SECONDS) }
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
     * 🌍 Del español al idioma elegido en la app ([targetCode]). Solo traduce las líneas que están en español
     * (o que no se sabe en qué idioma están); lo que ya está en tu idioma queda igual.
     * [onDownload]: aviso cuando hay que bajar el idioma (la primera vez).
     */
    fun spanishTo(text: String, targetCode: String?, onDownload: (() -> Unit)? = null): String {
        val tgt = mlLang(targetCode) ?: return text
        if (tgt == TranslateLanguage.SPANISH || text.isBlank() || text.none { it.isLetter() }) return text
        val tr = translator(TranslateLanguage.SPANISH, tgt, onDownload) ?: return text
        val key = "es>$tgt"
        val out = StringBuilder()
        var inCode = false
        text.split('\n').forEachIndexed { i, ln ->
            if (i > 0) out.append('\n')
            // 💻 El código (entre ```) no se traduce
            if (ln.trimStart().startsWith("```")) { inCode = !inCode; out.append(ln); return@forEachIndexed }
            if (inCode || ln.none { it.isLetter() }) { out.append(ln); return@forEachIndexed }
            val id = identify(urlRx.replace(ln, " "))
            // Ya está en tu idioma (lo escribió la IA): queda igual
            if (id == tgt || mlLang(id) == tgt) { out.append(ln); return@forEachIndexed }
            // Español (o parecido: gallego, catalán, portugués, italiano), o no se sabe: se traduce
            if (id in setOf("es", "und", "gl", "ca", "pt", "it")) out.append(line(ln, tr, key)) else out.append(ln)
        }
        return out.toString()
    }

    /**
     * 🌍 De tu idioma ([fromCode]) al español: para que la app entienda tus respuestas cortas
     * («just chatting» → «solo conversando», «search it» → «búscalo»).
     */
    fun toSpanish(text: String, fromCode: String?): String {
        val src = mlLang(fromCode) ?: return text
        if (src == TranslateLanguage.SPANISH || text.isBlank()) return text
        val tr = translator(src, TranslateLanguage.SPANISH, null) ?: return text
        return line(text, tr, "$src>es")
    }
}

