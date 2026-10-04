package com.paulrj.cumulusia.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.paulrj.cumulusia.R
import com.paulrj.cumulusia.model.ChatMessage

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val getAiAvatarUri: () -> Uri? = { null },
    private val getAiName: () -> String = { "Cumulus IA" },  // nombre elegido para la IA
    private val getUserName: () -> String = { "Tú" }          // nombre elegido por el usuario
) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvContent: TextView = view.findViewById(R.id.tvMessageContent)
        val tvTokenInfo: TextView = view.findViewById(R.id.tvTokenInfo)
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val ivAiAvatar: ImageView = view.findViewById(R.id.iv_ai_avatar)

        init {
            // Avatar redondo (recorta la imagen en círculo)
            ivAiAvatar.clipToOutline = true
            ivAiAvatar.background = null   // sin el círculo azul de fondo
            ivAiAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    // Cuadrada con esquinas curvas (igual que la imagen de arriba)
                    outline.setRoundRect(0, 0, v.width, v.height, 14 * v.resources.displayMetrics.density)
                }
            }
        }
        val spaceBetween: Space = view.findViewById(R.id.space_between)
        val codeActions: LinearLayout = view.findViewById(R.id.codeActions)
        val messageActions: LinearLayout = view.findViewById(R.id.messageActions)
        val btnCopy: View = view.findViewById(R.id.btnCopyMessage)
        val btnShare: View = view.findViewById(R.id.btnShareMessage)
        val attachmentsScroll: View = view.findViewById(R.id.messageAttachmentsScroll)
        val attachmentsRow: LinearLayout = view.findViewById(R.id.messageAttachments)
    }

    // Tarjeta de un archivo creado por la IA: tocar = abrir · mantener presionado = compartir
    private fun addDeliveredCard(holder: ChatViewHolder, spec: String) {
        val parts = spec.split("|")
        if (parts.size < 4) return
        val thumb = parts[0]; val uri = Uri.parse(parts[1]); val mime = parts[2]; val name = parts[3]
        val context = holder.itemView.context
        val dp = context.resources.displayMetrics.density
        val open = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure { Toast.makeText(context, "No hay una app para abrir este archivo", Toast.LENGTH_SHORT).show() }
        }
        val share = {
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Compartir").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
            true
        }
        val isVideo = mime.startsWith("video/"); val isImage = mime.startsWith("image/")
        val bmp = if (thumb.isNotEmpty()) runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(thumb, o)
            var sample = 1
            while (o.outWidth / sample > 1400) sample *= 2
            android.graphics.BitmapFactory.decodeFile(thumb, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() else null

        if ((isVideo || isImage) && bmp != null) {
            val screenW = context.resources.displayMetrics.widthPixels
            val w = (screenW * 0.78f).toInt()
            val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceIn((120 * dp).toInt(), (520 * dp).toInt())
            val frame = android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (8 * dp).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#2C2C2E")) }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                setOnClickListener { open() }
                setOnLongClickListener { share() }
            }
            frame.addView(ImageView(context).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            })
            if (isVideo) frame.addView(TextView(context).apply {
                text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
                layoutParams = android.widget.FrameLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt(), Gravity.CENTER)
            })
            holder.attachmentsRow.addView(frame)
            return
        }
        // Documento (Word, PDF, texto...) u otro archivo: tarjeta con ícono y nombre
        val icon = when {
            mime.contains("word") -> "📝"; mime.contains("pdf") -> "📕"; isVideo -> "🎬"; isImage -> "🖼️"; else -> "📄"
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((context.resources.displayMetrics.widthPixels * 0.78f).toInt(), -2)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp; setColor(Color.parseColor("#1C1C1E")); setStroke((1.2f * dp).toInt(), Color.parseColor("#3D5AFE"))
            }
            setOnClickListener { open() }
            setOnLongClickListener { share() }
        }
        card.addView(TextView(context).apply { text = icon; textSize = 30f })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, 0, 0)
            addView(TextView(context).apply { text = name; textSize = 14f; setTextColor(Color.WHITE); maxLines = 2 })
            addView(TextView(context).apply {
                text = "Toca para abrir · mantén presionado para compartir"; textSize = 11f; setTextColor(Color.parseColor("#8E8E93"))
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        holder.attachmentsRow.addView(card)
    }

    // Imagen grande en la respuesta de la IA (carrusel horizontal si hay varias)
    private fun addLargeAiImage(holder: ChatViewHolder, path: String) {
        val context = holder.itemView.context
        val dp = context.resources.displayMetrics.density
        val bmp = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / sample > 1400) sample *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val w = (screenW * 0.78f).toInt()
        val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceIn((120 * dp).toInt(), (560 * dp).toInt())
        val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (8 * dp).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#2C2C2E")) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setOnClickListener { showFullImage(context, path) }
        }
        holder.attachmentsRow.addView(iv)
    }

    private fun showFullImage(context: Context, path: String) {
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.show()
    }

    // 📎 Miniaturas de los adjuntos enviados en el mensaje
    private fun bindAttachments(holder: ChatViewHolder, message: ChatMessage) {
        holder.attachmentsRow.removeAllViews()
        if (message.attachments.isEmpty()) {
            holder.attachmentsScroll.visibility = View.GONE
            return
        }
        holder.attachmentsScroll.visibility = View.VISIBLE
        val context = holder.itemView.context
        val dp = context.resources.displayMetrics.density
        val size = (72 * dp).toInt()
        message.attachments.forEach { path ->
            // 📦 Archivo entregado por la IA (video, imagen o documento): tarjeta para abrir / compartir
            if (path.startsWith("deliver:")) {
                addDeliveredCard(holder, path.removePrefix("deliver:"))
                return@forEach
            }
            val file = java.io.File(path)
            val ext = file.extension.lowercase()
            val isImage = ext in setOf("jpg", "jpeg", "jpe", "jfif", "pjpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif", "ico", "wbmp", "dng", "cr2", "nef", "arw", "raf", "orf", "rw2")
            // 🖼️ Imágenes de la IA (noticias, clima, imágenes creadas): grandes y se abren al tocarlas
            if (!message.isUser && isImage) {
                addLargeAiImage(holder, path)
                return@forEach
            }
            val isVideo = ext in setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
            val isAudio = ext in setOf("wav", "mp3", "flac", "m4a", "ogg", "aac", "opus")
            val frame = android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (6 * dp).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(Color.parseColor("#2C2C2E"))
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val bmp = runCatching {
                when {
                    isImage -> {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(path, o)
                        var sample = 1
                        while (o.outWidth / sample > size * 2 || o.outHeight / sample > size * 2) sample *= 2
                        android.graphics.BitmapFactory.decodeFile(
                            path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                    }
                    isVideo -> android.media.ThumbnailUtils.createVideoThumbnail(
                        file, android.util.Size(size, size), null
                    )
                    else -> null
                }
            }.getOrNull()
            if (bmp != null) {
                frame.addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
                })
            } else {
                frame.addView(TextView(context).apply {
                    text = (if (isAudio) "🎵" else "📄") + "\n" + file.extension.uppercase().take(5)
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
                })
            }
            if (isVideo) {
                frame.addView(ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_media_play)
                    setColorFilter(Color.WHITE)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        (26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER
                    )
                })
            }
            holder.attachmentsRow.addView(frame)
        }
    }

    private data class CodeBlock(val language: String, val code: String)

    // Convierte el formato de la IA (**negrita**, *cursiva*, # títulos, `código`, viñetas)
    // en texto con estilo, sin mostrar los asteriscos
    private fun renderMarkdown(text: String): CharSequence {
        val out = android.text.SpannableStringBuilder()
        val codeRegex = Regex("```[\\w+#.-]*[ \\t]*\\r?\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        var last = 0
        for (m in codeRegex.findAll(text)) {
            appendInlineBlock(out, text.substring(last, m.range.first))
            val start = out.length
            out.append(m.groupValues[1].trimEnd())
            out.setSpan(android.text.style.TypefaceSpan("monospace"), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#8C9EFF")), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n")
            last = m.range.last + 1
        }
        appendInlineBlock(out, text.substring(last))
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendInlineBlock(out: android.text.SpannableStringBuilder, block: String) {
        val lines = block.split("\n")
        lines.forEachIndexed { idx, raw ->
            var line = raw
            var header = 0
            Regex("^\\s*(#{1,6})\\s+").find(line)?.let { header = it.groupValues[1].length; line = line.substring(it.range.last + 1) }
            Regex("^(\\s*)[-*+]\\s+").find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.range.last + 1) }
            val lineStart = out.length
            appendInline(out, line)
            if (header > 0) {
                out.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), lineStart, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(android.text.style.RelativeSizeSpan(if (header <= 2) 1.2f else 1.1f), lineStart, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (idx < lines.lastIndex) out.append("\n")
        }
    }

    private fun appendInline(out: android.text.SpannableStringBuilder, line: String) {
        val token = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")
        var last = 0
        for (m in token.findAll(line)) {
            out.append(line.substring(last, m.range.first).replace("**", ""))
            val g = m.groupValues
            val start = out.length
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> {
                    out.append(g[1].ifEmpty { g[2] })
                    out.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[3].isNotEmpty() -> {
                    out.append(g[3])
                    out.setSpan(android.text.style.TypefaceSpan("monospace"), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(g[4].ifEmpty { g[5] })
                    out.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        out.append(line.substring(last).replace("**", ""))   // asteriscos sueltos sin pareja
    }

    // Encuentra los bloques ```lenguaje ... ``` que escribió la IA
    private fun findCodeBlocks(text: String): List<CodeBlock> =
        Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { CodeBlock(it.groupValues[1].trim(), it.groupValues[2].trimEnd()) }
            .filter { it.code.isNotBlank() }
            .toList()

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Copiado. Ya puedes pegarlo donde quieras.", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Compartir respuesta").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ================= ⬇️ DESCARGAS =================
    private fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Boolean =
        saveStreamToDownloads(context, name, mime) { it.write(bytes) }

    private fun saveStreamToDownloads(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/CumulusIA")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: return@runCatching false
        true
    }.getOrDefault(false)

    private fun codeExtension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
        "html" -> "html"; "css" -> "css"; "json" -> "json"; "xml" -> "xml"; "c" -> "c"; "cpp", "c++" -> "cpp"; "csharp", "cs", "c#" -> "cs"
        "go" -> "go"; "rust", "rs" -> "rs"; "swift" -> "swift"; "dart" -> "dart"; "php" -> "php"; "ruby", "rb" -> "rb"
        "bash", "sh", "shell" -> "sh"; "sql" -> "sql"; "yaml", "yml" -> "yml"; "gradle", "groovy" -> "gradle"; "markdown", "md" -> "md"
        else -> "txt"
    }

    /** Descarga lo que entregó la IA: sus archivos (video, imagen, documento) o, si es solo texto, el texto como .txt */
    private fun downloadMessage(context: Context, message: ChatMessage, cleanText: String) {
        var saved = 0; var already = 0
        message.attachments.forEach { a ->
            if (a.startsWith("deliver:")) {
                val parts = a.removePrefix("deliver:").split("|")
                if (parts.size < 4) return@forEach
                val uri = Uri.parse(parts[1]); val mime = parts[2]; val name = parts[3]
                if (uri.toString().contains("downloads", ignoreCase = true)) { already++; return@forEach }
                val ok = saveStreamToDownloads(context, name, mime) { out ->
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                }
                if (ok) saved++
            } else {
                val f = java.io.File(a)
                if (!f.exists()) return@forEach
                val ext = f.extension.lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                if (saveStreamToDownloads(context, f.name, mime) { out -> f.inputStream().use { it.copyTo(out) } }) saved++
            }
        }
        if (saved == 0 && already == 0) {
            val name = "respuesta_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
            if (saveToDownloads(context, name, "text/plain", cleanText.toByteArray(Charsets.UTF_8))) saved++
        }
        val msg = when {
            saved > 0 -> "⬇️ Descargado en Descargas/CumulusIA" + (if (saved > 1) " ($saved archivos)" else "")
            already > 0 -> "✅ Ya está en Descargas/CumulusIA"
            else -> "No se pudo descargar"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun bindActions(holder: ChatViewHolder, message: ChatMessage) {
        val context = holder.itemView.context
        val isFinalAiReply = !message.isUser && message.text.isNotBlank() &&
            message.text != "Pensando" && !message.text.startsWith("Pensando")

        holder.codeActions.removeAllViews()
        if (!isFinalAiReply) {
            holder.messageActions.visibility = View.GONE
            holder.codeActions.visibility = View.GONE
            return
        }

        holder.messageActions.visibility = View.VISIBLE
        // Se copia/comparte sin los asteriscos del formato
        val cleanText = renderMarkdown(message.text).toString()
        holder.btnCopy.setOnClickListener { copyToClipboard(context, "Cumulus IA", cleanText) }
        holder.btnShare.setOnClickListener { shareText(context, cleanText) }
        // ⬇️ Botón de descarga (gris, mismo estilo que copiar y compartir)
        val actionsRow = holder.btnShare.parent as? LinearLayout
        var btnDownload = actionsRow?.findViewWithTag<View>("btn_download")
        if (actionsRow != null && btnDownload == null) {
            btnDownload = android.widget.ImageButton(context).apply {
                tag = "btn_download"
                setImageResource(R.drawable.ic_action_download)
                contentDescription = "Descargar"
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9A9A9A"))
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = ta.getDrawable(0); ta.recycle()
                val d = context.resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            }
            actionsRow.addView(btnDownload)
        }
        btnDownload?.setOnClickListener { downloadMessage(context, message, cleanText) }

        // Si la respuesta trae código, se arma por partes: texto normal + cuadros de código
        val blocks = findCodeBlocks(message.text)
        if (blocks.isEmpty()) {
            holder.codeActions.visibility = View.GONE
            return
        }
        holder.tvContent.visibility = View.GONE
        holder.codeActions.visibility = View.VISIBLE
        holder.codeActions.setPadding(0, 0, 0, 0)
        val density = context.resources.displayMetrics.density
        val codeRegex = Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        var last = 0
        for (m in codeRegex.findAll(message.text)) {
            addTextSegment(holder.codeActions, message.text.substring(last, m.range.first), density)
            val code = m.groupValues[2].trimEnd()
            if (code.isNotBlank()) {
                holder.codeActions.addView(buildCodeCard(context, m.groupValues[1].trim(), code, density))
            }
            last = m.range.last + 1
        }
        addTextSegment(holder.codeActions, message.text.substring(last), density)
    }

    private fun addTextSegment(container: LinearLayout, raw: String, density: Float) {
        if (raw.isBlank()) return
        container.addView(TextView(container.context).apply {
            text = renderMarkdown(raw.trim('\n'))
            textSize = 16f
            setTextColor(Color.WHITE)
            setLineSpacing(4 * density, 1f)
            setPadding((2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt())
            setTextIsSelectable(true)
        })
    }

    // 🧩 Cuadro de código: barra con el lenguaje + copiar/compartir, y el código con colores
    private fun buildCodeCard(context: Context, language: String, code: String, density: Float): View {
        val dp = { v: Int -> (v * density).toInt() }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#161616"))
                setStroke(dp(1), Color.parseColor("#33343A"))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6); marginStart = dp(4); marginEnd = dp(4) }
        }
        // Barra superior
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.parseColor("#202124"))
        }
        header.addView(TextView(context).apply {
            text = prettyLanguage(language)
            textSize = 13f
            setTextColor(Color.parseColor("#C8C8C8"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun iconButton(icon: Int, desc: String, action: () -> Unit) = android.widget.ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = desc
            setColorFilter(Color.parseColor("#B0B0B0"))
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0)
            ta.recycle()
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { action() }
        }
        header.addView(iconButton(R.drawable.ic_action_copy, "Copiar código") { copyToClipboard(context, language, code) })
        header.addView(iconButton(R.drawable.ic_action_share, "Compartir código") { shareText(context, code) })
        header.addView(iconButton(R.drawable.ic_action_download, "Descargar código") {
            val name = "codigo_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + "." + codeExtension(language)
            val ok = saveToDownloads(context, name, "text/plain", code.toByteArray(Charsets.UTF_8))
            Toast.makeText(context, if (ok) "⬇️ Descargado: Descargas/CumulusIA/$name" else "No se pudo descargar", Toast.LENGTH_LONG).show()
        })
        card.addView(header)
        // Código con colores (se desliza a los lados si las líneas son largas)
        val scroller = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        scroller.addView(TextView(context).apply {
            text = highlightCode(code, language)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#ABB2BF"))
            setLineSpacing(2 * density, 1f)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setTextIsSelectable(true)
        })
        card.addView(scroller)
        return card
    }

    private fun prettyLanguage(lang: String): String {
        val l = lang.lowercase()
        val names = mapOf(
            "kt" to "Kotlin", "kotlin" to "Kotlin", "java" to "Java", "py" to "Python", "python" to "Python",
            "js" to "JavaScript", "javascript" to "JavaScript", "ts" to "TypeScript", "typescript" to "TypeScript",
            "xml" to "Xml", "html" to "HTML", "css" to "CSS", "json" to "JSON", "sh" to "Bash", "bash" to "Bash",
            "c" to "C", "cpp" to "C++", "c++" to "C++", "cs" to "C#", "csharp" to "C#", "go" to "Go", "rust" to "Rust",
            "rs" to "Rust", "swift" to "Swift", "dart" to "Dart", "php" to "PHP", "rb" to "Ruby", "ruby" to "Ruby",
            "sql" to "SQL", "yaml" to "YAML", "yml" to "YAML", "gradle" to "Gradle", "groovy" to "Groovy",
            "cmake" to "CMake", "lua" to "Lua", "md" to "Markdown"
        )
        return "Código " + (names[l] ?: l.replaceFirstChar { it.uppercase() }.ifBlank { "" }).let {
            if (it.isBlank()) "" else it
        }
    }

    // 🎨 Colores del código (estilo oscuro): palabras clave, textos, comentarios, números, etiquetas XML
    private fun highlightCode(code: String, language: String): CharSequence {
        val out = android.text.SpannableString(code)
        val lang = language.lowercase()
        val isMarkup = lang in setOf("xml", "html", "htm", "svg", "vue")
        val hashComments = lang in setOf("py", "python", "sh", "bash", "yaml", "yml", "rb", "ruby", "toml", "cmake", "r", "pl")
        val taken = BooleanArray(code.length)
        fun paint(regex: Regex, color: String, group: Int = 0) {
            val c = Color.parseColor(color)
            for (m in regex.findAll(code)) {
                val r = m.groups[group]?.range ?: continue
                if (r.isEmpty() || (r.first until r.last + 1).any { taken[it] }) continue
                out.setSpan(android.text.style.ForegroundColorSpan(c), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (p in r) taken[p] = true
            }
        }
        // Comentarios primero (tienen prioridad)
        paint(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "#7F848E")
        if (!isMarkup) {
            paint(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "#7F848E")
            paint(Regex("(?m)//.*$"), "#7F848E")
            if (hashComments) paint(Regex("(?m)#.*$"), "#7F848E")
        }
        // Textos entre comillas
        paint(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`"), "#98C379")
        if (isMarkup) {
            paint(Regex("<\\?\\w+|\\?>"), "#61AFEF")
            paint(Regex("</?[\\w:.-]+|/?>"), "#E06C75")
            paint(Regex("([\\w:.-]+)(?==)"), "#D19A66", 1)
        } else {
            paint(Regex("@[\\w.]+"), "#E5C07B")
            val keywords = listOf(
                "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "override", "private",
                "public", "protected", "internal", "open", "abstract", "final", "static", "void", "int", "long", "float",
                "double", "boolean", "char", "byte", "short", "string", "if", "else", "when", "for", "while", "do",
                "return", "break", "continue", "try", "catch", "finally", "throw", "throws", "new", "this", "super",
                "import", "package", "from", "as", "is", "in", "def", "lambda", "pass", "with", "yield", "async",
                "await", "function", "const", "let", "export", "default", "extends", "implements", "true", "false",
                "null", "None", "True", "False", "nil", "self", "struct", "impl", "pub", "mod", "use", "match",
                "switch", "case", "typedef", "include", "define", "namespace", "using", "template", "auto", "func",
                "go", "defer", "select", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
                "and", "or", "not", "elif", "except", "raise", "global", "echo", "then", "fi", "done", "esac"
            ).joinToString("|")
            paint(Regex("\\b($keywords)\\b"), "#C678DD")
            paint(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), "#E5C07B")
            paint(Regex("\\b([a-zA-Z_][\\w]*)(?=\\s*\\()"), "#61AFEF", 1)
        }
        paint(Regex("\\b\\d+(?:\\.\\d+)?[fFLd]?\\b"), "#D19A66")
        return out
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]

        // Las respuestas de la IA se muestran con formato (sin asteriscos)
        holder.tvContent.text = if (message.isUser) message.text else renderMarkdown(message.text)
        // Si solo enviaste adjuntos sin texto, no se muestra una burbuja vacía
        holder.tvContent.visibility = if (message.text.isBlank() && message.attachments.isNotEmpty()) View.GONE else View.VISIBLE
        bindAttachments(holder, message)
        // Mantén presionado para seleccionar una parte y copiarla
        holder.tvContent.setTextIsSelectable(true)
        bindActions(holder, message)
        holder.tvSender.text = if (message.isUser) getUserName() else getAiName()

        // Mostrar los tokens solo si existen en el mensaje de la IA
        if (!message.isUser && message.tokens.isNotEmpty()) {
            holder.tvTokenInfo.text = message.tokens
            holder.tvTokenInfo.visibility = View.VISIBLE
        } else {
            holder.tvTokenInfo.visibility = View.GONE
        }

        // 📐 Ancho: la IA usa toda la pantalla; tu burbuja hasta el 80 %
        val dm = holder.itemView.context.resources.displayMetrics
        val d = dm.density
        if (message.isUser) {
            holder.tvContent.maxWidth = (dm.widthPixels * 0.8f).toInt()
            holder.tvContent.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        } else {
            holder.tvContent.maxWidth = Int.MAX_VALUE
            holder.tvContent.setPadding((2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt())
        }

        if (message.isUser) {
            // Mensaje del usuario: Burbuja redondeada, alineado a la derecha, SIN imagen
            holder.container.gravity = Gravity.END
            holder.ivAiAvatar.visibility = View.GONE
            holder.spaceBetween.visibility = View.GONE
            holder.tvContent.background = holder.itemView.context.getDrawable(R.drawable.bg_user_message)
            holder.tvContent.setTextColor(Color.WHITE)
            holder.tvSender.gravity = Gravity.END
            holder.tvSender.setTextColor(Color.parseColor("#888888"))
        } else {
            // Mensaje de la IA: Texto plano, alineado a la izquierda, CON imagen
            holder.container.gravity = Gravity.START
            holder.ivAiAvatar.visibility = View.VISIBLE
            holder.spaceBetween.visibility = View.VISIBLE
            val customAvatarUri = getAiAvatarUri()
            if (customAvatarUri != null) {
                try {
                    holder.ivAiAvatar.setImageURI(customAvatarUri)
                    if (holder.ivAiAvatar.drawable == null) {
                        holder.ivAiAvatar.setImageResource(R.drawable.cumulus_icon)
                    }
                } catch (e: Exception) {
                    holder.ivAiAvatar.setImageResource(R.drawable.cumulus_icon)
                }
            } else {
                holder.ivAiAvatar.setImageResource(R.drawable.cumulus_icon)
            }
            holder.tvContent.background = null // Quitar fondo para que sea texto plano
            holder.tvContent.setTextColor(Color.WHITE)
            holder.tvSender.gravity = Gravity.START
            holder.tvSender.setTextColor(Color.parseColor("#808080"))
            holder.tvTokenInfo.gravity = Gravity.START
            holder.tvTokenInfo.setTextColor(Color.parseColor("#606060"))
        }
    }

    override fun getItemCount(): Int = messages.size
    }
