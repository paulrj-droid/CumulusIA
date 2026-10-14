package com.paulrj.cumulusia.manager

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descargador con soporte de PAUSA, REANUDAR y ELIMINAR.
 * Usa cabeceras "Range" para reanudar desde donde se quedó (HuggingFace lo soporta).
 *
 * ♾️ Sin límite de tamaño: todos los contadores son Long (sirve para 3 GB, 50 GB o 100 GB).
 * 🔁 Si se corta (internet que se cae, servidor que corta la conexión, Android que pausa la app),
 *    vuelve a intentarlo SOLO y sigue desde el último byte guardado, sin empezar de cero.
 */
class ResumableDownloader {

    enum class State { IDLE, DOWNLOADING, PAUSED, FINISHED, FAILED }

    interface Listener {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
        fun onStateChanged(state: State)
        /** 🔁 Se cortó y va a reintentar sola (intento número [attempt], ya lleva [downloaded] bytes). */
        fun onRetry(attempt: Int, downloaded: Long) {}
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

    /** 🔁 Reintento automático en curso (para mostrar «Reconectando…»). */
    @Volatile var retrying: Int = 0; private set

    private companion object {
        const val SYNC_EVERY = 8L shl 20   // 8 MB
        /** Reintentos seguidos sin avanzar nada (si avanza, la cuenta vuelve a cero). ~40 min en total. */
        const val MAX_RETRIES = 40
    }

    /** Se cortó antes de terminar (para reintentar desde donde quedó). */
    private class CutException(msg: String) : Exception(msg)

    /** El enlace no sirve (no existe, sin permiso…): reintentar no sirve de nada. */
    private class FatalException(msg: String) : Exception(msg)

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

    fun resume() {
        if (state == State.PAUSED || state == State.FAILED) {
            // Retoma desde lo que ya está guardado en el archivo
            outputFile?.let { downloaded = if (it.exists()) it.length() else 0L }
            launchThread()
        }
    }

    fun cancel(deleteFile: Boolean = true) {
        cancelRequested = true
        pauseRequested = false
        thread?.interrupt()   // si estaba esperando para reintentar, se despierta y se detiene
        if (deleteFile && state != State.DOWNLOADING) outputFile?.delete()
    }

    private fun launchThread() {
        state = State.DOWNLOADING
        listener?.onStateChanged(state)

        thread = Thread {
            // 🐢 Prioridad baja: la descarga cede el procesador a la pantalla y a la barra de notificaciones
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            var failures = 0
            var result: State? = null
            while (result == null) {
                val before = downloaded
                try {
                    result = downloadOnce()
                } catch (e: Exception) {
                    if (cancelRequested) { outputFile?.delete(); result = State.IDLE; break }
                    if (pauseRequested) { result = State.PAUSED; break }
                    if (e is FatalException) { result = State.FAILED; break }
                    // Si alcanzó a avanzar algo, la cuenta de intentos vuelve a empezar
                    failures = if (downloaded > before) 1 else failures + 1
                    if (failures > MAX_RETRIES) { result = State.FAILED; break }
                    retrying = failures
                    runCatching { listener?.onRetry(failures, outputFile?.length() ?: downloaded) }
                    // Espera 5 s, 10 s, 20 s… hasta 60 s entre intentos (deja que vuelva el internet)
                    val waitMs = minOf(60_000L, 5_000L shl minOf(failures - 1, 4))
                    try { Thread.sleep(waitMs) } catch (_: InterruptedException) { }
                    if (cancelRequested) { outputFile?.delete(); result = State.IDLE; break }
                    if (pauseRequested) { result = State.PAUSED; break }
                    // Retoma desde lo que ya quedó guardado en el archivo
                    outputFile?.let { downloaded = if (it.exists()) it.length() else 0L }
                }
            }
            retrying = 0
            state = result ?: State.FAILED
            pauseRequested = false
            cancelRequested = false
            listener?.onStateChanged(state)
        }
        thread?.start()
    }

    /** Un intento de descarga (desde [downloaded]). Devuelve el estado final o lanza una excepción si se cortó. */
    private fun downloadOnce(): State {
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
                // 416: lo pedido empieza después del final → el archivo ya estaba completo
                416 -> if (downloaded > 0 && (totalSize <= 0 || downloaded >= totalSize)) return State.FINISHED
                    else throw CutException("HTTP 416")
                in 500..599, 408, 429 -> throw CutException("HTTP $code")   // servidor ocupado: se reintenta
                else -> throw FatalException("HTTP $code")
            }
            retrying = 0

            input = connection.inputStream
            // ⚡ Escritura en bloques grandes (1 MB): antes escribía de a 8 KB y eso
            //    cargaba al teléfono hasta pegar la barra de notificaciones
            val fileOut = FileOutputStream(file, downloaded > 0)   // reanudar = añadir
            output = java.io.BufferedOutputStream(fileOut, 1 shl 20)
            // 💾 Cada 8 MB se manda al almacenamiento de a poco. Si no, Android junta cientos de MB
            //    en memoria y los escribe todos de golpe: en ese momento el teléfono entero (y la
            //    barra de notificaciones) se pegaba por intervalos.
            var sinceSync = 0L

            val buffer = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (cancelRequested) break
                output.write(buffer, 0, read)
                downloaded += read
                sinceSync += read
                if (sinceSync >= SYNC_EVERY) {
                    sinceSync = 0
                    output.flush()
                    runCatching { fileOut.fd.sync() }
                }
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
            runCatching { fileOut.fd.sync() }

            return when {
                cancelRequested -> { file.delete(); State.IDLE }
                pauseRequested -> State.PAUSED
                // 🔁 La conexión terminó antes de tiempo: no está completo, se reintenta desde aquí
                totalSize > 0 && downloaded < totalSize -> throw CutException("Conexión cortada en $downloaded de $totalSize")
                else -> State.FINISHED
            }
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}

