package com.paulrj.cumulusia.ai

import java.text.Normalizer

/**
 * 🖼️ Entiende lo que pides en el chat para editar una imagen:
 *  recortar, agrandar / achicar, subir o bajar la resolución (con IA), cambiar el fondo
 *  (color, transparente, borroso u otra foto), quitar a una persona o cambiarla por la de otra foto,
 *  y crear una versión nueva de la misma imagen con IA («otra versión», «estilo anime», «de noche»).
 *  Se pueden pedir varias cosas juntas: «quítale el fondo y recórtala cuadrada».
 */
object ImageEditCommand {

    enum class Kind { CROP, BACKGROUND, UPSCALE, DOWNSCALE, REMOVE_PERSON, REPLACE_PERSON, VARIATION, ASK_SECOND_IMAGE, ASK_BACKGROUND }
    enum class CropMode { AUTO, SUBJECT, RATIO, PERCENT, TOP, BOTTOM, LEFT, RIGHT, CENTER }
    enum class BgMode { COLOR, TRANSPARENT, BLUR, IMAGE }

    data class Step(
        val kind: Kind,
        // ✂️ Recorte
        val cropMode: CropMode = CropMode.AUTO,
        val ratioW: Int = 0, val ratioH: Int = 0,
        val percent: Int = 10,
        // 🎨 Fondo
        val bgMode: BgMode = BgMode.COLOR,
        val color: Int = 0xFFFFFFFF.toInt(),
        val colorName: String = "blanco",
        // 📐 Tamaño / resolución (0 = no se pidió)
        val factor: Float = 0f,
        val longSide: Int = 0,
        val exactW: Int = 0, val exactH: Int = 0,
        // 🔄 Personas: true = la persona nueva está en la PRIMERA foto · allSubjects = «quita a TODAS las personas»
        val swapImages: Boolean = false,
        val allSubjects: Boolean = false,
        // 🪄 Versión nueva con IA: qué pediste (en inglés para la IA), en palabras para el chat, y cuánto cambia (0..1)
        val prompt: String = "",
        val what: String = "",
        val strength: Float = 0.5f
    ) {
        /** true = este paso usa DOS fotos (la segunda es la persona o el fondo nuevo). */
        val needsTwoImages: Boolean get() = kind == Kind.REPLACE_PERSON || (kind == Kind.BACKGROUND && bgMode == BgMode.IMAGE)
    }

    /** Quita tildes y pasa a minúsculas («Recórtala» → «recortala»). */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace('×', 'x').replace(Regex("[¿¡!?\"«»“”]"), " ").replace(Regex("\\s+"), " ").trim()

    private const val PERSON = "(persona|personas|gente|hombre|hombres|mujer|mujeres|nin[oa]s?|chic[oa]s?|senor|senora|joven|tipo|tipa|personaje|individuo|caballero|dama|muchach[oa]s?)"

    // ---------------------------------------------------------------- colores
    private class NamedColor(val re: Regex, val argb: Int, val name: String)

    private val colors: List<NamedColor> = listOf(
        "azul (marino|oscuro)" to (0xFF0D1B4AL to "azul marino"),
        "celeste|azul (claro|cielo|bebe)" to (0xFF81D4FAL to "celeste"),
        "verde (oscuro|bosque|militar)" to (0xFF1B5E20L to "verde oscuro"),
        "verde (claro|agua|menta|pastel)" to (0xFFA5D6A7L to "verde claro"),
        "gris (claro|perla)" to (0xFFE0E0E0L to "gris claro"),
        "gris (oscuro|carbon|grafito)" to (0xFF424242L to "gris oscuro"),
        "rojo (oscuro|vino)|burdeo|vinotinto|granate" to (0xFF7B1F2BL to "burdeo"),
        "(chroma|croma|pantalla) ?verde|verde (croma|chroma)" to (0xFF00B140L to "verde croma"),
        "blanc[oa]s?" to (0xFFFFFFFFL to "blanco"),
        "negr[oa]s?" to (0xFF000000L to "negro"),
        "roj[oa]s?" to (0xFFE53935L to "rojo"),
        "azul(es)?" to (0xFF1E88E5L to "azul"),
        "verdes?" to (0xFF43A047L to "verde"),
        "amarill[oa]s?" to (0xFFFDD835L to "amarillo"),
        "naranj[oa]s?|anaranjad[oa]" to (0xFFFB8C00L to "naranjo"),
        "rosad[oa]s?|rosas?|rosita" to (0xFFF48FB1L to "rosado"),
        "fucsia|magenta" to (0xFFE91E63L to "fucsia"),
        "morad[oa]s?|violeta|purpura" to (0xFF8E24AAL to "morado"),
        "lila|lavanda" to (0xFFCE93D8L to "lila"),
        "gris(es)?" to (0xFF9E9E9EL to "gris"),
        "cafe|marron|chocolate" to (0xFF6D4C41L to "café"),
        "beige|crema|hueso|marfil" to (0xFFF5F0E1L to "beige"),
        "dorad[oa]|oro" to (0xFFD4AF37L to "dorado"),
        "platead[oa]|plata" to (0xFFC0C0C0L to "plateado"),
        "turquesa|aguamarina" to (0xFF26C6DAL to "turquesa")
    ).map { (re, v) -> NamedColor(Regex("\\b($re)\\b"), v.first.toInt(), v.second) }

    private class ColorHit(val start: Int, val end: Int, val argb: Int, val name: String)

    /** Todos los colores del texto, en orden (los de dos palabras ganan: «azul marino» antes que «azul»). */
    private fun colorHits(p: String): List<ColorHit> {
        val hits = mutableListOf<ColorHit>()
        Regex("#([0-9a-f]{6})\\b").findAll(p).forEach {
            hits += ColorHit(it.range.first, it.range.last + 1, (0xFF000000L or it.groupValues[1].toLong(16)).toInt(), "#" + it.groupValues[1].uppercase())
        }
        for (c in colors) for (m in c.re.findAll(p)) {
            if (hits.none { m.range.first < it.end && m.range.last + 1 > it.start }) hits += ColorHit(m.range.first, m.range.last + 1, c.argb, c.name)
        }
        return hits.sortedBy { it.start }
    }

    /** El color que pides para el fondo nuevo (no el que tiene ahora). null = no dijiste color. */
    fun targetColor(p: String): Pair<Int, String>? {
        val hits = colorHits(p)
        if (hits.isEmpty()) return null
        val marker = Regex("\\b(a|al|por|en|hacia|sea|quede|ponlo|ponla|ponle|ponme|pon|ponga|dejalo|dejala|dejale|hazlo|hazla|hazle|usa|dale|con|uno|otro)\\s+((un|una|el|la|uno|color|de|fondo)\\s+)*$")
        // 1) «… a blanco», «… por uno rojo», «ponlo azul»
        hits.lastOrNull { marker.containsMatchIn(p.substring(0, it.start)) }?.let { return it.argb to it.name }
        // 2) Sin palabras de cambiar/quitar («fondo blanco», «ponle fondo azul»): el color que va con «fondo»
        val changeOrRemove = Regex("\\b(cambi\\w*|reemplaz\\w*|remplaz\\w*|sustitu\\w*|quit\\w*|borr\\w*|elimin\\w*|sac\\w*|remueve)\\b").containsMatchIn(p)
        if (!changeOrRemove) return hits.first().let { it.argb to it.name }
        // 3) «cambia el fondo negro» sin decir a qué color: ese color es el de AHORA → no hay color nuevo
        return null
    }

    // ---------------------------------------------------------------- tamaños
    private fun namedLongSide(p: String): Int = when {
        Regex("\\b(8k|4320p?)\\b").containsMatchIn(p) -> 4096           // tope del teléfono
        Regex("\\b(4k|uhd|2160p?|ultra ?hd)\\b").containsMatchIn(p) -> 3840
        Regex("\\b(2k|1440p?|qhd)\\b").containsMatchIn(p) -> 2560
        Regex("\\b(full ?hd|fhd|1080p?)\\b").containsMatchIn(p) -> 1920
        Regex("\\b(hd|720p?)\\b").containsMatchIn(p) -> 1280
        Regex("\\b(480p?)\\b").containsMatchIn(p) -> 854
        Regex("\\b360p\\b").containsMatchIn(p) -> 640
        else -> 0
    }

    private fun factorIn(p: String): Float {
        Regex("\\bx\\s?([1-8](?:[.,]5)?)\\b|\\b([1-8](?:[.,]5)?)\\s?x\\b|\\b([1-8](?:[.,]5)?)\\s?veces\\b").find(p)?.let { m ->
            val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }.replace(',', '.').toFloatOrNull()
            if (v != null && v > 0f) return v
        }
        return when {
            Regex("\\b(cuadruple|cuatro veces)\\b").containsMatchIn(p) -> 4f
            Regex("\\b(triple|tres veces)\\b").containsMatchIn(p) -> 3f
            Regex("\\b(doble|dos veces|duplica\\w*)\\b").containsMatchIn(p) -> 2f
            Regex("\\b(la mitad|mitad del tamano)\\b").containsMatchIn(p) -> 0.5f
            Regex("\\b(un|a un) cuarto\\b").containsMatchIn(p) -> 0.25f
            else -> 0f
        }
    }

    private fun exactSize(p: String): Pair<Int, Int>? =
        Regex("\\b(\\d{2,5})\\s?x\\s?(\\d{2,5})\\b").find(p)?.let { m ->
            val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
            if (w >= 33 && h >= 33) w to h else null
        }

    private fun percentIn(p: String): Int? =
        Regex("\\b(\\d{1,3})\\s?(%|por ?ciento)").find(p)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..800 }

    // ---------------------------------------------------------------- 🪄 versión nueva con IA
    private class Phrase(val re: Regex, val en: String, val es: String)

    private fun phrases(vararg items: Triple<String, String, String>) =
        items.map { (re, en, es) -> Phrase(Regex("\\b($re)\\b"), en, es) }

    /** Estilos («estilo anime», «conviértela en pintura»). */
    private val styles = phrases(
        Triple("anime|manga", "anime style illustration", "estilo anime"),
        Triple("caricatura|dibujo animado|dibujos animados|cartoon|comic|historieta", "cartoon illustration, comic style", "estilo caricatura"),
        Triple("pixar|disney|3d|tridimensional|animacion 3d", "3d animated movie style render", "estilo 3D"),
        Triple("acuarela", "watercolor painting", "acuarela"),
        Triple("oleo|pintura al oleo", "oil painting", "pintura al óleo"),
        Triple("van gogh", "painting in the style of van gogh", "estilo Van Gogh"),
        Triple("pintura|cuadro|pintad[oa]|artistic[oa]", "artistic painting", "pintura"),
        Triple("lapiz|boceto|sketch|dibujo a mano|dibujo(?! animad)", "pencil sketch drawing", "dibujo a lápiz"),
        Triple("pixel art|pixelad[oa]|8 ?bits|retro gamer", "pixel art", "pixel art"),
        Triple("cyberpunk|futurista|neon", "cyberpunk, neon lights, futuristic", "futurista"),
        Triple("fantasia|magic[oa]|fantastic[oa]|de cuento", "fantasy art, magical atmosphere", "fantasía"),
        Triple("realista|foto real|fotorrealista|hiperrealista|realismo", "photorealistic, realistic photo", "realista"),
        Triple("vintage|retro|antigua|antiguo|ochentera|de los 80|anos 80", "vintage retro photo, film grain", "vintage"),
        Triple("cinematografic[oa]|de pelicula|cine", "cinematic lighting, movie still", "de película"),
        Triple("steampunk", "steampunk style", "steampunk"),
        Triple("lego", "made of lego bricks", "de Lego"),
        Triple("arcilla|plastilina", "claymation, clay figure", "de plastilina")
    )

    /** Escenas («de noche», «con nieve»). */
    private val scenes = phrases(
        Triple("de noche|noche|nocturn[oa]", "at night, night time", "de noche"),
        Triple("atardecer|puesta de sol|anochecer", "at sunset, golden hour", "al atardecer"),
        Triple("amanecer", "at sunrise", "al amanecer"),
        Triple("de dia|soleado|soleada|con sol", "sunny day", "de día"),
        Triple("invierno|nieve|nevad[oa]|nevando", "winter, snow", "con nieve"),
        Triple("lluvia|lloviendo|lluvios[oa]", "rainy weather, rain", "con lluvia"),
        Triple("otono", "autumn, fall leaves", "de otoño"),
        Triple("primavera", "spring, flowers", "de primavera"),
        Triple("verano", "summer", "de verano"),
        Triple("navidad|navidena|navideno", "christmas decorations", "navideña"),
        Triple("halloween", "halloween decorations", "de Halloween"),
        Triple("playa|mar|oceano", "beach, ocean", "en la playa"),
        Triple("montana|montanas|cordillera", "mountains", "en la montaña"),
        Triple("bosque|selva", "forest", "en el bosque"),
        Triple("ciudad|urbana|urbano", "city", "en la ciudad"),
        Triple("espacio|galaxia|universo|estrellas", "outer space, galaxy, stars", "en el espacio"),
        Triple("arcoiris", "rainbow", "con arcoíris"),
        Triple("fuego|llamas", "fire, flames", "con fuego"),
        Triple("flores", "flowers", "con flores")
    )

    /** Cosas que se le pueden agregar («ponle lentes»). La IA es pequeña: puede no salir exacto. */
    private val things = phrases(
        Triple("lentes de sol|gafas de sol|anteojos de sol", "wearing sunglasses", "lentes de sol"),
        Triple("lentes|gafas|anteojos", "wearing glasses", "lentes"),
        Triple("sombrero", "wearing a hat", "sombrero"),
        Triple("gorro|gorra|jockey", "wearing a cap", "gorro"),
        Triple("corona", "wearing a crown", "corona"),
        Triple("barba", "with a beard", "barba"),
        Triple("bigote", "with a mustache", "bigote"),
        Triple("terno|traje", "wearing a suit", "traje"),
        Triple("corbata", "wearing a tie", "corbata"),
        Triple("sonrisa|sonriendo|que sonria", "smiling", "sonrisa"),
        Triple("pelo largo", "long hair", "pelo largo"),
        Triple("pelo rubio|rubi[oa]", "blonde hair", "pelo rubio"),
        Triple("gato", "with a cat", "un gato"),
        Triple("perro", "with a dog", "un perro"),
        Triple("mariposas", "butterflies", "mariposas"),
        Triple("globos", "balloons", "globos")
    )

    private fun hitsOf(list: List<Phrase>, p: String): List<Phrase> = list.filter { it.re.containsMatchIn(p) }

    /** ¿Pide una versión nueva / otro estilo / otra escena? Devuelve el paso (con el pedido en inglés para la IA). */
    private fun variation(p: String): Step? {
        // «blanco y negro» es un filtro, no una versión nueva (y no se confunde con colores de fondo)
        val q = p.replace(Regex("\\ben blanco y negro\\b"), " ")
        val askNew = Regex(
            "\\b(otra|nueva|una|distinta|diferente) version\\b|\\bversion (nueva|distinta|diferente|alternativa|mejorada con ia|con ia)\\b|" +
            "\\bvariaci(on|ones)\\b|\\bvariante\\b|\\b(rehaz|recrea|regenera|reimagina|reinventa|redibuja)\\w*|" +
            "\\b(otra|una) (imagen|foto|igual|parecida|similar)\\b.{0,30}\\b(de esa|de esta|de la misma|parecida|similar|igual|misma)\\b|" +
            "\\b(cambia|cambiala|cambiar|reemplaza|reemplazala)\\b.{0,25}\\bpor otra\\b|\\bpor otra (de esa|de esta|de la misma|parecida|igual)\\b|" +
            "\\bhaz(me|la)? otra\\b|\\bcrea(me)? otra\\b|\\bgenera(me)? otra\\b"
        ).containsMatchIn(q)
        val st = hitsOf(styles, q)
        val sc = hitsOf(scenes, q)
        val th = hitsOf(things, q)
        val transformVerb = Regex(
            "\\b(estilo|tipo|modo|efecto|look|aspecto|version|como si fuera|como un|como una|que parezca|parezca)\\b|" +
            "\\b(conviert|transform|vuelv|pas|haz|hac|pon|dej|cambi|recrea|redibuj|dibuj|pint|agreg|anad|sum|incluy)\\w*\\b|\\bque se vea\\b"
        ).containsMatchIn(q)
        val addVerb = Regex("\\b(ponle|ponerle|agregale|agregarle|anadele|anadirle|dale|hazle|que tenga|con)\\b").containsMatchIn(q)
        val styleAsk = st.isNotEmpty() && transformVerb
        val sceneAsk = sc.isNotEmpty() && (transformVerb || askNew)
        val thingAsk = th.isNotEmpty() && addVerb
        if (!askNew && !styleAsk && !sceneAsk && !thingAsk) return null
        // Fondo con color («fondo blanco») o tamaños no son versiones nuevas
        if (!askNew && Regex("\\bfondo\\b").containsMatchIn(q) && st.isEmpty()) return null
        val en = (st + sc + th).map { it.en }.distinct()
        val es = (st + sc + th).map { it.es }.distinct()
        val subtle = Regex("\\b(poco|leve|levemente|sutil|ligera|ligeramente|parecida|parecido|similar|casi igual|muy parecida)\\b").containsMatchIn(q)
        val strong = Regex("\\b(muy distinta|muy diferente|totalmente|completamente|bastante|mucho|radical|bien distinta|bien diferente)\\b").containsMatchIn(q)
        var strength = when {
            strong -> 0.72f
            subtle -> 0.35f
            st.isNotEmpty() -> 0.62f
            sc.isNotEmpty() || th.isNotEmpty() -> 0.58f
            else -> 0.5f
        }
        if (subtle && st.isNotEmpty()) strength = 0.48f
        return Step(Kind.VARIATION, prompt = en.joinToString(", "), what = es.joinToString(", "), strength = strength)
    }

    // ---------------------------------------------------------------- análisis
    /**
     * Devuelve los pasos a hacer (en el orden en que los pediste), o null si el mensaje no es para editar la imagen.
     * @param images cuántas imágenes vienen (1 = la foto, 2 = foto + persona/fondo nuevo)
     */
    fun parse(prompt: String, images: Int): List<Step>? {
        val p = norm(prompt)
        if (p.isBlank()) return null
        // ❓ Preguntas sobre la imagen («¿qué ves?», «describe la foto») no son ediciones
        if (Regex("^(que|quien|quienes|cual|cuales|como|cuanto|cuantos|donde|por que|porque|describe|describeme|explica|explicame|analiza|lee|traduce|dime)\\b").containsMatchIn(p) &&
            !Regex("\\b(recorta\\w*|cambia\\w*|quita\\w*|pon\\w*|agranda\\w*|aumenta\\w*|sube|baja|reduce|reducela|convierte\\w*|transforma\\w*|parezca|vea|luzca|haz\\w*|version|estilo)\\b").containsMatchIn(p)) return null
        replacePerson(p, images)?.let { return listOf(it) }

        // Varias cosas juntas: «quítale el fondo y recórtala cuadrada» (el fondo y la versión nueva se entienden con la frase completa)
        val parts = p.split(Regex("\\s*(,|;|\\.|\\by luego\\b|\\by despues\\b|\\bluego\\b|\\bdespues\\b|\\by tambien\\b|\\by ademas\\b|\\by\\b)\\s*"))
            .map { it.trim() }.filter { it.length > 2 }
        val steps = mutableListOf<Step>()
        var bgAt = -1
        var varAt = -1
        val fullVariation = variation(p)
        for (part in parts) {
            if (Regex("\\b(fondo|background)\\b").containsMatchIn(part) && variation(part)?.let { hitsOf(styles, part).isNotEmpty() } != true) {
                if (bgAt < 0) bgAt = steps.size; continue
            }
            if (fullVariation != null && (variation(part) != null || hitsOf(styles, part).isNotEmpty() || hitsOf(scenes, part).isNotEmpty() ||
                    hitsOf(things, part).isNotEmpty())) {
                if (varAt < 0) varAt = steps.size; continue
            }
            one(part)?.let { s -> if (steps.none { it.kind == s.kind }) steps += s }
        }
        val bg = if (bgAt >= 0) background(p, images) else null
        // Se insertan en el lugar donde los pediste
        val inserts = listOfNotNull(bg?.let { bgAt to it }, (if (varAt >= 0) fullVariation else null)?.let { varAt to it })
            .sortedByDescending { it.first }
        for ((at, s) in inserts) steps.add(at.coerceAtMost(steps.size), s)
        // «ponle fondo de playa y hazla estilo anime»: la versión nueva ya incluye la escena (no se pide otra foto)
        if (steps.any { it.kind == Kind.VARIATION }) steps.removeAll { it.kind == Kind.ASK_BACKGROUND }
        return steps.takeIf { it.isNotEmpty() }
    }

    /** 🔄 «Cambia a la persona por la de la otra foto». */
    private fun replacePerson(p: String, images: Int): Step? {
        val beforePor = p.substringBefore(" por ")
        val replace = Regex("\\b(cambia\\w*|reemplaz\\w*|remplaz\\w*|sustitu\\w*|intercambi\\w*|swap)\\b.{0,50}\\b$PERSON\\b").containsMatchIn(p) ||
            Regex("\\b$PERSON\\b.{0,40}\\b(por|con) (la|el|esta|este|la de la|el de la|la persona de la)\\s?(otra|segunda|nueva|siguiente)\\b").containsMatchIn(p) ||
            Regex("\\b(pon|ponle|ponme|coloca\\w*|mete\\w*|pega\\w*|inserta\\w*|agrega\\w*)\\b.{0,30}\\b$PERSON\\b.{0,40}\\b(en|dentro de) (la|esta|esa|mi) (otra |primera |segunda )?(foto|imagen)").containsMatchIn(p)
        if (!replace || Regex("\\bfondo\\b").containsMatchIn(beforePor)) return null
        if (images < 2) return Step(Kind.ASK_SECOND_IMAGE)
        val swap = Regex("\\b$PERSON (de|que esta en|que sale en|que aparece en) la (primera|1ra|1era|foto de arriba)\\b").containsMatchIn(p)
        return Step(Kind.REPLACE_PERSON, swapImages = swap)
    }

    /** 🎨 Fondo (se mira la frase completa: «cambia el fondo negro a blanco»). */
    private fun background(p: String, images: Int): Step? {
        if (!Regex("\\b(fondo|background)\\b").containsMatchIn(p)) return null
        val blur = Regex("\\b(borros\\w*|desenfoc\\w*|difumin\\w*|bokeh|blur|modo retrato)\\b").containsMatchIn(p)
        if (blur) return Step(Kind.BACKGROUND, bgMode = BgMode.BLUR)
        val transparent = Regex("\\b(transparente|png|sin fondo)\\b").containsMatchIn(p)
        val color = if (transparent) null else targetColor(p)
        if (color != null) return Step(Kind.BACKGROUND, bgMode = BgMode.COLOR, color = color.first, colorName = color.second)
        val remove = transparent || Regex("\\b(quit\\w*|borr\\w*|elimin\\w*|sac\\w*|remueve|remover|recort\\w*)\\b.{0,20}\\b(fondo|background)\\b").containsMatchIn(p)
        if (images >= 2 && !remove) return Step(Kind.BACKGROUND, bgMode = BgMode.IMAGE)
        if (remove) return Step(Kind.BACKGROUND, bgMode = BgMode.TRANSPARENT)
        if (Regex("\\b(cambi\\w*|pon\\w*|reemplaz\\w*|sustitu\\w*|nuevo|otro|distinto|diferente)\\b").containsMatchIn(p)) return Step(Kind.ASK_BACKGROUND)
        return null
    }

    /** Un solo pedido que no es de fondo ni de versión nueva. */
    private fun one(p: String): Step? {
        // 🧽 Quitar a la persona
        if (Regex("\\b(quita|quitale|quitarle|quitar|borra|borrale|borrar|elimina|eliminale|eliminar|saca|sacale|sacar|desaparece|desaparecer|remueve|remover)\\b\\s+(a\\s+)?(la|el|las|los|esa|ese|esta|este|aquella|aquel|al|todas las|todos los)?\\s*$PERSON\\b").containsMatchIn(p))
            return Step(Kind.REMOVE_PERSON, allSubjects = Regex("\\b(gente|personas|hombres|mujeres|ninos|ninas|chicos|chicas|todos|todas|muchachos)\\b").containsMatchIn(p))

        // ✂️ Recortar
        if (Regex("\\b(recort\\w*|cort(a|ala|ale|ar|arla|en|ame)\\b|encuadr\\w*|reencuadr\\w*|crop)").containsMatchIn(p) ||
            Regex("\\b(quita\\w*|elimina\\w*|saca\\w*) (los |el )?(borde|bordes|margen|margenes|espacio sobrante|sobrante)\\b").containsMatchIn(p)) {
            val exact = exactSize(p)
            val ratio = Regex("\\b(\\d{1,2})\\s?[:/x]\\s?(\\d{1,2})\\b").find(p)?.let { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                if (a in 1..32 && b in 1..32) a to b else null
            }
            val pct = percentIn(p)
            return when {
                exact != null -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = exact.first, ratioH = exact.second, exactW = exact.first, exactH = exact.second)
                ratio != null -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = ratio.first, ratioH = ratio.second)
                Regex("\\b(cuadrad\\w*|perfil|avatar)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = 1, ratioH = 1)
                Regex("\\b(historia|historias|estado|estados|story|stories|reel|reels|tiktok|shorts|fondo de pantalla|wallpaper|celular)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = 9, ratioH = 16)
                Regex("\\binstagram\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = 4, ratioH = 5)
                Regex("\\b(horizontal|panoramic\\w*|portada|banner|youtube|miniatura|pantalla ancha|cine)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = 16, ratioH = 9)
                Regex("\\bvertical\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RATIO, ratioW = 3, ratioH = 4)
                Regex("\\b(mitad|parte) (de )?(arriba|superior)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.TOP)
                Regex("\\b(mitad|parte) (de )?(abajo|inferior)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.BOTTOM)
                Regex("\\b(mitad|parte|lado) (de la |del lado )?izquierd\\w*").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.LEFT)
                Regex("\\b(mitad|parte|lado) (de la |del lado )?derech\\w*").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.RIGHT)
                pct != null -> Step(Kind.CROP, cropMode = CropMode.PERCENT, percent = pct.coerceIn(1, 45))
                Regex("\\b(centro|al medio|el medio)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.CENTER)
                Regex("\\b(borde|bordes|margen|margenes|sobrante)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.AUTO)
                Regex("\\b($PERSON|objeto|producto|logo|logotipo|animal|perro|gato|mascota|auto|carro|coche|principal|sujeto|solo)\\b").containsMatchIn(p) -> Step(Kind.CROP, cropMode = CropMode.SUBJECT)
                else -> Step(Kind.CROP, cropMode = CropMode.AUTO)
            }
        }

        val exact = exactSize(p)
        val pct = percentIn(p)
        val named = namedLongSide(p)
        val fac = factorIn(p)
        // 📉 Bajar resolución / achicar
        val wantsBetter = Regex("\\b(mejor\\w*|aument\\w*|sube|subir|subele|mas)\\b.{0,15}\\b(resolucion|calidad|definicion|nitidez)\\b").containsMatchIn(p)
        val down = !wantsBetter && (
            Regex("\\b(baj\\w*|reduc\\w*|disminu\\w*|menos|menor|achic\\w*|minimiz\\w*|comprim\\w*)\\b.{0,25}\\b(resolucion|calidad|peso|tamano|pixeles|megapixeles|mb)\\b").containsMatchIn(p) ||
            Regex("\\b(baja|menor|poca) resolucion\\b|\\b(comprim\\w*|mas livian\\w*|pese menos|mas pequen\\w*|achica\\w*|reducela|reducir|reduce|mas chica|mas chico|la mitad)\\b").containsMatchIn(p))
        if (down) return Step(Kind.DOWNSCALE, factor = if (fac in 0.01f..0.99f) fac else if (pct != null && pct < 100) pct / 100f else 0f,
            longSide = named, exactW = exact?.first ?: 0, exactH = exact?.second ?: 0)
        // 📈 Subir resolución / agrandar (con IA)
        val up = wantsBetter ||
            Regex("\\b(aument\\w*|sub\\w*|increment\\w*|eleva\\w*|mayor|max\\w*|alta|super)\\b.{0,20}\\b(resolucion|definicion)\\b").containsMatchIn(p) ||
            Regex("\\b(super ?resolucion|alta resolucion|resolucion alta|upscale|reescal\\w*|mas nitid\\w*|nitid\\w*|mas definid\\w*|en hd|a hd|full ?hd|4k|2k|mejora\\w* (la )?calidad|mas calidad|agrand\\w*|ampli\\w*|mas grande|duplica\\w*|al doble|el doble|triple|cuadruple)\\b").containsMatchIn(p) ||
            (Regex("\\b(x\\s?[2-8]|[2-8]\\s?x)\\b").containsMatchIn(p) && Regex("\\b(resolucion|tamano|grande|imagen|foto|escala\\w*|pasala|ponla|dejala|hazla)\\b").containsMatchIn(p))
        if (up) return Step(Kind.UPSCALE, factor = if (fac >= 1f) fac else if (pct != null && pct > 100) pct / 100f else 0f,
            longSide = named, exactW = exact?.first ?: 0, exactH = exact?.second ?: 0)
        // «Pásala a 1920x1080» / «al 150 %» / «en 4k»: tamaño exacto (sube con IA si hace falta)
        if ((exact != null || named > 0 || (pct != null && pct != 100)) &&
            Regex("\\b(resolucion|tamano|redimensiona\\w*|pasala|ponla|dejala|hazla|convierte\\w*|convertir|escala\\w*|cambia\\w*)\\b").containsMatchIn(p)) {
            val bigger = if (pct != null) pct > 100 else true
            return Step(if (bigger) Kind.UPSCALE else Kind.DOWNSCALE, factor = if (pct != null) pct / 100f else 0f,
                longSide = named, exactW = exact?.first ?: 0, exactH = exact?.second ?: 0)
        }
        return null
    }

    // ---------------------------------------------------------------- 🖍️ foto MARCADA (círculo, flecha, raya)
    /** Qué hacer con lo marcado: borrarlo, editarlo (filtro o color), agregar algo ahí o reemplazarlo por otra cosa. */
    enum class MarkAction { ERASE, EDIT, ADD, REPLACE }
    enum class MarkFilter { NONE, BRIGHTER, DARKER, GRAY, BLUR, PIXELATE, COLOR }

    /** @param what lo que hay que agregar o poner (en español, para el chat) · @param en lo mismo en inglés si se conoce */
    data class MarkEdit(val action: MarkAction, val what: String = "", val en: String = "",
                        val filter: MarkFilter = MarkFilter.NONE, val color: Int = 0, val colorName: String = "")

    /** «que encerré en rojo», «con la flecha», «donde puse la flecha», «lo marcado» (la marca no es parte del pedido) */
    private val markPhrase = Regex("\\s*\\b(?:(?:que|donde|en donde|adonde)\\s+)?(?:te\\s+|le\\s+)?(?:he\\s+)?" +
        "(marque|marcaste|encerre|circule|tache|subraye|senale|apunte|indique|puse|dibuje|raye|ralle|hice)\\b" +
        "(?:\\s+(?:(?:en|con|de)\\s+)?(?:un|una|el|la)?\\s*(?:circulo|circulito|flecha|flechita|raya|ralla|rayita|rallita|linea|marca|lapiz)s?)?" +
        "(?:\\s+(?:en|de|con)?\\s*(?:color\\s+)?(?:roj[oa]|azul|verde|amarill[oa]|negr[oa]|blanc[oa]|naranj[oa]|morad[oa]|rosad[oa]|celeste))?" +
        "|\\s*\\b(?:con|en)\\s+(?:un|una|el|la)\\s+(?:circulo|circulito|flecha|flechita|raya|ralla|rayita|linea|marca)s?" +
        "(?:\\s+(?:de\\s+)?(?:color\\s+)?(?:roj[oa]|azul|verde|amarill[oa]|negr[oa]|blanc[oa]|naranj[oa]|morad[oa]|rosad[oa]|celeste))?\\b" +
        "|\\s*\\b(?:lo|la parte|el lugar|la zona|el espacio)\\s+(?:marcad[oa]|encerrad[oa]|senalad[oa]|tachad[oa]|rayad[oa]|circulad[oa])\\b" +
        "|\\s*\\b(?:en|de)\\s+(?:la|esta|esa)\\s+(?:foto|imagen|captura)\\b")

    /** Quita la marca del pedido: «borra lo que encerré en rojo» → «borra lo». */
    fun withoutMark(prompt: String): String = norm(prompt).replace(markPhrase, " ").replace(Regex("\\s+"), " ").trim()

    /** Cosas comunes que se pueden poner en una foto (para la IA de imágenes, que entiende inglés). */
    private val objects = phrases(
        Triple("arbol|arboles", "a tree", "un árbol"), Triple("flor", "a flower", "una flor"), Triple("flores", "flowers", "flores"),
        Triple("planta|plantas", "a plant", "una planta"), Triple("pasto|cesped", "green grass", "pasto"), Triple("arbusto", "a bush", "un arbusto"),
        Triple("piedra|roca", "a rock", "una piedra"), Triple("montana|cerro", "a mountain", "una montaña"), Triple("nube|nubes", "clouds", "nubes"),
        Triple("sol", "the sun", "el sol"), Triple("luna", "the moon", "la luna"), Triple("estrella|estrellas", "stars", "estrellas"),
        Triple("cielo", "blue sky", "cielo"), Triple("agua|lago|rio", "water", "agua"), Triple("mar|oceano", "the sea", "el mar"),
        Triple("arena|playa", "sand beach", "arena"), Triple("nieve", "snow", "nieve"), Triple("casa", "a house", "una casa"),
        Triple("edificio", "a building", "un edificio"), Triple("ventana", "a window", "una ventana"), Triple("puerta", "a door", "una puerta"),
        Triple("pared|muro", "a wall", "una pared"), Triple("auto|carro|coche|vehiculo", "a car", "un auto"), Triple("moto|motocicleta", "a motorcycle", "una moto"),
        Triple("bicicleta|bici", "a bicycle", "una bicicleta"), Triple("avion", "an airplane", "un avión"), Triple("barco|bote", "a boat", "un barco"),
        Triple("perro|perrito", "a dog", "un perro"), Triple("gato|gatito", "a cat", "un gato"), Triple("pajaro|ave", "a bird", "un pájaro"),
        Triple("caballo", "a horse", "un caballo"), Triple("pez|pescado", "a fish", "un pez"), Triple("mariposa", "a butterfly", "una mariposa"),
        Triple("persona|hombre|mujer", "a person", "una persona"), Triple("nino|nina", "a child", "un niño"), Triple("sombrero", "a hat", "un sombrero"),
        Triple("lentes|gafas|anteojos", "glasses", "lentes"), Triple("corona", "a crown", "una corona"), Triple("pelota|balon", "a ball", "una pelota"),
        Triple("mesa", "a table", "una mesa"), Triple("silla", "a chair", "una silla"), Triple("sillon|sofa", "a sofa", "un sillón"),
        Triple("lampara", "a lamp", "una lámpara"), Triple("cuadro", "a framed painting", "un cuadro"), Triple("reloj", "a clock", "un reloj"),
        Triple("globo|globos", "balloons", "globos"), Triple("torta|pastel", "a cake", "una torta"), Triple("regalo", "a gift box", "un regalo"),
        Triple("corazon", "a heart", "un corazón"), Triple("arcoiris", "a rainbow", "un arcoíris"), Triple("fuego", "fire", "fuego"),
        Triple("letrero|cartel", "a sign", "un letrero"), Triple("camino|sendero", "a path", "un camino"), Triple("banco", "a bench", "un banco"),
        Triple("farol|poste", "a street lamp", "un farol"), Triple("fruta|frutas", "fruits", "fruta"), Triple("manzana", "an apple", "una manzana")
    )

    /** El pedido en inglés para la IA de imágenes, si son cosas conocidas (si no, null: MainActivity lo traduce con la IA del chat). */
    fun englishFor(whatEs: String): String? {
        val q = norm(whatEs)
        // (primero las cosas: «un perro» = «a dog»; si no hay, los accesorios, estilos y escenas)
        val hits = hitsOf(objects, q).ifEmpty { hitsOf(things, q) + hitsOf(styles, q) + hitsOf(scenes, q) }.map { it.en }.distinct()
        if (hits.isEmpty()) return null
        val color = colorHits(q).firstOrNull()?.let { c -> listOf("blanco" to "white", "negro" to "black", "rojo" to "red", "azul" to "blue",
            "verde" to "green", "amarillo" to "yellow", "naranjo" to "orange", "rosado" to "pink", "morado" to "purple", "gris" to "gray",
            "café" to "brown", "celeste" to "light blue", "dorado" to "golden").firstOrNull { it.first == c.name }?.second }
        return hits.joinToString(", ") + (if (color != null) ", $color color" else "")
    }

    /**
     * 🖍️ «borra lo que encerré», «cambia lo que marqué por un árbol», «agrega una flor donde puse la flecha»,
     * «pon en blanco y negro lo marcado», «pinta de azul lo que encerré»… null = no es un pedido para lo marcado.
     */
    fun markEdit(prompt: String): MarkEdit? {
        val c = withoutMark(prompt)
        if (c.isBlank()) return null
        // ❓ Una pregunta («¿qué es esto?») no es una edición
        if (Regex("^(que|quien|cual|cuales|como|cuanto|cuantos|donde|por que|describe|describeme|explica|explicame|dime|traduce|lee)\\b").containsMatchIn(c) &&
            !Regex("\\b(borra\\w*|quita\\w*|cambia\\w*|agrega\\w*|pon\\w*|pinta\\w*|reemplaza\\w*)\\b").containsMatchIn(c)) return null
        // 🎚️ Filtros sobre lo marcado
        val filter = when {
            Regex("\\b(blanco y negro|escala de grises|en gris|sin color|descolor\\w*)\\b").containsMatchIn(c) -> MarkFilter.GRAY
            Regex("\\b(pixel\\w*|cuadritos)\\b").containsMatchIn(c) -> MarkFilter.PIXELATE
            Regex("\\b(borros\\w*|difumin\\w*|desenfoc\\w*|blur|censur\\w*|tapa\\w*|oculta\\w*)\\b").containsMatchIn(c) -> MarkFilter.BLUR
            Regex("\\b(aclar\\w*|ilumin\\w*|mas clar[oa]|mas luz|mas brillo|mas brillante)\\b").containsMatchIn(c) -> MarkFilter.BRIGHTER
            Regex("\\b(oscurec\\w*|mas oscur[oa]|menos luz|menos brillo)\\b").containsMatchIn(c) -> MarkFilter.DARKER
            else -> MarkFilter.NONE
        }
        if (filter != MarkFilter.NONE) return MarkEdit(MarkAction.EDIT, filter = filter)
        val replaceRx = Regex("\\b(cambi\\w*|reemplaz\\w*|remplaz\\w*|sustitu\\w*|convi[e]?rt\\w*|transform\\w*)\\b(.*?)\\b(por|en)\\s+(.+)$")
        val removeRx = Regex("\\b(quit\\w*|borr\\w*|elimin\\w*|sac(a|ale|alo|ala|ar)|remueve\\w*|desaparec\\w*|limpia\\w*)\\b")
        val addRx = Regex("\\b(agreg\\w*|anad\\w*|pon|ponle|ponme|ponlo|ponla|poner|coloca\\w*|pega\\w*|mete\\w*|inserta\\w*|dibuja\\w*|suma\\w*|incluye\\w*)\\b(.*)$")
        val paintRx = Regex("\\b(pinta\\w*|colorea\\w*|tine\\w*|tiñe\\w*|color)\\b")
        // «cámbialo por azul», «píntalo de azul», «cambia el color a verde»: es solo de COLOR
        val onlyColor = { t: String -> val r = norm(t).replace(Regex("\\b(un|una|el|la|de|color|a|al|por|en|lo|le|tono)\\b"), " ")
            val h = colorHits(r)
            // (solo es de color si, sacando los colores, no queda nada: «una casa blanca» es una casa)
            h.isNotEmpty() && h.sortedByDescending { it.start }.fold(r) { acc, x -> acc.removeRange(x.start, x.end) }.isBlank() }
        if (paintRx.containsMatchIn(c) || Regex("\\bcambi\\w*\\b.{0,20}\\bcolor\\b").containsMatchIn(c)) {
            colorHits(c).lastOrNull()?.let { return MarkEdit(MarkAction.EDIT, filter = MarkFilter.COLOR, color = it.argb, colorName = it.name) }
        }
        replaceRx.find(c)?.let { m ->
            val what = m.groupValues[4].trim().trimEnd('.', ',')
            if (onlyColor(what)) colorHits(what).last().let { return MarkEdit(MarkAction.EDIT, filter = MarkFilter.COLOR, color = it.argb, colorName = it.name) }
            if (what.length >= 2) return MarkEdit(MarkAction.REPLACE, what = what, en = englishFor(what).orEmpty())
        }
        if (removeRx.containsMatchIn(c)) return MarkEdit(MarkAction.ERASE)
        addRx.find(c)?.let { m ->
            val what = m.groupValues[2].replace(Regex("^\\s*(le|les|lo|la|me|aqui|aca|ahi|alli|alla|encima|arriba|abajo|al lado)\\b"), " ")
                .replace(Regex("\\b(aqui|aca|ahi|alli|alla|donde|en ese lugar|en ese espacio|en esa parte)\\b.*$"), " ")
                .replace(Regex("\\s+(a|al|en|sobre|encima de)\\s+(lo|la|el|esto|eso|esta|esa|este|ese)\\s*$"), " ").trim().trimEnd('.', ',')
            if (onlyColor(what)) colorHits(what).last().let { return MarkEdit(MarkAction.EDIT, filter = MarkFilter.COLOR, color = it.argb, colorName = it.name) }
            if (what.length >= 2) return MarkEdit(MarkAction.ADD, what = what, en = englishFor(what).orEmpty())
        }
        // «cambia esto», «modifícalo», «edítalo» sin decir cómo: se pregunta (MainActivity)
        if (Regex("\\b(cambi\\w*|modific\\w*|edit\\w*|arregl\\w*|mejor\\w*)\\b").containsMatchIn(c)) return MarkEdit(MarkAction.EDIT)
        return null
    }

    /** ¿El mensaje habla claramente de una imagen? (para editar la última imagen del chat sin adjuntarla de nuevo) */
    fun mentionsImage(prompt: String): Boolean {
        val p = norm(prompt)
        return Regex("\\b(imagen|imagenes|foto|fotos|fotografia|logo|dibujo|retrato|recortala|agrandala|achicala|reducela|ponla|pasala|dejala|hazla|cambiala|mejorala|quitale|ponle|cambiale|sacale|amplia|ampliala|conviertela|transformala|rehazla|recreala|version|variacion)\\b").containsMatchIn(p) ||
            Regex("\\b(fondo|resolucion|recort\\w*)\\b").containsMatchIn(p)
    }

    /** «otra», «otra vez», «de nuevo»: repetir la última versión nueva (sale distinta). */
    fun isAgain(prompt: String): Boolean =
        Regex("^(otra|otra mas|otra vez|otra version|otra diferente|otra distinta|una mas|de nuevo|denuevo|intentalo de nuevo|hazla de nuevo|hazlo de nuevo|repitela|repitelo|repite|otra porfa|otra por favor)$")
            .matches(norm(prompt).trim(' ', '.', ',', ';'))

    /** ¿Respondió que sí? («sí», «dale», «descárgala», «ok»). */
    fun isYes(prompt: String): Boolean =
        Regex("^(si|sii+|yes|ok|okay|okey|dale|ya|bueno|claro|de acuerdo|hazlo|descargala|descargalo|bajala|bajalo|si,? (descargala|descargalo|dale|por favor|hazlo|bajala)|si por favor)\\b").containsMatchIn(norm(prompt))

    /** ¿Respondió que no? */
    fun isNo(prompt: String): Boolean =
        Regex("^(no|nop|nopo|no gracias|mejor no|despues|ahora no|cancela)\\b").containsMatchIn(norm(prompt))
}
