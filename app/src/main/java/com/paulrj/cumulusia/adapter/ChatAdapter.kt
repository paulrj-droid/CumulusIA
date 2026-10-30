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
import com.paulrj.cumulusia.AiActivity
import com.paulrj.cumulusia.R
import com.paulrj.cumulusia.SparkleDrawable
import com.paulrj.cumulusia.BrainDrawable
import com.paulrj.cumulusia.StarsDrawable
import com.paulrj.cumulusia.StarName
import com.paulrj.cumulusia.model.ChatMessage
import com.paulrj.cumulusia.ai.ContentReport

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val getAiAvatarUri: () -> Uri? = { null },
    private val getAiName: () -> String = { "Cumulus IA" },  // nombre elegido para la IA
    private val getUserName: () -> String = { "Tú" }          // nombre elegido por el usuario
) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    /** 🚩 Tocar la banderita de una respuesta de la IA: (mensaje, la pregunta que la originó, posición). Lo abre MainActivity. */
    var onReport: ((ChatMessage, String, Int) -> Unit)? = null

    /** 📄 true en el modo Ingeniero de código: el código se entrega como tarjetas de archivo (ej. «MainActivity · Código · KT»). */
    var isCodeMode: () -> Boolean = { false }

    // ⚡ Lo que ya se armó una vez se guarda: al subir y bajar por el chat no se vuelve a armar cada burbuja
    //    (el texto con formato, los colores del código, las fotos y la foto de la IA). Antes se rehacía todo cada vez
    //    y con muchos mensajes el chat se pegaba (más en el modo Ingeniero, con código largo y capturas).
    private val textCache = android.util.LruCache<String, CharSequence>(300)
    private val codeColorCache = android.util.LruCache<String, CharSequence>(80)
    private val codeInfoCache = android.util.LruCache<String, BooleanArray>(400)
    private val bmpCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var avatarCache: Pair<String, android.graphics.Bitmap?>? = null
    private fun keyOf(s: String) = "${s.length}:${s.hashCode()}"

    /** Texto con formato y enlaces (guardado: no se vuelve a armar al deslizar). */
    private fun cachedText(text: String, markdown: Boolean): CharSequence {
        val k = (if (markdown) "m" else "u") + keyOf(text)
        textCache.get(k)?.let { return it }
        return withLinks(if (markdown) renderMarkdown(text) else text).also { textCache.put(k, it) }
    }

    /** Foto achicada (guardada): [max] = el lado más largo en píxeles. */
    private fun cachedBitmap(path: String, max: Int): android.graphics.Bitmap? {
        val f = java.io.File(path)
        val k = "$path|$max|${f.lastModified()}"
        bmpCache.get(k)?.let { return it }
        val bmp = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / sample > max || o.outHeight / sample > max * 2) sample *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        bmpCache.put(k, bmp)
        return bmp
    }

    /** Miniatura de un video (guardada). */
    private fun cachedVideoThumb(file: java.io.File, size: Int): android.graphics.Bitmap? {
        val k = "v|${file.path}|$size|${file.lastModified()}"
        bmpCache.get(k)?.let { return it }
        val bmp = runCatching { android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(size, size), null) }.getOrNull() ?: return null
        bmpCache.put(k, bmp)
        return bmp
    }

    /** 🖼️ Tocar una foto del chat: se abre en grande con la ✕ y el lápiz (la ventana de MainActivity). */
    private fun openChatImage(context: Context, path: String) {
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is com.paulrj.cumulusia.MainActivity) c = c.baseContext
        val act = c as? com.paulrj.cumulusia.MainActivity
        if (act != null) runCatching { act.openChatImage(path) }.onFailure { showFullImage(context, path) }
        else showFullImage(context, path)
    }

    /** 🚩 Banderita dibujada (gris = sin reportar · roja y rellena = ya reportada). */
    private class FlagDrawable(private val color: Int, private val filled: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@FlagDrawable.color
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.58f
            val left = b.left + (b.width() - s) / 2f
            val top = b.top + (b.height() - s) / 2f
            paint.strokeWidth = s * 0.1f
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawLine(left + s * 0.18f, top + s * 0.06f, left + s * 0.18f, top + s * 0.96f, paint)
            val path = android.graphics.Path().apply {
                moveTo(left + s * 0.18f, top + s * 0.1f)
                lineTo(left + s * 0.9f, top + s * 0.1f)
                lineTo(left + s * 0.72f, top + s * 0.32f)
                lineTo(left + s * 0.9f, top + s * 0.56f)
                lineTo(left + s * 0.18f, top + s * 0.56f)
                close()
            }
            paint.style = if (filled) android.graphics.Paint.Style.FILL_AND_STROKE else android.graphics.Paint.Style.STROKE
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** La pregunta del usuario que originó la respuesta de la posición [position]. */
    private fun questionBefore(position: Int): String {
        var i = position - 1
        while (i >= 0) { messages.getOrNull(i)?.let { if (it.isUser) return it.text }; i-- }
        return ""
    }

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
            }.onFailure { Unit /* 🔕 aviso quitado */ }
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
        val bmp = if (thumb.isNotEmpty()) cachedBitmap(thumb, 1400) else null

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
        val bmp = cachedBitmap(path, 1400) ?: return
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
            setOnClickListener { openChatImage(context, path) }   // 🖼️ grande, con ✕ y lápiz
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
            val bmp = when {
                isImage -> cachedBitmap(path, size * 2)
                isVideo -> cachedVideoThumb(file, size)
                else -> null
            }
            // 🖼️ Tocar tu foto la abre en grande (✕ a la izquierda, lápiz a la derecha para marcarla)
            if (isImage) frame.setOnClickListener { openChatImage(context, path) }
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
    // ========================================================================
    // 🔗 ENLACES TOCABLES: https://… en las respuestas (YouTube, fuentes, ofertas…) se abren al tocarlos.
    //    Se abren en la app que corresponde (un enlace de YouTube abre la app de YouTube) y el texto
    //    se puede seguir seleccionando y copiando con un toque largo, como antes.
    // ========================================================================
    private class OpenLinkSpan(val url: String) : android.text.style.ClickableSpan() {
        override fun onClick(widget: View) {
            val ctx = widget.context
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                Toast.makeText(ctx, "No hay una app para abrir este enlace", Toast.LENGTH_SHORT).show()
            }
        }

        override fun updateDrawState(ds: android.text.TextPaint) {
            // 🔗 Celeste, como la «C» de Cumulus. (Se quita el degradado de colores del texto —el que pone StarText
            //    en la letra de todo el mensaje— solo en el enlace: antes lo tapaba y salían del mismo magenta)
            ds.shader = null
            ds.color = LINK_COLOR
            ds.isUnderlineText = true
        }

        // (el mismo celeste de las letras de la app, medido en tus capturas: #26D2FF)
        companion object { val LINK_COLOR: Int = Color.parseColor("#26D2FF") }
    }

    /** Tocar un enlace lo abre; tocar el resto del texto o mantenerlo presionado sigue seleccionando, como siempre. */
    private object LinkSelectMovement : android.text.method.ArrowKeyMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP && widget.selectionStart == widget.selectionEnd) {
                val layout = widget.layout
                if (layout != null) {
                    val x = event.x - widget.totalPaddingLeft + widget.scrollX
                    val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
                    val line = layout.getLineForVertical(y)
                    if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                        val off = layout.getOffsetForHorizontal(line, x)
                        val link = buffer.getSpans(off, off, OpenLinkSpan::class.java).firstOrNull()
                        if (link != null) {
                            link.onClick(widget)
                            return true
                        }
                    }
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }
    }

    private val urlRegex = Regex("https?://[^\\s<>\"«»]+")

    /** 📞 Teléfonos de los locales: «📞 +56 2 2345 6789» se toca para llamar */
    private val phoneRegex = Regex("📞\\s*(\\+?\\d[\\d ()./-]{5,}\\d)")

    /** Marca cada https://… del texto como enlace tocable, y cada 📞 teléfono para llamar. */
    private fun withLinks(text: CharSequence): CharSequence {
        if (!text.contains("http") && !text.contains("📞")) return text
        val out = android.text.SpannableStringBuilder(text)
        for (m in urlRegex.findAll(text)) {
            val url = m.value.trimEnd('.', ',', ';', ':', ')', '!', '?', '»', '"', '\'')
            if (url.length < 12) continue
            out.setSpan(OpenLinkSpan(url), m.range.first, m.range.first + url.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (m in phoneRegex.findAll(text)) {
            val g = m.groups[1] ?: continue
            val digits = g.value.filter { it.isDigit() || it == '+' }
            if (digits.count { it.isDigit() } < 7) continue
            // Abre el teléfono con el número listo (no llama solo: tú tocas llamar)
            out.setSpan(OpenLinkSpan("tel:$digits"), g.range.first, g.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** El texto se puede seleccionar Y sus enlaces se pueden tocar. */
    private fun enableLinks(tv: TextView) {
        tv.setTextIsSelectable(true)
        tv.movementMethod = LinkSelectMovement
    }

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
        Unit /* 🔕 aviso quitado */
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

    // Imágenes → Pictures/CumulusIA (galería) · Videos → Movies/CumulusIA (galería) · lo demás → Descargas/CumulusIA
    private fun saveStreamToDownloads(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Boolean = runCatching {
        val (collection, folder) = when {
            mime.startsWith("image/") -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_MOVIES
            else -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_DOWNLOADS
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/CumulusIA")
        }
        val uri = context.contentResolver.insert(collection, values) ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: return@runCatching false
        // 🔔 Aviso en la barra de notificaciones (tocarlo abre el archivo)
        com.paulrj.cumulusia.manager.SavedFileNotifier.notify(context, uri, name, mime)
        true
    }.getOrDefault(false)

    private fun folderLabel(mime: String) = when {
        mime.startsWith("image/") -> "tu galería (Pictures/CumulusIA)"
        mime.startsWith("video/") -> "tu galería (Movies/CumulusIA)"
        else -> "Descargas/CumulusIA"
    }

    private fun codeExtension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
        "html" -> "html"; "css" -> "css"; "json" -> "json"; "xml" -> "xml"; "c" -> "c"; "cpp", "c++" -> "cpp"; "csharp", "cs", "c#" -> "cs"
        "go" -> "go"; "rust", "rs" -> "rs"; "swift" -> "swift"; "dart" -> "dart"; "php" -> "php"; "ruby", "rb" -> "rb"
        "bash", "sh", "shell" -> "sh"; "sql" -> "sql"; "yaml", "yml" -> "yml"; "gradle", "groovy" -> "gradle"; "markdown", "md" -> "md"
        else -> "txt"
    }

    /** Descarga lo que entregó la IA: sus archivos (video, imagen, documento) o, si es solo texto, el texto como .txt */
    private fun downloadMessage(context: Context, message: ChatMessage, cleanText: String) {
        var saved = 0; var already = 0; val where = linkedSetOf<String>()
        message.attachments.forEach { a ->
            if (a.startsWith("deliver:")) {
                val parts = a.removePrefix("deliver:").split("|")
                if (parts.size < 4) return@forEach
                val uri = Uri.parse(parts[1]); val mime = parts[2]; val name = parts[3]
                // Archivos antiguos que ya estaban guardados en el teléfono
                if (uri.authority?.startsWith("media") == true) { already++; return@forEach }
                val ok = saveStreamToDownloads(context, name, mime) { out ->
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                }
                if (ok) { saved++; where += folderLabel(mime) }
            } else {
                val f = java.io.File(a)
                if (!f.exists()) return@forEach
                val ext = f.extension.lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                if (saveStreamToDownloads(context, f.name, mime) { out -> f.inputStream().use { it.copyTo(out) } }) { saved++; where += folderLabel(mime) }
            }
        }
        if (saved == 0 && already == 0) {
            val name = "respuesta_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
            if (saveToDownloads(context, name, "text/plain", cleanText.toByteArray(Charsets.UTF_8))) { saved++; where += folderLabel("text/plain") }
        }
        val msg = when {
            saved > 0 -> "⬇️ Guardado en " + where.joinToString(" y ") + (if (saved > 1) " ($saved archivos)" else "")
            already > 0 -> "✅ Ya estaba guardado en tu teléfono"
            else -> "No se pudo descargar"
        }
        Unit /* 🔕 aviso quitado */
    }

    private fun bindActions(holder: ChatViewHolder, message: ChatMessage, position: Int = -1) {
        val context = holder.itemView.context
        val isFinalAiReply = !message.isUser && message.text.isNotBlank() &&
            message.text != "Pensando" && !message.text.startsWith("Pensando")

        // 🖼️ Respuesta de la IA que es solo una imagen o archivo: igual se puede reportar
        val aiMediaOnly = !message.isUser && message.text.isBlank() && message.attachments.isNotEmpty()

        holder.codeActions.removeAllViews()
        if (!isFinalAiReply && !aiMediaOnly) {
            holder.messageActions.visibility = View.GONE
            holder.codeActions.visibility = View.GONE
            return
        }

        holder.messageActions.visibility = View.VISIBLE
        // Copiar, compartir y descargar solo cuando hay texto (se vuelven a mostrar al reciclar la fila)
        val textActions = if (aiMediaOnly) View.GONE else View.VISIBLE
        holder.btnCopy.visibility = textActions
        holder.btnShare.visibility = textActions
        (holder.btnShare.parent as? LinearLayout)?.findViewWithTag<View>("btn_download")?.visibility = textActions
        // Se copia/comparte sin los asteriscos del formato
        val cleanText by lazy { renderMarkdown(message.text).toString() }   // (solo se arma si tocas copiar, compartir o descargar)
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
        btnDownload?.visibility = textActions

        // 🚩 Reportar esta respuesta (Google Play: contenido generado por IA): roja si ya la reportaste
        var btnReport = actionsRow?.findViewWithTag<android.widget.ImageButton>("btn_report")
        if (actionsRow != null && btnReport == null) {
            btnReport = android.widget.ImageButton(context).apply {
                tag = "btn_report"
                contentDescription = "Reportar respuesta"
                scaleType = ImageView.ScaleType.CENTER
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = ta.getDrawable(0); ta.recycle()
                val d = context.resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            }
            actionsRow.addView(btnReport)
        }
        val reported = ContentReport.isReported(context, ContentReport.key(message.timestamp, message.text))
        btnReport?.setImageDrawable(FlagDrawable(Color.parseColor(if (reported) "#FF453A" else "#9A9A9A"), reported))
        btnReport?.contentDescription = if (reported) "Respuesta reportada" else "Reportar respuesta"
        btnReport?.setOnClickListener {
            val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
            onReport?.invoke(message, questionBefore(pos), pos)
        }
        if (aiMediaOnly) { holder.codeActions.visibility = View.GONE; return }

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
        val asFiles = runCatching { isCodeMode() }.getOrDefault(false)
        var fileIndex = 0
        for (m in codeRegex.findAll(message.text)) {
            addTextSegment(holder.codeActions, message.text.substring(last, m.range.first), density)
            val code = m.groupValues[2].trimEnd()
            if (code.isNotBlank()) {
                val lang = m.groupValues[1].trim()
                if (asFiles) {
                    // 📄 Modo Ingeniero de código: tarjeta de archivo; al tocarla se abre la ventana del archivo
                    val name = CodeFileViewer.fileNameFor(message.text.substring(last, m.range.first), lang, code, fileIndex++)
                    holder.codeActions.addView(CodeFileViewer.card(context, name, lang, code) { c, l -> highlightCode(c, l) })
                } else {
                    holder.codeActions.addView(buildCodeCard(context, lang, code, density))
                }
            }
            last = m.range.last + 1
        }
        addTextSegment(holder.codeActions, message.text.substring(last), density)
        // 🖥️/📱 Invita a abrir la vista previa web o la maqueta de la app
        addPreviewCards(holder.codeActions, message.text, density, position)
    }

    private fun addTextSegment(container: LinearLayout, raw: String, density: Float) {
        if (raw.isBlank()) return
        container.addView(TextView(container.context).apply {
            text = cachedText(raw.trim('\n'), true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setLineSpacing(4 * density, 1f)
            setPadding((2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt())
            enableLinks(this)   // 🔗 seleccionable y con enlaces tocables
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
            Unit /* 🔕 aviso quitado */
        })
        card.addView(header)
        // Código con colores (se desliza a los lados si las líneas son largas)
        val scroller = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_LTR   // ↔️ el código siempre de izquierda a derecha (también en árabe)
        }
        scroller.addView(TextView(context).apply {
            textDirection = View.TEXT_DIRECTION_LTR
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
        val k = language.lowercase() + "|" + keyOf(code)
        codeColorCache.get(k)?.let { return it }
        return colorize(code, language).also { codeColorCache.put(k, it) }
    }

    companion object {
    /**
     * 🎙️ Lo que dijiste por voz con «Chat en pantalla»: queda en el chat (así la IA recuerda la conversación y
     * entiende «el 1», «sí», «el de la derecha»…) pero NO se ve: para ti es solo voz.
     */
    const val VOICE_HIDDEN_SENDER = "🎙️voz"

    /** 🎨 Pone los colores al código (también lo usa la ventana «en vivo» mientras la IA escribe). */
    fun colorize(code: String, language: String): CharSequence {
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        // 🎙️ Tu mensaje dicho por voz (Chat en pantalla): está, pero no se ve
        val hiddenVoice = message.isUser && message.sender == VOICE_HIDDEN_SENDER
        holder.itemView.visibility = if (hiddenVoice) View.GONE else View.VISIBLE
        holder.itemView.layoutParams?.let { lp ->
            val h = if (hiddenVoice) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height != h) { lp.height = h; holder.itemView.layoutParams = lp }
        }
        if (hiddenVoice) return

        // Las respuestas de la IA se muestran con formato (sin asteriscos)
        holder.tvContent.text = cachedText(message.text, !message.isUser)
        // Si solo enviaste adjuntos sin texto, no se muestra una burbuja vacía
        holder.tvContent.visibility = if (message.text.isBlank() && message.attachments.isNotEmpty()) View.GONE else View.VISIBLE
        bindAttachments(holder, message)
        // Mantén presionado para seleccionar una parte y copiarla · 🔗 toca un enlace para abrirlo
        enableLinks(holder.tvContent)
        bindActions(holder, message, position)
        holder.tvSender.text = if (message.isUser) getUserName() else StarName.styled(getAiName())   // ✨ mitad blanco, mitad colores de las estrellas

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
                // ⚡ La foto de la IA se lee UNA vez (chica) y se reusa en todas las burbujas (antes se leía entera en cada una)
                //  (si cambias la foto, aunque quede en el mismo archivo, se vuelve a leer)
                val avKey = customAvatarUri.toString() + "|" + (customAvatarUri.path?.takeIf { customAvatarUri.scheme == "file" }
                    ?.let { java.io.File(it).lastModified() } ?: 0L)
                if (avatarCache?.first != avKey) avatarCache = avKey to runCatching {
                    val cr = holder.itemView.context.contentResolver
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(customAvatarUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (o.outWidth / sample > 256 || o.outHeight / sample > 256) sample *= 2
                    cr.openInputStream(customAvatarUri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull()
                val av = avatarCache?.second
                if (av != null) holder.ivAiAvatar.setImageBitmap(av)
                else try {
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
        // ✦ Mientras la IA trabaja: estrella morada girando + «Pensando...» / «Analizando...» + ›
        bindWorking(holder, message, position)
    }

    /**
     * ✦ Burbuja de «trabajando»: estrella morada que gira, «Pensando...» o «Analizando...»,
     * lo que está haciendo ahora (en chico) y la flecha › que abre la ventana con cada paso.
     */
    private fun bindWorking(holder: ChatViewHolder, message: ChatMessage, position: Int) {
        val working = !message.isUser && AiActivity.isWorking(position)
        var row = holder.container.findViewWithTag<LinearLayout>("ai_working_row")
        if (!working) {
            if (row != null) holder.container.removeView(row)
            return
        }
        val context = holder.itemView.context
        val d = context.resources.displayMetrics.density
        if (row == null) {
            row = buildWorkingRow(context, d)
            // Justo debajo del avatar y el nombre de la IA
            holder.container.addView(row, minOf(1, holder.container.childCount))
        }
        // Mientras trabaja no se muestra el texto «Pensando» ni los botones de copiar/compartir
        holder.tvContent.visibility = View.GONE
        holder.codeActions.visibility = View.GONE
        holder.messageActions.visibility = View.GONE
        holder.tvTokenInfo.visibility = View.GONE
        row.findViewWithTag<TextView>("ai_working_label")?.text = AiActivity.label
        // Lo que está haciendo ahora (el último paso); si no hay, el aviso de la burbuja
        val status = AiActivity.lastStep()?.takeIf { it.isNotBlank() }
            ?: message.text.trim().takeIf { it.isNotEmpty() && !it.startsWith("Pensando") }.orEmpty()
        // ⚙️ PROCESO EN CURSO: lo que ya hizo (resumido, ej. «Buscó en internet · 3») y lo que está haciendo
        //    ahora con puntitos animados (ej. «Leyendo 3 páginas»). Si todavía no hay pasos, el aviso de antes.
        val steps = AiActivity.stepsSnapshot()
        val stepsBox = row.findViewWithTag<LinearLayout>("ai_working_steps")
        if (stepsBox != null && steps.isNotEmpty()) {
            bindProcess(stepsBox, steps, d)
            stepsBox.visibility = View.VISIBLE
            row.findViewWithTag<TextView>("ai_working_status")?.visibility = View.GONE
        } else {
            stepsBox?.visibility = View.GONE
            row.findViewWithTag<TextView>("ai_working_status")?.apply {
                text = status
                visibility = if (status.isBlank()) View.GONE else View.VISIBLE
            }
        }
        // 📱 App con Kotlin: apenas está creada la parte visual (diseño XML), se invita a ver la maqueta.
        //    (La vista previa web se ofrece cuando la app está terminada, en la tarjeta de la respuesta.)
        val visualReady = AiActivity.previewReady && AiActivity.previewKind == "app"
        row.findViewWithTag<TextView>("ai_working_preview")?.apply {
            visibility = if (visualReady) View.VISIBLE else View.GONE
            text = "📱 Ya está la parte visual: entra a la maqueta  ›"
            setOnClickListener { AiActivity.onOpenPreview?.invoke("app") }
        }
        row.setOnClickListener { AiActivity.showPanel(context) }
    }

    private fun buildWorkingRow(context: Context, d: Float): LinearLayout {
        val dp = { v: Int -> (v * d).toInt() }
        val box = LinearLayout(context).apply {
            tag = "ai_working_row"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = ta.getDrawable(0); ta.recycle()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 🧠 Cerebro azul que gira en círculo: más rápido cuando la IA piensa rápido, más lento cuando piensa lento
        line.addView(ImageView(context).apply {
            setImageDrawable(StarsDrawable(AiActivity.LIGHT_BLUE) { AiActivity.running })   // ✨ estrellas (antes cerebro)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
        })
        line.addView(TextView(context).apply {
            tag = "ai_working_label"
            textSize = 18f
            setTextColor(Color.parseColor("#9A9A9A"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // › Abre la ventana con lo que está haciendo (búsquedas, páginas, motor, comandos...)
        line.addView(TextView(context).apply {
            text = arrowForward(context)
            textSize = 26f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(12), 0, dp(6), dp(3))
        })
        box.addView(line)
        box.addView(TextView(context).apply {
            tag = "ai_working_status"
            textSize = 12.5f
            setTextColor(Color.parseColor("#6E6E73"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(42), dp(2), dp(8), 0)   // alineado con «Pensando...» (cerebro de 32 + 10)
        })
        // ⚙️ Proceso en curso (pasos hechos + el paso actual con puntitos animados)
        box.addView(LinearLayout(context).apply {
            tag = "ai_working_steps"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(8), 0)
            visibility = View.GONE
        })
        box.addView(TextView(context).apply {
            tag = "ai_working_preview"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d; setColor(Color.parseColor("#3A2E5C")); setStroke(dp(1), AiActivity.PURPLE)
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(42); topMargin = dp(8) }
        })
        return box
    }

    /** › hacia adelante: en árabe (de derecha a izquierda) apunta al otro lado ‹ */
    private fun arrowForward(context: Context): String =
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "‹" else "›"

    // ======================== ⚙️ PROCESO EN CURSO ========================

    /** Tipo de paso: para juntar los que son iguales en una sola línea («Buscó en internet · 3»). */
    private fun stepKind(text: String): String {
        val t = text.lowercase()
        return when {
            Regex("actualizando la ia|versiones de hoy|versiones y la documentaci").containsMatchIn(t) -> "update"
            Regex("leyendo .*p[aá]gina|le[ií] .*p[aá]gina|wikipedia|medlineplus|abriendo").containsMatchIn(t) -> "read"
            Regex("buscando|busco |me pediste buscar|consultando").containsMatchIn(t) -> "search"
            Regex("escribiendo|archivo|planificando|base de la (app|p[aá]gina)|l[oó]gica|maqueta|programarlo|se cort[oó]").containsMatchIn(t) -> "code"
            Regex("descargando|mirando|foto|miniatura|afiche|portada|cuadros del video").containsMatchIn(t) -> "media"
            Regex("motor|modo .*pensamiento|contexto|reintento|repar").containsMatchIn(t) -> "engine"
            else -> "other"
        }
    }

    /** Cómo se dice en pasado lo que ya terminó (null = se muestra el paso tal cual). */
    private fun pastLabel(kind: String, n: Int): String? = when (kind) {
        "search" -> if (n == 1) "Buscó en internet" else "Hizo $n búsquedas"
        "read" -> if (n == 1) "Leyó páginas de internet" else "Leyó páginas de internet · $n pasos"
        "code" -> if (n == 1) "Trabajó en el código" else "Trabajó en el código · $n pasos"
        "media" -> if (n == 1) "Reunió fotos e imágenes" else "Reunió fotos e imágenes · $n pasos"
        "engine" -> if (n == 1) "Preparó el motor" else "Preparó el motor · $n pasos"
        "update" -> "Actualizó la IA con internet"
        else -> null
    }

    /** Quita el emoji del comienzo («🔎 Buscando…» → «Buscando…»): el ícono lo pone la línea. */
    private fun plainStep(text: String): String =
        text.replace(Regex("^[^\\p{L}\\p{N}«¿¡\"(]+"), "").trim().ifEmpty { text.trim() }

    private fun bindProcess(box: LinearLayout, steps: List<AiActivity.Step>, d: Float) {
        val context = box.context
        val dp = { v: Int -> (v * d).toInt() }
        box.removeAllViews()
        // Lo que ya terminó: los pasos seguidos del mismo tipo van juntos en una línea
        val done = steps.dropLast(1)
        val groups = mutableListOf<Pair<String, MutableList<String>>>()
        done.forEach { s ->
            val k = stepKind(s.text)
            if (k != "other" && groups.lastOrNull()?.first == k) groups.last().second.add(s.text)
            else groups.add(k to mutableListOf(s.text))
        }
        val shown = groups.takeLast(4)
        if (groups.size > shown.size) box.addView(TextView(context).apply {
            text = "… y ${groups.size - shown.size} paso(s) antes"
            textSize = 12.5f
            setTextColor(Color.parseColor("#5E5E63"))
            setPadding(dp(30), 0, 0, dp(4))
        })
        shown.forEach { (kind, texts) ->
            val label = pastLabel(kind, texts.size) ?: plainStep(texts.last())
            box.addView(processLine(context, d, null, label, current = false))
        }
        // Lo que está haciendo AHORA: puntitos animados + texto con brillo que pasa
        steps.lastOrNull()?.let { box.addView(processLine(context, d, ProcessDots(), plainStep(it.text), current = true)) }
    }

    private fun processLine(context: Context, d: Float, dots: android.graphics.drawable.Drawable?, label: String, current: Boolean): View {
        val dp = { v: Int -> (v * d).toInt() }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        if (dots != null) line.addView(ImageView(context).apply { setImageDrawable(dots) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        else line.addView(TextView(context).apply {
            text = "✓"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6E6E73"))
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        val tv: TextView = if (current) ShimmerTextView(context) else TextView(context)
        tv.apply {
            text = label
            textSize = if (current) 16f else 14.5f
            setTextColor(Color.parseColor(if (current) "#9A9A9A" else "#7A7A7F"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        line.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(context).apply {
            text = arrowForward(context)
            textSize = 18f
            setTextColor(Color.parseColor("#6E6E73"))
            setPadding(dp(8), 0, dp(4), dp(2))
        })
        return line
    }

    /**
     * ⁘ Cuatro puntitos que laten uno tras otro mientras la IA trabaja, con los colores de las letras de «Cumulus»:
     * degradado celeste → magenta (en modo claro, oscuros como las letras). Antes eran naranjos.
     */
    private class ProcessDots : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var shaderFor = android.graphics.Rect()
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            if (s <= 0f) return
            if (com.paulrj.cumulusia.manager.StarText.light) {
                paint.shader = null; paint.color = Color.parseColor("#1C1C1E"); shaderFor = android.graphics.Rect()
            } else if (shaderFor != b || paint.shader == null) {
                shaderFor = android.graphics.Rect(b)
                paint.color = Color.WHITE
                paint.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.left + s, b.top + s,
                    com.paulrj.cumulusia.manager.StarText.C1, com.paulrj.cumulusia.manager.StarText.C2,
                    android.graphics.Shader.TileMode.CLAMP)
            }
            val r = s * 0.13f
            val t = (android.os.SystemClock.uptimeMillis() % 1200L) / 1200f
            val pos = listOf(0.3f to 0.3f, 0.7f to 0.3f, 0.7f to 0.7f, 0.3f to 0.7f)   // en círculo
            pos.forEachIndexed { i, (x, y) ->
                val ph = ((t - i * 0.25f) % 1f + 1f) % 1f
                paint.alpha = (90 + 165 * (1f - ph)).toInt().coerceIn(60, 255)
                canvas.drawCircle(b.left + s * x, b.top + s * y, r, paint)
            }
            if (AiActivity.running) invalidateSelf()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** ✨ Texto con un brillo que lo recorre (como «Ejecutando un comando»). */
    private class ShimmerTextView(context: Context) : TextView(context) {
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat().coerceAtLeast(1f)
            val t = (android.os.SystemClock.uptimeMillis() % 1800L) / 1800f
            val x = -0.6f * w + t * 2.2f * w
            paint.shader = android.graphics.LinearGradient(
                x, 0f, x + 0.6f * w, 0f,
                intArrayOf(Color.parseColor("#8A8A8E"), Color.WHITE, Color.parseColor("#8A8A8E")),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            super.onDraw(canvas)
            if (AiActivity.running && isAttachedToWindow) postInvalidateOnAnimation()
        }
    }

    /**
     * 🖥️/📱 Al final de una respuesta con código: tarjeta que invita a abrir la vista previa web
     * (HTML, CSS, JavaScript) o la maqueta de la app (diseños XML de Android).
     */
    private fun addPreviewCards(container: LinearLayout, text: String, density: Float, position: Int = -1) {
        val info = codeInfo(text)
        val hasLayout = info[1]
        // 📱 En una app Android, su maqueta.js no es una app web: la tarjeta «app web» sale solo si trae una página HTML
        val hasWeb = info[0] && (!hasLayout || info[2])
        if (!hasWeb && !hasLayout) return
        // ¿Ya había una versión antes en este chat? → «actualicé…» (para ir viendo cómo va quedando)
        //  (se mira lo que ya se calculó de cada mensaje: antes se revisaba todo el chat de nuevo en cada burbuja)
        fun earlierHas(i: Int): Boolean = (0 until position.coerceAtMost(messages.size)).any { k ->
            val m = messages[k]; !m.isUser && m.text.contains("```") && codeInfo(m.text)[i] }
        if (hasWeb) {
            val again = earlierHas(0)
            container.addView(previewCard(container.context, density, "🖥️",
                if (again) "Actualicé tu app web. Ábrela en la vista previa para ver cómo va quedando."
                else "Tu app web está terminada. Ábrela en la vista previa para verla funcionando y probarla.",
                "Abrir vista previa") { AiActivity.onOpenPreview?.invoke("web") })
        }
        if (hasLayout) {
            val again = earlierHas(1)
            container.addView(previewCard(container.context, density, "📱",
                if (again) "Actualicé la parte visual de tu app. Entra a la maqueta para ver cómo va quedando."
                else "Ya está creada la parte visual de tu app. Entra a la maqueta para ver cómo se ve.",
                "Ver maqueta") { AiActivity.onOpenPreview?.invoke("app") })
        }
    }

    /** [¿app web?, ¿diseño de app?, ¿página HTML?] de una respuesta (guardado). */
    private fun codeInfo(text: String): BooleanArray {
        val k = keyOf(text)
        codeInfoCache.get(k)?.let { return it }
        val html = text.contains("```") && codeBlocksOf(text).any { (lang, code) ->
            lang in setOf("html", "htm") || Regex("(?i)<!doctype html|<html[\\s>]").containsMatchIn(code) }
        return booleanArrayOf(hasWebCode(text), hasLayoutCode(text), html).also { codeInfoCache.put(k, it) }
    }

    private fun codeBlocksOf(text: String): List<Pair<String, String>> =
        Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .map { it.groupValues[1].lowercase() to it.groupValues[2] }.toList()

    /** 🖥️ ¿La respuesta trae una app web (HTML, CSS o JavaScript)? */
    private fun hasWebCode(text: String): Boolean = text.contains("```") && codeBlocksOf(text).any { (lang, code) ->
        lang in setOf("html", "htm", "css", "js", "javascript", "jsx") || Regex("(?i)<!doctype html|<html[\\s>]").containsMatchIn(code)
    }

    /** 📱 ¿La respuesta trae la parte visual de una app Android (un diseño XML)? */
    private fun hasLayoutCode(text: String): Boolean = text.contains("```") && codeBlocksOf(text).any { (lang, code) ->
        (lang == "xml" || code.trimStart().startsWith("<")) && !code.contains("<manifest") &&
            Regex("<(LinearLayout|RelativeLayout|FrameLayout|ScrollView|TableLayout|GridLayout|[\\w.]*ConstraintLayout|[\\w.]*CoordinatorLayout|" +
                "[\\w.]*CardView|[\\w.]*DrawerLayout|[\\w.]*NestedScrollView)[\\s>]").containsMatchIn(code)
    }

    private fun previewCard(context: Context, d: Float, icon: String, message: String, button: String, onOpen: () -> Unit): View {
        val dp = { v: Int -> (v * d).toInt() }
        // Tarjeta en columna: arriba el ícono y el texto (se lee de izquierda a derecha, a lo ancho),
        // abajo el botón a todo el ancho (antes el botón al costado apretaba el texto en una columna angosta)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d; setColor(Color.parseColor("#1C1A24")); setStroke(dp(1), AiActivity.PURPLE)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8); bottomMargin = dp(4); marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onOpen() }
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        top.addView(TextView(context).apply { text = icon; textSize = 24f; setPadding(0, 0, dp(12), 0) })
        top.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor("#E5E5EA"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(top)
        card.addView(TextView(context).apply {
            text = button
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(Color.parseColor("#6E56CF")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            setOnClickListener { onOpen() }
        })
        return card
    }

    override fun getItemCount(): Int = messages.size
    }

