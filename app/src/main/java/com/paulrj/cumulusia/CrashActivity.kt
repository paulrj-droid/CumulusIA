package com.paulrj.cumulusia

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "Sin información de error."

        val textView = TextView(this).apply {
            text = trace
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        val scrollView = ScrollView(this).apply {
            // 📱 Android 15/16 dibujan de borde a borde: así el texto no queda debajo de la hora ni de la barra de gestos
            fitsSystemWindows = true
            addView(textView)
        }
        setContentView(scrollView)
    }
}

