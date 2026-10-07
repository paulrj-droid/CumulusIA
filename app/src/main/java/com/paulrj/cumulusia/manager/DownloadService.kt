package com.paulrj.cumulusia.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * 📥 Mantiene las descargas trabajando aunque salgas de la app o la cierres,
 * y muestra el progreso en la barra de notificaciones (arriba, junto a la hora y la batería).
 *
 * - Las descargas en curso viven aquí (ActiveDownloads), no en la pantalla: al volver a abrir
 *   la app se reconectan a la misma descarga en vez de empezar otra.
 * - El servicio se apaga solo cuando ya no queda ninguna descarga activa.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Throwable) {
            // Si Android no deja iniciar en primer plano, la descarga sigue mientras la app esté abierta
            stopSelf()
            return START_NOT_STICKY
        }
        if (progress.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cumulus_downloads"
        private const val NOTIF_ID = 4711
        private const val DONE_NOTIF_ID = 4712

        /** id de la descarga → (título, porcentaje) de las que están bajando ahora. */
        private val progress = ConcurrentHashMap<String, Pair<String, Int>>()
        @Volatile private var running = false
        @Volatile private var lastNotify = 0L
        private var cachedIntent: PendingIntent? = null
        @Volatile private var lastShownPct = -1
        // La hora de la notificación queda fija: si cambia en cada aviso, la barra la reordena y la vuelve a dibujar
        @Volatile private var startedAt = 0L

        private fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW).apply {
                            description = "Progreso de las descargas de modelos y voces"
                            setShowBadge(false)
                        }
                    )
                }
            }
        }

        private fun openAppIntent(ctx: Context): PendingIntent? {
            cachedIntent?.let { return it }
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            return PendingIntent.getActivity(ctx, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).also { cachedIntent = it }
        }

        private fun buildNotification(ctx: Context): Notification {
            val items = progress.values.toList()
            val title = when (items.size) {
                0 -> "Descargas"
                1 -> items[0].first
                else -> "Descargando ${items.size} archivos"
            }
            val known = items.map { it.second }.filter { it >= 0 }
            val pct = if (known.isEmpty()) -1 else known.sum() / known.size
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setWhen(startedAt)
                .setShowWhen(false)
                // Solo en el teléfono: no se reenvía a cada rato al reloj inteligente u otros dispositivos
                .setLocalOnly(true)
                .setContentTitle(title)
                .setContentText(if (pct >= 0) "$pct %" else "Descargando…")
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
        }

        /** Llamar en cada avance. Arranca el servicio la primera vez y actualiza la notificación. */
        fun onProgress(ctx: Context, id: String, title: String, percent: Int) {
            val app = ctx.applicationContext
            val prev = progress.put(id, title to percent)
            if (!running) {
                running = true
                createChannel(app)
                runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
                    .onFailure { running = false }
                return
            }
            // Cada 3 segundos, y solo si cambió el porcentaje: así la barra de notificaciones no se pega
            val now = System.currentTimeMillis()
            if (prev?.second == percent) return
            // Solo cada 5 % (20 cambios en toda la descarga): casi no toca la barra mientras la miras
            if (percent >= 0 && lastShownPct >= 0 && percent / 5 == lastShownPct / 5 && percent != 100) return
            if (now - lastNotify < 3000 && percent != 100) return
            lastNotify = now
            lastShownPct = percent
            runCatching {
                app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(app))
            }
        }

        /** Llamar cuando una descarga termina, se pausa, falla o se elimina. */
        fun onStopped(ctx: Context, id: String, finishedTitle: String? = null) {
            val app = ctx.applicationContext
            progress.remove(id)
            val nm = app.getSystemService(NotificationManager::class.java)
            if (finishedTitle != null) {
                runCatching {
                    nm.notify(DONE_NOTIF_ID, NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Descarga completada")
                        .setContentText(finishedTitle)
                        .setAutoCancel(true)
                        .setContentIntent(openAppIntent(app))
                        .build())
                }
            }
            if (progress.isEmpty()) {
                if (running) runCatching { app.stopService(Intent(app, DownloadService::class.java)) }
                running = false
                lastShownPct = -1
                startedAt = 0L
                runCatching { nm.cancel(NOTIF_ID) }
            } else {
                runCatching { nm.notify(NOTIF_ID, buildNotification(app)) }
            }
        }
    }
}

/** Descargas en curso de toda la app (sobreviven a que se cierre la pantalla). */
object ActiveDownloads {
    val map = ConcurrentHashMap<String, ResumableDownloader>()
    /** Archivos que todavía se están descargando (incompletos): no se deben cargar como modelo. */
    val paths: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
