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
 * Recomendado: modelos GGUF Q4_0 (la NPU Hexagon los soporta mejor; los K-quant como Q4_K_M
 * mandan partes del modelo a la CPU: más lento y más calor).
 *
 * Ajustes según la documentación de GenieX 0.8.0:
 *  - NPU = «hybrid» (NPU + CPU por partes): el modo más rápido. Si falla, NPU fija y luego GPU.
 *  - 🚀 VELOCIDAD MÁXIMA: power_mode «burst» (la NPU a su máxima frecuencia), 6 hilos de CPU para escribir
 *    (lo que usa Qualcomm en sus pruebas) y los 8 núcleos para leer el mensaje (la parte más pesada).
 *  - La GPU usa todas las capas del modelo (nGpuLayers = -1).
 *  - Visión (mmproj) va SIEMPRE en la GPU: en la NPU cierra la app (probado en el S25); en GPU y CPU funciona.
 *    Sin mmproj, el modelo va en la NPU.
 *  - 🛡️ Si un modelo cierra la app en NPU/GPU, esa combinación se recuerda y no se repite.
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
    private var loadedKey: String? = null
    private var lastSystem: String? = null
    @Volatile private var visionSkipped: String? = null

    // 🚀 Potencia de la NPU al máximo («burst»). Es lo más rápido; el teléfono se puede entibiar en respuestas largas.
    //    Si algún día se prefiere menos calor: "sustained_high_performance".
    private const val POWER_MODE = "burst"
    // 🧵 Hilos de CPU: 6 para escribir (lo que usa Qualcomm; escribir depende más de la memoria que de los núcleos)
    //    y los 8 núcleos del Snapdragon 8 Elite para leer el mensaje (prefill, la parte que más cálculo pide)
    private const val CPU_THREADS = 6
    private val CPU_THREADS_BATCH = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

    private fun modelConfig(nCtx: Int) = ModelConfig(
        nCtx = nCtx, nThreads = CPU_THREADS, nThreadsBatch = CPU_THREADS_BATCH,
        nBatch = 2048, nUBatch = 512, nGpuLayers = -1, power_mode = POWER_MODE
    )

    // ======================================================================
    // 🛡️ PROTECCIÓN: si un GGUF cierra la app en NPU/GPU, esa combinación se recuerda
    //    (modelo + unidad + visión) y la próxima vez se prueba la siguiente opción.
    // ======================================================================
    private var guard: android.content.SharedPreferences? = null

    private fun guardKey(path: String, unit: String, vision: Boolean) =
        path + "|" + java.io.File(path).length() + "|" + unit + "|" + (if (vision) "vision" else "texto")

    private fun isBlocked(key: String) = guard?.getStringSet("blocked", null)?.contains(key) == true

    /** Se escribe ANTES de cada paso riesgoso (commit: queda guardado aunque la app se cierre al instante). */
    private fun markAttempt(key: String?, phase: String = "load") {
        val g = guard ?: return
        if (key == null) g.edit().remove("attempt").remove("phase").commit()
        else g.edit().putString("attempt", key).putString("phase", phase).commit()
    }

    private fun unitName(unit: String) = when (unit) {
        "hybrid" -> "NPU (híbrida)"
        "npu" -> "NPU"
        "gpu" -> "GPU"
        else -> unit.uppercase()
    }

    private fun describe(key: String): String {
        val p = key.split("|")
        val name = java.io.File(p.getOrElse(0) { "" }).name
        return "«$name» en ${unitName(p.getOrElse(2) { "" })}" + (if (p.getOrNull(3) == "vision") " con visión" else "")
    }

    /**
     * Al abrir la app: si quedó un paso de GenieX a medias Y Android anotó que la app se cayó por un error,
     * esa combinación se bloquea. [crashed] = cierre real por error (ApplicationExitInfo de Android).
     * Si la cerraste tú, si se actualizó o si Android la sacó de memoria en segundo plano, NO se bloquea nada.
     * Devuelve el aviso para el usuario, o null.
     */
    fun checkPreviousCrash(context: Context, crashed: Boolean): String? {
        val g = context.getSharedPreferences("geniex_guard", Context.MODE_PRIVATE)
        guard = g
        // Una versión nueva de la app puede traer el arreglo: se vuelve a probar todo
        val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (g.getLong("app_update", 0L) != updated) g.edit().putLong("app_update", updated).remove("blocked").commit()
        val attempt = g.getString("attempt", null) ?: return null
        val phase = g.getString("phase", "load")
        markAttempt(null)
        // Antes, si la app se cerraba MIENTRAS CARGABA el modelo, se culpaba al modelo aunque la hubieras
        // cerrado tú. Ahora solo cuenta un cierre por error de verdad (lo anota Android).
        if (!crashed) return null
        g.edit().putStringSet("blocked", (g.getStringSet("blocked", null) ?: emptySet()) + attempt).commit()
        Log.w(TAG, "Bloqueado por cerrar la app ($phase): $attempt")
        return describe(attempt)
    }

    /** «Volver a intentar»: el modelo puede probar otra vez la NPU/GPU. */
    fun clearBlocked(context: Context, path: String? = null) {
        val g = context.getSharedPreferences("geniex_guard", Context.MODE_PRIVATE)
        val left = if (path == null) emptySet() else (g.getStringSet("blocked", null) ?: emptySet()).filterNot { it.startsWith("$path|") }.toSet()
        g.edit().putStringSet("blocked", left).commit()
    }

    /** Si la visión se dejó fuera (GPU), el motivo; si no, null. */
    fun visionSkippedReason(): String? = visionSkipped
    @Volatile private var label: String = "GenieX sin cargar"
    @Volatile private var sdkReady = false
    @Volatile private var lastErrors: String = ""
    @Volatile private var lastStats: String = ""

    /** ✍️ Texto que va escribiendo el modelo (MainActivity lo usa para ver la app armándose en la vista previa). */
    @Volatile var onPartial: ((String) -> Unit)? = null
    @Volatile private var lastPartialAt = 0L
    private fun emitPartial(sb: StringBuilder) {
        val cb = onPartial ?: return
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < 400) return   // como mucho ~2 veces por segundo
        lastPartialAt = now
        runCatching { cb(sb.toString()) }
    }
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
        if (guard == null) guard = context.getSharedPreferences("geniex_guard", Context.MODE_PRIVATE)
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
     * Carga con el contexto EXACTO que eligió el usuario en la barra «Contexto».
     * La app no ajusta ni recorta nada del contexto: lo controla 100% el usuario.
     */
    suspend fun load(path: String, backend: String, nCtx: Int, mmproj: String? = null): Boolean =
        loadWithCtx(path, backend, nCtx, mmproj)

    private suspend fun loadWithCtx(path: String, backend: String, nCtx: Int, mmproj: String?): Boolean = mutex.withLock {
        closeLocked()
        if (!sdkReady) {
            lastErrors = lastErrors.ifBlank { "GenieX no está iniciado" }
            return@withLock false
        }
        // ⚡ NPU: primero «hybrid» (NPU + CPU por partes, el más rápido según Qualcomm), luego NPU fija y al final GPU
        val units = if (backend.contains("NPU", true)) listOf("hybrid", "npu", "gpu") else listOf("gpu")
        val errors = StringBuilder()
        val mm = mmproj?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        visionSkipped = null
        // 👁️ Con visión (modelo + mmproj) se carga SIEMPRE en la GPU: en la NPU la visión cierra la app
        //    (probado en el Galaxy S25); en GPU y CPU funciona bien.
        if (mm != null) {
            if (backend.contains("NPU", true)) errors.append("• NPU visión: en la NPU la visión (mmproj) cierra la app, así que el modelo con visión va en la GPU\n")
            val vkey = guardKey(path, "gpu", true)
            if (isBlocked(vkey)) {
                errors.append("• GPU visión: se omite (antes cerró la app)\n")
            } else try {
                markAttempt(vkey)
                Log.i(TAG, "Cargando VLM $path + $mm en gpu")
                val vr = VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_path = path,
                            mmproj_path = mm,
                            config = modelConfig(nCtx),
                            runtime_id = "llama_cpp",
                            compute_unit = "gpu"
                        )
                    )
                    .build()
                val vw = vr.getOrNull()
                if (vw != null) {
                    vlm = vw
                    loadedMmproj = mm
                    loadedPath = path
                    loadedUnit = "gpu"
                    loadedCtx = nCtx
                    loadedKey = vkey
                    lastSystem = null
                    label = if (backend.contains("NPU", true)) "⚡ NPU→🎮 GPU 👁️ (GenieX)" else "🎮 GPU Adreno 👁️ (GenieX)"
                    lastErrors = errors.toString()
                    Log.i(TAG, "GenieX VLM activo: $label")
                    return@withLock true
                }
                errors.append("• GPU visión: ")
                    .append(vr.exceptionOrNull()?.let { chain(it) } ?: "error desconocido").append("\n")
            } catch (t: Throwable) {
                errors.append("• GPU visión: ").append(chain(t)).append("\n")
            } finally {
                markAttempt(null)
            }
            visionSkipped = "La visión (mmproj) no se pudo cargar en la GPU con este modelo: para fotos usa la CPU."
        }
        // 💬 Solo texto: NPU (si la elegiste) y luego GPU
        for (unit in units) {
            val key = guardKey(path, unit, false)
            if (isBlocked(key)) {
                errors.append("• ").append(unitName(unit)).append(": se omite (antes cerró la app con este modelo)\n")
                continue
            }
            try {
                markAttempt(key)
                Log.i(TAG, "Cargando $path en $unit")
                val result = LlmWrapper.builder()
                    .llmCreateInput(
                        LlmCreateInput(
                            model_path = path,
                            config = modelConfig(nCtx),
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
                    loadedKey = key
                    lastSystem = null
                    label = when (unit) {
                        "gpu" -> if (backend.contains("NPU", true)) "⚡ NPU→🎮 GPU (GenieX)" else "🎮 GPU Adreno (GenieX)"
                        else -> "⚡ NPU Hexagon (GenieX)"
                    }
                    // Si la NPU falló y se usó la GPU, se guarda por qué (la app lo muestra)
                    lastErrors = errors.toString()
                    Log.i(TAG, "GenieX activo: $label")
                    return@withLock true
                }
                errors.append("• ").append(unitName(unit)).append(": ")
                    .append(result.exceptionOrNull()?.let { chain(it) } ?: "error desconocido").append("\n")
            } catch (t: Throwable) {
                errors.append("• ").append(unitName(unit)).append(": ").append(chain(t)).append("\n")
            } finally {
                markAttempt(null)
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
        val w = llm ?: throw IllegalStateException("GenieX no tiene un modelo cargado")
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
        // ⚡ Ya NO se limpia la memoria en cada mensaje: GenieX reutiliza lo que ya leyó de la conversación
        //    («prefix reuse») y solo lee lo nuevo. Antes la GPU releía TODO el chat en cada mensaje (lo más lento).
        markAttempt(loadedKey, "gen")
        try {
            var text = runLlmOnce(w, templated.formattedText, config)
            // 🧹 Si responde igual que su respuesta anterior (memoria vieja), se limpia y se responde de nuevo
            val prev = contents.indices.lastOrNull { roles[it] == "assistant" }?.let { contents[it].trim() }
            if (prev != null && prev.length > 20 && text.trim().take(160) == prev.take(160)) {
                Log.w(TAG, "Repitió la respuesta anterior → limpio memoria y respondo de nuevo")
                runCatching { w.reset() }
                text = runLlmOnce(w, templated.formattedText, config)
            }
            text
        } finally {
            markAttempt(null)
        }
    }

    private suspend fun runLlmOnce(w: LlmWrapper, prompt: String, config: GenerationConfig): String {
        val sb = StringBuilder()
        var err: Throwable? = null
        w.generateStreamFlow(prompt, config).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> { sb.append(r.text); emitPartial(sb) }
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
        return text
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
        markAttempt(loadedKey, "gen")
        try {
            return runVlmGuarded(v, vm, maxTokens, temperature)
        } finally {
            markAttempt(null)
        }
    }

    private suspend fun runVlmGuarded(v: VlmWrapper, vm: List<VlmChatMessage>, maxTokens: Int, temperature: Float): String {
        try {
            return runVlmOnce(v, vm, maxTokens, temperature)
        } catch (t: Throwable) {
            if (!isTooLong(t)) throw t
        }
        // 🧹 La memoria del motor se llenó (pasa en la NPU tras varios mensajes): se limpia y se reintenta solo
        Log.w(TAG, "VLM: prompt demasiado largo → limpio memoria y reintento")
        runCatching { v.reset() }
        // Sin recortes automáticos: si tampoco cabe con la memoria limpia, se informa el error
        return runVlmOnce(v, vm, maxTokens, temperature)
    }

    private fun isTooLong(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { (it.message ?: "").contains("too long", true) || (it.message ?: "").contains("context", true) }

    private suspend fun runVlmOnce(v: VlmWrapper, vm: List<VlmChatMessage>, maxTokens: Int, temperature: Float): String {
        // La NPU no se limpia (limpiarla cerraba la app); GenieX reutiliza lo ya leído
        if (loadedUnit == "gpu") runCatching { v.reset() }
        val arr = vm.toTypedArray()
        val templated = v.applyChatTemplate(arr, null, false).getOrThrow()
        val stops = arrayOf("</buscar>", "</abrir>")
        val base = GenerationConfig(
            maxTokens = maxTokens.coerceIn(64, 4096),
            stopWords = stops,
            stopCount = stops.size,
            samplerConfig = SamplerConfig(temperature = temperature, topK = 40, topP = 0.95f, minP = 0.05f)
        )
        val config = v.injectMediaPathsToConfig(arr, base)
        val sb = StringBuilder()
        var err: Throwable? = null
        v.generateStreamFlow(templated.formattedText, config).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> { sb.append(r.text); emitPartial(sb) }
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
        loadedKey = null
        label = "GenieX sin cargar"
    }
}


