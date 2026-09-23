package com.paulrj.cumulusia.ui.components // Ajusta tu paquete según corresponda

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.default.*
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
    // Color azul oscuro solicitado para los botones activos
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
            // 1. VISTA PREVIA DE ADJUNTOS CON LA "X" (Arriba de "Pregúntame...")
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
                            // Tarjeta contenedora de la miniatura del archivo/foto/video
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
                            
                            // Botón "X" flotante para eliminar el adjunto y no enviarlo
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

            // 2. CAMPO DE TEXTO ("Pregúntame...")
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

            // 3. FILA DE BOTONES TRANSPARENTES Y ACCIONES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón Play / Pause (Se vuelve azul oscuro cuando la IA habla automáticamente)
                IconButton(onClick = onPlayPauseToggle) {
                    Icon(
                        imageVector = if (isAudioPlaybackEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause Voz IA",
                        tint = if (isAudioPlaybackEnabled) darkBlueColor else LocalContentColor.current
                    )
                }

                // Botón On/Off Internet (Se vuelve azul oscuro cuando está habilitado)
                IconButton(onClick = onInternetToggle) {
                    Icon(
                        imageVector = if (isInternetEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = "Internet On/Off",
                        tint = if (isInternetEnabled) darkBlueColor else LocalContentColor.current
                    )
                }
                
                // Botón A (Archivos)
                IconButton(onClick = onFileClick) {
                    Text("A", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                // Botón F (Fotos)
                IconButton(onClick = onImageClick) {
                    Text("F", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                // Botón Cámara
                IconButton(onClick = onCameraClick) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Cámara")
                }
                
                // Botón V (Videos)
                IconButton(onClick = onVideoClick) {
                    Text("V", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
                
                // Botón Voz a Voz (Barras)
                IconButton(onClick = onVoiceToVoiceClick) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Voz a voz")
                }
                
                // Botón Micrófono
                IconButton(onClick = onMicClick) {
                    Icon(Icons.Default.Mic, contentDescription = "Micrófono")
                }
                
                // Botón Enviar
                IconButton(onClick = onSendClick) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

