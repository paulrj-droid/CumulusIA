package com.paulrj.cumulusia.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * 🙋 (27-nov-2026) GESTOS DEL USUARIO (Avatar visión): con la cámara delantera que ya está prendida, mira tu cara y tu
 * mano con MediaPipe de Google (dentro del teléfono, sin internet; licencia Apache 2.0, apta para uso comercial) y avisa
 * cuando haces un gesto: saludar con la mano, levantar una ceja o las dos, reírte, sonreír, guiñar un ojo, cerrar los
 * dos ojos, arrugar la nariz o poner cara de enojo.
 *
 * Solo mira cuando [allowed] dice que sí (la app lo usa para que funcione ÚNICAMENTE cuando tú y el avatar están
 * callados). Mientras no se permite, no gasta nada: los cuadros se botan sin analizar.
 * Analiza ~4 cuadros por segundo (la mano, 2 por segundo) y el gesto tiene que mantenerse un instante para contar
 * (así un parpadeo normal no se toma como «cerrar los ojos»).
 *
 * Los modelos (face_landmarker.task y gesture_recognizer.task) se bajan solos al compilar (app/build.gradle).
 * Si faltan, esto se apaga solo y el resto de la visión sigue igual.
 */
class GestureWatcher(private val ctx: Context) {

    companion object {
        private const val TAG = "GestureWatcher"
        const val WAVE = 0          // 👋 saludando con la mano
        const val BROW_ONE = 1      // 🤨 una ceja arriba
        const val BROWS_BOTH = 2    // 😮 las dos cejas arriba (frente arrugada)
        const val LAUGH = 3         // 😄 riéndose
        const val SMILE = 4         // 😊 sonriendo
        const val WINK = 5          // 😉 guiñando un ojo
        const val EYES_CLOSED = 6   // 😌 los dos ojos cerrados
        const val NOSE = 7          // 😖 nariz arrugada
        const val ANGRY = 8         // 😠 cara de enojo

        /** Cuántos análisis seguidos (~0,25 s cada uno) tiene que durar cada gesto para contar. */
        private val HOLD = intArrayOf(3, 3, 3, 3, 4, 2, 5, 3, 4)
    }

    /** ¿Se puede mirar ahora? (lo decide la app: tú y el avatar callados). */
    @Volatile var allowed: (() -> Boolean)? = null
    /** Se hizo un gesto (llega desde otro hilo). */
    @Volatile var onGesture: ((Int) -> Unit)? = null

    private val thread = HandlerThread("cumulus-gestures").also { it.start() }
    private val handler = Handler(thread.looper)
    @Volatile private var busy = false
    private var lastAt = 0L
    private var frameN = 0
    private var face: FaceLandmarker? = null
    private var hands: GestureRecognizer? = null
    @Volatile private var failed = false
    private var lastKind = -1
    private var streak = 0
    private var firedKind = -1

    /** Un cuadro de la cámara (NV21). Se analiza solo si toca y está permitido. */
    fun offer(nv: ByteArray, w: Int, h: Int, rot: Int) {
        if (failed || busy) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 250) return
        val ok = runCatching { allowed?.invoke() ?: false }.getOrDefault(false)
        if (!ok) { lastKind = -1; streak = 0; firedKind = -1; return }
        lastAt = now; busy = true
        handler.post {
            try { analyze(nv, w, h, rot) } catch (e: Throwable) { Log.w(TAG, "gestos: análisis", e) } finally { busy = false }
        }
    }

    fun release() {
        handler.post {
            runCatching { face?.close() }; runCatching { hands?.close() }
            face = null; hands = null
            thread.quitSafely()
        }
    }

    private fun ensureModels(): Boolean {
        if (face != null) return true
        if (failed) return false
        return try {
            face = FaceLandmarker.createFromOptions(ctx, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("mediapipe/face_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .build())
            hands = runCatching {
                GestureRecognizer.createFromOptions(ctx, GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("mediapipe/gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .build())
            }.getOrNull()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "gestos: no pude cargar MediaPipe (¿faltan los modelos?)", e)
            failed = true
            false
        }
    }

    private fun analyze(nv: ByteArray, w: Int, h: Int, rot: Int) {
        if (!ensureModels()) return
        val bmp = nv21Half(nv, w, h, rot) ?: return
        try {
            val img = BitmapImageBuilder(bmp).build()
            var kind = -1
            // 🙂 la cara: los ~50 movimientos de la cara (0 a 1)
            val res = face?.detect(img)
            val shapes = res?.faceBlendshapes()?.orElse(null)?.firstOrNull()
            if (shapes != null) {
                val s = HashMap<String, Float>(64)
                for (c in shapes) s[c.categoryName()] = c.score()
                kind = classify(s)
            }
            // 🖐️ la mano (2 veces por segundo): mano abierta levantada = saludo
            frameN++
            if (kind < 0 && hands != null && frameN % 2 == 0) {
                val g = runCatching { hands?.recognize(img) }.getOrNull()
                val top = g?.gestures()?.firstOrNull()?.firstOrNull()
                if (top != null && top.categoryName() == "Open_Palm" && top.score() > 0.6f) kind = WAVE
            } else if (kind < 0 && lastKind == WAVE && frameN % 2 == 1) kind = WAVE   // (entre medio se mantiene)
            step(kind)
        } finally { runCatching { bmp.recycle() } }
    }

    /** Qué gesto hay en la cara (o −1 si ninguno). El orden importa: lo más claro primero. */
    private fun classify(s: Map<String, Float>): Int {
        fun v(k: String) = s[k] ?: 0f
        val blinkL = v("eyeBlinkLeft"); val blinkR = v("eyeBlinkRight")
        val smile = (v("mouthSmileLeft") + v("mouthSmileRight")) / 2f
        val jaw = v("jawOpen")
        val outL = v("browOuterUpLeft"); val outR = v("browOuterUpRight"); val inner = v("browInnerUp")
        val downB = (v("browDownLeft") + v("browDownRight")) / 2f
        val sneer = (v("noseSneerLeft") + v("noseSneerRight")) / 2f
        return when {
            blinkL > 0.6f && blinkR > 0.6f && smile < 0.5f -> EYES_CLOSED
            kotlin.math.max(blinkL, blinkR) > 0.6f && kotlin.math.min(blinkL, blinkR) < 0.3f -> WINK
            smile > 0.5f && jaw > 0.3f -> LAUGH
            smile > 0.55f -> SMILE
            inner > 0.5f && kotlin.math.min(outL, outR) > 0.35f -> BROWS_BOTH
            kotlin.math.max(outL, outR) > 0.45f && kotlin.math.abs(outL - outR) > 0.3f -> BROW_ONE
            sneer > 0.45f -> NOSE
            downB > 0.5f && smile < 0.2f -> ANGRY
            else -> -1
        }
    }

    /** Cuenta cuánto dura el gesto; al cumplir su tiempo avisa UNA vez (hasta que lo sueltes y lo vuelvas a hacer). */
    private fun step(kind: Int) {
        if (kind != lastKind) { lastKind = kind; streak = 0; if (kind != firedKind) firedKind = -1 }
        if (kind < 0) { firedKind = -1; return }
        streak++
        if (streak >= HOLD[kind] && firedKind != kind) {
            firedKind = kind
            runCatching { onGesture?.invoke(kind) }
        }
    }

    /** NV21 → imagen a la mitad del tamaño (320×240), ya derecha. */
    private fun nv21Half(nv: ByteArray, w: Int, h: Int, rot: Int): Bitmap? = runCatching {
        val hw = w / 2; val hh = h / 2
        val px = IntArray(hw * hh)
        val frame = w * h
        for (y in 0 until hh) {
            val yRow = (y * 2) * w
            val uvRow = frame + y * w
            for (x in 0 until hw) {
                val yy = (nv[yRow + x * 2].toInt() and 0xFF) - 16
                val uvI = uvRow + x * 2
                val vv = (nv[uvI].toInt() and 0xFF) - 128
                val uu = (nv[uvI + 1].toInt() and 0xFF) - 128
                val y1 = 1192 * (if (yy < 0) 0 else yy)
                var r = (y1 + 1634 * vv) shr 10
                var g = (y1 - 833 * vv - 400 * uu) shr 10
                var b = (y1 + 2066 * uu) shr 10
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                px[y * hw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, hw, hh, Bitmap.Config.ARGB_8888)
        if (rot == 0) bmp else {
            val r = Bitmap.createBitmap(bmp, 0, 0, hw, hh, Matrix().apply { postRotate(rot.toFloat()) }, true)
            if (r !== bmp) bmp.recycle()
            r
        }
    }.getOrNull()
}
