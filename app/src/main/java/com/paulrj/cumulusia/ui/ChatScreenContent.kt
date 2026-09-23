import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun ChatScreenContent(
    text: String,
    onTextChanged: (String) -> Unit,
    attachedUris: List<Uri>,
    onAddUri: (Uri) -> Unit,
    onRemoveUri: (Uri) -> Unit,
    onSendClick: () -> Unit,
    // ... demas estados (audio, internet, etc.)
) {
    val context = androidx.ui.core.LocalContext.current // O androidx.compose.ui.platform.LocalContext.current

    // Estado temporal para guardar la Uri de la foto tomada con la cámara
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // 1. Launcher para Archivos generales (Botón A)
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onAddUri(it) }
    }

    // 2. Launcher para Imágenes/Fotos de la galería (Botón F)
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onAddUri(it) }
    }

    // 3. Launcher para Videos (Botón V)
    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onAddUri(it) }
    }

    // 4. Launcher para la Cámara (Icono de Cámara)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempCameraUri?.let { onAddUri(it) }
        }
    }

    // Launcher para pedir permiso de cámara si es necesario
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    // Llamada a tu barra de chat conectando cada botón con su respectiva acción
    ChatInputBarComplete(
        text = text,
        onTextChanged = onTextChanged,
        onSendClick = onSendClick,
        onMicClick = { /* Lógica de micrófono */ },
        isAudioPlaybackEnabled = false,
        onPlayPauseToggle = { /* Toggle voz IA */ },
        isInternetEnabled = true,
        onInternetToggle = { /* Toggle internet */ },
        attachedUris = attachedUris,
        onRemoveAttachment = { uriToRemove -> onRemoveUri(uriToRemove) },
        onFileClick = { 
            fileLauncher.launch("*/*") // Permite cualquier archivo general
        },
        onImageClick = { 
            imageLauncher.launch("image/*") // Filtra solo imágenes
        },
        onCameraClick = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onVideoClick = { 
            videoLauncher.launch("video/*") // Filtra solo videos
        },
        onVoiceToVoiceClick = { /* Lógica voz a voz */ }
    )
}
