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
 * - En el chat: cerebro azul 3D que gira + «Pensando...» / «Analizando...» + la flecha ›
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

    // ========================================================================
    // 👁️ EN VIVO: lo que la IA va escribiendo (código en Ingeniero de código, texto en los demás modos).
    //    Es SOLO PARA MIRAR: en la ventana no se puede tocar, seleccionar ni editar.
    // ========================================================================
    /** true = modo Ingeniero de código (se muestra como código); false = texto normal. Lo pone MainActivity. */
    @Volatile var liveIsCode = false
    @Volatile private var liveText = ""
    @Volatile private var livePosted = false
    private var liveRefresh: (() -> Unit)? = null
    private val liveTick = Runnable { livePosted = false; runCatching { liveRefresh?.invoke() } }

    /**
     * 🎨 Colores para el código que la IA va escribiendo en vivo.
     *  • Lo que está dentro de ``` … ``` se pinta según su lenguaje (```kotlin, ```html, ```js…).
     *  • Las explicaciones fuera de los bloques quedan en gris claro.
     *  • Si la IA escribe código sin ```, se adivina el lenguaje y se pinta todo.
     *  Solo se pinta desde «from» hasta el final (lo que se ve), así no se traba con textos largos.
     */
    private fun liveColors(text: String, from: Int): CharSequence {
        val fence = "```"
        val out = android.text.SpannableStringBuilder()
        if (!text.contains(fence)) {
            val part = text.substring(from)
            return com.paulrj.cumulusia.adapter.ChatAdapter.colorize(part, guessLang(text))
        }
        // ¿Lo que se ve empieza dentro de un bloque de código? (se cuentan los ``` de antes)
        val before = text.substring(0, from)
        var inCode = Regex(fence).findAll(before).count() % 2 == 1
        var lang = if (inCode) before.substringAfterLast(fence).substringBefore('\n').trim() else ""
        val pieces = text.substring(from).split(fence)
        pieces.forEachIndexed { i, piece0 ->
            var piece = piece0
            if (i > 0) {
                // Cada ``` cambia entre explicación y código
                val st = out.length
                out.append(fence)
                inCode = !inCode
                if (inCode) {
                    val first = piece.substringBefore('\n', piece)
                    if (first.trim().matches(Regex("[\\w+#.-]*"))) {
                        lang = first.trim()
                        out.append(first)
                        piece = if (piece.contains('\n')) piece.substringAfter('\n') else ""
                        if (piece0.contains('\n')) out.append('\n')
                    }
                }
                out.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#7F848E")), st, out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (piece.isEmpty()) return@forEachIndexed
            if (inCode) out.append(com.paulrj.cumulusia.adapter.ChatAdapter.colorize(piece, lang.ifBlank { guessLang(piece) }))
            else out.append(piece)
        }
        return out
    }

    /** Adivina el lenguaje cuando la IA no lo dice (para pintar bien HTML/XML y los comentarios con #). */
    private fun guessLang(code: String): String {
        val t = code.trimStart()
        return when {
            t.startsWith("<") -> if (t.startsWith("<?xml") || t.contains("android:")) "xml" else "html"
            Regex("(?m)^\\s*(def |import \\w+$|from \\w+ import)").containsMatchIn(code) -> "python"
            Regex("\\b(fun|val|var)\\s").containsMatchIn(code) -> "kotlin"
            Regex("\\b(function|const|let)\\s|=>").containsMatchIn(code) -> "js"
            else -> "kotlin"
        }
    }

    /** El motor avisa el texto que lleva escrito (completo hasta ahora); se muestra en vivo en la ventana. */
    fun live(text: String) {
        if (text.isEmpty()) return
        liveText = text
        if (!livePosted) { livePosted = true; main.postDelayed(liveTick, 150) }   // como máximo ~7 veces por segundo
    }
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
        liveText = ""          // 👁️ la vista en vivo empieza vacía en cada respuesta
        liveIsCode = false
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

    /** 📋 Todos los pasos de la respuesta en curso (el chat los muestra como «proceso en curso»). */
    fun stepsSnapshot(): List<Step> = steps.toList()

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

    /**
     * 🌡️ Temperatura de la batería (°C) y su carga (%) según Android. La batería se calienta junto con el procesador,
     * así que sirve para ver cuánto se calienta el teléfono mientras la IA trabaja. (null si el teléfono no la informa)
     */
    fun batteryInfo(context: Context): Pair<Float?, Int> = runCatching {
        val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        (if (t == Int.MIN_VALUE || t <= 0) null else t / 10f) to pct
    }.getOrDefault(null to -1)

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
            setImageDrawable(StarsDrawable(LIGHT_BLUE) { running })   // ✨ estrellas que giran (antes cerebro 🧠)
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
        // 🌡️ Batería con la TEMPERATURA del teléfono (a la derecha de «Analizando...»): así ves cuánto se calienta
        //    mientras la IA trabaja. Verde = normal · amarillo = tibio · naranjo = caliente · rojo = muy caliente.
        val battery = BatteryTempDrawable()
        val tempBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.parseColor("#202024")) }
            contentDescription = "Temperatura del teléfono"
        }
        tempBox.addView(ImageView(context).apply { setImageDrawable(battery) },
            LinearLayout.LayoutParams(dp(26), dp(14)).apply { marginEnd = dp(6) })
        val tempText = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        tempBox.addView(tempText)
        fun updateTemp() {
            val (tempC, level) = batteryInfo(context)
            if (tempC == null) { tempBox.visibility = View.GONE; return }
            tempBox.visibility = View.VISIBLE
            battery.update(tempC, level)
            tempText.text = String.format(Locale.US, "%.0f°C", tempC)
            tempText.setTextColor(BatteryTempDrawable.colorFor(tempC))
            tempBox.contentDescription = String.format(Locale("es", "ES"), "Temperatura del teléfono: %.1f grados", tempC)
        }
        updateTemp()
        // Se actualiza cada 3 segundos mientras la ventana está abierta
        val tempTick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                runCatching { updateTemp() }
                main.postDelayed(this, 3000)
            }
        }
        main.postDelayed(tempTick, 3000)
        header.addView(star); header.addView(title)
        header.addView(tempBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        header.addView(close)
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
        // 👁️ Debajo de los pasos: lo que la IA está escribiendo en este momento (solo para mirar)
        val liveTitle = TextView(context).apply {
            textSize = 14.5f
            setTextColor(PURPLE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(6))
            visibility = View.GONE
        }
        val liveBox = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#E5E5EA"))
            setLineSpacing(2 * d, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(Color.parseColor("#0E0E10"))
                setStroke(dp(1), Color.parseColor("#2C2C30"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
            // Solo mirar: no se selecciona, no se copia, no se edita y no reacciona al toque
            setTextIsSelectable(false)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            visibility = View.GONE
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(list)
            addView(liveTitle)
            addView(liveBox)
        }
        val scroll = ScrollView(context).apply {
            addView(content)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        // 👁️ Pone al día la vista en vivo sin redibujar todos los pasos (así no se traba mientras escribe)
        fun renderLive() {
            val raw = liveText
            if (raw.isBlank()) { liveTitle.visibility = View.GONE; liveBox.visibility = View.GONE; return }
            // ¿El usuario está mirando abajo? Solo entonces se sigue bajando solo (si subió a leer los pasos, no lo mueve)
            val atBottom = content.height - (scroll.scrollY + scroll.height) < dp(160)
            val clean = raw
                .replace(Regex("<(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE), "💭 ")
                .replace(Regex("</(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE), "\n\n")
                .trimEnd()
            val cut = if (clean.length > 8000) clean.length - 8000 else 0
            val shown: CharSequence = if (liveIsCode) {
                // 🎨 Código con colores (igual que en el chat): solo se pintan los últimos 8000 caracteres
                val colored = liveColors(clean, cut)
                if (cut > 0) android.text.SpannableStringBuilder("…\n").append(colored) else colored
            } else if (cut > 0) "…\n" + clean.takeLast(8000) else clean
            liveTitle.text = when {
                liveIsCode && running -> "💻 Código que la IA está escribiendo"
                liveIsCode -> "💻 Código que escribió la IA"
                running -> "✍️ Lo que la IA está escribiendo"
                else -> "✍️ Lo que escribió la IA"
            } + "  ·  solo para mirar"
            liveBox.typeface = if (liveIsCode) Typeface.MONOSPACE else Typeface.DEFAULT
            // ↔️ El código siempre de izquierda a derecha; el texto normal sigue al idioma (árabe: de derecha a izquierda)
            liveBox.layoutDirection = if (liveIsCode) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_INHERIT
            liveBox.textDirection = if (liveIsCode) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
            liveBox.textSize = if (liveIsCode) 12f else 14f
            // Código: color base gris claro (las palabras clave, textos, etc. llevan su propio color)
            liveBox.setTextColor(Color.parseColor(if (liveIsCode) "#ABB2BF" else "#E5E5EA"))
            liveBox.text = if (running) android.text.SpannableStringBuilder(shown).append(" ▍") else shown
            liveTitle.visibility = View.VISIBLE
            liveBox.visibility = View.VISIBLE
            if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

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
            renderLive()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        render()
        panelRefresh = { if (dialog.isShowing) render() }
        liveRefresh = { if (dialog.isShowing) renderLive() }
        dialog.setOnDismissListener { panelRefresh = null; liveRefresh = null; main.removeCallbacks(tempTick) }

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
 * ✨ Nombre de la IA completo con el degradado celeste → magenta de las estrellas del ícono.
 * Sirve para cualquier nombre que el usuario le ponga a la IA.
 */
object StarName {
    private val C1 = Color.parseColor("#18E6FF")   // celeste (igual que las estrellas)
    private val C2 = Color.parseColor("#FF16E6")   // magenta

    /** Dibuja su parte del texto con el degradado (de izquierda a derecha). */
    private class GradientSpan : android.text.style.ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            return kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            if (com.paulrj.cumulusia.manager.StarText.light) {
                // ☀️ Modo claro: el nombre va en negro
                val oldColor = paint.color; val oldShader = paint.shader
                paint.shader = null; paint.color = Color.parseColor("#1C1C1E")
                canvas.drawText(text, start, end, x, y.toFloat(), paint)
                paint.color = oldColor; paint.shader = oldShader
                return
            }
            val w = paint.measureText(text, start, end)
            val old = paint.shader
            paint.shader = android.graphics.LinearGradient(x, top.toFloat(), x + w, bottom.toFloat(), C1, C2,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            paint.shader = old
        }
    }

    fun styled(name: CharSequence): CharSequence {
        val text = name.toString()
        if (text.isBlank()) return text
        val sp = android.text.SpannableString(text)
        // ✨ Todo el nombre con el color de las estrellas (celeste → magenta)
        sp.setSpan(GradientSpan(), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private fun isStyled(t: CharSequence?) =
        t is android.text.Spanned && t.getSpans(0, t.length, GradientSpan::class.java).isNotEmpty()

    /** Deja el TextView siempre con los colores, aunque después se le cambie el nombre. */
    fun attach(tv: TextView?) {
        tv ?: return
        tv.text = styled(tv.text)
        tv.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isStyled(tv.text) && !tv.text.isNullOrBlank()) tv.post { if (!isStyled(tv.text)) tv.text = styled(tv.text) }
            }
        })
    }
}

/**
 * ✨ ESTRELLAS de Cumulus (las mismas del ícono de la app): una grande y 9 chicas con degradado celeste → magenta.
 * Hacen lo mismo que hacía el cerebro: mientras la IA trabaja GIRAN EN 3D sobre sí mismas (como una moneda girando),
 * más rápido cuando la IA piensa rápido y más lento cuando piensa lento (AiActivity.spinPhase()),
 * y las estrellas chicas titilan a su ritmo (como las neuronas). Quieta y de frente cuando la IA no trabaja.
 * Dibujado por la app (no usa imágenes).
 */
class StarsDrawable(@Suppress("UNUSED_PARAMETER") accent: Int, private val thinking: () -> Boolean) : Drawable() {
    private companion object {
        // x, y, radio, grosor — en la misma cuadrícula de 108 que el ícono (ic_launcher_foreground.xml)
        val STARS = floatArrayOf(
            48f, 58f, 22f, 0.17f,      // grande
            67f, 38f, 7f, 0.14f,
            60.5f, 33f, 2.2f, 0.12f,
            75.5f, 49.5f, 3.6f, 0.12f,
            31f, 36f, 3.8f, 0.13f,
            41f, 29f, 2.0f, 0.12f,
            81f, 64f, 4.6f, 0.13f,
            70f, 77f, 3.0f, 0.12f,
            31f, 73f, 2.6f, 0.12f,
            82f, 41.5f, 2.0f, 0.12f
        )
        const val MID_X = 56f; const val MID_Y = 53.5f; const val HALF = 30f   // centro y medio ancho del dibujo
        val C1 = Color.parseColor("#18E6FF")   // celeste
        val C2 = Color.parseColor("#FF16E6")   // magenta
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val camera = android.graphics.Camera()
    private val matrix = android.graphics.Matrix()
    private var shaderFor = android.graphics.Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val cx = b.left + w / 2f; val cy = b.top + h / 2f
        val k = size * 0.5f / HALF                    // de la cuadrícula del ícono a píxeles
        fun px(x: Float) = cx + (x - MID_X) * k
        fun py(y: Float) = cy + (y - MID_Y) * k

        // Degradado celeste → magenta (arriba a la izquierda → abajo a la derecha), igual que el ícono
        if (shaderFor != b) {
            shaderFor = android.graphics.Rect(b)
            paint.shader = android.graphics.LinearGradient(px(33f), py(43f), px(63f), py(73f), C1, C2,
                android.graphics.Shader.TileMode.CLAMP)
        }

        val active = thinking()
        val phase = if (active) AiActivity.spinPhase() else 0f
        if (active) {
            // ✨ Mientras la IA piensa: una fila de estrellas que avanza (como la animación de Qwen)
            conveyor(canvas, cx, cy, size, phase)
            invalidateSelf()   // siguiente cuadro de la animación
            return
        }
        canvas.save()
        if (active) {
            // 🔄 Giro 3D sobre el eje vertical (una vuelta completa por fase)
            camera.save()
            camera.setLocation(0f, 0f, -12f)
            camera.rotateY(phase * 360f)
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-cx, -cy)
            matrix.postTranslate(cx, cy)
            canvas.concat(matrix)
        }
        var i = 0
        while (i < STARS.size) {
            val n = i / 4
            // ✨ Las chicas titilan desfasadas mientras la IA piensa; la grande queda fija
            val tw = if (active && n > 0) 0.72f + 0.28f * ((kotlin.math.sin((phase * 2f + n / 3f) * 2.0 * Math.PI) + 1.0) / 2.0).toFloat() else 1f
            star(canvas, px(STARS[i]), py(STARS[i + 1]), STARS[i + 2] * k * tw, STARS[i + 3])
            i += 4
        }
        canvas.restore()
        if (active) invalidateSelf()   // siguiente cuadro de la animación
    }

    /**
     * ✨ FILA DE ESTRELLAS QUE AVANZA (mientras la IA piensa):
     * las estrellas entran chiquitas por la izquierda, crecen, la del centro es la más grande y brillante
     * (con sus puntas bien marcadas), y salen achicándose por la derecha. Las de los lados son más redondas.
     * Avanzan más rápido cuando la IA piensa rápido y más lento cuando piensa lento (AiActivity.spinPhase()).
     */
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var rowShaderFor = android.graphics.Rect()

    private fun conveyor(canvas: Canvas, cx: Float, cy: Float, size: Float, phase: Float) {
        val sp = size * 0.34f                                    // distancia entre estrellas
        if (rowShaderFor != bounds) {
            rowShaderFor = android.graphics.Rect(bounds)
            rowPaint.shader = android.graphics.LinearGradient(cx - sp * 1.75f, 0f, cx + sp * 1.75f, 0f, C1, C2,
                android.graphics.Shader.TileMode.CLAMP)
        }
        for (j in -3..2) {
            val x = (j + phase) * sp                             // avanzan hacia la derecha
            val dist = kotlin.math.abs(x) / sp                   // 0 = centro
            val fade = ((1.75f - dist) / 0.55f).coerceIn(0f, 1f)  // aparecen y desaparecen suave en los bordes
            if (fade <= 0f) continue
            val bell = kotlin.math.exp(-dist * dist * 2.4f)
            val r = size * (0.045f + 0.115f * bell) * fade
            val thick = 0.15f + 0.33f * minOf(dist, 1f)           // centro: estrella · lados: más redondas
            rowPaint.alpha = (255 * (0.45f + 0.55f * kotlin.math.exp(-dist * dist * 2.2f)) * fade * (alpha255 / 255f)).toInt()
            starWith(canvas, cx + x, cy, r, thick, rowPaint)
        }
    }

    private var alpha255 = 255

    private fun starWith(c: Canvas, x: Float, y: Float, r: Float, thick: Float, p: Paint) {
        val a = r * thick
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + a, y - a, x + r, y)
        path.quadTo(x + a, y + a, x, y + r)
        path.quadTo(x - a, y + a, x - r, y)
        path.quadTo(x - a, y - a, x, y - r)
        path.close()
        c.drawPath(path, p)
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float, thick: Float) {
        val a = r * thick   // qué tan curvas son las puntas
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + a, y - a, x + r, y)
        path.quadTo(x + a, y + a, x, y + r)
        path.quadTo(x - a, y + a, x - r, y)
        path.quadTo(x - a, y - a, x, y - r)
        path.close()
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; alpha255 = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; rowPaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 🧠 CEREBRO 3D azul brillante (como la foto): una nube de neuronas con forma de cerebro de verdad
 * (dos hemisferios, lóbulo temporal y cerebelo), sus pliegues y la red que las une.
 * Mientras la IA trabaja GIRA EN 3D sobre sí mismo, como una botella girando sobre la mesa:
 * lo de adelante brilla más y lo de atrás se ve tenue (así no parece de papel).
 * Gira más rápido cuando la IA piensa rápido y más lento cuando piensa lento (AiActivity.spinPhase()).
 * Dibujado por la app (no usa imágenes).
 */
class BrainDrawable(@Suppress("UNUSED_PARAMETER") accent: Int, private val thinking: () -> Boolean) : Drawable() {
    private companion object {
        // Coordenadas 3D (x = costado, y = arriba, z = adelante), generadas una sola vez a partir de la silueta del cerebro
        const val P_DATA = "-0.736,0.415,-0.093,-0.52,0.09,0.829,-0.699,0.454,0.015,-0.737,0.211,-0.616,-0.4,0.598,0.254,-0.655,-0.21,-0.793,-0.312,0.266,-0.8,-0.507,-0.577,0.378,-0.083,0.094,0.942,-0.642,0.464,0.301,-0.459,-0.463,-0.669,-0.559,-0.556,0.055,-0.741,-0.231,-0.605,-0.465,-0.09,-0.918,-0.34,0.602,-0.116,-0.634,0.532,0.037,-0.803,-0.17,-0.083,-0.056,0.008,0.972,-0.443,0.435,0.665,-0.776,-0.225,-0.361,-0.585,-0.42,-0.412,-0.738,0.064,0.52,-0.664,0.049,0.677,-0.184,0.211,0.895,-0.258,-0.324,-0.976,-0.356,-0.045,0.939,-0.49,0.167,-0.834,-0.701,-0.167,0.544,-0.466,-0.46,-0.78,-0.619,-0.239,0.58,-0.801,0.175,0.116,-0.486,0.436,-0.604,-0.559,0.293,-0.721,-0.703,-0.012,-0.749,-0.278,0.535,-0.383,-0.805,0.043,-0.565,-0.504,0.376,0.692,-0.12,0.174,-0.781,-0.729,-0.068,0.533,-0.682,0.402,-0.502,-0.823,0.208,-0.251,-0.422,0.057,0.897,-0.297,0.593,0.146,-0.654,-0.352,-0.62,-0.262,0.339,0.798,-0.725,-0.289,-0.489,-0.184,0.456,0.468,-0.181,0.365,-0.593,-0.509,0.311,0.747,-0.632,-0.436,-0.153,-0.115,0.059,-0.901,-0.543,0.491,-0.511,-0.366,0.525,-0.475,-0.788,-0.203,0.189,-0.564,-0.325,-0.843,-0.494,-0.229,0.816,-0.391,-0.305,-0.945,-0.569,-0.486,-0.106,-0.78,-0.034,-0.633,-0.65,-0.502,0.141,-0.271,0.539,-0.269,-0.268,0.121,0.939,-0.665,0.411,0.374,-0.34,0.01,-0.935,-0.509,0.604,-0.035,-0.133,0.407,-0.354,-0.565,0.094,-0.83,-0.504,0.528,0.463,-0.205,0.455,0.573,-0.586,0.543,-0.29,-0.422,0.616,-0.162,-0.801,-0,0.244,-0.664,0.483,0.162,-0.762,0.022,0.446,-0.651,0.393,0.459,-0.766,-0.28,-0.037,-0.737,0.252,0.388,-0.22,0.523,0.224,-0.491,-0.45,-0.477,-0.593,0.529,-0.389,-0.426,0.311,0.793,-0.699,0.129,-0.711,-0.271,0.444,0.659,-0.762,0.254,0.246,-0.397,-0.476,-0.831,-0.411,-0.362,0.729,-0.421,0.573,-0.373,-0.343,-0.401,-0.94,-0.637,-0.171,0.644,-0.786,0.149,0.252,-0.598,0.523,0.254,-0.698,-0.384,0.361,-0.718,-0.367,-0.049,-0.358,-0.145,-0.956,-0.829,-0.016,-0.445,-0.328,0.287,0.845,-0.734,0.419,-0.241,-0.308,0.406,0.741,-0.347,0.562,0.434,-0.509,0.572,0.277,-0.248,0.435,-0.56,-0.413,0.631,0.072,-0.635,-0.027,-0.815,-0.517,-0.542,-0.025,-0.487,-0.346,0.604,-0.613,-0.378,-0.702,-0.345,-0.297,0.878,-0.748,0.277,-0.544,-0.591,0.304,0.671,-0.839,0.064,-0.368,-0.726,-0.388,0.143,-0.773,0.304,0.07,-0.504,-0.612,0.206,-0.803,0.224,-0.451,-0.638,0.356,-0.603,-0.426,-0.29,0.831,-0.45,-0.45,0.546,-0.773,-0.135,0.365,-0.675,0.479,-0.283,-0.425,0.289,-0.772,-0.69,-0.435,0.057,-0.572,-0.09,0.787,-0.488,-0.299,0.757,-0.584,0.576,-0.19,-0.226,0.511,0.356,-0.554,-0.425,-0.608,-0.118,0.33,0.665,-0.138,0.128,-0.856,-0.816,0.231,-0.338,-0.814,0.143,0.03,-0.768,0.115,-0.623,-0.173,0.138,0.943,-0.238,0.475,-0.472,-0.697,0.102,0.615,-0.427,0.575,0.381,-0.819,0.047,0.065,-0.184,0.02,0.977,-0.226,-0.267,0.938,-0.825,0.084,-0.476,-0.494,-0.369,-0.878,-0.628,-0.425,-0.243,-0.816,-0.135,-0.263,-0.701,0.378,0.303,-0.435,0.39,-0.671,-0.5,-0.525,0.485,-0.189,0.192,-0.833,-0.805,0.265,-0.138,-0.365,-0.169,0.925,-0.202,0.523,0.046,-0.806,0.1,0.161,0.67,0.307,0.543,0.07,0.021,0.972,0.708,-0.288,0.458,0.227,0.282,-0.755,0.577,0.438,0.556,0.808,-0.161,-0.275,0.687,0.38,0.362,0.633,0.513,0.179,0.658,-0.355,0.495,0.152,0.392,-0.491,0.196,0.3,0.812,0.356,-0.459,-0.9,0.824,0.136,-0.448,0.605,-0.548,0.241,0.576,-0.555,0.081,0.466,0.227,-0.808,0.81,0.259,-0.248,0.802,-0.048,-0.565,0.314,0.538,0.478,0.489,-0.427,-0.832,0.795,-0.197,-0.148,0.703,0.044,0.606,0.137,0.411,0.3,0.754,0.375,-0.341,0.207,0.474,0.481,0.835,0.035,-0.141,0.781,0.258,0.125,0.765,-0.186,0.362,0.521,-0.43,0.538,0.594,0.429,-0.56,0.63,-0.22,-0.817,0.521,0.53,0.44,0.681,-0.348,-0.424,0.524,0.569,-0.278,0.654,-0.402,-0.261,0.551,0.062,0.808,0.697,-0.058,0.592,0.838,0.1,-0.356,0.823,0.005,-0.492,0.84,0.022,-0.263,0.699,0.297,-0.596,0.596,0.314,0.657,0.44,0.422,-0.632,0.597,-0.126,0.744,0.41,-0.069,-0.934,0.716,0.427,0.086,0.315,0.16,0.911,0.49,-0.346,0.595,0.416,0.631,0.091,0.747,-0.303,0.291,0.447,0.489,-0.546,0.752,0.369,-0.012,0.501,0.427,0.637,0.314,0.436,0.691,0.225,0.428,0.652,0.536,0.601,0.047,0.705,0.137,0.591,0.687,-0.384,-0.155,0.13,0.386,0.471,0.715,-0.34,-0.243,0.795,0.091,-0.578,0.165,0.439,-0.406,0.536,0.264,-0.753,0.578,-0.039,-0.855,0.236,0.549,0.164,0.718,-0.271,-0.616,0.743,-0.351,0.185,0.762,-0.174,-0.616,0.671,0.218,0.62,0.272,0.496,-0.468,0.554,-0.065,0.809,0.177,0.499,0.093,0.758,-0.298,-0.034,0.134,0.099,-0.88,0.359,-0.144,-0.956,0.414,0.591,-0.251,0.634,0.441,0.417,0.637,-0.361,-0.67,0.591,0.224,0.722,0.758,0.326,-0.456,0.14,-0.065,-0.974,0.573,-0.511,0.423,0.803,0.235,-0.039,0.408,-0.323,0.814,0.62,-0.209,0.638,0.755,0.348,0.085,0.327,0.388,-0.669,0.826,-0.076,-0.369,0.534,0.596,-0.064,0.689,-0.065,-0.77,0.168,0.48,-0.075,0.782,-0.213,0.227,0.763,0.372,-0.242,0.611,0.555,-0.211,0.738,-0.273,-0.476,0.755,-0.106,0.452,0.426,0.159,0.87,0.551,-0.427,-0.702,0.515,0.555,0.347,0.608,-0.314,-0.801,0.59,-0.46,-0.182,0.625,0.538,0.023,0.504,-0.498,-0.172,0.184,0.14,0.942,0.648,0.454,0.313,0.416,-0.249,-0.941,0.634,0.511,-0.308,0.52,0.219,0.787,0.482,0.355,-0.7,0.303,0.037,0.951,0.188,0.371,-0.592,0.713,-0.404,0.248,0.549,0.588,0.149,0.218,0.498,-0.334,0.141,0.234,-0.735,0.779,-0.254,0.118,0.102,0.231,0.8,0.318,0.33,-0.737,0.423,-0.38,-0.914,0.708,0.457,-0.231,0.745,-0.035,-0.698,0.699,-0.436,0.136,0.808,-0.102,0.133,0.513,-0.597,0.277,0.645,-0.472,0.334,0.826,0.115,-0.059,0.553,0.56,0.242,0.43,0.535,0.504,0.828,0.178,-0.178,0.25,0.557,-0.132,0.565,-0.44,-0.35,0.707,0.039,-0.734,0.384,0.147,-0.875,0.571,-0.42,-0.516,0.289,0.555,0.324,0.399,0.615,-0.154,0.655,0.166,-0.735,0.272,-0.321,0.881,0.805,-0.111,-0.5,0.275,0.373,0.772,0.533,-0.599,0.167,0.795,0.289,-0.375,0.524,-0.239,0.775,0.711,-0.375,-0.054,0.271,0.416,-0.603,0.192,0.477,0.383,0.685,-0.376,0.411,0.399,0.584,0.322,0.387,0.272,0.834,0.642,0.473,-0.436"
        const val B_DATA = "-0.43,0.496,0.238,-0.277,0.248,0.856,-0.465,-0.237,-0.911,-0.293,0.119,0.288,-0.421,-0.366,-0.06,-0.233,-0.023,0.143,-0.145,0.255,-0.426,-0.353,0.135,-0.658,-0.353,-0.112,0.227,-0.294,-0.161,0.718,-0.134,0.021,0.933,-0.394,0.004,0.893,-0.133,0.139,-0.571,-0.349,-0.14,-0.268,-0.386,-0.378,-0.841,-0.23,-0.121,-0.96,-0.32,0.366,-0.368,-0.749,0.006,-0.356,-0.235,-0.133,-0.857,-0.336,0.113,0.483,-0.542,-0.165,0.557,-0.131,-0.245,-0.949,-0.187,-0.257,0.88,-0.144,0.045,0.831,-0.763,0.117,-0.281,-0.366,0.245,-0.758,-0.064,-0.056,0.696,-0.476,-0.338,-0.791,-0.418,-0.277,0.809,-0.505,-0.303,0.087,-0.267,-0.103,-0.624,-0.243,-0.007,0.366,-0.109,-0.139,-0.873,-0.408,-0.349,-0.182,-0.557,-0.024,0.631,-0.342,0.224,-0.012,-0.133,0.127,0.448,-0.202,-0.102,0.557,-0.083,0.046,-0.091,-0.329,-0.101,0.921,-0.589,-0.044,0.444,-0.286,-0.208,-0.186,-0.499,-0.151,-0.001,-0.359,0.47,-0.44,-0.358,0.475,-0.268,-0.559,0.398,0.376,-0.493,0.319,-0.264,-0.388,0.482,-0.028,-0.144,-0.033,0.156,-0.548,0.317,-0.482,-0.531,0.34,0.612,-0.635,0.384,-0.294,-0.523,-0.244,0.635,-0.437,0.36,0.715,-0.226,0.31,-0.646,-0.541,0.464,-0.044,-0.251,-0.414,0.446,-0.542,0.333,0.156,-0.542,0.267,-0.114,-0.478,0.114,0.36,-0.409,-0.139,-0.055,-0.446,-0.226,-0.709,-0.166,-0.067,-0.593,-0.657,0.309,-0.232,-0.349,0.093,-0.507,-0.476,-0.283,-0.183,-0.269,0.131,-0.744,-0.34,-0.101,-0.897,-0.573,-0.364,0.087,-0.185,0.112,0.886,-0.489,0.255,0.605,-0.598,0.122,-0.692,-0.361,0.124,-0.288,-0.59,0.033,-0.758,-0.602,0.22,-0.695,-0.611,-0.057,-0.24,-0.193,0.106,-0.403,-0.587,-0.413,-0.163,-0.32,-0.182,-0.942,-0.22,-0.336,-0.449,-0.638,0.265,-0.4,-0.657,0.282,0.28,-0.648,-0.062,0.058,-0.476,0.081,0.474,-0.767,-0.084,-0.479,-0.388,0.039,0.75,-0.566,0.276,0.403,-0.421,-0.154,0.796,-0.584,-0.139,0.376,-0.257,0.137,0.543,-0.683,0.152,-0.042,-0.265,-0.259,-0.327,-0.295,0.368,0.618,-0.287,0.073,-0.918,-0.302,-0.269,0.662,-0.271,0.075,0.875,-0.285,0.158,-0.401,-0.557,-0.145,0.214,-0.319,-0.021,-0.234,-0.435,0.541,0.146,-0.56,-0.152,-0.707,-0.766,-0.044,0.194,-0.134,-0.093,-0.243,-0.489,0.08,0.794,-0.619,0.121,-0.458,-0.641,-0.251,-0.514,-0.471,0.316,-0.62,-0.397,0.005,-0.802,-0.493,-0.009,0.729,-0.101,0.003,0.573,-0.157,-0.398,-0.863,-0.381,-0.232,0.35,-0.266,0.36,-0.129,-0.699,0.215,0.179,-0.421,0.163,-0.037,-0.199,0.31,-0.058,-0.573,0.007,-0.138,-0.276,-0.252,-0.668,-0.309,0.211,-0.221,-0.455,-0.169,-0.476,-0.405,-0.284,-0.412,-0.283,0.11,-0.055,-0.482,-0.451,0.326,-0.306,-0.227,0.443,-0.241,-0.279,-0.919,-0.197,0.111,-0.191,-0.642,-0,-0.364,-0.425,0.231,0.449,-0.591,0.416,-0.502,-0.729,-0.245,0.282,0.067,-0.285,-0.955,0.159,-0.055,-0.174,0.269,-0.041,0.947,0.093,-0.246,-0.786,0.313,0.098,-0.017,0.194,-0.06,-0.496,0.318,-0.354,-0.851,0.323,0.009,-0.375,0.163,-0.202,0.912,0.568,0.094,-0.189,0.212,-0.173,-0.36,0.472,0.149,0.815,0.64,0.181,-0.026,0.466,0.523,0.242,0.758,-0.083,-0.372,0.258,-0.151,-0.534,0.307,0.207,-0.798,0.415,-0.196,0.149,0.236,-0.09,0.388,0.532,-0.079,-0.414,0.559,-0.366,-0.154,0.183,0.295,0.108,0.589,0.16,0.502,0.533,0.376,0.613,0.452,0.274,0.441,0.463,-0.057,0.604,0.247,-0.291,-0.84,0.432,-0.194,0.34,0.474,0.061,0.51,0.12,0.121,0.696,0.63,-0.094,-0.52,0.14,0.023,-0.328,0.375,0.198,0.632,0.274,-0.405,0.059,0.119,0.225,-0.104,0.496,-0.402,-0.738,0.662,-0.106,-0.075,0.155,0.155,0.517,0.079,-0.091,0.925,0.248,0.27,-0.035,0.448,-0.196,-0.55,0.618,-0.387,0.121,0.542,-0.163,-0.5,0.118,0.095,0.933,0.542,-0.158,0.229,0.448,0.038,0.641,0.159,-0.226,-0.639,0.235,-0.293,0.774,0.576,0.464,-0.34,0.342,0.333,0.238,0.346,-0.202,-0.948,0.569,-0.169,-0.61,0.497,0.373,-0.046,0.528,0.346,0.373,0.534,-0.176,0.578,0.603,0.032,0.347,0.181,-0.229,-0.817,0.4,0.489,0.155,0.241,0.171,-0.374,0.127,-0.209,0.418,0.084,0.129,0.329,0.244,0.39,0.363,0.347,-0.417,0.215,0.493,-0.075,0.085,0.298,-0.321,-0.212,0.188,-0.215,-0.219,0.471,0.368,0.103,0.282,-0.192,0.748,0.682,0.347,-0.449,0.605,0.24,-0.643,0.166,0.279,0.639,0.245,0.049,0.204,0.137,-0.318,-0.908,0.345,-0.263,0.2,0.601,0.377,0.183,0.531,0.413,-0.524,0.28,-0.122,-0.045,0.267,-0.134,-0.664,0.165,-0.317,0.224,0.268,0.424,0.457,0.099,0.234,0.412,0.737,-0.056,-0.243,0.198,-0.174,0.47,0.076,0.149,-0.009,0.462,0.52,-0.282,0.501,-0.302,-0.228,0.336,0.396,0.381,0.611,-0.32,-0.395,0.188,-0.098,0.037,0.489,0.169,-0.507,0.153,-0.125,0.839,0.088,0.047,0.601,0.23,0.021,-0.269,0.632,-0.221,-0.081,0.252,-0.453,-0.864,0.566,0.014,-0.111,0.129,0.158,-0.427,0.614,-0.117,0.539,0.073,-0.21,-0.897,0.19,0.391,-0.168,0.473,-0.24,0.803,0.271,0.142,0.496,0.638,-0.006,-0.443,0.29,-0.189,0.322,0.213,0.116,0.769,0.145,0.01,-0.931,0.222,0.016,0.813,0.594,-0.32,0.491,0.725,0.006,-0.673,0.571,-0.264,0.278,0.744,0.293,-0.043,0.608,0.135,-0.501,0.44,0.256,0.088,0.453,0.166,-0.036,0.383,0.173,0.214,0.631,0.465,-0.097,0.748,0.131,-0.251,0.057,0.067,0.846,0.25,-0.344,-0.383,0.567,-0.211,-0.817,0.693,-0.331,0.056,0.757,0.195,-0.388,0.526,-0.279,-0.7,0.352,-0.381,0.127,0.206,-0.003,-0.689,0.361,0.442,-0.434,0.285,0.365,-0.46,0.572,0.255,-0.215,0.246,0.262,-0.458,0.313,0.082,-0.867"
        const val E_DATA = "0,2,0,96,0,146,1,22,1,41,1,121,2,15,2,72,2,111,3,81,3,107,3,130,4,42,4,99,4,101,4,134,5,54,5,102,5,105,6,26,6,119,6,145,7,112,7,116,7,144,8,17,8,23,8,131,8,136,8,151,9,62,9,72,9,74,9,90,9,142,10,28,10,78,10,84,10,125,11,59,11,103,11,112,12,43,12,45,12,58,13,63,13,93,13,102,14,60,14,64,14,70,14,148,15,64,15,72,16,75,16,92,16,141,17,136,17,151,18,36,18,48,18,82,18,97,18,108,19,45,19,94,19,141,20,78,20,125,20,140,21,22,21,38,21,73,21,133,22,38,22,133,23,61,23,131,24,56,24,87,24,93,25,41,25,61,25,147,26,32,26,66,26,119,27,29,27,38,27,88,28,84,28,139,29,88,29,104,30,89,30,111,30,129,30,135,30,149,31,51,31,114,31,143,32,114,32,119,32,143,33,58,33,81,33,102,34,52,34,60,34,65,34,86,34,132,35,58,35,94,35,130,35,138,36,48,36,80,36,108,37,50,37,127,37,145,38,73,38,117,38,133,39,51,39,107,39,114,40,109,40,128,40,146,41,61,42,77,42,101,42,148,43,45,43,105,43,125,44,80,44,95,44,97,44,126,46,68,46,98,46,124,47,65,47,100,47,132,48,80,48,108,49,57,49,92,49,103,49,140,50,63,50,127,50,145,51,79,52,86,52,100,52,132,53,71,53,110,53,117,54,105,54,139,55,115,55,121,55,122,56,87,56,93,56,139,57,103,57,140,58,130,59,91,59,110,59,112,59,120,60,65,60,86,61,131,61,136,62,74,62,76,62,142,63,93,64,70,64,101,65,132,66,81,66,102,67,98,67,99,67,134,68,82,68,126,69,79,69,118,69,123,70,123,71,89,71,117,71,149,72,90,73,117,74,142,75,92,75,120,76,83,76,142,77,124,77,148,78,125,79,86,79,118,80,95,81,130,82,97,82,126,83,89,83,142,84,87,84,139,85,104,85,115,85,116,85,122,88,121,89,149,90,99,91,110,92,110,92,120,94,109,94,138,94,141,95,97,96,118,96,146,98,124,98,134,99,134,100,132,104,116,104,122,104,144,105,125,106,115,106,137,106,147,107,113,107,114,109,128,109,138,110,120,111,129,113,128,113,138,115,122,115,137,115,147,116,144,118,123,119,143,127,145,129,135,129,149,131,136,135,149,137,147,150,154,150,156,150,191,150,206,150,218,151,253,152,158,152,177,152,245,152,296,153,223,153,236,153,264,153,267,154,181,154,202,154,226,155,170,155,189,155,237,156,226,156,254,157,195,157,254,157,262,157,276,158,178,158,197,158,296,159,211,159,219,159,260,159,263,160,266,160,289,160,298,161,169,161,255,161,268,162,187,162,188,162,210,163,164,163,273,163,274,163,290,164,271,164,290,165,212,165,258,165,282,166,242,166,278,166,291,167,188,167,210,167,217,167,270,167,288,168,174,168,277,168,284,169,247,169,268,170,209,170,222,171,186,171,206,171,218,172,174,172,208,172,221,172,295,173,229,173,242,173,291,173,299,174,204,174,208,174,295,175,189,175,275,175,278,176,201,176,232,176,235,177,199,177,241,177,245,177,272,178,197,178,231,179,190,179,192,179,200,179,299,180,213,180,239,180,249,181,226,181,248,181,277,182,209,182,244,182,280,182,283,183,225,183,243,183,256,184,207,184,209,184,250,184,252,184,280,185,220,185,246,185,257,186,234,186,245,187,188,187,189,188,210,188,288,189,237,190,229,190,286,191,202,191,218,191,228,192,200,192,236,192,258,193,220,193,234,193,292,194,213,194,224,194,255,194,282,195,201,195,235,195,251,196,246,196,253,196,259,196,298,197,233,197,234,198,205,198,214,198,238,198,262,199,216,199,241,199,261,200,219,201,232,201,235,202,203,202,277,203,204,203,289,204,289,205,238,205,251,205,262,206,218,207,209,207,250,207,293,208,295,209,293,211,219,211,263,212,258,212,282,212,286,213,239,214,221,214,240,214,284,215,217,215,227,215,244,216,261,216,265,216,271,217,244,217,288,218,228,219,263,219,294,220,292,221,240,222,265,222,293,223,230,223,264,224,230,224,255,225,243,225,279,225,285,226,254,227,247,227,249,227,283,228,257,229,291,231,273,231,274,231,296,232,275,232,278,233,287,233,292,234,292,236,258,236,260,236,267,236,294,237,288,238,251,238,285,239,270,239,281,240,279,241,265,241,272,242,269,243,256,243,269,246,257,246,259,246,298,247,249,248,276,248,277,248,297,250,252,252,280,253,259,253,266,255,268,256,269,256,299,257,298,260,294,261,271,261,274,262,276,264,267,265,272,266,289,267,294,270,281,273,290,274,296,275,278,276,297,279,285,280,283,281,286,284,295,284,297"
        const val C_DATA = "-0.508,0.606,0.149,-0.627,0.511,0.195,-0.627,0.511,0.195,-0.711,0.399,0.167,-0.711,0.399,0.167,-0.738,0.305,0.248,-0.738,0.305,0.248,-0.776,0.184,0.231,-0.776,0.184,0.231,-0.778,0.103,0.307,-0.778,0.103,0.307,-0.784,0,0.309,-0.553,0.574,-0.234,-0.689,0.484,-0.188,-0.689,0.484,-0.188,-0.757,0.377,-0.216,-0.757,0.377,-0.216,-0.79,0.293,-0.145,-0.79,0.293,-0.145,-0.818,0.186,-0.174,-0.818,0.186,-0.174,-0.824,0.1,-0.108,-0.824,0.1,-0.108,-0.824,0,-0.117,-0.283,0.277,0.872,-0.482,0.288,0.776,-0.482,0.288,0.776,-0.474,0.376,0.713,-0.474,0.376,0.713,-0.623,0.308,0.622,-0.623,0.308,0.622,-0.649,0.346,0.527,-0.649,0.346,0.527,-0.687,0.342,0.424,-0.687,0.342,0.424,-0.665,0.415,0.34,-0.31,0.043,0.947,-0.488,0.043,0.858,-0.488,0.043,0.858,-0.545,0.122,0.796,-0.545,0.122,0.796,-0.636,0.043,0.71,-0.636,0.043,0.71,-0.695,0.069,0.609,-0.695,0.069,0.609,-0.729,0.049,0.529,-0.729,0.049,0.529,-0.737,0.132,0.471,-0.737,0.132,0.471,-0.758,0.138,0.383,-0.457,-0.149,0.872,-0.551,-0.142,0.791,-0.551,-0.142,0.791,-0.613,-0.076,0.731,-0.613,-0.076,0.731,-0.63,-0.147,0.669,-0.63,-0.147,0.669,-0.656,-0.16,0.596,-0.79,0.298,-0.234,-0.785,0.3,-0.317,-0.785,0.3,-0.317,-0.745,0.375,-0.374,-0.745,0.375,-0.374,-0.771,0.286,-0.457,-0.771,0.286,-0.457,-0.722,0.289,-0.565,-0.722,0.289,-0.565,-0.7,0.215,-0.653,-0.7,0.215,-0.653,-0.57,0.213,-0.766,-0.831,0.106,-0.298,-0.829,0.078,-0.392,-0.829,0.078,-0.392,-0.81,0.119,-0.491,-0.81,0.119,-0.491,-0.793,0.037,-0.577,-0.793,0.037,-0.577,-0.735,0.043,-0.689,-0.735,0.043,-0.689,-0.668,-0.002,-0.777,-0.668,-0.002,-0.777,-0.518,0.021,-0.872,-0.632,-0.436,0.436,-0.673,-0.42,0.353,-0.673,-0.42,0.353,-0.722,-0.346,0.296,-0.722,-0.346,0.296,-0.736,-0.349,0.204,-0.736,-0.349,0.204,-0.756,-0.301,0.129,-0.756,-0.301,0.129,-0.739,-0.337,0.041,-0.739,-0.337,0.041,-0.755,-0.287,-0.041,-0.755,-0.287,-0.041,-0.737,-0.308,-0.14,-0.737,-0.308,-0.14,-0.767,-0.245,-0.222,-0.767,-0.245,-0.222,-0.763,-0.245,-0.319,-0.566,-0.543,0.362,-0.599,-0.538,0.277,-0.599,-0.538,0.277,-0.669,-0.473,0.212,-0.669,-0.473,0.212,-0.655,-0.488,0.121,-0.655,-0.488,0.121,-0.681,-0.436,0.045,-0.681,-0.436,0.045,-0.626,-0.468,-0.045,-0.626,-0.468,-0.045,-0.657,-0.415,-0.125,-0.657,-0.415,-0.125,-0.626,-0.425,-0.24,-0.626,-0.425,-0.24,-0.666,-0.372,-0.34,-0.67,-0.351,-0.468,-0.661,-0.351,-0.528,-0.661,-0.351,-0.528,-0.691,-0.308,-0.578,-0.691,-0.308,-0.578,-0.671,-0.323,-0.642,-0.671,-0.323,-0.642,-0.671,-0.3,-0.695,-0.671,-0.3,-0.695,-0.625,-0.336,-0.747,-0.625,-0.336,-0.747,-0.6,-0.313,-0.806,-0.6,-0.313,-0.806,-0.529,-0.349,-0.858,-0.529,-0.349,-0.858,-0.443,-0.34,-0.915,-0.585,-0.415,-0.468,-0.563,-0.422,-0.523,-0.563,-0.422,-0.523,-0.615,-0.384,-0.572,-0.615,-0.384,-0.572,-0.585,-0.406,-0.63,-0.585,-0.406,-0.63,-0.624,-0.368,-0.679,-0.624,-0.368,-0.679,-0.565,-0.406,-0.743,-0.565,-0.406,-0.743,-0.547,-0.384,-0.81,-0.547,-0.384,-0.81,-0.457,-0.422,-0.87,-0.457,-0.422,-0.87,-0.334,-0.415,-0.936,-0.483,0.553,0.426,-0.56,0.493,0.457,-0.56,0.493,0.457,-0.64,0.422,0.426,-0.64,0.422,0.426,-0.668,0.364,0.445,-0.668,0.364,0.445,-0.702,0.319,0.404,-0.463,0.596,0.298,-0.549,0.537,0.335,-0.549,0.537,0.335,-0.625,0.468,0.34,-0.574,0.543,-0.34,-0.658,0.489,-0.308,-0.658,0.489,-0.308,-0.715,0.426,-0.309,-0.591,0.5,-0.447,-0.693,0.436,-0.407,-0.693,0.436,-0.407,-0.748,0.362,-0.404,-0.56,0.415,-0.596,-0.679,0.366,-0.547,-0.679,0.366,-0.547,-0.737,0.298,-0.532,-0.615,0.191,0.702,-0.671,0.183,0.625,-0.671,0.183,0.625,-0.696,0.232,0.556,-0.696,0.232,0.556,-0.724,0.164,0.498,-0.724,0.164,0.498,-0.747,0.149,0.426,-0.72,-0.043,-0.723,-0.687,-0.098,-0.766,-0.687,-0.098,-0.766,-0.607,-0.114,-0.84,-0.607,-0.114,-0.84,-0.473,-0.106,-0.915,-0.583,-0.255,0.638,-0.593,-0.261,0.594,-0.593,-0.261,0.594,-0.63,-0.229,0.555,-0.63,-0.229,0.555,-0.695,-0.234,0.511,0.508,0.606,0.149,0.627,0.511,0.195,0.627,0.511,0.195,0.711,0.399,0.167,0.711,0.399,0.167,0.738,0.305,0.248,0.738,0.305,0.248,0.776,0.184,0.231,0.776,0.184,0.231,0.778,0.103,0.307,0.778,0.103,0.307,0.784,0,0.309,0.553,0.574,-0.234,0.689,0.484,-0.188,0.689,0.484,-0.188,0.757,0.377,-0.216,0.757,0.377,-0.216,0.79,0.293,-0.145,0.79,0.293,-0.145,0.818,0.186,-0.174,0.818,0.186,-0.174,0.824,0.1,-0.108,0.824,0.1,-0.108,0.824,0,-0.117,0.283,0.277,0.872,0.482,0.288,0.776,0.482,0.288,0.776,0.474,0.376,0.713,0.474,0.376,0.713,0.623,0.308,0.622,0.623,0.308,0.622,0.649,0.346,0.527,0.649,0.346,0.527,0.687,0.342,0.424,0.687,0.342,0.424,0.665,0.415,0.34,0.31,0.043,0.947,0.488,0.043,0.858,0.488,0.043,0.858,0.545,0.122,0.796,0.545,0.122,0.796,0.636,0.043,0.71,0.636,0.043,0.71,0.695,0.069,0.609,0.695,0.069,0.609,0.729,0.049,0.529,0.729,0.049,0.529,0.737,0.132,0.471,0.737,0.132,0.471,0.758,0.138,0.383,0.457,-0.149,0.872,0.551,-0.142,0.791,0.551,-0.142,0.791,0.613,-0.076,0.731,0.613,-0.076,0.731,0.63,-0.147,0.669,0.63,-0.147,0.669,0.656,-0.16,0.596,0.79,0.298,-0.234,0.785,0.3,-0.317,0.785,0.3,-0.317,0.745,0.375,-0.374,0.745,0.375,-0.374,0.771,0.286,-0.457,0.771,0.286,-0.457,0.722,0.289,-0.565,0.722,0.289,-0.565,0.7,0.215,-0.653,0.7,0.215,-0.653,0.57,0.213,-0.766,0.831,0.106,-0.298,0.829,0.078,-0.392,0.829,0.078,-0.392,0.81,0.119,-0.491,0.81,0.119,-0.491,0.793,0.037,-0.577,0.793,0.037,-0.577,0.735,0.043,-0.689,0.735,0.043,-0.689,0.668,-0.002,-0.777,0.668,-0.002,-0.777,0.518,0.021,-0.872,0.632,-0.436,0.436,0.673,-0.42,0.353,0.673,-0.42,0.353,0.722,-0.346,0.296,0.722,-0.346,0.296,0.736,-0.349,0.204,0.736,-0.349,0.204,0.756,-0.301,0.129,0.756,-0.301,0.129,0.739,-0.337,0.041,0.739,-0.337,0.041,0.755,-0.287,-0.041,0.755,-0.287,-0.041,0.737,-0.308,-0.14,0.737,-0.308,-0.14,0.767,-0.245,-0.222,0.767,-0.245,-0.222,0.763,-0.245,-0.319,0.566,-0.543,0.362,0.599,-0.538,0.277,0.599,-0.538,0.277,0.669,-0.473,0.212,0.669,-0.473,0.212,0.655,-0.488,0.121,0.655,-0.488,0.121,0.681,-0.436,0.045,0.681,-0.436,0.045,0.626,-0.468,-0.045,0.626,-0.468,-0.045,0.657,-0.415,-0.125,0.657,-0.415,-0.125,0.626,-0.425,-0.24,0.626,-0.425,-0.24,0.666,-0.372,-0.34,0.67,-0.351,-0.468,0.661,-0.351,-0.528,0.661,-0.351,-0.528,0.691,-0.308,-0.578,0.691,-0.308,-0.578,0.671,-0.323,-0.642,0.671,-0.323,-0.642,0.671,-0.3,-0.695,0.671,-0.3,-0.695,0.625,-0.336,-0.747,0.625,-0.336,-0.747,0.6,-0.313,-0.806,0.6,-0.313,-0.806,0.529,-0.349,-0.858,0.529,-0.349,-0.858,0.443,-0.34,-0.915,0.585,-0.415,-0.468,0.563,-0.422,-0.523,0.563,-0.422,-0.523,0.615,-0.384,-0.572,0.615,-0.384,-0.572,0.585,-0.406,-0.63,0.585,-0.406,-0.63,0.624,-0.368,-0.679,0.624,-0.368,-0.679,0.565,-0.406,-0.743,0.565,-0.406,-0.743,0.547,-0.384,-0.81,0.547,-0.384,-0.81,0.457,-0.422,-0.87,0.457,-0.422,-0.87,0.334,-0.415,-0.936,0.483,0.553,0.426,0.56,0.493,0.457,0.56,0.493,0.457,0.64,0.422,0.426,0.64,0.422,0.426,0.668,0.364,0.445,0.668,0.364,0.445,0.702,0.319,0.404,0.463,0.596,0.298,0.549,0.537,0.335,0.549,0.537,0.335,0.625,0.468,0.34,0.574,0.543,-0.34,0.658,0.489,-0.308,0.658,0.489,-0.308,0.715,0.426,-0.309,0.591,0.5,-0.447,0.693,0.436,-0.407,0.693,0.436,-0.407,0.748,0.362,-0.404,0.56,0.415,-0.596,0.679,0.366,-0.547,0.679,0.366,-0.547,0.737,0.298,-0.532,0.615,0.191,0.702,0.671,0.183,0.625,0.671,0.183,0.625,0.696,0.232,0.556,0.696,0.232,0.556,0.724,0.164,0.498,0.724,0.164,0.498,0.747,0.149,0.426,0.72,-0.043,-0.723,0.687,-0.098,-0.766,0.687,-0.098,-0.766,0.607,-0.114,-0.84,0.607,-0.114,-0.84,0.473,-0.106,-0.915,0.583,-0.255,0.638,0.593,-0.261,0.594,0.593,-0.261,0.594,0.63,-0.229,0.555,0.63,-0.229,0.555,0.695,-0.234,0.511"
        const val M_DATA = "-0.485,-0.372,0.553,-0.693,-0.306,0.465,-0.693,-0.306,0.465,-0.741,-0.2,0.418,-0.741,-0.2,0.418,-0.769,-0.194,0.292,-0.769,-0.194,0.292,-0.793,-0.12,0.198,-0.793,-0.12,0.198,-0.797,-0.147,0.073,-0.797,-0.147,0.073,-0.81,-0.098,-0.041,-0.81,-0.098,-0.041,-0.814,-0.098,-0.155,-0.814,-0.098,-0.155,-0.829,-0.024,-0.249,-0.829,-0.024,-0.249,-0.828,0.074,-0.404,-0.487,-0.455,-0.394,-0.606,-0.402,-0.471,-0.606,-0.402,-0.471,-0.69,-0.32,-0.523,-0.69,-0.32,-0.523,-0.68,-0.31,-0.642,-0.68,-0.31,-0.642,-0.673,-0.254,-0.741,-0.673,-0.254,-0.741,-0.563,-0.279,-0.856,-0.563,-0.279,-0.856,-0.315,-0.266,-0.968,-0.483,0.617,-0.064,-0.632,0.528,-0.013,-0.632,0.528,-0.013,-0.721,0.419,-0.035,-0.721,0.419,-0.035,-0.753,0.342,0.04,-0.753,0.342,0.04,-0.789,0.237,0.018,-0.789,0.237,0.018,-0.798,0.17,0.084,-0.798,0.17,0.084,-0.811,0.08,0.049,-0.811,0.08,0.049,-0.807,0.018,0.12,-0.807,0.018,0.12,-0.805,-0.064,0.117,0.485,-0.372,0.553,0.693,-0.306,0.465,0.693,-0.306,0.465,0.741,-0.2,0.418,0.741,-0.2,0.418,0.769,-0.194,0.292,0.769,-0.194,0.292,0.793,-0.12,0.198,0.793,-0.12,0.198,0.797,-0.147,0.073,0.797,-0.147,0.073,0.81,-0.098,-0.041,0.81,-0.098,-0.041,0.814,-0.098,-0.155,0.814,-0.098,-0.155,0.829,-0.024,-0.249,0.829,-0.024,-0.249,0.828,0.074,-0.404,0.487,-0.455,-0.394,0.606,-0.402,-0.471,0.606,-0.402,-0.471,0.69,-0.32,-0.523,0.69,-0.32,-0.523,0.68,-0.31,-0.642,0.68,-0.31,-0.642,0.673,-0.254,-0.741,0.673,-0.254,-0.741,0.563,-0.279,-0.856,0.563,-0.279,-0.856,0.315,-0.266,-0.968,0.483,0.617,-0.064,0.632,0.528,-0.013,0.632,0.528,-0.013,0.721,0.419,-0.035,0.721,0.419,-0.035,0.753,0.342,0.04,0.753,0.342,0.04,0.789,0.237,0.018,0.789,0.237,0.018,0.798,0.17,0.084,0.798,0.17,0.084,0.811,0.08,0.049,0.811,0.08,0.049,0.807,0.018,0.12,0.807,0.018,0.12,0.805,-0.064,0.117"
        val P: FloatArray by lazy { floats(P_DATA) }      // neuronas de la superficie
        val B: FloatArray by lazy { floats(B_DATA) }      // volumen (relleno azul)
        val E: IntArray by lazy { E_DATA.split(',').map { it.trim().toInt() }.toIntArray() }   // red entre neuronas
        val C: FloatArray by lazy { floats(C_DATA) }      // pliegues finos (tramos)
        val M: FloatArray by lazy { floats(M_DATA) }      // pliegues marcados (tramos)
        fun floats(s: String) = s.split(',').map { it.trim().toFloat() }.toFloatArray()
        const val BUCKETS = 4                              // capas de profundidad (atrás → adelante)
    }

    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var alphaAll = 255
    // Proyección de cada punto: x, y en pantalla y profundidad 0..1
    private val pp = FloatArray(P.size)
    private val bp = FloatArray(B.size)
    // Listas por capa de profundidad (se reutilizan en cada cuadro: sin crear memoria nueva)
    private val bufPts = Array(BUCKETS * 3) { FloatArray(P.size / 3 * 2 + B.size / 3 * 2) }
    private val cntPts = IntArray(BUCKETS * 3)
    private val bufLines = Array(BUCKETS) { FloatArray(maxOf(E.size * 2, C.size / 6 * 4, M.size / 6 * 4)) }
    private val cntLines = IntArray(BUCKETS)

    private var ca = 1f; private var sa = 0f; private var ct = 1f; private var st = 0f
    private var cx = 0f; private var cy = 0f; private var kk = 1f

    private fun project(src: FloatArray, i: Int, out: FloatArray, o: Int) {
        val x = src[i]; val y = src[i + 1]; val z = src[i + 2]
        val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca      // giro alrededor del eje vertical
        val y2 = y * ct - z1 * st; val z2 = y * st + z1 * ct     // se mira un poco desde arriba
        val f = 3.4f / (3.4f - z2)                               // perspectiva
        out[o] = cx + x1 * f * kk; out[o + 1] = cy - y2 * f * kk; out[o + 2] = ((z2 + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun bucket(d: Float) = (d * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)

    override fun draw(canvas: Canvas) {
        val bb = bounds
        val w = bb.width().toFloat(); val h = bb.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val active = thinking()
        val phase = if (active) AiActivity.spinPhase() else 0f
        // 🔄 Empieza de lado (la forma más reconocible) y da la vuelta completa
        val angle = (Math.PI / 2 + phase * 2.0 * Math.PI)
        ca = kotlin.math.cos(angle).toFloat(); sa = kotlin.math.sin(angle).toFloat()
        val tilt = Math.toRadians(14.0)
        ct = kotlin.math.cos(tilt).toFloat(); st = kotlin.math.sin(tilt).toFloat()
        cx = bb.left + w / 2f; cy = bb.top + h / 2f; kk = size * 0.43f

        var i = 0
        while (i < P.size) { project(P, i, pp, i); i += 3 }
        i = 0
        while (i < B.size) { project(B, i, bp, i); i += 3 }

        // 1) Cuerpo azul: manchas suaves (volumen + superficie); lo de adelante un poco más intenso
        pt.color = Color.rgb(25, 70, 255)
        splats(canvas, bp, size * 0.12f, 0.09f, 0.06f)
        splats(canvas, pp, size * 0.11f, 0.10f, 0.10f)

        // 2) Red de neuronas (líneas finas)
        cntLines.fill(0)
        var e = 0
        while (e < E.size) {
            val a = E[e] * 3; val c = E[e + 1] * 3
            val k = bucket((pp[a + 2] + pp[c + 2]) / 2f)
            val buf = bufLines[k]; var n = cntLines[k]
            buf[n++] = pp[a]; buf[n++] = pp[a + 1]; buf[n++] = pp[c]; buf[n++] = pp[c + 1]; cntLines[k] = n
            e += 2
        }
        for (k in 0 until BUCKETS) {
            val d = (k + 0.5f) / BUCKETS
            pt.color = Color.rgb(190, 230, 255); pt.alpha = ((0.06f + 0.40f * d * d) * alphaAll).toInt()
            pt.strokeWidth = maxOf(0.6f, size * 0.0035f)
            canvas.drawLines(bufLines[k], 0, cntLines[k], pt)
        }

        // 3) Pliegues del cerebro (finos y marcados)
        folds(canvas, C, size, 0.002f, 0.007f, Color.rgb(111, 198, 255))
        folds(canvas, M, size, 0.003f, 0.011f, Color.rgb(181, 236, 255))

        // 4) Neuronas: se encienden y apagan a su ritmo mientras la IA piensa
        cntPts.fill(0)
        i = 0
        while (i < pp.size) {
            val n = i / 3
            val g = n % 3                                        // 3 grupos que titilan desfasados
            val k = bucket(pp[i + 2]) * 3 + g
            val buf = bufPts[k]; var c = cntPts[k]
            buf[c++] = pp[i]; buf[c++] = pp[i + 1]; cntPts[k] = c
            i += 3
        }
        for (k in 0 until BUCKETS) for (g in 0 until 3) {
            val idx = k * 3 + g
            if (cntPts[idx] == 0) continue
            val d = (k + 0.5f) / BUCKETS
            val sp = if (active) ((kotlin.math.sin((phase * 2f + g / 3f) * 2.0 * Math.PI) + 1.0) / 2.0).toFloat() else 1f
            val r = size * (0.004f + 0.007f * d)
            pt.color = Color.rgb(160, 220, 255); pt.alpha = ((0.08f + 0.30f * d) * (0.5f + 0.5f * sp) * alphaAll).toInt()
            pt.strokeWidth = r * 4.8f
            canvas.drawPoints(bufPts[idx], 0, cntPts[idx], pt)
            pt.color = Color.WHITE; pt.alpha = ((0.15f + 0.85f * d * d) * (0.55f + 0.45f * sp) * alphaAll).toInt()
            pt.strokeWidth = r * 2f
            canvas.drawPoints(bufPts[idx], 0, cntPts[idx], pt)
        }
        if (active) invalidateSelf()   // siguiente cuadro de la animación
    }

    // Manchas redondas del relleno, agrupadas por profundidad
    private fun splats(canvas: Canvas, proj: FloatArray, diameter: Float, a0: Float, a1: Float) {
        for (k in 0 until BUCKETS) cntPts[k] = 0
        var i = 0
        while (i < proj.size) {
            val k = bucket(proj[i + 2])
            val buf = bufPts[k]; var c = cntPts[k]
            buf[c++] = proj[i]; buf[c++] = proj[i + 1]; cntPts[k] = c
            i += 3
        }
        pt.strokeWidth = diameter
        for (k in 0 until BUCKETS) {
            val d = (k + 0.5f) / BUCKETS
            pt.alpha = ((a0 + a1 * d) * alphaAll).toInt()
            canvas.drawPoints(bufPts[k], 0, cntPts[k], pt)
        }
    }

    // Pliegues: tramos agrupados por profundidad (adelante más gruesos y brillantes)
    private val tmp = FloatArray(6)
    private fun folds(canvas: Canvas, segs: FloatArray, size: Float, w0: Float, w1: Float, color: Int) {
        cntLines.fill(0)
        var i = 0
        while (i < segs.size) {
            project(segs, i, tmp, 0); project(segs, i + 3, tmp, 3)
            val k = bucket((tmp[2] + tmp[5]) / 2f)
            val buf = bufLines[k]; var n = cntLines[k]
            buf[n++] = tmp[0]; buf[n++] = tmp[1]; buf[n++] = tmp[3]; buf[n++] = tmp[4]; cntLines[k] = n
            i += 6
        }
        for (k in 0 until BUCKETS) {
            val d = (k + 0.5f) / BUCKETS
            pt.color = color; pt.alpha = ((0.05f + 0.85f * d * d * d) * alphaAll).toInt()
            pt.strokeWidth = size * (w0 + w1 * d)
            canvas.drawLines(bufLines[k], 0, cntLines[k], pt)
        }
    }

    override fun setAlpha(alpha: Int) { alphaAll = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { pt.colorFilter = colorFilter; invalidateSelf() }
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

/**
 * 🔋🌡️ Batería dibujada: el relleno muestra la carga y su COLOR la temperatura del teléfono
 * (verde < 38 °C · amarillo 38–41 · naranjo 42–44 · rojo 45 o más).
 */
class BatteryTempDrawable : Drawable() {
    companion object {
        fun colorFor(tempC: Float): Int = when {
            tempC < 38f -> Color.parseColor("#34C759")   // normal
            tempC < 42f -> Color.parseColor("#FFD60A")   // tibio
            tempC < 45f -> Color.parseColor("#FF9F0A")   // caliente
            else -> Color.parseColor("#FF453A")          // muy caliente
        }
    }
    private var temp = 30f
    private var level = 100
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#D1D1D6") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#D1D1D6") }

    fun update(tempC: Float, levelPct: Int) {
        temp = tempC
        level = if (levelPct in 0..100) levelPct else 100
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val sw = h * 0.12f
        stroke.strokeWidth = sw
        val tipW = w * 0.08f
        val body = android.graphics.RectF(b.left + sw / 2, b.top + sw / 2, b.right - tipW - sw / 2, b.bottom - sw / 2)
        val r = h * 0.22f
        canvas.drawRoundRect(body, r, r, stroke)
        // Polo de la batería
        canvas.drawRoundRect(android.graphics.RectF(body.right + sw * 0.4f, b.top + h * 0.32f, b.right.toFloat(), b.bottom - h * 0.32f), sw, sw, tip)
        // Relleno: la carga, con el color de la temperatura
        val pad = sw * 1.2f
        val inner = android.graphics.RectF(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        val wFill = (inner.width() * (level.coerceIn(8, 100) / 100f))
        fill.color = colorFor(temp)
        canvas.drawRoundRect(android.graphics.RectF(inner.left, inner.top, inner.left + wFill, inner.bottom), r * 0.5f, r * 0.5f, fill)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

