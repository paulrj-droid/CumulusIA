package com.paulrj.cumulusia

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader

/**
 * 🖥️ Panel de Vista Previa (estilo Canvas / Artifacts / Web Preview).
 *
 *  • Vista previa web: junta el HTML + CSS + JavaScript que escribió la IA en el chat y lo
 *    ejecuta de verdad en una ventana (puedes tocar botones, escribir, jugar…).
 *  • Maqueta de la app: convierte los diseños XML de una app Android (activity_main.xml, etc.)
 *    en una maqueta visual navegable. La lógica Kotlin solo funciona al compilar la app.
 *
 * Todo ocurre dentro del teléfono: no se sube nada a internet.
 */
object PreviewPanel {

    // ------------------------------------------------------------------ 🌍 idioma de la app
    // Con otro idioma elegido en la app, la ventana y la maqueta se ven en ese idioma: los botones, la barra de escritura,
    // los interruptores, los menús, los avisos… La primera vez se ve en español un momento y al tiro cambia a tu idioma
    // (después sale traducida al instante, porque se recuerda). Lo que ya está en tu idioma queda igual.
    @Volatile private var uiLang = "es"

    /**
     * 🖼️ Imágenes de tu proyecto (res/drawable, res/mipmap): nombre → (tipo, contenido). Las pone «Ver tu app» al leer
     * tu .zip o tus archivos. La maqueta las pide por su dirección (…/__res/nombre) y Cumulus las entrega UNA vez cada
     * una: antes iban copiadas dentro de la página cada vez que se usaban y la maqueta quedaba pesada y lenta.
     */
    @Volatile var projectAssets: Map<String, Pair<String, ByteArray>> = emptyMap()
    /** 🔤 Fuentes de tu proyecto (res/font, .ttf/.otf): nombre → (tipo, contenido). Se piden por …/__font/nombre. */
    @Volatile var projectFonts: Map<String, Pair<String, ByteArray>> = emptyMap()
    private fun assetUrl(name: String) = "https://preview.cumulus.local/__res/$name"
    private fun fontUrl(name: String) = "https://preview.cumulus.local/__font/$name"
    /**
     * 🌐 Tu página o app web completa, como en tu hosting: ruta (index.html, img/logo.png, css/estilos.css, datos.json…)
     * → (tipo, contenido). La vista previa los pide a https://preview.cumulus.local/… y Cumulus se los entrega, así
     * funcionan tus imágenes, tus fuentes, tus datos y los enlaces entre páginas. Lo que no venga sale roto, como en
     * internet: así ves qué le falta a tu página. [webStart]: la página con que se abre (index.html).
     */
    @Volatile var webFiles: Map<String, Pair<String, ByteArray>> = emptyMap()
    @Volatile var webStart: String = "index.html"
    private const val WEB_HOST = "preview.cumulus.local"

    /** «css/estilos.css» pedido desde «paginas/nosotros.html» → «paginas/css/estilos.css» (con ../ y /). */
    private fun webResolve(from: String, rel: String): String {
        val r = rel.substringBefore('#').substringBefore('?')
        val parts = (if (r.startsWith("/")) r.removePrefix("/") else from.substringBeforeLast('/', "").let { if (it.isEmpty()) r else "$it/$r" })
            .split('/')
        val out = ArrayDeque<String>()
        for (p in parts) when (p) { "", "." -> {}; ".." -> if (out.isNotEmpty()) out.removeLast(); else -> out.addLast(p) }
        return out.joinToString("/")
    }

    /** 🌙 El teléfono está en modo oscuro (las apps «DayNight» se ven como el teléfono, igual que en la app real). */
    @Volatile var phoneNight = true
    /** ☀️ Cumulus en modo claro: la ventana de la maqueta (barra de arriba, pestañas, avisos, «Cargando…») va clara. */
    @Volatile var uiLight = false
    /** El color de la ventana según el modo de Cumulus: [dark] en modo oscuro, [light] en modo claro. */
    private fun chrome(dark: String, light: String) = if (uiLight) light else dark
    @Volatile private var onTranslated: (() -> Unit)? = null
    private val trCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val trWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val trMain = android.os.Handler(android.os.Looper.getMainLooper())
    private val trRefresh = Runnable { runCatching { onTranslated?.invoke() } }

    private fun spanishUi() = uiLang.substringBefore('-').lowercase(java.util.Locale.ROOT) == "es"

    /** Lo ya traducido sale al tiro; si no, se traduce en segundo plano y la ventana se actualiza sola. */
    private fun later(key: String, original: String, job: () -> String): String {
        trCache[key]?.let { return it }
        if (trPending.add(key)) trWorker.execute {
            val out = runCatching { job() }.getOrDefault(original)
            if (trCache.size > 4000) trCache.clear()
            trCache[key] = out
            trPending.remove(key)
            if (out != original) { trMain.removeCallbacks(trRefresh); trMain.postDelayed(trRefresh, 350) }
        }
        return original
    }

    /**
     * 🔥 Al abrir la app con otro idioma: los textos propios de la maqueta y de la vista previa se traducen de antemano,
     * así al abrirlas ya salen en tu idioma (sin el momento en español). Se llama desde un hilo de fondo.
     */
    fun prewarm(code: String) {
        uiLang = code
        if (spanishUi()) return
        listOf("📱 Maqueta de la app", "🖥️ Vista previa web", "Vista", "Código", "Otra IA", "armándose…", "(sin código todavía)",
            "✦ La IA está escribiendo el código de tu app…", "Aquí vas a ver cómo se va armando; esta ventana se actualiza sola.",
            "📂 Abriste:", "✅ Activado", "⬜ Desactivado", "✨ Nuevo elemento", "＋ Agregar", "✍️ Escribe algo primero", "Botón",
            "⚠️ La lógica de la maqueta tuvo un error:", CODE_NOTE, "Mi app",
            WEB_ASKS, "la cámara", "el micrófono", "tu ubicación", "Permitir", "No permitir", WEB_NOTES, WEB_MISSING, WEB_OFFLINE, WEB_SERVER, "Cargando tu app…", "Escribe aquí", "Texto", "Elemento", "Opción", "Inicio", "Buscar", "Perfil",
            "No pude dibujar este diseño (el XML tiene un error):", "✂️ Este diseño llegó cortado: completé lo que faltaba para poder mostrarlo.",
            "📱 Maqueta con la lógica de tu app (simulada en JavaScript). La app real usa el Kotlin al compilarla.",
            "📱 Maqueta interactiva: menús, botones y listas responden como ejemplo. La lógica Kotlin real funciona al compilar la app.")
            .forEach { tx(it) }
    }

    /** ⚠️ Aviso cuando tu Kotlin cambia el diseño al abrirse y la maqueta no alcanzó a imitarlo todo. */
    private const val CODE_NOTE = "⚠️ Tu app cambia colores, formas o textos desde el código al abrirse: la maqueta imita lo más común; el resultado final se ve al compilar."

    // 🌐 Textos de la app web y página web: permisos (cámara, micrófono, ubicación) y avisos de lo que no cargó
    private const val WEB_ASKS = "🌐 Tu app web pide usar:"
    private const val WEB_NOTES = "⚠️ Avisos de tu app web (toca para ver)"
    private const val WEB_MISSING = "📄 Falta este archivo en tu proyecto:"
    private const val WEB_OFFLINE = "🌐 No cargó desde internet (¿sin internet o link roto?):"
    private const val WEB_SERVER = "🔌 Esta parte necesita el servidor de tu app (no corre en el teléfono):"

    /** Texto propio de esta ventana (escrito en español) → idioma de la app. */
    private fun tx(es: String): String {
        if (spanishUi() || es.none { it.isLetter() }) return es
        val lang = uiLang
        return later("a|$lang|$es", es) { AppTranslate.spanishTo(es, lang, assumeSpanish = true) }
    }

    /** Mensaje con etiquetas (<br>, <b>): se traduce cada pedazo de texto y las etiquetas quedan igual. */
    private fun txHtml(html: String): String {
        if (spanishUi()) return html
        return Regex("<[^>]+>|[^<]+").findAll(html).joinToString("") { m ->
            val v = m.value
            if (v.startsWith("<") || v.isBlank()) v
            else v.takeWhile { it.isWhitespace() } + tx(v.trim()) + v.takeLastWhile { it.isWhitespace() }
        }
    }

    /** Texto para meterlo en el JavaScript de la maqueta entre comillas simples. */
    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("</", "<\\/") + "'"

    /** [path]: la ruta completa que dio el proyecto («📍 Ruta completa: MiApp/app/src/…»), sin la carpeta principal. */
    private data class CodeBlock(val lang: String, val code: String, val fileName: String?, val path: String? = null)

    // ------------------------------------------------------------------ bloques de código del chat
    private fun codeBlocks(text: String): List<CodeBlock> {
        val out = mutableListOf<CodeBlock>()
        // (``` o más comillas: un archivo que trae ``` por dentro, como el código de Cumulus, va entre ```` y no se corta)
        Regex("(`{3,})([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)\\1(?!`)", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val before = text.substring(maxOf(0, m.range.first - 300), m.range.first)
            val name = Regex("([\\w.-]+\\.(xml|html?|css|js|kt|java|gradle))", RegexOption.IGNORE_CASE)
                .findAll(before).lastOrNull()?.value
            // 📍 Proyectos de Cumulus: cada archivo trae su ruta completa justo antes del código (en cualquier idioma).
            //    Así llegan TODOS a la otra IA en su lugar: build.yml, CMakeLists.txt, native-lib.cpp, gradle.properties…
            val full = Regex("📍[^:：\\n]*[:：]\\s*(\\S+)").findAll(before).lastOrNull()?.groupValues?.get(1)
                ?.takeIf { before.substring(before.lastIndexOf("📍")).count { it == '\n' } <= 2 }
            val path = full?.let { if (it.contains('/')) it.substringAfter('/') else it }
            out += CodeBlock(m.groupValues[2].lowercase(), m.groupValues[3], path?.substringAfterLast('/') ?: name, path)
        }
        return out
    }

    // ------------------------------------------------------------------ 🤖 LLEVAR MI PROYECTO A OTRA IA
    /** Ruta del archivo dentro del proyecto según su tipo («activity_main.xml» → app/src/main/res/layout/activity_main.xml). */
    private fun projectPath(b: CodeBlock, web: Boolean): String {
        val f = b.fileName ?: return ""
        val c = b.code
        val ext = f.substringAfterLast('.', "").lowercase()
        return when {
            web && ext in setOf("html", "htm", "css", "js") -> f
            f.equals("AndroidManifest.xml", true) -> "app/src/main/$f"
            f.endsWith(".gradle") || f.endsWith(".gradle.kts") -> if (f.startsWith("settings")) f else "app/$f"
            ext == "kt" || ext == "java" -> {
                val pkg = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(c)?.groupValues?.get(1)
                "app/src/main/java/" + (pkg?.replace('.', '/')?.plus("/") ?: "") + f
            }
            ext in setOf("cpp", "cc", "c", "h", "hpp") || f == "CMakeLists.txt" -> "app/src/main/cpp/$f"
            ext == "xml" -> when {
                c.contains("<navigation") -> "app/src/main/res/navigation/$f"
                Regex("<menu[\\s>]").containsMatchIn(c) && !viewRoots.containsMatchIn(c) -> "app/src/main/res/menu/$f"
                c.contains("<resources") -> "app/src/main/res/values/$f"
                Regex("<(shape|selector|vector|layer-list|ripple)[\\s>]").containsMatchIn(c) -> "app/src/main/res/drawable/$f"
                else -> "app/src/main/res/layout/$f"
            }
            else -> f
        }
    }

    /**
     * 🤖 Todo el proyecto en un solo texto, listo para pegar en Claude, Gemini o ChatGPT: qué app es, lo que pidió el
     * usuario, el resumen (pantallas, botones y lo que ya funciona) y CADA archivo (su versión más nueva) con su ruta.
     * null si todavía no hay código en el chat.
     */
    /** Textos del mensaje para la otra IA, en el idioma del usuario (12 idiomas; el resto, inglés). */
    private class HandoffText(val kindWeb: String, val kindCpp: String, val kindAndroid: String, val hello: String, val below: String,
                              val asked: String, val summary: String, val files: String, val need: String,
                              val n1: String, val n2: String, val n3: String, val reply: String)

    private val HANDOFF = mapOf(
        "es" to HandoffText("una app web (HTML, CSS y JavaScript)", "una app Android con IA local (Kotlin + XML + C++ con llama.cpp)",
            "una app Android (Kotlin + diseños XML)", "Hola. Estoy desarrollando %s y necesito que me ayudes a seguir desarrollándola.",
            "Abajo está TODO el proyecto como va ahora: cada archivo con su ruta y su contenido completo.",
            "LO QUE HE PEDIDO HASTA AHORA", "RESUMEN DEL PROYECTO", "ARCHIVOS", "QUÉ NECESITO",
            "1. Revisa el proyecto y corrige los errores que encuentres (que compile y funcione).",
            "2. Ayúdame a seguir desarrollándolo: te iré diciendo qué agregar.",
            "3. Cuando cambies algo, entrégame el archivo COMPLETO con su ruta (no solo un pedazo).", "Responde en español."),
        "en" to HandoffText("a web app (HTML, CSS and JavaScript)", "an Android app with local AI (Kotlin + XML + C++ with llama.cpp)",
            "an Android app (Kotlin + XML layouts)", "Hi. I'm developing %s and I need your help to keep building it.",
            "Below is the WHOLE project as it is now: every file with its path and full content.",
            "WHAT I HAVE ASKED FOR SO FAR", "PROJECT SUMMARY", "FILES", "WHAT I NEED",
            "1. Review the project and fix any errors you find (it must compile and work).",
            "2. Help me keep developing it: I'll tell you what to add.",
            "3. When you change something, give me the COMPLETE file with its path (not just a fragment).", "Reply in English."),
        "pt" to HandoffText("um app web (HTML, CSS e JavaScript)", "um app Android com IA local (Kotlin + XML + C++ com llama.cpp)",
            "um app Android (Kotlin + layouts XML)", "Olá. Estou desenvolvendo %s e preciso da sua ajuda para continuar.",
            "Abaixo está TODO o projeto como está agora: cada arquivo com seu caminho e conteúdo completo.",
            "O QUE PEDI ATÉ AGORA", "RESUMO DO PROJETO", "ARQUIVOS", "O QUE EU PRECISO",
            "1. Revise o projeto e corrija os erros que encontrar (precisa compilar e funcionar).",
            "2. Me ajude a continuar desenvolvendo: vou dizendo o que adicionar.",
            "3. Quando mudar algo, me entregue o arquivo COMPLETO com o caminho (não só um trecho).", "Responda em português."),
        "fr" to HandoffText("une app web (HTML, CSS et JavaScript)", "une app Android avec IA locale (Kotlin + XML + C++ avec llama.cpp)",
            "une app Android (Kotlin + layouts XML)", "Bonjour. Je développe %s et j'ai besoin de ton aide pour continuer.",
            "Ci-dessous se trouve TOUT le projet actuel : chaque fichier avec son chemin et son contenu complet.",
            "CE QUE J'AI DEMANDÉ JUSQU'ICI", "RÉSUMÉ DU PROJET", "FICHIERS", "CE DONT J'AI BESOIN",
            "1. Relis le projet et corrige les erreurs (il doit compiler et fonctionner).",
            "2. Aide-moi à continuer le développement : je te dirai quoi ajouter.",
            "3. Quand tu modifies quelque chose, donne-moi le fichier COMPLET avec son chemin (pas seulement un extrait).", "Réponds en français."),
        "de" to HandoffText("eine Web-App (HTML, CSS und JavaScript)", "eine Android-App mit lokaler KI (Kotlin + XML + C++ mit llama.cpp)",
            "eine Android-App (Kotlin + XML-Layouts)", "Hallo. Ich entwickle %s und brauche deine Hilfe, um weiterzumachen.",
            "Unten ist das GANZE aktuelle Projekt: jede Datei mit Pfad und vollständigem Inhalt.",
            "WAS ICH BISHER ANGEFRAGT HABE", "PROJEKTÜBERSICHT", "DATEIEN", "WAS ICH BRAUCHE",
            "1. Prüfe das Projekt und behebe gefundene Fehler (es muss kompilieren und funktionieren).",
            "2. Hilf mir, es weiterzuentwickeln: ich sage dir, was hinzukommen soll.",
            "3. Wenn du etwas änderst, gib mir die VOLLSTÄNDIGE Datei mit Pfad (nicht nur einen Ausschnitt).", "Antworte auf Deutsch."),
        "it" to HandoffText("un'app web (HTML, CSS e JavaScript)", "un'app Android con IA locale (Kotlin + XML + C++ con llama.cpp)",
            "un'app Android (Kotlin + layout XML)", "Ciao. Sto sviluppando %s e ho bisogno del tuo aiuto per continuare.",
            "Qui sotto c'è TUTTO il progetto attuale: ogni file con il suo percorso e il contenuto completo.",
            "COSA HO CHIESTO FINORA", "RIEPILOGO DEL PROGETTO", "FILE", "COSA MI SERVE",
            "1. Controlla il progetto e correggi gli errori (deve compilare e funzionare).",
            "2. Aiutami a continuare lo sviluppo: ti dirò cosa aggiungere.",
            "3. Quando cambi qualcosa, dammi il file COMPLETO con il percorso (non solo un pezzo).", "Rispondi in italiano."),
        "nl" to HandoffText("een web-app (HTML, CSS en JavaScript)", "een Android-app met lokale AI (Kotlin + XML + C++ met llama.cpp)",
            "een Android-app (Kotlin + XML-layouts)", "Hallo. Ik ontwikkel %s en ik heb je hulp nodig om verder te gaan.",
            "Hieronder staat het HELE huidige project: elk bestand met pad en volledige inhoud.",
            "WAT IK TOT NU TOE HEB GEVRAAGD", "PROJECTOVERZICHT", "BESTANDEN", "WAT IK NODIG HEB",
            "1. Controleer het project en los fouten op (het moet compileren en werken).",
            "2. Help me verder te ontwikkelen: ik zeg wat er bij moet.",
            "3. Als je iets wijzigt, geef me het VOLLEDIGE bestand met pad (niet alleen een stukje).", "Antwoord in het Nederlands."),
        "pl" to HandoffText("aplikację webową (HTML, CSS i JavaScript)", "aplikację Android z lokalną AI (Kotlin + XML + C++ z llama.cpp)",
            "aplikację Android (Kotlin + layouty XML)", "Cześć. Tworzę %s i potrzebuję pomocy, żeby ją dalej rozwijać.",
            "Poniżej jest CAŁY obecny projekt: każdy plik ze ścieżką i pełną treścią.",
            "O CO PROSIŁEM DO TEJ PORY", "PODSUMOWANIE PROJEKTU", "PLIKI", "CZEGO POTRZEBUJĘ",
            "1. Przejrzyj projekt i popraw błędy (musi się kompilować i działać).",
            "2. Pomóż mi rozwijać go dalej: powiem, co dodać.",
            "3. Gdy coś zmienisz, daj mi CAŁY plik ze ścieżką (nie tylko fragment).", "Odpowiadaj po polsku."),
        "ru" to HandoffText("веб-приложение (HTML, CSS и JavaScript)", "Android-приложение с локальным ИИ (Kotlin + XML + C++ с llama.cpp)",
            "Android-приложение (Kotlin + XML-разметка)", "Привет. Я разрабатываю %s, и мне нужна твоя помощь, чтобы продолжить.",
            "Ниже ВЕСЬ текущий проект: каждый файл с путём и полным содержимым.",
            "ЧТО Я УЖЕ ПРОСИЛ", "ОПИСАНИЕ ПРОЕКТА", "ФАЙЛЫ", "ЧТО МНЕ НУЖНО",
            "1. Проверь проект и исправь ошибки (он должен компилироваться и работать).",
            "2. Помоги мне развивать его дальше: я буду говорить, что добавить.",
            "3. Когда что-то меняешь, давай ПОЛНЫЙ файл с путём (а не кусок).", "Отвечай на русском."),
        "zh" to HandoffText("一个网页应用（HTML、CSS 和 JavaScript）", "一个带本地 AI 的安卓应用（Kotlin + XML + C++ 与 llama.cpp）",
            "一个安卓应用（Kotlin + XML 布局）", "你好。我正在开发%s，需要你帮我继续开发。",
            "下面是目前的整个项目：每个文件都附有路径和完整内容。",
            "我目前提出的需求", "项目概要", "文件", "我需要的帮助",
            "1. 检查项目并修复发现的错误（必须能编译并正常运行）。",
            "2. 帮我继续开发：我会告诉你要添加什么。",
            "3. 修改时请给我带路径的完整文件（不要只给片段）。", "请用中文回答。"),
        "ko" to HandoffText("웹 앱(HTML, CSS, JavaScript)", "로컬 AI가 있는 안드로이드 앱(Kotlin + XML + llama.cpp를 사용하는 C++)",
            "안드로이드 앱(Kotlin + XML 레이아웃)", "안녕하세요. %s을(를) 개발 중이며 계속 개발하려면 도움이 필요합니다.",
            "아래는 현재 프로젝트 전체입니다: 각 파일의 경로와 전체 내용.",
            "지금까지 요청한 내용", "프로젝트 요약", "파일", "필요한 것",
            "1. 프로젝트를 검토하고 오류를 수정해 주세요(컴파일되고 작동해야 합니다).",
            "2. 계속 개발할 수 있게 도와주세요: 추가할 내용을 말씀드릴게요.",
            "3. 무언가를 바꾸면 경로와 함께 파일 전체를 주세요(일부만 말고).", "한국어로 답해 주세요."),
        "ja" to HandoffText("Webアプリ（HTML・CSS・JavaScript）", "ローカルAI付きのAndroidアプリ（Kotlin + XML + llama.cppを使うC++）",
            "Androidアプリ（Kotlin + XMLレイアウト）", "こんにちは。%sを開発していて、続きの開発を手伝ってほしいです。",
            "以下が現在のプロジェクト全体です：各ファイルのパスと全内容。",
            "これまでに依頼したこと", "プロジェクトの概要", "ファイル", "お願いしたいこと",
            "1. プロジェクトを確認してエラーを直してください（コンパイルして動くように）。",
            "2. 開発の続きを手伝ってください：追加したいことを伝えます。",
            "3. 変更するときは、パス付きのファイル全体をください（一部だけでなく）。", "日本語で答えてください。")
    )

    /**
     * 📱 Lo PRIMERO que se le pide a la otra IA: que muestre la maqueta de la app tal como está, funcionando con su lógica
     * (así el usuario parte viendo su app allá y sigue desarrollándola: motor de IA, CPU/GPU, lo que quiera).
     * (Android, web) por idioma; el resto, inglés.
     */
    private val PREVIEW_ASK = mapOf(
        "es" to ("0. PRIMERO muéstrame la maqueta de la app tal como está ahora: una vista previa interactiva en HTML (por ejemplo en un artifact o canvas) " +
            "que dibuje sus pantallas con los mismos colores y que funcione con su lógica: el menú ☰, los ajustes ⚙️, enviar ➤, el micrófono 🎤 y cada botón " +
            "haciendo lo mismo que hace en el código. Cada vez que agregues o cambies algo, actualiza también la maqueta." to
            "0. PRIMERO muéstrame la app funcionando tal como está ahora: una vista previa (por ejemplo en un artifact o canvas) con su HTML, CSS y JavaScript " +
            "juntos, para probarla con toda su lógica. Cada vez que agregues o cambies algo, actualiza también la vista previa."),
        "en" to ("0. FIRST show me the app's mockup as it is now: an interactive HTML preview (for example in an artifact or canvas) that draws its screens " +
            "with the same colors and works with its logic: the ☰ menu, ⚙️ settings, send ➤, the 🎤 microphone and every button doing what it does in the code. " +
            "Every time you add or change something, update the mockup too." to
            "0. FIRST show me the app running as it is now: a preview (for example in an artifact or canvas) with its HTML, CSS and JavaScript together, " +
            "so I can try it with all its logic. Every time you add or change something, update the preview too."),
        "pt" to ("0. PRIMEIRO me mostre a maquete do app como está agora: uma prévia interativa em HTML (por exemplo em um artifact ou canvas) que desenhe " +
            "suas telas com as mesmas cores e funcione com sua lógica: o menu ☰, os ajustes ⚙️, enviar ➤, o microfone 🎤 e cada botão fazendo o mesmo que no código. " +
            "Sempre que adicionar ou mudar algo, atualize também a maquete." to
            "0. PRIMEIRO me mostre o app funcionando como está agora: uma prévia (por exemplo em um artifact ou canvas) com seu HTML, CSS e JavaScript juntos, " +
            "para testá-lo com toda a sua lógica. Sempre que adicionar ou mudar algo, atualize também a prévia."),
        "fr" to ("0. D'ABORD montre-moi la maquette de l'app telle qu'elle est : un aperçu HTML interactif (par exemple dans un artifact ou un canvas) qui dessine " +
            "ses écrans avec les mêmes couleurs et fonctionne avec sa logique : le menu ☰, les réglages ⚙️, envoyer ➤, le micro 🎤 et chaque bouton faisant ce qu'il fait dans le code. " +
            "À chaque ajout ou modification, mets aussi la maquette à jour." to
            "0. D'ABORD montre-moi l'app en marche telle qu'elle est : un aperçu (par exemple dans un artifact ou un canvas) avec son HTML, CSS et JavaScript ensemble, " +
            "pour la tester avec toute sa logique. À chaque ajout ou modification, mets aussi l'aperçu à jour."),
        "de" to ("0. ZUERST zeig mir das Mockup der App, wie sie jetzt ist: eine interaktive HTML-Vorschau (zum Beispiel in einem Artifact oder Canvas), die ihre " +
            "Bildschirme in denselben Farben zeigt und mit ihrer Logik funktioniert: Menü ☰, Einstellungen ⚙️, Senden ➤, Mikrofon 🎤 und jeder Button macht, was er im Code macht. " +
            "Wenn du etwas hinzufügst oder änderst, aktualisiere auch das Mockup." to
            "0. ZUERST zeig mir die App, wie sie jetzt läuft: eine Vorschau (zum Beispiel in einem Artifact oder Canvas) mit HTML, CSS und JavaScript zusammen, " +
            "damit ich sie mit ihrer ganzen Logik testen kann. Wenn du etwas hinzufügst oder änderst, aktualisiere auch die Vorschau."),
        "it" to ("0. PER PRIMA COSA mostrami il mockup dell'app com'è adesso: un'anteprima HTML interattiva (per esempio in un artifact o canvas) che disegni " +
            "le sue schermate con gli stessi colori e funzioni con la sua logica: il menu ☰, le impostazioni ⚙️, invia ➤, il microfono 🎤 e ogni pulsante che fa ciò che fa nel codice. " +
            "Ogni volta che aggiungi o cambi qualcosa, aggiorna anche il mockup." to
            "0. PER PRIMA COSA mostrami l'app funzionante com'è adesso: un'anteprima (per esempio in un artifact o canvas) con HTML, CSS e JavaScript insieme, " +
            "per provarla con tutta la sua logica. Ogni volta che aggiungi o cambi qualcosa, aggiorna anche l'anteprima."),
        "nl" to ("0. Laat me EERST de mockup van de app zien zoals hij nu is: een interactieve HTML-preview (bijvoorbeeld in een artifact of canvas) die de " +
            "schermen in dezelfde kleuren tekent en met de logica werkt: menu ☰, instellingen ⚙️, verzenden ➤, microfoon 🎤 en elke knop doet wat hij in de code doet. " +
            "Werk de mockup bij telkens als je iets toevoegt of wijzigt." to
            "0. Laat me EERST de app zien zoals hij nu werkt: een preview (bijvoorbeeld in een artifact of canvas) met HTML, CSS en JavaScript samen, " +
            "zodat ik hem met al zijn logica kan testen. Werk de preview bij telkens als je iets toevoegt of wijzigt."),
        "pl" to ("0. NAJPIERW pokaż mi makietę aplikacji w obecnym stanie: interaktywny podgląd HTML (np. w artifact lub canvas), który rysuje jej ekrany " +
            "w tych samych kolorach i działa z jej logiką: menu ☰, ustawienia ⚙️, wyślij ➤, mikrofon 🎤 i każdy przycisk robi to, co w kodzie. " +
            "Przy każdej zmianie aktualizuj też makietę." to
            "0. NAJPIERW pokaż mi działającą aplikację w obecnym stanie: podgląd (np. w artifact lub canvas) z HTML, CSS i JavaScript razem, " +
            "żebym mógł przetestować całą jej logikę. Przy każdej zmianie aktualizuj też podgląd."),
        "ru" to ("0. СНАЧАЛА покажи мне макет приложения в текущем виде: интерактивный HTML-предпросмотр (например, в artifact или canvas), который рисует его экраны " +
            "теми же цветами и работает с его логикой: меню ☰, настройки ⚙️, отправка ➤, микрофон 🎤 и каждая кнопка делает то же, что в коде. " +
            "При каждом изменении обновляй и макет." to
            "0. СНАЧАЛА покажи мне работающее приложение в текущем виде: предпросмотр (например, в artifact или canvas) с HTML, CSS и JavaScript вместе, " +
            "чтобы я мог проверить всю его логику. При каждом изменении обновляй и предпросмотр."),
        "zh" to ("0. 首先给我看应用当前的原型：一个可交互的 HTML 预览（例如在 artifact 或 canvas 中），用相同的颜色画出它的界面，并按它的逻辑运行：" +
            "菜单 ☰、设置 ⚙️、发送 ➤、麦克风 🎤，每个按钮都做代码里做的事。每次添加或修改内容时，也要更新原型。" to
            "0. 首先给我看应用当前运行的样子：一个预览（例如在 artifact 或 canvas 中），把它的 HTML、CSS 和 JavaScript 放在一起，让我能测试全部逻辑。" +
            "每次添加或修改内容时，也要更新预览。"),
        "ko" to ("0. 먼저 지금 상태의 앱 목업을 보여 주세요: 같은 색상으로 화면을 그리고 앱의 로직대로 작동하는 인터랙티브 HTML 미리보기(예: artifact 또는 canvas). " +
            "메뉴 ☰, 설정 ⚙️, 보내기 ➤, 마이크 🎤, 그리고 각 버튼이 코드에서와 똑같이 작동해야 합니다. 무언가를 추가하거나 바꿀 때마다 목업도 업데이트해 주세요." to
            "0. 먼저 지금 상태로 작동하는 앱을 보여 주세요: HTML, CSS, JavaScript를 함께 넣은 미리보기(예: artifact 또는 canvas)로 모든 로직을 테스트할 수 있게요. " +
            "무언가를 추가하거나 바꿀 때마다 미리보기도 업데이트해 주세요."),
        "ja" to ("0. まず今の状態のアプリのモックアップを見せてください：同じ色で画面を描き、アプリのロジックどおりに動くインタラクティブなHTMLプレビュー（例：artifactやcanvas）。" +
            "メニュー ☰、設定 ⚙️、送信 ➤、マイク 🎤、各ボタンがコードと同じ動きをすること。何かを追加・変更するたびに、モックアップも更新してください。" to
            "0. まず今の状態で動くアプリを見せてください：HTML・CSS・JavaScriptをまとめたプレビュー（例：artifactやcanvas）で、すべてのロジックを試せるように。" +
            "何かを追加・変更するたびに、プレビューも更新してください。")
    )

    /**
     * 🤖 Todo el proyecto en un solo texto, listo para pegar en Claude, Gemini o ChatGPT: qué app es, lo que pidió el
     * usuario, el resumen (pantallas, botones y lo que ya funciona) y CADA archivo (su versión más nueva) con su ruta.
     * [lang]: idioma del usuario («es», «en», «pt», «zh»…): el mensaje y la instrucción «responde en…» van en ese idioma.
     * null si todavía no hay código en el chat.
     */
    fun buildHandoff(messagesNewestFirst: List<String>, requestsOldestFirst: List<String>, lang: String = "es"): String? {
        val t = HANDOFF[lang] ?: HANDOFF.getValue("en")
        val all = messagesNewestFirst.asReversed().flatMap { codeBlocks(it) }
        if (all.isEmpty()) return null
        val web = all.any { isHtml(it) }
        val cpp = all.any { it.fileName?.matches(Regex("(?i).*\\.(cpp|cc|hpp)$|CMakeLists\\.txt")) == true }
        // La versión más nueva de cada archivo (sin el maqueta.js, que es solo para la maqueta)
        val files = LinkedHashMap<String, CodeBlock>()
        var n = 1
        for (b in all) {
            if (b.fileName?.equals("maqueta.js", true) == true || b.code.isBlank()) continue
            val key = b.path ?: b.fileName?.let { projectPath(b, web) } ?: "sin_nombre_${n++}.${b.lang.ifBlank { "txt" }}"
            files.remove(key); files[key] = b
        }
        if (files.isEmpty()) return null
        val kind = when { web -> t.kindWeb; cpp -> t.kindCpp; else -> t.kindAndroid }
        val sb = StringBuilder()
        sb.append(t.hello.format(kind)).append('\n').append(t.below).append("\n\n")
        if (requestsOldestFirst.isNotEmpty()) {
            sb.append("=== ${t.asked} ===\n")
            requestsOldestFirst.takeLast(12).forEach { sb.append("- ").append(it.replace(Regex("\\s+"), " ").take(220)).append('\n') }
            sb.append('\n')
        }
        projectOutline(messagesNewestFirst, maxChars = 1500)?.let {
            val body = it.substringAfter("]\n", it).let { o -> if (lang == "es") o else o.replace("Archivos:", "Files:")
                .replace("Pantallas y elementos:", "Screens and elements:").replace("Botones que ya funcionan:", "Buttons that already work:") }
            sb.append("=== ${t.summary} ===\n").append(body).append("\n\n")
        }
        sb.append("=== ${t.files} (${files.size}) ===\n")
        for ((path, b) in files) {
            sb.append("\n📁 ").append(path).append('\n')
            val fn = ProjectCheck.fence(b.code)   // (más comillas si el código trae ``` por dentro)
            sb.append(fn).append(b.lang.ifBlank { path.substringAfterLast('.', "") }).append('\n')
            sb.append(b.code.trimEnd()).append("\n").append(fn).append("\n")
        }
        // 📱 Primero: que la otra IA muestre la maqueta (o la vista previa web) funcionando con su lógica
        val preview = (PREVIEW_ASK[lang] ?: PREVIEW_ASK.getValue("en")).let { if (web) it.second else it.first }
        sb.append("\n=== ${t.need} ===\n").append(preview).append('\n').append(t.n1).append('\n').append(t.n2).append('\n').append(t.n3).append('\n').append(t.reply)
        return sb.toString()
    }

    // ------------------------------------------------------------------ VISTA PREVIA WEB
    private fun isHtml(b: CodeBlock) = b.lang in setOf("html", "htm", "xhtml") ||
        b.fileName?.matches(Regex("(?i).*\\.html?$")) == true ||
        Regex("(?i)<!doctype html|<html[\\s>]|<body[\\s>]").containsMatchIn(b.code)
    private fun isCss(b: CodeBlock) = b.lang == "css" || b.fileName?.endsWith(".css", true) == true
    private fun isJs(b: CodeBlock) = b.fileName?.equals("maqueta.js", true) != true &&   // 🧪 la lógica de la maqueta no es una app web
        (b.lang in setOf("js", "javascript", "jsx") || b.fileName?.endsWith(".js", true) == true)

    /** messagesNewestFirst: textos de las respuestas de la IA, de la más nueva a la más vieja. */
    fun buildWebPage(messagesNewestFirst: List<String>): Pair<String, String>? {
        // 🌐 Tu página completa (.zip o archivos): se abre como en tu hosting, con sus propios archivos en su lugar
        //    (solo si cada CSS y JS que enlaza vino; si no, se arma como antes, juntando lo que pegaste)
        val site = webFiles
        site[webStart]?.second?.toString(Charsets.UTF_8)?.let { page ->
            val links = Regex("(?i)<(?:link[^>]+href|script[^>]+src)\\s*=\\s*[\"']((?!https?:|//|data:)[^\"'?#]+\\.(?:css|js|mjs))").findAll(page)
                .map { webResolve(webStart, it.groupValues[1]) }.toList()
            if (links.all { site.containsKey(it) }) return "@@URL:https://$WEB_HOST/$webStart" to page
        }
        val all = messagesNewestFirst.map { codeBlocks(it) }
        val htmlIdx = all.indexOfFirst { bl -> bl.any { isHtml(it) } }
        val pool: List<CodeBlock>
        var html: String
        if (htmlIdx >= 0) {
            // El HTML más nuevo + los CSS/JS de ese mensaje y de los mensajes más nuevos (cambios posteriores)
            html = all[htmlIdx].last { isHtml(it) }.code
            pool = all.take(htmlIdx + 1).flatten()
        } else {
            pool = all.firstOrNull { bl -> bl.any { isJs(it) || isCss(it) } } ?: return null
            html = "<!DOCTYPE html><html><head></head><body></body></html>"
        }
        // Solo la versión más nueva de cada archivo CSS/JS
        val css = pool.filter { isCss(it) }.distinctBy { it.fileName ?: it.code.hashCode().toString() }.joinToString("\n") { it.code }
        // 🦙 Los módulos (con «import … from», como ia-local.js de una app web de IA) van en su propio <script type="module">:
        //    juntos con el resto en un <script> normal, el «import» rompía toda la vista previa
        val moduleRx = Regex("(?m)^\\s*import\\s[^\\n]*\\sfrom\\s+['\"]")
        val jsBlocks = pool.filter { isJs(it) }.distinctBy { it.fileName ?: it.code.hashCode().toString() }
        val js = jsBlocks.filter { !moduleRx.containsMatchIn(it.code) }.joinToString("\n;\n") { it.code }
        val modules = jsBlocks.filter { moduleRx.containsMatchIn(it.code) }
        // Los archivos locales enlazados (style.css, app.js) se reemplazan por el código del chat
        html = html.replace(Regex("(?i)<link[^>]+href=[\"'](?!https?:)[^\"']+\\.css[\"'][^>]*>"), "")
            .replace(Regex("(?i)<script[^>]+src=[\"'](?!https?:)[^\"']+\\.js[\"'][^>]*>\\s*</script>"), "")
        val meta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        if (!Regex("(?i)<meta[^>]+viewport").containsMatchIn(html)) {
            html = insertAt(html, Regex("(?i)<head[^>]*>"), after = true, insert = meta) ?: (meta + html)
        }
        if (css.isNotBlank()) {
            val tag = "<style>\n$css\n</style>"
            html = insertAt(html, Regex("(?i)</head>"), after = false, insert = tag) ?: (tag + html)
        }
        if (js.isNotBlank()) {
            val tag = "<script>\n$js\n</script>"
            html = insertAt(html, Regex("(?i)</body>(?![\\s\\S]*</body>)"), after = false, insert = tag) ?: (html + tag)
        }
        for (m in modules) {
            val tag = "<script type=\"module\">\n${m.code.replace("</script", "<\\/script")}\n</script>"
            html = insertAt(html, Regex("(?i)</body>(?![\\s\\S]*</body>)"), after = false, insert = tag) ?: (html + tag)
        }
        // 🌍 La vista se ve en tu idioma (botones, títulos, avisos); la pestaña Código muestra lo que escribió la IA
        // (tu página tal como la escribió la IA: sus textos NO se traducen, así ves exactamente tu diseño)
        return html to html
    }

    /** Inserta [insert] antes o después de la primera coincidencia de [rx]; null si no la encuentra. */
    private fun insertAt(text: String, rx: Regex, after: Boolean, insert: String): String? {
        val m = rx.find(text) ?: return null
        val pos = if (after) m.range.last + 1 else m.range.first
        return text.substring(0, pos) + insert + text.substring(pos)
    }

    // ------------------------------------------------------------------ MAQUETA DE APP ANDROID
    private val viewRoots = Regex("(?i)<(LinearLayout|androidx\\.[\\w.]*Layout|[\\w.]*ConstraintLayout|RelativeLayout|FrameLayout|ScrollView|" +
        "[\\w.]*NestedScrollView|[\\w.]*CoordinatorLayout|[\\w.]*CardView|[\\w.]*DrawerLayout|TableLayout|GridLayout|merge)[\\s>]")

    /**
     * 🧪 TODOS los maqueta.js del chat, del más viejo al más nuevo: la lógica se va SUMANDO (enviar, después el micrófono…).
     * Si dos tocan el mismo botón, gana el más nuevo. Cada uno va aparte, para que un error en uno no apague a los otros.
     */
    private fun mockupLogic(messagesNewestFirst: List<String>): List<String> {
        val all = messagesNewestFirst.asReversed().flatMap { msg -> codeBlocks(msg).filter { it.fileName?.equals("maqueta.js", true) == true && it.code.isNotBlank() } }
            .map { it.code }
        // 💬 La maqueta del chat limpio la escribe la app («maqueta-base»): desde la última, se ignoran las anteriores del chat
        //    (así un maqueta.js viejo con errores no rompe la maqueta nueva)
        val base = all.indexOfLast { it.contains("maqueta-base") }
        return (if (base >= 0) all.drop(base) else all).distinct()
    }

    /**
     * 🧱 Resumen del proyecto para la IA (modo código): archivos, pantallas con sus elementos y qué botones ya tienen lógica.
     * Así sigue la MISMA app aunque el chat sea largo y el modelo tenga poca memoria. null si todavía no hay código.
     */
    fun projectOutline(messagesNewestFirst: List<String>, maxChars: Int = 650): String? {
        val files = LinkedHashSet<String>()
        for (msg in messagesNewestFirst.asReversed()) for (b in codeBlocks(msg)) {
            val f = b.fileName ?: continue
            if (f.equals("maqueta.js", true)) continue
            files.remove(f); files.add(f)
        }
        if (files.isEmpty()) return null
        val sb = StringBuilder("[PROYECTO ACTUAL: sigue desarrollando ESTA MISMA app; conserva los nombres e id que ya existen]\n")
        sb.append("Archivos: ").append(files.toList().takeLast(14).joinToString(", ")).append('\n')
        mockupIds(messagesNewestFirst, maxChars = 380)?.let { sb.append("Pantallas y elementos:\n").append(it).append('\n') }
        val done = mockupLogic(messagesNewestFirst).flatMap { js -> Regex("alClick\\(\\s*['\"]([\\w]+)['\"]").findAll(js).map { it.groupValues[1] }.toList() }.distinct()
        if (done.isNotEmpty()) sb.append("Botones que ya funcionan: ").append(done.joinToString(", ")).append('\n')
        return sb.toString().take(maxChars).trim()
    }

    /**
     * 🧪 Resumen corto de las pantallas para que la IA escriba maqueta.js: cada pantalla con sus elementos
     * («activity_main: EditText etPost, Button btnPublicar, RecyclerView rvPosts»). null si no hay diseños.
     */
    fun mockupIds(messagesNewestFirst: List<String>, maxChars: Int = 700): String? {
        val layouts = LinkedHashMap<String, String>()
        var n = 1
        for (msg in messagesNewestFirst.asReversed()) for (b in codeBlocks(msg)) {
            val c = b.code
            if ((b.lang == "xml" || c.trimStart().startsWith("<")) && viewRoots.containsMatchIn(c) && !c.contains("<manifest")) {
                val name = b.fileName?.substringBeforeLast('.') ?: "pantalla_${n++}"
                layouts.remove(name); layouts[name] = c
            }
        }
        if (layouts.isEmpty()) return null
        val sb = StringBuilder()
        // Las pantallas más nuevas primero (si no cabe todo, se dejan fuera las más viejas)
        for ((name, xml) in layouts.entries.reversed()) {
            val ids = Regex("<([\\w.]+)\\b[^>]*?android:id=\"@\\+id/([\\w]+)\"", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                .map { "${it.groupValues[1].substringAfterLast('.')} ${it.groupValues[2]}" }.toList()
            if (ids.isEmpty()) continue
            val line = "$name: " + ids.joinToString(", ") + "\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 🎮 Interactividad básica de la maqueta (siempre): el menú lateral y la barra de abajo abren la pantalla,
     * ＋ agrega a la lista, ➤ manda lo escrito a la lista, ♥ se marca, los botones y los interruptores avisan.
     * Funciones para maqueta.js: alClick(id,fn) · texto(id[,valor]) · valor(id) · limpiar(id) · agregar(listaId,texto)
     * · aviso(msg) · abrir(pantalla) · mostrar(id,true/false) · burbuja(texto[, deLaApp]) (mensaje de chat en la pantalla)
     */
    private val INTERACTIVE_JS = """
function el(id){return document.querySelector('.screen[style*="flex"] [data-id="'+id+'"]')||document.querySelector('[data-id="'+id+'"]');}
function aviso(m){var t=document.getElementById('toast');t.textContent=m;t.style.opacity=1;clearTimeout(t._h);t._h=setTimeout(function(){t.style.opacity=0},1900);}
function texto(id,v){var e=el(id);if(!e)return '';if(v===undefined)return e.value!==undefined?e.value:e.textContent;if(e.value!==undefined)e.value=v;else e.textContent=v;}
function valor(id){var e=el(id);return e?(e.value!==undefined?e.value:e.textContent):'';}
function limpiar(id){var e=el(id);if(e&&e.value!==undefined)e.value='';}
function mostrar(id,on){var e=el(id);if(e)e.style.display=on?'':'none';}
// 🎨 Colores que tu Kotlin pone por código (setBackgroundColor, setTextColor…), al abrir la pantalla o al tocar un botón
function rgba(r,g,b,a){return 'rgba('+r+','+g+','+b+','+((a===undefined?255:a)/255)+')';}
function colorFondo(e,r,g,b,a){if(e)e.style.background=rgba(r,g,b,a);}
function colorTexto(e,r,g,b,a){if(e)e.style.color=rgba(r,g,b,a);}
function colorPantalla(n,r,g,b,a){var i=NAMES.indexOf(n);var s=document.getElementById('s'+i);if(s)s.style.background=rgba(r,g,b,a);}
// 🎨 Lo que tu Kotlin le hace al diseño al abrirse (fondos redondeados, bordes, colores de modo claro/oscuro, a TODOS
//    los botones…): la maqueta lo imita. dn(día,noche) elige según el modo de tu app (NOCHE)
function dn(d,n){return (typeof NOCHE!=='undefined'&&NOCHE)?n:d;}
function _c(c){return c?rgba(c[0],c[1],c[2],c[3]):null;}
function fondo(e,c){if(e&&c)e.style.background=_c(c);}
function letra(e,c){if(e&&c)e.style.color=_c(c);}
function forma(e,r,c,sw,sc){if(!e)return;if(r!=null)e.style.borderRadius=r+'px';if(c)e.style.background=_c(c);
 if(sw!=null)e.style.border=sw+'px solid '+(sc?_c(sc):'currentColor');}
function borde(e,w,c){if(!e)return;if(w!=null){e.style.borderWidth=w+'px';e.style.borderStyle='solid';}if(c)e.style.borderColor=_c(c);}
function grad(e,a,b){if(e&&a&&b)e.style.background='linear-gradient('+_c(a)+','+_c(b)+')';}
function recorte(e,f){if(e)e.style.clipPath=f;}
function mayus(e,on){if(e)e.style.textTransform=on?'uppercase':'none';}
function pantalla(n,c){var i=NAMES.indexOf(n);var s=document.getElementById('s'+i);if(s&&c)s.style.background=_c(c);}
var TODOS=[];
// (lo que se le hace a TODOS los botones también va a las ventanas y vistas que se agregan después)
function _aplicarTodos(root){TODOS.forEach(function(p){root.querySelectorAll(p[0]).forEach(function(e){try{p[1](e)}catch(x){}});});}
function todos(sel,fn){TODOS.push([sel,fn]);document.querySelectorAll(sel.split(',').map(function(s){return '.phone '+s.trim()}).join(',')).forEach(function(e){try{fn(e)}catch(x){}});}
// (¿este elemento tiene ESE color de letra? para «if (v.currentTextColor == azul) …»)
function _igual(e,c){var m=getComputedStyle(e).color.match(/[\d.]+/g);return !!m&&!!c&&Math.abs(m[0]-c[0])<3&&Math.abs(m[1]-c[1])<3&&Math.abs(m[2]-c[2])<3;}
// 🎨 Color del texto de ayuda (hint) y de los íconos (imageTintList, setColorFilter, iconTint) puestos por código
function pista(e,c){if(e&&c)e.style.setProperty('--ph',_c(c));}
function tinte(e,c){if(!e||!c)return;var k=_c(c);e.style.color=k;e.querySelectorAll('svg path,svg circle,svg rect,svg polygon').forEach(function(p){p.style.fill=k;});}
// ✨ «Todo lo blanco con degradado» (tu Kotlin pinta los textos blancos con dos colores, y a veces los íconos):
//    cada texto blanco de la maqueta toma ese degradado; también lo que aparece después (menús, ventanas)
var DG=null;
function degradado(c1,c2,o){DG={a:_c(c1),b:_c(c2),o:o||{}};
 var s=document.createElementNS('http://www.w3.org/2000/svg','svg');s.setAttribute('width','0');s.setAttribute('height','0');s.style.position='absolute';
 s.innerHTML='<defs><linearGradient id="dgx" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="'+DG.a+'"/><stop offset="1" stop-color="'+DG.b+'"/></linearGradient></defs>';
 document.body.appendChild(s);
 var st=document.createElement('style');st.textContent='.dgt{background:linear-gradient(100deg,'+DG.a+','+DG.b+');-webkit-background-clip:text;background-clip:text;color:transparent!important;-webkit-text-fill-color:transparent}';
 document.head.appendChild(st);
 pintaDg();var ph=document.querySelector('.phone');
 if(ph&&window.MutationObserver){var t;new MutationObserver(function(){clearTimeout(t);t=setTimeout(pintaDg,60);}).observe(ph,{childList:true,subtree:true,characterData:true});}}
function _blanco(c){var m=c&&c.match(/[\d.]+/g);return !!m&&+m[0]>=225&&+m[1]>=225&&+m[2]>=225&&(m[3]===undefined||+m[3]>0.47);}
function pintaDg(){if(!DG)return;if(DG.o.noche&&!(typeof NOCHE!=='undefined'&&NOCHE))return;var ph=document.querySelector('.phone');if(!ph)return;
 var w=document.createTreeWalker(ph,NodeFilter.SHOW_TEXT,null),n,list=[];
 // (los menús que arma la maqueta usan la letra clara del tema: en tu app esos textos son blancos)
 function claro(pe){var m=getComputedStyle(pe).color.match(/[\d.]+/g);return !!m&&+m[0]>=190&&+m[1]>=190&&+m[2]>=190;}
 while((n=w.nextNode())){var pe=n.parentNode;if(!n.nodeValue.trim()||!pe||pe.classList&&pe.classList.contains('dgt'))continue;
  if(/^(SCRIPT|STYLE|INPUT|TEXTAREA|OPTION)$/.test(pe.tagName)||pe.closest('svg'))continue;list.push(n);}
 list.forEach(function(n){var pe=n.parentNode;if(!_blanco(getComputedStyle(pe).color)&&!(pe.closest('.ovm')&&claro(pe)))return;
  var sp=document.createElement('span');sp.className='dgt';n.parentNode.insertBefore(sp,n);sp.appendChild(n);});
 if(DG.o.iconos)ph.querySelectorAll('svg path,svg circle,svg rect,svg polygon,svg ellipse').forEach(function(e){if(e._dg)return;
  var cs=getComputedStyle(e);if(_blanco(cs.fill)){e.style.fill='url(#dgx)';e._dg=1;}if(_blanco(cs.stroke)){e.style.stroke='url(#dgx)';e._dg=1;}});}
// 🧾 Las filas que tu código pone en una lista al abrir (en vez de las de ejemplo): cada dato va al texto cuyo id se le
//    parece (text → tvMessageContent, sender → tvSender, tokens → tvTokenInfo); un dato vacío esconde ese texto
function _nk(s){return (s||'').replace(/([a-z0-9])([A-Z])/g,'$1_$2').toLowerCase().replace(/^(tv|txt|text|lbl|label|iv|img)_/,'').replace(/[^a-z0-9]/g,'');}
var SYNF={text:['message','content','body','msg','texto','mensaje','text'],sender:['sender','name','author','user','nombre'],tokens:['token'],title:['title','titulo'],subtitle:['subtitle','subtitulo']};
function _campo(id,k){var a=_nk(id),b=_nk(k);if(!a||!b)return 0;if(a===b)return 4;if(a.indexOf(b)>=0)return 3;if(b.length>3&&b.indexOf(a)>=0)return 2;
 return (SYNF[b]||[]).some(function(w){return a.indexOf(w)>=0})?1:0;}
function filas(items){if(!items||!items.length)return;var keys=Object.keys(items[0]);
 var leaf=function(r){return [].slice.call(r.querySelectorAll('[data-id]')).filter(function(e){return !e.children.length||e.classList.contains('txt')});};
 // (cada dato va a UN solo texto: el que más se le parece, y de preferencia un texto de verdad)
 var pick=function(r){var used=[],m={};keys.forEach(function(k){var best=null,bs=0;leaf(r).forEach(function(e){if(used.indexOf(e)>=0)return;
  var s=_campo(e.dataset.id,k);if(s&&e.classList.contains('txt'))s+=0.5;if(s>bs){bs=s;best=e;}});if(best){used.push(best);m[k]=best;}});return m;};
 document.querySelectorAll('.phone .lst').forEach(function(l){var t=l.firstElementChild;if(!t)return;
  if(!Object.keys(pick(t)).length)return;
  var tpl=t.cloneNode(true);l.innerHTML='';
  items.forEach(function(it){var r=tpl.cloneNode(true),m=pick(r);
   for(var k in m){var e=m[k];if(it[k]===''){e.style.display='none';e._vacio=1;}else e.textContent=it[k];}
   l.appendChild(r);});});}
// 👁️ Lo que el Adapter muestra en cada fila aunque el diseño lo tenga escondido (el avatar de la IA…)
function mostrarEnFilas(ids){ids.forEach(function(id){document.querySelectorAll('.phone .lst [data-id="'+id+'"]').forEach(function(e){if(e._vacio)return;e.style.display=e.style.flexDirection?'flex':'';if(getComputedStyle(e).display==='none')e.style.display='flex';});});}
// 🖼️ Una imagen o ícono que tu código agrega (un logo al lado del botón ☰, ⤓ y ⚑ en cada mensaje…): su dibujo de tu
//    proyecto (VEC), su imagen (.png…), o un ícono parecido; con su tamaño, margen, esquinas y color
var VEC=VEC||{};
// (íconos de líneas finas para los que tu código dibuja a mano, como la banderita de reportar)
var GLY={'⚑':'<svg viewBox="0 0 24 24" width="55%" height="55%"><path d="M6 21V4h11l-2.5 4L17 12H6" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round"/></svg>',
 '⤓':'<svg viewBox="0 0 24 24" width="60%" height="60%"><path d="M12 4v11m-5-5 5 5 5-5M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'};
function vistaImg(a,pos,n,w,h,r,q,tint,glyph,ml){document.querySelectorAll('.phone [data-id="'+a+'"]').forEach(function(x){
 var i,W=(w||40),H=(h||w||40);
 if(n&&VEC[n]){i=document.createElement('span');i.innerHTML=VEC[n];}
 else if(n){i=document.createElement('img');i.src='https://preview.cumulus.local/__res/'+n;i.style.objectFit='cover';
  i.onerror=function(){if(glyph){var g=document.createElement('span');g.textContent=glyph;g.style.cssText=i.style.cssText;i.replaceWith(g);}else i.remove();};}
 else if(glyph&&GLY[glyph]){i=document.createElement('span');i.innerHTML=GLY[glyph];}
 else{i=document.createElement('span');i.textContent=glyph||'';i.style.fontSize=Math.round(Math.min(W,H)*0.55)+'px';}
 i.className='v vimg';i.style.width=W+'px';i.style.height=H+'px';i.style.flex='none';i.style.display='inline-flex';i.style.alignItems='center';i.style.justifyContent='center';
 if(r)i.style.borderRadius=Math.min(r,H/2)+'px';if(r)i.style.overflow='hidden';if(ml)i.style.marginLeft=ml+'px';if(tint)i.style.color=_c(tint);
 if(q&&pos==='despues'){var s=x.nextElementSibling;if(s&&!s.dataset.id&&!s.textContent.trim()&&!s.children.length)s.remove();}
 if(pos==='dentro')x.appendChild(i);else if(pos==='dentro0')x.insertBefore(i,x.firstChild);
 else if(!x.parentNode)return;else if(pos==='antes')x.parentNode.insertBefore(i,x);else if(pos==='despues')x.parentNode.insertBefore(i,x.nextSibling);
 else if(pos==='inicio')x.parentNode.insertBefore(i,x.parentNode.firstChild);else x.parentNode.appendChild(i);});}
// 📑 Cambiar de página en un ViewFlipper (como «Modelos GGUF» → la página de descargas) y esconder o mostrar lo que
//    tu código esconde según la página
function pagina(id,n){var f=el(id);if(!f)return;f.classList.remove('vf');var k=f.children.length;if(!k)return;n=((n%k)+k)%k;f._pg=n;
 [].forEach.call(f.children,function(c,i){if(c._d===undefined)c._d=c.style.display;c.style.display=(i==n)?c._d:'none';});if(window.fitLater)fitLater();}
function alTocar(id,fn){document.querySelectorAll('[data-id="'+id+'"]').forEach(function(e){if(e._ht)return;e._ht=1;if(!e.matches('.ibtn'))e.dataset.h='1';e.addEventListener('click',function(){try{fn(e)}catch(x){}});});}
function paginaMas(id,d){var f=el(id);if(f)pagina(id,(f._pg||0)+d);}
function mostrarPadre(id,on){var e=el(id),q=e&&e.parentNode;if(!q)return;if(q._d===undefined)q._d=q.style.display;q.style.display=on?q._d:'none';}
// 🧱 Una vista armada por código ({t: tipo, x: texto, h: hint, s: tamaño, b: negrita, row, p: rellenos, k: hijas, st: estilo…})
function _vista(n){var t=n.t||'View',e,c;
 if(/Button$/.test(t)&&!/Image|Radio/.test(t)){e=document.createElement('button');e.className='btn v';e.textContent=n.x||'';}
 else if(/EditText$/.test(t)){e=document.createElement(n.ta?'textarea':'input');e.className='inp v';if(n.h)e.placeholder=n.h;if(n.x&&n.x!=='…')e.value=n.x;
  if(n.ta){e.style.resize='none';e.style.minHeight='160px';e.style.fontFamily='inherit';}}
 else if(/Switch/.test(t)){e=document.createElement('label');e.className='v txt swr';c=document.createElement('span');c.textContent=n.x||'';e.appendChild(c);
  c=document.createElement('input');c.type='checkbox';c.className='swi';e.appendChild(c);}
 else if(t==='CheckBox'||t==='RadioButton'){e=document.createElement('label');e.className='v txt';e.style.display='flex';e.style.alignItems='center';
  c=document.createElement('input');c.type=t==='CheckBox'?'checkbox':'radio';e.appendChild(c);e.appendChild(document.createTextNode(n.x||''));}
 else if(/TextView$/.test(t)){e=document.createElement('div');e.className='txt v';e.textContent=n.x||'';}
 else if(t==='SeekBar'){e=document.createElement('input');e.type='range';e.className='v';}
 else if(t==='ProgressBar'){e=document.createElement('div');e.className='v pgc';}
 else if(t==='ImageView'||t==='ImageButton'||t==='View'||t==='Space'){e=document.createElement('div');e.className='v';e.style.flex='none';}
 else{e=document.createElement('div');e.className='v'+(/Card/.test(t)?' card':'');e.style.display='flex';e.style.flexDirection=n.row?'row':'column';
  if(/Scroll/.test(t))e.style.overflow='auto';if(n.row&&(n.gv||n.g))e.style.alignItems='center';}
 if(n.s)e.style.fontSize=n.s+'px';if(n.b)e.style.fontWeight='700';
 if(n.g){e.style.textAlign='center';e.style.justifyContent='center';e.style.alignItems='center';}
 if(n.p)e.style.padding=n.p[1]+'px '+n.p[2]+'px '+n.p[3]+'px '+n.p[0]+'px';
 if(n.w==='f'){e.style.alignSelf='stretch';}else if(n.w)e.style.width=n.w+'px';
 if(n.hh==='f')e.style.flex='1';else if(n.hh)e.style.height=n.hh+'px';
 if(n.wt){e.style.flex=n.wt+' 1 0';e.style.minWidth='0';}
 if(n.ml)e.style.marginLeft=n.ml+'px';if(n.mr)e.style.marginRight=n.mr+'px';if(n.mt)e.style.marginTop=n.mt+'px';
 if(n.hide)e.style.display='none';
 (n.k||[]).forEach(function(k){var ce=_vista(k);if(n.row&&k.w==='f'&&!k.wt)ce.style.flex='1 1 auto';if(!n.row&&k.hh==='f')ce.style.flex='1';e.appendChild(ce);});
 if(n.st)try{n.st(e)}catch(x){}
 if(n.clk){e.dataset.h='1';e.addEventListener('click',function(ev){ev.stopPropagation();n.clk()});}
 return e;}
// 🪟 Una ventana que tu código arma vista por vista (Dialog / AlertDialog): con su título, su contenido y sus botones
function ventanaCodigo(n,o){o=o||{};var ph=document.querySelector('.phone'),ov=document.createElement('div');ov.className='ov';
 var c=document.createElement('div');c.className='ovc'+(o.sinFondo?' ovs':'');
 if(o.full){ov.style.padding='0';c.style.cssText='width:100%;height:100%;max-height:100%;border-radius:0;background:var(--bg);color:var(--fg)';}
 if(o.hoja){ov.style.alignItems='flex-end';ov.style.padding='0';c.style.borderRadius='18px 18px 0 0';}
 if(o.radio!=null&&!o.full)c.style.borderRadius=o.hoja?o.radio+'px '+o.radio+'px 0 0':o.radio+'px';
 if(o.titulo){var h=document.createElement('div');h.className='ovt';h.textContent=o.titulo;c.appendChild(h);}
 var b=_vista(n);b.style.flex='1';b.style.minHeight='0';c.appendChild(b);
 if(o.botones&&o.botones.length){var r=document.createElement('div');r.style.cssText='display:flex;justify-content:flex-end;gap:8px;padding:8px 12px';
  o.botones.forEach(function(t){var k=document.createElement('button');k.className='btn txb v';k.textContent=t;k.dataset.h='1';
   k.onclick=function(ev){ev.stopPropagation();ov.remove()};r.appendChild(k);});c.appendChild(r);}
 // (la ✕ solo en las de pantalla completa: esas no tienen «afuera» para tocar y cerrarla)
 if(o.full){var x=document.createElement('button');x.className='ovx';x.textContent='✕';x.onclick=function(ev){ev.stopPropagation();ov.remove()};c.insertBefore(x,c.firstChild);}
 ov.addEventListener('click',function(ev){if(ev.target===ov)ov.remove()});ov.appendChild(c);(ph||document.body).appendChild(ov);
 _aplicarTodos(c);setTimeout(function(){fitBtns(c)},0);}
// 🧱 Una vista que tu código agrega al abrir, junto a algo que ya existe (después de una fila, adentro de una barra…)
function insertarVista(a,up,pos,n){document.querySelectorAll('.phone [data-id="'+a+'"]').forEach(function(x){var t=x,k=pos==='dentro'?up+1:up;
 for(var i=0;i<k&&t.parentNode;i++)t=t.parentNode;var v=_vista(n);
 if(pos==='dentro')t.appendChild(v);else if(t.parentNode){if(pos==='antes')t.parentNode.insertBefore(v,t);else t.parentNode.insertBefore(v,t.nextSibling);}});}
// ✨ Un texto con su propio degradado de dos colores (como un nombre pintado con un «span»): en cada lugar donde aparece,
//    también en las filas de una lista; si en tu app es solo de noche, en modo claro queda con su color normal
function degradadoEn(id,a,b,noche){if(noche&&!(typeof NOCHE!=='undefined'&&NOCHE))return;
 document.querySelectorAll('.phone [data-id="'+id+'"]').forEach(function(e){var t=e.textContent;if(!t.trim()||e.children.length>1)return;
  e.textContent='';var s=document.createElement('span');s.className='dgt';s.textContent=t;
  s.style.cssText='background:linear-gradient(90deg,'+_c(a)+','+_c(b)+');-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent';
  e.appendChild(s);});}
// 📏 Elementos del mismo alto (el del más alto), como hace tu código; se vuelve a medir al abrir un panel o girar
var IGUALAR=[];
function igualarAlto(ids){var f=function(){var es=ids.map(function(i){return el(i)}).filter(function(e){return e&&e.offsetParent!==null});if(es.length<2)return;
 es.forEach(function(e){e.style.minHeight=''});var h=Math.max.apply(null,es.map(function(e){return e.offsetHeight}));if(h>0)es.forEach(function(e){e.style.minHeight=h+'px'});};
 IGUALAR.push(f);setTimeout(f,80);setTimeout(f,400);window.addEventListener('resize',f);}
// ⋮ El menú de la barra de arriba (onCreateOptionsMenu): el botón ⋮ a la derecha de la barra y, al tocar cada opción,
//    lo que hace en tu código (onOptionsItemSelected). Las opciones con ícono y showAsAction van como ícono en la barra.
function menuOpciones(barId,items){var bars=barId?document.querySelectorAll('.phone [data-id="'+barId+'"]'):[];
 if(!bars.length)bars=document.querySelectorAll('.phone .screen>.bar');
 [].forEach.call(bars,function(b){if(b._om)return;b._om=1;var sp=document.createElement('span');sp.style.cssText='margin-left:auto;display:flex;align-items:center';
  var over=items.filter(function(i){return !i.i});
  items.filter(function(i){return i.i}).forEach(function(i){var k=document.createElement('button');k.className='ibtn';k.dataset.h='1';k.style.color='inherit';
   k.innerHTML=(window.VEC&&VEC[i.i])||_icn(i.i)||'●';k.title=i.t;k.addEventListener('click',function(ev){ev.stopPropagation();try{i.f()}catch(x){}});sp.appendChild(k);});
  if(over.length){var k=document.createElement('button');k.className='ibtn';k.textContent='⋮';k.dataset.h='1';k.style.color='inherit';
   k.addEventListener('click',function(ev){ev.stopPropagation();_ancla=k;menu(over.map(function(i){return i.t}),null,0,1);_ancla=null;
    var ovs=document.querySelectorAll('.phone .ov'),ov=ovs[ovs.length-1];if(!ov)return;
    [].forEach.call(ov.querySelectorAll('.ovi'),function(li,n){var c=li.cloneNode(true);li.parentNode.replaceChild(c,li);
     c.addEventListener('click',function(e2){e2.stopPropagation();ov.remove();try{over[n].f()}catch(x){}});});});sp.appendChild(k);}
  b.appendChild(sp);});}
// ⚠️ Lo que no se pudo imitar: aviso arriba de la maqueta
function avisoCodigo(a,b){var n=document.getElementById('codeNote');if(n){n.textContent=T.codeNote+' ('+a+'/'+b+')';n.style.display='block';}}
var H={};var _ancla=null;
// 🔁 Botón que cambia de ícono al tocarlo (▶ ⇄ ⏸, 🔊 ⇄ 🔇…), con su aviso de cada estado
var ICN={play:'▶',pause:'⏸',stop:'⏹',mic:'🎤',mute:'🔇',volume:'🔊',sound:'🔊',fav:'♥',heart:'♥',star:'★',check:'✓',close:'✕',clear:'✕',add:'＋',delete:'🗑',edit:'✎',
 search:'🔍',share:'↗',send:'➤',camera:'📷',lock:'🔒',visibility_off:'🙈',visibility:'👁',light:'☀',dark:'🌙',more:'⋮',menu:'☰',expand:'⌄',collapse:'⌃'};
function _icn(n){n=(n||'').toLowerCase().replace(/^ic_(media_|menu_|action_|baseline_|outline_|round_)?/,'');for(var k in ICN)if(n.indexOf(k)>=0)return ICN[k];return null;}
function alternar(id,a,ta,b,tb){var e=el(id);if(!e)return;e._st=e._st?0:1;var n=e._st?b:a,t=e._st?tb:ta,g=_icn(n);
 if(g){e.textContent=g;}if(t)aviso(t);}
function alClick(id,fn){H[id]=fn;document.querySelectorAll('[data-id="'+id+'"]').forEach(function(e){e.dataset.h='1';if(e._hb)return;e._hb=1;
 e.addEventListener('click',function(ev){ev.stopPropagation();var f=H[id];_ancla=e;if(f)try{f(e)}catch(x){aviso('⚠️ '+x.message)}_ancla=null;});});}
function burbuja(txt,deApp){var a=document.activeElement&&document.activeElement.matches&&document.activeElement.matches('input.inp')?document.activeElement:null;
 var s=document.querySelector('.screen[style*="flex"]');if(!s)return;var i=a||s.querySelector('input.inp');var log=s.querySelector('.chatlog');
 if(!log){log=document.createElement('div');log.className='chatlog';var bar=i?(i.closest('.v[style*="row"]')||i):null;
  if(bar&&bar.parentNode)bar.parentNode.insertBefore(log,bar);else s.appendChild(log);}
 var b=document.createElement('div');b.className='bub'+(deApp?' app':'');b.textContent=txt;log.appendChild(b);log.scrollTop=log.scrollHeight;}
function agregar(lista,txt){var l=typeof lista==='string'?el(lista):lista;if(!l){burbuja(txt);return;}
 var f=l.firstElementChild,n;if(f){n=f.cloneNode(true);var t=n.querySelector('.txt')||n.querySelector('div:last-child');if(t)t.textContent=txt;}
 else{n=document.createElement('div');n.className='row';n.textContent=txt;}
 l.insertBefore(n,l.firstChild);n.scrollIntoView({behavior:'smooth',block:'nearest'});}
function nrm(s){return (s||'').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]/g,'');}
var SYN={inicio:'home feed main inicio principal',perfil:'profile perfil account cuenta',ajustes:'settings ajustes config',
 mensajes:'chat message mensaje inbox',notificaciones:'notif alerta',buscar:'search buscar explore explorar',amigos:'friend amigo follow seguidor',
 publicar:'post publicar crear create new nueva',fotos:'photo foto gallery galeria'};
// 🪟 Una pantalla de tu app como ventana encima (diálogos y hojas de abajo: inflate(R.layout.dialog_x))
function ventana(i,sinFondo,radio){var s=document.getElementById('s'+i);if(!s)return;var o=document.createElement('div');o.className='ov';
 var c=document.createElement('div');c.className='ovc ovd'+(sinFondo?' ovs':'');c.innerHTML=s.innerHTML;o.classList.add('ovpad');
 // (🔲 las esquinas que tu código le pone a la ventana: 0 puntiaguda, 22 redondeada; si no, las del tema)
 if(radio!=null)c.style.borderRadius=radio+'px';
 // (sin ✕: como en Android, la ventana se cierra tocando afuera o con un botón de la ventana)
 c.querySelectorAll('[data-id]').forEach(function(e){var id=e.dataset.id;if(!H[id])return;e.dataset.h='1';
  e.addEventListener('click',function(ev){ev.stopPropagation();try{H[id](e)}catch(z){aviso('⚠️ '+z.message)}});});
 o.addEventListener('click',function(ev){if(ev.target===o)o.remove()});o.appendChild(c);
 (document.querySelector('.phone')||document.body).appendChild(o);setTimeout(function(){fitBtns(c)},0);}
// ☰ Un menú de tu app (PopupMenu, AlertDialog con opciones, menús propios): sus opciones en una ventanita
function menu(op,ti,centro,nativo){var o=document.createElement('div');o.className='ov';var c=document.createElement('div');c.className='ovc ovm'+(nativo?' ovn':'');
 if(nativo&&centro){c.classList.add('ovd');o.classList.add('ovpad');}
 if(ti){var h=document.createElement('div');h.className='ovt';h.textContent=ti;c.appendChild(h);}
 (op||[]).forEach(function(t){var r=document.createElement('div');r.className='ovi';
  if(/^⏻\s*/.test(t)){t=t.replace(/^⏻\s*/,'⏻ ');var sw=document.createElement('input');sw.type='checkbox';sw.className='swi';r.classList.add('swr');
   var sp=document.createElement('span');sp.textContent=t.slice(2);r.appendChild(sp);r.appendChild(sw);
   r.addEventListener('click',function(ev){ev.stopPropagation();if(ev.target!==sw)sw.checked=!sw.checked;aviso(sw.checked?T.on:T.off);});}
  else{r.textContent=t;r.addEventListener('click',function(ev){ev.stopPropagation();o.remove();aviso('👆 '+t);});}
  c.appendChild(r);});
 o.addEventListener('click',function(ev){if(ev.target===o)o.remove()});o.appendChild(c);
 var ph=document.querySelector('.phone');(ph||document.body).appendChild(o);
 // 📍 Como en tu app: el menú sale junto al botón que lo abrió (abajo si el botón está arriba, arriba si está abajo)
 var an=centro?null:_ancla;if(ph&&an&&ph.contains(an)){var pr=ph.getBoundingClientRect(),ar=an.getBoundingClientRect();
  o.style.alignItems='flex-start';o.style.justifyContent='flex-start';c.style.position='absolute';c.style.maxHeight='70%';
  if(ar.left+ar.width/2>pr.left+pr.width/2)c.style.right=Math.max(6,pr.right-ar.right)+'px';else c.style.left=Math.max(6,ar.left-pr.left)+'px';
  if(ar.top+ar.height/2>pr.top+pr.height/2)c.style.bottom=(pr.bottom-ar.top+4)+'px';else c.style.top=(ar.bottom-pr.top+4)+'px';}}
// ✕ Cerrar la ventana de arriba (tu código hace dialogo.dismiss() al tocar un botón de la ventana)
function cerrarVentana(){setTimeout(function(){var o=document.querySelectorAll('.phone .ov');if(o.length)o[o.length-1].remove();},0);}
function abrir(n,v,sinFondo,radio){for(var j=0;j<NAMES.length;j++)if(NAMES[j]===n){if(v)ventana(j,sinFondo,radio);else show(j);return true;}
 var k=nrm(n);if(!k)return false;var keys=[k];for(var s in SYN){if(k.indexOf(s)>=0||SYN[s].split(' ').some(function(w){return k.indexOf(w)>=0}))keys=keys.concat(SYN[s].split(' '));}
 for(var i=0;i<NAMES.length;i++){var y=nrm(NAMES[i]);if(keys.some(function(w){return w.length>2&&y.indexOf(w)>=0})){show(i);return true;}}return false;}
function scr(e){return e.closest('.screen');}
document.addEventListener('click',function(ev){var e=ev.target.closest('.nv,.bn,.fab,.ibtn,.btn,input[type=checkbox]');if(!e||e.dataset.h)return;
 if(e.matches('.nv,.bn')){var t=e.textContent.replace(/^[^\p{L}\p{N}]+/u,'').trim();var d=e.closest('.dl');if(d)d.classList.remove('ol','or');
  if(!(e.dataset.k&&abrir(e.dataset.k))&&!abrir(t))aviso(T.opened+t);return;}
 if(e.matches('input[type=checkbox]')){aviso(e.checked?T.on:T.off);return;}
 var s=scr(e),l=s&&s.querySelector('.lst'),inp=s&&Array.prototype.find.call(s.querySelectorAll('input.inp'),function(i){return i.value.trim()});
 var tx=e.textContent.trim();
 if(e.matches('.fab')||tx==='＋'){if(l){agregar(l,inp?inp.value.trim():T.newItem);if(inp)inp.value='';}else aviso(T.add);return;}
 if(tx==='➤'){if(inp){if(l)agregar(l,inp.value.trim());else burbuja(inp.value.trim());inp.value='';}else aviso(T.writeFirst);return;}
 if(tx==='♥'){e.classList.toggle('liked');return;}
 if(e.matches('.ibtn')&&(tx==='☰'||tx==='⚙'))return;
 if(e.matches('.btn')&&inp){if(l)agregar(l,inp.value.trim());else burbuja(inp.value.trim());inp.value='';return;}
 aviso('👆 '+(tx||T.btn));});
"""

    /**
     * 🎨 Los colores, la forma de los botones y la letra de tu app como variables CSS. Sin tema (solo pegaste 2 archivos),
     * los de siempre de la maqueta. Con tema: primero los de Material 3 / Material 2 / AppCompat (claro u oscuro, como
     * Android) y encima los que tu tema cambia (colorPrimary, colorOnPrimary, android:colorBackground, colorSurface…).
     */
    private fun themeVars(t: ThemeInfo, x: XmlToHtml): String {
        val v = linkedMapOf(
            "bg" to "#121212", "fg" to "#e8e8ea", "fg2" to "#a0a0a8", "btn" to "#3D5AFE", "btnfg" to "#fff", "btnr" to "20px", "btncase" to "none",
            "btntx" to "#8C9EFF", "out" to "#8e8e93", "tonal" to "#2a2a32", "ontonal" to "#e8e8ea", "inpbg" to "#1f1f23", "inpbd" to "#444",
            "card" to "#1e1e22", "line" to "#2a2a2e", "bar" to "#3D5AFE", "barfg" to "#fff", "fab" to "#3D5AFE", "onfab" to "#fff",
            "accent" to "#3D5AFE", "sbg" to "transparent", "font" to "Roboto,'Segoe UI',sans-serif", "btnls" to "0", "btnw" to "500")
        // (sin tema y con pantallas claras: los colores de un tema claro de Android)
        if (!t.known && !t.night) v.putAll(mapOf("bg" to "#FFFFFF", "fg" to "#1C1B1F", "fg2" to "#5F6368", "btntx" to "#3D5AFE", "out" to "#9E9E9E",
            "tonal" to "#E8E8EC", "ontonal" to "#1C1B1F", "inpbg" to "transparent", "inpbd" to "#9E9E9E", "card" to "#FFFFFF", "line" to "#E0E0E0",
            "bar" to "#FFFFFF", "barfg" to "#1C1B1F"))
        if (!t.known) return ":root{" + v.entries.joinToString("") { "--${it.key}:${it.value};" } + "}"
        val light = !t.night
        fun set(vararg kv: Pair<String, String>) = kv.forEach { v[it.first] = it.second }
        when {
            t.m3 && light -> set("bg" to "#FEF7FF", "fg" to "#1D1B20", "fg2" to "#49454F", "btn" to "#6750A4", "btnfg" to "#FFFFFF", "btnr" to "999px",
                "btntx" to "#6750A4", "out" to "#79747E", "tonal" to "#E8DEF8", "ontonal" to "#1D192B", "inpbg" to "transparent", "inpbd" to "#79747E",
                "card" to "#F3EDF7", "line" to "#E7E0EC", "bar" to "#FEF7FF", "barfg" to "#1D1B20", "fab" to "#EADDFF", "onfab" to "#21005D", "accent" to "#6750A4")
            t.m3 -> set("bg" to "#141218", "fg" to "#E6E0E9", "fg2" to "#CAC4D0", "btn" to "#D0BCFF", "btnfg" to "#381E72", "btnr" to "999px",
                "btntx" to "#D0BCFF", "out" to "#938F99", "tonal" to "#4A4458", "ontonal" to "#E8DEF8", "inpbg" to "transparent", "inpbd" to "#938F99",
                "card" to "#211F26", "line" to "#49454F", "bar" to "#141218", "barfg" to "#E6E0E9", "fab" to "#4F378B", "onfab" to "#EADDFF", "accent" to "#D0BCFF")
            t.m2 && light -> set("bg" to "#FFFFFF", "fg" to "#212121", "fg2" to "#757575", "btn" to "#6200EE", "btnfg" to "#FFFFFF", "btnr" to "4px",
                "btncase" to "uppercase", "btntx" to "#6200EE", "out" to "#BDBDBD", "tonal" to "#E0E0E0", "ontonal" to "#212121", "inpbg" to "transparent",
                "inpbd" to "#9E9E9E", "card" to "#FFFFFF", "line" to "#E0E0E0", "bar" to "#6200EE", "barfg" to "#FFFFFF", "fab" to "#03DAC5", "onfab" to "#000000",
                "accent" to "#03DAC5")
            t.m2 -> set("bg" to "#121212", "fg" to "#E0E0E0", "fg2" to "#9E9E9E", "btn" to "#BB86FC", "btnfg" to "#000000", "btnr" to "4px",
                "btncase" to "uppercase", "btntx" to "#BB86FC", "out" to "#5F5F5F", "tonal" to "#2C2C2C", "ontonal" to "#E0E0E0", "inpbg" to "transparent",
                "inpbd" to "#8A8A8A", "card" to "#1E1E1E", "line" to "#2C2C2C", "bar" to "#1F1F1F", "barfg" to "#FFFFFF", "fab" to "#03DAC5", "onfab" to "#000000",
                "accent" to "#03DAC5")
            light -> set("bg" to "#FAFAFA", "fg" to "#212121", "fg2" to "#757575", "btn" to "#D6D7D7", "btnfg" to "#212121", "btnr" to "2px",
                "btncase" to "uppercase", "btntx" to "#009688", "out" to "#BDBDBD", "tonal" to "#E0E0E0", "ontonal" to "#212121", "inpbg" to "transparent",
                "inpbd" to "#9E9E9E", "card" to "#FFFFFF", "line" to "#E0E0E0", "bar" to "#F5F5F5", "barfg" to "#212121", "fab" to "#FF4081", "onfab" to "#FFFFFF",
                "accent" to "#009688")
            else -> set("bg" to "#303030", "fg" to "#FFFFFF", "fg2" to "#B3B3B3", "btn" to "#5A595B", "btnfg" to "#FFFFFF", "btnr" to "2px",
                "btncase" to "uppercase", "btntx" to "#80CBC4", "out" to "#5F5F5F", "tonal" to "#424242", "ontonal" to "#FFFFFF", "inpbg" to "transparent",
                "inpbd" to "#8A8A8A", "card" to "#424242", "line" to "#424242", "bar" to "#212121", "barfg" to "#FFFFFF", "fab" to "#FF4081", "onfab" to "#FFFFFF",
                "accent" to "#80CBC4")
        }
        // 🔤 Letra de los botones como en Android: Material 2 y AppCompat con las letras separadas (0.0892), Material 3 casi juntas
        v["btnls"] = if (t.m3) "0.00714em" else "0.0892em"
        // (lo que tu tema cambia; un color que no se pudo leer se deja como el de Material)
        fun c(k: String) = t.item(k)?.let { x.color(it) }?.takeIf { !it.startsWith("var(") }
        c("colorPrimary")?.let { set("btn" to it, "btntx" to it); if (!t.m3) v["bar"] = it; if (t.m3) v["accent"] = it }
        c("colorOnPrimary")?.let { set("btnfg" to it); if (!t.m3) v["barfg"] = it }
        c("colorButtonNormal")?.let { if (!t.m3 && !t.m2) v["btn"] = it }
        (c("colorSecondary") ?: c("colorAccent"))?.let { if (!t.m3) set("accent" to it, "fab" to it) }
        c("colorOnSecondary")?.let { if (!t.m3) v["onfab"] = it }
        (c("colorBackground") ?: c("colorSurface"))?.let { set("bg" to it); if (t.m3) v["bar"] = it }
        c("windowBackground")?.let { v["bg"] = it }
        c("colorSurface")?.let { v["card"] = it }
        (c("textColorPrimary") ?: c("colorOnBackground") ?: c("colorOnSurface"))?.let { set("fg" to it); if (t.m3) v["barfg"] = it }
        c("textColorSecondary")?.let { v["fg2"] = it }
        c("colorOutline")?.let { set("out" to it, "inpbd" to it) }
        c("colorPrimaryContainer")?.let { if (t.m3) v["fab"] = it }
        c("colorOnPrimaryContainer")?.let { if (t.m3) v["onfab"] = it }
        c("colorSecondaryContainer")?.let { v["tonal"] = it }
        c("colorOnSecondaryContainer")?.let { v["ontonal"] = it }
        // Fondo de ventana con un dibujo (degradado, imagen): detrás de cada pantalla
        t.item("windowBackground")?.takeIf { it.startsWith("@drawable/") || it.startsWith("@mipmap/") }?.let { ref ->
            x.shapeCss(ref)?.let { css ->
                Regex("background:([^;]+);").find(css)?.groupValues?.get(1)?.let { v["sbg"] = it }
            }
            ref.substringAfter('/').takeIf { projectAssets.containsKey(it) }?.let { v["sbg"] = "url(${assetUrl(it)}) center/cover no-repeat" }
        }
        // 🔲 Esquinas de las ventanas que pide tu tema (puntiagudas «0dp» o redondeadas «24dp»):
        //    materialAlertDialogTheme → alertDialogStyle → shapeAppearance → cornerSize, o shapeAppearanceCornerExtraLarge (M3),
        //    shapeAppearanceMediumComponent (M2) o dialogCornerRadius. Sin nada de eso, quedan las del tema (M2 4, AppCompat 2, M3 28).
        run {
            fun corner(ref: String?): String? = ref?.let { r ->
                x.book.resolve(r).first.entries.firstOrNull { it.key.substringAfter(':') == "cornerSize" }?.value }
            val dlgTheme = t.item("materialAlertDialogTheme")?.let { x.book.resolve(it).first } ?: emptyMap()
            val dlgStyle = (dlgTheme["alertDialogStyle"] ?: dlgTheme["materialAlertDialogStyle"] ?: t.item("alertDialogStyle"))
                ?.let { x.book.resolve(it).first } ?: emptyMap()
            val raw = listOf(dlgStyle["shapeAppearanceOverlay"], dlgStyle["shapeAppearance"], dlgTheme["shapeAppearanceOverlay"],
                    t.item("shapeAppearanceCornerExtraLarge")?.takeIf { t.m3 }, t.item("shapeAppearanceMediumComponent")?.takeIf { !t.m3 })
                .firstNotNullOfOrNull { corner(it) } ?: dlgTheme["dialogCornerRadius"] ?: dlgTheme["android:dialogCornerRadius"] ?: t.item("dialogCornerRadius")
            raw?.trim()?.let { r ->
                Regex("^(-?[0-9.]+)\\s*(dp|dip|px|sp)?$").find(r)?.let { v["dlgr"] = it.groupValues[1] + "px" }
                    ?: r.takeIf { it.endsWith("%") }?.let { v["dlgr"] = it }
            }
        }
        // 🔤 La letra del tema (res/font)
        (t.item("fontFamily"))?.takeIf { it.startsWith("@font/") }?.removePrefix("@font/")?.let { f ->
            if (projectFonts.containsKey(f)) v["font"] = "'f_$f',Roboto,sans-serif"
        }
        return ":root{" + v.entries.joinToString("") { "--${it.key}:${it.value};" } + "}"
    }

    fun buildAppMockup(messagesNewestFirst: List<String>): Pair<String, String>? {
        val layouts = LinkedHashMap<String, String>()
        val strings = HashMap<String, String>()
        val colors = HashMap<String, String>()
        val shapes = HashMap<String, String>()   // fondos redondeados (res/drawable/*.xml con <shape>)
        val navs = LinkedHashMap<String, String>()   // 🧭 res/navigation/*.xml (qué fragmento se ve primero)
        val menus = HashMap<String, String>()        // ☰ res/menu/*.xml (opciones del menú lateral y de la barra de abajo)
        val vectors = HashMap<String, String>()      // 🎨 íconos dibujados (res/drawable/*.xml con <vector>)
        val drawSel = HashMap<String, String>()      // 🎨 selectores de fondo: el fondo normal (sin tocar)
        val styles = HashMap<String, Pair<String?, Map<String, String>>>()   // 🎨 estilos y temas (themes.xml, styles.xml)
        val fontAlias = HashMap<String, String>()    // 🔤 res/font/*.xml (familia) → su primera fuente
        val dimens = HashMap<String, String>()       // 📏 res/values/dimens.xml (@dimen/margen…)
        var manifest: String? = null
        val nightBlocks = mutableListOf<CodeBlock>() // 🌙 values-night, drawable-night: solo si la app se ve oscura
        var n = 1
        fun handle(b: CodeBlock) {
            val c = b.code
            val base = b.fileName?.substringBeforeLast('.')
            when {
                c.contains("<manifest") -> manifest = c
                Regex("<vector[\\s>]").containsMatchIn(c) && base != null -> vectors[base] = c
                Regex("<font-family[\\s>]").containsMatchIn(c) && base != null ->
                    Regex("(?:android|app):font=\"@font/([\\w.]+)\"").find(c)?.groupValues?.get(1)?.let { fontAlias[base] = it }
                Regex("<selector[\\s>]").containsMatchIn(c) && !c.contains("<shape") && base != null -> {
                    // (el estado normal: el último <item> sin «state_»)
                    val items = Regex("<item\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL).findAll(c).map { it.value }.toList()
                    val def = items.lastOrNull { !it.contains("state_") } ?: items.lastOrNull()
                    def?.let { Regex("android:color=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { colors[base] = it }
                    def?.let { Regex("android:drawable=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { drawSel[base] = it }
                }
                c.contains("<shape") && base != null -> shapes[base] = c
                c.contains("<navigation") -> navs[base ?: "nav_${n++}"] = c
                Regex("<menu[\\s>]").containsMatchIn(c) && !viewRoots.containsMatchIn(c) -> menus[base ?: "menu_${n++}"] = c
                c.contains("<resources") -> {
                    Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(c)
                        .forEach { strings[it.groupValues[1]] = it.groupValues[2].replace("\\'", "'").replace("\\n", " ").trim() }
                    Regex("<color\\s+name=\"([^\"]+)\"[^>]*>(.*?)</color>").findAll(c)
                        .forEach { colors[it.groupValues[1]] = it.groupValues[2].trim() }
                    Regex("<dimen\\s+name=\"([^\"]+)\"[^>]*>(.*?)</dimen>").findAll(c)
                        .forEach { dimens[it.groupValues[1]] = it.groupValues[2].trim() }
                    // 🎨 <style name="…" parent="…"> <item name="colorPrimary">…</item> </style>  (también <style … />)
                    Regex("<style\\s+name=\"([^\"]+)\"([^>]*?)(?:/>|>(.*?)</style>)", RegexOption.DOT_MATCHES_ALL).findAll(c).forEach { m ->
                        val parent = Regex("parent=\"([^\"]*)\"").find(m.groupValues[2])?.groupValues?.get(1)
                            ?.removePrefix("@style/")?.removePrefix("@android:style/")?.takeIf { it.isNotBlank() }
                        val items = LinkedHashMap<String, String>()
                        Regex("<item\\s+name=\"([^\"]+)\"[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[3])
                            .forEach { items[it.groupValues[1]] = it.groupValues[2].trim() }
                        styles[m.groupValues[1]] = parent to items
                    }
                }
                viewRoots.containsMatchIn(c) -> {
                    val name = base ?: "pantalla_${n++}"
                    layouts.remove(name); layouts[name] = c
                }
            }
        }
        // Del más viejo al más nuevo, para que la versión nueva de un archivo reemplace a la vieja
        for (msg in messagesNewestFirst.asReversed()) for (b in codeBlocks(msg)) {
            val c = b.code
            if (!(b.lang == "xml" || c.trimStart().startsWith("<"))) continue
            // 📁 Variantes de un archivo según la carpeta: values-night (modo oscuro) se guarda aparte; values-es,
            //    layout-land, drawable-sw600dp… son para otro idioma o pantalla y no se mezclan con la normal
            val folder = b.path?.substringBeforeLast('/', "")?.substringAfterLast('/').orEmpty()
            val quals = folder.substringAfter('-', "").split('-').filter { it.isNotEmpty() && !Regex("v\\d+").matches(it) && it != "anydpi" && it != "nodpi" }
            when {
                quals.isEmpty() -> handle(b)
                quals == listOf("night") -> nightBlocks += b
                else -> {}
            }
        }
        // 🎨 El tema de la app (el del AndroidManifest; si no, el primero «Theme.…» / «AppTheme»)
        val themeName = manifest?.let { Regex("<application\\b[^>]*?android:theme=\"@style/([\\w.]+)\"", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1) }
            ?.takeIf { styles.containsKey(it) }
            ?: styles.keys.filter { Regex("^(Theme|AppTheme|Base\\.Theme)").containsMatchIn(it) && !it.contains("Splash") }.minByOrNull { it.length }
        var theme = ThemeInfo.of(themeName, StyleBook(styles), phoneNight)
        // 🌙 App oscura: se usan sus colores y estilos de values-night
        if (theme.known && theme.night && nightBlocks.isNotEmpty()) {
            nightBlocks.forEach { handle(it) }
            theme = ThemeInfo.of(themeName, StyleBook(styles), phoneNight)
        }
        if (layouts.isEmpty()) return null
        // 🌗 Sin tema (pegaste solo algunos archivos): si el fondo de la pantalla principal es claro, la app es clara
        //    (letras oscuras en la barra y los textos); si es oscuro, oscura. Como lo vería Android con un tema normal.
        if (!theme.known) {
            val main = layouts.entries.sortedBy { if (it.key.startsWith("activity")) 0 else 1 }.firstOrNull()?.value
            var bg = main?.let { Regex("android:background=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            var guard = 0
            while (bg != null && bg.startsWith("@color/") && guard++ < 4) bg = colors[bg.removePrefix("@color/")]
            val hex = bg?.removePrefix("#")?.takeIf { Regex("^[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(it) }?.takeLast(6)
            if (hex != null) {
                val r = hex.substring(0, 2).toInt(16); val g = hex.substring(2, 4).toInt(16); val b = hex.substring(4, 6).toInt(16)
                val light = (0.299 * r + 0.587 * g + 0.114 * b) > 140
                theme = ThemeInfo(false, !light, false, false, false, emptyMap(), "")
            }
        }
        // Pantallas principales primero
        val ordered = layouts.entries.sortedBy { if (it.key.startsWith("activity")) 0 else if (it.key.startsWith("fragment")) 1 else 2 }
        val tabs = StringBuilder(); val screens = StringBuilder()
        val x = XmlToHtml(strings, colors, layouts, shapes, navs, menus, vectors, drawSel, StyleBook(styles), theme, fontAlias, dimens)
        // 🎨 Barra de título que pone Android solo cuando el tema de la app la tiene (los temas «NoActionBar» no)
        val appName = esc(strings["app_name"] ?: tx("Mi app"))
        ordered.forEachIndexed { i, (name, xml) ->
            tabs.append("<button class='tab${if (i == 0) " on" else ""}' onclick='show($i)'>${esc(name)}</button>")
            val body = runCatching { x.convert(xml, 0) }
                .getOrElse { "<div class='err'>${esc(tx("No pude dibujar este diseño (el XML tiene un error):"))}<br>${esc(it.message ?: "")}</div>" }
            val bar = if (theme.actionBar && name.startsWith("activity") && !Regex("Toolbar|AppBarLayout|ActionBar").containsMatchIn(xml))
                "<div class='v bar' style='display:flex;align-items:center;gap:10px'>$appName</div>" else ""
            screens.append("<div class='screen' id='s$i' style='display:${if (i == 0) "flex" else "none"}'>$bar$body</div>")
        }
        // 🎨 Los colores, la forma de los botones y la letra de TU app (su tema); sin tema, los de siempre de la maqueta
        val themeCss = themeVars(theme, x)
        // 🔤 Las fuentes de tu proyecto (res/font)
        val fontCss = projectFonts.keys.joinToString("") { k -> "@font-face{font-family:'f_$k';src:url(${fontUrl(k)})}" }
        val namesJs = ordered.joinToString(",", "[", "]") { "'" + it.key.replace("'", "") + "'" }
        // 🧪 Lógica de la maqueta escrita por la IA (maqueta.js): se ejecuta encima de la interactividad básica
        val logic = mockupLogic(messagesNewestFirst)
        val userJs = logic.joinToString("\n") { js0 ->
            val js = js0   // (los avisos y textos de tu app tal como los escribiste: no se traducen)
            "try{\n" + js.replace("</script", "<\\/script") + "\n}catch(e){aviso(T.logicErr+e.message)}" }
        // 🖼️ Los dibujos de tu proyecto que maqueta.js agrega por código (vistaImg): se mandan ya convertidos
        val vecJs = Regex("vistaImg\\('[^']*','[^']*','(\\w+)'").findAll(userJs).map { it.groupValues[1] }.distinct()
            .mapNotNull { n -> runCatching { x.drawableHtml("@drawable/$n", "currentColor", "100%", "100%") }.getOrNull()?.takeIf { it.startsWith("<svg") }?.let { jsStr(n) + ":" + jsStr(it) } }
            .joinToString(",", "{", "}")
        val noteText = esc(tx(if (logic.isNotEmpty()) "📱 Maqueta con la lógica de tu app (simulada en JavaScript). La app real usa el Kotlin al compilarla."
            else "📱 Maqueta interactiva: menús, botones y listas responden como ejemplo. La lógica Kotlin real funciona al compilar la app."))
        // 🌍 Los avisos de la maqueta en tu idioma
        val textsJs = "var T={opened:" + jsStr(tx("📂 Abriste:") + " ") + ",on:" + jsStr(tx("✅ Activado")) + ",off:" + jsStr(tx("⬜ Desactivado")) +
            ",newItem:" + jsStr(tx("✨ Nuevo elemento")) + ",add:" + jsStr(tx("＋ Agregar")) + ",writeFirst:" + jsStr(tx("✍️ Escribe algo primero")) +
            ",btn:" + jsStr(tx("Botón")) + ",logicErr:" + jsStr(tx("⚠️ La lógica de la maqueta tuvo un error:") + " ") +
            ",codeNote:" + jsStr(tx(CODE_NOTE)) + "};"
        // 📐 La familia de componentes de tu tema (Material 3, Material 2, AppCompat): cada una tiene sus medidas en Android
        val themeClass = (when { !theme.known -> "th-x"; theme.m3 -> "th-m3"; theme.m2 -> "th-m2"; else -> "th-ac" }) + if (theme.night) " nt" else ""
        val page = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 $themeCss
 $fontCss
 body{margin:0;background:${chrome("#0b0b0d", "#F2F2F7")};font-family:Roboto,'Segoe UI',sans-serif;color:${chrome("#e8e8ea", "#1C1C1E")}}
 .tabs{display:flex;gap:6px;overflow-x:auto;padding:10px;background:${chrome("#141417", "#F2F2F7")};position:sticky;top:0;z-index:5}
 .tab{border:1px solid ${chrome("#3a3a3e", "#C7C7CC")};background:${chrome("#1d1d21", "#FFFFFF")};color:${chrome("#ddd", "#1C1C1E")};border-radius:16px;padding:6px 12px;font-size:13px;white-space:nowrap}
 .tab.on{background:#3D5AFE;border-color:#3D5AFE;color:#fff}
 .phone{margin:12px auto;width:min(380px,94vw);min-height:640px;background:var(--bg);color:var(--fg);font-family:var(--font);border:8px solid #222;border-radius:28px;overflow:hidden;position:relative}
 .screen{flex-direction:column;min-height:624px;position:relative;background:var(--sbg)}
 .v{box-sizing:border-box;min-width:0}
 .txt{padding:2px 0;font-size:14px}
 .btn{letter-spacing:var(--btnls,0);font-weight:var(--btnw,500);background:var(--btn);color:var(--btnfg);border:none;border-radius:var(--btnr);text-transform:var(--btncase);font-family:inherit;padding:10px 16px;font-size:14px;margin:4px 0;overflow:hidden;line-height:1.15;overflow-wrap:normal}
 .btn.out{background:transparent;color:var(--btntx);border:1px solid var(--out)} .btn.txb{background:transparent;color:var(--btntx)}
 .btn.tonal{background:var(--tonal);color:var(--ontonal)} .dic{display:inline-flex;vertical-align:middle;margin:0 6px}
 .inp::placeholder{color:var(--ph,#8e8e93)}
 .inp{background:var(--inpbg);border:1px solid var(--inpbd);border-radius:8px;color:var(--fg);font-family:inherit;padding:10px;font-size:14px;margin:4px 0}
 .img{background:#2a2a30;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#888;min-height:48px;min-width:48px}
 .card{background:var(--card);border-radius:14px;padding:12px;margin:6px 0;box-shadow:0 2px 6px #0006}
 .bar{background:var(--bar);color:var(--barfg);padding:14px 16px;font-size:18px;font-weight:600}
 .row{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--line)}
 .fab{position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:16px;background:var(--fab);color:var(--onfab);border:none;font-size:24px}
 .err{color:#ff8a80;padding:16px}
 .vf>:not(:first-child){display:none!important}
 .ov{position:absolute;inset:0;background:#000a;z-index:12;display:flex;align-items:center;justify-content:center;padding:14px;box-sizing:border-box}
 .ovc{background:#1c1c20;border-radius:18px;max-height:92%;overflow:auto;width:100%;position:relative;box-shadow:0 8px 30px #000c;display:flex;flex-direction:column}
 /* (en una app clara, las ventanas y menús son claros con letra oscura, como en Android) */
 body:not(.nt) .ovc{background:#FFFFFF;color:#1C1B1F;box-shadow:0 6px 24px rgba(0,0,0,.25)} body:not(.nt) .ovi:active{background:#EEE}
 body:not(.nt) .ov{background:rgba(0,0,0,.32)}
 .ovm{width:auto;min-width:62%;max-width:92%;padding:8px 0}
 /* 📐 Menús y ventanas de Android (PopupMenu, AlertDialog, diálogos con su diseño XML) con la forma de su tema:
    Material 2 esquinas de 4 dp, AppCompat 2 dp, Material 3 ventanas de 28 dp y menús de 4 dp; los menús de Android
    son compactos (de 112 a 280 dp de ancho, opciones de 48 dp de alto). Los que tu código arma a mano conservan su forma. */
 .ov.ovpad{padding:24px}
 .ovc.ovd{border-radius:var(--dlgr,4px)} .th-ac .ovc.ovd{border-radius:var(--dlgr,2px)} .th-m3 .ovc.ovd{border-radius:var(--dlgr,28px)}
 .ovc.ovs{background:transparent!important;box-shadow:none!important;border-radius:0!important;overflow:visible}
 .ovm.ovn:not(.ovd){border-radius:4px;min-width:112px;max-width:280px} .th-ac .ovm.ovn:not(.ovd){border-radius:2px}
 .ovm.ovn .ovi{padding:0 16px;min-height:48px;display:flex;align-items:center;font-size:16px} .th-m3 .ovm.ovn .ovi{font-size:14px} .ovi{padding:12px 20px;font-size:15px;cursor:pointer} .ovi:active{background:#2a2a30}
 .ovt{padding:10px 20px 6px;font-weight:700;color:#9aa4ff;font-size:15px}
 .ovx{position:sticky;top:6px;align-self:flex-end;margin:6px 6px -38px;z-index:2;background:#2c2c32;color:#fff;border:none;border-radius:50%;width:32px;height:32px;min-height:32px;cursor:pointer}
 .ibtn{background:transparent;border:none;color:var(--fg);width:44px;height:44px;min-width:44px;border-radius:50%;font-size:20px;display:flex;align-items:center;justify-content:center;flex:none;padding:0}
 .dl{flex:1;min-height:624px}
 /* 📏 En un teléfono, la maqueta usa todo el ancho de la pantalla (sin el marco): así los botones y textos ocupan lo mismo
    que en tu app y se cortan en las mismas palabras (con el marco era más angosta y algunos botones salían más grandes) */
 @media (max-width:460px) and (orientation:portrait){.phone{width:100%;margin:8px 0 0;border-width:0;border-radius:0}}
 /* 📱↔️ Teléfono de lado: la maqueta también se ve horizontal (como tu app con la pantalla girada) */
 @media (orientation:landscape){.phone{width:min(780px,94vw);min-height:360px}.screen,.dl{min-height:344px}}
 .drawer{position:absolute;top:0;bottom:0;max-width:86%;z-index:6;transition:transform .22s ease-out;will-change:transform;contain:layout paint;box-shadow:0 0 10px #0008;overflow:auto;align-self:stretch!important;height:auto!important;display:flex;flex-direction:column;background:var(--bg)}
 .dl-left{left:0;transform:translate3d(-105%,0,0)} .dl-right{right:0;transform:translate3d(105%,0,0)}
 .dl.ol>.dl-left,.dl.or>.dl-right{transform:translate3d(0,0,0)}
 .scrim{position:absolute;inset:0;background:#0008;z-index:5;display:none} .dl.ol>.scrim,.dl.or>.scrim{display:block}
 .note{font-size:12px;color:${chrome("#8e8e93", "#6E6E73")};text-align:center;padding:4px 12px 16px}
 .note.warn{color:${chrome("#ffcc80", "#B35C00")};padding:8px 14px 0}
 .bar .hamb{display:none;color:var(--barfg);width:32px;height:32px;min-width:32px} .dl .bar .hamb{display:flex}
 #toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#2c2c32;color:#fff;padding:10px 16px;border-radius:18px;
  font-size:13px;opacity:0;transition:opacity .25s;z-index:20;max-width:80vw;text-align:center;box-shadow:0 4px 14px #000a;pointer-events:none}
 .liked{color:#ff4d6d!important}
 .chatlog{display:flex;flex-direction:column;gap:6px;padding:8px;overflow:auto;flex:1;min-height:60px}
 .bub{align-self:flex-end;background:#3D5AFE;color:#fff;border-radius:16px 16px 4px 16px;padding:8px 12px;max-width:75%;font-size:14px;word-wrap:break-word}
 .bub.app{align-self:flex-start;background:#2a2a30;border-radius:16px 16px 16px 4px} .nv,.bn,.btn,.ibtn,.fab{cursor:pointer}
 .swr{display:flex!important;align-items:center;justify-content:space-between;gap:12px;min-height:48px;cursor:pointer}
 .swi{appearance:none;-webkit-appearance:none;width:52px;height:30px;border-radius:999px;background:#555;position:relative;flex:none;margin:0;cursor:pointer;transition:background .2s}
 .swi::after{content:'';position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#ddd;transition:transform .2s,background .2s}
 .swi:checked{background:#5a5a5a} .swi:checked::after{transform:translateX(22px);background:var(--accent)}
 /* 📐 Los componentes con las medidas de Android (Material 3, Material 2 / AppCompat), para cualquier app */
 .btn{display:inline-flex;align-items:center;justify-content:center;text-align:center}
 .th-m2 .btn,.th-ac .btn,.th-x .btn{min-height:36px;padding:6px 16px;margin:6px 0}
 .th-m3 .btn{min-height:40px;padding:6px 24px;margin:4px 0}
 .th-m2 .btn.txb,.th-ac .btn.txb{padding:6px 8px} .th-m3 .btn.txb{padding:6px 12px}
 .th-m2 .fab,.th-ac .fab,.th-x .fab{border-radius:50%}
 .th-m2 .card,.th-x .card{border-radius:4px} .th-ac .card{border-radius:2px} .th-m3 .card{border-radius:12px}
 .card{padding:0;margin:0;box-shadow:0 1px 3px rgba(0,0,0,.3)}
 .bar{min-height:56px;box-sizing:border-box;display:flex;align-items:center;padding:0 16px;font-size:20px;font-weight:500}
 .th-m3 .bar{font-size:22px;font-weight:400}
 .bnv{min-height:56px} .bnv .bn{font-size:12px;gap:2px} .bnv .bn>span{font-size:24px!important;line-height:24px}
 .th-m3 .bnv{min-height:80px} .th-m3 .bnv .bn{font-size:12px;font-weight:500}
 .th-m3 .bnv .bn:first-child>span{background:var(--tonal);border-radius:16px;padding:4px 20px}
 .inp{border:none;border-bottom:1px solid var(--inpbd);border-radius:0;background:transparent;min-height:48px;padding:10px 4px}
 .til.out .inp{border:1px solid var(--out);border-radius:4px;min-height:56px;padding:16px}
 .til.fill .inp{border:none;border-bottom:1px solid var(--inpbd);border-radius:4px 4px 0 0;min-height:56px;padding:16px;background:rgba(127,127,127,.12)}
 select.inp{appearance:none;-webkit-appearance:none;border:none;padding-right:28px;background:transparent url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%23888'/%3E%3C/svg%3E") no-repeat right 8px center}
 .chip{display:inline-flex;align-items:center;min-height:32px;padding:0 12px;border-radius:16px;background:rgba(127,127,127,.2);font-size:14px;margin:4px 4px 4px 0}
 .th-m3 .chip{border-radius:8px;background:transparent;border:1px solid var(--out)}
 .pgc{width:48px;height:48px;border-radius:50%;border:4px solid transparent;border-top-color:var(--accent);border-right-color:var(--accent);box-sizing:border-box;animation:pgr 1s linear infinite;flex:none}
 @keyframes pgr{to{transform:rotate(360deg)}}
 progress.v{accent-color:var(--accent);height:4px;width:100%}
 input[type=checkbox]:not(.swi),input[type=radio]{accent-color:var(--accent);width:18px;height:18px;margin:0 8px 0 4px;flex:none}
 label.txt:not(.swr){min-height:48px}
 /* Interruptor de Material 2 / AppCompat (pista delgada y botoncito redondo); el de Material 3 es el de arriba */
 .th-m2 .swi,.th-ac .swi,.th-x .swi{width:36px;height:20px;background:transparent;overflow:visible}
 .th-m2 .swi::before,.th-ac .swi::before,.th-x .swi::before{content:'';position:absolute;left:0;top:3px;width:36px;height:14px;border-radius:7px;background:rgba(0,0,0,.38)}
 .nt.th-m2 .swi::before,.nt.th-ac .swi::before,.nt.th-x .swi::before{background:rgba(255,255,255,.3)}
 .th-m2 .swi::after,.th-ac .swi::after,.th-x .swi::after{top:0;left:0;width:20px;height:20px;background:#FAFAFA;box-shadow:0 1px 3px rgba(0,0,0,.4)}
 .nt.th-m2 .swi::after,.nt.th-ac .swi::after,.nt.th-x .swi::after{background:#BDBDBD}
 .th-m2 .swi:checked,.th-ac .swi:checked,.th-x .swi:checked{background:transparent}
 .th-m2 .swi:checked::before,.th-ac .swi:checked::before,.th-x .swi:checked::before{background:var(--accent);opacity:.54}
 .th-m2 .swi:checked::after,.th-ac .swi:checked::after,.th-x .swi:checked::after{transform:translateX(16px);background:var(--accent)}
 /* Material 3: pista de 52×32 con borde cuando está apagado, botoncito chico (16) apagado y grande (24) encendido */
 .th-m3 .swi{width:52px;height:32px;background:var(--tonal);border:2px solid var(--out);box-sizing:border-box}
 .th-m3 .swi::after{top:6px;left:6px;width:16px;height:16px;background:var(--out)}
 .th-m3 .swi:checked{background:var(--accent);border-color:var(--accent)} .th-m3 .swi:checked::after{top:2px;left:2px;width:24px;height:24px;transform:translateX(20px);background:var(--btnfg)}
</style></head><body class='$themeClass'>
<div class="tabs">$tabs</div>
<div id="codeNote" class="note warn" style="display:none"></div>
<div class="phone">$screens</div>
<div class="note">$noteText</div><div id="toast"></div>
<script>
function dr(e,s){var d=e.closest('.dl');if(!d)return;d.classList.remove('ol','or');if(s){d.classList.add(s);
 var p=d.querySelector(s=='ol'?'.dl-left':'.dl-right');if(p&&!p._fit){p._fit=1;setTimeout(function(){fitBtns(p)},240);}
 setTimeout(function(){(window.IGUALAR||[]).forEach(function(f){f()})},300);}}
// 🧩 ConstraintLayout: lo que se alinea arriba/abajo con otro elemento va en la MISMA fila; el orden de izquierda a
//    derecha sale de sus anclas (pegado al borde izquierdo, a la derecha de…, a la izquierda de…, pegado al borde derecho)
function clLayout(){document.querySelectorAll('.cl').forEach(function(c){if(c._done)return;c._done=1;
 var kids=[].slice.call(c.children).filter(function(e){return e.dataset&&e.dataset.cl});if(!kids.length)return;
 var byId={};kids.forEach(function(e){if(e.dataset.id)byId[e.dataset.id]=e});
 var up=new Map();kids.forEach(function(e){up.set(e,e)});
 function f(e){while(up.get(e)!==e)e=up.get(e);return e} function u(a,b){a=f(a);b=f(b);if(a!==b)up.set(b,a)}
 kids.forEach(function(e){var d=e.dataset;
  [d.tt,d.bb].forEach(function(r){if(r&&r!=='parent'&&byId[r])u(byId[r],e)});
  if(!d.tt&&!d.bb&&!d.tb&&!d.bt)[d.se,d.es].forEach(function(r){if(r&&r!=='parent'&&byId[r])u(byId[r],e)});});
 var groups=new Map();kids.forEach(function(e){var r=f(e);if(!groups.has(r))groups.set(r,[]);groups.get(r).push(e)});
 groups.forEach(function(g){
  if(g.length<2){var e=g[0],d=e.dataset;
   if(d.w0){e.style.alignSelf='stretch';e.style.width='auto'}
   else if(d.ss==='parent'&&d.ee==='parent')e.style.alignSelf='center';
   else if(d.ee==='parent'&&!d.ss&&!d.se)e.style.alignSelf='flex-end';
   return}
  var memo=new Map();
  function key(e,depth){if(memo.has(e))return memo.get(e);if(depth>20)return 0;var d=e.dataset,k=0;
   if(d.se&&byId[d.se]&&g.indexOf(byId[d.se])>=0)k=key(byId[d.se],depth+1)+1;
   else if(d.ss||!(d.ee||d.es))k=0;
   else if(d.es&&byId[d.es]&&g.indexOf(byId[d.es])>=0)k=key(byId[d.es],depth+1)-1;
   else k=1000;memo.set(e,k);return k}
  var order=g.slice().sort(function(a,b){return key(a,0)-key(b,0)||g.indexOf(a)-g.indexOf(b)});
  var row=document.createElement('div');row.className='v clrow';
  row.style.cssText='display:flex;flex-direction:row;align-items:center;width:100%';
  c.insertBefore(row,g[0]);
  order.forEach(function(e){row.appendChild(e);e.style.alignSelf='center';
   if(e.dataset.w0){e.style.flex='1 1 0';e.style.minWidth='0'}});
  // (lo pegado al borde derecho queda a la derecha aunque sobre espacio)
  var firstRight=order.filter(function(e){return key(e,0)>=500})[0]||order.filter(function(e){return key(e,0)<0})[0];
  if(firstRight&&!order.some(function(e){return e.dataset.w0}))firstRight.style.marginLeft='auto';
 });});}
// 📏 Como en la app real (autoSize): el nombre del botón SIEMPRE queda dentro; si no cabe (otro idioma, palabra larga),
//    puede usar 2 líneas y la letra se achica hasta que entre (mínimo 9 px)
// ⚡ Solo los botones de lo que se ve (la pantalla abierta o la ventana) y solo los que NO caben: primero se miden
//    todos de una vez y después se achican esos pocos (antes se medía cada botón de todas las pantallas, uno por uno)
function fitBtns(root){var sc=(root&&root.querySelectorAll)?root:(document.querySelector('.screen[style*="flex"]')||document);
 var list=[].slice.call(sc.querySelectorAll('.btn,.fab'));
 var over=list.filter(function(b){var w=b.clientWidth;return w&&(b.scrollWidth>w+1||b.scrollHeight>b.clientHeight+1)});
 over.forEach(function(b){if(b._fs0===undefined)b._fs0=parseFloat(getComputedStyle(b).fontSize)||14;var fs=b._fs0;
  var ls=parseFloat(getComputedStyle(b).letterSpacing)||0;
  while(fs>9&&(b.scrollWidth>b.clientWidth+1||b.scrollHeight>b.clientHeight+1)){
   if(ls>0){ls=0;b.style.letterSpacing='0';continue;}fs-=1;b.style.fontSize=fs+'px';}});}
var _fitT;function fitLater(){clearTimeout(_fitT);_fitT=setTimeout(fitBtns,120);}
function show(i){document.querySelectorAll('.screen').forEach(function(s,k){s.style.display=k==i?'flex':'none'});setTimeout(fitBtns,0);
 document.querySelectorAll('.tab').forEach(function(t,k){t.classList.toggle('on',k==i)});}
$textsJs
$INTERACTIVE_JS
var NAMES=$namesJs;
var NOCHE=${theme.night};
var VEC=$vecJs;
$userJs
try{clLayout()}catch(e){}
fitBtns();window.addEventListener('load',function(){fitBtns()});window.addEventListener('resize',fitLater);
</script></body></html>"""
        val code = ordered.joinToString("\n\n") { "<!-- ${it.key}.xml -->\n${it.value}" }
        return page to code
    }

    /**
     * ✂️ Un diseño XML que llegó cortado (al modelo se le acabó el espacio): se quita la etiqueta a medias
     * del final y se cierran las que quedaron abiertas, para que se pueda dibujar igual.
     */
    fun repairXml(xml: String): String {
        var s = xml.trimEnd()
        val lt = s.lastIndexOf('<'); val gt = s.lastIndexOf('>')
        if (lt > gt) s = s.substring(0, lt).trimEnd()
        val stack = ArrayDeque<String>()
        Regex("<!--.*?-->|<\\?.*?\\?>|<!\\[CDATA\\[.*?]]>|<(/?)([A-Za-z_][\\w.:-]*)((?:[^<>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>",
            RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            val name = m.groupValues[2]
            if (name.isEmpty()) return@forEach
            if (m.groupValues[1] == "/") {
                val idx = stack.lastIndexOf(name)
                if (idx >= 0) while (stack.size > idx) stack.removeLast()
            } else if (m.groupValues[4] != "/") stack.addLast(name)
        }
        val sb = StringBuilder(s)
        while (stack.isNotEmpty()) sb.append("\n</").append(stack.removeLast()).append(">")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&#39;").replace("\"", "&quot;")

    /**
     * 🎨 Los estilos y temas de tu app (themes.xml, styles.xml): cada estilo con sus ítems y su padre.
     * [resolve] junta los ítems del estilo con los de sus padres (el hijo manda) y dice de qué estilo de Android viene
     * (Theme.Material3.DayNight, Widget.MaterialComponents.Button.OutlinedButton…).
     */
    private class StyleBook(val styles: Map<String, Pair<String?, Map<String, String>>>) {
        fun resolve(ref: String): Pair<Map<String, String>, String> {
            var name: String? = ref.trim().removePrefix("@style/").removePrefix("@android:style/").removePrefix("?attr/").removePrefix("?")
            val chain = mutableListOf<Map<String, String>>()
            var platform = ""
            var guard = 0
            while (name != null && guard++ < 15) {
                val st = styles[name]
                if (st == null) { platform = name; break }
                chain += st.second
                // (sin parent: «Boton.Grande» hereda de «Boton» si ese estilo existe)
                name = st.first ?: name.substringBeforeLast('.', "").takeIf { it.isNotEmpty() && styles.containsKey(it) }
            }
            val out = LinkedHashMap<String, String>()
            chain.asReversed().forEach { out.putAll(it) }
            return out to platform
        }
    }

    /** 🎨 El tema de tu app: claro u oscuro, Material 3 / Material 2 / AppCompat, si tiene barra de título y sus colores. */
    private class ThemeInfo(val known: Boolean, val night: Boolean, val m3: Boolean, val m2: Boolean, val actionBar: Boolean,
                            val items: Map<String, String>, val platform: String) {
        fun item(k: String): String? = items[k] ?: items["android:$k"]
        companion object {
            fun of(name: String?, book: StyleBook, phoneNight: Boolean): ThemeInfo {
                if (name == null) return ThemeInfo(false, true, false, false, false, emptyMap(), "")
                val (items, platform) = book.resolve(name)
                val p = platform
                val night = when {
                    items["isLightTheme"] == "true" -> false
                    p.contains("DayNight") -> phoneNight
                    p.contains("Light") -> false
                    p.startsWith("Theme") || p.startsWith("android:Theme") -> true
                    else -> phoneNight
                }
                val noBar = (name + " " + p).contains("NoActionBar") || (name + " " + p).contains("NoTitleBar") ||
                    listOf("windowActionBar", "android:windowActionBar").any { items[it] == "false" } ||
                    listOf("windowNoTitle", "android:windowNoTitle").any { items[it] == "true" }
                return ThemeInfo(p.isNotEmpty() || items.isNotEmpty(), night, p.contains("Material3"), p.contains("MaterialComponents"),
                    !noBar && p.isNotEmpty(), items, p)
            }
        }
    }

    /** Convierte un diseño XML de Android en HTML aproximado. */
    private class XmlToHtml(val strings: Map<String, String>, val colors: Map<String, String>, val layouts: Map<String, String>,
                            val shapes: Map<String, String> = emptyMap(), val navs: Map<String, String> = emptyMap(),
                            val menus: Map<String, String> = emptyMap(),
                            // 🎨 Para que se vea como TU diseño: íconos dibujados, selectores, estilos, tema y fuentes
                            val vectors: Map<String, String> = emptyMap(), val drawSel: Map<String, String> = emptyMap(),
                            val book: StyleBook = StyleBook(emptyMap()), val theme: ThemeInfo = ThemeInfo.of(null, StyleBook(emptyMap()), true),
                            val fontAlias: Map<String, String> = emptyMap(), val dimens: Map<String, String> = emptyMap()) {

        /**
         * 🎨 Los atributos de un elemento con los de su estilo (style="@style/…", o el estilo de botón del tema):
         * lo que está escrito en el elemento manda. «__variant» anota de qué estilo de Android viene (OutlinedButton, TextButton…).
         */
        /** El «hint» del último TextInputLayout (su campo de texto lo muestra si no tiene uno propio). */
        private var tilHint: String? = null

        fun withStyle(tag: String, a: HashMap<String, String>) {
            val ref = a["style"] ?: when {
                tag.endsWith("Button") && !tag.endsWith("ImageButton") && !tag.endsWith("RadioButton") && !tag.contains("FloatingActionButton") ->
                    theme.item("materialButtonStyle") ?: theme.item("buttonStyle")
                tag.endsWith("EditText") -> theme.item("editTextStyle")
                tag == "TextInputLayout" -> theme.item("textInputStyle")
                tag.endsWith("TextView") -> theme.item("textViewStyle")
                else -> null
            }
            if (ref != null) {
                val (items, platform) = book.resolve(ref)
                for ((k, v) in items) a.putIfAbsent(k.substringAfter(':'), v)
                a.putIfAbsent("__variant", platform + " " + ref)
            }
            // 💊 Forma del estilo (shapeAppearance → cornerSize): «50%» es píldora, «24dp» esquinas de 24
            if (a["cornerRadius"] == null) (a["shapeAppearanceOverlay"] ?: a["shapeAppearance"])?.let { sh ->
                book.resolve(sh).first.entries.firstOrNull { it.key.substringAfter(':') == "cornerSize" }?.value?.let { cs ->
                    if (cs.endsWith("%")) a["__radiusCss"] = "999px" else a["cornerRadius"] = cs
                }
            }
        }

        /** Sigue los selectores de fondos (state lists) hasta el fondo normal (sin tocar). */
        private fun followSel(ref: String?): String? {
            var r = ref?.trim() ?: return null
            var guard = 0
            while (guard++ < 4) {
                val nm = when { r.startsWith("@drawable/") -> r.removePrefix("@drawable/"); else -> return r }
                r = drawSel[nm] ?: return r
            }
            return r
        }

        private fun drawableName(ref: String?): String? {
            val r = followSel(ref) ?: return null
            return when {
                r.startsWith("@drawable/") -> r.removePrefix("@drawable/")
                r.startsWith("@mipmap/") -> r.removePrefix("@mipmap/")
                else -> null
            }
        }

        /**
         * 🖼️ Una imagen de tu proyecto lista para mostrar: un ícono dibujado (<vector>) como SVG, o una imagen
         * (.png, .jpg, .webp) de tu .zip. null si no vino (se usa el ícono de ejemplo).
         */
        fun drawableHtml(ref: String?, tint: String?, w: String?, h: String?, fit: String = "contain"): String? {
            val nm = drawableName(ref) ?: return null
            vectors[nm]?.let { v -> return runCatching { vectorSvg(v, tint, w, h) }.getOrNull() }
            nm.takeIf { projectAssets.containsKey(it) }?.let { assetUrl(it) }?.let { uri ->
                return "<img src='$uri' alt='' style='display:block;object-fit:$fit;" +
                    (if (w != null) "width:$w;" else "max-width:100%;") + (if (h != null) "height:$h;" else "height:auto;") + "'>"
            }
            return null
        }

        /** 🎨 Un ícono de Android (<vector> con pathData) convertido a SVG, con sus colores o con el tinte (tint) del botón. */
        fun vectorSvg(xml: String, tint0: String?, w0: String?, h0: String?): String {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml.trim().removePrefix("﻿")))
            val body = StringBuilder()
            var vw = "24"; var vh = "24"; var dw: String? = null; var dh: String? = null; var vtint: String? = null; var alpha: String? = null
            var ev = p.eventType
            var tintCss: String? = null
            fun attrs(): Map<String, String> = (0 until p.attributeCount).associate { p.getAttributeName(it).substringAfter(':') to p.getAttributeValue(it) }
            fun paint(v: String?): String? {
                if (v == null) return null
                // (transparente sigue transparente aunque el ícono tenga tinte: así los íconos de líneas no salen rellenos)
                val raw = color(v)
                if (raw == "transparent" || Regex("^#00[0-9A-Fa-f]{6}$").matches(v.trim()) || raw?.let { Regex("rgba\\([^)]*,\\s*0(\\.0+)?\\)$").matches(it) } == true) return "none"
                if (tintCss != null) return tintCss
                return color(v) ?: if (v.isNotBlank()) "currentColor" else null
            }
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val a = attrs()
                    when (p.name.substringAfterLast(':')) {
                        "vector" -> {
                            a["viewportWidth"]?.let { vw = it }; a["viewportHeight"]?.let { vh = it }
                            dw = dim(a["width"]); dh = dim(a["height"]); vtint = a["tint"]; alpha = a["alpha"]
                            val t = tint0 ?: vtint
                            tintCss = if (t == null) null else color(t) ?: "currentColor"
                        }
                        "group" -> {
                            fun f(k: String, d: Float) = a[k]?.toFloatOrNull() ?: d
                            val px = f("pivotX", 0f); val py = f("pivotY", 0f)
                            body.append("<g transform='translate(${f("translateX", 0f) + px},${f("translateY", 0f) + py}) rotate(${f("rotation", 0f)}) " +
                                "scale(${f("scaleX", 1f)},${f("scaleY", 1f)}) translate(${-px},${-py})'>")
                        }
                        "path" -> {
                            val d = a["pathData"]
                            if (d != null) {
                                val fill = paint(a["fillColor"]) ?: "none"
                                val stroke = paint(a["strokeColor"])
                                val st = StringBuilder("fill:$fill;")
                                a["fillAlpha"]?.let { st.append("fill-opacity:$it;") }
                                if (a["fillType"].equals("evenOdd", true)) st.append("fill-rule:evenodd;")
                                if (stroke != null) {
                                    st.append("stroke:$stroke;stroke-width:${a["strokeWidth"] ?: "1"};")
                                    a["strokeAlpha"]?.let { st.append("stroke-opacity:$it;") }
                                    a["strokeLineCap"]?.let { st.append("stroke-linecap:$it;") }
                                    a["strokeLineJoin"]?.let { st.append("stroke-linejoin:$it;") }
                                }
                                body.append("<path d='${esc(d)}' style='$st'/>")
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name.substringAfterLast(':') == "group") body.append("</g>")
                ev = p.next()
            }
            val w = w0 ?: dw ?: "24px"; val h = h0 ?: dh ?: "24px"
            return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $vw $vh' width='$w' height='$h' " +
                "style='display:block;flex:none;${alpha?.let { "opacity:$it;" } ?: ""}'>$body</svg>"
        }

        /** Fondo con un ícono dibujado o una imagen de tu proyecto (background="@drawable/fondo"). */
        private fun bgImage(ref: String?): String? {
            val nm = drawableName(ref) ?: return null
            vectors[nm]?.let { v ->
                val svg = runCatching { vectorSvg(v, null, "100%", "100%") }.getOrNull() ?: return null
                val enc = svg.replace("\"", "'").replace("%", "%25").replace("#", "%23").replace("<", "%3C").replace(">", "%3E").replace("\n", " ")
                return "background:url(\"data:image/svg+xml,$enc\") center/contain no-repeat;"
            }
            if (projectAssets.containsKey(nm)) return "background:url(${assetUrl(nm)}) center/cover no-repeat;"
            return null
        }

        /** Íconos al lado del texto de un botón o texto: app:icon, drawableStart/Left (antes), drawableEnd/Right (después), drawableTop (arriba). */
        fun sideIcons(a: Map<String, String>, button: Boolean = false): Triple<String, String, String> {
            // (en un botón, el ícono sin tinte toma el color del texto del botón, como en Material)
            val tint = a["iconTint"] ?: a["drawableTint"] ?: if (button) "currentColor" else null
            fun one(ref: String?, css: String) = drawableHtml(ref, tint, "18px", "18px")?.let { "<span class='dic' style='$css'>$it</span>" }.orEmpty()
            return Triple(one(a["icon"] ?: a["drawableStart"] ?: a["drawableLeft"], ""), one(a["drawableTop"], "display:flex;justify-content:center;margin:0 0 4px"),
                one(a["drawableEnd"] ?: a["drawableRight"], ""))
        }

        /**
         * 🧭 Pantalla que muestra un contenedor de fragmentos (FragmentContainerView / NavHostFragment):
         * la de inicio del gráfico de navegación; si no, la que indica tools:layout; si no, el fragmento «home/feed/inicio».
         * Antes el contenedor quedaba vacío y la app se veía en blanco al agregarle la lógica con fragmentos.
         */
        fun fragmentLayout(a: Map<String, String>): String? {
            a["layout"]?.substringAfter("/")?.let { layouts[it] }?.let { return it }
            val graph = a["navGraph"]?.substringAfter("/")?.let { navs[it] } ?: navs.values.firstOrNull()
            if (graph != null) {
                val start = Regex("startDestination=\"@\\+?id/([\\w.]+)\"").find(graph)?.groupValues?.get(1)
                val frags = Regex("<(?:fragment|dialog)\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL).findAll(graph).map { it.value }.toList()
                val tag = frags.firstOrNull { start != null && Regex("android:id=\"@\\+?id/$start\"").containsMatchIn(it) } ?: frags.firstOrNull()
                tag?.let { Regex("tools:layout=\"@layout/([\\w.]+)\"").find(it)?.groupValues?.get(1) }?.let { layouts[it] }?.let { return it }
            }
            val frag = layouts.keys.filter { it.startsWith("fragment") }
            return (frag.firstOrNull { Regex("home|feed|inicio|main|principal|timeline|muro").containsMatchIn(it) } ?: frag.firstOrNull())
                ?.let { layouts[it] }
        }

        /**
         * 📋 La fila de una lista (RecyclerView) que tu código llena: el diseño item_… que más se parece al nombre de la lista
         * (rv_chat_messages → item_chat_message). Si hay uno solo, ese. Antes salían filas de ejemplo «Elemento 1, 2, 3».
         */
        fun guessItem(listId: String?): String? {
            val items = layouts.keys.filter { Regex("^(item|row|fila|list_item)_|_(item|row)$").containsMatchIn(it) }
            if (items.isEmpty()) return null
            val words = listId.orEmpty().replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 && it !in setOf("recycler", "view", "list", "lista", "lst") }.map { it.removeSuffix("es").removeSuffix("s") }
            val best = items.map { k -> k to words.count { w -> w.length > 2 && k.contains(w) } }.maxByOrNull { it.second }
            return if (best != null && best.second > 0) best.first else items.singleOrNull()
        }

        /** Nombre legible de un campo por su id: tv_ai_name → «Ai name», tvSender → «Sender». null si no tiene id. */
        private fun fieldName(idRef: String?): String? {
            val raw = idRef?.substringAfter('/') ?: return null
            val words = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_').filter { it.isNotEmpty() }
                .dropWhile { it in setOf("tv", "txt", "text", "lbl", "label", "textview") }
            if (words.isEmpty()) return null
            return words.joinToString(" ").replaceFirstChar { it.uppercase() }
        }

        /** ☰ Opciones de un menú (res/menu): título e ícono de cada <item>. */
        fun menuItems(ref: String?): List<Pair<String, String>> {
            val xml = ref?.substringAfter("/")?.let { menus[it] } ?: return emptyList()
            return Regex("<item\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val title = Regex("android:title=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)?.let { res(it) } ?: return@mapNotNull null
                val iconRef = Regex("android:icon=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                // 🎨 El ícono real del menú (si vino su dibujo); si no, uno de ejemplo
                val icon = drawableHtml(iconRef, null, "22px", "22px")
                    ?: iconFor(mapOf("src" to (iconRef ?: ""), "id" to title)).let { if (it == "●") "•" else it }
                title to icon
            }.toList()
        }

        fun convert(xml: String, depth: Int): String = try { convertRaw(xml, depth) } catch (e: Exception) {
            // Diseño cortado: se completa y se dibuja lo que alcanzó a escribir la IA
            val fixed = repairXml(xml.trim().removePrefix("\uFEFF"))
            if (fixed == xml.trim()) throw e
            convertRaw(fixed, depth) + (if (depth == 0) "<div class='note'>${esc(tx("✂️ Este diseño llegó cortado: completé lo que faltaba para poder mostrarlo."))}</div>" else "")
        }

        private fun convertRaw(xml: String, depth: Int): String {
            if (depth > 4) return ""
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml.trim().removePrefix("﻿")))
            val sb = StringBuilder()
            val closers = ArrayDeque<String>()
            val parents = ArrayDeque<String>()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val a = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) a[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
                        val tag = p.name.substringAfterLast('.')
                        runCatching { withStyle(tag, a) }   // 🎨 lo que trae su estilo (style="@style/…") o el tema
                        val (open, close) = element(tag, a, depth, parents.lastOrNull())
                        sb.append(open); closers.addLast(close); parents.addLast(tag)
                    }
                    XmlPullParser.END_TAG -> {
                        if (closers.isNotEmpty()) sb.append(closers.removeLast())
                        if (parents.isNotEmpty()) parents.removeLast()
                    }
                }
                ev = p.next()
            }
            return sb.toString()
        }

        private fun res(v: String?): String? {
            if (v == null) return null
            return when {
                v.startsWith("@string/") -> (strings[v.removePrefix("@string/")] ?: v.removePrefix("@string/")).replace("\\'", "'").replace("\\\"", "\"")
                v.startsWith("@android:string/") -> v.substringAfter('/')
                v.startsWith("@color/") -> colors[v.removePrefix("@color/")]?.let { res(it) } ?: guessColor(v.removePrefix("@color/"))
                // 🎨 Los colores con nombre de Android (holo_orange_dark, darker_gray, system_accent1_500…)
                v.startsWith("@android:color/") -> ProjectCheck.namedColor(v.substringAfter('/')) ?: "#888888"
                // 🎨 ?attr/colorPrimary… : el color del tema de tu app (o el de Material si el tema no lo cambia)
                v.startsWith("?") -> {
                    val key = v.removePrefix("?").removePrefix("android:attr/").removePrefix("attr/").removePrefix("android:")
                    theme.item(key)?.takeIf { it != v }?.let { res(it) } ?: when (key) {
                        "colorPrimary", "colorAccent", "colorControlActivated" -> "var(--btn)"
                        "colorOnPrimary" -> "var(--btnfg)"
                        "colorBackground", "windowBackground" -> "var(--bg)"
                        "colorSurface", "colorSurfaceContainer" -> "var(--card)"
                        "colorOnSurface", "colorOnBackground", "textColorPrimary", "colorControlNormal" -> "var(--fg)"
                        "textColorSecondary", "colorOnSurfaceVariant" -> "var(--fg2)"
                        "colorSecondary", "colorTertiary", "colorSecondaryVariant" -> "var(--accent)"
                        "colorPrimaryDark", "colorPrimaryVariant", "colorPrimaryContainer" -> "var(--btn)"
                        "colorOnSecondary", "colorOnPrimaryContainer" -> "var(--btnfg)"
                        "colorError" -> "#B3261E"
                        "colorOnError" -> "#FFFFFF"
                        "colorSurfaceVariant", "colorSecondaryContainer" -> "var(--tonal)"
                        "colorOnSecondaryContainer" -> "var(--ontonal)"
                        "colorOutline" -> "var(--out)"
                        else -> null
                    }
                }
                v.startsWith("@") -> null
                else -> v.replace("\\'", "'").replace("\\@", "@").replace("\\?", "?")
            }
        }

        /** Fondo de un @drawable con <shape>: color, degradado, esquinas, borde y forma de círculo. */
        fun shapeCss(ref0: String?): String? {
            val ref = followSel(ref0)
            if (ref != null && (ref.startsWith("@color/") || ref.startsWith("#") || ref.startsWith("?"))) return color(ref)?.let { "background:$it;" }
            if (ref == null || !ref.startsWith("@drawable/")) return null
            val xml = shapes[ref.removePrefix("@drawable/")] ?: return null
            val s = StringBuilder()
            fun attr(tag: String, name: String) = Regex("<$tag\\b[^>]*?android:$name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            val g1 = color(attr("gradient", "startColor")); val g2 = color(attr("gradient", "endColor"))
            if (g1 != null && g2 != null) s.append("background:linear-gradient(135deg,$g1,$g2);")
            else color(attr("solid", "color"))?.let { s.append("background:$it;") }
            dim(attr("corners", "radius"))?.let { s.append("border-radius:$it;") }
            val sw = dim(attr("stroke", "width")); val sc = color(attr("stroke", "color"))
            if (sw != null && sc != null) s.append("border:$sw solid $sc;")
            if (Regex("android:shape=\"oval\"").containsMatchIn(xml)) s.append("border-radius:50%;")
            return s.toString()
        }

        /**
         * 🖌️ Un fondo @drawable que no vino en tus archivos: se adivina por su nombre (círculo, redondeado, caja de
         * escribir, burbuja, tarjeta) y su color. Antes quedaba sin fondo. Si mandas res/drawable, se usa el real.
         */
        private fun guessDrawable(ref: String?): String? {
            if (ref == null || !ref.startsWith("@drawable/")) return null
            val n = ref.removePrefix("@drawable/").lowercase()
            if (n.startsWith("ic_") || n.startsWith("img_") || n.startsWith("photo")) return null   // (íconos e imágenes: no son fondos)
            val color = when {
                Regex("blue|azul|primary|accent").containsMatchIn(n) -> "#3D5AFE"
                Regex("red|rojo|error|danger").containsMatchIn(n) -> "#E53935"
                Regex("green|verde|success").containsMatchIn(n) -> "#43A047"
                Regex("orange|naranja|yellow|amarillo|warning").containsMatchIn(n) -> "#FFB300"
                Regex("purple|morado|violet").containsMatchIn(n) -> "#7E57C2"
                Regex("white|blanco|light").containsMatchIn(n) -> "#FFFFFF"
                Regex("transparent|ripple|selectable").containsMatchIn(n) -> "transparent"
                else -> null
            }
            return when {
                Regex("circle|oval|circulo|round_icon").containsMatchIn(n) -> "border-radius:50%;" + (color?.let { "background:$it;" } ?: "")
                Regex("input|edit|field|search|campo|entrada|composer").containsMatchIn(n) -> "background:${color ?: "#1E1E26"};border-radius:24px;"
                Regex("bubble|burbuja|message|mensaje|card|tarjeta|post|panel|dialog").containsMatchIn(n) -> "background:${color ?: "#1E1E26"};border-radius:16px;"
                Regex("round|pill|button|btn|boton|chip|corner").containsMatchIn(n) -> "background:${color ?: "#2A2A32"};border-radius:14px;"
                color != null -> "background:$color;"
                else -> null
            }
        }

        /**
         * 🎨 Un color que el diseño usa pero no está definido: se adivina por su nombre.
         * Antes TODO lo desconocido salía azul, y si era el fondo la maqueta quedaba entera azul.
         */
        private fun guessColor(name: String): String {
            val n = name.lowercase()
            return when {
                listOf("background", "fondo", "bg", "surface", "superficie", "window", "dark", "oscuro", "black", "negro").any { it in n } -> "#121218"
                listOf("card", "tarjeta", "container", "contenedor").any { it in n } -> "#1E1E26"
                listOf("text", "texto", "onprimary", "on_primary", "white", "blanco", "titulo", "title").any { it in n } -> "#FFFFFF"
                listOf("hint", "secondary_text", "gris", "gray", "grey").any { it in n } -> "#9A9AA5"
                listOf("red", "rojo", "error", "danger").any { it in n } -> "#E53935"
                listOf("green", "verde", "success").any { it in n } -> "#43A047"
                listOf("orange", "naranja", "yellow", "amarillo", "warning").any { it in n } -> "#FFB300"
                listOf("purple", "morado", "violet").any { it in n } -> "#7E57C2"
                listOf("primary", "accent", "blue", "azul", "button", "boton", "botón").any { it in n } -> "#3D5AFE"
                else -> "#2A2A32"
            }
        }

        fun color(v: String?): String? {
            val c = res(v) ?: return null
            if (c.startsWith("var(") || c == "transparent") return c
            if (!c.startsWith("#")) return null
            val h = c.removePrefix("#")
            return when (h.length) {
                8 -> { val a = h.substring(0, 2).toInt(16) / 255.0; "rgba(${h.substring(2, 4).toInt(16)},${h.substring(4, 6).toInt(16)},${h.substring(6, 8).toInt(16)},$a)" }
                4 -> { val x = h.map { "$it$it".toInt(16) }; "rgba(${x[1]},${x[2]},${x[3]},${x[0] / 255.0})" }   // (#ARGB)
                6, 3 -> "#$h"
                else -> null
            }
        }

        private fun dim(v: String?): String? = when {
            v == null -> null
            v.startsWith("@dimen/") -> dimens[v.removePrefix("@dimen/")]?.takeIf { !it.startsWith("@dimen/") }?.let { dim(it) }   // 📏 res/values/dimens.xml
            v == "match_parent" || v == "fill_parent" -> "100%"
            v == "wrap_content" -> null
            v.endsWith("dp") || v.endsWith("dip") || v.endsWith("sp") || v.endsWith("px") ->
                v.replace(Regex("(dp|dip|sp|px)$"), "").toFloatOrNull()?.let { "${it}px" }
            else -> null
        }

        private fun style(tag: String, a: Map<String, String>, parent: String? = null): String {
            val s = StringBuilder()
            val w = a["layout_width"]; val h = a["layout_height"]
            // 🧩 Dentro de un ConstraintLayout, 0dp quiere decir «todo el espacio entre sus anclas» (lo reparte clLayout())
            val inCl = parent == "ConstraintLayout"
            val w0 = inCl && w != null && Regex("^0(\\.0)?(dp|dip|px)?$").matches(w)
            val h0 = inCl && h != null && Regex("^0(\\.0)?(dp|dip|px)?$").matches(h)
            if (!w0) dim(w)?.let { if (w == "match_parent" || w == "fill_parent") s.append("align-self:stretch;width:auto;") else s.append("width:$it;") }
            if (!h0) dim(h)?.let { if (h == "match_parent" || h == "fill_parent") s.append("flex:1;") else s.append("height:$it;") }
            a["layout_weight"]?.toFloatOrNull()?.let { s.append("flex:$it 1 0;") }
            dim(a["padding"])?.let { s.append("padding:$it;") }
            dim(a["paddingHorizontal"])?.let { s.append("padding-left:$it;padding-right:$it;") }
            dim(a["paddingVertical"])?.let { s.append("padding-top:$it;padding-bottom:$it;") }
            (dim(a["paddingStart"]) ?: dim(a["paddingLeft"]))?.let { s.append("padding-left:$it;") }
            (dim(a["paddingEnd"]) ?: dim(a["paddingRight"]))?.let { s.append("padding-right:$it;") }
            dim(a["paddingTop"])?.let { s.append("padding-top:$it;") }
            dim(a["paddingBottom"])?.let { s.append("padding-bottom:$it;") }
            dim(a["layout_margin"])?.let { s.append("margin:$it;") }
            dim(a["layout_marginHorizontal"])?.let { s.append("margin-left:$it;margin-right:$it;") }
            dim(a["layout_marginVertical"])?.let { s.append("margin-top:$it;margin-bottom:$it;") }
            (dim(a["layout_marginStart"]) ?: dim(a["layout_marginLeft"]))?.let { s.append("margin-left:$it;") }
            (dim(a["layout_marginEnd"]) ?: dim(a["layout_marginRight"]))?.let { s.append("margin-right:$it;") }
            dim(a["layout_marginTop"])?.let { s.append("margin-top:$it;") }
            dim(a["layout_marginBottom"])?.let { s.append("margin-bottom:$it;") }
            val shapeBg = shapeCss(a["background"]) ?: bgImage(a["background"]) ?: guessDrawable(a["background"])
            if (shapeBg != null) s.append(shapeBg)
            else (color(a["background"]) ?: color(a["backgroundTint"]) ?: color(a["cardBackgroundColor"]))?.let { s.append("background:$it;") }
            (color(a["textColor"]) ?: color(a["titleTextColor"]) ?: color(a["itemTextColor"]) ?: color(a["tabTextColor"]))?.let { s.append("color:$it;") }
            // 🎨 Más colores de tu diseño: el texto de ayuda (hint), los interruptores, casillas, barras y el recuadro de
            //    los campos de texto (TextInputLayout)
            (color(a["textColorHint"]) ?: color(a["hintTextColor"]))?.let { s.append("--ph:$it;") }
            (color(a["buttonTint"]) ?: color(a["thumbTint"]) ?: color(a["progressTint"]) ?: color(a["trackTint"]) ?: color(a["indicatorColor"]) ?: color(a["thumbColor"]))
                ?.let { s.append("accent-color:$it;--accent:$it;") }
            color(a["boxBackgroundColor"])?.let { s.append("background:$it;") }
            color(a["boxStrokeColor"])?.let { s.append("--inpbd:$it;") }
            dim(a["textSize"])?.let { s.append("font-size:$it;") }
            if (a["textStyle"]?.contains("bold") == true) s.append("font-weight:700;")
            if (a["textStyle"]?.contains("italic") == true) s.append("font-style:italic;")
            // gravity: «center» centra todo; «center_vertical» solo en vertical (el texto sigue a la izquierda)
            val gv = a["gravity"].orEmpty()
            val row = a["orientation"] == "horizontal"
            if (Regex("(^|\\|)center(\\||$)").containsMatchIn(gv) || a["layout_gravity"]?.let { Regex("(^|\\|)center(\\||$)").containsMatchIn(it) } == true && gv.isEmpty() && tag.endsWith("TextView")) {
                s.append("text-align:center;justify-content:center;align-items:center;")
            } else {
                if (gv.contains("center_vertical")) s.append(if (row) "align-items:center;" else "justify-content:center;")
                if (gv.contains("center_horizontal")) s.append(if (row) "justify-content:center;" else "align-items:center;text-align:center;")
            }
            if (a["layout_gravity"]?.contains("center") == true) s.append("align-self:center;")
            if (a["layout_gravity"]?.contains("end") == true || a["layout_gravity"]?.contains("right") == true) s.append("align-self:flex-end;")
            if (a["visibility"] == "gone") s.append("display:none;")
            if (a["visibility"] == "invisible") s.append("visibility:hidden;")
            dim(a["cardCornerRadius"])?.let { s.append("border-radius:$it;") }
            // 🎨 Detalles de diseño moderno (Material 3) para que la maqueta se vea como la app
            dim(a["cornerRadius"])?.let { s.append("border-radius:$it;") }
            a["__radiusCss"]?.let { s.append("border-radius:$it;") }
            // (sombra como la de Android: suave y hacia abajo, más grande mientras más alto está el elemento)
            (dim(a["cardElevation"]) ?: dim(a["elevation"]))?.let { s.append("box-shadow:0 calc($it / 2) calc($it * 1.5) rgba(0,0,0,.35);") }
            // (el espacio de adentro de una tarjeta: contentPadding)
            dim(a["contentPadding"])?.let { s.append("padding:$it;") }
            val stroke = dim(a["strokeWidth"]); val strokeColor = color(a["strokeColor"])
            if (stroke != null && strokeColor != null) s.append("border:$stroke solid $strokeColor;")
            a["fontFamily"]?.let { f -> if (f.contains("medium")) s.append("font-weight:500;") else if (f.contains("bold") || f.contains("black")) s.append("font-weight:700;") }
            // 🔤 La fuente de tu proyecto (res/font)
            a["fontFamily"]?.takeIf { it.startsWith("@font/") }?.removePrefix("@font/")?.let { f -> fontAlias[f] ?: f }
                ?.takeIf { projectFonts.containsKey(it) }?.let { s.append("font-family:'f_$it',sans-serif;") }
            when (a["textAllCaps"]) { "true" -> s.append("text-transform:uppercase;"); "false" -> s.append("text-transform:none;") }
            a["letterSpacing"]?.toFloatOrNull()?.let { s.append("letter-spacing:${it}em;") }
            a["alpha"]?.toFloatOrNull()?.let { s.append("opacity:$it;") }
            return s.toString()
        }

        /** Ícono de un ImageButton según su imagen, id o descripción (☰ menú, ⚙ ajustes, 🎤, ➤ enviar…). */
        private fun iconFor(a: Map<String, String>): String {
            // (primero la imagen: btn_voice_play_pause con ic_media_play es ▶; si la imagen no dice nada, el id y la descripción)
            val src = (a["src"].orEmpty() + " " + a["srcCompat"].orEmpty()).trim()
            if (src.isNotEmpty()) iconFor(mapOf("id" to src)).takeIf { it != "●" }?.let { return it }
            val k0 = (a["src"].orEmpty() + " " + a["srcCompat"].orEmpty() + " " + a["id"].orEmpty() + " " + a["contentDescription"].orEmpty()).lowercase()
            // 🤖 Imágenes propias de Android (@android:drawable/ic_menu_send, ic_media_play, ic_input_add…): se mira lo que
            //    viene después de «ic_menu_», así ic_menu_preferences es ⚙ y ic_menu_send es ➤ (antes las dos salían ☰)
            val k = k0.replace(Regex("ic_menu_sort_by_size"), "hamburg").replace(Regex("ic_(menu|media|input|btn|dialog)_"), "ic_")
            return when {
                Regex("setting|ajuste|config|tune|gear|engran|preferences|manage").containsMatchIn(k) -> "⚙"
                Regex("pause|pausa").containsMatchIn(k) -> "⏸"
                Regex("menu|hamburg|drawer").containsMatchIn(k) -> "☰"
                Regex("mic|voz|voice|hablar|speak").containsMatchIn(k) -> "🎤"
                Regex("send|enviar").containsMatchIn(k) -> "➤"
                Regex("close|cerrar|clear").containsMatchIn(k) -> "✕"
                Regex("search|buscar|lupa").containsMatchIn(k) -> "🔍"
                Regex("back|atras|atrás|volver|arrow_left").containsMatchIn(k) -> "←"
                Regex("add|agregar|plus|nuevo|new").containsMatchIn(k) -> "＋"
                Regex("more|mas|más|overflow|dots").containsMatchIn(k) -> "⋮"
                Regex("camera|camara|cámara|photo|foto").containsMatchIn(k) -> "📷"
                Regex("share|compartir").containsMatchIn(k) -> "↗"
                Regex("delete|borrar|eliminar|trash").containsMatchIn(k) -> "🗑"
                Regex("play").containsMatchIn(k) -> "▶"
                // 🏠 Íconos típicos de menús de apps (inicio, perfil, notificaciones, mensajes, me gusta)
                Regex("home|inicio|casa|feed").containsMatchIn(k) -> "🏠"
                Regex("person|perfil|profile|account|cuenta|user|usuario").containsMatchIn(k) -> "👤"
                Regex("notif|bell|campana|alert").containsMatchIn(k) -> "🔔"
                Regex("message|chat|mensaje|mail|correo|inbox").containsMatchIn(k) -> "💬"
                Regex("favorite|like|heart|coraz|me gusta").containsMatchIn(k) -> "♥"
                Regex("logout|salir|exit|cerrar sesi").containsMatchIn(k) -> "⎋"
                else -> "●"
            }
        }

        private fun element(tag: String, a: Map<String, String>, depth: Int, parent: String? = null): Pair<String, String> {
            val st = style(tag, a, parent)
            // 🌍 Lo que se ve (botones, textos, barra de escritura, interruptores) en el idioma de la app
            // 📝 Los textos de TU app tal como los escribiste (no se traducen: así ves exactamente tu diseño)
            val text = esc(res(a["text"]) ?: "")
            val hint = esc(res(a["hint"]) ?: "")
            val id0 = a["id"]?.substringAfter('/')?.let { " title='${esc(it)}' data-id='${esc(it)}'" } ?: ""
            // 🧩 Hijo de un ConstraintLayout: sus anclas (a quién se pega arriba, abajo, a la izquierda, a la derecha)
            //    viajan en el HTML y clLayout() arma las filas como en la app real (texto arriba y ▶ ＋ [Modo] 🎤 ➤ abajo)
            val id = if (parent != "ConstraintLayout") id0 else {
                fun anc(k: String) = a["layout_constraint$k"]?.let { v -> if (v == "parent") "parent" else v.substringAfter('/') }
                val pairs = listOf("tt" to "Top_toTopOf", "tb" to "Top_toBottomOf", "bt" to "Bottom_toTopOf", "bb" to "Bottom_toBottomOf",
                    "ss" to "Start_toStartOf", "se" to "Start_toEndOf", "es" to "End_toStartOf", "ee" to "End_toEndOf")
                    .mapNotNull { (k, n) -> (anc(n) ?: anc(n.replace("Start", "Left").replace("End", "Right")))?.let { " data-$k='${esc(it)}'" } }
                val w0 = a["layout_width"]?.let { Regex("^0(\\.0)?(dp|dip|px)?$").matches(it) } == true
                "$id0 data-cl='1'" + pairs.joinToString("") + (if (w0) " data-w0='1'" else "")
            }
            val lg = a["layout_gravity"].orEmpty()
            return when {
                // ☰ Paneles laterales (DrawerLayout): ocultos; se abren con los botones de menú y ajustes
                tag == "DrawerLayout" ->
                    "<div class='v dl'$id style='display:flex;flex-direction:column;position:relative;overflow:hidden;$st'>" to
                        "<div class='scrim' onclick=\"dr(this,0)\"></div></div>"
                // ☰ Menú lateral (NavigationView): su cabecera y sus opciones
                tag == "NavigationView" -> {
                    val header = a["headerLayout"]?.substringAfter("/")?.let { layouts[it] }
                        ?.let { runCatching { convert(it, depth + 1) }.getOrDefault("") }.orEmpty()
                    val items = menuItems(a["menu"]).joinToString("") { (t, i) -> "<div class='row nv' data-k='${esc(t)}'><span>$i</span><span>${esc(t)}</span></div>" }
                    val cls = if (parent == "DrawerLayout") (if (lg.contains("end") || lg.contains("right")) "v drawer dl-right" else "v drawer dl-left") else "v"
                    "<div class='$cls'$id style='background:var(--card);min-width:240px;$st'>$header$items" to "</div>"
                }
                parent == "DrawerLayout" && (lg.contains("start") || lg.contains("left")) ->
                    "<div class='v drawer dl-left'$id style='$st'>" to "</div>"
                parent == "DrawerLayout" && (lg.contains("end") || lg.contains("right")) ->
                    "<div class='v drawer dl-right'$id style='$st'>" to "</div>"
                tag == "ImageButton" -> {
                    val icon = iconFor(a)
                    val click = when (icon) { "☰" -> " onclick=\"dr(this,'ol')\""; "⚙" -> " onclick=\"dr(this,'or')\""; else -> "" }
                    val tint = color(a["tint"])?.let { "color:$it;" } ?: ""
                    // 🎨 El ícono real de tu botón (si vino su dibujo o su imagen); si no, uno de ejemplo
                    val real = drawableHtml(a["src"] ?: a["srcCompat"], a["tint"], "24px", "24px")
                    "<button class='ibtn v'$id$click style='$tint$st'>${real ?: icon}</button>" to ""
                }
                // 🧭 Contenedor de fragmentos: se dibuja la pantalla de inicio (antes quedaba en blanco)
                tag == "FragmentContainerView" || tag == "NavHostFragment" || (tag == "fragment" && parent != null) -> {
                    val frag = fragmentLayout(a)
                    val inner = if (frag != null) runCatching { convert(frag, depth + 1) }.getOrDefault("") else ""
                    "<div class='v'$id style='display:flex;flex-direction:column;$st'>$inner" to "</div>"
                }
                // ▭ Barra de abajo (BottomNavigationView): sus opciones con ícono
                tag == "BottomNavigationView" || tag == "NavigationBarView" -> {
                    val items = menuItems(a["menu"]).ifEmpty { listOf(tx("Inicio") to "🏠", tx("Buscar") to "🔍", tx("Perfil") to "👤") }
                    "<div class='v bnv'$id style='display:flex;flex-direction:row;justify-content:space-around;align-items:center;background:var(--card);padding:8px 0;$st'>" +
                        items.joinToString("") { (t, i) -> "<div class='bn' data-k='${esc(t)}' style='display:flex;flex-direction:column;align-items:center;font-size:11px'><span style='font-size:18px'>$i</span>${esc(t)}</div>" } to "</div>"
                }
                tag == "HorizontalScrollView" -> "<div class='v'$id style='display:flex;flex-direction:row;overflow-x:auto;$st'>" to "</div>"
                tag == "LinearLayout" -> {
                    val dir = if (a["orientation"] == "horizontal") "row" else "column"
                    "<div class='v'$id style='display:flex;flex-direction:$dir;${if (dir == "row") "align-items:center;" else ""}$st'>" to "</div>"   // (sin espacio extra entre elementos: Android no lo pone; solo los márgenes de tu diseño)
                }
                tag.endsWith("Toolbar") || tag == "ActionBar" ->
                    // ☰ Si la pantalla tiene menú lateral, la barra trae el botón para abrirlo (como en la app real)
                    "<div class='v bar'$id style='display:flex;align-items:center;gap:10px;$st'><button class='ibtn hamb' onclick=\"dr(this,'ol')\">☰</button>" +
                        "${esc(res(a["title"]) ?: strings["app_name"] ?: tx("Mi app"))}" to "</div>"
                tag.endsWith("CardView") -> "<div class='v card'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
                // 🧩 ConstraintLayout: se dibuja en columna y clLayout() junta en filas lo que va lado a lado
                // 🎞️ ViewFlipper / ViewSwitcher: como en la app, se ve solo su primera página
                tag == "ViewFlipper" || tag == "ViewSwitcher" || tag == "ViewAnimator" || tag == "TextSwitcher" || tag == "ImageSwitcher" ->
                    "<div class='v vf'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
                tag == "ConstraintLayout" -> "<div class='v cl'$id style='display:flex;flex-direction:column;position:relative;$st'>" to "</div>"
                tag.endsWith("ScrollView") -> "<div class='v'$id style='display:flex;flex-direction:column;overflow:auto;$st'>" to "</div>"
                // ✍️ TextInputLayout: con recuadro (OutlinedBox) o relleno (FilledBox, el de siempre); su «hint» va en el campo
                tag == "TextInputLayout" -> {
                    tilHint = res(a["hint"])
                    val box = if ((a["__variant"].orEmpty() + a["style"].orEmpty()).contains("Outlined")) "out" else "fill"
                    "<div class='v til $box'$id style='display:flex;flex-direction:column;position:relative;$st'>" to "</div>"
                }
                tag.endsWith("Layout") || tag == "merge" || tag == "RadioGroup" ->
                    "<div class='v'$id style='display:flex;flex-direction:column;position:relative;$st'>" to "</div>"
                tag.contains("FloatingActionButton") -> {
                    val real = drawableHtml(a["src"] ?: a["srcCompat"] ?: a["icon"], a["tint"] ?: a["iconTint"], "24px", "24px")
                    val (s0, _, _) = sideIcons(a, button = true)
                    val fabCss = if (tag.startsWith("Extended") && text.isNotEmpty()) "width:auto;padding:0 18px;display:flex;align-items:center;gap:8px;" else ""
                    "<button class='fab'$id style='display:flex;align-items:center;justify-content:center;$fabCss$st'>" +
                        (if (text.isNotEmpty()) (real?.let { "<span class='dic'>$it</span>" } ?: s0) + text else real ?: "+") + "</button>" to ""
                }
                tag.endsWith("Button") && !tag.endsWith("RadioButton") && !tag.endsWith("ToggleButton") -> {
                    // 🎨 Botón «Outlined» (solo borde), «Text» (sin fondo) o «Tonal», como el estilo de tu app
                    val variant = a["__variant"].orEmpty()
                    val kind = when {
                        variant.contains("OutlinedButton") -> " out"
                        variant.contains("TextButton") || variant.contains("Borderless") -> " txb"
                        variant.contains("TonalButton") || variant.contains("UnelevatedButton") -> " tonal"
                        else -> ""
                    }
                    val (s0, t0, e0) = sideIcons(a, button = true)
                    "<button class='btn v$kind'$id style='$st'>$t0$s0${if (text.isNotEmpty()) text else if (tag == "ImageButton") "⬤" else esc(tx("Botón"))}$e0</button>" to ""
                }
                tag.endsWith("EditText") || tag == "AutoCompleteTextView" -> {
                    val flat = if (a["background"] == "@null" || a["background"] == "@android:color/transparent") "background:transparent;border:none;" else ""
                    val ph = if (hint.isNotEmpty()) hint else if (parent == "TextInputLayout" && !tilHint.isNullOrBlank()) esc(tilHint!!) else esc(tx("Escribe aquí"))
                    // 📝 Campo de varias líneas (ocupa todo el alto, gravity="top" o texto multilínea): el texto y su ayuda van arriba
                    val multi = a["layout_height"] == "match_parent" || a["layout_height"] == "fill_parent" || a["gravity"].orEmpty().contains("top") ||
                        a["inputType"].orEmpty().contains("MultiLine", true) || (a["minLines"] ?: a["lines"])?.toIntOrNull()?.let { it > 1 } == true
                    if (multi) "<textarea class='inp v'$id placeholder='$ph' style='resize:none;font-family:inherit;$flat$st'>$text</textarea>" to ""
                    else "<input class='inp v'$id placeholder='$ph' value='$text' style='$flat$st'>" to ""
                }
                // 🏷️ Chip: la pastilla de Material (32 dp de alto)
                tag == "Chip" -> "<div class='v chip'$id style='$st'>${if (text.isNotEmpty()) text else esc(tx("Texto"))}</div>" to ""
                tag.endsWith("TextView") ->
                    // (sin texto: lo pone tu código; se muestra el nombre del campo, tvSender → «Sender», en vez de «Texto»)
                    sideIcons(a).let { (s0, t0, e0) ->
                        "<div class='txt v'$id style='$st'>$t0$s0${if (text.isNotEmpty()) text else esc(fieldName(a["id"]) ?: tx("Texto"))}$e0</div>" to ""
                    }
                tag.endsWith("ImageView") -> {
                    // 🖼️ La imagen o el ícono real de tu proyecto (si vino); si no, el recuadro de ejemplo
                    fun fixed(v: String?) = v != null && v != "wrap_content"
                    val fit = when (a["scaleType"]) { "centerCrop" -> "cover"; "fitXY" -> "fill"; "center" -> "none"; else -> "contain" }
                    val real = drawableHtml(a["src"] ?: a["srcCompat"], a["tint"], if (fixed(a["layout_width"])) "100%" else null,
                        if (fixed(a["layout_height"])) "100%" else null, fit)
                    if (real != null) "<div class='v'$id style='display:flex;align-items:center;justify-content:center;overflow:hidden;$st'>$real</div>" to ""
                    else "<div class='img v'$id style='$st'>🖼️</div>" to ""
                }
                tag == "RecyclerView" || tag == "ListView" || tag == "GridView" -> {
                    // Si el diseño indica su fila (tools:listitem), se dibuja 3 veces; si no, filas de ejemplo
                    val item = (a["listitem"]?.substringAfter("/") ?: guessItem(a["id"]?.substringAfter('/')))?.let { layouts[it] }
                    val rows = (1..3).joinToString("") { i ->
                        if (item != null) runCatching { convert(item, depth + 1) }.getOrDefault("")
                        else "<div class='row'><div class='img' style='width:36px;height:36px;min-height:36px'>📄</div><div>${esc(tx("Elemento"))} $i</div></div>"
                    }
                    "<div class='v lst'$id style='display:flex;flex-direction:column;$st'>$rows" to "</div>"
                }
                // 🌙 Interruptor (como «Modo oscuro»): el texto a la izquierda y el interruptor a la derecha
                tag.contains("Switch") ->
                    "<label class='v txt swr'$id style='$st'><span>$text</span><input type='checkbox' class='swi'${if (a["checked"] == "true") " checked" else ""}></label>" to ""
                tag.endsWith("CheckBox") || tag == "ToggleButton" ->
                    "<label class='v txt'$id style='display:flex;align-items:center;gap:8px;$st'><input type='checkbox'${if (a["checked"] == "true") " checked" else ""}>$text</label>" to ""
                tag.endsWith("RadioButton") ->
                    "<label class='v txt'$id style='display:flex;align-items:center;gap:8px;$st'><input type='radio' name='r'>$text</label>" to ""
                tag.endsWith("SeekBar") || tag == "Slider" -> "<input type='range' class='v'$id style='$st'>" to ""
                // ⏳ ProgressBar: el círculo que gira (el de siempre en Android) o la barra (Horizontal / LinearProgressIndicator)
                tag == "CircularProgressIndicator" || (tag.endsWith("ProgressBar") && !(a["style"].orEmpty() + a["__variant"].orEmpty()).contains("Horizontal")) ->
                    "<div class='v pgc'$id style='$st'></div>" to ""
                tag.endsWith("ProgressBar") || tag == "LinearProgressIndicator" -> "<progress class='v'$id style='$st'></progress>" to ""
                tag.endsWith("Spinner") -> "<select class='inp v'$id style='$st'><option>${esc(tx("Opción"))} 1</option><option>${esc(tx("Opción"))} 2</option></select>" to ""
                tag == "include" -> {
                    val inc = a["layout"]?.substringAfter("/")?.let { layouts[it] }
                    (if (inc != null) runCatching { convert(inc, depth + 1) }.getOrDefault("") else "") to ""
                }
                tag == "View" || tag == "Space" -> "<div class='v'$id style='min-height:1px;$st'></div>" to ""
                else -> "<div class='v'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
            }
        }
    }

    // ------------------------------------------------------------------ VENTANA
    /**
     * Abre la ventana. [build] se vuelve a llamar con 🔄 para tomar la versión más nueva del chat.
     * [live]: mientras devuelva true (la IA está escribiendo el código), la vista se actualiza sola
     * cada pocos segundos para ver cómo se va armando la app.
     */
    @SuppressLint("SetJavaScriptEnabled")
    /** [onRefresh]: lo que hace 🔄 antes de volver a dibujar (ej. borrar todo el código pegado: la ventana queda vacía). */
    fun open(activity: Activity, title: String, emptyMessage: String, live: (() -> Boolean)? = null,
             handoff: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null, build: () -> Pair<String, String>?) {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        // 🌍 La ventana en el idioma de la app
        uiLang = runCatching { com.paulrj.cumulusia.manager.LanguageManager.getSelectedLanguage(activity) }.getOrDefault("es")
        phoneNight = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // ☀️🌙 El modo de Cumulus (el interruptor «Modo claro / oscuro»)
        uiLight = runCatching { activity.getSharedPreferences("CumulusAppPermissions", android.content.Context.MODE_PRIVATE).getBoolean("light_mode", false) }
            .getOrDefault(false)
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(chrome("#0B0B0D", "#F2F2F7"))) }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(10), px(8), px(10)); setBackgroundColor(Color.parseColor(chrome("#141417", "#F2F2F7")))
        }
        val titleView = TextView(activity).apply {
            text = tx(title); textSize = 17f; setTextColor(Color.parseColor(chrome("#FFFFFF", "#1C1C1E"))); typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 🔘 Segunda fila: Vista · Código · 🔄 · 🤖 Otra IA (se desliza a los lados si no caben).
        //    El ✕ queda SIEMPRE visible arriba a la derecha, junto al título.
        val tools = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), 0, px(12), px(10))
        }
        val toolsRow = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor(chrome("#141417", "#F2F2F7")))
            addView(tools)
        }
        fun pill(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(px(12), px(7), px(12), px(7))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#2C2C30")) }
            setOnClickListener { onClick() }
        }
        val content = FrameLayout(activity)
        // ⚠️ Avisos de la app web: archivos que faltan, lo que no cargó de internet y lo que necesita su servidor.
        //    Una línea (toca para ver todos); se vacía al recargar.
        val webNotes = LinkedHashSet<String>()
        var notesOpen = false
        val notesView = TextView(activity).apply {
            textSize = 13f; setTextColor(Color.parseColor(chrome("#FFCC80", "#8A4B00"))); visibility = View.GONE
            setPadding(px(14), px(8), px(14), px(8)); setBackgroundColor(Color.parseColor(chrome("#2A2112", "#FFF3E0")))
            setOnClickListener { notesOpen = !notesOpen; refreshNotes(this, webNotes, notesOpen) }
        }
        fun addNote(kind: String, detail: String) {
            if (webFiles.isEmpty()) return   // (solo en la app web y la página web)
            activity.runOnUiThread {
                if (webNotes.size < 12 && webNotes.add(tx(kind) + " " + detail)) refreshNotes(notesView, webNotes, notesOpen)
            }
        }
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 🌐 Tus archivos (imágenes, fuentes, datos, otras páginas) se entregan desde Cumulus, como un hosting
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val u = request?.url ?: return null
                    if (u.host != WEB_HOST) return null
                    var path = (u.path ?: "").removePrefix("/")
                    // 🖼️🔤 Imágenes y fuentes de tu app Kotlin (la maqueta las pide una sola vez cada una)
                    val res = when {
                        path.startsWith("__res/") -> projectAssets[path.removePrefix("__res/")]
                        path.startsWith("__font/") -> projectFonts[path.removePrefix("__font/")]
                        else -> null
                    }
                    if (res != null) return android.webkit.WebResourceResponse(res.first, null, ByteArrayInputStream(res.second))
                    val files = webFiles
                    if (files.isEmpty()) return null
                    if (path.isEmpty() || path.endsWith("/")) path += "index.html"
                    val f = files[path] ?: files["$path/index.html"]
                        // (no vino: sale roto, como en internet, y se avisa qué falta o qué necesita el servidor)
                        ?: return run {
                            if (path != "favicon.ico") addNote(if (needsServer(path, request)) WEB_SERVER else WEB_MISSING, "/$path")
                            android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(),
                            ByteArrayInputStream(ByteArray(0))) }
                    val text = f.first.startsWith("text/") || f.first.contains("javascript") || f.first.contains("json") || f.first.contains("svg")
                    return android.webkit.WebResourceResponse(f.first, if (text) "UTF-8" else null, ByteArrayInputStream(f.second))
                }
                // 🌐 Algo de internet que no cargó (una fuente, una librería, una imagen de otro sitio)
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    val u = request?.url ?: return
                    if (u.host == WEB_HOST || u.scheme == "data" || u.scheme == "blob") return
                    addNote(WEB_OFFLINE, (u.host ?: "") + (u.path ?: ""))
                }
                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                    val u = request?.url ?: return
                    if (u.host == WEB_HOST || (response?.statusCode ?: 0) < 400) return
                    addNote(WEB_OFFLINE, "${u.host}${u.path ?: ""} (${response?.statusCode})")
                }
            }
            settings.setGeolocationEnabled(true)
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {   // (alert(), confirm() y prompt() como siempre)
                // 📷🎤 La app web pide la cámara o el micrófono: Cumulus te pregunta y, si aceptas, funciona de verdad
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request ?: return
                    activity.runOnUiThread {
                        if (request.origin?.host != WEB_HOST) { request.deny(); return@runOnUiThread }
                        val wanted = request.resources.filter {
                            it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }
                        if (wanted.isEmpty()) { request.deny(); return@runOnUiThread }
                        val names = wanted.map { if (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) tx("la cámara") else tx("el micrófono") }
                        val perms = wanted.map {
                            if (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) android.Manifest.permission.CAMERA
                            else android.Manifest.permission.RECORD_AUDIO
                        }
                        askWebPermission(activity, names, perms) { granted ->
                            val ok = wanted.filterIndexed { i, _ -> perms[i] in granted }
                            if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                        }
                    }
                }
                // 📍 La app web pide tu ubicación
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                    callback ?: return
                    activity.runOnUiThread {
                        if (origin == null || !origin.contains(WEB_HOST)) { callback.invoke(origin, false, false); return@runOnUiThread }
                        val perms = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        askWebPermission(activity, listOf(tx("tu ubicación")), perms) { granted -> callback.invoke(origin, granted.isNotEmpty(), false) }
                    }
                }
                // 📎 «Subir foto» / «Elegir archivo» (<input type="file">): abre la galería o tus archivos
                override fun onShowFileChooser(view: WebView?, cb: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                                               params: FileChooserParams?): Boolean {
                    cb ?: return false
                    val reg = (activity as? androidx.activity.ComponentActivity)?.activityResultRegistry ?: run { cb.onReceiveValue(null); return true }
                    val mime = params?.acceptTypes?.firstOrNull { it.isNotBlank() }?.let { if (it.startsWith(".")) "*/*" else it } ?: "*/*"
                    val many = params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    val key = "web_file_" + (++webAsk)
                    var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
                    var done = false
                    fun finish(uris: Array<android.net.Uri>?) { if (!done) { done = true; cb.onReceiveValue(uris) }; launcher?.unregister() }
                    launcher = if (many) reg.register(key, androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { l -> finish(l.takeIf { it.isNotEmpty() }?.toTypedArray()) }
                        else reg.register(key, androidx.activity.result.contract.ActivityResultContracts.GetContent()) { u -> finish(u?.let { arrayOf(it) }) }
                    runCatching { launcher?.launch(mime) }.onFailure { finish(null) }
                    return true
                }
                // 🔌 La app intentó hablar con un servidor (fetch / XMLHttpRequest) y no respondió
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                    val msg = m?.message().orEmpty()
                    if (m?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR &&
                        Regex("Failed to fetch|NetworkError|CORS|XMLHttpRequest|ERR_CONNECTION|ERR_NAME_NOT_RESOLVED", RegexOption.IGNORE_CASE).containsMatchIn(msg))
                        addNote(WEB_SERVER, msg.take(120))
                    return super.onConsoleMessage(m)
                }
            }
            setBackgroundColor(Color.WHITE)
        }
        val codeView = TextView(activity).apply {
            setTextColor(Color.parseColor(chrome("#E6E6E6", "#1C1C1E"))); textSize = 12f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(px(12), px(12), px(12), px(12))
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val codeScroll = ScrollView(activity).apply {
            visibility = View.GONE
            // ↔️ el código siempre de izquierda a derecha (también con la app en árabe)
            addView(HorizontalScrollView(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; addView(codeView) })
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        content.addView(codeScroll, FrameLayout.LayoutParams(-1, -1))

        lateinit var tabView: TextView
        lateinit var tabCode: TextView
        fun select(preview: Boolean) {
            web.visibility = if (preview) View.VISIBLE else View.GONE
            codeScroll.visibility = if (preview) View.GONE else View.VISIBLE
            (tabView.background as GradientDrawable).setColor(Color.parseColor(if (preview) "#3D5AFE" else "#2C2C30"))
            (tabCode.background as GradientDrawable).setColor(Color.parseColor(if (preview) "#2C2C30" else "#3D5AFE"))
        }
        var shown: String? = "\u0000"   // lo último que se mostró (para no recargar si no cambió)
        fun isLive() = live != null && runCatching { live() }.getOrDefault(false)
        /** Muestra lo que se armó (en el hilo de la pantalla); no recarga si no cambió nada. */
        fun show(result: Pair<String, String>?, force: Boolean) {
            val key = result?.first
            if (!force && key == shown) return
            shown = key
            if (result == null) {
                val msg = if (isLive()) esc(tx("✦ La IA está escribiendo el código de tu app…")) + "<br><br>" +
                    esc(tx("Aquí vas a ver cómo se va armando; esta ventana se actualiza sola.")) else txHtml(emptyMessage)
                web.loadDataWithBaseURL(null, "<html><body style='background:${chrome("#0b0b0d", "#F2F2F7")};color:${chrome("#ccc", "#3A3A3C")};font-family:sans-serif;padding:24px;font-size:16px'>" +
                    msg + "</body></html>", "text/html", "UTF-8", null)
                codeView.text = tx("(sin código todavía)")
            } else {
                // 🌐 Tu página completa se abre desde su dirección (sus imágenes, datos y enlaces funcionan)
                if (result.first.startsWith("@@URL:")) web.loadUrl(result.first.removePrefix("@@URL:"))
                else web.loadDataWithBaseURL("https://$WEB_HOST/", result.first, "text/html", "UTF-8", null)
                codeView.text = result.second
            }
        }
        // ⚡ La app se arma en segundo plano (antes se armaba en el hilo de la pantalla y Cumulus se congelaba
        //    varios segundos con un proyecto grande); mientras tanto se ve «Cargando tu app…»
        var gen = 0
        fun reload(force: Boolean = true) {
            val my = ++gen
            webNotes.clear(); refreshNotes(notesView, webNotes, notesOpen)
            if (shown == "\u0000") web.loadDataWithBaseURL(null, "<html><body style='background:${chrome("#0b0b0d", "#F2F2F7")};color:${chrome("#ccc", "#3A3A3C")};font-family:sans-serif;" +
                "padding:24px;font-size:16px'>⏳ " + esc(tx("Cargando tu app…")) + "</body></html>", "text/html", "UTF-8", null)
            Thread {
                val result = runCatching { build() }.getOrNull()
                activity.runOnUiThread { if (my == gen && dialog.isShowing) show(result, force) }
            }.start()
        }
        tabView = pill(tx("Vista")) { select(true) }
        tabCode = pill(tx("Código")) { select(false) }
        var otherAi: TextView? = null
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) }
        tools.addView(tabView, lp); tools.addView(tabCode, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        // 🔄 Con [onRefresh] (tu código pegado): borra TODO lo que pegaste (todos los archivos) y la app, y la ventana queda vacía
        tools.addView(pill("🔄") { runCatching { onRefresh?.invoke() }; reload() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        // 🤖 Llevar el proyecto a otra IA (Claude, Gemini, ChatGPT…): todo el código en un solo texto para pegar
        //    Ícono: círculo turquesa → morado con estrellas (res/drawable/ic_otra_ia.xml)
        if (handoff != null) tools.addView(pill(tx("Otra IA")) { handoff() }.apply {
            otherAi = this
            runCatching { androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_otra_ia) }.getOrNull()?.let { ic ->
                ic.setBounds(0, 0, px(20), px(20))
                setCompoundDrawablesRelative(ic, null, null, null)
                compoundDrawablePadding = px(7)
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        // ✕ Cerrar: arriba a la derecha, junto al título (ya no se esconde aunque haya más botones)
        bar.addView(pill("✕") { dialog.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(notesView, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        // ☀️🌙 Las barras del teléfono (hora y batería arriba, la línea de gestos abajo) con el color de la ventana:
        //    blancas en modo claro (antes la de abajo quedaba negra) y negras en modo oscuro, con sus íconos visibles
        dialog.window?.let { w ->
            val barColor = Color.parseColor(chrome("#0B0B0D", "#F2F2F7"))
            // (la ventana se dibuja también detrás de las barras: así la de gestos toma el color de la ventana; antes Android
            //  la dejaba negra porque esta clase de ventana no pinta el fondo de las barras)
            runCatching {
                @Suppress("DEPRECATION")
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) w.isNavigationBarContrastEnforced = false
            }
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(barColor))
            w.decorView.setBackgroundColor(barColor)
            @Suppress("DEPRECATION")
            w.statusBarColor = barColor
            @Suppress("DEPRECATION")
            w.navigationBarColor = barColor
            runCatching {
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = uiLight
                    isAppearanceLightNavigationBars = uiLight
                }
            }
        }
        // 📱 Android 15/16 dibujan de borde a borde: la barra de arriba y la de gestos no tapan la vista previa ni sus botones
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        // ✦ Se está armando: se actualiza sola cada 2,5 s hasta que la IA termina (y una vez más al final)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val still = isLive()
                titleView.text = if (still) "${tx(title)} · ✦ ${tx("armándose…")}" else tx(title)
                reload(force = false)
                if (still) handler.postDelayed(this, 2500)
            }
        }
        // 🌍 Cuando llega una traducción: los botones de arriba y la vista se actualizan (sin recargar si nada cambió)
        val relabel: () -> Unit = {
            if (dialog.isShowing) {
                tabView.text = tx("Vista"); tabCode.text = tx("Código"); otherAi?.text = tx("Otra IA")
                titleView.text = if (isLive()) "${tx(title)} · ✦ ${tx("armándose…")}" else tx(title)
                reload(force = shown == null)
            }
        }
        onTranslated = relabel
        dialog.setOnDismissListener { handler.removeCallbacks(tick); if (onTranslated === relabel) onTranslated = null; runCatching { web.destroy() } }
        select(true)
        reload()
        if (isLive()) {
            titleView.text = "${tx(title)} · ✦ ${tx("armándose…")}"
            handler.postDelayed(tick, 2500)
        }
        dialog.show()
    }

    /** Para que cada vez que se elige un archivo se use una llave distinta. */
    private var webAsk = 0

    /** Los avisos de la app web: cerrados, la primera línea y cuántos hay; abiertos, todos. */
    private fun refreshNotes(v: TextView, notes: Collection<String>, open: Boolean) {
        if (notes.isEmpty()) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        v.text = if (open) notes.joinToString("\n") else tx(WEB_NOTES) + " · ${notes.size}"
    }

    /** ¿Lo que faltó es algo de su servidor? (api/…, .php, enviar un formulario o pedir datos sin ser un archivo) */
    private fun needsServer(path: String, request: android.webkit.WebResourceRequest?): Boolean {
        val p = path.lowercase(java.util.Locale.ROOT)
        if (Regex("\\.(php|aspx?|py|rb|jsp|cgi|pl)$").containsMatchIn(p) || p.startsWith("api/") || p.contains("/api/") || p.startsWith("graphql")) return true
        if (request == null) return false
        if (request.method != null && request.method != "GET") return true
        return !request.isForMainFrame && !p.substringAfterLast('/').contains('.')
    }

    /**
     * 📷🎤📍 Cumulus te pregunta si la app web puede usar la cámara, el micrófono o tu ubicación; si dices que sí,
     * Android te pide el permiso (si aún no se lo diste a Cumulus). [done] recibe los permisos concedidos.
     */
    private fun askWebPermission(activity: Activity, names: List<String>, perms: List<String>, done: (Set<String>) -> Unit) {
        android.app.AlertDialog.Builder(activity)
            .setMessage(tx(WEB_ASKS) + " " + names.joinToString(", "))
            .setPositiveButton(tx("Permitir")) { _, _ ->
                val have = perms.filter { androidx.core.content.ContextCompat.checkSelfPermission(activity, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }.toSet()
                val missing = perms.filter { it !in have }
                val reg = (activity as? androidx.activity.ComponentActivity)?.activityResultRegistry
                if (missing.isEmpty() || reg == null) { done(have); return@setPositiveButton }
                var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
                launcher = reg.register("web_perm_" + (++webAsk), androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    done(have + res.filterValues { it }.keys); launcher?.unregister()
                }
                runCatching { launcher?.launch(missing.toTypedArray()) }.onFailure { done(have); launcher?.unregister() }
            }
            .setNegativeButton(tx("No permitir")) { _, _ -> done(emptySet()) }
            .setOnCancelListener { done(emptySet()) }
            .show()
    }
}
