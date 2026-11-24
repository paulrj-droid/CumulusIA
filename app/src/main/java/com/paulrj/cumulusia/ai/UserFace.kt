package com.paulrj.cumulusia.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 🧑‍🦱🔐 (24-nov-2026) «IA reconoce al usuario» — la CARA.
 *
 *  • Con las 6 fotos (4 de frente, 1 de perfil derecho, 1 de perfil izquierdo; desde el 24-nov-2026) se saca la «huella» de tu cara
 *    (512 números por foto, también en espejo) con AuraFace v1 (Apache-2.0, uso comercial permitido; ≈66 MB, se
 *    descarga una sola vez de Hugging Face y corre en la CPU del teléfono). Todo queda SOLO en el teléfono.
 *  • Cada vez que le hablas con la visión del avatar prendida, se mira la cara de la foto de ese momento y se compara:
 *    tú (USER), otra persona (OTHER) o no se sabe (UNKNOWN: sin cara, muy lejos, de lado…).
 *  • 👄 Voz a voz + visión: además se miran los labios (~5 veces por segundo). Si se te ve a ti pero tus labios no se
 *    movieron mientras alguien hablaba, el que habló era otra persona.
 *
 * No toca la cámara para adjuntar fotos, el modelo de chat, NPU/GPU/CPU ni la unión de videos.
 */
object UserFace {
    private const val TAG = "UserFace"

    enum class Who { USER, OTHER, UNKNOWN }

    /** AuraFace v1 (glintr100), TFLite con pesos int8: entra 112×112 RGB alineada, sale 512. Apache-2.0. */
    val SPEC = ImageAiModels.Spec("auraface_v1", "AuraFace (reconocer tu cara)", 66, "auraface_v1_glintr100_wi8.tflite",
        ImageAiModels.Format.TFLITE, listOf(
            "https://huggingface.co/eungsang/auraface-v1-tflite/resolve/main/auraface_v1_glintr100_wi8.tflite"))

    /** Parecido (coseno) desde el cual es tu cara · bajo el cual es otra persona · entre medio: no se sabe. */
    private const val SAME = 0.40f
    private const val DIFFERENT = 0.22f
    private const val DIM = 512

    private val KINDS = listOf("frontal", "derecha", "izquierda")
    /** Cuántas fotos de cada una: 4 de frente, 1 de perfil derecho y 1 de perfil izquierdo. */
    fun count(kind: String): Int = if (kind == "frontal") 4 else 1
    fun dir(ctx: Context) = File(ctx.filesDir, "user_id").apply { mkdirs() }
    fun photos(ctx: Context): List<File> = KINDS.flatMap { k -> (1..count(k)).map { File(dir(ctx), "cara_${k}_$it.jpg") } }
    private fun printsFile(ctx: Context) = File(dir(ctx), "cara_huellas.bin")

    /** ¿Están las 6 fotos? */
    fun photosComplete(ctx: Context): Boolean = photos(ctx).all { it.exists() && it.length() > 0 }

    /** Firma de las 6 fotos (si cambias una, la huella se vuelve a sacar). */
    private fun signature(ctx: Context): Long = photos(ctx).fold(17L) { acc, f -> acc * 31 + f.lastModified() + f.length() }

    @Volatile private var prints: List<FloatArray>? = null
    @Volatile private var printsSig = 0L

    /** ✅ Registrado de verdad: las 6 fotos + su huella lista. Sin esto, Cumulus se porta como siempre. */
    fun registered(ctx: Context): Boolean {
        if (!photosComplete(ctx)) return false
        return loadPrints(ctx) != null
    }

    private fun loadPrints(ctx: Context): List<FloatArray>? {
        val sig = signature(ctx)
        prints?.let { if (printsSig == sig) return it }
        val f = printsFile(ctx)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { s ->
                if (s.readLong() != sig) return null
                val n = s.readInt()
                if (n !in 1..64) return null
                List(n) { FloatArray(DIM) { s.readFloat() } }
            }
        }.getOrNull()?.also { prints = it; printsSig = sig }
    }

    private fun savePrints(ctx: Context, list: List<FloatArray>, sig: Long) {
        val tmp = File(dir(ctx), "cara_huellas.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeLong(sig); s.writeInt(list.size)
            for (e in list) for (v in e) s.writeFloat(v)
        }
        val f = printsFile(ctx)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        prints = list; printsSig = sig
    }

    /** Se borra la huella (por ejemplo, al cambiar una foto, antes de sacarla de nuevo). */
    fun forget(ctx: Context) { prints = null; printsSig = 0L; runCatching { printsFile(ctx).delete() } }

    // ------------------------------------------------------------------
    // 📥 Preparar: descarga la IA (la primera vez) y saca la huella de las 6 fotos
    // ------------------------------------------------------------------
    enum class Prep { READY, MISSING_PHOTOS, NO_MODEL, NO_FRONT_FACE }
    @Volatile var preparing = false; private set

    /** En segundo plano (NO en el hilo principal). */
    fun prepare(ctx: Context, onProgress: (String) -> Unit = {}): Prep {
        if (!photosComplete(ctx)) return Prep.MISSING_PHOTOS
        synchronized(this) {
            if (loadPrints(ctx) != null) return Prep.READY
            preparing = true
            try {
                val sig = signature(ctx)
                if (openModel(ctx, onProgress) == null) return Prep.NO_MODEL
                val list = ArrayList<FloatArray>()
                var frontOk = 0
                for (f in photos(ctx)) {
                    val bmp = decodeUpright(f, 1280) ?: continue
                    try {
                        val face = detectStill(bmp).maxByOrNull { it.boundingBox.width() } ?: continue
                        val crop = aligned(bmp, face) ?: continue
                        val e1 = embed(crop); val e2 = embed(mirror(crop))
                        crop.recycle()
                        if (e1 != null) list += e1
                        if (e2 != null) list += e2
                        if (e1 != null && f.name.startsWith("cara_frontal")) frontOk++
                    } finally { bmp.recycle() }
                }
                if (frontOk == 0) return Prep.NO_FRONT_FACE
                savePrints(ctx, list, sig)
                Log.d(TAG, "Huella lista: ${list.size} (de frente: $frontOk)")
                return Prep.READY
            } catch (t: Throwable) {
                Log.w(TAG, "preparar", t)
                return Prep.NO_MODEL
            } finally { preparing = false; scheduleClose() }
        }
    }

    // ------------------------------------------------------------------
    // 🔎 ¿Quién está hablando? (la foto de ahora de la visión del avatar)
    // ------------------------------------------------------------------
    /**
     * [who]: tú, otra persona o no se sabe · [userIsLargest]: tú eres la cara más grande (la más cerca) ·
     * [faces]: cuántas caras se ven.
     */
    class Seen(val who: Who, val score: Float, val userIsLargest: Boolean = false, val faces: Int = 0)

    /**
     * ¿Estás tú en la foto? (en segundo plano). (24-nov-2026) Se miran hasta 4 caras, de la más grande a la más chica:
     * si CUALQUIERA eres tú → USER (siempre se te responde a ti, aunque haya más personas frente a la cámara).
     * Si no estás y la más grande es claramente otra persona → OTHER. Si no → no se sabe.
     */
    fun identify(ctx: Context, shot: File?): Seen {
        if (shot == null || !shot.exists()) return Seen(Who.UNKNOWN, 0f)
        val mine = loadPrints(ctx) ?: return Seen(Who.UNKNOWN, 0f)
        synchronized(this) {
            try {
                if (openModel(ctx) {} == null) return Seen(Who.UNKNOWN, 0f)
                val bmp = decodeUpright(shot, 1280) ?: return Seen(Who.UNKNOWN, 0f)
                try {
                    // (muy chica = muy lejos: no se puede saber)
                    val faces = detectStill(bmp).filter { it.boundingBox.width() >= bmp.width * 0.10f }
                        .sortedByDescending { it.boundingBox.width() }
                    if (faces.isEmpty()) return Seen(Who.UNKNOWN, 0f)
                    var largestScore = -1f
                    for ((i, face) in faces.take(4).withIndex()) {
                        val crop = aligned(bmp, face) ?: continue
                        val e = embed(crop); crop.recycle()
                        e ?: continue
                        val best = mine.maxOf { cosine(it, e) }
                        if (i == 0) largestScore = best
                        if (best >= SAME) {
                            Log.d(TAG, "¿Eres tú? cara ${i + 1} de ${faces.size}: parecido=${"%.2f".format(best)} → USER")
                            return Seen(Who.USER, best, userIsLargest = i == 0, faces = faces.size)
                        }
                    }
                    val who = if (largestScore in 0f..DIFFERENT - 0.0001f) Who.OTHER else Who.UNKNOWN
                    Log.d(TAG, "¿Eres tú? no (${faces.size} caras, la más grande=${"%.2f".format(largestScore)}) → $who")
                    return Seen(who, largestScore, faces = faces.size)
                } finally { bmp.recycle() }
            } catch (t: Throwable) {
                Log.w(TAG, "identificar", t)
                return Seen(Who.UNKNOWN, 0f)
            } finally { scheduleClose() }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until DIM) s += a[i] * b[i]
        return s   // (las dos ya vienen con largo 1)
    }

    // ------------------------------------------------------------------
    // 🧠 La IA de caras (CPU, se cierra sola a los 3 minutos sin usarse para no ocupar memoria)
    // ------------------------------------------------------------------
    private var interpreter: org.tensorflow.lite.Interpreter? = null
    private val closer = android.os.Handler(android.os.Looper.getMainLooper())
    private val closeTask = Runnable {
        Thread {
            synchronized(this) { runCatching { interpreter?.close() }; interpreter = null }
        }.start()
    }
    private fun scheduleClose() { closer.removeCallbacks(closeTask); closer.postDelayed(closeTask, 180_000) }

    private fun openModel(ctx: Context, onProgress: (String) -> Unit): org.tensorflow.lite.Interpreter? {
        interpreter?.let { return it }
        val file = ImageAiModels(ctx.applicationContext, onProgress) { false }.ensure(SPEC) ?: return null
        return try {
            val opts = org.tensorflow.lite.Interpreter.Options().setNumThreads(4)
            org.tensorflow.lite.Interpreter(file, opts).also { interpreter = it }
        } catch (t: Throwable) {
            Log.w(TAG, "No pude abrir AuraFace: se borra para bajarla de nuevo", t)
            runCatching { file.delete() }
            null
        }
    }

    /** 512 números con largo 1 (o null si falló). */
    private fun embed(face112: Bitmap): FloatArray? {
        val tfl = interpreter ?: return null
        val px = IntArray(112 * 112)
        face112.getPixels(px, 0, 112, 0, 0, 112, 112)
        val input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        for (p in px) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(DIM) }
        tfl.run(input, out)
        val e = out[0]
        var n = 0f
        for (v in e) n += v * v
        n = sqrt(n)
        if (n < 1e-6f) return null
        for (i in e.indices) e[i] /= n
        return e
    }

    // ------------------------------------------------------------------
    // 🙂 Encontrar y enderezar la cara (como pide ArcFace: ojos y boca en su lugar, 112×112)
    // ------------------------------------------------------------------
    private val stillDetector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.08f).build())
    }

    private fun detectStill(bmp: Bitmap): List<Face> =
        runCatching { Tasks.await(stillDetector.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS) }
            .getOrNull().orEmpty()

    // Dónde van los ojos y las comisuras de la boca en la cara de 112×112 (referencia de ArcFace)
    private val REF = arrayOf(PointF(38.2946f, 51.6963f), PointF(73.5318f, 51.5014f),
        PointF(41.5493f, 92.3655f), PointF(70.7299f, 92.2041f))

    private fun aligned(bmp: Bitmap, face: Face): Bitmap? {
        val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val eyes = listOfNotNull(face.getLandmark(FaceLandmark.LEFT_EYE), face.getLandmark(FaceLandmark.RIGHT_EYE))
            .map { it.position }.sortedBy { it.x }
        val mouth = listOfNotNull(face.getLandmark(FaceLandmark.MOUTH_LEFT), face.getLandmark(FaceLandmark.MOUTH_RIGHT))
            .map { it.position }.sortedBy { it.x }
        val m = Matrix()
        if (eyes.size == 2 && mouth.size == 2 && eyes[1].x - eyes[0].x > face.boundingBox.width() * 0.18f) {
            // De frente: se ponen los ojos y la boca donde los espera la IA (giro + tamaño + lugar)
            val src = listOf(eyes[0], eyes[1], mouth[0], mouth[1])
            var mx = 0f; var my = 0f; var ux = 0f; var uy = 0f
            for (i in 0 until 4) { mx += src[i].x; my += src[i].y; ux += REF[i].x; uy += REF[i].y }
            mx /= 4; my /= 4; ux /= 4; uy /= 4
            var num1 = 0f; var num2 = 0f; var den = 0f
            for (i in 0 until 4) {
                val sx = src[i].x - mx; val sy = src[i].y - my
                val dx = REF[i].x - ux; val dy = REF[i].y - uy
                num1 += sx * dx + sy * dy; num2 += sx * dy - sy * dx; den += sx * sx + sy * sy
            }
            if (den < 1f) return null
            val a = num1 / den; val b = num2 / den
            m.setValues(floatArrayOf(a, -b, ux - a * mx + b * my, b, a, uy - b * mx - a * my, 0f, 0f, 1f))
        } else {
            // De perfil (un ojo escondido): el cuadrado de la cara, igual en las fotos y en lo que ve
            val r = face.boundingBox
            val side = maxOf(r.width(), r.height()) * 1.05f
            val cx = r.exactCenterX(); val cy = r.exactCenterY() + r.height() * 0.04f
            val s = 112f / side
            m.setScale(s, s); m.preTranslate(-(cx - side / 2), -(cy - side / 2))
        }
        c.drawBitmap(bmp, m, paint)
        return out
    }

    private fun mirror(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, Matrix().apply { setScale(-1f, 1f); postTranslate(b.width.toFloat(), 0f) }, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** La foto derecha (según su EXIF) y a lo más [maxSide] px por lado. */
    private fun decodeUpright(f: File, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val deg = when (android.media.ExifInterface(f.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) {
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
            if (r !== b) b.recycle(); b = r
        }
        b
    }.getOrNull()

    // ------------------------------------------------------------------
    // 👄 Labios (solo voz a voz + visión, ~5 veces por segundo): ¿se movieron mientras alguien hablaba?
    // ------------------------------------------------------------------
    private class Lip(val at: Long, val gap: Float)
    private val lips = ArrayDeque<Lip>()
    @Volatile private var lipThread: Thread? = null

    private val lipDetector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.12f).build())
    }

    /**
     * Prende el seguimiento de labios. [active]: si ahora corresponde mirar (voz a voz + visión + registrado).
     * [frame]: el último cuadro de la visión. Se apaga solo cuando [alive] da false.
     */
    fun startLips(active: () -> Boolean, alive: () -> Boolean, frame: () -> InputImage?) {
        if (lipThread?.isAlive == true) return
        lipThread = Thread({
            while (runCatching { alive() }.getOrDefault(false)) {
                try {
                    if (!runCatching { active() }.getOrDefault(false)) {
                        synchronized(lips) { lips.clear() }
                        Thread.sleep(600); continue
                    }
                    val img = frame()
                    if (img != null) {
                        val faces = runCatching { Tasks.await(lipDetector.process(img), 2, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                        val f = faces.maxByOrNull { it.boundingBox.width() }
                        val up = f?.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                        val lo = f?.getContour(FaceContour.LOWER_LIP_TOP)?.points
                        if (f != null && up != null && lo != null && up.size >= 5 && lo.size >= 5) {
                            // (los 3 puntos del medio de cada labio: cuánto se abre la boca, según el alto de la cara)
                            val mu = up.size / 2; val ml = lo.size / 2
                            var g = 0f
                            for (k in -1..1) g += kotlin.math.abs(lo[(ml + k).coerceIn(0, lo.size - 1)].y - up[(mu + k).coerceIn(0, up.size - 1)].y)
                            val gap = g / 3f / f.boundingBox.height().coerceAtLeast(1)
                            val now = SystemClock.uptimeMillis()
                            synchronized(lips) {
                                lips.addLast(Lip(now, gap))
                                while (lips.isNotEmpty() && now - lips.first().at > 12_000) lips.removeFirst()
                            }
                        }
                    }
                    Thread.sleep(200)
                } catch (_: InterruptedException) { break } catch (t: Throwable) {
                    Log.w(TAG, "labios", t); runCatching { Thread.sleep(1000) }
                }
            }
            synchronized(lips) { lips.clear() }
        }, "cumulus-labios").apply { isDaemon = true; start() }
    }

    /** ¿Se movieron los labios de la cara que se ve en los últimos [windowMs]? null = no hay suficientes cuadros. */
    fun lipsMoved(windowMs: Long): Boolean? {
        val now = SystemClock.uptimeMillis()
        val g = synchronized(lips) { lips.filter { now - it.at <= windowMs }.map { it.gap } }.sorted()
        if (g.size < 6) return null
        val spread = g[g.size - 2] - g[1]
        Log.d(TAG, "👄 labios: ${g.size} cuadros, se abrió ${"%.3f".format(spread)}")
        return spread > 0.02f
    }
}
