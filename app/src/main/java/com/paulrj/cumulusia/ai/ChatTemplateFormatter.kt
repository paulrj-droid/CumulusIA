package com.paulrj.cumulusia.ai

/**
 * 💬 FORMATEADOR DE CHAT TEMPLATES
 * Envuelve el system prompt, el historial y el mensaje nuevo con las etiquetas EXACTAS de cada familia
 * de modelos. Cada plantilla usa solo sus propias etiquetas (nunca se mezclan) y trae sus propios
 * stop tokens de fin de turno.
 *
 * Las claves son las mismas que entiende llama.cpp («chatml», «llama3», «gemma», «phi3», «phi4»,
 * «mistral-v7», «deepseek3»), así los tres motores hablan el mismo idioma.
 * El BOS (<|begin_of_text|>, <bos>…) NO se escribe aquí: lo agrega el motor al convertir a tokens.
 */
object ChatTemplateFormatter {

    /** «auto» = no se formatea en la app: el motor usa la plantilla que viene dentro del archivo del modelo. */
    const val AUTO = "auto"

    class Template(val key: String, val label: String, val stops: List<String>)

    val TEMPLATES: List<Template> = listOf(
        Template("chatml", "ChatML (Qwen, SmolLM, Hermes, LFM…)", listOf("<|im_end|>", "<|im_start|>")),
        Template("llama3", "Llama 3", listOf("<|eot_id|>", "<|start_header_id|>")),
        Template("gemma", "Gemma", listOf("<end_of_turn>", "<start_of_turn>")),
        Template("phi3", "Phi-3 / Phi-4 mini", listOf("<|end|>", "<|user|>", "<|endoftext|>")),
        Template("phi4", "Phi-4", listOf("<|im_end|>", "<|im_start|>")),
        Template("mistral-v7", "Mistral", listOf("</s>", "[INST]")),
        Template("deepseek3", "DeepSeek", listOf("<｜end▁of▁sentence｜>", "<｜User｜>"))
    )

    fun isKnown(key: String?): Boolean = key != null && TEMPLATES.any { it.key == key }

    fun label(key: String?): String = TEMPLATES.firstOrNull { it.key == key }?.label ?: "La del archivo del modelo"

    /** Stop tokens de fin de turno de esa plantilla (vacío si es «auto»: el motor usa los del modelo). */
    fun stopsFor(key: String?): List<String> = TEMPLATES.firstOrNull { it.key == key }?.stops ?: emptyList()

    /**
     * Arma el texto completo. roles: "user" / "assistant" (el último es el mensaje nuevo del usuario).
     * null = plantilla «auto» o desconocida: que el motor use la del modelo.
     */
    /**
     * @param noThink modelos con pensamiento interno que se puede apagar (Qwen3 y similares, su plantilla trae
     *                «enable_thinking»): se deja el bloque <think> vacío, igual que hace su plantilla oficial al apagarlo.
     */
    fun format(key: String?, system: String, roles: List<String>, contents: List<String>, noThink: Boolean = false): String? {
        if (!isKnown(key)) return null
        val msgs = roles.indices.map { (if (roles[it] == "assistant") "assistant" else "user") to contents.getOrElse(it) { "" } }
        val sb = StringBuilder()
        when (key) {
            "chatml" -> {
                if (system.isNotBlank()) sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for ((r, c) in msgs) sb.append("<|im_start|>").append(r).append('\n').append(c).append("<|im_end|>\n")
                sb.append("<|im_start|>assistant\n")
                if (noThink) sb.append("<think>\n\n</think>\n\n")
            }
            "llama3" -> {
                if (system.isNotBlank()) sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(system.trim()).append("<|eot_id|>")
                for ((r, c) in msgs) sb.append("<|start_header_id|>").append(r).append("<|end_header_id|>\n\n").append(c.trim()).append("<|eot_id|>")
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            "gemma" -> {
                // Gemma no tiene rol «system»: va al comienzo del primer mensaje del usuario (igual que su plantilla oficial)
                var sysPending = system.isNotBlank()
                for ((r, c) in msgs) {
                    val role = if (r == "assistant") "model" else "user"
                    val body = if (sysPending && role == "user") { sysPending = false; system.trim() + "\n\n" + c.trim() } else c.trim()
                    sb.append("<start_of_turn>").append(role).append('\n').append(body).append("<end_of_turn>\n")
                }
                sb.append("<start_of_turn>model\n")
            }
            "phi3" -> {
                if (system.isNotBlank()) sb.append("<|system|>\n").append(system).append("<|end|>\n")
                for ((r, c) in msgs) sb.append("<|").append(r).append("|>\n").append(c).append("<|end|>\n")
                sb.append("<|assistant|>\n")
            }
            "phi4" -> {
                if (system.isNotBlank()) sb.append("<|im_start|>system<|im_sep|>").append(system).append("<|im_end|>")
                for ((r, c) in msgs) sb.append("<|im_start|>").append(r).append("<|im_sep|>").append(c).append("<|im_end|>")
                sb.append("<|im_start|>assistant<|im_sep|>")
            }
            "mistral-v7" -> {
                if (system.isNotBlank()) sb.append("[SYSTEM_PROMPT]").append(system).append("[/SYSTEM_PROMPT]")
                for ((r, c) in msgs) {
                    if (r == "user") sb.append("[INST]").append(c).append("[/INST]") else sb.append(c).append("</s>")
                }
            }
            "deepseek3" -> {
                if (system.isNotBlank()) sb.append(system)
                for ((r, c) in msgs) {
                    if (r == "user") sb.append("<｜User｜>").append(c) else sb.append("<｜Assistant｜>").append(c).append("<｜end▁of▁sentence｜>")
                }
                sb.append("<｜Assistant｜>")
            }
        }
        return sb.toString()
    }

    /**
     * Reconoce la familia por la plantilla que viene dentro del GGUF (tokenizer.chat_template),
     * y si no trae, por la arquitectura o el nombre. null = no se reconoce (se usa la del modelo).
     */
    fun detect(embeddedTemplate: String?, architecture: String?, name: String?): String? {
        val t = embeddedTemplate.orEmpty()
        when {
            t.contains("<｜User｜>") || t.contains("<｜Assistant｜>") -> return "deepseek3"
            t.contains("<|im_sep|>") -> return "phi4"
            t.contains("<|start_header_id|>") -> return "llama3"
            t.contains("<start_of_turn>") -> return "gemma"
            t.contains("<end_of_utterance>") -> return null      // SmolVLM: parece ChatML pero no lo es
            t.contains("<|im_start|>") -> return "chatml"
            t.contains("<|assistant|>") && t.contains("<|end|>") -> return "phi3"
            t.contains("[SYSTEM_PROMPT]") -> return "mistral-v7"
        }
        if (t.isNotBlank()) return null   // trae una plantilla propia que no es de ninguna familia conocida: se respeta
        val a = architecture.orEmpty().lowercase()
        val n = name.orEmpty().lowercase()
        return when {
            n.contains("deepseek") && (n.contains("r1") || n.contains("v3")) -> "deepseek3"
            a.startsWith("qwen") || n.contains("qwen") || n.contains("smollm") || a.startsWith("lfm") -> "chatml"
            a.startsWith("gemma") || n.contains("gemma") -> "gemma"
            n.contains("phi-4") && !n.contains("mini") -> "phi4"
            a.startsWith("phi3") || n.contains("phi-3") || n.contains("phi3") || n.contains("phi-4-mini") -> "phi3"
            n.contains("llama-3") || n.contains("llama3") -> "llama3"
            else -> null   // Mistral antiguos, Llama 2 y otros: se usa la plantilla del archivo
        }
    }
}

