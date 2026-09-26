package com.paulrj.cumulusia

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.paulrj.cumulusia.engine.LiteRtLmEngineManager
import com.paulrj.cumulusia.manager.LanguageManager

class MainActivity : AppCompatActivity() {

    private lateinit var engineManager: LiteRtLmEngineManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LanguageManager.loadLocale(this)
        engineManager = LiteRtLmEngineManager(this)
        
        // Inicialización de componentes principales de Cumulus IA
    }
}
