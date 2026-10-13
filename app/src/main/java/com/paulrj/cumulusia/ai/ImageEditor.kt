package com.paulrj.cumulusia.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.paulrj.cumulusia.ai.ImageEditCommand.BgMode
import com.paulrj.cumulusia.ai.ImageEditCommand.CropMode
import com.paulrj.cumulusia.ai.ImageEditCommand.Kind
import com.paulrj.cumulusia.ai.ImageEditCommand.Step
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 🖼️ EDITOR DE IMÁGENES CON IA (todo dentro del teléfono, se pide por el chat)
 *  ✂️ Recortar (a la persona / objeto, cuadrada, 16:9, mitades, bordes…)
 *  📈 Agrandar y subir la resolución con IA (Real-ESRGAN) · 📉 achicar y bajar la resolución
 *  🎨 Cambiar el fondo (color, transparente, borroso u otra foto)
 *  🧽 Quitar a una persona (la IA MI-GAN rellena el hueco) · 🔄 cambiarla por la persona de otra foto
 *  🪄 Versión nueva de la misma imagen (otro estilo, de noche, con nieve…) con SDXS (stable-diffusion.cpp)
 *
 * Motor: las IA de resolución y de relleno usan SOLO el motor elegido en ⚙️ (NPU, GPU o CPU).
 * Si ese motor falla, se avisa con el motivo y no se cambia a otro. La versión nueva usa la CPU
 * (ese tipo de IA no corre en la NPU). Separar personas y fondo lo hace ML Kit de Google.
 * Nada se guarda en la galería: el resultado queda en el chat.
 */
class ImageEditor(
    private val context: Context,
    private val engine: ImageEngine,
    private val onProgress: (String) -> Unit,
    private val isCancelled: () -> Boolean
) {
    class Cancelled : Exception("Edición detenida")
    class NeedsInfo(message: String) : Exception(message)

    class Outcome(val bitmap: Bitmap, val transparent: Boolean, val summary: List<String>, val tech: List<String>)

    private val summary = mutableListOf<String>()
    private val tech = linkedSetOf<String>()
    private var lastProgressAt = 0L

    private fun progress(text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAt < 350) return
        lastProgressAt = now
        runCatching { onProgress(text) }
    }

    private fun checkStop() { if (isCancelled()) throw Cancelled() }

    private val models = ImageAiModels(context, { progress(it, true) }, isCancelled)

    // =====================================================================
    // ▶️ Hace todos los pasos pedidos, uno detrás de otro
    // =====================================================================
    fun process(steps: List<Step>, base: Bitmap, second: Bitmap?): Outcome {
        var cur = base
        var transparent = false
        for (step in steps) {
            checkStop()
            val next: Bitmap = when (step.kind) {
                Kind.CROP -> crop(cur, step)
                Kind.UPSCALE, Kind.DOWNSCALE -> resize(cur, step)
                Kind.BACKGROUND -> {
                    if (step.bgMode == BgMode.IMAGE && second == null) throw NeedsInfo("Para poner otra foto de fondo, adjunta las dos imágenes juntas: primero la foto y después el fondo nuevo.")
                    val r = background(cur, step, second)
                    transparent = step.bgMode == BgMode.TRANSPARENT
                    r
                }
                Kind.REMOVE_PERSON -> removeSubject(cur, step.allSubjects)
                Kind.REPLACE_PERSON -> {
                    if (second == null) throw NeedsInfo("Para cambiar a la persona necesito dos fotos en el mismo mensaje.")
                    if (step.swapImages) replacePerson(second, cur) else replacePerson(cur, second)
                }
                Kind.VARIATION -> variation(cur, step)
                Kind.ASK_SECOND_IMAGE, Kind.ASK_BACKGROUND -> cur
            }
            if (next !== cur && cur !== base) cur.recycle()
            cur = next
            // Un fondo transparente se pierde si después se hace algo que no conserva la transparencia
            if (step.kind != Kind.BACKGROUND && step.kind != Kind.CROP && step.kind != Kind.UPSCALE && step.kind != Kind.DOWNSCALE) transparent = false
        }
        return Outcome(cur, transparent && cur.hasAlpha(), summary.toList(), tech.toList())
    }

    // =====================================================================
    // ✂️ RECORTAR
    // =====================================================================
    private fun crop(src: Bitmap, s: Step): Bitmap {
        val w = src.width; val h = src.height
        var r: Rect = when (s.cropMode) {
            CropMode.TOP -> Rect(0, 0, w, h / 2)
            CropMode.BOTTOM -> Rect(0, h - h / 2, w, h)
            CropMode.LEFT -> Rect(0, 0, w / 2, h)
            CropMode.RIGHT -> Rect(w - w / 2, 0, w, h)
            CropMode.CENTER -> Rect((w * 0.15f).toInt(), (h * 0.15f).toInt(), (w * 0.85f).toInt(), (h * 0.85f).toInt())
            CropMode.PERCENT -> {
                val dx = (w * s.percent / 100f).toInt(); val dy = (h * s.percent / 100f).toInt()
                Rect(dx, dy, w - dx, h - dy)
            }
            CropMode.RATIO -> ratioRect(src, s.ratioW, s.ratioH)
            CropMode.SUBJECT, CropMode.AUTO -> subjectRect(src, s.cropMode == CropMode.AUTO)
        }
        r = Rect(r.left.coerceIn(0, w - 1), r.top.coerceIn(0, h - 1), r.right.coerceIn(1, w), r.bottom.coerceIn(1, h))
        if (r.width() < 8 || r.height() < 8) r = Rect(0, 0, w, h)
        var out = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
        if (s.exactW > 0 && s.exactH > 0) {
            val sized = scaleSmart(out, s.exactW.coerceIn(16, MAX_SIDE), s.exactH.coerceIn(16, MAX_SIDE))
            if (sized !== out) out.recycle()
            out = sized
        }
        summary += when (s.cropMode) {
            CropMode.TOP -> "✂️ Recorté la mitad de arriba"
            CropMode.BOTTOM -> "✂️ Recorté la mitad de abajo"
            CropMode.LEFT -> "✂️ Recorté la mitad izquierda"
            CropMode.RIGHT -> "✂️ Recorté la mitad derecha"
            CropMode.CENTER -> "✂️ Recorté el centro"
            CropMode.PERCENT -> "✂️ Le quité ${s.percent} % a cada borde"
            CropMode.RATIO -> if (s.exactW > 0) "✂️ La recorté a ${out.width}×${out.height}" else "✂️ La recorté en formato ${s.ratioW}:${s.ratioH}"
            CropMode.SUBJECT, CropMode.AUTO -> "✂️ La recorté dejando solo lo principal"
        } + " (${w}×$h → ${out.width}×${out.height})."
        return out
    }

    /** El recuadro más grande con esa proporción, centrado en lo principal de la foto. */
    private fun ratioRect(src: Bitmap, rw: Int, rh: Int): Rect {
        val w = src.width; val h = src.height
        val target = rw.toFloat() / rh
        var cw = w; var ch = (w / target).roundToInt()
        if (ch > h) { ch = h; cw = (h * target).roundToInt() }
        cw = cw.coerceIn(1, w); ch = ch.coerceIn(1, h)
        var cx = w / 2f; var cy = h / 2f
        val box = runCatching { quickSubjectBox(src) }.getOrNull()
        if (box != null) { cx = box.exactCenterX(); cy = box.exactCenterY() }
        val left = (cx - cw / 2f).roundToInt().coerceIn(0, w - cw)
        val top = (cy - ch / 2f).roundToInt().coerceIn(0, h - ch)
        return Rect(left, top, left + cw, top + ch)
    }

    /** Recuadro de lo principal (persona, objeto o logo) con un poco de margen. */
    private fun subjectRect(src: Bitmap, auto: Boolean): Rect {
        val w = src.width; val h = src.height
        // 1) Logo o dibujo sobre un fondo de un solo color
        borderColor(src)?.let { bg ->
            uniformBox(src, bg)?.let { b -> return pad(b, w, h, 0.04f) }
        }
        // 2) Foto: la IA de Google separa a la persona / objeto
        val seg = runCatching { segment(src, multi = false) }.getOrElse { if (it is Cancelled) throw it; null }
        if (seg != null) {
            val b = maskBox(seg.fg, seg.w, seg.h, 0.5f)
            if (b != null) {
                val f = w.toFloat() / seg.w
                return pad(Rect((b.left * f).toInt(), (b.top * f).toInt(), (b.right * f).toInt(), (b.bottom * f).toInt()), w, h, 0.06f)
            }
        }
        // 3) Sin nada claro: se quitan los bordes (10 %)
        if (!auto) summary += "ℹ️ No encontré una persona u objeto claro; recorté los bordes."
        return Rect((w * 0.1f).toInt(), (h * 0.1f).toInt(), (w * 0.9f).toInt(), (h * 0.9f).toInt())
    }

    private fun pad(r: Rect, w: Int, h: Int, f: Float): Rect {
        val dx = (max(r.width(), r.height()) * f).toInt()
        return Rect((r.left - dx).coerceAtLeast(0), (r.top - dx).coerceAtLeast(0), (r.right + dx).coerceAtMost(w), (r.bottom + dx).coerceAtMost(h))
    }

    /** Para centrar un recorte: no espera descargas (si la IA de Google no está lista, usa el centro). */
    private fun quickSubjectBox(src: Bitmap): Rect? {
        borderColor(src)?.let { bg -> return uniformBox(src, bg) }
        val seg = segment(src, multi = false, maxWaitMs = 6000) ?: return null
        val b = maskBox(seg.fg, seg.w, seg.h, 0.5f) ?: return null
        val f = src.width.toFloat() / seg.w
        return Rect((b.left * f).toInt(), (b.top * f).toInt(), (b.right * f).toInt(), (b.bottom * f).toInt())
    }

    // =====================================================================
    // 📐 AGRANDAR / ACHICAR · SUBIR / BAJAR LA RESOLUCIÓN
    // =====================================================================
    private fun resize(src: Bitmap, s: Step): Bitmap {
        val w = src.width; val h = src.height
        val long = max(w, h)
        var tw: Int; var th: Int
        when {
            s.exactW > 0 && s.exactH > 0 -> { tw = s.exactW; th = s.exactH }
            s.longSide > 0 -> { val f = s.longSide.toFloat() / long; tw = (w * f).roundToInt(); th = (h * f).roundToInt() }
            s.factor > 0f -> { tw = (w * s.factor).roundToInt(); th = (h * s.factor).roundToInt() }
            s.kind == Kind.DOWNSCALE -> { tw = w / 2; th = h / 2 }
            else -> {   // subir sin decir cuánto: ×4 si es chica, ×2 si es mediana, hasta 4096 si es grande
                val f = when { long <= 640 -> 4f; long <= 2048 -> 2f; else -> MAX_SIDE.toFloat() / long }
                tw = (w * f).roundToInt(); th = (h * f).roundToInt()
            }
        }
        // Tope del teléfono: 4096 px por lado
        val cap = min(1f, MAX_SIDE.toFloat() / max(tw, th))
        if (cap < 1f) { tw = (tw * cap).roundToInt(); th = (th * cap).roundToInt() }
        tw = tw.coerceAtLeast(16); th = th.coerceAtLeast(16)
        val capped = cap < 1f
        // Tamaño exacto con otra proporción: se recorta al centro para no deformar
        var work = src
        if (s.exactW > 0 && s.exactH > 0 && abs(tw.toFloat() / th - w.toFloat() / h) > 0.03f) {
            val r = ratioRect(src, tw, th)
            work = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            summary += "✂️ Ajusté el encuadre a la proporción ${tw}:${th} para que no se deforme."
        }
        // Bajar la resolución nunca la agranda
        if (s.kind == Kind.DOWNSCALE && tw.toLong() * th >= w.toLong() * h) {
            if (work !== src) work.recycle()
            summary += "ℹ️ La imagen ya es de ${w}×$h: no hacía falta bajarle la resolución."
            return src
        }
        val wantsAi = s.kind == Kind.UPSCALE
        val out = if (wantsAi) aiResize(work, tw, th) else scaleSmart(work, tw, th)
        if (work !== src && work !== out) work.recycle()
        val bigger = tw.toLong() * th > w.toLong() * h
        summary += when {
            wantsAi && bigger -> "📈 Subí la resolución con IA de ${w}×$h a ${out.width}×${out.height}"
            wantsAi -> "✨ Mejoré la nitidez con IA (${out.width}×${out.height})"
            else -> "📉 Bajé la resolución de ${w}×$h a ${out.width}×${out.height}"
        } + (if (capped) " (4096 px es lo máximo que maneja el teléfono)." else ".")
        return out
    }

    /** Escala con buena calidad (al achicar mucho, se achica a la mitad varias veces). */
    private fun scaleSmart(src: Bitmap, tw: Int, th: Int): Bitmap {
        if (tw == src.width && th == src.height) return src
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th && cur.width / 2 >= 16) {
            val half = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = half
        }
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    /** Agranda o mejora con la IA de súper resolución, en el motor elegido. */
    private fun aiResize(src: Bitmap, tw: Int, th: Int): Bitmap {
        val spec = if (engine == ImageEngine.NPU) ImageAiModels.SR_NPU else ImageAiModels.SR_LIGHT
        val model = (try {
            models.openTflite(spec, engine, floatModel = spec !== ImageAiModels.SR_NPU)
        } catch (e: ImageEngineError) {
            throw engineProblem("la IA de resolución", e)
        }) ?: throw NeedsInfo("No pude descargar la IA de resolución (≈${spec.sizeMb} MB). La primera vez necesito internet; revisa tu conexión y vuelve a pedírmelo." +
            models.lastError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(100)})" }.orEmpty())
        try {
            tech += "IA de resolución ${model.label} en ${engine.label}"
            // La IA entrega ×4: se le da la foto a la mitad del tamaño final (o la original si es más chica),
            // con un tope por motor para que no tarde demasiado
            val engineCap = when (engine) { ImageEngine.NPU -> 2048; ImageEngine.GPU -> 2048; ImageEngine.CPU -> 1280 }
            val srcLong = max(src.width, src.height); val tLong = max(tw, th)
            val inLong = min(min(srcLong, engineCap), max(16, ceil(tLong / 2.0).toInt()))
            val input = if (inLong < srcLong) scaleSmart(src, (src.width * inLong.toFloat() / srcLong).roundToInt().coerceAtLeast(8),
                (src.height * inLong.toFloat() / srcLong).roundToInt().coerceAtLeast(8)) else src
            val out = try {
                superResolve(model, input, tw, th)
            } catch (c: Cancelled) {
                throw c
            } catch (t: Throwable) {
                throw engineProblem("la IA de resolución", ImageEngineError(t.message?.take(140) ?: t.javaClass.simpleName))
            }
            if (input !== src) input.recycle()
            return out
        } finally { model.close() }
    }

    private fun engineProblem(what: String, e: ImageEngineError): NeedsInfo {
        val others = ImageEngine.values().filter { it != engine }.joinToString(" o ") { it.name }
        return NeedsInfo("⚠️ No pude usar ${engine.label} para $what.\nMotivo: ${e.message}\n\n" +
            "La app no cambia de motor sola: si quieres, cambia el motor a $others en ⚙️ y vuelve a pedírmelo.")
    }

    /** Lee y escribe tensores de cualquier tipo (float, uint8 o int8 cuantizados). */
    private class TensorIO(t: org.tensorflow.lite.Tensor) {
        val type: String = t.dataType().name
        private val scale: Float
        private val zero: Int
        init {
            val q = runCatching { t.quantizationParams() }.getOrNull()
            scale = q?.scale ?: 0f
            zero = q?.zeroPoint ?: 0
        }
        val bytesPer: Int = when (type) { "UINT8", "INT8" -> 1; else -> 4 }

        fun put(buf: ByteBuffer, v: Float) {
            when (type) {
                "UINT8" -> {
                    val q = if (scale > 0f) (v / scale).roundToInt() + zero else (v * 255f).roundToInt()
                    buf.put(q.coerceIn(0, 255).toByte())
                }
                "INT8" -> {
                    val q = if (scale > 0f) (v / scale).roundToInt() + zero else (v * 255f).roundToInt() - 128
                    buf.put(q.coerceIn(-128, 127).toByte())
                }
                else -> buf.putFloat(v)
            }
        }

        fun get(buf: ByteBuffer): Float = when (type) {
            "UINT8" -> { val q = buf.get().toInt() and 0xFF; if (scale > 0f) (q - zero) * scale else q / 255f }
            "INT8" -> { val q = buf.get().toInt(); if (scale > 0f) (q - zero) * scale else (q + 128) / 255f }
            else -> buf.float
        }
    }

    private fun superResolve(m: TfModel, src: Bitmap, tw: Int, th: Int): Bitmap {
        val itp = m.interpreter
        val inT = itp.getInputTensor(0); val outT = itp.getOutputTensor(0)
        val inIO = TensorIO(inT); val outIO = TensorIO(outT)
        val inShape = inT.shape(); val outShape = outT.shape()
        val inNchw = inShape.size == 4 && inShape[1] == 3 && inShape[3] != 3
        val outNchw = outShape.size == 4 && outShape[1] == 3 && outShape[3] != 3
        val tileH = if (inNchw) inShape[2] else inShape[1]
        val tileW = if (inNchw) inShape[3] else inShape[2]
        val outTH = if (outNchw) outShape[2] else outShape[1]
        val outTW = if (outNchw) outShape[3] else outShape[2]
        val sc = max(1, outTH / tileH)
        val pad = min(10, tileW / 8)
        val stepW = tileW - 2 * pad; val stepH = tileH - 2 * pad
        val w = src.width; val h = src.height
        val inBuf = ByteBuffer.allocateDirect(inIO.bytesPer * 3 * tileW * tileH).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(outIO.bytesPer * 3 * outTW * outTH).order(ByteOrder.nativeOrder())
        val srcPx = IntArray(tileW * tileH)
        val outPx = IntArray(outTW * outTH)
        val tileBmp = Bitmap.createBitmap(outTW, outTH, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val fx = tw.toFloat() / w; val fy = th.toFloat() / h
        val tilesX = (w + stepW - 1) / stepW; val tilesY = (h + stepH - 1) / stepH
        val total = tilesX * tilesY; var done = 0
        val rowPx = IntArray(w)
        val n = outTW * outTH
        val rgbOut = FloatArray(3)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            checkStop()
            val x0 = tx * stepW; val y0 = ty * stepH
            val x1 = min(x0 + stepW, w); val y1 = min(y0 + stepH, h)
            val wx = (x0 - pad).coerceAtMost(w - tileW).coerceAtLeast(0)
            val wy = (y0 - pad).coerceAtMost(h - tileH).coerceAtLeast(0)
            // Pedazo de la foto (si la foto es más chica que el pedazo, se repite el borde)
            for (yy in 0 until tileH) {
                val sy = (wy + yy).coerceAtMost(h - 1)
                val cw = min(tileW, w - wx)
                src.getPixels(rowPx, 0, w, wx, sy, cw, 1)
                for (xx in 0 until tileW) srcPx[yy * tileW + xx] = rowPx[min(xx, cw - 1)]
            }
            inBuf.rewind()
            if (inNchw) {
                for (c in 0 until 3) { val shift = 16 - 8 * c; for (i in 0 until tileW * tileH) inIO.put(inBuf, ((srcPx[i] shr shift) and 0xFF) / 255f) }
            } else {
                for (i in 0 until tileW * tileH) {
                    val p = srcPx[i]
                    inIO.put(inBuf, ((p shr 16) and 0xFF) / 255f); inIO.put(inBuf, ((p shr 8) and 0xFF) / 255f); inIO.put(inBuf, (p and 0xFF) / 255f)
                }
            }
            inBuf.rewind(); outBuf.rewind()
            itp.run(inBuf, outBuf)
            outBuf.rewind()
            if (outNchw) {
                for (i in 0 until n) outPx[i] = 0xFF000000.toInt()
                for (c in 0 until 3) { val shift = 16 - 8 * c; for (i in 0 until n) outPx[i] = outPx[i] or (to255(outIO.get(outBuf)) shl shift) }
            } else {
                for (i in 0 until n) {
                    rgbOut[0] = outIO.get(outBuf); rgbOut[1] = outIO.get(outBuf); rgbOut[2] = outIO.get(outBuf)
                    outPx[i] = (0xFF shl 24) or (to255(rgbOut[0]) shl 16) or (to255(rgbOut[1]) shl 8) or to255(rgbOut[2])
                }
            }
            tileBmp.setPixels(outPx, 0, outTW, 0, 0, outTW, outTH)
            val srcR = Rect((x0 - wx) * sc, (y0 - wy) * sc, (x1 - wx) * sc, (y1 - wy) * sc)
            val dstR = Rect((x0 * fx).roundToInt(), (y0 * fy).roundToInt(), (x1 * fx).roundToInt(), (y1 * fy).roundToInt())
            if (dstR.width() > 0 && dstR.height() > 0) canvas.drawBitmap(tileBmp, srcR, dstR, paint)
            done++
            progress("✨ Subiendo la resolución con IA en ${engine.label}… ${done * 100 / total}%")
        }
        tileBmp.recycle()
        // Transparencia (logos PNG): se agranda aparte
        if (src.hasAlpha() && hasRealAlpha(src)) {
            val a = Bitmap.createScaledBitmap(src, tw, th, true)
            val row = IntArray(tw); val arow = IntArray(tw)
            for (y in 0 until th) {
                result.getPixels(row, 0, tw, 0, y, tw, 1); a.getPixels(arow, 0, tw, 0, y, tw, 1)
                for (x in 0 until tw) row[x] = (arow[x] and 0xFF000000.toInt()) or (row[x] and 0x00FFFFFF)
                result.setPixels(row, 0, tw, 0, y, tw, 1)
            }
            a.recycle()
        }
        return result
    }

    private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun hasRealAlpha(b: Bitmap): Boolean {
        val step = max(1, max(b.width, b.height) / 200)
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { if (Color.alpha(b.getPixel(x, y)) < 250) return true; x += step }; y += step }
        return false
    }

    // =====================================================================
    // 🎨 FONDO: color, transparente, borroso u otra foto
    //    La máscara se calcula a ≤2048 px y el resultado sale del tamaño original.
    // =====================================================================
    private fun background(src: Bitmap, s: Step, second: Bitmap?): Bitmap {
        val small = capTo(src, MASK_SIDE)
        val mask: Mask = maskFor(small)
        checkStop()
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var bgImg: Bitmap? = null
        when (s.bgMode) {
            BgMode.BLUR -> bgImg = blurred(src)
            BgMode.IMAGE -> bgImg = coverScale(second!!, w, h)
            else -> Unit
        }
        val newColor = s.color
        val row = IntArray(w); val bgRow = IntArray(w)
        val mw = mask.w; val mh = mask.h
        val al = mask.alpha
        for (y in 0 until h) {
            if (y % 64 == 0) { checkStop(); progress("🎨 Cambiando el fondo… ${y * 100 / h}%") }
            src.getPixels(row, 0, w, 0, y, w, 1)
            bgImg?.getPixels(bgRow, 0, w, 0, y, w, 1)
            // Fila de la máscara (bilineal) y del color corregido del borde (el más cercano)
            val fy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, (mh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(mh - 1, y0 + 1); val ty = fy - y0
            val ny = min(mh - 1, ((y + 0.5f) * mh / h).toInt())
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, (mw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(mw - 1, x0 + 1); val tx = fx - x0
                val a = (al[y0 * mw + x0] * (1 - tx) + al[y0 * mw + x1] * tx) * (1 - ty) +
                    (al[y1 * mw + x0] * (1 - tx) + al[y1 * mw + x1] * tx) * ty
                val nx = min(mw - 1, ((x + 0.5f) * mw / w).toInt())
                val fg = mask.fgColor(ny * mw + nx, row[x])
                row[x] = when (s.bgMode) {
                    BgMode.TRANSPARENT -> ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (fg and 0x00FFFFFF)
                    BgMode.COLOR -> mix(fg, newColor, a)
                    BgMode.BLUR, BgMode.IMAGE -> mix(fg, bgRow[x], a)
                }
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        bgImg?.recycle()
        if (small !== src) small.recycle()
        if (s.bgMode == BgMode.TRANSPARENT) out.setHasAlpha(true)
        summary += when (s.bgMode) {
            BgMode.COLOR -> "🎨 Le cambié el fondo a ${s.colorName}."
            BgMode.TRANSPARENT -> "🎨 Le quité el fondo (queda transparente, en PNG)."
            BgMode.BLUR -> "🎨 Le puse el fondo borroso (efecto retrato)."
            BgMode.IMAGE -> "🎨 Le puse de fondo la segunda imagen."
        }
        return out
    }

    private fun mix(fg: Int, bg: Int, a: Float): Int {
        if (a >= 0.999f) return fg or 0xFF000000.toInt()
        if (a <= 0.001f) return bg or 0xFF000000.toInt()
        val r = (Color.red(fg) * a + Color.red(bg) * (1 - a)).toInt()
        val g = (Color.green(fg) * a + Color.green(bg) * (1 - a)).toInt()
        val b = (Color.blue(fg) * a + Color.blue(bg) * (1 - a)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Fondo borroso: se achica mucho y se vuelve a agrandar (rápido y suave). */
    private fun blurred(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val sw = max(8, w / 24); val sh = max(8, h / 24)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val mid = Bitmap.createScaledBitmap(small, max(8, w / 6), max(8, h / 6), true)
        small.recycle()
        val out = Bitmap.createScaledBitmap(mid, w, h, true)
        mid.recycle()
        return out
    }

    /** La foto de fondo nueva cubre todo (se recorta al centro). */
    private fun coverScale(b: Bitmap, w: Int, h: Int): Bitmap {
        val f = max(w.toFloat() / b.width, h.toFloat() / b.height)
        val sw = (b.width * f).roundToInt().coerceAtLeast(w); val sh = (b.height * f).roundToInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(b, sw, sh, true)
        val out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (out !== scaled) scaled.recycle()
        return out
    }

    // ---------------------------------------------------------------- máscara (qué es fondo y qué no)
    /** alpha[i]: 1 = persona/objeto/logo · 0 = fondo. fgColor corrige el borde para que no quede el color del fondo viejo. */
    private inner class Mask(val w: Int, val h: Int, val alpha: FloatArray, private val fix: IntArray?, private val fixWeight: FloatArray?) {
        fun fgColor(i: Int, original: Int): Int {
            val f = fix ?: return original
            val k = fixWeight!![i]
            if (k <= 0f) return original
            return mix(f[i], original, k)
        }
    }

    private fun maskFor(src: Bitmap): Mask {
        val bg = borderColor(src)
        if (bg != null) {
            tech += "Fondo de un solo color (sin IA)"
            return colorKeyMask(src, bg)
        }
        progress("🧠 Separando a la persona u objeto del fondo…", true)
        val seg = segment(src, multi = false) ?: throw NeedsInfo(
            "No pude separar la persona u objeto del fondo. La primera vez se necesita internet (la IA de Google se descarga sola): revisa tu conexión e inténtalo de nuevo.")
        tech += "Separar fondo: ML Kit (Google)"
        return photoMask(src, seg.fg, seg.w, seg.h)
    }

    /** Foto: máscara de la IA de Google, más suave en el borde y sin halo del fondo viejo. */
    private fun photoMask(src: Bitmap, small: FloatArray, sw: Int, sh: Int): Mask {
        val w = src.width; val h = src.height
        val s2 = FloatArray(small.size) { ((small[it] - 0.2f) / 0.6f).coerceIn(0f, 1f) }
        val alpha = upsample(s2, sw, sh, w, h)
        // Color del borde: el del pixel "seguro" (persona) más cercano, a la resolución chica
        val sure = BooleanArray(s2.size) { s2[it] >= 0.95f }
        val near = nearestIndex(sure, sw, sh)
        val smallBmp = Bitmap.createScaledBitmap(src, sw, sh, true)
        val smallPx = IntArray(sw * sh); smallBmp.getPixels(smallPx, 0, sw, 0, 0, sw, sh)
        if (smallBmp !== src) smallBmp.recycle()
        val fix = IntArray(w * h); val k = FloatArray(w * h)
        for (y in 0 until h) {
            val syi = min(sh - 1, (y * sh / h)); for (x in 0 until w) {
                val i = y * w + x; val a = alpha[i]
                if (a > 0.02f && a < 0.97f) {
                    val sxi = min(sw - 1, (x * sw / w)); val ni = near[syi * sw + sxi]
                    if (ni >= 0) { fix[i] = smallPx[ni]; k[i] = (1f - a) * 0.85f }
                }
            }
        }
        return Mask(w, h, alpha, fix, k)
    }

    /**
     * Logo o dibujo sobre fondo de un solo color (por ejemplo negro):
     * la silueta completa se mantiene (no se borran partes), las sombras oscuras del borde se
     * vuelven del color del logo (no quedan contornos negros) y el borde queda suave.
     */
    private fun colorKeyMask(src: Bitmap, bg: Int): Mask {
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)
        val br = Color.red(bg); val bgG = Color.green(bg); val bb = Color.blue(bg)
        val d = FloatArray(n)
        for (i in 0 until n) { val p = px[i]; d[i] = max(abs(Color.red(p) - br), max(abs(Color.green(p) - bgG), abs(Color.blue(p) - bb))).toFloat() }
        val v = gaussian(d, w, h, 0.6f)
        val thr = max(4f, borderNoise(d, w, h) * 3f + 2f)
        var logo = BooleanArray(n) { v[it] > thr }
        logo = removeSmall(logo, w, h, max(50, n / 500), true)
        val holes = removeSmall(BooleanArray(n) { !logo[it] }, w, h, max(20, n / 16000), true)
        for (i in 0 until n) logo[i] = !holes[i]
        checkStop()
        val dist = distanceToFalse(logo, w, h)
        val p99 = percentile(v, logo, 0.99f)
        val ref = thr + 0.42f * (p99 - thr)
        val bgDark = (br * 299 + bgG * 587 + bb * 114) / 1000 < 70
        val sat = FloatArray(n) { val p = px[it]; val mx = max(Color.red(p), max(Color.green(p), Color.blue(p))); val mn = min(Color.red(p), min(Color.green(p), Color.blue(p))); if (mx == 0) 0f else (mx - mn).toFloat() / mx }
        val srcSolid = BooleanArray(n) { logo[it] && v[it] > ref && v[it] < max(ref + 1f, p99 * 0.75f) && (!bgDark || sat[it] > 0.35f) }
        val hasSolid = srcSolid.any { it }
        val near = if (hasSolid) nearestIndex(srcSolid, w, h) else IntArray(n) { -1 }
        val va = gaussian(d, w, h, 1.1f)
        val alpha = FloatArray(n); val fix = IntArray(n); val k = FloatArray(n)
        val floor = 0.6f
        for (i in 0 until n) {
            if (!logo[i]) { alpha[i] = 0f; continue }
            var ae = ((va[i] - thr) / 22f).coerceIn(0f, 1f); ae = ae * ae * (3 - 2 * ae)
            val t = (dist[i] - 4f).coerceIn(0f, 1f)
            alpha[i] = ae * (1 - t) + t
            val ni = near[i]
            if (ni >= 0 && v[i] <= ref) {
                val nb = px[ni]
                val nbv = max(1f, max(abs(Color.red(nb) - br), max(abs(Color.green(nb) - bgG), abs(Color.blue(nb) - bb))).toFloat())
                val r = (v[i] / nbv).coerceIn(0f, 1f)
                val f = if (bgDark) floor + (1 - floor) * r else 1f
                fix[i] = Color.rgb((Color.red(nb) * f).toInt().coerceIn(0, 255), (Color.green(nb) * f).toInt().coerceIn(0, 255), (Color.blue(nb) * f).toInt().coerceIn(0, 255))
                k[i] = if (bgDark) 1f else (1f - alpha[i]).coerceIn(0f, 1f)
            }
        }
        val soft = gaussian(FloatArray(n) { if (logo[it]) 1f else 0f }, w, h, 0.7f)
        for (i in 0 until n) alpha[i] = min(alpha[i], (soft[i] * 1.6f).coerceAtMost(1f))
        return Mask(w, h, alpha, fix, k)
    }

    /** Color del fondo si TODO el borde de la imagen es de un solo color (logos, dibujos, fotos de producto). */
    private fun borderColor(b: Bitmap): Int? {
        val w = b.width; val h = b.height
        if (w < 16 || h < 16) return null
        val step = max(1, (w + h) / 600)
        val cols = ArrayList<Int>()
        var x = 0
        while (x < w) { cols += b.getPixel(x, 1); cols += b.getPixel(x, h - 2); x += step }
        var y = 0
        while (y < h) { cols += b.getPixel(1, y); cols += b.getPixel(w - 2, y); y += step }
        val rs = cols.map { Color.red(it) }.sorted(); val gs = cols.map { Color.green(it) }.sorted(); val bs = cols.map { Color.blue(it) }.sorted()
        val mr = rs[rs.size / 2]; val mg = gs[gs.size / 2]; val mb = bs[bs.size / 2]
        val ok = cols.count { max(abs(Color.red(it) - mr), max(abs(Color.green(it) - mg), abs(Color.blue(it) - mb))) <= 8 }
        return if (ok >= cols.size * 0.94f) Color.rgb(mr, mg, mb) else null
    }

    private fun uniformBox(src: Bitmap, bg: Int): Rect? {
        val w = src.width; val h = src.height
        var l = w; var t = h; var r = -1; var btm = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                if (max(abs(Color.red(p) - Color.red(bg)), max(abs(Color.green(p) - Color.green(bg)), abs(Color.blue(p) - Color.blue(bg)))) > 24) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > btm) btm = y
                }
            }
        }
        if (r < 0) return null
        val box = Rect(l, t, r + 1, btm + 1)
        return if (box.width() * box.height() > w * h * 0.97f) null else box
    }

    private fun borderNoise(d: FloatArray, w: Int, h: Int): Float {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (x in 0 until w) for (y in intArrayOf(0, 1, h - 2, h - 1)) { val v = d[y * w + x].toDouble(); s += v; s2 += v * v; n++ }
        for (y in 0 until h) for (x in intArrayOf(0, 1, w - 2, w - 1)) { val v = d[y * w + x].toDouble(); s += v; s2 += v * v; n++ }
        val mean = s / n
        return sqrt(max(0.0, s2 / n - mean * mean)).toFloat()
    }

    private fun percentile(v: FloatArray, sel: BooleanArray, q: Float): Float {
        val hist = IntArray(256); var tot = 0
        for (i in v.indices) if (sel[i]) { hist[v[i].toInt().coerceIn(0, 255)]++; tot++ }
        if (tot == 0) return 255f
        var acc = 0; val goal = (tot * q).toInt()
        for (i in 0 until 256) { acc += hist[i]; if (acc >= goal) return i.toFloat() }
        return 255f
    }

    // =====================================================================
    // 🧽 QUITAR A LA PERSONA · 🔄 CAMBIARLA POR LA DE OTRA FOTO
    // =====================================================================
    private fun removeSubject(src0: Bitmap, all: Boolean): Bitmap {
        val src = capTo(src0, INPAINT_SIDE)
        progress("🧠 Buscando a la persona…", true)
        val seg = segment(src, multi = true) ?: throw NeedsInfo(
            "No pude encontrar a la persona. La primera vez se necesita internet para que se descargue la IA de Google.")
        tech += "Separar personas: ML Kit (Google)"
        val hole = subjectMask(seg, all) ?: throw NeedsInfo("No encontré ninguna persona u objeto principal en la foto.")
        val out = inpaint(src, hole, seg.w, seg.h)
        if (src !== src0) src.recycle()
        summary += if (all) "🧽 Quité a las personas y rellené el espacio con IA." else "🧽 Quité a la persona y rellené el espacio con IA."
        return out
    }

    private fun replacePerson(base0: Bitmap, donor0: Bitmap): Bitmap {
        val base = capTo(base0, INPAINT_SIDE)
        val donor = capTo(donor0, INPAINT_SIDE)
        progress("🧠 Buscando a la persona en la primera foto…", true)
        val segA = segment(base, multi = true) ?: throw NeedsInfo("No pude analizar la primera foto. La primera vez se necesita internet (IA de Google).")
        val maskA = subjectMask(segA, false) ?: throw NeedsInfo("No encontré a una persona en la primera foto.")
        checkStop()
        progress("🧠 Buscando a la persona nueva en la segunda foto…", true)
        val segB = segment(donor, multi = true) ?: throw NeedsInfo("No pude analizar la segunda foto.")
        val maskB = subjectMask(segB, false) ?: throw NeedsInfo("No encontré a una persona en la segunda foto.")
        tech += "Separar personas: ML Kit (Google)"
        val boxAs = maskBox(maskA, segA.w, segA.h, 0.5f) ?: throw NeedsInfo("No encontré a una persona en la primera foto.")
        val fa = base.width.toFloat() / segA.w
        val boxA = Rect((boxAs.left * fa).toInt(), (boxAs.top * fa).toInt(), (boxAs.right * fa).toInt(), (boxAs.bottom * fa).toInt())
        // 1) Se borra a la persona vieja
        val clean = inpaint(base, maskA, segA.w, segA.h)
        checkStop()
        // 2) Se recorta a la persona nueva (con su borde suave)
        val boxBs = maskBox(maskB, segB.w, segB.h, 0.5f) ?: throw NeedsInfo("No encontré a una persona en la segunda foto.")
        val fb = donor.width.toFloat() / segB.w
        val boxB = Rect((boxBs.left * fb).toInt().coerceAtLeast(0), (boxBs.top * fb).toInt().coerceAtLeast(0),
            (boxBs.right * fb).toInt().coerceAtMost(donor.width), (boxBs.bottom * fb).toInt().coerceAtMost(donor.height))
        val cw = boxB.width(); val ch = boxB.height()
        if (cw < 4 || ch < 4) throw NeedsInfo("La persona de la segunda foto es demasiado pequeña.")
        val alphaB = upsampleRegion(FloatArray(maskB.size) { ((maskB[it] - 0.2f) / 0.6f).coerceIn(0f, 1f) }, segB.w, segB.h,
            donor.width, donor.height, boxB)
        val cut = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val row = IntArray(cw)
        // Brillo parecido al de la persona vieja
        val gain = brightnessGain(base, boxA, donor, boxB)
        for (y in 0 until ch) {
            donor.getPixels(row, 0, cw, boxB.left, boxB.top + y, cw, 1)
            for (x in 0 until cw) {
                val a = alphaB[y * cw + x]
                val p = row[x]
                val r = (Color.red(p) * gain).toInt().coerceIn(0, 255); val g = (Color.green(p) * gain).toInt().coerceIn(0, 255); val b = (Color.blue(p) * gain).toInt().coerceIn(0, 255)
                row[x] = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
            }
            cut.setPixels(row, 0, cw, 0, y, cw, 1)
        }
        // 3) Tamaño y lugar: misma altura que la persona vieja, parada en el mismo lugar
        val aTouchesBottom = boxA.bottom >= base.height - 3
        val bTouchesBottom = boxB.bottom >= donor.height - 3
        var scale = boxA.height().toFloat() / ch
        if (aTouchesBottom && !bTouchesBottom) scale = boxA.width().toFloat() / cw   // la vieja estaba cortada abajo
        scale = min(scale, base.width * 0.98f / cw)
        val nw = max(4, (cw * scale).roundToInt()); val nh = max(4, (ch * scale).roundToInt())
        val placed = Bitmap.createScaledBitmap(cut, nw, nh, true)
        if (placed !== cut) cut.recycle()
        val left = (boxA.exactCenterX() - nw / 2f).roundToInt().coerceIn(-nw / 4, max(-nw / 4, base.width - nw * 3 / 4))
        val top = if (aTouchesBottom && !bTouchesBottom) boxA.top else boxA.bottom - nh
        Canvas(clean).drawBitmap(placed, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        placed.recycle()
        if (base !== base0) base.recycle()
        if (donor !== donor0) donor.recycle()
        summary += "🔄 Cambié a la persona de la primera foto por la de la segunda."
        summary += "ℹ️ Es un fotomontaje hecho en el teléfono: si las fotos tienen luz o ángulo muy distintos, se va a notar."
        return clean
    }

    private fun brightnessGain(a: Bitmap, ra: Rect, b: Bitmap, rb: Rect): Float {
        fun lum(bm: Bitmap, r: Rect): Float {
            var s = 0.0; var n = 0
            val stepX = max(1, r.width() / 40); val stepY = max(1, r.height() / 40)
            var y = r.top.coerceAtLeast(0)
            while (y < min(r.bottom, bm.height)) {
                var x = r.left.coerceAtLeast(0)
                while (x < min(r.right, bm.width)) { val p = bm.getPixel(x, y); s += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p); n++; x += stepX }
                y += stepY
            }
            return if (n == 0) 128f else (s / n).toFloat()
        }
        val la = lum(a, ra); val lb = lum(b, rb)
        return sqrt((la / max(1f, lb)).toDouble()).toFloat().coerceIn(0.85f, 1.18f)
    }

    /** Máscara (a la resolución chica de la IA) de la persona más grande, o de todas. */
    private fun subjectMask(seg: Segmentation, all: Boolean): FloatArray? {
        val subs = seg.subjects.filter { it.area > seg.w * seg.h * 0.002f }
        val chosen = if (all) subs else listOfNotNull(subs.maxByOrNull { it.area })
        val m = FloatArray(seg.w * seg.h)
        if (chosen.isEmpty()) {
            val fg = seg.fg
            var any = false
            for (i in m.indices) { m[i] = fg[i]; if (fg[i] > 0.5f) any = true }
            return if (any) m else null
        }
        for (s in chosen) for (yy in 0 until s.h) for (xx in 0 until s.w) {
            val gx = s.x + xx; val gy = s.y + yy
            if (gx < 0 || gy < 0 || gx >= seg.w || gy >= seg.h) continue
            val v = s.mask[yy * s.w + xx]
            val i = gy * seg.w + gx
            if (v > m[i]) m[i] = v
        }
        return m
    }

    // ---------------------------------------------------------------- relleno con IA (MI-GAN)
    /** Borra lo marcado en la máscara (resolución chica sw×sh) y lo rellena con la IA MI-GAN. */
    private fun inpaint(src: Bitmap, holeSmall: FloatArray, sw: Int, sh: Int): Bitmap {
        val w = src.width; val h = src.height
        // Hueco un poco más grande que la persona (para que no quede su contorno)
        val hard = BooleanArray(holeSmall.size) { holeSmall[it] > 0.35f }
        val grow = max(2, (max(sw, sh) * 0.015f).roundToInt())
        val grown = dilate(hard, sw, sh, grow)
        val soft = gaussian(FloatArray(grown.size) { if (grown[it]) 1f else 0f }, sw, sh, 1.2f)
        val box = maskBox(soft, sw, sh, 0.02f) ?: return src.copy(Bitmap.Config.ARGB_8888, true)
        val f = w.toFloat() / sw
        val bx = Rect((box.left * f).toInt(), (box.top * f).toInt(), (box.right * f).toInt(), (box.bottom * f).toInt())
        // Zona que ve la IA: cuadrada, con contexto alrededor del hueco
        val side = min(max(w, h), (max(bx.width(), bx.height()) * 1.6f).roundToInt().coerceAtLeast(64))
        val cx = bx.exactCenterX(); val cy = bx.exactCenterY()
        val rw = min(side, w); val rh = min(side, h)
        val rl = (cx - rw / 2f).roundToInt().coerceIn(0, w - rw); val rt = (cy - rh / 2f).roundToInt().coerceIn(0, h - rh)
        val region = Rect(rl, rt, rl + rw, rt + rh)
        // Máscara del hueco solo dentro de esa zona (a tamaño real)
        val holeRegion = upsampleRegion(soft, sw, sh, w, h, region)
        val filled = aiFill(src, region, holeRegion)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(rw); val frow = IntArray(rw)
        val fs = if (filled.width != rw || filled.height != rh) Bitmap.createScaledBitmap(filled, rw, rh, true) else filled
        for (y in 0 until rh) {
            out.getPixels(row, 0, rw, rl, rt + y, rw, 1)
            fs.getPixels(frow, 0, rw, 0, y, rw, 1)
            for (x in 0 until rw) {
                val a = holeRegion[y * rw + x]
                if (a > 0.001f) row[x] = mix(frow[x], row[x], a)
            }
            out.setPixels(row, 0, rw, rl, rt + y, rw, 1)
        }
        if (fs !== filled) fs.recycle()
        filled.recycle()
        return out
    }

    /** MI-GAN: ve 512×512 (la foto con el hueco) y devuelve la zona rellena. Usa el motor elegido. */
    private fun aiFill(src: Bitmap, region: Rect, holeRegion: FloatArray): Bitmap {
        val model = (try {
            models.openTflite(ImageAiModels.INPAINT, engine, floatModel = true)
        } catch (e: ImageEngineError) {
            throw engineProblem("la IA de relleno", e)
        }) ?: throw NeedsInfo("No pude descargar la IA de relleno (≈${ImageAiModels.INPAINT.sizeMb} MB). La primera vez necesito internet; revisa tu conexión y vuelve a pedírmelo.")
        try {
            tech += "Relleno con IA ${model.label} en ${engine.label}"
            progress("🪄 Rellenando el espacio con IA en ${engine.label}…", true)
            val itp = model.interpreter
            val inT = itp.getInputTensor(0); val outT = itp.getOutputTensor(0)
            val inIO = TensorIO(inT); val outIO = TensorIO(outT)
            val shape = inT.shape()                 // [1,4,512,512] o [1,512,512,4]
            val nchw = shape[1] == 4
            val S = if (nchw) shape[2] else shape[1]
            val crop = Bitmap.createBitmap(src, region.left, region.top, region.width(), region.height())
            val small = Bitmap.createScaledBitmap(crop, S, S, true)
            if (crop !== src && crop !== small) crop.recycle()
            val px = IntArray(S * S); small.getPixels(px, 0, S, 0, 0, S, S); small.recycle()
            // Máscara a 512: 1 = se mantiene · 0 = se borra
            val rw = region.width(); val rh = region.height()
            val keep = FloatArray(S * S)
            for (y in 0 until S) for (x in 0 until S) {
                val fx = min(rw - 1, ((x + 0.5f) * rw / S).toInt())
                val fy = min(rh - 1, ((y + 0.5f) * rh / S).toInt())
                keep[y * S + x] = if (holeRegion[fy * rw + fx] > 0.08f) 0f else 1f
            }
            val inBuf = ByteBuffer.allocateDirect(inIO.bytesPer * 4 * S * S).order(ByteOrder.nativeOrder())
            fun rgb(p: Int, c: Int) = (((p shr (16 - 8 * c)) and 0xFF) / 127.5f) - 1f
            if (nchw) {
                for (i in 0 until S * S) inIO.put(inBuf, keep[i] - 0.5f)
                for (c in 0 until 3) for (i in 0 until S * S) inIO.put(inBuf, rgb(px[i], c) * keep[i])
            } else {
                for (i in 0 until S * S) { inIO.put(inBuf, keep[i] - 0.5f); for (c in 0 until 3) inIO.put(inBuf, rgb(px[i], c) * keep[i]) }
            }
            val outShape = outT.shape()
            val outNchw = outShape[1] == 3
            val outBuf = ByteBuffer.allocateDirect(outIO.bytesPer * 3 * S * S).order(ByteOrder.nativeOrder())
            inBuf.rewind(); outBuf.rewind()
            checkStop()
            try {
                itp.run(inBuf, outBuf)
            } catch (t: Throwable) {
                throw engineProblem("la IA de relleno", ImageEngineError(t.message?.take(140) ?: t.javaClass.simpleName))
            }
            outBuf.rewind()
            val res = IntArray(S * S) { 0xFF000000.toInt() }
            fun to8(v: Float) = ((v + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)
            if (outNchw) {
                for (c in 0 until 3) { val sh = 16 - 8 * c; for (i in 0 until S * S) res[i] = res[i] or (to8(outIO.get(outBuf)) shl sh) }
            } else {
                for (i in 0 until S * S) { val r = to8(outIO.get(outBuf)); val g = to8(outIO.get(outBuf)); val b = to8(outIO.get(outBuf)); res[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
            }
            val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
            bmp.setPixels(res, 0, S, 0, 0, S, S)
            val back = Bitmap.createScaledBitmap(bmp, region.width(), region.height(), true)
            if (back !== bmp) bmp.recycle()
            return back
        } finally { model.close() }
    }

    // =====================================================================
    // 🪄 VERSIÓN NUEVA DE LA MISMA IMAGEN (SDXS con stable-diffusion.cpp, en la CPU)
    // =====================================================================
    private fun variation(src: Bitmap, s: Step): Bitmap {
        if (!ImageGenNative.available) throw NeedsInfo(
            "La IA para crear versiones nuevas no vino incluida en esta compilación de la app." +
                ImageGenNative.loadError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(120)})" }.orEmpty() +
                "\n\nLo demás (recortar, agrandar, fondo, personas) sí funciona.")
        val modelFile = models.ensure(ImageAiModels.IMAGE_GEN) ?: run {
            checkStop()
            throw NeedsInfo("No pude descargar la IA de imágenes (≈${ImageAiModels.IMAGE_GEN.sizeMb} MB). Revisa tu internet y el espacio libre, y vuelve a pedírmelo: la descarga sigue donde quedó." +
                models.lastError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(100)})" }.orEmpty())
        }
        checkStop()
        ImageGenNative.init(context.filesDir.absolutePath)
        progress("🧠 Cargando la IA de imágenes…", true)
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        if (!ImageGenNative.nativeLoad(modelFile.absolutePath, threads)) {
            val why = ImageGenNative.lastError()
            throw NeedsInfo("No pude abrir la IA de imágenes." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
        }
        checkStop()
        val version = runCatching { ImageGenNative.nativeModelVersion() }.getOrDefault("")
        val oneStep = version.contains("SDXS", ignoreCase = true) || version.contains("turbo", ignoreCase = true)
        // Tamaño de trabajo: ≈512×512 con la misma forma de la foto (múltiplos de 64)
        val (gw, gh) = genSize(src.width, src.height)
        val flat = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)    // lo transparente queda blanco
            drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, gw, gh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(gw * gh); flat.getPixels(px, 0, gw, 0, 0, gw, gh); flat.recycle()
        val rgb = ByteArray(gw * gh * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        val prompt = buildPrompt(s)
        val negative = "blurry, low quality, deformed, ugly, watermark, text, nsfw, nude"
        val seed = (System.nanoTime() and 0x7FFFFFFF)
        // ⏹️ Vigía: si tocas detener, la IA para al tiro; mientras tanto se muestra el avance
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            val started = System.currentTimeMillis()
            while (running.get()) {
                if (isCancelled()) { ImageGenNative.cancel(); break }
                val secs = (System.currentTimeMillis() - started) / 1000
                progress("🪄 Creando la versión nueva en la CPU… $secs", true)
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val out: ByteArray? = try {
            if (oneStep) ImageGenNative.nativeImg2Img(rgb, gw, gh, prompt, negative, noiseLevelFor(s.strength), 1, 1f, seed, true)
            else ImageGenNative.nativeImg2Img(rgb, gw, gh, prompt, negative, s.strength, 20, 7f, seed, false)
        } finally {
            running.set(false)
            watcher.interrupt()
            // Se libera la memoria de la IA de imágenes (así el modelo del chat tiene toda la RAM)
            ImageGenNative.free()
        }
        checkStop()
        if (out == null || out.size < gw * gh * 3) {
            val why = ImageGenNative.lastError()
            throw NeedsInfo("La IA no pudo crear la versión nueva." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
        }
        val outPx = IntArray(gw * gh) { i ->
            (0xFF shl 24) or ((out[i * 3].toInt() and 0xFF) shl 16) or ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or (out[i * 3 + 2].toInt() and 0xFF)
        }
        val gen = Bitmap.createBitmap(outPx, gw, gh, Bitmap.Config.ARGB_8888)
        // Misma forma exacta que la foto original (el tamaño de trabajo se redondeó a múltiplos de 64)
        val long = max(gw, gh)
        val f = long.toFloat() / max(src.width, src.height)
        val fw = (src.width * f).roundToInt().coerceAtLeast(16); val fh = (src.height * f).roundToInt().coerceAtLeast(16)
        val result = if (fw == gw && fh == gh) gen else Bitmap.createScaledBitmap(gen, fw, fh, true).also { if (it !== gen) gen.recycle() }
        tech += "IA de imágenes ${ImageAiModels.IMAGE_GEN.label} en la CPU" + (if (version.isNotBlank()) " ($version)" else "")
        summary += "🪄 Creé una versión nueva de la imagen" + (if (s.what.isNotBlank()) " (${s.what})" else "") + " · ${result.width}×${result.height}."
        summary += "ℹ️ Es una IA pequeña que corre en el teléfono: si no te gusta, pídeme «otra» y sale distinta. Para más resolución, dime «súbele la resolución»."
        return result
    }

    private fun buildPrompt(s: Step): String {
        val parts = mutableListOf<String>()
        if (s.prompt.isNotBlank()) parts += s.prompt
        parts += "high quality, detailed, beautiful"
        parts += "family friendly, fully clothed"
        return parts.joinToString(", ")
    }

    /** ≈512×512 con la forma de la foto, lados múltiplos de 64 entre 320 y 768. */
    private fun genSize(w: Int, h: Int): Pair<Int, Int> {
        val area = 512.0 * 512.0
        val ratio = w.toDouble() / h
        var gw = sqrt(area * ratio); var gh = gw / ratio
        gw = (gw / 64.0).roundToInt() * 64.0; gh = (gh / 64.0).roundToInt() * 64.0
        return gw.toInt().coerceIn(320, 768) to gh.toInt().coerceIn(320, 768)
    }

    /**
     * Cuánto cambia (0..1, como el «strength» clásico de img2img) → nivel de ruido para modelos de un paso.
     * Se calcula con el mismo calendario de ruido de Stable Diffusion (betas 0.00085 → 0.012).
     */
    private fun noiseLevelFor(strength: Float): Float {
        val t = (strength.coerceIn(0.05f, 0.98f) * 999).toInt()
        val b0 = sqrt(0.00085); val b1 = sqrt(0.012)
        var alphaBar = 1.0
        for (i in 0..t) { val beta = (b0 + (b1 - b0) * i / 999.0).pow(2); alphaBar *= (1 - beta) }
        val sigma = sqrt((1 - alphaBar) / alphaBar)
        return (sigma / (1 + sigma)).toFloat().coerceIn(0.05f, 0.98f)
    }

    // =====================================================================
    // 🧠 SEPARAR PERSONAS Y OBJETOS (ML Kit Subject Segmentation de Google)
    // =====================================================================
    class Subject(val x: Int, val y: Int, val w: Int, val h: Int, val mask: FloatArray) {
        val area: Float = mask.count { it > 0.5f }.toFloat()
    }
    class Segmentation(val w: Int, val h: Int, val fg: FloatArray, val subjects: List<Subject>)

    /** La IA trabaja con la foto a ≤1280 px. Devuelve null si no se pudo (sin internet la primera vez, etc.). */
    private fun segment(src: Bitmap, multi: Boolean, maxWaitMs: Long = 120_000L): Segmentation? {
        val long = max(src.width, src.height)
        val f = min(1f, 1280f / long)
        val sw = max(8, (src.width * f).roundToInt()); val sh = max(8, (src.height * f).roundToInt())
        val small = if (f < 1f) Bitmap.createScaledBitmap(src, sw, sh, true) else src
        val opts = com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .apply {
                if (multi) enableMultipleSubjects(
                    com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.SubjectResultOptions.Builder()
                        .enableConfidenceMask().build())
            }
            .build()
        val segmenter = com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(opts)
        try {
            val img = com.google.mlkit.vision.common.InputImage.fromBitmap(small, 0)
            val start = System.currentTimeMillis()
            var told = false
            while (true) {
                checkStop()
                try {
                    val res = com.google.android.gms.tasks.Tasks.await(segmenter.process(img), 60, TimeUnit.SECONDS)
                    val fgBuf = res.foregroundConfidenceMask ?: return null
                    val fg = FloatArray(sw * sh); fgBuf.rewind(); fgBuf.get(fg, 0, min(fg.size, fgBuf.remaining()))
                    val subs = ArrayList<Subject>()
                    if (multi) for (s in res.subjects) {
                        val mb = s.confidenceMask ?: continue
                        val arr = FloatArray(s.width * s.height); mb.rewind(); mb.get(arr, 0, min(arr.size, mb.remaining()))
                        subs += Subject(s.startX, s.startY, s.width, s.height, arr)
                    }
                    return Segmentation(sw, sh, fg, subs)
                } catch (c: Cancelled) {
                    throw c
                } catch (t: Throwable) {
                    // La primera vez la IA de Google se está descargando: se espera y se reintenta
                    if (System.currentTimeMillis() - start > maxWaitMs) return null
                    if (!told) { progress("📥 Descargando la IA de Google para separar personas y objetos (solo la primera vez)…", true); told = true }
                    Thread.sleep(2500)
                }
            }
        } finally {
            runCatching { segmenter.close() }
            if (small !== src) small.recycle()
        }
    }

    // =====================================================================
    // 🔧 Herramientas de imagen
    // =====================================================================
    /** Copia achicada para trabajar con menos memoria (o la misma si ya es chica). */
    private fun capTo(b: Bitmap, side: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= side) return b
        val f = side.toFloat() / long
        return scaleSmart(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1))
    }

    private fun maskBox(m: FloatArray, w: Int, h: Int, thr: Float): Rect? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x] > thr) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** Agranda una máscara chica al tamaño de la foto (bilineal). */
    private fun upsample(m: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray =
        upsampleRegion(m, sw, sh, w, h, Rect(0, 0, w, h))

    /** Igual que upsample, pero solo para un recuadro de la foto grande (ahorra memoria). */
    private fun upsampleRegion(m: FloatArray, sw: Int, sh: Int, w: Int, h: Int, r: Rect): FloatArray {
        val rw = r.width(); val rh = r.height()
        val out = FloatArray(rw * rh)
        for (yy in 0 until rh) {
            val y = r.top + yy
            val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            for (xx in 0 until rw) {
                val x = r.left + xx
                val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val a = m[y0 * sw + x0] * (1 - tx) + m[y0 * sw + x1] * tx
                val b = m[y1 * sw + x0] * (1 - tx) + m[y1 * sw + x1] * tx
                out[yy * rw + xx] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    private fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = max(1, ceil(sigma * 3).toInt())
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) { val o = y * w; for (x in 0 until w) { var s = 0f; for (j in -r..r) s += src[o + (x + j).coerceIn(0, w - 1)] * k[j + r]; tmp[o + x] = s } }
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (j in -r..r) s += tmp[(y + j).coerceIn(0, h - 1) * w + x] * k[j + r]; out[y * w + x] = s }
        return out
    }

    /** Quita las manchas (grupos de pixeles "true") más chicas que minSize. */
    private fun removeSmall(m: BooleanArray, w: Int, h: Int, minSize: Int, keepBorderTouching: Boolean): BooleanArray {
        val out = m.copyOf()
        val seen = BooleanArray(m.size)
        val queue = IntArray(m.size)
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var qh = 0; var qt = 0
            queue[qt++] = start; seen[start] = true
            var touches = false
            while (qh < qt) {
                val i = queue[qh++]; val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                if (x > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[qt++] = i - 1 }
                if (x < w - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[qt++] = i + 1 }
                if (y > 0 && m[i - w] && !seen[i - w]) { seen[i - w] = true; queue[qt++] = i - w }
                if (y < h - 1 && m[i + w] && !seen[i + w]) { seen[i + w] = true; queue[qt++] = i + w }
            }
            if (qt < minSize && !(keepBorderTouching && touches && qt >= minSize / 4)) for (q in 0 until qt) out[queue[q]] = false
        }
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val d = distanceToTrue(m, w, h)
        return BooleanArray(m.size) { d[it] <= r }
    }

    /** Distancia de cada pixel "true" al pixel "false" más cercano (0 para los "false"). */
    private fun distanceToFalse(m: BooleanArray, w: Int, h: Int): FloatArray {
        val inv = BooleanArray(m.size) { !m[it] }
        return distanceToTrue(inv, w, h)
    }

    /** Distancia de cada pixel al pixel "true" más cercano. */
    private fun distanceToTrue(m: BooleanArray, w: Int, h: Int): FloatArray {
        val idx = nearestIndex(m, w, h)
        return FloatArray(m.size) {
            val j = idx[it]
            if (j < 0) Float.MAX_VALUE else { val dx = (it % w - j % w).toFloat(); val dy = (it / w - j / w).toFloat(); sqrt(dx * dx + dy * dy) }
        }
    }

    /** Para cada pixel, el índice del pixel "true" más cercano (transformada de distancia exacta). -1 si no hay ninguno. */
    private fun nearestIndex(m: BooleanArray, w: Int, h: Int): IntArray {
        val INF = 1e20f
        // Pasada 1: por columnas, la fila "true" más cercana
        val nearRow = IntArray(w * h) { -1 }
        for (x in 0 until w) {
            var last = -1
            for (y in 0 until h) { if (m[y * w + x]) last = y; nearRow[y * w + x] = last }
            last = -1
            for (y in h - 1 downTo 0) {
                if (m[y * w + x]) last = y
                val cur = nearRow[y * w + x]
                if (last >= 0 && (cur < 0 || abs(last - y) < abs(cur - y))) nearRow[y * w + x] = last
            }
        }
        // Pasada 2: por filas, envolvente de parábolas (Felzenszwalb)
        val out = IntArray(w * h) { -1 }
        val g = FloatArray(w); val v = IntArray(w); val z = FloatArray(w + 1)
        for (y in 0 until h) {
            for (x in 0 until w) { val r = nearRow[y * w + x]; g[x] = if (r < 0) INF else ((r - y) * (r - y)).toFloat() }
            var k = -1
            for (q in 0 until w) {
                if (g[q] >= INF) continue
                if (k < 0) { k = 0; v[0] = q; z[0] = -INF; z[1] = INF; continue }
                var s: Float
                while (true) {
                    val p = v[k]
                    s = ((g[q] + q.toFloat() * q) - (g[p] + p.toFloat() * p)) / (2f * (q - p))
                    if (s <= z[k] && k > 0) k-- else break
                }
                if (s <= z[k]) { v[k] = q; z[k + 1] = INF; continue }   // k == 0 y la nueva parábola gana en todo
                k++; v[k] = q; z[k] = s; z[k + 1] = INF
            }
            if (k < 0) continue
            var j = 0
            for (q in 0 until w) {
                while (z[j + 1] < q) j++
                val px = v[j]
                out[y * w + q] = nearRow[y * w + px] * w + px
            }
        }
        return out
    }

    companion object {
        const val MAX_SIDE = 4096
        /** La máscara del fondo se calcula a este tamaño (el resultado sale del tamaño original). */
        const val MASK_SIDE = 2048
        /** Quitar o cambiar personas trabaja hasta este tamaño. */
        const val INPAINT_SIDE = 3072

        /** Abre una foto derecha (según la cámara) y sin pasar de maxSide px (fotos de 50 o 200 MP no caben en memoria). */
        fun loadUpright(file: File, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            var bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return@runCatching null
            val long = max(bmp.width, bmp.height)
            if (long > maxSide) {
                val f = maxSide.toFloat() / long
                val s = Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt(), (bmp.height * f).roundToInt(), true)
                if (s !== bmp) bmp.recycle()
                bmp = s
            }
            val rot = runCatching {
                when (android.media.ExifInterface(file.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            }.getOrDefault(0f)
            if (rot == 0f) bmp else {
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                if (r !== bmp) bmp.recycle()
                r
            }
        }.getOrNull()
    }
}
