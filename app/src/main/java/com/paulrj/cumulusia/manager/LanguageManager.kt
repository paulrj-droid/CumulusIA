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
            // 🆕 Los textos nuevos (y el indonesio también con su código antiguo «in»)
            extraTranslations.forEach { (lang, list) ->
                list.forEachIndexed { i, tr ->
                    val es = extraKeys.getOrNull(i) ?: return@forEachIndexed
                    addEntry(lang, es, tr)
                    if (lang == "id") addEntry("in", es, tr)
                }
            }
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
        var final = result?.let { r -> if (r.startsWith(lead) && r.endsWith(trail)) r else lead + r.trim() + trail }
        // 🌍 No está en el diccionario: lo traduce el traductor de Google del teléfono (en segundo plano) y se recuerda.
        //    Mientras tanto se ve como está; apenas está listo, la pantalla se vuelve a revisar y sale en tu idioma.
        if (final == null) {
            val ml = mlFallback(lang, text)
            if (ml == null) return null   // (todavía no está: se vuelve a mirar en la próxima revisión, sin guardar «no tiene»)
            final = ml
        }
        synchronized(dictLock) {
            if (resultCache.size > 4000) resultCache.clear()
            resultCache[text] = final ?: ""
        }
        return final
    }

    // ---------- 🌍 Traductor del teléfono para los textos que NO están en el diccionario ----------
    //    (ventanas y avisos nuevos: cámara en vivo, reportes, proyectos, voz MP3… y los que vengan)
    private val mlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mlPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mlWorker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /** ¿Es un texto de la pantalla que vale la pena traducir? (no números que cambian, ni código, ni enlaces) */
    private fun mlWorth(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.length > 400 || t.count { it.isLetter() } < 2) return false
        if (t.count { it.isDigit() } > 3) return false                       // (CPU 3% · GPU 14% · RAM…: cambia a cada rato)
        if (Regex("https?://|www\\.|[{};=<>]|\\.(kt|xml|js|html|gguf|litertlm|onnx|apk)\\b").containsMatchIn(t)) return false
        return true
    }

    /** La traducción si ya está lista; si no, la pide (una sola vez) y devuelve null por ahora. */
    private fun mlFallback(lang: String, text: String): String? {
        if (lang == "es" || !mlWorth(text)) return null
        val key = "$lang|$text"
        mlCache[key]?.let { return it.ifEmpty { null } }
        if (mlPending.size < 400 && mlPending.add(key)) mlWorker.execute {
            val out = runCatching { com.paulrj.cumulusia.AppTranslate.spanishTo(text, lang) }.getOrDefault(text)
            mlCache[key] = if (out == text) "" else out
            mlPending.remove(key)
            if (out != text) mainHandler.post { scanWindows(full = true) }
        }
        return null
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
        runCatching { fixDirection(root) }
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
            // ↔️ Cada ventana (pantalla, menú ➕, ventanitas, paneles) en la dirección del idioma ELEGIDO
            runCatching { fixDirection(r) }
            val isNew = hooked[r]?.get() !== r.viewTreeObserver
            if (isNew) hook(r)
            if (isNew || full) translateTree(r)
        }
    }

    /**
     * ↔️ Árabe: de DERECHA a IZQUIERDA · los otros 13 idiomas: de IZQUIERDA a DERECHA.
     * Se pone en cada ventana según el idioma elegido ahora. Antes algunas ventanas se quedaban con la dirección
     * del idioma anterior: al pasar del árabe al español salían los botones al revés (y en árabe, al derecho).
     */
    private fun fixDirection(root: View) {
        val dir = android.text.TextUtils.getLayoutDirectionFromLocale(Locale(currentLanguage()))
        if (root.layoutDirection != dir) root.layoutDirection = dir
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

    // ========================================================================
    // 🆕 Textos nuevos de la app (botones, interruptores y ventanas agregados después del diccionario ui.json):
    //    TUS PROYECTOS DE APPS, ELIMINAR / ABRIR PROYECTO, VOZ MP3 y su aviso, EDITAR, Chat en pantalla,
    //    Editar personalidad y las opciones de Plantilla de chat. Van en los 13 idiomas que no son español.
    //    (van escritos normal: en un botón en mayúsculas, como «ELIMINAR PROYECTO», la traducción sale en mayúsculas sola)
    // ========================================================================
    private val extraKeys = listOf(
        "Tus proyectos de apps",
        "Eliminar proyecto",
        "Abrir proyecto",
        "Voz MP3",
        "Editar",
        "Chat en pantalla",
        "Editar personalidad",
        "Desliza con el dedo para ver todo el texto. Toca LISTO y después GUARDAR.",
        "Este botón es solo para subir tu propia voz en MP3, una voz autorizada o de dominio propio. Las voces que subas quedan bajo tu responsabilidad.",
        "Acepto",
        "Aceptar",
        "La del propio modelo (recomendada)",
        "La del propio GGUF (recomendada)",
        "Automática (el GGUF no trae plantilla)",
        "Automática: {0} (el GGUF no trae plantilla)",
        // 🚩 Reportar respuesta · 📂 Proyectos (Proyecto 1, 2, 3 y la pregunta antes de eliminar)
        "Reportar respuesta",
        "Ofensivo, odio o discriminación",
        "Sexual o inapropiado",
        "Violento o peligroso",
        "Información falsa o engañosa",
        "Otro motivo",
        "Enviar reporte",
        "Gracias por avisar",
        "Tu reporte nos ayuda a mejorar las respuestas de Cumulus IA. La banderita quedó en rojo para que sepas que ya lo reportaste.",
        "Proyecto {0}",
        "¿Quieres eliminar tu proyecto?\n\nSi lo eliminas no hay vuelta atrás: el proyecto se eliminará por completo.",
        // 💬 Chats guardados (al tocar un chat)
        "Guardado el {0}",
        "¿Qué quieres hacer con este chat?",
        // 🔄 Interruptor del menú ➕
        "Actualización de códigos y programación",
        // ▶ Probar una voz en «Voces guardadas»
        "▶ Probar",
        "⏹ Parar",
        // ➕ Ver el código que hiciste con otra IA
        "Ver tu app y página web",
        "Ver tu app Kotlin",
        "Avatar",
        "Imagen avatar",
        "Letras del chat en blanco",
        "Si las letras del chat te salen negras, aquí las puedes cambiar a blanco.",
        "Avatares guardados",
        "IA en la nube",
        "Usar IA en la nube",
        "Personalizado",
        "Dirección del servicio (URL)",
        "Clave (API key)",
        "Clave guardada",
        "Elegir modelo",
        "Modelo",
        "Escribir a mano"
    )

    private val extraTranslations: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "Your app projects",
            "Delete project",
            "Open project",
            "MP3 voice",
            "Edit",
            "On-screen chat",
            "Edit personality",
            "Swipe to see all the text. Tap DONE and then SAVE.",
            "This button is only for uploading your own voice as an MP3, an authorized voice or one you own the rights to. The voices you upload are your responsibility.",
            "I accept",
            "Accept",
            "The model's own (recommended)",
            "The GGUF's own (recommended)",
            "Automatic (the GGUF has no template)",
            "Automatic: {0} (the GGUF has no template)",
            "Report response",
            "Offensive, hate or discrimination",
            "Sexual or inappropriate",
            "Violent or dangerous",
            "False or misleading information",
            "Other reason",
            "Send report",
            "Thanks for letting us know",
            "Your report helps us improve Cumulus IA's responses. The little flag turned red so you know you already reported it.",
            "Project {0}",
            "Do you want to delete your project?\n\nIf you delete it, there's no going back: the project will be deleted completely.",
            "Saved on {0}",
            "What do you want to do with this chat?",
            "Code and programming updates",
            "▶ Try",
            "⏹ Stop",
            "View your web app and page",
            "View your Kotlin app",
            "Avatar",
            "Avatar image",
            "White chat text",
            "If the chat text looks black, you can make it white here.",
            "Saved avatars",
            "Cloud AI",
            "Use cloud AI",
            "Custom",
            "Service address (URL)",
            "Key (API key)",
            "Key saved",
            "Choose model",
            "Model",
            "Type it in"
        ),
        "ru" to listOf(
            "Ваши проекты приложений",
            "Удалить проект",
            "Открыть проект",
            "Голос MP3",
            "Изменить",
            "Чат на экране",
            "Изменить личность",
            "Проведите пальцем, чтобы увидеть весь текст. Нажмите ГОТОВО, а затем СОХРАНИТЬ.",
            "Эта кнопка только для загрузки вашего собственного голоса в MP3, разрешённого голоса или голоса, права на который принадлежат вам. Вы несёте ответственность за загруженные голоса.",
            "Я принимаю",
            "Принять",
            "Встроенный в модель (рекомендуется)",
            "Встроенный в GGUF (рекомендуется)",
            "Автоматически (в GGUF нет шаблона)",
            "Автоматически: {0} (в GGUF нет шаблона)",
            "Пожаловаться на ответ",
            "Оскорбление, ненависть или дискриминация",
            "Сексуальный или неприемлемый",
            "Насилие или опасность",
            "Ложная или вводящая в заблуждение информация",
            "Другая причина",
            "Отправить жалобу",
            "Спасибо, что сообщили",
            "Ваша жалоба помогает нам улучшать ответы Cumulus IA. Флажок стал красным, чтобы вы знали, что уже пожаловались.",
            "Проект {0}",
            "Удалить ваш проект?\n\nЕсли вы его удалите, вернуть его будет нельзя: проект будет удалён полностью.",
            "Сохранено {0}",
            "Что вы хотите сделать с этим чатом?",
            "Обновления кода и программирования",
            "▶ Прослушать",
            "⏹ Стоп",
            "Посмотреть веб-приложение и сайт",
            "Посмотреть приложение на Kotlin",
            "Аватар",
            "Изображение аватара",
            "Белый текст чата",
            "Если текст чата чёрный, здесь можно сделать его белым.",
            "Сохранённые аватары",
            "ИИ в облаке",
            "Использовать ИИ в облаке",
            "Свой",
            "Адрес сервиса (URL)",
            "Ключ (API key)",
            "Ключ сохранён",
            "Выбрать модель",
            "Модель",
            "Ввести вручную"
        ),
        "zh" to listOf(
            "你的应用项目",
            "删除项目",
            "打开项目",
            "MP3 语音",
            "编辑",
            "屏幕聊天",
            "编辑个性",
            "用手指滑动查看全部文字。点击“完成”，然后点击“保存”。",
            "此按钮仅用于上传你自己的 MP3 声音、经授权的声音或你拥有权利的声音。你上传的声音由你自行负责。",
            "我同意",
            "接受",
            "模型自带的（推荐）",
            "GGUF 自带的（推荐）",
            "自动（GGUF 不含模板）",
            "自动：{0}（GGUF 不含模板）",
            "举报回答",
            "冒犯、仇恨或歧视",
            "色情或不当内容",
            "暴力或危险",
            "虚假或误导性信息",
            "其他原因",
            "发送举报",
            "感谢你的反馈",
            "你的举报帮助我们改进 Cumulus IA 的回答。小旗子已变成红色，表示你已经举报过了。",
            "项目 {0}",
            "要删除你的项目吗？\n\n删除后无法恢复：项目将被彻底删除。",
            "保存于 {0}",
            "你想对这个聊天做什么？",
            "代码与编程更新",
            "▶ 试听",
            "⏹ 停止",
            "查看你的网页应用和网页",
            "查看你的 Kotlin 应用",
            "虚拟形象",
            "头像图片",
            "聊天文字显示为白色",
            "如果聊天文字显示为黑色，可以在这里改成白色。",
            "已保存的虚拟形象",
            "云端 AI",
            "使用云端 AI",
            "自定义",
            "服务地址 (URL)",
            "密钥 (API key)",
            "密钥已保存",
            "选择模型",
            "模型",
            "手动输入"
        ),
        "ko" to listOf(
            "내 앱 프로젝트",
            "프로젝트 삭제",
            "프로젝트 열기",
            "MP3 음성",
            "편집",
            "화면 채팅",
            "성격 편집",
            "손가락으로 밀어 전체 텍스트를 보세요. 완료를 누른 다음 저장을 누르세요.",
            "이 버튼은 본인의 MP3 음성, 허락받은 음성 또는 권리를 가진 음성을 올리는 용도로만 사용됩니다. 올린 음성에 대한 책임은 본인에게 있습니다.",
            "동의합니다",
            "확인",
            "모델 자체 템플릿 (권장)",
            "GGUF 자체 템플릿 (권장)",
            "자동 (GGUF에 템플릿 없음)",
            "자동: {0} (GGUF에 템플릿 없음)",
            "답변 신고",
            "불쾌감, 혐오 또는 차별",
            "성적이거나 부적절함",
            "폭력적이거나 위험함",
            "거짓이거나 오해의 소지가 있는 정보",
            "기타 사유",
            "신고 보내기",
            "알려 주셔서 감사합니다",
            "신고는 Cumulus IA의 답변을 개선하는 데 도움이 됩니다. 이미 신고했다는 것을 알 수 있도록 깃발이 빨간색으로 바뀌었어요.",
            "프로젝트 {0}",
            "프로젝트를 삭제할까요?\n\n삭제하면 되돌릴 수 없어요: 프로젝트가 완전히 삭제됩니다.",
            "{0}에 저장됨",
            "이 채팅으로 무엇을 할까요?",
            "코드 및 프로그래밍 업데이트",
            "▶ 미리 듣기",
            "⏹ 정지",
            "웹 앱과 웹페이지 보기",
            "Kotlin 앱 보기",
            "아바타",
            "아바타 이미지",
            "채팅 글자를 흰색으로",
            "채팅 글자가 검게 보이면 여기서 흰색으로 바꿀 수 있어요.",
            "저장된 아바타",
            "클라우드 AI",
            "클라우드 AI 사용",
            "사용자 지정",
            "서비스 주소 (URL)",
            "키 (API key)",
            "키 저장됨",
            "모델 선택",
            "모델",
            "직접 입력"
        ),
        "ja" to listOf(
            "あなたのアプリプロジェクト",
            "プロジェクトを削除",
            "プロジェクトを開く",
            "MP3音声",
            "編集",
            "画面チャット",
            "性格を編集",
            "指でスクロールして全文を表示します。「完了」をタップしてから「保存」をタップしてください。",
            "このボタンは、あなた自身の声のMP3、許可を得た声、または権利を持つ声をアップロードするためだけのものです。アップロードした声の責任はあなたにあります。",
            "同意します",
            "承諾",
            "モデル内蔵のもの（推奨）",
            "GGUF内蔵のもの（推奨）",
            "自動（GGUFにテンプレートなし）",
            "自動：{0}（GGUFにテンプレートなし）",
            "回答を報告",
            "攻撃的、憎悪または差別",
            "性的または不適切",
            "暴力的または危険",
            "虚偽または誤解を招く情報",
            "その他の理由",
            "報告を送信",
            "お知らせいただきありがとうございます",
            "報告はCumulus IAの回答の改善に役立ちます。報告済みであることがわかるように、旗が赤くなりました。",
            "プロジェクト {0}",
            "プロジェクトを削除しますか？\n\n削除すると元に戻せません：プロジェクトは完全に削除されます。",
            "{0} に保存",
            "このチャットをどうしますか？",
            "コードとプログラミングの更新",
            "▶ 試聴",
            "⏹ 停止",
            "Webアプリとページを見る",
            "Kotlinアプリを見る",
            "アバター",
            "アバター画像",
            "チャットの文字を白に",
            "チャットの文字が黒く表示される場合は、ここで白に変更できます。",
            "保存したアバター",
            "クラウドAI",
            "クラウドAIを使う",
            "カスタム",
            "サービスのアドレス (URL)",
            "キー (API key)",
            "キーを保存済み",
            "モデルを選ぶ",
            "モデル",
            "手入力"
        ),
        "pt" to listOf(
            "Seus projetos de apps",
            "Excluir projeto",
            "Abrir projeto",
            "Voz MP3",
            "Editar",
            "Chat na tela",
            "Editar personalidade",
            "Deslize com o dedo para ver todo o texto. Toque em CONCLUIR e depois em SALVAR.",
            "Este botão é apenas para enviar a sua própria voz em MP3, uma voz autorizada ou de sua propriedade. As vozes que você enviar são de sua responsabilidade.",
            "Aceito",
            "Aceitar",
            "A do próprio modelo (recomendada)",
            "A do próprio GGUF (recomendada)",
            "Automática (o GGUF não traz modelo)",
            "Automática: {0} (o GGUF não traz modelo)",
            "Denunciar resposta",
            "Ofensivo, ódio ou discriminação",
            "Sexual ou inapropriado",
            "Violento ou perigoso",
            "Informação falsa ou enganosa",
            "Outro motivo",
            "Enviar denúncia",
            "Obrigado por avisar",
            "Sua denúncia nos ajuda a melhorar as respostas da Cumulus IA. A bandeirinha ficou vermelha para você saber que já denunciou.",
            "Projeto {0}",
            "Quer excluir seu projeto?\n\nSe excluir, não tem volta: o projeto será excluído por completo.",
            "Salvo em {0}",
            "O que você quer fazer com este chat?",
            "Atualização de códigos e programação",
            "▶ Testar",
            "⏹ Parar",
            "Ver seu app e página web",
            "Ver seu app Kotlin",
            "Avatar",
            "Imagem do avatar",
            "Letras do chat em branco",
            "Se as letras do chat aparecerem pretas, aqui você pode deixá-las brancas.",
            "Avatares salvos",
            "IA na nuvem",
            "Usar IA na nuvem",
            "Personalizado",
            "Endereço do serviço (URL)",
            "Chave (API key)",
            "Chave salva",
            "Escolher modelo",
            "Modelo",
            "Digitar à mão"
        ),
        "de" to listOf(
            "Deine App-Projekte",
            "Projekt löschen",
            "Projekt öffnen",
            "MP3-Stimme",
            "Bearbeiten",
            "Chat auf dem Bildschirm",
            "Persönlichkeit bearbeiten",
            "Wische, um den ganzen Text zu sehen. Tippe auf FERTIG und dann auf SPEICHERN.",
            "Diese Schaltfläche dient nur zum Hochladen deiner eigenen Stimme als MP3, einer autorisierten Stimme oder einer Stimme, an der du die Rechte hast. Du bist für die hochgeladenen Stimmen verantwortlich.",
            "Ich stimme zu",
            "Akzeptieren",
            "Die des Modells (empfohlen)",
            "Die der GGUF-Datei (empfohlen)",
            "Automatisch (die GGUF-Datei hat keine Vorlage)",
            "Automatisch: {0} (die GGUF-Datei hat keine Vorlage)",
            "Antwort melden",
            "Beleidigend, Hass oder Diskriminierung",
            "Sexuell oder unangemessen",
            "Gewalttätig oder gefährlich",
            "Falsche oder irreführende Information",
            "Anderer Grund",
            "Meldung senden",
            "Danke für den Hinweis",
            "Deine Meldung hilft uns, die Antworten von Cumulus IA zu verbessern. Das Fähnchen ist rot geworden, damit du weißt, dass du es schon gemeldet hast.",
            "Projekt {0}",
            "Möchtest du dein Projekt löschen?\n\nWenn du es löschst, gibt es kein Zurück: Das Projekt wird vollständig gelöscht.",
            "Gespeichert am {0}",
            "Was möchtest du mit diesem Chat machen?",
            "Code- und Programmier-Updates",
            "▶ Anhören",
            "⏹ Stopp",
            "Deine Web-App und Webseite ansehen",
            "Deine Kotlin-App ansehen",
            "Avatar",
            "Avatar-Bild",
            "Chat-Text in Weiß",
            "Wenn der Chat-Text schwarz erscheint, kannst du ihn hier weiß machen.",
            "Gespeicherte Avatare",
            "KI in der Cloud",
            "KI in der Cloud verwenden",
            "Benutzerdefiniert",
            "Adresse des Dienstes (URL)",
            "Schlüssel (API key)",
            "Schlüssel gespeichert",
            "Modell wählen",
            "Modell",
            "Von Hand eingeben"
        ),
        "fr" to listOf(
            "Tes projets d'apps",
            "Supprimer le projet",
            "Ouvrir le projet",
            "Voix MP3",
            "Modifier",
            "Chat à l'écran",
            "Modifier la personnalité",
            "Fais glisser ton doigt pour voir tout le texte. Touche TERMINÉ puis ENREGISTRER.",
            "Ce bouton sert uniquement à envoyer ta propre voix en MP3, une voix autorisée ou dont tu possèdes les droits. Les voix que tu envoies sont sous ta responsabilité.",
            "J'accepte",
            "Accepter",
            "Celui du modèle (recommandé)",
            "Celui du GGUF (recommandé)",
            "Automatique (le GGUF n'a pas de modèle de chat)",
            "Automatique : {0} (le GGUF n'a pas de modèle de chat)",
            "Signaler la réponse",
            "Offensant, haine ou discrimination",
            "Sexuel ou inapproprié",
            "Violent ou dangereux",
            "Information fausse ou trompeuse",
            "Autre motif",
            "Envoyer le signalement",
            "Merci de nous avoir prévenus",
            "Ton signalement nous aide à améliorer les réponses de Cumulus IA. Le petit drapeau est devenu rouge pour que tu saches que tu l'as déjà signalé.",
            "Projet {0}",
            "Veux-tu supprimer ton projet ?\n\nSi tu le supprimes, impossible de revenir en arrière : le projet sera supprimé complètement.",
            "Enregistré le {0}",
            "Que veux-tu faire de ce chat ?",
            "Mise à jour du code et de la programmation",
            "▶ Écouter",
            "⏹ Arrêter",
            "Voir ton app et ta page web",
            "Voir ton app Kotlin",
            "Avatar",
            "Image de l'avatar",
            "Texte du chat en blanc",
            "Si le texte du chat apparaît en noir, tu peux le passer en blanc ici.",
            "Avatars enregistrés",
            "IA dans le cloud",
            "Utiliser l'IA dans le cloud",
            "Personnalisé",
            "Adresse du service (URL)",
            "Clé (API key)",
            "Clé enregistrée",
            "Choisir le modèle",
            "Modèle",
            "Saisir à la main"
        ),
        "it" to listOf(
            "I tuoi progetti di app",
            "Elimina progetto",
            "Apri progetto",
            "Voce MP3",
            "Modifica",
            "Chat sullo schermo",
            "Modifica personalità",
            "Scorri con il dito per vedere tutto il testo. Tocca FATTO e poi SALVA.",
            "Questo pulsante serve solo per caricare la tua voce in MP3, una voce autorizzata o di cui possiedi i diritti. Le voci che carichi sono sotto la tua responsabilità.",
            "Accetto",
            "Accetta",
            "Quello del modello (consigliato)",
            "Quello del GGUF (consigliato)",
            "Automatico (il GGUF non ha un modello di chat)",
            "Automatico: {0} (il GGUF non ha un modello di chat)",
            "Segnala risposta",
            "Offensivo, odio o discriminazione",
            "Sessuale o inappropriato",
            "Violento o pericoloso",
            "Informazione falsa o ingannevole",
            "Altro motivo",
            "Invia segnalazione",
            "Grazie per averci avvisato",
            "La tua segnalazione ci aiuta a migliorare le risposte di Cumulus IA. La bandierina è diventata rossa così sai che l'hai già segnalata.",
            "Progetto {0}",
            "Vuoi eliminare il tuo progetto?\n\nSe lo elimini non si torna indietro: il progetto verrà eliminato completamente.",
            "Salvata il {0}",
            "Cosa vuoi fare con questa chat?",
            "Aggiornamento di codice e programmazione",
            "▶ Ascolta",
            "⏹ Ferma",
            "Vedi la tua app e pagina web",
            "Vedi la tua app Kotlin",
            "Avatar",
            "Immagine avatar",
            "Testo della chat in bianco",
            "Se il testo della chat appare nero, qui puoi renderlo bianco.",
            "Avatar salvati",
            "IA nel cloud",
            "Usa l'IA nel cloud",
            "Personalizzato",
            "Indirizzo del servizio (URL)",
            "Chiave (API key)",
            "Chiave salvata",
            "Scegli modello",
            "Modello",
            "Scrivi a mano"
        ),
        "id" to listOf(
            "Proyek aplikasimu",
            "Hapus proyek",
            "Buka proyek",
            "Suara MP3",
            "Edit",
            "Obrolan di layar",
            "Edit kepribadian",
            "Geser dengan jari untuk melihat seluruh teks. Ketuk SELESAI lalu SIMPAN.",
            "Tombol ini hanya untuk mengunggah suaramu sendiri dalam MP3, suara yang diizinkan, atau suara yang haknya kamu miliki. Suara yang kamu unggah menjadi tanggung jawabmu.",
            "Saya setuju",
            "Terima",
            "Bawaan model (disarankan)",
            "Bawaan GGUF (disarankan)",
            "Otomatis (GGUF tidak memiliki templat)",
            "Otomatis: {0} (GGUF tidak memiliki templat)",
            "Laporkan jawaban",
            "Menyinggung, kebencian, atau diskriminasi",
            "Seksual atau tidak pantas",
            "Kekerasan atau berbahaya",
            "Informasi palsu atau menyesatkan",
            "Alasan lain",
            "Kirim laporan",
            "Terima kasih sudah memberi tahu",
            "Laporanmu membantu kami memperbaiki jawaban Cumulus IA. Bendera kecil sudah berubah merah agar kamu tahu sudah melaporkannya.",
            "Proyek {0}",
            "Ingin menghapus proyekmu?\n\nJika dihapus, tidak bisa dikembalikan: proyek akan dihapus seluruhnya.",
            "Disimpan pada {0}",
            "Apa yang ingin kamu lakukan dengan obrolan ini?",
            "Pembaruan kode dan pemrograman",
            "▶ Coba",
            "⏹ Berhenti",
            "Lihat aplikasi dan halaman webmu",
            "Lihat aplikasi Kotlin-mu",
            "Avatar",
            "Gambar avatar",
            "Teks obrolan putih",
            "Jika teks obrolan tampak hitam, kamu bisa mengubahnya menjadi putih di sini.",
            "Avatar tersimpan",
            "AI di cloud",
            "Gunakan AI di cloud",
            "Kustom",
            "Alamat layanan (URL)",
            "Kunci (API key)",
            "Kunci tersimpan",
            "Pilih model",
            "Model",
            "Ketik manual"
        ),
        "vi" to listOf(
            "Dự án ứng dụng của bạn",
            "Xóa dự án",
            "Mở dự án",
            "Giọng MP3",
            "Chỉnh sửa",
            "Trò chuyện trên màn hình",
            "Chỉnh sửa tính cách",
            "Vuốt để xem toàn bộ văn bản. Chạm XONG rồi LƯU.",
            "Nút này chỉ dùng để tải lên giọng nói MP3 của chính bạn, giọng nói được cho phép hoặc giọng nói bạn có quyền sở hữu. Bạn chịu trách nhiệm về các giọng nói đã tải lên.",
            "Tôi đồng ý",
            "Chấp nhận",
            "Mẫu riêng của mô hình (khuyên dùng)",
            "Mẫu riêng của GGUF (khuyên dùng)",
            "Tự động (GGUF không có mẫu)",
            "Tự động: {0} (GGUF không có mẫu)",
            "Báo cáo câu trả lời",
            "Xúc phạm, thù ghét hoặc phân biệt đối xử",
            "Tình dục hoặc không phù hợp",
            "Bạo lực hoặc nguy hiểm",
            "Thông tin sai hoặc gây hiểu lầm",
            "Lý do khác",
            "Gửi báo cáo",
            "Cảm ơn bạn đã báo",
            "Báo cáo của bạn giúp chúng tôi cải thiện câu trả lời của Cumulus IA. Lá cờ nhỏ đã chuyển sang màu đỏ để bạn biết mình đã báo cáo.",
            "Dự án {0}",
            "Bạn muốn xóa dự án của mình?\n\nNếu xóa thì không thể khôi phục: dự án sẽ bị xóa hoàn toàn.",
            "Đã lưu lúc {0}",
            "Bạn muốn làm gì với cuộc trò chuyện này?",
            "Cập nhật mã và lập trình",
            "▶ Nghe thử",
            "⏹ Dừng",
            "Xem ứng dụng và trang web của bạn",
            "Xem ứng dụng Kotlin của bạn",
            "Hình đại diện",
            "Ảnh đại diện AI",
            "Chữ trò chuyện màu trắng",
            "Nếu chữ trò chuyện hiển thị màu đen, bạn có thể đổi sang màu trắng tại đây.",
            "Hình đại diện đã lưu",
            "AI đám mây",
            "Dùng AI đám mây",
            "Tùy chỉnh",
            "Địa chỉ dịch vụ (URL)",
            "Khóa (API key)",
            "Đã lưu khóa",
            "Chọn mô hình",
            "Mô hình",
            "Nhập thủ công"
        ),
        "th" to listOf(
            "โปรเจกต์แอปของคุณ",
            "ลบโปรเจกต์",
            "เปิดโปรเจกต์",
            "เสียง MP3",
            "แก้ไข",
            "แชทบนหน้าจอ",
            "แก้ไขบุคลิก",
            "เลื่อนนิ้วเพื่อดูข้อความทั้งหมด แตะ เสร็จสิ้น แล้วแตะ บันทึก",
            "ปุ่มนี้ใช้สำหรับอัปโหลดเสียงของคุณเองเป็น MP3 เสียงที่ได้รับอนุญาต หรือเสียงที่คุณเป็นเจ้าของสิทธิ์เท่านั้น คุณต้องรับผิดชอบต่อเสียงที่อัปโหลด",
            "ฉันยอมรับ",
            "ยอมรับ",
            "เทมเพลตของโมเดลเอง (แนะนำ)",
            "เทมเพลตของ GGUF เอง (แนะนำ)",
            "อัตโนมัติ (GGUF ไม่มีเทมเพลต)",
            "อัตโนมัติ: {0} (GGUF ไม่มีเทมเพลต)",
            "รายงานคำตอบ",
            "ไม่เหมาะสม ความเกลียดชัง หรือการเลือกปฏิบัติ",
            "เรื่องทางเพศหรือไม่เหมาะสม",
            "ความรุนแรงหรืออันตราย",
            "ข้อมูลเท็จหรือทำให้เข้าใจผิด",
            "เหตุผลอื่น",
            "ส่งรายงาน",
            "ขอบคุณที่แจ้งให้ทราบ",
            "รายงานของคุณช่วยให้เราปรับปรุงคำตอบของ Cumulus IA ธงเล็กเปลี่ยนเป็นสีแดงแล้วเพื่อให้คุณรู้ว่ารายงานไปแล้ว",
            "โปรเจกต์ {0}",
            "ต้องการลบโปรเจกต์ของคุณหรือไม่?\n\nถ้าลบแล้วจะกู้คืนไม่ได้: โปรเจกต์จะถูกลบทั้งหมด",
            "บันทึกเมื่อ {0}",
            "คุณต้องการทำอะไรกับแชทนี้?",
            "อัปเดตโค้ดและการเขียนโปรแกรม",
            "▶ ลองฟัง",
            "⏹ หยุด",
            "ดูแอปและหน้าเว็บของคุณ",
            "ดูแอป Kotlin ของคุณ",
            "อวาตาร์",
            "รูปอวาตาร์",
            "ตัวอักษรแชทสีขาว",
            "หากตัวอักษรแชทเป็นสีดำ คุณเปลี่ยนเป็นสีขาวได้ที่นี่",
            "อวาตาร์ที่บันทึกไว้",
            "AI บนคลาวด์",
            "ใช้ AI บนคลาวด์",
            "กำหนดเอง",
            "ที่อยู่บริการ (URL)",
            "คีย์ (API key)",
            "บันทึกคีย์แล้ว",
            "เลือกโมเดล",
            "โมเดล",
            "พิมพ์เอง"
        ),
        "ar" to listOf(
            "مشاريع تطبيقاتك",
            "حذف المشروع",
            "فتح المشروع",
            "صوت MP3",
            "تعديل",
            "الدردشة على الشاشة",
            "تعديل الشخصية",
            "اسحب بإصبعك لرؤية النص كاملًا. اضغط تم ثم حفظ.",
            "هذا الزر مخصص فقط لرفع صوتك أنت بصيغة MP3، أو صوت مُصرَّح به، أو صوت تملك حقوقه. أنت مسؤول عن الأصوات التي ترفعها.",
            "أوافق",
            "قبول",
            "قالب النموذج نفسه (موصى به)",
            "قالب GGUF نفسه (موصى به)",
            "تلقائي (لا يحتوي GGUF على قالب)",
            "تلقائي: {0} (لا يحتوي GGUF على قالب)",
            "الإبلاغ عن الإجابة",
            "مسيء أو كراهية أو تمييز",
            "جنسي أو غير لائق",
            "عنيف أو خطير",
            "معلومات خاطئة أو مضللة",
            "سبب آخر",
            "إرسال البلاغ",
            "شكرًا لإبلاغنا",
            "بلاغك يساعدنا على تحسين إجابات Cumulus IA. أصبح العلم الصغير أحمر حتى تعرف أنك أبلغت عنه بالفعل.",
            "المشروع {0}",
            "هل تريد حذف مشروعك؟\n\nإذا حذفته فلا يمكن التراجع: سيُحذف المشروع بالكامل.",
            "حُفظت في {0}",
            "ماذا تريد أن تفعل بهذه الدردشة؟",
            "تحديث الأكواد والبرمجة",
            "▶ استمع",
            "⏹ إيقاف",
            "اعرض تطبيقك وصفحة الويب",
            "اعرض تطبيق Kotlin الخاص بك",
            "الصورة الرمزية",
            "صورة الأفاتار",
            "نص الدردشة باللون الأبيض",
            "إذا ظهر نص الدردشة باللون الأسود، يمكنك تغييره إلى الأبيض هنا.",
            "الصور الرمزية المحفوظة",
            "الذكاء الاصطناعي السحابي",
            "استخدام الذكاء الاصطناعي السحابي",
            "مخصص",
            "عنوان الخدمة (URL)",
            "المفتاح (API key)",
            "تم حفظ المفتاح",
            "اختيار النموذج",
            "النموذج",
            "الكتابة يدويًا"
        ),
    )

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
