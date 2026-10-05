package com.paulrj.cumulusia.ai

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
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
    // 👁️ Visión en NPU/GPU: si el modelo tiene mmproj se carga como VLM (sirve para texto e imágenes)
    private var vlm: VlmWrapper? = null
    private var loadedMmproj: String? = null
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
    fun isLoaded(path: String?): Boolean = (llm != null || vlm != null) && path != null && path == loadedPath
    /** true si el modelo cargado en NPU/GPU puede ver imágenes (se cargó con su mmproj). */
    fun hasVision(): Boolean = vlm != null
    fun loadedMmprojPath(): String? = loadedMmproj

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
    /**
     * Las noticias y la búsqueda web mandan ~1500 tokens + la respuesta. Con un contexto chico el motor
     * se llenaba («Input prompt too long») y fallaba en cada intento. En NPU/GPU se usan al menos 8192;
     * si el chip no acepta tanto, se carga con el tamaño elegido en ajustes.
     */
    suspend fun load(path: String, backend: String, nCtx: Int, mmproj: String? = null): Boolean {
        val big = maxOf(nCtx, 8192)
        if (loadWithCtx(path, backend, big, mmproj)) return true
        val firstErrors = lastErrors
        if (big == nCtx) return false
        Log.w(TAG, "No cargó con contexto $big; pruebo con $nCtx")
        val ok = loadWithCtx(path, backend, nCtx, mmproj)
        if (!ok) lastErrors = "Con contexto $big:\n$firstErrors\nCon contexto $nCtx:\n$lastErrors"
        return ok
    }

    private suspend fun loadWithCtx(path: String, backend: String, nCtx: Int, mmproj: String?): Boolean = mutex.withLock {
        closeLocked()
        if (!sdkReady) {
            lastErrors = lastErrors.ifBlank { "GenieX no está iniciado" }
            return@withLock false
        }
        val units = if (backend.contains("NPU", true)) listOf("npu", "gpu") else listOf("gpu")
        val errors = StringBuilder()
        val mm = mmproj?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        for (unit in units) {
            // 👁️ Primero con visión (modelo + mmproj). Si no se puede, sigue igual que antes solo texto.
            if (mm != null) {
                try {
                    Log.i(TAG, "Cargando VLM $path + $mm en $unit")
                    val vr = VlmWrapper.builder()
                        .vlmCreateInput(
                            VlmCreateInput(
                                model_path = path,
                                mmproj_path = mm,
                                config = ModelConfig(nCtx = nCtx.coerceIn(512, 16384), nGpuLayers = -1),
                                runtime_id = "llama_cpp",
                                compute_unit = unit
                            )
                        )
                        .build()
                    val vw = vr.getOrNull()
                    if (vw != null) {
                        vlm = vw
                        loadedMmproj = mm
                        loadedPath = path
                        loadedUnit = unit
                        loadedCtx = nCtx
                        lastSystem = null
                        label = when (unit) {
                            "npu" -> "⚡ NPU Hexagon 👁️ (GenieX)"
                            else -> if (backend.contains("NPU", true)) "⚡ NPU→🎮 GPU 👁️ (GenieX)" else "🎮 GPU Adreno 👁️ (GenieX)"
                        }
                        lastErrors = errors.toString()
                        Log.i(TAG, "GenieX VLM activo: $label")
                        return@withLock true
                    }
                    errors.append("• ").append(unit.uppercase()).append(" visión: ")
                        .append(vr.exceptionOrNull()?.let { chain(it) } ?: "error desconocido").append("\n")
                } catch (t: Throwable) {
                    errors.append("• ").append(unit.uppercase()).append(" visión: ").append(chain(t)).append("\n")
                }
            }
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
        // 👁️ Si se cargó con visión, el mismo motor responde también el texto
        vlm?.let { v ->
            val vm = ArrayList<VlmChatMessage>()
            vm.add(VlmChatMessage(role = "system", contents = listOf(VlmContent("text", system))))
            for (i in roles.indices) vm.add(VlmChatMessage(role = if (roles[i] == "assistant") "assistant" else "user",
                contents = listOf(VlmContent("text", contents[i]))))
            return@withLock runVlmLocked(v, vm, maxTokens, temperature)
        }
        var w = llm ?: throw IllegalStateException("GenieX no tiene un modelo cargado")
        // 🧹 GPU: se limpia la memoria en cada mensaje (reutilizarla hacía que repitiera
        //    respuestas viejas). Releer todo en la GPU cuesta ~1 s.
        // ⚠️ NPU: NO se limpia ni se recarga (las dos cosas cerraban la app). Los nombres y
        //    la hora van pegados a cada mensaje, así que no dependen de la memoria vieja.
        if (loadedUnit != "npu") runCatching { w.reset() }
        lastSystem = system
        val msgs = ArrayList<ChatMessage>()
        msgs.add(ChatMessage("system", system))
        for (i in roles.indices) msgs.add(ChatMessage(if (roles[i] == "assistant") "assistant" else "user", contents[i]))
        val templated = w.applyChatTemplate(msgs.toTypedArray(), null, false).getOrThrow()

        val stops = arrayOf("</buscar>", "</abrir>")
        val config = GenerationConfig(
            maxTokens = fitTokens(maxTokens, templated.formattedText),
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

    /**
     * 👁️ Responde sobre imágenes (fotos, cuadros de video, cámara) en NPU/GPU.
     * Solo funciona si el modelo se cargó con su mmproj (hasVision() = true).
     */
    suspend fun generateWithImages(
        system: String,
        prompt: String,
        imagePaths: List<String>,
        maxTokens: Int,
        temperature: Float
    ): String = mutex.withLock {
        val v = vlm ?: throw IllegalStateException("El modelo en NPU/GPU se cargó sin mmproj (sin visión)")
        val parts = ArrayList<VlmContent>()
        for (p in imagePaths) parts.add(VlmContent("image", p))
        parts.add(VlmContent("text", prompt))
        val vm = listOf(
            VlmChatMessage(role = "system", contents = listOf(VlmContent("text", system))),
            VlmChatMessage(role = "user", contents = parts)
        )
        runVlmLocked(v, vm, maxTokens, temperature)
    }

    private suspend fun runVlmLocked(v: VlmWrapper, vm: List<VlmChatMessage>, maxTokens: Int, temperature: Float): String {
        try {
            return runVlmOnce(v, vm, maxTokens, temperature)
        } catch (t: Throwable) {
            if (!isTooLong(t)) throw t
        }
        // 🧹 La memoria del motor se llenó (pasa en la NPU tras varios mensajes): se limpia y se reintenta solo
        Log.w(TAG, "VLM: prompt demasiado largo → limpio memoria y reintento")
        runCatching { v.reset() }
        try {
            return runVlmOnce(v, vm, maxTokens, temperature)
        } catch (t: Throwable) {
            if (!isTooLong(t)) throw t
        }
        // Último intento: solo instrucciones + mensaje actual, recortados para que quepan
        runCatching { v.reset() }
        val budget = (loadedCtx * 2).coerceAtLeast(1500)   // ~2 caracteres por token, deja espacio a la respuesta
        val sys = vm.firstOrNull { it.role == "system" }
        val last = vm.last()
        val small = ArrayList<VlmChatMessage>()
        sys?.let { m -> small.add(VlmChatMessage(role = "system", contents = m.contents.map { c ->
            if (c.type == "text") VlmContent("text", (c.text ?: "").take(budget / 3)) else c })) }
        small.add(VlmChatMessage(role = last.role, contents = last.contents.map { c ->
            if (c.type == "text") VlmContent("text", (c.text ?: "").take(budget)) else c }))
        return runVlmOnce(v, small, maxTokens.coerceAtMost(loadedCtx / 3), temperature)
    }

    /** La respuesta nunca pide más espacio del que queda en el contexto (texto ≈ 3 caracteres por token; cada imagen ≈ 300). */
    private fun fitTokens(maxTokens: Int, prompt: String): Int {
        val used = prompt.length / 3 + 300
        val free = loadedCtx - used - 64
        return maxTokens.coerceAtMost(free).coerceIn(128, 4096)
    }

    private fun isTooLong(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { (it.message ?: "").contains("too long", true) || (it.message ?: "").contains("context", true) }

    private suspend fun runVlmOnce(v: VlmWrapper, vm: List<VlmChatMessage>, maxTokens: Int, temperature: Float): String {
        // GPU se limpia en cada mensaje (igual que en texto); la NPU no se toca
        if (loadedUnit != "npu") runCatching { v.reset() }
        val arr = vm.toTypedArray()
        val templated = v.applyChatTemplate(arr, null, false).getOrThrow()
        val stops = arrayOf("</buscar>", "</abrir>")
        val base = GenerationConfig(
            maxTokens = fitTokens(maxTokens, templated.formattedText),
            stopWords = stops,
            stopCount = stops.size,
            samplerConfig = SamplerConfig(temperature = temperature, topK = 40, topP = 0.95f, minP = 0.05f)
        )
        val config = v.injectMediaPathsToConfig(arr, base)
        val sb = StringBuilder()
        var err: Throwable? = null
        v.generateStreamFlow(templated.formattedText, config).collect { r ->
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
        if (text.contains("<buscar>") && !text.contains("</buscar>")) text += "</buscar>"
        if (text.contains("<abrir>") && !text.contains("</abrir>")) text += "</abrir>"
        return text
    }

    /** ⏹️ Corta la respuesta en curso (se llama desde un hilo aparte, no bloquea el mutex). */
    fun requestStop() {
        vlm?.let { v -> runCatching { kotlinx.coroutines.runBlocking { v.stopStream() } } }
        val w = llm ?: return
        runCatching { kotlinx.coroutines.runBlocking { w.stopStream() } }
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        try { vlm?.close() } catch (_: Throwable) {}
        vlm = null
        loadedMmproj = null
        loadedPath = null
        loadedUnit = null
        label = "GenieX sin cargar"
    }
}
