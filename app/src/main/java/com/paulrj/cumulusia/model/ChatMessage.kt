package com.paulrj.cumulusia.model

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String = if (isUser) "Tú" else "Cumulus IA", // Añadido para el nombre
    val tokens: String = "", // Añadido para mostrar los tokens abajo
    val attachments: List<String> = emptyList() // 📎 Rutas de imágenes/archivos/videos enviados
)

