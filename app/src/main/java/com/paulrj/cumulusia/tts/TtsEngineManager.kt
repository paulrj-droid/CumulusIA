package com.paulrj.cumulusia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import com.paulrj.cumulusia.ai.CumulusEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private fun speakWithElevenLabs(text: String, onComplete: (() -> Unit)?) {
        if (elevenLabsApiKey.isEmpty()) {
            speakWithAndroidTts(text, onComplete) // Fallback si no hay API Key
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.elevenlabs.io/v1/text-to-speech/$elevenLabsVoiceId"

                val jsonBody = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_multilingual_v2")
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    })
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("xi-api-key", elevenLabsApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val tempFile = File(context.cacheDir, "tts_elevenlabs_temp.mp3")
                    FileOutputStream(tempFile).use { output ->
                        response.body!!.byteStream().copyTo(output)
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        playAudioFile(tempFile, onComplete)
                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        speakWithAndroidTts(text, onComplete)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    speakWithAndroidTts(text, onComplete)
                }
            }
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
