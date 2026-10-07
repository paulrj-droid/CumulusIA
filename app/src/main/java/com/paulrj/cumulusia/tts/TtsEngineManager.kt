package com.paulrj.cumulusia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.concurrent.ConcurrentHashMap

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
    // Último motivo por el que ElevenLabs rechazó la voz (se muestra al usuario en vez de callar)
    @Volatile var lastElevenError: String? = null

    // 🔔 La voz de Android avisa cuándo TERMINA de hablar. Antes avisaba apenas empezaba, y la
    //    conversación por voz creía que la IA ya se había callado (la interrupción no funcionaba
    //    y su propia voz se tomaba como si hablaras tú).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val androidDone = ConcurrentHashMap<String, Pair<Int, () -> Unit>>()
    @Volatile private var pendingAndroid: Pair<String, (() -> Unit)?>? = null
    // La parte de ElevenLabs que está sonando espera aquí a que termine (stop() la libera)
    private var playbackWaiter: (() -> Unit)? = null
    // Cada vez que se habla o se detiene cambia el número: así un audio viejo nunca suena encima del nuevo
    private val speakSession = AtomicInteger(0)

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
            androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishAndroid(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishAndroid(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finishAndroid(utteranceId)
            })
            isAndroidTtsReady = true
            // Lo que se pidió decir mientras la voz se preparaba, se dice ahora (antes se perdía)
            pendingAndroid?.let { (text, done) ->
                pendingAndroid = null
                mainHandler.post { speakWithAndroidTts(text, done) }
            }
        } else {
            // Sin voz de Android: no se deja a nadie esperando a que termine de hablar
            pendingAndroid?.let { (_, done) ->
                pendingAndroid = null
                if (done != null) mainHandler.post { done() }
            }
        }
    }

    // Terminó de hablar (o no pudo): avisa en el hilo principal, solo si nadie la detuvo antes
    private fun finishAndroid(utteranceId: String?) {
        val (session, done) = androidDone.remove(utteranceId ?: return) ?: return
        mainHandler.post { if (session == speakSession.get()) done() }
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
        if (!isAndroidTtsReady) {
            // La voz de Android aún se está preparando: habla apenas esté lista
            pendingAndroid = text to onComplete
            return
        }
        applyAndroidVoice()
        val tts = androidTts ?: run { if (onComplete != null) mainHandler.post { onComplete() }; return }
        // Android lee hasta ~4000 letras de una vez: una respuesta larga se divide en partes seguidas
        val parts = splitForAndroid(text)
        val base = "TTS_ID_${System.currentTimeMillis()}"
        val lastId = "${base}_${parts.size - 1}"
        // El aviso «terminé» llega cuando suena la ÚLTIMA parte (no al empezar)
        if (onComplete != null) androidDone[lastId] = speakSession.get() to onComplete
        var failed = false
        parts.forEachIndexed { i, part ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(part, mode, null, "${base}_$i") != TextToSpeech.SUCCESS) failed = true
        }
        // Si no pudo hablar, igual avisa que terminó (así la conversación sigue)
        if (failed) finishAndroid(lastId)
    }

    private fun splitForAndroid(text: String): List<String> {
        val max = 3500
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in Regex("[^.!?\\n]*[.!?\\n]+|[^.!?\\n]+").findAll(text).map { it.value }) {
            if (sb.isNotEmpty() && sb.length + sentence.length > max) { parts.add(sb.toString()); sb.clear() }
            var s = sentence
            while (s.length > max) {   // una frase larguísima se corta en un espacio
                val cut = s.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                parts.add(s.substring(0, cut)); s = s.substring(cut)
            }
            sb.append(s)
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts.ifEmpty { listOf(text) }
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
            if (!response.isSuccessful || body == null) {
                val raw = runCatching { body?.string() }.getOrNull().orEmpty()
                val detail = runCatching {
                    val d = JSONObject(raw).opt("detail")
                    when (d) {
                        is JSONObject -> d.optString("message").ifBlank { d.optString("status") }
                        is String -> d
                        else -> raw
                    }
                }.getOrDefault(raw)
                lastElevenError = "HTTP ${response.code}: " + detail.take(220)
                return null
            }
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
        lastElevenError = null
        // Siempre la voz elegida más reciente (por si se cambió después de crear el motor)
        context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE).getString("elevenlabs_voice_id", null)
            ?.takeIf { it.isNotBlank() }?.let { elevenLabsVoiceId = it }
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
                    // Avisa por qué ElevenLabs no habló (voz no lista, sin créditos, clave, etc.)
                    lastElevenError?.let { why ->
                        android.util.Log.w("CumulusTTS", "ElevenLabs no habló: $why")   // 🔕 sin aviso emergente
                    }
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
        playbackWaiter = onComplete
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
                    if (playbackWaiter === onComplete) playbackWaiter = null
                    onComplete?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    if (playbackWaiter === onComplete) playbackWaiter = null
                    onComplete?.invoke()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (playbackWaiter === onComplete) playbackWaiter = null
            onComplete?.invoke()
        }
    }

    fun stop() {
        speakSession.incrementAndGet() // cancela las partes pendientes de ElevenLabs
        pendingAndroid = null
        androidDone.clear()            // lo que se detiene no avisa «terminé» (igual que ElevenLabs)
        try {
            // Se detiene SIEMPRE (antes solo si ya estaba sonando: lo que estaba por empezar se escapaba
            // y la IA seguía hablando aunque la interrumpieras)
            androidTts?.stop()
            mediaPlayer?.let { mp ->
                runCatching { if (mp.isPlaying) mp.stop() }
                mp.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // La parte de ElevenLabs que esperaba terminar de sonar se libera (y su archivo se borra)
        playbackWaiter?.let { waiter -> playbackWaiter = null; waiter() }
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
        speakSession.incrementAndGet() // las partes de ElevenLabs que faltaban ya no suenan
        pendingAndroid = null
        androidDone.clear()
        try {
            androidTts?.stop()
            androidTts?.shutdown()
            androidTts = null
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        playbackWaiter?.let { waiter -> playbackWaiter = null; waiter() }
    }
}
