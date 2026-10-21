package com.paulrj.cumulusia.adapter

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.paulrj.cumulusia.R
import java.io.File
import java.util.Locale

/**
 * 📄 ARCHIVOS DE CÓDIGO EN EL CHAT (modo Ingeniero de código)
 *
 * - Cada código que entrega la IA aparece como una tarjeta de archivo: «MainActivity · Código · KT».
 * - Al tocarla se abre la ventana del archivo: ✕ arriba a la izquierda, el nombre al centro y ⋮ a la derecha.
 *   El código se ve con colores y SOLO PARA MIRAR (no se edita).
 * - En ⋮: Publicar · Copiar · Descargar · Guardar como KT (o la extensión que sea) · Guardar como PDF ·
 *   Compartir como PDF · Imprimir.
 */
object CodeFileViewer {

    // ======================== 🏷️ NOMBRE DEL ARCHIVO ========================

    private val FILE_RX = Regex(
        "([\\w./-]*[\\w-]+\\.(kt|kts|java|xml|gradle|py|js|mjs|ts|tsx|jsx|html|htm|css|scss|json|swift|dart|sql|md|txt|pro|cpp|cc|hpp|h|c|cs|go|rs|php|rb|sh|yml|yaml|toml|cmake|lua|vue|properties))\\b",
        RegexOption.IGNORE_CASE
    )

    fun extensionFor(language: String, code: String): String = when (language.lowercase(Locale.ROOT)) {
        "kotlin", "kt" -> "kt"; "kts" -> "kts"; "java" -> "java"; "python", "py" -> "py"
        "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "jsx" -> "jsx"; "tsx" -> "tsx"
        "html", "htm" -> "html"; "css" -> "css"; "scss" -> "scss"; "json" -> "json"; "xml" -> "xml"
        "c" -> "c"; "cpp", "c++" -> "cpp"; "h", "hpp" -> "h"; "csharp", "cs", "c#" -> "cs"; "go" -> "go"
        "rust", "rs" -> "rs"; "swift" -> "swift"; "dart" -> "dart"; "php" -> "php"; "ruby", "rb" -> "rb"
        "bash", "sh", "shell" -> "sh"; "sql" -> "sql"; "yaml", "yml" -> "yml"; "toml" -> "toml"
        "gradle", "groovy" -> "gradle"; "markdown", "md" -> "md"; "cmake" -> "cmake"; "lua" -> "lua"
        "properties" -> "properties"; "vue" -> "vue"
        else -> {
            val t = code.trimStart()
            when {
                t.startsWith("<!DOCTYPE", true) || t.startsWith("<html", true) -> "html"
                t.startsWith("<") -> "xml"
                Regex("(?m)^\\s*(fun |val |package |import android)").containsMatchIn(code) -> "kt"
                else -> "txt"
            }
        }
    }

    /**
     * Nombre del archivo: el que la IA escribió justo antes del código (ej. «app/src/.../MainActivity.kt»),
     * o el de un comentario en las primeras líneas, o el de la clase; si no, «codigo_N».
     */
    fun fileNameFor(textBefore: String, language: String, code: String, index: Int): String {
        val ext = extensionFor(language, code)
        val near = textBefore.takeLast(400)
        FILE_RX.findAll(near).lastOrNull()?.let { return it.groupValues[1].substringAfterLast('/') }
        code.lineSequence().take(3).forEach { line ->
            if (Regex("^\\s*(//|#|<!--|/\\*|--)").containsMatchIn(line))
                FILE_RX.find(line)?.let { return it.groupValues[1].substringAfterLast('/') }
        }
        when (ext) {
            "kt", "java", "swift", "dart", "cs" ->
                Regex("\\b(?:class|object|interface)\\s+([A-Z]\\w*)").find(code)?.let { return it.groupValues[1] + "." + ext }
            "xml" -> {
                val t = code.trimStart()
                if (t.contains("<manifest")) return "AndroidManifest.xml"
                if (Regex("<resources[\\s>]").containsMatchIn(t)) return when {
                    t.contains("<color ") -> "colors.xml"
                    t.contains("<style ") -> "themes.xml"
                    else -> "strings.xml"
                }
            }
            "html" -> return "index.html"
            "css" -> return "style.css"
            "js" -> return "script.js"
        }
        return "codigo_${index + 1}.$ext"
    }

    // ======================== 🗂️ TARJETA EN EL CHAT ========================

    fun card(context: Context, name: String, language: String, code: String,
             highlight: (String, String) -> CharSequence): View {
        val d = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val ext = name.substringAfterLast('.', "txt").uppercase(Locale.ROOT)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(Color.parseColor("#1E1C1A"))
                setStroke(dp(1), Color.parseColor("#3A3836"))
            }
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = ta.getDrawable(0); ta.recycle()
            isClickable = true
            contentDescription = "Abrir $name"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(6); marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { open(context, name, language, code, highlight) }
        }
        // Cuadrado con </> — fondo con el color de las estrellas ✨ (celeste → rosado), el </> en oscuro para que se lea
        row.addView(FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#18E6FF"), Color.parseColor("#FF16E6"))
            ).apply { cornerRadius = 10 * d }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(16) }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_menu_code)
                setColorFilter(Color.parseColor("#141414"))
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = name.substringBeforeLast('.')
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        texts.addView(TextView(context).apply {
            text = "Código · $ext"
            textSize = 14f
            setTextColor(Color.parseColor("#B5B0AA"))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts)
        return row
    }

    // ======================== 🪟 VENTANA DEL ARCHIVO ========================

    fun open(context: Context, name: String, language: String, code: String,
             highlight: (String, String) -> CharSequence) {
        val d = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        // Barra de arriba: ✕ · nombre · ⋮
        val bar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60))
        }
        bar.addView(TextView(context).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Cerrar"
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0); ta.recycle()
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(6) })
        bar.addView(TextView(context).apply {
            text = name
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { marginStart = dp(68); marginEnd = dp(68) })
        val more = TextView(context).apply {
            text = "⋮"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Más opciones"
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0); ta.recycle()
        }
        bar.addView(more, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(6) })
        root.addView(bar)

        // El código con colores: se desliza hacia abajo y a los lados. Solo para mirar (no se edita).
        val codeView = TextView(context).apply {
            text = highlight(code, language)
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            setTextColor(Color.parseColor("#ABB2BF"))
            setLineSpacing(3 * d, 1f)
            setPadding(dp(18), dp(8), dp(18), dp(40))
            setTextIsSelectable(false)
            isFocusable = false
            isLongClickable = false
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_LTR   // ↔️ el código siempre de izquierda a derecha (también en árabe)
            addView(codeView)
        }
        root.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(horizontal)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        more.setOnClickListener { showMenu(context, more, name, language, code, highlight) }

        // 📱 La barra ✕ · nombre · ⋮ queda DEBAJO de la barra de notificaciones (hora, red, batería),
        //    y el final del código queda sobre la barra de navegación (también con muesca/cámara y de lado).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1A1A")))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = Color.parseColor("#1A1A1A")
            navigationBarColor = Color.parseColor("#1A1A1A")
        }
        dialog.show()
    }

    // ======================== ⋮ MENÚ ========================

    private fun showMenu(context: Context, anchor: View, name: String, language: String, code: String,
                         highlight: (String, String) -> CharSequence) {
        val d = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val ext = name.substringAfterLast('.', "txt").uppercase(Locale.ROOT)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.parseColor("#262626"))
                setStroke(dp(1), Color.parseColor("#333333"))
            }
            elevation = 12 * d
        }
        val popup = PopupWindow(box, dp(290), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * d
        }
        fun item(icon: Int, label: String, action: () -> Unit) {
            box.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0); ta.recycle()
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(Color.parseColor("#BDBDBD"))
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(18) })
                addView(TextView(context).apply {
                    text = label
                    textSize = 16.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                setOnClickListener {
                    popup.dismiss()
                    runCatching { action() }.onFailure {
                        Toast.makeText(context, "No se pudo: ${it.message ?: label}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        fun divider() = box.addView(View(context).apply { setBackgroundColor(Color.parseColor("#3A3A3A")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) })

        item(R.drawable.ic_action_upload, "Publicar") { publish(context, name, code) }
        item(R.drawable.ic_action_copy, "Copiar") { copy(context, name, code) }
        item(R.drawable.ic_action_download, "Descargar") { download(context, name, code) }
        divider()
        item(R.drawable.ic_action_download, "Guardar como $ext") { saveAs(context, name, mimeFor(name), code.toByteArray(Charsets.UTF_8)) }
        item(R.drawable.ic_action_download, "Guardar como PDF") {
            val pdf = makePdf(context, name, code, language, highlight)
            saveAs(context, name.substringBeforeLast('.') + ".pdf", "application/pdf", pdf.readBytes())
        }
        item(R.drawable.ic_action_upload, "Compartir como PDF") { sharePdf(context, name, code, language, highlight) }
        divider()
        item(R.drawable.ic_action_print, "Imprimir") { print(context, name, code, language, highlight) }

        popup.showAsDropDown(anchor, -dp(234), -dp(8))
    }

    // ======================== ACCIONES ========================

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Solo los tipos conocidos; los demás (.kt, .gradle…) como binario para que Android no les agregue «.txt»
        return when (ext) {
            "html", "htm" -> "text/html"; "css" -> "text/css"; "json" -> "application/json"; "xml" -> "text/xml"
            "txt" -> "text/plain"; "md" -> "text/markdown"; "js" -> "text/javascript"; "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun shareFolder(context: Context): File =
        File(context.filesDir, "entregas/codigo").apply { mkdirs() }   // carpeta que la app ya comparte (FileProvider)

    private fun uriFor(context: Context, f: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)

    /** 📤 Publicar: envía el ARCHIVO a la app que elijas (GitHub, Drive, WhatsApp, correo…). */
    private fun publish(context: Context, name: String, code: String) {
        val f = File(shareFolder(context), name).apply { writeText(code, Charsets.UTF_8) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, f))
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Publicar $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun copy(context: Context, name: String, code: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(name, code))
    }

    /** ⬇️ Descargar: a Descargas/CumulusIA con su nombre (ej. MainActivity.kt). */
    private fun download(context: Context, name: String, code: String) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/CumulusIA")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("sin permiso para Descargas")
        context.contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray(Charsets.UTF_8)) }
            ?: error("no se pudo escribir en Descargas")
        // 🔔 Aviso en la barra de notificaciones (tocarlo abre el archivo)
        com.paulrj.cumulusia.manager.SavedFileNotifier.notify(context, uri, name, mimeFor(name),
            com.paulrj.cumulusia.manager.SavedFileNotifier.WHERE_DOWNLOADS)
    }

    private fun findActivity(context: Context): androidx.activity.ComponentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is androidx.activity.ComponentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** 💾 «Guardar como»: Android te deja elegir la carpeta y el nombre. */
    private fun saveAs(context: Context, name: String, mime: String, bytes: ByteArray) {
        val act = findActivity(context) ?: error("no se pudo abrir «Guardar como»")
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = act.activityResultRegistry.register(
            "cumulus_save_" + System.nanoTime(),
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mime)
        ) { uri ->
            launcher?.unregister()
            if (uri != null) runCatching {
                act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no se pudo escribir")
                // 🔔 Aviso en la barra de notificaciones (tocarlo abre el archivo)
                com.paulrj.cumulusia.manager.SavedFileNotifier.notify(act, uri, name, mime,
                    com.paulrj.cumulusia.manager.SavedFileNotifier.WHERE_CHOSEN)
            }.onFailure { Toast.makeText(act, "No se pudo guardar $name", Toast.LENGTH_SHORT).show() }
        }
        launcher.launch(name)
    }

    // ======================== 📄 PDF ========================

    /** Colores del código para papel blanco (los del chat son para fondo oscuro). */
    private val LIGHT = mapOf(
        "#C678DD" to "#A626A4", "#98C379" to "#50A14F", "#7F848E" to "#8E8F96", "#E5C07B" to "#B07A00",
        "#61AFEF" to "#2F6FDB", "#D19A66" to "#986801", "#E06C75" to "#D43F3A"
    ).entries.associate { Color.parseColor(it.key) to Color.parseColor(it.value) }

    /** Arma el PDF del código (A4, con colores para papel, el nombre arriba y el número de página abajo). */
    private fun makePdf(context: Context, name: String, code: String, language: String,
                        highlight: (String, String) -> CharSequence): File {
        val dark = highlight(code, language)
        val text = SpannableString(code)
        if (dark is Spanned) dark.getSpans(0, dark.length, ForegroundColorSpan::class.java).forEach { s ->
            val c = LIGHT[s.foregroundColor] ?: Color.parseColor("#383A42")
            text.setSpan(ForegroundColorSpan(c), dark.getSpanStart(s), dark.getSpanEnd(s), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val pageW = 595; val pageH = 842; val margin = 42f
        val width = (pageW - margin * 2).toInt()
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = 8.6f; color = Color.parseColor("#383A42")
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setLineSpacing(1.5f, 1f).build()
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 14f; color = Color.BLACK
        }
        val footPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
        val doc = android.graphics.pdf.PdfDocument()
        var line = 0
        var pageNo = 1
        do {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c = page.canvas
            var top = margin
            if (pageNo == 1) {
                c.drawText(name, margin, top + 14f, titlePaint)
                c.drawLine(margin, top + 22f, pageW - margin, top + 22f, footPaint)
                top += 34f
            }
            val avail = pageH - margin - 18f - top
            val first = line
            var last = first
            while (last + 1 < layout.lineCount &&
                layout.getLineBottom(last + 1) - layout.getLineTop(first) <= avail) last++
            if (layout.lineCount > 0) {
                c.save()
                c.translate(margin, top - layout.getLineTop(first))
                c.clipRect(0f, layout.getLineTop(first).toFloat(), width.toFloat(), layout.getLineBottom(last).toFloat())
                layout.draw(c)
                c.restore()
            }
            c.drawText("$name · página $pageNo", margin, pageH - margin + 10f, footPaint)
            doc.finishPage(page)
            line = last + 1
            pageNo++
        } while (line < layout.lineCount)
        val f = File(shareFolder(context), name.substringBeforeLast('.') + ".pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun sharePdf(context: Context, name: String, code: String, language: String,
                         highlight: (String, String) -> CharSequence) {
        val f = makePdf(context, name, code, language, highlight)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, f))
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 🖨️ Imprimir: abre la impresión de Android (impresora o «Guardar como PDF» del sistema). */
    private fun print(context: Context, name: String, code: String, language: String,
                      highlight: (String, String) -> CharSequence) {
        val f = makePdf(context, name, code, language, highlight)
        val act = findActivity(context) ?: context
        val pm = act.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print(name, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?, newAttributes: android.print.PrintAttributes,
                cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback, extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    android.print.PrintDocumentInfo.Builder(f.name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?, destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback
            ) {
                runCatching {
                    f.inputStream().use { input ->
                        java.io.FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                    }
                }.onSuccess { callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
                    .onFailure { callback.onWriteFailed(it.message) }
            }
        }, android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build())
    }
}
