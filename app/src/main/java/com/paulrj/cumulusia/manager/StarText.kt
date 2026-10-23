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

    /** ☀️ ¿Modo claro? Ahí todo queda en negro (sin degradado). Se lee de la configuración de la app. */
    @Volatile var light = false
        private set
    /** Sube cada vez que cambias entre modo claro y oscuro (así cada texto sabe que debe redibujarse). */
    @Volatile private var modeVersion = 0
    private var prefs: android.content.SharedPreferences? = null
    // Se guarda aquí para que Android no lo borre (si no, deja de avisar)
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == "light_mode") setLight(sp.getBoolean("light_mode", false))
    }

    private fun setLight(value: Boolean) {
        if (value != light) {
            light = value; modeVersion++
            // 🌗 Al cambiar de modo se olvida lo revisado: un ícono revisado en modo claro (negro) no es «no blanco»
            //    para siempre. Antes ☰ y ⚙️ quedaban blancos en el modo oscuro, sin el color de las estrellas.
            checked.clear()
        }
    }

    /** La primera vez lee el modo y queda escuchando: al tocar el interruptor cambia al instante. */
    private fun refreshMode(v: View) {
        if (prefs != null) return
        runCatching {
            val sp = v.context.applicationContext.getSharedPreferences("CumulusAppPermissions", android.content.Context.MODE_PRIVATE)
            prefs = sp
            setLight(sp.getBoolean("light_mode", false))
            sp.registerOnSharedPreferenceChangeListener(prefsListener)
        }
    }

    /** Llamar con cada vista de cada ventana. Devuelve true si cambió algo. */
    fun apply(v: View): Boolean {
        refreshMode(v)
        return when (v) {
            is TextView -> { val c = applyCompound(v); applyText(v) || c }
            is ImageView -> applyIcon(v)
            is android.widget.SeekBar -> applySeek(v)
            else -> false
        }
    }

    // ========================= TEXTOS =========================

    private class TextState(var key: String = "", var on: Boolean = false, var mode: Int = -1)
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
        // ☀️🌙 Cambió el modo: se redibuja (el nombre «Cumulus» pasa a negro o vuelve a sus colores)
        var modeChanged = false
        if (st.mode != modeVersion) { modeChanged = st.mode != -1; st.mode = modeVersion; if (modeChanged) v.invalidate() }
        val layout = v.layout ?: return modeChanged    // todavía no se acomoda en la pantalla: se revisa en la próxima pasada
        val wantOn = !light && isWhite(v.currentTextColor) && !v.text.isNullOrEmpty()
        if (!wantOn) {
            // Ya no es blanco (o está vacío y se ve el texto de ayuda gris): se quita el degradado
            if (st.on) { v.paint.shader = null; st.on = false; st.key = ""; redraw(v); return true }
            return modeChanged
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

    /**
     * Ícono envuelto: dibuja la forma del ícono original rellena con el degradado.
     * Si la app le pone otro color (ej. negro en el modo claro), se dibuja con ese color, sin degradado.
     */
    class GradientIcon(val inner: Drawable, private val ignoreTint: Boolean = false) : Drawable(), Drawable.Callback {
        private val layerPaint = Paint()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        private var shaderFor = Rect()
        // Color que pidió la app (null o blanco = degradado)
        private var tintColor: Int? = null
        private var filterColor: Int? = null

        init { inner.callback = this }

        private fun plainColor(): Int? {
            val c = if (ignoreTint) null else filterColor ?: tintColor   // ignoreTint: siempre degradado (ej. ⏸️)
            return when {
                c != null && !isWhite(c) -> c
                light -> Color.parseColor("#1C1C1E")      // ☀️ modo claro: negro
                else -> null
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val plain = plainColor()
            if (plain != null) {
                inner.colorFilter = android.graphics.PorterDuffColorFilter(plain, PorterDuff.Mode.SRC_IN)
                inner.alpha = layerPaint.alpha
                inner.draw(canvas)
                inner.colorFilter = null
                return
            }
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
        override fun setColorFilter(colorFilter: ColorFilter?) {
            filterColor = colorFilter?.let { filterResult(it) }
            invalidateSelf()
        }
        override fun setTintList(tint: android.content.res.ColorStateList?) {
            tintColor = tint?.getColorForState(state, tint.defaultColor)
            invalidateSelf()
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { scheduleSelf(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { unscheduleSelf(what) }
    }

    /** ¿Qué color deja un filtro de color? (se prueba pintando un punto blanco) */
    private fun filterResult(cf: ColorFilter): Int? = runCatching {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPaint(Paint().apply { color = Color.WHITE; colorFilter = cf })
        bmp.getPixel(0, 0).also { bmp.recycle() }
    }.getOrNull()

    /** Ícono ya revisado → si era blanco o no (así no se vuelve a revisar cada vez). */
    private val checked = WeakHashMap<Drawable, Boolean>()

    private fun applyIcon(v: ImageView): Boolean {
        val d = v.drawable ?: return false
        if (d is GradientIcon) return false
        // ☀️ En modo claro los íconos son negros: no se revisan (ni se anotan) hasta volver al modo oscuro
        if (light) return false
        checked[d]?.let { if (!it) return false }
        if (v.width <= 0 || v.height <= 0) return false
        val white = checked[d] ?: isWhiteIcon(v).also { checked[d] = it }
        if (!white || light) return false
        // El color que tenía (tinte) se mantiene: así la app lo puede cambiar a negro en el modo claro
        v.setImageDrawable(GradientIcon(d))
        return true
    }

    // ===================== ÍCONOS DENTRO DE BOTONES (✕ ✓ y números de «Nombre del modelo», «mmproj»…) =====================

    private fun isWhiteDrawable(d: Drawable): Boolean = checked[d] ?: runCatching {
        val bw = d.bounds.width().takeIf { it > 0 } ?: d.intrinsicWidth
        val bh = d.bounds.height().takeIf { it > 0 } ?: d.intrinsicHeight
        if (bw <= 0 || bh <= 0) return@runCatching false
        val w = minOf(bw, 64); val h = minOf(bh, 64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / bw.toFloat(), h / bh.toFloat())
        c.translate(-d.bounds.left.toFloat(), -d.bounds.top.toFloat())
        val hadBounds = !d.bounds.isEmpty
        if (!hadBounds) d.setBounds(0, 0, bw, bh)
        d.draw(c)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        var visible = 0; var white = 0
        for (p in px) { if (Color.alpha(p) < 100) continue; visible++; if (isWhite(p)) white++ }
        visible >= 4 && white >= visible * 0.7
    }.getOrDefault(false).also { checked[d] = it }

    private fun applyCompound(v: TextView): Boolean {
        if (light) return false
        val ds = v.compoundDrawablesRelative
        if (ds.all { it == null || it is GradientIcon }) return false
        var changed = false
        val out = Array(4) { i ->
            val d = ds[i]
            if (d != null && d !is GradientIcon && isWhiteDrawable(d)) {
                changed = true
                GradientIcon(d).also { it.bounds = android.graphics.Rect(d.bounds) }
            } else d
        }
        if (changed) v.setCompoundDrawablesRelative(out[0], out[1], out[2], out[3])
        return changed
    }

    // ===================== BARRAS (Contexto, Temperatura, Top K, Top P, Min-P) =====================

    private fun applySeek(sb: android.widget.SeekBar): Boolean {
        val ld = sb.progressDrawable as? android.graphics.drawable.LayerDrawable ?: return false
        val prog = ld.findDrawableByLayerId(android.R.id.progress) ?: return false
        if (prog is GradientIcon) return false
        if (light) return false
        // Solo si la barra es blanca (en el modo claro es oscura y queda igual)
        val tint = sb.progressTintList?.defaultColor
        if (tint == null || !isWhite(tint)) return false
        val wrapped = GradientIcon(prog)
        wrapped.bounds = android.graphics.Rect(prog.bounds)
        wrapped.level = prog.level
        if (!ld.setDrawableByLayerId(android.R.id.progress, wrapped)) return false
        sb.progressTintList = sb.progressTintList      // vuelve a aplicar el color (el degradado lo recibe)
        sb.invalidate()
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


