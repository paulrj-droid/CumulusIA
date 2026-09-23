package com.cumulusia.app.model

import java.io.File

data class TtsVoice(
    val name: String,
    val filePath: String,
    val sizeBytes: Long,
    val formatTag: String,
    var isLoaded: Boolean = false
) {
    val formattedSize: String
        get() = String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))

    companion object {
        fun fromFile(file: File): TtsVoice {
            val name = file.name
            val ext = file.extension.uppercase()
            val formatTag = if (ext.isNotEmpty()) ext else "VOICE"

            return TtsVoice(
                name = name,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                formatTag = formatTag
            )
        }
    }
}
