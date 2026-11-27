package com.paulrj.cumulusia.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 👁️ VISIÓN DEL AVATAR: la cámara queda prendida (sin mostrarse en pantalla) mientras «Avatar visión» está encendido,
 * y la IA mira lo último que vio cada vez que le hablas o le escribes.
 *
 * 📷 Solo la cámara DELANTERA (selfie), en baja resolución (480p): te mira a ti y gasta poco.
 *   (Para mostrarle cosas está la cámara para adjuntar fotos.)
 *
 * No toca la Cámara para adjuntar fotos, el modelo, NPU/GPU/CPU ni la unión de videos.
 */
class AvatarVision(private val ctx: Context) {

    @Volatile var running = false; private set
    /** Algo salió mal al abrir la cámara (para avisar). */
    var onError: ((String) -> Unit)? = null

    /**
     * 🙂 Lo que ven los ojos de tu cara (cámara delantera, ~8 veces por segundo): dónde estás (x, y de 0 a 1, como
     * te ve el avatar), qué tan cerca (ancho de tu cara / ancho de la imagen) y si sonríes (0 a 1).
     * null = no hay nadie de frente.
     */
    class FaceSeen(val x: Float, val y: Float, val size: Float, val smile: Float)
    var onFace: ((FaceSeen?) -> Unit)? = null
    /** 🙋 (27-nov-2026) Cada cuadro guardado (NV21, ancho, alto, giro), para mirar tus gestos (GestureWatcher). */
    @Volatile var onFrameRaw: ((ByteArray, Int, Int, Int) -> Unit)? = null

    /**
     * 🖐️ Dónde está tu cara en la foto (0 a 1, la foto TAL CUAL, sin dar vuelta: igual que [snapshot]).
     * null = no hay cara. Sirve para buscar lo que le muestras al lado de tu cara.
     */
    @Volatile var faceBox: android.graphics.RectF? = null; private set
    /** 👥 Cuántas caras se ven ahora (tú y las personas que le muestras). */
    @Volatile var faceCount = 0; private set

    // 🖐️ «Movimiento»: cada ~0,4 s se guarda una versión chiquita (en gris) de lo que se ve. Comparando con la de hace
    //    unos segundos se sabe DÓNDE apareció algo nuevo (la mano que levantaste, el vaso que le muestras…).
    private val GW = 48; private val GH = 64
    private val history = ArrayDeque<Pair<Long, ByteArray>>()
    private var lastLumaAt = 0L

    private val thread = HandlerThread("cumulus-vision").also { it.start() }
    private val handler = Handler(thread.looper)
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var camId: String? = null
    private var sensorDeg = 0

    // Último cuadro (NV21) — se guarda ~8 veces por segundo
    private val lock = Any()
    private var lastNv21: ByteArray? = null
    private var lastW = 0; private var lastH = 0; private var lastRot = 0
    @Volatile private var lastFrameAt = 0L
    @Volatile private var switchedAt = 0L
    private var lastStoreAt = 0L

    // 🙂 Detector de caras (Google ML Kit, en el teléfono): el avatar te sigue con la mirada
    private fun newDetector() =
        com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // (sonrisa)
                .setMinFaceSize(0.08f).build())
    // 🔁 (25-nov-2026) Si se traba varias veces seguidas (con la NPU trabajando), se cierra y se crea uno nuevo
    @Volatile private var detector = newDetector()
    @Volatile private var stuckInRow = 0
    private val RENEW_DETECTOR = false
    @Volatile private var detecting = false
    private var lastDetectAt = 0L
    // 🩺 (24-nov-2026) Cuántas veces miró tu cara y si se trabó (con la NPU el avatar quedaba quieto: así se ve por qué)
    @Volatile private var detectOk = 0
    @Volatile private var detectFail = 0
    @Volatile private var detectStuck = 0
    @Volatile private var detectLastError = ""
    @Volatile private var detectWithFace = 0
    @Volatile private var detectRenewed = 0
    @Volatile private var lastDetectOkAt = 0L
    /** 🩺 (25-nov-2026) Hace cuántos ms el detector de caras contestó bien por última vez (-1 = nunca). */
    fun detectAgeMs(): Long = if (lastDetectOkAt == 0L) -1L else SystemClock.uptimeMillis() - lastDetectOkAt

    /** 🩺 (25-nov-2026) Hace cuántos ms llegó el último cuadro de la cámara (-1 = nunca llegó). */
    fun frameAgeMs(): Long = if (lastFrameAt == 0L) -1L else SystemClock.uptimeMillis() - lastFrameAt
    /** 🩺 Estado de la cámara por dentro (para la ventana de actividad). */
    fun cameraState(): String = "cámara " + (if (running) "prendida" else "apagada") +
        (if (device == null) " · sin dispositivo" else "") + (if (session == null) " · sin sesión" else "") +
        (if (camLost > 0) " · Android la cortó $camLost ${if (camLost == 1) "vez" else "veces"} ($camLostWhy), reabierta $camReopened" else "") +
        " · último cuadro " + frameAgeMs().let { if (it < 0) "nunca" else String.format(java.util.Locale.ROOT, "hace %.1f s", it / 1000f) }

    /** 🩺 Cómo va el seguimiento de tu cara desde la última vez que se preguntó (para la ventana de actividad). */
    fun faceTrackReport(): String {
        val r = "miró tu cara $detectOk veces (te vio en $detectWithFace)" +
            (if (detectFail > 0) ", falló $detectFail" else "") +
            (if (detectStuck > 0) ", se trabó $detectStuck (se destrabó solo)" else "") +
            (if (detectRenewed > 0) ", detector nuevo $detectRenewed" else "") +
            (if (detectLastError.isNotEmpty()) " · error: $detectLastError" else "")
        detectOk = 0; detectFail = 0; detectStuck = 0; detectWithFace = 0; detectRenewed = 0; detectLastError = ""
        return r
    }

    /** Prende la cámara delantera. */
    fun start() = handler.post { openCamera() }

    /** Apaga la cámara (la visión queda en pausa). */
    fun stop() = handler.post { handler.removeCallbacks(reopenTask); closeCamera(); running = false }

    /** Apaga todo para siempre (al cerrar la app). */
    fun release() {
        handler.post {
            handler.removeCallbacks(reopenTask); runCatching { manager.unregisterAvailabilityCallback(availability) }
            closeCamera(); running = false; runCatching { detector.close() }; thread.quitSafely()
        }
    }

    /**
     * La foto de AHORA como JPEG en [out]. Si la cámara recién se prendió, espera hasta [waitMs] a que llegue
     * el primer cuadro. null = no hay cuadro.
     */
    fun snapshot(out: File, waitMs: Long = 3000): File? {
        val until = SystemClock.uptimeMillis() + waitMs
        while (SystemClock.uptimeMillis() < until) {
            if (lastFrameAt > switchedAt) break
            Thread.sleep(100)
        }
        val (nv, w, h, rot) = synchronized(lock) {
            val n = lastNv21 ?: return null
            Quad(n.copyOf(), lastW, lastH, lastRot)
        }
        if (SystemClock.uptimeMillis() - lastFrameAt > 8000) return null   // (cuadro muy viejo: la cámara se trabó)
        return runCatching {
            val jpg = ByteArrayOutputStream().use { s ->
                YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 88, s); s.toByteArray()
            }
            var bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return null
            if (rot != 0) {
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                if (r !== bmp) bmp.recycle(); bmp = r
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            out
        }.getOrNull()
    }

    /**
     * 👄 (24-nov-2026) El último cuadro tal cual, para mirar los labios de quien habla («IA reconoce al usuario»).
     * Solo lo lee: no cambia nada de lo que ve el avatar. null = no hay cuadro o es viejo.
     */
    fun latestInput(maxAgeMs: Long = 1500): com.google.mlkit.vision.common.InputImage? {
        if (SystemClock.uptimeMillis() - lastFrameAt > maxAgeMs) return null
        val q = synchronized(lock) {
            val n = lastNv21 ?: return null
            Quad(n.copyOf(), lastW, lastH, lastRot)
        }
        return runCatching {
            com.google.mlkit.vision.common.InputImage.fromByteArray(q.nv, q.w, q.h, q.rot,
                com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21)
        }.getOrNull()
    }

    private data class Quad(val nv: ByteArray, val w: Int, val h: Int, val rot: Int)

    // ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        closeCamera()
        switchedAt = SystemClock.uptimeMillis()
        val want = CameraCharacteristics.LENS_FACING_FRONT
        val id = runCatching { manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } }
            .getOrNull() ?: run { onError?.invoke("No encontré la cámara delantera."); return }
        camId = id
        sensorDeg = runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0 }.getOrDefault(0)
        running = true
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; reopenTries = 0; startSession() }
                override fun onDisconnected(d: CameraDevice) {
                    runCatching { d.close() }; if (device === d) device = null
                    // 🔌 (25-nov-2026) Android le quitó la cámara a la app (pasa con la NPU mirando la foto): se vuelve a abrir sola
                    camLost++; camLostWhy = "Android la desconectó"
                    scheduleReopen()
                }
                override fun onError(d: CameraDevice, error: Int) {
                    runCatching { d.close() }; if (device === d) device = null
                    camLost++; camLostWhy = "error $error"
                    // (antes avisaba y quedaba apagada; ahora intenta volver a abrirla sola)
                    if (!scheduleReopen()) onError?.invoke("No pude usar la cámara (otra app la está usando o falló, código $error).")
                }
            }, handler)
        }.onFailure {
            // (si era un reintento después de que Android la cortó, se sigue intentando en silencio)
            if (reopenTries > 0 && scheduleReopen()) return@onFailure
            running = false; onError?.invoke("No pude abrir la cámara: ${it.message}")
        }
    }

    /** Tamaño a pedir: 480p (18-nov-2026: con HD 720p el teléfono se calentaba y se ponía lento). */
    private fun targetSize(): Size = Size(640, 480)

    private fun bestSize(target: Size): Size {
        val map = camId?.let { runCatching { manager.getCameraCharacteristics(it).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull() }
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return target
        val ta = target.width.toLong() * target.height
        val tr = target.width.toFloat() / target.height
        return sizes.minByOrNull { s ->
            val a = s.width.toLong() * s.height
            kotlin.math.abs(a - ta).toFloat() / ta + kotlin.math.abs(s.width.toFloat() / s.height - tr) * 2f
        } ?: target
    }

    private fun startSession() {
        val d = device ?: return
        runCatching { session?.close() }; session = null
        runCatching { reader?.close() }; reader = null
        val size = bestSize(targetSize())
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        reader = r
        @Suppress("DEPRECATION")
        runCatching {
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== d) { runCatching { s.close() }; return }
                    session = s
                    runCatching {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        s.setRepeatingRequest(req, null, handler)
                    }.onFailure { Log.w(TAG, "visión: repeating", it) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { onError?.invoke("No pude preparar la cámara.") }
            }, handler)
        }.onFailure { onError?.invoke("No pude preparar la cámara: ${it.message}") }
    }

    // ---- 🔌 (25-nov-2026) Volver a abrir la cámara si Android la corta ----
    // La ventana de actividad mostró «cámara prendida · sin dispositivo · último cuadro hace 85,9 s»: con la NPU mirando
    // la foto, Android desconecta la cámara de la app y nadie la volvía a abrir (por eso el avatar dejaba de seguirte).
    @Volatile private var camLost = 0
    @Volatile private var camReopened = 0
    @Volatile private var camLostWhy = ""
    private var reopenTries = 0
    private val reopenTask = Runnable {
        if (!running || device != null) return@Runnable
        reopenTries++
        camReopened++
        Log.w(TAG, "visión: vuelvo a abrir la cámara (intento $reopenTries)")
        openCamera()
    }
    /** Programa volver a abrir la cámara (1 s, 2 s, 3 s… hasta 5 s entre intentos, sin límite mientras esté prendida). */
    private fun scheduleReopen(): Boolean {
        if (!running) return false
        handler.removeCallbacks(reopenTask)
        handler.postDelayed(reopenTask, (1_000L * (reopenTries + 1)).coerceAtMost(5_000L))
        return true
    }
    // (y apenas Android avisa que la cámara quedó libre, se abre al tiro)
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            // (25-nov-2026: la espera bajó de 0,3 s a 0,05 s, para que el cortecito al reconectar casi no se note)
            if (id == camId && running && device == null) { handler.removeCallbacks(reopenTask); handler.postDelayed(reopenTask, 50) }
        }
    }
    init { runCatching { manager.registerAvailabilityCallback(availability, handler) } }

    private fun closeCamera() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
    }

    /** Giro del cuadro para que quede derecho (según cómo tienes el teléfono). */
    private fun rotation(): Int {
        val disp = runCatching { (ctx as? android.app.Activity)?.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        val deg = when (disp) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (sensorDeg + deg) % 360
    }

    private fun onFrame(rd: ImageReader) {
        val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastStoreAt < 125) return       // (~8 cuadros por segundo: el avatar te sigue con la mirada, suave y rápido)
            lastStoreAt = now
            val w = img.width; val h = img.height
            val nv = yuvToNv21(img)
            val rot = rotation()
            synchronized(lock) { lastNv21 = nv; lastW = w; lastH = h; lastRot = rot }
            lastFrameAt = now
            if (now - lastLumaAt >= 400) {
                lastLumaAt = now
                val g = smallLuma(nv, w, h, rot)
                synchronized(history) {
                    history.addLast(now to g)
                    while (history.isNotEmpty() && now - history.first().first > 4_500) history.removeFirst()
                }
            }
            maybeDetect(nv, w, h, rot, now)
            runCatching { onFrameRaw?.invoke(nv, w, h, rot) }   // 🙋 gestos (solo se analizan si están callados)
        } catch (e: Exception) {
            Log.w(TAG, "visión: cuadro", e)
        } finally { runCatching { img.close() } }
    }

    /**
     * 🙂 Tu cara (cada ~0,125 s): dónde estás, qué tan cerca y si sonríes → el avatar te sigue con los ojos y reacciona.
     */
    private fun maybeDetect(nv: ByteArray, w: Int, h: Int, rot: Int, now: Long) {
        // 🩺 (24-nov-2026) si el detector no contestó en 1,5 s, se trabó: se suelta para que siga mirándote
        if (detecting && now - lastDetectAt > 1_500) {
            detecting = false; detectStuck++; stuckInRow++
            Log.w(TAG, "visión: detector trabado, se suelta")
            // (3 veces seguidas sin contestar: el detector quedó pegado para siempre → uno nuevo)
            // (25-nov-2026: APAGADO por ahora; solo se anota. Para volver a usarlo: RENEW_DETECTOR = true)
            if (RENEW_DETECTOR && stuckInRow >= 3) {
                stuckInRow = 0; detectRenewed++
                val old = detector
                detector = newDetector()
                runCatching { old.close() }
                Log.w(TAG, "visión: detector nuevo (el anterior quedó pegado)")
            }
        }
        if (detecting || now - lastDetectAt < 125) return
        detecting = true; lastDetectAt = now
        val input = com.google.mlkit.vision.common.InputImage.fromByteArray(nv, w, h, rot, com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val frameW = if (rot == 90 || rot == 270) h else w
                val frameH = if (rot == 90 || rot == 270) w else h
                val face = faces.maxByOrNull { it.boundingBox.width() }
                detectOk++; stuckInRow = 0; lastDetectOkAt = SystemClock.uptimeMillis(); if (face != null) detectWithFace++
                faceCount = faces.size
                faceBox = face?.let { val b = it.boundingBox
                    android.graphics.RectF(b.left.toFloat() / frameW, b.top.toFloat() / frameH, b.right.toFloat() / frameW, b.bottom.toFloat() / frameH) }
                // (la imagen de la cámara delantera NO está en espejo: tu derecha sale a la izquierda; se da vuelta
                //  para que x sea «como te ve el avatar» desde la pantalla)
                val seen = face?.let {
                    FaceSeen(1f - it.boundingBox.exactCenterX() / frameW, it.boundingBox.exactCenterY() / frameH,
                        it.boundingBox.width().toFloat() / frameW, it.smilingProbability ?: 0f)
                }
                runCatching { onFace?.invoke(seen) }
            }
            .addOnFailureListener { e: Exception -> detectFail++; detectLastError = (e.message ?: e.toString()).take(80) }
            .addOnCompleteListener { detecting = false }
    }

    /** Lo que se ve, en chiquito y en gris ([GW]×[GH]), ya derecho (como la foto). */
    private fun smallLuma(nv: ByteArray, w: Int, h: Int, rot: Int): ByteArray {
        val out = ByteArray(GW * GH)
        for (gy in 0 until GH) for (gx in 0 until GW) {
            val u = (gx + 0.5f) / GW; val v = (gy + 0.5f) / GH
            val (sx, sy) = when (rot) {
                90 -> v * w to (1f - u) * h
                180 -> (1f - u) * w to (1f - v) * h
                270 -> (1f - v) * w to u * h
                else -> u * w to v * h
            }
            val x = sx.toInt().coerceIn(0, w - 1); val y = sy.toInt().coerceIn(0, h - 1)
            out[gy * GW + gx] = nv[y * w + x]
        }
        return out
    }

    /**
     * 🖐️ ¿Dónde está lo que le muestras? Compara lo que se ve ahora con lo de hace 1 a 4 segundos y busca la zona que
     * cambió (sin contar tu cara). Devuelve esa zona (0 a 1, como la foto) o null si no se movió nada.
     */
    fun objectBox(): android.graphics.RectF? {
        val (now, cur, olds) = synchronized(history) {
            if (history.size < 3) return null
            val last = history.last()
            Triple(last.first, last.second, history.filter { last.first - it.first in 900..4_500 }.map { it.second })
        }
        if (olds.isEmpty()) return null
        val fb = faceBox
        val diff = BooleanArray(GW * GH)
        var cnt = 0
        for (i in 0 until GW * GH) {
            val gx = i % GW; val gy = i / GW
            val u = (gx + 0.5f) / GW; val v = (gy + 0.5f) / GH
            // (tu cara no cuenta: se mueve sola al hablar)
            if (fb != null) {
                val mx = fb.width() * 0.15f; val my = fb.height() * 0.15f
                if (u > fb.left - mx && u < fb.right + mx && v > fb.top - my && v < fb.bottom + my) continue
            }
            val c = cur[i].toInt() and 0xFF
            var d = 0
            for (o in olds) d = maxOf(d, kotlin.math.abs(c - (o[i].toInt() and 0xFF)))
            if (d > 32) { diff[i] = true; cnt++ }
        }
        if (cnt < 6) return null
        // (se quitan puntitos sueltos: queda lo que tiene vecinos que también cambiaron)
        var l = GW; var t = GH; var r = -1; var b = -1; var kept = 0
        for (i in 0 until GW * GH) if (diff[i]) {
            val gx = i % GW; val gy = i / GW
            var nb = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = gx + dx; val yy = gy + dy
                if ((dx != 0 || dy != 0) && xx in 0 until GW && yy in 0 until GH && diff[yy * GW + xx]) nb++
            }
            if (nb >= 2) { kept++; l = minOf(l, gx); r = maxOf(r, gx); t = minOf(t, gy); b = maxOf(b, gy) }
        }
        if (kept < 5 || r < 0) return null
        return android.graphics.RectF(l.toFloat() / GW, t.toFloat() / GH, (r + 1f) / GW, (b + 1f) / GH)
    }

    private fun yuvToNv21(img: android.media.Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        var pos = 0
        val yRow = y.rowStride; val yPix = y.pixelStride
        for (r in 0 until h) {
            if (yPix == 1) { yb.position(r * yRow); yb.get(out, pos, w); pos += w }
            else for (c in 0 until w) out[pos++] = yb.get(r * yRow + c * yPix)
        }
        val uvRow = v.rowStride; val uvPix = v.pixelStride
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            val i = r * uvRow + c * uvPix
            out[pos++] = vb.get(i); out[pos++] = ub.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }

    companion object { private const val TAG = "CumulusVision" }
}
