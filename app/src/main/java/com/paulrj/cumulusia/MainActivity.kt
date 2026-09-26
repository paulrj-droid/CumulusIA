package com.paulrj.cumulusia

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.paulrj.cumulusia.adapter.ChatAdapter
import com.paulrj.cumulusia.ai.CumulusEngine
import com.paulrj.cumulusia.engine.LiteRtLmEngineManager
import com.paulrj.cumulusia.manager.LanguageManager
import com.paulrj.cumulusia.model.ChatMessage
import com.paulrj.cumulusia.tts.TtsEngineManager
import com.paulrj.cumulusia.tts.TtsProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var engineManager: LiteRtLmEngineManager
    private lateinit var cumulusEngine: CumulusEngine
    private lateinit var ttsManager: TtsEngineManager

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etChatInput: EditText
    private lateinit var btnPlayPause: ImageButton
    private lateinit var settingsFlipper: android.widget.ViewFlipper

    private lateinit var prefs: SharedPreferences

    private val messages = mutableListOf<ChatMessage>()
    private var isAudioPlaybackEnabled = false

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 101
        private const val PREFS_NAME = "CumulusMainPrefs"
    }

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = results?.get(0)
                if (!spokenText.isNullOrEmpty()) {
                    etChatInput.setText(spokenText)
                    etChatInput.setSelection(spokenText.length)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        LanguageManager.loadLocale(this)
        engineManager = LiteRtLmEngineManager(this)
        cumulusEngine = CumulusEngine()
        ttsManager = TtsEngineManager(this)

        setupDrawer()
        setupChatList()
        setupComposer()
        setupLanguageButtons()
        setupElevenLabs()
        setupModelSettingsPanel()
        setupPersonality()
        setupNames()
    }

    // ==================== DRAWER ====================

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)

        findViewById<ImageButton>(R.id.btn_open_left_drawer).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        findViewById<ImageButton>(R.id.btn_open_right_drawer).setOnClickListener {
            drawerLayout.openDrawer(Gravity.END)
        }
    }

    // ==================== CHAT ====================

    private fun setupChatList() {
        recyclerView = findViewById(R.id.rv_chat_messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages) { null }
        recyclerView.adapter = chatAdapter
    }

    private fun setupComposer() {
        etChatInput = findViewById(R.id.et_chat_input)
        btnPlayPause = findViewById(R.id.btn_voice_play_pause)
        val btnSend = findViewById<ImageButton>(R.id.btn_send)
        val btnMic = findViewById<ImageButton>(R.id.btn_mic)

        btnSend.setOnClickListener {
            val text = etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etChatInput.setText("")
            }
        }

        btnMic.setOnClickListener {
            checkAudioPermissionAndListen()
        }

        btnPlayPause.setOnClickListener {
            isAudioPlaybackEnabled = !isAudioPlaybackEnabled
            btnPlayPause.setImageResource(
                if (isAudioPlaybackEnabled) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            if (!isAudioPlaybackEnabled) {
                ttsManager.stop()
            }
        }
    }

    private fun sendMessage(text: String) {
        val userMessage = ChatMessage(text = text, isUser = true)
        messages.add(userMessage)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        lifecycleScope.launch {
            val response = cumulusEngine.generateResponse(text)
            val aiMessage = ChatMessage(text = response, isUser = false)
            messages.add(aiMessage)
            chatAdapter.notifyItemInserted(messages.size - 1)
            recyclerView.scrollToPosition(messages.size - 1)

            if (isAudioPlaybackEnabled) {
                ttsManager.speak(response)
            }
        }
    }

    // ==================== MICRÓFONO ====================

    private fun checkAudioPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        } else {
            launchSpeechRecognizer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchSpeechRecognizer()
            } else {
                Toast.makeText(this, "Se necesita permiso de micrófono", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== IDIOMAS ====================

    private fun setupLanguageButtons() {
        val languageButtons = mapOf(
            R.id.btnLanguageEs to "es",
            R.id.btnLanguageEn to "en",
            R.id.btnLanguageRu to "ru",
            R.id.btnLanguageZh to "zh",
            R.id.btnLanguageKo to "ko",
            R.id.btnLanguageJa to "ja",
            R.id.btnLanguagePt to "pt"
        )

        languageButtons.forEach { (id, code) ->
            findViewById<Button>(id).setOnClickListener {
                LanguageManager.setLocale(this, code)
                LanguageManager.applyToViewTree(findViewById(R.id.drawer_layout), code)
                Toast.makeText(this, "Idioma actualizado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== ELEVENLABS ====================

    private fun setupElevenLabs() {
        val etApiKey = findViewById<EditText>(R.id.et_eleven_api_key)
        val btnSaveKey = findViewById<MaterialButton>(R.id.btn_save_eleven_key)
        val btnRecord = findViewById<MaterialButton>(R.id.btn_eleven_record)
        val btnTest = findViewById<MaterialButton>(R.id.btn_eleven_test)
        val btnUse = findViewById<MaterialButton>(R.id.btn_eleven_use)
        val btnDelete = findViewById<MaterialButton>(R.id.btn_eleven_delete)

        etApiKey.setText(prefs.getString("eleven_api_key_display", ""))

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                ttsManager.configureElevenLabs(key, ttsManager.elevenLabsVoiceId)
                prefs.edit().putString("eleven_api_key_display", key).apply()
                Toast.makeText(this, "API Key guardada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ingresa una API Key válida", Toast.LENGTH_SHORT).show()
            }
        }

        btnRecord.setOnClickListener {
            Toast.makeText(this, "Función de grabación pendiente de implementar", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            if (ttsManager.elevenLabsApiKey.isNotEmpty()) {
                ttsManager.setProvider(TtsProvider.ELEVEN_LABS)
                ttsManager.speak("Esta es una prueba de la voz de ElevenLabs")
            } else {
                Toast.makeText(this, "Configura primero tu API Key", Toast.LENGTH_SHORT).show()
            }
        }

        btnUse.setOnClickListener {
            if (ttsManager.elevenLabsApiKey.isNotEmpty()) {
                ttsManager.setProvider(TtsProvider.ELEVEN_LABS)
                Toast.makeText(this, "ElevenLabs activado como voz principal", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Configura primero tu API Key", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete.setOnClickListener {
            etApiKey.setText("")
            ttsManager.configureElevenLabs("", ttsManager.elevenLabsVoiceId)
            prefs.edit().remove("eleven_api_key_display").apply()
            ttsManager.setProvider(TtsProvider.ANDROID_DEFAULT)
            Toast.makeText(this, "API Key eliminada", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== PANEL DE MODELOS / CONFIGURACIÓN ====================

    private fun setupModelSettingsPanel() {
        settingsFlipper = findViewById(R.id.settings_page_flipper)

        findViewById<MaterialButton>(R.id.btn_open_gguf_downloads).setOnClickListener {
            settingsFlipper.displayedChild = 1
        }

        findViewById<MaterialButton>(R.id.btn_open_tts_downloads).setOnClickListener {
            settingsFlipper.displayedChild = 2
        }

        val spinnerBackend = findViewById<Spinner>(R.id.spinner_backend)
        val backendOptions = listOf("CPU", "GPU", "NPU")
        spinnerBackend.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backendOptions)

        val btnLoadModel = findViewById<MaterialButton>(R.id.btn_load_model)
        val tvModelLoadedName = findViewById<TextView>(R.id.tv_model_loaded_name)

        btnLoadModel.setOnClickListener {
            val selectedBackend = spinnerBackend.selectedItem?.toString() ?: "CPU"
            Toast.makeText(this, "Cargando modelo con backend: $selectedBackend", Toast.LENGTH_SHORT).show()
            val loaded = engineManager.initializeEngine("modelo_seleccionado.gguf")
            if (loaded) {
                tvModelLoadedName.text = "Modelo: cargado ($selectedBackend)"
            }
        }

        setupSeekBar(
            R.id.seek_context, R.id.tv_context_value,
            formatValue = { "$it tok" }
        )
        setupSeekBar(
            R.id.seek_temperature, R.id.tv_temperature_value,
            formatValue = { "%.1f".format(it / 100.0) }
        )
        setupSeekBar(R.id.seek_top_k, null, formatValue = { "$it" })
        setupSeekBar(R.id.seek_top_p, null, formatValue = { "$it" })
    }

    private fun setupSeekBar(seekBarId: Int, valueLabelId: Int?, formatValue: (Int) -> String) {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val label = valueLabelId?.let { findViewById<TextView>(it) }

        label?.text = formatValue(seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label?.text = formatValue(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ==================== PERSONALIDAD ====================

    private fun setupPersonality() {
        val btnGeneral = findViewById<MaterialButton>(R.id.btn_personality_general)
        val btnProgrammer = findViewById<MaterialButton>(R.id.btn_personality_programmer)
        val btnCreative = findViewById<MaterialButton>(R.id.btn_personality_creative)
        val etCustom = findViewById<EditText>(R.id.et_personality_custom)
        val btnSaveCustom = findViewById<MaterialButton>(R.id.btn_save_custom_personality)
        val btnDeleteCustom = findViewById<MaterialButton>(R.id.btn_delete_custom_personality)

        etCustom.setText(prefs.getString("custom_personality", ""))

        btnGeneral.setOnClickListener {
            prefs.edit().putString("selected_personality", "general").apply()
            Toast.makeText(this, "Personalidad: Asistente General", Toast.LENGTH_SHORT).show()
        }

        btnProgrammer.setOnClickListener {
            prefs.edit().putString("selected_personality", "programmer").apply()
            Toast.makeText(this, "Personalidad: Programador", Toast.LENGTH_SHORT).show()
        }

        btnCreative.setOnClickListener {
            prefs.edit().putString("selected_personality", "creative").apply()
            Toast.makeText(this, "Personalidad: Creativo", Toast.LENGTH_SHORT).show()
        }

        btnSaveCustom.setOnClickListener {
            val text = etCustom.text.toString().trim()
            prefs.edit()
                .putString("custom_personality", text)
                .putString("selected_personality", "custom")
                .apply()
            Toast.makeText(this, "Personalidad personalizada guardada", Toast.LENGTH_SHORT).show()
        }

        btnDeleteCustom.setOnClickListener {
            etCustom.setText("")
            prefs.edit().remove("custom_personality").apply()
            Toast.makeText(this, "Personalidad personalizada eliminada", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== NOMBRES ====================

    private fun setupNames() {
        val etAiName = findViewById<EditText>(R.id.et_ai_name_custom)
        val btnSaveAiName = findViewById<MaterialButton>(R.id.btn_save_ai_name)
        val etUserName = findViewById<EditText>(R.id.et_user_name)
        val btnSaveUserName = findViewById<MaterialButton>(R.id.btn_save_user_name)
        val tvAiName = findViewById<TextView>(R.id.tv_ai_name)

        etAiName.setText(prefs.getString("ai_custom_name", ""))
        etUserName.setText(prefs.getString("user_name", ""))

        btnSaveAiName.setOnClickListener {
            val name = etAiName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString("ai_custom_name", name).apply()
                tvAiName.text = name
                Toast.makeText(this, "Nombre de la IA guardado", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveUserName.setOnClickListener {
            val name = etUserName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString("user_name", name).apply()
                Toast.makeText(this, "Nombre de usuario guardado", Toast.LENGTH_SHORT).show()
            }
        }

        prefs.getString("ai_custom_name", null)?.let {
            if (it.isNotEmpty()) tvAiName.text = it
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.release()
        engineManager.releaseEngine()
    }
}
