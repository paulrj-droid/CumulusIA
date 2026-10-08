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
                candidateEngine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = candidate.create(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )
                candidateEngine.initialize()
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
        conversation = currentEngine.createConversation()
        val t0 = System.currentTimeMillis()
        val response = conversation!!.sendMessage(prompt)
        val text = response.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { response.toString() }
        lastMs = System.currentTimeMillis() - t0
        lastPromptChars = prompt.length
        lastReplyChars = text.length
        text
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
        conversation = currentEngine.createConversation(
            if (system.isNotBlank()) ConversationConfig(systemInstruction = Contents.of(system), initialMessages = history)
            else ConversationConfig(initialMessages = history)
        )
        val t0 = System.currentTimeMillis()
        val response = conversation!!.sendMessage(last.second)
        val text = response.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { response.toString() }
        lastMs = System.currentTimeMillis() - t0
        lastPromptChars = system.length + contents.sumOf { it.length }
        lastReplyChars = text.length
        text
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
        loadedModelPath = null
        requestedBackendKey = null
        activeBackendLabel = "LiteRT-LM sin cargar"
    }
}

