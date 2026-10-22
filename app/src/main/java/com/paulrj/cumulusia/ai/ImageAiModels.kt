package com.paulrj.cumulusia.ai

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Motor que eligió el usuario en ⚙️ (la app NUNCA lo cambia sola). */
enum class ImageEngine(val label: String) {
    NPU("la NPU"), GPU("la GPU"), CPU("la CPU");

    companion object {
        /** «⚡ NPU (Hexagon)» → NPU · «🎮 GPU (Adreno)» → GPU · «💻 CPU (NEON)» → CPU */
        fun fromBackend(backend: String?): ImageEngine = when {
            backend == null -> CPU
            backend.contains("NPU", ignoreCase = true) -> NPU
            backend.contains("GPU", ignoreCase = true) -> GPU
            else -> CPU
        }
    }
}

/** No se pudo usar el motor elegido: se explica en el chat y el usuario decide (no se cambia de motor solo). */
class ImageEngineError(message: String) : Exception(message)

/**
 * 🩺 En qué paso iba la IA de imágenes. Se escribe al instante en un archivo (filesDir/img_phase.txt):
 * si la NPU se cae y Android cierra la app de golpe, al volver a abrirla «¿por qué se cerró?» muestra
 * el último paso (por ejemplo «creando el delegado QNN» o «bloque 1 de 12»).
 */
object ImgPhase {
    @Volatile var file: File? = null
    fun start(dir: File) {
        file = File(dir, "img_phase.txt").also { runCatching { it.delete() } }
    }
    fun mark(step: String) {
        val f = file ?: return
        runCatching {
            java.io.FileOutputStream(f, true).use { o -> o.write("▶ $step\n".toByteArray()); o.fd.sync() }
        }
    }
}

/**
 * 🧠 Un modelo TFLite (LiteRT) abierto en el motor que eligió el usuario:
 *  ⚡ NPU = delegado QNN de Qualcomm (usa las librerías QNN que trae GenieX)
 *  🎮 GPU = delegado GPU de LiteRT (Adreno)
 *  💻 CPU = LiteRT en la CPU (XNNPACK)
 * Si el motor elegido falla, NO se prueba otro: se avisa con el motivo.
 */
class TfModel(file: File, val label: String, val engine: ImageEngine, nativeLibDir: String, floatModel: Boolean) : java.io.Closeable {
    val interpreter: org.tensorflow.lite.Interpreter
    private var delegate: org.tensorflow.lite.Delegate? = null

    init {
        val options = org.tensorflow.lite.Interpreter.Options()
        ImgPhase.mark("abriendo «$label» en ${engine.label}")
        try {
            when (engine) {
                ImageEngine.NPU -> {
                    ImgPhase.mark("creando el delegado QNN (NPU)")
                    val d = QnnDelegateFactory.create(nativeLibDir, fp16 = floatModel)
                    ImgPhase.mark("delegado QNN listo")
                    delegate = d
                    options.addDelegate(d)
                }
                ImageEngine.GPU -> {
                    val supported = runCatching { org.tensorflow.lite.gpu.CompatibilityList().use { it.isDelegateSupportedOnThisDevice } }.getOrDefault(false)
                    if (!supported) throw ImageEngineError("LiteRT dice que la GPU de este teléfono no es compatible")
                    val d = org.tensorflow.lite.gpu.GpuDelegate()
                    delegate = d
                    options.addDelegate(d)
                }
                ImageEngine.CPU -> options.setNumThreads(4)
            }
            ImgPhase.mark("cargando el modelo en ${engine.label} (LiteRT)")
            interpreter = org.tensorflow.lite.Interpreter(file, options)
            ImgPhase.mark("modelo cargado en ${engine.label}")
        } catch (e: ImageEngineError) {
            closeDelegate()
            throw e
        } catch (t: Throwable) {
            closeDelegate()
            throw ImageEngineError(t.message?.take(160)?.ifBlank { null } ?: t.javaClass.simpleName)
        }
    }

    private fun closeDelegate() {
        val d = delegate ?: return
        runCatching { d.close() }
        delegate = null
    }

    override fun close() {
        runCatching { interpreter.close() }
        closeDelegate()
    }
}

/**
 * ⚡ Delegado QNN (NPU Hexagon) por reflexión: la clase viene en com.qualcomm.qti:qnn-litert-delegate.
 * Usa las librerías QNN (libQnnHtp.so, libQnnHtpV79Skel.so…) que ya trae GenieX dentro del APK.
 */
object QnnDelegateFactory {
    fun create(nativeLibDir: String, fp16: Boolean): org.tensorflow.lite.Delegate {
        runCatching {
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, false)
        }
        val cls = try {
            Class.forName("com.qualcomm.qti.QnnDelegate")
        } catch (t: Throwable) {
            throw ImageEngineError("falta el delegado QNN de Qualcomm en esta compilación")
        }
        val optCls = Class.forName("com.qualcomm.qti.QnnDelegate\$Options")
        val opts = optCls.getConstructor().newInstance()
        setEnum(optCls, opts, "setBackendType", "com.qualcomm.qti.QnnDelegate\$Options\$BackendType", "HTP_BACKEND", required = true)
        optCls.getMethod("setSkelLibraryDir", String::class.java).invoke(opts, nativeLibDir)
        // Modelos float (fp32/fp16) → la NPU trabaja en fp16 · modelos cuantizados (w8a8) → como vienen
        if (fp16) setEnum(optCls, opts, "setHtpPrecision", "com.qualcomm.qti.QnnDelegate\$Options\$HtpPrecision", "HTP_PRECISION_FP16", required = false)
        setEnum(optCls, opts, "setHtpPerformanceMode", "com.qualcomm.qti.QnnDelegate\$Options\$HtpPerformanceMode", "HTP_PERFORMANCE_BURST", required = false)
        val d = try {
            cls.getConstructor(optCls).newInstance(opts)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            throw ImageEngineError("el delegado QNN no arrancó (${cause.message?.take(120) ?: cause.javaClass.simpleName})")
        }
        return d as? org.tensorflow.lite.Delegate ?: throw ImageEngineError("el delegado QNN no es compatible con esta versión de LiteRT")
    }

    private fun setEnum(optCls: Class<*>, opts: Any, method: String, enumClass: String, value: String, required: Boolean) {
        try {
            val e = Class.forName(enumClass)
            val v = e.enumConstants?.firstOrNull { (it as Enum<*>).name == value } ?: error("no existe $value")
            optCls.getMethod(method, e).invoke(opts, v)
        } catch (t: Throwable) {
            if (required) throw ImageEngineError("no se pudo configurar la NPU ($method)")
        }
    }
}

/**
 * 📥 Las IA de imágenes se descargan una sola vez y quedan en la carpeta privada de la app
 * (nada va a la galería). Las descargas grandes se pueden continuar si se cortan.
 */
class ImageAiModels(private val context: Context, private val onProgress: (String) -> Unit, private val isCancelled: () -> Boolean) {

    enum class Format { TFLITE, GGUF }

    class Spec(val key: String, val label: String, val sizeMb: Int, val fileName: String, val format: Format, val urls: List<String>)

    companion object {
        private const val AIHUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"

        /**
         * Súper resolución ×4 para la NPU: Real-ESRGAN General x4v3 cuantizado w8a8 (Qualcomm AI Hub, BSD-3).
         * Es el mismo de la GPU y la CPU, pero preparado para la NPU: pesa 1,25 MB y usa ~40-160 MB de RAM.
         * (Antes era Real-ESRGAN x4plus: al prepararlo en la NPU pedía tanta RAM que Android cerraba la app.)
         */
        val SR_NPU = Spec("sr_general_x4v3_w8a8", "Real-ESRGAN General (NPU)", 2, "real_esrgan_general_x4v3_w8a8.tflite", Format.TFLITE, listOf(
            "$AIHUB/real_esrgan_general_x4v3/releases/v0.64.0/real_esrgan_general_x4v3-tflite-w8a8.zip",
            "$AIHUB/real_esrgan_general_x4v3/releases/v0.63.0/real_esrgan_general_x4v3-tflite-w8a8.zip"))

        /** Súper resolución ×4 para GPU y CPU: Real-ESRGAN General x4v3 (liviano y rápido, BSD-3). */
        val SR_LIGHT = Spec("sr_general_x4v3", "Real-ESRGAN General", 5, "real_esrgan_general_x4v3_float.tflite", Format.TFLITE, listOf(
            "$AIHUB/real_esrgan_general_x4v3/releases/v0.46.0/real_esrgan_general_x4v3-tflite-float.zip",
            "$AIHUB/real_esrgan_general_x4v3/releases/v0.63.0/real_esrgan_general_x4v3-tflite-float.zip",
            "https://huggingface.co/litert-community/EDSR-x4-LiteRT/resolve/main/edsr.tflite"))

        /** Relleno (borrar a una persona): MI-GAN 512 entrenado con paisajes (Picsart, MIT). */
        val INPAINT = Spec("inpaint_migan", "MI-GAN", 16, "migan_fp16.tflite", Format.TFLITE, listOf(
            "https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/main/migan_fp16.tflite"))

        /** 🪄 Versión nueva de una imagen: SDXS-512 (un solo paso, muy rápido en la CPU; licencia openrail++). */
        val IMAGE_GEN = Spec("sdxs512", "SDXS-512", 683, "sdxs-512-tinySDdistilled_Q8_0.gguf", Format.GGUF, listOf(
            "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf",
            "https://huggingface.co/koboldcpp/imgmodel/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf"))

        fun dir(context: Context) = File(context.filesDir, "image_ai").apply { mkdirs() }
        fun file(context: Context, spec: Spec) = File(dir(context), spec.fileName)

        /** ¿Ya está descargado y completo? */
        fun isReady(context: Context, spec: Spec): Boolean = isValid(file(context, spec), spec)

        private fun isValid(f: File, spec: Spec): Boolean {
            if (!f.exists() || f.length() < 100_000) return false
            return runCatching {
                f.inputStream().use { s ->
                    val h = ByteArray(8)
                    if (s.read(h) != 8) return@use false
                    when (spec.format) {
                        // Los TFLite dicen «TFL3» en los bytes 4 a 7 · los GGUF empiezan con «GGUF»
                        Format.TFLITE -> h[4] == 'T'.code.toByte() && h[5] == 'F'.code.toByte() && h[6] == 'L'.code.toByte() && h[7] == '3'.code.toByte()
                        Format.GGUF -> h[0] == 'G'.code.toByte() && h[1] == 'G'.code.toByte() && h[2] == 'U'.code.toByte() && h[3] == 'F'.code.toByte() &&
                            f.length() >= spec.sizeMb * 1024L * 1024L * 85 / 100
                    }
                }
            }.getOrDefault(false)
        }
    }

    /** Devuelve el archivo listo (lo descarga si falta). null = no se pudo (sin internet, sin espacio…). */
    fun ensure(spec: Spec): File? {
        val f = file(context, spec)
        if (isValid(f, spec)) return f
        val need = spec.sizeMb * 1024L * 1024L * 11 / 10
        if (dir(context).usableSpace < need) {
            lastError = "no hay espacio suficiente (se necesitan ${spec.sizeMb} MB libres)"
            return null
        }
        for (url in spec.urls) {
            if (isCancelled()) return null
            val part = File(dir(context), spec.fileName + ".part")
            try {
                onProgress("📥 Descargando la IA ${spec.label} (≈${spec.sizeMb} MB, solo la primera vez)…")
                download(url, part, spec)
                if (isCancelled()) return null
                if (url.endsWith(".zip")) {
                    val ok = unzipTflite(part, f)
                    part.delete()
                    if (!ok) continue
                } else {
                    if (f.exists()) f.delete()
                    if (!part.renameTo(f)) { part.copyTo(f, true); part.delete() }
                }
                if (isValid(f, spec)) return f
                f.delete()
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                // Un .zip a medias no sirve: se borra · un .gguf a medias se continúa la próxima vez
                if (url.endsWith(".zip")) part.delete()
            }
        }
        return null
    }

    @Volatile var lastError: String = ""
        private set

    private fun open(url: String, from: Long): HttpURLConnection {
        var u = url
        var redirects = 0
        while (true) {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CumulusApp")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 8) {
                val loc = conn.getHeaderField("Location") ?: throw java.io.IOException("redirección sin destino")
                u = URL(URL(u), loc).toString(); conn.disconnect(); redirects++; continue
            }
            if (code == 416 && from > 0) { conn.disconnect(); throw AlreadyComplete() }   // ya estaba todo descargado
            if (code !in 200..299) { conn.disconnect(); throw java.io.IOException("HTTP $code") }
            return conn
        }
    }

    private class AlreadyComplete : java.io.IOException("completo")

    private fun download(url: String, out: File, spec: Spec) {
        // Se continúa donde quedó (solo modelos grandes, que vienen sin comprimir)
        var from = if (!url.endsWith(".zip") && out.exists()) out.length() else 0L
        if (from == 0L && out.exists()) out.delete()
        val conn = try { open(url, from) } catch (done: AlreadyComplete) { return }
        if (from > 0 && conn.responseCode != 206) {   // el servidor no permite continuar: desde cero
            conn.disconnect(); out.delete(); from = 0L
            return download(url, out, spec)
        }
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong + from else -1L
        try {
            conn.inputStream.use { inp ->
                RandomAccessFile(out, "rw").use { raf ->
                    raf.seek(from)
                    val buf = ByteArray(128 * 1024); var got = from; var lastPct = -1
                    while (true) {
                        if (isCancelled()) throw java.io.IOException("cancelado")
                        val n = inp.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress("📥 Descargando la IA ${spec.label} (${total / 1_000_000} MB)… $pct%")
                            }
                        }
                    }
                    if (total > 0 && got < total) throw java.io.IOException("la descarga se cortó")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzipTflite(zip: File, dest: File): Boolean {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            var bestName: String? = null
            val tmp = File(dest.parentFile, dest.name + ".unz")
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.endsWith(".tflite", ignoreCase = true)) continue
                val cand = File(dest.parentFile, dest.name + ".cand")
                cand.outputStream().use { o -> z.copyTo(o, 128 * 1024) }
                if (bestName == null || cand.length() > tmp.length()) {
                    tmp.delete(); cand.renameTo(tmp); bestName = e.name
                } else cand.delete()
            }
            if (bestName == null) return false
            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest) || runCatching { tmp.copyTo(dest, true); tmp.delete() }.isSuccess
        }
    }

    /** Abre un TFLite en el motor elegido. Lanza ImageEngineError si ese motor no sirve (no se prueba otro). */
    fun openTflite(spec: Spec, engine: ImageEngine, floatModel: Boolean): TfModel? {
        val f = ensure(spec) ?: return null
        ImgPhase.start(context.filesDir)
        return try {
            TfModel(f, spec.label, engine, context.applicationInfo.nativeLibraryDir, floatModel)
        } catch (e: ImageEngineError) {
            throw e
        } catch (t: Throwable) {
            // Archivo dañado: se borra para bajarlo de nuevo la próxima vez
            runCatching { f.delete() }
            throw ImageEngineError(t.message?.take(160) ?: t.javaClass.simpleName)
        }
    }
}

