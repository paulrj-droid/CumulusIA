package com.paulrj.cumulusia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlinx.coroutines.delay
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

    // ========================================================================
    // 👄 MEDIDOR DE VOZ PARA LA BOCA DEL AVATAR
    //    Escucha lo que sale por el parlante de la voz de Android y de ElevenLabs (el volumen real, unas 20 veces
    //    por segundo): la boca se abre cuando suena y se CIERRA en cada pausa. La voz descargada (.onnx) ya
    //    manda su volumen real por su cuenta. Si el teléfono no deja medir, se usa el modo de antes.
    // ========================================================================
    private var meter: android.media.audiofx.Visualizer? = null
    @Volatile private var meterActive = false
    private var androidSessionId = 0

    private fun meterOn(sessionId: Int): Boolean {
        meterOff()
        if (sessionId == 0) return false
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val v = android.media.audiofx.Visualizer(sessionId)
            v.enabled = false
            val range = android.media.audiofx.Visualizer.getCaptureSizeRange()
            v.captureSize = 512.coerceIn(range[0], range[1])
            runCatching { v.scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_NORMALIZED }
            v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                    val w = wave ?: return
                    if (w.isEmpty()) return
                    var sum = 0.0
                    for (b in w) { val x = ((b.toInt() and 0xFF) - 128) / 128.0; sum += x * x }
                    val rms = kotlin.math.sqrt(sum / w.size).toFloat()
                    com.paulrj.cumulusia.ui.AvatarVoice.push(minOf(1f, rms * 3.2f))
                }
                override fun onFftDataCapture(vis: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) = Unit
            }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            meter = v; meterActive = true
            true
        } catch (t: Throwable) {
            android.util.Log.w("CumulusTTS", "Avatar: no se pudo medir la voz (${t.message})")
            meterOff(); false
        }
    }

    private fun meterOff() {
        meterActive = false
        meter?.let { v -> runCatching { v.enabled = false }; runCatching { v.release() } }
        meter = null
    }
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

    // 🗣️ Voz descargada o subida (.onnx, motor sherpa-onnx): lo que está sonando y por qué no pudo hablar
    @Volatile private var localTrack: AudioTrack? = null
    @Volatile var lastLocalError: String? = null
    /** Qué está haciendo la voz del teléfono la primera vez («Preparando la voz…», «Bajando los datos…»). */
    @Volatile var onLocalStatus: ((String) -> Unit)? = null

    init {
        // Inicializar TTS nativo de Android
        androidTts = TextToSpeech(context.applicationContext, this)

        // Cargar preferencias
        reloadVoiceChoice()
    }

    /**
     * 🔄 Lee la voz elegida (Android, voz descargada .onnx o ElevenLabs) desde lo guardado.
     * Se lee CADA VEZ que la IA va a hablar: así, si clonas una voz con ElevenLabs (USAR) o eliges otra, se usa esa al
     * tiro. Antes se leía solo al crear el motor y quedaba pegada la voz anterior (por ejemplo la .onnx), y ElevenLabs
     * no sonaba.
     */
    private fun reloadVoiceChoice() {
        val prefs = context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        val providerName = prefs.getString("selected_provider", TtsProvider.ANDROID_DEFAULT.name)
        currentProvider = try {
            TtsProvider.valueOf(providerName!!)
        } catch (e: Exception) {
            TtsProvider.ANDROID_DEFAULT
        }
        currentLocalVoicePath = prefs.getString("local_voice_path", null)
        prefs.getString("android_voice_name", null)?.let { name ->
            if (name != androidVoiceName) { androidVoiceName = name; if (isAndroidTtsReady) applyAndroidVoice() }
        }
        elevenLabsApiKey = prefs.getString("elevenlabs_api_key", "") ?: ""
        elevenLabsVoiceId = prefs.getString("elevenlabs_voice_id", "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            androidTts?.language = Locale.getDefault()
            applyAndroidVoice()
            androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                // 🎭 Avatar: empieza a sonar esta parte → los gestos van al compás
                override fun onStart(utteranceId: String?) {
                    androidPartText.remove(utteranceId)?.let { part ->
                        runCatching { com.paulrj.cumulusia.ui.AvatarMood.cueChunk(part, android.os.SystemClock.uptimeMillis()) }
                    }
                }
                // 🧑‍🚀 Avatar: la voz de Android avisa al empezar cada palabra → la boca se abre y el gesto sigue la frase
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    if (!meterActive) com.paulrj.cumulusia.ui.AvatarVoice.pulse()   // (si se mide el volumen real, no hace falta)
                    runCatching { com.paulrj.cumulusia.ui.AvatarMood.atCharInChunk(start) }
                }
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
        mainHandler.post { if (session == speakSession.get()) meterOff() }   // 👄 (terminó de hablar)
        mainHandler.post { if (session == speakSession.get()) done() }
    }

    /**
     * Método unificado de voz usado por la Burbuja y por el Chat.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        stop() // Detiene reproducciones anteriores
        runCatching { com.paulrj.cumulusia.ui.AvatarMood.fromSpeech(text) }   // 🎭 el avatar pone el gesto de lo que va a decir
        runCatching { reloadVoiceChoice() }   // 🔄 siempre la voz elegida más reciente (ElevenLabs, .onnx o Android)

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
        com.paulrj.cumulusia.ui.AvatarVoice.source = 2   // 🧑‍🚀 (cómo se mueve la boca del avatar con esta voz)
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
        // 👄 La voz de Android suena en una «sesión de audio» propia: así se mide su volumen real para la boca
        if (androidSessionId == 0) androidSessionId = runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
        }.getOrDefault(0)
        val params = android.os.Bundle().apply { if (androidSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, androidSessionId) }
        if (meterOn(androidSessionId)) com.paulrj.cumulusia.ui.AvatarVoice.source = 1
        parts.forEachIndexed { i, part ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            androidPartText["${base}_$i"] = part   // 🎭 (para que el avatar sepa qué parte está sonando)
            if (tts.speak(part, mode, params, "${base}_$i") != TextToSpeech.SUCCESS) failed = true
        }
        // Si no pudo hablar, igual avisa que terminó (así la conversación sigue)
        if (failed) finishAndroid(lastId)
    }

    /** 🎭 Texto de cada parte que se le pasó a la voz de Android (por su id). */
    private val androidPartText = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    /**
     * 🗣️ Habla con la voz descargada o subida (.onnx) usando el motor sherpa-onnx, dentro del teléfono.
     * La primera frase se arma corta para que empiece casi al tiro; las demás se preparan mientras suena.
     * Si la voz no se puede usar, se dice por qué (lastLocalError y la ventana de Actividad) y lee la voz de Android,
     * para que la conversación no se quede muda. [pathOverride] = probar otra voz (botón ▶ Probar).
     */
    private fun speakWithLocalVoice(text: String, onComplete: (() -> Unit)?, pathOverride: String? = null,
                                    fallbackToAndroid: Boolean = true) {
        com.paulrj.cumulusia.ui.AvatarVoice.source = 1   // 🧑‍🚀 (cómo se mueve la boca del avatar con esta voz)
        // Siempre la voz elegida más reciente (por si se cambió después de crear el motor)
        val path = pathOverride ?: context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
            .getString("local_voice_path", null)?.takeIf { it.isNotBlank() } ?: currentLocalVoicePath
        val session = speakSession.incrementAndGet()
        lastLocalError = null
        if (path.isNullOrBlank()) {
            lastLocalError = "No hay una voz descargada elegida."
            if (fallbackToAndroid) speakWithAndroidTts(text, onComplete) else onComplete?.let { done -> mainHandler.post { done() } }
            return
        }
        val chunks = splitForSpeech(text)
        Thread {
            val tts = try {
                PiperVoice.engineFor(context, File(path)) { msg -> onLocalStatus?.let { cb -> mainHandler.post { cb(msg) } } }
            } catch (t: Throwable) {
                lastLocalError = (t as? PiperVoice.VoiceProblem)?.message ?: ("No pude usar la voz: " + (t.message ?: t.javaClass.simpleName))
                android.util.Log.w("CumulusTTS", "Voz local: $lastLocalError", t)
                runCatching { com.paulrj.cumulusia.AiActivity.log("🗣️ La voz elegida no pudo hablar; leo con la voz del teléfono", lastLocalError.orEmpty()) }
                mainHandler.post {
                    if (session != speakSession.get()) return@post
                    if (fallbackToAndroid) speakWithAndroidTts(text, onComplete) else onComplete?.invoke()
                }
                return@Thread
            }
            var track: AudioTrack? = null
            var written = 0L
            try {
                for (chunk in chunks) {
                    if (session != speakSession.get()) break
                    val audio = synchronized(PiperVoice) { tts.generate(chunk, 0, 1.0f) }
                    if (session != speakSession.get()) break
                    val samples = audio.samples
                    if (samples.isEmpty()) continue
                    if (track == null) {
                        val rate = audio.sampleRate
                        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))   // (medio segundo)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        localTrack = track
                        track.play()
                    }
                    // 🎭 Avatar: esta parte va a sonar después de lo que ya está en el parlante → ahí cambia el gesto
                    try {
                        val head0 = track!!.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        com.paulrj.cumulusia.ui.AvatarMood.cueChunk(chunk, android.os.SystemClock.uptimeMillis() +
                            ((written - head0).coerceAtLeast(0L) * 1000L / audio.sampleRate.coerceAtLeast(1)))
                    } catch (_: Throwable) {}
                    // Se escribe por partes chicas: así «detener» corta al instante
                    var off = 0
                    val step = 2048
                    while (off < samples.size && session == speakSession.get()) {
                        val n = minOf(step, samples.size - off)
                        // 🧑‍🚀 Avatar: cuánto suena este pedazo (y cuándo va a sonar) para mover la boca
                        try {
                            val head = track!!.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            val baseAt = android.os.SystemClock.uptimeMillis() + ((written - head).coerceAtLeast(0L) * 1000L / audio.sampleRate.coerceAtLeast(1))
                            var q = 0
                            while (q < n) {
                                val m = minOf(512, n - q)
                                var sum = 0.0
                                for (k in 0 until m) { val v = samples[off + q + k]; sum += v * v }
                                val rms = kotlin.math.sqrt(sum / m).toFloat()
                                com.paulrj.cumulusia.ui.AvatarVoice.pushAt(minOf(1f, rms * 5f), baseAt + q * 1000L / audio.sampleRate.coerceAtLeast(1))
                                q += m
                            }
                        } catch (_: Throwable) {}
                        val w = track!!.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) break
                        off += w
                        written += w
                    }
                }
                // Espera a que termine de sonar lo último
                val t = track
                if (t != null) {
                    while (session == speakSession.get() && t.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written) Thread.sleep(25)
                }
            } catch (t: Throwable) {
                android.util.Log.w("CumulusTTS", "La voz local se cortó", t)
            } finally {
                track?.let { tr ->
                    runCatching { tr.stop() }
                    runCatching { tr.release() }
                    if (localTrack === tr) localTrack = null
                }
            }
            mainHandler.post { if (session == speakSession.get()) onComplete?.invoke() }
        }.start()
    }

    /** ▶ Probar una voz descargada o subida (.onnx) con una frase corta, sin elegirla. */
    fun testLocalVoice(path: String, text: String, onComplete: (() -> Unit)?) {
        stop()
        speakWithLocalVoice(text, onComplete, pathOverride = path, fallbackToAndroid = false)
    }

    /** ▶ Probar la voz del teléfono con una frase corta. */
    fun testAndroidVoice(text: String, onComplete: (() -> Unit)?) {
        stop()
        speakWithAndroidTts(text, onComplete)
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

    /** 🔁 Esperas entre reintentos de una parte de ElevenLabs que no llegó (en total unos 30 s). */
    private val ELEVEN_RETRY_WAITS = longArrayOf(1000, 2000, 3000, 4000, 5000, 5000, 5000, 5000)

    private fun speakWithElevenLabs(text: String, onComplete: (() -> Unit)?) {
        // 🧑‍🚀 (boca con el volumen real: cerrada mientras llega el audio, se abre cuando suena)
        com.paulrj.cumulusia.ui.AvatarVoice.source = 1
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
                // 🔁 Si una parte no llega (por ejemplo al minimizar la app, el teléfono corta internet un rato a las
                //    apps de atrás), se vuelve a pedir unas veces (hasta ~30 s) en vez de pasarse a la voz de Android.
                //    Mientras tanto la voz espera callada y después sigue con ElevenLabs.
                var file: File? = null
                var attempt = 0
                while (session == speakSession.get()) {
                    lastElevenError = null
                    file = runCatching {
                        fetchElevenLabsChunk(chunks[i], chunks.getOrNull(i - 1), chunks.getOrNull(i + 1), i, session)
                    }.onFailure { it.printStackTrace() }.getOrNull()
                    if (file != null) break
                    // (sin créditos, clave mala, voz que no existe… no se arregla esperando: ahí sí pasa a Android)
                    val code = Regex("^HTTP (\\d+)").find(lastElevenError.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    val permanent = code != null && code in 400..499 && code != 408 && code != 429
                    if (permanent || attempt >= ELEVEN_RETRY_WAITS.size) break
                    android.util.Log.w("CumulusTTS", "ElevenLabs: parte ${i + 1} no llegó (${lastElevenError ?: "sin conexión"}); reintento ${attempt + 1}")
                    delay(ELEVEN_RETRY_WAITS[attempt++])
                }
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
                runCatching { com.paulrj.cumulusia.ui.AvatarMood.cueChunk(chunks[i], android.os.SystemClock.uptimeMillis()) }   // 🎭
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
                // 👄 ElevenLabs: se mide el volumen real de lo que suena para la boca del avatar
                com.paulrj.cumulusia.ui.AvatarVoice.source = if (meterOn(audioSessionId)) 1 else 0   // (si no deja medir: sílabas)
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
            meterOff()   // 👄
            // 🗣️ La voz descargada se calla al instante (lo que quedaba por sonar se descarta)
            localTrack?.let { tr -> runCatching { tr.pause() }; runCatching { tr.flush() } }
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
            localTrack?.let { tr -> runCatching { tr.pause() }; runCatching { tr.flush() } }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        playbackWaiter?.let { waiter -> playbackWaiter = null; waiter() }
    }
}
