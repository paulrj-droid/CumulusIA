package com.paulrj.cumulusia.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * ⬇️ Aviso en la barra de notificaciones cada vez que la app guarda un archivo en el teléfono
 * (flecha ⬇️ del chat, «Descargar» y «Guardar como» del visor de código, etc.).
 *
 *  • Tocar el aviso abre el archivo. El botón «Compartir» lo envía a otra app.
 *  • Si falta el permiso de notificaciones, se pide una sola vez por sesión
 *    y, si lo das, aparece el aviso de lo que se acaba de guardar.
 */
object SavedFileNotifier {

    private const val CHANNEL_ID = "cumulus_saved_files"
    // Cada archivo tiene su propio aviso (no se reemplazan entre ellos)
    private val nextId = AtomicInteger(5200)
    private val pending = mutableListOf<Saved>()
    @Volatile private var askedThisSession = false

    private data class Saved(val uri: Uri, val name: String, val mime: String, val where: String)

    /** Lugares (en español: el diccionario los traduce al idioma de la app). */
    const val WHERE_DOWNLOADS = "Guardado en Descargas/CumulusIA"
    const val WHERE_PICTURES = "Guardado en tu galería (Pictures/CumulusIA)"
    const val WHERE_MOVIES = "Guardado en tu galería (Movies/CumulusIA)"
    const val WHERE_CHOSEN = "Guardado en la carpeta que elegiste"

    fun whereFor(mime: String): String = when {
        mime.startsWith("image/") -> WHERE_PICTURES
        mime.startsWith("video/") -> WHERE_MOVIES
        else -> WHERE_DOWNLOADS
    }

    /** Llamar justo después de guardar el archivo. Se puede llamar desde cualquier hilo. */
    fun notify(ctx: Context, uri: Uri, name: String, mime: String, where: String = whereFor(mime)) {
        val saved = Saved(uri, name, mime, where)
        if (hasPermission(ctx)) { post(ctx.applicationContext, saved); return }
        synchronized(pending) { pending += saved }
        askPermission(ctx)
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun findActivity(ctx: Context): androidx.activity.ComponentActivity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is androidx.activity.ComponentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** Pide el permiso de notificaciones (Android 13+) y, si lo das, muestra lo que quedó pendiente. */
    private fun askPermission(ctx: Context) {
        if (askedThisSession) { synchronized(pending) { pending.clear() }; return }
        val act = findActivity(ctx) ?: run { synchronized(pending) { pending.clear() }; return }
        askedThisSession = true
        act.runOnUiThread {
            runCatching {
                var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
                launcher = act.activityResultRegistry.register(
                    "cumulus_notif_" + System.nanoTime(),
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    launcher?.unregister()
                    val list = synchronized(pending) { pending.toList().also { pending.clear() } }
                    if (granted) list.forEach { post(act.applicationContext, it) }
                }
                launcher.launch("android.permission.POST_NOTIFICATIONS")
            }.onFailure { synchronized(pending) { pending.clear() } }
        }
    }

    private fun tr(ctx: Context, es: String) = runCatching { LanguageManager.text(ctx, es) }.getOrDefault(es)

    private fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr(ctx, "Archivos guardados"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr(ctx, "Aviso cuando descargas o guardas un archivo")
                setSound(null, null)          // sin sonido: solo aparece en la barra
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    /** Los archivos de código y texto (.kt, .java, .py…) se abren como texto para que cualquier editor los muestre. */
    private val TEXT_EXT = setOf(
        "txt", "md", "kt", "kts", "java", "py", "js", "ts", "html", "htm", "css", "json", "xml", "c", "cpp", "h", "cs",
        "go", "rs", "swift", "dart", "php", "rb", "sh", "sql", "yml", "yaml", "gradle", "groovy", "properties", "csv"
    )

    private fun openMime(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext in TEXT_EXT && (mime == "application/octet-stream" || mime.startsWith("text/") || mime == "application/json") -> "text/plain"
            mime.isBlank() -> "*/*"
            else -> mime
        }
    }

    private fun post(app: Context, s: Saved) {
        runCatching {
            createChannel(app)
            val id = nextId.getAndIncrement()
            val type = openMime(s.name, s.mime)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            // 📂 Tocar el aviso → abrir el archivo
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(s.uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openChooser = Intent.createChooser(open, tr(app, "Abrir con")).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPi = PendingIntent.getActivity(app, id * 2, openChooser, flags)

            // 📤 Botón «Compartir»
            val send = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, s.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val sendChooser = Intent.createChooser(send, tr(app, "Compartir")).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val sendPi = PendingIntent.getActivity(app, id * 2 + 1, sendChooser, flags)

            val where = tr(app, s.where)
            val tap = tr(app, "Toca para abrir")
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(tr(app, "Descarga completada"))
                .setContentText(s.name)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${s.name}\n$where\n$tap"))
                .setSubText("Cumulus")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(0, tr(app, "Compartir"), sendPi)
                .build()
            app.getSystemService(NotificationManager::class.java)?.notify(id, n)
        }
    }
}

