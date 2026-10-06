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
import com.paulrj.cumulusia.ai.GenieXEngineManager
import com.paulrj.cumulusia.model.ChatMessage
import com.paulrj.cumulusia.manager.ResumableDownloader
import com.paulrj.cumulusia.manager.DownloadService
import com.paulrj.cumulusia.manager.ActiveDownloads
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
    // ⏹️ Botón detener
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var replyTtsActive: Boolean = false
    private var sendIconDrawable: android.graphics.drawable.Drawable? = null
    private var sendBgDrawable: android.graphics.drawable.Drawable? = null
    private var showingStopIcon = false
    private val stopIconHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentSpokenReply: String = ""
    private var previousSpokenReply: String = ""
    @Volatile private var ttsFinishedAt: Long = 0L
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
        private const val ENGINE_FAIL_MARK = "⚠️ El motor no pudo responder"
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
    private external fun nativeRunChat(roles: Array<String>, contents: Array<String>): String
    private external fun nativeLastStats(): String
    private external fun nativeSetStop(stop: Boolean)
    private external fun nativeSetLibDir(dir: String, useNpu: Boolean)
    // La NPU se enciende al abrir la app según el backend guardado
    private var npuEnabledAtStart = false
    private external fun nativeResetChatMemory()
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
        // 📱 Al girar el teléfono, la app usa también la franja de la cámara (sin banda negra al costado)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 🛡️ Si la app se cerró usando la GPU, se vuelve a CPU para que no se caiga de nuevo
        run {
            val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Solo si la app de verdad se cerró. Al girar la pantalla Android rehace la ventana
            // (savedInstanceState != null): eso NO es un cierre y no debe mostrar el aviso.
            // 📋 Lee el registro del sistema para ver POR QUÉ se cerró
            val crashLog = if (savedInstanceState == null && p.getBoolean("gpu_run_pending", false)) readCrashLog() else ""
            // Si la marca quedó puesta pero NO hubo un cierre real (cerraste la app a mano,
            // o Android la sacó de memoria), se borra en silencio y se mantiene tu GPU/NPU
            if (savedInstanceState == null && crashLog.isBlank()) p.edit().putBoolean("gpu_run_pending", false).commit()
            if (crashLog.isNotBlank()) {
                p.edit().putBoolean("gpu_run_pending", false)
                    .putString("selected_ai_backend", "💻 CPU (NEON)").commit()
                // Se guarda el registro en silencio (sin ventana); sirve si algún día hay que revisarlo
                p.edit().putString("last_crash_log", crashLog).commit()
            }
        }
        // ⚡ Le indica a la NPU dónde están sus librerías (DESPUÉS de la protección:
        //    si la NPU cerró la app, aquí ya quedó en CPU y la NPU no se enciende)
        npuEnabledAtStart = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("selected_ai_backend", "")?.contains("NPU", true) == true
        // La NPU de llama.cpp queda apagada (la NPU se usa vía LiteRT con modelos .litertlm)
        runCatching { nativeSetLibDir(applicationInfo.nativeLibraryDir, false) }
        // ⚡ GenieX (Qualcomm): GGUF en NPU/GPU
        runCatching { GenieXEngineManager.init(this) }
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
        // 🧠 Al abrir la app siempre empieza un chat NUEVO y limpio.
        //    Los chats guardados solo se leen si el usuario los abre desde "Chats anteriores".
        //    (Al girar la pantalla se mantiene el chat que estaba abierto.)
        loadChat(
            if (savedInstanceState != null)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("current_chat_id", null)
            else null
        )
        findViewById<View>(R.id.btn_new_chat)?.setOnClickListener {
            startNewChat()
            drawerLayout?.closeDrawers()
        }
        findViewById<View>(R.id.btn_chat_history)?.setOnClickListener { showChatHistoryDialog() }
        // 📐 Botones de a pares con la misma altura (el más alto manda)
        matchHeights(findViewById(R.id.btn_new_chat), findViewById(R.id.btn_chat_history))
        matchHeights(btnRestoreChatImage, btnChooseBubbleImage)
        matchHeights(btnElevenRecord, btnElevenTest)
        matchHeights(btnElevenUse, btnElevenDelete)
        // Personalidad y nombres: los rojos del mismo tamaño que los azules GUARDAR
        followHeight(btnDeleteCustomPersonality, btnSaveCustomPersonality)
        followHeight(findViewById(R.id.btn_delete_ai_name), btnSaveAiName)
        followHeight(findViewById(R.id.btn_delete_user_name), btnSaveUserName)
        matchHeights(btnOpenGgufDownloads, btnOpenTtsDownloads)
        // 🟦 Recuadro con línea curva alrededor de las pestañas MODELOS GGUF / VOCES TTS
        (btnOpenGgufDownloads?.parent as? LinearLayout)?.let { row ->
            val d = resources.displayMetrics.density
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d
                setStroke((2 * d).toInt(), if (isLightMode()) sectionGray else sectionBlue)
                setColor(if (isLightMode()) Color.WHITE else Color.BLACK)
                sectionBoxes.add(this)   // cambia de color junto con los demás recuadros
            }
        }
        findViewById<View>(R.id.btn_think_level)?.setOnClickListener { showThinkLevelMenu(it) }
        LanguageManager.applyToViewTree(findViewById(android.R.id.content))
    }

    // Iguala la altura de dos botones que están lado a lado
    /** El botón rojo copia siempre el alto del azul (también cuando el azul crece con el ✔). */
    private fun followHeight(red: View?, blue: View?) {
        if (red == null || blue == null) return
        (red.parent as? LinearLayout)?.isBaselineAligned = false
        blue.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && red.layoutParams.height != h) {
                red.post { red.layoutParams = red.layoutParams.apply { height = h } }
            }
        }
    }

    private fun matchHeights(a: View?, b: View?) {
        if (a == null || b == null) return
        val parent = a.parent as? View ?: return
        (parent as? LinearLayout)?.isBaselineAligned = false   // así no se desalinean hacia abajo
        parent.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (a.height == 0 || b.height == 0) return
                parent.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val h = maxOf(a.height, b.height)
                listOf(a, b).forEach { v -> v.layoutParams = v.layoutParams.apply { height = h } }
            }
        })
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
            // (Antes se mostraba un aviso con CPU/GPU/NPU al abrir la app; ahora solo queda en el registro)
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
        if (cameraLiveActive) closeCameraLive()   // libera la cámara al salir de la app
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
        // Al abrir un panel lateral, el teclado y la barra del chat bajan
        drawerLayout?.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                val anyOpen = drawerLayout?.let {
                    it.isDrawerOpen(GravityCompat.START) || it.isDrawerOpen(GravityCompat.END)
                } == true
                // Empieza a abrirse (arrastrando o con el botón) desde cerrado
                if (newState != DrawerLayout.STATE_IDLE && !anyOpen) closeChatKeyboard()
            }
            override fun onDrawerOpened(drawerView: View) = closeChatKeyboard()

            // Las barras de arriba y abajo toman el color del panel mientras se abre
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val panelColor = (drawerView.background as? android.graphics.drawable.ColorDrawable)?.color
                    ?: Color.parseColor(if (isLightMode()) "#FFFFFF" else "#1C1C1E")
                topBarOverlay?.setBackgroundColor(panelColor)
                bottomBarOverlay?.setBackgroundColor(panelColor)
                topBarOverlay?.alpha = slideOffset
                bottomBarOverlay?.alpha = slideOffset
            }

            override fun onDrawerClosed(drawerView: View) {
                topBarOverlay?.alpha = 0f
                bottomBarOverlay?.alpha = 0f
            }
        })
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
                cumulusToast(this, "Carga automática activada", Toast.LENGTH_SHORT).show()
                if (!isModelLoadedInMemory) autoLoadModel()
            } else {
                cumulusToast(this, "Carga manual: usa el botón Cargar modelo", Toast.LENGTH_SHORT).show()
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

        chatAdapter = ChatAdapter(
            chatMessages,
            { bubbleImageUri },
            {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("ai_name", null)?.trim().orEmpty().ifEmpty { "Cumulus IA" }
            },
            {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("user_name", null)?.trim().orEmpty().ifEmpty { "Tú" }
            }
        )
        rvChatMessages?.layoutManager = LinearLayoutManager(this)
        rvChatMessages?.adapter = chatAdapter
        // ✦ Cuando la IA cambia de paso (Pensando → Analizando, buscando, leyendo...) se redibuja su burbuja
        AiActivity.onBubbleChange = { idx ->
            runOnUiThread { if (idx in chatMessages.indices) chatAdapter?.notifyItemChanged(idx, "ai_working") }
        }
        // 🖥️/📱 Botones «Abrir vista previa» / «Abrir maqueta» del chat
        AiActivity.onOpenPreview = { kind -> runOnUiThread { if (kind == "app") openAppMockup() else openWebPreview() } }
        // ✍️ NPU/GPU: el código que va escribiendo la IA se ve en la vista previa mientras se arma
        GenieXEngineManager.onPartial = { t: String ->
            AiActivity.reportGeneratedChars(t.length)   // 🏃 el monito corre según la velocidad de escritura
            if (liveCodeActive) updateLiveDraft(liveDraftPrefix + t)
        }
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
        installTopAvatar()
        
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
        refreshTopAvatar()
    }

    // 🖼️ Imagen de la IA arriba, al lado de las tres rayas (misma que la del chat, esquinas curvas)
    private var topAvatar: ImageView? = null

    private fun installTopAvatar() {
        val menuBtn = findViewById<View>(R.id.btn_open_left_drawer) ?: return
        val row = menuBtn.parent as? LinearLayout ?: return
        val d = resources.displayMetrics.density
        val idx = row.indexOfChild(menuBtn) + 1
        // Reemplaza el espacio de 40dp que había al lado (así el nombre sigue centrado)
        (row.getChildAt(idx) as? android.widget.Space)?.let { row.removeView(it) }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply {
                marginStart = (4 * d).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 10 * d)
                }
            }
            contentDescription = "Imagen de la IA"
        }
        row.addView(img, idx)
        topAvatar = img
        refreshTopAvatar()
    }

    private fun refreshTopAvatar() {
        val img = topAvatar ?: return
        val uri = bubbleImageUri
        if (uri != null) {
            runCatching { img.setImageURI(uri) }
            if (img.drawable == null) img.setImageResource(R.drawable.cumulus_icon)
        } else img.setImageResource(R.drawable.cumulus_icon)
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

        startStopIconWatcher()
        btnSend?.setOnClickListener {
            // ⏹️ Si la IA está escribiendo o hablando, el botón la interrumpe
            if (isAiBusy || isTtsSpeaking || replyTtsActive) {
                stopAiNow()
                return@setOnClickListener
            }
            var message = etChatInput?.text?.toString()?.trim() ?: ""
            // 📷 Cámara en vivo: cada pregunta va con una foto de lo que se ve ahora
            if (cameraLiveActive && message.isNotEmpty()) {
                if (skipNextCameraCapture) {
                    skipNextCameraCapture = false
                } else if (pendingAttachments.none { attachmentKind(it) == AttachKind.IMAGE }) {
                    captureCameraFrame()?.let { pendingAttachments.add(it) }
                    cameraLastShownIdx = chatMessages.size
                    cameraAnswerView?.text = "👀 Mirando..."
                    if (isInternetEnabled() && wantsCameraWebSearch(message)) {
                        // Paso 1: reconocer qué es · Paso 2 (al terminar): buscarlo en internet
                        pendingCameraSearch = message
                        message = "Identifica con precisión qué es lo principal que aparece en esta imagen (objeto, producto con su marca y modelo si se ve, lugar, planta, animal o texto). Responde SOLO con el nombre, en una línea corta."
                    }
                }
            }
            val attachments = pendingAttachments.toList()
            if (message.isEmpty() && attachments.isEmpty()) {
                cumulusToast(this, "Escribe un mensaje", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 📄 "Guárdalo en Word / PDF / txt": se guarda la última respuesta, sin preguntarle a la IA
            val fileFormat = if (attachments.isEmpty()) requestedFileFormat(message) else null
            if (fileFormat != null && isOnlySaveCommand(message)) {
                val lastReply = chatMessages.lastOrNull { !it.isUser && it.text.isNotBlank() && !it.text.startsWith("Pensando") && it.text != greetingText }
                if (lastReply != null) {
                    etChatInput?.text?.clear()
                    offerSaveAsFile(lastReply.text, fileFormat)
                    return@setOnClickListener
                }
            }
            // 🧠 Lo conversado antes de este mensaje (para que la IA lo recuerde)
            val history = buildHistoryForModel()

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
            val userMessageIndex = chatMessages.size - 1
            chatAdapter?.notifyItemInserted(chatMessages.size - 1)
            rvChatMessages?.scrollToPosition(chatMessages.size - 1)
            etChatInput?.text?.clear()
            saveCurrentChat()
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

            // 💻 Modo Ingeniero de código: solo programación. Noticias, imágenes, videos, poemas… no se hacen aquí
            runCatching { codeModeGate(message, history, attachments.isNotEmpty()) }.getOrNull()?.let { notCode ->
                chatMessages[thinkingIndex] = ChatMessage(text = notCode, isUser = false, sender = "Cumulus IA",
                    tokens = "💻 Modo Ingeniero de código")
                chatAdapter?.notifyItemChanged(thinkingIndex)
                rvChatMessages?.scrollToPosition(thinkingIndex)
                saveCurrentChat()
                return@setOnClickListener
            }

            // 🎬 Varios videos + "júntalos / únelos": la app los une en uno solo (no necesita la IA)
            val videoClips = attachments.filter { attachmentKind(it) == AttachKind.VIDEO }
            if (videoClips.size >= 2 && (wantsJoinVideos(message) || message.isBlank())) {
                isAiBusy = true
                updateSendButtonIcon()
                runJoinVideos(videoClips, thinkingIndex)
                return@setOnClickListener
            }

            // 🖼️ Imagen adjunta + "cambia el tamaño / mejórala / bórrale... / cámbiale...": editor de imágenes
            val imageFiles = attachments.filter { attachmentKind(it) == AttachKind.IMAGE }
            val imgOp = if (imageFiles.size == 1) imageOperation(message) else null
            if (imgOp == ImgOp.RESIZE || imgOp == ImgOp.ENHANCE) {
                isAiBusy = true
                updateSendButtonIcon()
                runImageTool(imgOp, imageFiles[0], message, thinkingIndex)
                return@setOnClickListener
            }
            if (imgOp == ImgOp.EDIT || imgOp == ImgOp.ERASE) {
                chatMessages[thinkingIndex] = ChatMessage(
                    text = (if (imgOp == ImgOp.ERASE) "🧽 Borrar partes de una imagen" else "🪄 Cambiar cosas dentro de una imagen") +
                        " necesita una IA de edición de imágenes en internet, y los servicios que lo hacen bien piden una API Key " +
                        "(como ElevenLabs para la voz). Todavía no está activado en la app.\n\n" +
                        "Lo que sí puedo hacer ahora con tu imagen: cambiarle el tamaño, mejorarla, o describirla si tienes un modelo con mmproj.",
                    isUser = false, sender = "Cumulus IA", tokens = "")
                chatAdapter?.notifyItemChanged(thinkingIndex)
                saveCurrentChat()
                return@setOnClickListener
            }
            // 🎨 "Crea una imagen de ...": imagen gratis por internet
            // 🛠️ Pedido de crear una app / página (o con código pegado): nunca es una imagen ni un video
            val creationRequest = runCatching { isCreationRequest(message) }.getOrDefault(false)
            if (attachments.isEmpty() && !creationRequest && wantsCreateImage(message)) {
                if (!isInternetEnabled()) {
                    chatMessages[thinkingIndex] = ChatMessage(text = "🌐 Para crear imágenes necesito internet. Actívalo en ➕ → Internet y vuelve a pedírmelo.",
                        isUser = false, sender = "Cumulus IA", tokens = "")
                    chatAdapter?.notifyItemChanged(thinkingIndex)
                    saveCurrentChat()
                    return@setOnClickListener
                }
                isAiBusy = true
                updateSendButtonIcon()
                runCreateImage(message, thinkingIndex)
                return@setOnClickListener
            }

            // 🎞️ "Crea un video de ...": imágenes gratis por internet + animación en el teléfono
            if (attachments.isEmpty() && !creationRequest && wantsCreateVideo(message)) {
                if (!isInternetEnabled()) {
                    chatMessages[thinkingIndex] = ChatMessage(text = "🌐 Para crear un video necesito internet. Actívalo en ➕ → Internet y vuelve a pedírmelo.",
                        isUser = false, sender = "Cumulus IA", tokens = "")
                    chatAdapter?.notifyItemChanged(thinkingIndex)
                    saveCurrentChat()
                    return@setOnClickListener
                }
                isAiBusy = true
                updateSendButtonIcon()
                runCreateVideo(message, thinkingIndex)
                return@setOnClickListener
            }

            replyImages.clear()
            val selectedBackend = spinnerBackend?.selectedItem?.toString() ?: getSavedBackend()
            stopRequested = false
            runCatching { nativeSetStop(false) }
            isAiBusy = true
            updateSendButtonIcon()   // ⏹️ aparece junto con "Pensando"
            // ✦ Estrella girando + «Pensando...» (o «Analizando...» si mandaste archivos, fotos, audio o video)
            AiActivity.start(thinkingIndex, if (attachments.isNotEmpty()) AiActivity.ANALYZING else AiActivity.THINKING)
            AiActivity.log("💭 Leyendo tu mensaje", message.take(300) +
                (if (attachments.isNotEmpty()) "\n📎 " + attachments.joinToString(", ") { it.name.substringAfter('_') } else ""))
            // 🖥️/📱 Pedido de una app o página. La invitación a mirarla llega en el momento justo:
            //    web → cuando la app está terminada · Kotlin → apenas está creada la parte visual (diseño XML)
            val previewKind = if (attachments.isEmpty()) runCatching {
                codePreviewKind(message, history.lastOrNull { it.first == "assistant" }?.second)
            }.getOrNull() else null
            startLiveCode(previewKind, attachments.isEmpty() && (previewKind != null || creationRequest || looksLikeCodeRequest(message)))
            when (previewKind) {
                "web" -> AiActivity.analyzing("🖥️ Es una app web: cuando esté terminada te invito a abrir la vista previa")
                "app" -> AiActivity.analyzing("📱 Es una app Android: apenas esté la parte visual te invito a ver la maqueta")
            }
            chatAdapter?.notifyItemChanged(thinkingIndex)
            thread {
                val start = System.currentTimeMillis()
                try {
                    val usingGpuNow = selectedBackend.contains("GPU", ignoreCase = true) ||
                        selectedBackend.contains("NPU", ignoreCase = true)
                    if (usingGpuNow) getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("gpu_run_pending", true).commit()
                    streamingIndex = if (isVoiceConversationActive) -1 else thinkingIndex
                    val reply = try {
                        generateAiResponse(message, selectedBackend, attachments, history)
                    } finally {
                        streamingIndex = -1
                        if (usingGpuNow) getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean("gpu_run_pending", false).apply()
                    }
                    // Guarda los adjuntos con el chat (imágenes y archivos hasta 2 MB; videos se borran)
                    val keptPaths = persistAttachments(attachments)
                    runOnUiThread {
                        if (userMessageIndex in chatMessages.indices && chatMessages[userMessageIndex].isUser) {
                            chatMessages[userMessageIndex] = chatMessages[userMessageIndex].copy(attachments = keptPaths)
                            chatAdapter?.notifyItemChanged(userMessageIndex)
                        }
                    }
                    val secs = (System.currentTimeMillis() - start) / 1000f
                    val wasStopped = stopRequested
                    val finalText = if (wasStopped) {
                        val t = reply.text.trim()
                        if (t.isEmpty() || t.startsWith("Pensando")) "⏹️ Respuesta detenida" else "$t\n\n⏹️ (detenida)"
                    } else reply.text
                    runOnUiThread {
                        stopLiveCode()
                        AiActivity.finish(if (wasStopped) "Respuesta detenida" else "Respuesta lista")
                        chatMessages[thinkingIndex] = ChatMessage(
                            text = finalText,
                            isUser = false,
                            sender = "Cumulus IA",
                            tokens = if (reply.tokens.isNotEmpty())
                                "${reply.tokens} · ${String.format(Locale.US, "%.1f", secs)} s"
                            else String.format(Locale.US, "%.1f s", secs),
                            // 🖼️ Fotos de las noticias / tarjeta del clima
                            attachments = if (wasStopped) emptyList() else replyImages.toList()
                        )
                        replyImages.clear()
                        chatAdapter?.notifyItemChanged(thinkingIndex)
                        rvChatMessages?.scrollToPosition(thinkingIndex)
                        isAiBusy = false
                        saveCurrentChat()
                        if (wasStopped) {
                            // Detenida: no se lee en voz alta
                        } else if (isVoiceConversationActive) {
                            // Voz a voz: habla la respuesta y luego vuelve a escuchar
                            speakThenListen(reply.text)
                        } else if (isVocalResponseActive && reply.text.isNotBlank()) {
                            replyTtsActive = true
                            chatTtsEngine?.speak(textForSpeech(reply.text)) { replyTtsActive = false }
                        }
                        // 📚 Guarda lo aprendido de internet
                        pendingLearnTopic?.let { topic ->
                            pendingLearnTopic = null
                            if (!wasStopped && reply.text.length > 80) {
                                saveKnowledge(topic, reply.text)
                                cumulusToast(this, "📚 Aprendido y guardado: $topic", Toast.LENGTH_LONG).show()
                            }
                        }
                        // 📷 Cámara en vivo: ya reconoció el objeto → ahora lo busca en internet
                        if (!wasStopped && cameraLiveActive && pendingCameraSearch != null) continueCameraSearch(reply.text)
                        // 📄 Si pediste el resultado como archivo (Word / PDF / txt), se ofrece guardarlo
                        val wantFile = requestedFileFormat(message)
                        if (!wasStopped && wantFile != null && reply.text.isNotBlank()) offerSaveAsFile(reply.text, wantFile)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error generando respuesta: ${t.message}")
                    runOnUiThread {
                        AiActivity.log("⚠️ Error", t.message.orEmpty().take(300))
                        stopLiveCode()
                        AiActivity.finish("Terminó con error")
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
                cumulusToast(this, "Respuesta por voz activada", Toast.LENGTH_SHORT).show()
            }
        }

        btnMic?.setOnClickListener {
            if (isVoiceConversationActive) {
                setVoiceConversation(false)
                cumulusToast(this, "Conversación por voz desactivada", Toast.LENGTH_SHORT).show()
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
                    micWatchdog.removeCallbacksAndMessages(null)   // ✅ el micrófono sí abrió
                    micFailStreak = 0
                    if (isVoiceConversationActive) {
                        etChatInput?.hint = micHint("Te escucho...")
                    } else {
                        cumulusToast(this@MainActivity, "Escuchando...", Toast.LENGTH_SHORT).show()
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
                    if (!isVoiceConversationActive) setDictationMic(false)
                    micWatchdog.removeCallbacksAndMessages(null)
                    partialCut.removeCallbacksAndMessages(null)
                    Log.w(TAG, "Error de reconocimiento de voz: $error")
                    // El servicio de voz se desconectó o quedó trabado: se crea uno nuevo (antes había que apagar y prender)
                    if (isVoiceConversationActive && (error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                        micFailStreak++
                        if (micFailStreak >= 2) { rebuildRecognizerAndListen(); return }
                    }
                    if (error == 12 || error == 13) preferOfflineSpeech = false // idioma sin paquete offline
                    if (isVoiceConversationActive) {
                        // El micrófono se mantiene encendido: ante cualquier corte, vuelve a escuchar
                        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500L else 150L
                            chatComposer?.postDelayed({ startConversationListening() }, wait)
                        } else {
                            setVoiceConversation(false)
                            cumulusToast(
                                this@MainActivity,
                                "Conversación por voz detenida (error $error)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }
                    cumulusToast(
                        this@MainActivity,
                        "No pude reconocer la voz. Inténtalo nuevamente.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onResults(results: Bundle?) {
                    isListeningForChat = false
                    if (!isVoiceConversationActive) setDictationMic(false)
                    micWatchdog.removeCallbacksAndMessages(null)
                    partialCut.removeCallbacksAndMessages(null)
                    micFailStreak = 0
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
                    if (partialText.isNotEmpty() && !isVoiceConversationActive) {
                        etChatInput?.setText(partialText)
                        etChatInput?.setSelection(partialText.length)
                    }
                    // ⚡ Voz a voz: si dejaste de hablar ~0.9 s, se cierra la frase al tiro
                    //    (algunos teléfonos ignoran el silencio pedido y esperaban muchos segundos)
                    // ✋ Interrupción automática: apenas empiezas a hablar (2+ palabras que no son el eco
                    //    de la propia IA), la IA se calla al instante y te escucha; al terminar tu frase
                    //    responde a lo último que dijiste.
                    if (isVoiceConversationActive && isTtsSpeaking &&
                        partialText.trim().split(Regex("\\s+")).size >= 2 && !looksLikeEcho(partialText)) {
                        Log.d(TAG, "✋ Interrumpida por el usuario: $partialText")
                        runCatching { chatTtsEngine?.stop() }
                        isTtsSpeaking = false
                        replyTtsActive = false
                        ttsFinishedAt = 0L   // lo que sigue ya no se trata como eco
                        etChatInput?.hint = micHint("Te escucho...")
                    }
                    if (partialText.isNotBlank() && isVoiceConversationActive) {
                        partialCut.removeCallbacksAndMessages(null)
                        partialCut.postDelayed({
                            if (isListeningForChat && isVoiceConversationActive) runCatching { speechRecognizer?.stopListening() }
                        }, 900L)
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
                cumulusToast(this, "Da permiso de micrófono y vuelve a activarlo", Toast.LENGTH_LONG).show()
                return
            }
            if (speechRecognizer == null) {
                cumulusToast(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
                return
            }
            if (chatTtsEngine == null) chatTtsEngine = TtsEngineManager(this)
            isVoiceConversationActive = true
            micRebuilds = 0; micFailStreak = 0
            etChatInput?.hint = "⏳ Activando micrófono..."   // «Te escucho» solo cuando abre de verdad
            voiceHistory.clear()
            // El micrófono de al lado de Enviar NO se pinta: el voz a voz se ve con el micrófono azul de la barra
            cumulusToast(this, "🎙️ Conversación por voz activada. ¡Habla!", Toast.LENGTH_SHORT).show()
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
            // La voz de la IA puede llegar al micrófono incluso unos segundos después de terminar
            val recentlySpoke = isTtsSpeaking || System.currentTimeMillis() - ttsFinishedAt < 3500
            if (recentlySpoke && looksLikeEcho(spokenText)) {
                // Es la propia voz de la IA que entró por el micrófono: se ignora
                Log.d(TAG, "🎙️ Eco ignorado: $spokenText")
                etChatInput?.text?.clear()
            } else {
                if (isTtsSpeaking) {
                    // Interrumpes a la IA: deja de hablar y te escucha
                    chatTtsEngine?.stop()
                    isTtsSpeaking = false
                }
                etChatInput?.text?.clear()
                if (cameraLiveActive && !isAiBusy) {
                    // 📷 Con la cámara en vivo, lo que dices va con la foto de lo que estás viendo
                    etChatInput?.setText(spokenText)
                    btnSend?.performClick()
                } else if (isAiBusy) {
                    // Interrupción mientras la IA piensa: esa respuesta se descarta
                    // y se responde a lo último que dijiste
                    voiceTurnId++
                    queuedVoiceText = listOfNotNull(queuedVoiceText, spokenText).joinToString(" ")
                } else {
                    runVoiceTurn(spokenText)
                }
            }
        }
        // El micrófono sigue encendido
        chatComposer?.postDelayed({ startConversationListening() }, 150)
    }

    private fun looksLikeEcho(heard: String): Boolean {
        fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(Locale.getDefault()), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        val reply = norm("$currentSpokenReply $previousSpokenReply")
        val words = norm(heard).split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return true
        val matches = words.count { reply.contains(it) }
        return matches >= (words.size * 0.5)
    }

    private fun sendQueuedVoiceIfAny() {
        val queued = queuedVoiceText ?: return
        queuedVoiceText = null
        if (isVoiceConversationActive && queued.isNotBlank()) runVoiceTurn(queued)
    }

    // ========================================================================
    // 🎙️ VOZ A VOZ PURO: no crea mensajes en el chat, solo escucha y habla
    // ========================================================================
    private val voiceHistory = mutableListOf<Pair<String, String>>()
    @Volatile private var voiceTurnId = 0

    private fun runVoiceTurn(spokenText: String) {
        if (spokenText.isBlank()) return
        val turn = ++voiceTurnId
        stopRequested = false
        runCatching { nativeSetStop(false) }
        isAiBusy = true
        updateSendButtonIcon()
        etChatInput?.hint = "💭 Pensando..."
        val history = voiceHistory.toList()
        val backend = spinnerBackend?.selectedItem?.toString() ?: getSavedBackend()
        AiActivity.start(-1)   // voz a voz: no hay burbuja, pero los pasos quedan para la ventana de actividad
        AiActivity.log("🎙️ Te escuché", spokenText.take(300))
        thread {
            val reply = runCatching { generateAiResponse(spokenText, backend, emptyList(), history) }
                .getOrElse { AiReply("Perdón, ocurrió un error.", "") }
            runOnUiThread {
                AiActivity.finish()
                isAiBusy = false
                if (!isVoiceConversationActive) return@runOnUiThread
                if (turn != voiceTurnId) {
                    // Me interrumpiste mientras pensaba: respondo a lo nuevo
                    sendQueuedVoiceIfAny()
                    return@runOnUiThread
                }
                voiceHistory.add("user" to spokenText)
                voiceHistory.add("assistant" to reply.text)
                while (voiceHistory.size > 40) voiceHistory.removeAt(0)
                if (queuedVoiceText != null) sendQueuedVoiceIfAny() else speakThenListen(reply.text)
            }
        }
    }

    // Si el idioma no está descargado para usarse sin internet, se vuelve al reconocimiento normal
    private var preferOfflineSpeech = true

    /**
     * 🎙️ Texto del cuadro de chat con el MISMO micrófono del botón de al lado del enviar
     * (mismo dibujo, en azul de activado), en vez del emoji de micrófono.
     */
    private fun micHint(text: String, leading: String = ""): CharSequence {
        val src = btnMic?.drawable?.constantState?.newDrawable()?.mutate() ?: return "$leading🎙️ $text"
        val size = ((etChatInput?.textSize ?: 40f) * 1.1f).toInt()
        src.setBounds(0, 0, size, size)
        // 🔵 Azul: indica que la conversación por voz está activada
        src.colorFilter = android.graphics.PorterDuffColorFilter(Color.parseColor("#3D5AFE"), android.graphics.PorterDuff.Mode.SRC_IN)
        val sp = android.text.SpannableStringBuilder(leading).append("\u0000 ").append(text)
        sp.setSpan(android.text.style.ImageSpan(src, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
            leading.length, leading.length + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private fun startConversationListening() {
        if (!isVoiceConversationActive || isListeningForChat) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LanguageManager.getSelectedLanguage(this@MainActivity))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // ⚡ Responde antes: da por terminada tu frase tras ~0.8 s de silencio (antes ~2 s)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            // Reconocimiento dentro del teléfono (más rápido que mandarlo a internet)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOfflineSpeech)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { Log.e(TAG, "No se pudo escuchar: ${it.message}") }
        // 🐕 Si en 2.5 s el micrófono no abrió de verdad, se recrea el reconocedor y se intenta otra vez
        micWatchdog.removeCallbacksAndMessages(null)
        micWatchdog.postDelayed({
            if (isVoiceConversationActive && !isListeningForChat) {
                Log.w(TAG, "🎙️ El micrófono no abrió; recreando el reconocedor")
                rebuildRecognizerAndListen()
            }
        }, 2500L)
    }

    private val micWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val partialCut = android.os.Handler(android.os.Looper.getMainLooper())
    private var micFailStreak = 0
    private var micRebuilds = 0

    /** Destruye el reconocedor trabado, crea uno nuevo y vuelve a escuchar (máx. 3 veces seguidas). */
    private fun rebuildRecognizerAndListen() {
        if (!isVoiceConversationActive) return
        if (micRebuilds >= 3) {
            micRebuilds = 0
            etChatInput?.hint = "⚠️ El micrófono no responde. Apaga y prende la conversación por voz."
            return
        }
        micRebuilds++
        micFailStreak = 0
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        isListeningForChat = false
        setupChatSpeechRecognizer()
        chatComposer?.postDelayed({ startConversationListening() }, 300)
        chatComposer?.postDelayed({ if (isListeningForChat) micRebuilds = 0 }, 4000)
    }

    // Quita código y símbolos para que la voz suene natural
    // Limpia el texto antes de leerlo en voz alta: sin asteriscos, almohadillas,
    // código, enlaces ni emojis (así no dice "asterisco asterisco")
    private fun textForSpeech(text: String): String =
        text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " Te dejé el código en la pantalla. ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")      // [texto](enlace) → texto
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("(?m)^\\s*[-*+•]\\s+"), "")              // viñetas al inicio de línea
            .replace(Regex("[*#_~>|`]+"), " ")
            .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), ". ")
            .replace(Regex("\\.\\s*\\."), ".")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ⏹️ Interrumpe a la IA: corta lo que está escribiendo y lo que está diciendo
    private fun stopAiNow() {
        stopRequested = true
        runCatching { nativeSetStop(true) }
        thread { runCatching { GenieXEngineManager.requestStop() } }
        runCatching { chatTtsEngine?.stop() }
        isTtsSpeaking = false
        replyTtsActive = false
        updateSendButtonIcon()
        cumulusToast(this, "⏹️ Detenido", Toast.LENGTH_SHORT).show()
    }

    // El botón de enviar se vuelve un cuadrado mientras la IA escribe o habla
    private fun updateSendButtonIcon() {
        val btn = btnSend ?: return
        if (sendIconDrawable == null) sendIconDrawable = btn.drawable
        if (sendBgDrawable == null) sendBgDrawable = btn.background
        val busy = isAiBusy || isTtsSpeaking || replyTtsActive
        if (busy == showingStopIcon) return
        showingStopIcon = busy
        if (busy) {
            val d = resources.displayMetrics.density
            btn.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 3 * d
                setColor(Color.parseColor("#9A9A9A"))   // gris, como el botón de copiar
                setSize((14 * d).toInt(), (14 * d).toInt())
            })
            btn.imageTintList = null
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#2A2A2A"))
                setStroke((1.5f * d).toInt(), Color.parseColor("#9A9A9A"))
            }
            btn.contentDescription = "Detener"
        } else {
            btn.setImageDrawable(sendIconDrawable)
            btn.background = sendBgDrawable
            btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            btn.contentDescription = "Enviar"
        }
    }

    private fun startStopIconWatcher() {
        stopIconHandler.removeCallbacksAndMessages(null)
        stopIconHandler.post(object : Runnable {
            override fun run() {
                updateSendButtonIcon()
                stopIconHandler.postDelayed(this, 250)
            }
        })
    }

    private fun speakThenListen(replyText: String) {
        if (!isVoiceConversationActive) return
        val spoken = textForSpeech(replyText)
        val engine = chatTtsEngine
        if (spoken.isBlank() || engine == null) {
            sendQueuedVoiceIfAny()
            startConversationListening()
            return
        }
        previousSpokenReply = currentSpokenReply
        currentSpokenReply = spoken
        isTtsSpeaking = true
        etChatInput?.hint = micHint("Respondiendo... (micrófono encendido)", leading = "🔊 ")
        engine.speak(spoken) {
            runOnUiThread {
                isTtsSpeaking = false
                ttsFinishedAt = System.currentTimeMillis()
                if (isVoiceConversationActive) {
                    etChatInput?.hint = micHint("Te escucho...")
                    sendQueuedVoiceIfAny()
                    startConversationListening()
                }
            }
        }
        // El micrófono no se apaga mientras la IA habla
        startConversationListening()
    }

    /** Micrófono de al lado de Enviar: azul mientras graba tu mensaje de voz, gris cuando termina. */
    private fun setDictationMic(active: Boolean) {
        btnMic?.setColorFilter(Color.parseColor(if (active) "#3D5AFE" else "#808080"))
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
            cumulusToast(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (isListeningForChat) {
            recognizer.stopListening()
            isListeningForChat = false
            setDictationMic(false)
            return
        }
        setDictationMic(true)   // 🔵 azul mientras te escucha para tu mensaje de voz
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

    private fun closeChatKeyboard() {
        val chatInput = etChatInput ?: return
        if (!chatInput.hasFocus()) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(chatInput.windowToken, 0)
        chatInput.clearFocus()
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
    // 💭 NIVEL DE PENSAMIENTO (botón ⋮ de arriba): rápido / medio / profundo
    // ========================================================================
    private fun thinkLevel(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("think_level", "rapido") ?: "rapido"

    private fun showThinkLevelMenu(anchor: View) {
        val options = listOf("rapido" to "⚡ Pensamiento rápido", "medio" to "🧠 Pensamiento medio", "profundo" to "🔬 Pensamiento profundo")
        val current = thinkLevel()
        showRoundMenu(anchor, options.map { (key, label) -> if (key == current) "✓ $label" else label }, above = false) { i ->
            val (key, label) = options[i]
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("think_level", key).apply()
            cumulusToast(this, label.substring(2), Toast.LENGTH_SHORT).show()
        }
    }

    // Instrucción extra y tokens adicionales según el nivel elegido
    private fun thinkInstruction(level: String): Pair<String, Int> = when (level) {
        "medio" -> " Antes de responder, razona brevemente paso a paso dentro de <pensamiento>...</pensamiento>" +
            " (máximo 6 líneas) y después escribe la respuesta final fuera de esas etiquetas." to 512
        "profundo" -> " Antes de responder, piensa a fondo dentro de <pensamiento>...</pensamiento>: analiza la pregunta," +
            " considera varias opciones, verifica cálculos o código y revisa posibles errores. Después escribe" +
            " la respuesta final, clara y completa, fuera de esas etiquetas." to 1536
        else -> " Responde de forma directa, sin escribir tu razonamiento." to 0
    }

    // Quita el razonamiento del texto final (también <think> de modelos como Qwen3 o DeepSeek-R1)
    // ========================================================================
    // 🌐 AGENTE WEB: la IA usa internet con herramientas <buscar> y <abrir>
    // ========================================================================
    private val agentToolRegex = Regex(
        "<(buscar|abrir)>\\s*(.*?)\\s*</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val maxAgentActions = 3

    // ================= 📅 PEDIDOS DIARIOS =================
    // La app reconoce lo que un usuario pide todos los días y le entrega a la IA
    // datos reales ya listos. Los modelos pequeños no saben buscar bien solos y
    // terminan inventando; así solo tienen que resumir.

    private fun rxAny(text: String, vararg patterns: String): Boolean =
        patterns.any { Regex("(?i)$it").containsMatchIn(text) }

    private fun xmlUnescape(t: String): String = t
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    // País de las noticias: primero la tarjeta SIM / red móvil (dice Chile aunque el teléfono esté en "Español (US)")
    private fun newsCountry(): String {
        runCatching {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            listOf(tm.simCountryIso, tm.networkCountryIso).firstOrNull { !it.isNullOrBlank() && it.length == 2 }
                ?.let { return it.uppercase(Locale.US) }
        }
        val c = Locale.getDefault().country.uppercase(Locale.US)
        return if (c.length == 2) c else "CL"
    }

    // Países que el usuario puede nombrar en la pregunta → código de Google Noticias
    private val newsCountries = linkedMapOf(
        "chile" to "CL", "argentina" to "AR", "per[uú]" to "PE", "bolivia" to "BO", "colombia" to "CO",
        "m[eé]xico" to "MX", "espa[ñn]a" to "ES", "venezuela" to "VE", "ecuador" to "EC", "uruguay" to "UY",
        "paraguay" to "PY", "cuba" to "CU", "guatemala" to "GT", "honduras" to "HN", "el salvador" to "SV",
        "nicaragua" to "NI", "costa rica" to "CR", "panam[aá]" to "PA", "rep[uú]blica dominicana" to "DO",
        "puerto rico" to "PR", "estados unidos|ee\\.? ?uu|eeuu|usa" to "US"
    )

    private fun countryInPrompt(prompt: String): Pair<String, String>? {
        for ((rx, code) in newsCountries) {
            val m = Regex("(?i)\\b($rx)\\b").find(prompt) ?: continue
            return code to m.value
        }
        return null
    }

    private fun fetchGoogleNews(url: String, max: Int = 8): List<String> {
        val (_, xml) = httpGetText(url) ?: return emptyList()
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(max).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let { xmlUnescape(it) }
                ?: return@mapNotNull null
            val src = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let { xmlUnescape(it) }
            // Google a veces responde con un aviso en vez de noticias: se descarta
            if (Regex("(?i)feed (no est[aá] disponible|is not available|isn.t available)|no disponible").containsMatchIn(title)) return@mapNotNull null
            val clean = if (src != null) title.removeSuffix(" - $src") else title
            "• $clean" + (if (src != null) " ($src)" else "")
        }.toList()
    }

    private fun newsContext(prompt: String): String? {
        val named = countryInPrompt(prompt)
        val gl = named?.first ?: newsCountry()
        val hl = if (gl == "ES") "es" else "es-419"
        val base = "hl=$hl&gl=$gl&ceid=$gl:$hl"
        val sections = mutableListOf<Pair<String, String>>()
        val wantsWorld = rxAny(prompt, "internacional", "del mundo", "mundiales", "extranjer")
        val wantsNational = rxAny(prompt, "nacional", "del pa[ií]s", "de chile", "de aqu[ií]", "locales") || !wantsWorld
        when {
            rxAny(prompt, "deport", "f[uú]tbol") -> sections += "Deportes" to "https://news.google.com/rss/headlines/section/topic/SPORTS?$base"
            rxAny(prompt, "econom", "negocio", "finanz") -> sections += "Economía" to "https://news.google.com/rss/headlines/section/topic/BUSINESS?$base"
            rxAny(prompt, "tecnolog", "ciencia") -> sections += "Tecnología" to "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?$base"
            rxAny(prompt, "espect[aá]culo", "far[aá]ndula", "entreten") -> sections += "Espectáculos" to "https://news.google.com/rss/headlines/section/topic/ENTERTAINMENT?$base"
            rxAny(prompt, "salud") -> sections += "Salud" to "https://news.google.com/rss/headlines/section/topic/HEALTH?$base"
            else -> {
                if (wantsNational) sections += "Nacionales" to "https://news.google.com/rss/headlines/section/geo/$gl?$base"
                if (wantsWorld || rxAny(prompt, " e internacional", " y internacional", "\\bhe internacional"))
                    sections += "Internacionales" to "https://news.google.com/rss/headlines/section/topic/WORLD?$base"
            }
        }
        val countryName = named?.second ?: java.util.Locale("", gl).getDisplayCountry(Locale("es"))
        AiActivity.analyzing("📰 Descargando titulares de Google Noticias",
            "País: $countryName ($gl) · secciones: " + sections.joinToString(", ") { it.first })
        val seenNews = HashSet<String>()   // 🔁 cada noticia aparece una sola vez, aunque venga en varias secciones
        val seenStories = mutableListOf<Set<String>>()   // 🔁 y la MISMA noticia contada por dos medios con otras palabras, también
        val sectionLists = mutableListOf<Pair<String, List<String>>>()   // 📋 para la lista que arma la app (opción B)
        val blocks = sections.mapNotNull { (name, url) ->
            var list = fetchGoogleNews(url)
            // Si la sección no responde, se busca la noticia por palabras (últimas 24 horas)
            if (list.isEmpty()) {
                val q = when (name) {
                    "Nacionales" -> countryName
                    "Internacionales" -> "noticias internacionales mundo"
                    else -> "$name $countryName"
                }
                list = fetchGoogleNews("https://news.google.com/rss/search?q=${java.net.URLEncoder.encode("$q when:1d", "UTF-8")}&$base")
            }
            if (list.isEmpty() && name == "Nacionales") list = fetchGoogleNews("https://news.google.com/rss?$base")
            list = list.filter { item ->
                if (!seenNews.add(newsKey(item))) return@filter false
                val words = storyWords(item)
                if (seenStories.any { sameStory(it, words) }) false else { seenStories.add(words); true }
            }
            if (list.isNotEmpty()) sectionLists += name to list
            if (list.isEmpty()) null else "Noticias $name" + (if (name == "Nacionales") " de " + java.util.Locale("", gl).getDisplayCountry(Locale("es")) else "") + " (Google Noticias, hoy):\n" + list.joinToString("\n")
        }
        if (blocks.isEmpty()) return null
        AiActivity.log("🔁 Quito las noticias repetidas", "${sectionLists.sumOf { it.second.size }} noticias distintas")
        // 🖼️ Fotos de las noticias (Bing Noticias): sus titulares también se pasan a la IA
        val photoQuery = when {
            sections.any { it.first == "Internacionales" } && sections.none { it.first == "Nacionales" } -> "noticias internacionales"
            sections.first().first == "Nacionales" -> "noticias " + java.util.Locale("", gl).getDisplayCountry(Locale("es"))
            else -> "noticias " + sections.first().first + " " + java.util.Locale("", gl).getDisplayCountry(Locale("es"))
        }
        // Las fotos NO se filtran contra el texto (antes se borraban justo porque esa noticia ya estaba
        // en la lista); solo se evita repetir la misma foto dos veces.
        val seenPhotos = HashSet<String>()
        // 🖼️ Una foto POR CADA titular de hoy (antes se buscaba siempre «noticias Chile» y salían
        //    las mismas fotos). Se buscan en paralelo y se evitan las fotos ya mostradas hace poco.
        val recentPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recent = recentPrefs.getString("recent_news_photos", "").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
        val headlines = blocks.flatMap { it.lines() }.filter { it.startsWith("• ") }
            .map { it.removePrefix("• ").replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim() }
            .filter { it.length > 15 && newsKey(it) !in recent }
            .take(8)
        AiActivity.log("🖼️ Buscando una foto para cada titular", "${headlines.size} búsquedas en Bing Noticias")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val perHeadline = try {
            headlines.map { h -> pool.submit<Pair<String, String>?> {
                runCatching { fetchNewsImages(h, gl, 1).firstOrNull()?.let { h to it.second } }.getOrNull()
            } }.mapNotNull { f -> runCatching { f.get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
        } finally { pool.shutdownNow() }
        val photos = (perHeadline.ifEmpty { runCatching { fetchNewsImages(photoQuery, gl) }.getOrDefault(emptyList()) })
            .filter { seenPhotos.add(newsKey(it.first)) }
            .distinctBy { runCatching { java.io.File(it.second).length() }.getOrDefault(0L) }   // misma foto = mismo tamaño
            .take(4)
        // Se recuerdan las últimas ~40 noticias con foto para no repetirlas en el próximo pedido
        recent.addAll(0, photos.map { newsKey(it.first) })
        recentPrefs.edit().putString("recent_news_photos", recent.distinct().take(40).joinToString("\n")).apply()
        photos.forEach { replyImages.add(it.second) }
        AiActivity.log("📸 ${photos.size} foto(s) listas")
        val photoBlock = if (photos.isEmpty()) "" else "\n\nNoticias con foto (se muestran como imágenes debajo de tu respuesta, en este orden):\n" +
            photos.mapIndexed { i, ph -> "${i + 1}. ${ph.first}" }.joinToString("\n")
        // 📋 Opción B: la APP arma la lista final (titular, medio y foto), numerada y sin repetidos.
        //    La IA no la reescribe: así no repite noticias, no las mezcla y no inventa.
        runCatching {
            val photoKeys = photos.map { newsKey(it.first) }
            val matched = HashSet<String>()
            val all = mutableListOf<String>()
            val sb = StringBuilder("📰 Noticias de hoy")
            if (sections.any { it.first == "Nacionales" } || named != null) sb.append(" en ").append(countryName)
            sb.append(" · ").append(java.text.SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES")).format(java.util.Date()))
            var n = 0
            for ((name, list) in sectionLists) {
                val icon = when (name) {
                    "Nacionales" -> flagEmoji(gl)
                    "Internacionales" -> "🌍"; "Deportes" -> "⚽"; "Economía" -> "💰"
                    "Tecnología" -> "💻"; "Espectáculos" -> "🎬"; "Salud" -> "🩺"; else -> "🗞️"
                }
                sb.append("\n\n").append(icon).append(" ").append(name)
                for (item in list) {
                    n++
                    val line = item.removePrefix("• ").trim()
                    val key = newsKey(line)
                    val hasPhoto = key in photoKeys
                    if (hasPhoto) matched += key
                    all += line
                    sb.append("\n").append(n).append(". ").append(if (hasPhoto) "📸 " else "").append(line)
                }
            }
            // Fotos que no corresponden a un titular de la lista (fotos generales de noticias)
            val extra = photos.filter { newsKey(it.first) !in matched }
            if (extra.isNotEmpty()) sb.append("\n\n📸 Más noticias con foto:\n").append(extra.joinToString("\n") { "• ${it.first}" })
            if (photos.isNotEmpty()) sb.append("\n\n📸 Las fotos están abajo, en el mismo orden.")
            sb.append("\n\nPídeme «más detalles de la 3» (o el número que quieras) y te cuento esa noticia.")
            if (n > 0) { dailyDirect = sb.toString(); dailyDirectHeadlines = all }
        }
        return "Noticias reales de HOY que la app ya descargó de internet para ti (entrégaselas al usuario):\n\n" +
            blocks.joinToString("\n\n") + photoBlock +
            "\n\nInstrucción: resume estos titulares en una lista clara, agrupados por sección, mencionando el medio. " +
            "Cada noticia va UNA sola vez: no repitas la misma noticia ni la misma idea con otras palabras. " +
            "Usa SOLO estos titulares; no inventes noticias ni agregues datos que no aparecen aquí."
    }

    private fun weatherCodeEs(code: Int): String = when (code) {
        0 -> "despejado"; 1 -> "mayormente despejado"; 2 -> "parcialmente nublado"; 3 -> "nublado"
        45, 48 -> "niebla"; 51, 53, 55 -> "llovizna"; 56, 57 -> "llovizna helada"
        61 -> "lluvia débil"; 63 -> "lluvia moderada"; 65 -> "lluvia fuerte"; 66, 67 -> "lluvia helada"
        71 -> "nieve débil"; 73 -> "nieve moderada"; 75 -> "nieve fuerte"; 77 -> "granizo fino"
        80, 81, 82 -> "chubascos"; 85, 86 -> "chubascos de nieve"; 95 -> "tormenta"; 96, 99 -> "tormenta con granizo"
        else -> "condición desconocida"
    }

    // Ubicación: la ciudad que diga el usuario, o la aproximada por internet
    private fun weatherLocation(prompt: String): Triple<String, Double, Double>? {
        val city = Regex("(?i)\\b(?:en|de|para)\\s+([A-Za-zÁÉÍÓÚÑáéíóúñ][A-Za-zÁÉÍÓÚÑáéíóúñ .-]{2,40})")
            .findAll(prompt).map { it.groupValues[1].trim().trimEnd('?', '.', '!') }
            .firstOrNull { !Regex("(?i)^(la |el |los |las )?(semana|mañana|hoy|tarde|noche|momento|ahora|fin de semana|d[ií]a|clima|tiempo)").containsMatchIn(it) }
        if (city != null) {
            httpGetText("https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1&language=es")?.let { (_, body) ->
                runCatching {
                    val r = org.json.JSONObject(body).getJSONArray("results").getJSONObject(0)
                    val name = r.optString("name") + (r.optString("country").let { if (it.isNotBlank()) ", $it" else "" })
                    return Triple(name, r.getDouble("latitude"), r.getDouble("longitude"))
                }
            }
        }
        httpGetText("https://ipwho.is/?lang=es")?.let { (_, body) ->
            runCatching {
                val o = org.json.JSONObject(body)
                if (o.optBoolean("success", false))
                    return Triple(o.optString("city") + ", " + o.optString("country"), o.getDouble("latitude"), o.getDouble("longitude"))
            }
        }
        return null
    }

    private fun weatherContext(prompt: String): String? {
        AiActivity.analyzing("🌤️ Buscando dónde quieres saber el clima")
        val (place, lat, lon) = weatherLocation(prompt) ?: return null
        AiActivity.log("🌤️ Consultando el clima en Open-Meteo", "$place · ${"%.3f".format(Locale.US, lat)}, ${"%.3f".format(Locale.US, lon)} · 7 días")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        val (_, body) = httpGetText(url) ?: return null
        return runCatching {
            val o = org.json.JSONObject(body)
            val c = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val days = d.getJSONArray("time")
            val sb = StringBuilder("Clima real en $place (Open-Meteo):\n")
            sb.append("Ahora: ${c.optDouble("temperature_2m")}°C, sensación ${c.optDouble("apparent_temperature")}°C, ")
                .append("${weatherCodeEs(c.optInt("weather_code"))}, humedad ${c.optInt("relative_humidity_2m")}%, ")
                .append("viento ${c.optDouble("wind_speed_10m")} km/h.\n")
            val names = listOf("Hoy", "Mañana", "Pasado mañana")
            val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outFmt = java.text.SimpleDateFormat("EEEE d", Locale("es", "ES"))
            for (i in 0 until days.length()) {
                val label = names.getOrNull(i) ?: runCatching { outFmt.format(inFmt.parse(days.getString(i))!!) }.getOrDefault(days.getString(i))
                sb.append("$label: ${weatherCodeEs(d.getJSONArray("weather_code").optInt(i))}, ")
                    .append("mín ${d.getJSONArray("temperature_2m_min").optDouble(i)}°C, máx ${d.getJSONArray("temperature_2m_max").optDouble(i)}°C, ")
                    .append("probabilidad de lluvia ${d.getJSONArray("precipitation_probability_max").optInt(i)}%.\n")
            }
            runCatching {
                val n = days.length()
                val labels = (0 until n).map { i -> names.getOrNull(i) ?: runCatching { outFmt.format(inFmt.parse(days.getString(i))!!) }.getOrDefault(days.getString(i)) }
                    .map { it.replaceFirstChar { ch -> ch.uppercase() } }
                renderWeatherCard(place, c.optDouble("temperature_2m"), c.optDouble("apparent_temperature"), c.optInt("weather_code"),
                    c.optInt("relative_humidity_2m"), c.optDouble("wind_speed_10m"), labels,
                    (0 until n).map { d.getJSONArray("weather_code").optInt(it) },
                    (0 until n).map { d.getJSONArray("temperature_2m_min").optDouble(it) },
                    (0 until n).map { d.getJSONArray("temperature_2m_max").optDouble(it) },
                    (0 until n).map { d.getJSONArray("precipitation_probability_max").optInt(it) })?.let { replyImages.add(it) }
            }
            // 📋 Opción B: la APP escribe el clima con los datos EXACTOS (la IA no cambia las cifras)
            runCatching {
                val n = days.length()
                val label = { i: Int -> (names.getOrNull(i) ?: runCatching { outFmt.format(inFmt.parse(days.getString(i))!!) }
                    .getOrDefault(days.getString(i))).replaceFirstChar { ch -> ch.uppercase() } }
                val code = { i: Int -> d.getJSONArray("weather_code").optInt(i) }
                val tMin = { i: Int -> d.getJSONArray("temperature_2m_min").optDouble(i) }
                val tMax = { i: Int -> d.getJSONArray("temperature_2m_max").optDouble(i) }
                val rain = { i: Int -> d.getJSONArray("precipitation_probability_max").optInt(i) }
                // ¿Qué día preguntó? (para el consejo) · ¿quiere la semana completa?
                val asked = when {
                    rxAny(prompt, "pasado ma[ñn]ana") -> 2
                    Regex("(?i)(?<!esta )\\bma[ñn]ana\\b").containsMatchIn(prompt) -> 1
                    else -> 0
                }.coerceAtMost((n - 1).coerceAtLeast(0))
                val wantsWeek = rxAny(prompt, "semana", "pr[oó]ximos d[ií]as", "\\b7 d[ií]as", "lunes", "martes", "mi[eé]rcoles",
                    "jueves", "viernes", "s[aá]bado", "domingo")
                val show = if (wantsWeek) n else minOf(3, n)
                val out = StringBuilder("🌤️ Clima en $place\n")
                out.append("Ahora: ${weatherEmoji(c.optInt("weather_code"))} ${numEs(c.optDouble("temperature_2m"))} °C ")
                    .append("(sensación ${numEs(c.optDouble("apparent_temperature"))} °C), ${weatherCodeEs(c.optInt("weather_code"))}\n")
                    .append("Humedad ${c.optInt("relative_humidity_2m")} % · viento ${numEs(c.optDouble("wind_speed_10m"))} km/h\n")
                for (i in 0 until show) {
                    out.append("\n${weatherEmoji(code(i))} ${label(i)}: ${weatherCodeEs(code(i))} · mín ${numEs(tMin(i))} °C · ")
                        .append("máx ${numEs(tMax(i))} °C · lluvia ${rain(i)} %")
                }
                if (n > 0) out.append("\n\n💡 Consejo para ${label(asked).lowercase(Locale("es", "ES"))}: ")
                    .append(weatherAdvice(code(asked), tMin(asked), tMax(asked), rain(asked)))
                dailyDirect = out.toString()
            }
            sb.append("\nInstrucción: responde solo lo que el usuario preguntó (ahora, mañana o la semana) usando EXACTAMENTE estos datos. ")
                .append("Da un consejo corto (paraguas, abrigo, protector solar). No inventes otras cifras.")
            sb.toString()
        }.getOrNull()
    }

    private fun currencyContext(): String? {
        AiActivity.analyzing("💵 Consultando el tipo de cambio", "open.er-api.com · base: 1 dólar (USD)")
        val (_, body) = httpGetText("https://open.er-api.com/v6/latest/USD") ?: return null
        return runCatching {
            val root = org.json.JSONObject(body)
            val r = root.getJSONObject("rates")
            // 💱 Moneda local según la tarjeta SIM / red (Chile → CLP aunque el teléfono esté en «Español (US)»)
            val local = runCatching { java.util.Currency.getInstance(Locale("", newsCountry())).currencyCode }.getOrNull()
                ?.takeIf { r.has(it) }
                ?: runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()?.takeIf { r.has(it) }
                ?: "USD"
            val codes = listOf(local, "CLP", "EUR", "ARS", "MXN", "PEN", "COP", "BRL").distinct().filter { r.has(it) }
            // 📋 Opción B: la APP escribe los valores EXACTOS (la IA no los toca)
            runCatching {
                val es = Locale("es", "ES")
                // 945,32 · 1.032,50 · 0,9215 (con 2 decimales como mínimo)
                val money = { v: Double ->
                    if (v >= 100) String.format(es, "%,.2f", v)
                    else String.format(es, "%,.4f", v).trimEnd('0').let { s ->
                        val dec = s.substringAfter(',', "")
                        if (dec.length < 2) s + "0".repeat(2 - dec.length) else s
                    }
                }
                val updated = runCatching {
                    val t = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(root.optString("time_last_update_utc"))!!
                    java.text.SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", es).format(t)
                }.getOrNull()
                val out = StringBuilder("💵 Tipo de cambio de hoy (valores referenciales)\n1 dólar (USD) equivale a:")
                codes.filter { it != "USD" }.forEach { code ->
                    val name = runCatching { java.util.Currency.getInstance(code).getDisplayName(es) }.getOrDefault(code)
                    out.append("\n• ").append(money(r.getDouble(code))).append(" ").append(code).append(" — ").append(name)
                }
                if (local != "EUR" && r.has("EUR")) out.append("\n\n💶 1 euro = ").append(money(r.getDouble(local) / r.getDouble("EUR"))).append(" ").append(local)
                if (updated != null) out.append("\n🕒 Actualizado: ").append(updated)
                dailyDirect = out.toString()
            }
            "Tipo de cambio actual (1 dólar USD):\n" + codes.joinToString("\n") { "• $it: ${r.getDouble(it)}" } +
                "\n1 euro = ${"%.2f".format(r.getDouble(local) / r.getDouble("EUR"))} $local\n" +
                "Instrucción: responde con estos valores exactos y aclara que son referenciales."
        }.getOrNull()
    }

    private fun foodContext(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val meal = when (h) { in 5..10 -> "desayuno"; in 11..15 -> "almuerzo"; in 16..19 -> "once o merienda"; else -> "cena" }
        return "Contexto: son las ${h}h, momento de $meal. Instrucción: recomienda 3 opciones concretas y variadas para $meal " +
            "(una rápida, una saludable y una más contundente), con ingredientes principales y tiempo aproximado. " +
            "Si el usuario dio gustos, ingredientes o restricciones, respétalos. Ofrece dar la receta de la que elija."
    }

    // ================= 📋 OPCIÓN B: LA APP HACE LO QUE MEJOR SABE HACER =================
    // Noticias, clima y divisas: la app arma la respuesta con los datos EXACTOS (lista, cifras, fotos, tarjeta).
    // La IA no los reescribe (los modelos pequeños repetían, mezclaban o cambiaban los datos).

    /** Respuesta lista para mostrar que dejó el último pedido diario (noticias, clima o divisas). */
    @Volatile private var dailyDirect: String? = null
    /** Titulares de la última lista de noticias (para el resumen corto de la IA). */
    @Volatile private var dailyDirectHeadlines: List<String> = emptyList()

    /** Bandera del país (🇨🇱) a partir de su código de 2 letras. */
    private fun flagEmoji(country: String): String {
        val cc = country.uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🏠"
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    private val storyStopWords = setOf("para", "como", "este", "esta", "estos", "estas", "sobre", "tras", "entre", "desde",
        "hasta", "pero", "porque", "cuando", "donde", "segun", "luego", "ante", "tiene", "tienen", "sera", "ser", "fue", "son",
        "dice", "dijo", "hace", "hoy", "ayer", "anos", "nuevo", "nueva", "todo", "todos", "otra", "otro", "sus", "los", "las", "del", "que", "una", "con")

    /** Palabras importantes de un titular (sin tildes, sin el medio entre paréntesis). */
    private fun storyWords(title: String): Set<String> = java.text.Normalizer.normalize(
        title.lowercase(Locale.ROOT).removePrefix("• ").replace(Regex("\\s*\\([^)]*\\)\\s*$"), ""),
        java.text.Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in storyStopWords }.toSet()

    /** ¿Es la misma noticia contada con otras palabras? (comparten la mayoría de sus palabras importantes) */
    private fun sameStory(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val common = a.intersect(b).size
        return common >= 3 && common.toDouble() / minOf(a.size, b.size) >= 0.6
    }

    /** Número con coma decimal y sin «,0» al final (18,5 · 22). */
    private fun numEs(v: Double): String {
        if (v.isNaN()) return "—"
        return String.format(Locale("es", "ES"), "%.1f", v).removeSuffix(",0").removeSuffix(".0")
    }

    /** Consejo corto calculado con los datos reales (sin la IA). */
    private fun weatherAdvice(code: Int, min: Double, max: Double, rain: Int): String {
        val tips = mutableListOf<String>()
        if (rain >= 50 || code in 51..67 || code in 80..82 || code >= 95) tips += "lleva paraguas ☔"
        else if (rain >= 30) tips += "por si acaso, ten un paraguas a mano 🌂"
        if (code in 71..77 || code == 85 || code == 86) tips += "cuidado con la nieve en el camino ❄️"
        if (!min.isNaN() && min <= 7) tips += "abrígate bien 🧥"
        else if (!min.isNaN() && min <= 12) tips += "lleva algo para abrigarte en la mañana y en la noche 🧥"
        if (!max.isNaN() && max >= 28) tips += "usa protector solar y toma agua 🧴"
        if (tips.isEmpty()) tips += "buen día para salir 🙂"
        return tips.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    /**
     * 📰 «Más detalles de la 3», «cuéntame la segunda noticia»… justo después de una lista de noticias.
     * Devuelve el titular elegido (o null si el mensaje no habla de una noticia de la lista).
     */
    private fun newsItemAsked(prompt: String, lastAssistant: String?): String? {
        if (lastAssistant == null || !lastAssistant.contains("📰 Noticias")) return null
        val p = prompt.lowercase(Locale.ROOT)
        if (p.split(Regex("\\s+")).size > 16) return null
        val ordinals = listOf("primer", "segund", "tercer", "cuart", "quint", "sext", "s[eé]ptim", "octav", "noven", "d[eé]cim")
        val num = Regex("\\b(?:la|el|noticia|n[uú]mero|titular|#)\\s*(?:n[uú]mero\\s*)?(\\d{1,2})\\b").find(p)?.groupValues?.get(1)?.toIntOrNull()
            ?: ordinals.indexOfFirst { Regex("\\b(?:la|el)\\s+$it").containsMatchIn(p) }.takeIf { it >= 0 }?.plus(1)
            ?: return null
        val items = Regex("(?m)^\\s*(\\d{1,2})\\.\\s+(?:📸\\s*)?(.+)$").findAll(lastAssistant)
            .associate { it.groupValues[1].toInt() to it.groupValues[2].trim() }
        return items[num]
    }

    /** ✍️ Resumen corto (1 o 2 frases) de los titulares, escrito por la IA. Si sale mal, no se muestra. */
    private fun newsSummary(headlines: List<String>, backend: String): String? {
        if (headlines.size < 2 || stopRequested) return null
        val sys = "Resumes titulares de noticias en español, en 1 o 2 frases. Usas SOLO los titulares dados: no agregas datos, cifras ni opiniones."
        val ask = "Titulares de hoy:\n" + headlines.take(10).joinToString("\n") { "- $it" } +
            "\n\nEscribe un resumen de 1 o 2 frases con lo más importante. Sin saludo, sin lista y sin copiar los titulares completos."
        val raw = runCatching { removeThinking(rawGenerate(sys, ask, 120, backend)) }.getOrNull() ?: return null
        val s = stripMarkdownSymbols(raw).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").trim()
        if (stopRequested || s.length !in 25..450) return null
        if (Regex("(?i)(no puedo|no tengo acceso|lo siento|como (modelo|ia)|i can'?t|i cannot)").containsMatchIn(s)) return null
        return s
    }

    /** Devuelve datos listos para la IA si el mensaje es un pedido diario conocido. */
    private fun dailyIntentContext(prompt: String, codeOnly: Boolean = false): String? {
        val online = isInternetEnabled()
        // 🛠️ Pedido de programación: «actualízate en…» y «busca…» solo cuentan si el mensaje EMPIEZA con esa orden
        //    («créame una app que busca recetas» es una app, no una búsqueda)
        if (codeOnly && !Regex("(?i)^\\s*(por favor\\s+|oye\\s+)?(actual[ií]zate|ponte al d[ií]a|aprende|estudia|inf[oó]rmate|investiga|" +
                "b[uú]sca(me|r)?|averigua(r)?|googlea(r)?|consulta en internet)\\b").containsMatchIn(prompt)) return null
        // 📚 "Actualízate en ..." → aprende de internet y guarda las notas
        learnTopic(prompt)?.let { topic ->
            if (!online) return null
            AiActivity.analyzing("📚 Aprendiendo de internet sobre «$topic»", "3 búsquedas: novedades, versión y documentación")
            val ctx = learnContext(topic) ?: return "Instrucción: el usuario pidió que te actualizaras sobre «$topic», pero la búsqueda en internet no devolvió resultados. Díselo con honestidad."
            pendingLearnTopic = topic
            return "Resultados de internet (aprendizaje): " + ctx
        }
        // 🔎 "Busca en internet ...", "investiga ...", "averigua ..." → la app busca directo
        val searchCmd = Regex("(?i)\\b(b[uú]sca(me|r)?|investiga(r)?|averigua(r)?|googlea(r)?|consulta en internet)\\b(\\s+(en|por)\\s+(internet|google|la web))?(\\s+(sobre|acerca de|informaci[oó]n (de|sobre)|info (de|sobre)|qu[eé] es|qui[eé]n es))?\\s+(.{3,})")
            .find(prompt)
        if (searchCmd != null && online && !rxAny(prompt, "\\bnoticia", "\\bclima\\b")) {
            val query = searchCmd.groupValues.last().trim().trimEnd('?', '.', '!')
            AiActivity.analyzing("🔎 Me pediste buscar en internet", "«$query»")
            val web = fetchWebContext(query)
            return if (web != null)
                "Resultados de internet obtenidos ahora para «$query»:\n\n$web\n\n" +
                    "Instrucción: el usuario pidió buscar esto. Entrégale la información encontrada de forma clara y ordenada, " +
                    "usando SOLO estos resultados, y menciona los sitios de donde sale. Si no alcanza, dilo."
            else "Instrucción: el usuario pidió buscar «$query» en internet, pero la búsqueda no devolvió resultados. " +
                "Díselo con honestidad y no inventes información."
        }
        // 🛠️ Pedido de programación: no se buscan noticias, clima, divisas ni comida
        if (codeOnly) return null
        return when {
            rxAny(prompt, "\\bnoticia", "\\btitulares", "qu[eé] (est[aá] pasando|pas[oó]) (hoy|en el mundo)") ->
                if (online) newsContext(prompt) else null
            rxAny(prompt, "\\bclima\\b", "\\btiempo (hace|har[aá]|va a hacer|para|de hoy|de mañana|en)", "\\bpron[oó]stico",
                "va a llover", "llover[aá]", "\\btemperatura", "hace (fr[ií]o|calor)", "har[aá] (fr[ií]o|calor)") ->
                if (online) weatherContext(prompt) else null
            rxAny(prompt, "precio del d[oó]lar", "\\bd[oó]lar (hoy|est[aá]|a cu[aá]nto)", "tipo de cambio", "cu[aá]nto (est[aá]|vale) el (d[oó]lar|euro)") ->
                if (online) currencyContext() else null
            rxAny(prompt, "qu[eé] (puedo|podr[ií]a|deber[ií]a) (comer|cocinar|almorzar|cenar|desayunar)", "recomi[eé]nda(me)? (algo de |qu[eé] )?(comer|comida|cocinar)",
                "qu[eé] (como|cocino|almuerzo|ceno|desayuno)( hoy)?\\b", "ideas? (de|para) (comida|almuerzo|cena|desayuno)") ->
                foodContext().also { AiActivity.log("🍽️ Ideas de comida según la hora del teléfono") }
            else -> null
        }
    }

    // ========================================================================
    // 📄 DOCUMENTOS: leer Word / Excel / PowerPoint / PDF y guardar respuestas como archivo
    // ========================================================================
    private fun xmlText(t: String): String = t
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    private fun zipEntries(file: File, filter: (String) -> Boolean): Map<String, String> {
        val out = sortedMapOf<String, String>(compareBy({ Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 }, { it }))
        java.util.zip.ZipFile(file).use { zip ->
            for (e in zip.entries()) {
                if (!e.isDirectory && filter(e.name))
                    out[e.name] = zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText()
            }
        }
        return out
    }

    private fun docxText(file: File): String {
        val xml = zipEntries(file) { it == "word/document.xml" }.values.firstOrNull() ?: return ""
        return xml.split("</w:p>").joinToString("\n") { p ->
            Regex("<w:t[^>]*>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL).findAll(p).joinToString("") {
                when {
                    it.value.startsWith("<w:tab") -> "\t"
                    it.value.startsWith("<w:br") -> "\n"
                    else -> xmlText(it.groupValues[1])
                }
            }
        }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun xlsxText(file: File): String {
        val entries = zipEntries(file) { it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet") }
        val shared = entries["xl/sharedStrings.xml"]?.let { ss ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(ss).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { xmlText(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val sb = StringBuilder()
        entries.filterKeys { it.startsWith("xl/worksheets/sheet") }.entries.forEachIndexed { i, (_, xml) ->
            sb.append("Hoja ${i + 1}:\n")
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
                val cells = Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[2]
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                    when {
                        attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        attrs.contains("t=\"inlineStr\"") -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { xmlText(it) } ?: ""
                        else -> v?.let { xmlText(it) } ?: ""
                    }
                }.toList()
                if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString("\t")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun pptxText(file: File): String =
        zipEntries(file) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.values.mapIndexed { i, xml ->
            "Diapositiva ${i + 1}:\n" + xml.split("</a:p>").joinToString("\n") { p ->
                Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(p).joinToString("") { xmlText(it.groupValues[1]) }
            }.replace(Regex("\n{2,}"), "\n").trim()
        }.joinToString("\n\n")

    private fun odfText(file: File): String {
        val xml = zipEntries(file) { it == "content.xml" }.values.firstOrNull() ?: return ""
        return xml.replace(Regex("</text:p>|</text:h>|<text:line-break/>"), "\n").replace(Regex("<text:tab/>"), "\t")
            .replace(Regex("<[^>]+>"), "").let { xmlText(it) }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun rtfText(file: File): String = file.readText(Charsets.ISO_8859_1)
        .replace(Regex("\\\\par[d]?"), "\n").replace(Regex("\\\\'([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "").replace(Regex("[{}]"), "").trim()

    private var pdfBoxReady = false
    private fun pdfText(file: File): String {
        if (!pdfBoxReady) {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
            pdfBoxReady = true
        }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            return com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc).trim()
        }
    }

    /** Texto de cualquier adjunto: archivos de texto, Word, Excel, PowerPoint, OpenDocument, RTF y PDF. */
    private fun readAttachmentText(f: File): String {
        val ext = f.extension.lowercase(Locale.US)
        val text = runCatching {
            when (ext) {
                "docx" -> docxText(f)
                "xlsx" -> xlsxText(f)
                "pptx" -> pptxText(f)
                "odt", "ods", "odp" -> odfText(f)
                "rtf" -> rtfText(f)
                "pdf" -> pdfText(f)
                else -> f.readText()
            }
        }.onFailure { Log.e(TAG, "No se pudo leer ${f.name}: ${it.message}") }.getOrDefault("")
        if (text.isBlank() && ext == "pdf")
            return "[Este PDF no tiene texto seleccionable (parece escaneado o es una imagen). Avisa al usuario que no se puede leer el texto.]"
        if (text.isBlank()) return "[No se pudo leer el contenido de este archivo.]"
        return text
    }

    // ---------- Guardar respuestas como archivo ----------
    private fun xmlEscape(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildDocx(text: String): ByteArray {
        val body = text.lines().joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${xmlEscape(line)}</w:t></w:r></w:p>"
        }
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
        )
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun buildPdf(text: String): ByteArray {
        val pageW = 595; val pageH = 842; val margin = 50
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, pageW - margin * 2)
            .setLineSpacing(2f, 1f).build()
        val doc = android.graphics.pdf.PdfDocument()
        val usable = pageH - margin * 2
        var line = 0; var pageNum = 1
        while (line < layout.lineCount) {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= usable) last++
            val c = page.canvas
            c.save()
            c.translate(margin.toFloat(), (margin - top).toFloat())
            c.clipRect(0, top, pageW, layout.getLineBottom(last))
            layout.draw(c)
            c.restore()
            doc.finishPage(page)
            line = last + 1; pageNum++
        }
        val bos = java.io.ByteArrayOutputStream()
        doc.writeTo(bos); doc.close()
        return bos.toByteArray()
    }

    /** Guarda el archivo dentro de la app (privado) y devuelve su Uri para abrir / compartir / descargar. */
    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val f = File(deliveriesDir(), name); f.writeBytes(bytes); privateUri(f)
    }.onFailure { Log.e(TAG, "No se pudo guardar: ${it.message}") }.getOrNull()

    private fun saveTextAsFile(rawText: String, format: String) {
        val text = stripMarkdownSymbols(rawText).replace("⏹️ (detenida)", "").trim()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().take(30).replace(' ', '_').ifBlank { "documento" }
        val (ext, mime) = when (format) {
            "docx" -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pdf" -> "pdf" to "application/pdf"
            else -> "txt" to "text/plain"
        }
        val name = "${title}_$stamp.$ext"
        thread {
            val bytes = runCatching {
                when (format) { "docx" -> buildDocx(text); "pdf" -> buildPdf(text); else -> text.toByteArray(Charsets.UTF_8) }
            }.getOrNull()
            val uri = bytes?.let { saveToDownloads(name, mime, it) }
            runOnUiThread {
                if (uri == null) {
                    cumulusToast(this, "❌ No se pudo guardar el archivo", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                postDeliveredFile("📄 Aquí tienes tu archivo listo: $name\nToca para abrirlo · ⬇️ para guardarlo en tu teléfono", deliveryTag(null, uri, mime, name))
            }
        }
    }

    // ¿El usuario pidió el resultado como archivo? → "docx", "pdf", "txt", "ask" (sin formato) o null
    private fun requestedFileFormat(prompt: String): String? {
        val p = prompt.lowercase(Locale.ROOT)
        val asksFile = Regex("(en|como|a|formato|archivo|documento)\\s+(de\\s+)?(word|docx|pdf|txt|texto plano)\\b").containsMatchIn(p) ||
            Regex("\\b(gu[aá]rda(lo|la|me)?|exp[oó]rta(lo|la)?|desc[aá]rga(lo|la)?|convi[eé]rte(lo|la)?|p[aá]sa(lo|la))\\b.*\\b(archivo|documento|word|pdf|txt)\\b").containsMatchIn(p) ||
            Regex("\\b(crea|haz|hazme|genera|gen[eé]rame|cr[eé]ame)\\s+(un|el)\\s+(archivo|documento)\\b").containsMatchIn(p)
        if (!asksFile) return null
        return when {
            Regex("\\b(word|docx)\\b").containsMatchIn(p) -> "docx"
            Regex("\\bpdf\\b").containsMatchIn(p) -> "pdf"
            Regex("\\b(txt|texto plano)\\b").containsMatchIn(p) -> "txt"
            else -> "ask"
        }
    }

    // Solo una orden de guardar ("guárdalo en Word") → se guarda la última respuesta sin preguntarle a la IA
    private fun isOnlySaveCommand(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT).trim()
        return p.length < 70 && Regex("^(por favor\\s+)?(gu[aá]rda(lo|la|me)?|exp[oó]rta(lo|la)?|desc[aá]rga(lo|la)?|convi[eé]rte(lo|la)?|p[aá]sa(lo|la)?|ponlo|d[aá]melo|hazlo)\\b").containsMatchIn(p)
    }

    private fun offerSaveAsFile(text: String, format: String) {
        if (format != "ask") { saveTextAsFile(text, format); return }
        val labels = arrayOf("📝 Word (.docx)", "📕 PDF (.pdf)", "📄 Texto (.txt)")
        val codes = arrayOf("docx", "pdf", "txt")
        AlertDialog.Builder(this)
            .setTitle("Guardar como archivo")
            .setItems(labels) { _, which -> saveTextAsFile(text, codes[which]) }
            .setNegativeButton("Cancelar", null)
            .show().also { styleDarkRoundDialog(it) }
    }

    // ========================================================================
    // 🎬 UNIR VIDEOS: junta varios clips en un solo video (sin perder calidad)
    // ========================================================================
    private fun wantsJoinVideos(prompt: String): Boolean =
        Regex("(?i)\\b(j[uú]nta(los|las|r)?|[uú]ne(los|las)?|unir|combina(los|r)?|fusiona(los|r)?|pega(los|r)?|haz(me)? un (solo )?video|un solo video|montaje)\\b")
            .containsMatchIn(prompt)

    /**
     * Copia los clips uno detrás de otro con MediaExtractor + MediaMuxer.
     * No vuelve a comprimir: es rápido y mantiene la calidad, pero los clips deben
     * tener el mismo formato (lo normal si son de la misma cámara).
     */
    private fun joinVideos(clips: List<File>, out: File): Long {
        val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1; var audioTrack = -1
        var offsetUs = 0L
        val buffer = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        try {
            // Formatos y giro tomados del primer clip
            android.media.MediaExtractor().apply { setDataSource(clips[0].absolutePath) }.let { ex ->
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i); val mime = f.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = muxer.addTrack(f)
                    if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = muxer.addTrack(f)
                }
                ex.release()
            }
            runCatching {
                val r = android.media.MediaMetadataRetriever(); r.setDataSource(clips[0].absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { muxer.setOrientationHint(it) }
                r.release()
            }
            if (videoTrack < 0) throw IllegalStateException("El primer archivo no tiene video")
            muxer.start()
            for (clip in clips) {
                var clipEndUs = 0L
                for (kind in listOf("video/", "audio/")) {
                    val target = if (kind == "video/") videoTrack else audioTrack
                    if (target < 0) continue
                    val ex = android.media.MediaExtractor(); ex.setDataSource(clip.absolutePath)
                    val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith(kind) }
                    if (idx == null) { ex.release(); continue }
                    ex.selectTrack(idx)
                    while (true) {
                        info.offset = 0
                        info.size = ex.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        info.presentationTimeUs = ex.sampleTime + offsetUs
                        info.flags = if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(target, buffer, info)
                        clipEndUs = maxOf(clipEndUs, info.presentationTimeUs + 1)
                        ex.advance()
                    }
                    ex.release()
                }
                // El siguiente clip empieza justo donde terminó este (≈ 1 cuadro de separación)
                offsetUs = clipEndUs + 33_000
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        return offsetUs / 1_000_000
    }

    private fun saveVideoToGallery(file: File, name: String): Uri? = runCatching {
        val f = File(deliveriesDir(), name)
        file.copyTo(f, overwrite = true)
        privateUri(f)
    }.getOrNull()

    /** Une los videos adjuntos y muestra el resultado en el chat. */
    private fun runJoinVideos(clips: List<File>, thinkingIndex: Int) {
        AiActivity.start(thinkingIndex, AiActivity.ANALYZING)
        AiActivity.log("🎬 Uniendo ${clips.size} videos en uno solo", clips.joinToString("\n") { it.name.substringAfter('_') })
        chatAdapter?.notifyItemChanged(thinkingIndex)
        thread {
            val start = System.currentTimeMillis()
            val out = File(cacheDir, "union_${System.currentTimeMillis()}.mp4")
            val result = runCatching { joinVideos(clips, out) }
            val name = "CumulusIA_union_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".mp4"
            val uri = if (result.isSuccess) saveVideoToGallery(out, name) else null
            val thumb = if (uri != null) videoThumb(out) else null
            runOnUiThread {
                val secs = (System.currentTimeMillis() - start) / 1000.0
                val text = if (uri != null) {
                    "🎬 ¡Listo! Uní tus ${clips.size} videos en uno solo de unos ${result.getOrDefault(0L)} segundos.\n\n" +
                        "Toca el video para verlo · ⬇️ para guardarlo en tu teléfono."
                } else {
                    Log.e(TAG, "Unión de videos falló: ${result.exceptionOrNull()?.message}")
                    "⚠️ No pude unir los videos. Esto pasa cuando los clips tienen formatos distintos " +
                        "(por ejemplo, diferente resolución, cámara o app de origen). Prueba con clips grabados con la misma cámara."
                }
                AiActivity.finish(if (uri != null) "Video listo" else "No se pudo unir los videos")
                chatMessages[thinkingIndex] = ChatMessage(text = text, isUser = false, sender = "Cumulus IA",
                    tokens = "🎬 ${clips.size} videos · ${String.format(Locale.US, "%.1f", secs)} s",
                    attachments = if (uri != null) listOf(deliveryTag(thumb, uri, "video/mp4", name)) else emptyList())
                chatAdapter?.notifyItemChanged(thinkingIndex)
                rvChatMessages?.scrollToPosition(thinkingIndex)
                isAiBusy = false
                updateSendButtonIcon()
                saveCurrentChat()
                runCatching { out.delete() }
            }
        }
    }

    // ========================================================================
    // 🎞️ CREAR VIDEO CLIP: imágenes gratis por internet (Pollinations) + animación en el teléfono
    // ========================================================================
    private fun wantsCreateVideo(prompt: String): Boolean =
        Regex("(?i)\\b(crea(me)?|haz(me)?|genera(me)?|gen[eé]rame|cr[eé]ame|produce|arma(me)?)\\s+(un|una)\\s+(peque[ñn]o\\s+)?(video|v[ií]deo|clip|videoclip|animaci[oó]n)\\b")
            .containsMatchIn(prompt) &&
            // 💻 «Crea una animación en CSS / JavaScript / Kotlin» es código, no un video
            !Regex("(?i)\\b(html|css|javascript|js|kotlin|java|python|c[oó]digo|app|aplicaci[oó]n|programa|script|p[aá]gina web|sitio web|android|xml|react|flutter|svg|canvas|lottie)\\b").containsMatchIn(prompt)

    /** 💻 ¿Es un pedido de programación? (app, página web, juego, calculadora, HTML, Kotlin...) */
    private fun looksLikeCodeRequest(prompt: String): Boolean =
        Regex("(?i)\\b(html|css|javascript|js|typescript|kotlin|java|python|php|sql|react|vue|angular|flutter|dart|swift|node|json|xml|apis?|" +
            "web|p[aá]ginas?|sitios?|landing|apps?|aplicaci[oó]n(es)?|programas?|software|scripts?|c[oó]digos?|funci[oó]n(es)?|clases?|calculadoras?|" +
            "formularios?|login|base de datos|bots?|android|layouts?|widgets?|extensi[oó]n(es)?|plugins?|algoritmos?|juegos?|videojuegos?|chatbots?|crud|dashboards?)\\b")
            .containsMatchIn(prompt) || Regex("(?i)c\\+\\+|c#|\\.(kt|js|html?|css|py)\\b").containsMatchIn(prompt)

    /** 📋 ¿El mensaje trae código pegado (por ejemplo, el formato de la app que quiere)? */
    private fun hasPastedCode(prompt: String): Boolean {
        if (prompt.contains("```")) return true
        val lines = prompt.lines().filter { it.isNotBlank() }
        if (lines.size < 3) return false
        val codeLike = lines.count {
            Regex("^\\s*(<[a-zA-Z!?/]|import |package |fun |val |var |class |object |interface |def |function |const |let |public |" +
                "private |protected |return |if ?\\(|for ?\\(|while ?\\(|#include|@\\w|[}{\\])]|//|/\\*|\\*)|[;{}>]\\s*$").containsMatchIn(it)
        }
        return codeLike >= 3 || codeLike * 2 >= lines.size
    }

    /**
     * 🛠️ ¿Pide CREAR algo (app, página web, juego, programa…) o trae el código/formato de lo que quiere?
     * Si es así, siempre es programación: nunca noticias, clima, divisas, comida, imagen ni video.
     * En el modo Ingeniero de código basta con pedir «crea / haz / desarrolla…» (salvo que diga imagen o video).
     */
    private fun isCreationRequest(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        if (hasPastedCode(prompt)) return true
        val p = prompt.lowercase(Locale.ROOT)
        val verb = Regex("\\b(cr[eé]a(me|r|la|lo)?|haz(me|la|lo)?|hacer|genera(me|r)?|gen[eé]rame|desarrolla(me|r)?|programa(me|r)?|" +
            "constr[uú]ye(me)?|construir|arma(me)?|dise[ñn]a(me|r)?|implementa(me|r)?|escr[ií]be(me)?|codifica)\\b").containsMatchIn(p)
        val want = Regex("\\b(quiero|necesito|me gustar[ií]a)\\b").containsMatchIn(p)
        val code = looksLikeCodeRequest(p)
        if ((verb || want) && code) return true
        if (currentChatMode().key == "codigo" && verb) {
            // En modo código: «créame…» es programar, salvo que pida otra cosa (imagen, video, poema, noticias…)
            return !nonCodeThingsRx.containsMatchIn(p)
        }
        return false
    }

    /** Cosas que NO son programación (en el modo Ingeniero de código no se hacen). */
    private val nonCodeThingsRx = Regex("(?i)\\b(noticias?|titulares|clima|pron[oó]stico|d[oó]lar|euro|tipo de cambio|poemas?|poes[ií]as?|versos?|" +
        "cuentos?|historias?|canci[oó]n(es)?|chistes?|cartas?|correos?|emails?|ensayos?|res[uú]men(es)?|recetas?|comidas?|hor[oó]scopo|" +
        "frases?|datos? curiosos?|consejos?|dietas?|im[aá]gen(es)?|fotos?|fotograf[ií]as?|dibujos?|ilustraci[oó]n(es)?|fondos? de pantalla|" +
        "wallpapers?|videos?|v[ií]deos?|clips?|videoclips?|pel[ií]culas?|series?|m[uú]sica|partidos?|f[uú]tbol)\\b")

    /** Palabras de programación (preguntas de código aunque no pida crear: «¿qué es una variable?», «tengo un error»…). */
    private val codeTopicRx = Regex("(?i)\\b(variables?|bucles?|loops?|arrays?|arreglos?|strings?|objetos?|m[eé]todos?|par[aá]metros?|" +
        "excepci[oó]n(es)?|exceptions?|errores|error|bugs?|compila\\w*|gradle|librer[ií]as?|dependencias?|github|git|commits?|repositorios?|" +
        "servidor(es)?|backend|frontend|endpoints?|frameworks?|sdk|ide|android studio|depura\\w*|debug\\w*|logcat|crash\\w*|null\\w*|" +
        "programaci[oó]n|programar|desarrollador(es)?|deploy|build|apk|interfaz|ui|ux|componentes?|bot[oó]n|botones|pantallas?|activity|fragments?|" +
        "viewmodel|room|retrofit|firebase|corrutinas?|coroutines?|threads?|async|await|promesas?|promises?|npm|pip|terminal|consola|comandos?|linux|bash|" +
        "compilar|ejecutar|refactoriza\\w*|optimiza\\w*|sintaxis|indentaci[oó]n|tipado|interfaces?|herencia|polimorfismo|recursi[oó]n|regex|" +
        "solid|mvvm|mvc|patr[oó]n de dise[ñn]o|estructura de datos|base de datos)\\b")

    /** Palabras que SIEMPRE son programación («app de noticias», «código para el clima»…). */
    private val strongTechRx = Regex("(?i)\\b(html|css|javascript|js|typescript|kotlin|java|python|php|sql|react|vue|angular|flutter|dart|swift|" +
        "node|json|xml|apis?|apps?|aplicaci[oó]n(es)?|c[oó]digos?|scripts?|android|layouts?|programar|programaci[oó]n|p[aá]gina web|sitio web)\\b")

    /**
     * 💻 ¿El mensaje es de programación? (crear apps, páginas web o código; preguntas de código; código pegado;
     * seguir con lo que venían programando; archivos o capturas para programar)
     */
    private fun isCodeTopic(prompt: String, lastAssistant: String?, hasAttachments: Boolean): Boolean {
        if (prompt.isBlank()) return hasAttachments
        if (hasPastedCode(prompt)) return true
        val p = prompt.lowercase(Locale.ROOT)
        if (isCreationRequest(prompt)) return true                  // «créame una página web de noticias»
        if (strongTechRx.containsMatchIn(p)) return true            // «app de noticias», «tengo un error en mi app»
        val nonCode = nonCodeThingsRx.containsMatchIn(p)
        if (nonCode) return false                                   // «dame las noticias», «muéstrame una imagen», «un poema»
        if (looksLikeCodeRequest(p) || codeTopicRx.containsMatchIn(p)) return true   // «¿qué es una variable?»
        // Sigue con lo que venían programando («agrégale…», «corrígelo», «siguiente», «ok»)
        if (lastAssistant?.contains("```") == true && !nonCode &&
            (isFollowUp(prompt) || p.split(Regex("\\s+")).size <= 6)) return true
        // Con un archivo o una captura adjunta (el diseño a copiar, un error…), si no pide otra cosa
        return hasAttachments && !nonCode
    }

    /**
     * 💻 Modo Ingeniero de código: SOLO programación (crear apps, páginas web y código; corregir y explicar código).
     * Noticias, imágenes, videos, poemas, clima, etc. no se hacen en este modo: se le dice que cambie de modo.
     * Devuelve la respuesta para el usuario, o null si el pedido sí es de programación.
     */
    private fun codeModeGate(prompt: String, history: List<Pair<String, String>>, hasAttachments: Boolean): String? {
        if (currentChatMode().key != "codigo") return null
        val p = prompt.trim()
        val low = p.lowercase(Locale.ROOT)
        // Nombres, internet y lo aprendido se siguen respondiendo
        if (nameAnswer(p) != null) return null
        if (Regex("\\b(qu[eé] (has aprendido|aprendiste|temas sabes)|olvida|borra lo aprendido)\\b").containsMatchIn(low)) return null
        if (Regex("^[¿¡\\s]*(hola|holi|hey|buenas|buenos d[ií]as|buenas (tardes|noches)|qu[eé] tal|c[oó]mo est[aá]s)[!.?,¿¡\\s]*$").containsMatchIn(low))
            return "👋 ¡Hola! Estoy en modo Ingeniero de código. Dime qué app, página web o código quieres que cree, " +
                "o pégame el código que quieres que revise o corrija."
        if (Regex("^[¡\\s]*(muchas )?gracias[!.?,\\s]*$|^[¡\\s]*(ok|vale|genial|excelente|perfecto),? gracias[!.?,\\s]*$").containsMatchIn(low))
            return "¡De nada! 💻 ¿Qué más programamos?"
        val lastAssistant = history.lastOrNull { it.first == "assistant" }?.second
        if (isCodeTopic(p, lastAssistant, hasAttachments)) return null
        val what = when {
            Regex("\\bnoticias?|titulares").containsMatchIn(low) -> "ver las noticias"
            Regex("\\b(clima|pron[oó]stico|llover|temperatura)").containsMatchIn(low) -> "ver el clima"
            Regex("\\b(d[oó]lar|euro|tipo de cambio)").containsMatchIn(low) -> "ver el tipo de cambio"
            Regex("\\b(im[aá]gen|foto|dibuj|ilustra|fondo de pantalla|wallpaper)").containsMatchIn(low) -> "crear o mostrar imágenes"
            Regex("\\b(video|v[ií]deo|clip)").containsMatchIn(low) -> "crear videos"
            Regex("\\b(poema|poes[ií]a|verso|cuento|historia|canci[oó]n|chiste|carta|correo|ensayo|frase)").containsMatchIn(low) ->
                "escribir poemas, cuentos u otros textos"
            Regex("\\b(receta|comida|cocinar|almorzar|cenar|desayun)").containsMatchIn(low) -> "recetas y comida"
            else -> "eso"
        }
        return "💻 Estoy en modo Ingeniero de código: aquí solo creo apps, páginas web y código (y también corrijo o explico código).\n\n" +
            "Para $what cambia de modo: toca el botón «Ingeniero de código» de abajo y elige «General» o «Solo chat».\n\n" +
            "¿Qué app, página web o código quieres que cree?"
    }

    /**
     * 🖥️/📱 ¿Lo que pide se puede ver funcionando? "web" = vista previa web (HTML/CSS/JS),
     * "app" = maqueta de la app (Kotlin + diseños XML). null = otro tipo de código o no es código.
     */
    private fun codePreviewKind(prompt: String, lastAssistant: String?): String? {
        val p = prompt.lowercase(Locale.ROOT)
        if (Regex("\\b(html|css|javascript|js|p[aá]gina web|sitio web|web app|aplicaci[oó]n web|app web|landing|navegador)\\b").containsMatchIn(p)) return "web"
        if (Regex("\\b(kotlin|android|jetpack|compose|xml|layout|apk)\\b").containsMatchIn(p)) return "app"
        // Continuación («agrégale un botón») de un código que ya estaba en el chat
        if (lastAssistant != null && lastAssistant.contains("```") && isFollowUp(prompt)) {
            if (Regex("(?i)```(html|htm|css|js|javascript)\\b|<!doctype html|<html[\\s>]").containsMatchIn(lastAssistant)) return "web"
            if (Regex("(?i)```(kotlin|kt|xml)\\b|<LinearLayout|ConstraintLayout").containsMatchIn(lastAssistant)) return "app"
        }
        // En modo Ingeniero de código, «créame una app de …» sin decir el lenguaje → app Android
        if (currentChatMode().key == "codigo" && Regex("\\b(cr[eé]a|cr[eé]ame|haz|hazme|genera|desarrolla|programa)\\b").containsMatchIn(p) &&
            Regex("\\b(app|aplicaci[oó]n)\\b").containsMatchIn(p)) return "app"
        return null
    }

    // Lo que hay que dibujar: se quita la orden ("crea un video de...")
    private fun videoSubject(prompt: String): String =
        prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("[\"“”«»]"), "").replace(Regex("(?i)^.*?\\b(video|v[ií]deo|clip|videoclip|animaci[oó]n)\\b\\s*(corto\\s+)?(de|del|sobre|con|que muestre|mostrando)?\\s*"), "")
            .trim().trimEnd('.', '!', '?').ifBlank { prompt }

    private fun downloadImage(url: String): android.graphics.Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 90000
            setRequestProperty("User-Agent", "CumulusIA/1.0 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally { conn.disconnect() }
    }.getOrNull()

    /**
     * Pide las imágenes de cada escena a Pollinations (gratis, sin clave).
     * Se piden UNA POR UNA: el servicio gratuito rechaza varias peticiones a la vez.
     */
    private fun fetchScenes(subject: String, w: Int, h: Int, onProgress: (Int) -> Unit): List<android.graphics.Bitmap> {
        // 🎬 Se pide UNA sola imagen (el servicio gratuito no acepta varias seguidas, en CPU, NPU o GPU)
        //    y de ella salen las 4 escenas: plano general, medio, detalle y final con luz cálida.
        val reqW = 576; val reqH = 1024
        val prompt = "$subject, cinematic shot, highly detailed"
        val base = (System.currentTimeMillis() % 100000).toInt()
        var img: android.graphics.Bitmap? = null
        for (attempt in 0 until 4) {
            val url = "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
                "?width=$reqW&height=$reqH&seed=${base + attempt}&nologo=true"
            val (got, retryAfter) = downloadSceneImage(url)
            img = got
            if (img != null) break
            Thread.sleep(((retryAfter ?: (5L * (attempt + 1))).coerceIn(3L, 30L)) * 1000L)
        }
        val src = img ?: return emptyList()
        onProgress(1)
        fun crop(frac: Float, cy: Float): android.graphics.Bitmap {
            val cw = (src.width * frac).toInt().coerceAtLeast(1); val ch = (src.height * frac).toInt().coerceAtLeast(1)
            val x = ((src.width - cw) / 2).coerceAtLeast(0)
            val y = ((src.height * cy) - ch / 2f).toInt().coerceIn(0, src.height - ch)
            return android.graphics.Bitmap.createBitmap(src, x, y, cw, ch)
        }
        val warm = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { bm ->
            val cm = android.graphics.ColorMatrix(floatArrayOf(
                1.10f, 0f, 0f, 0f, 12f,
                0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 0.88f, 0f, -6f,
                0f, 0f, 0f, 1f, 0f))
            android.graphics.Canvas(bm).drawBitmap(src, 0f, 0f,
                android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) })
        }
        val scenes = listOf(src, crop(0.78f, 0.5f), crop(0.55f, 0.42f), warm)
        onProgress(4)
        return scenes
    }

    /** Descarga una escena; si el servicio dice «espera» (429/503), devuelve cuántos segundos esperar. */
    private fun downloadSceneImage(url: String): Pair<android.graphics.Bitmap?, Long?> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("User-Agent", "CumulusIA/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Pollinations escena HTTP $code")
                return@runCatching null to conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            }
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } to null
        } finally { conn.disconnect() }
    }.getOrElse { Log.w(TAG, "Pollinations escena: ${it.message}"); null to null }

    // Copia un cuadro ARGB a la imagen YUV del codificador (respeta el formato de cada teléfono)
    private fun writeFrameToImage(argb: IntArray, w: Int, h: Int, img: android.media.Image) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val uvRow = uP.rowStride; val uvPix = uP.pixelStride
        for (j in 0 until h) {
            val rowOff = j * w
            for (i in 0 until w) {
                val c = argb[rowOff + i]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yB.put(j * yRow + i, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val off = (j / 2) * uvRow + (i / 2) * uvPix
                    uB.put(off, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vB.put(off, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }

    /** Arma el MP4: cada imagen con zoom/movimiento suave y fundido entre escenas. */
    private fun renderSlideshow(images: List<android.graphics.Bitmap>, w: Int, h: Int, out: File,
                                sceneSecs: List<Float>? = null, onProgress: (Int) -> Unit) {
        val fps = 24; val fadeFrames = 12
        // Cada escena dura 3 s, o lo que dure su narración
        val framesPer = images.indices.map { i -> ((sceneSecs?.getOrNull(i) ?: 3f) * fps).toInt().coerceAtLeast(fps) }
        val starts = framesPer.runningFold(0) { a, b -> a + b }
        val total = starts.last()
        val mime = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        val format = android.media.MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = android.media.MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = android.media.MediaCodec.BufferInfo()
        val frame = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(frame)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val pixels = IntArray(w * h)

        fun drawScene(idx: Int, t: Float, alpha: Int) {
            val bmp = images[idx]
            val zoomIn = idx % 2 == 0
            val scale = (maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)) * (if (zoomIn) 1f + 0.15f * t else 1.15f - 0.15f * t)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            val panX = (if (idx % 3 == 1) -1f else 1f) * 0.04f * w * (t - 0.5f)
            val left = (w - dw) / 2f + panX; val top = (h - dh) / 2f
            paint.alpha = alpha
            canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
        }

        fun drain(endOfStream: Boolean) {
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (f in 0 until total) {
                val scene = (starts.indexOfLast { it <= f }).coerceIn(0, images.size - 1)
                val local = f - starts[scene]
                val perScene = framesPer[scene]
                canvas.drawColor(Color.BLACK)
                drawScene(scene, local / perScene.toFloat(), 255)
                // Fundido hacia la siguiente escena en el último medio segundo
                if (scene + 1 < images.size && local >= perScene - fadeFrames) {
                    val a = ((local - (perScene - fadeFrames) + 1) * 255 / fadeFrames).coerceIn(0, 255)
                    drawScene(scene + 1, 0f, a)
                }
                frame.getPixels(pixels, 0, w, 0, 0, w, h)
                var inIdx: Int
                do { inIdx = codec.dequeueInputBuffer(10_000); if (inIdx < 0) drain(false) } while (inIdx < 0)
                val img = codec.getInputImage(inIdx)!!
                writeFrameToImage(pixels, w, h, img)
                codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, f * 1_000_000L / fps, 0)
                drain(false)
                if (f % fps == 0) onProgress(f * 100 / total)
            }
            var eos: Int
            do { eos = codec.dequeueInputBuffer(10_000); if (eos < 0) drain(false) } while (eos < 0)
            codec.queueInputBuffer(eos, 0, 0, total * 1_000_000L / fps, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
            frame.recycle()
        }
    }

    /** Crea el clip completo y lo muestra en el chat. */
    private fun runCreateVideo(prompt: String, thinkingIndex: Int) {
        val subject = videoSubject(prompt)
        AiActivity.start(thinkingIndex, AiActivity.CREATING)
        AiActivity.log("🎞️ Creando un video de «$subject»")
        fun status(t: String) = runOnUiThread {
            AiActivity.log(t)
            if (thinkingIndex < chatMessages.size) {
                chatMessages[thinkingIndex] = ChatMessage(text = t, isUser = false, sender = "Cumulus IA", tokens = "")
                chatAdapter?.notifyItemChanged(thinkingIndex)
            }
        }
        thread {
            val start = System.currentTimeMillis()
            val w = 720; val h = 1280   // vertical, ideal para el teléfono
            status("🎨 Creando las escenas de tu video... (0/4)")
            val images = fetchScenes(subject, w, h) { n -> status("🎨 Creando las escenas de tu video... ($n/4)") }
            val out = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")
            var uri: Uri? = null
            var error: String? = null
            var clipThumb: String? = null
            var clipName = ""
            var hasVoice = false
            var hasMusic = false
            if (images.size < 2) {
                error = "No pude obtener las imágenes de internet (el servicio gratuito puede estar saturado). Inténtalo de nuevo en un momento."
            } else {
                // 🔊 Sonido del video: narración (ElevenLabs) + música de fondo, salvo que pidas lo contrario
                val low = prompt.lowercase(Locale.ROOT)
                val mute = Regex("\\b(mudo|sin sonido|sin audio)\\b").containsMatchIn(low)
                val wantVoice = !mute && !Regex("\\bsin (voz|narraci[oó]n)\\b").containsMatchIn(low)
                val wantMusic = !mute && !Regex("\\bsin m[uú]sica\\b").containsMatchIn(low)
                var narration: ShortArray? = null
                var sceneSecs: List<Float>? = null
                var voiceClips: List<ShortArray?> = emptyList()
                if (wantVoice) elevenKey()?.let { (key, voice) ->
                    status("✍️ Escribiendo la narración...")
                    val lines = narrationLines(subject, images.size)
                    status("🗣️ Grabando la voz con ElevenLabs...")
                    voiceClips = lines.map { elevenPcm(it, key, voice) }
                    if (voiceClips.any { it != null })
                        sceneSecs = voiceClips.map { c -> if (c == null) 3f else maxOf(3f, c.size / narrationRate.toFloat() + 0.8f) }
                }
                val secsList = sceneSecs ?: List(images.size) { 3f }
                if (sceneSecs != null || wantMusic) {
                    val totalSamples = (secsList.sum() * narrationRate).toInt() + narrationRate / 2
                    val voiceTrack = FloatArray(totalSamples)
                    if (sceneSecs != null) {
                        var offset = 0f
                        voiceClips.forEachIndexed { i, c ->
                            val st = ((offset + 0.3f) * narrationRate).toInt()
                            c?.forEachIndexed { k, v -> if (st + k < totalSamples) voiceTrack[st + k] = v / 32768f }
                            offset += secsList[i]
                        }
                    }
                    if (wantMusic) status("🎵 Componiendo la música de fondo...")
                    val music = if (wantMusic) composeMusic(totalSamples, subject) else null
                    narration = mixVoiceAndMusic(voiceTrack, music, sceneSecs != null)
                    hasVoice = sceneSecs != null; hasMusic = music != null
                }
                sceneSecs = secsList
                status("🎬 Animando y armando el video...")
                val silent = if (narration != null) File(cacheDir, "mudo_${System.currentTimeMillis()}.mp4") else out
                runCatching { renderSlideshow(images, w, h, silent, sceneSecs) { pct -> status("🎬 Armando el video... $pct%") } }
                    .onFailure { error = "No pude armar el video en este teléfono (${it.message})."; Log.e(TAG, "Clip: ${it.message}") }
                narration?.let { pcm ->
                    if (error == null) {
                        status("🔊 Agregando la voz al video...")
                        val m4a = File(cacheDir, "voz_${System.currentTimeMillis()}.m4a")
                        val ok = runCatching { encodePcmToM4a(pcm, m4a); muxVideoAudio(silent, m4a, out) }
                            .onFailure { Log.e(TAG, "Audio del clip: ${it.message}") }.isSuccess
                        if (!ok) { runCatching { silent.copyTo(out, overwrite = true) }; hasVoice = false; hasMusic = false }   // si falla el sonido, queda el video mudo
                        runCatching { m4a.delete() }
                    }
                    runCatching { silent.delete() }
                }
                if (error == null) {
                    val name = "CumulusIA_clip_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".mp4"
                    uri = saveVideoToGallery(out, name)
                    if (uri == null) error = "El video se creó pero no se pudo preparar para mostrarlo."
                    else { clipThumb = videoThumb(out); clipName = name }
                }
            }
            images.forEach { it.recycle() }
            runOnUiThread {
                val secs = (System.currentTimeMillis() - start) / 1000.0
                val text = error?.let { "⚠️ $it" } ?: ("🎬 ¡Tu video está listo! ${images.size} escenas animadas de «$subject» " +
                    (when {
                        hasVoice && hasMusic -> "con narración de tu voz de ElevenLabs y música de fondo"
                        hasVoice -> "con narración de tu voz de ElevenLabs"
                        hasMusic -> "con música de fondo"
                        else -> "(sin sonido)"
                    }) + ".\n\nToca el video para verlo · ⬇️ para guardarlo en tu teléfono.")
                val doneUri = uri
                AiActivity.finish(if (error == null) "Video listo" else "No se pudo crear el video")
                chatMessages[thinkingIndex] = ChatMessage(text = text, isUser = false, sender = "Cumulus IA",
                    tokens = "🎞️ Video clip · ${String.format(Locale.US, "%.1f", secs)} s\n🌐 Datos de: Pollinations (imágenes)",
                    attachments = if (doneUri != null) listOf(deliveryTag(clipThumb, doneUri, "video/mp4", clipName)) else emptyList())
                chatAdapter?.notifyItemChanged(thinkingIndex)
                rvChatMessages?.scrollToPosition(thinkingIndex)
                isAiBusy = false
                updateSendButtonIcon()
                saveCurrentChat()
                runCatching { out.delete() }
            }
        }
    }

    // ========================================================================
    // 🖼️ IMÁGENES: crear (gratis por internet), cambiar tamaño y mejorar (en el teléfono)
    // ========================================================================
    private fun wantsCreateImage(prompt: String): Boolean =
        Regex("(?i)\\b(crea(me)?|haz(me)?|genera(me)?|gen[eé]rame|cr[eé]ame|dibuja(me)?|dise[ñn]a(me)?)\\s+(un|una)\\s+(imagen|foto|fotograf[ií]a|dibujo|ilustraci[oó]n|fondo de pantalla|wallpaper|logo|p[oó]ster|retrato)\\b")
            .containsMatchIn(prompt) || wantsCreateImageShort(prompt)

    // "Créame una pareja en la playa", "dibújame un gato": también es una imagen,
    // salvo que pida un texto (correo, poema, código, receta, lista...) o un video
    private fun wantsCreateImageShort(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT).trim()
        if (!Regex("^(por favor\\s+)?(cr[eé]ame|dib[uú]jame|dibuja|gen[eé]rame|ilustra(me)?)\\s+(un|una|unos|unas|el|la)\\s+").containsMatchIn(p)) return false
        // 💻 «Créame una calculadora con HTML…», «créame un juego…», o estás en modo Ingeniero de código:
        //    es un pedido de PROGRAMACIÓN, no una imagen (antes salía una foto de una calculadora)
        val askedToCreate = Regex("^(por favor\\s+)?(cr[eé]ame|gen[eé]rame)\\b").containsMatchIn(p)
        if (askedToCreate && (currentChatMode().key == "codigo" || looksLikeCodeRequest(p))) return false
        if (Regex("\\b(html|css|javascript|kotlin|python|c[oó]digo)\\b").containsMatchIn(p)) return false
        return !Regex("\\b(video|v[ií]deo|clip|texto|correo|email|mensaje|carta|documento|archivo|c[oó]digo|programa|app|aplicaci[oó]n|poema|cuento|historia|canci[oó]n|chiste|receta|lista|plan|resumen|informe|tabla|presentaci[oó]n|contrase[ñn]a|nombre|frase|guion|gui[oó]n)\\b").containsMatchIn(p)
    }

    private fun imageSubject(prompt: String): String =
        prompt.replace(Regex("(?i)^.*?\\b(imagen|foto|fotograf[ií]a|dibujo|ilustraci[oó]n|fondo de pantalla|wallpaper|logo|p[oó]ster|retrato)\\b\\s*(de|del|sobre|con|que muestre)?\\s*"), "")
            .replace(Regex("(?i)^(por favor\\s+)?(cr[eé]ame|dib[uú]jame|dibuja|gen[eé]rame|ilustra(me)?)\\s+"), "")
            .trim().trimEnd('.', '!', '?').ifBlank { prompt }

    private enum class ImgOp { RESIZE, ENHANCE, EDIT, ERASE }

    private fun imageOperation(prompt: String): ImgOp? {
        val p = prompt.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(borra(le|r)?|elimina(le|r)?|quita(le|r)?|saca(le|r)?)\\b").containsMatchIn(p) -> ImgOp.ERASE
            Regex("\\b(tama[ñn]o|redimensiona|reduce|red[uú]cela|achica|agranda|ampl[ií]a|escala|\\d{2,5}\\s*[x×]\\s*\\d{2,5}|\\d{1,3}\\s*%|m[aá]s (peque[ñn]a|grande|chica))\\b").containsMatchIn(p) -> ImgOp.RESIZE
            Regex("\\b(mejora(la|r)?|nitidez|n[ií]tida|enfoca(la|r)?|calidad|restaura(la|r)?|hd|realza|aviva|m[aá]s clara|ilumina(la)?)\\b").containsMatchIn(p) -> ImgOp.ENHANCE
            Regex("\\b(c[aá]mbia(le|la|r)?|ponle|agr[eé]ga(le)?|a[ñn][aá]de(le)?|convi[eé]rte(la)?|transforma(la)?|hazla|edita(la|r)?|modifica(la|r)?|pinta(la|le)?)\\b").containsMatchIn(p) -> ImgOp.EDIT
            else -> null
        }
    }

    private fun loadBitmapUpright(file: File): android.graphics.Bitmap? = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
        val rot = runCatching {
            when (android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        }.getOrDefault(0f)
        if (rot == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            android.graphics.Matrix().apply { postRotate(rot) }, true)
    }.getOrNull()

    private fun resizeBitmap(src: android.graphics.Bitmap, prompt: String): android.graphics.Bitmap {
        val p = prompt.lowercase(Locale.ROOT)
        val exact = Regex("(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})").find(p)
        val pct = Regex("(\\d{1,3})\\s*%").find(p)?.groupValues?.get(1)?.toIntOrNull()
        val (w, h) = when {
            exact != null -> exact.groupValues[1].toInt() to exact.groupValues[2].toInt()
            pct != null -> (src.width * pct / 100) to (src.height * pct / 100)
            Regex("(peque[ñn]a|reduce|red[uú]cela|achica|chica)").containsMatchIn(p) -> src.width / 2 to src.height / 2
            else -> src.width * 2 to src.height * 2
        }
        val maxSide = 4096
        val f = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        return android.graphics.Bitmap.createScaledBitmap(src, (w * f).toInt().coerceAtLeast(16), (h * f).toInt().coerceAtLeast(16), true)
    }

    /** Mejora: la agranda si es pequeña, la enfoca y le da un poco más de contraste y color. */
    private fun enhanceBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val up = if (maxOf(src.width, src.height) < 1600)
            android.graphics.Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true) else src
        val w = up.width; val h = up.height
        val px = IntArray(w * h); up.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        // Enfoque (máscara de enfoque suave)
        val k = 0.6f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { out[i] = px[i]; continue }
            val c = px[i]; val n = px[i - w]; val s = px[i + w]; val e = px[i + 1]; val o = px[i - 1]
            fun ch(v: Int, sh: Int) = (v shr sh) and 0xff
            fun sharp(sh: Int): Int {
                val center = ch(c, sh); val avg = (ch(n, sh) + ch(s, sh) + ch(e, sh) + ch(o, sh)) / 4f
                return (center + k * (center - avg)).toInt().coerceIn(0, 255)
            }
            out[i] = (0xff shl 24) or (sharp(16) shl 16) or (sharp(8) shl 8) or sharp(0)
        }
        val sharpBmp = android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        // Contraste +8 % y saturación +15 %
        val result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cm = android.graphics.ColorMatrix().apply { setSaturation(1.15f) }
        val contrast = 1.08f; val t = (1f - contrast) * 128f
        cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        android.graphics.Canvas(result).drawBitmap(sharpBmp, 0f, 0f,
            android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) })
        sharpBmp.recycle()
        return result
    }

    private fun saveImageToGallery(bmp: android.graphics.Bitmap, name: String): Pair<Uri?, File?> {
        val f = File(deliveriesDir(), name)
        runCatching { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }
        if (!f.exists() || f.length() == 0L) return null to null
        return runCatching { privateUri(f) }.getOrNull() to f
    }

    private fun showImageResult(thinkingIndex: Int, text: String, tokens: String, uri: Uri?, thumb: File?) {
        AiActivity.finish(if (uri != null || thumb != null) "Imagen lista" else "No se pudo terminar la imagen")
        chatMessages[thinkingIndex] = ChatMessage(text = text, isUser = false, sender = "Cumulus IA", tokens = tokens,
            attachments = when {
                uri != null && thumb != null -> listOf(deliveryTag(thumb.absolutePath, uri, "image/jpeg", thumb.name))
                else -> listOfNotNull(thumb?.absolutePath)
            })
        chatAdapter?.notifyItemChanged(thinkingIndex)
        rvChatMessages?.scrollToPosition(thinkingIndex)
        isAiBusy = false
        updateSendButtonIcon()
        saveCurrentChat()
    }

    private fun imgName(tag: String) = "CumulusIA_${tag}_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".jpg"

    /** Crea una imagen nueva con Pollinations (gratis, sin clave). */
    private fun runCreateImage(prompt: String, thinkingIndex: Int) {
        val subject = imageSubject(prompt)
        val p = prompt.lowercase(Locale.ROOT)
        val (w, h) = when {
            Regex("fondo de pantalla|wallpaper|vertical|celular|historia|estado").containsMatchIn(p) -> 768 to 1344
            Regex("horizontal|panor[aá]mic|portada|banner").containsMatchIn(p) -> 1344 to 768
            else -> 1024 to 1024
        }
        AiActivity.start(thinkingIndex, AiActivity.CREATING)
        AiActivity.log("🎨 Creando tu imagen de «$subject»", "Tamaño: ${w}×$h")
        runOnUiThread {
            chatMessages[thinkingIndex] = ChatMessage(text = "🎨 Creando tu imagen...", isUser = false, sender = "Cumulus IA", tokens = "")
            chatAdapter?.notifyItemChanged(thinkingIndex)
        }
        thread {
            val start = System.currentTimeMillis()
            val url = "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode("$subject, high quality, detailed", "UTF-8").replace("+", "%20") +
                "?width=$w&height=$h&seed=${(System.currentTimeMillis() % 1000000)}&nologo=true&enhance=true"
            // Hasta 4 intentos respetando la espera que pida el servicio (antes solo 2 seguidos)
            var bmp: android.graphics.Bitmap? = null
            for (attempt in 0 until 4) {
                AiActivity.log(if (attempt == 0) "🌐 Pidiendo la imagen a Pollinations" else "🔁 Reintento ${attempt + 1} de 4 (el servicio estaba ocupado)",
                    if (attempt == 0) url.substringBefore("?").take(160) else "")
                val (got, retryAfter) = downloadSceneImage(if (attempt == 0) url else url.replace(Regex("seed=\\d+"), "seed=${System.currentTimeMillis() % 1000000 + attempt}"))
                bmp = got
                if (bmp != null) break
                Thread.sleep(((retryAfter ?: (3L * (attempt + 1))).coerceIn(2L, 20L)) * 1000L)
            }
            val (uri, thumb) = bmp?.let { saveImageToGallery(it, imgName("imagen")) } ?: (null to null)
            bmp?.recycle()
            runOnUiThread {
                val secs = (System.currentTimeMillis() - start) / 1000.0
                if (uri == null) showImageResult(thinkingIndex,
                    "⚠️ No pude crear la imagen (el servicio gratuito puede estar saturado). Inténtalo de nuevo en un momento.", "", null, null)
                else showImageResult(thinkingIndex, "🎨 Aquí está tu imagen de «$subject» (${w}×$h).",
                    "🖼️ Imagen · ${String.format(Locale.US, "%.1f", secs)} s\n🌐 Datos de: Pollinations (imágenes)", uri, thumb)
            }
        }
    }

    /** Cambia el tamaño o mejora la imagen adjunta, todo dentro del teléfono. */
    private fun runImageTool(op: ImgOp, image: File, prompt: String, thinkingIndex: Int) {
        AiActivity.start(thinkingIndex, AiActivity.ANALYZING)
        AiActivity.log(if (op == ImgOp.RESIZE) "📐 Cambiando el tamaño de la imagen" else "✨ Mejorando la imagen (nitidez, contraste y color)",
            image.name.substringAfter('_'))
        chatAdapter?.notifyItemChanged(thinkingIndex)
        thread {
            val start = System.currentTimeMillis()
            val src = loadBitmapUpright(image)
            var text: String; var uri: Uri? = null; var thumb: File? = null
            if (src == null) {
                text = "⚠️ No pude abrir esa imagen."
            } else {
                val result = runCatching { if (op == ImgOp.RESIZE) resizeBitmap(src, prompt) else enhanceBitmap(src) }.getOrNull()
                if (result == null) text = "⚠️ No pude procesar la imagen (puede ser demasiado grande)."
                else {
                    val saved = saveImageToGallery(result, imgName(if (op == ImgOp.RESIZE) "tamano" else "mejorada"))
                    uri = saved.first; thumb = saved.second
                    text = if (op == ImgOp.RESIZE) "📐 Listo: cambié el tamaño de ${src.width}×${src.height} a ${result.width}×${result.height}."
                    else "✨ Listo: mejoré la imagen (más nítida, con más contraste y color" +
                        (if (result.width > src.width) ", y la agrandé a ${result.width}×${result.height})." else ").")
                    if (result != src) result.recycle()
                }
                src.recycle()
            }
            runOnUiThread {
                val secs = (System.currentTimeMillis() - start) / 1000.0
                showImageResult(thinkingIndex, text, "🖼️ Editor de imágenes · ${String.format(Locale.US, "%.1f", secs)} s", uri, thumb)
            }
        }
    }

    // ========================================================================
    // 📷 CÁMARA EN VIVO: la IA mira tu entorno cuando le preguntas (y busca en internet si se lo pides)
    // ========================================================================
    private var cameraLiveActive = false
    private var cameraOverlay: android.widget.FrameLayout? = null
    private var cameraTexture: android.view.TextureView? = null
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var cameraSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraThread: android.os.HandlerThread? = null
    private var cameraAnswerView: TextView? = null
    private var cameraInput: EditText? = null
    private var skipNextCameraCapture = false
    private var pendingCameraSearch: String? = null
    private var cameraLastShownIdx = -1
    private var cameraLastSpokenText = ""
    private val cameraWatcher = android.os.Handler(android.os.Looper.getMainLooper())

    private fun wantsCameraWebSearch(prompt: String): Boolean =
        Regex("(?i)\\b(b[uú]sca(lo|la|me|r)?|investiga(lo)?|en internet|en google|cu[aá]nto cuesta|precio|d[oó]nde (lo )?compro|d[oó]nde comprar|m[aá]s informaci[oó]n)\\b").containsMatchIn(prompt)

    private fun openCameraLive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), TAKE_PHOTO_REQUEST)
            cumulusToast(this, "Da permiso de cámara y vuelve a tocar Cámara en vivo", Toast.LENGTH_LONG).show()
            return
        }
        if (cameraLiveActive) return
        val d = resources.displayMetrics.density
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
        }
        val texture = android.view.TextureView(this)
        overlay.addView(texture, android.widget.FrameLayout.LayoutParams(-1, -1))

        // ✕ cerrar
        overlay.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
            setOnClickListener { closeCameraLive() }
        }, android.widget.FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = (40 * d).toInt(); rightMargin = (16 * d).toInt()
        })
        overlay.addView(TextView(this).apply {
            text = "📷 Cámara en vivo"; textSize = 15f; setTextColor(Color.WHITE)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#99000000")) }
        }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START).apply {
            topMargin = (46 * d).toInt(); leftMargin = (16 * d).toInt()
        })

        // Panel inferior: respuesta + barra para preguntar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (24 * d).toInt())
        }
        val answer = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); maxLines = 8
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d; setColor(Color.parseColor("#CC000000")); setStroke((1 * d).toInt(), Color.parseColor("#3D5AFE"))
            }
            text = "Apunta a algo y pregúntame: «¿qué es esto?», «¿qué dice ahí?»… o «búscalo en internet»."
        }
        bottom.addView(answer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28 * d; setColor(Color.parseColor("#E61C1C1E")); setStroke((1 * d).toInt(), Color.parseColor("#48484A"))
            }
        }
        val input = EditText(this).apply {
            hint = "Pregunta sobre lo que ves..."; setHintTextColor(Color.parseColor("#8E8E93")); setTextColor(Color.WHITE)
            background = null; textSize = 15f; maxLines = 3
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        fun roundBtn(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
            setOnClickListener { onClick() }
        }
        bar.addView(roundBtn("🎙️", "#2C2C2E") { toggleChatVoiceRecognition() },
            LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = (6 * d).toInt() })
        bar.addView(roundBtn("➤", "#3D5AFE") {
            val q = input.text.toString().trim()
            if (q.isEmpty()) { cumulusToast(this, "Escribe o di tu pregunta", Toast.LENGTH_SHORT).show(); return@roundBtn }
            input.text.clear()
            hideKeyboardAndRestoreChat()
            etChatInput?.setText(q)
            btnSend?.performClick()
        }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        bottom.addView(bar, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(bottom, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))

        root.addView(overlay)
        cameraOverlay = overlay; cameraTexture = texture; cameraAnswerView = answer; cameraInput = input
        cameraLiveActive = true
        cameraLastShownIdx = chatMessages.size - 1
        cameraLastSpokenText = ""

        if (texture.isAvailable) startCameraPreview(texture)
        else texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) = startCameraPreview(texture)
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = fitPreview(texture)
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
        }
        startCameraWatcher()
        // 🎙️ Con la cámara abierta el micrófono escucha solo: le hablas y te responde en voz
        if (!isVoiceConversationActive) { cameraStartedVoice = true; setVoiceConversation(true) }
        if (!(activeModelHasVision())) {
            answer.text = "⚠️ Para entender lo que ves necesito un modelo que lea imágenes (por ejemplo Qwen3-VL-2B) con su mmproj. " +
                "Puedes abrir la cámara igual, pero no podré describir la imagen hasta que lo cargues."
        }
    }

    private fun activeModelHasVision(): Boolean = runCatching { (nativeIsModelLoaded() && nativeHasVision()) || GenieXEngineManager.hasVision() }.getOrDefault(false)

    private var previewW = 1920; private var previewH = 1080

    // Ajusta la imagen de la cámara para que llene la pantalla sin estirarse
    private fun fitPreview(texture: android.view.TextureView) {
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val portrait = vh > vw
        val bufW = if (portrait) previewH.toFloat() else previewW.toFloat()
        val bufH = if (portrait) previewW.toFloat() else previewH.toFloat()
        val scale = maxOf(vw / bufW, vh / bufH)
        val m = android.graphics.Matrix()
        m.setScale(bufW * scale / vw, bufH * scale / vh, vw / 2f, vh / 2f)
        texture.setTransform(m)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startCameraPreview(texture: android.view.TextureView) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val camId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run { cumulusToast(this, "No encontré cámara", Toast.LENGTH_SHORT).show(); return }
        val map = manager.getCameraCharacteristics(camId).get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        val best = sizes.filter { it.width <= 1920 && it.height <= 1080 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
        if (best != null) { previewW = best.width; previewH = best.height }
        val th = android.os.HandlerThread("camara").apply { start() }
        cameraThread = th
        val handler = android.os.Handler(th.looper)
        runCatching {
            manager.openCamera(camId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    cameraDevice = device
                    val st = texture.surfaceTexture ?: return
                    st.setDefaultBufferSize(previewW, previewH)
                    val surface = android.view.Surface(st)
                    val req = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            cameraSession = session
                            runCatching { session.setRepeatingRequest(req.build(), null, handler) }
                            runOnUiThread { fitPreview(texture) }
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            runOnUiThread { cumulusToast(this@MainActivity, "No se pudo iniciar la cámara", Toast.LENGTH_SHORT).show() }
                        }
                    }, handler)
                }
                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) { device.close(); cameraDevice = null }
            }, handler)
        }.onFailure { cumulusToast(this, "No se pudo abrir la cámara: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private var cameraStartedVoice = false

    private fun closeCameraLive() {
        cameraLiveActive = false
        if (cameraStartedVoice) { cameraStartedVoice = false; if (isVoiceConversationActive) setVoiceConversation(false) }
        pendingCameraSearch = null
        cameraWatcher.removeCallbacksAndMessages(null)
        runCatching { cameraSession?.close() }; cameraSession = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { cameraThread?.quitSafely() }; cameraThread = null
        cameraOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        cameraOverlay = null; cameraTexture = null; cameraAnswerView = null; cameraInput = null
    }

    /** Foto de lo que se ve ahora mismo (reducida para que la IA la lea rápido). */
    private fun captureCameraFrame(): File? = runCatching {
        val tv = cameraTexture ?: return@runCatching null
        val full = tv.bitmap ?: return@runCatching null
        val maxSide = 896f
        val f = minOf(1f, maxSide / maxOf(full.width, full.height))
        val bmp = if (f < 1f) android.graphics.Bitmap.createScaledBitmap(full, (full.width * f).toInt(), (full.height * f).toInt(), true) else full
        val dir = File(filesDir, "chat_images").apply { mkdirs() }
        val file = File(dir, "camara_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        if (bmp != full) bmp.recycle()
        full.recycle()
        file
    }.getOrNull()

    // Muestra en la cámara la respuesta que va escribiendo la IA y la lee en voz alta al terminar
    private fun startCameraWatcher() {
        cameraWatcher.removeCallbacksAndMessages(null)
        cameraWatcher.post(object : Runnable {
            override fun run() {
                if (!cameraLiveActive) return
                val last = chatMessages.lastOrNull()
                if (last != null && !last.isUser && chatMessages.size - 1 > cameraLastShownIdx) {
                    val t = stripMarkdownSymbols(removeThinking(last.text))
                    if (t.isNotBlank()) {
                        cameraAnswerView?.text = when {
                            !t.startsWith("Pensando") -> t
                            pendingCameraSearch != null -> "👀 Reconociendo lo que ves..."
                            else -> "⏳ Pensando..."
                        }
                        if (!isAiBusy && !t.startsWith("Pensando") && t != cameraLastSpokenText && pendingCameraSearch == null) {
                            cameraLastSpokenText = t
                            if (!isVocalResponseActive) {
                                if (chatTtsEngine == null) chatTtsEngine = TtsEngineManager(this@MainActivity)
                                replyTtsActive = true
                                if (isVoiceConversationActive) { isTtsSpeaking = true; currentSpokenReply = textForSpeech(t) }
                                chatTtsEngine?.speak(textForSpeech(t)) {
                                    replyTtsActive = false
                                    if (isTtsSpeaking) { isTtsSpeaking = false; ttsFinishedAt = System.currentTimeMillis() }
                                }
                            }
                        }
                    }
                }
                cameraWatcher.postDelayed(this, 400)
            }
        })
    }

    /** Después de reconocer el objeto, busca en internet lo que la IA identificó. */
    private fun continueCameraSearch(identified: String) {
        val original = pendingCameraSearch ?: return
        pendingCameraSearch = null
        val name = stripMarkdownSymbols(removeThinking(identified)).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("^(es |esto es |se trata de |veo )", RegexOption.IGNORE_CASE), "").trim().trimEnd('.').take(120)
        if (name.isBlank()) return
        cameraAnswerView?.text = "🔎 Buscando en internet: $name..."
        chatTtsEngine?.stop(); replyTtsActive = false; isTtsSpeaking = false
        skipNextCameraCapture = true
        etChatInput?.setText("Busca en internet $name. Pregunta del usuario: $original")
        btnSend?.performClick()
    }

    // ========================================================================
    // 🖼️ IMÁGENES PARA NOTICIAS Y CLIMA
    // ========================================================================
    private val replyImages = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun saveReplyBitmap(bmp: android.graphics.Bitmap, tag: String): String? = runCatching {
        val dir = File(filesDir, "chat_images").apply { mkdirs() }
        val f = File(dir, "${tag}_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        f.absolutePath
    }.getOrNull()

    /** Fotos de noticias desde Bing Noticias (trae miniaturas); se agregan debajo de la respuesta. */
    private fun fetchNewsImages(query: String, gl: String, max: Int = 4): List<Pair<String, String>> {
        val mkt = "es-" + gl
        val url = "https://www.bing.com/news/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=rss&setlang=es&mkt=$mkt"
        val (_, xml) = httpGetText(url) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            if (out.size >= max) return@forEach
            val item = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let { xmlUnescape(it) } ?: return@forEach
            var img = Regex("<News:Image>(.*?)</News:Image>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(item)?.groupValues?.get(1)?.let { xmlUnescape(it) }
                ?: Regex("<media:content[^>]*url=\"([^\"]+)\"").find(item)?.groupValues?.get(1)?.let { xmlUnescape(it) }
                ?: return@forEach
            if (img.startsWith("//")) img = "https:$img"
            if (img.contains("bing.com/th") && !img.contains("&w=")) img += "&w=640&h=360&c=7"
            val bmp = downloadImage(img) ?: return@forEach
            val path = saveReplyBitmap(bmp, "noticia")
            bmp.recycle()
            if (path != null) out += title to path
        }
        // Si Bing Noticias no trajo fotos, se buscan en Bing Imágenes (fotos de prensa del tema)
        if (out.isEmpty()) out += runCatching { fetchSearchImages("$query hoy", max) }.getOrDefault(emptyList())
        Log.i(TAG, "Fotos de noticias: ${out.size} para «$query»")
        return out
    }

    /** Respaldo: fotos desde Bing Imágenes (enlaces "murl" de la página de resultados). */
    private fun fetchSearchImages(query: String, max: Int): List<Pair<String, String>> {
        val (_, html) = httpGetText("https://www.bing.com/images/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&qft=+filterui:age-lt1440&setlang=es")
            ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val urls = Regex("murl&quot;:&quot;(https?://[^&]+?)&quot;").findAll(html).map { it.groupValues[1] }.distinct().take(max * 3).toList()
        val titles = Regex("t&quot;:&quot;(.*?)&quot;").findAll(html).map { xmlUnescape(it.groupValues[1]) }.toList()
        for ((i, u) in urls.withIndex()) {
            if (out.size >= max) break
            val bmp = downloadImage(u) ?: continue
            if (bmp.width < 200 || bmp.height < 120) { bmp.recycle(); continue }
            val path = saveReplyBitmap(bmp, "noticia")
            bmp.recycle()
            if (path != null) out += (titles.getOrNull(i) ?: query) to path
        }
        return out
    }

    private fun weatherEmoji(code: Int): String = when (code) {
        0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"; 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
    }

    /** Dibuja una tarjeta del clima (ahora + próximos días) como imagen. */
    private fun renderWeatherCard(place: String, nowTemp: Double, feels: Double, nowCode: Int, hum: Int, wind: Double,
                                  days: List<String>, codes: List<Int>, mins: List<Double>, maxs: List<Double>, rain: List<Int>): String? = runCatching {
        val w = 1080; val rows = days.size.coerceAtMost(7); val h = 560 + rows * 120 + 40
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val night = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it < 7 || it >= 20 }
        val grad = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            if (night) Color.parseColor("#0B1A3A") else Color.parseColor("#2F6BFF"),
            if (night) Color.parseColor("#1E2A55") else Color.parseColor("#7FB2FF"), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), 60f, 60f, android.graphics.Paint().apply { shader = grad; isAntiAlias = true })
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        p.textSize = 52f; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("📍 " + place.take(32), 60f, 100f, p)
        p.textSize = 180f
        c.drawText(weatherEmoji(nowCode), 60f, 320f, p)
        p.textSize = 170f
        c.drawText("${Math.round(nowTemp)}°", 330f, 310f, p)
        p.typeface = android.graphics.Typeface.DEFAULT; p.textSize = 46f
        c.drawText(weatherCodeEs(nowCode).replaceFirstChar { it.uppercase() }, 60f, 410f, p)
        p.color = Color.parseColor("#E6FFFFFF"); p.textSize = 38f
        c.drawText("Sensación ${Math.round(feels)}°  ·  💧 $hum%  ·  💨 ${Math.round(wind)} km/h", 60f, 470f, p)
        val line = android.graphics.Paint().apply { color = Color.parseColor("#55FFFFFF"); strokeWidth = 2f }
        c.drawLine(60f, 520f, (w - 60).toFloat(), 520f, line)
        for (i in 0 until rows) {
            val y = 600f + i * 120
            p.color = Color.WHITE; p.textSize = 44f; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            c.drawText(days[i], 60f, y, p)
            p.typeface = android.graphics.Typeface.DEFAULT; p.textSize = 60f
            c.drawText(weatherEmoji(codes.getOrElse(i) { 0 }), 470f, y + 6, p)
            p.textSize = 40f; p.color = Color.parseColor("#CCFFFFFF")
            c.drawText("💧${rain.getOrElse(i) { 0 }}%", 590f, y, p)
            p.color = Color.WHITE; p.textSize = 44f
            val mm = "${Math.round(mins.getOrElse(i) { 0.0 })}° / ${Math.round(maxs.getOrElse(i) { 0.0 })}°"
            c.drawText(mm, w - 60f - p.measureText(mm), y, p)
        }
        val path = saveReplyBitmap(bmp, "clima")
        bmp.recycle()
        path
    }.getOrNull()

    // ========================================================================
    // 📚 APRENDER DE INTERNET: "actualízate en Kotlin" → notas guardadas que la IA usa después
    // ========================================================================
    @Volatile private var pendingLearnTopic: String? = null

    private fun knowledgeDir() = File(filesDir, "conocimiento").apply { mkdirs() }

    private fun learnTopic(prompt: String): String? {
        val m = Regex("(?i)\\b(actual[ií]zate|ponte al d[ií]a|aprende|estudia|inf[oó]rmate|investiga y aprende)\\b\\s*(en|sobre|de|acerca de|lo [uú]ltimo de|lo nuevo de|las novedades de)?\\s+(.{2,60})")
            .find(prompt) ?: return null
        return m.groupValues[3].trim().trimEnd('.', '!', '?')
            .replace(Regex("(?i)^(lo [uú]ltimo de|lo nuevo de|las novedades de|la [uú]ltima versi[oó]n de)\\s+"), "").ifBlank { null }
    }

    private fun knowledgeSlug(topic: String) = java.text.Normalizer.normalize(topic.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9+#.]+"), "_").trim('_').take(40)

    private fun knowledgeTopics(): List<Pair<String, File>> = knowledgeDir().listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { f -> (f.readLines().firstOrNull()?.removePrefix("TEMA: ")?.trim() ?: f.nameWithoutExtension) to f }

    /** Datos de internet para aprender un tema (varias búsquedas sobre lo más nuevo). */
    private fun learnContext(topic: String): String? {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val queries = listOf(
            "$topic última versión novedades $year",
            "$topic what's new latest release $year",
            "$topic documentación oficial cambios importantes"
        )
        val parts = queries.mapNotNull { q -> runCatching { fetchWebContext(q) }.getOrNull() }
        if (parts.isEmpty()) return null
        return "Información de internet obtenida ahora sobre «$topic»:\n\n" + parts.joinToString("\n\n---\n\n").take(9000) +
            "\n\nInstrucción: con SOLO esa información escribe NOTAS TÉCNICAS actualizadas sobre «$topic» para usarlas después al programar. " +
            "Incluye: 1) versión más reciente y fecha si aparece, 2) novedades y cambios importantes, 3) cosas que quedaron obsoletas o cambiaron de nombre, " +
            "4) ejemplos cortos de código con la forma nueva de hacerlo, 5) fuentes. Usa listas con guiones, sé preciso y no inventes nada que no esté en la información."
    }

    private fun saveKnowledge(topic: String, notes: String) {
        val clean = stripMarkdownSymbols(removeThinking(notes)).trim()
        if (clean.length < 80) return
        val date = java.text.SimpleDateFormat("d 'de' MMMM yyyy", Locale("es", "ES")).format(java.util.Date())
        runCatching { File(knowledgeDir(), knowledgeSlug(topic) + ".txt").writeText("TEMA: $topic\nAPRENDIDO: $date\n\n$clean") }
    }

    /** Notas aprendidas que tienen que ver con la pregunta (se pegan al mensaje para la IA). */
    private fun knowledgeFor(prompt: String): String {
        val p = java.text.Normalizer.normalize(prompt.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val hits = knowledgeTopics().filter { (topic, _) ->
            val words = knowledgeSlug(topic).split('_').filter { it.length >= 2 }
            words.isNotEmpty() && words.all { w -> Regex("(^|[^a-z0-9])" + Regex.escape(w) + "([^a-z0-9]|$)").containsMatchIn(p) }
        }.take(2)
        if (hits.isEmpty()) return ""
        val per = 3500 / hits.size
        return hits.joinToString("\n") { (topic, f) ->
            "[Conocimiento ACTUALIZADO que aprendiste de internet sobre $topic (úsalo antes que tu memoria):\n" + f.readText().take(per) + "]"
        } + "\n"
    }

    /** Respuestas directas: qué aprendió / olvidar un tema. */
    private fun knowledgeCommand(prompt: String): String? {
        val p = prompt.lowercase(Locale.ROOT).trim()
        if (Regex("\\b(qu[eé] (has aprendido|aprendiste|temas sabes)|en qu[eé] te (has )?actualizado|lista de (lo )?aprendido)\\b").containsMatchIn(p)) {
            val t = knowledgeTopics()
            return if (t.isEmpty()) "📚 Todavía no he aprendido nada de internet. Pídeme, por ejemplo: «actualízate en Kotlin»."
            else "📚 Temas que aprendí de internet:\n" + t.joinToString("\n") { (topic, f) ->
                "• $topic (" + (f.readLines().getOrNull(1)?.removePrefix("APRENDIDO: ") ?: "") + ")"
            } + "\n\nPara borrar uno di: «olvida <tema>»."
        }
        Regex("^(olvida|borra lo aprendido de|elimina lo aprendido de)\\s+(.+)$").find(p)?.let { m ->
            val topic = m.groupValues[2].trim().trimEnd('.', '!')
            val f = File(knowledgeDir(), knowledgeSlug(topic) + ".txt")
            return if (f.exists() && f.delete()) "🗑️ Listo, olvidé lo aprendido sobre «$topic»." else "No tengo notas guardadas sobre «$topic»."
        }
        return null
    }

    // ========================================================================
    // 🎬 VER VIDEOS: cuadros repartidos en el tiempo para que la IA entienda lo que pasa
    // ========================================================================
    /** Devuelve hasta 6 cuadros del video (y un texto que explica en qué segundo va cada uno). */
    private fun extractVideoFrames(video: File, maxFrames: Int = 6): Pair<List<File>, String> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0) return emptyList<File>() to ""
            // Un cuadro cada ~2 s en videos cortos; en largos se reparten los 6
            val count = ((durMs / 2000L).toInt() + 1).coerceIn(2, maxFrames)
            val dir = File(cacheDir, "video_frames").apply { mkdirs() }
            val times = mutableListOf<String>()
            val files = (0 until count).mapNotNull { i ->
                val tMs = (durMs * (i + 0.5) / count).toLong()
                val bmp = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                        r.getScaledFrameAtTime(tMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                    else r.getFrameAtTime(tMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: return@mapNotNull null
                val f = File(dir, "cuadro_${System.currentTimeMillis()}_$i.jpg")
                runCatching { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
                bmp.recycle()
                times += String.format(Locale.US, "%.1f s", tMs / 1000.0)
                f.takeIf { it.exists() && it.length() > 0 }
            }
            val desc = "[Video «${video.name.substringAfter('_')}» de ${String.format(Locale.US, "%.1f", durMs / 1000.0)} segundos. " +
                "Te paso ${files.size} cuadros en orden, tomados en: ${times.joinToString(", ")}. " +
                "No tienes el audio del video, solo las imágenes.]"
            files to desc
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudieron sacar cuadros del video: ${t.message}")
            emptyList<File>() to ""
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Convierte cualquier imagen a JPG derecho y de tamaño razonable antes de dársela a la IA.
     * Así el motor (que solo entiende bien JPG/PNG) puede leer WEBP, HEIC, AVIF, GIF, BMP, ICO y fotos RAW (DNG...).
     */
    private fun normalizeImageForModel(src: File): File = runCatching {
        val maxSide = 1024
        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(src)) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val f = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            if (f < 1f) decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
        }
        // Fondo blanco para imágenes con transparencia (PNG, WEBP, ICO)
        val out = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        bmp.recycle()
        val dir = File(cacheDir, "img_ia").apply { mkdirs() }
        val f = File(dir, src.nameWithoutExtension.take(40) + "_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        f
    }.getOrElse {
        Log.w(TAG, "No se pudo convertir ${src.name}: ${it.message}")
        src
    }

    // ========================================================================
    // 📦 ENTREGA AL USUARIO: todo lo que la IA crea o modifica aparece en el chat como tarjeta
    //    (tocar = abrir · mantener presionado = compartir)
    // ========================================================================
    private fun deliveryTag(thumb: String?, uri: Uri, mime: String, name: String) =
        "deliver:${thumb.orEmpty()}|$uri|$mime|${name.replace("|", "_")}"

    private fun videoThumb(video: File): String? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val bmp = r.getScaledFrameAtTime(500_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
                ?: return@runCatching null
            saveReplyBitmap(bmp, "video").also { bmp.recycle() }
        } finally { r.release() }
    }.getOrNull()

    /** Agrega al chat un mensaje de la IA con el archivo listo para abrir o compartir. */
    private fun postDeliveredFile(text: String, tag: String) {
        chatMessages.add(ChatMessage(text = text, isUser = false, sender = "Cumulus IA", tokens = "📦 Archivo entregado",
            attachments = listOf(tag)))
        chatAdapter?.notifyItemInserted(chatMessages.lastIndex)
        rvChatMessages?.scrollToPosition(chatMessages.lastIndex)
        saveCurrentChat()
    }

    // ========================================================================
    // 🔒 Lo que crea la IA se guarda PRIVADO dentro de la app (no en tu galería).
    //    Solo va a tu teléfono si tocas la flecha ⬇️ de descarga.
    // ========================================================================
    private fun deliveriesDir() = File(filesDir, "entregas").apply { mkdirs() }

    private fun privateUri(f: File): Uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    // ========================================================================
    // 🗣️ NARRACIÓN DE VIDEOS con la voz de ElevenLabs
    // ========================================================================
    private val narrationRate = 24000   // ElevenLabs PCM 24 kHz mono

    // 🎙️ Audio → texto para NPU/GPU (GenieX no escucha audio): ElevenLabs Scribe v2 y, si no hay
    //    clave o falla, el reconocedor de voz del teléfono (sin internet, Android 13+).
    private val AUDIO_EXTS = setOf("wav", "mp3", "flac", "m4a", "ogg", "aac", "opus", "3gp", "amr", "webm")

    private fun audiosToText(files: List<File>): String {
        val sb = StringBuilder()
        for (f in files) {
            AiActivity.analyzing("🎙️ Escuchando el audio «${f.name.substringAfter('_')}»", "Paso el audio a texto (ElevenLabs Scribe o el teléfono)")
            val t = runCatching { transcribeWithEleven(f) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { transcribeOnDevice(f) }.getOrNull()?.takeIf { it.isNotBlank() }
            AiActivity.log(if (t != null) "📝 Audio pasado a texto" else "⚠️ No se pudo pasar el audio a texto", t?.take(200).orEmpty())
            sb.append("\n\n[Audio adjunto «").append(f.name).append("» — transcripción]:\n")
                .append(t ?: "(no se pudo transcribir este audio)")
        }
        return sb.toString()
    }

    private fun transcribeWithEleven(f: File): String? {
        val key = elevenKey()?.first ?: return null
        val boundary = "Boundary-" + System.currentTimeMillis()
        val conn = (URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; doInput = true; useCaches = false
            connectTimeout = 20000; readTimeout = 180000
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        BufferedOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model_id", "scribe_v2")
            field("tag_audio_events", "false")
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${f.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
            FileInputStream(f).use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (conn.responseCode !in 200..299) {
            Log.w("CumulusAudio", "Scribe HTTP ${conn.responseCode}: " +
                runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(300))
            conn.disconnect(); return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return org.json.JSONObject(body).optString("text").trim()
    }

    /** Decodifica cualquier audio a PCM 16 bits, mono, 16 kHz (lo que pide el reconocedor del teléfono). */
    private fun decodeToPcm16kMono(f: File): ByteArray? {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mono = java.io.ByteArrayOutputStream()
            val info = android.media.MediaCodec.BufferInfo()
            var inDone = false; var outDone = false; var pos = 0.0
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sh = buf.asShortBuffer()
                    val frames = sh.remaining() / ch.coerceAtLeast(1)
                    val step = rate / 16000.0
                    // Mezcla a mono y baja a 16 kHz (vecino más cercano: suficiente para voz)
                    while (pos < frames) {
                        val fi = pos.toInt(); var sum = 0
                        for (c in 0 until ch) sum += sh.get(fi * ch + c)
                        val v = (sum / ch).toShort()
                        mono.write(v.toInt() and 0xFF); mono.write((v.toInt() shr 8) and 0xFF)
                        pos += step
                    }
                    pos -= frames
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release()
            return mono.toByteArray().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w("CumulusAudio", "No se pudo decodificar ${f.name}", t); return null
        } finally { ex.release() }
    }

    /** Reconocedor de voz del teléfono leyendo el archivo (Android 13+, sin internet). */
    private fun transcribeOnDevice(f: File): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return null
        val pcm = decodeToPcm16kMono(f) ?: return null
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val latch = java.util.concurrent.CountDownLatch(1)
        val text = StringBuilder()
        var rec: SpeechRecognizer? = null
        fun take(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) text.append(if (text.isEmpty()) "" else " ").append(it.trim())
            }
        }
        runOnUiThread {
            try {
                rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onSegmentResults(segmentResults: Bundle) { take(segmentResults) }
                        override fun onEndOfSegmentedSession() { latch.countDown() }
                        override fun onResults(results: Bundle?) { take(results); latch.countDown() }
                        override fun onError(error: Int) { latch.countDown() }
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        // Sesión por segmentos: transcribe el audio completo, no solo la primera frase
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    })
                }
            } catch (t: Throwable) { Log.w("CumulusAudio", "Reconocedor no disponible", t); latch.countDown() }
        }
        thread {
            runCatching { android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } }
        }
        latch.await(pcm.size / 32000L + 60, java.util.concurrent.TimeUnit.SECONDS)
        runOnUiThread { runCatching { rec?.destroy() } }
        runCatching { pipe[0].close() }
        return text.toString().trim()
    }

    private fun elevenKey(): Pair<String, String>? {
        val p = getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        val key = p.getString("elevenlabs_api_key", "").orEmpty()
        if (key.isBlank()) return null
        return key to (p.getString("elevenlabs_voice_id", "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM")
    }

    /** Una frase corta por escena: la escribe la IA si hay un modelo cargado; si no, se usa una plantilla. */
    private fun narrationLines(subject: String, n: Int): List<String> {
        val ask = "Escribe la narración de un video corto sobre: $subject. Son $n escenas: 1) plano general, 2) plano medio, " +
            "3) detalle de cerca, 4) final. Escribe EXACTAMENTE $n frases en español, una por línea, sin números ni comillas, " +
            "cada una de 8 a 16 palabras, con tono cinematográfico y emotivo. Solo las frases."
        val sys = "Eres un guionista de videos cortos. Respondes solo con las frases pedidas."
        val raw = runCatching {
            val model = getActiveModelFile()
            when {
                GenieXEngineManager.isLoaded(model?.absolutePath) ->
                    runBlocking { GenieXEngineManager.generate(sys, listOf("user"), listOf(ask), 220, 0.8f) }
                nativeIsModelLoaded() -> {
                    nativeSetChatMode(sys, 220)
                    nativeRunChat(arrayOf("user"), arrayOf(ask))
                }
                else -> ""
            }
        }.getOrDefault("")
        val lines = stripMarkdownSymbols(removeThinking(raw)).lines()
            .map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[).:-]\\s*"), "").trim('"', '«', '»', ' ') }
            .filter { it.split(" ").size >= 4 }
        if (lines.size >= n) return lines.take(n)
        val s = subject.replaceFirstChar { it.uppercase() }
        return listOf(
            "$s. Una escena que nos invita a detenernos y mirar.",
            "Cada detalle cuenta una historia que vale la pena descubrir.",
            "De cerca, todo cobra una nueva vida.",
            "Y así, este momento queda grabado para siempre."
        ).take(n)
    }

    /** Pide a ElevenLabs la frase como audio PCM (16 bits, 24 kHz, mono). */
    private fun elevenPcm(text: String, key: String, voice: String): ShortArray? =
        elevenPcmModel(text, key, voice, "eleven_v4")   // solo el modelo más nuevo

    private fun elevenPcmModel(text: String, key: String, voice: String, model: String): ShortArray? = runCatching {
        val conn = (URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_$narrationRate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("text", text).put("model_id", model)
                .put("voice_settings", JSONObject().put("stability", 0.4).put("similarity_boost", 0.95)).toString().toByteArray())
        }
        if (conn.responseCode !in 200..299) { Log.e(TAG, "ElevenLabs narración: ${conn.responseCode}"); return@runCatching null }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val out = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        out
    }.getOrNull()

    /** Codifica el audio PCM a AAC (.m4a) para poder ponerlo dentro del MP4. */
    private fun encodePcmToM4a(pcm: ShortArray, out: File) {
        val rate = narrationRate
        val fmt = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        val info = android.media.MediaCodec.BufferInfo()
        var pos = 0; var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val count = minOf((buf.capacity() / 2), pcm.size - pos, 4096)
                        if (count <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, pos * 1_000_000L / rate, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bb = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bb.asShortBuffer().put(pcm, pos, count)
                            buf.put(bb)
                            codec.queueInputBuffer(inIdx, 0, count * 2, pos * 1_000_000L / rate, 0)
                            pos += count
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                    }
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        }
    }

    /** Junta el video (sin sonido) con el audio de la narración en un solo MP4. */
    private fun muxVideoAudio(video: File, audio: File, out: File) {
        val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vEx = android.media.MediaExtractor().apply { setDataSource(video.absolutePath) }
        val aEx = android.media.MediaExtractor().apply { setDataSource(audio.absolutePath) }
        try {
            val vIdx = (0 until vEx.trackCount).first { vEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val aIdx = (0 until aEx.trackCount).first { aEx.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            vEx.selectTrack(vIdx); aEx.selectTrack(aIdx)
            val vt = muxer.addTrack(vEx.getTrackFormat(vIdx)); val at = muxer.addTrack(aEx.getTrackFormat(aIdx))
            muxer.start()
            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            for ((ex, t) in listOf(vEx to vt, aEx to at)) {
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(t, buf, info)
                    ex.advance()
                }
            }
        } finally {
            runCatching { muxer.stop() }; runCatching { muxer.release() }
            vEx.release(); aEx.release()
        }
    }

    // ========================================================================
    // 🎵 MÚSICA DE FONDO: se compone dentro del teléfono (sin derechos de autor)
    // ========================================================================
    private fun composeMusic(samples: Int, subject: String): FloatArray {
        val sr = narrationRate.toFloat()
        val t = subject.lowercase(Locale.ROOT)
        val epic = Regex("drag[oó]n|batalla|guerra|acci[oó]n|carrera|[eé]pic|h[eé]roe|fuego|tormenta|monstruo|espacio|cohete").containsMatchIn(t)
        val sad = Regex("noche|triste|lluvia|misterio|terror|oscur|soledad|niebla|invierno|fantasma").containsMatchIn(t)
        fun hz(midi: Int) = 440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()
        // Progresiones de acordes (notas MIDI)
        val chords = when {
            epic -> listOf(listOf(45, 52, 57, 60), listOf(41, 48, 53, 57), listOf(43, 50, 55, 59), listOf(40, 47, 52, 56))   // Am F G E
            sad -> listOf(listOf(45, 52, 57, 60), listOf(41, 48, 53, 57), listOf(38, 45, 50, 53), listOf(40, 47, 52, 55))    // Am F Dm Em
            else -> listOf(listOf(48, 55, 60, 64), listOf(43, 50, 55, 59), listOf(45, 52, 57, 60), listOf(41, 48, 53, 57))   // C G Am F
        }
        val barSecs = if (epic) 2.0f else 3.0f
        val barLen = (barSecs * sr).toInt()
        val out = FloatArray(samples)
        val twoPi = (2 * Math.PI).toFloat()
        for (i in 0 until samples) {
            val bar = i / barLen
            val inBar = (i % barLen) / sr
            val chord = chords[bar % chords.size]
            val time = i / sr
            // Colchón suave (pad) con entrada y salida lenta en cada acorde
            val env = minOf(1f, inBar / 0.6f) * minOf(1f, (barSecs - inBar) / 0.6f)
            var v = 0f
            for (n in chord) {
                val f = hz(n)
                v += (kotlin.math.sin(twoPi * f * time) + 0.25f * kotlin.math.sin(twoPi * 2 * f * time)) * 0.09f * env
            }
            // Arpegio delicado (una nota cada 1/4 o 1/2 compás)
            val step = if (epic) 0.25f else 0.5f
            val k = (inBar / step).toInt()
            val since = inBar - k * step
            val arpNote = chord[(k % (chord.size - 1)) + 1] + 12
            v += kotlin.math.sin(twoPi * hz(arpNote) * time) * 0.07f * kotlin.math.exp(-since * 5f)
            // Pulso grave para escenas épicas
            if (epic) {
                val beat = (time % 0.5f)
                v += kotlin.math.sin(twoPi * 55f * beat) * 0.18f * kotlin.math.exp(-beat * 12f)
            }
            out[i] = v
        }
        // Entrada y salida suave de toda la música
        val fadeIn = (1.0f * sr).toInt(); val fadeOut = (1.8f * sr).toInt()
        for (i in 0 until minOf(fadeIn, samples)) out[i] *= i / fadeIn.toFloat()
        for (i in 0 until minOf(fadeOut, samples)) out[samples - 1 - i] *= i / fadeOut.toFloat()
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
        for (i in out.indices) out[i] = out[i] / peak * 0.45f
        return out
    }

    /** Mezcla voz + música: cuando habla la voz, la música baja sola para que se entienda. */
    private fun mixVoiceAndMusic(voice: FloatArray, music: FloatArray?, hasVoice: Boolean): ShortArray {
        val n = voice.size
        val out = ShortArray(n)
        var level = 0f
        val attack = 1f / (0.05f * narrationRate); val release = 1f / (0.5f * narrationRate)
        for (i in 0 until n) {
            val a = kotlin.math.abs(voice[i])
            level = if (a > level) level + (a - level) * attack * 40 else maxOf(0f, level - release)
            val duck = if (hasVoice) (1f - minOf(1f, level * 6f) * 0.7f) else 1f
            val m = (music?.getOrNull(i) ?: 0f) * (if (hasVoice) 0.55f else 1f) * duck
            out[i] = ((voice[i] + m).coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        return out
    }

    /** Convierte los datos que trajo la app (noticias, clima, búsqueda) en una respuesta legible, sin el modelo. */
    private fun dailyFallback(data: String): String {
        val body = data.substringBefore("\n\nInstrucción:").substringBefore("\nInstrucción:")
            .replace(Regex("(?m)^Noticias con foto.*$"), "📸 Con foto:")
            .replace("Resultados de internet obtenidos ahora para", "🔎 Esto encontré en internet sobre")
            .trim()
        return "Aquí tienes lo que encontré ahora mismo:\n\n$body"
    }

    // El antiguo modo agente (el modelo decide qué buscar) se deja apagado
    private val WEB_AGENT_MODE = false

    /** ¿Este mensaje necesita internet? (como LLM Hub: no se busca para saludos, cálculos ni textos creativos) */
    /**
     * 🧵 ¿El mensaje sigue el tema anterior? («ok expándelo», «agrégale la lógica», «y ahora el botón»,
     * «hazlo en Kotlin», «sigue», «corrígelo»…). Si es así, la IA debe continuar sobre su respuesta anterior.
     */
    private fun isFollowUp(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT).trim()
        if (p.isEmpty() || p.split(Regex("\\s+")).size > 14) return false
        val starts = Regex("^(ok|okay|vale|s[ií]|dale|bueno|perfecto|listo|ya|y|e|ahora|entonces|tambi[eé]n|pero|adem[aá]s|igual|otra vez|de nuevo)\\b").containsMatchIn(p)
        val verb = Regex("\\b(ag?r[eé]g|a[ñn]ad|implement|complet|contin[uú]|sigue|segu[ií]|termin|haz|hac|corrig|arregl|modific|cambi|mejor|optimiz|explic|repit|muestr|env[ií]|dame|d[aá]mel|p[aá]sa|escrib|pon|quit|elimin|borr|us[aá]|prueb|revis|exp[aá]nd|ampl[ií]|extiend|desarroll|detall|profundiz|actualiz|ajust|integr|incluy|conviert|tradu[cz]|resum|simplific|reduc|separ|un[ei]|junt|continu|termin)\\p{L}*").containsMatchIn(p)
        val pronoun = Regex("\\p{L}*[áéíóú]\\p{L}*(lo|la|los|las|le|les)(?!\\p{L})|\\b(hazlo|hazla|ponlo|ponla|dilo|dila|esto|eso|lo anterior|el c[oó]digo|la app|la l[oó]gica|lo mismo)\\b").containsMatchIn(p)
        val factual = Regex("\\b(noticias?|clima|precio|d[oó]lar|busca|google|internet|partido|resultados?|qui[eé]n es|cu[aá]ndo|d[oó]nde|qu[eé] pas[aó]|hoy|actual(es)?|[uú]ltim[oa]s?)\\b").containsMatchIn(p)
        return !factual && (pronoun || (verb && (starts || p.split(Regex("\\s+")).size <= 8)))
    }

    private fun needsWebSearch(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT).trim()
        if (isFollowUp(p)) return false
        if (p.length < 4) return false
        if (Regex("^(hola|holi|buenas|buenos d[ií]as|buenas (tardes|noches)|gracias|ok|okay|vale|chao|adi[oó]s|jaja\\S*|s[ií]|no|bien|genial|perfecto)[!.?\\s]*$").containsMatchIn(p)) return false
        if (Regex("^[\\d\\s+\\-*/().,=x^%]+\\??$").matches(p)) return false
        // 🧵 Continuar la conversación («ok agrégala», «hazlo», «sigue», «corrígelo», «agrega la lógica»…)
        //    no es una búsqueda: buscar borraba el historial y la IA repetía su respuesta anterior
        if (Regex("^(ok|okay|vale|s[ií]|dale|bueno|perfecto|listo|ya|y|ahora|entonces|tambi[eé]n)?[,\\s]*(ag?r[eé]ga|a[ñn]ade|implementa|completa|contin[uú]a|sigue|termina|haz|hace|corrige|arregla|modifica|cambia|mejora|optimiza|explica|repite|muestra|env[ií]a|dame|p[aá]same|escribe|crea|genera|pon|quita|elimina|borra|usa|prueba|revisa|exp[aá]nd|ampl[ií]a|extiende|desarrolla|detalla|profundiza|actualiza|ajusta|integra|incluye|convierte|traduce|resume|simplifica|reduce|separa|une|junta)\\w*").containsMatchIn(p) &&
            !Regex("\\b(noticias?|clima|tiempo|precio|d[oó]lar|hoy|actual|[uú]ltim[oa]s?|internet|busca|google|partido|resultados?|marcador|f[uú]tbol|qui[eé]n|cu[aá]ndo|d[oó]nde)\\b").containsMatchIn(p)) return false
        if (p.split(Regex("\\s+")).size <= 6 && Regex("(\\p{L}*[áéíóú]\\p{L}*(lo|la|los|las|le|les)(?!\\p{L})|\\b(hazlo|hazla|ponlo|ponla|dilo|dila|sigue|contin[uú]a)\\b)").containsMatchIn(p) &&
            !Regex("\\b(noticias?|clima|precio|d[oó]lar|busca|google|internet)\\b").containsMatchIn(p)) return false
        if (Regex("\\b(c[oó]digo|app|aplicaci[oó]n|funci[oó]n|clase|l[oó]gica|kotlin|java|python|html|css|javascript|script|programa|bot[oó]n|pantalla|archivo)\\b").containsMatchIn(p) &&
            !Regex("\\b(busca|investiga|en internet|google|qu[eé] es|novedades|[uú]ltima versi[oó]n)\\b").containsMatchIn(p)) return false
        if (Regex("\\b(escribe|esc[rí]beme|redacta|inventa|crea(me)? un (poema|cuento|chiste|texto|correo|mensaje)|cu[eé]ntame un (chiste|cuento)|trad[uú]ce|corrige|resume este|resume el|reescribe|mejora (este|el) texto|c[oó]mo te llamas|qui[eé]n eres|c[oó]mo est[aá]s)\\b").containsMatchIn(p)) return false
        return true
    }

    /** Deja solo lo que hay que buscar (quita "por favor", "dime", "búscame", etc.) */
    private fun cleanSearchQuery(prompt: String): String {
        val first = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val cleaned = first
            .replace(Regex("(?i)^(oye|hola|por favor|porfa)[,\\s]+"), "")
            .replace(Regex("(?i)\\b(por favor|porfa|me puedes|puedes|podr[ií]as|quiero saber|necesito saber|dime|cu[eé]ntame|b[uú]sca(me)?( en internet| en google)?|investiga|averigua|inf[oó]rmame( sobre)?)\\b"), " ")
            .replace(Regex("[¿?¡!]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return cleaned.ifBlank { first }.take(200)
    }

    /** Clave para reconocer la misma noticia aunque cambie el medio, las mayúsculas o los signos. */
    private fun newsKey(title: String): String = java.text.Normalizer.normalize(
        title.lowercase(Locale.ROOT).substringBefore(" (").replace(Regex("^[•\\-\\d.)\\s]+"), ""),
        java.text.Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().take(60)

    /**
     * 🔁 Quita lo que el modelo repite: líneas o párrafos iguales (o casi iguales) que ya dijo antes
     * en la misma respuesta. No toca los bloques de código.
     */
    private fun removeRepeatedLines(text: String): String {
        if (text.contains("```")) return text
        val seen = HashSet<String>()
        val out = StringBuilder()
        for (line in text.lines()) {
            val key = java.text.Normalizer.normalize(line.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            // Líneas cortas (títulos, viñetas vacías, separadores) se dejan siempre
            if (key.length >= 25 && !seen.add(key)) continue
            out.append(line).append('\n')
        }
        // Sin líneas en blanco de más donde se quitó algo
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd()
    }

    private fun agentToolInstructions(codeMode: Boolean): String =
        " HERRAMIENTAS DE INTERNET: puedes usar internet tú mismo. " +
            "Si la pregunta necesita información actual o que no sabes con seguridad (noticias, precios, clima, " +
            "resultados deportivos, personas, fechas, datos recientes, versiones o documentación), escribe solo esto y detente: " +
            "<buscar>palabras clave de búsqueda</buscar>. La app te devolverá los resultados con títulos, resúmenes y enlaces. " +
            "Para leer una página completa escribe: <abrir>https://enlace-completo</abrir>. " +
            "Puedes buscar o abrir hasta $maxAgentActions veces; si los resultados no sirven, busca con otras palabras. " +
            "Escribe búsquedas cortas y precisas, no copies la pregunta entera. " +
            "No anuncies que vas a buscar ni hables de tus búsquedas: escribe directamente la etiqueta <buscar>. " +
            "Si los resultados traen titulares o resúmenes con la información, úsalos para responder aunque no abras la página. " +
            (if (codeMode) "En temas de programación busca en la documentación oficial, Stack Overflow o GitHub. " else "") +
            "Cuando tengas la información, responde al usuario de forma completa y menciona las fuentes (nombre del sitio). " +
            "Si la pregunta no necesita internet (saludos, charla, opiniones o conocimiento general estable), responde directo sin buscar. " +
            "Nunca inventes resultados de búsqueda."

    private fun setStreamingStatus(text: String) {
        AiActivity.analyzing(text)   // ✦ también queda en la ventana de actividad
        val idx = streamingIndex
        if (idx < 0) return
        runOnUiThread {
            if (streamingIndex == idx && idx in chatMessages.indices && !chatMessages[idx].isUser) {
                chatMessages[idx] = chatMessages[idx].copy(text = text)
                chatAdapter?.notifyItemChanged(idx)
                rvChatMessages?.scrollToPosition(idx)
            }
        }
    }

    private fun agentPageBudget(): Int {
        val ctx = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("ai_context", 2048).coerceIn(2048, 15000)
        return (((ctx - 1500) * 2.5) / 4).toInt().coerceIn(1200, 3500)
    }

    private fun agentSearch(query: String): String {
        val results = searchWeb(query).take(6)
        if (results.isEmpty()) return "[Resultados de «$query»: no se encontró nada. Prueba con otras palabras.]"
        val list = results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.snippet.take(300)}\n   Enlace: ${r.url}"
        }.joinToString("\n")
        return "[Resultados de búsqueda para «$query»]\n$list"
    }

    private fun agentOpen(url: String, question: String): String {
        val clean = url.trim().trimEnd('.', ',', ')', ']')
        if (!clean.startsWith("http")) return "[No se pudo abrir «$clean»: el enlace debe empezar con https://]"
        val got = httpGetText(clean) ?: return "[No se pudo abrir $clean. Prueba con otro enlace de los resultados.]"
        val title = pageTitle(got.second).ifBlank { clean }
        val text = bestPassages(htmlToReadableText(got.second), question, agentPageBudget())
        if (text.isBlank()) return "[La página $clean no tiene texto legible. Prueba con otro enlace.]"
        return "[Contenido de la página «$title» (${got.first})]\n$text"
    }

    // Bucle: la IA pide búsquedas/páginas hasta tener la información y luego responde
    private fun runWebAgent(
        roles: MutableList<String>,
        contents: MutableList<String>,
        question: String,
        gen: (List<String>, List<String>) -> String = { r, c -> nativeRunChat(r.toTypedArray(), c.toTypedArray()) }
    ): Pair<String, Int> {
        var actions = 0
        while (true) {
            val raw = gen(roles, contents)
            if (stopRequested) return agentToolRegex.replace(raw, "").trim() to actions
            val m = agentToolRegex.find(raw) ?: return raw to actions
            val call = raw.substring(0, m.range.last + 1)
            roles.add("assistant"); contents.add(call)
            if (actions >= maxAgentActions) {
                roles.add("user")
                contents.add("[Ya no puedes usar más <buscar> ni <abrir>. Responde ahora al usuario con la información que ya tienes y menciona las fuentes.]")
                val last = gen(roles, contents)
                return agentToolRegex.replace(last, "").trim() to actions
            }
            actions++
            val kind = m.groupValues[1].lowercase()
            val arg = m.groupValues[2].trim().take(300)
            val result = if (kind == "buscar") {
                setStreamingStatus("🔎 Buscando: $arg")
                agentSearch(arg)
            } else {
                setStreamingStatus("📄 Leyendo: " + (runCatching { java.net.URI(arg.trim()).host }.getOrNull() ?: arg))
                agentOpen(arg, question)
            }
            Log.d(TAG, "🌐 Agente $kind «$arg» → ${result.length} caracteres")
            val left = maxAgentActions - actions
            roles.add("user")
            contents.add(
                result + "\n\n[Pregunta original: $question]\n" +
                    (if (left > 0) "[Si necesitas más, usa otra vez <buscar> o <abrir> (te quedan $left). " +
                        "Si ya tienes la información, responde al usuario y menciona las fuentes.]"
                    else "[Ya no puedes buscar más: responde ahora al usuario con esta información y menciona las fuentes.]")
            )
        }
    }

    // ⚡ STREAMING: el motor envía el texto mientras lo escribe
    @Volatile private var streamingIndex = -1
    @Volatile private var lastStreamUpdate = 0L

    @androidx.annotation.Keep
    fun onNativeToken(bytes: ByteArray) {
        val idx = streamingIndex
        if (idx < 0) return
        val now = System.currentTimeMillis()
        if (now - lastStreamUpdate < 120) return
        lastStreamUpdate = now
        val partial = String(bytes, Charsets.UTF_8)
        AiActivity.reportGeneratedChars(partial.length)   // 🏃 el monito corre según la velocidad de escritura
        // 🖥️ Código que la IA va escribiendo: se puede ver armándose en la vista previa
        if (liveCodeActive) updateLiveDraft(liveDraftPrefix + partial)
        // Mientras razona dentro de <pensamiento> se muestra "Pensando…"
        val open = Regex("<(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE).findAll(partial).count()
        val close = Regex("</(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE).findAll(partial).count()
        val tool = Regex("<(buscar|abrir)>", RegexOption.IGNORE_CASE).containsMatchIn(partial)
        // Sin texto en vivo: se mantiene "Pensando" hasta tener la respuesta completa
        if (!tool) return
        val shown = "🔎 Buscando en internet…"
        runOnUiThread {
            if (streamingIndex == idx && idx in chatMessages.indices && !chatMessages[idx].isUser) {
                chatMessages[idx] = chatMessages[idx].copy(text = shown)
                chatAdapter?.notifyItemChanged(idx)
                rvChatMessages?.scrollToPosition(idx)
            }
        }
    }

    private fun removeThinking(text: String): String {
        var t = text.replace(Regex("<(pensamiento|think|thinking)>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        // Si el modelo abrió la etiqueta y no la cerró, se deja lo que escribió sin la etiqueta
        t = t.replace(Regex("</?(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE), "")
        return t.trim().ifBlank { text.replace(Regex("</?(pensamiento|think|thinking)>", RegexOption.IGNORE_CASE), "").trim() }
    }

    // ========================================================================
    // 🧠 MEMORIA DE CONVERSACIONES (se guardan en el teléfono)
    // ========================================================================
    private var currentChatId: String = ""
    private val greetingText get() = LanguageManager.text(this, "¡Hola! ¿En qué te puedo ayudar?")

    private fun chatsDir() = File(filesDir, "chats").apply { mkdirs() }
    private fun chatMediaDir(id: String) = File(filesDir, "chat_media/$id")

    // Caracteres de historial que caben según el CONTEXTO (deja lugar para la respuesta)
    private fun historyCharBudget(): Int {
        val ctx = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("ai_context", 2048).coerceIn(2048, 15000)
        // ⚡ Tope de historial: con contexto grande no se relee una conversación gigante en cada mensaje
        return ((ctx - 1500) * 2.5).toInt().coerceIn(1500, 16000)
    }

    // Mensajes anteriores en formato (rol, texto). Se omiten el saludo y "Pensando".
    // 🧭 Opción A: la antigua «guía de pedidos diarios» (DAILY_GUIDE, más de 1.500 tokens en cada mensaje) se quitó.
    //    Ahora cada modo lleva instrucciones cortas (buildSystemPrompt) y la app entrega directo noticias, clima y divisas.
    private fun buildHistoryForModel(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var seenUser = false
        for (m in chatMessages) {
            if (m.isUser) seenUser = true
            if (!seenUser) continue
            if (!m.isUser && (m.text == "Pensando" || m.text.isBlank())) continue
            val text = if (m.isUser && m.attachments.isNotEmpty()) {
                val names = m.attachments.joinToString(", ") { File(it).name.substringAfter('_') }
                (if (m.text.isNotBlank()) m.text + "\n" else "") + "[Adjuntó: $names]"
            } else m.text
            // 🔁 Respuestas repetidas no se vuelven a mostrar al modelo: así no las copia otra vez
            if (!m.isUser) {
                val norm = text.replace("⏹️ (detenida)", "").trim().lowercase(Locale.ROOT)
                if (out.any { it.first == "assistant" && it.second.replace("⏹️ (detenida)", "").trim().lowercase(Locale.ROOT) == norm }) {
                    if (out.isNotEmpty() && out.last().first == "user") out.removeAt(out.lastIndex)
                    continue
                }
            }
            out.add((if (m.isUser) "user" else "assistant") to text)
        }
        return out
    }

    // Deja solo los mensajes más recientes que caben en el presupuesto
    private fun trimHistory(history: List<Pair<String, String>>, budget: Int): List<Pair<String, String>> {
        var total = 0
        val kept = ArrayDeque<Pair<String, String>>()
        for (item in history.asReversed()) {
            val cost = item.second.length + 20
            if (total + cost > budget) {
                // 🧵 Antes, si la última respuesta era larga (ej. un código), se borraba TODO el historial
                //    y la IA perdía el hilo. Ahora el último intercambio siempre se conserva (recortado si hace falta).
                if (kept.size < 2) {
                    val room = (budget - total - 20).coerceAtLeast(600)
                    val t = item.second
                    val cut = if (t.length <= room) t
                        else t.take(room / 2) + "\n[… parte omitida por espacio …]\n" + t.takeLast(room / 2)
                    kept.addFirst(item.first to cut)
                    total += cut.length + 20
                    continue
                }
                break
            }
            total += cost
            kept.addFirst(item)
        }
        // El historial debe empezar con un mensaje del usuario
        while (kept.isNotEmpty() && kept.first().first != "user") kept.removeFirst()
        return kept.toList()
    }

    private fun saveCurrentChat() {
        if (currentChatId.isEmpty()) return
        val saved = chatMessages.dropWhile { !it.isUser }.filter { !(it.text == "Pensando" && !it.isUser) }
        if (saved.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            saved.forEach { m ->
                arr.put(JSONObject().apply {
                    put("user", m.isUser)
                    put("text", m.text)
                    put("tokens", m.tokens)
                    put("time", m.timestamp)
                    put("attachments", JSONArray(m.attachments))
                })
            }
            val title = saved.firstOrNull { it.isUser && it.text.isNotBlank() }?.text?.take(40)
                ?: "Chat con adjuntos"
            val json = JSONObject().apply {
                put("id", currentChatId)
                put("title", title)
                put("updated", System.currentTimeMillis())
                put("messages", arr)
            }
            File(chatsDir(), "$currentChatId.json").writeText(json.toString())
        }.onFailure { Log.e(TAG, "No se pudo guardar el chat: ${it.message}") }
    }

    private fun loadChat(id: String?) {
        chatMessages.clear()
        chatMessages.add(ChatMessage(text = greetingText, isUser = false, sender = "Cumulus IA", tokens = ""))
        val file = id?.let { File(chatsDir(), "$it.json") }
        if (id != null && file != null && file.exists()) {
            currentChatId = id
            runCatching {
                val arr = JSONObject(file.readText()).getJSONArray("messages")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val atts = o.optJSONArray("attachments")
                    chatMessages.add(
                        ChatMessage(
                            text = o.optString("text"),
                            isUser = o.optBoolean("user"),
                            timestamp = o.optLong("time", System.currentTimeMillis()),
                            sender = if (o.optBoolean("user")) "Tú" else "Cumulus IA",
                            tokens = o.optString("tokens"),
                            attachments = (0 until (atts?.length() ?: 0)).map { atts!!.getString(it) }
                        )
                    )
                }
            }.onFailure { Log.e(TAG, "No se pudo abrir el chat: ${it.message}") }
        } else {
            currentChatId = "chat_${System.currentTimeMillis()}"
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("current_chat_id", currentChatId).apply()
        chatAdapter?.notifyDataSetChanged()
        rvChatMessages?.post { rvChatMessages?.scrollToPosition(chatMessages.lastIndex) }
        // La memoria del motor corresponde a otra conversación: se reinicia
        thread { runCatching { nativeResetChatMemory() } }
    }

    private fun startNewChat() {
        if (isAiBusy) {
            cumulusToast(this, "Espera a que termine la respuesta", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentChat()
        clearAttachments(deleteFiles = true)
        loadChat(null)
        cumulusToast(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
    }

    private fun showChatHistoryDialog() {
        saveCurrentChat()
        val chats = chatsDir().listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Triple(o.getString("id"), o.optString("title", "Chat"), o.optLong("updated", f.lastModified()))
                }.getOrNull()
            }
            .sortedByDescending { it.third }
        if (chats.isEmpty()) {
            cumulusToast(this, "Todavía no hay chats guardados", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val labels = chats.map { (id, title, time) ->
            (if (id == currentChatId) "● " else "") + title + "\n" + fmt.format(java.util.Date(time))
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chats anteriores")
            .setItems(labels) { _, which ->
                val (id, title, _) = chats[which]
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(arrayOf("Abrir", "Eliminar")) { _, action ->
                        if (action == 0) {
                            if (isAiBusy) {
                                cumulusToast(this, "Espera a que termine la respuesta", Toast.LENGTH_SHORT).show()
                            } else {
                                loadChat(id)
                                drawerLayout?.closeDrawers()
                            }
                        } else {
                            deleteChat(id)
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show().also { styleDarkRoundDialog(it) }
    }

    private fun deleteChat(id: String) {
        File(chatsDir(), "$id.json").delete()
        chatMediaDir(id).deleteRecursively()
        if (id == currentChatId) loadChat(null)
        cumulusToast(this, "Chat eliminado", Toast.LENGTH_SHORT).show()
    }

    // Imágenes y archivos se guardan con el chat (máx. 2 MB; las fotos grandes se comprimen).
    // Los videos se borran enseguida.
    private fun persistAttachments(files: List<File>): List<String> {
        val maxBytes = 2L * 1024 * 1024
        val dir = chatMediaDir(currentChatId).apply { mkdirs() }
        return files.mapNotNull { f ->
            if (!f.exists()) return@mapNotNull null
            val dest = File(dir, f.name)
            when (attachmentKind(f)) {
                AttachKind.VIDEO -> { f.delete(); null }
                AttachKind.IMAGE -> {
                    val ok = if (f.length() <= maxBytes) f.renameTo(dest) || runCatching { f.copyTo(dest, true); f.delete() }.isSuccess
                    else compressImageTo(f, File(dir, f.nameWithoutExtension + ".jpg"), maxBytes)
                    f.delete()
                    val out = if (dest.exists()) dest else File(dir, f.nameWithoutExtension + ".jpg")
                    if (ok && out.exists()) out.absolutePath else null
                }
                else -> {
                    if (f.length() <= maxBytes) {
                        val ok = f.renameTo(dest) || runCatching { f.copyTo(dest, true); f.delete() }.isSuccess
                        if (ok) dest.absolutePath else null
                    } else { f.delete(); null }
                }
            }
        }
    }

    private fun compressImageTo(src: File, dest: File, maxBytes: Long): Boolean = runCatching {
        var bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching false
        var quality = 90
        while (true) {
            dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
            if (dest.length() <= maxBytes) return@runCatching true
            if (quality > 50) quality -= 10
            else bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt().coerceAtLeast(64),
                (bmp.height * 0.8f).toInt().coerceAtLeast(64), true)
            if (bmp.width <= 64) return@runCatching dest.length() <= maxBytes
        }
        false
    }.getOrDefault(false)

    // Si el modelo igual escribe **negritas** o # títulos, se quitan los símbolos
    // (los bloques de código ``` se dejan intactos)
    private fun stripMarkdownSymbols(text: String): String {
        val parts = text.split("```")
        return parts.mapIndexed { i, part ->
            if (i % 2 == 1) part   // dentro de un bloque de código
            else part
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("__(.+?)__"), "$1")
                .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
                .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
                .replace(Regex("(?m)^(\\s*)[*+]\\s+"), "$1- ")
                .replace("**", "")
        }.joinToString("```")
    }

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
        updateSectionBoxColors(light)
        styleLanguageButtons(light)
        // Al volver a oscuro, los mensajes se redibujan con sus colores originales
        if (!light) chatAdapter?.notifyDataSetChanged()
    }

    // ========================================================================
    // 📱 BARRAS DEL SISTEMA (hora/batería arriba y la línea de gestos abajo)
    // La app se dibuja detrás de las barras y les pone su mismo color de fondo.
    // ========================================================================
    private var edgeToEdgeInstalled = false
    // Franjas que cubren la barra de estado y la de gestos con el color del panel lateral
    private var topBarOverlay: View? = null
    private var bottomBarOverlay: View? = null

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
        (root as? android.widget.FrameLayout)?.let { frame ->
            fun overlay(gravity: Int) = View(this).apply {
                alpha = 0f
                isClickable = false
                isFocusable = false
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 0, gravity
                )
            }
            topBarOverlay = overlay(android.view.Gravity.TOP).also { frame.addView(it) }
            bottomBarOverlay = overlay(android.view.Gravity.BOTTOM).also { frame.addView(it) }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, 0)
            topBarOverlay?.layoutParams?.let { it.height = bars.top; topBarOverlay?.layoutParams = it }
            bottomBarOverlay?.layoutParams?.let {
                it.height = if (ime.bottom > 0) 0 else bars.bottom
                bottomBarOverlay?.layoutParams = it
            }
            // Cada parte recibe su espacio por separado: la pantalla del chat y
            // los dos paneles laterales (así ninguno queda bajo la hora/batería)
            if (drawer != null) {
                for (i in 0 until drawer.childCount) {
                    val child = drawer.getChildAt(i)
                    @Suppress("UNCHECKED_CAST")
                    val orig = (child.getTag(R.id.drawer_layout) as? IntArray)
                        ?: intArrayOf(child.paddingLeft, child.paddingTop, child.paddingRight, child.paddingBottom)
                            .also { child.setTag(R.id.drawer_layout, it) }
                    // Chat y paneles laterales: abajo dejan lugar al teclado (o a la línea de gestos),
                    // así se puede escribir en cualquier campo sin que el teclado lo tape
                    val bottom = maxOf(bars.bottom, ime.bottom)
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
    private val sectionGray = Color.parseColor("#C7C7CC")
    private val sectionBoxes = mutableListOf<android.graphics.drawable.GradientDrawable>()

    // Modo oscuro: líneas azules. Modo claro: líneas grises.
    private fun updateSectionBoxColors(light: Boolean) {
        val dp = resources.displayMetrics.density
        sectionBoxes.forEach {
            it.setStroke((2 * dp).toInt(), if (light) sectionGray else sectionBlue)
            it.setColor(if (light) Color.WHITE else Color.BLACK)   // ⬛ adentro: negro (oscuro) / blanco (claro)
        }
    }

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
                    setStroke((2 * dp).toInt(), if (isLightMode()) sectionGray else sectionBlue)
                    setColor(if (isLightMode()) Color.WHITE else Color.BLACK)
                    sectionBoxes.add(this)
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
    // Color real del fondo, tal como se ve (centro del botón)
    private fun renderedBgColor(view: View): Int? = runCatching {
        val bg = view.background ?: return@runCatching null
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        bg.setBounds(0, 0, view.width, view.height)
        bg.draw(cv)
        val px = bmp.getPixel(view.width / 2, view.height / 2)
        bmp.recycle()
        if (Color.alpha(px) < 128) null else px
    }.getOrNull()

    // Fondo redondeado que no acepta "tint" (así el XML no lo vuelve a oscurecer)
    private class NoTintRoundDrawable(fill: Int, stroke: Int, strokeW: Int, radius: Float) :
        android.graphics.drawable.GradientDrawable() {
        init { setColor(fill); setStroke(strokeW, stroke); cornerRadius = radius }
        override fun setTintList(tint: android.content.res.ColorStateList?) {}
        override fun setTint(tintColor: Int) {}
        override fun setTintMode(tintMode: android.graphics.PorterDuff.Mode?) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    }

    private fun setBgTint(view: View, color: Int) {
        val csl = android.content.res.ColorStateList.valueOf(color)
        androidx.core.view.ViewCompat.setBackgroundTintList(view, csl)
        view.backgroundTintList = csl
    }

    private fun tintColorOf(view: View): Int? {
        // ViewCompat lee también el color de los MaterialButton (android:backgroundTint en el XML)
        val csl = androidx.core.view.ViewCompat.getBackgroundTintList(view) ?: view.backgroundTintList ?: return null
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
            val darkButton = light && view is Button && Color.red(c) < 80 && Color.green(c) < 80 && Color.blue(c) < 80
            if (!darkButton) bgMap[c]?.let { setBgTint(view, it) }
        }
        // ⬜ Botones oscuros en modo claro → fondo blanco, borde gris claro y texto oscuro.
        //    El color se mide en pantalla (los MaterialButton esconden su color real), y el fondo
        //    blanco ignora el "tint" del XML, así no vuelve a ponerse negro.
        if (view is Button && view !is androidx.appcompat.widget.SwitchCompat) {
            val keyBg = R.id.btn_send
            val keyText = R.id.btn_mic
            val savedBg = view.getTag(keyBg)
            if (light) {
                if (savedBg == null && view.width > 0 && view.height > 0) {
                    val c = renderedBgColor(view)
                    if (c != null && Color.red(c) < 80 && Color.green(c) < 80 && Color.blue(c) < 80) {
                        view.setTag(keyBg, view.background ?: android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        view.setTag(keyText, view.currentTextColor)
                        val d = resources.displayMetrics.density
                        val radius = (view as? MaterialButton)?.cornerRadius?.toFloat()?.takeIf { it > 0 } ?: (10 * d)
                        view.background = NoTintRoundDrawable(Color.WHITE, Color.parseColor("#C7C7CC"), (1.5f * d).toInt(), radius)
                        view.setTextColor(Color.parseColor("#1C1C1E"))
                    }
                }
                if (view.getTag(keyBg) != null) return
            } else if (savedBg is android.graphics.drawable.Drawable) {
                view.background = savedBg
                (view.getTag(keyText) as? Int)?.let { view.setTextColor(it) }
                view.setTag(keyBg, null); view.setTag(keyText, null)
            }
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

    // 🧭 Opción A: el ROL de cada modo en pocas líneas claras. Las reglas comunes (nombre, fecha, internet,
    //    formato, tono) las agrega buildSystemPrompt() una sola vez, sin repetirse ni contradecirse.
    private val chatModes = listOf(
        ChatMode(
            "solo_chat", "Solo chat",
            "Eres Cumulus IA, un asistente conversacional, como un amigo experto: respondes de forma natural y útil, " +
                "normalmente en 1 a 3 párrafos cortos, y puedes cerrar con una pregunta para seguir conversando.",
            1536
        ),
        ChatMode(
            "codigo", "Ingeniero de código",
            "Eres Cumulus IA en modo Ingeniero de código: un ingeniero de software senior en todos los lenguajes.\n" +
                "- Entrega el código COMPLETO y listo para copiar, en bloques ``` con el lenguaje; nunca «resto del código igual».\n" +
                "- Si te pasan un archivo y piden un cambio, devuelve el archivo completo ya corregido.\n" +
                "- Antes de cada bloque indica dónde va, por ejemplo: «📁 Va en: app/src/main/java/com/ejemplo/app/MainActivity.kt».\n" +
                "- Si hay un error, explica la causa en 1 o 2 frases y luego da la solución; si algo no está claro, di qué supones.",
            4096
        ),
        ChatMode(
            "general", "General",
            "Eres Cumulus IA, un asistente que sabe de todo (ciencia, historia, tecnología, salud, cocina, finanzas, cultura y más): " +
                "primero respondes exactamente lo que se pregunta y luego agregas los detalles útiles, bien ordenados.",
            1536
        ),
        ChatMode(
            "estudio", "Estudio",
            "Eres Cumulus IA en modo Estudio, un profesor paciente: explicas paso a paso con palabras sencillas y un ejemplo, " +
                "cierras con un resumen de las ideas clave y, si sirve, una pregunta corta para practicar.",
            1536
        ),
        ChatMode(
            "traduccion", "Traducción",
            "Eres Cumulus IA en modo Traducción, un traductor profesional. Traduce fiel y natural: al idioma que pida el usuario; " +
                "si no lo dice, del español al inglés y de otro idioma al español. Responde solo con la traducción.",
            512
        )
    )

    /**
     * 🧭 Opción A: instrucciones del sistema CORTAS (5–8 líneas) y sin contradicciones.
     * No llevan la hora (solo el día): así no cambian cada minuto y el motor reutiliza su memoria.
     */
    private fun buildSystemPrompt(rawMode: ChatMode, aiName: String, userName: String, today: String, personality: String): String {
        val lines = mutableListOf<String>()
        lines += rawMode.systemPrompt.replace("Cumulus IA", aiName)
        lines += "Te llamas $aiName." +
            (if (userName.isNotEmpty()) " El usuario se llama $userName; llámalo por su nombre al saludar." else "") +
            " Hoy es $today."
        if (rawMode.key != "traduccion") {
            lines += "Responde en el idioma del usuario y sigue el hilo: si se refiere a algo anterior, continúa sobre eso sin cambiar de tema."
            lines += "Si el mensaje trae datos (de internet o de la app), úsalos tal cual; no inventes datos y, si no sabes algo, dilo."
            lines += if (isInternetEnabled()) "Tienes internet: la app busca por ti y te entrega los resultados dentro del mensaje."
                else "Internet está apagado: si te piden algo actual, di que se activa en ➕ → Internet."
            lines += "Escribe en texto plano, sin asteriscos ni #; para listas usa números o guiones." +
                (if (rawMode.key != "codigo") " Si escribes código, va en un bloque ``` con el lenguaje." else "")
            if (rawMode.key != "codigo")
                lines += "La app puede crear imágenes y videos y guardar tus respuestas en Word, PDF o txt (el usuario escribe «guárdalo en PDF»)."
            lines += if (personality.isNotEmpty()) "Personalidad que pidió el usuario: $personality"
                else "Tono: cálido, cercano y con buen humor, sin exagerar."
        }
        return lines.joinToString("\n")
    }

    private fun currentChatMode(): ChatMode {
        val key = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("chat_mode", "solo_chat")
        return chatModes.firstOrNull { it.key == key } ?: chatModes.first()
    }

    // 🗂️ Menú con esquinas redondas y borde gris. Fondo negro en modo oscuro, blanco en modo claro.
    private fun showRoundMenu(anchor: View, items: List<String>, above: Boolean, onPick: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val light = isLightMode()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.parseColor(if (light) "#FFFFFF" else "#141414"))
                setStroke(dp(1), Color.parseColor(if (light) "#C7C7CC" else "#8E8E93"))
            }
            elevation = 12 * d
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val popup = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * d
        }
        items.forEachIndexed { i, label ->
            box.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(if (light) Color.parseColor("#111111") else Color.WHITE)
                setPadding(dp(18), dp(12), dp(28), dp(12))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0); ta.recycle()
                setOnClickListener { popup.dismiss(); onPick(i) }
            })
        }
        if (above) {
            box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            popup.showAsDropDown(anchor, 0, -(anchor.height + box.measuredHeight + dp(8)))
        } else {
            popup.showAsDropDown(anchor, 0, dp(4))
        }
    }

    private fun showChatModeMenu(anchor: View) {
        val current = currentChatMode().key
        val modes = chatModes.reversed()
        showRoundMenu(anchor, modes.map { if (it.key == current) "✓  ${it.label}" else "     ${it.label}" }, above = true) { i ->
            val mode = modes[i]
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("chat_mode", mode.key).apply()
            btnChatMode?.text = "${mode.label} ▴"
            cumulusToast(this, "Modo: ${mode.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChatModeAndAttach() {
        btnChatMode?.text = "${currentChatMode().label} ▴"
        btnChatMode?.setOnClickListener { anchor -> showChatModeMenu(anchor) }

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
        refreshInternetBadge()
    }

    /**
     * 🌐 Al tocar el interruptor de Internet, la línea de estado de la última respuesta cambia al
     * instante (⚠️ amarillo ↔ 🌐 azul), sin esperar al próximo mensaje. Las respuestas que sí
     * usaron datos de la web («🌐 Datos de: …») no se tocan.
     */
    private fun refreshInternetBadge() {
        val idx = chatMessages.indexOfLast { !it.isUser }
        if (idx < 0) return
        val msg = chatMessages[idx]
        val on = isInternetEnabled()
        val newTokens = if (on) msg.tokens.replace("⚠️ Sin datos de internet", "🌐 Internet activado · no hizo falta buscar")
                        else msg.tokens.replace("🌐 Internet activado · no hizo falta buscar", "⚠️ Sin datos de internet")
        if (newTokens != msg.tokens) {
            chatMessages[idx] = msg.copy(tokens = newTokens)
            chatAdapter?.notifyItemChanged(idx)
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
        addOption("👁️", "Cámara en vivo") { openCameraLive() }
        addOption("📄", "Archivo") {
            openAttachmentPicker(arrayOf("audio/*", "text/*", "application/*", "image/*", "video/*"))
        }

        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A3C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
        })

        // 🖥️ Vista previa web y 📱 maqueta de la app (lo que construye la IA en el chat)
        addOption("🖥️", "Vista previa web") { openWebPreview() }
        addOption("📱", "Maqueta de la app") { openAppMockup() }

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
                // 🌐 Se conecta YA (no al mandar el mensaje): prueba la red y deja lista la conexión
                //    para que la primera búsqueda no frene al motor
                if (checked) thread {
                    val ok = runCatching {
                        val c = (URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
                        }
                        try { c.responseCode in 200..399 } finally { c.disconnect() }
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (!ok) cumulusToast(this@MainActivity,
                            "⚠️ Internet activado, pero el teléfono no tiene conexión ahora. Revisa Wi‑Fi o datos.",
                            Toast.LENGTH_LONG).show()
                    }
                }
                cumulusToast(
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
        // ⚫ Modo oscuro: fondo negro · ⚪ Modo claro: fondo blanco (siempre esquinas curvas y borde gris)
        if (isLightMode()) {
            val d = resources.displayMetrics.density
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 22 * d
                    setColor(Color.WHITE)
                    setStroke((1.2f * d).toInt(), Color.parseColor("#C7C7CC"))
                }, (16 * d).toInt()))
            recolorTree(container, true)   // textos e íconos oscuros sobre el blanco
        } else styleDarkRoundDialog(dialog)
    }

    /** Respuestas de la IA en el chat, de la más nueva a la más vieja (para la vista previa). */
    private fun aiMessagesNewestFirst(): List<String> =
        chatMessages.filter { !it.isUser && it.text.isNotBlank() }.map { it.text }.asReversed()

    private fun openWebPreview() {
        PreviewPanel.open(this, "🖥️ Vista previa web",
            "Todavía no hay código web (HTML, CSS o JavaScript) en este chat.<br><br>" +
                "Pídele a la IA, por ejemplo: <b>«Créame una calculadora en HTML, CSS y JavaScript»</b> y vuelve a abrir esta ventana " +
                "(o toca 🔄).", live = { liveCodeActive }) { PreviewPanel.buildWebPage(liveDraftForPreview() + aiMessagesNewestFirst()) }
    }

    // ✍️ Código que la IA está escribiendo AHORA (para ver el desarrollo de la app en la vista previa)
    @Volatile private var liveCodeActive = false
    @Volatile private var liveCodeDraft: String? = null
    @Volatile private var liveDraftPrefix = ""

    private fun liveDraftForPreview(): List<String> = listOfNotNull(liveCodeDraft?.takeIf { liveCodeActive }?.let { d ->
        // Si el bloque de código todavía no se cierra (la IA sigue escribiendo), se cierra para poder mostrarlo
        if (Regex("```").findAll(d).count() % 2 == 1) "$d\n```" else d
    })

    private fun startLiveCode(kind: String?, active: Boolean = kind != null) {
        liveDraftPrefix = ""
        liveCodeDraft = null
        liveCodeActive = active
        AiActivity.previewKind = kind
    }

    /** Guarda lo que va escribiendo la IA y avisa cuando ya está la parte visual de la app. */
    private fun updateLiveDraft(draft: String) {
        if (!liveCodeActive) return
        liveCodeDraft = draft
        checkLiveVisualReady(draft)
    }

    // Un diseño XML de Android COMPLETO (bloque de código cerrado) = ya está la parte visual
    private val layoutBlockRx = Regex("```(xml)?[ \\t]*\\r?\\n\\s*(<\\?xml[^>]*>\\s*)?(<!--.*?-->\\s*)*<(LinearLayout|RelativeLayout|FrameLayout|" +
        "ScrollView|TableLayout|GridLayout|[\\w.]*ConstraintLayout|[\\w.]*CoordinatorLayout|[\\w.]*CardView|[\\w.]*DrawerLayout|" +
        "[\\w.]*NestedScrollView)[\\s>].*?```", RegexOption.DOT_MATCHES_ALL)
    @Volatile private var lastVisualCheckLen = 0

    /**
     * 📱 App con Kotlin: en cuanto la IA termina de escribir la parte visual (el diseño XML),
     * se le pide al usuario que entre a la maqueta. Ni antes ni después.
     */
    private fun checkLiveVisualReady(draft: String) {
        if (AiActivity.previewReady || AiActivity.previewKind == "web") return
        // Revisar solo cuando creció el texto y ya hay un bloque cerrado (no en cada letra)
        if (draft.length - lastVisualCheckLen < 150 && lastVisualCheckLen != 0) return
        lastVisualCheckLen = draft.length
        if (!draft.contains("Layout") || draft.split("```").size < 3) return
        if (layoutBlockRx.containsMatchIn(draft)) {
            AiActivity.previewKind = "app"
            AiActivity.markPreviewReady("📱 Ya está creada la parte visual de tu app: entra a la maqueta para ver cómo se ve")
        }
    }

    private fun stopLiveCode() {
        liveCodeActive = false
        liveCodeDraft = null
        liveDraftPrefix = ""
        lastVisualCheckLen = 0
    }

    private fun openAppMockup() {
        PreviewPanel.open(this, "📱 Maqueta de la app",
            "Todavía no hay diseños de app Android (XML) en este chat.<br><br>" +
                "Pídele a la IA, por ejemplo: <b>«Créame una app de notas completa en Kotlin en partes separadas»</b> y vuelve a abrir " +
                "esta ventana (o toca 🔄).", live = { liveCodeActive }) { PreviewPanel.buildAppMockup(liveDraftForPreview() + aiMessagesNewestFirst()) }
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
            cumulusToast(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
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
                cumulusToast(this, "No se pudo preparar la cámara", Toast.LENGTH_SHORT).show()
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
            cumulusToast(this, "Da permiso de cámara y vuelve a tocar Cámara", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error al abrir la cámara: ${e.message}")
            cumulusToast(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
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

    // 🔎 Búsqueda con varios buscadores: si uno falla o bloquea, se usa el siguiente
    private fun searchWeb(query: String): List<WebResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<WebResult>()
        fun add(list: List<WebResult>) {
            list.forEach { r -> if (out.none { it.url == r.url }) out.add(r) }
        }
        val newsLike = Regex(
            "noticia|hoy|último|ultima|última|actual|ahora|reciente|semana|ayer|resultado|partido|precio|dólar|dolar|clima|tiempo en|elecci",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(q)
        // Sin DuckDuckGo: Google Noticias primero en temas de actualidad, Bing para la web general
        if (newsLike) add(searchGoogleNews(q))
        add(searchBing(q))
        if (out.size < 3 && !newsLike) add(searchGoogleNews(q))
        if (out.size < 3) add(searchWikipedia(q))
        Log.d(TAG, "🔎 «$q» → ${out.size} resultados")
        return out.take(8)
    }

    private fun htmlText(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun enc(q: String) = java.net.URLEncoder.encode(q.take(250), "UTF-8")

    // Noticias: Google News RSS (Chile, en español), muy confiable para titulares del día
    private fun searchGoogleNews(query: String): List<WebResult> = runCatching {
        val (_, xml) = httpGetText("https://news.google.com/rss/search?q=${enc(query)}&hl=es-419&gl=CL&ceid=CL:es-419")
            ?: return@runCatching emptyList()
        xml.split("<item>").drop(1).mapNotNull { item ->
            fun tag(n: String) = Regex("<$n[^>]*>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.let { htmlText(it) }.orEmpty()
            val title = tag("title"); val link = tag("link")
            if (title.isBlank() || !link.startsWith("http")) null
            else WebResult(title, link, "${tag("source")} · ${tag("pubDate")}".trim(' ', '·'))
        }.take(8)
    }.getOrDefault(emptyList())

    private fun searchDuckDuckGoLite(query: String): List<WebResult> = runCatching {
        val (_, html) = httpGetText("https://lite.duckduckgo.com/lite/?q=${enc(query)}") ?: return@runCatching emptyList()
        val links = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).toList()
        val snippets = Regex("class=['\"]result-snippet['\"][^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { htmlText(it.groupValues[1]) }.toList()
        links.mapIndexedNotNull { i, m ->
            var href = htmlText(m.groupValues[1])
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http")) null else WebResult(htmlText(m.groupValues[2]), href, snippets.getOrElse(i) { "" })
        }.take(8)
    }.getOrDefault(emptyList())

    private fun searchBing(query: String): List<WebResult> = runCatching {
        val (_, html) = httpGetText("https://www.bing.com/search?q=${enc(query)}&setlang=es&cc=CL") ?: return@runCatching emptyList()
        html.split("class=\"b_algo\"").drop(1).mapNotNull { block ->
            val m = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)
                ?: return@mapNotNull null
            var href = htmlText(m.groupValues[1])
            // Bing envuelve enlaces: bing.com/ck/a?...&u=a1<base64>
            Regex("[?&]u=a1([^&]+)").find(href)?.let {
                runCatching {
                    val b = it.groupValues[1].replace('-', '+').replace('_', '/')
                    val padded = b + "=".repeat((4 - b.length % 4) % 4)
                    href = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
                }
            }
            if (!href.startsWith("http")) return@mapNotNull null
            val snippet = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                ?.let { htmlText(it) }.orEmpty()
            WebResult(htmlText(m.groupValues[2]), href, snippet)
        }.take(8)
    }.getOrDefault(emptyList())

    private fun searchWikipedia(query: String): List<WebResult> = runCatching {
        val (_, json) = httpGetText(
            "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=${enc(query)}"
        ) ?: return@runCatching emptyList()
        val arr = org.json.JSONObject(json).getJSONObject("query").getJSONArray("search")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.getString("title")
            WebResult(title, "https://es.wikipedia.org/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                htmlText(o.optString("snippet")))
        }
    }.getOrDefault(emptyList())

    private fun searchDuckDuckGo(query: String): List<WebResult> = runCatching {
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
        val ctx = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("ai_context", 2048).coerceIn(2048, 15000)
        // Caracteres de internet que caben (deja espacio para la respuesta)
        // ⚡ Tope fijo: leer 60 mil letras de páginas demoraba mucho; con 8 mil basta para responder bien
        val budget = ((ctx - 1200) * 2.5).toInt().coerceIn(1200, 8000)

        val directUrls = Regex("https?://[^\\s<>\"]+").findAll(query).map { it.value.trimEnd('.', ',', ')') }.toList()
        val cleanQuery = query.replace(Regex("https?://\\S+"), " ").trim().ifBlank { query }

        val targets: List<WebResult> = if (directUrls.isNotEmpty()) {
            directUrls.take(3).map { WebResult("", it, "") }
        } else {
            searchWeb(cleanQuery)
        }
        AiActivity.analyzing(if (directUrls.isNotEmpty()) "🌐 Abriendo el enlace" else "🔎 Buscando en internet: «${cleanQuery.take(80)}»",
            if (directUrls.isNotEmpty()) directUrls.take(3).joinToString("\n") else "${targets.size} resultados")
        if (targets.isEmpty()) return null

        val toRead = targets.take(3)
        AiActivity.log("📄 Leyendo ${toRead.size} página(s)", toRead.joinToString("\n") { r ->
            (runCatching { java.net.URI(r.url).host }.getOrNull() ?: r.url).removePrefix("www.") +
                (if (r.title.isNotBlank()) " — " + r.title.take(70) else "")
        })
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
        AiActivity.log("🧾 Información reunida", "${result.length} letras de ${pages.count { it != null }} página(s) leída(s)")
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
            cumulusToast(this, msg, Toast.LENGTH_SHORT).show()
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
            "jpg", "jpeg", "jpe", "jfif", "pjpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif",
            "ico", "wbmp", "dng", "cr2", "nef", "arw", "raf", "orf", "rw2" -> AttachKind.IMAGE
            "mp4", "mkv", "webm", "3gp", "mov", "avi" -> AttachKind.VIDEO
            "wav", "mp3", "flac", "m4a", "ogg", "aac", "opus" -> AttachKind.AUDIO
            "txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "kts", "java", "py", "js", "ts",
            "jsx", "tsx", "mjs", "cjs", "vue", "svelte", "c", "cc", "cpp", "cxx", "h", "hpp", "cs", "go", "rs",
            "swift", "dart", "php", "rb", "lua", "r", "scala", "pl", "m", "mm", "groovy", "sh", "bash", "zsh",
            "bat", "ps1", "cmd", "yml", "yaml", "toml", "gradle", "properties", "cmake", "mk", "makefile",
            "dockerfile", "sql", "css", "scss", "sass", "less", "ini", "cfg", "conf", "env", "gitignore",
            "proto", "graphql", "tex", "asm", "s", "ino", "pro", "smali",
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "pdf", "rtf" -> AttachKind.TEXT
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

    // 🪪 "¿Cómo te llamas?" / "¿Cómo me llamo?" → respuesta directa, sin depender del modelo
    private fun nameAnswer(raw: String): String? {
        val q = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[¿?¡!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val aiName = prefs.getString("ai_name", null)?.trim().orEmpty().ifEmpty { "Cumulus IA" }
        val userName = prefs.getString("user_name", null)?.trim().orEmpty()
        val askAi = Regex("^(y |ahora |entonces |oye )?(como te llamas|cual es tu nombre|dime tu nombre|tu nombre|como te llamas ahora|ahora como te llamas)$").matches(q)
        val askUser = Regex("^(y |ahora |entonces |oye )?(como me llamo|di mi nombre|dime mi nombre|cual es mi nombre|mi nombre|como me llamo ahora|sabes mi nombre|sabes como me llamo)$").matches(q)
        // 🌐 "¿Tienes internet?" → se responde según el interruptor de Internet
        val askNet = q.contains("internet") && Regex("(tienes|tengas|puedes|podes|acceso|accedes|acceder|conectad|usar|ya tienes|tiene)").containsMatchIn(q) &&
            !Regex("(busca|buscar|investiga|noticia|clima|precio|quien|que es|cuanto)").containsMatchIn(q)
        if (askNet) {
            return if (isInternetEnabled())
                listOf("¡Sí! 🌐 Tengo acceso a internet: puedo buscar noticias, datos actuales y abrir páginas. ¿Qué quieres que busque?",
                    "Sí, tengo internet activado 🌐. Pídeme lo que quieras buscar.").random()
            else "Ahora no tengo internet 🌐❌. Actívalo en el botón ➕ → Internet y podré buscar información actual."
        }
        if (q.length > 40) return null
        return when {
            askAi -> listOf("¡Me llamo $aiName! 😊", "Soy $aiName, ¿en qué te ayudo?", "Mi nombre es $aiName 😄").random()
            askUser && userName.isNotEmpty() -> listOf("¡Te llamas $userName! 😊", "Eres $userName 😄", "Tu nombre es $userName, ¿en qué te ayudo?").random()
            askUser -> "Todavía no me has dicho tu nombre 😅. Puedes ponerlo en Configuración → tu nombre."
            else -> null
        }
    }

    /**
     * 🩹 Auto‑reparación: si el motor falla, la app lo arregla sola al instante (limpia la memoria y
     * recarga el modelo) y vuelve a responder el MISMO mensaje. Así un error no se repite en el
     * segundo ni en el tercer mensaje, y el usuario no tiene que tocar «Nuevo chat» ni recargar.
     */
    private fun generateAiResponse(
        prompt: String,
        backend: String,
        attachments: List<File> = emptyList(),
        history: List<Pair<String, String>> = emptyList()
    ): AiReply {
        // 💻 Modo Ingeniero de código: solo programación (también en voz a voz)
        runCatching { codeModeGate(prompt, history, attachments.isNotEmpty()) }.getOrNull()?.let {
            AiActivity.log("💻 No es un pedido de programación: en modo Ingeniero de código no se hace")
            return AiReply(it, "💻 Modo Ingeniero de código")
        }
        // 🧩 «Créame una app completa / por partes»: la app arma el proyecto ARCHIVO POR ARCHIVO
        //    (un modelo pequeño no cabe todo en una respuesta y termina dando una «versión básica»)
        if (attachments.isEmpty() && isProjectRequest(prompt) && triggerAutomaticModelLoadOnFirstMessage()) {
            AiActivity.analyzing("🧩 Es un proyecto completo: lo armo archivo por archivo")
            val built = runCatching { buildProjectInParts(prompt, backend) }
                .onFailure { Log.e(TAG, "Proyecto por partes falló", it) }.getOrNull()
            if (built != null) return built
        }
        var first = generateAiResponseOnce(prompt, backend, attachments, history)
        // 🙅 A veces un modelo pequeño se niega sin motivo («Lo siento, no puedo asistir con eso»):
        //    se reintenta una vez con la memoria limpia antes de mostrarlo
        if (!stopRequested && first.text.length < 220 &&
            Regex("(?i)(no puedo (asistir|ayudar)|no puedo (hacer|cumplir) eso|lo siento,? pero no puedo|i can'?t (help|assist)|i cannot (help|assist))").containsMatchIn(first.text)) {
            Log.w(TAG, "🙅 Respuesta de rechazo; reintento: ${first.text}")
            AiActivity.log("🔁 El modelo se negó sin motivo: reintento con la memoria limpia", first.text.take(200))
            runCatching { nativeResetChatMemory() }
            val again = generateAiResponseOnce(prompt, backend, attachments, history)
            if (!again.text.startsWith(ENGINE_FAIL_MARK)) first = again
        }
        if (!first.text.startsWith(ENGINE_FAIL_MARK) || stopRequested) return first
        Log.w(TAG, "🩹 Motor falló; reparando y reintentando: ${first.text.take(200)}")
        AiActivity.log("🩹 El motor falló: lo reparo y reintento", first.text.substringAfter("\n", "").substringBefore("\n\n").trim().take(300))
        streamingIndex.let { idx ->
            if (idx >= 0) runOnUiThread {
                if (idx in chatMessages.indices && !chatMessages[idx].isUser) {
                    // Se muestra el motivo para poder diagnosticarlo (ej. con modelos pequeños)
                    val why = first.text.substringAfter("\n", "").substringBefore("\n\n").trim().take(160)
                    chatMessages[idx] = chatMessages[idx].copy(text = "🩹 Reparando el motor y reintentando..." +
                        (if (why.isNotEmpty()) "\n(motivo: $why)" else ""))
                    chatAdapter?.notifyItemChanged(idx)
                }
            }
        }
        val healed = runCatching { healEngine(backend) }.getOrDefault(false)
        if (healed && !stopRequested) {
            replyImages.clear()   // las fotos (noticias/clima) se vuelven a poner en el reintento
            // Reintento con memoria limpia: sin historial viejo, que suele ser lo que desbordó al motor
            val second = generateAiResponseOnce(prompt, backend, attachments, emptyList())
            if (!second.text.startsWith(ENGINE_FAIL_MARK)) return second
            return AiReply(second.text.replace("\n\nSi se repite, toca «Nuevo chat» o vuelve a cargar el modelo.",
                "\n\n🩹 Ya reparé el motor y reintenté una vez. Prueba con un mensaje más corto o en otro motor (CPU/GPU/NPU)."), second.tokens)
        }
        return first
    }


    // ========================================================================
    // 🧩 PROYECTO COMPLETO POR PARTES (un archivo por llamada al modelo)
    // ========================================================================
    private fun isProjectRequest(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT)
        val create = Regex("\\b(cr[eé]a(me|r)?|haz(me)?|hacer|genera(me|r)?|desarrolla(me|r)?|programa(me|r)?|construye(me)?|arma(me)?|p[aá]same|dame|quiero|necesito)\\b").containsMatchIn(p)
        val thing = Regex("\\b(app|aplicaci[oó]n|proyecto|juego|programa|sistema|p[aá]gina web|sitio web)\\b").containsMatchIn(p)
        val full = Regex("(complet[oa]|enter[oa]|todos los (archivos|c[oó]digos)|todo el c[oó]digo|por partes|en partes|partes separadas|separad[oa]s|archivo por archivo|funcional)").containsMatchIn(p)
        // 🛠️ Modo Ingeniero de código: «créame una app / página web / juego…» ya es un proyecto completo
        //    (si pegó código como formato, se responde de una vez usando ese código como base)
        val codeMode = currentChatMode().key == "codigo" && !hasPastedCode(prompt)
        return create && thing && (full || codeMode)
    }

    /** Llama al modelo cargado (CPU, GPU/NPU con GenieX o LiteRT) con unas instrucciones y un pedido. */
    private fun rawGenerate(system: String, ask: String, maxTokens: Int, backend: String): String {
        val model = getActiveModelFile()
        val temp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat("ai_temperature", 0.5f).coerceAtMost(0.5f)
        fun once(): String = when {
            GenieXEngineManager.isLoaded(model?.absolutePath) ->
                runBlocking { GenieXEngineManager.generate(system, listOf("user"), listOf(ask), maxTokens, temp) }
            model?.extension.equals("litertlm", true) ->
                runCatching { runBlocking { LiteRtLmEngineManager.generateChat(system, listOf("user"), listOf(ask)) } }
                    .getOrElse { runBlocking { LiteRtLmEngineManager.generateFresh("[Instrucciones: $system]\n\n$ask") } }
            else -> {
                runCatching { nativeResetChatMemory() }
                nativeSetChatMode(system, maxTokens)
                nativeRunChat(arrayOf("user"), arrayOf(ask))
            }
        }
        return try { once() } catch (t: Throwable) {
            // 🩹 Si el motor se llenó o falló, se repara y se intenta una vez más
            Log.w(TAG, "Proyecto: el motor falló (${t.message}); reparo y reintento")
            if (!healEngine(backend)) throw t
            once()
        }
    }

    private fun langFor(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "xml" -> "xml"; "gradle" -> "groovy"; "py" -> "python"
        "js" -> "javascript"; "ts" -> "typescript"; "html" -> "html"; "css" -> "css"; "json" -> "json"
        "swift" -> "swift"; "dart" -> "dart"; "sql" -> "sql"; "md" -> "markdown"; else -> ""
    }

    private fun projectProgress(text: String) {
        AiActivity.analyzing(text)   // ✦ también queda en la ventana de actividad
        val idx = streamingIndex
        if (idx < 0) return
        runOnUiThread {
            if (idx in chatMessages.indices && !chatMessages[idx].isUser) {
                chatMessages[idx] = chatMessages[idx].copy(text = text)
                chatAdapter?.notifyItemChanged(idx)
            }
        }
    }


    /**
     * 📁 Carpeta donde va cada archivo (estructura estándar de Android si el modelo no dio la ruta completa).
     * Para Kotlin/Java se usa el «package» del propio código: package com.ejemplo.tts → app/src/main/java/com/ejemplo/tts
     */
    private fun locationFor(path: String, code: String): Pair<String, String> {
        val clean = path.replace('\\', '/').trim('/')
        val fileName = clean.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val pkg = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(code)?.groupValues?.get(1)
        val folder = when {
            (ext == "kt" || ext == "java") && pkg != null -> "app/src/main/java/" + pkg.replace('.', '/')
            clean.contains('/') -> clean.substringBeforeLast('/')
            fileName.equals("AndroidManifest.xml", true) -> "app/src/main"
            fileName.startsWith("build.gradle") -> "app   (el build.gradle de la app, no el de la raíz)"
            fileName.startsWith("settings.gradle") -> "la raíz del proyecto"
            ext == "xml" && code.contains("<resources") -> "app/src/main/res/values"
            ext == "xml" && (code.contains("<menu")) -> "app/src/main/res/menu"
            ext == "xml" && (code.contains("<vector") || code.contains("<shape") || code.contains("<selector")) -> "app/src/main/res/drawable"
            ext == "xml" -> "app/src/main/res/layout"
            ext == "kt" || ext == "java" -> "app/src/main/java/(tu paquete)"
            else -> "la raíz del proyecto"
        }
        return folder to fileName
    }

    private fun buildProjectInParts(prompt: String, backend: String): AiReply? {
        val start = System.currentTimeMillis()
        val aiName = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("ai_name", null)?.trim().orEmpty().ifEmpty { "Cumulus IA" }
        val sys = chatModes.first { it.key == "codigo" }.systemPrompt.replace("Cumulus IA", aiName) +
            " Nunca entregues una «versión básica» ni dejes partes pendientes: escribe código real, completo y funcional."
        // 1) Plan: lista de archivos
        projectProgress("🧩 Planificando el proyecto (lista de archivos)...")
        val planAsk = "El usuario pidió: «$prompt»\n\n" +
            "Planifica ese proyecto COMPLETO y funcional (no una versión básica). Usa la tecnología que pidió; si no dijo, " +
            "una app Android en Kotlin (con sus layouts XML, AndroidManifest.xml y build.gradle de la app).\n" +
            "Usa rutas COMPLETAS desde la raíz del proyecto y un solo paquete para todo (ej. com.ejemplo.papelera), " +
            "por ejemplo app/src/main/java/com/ejemplo/papelera/MainActivity.kt o app/src/main/res/layout/activity_main.xml.\n" +
            "Responde SOLO con la lista de archivos, uno por línea, con este formato exacto:\n" +
            "ARCHIVO: ruta/del/archivo.ext | qué contiene | búsqueda corta en inglés para encontrar código de ejemplo\n" +
            "Entre 4 y 9 archivos. Nada más, sin código."
        val plan = removeThinking(rawGenerate(sys, planAsk, 700, backend))
        if (stopRequested) return null
        val fileRx = Regex("([\\w./-]+\\.(kt|kts|java|xml|gradle|py|js|ts|html|css|json|swift|dart|sql|md|txt|pro))", RegexOption.IGNORE_CASE)
        val files = LinkedHashMap<String, String>()
        val queries = HashMap<String, String>()   // búsqueda en inglés por archivo (para código de internet)
        val refLinks = mutableListOf<String>()
        plan.lines().forEach { line ->
            val m = fileRx.find(line) ?: return@forEach
            val path = m.value.trim('/', '.', ' ')
            if (path.length > 2 && files.size < 9 && !files.containsKey(path)) {
                val cols = line.split("|").map { it.trim() }
                files[path] = cols.getOrNull(1).orEmpty().take(160)
                cols.getOrNull(2)?.takeIf { it.length in 4..120 }?.let { queries[path] = it.replace(Regex("[\"«»`*]"), "") }
            }
        }
        if (files.size < 2) {
            // Plan por defecto (Android Kotlin) si el modelo no dio una lista clara
            files.clear()
            files["app/build.gradle"] = "dependencias y configuración de la app"
            files["app/src/main/AndroidManifest.xml"] = "manifiesto con la actividad y permisos"
            files["app/src/main/res/layout/activity_main.xml"] = "diseño de la pantalla principal"
            files["app/src/main/java/com/ejemplo/app/MainActivity.kt"] = "lógica principal de la app"
            files["app/src/main/java/com/ejemplo/app/Repositorio.kt"] = "datos y lógica de guardado"
        }
        // 2) Cada archivo por separado, recordando lo ya escrito para que todo encaje
        val out = StringBuilder()
        out.append("🧩 Proyecto completo en ${files.size} partes, listo para copiar y pegar:\n\n")
        files.entries.forEachIndexed { i, (path, desc) -> out.append("${i + 1}. $path — $desc\n") }
        val summaries = StringBuilder()
        val locations = mutableListOf<String>()
        var done = 0
        for ((i, entry) in files.entries.withIndex()) {
            if (stopRequested) break
            val (path, desc) = entry
            // 🌐 Código real de internet para este archivo (si el internet está activado)
            var reference = ""
            if (isInternetEnabled() && !path.endsWith(".gradle") && !path.endsWith("AndroidManifest.xml")) {
                projectProgress("🔎 Buscando código en internet para la parte ${i + 1} de ${files.size}: ${path.substringAfterLast('/')} ...")
                val q = queries[path] ?: "android kotlin " + path.substringAfterLast('/').substringBeforeLast('.') + " " + desc.take(60)
                runCatching { fetchCodeFromInternet(q, 2500) }.getOrNull()?.let { (txt, links) ->
                    reference = txt; refLinks += links.take(2)
                }
            }
            projectProgress("🧩 Escribiendo parte ${i + 1} de ${files.size}: $path ...")
            liveDraftPrefix = out.toString() + "\n"   // 🖥️ la vista previa muestra lo ya hecho + lo que escribe ahora
            val ask = "Proyecto pedido por el usuario: «$prompt»\n" +
                "Archivos del proyecto:\n" + files.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } + "\n" +
                (if (summaries.isNotEmpty()) "Lo ya escrito (respeta estos nombres, ids, clases y funciones):\n${summaries.toString().takeLast(2500)}\n" else "") +
                (if (reference.isNotEmpty()) "\nCÓDIGO DE REFERENCIA DE INTERNET (úsalo y adáptalo si sirve; no lo copies si no encaja):\n$reference\n" else "") +
                "\nAhora escribe el archivo COMPLETO «$path» ($desc). Debe funcionar junto con los demás archivos. " +
                "Entrega SOLO el contenido del archivo dentro de un bloque ```${langFor(path)}, completo, sin «...», sin TODO y sin partes pendientes."
            val raw = removeThinking(rawGenerate(sys, ask, 4096, backend))
            val code = Regex("```[\\w+-]*\\n(.*?)(```|$)", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trimEnd()
                ?: raw.trim()
            if (code.isBlank()) continue
            done++
            val (folder, fileName) = locationFor(path, code)
            locations += "$fileName → $folder"
            out.append("\n📄 Parte ${i + 1} de ${files.size} — $fileName\n" +
                "📁 Este código va en la carpeta: $folder\n" +
                "   (crea ahí el archivo «$fileName» y pega todo el código)\n" +
                "```${langFor(path)}\n$code\n```\n")
            // Resumen de lo importante para los siguientes archivos (clases, funciones, ids)
            val keys = code.lines().filter {
                Regex("\\b(class|interface|object|fun|def|function|android:id|<activity|package|import com\\.|val .*=|const )\\b").containsMatchIn(it)
            }.take(25).joinToString("\n") { it.trim().take(140) }
            summaries.append("[$path]\n$keys\n")
            if (liveCodeActive) updateLiveDraft(out.toString())
        }
        liveDraftPrefix = ""
        if (done == 0) return null
        out.append("\n🗂️ DÓNDE VA CADA ARCHIVO (resumen):\n" + locations.joinToString("\n") { "• $it" } + "\n")
        val hasWeb = files.keys.any { it.endsWith(".html", true) || it.endsWith(".js", true) || it.endsWith(".css", true) }
        val hasXmlLayout = files.keys.any { it.contains("layout", true) && it.endsWith(".xml", true) }
        if (hasWeb) out.append("\n🖥️ Para verla funcionando, toca «Abrir vista previa» aquí abajo (o ➕ → «Vista previa web»).\n")
        if (hasXmlLayout) out.append("\n📱 Para ver el diseño de las pantallas, toca «Ver maqueta» aquí abajo (o ➕ → «Maqueta de la app»).\n")
        out.append("\n✅ Copia cada parte en su carpeta con el mismo nombre de archivo. Si quieres que agregue algo (más pantallas, " +
            "guardar en base de datos, diseño, etc.), dime qué y lo sumo al proyecto.")
        if (refLinks.isNotEmpty()) out.append("\n\n🌐 Código de referencia tomado de internet:\n" + refLinks.distinct().take(8).joinToString("\n") { "• $it" })
        val secs = (System.currentTimeMillis() - start) / 1000
        val engine = if (GenieXEngineManager.isLoaded(getActiveModelFile()?.absolutePath)) GenieXEngineManager.engineInfo() else "motor local"
        return AiReply(out.toString(), "🧩 Proyecto: $done de ${files.size} archivos · $engine · ${secs} s" +
            (if (refLinks.isNotEmpty()) "\n🌐 Datos de: Stack Overflow (código de referencia)" else ""))
    }


    // ========================================================================
    // 💻🌐 CÓDIGO DESDE INTERNET (Stack Overflow): ejemplos reales para completar lo que el modelo no sabe
    // ========================================================================
    private fun httpGetJsonGz(url: String): org.json.JSONObject? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "CumulusIA/1.0 (Android)")
        }
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val bytes = c.inputStream.use { it.readBytes() }
            // La API de Stack Exchange responde comprimida (gzip)
            val txt = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
            else String(bytes, Charsets.UTF_8)
            org.json.JSONObject(txt)
        } finally { c.disconnect() }
    }.getOrNull()

    /** Busca respuestas con código en Stack Overflow. Devuelve (texto con el código, enlaces). */
    private fun fetchCodeFromInternet(query: String, maxChars: Int = 5000): Pair<String, List<String>>? {
        if (query.isBlank()) return null
        AiActivity.analyzing("🔎 Buscando código en Stack Overflow", "«$query»")
        val search = httpGetJsonGz("https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&answers=1" +
            "&site=stackoverflow&pagesize=5&q=${enc(query)}") ?: return null
        val qs = search.optJSONArray("items") ?: return null
        val ids = (0 until qs.length()).mapNotNull { qs.optJSONObject(it) }
            .filter { it.optBoolean("is_answered") }.map { it.optLong("question_id") }.take(4)
        if (ids.isEmpty()) return null
        val titles = (0 until qs.length()).mapNotNull { qs.optJSONObject(it) }
            .associate { it.optLong("question_id") to xmlUnescape(it.optString("title")) }
        val ans = httpGetJsonGz("https://api.stackexchange.com/2.3/questions/${ids.joinToString(";")}/answers" +
            "?order=desc&sort=votes&site=stackoverflow&filter=withbody&pagesize=8") ?: return null
        val items = ans.optJSONArray("items") ?: return null
        val sb = StringBuilder(); val links = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            if (a.optInt("score") < 1 && !a.optBoolean("is_accepted")) continue
            val blocks = Regex("<pre[^>]*><code[^>]*>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)
                .findAll(a.optString("body")).map { xmlUnescape(it.groupValues[1].replace(Regex("<[^>]+>"), "")) }
                .filter { it.lines().size >= 3 }.take(2).toList()
            if (blocks.isEmpty()) continue
            val link = "https://stackoverflow.com/a/${a.optLong("answer_id")}"
            val piece = "[Ejemplo de Stack Overflow — «${titles[a.optLong("question_id")] ?: ""}» — $link]\n" +
                blocks.joinToString("\n") { "```\n${it.trim().take(1800)}\n```" } + "\n\n"
            if (sb.length + piece.length > maxChars) break
            sb.append(piece); links += link
        }
        return if (sb.isEmpty()) null else sb.toString() to links
    }

    /** Convierte el pedido en una búsqueda corta en inglés (Stack Overflow responde mucho mejor en inglés). */
    private fun englishCodeQuery(request: String, backend: String): String {
        val q = runCatching {
            removeThinking(rawGenerate("You turn programming requests into short English search queries.",
                "Request: «${request.take(400)}»\nWrite ONLY a short English search query (max 8 words) to find example code " +
                    "on Stack Overflow (include the language/platform, e.g. android kotlin). No quotes, no explanation.", 40, getSavedBackend()))
        }.getOrDefault("")
        val line = q.lines().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("[\"«»`*]"), "").trim()
        return if (line.length in 4..120) line else request.take(120)
    }

    /** Limpia la memoria del motor y recarga el modelo activo en el mismo backend. */
    private fun healEngine(backend: String): Boolean {
        val model = getActiveModelFile() ?: return false
        runCatching { nativeResetChatMemory() }
        val ok = loadModelWithConfiguration(model, backend)
        isModelLoadedInMemory = ok
        runOnUiThread { updateLoadModelButton() }
        Log.i(TAG, "🩹 Motor recargado: $ok")
        return ok
    }

    private fun generateAiResponseOnce(
        prompt: String,
        backend: String,
        attachments: List<File> = emptyList(),
        history: List<Pair<String, String>> = emptyList()
    ): AiReply {
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
        // 🪪 Preguntas por los nombres: se responden directo con lo de Configuración (exacto e instantáneo)
        nameAnswer(prompt)?.let { return AiReply(it, "🪪 nombre de Configuración") }
        knowledgeCommand(prompt)?.let { return AiReply(it, "📚 conocimiento guardado") }
        return try {
            val activeModel = getActiveModelFile()
            val usingLiteRt = activeModel?.extension.equals("litertlm", ignoreCase = true)
            val rawMode = currentChatMode()
            // Pide a la IA texto limpio: sin asteriscos ni almohadillas
            // Fecha y hora reales del teléfono (el modelo no sabe en qué fecha estamos)
            val now = java.text.SimpleDateFormat(
                "EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES")
            ).format(java.util.Date())
            // Solo el día (sin hora) en las instrucciones: así no cambian cada minuto y el motor reutiliza su memoria
            val today = java.text.SimpleDateFormat(
                "EEEE d 'de' MMMM 'de' yyyy", Locale("es", "ES")
            ).format(java.util.Date())
            // Nombre de la IA y del usuario elegidos en Configuración
            val namePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val aiName = namePrefs.getString("ai_name", null)?.trim().orEmpty().ifEmpty { "Cumulus IA" }
            val userName = namePrefs.getString("user_name", null)?.trim().orEmpty()
            // Tu personalidad escrita reemplaza el tono por defecto
            val personality = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("custom_personality", "")?.trim().orEmpty()
            val level = thinkLevel()
            val (thinkText, extraTokens) = thinkInstruction(level)
            // 🧭 Opción A: instrucciones cortas y claras (rol del modo + nombre, fecha, internet, formato y tono).
            //    Antes se mandaba una guía larga (más de 1.500 tokens) con reglas que se contradecían.
            val modeNamed = rawMode.copy(
                systemPrompt = buildSystemPrompt(rawMode, aiName, userName, today, personality) + "\n" + thinkText.trim(),
                maxTokens = (rawMode.maxTokens + extraTokens).coerceAtMost(4096)
            )
            // 💻 Conversación de código (pedido de código o continuación de un código): respuesta larga permitida,
            //    para que no corte el código a la mitad aunque estés en el modo General
            val lastWasCode = history.lastOrNull { it.first == "assistant" }?.second?.contains("```") == true
            // 🛠️ Pedido de crear una app / página, o trae código pegado como formato
            val creation = rawMode.key != "traduccion" && attachments.isEmpty() && runCatching { isCreationRequest(prompt) }.getOrDefault(false)
            val codeTalk = rawMode.key == "codigo" || Regex("(?i)\\b(c[oó]digo|app|aplicaci[oó]n|funci[oó]n|clase|kotlin|java|python|html|css|javascript|script|programa|android|layout|xml)\\b").containsMatchIn(prompt) ||
                (lastWasCode && isFollowUp(prompt)) || creation
            val mode = if (codeTalk) modeNamed.copy(maxTokens = maxOf(modeNamed.maxTokens, 4096)) else modeNamed
            // ✦ Ventana de actividad: modo y ajustes que se aplican
            AiActivity.log("🧭 Modo ${mode.label} · pensamiento $level",
                "Instrucciones: ${mode.systemPrompt.lines().size} líneas (≈${mode.systemPrompt.length / 4} tokens)\n" +
                    "Historial: ${history.size} mensajes · respuesta de hasta ${mode.maxTokens} tokens")
            if (creation) AiActivity.analyzing("🛠️ Es un pedido para crear: empiezo a programarlo" +
                (if (hasPastedCode(prompt)) " con el código que me pasaste como base" else ""))
            else if (codeTalk) AiActivity.analyzing("💻 Analizando el pedido de código")
            var usedWeb = false
            var webSource = "búsqueda web"
            var webSourceUrls: List<String> = emptyList()
            val askedText = prompt   // lo que escribió el usuario (sin los datos que se agregan después)
            var prompt = prompt
            val followUp = isFollowUp(prompt)
            // 🕒 Si preguntas la hora o la fecha, se usa el reloj del teléfono (exacto) y no internet
            val asksTime = Regex("(?i)\\b(qu[eé] hora|la hora|hora es|hora actual|qu[eé] d[ií]a|fecha|qu[eé] fecha|d[ií]a es hoy|en qu[eé] a[ñn]o|qu[eé] mes)\\b")
                .containsMatchIn(prompt)
            val timeNow = java.text.SimpleDateFormat("HH:mm", Locale("es", "ES")).format(java.util.Date())
            // 🧭 Opción A: la fecha ya va en las instrucciones; la hora exacta solo se agrega cuando la preguntan
            val datePrefix = if (asksTime)
                "(Reloj del teléfono: $now, hora $timeNow, zona ${java.util.TimeZone.getDefault().id}. Si pregunta la hora o la fecha, usa este dato exacto.)\n"
            else ""
            // 🌐 Modo agente: la IA decide sola qué buscar y qué páginas abrir (motor GGUF, solo texto)
            // 📅 Pedidos diarios (noticias, clima, dólar, comida): la app trae los datos reales
            dailyDirect = null
            dailyDirectHeadlines = emptyList()
            // 📰 «Más detalles de la 3» justo después de una lista de noticias: se busca ESA noticia (no una lista nueva)
            val lastAssistantEarly = history.lastOrNull { it.first == "assistant" }?.second
            val newsItem = if (!creation && mode.key != "traduccion" && mode.key != "codigo" && attachments.isEmpty())
                runCatching { newsItemAsked(prompt, lastAssistantEarly) }.getOrNull() else null
            // 🌤️ «¿Y mañana?» justo después del clima: el clima de esa misma ciudad
            val weatherCity = if (!creation && newsItem == null && !asksTime && mode.key != "traduccion" && mode.key != "codigo" && attachments.isEmpty() && isInternetEnabled())
                lastAssistantEarly?.let { Regex("^🌤️ Clima en ([^\\n,]+)").find(it.trim())?.groupValues?.get(1)?.trim() }
                    ?.takeIf {
                        val startsY = rxAny(prompt, "^\\s*¿?\\s*(y|e|para|qu[eé] tal)\\b")
                        val dayWord = rxAny(prompt, "ma[ñn]ana", "pasado", "semana", "lunes", "martes", "mi[eé]rcoles", "jueves",
                            "viernes", "s[aá]bado", "domingo", "\\bhoy\\b", "tarde", "noche")
                        val weatherWord = rxAny(prompt, "llov", "lluvia", "fr[ií]o", "calor", "temperatura", "grados", "nublado")
                        val cityChange = rxAny(prompt, "^\\s*¿?\\s*y\\s+(en|para)\\s+\\p{L}")
                        prompt.trim().split(Regex("\\s+")).size <= 8 && (cityChange || (startsY && dayWord) || weatherWord)
                    }
                else null
            if (newsItem != null) AiActivity.analyzing("📰 Me pides más de una noticia de la lista", newsItem)
            if (weatherCity != null) AiActivity.analyzing("🌤️ Sigo con el clima de $weatherCity")
            val dailyData = when {
                newsItem != null -> null
                weatherCity != null -> runCatching { weatherContext("$prompt en $weatherCity") }.getOrNull()
                !followUp && !asksTime && prompt.isNotBlank() && mode.key != "traduccion" ->
                    // 🛠️ Pedido de crear (o modo código): solo «actualízate en…» y «busca en internet…»; nunca noticias,
                    //    clima, divisas ni comida (antes «créame una app de noticias» entregaba las noticias de hoy)
                    runCatching { dailyIntentContext(prompt, codeOnly = creation || mode.key == "codigo") }.getOrNull()
                else -> null
            }
            if (dailyData != null) {
                webSource = when {
                    dailyData.startsWith("Noticias") -> "Google Noticias"
                    dailyData.startsWith("Clima") -> "Open-Meteo (clima)"
                    dailyData.startsWith("Tipo de cambio") -> "open.er-api.com (divisas)"
                    dailyData.startsWith("Resultados de internet") -> "búsqueda web (DuckDuckGo)"
                    else -> ""
                }
                if (webSource.isNotEmpty()) usedWeb = true
                // 📋 Opción B: noticias, clima y divisas → la APP arma la respuesta con los datos EXACTOS.
                //    La IA no los reescribe (así no repite, no mezcla ni inventa); solo agrega un resumen corto a las noticias.
                val direct = dailyDirect
                if (direct != null && attachments.isEmpty()) {
                    dailyDirect = null
                    AiActivity.log("📋 Armo la respuesta con los datos exactos (la IA no los cambia)")
                    val summary = if (dailyData.startsWith("Noticias") && !stopRequested) {
                        AiActivity.log("✍️ La IA escribe un resumen corto de los titulares")
                        newsSummary(dailyDirectHeadlines, backend)
                    } else null
                    return AiReply(
                        (if (summary != null) "🗞️ En resumen: $summary\n\n" else "") + direct,
                        "📋 Datos exactos armados por la app" + (if (summary != null) " · ✍️ resumen corto de la IA" else "") +
                            "\n🌐 Datos de: $webSource"
                    )
                }
                prompt = "$dailyData\n\nPregunta del usuario: $prompt"
            }
            if (newsItem != null) {
                val title = newsItem.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
                val web = if (isInternetEnabled()) runCatching { fetchWebContext(title) }.getOrNull() else null
                prompt = if (web != null) {
                    usedWeb = true
                    webSource = "búsqueda web (detalle de la noticia)"
                    webSourceUrls = Regex("Fuente: (https?://\\S+)").findAll(web).map { it.groupValues[1] }.distinct().take(4).toList()
                    "Resultados de internet sobre la noticia «$newsItem»:\n\n$web\n\n" +
                        "Con SOLO estos resultados responde: «$prompt». Cuenta qué pasó, dónde, cuándo y quiénes; si no alcanza, dilo."
                } else "$prompt\n\n(Se refiere a la noticia «$newsItem». Solo tienes el titular: no inventes detalles.)"
            }
            // 🌐 Igual que LLM Hub: la APP busca siempre en internet y le entrega los resultados al modelo.
            //    (El "modo agente", donde el modelo decide qué buscar, queda desactivado: los modelos
            //     pequeños se equivocaban al usar las herramientas y terminaban inventando.)
            val agentWeb = WEB_AGENT_MODE && isInternetEnabled() && prompt.isNotBlank() && !asksTime && dailyData == null &&
                mode.key != "traduccion" &&
                attachments.none { val k = attachmentKind(it); k == AttachKind.IMAGE || k == AttachKind.AUDIO || k == AttachKind.VIDEO }
            if (!agentWeb && dailyData == null && newsItem == null && !creation && isInternetEnabled() && prompt.isNotBlank() && mode.key != "traduccion" &&
                !asksTime && needsWebSearch(prompt) && attachments.isEmpty()) {   // con archivos adjuntos se responde sobre el archivo, sin buscar
                // En modo código se busca en documentación y foros de programación
                val webQuery = if (mode.key == "codigo") {
                    // Solo la pregunta (sin el código pegado) + sitios de programación
                    val question = prompt.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
                        .lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
                    "$question (documentación OR stackoverflow OR github)"
                } else cleanSearchQuery(prompt)
                AiActivity.analyzing()
                val web = fetchWebContext(webQuery)
                if (web == null) AiActivity.log("🌐 Internet no trajo nada útil: respondo con lo que sé")
                if (web != null) {
                    usedWeb = true
                    webSource = "búsqueda web"
                    webSourceUrls = Regex("Fuente: (https?://\\S+)").findAll(web).map { it.groupValues[1] }.distinct().take(4).toList()
                    val question = prompt
                    // 🧭 Opción A: una sola instrucción clara (antes eran 7 reglas repetidas)
                    prompt = "Resultados de internet de ahora mismo:\n\n$web\n\n" +
                        "Con SOLO estos resultados responde: «$question». Primero la respuesta directa y luego los detalles " +
                        "importantes; usa fechas, cifras y nombres tal cual aparecen y, si no alcanza, dilo."
                }
            }
            // 🧹 Igual que LLM Hub: cuando la respuesta usa datos de internet, se responde en un contexto limpio
            //    (sin la conversación anterior), para que el modelo no mezcle respuestas viejas con los datos nuevos
            // 🧵 Con datos de internet ya no se borra TODO: se conserva el último intercambio para no perder el hilo
            val history = if (usedWeb && dailyData?.startsWith("Contexto:") != true) history.takeLast(2) else history
            // Separa los adjuntos por tipo
            val textFiles = attachments.filter { attachmentKind(it) == AttachKind.TEXT }
            // ✦ Archivos que la IA va a analizar (ventana de actividad)
            attachments.filter { attachmentKind(it) == AttachKind.IMAGE }.let { imgs ->
                if (imgs.isNotEmpty()) AiActivity.analyzing("🖼️ Mirando ${imgs.size} imagen(es)", imgs.joinToString("\n") { it.name.substringAfter('_') })
            }
            textFiles.forEach { AiActivity.analyzing("📄 Leyendo el archivo «${it.name.substringAfter('_')}»", "${(it.length() + 1023) / 1024} KB") }
            if (attachments.any { attachmentKind(it) == AttachKind.VIDEO }) AiActivity.analyzing("🎬 Sacando cuadros del video para mirarlo")
            // 🎬 Videos: se sacan cuadros repartidos en el tiempo y la IA los ve como imágenes en orden
            val videoInfo = StringBuilder()
            val videoFrames = attachments.filter { attachmentKind(it) == AttachKind.VIDEO }.flatMap { v ->
                val (frames, desc) = extractVideoFrames(v)
                if (frames.isNotEmpty()) videoInfo.append(desc).append('\n')
                frames
            }
            val mediaFiles = attachments.filter { attachmentKind(it) == AttachKind.AUDIO } +
                // 🖼️ Toda imagen (WEBP, HEIC, AVIF, RAW, GIF...) se convierte a JPG para que el motor la lea siempre bien
                attachments.filter { attachmentKind(it) == AttachKind.IMAGE }.map { normalizeImageForModel(it) } +
                videoFrames
            val unsupported = attachments.filter {
                val k = attachmentKind(it)
                k == AttachKind.OTHER || (k == AttachKind.VIDEO && videoFrames.isEmpty())
            }
            // Archivos de texto/código: se leen y van dentro del mensaje
            var fullPrompt = if (videoInfo.isNotEmpty())
                videoInfo.toString() + "Mira los cuadros en orden como si fuera el video y responde: " +
                    prompt.ifBlank { "¿Qué pasa en este video? Descríbelo de principio a fin." }
            else prompt
            if (textFiles.isNotEmpty()) {
                // Cuánto texto de archivos cabe según el CONTEXTO elegido
                val ctxTokens = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("ai_context", 2048).coerceIn(2048, 15000)
                val totalChars = ((ctxTokens - 1200) * 2.5).toInt().coerceIn(4000, 70000)
                val perFile = (totalChars / textFiles.size).coerceAtLeast(800)
                val joined = textFiles.joinToString("\n\n") { f ->
                    val content = readAttachmentText(f).take(perFile)
                    "Archivo adjunto «${f.name.substringAfter('_')}»:\n```\n$content\n```"
                }
                fullPrompt = joined + "\n\n" + prompt.ifBlank {
                    if (textFiles.size > 1) "Analiza estos archivos." else "Analiza este archivo."
                }
            }
            if (videoFrames.isNotEmpty()) AiActivity.log("🎞️ ${videoFrames.size} cuadros del video listos")
            if (unsupported.isNotEmpty()) AiActivity.log("⚠️ ${unsupported.size} adjunto(s) que todavía no puedo leer",
                unsupported.joinToString("\n") { it.name.substringAfter('_') })
            val unsupportedNote = if (unsupported.isNotEmpty()) {
                "\n\n⚠️ No pude leer ${unsupported.size} adjunto(s) (videos u otros formatos todavía no son compatibles)."
            } else ""

            val hasText = fullPrompt.isNotBlank()
            // 🧭 Opción A: el mensaje va limpio. Ya no se pegan notas entre corchetes ([Dato interno…], [Modo activo…],
            //    [Regla…]): nombre, fecha, modo e internet ya están en las instrucciones cortas del sistema.
            //    Solo se agrega lo que de verdad sirve: la hora exacta si la preguntas y tus notas aprendidas del tema.
            val learned = if (pendingLearnTopic == null) knowledgeFor(prompt) else ""
            if (learned.isNotBlank()) AiActivity.log("📚 Uso las notas que aprendiste de internet sobre este tema")
            fullPrompt = datePrefix + learned + fullPrompt
            // 🧵 Continuación: «ok expándelo», «agrégale la lógica»… La conversación va con sus ROLES reales
            //    (usuario / asistente), así que la respuesta anterior ya está en el historial: no se vuelve a pegar
            //    (antes se pegaba dos veces y llenaba el contexto). Solo se agrega un recordatorio corto al final.
            val lastAssistant = history.lastOrNull { it.first == "assistant" }?.second
            val lastUserMsg = history.lastOrNull { it.first == "user" }?.second
            // 🧩 ENTREGA POR PARTES: «créame una app … dámela por partes» → Parte 1, luego «siguiente» → Parte 2…
            val partsRegex = Regex("(?i)(por partes|en partes|parte por parte|por etapas|paso a paso|de a poco|dividid[oa])")
            val partsHere = partsRegex.containsMatchIn(prompt)
            val partsIdx = history.indexOfLast { it.first == "user" && partsRegex.containsMatchIn(it.second) }
            val partsOngoing = !partsHere && newsItem == null && partsIdx >= 0 && (followUp ||
                Regex("(?i)\\b(siguiente|pr[oó]xim[ao]|sigue|contin[uú]a|parte\\s*\\d+|la que sigue|otra parte|m[aá]s)\\b").containsMatchIn(prompt))
            if (partsHere) {
                fullPrompt += "\n\n(Entrégalo por partes: primero un plan corto con 4 a 6 partes numeradas y luego SOLO la «Parte 1» " +
                    "completa. Al final di qué trae la Parte 2 y que escriba «siguiente» para continuar.)"
            } else if (partsOngoing) {
                val original = history[partsIdx].second.take(400)
                val delivered = history.drop(partsIdx + 1).count { it.first == "assistant" }.coerceAtLeast(1)
                fullPrompt += "\n\n(Entrega por partes del pedido «$original»: ya entregaste $delivered. Ahora entrega SOLO la " +
                    "«Parte ${delivered + 1}», nueva y conectada con lo anterior, empezando con «Parte ${delivered + 1}:». " +
                    "Si ya está todo, dilo y explica cómo unir las partes.)"
            }
            // 🛠️ Pedido para CREAR: que empiece de inmediato y entregue todo (no preguntas, no «versión básica»)
            if (creation && !followUp && !partsOngoing) {
                fullPrompt += "\n\n(Es un pedido para CREAR: empieza ya y entrega el código completo y funcional, sin preguntar y sin «versión básica»." +
                    (if (hasPastedCode(askedText)) " Usa el código que te pasó el usuario como base y respeta su formato." else "") + ")"
            }
            // 🖥️/📱 App web o Android: código completo y separado por archivo, para verlo en la vista previa o la maqueta
            if (codeTalk && attachments.isEmpty() && !partsOngoing) when (runCatching { codePreviewKind(askedText, lastAssistant) }.getOrNull()) {
                "web" -> fullPrompt += "\n\n(Entrega el código COMPLETO y funcional, cada archivo en su propio bloque: index.html, style.css y script.js. " +
                    "Se va a probar en la vista previa web.)"
                "app" -> fullPrompt += "\n\n(Además del código Kotlin, incluye el diseño XML de cada pantalla (res/layout) para verlo en la maqueta de la app.)"
            }
            if (!partsHere && !partsOngoing && followUp && newsItem == null && !lastAssistant.isNullOrBlank()) {
                fullPrompt += "\n\n(Sigue sobre tu respuesta anterior" +
                    (if (!lastUserMsg.isNullOrBlank()) " al pedido «${lastUserMsg.take(200)}»" else "") +
                    ": continúa, amplía o modifica eso mismo. Si es código, entrégalo COMPLETO y ya actualizado.)"
            }
            // 💻🌐 Código con internet: si pides crear/implementar algo y el internet está activado, la app busca
            //    ejemplos reales en Stack Overflow y se los da a la IA como referencia (no reemplaza tu conversación)
            if (codeTalk && !followUp && !usedWeb && isInternetEnabled() && attachments.isEmpty() && prompt.isNotBlank() &&
                Regex("(?i)\\b(cr[eé]a|cr[eé]ame|haz|hazme|genera|implementa|agrega|a[ñn]ade|ejemplo|c[oó]mo (hago|se hace|puedo|hacer)|necesito|quiero|busca|usa|integra|conecta)\\b").containsMatchIn(prompt)) {
                projectProgress("🔎 Buscando código de referencia en internet...")
                val ref = runCatching { fetchCodeFromInternet(englishCodeQuery(prompt, backend)) }.getOrNull()
                AiActivity.log(if (ref != null) "📄 Encontré ${ref.second.size} ejemplo(s) de código en Stack Overflow" else "🌐 Stack Overflow no trajo ejemplos útiles",
                    ref?.second?.take(4)?.joinToString("\n").orEmpty())
                if (ref != null) {
                    usedWeb = true
                    webSource = "Stack Overflow (código de referencia)"
                    webSourceUrls = ref.second.take(4)
                    fullPrompt = "Código de referencia de Stack Overflow (adáptalo si sirve; si no encaja, no lo copies):\n" +
                        ref.first + "\n" + fullPrompt
                }
            }
            // ✦ Ventana de actividad: motor y ajustes con los que se escribe la respuesta
            runCatching {
                val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val engineNow = when {
                    usingLiteRt -> LiteRtLmEngineManager.engineInfo()
                    GenieXEngineManager.isLoaded(activeModel?.absolutePath) -> GenieXEngineManager.engineInfo()
                    else -> runCatching { nativeEngineInfo() }.getOrDefault("💻 CPU")
                }
                AiActivity.log("⚙️ Motor: $engineNow",
                    "Modelo: ${activeModel?.name ?: "—"}\n" +
                        "Contexto: ${p.getInt("ai_context", 2048).coerceIn(2048, 15000)} · temperatura ${p.getFloat("ai_temperature", 0.7f)} · " +
                        "respuesta de hasta ${mode.maxTokens} tokens\n" +
                        "Tu mensaje con datos: ≈${fullPrompt.length / 4} tokens" + (if (mediaFiles.isNotEmpty()) " · ${mediaFiles.size} imagen(es)/audio(s)" else ""))
                AiActivity.log("✍️ Escribiendo la respuesta...")
            }
            val modelRaw = if (usingLiteRt) {
                if (mediaFiles.isNotEmpty()) {
                    "⚠️ Las imágenes y audios solo funcionan con modelos GGUF que tengan su mmproj."
                } else if (!hasText) {
                    unsupportedNote.trim()
                } else {
                    // ⚡ LiteRT (GPU/NPU): mismo cerebro que el motor GGUF — modos, memoria, internet con agente
                    val sysText = mode.systemPrompt +
                        (if (agentWeb) agentToolInstructions(mode.key == "codigo") else "")
                    val liteGen: (List<String>, List<String>) -> String = { rl, ct ->
                        // 🧵 Opción A: conversación con ROLES NATIVOS (sistema / usuario / modelo), igual que CPU y GenieX
                        runCatching { runBlocking { LiteRtLmEngineManager.generateChat(sysText, rl, ct) } }.getOrElse { e ->
                            Log.w(TAG, "LiteRT con roles nativos falló (${e.message}); uso el formato de texto")
                            val conv = StringBuilder()
                            conv.append("[Instrucciones del sistema: ").append(sysText).append("]\n\n")
                            val n = rl.size
                            if (n > 1) conv.append("[Conversación hasta ahora]\n")
                            for (i in 0 until n) {
                                if (i == n - 1 && rl[i] == "user") {
                                    if (n > 1) conv.append("\n[Mensaje actual del usuario]\n")
                                    conv.append(ct[i])
                                } else {
                                    conv.append(if (rl[i] == "user") "Usuario: " else "Asistente: ")
                                        .append(ct[i]).append("\n")
                                }
                            }
                            runBlocking { LiteRtLmEngineManager.generateFresh(conv.toString()) }
                        }
                    }
                    val trimmed = trimHistory(history, historyCharBudget() - fullPrompt.length)
                    val rl = (trimmed.map { it.first } + "user").toMutableList()
                    val ct = (trimmed.map { it.second } + fullPrompt).toMutableList()
                    if (agentWeb) {
                        val (answer, actions) = runWebAgent(rl, ct, prompt, liteGen)
                        if (actions > 0) usedWeb = true
                        answer + unsupportedNote
                    } else {
                        liteGen(rl, ct) + unsupportedNote
                    }
                }
            } else {
                nativeSetChatMode(
                    if (agentWeb) mode.systemPrompt + agentToolInstructions(mode.key == "codigo") else mode.systemPrompt,
                    mode.maxTokens
                )
                val usingGenieX = GenieXEngineManager.isLoaded(activeModel?.absolutePath)
                when {
                    // ⚡ GenieX (GGUF en NPU/GPU): mismo cerebro — modos, memoria, internet con agente
                    // 👁️ Visión en NPU/GPU (modelo cargado con su mmproj): fotos, cuadros de video y cámara
                    usingGenieX && mediaFiles.isNotEmpty() && GenieXEngineManager.hasVision() &&
                        mediaFiles.any { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp") } -> {
                        val imgs = mediaFiles.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp") }
                        val temp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat("ai_temperature", 0.7f)
                        // 🎙️ Si además hay audios, van como texto (transcripción) junto a las imágenes
                        val auds = mediaFiles.filter { it.extension.lowercase() in AUDIO_EXTS }
                        val heard = if (auds.isNotEmpty()) audiosToText(auds) else ""
                        runBlocking {
                            GenieXEngineManager.generateWithImages(mode.systemPrompt, fullPrompt.ifBlank { "Describe la imagen." } + heard,
                                imgs.map { it.absolutePath }, mode.maxTokens, temp)
                        } + unsupportedNote
                    }
                    // 🎙️ Audios en NPU/GPU: se transcriben y el modelo responde sobre el texto
                    usingGenieX && mediaFiles.isNotEmpty() &&
                        mediaFiles.any { it.extension.lowercase() in AUDIO_EXTS } -> {
                        val auds = mediaFiles.filter { it.extension.lowercase() in AUDIO_EXTS }
                        val heard = audiosToText(auds)
                        val temp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat("ai_temperature", 0.7f)
                        val ask = fullPrompt.ifBlank { "Resume y responde a lo que se dice en el audio." } + heard
                        val imgNote = if (auds.size < mediaFiles.size)
                            "\n\n⚠️ Para ver imágenes en NPU/GPU elige el mmproj del modelo (☰ → botón mmproj) y vuelve a cargar el modelo." else ""
                        runBlocking { GenieXEngineManager.generate(mode.systemPrompt, listOf("user"), listOf(ask), mode.maxTokens, temp) } +
                            imgNote + unsupportedNote
                    }
                    usingGenieX && mediaFiles.isNotEmpty() ->
                        "⚠️ Para ver imágenes en NPU/GPU elige el mmproj del modelo (☰ → botón mmproj) y vuelve a cargar el modelo. También puedes usar CPU."
                    usingGenieX && hasText -> {
                        val sysText = mode.systemPrompt +
                            (if (agentWeb) agentToolInstructions(mode.key == "codigo") else "")
                        val temp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat("ai_temperature", 0.7f)
                        val gxGen: (List<String>, List<String>) -> String = { rl, ct ->
                            runBlocking { GenieXEngineManager.generate(sysText, rl, ct, mode.maxTokens, temp) }
                        }
                        val trimmedGx = trimHistory(history, historyCharBudget() - fullPrompt.length)
                        val rl = (trimmedGx.map { it.first } + "user").toMutableList()
                        val ct = (trimmedGx.map { it.second } + fullPrompt).toMutableList()
                        if (agentWeb) {
                            val (answer, actions) = runWebAgent(rl, ct, prompt, gxGen)
                            if (actions > 0) usedWeb = true
                            answer + unsupportedNote
                        } else gxGen(rl, ct) + unsupportedNote
                    }
                    mediaFiles.isNotEmpty() && !nativeHasVision() ->
                        "⚠️ Para leer imágenes o audios necesitas un modelo multimodal con su mmproj. " +
                            "Elígelo en ☰ → botón mmproj."
                    mediaFiles.isNotEmpty() ->
                        nativeRunInferenceWithMediaList(
                            fullPrompt, mediaFiles.map { it.absolutePath }.toTypedArray()
                        ) + unsupportedNote
                    // 🧠 Texto: se envía con el historial para que recuerde la conversación
                    hasText -> {
                        val budget = historyCharBudget() - fullPrompt.length
                        val trimmed = trimHistory(history, budget)
                        if (agentWeb) {
                            val (answer, actions) = runWebAgent(
                                (trimmed.map { it.first } + "user").toMutableList(),
                                (trimmed.map { it.second } + fullPrompt).toMutableList(),
                                prompt
                            )
                            if (actions > 0) usedWeb = true
                            answer + unsupportedNote
                        } else {
                            nativeRunChat(
                                (trimmed.map { it.first } + "user").toTypedArray(),
                                (trimmed.map { it.second } + fullPrompt).toTypedArray()
                            ) + unsupportedNote
                        }
                    }
                    else -> "⚠️ Por ahora puedo leer imágenes, audios y archivos de texto o código. " +
                        "Videos y otros archivos todavía no son compatibles."
                }
            }
            // 🛟 Si la app SÍ trajo datos de internet pero el modelo dice que no tiene acceso,
            //    se entregan los datos directamente (el usuario nunca se queda sin su respuesta)
            val raw = if (dailyData != null && usedWeb && !stopRequested &&
                Regex("(?i)(no tengo acceso|no puedo acceder|no tengo (la )?informaci[oó]n|no dispongo|activa(r)? (el )?internet|no puedo (darte|entregarte|ver) (las )?(noticias|el clima)|no est[aá] disponible)").containsMatchIn(modelRaw)
            ) dailyFallback(dailyData).also { AiActivity.log("🛟 El modelo dijo que no tenía acceso: entrego los datos directo") } else modelRaw.let { r ->
                // 📎 Fuentes reales (las pone la app, así nunca son inventadas)
                if (webSourceUrls.isEmpty() || stopRequested) r
                else r.trimEnd() + "\n\nFuentes:\n" + webSourceUrls.joinToString("\n") { "• $it" }
            }
            val tokenCount = raw.split(" ").size
            val gxActive = !usingLiteRt && GenieXEngineManager.isLoaded(activeModel?.absolutePath)
            val engine = if (gxActive) GenieXEngineManager.engineInfo() else if (usingLiteRt) {
                LiteRtLmEngineManager.engineInfo()
            } else {
                try { nativeEngineInfo() } catch (t: Throwable) { "💻 CPU (NEON)" }
            }
            AiReply(removeRepeatedLines(stripMarkdownSymbols(removeThinking(raw))), "$tokenCount tokens · $engine · 💭 $level" +
                (if (gxActive) GenieXEngineManager.lastStats().let { if (it.isNotEmpty()) " · $it" else "" }
                 else if (!usingLiteRt) runCatching { " · " + nativeLastStats() }.getOrDefault("")
                 else LiteRtLmEngineManager.lastStats().let { if (it.isNotEmpty()) " · $it" else "" }) + when {
                    usedWeb -> "\n🌐 Datos de: $webSource"
                    // Internet encendido pero la pregunta no necesitaba buscar (ej. «hola, ¿cómo te llamas?")
                    isInternetEnabled() -> "\n🌐 Internet activado · no hizo falta buscar"
                    else -> "\n⚠️ Sin datos de internet"
                })
        } catch (t: Throwable) {
            Log.e(TAG, "Fallo del motor nativo: ${t.message}")
            // Se muestra el motivo real del error (antes solo salía "RuntimeException")
            val why = generateSequence(t) { it.cause }.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                .distinct().take(2).joinToString(" → ").take(300)
            AiReply(ENGINE_FAIL_MARK + " (${t.javaClass.simpleName})" +
                (if (why.isNotEmpty()) ":\n$why" else ".") +
                "\n\nSi se repite, toca «Nuevo chat» o vuelve a cargar el modelo.", "")
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
        val ctx = (seekContext?.progress ?: 2048).coerceAtLeast(2048)
        val temp = (seekTemperature?.progress ?: 70) / 100f
        Log.d(TAG, "Aplicando parámetros: ctx=$ctx, temp=$temp")
        try {
            nativeSetParams(ctx, temp)
            Log.d(TAG, "✅ Parámetros aplicados exitosamente")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error aplicando parámetros: ${t.message}")
        }
    }

    // 📋 Últimas líneas importantes del registro (la app solo puede leer las suyas)
    private fun readCrashLog(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000", "-v", "brief"))
        val lines = proc.inputStream.bufferedReader().readLines()
        val clean = { l: String -> l.replace(Regex("\\s+"), " ").take(200) }
        // Inicio del último cierre (encabezado + primeros pasos de la pila)
        val start = lines.indexOfLast { it.contains("*** *** ***") || it.contains("Build fingerprint") }
        // Sin registro de cierre real (la app solo se cerró a mano o Android la sacó): no hay nada que mostrar
        if (start < 0) return@runCatching ""
        val crash = if (start >= 0) lines.drop(start).filter {
            it.contains("signal") || it.contains("Abort message") || it.contains("Cause") ||
                Regex("#0[0-9] ").containsMatchIn(it) || it.contains("#1[0-2] ")
        }.take(16).map(clean) else emptyList()
        // Mensajes del motor antes del cierre
        val keys = Regex("ggml|hex|htp|HTP|LiteRt|Qnn|QNN|Dispatch|CumulusNative|AdrenoVK|vulkan")
        val engine = lines.take(if (start >= 0) start else lines.size)
            .filter { keys.containsMatchIn(it) }.takeLast(18).map(clean)
        (crash + listOf("— motor —") + engine).joinToString("\n")
    }.getOrDefault("")

    // Muestra el motor REAL: los GGUF siempre van en CPU; los .litertlm dicen qué usó LiteRT
    private fun realEngineLabel(modelFile: File, backend: String): String =
        if (modelFile.extension.equals("litertlm", true)) LiteRtLmEngineManager.engineInfo()
        else if (GenieXEngineManager.isLoaded(modelFile.absolutePath)) GenieXEngineManager.engineInfo()
        else "💻 CPU" + if (backend.contains("GPU", true) || backend.contains("NPU", true)) " (respaldo)" else ""

    private fun loadModelWithConfiguration(modelFile: File, backend: String): Boolean {
        // ⏳ Un modelo que todavía se está descargando está incompleto: no se carga (antes daba
        //    «model is corrupted or incomplete» y la ventana de error)
        if (ActiveDownloads.paths.contains(modelFile.absolutePath)) {
            Log.w(TAG, "No se carga ${modelFile.name}: la descarga aún no termina")
            runOnUiThread { tvModelLoadedName?.text = "Modelo: ${modelFile.name} (⏳ esperando que termine la descarga)" }
            return false
        }
        // 🛡️ Si cargar en GPU/NPU cierra la app, al volver a abrirla se pasa a CPU
        val risky = true  // siempre se vigila: si se cierra, al volver se muestra el motivo
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (risky) prefs.edit().putBoolean("gpu_run_pending", true).commit()
        try {
            return loadModelWithConfigurationInner(modelFile, backend)
        } finally {
            if (risky) prefs.edit().putBoolean("gpu_run_pending", false).commit()
        }
    }

    private fun loadModelWithConfigurationInner(modelFile: File, backend: String): Boolean {

        if (modelFile.extension.equals("litertlm", ignoreCase = true)) {
            // 🛡️ Modelos compilados para la NPU de OTRO chip cierran la app: se bloquean antes
            val n = modelFile.name.lowercase()
            val otherChip = Regex("mediatek|\\bmt6\\d{3}|google_tensor|tensor_g\\d|intel_|qcs\\d{4}|exynos").find(n)
            val mySoc = (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE).lowercase()
            val qcomOther = Regex("sm(\\d{4})").find(n)?.groupValues?.get(1)?.let { !mySoc.contains(it) } ?: false
            // LiteRT ya no usa la NPU (la NPU es de GenieX): modelos solo-NPU se bloquean
            if (n.contains("qualcomm") || n.contains("_npu") || n.contains("-npu")) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("ℹ️ Modelo solo para NPU")
                        .setMessage("«${modelFile.name}» es un .litertlm compilado para NPU.\n\n" +
                            "La NPU ahora se usa con GenieX y modelos GGUF Q4_0.\n" +
                            "Para .litertlm usa uno normal o -gpu en GPU (Adreno).")
                        .setPositiveButton("OK", null).show().also { styleDarkRoundDialog(it) }
                }
                return false
            }
            if (otherChip != null || qcomOther) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Modelo para otro chip")
                        .setMessage("«${modelFile.name}» está compilado para la NPU de otro procesador " +
                            "(${otherChip?.value ?: "otro Snapdragon"}) y cerraría la app en este teléfono ($mySoc).\n\n" +
                            "Usa un .litertlm normal o -gpu (GPU), o uno hecho para tu chip.")
                        .setPositiveButton("OK", null).show().also { styleDarkRoundDialog(it) }
                }
                return false
            }
            nativeReleaseModel()
            return runBlocking {
                LiteRtLmEngineManager.load(
                    applicationContext,
                    modelFile.absolutePath,
                    if (backend.contains("NPU", true)) "🎮 GPU (Adreno)" else backend,
                    "main_activity"
                )
            }
        }
        runBlocking { LiteRtLmEngineManager.release("main_activity") }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val contextSize = prefs.getInt("ai_context", 2048).coerceIn(2048, 15000)
        val temperature = prefs.getFloat(
            "ai_temperature",
            (seekTemperature?.progress ?: 70) / 100f
        )
        // ⚡ GGUF en NPU/GPU → GenieX (llama.cpp con los parches de Qualcomm)
        if (backend.contains("GPU", true) || backend.contains("NPU", true)) {
            nativeReleaseModel()
            val ok = runCatching {
                runBlocking { GenieXEngineManager.load(modelFile.absolutePath, backend, contextSize, getSelectedMmprojFile()?.absolutePath) }
            }.getOrDefault(false)
            if (ok) {
                val npuWhy = GenieXEngineManager.lastLoadErrors()
                if (GenieXEngineManager.engineInfo().contains("NPU→") && npuWhy.isNotBlank()) {
                    runOnUiThread {
                        val full = "Modelo: ${modelFile.name}\nLa NPU no aceptó el modelo, se usa la GPU:\n\n$npuWhy"
                        AlertDialog.Builder(this)
                            .setTitle("ℹ️ NPU → GPU")
                            .setMessage(full)
                            .setPositiveButton("Copiar") { _, _ ->
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("NPU", full))
                            }
                            .setNegativeButton("OK", null).show().also { styleDarkRoundDialog(it) }
                    }
                }
                return true
            }
            val why = GenieXEngineManager.lastLoadErrors()
            runOnUiThread {
                val full = "Modelo: ${modelFile.name}\nBackend: $backend\n\n$why\n\n" + GenieXEngineManager.diagnostics(this)
                AlertDialog.Builder(this)
                    .setTitle("⚠️ GenieX no pudo cargar → se usa CPU")
                    .setMessage(full)
                    .setPositiveButton("Copiar") { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("GenieX error", full))
                        cumulusToast(this, "Error copiado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show().also { styleDarkRoundDialog(it) }
            }
        }
        runCatching { runBlocking { GenieXEngineManager.release() } }
        nativeSetMmproj(getSelectedMmprojFile()?.absolutePath ?: "")
        return nativeConfigureAndLoadModel(
            modelFile.absolutePath,
            "💻 CPU (NEON)",
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
        
        cumulusToast(this, "Recargando modelo en $backend...", Toast.LENGTH_SHORT).show()
        thread {
            val success = loadModelWithConfiguration(modelFile, backend)
            runOnUiThread {
                isModelLoadedInMemory = success
                updateLoadModelButton()
                if (success) {
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [${realEngineLabel(modelFile, backend)} - Cargado]" + mediaCapsLabel()
                    cumulusToast(this, "✅ Modelo recargado exitosamente en $backend", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "✅ Modelo recargado exitosamente en $backend")
                } else {
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} (error al recargar en $backend)"
                    cumulusToast(this, "❌ Error al recargar modelo en $backend", Toast.LENGTH_LONG).show()
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
        chatAdapter?.notifyDataSetChanged()
        if (showToast) {
            cumulusToast(this, "Nombre restablecido a Cumulus IA", Toast.LENGTH_SHORT).show()
        }
        refreshSaveChecks()
    }

    /** ✔ negro en GUARDAR de personalidad, nombre de la IA y nombre del usuario cuando lo escrito ya está guardado. */
    private fun refreshSaveChecks() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fun same(saved: String?, typed: CharSequence?) =
            !saved.isNullOrBlank() && saved.trim() == typed?.toString()?.trim()
        setButtonCheck(btnSaveCustomPersonality, same(prefs.getString("custom_personality", null), etPersonalityCustom?.text))
        setButtonCheck(btnSaveAiName, same(prefs.getString("ai_name", null), etAiNameCustom?.text?.toString()?.take(10)))
        setButtonCheck(btnSaveUserName, same(prefs.getString("user_name", null), etUserName?.text))
    }

    private fun watchForSaveChecks(et: EditText?) {
        et?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { refreshSaveChecks() }
        })
    }

    private fun setupNameConfig() {
        // ✔ Si cambias lo escrito, el visto bueno se quita hasta que vuelvas a guardar
        watchForSaveChecks(etAiNameCustom); watchForSaveChecks(etUserName); watchForSaveChecks(etPersonalityCustom)
        etAiNameCustom?.post { refreshSaveChecks() }
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
            chatAdapter?.notifyDataSetChanged()
            cumulusToast(this, "Nombre de la IA guardado", Toast.LENGTH_SHORT).show()
            refreshSaveChecks()
        }

        // 🗑 Eliminar nombre de la IA → vuelve a "Cumulus IA"
        findViewById<View>(R.id.btn_delete_ai_name)?.setOnClickListener {
            resetAiNameToDefault(showToast = true)
            etAiNameCustom?.text?.clear()
            refreshSaveChecks()
        }

        // 🗑 Eliminar nombre del usuario
        findViewById<View>(R.id.btn_delete_user_name)?.setOnClickListener {
            etUserName?.text?.clear()
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("user_name").apply()
            chatAdapter?.notifyDataSetChanged()
            cumulusToast(this, "Nombre de usuario eliminado", Toast.LENGTH_SHORT).show()
            refreshSaveChecks()
        }

        btnSaveUserName?.setOnClickListener {
            val name = etUserName?.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                cumulusToast(this, "Escribe un nombre de usuario", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("user_name", name).apply()
            chatAdapter?.notifyDataSetChanged()
            cumulusToast(this, "Nombre de usuario guardado", Toast.LENGTH_SHORT).show()
            refreshSaveChecks()
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
                    else cumulusToast(this, "No se pudo leer la foto", Toast.LENGTH_SHORT).show()
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
                        cumulusToast(this, "Algunos archivos no se pudieron abrir", Toast.LENGTH_SHORT).show()
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
            cumulusToast(this, "Imagen personalizada aplicada al chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun useDefaultBubbleImage() {
        bubbleImageUri = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("bubble_image_uri")
            .apply()
        updateBubbleImageEverywhere()
        cumulusToast(this, "Imagen predeterminada restaurada en el chat", Toast.LENGTH_SHORT).show()
    }

    private fun updateBubbleImageButton() {
        btnRestoreChatImage?.isEnabled = bubbleImageUri != null
    }

    private fun updateBubbleImageEverywhere() {
        updateBubbleImageButton()
        updateChatAvatars()
    }

    // 🔵 Lista de voces: cada voz encerrada en un recuadro con línea azul y esquinas curvas
    private fun voiceListAdapter(labels: Array<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val d = resources.displayMetrics.density
                v.background = android.graphics.drawable.InsetDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setColor(Color.TRANSPARENT)
                        setStroke((1.5f * d).toInt(), Color.parseColor("#3D5AFE"))
                    }, (12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                (v as? TextView)?.apply {
                    setTextColor(if (isLightMode()) Color.parseColor("#111111") else Color.WHITE)
                    setPadding((26 * d).toInt(), (14 * d).toInt(), (26 * d).toInt(), (14 * d).toInt())
                }
                // 🔵 Círculo de selección: azul cuando está elegida, gris cuando no
                (v as? android.widget.CheckedTextView)?.checkMarkTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.parseColor("#3D5AFE"), Color.parseColor("#9A9A9A"))
                )
                return v
            }
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
                styleDarkRoundDialog(this)   // negro/blanco, curvo y borde gris
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
            cumulusToast(this, "No hay voces disponibles para seleccionar", Toast.LENGTH_LONG).show()
            return
        }

        if (!elevenLabsError.isNullOrBlank()) {
            cumulusToast(this, elevenLabsError, Toast.LENGTH_LONG).show()
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

        // 🔵 Lista hecha a mano: cada voz en su recuadro azul, con "Eliminar" debajo
        val d = resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val light = isLightMode()
        val textColor = if (light) Color.parseColor("#111111") else Color.WHITE
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        lateinit var dialog: AlertDialog

        choices.forEachIndexed { i, choice ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setStroke((1.5f * d).toInt(), Color.parseColor("#3D5AFE"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5); bottomMargin = dp(5) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = labels[i]
                textSize = 17f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Círculo: azul relleno si es la voz elegida, gris vacío si no
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    if (i == selectedIndex) {
                        setColor(Color.parseColor("#3D5AFE")); setStroke(dp(2), Color.parseColor("#3D5AFE"))
                    } else setStroke(dp(2), Color.parseColor("#9A9A9A"))
                }
            })
            box.addView(row)
            // Elegir la voz tocando el recuadro
            box.setOnClickListener {
                saveSelectedTtsVoice(elevenLabsApiKey, choice)
                cumulusToast(this, "Voz activa: ${choice.displayName}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            // 🗑 Eliminar (no aplica a la voz del teléfono)
            if (choice.source != VoiceSource.SYSTEM) {
                box.addView(TextView(this).apply {
                    text = "🗑 Eliminar"
                    textSize = 14f
                    setTextColor(Color.parseColor("#E53935"))
                    setPadding(0, dp(8), dp(4), dp(2))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Eliminar voz")
                            .setMessage("¿Eliminar «${choice.displayName}»?" +
                                if (choice.source == VoiceSource.ELEVEN_LABS) "\nSe borrará también de tu cuenta de ElevenLabs." else "")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Eliminar") { _, _ ->
                                deleteSavedVoice(choice, elevenLabsApiKey) { ok ->
                                    if (ok) {
                                        list.removeView(box)
                                        cumulusToast(this@MainActivity, "🗑 Voz eliminada", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .show().also { styleDarkRoundDialog(it) }
                    }
                })
            }
            list.addView(box)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Voces guardadas")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()
        styleDarkRoundDialog(dialog)   // negro (oscuro) / blanco (claro), curvo y borde gris
    }

    // Borra una voz: las de ElevenLabs en tu cuenta (internet); las descargadas, del teléfono
    private fun deleteSavedVoice(choice: SelectableTtsVoice, apiKey: String, done: (Boolean) -> Unit) {
        val ttsPrefs = getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
        when (choice.source) {
            VoiceSource.DOWNLOADED -> {
                val ok = choice.localPath?.let { File(it).delete() } ?: false
                if (ttsPrefs.getString("local_voice_path", null) == choice.localPath) {
                    ttsPrefs.edit().remove("local_voice_path").putString("selected_provider", "ANDROID_DEFAULT").apply()
                }
                if (!ok) cumulusToast(this, "No se pudo borrar el archivo", Toast.LENGTH_SHORT).show()
                done(ok)
            }
            VoiceSource.ELEVEN_LABS -> thread {
                val ok = runCatching {
                    val c = (URL("https://api.elevenlabs.io/v1/voices/${choice.id}").openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        setRequestProperty("xi-api-key", apiKey)
                        connectTimeout = 20000; readTimeout = 20000
                    }
                    val code = c.responseCode
                    c.disconnect()
                    code in 200..299
                }.getOrDefault(false)
                runOnUiThread {
                    if (ok && ttsPrefs.getString("elevenlabs_voice_id", null) == choice.id) {
                        ttsPrefs.edit().remove("elevenlabs_voice_id").putString("selected_provider", "ANDROID_DEFAULT").apply()
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("eleven_active_voice_id").apply()
                    }
                    if (!ok) cumulusToast(this, "❌ No se pudo eliminar en ElevenLabs (revisa internet o la API key)", Toast.LENGTH_LONG).show()
                    done(ok)
                }
            }
            else -> done(false)
        }
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
    // ▶️/⏹️ Botón Probar de ElevenLabs: gris «Probar» ↔ rojo «Parar»
    private var elevenTestPlaying = false
    private var elevenTestTint: android.content.res.ColorStateList? = null
    private var elevenTestLabel: CharSequence? = null

    private fun setElevenTestButton(playing: Boolean) {
        val b = btnElevenTest ?: return
        if (elevenTestLabel == null) { elevenTestLabel = b.text; elevenTestTint = b.backgroundTintList }
        elevenTestPlaying = playing
        if (playing) {
            b.text = "Parar"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        } else {
            b.text = elevenTestLabel
            b.backgroundTintList = elevenTestTint
        }
    }

    private fun stopElevenTest() {
        runCatching { mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() } }
        mediaPlayer = null
        setElevenTestButton(false)
    }

    /**
     * 🔕 Avisos emergentes desactivados: ya no aparece ninguno (quedan solo en el registro interno).
     * Todas las acciones siguen funcionando igual; solo se quita el aviso.
     */
    private class QuietToast(private val toast: Toast?) { fun show() { toast?.show() } }

    private fun cumulusToast(ctx: Context, text: CharSequence?, duration: Int): QuietToast {
        val t = text?.toString().orEmpty()
        // 🔕 Sin avisos emergentes: la app ya es estable (el usuario lo pidió). Las acciones siguen igual.
        Log.d(TAG, "Aviso (oculto): $t")
        return QuietToast(null)
    }

    private fun isImportantNotice(t: String): Boolean {
        val low = t.lowercase(Locale.ROOT)
        val keys = listOf("⚠", "❌", "error", "no se pudo", "no pude", "no puedo", "falta", "primero",
            "da permiso", "permiso", "no disponible", "no hay ", "sin conexión", "no tiene conexión", "revisa",
            "inténtalo", "intentalo", "no corresponde", "no responde", "falló", "fallo", "detén", "necesito",
            "escribe", "espera a", "introduce", "ya está en curso", "todavía no", "no encontr", "no existe")
        return keys.any { low.contains(it) }
    }

    /** Permite ajustar la barra deslizando el dedo: bloquea el panel lateral y el scroll mientras arrastras. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun enableSlideDrag(sb: SeekBar) {
        sb.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    drawerLayout?.let { d ->
                        for (g in intArrayOf(androidx.core.view.GravityCompat.START, androidx.core.view.GravityCompat.END))
                            if (d.isDrawerOpen(g)) d.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_OPEN, g)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    drawerLayout?.let { d ->
                        for (g in intArrayOf(androidx.core.view.GravityCompat.START, androidx.core.view.GravityCompat.END))
                            if (d.getDrawerLockMode(g) == androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_OPEN)
                                d.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED, g)
                    }
                }
            }
            false   // la barra sigue recibiendo el toque y se mueve con el dedo
        }
    }

    private fun setupDrawerControls() {
        val drawer = drawerLayout ?: return

        // Los paneles laterales absorben los toques: así tocar un espacio vacío
        // del panel ya no activa el micrófono ni el menú de modos del chat de atrás.
        for (i in 0 until drawer.childCount) {
            val child = drawer.getChildAt(i)
            val lp = child.layoutParams as? androidx.drawerlayout.widget.DrawerLayout.LayoutParams ?: continue
            if (lp.gravity != android.view.Gravity.NO_GRAVITY) {
                child.isClickable = true
                child.isFocusable = false
                child.setOnTouchListener { _, _ -> false }
                child.setOnClickListener { }
            }
        }

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
        // Lista NPU/CPU/GPU: fondo blanco en modo claro, negro en modo oscuro (esquinas curvas y borde gris)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, backends) {
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(if (isLightMode()) Color.parseColor("#111111") else Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return v
            }
        }
        spinnerBackend?.adapter = adapter
        fun refreshSpinnerPopup() {
            val d = resources.displayMetrics.density
            val light = isLightMode()
            spinnerBackend?.setPopupBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(if (light) Color.WHITE else Color.parseColor("#141414"))
                setStroke((1.2f * d).toInt(), Color.parseColor(if (light) "#C7C7CC" else "#8E8E93"))
            })
        }
        refreshSpinnerPopup()
        // Antes de abrir la lista se revisa el modo (por si cambiaste de claro a oscuro)
        spinnerBackend?.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) refreshSpinnerPopup()
            false
        }

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
                        cumulusToast(
                            this@MainActivity, 
                            "⚡ NPU activada — GGUF Q4_0 con GenieX de Qualcomm, o .litertlm hechos para tu chip.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (selected == "🎮 GPU (Adreno)") {
                        cumulusToast(this@MainActivity, "🎮 GPU activada — GGUF con GenieX (Qualcomm) o modelos .litertlm (LiteRT).", Toast.LENGTH_LONG).show()
                            
                        
                    } else if (selected == "💻 CPU (NEON)") {
                        cumulusToast(this@MainActivity, "💻 CPU activada — modo compatible universal", Toast.LENGTH_SHORT).show()
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
                    cumulusToast(this, "Backend seleccionado: ${backends[which]}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show().also { styleDarkRoundDialog(it) }
        }

        btnAddGgufUrl?.setOnClickListener {
            val urlString = etGgufUrl?.text?.toString()?.trim() ?: ""
            if (urlString.isEmpty()) {
                cumulusToast(this, "Introduce una URL GGUF o LiteRT-LM válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val slot = createDownloadSlot(DownloadType.GGUF)
            if (slot == null) {
                cumulusToast(this, "No se pudo crear la tarjeta de descarga", Toast.LENGTH_SHORT).show()
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
                    // Se recuerda de qué carpeta de Hugging Face vino (para emparejar modelo y mmproj)
                    Regex("huggingface\\.co/([^/]+/[^/]+)").find(urlString)?.groupValues?.get(1)?.let { repo ->
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString("repo_of_" + downloadedFile.name, repo.lowercase(Locale.ROOT)).apply()
                    }
                    onGgufDownloadFinished(downloadedFile)
                }
            )
            etGgufUrl?.text?.clear()
        }

        btnAddTtsUrl?.setOnClickListener {
            val urlString = etTtsUrl?.text?.toString()?.trim() ?: ""
            if (urlString.isEmpty()) {
                cumulusToast(this, "Introduce una URL TTS válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val slot = createDownloadSlot(DownloadType.TTS)
            if (slot == null) {
                cumulusToast(this, "No se pudo crear la tarjeta de descarga", Toast.LENGTH_SHORT).show()
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
                    cumulusToast(this, "Voces TTS descargadas: ${downloadedFile.name}", Toast.LENGTH_LONG).show()
                }
            )
            etTtsUrl?.text?.clear()
        }

        btnLoadModel?.setOnClickListener {
            if (isModelLoadedInMemory || (!isAutoLoadEnabled() && isManualModelPinned())) {
                // Manual: tocar "MODELO ACTIVO" lo desactiva (también si aún se estaba recargando)
                setManualModelPinned(false)
                releaseModelFromMemory()
                updateLoadModelButton()
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
        // 🔘 Pausar / Reanudar / Eliminar con esquinas más curvas
        listOf(ui.btnPause, ui.btnResume, ui.btnDelete).forEach { b ->
            val r = 14 * resources.displayMetrics.density
            if (b is MaterialButton) b.cornerRadius = r.toInt()
            else b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = r
                setColor(Color.WHITE)   // el color real lo pone su backgroundTint (azul, rojo o negro)
            }
        }
        if (isLightMode()) recolorTree(itemView, true)   // ⬜ botón Pausar blanco en modo claro
        // 🔵 Barra de progreso de la descarga en azul (antes verde)
        ui.progress.progressTintList = ColorStateList.valueOf(Color.parseColor("#3D5AFE"))
        ui.progress.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#403D5AFE"))  // fondo azul suave
        if (isLightMode()) recolorTree(itemView, true)
        return DownloadSlot(type, ui)
    }

    // 🔢 Numera las descargas: 1, 2, 3... (si borras una, las demás se vuelven a numerar)
    private fun renumberDownloads(type: DownloadType) {
        slotsFor(type).forEachIndexed { i, sl ->
            val name = sl.file?.name ?: return@forEachIndexed
            sl.ui.title.text = "${i + 1}. ${sl.title}: $name"
        }
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
        renumberDownloads(slot.type)
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
            cumulusToast(this, "Esta descarga ya está en curso", Toast.LENGTH_SHORT).show()
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
        renumberDownloads(slot.type)
        ui.status.text = if (file.exists() && file.length() > 0) {
            "Descarga guardada — preparando reanudación..."
        } else {
            "Conectando con el servidor..."
        }
        ui.progress.progress = 0
        ui.btnPause.isEnabled = false
        ui.btnResume.isEnabled = false
        ui.btnDelete.isEnabled = true

        // 📥 Si esta descarga siguió trabajando con la app cerrada, se reconecta a ella (no se empieza otra)
        val alreadyRunning = ActiveDownloads.map[slot.id]?.takeIf {
            it.state == ResumableDownloader.State.DOWNLOADING || it.state == ResumableDownloader.State.PAUSED
        }
        val downloader = alreadyRunning ?: ResumableDownloader()
        ActiveDownloads.map[slot.id] = downloader
        if (initialState != ResumableDownloader.State.FINISHED || !file.exists()) ActiveDownloads.paths.add(file.absolutePath)
        slot.downloader = downloader
        persistDownloadRecord(slot, state = alreadyRunning?.state ?: initialState)
        // Permiso para mostrar el progreso en la barra de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 4713) }
        }

        downloader.listener = object : ResumableDownloader.Listener {
            private var lastUi = 0L
            private var lastPct = -2
            override fun onProgress(downloaded: Long, total: Long, percent: Int) {
                // ⚡ Antes se actualizaba cada 8 KB (cientos de veces por segundo) y la app y la barra
                //    de notificaciones se pegaban. Ahora máximo ~4 veces por segundo.
                val now = System.currentTimeMillis()
                if (percent == lastPct && now - lastUi < 250) return
                if (now - lastUi < 250 && percent != 100) return
                lastUi = now; lastPct = percent
                // 🔔 Progreso en la barra de notificaciones (sigue aunque salgas o cierres la app)
                DownloadService.onProgress(applicationContext, slot.id, file.name, percent)
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
                when (state) {
                    ResumableDownloader.State.DOWNLOADING -> DownloadService.onProgress(applicationContext, slot.id, file.name, -1)
                    ResumableDownloader.State.FINISHED -> {
                        DownloadService.onStopped(applicationContext, slot.id, file.name)
                        ActiveDownloads.map.remove(slot.id)
                        ActiveDownloads.paths.remove(file.absolutePath)
                    }
                    ResumableDownloader.State.PAUSED -> DownloadService.onStopped(applicationContext, slot.id)
                    else -> {
                        DownloadService.onStopped(applicationContext, slot.id)
                        ActiveDownloads.map.remove(slot.id)
                    }
                }
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
            ActiveDownloads.map.remove(slot.id)
            ActiveDownloads.paths.remove(file.absolutePath)
            DownloadService.onStopped(applicationContext, slot.id)
            deleteDownloadedModelIfMatches(file)
            slot.downloader = null
            removeDownloadSlot(slot)
            cumulusToast(this, "Descarga eliminada", Toast.LENGTH_SHORT).show()
        }

        if (alreadyRunning != null) {
            // Ya estaba bajando (o en pausa) en segundo plano: solo se actualiza la pantalla
            downloader.listener?.onStateChanged(alreadyRunning.state)
            return
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
                var success = loadModelWithConfiguration(modelFile, backend)
                isModelLoading = false
                // Si lo desactivaste mientras se recargaba, se libera y queda apagado
                if (success && !isAutoLoadEnabled() && !isManualModelPinned()) {
                    runCatching { nativeReleaseModel() }
                    runCatching { runBlocking { GenieXEngineManager.release() } }
                    runCatching { runBlocking { LiteRtLmEngineManager.release("main_activity") } }
                    success = false
                }
                runOnUiThread {
                    isModelLoadedInMemory = success
                    updateLoadModelButton()
                    if (success) {
                        tvModelLoadedName?.text = "Modelo: ${modelFile.name} [$backend - ${if (isAutoLoadEnabled()) "Auto-activado" else "Manual"}]" + mediaCapsLabel()
                        setModelNameText("Nombre del modelo\n${modelFile.name} · Automático")
                        cumulusToast(this, "✅ ${modelFile.name} activado automáticamente", Toast.LENGTH_LONG).show()
                        Log.d(TAG, "✅ Modelo cargado automáticamente en $backend")
                    } else {
                        tvModelLoadedName?.text = if (!isAutoLoadEnabled() && !isManualModelPinned())
                            "Modelo: Descargado (en espera)" else "Modelo: ${modelFile.name} (error de activación)"
                        cumulusToast(this, "❌ Error al cargar modelo automáticamente", Toast.LENGTH_LONG).show()
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
                runCatching { runBlocking { GenieXEngineManager.release() } }
                runOnUiThread {
                    isModelLoadedInMemory = false
                    modelLoadedInBackground = false
                    updateLoadModelButton()
                    tvModelLoadedName?.text = "Modelo: Descargado (en espera)"
                    cumulusToast(this, "Motor de IA desactivado", Toast.LENGTH_SHORT).show()
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

        if (nativeIsModelLoaded() || GenieXEngineManager.isLoaded(modelFile.absolutePath)) {
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
        // 📌 Manual: si lo dejaste ACTIVO, sigue rojo «MODELO ACTIVO» aunque cierres y abras la app
        //    (mientras el modelo vuelve a cargarse en memoria). Solo tú lo desactivas tocándolo.
        val active = isModelLoadedInMemory || (!isAutoLoadEnabled() && isManualModelPinned())
        btnLoadModel?.text = LanguageManager.text(
            this,
            if (active) "MODELO ACTIVO" else "CARGAR MODELO"
        )
        // En manual se puede tocar "MODELO ACTIVO" para desactivarlo
        btnLoadModel?.isEnabled = !active || !isAutoLoadEnabled()
        btnLoadModel?.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (active) "#C62828" else "#3D5AFE")
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
            button.setOnClickListener {
                if (languageCode != LanguageManager.getSelectedLanguage(this)) {
                    LanguageManager.setLocale(this, languageCode)
                    recreate()
                }
            }
        }
        styleLanguageButtons(isLightMode())
    }

    // 🌐 Botones de idioma: esquinas curvas; negros en modo oscuro, blancos con borde gris en modo claro
    private val languageButtonIds = listOf(
        R.id.btnLanguageEs to "es", R.id.btnLanguageEn to "en", R.id.btnLanguageRu to "ru",
        R.id.btnLanguageZh to "zh", R.id.btnLanguageKo to "ko", R.id.btnLanguageJa to "ja", R.id.btnLanguagePt to "pt"
    )

    private fun styleLanguageButtons(light: Boolean) {
        val d = resources.displayMetrics.density
        val selected = LanguageManager.getSelectedLanguage(this)
        languageButtonIds.forEach { (id, code) ->
            val b = findViewById<Button>(id) ?: return@forEach
            (b as? MaterialButton)?.let { mb ->
                mb.cornerRadius = (14 * d).toInt()
                mb.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (light) "#FFFFFF" else "#2C2C2E"))
                mb.strokeWidth = if (light) (1.5f * d).toInt() else 0
                mb.strokeColor = ColorStateList.valueOf(Color.parseColor("#C7C7CC"))
            } ?: run {
                b.backgroundTintList = null
                b.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(Color.parseColor(if (light) "#FFFFFF" else "#2C2C2E"))
                    if (light) setStroke((1.5f * d).toInt(), Color.parseColor("#C7C7CC"))
                }
            }
            b.setTextColor(Color.parseColor(when {
                code == selected -> "#3D5AFE"            // idioma elegido en azul
                light -> "#1C1C1E"
                else -> "#FFFFFF"
            }))
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

    // Todos los .gguf descargados: primero los mmproj detectados, luego el resto
    private fun getMmprojCandidateFiles(): List<File> {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        val all = storageDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty().sortedByDescending { it.lastModified() }
        val (mm, rest) = all.partition { isMmprojFile(it) }
        return mm + rest
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
                getActiveModelFile()?.let { m ->
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString("mmproj_for_" + m.name, downloadedFile.absolutePath).apply()
                }
                thread {
                    genieXRefreshVision()?.let { m -> runOnUiThread { cumulusToast(this, m, Toast.LENGTH_LONG).show() }; return@thread }
                    nativeSetMmproj(downloadedFile.absolutePath)
                    val loaded = nativeIsModelLoaded()
                    val visionReady = loaded && nativeHasVision()
                    runOnUiThread {
                        val msg = when {
                            visionReady -> "✅ mmproj ${downloadedFile.name} activado automáticamente${mediaCapsLabel()}"
                            !loaded -> "mmproj ${downloadedFile.name} guardado. Se activará al cargar el modelo."
                            else -> "⚠️ mmproj descargado, pero no corresponde al modelo activo"
                        }
                        cumulusToast(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * ⚡👁️ Si el modelo está cargado en NPU/GPU y cambió el mmproj, lo recarga solo para que la
     * visión quede activa (o desactivada). Devuelve el mensaje a mostrar, o null si no aplica (CPU).
     */
    private fun genieXRefreshVision(): String? {
        val model = getActiveModelFile() ?: return null
        if (!GenieXEngineManager.isLoaded(model.absolutePath)) return null
        val want = getSelectedMmprojFile()?.absolutePath
        if (want == GenieXEngineManager.loadedMmprojPath()) return null
        runOnUiThread { cumulusToast(this, "⏳ Recargando el modelo en NPU/GPU con el nuevo mmproj…", Toast.LENGTH_SHORT).show() }
        val ok = runCatching { loadModelWithConfiguration(model, getSavedBackend()) }.getOrDefault(false)
        isModelLoadedInMemory = ok
        runOnUiThread { updateLoadModelButton() }
        return when {
            !ok -> "⚠️ No se pudo recargar el modelo. Cárgalo de nuevo desde ☰."
            want == null -> "Visión desactivada (${GenieXEngineManager.engineInfo()})"
            GenieXEngineManager.hasVision() -> "✅ Visión activa en ${GenieXEngineManager.engineInfo()}"
            else -> "⚠️ La NPU/GPU no aceptó ese mmproj; el modelo quedó solo con texto. Prueba en CPU."
        }
    }

    private fun getSelectedMmprojFile(): File? {
        val path = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("selected_mmproj_path", null) ?: return null
        if (ActiveDownloads.paths.contains(path)) return null   // ⏳ aún descargando: incompleto
        return File(path).takeIf { it.exists() }
    }

    private fun updateMmprojButton() {
        val selected = getSelectedMmprojFile()
        btnMmproj?.text = if (selected != null) {
            "mmproj\n${selected.name}"
        } else {
            "mmproj\nNinguno"
        }
        btnMmproj?.let { btn ->
            val files = runCatching { getDownloadedMmprojFiles() }.getOrDefault(emptyList())
            val idx = if (selected != null) files.indexOfFirst { it.absolutePath == selected.absolutePath }.coerceAtLeast(0) else -1
            applyBlueStatusIcons(btn, if (selected != null) idx + 1 else null, selected != null)
        }
    }

    private fun showSelectMmprojDialog() {
        // Solo archivos mmproj (los modelos de chat se eligen arriba, en "Nombre del modelo")
        val files = getDownloadedMmprojFiles()
        if (files.isEmpty()) {
            cumulusToast(this, "No hay archivos mmproj descargados. Descarga el .gguf que tenga \"mmproj\" en el nombre.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = (listOf("Ninguno") + files.mapIndexed { i, f ->
            "${i + 1}. 🖼️ ${f.name} (${f.length() / (1024 * 1024)} MB)"
        }).toTypedArray()
        val current = getSelectedMmprojFile()
        val currentIndex = files.indexOfFirst { it.absolutePath == current?.absolutePath } + 1

        AlertDialog.Builder(this)
            .setTitle("Seleccionar mmproj")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                setSelectedMmproj(if (which == 0) null else files[which - 1])
                // Se recuerda esta pareja para el modelo activo
                getActiveModelFile()?.let { m ->
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString("mmproj_for_" + m.name, if (which == 0) "none" else files[which - 1].absolutePath).apply()
                }
                dialog.dismiss()
                val mmprojPath = if (which == 0) "" else files[which - 1].absolutePath
                thread {
                    genieXRefreshVision()?.let { m -> runOnUiThread { cumulusToast(this, m, Toast.LENGTH_LONG).show() }; return@thread }
                    nativeSetMmproj(mmprojPath)
                    val visionReady = nativeIsModelLoaded() && nativeHasVision()
                    runOnUiThread {
                        val msg = when {
                            mmprojPath.isEmpty() -> "Visión desactivada"
                            visionReady -> "✅ Multimodal activado: ${labels[which]}${mediaCapsLabel()}"
                            !nativeIsModelLoaded() -> "mmproj guardado. Se activará al cargar el modelo."
                            else -> "⚠️ Ese mmproj no corresponde al modelo activo"
                        }
                        cumulusToast(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show().also { styleDarkRoundDialog(it) }
    }

    private fun getSelectedModelPath(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("selected_custom_model_path", null)
    }

    private fun setSelectedModelFile(file: File) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("selected_custom_model_path", file.absolutePath).apply()
        autoPairMmproj(file)
    }

    // 🤝 Modelo + mmproj trabajan juntos: al elegir un modelo se elige solo su mmproj
    private fun modelFamily(name: String): String = name.lowercase(Locale.ROOT)
        .removeSuffix(".gguf").removeSuffix(".litertlm")
        .replace(Regex("[-_.](i?q\\d.*|f16|f32|bf16|fp16)$"), "")
        .replace(Regex("[-_](it|instruct|chat)$"), "")
        .replace("mmproj", "").replace(Regex("^[-_.]+|[-_.]+$"), "")

    private fun autoPairMmproj(model: File) {
        val mmprojs = runCatching { getDownloadedMmprojFiles() }.getOrDefault(emptyList())
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 1) La pareja que elegiste antes para este modelo
        prefs.getString("mmproj_for_" + model.name, null)?.let { saved ->
            if (saved == "none") { setSelectedMmproj(null); updateMmprojButton(); return }
            mmprojs.firstOrNull { it.absolutePath == saved }?.let { setSelectedMmproj(it); updateMmprojButton(); return }
        }
        // 2) Un mmproj con el mismo nombre de familia (ej. gemma-3-4b)
        val fam = modelFamily(model.name)
        val byName = mmprojs.firstOrNull { val mf = modelFamily(it.name); mf.isNotBlank() && (mf.contains(fam) || fam.contains(mf)) }
        // 3) Un mmproj descargado de la misma carpeta de Hugging Face (se guarda al descargar)
        val sameRepo = prefs.getString("repo_of_" + model.name, null)?.let { repo ->
            mmprojs.firstOrNull { prefs.getString("repo_of_" + it.name, null) == repo }
        }
        val pick = byName ?: sameRepo ?: return   // sin pareja clara: se deja como estaba
        if (getSelectedMmprojFile()?.absolutePath == pick.absolutePath) return
        setSelectedMmproj(pick)
        updateMmprojButton()
        cumulusToast(this, "🤝 mmproj emparejado: ${pick.name}", Toast.LENGTH_SHORT).show()
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
            cumulusToast(this, "No hay modelos GGUF o LiteRT-LM descargados.", Toast.LENGTH_SHORT).show()
            return
        }

        if (models.size == 1) {
            val onlyModel = models.first()
            setSelectedModelFile(onlyModel)
            checkStoredModelStatus()
            autoLoadModel()
            cumulusToast(
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
            .show().also { styleDarkRoundDialog(it) }
    }

    private fun loadModelIntoMemoryManual() {
        val modelFile = getActiveModelFile()

        if (modelFile == null || !modelFile.exists()) {
            cumulusToast(this, "No existe ningún modelo GGUF o LiteRT-LM válido.", Toast.LENGTH_LONG).show()
            return
        }

        val selectedBackend = spinnerBackend?.selectedItem?.toString() ?: getSavedBackend()
        saveBackend(selectedBackend)

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🚀 Carga manual de modelo")
        Log.d(TAG, "Modelo: ${modelFile.name}")
        Log.d(TAG, "Backend: $selectedBackend")
        Log.d(TAG, "═══════════════════════════════════════════════════")

        cumulusToast(this, "Cargando modelo ${modelFile.name} usando $selectedBackend...", Toast.LENGTH_SHORT).show()

        isModelLoading = true
        thread {
            val success = loadModelWithConfiguration(modelFile, selectedBackend)
            isModelLoading = false
            runOnUiThread {
                isModelLoadedInMemory = success
                updateLoadModelButton()
                if (success) {
                    if (!isAutoLoadEnabled()) setManualModelPinned(true)
                    cumulusToast(this, "✅ ¡Modelo inicializado en memoria correctamente con $selectedBackend!", Toast.LENGTH_LONG).show()
                    tvModelLoadedName?.text = "Modelo: ${modelFile.name} [${realEngineLabel(modelFile, selectedBackend)} - Cargado]" + mediaCapsLabel()
                    Log.d(TAG, "✅ Modelo cargado exitosamente en $selectedBackend")
                } else {
                    if (modelFile.extension.equals("litertlm", ignoreCase = true)) {
                        // Muestra el error real de LiteRT-LM para saber qué falla
                        val detail = LiteRtLmEngineManager.lastLoadErrors().ifBlank { "Sin detalles" }
                        AlertDialog.Builder(this)
                            .setTitle("❌ LiteRT-LM no pudo cargar el modelo")
                            .setMessage(detail)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Copiar") { _, _ ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", detail))
                                cumulusToast(this, "Error copiado", Toast.LENGTH_SHORT).show()
                            }
                            .show().also { styleDarkRoundDialog(it) }
                    } else {
                        cumulusToast(this, "❌ Fallo al inicializar el modelo en la capa nativa C++", Toast.LENGTH_LONG).show()
                    }
                    Log.e(TAG, "❌ Error al cargar modelo en $selectedBackend")
                }
            }
        }
    }

    private fun setupAdvancedConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 👆 Deslizar con el dedo: el panel lateral y el desplazamiento vertical ya no "roban" el gesto
        listOf(seekContext, seekTemperature, seekTopK, seekTopP).forEach { it?.let { sb -> enableSlideDrag(sb) } }
        // Se muestran los valores guardados (antes volvían a los de fábrica al abrir la app)
        // Mínimo de la barra: 2048 (debajo de eso el modelo no rinde bien). Arriba, lo decides tú.
        seekContext?.min = 2048
        seekContext?.progress = prefs.getInt("ai_context", 2048).coerceAtLeast(2048)
        tvContextValue?.text = "${seekContext?.progress ?: 2048} tok"
        val savedTemp = prefs.getFloat("ai_temperature", -1f)
        if (savedTemp >= 0f) seekTemperature?.progress = (savedTemp * 100).toInt()
        tvTemperatureValue?.text = String.format(Locale.US, "%.1f", (seekTemperature?.progress ?: 50) / 100.0)
        if (prefs.contains("ai_top_k")) seekTopK?.progress = prefs.getInt("ai_top_k", 40)
        if (prefs.contains("ai_top_p")) seekTopP?.progress = prefs.getInt("ai_top_p", 95)

        seekContext?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // El valor exacto que elijas con el dedo, pero nunca menos de 2048
                val v = progress.coerceAtLeast(2048)
                if (v != progress) seekBar?.progress = v
                tvContextValue?.text = "$v tok"
                prefs.edit().putInt("ai_context", v).apply()
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
                cumulusToast(this, "Escribe una personalidad personalizada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("custom_personality", customPersonality).apply()
            cumulusToast(this, "Personalidad guardada: la IA la usará desde el próximo mensaje", Toast.LENGTH_SHORT).show()
            refreshSaveChecks()
        }
        
        btnDeleteCustomPersonality?.setOnClickListener {
            etPersonalityCustom?.text?.clear()
            prefs.edit().remove("custom_personality").apply()
            cumulusToast(this, "Personalidad borrada", Toast.LENGTH_SHORT).show()
            refreshSaveChecks()
        }
    }

    // 🔑 Botón de la clave: azul «GUARDAR API KEY» ↔ rojo «API KEY GUARDADA»
    private var keyBtnTint: android.content.res.ColorStateList? = null
    private var keyBtnLabel: CharSequence? = null
    private fun setKeyButtonSaved(saved: Boolean) {
        val b = btnSaveElevenKey ?: return
        if (keyBtnLabel == null) { keyBtnLabel = b.text; keyBtnTint = b.backgroundTintList }
        if (saved) {
            b.text = "API KEY GUARDADA"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C62828"))
        } else {
            b.text = keyBtnLabel
            b.backgroundTintList = keyBtnTint
        }
    }

    // ✔ Visto bueno negro en USAR / GUARDAR de ElevenLabs
    private val btnBaseLabels = HashMap<Int, CharSequence>()
    private fun setButtonCheck(b: MaterialButton?, checked: Boolean) {
        b ?: return
        val base = btnBaseLabels.getOrPut(b.id) { b.text }
        if (!checked) { b.text = base; return }
        val sp = android.text.SpannableString("✔  $base")
        sp.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.text = sp
    }

    private fun setupElevenLabsActions() {
        // Al abrir la app: si ya hay una clave guardada, el botón aparece rojo
        val savedKeyNow = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("eleven_api_key", "").orEmpty()
        setKeyButtonSaved(savedKeyNow.isNotBlank() && savedKeyNow == etElevenApiKey?.text?.toString()?.trim())
        // Si cambias la clave escrita, vuelve a azul para que la guardes de nuevo
        etElevenApiKey?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("eleven_api_key", "").orEmpty()
                setKeyButtonSaved(saved.isNotBlank() && saved == e?.toString()?.trim())
            }
        })

        btnSaveElevenKey?.setOnClickListener {
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                cumulusToast(this, "Introduce tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("eleven_api_key", apiKey).apply()
            getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("elevenlabs_api_key", apiKey)
                .apply()
            cumulusToast(this, "¡API Key de ElevenLabs guardada con éxito!", Toast.LENGTH_SHORT).show()
            setKeyButtonSaved(true)
        }

        btnElevenRecord?.setOnClickListener {
            // Nueva grabación: se quitan los ✔ hasta que la uses o la guardes
            setButtonCheck(btnElevenUse, false); setButtonCheck(btnElevenSave, false)
            if (!isRecordingVoice) {
                startAudioRecording()
            } else {
                stopAudioRecording()
            }
        }

        btnElevenTest?.setOnClickListener {
            // ⏹️ Si ya está sonando, el mismo botón la detiene
            if (elevenTestPlaying) { stopElevenTest(); return@setOnClickListener }
            if (recordedAudioFile == null || !recordedAudioFile!!.exists()) {
                cumulusToast(this, "Primero graba una muestra de voz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(recordedAudioFile!!.absolutePath)
                    prepare()
                    setOnCompletionListener { runOnUiThread { stopElevenTest() } }
                    start()
                }
                setElevenTestButton(true)
                cumulusToast(this, "Reproduciendo muestra de voz grabada...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                setElevenTestButton(false)
                cumulusToast(this, "Error al reproducir audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnElevenUse?.setOnClickListener {
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                cumulusToast(this, "Falta tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (recordedAudioFile == null || !recordedAudioFile!!.exists()) {
                cumulusToast(this, "Falta la muestra de audio grabada", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val progressDialog = ProgressDialog(this).apply {
                setTitle("ElevenLabs Cloud")
                setMessage("Conectando con servidores y clonando voz...")
                setCancelable(false)
                show()
                styleDarkRoundDialog(this)   // negro/blanco, curvo y borde gris
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
                        cumulusToast(this, "¡Voz clonada y vinculada correctamente!", Toast.LENGTH_LONG).show()
                        setButtonCheck(btnElevenUse, true)   // ✔ se está usando esta voz
                    } else {
                        cumulusToast(this, "❌ " + (lastElevenError ?: "Error de conexión con ElevenLabs"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnElevenDelete?.setOnClickListener {
            // 🗑 Al eliminar, se quitan los ✔ de USAR y GUARDAR
            setButtonCheck(btnElevenUse, false); setButtonCheck(btnElevenSave, false)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove("eleven_active_voice_id").apply()
            getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)
                .edit()
                .remove("elevenlabs_voice_id")
                .apply()
            recordedAudioFile?.delete()
            recordedAudioFile = null
            cumulusToast(this, "Configuración de ElevenLabs restablecida", Toast.LENGTH_SHORT).show()
        }

        btnElevenSave?.setOnClickListener {
            if (isRecordingVoice) {
                cumulusToast(this, "Primero detén la grabación", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val apiKey = etElevenApiKey?.text?.toString()?.trim() ?: ""
            if (apiKey.isEmpty()) {
                cumulusToast(this, "Falta tu API Key de ElevenLabs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val source = recordedAudioFile
            if (source == null || !source.exists()) {
                cumulusToast(this, "Primero graba una muestra de voz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val nameInput = EditText(this).apply {
                hint = "Nombre de la voz (ej: Mi voz)"
                setSingleLine()
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8E8E93"))
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#8E8E93"))
            }
            // 🎵 Quitar música, ruido y efectos (ElevenLabs aísla solo la voz antes de clonarla)
            val cleanCheck = android.widget.CheckBox(this).apply {
                text = "🎵 Quitar música, ruido y efectos de fondo"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("voice_remove_noise", true)
                buttonTintList = ColorStateList.valueOf(Color.parseColor("#3D5AFE"))
            }
            val cleanHint = TextView(this).apply {
                text = "Actívalo si la grabación tiene música o ruido (ej. audio de un video). Si grabaste en silencio, desactívalo: así suena mejor."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
            }
            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (22 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, 0)
                addView(nameInput)
                addView(cleanCheck)
                addView(cleanHint)
            }

            AlertDialog.Builder(this)
                .setTitle("Guardar voz")
                .setView(nameBox)
                .setPositiveButton("Guardar") { _, _ ->
                    val removeNoise = cleanCheck.isChecked
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("voice_remove_noise", removeNoise).apply()
                    val voiceName = nameInput.text.toString().trim().ifEmpty {
                        "Mi voz " + java.text.SimpleDateFormat("dd-MM HH:mm", Locale.US).format(java.util.Date())
                    }
                    val progressDialog = ProgressDialog(this).apply {
                        setTitle("ElevenLabs Cloud")
                        setMessage("Guardando la voz \"$voiceName\"...")
                        setCancelable(false)
                        show()
                        styleDarkRoundDialog(this)   // negro/blanco, curvo y borde gris
                    }
                    thread {
                        val savedVoiceId = uploadVoiceToElevenLabs(apiKey, voiceName, source, removeNoise)
                        runOnUiThread {
                            progressDialog.dismiss()
                            if (savedVoiceId != null) {
                                setButtonCheck(btnElevenSave, true)   // ✔ esta voz quedó guardada
                                cumulusToast(
                                    this,
                                    "✅ Voz \"$voiceName\" guardada. Ya aparece en Voces guardadas.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                cumulusToast(
                                    this,
                                    "❌ " + (lastElevenError ?: "No se pudo guardar la voz."),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show().also { styleDarkRoundDialog(it) }
        }
    }

    // 🖤 Ventana con fondo negro, borde gris y esquinas redondas
    private fun styleDarkRoundDialog(dialog: AlertDialog) {
        val d = resources.displayMetrics.density
        val light = isLightMode()   // ⚪ modo claro: fondo blanco · ⚫ modo oscuro: fondo negro
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(if (light) Color.WHITE else Color.BLACK)
                setStroke((1.2f * d).toInt(), Color.parseColor(if (light) "#C7C7CC" else "#8E8E93"))
            }, (16 * d).toInt()))
        dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(if (light) Color.parseColor("#111111") else Color.WHITE)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.parseColor(if (light) "#333333" else "#DDDDDD"))
        if (light) dialog.window?.decorView?.let { recolorTree(it, true) }   // lo que escribes, en oscuro
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
                cumulusToast(this@MainActivity, "Grabando voz para clonación...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar grabadora: ${e.message}")
                cumulusToast(this@MainActivity, "No se pudo iniciar el micrófono", Toast.LENGTH_SHORT).show()
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
            cumulusToast(this, "¡Muestra de voz guardada con éxito!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener grabadora: ${e.message}")
        }
    }

    private fun uploadVoiceToElevenLabs(apiKey: String, voiceName: String, audioFile: File, removeNoise: Boolean = false): String? {
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

            if (removeNoise) {
                // ElevenLabs separa la voz de la música, ruidos y efectos antes de clonarla
                writer.append(twoHyphens).append(boundary).append(lineEnd)
                writer.append("Content-Disposition: form-data; name=\"remove_background_noise\"").append(lineEnd)
                writer.append(lineEnd).append("true").append(lineEnd)
            }

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

    // Dibuja texto/símbolo azul como icono lateral del botón "Nombre del modelo"
    private class BlueSymbolDrawable(private val symbol: String, private val sizePx: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#3D5AFE")
            textSize = sizePx
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(symbol, b.exactCenterX(), y, paint)
        }
        override fun getIntrinsicWidth() = (sizePx * 1.2f).toInt()
        override fun getIntrinsicHeight() = (sizePx * 1.2f).toInt()
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun applyBlueStatusIcons(btn: MaterialButton, number: Int?, ok: Boolean) {
        val sizePx = 26f * resources.displayMetrics.scaledDensity
        val left = BlueSymbolDrawable(number?.toString() ?: " ", sizePx)
        val right = BlueSymbolDrawable(if (ok) "✓" else "✕", sizePx)
        left.setBounds(0, 0, left.intrinsicWidth, left.intrinsicHeight)
        right.setBounds(0, 0, right.intrinsicWidth, right.intrinsicHeight)
        btn.setCompoundDrawablesRelative(left, null, right, null)
    }

    private fun setModelNameText(text: String) {
        val btn = btnModelName ?: return
        btn.text = text
        val models = runCatching { getDownloadedModelFiles() }.getOrDefault(emptyList())
        val active = getActiveModelFile()
        val hasModel = active != null && active.exists()
        val idx = if (hasModel) models.indexOfFirst { it.absolutePath == active!!.absolutePath }.coerceAtLeast(0) else -1
        applyBlueStatusIcons(btn, if (hasModel) idx + 1 else null, hasModel)
    }

    private fun checkStoredModelStatus() {
        val activeModel = getActiveModelFile()
        if (activeModel != null && activeModel.exists()) {
            tvModelLoadedName?.text = "Modelo local: ${activeModel.name} (${activeModel.length() / (1024 * 1024)} MB)"
            setModelNameText("Nombre del modelo\n${activeModel.name} · Automático")
            logLoadedModelName(activeModel.name)
        } else {
            tvModelLoadedName?.text = "Modelo: Ninguno descargado"
            setModelNameText("Nombre del modelo\nAutomático · esperando GGUF")
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
                    cameraLiveActive -> closeCameraLive()
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
        AiActivity.onBubbleChange = null
        AiActivity.onOpenPreview = null
        GenieXEngineManager.onPartial = null
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
                runCatching { runBlocking { GenieXEngineManager.release() } }
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
