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

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
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

    fun saveSelectedVoice(voicePath: String) {
        prefs.edit().putString("selected_voice_path", voicePath).apply()
    }

    fun getSelectedVoicePath(): String? {
        return prefs.getString("selected_voice_path", null)
    }

    private fun sanitizeUrl(url: String): String {
        var clean = url.trim()
        if (clean.contains("huggingface.co") && clean.contains("/blob/")) {
            clean = clean.replace("/blob/", "/resolve/")
        }
        return clean
    }

    fun downloadVoiceFromUrl(rawUrl: String, onEnqueue: (Long) -> Unit): Long {
        val cleanUrl = sanitizeUrl(rawUrl)
        val uri = Uri.parse(cleanUrl)
        val fileName = uri.lastPathSegment?.substringBefore("?") ?: "voz_${System.currentTimeMillis()}.onnx"
        val destinationFile = File(voicesDir, fileName)

        val request = DownloadManager.Request(Uri.parse(cleanUrl)).apply {
            setTitle("Cumulus IA - Descargando Voz TTS")
            setDescription(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destinationFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)
        onEnqueue(downloadId)

        if (cleanUrl.endsWith(".onnx", ignoreCase = true)) {
            val jsonUrl = "$cleanUrl.json"
            val jsonFileName = "$fileName.json"
            val jsonDestination = File(voicesDir, jsonFileName)

            val jsonRequest = DownloadManager.Request(Uri.parse(jsonUrl)).apply {
                setTitle("Configuración TTS: $jsonFileName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(jsonDestination))
                setAllowedOverMetered(true)
            }
            try {
                downloadManager.enqueue(jsonRequest)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return downloadId
    }

    fun deleteVoice(voice: TtsVoice): Boolean {
        val file = File(voice.filePath)
        val jsonFile = File("${voice.filePath}.json")
        if (jsonFile.exists()) jsonFile.delete()

        if (getSelectedVoicePath() == voice.filePath) {
            prefs.edit().remove("selected_voice_path").apply()
        }

        return if (file.exists()) file.delete() else false
    }

    fun registerDownloadReceiver(onComplete: (Long) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent?.action) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    onComplete(id)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
