package com.paulrj.cumulusia

import android.Manifest
import android.provider.MediaStore
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
    private var switchAutoLoad: com.google.android.material.switchmaterial.SwitchMaterial? = null
    private var btnModelName: MaterialButton? = null
    private var btnMmproj: MaterialButton? = null
    private var btnBackend: MaterialButton? = null
    
    private var rvChatMessages: RecyclerView? = null
    private var etChatInput: EditText? = null
    private var chatComposer: ConstraintLayout? = null
    private var btnSend: ImageButton? = null
    private var btnMic: ImageButton? = null
    private var btnAttach: ImageButton? = null
    private var btnChatMode: MaterialButton? = null
    // 📎 Adjuntos pendientes: hasta 10 imágenes, 10 archivos y 5 videos
    private val pendingAttachments = mutableListOf<File>()
    private val pendingAttachment: File? get() = pendingAttachments.firstOrNull()
    private var attachmentsScroll: View? = null
    private var attachmentsStrip: LinearLayout? = null
    private var cameraPhotoUri: Uri? = null
    private var btnVoicePlayPause: ImageButton? = null
    private var tvAiName: TextView? = null
    private var isVocalResponseActive: Boolean = false
    // 🎙️ Conversación voz a voz (botón dentro del +)
    @Volatile private var isVoiceConversationActive: Boolean = false
    @Volatile private var isAiBusy: Boolean = false
    @Volatile private var isTtsSpeaking: Boolean = false
    private var currentSpokenReply: String = ""
    private var queuedVoiceText: String? = null
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
    private var btnElevenSave: MaterialButton? = null

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedAudioFile: File? = null
    private var isRecordingVoice: Boolean = false
    @Volatile private var lastElevenError: String? = null
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
    
    private var etPersonalityCustom: EditText? = null
    private var btnSaveCustomPersonality: MaterialButton? = null
    private var btnDeleteCustomPersonality: MaterialButton? = null

    private var isAppInForeground: Boolean = false
    private var modelLoadedInBackground: Boolean = false
    @Volatile private var isModelLoading: Boolean = false
    private var restoringSwitchState: Boolean = false

    companion object {
        private const val TAG = "CumulusMainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "CumulusAppPermissions"
        private const val PICK_IMAGE_REQUEST = 1002
        private const val PICK_ATTACHMENT_REQUEST = 1003
        private const val TAKE_PHOTO_REQUEST = 1004
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
    private external fun nativeSetMmproj(path: String)
    private external fun nativeHasVision(): Boolean
    private external fun nativeRunInferenceWithImage(prompt: String, imagePath: String): String
    private external fun nativeGetMediaCaps(): String
    private external fun nativeSetChatMode(systemPrompt: String, maxTokens: Int)
    private external fun nativeRunInferenceWithMedia(prompt: String, mediaPath: String): String
    private external fun nativeRunInferenceWithMediaList(prompt: String, mediaPaths: Array<String>): String

    // Texto que indica qué entiende el modelo cargado: imágenes y/o audio
    private fun mediaCapsLabel(): String {
        val caps = runCatching { nativeGetMediaCaps() }.getOrDefault("")
        if (caps.isBlank()) return ""
        val parts = mutableListOf<String>()
        if (caps.contains("vision")) parts.add("👁️ Imágenes")
        if (caps.contains("audio")) parts.add("🎧 Audio")
        return "\nMultimodal: " + parts.joinToString(" + ")
    }
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
        // Automático: se activa al abrir la app.
        // Manual: si lo dejaste activo, se vuelve a activar solo.
        if (!isModelLoadedInMemory && !isModelLoading &&
            (isAutoLoadEnabled() || isManualModelPinned())
        ) {
            autoLoadModel()
        }
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        if (isVoiceConversationActive) setVoiceConversation(false)

        // Solo en modo automático se desactiva al salir de la app.
        // En manual el modelo sigue activo hasta que pulses el botón.
        if (isAutoLoadEnabled() && isModelLoadedInMemory && !modelLoadedInBackground) {
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
        switchAutoLoad = findViewById(R.id.switch_auto_load)
        switchAutoLoad?.isChecked = isAutoLoadEnabled()
        updateAutoLoadSwitchText()
        switchAutoLoad?.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("auto_load_model", checked).apply()
            updateAutoLoadSwitchText()
            setManualModelPinned(!checked && isModelLoadedInMemory)
            updateLoadModelButton()
            if (checked) {
                Toast.makeText(this, "Carga automática activada", Toast.LENGTH_SHORT).show()
                if (!isModelLoadedInMemory) autoLoadModel()
            } else {
                Toast.makeText(this, "Carga manual: usa el botón Cargar modelo", Toast.LENGTH_SHORT).show()
            }
        }
        btnModelName = findViewById(R.id.btn_model_name)
        btnMmproj = findViewById(R.id.btn_mmproj)
        btnMmproj?.setOnClickListener { showSelectMmprojDialog() }
        updateMmprojButton()
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
        btnElevenSave = findViewById(R.id.btn_eleven_save)

        rvChatMessages = findViewById(R.id.rv_chat_messages)
        etChatInput = findViewById(R.id.et_chat_input)
        chatComposer = findViewById(R.id.chat_composer)
        btnSend = findViewById(R.id.btn_send)
        btnMic = findViewById(R.id.btn_mic)
        btnAttach = findViewById(R.id.btn_attach)
        btnChatMode = findViewById(R.id.btn_chat_mode)
        attachmentsScroll = findViewById(R.id.attachments_scroll)
        attachmentsStrip = findViewById(R.id.attachments_strip)
        setupChatModeAndAttach()
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
        
        etPersonalityCustom = findViewById(R.id.et_personality_custom)
        btnSaveCustomPersonality = findViewById(R.id.btn_save_custom_personality)
        btnDeleteCustomPersonality = findViewById(R.id.btn_delete_custom_personality)

        chatAdapter = ChatAdapter(chatMessages) { bubbleImageUri }
        rvChatMessages?.layoutManager = LinearLayoutManager(this)
        rvChatMessages?.adapter = chatAdapter
        setupThemeSwitch()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString("eleven_api_key", "")
        if (!savedKey.isNullOrEmpty()) {
            etElevenApiKey?.setText(savedKey)
        }
        
        val savedAiName = prefs.getString("ai_name", "Cumulus IA")
        tvAiName?.text = savedAiName?.take(10)
        // Muestra el nombre personalizado en su campo para poder editarlo o borrarlo
        if (savedAiName != null && savedAiName != "Cumulus IA") etAiNameCustom?.setText(savedAiName.take(10))
        
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
            val attachments = pendingAttachments.toList()
            if (message.isEmpty() && attachments.isEmpty()) {
                Toast.makeText(this, "Escribe un mensaje", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // El mensaje del chat muestra las miniaturas de lo que enviaste
            chatMessages.add(
                ChatMessage(
                    text = message,
                    isUser = true,
                    sender = "Tú",
                    tokens = "",
                    attachments = attachments.map { it.absolutePath }
                )
            )
            chatAdapter?.notifyItemInserted(chatMessages.size - 1)
            rvChatMessages?.scrollToPosition(chatMessages.size - 1)
            etChatInput?.text?.clear()
            // Se quitan de la barra, pero los archivos se conservan para el chat
            clearAttachments(deleteFiles = false)

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
            isAiBusy = true
            thread {
                val start = System.currentTimeMillis()
                try {
                    val reply = generateAiResponse(message, selectedBackend, attachments)
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
                        isAiBusy = false
                        if (isVoiceConversationActive) {
                            // Voz a voz: habla la respuesta y luego vuelve a escuchar
                            speakThenListen(reply.text)
                        } else if (isVocalResponseActive && reply.text.isNotBlank()) {
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
                        isAiBusy = false
                        sendQueuedVoiceIfAny()
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
            if (isVoiceConversationActive) {
                setVoiceConversation(false)
                Toast.makeText(this, "Conversación por voz desactivada", Toast.LENGTH_SHORT).show()
            } else {
                toggleChatVoiceRecognition()
            }
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
                    if (isVoiceConversationActive) {
                        etChatInput?.hint = "🎙️ Te escucho..."
                    } else {
                        Toast.makeText(this@MainActivity, "Escuchando...", Toast.LENGTH_SHORT).show()
                    }
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
                    if (isVoiceConversationActive) {
                        // El micrófono se mantiene encendido: ante cualquier corte, vuelve a escuchar
                        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500L else 150L
                            chatComposer?.postDelayed({ startConversationListening() }, wait)
                        } else {
                            setVoiceConversation(false)
                            Toast.makeText(
                                this@MainActivity,
                                "Conversación por voz detenida (error $error)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }
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
                    if (isVoiceConversationActive) {
                        handleConversationSpeech(spokenText)
                        return
                    }
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
    // ========================================================================
    // 🎙️ CONVERSACIÓN VOZ A VOZ: escuchar → la IA responde hablando → escuchar
    // ========================================================================
    private fun setVoiceConversation(active: Boolean) {
        if (active) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE
                )
                Toast.makeText(this, "Da permiso de micrófono y vuelve a activarlo", Toast.LENGTH_LONG).show()
                return
            }
            if (speechRecognizer == null) {
                Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
                return
            }
            if (chatTtsEngine == null) chatTtsEngine = TtsEngineManager(this)
            isVoiceConversationActive = true
            btnMic?.setColorFilter(Color.parseColor("#00E676"))
            Toast.makeText(this, "🎙️ Conversación por voz activada. ¡Habla!", Toast.LENGTH_SHORT).show()
            startConversationListening()
        } else {
            isVoiceConversationActive = false
            isTtsSpeaking = false
            queuedVoiceText = null
            btnMic?.setColorFilter(Color.parseColor("#808080"))
            etChatInput?.hint = "Pregúntame..."
            if (isListeningForChat) {
                speechRecognizer?.cancel()
                isListeningForChat = false
            }
            chatTtsEngine?.stop()
        }
    }

    // Lo que dices mientras el micrófono está siempre encendido
    private fun handleConversationSpeech(spokenText: String) {
        if (spokenText.isNotEmpty()) {
            if (isTtsSpeaking && looksLikeEcho(spokenText)) {
                // Es la propia voz de la IA que entró por el micrófono: se ignora
                Log.d(TAG, "🎙️ Eco ignorado: $spokenText")
                etChatInput?.text?.clear()
            } else {
                if (isTtsSpeaking) {
                    // Interrumpes a la IA: deja de hablar y te escucha
                    chatTtsEngine?.stop()
                    isTtsSpeaking = false
                }
                if (isAiBusy) {
                    // La IA sigue pensando: se envía apenas termine
                    queuedVoiceText = listOfNotNull(queuedVoiceText, spokenText).joinToString(" ")
                    etChatInput?.text?.clear()
                } else {
                    etChatInput?.setText(spokenText)
                    btnSend?.performClick()
                }
            }
        }
        // El micrófono sigue encendido
        chatComposer?.postDelayed({ startConversationListening() }, 150)
    }

    private fun looksLikeEcho(heard: String): Boolean {
        val reply = currentSpokenReply.lowercase(Locale.getDefault())
        val words = heard.lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return true
        val matches = words.count { reply.contains(it) }
        return matches >= (words.size * 0.6)
    }

    private fun sendQueuedVoiceIfAny() {
        val queued = queuedVoiceText ?: return
        queuedVoiceText = null
        if (isVoiceConversationActive && queued.isNotBlank()) {
            etChatInput?.setText(queued)
            btnSend?.performClick()
        }
    }

    private fun startConversationListening() {
        if (!isVoiceConversationActive || isListeningForChat) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LanguageManager.getSelectedLanguage(this@MainActivity))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { Log.e(TAG, "No se pudo escuchar: ${it.message}") }
    }

    // Quita código y símbolos para que la voz suene natural
    private fun textForSpeech(text: String): String =
        text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " Te dejé el código en la pantalla. ")
            .replace(Regex("[*#`_>|]+"), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun speakThenListen(replyText: String) {
        if (!isVoiceConversationActive) return
        val spoken = textForSpeech(replyText)
        val engine = chatTtsEngine
        if (spoken.isBlank() || engine == null) {
            sendQueuedVoiceIfAny()
            startConversationListening()
            return
        }
        currentSpokenReply = spoken
        isTtsSpeaking = true
        etChatInput?.hint = "🔊 Respondiendo... (🎙️ micrófono encendido)"
        engine.speak(spoken) {
            runOnUiThread {
                isTtsSpeaking = false
                if (isVoiceConversationActive) {
                    etChatInput?.hint = "🎙️ Te escucho..."
                    sendQueuedVoiceIfAny()
                    startConversationListening()
                }
            }
        }
        // El micrófono no se apaga mientras la IA habla
        startConversationListening()
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
        // El texto ahora siempre va arriba y los botones abajo,
        // así que solo se guarda el estado.
        isChatInputExpanded = expanded
    }

    @Suppress("unused")
    private fun updateChatComposerExpansionLegacy(expanded: Boolean) {
        val composer = chatComposer ?: return

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

    // ========================================================================
    // 🌓 MODO CLARO / OSCURO
    // La app usa colores fijos oscuros; el modo claro los cambia en pantalla
    // por sus equivalentes claros (y al revés), sin reiniciar la app.
    // ========================================================================
    private val bgDarkToLight = mapOf(
        "#0D0D0D" to "#F2F2F7", "#1C1C1E" to "#FFFFFF", "#2A2A2A" to "#E5E5EA",
        "#2C2C2E" to "#E8E8ED", "#3A3A3C" to "#D1D1D6", "#1E2A5A" to "#DDE3FF", "#1E1E1E" to "#FAFAFA"
    ).map { (d, l) -> Color.parseColor(d) to Color.parseColor(l) }.toMap()
    private val textDarkToLight = mapOf(
        "#FFFFFF" to "#1C1C1E", "#D0D0D0" to "#3A3A3C", "#B0B0B0" to "#555555", "#8C9EFF" to "#2F45C8"
    ).map { (d, l) -> Color.parseColor(d) to Color.parseColor(l) }.toMap()
    private val bgLightToDark = bgDarkToLight.entries.associate { (k, v) -> v to k }
    private val textLightToDark = textDarkToLight.entries.associate { (k, v) -> v to k }
    private val accentColors = setOf(Color.parseColor("#3D5AFE"), Color.parseColor("#C62828"), Color.parseColor("#00E676"))
    private var themeObserverInstalled = false

    private fun isLightMode(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("light_mode", false)

    private fun setupThemeSwitch() {
        val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_theme)
        val light = isLightMode()
        sw?.isChecked = !light
        sw?.text = if (light) "☀️ Modo claro" else "🌙 Modo oscuro"
        sw?.setOnCheckedChangeListener { view, darkChecked ->
            val wasLight = isLightMode()
            val nowLight = !darkChecked
            if (wasLight == nowLight) return@setOnCheckedChangeListener
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("light_mode", nowLight).apply()
            (view as? TextView)?.text = if (nowLight) "☀️ Modo claro" else "🌙 Modo oscuro"
            applyThemeToScreen(nowLight)
        }
        installThemeObserver()
        installThemeLayoutListener()
        installEdgeToEdge()
        installSectionBoxes()
        if (light) applyThemeToScreen(true) else applySystemBars(false)
    }

    // Los mensajes del chat se crean después: se recolorean al aparecer
    private fun installThemeObserver() {
        if (themeObserverInstalled) return
        val rv = rvChatMessages ?: return
        themeObserverInstalled = true
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (isLightMode()) recolorTree(view, true)
            }
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
        chatAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshChatColors()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refreshChatColors()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = refreshChatColors()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshChatColors()
        })
    }

    private fun refreshChatColors() {
        if (!isLightMode()) return
        rvChatMessages?.post {
            val rv = rvChatMessages ?: return@post
            for (i in 0 until rv.childCount) recolorTree(rv.getChildAt(i), true)
        }
    }

    private fun applyThemeToScreen(light: Boolean) {
        val root = window.decorView
        recolorTree(root, light)
        applySystemBars(light)
        // Al volver a oscuro, los mensajes se redibujan con sus colores originales
        if (!light) chatAdapter?.notifyDataSetChanged()
    }

    // ========================================================================
    // 📱 BARRAS DEL SISTEMA (hora/batería arriba y la línea de gestos abajo)
    // La app se dibuja detrás de las barras y les pone su mismo color de fondo.
    // ========================================================================
    private var edgeToEdgeInstalled = false

    private fun installEdgeToEdge() {
        if (edgeToEdgeInstalled) return
        edgeToEdgeInstalled = true
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false   // sin franja gris en la línea de gestos
            window.isStatusBarContrastEnforced = false
        }
        // Se aplica al contenedor principal (el DrawerLayout ignora el padding,
        // por eso antes la barra del chat quedaba tapada por el teclado)
        val root = findViewById<View>(android.R.id.content) ?: return
        val drawer = findViewById<android.view.ViewGroup>(R.id.drawer_layout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, 0)
            // Cada parte recibe su espacio por separado: la pantalla del chat y
            // los dos paneles laterales (así ninguno queda bajo la hora/batería)
            if (drawer != null) {
                for (i in 0 until drawer.childCount) {
                    val child = drawer.getChildAt(i)
                    @Suppress("UNCHECKED_CAST")
                    val orig = (child.getTag(R.id.drawer_layout) as? IntArray)
                        ?: intArrayOf(child.paddingLeft, child.paddingTop, child.paddingRight, child.paddingBottom)
                            .also { child.setTag(R.id.drawer_layout, it) }
                    val isMainScreen = i == 0
                    // Pantalla del chat: abajo deja lugar al teclado. Paneles: a la línea de gestos.
                    val bottom = if (isMainScreen) maxOf(bars.bottom, ime.bottom) else bars.bottom
                    child.setPadding(orig[0], orig[1] + bars.top, orig[2], orig[3] + bottom)
                }
            }
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun applySystemBars(light: Boolean) {
        val barColor = Color.parseColor(if (light) "#F2F2F7" else "#0D0D0D")
        // Fondo detrás de las barras = color de la app
        findViewById<View>(R.id.drawer_layout)?.setBackgroundColor(barColor)
        findViewById<View>(android.R.id.content)?.setBackgroundColor(barColor)
        window.decorView.setBackgroundColor(barColor)
        @Suppress("DEPRECATION")
        window.statusBarColor = barColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = barColor
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light       // íconos oscuros en modo claro
            isAppearanceLightNavigationBars = light
        }
    }

    // ========================================================================
    // 🟦 RECUADROS AZULES: separan cada sección de los paneles laterales
    // ========================================================================
    private var sectionBoxesInstalled = false

    private fun installSectionBoxes() {
        if (sectionBoxesInstalled) return
        sectionBoxesInstalled = true
        val drawer = findViewById<android.view.ViewGroup>(R.id.drawer_layout) ?: return
        // Panel izquierdo (Servicios y control)
        for (i in 1 until drawer.childCount) {
            val panel = drawer.getChildAt(i)
            if (panel is android.widget.ScrollView) (panel.getChildAt(0) as? LinearLayout)?.let { boxSections(it) }
        }
        // Panel derecho: página de Configuración principal
        val flipper = findViewById<android.widget.ViewFlipper>(R.id.settings_page_flipper)
        ((flipper?.getChildAt(0) as? android.widget.ScrollView)?.getChildAt(0) as? LinearLayout)?.let { boxSections(it) }
    }

    private val sectionBlue = Color.parseColor("#3D5AFE")

    private fun isSectionTitle(v: View): Boolean =
        v is TextView && v !is Button && v !is EditText &&
            v !is androidx.appcompat.widget.SwitchCompat &&
            v.currentTextColor == sectionBlue && v.text.isNotBlank() && v.text.toString() == v.text.toString().uppercase()

    // Botones que van en su propio recuadro aunque no tengan título
    private val standaloneSectionIds = setOf(R.id.btn_choose_voices)

    private fun boxSections(container: LinearLayout) {
        val dp = resources.displayMetrics.density
        val children = (0 until container.childCount).map { container.getChildAt(it) }
        val groups = mutableListOf<MutableList<View>>()
        var current: MutableList<View>? = null
        for ((idx, child) in children.withIndex()) {
            // El encabezado del panel ("SERVICIOS Y CONTROL") va fuera de los recuadros:
            // es el primer título y le sigue directamente otro título
            if (idx == 0 && isSectionTitle(child) && children.getOrNull(1)?.let { isSectionTitle(it) } == true) {
                continue
            }
            val isTitle = isSectionTitle(child)
            val standalone = child.id in standaloneSectionIds
            // Títulos que no son azules (ej. "SERVICIOS Y CONTROL") quedan fuera de los recuadros
            val isHeader = child is TextView && child !is Button && child !is EditText &&
                child !is androidx.appcompat.widget.SwitchCompat && !isTitle &&
                child.currentTextColor != Color.WHITE && current == null && groups.isEmpty() &&
                child.text.toString() == child.text.toString().uppercase()
            when {
                isHeader -> { current = null }
                isTitle || standalone -> {
                    current = mutableListOf(child); groups.add(current)
                    if (standalone) { current = null }
                }
                else -> {
                    if (current == null) { current = mutableListOf(); groups.add(current) }
                    current.add(child)
                }
            }
        }
        for (group in groups) {
            if (group.isEmpty()) continue
            val index = container.indexOfChild(group.first())
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setStroke((2 * dp).toInt(), sectionBlue)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * dp).toInt() }
            }
            group.forEach { container.removeView(it) }
            group.forEachIndexed { n, v ->
                (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    if (n == 0) lp.topMargin = 0
                    if (n == group.lastIndex) lp.bottomMargin = (8 * dp).toInt()
                }
                box.addView(v)
            }
            container.addView(box, index)
        }
    }

    // Color real del fondo (estado "habilitado"), sirve también para MaterialButton
    private fun tintColorOf(view: View): Int? {
        val csl = view.backgroundTintList ?: return null
        return csl.getColorForState(intArrayOf(android.R.attr.state_enabled), csl.defaultColor)
    }

    private var themeLayoutListenerInstalled = false

    // Vuelve a pintar cuando aparecen vistas nuevas (descargas, botones que cambian, etc.)
    private fun installThemeLayoutListener() {
        if (themeLayoutListenerInstalled) return
        themeLayoutListenerInstalled = true
        var pending = false
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isLightMode() || pending) return@addOnGlobalLayoutListener
            pending = true
            window.decorView.postDelayed({
                pending = false
                if (isLightMode()) recolorTree(window.decorView, true)
            }, 120)
        }
    }

    private fun recolorTree(view: View, light: Boolean) {
        val bgMap = if (light) bgDarkToLight else bgLightToDark
        val textMap = if (light) textDarkToLight else textLightToDark

        // Fondo
        when (val bg = view.background) {
            is android.graphics.drawable.ColorDrawable -> bgMap[bg.color]?.let { view.setBackgroundColor(it) }
            is android.graphics.drawable.GradientDrawable -> {
                val c = bg.color?.defaultColor
                if (c != null && view.backgroundTintList == null) {
                    bgMap[c]?.let { bg.mutate(); (bg as android.graphics.drawable.GradientDrawable).setColor(it) }
                }
            }
            else -> Unit
        }
        tintColorOf(view)?.let { c ->
            bgMap[c]?.let { view.backgroundTintList = android.content.res.ColorStateList.valueOf(it) }
        }

        // Texto (los botones de color azul/rojo mantienen su texto blanco)
        if (view is TextView) {
            val tint = tintColorOf(view)
            val onAccent = tint != null && tint in accentColors
            if (!onAccent) {
                textMap[view.currentTextColor]?.let { view.setTextColor(it) }
            }
        }

        // Íconos
        if (view is ImageView && view.id != R.id.btn_send && view.id != R.id.iv_ai_avatar) {
            view.imageTintList?.defaultColor?.let { c ->
                textMap[c]?.let { view.imageTintList = android.content.res.ColorStateList.valueOf(it) }
            }
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) recolorTree(view.getChildAt(i), light)
        }
    }

    // ========================================================================
    // 🧭 MODOS DE CHAT (botón largo de abajo)
    // ========================================================================
    private data class ChatMode(
        val key: String,
        val label: String,
        val systemPrompt: String,
        val maxTokens: Int
    )

    private val chatModes = listOf(
        ChatMode(
            "solo_chat", "Solo chat",
            "Eres Cumulus IA, un asistente útil, claro y conversacional. " +
                "Responde en el idioma del usuario de forma breve y natural.",
            256
        ),
        ChatMode(
            "codigo", "Ingeniero de código",
            "Eres Cumulus IA en modo Ingeniero de código: un programador experto. " +
                "Escribe código correcto, completo y listo para usar, dentro de bloques ``` con el lenguaje indicado. " +
                "Explica brevemente qué hace y cómo usarlo. Si hay un error, explica la causa y da la corrección. " +
                "Responde en el idioma del usuario.",
            1024
        ),
        ChatMode(
            "general", "General",
            "Eres Cumulus IA, un asistente general experto en muchos temas. " +
                "Da respuestas completas, ordenadas y precisas, con ejemplos cuando ayuden. " +
                "Responde en el idioma del usuario.",
            512
        ),
        ChatMode(
            "estudio", "Estudio",
            "Eres Cumulus IA en modo Estudio: un profesor paciente. " +
                "Explica paso a paso con palabras sencillas, usa ejemplos, resume las ideas clave al final " +
                "y, si sirve, propone una pregunta corta para practicar. Responde en el idioma del usuario.",
            768
        ),
        ChatMode(
            "traduccion", "Traducción",
            "Eres Cumulus IA en modo Traducción: un traductor profesional. " +
                "Traduce el texto del usuario de forma fiel y natural. Si el usuario indica un idioma de destino, usa ese; " +
                "si el texto está en español, tradúcelo al inglés; si está en otro idioma, tradúcelo al español. " +
                "Responde solo con la traducción, sin explicaciones.",
            512
        )
    )

    private fun currentChatMode(): ChatMode {
        val key = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("chat_mode", "solo_chat")
        return chatModes.firstOrNull { it.key == key } ?: chatModes.first()
    }

    private fun setupChatModeAndAttach() {
        btnChatMode?.text = "${currentChatMode().label} ▴"
        btnChatMode?.setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(this, anchor, android.view.Gravity.TOP)
            // Orden igual al dibujo: de arriba hacia abajo
            chatModes.reversed().forEachIndexed { index, mode ->
                val title = if (mode.key == currentChatMode().key) "✓ ${mode.label}" else mode.label
                popup.menu.add(0, index, index, title)
            }
            popup.setOnMenuItemClickListener { item ->
                val mode = chatModes.reversed()[item.itemId]
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("chat_mode", mode.key).apply()
                btnChatMode?.text = "${mode.label} ▴"
                Toast.makeText(this, "Modo: ${mode.label}", Toast.LENGTH_SHORT).show()
                true
            }
            popup.show()
        }

        btnAttach?.setOnClickListener { showAttachMenu() }
        updateInternetIndicator()
    }

    // ========================================================================
    // ➕ VENTANA DEL BOTÓN +: Imagen / Video / Cámara / Archivo / Internet
    // ========================================================================
    private fun isInternetEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("chat_internet", false)

    private fun updateInternetIndicator() {
        // El + se pinta verde cuando Internet está activado
        if (pendingAttachments.isEmpty()) {
            btnAttach?.setColorFilter(Color.parseColor(if (isInternetEnabled()) "#00E676" else "#808080"))
        }
    }

    private fun showAttachMenu() {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        lateinit var dialog: AlertDialog

        fun addOption(icon: String, label: String, action: () -> Unit) {
            container.addView(TextView(this).apply {
                text = "$icon   $label"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding((8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
                isClickable = true
                isFocusable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = obtainStyledAttributes(attrs)
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }

        addOption("🖼️", "Imagen") { openAttachmentPicker(arrayOf("image/*")) }
        addOption("🎬", "Video") { openAttachmentPicker(arrayOf("video/*")) }
        addOption("📷", "Cámara") { openCamera() }
        addOption("📄", "Archivo") {
            openAttachmentPicker(arrayOf("audio/*", "text/*", "application/*", "image/*", "video/*"))
        }

        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A3C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
        })

        // 🎙️ Conversación voz a voz (on/off)
        container.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "🎙️   Conversación por voz"
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = isVoiceConversationActive
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            setOnCheckedChangeListener { view, checked ->
                setVoiceConversation(checked)
                if (checked && !isVoiceConversationActive) view.isChecked = false
                if (checked && isVoiceConversationActive) dialog.dismiss()
            }
        })

        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A3C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
        })

        container.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "🌐   Internet"
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = isInternetEnabled()
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("chat_internet", checked).apply()
                updateInternetIndicator()
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "🌐 Internet activado: la IA buscará en la web" else "Internet desactivado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()
        dialog.show()
    }

    private fun openAttachmentPicker(mimeTypes: Array<String>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)   // elegir varios a la vez
        }
        try {
            startActivityForResult(intent, PICK_ATTACHMENT_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        try {
            // La foto se guarda en Imágenes/CumulusIA (no necesita FileProvider)
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "cumulus_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CumulusIA")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "No se pudo preparar la cámara", Toast.LENGTH_SHORT).show()
                return
            }
            cameraPhotoUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, TAKE_PHOTO_REQUEST)
        } catch (e: SecurityException) {
            // Solo pasa si la app declara el permiso CAMERA y aún no se ha dado
            cameraPhotoUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            cameraPhotoUri = null
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), TAKE_PHOTO_REQUEST)
            Toast.makeText(this, "Da permiso de cámara y vuelve a tocar Cámara", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error al abrir la cámara: ${e.message}")
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    // ========================================================================
    // 🌐 INTERNET REAL: busca en la web, entra a las páginas y lee su contenido.
    //    Si escribes un enlace (https://...), lee esa página directamente.
    // ========================================================================
    private data class WebResult(val title: String, val url: String, val snippet: String)

    private fun httpGetText(url: String, maxBytes: Int = 1_500_000): Pair<String, String>? = runCatching {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = false
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return@runCatching null
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return@runCatching null
                val type = conn.contentType.orEmpty().lowercase(Locale.US)
                if (type.isNotEmpty() && !type.contains("text") && !type.contains("html") &&
                    !type.contains("json") && !type.contains("xml")
                ) return@runCatching null
                val cs = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.toByteArray()
                }
                val text = runCatching { String(bytes, java.nio.charset.Charset.forName(cs)) }.getOrElse { String(bytes) }
                return@runCatching current to text
            } finally {
                conn.disconnect()
            }
        }
        null
    }.onFailure { Log.w(TAG, "No se pudo abrir $url: ${it.message}") }.getOrNull()

    private fun htmlToReadableText(html: String): String {
        var h = html
        // Quita lo que no es contenido: scripts, estilos, menús, pies de página
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "footer", "header", "form", "aside", "iframe")) {
            h = h.replace(Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        }
        h = h.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        // Saltos de párrafo en etiquetas de bloque
        h = h.replace(Regex("</?(p|div|br|li|h[1-6]|tr|section|article|table|ul|ol)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        val text = android.text.Html.fromHtml(h, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        return text.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 40 }        // descarta botones y textos sueltos
            .joinToString("\n")
    }

    private fun pageTitle(html: String): String =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)
            ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            .orEmpty()

    // Elige los párrafos de la página que más se parecen a tu pregunta
    private fun bestPassages(text: String, query: String, maxChars: Int): String {
        val stop = setOf("que", "qué", "como", "cómo", "para", "por", "con", "los", "las", "una", "uno", "del",
            "the", "and", "for", "what", "how", "cual", "cuál", "donde", "dónde", "cuando", "cuándo", "es", "de", "la", "el", "en")
        val words = query.lowercase(Locale.getDefault())
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in stop }
            .toSet()
        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return ""
        val scored = paragraphs.mapIndexed { idx, p ->
            val lower = p.lowercase(Locale.getDefault())
            val hits = words.count { lower.contains(it) }
            Triple(idx, p, hits * 10 - idx / 20)   // prioriza coincidencias y lo de arriba
        }
        val chosen = scored.sortedByDescending { it.third }
            .let { list -> if (words.isEmpty()) scored else list }
        val out = StringBuilder()
        val picked = mutableListOf<Pair<Int, String>>()
        for ((idx, p, _) in chosen) {
            if (out.length + p.length > maxChars) continue
            picked.add(idx to p)
            out.append(p).append('\n')
            if (out.length >= maxChars * 0.9) break
        }
        return picked.sortedBy { it.first }.joinToString("\n") { it.second.take(700) }
    }

    private fun searchWeb(query: String): List<WebResult> = runCatching {
        val q = java.net.URLEncoder.encode(query.take(250), "UTF-8")
        val (_, html) = httpGetText("https://html.duckduckgo.com/html/?q=$q") ?: return@runCatching emptyList()
        val blocks = html.split("class=\"result__body\"").drop(1)
        blocks.mapNotNull { block ->
            val link = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(block) ?: return@mapNotNull null
            var href = android.text.Html.fromHtml(link.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            // DuckDuckGo envuelve los enlaces: //duckduckgo.com/l/?uddg=URL_REAL
            Regex("[?&]uddg=([^&]+)").find(href)?.let {
                href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) return@mapNotNull null
            val title = android.text.Html.fromHtml(link.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
                ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                .orEmpty()
            WebResult(title, href, snippet)
        }.distinctBy { it.url }.take(8)
    }.onFailure { Log.e(TAG, "Búsqueda web falló: ${it.message}") }.getOrDefault(emptyList())

    // Arma el contexto para la IA: lee páginas reales (en paralelo) y resume lo relevante
    private fun fetchWebContext(query: String): String? {
        val ctx = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("ai_context", 2048)
        val budget = ((ctx - 800) * 2.5).toInt().coerceIn(1200, 9000)   // caracteres que caben

        val directUrls = Regex("https?://[^\\s<>\"]+").findAll(query).map { it.value.trimEnd('.', ',', ')') }.toList()
        val cleanQuery = query.replace(Regex("https?://\\S+"), " ").trim().ifBlank { query }

        val targets: List<WebResult> = if (directUrls.isNotEmpty()) {
            directUrls.take(3).map { WebResult("", it, "") }
        } else {
            searchWeb(cleanQuery)
        }
        if (targets.isEmpty()) return null

        val toRead = targets.take(if (directUrls.isNotEmpty()) 3 else 3)
        val perPage = budget / (toRead.size + 1)
        val pages = arrayOfNulls<String>(toRead.size)
        val workers = toRead.mapIndexed { i, r ->
            thread {
                val got = httpGetText(r.url) ?: return@thread
                val html = got.second
                val title = r.title.ifBlank { pageTitle(html) }.ifBlank { r.url }
                val passages = bestPassages(htmlToReadableText(html), cleanQuery, perPage)
                if (passages.isNotBlank()) {
                    pages[i] = "### $title\nFuente: ${got.first}\n$passages"
                }
            }
        }
        workers.forEach { it.join(15000) }

        val sb = StringBuilder()
        pages.filterNotNull().forEach { sb.append(it).append("\n\n") }
        // Si alguna página no se pudo leer, al menos se usan los resúmenes del buscador
        if (directUrls.isEmpty()) {
            val snippets = targets.filter { it.snippet.isNotBlank() }.take(5)
                .joinToString("\n") { "- ${it.title}: ${it.snippet} (${it.url})" }
            if (snippets.isNotBlank() && sb.length + snippets.length < budget) {
                sb.append("### Otros resultados\n").append(snippets)
            }
        }
        val result = sb.toString().trim().take(budget)
        Log.d(TAG, "🌐 Contexto web: ${result.length} caracteres de ${pages.count { it != null }} páginas")
        return result.ifBlank { null }
    }

    private val maxImages = 10
    private val maxFiles = 10
    private val maxVideos = 5

    // Agrega un adjunto respetando los límites. Devuelve false si no cupo.
    private fun addAttachment(file: File): Boolean {
        val kind = attachmentKind(file)
        val images = pendingAttachments.count { attachmentKind(it) == AttachKind.IMAGE }
        val videos = pendingAttachments.count { attachmentKind(it) == AttachKind.VIDEO }
        val files = pendingAttachments.size - images - videos
        val full = when (kind) {
            AttachKind.IMAGE -> images >= maxImages
            AttachKind.VIDEO -> videos >= maxVideos
            else -> files >= maxFiles
        }
        if (full) {
            file.delete()
            val msg = when (kind) {
                AttachKind.IMAGE -> "Máximo $maxImages imágenes por mensaje"
                AttachKind.VIDEO -> "Máximo $maxVideos videos por mensaje"
                else -> "Máximo $maxFiles archivos por mensaje"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return false
        }
        pendingAttachments.add(file)
        renderAttachments()
        return true
    }

    private fun removeAttachment(file: File) {
        pendingAttachments.remove(file)
        file.delete()
        renderAttachments()
    }

    private fun clearAttachments(deleteFiles: Boolean) {
        if (deleteFiles) pendingAttachments.forEach { it.delete() }
        pendingAttachments.clear()
        renderAttachments()
    }

    // Compatibilidad con el código anterior (un solo adjunto)
    private fun setPendingAttachment(file: File?, deleteFile: Boolean = true) {
        if (file == null) clearAttachments(deleteFile) else addAttachment(file)
    }

    private fun decodeThumb(file: File, sizePx: Int): android.graphics.Bitmap? = runCatching {
        when (attachmentKind(file)) {
            AttachKind.IMAGE -> {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > sizePx * 2 || bounds.outHeight / sample > sizePx * 2) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
            AttachKind.VIDEO -> android.media.ThumbnailUtils.createVideoThumbnail(
                file, android.util.Size(sizePx, sizePx), null
            )
            else -> null
        }
    }.getOrNull()

    // Miniatura cuadrada: imagen / cuadro del video / ícono del archivo, con la X dentro
    private fun buildAttachmentThumb(file: File, onRemove: (() -> Unit)?): View {
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * dp).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#2C2C2E"))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val kind = attachmentKind(file)
        val bmp = decodeThumb(file, size)
        if (bmp != null) {
            frame.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
            })
        } else {
            // Archivo o audio: ícono + extensión
            frame.addView(TextView(this).apply {
                val icon = if (kind == AttachKind.AUDIO) "🎵" else "📄"
                text = "$icon\n${file.extension.uppercase(Locale.US).take(5)}"
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
            })
        }
        if (kind == AttachKind.VIDEO) {
            frame.addView(ImageView(this).apply {
                setImageResource(android.R.drawable.ic_media_play)
                setColorFilter(Color.WHITE)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (24 * dp).toInt(), (24 * dp).toInt(), android.view.Gravity.CENTER
                )
            })
        }
        if (onRemove != null) {
            // X dentro de la miniatura, arriba a la derecha
            frame.addView(TextView(this).apply {
                text = "✕"
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC000000"))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (20 * dp).toInt(), (20 * dp).toInt(), android.view.Gravity.TOP or android.view.Gravity.END
                ).apply { topMargin = (3 * dp).toInt(); marginEnd = (3 * dp).toInt() }
                setOnClickListener { onRemove() }
            })
        }
        return frame
    }

    private fun renderAttachments() {
        val strip = attachmentsStrip ?: return
        strip.removeAllViews()
        pendingAttachments.forEach { file ->
            strip.addView(buildAttachmentThumb(file) { removeAttachment(file) })
        }
        attachmentsScroll?.visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        btnAttach?.setColorFilter(Color.parseColor(if (pendingAttachments.isNotEmpty()) "#3D5AFE" else "#808080"))
        if (pendingAttachments.isEmpty()) updateInternetIndicator()
    }

    private enum class AttachKind { IMAGE, VIDEO, AUDIO, TEXT, OTHER }

    private fun attachmentKind(file: File): AttachKind {
        val ext = file.extension.lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif" -> AttachKind.IMAGE
            "mp4", "mkv", "webm", "3gp", "mov", "avi" -> AttachKind.VIDEO
            "wav", "mp3", "flac", "m4a", "ogg", "aac", "opus" -> AttachKind.AUDIO
            "txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts",
            "c", "cpp", "h", "hpp", "cs", "go", "rs", "sh", "yml", "yaml", "gradle", "sql", "css", "ini" -> AttachKind.TEXT
            else -> AttachKind.OTHER
        }
    }

    private fun copyAttachmentToCache(uri: Uri): File? = runCatching {
        var name = "adjunto"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(cacheDir, "adjuntos").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        out
    }.getOrNull()

    private fun generateAiResponse(prompt: String, backend: String, attachments: List<File> = emptyList()): AiReply {
        if (!triggerAutomaticModelLoadOnFirstMessage()) {
            if (!isAutoLoadEnabled() && getActiveModelFile() != null) {
                return AiReply(
                    "⏸ La carga automática está desactivada.\n\n" +
                    "Abre el panel ☰ y pulsa «Cargar modelo» para activarlo.",
                    ""
                )
            }
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
            val baseMode = currentChatMode()
            // Tu personalidad escrita se suma a las instrucciones del modo elegido
            val personality = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("custom_personality", "")?.trim().orEmpty()
            val mode = if (personality.isNotEmpty()) {
                baseMode.copy(
                    systemPrompt = baseMode.systemPrompt +
                        " Además, sigue siempre esta personalidad indicada por el usuario: " + personality
                )
            } else baseMode
            var usedWeb = false
            var prompt = prompt
            if (isInternetEnabled() && prompt.isNotBlank() && mode.key != "traduccion") {
                val web = fetchWebContext(prompt)
                if (web != null) {
                    usedWeb = true
                    prompt = "Información obtenida ahora mismo de internet:\n\n$web\n\n" +
                        "Usa esa información para responder de forma precisa y menciona las fuentes (sitio web) " +
                        "que usaste. Si la información no alcanza, dilo.\n\nPregunta: $prompt"
                }
            }
            // Separa los adjuntos por tipo
            val textFiles = attachments.filter { attachmentKind(it) == AttachKind.TEXT }
            val mediaFiles = attachments.filter {
                val k = attachmentKind(it); k == AttachKind.IMAGE || k == AttachKind.AUDIO
            }
            val unsupported = attachments.filter {
                val k = attachmentKind(it); k == AttachKind.VIDEO || k == AttachKind.OTHER
            }
            // Archivos de texto/código: se leen y van dentro del mensaje
            var fullPrompt = prompt
            if (textFiles.isNotEmpty()) {
                val perFile = (8000 / textFiles.size).coerceAtLeast(800)
                val joined = textFiles.joinToString("\n\n") { f ->
                    val content = runCatching { f.readText() }.getOrDefault("").take(perFile)
                    "Archivo adjunto «${f.name.substringAfter('_')}»:\n```\n$content\n```"
                }
                fullPrompt = joined + "\n\n" + prompt.ifBlank {
                    if (textFiles.size > 1) "Analiza estos archivos." else "Analiza este archivo."
                }
            }
            val unsupportedNote = if (unsupported.isNotEmpty()) {
                "\n\n⚠️ No pude leer ${unsupported.size} adjunto(s) (videos u otros formatos todavía no son compatibles)."
            } else ""

            val raw = if (usingLiteRt) {
                if (mediaFiles.isNotEmpty()) {
                    "⚠️ Las imágenes y audios solo funcionan con modelos GGUF que tengan su mmproj."
                } else if (fullPrompt.isBlank()) {
                    unsupportedNote.trim()
                } else {
                    val withMode = "[Instrucciones: ${mode.systemPrompt}]\n\n$fullPrompt"
                    runBlocking { LiteRtLmEngineManager.generate(withMode) } + unsupportedNote
                }
            } else {
                nativeSetChatMode(mode.systemPrompt, mode.maxTokens)
                when {
                    mediaFiles.isNotEmpty() && !nativeHasVision() ->
                        "⚠️ Para leer imágenes o audios necesitas un modelo multimodal con su mmproj. " +
                            "Elígelo en ☰ → botón mmproj."
                    mediaFiles.isNotEmpty() ->
                        nativeRunInferenceWithMediaList(
                            fullPrompt, mediaFiles.map { it.absolutePath }.toTypedArray()
                        ) + unsupportedNote
                    fullPrompt.isNotBlank() -> nativeRunInference(fullPrompt) + unsupportedNote
                    else -> "⚠️ Por ahora puedo leer imágenes, audios y archivos de texto o código. " +
                        "Videos y otros archivos todavía no son compatibles."
                }
            }
            val tokenCount = raw.split(" ").size
            val engine = if (usingLiteRt) {
                LiteRtLmEngineManager.engineInfo()
            } else {
                try { nativeEngineInfo() } catch (t: Throwable) { "💻 CPU (NEON)" }
            }
            AiReply(raw, "$tokenCount tokens · $engine" + if (usedWeb) " · 🌐 web" else "")
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
        nativeSetMmproj(getSelectedMmprojFile()?.absolutePath ?: "")
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
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - Cargado]" + mediaCapsLabel()
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

    private fun resetAiNameToDefault(showToast: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("ai_name", "Cumulus IA") == "Cumulus IA" && !showToast) {
            tvAiName?.text = "Cumulus IA"
            return
        }
        prefs.edit().remove("ai_name").apply()
        tvAiName?.text = "Cumulus IA"
        if (showToast) {
            Toast.makeText(this, "Nombre restablecido a Cumulus IA", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNameConfig() {
        // Al borrar todo el nombre personalizado, vuelve a "Cumulus IA"
        etAiNameCustom?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank() && etAiNameCustom?.hasFocus() == true) {
                    val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("ai_name", null)
                    if (saved != null) resetAiNameToDefault(showToast = true)
                }
            }
        })
        btnSaveAiName?.setOnClickListener {
            // El nombre de arriba admite como máximo 10 letras
            val name = (etAiNameCustom?.text?.toString()?.trim() ?: "").take(10).trim()
            if (name.isEmpty()) {
                resetAiNameToDefault(showToast = true)
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("ai_name", name).apply()
            tvAiName?.text = name
            Toast.makeText(this, "Nombre de la IA guardado", Toast.LENGTH_SHORT).show()
        }

        // 🗑 Eliminar nombre de la IA → vuelve a "Cumulus IA"
        findViewById<View>(R.id.btn_delete_ai_name)?.setOnClickListener {
            resetAiNameToDefault(showToast = true)
            etAiNameCustom?.text?.clear()
        }

        // 🗑 Eliminar nombre del usuario
        findViewById<View>(R.id.btn_delete_user_name)?.setOnClickListener {
            etUserName?.text?.clear()
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("user_name").apply()
            Toast.makeText(this, "Nombre de usuario eliminado", Toast.LENGTH_SHORT).show()
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
        if (requestCode == TAKE_PHOTO_REQUEST) {
            val uri = cameraPhotoUri
            cameraPhotoUri = null
            if (uri == null) return
            if (resultCode != RESULT_OK) {
                runCatching { contentResolver.delete(uri, null, null) }
                return
            }
            thread {
                val copied = copyAttachmentToCache(uri)
                runOnUiThread {
                    if (copied != null) setPendingAttachment(copied)
                    else Toast.makeText(this, "No se pudo leer la foto", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (requestCode == PICK_ATTACHMENT_REQUEST && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip -> for (n in 0 until clip.itemCount) uris.add(clip.getItemAt(n).uri) }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            thread {
                val copied = uris.take(25).mapNotNull { copyAttachmentToCache(it) }
                runOnUiThread {
                    copied.forEach { addAttachment(it) }
                    if (copied.size < uris.size) {
                        Toast.makeText(this, "Algunos archivos no se pudieron abrir", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
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

        val proceed: (List<SelectableTtsVoice>) -> Unit = proceed@{ systemVoices ->
            if (apiKey.isEmpty()) {
                showUnifiedVoiceSelectionDialog(
                    systemVoices = systemVoices,
                    downloadedVoices = downloadedVoices,
                    elevenLabsVoices = emptyList(),
                    elevenLabsApiKey = "",
                    elevenLabsError = "Guarda una API Key para consultar las voces de ElevenLabs."
                )
                return@proceed
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
            Unit
        }
        proceed(emptyList())
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
            add(
                SelectableTtsVoice(
                    id = "__system_default__",
                    displayName = "Voz predeterminada del teléfono",
                    source = VoiceSource.SYSTEM
                )
            )
            addAll(downloadedVoices)
            addAll(elevenLabsVoices.filter { voice ->
                val category = voice.category.lowercase(Locale.US)
                category != "premade" && category != "default"
            }.map { voice ->
                SelectableTtsVoice(
                    id = voice.id,
                    displayName = voice.name,
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
            "ANDROID_DEFAULT" -> "__system_default__"
            "LOCAL_DOWNLOADED" -> ttsPrefs.getString("local_voice_path", null)
            "ELEVEN_LABS" -> ttsPrefs.getString("elevenlabs_voice_id", null)
            else -> null
        }
        val selectedIndex = choices.indexOfFirst { it.id == selectedId }
        val labels = choices.map { choice ->
            val sourceLabel = when (choice.source) {
                VoiceSource.SYSTEM -> "Teléfono"
                VoiceSource.DOWNLOADED -> "TTS descargada"
                VoiceSource.ELEVEN_LABS -> "ElevenLabs"
            }
            "$sourceLabel · ${choice.displayName}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Voces guardadas")
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
                throw Exception(describeElevenLabsError(responseCode, responseBody))
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
                    .remove("android_voice_name")
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
                onDone = { downloadedFile -> onGgufDownloadFinished(downloadedFile) }
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
            if (isModelLoadedInMemory) {
                // Manual: tocar "MODELO ACTIVO" lo desactiva
                setManualModelPinned(false)
                releaseModelFromMemory()
            } else {
                loadModelIntoMemoryManual()
            }
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
                        if (isMmprojFile(downloadedFile)) {
                            if (getSelectedMmprojFile() == null) setSelectedMmproj(downloadedFile)
                        } else {
                            setSelectedModelFile(downloadedFile)
                            checkStoredModelStatus()
                        }
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
        // Textos de los botones escritos aquí para evitar símbolos raros (Â, ðŸ)
        ui.btnPause.text = LanguageManager.text(this, "⏸ Pausar")
        ui.btnResume.text = LanguageManager.text(this, "▶ Reanudar")
        ui.btnDelete.text = LanguageManager.text(this, "🗑 Eliminar")
        container.addView(itemView)
        if (isLightMode()) recolorTree(itemView, true)
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
    private fun isAutoLoadEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("auto_load_model", true)

    private fun updateAutoLoadSwitchText() {
        switchAutoLoad?.text = if (switchAutoLoad?.isChecked == true) {
            "Carga automática: ACTIVADA"
        } else {
            "Carga automática: DESACTIVADA (manual)"
        }
    }

    private fun isManualModelPinned(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("manual_model_active", false)

    private fun setManualModelPinned(pinned: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("manual_model_active", pinned).apply()
    }

    private fun autoLoadModel() {
        if (!isAutoLoadEnabled() && !isManualModelPinned()) {
            Log.d(TAG, "Carga automática desactivada: se espera el botón Cargar modelo")
            return
        }
        if (isModelLoading) return
        val modelFile = getActiveModelFile()
        if (modelFile != null && modelFile.exists()) {
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "🚀 Auto-carga en ejecución...")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            val backend = getSavedBackend()
            isModelLoading = true
            thread {
                val success = loadModelWithConfiguration(modelFile, backend)
                isModelLoading = false
                runOnUiThread {
                    isModelLoadedInMemory = success
                    updateLoadModelButton()
                    if (success) {
                        tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - ${if (isAutoLoadEnabled()) "Auto-activado" else "Manual"}]" + mediaCapsLabel()
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
            if (!isAutoLoadEnabled() && !isManualModelPinned()) return false
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
        if (!isAutoLoadEnabled() && !isManualModelPinned()) return false
        Log.d(TAG, "🎯 Carga automática antes de procesar el primer mensaje")
        val success = loadModelWithConfiguration(modelFile, backend)
        isModelLoadedInMemory = success
        runOnUiThread {
            updateLoadModelButton()
            if (success) {
                tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - Activo]" + mediaCapsLabel()
            }
        }
        return success
    }
    private fun updateLoadModelButton() {
        btnLoadModel?.text = LanguageManager.text(
            this,
            if (isModelLoadedInMemory) "MODELO ACTIVO" else "CARGAR MODELO"
        )
        // En manual se puede tocar "MODELO ACTIVO" para desactivarlo
        btnLoadModel?.isEnabled = !isModelLoadedInMemory || !isAutoLoadEnabled()
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
            }?.filter { !isMmprojFile(it) }
            if (files != null) {
                return files.sortedByDescending { it.lastModified() }
            }
        }
        return emptyList()
    }

    private fun getDownloadedMmprojFiles(): List<File> {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return storageDir.listFiles { _, name ->
            name.endsWith(".gguf", ignoreCase = true)
        }.orEmpty().filter { isMmprojFile(it) }.sortedByDescending { it.lastModified() }
    }

    private val mmprojCheckCache = HashMap<String, Boolean>()

    // Un mmproj se reconoce por su nombre o por sus datos internos (claves "clip.")
    private fun isMmprojFile(file: File): Boolean {
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        if (file.name.contains("mmproj", ignoreCase = true)) return true
        val key = "${file.absolutePath}:${file.length()}"
        synchronized(mmprojCheckCache) { mmprojCheckCache[key]?.let { return it } }
        val result = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                val read = input.read(buffer)
                if (read < 4) return@use false
                val header = String(buffer, 0, read, Charsets.ISO_8859_1)
                header.startsWith("GGUF") &&
                    (header.contains("clip.vision") ||
                        header.contains("clip.projector_type") ||
                        header.contains("clip.has_vision_encoder"))
            }
        }.getOrDefault(false)
        synchronized(mmprojCheckCache) { mmprojCheckCache[key] = result }
        return result
    }

    private fun setSelectedMmproj(file: File?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (file == null) prefs.remove("selected_mmproj_path")
        else prefs.putString("selected_mmproj_path", file.absolutePath)
        prefs.apply()
        updateMmprojButton()
    }

    // Al terminar una descarga GGUF: si es mmproj se activa como mmproj,
    // si no, se activa como modelo (igual que antes)
    private fun onGgufDownloadFinished(downloadedFile: File) {
        thread {
            val isMmproj = isMmprojFile(downloadedFile)
            runOnUiThread {
                if (!isMmproj) {
                    setSelectedModelFile(downloadedFile)
                    checkStoredModelStatus()
                    autoLoadModel()
                    return@runOnUiThread
                }
                setSelectedMmproj(downloadedFile)
                thread {
                    nativeSetMmproj(downloadedFile.absolutePath)
                    val loaded = nativeIsModelLoaded()
                    val visionReady = loaded && nativeHasVision()
                    runOnUiThread {
                        val msg = when {
                            visionReady -> "✅ mmproj ${downloadedFile.name} activado automáticamente${mediaCapsLabel()}"
                            !loaded -> "mmproj ${downloadedFile.name} guardado. Se activará al cargar el modelo."
                            else -> "⚠️ mmproj descargado, pero no corresponde al modelo activo"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun getSelectedMmprojFile(): File? {
        val path = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("selected_mmproj_path", null) ?: return null
        return File(path).takeIf { it.exists() }
    }

    private fun updateMmprojButton() {
        val selected = getSelectedMmprojFile()
        btnMmproj?.text = if (selected != null) {
            "mmproj\n${selected.name}"
        } else {
            "mmproj\nNinguno"
        }
    }

    private fun showSelectMmprojDialog() {
        val files = getDownloadedMmprojFiles()
        if (files.isEmpty()) {
            Toast.makeText(
                this,
                "No hay archivos mmproj descargados. Descarga el .gguf que tenga \"mmproj\" en el nombre.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = (listOf("Ninguno") + files.map {
            "${it.name} (${it.length() / (1024 * 1024)} MB)"
        }).toTypedArray()
        val current = getSelectedMmprojFile()
        val currentIndex = files.indexOfFirst { it.absolutePath == current?.absolutePath } + 1

        AlertDialog.Builder(this)
            .setTitle("Seleccionar mmproj")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                setSelectedMmproj(if (which == 0) null else files[which - 1])
                dialog.dismiss()
                val mmprojPath = if (which == 0) "" else files[which - 1].absolutePath
                thread {
                    nativeSetMmproj(mmprojPath)
                    val visionReady = nativeIsModelLoaded() && nativeHasVision()
                    runOnUiThread {
                        val msg = when {
                            mmprojPath.isEmpty() -> "Visión desactivada"
                            visionReady -> "✅ Multimodal activado: ${labels[which]}${mediaCapsLabel()}"
                            !nativeIsModelLoaded() -> "mmproj guardado. Se activará al cargar el modelo."
                            else -> "⚠️ Ese mmproj no corresponde al modelo activo"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

        isModelLoading = true
        thread {
            val success = loadModelWithConfiguration(modelFile, selectedBackend)
            isModelLoading = false
            runOnUiThread {
                isModelLoadedInMemory = success
                updateLoadModelButton()
                if (success) {
                    if (!isAutoLoadEnabled()) setManualModelPinned(true)
                    Toast.makeText(this, "✅ ¡Modelo inicializado en memoria correctamente con $selectedBackend!", Toast.LENGTH_LONG).show()
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$selectedBackend - Cargado]" + mediaCapsLabel()
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

        btnSaveCustomPersonality?.setOnClickListener {
            val customPersonality = etPersonalityCustom?.text?.toString()?.trim() ?: ""
            if (customPersonality.isEmpty()) {
                Toast.makeText(this, "Escribe una personalidad personalizada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("custom_personality", customPersonality).apply()
            Toast.makeText(this, "Personalidad guardada: la IA la usará desde el próximo mensaje", Toast.LENGTH_SHORT).show()
        }
        
        btnDeleteCustomPersonality?.setOnClickListener {
            etPersonalityCustom?.text?.clear()
            prefs.edit().remove("custom_personality").apply()
            Toast.makeText(this, "Personalidad borrada", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "❌ " + (lastElevenError ?: "Error de conexión con ElevenLabs"), Toast.LENGTH_LONG).show()
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

        btnElevenSave?.setOnClickListener {
            if (isRecordingVoice) {
                Toast.makeText(this, "Primero detén la grabación", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Falta tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val source = recordedAudioFile
            if (source == null || !source.exists()) {
                Toast.makeText(this, "Primero graba una muestra de voz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val nameInput = EditText(this).apply {
                hint = "Nombre de la voz (ej: Mi voz)"
                setSingleLine()
            }

            AlertDialog.Builder(this)
                .setTitle("Guardar voz")
                .setView(nameInput)
                .setPositiveButton("Guardar") { _, _ ->
                    val voiceName = nameInput.text.toString().trim().ifEmpty {
                        "Mi voz " + java.text.SimpleDateFormat("dd-MM HH:mm", Locale.US).format(java.util.Date())
                    }
                    val progressDialog = ProgressDialog(this).apply {
                        setTitle("ElevenLabs Cloud")
                        setMessage("Guardando la voz \"$voiceName\"...")
                        setCancelable(false)
                        show()
                    }
                    thread {
                        val savedVoiceId = uploadVoiceToElevenLabs(apiKey, voiceName, source)
                        runOnUiThread {
                            progressDialog.dismiss()
                            if (savedVoiceId != null) {
                                Toast.makeText(
                                    this,
                                    "✅ Voz \"$voiceName\" guardada. Ya aparece en Voces guardadas.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "❌ " + (lastElevenError ?: "No se pudo guardar la voz."),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun startAudioRecording() {
        val audioDir = externalCacheDir
        if (audioDir != null && !audioDir.exists()) {
            audioDir.mkdirs()
        }
        recordedAudioFile = File(audioDir, "eleven_sample_voice.m4a")

        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setAudioChannels(1)
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

        lastElevenError = null
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
            writer.append("Content-Type: audio/mp4").append(lineEnd)
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
                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                Log.e(TAG, "Error en servidor ElevenLabs: Código $responseCode $body")
                lastElevenError = describeElevenLabsError(responseCode, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción conectando con ElevenLabs: ${e.message}")
            lastElevenError = "Sin conexión con ElevenLabs: ${e.message ?: "revisa tu internet"}"
        }
        return null
    }

    private fun describeElevenLabsError(code: Int, body: String): String {
        var status = ""
        var message = ""
        runCatching {
            val json = JSONObject(body)
            val detail = json.opt("detail")
            when (detail) {
                is JSONObject -> {
                    status = detail.optString("status")
                    message = detail.optString("message")
                }
                is String -> message = detail
            }
        }
        val s = (status + " " + message).lowercase(Locale.US)
        return when {
            "instant_voice_cloning" in s || "can_not_use" in s || "subscription" in s || "upgrade" in s ->
                "Tu plan de ElevenLabs no permite clonar voces. Necesitas el plan Starter o superior."
            "voice_limit" in s || "maximum amount" in s || "limit" in s ->
                "Llegaste al límite de voces de tu cuenta. Borra voces en elevenlabs.io (Voices) y vuelve a intentar."
            "missing_permissions" in s || "permission" in s ->
                "Tu API Key no tiene permiso para crear voces. En elevenlabs.io crea una key con 'Voices: Write' activado."
            code == 401 || "invalid_api_key" in s ->
                "API Key inválida. Cópiala de nuevo desde elevenlabs.io y pulsa Guardar API Key."
            message.isNotBlank() -> "ElevenLabs ($code): $message"
            else -> "ElevenLabs respondió con error $code"
        }
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
