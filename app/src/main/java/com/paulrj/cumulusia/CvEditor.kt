package com.paulrj.cumulusia

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 📄 VENTANA «CURRÍCULUM» (se abre desde el ➕, como la maqueta).
 *
 * Aquí TÚ armas tu currículum, sin la IA: casillas para el título, las secciones (ANTECEDENTES PERSONALES…), cada
 * subtítulo (NOMBRE, RUT…) con su dato, y los trabajos (los años con la empresa). Puedes:
 *   🆕 empezar uno nuevo (ya trae todos los títulos y subtítulos, en tu idioma)
 *   📂 abrir uno de tu teléfono (PDF, Word, foto o texto): sus datos se ponen en las casillas para corregirlos
 *      (de una foto o un PDF escaneado los lee el lector de letras del teléfono, no la IA del chat)
 *   ➕ agregar, 🗑️ borrar y ↑↓ mover filas y secciones · 🎨 elegir colores
 *   💾 guardarlo en «Mis currículums» (dentro de Cumulus) · ⬇️ descargarlo en PDF, Word o imagen · 👁️ verlo antes
 *
 * Por dentro el currículum son líneas: «# TÍTULO», «## SECCIÓN», «SUBTÍTULO : dato», «: continuación del dato» y un
 * texto final (como «DISPONIBILIDAD INMEDIATA»). Así se arma con el mismo Word y PDF que ya usa Cumulus.
 */
object CvEditor {

    /** Lo que la ventana necesita de Cumulus (leer archivos, armar el PDF y el Word, guardar en el teléfono…). */
    interface Host {
        /** Texto de la ventana (escrito en español) → idioma de la app. */
        fun t(es: String): String
        /** Un currículum nuevo y vacío, con todos sus títulos y subtítulos en el idioma de la app. */
        fun template(): List<String>
        /** El currículum en PDF / Word / imagen. style = [color de títulos, de subtítulos, de datos, dónde va el texto final
         *  (0 izquierda · 1 centro · 2 derecha)]. */
        fun pdf(lines: List<String>, colors: IntArray): ByteArray
        fun docx(lines: List<String>, colors: IntArray): ByteArray
        fun png(lines: List<String>, colors: IntArray): ByteArray
        /** Lo guarda en la carpeta Descargas del teléfono. Devuelve dónde quedó, o null si no se pudo. */
        fun saveToPhone(bytes: ByteArray, name: String, mime: String): String?
        /** Abre el selector de archivos del teléfono (PDF, Word, imagen o texto). */
        fun pickFile(onPicked: (File) -> Unit)
        /** Lee un archivo (se llama en segundo plano): sus líneas y sus colores, o el motivo si no se pudo. */
        fun readFile(file: File): Triple<List<String>?, IntArray?, String?>
        /** Carpeta donde se guardan «Mis currículums». */
        fun storeDir(): File
        /** Que lo que escribes no se traduzca nunca. */
        fun noTranslate(v: View)
        fun light(): Boolean
        /** Las ventanitas (preguntas y listas) con el estilo de Cumulus: redondas, en modo claro u oscuro. */
        fun styleDialog(d: AlertDialog)
        /** Un botón con el estilo de CANCELAR y LISTO de «Editar personalidad». */
        fun button(label: String, onClick: () -> Unit): View
        /** Abre «Compartir» del teléfono con el archivo. false = no se pudo. */
        fun share(bytes: ByteArray, name: String, mime: String): Boolean
    }

    // ---------- El currículum ----------
    class Row(var label: String = "", var value: String = "")
    class Section(var title: String = "", val rows: MutableList<Row> = mutableListOf())
    class Cv(
        var title: String = "CURRICULUM",
        val sections: MutableList<Section> = mutableListOf(),
        var footer: String = "",
        /** ↔️ Dónde va el texto final (DISPONIBILIDAD INMEDIATA): 0 izquierda · 1 centro · 2 derecha (como antes) */
        var footerAlign: Int = 2,
        /** 🎨 títulos (y secciones) · subtítulos · datos */
        val colors: IntArray = intArrayOf(BLACK, BLACK, BLACK),
        /** El archivo en «Mis currículums» (vacío = todavía no se guarda). */
        var file: String = ""
    )

    private const val BLACK = 0xFF000000.toInt()

    /** 🎨 Los colores que se pueden elegir (los mismos para títulos, subtítulos y datos). */
    private val PALETTE = listOf(
        "negro" to BLACK, "gris" to 0xFF7F7F7F.toInt(), "azul marino" to 0xFF1F3864.toInt(), "azul" to 0xFF2E74B5.toInt(),
        "celeste" to 0xFF00B0F0.toInt(), "rojo" to 0xFFC00000.toInt(), "burdeo" to 0xFF7F0000.toInt(), "verde" to 0xFF00A050.toInt(),
        "morado" to 0xFF7030A0.toInt(), "rosado" to 0xFFE91E63.toInt(), "naranjo" to 0xFFED7D31.toInt(), "café" to 0xFF833C0B.toInt())

    /** Líneas → casillas. */
    fun fromLines(lines: List<String>): Cv {
        val cv = Cv(title = "")
        var sec: Section? = null
        val trailing = mutableListOf<String>()
        for (raw in lines) {
            val l = raw.trimEnd()
            if (l.isBlank()) continue
            when {
                l.startsWith("## ") -> {
                    // (un texto suelto antes de esta sección no se pierde: queda como fila de la sección anterior)
                    if (trailing.isNotEmpty()) {
                        val prev = sec ?: Section("").also { cv.sections += it }
                        trailing.forEach { prev.rows += Row("", it) }; trailing.clear()
                    }
                    sec = Section(l.removePrefix("## ").trim()); cv.sections += sec
                }
                l.startsWith("# ") -> if (cv.title.isBlank()) cv.title = l.removePrefix("# ").trim() else trailing += l.removePrefix("# ").trim()
                l.startsWith(": ") || l == ":" -> {
                    val v = l.removePrefix(":").trim()
                    val row = sec?.rows?.lastOrNull()
                    if (row != null && trailing.isEmpty()) row.value = if (row.value.isBlank()) v else row.value + "\n" + v
                    else {
                        if (sec == null) { sec = Section(""); cv.sections += sec }
                        trailing.forEach { sec.rows += Row("", it) }; trailing.clear()
                        sec.rows += Row("", v)
                    }
                }
                l.contains(" : ") || l.endsWith(" :") -> {
                    if (sec == null) { sec = Section(""); cv.sections += sec }
                    trailing.forEach { sec.rows += Row("", it) }; trailing.clear()
                    sec.rows += Row(l.substringBefore(" :").trim(), l.substringAfter(" :", "").trim())
                }
                else -> trailing += l.trim()
            }
        }
        // (lo suelto al final —«DISPONIBILIDAD INMEDIATA»— es el texto final)
        cv.footer = trailing.joinToString("\n")
        if (cv.title.isBlank()) cv.title = "CURRICULUM"
        return cv
    }

    /** Casillas → líneas (lo que arma el PDF y el Word). */
    fun toLines(cv: Cv, export: Boolean = false): List<String> {
        val out = mutableListOf("# " + cv.title.trim().ifBlank { "CURRICULUM" })
        for (s in cv.sections) {
            if (s.title.isNotBlank()) out += "## " + s.title.trim()
            for (r in s.rows) {
                // (al descargar o ver, una fila sin subtítulo ni dato no se pone)
                if (export && r.label.isBlank() && r.value.isBlank()) continue
                val parts = r.value.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val first = parts.firstOrNull().orEmpty()
                out += if (r.label.isBlank()) ": $first" else "${r.label.trim()} : $first"
                parts.drop(1).forEach { out += ": $it" }
            }
        }
        cv.footer.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        return out
    }

    // ---------- «Mis currículums» ----------
    private fun save(host: Host, cv: Cv): File {
        val dir = host.storeDir().apply { mkdirs() }
        val f = if (cv.file.isNotBlank()) File(dir, cv.file) else File(dir, "cv_${System.currentTimeMillis()}.txt")
        val head = "@colors " + cv.colors.joinToString(" ") { String.format(Locale.US, "%06X", it and 0xFFFFFF) }
        f.writeText(head + "\n@align ${cv.footerAlign}\n" + toLines(cv).joinToString("\n"))
        cv.file = f.name
        return f
    }

    private fun load(f: File): Cv {
        val all = f.readText().lines()
        val cv = fromLines(all.filter { !it.startsWith("@colors") && !it.startsWith("@align") })
        all.firstOrNull { it.startsWith("@align") }?.removePrefix("@align")?.trim()?.toIntOrNull()?.let { if (it in 0..2) cv.footerAlign = it }
        all.firstOrNull { it.startsWith("@colors") }?.removePrefix("@colors")?.trim()?.split(" ")?.forEachIndexed { i, h ->
            if (i < 3) h.toIntOrNull(16)?.let { cv.colors[i] = it or 0xFF000000.toInt() }
        }
        cv.file = f.name
        return cv
    }

    /** El nombre que se ve en la lista: el título y el primer dato (normalmente el nombre de la persona). */
    private fun displayName(cv: Cv): String {
        val who = cv.sections.flatMap { it.rows }.firstOrNull { it.label.isNotBlank() && it.value.isNotBlank() }?.value?.lines()?.first().orEmpty()
        return listOf(cv.title, who).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Nombre del archivo: el nombre de la persona (sin tildes ni símbolos) y la fecha. */
    private fun fileBase(cv: Cv): String {
        val who = java.text.Normalizer.normalize(displayName(cv), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), " ").trim().replace(Regex("\\s+"), "_").take(40).ifBlank { "curriculum" }
        return who + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(java.util.Date())
    }

    // ---------- La ventana ----------
    // Igual que «✏️ Editar personalidad»: arriba el título y una ayuda, al medio el RECUADRO con las casillas (se desliza
    // con el dedo) y abajo TODOS los botones, de dos en dos. Si el teclado tapa los botones, se esconde el teclado y listo.
    fun open(activity: Activity, host: Host, start: Cv? = null) {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val light = host.light()
        val bg = if (light) Color.WHITE else Color.BLACK
        val boxColor = if (light) Color.parseColor("#EDEDF0") else Color.parseColor("#1F1F1F")   // (el gris de la personalidad)
        val text = if (light) Color.parseColor("#111111") else Color.WHITE
        val sub = Color.parseColor("#8E8E93")
        val field = if (light) Color.WHITE else Color.parseColor("#2C2C2E")
        val line = if (light) Color.parseColor("#D1D1D6") else Color.parseColor("#3A3A3C")
        val accent = Color.parseColor("#3D5AFE")
        val ui = Handler(Looper.getMainLooper())

        var cv = start ?: fromLines(host.template())
        var dirty = false

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(px(16), px(20), px(16), px(16))
        }
        val outer = ScrollView(activity).apply { setBackgroundColor(bg); isFillViewport = true; addView(root) }
        val frame = FrameLayout(activity).apply { setBackgroundColor(bg); addView(outer) }

        fun round(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
            cornerRadius = r * d; setColor(color); stroke?.let { setStroke(px(1), it) }
        }
        fun AlertDialog.Builder.styled(): AlertDialog = show().also { runCatching { host.styleDialog(it) } }
        // 💬 Avisos dentro de la ventana (la app no usa avisos emergentes): una franja abajo que se va sola
        val banner = TextView(activity).apply {
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(px(16), px(12), px(16), px(12)); background = round(Color.parseColor("#E6222226"), 14f)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(px(16), 0, px(16), px(24)) }
            setOnClickListener { visibility = View.GONE }
        }
        frame.addView(banner)
        val hideBanner = Runnable { banner.visibility = View.GONE }
        fun say(msg: String) {
            banner.text = msg; banner.visibility = View.VISIBLE
            ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 3500)
        }

        fun small(label: String, onClick: () -> Unit) = TextView(activity).apply {
            this.text = label; textSize = 15f; setTextColor(sub); gravity = Gravity.CENTER
            setPadding(px(8), px(6), px(8), px(6))
            setOnClickListener { onClick() }
        }
        fun edit(value: String, hint: String, bold: Boolean = false, size: Float = 15f, multi: Boolean = false, onChange: (String) -> Unit) =
            EditText(activity).apply {
                setText(value); this.hint = host.t(hint); textSize = size; setTextColor(text); setHintTextColor(sub)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                background = round(field, 10f); setPadding(px(10), px(8), px(10), px(8))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                if (!multi) maxLines = 2
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG   // (árabe de derecha a izquierda, solo)
                host.noTranslate(this)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()); dirty = true }
                })
            }

        // 🔝 Título y ayuda (como «✏️ Editar personalidad»)
        root.addView(TextView(activity).apply {
            this.text = host.t("📄 Currículum"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(text)
        })
        root.addView(TextView(activity).apply {
            this.text = host.t("Escribe en las casillas y desliza con el dedo para ver todo. Los botones están abajo.")
            textSize = 13f; setTextColor(sub); setPadding(0, px(4), 0, px(12))
        })

        // 📝 EL RECUADRO con las casillas (≈ 40 % de la pantalla, como el de la personalidad; se desliza con el dedo)
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), px(16)) }
        val scroll = ScrollView(activity).apply {
            addView(form); background = round(boxColor, 16f); isVerticalScrollBarEnabled = true
            // (dentro de la ventana que también se desliza: el dedo mueve el recuadro, no la ventana)
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.40f).toInt()))

        // 🧰 Abajo, todos los botones de dos en dos (con el mismo estilo de CANCELAR y LISTO de la personalidad)
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6), 0, 0) }
        root.addView(buttons)
        fun buttonRow(vararg items: Pair<String, () -> Unit>) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0) }
            items.forEachIndexed { i, (label, action) ->
                row.addView(host.button(label, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = px(6); if (i < items.size - 1) marginEnd = px(6)
                })
            }
            buttons.addView(row)
        }

        lateinit var render: () -> Unit

        /** 🎨 Los colores: una ventanita con los de los títulos, los subtítulos y los datos. */
        fun colorsDialog() {
            val boxV = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(12), px(20), px(4)) }
            fun paint() {
                boxV.removeAllViews()
                listOf("Títulos", "Subtítulos", "Datos").forEachIndexed { which, name ->
                    boxV.addView(TextView(activity).apply {
                        this.text = host.t(name); textSize = 14f; setTextColor(sub); setPadding(0, px(8), 0, px(4))
                    })
                    val dots = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    for ((_, c) in PALETTE) dots.addView(View(activity).apply {
                        val sel = cv.colors[which] == c
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(c)
                            setStroke(px(if (sel) 3 else 1), if (sel) accent else line)
                        }
                        layoutParams = LinearLayout.LayoutParams(px(if (sel) 30 else 26), px(if (sel) 30 else 26)).apply { marginEnd = px(8); gravity = Gravity.CENTER_VERTICAL }
                        setOnClickListener { cv.colors[which] = c; dirty = true; paint() }
                    })
                    boxV.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(dots) })
                }
            }
            paint()
            AlertDialog.Builder(activity).setTitle(host.t("🎨 Colores")).setView(boxV)
                .setPositiveButton(host.t("Listo"), null).styled()
        }

        /** El menú ⋮ de una fila o sección: subir, bajar, borrar. */
        fun rowMenu(anchor: View, up: () -> Unit, down: () -> Unit, del: () -> Unit) {
            val pm = android.widget.PopupMenu(activity, anchor)
            pm.menu.add(0, 1, 0, host.t("⬆️ Subir")); pm.menu.add(0, 2, 1, host.t("⬇️ Bajar")); pm.menu.add(0, 3, 2, host.t("🗑️ Borrar"))
            pm.setOnMenuItemClickListener { when (it.itemId) { 1 -> up(); 2 -> down(); else -> del() }; dirty = true; render(); true }
            pm.show()
        }
        fun <T> MutableList<T>.move(i: Int, by: Int) { val j = i + by; if (i in indices && j in indices) { val x = this[i]; this[i] = this[j]; this[j] = x } }

        render = {
            form.removeAllViews()
            // Título grande
            form.addView(edit(cv.title, "Título (CURRICULUM)", bold = true, size = 19f) { cv.title = it }.apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(10) }
            })
            cv.sections.forEachIndexed { si, s ->
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; background = round(Color.TRANSPARENT, 12f, line); setPadding(px(8), px(8), px(4), px(8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(10) }
                }
                // Sección: su título y ⋮
                val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(edit(s.title, "Sección (ANTECEDENTES…)", bold = true, size = 15f) { s.title = it }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                head.addView(small("⋮") {}.also { v -> v.setOnClickListener {
                    rowMenu(v, { cv.sections.move(si, -1) }, { cv.sections.move(si, 1) }, {
                        AlertDialog.Builder(activity).setMessage(host.t("¿Borrar esta sección con todo lo que tiene?"))
                            .setPositiveButton(host.t("Borrar")) { _, _ -> cv.sections.removeAt(si); dirty = true; render() }
                            .setNegativeButton(host.t("Cancelar"), null).styled()
                    })
                } })
                box.addView(head)
                // Filas: subtítulo | dato | ⋮
                s.rows.forEachIndexed { ri, r ->
                    val rowV = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) }
                    }
                    rowV.addView(edit(r.label, "Subtítulo / años", size = 14f) { r.label = it }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f).apply { marginEnd = px(6) }
                    })
                    rowV.addView(edit(r.value, "Dato", size = 14f, multi = true) { r.value = it }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f)
                    })
                    rowV.addView(small("⋮") {}.also { v -> v.setOnClickListener {
                        rowMenu(v, { s.rows.move(ri, -1) }, { s.rows.move(ri, 1) }, { s.rows.removeAt(ri) })
                    } })
                    box.addView(rowV)
                }
                box.addView(TextView(activity).apply {
                    this.text = host.t("➕ Agregar fila"); textSize = 14f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
                    setPadding(px(4), px(10), px(4), px(2))
                    setOnClickListener { s.rows += Row(); dirty = true; render() }
                })
                form.addView(box)
            }
            form.addView(TextView(activity).apply {
                this.text = host.t("➕ Agregar sección"); textSize = 15f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; background = round(Color.TRANSPARENT, 12f, line); setPadding(px(10), px(12), px(10), px(12))
                setOnClickListener { cv.sections += Section("", mutableListOf(Row())); dirty = true; render() }
            })
            form.addView(TextView(activity).apply {
                this.text = host.t("Texto final (por ejemplo: DISPONIBILIDAD INMEDIATA)"); textSize = 13f; setTextColor(sub)
                setPadding(px(4), px(16), px(4), px(4))
            })
            form.addView(edit(cv.footer, "Escribe aquí (opcional)", multi = true) { cv.footer = it })
            // ↔️ IZQUIERDA · CENTRO · DERECHA: dónde queda el texto final, abajo del todo del currículum
            val alignRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8), 0, 0) }
            listOf("IZQUIERDA", "CENTRO", "DERECHA").forEachIndexed { i, label ->
                alignRow.addView(host.button(label) { cv.footerAlign = i; dirty = true; render() }.apply {
                    alpha = if (cv.footerAlign == i) 1f else 0.4f   // (el elegido se ve encendido)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = px(3); if (i < 2) marginEnd = px(3)
                })
            }
            form.addView(alignRow)
        }

        // ---------- Acciones ----------
        fun busy(msg: String): AlertDialog = AlertDialog.Builder(activity).setMessage(host.t(msg)).setCancelable(false).styled()

        fun doSave(then: (() -> Unit)? = null) {
            runCatching { save(host, cv) }.onSuccess { dirty = false; say(host.t("💾 Guardado en «Mis currículums»")); then?.invoke() }
                .onFailure { say(host.t("❌ No se pudo guardar")) }
        }

        /** Si hay cambios sin guardar, pregunta antes de seguir. */
        fun askSaveThen(next: () -> Unit) {
            if (!dirty) { next(); return }
            AlertDialog.Builder(activity).setMessage(host.t("¿Guardar los cambios de este currículum antes?"))
                .setPositiveButton(host.t("Guardar")) { _, _ -> doSave(next) }
                .setNegativeButton(host.t("No guardar")) { _, _ -> next() }
                .setNeutralButton(host.t("Cancelar"), null).styled()
        }

        fun newCv() = askSaveThen { cv = fromLines(host.template()); dirty = false; render(); scroll.scrollTo(0, 0) }

        fun openFile() = askSaveThen {
            host.pickFile { f ->
                val wait = busy("📂 Leyendo tu currículum…")
                thread {
                    val (lines, colors, err) = runCatching { host.readFile(f) }.getOrElse { Triple(null, null, it.message) }
                    ui.post {
                        runCatching { wait.dismiss() }
                        if (lines.isNullOrEmpty()) { say(err ?: host.t("❌ No pude leer ese archivo")); return@post }
                        cv = fromLines(lines)
                        colors?.forEachIndexed { i, c -> if (i < 3) cv.colors[i] = c }
                        dirty = true; render(); scroll.scrollTo(0, 0)
                        say(host.t("✅ Listo: revisa las casillas y corrige lo que haga falta"))
                    }
                }
            }
        }

        fun myList() = askSaveThen {
            val files = host.storeDir().listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }.orEmpty()
            if (files.isEmpty()) { say(host.t("Todavía no tienes currículums guardados")); return@askSaveThen }
            val loaded = files.map { f -> f to runCatching { load(f) }.getOrNull() }.filter { it.second != null }
            val names = loaded.map { (f, c) ->
                displayName(c!!) + "  ·  " + java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(f.lastModified()))
            }.toTypedArray()
            AlertDialog.Builder(activity).setTitle(host.t("📋 Mis currículums"))
                .setItems(names) { _, i ->
                    val (f, c) = loaded[i]
                    AlertDialog.Builder(activity).setTitle(names[i])
                        .setPositiveButton(host.t("✏️ Abrir")) { _, _ -> cv = c!!; dirty = false; render(); scroll.scrollTo(0, 0) }
                        .setNegativeButton(host.t("🗑️ Borrar")) { _, _ ->
                            AlertDialog.Builder(activity).setMessage(host.t("¿Borrar este currículum para siempre?"))
                                .setPositiveButton(host.t("Borrar")) { _, _ ->
                                    f.delete(); if (cv.file == f.name) cv.file = ""
                                    say(host.t("🗑️ Currículum borrado"))
                                }.setNegativeButton(host.t("Cancelar"), null).styled()
                        }
                        .setNeutralButton(host.t("Cancelar"), null).styled()
                }.setNegativeButton(host.t("Cerrar"), null).styled()
        }

        fun preview() {
            val wait = busy("👁️ Armando la vista previa…")
            val lines = toLines(cv, export = true); val colors = cv.colors + cv.footerAlign
            thread {
                val png = runCatching { host.png(lines, colors) }.getOrNull()
                val bmp = png?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                ui.post {
                    runCatching { wait.dismiss() }
                    if (bmp == null) { say(host.t("❌ No se pudo armar la vista previa")); return@post }
                    val pv = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    val frame = FrameLayout(activity).apply { setBackgroundColor(Color.parseColor("#2C2C2E")) }
                    frame.addView(ScrollView(activity).apply {
                        addView(ImageView(activity).apply {
                            setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(px(10), px(70), px(10), px(20))
                        })
                    })
                    frame.addView(TextView(activity).apply {
                        this.text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = round(Color.parseColor("#99000000"), 24f)
                        layoutParams = FrameLayout.LayoutParams(px(48), px(48), Gravity.TOP or Gravity.END).apply { setMargins(0, px(14), px(14), 0) }
                        setOnClickListener { pv.dismiss() }
                    })
                    pv.setContentView(frame); pv.setOnDismissListener { bmp.recycle() }; pv.show()
                }
            }
        }

        fun download() {
            val labels = arrayOf("📕 PDF", "📝 Word (.docx)", "🖼️ " + host.t("Imagen") + " (.png)")
            AlertDialog.Builder(activity).setTitle(host.t("⬇️ Descargar en tu teléfono"))
                .setItems(labels) { _, which ->
                    val (ext, mime) = when (which) {
                        1 -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        2 -> "png" to "image/png"
                        else -> "pdf" to "application/pdf"
                    }
                    val lines = toLines(cv, export = true); val colors = cv.colors + cv.footerAlign
                    val wait = busy("⬇️ Guardando…")
                    thread {
                        val where = runCatching {
                            val bytes = when (ext) { "docx" -> host.docx(lines, colors); "png" -> host.png(lines, colors); else -> host.pdf(lines, colors) }
                            host.saveToPhone(bytes, "${fileBase(cv)}.$ext", mime)
                        }.getOrNull()
                        ui.post {
                            runCatching { wait.dismiss() }
                            say(if (where != null) host.t("✅ Guardado en") + " $where" else host.t("❌ No se pudo guardar el archivo"))
                        }
                    }
                }.setNegativeButton(host.t("Cancelar"), null).styled()
        }

        /** 🔗 Compartir el currículum (WhatsApp, correo, Drive…) en PDF, Word o imagen. */
        fun share() {
            val labels = arrayOf("📕 PDF", "📝 Word (.docx)", "🖼️ " + host.t("Imagen") + " (.png)")
            AlertDialog.Builder(activity).setTitle(host.t("🔗 Compartir"))
                .setItems(labels) { _, which ->
                    val (ext, mime) = when (which) {
                        1 -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        2 -> "png" to "image/png"
                        else -> "pdf" to "application/pdf"
                    }
                    val lines = toLines(cv, export = true); val colors = cv.colors + cv.footerAlign
                    val wait = busy("🔗 Preparando…")
                    thread {
                        val bytes = runCatching { when (ext) { "docx" -> host.docx(lines, colors); "png" -> host.png(lines, colors); else -> host.pdf(lines, colors) } }.getOrNull()
                        ui.post {
                            runCatching { wait.dismiss() }
                            if (bytes == null || !runCatching { host.share(bytes, "${fileBase(cv)}.$ext", mime) }.getOrDefault(false))
                                say(host.t("❌ No se pudo compartir"))
                        }
                    }
                }.setNegativeButton(host.t("Cancelar"), null).styled()
        }

        /** 🗑️ Borrar este currículum (de «Mis currículums» si estaba guardado) y dejar uno nuevo en blanco. */
        fun deleteCv() {
            AlertDialog.Builder(activity).setMessage(host.t("¿Borrar este currículum para siempre?"))
                .setPositiveButton(host.t("Borrar")) { _, _ ->
                    if (cv.file.isNotBlank()) runCatching { java.io.File(host.storeDir(), cv.file).delete() }
                    cv = fromLines(host.template()); dirty = false; render(); scroll.scrollTo(0, 0)
                    say(host.t("🗑️ Currículum borrado"))
                }.setNegativeButton(host.t("Cancelar"), null).styled()
        }

        buttonRow("NUEVO" to { newCv() }, "ABRIR" to { openFile() })
        buttonRow("GUARDAR" to { doSave() }, "MIS CURRÍCULUM" to { myList() })
        buttonRow("VER" to { preview() }, "COLORES" to { colorsDialog() })
        buttonRow("DESCARGAR" to { download() }, "COMPARTIR" to { share() })
        buttonRow("BORRAR" to { deleteCv() }, "CERRAR" to { askSaveThen { dialog.dismiss() } })

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) { askSaveThen { dialog.dismiss() }; true } else false
        }

        render()
        dialog.setContentView(frame)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }
}
