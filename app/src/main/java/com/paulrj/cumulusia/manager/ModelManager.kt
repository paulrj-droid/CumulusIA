package com.paulrj.cumulusia.manager

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.paulrj.cumulusia.model.GgufModel
import java.io.File

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("cumulus_ia_prefs", Context.MODE_PRIVATE)

    private val modelsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getDownloadedModels(): List<GgufModel> {
        val files = modelsDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }
        return files?.map { GgufModel.fromFile(it) } ?: emptyList()
    }

    /**
     * Obtiene el modelo activo de forma automática o manual.
     * Si no hay ninguno guardado pero existen modelos descargados, selecciona el primero automáticamente.
     */
    fun getSelectedModel(): GgufModel? {
        val models = getDownloadedModels()
        if (models.isEmpty()) return null

        val savedPath = prefs.getString("selected_model_path", null)
        
        // Buscar si el modelo guardado aún existe físicamente
        val selected = models.find { it.filePath == savedPath }
        if (selected != null) {
            return selected
        }

        // Selección automática del primer modelo disponible si no hay uno guardado o válido
        val firstModel = models.first()
        setSelectedModel(firstModel)
        return firstModel
    }

    fun setSelectedModel(model: GgufModel) {
        prefs.edit().putString("selected_model_path", model.filePath).apply()
    }

    fun startDownload(url: String, fileName: String, onEnqueue: (Long) -> Unit) {
        val destinationFile = File(modelsDir, fileName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Descargando modelo GGUF")
            setDescription(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destinationFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        onEnqueue(downloadId)
    }

    fun deleteModel(model: GgufModel): Boolean {
        val file = File(model.filePath)
        val deleted = if (file.exists()) file.delete() else false
        
        // Si borramos el modelo activo, limpiamos la preferencia
        val savedPath = prefs.getString("selected_model_path", null)
        if (savedPath == model.filePath) {
            prefs.edit().remove("selected_model_path").apply()
        }
        
        return deleted
    }

    fun registerDownloadReceiver(onComplete: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent?.action) {
                    onComplete()
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
