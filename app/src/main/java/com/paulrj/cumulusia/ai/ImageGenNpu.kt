package com.paulrj.cumulusia.ai

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ⚡🎨 Crear imágenes DESDE CERO y CAMBIAR FOTOS en la NPU (Snapdragon), en segundos.
 *
 * Usa el ControlNet-Canny OFICIAL de Qualcomm AI Hub (Stable Diffusion 1.5 + ControlNet; licencias CreativeML
 * OpenRAIL-M y Apache-2.0), que Qualcomm publica ya compilado para cada chip (QNN context binary, w8a16).
 * Un solo paquete sirve para las dos cosas: con una foto, el ControlNet sigue sus contornos; sin foto, va en cero.
 * Se descarga SOLO el del chip de tu teléfono (≈1,4 GB, una sola vez).
 * (Si ya bajaste el Stable Diffusion 1.5 solo —≈1,1 GB, de la versión anterior—, sirve para crear desde cero;
 *  para cambiar fotos se baja el paquete con ControlNet y el anterior se borra.)
 *
 * Chips con modelo oficial: Snapdragon 8 Elite Gen 5 · 8 Elite (S25) · 8 Gen 3 · 8 Gen 2 · 7 Gen 4.
 * En los demás teléfonos (o si la NPU falla) se crea en la CPU, como antes.
 *
 * El motor (sd-qnn.cpp, dentro de libcumulus_sd.so) es propio de Cumulus: nada viene de Local Dream.
 */
object ImageGenNpu {

    /** Cómo se llama el chip en los archivos de Qualcomm AI Hub. */
    class Chip(val aiHubName: String, val label: String)

    private const val AIHUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"

    /** Un paquete de Qualcomm AI Hub (carpeta propia en el teléfono). */
    class Bundle(val modelId: String, val releases: List<String>, val sizeMb: Int, val control: Boolean)
    /** Stable Diffusion 1.5 solo (versión anterior de Cumulus): crea desde cero. */
    val SD = Bundle("stable_diffusion_v1_5", listOf("v0.66.0", "v0.65.0"), 1100, false)
    /** ControlNet-Canny (SD 1.5 + ControlNet): crea desde cero Y cambia fotos. Es el que se descarga ahora. */
    val CN = Bundle("controlnet_canny", listOf("v0.66.0", "v0.65.0"), 1400, true)
    /** Lo que pesa la descarga (el paquete con ControlNet). */
    val SIZE_MB get() = CN.sizeMb

    // =====================================================================
    // 🔌 ¿Está el motor en esta compilación de la app?
    // =====================================================================
    val available: Boolean by lazy {
        if (!ImageGenNative.available) false     // (la misma librería libcumulus_sd.so)
        else runCatching { nativeNpuAvailable() }.getOrDefault(false)
    }

    private external fun nativeNpuAvailable(): Boolean
    private external fun nativeNpuLoad(paths: Array<String>, libDir: String): Boolean
    private external fun nativeNpuHasControl(): Boolean
    /** Devuelve [ancho (4 bytes), alto (4 bytes), RGB…] o null. control = foto RGB de cw×ch (null = desde cero). */
    private external fun nativeNpuGenerate(prompt: String, negative: String, steps: Int, cfg: Float, seed: Long,
                                           control: ByteArray?, cw: Int, ch: Int, controlStrength: Float,
                                           initStrength: Float, encoderPath: String?): ByteArray?
    private external fun nativeNpuCancel()
    private external fun nativeNpuFree()
    private external fun nativeNpuProgress(): Int
    private external fun nativeNpuLastError(): String
    private external fun nativeNpuInfo(): String

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
    // 📥 Archivos del modelo (las partes del paquete, del chip de este teléfono)
    // =====================================================================
    /** Las partes (.bin) de un paquete listo · control = trae el ControlNet (sirve para cambiar fotos). */
    class Files(val parts: List<File>, val control: Boolean)

    fun dir(context: Context, b: Bundle = CN): File =
        File(File(context.filesDir, "image_ai"), (if (b === CN) "npu_cn_" else "npu_") + (chip?.aiHubName ?: "none"))
    private fun okMark(context: Context, b: Bundle) = File(dir(context, b), "listo.txt")

    private fun bundleFiles(context: Context, b: Bundle): Files? {
        if (!okMark(context, b).exists()) return null
        val bins = dir(context, b).listFiles()?.filter { it.isFile && it.name.endsWith(".bin", true) && it.length() > 1_000_000 }.orEmpty()
        if (bins.size < (if (b.control) 4 else 3)) return null
        return Files(bins.sortedBy { it.name }, b.control)
    }

    /**
     * Las partes listas para usar. needControl = para cambiar una foto (hace falta el paquete con ControlNet).
     * Para crear desde cero sirve cualquiera de los dos (primero el del ControlNet).
     */
    fun files(context: Context, needControl: Boolean = false): Files? =
        bundleFiles(context, CN) ?: if (needControl) null else bundleFiles(context, SD)

    fun isReady(context: Context, needControl: Boolean = false): Boolean = chip != null && files(context, needControl) != null

    /**
     * Descarga y descomprime el paquete con ControlNet del chip (≈1,4 GB). Continúa donde quedó si se cortó.
     * @return null = listo · texto = por qué no se pudo
     */
    fun download(context: Context, onProgress: (String) -> Unit, isCancelled: () -> Boolean): String? {
        val c = chip ?: return "este chip no tiene el modelo oficial de Qualcomm"
        val b = CN
        if (bundleFiles(context, b) != null) return null
        val d = dir(context, b).apply { mkdirs() }
        val need = b.sizeMb * 1024L * 1024L * 23 / 10   // (el .zip + lo descomprimido)
        val zip = File(d, "modelo.zip.part")
        if (d.usableSpace + (if (zip.exists()) zip.length() else 0L) < need) return "no hay espacio suficiente (se necesitan ≈" +
            String.format(Locale.US, "%.1f", need / 1_000_000_000.0).replace('.', ',') + " GB libres)"
        var lastErr = "no se pudo descargar"
        for (rel in b.releases) {
            if (isCancelled()) return "cancelado"
            val url = "$AIHUB/${b.modelId}/releases/$rel/${b.modelId}-qnn_context_binary-w8a16-${c.aiHubName}.zip"
            try {
                fetch(url, zip, onProgress, isCancelled)
                if (isCancelled()) return "cancelado"
                onProgress("📦 Preparando la IA de imágenes de la NPU…")
                unzipBins(zip, d)
                zip.delete()
                okMark(context, b).writeText("$url\n")
                if (bundleFiles(context, b) != null) {
                    // (el paquete anterior, sin ControlNet, ya no hace falta: se libera ≈1,1 GB)
                    runCatching { dir(context, SD).deleteRecursively() }
                    return null
                }
                okMark(context, b).delete()
                lastErr = "el archivo de Qualcomm no trae las partes esperadas"
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                if (lastErr.startsWith("HTTP 4")) zip.delete()   // (esa versión no está: se prueba la siguiente)
            }
        }
        return lastErr
    }

    /** Borra los modelos de la NPU (para liberar espacio o si quedaron dañados). */
    fun delete(context: Context) { dir(context, CN).deleteRecursively(); dir(context, SD).deleteRecursively() }

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
        runCatching { nativeNpuLoad(f.parts.map { it.absolutePath }.toTypedArray(), context.applicationInfo.nativeLibraryDir) }
            .getOrDefault(false)

    /** ¿Lo cargado trae el ControlNet? */
    fun hasControl(): Boolean = available && runCatching { nativeNpuHasControl() }.getOrDefault(false)

    /** Tamaño de la imagen del modelo (Stable Diffusion 1.5 de Qualcomm: 512×512). */
    const val SIDE = 512

    /**
     * 🧩 TAESD (codificador, licencia MIT, © 2023 Ollin Boer Bohan — github.com/madebyollin/taesd): pasa la foto al
     * formato de la IA para que la NPU parta de ella (img2img). Va dentro de la app (assets/ai/taesd_encoder.bin, 2,4 MB);
     * se copia una vez a la carpeta de la app. null = no está.
     */
    fun encoderFile(context: Context): File? = runCatching {
        val f = File(File(context.filesDir, "image_ai"), "taesd_encoder.bin")
        val size = context.assets.openFd("ai/taesd_encoder.bin").use { it.length }
        if (!f.exists() || f.length() != size) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "taesd_encoder.bin.tmp")
            context.assets.open("ai/taesd_encoder.bin").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        }
        f.takeIf { it.length() == size }
    }.getOrNull() ?: runCatching {
        // (si el asset viene comprimido, openFd no funciona: se copia igual y se acepta si empieza bien)
        val f = File(File(context.filesDir, "image_ai"), "taesd_encoder.bin")
        if (!f.exists() || f.length() < 2_000_000) {
            f.parentFile?.mkdirs()
            context.assets.open("ai/taesd_encoder.bin").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        f.takeIf { it.length() > 2_000_000 }
    }.getOrNull()

    /**
     * Crea la imagen. Devuelve (ancho, alto, RGB) o null.
     * control = foto RGB de SIDE×SIDE para seguir sus contornos (null = desde cero) · controlStrength = cuánto la sigue.
     * initStrength (0..1) + encoder = partir de la foto (img2img): cuánto se rehace (0,65 = cambia el estilo, conserva la cara).
     */
    fun generate(prompt: String, negative: String, steps: Int, cfg: Float, seed: Long,
                 control: ByteArray? = null, controlStrength: Float = 1f,
                 initStrength: Float = 0f, encoder: File? = null): Triple<Int, Int, ByteArray>? {
        val r = runCatching {
            nativeNpuGenerate(prompt, negative, steps, cfg, seed, control, SIDE, SIDE, controlStrength, initStrength, encoder?.absolutePath)
        }.getOrNull() ?: return null
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
    /** 🔎 Diagnóstico: forma y tipo de cada parte del modelo + cómo salió la última imagen (σ del ruido, detalle…). */
    fun info(): String = if (available) runCatching { nativeNpuInfo() }.getOrDefault("").trim() else ""
}

