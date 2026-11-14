package com.paulrj.cumulusia.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 🗣️ Lo que la voz de la IA está haciendo, para que el avatar mueva la boca:
 *  - speaking: la IA está hablando (la boca se mueve).
 *  - listening: te está escuchando (los anillos cambian de color).
 *  - push(level): cuánto suena la voz en este momento (0..1). La voz descargada (.onnx) lo manda con el volumen real;
 *    la voz de Android avisa al empezar cada palabra (pulse). Si no llega nada, la boca sigue un ritmo de sílabas.
 */
object AvatarVoice {
    @Volatile var speaking = false
    @Volatile var listening = false
    @Volatile var level = 0f
    @Volatile var levelAt = 0L
    /** De dónde sale el movimiento de la boca: 0 = ritmo de sílabas, 1 = volumen real (voz descargada), 2 = aviso por palabra (voz de Android). */
    @Volatile var source = 0
    /** La pantalla de chat dice si la IA está hablando / escuchando (así no hay que tocar cada lugar donde cambia). */
    @Volatile var speakingSource: (() -> Boolean)? = null
    @Volatile var listeningSource: (() -> Boolean)? = null

    fun isSpeaking(): Boolean = speaking || (runCatching { speakingSource?.invoke() }.getOrNull() ?: false)
    fun isListening(): Boolean = listening || (runCatching { listeningSource?.invoke() }.getOrNull() ?: false)

    // (la voz descargada escribe el audio un poquito antes de que suene: cada nivel se guarda con la hora en que va a sonar)
    private val lvAt = LongArray(64); private val lv = FloatArray(64); private var lvN = 0

    fun push(l: Float) = pushAt(l, SystemClock.uptimeMillis())
    fun pulse() = push(0.85f)

    @Synchronized fun pushAt(l: Float, at: Long) {
        val i = lvN % 64; lvAt[i] = at; lv[i] = l.coerceIn(0f, 1f); lvN++
    }

    /** Pasa al nivel «actual» el último que ya debería estar sonando. */
    @Synchronized fun tick(now: Long) {
        // (promedio de lo que sonó en los últimos ~120 ms: así la boca no tirita con cada pedacito de audio)
        var bestAt = -1L; var sum = 0f; var n = 0
        val from = maxOf(0, lvN - 64)
        for (k in from until lvN) {
            val i = k % 64
            if (lvAt[i] <= now && lvAt[i] > now - 120) { sum += lv[i]; n++ }
            if (lvAt[i] <= now && lvAt[i] > bestAt) bestAt = lvAt[i]
        }
        if (bestAt > 0) { level = if (n > 0) sum / n else 0f; levelAt = bestAt }
    }

    @Synchronized fun clear() { lvN = 0; level = 0f; levelAt = 0L }
}

/**
 * 🎭 Gestos del avatar (24): seis familias de emoción, cada una en 4 intensidades (de suave a fuerte).
 * Cada gesto mueve: cejas (lado de la nariz / lado de afuera), párpados (abiertos o entrecerrados), párpado de abajo,
 * comisuras de la boca (sonrisa / tristeza), ancho y apertura de la boca, dientes, arrugas de la frente y de la nariz.
 */
object AvatarMood {
    /** bIn, bOut (cejas: + sube), lid (+ cierra / − abre más), low (párpado de abajo sube), smile (+ arriba / − abajo),
     *  mw (ancho de boca), open (boca abierta), teeth (1 = enseña los dientes), fore (frente), nose (arruga la nariz) */
    class Expr(val bIn: Float, val bOut: Float, val lid: Float, val low: Float, val smile: Float, val mw: Float,
               val open: Float, val teeth: Float, val fore: Float, val nose: Float)

    const val NEUTRAL = -1
    // Familia 0: severidad → enojo
    const val STERNNESS = 0; const val INDIGNATION = 1; const val ANGER = 2; const val RAGE = 3
    // Familia 1: desdén → asco
    const val DISDAIN = 4; const val AVERSION = 5; const val DISGUST = 6; const val REVULSION = 7
    // Familia 2: preocupación → terror
    const val CONCERN = 8; const val ANXIETY = 9; const val FEAR = 10; const val TERROR = 11
    // Familia 3: satisfacción → risa
    const val SATISFACTION = 12; const val AMUSEMENT = 13; const val JOY = 14; const val LAUGHTER = 15
    // Familia 4: desánimo → dolor
    const val DEJECTION = 16; const val MELANCHOLY = 17; const val SADNESS = 18; const val GRIEF = 19
    // Familia 5: atención → asombro
    const val ALERTNESS = 20; const val WONDER = 21; const val SURPRISE = 22; const val SHOCK = 23

    val NEUTRAL_EXPR = Expr(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
    val EXPRS = arrayOf(
        Expr(-0.012f, 0.000f, 0.15f, 0.10f, -0.20f, 0.95f, 0.00f, 0f, 0.0f, 0.0f),   // severidad
        Expr(-0.018f, 0.004f, -0.10f, 0.10f, -0.30f, 1.05f, 0.22f, 1f, 0.0f, 0.2f),  // indignación
        Expr(-0.024f, 0.006f, -0.15f, 0.15f, -0.40f, 1.10f, 0.35f, 1f, 0.0f, 0.4f),  // enojo
        Expr(-0.030f, 0.008f, -0.10f, 0.30f, -0.55f, 1.22f, 0.85f, 1f, 0.0f, 0.7f),  // furia
        Expr(0.000f, 0.004f, 0.30f, 0.00f, -0.25f, 0.90f, 0.00f, 0f, 0.0f, 0.0f),    // desdén
        Expr(-0.008f, 0.000f, 0.20f, 0.15f, -0.35f, 0.95f, 0.06f, 0f, 0.0f, 0.3f),   // rechazo
        Expr(-0.020f, -0.004f, 0.35f, 0.35f, -0.50f, 1.00f, 0.22f, 1f, 0.0f, 0.8f),  // asco
        Expr(-0.026f, -0.006f, 0.50f, 0.45f, -0.60f, 1.12f, 0.55f, 1f, 0.0f, 1.0f),  // repulsión
        Expr(0.010f, -0.004f, 0.00f, 0.00f, -0.20f, 0.95f, 0.00f, 0f, 0.3f, 0.0f),   // preocupación
        Expr(0.018f, -0.004f, -0.10f, 0.00f, -0.30f, 1.00f, 0.30f, 1f, 0.6f, 0.0f),  // ansiedad
        Expr(0.026f, 0.000f, -0.30f, 0.00f, -0.30f, 1.10f, 0.55f, 0f, 0.9f, 0.0f),   // miedo
        Expr(0.030f, 0.006f, -0.45f, 0.00f, -0.40f, 1.20f, 0.85f, 1f, 1.0f, 0.0f),   // terror
        Expr(0.000f, 0.000f, 0.10f, 0.10f, 0.35f, 1.05f, 0.00f, 0f, 0.0f, 0.0f),     // satisfacción
        Expr(0.002f, 0.002f, 0.12f, 0.15f, 0.50f, 1.10f, 0.08f, 1f, 0.0f, 0.0f),     // diversión
        Expr(0.004f, 0.006f, 0.10f, 0.22f, 0.75f, 1.20f, 0.30f, 1f, 0.0f, 0.0f),     // alegría
        Expr(0.006f, 0.004f, 0.40f, 0.35f, 0.90f, 1.25f, 0.70f, 1f, 0.0f, 0.1f),     // risa
        Expr(0.006f, -0.006f, 0.30f, 0.00f, -0.30f, 0.95f, 0.00f, 0f, 0.1f, 0.0f),   // desánimo
        Expr(0.014f, -0.008f, 0.25f, 0.00f, -0.35f, 1.00f, 0.00f, 0f, 0.4f, 0.0f),   // melancolía
        Expr(0.020f, -0.010f, 0.50f, 0.20f, -0.50f, 1.05f, 0.35f, 1f, 0.7f, 0.0f),   // tristeza
        Expr(0.026f, -0.012f, 0.60f, 0.30f, -0.60f, 1.15f, 0.70f, 1f, 1.0f, 0.2f),   // dolor
        Expr(0.006f, 0.006f, -0.15f, 0.00f, 0.00f, 0.95f, 0.00f, 0f, 0.2f, 0.0f),    // atención
        Expr(0.014f, 0.012f, -0.25f, 0.00f, 0.00f, 0.80f, 0.15f, 0f, 0.4f, 0.0f),    // asombro suave
        Expr(0.024f, 0.020f, -0.40f, 0.00f, 0.00f, 0.70f, 0.45f, 0f, 0.8f, 0.0f),    // sorpresa
        Expr(0.030f, 0.024f, -0.50f, 0.00f, -0.05f, 0.68f, 0.70f, 0f, 1.0f, 0.0f),   // impacto
    )

    @Volatile var current = NEUTRAL
    @Volatile var until = 0L

    /** Pone un gesto (por ejemplo desde otra parte de la app) durante unos milisegundos. */
    fun set(expr: Int, durationMs: Long) { current = expr; until = SystemClock.uptimeMillis() + durationMs }

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    /** 😄 La risa como la lee la voz: «jajaja» llega como «ja ja ja» (y los emojis ya no están). */
    private val SPACED_LAUGH = Regex("(?<![\\p{L}])j[aeiou](?: j[aeiou])+(?![\\p{L}])")

    /** 😄 ¿Lo que va a decir es un chiste (o adivinanza / trabalenguas)? */
    private fun isJoke(t: String): Boolean = has(t.lowercase(), "chiste", "adivinanza", "trabalenguas", "joke", "riddle", "tongue twister")

    /** 🎭 Lee lo que la IA va a decir y elige el gesto (español, inglés y emojis). */
    fun fromText(text: String): Int {
        val t = text.lowercase()
        val strong = text.count { it == '!' } >= 2
        fun up(e: Int) = if (strong && e % 4 < 3) e + 1 else e
        return when {
            has(t, "jaja", "jeje", "jiji", "haha", "lol", "😂", "🤣") || SPACED_LAUGH.containsMatchIn(t) -> LAUGHTER
            has(t, "😱", "pánico", "panico", "aterra", "terrifying") -> TERROR
            has(t, "falleci", "murió", "murio", "luto", "😭", "passed away") -> GRIEF
            has(t, "asqueroso", "repugnante", "🤮", "disgusting") -> REVULSION
            has(t, "furia", "furioso", "odio", "😡", "furious") -> RAGE
            has(t, "asco", "🤢", "gross") -> up(DISGUST)
            has(t, "enojad", "enfadad", "molest", "😠", "angry") -> up(ANGER)
            has(t, "injusto", "inaceptable", "indign", "unfair", "unacceptable") -> up(INDIGNATION)
            has(t, "no debes", "prohibido", "no está permitido", "no esta permitido", "not allowed") -> STERNNESS
            has(t, "peligro", "miedo", "😨", "danger", "scary") -> up(FEAR)
            has(t, "urgente", "emergencia", "😰", "urgent", "emergency") -> up(ANXIETY)
            has(t, "preocup", "riesgo", "cuidado con", "😟", "worried", "risk") -> up(CONCERN)
            has(t, "triste", "😢", "😞", "sad ") -> up(SADNESS)
            has(t, "lo siento", "lo lamento", "lamentablemente", "perdón", "perdon", "disculpa", "sorry", "unfortunately") -> MELANCHOLY
            has(t, "no pude", "no puedo", "no es posible", "no fue posible", "no encontré", "no encontre", "😔", "i can't", "couldn't") -> DEJECTION
            has(t, "nostalgia", "extraño", "echo de menos", "miss you") -> MELANCHOLY
            has(t, "desagradable", "unpleasant") -> AVERSION
            has(t, "aburrido", "meh", "🙄", "boring") -> DISDAIN
            has(t, "😲", "😮", "wow", "increíble", "increible", "impresionante", "asombroso", "¿en serio", "no lo puedo creer", "sorprend", "asombr",
                "amazing", "incredible") -> if (strong) SHOCK else SURPRISE
            has(t, "buena pregunta", "qué interesante", "que interesante", "good question") -> WONDER
            has(t, "felicidades", "felicitaciones", "genial", "excelente", "fantástico", "fantastico", "maravill", "me alegra",
                "qué bien", "que bien", "🎉", "😄", "😃", "🥳", "congratulations", "great", "awesome", "wonderful") -> up(JOY)
            has(t, "😊", "😉", "😁", "divertido", "gracioso", "funny", "fun ") -> up(AMUSEMENT)
            has(t, "interesante", "fascinante", "curioso", "imagina", "interesting", "fascinating", "🤔") -> WONDER
            has(t, "atención", "atencion", "importante", "ojo:", "⚠", "warning", "important") -> ALERTNESS
            has(t, "listo", "perfecto", "con gusto", "de nada", "claro", "por supuesto", "✅", "👍", "🙂", "done", "sure", "of course", "you're welcome") -> SATISFACTION
            else -> NEUTRAL
        }
    }

    // ---------------------------------------------------------------- 🗣️ lo que TÚ le dices
    /** Mientras dure, el gesto que puso tu mensaje manda (la respuesta de la IA no lo cambia). */
    @Volatile private var userHoldUntil = 0L

    /**
     * 🎭 Reacciona a lo que le dices: insulto → enojo · algo lindo → contento · algo triste → triste ·
     * chiste / trabalenguas / algo feliz → sonríe · algo asombroso → asombro · algo curioso → cara de curioso ·
     * algo normal → normal.
     */
    fun fromUserText(text: String): Int {
        val t = " " + text.lowercase().replace('¿', ' ').replace('¡', ' ') + " "
        val bangs = text.count { it == '!' } >= 2
        val insults = listOf("idiota", "estúpid", "estupid", "tonto", "tonta", "imbécil", "imbecil", "inútil", "inutil", "basura",
            "maldit", "mierda", "cállate", "callate", "te odio", "eres malo", "eres mala", "no sirves", "no sirve para nada",
            "pésimo", "pesimo", "eres horrible", "eres feo", "eres fea", "asqueroso", "asquerosa", "weón", "weon", "huevón", "huevon",
            "aweonao", "culiao", "conchetumare", " ctm", "qliao", "tarado", "tarada", "pendejo", "pendeja", "gil ", "bruto", "bruta",
            " stupid", " idiot ", "useless", "shut up", "hate you", "dumb", "you suck")
        val swears = listOf("mierda", "conchetumare", " ctm", "culiao", "qliao", "maldit", "fuck")
        val n = insults.count { t.contains(it) }
        if (n > 0) return if (n >= 2 || bangs || swears.any { t.contains(it) }) RAGE else ANGER
        if (has(t, "jaja", "jeje", "jiji", "haha", "😂", "🤣")) return LAUGHTER
        if (has(t, "trabalenguas", "chiste", "adivinanza", "tres tristes tigres", "pablito clavó", "pablito clavo", "erre con erre",
                "el cielo está enladrillado", "el cielo esta enladrillado", "compré pocas copas", "joke", "tongue twister", "riddle")) return JOY
        if (has(t, "falleci", "murió", "murio", "se murió", "funeral", "luto", "😭", "passed away", "died")) return GRIEF
        if (has(t, "triste", "me siento mal", "estoy mal", "deprimid", "lloré", "llore", "llorando", "me duele", "estoy enferm",
                "me dejó", "me dejo", "terminamos", "me despidieron", "perdí mi", "perdi mi", "estoy sol", "me siento sol",
                "😢", "😞", "😔", " sad ", "depressed", "lonely")) return SADNESS
        if (has(t, "te quiero", "te amo", "te adoro", "eres lind", "eres hermos", "eres guap", "eres genial", "eres el mejor",
                "eres la mejor", "eres increíble", "eres increible", "eres muy inteligente", "eres bacán", "eres bacan", "me encantas",
                "me caes bien", "gracias", "buen trabajo", "bien hecho", "qué lindo", "que lindo", "qué linda", "que linda", "❤", "😍", "🥰",
                "love you", "thank you", "thanks", "you're the best", "good job", "well done", "you're amazing")) return JOY
        if (has(t, "estoy feliz", "estoy contento", "estoy contenta", "qué alegría", "que alegria", "gané", " gane ", "aprobé", "aprobe",
                "me casé", "me case", "cumpleaños", "celebr", "buenas noticias", "lo logré", "lo logre", "😄", "😃", "🎉", "🥳",
                "i'm happy", "good news", "i won", "birthday")) return if (bangs) LAUGHTER else JOY
        if (has(t, "no lo vas a creer", "sabías que", "sabias que", "wow", "increíble", "increible", "asombroso", "impresionante",
                "no puede ser", "adivina qué", "adivina que", "mira esto", "de verdad", "en serio", "😮", "😲", "😱",
                "amazing", "incredible", "guess what", "no way")) return if (bangs) SHOCK else SURPRISE
        if (has(t, "por qué", "por que ", "cómo funciona", "como funciona", "qué pasaría", "que pasaria", "curios", "me pregunto",
                "qué es ", "que es ", "cómo se ", "como se ", "existe", "🤔", "what if", "why ", "how does", "i wonder")) return WONDER
        return NEUTRAL
    }

    /** Lo llama la app con lo que dijiste (por voz). El gesto se queda mientras la IA piensa y responde. */
    fun fromUser(text: String) {
        val e = fromUserText(text)
        if (e == NEUTRAL) { userHoldUntil = 0L; current = NEUTRAL; until = 0L; return }
        set(e, 60_000L)
        userHoldUntil = SystemClock.uptimeMillis() + 60_000L
    }

    // ---------------------------------------------------------------- 🎵 al compás de lo que dice la IA
    // Cada frase de la respuesta tiene su gesto; el avatar lo pone JUSTO cuando la voz llega a esa frase.
    private class Sentence(val start: Int, val end: Int, val mood: Int)
    @Volatile private var sentences: List<Sentence> = emptyList()
    @Volatile private var speechText = ""
    @Volatile private var holdMood = NEUTRAL          // gesto de tu mensaje (si había) para las frases sin emoción
    private val cuePos = ArrayList<Int>(); private val cueAt = ArrayList<Long>()
    @Volatile private var exactPos = -1; @Volatile private var exactAt = 0L
    @Volatile private var searchFrom = 0
    /** Letras por segundo que dice una voz (para calcular por dónde va entre un aviso y otro). */
    private const val CPS = 14f

    // ---------------------------------------------------------------- 🏷️ etiquetas que pone la IA
    /** Etiquetas de emoción que la IA puede escribir (con o sin tilde) → gesto. «neutral» = sin gesto a propósito. */
    private const val EXPLICIT_NEUTRAL = -2
    private val TAGS = mapOf(
        "alegria" to JOY, "feliz" to JOY, "carino" to JOY, "risa" to LAUGHTER, "diversion" to AMUSEMENT,
        "satisfaccion" to SATISFACTION, "tristeza" to SADNESS, "triste" to SADNESS, "dolor" to GRIEF,
        "melancolia" to MELANCHOLY, "sorpresa" to SURPRISE, "asombro" to SURPRISE, "impacto" to SHOCK,
        "curioso" to WONDER, "curiosidad" to WONDER, "interes" to WONDER, "atencion" to ALERTNESS,
        "enojo" to ANGER, "enfado" to ANGER, "furia" to RAGE, "indignacion" to INDIGNATION, "seriedad" to STERNNESS,
        "miedo" to FEAR, "terror" to TERROR, "preocupacion" to CONCERN, "ansiedad" to ANXIETY,
        "asco" to DISGUST, "desden" to DISDAIN, "desanimo" to DEJECTION,
        "neutral" to EXPLICIT_NEUTRAL, "calma" to EXPLICIT_NEUTRAL, "normal" to EXPLICIT_NEUTRAL,
        "joy" to JOY, "happy" to JOY, "laugh" to LAUGHTER, "sad" to SADNESS, "surprise" to SURPRISE,
        "curious" to WONDER, "angry" to ANGER, "fear" to FEAR, "worried" to CONCERN, "disgust" to DISGUST
    )
    private fun plainTag(t: String) = t.lowercase().trim()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
    /** Cualquier etiqueta [palabra] o [mirar:lado] (o «emocion: x»): solo las conocidas se quitan. */
    private val TAG_RE = Regex("\\[\\s*([A-Za-zÁÉÍÓÚáéíóúñÑ]+)(?:\\s*:\\s*([A-Za-zÁÉÍÓÚáéíóúñÑ]+))?\\s*]")
    private fun isOurTag(m: MatchResult): Boolean {
        val a = plainTag(m.groupValues[1])
        return a in TAGS || a == "mirar" || a == "look" || a == "emocion" || a == "emotion"
    }

    /** 🧹 Quita las etiquetas del avatar del texto (para mostrar, copiar, descargar y leer en voz alta). */
    fun stripTags(text: String): String {
        if (!text.contains('[')) return text
        var out = TAG_RE.replace(text) { m -> if (isOurTag(m)) "" else m.value }
        // (mientras la IA escribe, una etiqueta a medias al final tampoco se ve: «[alegr»)
        out = out.replace(Regex("\\[[A-Za-zÁÉÍÓÚáéíóúñÑ:]{0,14}$"), "")
        return out.replace(Regex("(?m)^[ \\t]+(?=\\S)"), "").replace(Regex(" {2,}"), " ")
    }

    /**
     * Lo llama la voz al empezar a hablar: prepara el gesto de cada frase y devuelve el texto SIN etiquetas
     * (lo que la voz lee). Si la IA marcó la frase con una etiqueta, manda esa; si no, se elige por las palabras.
     */
    @Synchronized fun fromSpeech(raw: String): String {
        val now = SystemClock.uptimeMillis()
        holdMood = if (now < userHoldUntil && current != NEUTRAL) current else NEUTRAL
        // 1) se sacan las etiquetas y se anota dónde quedó cada una en el texto limpio
        val clean = StringBuilder()
        val tagAt = ArrayList<Pair<Int, Int>>()   // (posición en el texto limpio, gesto)
        var last = 0
        for (m in TAG_RE.findAll(raw)) {
            clean.append(raw, last, m.range.first); last = m.range.last + 1
            if (!isOurTag(m)) { clean.append(m.value); continue }
            val a = plainTag(m.groupValues[1]); val b = plainTag(m.groupValues[2])
            when {
                a == "mirar" || a == "look" -> AvatarLook.fromUser("mira " + b)   // 👀 la IA mira hacia allá
                a == "emocion" || a == "emotion" -> TAGS[b]?.let { tagAt += clean.length to it }
                else -> tagAt += clean.length to TAGS.getValue(a)
            }
        }
        clean.append(raw, last, raw.length)
        val text = clean.toString().trim()
        val shift = clean.length - clean.toString().trimStart().length   // (lo que se recortó al principio)
        speechText = text
        // 2) cada frase: la etiqueta que cae en ella (o justo antes), o si no, por las palabras
        sentences = Regex("[^.!?…\\n]*[.!?…\\n]+|[^.!?…\\n]+").findAll(text)
            .map { m ->
                val st = m.range.first; val en = m.range.last + 1
                val tag = tagAt.firstOrNull { (pos, _) -> pos - shift in (st - 1) until en }
                val mood = when {
                    tag == null -> fromText(m.value)
                    else -> tag.second   // (EXPLICIT_NEUTRAL = la IA pidió cara normal en esta frase)
                }
                Sentence(st, en, mood)
            }.toList()
        // 😄 Si es un chiste: sonríe mientras lo cuenta (las frases sin emoción) y se ríe en el remate (la última frase)
        if (sentences.size > 1 && isJoke(text)) {
            val lastIdx = sentences.indexOfLast { it.end - it.start > 2 }
            sentences = sentences.mapIndexed { i, s ->
                when {
                    s.mood != NEUTRAL -> s                      // (la que ya tiene gesto, o la IA pidió cara normal, se respeta)
                    i == lastIdx -> Sentence(s.start, s.end, LAUGHTER)   // el remate
                    else -> Sentence(s.start, s.end, AMUSEMENT)          // contándolo, con una sonrisa
                }
            }
            if (holdMood == NEUTRAL) holdMood = AMUSEMENT
        }
        cuePos.clear(); cueAt.clear(); exactPos = -1; exactAt = 0L; searchFrom = 0
        // (el primer gesto se pone al tiro: la primera frase o el de tu mensaje)
        val first = (sentences.firstOrNull()?.mood ?: NEUTRAL).let { if (it == EXPLICIT_NEUTRAL) NEUTRAL else it }
        val e = if (first != NEUTRAL) first else holdMood
        if (e != NEUTRAL) set(e, 2500L)
        return text
    }

    /** La voz avisa que [chunk] (un pedazo de lo que dice) empieza a sonar en [atMs]. */
    @Synchronized fun cueChunk(chunk: String, atMs: Long) {
        val probe = chunk.trim().take(24)
        var pos = if (probe.isEmpty()) -1 else speechText.indexOf(probe, searchFrom)
        if (pos < 0) pos = searchFrom
        searchFrom = pos + 1
        cuePos.add(pos); cueAt.add(atMs)
    }

    /** La voz de Android avisa la letra exacta por donde va (dentro del pedazo que suena). */
    @Synchronized fun atCharInChunk(start: Int) {
        val base = if (cuePos.isEmpty()) 0 else cuePos[cuePos.size - 1]
        exactPos = base + start; exactAt = SystemClock.uptimeMillis()
    }

    /** Por qué letra va la voz ahora mismo. */
    @Synchronized private fun posNow(now: Long): Int {
        var bp = -1; var ba = 0L
        for (k in cuePos.indices) if (cueAt[k] <= now) { bp = cuePos[k]; ba = cueAt[k] }
        if (exactPos >= 0 && exactAt >= ba) { bp = exactPos; ba = exactAt }
        if (bp < 0) return 0
        return bp + ((now - ba) * CPS / 1000f).toInt()
    }

    /** 🎭 El gesto de la frase que se está diciendo (si la frase no tiene emoción, el de tu mensaje o neutro). */
    fun moodWhileSpeaking(now: Long): Int {
        val list = sentences
        if (list.isEmpty()) return current
        val p = posNow(now).coerceAtMost(speechText.length - 1).coerceAtLeast(0)
        val sNow = list.lastOrNull { it.start <= p } ?: list.first()
        // 🏷️ la IA pidió cara normal en esta frase: se quita el gesto al tiro
        if (sNow.mood == EXPLICIT_NEUTRAL) { current = NEUTRAL; until = 0L; return NEUTRAL }
        val m = if (sNow.mood != NEUTRAL) sNow.mood else holdMood
        if (m != NEUTRAL) { current = m; until = now + 2000L } else if (now >= until) current = NEUTRAL
        return if (m != NEUTRAL) m else if (now < until) current else NEUTRAL
    }
}

/**
 * 👀 Hacia dónde mira el avatar: SOLO cuando se lo pides («mira arriba», «mira abajo», «mira a la izquierda»,
 * «mira a la derecha»). Se queda mirando hacia allá 3 segundos y endereza la cara sola para mirarte
 * (o antes, si le dices «mira», «mírame» o «mira al frente»). Si no se lo pides, la cara no gira.
 * yaw: −1 izquierda … +1 derecha (como lo ves en la pantalla) · pitch: −1 abajo … +1 arriba.
 */
object AvatarLook {
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var until = 0L
    /** Cuánto se queda mirando hacia ese lado antes de enderezar la cara y volver a mirarte. */
    const val HOLD_MS = 3000L

    private fun plain(t: String) = t.lowercase()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')

    /** Lee lo que dijiste: si es una orden de mirar, mueve la cara y devuelve true. */
    fun fromUser(text: String): Boolean {
        val t = " " + plain(text) + " "
        val verb = listOf("mira", "mire", "mirar", "voltea", "gira", "girar", "dirige la mirada", "look", "turn").any { t.contains(it) }
        if (!verb) return false
        if (listOf("mirame", "mira me", "al frente", "adelante", "al centro", "a mi ", "hacia mi", "look at me", "straight", "center").any { t.contains(it) }) {
            yaw = 0f; pitch = 0f; until = 0L; return true
        }
        var y = 0f; var p = 0f
        if (t.contains("izquierd") || t.contains(" left")) y = -1f
        if (t.contains("derech") || t.contains(" right")) y = 1f
        if (t.contains("arriba") || t.contains(" up ") || t.contains("al cielo") || t.contains("hacia el techo")) p = 1f
        if (t.contains("abajo") || t.contains(" down") || t.contains("al suelo") || t.contains("al piso")) p = -1f
        // «mira» solo (sin decir hacia dónde): endereza la cara y te mira
        if (y == 0f && p == 0f) { yaw = 0f; pitch = 0f; until = 0L; return true }
        // (se queda mirando hacia allá 3 segundos y vuelve sola a mirarte)
        yaw = y; pitch = p; until = SystemClock.uptimeMillis() + HOLD_MS
        return true
    }
}

/**
 * 🖼️ Imagen propia del avatar (un personaje, una foto…): la cara se anima igual que el avatar original —
 * parpadea, mueve la boca al compás de la voz y pone los 24 gestos — deformando la imagen con una malla.
 * [points] = ojo izquierdo (x, y), ojo derecho (x, y) y centro de la boca (x, y), de 0 a 1 sobre la imagen.
 */
object AvatarCustom {
    /**
     * ✏️ Ojo dibujado con el dedo (su contorno): para cada columna se guarda el borde de arriba y el de abajo,
     * y el color de la piel que lo rodea (para pintar el párpado con esa forma exacta).
     */
    class EyeShape(val poly: FloatArray, val xs: FloatArray, val top: FloatArray, val bot: FloatArray, val skin: Int, val line: Int)
    /** ✏️ Boca dibujada (la línea entre los labios), en píxeles de la imagen, ordenada de izquierda a derecha. */
    class MouthLine(val pts: FloatArray)

    /** Lee «x,y x,y …» (de 0 a 1) a píxeles de la imagen. */
    private fun parsePts(t: String, w: Int, h: Int): FloatArray? {
        val out = ArrayList<Float>()
        for (pair in t.trim().split(' ')) {
            val xy = pair.split(',')
            if (xy.size != 2) continue
            val x = xy[0].toFloatOrNull() ?: continue; val y = xy[1].toFloatOrNull() ?: continue
            out += x * w; out += y * h
        }
        return if (out.size >= 6) out.toFloatArray() else null
    }

    private fun lum(c: Int) = Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f

    /** Arma el ojo dibujado: bordes de arriba/abajo por columnas y el color de la piel de alrededor. */
    private fun buildEye(bmp: android.graphics.Bitmap, poly: FloatArray): EyeShape? {
        val n = poly.size / 2
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until n) { x0 = min(x0, poly[i * 2]); x1 = max(x1, poly[i * 2]); y0 = min(y0, poly[i * 2 + 1]); y1 = max(y1, poly[i * 2 + 1]) }
        if (x1 - x0 < 3f || y1 - y0 < 2f) return null
        val cols = 32
        val xs = FloatArray(cols); val top = FloatArray(cols); val bot = FloatArray(cols)
        for (c in 0 until cols) {
            val x = x0 + (x1 - x0) * (c + 0.5f) / cols
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in 0 until n) {
                val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
                val bx = poly[((i + 1) % n) * 2]; val by = poly[((i + 1) % n) * 2 + 1]
                if ((x - ax) * (x - bx) <= 0f && ax != bx) {
                    val y = ay + (by - ay) * (x - ax) / (bx - ax)
                    lo = min(lo, y); hi = max(hi, y)
                }
            }
            if (lo == Float.MAX_VALUE) { lo = (y0 + y1) / 2; hi = lo }
            xs[c] = x; top[c] = lo; bot[c] = hi
        }
        // piel de alrededor: muestras arriba y abajo del ojo; se usa la del medio por brillo (evita cejas y pestañas)
        val eh = y1 - y0
        val samples = ArrayList<Int>()
        for (c in 0 until cols step 3) for (k in 0..2) {
            val x = xs[c].toInt().coerceIn(0, bmp.width - 1)
            val ya = (top[c] - eh * (0.25f + k * 0.12f)).toInt().coerceIn(0, bmp.height - 1)
            val yb = (bot[c] + eh * (0.25f + k * 0.12f)).toInt().coerceIn(0, bmp.height - 1)
            samples += bmp.getPixel(x, ya); samples += bmp.getPixel(x, yb)
        }
        val sorted = samples.filter { Color.alpha(it) > 128 }.sortedBy { lum(it) }
        val skin = sorted.getOrNull((sorted.size * 0.7f).toInt()) ?: Color.rgb(200, 170, 150)
        // la línea de las pestañas: lo más oscuro del borde del ojo (en anime, el trazo negro)
        val edge = ArrayList<Int>()
        for (i in 0 until n) edge += bmp.getPixel(poly[i * 2].toInt().coerceIn(0, bmp.width - 1), poly[i * 2 + 1].toInt().coerceIn(0, bmp.height - 1))
        val line = edge.minByOrNull { lum(it) }?.let { if (lum(it) < 90) it else Color.rgb(30, 20, 24) } ?: Color.rgb(30, 20, 24)
        return EyeShape(poly, xs, top, bot, Color.rgb(Color.red(skin), Color.green(skin), Color.blue(skin)), line)
    }

    class Face(val bitmap: android.graphics.Bitmap, val points: FloatArray, val cutout: Boolean = false, shapes: String? = null) {
        /** ✏️ Ojos dibujados (null = marcado con un toque) y boca dibujada. */
        val eyes = arrayOfNulls<EyeShape>(2)
        var mouthLine: MouthLine? = null
        // color de la piel bajo cada ojo (para dibujar el párpado al parpadear)
        val lidColor = IntArray(2)
        init {
            val w = bitmap.width; val h = bitmap.height
            val d = kotlin.math.hypot((points[2] - points[0]) * w, (points[3] - points[1]) * h)
            for (k in 0..1) {
                var r = 0; var g = 0; var b = 0; var n = 0
                val ex = points[k * 2] * w; val ey = points[k * 2 + 1] * h
                for (dx in -2..2) for (dyk in 0..2) {
                    val px = (ex + dx * d * 0.04f).toInt().coerceIn(0, w - 1)
                    val py = (ey + d * (0.24f + dyk * 0.04f)).toInt().coerceIn(0, h - 1)
                    val c = bitmap.getPixel(px, py)
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
                lidColor[k] = Color.rgb(r / n, g / n, b / n)
            }
            // ✏️ formas dibujadas: «e1=x,y x,y…;e2=…;m=…»
            shapes?.split(';')?.forEach { part ->
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) return@forEach
                val pts = parsePts(kv[1], w, h) ?: return@forEach
                when (kv[0]) {
                    "e1" -> eyes[0] = runCatching { buildEye(bitmap, pts) }.getOrNull()
                    "e2" -> eyes[1] = runCatching { buildEye(bitmap, pts) }.getOrNull()
                    "m" -> {
                        // de izquierda a derecha
                        val idx = (0 until pts.size / 2).sortedBy { pts[it * 2] }
                        mouthLine = MouthLine(FloatArray(pts.size) { i -> pts[idx[i / 2] * 2 + (i % 2)] })
                    }
                }
            }
        }
    }
    @Volatile var face: Face? = null

    private const val PREFS = "CumulusAvatar"

    /** true mientras el avatar está en pantalla (solo entonces la imagen ocupa RAM). */
    @Volatile var active = false

    /** Lee la imagen guardada (si hay). Se llama cuando el avatar aparece en pantalla y al cambiar la imagen. */
    @Synchronized fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = p.getString("avatar_image_path", null)
        val pts = p.getString("avatar_points", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        if (path == null || pts == null || pts.size != 6) { face = null; loadFailed = path != null; return }
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        val cut = p.getBoolean("avatar_cutout", false)
        val shapes = p.getString("avatar_shapes", null)
        val newFace = if (bmp != null) runCatching { Face(bmp, pts.toFloatArray(), cut, shapes) }.getOrNull() else null
        loadFailed = newFace == null   // (imagen dañada o borrada: se usa la cara de IA)
        // (si el avatar se apagó mientras se leía, no se deja en RAM)
        if (!active) { newFace?.bitmap?.recycle(); return }
        face = newFace
    }

    /** true si la imagen guardada no se pudo leer (entonces se muestra la cara de IA por defecto). */
    @Volatile var loadFailed = false

    /**
     * ⏳ ¿Hay una imagen elegida que todavía se está leyendo? (al abrir la app tarda un instante)
     * Mientras tanto NO se muestra la cara de IA por defecto: antes aparecía un momento y al tiro cambiaba a tu imagen.
     */
    fun waitingForImage(context: Context): Boolean {
        if (face != null || loadFailed || !active) return false
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("avatar_image_path", null) ?: return false
        return java.io.File(path).exists()
    }

    /** 🧹 Suelta la imagen de la RAM (cuando el avatar se quita de la pantalla). */
    @Synchronized fun unload() {
        val f = face ?: return
        face = null
        runCatching { f.bitmap.recycle() }
    }

    /** [cutout] = la imagen ya viene recortada (solo cabeza, cabello, orejas y cuello, sin fondo). */
    /** [shapes] = ojos y boca dibujados con el dedo («e1=x,y x,y…;e2=…;m=…», de 0 a 1), o null si se marcaron con toques. */
    fun save(context: Context, path: String, pts: FloatArray, cutout: Boolean = false, shapes: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("avatar_image_path", path).putString("avatar_points", pts.joinToString(","))
            .putBoolean("avatar_cutout", cutout).putString("avatar_shapes", shapes).apply()
        // solo se carga si el avatar está en pantalla; si no, se lee cuando aparezca.
        // ⚠️ Aquí NO se suelta la imagen anterior: esto corre en segundo plano y la pantalla todavía la puede estar
        //    dibujando (el avatar ahora se ve siempre) → «imagen ya liberada» y la app se cerraba al tocar GUARDAR.
        //    La imagen nueva simplemente reemplaza a la anterior, y Android libera la vieja solo.
        if (active) load(context)
    }

    /** Vuelve al avatar original (la cara de IA dibujada). */
    fun reset(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // (si la imagen está en «Avatares guardados» NO se borra: sigue guardada ahí para volver a usarla)
        p.getString("avatar_image_path", null)?.let { if (!isInLibrary(context, it)) runCatching { java.io.File(it).delete() } }
        p.edit().remove("avatar_image_path").remove("avatar_points").remove("avatar_cutout").remove("avatar_shapes").apply()
        unload()
    }

    // ========================================================================
    // 🗂️ AVATARES GUARDADOS: hasta 10 avatares personalizados (imagen + ojos y boca marcados o dibujados).
    //    Cada vez que guardas un avatar nuevo se agrega aquí; desde la ventana se pueden USAR o BORRAR.
    // ========================================================================
    const val LIB_MAX = 10
    class SavedAvatar(val file: String, val points: FloatArray, val cutout: Boolean, val shapes: String?, val at: Long)

    fun libDir(context: Context) = java.io.File(context.filesDir, "avatar_lib").apply { mkdirs() }

    fun isInLibrary(context: Context, path: String): Boolean =
        runCatching { java.io.File(path).parentFile?.canonicalPath == libDir(context).canonicalPath }.getOrDefault(false)

    /** Los avatares guardados (los más nuevos primero). Los que ya no tienen su imagen se omiten. */
    fun libList(context: Context): List<SavedAvatar> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("avatar_lib", null) ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SavedAvatar>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val file = o.optString("file"); if (file.isEmpty() || !java.io.File(file).exists()) continue
            val pts = o.optString("points").split(",").mapNotNull { it.toFloatOrNull() }
            if (pts.size != 6) continue
            out += SavedAvatar(file, pts.toFloatArray(), o.optBoolean("cutout"), o.optString("shapes").ifEmpty { null }, o.optLong("at"))
        }
        return out.sortedByDescending { it.at }
    }

    private fun libWrite(context: Context, list: List<SavedAvatar>) {
        val arr = org.json.JSONArray()
        for (a in list) arr.put(org.json.JSONObject().apply {
            put("file", a.file); put("points", a.points.joinToString(",")); put("cutout", a.cutout)
            put("shapes", a.shapes ?: ""); put("at", a.at)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("avatar_lib", arr.toString()).apply()
    }

    /** Agrega un avatar a «Avatares guardados». false si ya hay 10. */
    @Synchronized fun libAdd(context: Context, file: String, pts: FloatArray, cutout: Boolean, shapes: String?): Boolean {
        val list = libList(context)
        if (list.size >= LIB_MAX) return false
        libWrite(context, list + SavedAvatar(file, pts, cutout, shapes, System.currentTimeMillis()))
        return true
    }

    /** Borra un avatar guardado (su imagen también). Si era el que estabas usando, vuelve la cara de IA. */
    @Synchronized fun libRemove(context: Context, file: String) {
        val wasActive = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("avatar_image_path", null) == file
        libWrite(context, libList(context).filter { it.file != file })
        if (wasActive) reset(context)
        runCatching { java.io.File(file).delete() }
    }

    /** USAR: este avatar guardado pasa a ser el de la cara (con sus ojos y boca). */
    fun libUse(context: Context, a: SavedAvatar) = save(context, a.file, a.points, a.cutout, a.shapes)

    /** ¿Es el avatar que se está usando ahora? */
    fun isActivePath(context: Context, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("avatar_image_path", null) == file
}

/**
 * 🧑‍🚀 Avatar de la conversación por voz: un rostro de IA original, dibujado por la app (sin fotos ni licencias).
 * Piel azulada con sombras, ojos azules que parpadean solos y miran a los lados, circuitos en la frente y el cuello
 * que se encienden al hablar, anillos con puntos alrededor y una boca que se abre al ritmo de la voz.
 */
class AvatarView(context: Context) : View(context) {

    private val rnd = Random(7)
    // (antes de cada degradado se pone fill.color opaco: en Android la transparencia del color anterior también
    //  se aplica al degradado, y la cabeza quedaba transparente — se veía el cuello a través de la pera)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val head = Path()
    private val neck = Path()
    private val tmp = RectF()

    // ---- estado de la animación ----
    private var nextBlink = SystemClock.uptimeMillis() + 1500
    private var blinkStart = -1L
    private var doubleBlink = false
    private var gazeX = 0f; private var gazeY = 0f
    private var gazeTX = 0f; private var gazeTY = 0f
    private var nextGaze = 0L
    private var mouth = 0f
    private var glow = 0f
    private var lastFrame = 0L
    private var isSpeakingNow = false
    private var slowLevel = 0f   // (volumen promedio de la voz en el último medio segundo)
    // 👀 giro de la cara (solo cuando se lo pides)
    private var lookX = 0f; private var lookY = 0f
    // 🎭 gesto actual (se acerca suave al gesto pedido)
    private var eBIn = 0f; private var eBOut = 0f; private var eLid = 0f; private var eLow = 0f; private var eSmile = 0f
    private var eMw = 1f; private var eOpen = 0f; private var eTeeth = 0f; private var eFore = 0f; private var eNose = 0f
    private var isListeningNow = false

    // ---- partículas y circuitos (fijos, generados una vez) ----
    private class Dot(var a: Float, val r: Float, val speed: Float, val size: Float)
    private val ringDots = List(46) { Dot(rnd.nextFloat() * 6.283f, 0.46f + rnd.nextFloat() * 0.16f, (rnd.nextFloat() - 0.5f) * 0.25f, 1.2f + rnd.nextFloat() * 2.2f) }
    private class Floater(var x: Float, var y: Float, val vy: Float, val size: Float, val tw: Float)
    private val floaters = List(40) { Floater(rnd.nextFloat(), rnd.nextFloat(), 0.008f + rnd.nextFloat() * 0.02f, 0.8f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f) }
    /** Trazos de circuito en la frente / cuello: listas de puntos (x, y en unidades de la cara, centro = 0). */
    private val traces: List<FloatArray> = buildList {
        repeat(14) {
            var x = (rnd.nextFloat() - 0.5f) * 0.42f
            var y = -0.40f + rnd.nextFloat() * 0.06f
            val pts = ArrayList<Float>(); pts += x; pts += y
            repeat(3 + rnd.nextInt(3)) {
                if (rnd.nextBoolean()) y += 0.03f + rnd.nextFloat() * 0.07f else x += (rnd.nextFloat() - 0.5f) * 0.10f
                pts += x; pts += y.coerceAtMost(-0.12f)
            }
            add(pts.toFloatArray())
        }
        repeat(8) {
            var x = (rnd.nextFloat() - 0.5f) * 0.22f
            var y = 0.52f + rnd.nextFloat() * 0.05f
            val pts = ArrayList<Float>(); pts += x; pts += y
            repeat(2 + rnd.nextInt(2)) {
                if (rnd.nextBoolean()) y += 0.05f + rnd.nextFloat() * 0.06f else x += (rnd.nextFloat() - 0.5f) * 0.10f
                pts += x; pts += y
            }
            add(pts.toFloatArray())
        }
    }


    /** 🔵 Circuitos que pediste en la cara (sienes, pómulos, junto a la boca) y en el cuello y los hombros. */
    private val faceTraces: List<FloatArray> = listOf(
        floatArrayOf(-0.206f, -0.231f, -0.206f, -0.153f, -0.252f, -0.148f),
        floatArrayOf(0.146f, -0.287f, 0.146f, -0.199f, 0.19f, -0.194f),
        floatArrayOf(0.218f, -0.259f, 0.218f, -0.144f),
        floatArrayOf(-0.252f, -0.083f, -0.238f, 0.028f, -0.181f, 0.028f),
        floatArrayOf(0.241f, -0.074f, 0.231f, 0.005f, 0.185f, 0.028f),
        floatArrayOf(-0.125f, 0.046f, -0.125f, 0.12f, -0.171f, 0.12f),
        floatArrayOf(-0.208f, 0.079f, -0.194f, 0.157f, -0.157f, 0.162f),
        floatArrayOf(-0.122f, 0.176f, -0.12f, 0.222f, -0.153f, 0.222f),
        floatArrayOf(0.076f, 0.032f, 0.083f, 0.088f, 0.119f, 0.088f),
        floatArrayOf(0.157f, 0.023f, 0.153f, 0.116f, 0.111f, 0.13f),
        floatArrayOf(0.218f, 0.079f, 0.199f, 0.162f, 0.148f, 0.176f),
        floatArrayOf(0.122f, 0.162f, 0.13f, 0.222f, 0.181f, 0.218f))
    private val neckTraces: List<FloatArray> = listOf(
        floatArrayOf(-0.125f, 0.37f, -0.134f, 0.463f, -0.176f, 0.472f),
        floatArrayOf(-0.065f, 0.421f, -0.079f, 0.574f),
        floatArrayOf(-0.009f, 0.417f, -0.009f, 0.481f),
        floatArrayOf(0.056f, 0.391f, 0.06f, 0.493f),
        floatArrayOf(0.148f, 0.37f, 0.093f, 0.375f, 0.097f, 0.505f),
        floatArrayOf(0.097f, 0.426f, 0.162f, 0.426f, 0.157f, 0.509f),
        floatArrayOf(-0.093f, 0.528f, -0.162f, 0.537f, -0.145f, 0.662f),
        floatArrayOf(-0.329f, 0.63f, -0.352f, 0.694f),
        floatArrayOf(-0.273f, 0.634f, -0.292f, 0.699f),
        floatArrayOf(-0.222f, 0.583f, -0.227f, 0.681f),
        floatArrayOf(0.153f, 0.597f, 0.167f, 0.681f),
        floatArrayOf(0.227f, 0.574f, 0.264f, 0.681f),
        floatArrayOf(0.31f, 0.611f, 0.329f, 0.69f))

    /** Para dibujarlo como fondo del chat (sin estar en la pantalla como vista). */
    var drawOnly = false
    fun render(c: Canvas) = onDraw(c)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val t = now / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        update(now, t, dt)

        // ---- fondo: azul noche con un halo ----
        fill.color = Color.BLACK; fill.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(Color.rgb(16, 34, 72), Color.rgb(6, 12, 30), Color.rgb(2, 4, 12)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null

        val s = min(w, h * 0.82f)                      // tamaño de la cara
        val cx = w / 2 + sin(t * 0.7f) * s * 0.004f    // (se mueve un poquito, como respirando)
        val cy = h * 0.47f + sin(t * 0.9f) * s * 0.006f

        // 🖼️ Imagen propia del avatar: se anima igual (parpadeo, boca, gestos) y no se dibuja la cara de IA
        val custom = AvatarCustom.face
        // ⏳ tu imagen se está leyendo (al abrir la app): solo el fondo, sin mostrar antes la cara por defecto
        if (custom == null && AvatarCustom.waitingForImage(context)) {
            drawFloaters(c, w, h, t, dt)
            if (!drawOnly) postInvalidateOnAnimation()
            return
        }
        if (custom != null) {
            if (custom.cutout) drawRings(c, w / 2, h * 0.47f, s, t, dt)   // (sin fondo: se ven los anillos de atrás)
            drawCustom(c, custom, w, h, s, t)
            drawFloaters(c, w, h, t, dt)
            if (!drawOnly) postInvalidateOnAnimation()
            return
        }
        drawFloaters(c, w, h, t, dt)
        drawRings(c, cx, cy, s, t, dt)
        // 👀 giro: los rasgos se corren hacia donde mira (la nariz un poco más, porque sobresale), la cabeza un poquito,
        //    y las orejas al revés (como si la cabeza girara de verdad)
        val fx = lookX * s * 0.06f; val fy = -lookY * s * 0.045f
        drawNeck(c, cx, cy, s)
        drawEars(c, cx - lookX * s * 0.02f, cy + fy * 0.3f, s)
        drawHead(c, cx + fx * 0.25f, cy + fy * 0.25f, s)
        drawCircuits(c, cx + fx * 0.6f, cy + fy * 0.6f, s, t)
        drawBrows(c, cx + fx, cy + fy, s)
        drawEye(c, cx - s * 0.115f + fx * (if (lookX > 0) 0.85f else 1.1f), cy - s * 0.035f + fy, s, false)
        drawEye(c, cx + s * 0.115f + fx * (if (lookX < 0) 0.85f else 1.1f), cy - s * 0.035f + fy, s, true)
        drawNose(c, cx + fx * 1.3f, cy + fy * 1.1f, s)
        drawMouth(c, cx + fx * 1.05f, cy + s * 0.215f + fy * 0.9f, s)

        if (!drawOnly) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ 🖼️ imagen propia
    private val MESH = 64
    private val meshVerts = FloatArray((MESH + 1) * (MESH + 1) * 2)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun gauss(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
        val a = (x - cx) / rx; val b = (y - cy) / ry
        return exp(-(a * a + b * b))
    }

    // ---- datos de la cara de este cuadro (los usa disp(), que mueve cada punto de la imagen) ----
    private var cf: AvatarCustom.Face? = null
    private var kD = 1f; private var kE1x = 0f; private var kE1y = 0f; private var kE2x = 0f; private var kE2y = 0f
    private var kMx = 0f; private var kMy = 0f; private var kEcx = 0f; private var kEcy = 0f
    private var kOpen = 0f; private var kWide = 0f; private var kLid = 0f
    private var kLx = 0f; private var kLy = 0f; private var kRx = 0f; private var kRy = 0f   // (puntas de la boca)
    private val dOut = FloatArray(2)

    /** ✏️ Altura de la línea de la boca dibujada en la columna x (fuera de la línea: la punta más cercana). */
    private fun mouthYAt(m: AvatarCustom.MouthLine, x: Float): Float {
        val p = m.pts; val n = p.size / 2
        if (x <= p[0]) return p[1]
        if (x >= p[(n - 1) * 2]) return p[(n - 1) * 2 + 1]
        for (i in 0 until n - 1) {
            val ax = p[i * 2]; val bx = p[(i + 1) * 2]
            if (x in ax..bx) { val f = if (bx > ax) (x - ax) / (bx - ax) else 0f; return p[i * 2 + 1] + (p[(i + 1) * 2 + 1] - p[i * 2 + 1]) * f }
        }
        return p[(n - 1) * 2 + 1]
    }

    /** Cuánto se abre la boca en esa columna (0 en las puntas, 1 al medio): así abre con la forma de la línea. */
    private fun mouthProfile(x: Float): Float {
        if (kRx <= kLx) return 1f
        val u = (x - kLx) / (kRx - kLx)
        if (u <= 0f || u >= 1f) return 0f
        return Math.pow(sin(u * PI.toFloat()).toDouble(), 0.6).toFloat()
    }

    /**
     * Cuánto se mueve el punto (x, y) de la imagen en este cuadro (resultado en dOut): giro de la cara, mandíbula,
     * comisuras, cejas, ojos. La malla y los dibujos de encima (párpados y boca dibujados) usan lo MISMO, así calzan.
     */
    private fun disp(x: Float, y: Float) {
        val f = cf!!; val d = kD
        var dx = 0f; var dy = 0f
        // 👀 giro de la cara (solo si se lo pides): los rasgos se corren hacia donde mira, los bordes quedan quietos
        if (lookX != 0f || lookY != 0f) {
            val fcx = (kEcx + kMx) / 2; val fcy = (kEcy + kMy) / 2
            val gf = gauss(x, y, fcx, fcy, 0.95f * d, 1.15f * d)
            val gn = gauss(x, y, fcx, (kEcy * 0.4f + kMy * 0.6f), 0.35f * d, 0.35f * d)   // (la nariz sobresale: se corre más)
            dx += lookX * d * (0.28f * gf + 0.11f * gn)
            dy -= lookY * d * (0.2f * gf + 0.06f * gn)
        }
        // mandíbula / labio de abajo: baja al hablar y en los gestos con boca abierta
        val ml = f.mouthLine
        if (ml == null) {
            val below = 1f / (1f + exp(-(y - kMy) / (0.035f * d)))
            dy += kOpen * 0.16f * d * below * gauss(x, y, kMx, kMy + 0.3f * d, 0.6f * d, 0.5f * d)
        } else if (kOpen > 0f) {
            // ✏️ boca dibujada: lo de abajo de TU línea baja; en las puntas no se abre (abre con la forma de la línea)
            val ly = mouthYAt(ml, x)
            val rel = y - ly
            val below = 1f / (1f + exp(-rel / (0.012f * d)))
            val prof = mouthProfile(x)
            val deep = (rel / (0.15f * d)).coerceIn(0f, 1f)
            val band = prof + (1f - prof) * deep
            val halfW = max(0.6f * d, (kRx - kLx) * 0.65f)
            dy += kOpen * 0.16f * d * below * band * gauss(x, y, kMx, kMy + 0.3f * d, halfW, 0.5f * d)
        }
        // comisuras: sonrisa (arriba) o tristeza (abajo), boca más ancha o más chica
        for (side in intArrayOf(-1, 1)) {
            val cx = if (ml != null) (if (side < 0) kLx else kRx) else kMx + side * 0.27f * d
            val cy = if (ml != null) (if (side < 0) kLy else kRy) else kMy
            val g = gauss(x, y, cx, cy, 0.16f * d, 0.13f * d)
            dy -= eSmile * 0.07f * d * g
            dx += side * (eSmile * 0.03f + (if (ml != null) 0f else (eMw - 1f) * 0.25f)) * d * g
        }
        // cejas (lado de la nariz y lado de afuera) y ojos
        for (e in 0..1) {
            val ex = if (e == 0) kE1x else kE2x; val ey = if (e == 0) kE1y else kE2y
            val inSide = if (kEcx > ex) 1f else -1f
            dy -= eBIn * 5f * d * gauss(x, y, ex + inSide * 0.17f * d, ey - 0.33f * d, 0.15f * d, 0.13f * d)
            dy -= eBOut * 5f * d * gauss(x, y, ex - inSide * 0.17f * d, ey - 0.30f * d, 0.15f * d, 0.13f * d)
            if (kWide > 0f) dy += (y - ey) * kWide * 0.35f * gauss(x, y, ex, ey, 0.28f * d, 0.17f * d)
            if (f.eyes[e] != null) continue   // ✏️ ojo dibujado: el párpado se pinta encima con su forma
            // ojos entrecerrados (el párpado de arriba baja) y párpado de abajo que sube (sonrisa)
            val sq = eLid.coerceAtLeast(0f)
            if (sq > 0f) dy += sq * 0.07f * d * gauss(x, y, ex, ey - 0.09f * d, 0.24f * d, 0.07f * d)
            if (eLow > 0f) dy -= eLow * 0.06f * d * gauss(x, y, ex, ey + 0.10f * d, 0.24f * d, 0.07f * d)
            if (kLid > 0.01f) {
                // todo el ojo se junta en una línea (el párpado de arriba baja mucho y el de abajo sube un poquito)
                val closeY = ey + 0.06f * d
                val up = if (y < closeY) 1f else 0.85f
                dy += (closeY - y) * kLid * up * min(1f, 1.7f * gauss(x, y, ex, ey, 0.27f * d, if (y < closeY) 0.22f * d else 0.12f * d))
            }
        }
        dOut[0] = dx; dOut[1] = dy
    }

    /**
     * Dibuja la imagen con la cara centrada y la deforma con una malla: la mandíbula baja al hablar, las comisuras
     * suben o bajan, las cejas se mueven, los ojos se abren; encima van los párpados (parpadeo) y el interior de la boca.
     * ✏️ Si dibujaste los ojos y la boca, el párpado y la boca toman EXACTAMENTE esa forma.
     */
    private fun drawCustom(c: Canvas, f: AvatarCustom.Face, w: Float, h: Float, s: Float, t: Float) {
        val bmp = f.bitmap; val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        val p = f.points
        val e1x = p[0] * bw; val e1y = p[1] * bh; val e2x = p[2] * bw; val e2y = p[3] * bh
        val mx = p[4] * bw; val my = p[5] * bh
        val d = kotlin.math.hypot(e2x - e1x, e2y - e1y).coerceAtLeast(4f)
        val ecx = (e1x + e2x) / 2; val ecy = (e1y + e2y) / 2
        val sc: Float; var tx: Float; var ty: Float
        if (f.cutout) {
            // ✂️ Recortada (cabeza, cabello, orejas y cuello): margen que se adapta — entra COMPLETA, centrada,
            //    sea una cara angosta y calva o una ancha con mucho pelo (Vegeta)
            sc = min(w * 0.9f / bw, h * 0.86f / bh)
            tx = (w - bw * sc) / 2
            ty = (h - bh * sc) / 2 - h * 0.02f + sin(t * 0.9f) * s * 0.004f
        } else {
            // Sin recortar (no se pudo quitar el fondo): la cara grande como el avatar original, sin bordes vacíos
            sc = max(0.26f * s / d, max(w / bw, h / bh))
            tx = (w / 2 - ecx * sc).coerceIn(w - bw * sc, 0f)
            ty = (h * 0.40f - ecy * sc + sin(t * 0.9f) * s * 0.004f).coerceIn(h - bh * sc, 0f)
        }

        // ---- datos de este cuadro ----
        cf = f; kD = d; kE1x = e1x; kE1y = e1y; kE2x = e2x; kE2y = e2y; kMx = mx; kMy = my; kEcx = ecx; kEcy = ecy
        kOpen = (eOpen * 1.15f + mouth * (1f - eOpen * 0.5f)).coerceIn(0f, 1.4f)
        kWide = (-eLid).coerceAtLeast(0f)
        // 👁️ parpadeo (en ojos marcados con un toque: el ojo se cierra estirando la piel del párpado hacia abajo)
        kLid = max(blinkAmount(SystemClock.uptimeMillis()), (eLid - 0.35f).coerceAtLeast(0f) * 1.2f).coerceIn(0f, 1f)
        f.mouthLine?.pts?.let { mp -> kLx = mp[0]; kLy = mp[1]; kRx = mp[mp.size - 2]; kRy = mp[mp.size - 1] }

        // ---- malla deformada (solo se calcula cerca de la cara: más rápido) ----
        val fx0 = min(e1x, mx) - 2.2f * d; val fx1 = max(e2x, mx) + 2.2f * d
        val fy0 = min(e1y, e2y) - 2.0f * d; val fy1 = my + 2.2f * d
        var k = 0
        for (j in 0..MESH) for (i in 0..MESH) {
            val x = bw * i / MESH; val y = bh * j / MESH
            if (x < fx0 || x > fx1 || y < fy0 || y > fy1) { meshVerts[k++] = x; meshVerts[k++] = y; continue }
            disp(x, y)
            meshVerts[k++] = x + dOut[0]; meshVerts[k++] = y + dOut[1]
        }
        val save = c.save()
        c.translate(tx, ty); c.scale(sc, sc)
        c.rotate(sin(t * 0.45f) * 0.8f, ecx, ecy + 0.5f * d)   // (la cabeza se mueve un poquito)
        c.drawBitmapMesh(bmp, MESH, MESH, meshVerts, 0, null, 0, bmpPaint)

        // ---- interior de la boca (oscuro, con dientes) ----
        val ml = f.mouthLine
        if (kOpen > 0.03f && ml != null) {
            // ✏️ boca dibujada: el hueco va entre el labio de arriba (quieto) y el de abajo (que bajó), con TU forma
            val cell = max(bw, bh) / MESH
            val n = 24
            val topX = FloatArray(n + 1); val topY = FloatArray(n + 1); val botX = FloatArray(n + 1); val botY = FloatArray(n + 1)
            var maxGap = 0f
            for (q in 0..n) {
                val x = kLx + (kRx - kLx) * q / n
                val ly = mouthYAt(ml, x)
                disp(x, ly - cell); topX[q] = x + dOut[0]; topY[q] = ly - cell * 0.2f + dOut[1]
                disp(x, ly + cell); botX[q] = x + dOut[0]; botY[q] = ly + cell * 0.2f + dOut[1]
                // (en las puntas los labios se juntan: el hueco termina en punta, con la forma de tu línea)
                val pr = mouthProfile(x)
                botY[q] = topY[q] + (botY[q] - topY[q]) * Math.pow(pr.toDouble(), 0.35).toFloat()
                maxGap = max(maxGap, botY[q] - topY[q])
            }
            if (maxGap > 1.5f) {
                path.reset(); path.moveTo(topX[0], topY[0])
                for (q in 1..n) path.lineTo(topX[q], topY[q])
                for (q in n downTo 0) path.lineTo(botX[q], botY[q])
                path.close()
                fill.color = Color.argb(238, 30, 10, 18); c.drawPath(path, fill)
                if (kOpen > 0.22f || eTeeth > 0.3f) {
                    // dientes de arriba: una franja clara pegada al labio de arriba
                    c.save(); c.clipPath(path)
                    path.reset(); path.moveTo(topX[0], topY[0])
                    for (q in 1..n) path.lineTo(topX[q], topY[q])
                    for (q in n downTo 0) path.lineTo(topX[q], topY[q] + (botY[q] - topY[q]) * 0.33f)
                    path.close()
                    fill.color = Color.argb(205, 238, 238, 242); c.drawPath(path, fill)
                    c.restore()
                }
            }
        } else if (kOpen > 0.03f) {
            val hw = 0.16f * d * eMw * (0.8f + 0.2f * kOpen)
            val hh = kOpen * 0.075f * d
            val oy = my + kOpen * 0.07f * d - eSmile * 0.02f * d
            tmp.set(mx - hw, oy - hh, mx + hw, oy + hh)
            path.reset(); path.addOval(tmp, Path.Direction.CW)
            fill.color = Color.BLACK; fill.shader = RadialGradient(mx, oy, hw, intArrayOf(Color.argb(240, 26, 8, 16), Color.argb(230, 40, 14, 24), Color.argb(150, 60, 24, 34)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, fill); fill.shader = null
            if (kOpen > 0.22f || eTeeth > 0.3f) {
                c.save(); c.clipPath(path)
                fill.color = Color.argb(200, 235, 235, 240)
                tmp.set(mx - hw * 0.8f, oy - hh * 1.2f, mx + hw * 0.8f, oy - hh * 0.45f); c.drawOval(tmp, fill)
                c.restore()
            }
        }

        // ---- ✏️ ojos dibujados: el párpado baja con la forma EXACTA que trazaste, del color de la piel de alrededor ----
        val blinkNow = blinkAmount(SystemClock.uptimeMillis())
        for (e in 0..1) {
            val es = f.eyes[e] ?: continue
            val lid = max(blinkNow, eLid.coerceAtLeast(0f) * 0.6f).coerceIn(0f, 1f)
            val low = (eLow * 0.5f).coerceIn(0f, 0.5f)
            val cols = es.xs.size
            var eh = 1f
            for (q in 0 until cols) eh = max(eh, es.bot[q] - es.top[q])
            val pad = eh * 0.18f
            val midY = (es.top[cols / 2] + es.bot[cols / 2]) / 2
            disp(es.xs[cols / 2], midY)          // (el ojo se mueve junto con la cara: cejas, giro…)
            val ox = dOut[0]; val oy = dOut[1]
            if (lid > 0.02f) {
                path.reset()
                path.moveTo(es.xs[0] + ox - pad * 0.3f, es.top[0] - pad + oy)
                for (q in 0 until cols) path.lineTo(es.xs[q] + ox, es.top[q] - pad + oy)
                path.lineTo(es.xs[cols - 1] + ox + pad * 0.3f, es.top[cols - 1] - pad + oy)
                for (q in cols - 1 downTo 0) {
                    val ey = es.top[q] + (es.bot[q] - es.top[q]) * lid
                    path.lineTo(es.xs[q] + ox, ey + oy + (if (lid > 0.9f) pad * 0.5f else 0f))
                }
                path.close()
                fill.color = Color.BLACK; fill.shader = LinearGradient(0f, es.top[0] - pad + oy, 0f, midY + eh * 0.5f + oy,
                    es.skin, darker(es.skin, 0.88f), Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
                // pestañas: el borde del párpado con el color del trazo del ojo (en anime, la línea negra)
                path.reset()
                for (q in 0 until cols) {
                    val ey = es.top[q] + (es.bot[q] - es.top[q]) * lid
                    if (q == 0) path.moveTo(es.xs[q] + ox, ey + oy) else path.lineTo(es.xs[q] + ox, ey + oy)
                }
                stroke.color = es.line; stroke.strokeWidth = max(1.5f, eh * 0.12f)
                c.drawPath(path, stroke)
            }
            if (low > 0.02f) {
                path.reset()
                for (q in 0 until cols) {
                    val ey = es.bot[q] - (es.bot[q] - es.top[q]) * low
                    if (q == 0) path.moveTo(es.xs[q] + ox, ey + oy) else path.lineTo(es.xs[q] + ox, ey + oy)
                }
                for (q in cols - 1 downTo 0) path.lineTo(es.xs[q] + ox, es.bot[q] + pad * 0.6f + oy)
                path.close()
                fill.color = es.skin; c.drawPath(path, fill)
            }
        }

        // ---- pestañas de los ojos marcados con un toque: cuando el ojo está cerrado se marca una línea oscura suave ----
        if (kLid > 0.55f) {
            for (e in 0..1) {
                if (f.eyes[e] != null) continue
                val ex = if (e == 0) e1x else e2x; val ey = if (e == 0) e1y else e2y
                stroke.color = Color.argb(((kLid - 0.55f) * 300).toInt().coerceIn(0, 140), 20, 14, 18); stroke.strokeWidth = 0.02f * d
                path.reset(); path.moveTo(ex - 0.2f * d, ey + 0.03f * d); path.quadTo(ex, ey + 0.08f * d, ex + 0.2f * d, ey + 0.03f * d)
                c.drawPath(path, stroke)
            }
        }
        c.restoreToCount(save)

        // brillo azul suave en los bordes (para que siga pareciendo un holograma de IA)
        if (f.cutout) return
        fill.color = Color.BLACK; fill.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, accent((50 + glow * 70).toInt())), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
    }

    private fun darker(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    // ------------------------------------------------------------------ animación
    private fun update(now: Long, t: Float, dt: Float) {
        // 👁️ Parpadeo: solo, cada 2 a 5 segundos (a veces dos seguidos)
        if (blinkStart < 0 && now >= nextBlink) { blinkStart = now; doubleBlink = rnd.nextFloat() < 0.18f }
        if (blinkStart >= 0 && now - blinkStart > (if (doubleBlink) 480 else 220)) {
            blinkStart = -1; nextBlink = now + 2000 + rnd.nextInt(3000)
        }
        // 👀 La mirada cambia de punto cada tanto
        if (now >= nextGaze) {
            gazeTX = (rnd.nextFloat() - 0.5f) * 0.9f; gazeTY = (rnd.nextFloat() - 0.5f) * 0.5f
            if (rnd.nextFloat() < 0.45f) { gazeTX = 0f; gazeTY = 0f }   // (casi siempre te mira a ti)
            nextGaze = now + 1200 + rnd.nextInt(2600)
        }
        // 👀 «mira arriba / abajo / a la izquierda / a la derecha»: la cara gira y los ojos miran hacia allá
        val looking = now < AvatarLook.until
        val lx = if (looking) AvatarLook.yaw else 0f; val ly = if (looking) AvatarLook.pitch else 0f
        val kl = 1f - exp(-dt * 4f)
        lookX += (lx - lookX) * kl; lookY += (ly - lookY) * kl
        if (looking) { gazeTX = lx * 0.95f; gazeTY = -ly * 0.9f; nextGaze = now + 800 }
        val k = 1f - exp(-dt * 14f)
        gazeX += (gazeTX - gazeX) * k; gazeY += (gazeTY - gazeY) * k

        // 👄 Boca: con la voz real si llega; si no, un ritmo de sílabas mientras habla
        AvatarVoice.tick(now)
        var target = 0f
        val speaking = AvatarVoice.isSpeaking()
        val listening = !speaking && AvatarVoice.isListening()
        if (speaking) {
            val age = (now - AvatarVoice.levelAt).toFloat()
            // 🗣️ sílabas suaves (~4 por segundo, como una persona), sin saltos bruscos
            val ph = t * 2f * PI.toFloat() * 3.8f
            val syl = 0.5f - 0.5f * cos(ph + 0.6f * sin(t * 1.7f))
            val synthetic = 0.12f + 0.5f * syl * (0.75f + 0.25f * sin(t * 0.9f))
            // 😠 Abre harto la boca SOLO si está enojada (te insultaron o te dijeron algo feo); hablando normal, como al principio
            val boost = if (AvatarMood.current in 0..3) 1.7f else 1f
            val fast = if (age < 220f) AvatarVoice.level else 0f
            slowLevel += (fast - slowLevel) * (1f - exp(-dt * 2.5f))
            target = when (AvatarVoice.source) {
                // volumen real: cuando suena, la boca ABRE Y CIERRA por sílabas (~4 por segundo) — antes quedaba
                // casi abierta y solo «tiritaba»; en cada pausa de la voz se cierra
                1 -> if (fast < 0.05f) 0f else {
                    val amp = (0.22f + slowLevel * 1.2f).coerceAtMost(0.55f)
                    val dip = (fast / (slowLevel * 0.8f + 0.02f)).coerceIn(0f, 1f)   // (baja la voz → cierra)
                    amp * (0.02f + 0.98f * syl) * dip * boost
                }
                2 -> when {                                                                   // aviso por palabra
                    age < 480f -> AvatarVoice.level * (0.45f + 0.55f * syl) * exp(-age / 420f) * 0.75f * boost
                    age < 1600f -> 0.04f
                    else -> synthetic * boost                                                 // (el teléfono no avisa: sílabas)
                }
                else -> synthetic * boost
            }
        }
        isSpeakingNow = speaking; isListeningNow = listening
        // (abre y cierra con suavidad, como los labios de verdad: antes 30 / 16 y se veía tiritando)
        // (abre y cierra rápido pero sin saltos: así se ve que la boca cierra entre sílabas; antes 13 / 9 y no alcanzaba a cerrar)
        val attack = if (target > mouth) 1f - exp(-dt * 18f) else 1f - exp(-dt * 15f)
        mouth += (target.coerceIn(0f, 1f) - mouth) * attack
        // 🎭 gesto: el que pidió la frase (mientras dure) o neutro; escuchando = un poquito atento
        val nowMs = SystemClock.uptimeMillis()
        // (mientras habla: el gesto de la frase que va diciendo, al compás de la voz)
        val moodId = if (speaking) AvatarMood.moodWhileSpeaking(nowMs) else AvatarMood.current
        val active = moodId >= 0 && (nowMs < AvatarMood.until || speaking)
        val ex = if (active) AvatarMood.EXPRS[moodId] else if (listening) AvatarMood.EXPRS[AvatarMood.ALERTNESS] else AvatarMood.NEUTRAL_EXPR
        val w = if (!active && listening) 0.5f else 1f
        val ke = 1f - exp(-dt * 5f)
        eBIn += (ex.bIn * w - eBIn) * ke; eBOut += (ex.bOut * w - eBOut) * ke; eLid += (ex.lid * w - eLid) * ke
        eLow += (ex.low * w - eLow) * ke; eSmile += (ex.smile * w - eSmile) * ke; eMw += ((1f + (ex.mw - 1f) * w) - eMw) * ke
        eOpen += (ex.open * w - eOpen) * ke; eTeeth += (ex.teeth * w - eTeeth) * ke; eFore += (ex.fore * w - eFore) * ke
        eNose += (ex.nose * w - eNose) * ke
        val gTarget = if (speaking) 0.55f + mouth * 0.45f else if (listening) 0.35f else 0.18f
        glow += (gTarget - glow) * (1f - exp(-dt * 6f))
    }

    private fun blinkAmount(now: Long): Float {
        if (blinkStart < 0) return 0f
        val e = (now - blinkStart).toFloat()
        // (cierra rápido, queda cerrado un instante y abre: así se ve el ojo cerrado de verdad)
        fun one(x: Float) = when { x < 0f -> 0f; x < 60f -> x / 60f; x < 130f -> 1f; x < 220f -> 1f - (x - 130f) / 90f; else -> 0f }
        return if (doubleBlink) max(one(e), one(e - 260f)) else one(e)
    }

    // ------------------------------------------------------------------ dibujo
    private fun accent(a: Int): Int = if (isListeningNow && !isSpeakingNow)
        Color.argb(a, 90, 230, 255) else Color.argb(a, 70, 150, 255)

    private fun drawFloaters(c: Canvas, w: Float, h: Float, t: Float, dt: Float) {
        for (f in floaters) {
            f.y -= f.vy * dt; if (f.y < -0.02f) { f.y = 1.02f; f.x = rnd.nextFloat() }
            val a = (90 + 80 * sin(t * 2f + f.tw)).toInt().coerceIn(20, 200)
            fill.color = accent(a)
            c.drawCircle(f.x * w, f.y * h, f.size * resources.displayMetrics.density * 0.8f, fill)
        }
    }

    private fun drawRings(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, dt: Float) {
        stroke.strokeWidth = s * 0.0025f
        for (i in 0 until 3) {
            val r = s * (0.50f + i * 0.07f)
            stroke.color = accent((40 + glow * 90).toInt() - i * 8)
            val start = (t * (8f + i * 5f) * (if (i % 2 == 0) 1 else -1)) % 360f
            tmp.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(tmp, start, 250f - i * 40f, false, stroke)
        }
        for (d in ringDots) {
            d.a += d.speed * dt
            val r = s * d.r
            fill.color = accent((90 + glow * 140).toInt().coerceAtMost(240))
            c.drawCircle(cx + cos(d.a) * r, cy + sin(d.a) * r * 0.98f, d.size * s * 0.0022f, fill)
        }
    }

    private fun skinShader(cx: Float, cy: Float, s: Float): Shader = RadialGradient(cx - s * 0.03f, cy - s * 0.10f, s * 0.42f,
        intArrayOf(Color.rgb(226, 236, 248), Color.rgb(190, 208, 232), Color.rgb(118, 146, 186), Color.rgb(58, 82, 124)),
        floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)

    private fun drawNeck(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.165f, cy + s * 0.24f)
        path.cubicTo(cx - s * 0.175f, cy + s * 0.42f, cx - s * 0.205f, cy + s * 0.52f, cx - s * 0.40f, cy + s * 0.64f)
        path.lineTo(cx - s * 0.48f, cy + s * 0.95f)
        path.lineTo(cx + s * 0.48f, cy + s * 0.95f)
        path.lineTo(cx + s * 0.40f, cy + s * 0.64f)
        path.cubicTo(cx + s * 0.205f, cy + s * 0.52f, cx + s * 0.175f, cy + s * 0.42f, cx + s * 0.165f, cy + s * 0.24f)
        path.close()
        fill.color = Color.BLACK; fill.shader = LinearGradient(cx - s * 0.24f, 0f, cx + s * 0.24f, 0f,
            intArrayOf(Color.rgb(44, 64, 102), Color.rgb(132, 158, 198), Color.rgb(44, 64, 102)), null, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // sombra bajo la mandíbula
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, cy + s * 0.28f, 0f, cy + s * 0.38f, Color.argb(150, 10, 20, 45), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // los hombros se pierden en la oscuridad
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, cy + s * 0.55f, 0f, cy + s * 0.85f, Color.TRANSPARENT, Color.rgb(3, 6, 16), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        neck.reset(); neck.addPath(path)
    }

    private fun drawEars(c: Canvas, cx: Float, cy: Float, s: Float) {
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * s * 0.265f; val ey = cy - s * 0.01f
            tmp.set(ex - s * 0.04f, ey - s * 0.075f, ex + s * 0.04f, ey + s * 0.075f)
            fill.color = Color.BLACK; fill.shader = RadialGradient(ex, ey, s * 0.07f, Color.rgb(150, 172, 206), Color.rgb(60, 84, 126), Shader.TileMode.CLAMP)
            c.drawOval(tmp, fill); fill.shader = null
            stroke.color = Color.argb(110, 30, 50, 90); stroke.strokeWidth = s * 0.004f
            tmp.inset(s * 0.012f, s * 0.02f); c.drawArc(tmp, if (side < 0) 100f else -80f, 160f, false, stroke)
        }
    }

    private fun drawHead(c: Canvas, cx: Float, cy: Float, s: Float) {
        head.reset()
        val top = cy - s * 0.43f; val chin = cy + s * 0.34f
        head.moveTo(cx, top)
        // cráneo (más angosto arriba, no de huevo)
        head.cubicTo(cx + s * 0.17f, top, cx + s * 0.265f, cy - s * 0.30f, cx + s * 0.27f, cy - s * 0.10f)
        // pómulo
        head.cubicTo(cx + s * 0.275f, cy - s * 0.02f, cx + s * 0.27f, cy + s * 0.06f, cx + s * 0.255f, cy + s * 0.12f)
        // ángulo de la mandíbula (marcado)
        head.cubicTo(cx + s * 0.245f, cy + s * 0.17f, cx + s * 0.235f, cy + s * 0.205f, cx + s * 0.205f, cy + s * 0.235f)
        // línea de la mandíbula, recta hacia el mentón
        head.lineTo(cx + s * 0.078f, cy + s * 0.358f)
        // mentón cuadrado (la pera, un poco más larga)
        head.cubicTo(cx + s * 0.06f, cy + s * 0.385f, cx - s * 0.06f, cy + s * 0.385f, cx - s * 0.078f, cy + s * 0.358f)
        head.lineTo(cx - s * 0.205f, cy + s * 0.235f)
        head.cubicTo(cx - s * 0.235f, cy + s * 0.205f, cx - s * 0.245f, cy + s * 0.17f, cx - s * 0.255f, cy + s * 0.12f)
        head.cubicTo(cx - s * 0.27f, cy + s * 0.06f, cx - s * 0.275f, cy - s * 0.02f, cx - s * 0.27f, cy - s * 0.10f)
        head.cubicTo(cx - s * 0.265f, cy - s * 0.30f, cx - s * 0.17f, top, cx, top)
        head.close()
        fill.color = Color.BLACK; fill.shader = skinShader(cx, cy, s); c.drawPath(head, fill)
        // volumen: sombras a los costados y bajo los pómulos
        fill.color = Color.BLACK; fill.shader = LinearGradient(cx - s * 0.28f, 0f, cx + s * 0.28f, 0f,
            intArrayOf(Color.argb(120, 20, 36, 70), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 20, 36, 70)),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(head, fill)
        for (side in intArrayOf(-1, 1)) {
            fill.color = Color.BLACK; fill.shader = RadialGradient(cx + side * s * 0.17f, cy + s * 0.12f, s * 0.09f, Color.argb(70, 30, 50, 95), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawPath(head, fill)
        }
        // 🙂 pómulos: una luz alargada sobre el hueso (inclinada hacia la oreja) y debajo un hundido con sombra
        c.save(); c.clipPath(head)
        for (side in intArrayOf(-1, 1)) {
            // hundido bajo el pómulo
            c.save()
            c.translate(cx + side * s * 0.195f, cy + s * 0.125f); c.rotate(side * -30f); c.scale(1f, 0.5f)
            fill.color = Color.BLACK; fill.shader = RadialGradient(0f, 0f, s * 0.10f, Color.argb(48, 20, 36, 75), Color.argb(0, 20, 36, 75), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, s * 0.10f, fill)
            c.restore()
            // luz sobre el hueso del pómulo
            c.save()
            c.translate(cx + side * s * 0.165f, cy + s * 0.045f); c.rotate(side * -22f); c.scale(1f, 0.40f)
            fill.color = Color.BLACK; fill.shader = RadialGradient(0f, 0f, s * 0.08f, Color.argb(80, 245, 250, 255), Color.argb(0, 245, 250, 255), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, s * 0.08f, fill)
            c.restore()
        }
        fill.shader = null
        c.restore()
        // mandíbula marcada: sombra a lo largo de la línea de la mandíbula y luz en el mentón
        c.save(); c.clipPath(head)
        stroke.color = Color.argb(45, 20, 34, 70); stroke.strokeWidth = s * 0.06f
        path.reset()
        path.moveTo(cx - s * 0.27f, cy + s * 0.10f); path.lineTo(cx - s * 0.215f, cy + s * 0.25f); path.lineTo(cx - s * 0.078f, cy + s * 0.38f)
        path.lineTo(cx + s * 0.078f, cy + s * 0.38f); path.lineTo(cx + s * 0.215f, cy + s * 0.25f); path.lineTo(cx + s * 0.27f, cy + s * 0.10f)
        c.drawPath(path, stroke)
        c.restore()
        // 🧔 silueta de la pera: luz en el centro de la pera y un borde de sombra suave alrededor
        c.save(); c.clipPath(head)
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy + s * 0.325f, s * 0.07f,
            intArrayOf(Color.argb(65, 255, 255, 255), Color.TRANSPARENT, Color.argb(45, 20, 34, 70), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP)
        tmp.set(cx - s * 0.08f, cy + s * 0.255f, cx + s * 0.08f, cy + s * 0.40f); c.drawOval(tmp, fill); fill.shader = null
        c.restore()
        // brillo en la frente
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - s * 0.04f, cy - s * 0.27f, s * 0.14f, Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(head, fill); fill.shader = null
        // contorno con brillo azul (más fuerte cuando habla)
        stroke.strokeWidth = s * 0.006f; stroke.color = accent((60 + glow * 150).toInt())
        c.drawPath(head, stroke)
    }

    private fun drawCircuits(c: Canvas, cx: Float, cy: Float, s: Float, t: Float) {
        c.save(); c.clipPath(head)
        stroke.strokeWidth = s * 0.0035f
        traces.forEachIndexed { i, p ->
            if (p[1] > 0.4f) return@forEachIndexed   // (los del cuello van fuera de la cabeza)
            val pulse = 0.5f + 0.5f * sin(t * 3f + i)
            stroke.color = accent((50 + glow * 120 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((90 + glow * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        c.save(); c.clipPath(neck)
        traces.forEachIndexed { i, p ->
            if (p[1] <= 0.4f) return@forEachIndexed
            val pulse = 0.5f + 0.5f * sin(t * 3f + i)
            stroke.color = accent((60 + glow * 140 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((100 + glow * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        // 🔵 los circuitos marcados: cara (dentro de la cabeza) y cuello/hombros (dentro del cuello)
        c.save(); c.clipPath(head); drawTraceList(c, cx, cy, s, t, faceTraces, 5); c.restore()
        c.save(); c.clipPath(neck); drawTraceList(c, cx, cy, s, t, neckTraces, 11); c.restore()
    }

    private fun drawTraceList(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, list: List<FloatArray>, phase: Int) {
        list.forEachIndexed { i, p ->
            val pulse = 0.5f + 0.5f * sin(t * 3f + i + phase)
            stroke.strokeWidth = s * 0.0035f
            stroke.color = accent((70 + glow * 140 * pulse).toInt().coerceAtMost(255))
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = accent((110 + glow * 140 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
            fill.color = accent((80 + glow * 100 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[0] * s, cy + p[1] * s, s * 0.0035f, fill)
        }
    }

    private fun drawBrows(c: Canvas, cx: Float, cy: Float, s: Float) {
        val lift = mouth * s * 0.006f
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            val x0 = cx + side * s * 0.045f; val x1 = cx + side * s * 0.19f
            val y = cy - s * 0.10f - lift
            // 🎭 lado de la nariz (eBIn) y lado de afuera (eBOut) se mueven por separado
            val yi = y + s * 0.006f - eBIn * s * 1.8f; val yo = y + s * 0.012f - eBOut * s * 1.8f
            path.moveTo(x0, yi)
            path.quadTo(cx + side * s * 0.12f, y - s * 0.03f - (eBIn + eBOut) * 0.9f * s, x1, yo)
            stroke.color = Color.rgb(54, 76, 116); stroke.strokeWidth = s * 0.013f
            c.drawPath(path, stroke)
        }
        // 🎭 arrugas de la frente (sorpresa, miedo, tristeza)
        if (eFore > 0.05f) {
            stroke.strokeWidth = s * 0.004f
            for (k in 0 until 3) {
                stroke.color = Color.argb((eFore * (70 - k * 15)).toInt().coerceIn(0, 255), 40, 60, 100)
                val wy = cy - s * (0.17f + k * 0.035f)
                path.reset(); path.moveTo(cx - s * (0.13f - k * 0.015f), wy + s * 0.008f)
                path.quadTo(cx, wy - s * 0.012f * (if (eBIn > 0.015f) 1.6f else 1f), cx + s * (0.13f - k * 0.015f), wy + s * 0.008f)
                c.drawPath(path, stroke)
            }
        }
        // 🎭 entrecejo fruncido (enojo, asco)
        if (eBIn < -0.006f) {
            stroke.strokeWidth = s * 0.004f; stroke.color = Color.argb((-eBIn * 3500).toInt().coerceIn(0, 110), 40, 60, 100)
            for (side in intArrayOf(-1, 1)) {
                path.reset(); path.moveTo(cx + side * s * 0.012f, cy - s * 0.115f); path.lineTo(cx + side * s * 0.02f, cy - s * 0.075f)
                c.drawPath(path, stroke)
            }
        }
        // 🎭 nariz arrugada (asco)
        if (eNose > 0.05f) {
            stroke.strokeWidth = s * 0.0035f; stroke.color = Color.argb((eNose * 90).toInt().coerceIn(0, 255), 40, 60, 100)
            for (side in intArrayOf(-1, 1)) for (k in 0 until 2) {
                path.reset()
                val yy = cy - s * (0.02f - k * 0.02f)
                path.moveTo(cx + side * s * 0.018f, yy); path.quadTo(cx + side * s * 0.035f, yy - s * 0.008f, cx + side * s * 0.05f, yy + s * 0.004f)
                c.drawPath(path, stroke)
            }
        }
    }

    private fun drawEye(c: Canvas, ex: Float, ey: Float, s: Float, right: Boolean) {
        val ew = s * 0.085f; val eh = s * 0.034f * (1f + (-eLid).coerceAtLeast(0f) * 0.55f)   // 🎭 (ojos más abiertos)
        // cuenca (sombra)
        fill.color = Color.BLACK; fill.shader = RadialGradient(ex, ey - eh * 0.2f, ew * 1.5f, Color.argb(110, 30, 50, 95), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(ex, ey, ew * 1.5f, fill); fill.shader = null
        // forma de almendra
        path.reset()
        val inner = if (right) -1 else 1   // (lagrimal hacia la nariz)
        path.moveTo(ex - ew, ey + inner * 0f)
        path.cubicTo(ex - ew * 0.5f, ey - eh * 1.25f, ex + ew * 0.5f, ey - eh * 1.25f, ex + ew, ey)
        path.cubicTo(ex + ew * 0.5f, ey + eh * 0.95f, ex - ew * 0.5f, ey + eh * 0.95f, ex - ew, ey)
        path.close()
        c.save(); c.clipPath(path)
        fill.color = Color.rgb(236, 242, 250); c.drawRect(ex - ew, ey - eh * 1.3f, ex + ew, ey + eh, fill)
        // iris y pupila (miran hacia gazeX/gazeY)
        val ix = ex + gazeX * ew * 0.35f; val iy = ey + gazeY * eh * 0.3f
        val ir = eh * 1.02f
        fill.color = Color.BLACK; fill.shader = RadialGradient(ix, iy, ir, intArrayOf(Color.rgb(170, 220, 255), Color.rgb(60, 140, 230), Color.rgb(16, 52, 112)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(ix, iy, ir, fill); fill.shader = null
        fill.color = Color.rgb(6, 12, 26); c.drawCircle(ix, iy, ir * 0.42f, fill)
        fill.color = Color.argb(230, 255, 255, 255); c.drawCircle(ix - ir * 0.32f, iy - ir * 0.34f, ir * 0.18f, fill)
        fill.color = Color.argb(140, 255, 255, 255); c.drawCircle(ix + ir * 0.28f, iy + ir * 0.25f, ir * 0.08f, fill)
        // sombra del párpado sobre el ojo
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ey - eh * 1.2f, 0f, ey - eh * 0.2f, Color.argb(120, 20, 35, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(ex - ew, ey - eh * 1.3f, ex + ew, ey, fill); fill.shader = null
        // 👁️ párpado que baja al parpadear
        //    El borde del párpado ES la línea de pestañas (antes era un rectángulo y en el medio del parpadeo
        //    quedaba un pedazo de ojo a la vista). Al parpadear, el de abajo también sube y se juntan: el ojo no se ve.
        val bl = blinkAmount(SystemClock.uptimeMillis())
        val b = max(bl, eLid.coerceAtLeast(0f) * 0.75f)   // 🎭 (entrecerrado)
        val lyU = ey - eh * 1.25f + eh * 1.72f * b          // (curva del párpado de arriba)
        val lyL = ey + eh * 0.95f - eh * 0.48f * bl         // (curva del párpado de abajo)
        if (b > 0.01f) {
            path.reset()
            path.moveTo(ex - ew, ey - eh * 2f); path.lineTo(ex + ew, ey - eh * 2f); path.lineTo(ex + ew, ey)
            path.cubicTo(ex + ew * 0.5f, lyU, ex - ew * 0.5f, lyU, ex - ew, ey); path.close()
            fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ey - eh * 1.3f, 0f, ey + eh * 0.5f, Color.rgb(176, 194, 222), Color.rgb(132, 154, 192), Shader.TileMode.CLAMP)
            c.drawPath(path, fill); fill.shader = null
        }
        if (bl > 0.01f) {
            path.reset()
            path.moveTo(ex - ew, ey + eh * 2f); path.lineTo(ex + ew, ey + eh * 2f); path.lineTo(ex + ew, ey)
            path.cubicTo(ex + ew * 0.5f, lyL, ex - ew * 0.5f, lyL, ex - ew, ey); path.close()
            fill.color = Color.rgb(150, 170, 206); c.drawPath(path, fill)
        }
        // 🎭 párpado de abajo que sube (sonrisa, asco, risa)
        if (eLow > 0.01f) {
            fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ey, 0f, ey + eh, Color.rgb(150, 170, 206), Color.rgb(176, 194, 222), Shader.TileMode.CLAMP)
            c.drawRect(ex - ew, ey + eh - eh * 1.4f * eLow, ex + ew, ey + eh * 1.2f, fill); fill.shader = null
        }
        c.restore()
        // línea de pestañas (sigue al párpado)
        path.reset()
        val ly = lyU
        path.moveTo(ex - ew, ey)
        path.cubicTo(ex - ew * 0.5f, ly, ex + ew * 0.5f, ly, ex + ew, ey)
        stroke.color = Color.rgb(18, 30, 58); stroke.strokeWidth = s * 0.006f
        c.drawPath(path, stroke)
        // pliegue del párpado
        path.reset()
        path.moveTo(ex - ew * 0.85f, ey - eh * 0.9f)
        path.quadTo(ex, ey - eh * 2.0f, ex + ew * 0.85f, ey - eh * 0.9f)
        stroke.color = Color.argb(90, 40, 60, 100); stroke.strokeWidth = s * 0.004f
        c.drawPath(path, stroke)
    }

    private fun drawNose(c: Canvas, cx: Float, cy: Float, s: Float) {
        // puente con brillo
        fill.color = Color.BLACK; fill.shader = LinearGradient(cx - s * 0.025f, 0f, cx + s * 0.03f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(60, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        tmp.set(cx - s * 0.025f, cy - s * 0.03f, cx + s * 0.03f, cy + s * 0.11f); c.drawOval(tmp, fill)
        // sombra de la punta y aletas
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy + s * 0.125f, s * 0.06f, Color.argb(110, 25, 42, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + s * 0.125f, s * 0.06f, fill); fill.shader = null
        stroke.color = Color.argb(140, 40, 60, 100); stroke.strokeWidth = s * 0.005f
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(cx + side * s * 0.022f, cy + s * 0.07f)
            path.quadTo(cx + side * s * 0.055f, cy + s * 0.11f, cx + side * s * 0.03f, cy + s * 0.135f)
            c.drawPath(path, stroke)
            fill.color = Color.argb(150, 16, 26, 52)
            tmp.set(cx + side * s * 0.024f - s * 0.011f, cy + s * 0.128f, cx + side * s * 0.024f + s * 0.011f, cy + s * 0.14f)
            c.drawOval(tmp, fill)
        }
    }

    private fun drawMouth(c: Canvas, mx: Float, my: Float, s: Float) {
        val mw = s * 0.085f * eMw                                   // 🎭 (más ancha al sonreír, más chica al asombrarse)
        // 🎭 boca: lo que pide el gesto + lo que se abre al hablar
        val opening = (eOpen * 1.15f + mouth * (1f - eOpen * 0.5f)).coerceIn(0f, 1.4f)
        val open = opening * s * (0.045f + eOpen * 0.02f)   // (👄 como al principio; abre harto solo en los gestos fuertes)
        val cd = -eSmile * s * 0.028f                                // comisuras: arriba al sonreír, abajo al estar triste
        val lx = mx - mw; val rx = mx + mw; val ky = my + cd
        // boca abierta (oscura, con dientes y lengua)
        if (open > s * 0.002f) {
            path.reset()
            path.moveTo(mx - mw * 0.92f, ky)
            path.cubicTo(mx - mw * 0.5f, my - open * 0.25f + cd * 0.2f, mx + mw * 0.5f, my - open * 0.25f + cd * 0.2f, mx + mw * 0.92f, ky)
            path.cubicTo(mx + mw * 0.5f, my + open * 1.1f - cd * 0.6f, mx - mw * 0.5f, my + open * 1.1f - cd * 0.6f, mx - mw * 0.92f, ky)
            path.close()
            fill.color = Color.rgb(20, 12, 30); c.drawPath(path, fill)
            if (opening > 0.25f || eTeeth > 0.3f) {
                c.save(); c.clipPath(path)
                // dientes de arriba (y de abajo si el gesto los enseña: enojo, asco, risa)
                fill.color = Color.argb(185, 225, 232, 245)
                tmp.set(mx - mw * 0.66f, my - open * 0.6f, mx + mw * 0.66f, my + open * 0.24f); c.drawOval(tmp, fill)
                if (eTeeth > 0.3f) {
                    fill.color = Color.argb((eTeeth * 160).toInt().coerceIn(0, 255), 215, 222, 238)
                    tmp.set(mx - mw * 0.6f, my + open * 0.75f - cd * 0.5f, mx + mw * 0.6f, my + open * 1.3f); c.drawOval(tmp, fill)
                } else {
                    // la lengua, abajo
                    fill.color = Color.argb(150, 120, 60, 90)
                    tmp.set(mx - mw * 0.5f, my + open * 0.55f, mx + mw * 0.5f, my + open * 1.4f); c.drawOval(tmp, fill)
                }
                c.restore()
            }
        }
        // labio de arriba (con el arco de cupido)
        path.reset()
        path.moveTo(lx, ky)
        path.cubicTo(mx - mw * 0.6f, my - s * 0.016f + cd * 0.5f, mx - mw * 0.25f, my - s * 0.026f, mx, my - s * 0.016f)
        path.cubicTo(mx + mw * 0.25f, my - s * 0.026f, mx + mw * 0.6f, my - s * 0.016f + cd * 0.5f, rx, ky)
        path.cubicTo(mx + mw * 0.5f, my - open * 0.25f + s * 0.002f + cd * 0.2f, mx - mw * 0.5f, my - open * 0.25f + s * 0.002f + cd * 0.2f, lx, ky)
        path.close()
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, my - s * 0.026f, 0f, my, Color.rgb(120, 130, 172), Color.rgb(78, 86, 128), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        // labio de abajo (más lleno, baja con la mandíbula)
        val ly = my + open - cd * 0.5f
        path.reset()
        path.moveTo(mx - mw * 0.95f, ky + open * 0.05f)
        path.cubicTo(mx - mw * 0.5f, ly + open * 0.1f, mx + mw * 0.5f, ly + open * 0.1f, mx + mw * 0.95f, ky + open * 0.05f)
        path.cubicTo(mx + mw * 0.55f, ly + s * 0.03f, mx - mw * 0.55f, ly + s * 0.03f, mx - mw * 0.95f, ky + open * 0.05f)
        path.close()
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ly, 0f, ly + s * 0.03f, Color.rgb(132, 140, 186), Color.rgb(84, 92, 136), Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        // brillo del labio de abajo y comisuras
        fill.color = Color.argb(70, 255, 255, 255)
        tmp.set(mx - mw * 0.3f, ly + s * 0.008f, mx + mw * 0.3f, ly + s * 0.016f); c.drawOval(tmp, fill)
        stroke.color = Color.argb(120, 30, 40, 70); stroke.strokeWidth = s * 0.004f
        c.drawPoint(lx, ky, stroke); c.drawPoint(rx, ky, stroke)
        // 🎭 pliegues de la sonrisa (mejillas) o de la tristeza (comisuras hacia abajo)
        if (abs(eSmile) > 0.15f) {
            stroke.strokeWidth = s * 0.004f
            stroke.color = Color.argb(((abs(eSmile) - 0.15f) * 140).toInt().coerceIn(0, 110), 40, 60, 100)
            for (side in intArrayOf(-1, 1)) {
                path.reset()
                val px = mx + side * mw
                if (eSmile > 0) { path.moveTo(px + side * s * 0.01f, ky - s * 0.07f); path.quadTo(px + side * s * 0.035f, ky - s * 0.02f, px + side * s * 0.012f, ky + s * 0.015f) }
                else { path.moveTo(px, ky + s * 0.004f); path.quadTo(px + side * s * 0.01f, ky + s * 0.02f, px + side * s * 0.004f, ky + s * 0.04f) }
                c.drawPath(path, stroke)
            }
        }
        // sombra bajo el labio (el mentón)
        tmp.set(mx - s * 0.045f, ly + s * 0.022f, mx + s * 0.045f, ly + s * 0.045f)
        fill.color = Color.BLACK; fill.shader = RadialGradient(mx, ly + s * 0.033f, s * 0.04f, Color.argb(40, 25, 40, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawOval(tmp, fill); fill.shader = null
    }
}

/**
 * 🧑‍🚀 El avatar como FONDO del chat: queda detrás de los mensajes, botones y menús laterales,
 * así todo lo que está encima se puede tocar y deslizar normal. Se redibuja solo (~60 por segundo).
 */
class AvatarDrawable(context: Context) : android.graphics.drawable.Drawable() {
    private val face = AvatarView(context).apply { drawOnly = true }
    /** Fracción del alto que usa la cara (1 = todo el chat, 0.45 = mitad de arriba con «Chat en pantalla»). */
    var heightFraction = 1f
        set(v) { field = v; invalidateSelf() }
    private val tick = Runnable { invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width(); val h = (b.height() * heightFraction).toInt()
        if (w <= 0 || h <= 0) return
        if (face.width != w || face.height != h) face.layout(0, 0, w, h)
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.clipRect(0, 0, w, h)
        face.render(canvas)
        canvas.restoreToCount(save)
        unscheduleSelf(tick)
        scheduleSelf(tick, SystemClock.uptimeMillis() + 16)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

