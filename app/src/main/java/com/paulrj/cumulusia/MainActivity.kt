package com.cumulus.ia

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cumulus.ia.engine.LiteRtLmEngineManager
import com.cumulus.ia.utils.LanguageManager

class MainActivity : AppCompatActivity() {

    private lateinit var languageManager: LanguageManager
    private lateinit var engineManager: LiteRtLmEngineManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        languageManager = LanguageManager(this)
        engineManager = LiteRtLmEngineManager(this)
        
        // Inicialización de componentes principales de Cumulus IA
    }
}

