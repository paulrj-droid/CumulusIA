package com.cumulusia.app.model

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val sender: String,
    val tokens: String = ""
)

