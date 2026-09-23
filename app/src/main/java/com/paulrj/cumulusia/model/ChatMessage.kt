package com.cumulusia.app.model

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String = if (isUser) "Tú" else "Cumulus IA",
    val tokens: String = ""
)
