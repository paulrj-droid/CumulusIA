package com.paulrj.cumulusia

import java.util.Locale

/**
 * 📚 GUÍA DE PROYECTOS (modo Ingeniero de código)
 *
 * Conocimiento escrito a mano para que la IA nunca invente los pasos:
 *  • Cómo se arma un proyecto y dónde va cada carpeta (Android Kotlin/XML, C++ con llama.cpp, web).
 *  • Cómo subirlo y usarlo en GitHub (crear carpetas, subir, reemplazar, compilar el APK, GitHub Pages),
 *    Replit, Netlify, CodePen y Glitch, Android Studio, instalar el APK y los errores más comunes.
 *
 * Cómo se usa:
 *  1. find(pregunta) elige el tema que corresponde (solo ese, para no llenar la memoria del modelo).
 *  2. Si es una pregunta de pasos («¿cómo lo subo a GitHub?») se responde con el texto exacto del tema.
 *  3. Si es otra cosa (un error, una explicación), el tema se le pasa a la IA como conocimiento y ella conversa.
 */
object ProjectGuide {

    class Topic(val id: String, val title: String, val match: Regex, val text: String)

    // ===================================================================================
    // 🌍 EN TODOS LOS IDIOMAS DE LA APP
    //    El español está aquí en el código. Los otros 13 idiomas (los mismos de la app) están en
    //    assets/i18n/guide.json: temas, títulos y frases. Se usa el idioma elegido en la app.
    //    Si falta una traducción, se usa el inglés (y si tampoco está, el español).
    // ===================================================================================
    // Palabras de cada tema en los 14 idiomas (para entender la pregunta en cualquier idioma)
    private const val ML_STRUCT = "structure|folders?|estrutura|pastas?|dossiers?|struttura|cartell[ae]|struktur|ordner|структур|папк|" +
        "结构|結構|文件夹|資料夾|目录|구조|폴더|構成|構造|フォルダ|cấu trúc|thư mục|โครงสร้าง|โฟลเดอร์|بنية|هيكل|مجلد"
    private const val ML_UPLOAD = "upload|enviar|carregar|téléverser|mettre en ligne|caricare|hochladen|загруз|上传|上傳|업로드|올리|" +
        "アップロード|unggah|tải lên|อัปโหลด|رفع"
    private const val ML_REPO = "repositor|dépôt|репозитор|仓库|倉庫|저장소|リポジトリ|kho mã"
    private const val ML_COMPILE = "compil|build(?!\\.)|kompil|компил|собрат|сборк|编译|構建|构建|컴파일|빌드|コンパイル|ビルド|" +
        "biên dịch|คอมไพล์|بناء التطبيق"
    private const val ML_PUBLISH = "publish|publier|pubblic|veröffentlich|опубликов|публик|发布|發布|上线|게시|배포|公開|terbit|" +
        "publikasi|xuất bản|เผยแพร่|نشر|go live|hosting|hospedar|héberger|ospitare|хостинг"
    private const val ML_LINK = "lien|collegamento|ссылк|链接|連結|링크|リンク|tautan|liên kết|ลิงก์|رابط"
    private const val ML_COMPUTER = "computer|computador|ordinateur|компьютер|ноутбук|电脑|電腦|컴퓨터|노트북|パソコン|コンピュータ|komputer|" +
        "máy tính|คอมพิวเตอร์|كمبيوتر|حاسوب"
    private const val ML_INSTALL = "install|instal|установ|安装|安裝|설치|インストール|pasang|cài đặt|cài|ติดตั้ง|تثبيت"
    private const val ML_ERROR = "erro|erreur|errore|fehler|ошибк|错误|錯誤|报错|오류|에러|エラー|kesalahan|galat|lỗi|ข้อผิดพลาด|خطأ|bug"

    /** Idioma con el que se responde (lo pone MainActivity con el idioma elegido en la app). */
    @Volatile var lang: String = "es"
    @Volatile private var data: org.json.JSONObject? = null
    @Volatile private var langs: List<String> = emptyList()

    /** Carga las traducciones (una sola vez) y toma el idioma elegido en la app. */
    fun use(ctx: android.content.Context, language: String) {
        lang = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_').ifBlank { "es" }
        if (data != null) return
        runCatching {
            val o = org.json.JSONObject(ctx.assets.open("i18n/guide.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val a = o.getJSONArray("langs")
            langs = (0 until a.length()).map { a.getString(it) }
            data = o
        }
    }

    private fun tr(section: String, key: String, es: String): String {
        if (lang == "es") return es
        val d = data ?: return es
        val i = langs.indexOf(lang).takeIf { it >= 0 } ?: langs.indexOf("en")
        if (i < 0) return es
        return d.optJSONObject(section)?.optJSONArray(key)?.optString(i)?.takeIf { it.isNotBlank() } ?: es
    }

    /** Texto del tema en el idioma de la app. */
    fun text(t: Topic): String = tr("topics", t.id, t.text)
    /** Título del tema en el idioma de la app. */
    fun title(t: Topic): String = tr("titles", t.id, t.title)

    /** Frase en el idioma de la app; {0}, {1}… se reemplazan por los datos. */
    fun p(key: String, vararg args: Any): String {
        var out = tr("p", key, P_ES[key] ?: key)
        args.forEachIndexed { i, v -> out = out.replace("{$i}", v.toString()) }
        return out
    }

    /** Frases (en español; las traducciones están en guide.json). */
    val P_ES: Map<String, String> = mapOf(
        "p_title" to "🧩 Proyecto «{0}» completo en {1} partes, listo para copiar y pegar.",
        "p_page_desc" to "🌐 Página web con menú arriba (☰ en el celular), portada con botón, secciones, tarjetas destacadas, " +
            "contacto con formulario y pie de página (color principal {0}).",
        "p_app_desc" to "🏗️ Trae menú lateral izquierdo ☰, ajustes a la derecha ⚙️, pantalla de inicio con tarjetas y atajos, " +
            "y barra de escritura con 🎤 micrófono y ➤ enviar (color principal {0}).",
        "p_modules" to "🧩 Módulos incluidos: {0}",
        "p_root" to "📂 Carpeta principal del proyecto: «{0}». Todos los archivos van DENTRO de ella, cada uno en la carpeta que se indica.",
        "p_part" to "📄 Parte {0} de {1} — {2}",
        "w_at_root" to "📁 Va en: {0}/   ← directo en la carpeta principal «{0}» (no dentro de otra carpeta).",
        "w_in_dir_parent" to "📁 Va en: {0}/   ← dentro de la carpeta «{1}» (que está en {2}).",
        "w_in_dir" to "📁 Va en: {0}/   ← dentro de la carpeta «{1}».",
        "w_main_root" to "📁 Va directo en la carpeta principal de tu proyecto (no dentro de otra carpeta).",
        "w_create" to "   Crea ahí el archivo «{0}» y pega todo el código.",
        "w_mkdir" to "   Si alguna carpeta no existe, créala con ese nombre exacto (respeta mayúsculas y minúsculas).",
        "w_full" to "   📍 Ruta completa: {0}",
        "w_name" to "   Nombre exacto del archivo: «{0}»",
        "w_your_root" to "tu carpeta principal del proyecto",
        "tree_title" to "🗂️ DÓNDE VA CADA ARCHIVO (así deben quedar las carpetas):",
        "tree_title_parts" to "🗂️ DÓNDE VA CADA ARCHIVO (dentro de la carpeta principal de tu proyecto):",
        "loc_root" to "{0} → directo en la carpeta principal (no dentro de otra carpeta)",
        "p_preview_web" to "🖥️ Para verla funcionando, toca «Abrir vista previa» aquí abajo (o ➕ → «Vista previa web»).",
        "p_preview_app" to "📱 Para ver el diseño de las pantallas, toca «Ver maqueta» aquí abajo (o ➕ → «Maqueta de la app»).",
        "p_next_web" to "📚 ¿Quieres publicarla con un enlace? Pregúntame «¿cómo la publico en Netlify?», «¿cómo uso GitHub Pages?» " +
            "o «¿cómo la pruebo en CodePen?».",
        "p_next_app" to "📚 ¿No sabes cómo seguir? Pregúntame «¿cómo lo subo a GitHub?» o «¿cómo saco el APK?» y te guío paso a paso.",
        "p_done" to "✅ Copia cada parte en su carpeta con el mismo nombre de archivo. Si quieres que agregue algo (más pantallas, " +
            "guardar en base de datos, otro diseño, etc.), dime qué y lo sumo al proyecto.",
        "q_one" to "¡Claro! Te digo exactamente dónde va:",
        "q_many" to "¡Claro! Hay {0} archivos con ese nombre, cada uno en su lugar:",
        "q_remember" to "📂 Recuerda: todo va dentro de la carpeta principal «{0}».",
        "q_more" to "¿Te queda alguna otra duda? Pregúntame lo que quieras 😊",
        "q_rootname" to "📂 La carpeta principal de tu proyecto se llama «{0}».\n\n1. Crea una carpeta con ese nombre exacto (sin espacios ni tildes).\n" +
            "2. Dentro de ella van todas las demás carpetas y archivos, así:",
        "q_rootname_end" to "¿Quieres que te explique alguna carpeta en particular?",
        "q_map" to "¡Te ayudo! 😊 Cada archivo va en su propia carpeta. Arriba de cada código del proyecto dice «📁 Va en:» " +
            "con la carpeta exacta. Aquí tienes todos juntos:",
        "q_tree" to "🗂️ Así deben quedar las carpetas:",
        "q_map_end" to "Si me dices el nombre del archivo (por ejemplo «¿dónde va {0}?»), te digo solo ese.",
        "q_fallback" to "Te dejo el mapa de tu proyecto para que sepas dónde va cada cosa:",
        "q_fallback_end" to "Cuéntame tu duda con otras palabras y te ayudo 😊",
        "g_files" to "📋 TUS ARCHIVOS: en GitHub escribe estas rutas, una por archivo (Add file → Create new file):",
        "g_next_apk" to "👉 Cuando estén todos, el APK se arma solo: pregúntame «¿cómo saco el APK?».",
        "g_next_web" to "👉 Cuando estén todos, publícala con un enlace: pregúntame «¿cómo uso GitHub Pages?» o «¿cómo la publico en Netlify?».",
        "g_web_note" to "⚠️ Tu proyecto es una {0} (HTML + CSS + JavaScript): no se arma un APK ni se abre en Android Studio. " +
            "Publícala gratis con GitHub Pages o Netlify y ábrela desde el enlace en cualquier celular o PC. " +
            "Para tenerla como app: ábrela en Chrome → ⋮ → «Agregar a la pantalla principal».",
        "g_webapp" to "app web",
        "g_webpage" to "página web",
        "g_no_wf" to "⚠️ Este proyecto no trae el archivo .github/workflows/compilar.yml. " +
            "Pídeme «créame el archivo compilar.yml para GitHub Actions» y te lo doy.",
        "g_tree" to "📂 ASÍ QUEDA TU PROYECTO «{0}»:",
        "g_android_note" to "⚠️ Tu proyecto es una app Android (Kotlin): eso no se publica como página. " +
            "Para tenerla en tu teléfono saca el APK con GitHub Actions (pregúntame «¿cómo saco el APK?»).",
        "g_web_ok" to "✅ Tu {0} sirve tal cual aquí. Tus archivos: {1}.",
        "g_stuck" to "¿Te trabaste en algún paso? Dime en cuál y te ayudo 😊",
        "lbl_doubt" to "💬 Duda del proyecto",
        "lbl_guide" to "📚 Guía"
    )

    // ===================================================================================
    // 🏗️ ESTRUCTURA DE LOS PROYECTOS
    // ===================================================================================
    private val ANDROID = Topic(
        "android", "Cómo se arma un proyecto Android (Kotlin + XML)",
        Regex("(?i)(estructura|c[oó]mo se arma|c[oó]mo (se )?organiza|qu[eé] carpetas (lleva|tiene|van)|partes del proyecto|" +
            "para qu[eé] (sirve|es) (la carpeta|el archivo|cada)|manifest|res/|layout|values|build\\.gradle|settings\\.gradle|paquete|package|" + ML_STRUCT + ")"),
        """
📱 CÓMO SE ARMA UN PROYECTO ANDROID (Kotlin + XML)

Todo va dentro de UNA carpeta principal (el nombre de tu app, sin espacios ni tildes). Adentro:

📂 MiApp/                         ← carpeta principal
   📄 settings.gradle             ← dice cómo se llama el proyecto y que tiene el módulo «app»
   📄 build.gradle                ← versiones de las herramientas (Android y Kotlin)
   📄 gradle.properties           ← ajustes de compilación (activa AndroidX)
   📁 .github/workflows/          ← (opcional) compilar.yml: GitHub arma el APK solo
   📁 app/                        ← tu aplicación
      📄 build.gradle             ← nombre del paquete, versión, minSdk y librerías
      📁 src/main/
         📄 AndroidManifest.xml   ← pantallas de la app y permisos (cámara, internet…)
         📁 java/com/ejemplo/miapp/  ← el código Kotlin (.kt)
         📁 res/                  ← los recursos
            📁 layout/            ← el diseño de cada pantalla (.xml)
            📁 values/            ← colores (colors.xml), textos (strings.xml), temas (themes.xml)
            📁 drawable/          ← íconos y fondos (.xml o .png)
         📁 cpp/                  ← (solo si usa C++) native-lib.cpp y CMakeLists.txt

🔑 Reglas que no fallan:
1. La carpeta del código sigue el nombre del paquete: «package com.ejemplo.miapp» → java/com/ejemplo/miapp/
2. Respeta mayúsculas y minúsculas: «MainActivity.kt» no es lo mismo que «mainactivity.kt».
3. Nunca dejes espacios en los nombres: «layout » (con espacio al final) rompe todo.
4. Los archivos de res/ van en minúscula y sin espacios: activity_main.xml, colors.xml.
5. Cada archivo va en la carpeta que te indico arriba de su código («📁 Va en:»).
""".trimIndent()
    )

    private val WEB = Topic(
        "web", "Cómo se arma una página o app web",
        Regex("(?i)(estructura|c[oó]mo se arma|qu[eé] carpetas|index\\.html|style\\.css|script\\.js|logic\\.js|botones\\.js|" +
            "orden de los (archivos|scripts)|en qu[eé] orden|" + ML_STRUCT + ")"),
        """
🌐 CÓMO SE ARMA UNA PÁGINA O APP WEB (HTML + CSS + JavaScript)

Es lo más simple: TODOS los archivos van juntos, sueltos dentro de UNA carpeta (sin subcarpetas).

📂 MiPagina/
   📄 index.html     ← la página (DEBE llamarse así: es la que se abre primero)
   📄 style.css      ← los colores y el diseño
   📄 logic.js       ← (app web) la lógica: qué responde y qué hace cada cosa
   📄 botones.js     ← los botones y partes que le fuiste agregando
   📄 script.js      ← lo que hace funcionar la pantalla (menú, barra de escritura…)

🔑 Reglas que no fallan:
1. Los nombres tal cual, en minúscula: index.html, style.css, script.js.
2. index.html ya trae las líneas que llaman a los demás (<link href="style.css"> y <script src="…">):
   si cambias un nombre de archivo, también hay que cambiarlo ahí.
3. Sirve igual para una página web (negocio, portafolio…) y para una app web (con botones y lógica).
4. Para verla en el celular: toca «Abrir vista previa» aquí en Cumulus.
5. Para tenerla en internet con un enlace: GitHub Pages o Netlify (pregúntame cómo).
6. Para usarla como app en el celular: ábrela en Chrome → ⋮ → «Agregar a la pantalla principal».
7. Una página web no se convierte en APK: el APK es solo para apps Android (Kotlin).
""".trimIndent()
    )

    private val CPP = Topic(
        "cpp", "Proyecto con C++ y llama.cpp (IA local)",
        Regex("(?i)(c\\+\\+|\\bcpp\\b|llama\\.?cpp|cmake|cmakelists|native-lib|jni|\\.so\\b|ndk)"),
        """
🦙 PROYECTO ANDROID CON C++ (llama.cpp, IA local)

Es un proyecto Android normal (Kotlin + XML) con una carpeta extra para el motor en C++:

📂 MiApp/app/src/main/cpp/
   📄 native-lib.cpp      ← el puente entre Kotlin y el motor (JNI)
   📄 CMakeLists.txt      ← le dice a Android cómo compilar el C++
   📁 llama.cpp/          ← el código de llama.cpp (NO lo copias a mano)

🔑 Lo importante:
1. La carpeta llama.cpp es enorme: no la subas desde el celular. El archivo .github/workflows/compilar.yml
   que viene en tu proyecto la descarga sola en GitHub antes de compilar.
2. El nombre del paquete en native-lib.cpp (Java_com_ejemplo_miapp_…) debe coincidir con el de tu código Kotlin.
   Si cambias el paquete, también cambia esos nombres.
3. El modelo (.gguf) no va dentro del proyecto: la app lo pide al abrirse y lo guarda en el teléfono.
4. Compilar C++ tarda más (10 a 20 minutos en GitHub). Es normal.
""".trimIndent()
    )

    // ===================================================================================
    // 🐙 GITHUB
    // ===================================================================================
    private val GITHUB = Topic(
        "github", "Subir tu proyecto a GitHub desde el celular",
        Regex("(?i)(github|repositorio|\\brepo\\b|commit|subir|subo|sub[ií]|crear (la|una) carpeta|crear carpetas|c[oó]mo creo (la|una|las) carpeta|" + ML_UPLOAD + "|" + ML_REPO + ")"),
        """
🐙 GITHUB DESDE EL CELULAR (guardar tu proyecto gratis)

GitHub guarda tu proyecto en internet. Sirve para apps Kotlin, apps web y páginas web. Se usa desde el navegador (github.com).

1️⃣ Crear el repositorio (una sola vez)
   • Entra a github.com e inicia sesión (o crea tu cuenta gratis).
   • Toca «+» (arriba) → «New repository».
   • Nombre: el de tu app, sin espacios (ej. MiApp). Elige «Public».
   • Marca «Add a README file» y toca «Create repository».
   ⚠️ El repositorio ES tu carpeta principal: no crees otra carpeta «MiApp» adentro.

2️⃣ Crear un archivo dentro de su carpeta (el truco de la «/»)
   • Toca «Add file» → «Create new file».
   • En el nombre escribe la ruta completa, por ejemplo:
       app/src/main/res/layout/activity_main.xml
     Cada vez que escribes «/», GitHub crea esa carpeta. Así se crean todas las carpetas, sin pasos extra.
   • Pega el código completo abajo.
   • Toca «Commit changes…» → «Commit changes».
   • Repite con cada archivo (la «Ruta completa» que te doy, SIN el nombre de la carpeta principal).

3️⃣ Reemplazar un archivo que ya existe
   • Abre el archivo → toca el lápiz ✏️ («Edit»).
   • Mantén presionado → «Seleccionar todo» → pega el código nuevo encima.
   • «Commit changes».

4️⃣ Borrar un archivo
   • Abre el archivo → «⋯» → «Delete file» → «Commit changes».

🔑 Errores típicos:
• Un espacio al final de una carpeta («assets /») → GitHub la toma como otra carpeta. Revisa la ruta antes de guardar.
• Mayúsculas distintas («Layout» en vez de «layout»).
• Pegar solo un pedazo del código: copia SIEMPRE el archivo completo.
""".trimIndent()
    )

    private val ACTIONS = Topic(
        "actions", "Sacar el APK con GitHub Actions",
        Regex("(?i)(\\bapk\\b|actions|compil\\w*|artifact|workflow|build\\.yml|compilar\\.yml|instalable|" + ML_COMPILE + ")"),
        """
⚙️ SACAR EL APK GRATIS CON GITHUB ACTIONS (sin computador)

Tu proyecto Android trae el archivo «.github/workflows/compilar.yml». Con él, GitHub arma el APK solo.

1️⃣ Sube TODOS los archivos del proyecto a GitHub, cada uno con su ruta (incluido .github/workflows/compilar.yml).
2️⃣ Cada vez que guardas un cambio («Commit»), GitHub empieza a compilar solo.
3️⃣ Toca la pestaña «Actions» de tu repositorio y abre la compilación de arriba (la más nueva).
   • 🟡 Amarillo = compilando (espera unos minutos).
   • ✅ Verde = ¡listo!
   • ❌ Rojo = hubo un error.
4️⃣ Si salió ✅: baja hasta «Artifacts» y toca el nombre (ej. «mi-app-apk»). Se descarga un .zip.
5️⃣ Abre el .zip con tu explorador de archivos, saca el .apk y tócalo para instalarlo.

❌ Si salió rojo:
   • Toca la compilación → toca el paso con ❌ → busca las líneas que dicen «error» o «FAILED».
   • Cópialas y pégamelas aquí: te digo qué archivo corregir y cómo.
   • Si el error es de red («Could not download», «timeout»), solo toca «Re-run jobs».

💡 Para compilarla de nuevo sin cambiar nada: «Actions» → «Compilar APK» → «Run workflow».
""".trimIndent()
    )

    private val PAGES = Topic(
        "pages", "Publicar tu página gratis con GitHub Pages",
        Regex("(?i)(github pages|\\bpages\\b|public\\w*|p[oó]nerla en internet|subirla a internet|en l[ií]nea|link|enlace|url|dominio|hosting|hostear|" + ML_PUBLISH + "|" + ML_LINK + ")"),
        """
🌍 PUBLICAR TU PÁGINA GRATIS CON GITHUB PAGES

Sirve para páginas y apps web (index.html, style.css, script.js…). Te da un enlace para compartir.

1️⃣ Sube los archivos a un repositorio de GitHub, SUELTOS (index.html debe quedar en la raíz, no dentro de otra carpeta).
2️⃣ En el repositorio toca «Settings» (⚙️) → en el menú «Pages».
3️⃣ En «Build and deployment» → «Source» elige «Deploy from a branch».
4️⃣ En «Branch» elige «main» y la carpeta «/ (root)» → «Save».
5️⃣ Espera 1 o 2 minutos y recarga: arriba aparece tu enlace, así:
       https://TU-USUARIO.github.io/NOMBRE-DEL-REPOSITORIO/

🔑 Para que funcione:
• El repositorio debe ser «Public» (en el plan gratis).
• El archivo principal se llama exactamente «index.html».
• Cada cambio que guardes se publica solo en un par de minutos.
""".trimIndent()
    )

    // ===================================================================================
    // 🌐 OTRAS PÁGINAS: REPLIT, NETLIFY, CODEPEN, GLITCH
    // ===================================================================================
    private val REPLIT = Topic(
        "replit", "Usar tu proyecto en Replit",
        Regex("(?i)(repl\\.?it|repli\\b|\\brepl\\b)"),
        """
🟠 REPLIT (programar y probar en el navegador)

Replit sirve para páginas web, apps web, Python, Node y más. NO arma apps Android con pantallas (para el APK usa GitHub Actions).

✅ Forma más fácil: traer tu proyecto desde GitHub
1️⃣ Primero sube tu proyecto a GitHub (pregúntame «¿cómo lo subo a GitHub?»).
2️⃣ Entra a replit.com/import → elige «GitHub» → conecta tu cuenta.
3️⃣ Elige tu repositorio → «Import».
   (Si el repositorio es público, también puedes abrir: replit.com/github.com/TU-USUARIO/TU-REPOSITORIO)
4️⃣ Toca «Run» ▶ para verla funcionando.

✍️ Forma a mano (página web)
1️⃣ Crea un proyecto nuevo vacío o de HTML, CSS y JS.
2️⃣ En el panel de archivos («Files») abre index.html, style.css y script.js y pega cada código en el suyo.
3️⃣ Para los que faltan (logic.js, botones.js) usa el botón de «nuevo archivo» y ponles ese nombre exacto.
4️⃣ Toca «Run» ▶.

🔑 Ojo:
• Si Replit te ofrece crearla con su IA, no hace falta: tú ya tienes el código.
• Publicarla con enlace propio desde Replit puede pedir un plan de pago. Gratis: GitHub Pages o Netlify.
""".trimIndent()
    )

    private val NETLIFY = Topic(
        "netlify", "Publicar tu página en Netlify",
        Regex("(?i)(netlify|netlifi)"),
        """
🟢 NETLIFY (publicar tu página o app web gratis, con enlace)

✅ Desde el celular (conectado a GitHub)
1️⃣ Sube tu página a GitHub, con index.html suelto en la raíz.
2️⃣ Entra a app.netlify.com e inicia sesión con tu cuenta de GitHub.
3️⃣ Toca «Add new site» (o «Add new project») → «Import an existing project» → «GitHub».
4️⃣ Elige tu repositorio.
5️⃣ «Build command»: déjalo VACÍO. «Publish directory»: déjalo vacío (o escribe un punto «.»).
6️⃣ Toca «Deploy». En un minuto te da el enlace: algo.netlify.app
   Cada cambio que guardes en GitHub se publica solo.

💻 Desde un computador (sin GitHub)
• Entra a app.netlify.com/drop y arrastra la carpeta de tu página. Listo.

🔑 Ojo:
• Para cambiar el nombre del enlace: «Site configuration» → «Change site name».
• Los nombres de los botones pueden variar un poco, pero los pasos son los mismos.
• Es para páginas y apps web; el APK de Android se saca con GitHub Actions.
""".trimIndent()
    )

    private val CODEPEN = Topic(
        "codepen", "Probar tu página en CodePen",
        Regex("(?i)(code ?pen)"),
        """
🖊️ CODEPEN (probar una página al instante, en 3 cuadros)

CodePen tiene 3 cuadros: HTML, CSS y JS. Lo que pegas se ve funcionando abajo, al tiro.

1️⃣ Entra a codepen.io (crea tu cuenta gratis para poder guardar) → «Create» → «Pen».
2️⃣ Cuadro HTML: pega SOLO lo que está entre <body> y </body> de tu index.html
   (sin las líneas <script src="…"> ni <link …>: CodePen junta los archivos solo).
3️⃣ Cuadro CSS: pega todo style.css.
4️⃣ Cuadro JS: pega los JavaScript uno debajo del otro, EN ESTE ORDEN:
   logic.js → botones.js → script.js  (si alguno no existe en tu proyecto, sáltalo)
5️⃣ Toca «Save» y comparte el enlace de tu Pen.

🔑 Ojo:
• Es ideal para probar y mostrar. Para una página «de verdad» con su propio enlace, usa GitHub Pages o Netlify.
• No sirve para apps Android (Kotlin).
""".trimIndent()
    )

    private val GLITCH = Topic(
        "glitch", "Glitch",
        Regex("(?i)(glitch)"),
        """
🎏 GLITCH

Glitch cerró su servicio de proyectos: desde el 8 de julio de 2025 ya no se pueden crear, editar ni
publicar proyectos ahí (los proyectos dejaron de estar en línea).

Usa una de estas en su lugar (todas gratis):
• Netlify → publicar tu página con enlace (pregúntame «¿cómo la publico en Netlify?»).
• GitHub Pages → publicar desde GitHub (pregúntame «¿cómo uso GitHub Pages?»).
• Replit → programar y probar en el navegador (también Node y Python).
• CodePen → probar una página al instante.
""".trimIndent()
    )

    // ===================================================================================
    // 💻 ANDROID STUDIO, INSTALAR EL APK Y ERRORES
    // ===================================================================================
    private val STUDIO = Topic(
        "studio", "Abrir tu proyecto en Android Studio (PC)",
        Regex("(?i)(android studio|\\bpc\\b|computador|computadora|ordenador|laptop|notebook|emulador|emulator|" + ML_COMPUTER + ")"),
        """
💻 ANDROID STUDIO (si tienes computador)

1️⃣ Descarga Android Studio gratis desde developer.android.com/studio e instálalo.
2️⃣ Copia la carpeta principal de tu proyecto al computador (o descárgala de GitHub: «Code» → «Download ZIP» y descomprímela).
3️⃣ En Android Studio: «File» → «Open» → elige la carpeta principal (la que tiene settings.gradle) → «OK».
4️⃣ Espera a que termine «Gradle Sync» (abajo). Si te ofrece descargar algo (SDK, JDK, Gradle), acepta.
5️⃣ Conecta tu teléfono por USB con la «Depuración USB» activada (Ajustes → Opciones de desarrollador),
   o crea un emulador en «Device Manager».
6️⃣ Toca ▶ «Run». La app se instala y se abre sola.

💡 Si algo sale en rojo abajo («Build»), cópiame el error y te digo cómo arreglarlo.
""".trimIndent()
    )

    private val INSTALL = Topic(
        "install", "Instalar el APK en tu teléfono",
        Regex("(?i)(instal\\w*|or[ií]genes desconocidos|fuentes desconocidas|no se instala|no me deja instalar|play protect|" + ML_INSTALL + ")"),
        """
📲 INSTALAR EL APK EN TU TELÉFONO

1️⃣ Descarga el .zip de «Artifacts» (GitHub Actions) y ábrelo con tu explorador de archivos.
2️⃣ Saca (extrae) el archivo .apk.
3️⃣ Toca el .apk. Si Android avisa «instalar apps desconocidas», toca «Configuración» y permite a esa app
   (Archivos o el navegador) instalar apps. Vuelve atrás y toca «Instalar».
4️⃣ Si aparece Play Protect, toca «Más detalles» → «Instalar de todas formas» (es tu propia app).

🔑 Si dice «La app no se instaló»:
• Ya tienes otra versión con el mismo paquete pero firmada distinto: desinstala la anterior y prueba de nuevo.
• Tu teléfono es más antiguo que el «minSdk» del proyecto (en app/build.gradle).
""".trimIndent()
    )

    private val ERRORS = Topic(
        "errors", "Errores típicos al armar el proyecto",
        Regex("(?i)(no compila|no funciona|no abre|se cierra|se cae|crash|unresolved reference|cannot find|not found|no encuentra|" +
            "androidx|duplicate|failed|error|" + ML_ERROR + ")"),
        """
🩺 ERRORES TÍPICOS AL ARMAR UN PROYECTO (y su solución)

• «Unresolved reference R» o «package R does not exist» → el «package» del archivo .kt no coincide con el «namespace»
  de app/build.gradle, o un .xml de res/ tiene un error. Revisa que el paquete sea el mismo en todos lados.
• «Unresolved reference …» de una clase tuya → falta ese archivo o está en otra carpeta del paquete.
• «AndroidX dependencies… android.useAndroidX» → falta el archivo gradle.properties en la carpeta principal.
• «Duplicate class» o «Redeclaration» → el mismo archivo quedó dos veces (por ejemplo en dos carpetas).
• «Resource not found» o «error: failed linking file» → un nombre en res/ tiene mayúscula, espacio o guion «-».
• «Manifest merger failed» → falta «android:exported="true"» en la pantalla principal del AndroidManifest.xml.
• La app se cierra al abrir → casi siempre falta un permiso en el Manifest o un id del XML no coincide con el del código.
• Página web en blanco → index.html con otro nombre, o un archivo .js/.css no está en la misma carpeta.
• La carpeta salió con espacio («assets /») → renómbrala o vuelve a crear los archivos con la ruta correcta.

💡 Lo mejor: copia el error completo y pégamelo aquí. Te digo exactamente qué archivo cambiar.
""".trimIndent()
    )

    /** Orden de prioridad: primero las páginas por su nombre, luego lo más específico. */
    private val ALL = listOf(GLITCH, CODEPEN, NETLIFY, REPLIT, PAGES, ACTIONS, STUDIO, INSTALL, CPP, GITHUB, ERRORS, ANDROID, WEB)

    /** Lista de páginas que conoce (para el menú de ayuda). */
    const val PLACES = "GitHub · GitHub Actions (APK) · GitHub Pages · Replit · Netlify · CodePen · Glitch · Android Studio"

    /**
     * Elige el tema de la pregunta. [web] = el proyecto actual es una página o app web (para «estructura»).
     * Devuelve null si la pregunta no es de ningún tema de la guía.
     */
    fun find(question: String, web: Boolean? = null): Topic? {
        val p = question.lowercase(Locale.ROOT)
        // «¿Cómo la publico?» sin decir dónde: en una app Android no se publica con Pages (se saca el APK)
        if (web == false && PAGES.match.containsMatchIn(p) && !Regex("github pages|\\bpages\\b|netlify").containsMatchIn(p) &&
            !Regex("(?i)(play store|google play)").containsMatchIn(p)) return ACTIONS
        // «¿Cómo creo la carpeta?» en cualquier idioma: el truco de la «/» de GitHub
        if (Regex("(?i)(cr[eé]a|creo|crear|create|make|criar|crio|cr[eé]er|cr[eé]e|creare|erstell|anleg|buat|tạo|созда|创建|建立|新建|만들|作る|作成|สร้าง|أنشئ|إنشاء)" +
                ".{0,25}(carpeta|folder|pasta|dossier|cartella|ordner|папк|文件夹|資料夾|目录|폴더|フォルダ|thư mục|โฟลเดอร์|مجلد)").containsMatchIn(p) &&
            !GLITCH.match.containsMatchIn(p) && !REPLIT.match.containsMatchIn(p) && !NETLIFY.match.containsMatchIn(p) &&
            !CODEPEN.match.containsMatchIn(p) && !STUDIO.match.containsMatchIn(p)) return GITHUB
        val hit = ALL.firstOrNull { it.match.containsMatchIn(p) } ?: return null
        // La estructura depende del proyecto: web o Android
        if (hit === ANDROID || hit === WEB) {
            if (Regex("index\\.html|style\\.css|script\\.js|logic\\.js|botones\\.js").containsMatchIn(p)) return WEB
            if (Regex("manifest|res/|layout|values|gradle|paquete|package").containsMatchIn(p)) return ANDROID
            return if (web == true) WEB else ANDROID
        }
        return hit
    }

    /** ¿Nombra alguna de las páginas o herramientas de la guía? (así la duda se responde aunque no haya proyecto) */
    fun mentionsPlace(question: String): Boolean =
        Regex("(?i)(github|repl\\.?it|repli\\b|netlify|code ?pen|glitch|android studio|\\bapk\\b|github pages|actions)").containsMatchIn(question)

    /** Texto para la IA (como conocimiento), recortado para no llenar la memoria del modelo. */
    fun knowledge(topic: Topic, maxChars: Int = 1800): String = text(topic).take(maxChars)
}
