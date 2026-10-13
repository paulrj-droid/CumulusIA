package com.paulrj.cumulusia.model

import android.content.Context
import com.paulrj.cumulusia.ai.ChatTemplateFormatter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * 🧩 CONFIGURACIÓN POR MODELO
 * Cada GGUF fue entrenado con su propio formato de tokens y responde distinto a los ajustes,
 * así que cada modelo guarda lo suyo:
 *  - modelName     → nombre del archivo
 *  - templateType  → "detect" = la PLANTILLA PROPIA DEL GGUF (tokenizer.chat_template, en Jinja): el motor la
 *                    ejecuta tal cual, así cualquier familia usa sus etiquetas exactas. Si el GGUF no trae
 *                    plantilla, se usa la de su familia detectada por el nombre.
 *                    O una plantilla fija elegida por ti: "chatml", "llama3", "gemma", "phi3", "phi4", "mistral-v7", "deepseek3"
 *  - stopTokens    → tus stop tokens para ESTE modelo (además de los de su plantilla)
 *  - temperature   → la temperatura de ESTE modelo
 *  - systemPrompt  → instrucciones extra para ESTE modelo ("" = solo las del modo)
 * Y lo que se detecta solo leyendo el GGUF: plantilla, familia y tamaño (1B, 3B, 7B…).
 */
data class ModelConfig(
    val modelName: String,
    val templateType: String = DETECT,
    val stopTokens: List<String> = emptyList(),
    val temperature: Float = 0.7f,
    val systemPrompt: String = "",
    // 🔎 Detectado en el archivo GGUF
    val detectedTemplate: String? = null,
    val architecture: String = "",
    val paramsB: Float = 0f,
    val sizeLabel: String = "",
    /** Su plantilla permite apagar el pensamiento interno (Qwen3 y similares). */
    val thinkingSwitch: Boolean = false,
    /** El GGUF trae su propia plantilla (tokenizer.chat_template). */
    val hasEmbeddedTemplate: Boolean = false,
    /** Largo de esa plantilla (para mostrarlo en ⚙️). */
    val embeddedTemplateChars: Int = 0,
    /** Fin de turno que declara el propio GGUF (sus tokens EOS / EOT, por ejemplo <|im_end|>). */
    val modelStops: List<String> = emptyList()
) {
    /** Plantilla que se usa de verdad: la detectada, la del archivo («auto») o la que elegiste. */
    val effectiveTemplate: String
        get() = when (templateType) {
            // La plantilla propia del GGUF manda; sin plantilla, la de su familia
            DETECT -> if (hasEmbeddedTemplate) ChatTemplateFormatter.AUTO else (detectedTemplate ?: ChatTemplateFormatter.AUTO)
            ChatTemplateFormatter.AUTO -> ChatTemplateFormatter.AUTO
            else -> templateType
        }

    /** Stop tokens completos: los de la plantilla + los tuyos (sin repetir). */
    val effectiveStops: List<String>
        get() = (ChatTemplateFormatter.stopsFor(effectiveTemplate) + modelStops + stopTokens).filter { it.isNotEmpty() }.distinct()

    val sizeClass: SizeClass get() = SizeClass.of(paramsB)

    fun toJson(): JSONObject = JSONObject()
        .put("modelName", modelName)
        .put("templateType", templateType)
        .put("stopTokens", JSONArray(stopTokens))
        .put("temperature", temperature.toDouble())
        .put("systemPrompt", systemPrompt)
        .put("detectedTemplate", detectedTemplate ?: "")
        .put("architecture", architecture)
        .put("paramsB", paramsB.toDouble())
        .put("sizeLabel", sizeLabel)
        .put("thinkingSwitch", thinkingSwitch)
        .put("hasEmbeddedTemplate", hasEmbeddedTemplate)
        .put("embeddedTemplateChars", embeddedTemplateChars)
        .put("modelStops", JSONArray(modelStops))

    companion object {
        /** Usar la plantilla que se detectó en el GGUF (lo recomendado). */
        const val DETECT = "detect"

        fun fromJson(o: JSONObject): ModelConfig = ModelConfig(
            modelName = o.optString("modelName"),
            templateType = o.optString("templateType", DETECT).ifBlank { DETECT },
            stopTokens = o.optJSONArray("stopTokens")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList(),
            temperature = o.optDouble("temperature", 0.7).toFloat(),
            systemPrompt = o.optString("systemPrompt"),
            detectedTemplate = o.optString("detectedTemplate").ifBlank { null },
            architecture = o.optString("architecture"),
            paramsB = o.optDouble("paramsB", 0.0).toFloat(),
            sizeLabel = o.optString("sizeLabel"),
            thinkingSwitch = o.optBoolean("thinkingSwitch", false),
            hasEmbeddedTemplate = o.optBoolean("hasEmbeddedTemplate", false),
            embeddedTemplateChars = o.optInt("embeddedTemplateChars", 0),
            modelStops = o.optJSONArray("modelStops")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
        )
    }
}

/** 📏 Tamaño del modelo: decide qué tan largas y estructuradas son las instrucciones del sistema. */
enum class SizeClass(val label: String) {
    SMALL("pequeño"),     // hasta ~3B: instrucciones cortas y directas
    MEDIUM("mediano"),    // ~4B a 6B: instrucciones normales
    LARGE("grande"),      // 7B o más: instrucciones estructuradas
    UNKNOWN("tamaño desconocido");

    companion object {
        fun of(paramsB: Float): SizeClass = when {
            paramsB <= 0f -> UNKNOWN
            paramsB <= 3.5f -> SMALL
            paramsB < 6.5f -> MEDIUM
            else -> LARGE
        }
    }
}

/** 🗂️ Guarda y entrega la configuración de cada modelo (en la memoria privada de la app). */
object ModelConfigStore {
    private const val PREFS = "CumulusModelConfigs"

    private fun key(file: File) = "cfg2_" + file.name + "_" + file.length()
    /** Clave de la versión anterior (sin la plantilla propia del GGUF): se migra una sola vez. */
    private fun oldKey(file: File) = "cfg_" + file.name + "_" + file.length()

    /**
     * La configuración de este modelo. La primera vez se crea leyendo el GGUF (puede tardar 1-2 s: llamar en
     * segundo plano) y parte con lo que tenías en ⚙️ (temperatura, stop tokens y plantilla, si elegiste una).
     */
    fun load(context: Context, file: File, defaultTemperature: Float, defaultStops: List<String> = emptyList(),
             defaultTemplate: String = ModelConfig.DETECT): ModelConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key(file), null)?.let { raw ->
            runCatching { return ModelConfig.fromJson(JSONObject(raw)) }
        }
        val detected = ModelConfigDetector.detect(file, defaultTemperature)
        // ♻️ Si ya lo tenías configurado (versión anterior de la app), se conservan TUS ajustes
        //    y se vuelve a leer el GGUF para tomar su plantilla propia y sus fines de turno.
        val old = prefs.getString(oldKey(file), null)?.let { runCatching { ModelConfig.fromJson(JSONObject(it)) }.getOrNull() }
        val cfg = if (old != null) detected.copy(
            templateType = if (ChatTemplateFormatter.isKnown(old.templateType)) old.templateType else ModelConfig.DETECT,
            stopTokens = old.stopTokens,
            temperature = old.temperature,
            systemPrompt = old.systemPrompt
        ) else detected.copy(
            stopTokens = defaultStops,
            templateType = if (ChatTemplateFormatter.isKnown(defaultTemplate)) defaultTemplate else ModelConfig.DETECT
        )
        save(context, file, cfg)
        if (old != null) prefs.edit().remove(oldKey(file)).apply()
        return cfg
    }

    /** Solo lo guardado (sin leer el archivo): para mostrar al tiro en ⚙️. */
    fun cached(context: Context, file: File): ModelConfig? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(file), null)
            ?.let { runCatching { ModelConfig.fromJson(JSONObject(it)) }.getOrNull() }

    fun save(context: Context, file: File, cfg: ModelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(file), cfg.toJson().toString()).apply()
    }
}

/** 🔎 Lee el GGUF (solo la cabecera) y arma la configuración inicial del modelo. */
object ModelConfigDetector {

    fun detect(file: File, defaultTemperature: Float): ModelConfig {
        val meta = if (file.extension.equals("gguf", true)) runCatching { GgufMetadata.read(file) }.getOrNull() else null
        val name = meta?.string("general.name").orEmpty().ifBlank { file.nameWithoutExtension }
        val arch = meta?.string("general.architecture").orEmpty()
        val tmpl = meta?.string("tokenizer.chat_template")
        val sizeLabel = meta?.string("general.size_label").orEmpty()
        val params = paramsFrom(sizeLabel).takeIf { it > 0f }
            ?: paramsFrom(file.name).takeIf { it > 0f }
            ?: paramsFrom(name).takeIf { it > 0f }
            ?: estimateParams(file.length(), meta?.int("general.file_type"))
        return ModelConfig(
            modelName = file.name,
            templateType = ModelConfig.DETECT,
            temperature = defaultTemperature,
            detectedTemplate = ChatTemplateFormatter.detect(tmpl, arch, name + " " + file.name),
            architecture = arch,
            paramsB = params,
            sizeLabel = sizeLabel.ifBlank { if (params > 0f) formatParams(params) else "" },
            thinkingSwitch = tmpl?.contains("enable_thinking") == true,
            hasEmbeddedTemplate = !tmpl.isNullOrBlank(),
            embeddedTemplateChars = tmpl?.length ?: 0,
            modelStops = listOfNotNull(
                meta?.int("tokenizer.ggml.eot_token_id")?.let { meta.tokenText(it) },
                meta?.int("tokenizer.ggml.eos_token_id")?.let { meta.tokenText(it) },
                meta?.int("tokenizer.ggml.eom_token_id")?.let { meta.tokenText(it) }
            ).filter { it.length in 3..40 }.distinct()
        )
    }

    /** «3B», «1.5B», «0.5b», «8x7B», «270M» → miles de millones de parámetros. */
    fun paramsFrom(text: String): Float {
        val t = text.lowercase()
        Regex("(\\d+)x(\\d+(?:\\.\\d+)?)b").find(t)?.let { m ->
            return m.groupValues[1].toFloat() * m.groupValues[2].toFloat()
        }
        Regex("(?<![a-z0-9.])(\\d+(?:[.,]\\d+)?)\\s?b(?![a-z])").find(t)?.let { m ->
            return m.groupValues[1].replace(',', '.').toFloatOrNull() ?: 0f
        }
        Regex("(?<![a-z0-9.])(\\d+(?:[.,]\\d+)?)\\s?m(?![a-z])").find(t)?.let { m ->
            return (m.groupValues[1].replace(',', '.').toFloatOrNull() ?: 0f) / 1000f
        }
        return 0f
    }

    /** Si el nombre no dice el tamaño: se calcula con el peso del archivo y el tipo de cuantización. */
    fun estimateParams(bytes: Long, fileType: Int?): Float {
        val bitsPerWeight = when (fileType) {
            0 -> 32f; 1, 32 -> 16f; 2 -> 4.5f; 3 -> 5f; 7 -> 8.5f; 8 -> 5.5f; 9 -> 6f
            10 -> 2.6f; 11 -> 3.4f; 12 -> 3.9f; 13 -> 4.3f; 14 -> 4.6f; 15 -> 4.85f
            16 -> 5.5f; 17 -> 5.7f; 18 -> 6.6f
            else -> 4.85f
        }
        if (bytes <= 0) return 0f
        return (bytes * 8f / bitsPerWeight / 1e9f)
    }

    fun formatParams(p: Float): String = when {
        p < 1f -> "${(p * 1000).toInt()}M"
        p < 10f -> String.format(java.util.Locale.US, "%.1fB", p).replace(".0B", "B")
        else -> "${p.toInt()}B"
    }
}

/**
 * 📖 Lector mínimo de la cabecera GGUF (formato v2/v3): saca los textos y números de los metadatos y
 * salta los arreglos grandes (como las 150 mil palabras del vocabulario) sin cargarlos en memoria.
 */
class GgufMetadata private constructor(private val values: Map<String, Any>, private val specialTokens: Map<Int, String>) {

    fun string(key: String): String? = values[key] as? String
    fun int(key: String): Int? = (values[key] as? Number)?.toInt()
    /** Texto de un token especial del vocabulario (por ejemplo el EOS «<|im_end|>»). */
    fun tokenText(id: Int): String? = specialTokens[id]

    companion object {
        private const val MAX_STRING = 256 * 1024      // las plantillas de chat caben de sobra

        fun read(file: File): GgufMetadata {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                val r = LeReader(input)
                val magic = r.u32()
                if (magic != 0x46554747L) error("no es un GGUF")   // "GGUF" en little-endian
                val version = r.u32()
                if (version < 2) error("GGUF v$version no soportado")
                r.u64()                                           // tensores
                val kvCount = r.u64()
                val out = HashMap<String, Any>()
                val special = HashMap<Int, String>()
                for (i in 0 until kvCount.coerceAtMost(100_000)) {
                    val key = r.string(4096) ?: break
                    val type = r.u32().toInt()
                    // Vocabulario: se guardan solo los tokens con forma de etiqueta (<|im_end|>, <end_of_turn>, [INST]…)
                    val v = if (key == "tokenizer.ggml.tokens" && type == 9) { r.specialTokens(special); null } else r.value(type)
                    if (v != null) out[key] = v
                }
                return GgufMetadata(out, special)
            }
        }
    }

    private class LeReader(private val inp: DataInputStream) {
        private val b8 = ByteArray(8)
        private fun fill(n: Int) { inp.readFully(b8, 0, n) }
        fun u32(): Long { fill(4); return (b8[0].toLong() and 0xFF) or ((b8[1].toLong() and 0xFF) shl 8) or ((b8[2].toLong() and 0xFF) shl 16) or ((b8[3].toLong() and 0xFF) shl 24) }
        fun u64(): Long { fill(8); var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b8[i].toLong() and 0xFF); return v }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = inp.skip(left)
                if (s <= 0) { if (inp.read() < 0) throw EOFException(); left-- } else left -= s
            }
        }
        /** Texto (o null si es demasiado largo: se salta). */
        fun string(max: Int = MAX_STRING): String? {
            val len = u64()
            if (len < 0 || len > max) { skip(len); return null }
            val buf = ByteArray(len.toInt()); inp.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
        /** Recorre el arreglo de tokens y guarda los que parecen etiquetas especiales. */
        fun specialTokens(into: MutableMap<Int, String>) {
            val et = u32().toInt(); val n = u64()
            if (et != 8) { skipArray(et, n); return }
            for (i in 0 until n) {
                val len = u64()
                if (len in 3..48) {
                    val buf = ByteArray(len.toInt()); inp.readFully(buf)
                    val t = String(buf, Charsets.UTF_8)
                    if ((t.startsWith("<") && t.endsWith(">")) || (t.startsWith("[") && t.endsWith("]"))) {
                        if (i <= Int.MAX_VALUE) into[i.toInt()] = t
                    }
                } else skip(len)
            }
        }
        private fun skipArray(et: Int, n: Long) {
            val size = when (et) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> -1 }
            if (size > 0) skip(n * size) else if (et == 8) { for (i in 0 until n) skip(u64()) } else error("arreglo raro")
        }
        fun value(type: Int): Any? = when (type) {
            0, 1, 7 -> { fill(1); b8[0].toInt() }
            2, 3 -> { fill(2); (b8[0].toInt() and 0xFF) or ((b8[1].toInt() and 0xFF) shl 8) }
            4, 5 -> u32().toInt()
            6 -> java.lang.Float.intBitsToFloat(u32().toInt())
            8 -> string()
            9 -> {   // arreglo: se salta entero
                val et = u32().toInt(); val n = u64()
                skipArray(et, n)
                null
            }
            10, 11 -> u64()
            12 -> java.lang.Double.longBitsToDouble(u64())
            else -> error("tipo $type desconocido")
        }
    }
}

