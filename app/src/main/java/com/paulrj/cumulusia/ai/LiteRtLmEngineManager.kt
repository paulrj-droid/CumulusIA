package com.paulrj.cumulusia.ai

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Motor de proceso único para modelos .litertlm.
 *
 * 🎛️ Usa SOLO el motor que eligió el usuario (NPU, GPU o CPU): ya no se pasa solo a otro.
 * Si ese motor no puede con el modelo, la carga falla y se muestra el motivo (lastLoadErrors).
 */
object LiteRtLmEngineManager {
    private const val TAG = "CumulusLiteRtLM"

    private val engineMutex = Mutex()
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var activeBackendLabel: String = "LiteRT-LM sin cargar"

    @Volatile
    private var requestedBackendKey: String? = null

    private val owners = mutableSetOf<String>()

    @Volatile
    private var nativeRuntimeConfigured = false

    private data class BackendCandidate(
        val label: String,
        val create: () -> Backend
    )

    fun isLoaded(modelPath: String? = null): Boolean {
        val loaded = engine != null && conversation != null && loadedModelPath != null
        return loaded && (modelPath == null || loadedModelPath == modelPath)
    }

    fun engineInfo(): String = activeBackendLabel

    // 📋 Errores del último intento de carga (para mostrarlos en pantalla)
    @Volatile private var lastLoadErrors: String = ""
    fun lastLoadErrors(): String = lastLoadErrors

    fun deviceNpuInfo(context: Context): String {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            Build.HARDWARE.orEmpty()
        }
        val isSm8750 = socModel.contains("SM8750", ignoreCase = true) ||
            Build.HARDWARE.contains("SM8750", ignoreCase = true)
        if (!isSm8750) {
            return "NPU LiteRT-LM: SoC detectado ${socModel.ifBlank { "desconocido" }}"
        }

        val dir = context.applicationInfo.nativeLibraryDir
        val required = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnHtpV79Stub.so",
            "libQnnHtpV79Skel.so",
            "libQnnSystem.so"
        )
        val missing = required.filterNot { File(dir, it).isFile }
        return if (missing.isEmpty()) {
            "NPU Snapdragon 8 Elite (SM8750): HTP V79 preparado"
        } else {
            "NPU SM8750 incompleta: faltan ${missing.joinToString()}"
        }
    }

    // ======================================================================
    // 🩺 ¿QUÉ TRAE ESTE .litertlm? (solo diagnóstico: no cambia cómo se carga ni cómo responde)
    //    Se le pregunta al propio archivo si trae visión (fotos) y audio, y en qué motores pueden correr.
    // ======================================================================
    @Volatile private var lastModelInfo: String = ""
    /** El archivo .litertlm trae visión (lo dice el propio archivo). */
    @Volatile private var modelHasVision = false
    /** El motor se cargó con la visión encendida (en la CPU, que es donde la admite el modelo). */
    @Volatile private var visionReady = false
    @Volatile private var visionNote: String = ""

    /** true si el .litertlm cargado puede mirar fotos. */
    fun hasVision(): Boolean = visionReady && engine != null

    /** Informe de lo que trae el último .litertlm cargado (lo muestra «diagnóstico general»). */
    fun modelInfoReport(): String = lastModelInfo +
        (if (visionNote.isNotBlank()) "\n$visionNote" else "") +
        (if (templateNote.isNotBlank()) "\n$templateNote" else "") +
        (if (lastVisionStats.isNotBlank()) "\n📷 Última foto: $lastVisionStats" else "")

    private fun readModelInfo(modelPath: String): String = try {
        com.google.ai.edge.litertlm.ModelInfo.from(modelPath).use { info ->
            val m = info.inputModalities()
            modelHasVision = m.vision
            fun yes(b: Boolean) = if (b) "SÍ ✅" else "no"
            fun backends(mod: com.google.ai.edge.litertlm.Modality) =
                runCatching { info.supportedBackends(mod).joinToString(", ") { it.name } }.getOrDefault("?").ifBlank { "—" }
            buildString {
                append("📄 ").append(File(modelPath).name).append(" (").append(File(modelPath).length() / (1024 * 1024)).append(" MB)\n")
                append("👁️ Trae visión (fotos): ").append(yes(m.vision))
                if (m.vision) append(" · motores: ").append(backends(com.google.ai.edge.litertlm.Modality.VISION))
                append("\n🎙️ Trae audio: ").append(yes(m.audio))
                if (m.audio) append(" · motores: ").append(backends(com.google.ai.edge.litertlm.Modality.AUDIO))
                append("\n💬 Texto · motores: ").append(backends(com.google.ai.edge.litertlm.Modality.TEXT))
                if (m.vision) runCatching { info.maxVisionTokenBudget() }.getOrNull()?.takeIf { it > 0 }
                    ?.let { append("\n🖼️ Tamaño máximo de foto: ").append(it).append(" tokens") }
                runCatching { info.maxContextTokens() }.getOrNull()?.takeIf { it > 0 }
                    ?.let { append("\n📏 Contexto máximo: ").append(it).append(" tokens") }
                runCatching { info.minRuntimeVersion() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { append("\n🔧 Necesita LiteRT-LM ").append(it).append(" o más nuevo") }
                if (!m.vision) append("\n⚙️ Este archivo no trae visión: con él no se pueden mirar fotos")
            }
        }
    } catch (t: Throwable) {
        modelHasVision = false
        "📄 ${File(modelPath).name}: no se pudo leer qué trae (${t.javaClass.simpleName}: ${t.message?.take(160)})"
    }

    suspend fun load(
        context: Context,
        modelPath: String,
        requestedBackend: String,
        owner: String
    ): Boolean = engineMutex.withLock {
        val backendKey = when {
            requestedBackend.contains("NPU", ignoreCase = true) -> "NPU"
            requestedBackend.contains("GPU", ignoreCase = true) -> "GPU"
            else -> "CPU"
        }
        owners.add(owner)
        if (loadedModelPath != modelPath) { chatTemplateOverride = null; templateNote = "" }
        // 🩺 Se anota qué trae el archivo (visión, audio, motores) ANTES de cargarlo: así se sabe aunque falle
        lastModelInfo = readModelInfo(modelPath)
        Log.i(TAG, "🩺 Qué trae el modelo:\n$lastModelInfo")
        if (isLoaded(modelPath) && requestedBackendKey == backendKey) {
            return@withLock true
        }
        closeLocked()

        val appContext = context.applicationContext
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
        configureNativeRuntime(nativeLibraryDir)
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else {
            Build.HARDWARE.orEmpty()
        }
        val isSnapdragon8Elite =
            socModel.contains("SM8750", ignoreCase = true) ||
                Build.HARDWARE.contains("SM8750", ignoreCase = true)
        val hasQualcommDispatch = File(
            nativeLibraryDir,
            "libLiteRtDispatch_Qualcomm.so"
        ).isFile
        val hasQnnHtp = File(nativeLibraryDir, "libQnnHtp.so").isFile &&
            File(nativeLibraryDir, "libQnnHtpV79Stub.so").isFile &&
            File(nativeLibraryDir, "libQnnHtpV79Skel.so").isFile &&
            File(nativeLibraryDir, "libQnnSystem.so").isFile

        if (isSnapdragon8Elite && (!hasQualcommDispatch || !hasQnnHtp)) {
            Log.e(
                TAG,
                "SM8750 detectado, pero faltan Dispatch Qualcomm o QAIRT HTP V79 en el APK"
            )
        }
        val candidates = when {
            requestedBackend.contains("NPU", ignoreCase = true) -> buildList {
                if (
                    Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                    Build.HARDWARE.contains("tensor", ignoreCase = true)
                ) {
                    add(
                        BackendCandidate("⚡ NPU Google Tensor (LiteRT-LM)") {
                            Backend.GOOGLE_TENSOR()
                        }
                    )
                }
                if (isSnapdragon8Elite && hasQualcommDispatch && hasQnnHtp) {
                    add(
                        BackendCandidate("⚡ NPU Snapdragon 8 Elite HTP (LiteRT-LM)") {
                            Backend.NPU(
                                nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
                            )
                        }
                    )
                }
                add(
                    BackendCandidate("⚡ NPU del sistema (LiteRT-LM)") {
                        Backend.NPU()
                    }
                )
                if (!isSnapdragon8Elite) {
                    add(
                        BackendCandidate("⚡ NPU empaquetada (LiteRT-LM)") {
                            Backend.NPU(
                                nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
                            )
                        }
                    )
                }
                // 🎛️ El motor lo eliges TÚ: ya no se pasa solo de la NPU a la GPU o a la CPU
            }
            // 🎛️ GPU elegida → solo GPU (ya no se pasa sola a la CPU; si falla, se explica por qué)
            requestedBackend.contains("GPU", ignoreCase = true) -> listOf(
                BackendCandidate("🎮 GPU (LiteRT-LM)") { Backend.GPU() }
            )
            else -> listOf(
                BackendCandidate("💻 CPU (LiteRT-LM)") { Backend.CPU() }
            )
        }

        val errors = StringBuilder()
        errors.append("Chip: ").append(socModel).append(" · ").append(Build.HARDWARE)
            .append("\nDispatch Qualcomm: ").append(if (hasQualcommDispatch) "sí" else "NO")
            .append(" · QNN HTP V79: ").append(if (hasQnnHtp) "sí" else "NO").append("\n\n")
        for (candidate in candidates) {
            var candidateEngine: Engine? = null
            try {
                Log.i(TAG, "Inicializando $modelPath con ${candidate.label}")
                // 👁️ Si el archivo trae visión, se enciende en la CPU (es donde este modelo la admite).
                //    La visión se carga recién cuando llega una foto, así que no retrasa la carga.
                //    Si la visión no arrancara, el modelo se carga igual en TU motor, solo con texto, y se anota.
                visionReady = false
                visionNote = ""
                if (modelHasVision) {
                    try {
                        candidateEngine = Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = candidate.create(),
                                visionBackend = Backend.CPU(),
                                cacheDir = appContext.cacheDir.absolutePath
                            )
                        )
                        candidateEngine.initialize()
                        visionReady = true
                        visionNote = "👁️ Visión encendida (en la CPU) · el texto va en ${candidate.label}"
                    } catch (t: Throwable) {
                        Log.w(TAG, "La visión no arrancó: ${t.message}")
                        try { candidateEngine?.close() } catch (_: Throwable) {}
                        candidateEngine = null
                        visionNote = "⚠️ La visión no arrancó (${t.message?.take(160)}): el modelo quedó solo con texto"
                    }
                }
                if (candidateEngine == null) {
                    candidateEngine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = candidate.create(),
                            cacheDir = appContext.cacheDir.absolutePath
                        )
                    )
                    candidateEngine.initialize()
                }
                val candidateConversation = candidateEngine.createConversation()
                engine = candidateEngine
                conversation = candidateConversation
                loadedModelPath = modelPath
                requestedBackendKey = backendKey
                activeBackendLabel = candidate.label
                Log.i(TAG, "LiteRT-LM activo: $activeBackendLabel")
                return@withLock true
            } catch (error: Throwable) {
                Log.w(TAG, "No se pudo iniciar ${candidate.label}: ${error.message}")
                errors.append("• ").append(candidate.label).append(":\n   ")
                    .append((error.message ?: error.javaClass.simpleName).take(400)).append("\n")
                try {
                    candidateEngine?.close()
                } catch (_: Throwable) {
                }
            }
        }

        lastLoadErrors = errors.toString()
        closeLocked()
        owners.remove(owner)
        false
    }

    // 📊 Medición de la última respuesta
    @Volatile private var lastMs: Long = 0
    @Volatile private var lastPromptChars: Int = 0
    @Volatile private var lastReplyChars: Int = 0
    fun lastStats(): String {
        if (lastMs <= 0) return ""
        val tok = lastReplyChars / 4.0   // aproximado: ~4 letras por token
        val tps = tok * 1000.0 / lastMs
        return String.format(java.util.Locale.US, "lee ≈%d tok · escribe ≈%.1f tok/s", lastPromptChars / 4, tps)
    }

    // Conversación NUEVA en cada mensaje: la app ya le manda todo el historial,
    // así el motor no lo duplica en su propia memoria
    suspend fun generateFresh(prompt: String): String = engineMutex.withLock {
        val currentEngine = engine
            ?: throw IllegalStateException("LiteRT-LM no tiene un modelo cargado")
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = newConversation(currentEngine, ConversationConfig())
        val t0 = System.currentTimeMillis()
        // ✍️ En vivo: el texto se va viendo mientras la IA escribe (antes aparecía todo de golpe al terminar)
        val text = sendTextLive(currentEngine, ConversationConfig(), prompt)
        lastMs = System.currentTimeMillis() - t0
        lastPromptChars = prompt.length
        lastReplyChars = text.length
        SamplingSettings.cutAtStops(text)
    }

    /**
     * 🎛️ Conversación con TUS ajustes de ⚙️ (temperatura, Top K y Top P). LiteRT-LM no tiene Min-P
     * ni deja cambiar la plantilla: siempre usa la que trae el modelo .litertlm.
     * Si este modelo no acepta ajustes propios, se crea con los suyos (y se anota en el registro).
     */
    private fun newConversation(engine: Engine, baseIn: ConversationConfig): Conversation {
        val base = withTemplate(baseIn)
        val sampler = runCatching {
            SamplerConfig(
                topK = SamplingSettings.topK.let { if (it <= 0) 100 else it },
                topP = SamplingSettings.topP.toDouble().coerceIn(0.0, 1.0),
                temperature = SamplingSettings.temperature.toDouble().coerceAtLeast(0.0)
            )
        }.getOrNull() ?: return engine.createConversation(base)
        return try {
            engine.createConversation(base.copy(samplerConfig = sampler))
        } catch (t: Throwable) {
            Log.w(TAG, "Este modelo no aceptó los ajustes de muestreo: ${t.message}")
            engine.createConversation(base)
        }
    }

    /**
     * 🧵 Conversación con ROLES NATIVOS: instrucciones del sistema + historial (usuario / modelo) + mensaje actual.
     * El motor aplica la plantilla de chat propia del modelo (como hacen la CPU y GenieX), en vez de recibir
     * todo pegado como un solo texto. Si algo falla, MainActivity vuelve al formato de texto anterior.
     */
    suspend fun generateChat(system: String, roles: List<String>, contents: List<String>): String = engineMutex.withLock {
        val currentEngine = engine
            ?: throw IllegalStateException("LiteRT-LM no tiene un modelo cargado")
        require(roles.isNotEmpty() && roles.size == contents.size) { "Conversación vacía" }
        // Se juntan mensajes seguidos del mismo rol (algunas plantillas exigen usuario/modelo alternados)
        val merged = mutableListOf<Pair<String, String>>()
        for (i in roles.indices) {
            val role = if (roles[i] == "assistant") "assistant" else "user"
            if (merged.isNotEmpty() && merged.last().first == role) {
                merged[merged.lastIndex] = role to (merged.last().second + "\n\n" + contents[i])
            } else merged.add(role to contents[i])
        }
        // El historial debe empezar con el usuario y el último mensaje es el que se envía ahora
        while (merged.isNotEmpty() && merged.first().first != "user") merged.removeAt(0)
        val last = merged.removeLastOrNull()
            ?: throw IllegalStateException("No hay mensaje del usuario")
        val history = merged.map { (role, text) -> if (role == "assistant") Message.model(text) else Message.user(text) }
        try { conversation?.close() } catch (_: Throwable) {}
        val chatBase = if (system.isNotBlank()) ConversationConfig(systemInstruction = Contents.of(system), initialMessages = history)
            else ConversationConfig(initialMessages = history)
        conversation = newConversation(currentEngine, chatBase)
        val t0 = System.currentTimeMillis()
        // ✍️ En vivo: el texto se va viendo mientras la IA escribe (antes aparecía todo de golpe al terminar)
        val text = sendTextLive(currentEngine, chatBase, last.second)
        lastMs = System.currentTimeMillis() - t0
        lastPromptChars = system.length + contents.sumOf { it.length }
        lastReplyChars = text.length
        SamplingSettings.cutAtStops(text)
    }

    @Volatile private var lastVisionStats: String = ""

    // ======================================================================
    // ✍️ ESCRITURA EN VIVO: igual que la CPU y GenieX, el texto (y el código) se ve mientras la IA lo escribe,
    //    fluido y de corrido, en vez de aparecer todo de golpe al terminar cada parte.
    // ======================================================================
    /** Lo que lleva escrito la IA (el texto COMPLETO hasta ahora). Lo conecta MainActivity con la ventana «Analizando...». */
    @Volatile var onPartial: ((String) -> Unit)? = null
    @Volatile private var stopNow = false
    /** ⏹️ Deja de escribir: se entrega lo que alcanzó a escribir. */
    fun requestStop() { stopNow = true }
    private class StopStreaming : RuntimeException("detenido")

    private fun textOf(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString(separator = "") { it.text }

    /** Recibe la respuesta de a pedacitos y avisa cada ~60 ms con todo lo escrito hasta ahora. */
    private suspend fun streamText(conv: Conversation, text: String): String {
        stopNow = false
        val sb = StringBuilder()
        var last = 0L
        try {
            conv.sendMessageAsync(text).collect { m ->
                val piece = textOf(m)
                // (según la versión, cada pedacito trae solo lo nuevo o todo lo escrito hasta ahora: sirven las dos)
                if (sb.isNotEmpty() && piece.length > sb.length && piece.startsWith(sb)) { sb.setLength(0); sb.append(piece) }
                else sb.append(piece)
                val now = System.currentTimeMillis()
                if (now - last >= 60) { last = now; runCatching { onPartial?.invoke(sb.toString()) } }
                if (stopNow) throw StopStreaming()
            }
        } catch (_: StopStreaming) {
            Log.i(TAG, "⏹️ Detenido: se entrega lo que alcanzó a escribir")
        }
        runCatching { onPartial?.invoke(sb.toString()) }
        return sb.toString()
    }

    /**
     * Envía el texto y lo recibe EN VIVO. Si la plantilla del modelo falla (ChatML), se usa la de respaldo y se reintenta;
     * si este motor no pudiera escribir en vivo, se pide la respuesta completa como antes (nada deja de funcionar).
     */
    private suspend fun sendTextLive(engine: Engine, base: ConversationConfig, text: String): String {
        suspend fun once(): String = try {
            streamText(conversation!!, text)
        } catch (t: Throwable) {
            if (isTemplateError(t)) throw t
            Log.w(TAG, "No se pudo escribir en vivo (${t.message}): pido la respuesta completa")
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = newConversation(engine, base)
            val r = conversation!!.sendMessage(text)
            textOf(r).ifBlank { r.toString() }.also { runCatching { onPartial?.invoke(it) } }
        }
        return try {
            once()
        } catch (t: Throwable) {
            if (chatTemplateOverride != null || !isTemplateError(t) || !looksChatMl()) throw t
            Log.w(TAG, "La plantilla del modelo falló (${t.message}): uso ChatML de respaldo")
            chatTemplateOverride = CHATML_SAFE
            templateNote = "💬 La plantilla de chat que trae el archivo no funciona con LiteRT-LM: se usa ChatML de respaldo"
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = newConversation(engine, base)
            once()
        }
    }

    // ======================================================================
    // 💬 PLANTILLA DE RESPALDO: algunos .litertlm (Qwen2.5-Coder…) traen una plantilla de chat que no
    //    funciona con LiteRT-LM («tried to use + operator on unsupported types string and sequence»):
    //    la plantilla espera texto y LiteRT-LM le pasa una lista. Si pasa, se usa esta plantilla ChatML
    //    (la misma familia de Qwen) que entiende las dos formas, y se reintenta una vez.
    // ======================================================================
    @Volatile private var chatTemplateOverride: String? = null
    @Volatile private var templateNote: String = ""

    private const val CHATML_SAFE =
        "{% for message in messages %}<|im_start|>{{ message['role'] }}\n" +
        "{% if message['content'] is string %}{{ message['content'] }}" +
        "{% else %}{% for c in message['content'] %}{% if c['type'] == 'text' %}{{ c['text'] }}{% endif %}{% endfor %}{% endif %}" +
        "<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}"

    private fun isTemplateError(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { (it.message ?: "").contains("apply template", true) }

    /** Solo para modelos de la familia ChatML (Qwen, SmolLM, Hermes…): ahí la plantilla de respaldo es la correcta. */
    private fun looksChatMl(): Boolean {
        val n = File(loadedModelPath ?: return false).name.lowercase()
        return listOf("qwen", "smollm", "hermes", "chatml").any { n.contains(it) }
    }

    private fun withTemplate(base: ConversationConfig): ConversationConfig =
        chatTemplateOverride?.let { base.copy(chatTemplate = it) } ?: base

    /** Envía el mensaje; si falla SOLO por la plantilla del modelo (ChatML), la cambia por la de respaldo y reintenta. */
    private fun sendWithTemplateFallback(engine: Engine, base: ConversationConfig, send: (Conversation) -> Message): Message {
        try {
            return send(conversation!!)
        } catch (t: Throwable) {
            if (chatTemplateOverride != null || !isTemplateError(t) || !looksChatMl()) throw t
            Log.w(TAG, "La plantilla del modelo falló (${t.message}): uso ChatML de respaldo")
            chatTemplateOverride = CHATML_SAFE
            templateNote = "💬 La plantilla de chat que trae el archivo no funciona con LiteRT-LM: se usa ChatML de respaldo"
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = newConversation(engine, base)
            return send(conversation!!)
        }
    }

    /**
     * 👁️ Mira fotos con LiteRT-LM: las fotos van ANTES del texto (como recomienda Gemma) en un solo mensaje.
     * Con fotos se usa temperatura baja (≤ 0.2) para que describa lo que ve y no invente.
     */
    suspend fun generateWithImages(system: String, prompt: String, imagePaths: List<String>): String = engineMutex.withLock {
        val currentEngine = engine ?: throw IllegalStateException("LiteRT-LM no tiene un modelo cargado")
        check(visionReady) { "Este modelo .litertlm se cargó sin visión" }
        try { conversation?.close() } catch (_: Throwable) {}
        val base = if (system.isNotBlank()) ConversationConfig(systemInstruction = Contents.of(system)) else ConversationConfig()
        val sampler = runCatching {
            SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = SamplingSettings.temperature.toDouble().coerceIn(0.0, 0.2)
            )
        }.getOrNull()
        val tb = withTemplate(base)
        conversation = if (sampler != null)
            runCatching { currentEngine.createConversation(tb.copy(samplerConfig = sampler)) }.getOrElse { currentEngine.createConversation(tb) }
        else currentEngine.createConversation(tb)
        val parts = imagePaths.map { Content.ImageFile(it) } + Content.Text(prompt)
        val t0 = System.currentTimeMillis()
        val response = conversation!!.sendMessage(Contents.of(parts))
        val text = response.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { response.toString() }
        lastMs = System.currentTimeMillis() - t0
        lastPromptChars = system.length + prompt.length
        lastReplyChars = text.length
        lastVisionStats = "${imagePaths.size} foto(s) · " + String.format(java.util.Locale.ROOT, "%.1f", lastMs / 1000f) +
            " s en total (mirar + escribir) · " + text.length + " letras"
        Log.i(TAG, "👁️ $lastVisionStats")
        SamplingSettings.cutAtStops(text)
    }

    suspend fun generate(prompt: String): String = engineMutex.withLock {
        val currentConversation = conversation
            ?: throw IllegalStateException("LiteRT-LM no tiene un modelo cargado")
        val response = currentConversation.sendMessage(prompt)
        response.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { response.toString() }
    }

    suspend fun release(owner: String) = engineMutex.withLock {
        owners.remove(owner)
        if (owners.isEmpty()) closeLocked()
    }

    private fun configureNativeRuntime(nativeLibraryDir: String) {
        if (nativeRuntimeConfigured) return
        try {
            Os.setenv("LD_LIBRARY_PATH", nativeLibraryDir, true)
            Os.setenv("ADSP_LIBRARY_PATH", nativeLibraryDir, true)
            nativeRuntimeConfigured = true
            Log.i(TAG, "Runtime Qualcomm configurado en $nativeLibraryDir")
        } catch (error: Throwable) {
            Log.w(TAG, "No se pudieron configurar las rutas QNN: ${error.message}")
        }
    }

    private fun closeLocked() {
        try {
            conversation?.close()
        } catch (_: Throwable) {
        }
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        conversation = null
        engine = null
        visionReady = false
        loadedModelPath = null
        requestedBackendKey = null
        activeBackendLabel = "LiteRT-LM sin cargar"
    }
}

