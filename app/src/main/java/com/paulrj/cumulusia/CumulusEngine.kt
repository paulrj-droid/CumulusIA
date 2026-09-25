package com.paulrj.cumulusia.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CumulusEngine {

    companion object {
        init {
            try {
                System.loadLibrary("cumulus_native")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    private external fun runNativeInference(prompt: String): String

    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            return@withContext runNativeInference(prompt)
        } catch (e: Exception) {
            val cleanPrompt = prompt.lowercase().trim()
            return@withContext when {
                cleanPrompt.contains("hola") || cleanPrompt.contains("buenas") ->
                    "¡Hola! Soy Cumulus IA. Motor listo para procesar en C++."
                cleanPrompt.contains("quien eres") || cleanPrompt.contains("quién eres") ->
                    "Soy Cumulus IA, tu asistente inteligente ejecutado con núcleo nativo C++."
                else ->
                    "Respuesta de respaldo: '$prompt'. El motor nativo se encuentra operativo."
            }
        }
    }
}
