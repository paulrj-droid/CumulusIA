package com.cumulusia.app.model

import java.io.File

data class GgufModel(
    val name: String,
    val filePath: String,
    val sizeBytes: Long,
    val quantType: String,
    val estimatedRamGb: Int,
    var isLoaded: Boolean = false
) {
    val formattedSize: String
        get() = String.format("%.1f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))

    companion object {
        fun fromFile(file: File): GgufModel {
            val name = file.name
            val quantMatch = Regex("Q[0-9]_[K_A-Z0-9]+").find(name)?.value ?: "GGUF"
            val sizeGb = file.length() / (1024.0 * 1024.0 * 1024.0)
            val estimatedRam = (sizeGb * 1.25 + 0.5).toInt()

            return GgufModel(
                name = name,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                quantType = quantMatch,
                estimatedRamGb = estimatedRam
            )
        }
    }
}
