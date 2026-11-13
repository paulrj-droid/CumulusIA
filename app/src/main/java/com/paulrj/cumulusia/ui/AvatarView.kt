package com.paulrj.cumulusia.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 🗣️ Lo que la voz de la IA está haciendo, para que el avatar mueva la boca:
 *  - speaking: la IA está hablando (la boca se mueve).
 *  - listening: te está escuchando (los anillos cambian de color).
 *  - push(level): cuánto suena la voz en este momento (0..1). La voz descargada (.onnx) lo manda con el volumen real;
 *    la voz de Android avisa al empezar cada palabra (pulse). Si no llega nada, la boca sigue un ritmo de sílabas.
 */
object AvatarVoice {
    @Volatile var speaking = false
    @Volatile var listening = false
    @Volatile var level = 0f
    @Volatile var levelAt = 0L
    /** De dónde sale el movimiento de la boca: 0 = ritmo de sílabas, 1 = volumen real (voz descargada), 2 = aviso por palabra (voz de Android). */
    @Volatile var source = 0
    /** La pantalla de chat dice si la IA está hablando / escuchando (así no hay que tocar cada lugar donde cambia). */
    @Volatile var speakingSource: (() -> Boolean)? = null
    @Volatile var listeningSource: (() -> Boolean)? = null

    fun isSpeaking(): Boolean = speaking || (runCatching { speakingSource?.invoke() }.getOrNull() ?: false)
    fun isListening(): Boolean = listening || (runCatching { listeningSource?.invoke() }.getOrNull() ?: false)

    // (la voz descargada escribe el audio un poquito antes de que suene: cada nivel se guarda con la hora en que va a sonar)
    private val lvAt = LongArray(64); private val lv = FloatArray(64); private var lvN = 0

    fun push(l: Float) = pushAt(l, SystemClock.uptimeMillis())
    fun pulse() = push(0.85f)

    @Synchronized fun pushAt(l: Float, at: Long) {
        val i = lvN % 64; lvAt[i] = at; lv[i] = l.coerceIn(0f, 1f); lvN++
    }

    /** Pasa al nivel «actual» el último que ya debería estar sonando. */
    @Synchronized fun tick(now: Long) {
        var bestAt = -1L; var best = 0f
        val from = maxOf(0, lvN - 64)
        for (k in from until lvN) { val i = k % 64; if (lvAt[i] <= now && lvAt[i] > bestAt) { bestAt = lvAt[i]; best = lv[i] } }
        if (bestAt > levelAt) { level = best; levelAt = bestAt }
    }

    @Synchronized fun clear() { lvN = 0; level = 0f; levelAt = 0L }
}

/**
 * 🧑‍🚀 Avatar de la conversación por voz: un rostro de IA original, dibujado por la app (sin fotos ni licencias).
 * Piel azulada con sombras, ojos azules que parpadean solos y miran a los lados, circuitos en la frente y el cuello
 * que se encienden al hablar, anillos con puntos alrededor y una boca que se abre al ritmo de la voz.
 */
class AvatarView(context: Context) : View(context) {

    private val rnd = Random(7)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val head = Path()
    private val neck = Path()
    private val tmp = RectF()

    // ---- estado de la animación ----
    private var nextBlink = SystemClock.uptimeMillis() + 1500
    private var blinkStart = -1L
    private var doubleBlink = false
    private var gazeX = 0f; private var gazeY = 0f
    private var gazeTX = 0f; private var gazeTY = 0f
    private var nextGaze = 0L
    private var mouth = 0f
    private var glow = 0f
    private var lastFrame = 0L
    private var isSpeakingNow = false
    private var isListeningNow = false

    // ---- partículas y circuitos (fijos, generados una vez) ----
    private class Dot(var a: Float, val r: Float, val speed: Float, val size: Float)
    private val ringDots = List(46) { Dot(rnd.nextFloat() * 6.283f, 0.46f + rnd.nextFloat() * 0.16f, (rnd.nextFloat() - 0.5f) * 0.25f, 1.2f + rnd.nextFloat() * 2.2f) }
    private class Floater(var x: Float, var y: Float, val vy: Float, val size: Float, val tw: Float)
    private val floaters = List(40) { Floater(rnd.nextFloat(), rnd.nextFloat(), 0.008f + rnd.nextFloat() * 0.02f, 0.8f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f) }
    /** Trazos de circuito en la frente / cuello: listas de puntos (x, y en unidades de la cara, centro = 0). */
    private val traces: List<FloatArray> = buildList {
        repeat(14) {
            var x = (rnd.nextFloat() - 0.5f) * 0.42f
            var y = -0.40f + rnd.nextFloat() * 0.06f
            val pts = ArrayList<Float>(); pts += x; pts += y
            repeat(3 + rnd.nextInt(3)) {
                if (rnd.nextBoolean()) y += 0.03f + rnd.nextFloat() * 0.07f else x += (rnd.nextFloat() - 0.5f) * 0.10f
                pts += x; pts += y.coerceAtMost(-0.12f)
            }
            add(pts.toFloatArray())
        }
        repeat(8) {
            var x = (rnd.nextFloat() - 0.5f) * 0.22f
            var y = 0.52f + rnd.nextFloat() * 0.05f
            val pts = ArrayList<Float>(); pts += x; pts += y
            repeat(2 + rnd.nextInt(2)) {
                if (rnd.nextBoolean()) y += 0.05f + rnd.nextFloat() * 0.06f else x += (rnd.nextFloat() - 0.5f) * 0.10f
                pts += x; pts += y
            }
            add(pts.toFloatArray())
        }
    }


    /** 🔵 Circuitos que pediste en la cara (sienes, pómulos, junto a la boca) y en el cuello y los hombros. */
    private val faceTraces: List<FloatArray> = listOf(
        floatArrayOf(-0.206f, -0.231f, -0.206f, -0.153f, -0.252f, -0.148f),
        floatArrayOf(0.146f, -0.287f, 0.146f, -0.199f, 0.19f, -0.194f),
        floatArrayOf(0.218f, -0.259f, 0.218f, -0.144f),
        floatArrayOf(-0.252f, -0.083f, -0.238f, 0.028f, -0.181f, 0.028f),
        floatArrayOf(0.241f, -0.074f, 0.231f, 0.005f, 0.185f, 0.028f),
        floatArrayOf(-0.125f, 0.046f, -0.125f, 0.12f, -0.171f, 0.12f),
        floatArrayOf(-0.208f, 0.079f, -0.194f, 0.157f, -0.157f, 0.162f),
        floatArrayOf(-0.122f, 0.176f, -0.12f, 0.222f, -0.153f, 0.222f),
        floatArrayOf(0.076f, 0.032f, 0.083f, 0.088f, 0.119f, 0.088f),
        floatArrayOf(0.157f, 0.023f, 0.153f, 0.116f, 0.111f, 0.13f),
        floatArrayOf(0.218f, 0.079f, 0.199f, 0.162f, 0.148f, 0.176f),
        floatArrayOf(0.122f, 0.162f, 0.13f, 0.222f, 0.181f, 0.218f))
    private val neckTraces: List<FloatArray> = listOf(
        floatArrayOf(-0.125f, 0.37f, -0.134f, 0.463f, -0.176f, 0.472f),
        floatArrayOf(-0.065f, 0.421f, -0.079f, 0.574f),
        floatArrayOf(-0.009f, 0.417f, -0.009f, 0.481f),
        floatArrayOf(0.056f, 0.391f, 0.06f, 0.493f),
        floatArrayOf(0.148f, 0.37f, 0.093f, 0.375f, 0.097f, 0.505f),
        floatArrayOf(0.097f, 0.426f, 0.162f, 0.426f, 0.157f, 0.509f),
        floatArrayOf(-0.093f, 0.528f, -0.162f, 0.537f, -0.145f, 0.662f),
        floatArrayOf(-0.329f, 0.63f, -0.352f, 0.694f),
        floatArrayOf(-0.273f, 0.634f, -0.292f, 0.699f),
        floatArrayOf(-0.222f, 0.583f, -0.227f, 0.681f),
        floatArrayOf(0.153f, 0.597f, 0.167f, 0.681f),
        floatArrayOf(0.227f, 0.574f, 0.264f, 0.681f),
        floatArrayOf(0.31f, 0.611f, 0.329f, 0.69f))

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val t = now / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        update(now, t, dt)

        // ---- fondo: azul noche con un halo ----
        fill.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(Color.rgb(16, 34, 72), Color.rgb(6, 12, 30), Color.rgb(2, 4, 12)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null

        val s = min(w, h * 0.82f)                      // tamaño de la cara
        val cx = w / 2 + sin(t * 0.7f) * s * 0.004f    // (se mueve un poquito, como respirando)
        val cy = h * 0.47f + sin(t * 0.9f) * s * 0.006f

        drawFloaters(c, w, h, t, dt)
        drawRings(c, cx, cy, s, t, dt)
        drawNeck(c, cx, cy, s)
        drawEars(c, cx, cy, s)
        drawHead(c, cx, cy, s)
        drawCircuits(c, cx, cy, s, t)
        drawBrows(c, cx, cy, s)
        drawEye(c, cx - s * 0.115f, cy - s * 0.035f, s, false)
        drawEye(c, cx + s * 0.115f, cy - s * 0.035f, s, true)
        drawNose(c, cx, cy, s)
        drawMouth(c, cx, cy + s * 0.215f, s)

        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ animación
    private fun update(now: Long, t: Float, dt: Float) {
        // 👁️ Parpadeo: solo, cada 2 a 5 segundos (a veces dos seguidos)
        if (blinkStart < 0 && now >= nextBlink) { blinkStart = now; doubleBlink = rnd.nextFloat() < 0.18f }
        if (blinkStart >= 0 && now - blinkStart > (if (doubleBlink) 420 else 170)) {
            blinkStart = -1; nextBlink = now + 2000 + rnd.nextInt(3000)
        }
        // 👀 La mirada cambia de punto cada tanto
        if (now >= nextGaze) {
            gazeTX = (rnd.nextFloat() - 0.5f) * 0.9f; gazeTY = (rnd.nextFloat() - 0.5f) * 0.5f
            if (rnd.nextFloat() < 0.45f) { gazeTX = 0f; gazeTY = 0f }   // (casi siempre te mira a ti)
            nextGaze = now + 1200 + rnd.nextInt(2600)
        }
        val k = 1f - exp(-dt * 14f)
        gazeX += (gazeTX - gazeX) * k; gazeY += (gazeTY - gazeY) * k

        // 👄 Boca: con la voz real si llega; si no, un ritmo de sílabas mientras habla
        AvatarVoice.tick(now)
        var target = 0f
        val speaking = AvatarVoice.isSpeaking()
        val listening = !speaking && AvatarVoice.isListening()
        if (speaking) {
            val age = (now - AvatarVoice.levelAt).toFloat()
            val syl = 0.5f + 0.5f * abs(sin(t * 2f * PI.toFloat() * 3.6f)) * (0.7f + 0.3f * sin(t * 7.3f))
            val synthetic = 0.15f + 0.55f * syl * (0.6f + 0.4f * abs(sin(t * 1.3f)))
            target = when (AvatarVoice.source) {
                1 -> if (age < 220f) AvatarVoice.level * 1.15f else 0f                       // volumen real
                2 -> when {                                                                   // aviso por palabra
                    age < 480f -> AvatarVoice.level * (0.45f + 0.55f * syl) * exp(-age / 420f) * 1.2f
                    age < 1600f -> 0.04f
                    else -> synthetic                                                         // (el teléfono no avisa: sílabas)
                }
                else -> synthetic
            }
        }
        isSpeakingNow = speaking; isListeningNow = listening
        val attack = if (target > mouth) 1f - exp(-dt * 30f) else 1f - exp(-dt * 16f)
        mouth += (target.coerceIn(0f, 1f) - mouth) * attack
        val gTarget = if (speaking) 0.55f + mouth * 0.45f else if (listening) 0.35f else 0.18f
        glow += (gTarget - glow) * (1f - exp(-dt * 6f))
    }

    private fun blinkAmount(now: Long): Float {
        if (blinkStart < 0) return 0f
        val e = (now - blinkStart).toFloat()
        fun one(x: Float) = when { x < 0f -> 0f; x < 70f -> x / 70f; x < 170f -> 1f - (x - 70f) / 100f; else -> 0f }
        return if (doubleBlink) max(one(e), one(e - 230f)) else one(e)
    }

    // ------------------------------------------------------------------ dibujo
    private fun accent(a: Int): Int = if (isListeningNow && !isSpeakingNow)
        Color.argb(a, 90, 230, 255) else Color.argb(a, 70, 150, 255)

    private fun drawFloaters(c: Canvas, w: Float, h: Float, t: Float, dt: Float) {
        for (f in floaters) {
            f.y -= f.vy * dt; if (f.y < -0.02f) { f.y = 1.02f; f.x = rnd.nextFloat() }
            val a = (90 + 80 * sin(t * 2f + f.tw)).toInt().coerceIn(20, 200)
            fill.color = accent(a)
            c.drawCircle(f.x * w, f.y * h, f.size * resources.displayMetrics.density * 0.8f, fill)
        }
    }

    private fun drawRings(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, dt: Float) {
        stroke.strokeWidth = s * 0.0025f
        for (i in 0 until 3) {
            val r = s * (0.50f + i * 0.07f)
            stroke.color = accent((40 + glow * 90).toInt() - i * 8)
            val start = (t * (8f + i * 5f) * (if (i % 2 == 0) 1 else -1)) % 360f
            tmp.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(tmp, start, 250f - i * 40f, false, stroke)
        }
        for (d in ringDots) {
            d.a += d.speed * dt
            val r = s * d.r
            fill.color = accent((90 + glow * 140).toInt().coerceAtMost(240))
            c.drawCircle(cx + cos(d.a) * r, cy + sin(d.a) * r * 0.98f, d.size * s * 0.0022f, fill)
        }
    }

    private fun skinShader(cx: Float, cy: Float, s: Float): Shader = RadialGradient(cx - s * 0.03f, cy - s * 0.10f, s * 0.42f,
        intArrayOf(Color.rgb(226, 236, 248), Color.rgb(190, 208, 232), Color.rgb(118, 146, 186), Color.rgb(58, 82, 124)),
        floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)

    private fun drawNeck(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.165f, cy + s * 0.24f)
        path.cubicTo(cx - s * 0.175f, cy + s * 0.42f, cx - s * 0.205f, cy + s * 0.52f, cx - s * 0.40f, cy + s * 0.64f)
        path.lineTo(cx - s * 0.48f, cy + s * 0.95f)
        path.lineTo(cx + s * 0.48f, cy + s * 0.95f)
        path.lineTo(cx + s * 0.40f, cy + s * 0.64f)
        path.cubicTo(cx + s * 0.205f, cy + s * 0.52f, cx + s * 0.175f, cy + s * 0.42f, cx + s * 0.165f, cy + s * 0.24f)
        path.close()
        fill.shader = LinearGradient(cx - s * 0.24f, 0f, cx + s * 0.24f, 0f,
            intArrayOf(Color.rgb(44, 64, 102), Color.rgb(132, 158, 198), Color.rgb(44, 64, 102)), null, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // sombra bajo la mandíbula
        fill.shader = LinearGradient(0f, cy + s * 0.28f, 0f, cy + s * 0.38f, Color.argb(150, 10, 20, 45), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // los hombros se pierden en la oscuridad
        fill.shader = LinearGradient(0f, cy + s * 0.55f, 0f, cy + s * 0.85f, Color.TRANSPARENT, Color.rgb(3, 6, 16), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        neck.reset(); neck.addPath(path)
    }

    private fun drawEars(c: Canvas, cx: Float, cy: Float, s: Float) {
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * s * 0.265f; val ey = cy - s * 0.01f
            tmp.set(ex - s * 0.04f, ey - s * 0.075f, ex + s * 0.04f, ey + s * 0.075f)
            fill.shader = RadialGradient(ex, ey, s * 0.07f, Color.rgb(150, 172, 206), Color.rgb(60, 84, 126), Shader.TileMode.CLAMP)
            c.drawOval(tmp, fill); fill.shader = null
            stroke.color = Color.argb(110, 30, 50, 90); stroke.strokeWidth = s * 0.004f
            tmp.inset(s * 0.012f, s * 0.02f); c.drawArc(tmp, if (side < 0) 100f else -80f, 160f, false, stroke)
        }
    }

    private fun drawHead(c: Canvas, cx: Float, cy: Float, s: Float) {
        head.reset()
        val top = cy - s * 0.43f; val chin = cy + s * 0.34f
        head.moveTo(cx, top)
        // cráneo (más angosto arriba, no de huevo)
        head.cubicTo(cx + s * 0.17f, top, cx + s * 0.265f, cy - s * 0.30f, cx + s * 0.27f, cy - s * 0.10f)
        // pómulo
        head.cubicTo(cx + s * 0.275f, cy - s * 0.02f, cx + s * 0.27f, cy + s * 0.06f, cx + s * 0.255f, cy + s * 0.12f)
        // ángulo de la mandíbula (marcado)
        head.cubicTo(cx + s * 0.245f, cy + s * 0.17f, cx + s * 0.235f, cy + s * 0.205f, cx + s * 0.205f, cy + s * 0.235f)
        // línea de la mandíbula, recta hacia el mentón
        head.lineTo(cx + s * 0.078f, cy + s * 0.358f)
        // mentón cuadrado (la pera, un poco más larga)
        head.cubicTo(cx + s * 0.06f, cy + s * 0.385f, cx - s * 0.06f, cy + s * 0.385f, cx - s * 0.078f, cy + s * 0.358f)
        head.lineTo(cx - s * 0.205f, cy + s * 0.235f)
        head.cubicTo(cx - s * 0.235f, cy + s * 0.205f, cx - s * 0.245f, cy + s * 0.17f, cx - s * 0.255f, cy + s * 0.12f)
        head.cubicTo(cx - s * 0.27f, cy + s * 0.06f, cx - s * 0.275f, cy - s * 0.02f, cx - s * 0.27f, cy - s * 0.10f)
        head.cubicTo(cx - s * 0.265f, cy - s * 0.30f, cx - s * 0.17f, top, cx, top)
        head.close()
        fill.shader = skinShader(cx, cy, s); c.drawPath(head, fill)
        // volumen: sombras a los costados y bajo los pómulos
        fill.shader = LinearGradient(cx - s * 0.28f, 0f, cx + s * 0.28f, 0f,
            intArrayOf(Color.argb(120, 20, 36, 70), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 20, 36, 70)),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(head, fill)
        for (side in intArrayOf(-1, 1)) {
            fill.shader = RadialGradient(cx + side * s * 0.17f, cy + s * 0.12f, s * 0.09f, Color.argb(70, 30, 50, 95), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawPath(head, fill)
        }
        // 🙂 pómulos: una luz alargada sobre el hueso (inclinada hacia la oreja) y debajo un hundido con sombra
        c.save(); c.clipPath(head)
        for (side in intArrayOf(-1, 1)) {
            // hundido bajo el pómulo
            c.save()
            c.translate(cx + side * s * 0.195f, cy + s * 0.125f); c.rotate(side * -30f); c.scale(1f, 0.5f)
            fill.shader = RadialGradient(0f, 0f, s * 0.10f, Color.argb(48, 20, 36, 75), Color.argb(0, 20, 36, 75), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, s * 0.10f, fill)
            c.restore()
            // luz sobre el hueso del pómulo
            c.save()
            c.translate(cx + side * s * 0.165f, cy + s * 0.045f); c.rotate(side * -22f); c.scale(1f, 0.40f)
            fill.shader = RadialGradient(0f, 0f, s * 0.08f, Color.argb(80, 245, 250, 255), Color.argb(0, 245, 250, 255), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, s * 0.08f, fill)
            c.restore()
        }
        fill.shader = null
        c.restore()
        // mandíbula marcada: sombra a lo largo de la línea de la mandíbula y luz en el mentón
        c.save(); c.clipPath(head)
        stroke.color = Color.argb(45, 20, 34, 70); stroke.strokeWidth = s * 0.06f
        path.reset()
        path.moveTo(cx - s * 0.27f, cy + s * 0.10f); path.lineTo(cx - s * 0.215f, cy + s * 0.25f); path.lineTo(cx - s * 0.078f, cy + s * 0.38f)
        path.lineTo(cx + s * 0.078f, cy + s * 0.38f); path.lineTo(cx + s * 0.215f, cy + s * 0.25f); path.lineTo(cx + s * 0.27f, cy + s * 0.10f)
        c.drawPath(path, stroke)
        c.restore()
        // 🧔 silueta de la pera: luz en el centro de la pera y un borde de sombra suave alrededor
        c.save(); c.clipPath(head)
        fill.shader = RadialGradient(cx, cy + s * 0.325f, s * 0.07f,
            intArrayOf(Color.argb(65, 255, 255, 255), Color.TRANSPARENT, Color.argb(45, 20, 34, 70), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP)
        tmp.set(cx - s * 0.08f, cy + s * 0.255f, cx + s * 0.08f, cy + s * 0.40f); c.drawOval(tmp, fill); fill.shader = null
        c.restore()
        // brillo en la frente
        fill.shader = RadialGradient(cx - s * 0.04f, cy - s * 0.27f, s * 0.14f, Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(head, fill); fill.shader = null
        // contorno con brillo azul (más fuerte cuando habla)
        stroke.strokeWidth = s * 0.006f; stroke.color = accent((60 + glow * 150).toInt())
        c.drawPath(head, stroke)
    }

    private fun drawCircuits(c: Canvas, cx: Float, cy: Float, s: Float, t: Float) {
        c.save(); c.clipPath(head)
        stroke.strokeWidth = s * 0.0035f
        traces.forEachIndexed { i, p ->
            if (p[1] > 0.4f) return@forEachIndexed   // (los del cuello van fuera de la cabeza)
            val pulse = 0.5f + 0.5f * sin(t * 3f + i)
            stroke.color = accent((50 + glow * 120 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((90 + glow * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        c.save(); c.clipPath(neck)
        traces.forEachIndexed { i, p ->
            if (p[1] <= 0.4f) return@forEachIndexed
            val pulse = 0.5f + 0.5f * sin(t * 3f + i)
            stroke.color = accent((60 + glow * 140 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((100 + glow * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        // 🔵 los circuitos marcados: cara (dentro de la cabeza) y cuello/hombros (dentro del cuello)
        c.save(); c.clipPath(head); drawTraceList(c, cx, cy, s, t, faceTraces, 5); c.restore()
        c.save(); c.clipPath(neck); drawTraceList(c, cx, cy, s, t, neckTraces, 11); c.restore()
    }

    private fun drawTraceList(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, list: List<FloatArray>, phase: Int) {
        list.forEachIndexed { i, p ->
            val pulse = 0.5f + 0.5f * sin(t * 3f + i + phase)
            stroke.strokeWidth = s * 0.0035f
            stroke.color = accent((70 + glow * 140 * pulse).toInt().coerceAtMost(255))
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((110 + glow * 140 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
            fill.color = accent((80 + glow * 100 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[0] * s, cy + p[1] * s, s * 0.0035f, fill)
        }
    }

    private fun drawBrows(c: Canvas, cx: Float, cy: Float, s: Float) {
        val lift = mouth * s * 0.006f
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            val x0 = cx + side * s * 0.045f; val x1 = cx + side * s * 0.19f
            val y = cy - s * 0.10f - lift
            path.moveTo(x0, y + s * 0.006f)
            path.quadTo(cx + side * s * 0.12f, y - s * 0.03f, x1, y + s * 0.012f)
            stroke.color = Color.rgb(54, 76, 116); stroke.strokeWidth = s * 0.013f
            c.drawPath(path, stroke)
        }
    }

    private fun drawEye(c: Canvas, ex: Float, ey: Float, s: Float, right: Boolean) {
        val ew = s * 0.085f; val eh = s * 0.034f
        // cuenca (sombra)
        fill.shader = RadialGradient(ex, ey - eh * 0.2f, ew * 1.5f, Color.argb(110, 30, 50, 95), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(ex, ey, ew * 1.5f, fill); fill.shader = null
        // forma de almendra
        path.reset()
        val inner = if (right) -1 else 1   // (lagrimal hacia la nariz)
        path.moveTo(ex - ew, ey + inner * 0f)
        path.cubicTo(ex - ew * 0.5f, ey - eh * 1.25f, ex + ew * 0.5f, ey - eh * 1.25f, ex + ew, ey)
        path.cubicTo(ex + ew * 0.5f, ey + eh * 0.95f, ex - ew * 0.5f, ey + eh * 0.95f, ex - ew, ey)
        path.close()
        c.save(); c.clipPath(path)
        fill.color = Color.rgb(236, 242, 250); c.drawRect(ex - ew, ey - eh * 1.3f, ex + ew, ey + eh, fill)
        // iris y pupila (miran hacia gazeX/gazeY)
        val ix = ex + gazeX * ew * 0.35f; val iy = ey + gazeY * eh * 0.3f
        val ir = eh * 1.02f
        fill.shader = RadialGradient(ix, iy, ir, intArrayOf(Color.rgb(170, 220, 255), Color.rgb(60, 140, 230), Color.rgb(16, 52, 112)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(ix, iy, ir, fill); fill.shader = null
        fill.color = Color.rgb(6, 12, 26); c.drawCircle(ix, iy, ir * 0.42f, fill)
        fill.color = Color.argb(230, 255, 255, 255); c.drawCircle(ix - ir * 0.32f, iy - ir * 0.34f, ir * 0.18f, fill)
        fill.color = Color.argb(140, 255, 255, 255); c.drawCircle(ix + ir * 0.28f, iy + ir * 0.25f, ir * 0.08f, fill)
        // sombra del párpado sobre el ojo
        fill.shader = LinearGradient(0f, ey - eh * 1.2f, 0f, ey - eh * 0.2f, Color.argb(120, 20, 35, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(ex - ew, ey - eh * 1.3f, ex + ew, ey, fill); fill.shader = null
        // 👁️ párpado que baja al parpadear
        val b = blinkAmount(SystemClock.uptimeMillis())
        if (b > 0.01f) {
            fill.shader = LinearGradient(0f, ey - eh * 1.3f, 0f, ey + eh, Color.rgb(176, 194, 222), Color.rgb(132, 154, 192), Shader.TileMode.CLAMP)
            c.drawRect(ex - ew, ey - eh * 1.3f, ex + ew, ey - eh * 1.3f + eh * 2.3f * b, fill); fill.shader = null
        }
        c.restore()
        // línea de pestañas (sigue al párpado)
        val b2 = blinkAmount(SystemClock.uptimeMillis())
        path.reset()
        val ly = ey - eh * 1.25f * (1f - b2) + eh * 0.9f * b2
        path.moveTo(ex - ew, ey)
        path.cubicTo(ex - ew * 0.5f, ly, ex + ew * 0.5f, ly, ex + ew, ey)
        stroke.color = Color.rgb(18, 30, 58); stroke.strokeWidth = s * 0.006f
        c.drawPath(path, stroke)
        // pliegue del párpado
        path.reset()
        path.moveTo(ex - ew * 0.85f, ey - eh * 0.9f)
        path.quadTo(ex, ey - eh * 2.0f, ex + ew * 0.85f, ey - eh * 0.9f)
        stroke.color = Color.argb(90, 40, 60, 100); stroke.strokeWidth = s * 0.004f
        c.drawPath(path, stroke)
    }

    private fun drawNose(c: Canvas, cx: Float, cy: Float, s: Float) {
        // puente con brillo
        fill.shader = LinearGradient(cx - s * 0.025f, 0f, cx + s * 0.03f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(60, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        tmp.set(cx - s * 0.025f, cy - s * 0.03f, cx + s * 0.03f, cy + s * 0.11f); c.drawOval(tmp, fill)
        // sombra de la punta y aletas
        fill.shader = RadialGradient(cx, cy + s * 0.125f, s * 0.06f, Color.argb(110, 25, 42, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + s * 0.125f, s * 0.06f, fill); fill.shader = null
        stroke.color = Color.argb(140, 40, 60, 100); stroke.strokeWidth = s * 0.005f
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(cx + side * s * 0.022f, cy + s * 0.07f)
            path.quadTo(cx + side * s * 0.055f, cy + s * 0.11f, cx + side * s * 0.03f, cy + s * 0.135f)
            c.drawPath(path, stroke)
            fill.color = Color.argb(150, 16, 26, 52)
            tmp.set(cx + side * s * 0.024f - s * 0.011f, cy + s * 0.128f, cx + side * s * 0.024f + s * 0.011f, cy + s * 0.14f)
            c.drawOval(tmp, fill)
        }
    }

    private fun drawMouth(c: Canvas, mx: Float, my: Float, s: Float) {
        val mw = s * 0.085f
        val open = mouth * s * 0.045f
        // boca abierta (oscura, con un poco de dientes)
        if (open > s * 0.002f) {
            path.reset()
            path.moveTo(mx - mw * 0.92f, my)
            path.cubicTo(mx - mw * 0.5f, my - open * 0.25f, mx + mw * 0.5f, my - open * 0.25f, mx + mw * 0.92f, my)
            path.cubicTo(mx + mw * 0.5f, my + open * 1.1f, mx - mw * 0.5f, my + open * 1.1f, mx - mw * 0.92f, my)
            path.close()
            fill.color = Color.rgb(20, 12, 30); c.drawPath(path, fill)
            if (mouth > 0.25f) {
                c.save(); c.clipPath(path)
                fill.color = Color.argb(170, 225, 232, 245)
                tmp.set(mx - mw * 0.62f, my - open * 0.6f, mx + mw * 0.62f, my + open * 0.22f); c.drawOval(tmp, fill)
                // la lengua, abajo
                fill.color = Color.argb(150, 120, 60, 90)
                tmp.set(mx - mw * 0.5f, my + open * 0.55f, mx + mw * 0.5f, my + open * 1.4f); c.drawOval(tmp, fill)
                c.restore()
            }
        }
        // labio de arriba (con el arco de cupido)
        path.reset()
        path.moveTo(mx - mw, my)
        path.cubicTo(mx - mw * 0.6f, my - s * 0.016f, mx - mw * 0.25f, my - s * 0.026f, mx, my - s * 0.016f)
        path.cubicTo(mx + mw * 0.25f, my - s * 0.026f, mx + mw * 0.6f, my - s * 0.016f, mx + mw, my)
        path.cubicTo(mx + mw * 0.5f, my - open * 0.25f + s * 0.002f, mx - mw * 0.5f, my - open * 0.25f + s * 0.002f, mx - mw, my)
        path.close()
        fill.shader = LinearGradient(0f, my - s * 0.026f, 0f, my, Color.rgb(120, 130, 172), Color.rgb(78, 86, 128), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // labio de abajo (más lleno, baja con la mandíbula)
        val ly = my + open
        path.reset()
        path.moveTo(mx - mw * 0.95f, my + open * 0.05f)
        path.cubicTo(mx - mw * 0.5f, ly + open * 0.1f, mx + mw * 0.5f, ly + open * 0.1f, mx + mw * 0.95f, my + open * 0.05f)
        path.cubicTo(mx + mw * 0.55f, ly + s * 0.03f, mx - mw * 0.55f, ly + s * 0.03f, mx - mw * 0.95f, my + open * 0.05f)
        path.close()
        fill.shader = LinearGradient(0f, ly, 0f, ly + s * 0.03f, Color.rgb(132, 140, 186), Color.rgb(84, 92, 136), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        // brillo del labio de abajo y comisuras
        fill.color = Color.argb(70, 255, 255, 255)
        tmp.set(mx - mw * 0.3f, ly + s * 0.008f, mx + mw * 0.3f, ly + s * 0.016f); c.drawOval(tmp, fill)
        stroke.color = Color.argb(120, 30, 40, 70); stroke.strokeWidth = s * 0.004f
        c.drawPoint(mx - mw, my, stroke); c.drawPoint(mx + mw, my, stroke)
        // sombra bajo el labio (el mentón)
        tmp.set(mx - s * 0.045f, ly + s * 0.022f, mx + s * 0.045f, ly + s * 0.045f)
        fill.shader = RadialGradient(mx, ly + s * 0.033f, s * 0.04f, Color.argb(40, 25, 40, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawOval(tmp, fill); fill.shader = null
    }
}
