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
            addView(textView)
        }
        setContentView(scrollView)
    }
}
