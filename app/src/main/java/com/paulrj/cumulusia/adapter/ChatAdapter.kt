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
            ivAiAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
        }
        val spaceBetween: Space = view.findViewById(R.id.space_between)
        val codeActions: LinearLayout = view.findViewById(R.id.codeActions)
        val messageActions: LinearLayout = view.findViewById(R.id.messageActions)
        val btnCopy: TextView = view.findViewById(R.id.btnCopyMessage)
        val btnShare: TextView = view.findViewById(R.id.btnShareMessage)
        val attachmentsScroll: View = view.findViewById(R.id.messageAttachmentsScroll)
        val attachmentsRow: LinearLayout = view.findViewById(R.id.messageAttachments)
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
            val file = java.io.File(path)
            val ext = file.extension.lowercase()
            val isImage = ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
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

        // Un botón por cada bloque de código: copia solo el código
        val blocks = findCodeBlocks(message.text)
        if (blocks.isEmpty()) {
            holder.codeActions.visibility = View.GONE
            return
        }
        holder.codeActions.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        blocks.forEachIndexed { index, block ->
            val name = block.language.ifBlank { "código" }
            val label = if (blocks.size > 1) "💻 Copiar $name (${index + 1})" else "💻 Copiar $name"
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            fun chip(text: String, action: () -> Unit) = TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.parseColor("#8C9EFF"))
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_input_rounded)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E2A5A"))
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (32 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener { action() }
            }
            row.addView(chip(label) { copyToClipboard(context, name, block.code) })
            row.addView(chip("🔗") { shareText(context, block.code) })
            holder.codeActions.addView(row)
        }
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

