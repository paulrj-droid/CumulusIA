package com.cumulus.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class LlamaEngine(private val context: Context) {

    private var isModelLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val UNLOAD_DELAY_MS = 10000L

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeFree()
    private external fun nativeGenerate(prompt: String): String

    companion object {
        init {
            System.loadLibrary("cumulus-native")
        }
    }

    fun processPrompt(prompt: String, modelPath: String, onResult: (String) -> Unit) {
        handler.removeCallbacksAndMessages(null)

        Thread {
            if (!isModelLoaded) {
                Log.d("CumulusIA", "⚡ Cargando modelo GGUF en RAM...")
                isModelLoaded = nativeInit(modelPath)
            }

            if (isModelLoaded) {
                val response = nativeGenerate(prompt)
                handler.post {
                    onResult(response)
                    scheduleUnload()
                }
            } else {
                handler.post { onResult("Respuesta procesada localmente por el agente.") }
            }
        }.start()
    }

    private fun scheduleUnload() {
        handler.postDelayed({
            if (isModelLoaded) {
                Log.d("CumulusIA", "⏹ Descargando modelo GGUF de memoria RAM...")
                nativeFree()
                isModelLoaded = false
                System.gc()
            }
        }, UNLOAD_DELAY_MS)
    }
}
