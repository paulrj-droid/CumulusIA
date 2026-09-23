package com.cumulusia.app.manager

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ResumableDownloader {

    enum class State {
        IDLE, DOWNLOADING, PAUSED, FINISHED, FAILED
    }

    interface Listener {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
        fun onStateChanged(state: State)
    }

    var listener: Listener? = null
    var state: State = State.IDLE
        private set

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private var downloadThread: Thread? = null

    fun start(urlString: String, targetFile: File) {
        if (state == State.DOWNLOADING) return
        isPaused.set(false)
        isCancelled.set(false)
        updateState(State.DOWNLOADING)

        downloadThread = thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var randomAccessFile: RandomAccessFile? = null

            try {
                val url = URL(urlString)
                var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    if (downloadedBytes > 0) {
                        setRequestProperty("Range", "bytes=$downloadedBytes-")
                    }
                    connect()
                }

                val responseCode = connection.responseCode
                val fileLength = connection.contentLength.toLong()
                val totalLength = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    fileLength + downloadedBytes
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    downloadedBytes = 0L
                    fileLength
                } else {
                    throw Exception("Servidor respondió con código HTTP $responseCode")
                }

                inputStream = connection.inputStream
                randomAccessFile = RandomAccessFile(targetFile, "rw").apply {
                    seek(downloadedBytes)
                }

                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (true) {
                    if (isCancelled.get()) {
                        updateState(State.IDLE)
                        break
                    }
                    if (isPaused.get()) {
                        updateState(State.PAUSED)
                        break
                    }

                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        if (totalLength > 0 && targetFile.length() < totalLength) {
                            throw Exception("La descarga se interrumpió antes de completarse.")
                        }
                        updateState(State.FINISHED)
                        break
                    }

                    randomAccessFile.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val percent = if (totalLength > 0) {
                        ((downloadedBytes * 100) / totalLength).toInt()
                    } else {
                        -1
                    }
                    listener?.onProgress(downloadedBytes, totalLength, percent)
                }

            } catch (e: Throwable) {
                if (!isCancelled.get() && !isPaused.get()) {
                    Log.e("ResumableDownloader", "Error en descarga: ${e.message}")
                    updateState(State.FAILED)
                }
            } finally {
                try { inputStream?.close() } catch (_: Throwable) {}
                try { randomAccessFile?.close() } catch (_: Throwable) {}
                try { connection?.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    fun pause() {
        if (state == State.DOWNLOADING) {
            isPaused.set(true)
        }
    }

    fun resume() {
        if (state == State.PAUSED) {
            isPaused.set(false)
        }
    }

    fun cancel(deleteFile: Boolean = false) {
        isCancelled.set(true)
        isPaused.set(false)
        downloadThread?.interrupt()
    }

    private fun updateState(newState: State) {
        state = newState
        listener?.onStateChanged(newState)
    }
}

