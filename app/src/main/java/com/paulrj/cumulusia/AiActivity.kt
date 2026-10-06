package com.paulrj.cumulusia

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ✦ ACTIVIDAD DE LA IA: lo que está haciendo en este momento.
 *
 * - En el chat: estrella morada que gira + «Pensando...» / «Analizando...» + la flecha ›
 * - Al tocar la burbuja se abre una ventana con cada paso: lo que busca, las páginas que lee,
 *   el motor y los ajustes que aplica, los archivos que analiza, los reintentos, etc.
 *   La ventana se va actualizando sola mientras la IA trabaja.
 */
object AiActivity {
    data class Step(val atMs: Long, val text: String, val detail: String = "")

    /** Morado claro de la estrella. */
    val PURPLE: Int = Color.parseColor("#C4A7FF")

    const val THINKING = "Pensando..."
    const val ANALYZING = "Analizando..."
    const val CREATING = "Creando..."

    private val main = Handler(Looper.getMainLooper())
    private val steps = CopyOnWriteArrayList<Step>()

    /** Posición del mensaje «Pensando» en el chat (-1 = ninguno, por ejemplo en voz a voz). */
    @Volatile var activeIndex = -1
        private set
    @Volatile var running = false
        private set
    @Volatile var label = THINKING
        private set
    @Volatile private var startedAt = 0L
    @Volatile private var totalMs = 0L

    // 🏃 Velocidad con la que la IA está escribiendo (para que el monito corra más rápido o más lento)
    @Volatile private var tokPerSec = 0f
    @Volatile private var lastChars = 0
    @Volatile private var lastCharsAt = 0L
    @Volatile private var runPhaseValue = 0f
    @Volatile private var runPhaseAt = 0L

    /** MainActivity lo usa para redibujar la burbuja cuando cambia lo que se está haciendo. */
    @Volatile var onBubbleChange: ((Int) -> Unit)? = null

    /** 🖥️ "web" / 📱 "app": el pedido es una app que se puede ver en la vista previa o la maqueta (null = no). */
    @Volatile var previewKind: String? = null

    /** 📱 true cuando la parte visual de la app (diseño XML) ya está escrita: recién ahí se invita a ver la maqueta. */
    @Volatile var previewReady = false
        private set

    fun markPreviewReady(text: String) {
        if (previewReady) return
        previewReady = true
        log(text)
    }

    /** Abre la vista previa web ("web") o la maqueta de la app ("app"). Lo pone MainActivity. */
    @Volatile var onOpenPreview: ((String) -> Unit)? = null

    private var panelRefresh: (() -> Unit)? = null
    private val bubbleTick = Runnable {
        val idx = activeIndex
        if (running && idx >= 0) runCatching { onBubbleChange?.invoke(idx) }
    }

    /** Empieza una nueva actividad (se borran los pasos anteriores). */
    fun start(index: Int, startLabel: String = THINKING) {
        steps.clear()
        startedAt = SystemClock.elapsedRealtime()
        totalMs = 0L
        tokPerSec = 0f
        lastChars = 0
        lastCharsAt = 0L
        previewKind = null
        previewReady = false
        activeIndex = index
        label = startLabel
        running = true
        notifyPanel()
    }

    fun isWorking(position: Int): Boolean = running && position >= 0 && position == activeIndex

    /** Cambia la palabra de la burbuja («Pensando...» → «Analizando...»). */
    fun setLabel(newLabel: String) {
        if (label == newLabel) return
        label = newLabel
        notifyBubble()
        notifyPanel()
    }

    /** La IA está analizando algo (código, foto, archivo, video, noticias, internet...). */
    fun analyzing(text: String? = null, detail: String = "") {
        if (label == THINKING) setLabel(ANALYZING)
        if (text != null) log(text, detail)
    }

    /** Agrega un paso a la ventana de actividad. */
    fun log(text: String, detail: String = "") {
        if (text.isBlank()) return
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        // No se repite el mismo paso dos veces seguidas (por ejemplo «Armando el video... 40%» → solo se actualiza)
        val last = steps.lastOrNull()
        val step = Step(SystemClock.elapsedRealtime() - startedAt, text.trim(), detail.trim())
        if (last != null && sameKind(last.text, step.text)) steps[steps.lastIndex] = step else steps.add(step)
        while (steps.size > 200) steps.removeAt(0)
        notifyBubble()
        notifyPanel()
    }

    /** Termina la actividad: la burbuja vuelve a ser la respuesta normal. */
    fun finish(note: String = "Respuesta lista") {
        if (!running) return
        totalMs = SystemClock.elapsedRealtime() - startedAt
        log("✅ $note", "Tiempo total: " + seconds(totalMs))
        running = false
        activeIndex = -1
        previewKind = null
        previewReady = false
        notifyPanel()
    }

    /** Último paso (se muestra en chico debajo de «Pensando...»). */
    fun lastStep(): String? = steps.lastOrNull()?.text

    /**
     * 🏃 El motor avisa cuánto texto lleva escrito; con eso se mide la velocidad (≈ tokens por segundo).
     * Si empieza otra respuesta (el texto vuelve a ser corto), se mide desde cero sin perder la velocidad anterior.
     */
    fun reportGeneratedChars(total: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastCharsAt != 0L && total > lastChars) {
            val dt = (now - lastCharsAt).coerceAtLeast(1L)
            val inst = (total - lastChars) / 4f * 1000f / dt   // ~4 letras por token
            tokPerSec = if (tokPerSec <= 0f) inst else tokPerSec * 0.7f + inst * 0.3f
        }
        lastChars = total
        lastCharsAt = now
    }

    /** Velocidad aproximada en tokens por segundo (0 si todavía no escribe). */
    fun tokensPerSecond(): Float =
        if (running && lastCharsAt != 0L && SystemClock.elapsedRealtime() - lastCharsAt < 2500) tokPerSec else 0f

    /**
     * 🏃 Qué tan rápido corre el monito (0 = quieto, 1 = a toda velocidad).
     * Mientras lee o espera al motor trota suave; mientras escribe, corre según los tokens por segundo.
     */
    fun pace(): Float {
        if (!running) return 0f
        val tps = tokensPerSecond()
        if (tps <= 0f) return 0.1f   // todavía lee o espera al motor: patitas lentas
        return (0.15f + (tps / 25f).coerceIn(0f, 1f) * 0.85f).coerceIn(0.1f, 1f)
    }

    /** Fase de la carrera (0..1). Avanza con el reloj según la velocidad, así sigue suave aunque el chat se redibuje. */
    fun runPhase(): Float {
        val now = SystemClock.uptimeMillis()
        val dt = if (runPhaseAt == 0L) 0L else (now - runPhaseAt).coerceIn(0L, 100L)
        runPhaseAt = now
        val strideMs = 1400f - 1150f * pace()   // de 1,3 s por paso (piensa lento) a 0,25 s (piensa muy rápido)
        runPhaseValue = (runPhaseValue + dt / strideMs) % 1f
        return runPhaseValue
    }

    private fun sameKind(a: String, b: String): Boolean {
        // Mismo texto sin los números/porcentajes del final = mismo paso que avanza
        val norm = { s: String -> s.replace(Regex("[\\d%().…/\\s]+$"), "").trim() }
        return a == b || (norm(a).length > 6 && norm(a) == norm(b))
    }

    private fun seconds(ms: Long): String = String.format(Locale("es", "ES"), "%.1f s", ms / 1000.0)

    private fun notifyBubble() {
        main.removeCallbacks(bubbleTick)
        main.postDelayed(bubbleTick, 120)
    }

    private fun notifyPanel() {
        main.post { panelRefresh?.invoke() }
    }

    // ========================================================================
    // 🪟 VENTANA DE ACTIVIDAD (se abre con la flecha ›)
    // ========================================================================
    fun showPanel(context: Context) {
        val d = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161618"))
                cornerRadii = floatArrayOf(22 * d, 22 * d, 22 * d, 22 * d, 0f, 0f, 0f, 0f)
                setStroke(dp(1), Color.parseColor("#2C2C30"))
            }
        }
        // Barra para indicar que es una ventana que se cierra
        root.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * d; setColor(Color.parseColor("#48484A")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
        })
        // Encabezado: ✦ + título + ✕
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val star = ImageView(context).apply {
            setImageDrawable(RunnerDrawable { running })
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
        }
        val title = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#9A9A9A"))
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(star); header.addView(title); header.addView(close)
        root.addView(header)
        val subtitle = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(subtitle)
        // 🖥️/📱 Si está armando una app: botón para ver el avance en la vista previa o la maqueta
        val previewBtn = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#3A2E5C")); setStroke(dp(1), PURPLE) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            setOnClickListener { previewKind?.let { k -> runCatching { onOpenPreview?.invoke(k) } } }
        }
        root.addView(previewBtn)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(list)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        fun render() {
            title.text = if (running) label else "Actividad de la IA"
            subtitle.text = if (running) "Lo que la IA está haciendo ahora, paso a paso" +
                tokensPerSecond().let { if (it > 0f) " · escribe ≈" + it.toInt() + " tok/s" else "" }
                else "Lo que hizo la IA en la última respuesta" + (if (totalMs > 0) " · " + seconds(totalMs) else "")
            // Solo cuando ya está la parte visual de la app (la vista previa web se ofrece al terminar)
            val kind = if (running && previewReady && previewKind == "app") previewKind else null
            previewBtn.visibility = if (kind != null) View.VISIBLE else View.GONE
            previewBtn.text = "📱 Ver la maqueta de tu app  ›"
            list.removeAllViews()
            val all = steps.toList()
            if (all.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "Todavía no hay pasos para mostrar."
                    textSize = 14f
                    setTextColor(Color.parseColor("#8E8E93"))
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            all.forEachIndexed { i, s ->
                val isLast = i == all.lastIndex
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(7), 0, dp(7))
                }
                // Punto de la línea de tiempo (morado en el paso actual)
                row.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isLast && running) PURPLE else Color.parseColor("#48484A"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(7); marginEnd = dp(12) }
                })
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(context).apply {
                    text = s.text
                    textSize = 14.5f
                    setTextColor(if (isLast && running) Color.WHITE else Color.parseColor("#D1D1D6"))
                    setLineSpacing(2 * d, 1f)
                    setTextIsSelectable(true)
                })
                if (s.detail.isNotBlank()) col.addView(TextView(context).apply {
                    text = s.detail
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#8E8E93"))
                    setLineSpacing(2 * d, 1f)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(Color.parseColor("#202024")) }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(5) }
                    setTextIsSelectable(true)
                })
                row.addView(col)
                row.addView(TextView(context).apply {
                    text = seconds(s.atMs)
                    textSize = 11f
                    setTextColor(Color.parseColor("#6E6E73"))
                    setPadding(dp(8), dp(2), 0, 0)
                })
                list.addView(row)
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        render()
        panelRefresh = { if (dialog.isShowing) render() }
        dialog.setOnDismissListener { panelRefresh = null }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val h = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }
}

/**
 * ✦ Estrella morada (una grande y una chica). Mientras la IA trabaja da vueltas en círculo.
 * El giro se calcula con el reloj, así sigue suave aunque el chat se redibuje.
 */
class SparkleDrawable(color: Int, private val spinning: () -> Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()
    private val periodMs = 1600L

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f
        val spin = spinning()
        canvas.save()
        if (spin) canvas.rotate((SystemClock.uptimeMillis() % periodMs) * 360f / periodMs, cx, cy)
        // Grande (abajo a la izquierda) y chica (arriba a la derecha), como ✦ con su destello
        star(canvas, cx - size * 0.07f, cy + size * 0.07f, size * 0.38f)
        star(canvas, cx + size * 0.28f, cy - size * 0.26f, size * 0.11f)
        canvas.restore()
        if (spin) invalidateSelf()   // siguiente cuadro de la animación
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        val k = r * 0.16f   // qué tan curvas son las puntas
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + k, y - k, x + r, y)
        path.quadTo(x + k, y + k, x, y + r)
        path.quadTo(x - k, y + k, x - r, y)
        path.quadTo(x - k, y - k, x, y - r)
        path.close()
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 🏃 Monito blanco (el del dibujo): cabeza redonda, ojitos que miran siempre a la palabra «Pensando...» y dos patitas.
 * Lo ÚNICO que se mueve son las patitas: más rápido cuando la IA piensa rápido y más lento cuando piensa lento.
 */
class RunnerDrawable(private val running: () -> Boolean) : Drawable() {
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B1B1F"); style = Paint.Style.FILL }
    private val oval = android.graphics.RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val left = b.left + (w - size) / 2f
        val top = b.top + (h - size) / 2f
        val active = running()
        // La velocidad de las patitas sale de runPhase(), que avanza según qué tan rápido piensa la IA
        val ang = if (active) (AiActivity.runPhase() * 2.0 * Math.PI).toFloat() else 0f
        val swing = kotlin.math.sin(ang)
        val r = size * 0.27f
        val cx = left + size * 0.50f
        val cy = top + size * 0.36f   // la cabeza y los ojos quedan quietos

        // Patitas: se mueven hacia adelante y atrás, una contra la otra (siempre el mismo largo de paso)
        leg.strokeWidth = size * 0.11f
        val hipY = cy + r * 0.75f
        val legLen = size * 0.28f
        val maxDeg = if (active) 30.0 else 0.0
        for (i in 0..1) {
            val dir = if (i == 0) -1f else 1f
            val hipX = cx + if (i == 0) -r * 0.35f else r * 0.25f
            val a = Math.toRadians(dir * swing * maxDeg).toFloat()
            val footX = hipX + kotlin.math.sin(a) * legLen
            val footY = hipY + kotlin.math.cos(a) * legLen
            canvas.drawLine(hipX, hipY, footX, footY, leg)
            // Pie mirando hacia la palabra
            oval.set(footX - size * 0.05f, footY - size * 0.045f, footX + size * 0.10f, footY + size * 0.045f)
            canvas.drawOval(oval, white)
        }
        // Cabeza / cuerpo
        canvas.drawCircle(cx, cy, r, white)
        // Ojitos grandes y del MISMO tamaño, mirando siempre a la derecha (hacia «Pensando...»), con brillo
        val eyeR = r * 0.36f
        val ey = cy - r * 0.12f
        for (ex in floatArrayOf(cx - r * 0.28f, cx + r * 0.46f)) {
            canvas.drawCircle(ex, ey, eyeR, eye)
            canvas.drawCircle(ex + eyeR * 0.35f, ey - eyeR * 0.35f, eyeR * 0.32f, white)
        }
        if (active) invalidateSelf()   // siguiente cuadro de las patitas
    }

    override fun setAlpha(alpha: Int) {
        white.alpha = alpha; leg.alpha = alpha; eye.alpha = alpha; invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        white.colorFilter = colorFilter; leg.colorFilter = colorFilter; invalidateSelf()
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

