package com.paulrj.cumulusia

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.paulrj.cumulusia.adapter.ChatAdapter
import com.paulrj.cumulusia.ai.LiteRtLmEngineManager
import com.paulrj.cumulusia.model.ChatMessage
import com.paulrj.cumulusia.manager.ResumableDownloader
import com.paulrj.cumulusia.manager.VoiceManager
import com.paulrj.cumulusia.manager.LanguageManager
import com.paulrj.cumulusia.tts.TtsEngineManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

// ============================================================================
// 1. ACTIVIDAD PRINCIPAL (PANTALLA CON PANELES LATERALES ☰ Y ️)
// ============================================================================
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.loadLocale(newBase))
    }

    private var drawerLayout: DrawerLayout? = null
    private var settingsPageFlipper: ViewFlipper? = null
    private var btnOpenGgufDownloads: MaterialButton? = null
    private var btnOpenTtsDownloads: MaterialButton? = null
    private var btnBackFromGgufDownload: ImageButton? = null
    private var btnBackFromTtsDownload: ImageButton? = null
    
    private var spinnerBackend: Spinner? = null
    private var btnLoadModel: MaterialButton? = null
    private var btnModelName: MaterialButton? = null
    private var btnBackend: MaterialButton? = null
    
    private var rvChatMessages: RecyclerView? = null
    private var etChatInput: EditText? = null
    private var chatComposer: ConstraintLayout? = null
    private var btnSend: ImageButton? = null
    private var btnMic: ImageButton? = null
    private var btnVoicePlayPause: ImageButton? = null
    private var tvAiName: TextView? = null
    private var isVocalResponseActive: Boolean = false
    private var isChatInputExpanded: Boolean = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningForChat: Boolean = false
    private var chatTtsEngine: TtsEngineManager? = null
    
    private var chatAdapter: ChatAdapter? = null
    private val chatMessages = mutableListOf<ChatMessage>()

    private var etGgufUrl: EditText? = null
    private var btnAddGgufUrl: MaterialButton? = null
    private var ggufDownloadsContainer: LinearLayout? = null
    private var tvEmptyGgufDownloads: TextView? = null
    private var etTtsUrl: EditText? = null
    private var btnAddTtsUrl: MaterialButton? = null
    private var ttsDownloadsContainer: LinearLayout? = null
    private var tvEmptyTtsDownloads: TextView? = null
    private var tvModelLoadedName: TextView? = null
    private var isModelLoadedInMemory: Boolean = false

    private class DownloadUi(
        val panel: View, val title: TextView, val status: TextView, val progress: ProgressBar,
        val btnPause: Button, val btnResume: Button, val btnDelete: Button
    )
    private enum class DownloadType {
        GGUF,
        TTS
    }
    private class DownloadSlot(val type: DownloadType, val ui: DownloadUi) {
        var id: String = "${type.name.lowercase()}_${System.currentTimeMillis()}_${hashCode()}"
        var downloader: ResumableDownloader? = null
        var file: File? = null
        var url: String = ""
        var title: String = ""
    }

    private data class ElevenLabsVoice(
        val id: String,
        val name: String,
        val category: String,
        val labels: String
    )

    private enum class VoiceSource {
        SYSTEM,
        DOWNLOADED,
        ELEVEN_LABS
    }

    private data class SelectableTtsVoice(
        val id: String,
        val displayName: String,
        val source: VoiceSource,
        val localPath: String? = null
    )
    private val ggufSlots = mutableListOf<DownloadSlot>()
    private val ttsSlots = mutableListOf<DownloadSlot>()

    private var btnChooseBubbleImage: MaterialButton? = null
    private var btnRestoreChatImage: MaterialButton? = null
    private var btnChooseVoices: MaterialButton? = null
    private var bubbleImageUri: Uri? = null

    private var etElevenApiKey: EditText? = null
    private var btnSaveElevenKey: MaterialButton? = null
    private var btnElevenRecord: MaterialButton? = null
    private var btnElevenTest: MaterialButton? = null
    private var btnElevenUse: MaterialButton? = null
    private var btnElevenDelete: MaterialButton? = null

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedAudioFile: File? = null
    private var isRecordingVoice: Boolean = false
    private var voiceBrowserTts: TextToSpeech? = null

    private var etAiNameCustom: EditText? = null
    private var btnSaveAiName: MaterialButton? = null
    private var etUserName: EditText? = null
    private var btnSaveUserName: MaterialButton? = null

    private var seekContext: SeekBar? = null
    private var tvContextValue: TextView? = null
    private var seekTemperature: SeekBar? = null
    private var tvTemperatureValue: TextView? = null
    private var seekTopK: SeekBar? = null
    private var seekTopP: SeekBar? = null
    private var btnPersonalityGeneral: Button? = null
    private var btnPersonalityProgrammer: Button? = null
    private var btnPersonalityCreative: Button? = null
    
    private var etPersonalityCustom: EditText? = null
    private var btnSaveCustomPersonality: MaterialButton? = null
    private var btnDeleteCustomPersonality: MaterialButton? = null

    private var isAppInForeground: Boolean = false
    private var modelLoadedInBackground: Boolean = false
    private var restoringSwitchState: Boolean = false

    companion object {
        private const val TAG = "CumulusMainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "CumulusAppPermissions"
        private const val PICK_IMAGE_REQUEST = 1002
        private const val KEY_DOWNLOADS = "persisted_downloads"
        private const val PAGE_SETTINGS = 0
        private const val PAGE_GGUF_DOWNLOAD = 1
        private const val PAGE_TTS_DOWNLOAD = 2

        init {
            try {
                System.loadLibrary("cumulus_native")
                Log.d(TAG, "✅ Librería nativa cumulus_native cargada exitosamente")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Error cargando librería nativa: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    external fun stringFromJNI(): String
    private external fun nativeLoadModel(modelPath: String): Boolean
    private external fun nativeConfigureAndLoadModel(
        modelPath: String,
        backend: String,
        contextSize: Int,
        temperature: Float
    ): Boolean
    private external fun nativeRunInference(prompt: String): String
    private external fun nativeIsModelLoaded(): Boolean
    private external fun nativeReleaseModel()
    private external fun nativeSetBackend(backend: String)
    private external fun nativeSetParams(ctx: Int, temp: Float)
    private external fun nativeEngineInfo(): String
    private external fun nativeGetDeviceCapabilities(): String  // ✅ NUEVO: Detecta CPU/GPU/NPU disponibles

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_main)
        chatTtsEngine = TtsEngineManager(this)
        setupChatSpeechRecognizer()

        initViews()
        setupDrawerControls()
        setupRightDrawerActions()
        restoreSavedDownloads()
        setupChatActions()
        setupNameConfig()
        setupImageAndVoiceConfig()
        setupElevenLabsActions()
        setupAdvancedConfig()
        setupLanguageSelection()
        setupBackPressedHandler()
        verifyAndRequestPermissions()
        
        // ✅ NUEVO: Mostrar capacidades del dispositivo al iniciar
        showDeviceCapabilities()
        
        checkStoredModelStatus()
        addMessageToChat(
            "Cumulus IA",
            LanguageManager.text(this, "¡Hola! ¿En qué te puedo ayudar?"),
            "42 tokens",
            false
        )
        LanguageManager.applyToViewTree(findViewById(android.R.id.content))
    }

    // ✅ NUEVO: Muestra qué hardware está disponible
    private fun showDeviceCapabilities() {
        try {
            val caps = nativeGetDeviceCapabilities()
            val hybridCaps = "$caps\n⚡ ${LiteRtLmEngineManager.deviceNpuInfo(this)}"
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "🔍 Capacidades del dispositivo:")
            Log.d(TAG, hybridCaps)
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Toast.makeText(this, hybridCaps, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Error obteniendo capacidades: ${t.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        isAppInForeground = true
        updateLoadModelButton()
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        
        if (isModelLoadedInMemory && !modelLoadedInBackground) {
            Log.d(TAG, "Auto-desactivación: App en segundo plano, liberando motor IA...")
            releaseModelFromMemory()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        settingsPageFlipper = findViewById(R.id.settings_page_flipper)
        btnOpenGgufDownloads = findViewById(R.id.btn_open_gguf_downloads)
        btnOpenTtsDownloads = findViewById(R.id.btn_open_tts_downloads)
        btnBackFromGgufDownload = findViewById(R.id.btn_back_from_gguf_download)
        btnBackFromTtsDownload = findViewById(R.id.btn_back_from_tts_download)

        btnChooseBubbleImage = findViewById(R.id.btn_choose_bubble_image)
        btnRestoreChatImage = findViewById(R.id.btn_restore_chat_image)
        btnChooseVoices = findViewById(R.id.btn_choose_voices)

        spinnerBackend = findViewById(R.id.spinner_backend)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnModelName = findViewById(R.id.btn_model_name)
        btnBackend = findViewById(R.id.btn_backend)
        tvModelLoadedName = findViewById(R.id.tv_model_loaded_name)
        
        etGgufUrl = findViewById(R.id.et_gguf_url)
        btnAddGgufUrl = findViewById(R.id.btn_add_gguf_url)
        ggufDownloadsContainer = findViewById(R.id.downloads_container_gguf)
        tvEmptyGgufDownloads = findViewById(R.id.tv_empty_gguf_downloads)
        etTtsUrl = findViewById(R.id.et_tts_url)
        btnAddTtsUrl = findViewById(R.id.btn_add_tts_url)
        ttsDownloadsContainer = findViewById(R.id.downloads_container_tts)
        tvEmptyTtsDownloads = findViewById(R.id.tv_empty_tts_downloads)
        updateEmptyDownloadState(DownloadType.GGUF)
        updateEmptyDownloadState(DownloadType.TTS)

        etElevenApiKey = findViewById(R.id.et_eleven_api_key)
        btnSaveElevenKey = findViewById(R.id.btn_save_eleven_key)
        btnElevenRecord = findViewById(R.id.btn_eleven_record)
        btnElevenTest = findViewById(R.id.btn_eleven_test)
        btnElevenUse = findViewById(R.id.btn_eleven_use)
        btnElevenDelete = findViewById(R.id.btn_eleven_delete)

        rvChatMessages = findViewById(R.id.rv_chat_messages)
        etChatInput = findViewById(R.id.et_chat_input)
        chatComposer = findViewById(R.id.chat_composer)
        btnSend = findViewById(R.id.btn_send)
        btnMic = findViewById(R.id.btn_mic)
        btnVoicePlayPause = findViewById(R.id.btn_voice_play_pause)
        tvAiName = findViewById(R.id.tv_ai_name)
        etAiNameCustom = findViewById(R.id.et_ai_name_custom)
        btnSaveAiName = findViewById(R.id.btn_save_ai_name)
        etUserName = findViewById(R.id.et_user_name)
        btnSaveUserName = findViewById(R.id.btn_save_user_name)

        seekContext = findViewById(R.id.seek_context)
        tvContextValue = findViewById(R.id.tv_context_value)
        seekTemperature = findViewById(R.id.seek_temperature)
        tvTemperatureValue = findViewById(R.id.tv_temperature_value)
        seekTopK = findViewById(R.id.seek_top_k)
        seekTopP = findViewById(R.id.seek_top_p)
        btnPersonalityGeneral = findViewById(R.id.btn_personality_general)
        btnPersonalityProgrammer = findViewById(R.id.btn_personality_programmer)
        btnPersonalityCreative = findViewById(R.id.btn_personality_creative)
        
        etPersonalityCustom = findViewById(R.id.et_personality_custom)
        btnSaveCustomPersonality = findViewById(R.id.btn_save_custom_personality)
        btnDeleteCustomPersonality = findViewById(R.id.btn_delete_custom_personality)

        chatAdapter = ChatAdapter(chatMessages) { bubbleImageUri }
        rvChatMessages?.layoutManager = LinearLayoutManager(this)
        rvChatMessages?.adapter = chatAdapter

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString("eleven_api_key", "")
        if (!savedKey.isNullOrEmpty()) {
            etElevenApiKey?.setText(savedKey)
        }
        
        val savedAiName = prefs.getString("ai_name", "Cumulus IA")
        tvAiName?.text = savedAiName
        
        val savedUserName = prefs.getString("user_name", "")
        etUserName?.setText(savedUserName)
        
        val savedCustomPersonality = prefs.getString("custom_personality", "")
        etPersonalityCustom?.setText(savedCustomPersonality)
        
        val savedBubbleImage = prefs.getString("bubble_image_uri", null)
        if (savedBubbleImage != null) {
            bubbleImageUri = Uri.parse(savedBubbleImage)
        }
        updateBubbleImageButton()
        
        etChatInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                etChatInput?.post {
                    (etChatInput?.parent as? View)?.requestLayout()
                }
            }
        })
    }

    private fun addMessageToChat(sender: String, text: String, tokens: String = "", isUser: Boolean = false) {
        val message = ChatMessage(
            text = text,
            isUser = isUser,
            sender = if (isUser) "Tú" else "Cumulus IA",
            tokens = tokens
        )
        chatMessages.add(message)
        chatAdapter?.notifyItemInserted(chatMessages.size - 1)
        rvChatMessages?.scrollToPosition(chatMessages.size - 1)
    }

    private fun updateChatAvatars() {
        chatAdapter?.notifyDataSetChanged()
    }

    private fun setupChatActions() {
        etChatInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val input = etChatInput ?: return
                if (s.isNullOrEmpty()) {
                    updateChatComposerExpansion(false)
                    return
                }

                if (!isChatInputExpanded) {
                    input.post {
                        if (!isChatInputExpanded && input.lineCount > 1) {
                            updateChatComposerExpansion(true)
                        }
                    }
                }
            }
        })

        etChatInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend?.performClick()
                true
            } else {
                false
            }
        }

        btnSend?.setOnClickListener {
            val message = etChatInput?.text?.toString()?.trim() ?: ""
            if (message.isEmpty()) {
                Toast.makeText(this, "Escribe un mensaje", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addMessageToChat("Tú", message, "", true)
            etChatInput?.text?.clear()

            val thinkingIndex = chatMessages.size
            chatMessages.add(ChatMessage(
                text = "Pensando",
                isUser = false,
                sender = "Cumulus IA",
                tokens = ""
            ))
            chatAdapter?.notifyItemInserted(thinkingIndex)
            rvChatMessages?.scrollToPosition(thinkingIndex)
            hideKeyboardAndRestoreChat()

            val selectedBackend = spinnerBackend?.selectedItem?.toString() ?: getSavedBackend()
            thread {
                val start = System.currentTimeMillis()
                try {
                    val reply = generateAiResponse(message, selectedBackend)
                    val secs = (System.currentTimeMillis() - start) / 1000f
                    runOnUiThread {
                        chatMessages[thinkingIndex] = ChatMessage(
                            text = reply.text,
                            isUser = false,
                            sender = "Cumulus IA",
                            tokens = if (reply.tokens.isNotEmpty())
                                "${reply.tokens} · ${String.format(Locale.US, "%.1f", secs)} s"
                            else String.format(Locale.US, "%.1f s", secs)
                        )
                        chatAdapter?.notifyItemChanged(thinkingIndex)
                        rvChatMessages?.scrollToPosition(thinkingIndex)
                        if (isVocalResponseActive && reply.text.isNotBlank()) {
                            chatTtsEngine?.speak(reply.text)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error generando respuesta: ${t.message}")
                    runOnUiThread {
                        chatMessages[thinkingIndex] = ChatMessage(
                            text = "⚠️ Ocurrió un error al procesar tu mensaje.",
                            isUser = false,
                            sender = "Cumulus IA",
                            tokens = ""
                        )
                        chatAdapter?.notifyItemChanged(thinkingIndex)
                    }
                }
            }
        }

        btnVoicePlayPause?.setOnClickListener {
            if (isVocalResponseActive) {
                isVocalResponseActive = false
                chatTtsEngine?.stop()
                btnVoicePlayPause?.setImageResource(android.R.drawable.ic_media_play)
            } else {
                isVocalResponseActive = true
                btnVoicePlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                Toast.makeText(this, "Respuesta por voz activada", Toast.LENGTH_SHORT).show()
            }
        }

        btnMic?.setOnClickListener {
            toggleChatVoiceRecognition()
        }
    }

    private fun setupChatSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "El reconocimiento de voz de Android no está disponible")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningForChat = true
                    Toast.makeText(this@MainActivity, "Escuchando...", Toast.LENGTH_SHORT).show()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    isListeningForChat = false
                }

                override fun onError(error: Int) {
                    isListeningForChat = false
                    Log.w(TAG, "Error de reconocimiento de voz: $error")
                    Toast.makeText(
                        this@MainActivity,
                        "No pude reconocer la voz. Inténtalo nuevamente.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onResults(results: Bundle?) {
                    isListeningForChat = false
                    val spokenText = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spokenText.isNotEmpty()) {
                        etChatInput?.setText(spokenText)
                        etChatInput?.setSelection(spokenText.length)
                        btnSend?.performClick()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialText = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partialText.isNotEmpty()) {
                        etChatInput?.setText(partialText)
                        etChatInput?.setSelection(partialText.length)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun toggleChatVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        val recognizer = speechRecognizer ?: run {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (isListeningForChat) {
            recognizer.stopListening()
            isListeningForChat = false
            return
        }
        val languageCode = LanguageManager.getSelectedLanguage(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }
    private fun updateChatComposerExpansion(expanded: Boolean) {
        if (isChatInputExpanded == expanded) return
        val composer = chatComposer ?: return
        isChatInputExpanded = expanded

        val constraints = ConstraintSet()
        constraints.clone(composer)
        constraints.clear(R.id.et_chat_input, ConstraintSet.START)
        constraints.clear(R.id.et_chat_input, ConstraintSet.END)
        constraints.clear(R.id.et_chat_input, ConstraintSet.TOP)
        constraints.clear(R.id.et_chat_input, ConstraintSet.BOTTOM)

        if (expanded) {
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.BOTTOM,
                R.id.btn_voice_play_pause,
                ConstraintSet.TOP,
                4
            )
        } else {
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.START,
                R.id.btn_voice_play_pause,
                ConstraintSet.END
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.END,
                R.id.btn_mic,
                ConstraintSet.START
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP
            )
            constraints.connect(
                R.id.et_chat_input,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM
            )
        }

        constraints.applyTo(composer)
    }

    private fun hideKeyboardAndRestoreChat() {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val inputView = etChatInput
        if (inputView != null) {
            inputMethodManager.hideSoftInputFromWindow(inputView.windowToken, 0)
            inputView.clearFocus()
        }

        rvChatMessages?.post {
            val lastPosition = chatMessages.lastIndex
            if (lastPosition >= 0) {
                rvChatMessages?.scrollToPosition(lastPosition)
            }
        }
    }

    private data class AiReply(val text: String, val tokens: String)

    private fun generateAiResponse(prompt: String, backend: String): AiReply {
        if (!triggerAutomaticModelLoadOnFirstMessage()) {
            return AiReply(
                "📥 Todavía no tengo ningún modelo cargado en memoria.\n\n" +
                "1️⃣ Descarga un modelo GGUF desde el panel ☰\n" +
                "2️⃣ Vuelve a preguntarme: lo activaré automáticamente con tu primer mensaje.\n" +
                "3️⃣ También puedes pulsar «Cargar modelo» manualmente.",
                ""
            )
        }
        return try {
            val activeModel = getActiveModelFile()
            val usingLiteRt = activeModel?.extension.equals("litertlm", ignoreCase = true)
            val raw = if (usingLiteRt) {
                runBlocking { LiteRtLmEngineManager.generate(prompt) }
            } else {
                nativeRunInference(prompt)
            }
            val tokenCount = raw.split(" ").size
            val engine = if (usingLiteRt) {
                LiteRtLmEngineManager.engineInfo()
            } else {
                try { nativeEngineInfo() } catch (t: Throwable) { "💻 CPU (NEON)" }
            }
            AiReply(raw, "$tokenCount tokens · $engine")
        } catch (t: Throwable) {
            Log.e(TAG, "Fallo del motor nativo: ${t.message}")
            AiReply("⚠️ El motor nativo no está disponible (${t.javaClass.simpleName}).", "")
        }
    }

    private fun getSavedBackend(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString("selected_ai_backend", "💻 CPU (NEON)") ?: "💻 CPU (NEON)"
        return if (saved == "🎮 GPU (Vulkan)") "🎮 GPU (Adreno)" else saved
    }
        
    private fun saveBackend(backend: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("selected_ai_backend", backend).apply()
        Log.d(TAG, "💾 Backend guardado: $backend")
    }

    private fun applyBackendToEngine(backend: String) {
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🔧 Aplicando backend al motor nativo: $backend")
        try {
            nativeSetBackend(backend)
            Log.d(TAG, "✅ Backend aplicado exitosamente: $backend")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error aplicando backend: ${t.message}")
            t.printStackTrace()
        }
        Log.d(TAG, "═══════════════════════════════════════════════════")
    }

    private fun applyParamsToEngine() {
        val ctx = seekContext?.progress ?: 2048
        val temp = (seekTemperature?.progress ?: 70) / 100f
        Log.d(TAG, "Aplicando parámetros: ctx=$ctx, temp=$temp")
        try {
            nativeSetParams(ctx, temp)
            Log.d(TAG, "✅ Parámetros aplicados exitosamente")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error aplicando parámetros: ${t.message}")
        }
    }

    private fun loadModelWithConfiguration(modelFile: File, backend: String): Boolean {
        if (modelFile.extension.equals("litertlm", ignoreCase = true)) {
            nativeReleaseModel()
            return runBlocking {
                LiteRtLmEngineManager.load(
                    applicationContext,
                    modelFile.absolutePath,
                    backend,
                    "main_activity"
                )
            }
        }
        runBlocking { LiteRtLmEngineManager.release("main_activity") }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val contextSize = prefs.getInt("ai_context", seekContext?.progress ?: 1024)
        val temperature = prefs.getFloat(
            "ai_temperature",
            (seekTemperature?.progress ?: 70) / 100f
        )
        return nativeConfigureAndLoadModel(
            modelFile.absolutePath,
            backend,
            contextSize,
            temperature
        )
    }

    private fun reloadModelWithBackend(backend: String) {
        val modelFile = getActiveModelFile() ?: return
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🔄 Recargando modelo con nuevo backend: $backend")
        Log.d(TAG, "Archivo: ${modelFile.absolutePath}")
        Log.d(TAG, "═══════════════════════════════════════════════════")
        
        Toast.makeText(this, "Recargando modelo en $backend...", Toast.LENGTH_SHORT).show()
        thread {
            val success = loadModelWithConfiguration(modelFile, backend)
            runOnUiThread {
                isModelLoadedInMemory = success
                updateLoadModelButton()
                if (success) {
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - Cargado]"
                    Toast.makeText(this, "✅ Modelo recargado exitosamente en $backend", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "✅ Modelo recargado exitosamente en $backend")
                } else {
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} (error al recargar en $backend)"
                    Toast.makeText(this, "❌ Error al recargar modelo en $backend", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "❌ Error al recargar modelo en $backend")
                }
            }
        }
    }

    private fun setupNameConfig() {
        btnSaveAiName?.setOnClickListener {
            val name = etAiNameCustom?.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "Escribe un nombre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("ai_name", name).apply()
            tvAiName?.text = name
            Toast.makeText(this, "Nombre de la IA guardado", Toast.LENGTH_SHORT).show()
        }

        btnSaveUserName?.setOnClickListener {
            val name = etUserName?.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "Escribe un nombre de usuario", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("user_name", name).apply()
            Toast.makeText(this, "Nombre de usuario guardado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupImageAndVoiceConfig() {
        btnChooseBubbleImage?.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        btnRestoreChatImage?.setOnClickListener {
            useDefaultBubbleImage()
        }

        btnChooseVoices?.setOnClickListener {
            showVoiceSelectionDialog()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            val selectedUri = data.data ?: return
            try {
                val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(selectedUri, takeFlags)
            } catch (e: SecurityException) {
                Log.w(TAG, "No se pudo conservar el permiso de lectura de la imagen: ${e.message}")
            }
            bubbleImageUri = selectedUri
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("bubble_image_uri", selectedUri.toString()).apply()
            updateBubbleImageEverywhere()
            Toast.makeText(this, "Imagen personalizada aplicada al chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun useDefaultBubbleImage() {
        bubbleImageUri = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("bubble_image_uri")
            .apply()
        updateBubbleImageEverywhere()
        Toast.makeText(this, "Imagen predeterminada restaurada en el chat", Toast.LENGTH_SHORT).show()
    }

    private fun updateBubbleImageButton() {
        btnRestoreChatImage?.isEnabled = bubbleImageUri != null
    }

    private fun updateBubbleImageEverywhere() {
        updateBubbleImageButton()
        updateChatAvatars()
    }

    private fun showVoiceSelectionDialog() {
        val appPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = appPrefs.getString("eleven_api_key", "")?.trim().orEmpty()
        val downloadedVoices = getDownloadedTtsChoices()

        querySystemVoices { systemVoices ->
            if (apiKey.isEmpty()) {
                showUnifiedVoiceSelectionDialog(
                    systemVoices = systemVoices,
                    downloadedVoices = downloadedVoices,
                    elevenLabsVoices = emptyList(),
                    elevenLabsApiKey = "",
                    elevenLabsError = "Guarda una API Key para consultar las voces de ElevenLabs."
                )
                return@querySystemVoices
            }

            val progressDialog = ProgressDialog(this).apply {
                setTitle("Voces disponibles")
                setMessage("Consultando voces reales de ElevenLabs...")
                setCancelable(false)
                show()
            }

            thread {
                val result = try {
                    Result.success(fetchElevenLabsVoices(apiKey))
                } catch (t: Throwable) {
                    Result.failure<List<ElevenLabsVoice>>(t)
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    result.onSuccess { voices ->
                        showUnifiedVoiceSelectionDialog(
                            systemVoices = systemVoices,
                            downloadedVoices = downloadedVoices,
                            elevenLabsVoices = voices,
                            elevenLabsApiKey = apiKey,
                            elevenLabsError = null
                        )
                    }.onFailure { error ->
                        showUnifiedVoiceSelectionDialog(
                            systemVoices = systemVoices,
                            downloadedVoices = downloadedVoices,
                            elevenLabsVoices = emptyList(),
                            elevenLabsApiKey = apiKey,
                            elevenLabsError = "No se pudieron consultar las voces de ElevenLabs: ${error.message ?: "error de conexión"}"
                        )
                    }
                }
            }
        }
    }

    private fun querySystemVoices(onReady: (List<SelectableTtsVoice>) -> Unit) {
        voiceBrowserTts?.shutdown()
        voiceBrowserTts = null
        voiceBrowserTts = TextToSpeech(applicationContext, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                val systemVoices = if (status == TextToSpeech.SUCCESS) {
                    voiceBrowserTts?.voices.orEmpty()
                        .filter { it.name.isNotBlank() }
                        .distinctBy { it.name }
                        .map { voice ->
                            val networkTag = if (voice.isNetworkConnectionRequired) {
                                " · requiere red"
                            } else {
                                ""
                            }
                            SelectableTtsVoice(
                                id = voice.name,
                                displayName = "${voice.name} · ${voice.locale.displayName}$networkTag",
                                source = VoiceSource.SYSTEM
                            )
                        }
                        .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
                } else {
                    emptyList()
                }

                runOnUiThread {
                    voiceBrowserTts?.shutdown()
                    voiceBrowserTts = null
                    onReady(systemVoices)
                }
            }
        })
    }

    private fun getDownloadedTtsChoices(): List<SelectableTtsVoice> {
        val managedVoices = runCatching {
            VoiceManager(this).getDownloadedVoices()
        }.getOrDefault(emptyList())

        val downloadsDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val downloadedFiles = downloadsDirectory?.listFiles { _, name ->
            name.endsWith(".onnx", ignoreCase = true) ||
                name.endsWith(".bin", ignoreCase = true) ||
                name.endsWith(".zip", ignoreCase = true)
        }.orEmpty()

        return (managedVoices.map { voice ->
            voice.filePath to voice.name
        } + downloadedFiles.map { file ->
            file.absolutePath to file.name
        })
            .distinctBy { it.first }
            .map { (path, name) ->
                SelectableTtsVoice(
                    id = path,
                    displayName = name,
                    source = VoiceSource.DOWNLOADED,
                    localPath = path
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
    }

    private fun showUnifiedVoiceSelectionDialog(
        systemVoices: List<SelectableTtsVoice>,
        downloadedVoices: List<SelectableTtsVoice>,
        elevenLabsVoices: List<ElevenLabsVoice>,
        elevenLabsApiKey: String,
        elevenLabsError: String?
    ) {
        val choices = buildList {
            addAll(systemVoices)
            addAll(downloadedVoices)
            addAll(elevenLabsVoices.map { voice ->
                val details = buildString {
                    if (voice.category.isNotBlank()) append(voice.category)
                    if (voice.labels.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(voice.labels)
                    }
                }
                SelectableTtsVoice(
                    id = voice.id,
                    displayName = buildString {
                        append(voice.name)
                        if (details.isNotBlank()) append(" · $details")
                    },
                    source = VoiceSource.ELEVEN_LABS
                )
            })
        }

        if (choices.isEmpty()) {
            Toast.makeText(this, "No hay voces disponibles para seleccionar", Toast.LENGTH_LONG).show()
            return
        }

        if (!elevenLabsError.isNullOrBlank()) {
            Toast.makeText(this, elevenLabsError, Toast.LENGTH_LONG).show()
        }

        val ttsPrefs = getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        val selectedId = when (ttsPrefs.getString("selected_provider", null)) {
            "ANDROID_DEFAULT" -> ttsPrefs.getString("android_voice_name", null)
            "LOCAL_DOWNLOADED" -> ttsPrefs.getString("local_voice_path", null)
            "ELEVEN_LABS" -> ttsPrefs.getString("elevenlabs_voice_id", null)
            else -> null
        }
        val selectedIndex = choices.indexOfFirst { it.id == selectedId }
        val labels = choices.map { choice ->
            val sourceLabel = when (choice.source) {
                VoiceSource.SYSTEM -> "Sistema"
                VoiceSource.DOWNLOADED -> "TTS descargada"
                VoiceSource.ELEVEN_LABS -> "ElevenLabs"
            }
            "$sourceLabel · ${choice.displayName}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Elegir voz para la IA")
            .setMessage("Puedes elegir una voz del sistema, una TTS descargada o una voz real de ElevenLabs.")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                saveSelectedTtsVoice(elevenLabsApiKey, choices[which])
                Toast.makeText(
                    this,
                    "Voz activa: ${choices[which].displayName}",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fetchElevenLabsVoices(apiKey: String): List<ElevenLabsVoice> {
        val connection = (URL("https://api.elevenlabs.io/v1/voices").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
            connect()
        }

        try {
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw Exception("HTTP $responseCode")
            }
            val responseBody = responseStream.bufferedReader().use { it.readText() }
            if (responseCode !in 200..299) {
                val detail = runCatching {
                    JSONObject(responseBody).optString("detail")
                }.getOrNull().orEmpty()
                throw Exception(if (detail.isNotBlank()) detail else "HTTP $responseCode")
            }

            val voicesJson = JSONObject(responseBody).optJSONArray("voices") ?: JSONArray()
            return buildList {
                for (index in 0 until voicesJson.length()) {
                    val voice = voicesJson.optJSONObject(index) ?: continue
                    val id = voice.optString("voice_id").trim()
                    val name = voice.optString("name").trim()
                    if (id.isEmpty() || name.isEmpty()) continue

                    val labelsJson = voice.optJSONObject("labels")
                    val labels = labelsJson?.keys()?.asSequence()
                        ?.mapNotNull { key ->
                            val value = labelsJson.optString(key).trim()
                            if (value.isBlank()) null else "$key: $value"
                        }
                        ?.joinToString(" · ")
                        .orEmpty()

                    add(
                        ElevenLabsVoice(
                            id = id,
                            name = name,
                            category = voice.optString("category").trim(),
                            labels = labels
                        )
                    )
                }
            }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        } finally {
            connection.disconnect()
        }
    }
    private fun saveSelectedTtsVoice(apiKey: String, voice: SelectableTtsVoice) {
        val ttsPrefs = getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        val appPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (voice.source) {
            VoiceSource.SYSTEM -> {
                ttsPrefs.edit()
                    .putString("selected_provider", "ANDROID_DEFAULT")
                    .putString("android_voice_name", voice.id)
                    .remove("local_voice_path")
                    .apply()
            }
            VoiceSource.DOWNLOADED -> {
                val localPath = voice.localPath ?: voice.id
                VoiceManager(this).saveSelectedVoice(localPath)
                ttsPrefs.edit()
                    .putString("selected_provider", "LOCAL_DOWNLOADED")
                    .putString("local_voice_path", localPath)
                    .apply()
            }
            VoiceSource.ELEVEN_LABS -> {
                appPrefs.edit()
                    .putString("eleven_active_voice_id", voice.id)
                    .apply()
                ttsPrefs.edit()
                    .putString("elevenlabs_api_key", apiKey)
                    .putString("elevenlabs_voice_id", voice.id)
                    .putString("selected_provider", "ELEVEN_LABS")
                    .apply()
            }
        }
        chatTtsEngine?.release()
        chatTtsEngine = TtsEngineManager(this)
    }

    private fun setupDrawerControls() {
        val drawer = drawerLayout ?: return

        findViewById<ImageButton>(R.id.btn_open_left_drawer)?.setOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.END)) {
                drawer.closeDrawer(GravityCompat.END)
            }
            drawer.openDrawer(GravityCompat.START)
        }

        findViewById<ImageButton>(R.id.btn_open_right_drawer)?.setOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START)
            }
            // La tuerca siempre abre la pantalla principal de configuración.
            showSettingsPage(PAGE_SETTINGS)
            drawer.openDrawer(GravityCompat.END)
        }
    }




    private fun setupRightDrawerActions() {
        btnOpenGgufDownloads?.setOnClickListener {
            showSettingsPage(PAGE_GGUF_DOWNLOAD)
        }

        btnOpenTtsDownloads?.setOnClickListener {
            showSettingsPage(PAGE_TTS_DOWNLOAD)
        }

        btnBackFromGgufDownload?.setOnClickListener {
            showSettingsPage(PAGE_SETTINGS)
        }

        btnBackFromTtsDownload?.setOnClickListener {
            showSettingsPage(PAGE_SETTINGS)
        }

        val backends = arrayOf("⚡ NPU (Hexagon)", "💻 CPU (NEON)", "🎮 GPU (Adreno)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backends)
        spinnerBackend?.adapter = adapter

        val savedIndex = backends.indexOf(getSavedBackend())
        if (savedIndex >= 0) spinnerBackend?.setSelection(savedIndex)

        spinnerBackend?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = parent?.getItemAtPosition(position)?.toString() ?: return
                if (selected != getSavedBackend()) {
                    Log.d(TAG, "═══════════════════════════════════════════════════")
                    Log.d(TAG, "🔄 Usuario cambió backend: $selected")
                    Log.d(TAG, "═══════════════════════════════════════════════════")
                    
                    saveBackend(selected)
                    applyBackendToEngine(selected)
                    
                    if (selected == "⚡ NPU (Hexagon)") {
                        // ✅ MEJORADO: Mensaje más claro sobre NPU
                        Toast.makeText(
                            this@MainActivity, 
                            "⚠️ NPU Hexagon aún en desarrollo.\n" +
                            "La inferencia se ejecutará en GPU (Adreno) o CPU como fallback.\n" +
                            "La integración completa de NPU requiere SDK QNN de Qualcomm.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (selected == "🎮 GPU (Adreno)") {
                        Toast.makeText(this@MainActivity, "🎮 GPU activada — el modelo se cargará en tu GPU Adreno (OpenCL)", Toast.LENGTH_LONG).show()
                            
                        
                    } else if (selected == "💻 CPU (NEON)") {
                        Toast.makeText(this@MainActivity, "💻 CPU activada — modo compatible universal", Toast.LENGTH_SHORT).show()
                    }
                    
                    if (isModelLoadedInMemory) {
                        reloadModelWithBackend(selected)
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnModelName?.setOnClickListener {
            showSelectModelDialog()
        }

        btnBackend?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Seleccionar Backend")
                .setItems(backends) { _, which ->
                    spinnerBackend?.setSelection(which)
                    Toast.makeText(this, "Backend seleccionado: ${backends[which]}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnAddGgufUrl?.setOnClickListener {
            val urlString = etGgufUrl?.text?.toString()?.trim() ?: ""
            if (urlString.isEmpty()) {
                Toast.makeText(this, "Introduce una URL GGUF o LiteRT-LM válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val slot = createDownloadSlot(DownloadType.GGUF)
            if (slot == null) {
                Toast.makeText(this, "No se pudo crear la tarjeta de descarga", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ggufSlots.add(slot)
            updateEmptyDownloadState(DownloadType.GGUF)
            startManagedDownload(
                slot = slot,
                url = urlString,
                fileName = if (
                    urlString.substringBefore('?').endsWith(".litertlm", ignoreCase = true)
                ) "model.litertlm" else "model.gguf",
                title = "Descargando Modelo de IA",
                onDone = { downloadedFile ->
                    setSelectedModelFile(downloadedFile)
                    checkStoredModelStatus()
                    autoLoadModel()
                }
            )
            etGgufUrl?.text?.clear()
        }

        btnAddTtsUrl?.setOnClickListener {
            val urlString = etTtsUrl?.text?.toString()?.trim() ?: ""
            if (urlString.isEmpty()) {
                Toast.makeText(this, "Introduce una URL TTS válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val slot = createDownloadSlot(DownloadType.TTS)
            if (slot == null) {
                Toast.makeText(this, "No se pudo crear la tarjeta de descarga", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ttsSlots.add(slot)
            updateEmptyDownloadState(DownloadType.TTS)
            startManagedDownload(
                slot = slot,
                url = urlString,
                fileName = "voces_tts.onnx",
                title = "Descargando Voces TTS",
                onDone = { downloadedFile ->
                    Toast.makeText(this, "Voces TTS descargadas: ${downloadedFile.name}", Toast.LENGTH_LONG).show()
                }
            )
            etTtsUrl?.text?.clear()
        }

        btnLoadModel?.setOnClickListener {
            loadModelIntoMemoryManual()
        }
        
        tvModelLoadedName?.setOnClickListener {
            loadModelIntoMemoryManual()
        }
    }

    private fun showSettingsPage(page: Int) {
        settingsPageFlipper?.displayedChild = page
    }

    private fun restoreSavedDownloads() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = try {
            JSONArray(prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]")
        } catch (t: Throwable) {
            Log.e(TAG, "Registros de descargas dañados; se ignorarán", t)
            JSONArray()
        }

        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val type = when (record.optString("type")) {
                DownloadType.GGUF.name -> DownloadType.GGUF
                DownloadType.TTS.name -> DownloadType.TTS
                else -> continue
            }
            val url = record.optString("url").trim()
            val path = record.optString("filePath").trim()
            if (url.isEmpty() || path.isEmpty()) continue

            val slot = createDownloadSlot(type) ?: continue
            slot.id = record.optString("id").ifBlank { slot.id }
            slot.url = url
            slot.title = record.optString(
                "title",
                if (type == DownloadType.GGUF) "Descargando Modelo de IA" else "Descargando Voces TTS"
            )
            slotsFor(type).add(slot)
            updateEmptyDownloadState(type)

            val file = File(path)
            val savedState = runCatching {
                ResumableDownloader.State.valueOf(
                    record.optString("state", ResumableDownloader.State.DOWNLOADING.name)
                )
            }.getOrDefault(ResumableDownloader.State.DOWNLOADING)

            startManagedDownload(
                slot = slot,
                url = url,
                fileName = file.name,
                title = slot.title,
                onDone = { downloadedFile ->
                    if (type == DownloadType.GGUF) {
                        setSelectedModelFile(downloadedFile)
                        checkStoredModelStatus()
                    }
                },
                existingFile = file,
                initialState = savedState
            )
        }
    }

    private fun persistDownloadRecord(
        slot: DownloadSlot,
        url: String = slot.url,
        file: File? = null,
        title: String = slot.title,
        state: ResumableDownloader.State
    ) {
        val targetFile = file ?: slot.file ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = try {
            JSONArray(prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }

        var existingIndex = -1
        for (index in 0 until records.length()) {
            if (records.optJSONObject(index)?.optString("id") == slot.id) {
                existingIndex = index
                break
            }
        }

        val record = JSONObject()
            .put("id", slot.id)
            .put("type", slot.type.name)
            .put("url", url)
            .put("filePath", targetFile.absolutePath)
            .put("title", title)
            .put("state", state.name)

        if (existingIndex >= 0) {
            records.put(existingIndex, record)
        } else {
            records.put(record)
        }
        prefs.edit().putString(KEY_DOWNLOADS, records.toString()).apply()
    }

    private fun removeDownloadRecord(slot: DownloadSlot) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldRecords = try {
            JSONArray(prefs.getString(KEY_DOWNLOADS, "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }
        val records = JSONArray()
        for (index in 0 until oldRecords.length()) {
            val record = oldRecords.optJSONObject(index) ?: continue
            if (record.optString("id") != slot.id) records.put(record)
        }
        prefs.edit().putString(KEY_DOWNLOADS, records.toString()).apply()
    }

    private fun createDownloadSlot(type: DownloadType): DownloadSlot? {
        val container = when (type) {
            DownloadType.GGUF -> ggufDownloadsContainer
            DownloadType.TTS -> ttsDownloadsContainer
        } ?: return null

        val itemView = layoutInflater.inflate(
            when (type) {
                DownloadType.GGUF -> R.layout.download_item_gguf
                DownloadType.TTS -> R.layout.download_item_tts
            },
            container,
            false
        )
        val ids = when (type) {
            DownloadType.GGUF -> intArrayOf(
                R.id.tv_download_title_gguf,
                R.id.tv_download_status_gguf,
                R.id.progress_download_gguf,
                R.id.btn_pause_gguf,
                R.id.btn_resume_gguf,
                R.id.btn_delete_gguf
            )
            DownloadType.TTS -> intArrayOf(
                R.id.tv_download_title_tts,
                R.id.tv_download_status_tts,
                R.id.progress_download_tts,
                R.id.btn_pause_tts,
                R.id.btn_resume_tts,
                R.id.btn_delete_tts
            )
        }
        val ui = DownloadUi(
            itemView,
            itemView.findViewById(ids[0]),
            itemView.findViewById(ids[1]),
            itemView.findViewById(ids[2]),
            itemView.findViewById(ids[3]),
            itemView.findViewById(ids[4]),
            itemView.findViewById(ids[5])
        )
        container.addView(itemView)
        return DownloadSlot(type, ui)
    }

    private fun slotsFor(type: DownloadType): MutableList<DownloadSlot> {
        return when (type) {
            DownloadType.GGUF -> ggufSlots
            DownloadType.TTS -> ttsSlots
        }
    }

    private fun updateEmptyDownloadState(type: DownloadType) {
        val slots = slotsFor(type)
        val emptyLabel = when (type) {
            DownloadType.GGUF -> tvEmptyGgufDownloads
            DownloadType.TTS -> tvEmptyTtsDownloads
        }
        emptyLabel?.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removeDownloadSlot(slot: DownloadSlot) {
        removeDownloadRecord(slot)
        slotsFor(slot.type).remove(slot)
        (slot.ui.panel.parent as? ViewGroup)?.removeView(slot.ui.panel)
        updateEmptyDownloadState(slot.type)
    }

    private fun createUniqueDownloadFile(targetDir: File, url: String, fallbackName: String): File {
        val urlName = runCatching {
            Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
        }.getOrDefault("")
        val rawName = if (urlName.isBlank()) fallbackName else urlName
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { fallbackName }
        var candidate = File(targetDir, safeName)
        var index = 2
        while (candidate.exists()) {
            val dotIndex = candidate.name.lastIndexOf('.')
            val stem = if (dotIndex > 0) candidate.name.substring(0, dotIndex) else candidate.name
            val extension = if (dotIndex > 0) candidate.name.substring(dotIndex) else ""
            candidate = File(targetDir, "${stem}_$index$extension")
            index++
        }
        return candidate
    }

    private fun startManagedDownload(
        slot: DownloadSlot,
        url: String,
        fileName: String,
        title: String,
        onDone: (File) -> Unit,
        existingFile: File? = null,
        initialState: ResumableDownloader.State = ResumableDownloader.State.DOWNLOADING
    ) {
        val ui = slot.ui

        if (slot.downloader?.state == ResumableDownloader.State.DOWNLOADING) {
            Toast.makeText(this, "Esta descarga ya está en curso", Toast.LENGTH_SHORT).show()
            return
        }

        slot.downloader?.cancel(true)

        val targetDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        if (!targetDir.exists()) targetDir.mkdirs()
        val file = existingFile ?: createUniqueDownloadFile(targetDir, url, fileName)
        slot.file = file
        slot.url = url
        slot.title = title

        ui.panel.visibility = View.VISIBLE
        ui.title.text = "$title: ${file.name}"
        ui.status.text = if (file.exists() && file.length() > 0) {
            "Descarga guardada — preparando reanudación..."
        } else {
            "Conectando con el servidor..."
        }
        ui.progress.progress = 0
        ui.btnPause.isEnabled = false
        ui.btnResume.isEnabled = false
        ui.btnDelete.isEnabled = true

        val downloader = ResumableDownloader()
        slot.downloader = downloader
        persistDownloadRecord(slot, state = initialState)

        downloader.listener = object : ResumableDownloader.Listener {
            override fun onProgress(downloaded: Long, total: Long, percent: Int) {
                if (!slotsFor(slot.type).contains(slot)) return
                runOnUiThread {
                    if (percent in 0..100) ui.progress.progress = percent
                    val mb = downloaded / (1024 * 1024)
                    val totalMb = total / (1024 * 1024)
                    ui.status.text = if (percent >= 0 && total > 0) {
                        "Descargando: $percent% ($mb / $totalMb MB)"
                    } else {
                        "Descargando: $mb MB"
                    }
                }
            }

            override fun onStateChanged(state: ResumableDownloader.State) {
                if (!slotsFor(slot.type).contains(slot)) return
                persistDownloadRecord(slot, state = state)
                runOnUiThread {
                    when (state) {
                        ResumableDownloader.State.DOWNLOADING -> {
                            ui.btnPause.isEnabled = true
                            ui.btnResume.isEnabled = false
                            ui.status.setTextColor(Color.parseColor("#808080"))
                        }
                        ResumableDownloader.State.PAUSED -> {
                            ui.btnPause.isEnabled = false
                            ui.btnResume.isEnabled = true
                            ui.status.setTextColor(Color.parseColor("#808080"))
                            ui.status.text = "⏸ En pausa — pulsa Reanudar para continuar"
                        }
                        ResumableDownloader.State.FINISHED -> {
                            ui.btnPause.isEnabled = false
                            ui.btnResume.isEnabled = false
                            ui.progress.progress = 100
                            ui.status.setTextColor(
                                Color.parseColor(
                                    if (slot.type == DownloadType.TTS) "#3D5AFE" else "#808080"
                                )
                            )
                            ui.status.text = "✅ Descarga completada. Pulsa 🗑 Eliminar para borrar el archivo."
                            onDone(file)
                        }
                        ResumableDownloader.State.FAILED -> {
                            ui.btnPause.isEnabled = false
                            ui.btnResume.isEnabled = false
                            ui.status.setTextColor(Color.parseColor("#808080"))
                            ui.status.text = "❌ Error de conectividad. Pulsa 🗑 Eliminar para cerrar."
                        }
                        ResumableDownloader.State.IDLE -> ui.panel.visibility = View.GONE
                    }
                }
            }
        }

        ui.btnPause.setOnClickListener { downloader.pause() }
        ui.btnResume.setOnClickListener { downloader.resume() }
        ui.btnDelete.setOnClickListener {
            slot.downloader?.cancel(true)
            deleteDownloadedModelIfMatches(file)
            slot.downloader = null
            removeDownloadSlot(slot)
            Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show()
        }

        when (initialState) {
            ResumableDownloader.State.FINISHED -> {
                if (file.exists()) {
                    ui.progress.progress = 100
                    ui.status.text = "✅ Descarga completada. Pulsa 🗑 Eliminar para borrar el archivo."
                    ui.btnPause.isEnabled = false
                    ui.btnResume.isEnabled = false
                } else {
                    downloader.start(url, file)
                }
            }
            ResumableDownloader.State.FAILED -> {
                ui.status.text = "❌ Error de conectividad. Pulsa 🗑 Eliminar para cerrar."
                ui.btnPause.isEnabled = false
                ui.btnResume.isEnabled = false
            }
            ResumableDownloader.State.PAUSED -> {
                // Se inicia y se pausa inmediatamente para reconstruir un
                // descargador reanudable sin perder el punto del archivo.
                downloader.start(url, file)
                downloader.pause()
            }
            else -> downloader.start(url, file)
        }
    }

    private fun deleteDownloadedModelIfMatches(file: File) {
        if (file.exists()) file.delete()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("selected_custom_model_path", null) == file.absolutePath) {
            prefs.edit().remove("selected_custom_model_path").apply()
            val nextModel = getActiveModelFile()
            if (nextModel != null) {
                checkStoredModelStatus()
                autoLoadModel()
            } else {
                if (isModelLoadedInMemory) releaseModelFromMemory()
                checkStoredModelStatus()
            }
        }
    }
    private fun autoLoadModel() {
        val modelFile = getActiveModelFile()
        if (modelFile != null && modelFile.exists()) {
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "🚀 Auto-carga en ejecución...")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            val backend = getSavedBackend()
            thread {
                val success = loadModelWithConfiguration(modelFile, backend)
                runOnUiThread {
                    isModelLoadedInMemory = success
                    updateLoadModelButton()
                    if (success) {
                        tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - Auto-activado]"
                        btnModelName?.text = "Nombre del modelo\n${modelFile.name} · Automático"
                        Toast.makeText(this, "✅ ${modelFile.name} activado automáticamente", Toast.LENGTH_LONG).show()
                        Log.d(TAG, "✅ Modelo cargado automáticamente en $backend")
                    } else {
                        tvModelLoadedName?.text = "Modelo: ${modelFile.name} (error de activación)"
                        Toast.makeText(this, "❌ Error al cargar modelo automáticamente", Toast.LENGTH_LONG).show()
                        Log.e(TAG, "❌ Error al cargar modelo automáticamente")
                    }
                }
            }
        }
    }

    private fun releaseModelFromMemory() {
        if (isModelLoadedInMemory) {
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "🛑 Liberando modelo de memoria...")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            thread {
                nativeReleaseModel()
                runBlocking { LiteRtLmEngineManager.release("main_activity") }
                runOnUiThread {
                    isModelLoadedInMemory = false
                    modelLoadedInBackground = false
                    updateLoadModelButton()
                    tvModelLoadedName?.text = "Modelo: Descargado (en espera)"
                    Toast.makeText(this, "Motor de IA desactivado", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "✅ Motor de IA liberado correctamente")
                }
            }
        }
    }

    private fun triggerAutomaticModelLoadOnFirstMessage(): Boolean {
        val modelFile = getActiveModelFile() ?: return false
        if (!modelFile.exists()) return false
        val backend = getSavedBackend()

        if (modelFile.extension.equals("litertlm", ignoreCase = true)) {
            if (LiteRtLmEngineManager.isLoaded(modelFile.absolutePath)) {
                isModelLoadedInMemory = true
                return true
            }
            val success = loadModelWithConfiguration(modelFile, backend)
            isModelLoadedInMemory = success
            runOnUiThread {
                updateLoadModelButton()
                if (success) {
                    tvModelLoadedName?.text =
                        "Modelo: ${modelFile.name} [${LiteRtLmEngineManager.engineInfo()}]"
                }
            }
            return success
        }

        if (nativeIsModelLoaded()) {
            isModelLoadedInMemory = true
            return true
        }
        isModelLoadedInMemory = false
        Log.d(TAG, "🎯 Carga automática antes de procesar el primer mensaje")
        val success = loadModelWithConfiguration(modelFile, backend)
        isModelLoadedInMemory = success
        runOnUiThread {
            updateLoadModelButton()
            if (success) {
                tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - Activo]"
            }
        }
        return success
    }

    private fun updateLoadModelButton() {
        btnLoadModel?.text = LanguageManager.text(
            this,
            if (isModelLoadedInMemory) "MODELO ACTIVO" else "CARGAR MODELO"
        )
        btnLoadModel?.isEnabled = !isModelLoadedInMemory
        btnLoadModel?.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isModelLoadedInMemory) "#C62828" else "#3D5AFE")
        )
    }

    private fun setupLanguageSelection() {
        val languageButtons = listOf(
            R.id.btnLanguageEs to "es",
            R.id.btnLanguageEn to "en",
            R.id.btnLanguageRu to "ru",
            R.id.btnLanguageZh to "zh",
            R.id.btnLanguageKo to "ko",
            R.id.btnLanguageJa to "ja",
            R.id.btnLanguagePt to "pt"
        )
        val selectedLanguage = LanguageManager.getSelectedLanguage(this)

        languageButtons.forEach { (buttonId, languageCode) ->
            val button = findViewById<Button>(buttonId)
            button.setTextColor(
                Color.parseColor(if (languageCode == selectedLanguage) "#3D5AFE" else "#FFFFFF")
            )
            button.setOnClickListener {
                if (languageCode != LanguageManager.getSelectedLanguage(this)) {
                    LanguageManager.setLocale(this, languageCode)
                    recreate()
                }
            }
        }
    }

    private fun getDownloadedModelFiles(): List<File> {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (storageDir != null && storageDir.exists()) {
            val files = storageDir.listFiles { _, name ->
                name.endsWith(".gguf", ignoreCase = true) ||
                    name.endsWith(".litertlm", ignoreCase = true)
            }
            if (files != null) {
                return files.sortedByDescending { it.lastModified() }
            }
        }
        return emptyList()
    }

    private fun getSelectedModelPath(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("selected_custom_model_path", null)
    }

    private fun setSelectedModelFile(file: File) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("selected_custom_model_path", file.absolutePath).apply()
    }

    private fun getActiveModelFile(): File? {
        val models = getDownloadedModelFiles()
        if (models.isEmpty()) return null

        val savedPath = getSelectedModelPath()
        if (!savedPath.isNullOrEmpty()) {
            val savedFile = File(savedPath)
            if (savedFile.exists()) {
                return savedFile
            }
        }

        val defaultModel = models.first()
        setSelectedModelFile(defaultModel)
        return defaultModel
    }

    private fun showSelectModelDialog() {
        val models = getDownloadedModelFiles()
        if (models.isEmpty()) {
            Toast.makeText(this, "No hay modelos GGUF o LiteRT-LM descargados.", Toast.LENGTH_SHORT).show()
            return
        }

        if (models.size == 1) {
            val onlyModel = models.first()
            setSelectedModelFile(onlyModel)
            checkStoredModelStatus()
            autoLoadModel()
            Toast.makeText(
                this,
                "${onlyModel.name} es el único modelo y queda activo automáticamente.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val modelNames = models.map { "${it.name} (${it.length() / (1024 * 1024)} MB)" }.toTypedArray()
        val currentActive = getActiveModelFile()
        val currentIndex = models.indexOfFirst { it.absolutePath == currentActive?.absolutePath }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Seleccionar Modelo Activo (Manual)")
            .setSingleChoiceItems(modelNames, currentIndex) { dialog, which ->
                val selected = models[which]
                setSelectedModelFile(selected)
                checkStoredModelStatus()
                autoLoadModel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadModelIntoMemoryManual() {
        val modelFile = getActiveModelFile()

        if (modelFile == null || !modelFile.exists()) {
            Toast.makeText(this, "No existe ningún modelo GGUF o LiteRT-LM válido.", Toast.LENGTH_LONG).show()
            return
        }

        val selectedBackend = spinnerBackend?.selectedItem?.toString() ?: getSavedBackend()
        saveBackend(selectedBackend)

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🚀 Carga manual de modelo")
        Log.d(TAG, "Modelo: ${modelFile.name}")
        Log.d(TAG, "Backend: $selectedBackend")
        Log.d(TAG, "═══════════════════════════════════════════════════")

        Toast.makeText(this, "Cargando modelo ${modelFile.name} usando $selectedBackend...", Toast.LENGTH_SHORT).show()

        thread {
            val success = loadModelWithConfiguration(modelFile, selectedBackend)
            runOnUiThread {
                isModelLoadedInMemory = success
                updateLoadModelButton()
                if (success) {
                    Toast.makeText(this, "✅ ¡Modelo inicializado en memoria correctamente con $selectedBackend!", Toast.LENGTH_LONG).show()
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$selectedBackend - Cargado]"
                    Log.d(TAG, "✅ Modelo cargado exitosamente en $selectedBackend")
                } else {
                    Toast.makeText(this, "❌ Fallo al inicializar el modelo en la capa nativa C++", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "❌ Error al cargar modelo en $selectedBackend")
                }
            }
        }
    }

    private fun setupAdvancedConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        seekContext?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvContextValue?.text = "$progress tok"
                prefs.edit().putInt("ai_context", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTemperature?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 100.0
                tvTemperatureValue?.text = String.format(Locale.US, "%.1f", temp)
                prefs.edit().putFloat("ai_temperature", temp.toFloat()).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTopK?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("ai_top_k", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTopP?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("ai_top_p", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPersonalityGeneral?.setOnClickListener {
            prefs.edit().putString("ai_personality", "General").apply()
            Toast.makeText(this, "Personalidad: Asistente General", Toast.LENGTH_SHORT).show()
        }

        btnPersonalityProgrammer?.setOnClickListener {
            prefs.edit().putString("ai_personality", "Programador").apply()
            Toast.makeText(this, "Personalidad: Programador", Toast.LENGTH_SHORT).show()
        }

        btnPersonalityCreative?.setOnClickListener {
            prefs.edit().putString("ai_personality", "Creativo").apply()
            Toast.makeText(this, "Personalidad: Creativo", Toast.LENGTH_SHORT).show()
        }
        
        btnSaveCustomPersonality?.setOnClickListener {
            val customPersonality = etPersonalityCustom?.text?.toString()?.trim() ?: ""
            if (customPersonality.isEmpty()) {
                Toast.makeText(this, "Escribe una personalidad personalizada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("custom_personality", customPersonality).apply()
            Toast.makeText(this, "Personalidad personalizada guardada", Toast.LENGTH_SHORT).show()
        }
        
        btnDeleteCustomPersonality?.setOnClickListener {
            etPersonalityCustom?.text?.clear()
            prefs.edit().remove("custom_personality").apply()
            Toast.makeText(this, "Personalidad personalizada eliminada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupElevenLabsActions() {
        btnSaveElevenKey?.setOnClickListener {
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Introduce tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("eleven_api_key", apiKey).apply()
            getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("elevenlabs_api_key", apiKey)
                .apply()
            Toast.makeText(this, "¡API Key de ElevenLabs guardada con éxito!", Toast.LENGTH_SHORT).show()
        }

        btnElevenRecord?.setOnClickListener {
            if (!isRecordingVoice) {
                startAudioRecording()
            } else {
                stopAudioRecording()
            }
        }

        btnElevenTest?.setOnClickListener {
            if (recordedAudioFile == null || !recordedAudioFile!!.exists()) {
                Toast.makeText(this, "Primero graba una muestra de voz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(recordedAudioFile!!.absolutePath)
                    prepare()
                    start()
                }
                Toast.makeText(this, "Reproduciendo muestra de voz grabada...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al reproducir audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnElevenUse?.setOnClickListener {
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Falta tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (recordedAudioFile == null || !recordedAudioFile!!.exists()) {
                Toast.makeText(this, "Falta la muestra de audio grabada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val progressDialog = ProgressDialog(this).apply {
                setTitle("ElevenLabs Cloud")
                setMessage("Conectando con servidores y clonando voz...")
                setCancelable(false)
                show()
            }

            thread {
                val clonedVoiceId = uploadVoiceToElevenLabs(apiKey, "CumulusCustomVoice", recordedAudioFile!!)
                runOnUiThread {
                    progressDialog.dismiss()
                    if (clonedVoiceId != null) {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString("eleven_active_voice_id", clonedVoiceId).apply()
                        getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("elevenlabs_api_key", apiKey)
                            .putString("elevenlabs_voice_id", clonedVoiceId)
                            .putString("selected_provider", "ELEVEN_LABS")
                            .apply()
                        Toast.makeText(this, "¡Voz clonada y vinculada correctamente!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Error de conexión o API Key inválida con ElevenLabs", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnElevenDelete?.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove("eleven_active_voice_id").apply()
            getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
                .edit()
                .remove("elevenlabs_voice_id")
                .apply()
            recordedAudioFile?.delete()
            recordedAudioFile = null
            Toast.makeText(this, "Configuración de ElevenLabs restablecida", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAudioRecording() {
        val audioDir = externalCacheDir
        if (audioDir != null && !audioDir.exists()) {
            audioDir.mkdirs()
        }
        recordedAudioFile = File(audioDir, "eleven_sample_voice.3gp")

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(recordedAudioFile!!.absolutePath)
            try {
                prepare()
                start()
                isRecordingVoice = true
                btnElevenRecord?.text = "Detener Grabación"
                Toast.makeText(this@MainActivity, "Grabando voz para clonación...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar grabadora: ${e.message}")
                Toast.makeText(this@MainActivity, "No se pudo iniciar el micrófono", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopAudioRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingVoice = false
            btnElevenRecord?.text = "Grabar Muestra"
            Toast.makeText(this, "¡Muestra de voz guardada con éxito!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener grabadora: ${e.message}")
        }
    }

    private fun uploadVoiceToElevenLabs(apiKey: String, voiceName: String, audioFile: File): String? {
        val boundary = "Boundary-" + System.currentTimeMillis()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        try {
            val url = URL("https://api.elevenlabs.io/v1/voices/add")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val outputStream = BufferedOutputStream(connection.outputStream)
            val writer = outputStream.writer(Charsets.UTF_8)

            writer.append(twoHyphens).append(boundary).append(lineEnd)
            writer.append("Content-Disposition: form-data; name=\"name\"").append(lineEnd)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineEnd)
            writer.append(lineEnd).append(voiceName).append(lineEnd)

            writer.append(twoHyphens).append(boundary).append(lineEnd)
            writer.append("Content-Disposition: form-data; name=\"files\"; filename=\"${audioFile.name}\"").append(lineEnd)
            writer.append("Content-Type: audio/3gpp").append(lineEnd)
            writer.append(lineEnd)
            writer.flush()

            val fileInputStream = FileInputStream(audioFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            fileInputStream.close()
            outputStream.flush()

            writer.append(lineEnd)
            writer.append(twoHyphens).append(boundary).append(twoHyphens).append(lineEnd)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val responseString = reader.readText()
                reader.close()

                val jsonResponse = JSONObject(responseString)
                return jsonResponse.optString("voice_id", null)
            } else {
                Log.e(TAG, "Error en servidor ElevenLabs: Código $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción conectando con ElevenLabs: ${e.message}")
        }
        return null
    }

    private fun checkStoredModelStatus() {
        val activeModel = getActiveModelFile()
        if (activeModel != null && activeModel.exists()) {
            tvModelLoadedName?.text = "Modelo local: ${activeModel.name} (${activeModel.length() / (1024 * 1024)} MB)"
            btnModelName?.text = "Nombre del modelo\n${activeModel.name} · Automático"
            logLoadedModelName(activeModel.name)
        } else {
            tvModelLoadedName?.text = "Modelo: Ninguno descargado"
            btnModelName?.text = "Nombre del modelo\nAutomático · esperando GGUF"
        }
    }

    private fun verifyAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = drawerLayout
                when {
                    drawer != null && drawer.isDrawerOpen(GravityCompat.START) -> {
                        drawer.closeDrawer(GravityCompat.START)
                    }
                    drawer != null && drawer.isDrawerOpen(GravityCompat.END) -> {
                        if (settingsPageFlipper?.displayedChild != PAGE_SETTINGS) {
                            showSettingsPage(PAGE_SETTINGS)
                        } else {
                            drawer.closeDrawer(GravityCompat.END)
                        }
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Las descargas no se cancelan al destruir la Activity. Su metadata
        // y archivo parcial quedan guardados para continuar al volver a entrar.

        voiceBrowserTts?.shutdown()
        voiceBrowserTts = null
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        chatTtsEngine?.release()
        chatTtsEngine = null

        thread {
            try {
                runBlocking { LiteRtLmEngineManager.release("main_activity") }
                if (isModelLoadedInMemory) nativeReleaseModel()
                isModelLoadedInMemory = false
                Log.d(TAG, "✅ Motores liberados al cerrar la actividad principal.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al liberar motores en onDestroy: ${e.message}")
            }
        }
    }
}


private fun logLoadedModelName(name: String) {
    Log.d("ModelStatus", "Modelo detectado automáticamente: $name")
}
