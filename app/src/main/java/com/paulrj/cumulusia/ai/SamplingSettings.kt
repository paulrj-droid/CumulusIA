package com.paulrj.cumulusia.ai

/**
 * 🎛️ AJUSTES DEL MODELO que eliges en ⚙️ → Configuración del modelo
 *  (Top K, Top P, Min-P, stop tokens y plantilla de chat). Los usan los tres motores:
 *  💻 CPU (llama.cpp) · ⚡🎮 GenieX (NPU/GPU) · LiteRT-LM (.litertlm).
 *  La temperatura y el contexto siguen guardándose como antes.
 */
object SamplingSettings {

    /** Temperatura (la misma de ⚙️; se copia aquí para LiteRT-LM). */
    @Volatile var temperature: Float = 0.7f
    /** Top K: cuántas palabras candidatas se miran (0 = sin límite). */
    @Volatile var topK: Int = 40
    /** Top P: probabilidad acumulada máxima (1.0 = sin límite). */
    @Volatile var topP: Float = 0.95f
    /** Min-P: descarta las palabras con menos de este % de la más probable (0 = apagado). */
    @Volatile var minP: Float = 0.05f
    /** Textos donde la IA debe dejar de escribir (además de los que ya usa la app). */
    @Volatile var stops: List<String> = emptyList()
    /** Plantilla de chat EFECTIVA del modelo activo: "auto" = la que trae el archivo del modelo. */
    @Volatile var chatTemplate: String = AUTO
    /** El modelo activo tiene pensamiento interno que se puede apagar (Qwen3…): se apaga, como antes. */
    @Volatile var noThink: Boolean = false
    /** Fin de turno que declara el PROPIO GGUF (sus tokens EOS / EOT, por ejemplo <|im_end|> o <|eot_id|>). */
    @Volatile var modelStops: List<String> = emptyList()

    const val AUTO = ChatTemplateFormatter.AUTO

    fun templateName(key: String = chatTemplate): String = ChatTemplateFormatter.label(key)

    /** «<|im_end|>, ###, \n\n» → lista (\n escrito como texto = salto de línea). */
    fun parseStops(text: String): List<String> =
        text.split(',')
            .map { it.trim(' ').replace("\\n", "\n").replace("\\t", "\t") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(16)

    /** Para mostrar debajo del campo: «Ninguno» o «3: <|im_end|> · ### · ↵↵». */
    fun stopsLabel(list: List<String> = stops): String =
        if (list.isEmpty()) "Ninguno"
        else "${list.size}: " + list.joinToString(" · ") { it.replace("\n", "↵").replace("\t", "⇥") }

    /** Corta la respuesta en el primer stop token que aparezca. */
    fun cutAtStops(text: String, list: List<String> = stops): String {
        var cut = -1
        for (s in list) {
            val i = text.indexOf(s)
            if (i >= 0 && (cut < 0 || i < cut)) cut = i
        }
        return if (cut >= 0) text.substring(0, cut).trimEnd() else text
    }

    /** Marcas de fin de turno de la plantilla + las que declara el propio modelo (así no sigue escribiendo el turno siguiente). */
    fun templateStops(key: String = chatTemplate): List<String> = (ChatTemplateFormatter.stopsFor(key) + modelStops).distinct()

    /**
     * Arma el texto de la conversación con la plantilla del modelo activo (ChatTemplateFormatter).
     * null = «auto»: se usa la plantilla que viene dentro del archivo del modelo.
     */
    fun formatPrompt(system: String, roles: List<String>, contents: List<String>, key: String = chatTemplate): String? =
        ChatTemplateFormatter.format(key, system, roles, contents, noThink)
}
