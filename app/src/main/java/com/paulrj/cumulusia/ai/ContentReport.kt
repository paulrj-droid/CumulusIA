package com.paulrj.cumulusia.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 🚩 REPORTAR UNA RESPUESTA O IMAGEN DE LA IA (lo exige Google Play a las apps con IA generativa):
 * el usuario toca la banderita debajo de la respuesta, elige el motivo y la app te lo envía sin salir de la app.
 *
 * ✅ CÓMO RECIBIR LOS REPORTES (una sola vez, 5 minutos, desde el teléfono):
 *  1. Entra a forms.google.com y crea un formulario nuevo llamado «Reportes Cumulus IA».
 *  2. Agrega 5 preguntas de tipo «Respuesta corta» o «Párrafo»: Motivo, Comentario, Respuesta, Pregunta y Detalles.
 *  3. Toca ⋮ (arriba a la derecha) → «Obtener enlace rellenado previamente».
 *  4. Escribe como respuesta, en cada pregunta y en MAYÚSCULAS: MOTIVO, COMENTARIO, RESPUESTA, PREGUNTA y DETALLES.
 *  5. Toca «Obtener enlace» → «Copiar enlace» y pégalo abajo, entre las comillas de REPORT_FORM_LINK.
 * Los reportes te llegan a la pestaña «Respuestas» del formulario (y puedes verlos en una hoja de cálculo).
 * Mientras no pegues el enlace, los reportes se guardan en el teléfono y se envían solos cuando lo pegues.
 */
object ContentReport {

    /** 📝 Pega aquí tu «enlace rellenado previamente» del Formulario de Google (ver arriba). */
    const val REPORT_FORM_LINK = ""

    val REASONS = listOf(
        "😠 Ofensivo, odio o discriminación",
        "🔞 Sexual o inapropiado",
        "⚠️ Violento o peligroso",
        "❌ Información falsa o engañosa",
        "📝 Otro motivo"
    )

    private const val PREFS = "cumulus_reports"

    /** Identificador de un mensaje (para pintar la banderita en rojo cuando ya se reportó). */
    fun key(timestamp: Long, text: String): String = "${timestamp}_${text.hashCode()}"

    fun isReported(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("reported", emptySet())?.contains(key) == true

    fun markReported(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("reported", emptySet())?.toMutableSet() ?: mutableSetOf()
        set += key
        prefs.edit().putStringSet("reported", set.toList().takeLast(500).toSet()).apply()
    }

    /**
     * Arma el envío al formulario: «…/viewform?usp=pp_url&entry.1=MOTIVO&…» → («…/formResponse», «entry.1=Ofensivo&…»).
     * Null si el enlace no es de un Formulario de Google o no trae ninguna de las palabras clave.
     */
    fun formRequest(link: String, values: Map<String, String>): Pair<String, String>? {
        val l = link.trim()
        if (!l.startsWith("https://docs.google.com/forms/")) return null
        val base = l.substringBefore('?').replace(Regex("/(viewform|edit)$"), "/formResponse")
        if (!base.endsWith("/formResponse")) return null
        val params = l.substringAfter('?', "").split('&').mapNotNull { p ->
            if (!p.startsWith("entry.")) return@mapNotNull null
            val name = p.substringBefore('=')
            val placeholder = runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("").trim().uppercase()
            val v = values[placeholder] ?: return@mapNotNull null
            name + "=" + URLEncoder.encode(v, "UTF-8")
        }
        if (params.isEmpty()) return null
        return base to params.joinToString("&")
    }

    private fun post(url: String, body: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        code in 200..399
    }.getOrDefault(false)

    private fun pendingFile(context: Context) = File(context.filesDir, "reportes_pendientes.txt")

    private fun encodeLine(values: Map<String, String>): String =
        values.entries.joinToString("\t") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun decodeLine(line: String): Map<String, String> = line.split('\t').mapNotNull { part ->
        val k = part.substringBefore('=', "")
        if (k.isBlank()) null else k to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrDefault("")
    }.toMap()

    /**
     * Envía el reporte (llamar fuera del hilo principal). Si no se puede enviar ahora (sin enlace o sin internet),
     * queda guardado en el teléfono y se reintenta con el siguiente reporte. Devuelve true si llegó.
     */
    fun send(context: Context, reason: String, comment: String, reply: String, question: String, details: String): Boolean {
        val values = mapOf(
            "MOTIVO" to reason,
            "COMENTARIO" to comment.take(1000),
            "RESPUESTA" to reply.take(3000),
            "PREGUNTA" to question.take(1500),
            "DETALLES" to details.take(500)
        )
        // Primero los que quedaron pendientes
        val pending = pendingFile(context).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        val stillPending = mutableListOf<String>()
        pending.forEach { line ->
            val req = formRequest(REPORT_FORM_LINK, decodeLine(line))
            if (req == null || !post(req.first, req.second)) stillPending += line
        }
        val req = formRequest(REPORT_FORM_LINK, values)
        val ok = req != null && post(req.first, req.second)
        if (!ok) stillPending += encodeLine(values)
        runCatching {
            if (stillPending.isEmpty()) pendingFile(context).delete()
            else pendingFile(context).writeText(stillPending.takeLast(100).joinToString("\n"))
        }
        return ok
    }
}
