package com.paulrj.cumulusia.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 🎬☁️ VIDEO DESDE UNA IMAGEN con Gemini Omni Flash (Google), usando la clave de Gemini del usuario.
 *
 * - Se usa la MISMA clave de Gemini que ☁️ IA en la nube (no hace falta que la nube esté encendida).
 * - Google cobra cada video en la cuenta del usuario (Omni no tiene uso gratis). Los videos traen sonido
 *   y la marca de agua invisible SynthID de Google.
 * - Conexión: «Interactions API» de Gemini (POST /v1beta/interactions). Si Google responde que el video sigue
 *   haciéndose, se le vuelve a preguntar cada pocos segundos hasta que esté listo.
 * - Veo 3.1 (preview) se apagó en la API de Gemini el 22-oct-2026: Google recomienda Gemini Omni Flash.
 * - Este archivo no toca el modelo del teléfono, ni NPU/GPU/CPU, ni la unión de videos.
 */
object CloudVideo {

    const val MODEL = "gemini-omni-1.1-flash"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"

    /** Precio aproximado (para avisar antes de crear): US$ por segundo a 720p. Los clips duran de 3 a 10 s. */
    const val USD_PER_SECOND = 0.10

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.MINUTES)      // (Google puede tardar varios minutos en crear el video)
            .writeTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    @Volatile private var current: Call? = null
    @Volatile private var cancelled = false

    /** ¿Hay una clave de Gemini guardada? */
    fun hasKey(ctx: Context): Boolean = CloudAi.apiKey(ctx, CloudAi.Provider.GEMINI).isNotBlank()

    /** ⏹️ Detener: corta la espera. */
    fun cancel() {
        cancelled = true
        runCatching { current?.cancel() }
    }

    /**
     * Crea el video a partir de [image] con lo que pediste en [request] y lo guarda en [out] (MP4).
     * Sin imagen ([image] = null) lo crea solo con el texto («un avión volando sobre el mar»), vertical para el teléfono.
     * [onStatus] recibe textos cortos de avance (en español: la app los muestra en tu idioma).
     * Si algo falla lanza [CloudAi.CloudError] con el motivo explicado.
     */
    fun create(ctx: Context, image: File?, request: String, out: File, onStatus: (String) -> Unit) {
        cancelled = false
        val key = CloudAi.apiKey(ctx, CloudAi.Provider.GEMINI)
        if (key.isBlank()) throw CloudAi.CloudError("Falta tu clave de Gemini (☁️ IA en la nube → Gemini).")
        val enc = image?.let { encodeImage(it) ?: throw CloudAi.CloudError("No pude leer la imagen.") }
        val body = JSONObject().apply {
            put("model", MODEL)
            if (enc != null) {
                val (mime, b64, _) = enc
                put("input", JSONArray().apply {
                    put(JSONObject().put("type", "image").put("data", b64).put("mime_type", mime))
                    put(JSONObject().put("type", "text").put("text", buildPrompt(request)))
                })
                put("generation_config", JSONObject().put("video_config", JSONObject().put("task", "image_to_video")))
            } else {
                // 🎬 Solo con texto: lo que pediste, tal cual (Omni entiende muchos idiomas)
                put("input", request.trim())
            }
            put("response_format", JSONObject().put("type", "video")
                .put("aspect_ratio", if (enc == null || enc.third) "9:16" else "16:9").put("resolution", "720p"))
        }
        onStatus(if (enc != null) "☁️ Enviando la imagen a Google (Gemini Omni)…" else "☁️ Enviando tu pedido a Google (Gemini Omni)…")
        var json = send(Request.Builder().url(BASE + "interactions")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        var video = findVideo(json)
        if (video == null) {
            // ⏳ Sigue haciéndose: se pregunta cada 6 s (máx. 10 min)
            val id = json.optString("id").ifBlank { json.optString("name") }
            if (id.isBlank()) throw CloudAi.CloudError(noVideoReason(json))
            val start = System.currentTimeMillis()
            while (video == null) {
                checkFinished(json)
                if (System.currentTimeMillis() - start > 10 * 60_000L)
                    throw CloudAi.CloudError("Google tardó demasiado en crear el video. Inténtalo de nuevo en un rato.")
                val secs = (System.currentTimeMillis() - start) / 1000
                onStatus("🎬 Google está creando tu video… ${secs / 60}:${"%02d".format(secs % 60)} min")
                sleep(6_000)
                val path = if (id.startsWith("interactions/")) id else "interactions/$id"
                json = send(Request.Builder().url(BASE + path).header("x-goog-api-key", key).get().build())
                video = findVideo(json)
            }
        }
        onStatus("⬇️ Bajando el video…")
        val data = video.optString("data")
        if (data.isNotBlank()) {
            out.writeBytes(Base64.decode(data, Base64.DEFAULT))
        } else {
            val uri = video.optString("uri")
            val url = when {
                uri.startsWith("http") -> uri
                uri.startsWith("files/") -> BASE + uri + ":download?alt=media"
                else -> BASE + "files/" + uri.substringAfterLast('/') + ":download?alt=media"
            }
            download(url, key, out)
        }
        if (!out.isFile || out.length() < 1000) throw CloudAi.CloudError("Google no devolvió un video válido.")
    }

    /** Lo que se le pide a Google: animar ESTA imagen (mismo personaje y estilo) + lo que escribiste. */
    private fun buildPrompt(request: String): String {
        val r = request.trim()
        return "Animate this exact image into a short video clip. Keep the same character(s), face, colors, art style " +
            "and composition as the image; add natural, smooth motion. " +
            (if (r.isNotEmpty()) "User request (may be in any language): $r" else "")
    }

    private fun send(req: Request): JSONObject {
        if (cancelled) throw CloudAi.CloudError("Detenido.")
        val call = http.newCall(req)
        current = call
        try {
            call.execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudAi.CloudError(friendly(resp.code, errorDetail(raw)))
                return runCatching { JSONObject(raw) }.getOrElse { throw CloudAi.CloudError("Google respondió algo que no entiendo.") }
            }
        } catch (e: CloudAi.CloudError) {
            throw e
        } catch (e: Exception) {
            if (cancelled) throw CloudAi.CloudError("Detenido.")
            throw CloudAi.CloudError("No pude conectarme con Google. Revisa tu internet e inténtalo de nuevo.")
        } finally { current = null }
    }

    private fun download(url: String, key: String, out: File) {
        val call = http.newCall(Request.Builder().url(url).header("x-goog-api-key", key).get().build())
        current = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw CloudAi.CloudError("No pude bajar el video (${resp.code}).")
                resp.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: CloudAi.CloudError) { throw e }
        catch (e: Exception) { throw CloudAi.CloudError(if (cancelled) "Detenido." else "No pude bajar el video. Revisa tu internet.") }
        finally { current = null }
    }

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && !cancelled) { Thread.sleep(minOf(500L, left)); left -= 500 }
        if (cancelled) throw CloudAi.CloudError("Detenido.")
    }

    /** Busca el video en la respuesta (sea donde sea que venga: «steps», «outputs»…). */
    private fun findVideo(any: Any?): JSONObject? {
        when (any) {
            is JSONObject -> {
                val type = any.optString("type")
                val mime = any.optString("mime_type").ifBlank { any.optString("mimeType") }
                if ((type == "video" || mime.startsWith("video/")) && (any.optString("data").isNotBlank() || any.optString("uri").isNotBlank()))
                    return any
                val keys = any.keys()
                while (keys.hasNext()) { val k = keys.next(); if (k == "input") continue; findVideo(any.opt(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until any.length()) findVideo(any.opt(i))?.let { return it }
        }
        return null
    }

    /** Si Google terminó SIN video (bloqueado o con error), se explica por qué. */
    private fun checkFinished(json: JSONObject) {
        val st = json.optString("status").lowercase()
        if (json.optJSONObject("error") != null || st in setOf("failed", "cancelled", "canceled", "incomplete", "completed"))
            throw CloudAi.CloudError(noVideoReason(json))
    }

    private fun noVideoReason(json: JSONObject): String {
        val e = json.optJSONObject("error")?.optString("message").orEmpty()
        return "Google no creó el video. Puede que la imagen o el pedido no estén permitidos (por ejemplo, fotos con niños, " +
            "personas famosas o contenido sensible)." + (if (e.isNotBlank()) "\n(${e.take(200)})" else "")
    }

    private fun errorDetail(raw: String): String = runCatching {
        val j = JSONObject(raw)
        j.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: j.optString("message").ifBlank { raw }
    }.getOrDefault(raw).trim().take(220)

    private fun friendly(code: Int, detail: String): String {
        val why = when (code) {
            400 -> "Google no aceptó el pedido del video."
            401, 403 -> "La clave de Gemini no es válida, o tu cuenta de Google todavía no tiene activada la facturación " +
                "(los videos no tienen uso gratis)."
            404 -> "Tu clave de Gemini no tiene acceso al modelo de video ($MODEL)."
            429 -> "Llegaste al límite de uso de Google (o se acabó el saldo). Espera un rato o revisa tu cuenta."
            in 500..599 -> "Google está con problemas en este momento. Inténtalo en un rato."
            else -> "Google respondió con un error ($code)."
        }
        return if (detail.isBlank()) why else "$why\n($detail)"
    }

    /** La imagen achicada (lado mayor 1280 px, JPEG) y si es vertical. */
    private fun encodeImage(f: File): Triple<String, String, Boolean>? {
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        if (big <= 0) return null
        var sample = 1
        while (big / (sample * 2) >= 1280) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val portrait = bmp.height > bmp.width
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val img = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val bytes = ByteArrayOutputStream().use { s -> img.compress(Bitmap.CompressFormat.JPEG, 90, s); s.toByteArray() }
        if (img !== bmp) img.recycle()
        bmp.recycle()
        return Triple("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), portrait)
    }
}
