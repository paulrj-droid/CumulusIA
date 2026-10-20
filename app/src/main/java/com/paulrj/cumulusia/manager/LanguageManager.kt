package com.paulrj.cumulusia.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

object LanguageManager {
    private const val PREFS_NAME = "CumulusAppPermissions"
    private const val KEY_SELECTED_LANGUAGE = "selected_language_code"

    fun setLocale(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
        onLanguageChanged(context, languageCode)
        
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // Mejorado: Evita problemas con createConfigurationContext en versiones antiguas
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
        if (appContext == null) appContext = root.context.applicationContext
        translateTree(root, languageCode)
    }

    fun text(context: Context, spanishText: String): String {
        if (appContext == null) appContext = context.applicationContext
        return translate(getSelectedLanguage(context), spanishText) ?: spanishText
    }

    private fun translate(code: String, source: String): String? {
        if (code == "es") return source
        return translateText(code, source) ?: translations[code]?.get(source)
    }

    // ========================================================================
    // 🌐 TRADUCCIÓN DE TODA LA APP: pantalla principal, menús, ventanas emergentes, diálogos,
    //    paneles y avisos. Se traduce todo lo que se ve con el diccionario de
    //    assets/i18n/ui.json (español → inglés, portugués, ruso, chino, coreano y japonés).
    //    No se traduce lo que escribes tú, las respuestas de la IA ni el código.
    // ========================================================================
    private const val DICT_ASSET = "i18n/ui.json"
    @Volatile private var appContext: Context? = null
    @Volatile private var dictLoaded = false
    @Volatile private var currentLang: String? = null
    private val dictLock = Any()
    /** idioma → texto en español (tal cual) → traducción */
    private val exactDict = HashMap<String, HashMap<String, String>>()
    /** idioma → texto en español en minúsculas → traducción (para «GUARDAR API KEY» = «Guardar API Key») */
    private val lowerDict = HashMap<String, HashMap<String, String>>()
    /** idioma → frases con datos variables: «Modo: {0}», «Máximo {0} imágenes»… */
    private val patterns = HashMap<String, MutableList<Triple<Regex, List<Int>, String>>>()
    /** Resultados ya calculados (un texto se traduce una sola vez). "" = no tiene traducción. */
    private val resultCache = HashMap<String, String>()
    private val noTranslateViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val lastSetText = WeakHashMap<TextView, String>()
    private val lastSetHint = WeakHashMap<TextView, String>()

    /** Lo que nunca se traduce (por ejemplo un texto que el usuario escribió). */
    fun noTranslate(view: View) { noTranslateViews.add(view) }

    private fun currentLanguage(): String {
        currentLang?.let { return it }
        val c = appContext ?: return "es"
        return getSelectedLanguage(c).also { currentLang = it }
    }

    private fun onLanguageChanged(context: Context, code: String) {
        if (appContext == null) appContext = context.applicationContext
        currentLang = code
        synchronized(dictLock) { resultCache.clear() }
        lastSetText.clear(); lastSetHint.clear()
    }

    private fun normalize(s: String): String =
        s.trim().replace('…'.toString(), "...").replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex(" ?\\n ?"), "\n")

    private fun isUpperText(s: String): Boolean {
        var letters = 0
        for (ch in s) if (ch.isLetter()) { letters++; if (ch.isLowerCase()) return false }
        return letters >= 2 && s != s.lowercase(Locale.ROOT)
    }

    private fun isDecor(cp: Int): Boolean = !Character.isLetterOrDigit(cp) &&
        cp != '¿'.code && cp != '¡'.code && cp != '«'.code && cp != '»'.code && cp != '('.code && cp != ')'.code &&
        cp != '"'.code && cp != '\''.code && cp != '.'.code && cp != '?'.code && cp != '!'.code && cp != '%'.code && cp != '…'.code

    /** Separa adornos del principio y del final (emojis, flechas, «▴», espacios): (antes, centro, después). */
    private fun splitDecor(s: String): Triple<String, String, String> {
        var a = 0
        while (a < s.length) { val cp = s.codePointAt(a); if (!isDecor(cp)) break; a += Character.charCount(cp) }
        var b = s.length
        while (b > a) { val cp = s.codePointBefore(b); if (!isDecor(cp)) break; b -= Character.charCount(cp) }
        return Triple(s.substring(0, a), s.substring(a, b), s.substring(b))
    }

    private fun addEntry(lang: String, es: String, tr: String) {
        val key = normalize(es)
        val value = normalize(tr)
        if (key.isEmpty() || value.isEmpty()) return
        if (key.contains("{0}")) {
            // Frase con datos: se arma una expresión que reconoce el texto con cualquier dato en su lugar
            val parts = key.split(Regex("\\{(\\d)\\}"))
            val order = Regex("\\{(\\d)\\}").findAll(key).map { it.groupValues[1].toInt() }.toList()
            if (parts.joinToString("").count { it.isLetter() } < 4) return
            val rx = buildString {
                append('^')
                parts.forEachIndexed { i, p ->
                    append(Regex.escape(p))
                    if (i < parts.size - 1) append("(.+?)")
                }
                append('$')
            }
            runCatching { Regex(rx, RegexOption.DOT_MATCHES_ALL) }.getOrNull()?.let {
                patterns.getOrPut(lang) { mutableListOf() }.add(Triple(it, order, value))
            }
            return
        }
        exactDict.getOrPut(lang) { HashMap() }.putIfAbsent(key, value)
        lowerDict.getOrPut(lang) { HashMap() }.putIfAbsent(key.lowercase(Locale.ROOT), value)
        // También sin adornos: «🌙 Modo oscuro» enseña «Modo oscuro»
        val (_, coreEs, _) = splitDecor(key)
        val (_, coreTr, _) = splitDecor(value)
        if (coreEs != key && coreEs.isNotBlank() && coreTr.isNotBlank()) {
            exactDict.getValue(lang).putIfAbsent(coreEs, coreTr)
            lowerDict.getValue(lang).putIfAbsent(coreEs.lowercase(Locale.ROOT), coreTr)
        }
    }

    private fun ensureDict(ctx: Context?): Boolean {
        if (dictLoaded) return true
        synchronized(dictLock) {
            if (dictLoaded) return true
            val c = ctx ?: appContext ?: return false
            runCatching {
                val json = c.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = org.json.JSONObject(json)
                val langs = root.getJSONArray("langs")
                val s = root.getJSONObject("s")
                val keys = s.keys()
                while (keys.hasNext()) {
                    val es = keys.next()
                    val arr = s.getJSONArray(es)
                    for (i in 0 until langs.length()) {
                        val tr = arr.optString(i, "")
                        if (tr.isNotEmpty()) addEntry(langs.getString(i), es, tr)
                    }
                }
            }.onFailure { Log.e("LanguageManager", "No se pudo leer el diccionario: ${it.message}") }
            // Lo que ya estaba en la app también sirve
            translations.forEach { (lang, map) -> map.forEach { (es, tr) -> addEntry(lang, es, tr) } }
            patterns.values.forEach { list -> list.sortByDescending { it.first.pattern.length } }
            dictLoaded = true
            return true
        }
    }

    private fun lookup(lang: String, text: String): String? {
        val key = normalize(text)
        if (key.isEmpty()) return null
        exactDict[lang]?.get(key)?.let { return it }
        val tr = lowerDict[lang]?.get(key.lowercase(Locale.ROOT)) ?: return null
        val loc = Locale.forLanguageTag(lang)
        return when {
            isUpperText(key) -> tr.uppercase(loc)
            isUpperText(tr) -> tr.lowercase(loc).replaceFirstChar { it.titlecase(loc) }
            else -> tr
        }
    }

    private fun matchPattern(lang: String, text: String): String? {
        val key = normalize(text)
        val list = patterns[lang] ?: return null
        for ((rx, order, tpl) in list) {
            val m = rx.matchEntire(key) ?: continue
            var out = tpl
            order.forEachIndexed { i, n ->
                val v = m.groupValues[i + 1]
                out = out.replace("{$n}", lookup(lang, v) ?: v)
            }
            return if (isUpperText(key)) out.uppercase(Locale.forLanguageTag(lang)) else out
        }
        return null
    }

    /** Un trozo que no hace falta traducir: nombres de archivo, marcas, números (gemma.gguf, mmproj, Q4_0). */
    private fun isTokenLike(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || !t.any { it.isLetter() }) return true
        if (t.length > 40 || t.contains(' ')) return false
        return t.none { it in "áéíóúñÁÉÍÓÚÑ¿¡" }
    }

    private fun translateOne(lang: String, text: String): String? {
        // Primero sin adornos: así el emoji y sus espacios quedan tal cual
        val (pre, core, post) = splitDecor(text)
        if (core.isNotEmpty() && (pre.isNotEmpty() || post.isNotEmpty())) {
            lookup(lang, core)?.let { return pre + it + post }
        }
        lookup(lang, text)?.let { return it }
        matchPattern(lang, text)?.let { return it }
        if (core.isNotEmpty() && core != text) matchPattern(lang, core)?.let { return pre + it + post }
        return null
    }

    private val segmentSplit = Regex("(\\n| · | — | – | \\| |: )")

    /** Traduce un texto de la pantalla. null = no hay traducción (se deja como está). */
    fun translateText(lang: String, text: String): String? {
        if (lang == "es" || text.isBlank()) return null
        if (!ensureDict(null)) return null
        synchronized(dictLock) { resultCache[text]?.let { return it.ifEmpty { null } } }
        val lead = text.takeWhile { it.isWhitespace() }
        val trail = text.takeLastWhile { it.isWhitespace() }
        var result = translateOne(lang, text)
        if (result == null && text.length <= 300) {
            // Texto armado por partes: «Carga automática: ACTIVADA», «mmproj\nNinguno», «Modelo: x [CPU - Activo]»
            val pieces = mutableListOf<String>()
            var last = 0
            segmentSplit.findAll(text).forEach { m ->
                pieces += text.substring(last, m.range.first); pieces += m.value; last = m.range.last + 1
            }
            pieces += text.substring(last)
            if (pieces.size in 3..15) {
                var any = false
                var ok = true
                val out = StringBuilder()
                pieces.forEachIndexed { i, p ->
                    if (i % 2 == 1) { out.append(p); return@forEachIndexed }
                    val t = if (p.isBlank()) null else translateOne(lang, p)
                    when {
                        t != null -> { any = true; out.append(p.takeWhile { it.isWhitespace() }).append(t).append(p.takeLastWhile { it.isWhitespace() }) }
                        isTokenLike(p) -> out.append(p)
                        else -> ok = false
                    }
                }
                if (any && ok) result = out.toString()
            }
        }
        val final = result?.let { r -> if (r.startsWith(lead) && r.endsWith(trail)) r else lead + r.trim() + trail }
        synchronized(dictLock) {
            if (resultCache.size > 4000) resultCache.clear()
            resultCache[text] = final ?: ""
        }
        return final
    }

    /** Recorre una ventana, traduce sus textos y les pone los colores de las estrellas. Devuelve true si cambió algo. */
    private fun translateTree(root: View, lang: String = currentLanguage()): Boolean {
        val translate = lang != "es"   // en español no se traduce, pero igual se pintan los colores ✨
        var changed = false
        fun visit(v: View) {
            if (v in noTranslateViews) return
            if (v is android.webkit.WebView) return
            if (v is TextView) {
                // Lo que escribe el usuario y los mensajes del chat (texto que se puede seleccionar) no se tocan
                if (translate && v !is EditText && !v.isTextSelectable) {
                    val cs = v.text
                    val cur = cs?.toString().orEmpty()
                    if (cur.isNotEmpty() && lastSetText[v] != cur) {
                        val spans = if (cs is Spanned) cs.getSpans(0, cs.length, Any::class.java) else emptyArray()
                        if (spans.isEmpty()) {
                            translateText(lang, cur)?.let { out ->
                                if (out != cur) { v.text = out; changed = true }
                                lastSetText[v] = out
                            }
                        } else {
                            // Texto con estilo solo en el adorno del inicio (ej. «✔  Guardar» con el ✔ en negrita):
                            // se traduce la palabra y el adorno conserva su estilo
                            val pre = splitDecor(cur).first
                            val sp = cs as Spanned
                            if (pre.isNotEmpty() && spans.all { sp.getSpanEnd(it) <= pre.length }) {
                                translateText(lang, cur)?.let { out ->
                                    if (out != cur && out.startsWith(pre)) {
                                        v.text = android.text.SpannableStringBuilder(cs.subSequence(0, pre.length)).append(out.substring(pre.length))
                                        changed = true
                                    }
                                    lastSetText[v] = out
                                }
                            }
                        }
                    }
                }
                val h = v.hint
                if (translate && !h.isNullOrEmpty() && h !is Spanned) {
                    val cur = h.toString()
                    if (lastSetHint[v] != cur) {
                        translateText(lang, cur)?.let { out ->
                            if (out != cur) { v.hint = out; changed = true }
                            lastSetHint[v] = out
                        }
                    }
                }
            }
            // ✨ Después de traducir: lo blanco (textos e íconos) toma el color de las estrellas
            if (runCatching { StarText.apply(v) }.getOrDefault(false)) changed = true
            if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { visit(it) }
        }
        runCatching { visit(root) }.onFailure { Log.e("LanguageManager", "Error al traducir: ${it.message}") }
        return changed
    }

    // ---------- Vigilante: traduce cada ventana nueva (menús, diálogos, paneles) antes de dibujarse ----------
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val hooked = WeakHashMap<View, java.lang.ref.WeakReference<ViewTreeObserver>>()
    private val dirty = WeakHashMap<View, Boolean>()
    private var resumed = 0
    private var tickerOn = false
    private val ticker = object : Runnable {
        override fun run() {
            if (resumed <= 0) { tickerOn = false; return }
            scanWindows(full = true)
            mainHandler.postDelayed(this, 250)
        }
    }

    private fun hook(root: View) {
        val vto = root.viewTreeObserver
        if (!vto.isAlive || hooked[root]?.get() === vto) return
        hooked[root] = java.lang.ref.WeakReference(vto)
        vto.addOnGlobalLayoutListener { dirty[root] = true }
        var lastWalk = 0L
        var cancels = 0
        vto.addOnPreDrawListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (dirty[root] == true && now - lastWalk >= 40) {
                dirty[root] = false
                lastWalk = now
                // Si cambió algún texto, se salta este dibujo y se dibuja ya traducido (sin parpadeo)
                if (translateTree(root) && cancels < 2) { cancels++; return@addOnPreDrawListener false }
            }
            cancels = 0
            true
        }
        vto.addOnWindowFocusChangeListener { has -> if (!has) scanWindowsSoon() }
    }

    private fun scanWindows(full: Boolean) {
        val roots = runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrNull() ?: return
        for (r in roots) {
            val isNew = hooked[r]?.get() !== r.viewTreeObserver
            if (isNew) hook(r)
            if (isNew || full) translateTree(r)
        }
    }

    /** Revisa enseguida si se abrió un menú o una ventana (se llama al tocar la pantalla). */
    fun scanWindowsSoon() {
        mainHandler.post { scanWindows(full = false) }
    }

    /** Se llama una vez al abrir la app (CumulusApp). */
    fun install(app: Application) {
        appContext = app.applicationContext
        currentLang = getSelectedLanguage(app)
        if (currentLang != "es") Thread { ensureDict(app) }.start()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
                if (!tickerOn) { tickerOn = true; mainHandler.post(ticker) }
                scanWindowsSoon()
            }
            override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private val translations = mapOf(
        "en" to mapOf(
            "Pregúntame..." to "Ask me...",
            "SERVICIOS Y CONTROL" to "SERVICES AND CONTROL",
            "Activar Burbuja" to "Enable Floating Bubble",
            "Elegir imagen personalizada" to "Choose custom image",
            "Elegir voz TTS" to "Choose TTS voice",
            "Activar Accesibilidad" to "Enable Accessibility",
            "Dar permisos de apps" to "Grant app permissions",
            "Bloquear apps" to "Block apps",
            "Guardar API Key" to "Save API key",
            "Grabar" to "Record", "Probar" to "Test", "Usar" to "Use", "Eliminar" to "Delete",
            "CONFIGURACIÓN PRINCIPAL" to "MAIN SETTINGS",
            "Motor IA, personalidad, nombres e idiomas" to "AI engine, personality, names and languages",
            "MODELOS GGUF" to "GGUF MODELS", "VOCES TTS" to "TTS VOICES",
            "Nombre del modelo" to "Model name", "Cargar modelo" to "Load model",
            "CARGAR MODELO" to "LOAD MODEL", "MODELO ACTIVO" to "MODEL ACTIVE",
            "¡Hola! ¿En qué te puedo ayudar?" to "Hello! How can I help you?",
            "Modelo: Ninguno" to "Model: None", "CONFIGURACIÓN DEL MODELO" to "MODEL SETTINGS",
            "CONTEXTO" to "CONTEXT", "TEMPERATURA" to "TEMPERATURE",
            "PERSONALIDAD" to "PERSONALITY", "Asistente General" to "General Assistant",
            "Programador" to "Programmer", "Creativo" to "Creative",
            "Escribe tu personalidad..." to "Describe your personality...",
            "Guardar" to "Save", "CONFIGURACIÓN DE NOMBRES" to "NAME SETTINGS",
            "Nombre de la IA" to "AI name", "Guardar nombre" to "Save name",
            "Nombre del usuario" to "User name", "Guardar usuario" to "Save user",
            "IDIOMAS" to "LANGUAGES", "Descargando..." to "Downloading...",
            "Preparando descarga..." to "Preparing download...", "⏸ Pausar" to "⏸ Pause",
            "▶ Reanudar" to "▶ Resume", "🗑 Eliminar" to "🗑 Delete"
        ),
        "pt" to mapOf(
            "Pregúntame..." to "Pergunte-me...",
            "SERVICIOS Y CONTROL" to "SERVIÇOS E CONTROLE",
            "Activar Burbuja" to "Ativar bolha flutuante",
            "Elegir imagen personalizada" to "Escolher imagem personalizada",
            "Elegir voz TTS" to "Escolher voz TTS",
            "Activar Accesibilidad" to "Ativar acessibilidade",
            "Dar permisos de apps" to "Dar permissões aos aplicativos",
            "Bloquear apps" to "Bloquear aplicativos",
            "Guardar API Key" to "Salvar chave API",
            "Grabar" to "Gravar", "Probar" to "Testar", "Usar" to "Usar", "Eliminar" to "Excluir",
            "CONFIGURACIÓN PRINCIPAL" to "CONFIGURAÇÃO PRINCIPAL",
            "Motor IA, personalidad, nombres e idiomas" to "Motor de IA, personalidade, nomes e idiomas",
            "MODELOS GGUF" to "MODELOS GGUF", "VOCES TTS" to "VOZES TTS",
            "Nombre del modelo" to "Nome do modelo", "Cargar modelo" to "Carregar modelo",
            "CARGAR MODELO" to "CARREGAR MODELO", "MODELO ACTIVO" to "MODELO ATIVO",
            "¡Hola! ¿En qué te puedo ayudar?" to "Olá! Como posso ajudar?",
            "Modelo: Ninguno" to "Modelo: Nenhum", "CONFIGURACIÓN DEL MODELO" to "CONFIGURAÇÃO DO MODELO",
            "CONTEXTO" to "CONTEXTO", "TEMPERATURA" to "TEMPERATURA",
            "PERSONALIDAD" to "PERSONALIDADE", "Asistente General" to "Assistente geral",
            "Programador" to "Programador", "Creativo" to "Criativo",
            "Escribe tu personalidad..." to "Descreva sua personalidade...",
            "Guardar" to "Salvar", "CONFIGURACIÓN DE NOMBRES" to "CONFIGURAÇÃO DE NOMES",
            "Nombre de la IA" to "Nome da IA", "Guardar nombre" to "Salvar nome",
            "Nombre del usuario" to "Nome do usuário", "Guardar usuario" to "Salvar usuário",
            "IDIOMAS" to "IDIOMAS", "Descargando..." to "Baixando...",
            "Preparando descarga..." to "Preparando download...", "⏸ Pausar" to "⏸ Pausar",
            "▶ Reanudar" to "▶ Retomar", "🗑 Eliminar" to "🗑 Excluir"
        ),
        "ru" to mapOf(
            "Pregúntame..." to "Спросите меня...",
            "SERVICIOS Y CONTROL" to "СЕРВИСЫ И УПРАВЛЕНИЕ",
            "Activar Burbuja" to "Включить плавающий пузырь",
            "Elegir imagen personalizada" to "Выбрать изображение",
            "Elegir voz TTS" to "Выбрать голос TTS",
            "Activar Accesibilidad" to "Включить спец. возможности",
            "Dar permisos de apps" to "Разрешения приложений", "Bloquear apps" to "Блокировать приложения",
            "Guardar API Key" to "Сохранить API-ключ",
            "Grabar" to "Записать", "Probar" to "Проверить", "Usar" to "Использовать", "Eliminar" to "Удалить",
            "CONFIGURACIÓN PRINCIPAL" to "ОСНОВНЫЕ НАСТРОЙКИ",
            "Motor IA, personalidad, nombres e idiomas" to "ИИ, личность, имена и языки",
            "MODELOS GGUF" to "МОДЕЛИ GGUF", "VOCES TTS" to "ГОЛОСА TTS",
            "Nombre del modelo" to "Название модели", "Cargar modelo" to "Загрузить модель",
            "CARGAR MODELO" to "ЗАГРУЗИТЬ МОДЕЛЬ", "MODELO ACTIVO" to "МОДЕЛЬ АКТИВНА",
            "¡Hola! ¿En qué te puedo ayudar?" to "Здравствуйте! Чем я могу помочь?",
            "Modelo: Ninguno" to "Модель: нет", "CONFIGURACIÓN DEL MODELO" to "НАСТРОЙКИ МОДЕЛИ",
            "CONTEXTO" to "КОНТЕКСТ", "TEMPERATURA" to "ТЕМПЕРАТУРА",
            "PERSONALIDAD" to "ЛИЧНОСТЬ", "Asistente General" to "Общий помощник",
            "Programador" to "Программист", "Creativo" to "Творческий",
            "Escribe tu personalidad..." to "Опишите личность...",
            "Guardar" to "Сохранить", "CONFIGURACIÓN DE NOMBRES" to "НАСТРОЙКИ ИМЁН",
            "Nombre de la IA" to "Имя ИИ", "Guardar nombre" to "Сохранить имя",
            "Nombre del usuario" to "Имя пользователя", "Guardar usuario" to "Сохранить пользователя",
            "IDIOMAS" to "ЯЗЫКИ", "Descargando..." to "Загрузка...",
            "Preparando descarga..." to "Подготовка загрузки...", "⏸ Pausar" to "⏸ Пауза",
            "▶ Reanudar" to "▶ Продолжить", "🗑 Eliminar" to "🗑 Удалить"
        ),
        "zh" to mapOf(
            "Pregúntame..." to "问我吧...",
            "SERVICIOS Y CONTROL" to "服务与控制", "Activar Burbuja" to "启用悬浮窗",
            "Elegir imagen personalizada" to "选择自定义图片", "Elegir voz TTS" to "选择 TTS 语音",
            "Activar Accesibilidad" to "启用无障碍", "Dar permisos de apps" to "授予应用权限",
            "Bloquear apps" to "屏蔽应用", "Guardar API Key" to "保存 API 密钥",
            "Grabar" to "录制", "Probar" to "测试", "Usar" to "使用", "Eliminar" to "删除",
            "CONFIGURACIÓN PRINCIPAL" to "主要设置",
            "Motor IA, personalidad, nombres e idiomas" to "AI 引擎、个性、名称和语言",
            "MODELOS GGUF" to "GGUF 模型", "VOCES TTS" to "TTS 语音",
            "Nombre del modelo" to "模型名称", "Cargar modelo" to "加载模型",
            "CARGAR MODELO" to "加载模型", "MODELO ACTIVO" to "模型已启用",
            "¡Hola! ¿En qué te puedo ayudar?" to "你好！我能为你做什么？",
            "Modelo: Ninguno" to "模型：无", "CONFIGURACIÓN DEL MODELO" to "模型设置",
            "CONTEXTO" to "上下文", "TEMPERATURA" to "温度", "PERSONALIDAD" to "个性",
            "Asistente General" to "通用助手", "Programador" to "程序员", "Creativo" to "创意",
            "Escribe tu personalidad..." to "描述你的个性...", "Guardar" to "保存",
            "CONFIGURACIÓN DE NOMBRES" to "名称设置", "Nombre de la IA" to "AI 名称",
            "Guardar nombre" to "保存名称", "Nombre del usuario" to "用户名",
            "Guardar usuario" to "保存用户", "IDIOMAS" to "语言",
            "Descargando..." to "下载中...", "Preparando descarga..." to "准备下载...",
            "⏸ Pausar" to "⏸ 暂停", "▶ Reanudar" to "▶ 继续", "🗑 Eliminar" to "🗑 删除"
        ),
        "ko" to mapOf(
            "Pregúntame..." to "무엇이든 물어보세요...",
            "SERVICIOS Y CONTROL" to "서비스 및 제어", "Activar Burbuja" to "플로팅 버블 켜기",
            "Elegir imagen personalizada" to "사용자 이미지 선택", "Elegir voz TTS" to "TTS 음성 선택",
            "Activar Accesibilidad" to "접근성 켜기", "Dar permisos de apps" to "앱 권한 부여",
            "Bloquear apps" to "앱 차단", "Guardar API Key" to "API 키 저장",
            "Grabar" to "녹음", "Probar" to "테스트", "Usar" to "사용", "Eliminar" to "삭제",
            "CONFIGURACIÓN PRINCIPAL" to "기본 설정",
            "Motor IA, personalidad, nombres e idiomas" to "AI 엔진, 성격, 이름 및 언어",
            "MODELOS GGUF" to "GGUF 모델", "VOCES TTS" to "TTS 음성",
            "Nombre del modelo" to "모델 이름", "Cargar modelo" to "모델 로드",
            "CARGAR MODELO" to "모델 로드", "MODELO ACTIVO" to "모델 활성화됨",
            "¡Hola! ¿En qué te puedo ayudar?" to "안녕하세요! 무엇을 도와드릴까요?",
            "Modelo: Ninguno" to "모델: 없음", "CONFIGURACIÓN DEL MODELO" to "모델 설정",
            "CONTEXTO" to "컨텍스트", "TEMPERATURA" to "온도", "PERSONALIDAD" to "성격",
            "Asistente General" to "일반 도우미", "Programador" to "프로그래머", "Creativo" to "창의적",
            "Escribe tu personalidad..." to "성격을 입력하세요...", "Guardar" to "저장",
            "CONFIGURACIÓN DE NOMBRES" to "이름 설정", "Nombre de la IA" to "AI 이름",
            "Guardar nombre" to "이름 저장", "Nombre del usuario" to "사용자 이름",
            "Guardar usuario" to "사용자 저장", "IDIOMAS" to "언어",
            "Descargando..." to "다운로드 중...", "Preparando descarga..." to "다운로드 준비 중...",
            "⏸ Pausar" to "⏸ 일시정지", "▶ Reanudar" to "▶ 계속", "🗑 Eliminar" to "🗑 삭제"
        ),
        "ja" to mapOf(
            "Pregúntame..." to "質問してください...",
            "SERVICIOS Y CONTROL" to "サービスと制御", "Activar Burbuja" to "フローティングバブルを有効化",
            "Elegir imagen personalizada" to "画像を選択", "Elegir voz TTS" to "TTS音声を選択",
            "Activar Accesibilidad" to "ユーザー補助を有効化", "Dar permisos de apps" to "アプリ権限を許可",
            "Bloquear apps" to "アプリをブロック", "Guardar API Key" to "APIキーを保存",
            "Grabar" to "録音", "Probar" to "テスト", "Usar" to "使用", "Eliminar" to "削除",
            "CONFIGURACIÓN PRINCIPAL" to "メイン設定",
            "Motor IA, personalidad, nombres e idiomas" to "AIエンジン、性格、名前、言語",
            "MODELOS GGUF" to "GGUFモデル", "VOCES TTS" to "TTS音声",
            "Nombre del modelo" to "モデル名", "Cargar modelo" to "モデルを読み込む",
            "CARGAR MODELO" to "モデルを読み込む", "MODELO ACTIVO" to "モデル有効",
            "¡Hola! ¿En qué te puedo ayudar?" to "こんにちは！何をお手伝いできますか？",
            "Modelo: Ninguno" to "モデル：なし", "CONFIGURACIÓN DEL MODELO" to "モデル設定",
            "CONTEXTO" to "コンテキスト", "TEMPERATURA" to "温度", "PERSONALIDAD" to "性格",
            "Asistente General" to "一般アシスタント", "Programador" to "プログラマー", "Creativo" to "クリエイティブ",
            "Escribe tu personalidad..." to "性格を入力...", "Guardar" to "保存",
            "CONFIGURACIÓN DE NOMBRES" to "名前の設定", "Nombre de la IA" to "AIの名前",
            "Guardar nombre" to "名前を保存", "Nombre del usuario" to "ユーザー名",
            "Guardar usuario" to "ユーザーを保存", "IDIOMAS" to "言語",
            "Descargando..." to "ダウンロード中...", "Preparando descarga..." to "ダウンロードを準備中...",
            "⏸ Pausar" to "⏸ 一時停止", "▶ Reanudar" to "▶ 再開", "🗑 Eliminar" to "🗑 削除"
        )
    )
    
    fun getLocalizedText(languageCode: String, key: String): String {
        return when (languageCode) {
            "en" -> when (key) {
                "bubble" -> "Floating Bubble"
                "accessibility" -> "Accessibility"
                "authorized" -> "Authorized Apps"
                "blocked" -> "Blocked Apps"
                "es_button" -> "Spanish"
                "en_button" -> "English"
                "ru_button" -> "Russian"
                "zh_button" -> "Chinese"
                "ko_button" -> "Korean"
                "ja_button" -> "Japanese"
                "pt_button" -> "Portuguese"
                else -> key
            }
            "ru" -> when (key) {
                "bubble" -> "Плавающий пузырь"
                "accessibility" -> "Доступность"
                "authorized" -> "Авторизованные приложения"
                "blocked" -> "Заблокированные приложения"
                "es_button" -> "Испанский"
                "en_button" -> "Английский"
                "ru_button" -> "Русский"
                "zh_button" -> "Китайский"
                "ko_button" -> "Корейский"
                "ja_button" -> "Японский"
                "pt_button" -> "Португальский"
                else -> key
            }
            "zh" -> when (key) {
                "bubble" -> "悬浮窗"
                "accessibility" -> "无障碍"
                "authorized" -> "授权应用"
                "blocked" -> "已阻止的应用"
                "es_button" -> "西班牙语"
                "en_button" -> "英语"
                "ru_button" -> "俄语"
                "zh_button" -> "中文"
                "ko_button" -> "韩语"
                "ja_button" -> "日语"
                "pt_button" -> "葡萄牙语"
                else -> key
            }
            "ko" -> when (key) {
                "bubble" -> "플로팅 버블"
                "accessibility" -> "접근성"
                "authorized" -> "승인된 앱"
                "blocked" -> "차단된 앱"
                "es_button" -> "스페인어"
                "en_button" -> "영어"
                "ru_button" -> "러시아어"
                "zh_button" -> "중국어"
                "ko_button" -> "한국어"
                "ja_button" -> "일본어"
                "pt_button" -> "포르투갈어"
                else -> key
            }
            "ja" -> when (key) {
                "bubble" -> "フローティングバブル"
                "accessibility" -> "ユーザー補助"
                "authorized" -> "承認されたアプリ"
                "blocked" -> "ブロックされたアプリ"
                "es_button" -> "スペイン語"
                "en_button" -> "英語"
                "ru_button" -> "ロシア語"
                "zh_button" -> "中国語"
                "ko_button" -> "韓国語"
                "ja_button" -> "日本語"
                "pt_button" -> "ポルトガル語"
                else -> key
            }
            "pt" -> when (key) {
                "bubble" -> "Bolha Flutuante"
                "accessibility" -> "Acessibilidade"
                "authorized" -> "Aplicativos Autorizados"
                "blocked" -> "Aplicativos Bloqueados"
                "es_button" -> "Espanhol"
                "en_button" -> "Inglês"
                "ru_button" -> "Russo"
                "zh_button" -> "Chinês"
                "ko_button" -> "Coreano"
                "ja_button" -> "Japonês"
                "pt_button" -> "Português"
                else -> key
            }
            else -> when (key) {
                "bubble" -> "Burbuja Flotante"
                "accessibility" -> "Accesibilidad"
                "authorized" -> "Apps Autorizadas"
                "blocked" -> "Apps Bloqueadas"
                "es_button" -> "Español"
                "en_button" -> "Inglés"
                "ru_button" -> "Ruso"
                "zh_button" -> "Chino"
                "ko_button" -> "Coreano"
                "ja_button" -> "Japonés"
                "pt_button" -> "Portugués"
                else -> key
            }
        }
    }
}

