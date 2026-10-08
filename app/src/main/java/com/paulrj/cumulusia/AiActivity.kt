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
 * - En el chat: cerebro azul que gira + «Pensando...» / «Analizando...» + la flecha ›
 * - Al tocar la burbuja se abre una ventana con cada paso: lo que busca, las páginas que lee,
 *   el motor y los ajustes que aplica, los archivos que analiza, los reintentos, etc.
 *   La ventana se va actualizando sola mientras la IA trabaja.
 */
object AiActivity {
    data class Step(val atMs: Long, val text: String, val detail: String = "")

    /** Morado claro de la estrella. */
    val PURPLE: Int = Color.parseColor("#C4A7FF")

    /** Azul claro de la estrella que gira mientras la IA piensa. */
    val LIGHT_BLUE: Int = Color.parseColor("#7CC8FF")

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
    /**
     * ✦ Giro de la estrella (0..1 = una vuelta). Gira más rápido cuando la IA piensa rápido y más lento cuando
     * piensa lento. Avanza con el reloj, así sigue suave aunque el chat se redibuje.
     */
    fun spinPhase(): Float {
        val now = SystemClock.uptimeMillis()
        val dt = if (spinPhaseAt == 0L) 0L else (now - spinPhaseAt).coerceIn(0L, 100L)
        spinPhaseAt = now
        val turnMs = 2000f - 1550f * pace()   // de ~1,9 s por vuelta (piensa lento) a 0,45 s (piensa muy rápido)
        spinPhaseValue = (spinPhaseValue + dt / turnMs) % 1f
        return spinPhaseValue
    }
    @Volatile private var spinPhaseValue = 0f
    @Volatile private var spinPhaseAt = 0L

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
            setImageDrawable(BrainDrawable(LIGHT_BLUE) { running })   // 🧠 cerebro que gira (antes estrella ✦)
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

        // 👆 Se arrastra con el dedo: hacia arriba sube casi a pantalla completa; hacia abajo vuelve
        //    a la altura normal; y si desde la altura normal la bajas otra vez, se cierra.
        //    Al volver a abrirla aparece siempre a la altura normal.
        val screenH = context.resources.displayMetrics.heightPixels
        val normalH = (screenH * 0.62f).toInt()
        val tallH = (screenH * 0.92f).toInt()
        var tall = false
        val frame = android.widget.FrameLayout(context).apply {
            setOnClickListener { dialog.dismiss() }   // tocar lo de arriba (fuera de la ventana) la cierra
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, normalH, Gravity.BOTTOM))
        fun setHeight(h: Int) { root.layoutParams = root.layoutParams.apply { height = h } }
        fun animateTo(target: Int, then: (() -> Unit)? = null) {
            android.animation.ValueAnimator.ofInt(root.layoutParams.height, target).apply {
                duration = 220
                addUpdateListener { setHeight(it.animatedValue as Int) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { then?.invoke() }
                })
                start()
            }
        }
        var startY = 0f
        var startH = 0
        // Se arrastra desde cualquier parte de la ventana que no sea la lista (la lista sigue deslizando sus pasos)
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startH = root.layoutParams.height; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    setHeight((startH - (e.rawY - startY)).toInt().coerceIn(dp(80), tallH)); true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - startY
                    when {
                        kotlin.math.abs(dy) < dp(8) -> { v.performClick(); animateTo(if (tall) tallH else normalH) }
                        !tall && dy < -dp(40) -> { tall = true; animateTo(tallH) }              // ↑ sube más
                        tall && dy > dp(40) -> { tall = false; animateTo(normalH) }             // ↓ vuelve a la altura normal
                        !tall && dy > dp(60) -> animateTo(0) { if (dialog.isShowing) dialog.dismiss() }   // ↓ otra vez: se cierra
                        else -> animateTo(if (tall) tallH else normalH)
                    }
                    true
                }
                else -> false
            }
        }

        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, tallH)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }
}

/**
 * ✦ Estrella (una grande y una chica). Mientras la IA trabaja da vueltas en círculo:
 * más rápido cuando la IA piensa rápido y más lento cuando piensa lento (AiActivity.spinPhase()).
 */
class SparkleDrawable(color: Int, private val spinning: () -> Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()

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
        if (spin) canvas.rotate(AiActivity.spinPhase() * 360f, cx, cy)
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
 * 🧠 Cerebro azul brillante visto de lado (lóbulo frontal, temporal y cerebelo), con sus pliegues
 * y una red de neuronas, en vez de la estrella ✦. Mientras la IA trabaja GIRA EN CÍRCULO sobre sí mismo,
 * como una botella girando sobre la mesa: más rápido cuando la IA piensa rápido y más lento cuando
 * piensa lento (AiActivity.spinPhase()). Las neuronas se encienden y apagan. Dibujado a mano: no usa imágenes.
 */
class BrainDrawable(@Suppress("UNUSED_PARAMETER") accent: Int, private val thinking: () -> Boolean) : Drawable() {
    // Todo se dibuja en una cuadrícula de 100 × 100 (frente a la izquierda, cerebelo abajo a la derecha)
    private val outline = Path().also { p ->
        p.moveTo(3.56f, 49.3f)
        p.cubicTo(3.32f, 50.88f, 2.86f, 55.13f, 3.14f, 56.96f)
        p.cubicTo(3.42f, 58.8f, 4.76f, 59.33f, 5.23f, 60.31f)
        p.cubicTo(5.69f, 61.28f, 5.41f, 62.02f, 5.92f, 62.81f)
        p.cubicTo(6.44f, 63.6f, 7.18f, 64.27f, 8.29f, 65.04f)
        p.cubicTo(9.41f, 65.81f, 11.22f, 66.94f, 12.61f, 67.41f)
        p.cubicTo(14f, 67.87f, 14.77f, 67.8f, 16.65f, 67.83f)
        p.cubicTo(18.53f, 67.85f, 22.61f, 66.92f, 23.89f, 67.55f)
        p.cubicTo(25.17f, 68.17f, 23.77f, 70.19f, 24.31f, 71.59f)
        p.cubicTo(24.84f, 72.98f, 25.75f, 74.79f, 27.09f, 75.9f)
        p.cubicTo(28.44f, 77.02f, 31.04f, 77.85f, 32.38f, 78.27f)
        p.cubicTo(33.73f, 78.69f, 34.17f, 78.2f, 35.17f, 78.41f)
        p.cubicTo(36.17f, 78.62f, 37.28f, 79.34f, 38.37f, 79.52f)
        p.cubicTo(39.46f, 79.71f, 40.58f, 79.64f, 41.71f, 79.52f)
        p.cubicTo(42.85f, 79.41f, 42.99f, 79.66f, 45.2f, 78.83f)
        p.cubicTo(47.4f, 77.99f, 51.07f, 75.72f, 54.94f, 74.51f)
        p.cubicTo(58.82f, 73.3f, 64.81f, 72.12f, 68.45f, 71.59f)
        p.cubicTo(72.1f, 71.05f, 74.16f, 71.14f, 76.81f, 71.31f)
        p.cubicTo(79.45f, 71.47f, 82.01f, 72.28f, 84.33f, 72.56f)
        p.cubicTo(86.65f, 72.84f, 89.32f, 73.02f, 90.73f, 72.98f)
        p.cubicTo(92.15f, 72.93f, 92.15f, 72.68f, 92.82f, 72.28f)
        p.cubicTo(93.5f, 71.89f, 94.1f, 71.63f, 94.77f, 70.61f)
        p.cubicTo(95.44f, 69.59f, 96.63f, 68.92f, 96.86f, 66.15f)
        p.cubicTo(97.09f, 63.39f, 96.79f, 57.54f, 96.16f, 54.04f)
        p.cubicTo(95.54f, 50.53f, 94.28f, 47.63f, 93.1f, 45.13f)
        p.cubicTo(91.92f, 42.62f, 90.64f, 40.99f, 89.06f, 39f)
        p.cubicTo(87.48f, 37f, 86.02f, 35.28f, 83.63f, 33.15f)
        p.cubicTo(81.24f, 31.01f, 77.27f, 27.86f, 74.72f, 26.19f)
        p.cubicTo(72.17f, 24.52f, 70.33f, 23.73f, 68.31f, 23.12f)
        p.cubicTo(66.29f, 22.52f, 64.09f, 22.91f, 62.6f, 22.57f)
        p.cubicTo(61.12f, 22.22f, 61.58f, 21.29f, 59.4f, 21.03f)
        p.cubicTo(57.22f, 20.78f, 51.62f, 21.15f, 49.51f, 21.03f)
        p.cubicTo(47.4f, 20.92f, 47.56f, 20.45f, 46.73f, 20.34f)
        p.cubicTo(45.89f, 20.22f, 46.31f, 19.99f, 44.5f, 20.34f)
        p.cubicTo(42.69f, 20.69f, 38.23f, 21.96f, 35.87f, 22.43f)
        p.cubicTo(33.5f, 22.89f, 32.27f, 22.61f, 30.29f, 23.12f)
        p.cubicTo(28.32f, 23.63f, 26.56f, 24.19f, 24.03f, 25.49f)
        p.cubicTo(21.5f, 26.79f, 16.97f, 29.64f, 15.12f, 30.92f)
        p.cubicTo(13.26f, 32.2f, 13.37f, 32.55f, 12.89f, 33.15f)
        p.cubicTo(12.4f, 33.75f, 12.68f, 34.01f, 12.19f, 34.54f)
        p.cubicTo(11.7f, 35.08f, 11.1f, 34.77f, 9.96f, 36.35f)
        p.cubicTo(8.83f, 37.93f, 6.27f, 42.16f, 5.37f, 44.01f)
        p.cubicTo(4.46f, 45.87f, 4.83f, 46.61f, 4.53f, 47.49f)
        p.cubicTo(4.23f, 48.38f, 3.79f, 47.73f, 3.56f, 49.3f)
        p.close()
    }
    // Cisura lateral (separa el lóbulo temporal), borde del cerebelo y surco central
    private val mainFolds = Path().also { p ->
        p.moveTo(24f, 67.5f)
        p.cubicTo(24.69f, 66.98f, 27.1f, 65.72f, 28.16f, 64.36f)
        p.cubicTo(29.21f, 63.01f, 28.99f, 60.26f, 30.34f, 59.39f)
        p.cubicTo(31.7f, 58.51f, 34.57f, 59.72f, 36.3f, 59.1f)
        p.cubicTo(38.02f, 58.48f, 38.99f, 56.02f, 40.7f, 55.65f)
        p.cubicTo(42.42f, 55.28f, 44.71f, 57.07f, 46.58f, 56.9f)
        p.cubicTo(48.45f, 56.72f, 50.13f, 54.99f, 51.92f, 54.6f)
        p.cubicTo(53.7f, 54.22f, 55.67f, 55.18f, 57.3f, 54.6f)
        p.cubicTo(58.93f, 54.02f, 59.75f, 52.5f, 61.7f, 51.15f)
        p.cubicTo(63.65f, 49.8f, 67.78f, 47.28f, 69f, 46.5f)
        p.moveTo(68.5f, 71.4f)
        p.cubicTo(69.11f, 70.98f, 71.14f, 69.95f, 72.15f, 68.89f)
        p.cubicTo(73.17f, 67.84f, 73.26f, 65.78f, 74.6f, 65.06f)
        p.cubicTo(75.93f, 64.34f, 78.47f, 65.1f, 80.17f, 64.57f)
        p.cubicTo(81.88f, 64.05f, 83.15f, 62.17f, 84.83f, 61.93f)
        p.cubicTo(86.51f, 61.68f, 88.47f, 63f, 90.25f, 63.1f)
        p.cubicTo(92.03f, 63.2f, 94.62f, 62.6f, 95.5f, 62.5f)
        p.moveTo(53f, 21f)
        p.cubicTo(52.6f, 21.7f, 50.82f, 23.63f, 50.59f, 25.18f)
        p.cubicTo(50.37f, 26.73f, 52.07f, 28.87f, 51.66f, 30.32f)
        p.cubicTo(51.24f, 31.78f, 48.52f, 32.49f, 48.1f, 33.91f)
        p.cubicTo(47.68f, 35.33f, 49.49f, 37.49f, 49.15f, 38.84f)
        p.cubicTo(48.81f, 40.19f, 46.32f, 40.78f, 46.07f, 42.01f)
        p.cubicTo(45.83f, 43.25f, 47.96f, 45.04f, 47.68f, 46.24f)
        p.cubicTo(47.39f, 47.43f, 44.88f, 48.04f, 44.35f, 49.17f)
        p.cubicTo(43.82f, 50.3f, 44.47f, 52.36f, 44.5f, 53f)
    }
    // Pliegues más finos
    private val folds = Path().also { p ->
        p.moveTo(43f, 21.5f)
        p.cubicTo(42.64f, 22.25f, 40.96f, 24.38f, 40.83f, 26f)
        p.cubicTo(40.69f, 27.63f, 42.59f, 29.64f, 42.17f, 31.25f)
        p.cubicTo(41.76f, 32.86f, 38.85f, 33.97f, 38.35f, 35.66f)
        p.cubicTo(37.85f, 37.34f, 39.61f, 39.76f, 39.15f, 41.34f)
        p.cubicTo(38.69f, 42.93f, 36.2f, 43.74f, 35.59f, 45.18f)
        p.cubicTo(34.99f, 46.62f, 35.52f, 49.2f, 35.5f, 50f)
        p.moveTo(61f, 23f)
        p.cubicTo(60.64f, 23.71f, 58.97f, 25.7f, 58.83f, 27.24f)
        p.cubicTo(58.69f, 28.78f, 60.5f, 30.76f, 60.17f, 32.26f)
        p.cubicTo(59.84f, 33.76f, 57.16f, 34.74f, 56.83f, 36.24f)
        p.cubicTo(56.5f, 37.74f, 58.47f, 39.75f, 58.17f, 41.26f)
        p.cubicTo(57.88f, 42.77f, 55.51f, 43.85f, 55.07f, 45.3f)
        p.cubicTo(54.62f, 46.76f, 55.43f, 49.22f, 55.5f, 50f)
        p.moveTo(9f, 37f)
        p.cubicTo(9.75f, 36.91f, 12.27f, 37.22f, 13.52f, 36.44f)
        p.cubicTo(14.77f, 35.66f, 15.27f, 32.46f, 16.48f, 32.31f)
        p.cubicTo(17.69f, 32.16f, 19.3f, 35.29f, 20.76f, 35.53f)
        p.cubicTo(22.22f, 35.76f, 23.69f, 33.99f, 25.24f, 33.72f)
        p.cubicTo(26.79f, 33.45f, 28.62f, 34.45f, 30.08f, 33.91f)
        p.cubicTo(31.54f, 33.38f, 33.35f, 31.07f, 34f, 30.5f)
        p.moveTo(5.5f, 48f)
        p.cubicTo(6.19f, 48f, 8.48f, 48.61f, 9.66f, 47.98f)
        p.cubicTo(10.84f, 47.36f, 11.43f, 44.27f, 12.59f, 44.27f)
        p.cubicTo(13.75f, 44.27f, 15.15f, 47.58f, 16.61f, 47.99f)
        p.cubicTo(18.08f, 48.41f, 19.96f, 46.81f, 21.39f, 46.76f)
        p.cubicTo(22.81f, 46.71f, 24.08f, 48.2f, 25.16f, 47.7f)
        p.cubicTo(26.23f, 47.21f, 26.7f, 44.5f, 27.84f, 43.8f)
        p.cubicTo(28.98f, 43.1f, 31.31f, 43.55f, 32f, 43.5f)
        p.moveTo(9f, 57f)
        p.cubicTo(9.64f, 56.95f, 11.73f, 57.27f, 12.84f, 56.69f)
        p.cubicTo(13.95f, 56.12f, 14.71f, 53.52f, 15.66f, 53.56f)
        p.cubicTo(16.62f, 53.59f, 17.5f, 56.24f, 18.55f, 56.89f)
        p.cubicTo(19.61f, 57.55f, 21.43f, 57.4f, 22f, 57.5f)
        p.moveTo(61f, 36f)
        p.cubicTo(61.65f, 35.98f, 63.8f, 36.49f, 64.9f, 35.88f)
        p.cubicTo(66f, 35.28f, 66.5f, 32.25f, 67.6f, 32.37f)
        p.cubicTo(68.69f, 32.49f, 69.97f, 35.9f, 71.46f, 36.57f)
        p.cubicTo(72.95f, 37.25f, 75f, 35.87f, 76.54f, 36.43f)
        p.cubicTo(78.08f, 36.98f, 79.13f, 39.32f, 80.71f, 39.91f)
        p.cubicTo(82.29f, 40.51f, 85.12f, 39.99f, 86f, 40f)
        p.moveTo(64f, 45f)
        p.cubicTo(64.74f, 45.22f, 66.93f, 46.45f, 68.44f, 46.35f)
        p.cubicTo(69.95f, 46.25f, 71.62f, 44.08f, 73.06f, 44.4f)
        p.cubicTo(74.5f, 44.72f, 75.55f, 47.68f, 77.1f, 48.28f)
        p.cubicTo(78.66f, 48.87f, 80.83f, 47.67f, 82.4f, 47.97f)
        p.cubicTo(83.97f, 48.28f, 85.07f, 49.93f, 86.5f, 50.1f)
        p.cubicTo(87.93f, 50.27f, 90.25f, 49.18f, 91f, 49f)
        p.moveTo(29.5f, 70.5f)
        p.cubicTo(30.15f, 70.37f, 32.33f, 70.44f, 33.43f, 69.74f)
        p.cubicTo(34.52f, 69.03f, 34.9f, 66.82f, 36.07f, 66.26f)
        p.cubicTo(37.24f, 65.71f, 39.12f, 66.75f, 40.43f, 66.4f)
        p.cubicTo(41.74f, 66.05f, 42.64f, 64.27f, 43.92f, 64.17f)
        p.cubicTo(45.19f, 64.07f, 46.75f, 65.94f, 48.08f, 65.83f)
        p.cubicTo(49.42f, 65.72f, 50.5f, 63.73f, 51.92f, 63.5f)
        p.cubicTo(53.33f, 63.28f, 55.14f, 64.8f, 56.56f, 64.47f)
        p.cubicTo(57.98f, 64.14f, 59.03f, 62.02f, 60.44f, 61.53f)
        p.cubicTo(61.85f, 61.03f, 64.24f, 61.5f, 65f, 61.5f)
        p.moveTo(33f, 75.5f)
        p.cubicTo(33.66f, 75.46f, 35.8f, 75.83f, 36.97f, 75.29f)
        p.cubicTo(38.14f, 74.74f, 38.81f, 72.6f, 40.03f, 72.21f)
        p.cubicTo(41.25f, 71.82f, 43f, 73.24f, 44.3f, 72.95f)
        p.cubicTo(45.61f, 72.67f, 46.57f, 70.67f, 47.88f, 70.51f)
        p.cubicTo(49.18f, 70.35f, 50.79f, 72.16f, 52.12f, 71.99f)
        p.cubicTo(53.46f, 71.83f, 54.35f, 69.85f, 55.88f, 69.51f)
        p.cubicTo(57.4f, 69.17f, 59.6f, 70.29f, 61.29f, 69.96f)
        p.cubicTo(62.97f, 69.62f, 65.21f, 67.91f, 66f, 67.5f)
        p.moveTo(72f, 66.5f)
        p.cubicTo(72.47f, 66.5f, 73.98f, 66.85f, 74.84f, 66.51f)
        p.cubicTo(75.7f, 66.18f, 76.27f, 64.71f, 77.16f, 64.49f)
        p.cubicTo(78.05f, 64.27f, 79.25f, 65.24f, 80.17f, 65.18f)
        p.cubicTo(81.09f, 65.12f, 81.86f, 64f, 82.68f, 64.1f)
        p.cubicTo(83.51f, 64.21f, 84.25f, 65.7f, 85.12f, 65.8f)
        p.cubicTo(85.99f, 65.9f, 87.01f, 64.6f, 87.88f, 64.7f)
        p.cubicTo(88.75f, 64.8f, 89.47f, 66.18f, 90.32f, 66.4f)
        p.cubicTo(91.17f, 66.61f, 92.55f, 66.07f, 93f, 66f)
        p.moveTo(72f, 69.5f)
        p.cubicTo(72.43f, 69.55f, 73.79f, 70.06f, 74.6f, 69.82f)
        p.cubicTo(75.42f, 69.58f, 76.06f, 68.18f, 76.9f, 68.06f)
        p.cubicTo(77.73f, 67.93f, 78.77f, 69.19f, 79.6f, 69.07f)
        p.cubicTo(80.44f, 68.94f, 81.01f, 67.31f, 81.9f, 67.31f)
        p.cubicTo(82.78f, 67.31f, 83.88f, 68.94f, 84.91f, 69.07f)
        p.cubicTo(85.94f, 69.19f, 87.09f, 67.93f, 88.09f, 68.06f)
        p.cubicTo(89.09f, 68.18f, 89.93f, 69.58f, 90.91f, 69.82f)
        p.cubicTo(91.9f, 70.06f, 93.49f, 69.55f, 94f, 69.5f)
        p.moveTo(30f, 24f)
        p.cubicTo(29.75f, 24.47f, 28.52f, 25.81f, 28.51f, 26.84f)
        p.cubicTo(28.51f, 27.86f, 29.89f, 29.16f, 29.99f, 30.16f)
        p.cubicTo(30.08f, 31.17f, 28.9f, 32.07f, 29.07f, 32.87f)
        p.cubicTo(29.24f, 33.68f, 30.68f, 34.65f, 31f, 35f)
        p.moveTo(36f, 22f)
        p.cubicTo(35.71f, 22.46f, 34.57f, 23.75f, 34.24f, 24.75f)
        p.cubicTo(33.91f, 25.75f, 34.04f, 27.46f, 34f, 28f)
        p.moveTo(66f, 24.5f)
        p.cubicTo(65.75f, 24.92f, 64.73f, 26.12f, 64.48f, 27.04f)
        p.cubicTo(64.23f, 27.96f, 64.5f, 29.51f, 64.5f, 30f)
        p.moveTo(71f, 26.5f)
        p.cubicTo(70.69f, 27f, 69.47f, 28.4f, 69.14f, 29.49f)
        p.cubicTo(68.81f, 30.57f, 69.02f, 32.41f, 69f, 33f)
        p.moveTo(78f, 30.5f)
        p.cubicTo(77.62f, 30.89f, 76.21f, 31.9f, 75.71f, 32.82f)
        p.cubicTo(75.21f, 33.74f, 75.12f, 35.47f, 75f, 36f)
        p.moveTo(17f, 41f)
        p.cubicTo(17.6f, 41.07f, 19.48f, 41.71f, 20.63f, 41.39f)
        p.cubicTo(21.77f, 41.08f, 22.88f, 38.96f, 23.87f, 39.11f)
        p.cubicTo(24.87f, 39.26f, 25.58f, 41.66f, 26.6f, 42.3f)
        p.cubicTo(27.62f, 42.95f, 29.43f, 42.88f, 30f, 43f)
        p.moveTo(84f, 52f)
        p.cubicTo(84.33f, 52.44f, 85.08f, 54.06f, 86f, 54.62f)
        p.cubicTo(86.92f, 55.19f, 88.33f, 55.31f, 89.5f, 55.38f)
        p.cubicTo(90.67f, 55.44f, 92.42f, 55.06f, 93f, 55f)
        p.moveTo(20f, 62f)
        p.cubicTo(20.35f, 62.04f, 21.45f, 62.47f, 22.1f, 62.26f)
        p.cubicTo(22.75f, 62.05f, 23.25f, 60.95f, 23.9f, 60.74f)
        p.cubicTo(24.55f, 60.53f, 25.65f, 60.96f, 26f, 61f)
    }
    private val nodes = floatArrayOf(12f, 42f, 20f, 30f, 31f, 26f, 44f, 25f, 57f, 27f, 69f, 30f, 80f, 36f, 89f, 45f, 91f, 56f, 83f, 58f, 73f, 54f, 61f, 46f, 50f, 42f, 38f, 38f, 25f, 44f, 15f, 52f, 28f, 52f, 42f, 50f, 56f, 58f, 36f, 63f, 48f, 69f, 60f, 66f, 78f, 68f, 88f, 67f, 66f, 40f, 7f, 50f, 52f, 33f, 34f, 72f)
    private val edges = intArrayOf(0, 1, 0, 14, 0, 15, 0, 25, 1, 2, 1, 14, 2, 3, 2, 13, 3, 4, 3, 13, 3, 26, 4, 5, 4, 24, 4, 26, 5, 6, 5, 24, 6, 7, 6, 24, 7, 8, 7, 9, 8, 9, 8, 23, 9, 10, 9, 22, 9, 23, 10, 11, 10, 18, 10, 21, 10, 22, 10, 24, 11, 12, 11, 18, 11, 24, 11, 26, 12, 13, 12, 17, 12, 18, 12, 26, 13, 14, 13, 16, 13, 17, 13, 26, 14, 15, 14, 16, 15, 16, 15, 25, 16, 17, 16, 19, 17, 18, 17, 19, 18, 20, 18, 21, 19, 20, 19, 27, 20, 21, 20, 27, 22, 23, 24, 26)
    private val links = Path().also { p ->
        var i = 0
        while (i < edges.size) {
            val a = edges[i] * 2; val b = edges[i + 1] * 2
            p.moveTo(nodes[a], nodes[a + 1]); p.lineTo(nodes[b], nodes[b + 1])
            i += 2
        }
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#3FA9FF")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = android.graphics.LinearGradient(0f, 20f, 0f, 80f,
            Color.parseColor("#1E5BFF"), Color.parseColor("#0716B8"), android.graphics.Shader.TileMode.CLAMP)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var alphaAll = 255

    private fun stroke(canvas: Canvas, path: Path, hex: String, alpha: Int, width: Float) {
        line.color = Color.parseColor(hex); line.alpha = alpha * alphaAll / 255; line.strokeWidth = width
        canvas.drawPath(path, line)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val k = minOf(w, h) / 100f
        val active = thinking()
        val phase = if (active) AiActivity.spinPhase() else 0f
        // 🔄 Giro en círculo sobre su eje (se ve de frente, de lado y de espaldas)
        val c = kotlin.math.cos(phase * 2.0 * Math.PI).toFloat()
        val sx = (if (c < 0f) -1f else 1f) * maxOf(kotlin.math.abs(c), 0.16f)
        canvas.save()
        canvas.translate(b.left + w / 2f, b.top + h / 2f)
        canvas.scale(sx * k, k)
        canvas.translate(-50f, -50f)

        // Resplandor azul alrededor (varias capas suaves)
        for ((width, a) in listOf(8f to 40, 5.5f to 70, 3.8f to 110)) {
            glow.strokeWidth = width; glow.alpha = a * alphaAll / 255
            canvas.drawPath(outline, glow)
        }
        fill.alpha = alphaAll
        canvas.drawPath(outline, fill)                           // relleno azul
        stroke(canvas, folds, "#6FC6FF", 230, 1.3f)              // pliegues finos
        stroke(canvas, mainFolds, "#B5ECFF", 255, 2f)            // pliegues marcados
        stroke(canvas, links, "#D6F0FF", 115, 0.55f)             // red de neuronas
        var i = 0
        while (i < nodes.size) {
            val n = i / 2
            // Cada neurona se enciende a su ritmo mientras la IA piensa
            val spark = if (active) ((kotlin.math.sin((phase * 2f + n * 0.37f) * 2.0 * Math.PI) + 1.0) / 2.0).toFloat() else 1f
            dot.color = Color.parseColor("#9FDCFF"); dot.alpha = ((40 + 80 * spark) * alphaAll / 255).toInt()
            canvas.drawCircle(nodes[i], nodes[i + 1], 2.4f, dot)
            dot.color = Color.WHITE; dot.alpha = ((150 + 105 * spark) * alphaAll / 255).toInt()
            canvas.drawCircle(nodes[i], nodes[i + 1], 1.1f, dot)
            i += 2
        }
        stroke(canvas, outline, "#A6E8FF", 255, 1.7f)            // borde brillante
        canvas.restore()
        if (active) invalidateSelf()   // siguiente cuadro de la animación
    }

    override fun setAlpha(alpha: Int) { alphaAll = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        glow.colorFilter = colorFilter; fill.colorFilter = colorFilter; line.colorFilter = colorFilter; dot.colorFilter = colorFilter
        invalidateSelf()
    }
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

