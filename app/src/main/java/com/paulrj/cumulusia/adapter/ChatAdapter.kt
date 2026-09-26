package com.paulrj.cumulusia.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.paulrj.cumulusia.R
import com.paulrj.cumulusia.model.ChatMessage

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val getBubbleImageUri: () -> Uri?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false)
            AiMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserMessageViewHolder) {
            holder.bind(message)
        } else if (holder is AiMessageViewHolder) {
            holder.bind(message, getBubbleImageUri())
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message_text)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.text
        }
    }

    class AiMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message_text)
        private val tvSender: TextView = view.findViewById(R.id.tv_sender_name)
        private val tvTokens: TextView = view.findViewById(R.id.tv_token_count)
        private val ivAvatar: ImageView = view.findViewById(R.id.iv_ai_avatar)

        fun bind(message: ChatMessage, customImageUri: Uri?) {
            tvMessage.text = message.text
            tvSender.text = message.sender
            if (message.tokens.isNotEmpty()) {
                tvTokens.text = message.tokens
                tvTokens.visibility = View.VISIBLE
            } else {
                tvTokens.visibility = View.GONE
            }

            if (customImageUri != null) {
                try {
                    ivAvatar.setImageURI(customImageUri)
                } catch (e: Exception) {
                    ivAvatar.setImageResource(R.drawable.ic_assistant)
                }
            } else {
                ivAvatar.setImageResource(R.drawable.ic_assistant)
            }
        }
    }
}
