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

    /** MainActivity lo usa para redibujar la burbuja cuando cambia lo que se está haciendo. */
    @Volatile var onBubbleChange: ((Int) -> Unit)? = null

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
        notifyPanel()
    }

    /** Último paso (se muestra en chico debajo de «Pensando...»). */
    fun lastStep(): String? = steps.lastOrNull()?.text

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
            setImageDrawable(SparkleDrawable(PURPLE) { running })
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
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

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(list)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        fun render() {
            title.text = if (running) label else "Actividad de la IA"
            subtitle.text = if (running) "Lo que la IA está haciendo ahora, paso a paso"
                else "Lo que hizo la IA en la última respuesta" + (if (totalMs > 0) " · " + seconds(totalMs) else "")
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

