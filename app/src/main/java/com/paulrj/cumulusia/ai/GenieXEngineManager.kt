package com.paulrj.cumulusia.ai

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ⚡ Motor GenieX (SDK oficial de Qualcomm) para modelos GGUF en NPU / GPU.
 * Usa llama.cpp con los parches de Qualcomm para Hexagon (NPU) y Adreno (GPU).
 * Recomendado: modelos GGUF Q4_0 (la NPU Hexagon los soporta mejor).
 */
object GenieXEngineManager {
    private const val TAG = "CumulusGenieX"
    private val mutex = Mutex()

    private var llm: LlmWrapper? = null
    private var loadedPath: String? = null
    private var loadedUnit: String? = null
    private var loadedCtx: Int = 2048
    private var lastSystem: String? = null
    @Volatile private var label: String = "GenieX sin cargar"
    @Volatile private var sdkReady = false
    @Volatile private var lastErrors: String = ""
    @Volatile private var lastStats: String = ""
    @Volatile private var initInfo: String = "init: no llamado"

    private fun chain(t: Throwable?): String {
        val sb = StringBuilder(); var c = t; var n = 0
        while (c != null && n < 6) {
            if (n > 0) sb.append("\n   causa: ")
            sb.append(c.javaClass.simpleName).append(": ").append(c.message ?: "")
            c = c.cause; n++
        }
        return sb.toString()
    }

    /** Info para el diálogo de error: estado del SDK y librerías GenieX/QNN dentro del APK. */
    fun diagnostics(context: Context): String {
        val dir = java.io.File(context.applicationInfo.nativeLibraryDir)
        val libs = dir.listFiles()?.map { it.name }?.filter {
            it.contains("geniex", true) || it.contains("llama", true) || it.contains("ggml", true) ||
            it.contains("Qnn", true) || it.contains("htp", true) || it.contains("cdsp", true)
        }?.sorted().orEmpty()
        // Prueba abrir cada librería del plugin: el error dice exactamente qué falta
        val order = listOf("libc++_shared.so", "libomp.so", "libggml-base.so", "libggml.so", "libggml-cpu.so", "libggml-opencl.so",
            "libggml-hexagon.so", "libllama.so", "libllama-common.so", "libgeniex_core.so", "libgeniex.so",
            "libgeniex_plugin_llama_cpp.so")
        val dl = StringBuilder("\nPrueba de carga:\n")
        for (n in order) {
            val f = java.io.File(dir, n)
            if (!f.exists()) { dl.append("  $n: (no está)\n"); continue }
            try { System.load(f.absolutePath); dl.append("  $n: OK\n") }
            catch (t: Throwable) { dl.append("  $n: ERROR ").append(t.message?.take(300)).append("\n") }
        }
        val log = try {
            val pr = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600"))
            pr.inputStream.bufferedReader().readLines()
                .filter { it.contains("geniex", true) || it.contains("plugin", true) || it.contains("dlopen", true) || it.contains("ggml", true) }
                .takeLast(25).joinToString("\n")
        } catch (t: Throwable) { "logcat: ${t.message}" }
        return "SDK: $initInfo\nChip: ${android.os.Build.SOC_MODEL}\nLibs GenieX/QNN en el APK: ${libs.size}\n" +
            dl.toString() + "\nLog GenieX:\n" + log
    }

    fun engineInfo(): String = label
    fun lastLoadErrors(): String = lastErrors
    fun lastStats(): String = lastStats
    fun isLoaded(path: String?): Boolean = llm != null && path != null && path == loadedPath

    /** Se llama al abrir la app. Si el SDK no está en el APK, simplemente queda desactivado. */
    fun init(context: Context) {
        try {
            GenieXSdk.getInstance().init(context.applicationContext, object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    sdkReady = true
                    initInfo = "OK"
                    Log.i(TAG, "GenieX listo")
                }
                override fun onFailure(reason: String) {
                    // Puede fallar solo el plugin QAIRT; llama_cpp igual sirve
                    sdkReady = true
                    initInfo = "con avisos: $reason"
                    Log.w(TAG, "GenieX init con avisos: $reason")
                }
            })
        } catch (t: Throwable) {
            sdkReady = false
            initInfo = "FALLÓ: " + chain(t)
            lastErrors = "GenieX no disponible: " + chain(t)
            Log.e(TAG, "GenieX no se pudo iniciar", t)
        }
    }

    /** Carga un GGUF. backend contiene "NPU" o "GPU". Si la NPU falla, prueba la GPU. */
    suspend fun load(path: String, backend: String, nCtx: Int): Boolean = mutex.withLock {
        closeLocked()
        if (!sdkReady) {
            lastErrors = lastErrors.ifBlank { "GenieX no está iniciado" }
            return@withLock false
        }
        val units = if (backend.contains("NPU", true)) listOf("npu", "gpu") else listOf("gpu")
        val errors = StringBuilder()
        for (unit in units) {
            try {
                Log.i(TAG, "Cargando $path en $unit")
                val result = LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_path = path,
                            config = ModelConfig(nCtx = nCtx.coerceIn(512, 16384), nGpuLayers = -1),
                            runtime_id = "llama_cpp",
                            compute_unit = unit
                        )
                    )
                    .build()
                val wrapper = result.getOrNull()
                if (wrapper != null) {
                    llm = wrapper
                    loadedPath = path
                    loadedUnit = unit
                    loadedCtx = nCtx
                    lastSystem = null
                    label = when (unit) {
                        "npu" -> "⚡ NPU Hexagon (GenieX)"
                        else -> if (backend.contains("NPU", true)) "⚡ NPU→🎮 GPU (GenieX)" else "🎮 GPU Adreno (GenieX)"
                    }
                    // Si la NPU falló y se usó la GPU, se guarda por qué (la app lo muestra)
                    lastErrors = errors.toString()
                    Log.i(TAG, "GenieX activo: $label")
                    return@withLock true
                }
                errors.append("• ").append(unit.uppercase()).append(": ")
                    .append(result.exceptionOrNull()?.let { chain(it) } ?: "error desconocido").append("\n")
            } catch (t: Throwable) {
                errors.append("• ").append(unit.uppercase()).append(": ").append(chain(t)).append("\n")
            }
        }
        lastErrors = errors.toString()
        false
    }

    /**
     * Genera una respuesta con la conversación completa.
     * roles: "user"/"assistant"; el último debe ser el mensaje nuevo del usuario.
     */
    suspend fun generate(
        system: String,
        roles: List<String>,
        contents: List<String>,
        maxTokens: Int,
        temperature: Float
    ): String = mutex.withLock {
        var w = llm ?: throw IllegalStateException("GenieX no tiene un modelo cargado")
        // ⚡ No se borra la memoria: GenieX reutiliza lo ya leído y solo procesa lo nuevo.
        // ⚠️ En la NPU NO se usa reset(): borrar la memoria de golpe cierra la app.
        //    Si cambiaron las instrucciones (nombre, modo, personalidad), la memoria vieja
        //    confunde al modelo → se recarga el modelo en la NPU (solo pasa cuando cambias algo).
        if (loadedUnit == "npu" && lastSystem != null && lastSystem != system) {
            val path = loadedPath
            if (path != null) {
                Log.i(TAG, "Instrucciones cambiaron: recargando en NPU")
                runCatching { w.close() }
                llm = null
                val fresh = LlmWrapper.builder()
                    .llmCreateInput(LlmCreateInput(
                        model_path = path,
                        config = ModelConfig(nCtx = loadedCtx.coerceIn(512, 16384), nGpuLayers = -1),
                        runtime_id = "llama_cpp",
                        compute_unit = "npu"
                    ))
                    .build().getOrNull() ?: throw IllegalStateException("No se pudo recargar el modelo en la NPU")
                llm = fresh
                w = fresh
            }
        }
        lastSystem = system
        val msgs = ArrayList<ChatMessage>()
        msgs.add(ChatMessage("system", system))
        for (i in roles.indices) msgs.add(ChatMessage(if (roles[i] == "assistant") "assistant" else "user", contents[i]))
        val templated = w.applyChatTemplate(msgs.toTypedArray(), null, false).getOrThrow()

        val stops = arrayOf("</buscar>", "</abrir>")
        val config = GenerationConfig(
            maxTokens = maxTokens.coerceIn(64, 4096),
            stopWords = stops,
            stopCount = stops.size,
            samplerConfig = SamplerConfig(temperature = temperature, topK = 40, topP = 0.95f, minP = 0.05f)
        )
        val sb = StringBuilder()
        var err: Throwable? = null
        w.generateStreamFlow(templated.formattedText, config).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> sb.append(r.text)
                is LlmStreamResult.Completed -> {
                    val p = r.profile
                    lastStats = String.format(
                        java.util.Locale.US, "lee %d tok a %.0f tok/s · escribe %.1f tok/s",
                        p.promptTokens, p.prefillSpeed, p.decodingSpeed
                    )
                }
                is LlmStreamResult.Error -> err = r.throwable
            }
        }
        if (sb.isEmpty() && err != null) throw err!!
        var text = sb.toString()
        // Si se detuvo en la etiqueta de búsqueda y el motor la omitió, se completa
        if (text.contains("<buscar>") && !text.contains("</buscar>")) text += "</buscar>"
        if (text.contains("<abrir>") && !text.contains("</abrir>")) text += "</abrir>"
        text
    }

    /** ⏹️ Corta la respuesta en curso (se llama desde un hilo aparte, no bloquea el mutex). */
    fun requestStop() {
        val w = llm ?: return
        runCatching { kotlinx.coroutines.runBlocking { w.stopStream() } }
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        loadedPath = null
        loadedUnit = null
        label = "GenieX sin cargar"
    }
}
