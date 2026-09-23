package com.paulrj.cumulusia.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBarComplete(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    isAudioPlaybackEnabled: Boolean,
    onPlayPauseToggle: () -> Unit,
    isInternetEnabled: Boolean,
    onInternetToggle: () -> Unit,
    attachedUris: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
    onFileClick: () -> Unit,
    onImageClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoClick: () -> Unit,
    onVoiceToVoiceClick: () -> Unit
) {
    val darkBlueColor = Color(0xFF0D47A1)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            if (attachedUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachedUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(75.dp)
                                .padding(4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.InsertDriveFile,
                                        contentDescription = "Archivo adjunto"
                                    )
                                }
                            }
                            
                            IconButton(
                                onClick = { onRemoveAttachment(uri) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Eliminar archivo",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = { Text("Pregúntame...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayPauseToggle) {
                    Text(
                        text = if (isAudioPlaybackEnabled) "PAUSE" else "PLAY",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isAudioPlaybackEnabled) darkBlueColor else LocalContentColor.current
                    )
                }

                IconButton(onClick = onInternetToggle) {
                    Text(
                        text = if (isInternetEnabled) "NET:ON" else "NET:OFF",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isInternetEnabled) darkBlueColor else LocalContentColor.current
                    )
                }
                
                IconButton(onClick = onFileClick) {
                    Text("A", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                IconButton(onClick = onImageClick) {
                    Text("F", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                IconButton(onClick = onCameraClick) {
                    Text("CAM", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                
                IconButton(onClick = onVideoClick) {
                    Text("V", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                IconButton(onClick = onVoiceToVoiceClick) {
                    Text("V2V", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                
                IconButton(onClick = onMicClick) {
                    Text("MIC", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                
                IconButton(onClick = onSendClick) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}
