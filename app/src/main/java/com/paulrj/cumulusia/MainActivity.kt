package com.paulrj.cumulusia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.paulrj.cumulusia.adapter.ChatAdapter
import com.paulrj.cumulusia.ai.CumulusEngine
import com.paulrj.cumulusia.engine.LiteRtLmEngineManager
import com.paulrj.cumulusia.manager.LanguageManager
import com.paulrj.cumulusia.model.ChatMessage
import com.paulrj.cumulusia.tts.TtsEngineManager
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

    private val messages = mutableListOf<ChatMessage>()
    private var isAudioPlaybackEnabled = false

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 101
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

        LanguageManager.loadLocale(this)
        engineManager = LiteRtLmEngineManager(this)
        cumulusEngine = CumulusEngine()
        ttsManager = TtsEngineManager(this)

        setupDrawer()
        setupChatList()
        setupComposer()
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)

        findViewById<ImageButton>(R.id.btn_open_left_drawer).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        findViewById<ImageButton>(R.id.btn_open_right_drawer).setOnClickListener {
            drawerLayout.openDrawer(Gravity.END)
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.release()
        engineManager.releaseEngine()
    }
}
