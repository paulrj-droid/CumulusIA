#pragma once
// ============================================================================
// 💬 PLANTILLA DE CHAT DEL PROPIO GGUF (Jinja)
//    Cada GGUF trae dentro su plantilla oficial (tokenizer.chat_template, escrita en Jinja) con las
//    etiquetas exactas con las que fue entrenado. Aquí se lee y se ejecuta tal cual, con el motor
//    Jinja de llama.cpp: sirve para CUALQUIER familia (Qwen, Llama, Gemma, Phi, Mistral, DeepSeek,
//    Granite, LFM, SmolLM, GLM, Command-R…), no solo para las conocidas.
// ============================================================================
#include <memory>
#include <string>
#include <vector>

namespace cumulus {

struct JinjaTemplate;   // plantilla ya leída y lista para usar

// Lee la plantilla. nullptr si no se pudo (el motor usa su método anterior). err: el motivo.
std::shared_ptr<JinjaTemplate> jinjaCompile(const std::string& source, const std::string& bosToken,
                                            const std::string& eosToken, std::string* err);

// Arma la conversación (system + historial + mensaje nuevo) con las etiquetas del modelo.
// roles: "user"/"assistant". enableThinking=false apaga el pensamiento en plantillas que lo permiten (Qwen3…).
bool jinjaRender(const JinjaTemplate& t, const std::string& system, const std::vector<std::string>& roles,
                 const std::vector<std::string>& contents, bool addGenerationPrompt, bool enableThinking,
                 std::string& out, std::string* err);

// Resumen para el registro: «sistema: sí · texto: string · pensamiento: sí»
std::string jinjaInfo(const JinjaTemplate& t);

}  // namespace cumulus

