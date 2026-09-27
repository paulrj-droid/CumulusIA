package com.paulrj.cumulusia

import android.app.Application
import android.content.Intent

class CumulusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val trace = sw.toString()

                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra("trace", trace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                // si esto falla, seguimos igual al cierre normal
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}
