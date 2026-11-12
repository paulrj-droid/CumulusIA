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
 *  🖍️ Foto MARCADA (círculo, flecha o raya): borrar, editar, agregar o reemplazar SOLO lo marcado
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
    private val isCancelled: () -> Boolean,
    /**
     * 👁️ Para elegir a UNA persona u objeto cuando dices «el hombre», «la mujer» o «la bicicleta»: recibe la foto con
     * cada uno numerado y cuántos hay, y devuelve el número que pediste (lo mira la IA del chat si tiene visión).
     * null = no hay IA con visión (se elige por lado, tamaño y color de la ropa).
     */
    private val pickSubject: ((Bitmap, Int) -> Int?)? = null,
    /**
     * 🧠↔️🎨 Se llama justo antes de cargar la IA de imágenes de MEJOR CALIDAD (≈3 GB de RAM): la app pone en pausa
     * el modelo del chat (GGUF o LiteRT) para que no falte memoria. Al terminar, la app lo vuelve a cargar.
     */
    private val beforeBigImageAi: (() -> Unit)? = null,
    /**
     * 👁️ Una pregunta corta a la IA del chat sobre una imagen (si tiene visión): recibe la imagen y la pregunta y
     * devuelve la respuesta. Se usa para saber si la persona o personaje es hombre o mujer. null = no hay visión.
     */
    private val askVision: ((Bitmap, String) -> String?)? = null
) {
    class Cancelled : Exception("Edición detenida")
    class NeedsInfo(message: String) : Exception(message)
    /** ⚡ La NPU no pudo crear la imagen (la app sigue sola en la CPU). */
    class NpuFailed(message: String) : Exception(message)

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
                Kind.REMOVE_PERSON -> removeSubject(cur, step.allSubjects, step.target)
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
    // 🖍️ FOTO MARCADA: se busca la marca (círculo, flecha, raya o tachado) y se hace el pedido SOLO ahí
    //    · Borrar: lo marcado se borra y la IA MI-GAN rellena el espacio
    //    · Editar: aclarar, oscurecer, blanco y negro, difuminar, pixelar o cambiar de color lo marcado
    //    · Agregar: la IA de imágenes dibuja lo pedido en el lugar marcado
    //    · Reemplazar: se borra lo marcado y la IA dibuja lo nuevo en su lugar
    //    La marca siempre se quita de la foto (su color no queda ni se toma como pedido).
    // =====================================================================
    /** @param markColors los colores del lápiz con que se marcó (si se saben); vacío = se buscan solos en la foto. */
    fun processMarked(base: Bitmap, edit: ImageEditCommand.MarkEdit, enPrompt: String, markColors: List<Int> = emptyList()): Outcome {
        val src = capTo(base, INPAINT_SIDE)
        val w = src.width; val h = src.height
        progress("🖍️ Buscando la marca en la foto…", true)
        // Se trabaja la marca a ≤768 px (rápido y con poca memoria)
        val f = min(1f, 768f / max(w, h))
        val sw = max(8, (w * f).roundToInt()); val sh = max(8, (h * f).roundToInt())
        val small = if (f < 1f) Bitmap.createScaledBitmap(src, sw, sh, true) else src
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        val stroke = markStrokes(px, sw, sh, markColors) ?: throw NeedsInfo(
            "🖍️ No encontré la marca en la foto. Márcala con el lápiz de Cumulus: toca la foto, luego el lápiz ✏️ de arriba a la derecha, " +
                "elige abajo un color que se note sobre lo que vas a marcar, encierra lo que quieres con un círculo (o pon una flecha o una raya) y toca ✓.\n\n" +
                "Si ya la marcaste con el mismo color de lo que quieres cambiar (por ejemplo, rojo sobre una polera roja), la marca se confunde: márcala con otro color.")
        checkStop()
        val side = max(sw, sh)
        val gap = max(2, (side * 0.012f).roundToInt())
        val strokeWide = dilate(stroke, sw, sh, gap)
        // ⭕ ¿Encierra algo? (lo que queda adentro del círculo, sin poder salir al borde de la foto)
        val inside = insideOf(strokeWide, sw, sh)
        val insideCount = inside.count { it }
        val kind: String
        val target: BooleanArray = if (insideCount > sw * sh * 0.002f) {
            kind = "círculo"
            dilate(inside, sw, sh, gap)
        } else {
            val tip = arrowTip(stroke, sw, sh)
            if (tip != null) {
                kind = "flecha"
                val (cx, cy, r) = tip
                BooleanArray(sw * sh) { val x = it % sw; val y = it / sw; (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }
            } else {
                kind = "raya"
                dilate(stroke, sw, sh, max(3, (side * 0.03f).roundToInt()))
            }
        }
        tech += "Marca: $kind"
        // 1) Se borra la marca (y, si pediste borrar o reemplazar, también lo marcado) y la IA MI-GAN rellena
        val erase = edit.action == ImageEditCommand.MarkAction.ERASE || edit.action == ImageEditCommand.MarkAction.REPLACE
        // 🎯 La persona u objeto marcado (con círculo, flecha o raya, del color que sea): se trabaja SOLO sobre su silueta
        //    (no todo lo que quedó dentro del círculo: la persona de al lado y el fondo quedan intactos).
        //    1) La IA de Google la separa (personas, bicicletas, perros…) · 2) si no, se sigue su color desde la marca
        //    (un globo, un letrero…) · 3) si tampoco, se usa la zona marcada como siempre. (Agregar algo no usa silueta.)
        val subjectHole: BooleanArray? = if (edit.action == ImageEditCommand.MarkAction.ADD) null else
            runCatching { markedSubjects(src, stroke, target, kind, sw, sh, gap) }.getOrNull()
                ?: runCatching { colorRegion(px, stroke, strokeWide, target, kind, sw, sh, gap) }.getOrNull()
                    ?.takeIf { kind != "círculo" || edit.action != ImageEditCommand.MarkAction.ERASE }   // (encerrado y borrar: todo lo de adentro, como siempre)
        val eraseArea = subjectHole ?: target
        val hole = FloatArray(sw * sh) { if (strokeWide[it] || (erase && eraseArea[it])) 1f else 0f }
        progress(if (erase) "🧽 Borrando lo marcado…" else "🧽 Quitando la marca de la foto…", true)
        var cur = inpaint(src, hole, sw, sh)
        if (src !== base) src.recycle()
        checkStop()
        val soft = gaussian(FloatArray(sw * sh) { if (eraseArea[it]) 1f else 0f }, sw, sh, max(1.2f, gap * 0.8f))
        when (edit.action) {
            ImageEditCommand.MarkAction.ERASE -> summary += if (subjectHole != null)
                "🧽 Quité completo lo que marcaste con el $kind (siguiendo su silueta, sin tocar lo de al lado) y rellené el espacio con IA."
                else "🧽 Borré lo que marcaste con el $kind y rellené el espacio con IA."
            ImageEditCommand.MarkAction.EDIT -> {
                if (edit.filter == ImageEditCommand.MarkFilter.NONE) {
                    if (enPrompt.isBlank()) throw NeedsInfo("🖍️ Encontré lo que marcaste, pero no sé qué cambiarle. Dime, por ejemplo: " +
                        "«ponlo en blanco y negro», «aclaralo», «oscurécelo», «difumínalo», «píntalo de azul», «bórralo» o «cámbialo por un árbol».")
                    cur = generateIn(cur, soft, sw, sh, enPrompt, 0.45f).also { if (it !== cur) cur.recycle() }
                    summary += "✏️ Cambié lo que marcaste con el $kind."
                } else {
                    cur = filterIn(cur, soft, sw, sh, edit)
                    summary += "✏️ " + when (edit.filter) {
                        ImageEditCommand.MarkFilter.BRIGHTER -> "Aclaré"
                        ImageEditCommand.MarkFilter.DARKER -> "Oscurecí"
                        ImageEditCommand.MarkFilter.GRAY -> "Puse en blanco y negro"
                        ImageEditCommand.MarkFilter.BLUR -> "Difuminé"
                        ImageEditCommand.MarkFilter.PIXELATE -> "Pixelé"
                        ImageEditCommand.MarkFilter.COLOR -> "Pinté de ${edit.colorName}"
                        else -> "Cambié"
                    } + " lo que marcaste con el $kind" + (if (subjectHole != null) ", siguiendo su silueta" else "") + " (el resto de la foto quedó igual)."
                }
            }
            ImageEditCommand.MarkAction.ADD, ImageEditCommand.MarkAction.REPLACE -> {
                val add = edit.action == ImageEditCommand.MarkAction.ADD
                val out = generateIn(cur, soft, sw, sh, enPrompt.ifBlank { edit.what }, if (add) 0.72f else 0.8f)
                if (out !== cur) cur.recycle()
                cur = out
                summary += (if (add) "➕ Agregué ${edit.what} donde marcaste con el $kind." else "🔄 Cambié lo que marcaste con el $kind por ${edit.what}.")
                summary += "ℹ️ Lo dibuja una IA pequeña que corre en el teléfono: si no queda bien, pídemelo de nuevo y sale distinto."
            }
        }
        return Outcome(cur, false, summary.toList(), tech.toList())
    }

    /** ¿Es un pixel de marca roja? (rojo fuerte: el lápiz rojo de Cumulus y la mayoría de las apps) */
    private fun isMarkColor(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return r >= 150 && r - max(g, b) >= 75 && g <= 120 && b <= 130
    }

    /** Tono (0..360) de un color fuerte, o -1 si es gris, blanco, negro o apagado (eso no se toma como marca sola). */
    private fun strongHue(p: Int): Float {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val c = mx - mn
        if (mx < 120 || c < 80) return -1f
        val h = when (mx) {
            r -> 60f * (((g - b).toFloat() / c) % 6f)
            g -> 60f * ((b - r).toFloat() / c + 2f)
            else -> 60f * ((r - g).toFloat() / c + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** ¿Este pixel es del color del lápiz? (parecido, aunque la foto se haya comprimido) */
    private fun nearPen(p: Int, pen: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val pr = (pen shr 16) and 0xFF; val pg = (pen shr 8) and 0xFF; val pb = pen and 0xFF
        val dr = r - pr; val dg = g - pg; val db = b - pb
        return dr * dr + dg * dg + db * db <= 85 * 85
    }

    /** Nombre del color de un lápiz o de un tono (para avisarte en el chat). */
    private fun penName(c: Int): String {
        val h = strongHue(c)
        return when {
            isMarkColor(c) && (h < 0f || h >= 340f || h < 15f) -> "rojo"
            h < 0f -> "ese color"
            h < 40f -> "naranjo"; h < 72f -> "amarillo"; h < 165f -> "verde"; h < 200f -> "celeste"
            h < 255f -> "azul"; h < 300f -> "morado"; h < 340f -> "rosado"; else -> "rojo"
        }
    }

    /** Dos colores del lápiz que se notan bien sobre ese color (para sugerirte con cuál marcar). */
    private fun contrastFor(name: String): String = when (name) {
        "rojo", "rosado", "naranjo" -> "amarillo o celeste"
        "amarillo" -> "azul o morado"
        "verde" -> "rosado o amarillo"
        "celeste", "azul" -> "amarillo o rojo"
        "morado" -> "amarillo o verde"
        else -> "amarillo o celeste"
    }

    /**
     * 🖍️ Los trazos de la marca, de CUALQUIER color fuerte (rojo, naranjo, amarillo, verde, celeste, azul, morado, rosado):
     * siempre DELGADOS, así una polera o un auto del mismo color (que son manchas gruesas) no cuentan.
     * Si se sabe con qué color(es) se marcó (el lápiz de Cumulus lo guarda), se busca justo ese; si no, se prueba
     * cada color y se queda con el que forma la marca más clara. null = no hay marca.
     * ⚠️ Si la marca toca algo GRANDE del mismo color (una polera roja marcada con rojo), se avisa que cambies el
     * color del lápiz: la marca se confunde con eso y el cambio saldría mal.
     */
    private fun markStrokes(px: IntArray, w: Int, h: Int, pens: List<Int> = emptyList()): BooleanArray? {
        if (pens.isNotEmpty()) {
            val r = thinStrokes(BooleanArray(w * h) { i -> pens.any { nearPen(px[i], it) } }, w, h)
            sameColorProblem(r, w, h, penName(pens.first()))
            r.thin?.let { return it }
        }
        val hue = FloatArray(w * h) { strongHue(px[it]) }
        // rojo (como siempre) · naranjo · amarillo · verde · celeste · azul · morado · rosado/fucsia
        val bands = listOf(Triple(15f, 40f, "naranjo"), Triple(40f, 72f, "amarillo"), Triple(72f, 165f, "verde"),
            Triple(165f, 200f, "celeste"), Triple(200f, 255f, "azul"), Triple(255f, 300f, "morado"), Triple(300f, 340f, "rosado"))
        var best: Strokes? = null; var bestName = "rojo"; var bestScore = 0f
        thinStrokes(BooleanArray(w * h) { isMarkColor(px[it]) }, w, h).let { if (it.thin != null) { best = it; bestScore = it.score * 1.25f } }
        for ((lo, hi, name) in bands) {
            val r = thinStrokes(BooleanArray(w * h) { hue[it] >= lo && hue[it] < hi }, w, h)
            if (r.thin != null && r.score > bestScore) { best = r; bestName = name; bestScore = r.score }
        }
        best?.let { sameColorProblem(it, w, h, bestName) }
        return best?.thin
    }

    /** ⚠️ La marca toca algo grande de su mismo color → se avisa (en vez de hacer un cambio que saldría mal). */
    private fun sameColorProblem(r: Strokes, w: Int, h: Int, color: String) {
        if (!r.hasBig) return
        val touches = r.thin == null || run {
            val near = dilate(r.thin, w, h, max(2, (max(w, h) * 0.006f).roundToInt()))
            near.indices.any { near[it] && r.big[it] }
        }
        if (touches) throw NeedsInfo(
            "🖍️ No puedo hacer ese cambio: marcaste con $color sobre algo que también es $color, y la marca se confunde con eso.\n\n" +
                "Toca la foto, luego el lápiz ✏️, elige abajo otro color que se note (por ejemplo ${contrastFor(color)}) y vuelve a marcar.")
    }

    /** Trazos delgados de un color (la marca), su puntaje, y las manchas grandes y gruesas de ese mismo color. */
    private class Strokes(val thin: BooleanArray?, val score: Float, val big: BooleanArray, val hasBig: Boolean)

    /** Lo DELGADO de una máscara de color (trazos de lápiz), con un puntaje: más alto = trazos más largos y claros. */
    private fun thinStrokes(red: BooleanArray, w: Int, h: Int): Strokes {
        val big = BooleanArray(w * h)
        if (red.count { it } < 12) return Strokes(null, 0f, big, false)
        val thick = distanceToFalse(red, w, h)
        val maxThick = max(3f, max(w, h) * 0.022f)
        val out = BooleanArray(w * h)
        val seen = BooleanArray(w * h); val queue = IntArray(w * h)
        val minSize = max(12, (w * h * 0.0003f).roundToInt())
        val bigSize = (w * h * 0.004f).roundToInt()   // una polera, un auto… (no un botón chico)
        val side = max(w, h).toFloat()
        var score = 0f; var total = 0; var hasBig = false
        for (start in red.indices) {
            if (!red[start] || seen[start]) continue
            var qh = 0; var qt = 0; queue[qt++] = start; seen[start] = true
            var maxD = 0f
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (qh < qt) {
                val i = queue[qh++]; val x = i % w; val y = i / w
                if (thick[i] > maxD) maxD = thick[i]
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && red[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[qt++] = i - 1 }
                if (x < w - 1 && red[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[qt++] = i + 1 }
                if (y > 0 && red[i - w] && !seen[i - w]) { seen[i - w] = true; queue[qt++] = i - w }
                if (y < h - 1 && red[i + w] && !seen[i + w]) { seen[i + w] = true; queue[qt++] = i + w }
            }
            if (qt >= minSize && maxD <= maxThick) {
                for (q in 0 until qt) out[queue[q]] = true
                total += qt
                score += qt * (max(x1 - x0, y1 - y0) + 1) / side   // (los trazos largos pesan más que los puntitos)
            } else if (qt >= bigSize && maxD > maxThick) {
                for (q in 0 until qt) big[queue[q]] = true
                hasBig = true
            }
        }
        return Strokes(if (total >= minSize) out else null, score, big, hasBig)
    }

    /** Lo que queda ENCERRADO por la marca (lo que no se puede alcanzar desde el borde de la foto sin cruzarla). */
    private fun insideOf(wall: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h); val queue = IntArray(w * h)
        var qh = 0; var qt = 0
        fun push(i: Int) { if (!wall[i] && !outside[i]) { outside[i] = true; queue[qt++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (qh < qt) {
            val i = queue[qh++]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !outside[it] && !wall[it] }
    }

    /** ➡️ Si la marca es una FLECHA: el círculo (x, y, radio) adonde apunta. null = es una raya o un tachado. */
    private fun arrowTip(stroke: BooleanArray, w: Int, h: Int): Triple<Int, Int, Int>? {
        val idx = stroke.indices.filter { stroke[it] }
        if (idx.size < 12) return null
        val mx = idx.sumOf { it % w } / idx.size; val my = idx.sumOf { it / w } / idx.size
        fun far(fx: Int, fy: Int) = idx.maxByOrNull { val dx = it % w - fx; val dy = it / w - fy; dx * dx + dy * dy }!!
        val a = far(mx, my); val ax = a % w; val ay = a / w
        val b = far(ax, ay); val bx = b % w; val by = b / w
        val len = sqrt(((ax - bx) * (ax - bx) + (ay - by) * (ay - by)).toFloat())
        if (len < max(w, h) * 0.05f) return null
        val rr = len * 0.18f
        fun near(x: Int, y: Int) = idx.count { val dx = it % w - x; val dy = it / w - y; dx * dx + dy * dy <= rr * rr }
        val na = near(ax, ay); val nb = near(bx, by)
        // La punta tiene más trazo (las dos rayitas de la cabeza)
        val (tx, ty, ox, oy) = when {
            na > nb * 1.5f -> listOf(ax, ay, bx, by)
            nb > na * 1.5f -> listOf(bx, by, ax, ay)
            else -> return null
        }
        val dx = (tx - ox) / len; val dy = (ty - oy) / len
        val r = max(len * 0.3f, max(w, h) * 0.08f)
        val cx = (tx + dx * r * 0.9f).roundToInt().coerceIn(0, w - 1); val cy = (ty + dy * r * 0.9f).roundToInt().coerceIn(0, h - 1)
        return Triple(cx, cy, r.roundToInt())
    }

    /** El recuadro (a tamaño real) de lo marcado, con un poco de margen. */
    private fun regionOf(soft: FloatArray, sw: Int, sh: Int, w: Int, h: Int, padF: Float): Rect? {
        val box = maskBox(soft, sw, sh, 0.02f) ?: return null
        val f = w.toFloat() / sw
        val pad = (max(box.width(), box.height()) * f * padF).roundToInt()
        return Rect(((box.left * f).toInt() - pad).coerceAtLeast(0), ((box.top * f).toInt() - pad).coerceAtLeast(0),
            ((box.right * f).toInt() + pad).coerceAtMost(w), ((box.bottom * f).toInt() + pad).coerceAtMost(h))
    }

    /** 🎚️ Aclarar, oscurecer, blanco y negro, difuminar, pixelar o pintar SOLO lo marcado (con borde suave). */
    private fun filterIn(src: Bitmap, soft: FloatArray, sw: Int, sh: Int, edit: ImageEditCommand.MarkEdit): Bitmap {
        val w = src.width; val h = src.height
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val r = regionOf(soft, sw, sh, w, h, 0f) ?: return out
        if (r.width() < 2 || r.height() < 2) return out
        val alpha = upsampleRegion(soft, sw, sh, w, h, r)
        val rw = r.width(); val rh = r.height()
        // Difuminar / pixelar: se achica la zona y se vuelve a agrandar
        val blurred: IntArray? = when (edit.filter) {
            ImageEditCommand.MarkFilter.BLUR, ImageEditCommand.MarkFilter.PIXELATE -> {
                val crop = Bitmap.createBitmap(out, r.left, r.top, rw, rh)
                val k = if (edit.filter == ImageEditCommand.MarkFilter.BLUR) 14 else max(6, min(rw, rh) / 14)
                val tiny = Bitmap.createScaledBitmap(crop, max(1, rw / k), max(1, rh / k), true)
                val back = Bitmap.createScaledBitmap(tiny, rw, rh, edit.filter == ImageEditCommand.MarkFilter.BLUR)
                val arr = IntArray(rw * rh); back.getPixels(arr, 0, rw, 0, 0, rw, rh)
                if (crop !== out) crop.recycle(); tiny.recycle(); if (back !== tiny) back.recycle()
                arr
            }
            else -> null
        }
        val tr = (edit.color shr 16) and 0xFF; val tg = (edit.color shr 8) and 0xFF; val tb = edit.color and 0xFF
        val row = IntArray(rw)
        for (y in 0 until rh) {
            out.getPixels(row, 0, rw, r.left, r.top + y, rw, 1)
            for (x in 0 until rw) {
                val a = alpha[y * rw + x]
                if (a <= 0.003f) continue
                val p = row[x]
                val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
                val lum = 0.299f * pr + 0.587f * pg + 0.114f * pb
                val q: Int = when (edit.filter) {
                    ImageEditCommand.MarkFilter.BRIGHTER -> Color.rgb((pr * 1.3f + 18).toInt().coerceIn(0, 255), (pg * 1.3f + 18).toInt().coerceIn(0, 255), (pb * 1.3f + 18).toInt().coerceIn(0, 255))
                    ImageEditCommand.MarkFilter.DARKER -> Color.rgb((pr * 0.62f).toInt(), (pg * 0.62f).toInt(), (pb * 0.62f).toInt())
                    ImageEditCommand.MarkFilter.GRAY -> lum.toInt().coerceIn(0, 255).let { Color.rgb(it, it, it) }
                    ImageEditCommand.MarkFilter.BLUR, ImageEditCommand.MarkFilter.PIXELATE -> blurred!![y * rw + x]
                    ImageEditCommand.MarkFilter.COLOR -> {
                        // Se mantienen las luces y sombras de lo marcado, con el color nuevo
                        val k = 0.25f + 1.1f * (lum / 255f)
                        Color.rgb((tr * k).toInt().coerceIn(0, 255), (tg * k).toInt().coerceIn(0, 255), (tb * k).toInt().coerceIn(0, 255))
                    }
                    else -> p
                }
                row[x] = mix(q or (0xFF shl 24), p, a.coerceIn(0f, 1f))
            }
            out.setPixels(row, 0, rw, r.left, r.top + y, rw, 1)
        }
        tech += "Edición en el teléfono (sin IA)"
        return out
    }

    /** 🪄 La IA de imágenes dibuja [prompt] SOLO en lo marcado (con borde suave); el resto de la foto queda igual. */
    private fun generateIn(src: Bitmap, soft: FloatArray, sw: Int, sh: Int, prompt: String, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val r0 = regionOf(soft, sw, sh, w, h, 0.25f) ?: return src
        // Zona cuadrada alrededor de lo marcado (la IA trabaja mejor así)
        val sideR = max(max(r0.width(), r0.height()), 96).coerceAtMost(min(w, h).coerceAtLeast(1)).coerceAtMost(max(w, h))
        val rw = min(sideR, w); val rh = min(sideR, h)
        val rl = (r0.exactCenterX() - rw / 2f).roundToInt().coerceIn(0, w - rw); val rt = (r0.exactCenterY() - rh / 2f).roundToInt().coerceIn(0, h - rh)
        val region = Rect(rl, rt, rl + rw, rt + rh)
        val crop = Bitmap.createBitmap(src, region.left, region.top, rw, rh)
        val gen = img2img(crop, "$prompt, natural, matching the surrounding photo, high quality, detailed, family friendly", strength)
        if (crop !== src) crop.recycle()
        val fit = if (gen.width != rw || gen.height != rh) Bitmap.createScaledBitmap(gen, rw, rh, true).also { if (it !== gen) gen.recycle() } else gen
        val alpha = upsampleRegion(soft, sw, sh, w, h, region)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(rw); val grow = IntArray(rw)
        for (y in 0 until rh) {
            out.getPixels(row, 0, rw, rl, rt + y, rw, 1)
            fit.getPixels(grow, 0, rw, 0, y, rw, 1)
            for (x in 0 until rw) { val a = alpha[y * rw + x]; if (a > 0.003f) row[x] = mix(grow[x], row[x], a.coerceIn(0f, 1f)) }
            out.setPixels(row, 0, rw, rl, rt + y, rw, 1)
        }
        fit.recycle()
        return out
    }

    /** La IA de imágenes (SDXS) sobre una foto chica: la misma de «versión nueva», con lo que pediste. */
    private fun img2img(src: Bitmap, prompt: String, strength: Float): Bitmap {
        if (!ImageGenNative.available) throw NeedsInfo(
            "Para agregar o reemplazar algo se necesita la IA de imágenes, y no vino incluida en esta compilación de la app." +
                ImageGenNative.loadError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(120)})" }.orEmpty() +
                "\n\nBorrar y editar lo marcado (aclarar, blanco y negro, difuminar, pintar) sí funciona.")
        val ai = loadImageAi(useControl = false)
        val (gw, gh) = genSize(src.width, src.height)
        val flat = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, gw, gh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(gw * gh); flat.getPixels(px, 0, gw, 0, 0, gw, gh); flat.recycle()
        val rgb = ByteArray(gw * gh * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        val negative = "blurry, low quality, deformed, ugly, watermark, text, nsfw, nude, marker, drawn circle, drawn line, arrow, red marker, red circle, red line"
        val seed = (System.nanoTime() and 0x7FFFFFFF)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            val started = System.currentTimeMillis()
            while (running.get()) {
                if (isCancelled()) { ImageGenNative.cancel(); break }
                val secs = (System.currentTimeMillis() - started) / 1000
                progress("🪄 Dibujando en lo marcado con la IA (CPU)… $secs", true)
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val out: ByteArray? = try {
            // (lo marcado: SIN ControlNet, porque ahí hay que dibujar algo nuevo, no seguir los contornos)
            generate(ImageAi(ai.pro, ai.label, ai.version, ai.oneStep, false), rgb, gw, gh, prompt, negative, strength, seed)
        } finally {
            running.set(false)
            watcher.interrupt()
            ImageGenNative.free()
        }
        checkStop()
        if (out == null || out.size < gw * gh * 3) {
            val why = ImageGenNative.lastError()
            throw NeedsInfo("La IA no pudo dibujar lo que pediste." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
        }
        val outPx = IntArray(gw * gh) { i ->
            (0xFF shl 24) or ((out[i * 3].toInt() and 0xFF) shl 16) or ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or (out[i * 3 + 2].toInt() and 0xFF)
        }
        tech += techLabel(ImageAi(ai.pro, ai.label, ai.version, ai.oneStep, false))
        return Bitmap.createBitmap(outPx, gw, gh, Bitmap.Config.ARGB_8888)
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
        // Tope del teléfono: 4096 px por lado. Al SUBIR la resolución se permite hasta 8K (7680 px) si el teléfono
        // tiene memoria libre para la imagen grande; si no, se queda en 4096 y se avisa por qué.
        val wantedLong = max(tw, th)
        val side = if (s.kind == Kind.UPSCALE && wantedLong > MAX_SIDE && fitsInMemory(tw, th, wantedLong)) UPSCALE_MAX_SIDE else MAX_SIDE
        val cap = min(1f, side.toFloat() / max(tw, th))
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
        } + (if (!capped) "."
            else if (side == MAX_SIDE && s.kind == Kind.UPSCALE && wantedLong > MAX_SIDE) " (para ${wantedLong} px el teléfono no tenía memoria libre suficiente ahora; cierra otras apps y vuelve a pedírmelo)."
            else " ($side px es lo máximo que maneja el teléfono).")
        return out
    }

    /** ¿Cabe una imagen de tw×th (más su copia de trabajo) en la memoria libre del teléfono ahora mismo? */
    private fun fitsInMemory(tw: Int, th: Int, wantedLong: Int): Boolean = runCatching {
        val f = min(1f, UPSCALE_MAX_SIDE.toFloat() / wantedLong)
        val bytes = (tw * f).toLong() * (th * f).toLong() * 4L * 3L   // la imagen, una copia y el guardado
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !mi.lowMemory && bytes < mi.availMem / 2
    }.getOrDefault(false)

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
            if (done < 2 || done == total - 1) ImgPhase.mark("calculando el bloque ${done + 1} de $total en ${engine.label}")
            itp.run(inBuf, outBuf)
            if (done == 0) ImgPhase.mark("bloque 1 listo")
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
    private fun removeSubject(src0: Bitmap, all: Boolean, target: String = ""): Bitmap {
        val src = capTo(src0, INPAINT_SIDE)
        progress("🧠 Buscando a la persona…", true)
        val seg = segment(src, multi = true) ?: throw NeedsInfo(
            "No pude encontrar a la persona. La primera vez se necesita internet para que se descargue la IA de Google.")
        tech += "Separar personas: ML Kit (Google)"
        // 🎯 «Quita al hombre de la derecha con camisa blanca»: se elige a UNO (los demás quedan intactos)
        val chosen = if (all || target.isBlank()) null else chooseSubject(src, seg, target)
        val hole = chosen?.let { s -> subjectOnly(seg, s) } ?: subjectMask(seg, all) ?: throw NeedsInfo("No encontré ninguna persona u objeto principal en la foto.")
        val out = inpaint(src, hole, seg.w, seg.h)
        if (src !== src0) src.recycle()
        summary += when {
            all -> "🧽 Quité a las personas y rellené el espacio con IA."
            chosen != null -> "🧽 Quité solo ${chosenLabel} y rellené el espacio con IA (lo demás quedó igual)."
            else -> "🧽 Quité a la persona y rellené el espacio con IA."
        }
        return out
    }

    /** Lo que se quitó, en palabras («a la persona de la derecha (arriba blanco, abajo negro)»). */
    private var chosenLabel = "a la persona"

    /** Máscara de un solo sujeto (a la resolución de la IA). */
    private fun subjectOnly(seg: Segmentation, s: Subject): FloatArray {
        val m = FloatArray(seg.w * seg.h)
        for (yy in 0 until s.h) for (xx in 0 until s.w) {
            val gx = s.x + xx; val gy = s.y + yy
            if (gx < 0 || gy < 0 || gx >= seg.w || gy >= seg.h) continue
            m[gy * seg.w + gx] = max(m[gy * seg.w + gx], s.mask[yy * s.w + xx])
        }
        return m
    }

    /** Cómo se ve cada persona u objeto: dónde está, qué tan grande es y de qué color es su ropa (arriba y abajo). */
    private class SubjectInfo(val s: Subject, val cx: Float, val area: Float, val upper: List<String>, val lower: List<String>, val whole: List<String>)

    /** Nombre del color de un pixel (blanco, negro, gris, rojo, naranjo, amarillo, verde, celeste, azul, morado, rosado, café, beige). */
    private fun colorWord(p: Int): String {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val c = mx - mn
        if (mx < 55) return "negro"
        if (c < 28 || c.toFloat() / mx < 0.16f) return when { mx < 75 -> "negro"; mn > 185 -> "blanco"; else -> "gris" }
        var h = when (mx) {
            r -> 60f * (((g - b).toFloat() / c) % 6f)
            g -> 60f * ((b - r).toFloat() / c + 2f)
            else -> 60f * ((r - g).toFloat() / c + 4f)
        }
        if (h < 0f) h += 360f
        if (h in 12f..48f && mx < 165) return "cafe"
        if (h in 20f..55f && c < 95 && mx >= 165) return "beige"
        return when {
            h < 12f || h >= 345f -> "rojo"; h < 40f -> "naranjo"; h < 70f -> "amarillo"; h < 165f -> "verde"
            h < 200f -> "celeste"; h < 255f -> "azul"; h < 290f -> "morado"; else -> "rosado"
        }
    }

    /** Los colores que más hay en una franja del sujeto (de 0 = arriba a 1 = abajo). */
    private fun bandColors(src: Bitmap, seg: Segmentation, s: Subject, from: Float, to: Float): List<String> {
        val fx = src.width.toFloat() / seg.w; val fy = src.height.toFloat() / seg.h
        val votes = HashMap<String, Int>(); var total = 0
        val y0 = (s.h * from).toInt(); val y1 = (s.h * to).toInt().coerceAtMost(s.h)
        val step = max(1, (s.w * (y1 - y0) / 2500f).let { sqrt(it).toInt() })
        var yy = y0
        while (yy < y1) {
            var xx = 0
            while (xx < s.w) {
                if (s.mask[yy * s.w + xx] > 0.6f) {
                    val px = ((s.x + xx) * fx).toInt().coerceIn(0, src.width - 1); val py = ((s.y + yy) * fy).toInt().coerceIn(0, src.height - 1)
                    val wd = colorWord(src.getPixel(px, py)); votes[wd] = (votes[wd] ?: 0) + 1; total++
                }
                xx += step
            }
            yy += step
        }
        if (total == 0) return emptyList()
        return votes.entries.sortedByDescending { it.value }.filter { it.value >= total * 0.22f }.map { it.key }.take(2)
    }

    /** ¿Se parece el color pedido a alguno de los que tiene? 1 = igual · 0.5 = parecido · 0 = no. */
    private fun colorMatch(want: String, have: List<String>): Float {
        if (want in have) return 1f
        val near = mapOf("blanco" to setOf("gris", "beige"), "negro" to setOf("gris", "azul", "cafe"), "gris" to setOf("blanco", "negro"),
            "azul" to setOf("celeste", "morado", "negro"), "celeste" to setOf("azul", "verde"), "rojo" to setOf("rosado", "naranjo", "cafe"),
            "rosado" to setOf("rojo", "morado"), "naranjo" to setOf("rojo", "amarillo", "cafe"), "amarillo" to setOf("naranjo", "beige"),
            "cafe" to setOf("naranjo", "negro", "beige", "rojo"), "beige" to setOf("blanco", "amarillo", "cafe"), "verde" to setOf("celeste"),
            "morado" to setOf("azul", "rosado"))
        return if (have.any { it in (near[want] ?: emptySet()) }) 0.5f else 0f
    }

    /**
     * 🎯 Elige a la persona u objeto que describiste: por lado (izquierda / derecha / medio), adelante o atrás, color de la ropa
     * de arriba y de abajo, y —si la IA del chat tiene visión— si es hombre, mujer, niño o qué objeto es.
     * Si no queda claro cuál es, pregunta (en vez de quitar a la persona equivocada).
     */
    private fun chooseSubject(src: Bitmap, seg: Segmentation, target: String): Subject? {
        val subs = seg.subjects.filter { it.area > seg.w * seg.h * 0.002f }
        val q = ImageEditCommand.subjectQuery(target)
        if (q.isEmpty) return null   // «quita a la persona» sin más: como siempre (la más grande)
        val infos = subs.map { s ->
            val cx = (s.x + s.w / 2f) / seg.w
            SubjectInfo(s, cx, s.area, bandColors(src, seg, s, 0.2f, 0.5f), bandColors(src, seg, s, 0.55f, 0.92f), bandColors(src, seg, s, 0f, 1f))
        }.sortedBy { it.cx }
        if (infos.isEmpty()) return null
        val n = infos.size
        fun place(i: Int): String = when {
            n == 1 -> "de la foto"
            n == 2 -> if (i == 0) "de la izquierda" else "de la derecha"
            i == 0 -> "de la izquierda"; i == n - 1 -> "de la derecha"
            n == 3 -> "del medio"
            else -> "(la ${i + 1}ª desde la izquierda)"
        }
        fun clothes(f: SubjectInfo) = "arriba ${f.upper.joinToString(" y ").ifEmpty { "?" }}, abajo ${f.lower.joinToString(" y ").ifEmpty { "?" }}"
        val who = when {
            "hombre" in q.kinds -> "al hombre"; "mujer" in q.kinds -> "a la mujer"
            "nino" in q.kinds -> "al niño"; "nina" in q.kinds -> "a la niña"
            q.kinds.isNotEmpty() -> "lo que pediste («${q.kinds.first()}»)"
            else -> "a la persona"
        }
        fun label(i: Int) = if (q.kinds.any { it !in setOf("hombre", "mujer", "nino", "nina") } && q.kinds.none { it in setOf("hombre", "mujer", "nino", "nina") })
            "$who, ${place(i).removePrefix("(").removeSuffix(")")}" else "$who ${place(i)} (${clothes(infos[i])})"
        fun listing() = infos.indices.joinToString("\n") { "• ${it + 1}) ${place(it).removePrefix("(").removeSuffix(")")}: ${clothes(infos[it])}" }
        // 1) Por lo que se ve: lado, tamaño y colores
        val maxArea = infos.maxOf { it.area }; val minArea = infos.minOf { it.area }
        val scores = infos.mapIndexed { i, f ->
            var sc = 0f
            val rank = if (n > 1) i.toFloat() / (n - 1) else 0.5f
            when (q.side) {
                "izq" -> sc += 3f * (1f - rank) - 1f
                "der" -> sc += 3f * rank - 1f
                "medio" -> sc += 2f - 4f * abs(f.cx - 0.5f)
            }
            when (q.size) {
                "grande" -> sc += if (f.area == maxArea) 2f else -0.5f
                "chico" -> sc += if (f.area == minArea) 2f else -0.5f
            }
            for (c in q.upper) { val m = colorMatch(c, f.upper); sc += if (m > 0f) 3f * m else -1.5f }
            for (c in q.lower) { val m = colorMatch(c, f.lower); sc += if (m > 0f) 3f * m else -1.5f }
            for (c in q.colors) { val m = colorMatch(c, f.whole); sc += if (m > 0f) 2f * m else -1f }
            sc
        }
        val order = scores.indices.sortedByDescending { scores[it] }
        val clear = n == 1 || scores[order[0]] - scores[order[1]] >= 1f
        val onlyByLook = q.kinds.isEmpty() && !q.relation
        // 2) «el hombre», «la mujer», «la bicicleta», «al lado del…»: lo mira la IA del chat (si tiene visión)
        if (pickSubject != null && (!onlyByLook || !clear)) {
            progress("👁️ Miro cuál es el que pediste…", true)
            val marked = numbered(src, seg, infos.map { it.s })
            val num = try { pickSubject.invoke(marked, n) } finally { marked.recycle() }
            checkStop()
            if (num != null && num == 0 && !onlyByLook) throw NeedsInfo("🔎 No encontré en la foto lo que me pediste quitar. " +
                "Márcalo con el lápiz ✏️ (toca la foto, luego el lápiz, encierra lo que quieres quitar y toca ✓) y pídeme «bórralo».")
            if (num != null && num in 1..n) {
                tech += "Elegido con la visión de la IA del chat"
                chosenLabel = label(num - 1)
                return infos[num - 1].s
            }
        }
        if (n == 1) { chosenLabel = label(0); return infos[0].s }
        // 3) Sin visión: si dijiste solo «el hombre» o «la bicicleta», no se puede saber cuál es → se pregunta
        if (!onlyByLook && q.side == null && q.size == null && q.upper.isEmpty() && q.lower.isEmpty() && q.colors.isEmpty())
            throw NeedsInfo("🔎 Encontré $n personas u objetos:\n${listing()}\n\n" +
                "Para saber cuál es el hombre, la mujer o el objeto que dices necesito un modelo con visión (que pueda ver fotos). " +
                "Dime de qué lado está («el de la derecha»), el color de su ropa («el de camisa blanca»), o márcalo con el lápiz ✏️.")
        if (!clear) throw NeedsInfo("🔎 Encontré $n personas u objetos y no estoy seguro de cuál quieres quitar:\n${listing()}\n\n" +
            "Dímelo con más detalle («el de la derecha», «el de polera roja») o márcalo con el lápiz ✏️.")
        chosenLabel = label(order[0])
        return infos[order[0]].s
    }

    /** La foto con cada persona u objeto rodeado de un color y su número grande (para que la IA del chat diga cuál es). */
    private fun numbered(src: Bitmap, seg: Segmentation, subs: List<Subject>): Bitmap {
        val out = Bitmap.createScaledBitmap(src, seg.w, seg.h, true).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val palette = intArrayOf(0xFFFF1744.toInt(), 0xFF2979FF.toInt(), 0xFF00E676.toInt(), 0xFFFFEA00.toInt(), 0xFFD500F9.toInt(), 0xFF00E5FF.toInt())
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val th = max(2, max(seg.w, seg.h) / 300)
        subs.forEachIndexed { k, s ->
            dot.color = palette[k % palette.size]
            // Contorno
            for (yy in 0 until s.h) for (xx in 0 until s.w) {
                if (s.mask[yy * s.w + xx] <= 0.5f) continue
                val edge = xx == 0 || yy == 0 || xx == s.w - 1 || yy == s.h - 1 ||
                    s.mask[yy * s.w + xx - 1] <= 0.5f || s.mask[yy * s.w + xx + 1] <= 0.5f ||
                    s.mask[(yy - 1) * s.w + xx] <= 0.5f || s.mask[(yy + 1) * s.w + xx] <= 0.5f
                if (edge) c.drawRect((s.x + xx - th).toFloat(), (s.y + yy - th).toFloat(), (s.x + xx + th).toFloat(), (s.y + yy + th).toFloat(), dot)
            }
            // Número en un círculo blanco, arriba al centro
            val r = max(14f, max(seg.w, seg.h) / 28f)
            val cx = (s.x + s.w / 2f).coerceIn(r, seg.w - r); val cy = (s.y + r * 1.2f).coerceIn(r, seg.h - r)
            dot.color = Color.WHITE; c.drawCircle(cx, cy, r, dot)
            text.color = Color.BLACK; text.textSize = r * 1.3f
            c.drawText("${k + 1}", cx, cy + r * 0.45f, text)
        }
        return out
    }

    /**
     * 🎈 Cuando la IA de Google no separa lo marcado (un globo, un letrero, una pelota…): se sigue su COLOR desde la marca
     * (la punta de la flecha, lo que cruza la raya o el centro del círculo) hasta donde cambia el color. Pasa por debajo
     * de la marca (la raya no lo corta en dos). null = no se pudo (muy chico, o se desparrama por todo el fondo).
     */
    private fun colorRegion(px: IntArray, stroke: BooleanArray, strokeWide: BooleanArray, target: BooleanArray, kind: String,
                            sw: Int, sh: Int, gap: Int): BooleanArray? {
        val n = sw * sh
        val seeds: List<Int> = when (kind) {
            "flecha" -> target.indices.filter { target[it] && !strokeWide[it] }
            "círculo" -> { val core = distanceToFalse(target, sw, sh); target.indices.filter { core[it] > gap * 3f && !strokeWide[it] } }
            else -> { val band = dilate(stroke, sw, sh, gap * 2); band.indices.filter { band[it] && !strokeWide[it] } }
        }
        if (seeds.size < 6) return null
        // Color del objeto: la mediana de las semillas (si la raya pasa un poco por el fondo, igual gana el objeto)
        val sample = if (seeds.size > 3000) seeds.filterIndexed { i, _ -> i % (seeds.size / 3000 + 1) == 0 } else seeds
        fun med(sh0: Int) = sample.map { (px[it] shr sh0) and 0xFF }.sorted()[sample.size / 2]
        val mr = med(16); val mg = med(8); val mb = med(0)
        fun dist(p: Int): Int { val dr = ((p shr 16) and 0xFF) - mr; val dg = ((p shr 8) and 0xFF) - mg; val db = (p and 0xFF) - mb; return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt() }
        val spread = sample.map { dist(px[it]) }.sorted()[sample.size / 2]
        val tol = (spread * 3).coerceIn(30, 62)
        val limit = if (kind == "círculo") target.count { it } else (n * 0.35f).toInt()
        val inArea = BooleanArray(n); val queue = IntArray(n); var qh = 0; var qt = 0
        for (i in seeds) if (dist(px[i]) <= tol && !inArea[i]) { inArea[i] = true; queue[qt++] = i }
        if (qt < 4) return null
        var real = 0
        while (qh < qt) {
            val i = queue[qh++]
            if (!strokeWide[i]) real++
            if (qt > limit) return null   // se desparramó (es el fondo, no un objeto)
            val x = i % sw; val y = i / sw
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < sw - 1) i + 1 else -1, if (y > 0) i - sw else -1, if (y < sh - 1) i + sw else -1)) {
                if (j < 0 || inArea[j]) continue
                if (kind == "círculo" && !target[j]) continue
                if (strokeWide[j] || dist(px[j]) <= tol) { inArea[j] = true; queue[qt++] = j }
            }
        }
        if (real < max(30, (n * 0.0015f).toInt())) return null
        if (kind == "círculo" && real < target.count { it } * 0.1f) return null
        // Solo el objeto (la marca de afuera, como el palo de la flecha, no cuenta): se cierra el paso de la raya
        // por encima del objeto y los hoyitos, sin agregar el fondo
        val real0 = BooleanArray(n) { inArea[it] && !strokeWide[it] }
        val closeR = gap * 2 + 2
        val grown = dilate(real0, sw, sh, closeR)
        val back = distanceToFalse(grown, sw, sh)
        val closed = BooleanArray(n) { back[it] > closeR }
        val holes = insideOf(closed, sw, sh)
        val out = BooleanArray(n) { real0[it] || closed[it] || holes[it] }
        tech += "Silueta de lo marcado: siguiendo su color"
        return dilate(out, sw, sh, gap)
    }

    /**
     * 🖍️🎯 ¿Lo marcado es una persona u objeto? (encerrado con un círculo, señalado con una flecha o tachado con una raya)
     * Si sí, se devuelve SOLO su silueta (a la resolución de la marca); si no, null y se borra lo marcado como siempre.
     */
    private fun markedSubjects(src: Bitmap, stroke: BooleanArray, target: BooleanArray, kind: String, sw: Int, sh: Int, gap: Int): BooleanArray? {
        val seg = segment(src, multi = true, maxWaitMs = 20_000L) ?: return null
        val subs = seg.subjects.filter { it.area > seg.w * seg.h * 0.002f }
        if (subs.isEmpty()) return null
        val targetCount = target.count { it }.coerceAtLeast(1)
        val strokeIdx = stroke.indices.filter { stroke[it] }
        var sx0 = sw; var sy0 = sh; var sx1 = 0; var sy1 = 0
        for (i in strokeIdx) { val x = i % sw; val y = i / sw; if (x < sx0) sx0 = x; if (x > sx1) sx1 = x; if (y < sy0) sy0 = y; if (y > sy1) sy1 = y }
        val picked = mutableListOf<BooleanArray>()
        var best: BooleanArray? = null; var bestOverlap = 0f
        for (s in subs) {
            // La silueta en la cuadrícula de la marca
            val m = BooleanArray(sw * sh)
            var area = 0; var overlap = 0; var strokeHits = 0
            var bx0 = sw; var by0 = sh; var bx1 = 0; var by1 = 0
            for (y in 0 until sh) for (x in 0 until sw) {
                val gx = (x * seg.w / sw) - s.x; val gy = (y * seg.h / sh) - s.y
                if (gx < 0 || gy < 0 || gx >= s.w || gy >= s.h || s.mask[gy * s.w + gx] <= 0.5f) continue
                val i = y * sw + x
                m[i] = true; area++
                if (target[i]) overlap++
                if (stroke[i]) strokeHits++
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
            }
            if (area == 0) continue
            val inside = overlap.toFloat() / area
            when (kind) {
                // ⭕ Encerrado casi completo (no una manchita encerrada sobre su ropa)
                "círculo" -> if (inside >= 0.6f) picked += m
                // ➡️ La punta de la flecha cae sobre él
                "flecha" -> { val f = overlap.toFloat() / targetCount; if (f >= 0.4f && f > bestOverlap) { bestOverlap = f; best = m } }
                // ✖️ La raya lo cruza de lado a lado (o de arriba a abajo)
                else -> {
                    val hits = strokeHits.toFloat() / strokeIdx.size.coerceAtLeast(1)
                    val spanX = (min(sx1, bx1) - max(sx0, bx0)).toFloat() / (bx1 - bx0 + 1)
                    val spanY = (min(sy1, by1) - max(sy0, by0)).toFloat() / (by1 - by0 + 1)
                    if (hits >= 0.5f && (spanX >= 0.6f || spanY >= 0.6f) && hits > bestOverlap) { bestOverlap = hits; best = m }
                }
            }
        }
        best?.let { picked += it }
        if (picked.isEmpty()) return null
        val union = BooleanArray(sw * sh) { i -> picked.any { it[i] } }
        tech += "Silueta de lo marcado: ML Kit (Google)"
        return dilate(union, sw, sh, gap)
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
    // 🪄 LA IA DE IMÁGENES QUE SE USA (la rápida o la de mejor calidad)
    //    · Rápida: SDXS-512 (683 MB, un paso, segundos; resultado simple).
    //    · Mejor calidad: DreamShaper 8 LCM (≈2 GB, 4-8 pasos) + ControlNet Canny (≈690 MB), que sigue los contornos
    //      de la foto: cambia el estilo (3D, anime, pintura…) sin cambiar la forma de la cara ni de las cosas.
    // =====================================================================
    private class ImageAi(val pro: Boolean, val label: String, val version: String, val oneStep: Boolean, val control: Boolean)

    /** Lee un archivo grande de corrido (queda en la memoria del sistema, así abrirlo después es instantáneo). */
    private fun warmFile(f: File) {
        runCatching {
            java.io.FileInputStream(f).use { inp ->
                val buf = ByteArray(8 shl 20)
                while (!isCancelled() && inp.read(buf) > 0) { }
            }
        }
    }

    private fun loadImageAi(useControl: Boolean = true): ImageAi {
        val pro = ImageAiModels.usePro(context)
        val spec = if (pro) ImageAiModels.IMAGE_PRO else ImageAiModels.IMAGE_GEN
        val modelFile = models.ensure(spec) ?: run {
            checkStop()
            throw NeedsInfo("No pude descargar la IA de imágenes (≈${spec.sizeMb} MB). Revisa tu internet y el espacio libre, y vuelve a pedírmelo: la descarga sigue donde quedó." +
                models.lastError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(100)})" }.orEmpty())
        }
        checkStop()
        // 🧠 La de mejor calidad usa ≈3 GB de RAM: el modelo del chat se pone en pausa mientras tanto
        if (pro) runCatching { beforeBigImageAi?.invoke() }
        // 🧭 ControlNet (solo con la de mejor calidad). Si no se pudo bajar, igual se crea la imagen, sin él.
        val control = if (pro && useControl) models.ensure(ImageAiModels.CONTROLNET_CANNY) else null
        checkStop()
        ImageGenNative.init(context.filesDir.absolutePath)
        progress("🧠 Cargando la IA de imágenes…", true)
        // ⚡ Se lee el archivo de una vez, de corrido (unos segundos), antes de abrirlo: la IA lo usa «mapeado» y,
        //    si no está en la memoria, lo va leyendo de a pedacitos mientras dibuja, lo que tardaba ≈7 minutos
        //    (la primera imagen tardaba eso; el repaso de la cara, con todo ya leído, solo 14 s)
        if (pro) for (f in listOfNotNull(modelFile, control)) warmFile(f)
        checkStop()
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        var withControl = control != null
        // (runCatching: si la librería de imágenes quedó de una compilación anterior, no se cae la app)
        var ok = runCatching { ImageGenNative.nativeLoad(modelFile.absolutePath, control?.absolutePath.orEmpty(), threads) }.getOrDefault(false)
        if (!ok && withControl) {   // (si el ControlNet no carga, se prueba sin él)
            withControl = false
            ok = runCatching { ImageGenNative.nativeLoad(modelFile.absolutePath, "", threads) }.getOrDefault(false)
        }
        if (!ok) {
            val why = ImageGenNative.lastError()
            throw NeedsInfo("No pude abrir la IA de imágenes." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
        }
        checkStop()
        val version = runCatching { ImageGenNative.nativeModelVersion() }.getOrDefault("")
        val oneStep = !pro && (version.contains("SDXS", ignoreCase = true) || version.contains("turbo", ignoreCase = true))
        return ImageAi(pro, spec.label, version, oneStep, withControl)
    }

    /** Crea la imagen con la IA cargada (rápida: 1 paso · mejor calidad: LCM en 8 pasos, con los contornos de la foto). */
    private fun generate(ai: ImageAi, rgb: ByteArray, gw: Int, gh: Int, prompt: String, negative: String, strength: Float, seed: Long): ByteArray? = when {
        ai.oneStep -> ImageGenNative.nativeImg2Img(rgb, gw, gh, prompt, negative, noiseLevelFor(strength), 1, 1f, seed, true)
        // (con ControlNet: cambio moderado + contornos a 0,8 → cambia el estilo sin inventar detalles nuevos,
        //  como una segunda ceja, ojos borrosos o el pelo corrido sobre la frente)
        // (fuerza hasta 0,5 —antes 0,55 a 0,65—: como en la NPU, rehacer menos respeta más la foto: la cara se parece
        //  más, la expresión no cambia y no desaparecen manos ni objetos chicos · el repaso de la cara usa 0,35)
        ai.pro -> ImageGenNative.nativeImg2Img(rgb, gw, gh, prompt, negative,
            if (ai.control) strength.coerceIn(0.3f, 0.5f) else strength.coerceAtMost(0.7f),
            // (contornos al 1,0 —antes 0,8—: respeta mejor la forma de la mano, de lo que sostiene y de las orejas)
            8, 1.5f, seed, false, true, if (ai.control) 1.0f else 0f)
        else -> ImageGenNative.nativeImg2Img(rgb, gw, gh, prompt, negative, strength, 20, 7f, seed, false)
    }

    private fun techLabel(ai: ImageAi): String =
        "IA de imágenes ${ai.label}" + (if (ai.control) " + ControlNet" else "") + " en la CPU" + (if (ai.version.isNotBlank()) " (${ai.version})" else "")

    // =====================================================================
    // 🪄 VERSIÓN NUEVA DE LA MISMA IMAGEN (stable-diffusion.cpp, en la CPU)
    // =====================================================================
    /** 👤 «male», «female» o null (no se sabe): si lo pediste («versión femenina») manda eso; si no, lo mira la IA del chat. */
    private var gender: String? = null

    private fun subjectGender(src: Bitmap, s: Step): String? {
        val t = (s.prompt + " " + s.what).lowercase(java.util.Locale.ROOT)
        when {
            Regex("\\b(female version|as a woman|femenin[oa])\\b").containsMatchIn(t) -> { tech += "👤 mujer (lo pediste)"; return "female" }
            Regex("\\b(male version|as a man|masculin[oa])\\b").containsMatchIn(t) -> { tech += "👤 hombre (lo pediste)"; return "male" }
        }
        val ask = askVision ?: return null
        progress("👁️ Miro si es hombre o mujer…", true)
        val small = runCatching {
            val f = 768f / max(src.width, src.height)
            if (f < 1f) Bitmap.createScaledBitmap(src, (src.width * f).roundToInt().coerceAtLeast(16), (src.height * f).roundToInt().coerceAtLeast(16), true) else src
        }.getOrNull() ?: return null
        val raw = try {
            runCatching {
                ask(small, "Mira esta imagen. La persona o el personaje principal, ¿es hombre o mujer? " +
                    "Fíjate en la cara, el pelo, el cuerpo y la ropa (también si es un dibujo o un personaje de anime). " +
                    "Responde SOLO con una palabra: hombre, mujer o ninguno.")
            }.getOrNull().orEmpty().lowercase(java.util.Locale.ROOT)
        } finally { if (small !== src) small.recycle() }
        checkStop()
        return when {
            Regex("\\b(mujer|woman|female|chica|niña)\\b").containsMatchIn(raw) -> { tech += "👤 mujer (lo vio la IA del chat)"; "female" }
            Regex("\\b(hombre|man|male|chico|niño)\\b").containsMatchIn(raw) -> { tech += "👤 hombre (lo vio la IA del chat)"; "male" }
            else -> null
        }
    }

    /** ⏱️ Cuánto tardó cada parte de la última imagen en la CPU (de los mensajes de stable-diffusion.cpp). */
    private fun sdTimes(): String {
        val names = listOf("learned_condition" to "texto", "condition" to "texto", "encode" to "leer la foto",
            "sampling" to "pasos", "decode" to "terminar", "control" to "contornos", "generate_image" to "total")
        return ImageGenNative.lastLogs().lines().mapNotNull { l ->
            val m = Regex("([A-Za-z_\\[\\]: ]+?)\\s*completed(?:,)?\\s*(?:taking|in)\\s*([0-9.]+\\s*m?s)").find(l) ?: return@mapNotNull null
            val n = m.groupValues[1].lowercase(java.util.Locale.ROOT)
            val label = names.firstOrNull { n.contains(it.first) }?.second ?: n.trim().take(18)
            "$label ${m.groupValues[2].replace(" ", "")}"
        }.distinct().joinToString(", ")
    }

    /** Lo que la IA NO debe dibujar al cambiar una foto (la de mejor calidad y la NPU entienden la lista larga). */
    private fun variationNegative(pro: Boolean): String =
        "blurry, low quality, deformed, ugly, watermark, text, nsfw, nude" +
            (if (pro) ", bad anatomy, extra fingers, disfigured face, distorted face, double eyebrows, extra eyebrows, " +
                "blurry eyes, deformed eyes, cross-eyed, duplicate, screenshot, user interface, buttons, text box, " +
                "torn clothes, ripped clothing, damaged clothes, mask, face paint, stains on face, blotchy skin, discolored skin, " +
                "hair on forehead, different hairstyle, " +
                // (las arrugas o rayas del ceño fruncido NO son cejas: la IA las dibujaba como cejas gruesas en la frente;
                //  el «:1.3» le da más peso a esto en stable-diffusion.cpp)
                "(eyebrows on forehead:1.3), (extra eyebrows:1.3), (thick eyebrows:1.2), bushy eyebrows, unibrow, " +
                // (en la NPU los ojos salían gigantes de caricatura y el brillo sobre los ojos se volvía una franja o sonrisa)
                "(oversized eyes:1.2), big cartoon eyes, (stripe across eyes:1.2), band over eyes, creepy grin, different face, " +
                // (en 3D las líneas finas del dibujo salían como grietas, y la boca fina se borraba)
                "(cracks on face:1.3), (lines on face:1.2), wrinkled forehead, scratches on skin, (no mouth:1.2), missing mouth" else "") +
            // 👤 que no cambie de hombre a mujer ni al revés
            when (gender) {
                "male" -> ", (woman:1.3), (female:1.3), (breasts:1.3), cleavage, feminine face, makeup, lipstick, eyeliner, long eyelashes"
                "female" -> ", (man:1.3), male, masculine face, beard, mustache"
                else -> ""
            }

    private fun variation(src: Bitmap, s: Step): Bitmap {
        if (!ImageGenNative.available) throw NeedsInfo(
            "La IA para crear versiones nuevas no vino incluida en esta compilación de la app." +
                ImageGenNative.loadError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(120)})" }.orEmpty() +
                "\n\nLo demás (recortar, agrandar, fondo, personas) sí funciona.")
        // 👤 ¿Hombre o mujer? (para que un hombre no salga con cara y cuerpo de mujer, ni al revés)
        gender = subjectGender(src, s)
        // ⚡ En la NPU (Snapdragon con el modelo oficial de Qualcomm): segundos. Si falla, sigue en la CPU.
        var npuNote = ""
        if (ImageGenNpu.useNpu(context)) {
            try {
                return variationNpu(src, s)
            } catch (nf: NpuFailed) {
                val why = nf.message.orEmpty().take(160)
                progress("⚡ La NPU no pudo cambiar la foto ($why): sigo en la CPU", true)
                if (!ImageAiModels.isReady(context, ImageAiModels.preferred(context))) throw NeedsInfo(
                    "⚠️ La NPU no pudo cambiar la foto.\n(motivo: $why)\n\n" +
                        "Puedo hacerlo en la CPU (tarda minutos): dime «usa la CPU para crear imágenes» y vuelve a pedírmelo.")
                npuNote = "ℹ️ La NPU no pudo esta vez, así que lo hice en la CPU. (motivo: ${why.take(100)})"
            }
        }
        val t0 = System.currentTimeMillis()
        val ai = loadImageAi()
        val tLoad = System.currentTimeMillis() - t0
        // Tamaño de trabajo: con la de mejor calidad, el lado corto en 512 (el largo hasta 1024), como en la NPU
        // —antes ≈512×512 en total: una foto vertical quedaba en 396×704 y la mano o lo que sostienes tenían muy
        // pocos píxeles—; con la rápida, ≈512×512 (múltiplos de 64)
        val (gw, gh) = if (ai.pro) cpuProSize(src.width, src.height) else genSize(src.width, src.height)
        val flat = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)    // lo transparente queda blanco
            drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, gw, gh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(gw * gh); flat.getPixels(px, 0, gw, 0, 0, gw, gh); flat.recycle()
        val rgb = ByteArray(gw * gh * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        val prompt = buildPrompt(s, ai.pro)
        val negative = variationNegative(ai.pro)
        // 🎲 Misma foto = mismo resultado la primera vez (para comparar ajustes); «otra» sale distinta
        val seed = npuSeed(px, s)
        // 🙂 Dónde está la cara (solo con la de mejor calidad: ahí se hace el repaso de la cara)
        val side = 512
        val faceBox = if (ai.pro) detectFace(px, gw, gh) ?: detectAnimeFace(px, gw, gh) else null
        val faceSq = if (ai.pro) faceSquare(faceBox, gw, gh, side) else null
        var faceNote = when {
            !ai.pro || faceSq != null -> ""
            faceBox == null -> "sin repaso de la cara (no encontré una cara)"
            max(faceBox[2] - faceBox[0], faceBox[3] - faceBox[1]) * 1.8f < 48 -> "sin repaso de la cara (la cara es muy chica)"
            else -> "sin repaso de la cara (la cara ya ocupa casi toda la imagen)"
        }
        // ⏹️ Vigía: si tocas detener, la IA para al tiro; mientras tanto se muestra el avance
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val stage = java.util.concurrent.atomic.AtomicReference("🪄 Creando la versión nueva en la CPU" + (if (ai.pro) " (mejor calidad: 5 a 10 min)" else ""))
        val watcher = Thread {
            val started = System.currentTimeMillis()
            while (running.get()) {
                if (isCancelled()) { ImageGenNative.cancel(); break }
                val secs = (System.currentTimeMillis() - started) / 1000
                progress("${stage.get()}… $secs", true)
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val outPx: IntArray
        var tGen = 0L; var tFace = 0L; var tFinish = 0L
        try {
            val t1 = System.currentTimeMillis()
            val out: ByteArray? = generate(ai, rgb, gw, gh, prompt, negative, s.strength, seed)
            tGen = System.currentTimeMillis() - t1
            sdTimes().takeIf { it.isNotBlank() }?.let { tech += "🔬 dibujar: $it" }
            checkStop()
            if (out == null || out.size < gw * gh * 3) {
                val why = ImageGenNative.lastError()
                throw NeedsInfo("La IA no pudo crear la versión nueva." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
            }
            outPx = IntArray(gw * gh) { i ->
                (0xFF shl 24) or ((out[i * 3].toInt() and 0xFF) shl 16) or ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or (out[i * 3 + 2].toInt() and 0xFF)
            }
            // 🙂 Repaso de la cara en grande (como en la NPU): la cara se recorta, se agranda a 512×512, la IA la
            //    repasa suave (fuerza 0,35, siguiendo sus contornos) y se pega de vuelta con transición suave
            //    → ojos con pupila, boca y rasgos más finos y más parecidos a la foto
            if (faceSq != null) {
                val (fx, fy, fs) = Triple(faceSq[0], faceSq[1], faceSq[2])
                stage.set("🙂 Repasando la cara en la CPU")
                val init = cropSquareRgb(outPx, gw, gh, fx, fy, fs, side)
                val t2 = System.currentTimeMillis()
                val fr = runCatching {
                    generate(ai, init, side, side, prompt + ", detailed face, detailed eyes, clear pupils, sharp facial features",
                        negative, 0.35f, seed + 1)
                }.getOrNull()
                tFace = System.currentTimeMillis() - t2
                checkStop()
                if (fr != null && fr.size >= side * side * 3) {
                    pasteSquareRgb(outPx, gw, fx, fy, fs, fr, side)
                    faceNote = "repaso de la cara ✓ (${fs}×$fs → ${side}×$side)"
                } else faceNote = "repaso de la cara: no se pudo"
            }
            // ✨ Afinado final SIN contornos (como en la NPU, donde los contornos se sueltan en el último 30 %):
            //    una pasada suave (fuerza 0,25) en la que la IA ya no está obligada a dibujar cada línea de la foto
            //    → las arrugas de la frente no se vuelven una segunda ceja ni rayas, y se limpian bordes raros
            //    (pelo que sale hacia afuera junto a la oreja, ojos a medio dibujar)
            if (ai.pro && ai.control) {
                stage.set("✨ Afinando los detalles en la CPU")
                val cur = ByteArray(gw * gh * 3)
                for (i in outPx.indices) { val p = outPx[i]; cur[i * 3] = (p shr 16).toByte(); cur[i * 3 + 1] = (p shr 8).toByte(); cur[i * 3 + 2] = p.toByte() }
                val t3 = System.currentTimeMillis()
                val fin = runCatching {
                    ImageGenNative.nativeImg2Img(cur, gw, gh, prompt, negative, 0.25f, 8, 1.5f, seed + 2, false, true, 0f)
                }.getOrNull()
                tFinish = System.currentTimeMillis() - t3
                sdTimes().takeIf { it.isNotBlank() }?.let { tech += "🔬 afinar: $it" }
                checkStop()
                if (fin != null && fin.size >= gw * gh * 3) {
                    for (i in outPx.indices)
                        outPx[i] = (0xFF shl 24) or ((fin[i * 3].toInt() and 0xFF) shl 16) or ((fin[i * 3 + 1].toInt() and 0xFF) shl 8) or (fin[i * 3 + 2].toInt() and 0xFF)
                }
            }
            // 🎨 Mismos colores que la foto (pelo azul sigue azul, la piel igual), salvo que pidas cambiarlos (de noche, nieve…)
            if (keepsColors(s)) runCatching { keepColors(outPx, px, gw, gh) }
        } finally {
            running.set(false)
            watcher.interrupt()
            // Se libera la memoria de la IA de imágenes (así el modelo del chat tiene toda la RAM)
            ImageGenNative.free()
        }
        if (faceNote.isNotBlank()) tech += "🙂 $faceNote"
        // ⏱️ En qué se va el tiempo (para saber qué conviene acelerar)
        tech += "⏱️ cargar la IA ${tLoad / 1000} s · dibujar ${gw}×$gh ${tGen / 1000} s" + (if (tFace > 0) " · repasar la cara ${tFace / 1000} s" else "") +
            (if (tFinish > 0) " · afinar ${tFinish / 1000} s" else "")
        val gen = Bitmap.createBitmap(outPx, gw, gh, Bitmap.Config.ARGB_8888)
        // Misma forma exacta que la foto original (el tamaño de trabajo se redondeó a múltiplos de 64)
        val long = max(gw, gh)
        val f = long.toFloat() / max(src.width, src.height)
        val fw = (src.width * f).roundToInt().coerceAtLeast(16); val fh = (src.height * f).roundToInt().coerceAtLeast(16)
        val result = if (fw == gw && fh == gh) gen else Bitmap.createScaledBitmap(gen, fw, fh, true).also { if (it !== gen) gen.recycle() }
        tech += techLabel(ai)
        summary += "🪄 Creé una versión nueva de la imagen" + (if (s.what.isNotBlank()) " (${s.what})" else "") + " · ${result.width}×${result.height}."
        summary += if (ai.pro) "ℹ️ Si no te gusta, pídeme «otra» y sale distinta. Para más resolución, dime «súbele la resolución»."
            else "ℹ️ Es la IA rápida (pequeña): si no te gusta, pídeme «otra» y sale distinta. Para un resultado mucho mejor, dime «usa la IA de imágenes de mejor calidad»."
        if (npuNote.isNotBlank()) summary += npuNote
        return result
    }

    /**
     * ⚡ VERSIÓN NUEVA DE LA FOTO EN LA NPU: ControlNet-Canny oficial de Qualcomm + TAESD (20 pasos por cuadro).
     * La foto se trabaja con su lado corto en 512 y en cuadros de 512×512 que se solapan (uno central para la cara);
     * la IA parte de la foto y sigue sus contornos; después se unen los cuadros y se le devuelven sus colores.
     * Si algo falla en la NPU lanza [NpuFailed] (se sigue en la CPU).
     */
    /**
     * 🔎 Diagnóstico de la NPU (mientras la ajustamos en los teléfonos reales): queda en la ventana «Creando…»
     * y debajo de la imagen. Dice el tipo de cada parte y si la UNet entiende lo que recibe (σ del ruido ≈1).
     */
    private fun npuDiag() {
        val info = ImageGenNpu.info()
        if (info.isBlank()) return
        progress("🔎 Diagnóstico de la NPU:\n$info", true)
        tech += "🔎 NPU: " + info.replace("\n", " · ")
    }

    /**
     * 🧩 Arma la imagen completa a partir de cuadros de SIDE×SIDE que se solapan a lo largo del lado largo.
     * canvas = foto (ARGB) de cw×ch, con el lado corto = SIDE · gen = crea un cuadro (RGB SIDE×SIDE → RGB SIDE×SIDE).
     * En las zonas solapadas los cuadros se mezclan con una transición suave (sin costuras).
     */
    private fun tileComposite(canvas: IntArray, cw: Int, ch: Int, side: Int,
                              gen: (index: Int, count: Int, rgb: ByteArray) -> ByteArray?): IntArray? {
        val vertical = ch >= cw
        val len = if (vertical) ch else cw
        val minOverlap = 96
        var n = if (len <= side) 1 else ceil((len - side).toDouble() / (side - minOverlap)).toInt() + 1
        // (siempre un número impar de cuadros: así hay uno CENTRAL que cubre entero el medio de la foto, donde suele
        //  estar la cara, y la cara sale de un solo cuadro en vez de mezclarse entre dos)
        if (n > 1 && n % 2 == 0) n++
        val mid = n / 2
        val pos = IntArray(n) { i -> if (n == 1) 0 else ((len - side).toLong() * i / (n - 1)).toInt() }
        val acc = FloatArray(cw * ch * 3)
        val wsum = FloatArray(cw * ch)
        val feather = 96f
        var cAlpha: FloatArray? = null
        var cRgb: ByteArray? = null
        for (i in 0 until n) {
            val tx = if (vertical) 0 else pos[i]; val ty = if (vertical) pos[i] else 0
            val rgb = ByteArray(side * side * 3)
            for (y in 0 until side) for (x in 0 until side) {
                val p = canvas[(ty + y) * cw + (tx + x)]
                val k = (y * side + x) * 3
                rgb[k] = (p shr 16).toByte(); rgb[k + 1] = (p shr 8).toByte(); rgb[k + 2] = p.toByte()
            }
            val out = gen(i, n, rgb) ?: return null
            if (out.size < side * side * 3) return null
            for (y in 0 until side) for (x in 0 until side) {
                val t = if (vertical) y else x
                // (rampa solo hacia los cuadros vecinos; en la orilla de la foto el peso es 1)
                var w = 1f
                if (i > 0) w = min(w, (t + 0.5f) / feather)
                if (i < n - 1) w = min(w, (side - t - 0.5f) / feather)
                w = w.coerceAtLeast(0.001f)
                val d = (ty + y) * cw + (tx + x)
                val k = (y * side + x) * 3
                if (n > 1 && i == mid) {
                    // (el cuadro central va ENCIMA de los demás y aparece de a poco desde sus bordes)
                    if (cAlpha == null) { cAlpha = FloatArray(cw * ch); cRgb = ByteArray(cw * ch * 3) }
                    val ramp = min(1f, min((t + 0.5f) / feather, (side - t - 0.5f) / feather))
                    cAlpha!![d] = ramp.coerceIn(0f, 1f)
                    cRgb!![d * 3] = out[k]; cRgb!![d * 3 + 1] = out[k + 1]; cRgb!![d * 3 + 2] = out[k + 2]
                    continue
                }
                acc[d * 3] += w * (out[k].toInt() and 0xFF)
                acc[d * 3 + 1] += w * (out[k + 1].toInt() and 0xFF)
                acc[d * 3 + 2] += w * (out[k + 2].toInt() and 0xFF)
                wsum[d] += w
            }
        }
        val ca = cAlpha; val cr = cRgb
        return IntArray(cw * ch) { d ->
            val w = wsum[d]
            var r: Float; var g: Float; var b: Float
            if (w > 1e-6f) { r = acc[d * 3] / w; g = acc[d * 3 + 1] / w; b = acc[d * 3 + 2] / w }
            else if (cr != null) { r = (cr[d * 3].toInt() and 0xFF).toFloat(); g = (cr[d * 3 + 1].toInt() and 0xFF).toFloat(); b = (cr[d * 3 + 2].toInt() and 0xFF).toFloat() }
            else { r = 0f; g = 0f; b = 0f }
            if (ca != null && cr != null && ca[d] > 0f) {
                val a = if (w > 1e-6f) ca[d] else 1f
                r += a * ((cr[d * 3].toInt() and 0xFF) - r); g += a * ((cr[d * 3 + 1].toInt() and 0xFF) - g); b += a * ((cr[d * 3 + 2].toInt() and 0xFF) - b)
            }
            (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
    }

    /** Recorta un cuadrado (x0,y0,size) de una imagen ARGB w×h y lo lleva a side×side (RGB), con suavizado bilineal. */
    private fun cropSquareRgb(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, size: Int, side: Int): ByteArray {
        val out = ByteArray(side * side * 3)
        val f = size.toFloat() / side
        for (y in 0 until side) for (x in 0 until side) {
            val sx = (x0 + (x + 0.5f) * f - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val sy = (y0 + (y + 0.5f) * f - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val ix = sx.toInt(); val iy = sy.toInt()
            val ax = sx - ix; val ay = sy - iy
            val ix1 = min(ix + 1, w - 1); val iy1 = min(iy + 1, h - 1)
            val p00 = px[iy * w + ix]; val p10 = px[iy * w + ix1]; val p01 = px[iy1 * w + ix]; val p11 = px[iy1 * w + ix1]
            for (c in 0 until 3) {
                val sh = 16 - c * 8
                val v = ((p00 shr sh) and 0xFF) * (1 - ax) * (1 - ay) + ((p10 shr sh) and 0xFF) * ax * (1 - ay) +
                    ((p01 shr sh) and 0xFF) * (1 - ax) * ay + ((p11 shr sh) and 0xFF) * ax * ay
                out[(y * side + x) * 3 + c] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * 🙂 Pega un cuadro side×side (RGB) en el cuadrado (x0,y0,size) de la imagen, achicándolo, con una transición suave
     * hacia los bordes (así no se nota dónde empieza el repaso).
     */
    private fun pasteSquareRgb(px: IntArray, w: Int, x0: Int, y0: Int, size: Int, patch: ByteArray, side: Int) {
        val feather = max(4f, size * 0.18f)
        val f = side.toFloat() / size
        for (y in 0 until size) for (x in 0 until size) {
            val edge = min(min(x, y), min(size - 1 - x, size - 1 - y)).toFloat()
            var a = (edge / feather).coerceIn(0f, 1f)
            a = a * a * (3 - 2 * a)   // (suave)
            if (a <= 0f) continue
            val sx = ((x + 0.5f) * f - 0.5f).coerceIn(0f, (side - 1).toFloat())
            val sy = ((y + 0.5f) * f - 0.5f).coerceIn(0f, (side - 1).toFloat())
            val ix = sx.toInt(); val iy = sy.toInt()
            val ax = sx - ix; val ay = sy - iy
            val ix1 = min(ix + 1, side - 1); val iy1 = min(iy + 1, side - 1)
            val d = (y0 + y) * w + (x0 + x)
            val old = px[d]
            var rgb = 0
            for (c in 0 until 3) {
                fun g(xx: Int, yy: Int) = (patch[(yy * side + xx) * 3 + c].toInt() and 0xFF).toFloat()
                val v = g(ix, iy) * (1 - ax) * (1 - ay) + g(ix1, iy) * ax * (1 - ay) + g(ix, iy1) * (1 - ax) * ay + g(ix1, iy1) * ax * ay
                val sh = 16 - c * 8
                val o = ((old shr sh) and 0xFF).toFloat()
                rgb = rgb or ((o + a * (v - o)).roundToInt().coerceIn(0, 255) shl sh)
            }
            px[d] = (0xFF shl 24) or rgb
        }
    }

    /**
     * 🙂 Dónde repasar la cara: el cuadrado alrededor de la cara más grande (con pelo, orejas y cuello), o null si
     * no hay cara, si es muy chica o si llena casi toda la imagen (ahí el repaso no aporta).
     * Si la cara es grande (selfie), el recuadro se ajusta más a la cara (1,3× en vez de 1,8×) para que igual se
     * agrande ≈1,7× al repasarla — antes una selfie quedaba siempre sin repaso.
     * face = la caja de la cara que encontró el detector, en la imagen de w×h.
     */
    private fun faceSquare(face: IntArray?, w: Int, h: Int, side: Int): IntArray? {
        if (face == null) return null
        val fwid = face[2] - face[0]; val fhei = face[3] - face[1]
        if (fwid <= 0 || fhei <= 0) return null
        val f = max(fwid, fhei).toFloat()
        var sizeF = f * 1.8f
        if (sizeF > side * 0.8f) sizeF = f * 1.3f            // (cara grande: recuadro más ajustado)
        if (sizeF > side * 0.85f) return null                 // (la cara llena casi toda la imagen)
        var size = sizeF.roundToInt()
        size = min(size, min(w, h))
        if (size < 48) return null
        val cx = (face[0] + face[2]) / 2f; val cy = (face[1] + face[3]) / 2f
        val x0 = (cx - size / 2f).roundToInt().coerceIn(0, w - size)
        val y0 = (cy - size / 2f).roundToInt().coerceIn(0, h - size)
        return intArrayOf(x0, y0, size)
    }

    /**
     * 🙂 Busca la cara más grande con el detector de caras de Google (ML Kit, dentro de la app).
     * Devuelve [izquierda, arriba, derecha, abajo] o null (sin cara, o el detector no está). Funciona muy bien con fotos;
     * con dibujos animados a veces no la encuentra (entonces no se hace el repaso).
     */
    private fun detectFace(px: IntArray, w: Int, h: Int): IntArray? = runCatching {
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val det = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build())
        try {
            val faces = com.google.android.gms.tasks.Tasks.await(
                det.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)), 15, java.util.concurrent.TimeUnit.SECONDS)
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        } finally {
            runCatching { det.close() }
            bmp.recycle()
        }
    }.getOrNull()

    /**
     * 🎎 Si el detector de Google no encontró una cara (pasa con los dibujos), se prueba el de anime.
     * Devuelve [izquierda, arriba, derecha, abajo] o null.
     */
    private fun detectAnimeFace(px: IntArray, w: Int, h: Int): IntArray? = runCatching {
        val det = NpuMemory.anime ?: context.assets.open("ai/animeface.bin").use { AnimeFaceDetector.load(it) }?.also { NpuMemory.anime = it }
        det?.detect(px, w, h)?.firstOrNull()?.let { intArrayOf(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
    }.getOrNull()

    /**
     * 🎲 Semilla (el ruido del que parte la IA): sale de la MISMA foto y del pedido, así la primera vez una foto da
     * siempre el mismo resultado (sirve para comparar ajustes de verdad); cada vez que se vuelve a pedir con esa foto
     * («otra»), sale distinta.
     */
    private fun npuSeed(px: IntArray, s: Step): Long {
        var h = 1125899906842597L
        val stepPx = max(1, px.size / 4096)
        var i = 0
        while (i < px.size) { h = 31 * h + (px[i] and 0xFFFFFF); i += stepPx }
        h = 31 * h + (s.prompt + "|" + s.what).hashCode()
        val n = synchronized(NpuMemory.tries) { val k = NpuMemory.tries[h] ?: 0; NpuMemory.tries[h] = k + 1; k }
        tech += "🎲 intento ${n + 1} con esta foto" + (if (n == 0) " (siempre sale igual la primera vez)" else "")
        return ((h xor (n * 0x9E3779B97F4A7C15uL.toLong())) and 0x7FFFFFFF)
    }

    private fun variationNpu(src: Bitmap, s: Step): Bitmap {
        val chip = ImageGenNpu.chip?.label.orEmpty()
        val files = ImageGenNpu.files(context, needControl = true) ?: run {
            val err = ImageGenNpu.download(context, { progress(it, true) }, isCancelled)
            checkStop()
            if (err != null) throw NeedsInfo("No pude descargar la IA de imágenes de la NPU (≈${String.format(java.util.Locale.US, "%.1f", ImageGenNpu.SIZE_MB / 1000.0).replace('.', ',')} GB). " +
                "Revisa tu internet y el espacio libre, y vuelve a pedírmelo: la descarga sigue donde quedó.\n(motivo: ${err.take(120)})")
            ImageGenNpu.files(context, needControl = true) ?: throw NeedsInfo("La IA de imágenes de la NPU se descargó incompleta. Vuelve a pedírmelo.")
        }
        checkStop()
        // 1) La foto con su lado corto = 512 (el largo hasta 1024): se trabaja en cuadros de 512×512 que se solapan,
        //    así se aprovecha toda la resolución de la NPU (antes la foto se achicaba entera a un solo cuadro:
        //    una vertical quedaba en 288×512 y la cara tenía pocos píxeles)
        val side = ImageGenNpu.SIDE
        val sc = min(side.toDouble() / min(src.width, src.height), (side * 2).toDouble() / max(src.width, src.height))
        val fw = (src.width * sc).roundToInt().coerceIn(16, side * 2); val fh = (src.height * sc).roundToInt().coerceIn(16, side * 2)
        val fitted = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        Canvas(fitted).apply {
            drawColor(Color.WHITE)   // (lo transparente queda blanco)
            drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, fw, fh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val fpx = IntArray(fw * fh); fitted.getPixels(fpx, 0, fw, 0, 0, fw, fh); fitted.recycle()
        // (si la foto es MUY alargada, el lado corto queda bajo 512: se completa estirando su orilla)
        val cw = max(fw, side); val ch = max(fh, side)
        val ox = (cw - fw) / 2; val oy = (ch - fh) / 2
        val canvasPx = IntArray(cw * ch) { d -> fpx[((d / cw) - oy).coerceIn(0, fh - 1) * fw + ((d % cw) - ox).coerceIn(0, fw - 1)] }
        // 🙂 Dónde está la cara (antes de cargar la IA: el detector usa la foto original)
        val faceBox = detectFace(fpx, fw, fh) ?: detectAnimeFace(fpx, fw, fh)
        val faceSq = faceSquare(faceBox, fw, fh, side)
        var faceNote = when {
            faceSq != null -> ""
            faceBox == null -> "sin repaso de la cara (no encontré una cara)"
            max(faceBox[2] - faceBox[0], faceBox[3] - faceBox[1]) * 1.8f < 48 -> "sin repaso de la cara (la cara es muy chica)"
            else -> "sin repaso de la cara (la cara ya ocupa casi toda la imagen)"
        }
        // 2) La NPU es una sola: el modelo del chat se pone en pausa mientras tanto
        runCatching { beforeBigImageAi?.invoke() }
        progress("⚡ Cargando la IA de imágenes en la NPU…", true)
        val prompt = buildPrompt(s, pro = true)
        val negative = variationNegative(true)
        val seed = npuSeed(fpx, s)
        val encoder = ImageGenNpu.encoderFile(context)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        var part = 1; var parts = 1
        var composite: IntArray? = null
        var outPx: IntArray? = null
        try {
            if (!ImageGenNpu.load(context, files)) throw NpuFailed(ImageGenNpu.lastError().ifBlank { "no se pudo cargar en la NPU" })
            if (!ImageGenNpu.hasControl()) throw NpuFailed("el modelo de la NPU no trae el ControlNet")
            checkStop()
            val watcher = Thread {
                while (running.get()) {
                    if (isCancelled()) { ImageGenNpu.cancel(); break }
                    progress("⚡ Creando la versión nueva en la NPU ($chip)" + (if (parts > 1) " · parte $part de $parts" else "") +
                        "… ${ImageGenNpu.progress()}%", true)
                    try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            try {
                composite = tileComposite(canvasPx, cw, ch, side) { i, n, rgb ->
                    part = i + 1; parts = n
                    if (isCancelled()) null
                    else {
                        // (parte de TU foto con TAESD: rehace el 50 % y sigue sus contornos al 1,0 → cambia el estilo
                        //  respetando la cara · la misma semilla en todos los cuadros para que combinen)
                        // (contornos solo en el 70 % inicial de los pasos: fijan la forma y después se sueltan, así las líneas
                        //  finas del dibujo —ceño, sombreado de la nariz— no salen como grietas en 3D)
                        // (fuerza 0,4 y guía 6: rehace menos y respeta más la foto —con 0,5 / 7,5 desaparecían manos y objetos
                        //  chicos y la expresión salía exagerada—; 25 pasos para que con 0,4 haga 10 y no quede a medias)
                        val r = ImageGenNpu.generate(prompt, negative, 25, 6f, seed, rgb, 1.0f, 0.4f, encoder, 0.7f)
                        if (r == null || r.first != side || r.second != side) null else r.third
                    }
                }
                // 3) Solo la parte de la foto, con sus colores
                val comp = composite
                if (comp != null && !isCancelled()) {
                    val op = IntArray(fw * fh) { k -> comp[(k / fw + oy) * cw + (k % fw + ox)] }
                    if (keepsColors(s)) runCatching { keepColors(op, fpx, fw, fh) }
                    // 4) 🙂 Repaso de la cara en grande (como «ADetailer»): la cara se recorta y se agranda a 512×512,
                    //    la NPU la repasa suave (parte de la imagen ya creada y sigue los contornos de la foto original)
                    //    y se pega de vuelta con transición suave → ojos con pupila, boca y bordes finos bien dibujados
                    if (faceSq != null) {
                        val (fx, fy, fs) = Triple(faceSq[0], faceSq[1], faceSq[2])
                        part = 1; parts = 1
                        progress("🙂 Repasando la cara en la NPU…", true)
                        val init = cropSquareRgb(op, fw, fh, fx, fy, fs, side)
                        val ctrl = cropSquareRgb(fpx, fw, fh, fx, fy, fs, side)
                        val fr = runCatching {
                            ImageGenNpu.generate(prompt + ", detailed face, detailed eyes, clear pupils, sharp facial features",
                                negative, 25, 6f, seed + 1, ctrl, 1.0f, 0.35f, encoder, 0.7f, init)
                        }.getOrNull()
                        if (fr != null && fr.first == side && fr.second == side && !isCancelled()) {
                            pasteSquareRgb(op, fw, fx, fy, fs, fr.third, side)
                            faceNote = "repaso de la cara ✓ (${fs}×$fs → ${side}×$side)"
                        } else faceNote = "repaso de la cara: no se pudo"
                    }
                    outPx = op
                }
            } finally {
                running.set(false); watcher.interrupt()
            }
        } finally {
            npuDiag()
            ImageGenNpu.free()   // la NPU queda libre para el modelo del chat
        }
        checkStop()
        val finalPx = outPx ?: throw NpuFailed(ImageGenNpu.lastError().ifBlank { "la NPU no devolvió ninguna imagen" })
        if (faceNote.isNotBlank()) tech += "🙂 $faceNote"
        val result = Bitmap.createBitmap(finalPx, fw, fh, Bitmap.Config.ARGB_8888)
        tech += "IA de imágenes ControlNet-Canny + Stable Diffusion 1.5 (Qualcomm AI Hub) en la NPU ($chip) + TAESD"
        summary += "🪄 Creé una versión nueva de la imagen" + (if (s.what.isNotBlank()) " (${s.what})" else "") +
            " en la NPU del teléfono · ${result.width}×${result.height}."
        summary += "ℹ️ Si no te gusta, pídeme «otra» y sale distinta. Para más resolución, dime «súbele la resolución»."
        return result
    }

    // =====================================================================
    // 🎨 IMAGEN NUEVA DESDE CERO (solo con la descripción, sin internet, en la CPU)
    //    · Mejor calidad: DreamShaper 8 LCM en 6 pasos (sin ControlNet: no hay foto que seguir).
    //    · Rápida: SDXS-512 en 1 paso.
    // =====================================================================
    /**
     * @param enPrompt lo que hay que dibujar, en inglés (la IA de imágenes entiende inglés)
     * @param what cómo lo pediste (para el resumen)
     * @param shape 0 = cuadrada · 1 = vertical (fondo de pantalla) · 2 = horizontal
     */
    fun createFromText(enPrompt: String, what: String, shape: Int = 0): Outcome {
        if (!ImageGenNative.available) throw NeedsInfo(
            "La IA para crear imágenes en el teléfono no vino incluida en esta compilación de la app." +
                ImageGenNative.loadError.takeIf { it.isNotBlank() }?.let { "\n(motivo: ${it.take(120)})" }.orEmpty())
        val ai = loadImageAi(useControl = false)
        // Tamaño de trabajo (múltiplos de 64, ≈512×512 que es lo que mejor sabe hacer)
        val (gw, gh) = when (shape) { 1 -> 384 to 640; 2 -> 640 to 384; else -> 512 to 512 }
        val prompt = listOf(enPrompt.trim().ifBlank { "a beautiful landscape" },
            "high quality, detailed, beautiful" + (if (ai.pro) ", masterpiece, sharp focus" else ""),
            "family friendly, fully clothed").joinToString(", ")
        val negative = "blurry, low quality, deformed, ugly, watermark, text, signature, nsfw, nude" +
            (if (ai.pro) ", bad anatomy, extra fingers, extra limbs, disfigured face, distorted face, double eyebrows, " +
                "deformed eyes, cross-eyed, duplicate, cropped" else "")
        val seed = (System.nanoTime() and 0x7FFFFFFF)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            val started = System.currentTimeMillis()
            while (running.get()) {
                if (isCancelled()) { ImageGenNative.cancel(); break }
                val secs = (System.currentTimeMillis() - started) / 1000
                progress("🎨 Creando tu imagen en el teléfono" + (if (ai.pro) " (mejor calidad: 2 a 5 min)" else "") + "… $secs", true)
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val out: ByteArray? = try {
            when {
                ai.oneStep -> ImageGenNative.nativeTxt2Img(prompt, negative, gw, gh, 1, 1f, seed, true, false)
                ai.pro -> ImageGenNative.nativeTxt2Img(prompt, negative, gw, gh, 6, 1.5f, seed, false, true)
                else -> ImageGenNative.nativeTxt2Img(prompt, negative, gw, gh, 20, 7f, seed, false, false)
            }
        } catch (e: UnsatisfiedLinkError) {
            // (la librería de imágenes quedó de una compilación anterior, sin «crear desde cero»)
            null
        } finally {
            running.set(false)
            watcher.interrupt()
            ImageGenNative.free()   // la RAM vuelve para el modelo del chat
        }
        checkStop()
        if (out == null || out.size < gw * gh * 3) {
            val why = ImageGenNative.lastError()
            throw NeedsInfo("La IA no pudo crear la imagen." + (if (why.isNotBlank()) "\n(motivo: ${why.take(160)})" else ""))
        }
        val outPx = IntArray(gw * gh) { i ->
            (0xFF shl 24) or ((out[i * 3].toInt() and 0xFF) shl 16) or ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or (out[i * 3 + 2].toInt() and 0xFF)
        }
        val result = Bitmap.createBitmap(outPx, gw, gh, Bitmap.Config.ARGB_8888)
        tech += techLabel(ai)
        summary += "🎨 Aquí está tu imagen" + (if (what.isNotBlank()) " de «$what»" else "") + ", creada en el teléfono sin internet · ${gw}×${gh}."
        summary += if (ai.pro) "ℹ️ Si no te gusta, pídeme «otra» y sale distinta. Para más resolución, dime «súbele la resolución»."
            else "ℹ️ Es la IA rápida (pequeña): si no te gusta, pídeme «otra». Para un resultado mucho mejor, dime «usa la IA de imágenes de mejor calidad»."
        return Outcome(result, false, summary.toList(), tech.toList())
    }

    /**
     * ⚡ Imagen NUEVA desde cero en la NPU (Stable Diffusion 1.5 oficial de Qualcomm, 20 pasos, en segundos).
     * Sale cuadrada (512×512). Si la NPU falla, lanza [NpuFailed] (la app sigue en la CPU).
     */
    fun createFromTextNpu(enPrompt: String, what: String, shape: Int = 0): Outcome {
        if (!ImageGenNpu.supported()) throw NpuFailed("este teléfono no tiene el modelo de la NPU")
        val chip = ImageGenNpu.chip?.label.orEmpty()
        val files = ImageGenNpu.files(context) ?: run {
            val err = ImageGenNpu.download(context, { progress(it, true) }, isCancelled)
            checkStop()
            if (err != null) throw NeedsInfo("No pude descargar la IA de imágenes de la NPU (≈${String.format(java.util.Locale.US, "%.1f", ImageGenNpu.SIZE_MB / 1000.0).replace('.', ',')} GB). " +
                "Revisa tu internet y el espacio libre, y vuelve a pedírmelo: la descarga sigue donde quedó.\n(motivo: ${err.take(120)})")
            ImageGenNpu.files(context) ?: throw NeedsInfo("La IA de imágenes de la NPU se descargó incompleta. Vuelve a pedírmelo.")
        }
        checkStop()
        // 🧠 La NPU es una sola: el modelo del chat (GenieX / LiteRT) se pone en pausa mientras tanto
        runCatching { beforeBigImageAi?.invoke() }
        progress("⚡ Cargando la IA de imágenes en la NPU…", true)
        val prompt = listOf(enPrompt.trim().ifBlank { "a beautiful landscape" },
            "high quality, highly detailed, beautiful, masterpiece, sharp focus, family friendly").joinToString(", ")
        val negative = "blurry, low quality, deformed, ugly, watermark, text, signature, nsfw, nude, naked, bad anatomy, " +
            "extra fingers, extra limbs, disfigured face, distorted face, double eyebrows, deformed eyes, cross-eyed, duplicate, cropped, worst quality"
        val seed = (System.nanoTime() and 0x7FFFFFFF)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val r: Triple<Int, Int, ByteArray>?
        try {
            if (!ImageGenNpu.load(context, files)) throw NpuFailed(ImageGenNpu.lastError().ifBlank { "no se pudo cargar en la NPU" })
            checkStop()
            val watcher = Thread {
                while (running.get()) {
                    if (isCancelled()) { ImageGenNpu.cancel(); break }
                    progress("⚡ Creando tu imagen en la NPU ($chip)… ${ImageGenNpu.progress()}%", true)
                    try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            try {
                r = ImageGenNpu.generate(prompt, negative, 20, 7.5f, seed)
            } finally {
                running.set(false); watcher.interrupt()
            }
        } finally {
            npuDiag()
            ImageGenNpu.free()   // la NPU queda libre para el modelo del chat
        }
        checkStop()
        if (r == null) throw NpuFailed(ImageGenNpu.lastError().ifBlank { "la NPU no devolvió ninguna imagen" })
        val (w, h, rgb) = r
        val px = IntArray(w * h) { i ->
            (0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        }
        val result = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        tech += "IA de imágenes Stable Diffusion 1.5 (Qualcomm AI Hub) en la NPU ($chip)"
        summary += "🎨 Aquí está tu imagen" + (if (what.isNotBlank()) " de «$what»" else "") + ", creada en la NPU del teléfono, sin internet · ${w}×${h}."
        if (shape != 0) summary += "ℹ️ En la NPU las imágenes salen cuadradas."
        summary += "ℹ️ Si no te gusta, pídeme «otra» y sale distinta. Para más resolución, dime «súbele la resolución»."
        return Outcome(result, false, summary.toList(), tech.toList())
    }

    private fun buildPrompt(s: Step, pro: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (s.prompt.isNotBlank()) parts += s.prompt
        // («beautiful» empujaba a la IA a hacer caras de mujer: ahora depende de si es hombre o mujer)
        parts += when (gender) {
            "male" -> "(man:1.2), male, masculine face, masculine body, high quality, detailed, handsome"
            "female" -> "(woman:1.2), female, feminine face, high quality, detailed, beautiful"
            else -> "high quality, detailed"
        }
        // (la de mejor calidad entiende más: se le pide que mantenga a la misma persona)
        if (pro) parts += "same person, same face, same pose, masterpiece, sharp focus"
        parts += "family friendly, fully clothed"
        return parts.joinToString(", ")
    }

    /**
     * ¿El estilo pedido mantiene los colores de la foto? (3D, anime, pintura, realista… sí · de noche, con nieve, atardecer,
     * blanco y negro, neón, otro color de pelo… no: ahí el cambio de color es justamente lo que pediste)
     */
    private fun keepsColors(s: Step): Boolean {
        val t = (s.prompt + " " + s.what).lowercase(java.util.Locale.ROOT)
        return !Regex("\\b(night|sunset|sunrise|golden hour|snow|winter|rain|autumn|fall leaves|christmas|halloween|outer space|galaxy|" +
            "fire|flames|neon|cyberpunk|vintage|film grain|sepia|black and white|monochrome|grayscale|pencil|sketch|pixel art|van gogh|" +
            "rainbow|blonde|red|blue|green|yellow|orange|purple|pink|brown|black|white|gr[ae]y|golden|silver|" +
            "noche|nieve|atardecer|amanecer|blanco y negro|sepia|rojo|roja|azul|verde|amarill[oa]|naranj[oa]|morad[oa]|rosad[oa]|" +
            "caf[eé]|negr[oa]|blanc[oa]|gris|dorad[oa]|plateado|color)\\b").containsMatchIn(t)
    }

    /**
     * 🎨 Le devuelve a la imagen nueva los colores de la foto: se cambia solo el color «de fondo» de cada zona (difuminado),
     * así quedan los detalles y las luces nuevas de la IA, pero con la paleta original (antes el pelo azul salía negro).
     * amount = cuánto color de la foto (0..1) · lum = cuánto de su brillo general.
     */
    private fun keepColors(gen: IntArray, src: IntArray, w: Int, h: Int, amount: Float = 0.8f, lum: Float = 0.3f) {
        val n = w * h
        val gy = FloatArray(n); val gcb = FloatArray(n); val gcr = FloatArray(n)
        val sy = FloatArray(n); val scb = FloatArray(n); val scr = FloatArray(n)
        fun split(p: IntArray, y: FloatArray, cb: FloatArray, cr: FloatArray) {
            for (i in 0 until n) {
                val r = ((p[i] shr 16) and 0xFF).toFloat(); val g = ((p[i] shr 8) and 0xFF).toFloat(); val b = (p[i] and 0xFF).toFloat()
                y[i] = 0.299f * r + 0.587f * g + 0.114f * b
                cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            }
        }
        split(gen, gy, gcb, gcr); split(src, sy, scb, scr)
        val rad = max(2, (max(w, h) * 0.02f).roundToInt())
        // (difuminado: 3 pasadas de caja ≈ gaussiano)
        fun blur(a: FloatArray): FloatArray {
            var cur = a.copyOf(); val tmp = FloatArray(n)
            repeat(3) {
                for (y in 0 until h) {
                    var acc = 0f; val row = y * w
                    for (x in -rad..rad) acc += cur[row + x.coerceIn(0, w - 1)]
                    for (x in 0 until w) {
                        tmp[row + x] = acc / (2 * rad + 1)
                        acc += cur[row + (x + rad + 1).coerceAtMost(w - 1)] - cur[row + (x - rad).coerceAtLeast(0)]
                    }
                }
                for (x in 0 until w) {
                    var acc = 0f
                    for (y in -rad..rad) acc += tmp[y.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) {
                        cur[y * w + x] = acc / (2 * rad + 1)
                        acc += tmp[(y + rad + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - rad).coerceAtLeast(0) * w + x]
                    }
                }
            }
            return cur
        }
        val bgy = blur(gy); val bsy = blur(sy)
        val bgcb = blur(gcb); val bscb = blur(scb)
        val bgcr = blur(gcr); val bscr = blur(scr)
        for (i in 0 until n) {
            val y = gy[i] + lum * (bsy[i] - bgy[i])
            val cb = gcb[i] + amount * (bscb[i] - bgcb[i]) - 128f
            val cr = gcr[i] + amount * (bscr[i] - bgcr[i]) - 128f
            val r = (y + 1.402f * cr).roundToInt().coerceIn(0, 255)
            val g = (y - 0.344136f * cb - 0.714136f * cr).roundToInt().coerceIn(0, 255)
            val b = (y + 1.772f * cb).roundToInt().coerceIn(0, 255)
            gen[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** ≈512×512 con la forma de la foto, lados múltiplos de 64 entre 320 y 768. */
    /** Tamaño de trabajo de la IA de mejor calidad en la CPU: lado corto 512, el largo hasta 1024 (múltiplos de 64). */
    private fun cpuProSize(w: Int, h: Int): Pair<Int, Int> {
        val short = min(w, h).toDouble(); val long = max(w, h).toDouble()
        val l = ((512.0 * long / short / 64.0).roundToInt() * 64).coerceIn(512, 1024)
        return if (w <= h) 512 to l else l to 512
    }

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
        /** Subir la resolución puede llegar hasta 8K (7680 px por lado) si hay memoria libre. */
        const val UPSCALE_MAX_SIDE = 7680
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

/** 🧠 Lo que la IA de imágenes de la NPU recuerda mientras la app está abierta. */
private object NpuMemory {
    /** Cuántas veces se pidió cada foto (para que «otra» salga distinta). */
    val tries = HashMap<Long, Int>()
    /** El detector de caras de anime, cargado una sola vez. */
    @Volatile var anime: AnimeFaceDetector? = null
}

