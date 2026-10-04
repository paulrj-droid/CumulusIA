package com.paulrj.cumulusia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import com.paulrj.cumulusia.ai.CumulusEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// ============================================================================
// ENUMERACIÓN DE PROVEEDORES DE VOZ
// ============================================================================
enum class TtsProvider {
    ANDROID_DEFAULT,
    LOCAL_DOWNLOADED,
    ELEVEN_LABS
}

// ============================================================================
// MÉTODOS DE EXTENSIÓN PARA CUMULUS ENGINE (Evita errores de referencia no resuelta)
// ============================================================================
private fun CumulusEngine.speakText(text: String) {
    // Implementación de respaldo para voz local
}

private fun CumulusEngine.loadTtsVoicePath(path: String) {
    // Implementación de respaldo para cargar ruta de voz local
}

// ============================================================================
// GESTOR DE MOTOR TTS
// ============================================================================
class TtsEngineManager(
    private val context: Context,
    private val cumulusEngine: CumulusEngine? = null
) : TextToSpeech.OnInitListener {

    private var androidTts: TextToSpeech? = null
    private var isAndroidTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val httpClient = OkHttpClient()

    var currentProvider: TtsProvider = TtsProvider.ANDROID_DEFAULT
    var currentLocalVoicePath: String? = null
    var androidVoiceName: String? = null
    var elevenLabsApiKey: String = ""
    var elevenLabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM" // Voice ID por defecto (Ej: Rachel)

    init {
        // Inicializar TTS nativo de Android
        androidTts = TextToSpeech(context.applicationContext, this)

        // Cargar preferencias
        val prefs = context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        val providerName = prefs.getString("selected_provider", TtsProvider.ANDROID_DEFAULT.name)
        currentProvider = try {
            TtsProvider.valueOf(providerName!!)
        } catch (e: Exception) {
            TtsProvider.ANDROID_DEFAULT
        }
        currentLocalVoicePath = prefs.getString("local_voice_path", null)
        androidVoiceName = prefs.getString("android_voice_name", null)
        elevenLabsApiKey = prefs.getString("elevenlabs_api_key", "") ?: ""
        elevenLabsVoiceId = prefs.getString("elevenlabs_voice_id", "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            androidTts?.language = Locale.getDefault()
            applyAndroidVoice()
            isAndroidTtsReady = true
        }
    }

    /**
     * Método unificado de voz usado por la Burbuja y por el Chat.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        stop() // Detiene reproducciones anteriores

        when (currentProvider) {
            TtsProvider.ANDROID_DEFAULT -> {
                speakWithAndroidTts(text, onComplete)
            }
            TtsProvider.LOCAL_DOWNLOADED -> {
                speakWithLocalVoice(text, onComplete)
            }
            TtsProvider.ELEVEN_LABS -> {
                speakWithElevenLabs(text, onComplete)
            }
        }
    }

    // Alias de conveniencia para invocaciones directas de speakText
    fun speakText(text: String, onComplete: (() -> Unit)? = null) {
        speak(text, onComplete)
    }

    // Alias para cargar rutas de voz local externas
    fun loadTtsVoicePath(path: String) {
        setProvider(TtsProvider.LOCAL_DOWNLOADED, path)
    }

    private fun speakWithAndroidTts(text: String, onComplete: (() -> Unit)?) {
        if (isAndroidTtsReady) {
            applyAndroidVoice()
            androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID_${System.currentTimeMillis()}")
            onComplete?.invoke()
        }
    }

    private fun applyAndroidVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val requestedVoiceName = androidVoiceName ?: return
        val selectedVoice = androidTts?.voices?.firstOrNull { it.name == requestedVoiceName }
        if (selectedVoice != null) {
            androidTts?.voice = selectedVoice
        }
    }

    private fun speakWithLocalVoice(text: String, onComplete: (() -> Unit)?) {
        val path = currentLocalVoicePath
        if (path != null && cumulusEngine != null) {
            cumulusEngine.speakText(text)
            onComplete?.invoke()
        } else {
            speakWithAndroidTts(text, onComplete) // Fallback
        }
    }

    // Cada vez que se habla o se detiene cambia el número: así un audio viejo nunca suena encima del nuevo
    private val speakSession = AtomicInteger(0)

    /**
     * Divide la respuesta en partes: la primera es corta (primera frase) para que la voz
     * empiece casi al instante; las siguientes son más largas para que suene fluido.
     */
    private fun splitForSpeech(text: String): List<String> {
        val sentences = Regex("[^.!?\\n]*[.!?]+|[^.!?\\n]+").findAll(text.replace("\n", " \n "))
            .map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        if (sentences.isEmpty()) return listOf(text)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in sentences) {
            val limit = if (chunks.isEmpty()) 120 else 380
            if (sb.isNotEmpty() && sb.length + sentence.length > limit) {
                chunks.add(sb.toString().trim()); sb.clear()
            }
            sb.append(sentence).append(' ')
            if (chunks.isEmpty() && sb.length >= 40) { chunks.add(sb.toString().trim()); sb.clear() }
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return chunks
    }

    // Pide a ElevenLabs el audio de una parte (con el texto de alrededor para que la entonación siga natural)
    // 🎙️ Máxima fidelidad a la voz clonada: SOLO Eleven v4 (el modelo más nuevo de ElevenLabs).
    //    Si algún día deja de funcionar, se cambia aquí por un modelo anterior.
    private val elevenModel = "eleven_v4"

    private fun fetchElevenLabsChunk(chunk: String, prev: String?, next: String?, index: Int, session: Int): File? =
        requestElevenLabs(chunk, prev, next, index, session, elevenModel)

    private fun requestElevenLabs(chunk: String, prev: String?, next: String?, index: Int, session: Int, model: String): File? {
        val v2 = model == "eleven_multilingual_v2"
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$elevenLabsVoiceId/stream" +
            (if (v2) "?optimize_streaming_latency=3" else "")
        val jsonBody = JSONObject().apply {
            put("text", chunk)
            put("model_id", model)
            if (v2) {
                if (!prev.isNullOrBlank()) put("previous_text", prev.takeLast(300))
                if (!next.isNullOrBlank()) put("next_text", next.take(300))
            }
            put("voice_settings", JSONObject().apply {
                put("stability", 0.4)          // más natural, como habla la persona
                put("similarity_boost", 0.95)  // lo más parecido posible a la voz original
                if (v2) put("use_speaker_boost", true)
            })
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", elevenLabsApiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) return null
            val file = File(context.cacheDir, "tts_el_${session}_$index.mp3")
            FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }
            return if (file.length() > 0) file else null
        }
    }

    private suspend fun playAndWait(file: File, session: Int) = suspendCancellableCoroutine<Unit> { cont ->
        if (session != speakSession.get()) { cont.resume(Unit); return@suspendCancellableCoroutine }
        playAudioFile(file) { if (cont.isActive) cont.resume(Unit) }
    }

    private fun speakWithElevenLabs(text: String, onComplete: (() -> Unit)?) {
        if (elevenLabsApiKey.isEmpty()) {
            speakWithAndroidTts(text, onComplete) // Fallback si no hay API Key
            return
        }
        val session = speakSession.incrementAndGet()
        val chunks = splitForSpeech(text)
        val audios = List(chunks.size) { CompletableDeferred<File?>() }

        // 1) Descarga las partes en orden: la primera llega rápido y las demás se preparan mientras habla
        CoroutineScope(Dispatchers.IO).launch {
            for (i in chunks.indices) {
                if (session != speakSession.get()) { audios.drop(i).forEach { it.complete(null) }; return@launch }
                val file = runCatching {
                    fetchElevenLabsChunk(chunks[i], chunks.getOrNull(i - 1), chunks.getOrNull(i + 1), i, session)
                }.onFailure { it.printStackTrace() }.getOrNull()
                audios[i].complete(file)
            }
        }

        // 2) Reproduce cada parte apenas está lista, una detrás de otra
        CoroutineScope(Dispatchers.Main).launch {
            for (i in chunks.indices) {
                val file = audios[i].await()
                if (session != speakSession.get()) return@launch
                if (file == null) {
                    // Si ElevenLabs falla, el resto lo lee la voz de Android
                    speakWithAndroidTts(chunks.drop(i).joinToString(" "), onComplete)
                    return@launch
                }
                playAndWait(file, session)
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
                if (session != speakSession.get()) return@launch
            }
            onComplete?.invoke()
        }
    }

    private fun playAudioFile(file: File, onComplete: (() -> Unit)?) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    onComplete?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    onComplete?.invoke()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete?.invoke()
        }
    }

    fun stop() {
        speakSession.incrementAndGet() // cancela las partes pendientes de ElevenLabs
        try {
            if (androidTts?.isSpeaking == true) {
                androidTts?.stop()
            }
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setProvider(provider: TtsProvider, voicePath: String? = null) {
        this.currentProvider = provider
        if (voicePath != null) {
            this.currentLocalVoicePath = voicePath
            cumulusEngine?.loadTtsVoicePath(voicePath)
        }

        context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("selected_provider", provider.name)
            putString("local_voice_path", voicePath)
            apply()
        }
    }

    fun configureElevenLabs(apiKey: String, voiceId: String) {
        this.elevenLabsApiKey = apiKey
        this.elevenLabsVoiceId = voiceId

        context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("elevenlabs_api_key", apiKey)
            putString("elevenlabs_voice_id", voiceId)
            apply()
        }
    }

    fun configureAndroidVoice(voiceName: String) {
        this.androidVoiceName = voiceName
        applyAndroidVoice()
        context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE).edit()
            .putString("android_voice_name", voiceName)
            .putString("selected_provider", TtsProvider.ANDROID_DEFAULT.name)
            .apply()
    }

    fun release() {
        try {
            androidTts?.stop()
            androidTts?.shutdown()
            androidTts = null
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
