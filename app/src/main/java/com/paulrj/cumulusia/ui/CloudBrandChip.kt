package com.paulrj.cumulusia.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * ☁️ Botón de un servicio de IA en la nube: el NOMBRE escrito en el color de ese servicio (no es su logo).
 *  Claude → naranjo terracota · Gemini → degradado azul-violeta-rosado · ChatGPT → blanco con contorno negro ·
 *  OpenRouter → gris azulado con contorno claro · Groq → naranjo rojizo · DeepSeek → azul ·
 *  Mistral → degradado amarillo-naranjo-rojo · Personalizado → colores de Cumulus.
 *
 * Se dibuja solo (fondo, nombre y marca de elegido), así el pintado automático de la app (estrellas, modo claro)
 * no le cambia los colores. [light] dice si la app está en modo claro (se mira cada vez que se dibuja).
 */
class CloudBrandChip(
    context: Context,
    private val brand: String,
    private var label: String,
    private val light: () -> Boolean
) : View(context) {

    var selectedChip = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
    }

    fun setLabel(text: String) { label = text; contentDescription = text; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (50 * d).toInt())
    }

    /** Colores del nombre: un color, o varios en degradado. Y el contorno (0 = sin contorno). */
    private fun style(isLight: Boolean): Triple<IntArray, Int, Float> = when (brand) {
        "claude" -> Triple(intArrayOf(Color.parseColor("#D97757")), 0, 0f)
        "gemini" -> Triple(intArrayOf(Color.parseColor("#4285F4"), Color.parseColor("#9B72CB"), Color.parseColor("#D96570")), 0, 0f)
        "chatgpt" -> Triple(intArrayOf(Color.WHITE), Color.BLACK, if (isLight) 3.2f else 2.6f)
        "openrouter" -> Triple(intArrayOf(Color.parseColor("#3B4A6B")), Color.parseColor("#C9D1E6"), 2.2f)
        "groq" -> Triple(intArrayOf(Color.parseColor("#F55036")), 0, 0f)
        "deepseek" -> Triple(intArrayOf(Color.parseColor("#4D6BFE")), 0, 0f)
        "mistral" -> Triple(intArrayOf(Color.parseColor("#FFD800"), Color.parseColor("#FF8205"), Color.parseColor("#E10500")),
            if (isLight) Color.parseColor("#55000000") else 0, if (isLight) 1.2f else 0f)
        else -> Triple(intArrayOf(Color.parseColor("#5CE1FF"), Color.parseColor("#8E7CF5"), Color.parseColor("#E33FD6")), 0, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val isLight = light()
        val w = width.toFloat(); val h = height.toFloat()
        // Fondo: la misma píldora gris de los demás botones
        rect.set(1f, 1f, w - 1f, h - 1f)
        val r = minOf(h / 2f, 26 * d)
        bg.color = if (isLight) Color.parseColor("#EDEDF0") else Color.parseColor("#1F1F1F")
        canvas.drawRoundRect(rect, r, r, bg)
        // Elegido: borde fino alrededor
        if (selectedChip) {
            ring.strokeWidth = 2f * d
            ring.color = if (isLight) Color.parseColor("#1C1C1E") else Color.WHITE
            rect.inset(ring.strokeWidth / 2f, ring.strokeWidth / 2f)
            canvas.drawRoundRect(rect, r, r, ring)
        }
        // Nombre (si no cabe, se achica)
        val check = if (selectedChip) "✓ " else ""
        val text = check + label
        var size = 16 * resources.displayMetrics.scaledDensity
        fill.textSize = size
        val maxW = w - 24 * d
        while (fill.measureText(text) > maxW && size > 9 * d) { size -= 0.5f * d; fill.textSize = size }
        outline.textSize = size
        val tw = fill.measureText(text)
        val x = (w - tw) / 2f
        val y = h / 2f - (fill.descent() + fill.ascent()) / 2f
        val (colors, outColor, outW) = style(isLight)
        // ✓ de elegido: del color normal del texto
        val checkW = if (check.isNotEmpty()) fill.measureText(check) else 0f
        if (check.isNotEmpty()) {
            fill.shader = null
            fill.color = if (isLight) Color.parseColor("#1C1C1E") else Color.WHITE
            canvas.drawText(check, x, y, fill)
        }
        val nx = x + checkW
        val nameW = fill.measureText(label)
        if (outColor != 0 && outW > 0f) {
            outline.color = outColor
            outline.strokeWidth = outW * d
            canvas.drawText(label, nx, y, outline)
        }
        // (el color del pincel también afecta al degradado: se deja en negro opaco antes de ponerlo)
        fill.color = Color.BLACK
        fill.shader = if (colors.size > 1)
            LinearGradient(nx, 0f, nx + nameW, 0f, colors, null, Shader.TileMode.CLAMP)
        else { fill.color = colors[0]; null }
        canvas.drawText(label, nx, y, fill)
        fill.shader = null
    }
}
