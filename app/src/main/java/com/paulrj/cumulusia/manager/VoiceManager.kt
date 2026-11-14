package com.paulrj.cumulusia.manager

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.paulrj.cumulusia.model.TtsVoice
import java.io.File

class VoiceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("cumulus_tts_prefs", Context.MODE_PRIVATE)

    private val voicesDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "tts_voices")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getDownloadedVoices(): List<TtsVoice> {
        val files = voicesDir.listFiles { _, name ->
            name.endsWith(".onnx", ignoreCase = true) ||
            name.endsWith(".bin", ignoreCase = true) ||
            name.endsWith(".zip", ignoreCase = true)
        }
        return files?.map { TtsVoice.fromFile(it) } ?: emptyList()
    }

    // Guarda la voz activa seleccionada por el usuario
    fun saveSelectedVoice(voicePath: String) {
        prefs.edit().putString("selected_voice_path", voicePath).apply()
    }

}
