package com.paulrj.cumulusia.ai

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 🎎 Detector de caras de DIBUJOS (anime / manga), para el repaso de la cara de la IA de imágenes cuando el detector
 * de Google (hecho para fotos) no encuentra ninguna.
 *
 * Es el clasificador «lbpcascade_animeface» (© 2011 nagadomi, licencia MIT — assets/ai/ANIMEFACE_LICENSE.txt),
 * evaluado con el mismo método que OpenCV (cascada LBP + pirámide de escalas + agrupación de ventanas), escrito aquí
 * sin librerías. Los datos van en assets/ai/animeface.bin (44 KB).
 * Funciona muy bien con el estilo de anime habitual (ojos grandes); con estilos muy distintos puede no encontrarla.
 */
class AnimeFaceDetector private constructor(
    private val winW: Int, private val winH: Int,
    private val feats: IntArray,              // 4 por característica: x, y, ancho, alto (de cada bloque de la grilla 3×3)
    private val stageThr: FloatArray, private val stageStart: IntArray, private val stageCount: IntArray,
    private val weakFeat: IntArray, private val weakSubset: IntArray, private val weakLeaf: FloatArray
) {
    companion object {
        /** Lee los datos («CLBP0001»). null si el archivo no es válido. */
        fun load(input: InputStream): AnimeFaceDetector? = runCatching {
            val bytes = input.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8); b.get(magic)
            if (String(magic, Charsets.US_ASCII) != "CLBP0001") return null
            val w = b.int; val h = b.int; val nf = b.int; val ns = b.int
            val feats = IntArray(nf * 4) { b.int }
            val thr = FloatArray(ns); val start = IntArray(ns); val count = IntArray(ns)
            val wf = ArrayList<Int>(); val ws = ArrayList<Int>(); val wl = ArrayList<Float>()
            for (s in 0 until ns) {
                thr[s] = b.float
                val n = b.int
                start[s] = wf.size; count[s] = n
                repeat(n) {
                    wf += b.int
                    repeat(8) { ws += b.int }
                    wl += b.float; wl += b.float
                }
            }
            AnimeFaceDetector(w, h, feats, thr, start, count, wf.toIntArray(), ws.toIntArray(), wl.toFloatArray())
        }.getOrNull()
    }

    /**
     * Busca caras en una imagen ARGB de w×h. Devuelve las cajas [x, y, ancho, alto] (con cuántas ventanas la votaron),
     * de la más votada a la menos.
     */
    fun detect(px: IntArray, w: Int, h: Int, scaleFactor: Float = 1.1f, minNeighbors: Int = 3, minSize: Int = 24): List<IntArray> {
        // gris + ecualización del histograma (como OpenCV antes de buscar)
        val gray = IntArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f).roundToInt().coerceIn(0, 255)
        }
        equalize(gray)
        val found = ArrayList<IntArray>()
        var f = 1f
        while (true) {
            val sw = (w / f).roundToInt(); val sh = (h / f).roundToInt()
            if (sw < winW || sh < winH) break
            if (winW * f >= minSize) {
                val img = if (f == 1f) gray else resize(gray, w, h, sw, sh)
                val ii = integral(img, sw, sh)
                val step = if (f > 2f) 1 else 2
                var y = 0
                while (y <= sh - winH) {
                    var x = 0
                    while (x <= sw - winW) {
                        if (passes(ii, sw + 1, x, y))
                            found += intArrayOf((x * f).roundToInt(), (y * f).roundToInt(), (winW * f).roundToInt(), (winH * f).roundToInt())
                        x += step
                    }
                    y += step
                }
            }
            f *= scaleFactor
        }
        return group(found, minNeighbors)
    }

    private fun passes(ii: IntArray, stride: Int, x: Int, y: Int): Boolean {
        for (s in stageThr.indices) {
            var sum = 0f
            val st = stageStart[s]
            for (k in st until st + stageCount[s]) {
                val fi = weakFeat[k] * 4
                val fx = x + feats[fi]; val fy = y + feats[fi + 1]; val fw = feats[fi + 2]; val fh = feats[fi + 3]
                fun bs(bx: Int, by: Int): Int {
                    val x0 = fx + bx * fw; val y0 = fy + by * fh
                    return ii[(y0 + fh) * stride + x0 + fw] - ii[y0 * stride + x0 + fw] - ii[(y0 + fh) * stride + x0] + ii[y0 * stride + x0]
                }
                val c = bs(1, 1)
                val code = (if (bs(0, 0) >= c) 128 else 0) or (if (bs(1, 0) >= c) 64 else 0) or (if (bs(2, 0) >= c) 32 else 0) or
                    (if (bs(2, 1) >= c) 16 else 0) or (if (bs(2, 2) >= c) 8 else 0) or (if (bs(1, 2) >= c) 4 else 0) or
                    (if (bs(0, 2) >= c) 2 else 0) or (if (bs(0, 1) >= c) 1 else 0)
                val bit = (weakSubset[k * 8 + (code shr 5)] ushr (code and 31)) and 1
                sum += if (bit == 1) weakLeaf[k * 2] else weakLeaf[k * 2 + 1]
            }
            if (sum < stageThr[s] - 1e-6f) return false
        }
        return true
    }

    private fun integral(img: IntArray, w: Int, h: Int): IntArray {
        val s = w + 1
        val ii = IntArray(s * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += img[y * w + x]
                ii[(y + 1) * s + x + 1] = ii[y * s + x + 1] + row
            }
        }
        return ii
    }

    private fun equalize(g: IntArray) {
        val hist = IntArray(256); for (v in g) hist[v]++
        var acc = 0; var first = -1
        val cdf = IntArray(256) { i -> acc += hist[i]; if (first < 0 && acc > 0) first = acc; acc }
        val total = g.size
        val lut = IntArray(256) { i -> (((cdf[i] - first).toFloat() * 255f / max(1, total - first)).roundToInt()).coerceIn(0, 255) }
        for (i in g.indices) g[i] = lut[g[i]]
    }

    /** Achica con promedio de área (suave, como el suavizado al reducir). */
    private fun resize(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val out = IntArray(nw * nh)
        val fx = w.toFloat() / nw; val fy = h.toFloat() / nh
        for (y in 0 until nh) {
            val y0 = (y * fy).toInt(); val y1 = min(h, max(y0 + 1, ((y + 1) * fy).toInt()))
            for (x in 0 until nw) {
                val x0 = (x * fx).toInt(); val x1 = min(w, max(x0 + 1, ((x + 1) * fx).toInt()))
                var s = 0; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { s += src[yy * w + xx]; n++ }
                out[y * nw + x] = if (n > 0) s / n else 0
            }
        }
        return out
    }

    /** Junta las ventanas parecidas (como groupRectangles de OpenCV): queda una caja por cara. */
    private fun group(rects: List<IntArray>, thr: Int, eps: Float = 0.2f): List<IntArray> {
        val n = rects.size
        val lab = IntArray(n) { it }
        fun find(a0: Int): Int { var a = a0; while (lab[a] != a) { lab[a] = lab[lab[a]]; a = lab[a] }; return a }
        fun similar(a: IntArray, b: IntArray): Boolean {
            val d = eps * (min(a[2], b[2]) + min(a[3], b[3])) * 0.5f
            return Math.abs(a[0] - b[0]) <= d && Math.abs(a[1] - b[1]) <= d &&
                Math.abs(a[0] + a[2] - b[0] - b[2]) <= d && Math.abs(a[1] + a[3] - b[1] - b[3]) <= d
        }
        for (i in 0 until n) for (j in i + 1 until n) if (similar(rects[i], rects[j])) lab[find(i)] = find(j)
        val clusters = HashMap<Int, MutableList<IntArray>>()
        for (i in 0 until n) clusters.getOrPut(find(i)) { ArrayList() } += rects[i]
        val out = ArrayList<IntArray>()   // [x, y, ancho, alto, votos]
        for (v in clusters.values) {
            if (v.size <= thr) continue
            val m = IntArray(4) { k -> (v.sumOf { it[k] }.toFloat() / v.size).roundToInt() }
            out += intArrayOf(m[0], m[1], m[2], m[3], v.size)
        }
        // (fuera las que quedan dentro de otra más votada)
        val res = out.filter { r ->
            out.none { r2 ->
                r2 !== r && run {
                    val dx = (r2[2] * eps).toInt(); val dy = (r2[3] * eps).toInt()
                    r[0] >= r2[0] - dx && r[1] >= r2[1] - dy && r[0] + r[2] <= r2[0] + r2[2] + dx && r[1] + r[3] <= r2[1] + r2[3] + dy &&
                        (r2[4] > max(3, r[4]) || r[4] < 3)
                }
            }
        }
        return res.sortedByDescending { it[4] }
    }
}
