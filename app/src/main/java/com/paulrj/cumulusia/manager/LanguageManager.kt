package com.cumulusia.app.manager

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

object LanguageManager {
    private const val PREFS_NAME = "CumulusAppPermissions"
    private const val KEY_SELECTED_LANGUAGE = "selected_language_code"

    fun setLocale(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
        
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun loadLocale(context: Context): Context {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val languageCode = prefs.getString(KEY_SELECTED_LANGUAGE, "es") ?: "es"
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context
    }

    fun getSelectedLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_LANGUAGE, "es") ?: "es"
    }

    fun applyToViewTree(root: View, languageCode: String = getSelectedLanguage(root.context)) {
        if (root is TextView) {
            val currentText = root.text?.toString().orEmpty()
            translate(languageCode, currentText)?.let { root.text = it }
            if (root is EditText) {
                val currentHint = root.hint?.toString().orEmpty()
                translate(languageCode, currentHint)?.let { root.hint = it }
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyToViewTree(root.getChildAt(index), languageCode)
            }
        }
    }

    fun text(context: Context, spanishText: String): String {
        return translate(getSelectedLanguage(context), spanishText) ?: spanishText
    }

    private fun translate(code: String, source: String): String? {
        if (code == "es") return source
        return translations[code]?.get(source)
    }

    private val translations = mapOf(
        "en" to mapOf(
            "Pregúntame..." to "Ask me...",
            "SERVICIOS Y CONTROL" to "SERVICES AND CONTROL",
            "Activar Burbuja" to "Enable Floating Bubble",
            "Grabar" to "Record", "Probar" to "Test", "Usar" to "Use", "Eliminar" to "Delete",
            "CONFIGURACIÓN PRINCIPAL" to "MAIN SETTINGS",
            "CARGAR MODELO" to "LOAD MODEL", "MODELO ACTIVO" to "MODEL ACTIVE",
            "¡Hola! ¿En qué te puedo ayudar?" to "Hello! How can I help you?",
            "Modelo: Ninguno" to "Model: None", "CONFIGURACIÓN DEL MODELO" to "MODEL SETTINGS",
            "CONTEXTO" to "CONTEXT", "TEMPERATURA" to "TEMPERATURE",
            "Guardar" to "Save", "Descargando..." to "Downloading..."
        ),
        "pt" to mapOf(
            "Pregúntame..." to "Pergunte-me...",
            "SERVICIOS Y CONTROL" to "SERVIÇOS E CONTROLE",
            "Grabar" to "Gravar", "Probar" to "Testar", "Usar" to "Usar", "Eliminar" to "Excluir",
            "¡Hola! ¿En qué te puedo ayudar?" to "Olá! Como posso ajudar?",
            "Descargando..." to "Baixando..."
        )
    )
}

