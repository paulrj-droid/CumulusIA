package com.paulrj.cumulusia.manager

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descargador con soporte de PAUSA, REANUDAR y ELIMINAR.
 * Usa cabeceras "Range" para reanudar desde donde se quedó (HuggingFace lo soporta).
 */
class ResumableDownloader {

    enum class State { IDLE, DOWNLOADING, PAUSED, FINISHED, FAILED }

    interface Listener {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
        fun onStateChanged(state: State)
    }

    @Volatile var state: State = State.IDLE; private set
    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false

    private var thread: Thread? = null
    private var url: String = ""
    private var outputFile: File? = null
    private var totalSize: Long = 0
    private var downloaded: Long = 0
    @Volatile private var lastNotify = 0L
    var listener: Listener? = null

    fun start(url: String, file: File) {
        if (state == State.DOWNLOADING) return
        this.url = url
        this.outputFile = file
        this.downloaded = if (file.exists()) file.length() else 0L
        pauseRequested = false
        cancelRequested = false
        launchThread()
    }

    fun pause() { if (state == State.DOWNLOADING) pauseRequested = true }

    fun resume() { if (state == State.PAUSED) launchThread() }

    fun cancel(deleteFile: Boolean = true) {
        cancelRequested = true
        pauseRequested = false
        if (deleteFile && state != State.DOWNLOADING) outputFile?.delete()
    }

    private fun launchThread() {
        state = State.DOWNLOADING
        listener?.onStateChanged(state)

        thread = Thread {
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            var output: java.io.OutputStream? = null
            try {
                val file = outputFile!!
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                    connect()
                }

                val code = connection.responseCode
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        totalSize = connection.contentLengthLong
                        downloaded = 0
                    }
                    206 -> {
                        connection.getHeaderField("Content-Range")
                            ?.substringAfter('/')?.toLongOrNull()?.let { totalSize = it }
                    }
                    else -> throw Exception("HTTP $code")
                }

                input = connection.inputStream
                // ⚡ Escritura en bloques grandes (1 MB): antes escribía de a 8 KB y eso
                //    cargaba al teléfono hasta pegar la barra de notificaciones
                output = java.io.BufferedOutputStream(FileOutputStream(file, downloaded > 0), 1 shl 20) // reanudar = añadir

                val buffer = ByteArray(256 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (cancelRequested) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val percent = if (totalSize > 0) ((downloaded * 100) / totalSize).toInt() else -1
                    // Aviso de avance máximo ~5 veces por segundo (antes en cada bloque)
                    val now = System.currentTimeMillis()
                    if (now - lastNotify >= 200 || percent == 100) {
                        lastNotify = now
                        listener?.onProgress(downloaded, totalSize, percent)
                    }
                    if (pauseRequested) break
                }

                output.flush()

                state = when {
                    cancelRequested -> { file.delete(); State.IDLE }
                    pauseRequested -> State.PAUSED
                    else -> State.FINISHED
                }
            } catch (e: Exception) {
                state = State.FAILED
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
                pauseRequested = false
                cancelRequested = false
                listener?.onStateChanged(state)
            }
        }
        thread?.start()
    }
}

