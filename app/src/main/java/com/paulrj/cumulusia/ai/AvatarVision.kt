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
 * 📷 Cámara DELANTERA con resolución que se adapta sola:
 *   • Tu cara de frente y cerca (10 a 50 cm) → 480p (gasta poco).
 *   • Sin cara cerca (mira algo lejos, por ejemplo un peluche a 3 m) → 720p (gasta más, ve mejor).
 *   • Espera 2 segundos con la misma situación antes de cambiar, para no estar cambiando a cada rato.
 * 📷 Cámara TRASERA (para mostrarle cosas): siempre FHD (1080p), sin cambio automático.
 *
 * No toca la Cámara para adjuntar fotos, el modelo, NPU/GPU/CPU ni la unión de videos.
 */
class AvatarVision(private val ctx: Context) {

    enum class Facing { FRONT, BACK }

    @Volatile var facing = Facing.FRONT; private set
    @Volatile var running = false; private set
    /** Algo salió mal al abrir la cámara (para avisar). */
    var onError: ((String) -> Unit)? = null

    /**
     * 🙂 Lo que ven los ojos de tu cara (cámara delantera, ~4 veces por segundo): dónde estás (x, y de 0 a 1, como
     * te ve el avatar), qué tan cerca (ancho de tu cara / ancho de la imagen) y si sonríes (0 a 1).
     * null = no hay nadie de frente.
     */
    class FaceSeen(val x: Float, val y: Float, val size: Float, val smile: Float)
    var onFace: ((FaceSeen?) -> Unit)? = null

    private val thread = HandlerThread("cumulus-vision").also { it.start() }
    private val handler = Handler(thread.looper)
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var camId: String? = null
    private var sensorDeg = 0
    @Volatile private var lowRes = true          // (delantera: 480p con tu cara cerca)

    // Último cuadro (NV21) — se guarda ~2 veces por segundo
    private val lock = Any()
    private var lastNv21: ByteArray? = null
    private var lastW = 0; private var lastH = 0; private var lastRot = 0
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastFacing = Facing.FRONT
    @Volatile private var switchedAt = 0L
    private var lastStoreAt = 0L

    // 🙂 Detector de caras (Google ML Kit, en el teléfono) para la resolución adaptable
    private val detector by lazy {
        com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // (sonrisa)
                .setMinFaceSize(0.08f).build())
    }
    @Volatile private var detecting = false
    private var lastDetectAt = 0L
    private var candidateLow: Boolean? = null
    private var candidateSince = 0L

    /** Prende la cámara [f] (si ya estaba prendida, cambia a esa). */
    fun start(f: Facing) = handler.post { openCamera(f) }

    /** Cambia de cámara (delantera ↔ trasera). */
    fun switchTo(f: Facing) = handler.post { if (!running || f != facing) openCamera(f) }

    /** Apaga la cámara (la visión queda en pausa). */
    fun stop() = handler.post { closeCamera(); running = false }

    /** Apaga todo para siempre (al cerrar la app). */
    fun release() {
        handler.post { closeCamera(); running = false; runCatching { detector.close() }; thread.quitSafely() }
    }

    /**
     * La foto de AHORA como JPEG en [out] (de la cámara que está prendida). Si recién se cambió de cámara, espera
     * hasta [waitMs] a que llegue un cuadro nuevo de esa cámara. null = no hay cuadro.
     */
    fun snapshot(out: File, waitMs: Long = 3000): File? {
        val until = SystemClock.uptimeMillis() + waitMs
        while (SystemClock.uptimeMillis() < until) {
            if (lastFrameAt > switchedAt && lastFacing == facing) break
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

    private data class Quad(val nv: ByteArray, val w: Int, val h: Int, val rot: Int)

    // ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera(f: Facing) {
        closeCamera()
        facing = f
        switchedAt = SystemClock.uptimeMillis()
        candidateLow = null
        lowRes = true
        val want = if (f == Facing.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = runCatching { manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } }
            .getOrNull() ?: run { onError?.invoke(if (f == Facing.FRONT) "No encontré la cámara delantera." else "No encontré la cámara trasera."); return }
        camId = id
        sensorDeg = runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0 }.getOrDefault(0)
        running = true
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession() }
                override fun onDisconnected(d: CameraDevice) { runCatching { d.close() }; if (device === d) device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    runCatching { d.close() }; if (device === d) device = null
                    onError?.invoke("No pude usar la cámara (otra app la está usando o falló, código $error).")
                }
            }, handler)
        }.onFailure { running = false; onError?.invoke("No pude abrir la cámara: ${it.message}") }
    }

    /** Tamaño a pedir: delantera 480p o 720p (según tu cara), trasera 1080p (FHD). */
    private fun targetSize(): Size = when {
        facing == Facing.BACK -> Size(1920, 1080)
        lowRes -> Size(640, 480)
        else -> Size(1280, 720)
    }

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

    private fun closeCamera() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
    }

    /** Giro del cuadro para que quede derecho (según cómo tienes el teléfono). */
    private fun rotation(): Int {
        val disp = runCatching { (ctx as? android.app.Activity)?.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        val deg = when (disp) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return if (facing == Facing.FRONT) (sensorDeg + deg) % 360 else (sensorDeg - deg + 360) % 360
    }

    private fun onFrame(rd: ImageReader) {
        val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastStoreAt < 240) return       // (~4 cuadros por segundo: el avatar te sigue con la mirada y gasta poco)
            lastStoreAt = now
            val w = img.width; val h = img.height
            val nv = yuvToNv21(img)
            val rot = rotation()
            synchronized(lock) { lastNv21 = nv; lastW = w; lastH = h; lastRot = rot }
            lastFacing = facing
            lastFrameAt = now
            if (facing == Facing.FRONT) maybeDetect(nv, w, h, rot, now)
        } catch (e: Exception) {
            Log.w(TAG, "visión: cuadro", e)
        } finally { runCatching { img.close() } }
    }

    /**
     * 🙂 Tu cara (cada ~0,25 s): dónde estás, qué tan cerca y si sonríes → el avatar te sigue con los ojos y reacciona.
     * Además decide 480p (cara cerca) o 720p (mira lejos), con 2 s de espera antes de cambiar.
     */
    private fun maybeDetect(nv: ByteArray, w: Int, h: Int, rot: Int, now: Long) {
        if (detecting || now - lastDetectAt < 240) return
        detecting = true; lastDetectAt = now
        val input = com.google.mlkit.vision.common.InputImage.fromByteArray(nv, w, h, rot, com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val frameW = if (rot == 90 || rot == 270) h else w
                val frameH = if (rot == 90 || rot == 270) w else h
                val face = faces.maxByOrNull { it.boundingBox.width() }
                val biggest = face?.boundingBox?.width() ?: 0
                val close = biggest >= frameW * 0.22f       // (cara grande = estás a 10-50 cm)
                handler.post { decideRes(close) }
                // (la imagen de la cámara delantera NO está en espejo: tu derecha sale a la izquierda; se da vuelta
                //  para que x sea «como te ve el avatar» desde la pantalla)
                val seen = face?.let {
                    FaceSeen(1f - it.boundingBox.exactCenterX() / frameW, it.boundingBox.exactCenterY() / frameH,
                        it.boundingBox.width().toFloat() / frameW, it.smilingProbability ?: 0f)
                }
                runCatching { onFace?.invoke(seen) }
            }
            .addOnCompleteListener { detecting = false }
    }

    private fun decideRes(wantLow: Boolean) {
        if (facing != Facing.FRONT || device == null) return
        val now = SystemClock.uptimeMillis()
        if (wantLow == lowRes) { candidateLow = null; return }
        if (candidateLow != wantLow) { candidateLow = wantLow; candidateSince = now; return }
        if (now - candidateSince >= 2000) {
            lowRes = wantLow; candidateLow = null
            Log.i(TAG, "visión: delantera a " + if (wantLow) "480p (cara cerca)" else "720p (mira lejos)")
            startSession()
        }
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
