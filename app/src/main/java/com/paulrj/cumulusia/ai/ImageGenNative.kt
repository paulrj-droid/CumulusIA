package com.paulrj.cumulusia.ai

/**
 * 🪄 Puente con la IA que crea versiones nuevas de una imagen (stable-diffusion.cpp, en la CPU).
 * La librería libcumulus_sd.so la compila el workflow de GitHub aparte del motor del chat.
 * Si en una compilación no se pudo incluir, [available] es false y la app lo explica en el chat
 * (todo lo demás sigue funcionando).
 */
object ImageGenNative {

    /** true = la librería está en el APK y se cargó bien. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("cumulus_sd")
            true
        } catch (t: Throwable) {
            loadError = t.message.orEmpty()
            false
        }
    }

    @Volatile var loadError: String = ""
        private set

    external fun nativeInit(crashDir: String)
    external fun nativeLoad(modelPath: String, threads: Int): Boolean
    external fun nativeModelVersion(): String
    external fun nativeImg2Img(
        rgb: ByteArray, w: Int, h: Int, prompt: String, negative: String,
        strength: Float, steps: Int, cfg: Float, seed: Long, oneStep: Boolean
    ): ByteArray?
    external fun nativeCancel()
    external fun nativeFree()
    external fun nativeProgress(): Int
    external fun nativeLastError(): String

    @Volatile private var inited = false

    fun init(crashDir: String) {
        if (inited || !available) return
        runCatching { nativeInit(crashDir) }
        inited = true
    }

    /** ⏹️ Se puede llamar desde cualquier hilo (no hace nada si la librería no está). */
    fun cancel() {
        if (available) runCatching { nativeCancel() }
    }

    /** Libera la memoria del modelo de imágenes. */
    fun free() {
        if (available) runCatching { nativeFree() }
    }

    fun lastError(): String = if (available) runCatching { nativeLastError() }.getOrDefault("") else loadError
}

