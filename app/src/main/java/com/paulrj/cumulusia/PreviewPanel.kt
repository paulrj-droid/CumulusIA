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

    /** [path]: la ruta completa que dio el proyecto («📍 Ruta completa: MiApp/app/src/…»), sin la carpeta principal. */
    private data class CodeBlock(val lang: String, val code: String, val fileName: String?, val path: String? = null)

    // ------------------------------------------------------------------ bloques de código del chat
    private fun codeBlocks(text: String): List<CodeBlock> {
        val out = mutableListOf<CodeBlock>()
        Regex("```([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val before = text.substring(maxOf(0, m.range.first - 300), m.range.first)
            val name = Regex("([\\w.-]+\\.(xml|html?|css|js|kt|java|gradle))", RegexOption.IGNORE_CASE)
                .findAll(before).lastOrNull()?.value
            // 📍 Proyectos de Cumulus: cada archivo trae su ruta completa justo antes del código (en cualquier idioma).
            //    Así llegan TODOS a la otra IA en su lugar: build.yml, CMakeLists.txt, native-lib.cpp, gradle.properties…
            val full = Regex("📍[^:：\\n]*[:：]\\s*(\\S+)").findAll(before).lastOrNull()?.groupValues?.get(1)
                ?.takeIf { before.substring(before.lastIndexOf("📍")).count { it == '\n' } <= 2 }
            val path = full?.let { if (it.contains('/')) it.substringAfter('/') else it }
            out += CodeBlock(m.groupValues[1].lowercase(), m.groupValues[2], path?.substringAfterLast('/') ?: name, path)
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
            sb.append("```").append(b.lang.ifBlank { path.substringAfterLast('.', "") }).append('\n')
            sb.append(b.code.trimEnd()).append("\n```\n")
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
        val js = pool.filter { isJs(it) }.distinctBy { it.fileName ?: it.code.hashCode().toString() }.joinToString("\n;\n") { it.code }
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
var H={};
function alClick(id,fn){H[id]=fn;document.querySelectorAll('[data-id="'+id+'"]').forEach(function(e){e.dataset.h='1';if(e._hb)return;e._hb=1;
 e.addEventListener('click',function(ev){ev.stopPropagation();var f=H[id];if(f)try{f(e)}catch(x){aviso('⚠️ '+x.message)}});});}
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
function abrir(n){var k=nrm(n);if(!k)return false;var keys=[k];for(var s in SYN){if(k.indexOf(s)>=0||SYN[s].split(' ').some(function(w){return k.indexOf(w)>=0}))keys=keys.concat(SYN[s].split(' '));}
 for(var i=0;i<NAMES.length;i++){var y=nrm(NAMES[i]);if(keys.some(function(w){return w.length>2&&y.indexOf(w)>=0})){show(i);return true;}}return false;}
function scr(e){return e.closest('.screen');}
document.addEventListener('click',function(ev){var e=ev.target.closest('.nv,.bn,.fab,.ibtn,.btn,input[type=checkbox]');if(!e||e.dataset.h)return;
 if(e.matches('.nv,.bn')){var t=e.textContent.replace(/^[^\w\u00C0-\u024F]+/,'').trim();var d=e.closest('.dl');if(d)d.classList.remove('ol','or');
  if(!abrir(t))aviso('📂 Abriste: '+t);return;}
 if(e.matches('input[type=checkbox]')){aviso(e.checked?'✅ Activado':'⬜ Desactivado');return;}
 var s=scr(e),l=s&&s.querySelector('.lst'),inp=s&&Array.prototype.find.call(s.querySelectorAll('input.inp'),function(i){return i.value.trim()});
 var tx=e.textContent.trim();
 if(e.matches('.fab')||tx==='＋'){if(l){agregar(l,inp?inp.value.trim():'✨ Nuevo elemento');if(inp)inp.value='';}else aviso('＋ Agregar');return;}
 if(tx==='➤'){if(inp){if(l)agregar(l,inp.value.trim());else burbuja(inp.value.trim());inp.value='';}else aviso('✍️ Escribe algo primero');return;}
 if(tx==='♥'){e.classList.toggle('liked');return;}
 if(e.matches('.ibtn')&&(tx==='☰'||tx==='⚙'))return;
 if(e.matches('.btn')&&inp){if(l)agregar(l,inp.value.trim());else burbuja(inp.value.trim());inp.value='';return;}
 aviso('👆 '+(tx||'Botón'));});
"""

    fun buildAppMockup(messagesNewestFirst: List<String>): Pair<String, String>? {
        val layouts = LinkedHashMap<String, String>()
        val strings = HashMap<String, String>()
        val colors = HashMap<String, String>()
        val shapes = HashMap<String, String>()   // fondos redondeados (res/drawable/*.xml con <shape>)
        val navs = LinkedHashMap<String, String>()   // 🧭 res/navigation/*.xml (qué fragmento se ve primero)
        val menus = HashMap<String, String>()        // ☰ res/menu/*.xml (opciones del menú lateral y de la barra de abajo)
        var n = 1
        // Del más viejo al más nuevo, para que la versión nueva de un archivo reemplace a la vieja
        for (msg in messagesNewestFirst.asReversed()) for (b in codeBlocks(msg)) {
            val c = b.code
            if (!(b.lang == "xml" || c.trimStart().startsWith("<"))) continue
            when {
                c.contains("<shape") && b.fileName != null -> shapes[b.fileName.substringBeforeLast('.')] = c
                c.contains("<navigation") -> navs[b.fileName?.substringBeforeLast('.') ?: "nav_${n++}"] = c
                Regex("<menu[\\s>]").containsMatchIn(c) && !viewRoots.containsMatchIn(c) ->
                    menus[b.fileName?.substringBeforeLast('.') ?: "menu_${n++}"] = c
                c.contains("<resources") -> {
                    Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(c)
                        .forEach { strings[it.groupValues[1]] = it.groupValues[2].replace("\\'", "'").replace("\\n", " ").trim() }
                    Regex("<color\\s+name=\"([^\"]+)\"[^>]*>(.*?)</color>").findAll(c)
                        .forEach { colors[it.groupValues[1]] = it.groupValues[2].trim() }
                }
                viewRoots.containsMatchIn(c) && !c.contains("<manifest") -> {
                    val name = b.fileName?.substringBeforeLast('.') ?: "pantalla_${n++}"
                    layouts.remove(name); layouts[name] = c
                }
            }
        }
        if (layouts.isEmpty()) return null
        // Pantallas principales primero
        val ordered = layouts.entries.sortedBy { if (it.key.startsWith("activity")) 0 else if (it.key.startsWith("fragment")) 1 else 2 }
        val tabs = StringBuilder(); val screens = StringBuilder()
        ordered.forEachIndexed { i, (name, xml) ->
            tabs.append("<button class='tab${if (i == 0) " on" else ""}' onclick='show($i)'>${esc(name)}</button>")
            val body = runCatching { XmlToHtml(strings, colors, layouts, shapes, navs, menus).convert(xml, 0) }
                .getOrElse { "<div class='err'>No pude dibujar este diseño (el XML tiene un error):<br>${esc(it.message ?: "")}</div>" }
            screens.append("<div class='screen' id='s$i' style='display:${if (i == 0) "flex" else "none"}'>$body</div>")
        }
        val namesJs = ordered.joinToString(",", "[", "]") { "'" + it.key.replace("'", "") + "'" }
        // 🧪 Lógica de la maqueta escrita por la IA (maqueta.js): se ejecuta encima de la interactividad básica
        val logic = mockupLogic(messagesNewestFirst)
        val userJs = logic.joinToString("\n") { js ->
            "try{\n" + js.replace("</script", "<\\/script") + "\n}catch(e){aviso('⚠️ La lógica de la maqueta tuvo un error: '+e.message)}" }
        val noteText = if (logic.isNotEmpty()) "📱 Maqueta con la lógica de tu app (simulada en JavaScript). La app real usa el Kotlin al compilarla."
            else "📱 Maqueta interactiva: menús, botones y listas responden como ejemplo. La lógica Kotlin real funciona al compilar la app."
        val page = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{margin:0;background:#0b0b0d;font-family:Roboto,'Segoe UI',sans-serif;color:#e8e8ea}
 .tabs{display:flex;gap:6px;overflow-x:auto;padding:10px;background:#141417;position:sticky;top:0;z-index:5}
 .tab{border:1px solid #3a3a3e;background:#1d1d21;color:#ddd;border-radius:16px;padding:6px 12px;font-size:13px;white-space:nowrap}
 .tab.on{background:#3D5AFE;border-color:#3D5AFE;color:#fff}
 .phone{margin:12px auto;width:min(380px,94vw);min-height:640px;background:#121212;border:8px solid #222;border-radius:28px;overflow:hidden;position:relative}
 .screen{flex-direction:column;min-height:624px;position:relative}
 .v{box-sizing:border-box;min-width:0}
 .txt{padding:2px 0;font-size:14px}
 .btn{background:#3D5AFE;color:#fff;border:none;border-radius:20px;padding:10px 16px;font-size:14px;margin:4px 0}
 .inp{background:#1f1f23;border:1px solid #444;border-radius:8px;color:#fff;padding:10px;font-size:14px;margin:4px 0}
 .img{background:#2a2a30;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#888;min-height:48px;min-width:48px}
 .card{background:#1e1e22;border-radius:14px;padding:12px;margin:6px 0;box-shadow:0 2px 6px #0006}
 .bar{background:#3D5AFE;color:#fff;padding:14px 16px;font-size:18px;font-weight:600}
 .row{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #2a2a2e}
 .fab{position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:16px;background:#3D5AFE;color:#fff;border:none;font-size:24px}
 .err{color:#ff8a80;padding:16px}
 .ibtn{background:transparent;border:none;color:#e8e8ea;width:44px;height:44px;min-width:44px;border-radius:50%;font-size:20px;display:flex;align-items:center;justify-content:center;flex:none;padding:0}
 .dl{flex:1;min-height:624px}
 .drawer{position:absolute;top:0;bottom:0;max-width:86%;z-index:6;transition:transform .25s;box-shadow:0 0 24px #000a;overflow:auto;align-self:stretch!important;height:auto!important;display:flex;flex-direction:column}
 .dl-left{left:0;transform:translateX(-105%)} .dl-right{right:0;transform:translateX(105%)}
 .dl.ol>.dl-left,.dl.or>.dl-right{transform:none}
 .scrim{position:absolute;inset:0;background:#0008;z-index:5;display:none} .dl.ol>.scrim,.dl.or>.scrim{display:block}
 .note{font-size:12px;color:#8e8e93;text-align:center;padding:4px 12px 16px}
 .bar .hamb{display:none;color:#fff;width:32px;height:32px;min-width:32px} .dl .bar .hamb{display:flex}
 #toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#2c2c32;color:#fff;padding:10px 16px;border-radius:18px;
  font-size:13px;opacity:0;transition:opacity .25s;z-index:20;max-width:80vw;text-align:center;box-shadow:0 4px 14px #000a;pointer-events:none}
 .liked{color:#ff4d6d!important}
 .chatlog{display:flex;flex-direction:column;gap:6px;padding:8px;overflow:auto;flex:1;min-height:60px}
 .bub{align-self:flex-end;background:#3D5AFE;color:#fff;border-radius:16px 16px 4px 16px;padding:8px 12px;max-width:75%;font-size:14px;word-wrap:break-word}
 .bub.app{align-self:flex-start;background:#2a2a30;border-radius:16px 16px 16px 4px} .nv,.bn,.btn,.ibtn,.fab{cursor:pointer}
 .swr{display:flex!important;align-items:center;justify-content:space-between;gap:12px;min-height:48px;cursor:pointer}
 .swi{appearance:none;-webkit-appearance:none;width:52px;height:30px;border-radius:999px;background:#555;position:relative;flex:none;margin:0;cursor:pointer;transition:background .2s}
 .swi::after{content:'';position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#ddd;transition:transform .2s,background .2s}
 .swi:checked{background:#5a5a5a} .swi:checked::after{transform:translateX(22px);background:#3D5AFE}
</style></head><body>
<div class="tabs">$tabs</div>
<div class="phone">$screens</div>
<div class="note">$noteText</div><div id="toast"></div>
<script>
function dr(e,s){var d=e.closest('.dl');if(!d)return;d.classList.remove('ol','or');if(s)d.classList.add(s);}
function show(i){document.querySelectorAll('.screen').forEach(function(s,k){s.style.display=k==i?'flex':'none'});
 document.querySelectorAll('.tab').forEach(function(t,k){t.classList.toggle('on',k==i)});}
$INTERACTIVE_JS
var NAMES=$namesJs;
$userJs
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

    /** Convierte un diseño XML de Android en HTML aproximado. */
    private class XmlToHtml(val strings: Map<String, String>, val colors: Map<String, String>, val layouts: Map<String, String>,
                            val shapes: Map<String, String> = emptyMap(), val navs: Map<String, String> = emptyMap(),
                            val menus: Map<String, String> = emptyMap()) {

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

        /** ☰ Opciones de un menú (res/menu): título e ícono de cada <item>. */
        fun menuItems(ref: String?): List<Pair<String, String>> {
            val xml = ref?.substringAfter("/")?.let { menus[it] } ?: return emptyList()
            return Regex("<item\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val title = Regex("android:title=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)?.let { res(it) } ?: return@mapNotNull null
                val icon = iconFor(mapOf("src" to (Regex("android:icon=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: ""), "id" to title))
                    .let { if (it == "●") "•" else it }
                title to icon
            }.toList()
        }

        fun convert(xml: String, depth: Int): String = try { convertRaw(xml, depth) } catch (e: Exception) {
            // Diseño cortado: se completa y se dibuja lo que alcanzó a escribir la IA
            val fixed = repairXml(xml.trim().removePrefix("\uFEFF"))
            if (fixed == xml.trim()) throw e
            convertRaw(fixed, depth) + (if (depth == 0) "<div class='note'>✂️ Este diseño llegó cortado: completé lo que faltaba para poder mostrarlo.</div>" else "")
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
                v.startsWith("@android:color/") -> when (v.substringAfter('/')) { "white" -> "#FFFFFF"; "black" -> "#000000"; "transparent" -> "transparent"; else -> "#888888" }
                v.startsWith("?") || v.startsWith("@") -> null
                else -> v.replace("\\'", "'").replace("\\@", "@").replace("\\?", "?")
            }
        }

        /** Fondo de un @drawable con <shape>: color, degradado, esquinas, borde y forma de círculo. */
        private fun shapeCss(ref: String?): String? {
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

        private fun color(v: String?): String? {
            val c = res(v) ?: return null
            if (!c.startsWith("#")) return null
            val h = c.removePrefix("#")
            return when (h.length) {
                8 -> { val a = h.substring(0, 2).toInt(16) / 255.0; "rgba(${h.substring(2, 4).toInt(16)},${h.substring(4, 6).toInt(16)},${h.substring(6, 8).toInt(16)},$a)" }
                6, 3 -> "#$h"
                else -> null
            }
        }

        private fun dim(v: String?): String? = when {
            v == null -> null
            v == "match_parent" || v == "fill_parent" -> "100%"
            v == "wrap_content" -> null
            v.endsWith("dp") || v.endsWith("dip") || v.endsWith("sp") || v.endsWith("px") ->
                v.replace(Regex("(dp|dip|sp|px)$"), "").toFloatOrNull()?.let { "${it}px" }
            else -> null
        }

        private fun style(tag: String, a: Map<String, String>): String {
            val s = StringBuilder()
            val w = a["layout_width"]; val h = a["layout_height"]
            dim(w)?.let { if (w == "match_parent" || w == "fill_parent") s.append("align-self:stretch;width:auto;") else s.append("width:$it;") }
            dim(h)?.let { if (h == "match_parent" || h == "fill_parent") s.append("flex:1;") else s.append("height:$it;") }
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
            val shapeBg = shapeCss(a["background"])
            if (shapeBg != null) s.append(shapeBg)
            else (color(a["background"]) ?: color(a["backgroundTint"]) ?: color(a["cardBackgroundColor"]))?.let { s.append("background:$it;") }
            color(a["textColor"])?.let { s.append("color:$it;") }
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
            (dim(a["cardElevation"]) ?: dim(a["elevation"]))?.let { s.append("box-shadow:0 ${it} calc($it * 3) #0008;") }
            val stroke = dim(a["strokeWidth"]); val strokeColor = color(a["strokeColor"])
            if (stroke != null && strokeColor != null) s.append("border:$stroke solid $strokeColor;")
            a["fontFamily"]?.let { f -> if (f.contains("medium")) s.append("font-weight:500;") else if (f.contains("bold") || f.contains("black")) s.append("font-weight:700;") }
            a["letterSpacing"]?.toFloatOrNull()?.let { s.append("letter-spacing:${it}em;") }
            a["alpha"]?.toFloatOrNull()?.let { s.append("opacity:$it;") }
            return s.toString()
        }

        /** Ícono de un ImageButton según su imagen, id o descripción (☰ menú, ⚙ ajustes, 🎤, ➤ enviar…). */
        private fun iconFor(a: Map<String, String>): String {
            val k = (a["src"].orEmpty() + " " + a["srcCompat"].orEmpty() + " " + a["id"].orEmpty() + " " + a["contentDescription"].orEmpty()).lowercase()
            return when {
                Regex("menu|hamburg|drawer").containsMatchIn(k) -> "☰"
                Regex("setting|ajuste|config|tune|gear|engran").containsMatchIn(k) -> "⚙"
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
            val st = style(tag, a)
            val text = esc(res(a["text"]) ?: "")
            val hint = esc(res(a["hint"]) ?: "")
            val id = a["id"]?.substringAfter('/')?.let { " title='${esc(it)}' data-id='${esc(it)}'" } ?: ""
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
                    val items = menuItems(a["menu"]).joinToString("") { (t, i) -> "<div class='row nv'><span>$i</span><span>${esc(t)}</span></div>" }
                    val cls = if (parent == "DrawerLayout") (if (lg.contains("end") || lg.contains("right")) "v drawer dl-right" else "v drawer dl-left") else "v"
                    "<div class='$cls'$id style='background:#1b1b20;min-width:240px;$st'>$header$items" to "</div>"
                }
                parent == "DrawerLayout" && (lg.contains("start") || lg.contains("left")) ->
                    "<div class='v drawer dl-left'$id style='$st'>" to "</div>"
                parent == "DrawerLayout" && (lg.contains("end") || lg.contains("right")) ->
                    "<div class='v drawer dl-right'$id style='$st'>" to "</div>"
                tag == "ImageButton" -> {
                    val icon = iconFor(a)
                    val click = when (icon) { "☰" -> " onclick=\"dr(this,'ol')\""; "⚙" -> " onclick=\"dr(this,'or')\""; else -> "" }
                    val tint = color(a["tint"])?.let { "color:$it;" } ?: ""
                    "<button class='ibtn v'$id$click style='$tint$st'>$icon</button>" to ""
                }
                // 🧭 Contenedor de fragmentos: se dibuja la pantalla de inicio (antes quedaba en blanco)
                tag == "FragmentContainerView" || tag == "NavHostFragment" || (tag == "fragment" && parent != null) -> {
                    val frag = fragmentLayout(a)
                    val inner = if (frag != null) runCatching { convert(frag, depth + 1) }.getOrDefault("") else ""
                    "<div class='v'$id style='display:flex;flex-direction:column;$st'>$inner" to "</div>"
                }
                // ▭ Barra de abajo (BottomNavigationView): sus opciones con ícono
                tag == "BottomNavigationView" || tag == "NavigationBarView" -> {
                    val items = menuItems(a["menu"]).ifEmpty { listOf("Inicio" to "🏠", "Buscar" to "🔍", "Perfil" to "👤") }
                    "<div class='v'$id style='display:flex;flex-direction:row;justify-content:space-around;background:#18181c;padding:8px 0;$st'>" +
                        items.joinToString("") { (t, i) -> "<div class='bn' style='display:flex;flex-direction:column;align-items:center;font-size:11px'><span style='font-size:18px'>$i</span>${esc(t)}</div>" } to "</div>"
                }
                tag == "HorizontalScrollView" -> "<div class='v'$id style='display:flex;flex-direction:row;overflow-x:auto;$st'>" to "</div>"
                tag == "LinearLayout" -> {
                    val dir = if (a["orientation"] == "horizontal") "row" else "column"
                    "<div class='v'$id style='display:flex;flex-direction:$dir;${if (dir == "row") "align-items:center;gap:6px;" else ""}$st'>" to "</div>"
                }
                tag.endsWith("Toolbar") || tag == "ActionBar" ->
                    // ☰ Si la pantalla tiene menú lateral, la barra trae el botón para abrirlo (como en la app real)
                    "<div class='v bar'$id style='display:flex;align-items:center;gap:10px;$st'><button class='ibtn hamb' onclick=\"dr(this,'ol')\">☰</button>" +
                        "${esc(res(a["title"]) ?: strings["app_name"] ?: "Mi app")}" to "</div>"
                tag.endsWith("CardView") -> "<div class='v card'$id style='display:flex;flex-direction:column;$st'>" to "</div>"
                tag.endsWith("ScrollView") -> "<div class='v'$id style='display:flex;flex-direction:column;overflow:auto;$st'>" to "</div>"
                tag.endsWith("Layout") || tag == "merge" || tag == "RadioGroup" ->
                    "<div class='v'$id style='display:flex;flex-direction:column;position:relative;$st'>" to "</div>"
                tag.contains("FloatingActionButton") ->
                    "<button class='fab'$id style='$st'>${if (text.isNotEmpty()) text else "+"}</button>" to ""
                tag.endsWith("Button") && !tag.endsWith("RadioButton") && !tag.endsWith("ToggleButton") ->
                    "<button class='btn v'$id style='$st'>${if (text.isNotEmpty()) text else if (tag == "ImageButton") "⬤" else "Botón"}</button>" to ""
                tag.endsWith("EditText") || tag == "AutoCompleteTextView" -> {
                    val flat = if (a["background"] == "@null" || a["background"] == "@android:color/transparent") "background:transparent;border:none;" else ""
                    "<input class='inp v'$id placeholder='${if (hint.isNotEmpty()) hint else "Escribe aquí"}' value='$text' style='$flat$st'>" to ""
                }
                tag.endsWith("TextView") || tag == "Chip" ->
                    "<div class='txt v'$id style='$st'>${if (text.isNotEmpty()) text else "Texto"}</div>" to ""
                tag.endsWith("ImageView") -> "<div class='img v'$id style='$st'>🖼️</div>" to ""
                tag == "RecyclerView" || tag == "ListView" || tag == "GridView" -> {
                    // Si el diseño indica su fila (tools:listitem), se dibuja 3 veces; si no, filas de ejemplo
                    val item = a["listitem"]?.substringAfter("/")?.let { layouts[it] }
                    val rows = (1..3).joinToString("") { i ->
                        if (item != null) runCatching { convert(item, depth + 1) }.getOrDefault("")
                        else "<div class='row'><div class='img' style='width:36px;height:36px;min-height:36px'>📄</div><div>Elemento $i</div></div>"
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
                tag.endsWith("ProgressBar") -> "<progress class='v'$id style='$st'></progress>" to ""
                tag.endsWith("Spinner") -> "<select class='inp v'$id style='$st'><option>Opción 1</option><option>Opción 2</option></select>" to ""
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
    fun open(activity: Activity, title: String, emptyMessage: String, live: (() -> Boolean)? = null,
             handoff: (() -> Unit)? = null, build: () -> Pair<String, String>?) {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0B0D")) }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(10), px(8), px(10)); setBackgroundColor(Color.parseColor("#141417"))
        }
        val titleView = TextView(activity).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
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
            setBackgroundColor(Color.parseColor("#141417"))
            addView(tools)
        }
        fun pill(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(px(12), px(7), px(12), px(7))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#2C2C30")) }
            setOnClickListener { onClick() }
        }
        val content = FrameLayout(activity)
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()   // permite alert(), confirm(), etc.
            setBackgroundColor(Color.WHITE)
        }
        val codeView = TextView(activity).apply {
            setTextColor(Color.parseColor("#E6E6E6")); textSize = 12f; typeface = Typeface.MONOSPACE
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
        fun reload(force: Boolean = true) {
            val result = runCatching { build() }.getOrNull()
            val key = result?.first
            if (!force && key == shown) return
            shown = key
            if (result == null) {
                val msg = if (isLive()) "✦ La IA está escribiendo el código de tu app…<br><br>Aquí vas a ver cómo se va armando; " +
                    "esta ventana se actualiza sola." else emptyMessage
                web.loadDataWithBaseURL(null, "<html><body style='background:#0b0b0d;color:#ccc;font-family:sans-serif;padding:24px;font-size:16px'>" +
                    msg + "</body></html>", "text/html", "UTF-8", null)
                codeView.text = "(sin código todavía)"
            } else {
                web.loadDataWithBaseURL("https://preview.cumulus.local/", result.first, "text/html", "UTF-8", null)
                codeView.text = result.second
            }
        }
        tabView = pill("Vista") { select(true) }
        tabCode = pill("Código") { select(false) }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) }
        tools.addView(tabView, lp); tools.addView(tabCode, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        tools.addView(pill("🔄") { reload() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        // 🤖 Llevar el proyecto a otra IA (Claude, Gemini, ChatGPT…): todo el código en un solo texto para pegar
        //    Ícono: círculo turquesa → morado con estrellas (res/drawable/ic_otra_ia.xml)
        if (handoff != null) tools.addView(pill("Otra IA") { handoff() }.apply {
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
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
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
                titleView.text = if (still) "$title · ✦ armándose…" else title
                reload(force = false)
                if (still) handler.postDelayed(this, 2500)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick); runCatching { web.destroy() } }
        select(true)
        reload()
        if (isLive()) {
            titleView.text = "$title · ✦ armándose…"
            handler.postDelayed(tick, 2500)
        }
        dialog.show()
    }
}


