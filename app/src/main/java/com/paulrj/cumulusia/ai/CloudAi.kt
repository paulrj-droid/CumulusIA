package com.paulrj.cumulusia.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ☁️ IA EN LA NUBE (con la clave de cada usuario).
 *
 * Un solo conector para varios servicios: casi todos hablan el mismo «idioma» (el de OpenAI: /chat/completions).
 * Claude (Anthropic) va con su conexión oficial (/v1/messages), que es la más completa.
 *
 * - La app le manda lo MISMO que al modelo del teléfono: instrucciones (modo, personalidad, avatar…), la conversación
 *   y tu mensaje (con los datos de internet si los hay). Así todo lo demás sigue funcionando igual.
 * - La clave se guarda CIFRADA en el teléfono (llave del sistema Android, que no sale del teléfono).
 * - Este archivo no toca el modelo del teléfono, ni NPU/GPU/CPU, ni las voces.
 */
object CloudAi {

    private const val PREFS = "CumulusCloudAi"

    /** El tipo de conexión de cada servicio. */
    enum class Kind { ANTHROPIC, OPENAI }

    /** Los servicios de la lista (el nombre no se traduce: es el nombre propio de cada uno). */
    enum class Provider(val key: String, val label: String, val baseUrl: String, val kind: Kind, val keyUrl: String) {
        CLAUDE("claude", "Claude", "https://api.anthropic.com/v1/", Kind.ANTHROPIC, "console.anthropic.com"),
        GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", Kind.OPENAI, "aistudio.google.com"),
        CHATGPT("chatgpt", "ChatGPT", "https://api.openai.com/v1/", Kind.OPENAI, "platform.openai.com"),
        OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/", Kind.OPENAI, "openrouter.ai"),
        GROQ("groq", "Groq", "https://api.groq.com/openai/v1/", Kind.OPENAI, "console.groq.com"),
        DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1/", Kind.OPENAI, "platform.deepseek.com"),
        MISTRAL("mistral", "Mistral", "https://api.mistral.ai/v1/", Kind.OPENAI, "console.mistral.ai"),
        CUSTOM("custom", "Personalizado", "", Kind.OPENAI, "");

        companion object {
            fun of(key: String?): Provider = values().firstOrNull { it.key == key } ?: CLAUDE
        }
    }

    /** Un problema con la nube, explicado para el usuario (en español: la app lo muestra en tu idioma). */
    class CloudError(message: String) : Exception(message)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // La llamada en curso (⏹️ detener la corta al instante)
    @Volatile private var current: Call? = null
    @Volatile private var cancelled = false

    // ------------------------------------------------------------------ ajustes guardados
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply() }

    fun provider(ctx: Context): Provider = Provider.of(prefs(ctx).getString("provider", Provider.CLAUDE.key))
    fun setProvider(ctx: Context, p: Provider) { prefs(ctx).edit().putString("provider", p.key).apply() }

    fun model(ctx: Context, p: Provider = provider(ctx)): String = prefs(ctx).getString("model_${p.key}", "").orEmpty()
    fun setModel(ctx: Context, p: Provider, model: String) { prefs(ctx).edit().putString("model_${p.key}", model.trim()).apply() }

    fun customUrl(ctx: Context): String = prefs(ctx).getString("custom_url", "").orEmpty()
    fun setCustomUrl(ctx: Context, url: String) { prefs(ctx).edit().putString("custom_url", url.trim()).apply() }

    fun apiKey(ctx: Context, p: Provider = provider(ctx)): String =
        prefs(ctx).getString("key_${p.key}", null)?.let { KeyVault.decrypt(it) }.orEmpty()

    fun setApiKey(ctx: Context, p: Provider, key: String) {
        val k = key.trim()
        prefs(ctx).edit().apply {
            if (k.isEmpty()) remove("key_${p.key}") else putString("key_${p.key}", KeyVault.encrypt(k))
        }.apply()
    }

    /** true = ya aceptaste el aviso de que tus mensajes salen del teléfono. */
    fun privacyAccepted(ctx: Context): Boolean = prefs(ctx).getBoolean("privacy_ok", false)
    fun setPrivacyAccepted(ctx: Context) { prefs(ctx).edit().putBoolean("privacy_ok", true).apply() }

    /** La dirección del servicio (la de «Personalizado» la escribes tú). */
    fun baseUrl(ctx: Context, p: Provider = provider(ctx)): String {
        val raw = if (p == Provider.CUSTOM) customUrl(ctx) else p.baseUrl
        if (raw.isBlank()) return ""
        var u = raw.trim()
        // (si pegaste la dirección completa, se deja solo la base)
        u = u.replace(Regex("/chat/completions/?$"), "/")
        return if (u.endsWith("/")) u else "$u/"
    }

    /** ☁️ Lista para usar: interruptor encendido, con clave, modelo (y dirección en «Personalizado»). */
    fun isReady(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        val p = provider(ctx)
        if (model(ctx, p).isBlank()) return false
        if (p == Provider.CUSTOM) return baseUrl(ctx, p).startsWith("http")
        return apiKey(ctx, p).isNotBlank()
    }

    /** Qué se está usando (para la ventana de actividad): «Claude · claude-sonnet-5-5». */
    fun describe(ctx: Context): String {
        val p = provider(ctx)
        return "${p.label} · ${model(ctx, p).ifBlank { "?" }}"
    }

    /** 📶 ¿Hay conexión a internet ahora mismo? */
    fun hasNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** ⏹️ Corta la respuesta que está llegando. */
    fun cancel() {
        cancelled = true
        runCatching { current?.cancel() }
    }

    // ------------------------------------------------------------------ conversación
    /**
     * Le manda a la nube las instrucciones, la conversación (roles «user» / «assistant») y las fotos del último mensaje.
     * [onPartial] recibe lo que lleva escrito (texto completo hasta ahora) mientras llega.
     * Devuelve la respuesta completa. Si algo falla, lanza [CloudError] con el motivo explicado.
     */
    fun chat(
        ctx: Context,
        system: String,
        roles: List<String>,
        contents: List<String>,
        images: List<File> = emptyList(),
        maxTokens: Int = 4096,
        temperature: Float = 0.7f,
        onPartial: ((String) -> Unit)? = null
    ): String {
        val p = provider(ctx)
        val model = model(ctx, p)
        val key = apiKey(ctx, p)
        val base = baseUrl(ctx, p)
        if (model.isBlank()) throw CloudError("Elige un modelo para ${p.label} en ☰ → IA en la nube.")
        if (p != Provider.CUSTOM && key.isBlank()) throw CloudError("Falta la clave (API key) de ${p.label} en ☰ → IA en la nube.")
        if (!base.startsWith("http")) throw CloudError("Falta la dirección del servicio en ☰ → IA en la nube.")
        cancelled = false
        val msgs = cleanTurns(roles, contents)
        if (msgs.isEmpty()) return ""
        val pics = images.mapNotNull { runCatching { imageForCloud(it) }.getOrNull() }
        return if (p.kind == Kind.ANTHROPIC)
            chatAnthropic(base, key, model, system, msgs, pics, maxTokens, temperature, onPartial)
        else
            chatOpenAi(p, base, key, model, system, msgs, pics, maxTokens, temperature, onPartial)
    }

    /**
     * Ordena la conversación como la piden los servicios: empieza con el usuario, se van turnando
     * (dos seguidos del mismo se juntan) y sin mensajes vacíos.
     */
    private fun cleanTurns(roles: List<String>, contents: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in roles.indices) {
            val text = contents.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val role = if (roles[i] == "user") "user" else "assistant"
            if (out.isEmpty() && role != "user") continue
            if (out.isNotEmpty() && out.last().first == role) {
                out[out.size - 1] = role to (out.last().second + "\n\n" + text)
            } else out.add(role to text)
        }
        // (el último tiene que ser tuyo)
        while (out.isNotEmpty() && out.last().first != "user") out.removeAt(out.size - 1)
        return out
    }

    /** 🖼️ La foto achicada (lado mayor 1280 px, JPEG): sube rápido y gasta menos. */
    private fun imageForCloud(f: File): Pair<String, String>? {
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        if (big <= 0) return null
        var sample = 1
        while (big / (sample * 2) >= 1280) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, 85, s); s.toByteArray() }
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return "image/jpeg" to Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ Claude (Anthropic)
    private fun chatAnthropic(
        base: String, key: String, model: String, system: String, msgs: List<Pair<String, String>>,
        pics: List<Pair<String, String>>, maxTokens: Int, temperature: Float, onPartial: ((String) -> Unit)?
    ): String {
        fun body(withTemp: Boolean) = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            if (withTemp) put("temperature", temperature.coerceIn(0f, 1f).toDouble())
            put("stream", true)
            put("messages", JSONArray().apply {
                msgs.forEachIndexed { i, (role, text) ->
                    val last = i == msgs.size - 1
                    put(JSONObject().apply {
                        put("role", role)
                        if (last && pics.isNotEmpty()) {
                            put("content", JSONArray().apply {
                                pics.forEach { (mime, b64) ->
                                    put(JSONObject().put("type", "image").put("source",
                                        JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)))
                                }
                                put(JSONObject().put("type", "text").put("text", text))
                            })
                        } else put("content", text)
                    })
                }
            })
        }
        fun request(b: JSONObject) = Request.Builder()
            .url(base + "messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(b.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            stream(request(body(true)), Provider.CLAUDE, model, onPartial, ::anthropicDelta)
        } catch (e: RetryWithout) {
            stream(request(body(false)), Provider.CLAUDE, model, onPartial, ::anthropicDelta)
        }
    }

    private fun anthropicDelta(json: JSONObject): String? {
        if (json.optString("type") == "error") {
            throw CloudError("Claude: " + json.optJSONObject("error")?.optString("message").orEmpty().take(220))
        }
        if (json.optString("type") != "content_block_delta") return null
        val d = json.optJSONObject("delta") ?: return null
        return if (d.optString("type") == "text_delta") d.optString("text") else null
    }

    // ------------------------------------------------------------------ compatibles con OpenAI
    private fun chatOpenAi(
        p: Provider, base: String, key: String, model: String, system: String, msgs: List<Pair<String, String>>,
        pics: List<Pair<String, String>>, maxTokens: Int, temperature: Float, onPartial: ((String) -> Unit)?
    ): String {
        // (los modelos nuevos de OpenAI piden «max_completion_tokens»; los demás, «max_tokens»)
        fun body(withTemp: Boolean, newTokens: Boolean) = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put(if (newTokens) "max_completion_tokens" else "max_tokens", maxTokens)
            if (withTemp) put("temperature", temperature.coerceIn(0f, 1.5f).toDouble())
            put("messages", JSONArray().apply {
                if (system.isNotBlank()) put(JSONObject().put("role", "system").put("content", system))
                msgs.forEachIndexed { i, (role, text) ->
                    val last = i == msgs.size - 1
                    put(JSONObject().apply {
                        put("role", role)
                        if (last && pics.isNotEmpty()) {
                            put("content", JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", text))
                                pics.forEach { (mime, b64) ->
                                    put(JSONObject().put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
                                }
                            })
                        } else put("content", text)
                    })
                }
            })
        }
        fun request(b: JSONObject) = Request.Builder()
            .url(base + "chat/completions")
            .apply { if (key.isNotBlank()) addHeader("Authorization", "Bearer $key") }
            .apply {
                if (p == Provider.OPENROUTER) {
                    addHeader("HTTP-Referer", "https://cumulusia.app")
                    addHeader("X-Title", "Cumulus")
                }
            }
            .addHeader("Content-Type", "application/json")
            .post(b.toString().toRequestBody("application/json".toMediaType()))
            .build()
        var withTemp = true
        var newTokens = p == Provider.CHATGPT
        // (hasta 2 reintentos: sin temperatura y/o con el otro nombre del límite de palabras)
        repeat(2) {
            try {
                return stream(request(body(withTemp, newTokens)), p, model, onPartial, ::openAiDelta)
            } catch (e: RetryWithout) {
                if (e.what == "temperature") withTemp = false else newTokens = !newTokens
            }
        }
        return stream(request(body(withTemp, newTokens)), p, model, onPartial, ::openAiDelta)
    }

    private fun openAiDelta(json: JSONObject): String? {
        json.optJSONObject("error")?.let { throw CloudError(it.optString("message").take(220)) }
        val ch = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val d = ch.optJSONObject("delta") ?: return null
        if (d.isNull("content")) return null
        return d.optString("content")
    }

    // ------------------------------------------------------------------ lectura de la respuesta en vivo
    /** El servicio rechazó un ajuste («temperature» o el nombre del límite de palabras): se reintenta sin él. */
    private class RetryWithout(val what: String) : Exception(what)

    private fun stream(
        req: Request, p: Provider, model: String, onPartial: ((String) -> Unit)?, delta: (JSONObject) -> String?
    ): String {
        val call = http.newCall(req)
        current = call
        val sb = StringBuilder()
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    val detail = errorDetail(raw)
                    if (resp.code == 400) {
                        val low = detail.lowercase()
                        if (low.contains("temperature")) throw RetryWithout("temperature")
                        if (low.contains("max_tokens") || low.contains("max_completion_tokens")) throw RetryWithout("tokens")
                    }
                    throw CloudError(friendly(resp.code, detail, p, model))
                }
                val src = resp.body?.source() ?: throw CloudError("${p.label} no mandó respuesta.")
                var lastPush = 0L
                while (!cancelled) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val piece = delta(json) ?: continue
                    if (piece.isEmpty()) continue
                    sb.append(piece)
                    val now = System.currentTimeMillis()
                    if (onPartial != null && now - lastPush > 100) { lastPush = now; runCatching { onPartial(sb.toString()) } }
                }
            }
        } catch (e: CloudError) {
            throw e
        } catch (e: RetryWithout) {
            throw e
        } catch (t: Throwable) {
            if (cancelled) return sb.toString()
            if (sb.isNotEmpty()) return sb.toString()   // (se cortó a la mitad: se entrega lo que alcanzó a llegar)
            throw CloudError(
                if (t is java.net.UnknownHostException || t is java.net.ConnectException)
                    "No pude conectarme con ${p.label}: revisa tu conexión a internet."
                else if (t is java.net.SocketTimeoutException)
                    "${p.label} tardó demasiado en responder. Inténtalo de nuevo."
                else "No pude hablar con ${p.label}: ${t.message ?: t.javaClass.simpleName}"
            )
        } finally {
            if (current === call) current = null
        }
        onPartial?.let { cb -> runCatching { cb(sb.toString()) } }
        return sb.toString()
    }

    private fun errorDetail(raw: String): String = runCatching {
        val j = JSONObject(raw)
        val e = j.opt("error")
        when (e) {
            is JSONObject -> e.optString("message").ifBlank { e.toString() }
            is String -> e
            else -> j.optString("message").ifBlank { raw }
        }
    }.getOrDefault(raw).trim().take(220)

    /** El error explicado en simple (en español: la app lo traduce a tu idioma). */
    private fun friendly(code: Int, detail: String, p: Provider, model: String): String {
        val why = when (code) {
            401, 403 -> "La clave (API key) de ${p.label} no es válida o no tiene permiso."
            402 -> "Tu cuenta de ${p.label} no tiene saldo."
            404 -> "${p.label} no encontró el modelo «$model» (o la dirección del servicio no es correcta)."
            408, 504 -> "${p.label} tardó demasiado en responder. Inténtalo de nuevo."
            413 -> "El mensaje (o la foto) es demasiado grande para ${p.label}."
            429 -> "Llegaste al límite de uso de ${p.label} (o se acabó el saldo). Espera un rato o revisa tu plan."
            in 500..599 -> "${p.label} está con problemas en este momento. Inténtalo en un rato."
            else -> "${p.label} respondió con un error ($code)."
        }
        return if (detail.isBlank()) why else "$why\n($detail)"
    }

    // ------------------------------------------------------------------ modelos disponibles
    /** 📋 Los modelos que tu clave puede usar (para elegir uno de la lista). */
    fun listModels(ctx: Context, p: Provider, key: String, customUrl: String = ""): List<String> {
        val base = if (p == Provider.CUSTOM) {
            var u = customUrl.trim().replace(Regex("/chat/completions/?$"), "/")
            if (!u.endsWith("/")) u += "/"
            u
        } else p.baseUrl
        if (!base.startsWith("http")) throw CloudError("Escribe primero la dirección del servicio.")
        val req = Request.Builder()
            .url(base + if (p.kind == Kind.ANTHROPIC) "models?limit=100" else "models")
            .apply {
                if (p.kind == Kind.ANTHROPIC) {
                    addHeader("x-api-key", key.trim()); addHeader("anthropic-version", "2023-06-01")
                } else if (key.isNotBlank()) addHeader("Authorization", "Bearer ${key.trim()}")
            }
            .get().build()
        val raw = try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw CloudError(friendly(resp.code, errorDetail(body), p, "?"))
                body
            }
        } catch (e: CloudError) { throw e } catch (t: Throwable) {
            throw CloudError("No pude conectarme con ${p.label}: revisa tu conexión a internet.")
        }
        val arr = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
            ?: runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val skip = Regex("(?i)(embed|whisper|tts|dall-e|moderation|transcribe|realtime|audio|image|imagen|veo|aqa|guard|rerank|ocr)")
        val ids = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            (o?.optString("id") ?: arr.optString(i)).takeIf { !it.isNullOrBlank() }?.removePrefix("models/")
        }.filter { !skip.containsMatchIn(it) }.distinct()
        // ChatGPT: solo los de conversación
        val chatOnly = if (p == Provider.CHATGPT) ids.filter { Regex("^(gpt|o\\d|chatgpt)", RegexOption.IGNORE_CASE).containsMatchIn(it) } else ids
        return chatOnly.sortedWith(compareBy({ !it.contains(":free") }, { it.lowercase() }))
    }

    // ------------------------------------------------------------------ 🔐 clave cifrada
    /** Cifra la clave con una llave del sistema Android (AndroidKeyStore): la llave nunca sale del teléfono. */
    private object KeyVault {
        private const val ALIAS = "cumulus_cloud_ai_key"

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = c.iv
            val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String? = runCatching {
            val (ivB64, encB64) = stored.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
}
