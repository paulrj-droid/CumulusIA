package com.paulrj.cumulusia.ai

/**
 * 🎨🎞️ CREAR IMÁGENES Y VIDEO CLIPS EN TODOS LOS IDIOMAS
 *
 * El español lo revisa MainActivity como siempre («créame una imagen de…», «haz un video de…»).
 * Aquí se reconocen los mismos pedidos en los idiomas de Latinoamérica (español y portugués), Estados Unidos (inglés),
 * Europa (francés, alemán, italiano, neerlandés, polaco, ruso), Asia (chino, coreano, japonés, indonesio, vietnamita,
 * tailandés) y Medio Oriente (árabe). Así, en cualquier país, «draw me a cat», «crie uma imagem de…», «erstelle ein Bild…»,
 * «画一只猫», «고양이 그림 그려줘», «วาดรูปแมว» o «ارسم صورة قطة» crean la imagen o el video igual que en Chile.
 */
object CreateMedia {

    /** Borde de palabra que entiende tildes y letras de otros idiomas. */
    private const val B = "(?:(?<=[\\p{L}\\p{N}])(?![\\p{L}\\p{N}])|(?<![\\p{L}\\p{N}])(?=[\\p{L}\\p{N}]))"
    private fun rx(s: String) = Regex("(?iu)" + s.replace("\\b", B))

    /** Hasta 4 palabras entre el verbo y lo que se crea («make me a cute little picture»). */
    private const val GAP = "(?:\\s+\\S+){0,4}?\\s+"

    // ---------------- 🎨 IMAGEN ----------------
    private val IMAGE: List<Regex> = listOf(
        // 🇺🇸🇬🇧 inglés
        rx("\\b(create|make|generate|draw|design|paint|render|produce)(\\s+me)?$GAP(image|picture|pic|photo|drawing|illustration|wallpaper|logo|poster|portrait|painting|artwork)s?\\b"),
        rx("^\\s*(please\\s+)?(draw|paint)(\\s+me)?\\s+(a|an|some|the)\\s+"),
        // 🇧🇷🇵🇹 portugués
        rx("\\b(cri[ae]|crie-me|fa[çc]a|faz|ger[ae]|desenh[ae]|desenhe-me)(-me)?$GAP(imagem|imagens|foto|fotografia|desenho|ilustra[çc][ãa]o|papel de parede|logo|logotipo|p[ôo]ster|retrato)\\b"),
        // 🇫🇷 francés
        rx("\\b(cr[ée]e|cr[ée]er|fais|g[ée]n[èe]re|dessine|dessiner|fabrique)(-moi)?$GAP(image|photo|dessin|illustration|fond d'[ée]cran|logo|affiche|portrait|tableau)s?\\b"),
        rx("^\\s*(s'il te pla[îi]t\\s+)?(dessine|dessinez)(-moi)?\\s+(un|une|des|le|la|les)\\b"),
        // 🇩🇪 alemán
        rx("\\b(erstell(e)?|mach(e)?|generier(e)?|zeichne|mal(e)?|entwirf|gestalte)(\\s+mir)?$GAP(bild|bilder|foto|zeichnung|illustration|hintergrundbild|wallpaper|logo|poster|portr[äa]t|gem[äa]lde)\\b"),
        // 🇮🇹 italiano
        rx("\\b(crea(mi)?|fai|fammi|genera(mi)?|disegna(mi)?|realizza(mi)?)$GAP(immagine|immagini|foto|disegno|illustrazione|sfondo|logo|poster|ritratto|dipinto)\\b"),
        // 🇳🇱 neerlandés · 🇵🇱 polaco
        rx("\\b(maak|genereer|teken)$GAP(afbeelding|plaatje|foto|tekening|illustratie|achtergrond|logo|poster)\\b"),
        rx("\\b(stw[óo]rz|zr[óo]b|wygeneruj|narysuj)$GAP(obraz(ek)?|zdj[ęe]cie|rysunek|ilustracj[ęe]|tapet[ęe]|logo|plakat)\\b"),
        // 🇷🇺 ruso
        rx("(создай|сделай|сгенерируй|нарисуй)(\\s+мне)?$GAP(изображени|картинк|фото|рисун|иллюстрац|обои|логотип|постер|портрет)"),
        rx("^\\s*(пожалуйста\\s+)?нарисуй\\s+"),
        // 🇨🇳 chino
        rx("(生成|画|创建|制作|做|帮我画|给我画|设计).{0,12}(图片|图像|照片|插画|插图|壁纸|头像|海报|图|画像|logo)"),
        rx("(帮我|给我|请)?画(一|个|只|幅|张)"),
        // 🇰🇷 coreano
        rx("(이미지|그림|사진|일러스트|배경화면|로고|포스터).{0,12}(만들어|그려|생성)"),
        rx("그려\\s*(줘|주세요|줄래)"),
        // 🇯🇵 japonés
        rx("(画像|イラスト|絵|写真|壁紙|ロゴ|ポスター).{0,12}(作って|描いて|生成|作成|書いて)"),
        rx("(描いて|かいて)(ください|ほしい)?"),
        // 🇮🇩 indonesio
        rx("\\b(buat(kan)?|bikin(kan|in)?|hasilkan|ciptakan|lukis(kan)?)$GAP(gambar|foto|ilustrasi|wallpaper|logo|poster|lukisan)\\b"),
        rx("^\\s*(tolong\\s+)?gambar(kan|in)\\s+"),
        // 🇻🇳 vietnamita
        rx("\\b(tạo|vẽ|làm|thiết kế)(\\s+(cho\\s+)?(tôi|mình|em))?$GAP(ảnh|hình ảnh|bức ảnh|bức tranh|bức vẽ|tranh vẽ|hình nền|logo|poster|minh họa)\\b"),
        rx("^\\s*(hãy\\s+)?vẽ\\s+(cho\\s+(tôi|mình)\\s+)?(một|con|cái|chiếc|bức)\\b"),
        // 🇹🇭 tailandés
        rx("(สร้าง|วาด|ทำ(?!ไม)|เจน|ออกแบบ).{0,12}(รูป(?!แบบ)|ภาพ(?!ยนตร์)|วอลเปเปอร์|โลโก้|โปสเตอร์)"),
        rx("วาด(รูป|ภาพ)?"),
        // 🇸🇦 árabe
        rx("(أنشئ|انشئ|اصنع|اعمل|ارسم|صمم|ولد|ولّد|سوي|سوّي|كوّن).{0,15}(صورة|صوره|صور|رسمة|رسم|خلفية|شعار|ملصق|بوستر|لوحة)"),
        rx("^\\s*(من فضلك\\s+)?ارسم(\\s+لي)?\\s+")
    )

    // ---------------- 🎞️ VIDEO CLIP ----------------
    private val VIDEO: List<Regex> = listOf(
        rx("\\b(create|make|generate|produce|render)(\\s+me)?$GAP(video|clip|video ?clip|animation|short film)s?\\b"),
        rx("\\b(cri[ae]|crie-me|fa[çc]a|faz|ger[ae]|produza)(-me)?$GAP(v[íi]deo|clipe|videoclipe|anima[çc][ãa]o)\\b"),
        rx("\\b(cr[ée]e|cr[ée]er|fais|g[ée]n[èe]re|r[ée]alise)(-moi)?$GAP(vid[ée]o|clip|animation)s?\\b"),
        rx("\\b(erstell(e)?|mach(e)?|generier(e)?|produzier(e)?)(\\s+mir)?$GAP(video|videos|clip|videoclip|animation)\\b"),
        rx("\\b(crea(mi)?|fai|fammi|genera(mi)?|realizza(mi)?)$GAP(video|clip|videoclip|animazione)\\b"),
        rx("\\b(maak|genereer)$GAP(video|clip|animatie)\\b"),
        rx("\\b(stw[óo]rz|zr[óo]b|wygeneruj)$GAP(film(ik)?|wideo|klip|animacj[ęe])\\b"),
        rx("(создай|сделай|сгенерируй|смонтируй)(\\s+мне)?$GAP(видео|клип|ролик|анимац)"),
        rx("(生成|创建|制作|做|帮我做|给我做).{0,12}(视频|短片|动画|短视频)"),
        rx("(영상|동영상|비디오|클립|애니메이션).{0,12}(만들어|생성)"),
        rx("(動画|ビデオ|クリップ|アニメーション|ムービー).{0,12}(作って|生成|作成)"),
        rx("\\b(buat(kan)?|bikin(kan|in)?|hasilkan)$GAP(video|klip|animasi)\\b"),
        rx("\\b(tạo|làm|dựng)(\\s+(cho\\s+)?(tôi|mình|em))?$GAP(video|clip|hoạt hình)\\b"),
        rx("(สร้าง|ทำ(?!ไม)|เจน|ตัดต่อ).{0,12}(วิดีโอ|คลิป|แอนิเมชัน)"),
        rx("(أنشئ|انشئ|اصنع|اعمل|سوي|سوّي|ولد|ولّد).{0,15}(فيديو|مقطع|كليب|رسوم متحركة)")
    )

    /** Programación, páginas, apps… («make an animation in CSS», «crie um app»): nunca es imagen ni video. */
    private val CODE = rx("\\b(html|css|javascript|js|typescript|kotlin|java|python|svg|canvas|lottie|react|flutter|android|xml|code|c[óo]digo|codice|programm\\p{L}*|" +
        "apps?|application|aplicativo|website|webseite|site web|sito web|web ?page|script)\\b|网页|代码|程序|앱|코드|アプリ|コード|كود|برمج|تطبيق|โค้ด|แอป|\\bkode\\b|mã nguồn")

    /** Pedidos de describir, explicar o buscar una imagen (no de crearla). */
    private val NOT_CREATE = rx("\\b(describe|explain|what is in|search|find|look for|beschreib|décris|descreva|descreva|descrivi|cari|tìm|ค้นหา|ابحث)\\b")

    /** Marcas del italiano y del portugués (dicen «una», «de», «con» como el español). */
    private val ITALIAN = rx("\\b(immagine|immagini|disegn\\p{L}*|illustrazione|sfondo|ritratto|dipinto|creami|fammi|generami|animazione)\\b")
    private val PORTUGUESE = rx("\\b(imagem|imagens|desenh[ae]|desenhe-me|ilustra[çc][ãa]o|crie|crie-me|fa[çc]a|papel de parede|anima[çc][ãa]o|clipe|uma)\\b")

    /** ¿Está escrito en español? (entonces lo revisa MainActivity como siempre) */
    fun isSpanish(prompt: String): Boolean =
        runCatching { WebSearchRules.looksSpanish(prompt) }.getOrDefault(true) &&
            !ITALIAN.containsMatchIn(prompt) && !PORTUGUESE.containsMatchIn(prompt)

    /** Idioma del pedido (para la narración del video y los textos de la respuesta). */
    private val ENGLISH = rx("\\b(draw|paint|create|make|generate|design|render|picture|image|photo|drawing|wallpaper|video|clip|animation)\\b")

    fun language(prompt: String, country: String): String = when {
        ITALIAN.containsMatchIn(prompt) -> "it"
        PORTUGUESE.containsMatchIn(prompt) -> "pt"
        // «draw me a cat»: inglés aunque no tenga «the», «to», «of»…
        !Regex("[^\\p{IsLatin}\\p{P}\\p{N}\\s]").containsMatchIn(prompt) && ENGLISH.containsMatchIn(prompt) &&
            runCatching { WebSearchRules.replyLanguage(prompt, country) }.getOrDefault("es") == "es" -> "en"
        else -> runCatching { WebSearchRules.replyLanguage(prompt, country) }.getOrDefault("es")
    }

    fun wantsImage(prompt: String): Boolean {
        val p = prompt.trim()
        if (p.length < 4 || CODE.containsMatchIn(p) || NOT_CREATE.containsMatchIn(p)) return false
        if (wantsVideo(p)) return false
        return IMAGE.any { it.containsMatchIn(p) }
    }

    fun wantsVideo(prompt: String): Boolean {
        val p = prompt.trim()
        if (p.length < 4 || CODE.containsMatchIn(p)) return false
        return VIDEO.any { it.containsMatchIn(p) }
    }

    /** Lo que se pide crear, sin las palabras del pedido («draw me a cute cat» → «cute cat»). */
    private val STRIP = rx(
        "\\b(please|can you|could you|i want|i'd like|create|make|generate|draw|design|paint|render|produce|me|for me|an?|some|the|of|with|showing|about|" +
            "image|picture|pic|photo|drawing|illustration|wallpaper|logo|poster|portrait|painting|artwork|video|clip|animation|short film|images|pictures|videos|" +
            "por favor|cri[ae]|crie-me|fa[çc]a|faz|ger[ae]|desenh[ae]|produza|uma?|imagem|imagens|foto|fotografia|desenho|ilustra[çc][ãa]o|papel de parede|v[íi]deo|clipe|anima[çc][ãa]o|" +
            "s'il te pla[îi]t|cr[ée]e|cr[ée]er|fais|g[ée]n[èe]re|dessine|moi|une?|des|de|du|d'|l'|dessin|fond d'[ée]cran|affiche|vid[ée]o|" +
            "bitte|erstell(e)?|mach(e)?|generier(e)?|zeichne|mal(e)?|mir|ein(e|en|em)?|von|mit|bild|bilder|zeichnung|hintergrundbild|" +
            "per favore|crea(mi)?|fai|fammi|genera(mi)?|disegna(mi)?|di|immagine|disegno|illustrazione|sfondo|animazione|" +
            "tolong|buat(kan)?|bikin(kan|in)?|hasilkan|ciptakan|gambar(kan|in)?|sebuah|tentang|ilustrasi|klip|animasi|" +
            "hãy|tạo|vẽ|làm|cho|tôi|mình|một|ảnh|hình ảnh|hình|bức ảnh|bức tranh|tranh|hình nền|về|hoạt hình|" +
            "пожалуйста|создай|сделай|сгенерируй|нарисуй|мне|изображение|картинку|картинка|фото|рисунок|видео|клип|ролик|про|о|об|" +
            "una|uno|un|il|lo|la|del|della|nel|nello|sul|über|einer|einem|eines|" +
            "alsjeblieft|maak|genereer|teken|een|van|afbeelding|plaatje|tekening|nu|" +
            "stw[óo]rz|zr[óo]b|wygeneruj|narysuj|obrazek|obraz|zdj[ęe]cie|rysunek|film|wideo|klip|" +
            "من فضلك|أنشئ|انشئ|اصنع|اعمل|ارسم|صمم|ولد|سوي|لي|صورة|صوره|رسمة|فيديو|مقطع|عن)\\b|" +
            "帮我|给我|请|生成|创建|制作|做|画一(只|个|幅|张)?|画|图片|图像|照片|插画|壁纸|视频|短片|一(张|个|幅|段|只)|关于|" +
            "이미지|그림|사진|만들어\\s*(줘|주세요)?|그려\\s*(줘|주세요)?|생성해\\s*(줘|주세요)?|영상|동영상|" +
            "画像|イラスト|写真|動画|を|作って(ください)?|描いて(ください)?|生成して(ください)?|" +
            "ช่วย|สร้าง|วาด|รูปภาพ|รูป|ภาพ|วิดีโอ|คลิป|ให้หน่อย|หน่อย|ครับ|ค่ะ"
    )

    fun subject(prompt: String): String {
        val first = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("[\"“”«»]"), "")
        val s = STRIP.replace(first, " ").replace(Regex("[\\s,.!?¿¡。！？،؟]+"), " ").trim().trim('-', '–', ':', ' ')
        return s.ifBlank { first.trim() }.take(200)
    }

    // ---------------- 🔞 SEGURIDAD EN TODOS LOS IDIOMAS ----------------
    /** Desnudos o contenido sexual en otros idiomas (el español e inglés los revisa MainActivity). */
    val UNSAFE = rx("\\b(nue|nues|nackt\\p{L}*|nud[oaie]|nudit\\p{L}*|nudes?|telanjang|bugil|khỏa thân|lõa thể|sexo|sexe|sesso|seks|tình dục|porn\\p{L}*|" +
        "erot\\p{L}*|[ée]rotique|sensuel|sinnlich|lingerie|dessous|biancheria intima|naakt|nago|nag[ai]e?)\\b|" +
        "голая|голый|обнажен|эроти|порно|секс|裸|色情|性感|裸体|누드|야한|성인|섹시|ヌード|裸の|エロ|セクシー|เปลือย|โป๊|ลามก|عاري|عارية|إباحي|اباحي|جنسي")

    // ---------------- 🌍 TEXTOS EN EL IDIOMA DEL USUARIO ----------------
    /** Nombre del idioma (en español) para pedirle a la IA la narración del video en ese idioma. */
    fun languageName(lang: String): String = when (lang) {
        "en" -> "inglés"; "pt" -> "portugués"; "fr" -> "francés"; "de" -> "alemán"; "it" -> "italiano"; "nl" -> "neerlandés"
        "pl" -> "polaco"; "ru" -> "ruso"; "zh" -> "chino"; "ko" -> "coreano"; "ja" -> "japonés"; "id" -> "indonesio"
        "vi" -> "vietnamita"; "th" -> "tailandés"; "ar" -> "árabe"; else -> "español"
    }

    /** (imagen lista «{0}» {1} = tamaño · video listo {0} escenas «{1}» · no se pudo · necesito internet imagen · video) */
    private val TEXTS: Map<String, List<String>> = mapOf(
        "en" to listOf("🎨 Here is your image of «{0}» ({1}).", "🎬 Your video is ready! {0} animated scenes of «{1}».",
            "⚠️ I couldn't create it right now (the free service may be busy). Please try again in a moment.",
            "🌐 I need internet to create images. Turn it on in ➕ → Internet and ask me again.",
            "🌐 I need internet to create a video. Turn it on in ➕ → Internet and ask me again."),
        "pt" to listOf("🎨 Aqui está a sua imagem de «{0}» ({1}).", "🎬 Seu vídeo está pronto! {0} cenas animadas de «{1}».",
            "⚠️ Não consegui criar agora (o serviço gratuito pode estar ocupado). Tente de novo em instantes.",
            "🌐 Para criar imagens preciso de internet. Ative em ➕ → Internet e peça de novo.",
            "🌐 Para criar um vídeo preciso de internet. Ative em ➕ → Internet e peça de novo."),
        "fr" to listOf("🎨 Voici ton image de «{0}» ({1}).", "🎬 Ta vidéo est prête ! {0} scènes animées de «{1}».",
            "⚠️ Je n'ai pas pu la créer pour l'instant (le service gratuit est peut-être saturé). Réessaie dans un moment.",
            "🌐 Pour créer des images, j'ai besoin d'internet. Active-le dans ➕ → Internet et redemande-moi.",
            "🌐 Pour créer une vidéo, j'ai besoin d'internet. Active-le dans ➕ → Internet et redemande-moi."),
        "de" to listOf("🎨 Hier ist dein Bild von «{0}» ({1}).", "🎬 Dein Video ist fertig! {0} animierte Szenen von «{1}».",
            "⚠️ Ich konnte es gerade nicht erstellen (der kostenlose Dienst ist vielleicht ausgelastet). Versuch es gleich noch einmal.",
            "🌐 Um Bilder zu erstellen, brauche ich Internet. Aktiviere es unter ➕ → Internet und frag mich noch einmal.",
            "🌐 Um ein Video zu erstellen, brauche ich Internet. Aktiviere es unter ➕ → Internet und frag mich noch einmal."),
        "it" to listOf("🎨 Ecco la tua immagine di «{0}» ({1}).", "🎬 Il tuo video è pronto! {0} scene animate di «{1}».",
            "⚠️ Non sono riuscito a crearla ora (il servizio gratuito potrebbe essere occupato). Riprova tra poco.",
            "🌐 Per creare immagini mi serve internet. Attivalo in ➕ → Internet e chiedimelo di nuovo.",
            "🌐 Per creare un video mi serve internet. Attivalo in ➕ → Internet e chiedimelo di nuovo."),
        "nl" to listOf("🎨 Hier is je afbeelding van «{0}» ({1}).", "🎬 Je video is klaar! {0} geanimeerde scènes van «{1}».",
            "⚠️ Het lukte nu niet (de gratis dienst is misschien druk). Probeer het zo opnieuw.",
            "🌐 Voor afbeeldingen heb ik internet nodig. Zet het aan via ➕ → Internet en vraag het opnieuw.",
            "🌐 Voor een video heb ik internet nodig. Zet het aan via ➕ → Internet en vraag het opnieuw."),
        "pl" to listOf("🎨 Oto twój obraz «{0}» ({1}).", "🎬 Twój film jest gotowy! {0} animowane sceny «{1}».",
            "⚠️ Nie udało się teraz (darmowa usługa może być zajęta). Spróbuj ponownie za chwilę.",
            "🌐 Do tworzenia obrazów potrzebuję internetu. Włącz go w ➕ → Internet i poproś ponownie.",
            "🌐 Do tworzenia filmu potrzebuję internetu. Włącz go w ➕ → Internet i poproś ponownie."),
        "ru" to listOf("🎨 Вот твоё изображение «{0}» ({1}).", "🎬 Твоё видео готово! {0} анимированные сцены «{1}».",
            "⚠️ Сейчас не получилось (бесплатный сервис может быть занят). Попробуй ещё раз чуть позже.",
            "🌐 Чтобы создавать изображения, нужен интернет. Включи его в ➕ → Интернет и попроси снова.",
            "🌐 Чтобы создать видео, нужен интернет. Включи его в ➕ → Интернет и попроси снова."),
        "zh" to listOf("🎨 这是你要的「{0}」图片（{1}）。", "🎬 你的视频做好了！{0} 个「{1}」动画场景。",
            "⚠️ 现在无法生成（免费服务可能繁忙），请稍后再试。",
            "🌐 生成图片需要联网。请在 ➕ → 网络 中打开后再试一次。", "🌐 生成视频需要联网。请在 ➕ → 网络 中打开后再试一次。"),
        "ko" to listOf("🎨 「{0}」 이미지예요 ({1}).", "🎬 영상이 완성됐어요! 「{1}」 애니메이션 장면 {0}개.",
            "⚠️ 지금은 만들 수 없었어요 (무료 서비스가 붐빌 수 있어요). 잠시 후 다시 시도해 주세요.",
            "🌐 이미지를 만들려면 인터넷이 필요해요. ➕ → 인터넷에서 켜고 다시 요청해 주세요.",
            "🌐 영상을 만들려면 인터넷이 필요해요. ➕ → 인터넷에서 켜고 다시 요청해 주세요."),
        "ja" to listOf("🎨 「{0}」の画像です（{1}）。", "🎬 動画ができました！「{1}」のアニメーションシーン {0} 個。",
            "⚠️ 今は作成できませんでした（無料サービスが混雑している可能性があります）。少ししてからもう一度お試しください。",
            "🌐 画像を作るにはインターネットが必要です。➕ → インターネットでオンにして、もう一度お願いします。",
            "🌐 動画を作るにはインターネットが必要です。➕ → インターネットでオンにして、もう一度お願いします。"),
        "id" to listOf("🎨 Ini gambarmu tentang «{0}» ({1}).", "🎬 Videomu sudah jadi! {0} adegan animasi tentang «{1}».",
            "⚠️ Aku belum bisa membuatnya sekarang (layanan gratisnya mungkin sedang sibuk). Coba lagi sebentar lagi.",
            "🌐 Untuk membuat gambar aku butuh internet. Aktifkan di ➕ → Internet lalu minta lagi.",
            "🌐 Untuk membuat video aku butuh internet. Aktifkan di ➕ → Internet lalu minta lagi."),
        "vi" to listOf("🎨 Đây là ảnh «{0}» của bạn ({1}).", "🎬 Video của bạn đã xong! {0} cảnh động về «{1}».",
            "⚠️ Hiện tôi chưa tạo được (dịch vụ miễn phí có thể đang bận). Hãy thử lại sau ít phút.",
            "🌐 Để tạo ảnh tôi cần internet. Hãy bật trong ➕ → Internet rồi yêu cầu lại.",
            "🌐 Để tạo video tôi cần internet. Hãy bật trong ➕ → Internet rồi yêu cầu lại."),
        "th" to listOf("🎨 นี่คือภาพ «{0}» ของคุณ ({1})", "🎬 วิดีโอของคุณพร้อมแล้ว! ฉากเคลื่อนไหว {0} ฉากเรื่อง «{1}»",
            "⚠️ ตอนนี้ยังสร้างไม่ได้ (บริการฟรีอาจไม่ว่าง) ลองใหม่อีกครั้งในอีกสักครู่",
            "🌐 การสร้างภาพต้องใช้อินเทอร์เน็ต เปิดได้ที่ ➕ → อินเทอร์เน็ต แล้วขอใหม่อีกครั้ง",
            "🌐 การสร้างวิดีโอต้องใช้อินเทอร์เน็ต เปิดได้ที่ ➕ → อินเทอร์เน็ต แล้วขอใหม่อีกครั้ง"),
        "ar" to listOf("🎨 إليك صورتك عن «{0}» ({1}).", "🎬 الفيديو جاهز! {0} مشاهد متحركة عن «{1}».",
            "⚠️ لم أتمكن من إنشائها الآن (قد تكون الخدمة المجانية مشغولة). حاول مرة أخرى بعد قليل.",
            "🌐 لإنشاء الصور أحتاج إلى الإنترنت. فعّله من ➕ → الإنترنت واطلب مني مرة أخرى.",
            "🌐 لإنشاء فيديو أحتاج إلى الإنترنت. فعّله من ➕ → الإنترنت واطلب مني مرة أخرى.")
    )

    /** Texto [i] en el idioma [lang] con los datos puestos; null en español (se usan los textos de siempre). */
    fun text(lang: String, i: Int, vararg args: Any): String? = TEXTS[lang]?.getOrNull(i)?.let { t ->
        var out = t
        args.forEachIndexed { k, a -> out = out.replace("{$k}", a.toString()) }
        out
    }
}
