package com.paulrj.cumulusia.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * ✨ TODO LO BLANCO DE LA APP CON EL COLOR DE LAS ESTRELLAS (degradado celeste → magenta del ícono):
 *  • Textos blancos: botones, títulos, menús, avisos, mensajes del chat y lo que escribes.
 *    Cada texto completo va de celeste (izquierda) a magenta (derecha).
 *  • Íconos blancos: ☰, ⋮, ⚙️, ＋, micrófono, ✕…
 * Lo grisáceo y lo que ya tiene color (azul, rojo, naranja…) queda igual.
 *
 * Lo aplica LanguageManager a todas las ventanas (pantallas, menús, diálogos), después de traducir.
 */
object StarText {
    val C1 = Color.parseColor("#18E6FF")   // celeste (igual que las estrellas)
    val C2 = Color.parseColor("#FF16E6")   // magenta

    /** ¿Es blanco (o casi blanco)? */
    private fun isWhite(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return Color.alpha(c) > 120 && r >= 225 && g >= 225 && b >= 225
    }

    /** Llamar con cada vista de cada ventana. Devuelve true si cambió algo. */
    fun apply(v: View): Boolean = when (v) {
        is TextView -> applyText(v)
        is ImageView -> applyIcon(v)
        else -> false
    }

    // ========================= TEXTOS =========================

    private class TextState(var key: String = "", var on: Boolean = false)
    private val textStates = WeakHashMap<TextView, TextState>()

    private fun skipText(v: TextView): Boolean {
        if (v.javaClass.simpleName == "ShimmerTextView") return true             // «Pensando…» con su brillo animado
        val tf = v.typeface
        if (tf != null && tf == Typeface.MONOSPACE) return true                  // código (tiene sus propios colores)
        return false
    }

    private fun applyText(v: TextView): Boolean {
        if (skipText(v)) return false
        val st = textStates.getOrPut(v) { TextState() }
        val layout = v.layout ?: return false          // todavía no se acomoda en la pantalla: se revisa en la próxima pasada
        val wantOn = isWhite(v.currentTextColor) && !v.text.isNullOrEmpty()
        if (!wantOn) {
            // Ya no es blanco (o está vacío y se ve el texto de ayuda gris): se quita el degradado
            if (st.on) { v.paint.shader = null; st.on = false; st.key = ""; redraw(v); return true }
            return false
        }
        // Ancho real del texto (de la línea que empieza más a la izquierda a la que termina más a la derecha)
        var left = Float.MAX_VALUE; var right = 0f
        for (i in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(i)); right = maxOf(right, layout.getLineRight(i))
        }
        if (left == Float.MAX_VALUE || right <= left) { left = 0f; right = maxOf(1f, layout.width.toFloat()) }
        val h = maxOf(1, layout.height).toFloat()
        val key = "${left.toInt()}:${right.toInt()}:${h.toInt()}"
        if (st.on && st.key == key && v.paint.shader != null) return false
        v.paint.shader = LinearGradient(left, 0f, right, h * 0.35f, C1, C2, Shader.TileMode.CLAMP)
        st.on = true; st.key = key
        redraw(v)
        return true
    }

    /**
     * Vuelve a dibujar el texto con el color nuevo. Los mensajes del chat (texto que se puede seleccionar)
     * guardan su dibujo en memoria y no se enteran del cambio de color: a esos se les vuelve a poner
     * su mismo texto para que se dibujen de nuevo con el degradado.
     */
    private fun redraw(v: TextView) {
        if (v.isTextSelectable && v !is android.widget.EditText) {
            val t = v.text
            runCatching { v.setText(t, TextView.BufferType.SPANNABLE) }
        }
        v.invalidate()
    }

    // ========================= ÍCONOS =========================

    /** Ícono envuelto: dibuja la forma del ícono original rellena con el degradado. */
    class GradientIcon(val inner: Drawable) : Drawable(), Drawable.Callback {
        private val layerPaint = Paint()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        private var shaderFor = Rect()

        init { inner.callback = this }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (shaderFor != b) {
                shaderFor = Rect(b)
                fill.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), C1, C2, Shader.TileMode.CLAMP)
            }
            val save = canvas.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), layerPaint)
            inner.draw(canvas)                                        // la forma del ícono
            canvas.drawRect(b, fill)                                  // y encima el degradado, solo donde hay ícono
            canvas.restoreToCount(save)
        }

        override fun onBoundsChange(bounds: Rect) { inner.bounds = bounds }
        override fun onStateChange(state: IntArray): Boolean = inner.setState(state)
        override fun isStateful(): Boolean = inner.isStateful
        override fun onLevelChange(level: Int): Boolean = inner.setLevel(level)
        override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
        override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
        override fun setAlpha(alpha: Int) { layerPaint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { /* el color lo pone el degradado */ }
        override fun setTintList(tint: android.content.res.ColorStateList?) { /* el color lo pone el degradado */ }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { scheduleSelf(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { unscheduleSelf(what) }
    }

    /** Ícono ya revisado → si era blanco o no (así no se vuelve a revisar cada vez). */
    private val checked = WeakHashMap<Drawable, Boolean>()

    private fun applyIcon(v: ImageView): Boolean {
        val d = v.drawable ?: return false
        if (d is GradientIcon) return false
        checked[d]?.let { if (!it) return false }
        if (v.width <= 0 || v.height <= 0) return false
        val white = checked[d] ?: isWhiteIcon(v).also { checked[d] = it }
        if (!white) return false
        v.colorFilter = null
        v.imageTintList = null
        v.setImageDrawable(GradientIcon(d))
        return true
    }

    /** Mira cómo se ve el ícono (con su color aplicado): ¿lo que se dibuja es blanco? */
    private fun isWhiteIcon(v: ImageView): Boolean = runCatching {
        val w = minOf(v.width, 64); val h = minOf(v.height, 64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / v.width.toFloat(), h / v.height.toFloat())
        // Solo el ícono (sin el fondo de la vista)
        c.translate(v.paddingLeft.toFloat(), v.paddingTop.toFloat())
        v.imageMatrix?.let { c.concat(it) }
        val d = v.drawable
        val oldFilter = d.colorFilter
        v.colorFilter?.let { d.colorFilter = it }
        d.draw(c)
        d.colorFilter = oldFilter
        var whiteCount = 0; var colored = 0; var visible = 0
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        for (p in px) {
            if (Color.alpha(p) < 100) continue
            visible++
            if (isWhite(p)) whiteCount++ else colored++
        }
        visible >= 8 && whiteCount >= visible * 0.7
    }.getOrDefault(false)
}


