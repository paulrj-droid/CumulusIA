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
    private val getAiAvatarUri: () -> Uri? = { null }
) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvContent: TextView = view.findViewById(R.id.tvMessageContent)
        val tvTokenInfo: TextView = view.findViewById(R.id.tvTokenInfo)
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val ivAiAvatar: ImageView = view.findViewById(R.id.iv_ai_avatar)
        val spaceBetween: Space = view.findViewById(R.id.space_between)
        val codeActions: LinearLayout = view.findViewById(R.id.codeActions)
        val messageActions: LinearLayout = view.findViewById(R.id.messageActions)
        val btnCopy: TextView = view.findViewById(R.id.btnCopyMessage)
        val btnShare: TextView = view.findViewById(R.id.btnShareMessage)
    }

    private data class CodeBlock(val language: String, val code: String)

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
        holder.btnCopy.setOnClickListener { copyToClipboard(context, "Cumulus IA", message.text) }
        holder.btnShare.setOnClickListener { shareText(context, message.text) }

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

        holder.tvContent.text = message.text
        // Mantén presionado para seleccionar una parte y copiarla
        holder.tvContent.setTextIsSelectable(true)
        bindActions(holder, message)
        holder.tvSender.text = if (message.isUser) "Tú" else "Cumulus IA"

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
                        holder.ivAiAvatar.setImageResource(R.drawable.ic_cumulus_diamond)
                    }
                } catch (e: Exception) {
                    holder.ivAiAvatar.setImageResource(R.drawable.ic_cumulus_diamond)
                }
            } else {
                holder.ivAiAvatar.setImageResource(R.drawable.ic_cumulus_diamond)
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
