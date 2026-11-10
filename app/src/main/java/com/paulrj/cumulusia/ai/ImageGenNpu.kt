package com.paulrj.cumulusia.ai

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ⚡🎨 Crear imágenes DESDE CERO en la NPU (Snapdragon), en segundos.
 *
 * Usa el Stable Diffusion 1.5 OFICIAL de Qualcomm AI Hub (licencia CreativeML OpenRAIL-M), que Qualcomm publica
 * ya compilado para cada chip (QNN context binary, w8a16, QAIRT 2.50 = la misma versión de QNN que trae GenieX).
 * Se descarga SOLO el del chip de tu teléfono (≈1,1 GB, una sola vez).
 *
 * Chips con modelo oficial: Snapdragon 8 Elite Gen 5 · 8 Elite (S25) · 8 Gen 3 · 8 Gen 2 · 7 Gen 4.
 * En los demás teléfonos (o si la NPU falla) se crea en la CPU, como antes.
 *
 * El motor (sd-qnn.cpp, dentro de libcumulus_sd.so) es propio de Cumulus: nada viene de Local Dream.
 */
object ImageGenNpu {

    /** Cómo se llama el chip en los archivos de Qualcomm AI Hub. */
    class Chip(val aiHubName: String, val label: String)

    private const val AIHUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases"
    /** Versiones publicadas a probar (la más nueva primero). */
    private val RELEASES = listOf("v0.66.0")
    /** Lo que pesa la descarga (aprox.: text encoder + UNet + VAE en w8a16). */
    const val SIZE_MB = 1100

    // =====================================================================
    // 🔌 ¿Está el motor en esta compilación de la app?
    // =====================================================================
    val available: Boolean by lazy {
        if (!ImageGenNative.available) false     // (la misma librería libcumulus_sd.so)
        else runCatching { nativeNpuAvailable() }.getOrDefault(false)
    }

    private external fun nativeNpuAvailable(): Boolean
    private external fun nativeNpuLoad(textEncoder: String, unet: String, vae: String, libDir: String): Boolean
    /** Devuelve [ancho (4 bytes), alto (4 bytes), RGB…] o null. */
    private external fun nativeNpuTxt2Img(prompt: String, negative: String, steps: Int, cfg: Float, seed: Long): ByteArray?
    private external fun nativeNpuCancel()
    private external fun nativeNpuFree()
    private external fun nativeNpuProgress(): Int
    private external fun nativeNpuLastError(): String

    // =====================================================================
    // 📱 ¿Qué chip tiene el teléfono?
    // =====================================================================
    val chip: Chip? by lazy {
        val soc = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "").uppercase(Locale.ROOT)
        val board = Build.BOARD.lowercase(Locale.ROOT).trim()   // (nombre clave del chip: sun = 8 Elite, pineapple = 8 Gen 3…)
        when {
            soc.startsWith("SM8850") || board == "canoe" ->
                Chip("qualcomm_snapdragon_8_elite_gen5_for_galaxy", "Snapdragon 8 Elite Gen 5")
            soc.startsWith("SM8750") || board == "sun" ->
                Chip("qualcomm_snapdragon_8_elite_for_galaxy", "Snapdragon 8 Elite")
            soc.startsWith("SM8650") || board == "pineapple" ->
                Chip("qualcomm_snapdragon_8gen3", "Snapdragon 8 Gen 3")
            // (8 Gen 2: Qualcomm lo publica como QCS8550, que es el mismo chip en su versión industrial)
            soc.startsWith("SM8550") || board == "kalama" ->
                Chip("qualcomm_qcs8550_proxy", "Snapdragon 8 Gen 2")
            soc.startsWith("SM7750") ->
                Chip("qualcomm_snapdragon_7gen4", "Snapdragon 7 Gen 4")
            else -> null
        }
    }

    /** ¿Este teléfono puede crear imágenes en la NPU? (motor en la app + chip con modelo oficial) */
    fun supported(): Boolean = available && chip != null

    // =====================================================================
    // ⚙️ Preferencia: crear desde cero en la NPU («npu») o en la CPU («cpu»). null = todavía no elige.
    // =====================================================================
    private const val PREFS = "image_ai"
    private const val KEY = "create_engine"
    fun engineChoice(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    fun setEngineChoice(context: Context, v: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
    }
    /** ¿Crear desde cero en la NPU? (se puede y no elegiste la CPU) */
    fun useNpu(context: Context): Boolean = supported() && engineChoice(context) != "cpu"

    // =====================================================================
    // 📥 Archivos del modelo (uno por parte, del chip de este teléfono)
    // =====================================================================
    class Files(val textEncoder: File, val unet: File, val vae: File)

    fun dir(context: Context): File = File(File(context.filesDir, "image_ai"), "npu_" + (chip?.aiHubName ?: "none"))
    private fun okMark(context: Context) = File(dir(context), "listo.txt")

    /** Las 3 partes, si ya están descargadas y completas. */
    fun files(context: Context): Files? {
        if (!okMark(context).exists()) return null
        val bins = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".bin", true) && it.length() > 1_000_000 }.orEmpty()
        fun pick(vararg keys: String) = bins.firstOrNull { f -> keys.any { f.name.lowercase(Locale.ROOT).contains(it) } }
        val text = pick("text_encoder", "textencoder", "text-encoder", "clip") ?: return null
        val unet = pick("unet") ?: return null
        val vae = pick("vae") ?: return null
        return Files(text, unet, vae)
    }

    fun isReady(context: Context): Boolean = chip != null && files(context) != null

    /**
     * Descarga y descomprime el modelo del chip (≈1,1 GB). Continúa donde quedó si se cortó.
     * @return null = listo · texto = por qué no se pudo
     */
    fun download(context: Context, onProgress: (String) -> Unit, isCancelled: () -> Boolean): String? {
        val c = chip ?: return "este chip no tiene el modelo oficial de Qualcomm"
        if (isReady(context)) return null
        val d = dir(context).apply { mkdirs() }
        val need = SIZE_MB * 1024L * 1024L * 23 / 10   // (el .zip + lo descomprimido)
        if (d.usableSpace < need) return "no hay espacio suficiente (se necesitan ≈" +
            String.format(Locale.US, "%.1f", need / 1_000_000_000.0).replace('.', ',') + " GB libres)"
        val zip = File(d, "modelo.zip.part")
        var lastErr = "no se pudo descargar"
        for (rel in RELEASES) {
            if (isCancelled()) return "cancelado"
            val url = "$AIHUB/$rel/stable_diffusion_v1_5-qnn_context_binary-w8a16-${c.aiHubName}.zip"
            try {
                fetch(url, zip, onProgress, isCancelled)
                if (isCancelled()) return "cancelado"
                onProgress("📦 Preparando la IA de imágenes de la NPU…")
                unzipBins(zip, d)
                zip.delete()
                okMark(context).writeText("$url\n")
                if (files(context) != null) return null
                okMark(context).delete()
                lastErr = "el archivo de Qualcomm no trae las 3 partes esperadas"
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                if (lastErr.startsWith("HTTP 4")) zip.delete()   // (esa versión ya no está: se prueba la siguiente)
            }
        }
        return lastErr
    }

    /** Borra el modelo de la NPU (para liberar espacio o si quedó dañado). */
    fun delete(context: Context) { dir(context).deleteRecursively() }

    private fun fetch(url: String, out: File, onProgress: (String) -> Unit, isCancelled: () -> Boolean) {
        var from = if (out.exists()) out.length() else 0L
        var u = url
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CumulusApp")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 8) {
                val loc = conn.getHeaderField("Location") ?: throw java.io.IOException("redirección sin destino")
                u = URL(URL(u), loc).toString(); conn.disconnect(); redirects++; continue
            }
            if (code == 416 && from > 0) { conn.disconnect(); return }        // ya estaba completo
            if (code !in 200..299) { conn.disconnect(); throw java.io.IOException("HTTP $code") }
            if (from > 0 && code != 206) { from = 0; out.delete() }          // el servidor no deja continuar
            break
        }
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong + from else -1L
        try {
            conn.inputStream.use { inp ->
                RandomAccessFile(out, "rw").use { raf ->
                    raf.seek(from)
                    val buf = ByteArray(256 * 1024); var got = from; var lastPct = -1
                    while (true) {
                        if (isCancelled()) throw java.io.IOException("cancelado")
                        val n = inp.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress("📥 Descargando la IA de imágenes para la NPU (${total / 1_000_000} MB)… $pct%") }
                        }
                    }
                    if (total > 0 && got < total) throw java.io.IOException("la descarga se cortó")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Saca del .zip todos los .bin (sin carpetas), directo al disco. */
    private fun unzipBins(zip: File, dest: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered(256 * 1024)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name.isBlank() || name.contains("..")) continue
                if (!name.endsWith(".bin", true) && !name.endsWith(".json", true) && !name.endsWith(".yaml", true)) continue
                val tmp = File(dest, "$name.tmp")
                tmp.outputStream().use { o -> z.copyTo(o, 256 * 1024) }
                val f = File(dest, name)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
            }
        }
    }

    // =====================================================================
    // ▶️ Usar el motor
    // =====================================================================
    fun load(context: Context, f: Files): Boolean =
        runCatching { nativeNpuLoad(f.textEncoder.absolutePath, f.unet.absolutePath, f.vae.absolutePath, context.applicationInfo.nativeLibraryDir) }
            .getOrDefault(false)

    /** Imagen desde cero. Devuelve (ancho, alto, RGB) o null. */
    fun txt2img(prompt: String, negative: String, steps: Int, cfg: Float, seed: Long): Triple<Int, Int, ByteArray>? {
        val r = runCatching { nativeNpuTxt2Img(prompt, negative, steps, cfg, seed) }.getOrNull() ?: return null
        if (r.size < 8) return null
        fun int(at: Int) = (r[at].toInt() and 0xFF) or ((r[at + 1].toInt() and 0xFF) shl 8) or ((r[at + 2].toInt() and 0xFF) shl 16) or ((r[at + 3].toInt() and 0xFF) shl 24)
        val w = int(0); val h = int(4)
        if (w <= 0 || h <= 0 || r.size < 8 + w * h * 3) return null
        return Triple(w, h, r.copyOfRange(8, 8 + w * h * 3))
    }

    fun cancel() { if (available) runCatching { nativeNpuCancel() } }
    fun free() { if (available) runCatching { nativeNpuFree() } }
    fun progress(): Int = if (available) runCatching { nativeNpuProgress() }.getOrDefault(0) else 0
    fun lastError(): String = if (available) runCatching { nativeNpuLastError() }.getOrDefault("") else ""
}
