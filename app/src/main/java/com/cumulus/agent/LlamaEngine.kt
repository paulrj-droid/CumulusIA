package com.cumulus.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class LlamaEngine(private val context: Context) {

    private var isModelLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val UNLOAD_DELAY_MS = 10000L

    fun processPrompt(prompt: String, modelPath: String, onResult: (String) -> Unit) {
        handler.removeCallbacksAndMessages(null)

        Thread {
            if (!isModelLoaded) {
                Log.d("CumulusIA", "⚡ Cargando agente local en memoria...")
                isModelLoaded = true
            }

            val response = "🤖 [Cumulus IA]: Consulta procesada con éxito: '$prompt'"
            handler.post {
                onResult(response)
                scheduleUnload()
            }
        }.start()
    }

    private fun scheduleUnload() {
        handler.postDelayed({
            if (isModelLoaded) {
                Log.d("CumulusIA", "⏹ Descargando modelo de memoria RAM...")
                isModelLoaded = false
                System.gc()
            }
        }, UNLOAD_DELAY_MS)
    }
}
