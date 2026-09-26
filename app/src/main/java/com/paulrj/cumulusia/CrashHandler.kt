package com.paulrj.cumulusia

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura cualquier error no controlado de la app, lo guarda,
 * y la próxima vez que abras la app te muestra POR QUÉ falló.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val PREFS = "CumulusCrash"
    private const val KEY = "last_crash"

    @Volatile private var installed = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            appContext = context.applicationContext
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
            installed = true
        }
    }

    fun getLastCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clearLastCrash(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val report = buildReport(t, e)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, report).apply()
        } catch (ignored: Exception) {
        }
        defaultHandler?.uncaughtException(t, e)
            ?: android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun buildReport(t: Thread, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().trim().lines().take(12).joinToString("\n")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return """
            🛑 REPORTE DE ERROR — CUMULUS IA
            Fecha: $date
            Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})
            Hilo: ${t.name}
            Error: ${e.javaClass.simpleName}
            Mensaje: ${e.message ?: "sin mensaje"}

            Traza del fallo:
            $trace
        """.trimIndent()
    }
}
