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
    /** 👂 (27-nov-2026) Última vez que el micrófono oyó TU voz (no la de la IA): el avatar te escucha y reacciona. */
    @Volatile var userTalkAt = 0L
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

    /**
     * 👄 (voz de Android) Hasta cuándo suena la palabra que está diciendo. Después la boca se CIERRA hasta la palabra
     * siguiente: así en las pausas («Hola, soy Pablo. … Estoy aquí…») la boca también hace la pausa, siguiendo la voz.
     */
    @Volatile var wordUntil = 0L
    /** Empieza una palabra de [chars] letras; [pauseAfter] = después viene una coma, un punto, «?», «!»… (pausa). */
    fun pulseWord(chars: Int, pauseAfter: Boolean) {
        val now = SystemClock.uptimeMillis()
        pushAt(0.85f, now)
        // (~75 ms por letra a la velocidad normal; sin pausa después, un poquito más para unir con la palabra siguiente)
        wordUntil = now + (chars * 75L + (if (pauseAfter) 0L else 140L)).coerceIn(160L, 1100L)
    }

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
/**
 * 💥🔊 «Sonido de poder»: un sonido PROPIO (creado aquí en el código, sin archivos ni derechos de nadie) cuando el aura
 * explota al enojarse: un estruendo grave que sube, con zumbido eléctrico y chispazos, y se apaga junto con la explosión
 * (~1,6 s). Suena solo si el interruptor «Sonido de poder» está prendido y el avatar tiene aura; nunca mientras la IA habla.
 */
/**
 * ✨ (22-nov-2026) Interruptores de «Efectos especiales» (Ajustes) para el avatar PERSONALIZADO:
 * [aura] = el aura de energía detrás · [hair] = el cabello que se mueve como llamas. Prendidos por defecto.
 * (antes se apagaban solos si la foto era de una persona real; ahora los elige el usuario)
 */
object AvatarEffects {
    @Volatile var aura = true
    @Volatile var hair = true
    /** 🎨 (23-nov-2026) color del aura: dorada (como siempre), azul, roja, celeste, morada, rosada, lila o blanca */
    @Volatile var auraColor: String = "gold"
    /** ⚡🔥 (23-nov-2026) efecto del aura (uno a la vez, del color elegido): "" = ninguno · "lightning" = relámpago ·
     *  "flash" = destello (prende y apaga) · "pulse" = latente (como latidos de un corazón) · "fire" = de fuego */
    @Volatile var auraFx: String = ""
    /** 🖥️ (23-nov-2026) Frecuencia del avatar: 60 = «Avatar estándar a 60 Hz» (por defecto: menos batería y calor)
     *  · 120 = «Avatar adaptable a 120 Hz» (se dibuja en cada refresco de la pantalla, como antes). */
    @Volatile var maxFps: Int = 60
    /** 🤓 (27-nov-2026) Avatar de Cumulus: 1 = «Avatar 1 (tradicional)» (por defecto) · 2 = «Avatar 2 (intelectual)».
     *  Vale cuando no hay una imagen propia de avatar puesta. */
    @Volatile var style: Int = 1
    /** 😴 (23-nov-2026) La última vez que tocaste la pantalla (para el modo descanso del avatar). */
    @Volatile var touchAt: Long = 0L
    /** 👁️ La pantalla de chat dice si la visión (cámara) está prendida: mientras lo esté, el avatar no descansa. */
    @Volatile var visionSource: (() -> Boolean)? = null
    /** 🖥️ (23-nov-2026) Avisa a la pantalla de chat cuando el avatar cambia de ritmo (30 en descanso · 60/120 activo),
     *  para pedirle al teléfono esos mismos Hz. */
    @Volatile var onFpsChange: ((Int) -> Unit)? = null

    /** El color (RGB) de cada aura. */
    fun rgb(key: String): Int = when (key) {
        "blue" -> android.graphics.Color.rgb(61, 107, 255)
        "red" -> android.graphics.Color.rgb(255, 40, 60)
        "cyan" -> android.graphics.Color.rgb(64, 200, 255)
        "purple" -> android.graphics.Color.rgb(150, 60, 255)
        "pink" -> android.graphics.Color.rgb(255, 90, 170)
        "lilac" -> android.graphics.Color.rgb(200, 160, 255)
        "white" -> android.graphics.Color.rgb(255, 255, 255)
        else -> android.graphics.Color.rgb(255, 214, 90)   // dorada
    }
}

object PowerSound {
    @Volatile var enabled = true
    private const val RATE = 22050
    private val pcm: ShortArray by lazy { build() }
    private var track: android.media.AudioTrack? = null

    private fun build(): ShortArray {
        val n = (RATE * 1.6f).toInt()
        val out = ShortArray(n)
        val rnd = java.util.Random(7)
        var lp = 0.0; var lp2 = 0.0; var crack = 0.0; var ph = 0.0; var ph2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = if (t < 0.10) t / 0.10 else Math.exp(-(t - 0.10) * 2.4)
            // estruendo grave que sube de tono al explotar
            val f = 48.0 + 46.0 * Math.min(t, 0.35) / 0.35
            ph += 2 * Math.PI * f / RATE
            val low = Math.sin(ph) * 0.55 + Math.sin(ph * 2.01) * 0.18
            // ruido de «viento de poder» (filtrado)
            val nz = rnd.nextDouble() * 2 - 1
            lp += (nz - lp) * 0.06; lp2 += (lp - lp2) * 0.25
            // zumbido eléctrico (vibra rápido)
            ph2 += 2 * Math.PI * (180.0 + 25.0 * Math.sin(2 * Math.PI * 32.0 * t)) / RATE
            val hum = Math.sin(ph2) * 0.10 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 9.0 * t))
            // chispazos
            if (rnd.nextDouble() < 0.0035 * env) crack = if (rnd.nextBoolean()) 0.8 else -0.8
            crack *= 0.82
            val v = (low + lp2 * 2.6 + hum + crack * 0.45) * env * 0.85
            out[i] = (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
        }
        return out
    }

    /** Suena una vez (en segundo plano). [strong] = furia: un poco más fuerte. */
    fun play(strong: Boolean) {
        if (!enabled || AvatarVoice.speaking) return
        Thread {
            runCatching {
                val data = pcm
                track?.let { runCatching { it.stop(); it.release() } }
                val t = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                t.write(data, 0, data.size)
                t.setVolume(if (strong) 1.0f else 0.8f)
                t.play()
                track = t
            }
        }.start()
    }
}

/**
 * 🔊 (23-nov-2026) Sonidos PROPIOS para cuando el aura explota al enojarse (creados aquí en el código, sin archivos ni
 * derechos de nadie), cada uno con su interruptor en Efectos especiales (apagados por defecto). Suenan junto con el
 * «Sonido de poder» si también está prendido; nunca mientras la IA habla. Se reproducen igual que el Sonido de poder.
 */
private object EffectSoundPlayer {
    const val RATE = 22050

    /** Deja el sonido a buen volumen (el punto más fuerte al 92%). */
    fun toPcm(v: DoubleArray): ShortArray {
        var pk = 1e-9
        for (x in v) pk = Math.max(pk, Math.abs(x))
        return ShortArray(v.size) { i -> (v[i] / pk * 0.92 * 32000).toInt().coerceIn(-32767, 32767).toShort() }
    }

    fun play(data: ShortArray, strong: Boolean, old: android.media.AudioTrack?): android.media.AudioTrack? {
        old?.let { runCatching { it.stop(); it.release() } }
        return runCatching {
            val t = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.setVolume(if (strong) 1.0f else 0.8f)
            t.play()
            t
        }.getOrNull()
    }
}

/** ⛈️ «Sonido de trueno»: un chasquido fuerte (el rayo) y después un trueno grave que retumba y se aleja (~2,8 s). */
object ThunderSound {
    @Volatile var enabled = false
    private val pcm: ShortArray by lazy { build() }
    private var track: android.media.AudioTrack? = null

    private fun build(): ShortArray {
        val rate = EffectSoundPlayer.RATE
        val n = (rate * 2.8).toInt()
        val out = DoubleArray(n)
        val r = java.util.Random(11)
        var lp = 0.0; var lp2 = 0.0; var lp3 = 0.0; var hpPrev = 0.0; var hp = 0.0
        var roll = 0.0; var rollT = 0.0; var crk = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val nz = r.nextDouble() * 2 - 1
            // ⚡ el chasquido del rayo (agudo, muy corto) y un segundo chasquido
            hp = 0.6 * (hp + nz - hpPrev); hpPrev = nz
            var crackEnv = if (t > 0.004) Math.exp(-t * 18.0) else t / 0.004
            if (t > 0.12) crackEnv += 0.6 * Math.exp(-(t - 0.12) * 22.0)
            val crack = hp * crackEnv * 0.9
            // 🌩️ el retumbar: ruido grave que sube y baja (rueda) y se va apagando
            lp += (nz - lp) * 0.08; lp2 += (lp - lp2) * 0.08; lp3 += (lp2 - lp3) * 0.12
            if (t >= rollT) { rollT = t + 0.12 + r.nextDouble() * 0.35; roll = 0.45 + r.nextDouble() * 0.55 }
            crk += (roll - crk) * 0.0012
            val env = Math.min(1.0, t / 0.06) * Math.exp(-t * 0.85)
            val rumble = lp3 * 26 * crk * env + lp * 2.0 * env * Math.exp(-t * 3)
            out[i] = crack + rumble
        }
        return EffectSoundPlayer.toPcm(out)
    }

    /** Suena una vez (en segundo plano). [strong] = furia: un poco más fuerte. */
    fun play(strong: Boolean) {
        if (!enabled || AvatarVoice.speaking) return
        Thread { runCatching { track = EffectSoundPlayer.play(pcm, strong, track) } }.start()
    }
}

/** ⚡ «Sonido de electricidad»: zumbido eléctrico con chisporroteo y chispazos (~1,5 s). */
object ElectricSound {
    @Volatile var enabled = false
    private val pcm: ShortArray by lazy { build() }
    private var track: android.media.AudioTrack? = null

    private fun build(): ShortArray {
        val rate = EffectSoundPlayer.RATE
        val n = (rate * 1.5).toInt()
        val out = DoubleArray(n)
        val r = java.util.Random(5)
        var ph = 0.0; var hp = 0.0; var hpPrev = 0.0
        var gate = 0.0; var gateT = 0.0; var spark = 0.0; var sparkLen = 0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = Math.min(1.0, t / 0.03) * (if (t < 0.9) 1.0 else Math.exp(-(t - 0.9) * 6))
            // zumbido (vibra como un cable con corriente)
            val f = 110 + 8 * Math.sin(2 * Math.PI * 7 * t)
            ph += 2 * Math.PI * f / rate
            val s = Math.sin(ph)
            val buzz = (if (s > 0) 1.0 else -1.0) * 0.18 + Math.sin(ph * 3) * 0.10 + Math.sin(ph * 5) * 0.06
            // chisporroteo (agudo, se prende y apaga al azar)
            val nz = r.nextDouble() * 2 - 1
            hp = 0.85 * (hp + nz - hpPrev); hpPrev = nz
            if (t >= gateT) { gateT = t + 0.01 + r.nextDouble() * 0.06; val g = r.nextDouble(); gate = g * g }
            val sizzle = hp * 0.35 * gate
            // chispazos
            if (sparkLen <= 0 && r.nextDouble() < 0.0025) { sparkLen = (rate * (0.004 + r.nextDouble() * 0.012)).toInt(); spark = 0.9 + r.nextDouble() * 0.6 }
            var sp = 0.0
            if (sparkLen > 0) { sp = nz * spark; sparkLen--; spark *= 0.995 }
            out[i] = (buzz * (0.6 + 0.4 * gate) + sizzle + sp * 0.7) * env
        }
        return EffectSoundPlayer.toPcm(out)
    }

    /** Suena una vez (en segundo plano). [strong] = furia: un poco más fuerte. */
    fun play(strong: Boolean) {
        if (!enabled || AvatarVoice.speaking) return
        Thread { runCatching { track = EffectSoundPlayer.play(pcm, strong, track) } }.start()
    }
}

/**
 * 😉 GESTOS A PEDIDO (20-nov-2026), por voz o por escrito, en los 14 idiomas (además de sus gestos propios), igual que
 * «mira arriba»: «guíñame» / «cierra un ojo» (cierra un ojo 1 s y lo abre solo), «cierra los ojos», «sonríeme»,
 * «dame / regálame una risa» o «ríete», «levanta las cejas» (las dos) y «levanta una ceja» (una arriba, la otra abajo).
 */
object AvatarGesture {
    @Volatile var winkUntil = 0L; @Volatile var winkSide = 1      // (1 = ojo de la derecha de la pantalla, −1 izquierda, 0 los dos)
    @Volatile var browUntil = 0L; @Volatile var browOne = false   // (false = las dos cejas arriba; true = una arriba y la otra abajo)
    @Volatile var browSide = 1
    /** 👋 (27-nov-2026) Avatar 2: hasta cuándo levanta el brazo y te saluda con la mano. */
    @Volatile var waveUntil = 0L
    private var flip = 1
    // 🔁 (20-nov-2026) el gesto se repite cuando la IA empieza a responder («¡Listo! 😉»): antes lo hacía solo al
    //    pedírselo y, como la respuesta llega unos segundos después, parecía que no lo había hecho
    @Volatile private var lastKind = 0; @Volatile private var lastAt = 0L; @Volatile private var replayed = true

    private fun remember(kind: Int) { lastKind = kind; lastAt = SystemClock.uptimeMillis(); replayed = false }

    /** Lo llama la voz al empezar a responder: repite el gesto que le pediste (una vez). */
    fun replay() {
        val now = SystemClock.uptimeMillis()
        if (replayed || now - lastAt > 60_000L) return
        replayed = true
        when (lastKind) {
            1 -> winkUntil = now + 1000L                       // guiño (el mismo ojo)
            2 -> { winkSide = 0; winkUntil = now + 1000L }      // los dos ojos
            3 -> browUntil = now + 2000L                        // cejas
        }
    }

    private fun fold(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun rx(s: String) = Regex("(?<![\\p{L}])($s)(?![\\p{L}])")

    // (20-nov-2026: también como se escribe rápido o lo entiende el micrófono: «giñame», «sierra un ojo», «cierra me un ojo»)
    private val WINK = rx("g(u)?ina( ?me| ?le)?|g(u)?ino|g(u)?inar|g(u)?inate|[cs]ierra( ?me)? (un|1) ojo|[cs]ierrame (un|1) ojo|[cs]ierra el ojo|" +
        "[cs]ierra un solo ojo|wink( at me)?|close one eye|" +
        "pisca(r)?( o olho| um olho)?|piscadinha|feche um olho|clin d['’]oeil|ferme un oeil|zwinker\\p{L}*|schlie(ss|ß) ein auge|" +
        "occhiolino|chiudi un occhio|kedip\\p{L}*|tutup satu mata|nhay mat|nham mot mat|подмигни\\p{L}*|закрои один глаз")
    private val WINK_RAW = Regex("眨眼|眨个眼|眨個眼|闭一只眼|閉一隻眼|ウインク|ウィンク|片目を閉じ|윙크|한쪽 눈.*감|ขยิบตา|หลับตาข้างเดียว|غمز|اغمز")
    private val CLOSE_BOTH = rx("[cs]ierra( ?me)? (los|tus) (dos |2 )?ojos|[cs]ierrame los ojos|[cs]ierra los dos|close (your|both) eyes|fecha os olhos|ferme les yeux|" +
        "schlie(ss|ß) die augen|chiudi gli occhi|tutup mata(mu)?|nham mat|закрои глаза")
    private val CLOSE_BOTH_RAW = Regex("闭上眼|閉上眼|目を閉じ|눈을 감|눈 감아|หลับตา(?!ข้าง)|أغمض عينيك|اغمض عينيك")
    private val LAUGH = rx("riete|rie te|rieme|reite|(da ?me|regala ?me|haz ?me|tirate|echate|regalanos) una (risa|risita|carcajada)|una risita|una carcajada|carcajada|" +
        "laugh( for me)?|give me a laugh|ria pra mim|da uma risada|risada|rigole|ris un peu|lach mal|lachen|ridi|fatti una risata|" +
        "tertawa\\p{L}*|ketawa\\p{L}*|cuoi lon|cuoi to|засмеися|посмеися|рассмеися")
    private val LAUGH_RAW = Regex("笑一笑|大笑|哈哈笑|笑って|爆笑|웃어|หัวเราะ|اضحك")
    private val SMILE = rx("sonrie( ?me| ?le)?|sonreir|sonriete|(da ?me|regala ?me|haz ?me|regalanos) una sonrisa|una sonrisa|una sonrisita|smile( at me| for me)?|sorria|sorri pra mim|" +
        "souris[- ]moi|fais[- ]moi un sourire|un sourire|lachel\\p{L}*|lachle|sorridi|sorrisino|senyum\\p{L}*|cuoi (len|di|voi toi)|cuoi|улыбни\\p{L}*")
    private val SMILE_RAW = Regex("微笑|笑一个|笑一個|ほほえ|微笑ん|笑顔|미소|ยิ้ม|ابتسم")
    private val BROW_WORD = rx("[cs]ejas?|[cs]ejitas?|eyebrows?|brows?|sobrancelhas?|sourcils?|augenbrauen?|sopraccigli[oa]|alis|nhuong may|nhuong mot ben may|бров\\p{L}*")
    private val BROW_RAW = Regex("眉|눈썹|คิ้ว|حاجب")
    private val RAISE = rx("levanta|levanta ?me|levantar|sube|sube ?me|subir|alza|alzar|arquea|mueve|raise|lift|levante|leve|hebe?|alza|solleva|angkat|nhuong|подними|поднять")
    private val RAISE_RAW = Regex("挑|扬|揚|抬|上げ|올려|เลิก|ارفع")
    private val ONE = rx("una|1|one|uma|un|eine|uno|satu|mot|одну|una sola|solo una")
    private val ONE_RAW = Regex("一边|一邊|一下眉|片眉|한쪽|ข้างเดียว|واحد")

    // 👋 (27-nov-2026) Saludos y despedidas en los 14 idiomas: el avatar 2 te saluda con la mano (no cambia nada más)
    private val GREET = rx("hola|holi|holis|buenas|buen dia|buenos dias|buenas tardes|buenas noches|saludos|saluda( ?me| ?nos)?|chao|chau|adios|nos vemos|hasta luego|" +
        "hi|hello|hey|good (morning|afternoon|evening)|bye|goodbye|wave( at me)?|ola|oi|bom dia|boa tarde|boa noite|tchau|bonjour|salut|bonsoir|coucou|au revoir|" +
        "hallo|guten (morgen|tag|abend)|tschuss|servus|ciao|buongiorno|buonasera|salve|arrivederci|halo|hai|selamat (pagi|siang|sore|malam)|sampai jumpa|" +
        "xin chao|tam biet|привет\\p{L}*|здравству\\p{L}*|добр\\p{L}* (утро|день|вечер)|пока")
    private val GREET_RAW = Regex("你好|您好|嗨|早上好|晚上好|再见|再見|こんにちは|こんばんは|おはよう|さようなら|やあ|안녕|สวัสดี|ลาก่อน|مرحبا|السلام عليكم|أهلا|اهلا|مع السلامة")

    /** Lee lo que dijiste o escribiste: si pide un gesto, lo hace y devuelve true. */
    fun fromUser(text: String): Boolean {
        val t = fold(text); val r = text.lowercase()
        val now = SystemClock.uptimeMillis()
        if (GREET.containsMatchIn(t) || GREET_RAW.containsMatchIn(r)) waveUntil = now + 3000L   // 👋 (avatar 2)
        // 🤨 cejas (una o las dos)
        if ((BROW_WORD.containsMatchIn(t) || BROW_RAW.containsMatchIn(r)) && (RAISE.containsMatchIn(t) || RAISE_RAW.containsMatchIn(r))) {
            browOne = ONE.containsMatchIn(t) || ONE_RAW.containsMatchIn(r)
            flip = -flip; browSide = flip
            browUntil = now + 2000L
            remember(3)
            return true
        }
        // 😌 cerrar los dos ojos (1 s)
        if (CLOSE_BOTH.containsMatchIn(t) || CLOSE_BOTH_RAW.containsMatchIn(r)) { winkSide = 0; winkUntil = now + 1000L; remember(2); return true }
        // 😉 guiño: un ojo 1 s y lo abre solo
        if (WINK.containsMatchIn(t) || WINK_RAW.containsMatchIn(r)) { flip = -flip; winkSide = flip; winkUntil = now + 1000L; remember(1); return true }
        // 😂 risa / 🙂 sonrisa
        // (se mantiene también mientras te responde, como cuando reacciona a tu mensaje)
        if (LAUGH.containsMatchIn(t) || LAUGH_RAW.containsMatchIn(r)) { AvatarMood.holdForUser(AvatarMood.LAUGHTER); return true }
        if (SMILE.containsMatchIn(t) || SMILE_RAW.containsMatchIn(r)) { AvatarMood.holdForUser(AvatarMood.JOY); return true }
        return false
    }
}

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

    /** 😉 Gesto pedido («sonríeme», «ríete»): al tiro y también mientras te responde. */
    fun holdForUser(expr: Int) {
        val now = SystemClock.uptimeMillis()
        current = expr; until = now + 2500L; userHoldUntil = now + 15_000L
    }

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
        return a in TAGS || a == "mirar" || a == "look" || a == "emocion" || a == "emotion" || garbledTag(m.groupValues[1], a)
    }

    /**
     * (19-nov-2026) Etiqueta de emoción mal escrita por la IA, como «[aleguridad]» (mezcló «alegría» y «seguridad»):
     * una sola palabra en minúsculas que empieza igual que una etiqueta conocida. También se quita (antes se veía).
     */
    private fun garbledTag(raw: String, a: String): Boolean =
        a.length in 4..16 && raw.none { it.isUpperCase() } &&
            TAGS.keys.any { k -> k.length >= 4 && a.take(3) == k.take(3) }

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
        runCatching { AvatarGesture.replay() }   // 😉 (repite el gesto que le pediste, al responder)
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
    /**
     * 👁️ Avatar visión: hacia dónde estás TÚ (lo que ve la cámara delantera), para seguirte con la mirada.
     * Se usa mientras [followUntil] no pasa y no hay una orden de mirar («mira arriba») en curso.
     */
    @Volatile var followYaw = 0f
    @Volatile var followPitch = 0f
    @Volatile var followUntil = 0L
    /** 🤔 (27-nov-2026) Hasta cuándo está pensando lo que le pediste (el avatar 2 pone el dedo en el mentón). */
    @Volatile var thinkUntil = 0L
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
    class EyeShape(val poly: FloatArray, val xs: FloatArray, val top: FloatArray, val bot: FloatArray, val skin: Int, val line: Int) {
        /**
         * 👁️ El IRIS que se mueve (como el avatar original): [iris] = solo el iris/pupila del personaje, [hole] = el
         * mismo lugar pintado del color del blanco del ojo (para que al moverse el iris no quede repetido).
         * Ambos empiezan en ([irisX], [irisY]) de la imagen. null = no se encontró un iris (el ojo queda quieto).
         */
        var iris: android.graphics.Bitmap? = null
        var hole: android.graphics.Bitmap? = null
        var irisX = 0f; var irisY = 0f
        /** El iris completo empieza un poco más arriba que el hueco (cuando se completó la parte tapada). */
        var irisDy = 0f
        /**
         * Inclinación del ojo (cuánto baja su línea del medio por cada píxel hacia la derecha). Al mirar a un lado el iris
         * sigue esa línea, como en el anime: los dos ojos miran parejo al mismo lado (antes, en ojos inclinados como los
         * de Vegeta, uno parecía mirar arriba y el otro abajo).
         */
        var slope = 0f
        /** Cuánto puede moverse el iris (en píxeles de la imagen) a los lados y arriba/abajo. */
        var moveX = 0f; var moveY = 0f
        /** Dónde se corta el ojo por arriba (parte del alto del ojo): 0,06 normal; más si el iris venía «pegado arriba». */
        var clipTop = 0.06f

        // 🧑 (23-nov-2026) PERSONA REAL: el iris redondo de la foto (para moverlo) y el ojo SIN iris (el blanco del ojo
        //    real en su lugar). Se arman la primera vez que se dibujan. (Solo fotos reales: el anime no los usa.)
        var realTried = false
        var realBase: android.graphics.Bitmap? = null
        var realIris: android.graphics.Bitmap? = null
        var realX = 0f; var realY = 0f      // (dónde empiezan los dos en la imagen)
        var realR = 0f                      // (radio del iris)
        var realCx = 0f                     // (centro del iris, de lado a lado: para que al moverse no se salga del ojo)
        var realCy = 0f                     // (centro del iris, de arriba a abajo)
        // 👁️ el color del párpado para el parpadeo: la piel clara de alrededor del ojo (sin pestañas, ceja ni sombras)
        var lidTried = false
        var lidOk = false
        var lidColor = 0
    }
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

    /**
     * 🎨 El color que más destaca del personaje: se miran los colores FUERA de la cara (cabello, ropa, accesorios),
     * se agrupan por tono y gana el que más se ve, dando más peso a los colores vivos y brillantes.
     * Después se aviva un poco para que los puntitos brillen en el fondo oscuro.
     */
    private fun standoutColor(bmp: android.graphics.Bitmap, pts: FloatArray): Int {
        val w = bmp.width; val h = bmp.height
        val fx = (pts[0] + pts[2] + pts[4]) / 3f * w; val fy = (pts[1] + pts[3] + pts[5]) / 3f * h
        val d = kotlin.math.hypot(((pts[2] - pts[0]) * w).toDouble(), ((pts[3] - pts[1]) * h).toDouble()).toFloat().coerceAtLeast(4f)
        val bins = 24
        val weight = FloatArray(bins); val sumR = FloatArray(bins); val sumG = FloatArray(bins); val sumB = FloatArray(bins)
        val hsv = FloatArray(3)
        val step = maxOf(1, maxOf(w, h) / 160)
        for (y in 0 until h step step) for (x in 0 until w step step) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            // (la cara no cuenta: es piel)
            val ex = (x - fx) / (d * 1.15f); val ey = (y - fy) / (d * 1.35f)
            if (ex * ex + ey * ey < 1f) continue
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.25f || hsv[1] < 0.18f) continue          // (negro, gris y blanco no destacan)
            val b = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            val wgt = hsv[1] * hsv[2]
            weight[b] += wgt; sumR[b] += Color.red(c) * wgt; sumG[b] += Color.green(c) * wgt; sumB[b] += Color.blue(c) * wgt
        }
        // (el tono ganador y sus vecinos, para no partir un color justo en el borde de un grupo)
        var best = -1; var bestW = 0f
        for (i in 0 until bins) {
            val tw = weight[i] + 0.5f * (weight[(i + 1) % bins] + weight[(i + bins - 1) % bins])
            if (tw > bestW) { bestW = tw; best = i }
        }
        if (best < 0 || weight[best] <= 0f) return Color.rgb(70, 150, 255)
        val base = Color.rgb((sumR[best] / weight[best]).toInt().coerceIn(0, 255), (sumG[best] / weight[best]).toInt().coerceIn(0, 255),
            (sumB[best] / weight[best]).toInt().coerceIn(0, 255))
        Color.colorToHSV(base, hsv)
        hsv[1] = max(hsv[1], 0.55f); hsv[2] = max(hsv[2], 0.92f)
        return Color.HSVToColor(hsv)
    }

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
        var skin = sorted.getOrNull((sorted.size * 0.7f).toInt()) ?: Color.rgb(200, 170, 150)
        // 🧑 PERSONA REAL (foto): la piel no es de un solo color (sombras, pestañas, ojeras), así que el color de arriba
        //    sale mal. Si la piel alrededor del ojo NO es pareja (en anime sí lo es y queda igual que antes), el párpado
        //    toma el color de la PIEL de la persona: lo de color piel del párpado y de la mejilla, el tono del medio.
        val flat = sorted.count {
            abs(Color.red(it) - Color.red(skin)) + abs(Color.green(it) - Color.green(skin)) + abs(Color.blue(it) - Color.blue(skin)) <= 24
        } >= sorted.size * 0.5f
        if (!flat) runCatching { realSkin(bmp, xs, top, bot, x1 - x0) }.getOrNull()?.let { skin = it }
        // la línea de las pestañas: lo más oscuro del borde del ojo (en anime, el trazo negro)
        val edge = ArrayList<Int>()
        for (i in 0 until n) edge += bmp.getPixel(poly[i * 2].toInt().coerceIn(0, bmp.width - 1), poly[i * 2 + 1].toInt().coerceIn(0, bmp.height - 1))
        val line = edge.minByOrNull { lum(it) }?.let { if (lum(it) < 90) it else Color.rgb(30, 20, 24) } ?: Color.rgb(30, 20, 24)
        return EyeShape(poly, xs, top, bot, Color.rgb(Color.red(skin), Color.green(skin), Color.blue(skin)), line).also {
            runCatching { findIris(bmp, it, x0, x1) }
        }
    }

    /**
     * 🧑 Color de la piel de una persona real (foto) para el párpado: se miran el párpado (entre el ojo y la ceja, sin
     * llegar a la ceja) y la mejilla (bajo la ojera), se dejan solo los puntos con color de piel (ni cejas ni pestañas
     * oscuras, ni brillos blancos, ni el blanco del ojo) y se promedia el tono del medio. null = no hay suficiente piel.
     */
    private fun realSkin(bmp: android.graphics.Bitmap, xs: FloatArray, top: FloatArray, bot: FloatArray, ew: Float): Int? {
        val cols = xs.size
        val hsv = FloatArray(3)
        val pts = ArrayList<Int>()
        for (c in 2 until cols - 2 step 2) {
            val x = xs[c].toInt().coerceIn(0, bmp.width - 1)
            for (k in 0..3) {
                val yLid = (top[c] - ew * (0.10f + k * 0.04f)).toInt().coerceIn(0, bmp.height - 1)      // párpado
                val yCheek = (bot[c] + ew * (0.35f + k * 0.08f)).toInt().coerceIn(0, bmp.height - 1)   // mejilla
                pts += bmp.getPixel(x, yLid); pts += bmp.getPixel(x, yCheek)
            }
        }
        val skinLike = pts.filter { p ->
            if (Color.alpha(p) <= 128) return@filter false
            Color.colorToHSV(p, hsv)
            (hsv[0] <= 50f || hsv[0] >= 340f) && hsv[1] in 0.08f..0.75f && hsv[2] >= 0.22f && hsv[2] <= 0.98f
        }.sortedBy { lum(it) }
        if (skinLike.size < 8) return null
        // (el tono del medio: ni lo más oscuro, que es sombra, ni lo más claro, que es brillo)
        val mid = skinLike.subList((skinLike.size * 0.35f).toInt(), (skinLike.size * 0.75f).toInt().coerceAtLeast((skinLike.size * 0.35f).toInt() + 1))
        var r = 0; var g = 0; var b = 0
        for (p in mid) { r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
        return Color.rgb(r / mid.size, g / mid.size, b / mid.size)
    }

    /**
     * 👁️ Busca el iris dentro del ojo dibujado: lo oscuro (o de color) que hay en el blanco del ojo, sin tocar el trazo
     * del borde. Arma el iris suelto (para moverlo) y su «hueco» pintado de blanco del ojo.
     */
    private fun findIris(bmp: android.graphics.Bitmap, es: EyeShape, x0: Float, x1: Float) {
        val cols = es.xs.size
        val bx0 = x0.toInt().coerceIn(0, bmp.width - 1); val bx1 = kotlin.math.ceil(x1).toInt().coerceIn(0, bmp.width - 1)
        var ty0 = Float.MAX_VALUE; var ty1 = -Float.MAX_VALUE
        for (q in 0 until cols) { ty0 = min(ty0, es.top[q]); ty1 = max(ty1, es.bot[q]) }
        val by0 = ty0.toInt().coerceIn(0, bmp.height - 1); val by1 = kotlin.math.ceil(ty1).toInt().coerceIn(0, bmp.height - 1)
        val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
        if (bw < 6 || bh < 3) return
        val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, bx0, by0, bw, bh)
        // ¿está (x, y) dentro del ojo, lejos del trazo del borde? (margen = 14% del alto del ojo en esa columna)
        fun inside(x: Int, y: Int, margin: Float): Boolean {
            val fx = (bx0 + x + 0.5f - x0) / (x1 - x0) * cols - 0.5f
            val q = fx.toInt().coerceIn(0, cols - 1)
            val top = es.top[q]; val bot = es.bot[q]; val m = (bot - top) * margin
            val yy = by0 + y + 0.5f
            return yy > top + m && yy < bot - m
        }
        // el blanco del ojo: lo más claro de adentro
        val ins = ArrayList<Int>()
        for (y in 0 until bh) for (x in 0 until bw) if (inside(x, y, 0.1f) && Color.alpha(px[y * bw + x]) > 200) ins += px[y * bw + x]
        if (ins.size < 12) return
        val byLum = ins.sortedBy { lum(it) }
        val sclera = byLum[(byLum.size * 0.85f).toInt().coerceAtMost(byLum.size - 1)]
        val sl = lum(sclera)
        if (sl < 110f) return          // (ojo todo oscuro: no hay blanco donde mover el iris)
        fun far(c: Int): Boolean {
            val dr = Color.red(c) - Color.red(sclera); val dg = Color.green(c) - Color.green(sclera); val db = Color.blue(c) - Color.blue(sclera)
            return lum(c) < sl * 0.7f || (dr * dr + dg * dg + db * db) > 80 * 80
        }
        val mask = BooleanArray(bw * bh)
        var n = 0; var sx = 0f; var sy = 0f; var ix0 = bw; var ix1 = -1; var iy0 = bh; var iy1 = -1
        for (y in 0 until bh) for (x in 0 until bw) {
            val c = px[y * bw + x]
            if (Color.alpha(c) > 200 && inside(x, y, 0.08f) && far(c)) {
                mask[y * bw + x] = true; n++; sx += x; sy += y
                ix0 = min(ix0, x); ix1 = max(ix1, x); iy0 = min(iy0, y); iy1 = max(iy1, y)
            }
        }
        // 🧹 Limpieza: el trazo del borde del ojo (sobre todo en ojos inclinados, como los de Vegeta) dejaba franjas
        //    finitas a lo largo del borde que se unían al iris. Se «adelgaza» todo (las franjas finas desaparecen y el
        //    iris, que es grueso, queda), se toma el pedazo más grande (el iris) y se le devuelve su tamaño.
        val rawMask = mask.copyOf()
        run {
            val hs = FloatArray(cols) { es.bot[it] - es.top[it] }.sorted()
            val r = max(1, kotlin.math.round(hs[cols / 2] * 0.1f).toInt())
            // suma acumulada para contar rápido cuántos píxeles marcados hay en un cuadrado
            fun integral(m: BooleanArray): IntArray {
                val ii = IntArray((bw + 1) * (bh + 1))
                for (y in 0 until bh) { var row = 0
                    for (x in 0 until bw) { if (m[y * bw + x]) row++; ii[(y + 1) * (bw + 1) + x + 1] = ii[y * (bw + 1) + x + 1] + row } }
                return ii
            }
            fun boxSum(ii: IntArray, x: Int, y: Int, rr: Int): Int {
                val ax = max(0, x - rr); val ay = max(0, y - rr); val bx = min(bw, x + rr + 1); val by = min(bh, y + rr + 1)
                return ii[by * (bw + 1) + bx] - ii[ay * (bw + 1) + bx] - ii[by * (bw + 1) + ax] + ii[ay * (bw + 1) + ax]
            }
            val im = integral(mask)
            val full = (2 * r + 1) * (2 * r + 1)
            val ero = BooleanArray(bw * bh) { i -> mask[i] && boxSum(im, i % bw, i / bw, r) == full }
            // el pedazo más grande que quedó
            val lab = IntArray(bw * bh); var best = 0; var bestN = 0; var cur = 0
            val stack = IntArray(bw * bh)
            for (i0 in 0 until bw * bh) if (ero[i0] && lab[i0] == 0) {
                cur++; var sp = 0; stack[sp++] = i0; lab[i0] = cur; var cnt = 0
                while (sp > 0) {
                    val i = stack[--sp]; cnt++
                    val x = i % bw; val y = i / bw
                    if (x > 0 && ero[i - 1] && lab[i - 1] == 0) { lab[i - 1] = cur; stack[sp++] = i - 1 }
                    if (x < bw - 1 && ero[i + 1] && lab[i + 1] == 0) { lab[i + 1] = cur; stack[sp++] = i + 1 }
                    if (y > 0 && ero[i - bw] && lab[i - bw] == 0) { lab[i - bw] = cur; stack[sp++] = i - bw }
                    if (y < bh - 1 && ero[i + bw] && lab[i + bw] == 0) { lab[i + bw] = cur; stack[sp++] = i + bw }
                }
                if (cnt > bestN) { bestN = cnt; best = cur }
            }
            if (best == 0) { n = 0; return@run }
            val comp = BooleanArray(bw * bh) { lab[it] == best }
            val ic = integral(comp)
            n = 0; sx = 0f; sy = 0f; ix0 = bw; ix1 = -1; iy0 = bh; iy1 = -1
            for (i in 0 until bw * bh) {
                val keep = mask[i] && boxSum(ic, i % bw, i / bw, r + 1) > 0
                mask[i] = keep
                if (keep) { val x = i % bw; val y = i / bw; n++; sx += x; sy += y
                    ix0 = min(ix0, x); ix1 = max(ix1, x); iy0 = min(iy0, y); iy1 = max(iy1, y) }
            }
        }
        // 👁️ Ojos de anime distintos: si no quedó un iris razonable (por ejemplo una PUPILA CHIQUITA, un puntito negro
        //    en el ojo blanco, que el adelgazado borra), se busca entre los pedazos originales el más «redondito» y
        //    bien adentro del ojo (lejos del borde), que no sea tan ancho como el ojo.
        if (n < 4 || (ix1 - ix0 + 1) > (x1 - x0) * 0.62f) {
            val lab = IntArray(bw * bh); var cur = 0
            val stack = IntArray(bw * bh)
            var bestScore = 0f; var best = 0
            for (i0 in 0 until bw * bh) if (rawMask[i0] && lab[i0] == 0) {
                cur++; var sp = 0; stack[sp++] = i0; lab[i0] = cur
                var cnt = 0; var deep = 0; var cx0 = bw; var cx1 = -1; var cy0 = bh; var cy1 = -1
                while (sp > 0) {
                    val i = stack[--sp]; cnt++
                    val x = i % bw; val y = i / bw
                    if (inside(x, y, 0.2f)) deep++
                    cx0 = min(cx0, x); cx1 = max(cx1, x); cy0 = min(cy0, y); cy1 = max(cy1, y)
                    if (x > 0 && rawMask[i - 1] && lab[i - 1] == 0) { lab[i - 1] = cur; stack[sp++] = i - 1 }
                    if (x < bw - 1 && rawMask[i + 1] && lab[i + 1] == 0) { lab[i + 1] = cur; stack[sp++] = i + 1 }
                    if (y > 0 && rawMask[i - bw] && lab[i - bw] == 0) { lab[i - bw] = cur; stack[sp++] = i - bw }
                    if (y < bh - 1 && rawMask[i + bw] && lab[i + bw] == 0) { lab[i + bw] = cur; stack[sp++] = i + bw }
                }
                val cw = cx1 - cx0 + 1; val chh = cy1 - cy0 + 1
                val deepFrac = deep.toFloat() / cnt
                if (cnt < 3 || cw > (x1 - x0) * 0.5f || deepFrac < 0.5f) continue
                val compact = cnt.toFloat() / (cw * chh)
                val score = cnt * deepFrac * compact
                if (score > bestScore) { bestScore = score; best = cur }
            }
            if (best == 0) return
            n = 0; sx = 0f; sy = 0f; ix0 = bw; ix1 = -1; iy0 = bh; iy1 = -1
            for (i in 0 until bw * bh) {
                val keep = lab[i] == best
                mask[i] = keep
                if (keep) { val x = i % bw; val y = i / bw; n++; sx += x; sy += y
                    ix0 = min(ix0, x); ix1 = max(ix1, x); iy0 = min(iy0, y); iy1 = max(iy1, y) }
            }
        }
        // (muy poco = no hay iris; casi todo = el ojo no tiene blanco)
        if (n < 3 || n > ins.size * 0.85f) return
        // (un «iris» casi tan ancho como el ojo es el trazo del borde, no el iris: no se mueve)
        if ((ix1 - ix0 + 1) > (x1 - x0) * 0.62f) return
        // un borde de 1 píxel alrededor del iris también se mueve (para que no quede un halo)
        val grown = mask.copyOf()
        for (y in 0 until bh) for (x in 0 until bw) if (mask[y * bw + x]) {
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until bw && yy in 0 until bh && inside(xx, yy, 0.08f)) grown[yy * bw + xx] = true
            }
        }
        // 👁️ IRIS PEGADO ARRIBA (medio iris tapado por el párpado, como Vegeta): se completa la parte escondida (como
        //    un espejo de lo que se ve), así al moverse el ojo aparece ENTERO; y al volver a su lugar esa parte queda otra
        //    vez tapada por el párpado y se ve como al inicio, a la mitad.
        var extra = 0
        var colTop = IntArray(0); var colBot = IntArray(0)
        run {
            colTop = IntArray(bw) { -1 }; colBot = IntArray(bw) { -1 }
            var cut = 0; var withIris = 0
            for (x in 0 until bw) {
                var t = -1; var b = -1
                for (y in 0 until bh) if (mask[y * bw + x]) { if (t < 0) t = y; b = y }
                if (t < 0) continue
                colTop[x] = t; colBot[x] = b; withIris++
                // ¿lo de arriba del iris toca el borde de arriba del ojo en esta columna?
                val fx = (bx0 + x + 0.5f - x0) / (x1 - x0) * cols - 0.5f
                val q = fx.toInt().coerceIn(0, cols - 1)
                val edge = es.top[q] + (es.bot[q] - es.top[q]) * 0.14f
                if (by0 + t + 0.5f <= edge) cut++
            }
            if (withIris >= 2 && cut * 2 >= withIris) {
                for (x in 0 until bw) if (colTop[x] >= 0) extra = max(extra, colBot[x] - colTop[x] + 1)
            } else extra = 0
        }
        val fh = bh + extra
        val irisPx = IntArray(bw * fh); val holePx = IntArray(bw * bh)
        for (i in 0 until bw * bh) if (grown[i]) { irisPx[i + extra * bw] = px[i] or (0xFF shl 24); holePx[i] = sclera or (0xFF shl 24) }
        if (extra > 0) {
            // espejo hacia arriba de cada columna: lo que se ve del iris se refleja sobre su borde de arriba
            for (x in 0 until bw) {
                val t = colTop[x]; val b = colBot[x]
                if (t < 0) continue
                for (k in 1..(b - t + 1)) {
                    val src = t + k - 1; val dst = t - k + extra
                    if (dst < 0 || src > b) break
                    if (mask[src * bw + x]) irisPx[dst * bw + x] = px[src * bw + x] or (0xFF shl 24)
                }
            }
            es.clipTop = 0.12f    // (el párpado tapa un poquito más: al quedar quieto se ve igual que antes)
        }
        es.iris = android.graphics.Bitmap.createBitmap(irisPx, bw, fh, android.graphics.Bitmap.Config.ARGB_8888)
        es.hole = android.graphics.Bitmap.createBitmap(holePx, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        es.irisX = bx0.toFloat(); es.irisY = by0.toFloat()
        es.irisDy = -extra.toFloat()
        // línea del medio del ojo: recta que mejor pasa por el centro de cada columna
        run {
            var sxx = 0.0; var sx1 = 0.0; var sy1 = 0.0; var sxy = 0.0
            for (q in 0 until cols) {
                val xq = es.xs[q].toDouble(); val yq = ((es.top[q] + es.bot[q]) / 2f).toDouble()
                sx1 += xq; sy1 += yq; sxx += xq * xq; sxy += xq * yq
            }
            val den = cols * sxx - sx1 * sx1
            es.slope = if (den > 1e-6) ((cols * sxy - sx1 * sy1) / den).toFloat().coerceIn(-1.5f, 1.5f) else 0f
        }
        // cuánto se puede mover: lo que queda de blanco a cada lado (sin pasarse del ojo)
        val ew = x1 - x0; val iw = (ix1 - ix0 + 1).toFloat(); val ih = (iy1 - iy0 + 1).toFloat()
        es.moveX = ((ew - iw) * 0.5f).coerceIn(0f, ew * 0.3f)
        es.moveY = ((ty1 - ty0 - ih) * 0.45f).coerceIn(0f, (ty1 - ty0) * 0.22f)
    }

    /**
     * 🧑 (23-nov-2026) PERSONA REAL: arma el IRIS que se mueve y el ojo SIN iris.
     *  • Busca el iris EXACTO: el círculo cuyo borde (la aureola) separa mejor el iris del blanco del ojo.
     *  • El iris: ese círculo de la foto con toda su aureola, con el borde suave.
     *  • El ojo sin iris: donde estaba el iris se pone el BLANCO DEL OJO de verdad, fila por fila, tomado de los dos lados
     *    del iris en esa misma fila (así tiene sus sombras y su tono). Nada de mancha gris.
     * Se llama una sola vez por ojo. Si no se puede, el ojo queda como estaba.
     */
    fun buildRealIris(bmp: android.graphics.Bitmap, es: EyeShape) {
        if (es.realTried) return
        es.realTried = true
        runCatching {
            if (bmp.isRecycled) return
            val cols = es.xs.size
            if (cols < 3) return
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var ty0 = Float.MAX_VALUE; var ty1 = -Float.MAX_VALUE
            for (q in 0 until cols) { x0 = min(x0, es.xs[q]); x1 = max(x1, es.xs[q]); ty0 = min(ty0, es.top[q]); ty1 = max(ty1, es.bot[q]) }
            val ew = x1 - x0; val eh = ty1 - ty0
            if (ew < 8f || eh < 3f) return
            val rMin = ew * 0.15f; val rMax = ew * 0.27f   // (un iris humano mide entre el 30% y el 54% del ancho del ojo)
            val mg = rMax * 1.5f + 2f
            val rx0 = (x0 - mg).toInt().coerceIn(0, bmp.width - 1)
            val rx1 = kotlin.math.ceil(x1 + mg).toInt().coerceIn(0, bmp.width - 1)
            val ry0 = (ty0 - mg).toInt().coerceIn(0, bmp.height - 1)
            val ry1 = kotlin.math.ceil(ty1 + mg).toInt().coerceIn(0, bmp.height - 1)
            val bw = rx1 - rx0 + 1; val bh = ry1 - ry0 + 1
            if (bw < 6 || bh < 4) return
            val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, rx0, ry0, bw, bh)
            // ¿(x, y) de la imagen está dentro del ojo? (margin = parte del alto que se deja en el borde)
            fun colOf(x: Float): Int = ((x - x0) / ew * cols - 0.5f).toInt().coerceIn(0, cols - 1)
            fun inside(x: Float, y: Float, margin: Float): Boolean {
                if (x < x0 || x > x1) return false
                val q = colOf(x); val m = (es.bot[q] - es.top[q]) * margin
                return y > es.top[q] + m && y < es.bot[q] - m
            }
            fun at(x: Int, y: Int): Int = px[(y - ry0).coerceIn(0, bh - 1) * bw + (x - rx0).coerceIn(0, bw - 1)]
            fun lumAt(x: Float, y: Float): Float = lum(at(x.toInt(), y.toInt()))
            // (dentro del ojo, y abajo un poquito más allá del borde: junto al párpado de abajo quedaban pedacitos del iris)
            fun insideLow(x: Float, y: Float): Boolean {
                if (x < x0 || x > x1) return false
                val q = colOf(x)
                return y > es.top[q] && y < es.bot[q] + (es.bot[q] - es.top[q]) * 0.1f
            }
            // el blanco del ojo en general (por si en alguna fila no hay de dónde tomarlo): entre lo de MENOS color
            // (no piel ni lagrimal) lo más claro
            val insPx = ArrayList<Int>()
            for (y in ry0..ry1) for (x in rx0..rx1) if (inside(x + 0.5f, y + 0.5f, 0.1f)) insPx += at(x, y)
            if (insPx.size < 12) return
            fun chromaOf(cc: Int): Int = max(Color.red(cc), max(Color.green(cc), Color.blue(cc))) - min(Color.red(cc), min(Color.green(cc), Color.blue(cc)))
            val chs = insPx.map { chromaOf(it) }.sorted()
            val chT = chs[(chs.size * 0.4f).toInt().coerceAtMost(chs.size - 1)]
            val lowC = insPx.filter { chromaOf(it) <= chT }.ifEmpty { insPx }
            val sortedW = lowC.sortedBy { lum(it) }
            val sclera = sortedW[(sortedW.size * 0.85f).toInt().coerceAtMost(sortedW.size - 1)]
            val scleraL = lum(sclera)
            if (scleraL < 80f) return   // (ojo muy oscuro: no hay blanco)
            // 🔎 EL IRIS EXACTO: el círculo cuyo BORDE (la aureola) separa mejor lo oscuro de adentro de lo blanco de afuera.
            //    Se mira solo el borde de los lados (arriba y abajo lo tapan los párpados). Así el círculo calza con el iris
            //    de verdad: al moverse se ve COMPLETO, con toda su aureola, y no queda ningún pedazo atrás.
            val angC = FloatArray(42); val angS = FloatArray(42)
            run {
                var k = 0
                for (side in 0..1) for (a in -70..70 step 7) {
                    val rad = Math.toRadians((a + side * 180).toDouble())
                    angC[k] = kotlin.math.cos(rad).toFloat(); angS[k] = kotlin.math.sin(rad).toFloat(); k++
                }
            }
            val st = max(1f, (ew / 60f).toInt().toFloat())
            var best = -1e9f; var bcx = (x0 + x1) / 2f; var bcy = (ty0 + ty1) / 2f; var r = ew * 0.215f
            var cx = x0 + ew * 0.2f
            while (cx <= x1 - ew * 0.2f) {
                val q = colOf(cx); val mid = (es.top[q] + es.bot[q]) / 2f
                var cy = mid - eh * 0.2f
                while (cy <= mid + eh * 0.2f) {
                    var rr = rMin
                    while (rr <= rMax) {
                        var s = 0f; var n = 0
                        for (k in 0 until 42) {
                            val xo = cx + angC[k] * rr * 1.1f; val yo = cy + angS[k] * rr * 1.1f
                            val xi = cx + angC[k] * rr * 0.9f; val yi = cy + angS[k] * rr * 0.9f
                            if (inside(xo, yo, 0.05f) && inside(xi, yi, 0.05f)) { s += lumAt(xo, yo) - lumAt(xi, yi); n++ }
                        }
                        if (n >= 8) { val sc = s / n; if (sc > best) { best = sc; bcx = cx; bcy = cy; r = rr } }
                        rr += st
                    }
                    cy += st
                }
                cx += st
            }
            if (best <= 0f) return   // (no se encontró un iris claro: el ojo queda como estaba)
            val rH = r * 1.22f        // (el hueco: el iris con toda su aureola, sin dejar pedacitos de ella atrás)
            // ⚪ el blanco del ojo de cada fila, JUSTO afuera del hueco, a cada lado (lo claro, sin pestañas), suavizado
            //    con las filas vecinas: el relleno calza con el blanco de al lado, sin rayas ni bordes
            val rawL = arrayOfNulls<Int>(bh); val rawR = arrayOfNulls<Int>(bh)
            for (y in 0 until bh) {
                val iy = ry0 + y + 0.5f; val dy = iy - bcy
                if (abs(dy) >= rH) continue
                val half = kotlin.math.sqrt(rH * rH - dy * dy)
                for (side in intArrayOf(-1, 1)) {
                    val vals = ArrayList<Int>()
                    for (k in 2..7) {
                        val sx = bcx + side * (half + k)
                        if (inside(sx, iy, 0f)) { val cc = at(sx.toInt(), ry0 + y); if (lum(cc) >= scleraL * 0.5f) vals += cc }
                    }
                    if (vals.isNotEmpty()) {
                        val v = vals.sortedBy { lum(it) }[vals.size * 2 / 3]
                        if (side < 0) rawL[y] = v else rawR[y] = v
                    }
                }
            }
            fun smooth(raw: Array<Int?>): IntArray? {
                val out = IntArray(bh); val has = BooleanArray(bh)
                for (y in 0 until bh) {
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    for (yy in (y - 4)..(y + 4)) {
                        val cc = raw.getOrNull(yy) ?: continue
                        sr += Color.red(cc); sg += Color.green(cc); sb += Color.blue(cc); n++
                    }
                    if (n > 0) { out[y] = Color.rgb(sr / n, sg / n, sb / n); has[y] = true }
                }
                if (has.none { it }) return null
                for (y in 0 until bh) if (!has[y]) {   // (filas sin muestra: la fila con muestra más cercana)
                    var dBest = Int.MAX_VALUE
                    for (yy in 0 until bh) if (has[yy] && abs(yy - y) < dBest) { dBest = abs(yy - y); out[y] = out[yy] }
                }
                return out
            }
            val sLeft = smooth(rawL); val sRight = smooth(rawR)
            val base = px.copyOf()
            val iris = IntArray(bw * bh)
            for (y in 0 until bh) {
                val iy = ry0 + y + 0.5f; val dy = iy - bcy
                val sl = sLeft?.get(y); val sr = sRight?.get(y)
                val half = kotlin.math.sqrt(max(rH * rH - dy * dy, 0f))
                val xl = bcx - half; val xr = bcx + half
                for (x in 0 until bw) {
                    val ix = rx0 + x + 0.5f
                    val dd = kotlin.math.sqrt((ix - bcx) * (ix - bcx) + dy * dy)
                    val i = y * bw + x
                    val c0 = px[i]
                    // el ojo sin iris: el blanco del ojo en su lugar (dentro del ojo y, abajo, un poquito más allá de su
                    // borde, para que no quede ningún pedacito del iris viejo junto al párpado de abajo)
                    val fillHere = dd < rH && insideLow(ix, iy)
                    // el iris que se mueve: el iris con su aureola y un borde suave; cubre TODO lo que se cambió por blanco
                    // (así, mirando al frente, el ojo queda igual a la foto)
                    if ((dd < rH + r * 0.1f && inside(ix, iy, -0.04f)) || fillHere) {
                        val a = if (fillHere) 1f else ((rH + r * 0.1f - dd) / (r * 0.1f)).coerceIn(0f, 1f)
                        iris[i] = Color.argb((a * 255).toInt(), Color.red(c0), Color.green(c0), Color.blue(c0))
                    }
                    if (fillHere) {
                        val fillC = when {
                            sl != null && sr != null -> {
                                val t = ((ix - xl) / max(xr - xl, 1f)).coerceIn(0f, 1f)
                                Color.rgb((Color.red(sl) + (Color.red(sr) - Color.red(sl)) * t).toInt(),
                                    (Color.green(sl) + (Color.green(sr) - Color.green(sl)) * t).toInt(),
                                    (Color.blue(sl) + (Color.blue(sr) - Color.blue(sl)) * t).toInt())
                            }
                            sl != null -> sl
                            sr != null -> sr
                            else -> sclera
                        }
                        val w = ((rH - dd) / (r * 0.1f)).coerceIn(0f, 1f)
                        base[i] = Color.rgb((Color.red(c0) + (Color.red(fillC) - Color.red(c0)) * w).toInt(),
                            (Color.green(c0) + (Color.green(fillC) - Color.green(c0)) * w).toInt(),
                            (Color.blue(c0) + (Color.blue(fillC) - Color.blue(c0)) * w).toInt())
                    }
                }
            }
            es.realCx = bcx; es.realCy = bcy
            es.realBase = android.graphics.Bitmap.createBitmap(base, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            es.realIris = android.graphics.Bitmap.createBitmap(iris, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            es.realX = rx0.toFloat(); es.realY = ry0.toFloat(); es.realR = r
        }
    }

    /**
     * 👁️ (23-nov-2026) PERSONA REAL: el color del PÁRPADO para el parpadeo. Se mira la piel de ALREDEDOR del ojo (entre el
     * ojo y la ceja, y debajo del ojo), se dejan solo los puntos con color de piel (nada de ceja, pestañas ni el blanco
     * del ojo) y se toma la parte CLARA (sin sombras): así el párpado cerrado tiene el mismo tono que la piel que lo rodea,
     * sea cual sea la piel de la persona. Una vez por ojo.
     */
    fun findLidSource(bmp: android.graphics.Bitmap, es: EyeShape) {
        if (es.lidTried) return
        es.lidTried = true
        runCatching {
            if (bmp.isRecycled) return
            val cols = es.xs.size
            if (cols < 3) return
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var minTop = Float.MAX_VALUE; var maxBot = -Float.MAX_VALUE; var eh = 1f
            for (q in 0 until cols) {
                x0 = min(x0, es.xs[q]); x1 = max(x1, es.xs[q]); minTop = min(minTop, es.top[q]); maxBot = max(maxBot, es.bot[q])
                eh = max(eh, es.bot[q] - es.top[q])
            }
            val hsv = FloatArray(3)
            val ring = ArrayList<Int>()
            val step = max(1, ((x1 - x0) / 60f).toInt())
            fun take(ya: Float, yb: Float) {
                var y = ya.toInt().coerceIn(0, bmp.height - 1)
                val yEnd = yb.toInt().coerceIn(0, bmp.height - 1)
                while (y <= yEnd) {
                    var x = x0.toInt().coerceIn(0, bmp.width - 1)
                    val xEnd = x1.toInt().coerceIn(0, bmp.width - 1)
                    while (x <= xEnd) {
                        val p = bmp.getPixel(x, y)
                        if (Color.alpha(p) > 128) {
                            Color.colorToHSV(p, hsv)
                            if ((hsv[0] <= 50f || hsv[0] >= 330f) && hsv[1] in 0.04f..0.6f && hsv[2] >= 0.35f) ring += p
                        }
                        x += step
                    }
                    y += step
                }
            }
            take(minTop - eh * 0.9f, minTop - eh * 0.1f)   // (entre el ojo y la ceja)
            take(maxBot + eh * 0.1f, maxBot + eh * 0.7f)   // (debajo del ojo)
            if (ring.size < 12) return
            val sorted = ring.sortedBy { lum(it) }
            val from = (sorted.size * 0.8f).toInt(); val to = (sorted.size * 0.97f).toInt().coerceAtLeast(from + 1).coerceAtMost(sorted.size)
            var r = 0; var g = 0; var b = 0
            for (k in from until to) { r += Color.red(sorted[k]); g += Color.green(sorted[k]); b += Color.blue(sorted[k]) }
            val m = to - from
            es.lidColor = Color.rgb(r / m, g / m, b / m)
            es.lidOk = true
        }
    }

    class Face(val bitmap: android.graphics.Bitmap, val points: FloatArray, val cutout: Boolean = false, shapes: String? = null,
               /** 🧑 (22-nov-2026) foto de una PERSONA REAL (no anime): sin aura, sin cabello que flamea y ojos que miran sin cortar el iris */
               val real: Boolean = false) {
        /** ✏️ Ojos dibujados (null = marcado con un toque) y boca dibujada. */
        val eyes = arrayOfNulls<EyeShape>(2)
        /** 👀 Ojos marcados con un TOQUE: solo su iris (para moverlo); el parpadeo sigue como antes. */
        val tapEyes = arrayOfNulls<EyeShape>(2)
        var mouthLine: MouthLine? = null
        // color de la piel bajo cada ojo (para dibujar el párpado al parpadear)
        val lidColor = IntArray(2)
        /** 🎨 El color que más destaca del personaje (cabello o ropa): lo toman los puntitos del fondo. */
        val dotColor: Int = runCatching { standoutColor(bitmap, points) }.getOrDefault(Color.rgb(70, 150, 255))
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
            // 👀 ojos marcados con un toque: se busca el iris en un óvalo alrededor del punto
            for (k in 0..1) if (eyes[k] == null) runCatching {
                val ex = points[k * 2] * w; val ey = points[k * 2 + 1] * h
                val n = 24
                val poly = FloatArray(n * 2) { i ->
                    val a = (i / 2) * (2.0 * Math.PI / n)
                    if (i % 2 == 0) (ex + kotlin.math.cos(a) * d * 0.16).toFloat() else (ey + kotlin.math.sin(a) * d * 0.06).toFloat()
                }
                tapEyes[k] = buildEye(bitmap, poly)?.takeIf { it.iris != null }
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
        val real = p.getStringSet("avatar_real_paths", null)?.contains(path) == true   // 🧑 ¿persona real?
        val newFace = if (bmp != null) runCatching { Face(bmp, pts.toFloatArray(), cut, shapes, real) }.getOrNull() else null
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

    /**
     * 🧑 (22-nov-2026) Anota si la imagen [path] es la foto de una PERSONA REAL (ML Kit encontró una cara humana al abrirla).
     * Persona real: sin aura y sin cabello que se mueve. Anime o póster: con aura y cabello (como siempre).
     * (se guarda aparte por imagen, así también vale al volver a USAR un avatar guardado)
     */
    fun markReal(context: Context, path: String, real: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet<String>(p.getStringSet("avatar_real_paths", emptySet<String>()) ?: emptySet<String>())
        if (real) set.add(path) else set.remove(path)
        p.edit().putStringSet("avatar_real_paths", set).apply()
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
    // 😉 gestos a pedido (suavizados): ojo cerrado y cejas de cada lado
    private var gWinkL = 0f; private var gWinkR = 0f; private var gBrowL = 0f; private var gBrowR = 0f
    private var lastFrame = 0L
    private var isSpeakingNow = false
    private var slowLevel = 0f   // (volumen promedio de la voz en el último medio segundo)
    private var peakLevel = 0f   // (lo más fuerte que sonó hace poco: una «pausa» es cuando baja mucho de eso)
    private var speakStartAt = 0L   // (cuándo empezó a hablar esta vez)
    // 👀 giro de la cara (solo cuando se lo pides)
    private var lookX = 0f; private var lookY = 0f
    // 👂 (27-nov-2026) reacciones mientras tú hablas: asentir con la cabeza, levantar las cejas
    private var lookYBase = 0f; private var nodStart = -1L; private var nextNod = 0L; private var listenBrowUntil = 0L
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

    // 🖥️ (23-nov-2026) El próximo cuadro del avatar. Con «Avatar estándar a 60 Hz», en una pantalla de 120 Hz se dibuja
    //    un refresco sí y otro no (60 cuadros por segundo): la mitad de trabajo para la GPU y la pantalla puede bajar a
    //    60. Con «Avatar adaptable a 120 Hz» (o si la pantalla ya está en 60) se dibuja en cada refresco, como antes.
    //    El movimiento va según el tiempo: se ve igual de rápido, solo cambia cuántos cuadros por segundo.
    private val frameTick = Runnable { invalidate() }
    // 😴 (23-nov-2026) MODO DESCANSO: si por 4 segundos no pasa nada (la IA no habla ni te escucha, la visión no te está
    //    siguiendo, no tocas la pantalla y no hay explosión del aura, relámpagos ni fuego), el avatar baja a 30 cuadros
    //    por segundo: sigue respirando y parpadeando, pero la GPU descansa. Vuelve AL INSTANTE a 60 (o 120) apenas pasa
    //    algo. Vale para las dos opciones de «Frecuencia del avatar».
    private var lastActiveAt = 0L
    private fun avatarBusy(now: Long): Boolean {
        if (AvatarVoice.isSpeaking() || AvatarVoice.isListening()) return true
        if (AvatarLook.followUntil > now || AvatarLook.until > now) return true          // 👁️ visión / «mira a…»
        if (runCatching { AvatarEffects.visionSource?.invoke() }.getOrNull() == true) return true   // 👁️ cámara prendida
        if (now - AvatarEffects.touchAt < 4000L) return true                               // 👆 tocaste la pantalla
        if (auraBurstAt != 0L && now - auraBurstAt < 1800L) return true                   // 💥 explosión del aura
        val fx = AvatarEffects.auraFx
        if (AvatarEffects.aura && (fx == "lightning" || fx == "fire") && AvatarCustom.face?.cutout == true) return true   // ⚡🔥
        return false
    }

    // 🌡️ (26-nov-2026) CALOR: si la batería llega a 42 °C, el avatar baja a 30 cuadros por segundo (la GPU trabaja la
    //    mitad) y vuelve a 60 (o 120) cuando baja de 41 °C. La temperatura se lee cada 5 segundos.
    private var hotCheckedAt = 0L
    private var hot = false
    private fun tooHot(now: Long): Boolean {
        if (hotCheckedAt != 0L && now - hotCheckedAt < 5000L) return hot
        hotCheckedAt = now
        val t = runCatching {
            context.applicationContext.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        }.getOrNull()
        if (t != null) hot = if (hot) t >= 41f else t >= 42f
        return hot
    }

    /** Cuántos cuadros por segundo toca ahora: 30 en descanso o con calor (42 °C); si no, 60 o 120 según «Frecuencia del avatar». */
    fun currentFps(): Int {
        val now = SystemClock.uptimeMillis()
        if (lastActiveAt == 0L || avatarBusy(now)) lastActiveAt = now
        if (tooHot(now)) return 30
        // 🖥️ (26-nov-2026) Con «Avatar adaptable a 120 Hz» la APP va a 120, pero el avatar se dibuja como máximo a 60
        //    (menos trabajo para la GPU y menos calor). Interruptor: avatarMax60 = false vuelve a dibujarlo a 120.
        val top = if (avatarMax60) AvatarEffects.maxFps.coerceAtMost(60) else AvatarEffects.maxFps
        return if (now - lastActiveAt > 4000L) 30 else top
    }
    private val avatarMax60 = true

    /** Cuánto esperar para pedir el próximo cuadro (así cae justo en el refresco de pantalla que toca). */
    fun frameDelayMs(hz: Float): Long {
        val fps = currentFps()
        val h = if (hz > 1f) hz else 60f
        return (1000f / fps - 500f / h).toLong().coerceAtLeast(1L)
    }

    private fun nextFrame() {
        val hz = runCatching { display?.refreshRate }.getOrNull() ?: 60f
        if (currentFps() >= 120 || hz <= currentFps() + 5f) { postInvalidateOnAnimation(); return }
        removeCallbacks(frameTick)
        postOnAnimationDelayed(frameTick, frameDelayMs(hz))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }

    /**
     * 🎞️ (24-nov-2026) Hora exacta del refresco de pantalla que dibuja este cuadro (la pone el AvatarDrawable).
     * Así todo se mueve en pasos parejos aunque el dibujo se atrase un poquito. 0 = se usa el reloj de siempre.
     */
    var frameClockMs = 0L

    override fun onDraw(c: Canvas) {
        val now = if (frameClockMs > 0L) frameClockMs.also { frameClockMs = 0L } else SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val t = now / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        update(now, t, dt)

        // ---- fondo: azul noche con un halo (más oscuro que antes: antes 16,34,72 · 6,12,30 · 2,4,12) ----
        // ⚫⚪ (23-nov-2026) Fondo NEGRO en modo oscuro y BLANCO en modo claro (avatar de Cumulus y personalizado).
        //    Solo el fondo: puntitos, círculos, efectos y auras quedan igual.
        fill.shader = null
        fill.color = if (com.paulrj.cumulusia.manager.StarText.light) Color.WHITE else Color.BLACK
        c.drawRect(0f, 0f, w, h, fill)

        val s = min(w, h * 0.82f)                      // tamaño de la cara
        val cx = w / 2 + sin(t * 0.7f) * s * 0.004f    // (se mueve un poquito, como respirando)
        val cy = h * 0.47f + sin(t * 0.9f) * s * 0.006f

        // 🖼️ Imagen propia del avatar: se anima igual (parpadeo, boca, gestos) y no se dibuja la cara de IA
        val custom = AvatarCustom.face
        // ⏳ tu imagen se está leyendo (al abrir la app): solo el fondo, sin mostrar antes la cara por defecto
        if (custom == null && AvatarCustom.waitingForImage(context)) {
            drawFloaters(c, w, h, t, dt)
            if (!drawOnly) nextFrame()
            return
        }
        if (custom != null) {
            if (custom.cutout) drawRings(c, w / 2, h * 0.47f, s, t, dt)   // (sin fondo: se ven los anillos de atrás)
            drawCustom(c, custom, w, h, s, t)
            drawFloaters(c, w, h, t, dt)
            if (!drawOnly) nextFrame()
            return
        }
        // 🤓 (27-nov-2026) «Avatar 2 (intelectual)» elegido en «Avatar de Cumulus»
        if (AvatarEffects.style == 2) {
            drawIntellectual(c, w, h, s, cx, cy, t, dt)
            if (!drawOnly) nextFrame()
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
        shadeSkin(c, cx - lookX * s * 0.02f, cy + fy * 0.3f, s)   // 🌑 piel más oscura (las letras del chat se leen mejor)
        drawCircuits(c, cx + fx * 0.6f, cy + fy * 0.6f, s, t)
        drawBrows(c, cx + fx, cy + fy, s)
        drawEye(c, cx - s * 0.115f + fx * (if (lookX > 0) 0.85f else 1.1f), cy - s * 0.035f + fy, s, false)
        drawEye(c, cx + s * 0.115f + fx * (if (lookX < 0) 0.85f else 1.1f), cy - s * 0.035f + fy, s, true)
        drawNose(c, cx + fx * 1.3f, cy + fy * 1.1f, s)
        drawMouth(c, cx + fx * 1.05f, cy + s * 0.215f + fy * 0.9f, s)

        if (!drawOnly) nextFrame()
    }

    // ------------------------------------------------------------------ 🖼️ imagen propia
    // ======================================================================
    // ✨ AURA DE ENERGÍA (solo avatar personalizado recortado): la silueta del personaje, difuminada y del color que
    //    más destaca de él (el mismo de los puntitos), crece y palpita detrás como llamas, con chispas que suben.
    // ======================================================================
    private var hairEnergy = 1f
    private var auraFor: AvatarCustom.Face? = null
    private var auraColorFor = ""   // (el color con que se armó el aura: si cambia, se vuelve a armar)
    private var auraBmp: android.graphics.Bitmap? = null
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val auraRect = RectF()
    private class Spark(var x: Float, var y: Float, val vy: Float, val size: Float, val ph: Float)
    private val sparks = List(22) { Spark(rnd.nextFloat(), rnd.nextFloat(), 0.10f + rnd.nextFloat() * 0.16f, 0.6f + rnd.nextFloat() * 1.2f, rnd.nextFloat() * 6.28f) }

    /** La silueta del personaje, difuminada y pintada del color del aura (se hace una vez por imagen). */
    /** ✨ Color del aura y sus chispas: dorado (antes tomaba el color del personaje y salía anaranjado). */
    private val AURA_GOLD = Color.rgb(255, 214, 90)
    /** Margen vacío alrededor del aura (en píxeles de la imagen chica) y cuánto es de la imagen, a cada lado. */
    private val AURA_PAD = 10
    private var auraPadX = 0f; private var auraPadY = 0f

    private fun auraBitmap(f: AvatarCustom.Face): android.graphics.Bitmap? {
        if (auraFor === f && auraColorFor == AvatarEffects.auraColor) return auraBmp
        auraFor = f; auraColorFor = AvatarEffects.auraColor
        auraBmp?.recycle(); auraBmp = null
        auraBmp = runCatching {
            val src = f.bitmap
            // (achicar mucho y volver a agrandar con filtro = un difuminado suave y barato)
            val sw = (src.width / 10).coerceAtLeast(8); val sh = (src.height / 10).coerceAtLeast(8)
            val small = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val col = AvatarEffects.rgb(AvatarEffects.auraColor)   // 🎨 (el color elegido en Efectos especiales)
            // 🖼️ Con margen alrededor: el aura RODEA todo el personaje. Si el cabello o los lados tocan el borde de la imagen
            //    (vienen cortados), la silueta se continúa hacia afuera y se apaga de a poco: brillo redondeado, sin franjas
            //    rectas. Abajo (hombros / pecho cortados) se apaga suave en el borde, sin brillar hacia abajo.
            val pw = sw + AURA_PAD * 2; val ph = sh + AURA_PAD * 2
            val out = IntArray(pw * ph)
            val fade = (sh * 0.10f).coerceAtLeast(2f)
            for (y in 0 until ph) for (x in 0 until pw) {
                val ix = x - AURA_PAD; val iy = y - AURA_PAD
                if (iy >= sh) continue                                   // (debajo de la imagen: nada)
                val cx = ix.coerceIn(0, sw - 1); val cy = iy.coerceIn(0, sh - 1)
                // fuera de la imagen (arriba o a los lados): se aleja del borde y se apaga
                val dOut = kotlin.math.hypot((ix - cx).toFloat(), (iy - cy).toFloat())
                val kOut = (1f - dOut / AURA_PAD).coerceIn(0f, 1f).let { it * it }
                // abajo (pecho / hombros cortados): se apaga suave hacia el borde de abajo
                val kBot = ((sh - 1 - cy) / fade).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val a = (Color.alpha(px[cy * sw + cx]) * kOut * kBot).toInt()
                if (a > 0) out[y * pw + x] = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
            }
            val tinted = android.graphics.Bitmap.createBitmap(out, pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
            if (small !== src) small.recycle()
            val tiny = android.graphics.Bitmap.createScaledBitmap(tinted, (pw / 3).coerceAtLeast(4), (ph / 3).coerceAtLeast(4), true)
            tinted.recycle()
            // (se agranda en dos pasos: el brillo queda redondeado y sin bordes en escalones)
            val mid = android.graphics.Bitmap.createScaledBitmap(tiny, pw, ph, true)
            tiny.recycle()
            auraPadX = AURA_PAD.toFloat() / sw; auraPadY = AURA_PAD.toFloat() / sh
            val soft = android.graphics.Bitmap.createScaledBitmap(mid, pw * 2, ph * 2, true)
            if (mid !== soft) mid.recycle()
            soft
        }.getOrNull()
        return auraBmp
    }

    // 💥 (19-nov-2026) Al ENOJARSE, el aura explota (crece de golpe, brilla y lanza chispas) y en 1–2 s vuelve a lo normal;
    //    si sigue enojado queda un poco más intensa y pulsando. Con furia, la explosión es más grande.
    private var auraAngryWas = false
    private var auraBurstAt = 0L
    private var auraBurstRage = false

    /** Dibuja el aura (3 capas que crecen y palpitan hacia arriba) y las chispas, en coordenadas de la imagen. */
    private fun drawAura(c: Canvas, f: AvatarCustom.Face, bw: Float, bh: Float, px: Float, py: Float, d: Float, t: Float) {
        val aura = auraBitmap(f) ?: return
        // 💥 ¿se acaba de enojar?
        val nowMs = SystemClock.uptimeMillis()
        val mood = if (nowMs < AvatarMood.until) AvatarMood.current else AvatarMood.NEUTRAL
        val angry = mood == AvatarMood.ANGER || mood == AvatarMood.RAGE
        if (angry && !auraAngryWas) {
            auraBurstAt = nowMs; auraBurstRage = mood == AvatarMood.RAGE
            runCatching { PowerSound.play(auraBurstRage) }   // 🔊 (si «Sonido de poder» está prendido)
            runCatching { ThunderSound.play(auraBurstRage) }   // ⛈️ (si «Sonido de trueno» está prendido)
            runCatching { ElectricSound.play(auraBurstRage) }  // ⚡ (si «Sonido de electricidad» está prendido)
        }
        auraAngryWas = angry
        val since = (nowMs - auraBurstAt).toFloat()
        // explosión: sube en 0,15 s y baja suave en ~1,6 s
        val burst = when {
            auraBurstAt == 0L || since > 1800f -> 0f
            since < 150f -> since / 150f
            else -> exp(-(since - 150f) / 520f)
        } * (if (auraBurstRage) 1.3f else 1f)
        val sustain = if (angry) 0.25f * (0.6f + 0.4f * sin(t * 9f)) else 0f   // (sigue enojado: pulsa)
        // ⚡🔥 (23-nov-2026) efecto del aura elegido en Efectos especiales
        //    ✨ destello: prende y apaga, prende y apaga · 💓 latente: late como un corazón (pum-pum… pum-pum…)
        //    🔥 de fuego: el brillo queda más suave y encima arden las llamas
        val fx = AvatarEffects.auraFx
        var fxA = 1f      // (cuánto brilla)
        var fxG = 0f      // (cuánto crece)
        when (fx) {
            "flash" -> {
                val ph = (nowMs % 800L) / 800f
                fxA = when {
                    ph < 0.06f -> ph / 0.06f                    // prende (rápido)
                    ph < 0.44f -> 1.15f                         // encendida
                    ph < 0.52f -> 1.15f * (1f - (ph - 0.44f) / 0.08f)   // apaga (rápido)
                    else -> 0f                                  // apagada
                }
                fxA = max(fxA, burst.coerceAtMost(1f))          // (si explota al enojarse, igual se ve)
            }
            "pulse" -> {
                val ph = (nowMs % 1050L) / 1050f
                fun beat(c0: Float, wd: Float): Float { val z = (ph - c0) / wd; return exp(-z * z) }
                val hb = max(beat(0.07f, 0.05f), 0.75f * beat(0.29f, 0.055f))   // pum (fuerte) · pum (más suave) · descanso
                fxA = 0.30f + 0.95f * hb
                fxG = 0.11f * hb
            }
            "fire" -> fxA = 0.55f
        }
        for (layer in 0..2) {
            val flick = 0.5f + 0.5f * sin(t * (7f + layer * 1.7f) + layer * 2.1f)
            val grow = 1.05f + layer * 0.055f + 0.02f * flick * hairEnergy +   // (un poco más grande: rodea todo el personaje)
                (0.30f + layer * 0.12f) * burst + 0.06f * sustain +               // 💥 explosión / enojo
                fxG * (1f + layer * 0.35f)                                        // 💓 (latente: crece en cada latido)
            val lift = -bh * (0.012f + layer * 0.016f) * (0.6f + 0.4f * flick)
            auraPaint.alpha = ((150 - layer * 40) * (0.75f + 0.25f * flick) * hairEnergy.coerceAtMost(1.3f) / 1.3f *
                (1f + 0.9f * burst + 0.35f * sustain) * fxA).toInt().coerceIn(0, 255)
            val save = c.save()
            c.scale(grow, grow * 1.04f, px, py)
            c.translate(0f, lift)
            auraRect.set(-bw * auraPadX, -bh * auraPadY, bw * (1f + auraPadX), bh * (1f + auraPadY))
            c.drawBitmap(aura, null, auraRect, auraPaint)
            c.restoreToCount(save)
        }
        // 🔥 (23-nov-2026) Aura de fuego: llamas que nacen de todo el borde del personaje y suben ardiendo con fuerza
        if (fx == "fire") drawFire(c, f, bw, bh, d, burst)
        // chispas que suben desde el cabello (del mismo color del aura)
        val col = AvatarEffects.rgb(AvatarEffects.auraColor)
        val sparkK = if (fx == "flash") fxA.coerceAtMost(1f) else 1f   // (destello: las chispas también prenden y apagan)
        for (sp in sparks) {
            sp.y -= sp.vy * 0.016f
            if (sp.y < 0f) { sp.y = 1f; sp.x = rnd.nextFloat() }
            val sx = px + (sp.x - 0.5f) * 3.2f * d + sin(t * 2f + sp.ph) * 0.08f * d
            val sy = py - 1.2f * d - (1f - sp.y) * 3.4f * d
            val a = (200 * sp.y * (0.6f + 0.4f * sin(t * 6f + sp.ph)) * sparkK).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(sx, sy, sp.size * 0.035f * d, fill)
        }
        // 💥 ráfaga de chispas hacia afuera al explotar
        if (burst > 0.02f) {
            val prog = (since / 1800f).coerceIn(0f, 1f)
            val cy = py - 0.6f * d
            for (k in 0 until 28) {
                val ang = k * (2f * PI.toFloat() / 28f) + (k % 3) * 0.17f
                val r = d * (0.6f + 3.4f * prog * (0.8f + 0.4f * ((k * 37) % 10) / 10f))
                val a = (230 * (1f - prog) * burst.coerceAtMost(1f)).toInt().coerceIn(0, 255)
                fill.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(px + cos(ang) * r, cy + sin(ang) * r * 0.9f, (0.05f + 0.03f * (k % 3)) * d, fill)
            }
        }
    }

    // ======================================================================
    // 🔥 (23-nov-2026) AURA DE FUEGO: llamas (del color del aura) que nacen pegadas al borde del personaje —cabello,
    //    lados y hombros— y suben ardiendo con fuerza: se mueven, se estiran, se achican y se apagan, y nacen otras.
    //    Van DETRÁS del personaje (no tapan la cara).
    // ======================================================================
    private class Flame {
        var on = false
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
        var age = 0f; var life = 1f; var size = 0f; var ph = 0f
    }
    private val flames = List(170) { Flame() }
    private var fireLastMs = 0L
    private var fireCarry = 0f
    private var fireFor: AvatarCustom.Face? = null
    private var fireEdge = FloatArray(0)   // (x, y, lado) · lado: −1 izquierda · 0 arriba · +1 derecha · en fracción de la imagen
    private var fireSpriteBmp: android.graphics.Bitmap? = null
    private var fireSpriteColor = ""
    private val firePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)   // (las llamas se suman y brillan)
    }
    private val fireRect = RectF()

    /** El borde del personaje (arriba y a los lados), de donde nacen las llamas. Se calcula una vez por imagen. */
    private fun fireEdges(f: AvatarCustom.Face): FloatArray {
        if (fireFor === f) return fireEdge
        fireFor = f
        fireEdge = runCatching {
            val src = f.bitmap
            val sw = (src.width / 8).coerceAtLeast(8); val sh = (src.height / 8).coerceAtLeast(8)
            val small = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== src) small.recycle()
            val yMax = (sh * 0.85f).toInt().coerceAtLeast(1)   // (abajo, el pecho cortado: de ahí no salen llamas)
            fun on(x: Int, y: Int): Boolean = Color.alpha(px[y * sw + x]) > 120
            val out = ArrayList<Float>()
            for (x in 0 until sw) for (y in 0 until yMax) if (on(x, y)) {   // arriba (cabello)
                out.add((x + 0.5f) / sw); out.add((y + 0.5f) / sh); out.add(0f); break
            }
            for (y in 0 until yMax) {                                         // a los lados
                var l = -1; var r = -1
                for (x in 0 until sw) if (on(x, y)) { if (l < 0) l = x; r = x }
                if (l >= 0) {
                    out.add((l + 0.5f) / sw); out.add((y + 0.5f) / sh); out.add(-1f)
                    out.add((r + 0.5f) / sw); out.add((y + 0.5f) / sh); out.add(1f)
                }
            }
            out.toFloatArray()
        }.getOrDefault(FloatArray(0))
        return fireEdge
    }

    /** Una llama: centro casi blanco, luego el color del aura y el borde más oscuro (dorada → anaranjado de fuego). */
    private fun fireSprite(): android.graphics.Bitmap? {
        val key = AvatarEffects.auraColor
        if (fireSpriteBmp != null && fireSpriteColor == key) return fireSpriteBmp
        fireSpriteBmp?.recycle(); fireSpriteBmp = null
        fireSpriteColor = key
        fireSpriteBmp = runCatching {
            val n = 64
            val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
            val col = AvatarEffects.rgb(key)
            val r = Color.red(col); val g = Color.green(col); val b = Color.blue(col)
            val hot = Color.argb(255, r + (255 - r) * 3 / 4, g + (255 - g) * 3 / 4, b + (255 - b) * 3 / 4)
            val mid = Color.argb(210, r, g, b)
            val deep = if (key == "gold") Color.argb(95, 255, 110, 30) else Color.argb(95, r * 7 / 10, g * 7 / 10, b * 7 / 10)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(hot, mid, deep, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
            }
            Canvas(bmp).drawCircle(n / 2f, n / 2f, n / 2f, p)
            bmp
        }.getOrNull()
        return fireSpriteBmp
    }

    /** Hace nacer, mueve y dibuja las llamas (en coordenadas de la imagen). */
    private fun drawFire(c: Canvas, f: AvatarCustom.Face, bw: Float, bh: Float, d: Float, burst: Float) {
        val edge = fireEdges(f)
        if (edge.size < 3) return
        val spr = fireSprite() ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (fireLastMs == 0L) 0.016f else ((now - fireLastMs).coerceIn(0L, 100L)) / 1000f
        fireLastMs = now
        val t = (now % 100000L) / 1000f
        // nacen llamas nuevas (más al enojarse)
        fireCarry = (fireCarry + dt * 125f * (1f + 1.5f * burst.coerceAtMost(1.3f))).coerceAtMost(6f)
        while (fireCarry >= 1f) {
            fireCarry -= 1f
            val fl = flames.firstOrNull { !it.on } ?: break
            val i = rnd.nextInt(edge.size / 3) * 3
            val side = edge[i + 2]
            fl.on = true; fl.age = 0f
            fl.life = 0.45f + rnd.nextFloat() * 0.45f
            fl.x = edge[i] * bw + (rnd.nextFloat() - 0.5f) * 0.15f * d - side * 0.10f * d   // (pegada al borde)
            fl.y = edge[i + 1] * bh + 0.10f * d
            fl.vx = side * (0.25f + rnd.nextFloat() * 0.35f) * d + (rnd.nextFloat() - 0.5f) * 0.3f * d
            fl.vy = -(1.3f + rnd.nextFloat() * 1.4f) * d
            fl.size = (0.30f + rnd.nextFloat() * 0.35f) * d
            fl.ph = rnd.nextFloat() * 6.28f
        }
        for (fl in flames) {
            if (!fl.on) continue
            fl.age += dt
            if (fl.age >= fl.life) { fl.on = false; continue }
            fl.vy -= 1.2f * d * dt                                       // (suben cada vez más rápido)
            fl.x += (fl.vx + sin(t * 11f + fl.ph) * 0.55f * d) * dt      // (se mecen como fuego)
            fl.y += fl.vy * dt
            val pr = fl.age / fl.life
            val a = (190f * min(1f, pr * 7f) * (1f - pr)).toInt().coerceIn(0, 255)
            if (a <= 0) continue
            val wd = fl.size * (1f - 0.55f * pr) * (0.85f + 0.15f * sin(t * 23f + fl.ph))   // (se achica y titila)
            val ht = wd * (1.6f + 0.9f * pr)                                                   // (y se estira hacia arriba)
            firePaint.alpha = a
            fireRect.set(fl.x - wd / 2f, fl.y - ht * 0.7f, fl.x + wd / 2f, fl.y + ht * 0.3f)
            c.drawBitmap(spr, null, fireRect, firePaint)
        }
    }

    // ======================================================================
    // ⚡ (23-nov-2026) AURA RELÁMPAGO: rayos a los lados del personaje (por todo el cuadro, como los dibujó Pablo),
    //    que aparecen y desaparecen como un relámpago: destellan, parpadean y se van; luego caen otros en otro lugar.
    //    Del color del aura, con el centro casi blanco. Van DETRÁS del personaje.
    // ======================================================================
    private class Bolt(val main: Path, val branches: List<Path>, val born: Long, val life: Long)
    private val bolts = ArrayList<Bolt>()
    private var nextBoltAt = 0L
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** Puntos de un rayo en zigzag entre dos puntos (se parte en mitades y cada mitad se corre al azar). */
    private fun boltPoints(x0: Float, y0: Float, x1: Float, y1: Float, rough: Float): FloatArray {
        var pts = floatArrayOf(x0, y0, x1, y1)
        var disp = rough
        repeat(5) {
            val n = pts.size / 2
            val out = FloatArray((n * 2 - 1) * 2)
            for (i in 0 until n - 1) {
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[i * 2 + 2]; val by = pts[i * 2 + 3]
                val len = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(1f)
                val nx = -(by - ay) / len; val ny = (bx - ax) / len
                val off = (rnd.nextFloat() * 2f - 1f) * disp
                out[i * 4] = ax; out[i * 4 + 1] = ay
                out[i * 4 + 2] = (ax + bx) / 2f + nx * off; out[i * 4 + 3] = (ay + by) / 2f + ny * off
            }
            out[out.size - 2] = pts[pts.size - 2]; out[out.size - 1] = pts[pts.size - 1]
            pts = out
            disp *= 0.55f
        }
        return pts
    }

    private fun pathOf(pts: FloatArray): Path = Path().apply {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    }

    /** Un rayo nuevo, a la izquierda o a la derecha: desde el personaje hacia el borde del cuadro, con 1 a 3 ramas. */
    private fun spawnBolt(now: Long, w: Float, h: Float, charL: Float, charR: Float, left: Boolean) {
        val ya = h * (0.10f + rnd.nextFloat() * 0.75f)
        val xa = if (left) (charL + 0.03f * w).coerceIn(0.22f * w, 0.45f * w) else (charR - 0.03f * w).coerceIn(0.55f * w, 0.78f * w)
        val xb = if (left) w * (0.02f + rnd.nextFloat() * 0.12f) else w * (0.86f + rnd.nextFloat() * 0.12f)
        val yb = (ya + (rnd.nextFloat() * 2f - 1f) * 0.32f * h).coerceIn(0.04f * h, 0.96f * h)
        val len = kotlin.math.hypot(xb - xa, yb - ya)
        val pts = boltPoints(xa, ya, xb, yb, len * 0.22f)
        val branches = ArrayList<Path>()
        repeat(1 + rnd.nextInt(3)) {
            val k = (pts.size / 2) * (2 + rnd.nextInt(6)) / 10            // (de algún punto del rayo, entre el 20% y el 70%)
            val sx = pts[k * 2]; val sy = pts[k * 2 + 1]
            val ang = kotlin.math.atan2(yb - ya, xb - xa) + (if (rnd.nextBoolean()) 1f else -1f) * (0.45f + rnd.nextFloat() * 0.6f)
            val bl = len * (0.22f + rnd.nextFloat() * 0.28f)
            branches += pathOf(boltPoints(sx, sy, sx + cos(ang) * bl, sy + sin(ang) * bl, bl * 0.25f))
        }
        bolts += Bolt(pathOf(pts), branches, now, 260L + rnd.nextInt(180))
        while (bolts.size > 6) bolts.removeAt(0)
    }

    /** Dibuja los rayos (en coordenadas del cuadro): destellan, parpadean y se apagan. */
    private fun drawLightning(c: Canvas, w: Float, h: Float, charL: Float, charR: Float, s: Float) {
        val now = SystemClock.uptimeMillis()
        if (now >= nextBoltAt) {
            val left = rnd.nextBoolean()
            spawnBolt(now, w, h, charL, charR, left)
            if (rnd.nextFloat() < 0.35f) spawnBolt(now, w, h, charL, charR, !left)   // (a veces caen dos a la vez)
            nextBoltAt = now + 180L + rnd.nextInt(770)
        }
        val col = AvatarEffects.rgb(AvatarEffects.auraColor)
        val r = Color.red(col); val g = Color.green(col); val b = Color.blue(col)
        val core = Color.rgb(r + (255 - r) * 7 / 10, g + (255 - g) * 7 / 10, b + (255 - b) * 7 / 10)
        // ⚡ destella · se apaga · vuelve a destellar · se va apagando
        fun bright(bt: Bolt): Float {
            val age = now - bt.born
            return when {
                age < 50L -> 1f
                age < 90L -> 0.15f
                age < 150L -> 1f
                age < 185L -> 0.35f
                else -> 1f - ((age - 185f) / (bt.life - 185f)).coerceIn(0f, 1f)
            }
        }
        bolts.removeAll { now - it.born > it.life }
        var flash = 0f
        for (bt in bolts) flash = max(flash, bright(bt))
        if (flash > 0.02f) {   // (el cuadro se ilumina un poquito con cada rayo)
            fill.shader = null
            fill.color = Color.argb((22 * flash).toInt().coerceIn(0, 255), r, g, b)
            c.drawRect(0f, 0f, w, h, fill)
        }
        fun stroke(p: Path, a: Float, wk: Float) {
            boltPaint.strokeWidth = s * 0.022f * wk; boltPaint.color = Color.argb((70 * a).toInt().coerceIn(0, 255), r, g, b)
            c.drawPath(p, boltPaint)
            boltPaint.strokeWidth = s * 0.009f * wk; boltPaint.color = Color.argb((210 * a).toInt().coerceIn(0, 255), r, g, b)
            c.drawPath(p, boltPaint)
            boltPaint.strokeWidth = s * 0.0035f * wk; boltPaint.color = Color.argb((255 * a).toInt().coerceIn(0, 255), Color.red(core), Color.green(core), Color.blue(core))
            c.drawPath(p, boltPaint)
        }
        for (bt in bolts) {
            val a = bright(bt)
            if (a <= 0.01f) continue
            stroke(bt.main, a, 1f)
            for (br in bt.branches) stroke(br, a * 0.8f, 0.6f)
        }
    }

    private val MESH = 64
    private val meshVerts = FloatArray((MESH + 1) * (MESH + 1) * 2)

    private val realMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private val realBounds = android.graphics.RectF()
    private var realBlurR = -1f

    /**
     * 👀 (22-nov-2026) PERSONA REAL: la vista se mueve deslizando SUAVE todo el interior del ojo (iris y blanco juntos),
     * con el borde difuminado. Antes se recortaba el iris y se tapaba su lugar con «blanco del ojo»: en anime calza,
     * pero en una foto real el corte se notaba (la vista se veía «separada»). Así parece que el ojo gira, sin costuras.
     */
    private fun drawRealGaze(c: Canvas, bmp: android.graphics.Bitmap, es: AvatarCustom.EyeShape, ox: Float, oy: Float) {
        if (bmp.isRecycled) return
        val cols = es.xs.size
        if (cols < 3) return
        // 👀 (23-nov-2026) Se mueve EL IRIS de verdad (antes se corría todo el ojo apenas un 7% y no se notaba)
        AvatarCustom.buildRealIris(bmp, es)
        if (drawRealIris(c, es, ox, oy)) return
        var eh = 1f
        for (q in 0 until cols) eh = max(eh, es.bot[q] - es.top[q])
        val ew = abs(es.xs[cols - 1] - es.xs[0]).coerceAtLeast(1f)
        val mvx = (gazeX * 1.6f).coerceIn(-1f, 1f) * ew * 0.07f
        val mvy = (gazeY * 1.6f).coerceIn(-1f, 1f) * eh * 0.10f + mvx * es.slope
        if (abs(mvx) < 0.25f && abs(mvy) < 0.25f) return   // (mirando al frente: el ojo queda tal cual la foto)
        path.reset()
        for (q in 0 until cols) { val y = es.top[q] + (es.bot[q] - es.top[q]) * 0.16f; if (q == 0) path.moveTo(es.xs[q] + ox, y + oy) else path.lineTo(es.xs[q] + ox, y + oy) }
        for (q in cols - 1 downTo 0) path.lineTo(es.xs[q] + ox, es.bot[q] - (es.bot[q] - es.top[q]) * 0.14f + oy)
        path.close()
        path.computeBounds(realBounds, true)
        val pad = eh * 0.5f + abs(mvx) + abs(mvy)
        realBounds.inset(-pad, -pad)
        val sl = c.saveLayer(realBounds, null)
        c.drawBitmap(bmp, ox + mvx, oy + mvy, bmpPaint)
        val br = max(1f, eh * 0.18f)
        if (abs(br - realBlurR) > 0.5f) {   // (el difuminado se arma una sola vez por tamaño de ojo)
            realBlurR = br
            realMaskPaint.maskFilter = android.graphics.BlurMaskFilter(br, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, realMaskPaint)
        c.restoreToCount(sl)
    }

    // 🧑 (23-nov-2026) para el iris real: el borde del ojo, apenas suave
    private val realEyeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private var realEyeBlurR = -1f

    /**
     * 👀 (23-nov-2026) PERSONA REAL: el ojo SIN iris (con su blanco de verdad) y encima el iris de la foto, corrido hacia
     * donde mira el avatar (a los lados, arriba y abajo). Todo queda dentro del ojo: los párpados tapan el iris como en un
     * ojo de verdad. Mirando al frente, el ojo queda tal cual la foto. false = no se pudo armar el iris (se usa lo de antes).
     */
    private fun drawRealIris(c: Canvas, es: AvatarCustom.EyeShape, ox: Float, oy: Float): Boolean {
        val base = es.realBase ?: return false
        val iris = es.realIris ?: return false
        if (base.isRecycled || iris.isRecycled) return false
        val cols = es.xs.size
        var eh = 1f
        for (q in 0 until cols) eh = max(eh, es.bot[q] - es.top[q])
        val ew = abs(es.xs[cols - 1] - es.xs[0]).coerceAtLeast(1f)
        // cuánto se mueve: a los lados hasta casi tocar la esquina del ojo; arriba/abajo, un poco
        // (sin salirse del ojo: hasta donde el iris llega a la esquina, de cada lado)
        val ex0 = min(es.xs[0], es.xs[cols - 1]); val ex1 = max(es.xs[0], es.xs[cols - 1])
        val gx = (gazeX * 1.6f).coerceIn(-1f, 1f)
        var lim = if (gx < 0f) es.realCx - ex0 - es.realR else ex1 - es.realCx - es.realR
        // (y no se mete en la esquina angosta del ojo, donde quedaría cortado: llega solo hasta donde el ojo todavía es
        //  bien abierto para el iris)
        run {
            var qc = 0
            for (q in 0 until cols) if (abs(es.xs[q] - es.realCx) < abs(es.xs[qc] - es.realCx)) qc = q
            val dir = if ((gx < 0f) == (es.xs[cols - 1] > es.xs[0])) -1 else 1
            var xe = es.xs[qc]
            var q = qc
            while (q in 0 until cols && es.bot[q] - es.top[q] >= es.realR * 1.3f) { xe = es.xs[q]; q += dir }
            lim = min(lim, abs(xe - es.realCx) - es.realR * 0.25f)
        }
        val maxX = lim.coerceIn(0f, ew * 0.18f)
        val mvx = gx * maxX
        // (al mirar a un lado, si el párpado de ABAJO tapa el borde del iris más que mirando al frente, el iris sube lo
        //  justo para que su aureola se vea completa abajo; arriba lo puede tapar un poco más el párpado, como en un ojo
        //  de verdad)
        var lift = 0f
        if (abs(mvx) > 0.25f) {
            val r = es.realR
            fun colOf(x: Float): Int = ((x - ex0) / ew * cols - 0.5f).toInt().coerceIn(0, cols - 1)
            var eb = 0f; var et = 0f
            var u = -r * 0.95f
            while (u <= r * 0.95f) {
                val x = es.realCx + mvx + u
                if (x >= ex0 && x <= ex1) {
                    val qn = colOf(x); val qf = colOf(es.realCx + u)
                    val half = kotlin.math.sqrt(max(r * r - u * u, 0f))
                    val cb = es.realCy + half; val ct = es.realCy - half
                    eb = max(eb, max(0f, cb - es.bot[qn]) - max(0f, cb - es.bot[qf]))
                    et = max(et, max(0f, es.top[qn] - ct) - max(0f, es.top[qf] - ct))
                }
                u += 1f
            }
            lift = min(eb, max(0f, r * 0.35f - et))
        }
        val mvy = (gazeY * 1.6f).coerceIn(-1f, 1f) * eh * 0.14f + mvx * es.slope - lift
        if (abs(mvx) < 0.25f && abs(mvy) < 0.25f) return true   // (mirando al frente: el ojo queda tal cual la foto)
        path.reset()
        for (q in 0 until cols) { if (q == 0) path.moveTo(es.xs[q] + ox, es.top[q] + oy) else path.lineTo(es.xs[q] + ox, es.top[q] + oy) }
        for (q in cols - 1 downTo 0) path.lineTo(es.xs[q] + ox, es.bot[q] + oy)
        path.close()
        path.computeBounds(realBounds, true)
        realBounds.inset(-eh * 0.4f, -eh * 0.4f)
        val sl = c.saveLayer(realBounds, null)
        c.drawBitmap(base, es.realX + ox, es.realY + oy, bmpPaint)
        c.drawBitmap(iris, es.realX + ox + mvx, es.realY + oy + mvy, bmpPaint)
        val br = max(1f, eh * 0.04f)
        if (abs(br - realEyeBlurR) > 0.5f) {
            realEyeBlurR = br
            realEyeMaskPaint.maskFilter = android.graphics.BlurMaskFilter(br, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        // (el ojo y un borde finito alrededor: ahí va el blanco nuevo, que tapa los pedacitos del iris viejo junto al
        //  párpado; el iris que se mueve no pasa del ojo, así que no se sale)
        realEyeMaskPaint.strokeWidth = eh * 0.12f
        c.drawPath(path, realEyeMaskPaint)
        c.restoreToCount(sl)
        return true
    }

    private val realSrc = android.graphics.Rect()
    private val realDst = android.graphics.RectF()
    // (el párpado de persona real: primero su forma, después el color solo adentro de la forma —SRC_IN—; antes se
    //  usaba DST_IN, que no borra lo de afuera de la forma, y el párpado salía CUADRADO)
    private val realLidMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val realLidFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private var realLidBlurR = -1f

    /**
     * 👁️ (22-nov-2026) PERSONA REAL: el párpado (ya armado en [path]) se rellena con la PIEL DE VERDAD de la foto: la que
     * está justo arriba del ojo (sin llegar a las pestañas), estirada hacia abajo hasta donde baja el párpado. Así tiene
     * el mismo tono, sombras y textura de la persona, sea cual sea su piel. Encima, una sombra suave hacia las pestañas.
     * false = no se pudo (muy cerca del borde de la imagen): se usa el color parejo de antes.
     */
    private fun drawRealLid(c: Canvas, bmp: android.graphics.Bitmap, es: AvatarCustom.EyeShape, lid: Float, pad: Float,
                            eh: Float, ox: Float, oy: Float): Boolean {
        if (bmp.isRecycled) return false
        val cols = es.xs.size
        if (cols < 3) return false
        var minTop = Float.MAX_VALUE; var lidBot = -Float.MAX_VALUE
        for (q in 0 until cols) {
            minTop = min(minTop, es.top[q])
            lidBot = max(lidBot, es.top[q] + (es.bot[q] - es.top[q]) * lid)
        }
        if (lid > 0.9f) lidBot += pad * 0.5f
        val x0 = min(es.xs[0], es.xs[cols - 1]) - pad * 0.3f
        val x1 = max(es.xs[0], es.xs[cols - 1]) + pad * 0.3f
        // 👁️ (23-nov-2026) el párpado cerrado: con forma de PÁRPADO (no cuadrado). Sube un poco por encima del ojo en el
        //    medio y se junta en las puntas; arriba se funde SUAVE con la piel (tapa las pestañas de arriba) y abajo
        //    termina en la línea de las pestañas. Del color de la piel clara de alrededor, un poquito más oscuro abajo.
        AvatarCustom.findLidSource(bmp, es)
        if (!es.lidOk) return false
        val colN = es.xs.size
        val dxc = abs(es.xs[colN - 1] - es.xs[0]) / colN
        val up = eh * 0.45f * (lid / 0.6f).coerceIn(0f, 1f)   // (al cerrarse del todo tapa las pestañas de arriba)
        var bTop = Float.MAX_VALUE; var bBot = -Float.MAX_VALUE
        val te = FloatArray(colN); val le = FloatArray(colN)
        for (q in 0 until colN) {
            val sq = Math.pow(sin(PI.toFloat() * (q + 0.5f) / colN).toDouble(), 0.8).toFloat()
            te[q] = es.top[q] - up * sq
            le[q] = es.top[q] + (es.bot[q] - es.top[q]) * lid + (if (lid > 0.9f) pad * 0.5f else 0f)
            bTop = min(bTop, te[q]); bBot = max(bBot, le[q])
        }
        val blur = max(1f, eh * 0.04f)
        realBounds.set(min(es.xs[0], es.xs[colN - 1]) - dxc * 2f + ox, bTop - blur * 3f + oy,
            max(es.xs[0], es.xs[colN - 1]) + dxc * 2f + ox, bBot + blur * 3f + oy)
        val sl = c.saveLayer(realBounds, null)
        // 1) la forma: columna por columna, transparente arriba (se funde con la piel) y entera más abajo
        if (abs(blur - realLidBlurR) > 0.5f) {
            realLidBlurR = blur
            realLidMaskPaint.maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        for (q in 0 until colN) {
            if (le[q] - te[q] < 0.5f) continue
            val fade = max(1f, min((le[q] - te[q]) * 0.5f, up * 0.7f))
            realLidMaskPaint.shader = LinearGradient(0f, te[q] + oy, 0f, te[q] + fade + oy, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
            c.drawRect(es.xs[q] - dxc / 2f - 0.5f + ox, te[q] + oy, es.xs[q] + dxc / 2f + 0.5f + ox, le[q] + oy, realLidMaskPaint)
        }
        realLidMaskPaint.shader = null
        // 2) el color de la piel, solo dentro de esa forma (un poquito más oscuro hacia las pestañas)
        realLidFillPaint.shader = LinearGradient(0f, bTop + oy, 0f, bBot + oy, es.lidColor, darker(es.lidColor, 0.9f), Shader.TileMode.CLAMP)
        c.drawRect(realBounds, realLidFillPaint)
        realLidFillPaint.shader = null
        c.restoreToCount(sl)
        return true
    }

    // 🔥 ¿Qué puntos de la malla son CABELLO? (1 = cabello, 0 = piel: frente, cara). Se calcula una vez por avatar.
    private var hairFor: AvatarCustom.Face? = null
    private var hairW: FloatArray? = null

    /**
     * Compara cada punto de la malla con los colores de la piel del personaje (mejillas y nariz, de los dos lados:
     * Vegeta tiene un lado claro y otro con sombra). Lo que se parece a la piel no flamea; el cabello sí.
     */
    private fun hairMask(f: AvatarCustom.Face, e1x: Float, e1y: Float, e2x: Float, e2y: Float, mx: Float, my: Float, d: Float): FloatArray? {
        if (hairFor === f) return hairW
        hairFor = f; hairW = null
        val bmp = f.bitmap
        if (bmp.isRecycled) return null
        val bw = bmp.width; val bh = bmp.height
        fun px(x: Float, y: Float): Int = bmp.getPixel(x.toInt().coerceIn(0, bw - 1), y.toInt().coerceIn(0, bh - 1))
        // colores de piel: mejillas (bajo cada ojo) y nariz, más la frente justo encima de cada ojo
        val skin = ArrayList<Int>()
        val ecx = (e1x + e2x) / 2; val ecy = (e1y + e2y) / 2
        for ((ex, ey) in listOf(e1x to e1y, e2x to e2y)) {
            for (k in 0..2) skin += px(ex + (k - 1) * 0.08f * d, ey + 0.30f * d + k * 0.05f * d)
            skin += px(ex, ey - 0.42f * d)
        }
        skin += px(ecx, (ecy + my) / 2); skin += px(ecx, ecy - 0.5f * d)
        val sk = skin.filter { Color.alpha(it) > 200 && lumOf(it) > 45f }
        if (sk.isEmpty()) return null
        // distancia que se fija más en el TONO que en el brillo (el pelo claro de Vegeta y su piel clara se parecen en brillo)
        fun dist(a: Int, b: Int): Float {
            val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
            val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
            val d1: Float = 2f * ((ar - ag) - (br - bg)).toFloat(); val d2: Float = 2f * ((ag - ab) - (bg - bb)).toFloat()
            val d3: Float = 0.6f * (lumOf(a) - lumOf(b))
            return kotlin.math.sqrt((d1 * d1 + d2 * d2 + d3 * d3).toDouble()).toFloat()
        }
        val n = MESH + 1
        val raw = FloatArray(n * n)
        val cell = max(bw, bh).toFloat() / MESH
        for (j in 0 until n) for (i in 0 until n) {
            val x = bw.toFloat() * i / MESH; val y = bh.toFloat() * j / MESH
            var skinVotes = 0; var tot = 0
            for (sy in -1..1) for (sx in -1..1) {
                val c = px(x + sx * cell * 0.33f, y + sy * cell * 0.33f)
                if (Color.alpha(c) < 100) continue
                tot++
                if (lumOf(c) < 60f) continue          // (las líneas negras no cuentan)
                if (sk.any { dist(c, it) < 48f }) skinVotes++
            }
            raw[j * n + i] = if (tot == 0) 1f else if (skinVotes * 3 >= tot) 0f else 1f
        }
        // al lado de la piel el pelo se mueve menos (para que el borde de la frente no se deforme)
        val out = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            var v = raw[j * n + i]
            if (v > 0f) for (dj in -1..1) for (di in -1..1) {
                val ii = i + di; val jj = j + dj
                if (ii in 0 until n && jj in 0 until n && raw[jj * n + ii] == 0f) v = min(v, 0.35f)
            }
            out[j * n + i] = v
        }
        hairW = out
        return out
    }

    private fun lumOf(c: Int) = Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f

    // 🎨 (23-nov-2026) EL COLOR DEL CABELLO del anime (para los puntitos y las líneas circulares cuando no hay aura).
    //    Se mira la parte de ARRIBA de la cabeza (donde empieza el dibujo), se dejan afuera las líneas negras y los
    //    brillos, y se toma el tono del medio. Si el cabello es NEGRO (o muy oscuro), queda el AZUL de siempre.
    //    Solo anime (las fotos de personas reales siguen como antes). Una vez por avatar.
    private var hairColorFor: AvatarCustom.Face? = null
    private var hairColorVal: Int? = null
    private fun hairColorOf(f: AvatarCustom.Face, e1x: Float, e1y: Float, e2x: Float, e2y: Float, d: Float) {
        if (hairColorFor === f) return
        hairColorFor = f; hairColorVal = null
        if (f.real || !f.cutout) return   // (solo anime recortado: con fondo, lo de arriba sería el fondo, no el pelo)
        hairColorVal = runCatching {
            val bmp = f.bitmap
            if (bmp.isRecycled) return@runCatching null
            val bw = bmp.width; val bh = bmp.height
            fun px(x: Float, y: Float): Int = bmp.getPixel(x.toInt().coerceIn(0, bw - 1), y.toInt().coerceIn(0, bh - 1))
            val ecy = (e1y + e2y) / 2
            // la parte de ARRIBA de la cabeza (en un anime, eso es cabello): desde donde empieza el dibujo hasta un
            // tercio del camino a los ojos (así no entra la frente, que en algunos personajes es del mismo tono del pelo)
            val x0 = min(e1x, e2x) - 1.6f * d; val x1 = max(e1x, e2x) + 1.6f * d
            val stepX = max(1f, (x1 - x0) / 40f)
            var yTop = -1f
            run {
                var y = 0f
                while (y < ecy && yTop < 0f) {
                    var x = x0
                    while (x < x1) { if (Color.alpha(px(x, y)) > 200) { yTop = y; break }; x += stepX }
                    y += max(1f, ecy / 200f)
                }
            }
            if (yTop < 0f) return@runCatching null
            val yBot = yTop + (ecy - yTop) * 0.35f
            if (yBot <= yTop + 2f) return@runCatching null
            val stepY = max(1f, (yBot - yTop) / 30f)
            val hair = ArrayList<Int>()
            var y = yTop
            while (y < yBot) {
                var x = x0
                while (x < x1) {
                    val c = px(x, y)
                    if (Color.alpha(c) > 200) hair += c
                    x += stepX
                }
                y += stepY
            }
            if (hair.size < 20) return@runCatching null
            // sin las líneas negras del dibujo (lo más oscuro) y sin los brillos (lo más claro): el tono del medio
            val sorted = hair.sortedBy { lumOf(it) }
            val mid = sorted.subList(sorted.size * 35 / 100, max(sorted.size * 85 / 100, sorted.size * 35 / 100 + 1))
            var r = 0; var g = 0; var b = 0
            for (c in mid) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
            val col = Color.rgb(r / mid.size, g / mid.size, b / mid.size)
            // cabello negro o casi negro → el azul de siempre
            if (lumOf(col) < 60f) Color.rgb(70, 150, 255) else col
        }.getOrNull()
    }

    /** El color del cabello del anime en uso (null = no hay, o es foto real). */
    private fun hairTint(): Int? {
        val f = AvatarCustom.face ?: return null
        return if (hairColorFor === f) hairColorVal else null
    }
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
    /**
     * 👀 Al mirar a un lado (o seguirte con la cámara) TODA la cabeza se corre un poco hacia allá, como el avatar
     * original: cabello, frente y cara juntos; el cuello casi no se mueve (de la pera para abajo se va quedando quieto).
     * Los rasgos (ojos, nariz, boca) se corren un poco más encima de esto (en [disp]), como si la cabeza girara.
     */
    private val hsOut = FloatArray(2)
    /** true = el personaje recortado se mueve ENTERO al mirar a los lados (como el avatar original): sin torcer el cuello. */
    private var kWhole = false
    private fun headShift(y: Float) {
        if (kWhole) { hsOut[0] = 0f; hsOut[1] = 0f; return }
        val d = kD
        val r = ((y - kMy) / d - 0.42f) / 1.0f
        val wgt = if (r <= 0f) 1f else if (r >= 1f) 0.1f else 1f - 0.9f * (r * r * (3f - 2f * r))
        hsOut[0] = lookX * d * 0.12f * wgt
        hsOut[1] = -lookY * d * 0.09f * wgt
    }

    private fun disp(x: Float, y: Float) {
        val f = cf!!; val d = kD
        var dx = 0f; var dy = 0f
        // 👀 giro de la cara (solo si se lo pides): los rasgos se corren hacia donde mira, los bordes quedan quietos
        if (lookX != 0f || lookY != 0f) {
            val fcx = (kEcx + kMx) / 2; val fcy = (kEcy + kMy) / 2
            val gf = gauss(x, y, fcx, fcy, 0.95f * d, 1.15f * d)
            val gn = gauss(x, y, fcx, (kEcy * 0.4f + kMy * 0.6f), 0.35f * d, 0.35f * d)   // (la nariz sobresale: se corre más)
            dx += lookX * d * (0.08f * gf + 0.04f * gn)   // (poquito: antes estiraba la nariz)
            dy -= lookY * d * (0.06f * gf + 0.03f * gn)
            headShift(y); dx += hsOut[0]; dy += hsOut[1]
        }
        // mandíbula / labio de abajo: baja al hablar y en los gestos con boca abierta
        // 🦴 SOLO la mandíbula: de la pera para abajo (el cuello) ya no se mueve (antes el cuello bajaba al hablar)
        val chin = run {
            val r = ((y - kMy) / d - 0.42f) / 0.26f
            if (r <= 0f) 1f else if (r >= 1f) 0f else 1f - r * r * (3f - 2f * r)
        }
        val ml = f.mouthLine
        if (chin <= 0f) {
            // (el cuello: quieto)
        } else if (ml == null) {
            val below = 1f / (1f + exp(-(y - kMy) / (0.035f * d)))
            dy += kOpen * 0.16f * d * below * chin * gauss(x, y, kMx, kMy + 0.3f * d, 0.6f * d, 0.5f * d)
        } else if (kOpen > 0f) {
            // ✏️ boca dibujada: lo de abajo de TU línea baja; en las puntas no se abre (abre con la forma de la línea)
            val ly = mouthYAt(ml, x)
            val rel = y - ly
            val below = 1f / (1f + exp(-rel / (0.012f * d)))
            val prof = mouthProfile(x)
            val deep = (rel / (0.15f * d)).coerceIn(0f, 1f)
            val band = prof + (1f - prof) * deep
            val halfW = max(0.6f * d, (kRx - kLx) * 0.65f)
            dy += kOpen * 0.16f * d * below * band * chin * gauss(x, y, kMx, kMy + 0.3f * d, halfW, 0.5f * d)
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
            // 😉 ceja levantada a pedido (de ese lado)
            val gbE = if (ex > kEcx) gBrowR else gBrowL
            if (gbE != 0f) dy -= gbE * 0.07f * d * gauss(x, y, ex, ey - 0.30f * d, 0.22f * d, 0.14f * d)
            if (kWide > 0f) dy += (y - ey) * kWide * 0.35f * gauss(x, y, ex, ey, 0.28f * d, 0.17f * d)
            if (f.eyes[e] != null) continue   // ✏️ ojo dibujado: el párpado se pinta encima con su forma
            // ojos entrecerrados (el párpado de arriba baja) y párpado de abajo que sube (sonrisa)
            val sq = eLid.coerceAtLeast(0f)
            if (sq > 0f) dy += sq * 0.07f * d * gauss(x, y, ex, ey - 0.09f * d, 0.24f * d, 0.07f * d)
            if (eLow > 0f) dy -= eLow * 0.06f * d * gauss(x, y, ex, ey + 0.10f * d, 0.24f * d, 0.07f * d)
            val lidE = max(kLid, if (ex > kEcx) gWinkR else gWinkL)   // 😉 (+ guiño a pedido de ese lado)
            if (lidE > 0.01f) {
                // todo el ojo se junta en una línea (el párpado de arriba baja mucho y el de abajo sube un poquito)
                val closeY = ey + 0.06f * d
                val up = if (y < closeY) 1f else 0.85f
                dy += (closeY - y) * lidE * up * min(1f, 1.7f * gauss(x, y, ex, ey, 0.27f * d, if (y < closeY) 0.22f * d else 0.12f * d))
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
            // 👀 Al mirar a los lados (o seguirte con la cámara) se mueve el personaje COMPLETO, cabeza y cuerpo juntos,
            //    como el avatar original (antes se movía solo la cabeza y el cuello se torcía)
            tx += lookX * s * 0.05f
            ty -= lookY * s * 0.035f
        } else {
            // Sin recortar (no se pudo quitar el fondo): la cara grande como el avatar original, sin bordes vacíos
            sc = max(0.26f * s / d, max(w / bw, h / bh))
            tx = (w / 2 - ecx * sc).coerceIn(w - bw * sc, 0f)
            ty = (h * 0.40f - ecy * sc + sin(t * 0.9f) * s * 0.004f).coerceIn(h - bh * sc, 0f)
        }

        // ---- datos de este cuadro ----
        kWhole = f.cutout
        cf = f; kD = d; kE1x = e1x; kE1y = e1y; kE2x = e2x; kE2y = e2y; kMx = mx; kMy = my; kEcx = ecx; kEcy = ecy
        kOpen = (eOpen * 1.15f + mouth * (1f - eOpen * 0.5f)).coerceIn(0f, 1.4f)
        kWide = (-eLid).coerceAtLeast(0f)
        // 👁️ parpadeo (en ojos marcados con un toque: el ojo se cierra estirando la piel del párpado hacia abajo)
        kLid = max(blinkAmount(SystemClock.uptimeMillis()), (eLid - 0.35f).coerceAtLeast(0f) * 1.2f).coerceIn(0f, 1f)
        f.mouthLine?.pts?.let { mp -> kLx = mp[0]; kLy = mp[1]; kRx = mp[mp.size - 2]; kRy = mp[mp.size - 1] }

        // ---- malla deformada (solo se calcula cerca de la cara: más rápido) ----
        val fx0 = min(e1x, mx) - 2.2f * d; val fx1 = max(e2x, mx) + 2.2f * d
        val fy0 = min(e1y, e2y) - 2.0f * d; val fy1 = my + 2.2f * d
        // 🔥 Cabello que FLAMEA (solo imágenes recortadas, sin fondo): de las cejas para arriba la imagen ondula como
        //    llamas; las raíces casi no se mueven y las puntas mucho. Al hablar flamea más fuerte.
        // ✨ (22-nov-2026) cabello que se mueve: según el interruptor «Movimiento del cabello» (Efectos especiales)
        val flame = f.cutout && AvatarEffects.hair
        val hairLine = min(e1y, e2y) - 0.75f * d
        hairEnergy += ((0.8f + 0.7f * mouth.coerceIn(0f, 1f) + (if (isSpeakingNow) 0.25f else 0f)) - hairEnergy) * 0.08f
        val amp = 0.11f * d * hairEnergy
        val hw = if (flame) hairMask(f, e1x, e1y, e2x, e2y, mx, my, d) else null
        hairColorOf(f, e1x, e1y, e2x, e2y, d)   // 🎨 (23-nov-2026) el color del cabello (una vez por avatar)
        var k = 0
        for (j in 0..MESH) for (i in 0..MESH) {
            val x = bw * i / MESH; val y = bh * j / MESH
            var vx = x; var vy = y
            if (!(x < fx0 || x > fx1 || y < fy0 || y > fy1)) { disp(x, y); vx += dOut[0]; vy += dOut[1] }
            else if (lookX != 0f || lookY != 0f) { headShift(y); vx += hsOut[0]; vy += hsOut[1] }   // (el resto de la cabeza también)
            if (flame && hw != null && hw[k / 2] > 0f && y < my) {
                // (× hw: solo el CABELLO flamea; la frente y la piel quedan quietas)
                // arriba: más fuerte mientras más alto (las raíces casi no se mueven, las puntas mucho)
                val wTop = if (y < hairLine) ((hairLine - y) / (1.6f * d)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) } else 0f
                // 🔥 a los lados (las puntas que salen junto a las orejas y la cara): más fuerte mientras más lejos de la cara
                val wSide = ((abs(x - ecx) - 0.85f * d) / (1.1f * d)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) } *
                    ((my - y) / (0.5f * d)).coerceIn(0f, 1f)
                val wgt = max(wTop, wSide) * hw[k / 2]
                if (wgt > 0f) {
                    val ph = y / d * 1.9f + x / d * 0.6f
                    // (más rápido que antes: ×1,5)
                    vx += amp * wgt * (sin(t * 4.7f + ph) * 0.75f + sin(t * 8.0f + ph * 1.7f) * 0.25f)
                    vy += -amp * 0.45f * wgt * (0.55f + 0.45f * sin(t * 6.3f + ph * 1.3f))   // (las puntas suben como llamas)
                }
            }
            meshVerts[k++] = vx; meshVerts[k++] = vy
        }
        // ⚡ (23-nov-2026) Aura relámpago: los rayos van en todo el cuadro, a los lados y DETRÁS del personaje
        if (f.cutout && AvatarEffects.aura && AvatarEffects.auraFx == "lightning") drawLightning(c, w, h, tx, tx + bw * sc, s)
        val save = c.save()
        c.translate(tx, ty); c.scale(sc, sc)
        c.rotate(sin(t * 0.45f) * 0.8f, ecx, ecy + 0.5f * d)   // (la cabeza se mueve un poquito)
        // ✨ aura de energía DETRÁS del personaje: según el interruptor «Aura» (Efectos especiales)
        if (f.cutout && AvatarEffects.aura) drawAura(c, f, bw, bh, ecx, my + 1.2f * d, d, t)
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
        // 👀 ojos marcados con un toque: el iris también mira (se esconde al parpadear o entrecerrar)
        if (kLid < 0.2f && eLid < 0.3f) for (e in 0..1) {
            if (f.eyes[e] != null) continue
            val es = f.tapEyes[e] ?: continue
            val ir = es.iris; val ho = es.hole
            if (!f.real && (ir == null || ho == null || ir.isRecycled || ho.isRecycled)) continue
            val cols = es.xs.size
            if ((if (es.xs[cols / 2] > kEcx) gWinkR else gWinkL) > 0.2f) continue   // 😉 (ese ojo cerrado)
            disp(es.xs[cols / 2], (es.top[cols / 2] + es.bot[cols / 2]) / 2)
            val ox = dOut[0]; val oy = dOut[1]
            if (f.real) { drawRealGaze(c, f.bitmap, es, ox, oy); continue }   // 🧑 persona real: sin cortar el iris
            if (ir == null || ho == null) continue
            val mvx = (gazeX * 1.6f).coerceIn(-1f, 1f) * es.moveX
            val mvy = (gazeY * 1.6f).coerceIn(-1f, 1f) * es.moveY + mvx * es.slope   // (sigue la inclinación del ojo)
            // (iris pegado arriba: al moverse baja un poco y se ve ENTERO; quieto vuelve arriba y se ve a la mitad)
            val drop = if (es.irisDy < 0f) (kotlin.math.hypot(gazeX, gazeY) * 2.2f).coerceIn(0f, 1f) * (-es.irisDy) * 0.55f else 0f
            path.reset()
            for (q in 0 until cols) { val y = es.top[q] + (es.bot[q] - es.top[q]) * es.clipTop; if (q == 0) path.moveTo(es.xs[q] + ox, y + oy) else path.lineTo(es.xs[q] + ox, y + oy) }
            for (q in cols - 1 downTo 0) path.lineTo(es.xs[q] + ox, es.bot[q] - (es.bot[q] - es.top[q]) * 0.06f + oy)
            path.close()
            c.save(); c.clipPath(path)
            c.drawBitmap(ho, es.irisX + ox, es.irisY + oy, bmpPaint)
            c.drawBitmap(ir, es.irisX + ox + mvx, es.irisY + es.irisDy + oy + mvy + drop, bmpPaint)
            c.restore()
        }
        for (e in 0..1) {
            val es = f.eyes[e] ?: continue
            val cols = es.xs.size
            val lid = max(max(blinkNow, eLid.coerceAtLeast(0f) * 0.6f),
                if (es.xs[cols / 2] > kEcx) gWinkR else gWinkL).coerceIn(0f, 1f)   // 😉 (+ guiño a pedido)
            val low = (eLow * 0.5f).coerceIn(0f, 0.5f)
            var eh = 1f
            for (q in 0 until cols) eh = max(eh, es.bot[q] - es.top[q])
            val pad = eh * 0.18f
            val midY = (es.top[cols / 2] + es.bot[cols / 2]) / 2
            disp(es.xs[cols / 2], midY)          // (el ojo se mueve junto con la cara: cejas, giro…)
            val ox = dOut[0]; val oy = dOut[1]
            // 👀 el iris mira para donde mira el avatar (te sigue con la mirada, mira a los lados…), como el original
            val ir = es.iris; val ho = es.hole
            if (f.real && lid < 0.97f) drawRealGaze(c, f.bitmap, es, ox, oy)   // 🧑 persona real: sin cortar el iris
            else if (ir != null && ho != null && !ir.isRecycled && !ho.isRecycled && lid < 0.97f) {
                val mvx = (gazeX * 1.6f).coerceIn(-1f, 1f) * es.moveX
            val mvy = (gazeY * 1.6f).coerceIn(-1f, 1f) * es.moveY + mvx * es.slope   // (sigue la inclinación del ojo)
            // (iris pegado arriba: al moverse baja un poco y se ve ENTERO; quieto vuelve arriba y se ve a la mitad)
            val drop = if (es.irisDy < 0f) (kotlin.math.hypot(gazeX, gazeY) * 2.2f).coerceIn(0f, 1f) * (-es.irisDy) * 0.55f else 0f
                path.reset()
                for (q in 0 until cols) { val y = es.top[q] + (es.bot[q] - es.top[q]) * es.clipTop; if (q == 0) path.moveTo(es.xs[q] + ox, y + oy) else path.lineTo(es.xs[q] + ox, y + oy) }
                for (q in cols - 1 downTo 0) path.lineTo(es.xs[q] + ox, es.bot[q] - (es.bot[q] - es.top[q]) * 0.06f + oy)
                path.close()
                c.save(); c.clipPath(path)
                c.drawBitmap(ho, es.irisX + ox, es.irisY + oy, bmpPaint)
                c.drawBitmap(ir, es.irisX + ox + mvx, es.irisY + es.irisDy + oy + mvy + drop, bmpPaint)
                c.restore()
            }
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
                // 🧑 (22-nov-2026) PERSONA REAL: el párpado se hace con la PIEL DE VERDAD de la foto (la de justo arriba del
                //    ojo, estirada hacia abajo al cerrarlo) → mismo tono, sombras y textura de cada persona (piel blanca,
                //    trigueña, morena, canela, amarilla o negra), y encima una sombra suave hacia las pestañas.
                //    (anime: igual que antes, el color parejo de la piel)
                if (!(f.real && drawRealLid(c, f.bitmap, es, lid, pad, eh, ox, oy))) {
                    fill.color = Color.BLACK; fill.shader = LinearGradient(0f, es.top[0] - pad + oy, 0f, midY + eh * 0.5f + oy,
                        es.skin, darker(es.skin, 0.88f), Shader.TileMode.CLAMP)
                    c.drawPath(path, fill); fill.shader = null
                }
                // pestañas: el borde del párpado con el color del trazo del ojo (en anime, la línea negra)
                path.reset()
                for (q in 0 until cols) {
                    val ey = es.top[q] + (es.bot[q] - es.top[q]) * lid
                    if (q == 0) path.moveTo(es.xs[q] + ox, ey + oy) else path.lineTo(es.xs[q] + ox, ey + oy)
                }
                // (persona real: pestañas más finas y del tono oscuro de su propia piel, no una raya negra marcada)
                // (23-nov-2026: más suave y fina todavía: antes se veía como una raya marcada alrededor del ojo cerrado)
                if (f.real) { val dk = darker(es.skin, 0.45f); stroke.color = Color.argb(140, Color.red(dk), Color.green(dk), Color.blue(dk)); stroke.strokeWidth = max(1f, eh * 0.05f) }
                else { stroke.color = es.line; stroke.strokeWidth = max(1.5f, eh * 0.12f) }
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
        run {
            for (e in 0..1) {
                if (f.eyes[e] != null) continue
                val ex = if (e == 0) e1x else e2x; val ey = if (e == 0) e1y else e2y
                val lidE = max(kLid, if (ex > kEcx) gWinkR else gWinkL)   // 😉 (+ guiño a pedido)
                if (lidE <= 0.55f) continue
                stroke.color = Color.argb(((lidE - 0.55f) * 300).toInt().coerceIn(0, 140), 20, 14, 18); stroke.strokeWidth = 0.02f * d
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
        // 👁️ (Avatar visión) te sigue con la mirada: la cara gira un poco hacia donde estás
        val following = !looking && now < AvatarLook.followUntil
        val lx = if (looking) AvatarLook.yaw else if (following) AvatarLook.followYaw else 0f
        val ly = if (looking) AvatarLook.pitch else if (following) AvatarLook.followPitch else 0f
        val kl = 1f - exp(-dt * 4f)
        lookX += (lx - lookX) * kl; lookYBase += (ly - lookYBase) * kl
        if (looking || following) { gazeTX = lx * 0.95f; gazeTY = -ly * 0.9f; nextGaze = now + 800 }
        // 👂 (27-nov-2026) MIENTRAS TÚ HABLAS (Cumulus, Emo y avatares personalizados): te mira, asiente con la cabeza
        //    cada 2 a 3,5 s, a veces levanta las cejas y sonríe un poquito; al callarte vuelve a lo normal
        val userTalking = !AvatarVoice.isSpeaking() && now - AvatarVoice.userTalkAt < 900L
        if (userTalking) {
            if (!looking && !following) { gazeTX = 0f; gazeTY = 0f; nextGaze = now + 800 }
            if (nextNod == 0L) nextNod = now + 400 + rnd.nextInt(600)
            if (now >= nextNod && nodStart < 0) {
                nodStart = now; nextNod = now + 2000 + rnd.nextInt(1500)
                if (rnd.nextFloat() < 0.35f) listenBrowUntil = now + 800
            }
        } else nextNod = 0L
        var nod = 0f
        if (nodStart >= 0) {
            val e = (now - nodStart) / 560f
            if (e >= 1f) nodStart = -1 else nod = sin(e * PI.toFloat()) * (if (e < 0.5f) 1f else 0.85f)
        }
        lookY = lookYBase - nod * 0.32f   // (la cara baja un poquito y vuelve: un «ajá»)
        val k = 1f - exp(-dt * 14f)
        gazeX += (gazeTX - gazeX) * k; gazeY += (gazeTY - gazeY) * k

        // 👄 Boca: con la voz real si llega; si no, un ritmo de sílabas mientras habla
        AvatarVoice.tick(now)
        var target = 0f
        val speaking = AvatarVoice.isSpeaking()
        val listening = !speaking && AvatarVoice.isListening()
        if (speaking) {
            if (!isSpeakingNow) speakStartAt = now
            val age = (now - AvatarVoice.levelAt).toFloat()
            // 🗣️ sílabas suaves (~4 por segundo, como una persona), sin saltos bruscos
            val ph = t * 2f * PI.toFloat() * 3.8f
            val syl = 0.5f - 0.5f * cos(ph + 0.6f * sin(t * 1.7f))
            val synthetic = 0.12f + 0.5f * syl * (0.75f + 0.25f * sin(t * 0.9f))
            // 😠 Abre harto la boca SOLO si está enojada (te insultaron o te dijeron algo feo); hablando normal, como al principio
            val boost = if (AvatarMood.current in 0..3) 1.7f else 1f
            val fast = if (age < 220f) AvatarVoice.level else 0f
            slowLevel += (fast - slowLevel) * (1f - exp(-dt * 2.5f))
            peakLevel = maxOf(fast, peakLevel * exp(-dt * 0.4f))
            target = when (AvatarVoice.source) {
                // volumen real: cuando suena, la boca ABRE Y CIERRA por sílabas (~4 por segundo) — antes quedaba
                // casi abierta y solo «tiritaba»; en cada pausa de la voz se cierra
                // (pausa = la voz baja mucho de lo que venía sonando: no solo silencio total, así se ven las pausas cortas)
                1 -> if (fast < maxOf(0.05f, peakLevel * 0.18f)) 0f else {
                    val amp = (0.22f + slowLevel * 1.2f).coerceAtMost(0.55f)
                    val dip = (fast / (slowLevel * 0.8f + 0.02f)).coerceIn(0f, 1f)   // (baja la voz → cierra)
                    amp * (0.02f + 0.98f * syl) * dip * boost
                }
                2 -> when {                                                                   // aviso por palabra
                    // mientras suena la palabra: abre y cierra por sílabas
                    now < AvatarVoice.wordUntil -> AvatarVoice.level * (0.45f + 0.55f * syl) * 0.75f * boost
                    // pausa entre palabras o frases: la boca se CIERRA (antes seguía moviéndose ~0,5 s y tapaba la pausa)
                    age < 1600f || AvatarVoice.levelAt >= speakStartAt -> 0f
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
        val listenSmile = if (!speaking && now - AvatarVoice.userTalkAt < 900L) 0.12f else 0f   // 👂 (sonrisita al escucharte)
        eLow += (ex.low * w - eLow) * ke; eSmile += (ex.smile * w + listenSmile - eSmile) * ke; eMw += ((1f + (ex.mw - 1f) * w) - eMw) * ke
        eOpen += (ex.open * w - eOpen) * ke; eTeeth += (ex.teeth * w - eTeeth) * ke; eFore += (ex.fore * w - eFore) * ke
        eNose += (ex.nose * w - eNose) * ke
        // 😉 gestos a pedido («guíñame», «levanta una ceja»…)
        val kg = 1f - exp(-dt * 14f)
        val winkOn = now < AvatarGesture.winkUntil
        val wsd = AvatarGesture.winkSide
        gWinkL += ((if (winkOn && wsd <= 0) 1f else 0f) - gWinkL) * kg
        gWinkR += ((if (winkOn && wsd >= 0) 1f else 0f) - gWinkR) * kg
        val browOn = now < AvatarGesture.browUntil
        val lb = if (now < listenBrowUntil) 0.45f else 0f   // 👂 (cejas un poquito arriba: «¿ah, sí?»)
        val bL = if (!browOn) lb else if (!AvatarGesture.browOne) 1f else if (AvatarGesture.browSide < 0) 1f else -0.6f
        val bR = if (!browOn) lb else if (!AvatarGesture.browOne) 1f else if (AvatarGesture.browSide > 0) 1f else -0.6f
        gBrowL += (bL - gBrowL) * (1f - exp(-dt * 9f)); gBrowR += (bR - gBrowR) * (1f - exp(-dt * 9f))
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

    /**
     * 🎨 (23-nov-2026) Con un AURA prendida (avatar personalizado recortado, como el aura misma): los puntitos del
     * fondo y las líneas circulares toman el COLOR DE ESA AURA (dorada, azul, roja…). Sin aura: como antes.
     */
    private fun auraTint(): Int? {
        val f = AvatarCustom.face ?: return null
        if (!f.cutout || !AvatarEffects.aura) return null
        return AvatarEffects.rgb(AvatarEffects.auraColor)
    }

    /** 🎨 Color de los puntitos del fondo: con aura, su color; si no, en un anime, el de su cabello (negro → azul);
     *  con una foto real, el color que más destaca de ella; si no hay imagen, el de siempre. */
    private fun dotColor(a: Int): Int {
        val c = auraTint() ?: hairTint() ?: AvatarCustom.face?.dotColor ?: return accent(a)
        return Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun drawFloaters(c: Canvas, w: Float, h: Float, t: Float, dt: Float) {
        for (f in floaters) {
            f.y -= f.vy * dt; if (f.y < -0.02f) { f.y = 1.02f; f.x = rnd.nextFloat() }
            val a = (90 + 80 * sin(t * 2f + f.tw)).toInt().coerceIn(20, 200)
            fill.color = dotColor(a)
            c.drawCircle(f.x * w, f.y * h, f.size * resources.displayMetrics.density * 0.8f, fill)
        }
    }

    private fun drawRings(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, dt: Float) {
        stroke.strokeWidth = s * 0.0025f
        for (i in 0 until 3) {
            val r = s * (0.50f + i * 0.07f)
            val ringA = (40 + glow * 90).toInt() - i * 8
            // (con aura: la línea circular del color del aura · sin aura: como antes)
            // (sin aura, en un anime: del color de su cabello; cabello negro → azul)
            stroke.color = (auraTint() ?: hairTint())?.let { Color.argb(ringA.coerceIn(0, 255), Color.red(it), Color.green(it), Color.blue(it)) } ?: accent(ringA)
            val start = (t * (8f + i * 5f) * (if (i % 2 == 0) 1 else -1)) % 360f
            tmp.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(tmp, start, 250f - i * 40f, false, stroke)
        }
        for (d in ringDots) {
            d.a += d.speed * dt
            val r = s * d.r
            fill.color = dotColor((90 + glow * 140).toInt().coerceAtMost(240))
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

    /**
     * 🌑 Oscurece un poco la piel (cabeza, cuello y orejas) con una capa azul noche semitransparente, encima de la piel
     *  y DEBAJO de los circuitos, ojos, cejas, nariz y boca: así las letras del chat se leen mejor sobre la cara y los
     *  circuitos siguen igual de luminosos. Se pinta de una sola vez (unidas) para que donde se juntan no quede más oscuro.
     */
    private val skinShadeArea = Path()
    private val earOval = Path()
    private fun shadeSkin(c: Canvas, earCx: Float, earCy: Float, s: Float) {
        skinShadeArea.reset()
        skinShadeArea.op(head, neck, Path.Op.UNION)
        for (side in intArrayOf(-1, 1)) {
            val ex = earCx + side * s * 0.265f; val ey = earCy - s * 0.01f
            earOval.reset(); earOval.addOval(ex - s * 0.04f, ey - s * 0.075f, ex + s * 0.04f, ey + s * 0.075f, Path.Direction.CW)
            skinShadeArea.op(earOval, Path.Op.UNION)
        }
        fill.shader = null
        fill.color = Color.argb(SKIN_SHADE_A, 4, 10, 28)   // (antes 90: un 30 % más oscura)
        c.drawPath(skinShadeArea, fill)
    }

    /** Cuánto oscurece la capa de la piel (0..255). Los párpados usan lo mismo para quedar del color de la cara. */
    private val SKIN_SHADE_A = 140

    /**
     * 🎨 Un color de piel con el MISMO oscurecido que shadeSkin le pone a la cara. Los párpados se dibujan ENCIMA de esa
     * capa (con los ojos), así que sin esto salían más claros que la piel (18-nov-2026: se veían de otro color).
     */
    private fun skinShaded(r: Int, g: Int, b: Int): Int {
        val a = SKIN_SHADE_A / 255f
        return Color.rgb((r * (1 - a) + 4 * a).toInt(), (g * (1 - a) + 10 * a).toInt(), (b * (1 - a) + 28 * a).toInt())
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
        // brillo en la frente (más suave: antes 110 y la frente se veía casi blanca al lado del pecho)
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - s * 0.04f, cy - s * 0.27f, s * 0.14f, Color.argb(40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(head, fill); fill.shader = null
        // 🌑 la frente y la parte de arriba de la cabeza un poco más oscuras (se va aclarando hasta los ojos),
        //    así quedan del mismo tono que las mejillas, el cuello y el pecho
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, cy - s * 0.43f, 0f, cy - s * 0.04f,
            Color.argb(85, 20, 36, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(head, fill); fill.shader = null
        // contorno con brillo azul (más fuerte cuando habla)
        stroke.strokeWidth = s * 0.006f; stroke.color = accent((60 + glow * 150).toInt())
        c.drawPath(head, stroke)
    }

    /**
     * 💡 Brillo de los circuitos: SIEMPRE el mismo, en el chat y en voz a voz (como se ven al escucharte).
     *  Antes cada trazo se prendía y apagaba (pulso) y además brillaban más o menos según si hablaba o escuchaba.
     */
    private val CIRCUIT_GLOW = 0.45f
    /** Color de los circuitos: siempre el celeste luminoso de voz a voz (antes cambiaba de azul a celeste según escuchara o no). */
    private fun circuit(a: Int): Int = Color.argb(a.coerceIn(0, 255), 90, 230, 255)

    private fun drawCircuits(c: Canvas, cx: Float, cy: Float, s: Float, t: Float) {
        c.save(); c.clipPath(head)
        stroke.strokeWidth = s * 0.0035f
        traces.forEachIndexed { i, p ->
            if (p[1] > 0.4f) return@forEachIndexed   // (los del cuello van fuera de la cabeza)
            val pulse = 1f   // 💡 brillo fijo (antes subía y bajaba)
            stroke.color = circuit((50 + CIRCUIT_GLOW * 120 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = circuit((90 + CIRCUIT_GLOW * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        c.save(); c.clipPath(neck)
        traces.forEachIndexed { i, p ->
            if (p[1] <= 0.4f) return@forEachIndexed
            val pulse = 1f   // 💡 brillo fijo (antes subía y bajaba)
            stroke.color = circuit((60 + CIRCUIT_GLOW * 140 * pulse).toInt())
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = circuit((100 + CIRCUIT_GLOW * 150 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
        }
        c.restore()
        // 🔵 los circuitos marcados: cara (dentro de la cabeza) y cuello/hombros (dentro del cuello)
        c.save(); c.clipPath(head); drawTraceList(c, cx, cy, s, t, faceTraces, 5); c.restore()
        c.save(); c.clipPath(neck); drawTraceList(c, cx, cy, s, t, neckTraces, 11); c.restore()
    }

    private fun drawTraceList(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, list: List<FloatArray>, phase: Int) {
        list.forEachIndexed { i, p ->
            val pulse = 1f   // 💡 brillo fijo (antes subía y bajaba)
            stroke.strokeWidth = s * 0.0035f
            stroke.color = circuit((70 + CIRCUIT_GLOW * 140 * pulse).toInt().coerceAtMost(255))
            path.reset(); path.moveTo(cx + p[0] * s, cy + p[1] * s)
            var j = 2; while (j < p.size) { path.lineTo(cx + p[j] * s, cy + p[j + 1] * s); j += 2 }
            c.drawPath(path, stroke)
            fill.color = circuit((110 + CIRCUIT_GLOW * 140 * pulse).toInt().coerceAtMost(255))
            c.drawCircle(cx + p[p.size - 2] * s, cy + p[p.size - 1] * s, s * 0.006f, fill)
            fill.color = circuit((80 + CIRCUIT_GLOW * 100 * pulse).toInt().coerceAtMost(255))
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
            val mb = (if (side < 0) gBrowL else gBrowR) * s * 0.032f   // 😉 (ceja levantada a pedido)
            val yi = y + s * 0.006f - eBIn * s * 1.8f - mb * 0.8f; val yo = y + s * 0.012f - eBOut * s * 1.8f - mb
            path.moveTo(x0, yi)
            path.quadTo(cx + side * s * 0.12f, y - s * 0.03f - (eBIn + eBOut) * 0.9f * s - mb, x1, yo)
            stroke.color = Color.rgb(10, 10, 14); stroke.strokeWidth = s * 0.013f   // ⚫ cejas negras (antes azul grisáceo 54, 76, 116)
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
        val bl = max(blinkAmount(SystemClock.uptimeMillis()), if (right) gWinkR else gWinkL)   // 😉 (+ guiño a pedido)
        val b = max(bl, eLid.coerceAtLeast(0f) * 0.75f)   // 🎭 (entrecerrado)
        val lyU = ey - eh * 1.25f + eh * 1.72f * b          // (curva del párpado de arriba)
        val lyL = ey + eh * 0.95f - eh * 0.48f * bl         // (curva del párpado de abajo)
        if (b > 0.01f) {
            path.reset()
            path.moveTo(ex - ew, ey - eh * 2f); path.lineTo(ex + ew, ey - eh * 2f); path.lineTo(ex + ew, ey)
            path.cubicTo(ex + ew * 0.5f, lyU, ex - ew * 0.5f, lyU, ex - ew, ey); path.close()
            fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ey - eh * 1.3f, 0f, ey + eh * 0.5f, skinShaded(176, 194, 222), skinShaded(132, 154, 192), Shader.TileMode.CLAMP)
            c.drawPath(path, fill); fill.shader = null
        }
        if (bl > 0.01f) {
            path.reset()
            path.moveTo(ex - ew, ey + eh * 2f); path.lineTo(ex + ew, ey + eh * 2f); path.lineTo(ex + ew, ey)
            path.cubicTo(ex + ew * 0.5f, lyL, ex - ew * 0.5f, lyL, ex - ew, ey); path.close()
            fill.color = skinShaded(150, 170, 206); c.drawPath(path, fill)
        }
        // 🎭 párpado de abajo que sube (sonrisa, asco, risa)
        if (eLow > 0.01f) {
            fill.color = Color.BLACK; fill.shader = LinearGradient(0f, ey, 0f, ey + eh, skinShaded(150, 170, 206), skinShaded(176, 194, 222), Shader.TileMode.CLAMP)
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
            // 🦷 Dientes ARRIBA y ABAJO (una fila curva pegada a cada labio) y la LENGUA al medio, con su rayita al centro.
            //    Antes había solo un óvalo gris arriba (y abajo solo en algunos gestos).
            c.save(); c.clipPath(path)
            val tongueY = my + open * 0.45f
            // 👅 lengua (va primero: los dientes de abajo le tapan la parte de abajo)
            fill.color = Color.BLACK
            fill.shader = RadialGradient(mx, tongueY - open * 0.08f, mw * 0.6f, Color.rgb(176, 92, 122), Color.rgb(118, 52, 82), Shader.TileMode.CLAMP)
            tmp.set(mx - mw * 0.55f, tongueY - open * 0.26f, mx + mw * 0.55f, tongueY + open * 0.42f); c.drawOval(tmp, fill)
            fill.shader = null
            stroke.color = Color.argb(150, 70, 26, 48); stroke.strokeWidth = s * 0.003f
            c.drawLine(mx, tongueY - open * 0.16f, mx, tongueY + open * 0.14f, stroke)
            // 🦷 dientes de arriba: desde el labio de arriba hasta una curva un poco más abajo
            val far = s * 0.2f
            path.reset()
            path.moveTo(mx - mw - far, my - far); path.lineTo(mx + mw + far, my - far)
            path.lineTo(mx + mw * 0.92f, ky + open * 0.06f)
            path.cubicTo(mx + mw * 0.45f, my + open * 0.17f + cd * 0.2f, mx - mw * 0.45f, my + open * 0.17f + cd * 0.2f, mx - mw * 0.92f, ky + open * 0.06f)
            path.close()
            fill.color = Color.BLACK
            fill.shader = LinearGradient(0f, my - open * 0.3f, 0f, my + open * 0.15f, Color.rgb(242, 245, 251), Color.rgb(204, 211, 228), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            // 🦷 dientes de abajo: desde el labio de abajo hasta una curva un poco más arriba
            path.reset()
            path.moveTo(mx - mw - far, my + open * 2f + far); path.lineTo(mx + mw + far, my + open * 2f + far)
            path.lineTo(mx + mw * 0.92f, ky + open * 0.12f)
            path.cubicTo(mx + mw * 0.45f, my + open * 0.7f - cd * 0.6f, mx - mw * 0.45f, my + open * 0.7f - cd * 0.6f, mx - mw * 0.92f, ky + open * 0.12f)
            path.close()
            fill.shader = LinearGradient(0f, my + open * 0.5f, 0f, my + open * 0.95f, Color.rgb(222, 227, 240), Color.rgb(188, 196, 216), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            c.restore()
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
        // brillo (rayita gris clara): antes iba pegado bajo el labio de abajo; ahora está en el MENTÓN, un poco curvo
        //    (18-nov-2026). Si la boca se abre mucho, baja con el labio para no quedar encima.
        val lipBot = 0.25f * (ky + open * 0.05f) + 0.75f * (ly + s * 0.03f)   // (parte de abajo del labio, al centro)
        val chinY = max(my + s * 0.075f, lipBot + s * 0.02f)
        val oldCap = stroke.strokeCap
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.argb(70, 255, 255, 255); stroke.strokeWidth = s * 0.007f
        path.reset()
        path.moveTo(mx - s * 0.03f, chinY + s * 0.005f)
        path.quadTo(mx, chinY - s * 0.006f, mx + s * 0.03f, chinY + s * 0.005f)
        c.drawPath(path, stroke)
        stroke.strokeCap = oldCap
        // comisuras
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

    // ======================================================================
    // 🤓 (27-nov-2026) AVATAR 2 · INTELECTUAL: cabeza amarilla redonda, lentes cuadrados unidos por un puente en arco,
    //    hombros y bracitos gorditos, con volumen (3D). Hace los mismos gestos que el avatar 1: parpadea, mira a los
    //    lados, mueve la boca con la voz, pone los 24 gestos, guiña, levanta las cejas y gira la cara. Además tiene 3 poses:
    //      • Normal: los brazos cruzados.
    //      • Pensando 🤔 (mientras piensa lo que le pediste): el dedo en el mentón.
    //      • Saludando 👋 (cuando lo saludas o te despides): levanta el brazo y mueve la mano.
    //    Lo que no se mueve (cuerpo, brazos, cabeza y lentes) se dibuja UNA vez en imágenes y después solo se pegan en
    //    cada cuadro: así gasta casi lo mismo que el avatar 1. Las medidas son las de la muestra aprobada (0 a 500).
    // ======================================================================
    private fun a2c(v: Long): Int = v.toInt()
    private val a2Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val a2F = Paint(Paint.ANTI_ALIAS_FLAG)
    private val a2S = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val a2Dst = RectF()
    private val a2R = RectF()
    private val a2Eye = Path()
    private val a2Clip = Path()
    private var a2K = 0f
    private val a2Back = arrayOfNulls<android.graphics.Bitmap>(3)
    private val a2Front = arrayOfNulls<android.graphics.Bitmap>(3)
    private var a2HeadBmp: android.graphics.Bitmap? = null
    private var a2GlassBmp: android.graphics.Bitmap? = null
    private var a2ShadowBmp: android.graphics.Bitmap? = null
    private var a2Pose = 0; private var a2PrevPose = 0; private var a2PoseAt = 0L
    private var a2Think = 0f; private var a2Wave = 0f; private var a2WaveStart = 0L

    // 🎨 colores con volumen (los mismos de la muestra)
    // 🎨 (27-nov-2026) cuerpo, brazos y manos con los MISMOS colores de la cabeza (antes: FFE760 · FFCC2E · E8930A)
    private val A2_BODY_COLS = intArrayOf(a2c(0xFFFFF27A), a2c(0xFFFFD43B), a2c(0xFFF5A80F), a2c(0xFFD98A00))
    private val A2_BODY_POS = floatArrayOf(0f, 0.45f, 0.85f, 1f)
    private fun a2SkinUNew(): Shader = RadialGradient(150f, 360f, 235f, A2_BODY_COLS, A2_BODY_POS, Shader.TileMode.CLAMP)
    private fun a2TorsoShadeNew(): Shader = RadialGradient(175f, 360f, 210f, intArrayOf(a2c(0x00A85E00), a2c(0x00A85E00), a2c(0x80A05400)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val a2SkinU: Shader by lazy { a2SkinUNew() }
    /** 🎨 (27-nov-2026) Franja del pecho y los hombros (debajo de la cabeza) con el mismo amarillo oscuro que tiene la
     *  cabeza abajo (#E39A0E); se aclara de a poco hacia los brazos. */
    private fun a2ChestBandNew(): Shader = RadialGradient(0f, 0f, 1f, intArrayOf(a2c(0xE6E39A0E), a2c(0xB3E8A010), a2c(0x00E8A010)),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP).apply {
        // (óvalo ancho debajo de la cabeza: cubre el pecho y los hombros, no el brazo levantado)
        setLocalMatrix(android.graphics.Matrix().apply { setScale(190f, 80f); postTranslate(200f, 348f) })
    }
    private val a2ChestBand: Shader by lazy { a2ChestBandNew() }
    private val a2TorsoShade: Shader by lazy { RadialGradient(175f, 360f, 210f, intArrayOf(a2c(0x00A85E00), a2c(0x00A85E00), a2c(0x80A05400)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
    private val a2HeadSkin: Shader by lazy { RadialGradient(164f, 150f, 225f, intArrayOf(a2c(0xFFFFF27A), a2c(0xFFFFD43B), a2c(0xFFF5A80F), a2c(0xFFD98A00)), floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP) }
    private val a2HeadShade: Shader by lazy { RadialGradient(158f, 144f, 234f, intArrayOf(a2c(0x00C06A00), a2c(0x00C06A00), a2c(0x52B86400), a2c(0x998A4600)), floatArrayOf(0f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP) }
    private val a2Cheek: Shader by lazy { RadialGradient(0f, 0f, 30f, a2c(0x73FF8A5B), a2c(0x00FF8A5B), Shader.TileMode.CLAMP) }
    private val a2Lens: Shader by lazy { LinearGradient(0f, 148f, 0f, 256f, a2c(0x47FFFFFF), a2c(0x1A9FD8FF), Shader.TileMode.CLAMP) }
    private val a2EyeWhite: Shader by lazy { RadialGradient(-1.6f, -6.4f, 42f, intArrayOf(a2c(0xFFFFFFFF), a2c(0xFFF7F9FC), a2c(0xFFCBD3DE)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) }
    private val a2Iris: Shader by lazy { RadialGradient(0f, -1.9f, 20.9f, intArrayOf(a2c(0xFF7A4A1A), a2c(0xFF4A2A0C), a2c(0xFF2A1606)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) }

    /** Una parte de la mano (palma, dedo, pulgar…) con su propio brillo, como en la muestra. */
    private class A2Part(val path: Path, val shader: Shader)
    private fun a2PartOf(p: Path, box: RectF, rot: Float, px: Float, py: Float): A2Part {
        val g = RadialGradient(0.35f, 0.30f, 0.80f, a2c(0xFFFFE45C), a2c(0xFFE89A06), Shader.TileMode.CLAMP)
        val m = android.graphics.Matrix(); m.setScale(box.width(), box.height()); m.postTranslate(box.left, box.top)
        if (rot != 0f) { val r = android.graphics.Matrix(); r.setRotate(rot, px, py); p.transform(r); m.postConcat(r) }
        g.setLocalMatrix(m)
        return A2Part(p, g)
    }
    private fun a2Rect(x: Float, y: Float, w: Float, h: Float, r: Float, rot: Float = 0f, px: Float = 0f, py: Float = 0f): A2Part {
        val box = RectF(x, y, x + w, y + h)
        val p = Path(); p.addRoundRect(box, r, r, Path.Direction.CW)
        return a2PartOf(p, box, rot, px, py)
    }
    private fun a2Shape(d: String): A2Part { val p = A2Svg.path(d); val b = RectF(); p.computeBounds(b, true); return a2PartOf(p, b, 0f, 0f, 0f) }
    private class A2Hand(val parts: List<A2Part>, val lines: List<Triple<Path, Float, Int>>)
    // ✋ mano abierta (saluda) · ✊ puño · ☝️ puño con el índice arriba (pensando). Origen = la muñeca.
    private val a2HandOpen: A2Hand by lazy { A2Hand(listOf(
        a2Shape("M-22 -6 C -26 -26, -24 -44, -14 -50 L 16 -50 C 26 -44, 26 -24, 22 -6 C 18 6, -18 6, -22 -6 Z"),
        a2Rect(-21f, -86f, 11f, 40f, 5.5f, -10f, -15f, -50f),
        a2Rect(-9f, -96f, 11.5f, 48f, 5.75f, -3f, -3f, -50f),
        a2Rect(3.5f, -93f, 11.5f, 45f, 5.75f, 4f, 9f, -50f),
        a2Rect(15f, -80f, 10f, 34f, 5f, 12f, 20f, -48f),
        a2Rect(-40f, -40f, 12f, 34f, 6f, -48f, -28f, -14f)),
        listOf(Triple(A2Svg.path("M-12 -30 Q 0 -22 12 -30"), 2f, a2c(0x8CC98200)),
               Triple(A2Svg.path("M-14 -16 Q -2 -10 10 -18"), 2f, a2c(0x66C98200)))) }
    private val a2Fist: A2Hand by lazy { A2Hand(listOf(
        a2Rect(-24f, -26f, 48f, 42f, 18f)),
        listOf(Triple(A2Svg.path("M-14 -26 L -14 -14 M -2 -27 L -2 -14 M 10 -26 L 10 -14"), 2f, a2c(0x99C98200)))) }
    private val a2FistThumb: A2Part by lazy { a2Rect(-26f, -8f, 34f, 12f, 6f) }
    private val a2Point: A2Hand by lazy { A2Hand(listOf(
        a2Rect(-22f, -24f, 44f, 40f, 17f)),
        listOf(Triple(A2Svg.path("M-4 -24 L -4 -12 M 8 -23 L 8 -12"), 2f, a2c(0x99C98200)))) }
    private val a2PointFinger: A2Part by lazy { a2Rect(-21f, -68f, 12.5f, 50f, 6.25f) }
    private val a2PointThumb: A2Part by lazy { a2Rect(-24f, -6f, 30f, 12f, 6f) }

    private fun a2Blur(sigma: Float) = android.graphics.BlurMaskFilter((sigma * 1.73f).coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    private fun a2Stroke(c: Canvas, p: Path, w: Float, color: Int, shader: Shader? = null, blur: Float = 0f) {
        a2S.strokeWidth = w; a2S.color = color; a2S.shader = shader; a2S.maskFilter = if (blur > 0f) a2Blur(blur) else null
        c.drawPath(p, a2S); a2S.shader = null; a2S.maskFilter = null
    }
    private fun a2Fill(c: Canvas, p: Path, color: Int, shader: Shader? = null) {
        a2F.color = color; a2F.shader = shader; c.drawPath(p, a2F); a2F.shader = null
    }
    private fun a2DrawPart(c: Canvas, part: A2Part) {
        a2F.color = Color.BLACK; a2F.shader = part.shader; c.drawPath(part.path, a2F); a2F.shader = null
        a2Stroke(c, part.path, 2f, a2c(0xFFC98200))
    }
    private fun a2DrawHand(c: Canvas, kind: Int, tx: Float, ty: Float, rot: Float, mirror: Boolean) {
        c.save(); c.translate(tx, ty); c.rotate(rot); c.scale(1.25f, 1.25f); if (mirror) c.scale(-1f, 1f)
        when (kind) {
            0 -> { a2HandOpen.parts.forEach { a2DrawPart(c, it) }; a2HandOpen.lines.forEach { a2Stroke(c, it.first, it.second, it.third) } }
            1 -> { a2Fist.parts.forEach { a2DrawPart(c, it) }; a2Fist.lines.forEach { a2Stroke(c, it.first, it.second, it.third) }; a2DrawPart(c, a2FistThumb) }
            else -> {
                a2Point.parts.forEach { a2DrawPart(c, it) }; a2Point.lines.forEach { a2Stroke(c, it.first, it.second, it.third) }
                a2DrawPart(c, a2PointFinger)
                a2Stroke(c, A2Svg.path("M-20 -48 Q -14.75 -45 -9.5 -48"), 1.6f, a2c(0x99C98200))
                a2DrawPart(c, a2PointThumb)
            }
        }
        c.restore()
    }

    /** Un brazo gordito: contorno (sin el pedazo del hombro), del mismo color que el cuerpo, y con volumen de tubo
     *  (luz arriba a la izquierda, sombra abajo) que se pierde suave al llegar al hombro. */
    private fun a2DrawArm(c: Canvas, d: String, cutHard: (Canvas, Paint) -> Unit, cutSoft: (Canvas, Paint) -> Unit, extra: ((Canvas) -> Unit)? = null) {
        val p = A2Svg.path(d)
        val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT); color = Color.BLACK
        }
        c.saveLayer(null, null)
        c.save(); c.translate(0f, 4f); a2Stroke(c, p, 52f, a2c(0x59B87300)); c.restore()
        a2Stroke(c, p, 50f, a2c(0xFFD18A00))
        cutHard(c, erase)
        c.restore()
        a2Stroke(c, p, 46f, Color.BLACK, a2SkinU)
        a2Stroke(c, p, 46f, Color.BLACK, a2TorsoShade)
        a2Stroke(c, p, 46f, Color.BLACK, a2ChestBand)   // 🎨 (27-nov-2026) hombros: mismo amarillo oscuro del pecho
        extra?.invoke(c)
        val clip = Path(); a2S.strokeWidth = 46f; a2S.maskFilter = null
        @Suppress("DEPRECATION") a2S.getFillPath(p, clip)
        c.saveLayer(null, null)
        c.clipPath(clip)
        a2Stroke(c, p, 46f, a2c(0x80A85E00))
        c.save(); c.translate(-5f, -7f); a2Stroke(c, p, 38f, Color.BLACK, a2SkinU, 3.5f); c.restore()
        a2Stroke(c, p, 46f, Color.BLACK, a2ChestBand)
        c.save(); c.translate(-11f, -14f); a2Stroke(c, p, 9f, a2c(0x99FFF6B0), null, 3.5f); c.restore()
        erase.maskFilter = a2Blur(6f); cutSoft(c, erase)
        c.restore()
    }

    // ======================================================================
    // ✋ (27-nov-2026) MANOS DE EMO dibujadas por ti: mano abierta (saluda), puño (brazos cruzados, con las rayitas de
    //    los nudillos y el pulgar doblado) y puño con el índice arriba (pensando). Las líneas café siguen del brazo a
    //    la mano sin cortarse, la muñeca pasa de a poco al color del brazo, y tienen el mismo 3D de los brazos
    //    (brillo del lado de la luz, arriba a la izquierda, y sombra del otro lado).
    //    (Las manos anteriores quedan en el código sin usarse.)
    // ======================================================================
    private class A2HandStyle(val fill: Path, val line: Path, val extras: List<Path>, val skin: Shader, val tube: Shader)
    private fun a2HandStyle(fillD: String, lineD: String, extras: List<String>, skinCols: IntArray, skinPos: FloatArray,
                            tubeHalf: Float, tubeCols: IntArray, tubePos: FloatArray, lowH: Float, yScale: Float = 1f): A2HandStyle {
        val fill = A2Svg.path(fillD); val line = A2Svg.path(lineD)
        // (yScale < 1 = la mano más corta desde la muñeca, sin cambiar su ancho)
        val sq = android.graphics.Matrix().apply { setScale(1f, yScale) }
        if (yScale != 1f) { fill.transform(sq); line.transform(sq) }
        val b = RectF(); fill.computeBounds(b, true)
        val rg = RadialGradient(0.35f, 0.30f, 0.85f, skinCols, skinPos, Shader.TileMode.CLAMP)
        val m = android.graphics.Matrix(); m.setScale(b.width(), b.height()); m.postTranslate(b.left, b.top); rg.setLocalMatrix(m)
        // (abajo, en la muñeca, la mano se desvanece para que se vea el brazo de debajo)
        fun bot() = LinearGradient(0f, -1f, 0f, 9f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val skin = android.graphics.ComposeShader(rg, bot(), android.graphics.PorterDuff.Mode.DST_IN)
        val across = LinearGradient(-tubeHalf, 0f, tubeHalf, 0f, tubeCols, tubePos, Shader.TileMode.CLAMP)
        val low = LinearGradient(0f, 0f, 0f, -lowH, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val tube = android.graphics.ComposeShader(android.graphics.ComposeShader(across, low, android.graphics.PorterDuff.Mode.DST_IN),
            bot(), android.graphics.PorterDuff.Mode.DST_IN)
        return A2HandStyle(fill, line, extras.map { A2Svg.path(it).also { p -> if (yScale != 1f) p.transform(sq) } }, skin, tube)
    }
    private val a2TubeCols = intArrayOf(a2c(0x80FFF27A), a2c(0x4DFFE45A), a2c(0x00FFD43B), a2c(0x40C77800), a2c(0x73A85E00))
    private val a2TubePos = floatArrayOf(0f, 0.35f, 0.65f, 0.85f, 1f)
    private val a2FistStyle: A2HandStyle by lazy { a2HandStyle(A2Svg.HAND_FIST_FILL, A2Svg.HAND_FIST_LINE, listOf(A2Svg.HAND_FIST_DASH, A2Svg.HAND_FIST_THUMB),
        intArrayOf(a2c(0xFFFFE760), a2c(0xFFFFCC2E), a2c(0xFFE8930A)), floatArrayOf(0f, 0.55f, 1f), 17.5f, a2TubeCols, a2TubePos, 12f,
        yScale = 0.8f) }   // ✊ (27-nov-2026) puño 20 % más corto (no se ve tan larga la mano empuñada)
    private val a2PointStyle: A2HandStyle by lazy { a2HandStyle(A2Svg.HAND_POINT_FILL, A2Svg.HAND_POINT_LINE, emptyList(),
        intArrayOf(a2c(0xFFFFEC70), a2c(0xFFFFD640)), floatArrayOf(0f, 1f), 17.5f, a2TubeCols, a2TubePos, 24f) }
    private val a2OpenStyle: A2HandStyle by lazy { a2HandStyle(A2Svg.HAND_OPEN_FILL, A2Svg.HAND_OPEN_LINE, emptyList(),
        intArrayOf(a2c(0xFFFFE15A), a2c(0xFFFAD050), a2c(0xFFF0A818)), floatArrayOf(0f, 0.6f, 1f), 20f,
        a2TubeCols, a2TubePos, 24f) }
    /** Lo que ocupa la mano abierta (para su imagen). */
    private val a2WaveBox: RectF by lazy { val b = RectF(); a2OpenStyle.fill.computeBounds(b, true); b.inset(-8f, -8f); b }
    private var a2WaveHandBmp: android.graphics.Bitmap? = null

    /** Una mano en su lugar: (tx, ty) = la muñeca, rot = giro, sc = tamaño, mirror = la otra mano. */
    private fun a2DrawHand2(c: Canvas, st: A2HandStyle, tx: Float, ty: Float, rot: Float, sc: Float, mirror: Boolean) {
        c.save(); c.translate(tx, ty); c.rotate(rot); c.scale(sc, sc); if (mirror) c.scale(-1f, 1f)
        val toWorld = android.graphics.Matrix().apply { setTranslate(tx, ty); preRotate(rot); preScale(sc, sc); if (mirror) preScale(-1f, 1f) }
        a2HandBody(c, st, rot, mirror, toWorld)
        c.restore()
    }
    private fun a2HandBody(c: Canvas, st: A2HandStyle, rot: Float, mirror: Boolean, toWorld: android.graphics.Matrix) {
        // 🎨 (27-nov-2026) la mano toma el color EXACTO del cuerpo y del brazo en ese mismo lugar (el mismo degradado)
        val inv = android.graphics.Matrix(); toWorld.invert(inv)
        fun bot() = LinearGradient(0f, -1f, 0f, 9f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val skin = a2SkinUNew().apply { setLocalMatrix(inv) }
        val shade = a2TorsoShadeNew().apply { setLocalMatrix(inv) }
        a2F.color = Color.BLACK
        a2F.shader = android.graphics.ComposeShader(skin, bot(), android.graphics.PorterDuff.Mode.DST_IN); c.drawPath(st.fill, a2F)
        a2F.shader = android.graphics.ComposeShader(shade, bot(), android.graphics.PorterDuff.Mode.DST_IN); c.drawPath(st.fill, a2F)
        val chest = a2ChestBandNew(); val cm = android.graphics.Matrix(); chest.getLocalMatrix(cm); cm.postConcat(inv); chest.setLocalMatrix(cm)
        a2F.shader = android.graphics.ComposeShader(chest, bot(), android.graphics.PorterDuff.Mode.DST_IN); c.drawPath(st.fill, a2F)
        a2F.shader = st.tube; c.drawPath(st.fill, a2F); a2F.shader = null
        // 3D: la luz viene de arriba a la izquierda; se pasa a las medidas de esta mano (girada y/o al espejo)
        val r = Math.toRadians(-rot.toDouble()); val gx = 1.0 / 1.562; val gy = 1.2 / 1.562
        var vx = (gx * kotlin.math.cos(r) - gy * kotlin.math.sin(r)).toFloat()
        val vy = (gx * kotlin.math.sin(r) + gy * kotlin.math.cos(r)).toFloat()
        if (mirror) vx = -vx
        c.save(); c.clipPath(st.fill); c.clipRect(-200f, -200f, 200f, -1f)
        c.save(); c.translate(-3.2f * vx, -3.2f * vy); a2Stroke(c, st.line, 7f, a2c(0x80A85E00), null, 1.4f); c.restore()
        c.save(); c.translate(3.2f * vx, 3.2f * vy); a2Stroke(c, st.line, 3.2f, a2c(0xBFFFF6B0), null, 1.4f); c.restore()
        c.restore()
        a2Stroke(c, st.line, 2.4f, a2c(0xFFD18A00))
        for (e in st.extras) a2Stroke(c, e, 2.2f, a2c(0xCCD18A00))
    }

    // 🩹 (27-nov-2026) la línea del hombro y su relleno, recalculados para la curva nueva del brazo que saluda
    //    (terminan justo en el borde de arriba del brazo: sin escalón ni pedacito de fondo)
    /** ⌚ (27-nov-2026) Reloj negro (correa alrededor del brazo y esfera redonda), en medidas de la mano: y = 0 es la muñeca. */
    private fun a2DrawWatch(c: Canvas) {
        val cy = 9f
        // correa: rodea el brazo, más clara a la izquierda (luz) y más oscura a la derecha
        a2R.set(-18.6f, cy - 4f, 18.6f, cy + 4f)
        a2F.color = Color.BLACK
        a2F.shader = LinearGradient(-18.6f, 0f, 18.6f, 0f, intArrayOf(a2c(0xFF3A3A40), a2c(0xFF1A1A1E), a2c(0xFF050506)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(a2R, 3.5f, 3.5f, a2F); a2F.shader = null
        a2S.strokeWidth = 0.8f; a2S.color = a2c(0x40FFFFFF); c.drawLine(-16f, cy - 2.9f, 16f, cy - 2.9f, a2S)
        // esfera: bisel negro, fondo gris muy oscuro, dos agujas y un brillo
        a2F.color = a2c(0xFF0A0A0C); c.drawCircle(0f, cy, 7.6f, a2F)
        a2F.shader = RadialGradient(-2f, cy - 2.5f, 7f, a2c(0xFF34343A), a2c(0xFF141417), Shader.TileMode.CLAMP)
        c.drawCircle(0f, cy, 5.8f, a2F); a2F.shader = null
        a2S.strokeWidth = 0.9f; a2S.color = a2c(0xFF6E6E76); c.drawCircle(0f, cy, 6.7f, a2S)
        a2S.strokeWidth = 1.1f; a2S.color = a2c(0xFFD8D8DE)
        c.drawLine(0f, cy, 0f, cy - 4f, a2S); c.drawLine(0f, cy, 2.8f, cy + 1.2f, a2S)
        a2F.color = a2c(0xFFD8D8DE); c.drawCircle(0f, cy, 0.9f, a2F)
        a2S.strokeWidth = 1.2f; a2S.color = a2c(0x66FFFFFF)
        a2R.set(-5.2f, cy - 5.2f, 5.2f, cy + 5.2f); c.drawArc(a2R, 200f, 70f, false, a2S)
    }

    private val A2_PATCH = "M276.5 334.3 C 300 344, 321.6 350.9, 343.2 347.0 L 344 362 L 270 362 Z"

    /** Brazos de cada pose (lo que va delante de la cabeza). */
    private fun a2BuildFront(c: Canvas, pose: Int) {
        val capL: (Canvas, Paint) -> Unit = { cv, pt -> cv.drawCircle(97f, 395f, 25f, pt) }
        val capR: (Canvas, Paint) -> Unit = { cv, pt -> cv.drawCircle(303f, 395f, 25f, pt) }
        val lineL = "M117 394 Q 113 412 107 430"; val lineR = "M283 394 Q 287 412 293 430"
        when (pose) {
            0 -> {
                // ✋ (27-nov-2026) puños dibujados por ti, seguidos del antebrazo (sin raya en la muñeca)
                a2DrawArm(c, "M92 404 Q 50 480 214.5 442.1", capL, capL)
                a2DrawHand2(c, a2FistStyle, 214.5f, 442.1f, 77f, 1.25f, false)
                a2DrawArm(c, "M308 404 Q 350 480 185 442", capR, capR)
                a2DrawHand2(c, a2FistStyle, 185f, 442f, -77f, 1.25f, true)
                a2Stroke(c, A2Svg.path(lineL), 3.5f, a2c(0xFFD18A00))
                a2Stroke(c, A2Svg.path(lineR), 3.5f, a2c(0xFFD18A00))
            }
            1 -> {
                a2DrawArm(c, "M308 404 Q 352 482 201 446.1", capR, capR)
                a2DrawHand2(c, a2FistStyle, 201f, 446.1f, -76.6f, 1.25f, true)
                val capL2: (Canvas, Paint) -> Unit = { cv, pt -> cv.drawCircle(92f, 394f, 25f, pt) }
                // ☝️ (27-nov-2026) el brazo termina justo en la muñeca y apunta igual que la mano
                a2DrawArm(c, "M92 404 C 88 486, 156.4 396.7, 162.5 327", capL2, capL2)
                a2DrawHand2(c, a2PointStyle, 162.5f, 327f, 5f, 1.32f, false)
                // ⌚ (27-nov-2026) reloj negro en la muñeca de la mano que piensa
                c.save(); c.translate(162.5f, 327f); c.rotate(5f); c.scale(1.32f, 1.32f); a2DrawWatch(c); c.restore()
                a2Stroke(c, A2Svg.path(lineR), 3.5f, a2c(0xFFD18A00))
            }
            else -> {
                a2DrawArm(c, "M92 404 Q 46 484 206.5 446.2", capL, capL)
                a2DrawHand2(c, a2FistStyle, 206.5f, 446.2f, 76.7f, 1.25f, false)
                val patch = A2Svg.path(A2_PATCH)
                val hard: (Canvas, Paint) -> Unit = { cv, pt -> cv.drawCircle(271f, 378f, 37f, pt); cv.drawRect(268f, 337f, 342f, 353f, pt) }
                val soft: (Canvas, Paint) -> Unit = { cv, pt -> hard(cv, pt); cv.drawPath(patch, pt) }
                a2DrawArm(c, "M278 370 Q 420.1 391.9 391.6 285.6", hard, soft) { cv ->
                    a2Fill(cv, patch, Color.BLACK, a2SkinU); a2Fill(cv, patch, Color.BLACK, a2TorsoShade)
                    a2Fill(cv, patch, Color.BLACK, a2ChestBand)
                }
                a2Stroke(c, A2Svg.path("M276.5 334.3 C 300 344, 321.6 350.9, 343.2 347.0"), 3.2f, a2c(0xFFD18A00))
                a2Stroke(c, A2Svg.path(lineL), 3.5f, a2c(0xFFD18A00))
                // (la mano que saluda se dibuja en vivo: se mueve)
            }
        }
    }

    /** Cuerpo de cada pose (va detrás de la cabeza) con su sombra en el suelo. */
    private fun a2BuildBack(c: Canvas, pose: Int) {
        a2F.color = a2c(0x38000000); a2F.maskFilter = a2Blur(6f)
        a2R.set(40f, 473f, 360f, 495f); c.drawOval(a2R, a2F); a2F.maskFilter = null
        val t = A2Svg.path(A2Svg.TORSO[pose.coerceIn(0, 2)])
        a2Fill(c, t, Color.BLACK, a2SkinU); a2Fill(c, t, Color.BLACK, a2TorsoShade)
        a2Fill(c, t, Color.BLACK, a2ChestBand)   // 🎨 (27-nov-2026) pecho y hombros: el amarillo oscuro de abajo de la cabeza
    }

    /** La cabeza: esfera amarilla con sombra en el borde, reflejo abajo, mejillas y brillo arriba. */
    private fun a2BuildHead(c: Canvas) {
        a2F.color = Color.BLACK; a2F.shader = a2HeadSkin; c.drawCircle(200f, 204f, 150f, a2F)
        a2F.shader = a2HeadShade; c.drawCircle(200f, 204f, 150f, a2F); a2F.shader = null
        a2S.strokeWidth = 10f; a2S.color = a2c(0x66FFE27A); a2S.maskFilter = a2Blur(3.5f)
        a2R.set(58f, 62f, 342f, 346f); c.drawArc(a2R, 15f, 60f, false, a2S); a2S.maskFilter = null
        for (x in floatArrayOf(128f, 272f)) {
            c.save(); c.translate(x, 258f); c.scale(1f, 0.6f)
            a2F.color = Color.BLACK; a2F.shader = a2Cheek; c.drawCircle(0f, 0f, 30f, a2F); a2F.shader = null
            c.restore()
        }
        c.save(); c.rotate(-25f, 148f, 110f)
        a2F.color = a2c(0x80FFFFFF); a2F.maskFilter = a2Blur(6f); a2R.set(94f, 84f, 202f, 136f); c.drawOval(a2R, a2F)
        c.restore()
        c.save(); c.rotate(-30f, 122f, 98f)
        a2F.color = a2c(0xD9FFFFFF); a2F.maskFilter = a2Blur(2f); a2R.set(110f, 91f, 134f, 105f); c.drawOval(a2R, a2F)
        c.restore()
        a2F.maskFilter = null
    }

    /** 👓 Los lentes: marco azul marino con grosor, puente en arco, patitas, vidrio con reflejos. */
    private fun a2BuildGlasses(c: Canvas) {
        val navy = a2c(0xFF1C2440)
        a2Stroke(c, A2Svg.path("M182 188 Q200 160 220 188"), 9f, a2c(0xFF080C1C))
        for (x in floatArrayOf(94f, 218f)) {
            a2R.set(x, 152f, x + 88f, 260f); a2S.strokeWidth = 11f; a2S.color = a2c(0xCC080C1C); c.drawRoundRect(a2R, 26f, 26f, a2S)
        }
        for (x in floatArrayOf(94f, 218f)) {
            a2R.set(x, 148f, x + 88f, 256f)
            a2F.color = Color.BLACK; a2F.shader = a2Lens; c.drawRoundRect(a2R, 26f, 26f, a2F); a2F.shader = null
            a2S.strokeWidth = 11f; a2S.color = navy; c.drawRoundRect(a2R, 26f, 26f, a2S)
        }
        a2Stroke(c, A2Svg.path("M180 184 Q200 154 220 184"), 9f, navy)
        a2Stroke(c, A2Svg.path("M96 184 L 58 174"), 10f, navy)
        a2Stroke(c, A2Svg.path("M304 184 L 342 174"), 10f, navy)
        a2Stroke(c, A2Svg.path("M110 170 Q124 160 142 160"), 5f, a2c(0x8CFFFFFF))
        a2Stroke(c, A2Svg.path("M226 170 Q240 160 258 160"), 5f, a2c(0x8CFFFFFF))
        for (x in floatArrayOf(94f, 218f)) {
            a2R.set(x, 148f, x + 88f, 256f)
            a2S.strokeWidth = 3f; a2S.color = Color.BLACK
            a2S.shader = LinearGradient(x, 148f, x + 88f, 256f, intArrayOf(a2c(0xF29DB0E6), a2c(0x009DB0E6)), floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP)
            c.drawRoundRect(a2R, 26f, 26f, a2S); a2S.shader = null
            val dx = x - 94f
            a2Stroke(c, A2Svg.path("M${118f + dx} 244 L ${166f + dx} 166"), 10f, a2c(0x21FFFFFF))
            a2Stroke(c, A2Svg.path("M${140f + dx} 248 L ${172f + dx} 196"), 4f, a2c(0x21FFFFFF))
        }
    }

    /** La sombra que hace la cabeza sobre el pecho y los brazos. */
    private fun a2BuildShadow(c: Canvas) {
        a2F.color = a2c(0x668A4A00); a2F.maskFilter = a2Blur(6f)
        a2R.set(90f, 326f, 310f, 374f); c.drawOval(a2R, a2F); a2F.maskFilter = null
    }

    /** El brazo que piensa (la sombra de la cabeza no lo tapa: está delante). */
    private val a2ThinkArm: Path by lazy {
        val out = Path(); val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 48f }
        @Suppress("DEPRECATION") pt.getFillPath(A2Svg.path("M92 404 C 88 486, 156.4 396.7, 162.5 327"), out)
        out.addCircle(168f, 280f, 50f, Path.Direction.CW); out
    }

    /** Hace una imagen de esa parte del avatar al tamaño de la pantalla (una sola vez). */
    private fun a2Make(x0: Float, y0: Float, x1: Float, y1: Float, k: Float, draw: (Canvas) -> Unit): android.graphics.Bitmap? = runCatching {
        val bw = kotlin.math.ceil((x1 - x0) * k).toInt().coerceAtLeast(1)
        val bh = kotlin.math.ceil((y1 - y0) * k).toInt().coerceAtLeast(1)
        val b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.scale(k, k); cv.translate(-x0, -y0); draw(cv); b
    }.getOrNull()

    private fun a2Put(c: Canvas, b: android.graphics.Bitmap?, x0: Float, y0: Float, alpha: Int) {
        if (b == null || alpha <= 0 || a2K <= 0f) return
        a2Paint.alpha = alpha.coerceAtMost(255)
        a2Dst.set(x0, y0, x0 + b.width / a2K, y0 + b.height / a2K)
        c.drawBitmap(b, null, a2Dst, a2Paint)
    }

    private fun a2Ensure(pose: Int) {
        if (a2Back[pose] == null) a2Back[pose] = a2Make(20f, 300f, 380f, 515f, a2K) { a2BuildBack(it, pose) }
        if (a2Front[pose] == null) a2Front[pose] = a2Make(20f, 196f, 445f, 500f, a2K) { a2BuildFront(it, pose) }
    }

    /** Ojos, cejas y boca (se mueven en cada cuadro). */
    private fun a2Face(c: Canvas, fx: Float, fy: Float, now: Long) {
        c.save(); c.translate(fx, fy)
        // 🤨 cejas: gestos (lado de la nariz y de afuera por separado), ceja levantada a pedido y al hablar
        val lift = mouth * 3f
        val wv = a2Wave * 4f
        for (side in intArrayOf(-1, 1)) {
            val mb = (if (side < 0) gBrowL else gBrowR) * 16f
            val inX = if (side < 0) 172f else 228f; val outX = if (side < 0) 110f else 290f
            val inY = 128f - lift - eBIn * 900f - mb * 0.8f - wv
            val outY = 132f - lift - eBOut * 900f - mb - wv
            val cX = if (side < 0) 140f else 260f
            val cY = 116f - lift - (eBIn + eBOut) * 450f - mb - wv
            // 🤔 (27-nov-2026) pensando: las cejas toman la forma de la muestra aprobada (la izquierda bien arqueada
            //    arriba, la derecha baja y casi recta), como el emoji 🤔
            val th = a2Think
            val tOutX = if (side < 0) 108f else 292f; val tOutY = if (side < 0) 118f else 142f
            val tCX = if (side < 0) 140f else 260f;  val tCY = if (side < 0) 94f else 132f
            val tInX = if (side < 0) 174f else 228f; val tInY = if (side < 0) 112f else 138f
            path.reset()
            path.moveTo(outX + (tOutX - outX) * th, outY + (tOutY - outY) * th)
            path.quadTo(cX + (tCX - cX) * th, cY + (tCY - cY) * th, inX + (tInX - inX) * th, inY + (tInY - inY) * th)
            a2Stroke(c, path, 10f, a2c(0xFF6B3B10))
        }
        // 🎭 arrugas de la frente (sorpresa, miedo, tristeza) y entrecejo fruncido (enojo, asco)
        if (eFore > 0.05f) for (k in 0 until 3) {
            val wy = 96f - k * 14f
            path.reset(); path.moveTo(160f + k * 6f, wy + 3f); path.quadTo(200f, wy - 5f, 240f - k * 6f, wy + 3f)
            a2Stroke(c, path, 3f, Color.argb((eFore * (110 - k * 25)).toInt().coerceIn(0, 255), 160, 90, 10))
        }
        if (eBIn < -0.006f) {
            val a = (-eBIn * 5000f).toInt().coerceIn(0, 150)
            path.reset(); path.moveTo(194f, 118f); path.lineTo(197f, 136f); path.moveTo(206f, 118f); path.lineTo(203f, 136f)
            a2Stroke(c, path, 3f, Color.argb(a, 120, 60, 8))
        }
        // 👁️ ojos: miran (gazeX/gazeY), parpadean, guiñan, se entrecierran o se abren más
        for (side in intArrayOf(-1, 1)) {
            val ex = if (side < 0) 138f else 262f; val ey = 202f
            val rx = 32f; val ry = 36f * (1f + (-eLid).coerceAtLeast(0f) * 0.3f)
            a2Eye.reset(); a2Eye.addOval(ex - rx, ey - ry, ex + rx, ey + ry, Path.Direction.CW)
            c.save(); c.clipPath(a2Eye)
            c.save(); c.translate(ex, ey); a2F.color = Color.BLACK; a2F.shader = a2EyeWhite; c.drawCircle(0f, 0f, ry + 4f, a2F); a2F.shader = null; c.restore()
            val ix = ex - side * 4f + gazeX * 30f; val iy = ey + 4f + gazeY * 26f
            c.save(); c.translate(ix, iy)
            a2F.color = Color.BLACK; a2F.shader = a2Iris; c.drawCircle(0f, 0f, 19f, a2F); a2F.shader = null
            a2F.color = a2c(0xFF120802); c.drawCircle(0f, 0f, 9f, a2F)
            a2F.color = Color.WHITE; c.drawCircle(6f, -7f, 5f, a2F)
            a2F.color = a2c(0xCCFFFFFF); c.drawCircle(-5f, 8f, 2.6f, a2F)
            c.restore()
            val bl = max(blinkAmount(now), if (side > 0) gWinkR else gWinkL)
            val b = max(bl, eLid.coerceAtLeast(0f) * 0.75f)
            val ly = ey - ry + 2f * ry * 0.86f * b
            if (b > 0.01f) {
                path.reset(); path.moveTo(ex - rx - 2f, ey - ry - 2f); path.lineTo(ex + rx + 2f, ey - ry - 2f); path.lineTo(ex + rx + 2f, ly)
                path.quadTo(ex, ly + 8f * b, ex - rx - 2f, ly); path.close()
                a2Fill(c, path, Color.BLACK, a2HeadSkin)
            }
            val low = 0.22f * bl + eLow.coerceAtLeast(0f) * 0.35f
            if (low > 0.01f) {
                val ly2 = ey + ry - 2f * ry * low
                path.reset(); path.moveTo(ex - rx - 2f, ey + ry + 2f); path.lineTo(ex + rx + 2f, ey + ry + 2f); path.lineTo(ex + rx + 2f, ly2)
                path.quadTo(ex, ly2 - 6f, ex - rx - 2f, ly2); path.close()
                a2Fill(c, path, Color.BLACK, a2HeadSkin)
            }
            c.restore()
            // línea de pestañas (sigue al párpado)
            if (b > 0.03f) {
                val e = ((ly + 4f * b - ey) / ry).coerceIn(-1f, 1f)
                val hw = rx * kotlin.math.sqrt(max(0.05f, 1f - e * e))
                path.reset(); path.moveTo(ex - hw, ly + 2f * b); path.quadTo(ex, ly + 10f * b, ex + hw, ly + 2f * b)
                a2Stroke(c, path, 3.5f, Color.argb((min(1f, b * 4f) * 255).toInt(), 0x6B, 0x3B, 0x10))
            }
        }
        // 👄 boca: sonrisa, tristeza, abierta al hablar (lengua y, en los gestos que los enseñan, dientes)
        val mx = 200f + 16f * a2Think
        val opening = (eOpen * 1.15f + mouth * (1f - eOpen * 0.5f)).coerceIn(0f, 1.4f)
        val open = max(opening * (44f + eOpen * 12f), a2Wave * 24f)
        val mw = 24f * eMw + 16f * a2Wave - 4f * a2Think + opening * 6f
        val curve = (10f + eSmile * 20f) * (1f - a2Think) - 6f * a2Think
        val cy0 = 284f - eSmile * 7f - 12f * a2Wave + 4f * a2Think
        if (open < 1.5f) {
            path.reset(); path.moveTo(mx - mw, cy0); path.quadTo(mx, cy0 + curve, mx + mw, cy0)
            a2Stroke(c, path, 8f, a2c(0xFF7A3A08))
        } else {
            val topC = cy0 + curve * 0.35f * (1f - a2Wave)
            val botC = cy0 + max(curve, 0f) * (1f - a2Wave) + open * 2f
            path.reset(); path.moveTo(mx - mw, cy0); path.quadTo(mx, topC, mx + mw, cy0); path.quadTo(mx, botC, mx - mw, cy0); path.close()
            a2Fill(c, path, a2c(0xFF7A3A08))
            c.save(); c.clipPath(path)
            val topV = (cy0 + topC) / 2f; val botV = (cy0 + botC) / 2f
            if (eTeeth > 0.05f) {
                a2F.color = Color.argb((eTeeth.coerceIn(0f, 1f) * 235).toInt(), 255, 255, 255)
                c.drawRect(mx - mw, topV - 20f, mx + mw, topV + min(7f, (botV - topV) * 0.28f), a2F)
            }
            a2F.color = a2c(0xFFFF7A7A)
            a2R.set(mx - mw * 0.55f, botV - (botV - topV) * 0.45f, mx + mw * 0.55f, botV + 6f); c.drawOval(a2R, a2F)
            c.restore()
            a2Stroke(c, path, (8f - min(open, 8f) * 0.625f).coerceAtLeast(3f), a2c(0xFF7A3A08))
        }
        c.restore()
    }

    private fun drawIntellectual(c: Canvas, w: Float, h: Float, s: Float, cx: Float, cy: Float, t: Float, dt: Float) {
        val now = SystemClock.uptimeMillis()
        val k = s / 520f
        if (k <= 0f) return
        // (si cambia el tamaño de la pantalla, las imágenes se vuelven a hacer a ese tamaño)
        if (a2K == 0f || abs(k - a2K) > a2K * 0.01f) {
            a2K = k
            for (i in 0 until 3) { a2Back[i] = null; a2Front[i] = null }
            a2HeadBmp = null; a2GlassBmp = null; a2ShadowBmp = null; a2WaveHandBmp = null
        }
        // 🧍 pose: saludando (3 s al saludarlo) · pensando (mientras piensa) · normal. Cambia con un fundido suave.
        val pose = when { now < AvatarGesture.waveUntil -> 2; now < AvatarLook.thinkUntil -> 1; else -> 0 }
        if (pose != a2Pose) { a2PrevPose = a2Pose; a2Pose = pose; a2PoseAt = now; if (pose == 2) a2WaveStart = now }
        val f = if (a2PrevPose == a2Pose) 1f else ((now - a2PoseAt) / 350f).coerceIn(0f, 1f)
        val kw = 1f - exp(-dt * 7f)
        a2Think += ((if (pose == 1) 1f else 0f) - a2Think) * kw
        a2Wave += ((if (pose == 2) 1f else 0f) - a2Wave) * kw
        a2Ensure(a2Pose); if (f < 1f) a2Ensure(a2PrevPose)
        if (a2HeadBmp == null) a2HeadBmp = a2Make(40f, 44f, 360f, 364f, a2K) { a2BuildHead(it) }
        if (a2GlassBmp == null) a2GlassBmp = a2Make(46f, 136f, 354f, 268f, a2K) { a2BuildGlasses(it) }
        if (a2ShadowBmp == null) a2ShadowBmp = a2Make(60f, 300f, 340f, 400f, a2K) { a2BuildShadow(it) }

        drawFloaters(c, w, h, t, dt)
        drawRings(c, cx, cy - 16f * k, s, t, dt)
        // 👀 giro de la cara: los ojos, cejas, boca y lentes se corren hacia donde mira; la cabeza un poquito
        val fx = lookX * 30f; val fy = -lookY * 22.5f
        val aNew = (f * 255f).toInt(); val aOld = 255 - aNew
        c.save(); c.translate(cx, cy); c.scale(k, k); c.translate(-200f, -220f)
        if (f < 1f) a2Put(c, a2Back[a2PrevPose], 20f, 300f, 255)
        a2Put(c, a2Back[a2Pose], 20f, 300f, aNew)
        c.save(); c.translate(fx * 0.25f, fy * 0.25f); a2Put(c, a2HeadBmp, 40f, 44f, 255); c.restore()
        a2Face(c, fx, fy, now)
        // (las patitas de los lentes no se salen de la cabeza al girar: quedan «detrás»)
        c.save()
        a2Clip.reset(); a2Clip.addCircle(200f + fx * 0.25f, 204f + fy * 0.25f, 150f, Path.Direction.CW); c.clipPath(a2Clip)
        c.translate(fx, fy); a2Put(c, a2GlassBmp, 46f, 136f, 255)
        c.restore()
        if (f < 1f) a2Put(c, a2Front[a2PrevPose], 20f, 196f, aOld)
        a2Put(c, a2Front[a2Pose], 20f, 196f, aNew)
        // 🌑 sombra de la cabeza sobre el pecho y los brazos (no sobre la cabeza ni sobre el brazo que piensa)
        c.save()
        a2Clip.reset(); a2Clip.addCircle(200f + fx * 0.25f, 204f + fy * 0.25f, 150f, Path.Direction.CW)
        c.clipOutPath(a2Clip)
        if (a2Pose == 1 || (a2PrevPose == 1 && f < 1f)) c.clipOutPath(a2ThinkArm)
        a2Put(c, a2ShadowBmp, 60f, 300f, 255)
        c.restore()
        // 👋 la mano que saluda (de un lado a otro)
        val handA = when { a2Pose == 2 -> aNew; a2PrevPose == 2 && f < 1f -> aOld; else -> 0 }
        if (handA > 0) {
            val ang = -15f + 14f * sin((now - a2WaveStart) / 1000f * 2f * PI.toFloat() * 1.8f)
            if (handA < 255) c.saveLayerAlpha(null, handA)
            // ✋ (27-nov-2026) tu mano abierta (con su 3D), ya dibujada en una imagen: aquí solo se gira en la muñeca
            if (a2WaveHandBmp == null) a2WaveHandBmp = a2Make(a2WaveBox.left, a2WaveBox.top, a2WaveBox.right, a2WaveBox.bottom, a2K * 1.15f) {
                a2HandBody(it, a2OpenStyle, -15f, false,
                    android.graphics.Matrix().apply { setTranslate(391.6f, 285.6f); preRotate(-15f); preScale(1.15f, 1.15f) })
            }
            val wb = a2WaveHandBmp
            if (wb != null) {
                c.save(); c.translate(391.6f, 285.6f); c.rotate(ang); c.scale(1.15f, 1.15f)
                a2Paint.alpha = 255
                a2Dst.set(a2WaveBox.left, a2WaveBox.top, a2WaveBox.left + wb.width / (a2K * 1.15f), a2WaveBox.top + wb.height / (a2K * 1.15f))
                c.drawBitmap(wb, null, a2Dst, a2Paint)
                c.restore()
            }
            if (handA < 255) c.restore()
        }
        c.restore()
    }
}

/**
 * 🧑‍🚀 El avatar como FONDO del chat: queda detrás de los mensajes, botones y menús laterales,
 * así todo lo que está encima se puede tocar y deslizar normal. Se redibuja solo: 60 o 120 por segundo según
 * «Frecuencia del avatar», y 30 en el modo descanso (23-nov-2026).
 */
class AvatarDrawable(context: Context) : android.graphics.drawable.Drawable() {
    private val face = AvatarView(context).apply { drawOnly = true }
    /** Fracción del alto que usa la cara (1 = todo el chat, 0.45 = mitad de arriba con «Chat en pantalla»). */
    var heightFraction = 1f
        set(v) { field = v; invalidateSelf() }
    /** Alto fijo de la cara en píxeles (la franja de arriba del chat). 0 = se usa [heightFraction]. */
    var heightPx = 0
        set(v) { field = v; invalidateSelf() }
    /** 📱↔️ Teléfono de lado: la cara va en un cuadro a la DERECHA de este ancho (todo el alto). 0 = arriba, como siempre. */
    var sideWidthPx = 0
        set(v) { field = v; invalidateSelf() }
    private val tick = Runnable { invalidateSelf() }
    private var lastFps = -1
    private val appContext = context.applicationContext
    /** Los Hz que tiene la pantalla ahora (cambia con la frecuencia adaptable). */
    private fun displayHz(): Float = runCatching {
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).refreshRate
    }.getOrDefault(60f)
    private val roundClip = Path()
    private val density = context.resources.displayMetrics.density

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = sideWidthPx.coerceIn(0, b.width())
        val w = if (side > 0) side else b.width()
        val h = if (side > 0) b.height() else if (heightPx > 0) minOf(heightPx, b.height()) else (b.height() * heightFraction).toInt()
        if (w <= 0 || h <= 0) return
        if (face.width != w || face.height != h) face.layout(0, 0, w, h)
        val save = canvas.save()
        canvas.translate((if (side > 0) b.right - side else b.left).toFloat(), b.top.toFloat())
        // 🔲 El fondo del avatar tiene las esquinas redondeadas: en la franja de arriba, en el cuadro de la derecha
        //    (de lado) y también grande en el voz a voz
        val r = 16f * density
        roundClip.reset(); roundClip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        canvas.clipPath(roundClip)
        face.render(canvas)
        canvas.restoreToCount(save)
        // 🖥️ (23-nov-2026) si el avatar pasó a descanso (30) o volvió a estar activo (60/120), se avisa una sola vez
        val fps = face.currentFps()
        if (fps != lastFps) { lastFps = fps; runCatching { AvatarEffects.onFpsChange?.invoke(fps) } }
        unscheduleSelf(tick)
        unscheduleSelf(keepAlive)   // (sí se dibujó: el seguro no hace falta)
        // 🖥️😴 (23-nov-2026) el próximo cuadro según «Frecuencia del avatar» (60 o 120) y el modo descanso (30)
        // 🎞️ (24-nov-2026) ahora pegado al refresco de la pantalla (Choreographer), no a un reloj aparte: cada cuadro
        //    cae justo en su turno (antes, a veces uno duraba 25 ms en vez de 16 y se sentía un tironcito)
        postVsync()
    }

    // ---- 🎞️ (24-nov-2026) Cuadros pegados al refresco de la pantalla ----
    private val choreographer = runCatching { android.view.Choreographer.getInstance() }.getOrNull()
    private var vsyncPosted = false
    private var lastDrawVsyncNs = 0L
    private var hzCache = 60f
    private var hzCheckedNs = 0L
    private val vsync = android.view.Choreographer.FrameCallback { ns ->
        vsyncPosted = false
        if (ns - hzCheckedNs > 1_000_000_000L) { hzCheckedNs = ns; hzCache = displayHz().let { if (it > 1f) it else 60f } }
        val period = 1_000_000_000.0 / hzCache
        val want = 1_000_000_000.0 / face.currentFps().coerceAtLeast(1)
        // (toca dibujar si ya pasó el tiempo de un cuadro, con medio refresco de margen: 60 en 120 Hz = uno sí y uno no)
        if (lastDrawVsyncNs == 0L || ns - lastDrawVsyncNs >= want - period * 0.5 || ns - lastDrawVsyncNs < 0L) {
            lastDrawVsyncNs = ns
            face.frameClockMs = ns / 1_000_000L
            invalidateSelf()
            // 🛟 (25-nov-2026) Seguro: si este pedido de dibujo se pierde (la vista del avatar se movió o se rearmó
            //    mientras la IA responde), el avatar quedaba congelado para siempre. Si en 0,3 s no se dibujó, se pide
            //    de nuevo (y cada 0,5 s hasta que vuelva a dibujarse).
            unscheduleSelf(keepAlive)
            scheduleSelf(keepAlive, SystemClock.uptimeMillis() + 300)
        } else postVsync()
    }
    private val keepAlive: Runnable = object : Runnable {
        override fun run() {
            vsyncPosted = false   // (por si el aviso de la pantalla también se perdió)
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + 500)
        }
    }
    private fun postVsync() {
        val ch = choreographer
        if (ch == null) { scheduleSelf(tick, SystemClock.uptimeMillis() + face.frameDelayMs(displayHz())); return }
        if (!vsyncPosted) { vsyncPosted = true; ch.postFrameCallback(vsync) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** 🤓 (27-nov-2026) Avatar 2: lee los trazos (M, L, C, Q, Z) de la muestra aprobada y guarda el cuerpo de cada pose. */
private object A2Svg {
    private val TOK = Regex("[MLCQZmlcqz]|-?\\d*\\.?\\d+")
    fun path(d: String): Path {
        val p = Path()
        val tok = TOK.findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        fun n(): Float = tok[i++].toFloat()
        while (i < tok.size) {
            val t = tok[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0].uppercaseChar(); i++
                if (cmd == 'Z') { p.close() }
                continue
            }
            when (cmd) {
                'M' -> { p.moveTo(n(), n()); cmd = 'L' }
                'L' -> p.lineTo(n(), n())
                'C' -> p.cubicTo(n(), n(), n(), n(), n(), n())
                'Q' -> p.quadTo(n(), n(), n(), n())
                else -> i++
            }
        }
        return p
    }
    // ✋ (27-nov-2026) Manos dibujadas por ti (origen = la muñeca; los dedos hacia arriba)
    //    (pensando: los 3 dedos doblados se ven como nudillos en una línea ondulada que baja hacia el meñique)
    const val HAND_OPEN_FILL = "M20.9 9.0 C24.3 8.2 20.9 5.6 20.9 4.0 C20.8 2.4 20.6 0.8 20.4 -0.5 C20.3 -1.8 19.9 -1.7 20.0 -4.0 C20.1 -6.2 20.6 -10.4 21.1 -13.9 C21.6 -17.5 22.0 -21.8 23.0 -25.2 C24.0 -28.6 25.4 -30.0 27.1 -34.3 C28.9 -38.6 32.0 -47.4 33.4 -50.9 C34.8 -54.4 35.0 -54.0 35.5 -55.2 C36.0 -56.4 36.2 -57.2 36.3 -58.0 C36.5 -58.8 36.5 -59.3 36.4 -59.9 C36.3 -60.4 36.1 -60.9 35.8 -61.3 C35.5 -61.7 35.1 -62.0 34.6 -62.2 C34.2 -62.4 33.7 -62.6 33.2 -62.6 C32.7 -62.6 32.2 -62.5 31.5 -62.2 C30.9 -61.9 30.5 -62.2 29.3 -60.7 C28.2 -59.3 25.9 -54.8 24.6 -53.4 C23.3 -51.9 22.1 -52.1 21.4 -51.9 C20.7 -51.7 20.6 -52.0 20.3 -52.2 C20.0 -52.4 19.7 -52.5 19.6 -52.9 C19.4 -53.4 19.2 -53.0 19.3 -55.0 C19.4 -57.1 19.8 -62.6 20.2 -65.4 C20.6 -68.3 21.4 -70.0 21.8 -72.1 C22.2 -74.2 22.3 -76.5 22.7 -78.2 C23.0 -79.9 23.6 -80.9 23.8 -82.2 C24.1 -83.5 24.1 -84.9 24.1 -85.8 C24.1 -86.6 24.1 -87.0 24.0 -87.5 C23.8 -88.0 23.6 -88.4 23.3 -88.7 C23.0 -89.1 22.6 -89.4 22.1 -89.6 C21.7 -89.8 21.2 -90.0 20.6 -89.9 C19.9 -89.8 19.1 -89.6 18.4 -89.1 C17.8 -88.5 18.3 -90.5 16.8 -86.5 C15.2 -82.4 10.7 -69.0 9.2 -64.7 C7.6 -60.4 8.0 -61.9 7.4 -60.9 C6.9 -59.9 6.3 -59.2 5.9 -58.8 C5.5 -58.5 5.3 -58.6 5.0 -58.7 C4.8 -58.7 4.4 -58.8 4.1 -59.1 C3.8 -59.4 3.6 -59.5 3.4 -60.3 C3.2 -61.1 2.9 -61.5 2.8 -63.7 C2.8 -66.0 3.0 -71.6 3.1 -73.8 C3.2 -76.0 3.5 -74.5 3.6 -76.9 C3.7 -79.4 3.7 -86.3 3.6 -88.5 C3.5 -90.6 3.4 -89.6 3.1 -90.0 C2.9 -90.5 2.6 -90.9 2.2 -91.2 C1.8 -91.5 1.3 -91.8 0.8 -91.9 C0.3 -92.0 -0.2 -92.1 -0.8 -91.9 C-1.4 -91.7 -2.3 -91.4 -2.9 -90.6 C-3.5 -89.8 -3.8 -89.5 -4.3 -86.9 C-4.8 -84.3 -4.9 -79.2 -5.7 -74.8 C-6.6 -70.4 -8.8 -62.9 -9.6 -60.3 C-10.4 -57.8 -10.1 -59.6 -10.6 -59.4 C-11.1 -59.3 -11.9 -59.1 -12.4 -59.3 C-12.9 -59.6 -13.2 -59.2 -13.5 -60.9 C-13.9 -62.5 -14.1 -66.5 -14.5 -69.3 C-14.9 -72.1 -15.5 -75.4 -16.0 -77.7 C-16.5 -79.9 -17.1 -81.6 -17.6 -82.6 C-18.0 -83.7 -18.2 -83.5 -18.7 -83.8 C-19.2 -84.1 -20.0 -84.4 -20.7 -84.3 C-21.4 -84.3 -22.2 -84.1 -22.7 -83.7 C-23.2 -83.4 -23.6 -83.1 -23.9 -82.2 C-24.2 -81.3 -24.5 -83.4 -24.4 -78.2 C-24.3 -73.1 -23.3 -57.6 -23.1 -51.3 C-22.9 -45.1 -23.1 -43.0 -23.3 -41.0 C-23.5 -38.9 -23.7 -39.7 -24.1 -39.3 C-24.4 -38.8 -24.8 -38.5 -25.2 -38.5 C-25.7 -38.4 -25.6 -37.8 -26.6 -38.8 C-27.6 -39.8 -29.7 -43.0 -31.2 -44.6 C-32.7 -46.2 -34.4 -47.5 -35.5 -48.3 C-36.5 -49.1 -36.8 -49.2 -37.5 -49.4 C-38.2 -49.6 -39.0 -49.7 -39.8 -49.7 C-40.5 -49.6 -41.2 -49.2 -41.7 -48.8 C-42.2 -48.4 -42.6 -47.8 -42.7 -47.2 C-42.9 -46.6 -43.1 -46.1 -42.8 -45.1 C-42.5 -44.1 -42.3 -43.4 -40.9 -41.1 C-39.4 -38.8 -36.0 -34.3 -34.1 -31.1 C-32.2 -28.0 -31.0 -25.1 -29.3 -22.2 C-27.7 -19.2 -26.0 -16.3 -24.4 -13.4 C-22.8 -10.4 -20.4 -6.1 -19.5 -4.4 C-18.6 -2.7 -19.1 -3.9 -19.2 -3.3 C-19.2 -2.6 -19.7 -1.7 -20.0 -0.5 C-20.3 0.7 -20.7 2.4 -20.9 4.0 C-21.0 5.6 -24.3 8.2 -20.9 9.0 C-17.4 9.8 -7.0 9.0 0.0 9.0 C7.0 9.0 17.4 9.8 20.9 9.0 Z"
    const val HAND_OPEN_LINE = "M20.9 9.0 C20.9 8.2 20.9 5.6 20.9 4.0 C20.8 2.4 20.6 0.8 20.4 -0.5 C20.3 -1.8 19.9 -1.7 20.0 -4.0 C20.1 -6.2 20.6 -10.4 21.1 -13.9 C21.6 -17.5 22.0 -21.8 23.0 -25.2 C24.0 -28.6 25.4 -30.0 27.1 -34.3 C28.9 -38.6 32.0 -47.4 33.4 -50.9 C34.8 -54.4 35.0 -54.0 35.5 -55.2 C36.0 -56.4 36.2 -57.2 36.3 -58.0 C36.5 -58.8 36.5 -59.3 36.4 -59.9 C36.3 -60.4 36.1 -60.9 35.8 -61.3 C35.5 -61.7 35.1 -62.0 34.6 -62.2 C34.2 -62.4 33.7 -62.6 33.2 -62.6 C32.7 -62.6 32.2 -62.5 31.5 -62.2 C30.9 -61.9 30.5 -62.2 29.3 -60.7 C28.2 -59.3 25.9 -54.8 24.6 -53.4 C23.3 -51.9 22.1 -52.1 21.4 -51.9 C20.7 -51.7 20.6 -52.0 20.3 -52.2 C20.0 -52.4 19.7 -52.5 19.6 -52.9 C19.4 -53.4 19.2 -53.0 19.3 -55.0 C19.4 -57.1 19.8 -62.6 20.2 -65.4 C20.6 -68.3 21.4 -70.0 21.8 -72.1 C22.2 -74.2 22.3 -76.5 22.7 -78.2 C23.0 -79.9 23.6 -80.9 23.8 -82.2 C24.1 -83.5 24.1 -84.9 24.1 -85.8 C24.1 -86.6 24.1 -87.0 24.0 -87.5 C23.8 -88.0 23.6 -88.4 23.3 -88.7 C23.0 -89.1 22.6 -89.4 22.1 -89.6 C21.7 -89.8 21.2 -90.0 20.6 -89.9 C19.9 -89.8 19.1 -89.6 18.4 -89.1 C17.8 -88.5 18.3 -90.5 16.8 -86.5 C15.2 -82.4 10.7 -69.0 9.2 -64.7 C7.6 -60.4 8.0 -61.9 7.4 -60.9 C6.9 -59.9 6.3 -59.2 5.9 -58.8 C5.5 -58.5 5.3 -58.6 5.0 -58.7 C4.8 -58.7 4.4 -58.8 4.1 -59.1 C3.8 -59.4 3.6 -59.5 3.4 -60.3 C3.2 -61.1 2.9 -61.5 2.8 -63.7 C2.8 -66.0 3.0 -71.6 3.1 -73.8 C3.2 -76.0 3.5 -74.5 3.6 -76.9 C3.7 -79.4 3.7 -86.3 3.6 -88.5 C3.5 -90.6 3.4 -89.6 3.1 -90.0 C2.9 -90.5 2.6 -90.9 2.2 -91.2 C1.8 -91.5 1.3 -91.8 0.8 -91.9 C0.3 -92.0 -0.2 -92.1 -0.8 -91.9 C-1.4 -91.7 -2.3 -91.4 -2.9 -90.6 C-3.5 -89.8 -3.8 -89.5 -4.3 -86.9 C-4.8 -84.3 -4.9 -79.2 -5.7 -74.8 C-6.6 -70.4 -8.8 -62.9 -9.6 -60.3 C-10.4 -57.8 -10.1 -59.6 -10.6 -59.4 C-11.1 -59.3 -11.9 -59.1 -12.4 -59.3 C-12.9 -59.6 -13.2 -59.2 -13.5 -60.9 C-13.9 -62.5 -14.1 -66.5 -14.5 -69.3 C-14.9 -72.1 -15.5 -75.4 -16.0 -77.7 C-16.5 -79.9 -17.1 -81.6 -17.6 -82.6 C-18.0 -83.7 -18.2 -83.5 -18.7 -83.8 C-19.2 -84.1 -20.0 -84.4 -20.7 -84.3 C-21.4 -84.3 -22.2 -84.1 -22.7 -83.7 C-23.2 -83.4 -23.6 -83.1 -23.9 -82.2 C-24.2 -81.3 -24.5 -83.4 -24.4 -78.2 C-24.3 -73.1 -23.3 -57.6 -23.1 -51.3 C-22.9 -45.1 -23.1 -43.0 -23.3 -41.0 C-23.5 -38.9 -23.7 -39.7 -24.1 -39.3 C-24.4 -38.8 -24.8 -38.5 -25.2 -38.5 C-25.7 -38.4 -25.6 -37.8 -26.6 -38.8 C-27.6 -39.8 -29.7 -43.0 -31.2 -44.6 C-32.7 -46.2 -34.4 -47.5 -35.5 -48.3 C-36.5 -49.1 -36.8 -49.2 -37.5 -49.4 C-38.2 -49.6 -39.0 -49.7 -39.8 -49.7 C-40.5 -49.6 -41.2 -49.2 -41.7 -48.8 C-42.2 -48.4 -42.6 -47.8 -42.7 -47.2 C-42.9 -46.6 -43.1 -46.1 -42.8 -45.1 C-42.5 -44.1 -42.3 -43.4 -40.9 -41.1 C-39.4 -38.8 -36.0 -34.3 -34.1 -31.1 C-32.2 -28.0 -31.0 -25.1 -29.3 -22.2 C-27.7 -19.2 -26.0 -16.3 -24.4 -13.4 C-22.8 -10.4 -20.4 -6.1 -19.5 -4.4 C-18.6 -2.7 -19.1 -3.9 -19.2 -3.3 C-19.2 -2.6 -19.7 -1.7 -20.0 -0.5 C-20.3 0.7 -20.7 2.4 -20.9 4.0 C-21.0 5.6 -20.9 8.2 -20.9 9.0"
    const val HAND_POINT_FILL = "M18.2 9.0 C21.2 8.2 18.1 5.6 18.2 4.0 C18.3 2.4 18.6 2.5 18.8 -0.5 C19.0 -3.5 18.6 -9.8 19.3 -13.9 C20.1 -18.1 21.8 -22.1 23.1 -25.5 C24.4 -28.8 25.7 -30.9 27.1 -34.3 C28.6 -37.6 30.9 -43.4 31.6 -45.7 C32.4 -47.9 31.8 -46.5 31.7 -47.5 C31.5 -48.5 31.1 -50.4 30.6 -51.5 C30.2 -52.6 29.6 -53.6 28.9 -54.3 C28.3 -54.9 27.5 -55.1 26.7 -55.1 C25.9 -55.2 24.9 -54.9 24.1 -54.4 C23.3 -53.9 22.6 -52.5 22.0 -52.2 C21.4 -51.8 20.8 -52.0 20.4 -52.2 C20.0 -52.3 19.7 -52.4 19.4 -53.3 C19.1 -54.2 18.9 -56.5 18.5 -57.5 C18.1 -58.4 17.7 -58.5 17.1 -58.8 C16.6 -59.2 16.2 -59.4 15.4 -59.6 C14.7 -59.7 13.5 -59.9 12.7 -59.9 C11.9 -59.9 11.6 -59.9 10.6 -59.5 C9.6 -59.1 7.8 -57.8 6.8 -57.5 C5.7 -57.3 4.9 -57.8 4.3 -58.0 C3.8 -58.2 3.6 -58.2 3.5 -58.6 C3.3 -59.1 3.4 -59.9 3.2 -60.4 C3.0 -61.0 2.7 -61.3 2.3 -61.7 C1.9 -62.1 1.4 -62.4 0.8 -62.7 C0.2 -62.9 -0.5 -63.2 -1.2 -63.3 C-1.9 -63.4 -2.4 -63.5 -3.2 -63.4 C-4.0 -63.4 -5.0 -63.2 -5.8 -63.0 C-6.5 -62.8 -7.0 -62.6 -7.8 -62.0 C-8.5 -61.4 -9.7 -59.9 -10.5 -59.5 C-11.3 -59.1 -12.1 -59.3 -12.6 -59.4 C-13.0 -59.5 -13.1 -59.8 -13.3 -60.2 C-13.5 -60.5 -13.5 -60.1 -13.7 -61.7 C-14.0 -63.3 -14.2 -67.2 -14.6 -69.8 C-14.9 -72.5 -15.5 -75.6 -16.0 -77.7 C-16.5 -79.7 -17.1 -81.4 -17.4 -82.3 C-17.8 -83.3 -17.9 -83.1 -18.2 -83.4 C-18.5 -83.7 -18.9 -83.9 -19.3 -84.1 C-19.7 -84.3 -20.2 -84.4 -20.7 -84.3 C-21.2 -84.3 -21.6 -84.3 -22.1 -84.0 C-22.6 -83.8 -23.3 -83.3 -23.7 -82.6 C-24.1 -82.0 -24.5 -84.9 -24.4 -80.1 C-24.3 -75.3 -23.3 -60.3 -23.1 -53.9 C-22.9 -47.5 -23.2 -43.9 -23.3 -41.5 C-23.3 -39.2 -23.3 -40.5 -23.6 -40.0 C-23.9 -39.5 -24.5 -38.8 -25.0 -38.6 C-25.4 -38.4 -25.5 -37.7 -26.5 -38.7 C-27.5 -39.7 -29.7 -43.0 -31.2 -44.6 C-32.8 -46.3 -34.6 -47.7 -35.8 -48.5 C-37.0 -49.4 -37.6 -49.5 -38.5 -49.6 C-39.3 -49.8 -40.1 -49.6 -40.7 -49.4 C-41.4 -49.2 -41.9 -48.7 -42.3 -48.2 C-42.6 -47.7 -42.9 -46.9 -42.9 -46.2 C-42.9 -45.5 -44.8 -48.0 -42.4 -43.9 C-40.0 -39.9 -32.0 -27.3 -28.7 -22.2 C-25.3 -17.1 -24.3 -16.3 -22.3 -13.4 C-20.3 -10.4 -17.7 -6.1 -16.7 -4.4 C-15.7 -2.7 -16.2 -3.9 -16.3 -3.3 C-16.4 -2.6 -16.9 -1.7 -17.2 -0.5 C-17.6 0.7 -18.0 2.4 -18.2 4.0 C-18.3 5.6 -21.2 8.2 -18.2 9.0 C-15.2 9.8 -6.1 9.0 0.0 9.0 C6.1 9.0 15.2 9.8 18.2 9.0 Z"
    const val HAND_POINT_LINE = "M18.2 9.0 C18.2 8.2 18.1 5.6 18.2 4.0 C18.3 2.4 18.6 2.5 18.8 -0.5 C19.0 -3.5 18.6 -9.8 19.3 -13.9 C20.1 -18.1 21.8 -22.1 23.1 -25.5 C24.4 -28.8 25.7 -30.9 27.1 -34.3 C28.6 -37.6 30.9 -43.4 31.6 -45.7 C32.4 -47.9 31.8 -46.5 31.7 -47.5 C31.5 -48.5 31.1 -50.4 30.6 -51.5 C30.2 -52.6 29.6 -53.6 28.9 -54.3 C28.3 -54.9 27.5 -55.1 26.7 -55.1 C25.9 -55.2 24.9 -54.9 24.1 -54.4 C23.3 -53.9 22.6 -52.5 22.0 -52.2 C21.4 -51.8 20.8 -52.0 20.4 -52.2 C20.0 -52.3 19.7 -52.4 19.4 -53.3 C19.1 -54.2 18.9 -56.5 18.5 -57.5 C18.1 -58.4 17.7 -58.5 17.1 -58.8 C16.6 -59.2 16.2 -59.4 15.4 -59.6 C14.7 -59.7 13.5 -59.9 12.7 -59.9 C11.9 -59.9 11.6 -59.9 10.6 -59.5 C9.6 -59.1 7.8 -57.8 6.8 -57.5 C5.7 -57.3 4.9 -57.8 4.3 -58.0 C3.8 -58.2 3.6 -58.2 3.5 -58.6 C3.3 -59.1 3.4 -59.9 3.2 -60.4 C3.0 -61.0 2.7 -61.3 2.3 -61.7 C1.9 -62.1 1.4 -62.4 0.8 -62.7 C0.2 -62.9 -0.5 -63.2 -1.2 -63.3 C-1.9 -63.4 -2.4 -63.5 -3.2 -63.4 C-4.0 -63.4 -5.0 -63.2 -5.8 -63.0 C-6.5 -62.8 -7.0 -62.6 -7.8 -62.0 C-8.5 -61.4 -9.7 -59.9 -10.5 -59.5 C-11.3 -59.1 -12.1 -59.3 -12.6 -59.4 C-13.0 -59.5 -13.1 -59.8 -13.3 -60.2 C-13.5 -60.5 -13.5 -60.1 -13.7 -61.7 C-14.0 -63.3 -14.2 -67.2 -14.6 -69.8 C-14.9 -72.5 -15.5 -75.6 -16.0 -77.7 C-16.5 -79.7 -17.1 -81.4 -17.4 -82.3 C-17.8 -83.3 -17.9 -83.1 -18.2 -83.4 C-18.5 -83.7 -18.9 -83.9 -19.3 -84.1 C-19.7 -84.3 -20.2 -84.4 -20.7 -84.3 C-21.2 -84.3 -21.6 -84.3 -22.1 -84.0 C-22.6 -83.8 -23.3 -83.3 -23.7 -82.6 C-24.1 -82.0 -24.5 -84.9 -24.4 -80.1 C-24.3 -75.3 -23.3 -60.3 -23.1 -53.9 C-22.9 -47.5 -23.2 -43.9 -23.3 -41.5 C-23.3 -39.2 -23.3 -40.5 -23.6 -40.0 C-23.9 -39.5 -24.5 -38.8 -25.0 -38.6 C-25.4 -38.4 -25.5 -37.7 -26.5 -38.7 C-27.5 -39.7 -29.7 -43.0 -31.2 -44.6 C-32.8 -46.3 -34.6 -47.7 -35.8 -48.5 C-37.0 -49.4 -37.6 -49.5 -38.5 -49.6 C-39.3 -49.8 -40.1 -49.6 -40.7 -49.4 C-41.4 -49.2 -41.9 -48.7 -42.3 -48.2 C-42.6 -47.7 -42.9 -46.9 -42.9 -46.2 C-42.9 -45.5 -44.8 -48.0 -42.4 -43.9 C-40.0 -39.9 -32.0 -27.3 -28.7 -22.2 C-25.3 -17.1 -24.3 -16.3 -22.3 -13.4 C-20.3 -10.4 -17.7 -6.1 -16.7 -4.4 C-15.7 -2.7 -16.2 -3.9 -16.3 -3.3 C-16.4 -2.6 -16.9 -1.7 -17.2 -0.5 C-17.6 0.7 -18.0 2.4 -18.2 4.0 C-18.3 5.6 -18.2 8.2 -18.2 9.0"
    const val HAND_FIST_FILL = "M19.2 9.0 C22.4 8.2 19.2 5.6 19.2 4.0 C19.2 2.4 19.2 0.8 19.2 -0.5 C19.2 -1.8 19.0 -1.7 19.3 -3.7 C19.5 -5.6 20.1 -9.0 20.9 -12.1 C21.6 -15.1 21.9 -17.0 23.6 -22.0 C25.4 -26.9 29.9 -37.9 31.2 -41.8 C32.6 -45.6 31.6 -44.2 31.6 -45.1 C31.6 -46.0 31.5 -46.4 31.0 -47.1 C30.5 -47.8 29.9 -48.9 28.9 -49.4 C27.8 -50.0 25.6 -50.0 24.6 -50.5 C23.6 -51.0 23.8 -51.7 22.6 -52.5 C21.5 -53.2 20.1 -54.0 17.9 -54.8 C15.6 -55.7 12.7 -56.7 9.1 -57.5 C5.4 -58.2 -0.3 -59.0 -4.1 -59.2 C-8.0 -59.4 -12.0 -58.9 -14.0 -58.8 C-16.0 -58.6 -15.6 -58.5 -16.2 -58.2 C-16.8 -57.9 -16.9 -58.0 -17.4 -57.2 C-18.0 -56.3 -19.3 -55.4 -19.8 -53.1 C-20.2 -50.9 -19.5 -45.4 -20.1 -43.5 C-20.7 -41.5 -22.4 -42.7 -23.3 -41.4 C-24.1 -40.1 -24.7 -37.3 -25.0 -35.5 C-25.3 -33.8 -25.5 -34.8 -25.0 -30.8 C-24.4 -26.8 -22.5 -16.8 -21.8 -11.7 C-21.0 -6.7 -20.9 -3.1 -20.5 -0.5 C-20.1 2.1 -19.4 2.4 -19.2 4.0 C-19.0 5.6 -22.4 8.2 -19.2 9.0 C-16.0 9.8 -6.4 9.0 0.0 9.0 C6.4 9.0 16.0 9.8 19.2 9.0 Z"
    const val HAND_FIST_LINE = "M19.2 9.0 C19.2 8.2 19.2 5.6 19.2 4.0 C19.2 2.4 19.2 0.8 19.2 -0.5 C19.2 -1.8 19.0 -1.7 19.3 -3.7 C19.5 -5.6 20.1 -9.0 20.9 -12.1 C21.6 -15.1 21.9 -17.0 23.6 -22.0 C25.4 -26.9 29.9 -37.9 31.2 -41.8 C32.6 -45.6 31.6 -44.2 31.6 -45.1 C31.6 -46.0 31.5 -46.4 31.0 -47.1 C30.5 -47.8 29.9 -48.9 28.9 -49.4 C27.8 -50.0 25.6 -50.0 24.6 -50.5 C23.6 -51.0 23.8 -51.7 22.6 -52.5 C21.5 -53.2 20.1 -54.0 17.9 -54.8 C15.6 -55.7 12.7 -56.7 9.1 -57.5 C5.4 -58.2 -0.3 -59.0 -4.1 -59.2 C-8.0 -59.4 -12.0 -58.9 -14.0 -58.8 C-16.0 -58.6 -15.6 -58.5 -16.2 -58.2 C-16.8 -57.9 -16.9 -58.0 -17.4 -57.2 C-18.0 -56.3 -19.3 -55.4 -19.8 -53.1 C-20.2 -50.9 -19.5 -45.4 -20.1 -43.5 C-20.7 -41.5 -22.4 -42.7 -23.3 -41.4 C-24.1 -40.1 -24.7 -37.3 -25.0 -35.5 C-25.3 -33.8 -25.5 -34.8 -25.0 -30.8 C-24.4 -26.8 -22.5 -16.8 -21.8 -11.7 C-21.0 -6.7 -20.9 -3.1 -20.5 -0.5 C-20.1 2.1 -19.4 2.4 -19.2 4.0 C-19.0 5.6 -19.2 8.2 -19.2 9.0"
    const val HAND_FIST_DASH = "M-14.4 -54.1 L-14.3 -48.2 M-3.2 -53.9 L-3.9 -49.5 M10.7 -51.9 L9.8 -47.6 M21.2 -46.7 L20.1 -42.9"
    const val HAND_FIST_THUMB = "M-19.0 -48.6 Q-18.6 -41.2 -19.3 -34.5"
    /** Cuerpo: 0 normal · 1 pensando · 2 saludando. */
    val TORSO = arrayOf(
        "M150 326 C 118 332, 84 358, 70.1 391.9 L68.2 395.5 L66.4 399.1 L64.8 402.7 L63.4 406.3 L62.1 410.0 L61.0 413.6 L60.1 417.4 L59.4 421.2 L58.9 425.1 L58.8 429.1 L58.9 433.2 L59.4 437.3 L60.3 441.4 L61.6 445.5 L63.3 449.5 L65.4 453.3 L67.9 456.9 L70.7 460.2 L73.7 463.1 L76.9 465.7 L80.2 468.0 L83.7 470.0 L87.2 471.7 L90.8 473.2 L94.5 474.4 L98.2 475.5 L101.9 476.3 L105.8 477.1 L109.7 477.6 L113.7 478.1 L117.7 478.4 L121.9 478.7 L126.2 478.8 L130.6 478.8 L135.1 478.7 L139.7 478.5 L144.5 478.2 L149.4 477.8 L154.4 477.4 L159.6 476.8 L 240.4 476.8 L245.6 477.4 L250.6 477.8 L255.5 478.2 L260.3 478.5 L264.9 478.7 L269.4 478.8 L273.8 478.8 L278.1 478.7 L282.3 478.4 L286.3 478.1 L290.3 477.6 L294.2 477.1 L298.1 476.3 L301.8 475.5 L305.5 474.4 L309.2 473.2 L312.8 471.7 L316.3 470.0 L319.8 468.0 L323.1 465.7 L326.3 463.1 L329.3 460.2 L332.1 456.9 L334.6 453.3 L336.7 449.5 L338.4 445.5 L339.7 441.4 L340.6 437.3 L341.1 433.2 L341.2 429.1 L341.1 425.1 L340.6 421.2 L339.9 417.4 L339.0 413.6 L337.9 410.0 L336.6 406.3 L335.2 402.7 L333.6 399.1 L331.8 395.5 L329.9 391.9 C 316 358, 282 332, 250 326 Z",
        "M150 326 C 118 332, 84 358, 70.1 391.9 L68.2 395.5 L66.4 399.1 L64.8 402.7 L63.4 406.3 L62.1 410.0 L61.0 413.6 L60.1 417.4 L59.4 421.2 L58.9 425.1 L58.8 429.1 L58.9 433.2 L59.4 437.3 L66.0 452.0 L82.0 466.0 L110.0 474.0 L150.0 478.0 L199.0 478.0 L 240.4 476.8 L245.6 477.4 L250.6 477.8 L255.5 478.2 L260.3 478.5 L264.9 478.7 L269.4 478.8 L273.8 478.8 L278.1 478.7 L282.3 478.4 L286.3 478.1 L290.3 477.6 L294.2 477.1 L298.1 476.3 L301.8 475.5 L305.5 474.4 L309.2 473.2 L312.8 471.7 L316.3 470.0 L319.8 468.0 L323.1 465.7 L326.3 463.1 L329.3 460.2 L332.1 456.9 L334.6 453.3 L336.7 449.5 L338.4 445.5 L339.7 441.4 L340.6 437.3 L341.1 433.2 L341.2 429.1 L341.1 425.1 L340.6 421.2 L339.9 417.4 L339.0 413.6 L337.9 410.0 L336.6 406.3 L335.2 402.7 L333.6 399.1 L331.8 395.5 L329.9 391.9 C 316 358, 282 332, 250 326 Z",
        "M150 326 C 118 332, 84 358, 70.1 391.9 L68.2 395.5 L66.4 399.1 L64.8 402.7 L63.4 406.3 L62.1 410.0 L61.0 413.6 L60.1 417.4 L59.4 421.2 L58.9 425.1 L58.8 429.1 L58.9 433.2 L59.4 437.3 L60.3 441.4 L61.6 445.5 L63.3 449.5 L65.4 453.3 L67.9 456.9 L70.7 460.2 L73.7 463.1 L76.9 465.7 L80.2 468.0 L83.7 470.0 L87.2 471.7 L90.8 473.2 L94.5 474.4 L98.2 475.5 L101.9 476.3 L105.8 477.1 L109.7 477.6 L113.7 478.1 L117.7 478.4 L121.9 478.7 L126.2 478.8 L130.6 478.8 L135.1 478.7 L139.7 478.5 L144.5 478.2 L149.4 477.8 L154.4 477.4 L159.6 476.8 L 201.0 478.0 L250.0 478.0 L290.0 474.0 L318.0 466.0 L334.0 452.0 L340.6 437.3 L341.1 433.2 L341.2 429.1 L341.1 425.1 L340.6 421.2 L339.9 417.4 L339.0 413.6 L337.9 410.0 L336.6 406.3 L335.2 402.7 L333.6 399.1 L331.8 395.5 L329.9 391.9 C 316 358, 282 332, 250 326 Z"
    )
}
