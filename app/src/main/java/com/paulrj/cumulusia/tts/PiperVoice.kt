package com.paulrj.cumulusia.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 🗣️ VOCES PIPER (.onnx) CON EL MOTOR SHERPA-ONNX, dentro del teléfono y sin internet.
 *
 * Una voz Piper trae dos archivos: «voz.onnx» y «voz.onnx.json» (su configuración). Para que sherpa-onnx la pueda
 * usar, la primera vez se prepara sola:
 *  1. «voz.onnx.tokens.txt»: la lista de sonidos de la voz, armada desde su .json;
 *  2. los datos de la voz (frecuencia, idioma…) se le agregan al final del .onnx (así lo pide sherpa-onnx);
 *  3. los datos de pronunciación «espeak-ng-data» (los mismos para todas las voces) se bajan UNA sola vez.
 * Las voces que ya vienen preparadas para sherpa-onnx (con su tokens.txt y espeak-ng-data al lado) se usan tal cual.
 *
 * ⚠️ Todo esto se llama desde un hilo de fondo.
 */
object PiperVoice {
    private const val TAG = "PiperVoice"

    /** Datos de pronunciación de sherpa-onnx (sirven para todas las voces Piper, en cualquier idioma). */
    private const val ESPEAK_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

    /** Un problema con la voz, explicado para el usuario (en español; la app lo muestra en tu idioma). */
    class VoiceProblem(message: String) : Exception(message)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    // El motor cargado (cargar una voz tarda un poco: se reutiliza mientras sea la misma)
    private var engine: OfflineTts? = null
    private var engineKey: String = ""

    /** Archivo de configuración de la voz («voz.onnx.json» o «voz.json»), si está. */
    fun configFor(model: File): File? = listOf(
        File(model.path + ".json"),
        File(model.parentFile, model.nameWithoutExtension + ".json")
    ).firstOrNull { it.isFile && it.length() > 0 }

    /**
     * 🌐 Baja la configuración «.onnx.json» de una voz bajada por URL (está en la misma dirección + «.json»).
     * true = quedó guardada al lado de la voz.
     */
    fun fetchConfigFor(model: File, modelUrl: String): Boolean = runCatching {
        if (configFor(model) != null) return true
        var url = modelUrl.trim()
        if (url.contains("huggingface.co") && url.contains("/blob/")) url = url.replace("/blob/", "/resolve/")
        val query = url.substringAfter('?', "")
        val jsonUrl = url.substringBefore('?') + ".json" + (if (query.isNotEmpty()) "?$query" else "")
        http.newCall(Request.Builder().url(jsonUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return false
            val text = r.body?.string().orEmpty()
            if (!text.trimStart().startsWith("{") || !text.contains("phoneme_id_map")) return false
            File(model.path + ".json").writeText(text)
        }
        true
    }.onFailure { Log.w(TAG, "No se pudo bajar el .json de ${model.name}", it) }.getOrDefault(false)

    /**
     * 🦙 El motor listo para hablar con esta voz (la prepara la primera vez). [status] = qué está haciendo
     * («Preparando la voz…», «Bajando los datos de pronunciación…»). Si no se puede, lanza [VoiceProblem].
     */
    @Synchronized
    fun engineFor(context: Context, model: File, status: ((String) -> Unit)? = null): OfflineTts {
        if (!model.isFile) throw VoiceProblem("No encuentro el archivo de la voz «${model.name}». Bájala o súbela de nuevo.")
        if (!model.name.endsWith(".onnx", ignoreCase = true))
            throw VoiceProblem("«${model.name}» no es una voz Piper (.onnx). Por ahora el motor de voces del teléfono usa voces .onnx.")
        val key = model.absolutePath + "|" + model.length()
        engine?.let { if (engineKey == key) return it }
        val dir = model.parentFile ?: throw VoiceProblem("No encuentro la carpeta de la voz.")

        // 1) ¿Ya viene preparada para sherpa-onnx? (tokens.txt y espeak-ng-data junto a la voz)
        val ownTokens = File(dir, "tokens.txt").takeIf { it.isFile }
        val ownEspeak = File(dir, "espeak-ng-data").takeIf { File(it, "phontab").isFile }
        val tokens: File
        if (ownTokens != null && configFor(model) == null) {
            tokens = ownTokens
        } else {
            val cfgFile = configFor(model) ?: throw VoiceProblem(
                "A la voz «${model.name}» le falta su archivo de configuración «${model.name}.json». " +
                    "Súbelo junto con la voz (con «Subir TTS» puedes elegir los dos archivos a la vez) o vuelve a bajar la voz por URL.")
            status?.invoke("🗣️ Preparando la voz «${model.nameWithoutExtension}» (solo la primera vez)…")
            val cfg = runCatching { JSONObject(cfgFile.readText()) }.getOrNull()
                ?: throw VoiceProblem("El archivo «${cfgFile.name}» está dañado: vuelve a bajarlo o subirlo.")
            tokens = File(model.path + ".tokens.txt")
            if (!tokens.isFile || tokens.lastModified() < cfgFile.lastModified()) writeTokens(cfg, tokens)
            addMetadataIfMissing(model, cfg)
        }

        // 2) Datos de pronunciación (una sola vez para todas las voces)
        val espeak = ownEspeak ?: ensureEspeakData(context, status)

        // 3) El motor
        status?.invoke("🗣️ Cargando la voz…")
        runCatching { engine?.release() }
        engine = null
        engineKey = ""
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeak.absolutePath
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu"
            )
        )
        val tts = runCatching { OfflineTts(config = config) }.getOrElse {
            Log.e(TAG, "sherpa-onnx no pudo cargar ${model.name}", it)
            throw VoiceProblem("No pude cargar la voz «${model.name}» (el archivo puede estar dañado o no ser una voz Piper).")
        }
        engine = tts
        engineKey = key
        return tts
    }

    /** Suelta la voz cargada (para liberar memoria o al borrarla). */
    @Synchronized
    fun releaseEngine() {
        runCatching { engine?.release() }
        engine = null
        engineKey = ""
    }

    /** Borra lo que se armó para una voz (al eliminarla). */
    fun deletePrepared(model: File) {
        releaseEngine()
        listOf(File(model.path + ".tokens.txt"), File(model.path + ".json"), File(model.path + ".meta_ok"),
            File(model.parentFile, model.nameWithoutExtension + ".json")).forEach { runCatching { it.delete() } }
    }

    // ------------------------------------------------------------------ preparación

    /** tokens.txt: cada sonido con su número («a 14»), igual que el script oficial de sherpa-onnx para Piper. */
    private fun writeTokens(cfg: JSONObject, out: File) {
        val map = cfg.optJSONObject("phoneme_id_map")
            ?: throw VoiceProblem("La configuración de la voz no trae su lista de sonidos (phoneme_id_map).")
        val sb = StringBuilder()
        val keys = map.keys()
        while (keys.hasNext()) {
            val symbol = keys.next()
            val ids = map.optJSONArray(symbol) ?: continue
            if (ids.length() == 0) continue
            sb.append(symbol).append(' ').append(ids.getInt(0)).append('\n')
        }
        if (sb.isEmpty()) throw VoiceProblem("La lista de sonidos de la voz está vacía.")
        out.writeText(sb.toString(), Charsets.UTF_8)
    }

    /**
     * Los datos que sherpa-onnx lee de la voz (frecuencia, idioma, voz de espeak…). Se agregan al FINAL del .onnx como
     * «metadata_props» del modelo (campo 14): el formato ONNX permite agregarlos así sin tocar lo demás.
     * Si la voz ya los trae (voces preparadas para sherpa-onnx), no se toca.
     */
    private fun addMetadataIfMissing(model: File, cfg: JSONObject) {
        val marker = File(model.path + ".meta_ok")
        if (marker.isFile && marker.readText() == model.length().toString()) return
        if (!(containsAscii(model, "model_type") && containsAscii(model, "sample_rate"))) {
            val audio = cfg.optJSONObject("audio")
            val espeakCfg = cfg.optJSONObject("espeak")
            val lang = cfg.optJSONObject("language")
            val meta = linkedMapOf(
                "model_type" to "vits",
                "comment" to "piper",   // (así lo pide sherpa-onnx para las voces de Piper)
                "language" to (lang?.optString("name_english").orEmpty()),
                "voice" to (espeakCfg?.optString("voice").orEmpty().ifBlank { "es" }),
                "has_espeak" to "1",
                "n_speakers" to cfg.optInt("num_speakers", 1).toString(),
                "sample_rate" to (audio?.optInt("sample_rate", 22050) ?: 22050).toString()
            )
            val bytes = ByteArrayOutputStream()
            for ((k, v) in meta) {
                val entry = ByteArrayOutputStream()
                val kb = k.toByteArray(Charsets.UTF_8); val vb = v.toByteArray(Charsets.UTF_8)
                entry.write(0x0A); writeVarint(entry, kb.size); entry.write(kb)   // key   (campo 1)
                entry.write(0x12); writeVarint(entry, vb.size); entry.write(vb)   // value (campo 2)
                val e = entry.toByteArray()
                bytes.write(0x72); writeVarint(bytes, e.size); bytes.write(e)      // metadata_props (campo 14)
            }
            java.io.FileOutputStream(model, true).use { it.write(bytes.toByteArray()) }
        }
        marker.writeText(model.length().toString())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }

    /** ¿El archivo contiene ese texto? (se lee por partes: las voces pesan decenas de MB) */
    private fun containsAscii(file: File, text: String): Boolean {
        val pat = text.toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(1 shl 20)
        var carry = ByteArray(0)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = carry + buf.copyOf(n)
                if (indexOf(chunk, pat) >= 0) return true
                carry = chunk.copyOfRange(maxOf(0, chunk.size - pat.size + 1), chunk.size)
            }
        }
        return false
    }

    private fun indexOf(data: ByteArray, pat: ByteArray): Int {
        outer@ for (i in 0..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    /** 📥 «espeak-ng-data» (pronunciación): se baja y se abre una sola vez; sirve para todas las voces. */
    private fun ensureEspeakData(context: Context, status: ((String) -> Unit)?): File {
        val dir = File(context.filesDir, "espeak-ng-data")
        val ok = File(context.filesDir, "espeak-ng-data.ok")
        if (ok.isFile && File(dir, "phontab").isFile) return dir
        status?.invoke("📥 Bajando los datos de pronunciación de las voces (una sola vez, unos 8 MB)…")
        val tmp = File(context.cacheDir, "espeak-ng-data.tar.bz2")
        try {
            http.newCall(Request.Builder().url(ESPEAK_URL).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) throw VoiceProblem(
                    "No pude bajar los datos de pronunciación (se necesita Internet solo esta vez). Inténtalo de nuevo con conexión.")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            status?.invoke("📦 Preparando los datos de pronunciación…")
            dir.deleteRecursively()
            val root = context.filesDir.canonicalFile
            TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val out = File(root, e.name).canonicalFile
                    // (solo dentro de la carpeta de la app)
                    if (!out.path.startsWith(root.path + File.separator)) continue
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> tar.copyTo(o) }
                }
            }
        } catch (p: VoiceProblem) {
            throw p
        } catch (t: Throwable) {
            Log.e(TAG, "espeak-ng-data", t)
            throw VoiceProblem("No pude preparar los datos de pronunciación (${t.message ?: t.javaClass.simpleName}). " +
                "Revisa la conexión e inténtalo de nuevo.")
        } finally {
            runCatching { tmp.delete() }
        }
        if (!File(dir, "phontab").isFile) throw VoiceProblem("Los datos de pronunciación llegaron incompletos. Inténtalo de nuevo.")
        ok.writeText("1")
        return dir
    }
}
